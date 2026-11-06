"""The outer loop: what supply temperature this house wants right now.

Cascade control, which is what a mixing valve wants. The slow loop turns
weather and indoor temperature into a supply temperature setpoint; the fast
loop (pid.py) turns the error in that setpoint into a valve position. They
are separate because they answer to different clocks: the water responds in
a minute, the house in half a day.

The supply setpoint is built from four terms that are reported separately,
because "the valve is at 62%" is not a diagnosis:

    curve      weather compensation, self-learning slope and offset
  + room       proportional correction from the fused indoor sensors
  + override   fixed lowering while the override contact is closed
  + delta      correction from how much heat the radiators actually take

The learning is the point of the last two. Weather compensation alone cannot
know that this particular house, with this insulation and these radiator
valves, needs half a degree more; and it cannot know that the wood stove is
lit. The curve terms learn the house over days, the delta term notices free
heat within the hour.

No Home Assistant imports: see model.py.
"""

from __future__ import annotations

import math

# The modes and the delta term's reasons for silence are vocabulary shared
# with texts.py, so they live in model.py; everything that reads a mode
# still imports it from here.
from .model import (
    DELTA_QUIET_DEMAND,
    DELTA_QUIET_LUKEWARM,
    DELTA_QUIET_OFF,
    DELTA_QUIET_SENSOR,
    EMERGENCY_FLOW,
    EMERGENCY_KINDS,
    EMERGENCY_VALVE,
    LEARN_CURVE,
    LEARN_DELTA,
    LEARN_GAIN,
    LEARN_SUN,
    LEARN_HOUSE,
    LEARN_TABLE,
    LEARN_VALVE_MAP,
    MODE_COAST,
    MODE_EMERGENCY,
    MODE_FAILSAFE,
    MODE_HEATING,
    MODE_HOLD,
    MODE_OFF,
    MODE_SUMMER,
    MODE_WARM,
    MODES_STOPPED,
    ROLE_SOLAR,
    TABLE_LEARNING_OFF,
    TABLE_NO_CHAIN,
    TABLE_NO_FIT,
    TABLE_STEERS,
    TABLE_SWITCHED_OFF,
    ControlInputs,
    ControlOutput,
    FusedSensor,
    LearnedState,
    SensorReading,
    Tuning,
    effective_curvature,
)
from . import curve
from .curvefit import CurveFit, fit_curve
from .forecast import Ensemble, outdoor_from_forecast
from .points import Bin, PointTable, Rules
from .pid import bumpless_integral, pid_step
from .sunlight import SUNLIGHT_PER_INDEX
from .thermal import Conditions, HouseModel, radiator_excess, solar_index
from .texts import MODE_REASONS, SWEEP_REASON, emergency_reason
from .valvemap import ValveMap


# The clock each cause of a cold house waits on, held at nought while the
# house is still under target after it, and the learning card's code then.
COLD_CLOCKS = {"sweep": "seconds_since_sweep", "coast": "seconds_since_coast",
               "shortfall": "seconds_since_shortfall", "off": "seconds_since_off"}
COLD_WHY = {"sweep": "sweep_cold", "coast": "coast_cold", "shortfall": "shortfall_cold",
            "off": "off_cold"}


def _deadbanded(error: float, deadband: float) -> float:
    """The error as the PID works on it (pid.pid_step's deadband)."""
    if deadband <= 0.0:
        return error
    if error > deadband:
        return error - deadband
    if error < -deadband:
        return error + deadband
    return 0.0


# How _evidence_gate judges the sun.
SUN_RULE_LIGHT, SUN_RULE_TERM, SUN_RULE_NONE = "light", "term", "none"


def sunlight_now(inputs: ControlInputs) -> float:
    """The sun this cycle, kW/m2 on the walls: the light the coordinator
    worked out for them (sunlight.Walls), or without walls the old guess
    from the sun's height and the cloud, put into the same unit. Never less
    than nothing; a light that is not a number is not known."""
    light = inputs.sunlight
    if light is not None and math.isfinite(light):
        return max(0.0, float(light))
    return SUNLIGHT_PER_INDEX * solar_index(inputs.sun_elevation, inputs.cloud)


def fuse_indoor(
    readings: list[SensorReading], tuning: Tuning, flow: float | None
) -> tuple[float | None, list[FusedSensor]]:
    """Three indoor sensors into the one number the outer loop regulates on.

    The two ceiling sensors and the sun room measure three different things,
    so they are not averaged as equals:

    * Each reading gets its fixed calibration offset, and a stratification
      term proportional to how far the supply temperature is above the room
      target - ceiling air gets hotter as the radiators do, and a flat
      offset would only be right at one flow temperature.
    * The sun room is clamped from above, not below. Clamped above so an
      afternoon of sun in one room cannot pull heat out of the north side of
      the house; not clamped below, because a room that is genuinely cold is
      the most important thing any of these sensors can say.

    Returns (fused temperature, one FusedSensor per reading given).
    """
    details: list[FusedSensor] = []

    for reading in readings:
        weight = max(0.0, reading.weight)
        value = None
        if reading.temperature is not None:
            value = float(reading.temperature) + reading.offset
            if reading.stratification and flow is not None:
                value -= reading.stratification * max(0.0, flow - tuning.room_target)
        details.append(
            FusedSensor(
                key=reading.key,
                role=reading.role,
                raw=reading.temperature,
                corrected=value,
                weight=weight,
                offset=reading.offset,
                active=reading.active,
                featured=reading.featured,
            )
        )

    contributing = [item for item in details if item.contributed]
    if not contributing:
        return None, details

    # The reference the sun room is measured against is the rest of the
    # house, so a sunny afternoon moves the reference hardly at all.
    others = [item for item in contributing if item.role != ROLE_SOLAR]
    pool = others or contributing
    reference = sum(item.corrected * item.weight for item in pool) / sum(
        item.weight for item in pool
    )

    total = 0.0
    total_weight = 0.0
    for item in contributing:
        value = item.corrected
        if item.role == ROLE_SOLAR and others:
            capped = min(value, reference + tuning.solar_clamp)
            item.clamped = capped < value
            value = capped
        total += value * item.weight
        total_weight += item.weight

    return total / total_weight, details


# The radiator warning goes up at Tuning.starved_share and comes down only
# below this fraction of it. A share hovering on the line would otherwise
# raise and drop the warning every few cycles - and the push to the parents
# that goes with it (alarms.py).
STARVED_RELEASE = 0.8


class HeatingController:
    """One house. Holds the tuning and the learned state, steps on a timer."""

    def __init__(
        self,
        tuning: Tuning,
        state: LearnedState | None = None,
        house: HouseModel | None = None,
        ensemble: Ensemble | None = None,
        valve_map: ValveMap | None = None,
        points: PointTable | None = None,
    ) -> None:
        self.tuning = tuning
        self.state = state or LearnedState(process_gain=tuning.gain_reference)
        # The building and the forecast sources live here rather than in
        # LearnedState, which would mean model.py importing the modules that
        # import it. They are persisted alongside it all the same.
        self.house = house or HouseModel()
        self.ensemble = ensemble or Ensemble()
        # Learning in the background only - see valvemap.py.
        self.valve_map = valve_map or ValveMap()
        # The curve table, its latest fit, and the house model's balance -
        # see points.py and curvefit.py. The fit is made again every
        # table_fit_seconds; until the first, there is none.
        self.points = points or PointTable()
        self.table_fit: CurveFit | None = None
        self.house_balance: tuple[float, float] | None = None
        self.house_night_balance: tuple[float, float] | None = None
        self._since_fit = float("inf")
        # Whether the radiator warning is up - see STARVED_RELEASE.
        self._starved = False
        self.state.retune(tuning)
        self._retune_learners()

    def apply_tuning(self, tuning: Tuning) -> None:
        """Swap in new tuning without losing what has been learned - but
        for the slope's trim when the installer's slope itself is turned:
        whoever turns the curve gets that curve, as on the EVR controller,
        and learning goes on from there. Kept, the trim would be taken off
        the new curve again: on 2026-10-15 the curve in use was 1.9 from a
        3.2 set and a trim of -1.3, and 1.9 set would have run at 0.3."""
        if abs(tuning.curve_slope - self.tuning.curve_slope) > 1e-9:
            self.state.slope_trim = 0.0
        # The table's fit and the house's balance from the new tuning at the
        # next cycle, not up to an hour later: the fit starts from the
        # installer's slope, and the card showed the old one as its proposal.
        self._since_fit = float("inf")
        self.tuning = tuning
        self.state.retune(tuning)
        self._retune_learners()

    def _retune_learners(self) -> None:
        self.house.retune(self.tuning.thermal_horizon_seconds)
        self.house.set_design(
            self.tuning.design_span, self.tuning.delta_design, self.tuning.thermal_sample_seconds
        )
        self.ensemble.retune(self.tuning.forecast_horizon_seconds)

    def burner_lead_seconds(self, seconds: float | None = None) -> float:
        """How long before heat is needed the burner has to be told to start.

        Set by the coordinator from the burner's own profile, because a
        pellet burner lighting takes half an hour and oil takes minutes -
        which is the whole reason the boiler and the shunt stop at different
        times rather than together.
        """
        if seconds is not None:
            self._burner_lead = float(seconds)
        return getattr(self, "_burner_lead", 1800.0)

    def step(self, inputs: ControlInputs) -> ControlOutput:
        tuning = self.tuning
        state = self.state
        out = ControlOutput()
        self._out = out

        # A cycle can be arbitrarily late - Home Assistant restarts, the
        # host swaps out - so the filters get the time that actually passed
        # while the PID gets a bounded dt. Handing a two-hour dt to the
        # integral would put the valve at an end stop on the first cycle
        # back.
        dt = max(0.0, min(float(inputs.dt), 24.0 * 3600.0))
        control_dt = min(dt, 4.0 * tuning.cycle_seconds)

        outdoor, outdoor_summer = self._outdoor(inputs, dt, out)
        indoor, indoor_slow = self._indoor(inputs, dt, out)
        self._timers(inputs, dt, out)
        # The level's memory runs on the clock, learning or not.
        state.level_fit.fade(dt)

        demand_temp = max(0.0, tuning.room_target - outdoor)
        demand = demand_temp / tuning.design_span

        curve_outdoor = self._curve_outdoor(inputs.outdoor, outdoor)
        curve_flow, effective_slope = self._curve(curve_outdoor)

        room_trim = 0.0
        if indoor is not None:
            gain = tuning.room_gain
            if state.recovering and indoor < tuning.room_target:
                # Full at the floor, plain at the target, a straight line
                # between: double all the way to the target overshoots
                # (Fredrik 2026-10-10). Below the floor it stays full.
                band = tuning.coast_comfort_band
                share = 1.0 if band <= 0.0 else _clamp((tuning.room_target - indoor) / band, 0.0, 1.0)
                gain *= 1.0 + (tuning.coast_recovery_gain - 1.0) * share
            room_trim = _clamp(
                gain * (tuning.room_target - indoor),
                -tuning.room_error_limit,
                tuning.room_error_limit,
            )

        override_trim = -tuning.override_lowering if inputs.override_active else 0.0

        # The delta term's own gate is judged on the setpoint it is about to
        # adjust, not on the measured supply: the supply answers the trim
        # within minutes, so gating on it closes a loop through the gate.
        delta, delta_expected, delta_trim = self._delta(
            inputs, indoor, demand, out, dt,
            base_flow=curve_flow + room_trim + override_trim,
            room=indoor_slow,
        )

        flow_setpoint = tuning.clamp_flow(
            curve_flow + room_trim + override_trim + delta_trim
        )

        self._observe_house(inputs, indoor, delta, dt)
        emergency = inputs.emergency if inputs.emergency in EMERGENCY_KINDS else ""
        if emergency:
            # The family running the house by hand outranks every stop the
            # regulation makes for itself, and a setback under way ends.
            mode = MODE_EMERGENCY
            state.coasting = False
            if emergency == EMERGENCY_FLOW:
                flow_setpoint = tuning.clamp_flow(tuning.emergency_flow)
        else:
            mode = self._mode(inputs, outdoor_summer, indoor)
            mode = self._coast(inputs, indoor, mode, out)
        self._watch_radiators(mode, indoor, delta, delta_expected, dt, out, inputs.sweeping)
        if mode == MODE_COAST:
            state.seconds_since_coast = 0.0
            state.seconds_learning_hold = 0.0
            state.learning_hold_cell = None
            state.recovering = True
        elif mode != MODE_HEATING or (indoor is not None and indoor >= tuning.room_target):
            state.recovering = False
        # Net time in the warm stop, for the trap in _learn(). Up while
        # stopped, down while heating normally, so a sunny afternoon fades
        # and a house that keeps overshooting accumulates.
        if mode == MODE_WARM:
            state.seconds_warm += dt
        elif mode == MODE_HEATING:
            state.seconds_warm = max(
                0.0, state.seconds_warm - dt * tuning.learn_warm_trap_decay
            )

        out.boiler_overheated = self._boiler_overheated(inputs.boiler)
        heat_source = self._heat_source(mode, dt, inputs.hot_water, out, inputs.sweeping)
        inner_mode = self._inner_mode(inputs, mode, emergency)
        cap = self._cold_boiler_cap(inputs) if inner_mode == MODE_HEATING else None
        target, flow_error, effective_kp = self._inner_loop(
            inputs, inner_mode, flow_setpoint, control_dt, cap=cap
        )
        out.cold_boiler_cap = cap
        if cap is not None and target >= cap - 0.01:
            out.flags.append("cold_boiler")

        if mode != MODE_HEATING:
            # The heating-only learners stand still in every other mode, and
            # say which. The sweep is said as itself: the mode carries on
            # underneath it.
            why = "sweeping" if inputs.sweeping else f"mode:{mode}"
            for key in (LEARN_CURVE, LEARN_SUN, LEARN_TABLE, LEARN_DELTA, LEARN_GAIN):
                self._said(key, False, why, None)
        if mode == MODE_HEATING:
            self._learn(
                inputs=inputs,
                dt=dt,
                demand=demand,
                indoor_slow=indoor_slow,
                flow_setpoint=flow_setpoint,
                position=target,
            )
            self._learn_gain(inputs, dt, flow_setpoint, target)
            self._collect_point(
                inputs, dt, demand, indoor, indoor_slow, flow_setpoint, target, out
            )
        else:
            state.seconds_steady = 0.0
        self.points.fade(dt / 3600.0, tuning.table_half_life_days * 24.0)
        self._refresh_table_fit(dt)
        self._steer_toward_table(dt)

        self._shadow_valve_map(inputs, mode, flow_setpoint, dt, out)

        command = self._command(target, inputs.position, dt, out)

        out.position = state.position
        out.command_position = command
        # Reported while holding or in failsafe too: it is still what the
        # house wants, and seeing it next to a valve that is not tracking it
        # is the whole point of the diagnostic.
        out.flow_setpoint = (
            None
            if mode in MODES_STOPPED or emergency == EMERGENCY_VALVE
            else flow_setpoint
        )
        state.last_flow_setpoint = out.flow_setpoint
        # What the Shelly's watchdog holds if Home Assistant goes quiet: the
        # setpoint while there is one, else the curve's supply for this
        # weather. The warm stop above all - on 16 October it left the
        # watchdog nothing to hold but its own 68 C, for four hours.
        out.failsafe_flow = (
            out.flow_setpoint if out.flow_setpoint is not None
            else tuning.clamp_flow(curve_flow)
        )
        self._cold_until_target(mode, inputs, indoor, indoor_slow, dt, out)
        if mode in MODES_STOPPED:
            state.seconds_since_stop = 0.0
        else:
            state.seconds_since_stop += dt
        out.flow_error = flow_error
        out.curve_flow = curve_flow
        out.room_trim = room_trim
        out.override_trim = override_trim
        out.delta_trim = delta_trim
        out.indoor_fused = indoor
        out.indoor_slow = indoor_slow
        out.outdoor_filtered = outdoor
        out.curve_outdoor = curve_outdoor
        out.outdoor_summer = outdoor_summer
        out.delta = delta
        out.delta_expected = delta_expected
        out.demand = demand
        out.effective_slope = effective_slope
        out.effective_kp = effective_kp
        out.mode = mode
        if inputs.sweeping:
            out.reason = SWEEP_REASON
        elif emergency:
            out.reason = emergency_reason(
                emergency, tuning.emergency_position, tuning.clamp_flow(tuning.emergency_flow))
        else:
            out.reason = MODE_REASONS.get(mode, "")
        out.heat_source = heat_source
        return out

    # --- inputs ---------------------------------------------------------

    def _outdoor(
        self, inputs: ControlInputs, dt: float, out: ControlOutput
    ) -> tuple[float, float]:
        """The outdoor temperature on two timescales.

        The curve wants hours - that is roughly how fast a house answers the
        weather. The summer stop wants a day, because it is deciding about
        the season rather than the afternoon, and a three-hour mean reaches
        17 degrees on the first warm day in April.
        """
        outdoor = inputs.outdoor
        if outdoor is None:
            out.degraded.append("outdoor")
            # The curve follows the forecast rather than holding a frozen
            # reading. Only the curve: inputs.outdoor stays None, so nothing
            # learns from a forecast as if it had been measured.
            outdoor = outdoor_from_forecast(inputs.forecast)
            if outdoor is not None:
                out.flags.append("outdoor_forecast")
        filtered = self.state.outdoor.update(outdoor, dt)
        seasonal = self.state.outdoor_summer.update(outdoor, dt)
        if filtered is None:
            # Never measured one. Assume the design outdoor temperature,
            # which is the cold end of the curve: guessing wrong in that
            # direction costs firewood, guessing wrong the other way costs
            # a frozen house. The summer stop gets the same answer, which
            # keeps it firmly out of summer.
            return self.tuning.design_outdoor, self.tuning.design_outdoor
        return filtered, seasonal if seasonal is not None else filtered

    def _indoor(
        self, inputs: ControlInputs, dt: float, out: ControlOutput
    ) -> tuple[float | None, float | None]:
        fused, details = fuse_indoor(inputs.indoor, self.tuning, inputs.flow)
        out.indoor_readings = details

        contributing = sum(1 for item in details if item.contributed)
        if contributing and contributing < len(details):
            out.flags.append("indoor_partial")

        if fused is None:
            # Deliberately not the last filtered value: a stale indoor
            # temperature looks like working feedback and would keep the
            # house at whatever it was when the sensors died. Dropping to
            # plain weather compensation is visibly degraded and safe.
            out.degraded.append("indoor")
            return None, None

        return (
            self.state.indoor.update(fused, dt),
            self.state.indoor_slow.update(fused, dt),
        )

    def _timers(self, inputs: ControlInputs, dt: float, out: ControlOutput) -> None:
        state = self.state

        # Emergency running counts as an override for the learning: the
        # house was not run by the curve, then or for a while after.
        if inputs.override_active or inputs.emergency in EMERGENCY_KINDS:
            state.seconds_since_override = 0.0
        else:
            state.seconds_since_override += dt

        if inputs.target_changed:
            state.seconds_since_target_change = 0.0
        else:
            state.seconds_since_target_change += dt

        # Advanced here and reset in step() once the mode is known, because
        # the timers run before the coast decision is taken.
        state.seconds_since_coast += dt

        light = sunlight_now(inputs)
        state.sun_heat.update(light, dt)
        if light > self.tuning.learn_sunlight:
            state.seconds_since_sun = 0.0
        else:
            state.seconds_since_sun += dt

        # How long the house has stood over target, whatever warmed it -
        # see Tuning.learn_over_target. The slow indoor temperature, which
        # _indoor() has just updated: the house, not a cooking hour.
        slow = state.indoor_slow.value
        if slow is not None and slow > self.tuning.room_target + self.tuning.learn_over_target:
            state.seconds_over_target += dt
        else:
            state.seconds_over_target = 0.0

        if inputs.sweeping:
            state.seconds_since_sweep = 0.0
        else:
            state.seconds_since_sweep += dt
        # Reset at the end of step(), once the setpoint is known.
        state.seconds_since_shortfall += dt
        # And this one once the mode is.
        state.seconds_since_off += dt
        # The Shelly's own count, a cycle old: it starts the clock and never
        # winds it back up.
        state.seconds_since_failsafe += dt
        if inputs.since_failsafe is not None:
            state.seconds_since_failsafe = min(
                state.seconds_since_failsafe, max(0.0, inputs.since_failsafe)
            )

        if inputs.flow is None:
            state.seconds_without_flow += dt
            out.degraded.append("flow")
        else:
            state.seconds_without_flow = 0.0

        if inputs.ret is None:
            out.degraded.append("return")

    # --- setpoint -------------------------------------------------------

    def _curve_outdoor(self, measured: float | None, filtered: float) -> float:
        """The outdoor temperature the curve reads: the filtered one, and
        in a cold front part of the way down to what is measured."""
        if measured is None or measured >= filtered:
            return filtered
        tuning = self.tuning
        return filtered - tuning.outdoor_fall_lead * min(
            filtered - measured, tuning.outdoor_fall_limit
        )

    def _curve(self, outdoor: float) -> tuple[float, float]:
        effective_slope = self.effective_slope()
        return self._curve_flow(outdoor, effective_slope, self.balance()), effective_slope

    def curvature(self) -> float:
        """The bend the curve runs on - see model.effective_curvature."""
        return effective_curvature(self.tuning, self.state)

    def effective_slope(self) -> float:
        """The slope the curve runs on: the installer's plus what was learned."""
        tuning = self.tuning
        return _clamp(
            tuning.curve_slope + self.state.slope_trim,
            tuning.slope_min,
            tuning.slope_max,
        )

    def balance(self) -> float:
        """The outdoor temperature below which the house needs heat."""
        return self.tuning.room_target + self.state.balance_shift

    def _curve_flow(
        self, outdoor: float, slope: float, balance: float, curvature: float | None = None
    ) -> float:
        tuning = self.tuning
        return curve.supply(
            outdoor, tuning.room_target, tuning.design_outdoor,
            slope, self.curvature() if curvature is None else curvature, balance,
        )

    def curve_at(
        self,
        outdoor: float,
        slope: float | None = None,
        balance: float | None = None,
        curvature: float | None = None,
    ) -> float:
        """The supply the curve asks for at an outdoor temperature, held to
        the supply limits - by default the curve in use, otherwise the one
        with the slope, balance and bend given. For drawing the curve; the
        control cycle does not go through here."""
        flow = self._curve_flow(
            outdoor,
            self.effective_slope() if slope is None else slope,
            self.balance() if balance is None else balance,
            curvature,
        )
        return self.tuning.clamp_flow(flow)

    def _delta(
        self,
        inputs: ControlInputs,
        indoor: float | None,
        demand: float,
        out: ControlOutput,
        dt: float = 0.0,
        base_flow: float | None = None,
        room: float | None = None,
    ) -> tuple[float | None, float | None, float]:
        """Correction from how much heat the radiators actually take.

        Q = flow rate * delta, so with a roughly constant circulator the
        supply/return difference is a direct reading of delivered power.
        When something else heats the house - wood stove, sun, a full oven -
        the thermostatic radiator valves close, water bypasses the
        radiators, and the delta collapses well before the indoor sensors
        have moved. Lowering the supply temperature then is both cheaper and
        more comfortable than waiting for the room to overshoot.

        The expected delta is modelled as proportional to demand, scaled by
        a week-long average of what this pump and these radiators give
        against the design figures (Tuning.delta_scale_min).
        """
        tuning = self.tuning

        if inputs.flow is None or inputs.ret is None:
            out.delta_quiet = DELTA_QUIET_SENSOR
            self._forget_delta()
            return None, None, 0.0

        delta = inputs.flow - inputs.ret

        if not tuning.delta_enabled:
            out.delta_quiet = DELTA_QUIET_OFF
            self._forget_delta()
            return delta, None, 0.0

        if demand < tuning.delta_min_demand:
            # Barely any heat being asked for: the delta is noise on top of
            # a valve that is nearly shut.
            out.delta_quiet = DELTA_QUIET_DEMAND
            self._forget_delta()
            return delta, None, 0.0

        if room is None:
            room = indoor
        weight = self._delta_weight(
            inputs.flow if base_flow is None else base_flow, room
        )
        out.delta_weight = weight
        if weight <= 0.0:
            out.delta_quiet = DELTA_QUIET_LUKEWARM
            self._forget_delta()
            out.delta_readable_from = (
                (tuning.room_target if room is None else room) + tuning.delta_min_overtemp
            )
            return delta, None, 0.0

        scale = self.state.delta_scale.value
        expected = max(
            1.0, tuning.delta_design * min(1.2, demand) * (1.0 if scale is None else scale)
        )
        trim = self._delta_trim(inputs, delta, expected, weight, dt, out)

        # The indoor loop has the last word in both directions. Without this
        # the two loops argue: a cold house with closed radiator valves
        # somewhere would read as free heat and get less supply temperature.
        if indoor is not None:
            if trim < 0.0 and indoor < tuning.room_target - tuning.delta_cold_guard:
                trim = 0.0
                out.flags.append("delta_held_cold")
            elif trim > 0.0 and indoor > tuning.room_target + tuning.delta_cold_guard:
                trim = 0.0

        return delta, expected, trim

    def _said(self, key: str, learning: bool, why: str, value: float | None) -> None:
        """One learner's verdict this cycle - see ControlOutput.learning.
        Nothing outside a cycle: a learner asked on its own has no output
        to say it in."""
        out = getattr(self, "_out", None)
        if out is not None:
            out.learning[key] = (learning, why, value)

    def _forget_delta(self) -> None:
        state = self.state
        state.delta_filter.value = None
        state.delta_trim_held = 0.0
        state.seconds_settled = 0.0
        state.seconds_unsettled = 0.0

    def _delta_trim(
        self, inputs: ControlInputs, delta: float, expected: float, weight: float,
        dt: float, out: ControlOutput,
    ) -> float:
        """The correction, judged only while the supply sits still - see
        Tuning.delta_settle_band. While the valve is on its way the delta
        is the valve's doing, not the radiators', and the last correction
        is kept; for delta_hold_max_seconds at most, after which it lets go
        and starts again afresh."""
        tuning = self.tuning
        state = self.state
        # The warm-up after a stop: cold radiators, not the house's demand.
        if state.seconds_since_stop < tuning.delta_warmup_seconds:
            state.delta_filter.value = None
            state.delta_trim_held = 0.0
            state.seconds_settled = 0.0
            out.delta_warming_left = tuning.delta_warmup_seconds - state.seconds_since_stop
            out.flags.append("delta_warming")
            return 0.0
        setpoint = state.last_flow_setpoint
        if setpoint is not None and abs(inputs.flow - setpoint) <= tuning.delta_settle_band:
            state.seconds_settled += dt
        else:
            state.seconds_settled = 0.0

        if state.seconds_settled < tuning.delta_settle_seconds:
            state.seconds_unsettled += dt
            if state.seconds_unsettled >= tuning.delta_hold_max_seconds:
                # Let go, and start again afresh once it settles.
                state.delta_filter.value = None
                state.delta_trim_held = 0.0
                return 0.0
            out.flags.append("delta_held_moving")
            out.delta_read = state.delta_filter.value
            return state.delta_trim_held

        state.seconds_unsettled = 0.0
        read = state.delta_filter.update(delta, dt)
        out.delta_read = read
        state.delta_trim_held = weight * _clamp(
            -tuning.delta_gain * (expected - read),
            -tuning.delta_lower_limit,
            tuning.delta_raise_limit,
        )
        return state.delta_trim_held

    def _watch_radiators(
        self,
        mode: str,
        indoor: float | None,
        delta: float | None,
        expected: float | None,
        dt: float,
        out: ControlOutput,
        sweeping: bool = False,
    ) -> None:
        """Notice radiators that take too little heat from a cold house.

        A low delta on its own means nothing wrong: sun or a stove closes
        the thermostatic valves and the delta falls - that is what the delta
        term is for. A low delta while the house is *colder* than its target
        is different. The rooms want heat, the valves should be wide open,
        and still the water comes back nearly as hot as it went out: a
        radiator short-circuiting supply to return, air, a valve stuck
        shut. One pair of pipe sensors cannot say which radiator; it can say
        that one of them is not doing its job.

        Judged only where the delta is fully trusted and the loop is
        regulating, and reported only when it has held for a good share of
        the last few days - an evening after a setback, with cold rooms and
        radiators still warming up, is not a fault. Nor is a sweep: cold rooms
        and lukewarm water are exactly what a boiler left to cool gives.
        """
        tuning = self.tuning
        watch = self.state.starved
        judged = (
            mode == MODE_HEATING
            and not sweeping
            and self.state.seconds_since_sweep >= tuning.learn_after_override_seconds
            and out.delta_weight >= 1.0
            and indoor is not None
            and delta is not None
            and expected is not None
        )
        held = None
        if judged:
            held = (
                expected - delta >= tuning.starved_margin * expected
                and indoor < tuning.room_target - tuning.delta_cold_guard
            )
        watch.update(held, dt)
        line = tuning.starved_share * (STARVED_RELEASE if self._starved else 1.0)
        self._starved = (
            watch.hours >= tuning.starved_min_hours
            and (watch.share or 0.0) >= line
        )
        out.radiators_starved = self._starved
        if out.radiators_starved:
            out.flags.append("radiators_starved")

    def _shadow_valve_map(
        self,
        inputs: ControlInputs,
        mode: str,
        flow_setpoint: float,
        dt: float,
        out: ControlOutput,
    ) -> None:
        """Teach the valve map, and ask it what it would have done.

        It learns in every mode - a shut valve in a warm stop is a reading
        too - but is only asked and scored while the loop is actually
        regulating, and only scored once the valve has settled on its
        setpoint: that is when the PID's position is the right answer to
        compare against.
        """
        valve_map = self.valve_map
        valve_map.observe(inputs.position, inputs.flow, inputs.ret, inputs.boiler, dt)
        if None in (inputs.position, inputs.flow, inputs.ret, inputs.boiler):
            self._said(LEARN_VALVE_MAP, False, "no_data", None)
        else:
            self._said(LEARN_VALVE_MAP, True, "", None)
        if mode != MODE_HEATING:
            return
        predicted = valve_map.position_for(flow_setpoint, inputs.ret, inputs.boiler)
        out.valve_map_position = predicted
        if (
            valve_map.settled()
            and inputs.flow is not None
            and abs(inputs.flow - flow_setpoint) <= 0.5
        ):
            valve_map.score(predicted, inputs.position, dt)

    def _learning_hold(self, inputs: ControlInputs, out: ControlOutput) -> bool:
        """Whether a setback that may start has to wait for the curve table -
        see Tuning.coast_learning_hold_seconds. Counts the wait while it
        holds."""
        tuning = self.tuning
        state = self.state
        outdoor = state.outdoor.value
        # A hold of nought needs no test of its own: nothing is left to wait for.
        if outdoor is None:
            return False
        hours = self.points.hours_at(outdoor, inputs.hour)
        if hours >= tuning.coast_learning_cell_hours:
            return False
        if state.seconds_learning_hold >= tuning.coast_learning_hold_max_seconds:
            return False
        # What counts is what the hold brought into this cell: a new cell -
        # the weather moved a bin, or the day into its next part - counts
        # from the hours it already had.
        cell = self.points.key_at(outdoor, inputs.hour)
        if state.learning_hold_cell != cell:
            state.learning_hold_cell = cell
            state.learning_hold_base = hours
        left = tuning.coast_learning_hold_seconds - (hours - state.learning_hold_base) * 3600.0
        if left <= 0.0:
            return False
        state.seconds_learning_hold += max(0.0, float(inputs.dt))
        out.coast_learning_hold = left
        out.flags.append("learning_hold")
        return True

    def _delta_weight(self, supply: float, room: float | None) -> float:
        """How much the delta is worth listening to, from 0 to 1, by how far
        the supply is over the room - see Tuning.delta_min_overtemp.

        A ramp rather than a switch. The first version was a switch, and a
        house that sat right on it - 31 C supply, 20.8 C inside - flipped it
        every cycle, the trim jumping between 0 and -0.9 K and the valve
        with it: 118 % of travel an hour on 26-27 September, four times the
        week before.
        """
        tuning = self.tuning
        over = supply - (tuning.room_target if room is None else room)
        if tuning.delta_fade <= 0.0:
            return 1.0 if over >= tuning.delta_min_overtemp else 0.0
        return _clamp((over - tuning.delta_min_overtemp) / tuning.delta_fade, 0.0, 1.0)

    def _mode(
        self, inputs: ControlInputs, outdoor_summer: float, indoor: float | None
    ) -> str:
        """Which of the stops applies, if any.

        The summer test reads the day-long average rather than the curve's
        three-hour one: stopping the heating is a decision about the season,
        and a warm afternoon is not one.
        """
        tuning = self.tuning
        state = self.state

        if not inputs.heating_enabled:
            state.summer_stopped = False
            state.warm_stopped = False
            return MODE_OFF

        if state.summer_stopped:
            if outdoor_summer < tuning.summer_outdoor - tuning.summer_hysteresis:
                state.summer_stopped = False
        elif outdoor_summer >= tuning.summer_outdoor:
            state.summer_stopped = True

        if state.summer_stopped:
            return MODE_SUMMER

        if indoor is None:
            state.warm_stopped = False
        elif state.warm_stopped:
            if indoor < tuning.room_target + tuning.warm_stop_margin - tuning.warm_stop_hysteresis:
                state.warm_stopped = False
        elif indoor > tuning.room_target + tuning.warm_stop_margin:
            state.warm_stopped = True

        if state.warm_stopped:
            return MODE_WARM

        if inputs.flow is None:
            if state.seconds_without_flow >= tuning.failsafe_after_seconds:
                return MODE_FAILSAFE
            return MODE_HOLD

        return MODE_HEATING

    def _cold_boiler_cap(self, inputs: ControlInputs) -> float | None:
        """How far the valve may open for the boiler's sake, in percent, or
        None while the boiler is warm enough to be left alone.

        A boiler drawn down under the burner's alarm limit by the shunt is
        the regulation's own doing, and the watch cannot tell it from a
        burner gone out. The first start after a setback is where it
        happens: the loop is cold, the setpoint high, and a pellet burner
        that went out in the stop is minutes from any heat. So the opening
        follows the boiler: cold_boiler_floor from cold_boiler_clearance
        over the limit and down, free cold_boiler_band further up, a line
        between. A line rather than a switch, for the same reason as the
        delta's weight - a boiler sitting on the line would flip the valve
        every cycle.
        """
        tuning = self.tuning
        if (tuning.cold_boiler_band <= 0.0 or inputs.boiler is None
                or inputs.boiler_limit is None):
            return None
        over = float(inputs.boiler) - float(inputs.boiler_limit) - tuning.cold_boiler_clearance
        if over >= tuning.cold_boiler_band:
            return None
        floor = _clamp(tuning.cold_boiler_floor, 0.0, 100.0)
        return floor + (100.0 - floor) * _clamp(over / tuning.cold_boiler_band, 0.0, 1.0)

    def _boiler_overheated(self, boiler: float | None) -> bool:
        """Whether the boiler is too hot for the burner: tripped at
        boiler_max, held until it has cooled to boiler_max_release, and held
        through a missing reading - a sensor that drops out is no proof the
        boiler cooled."""
        tuning = self.tuning
        state = self.state
        if boiler is not None:
            if boiler >= tuning.boiler_max:
                state.boiler_overheated = True
            elif boiler <= tuning.boiler_max_release:
                state.boiler_overheated = False
        return state.boiler_overheated

    def _heat_source(
        self, mode: str, dt: float, hot_water: bool, out: ControlOutput,
        sweeping: bool = False,
    ) -> bool:
        """Whether the burner on the hot side should be running.

        Only long-lived stops switch it off, and only after the stop has
        held for heat_source_off_delay. Every other state - heating,
        holding, failsafe - wants it on, and failsafe wants it on most of
        all: driving the valve to maximum heat achieves nothing against a
        tank that has gone cold.

        The delay counts time stopped, not time in this particular stop, so
        a warm house that turns into a mild spell switches the burner off
        without starting the clock again. Heating for even one cycle resets
        it, which is what keeps the burner from being cycled by a house
        hovering on the edge of its warm stop.
        """
        state = self.state

        stopped = mode in MODES_STOPPED
        if stopped:
            state.seconds_stopped += dt
        else:
            state.seconds_stopped = 0.0

        # An overheated boiler outranks everything, the sweep and the hot
        # water included: nothing is worth more heat in it.
        if state.boiler_overheated:
            out.flags.append("boiler_overheat")
            return False

        # A sweep outranks everything below, the hot water included: the
        # sweeper's brush goes where the flame was, and a burner that lights
        # for a shower while he works is the one thing this must never do.
        # At once, with no delay to sit through - the moment it begins was
        # chosen hours ahead of his arrival so that the boiler has cooled.
        if sweeping:
            out.flags.append("sweeping")
            return False

        # The one answer that outranks every stop. Everything else here is
        # about the radiators, and the radiators are not what a shower draws
        # from: a burner told to stop because the house is warm enough in May
        # is a burner that was also making the hot water.
        #
        # The clock above keeps running through it, because it measures how
        # long the heating has been stopped and that is true whatever the hot
        # water is doing. So the evening the hot water moves back to the
        # immersion heater, a boiler idle since May stops at once instead of
        # sitting through a delay it has already served.
        #
        # Reported rather than silent: "the burner is running although the
        # heating is stopped" is otherwise an evening of debugging. And
        # whether it is the hot water that has it on, by what the heating
        # alone would have said: in a heating house the burner runs anyway,
        # and "on for the hot water" there would be a wrong reason
        # (2026-10-18, the app said hot_water under the indoor sensors).
        if hot_water:
            out.flags.append("hot_water")
            out.burner_for_hot_water = not self._burner_for_heating(mode, out, stopped)
            return True

        if mode == MODE_WARM and out.coast_burner:
            out.flags.append("coast_burner")
        return self._burner_for_heating(mode, out, stopped)

    def _burner_for_heating(self, mode: str, out: ControlOutput, stopped: bool) -> bool:
        """Whether the radiators want the burner running, the hot water left
        out. Asks only: the flags are _heat_source's."""
        tuning = self.tuning
        state = self.state

        if mode == MODE_COAST:
            # No clock to sit through: the predictive decision already
            # required the house to hold for longer than lighting the burner
            # plus the delay is worth. The criterion *is* the clock, and
            # running a second one on top would keep the burner going
            # through the first hour of every coast it just proved was safe.
            return not out.coast_burner

        if not stopped:
            return True

        # A warm stop knows less than the forecast does, so the forecast is
        # allowed to answer first.
        #
        # heat_source_off_when_idle is off by default to keep a burner from
        # being cycled by a few warm hours, and the objection behind that
        # setting is a real one: nobody knows how long the warmth will last.
        # The coast verdict is exactly that knowledge. It is only true when
        # the house is predicted to hold for longer than relighting the
        # burner and sitting out the stop delay costs - the same question
        # the setting was guessing at, answered with a forecast and a model
        # of this building. Where it says yes it settles the objection
        # rather than overriding it.
        #
        # Measured before this existed: a house thirteen hours into a warm
        # stop, predicted to hold four and a half more, with the burner
        # running throughout because the warm stop short-circuited the
        # decision. The shunt had the same short-circuit and it cost
        # nothing, the valve being shut either way; a burner is not.
        #
        # No clock to sit through, for the reason in the coast branch above:
        # heat_source_off_delay is already inside the criterion.
        if mode == MODE_WARM and out.coast_burner:
            return False

        may_switch_off = (
            (mode == MODE_SUMMER and tuning.heat_source_off_in_summer)
            or (mode == MODE_WARM and tuning.heat_source_off_when_idle)
            or (mode == MODE_OFF and tuning.heat_source_off_when_disabled)
        )
        if not may_switch_off:
            return True

        return state.seconds_stopped < tuning.heat_source_off_delay

    # --- the building, and whether it can be left alone -------------------

    def _observe_house(
        self, inputs: ControlInputs, indoor: float | None, delta: float | None, dt: float
    ) -> None:
        """Let the house model watch this cycle.

        Every cycle, not only the ones with the heat off: supply minus return
        is a direct reading of delivered power, so it goes in as a regressor
        and January becomes data instead of a blind spot.
        """
        conditions = self._conditions(inputs)
        if indoor is None or conditions is None:
            self._said(LEARN_HOUSE, False, "no_indoor" if indoor is None else "no_outdoor", None)
        else:
            self._said(LEARN_HOUSE, True, "", getattr(self.house, "observed_hours", None))
        self.house.observe(
            indoor=indoor,
            conditions=conditions,
            delta=delta or 0.0,
            dt=dt,
            sample_seconds=self.tuning.thermal_sample_seconds,
            radiators=radiator_excess(inputs.flow, inputs.ret, indoor),
            night=self._night(inputs),
        )

    def _night(self, inputs: ControlInputs) -> bool:
        """Night for the house model's night balance - see
        Tuning.night_start_hour. Not known without the clock or the sun."""
        tuning = self.tuning
        if inputs.hour is None or inputs.sun_elevation is None:
            return False
        start, end = tuning.night_start_hour, tuning.night_end_hour
        hour = inputs.hour % 24.0
        in_window = start <= hour < end if start < end else (hour >= start or hour < end)
        return (
            in_window
            and inputs.sun_elevation <= 0.0
            and self.state.seconds_since_sun >= tuning.learn_after_sun_seconds
        )

    def _conditions(self, inputs: ControlInputs) -> Conditions | None:
        if inputs.outdoor is None:
            return None
        return Conditions(
            outdoor=float(inputs.outdoor),
            wind=inputs.wind,
            bearing=inputs.wind_bearing,
            sun=sunlight_now(inputs),
            hour=inputs.hour,
        )

    def _coast(
        self, inputs: ControlInputs, indoor: float | None, mode: str, out: ControlOutput
    ) -> str:
        """Should the house be left to coast on what it already has?

        Only ever asked when the answer would otherwise be to heat. The
        other stops are about the house being warm enough *now*; this one is
        about it staying warm enough *for long enough*, which is a different
        question and needs a forecast and a model of the building to answer.

        Two decisions come out of one prediction, at different horizons. The
        shunt can close as soon as the house will hold longer than it takes
        to get heat back into the rooms - minutes. The burner needs the house
        to hold longer than its own ignition takes as well, which for pellets
        is half an hour, so it stops later and starts earlier. That asymmetry
        is the whole reason they are asked separately.
        """
        tuning = self.tuning
        if not tuning.coast_enabled or indoor is None or not inputs.forecast:
            self.state.coasting = False
            return mode

        weather = [
            (hours, conditions)
            for hours, conditions in inputs.forecast
            if hours <= tuning.coast_horizon_hours
        ]
        if not weather:
            return mode

        # Trust before anything is predicted. A fit that has barely more
        # samples than parameters solves - that is all weight >= size + 1
        # promises - but it does not mean anything, and its own uncertainty
        # is computed from the same handful of residuals, so it cannot be
        # relied on to say so. Measured on this house the day the model was
        # reset: 13 samples, cold outside and the radiators both fitted with
        # the wrong sign, and a predicted hold that swung from four hours to
        # thirty-five minutes inside an hour. With the forecast now allowed
        # to stop the burner in a warm stop, that swing would light and put
        # out a pellet burner for nothing.
        why = self._distrust()
        out.coast_trust = why
        if why:
            self.state.coasting = False
            out.coast_allowance = tuning.coast_learning_band
            out.coast_margin = max(tuning.coast_comfort_band, tuning.coast_learning_band)
            return mode

        # The exploration budget. Wide while the model is unsure, closing on
        # its own as it learns - and re-opening the moment the weather goes
        # somewhere it has no data for, which is what makes "it goes back to
        # learning" happen without a mode to switch.
        spread = self.house.drift_uncertainty(indoor, weather[0][1])
        reach = self.house.reach(indoor, weather[0][1])
        allowance = 0.0
        if reach is None:
            allowance = tuning.coast_learning_band
        elif tuning.coast_unknown_reach > 0.0:
            share = min(1.0, max(0.0, reach) / tuning.coast_unknown_reach)
            allowance = tuning.coast_learning_band * share
        out.coast_uncertainty = spread
        out.coast_reach = reach
        out.coast_allowance = allowance
        # How far below target the setback may let the house go: whichever
        # is larger of what the family accepts and what learning still needs.
        margin = max(tuning.coast_comfort_band, allowance)
        out.coast_margin = margin

        outlook = self.house.outlook(
            indoor=indoor,
            floor=tuning.room_target - margin,
            weather=weather,
            confidence=tuning.coast_confidence,
        )
        out.coast_known = outlook.known
        out.coast_hours = outlook.hours
        if not outlook.known:
            self.state.coasting = False
            return mode

        held = float("inf") if outlook.holds else (outlook.hours or 0.0)

        # Hysteresis, because the threshold is being compared against a
        # prediction. Starting a coast asks for the lead plus a margin;
        # carrying one on asks only for the lead. Without the gap a house
        # predicted to hold for within a minute of the threshold shuts its
        # valve and opens it again every cycle, which is worse than either
        # decision taken and held.
        lead = tuning.shunt_lead_seconds
        needed = lead if self.state.coasting else lead + tuning.coast_hysteresis_seconds
        out.coast_shunt_threshold = needed / 3600.0
        out.coast_turn_hours = outlook.turns_at
        # Only towards weather that turns (Tuning.coast_bridge_only); the
        # burner's verdict below stays a matter of how long the house holds,
        # since the warm stop asks it too.
        bridges = outlook.turns_at is not None or not tuning.coast_bridge_only
        out.coast_shunt = bridges and held > needed / 3600.0
        # The burner has to clear its ignition *and* be worth stopping. A
        # hold of thirty-five minutes against a half-hour light is not a
        # saving, it is a short cycle with extra steps, so the same delay
        # that guards every other stop is folded into the criterion here
        # rather than run as a clock afterwards.
        burner_cost = self.burner_lead_seconds() + tuning.heat_source_off_delay
        if not self.state.coasting:
            burner_cost += tuning.coast_hysteresis_seconds
        out.coast_burner_threshold = burner_cost / 3600.0
        out.coast_burner = held > burner_cost / 3600.0

        # Knowing and acting are separate. Everything above is worked out in
        # every mode, because how long the house would hold and how sure the
        # model is are properties of the building and the weather rather than
        # of what the controller happens to be doing - and a budget that
        # reads zero because it was never computed says the opposite of a
        # budget that reads zero because the model has learned.
        #
        # Acting only makes sense from heating. The other stops have already
        # decided not to heat, and there is nothing a setback can add to a
        # valve that is already shut.
        if mode != MODE_HEATING:
            self.state.coasting = False
            return mode

        # A setback spends what is above target. It starts only from target,
        # which also means that after one the house is brought back to
        # target before another may begin; once begun it carries on down to
        # the floor. Without this, coast and heating took turns all night on
        # 2026-10-06, the house between 20.4 and 20.9 and the burner cycling
        # with them, each new coast spending the comfort band again.
        # The learning hold is only asked when a setback would start, so
        # its wait counts only time it actually held one back.
        may = self.state.coasting or (
            indoor >= tuning.room_target
            and not (out.coast_shunt and self._learning_hold(inputs, out))
        )
        self.state.coasting = bool(out.coast_shunt) and may
        if self.state.coasting:
            out.flags.append("coast")
            return MODE_COAST
        return mode

    def _distrust(self) -> str:
        """Why the house model may not decide yet, or "" when it may.

        Policy, kept here rather than in thermal.py: the model reports what
        it has seen and what it believes, the controller decides whether that
        is enough to act on.
        """
        hours = self.house.observed_hours
        needed = self.tuning.coast_min_hours
        if hours < needed:
            return f"{hours:.0f} av {needed:.0f} h data"
        cools = self.house.cools_when_left_alone()
        if cools is None:
            return "för lite data"
        if not cools or self.house.loss_on_floor():
            return "för lite varierad data för att lita på modellen än"
        return ""

    # --- inner loop -----------------------------------------------------

    def _feed_forward(self, inputs: ControlInputs, flow_setpoint: float) -> None:
        """Move the integral by the change in what the valve map says - see
        Tuning.valve_feedforward_gain. Nothing on a cycle without an answer,
        and the next answer starts afresh rather than stepping across the
        gap."""
        tuning = self.tuning
        state = self.state
        mapped = None
        if tuning.valve_feedforward_gain > 0.0:
            mapped = self.valve_map.position_for(flow_setpoint, inputs.ret, inputs.boiler)
        if mapped is not None and state.last_feedforward is not None:
            state.integral += _clamp(
                tuning.valve_feedforward_gain * (mapped - state.last_feedforward),
                -tuning.valve_feedforward_step, tuning.valve_feedforward_step,
            )
        state.last_feedforward = mapped

    def _inner_mode(self, inputs: ControlInputs, mode: str, emergency: str) -> str:
        """The mode the inner loop runs in. Itself, except in emergency
        running: a fixed valve is MODE_EMERGENCY there, and a fixed supply is
        an ordinary loop on that setpoint, holding and then going to the
        failsafe position without a supply reading like any other."""
        if mode != MODE_EMERGENCY or emergency == EMERGENCY_VALVE:
            return mode
        if inputs.flow is None:
            if self.state.seconds_without_flow >= self.tuning.failsafe_after_seconds:
                return MODE_FAILSAFE
            return MODE_HOLD
        return MODE_HEATING

    def _inner_loop(
        self,
        inputs: ControlInputs,
        mode: str,
        flow_setpoint: float,
        control_dt: float,
        cap: float | None = None,
    ) -> tuple[float, float | None, float]:
        """The valve position for this cycle. `cap` is the most it may open,
        when something outside the loop - the cold boiler's guard - says so;
        the loop saturates on it like an end stop, integral and all, so it
        comes off it the moment the cap lifts."""
        tuning = self.tuning
        state = self.state
        top = 100.0 if cap is None else _clamp(cap, 0.0, 100.0)

        if mode in MODES_STOPPED:
            state.integral = 0.0
            state.last_feedforward = None
            state.needs_reseed = True
            state.last_flow = inputs.flow
            return 0.0, None, 0.0

        if mode == MODE_FAILSAFE:
            state.integral = 0.0
            state.last_feedforward = None
            state.needs_reseed = True
            state.last_flow = None
            return tuning.failsafe_position, None, 0.0

        if mode == MODE_EMERGENCY:
            # A fixed valve: no loop to close. Reseeded on the way back, so
            # the regulation takes over from where the valve is.
            state.integral = 0.0
            state.needs_reseed = True
            state.last_flow = inputs.flow
            return _clamp(tuning.emergency_position, 0.0, 100.0), None, 0.0

        if mode == MODE_HOLD:
            # Only throw the integral away once the sensor has been gone long
            # enough for the loop's picture of the world to be stale. A blink
            # of one cycle has invalidated nothing - see reseed_after_seconds.
            if state.seconds_without_flow >= tuning.reseed_after_seconds:
                state.needs_reseed = True
            # The derivative is a different matter: it compares this reading
            # with the last one, and across a gap that difference is not a
            # rate of change. It always starts again.
            state.last_flow = None
            held = state.position
            if held is None:
                held = tuning.failsafe_position
            return held, None, 0.0

        flow = float(inputs.flow)

        # Rescale the gain by the valve authority actually measured. The
        # default Kp was tuned for gain_reference K of supply temperature
        # per percent of travel; a valve with twice that authority needs
        # half the gain or the loop hunts.
        wanted_kp = tuning.pid_kp * _clamp(
            tuning.gain_reference / max(0.05, self._valve_gain(inputs)),
            tuning.gain_scale_min,
            tuning.gain_scale_max,
        )
        if state.needs_reseed or state.position is None:
            state.last_kp = None
        effective_kp = _rising_slowly(state.last_kp, wanted_kp, control_dt, tuning.kp_rise_seconds)
        state.last_kp = effective_kp

        if state.needs_reseed or state.position is None:
            state.resume_position = min(top, self._resume_position(inputs, flow_setpoint))
            state.resume_hold_left = tuning.resume_hold_seconds
            state.integral = bumpless_integral(position=state.resume_position)
            state.needs_reseed = False
            # Without this the first derivative term would be computed
            # against a flow temperature from before the restart.
            state.last_flow = flow

        # Waiting for the water: stand at the start position until the
        # supply reaches its setpoint or the wait is over, then take over
        # from exactly there - the integral set so that the first output,
        # proportional term and all, is the start position.
        if state.resume_hold_left > 0.0 and state.resume_position is not None:
            state.resume_hold_left = max(0.0, state.resume_hold_left - control_dt)
            if flow < flow_setpoint and state.resume_hold_left > 0.0:
                state.last_flow = flow
                state.last_feedforward = None
                return min(top, state.resume_position), flow_setpoint - flow, effective_kp
            state.resume_hold_left = 0.0
            state.integral = _clamp(
                state.resume_position
                - effective_kp * _deadbanded(flow_setpoint - flow, tuning.pid_deadband),
                -100.0, 100.0,
            )
            state.last_flow = flow

        self._feed_forward(inputs, flow_setpoint)

        result = pid_step(
            setpoint=flow_setpoint,
            measurement=flow,
            last_measurement=state.last_flow,
            integral=state.integral,
            dt=control_dt,
            kp=effective_kp,
            ti=tuning.pid_ti,
            td=tuning.pid_td,
            deadband=tuning.pid_deadband,
            out_max=top,
        )
        state.integral = result.integral
        state.last_flow = flow
        return result.output, result.error, effective_kp

    def _valve_gain(self, inputs: ControlInputs) -> float:
        """K of supply per percent of travel where the valve stands: from
        the map when it can say (Tuning.valve_map_gain), else the learned
        one. Where the loop last sent it rather than where it last said it
        was: after a stop the report is still at shut while the valve
        travels to its start position."""
        state = self.state
        if self.tuning.valve_map_gain and inputs.boiler is not None and inputs.ret is not None:
            position = state.position if state.position is not None else inputs.position
            mapped = self.valve_map.gain_at(position, inputs.boiler - inputs.ret)
            if mapped is not None:
                return mapped
        return state.process_gain

    def _resume_position(self, inputs: ControlInputs, flow_setpoint: float) -> float:
        """Where to start the loop from when it has no integral to trust.

        The valve's own reported position is the honest answer while the
        system is running. Coming out of a summer or warm stop it is zero
        and using it would mean twenty minutes of winding the integral back
        up, so the learned valve gain is used to estimate the position that
        holds the setpoint: at a shut valve the supply is return water, and
        every percent of travel is worth process_gain degrees.
        """
        state = self.state

        if inputs.position is not None and not state.needs_reseed:
            return _clamp(float(inputs.position), 0.0, 100.0)

        # The valve map first: it knows the share of boiler water each
        # position lets through, so a hot boiler means less travel - the
        # single gain knows neither the valve's shape nor the boiler.
        mapped = self.valve_map.position_for(flow_setpoint, inputs.ret, inputs.boiler)
        if mapped is not None:
            return _clamp(mapped, 0.0, 100.0)

        if inputs.ret is not None and state.process_gain > 0.05:
            return _clamp(
                (flow_setpoint - float(inputs.ret)) / state.process_gain,
                0.0, self.tuning.resume_position_max,
            )

        if inputs.position is not None:
            return _clamp(float(inputs.position), 0.0, 100.0)

        return 0.0

    def _command(
        self, target: float, reported: float | None, dt: float, out: ControlOutput
    ) -> float | None:
        """The position to send, or None to leave the valve alone.

        Every command is a fresh journey for a three-point actuator, so
        chasing tenths of a percent would run the motor continuously for no
        measurable change in supply temperature. The end stops are the
        exception: fully shut and fully open are worth reaching exactly.

        That deadband is measured against the last command, not against the
        valve, because the valve is still travelling for most of a cycle and
        comparing with where it has got to so far would re-command it every
        cycle of every move. The price is that the loop cannot see a valve
        something else has moved, so the drift check below is what buys that
        back: a valve reporting somewhere other than where it was sent, for
        long enough that it cannot still be on its way, gets sent there
        again.
        """
        tuning = self.tuning
        state = self.state

        wanted = float(round(_clamp(target, 0.0, 100.0)))
        at_end = wanted <= 0.0 or wanted >= 100.0

        if state.position is None:
            state.seconds_adrift = 0.0
            state.position = wanted
            return wanted

        if (
            reported is None
            or abs(float(reported) - state.position) < tuning.position_drift_limit
        ):
            state.seconds_adrift = 0.0
        else:
            state.seconds_adrift += dt

        moved = abs(wanted - state.position) >= tuning.min_position_step or (
            at_end and wanted != state.position
        )
        # Resetting the clock here as well as on agreement is what keeps a
        # real standoff - the Shelly holding the valve shut on its own
        # over-temperature guard - to one command per period rather than a
        # tug of war every thirty seconds.
        adrift = state.seconds_adrift >= tuning.position_drift_seconds
        if adrift:
            out.flags.append("valve_adrift")

        if moved or adrift:
            state.seconds_adrift = 0.0
            state.position = wanted
            return wanted

        return None

    # --- learning -------------------------------------------------------

    def _learn(
        self,
        *,
        inputs: ControlInputs,
        dt: float,
        demand: float,
        indoor_slow: float | None,
        flow_setpoint: float,
        position: float,
    ) -> None:
        """Move the curve so the house stops needing the room correction.

        The regression separates the two things a wrong curve can be. An
        error that is there whatever the weather is a wrong offset; an error
        that grows as it gets colder outside is a wrong slope. Fitting
        indoor error against normalised demand tells them apart - but only
        once the weather has actually varied, which is why the slope moves
        only when the fit says it is identifiable.

        Every gate below exists because without it the curve gets blamed for
        something else: an override that was asked for, a setpoint the inner
        loop is not delivering, a valve with nowhere left to go, a target
        the family changed ten minutes ago.
        """
        tuning, state = self.tuning, self.state
        gate = (inputs, dt, demand, indoor_slow, flow_setpoint, position)
        held = state.sun_heat.value or 0.0
        if tuning.learn_sun_term:
            # The sun's part is learnt from every cycle that says something
            # about the curve but for the sun - the sunny ones above all.
            base_why, base_left = self._evidence_gate(*gate, sun_rule=SUN_RULE_NONE)
            if base_why:
                self._said(LEARN_SUN, False, base_why, base_left)
            else:
                term = state.sun_term
                term.observe(demand, held, tuning.room_target - indoor_slow, dt)
                share = term.share(demand)
                if share is None:
                    self._said(LEARN_SUN, True, "sun_term_hours", float(term.hours))
                else:
                    self._said(LEARN_SUN, True, "", share)
            why, left = self._evidence_gate(*gate, sun_rule=SUN_RULE_TERM)
        else:
            self._said(LEARN_SUN, False, "off", None)
            why, left = self._evidence_gate(*gate)
        if not tuning.learn_enabled:
            why, left = "off", None
        if why:
            self._said(LEARN_CURVE, False, why, left)
            self._said(LEARN_DELTA, False, why, left)
            return

        error = tuning.room_target - indoor_slow
        if tuning.learn_sun_term:
            # The sun's part comes off wherever it is known, the weak light
            # the light's gate lets through and the evening it holds too.
            error -= state.sun_term.correction(demand, held)
        # The light's own gate, which the delta keeps: open, or the curve is
        # learning past it.
        light_why, light_left = self._evidence_gate(*gate) if tuning.learn_sun_term else ("", None)
        # With the curve table steering, the regression stands down: two
        # learners on one curve would spend the winter undoing each other.
        # Only while it actually steers - a table that may but has no chain
        # yet must not leave the curve without a learner.
        if not self.table_steers():
            self._learn_offset_and_slope(demand, error, dt)
            self._learn_from_saturated_offset(error, dt)
            self._said(LEARN_CURVE, True, "sun_off" if light_why else "", self.state.learn_hours)
        else:
            self._said(LEARN_CURVE, False, "table_steers", None)
        self.state.learn_hours += dt / 3600.0
        if light_why:
            self._said(LEARN_DELTA, False, light_why, light_left)
        else:
            self._calibrate_expected_delta(inputs, demand, indoor_slow, dt)

    def _may_learn(
        self,
        inputs: ControlInputs,
        dt: float,
        demand: float,
        indoor_slow: float | None,
        flow_setpoint: float,
        position: float,
    ) -> bool:
        """Whether an indoor error now says anything about the curve.

        Every gate exists because without it the curve gets blamed for
        something else - see _learn().
        """
        if not self.tuning.learn_enabled:
            return False
        return self._evidence_gates_open(inputs, dt, demand, indoor_slow, flow_setpoint, position)

    def _cold_until_target(self, mode: str, inputs: ControlInputs, indoor: float | None,
                           indoor_slow: float | None, dt: float, out: ControlOutput) -> None:
        """A sweep, a setback, the heat source falling short or the heating
        switched off leaves the house under target, and what it does on the
        way back says nothing about the curve. Each has its wait afterwards; it counts from the
        house back on target, however long that takes (Fredrik
        2026-10-11), by holding the cause's clock at nought until then."""
        tuning = self.tuning
        state = self.state
        if inputs.sweeping:
            cause = "sweep"
        elif mode == MODE_COAST:
            cause = "coast"
        elif self._fell_short(mode, inputs, out.flow_setpoint, out.cold_boiler_cap):
            cause = "shortfall"
            state.seconds_since_shortfall = 0.0
        elif mode == MODE_OFF:
            cause = "off"
            state.seconds_since_off = 0.0
        else:
            cause = None
        if cause is not None:
            state.cold_after = cause
            state.seconds_cold = 0.0
            return
        if state.cold_after is None:
            return
        state.seconds_cold += dt
        if ((indoor is not None and indoor >= tuning.room_target)
                or (state.seconds_cold >= tuning.learn_target_wait_max_seconds
                    and not self._warming(indoor, indoor_slow))):
            state.cold_after = None
        else:
            setattr(state, COLD_CLOCKS[state.cold_after], 0.0)

    def _warming(self, indoor: float | None, indoor_slow: float | None) -> bool:
        """Whether the house is still getting warmer by learn_target_warming.
        On a steady climb the quick reading runs ahead of the slow one by the
        climb times the difference of their spans; a house that has stopped
        has the two together."""
        state = self.state
        span = (state.indoor_slow.seconds - state.indoor.seconds) / 3600.0
        if indoor is None or indoor_slow is None or span <= 0.0:
            return False
        return (indoor - indoor_slow) / span >= self.tuning.learn_target_warming

    def _fell_short(self, mode: str, inputs: ControlInputs, flow_setpoint: float | None,
                    cap: float | None = None) -> bool:
        """The heat source not keeping up: the valve at its open end - or as
        far as the cold boiler's guard lets it go, a boiler too cold to be
        drawn from being the heat source falling short too - and the supply
        still further under its setpoint than learning tolerates. A
        wide-open valve that meets the setpoint is a cold day, and a supply
        short while the valve is still opening is the valve."""
        tuning = self.tuning
        if mode != MODE_HEATING or flow_setpoint is None or inputs.flow is None:
            return False
        position = inputs.position if inputs.position is not None else self.state.position
        top = 100.0 if cap is None else cap
        return (position >= top - tuning.learn_position_margin
                and flow_setpoint - inputs.flow > tuning.learn_max_flow_error)

    def _evidence_gates_open(
        self,
        inputs: ControlInputs,
        dt: float,
        demand: float,
        indoor_slow: float | None,
        flow_setpoint: float,
        position: float,
    ) -> bool:
        """Whether the house now says anything about the curve at all - the
        learner's gates without its on/off switch, which the curve table
        shares: it collects whether or not anything learns from it."""
        return not self._evidence_gate(
            inputs, dt, demand, indoor_slow, flow_setpoint, position
        )[0]

    def _evidence_gate(
        self,
        inputs: ControlInputs,
        dt: float,
        demand: float,
        indoor_slow: float | None,
        flow_setpoint: float,
        position: float,
        sun_rule: str = SUN_RULE_LIGHT,
    ) -> tuple[str, float | None]:
        """The first gate that is shut, as a code for texts.learning_rows(),
        and the seconds left of it where it is a wait - ("", None) when all
        are open.

        sun_rule says how the sun is judged: by the light on the walls and
        the waits after it (SUN_RULE_LIGHT); not at all where the sun's part
        of the error is known well enough to take off (SUN_RULE_TERM,
        sunterm.py), else by the light; or not at all (SUN_RULE_NONE), for
        the sun term's own samples."""
        tuning = self.tuning
        state = self.state
        settle = tuning.learn_after_override_seconds

        if dt <= 0.0 or indoor_slow is None:
            return "no_indoor", None
        # One sensor of three is not the house: the night of 1-2 November
        # 2026 the two Verisure sensors were away 01:20-02:56, the kitchen
        # alone read 22 against the house's 21.3, and the curve learnt 1.6
        # hours of it. A sensor that is meant to count and has no reading
        # - not one switched off in the configuration, which is the house
        # as configured.
        out = getattr(self, "_out", None)
        if out is not None and any(
                item.active and item.weight > 0.0 and item.corrected is None
                for item in out.indoor_readings):
            return "indoor_partial", None
        # The curve's outdoor temperature is a held value or a forecast
        # then, and an indoor error would be blamed on the wrong curve.
        if inputs.outdoor is None:
            return "no_outdoor", None
        if inputs.override_active:
            return "override", None
        if state.seconds_since_override < settle:
            return "after_override", settle - state.seconds_since_override
        # A sweep keeps the burner off and the house lives on what was left
        # in the boiler: cold rooms that say nothing about the curve, during
        # it and while the house recovers afterwards.
        if inputs.sweeping:
            return "sweeping", None
        # Still under target after one of them: no clock yet.
        if state.cold_after is not None:
            return COLD_WHY[state.cold_after], None
        if state.seconds_since_sweep < settle:
            return "after_sweep", settle - state.seconds_since_sweep
        # And for a burner that went out: the same cold house, the same wait.
        if state.seconds_since_shortfall < settle:
            return "after_shortfall", settle - state.seconds_since_shortfall
        # And for the heating switched off: the valve shut, the house left
        # to cool.
        if state.seconds_since_off < settle:
            return "after_off", settle - state.seconds_since_off
        # Same reasoning, for the same reason. A predictive setback *makes*
        # the house colder than target on purpose; a curve learner that saw
        # that would raise the curve to answer it and slowly learn the
        # saving away, exactly as it would with the override contact.
        if state.seconds_since_coast < settle:
            return "after_coast", settle - state.seconds_since_coast
        # The Shelly's watchdog ran the valve while Home Assistant was away,
        # and the house it hands back holds the watchdog's heat, not the
        # curve's: the same wait, and then while it is still warm from it.
        if state.seconds_since_failsafe < settle:
            return "after_failsafe", settle - state.seconds_since_failsafe
        if (indoor_slow > tuning.room_target + tuning.learn_after_failsafe_warm
                and state.seconds_since_failsafe < tuning.learn_after_failsafe_max_seconds):
            return ("failsafe_warm",
                    tuning.learn_after_failsafe_max_seconds - state.seconds_since_failsafe)
        held = state.sun_heat.value or 0.0
        if sun_rule == SUN_RULE_NONE or (
                sun_rule == SUN_RULE_TERM
                and state.sun_term.known(demand, held, tuning.learn_sun_error)):
            pass
        elif state.seconds_since_sun <= 0.0:
            return "sun", None
        elif state.seconds_since_sun < tuning.learn_after_sun_seconds:
            return "after_sun", tuning.learn_after_sun_seconds - state.seconds_since_sun
        # Past that, while the house still holds the day over target: its
        # walls warmed, not the curve too high. Only over: a house under
        # target after the sun is the curve's to answer.
        elif (indoor_slow > tuning.room_target + tuning.learn_after_sun_warm
                and state.seconds_since_sun < tuning.learn_after_sun_max_seconds):
            return "sun_warm", tuning.learn_after_sun_max_seconds - state.seconds_since_sun
        # And whatever else warmed it - the kitchen's evening, the morning
        # sun the term already answers for, the sun past its six hours: a
        # house over target is not the curve's until it is back down, up to
        # learn_over_target_max_seconds from when it went over. After the
        # sun's and the watchdog's own waits, so that each says its cause
        # while it lasts. Only over, as above; with a ceiling, because a
        # curve genuinely too high keeps it over for ever and has to be
        # heard in the end (Tuning.learn_over_target).
        if (indoor_slow > tuning.room_target + tuning.learn_over_target
                and state.seconds_over_target < tuning.learn_over_target_max_seconds):
            return ("over_target",
                    tuning.learn_over_target_max_seconds - state.seconds_over_target)
        if state.seconds_since_target_change < tuning.learn_settle_seconds:
            return "target_changed", tuning.learn_settle_seconds - state.seconds_since_target_change
        if demand < tuning.learn_min_demand:
            return "low_demand", None
        if inputs.flow is None or abs(flow_setpoint - inputs.flow) > tuning.learn_max_flow_error:
            return "flow_off", None
        if not (
            tuning.learn_position_margin
            <= position
            <= 100.0 - tuning.learn_position_margin
        ):
            return "valve_end", None

        return "", None
    def _learn_offset_and_slope(self, demand: float, error: float, dt: float) -> None:
        """The regression: the mean error moves the curve's level at the
        weather it was measured in, and the error's growth with demand moves
        the slope once the weather has varied enough to see it."""
        tuning = self.tuning
        state = self.state
        hours = dt / 3600.0

        state.curve_fit.add(demand, error, dt)
        state.level_fit.add(demand, error, 0.0)
        _, error_slope, identifiable = state.curve_fit.fit(tuning.learn_min_variance)

        # The level reads the recent error (level_fit, faded by the clock);
        # the slope reads curve_fit, whose memory is hours of learning,
        # because it needs weather that has varied.
        #
        # The average error, not the fit's intercept. The intercept is the
        # error extrapolated back to no heating demand at all, which is a
        # long way outside the data: over one winter the demand only ever
        # spans a narrow band, so a noisy intercept comes out large and the
        # fit compensates with a slope of the opposite sign. Learning the
        # level from the mean and the slope from the covariance keeps the
        # two from cancelling each other out.
        #
        # room_gain converts an indoor error into the supply temperature
        # correction that answers it, made at the weather the error was
        # measured in - the fit's mean demand.
        level_outdoor = tuning.room_target - state.level_fit.mean_x * tuning.design_span
        self._raise_curve_at(
            level_outdoor,
            tuning.learn_offset_rate * hours * tuning.room_gain * state.level_fit.mean_y,
            slope_too=True,
        )

        if identifiable:
            # An error growing by error_slope per unit of demand needs
            # room_gain * error_slope more degrees of supply at the design
            # point, which is design_span degrees of slope.
            needed = tuning.room_gain * error_slope / tuning.design_span
            before = state.slope_trim
            mean_outdoor = tuning.room_target - state.curve_fit.mean_x * tuning.design_span
            held = self._curve_flow(mean_outdoor, self.effective_slope(), self.balance())
            pull = min(1.0, tuning.learn_slope_pull * hours) * before
            state.slope_trim = _clamp(
                before - pull + tuning.learn_slope_rate * hours * needed,
                tuning.slope_min - tuning.curve_slope,
                tuning.slope_max - tuning.curve_slope,
            )
            # Rotate the curve about the average operating point: a steeper
            # slope also raises the supply at today's weather, and without
            # taking that back out the two learners spend the winter undoing
            # each other. Only through the balance - the slope is what just
            # moved - and only as far as the balance reaches at this weather.
            moved = self._curve_flow(mean_outdoor, self.effective_slope(), self.balance())
            if moved != held:
                self._raise_curve_at(mean_outdoor, held - moved, slope_too=False)

    def _raise_curve_at(self, outdoor: float, supply: float, slope_too: bool) -> None:
        """Give `supply` degrees more (or less) supply at `outdoor`, split
        between the balance and the slope by how much each can do there.

        The balance moves the start of the curve and the slope its cold end,
        so a correction in mild weather is the balance's to make and one in
        the cold the slope's: at -8 outside a degree of balance is worth a
        sixth of a degree of supply, and asking it for the whole correction
        once sent it seventeen degrees in four days. The split is the
        smallest change, in units of how far each is expected to wander,
        that gives the supply asked for.
        """
        tuning = self.tuning
        state = self.state
        slope = self.effective_slope()
        balance = self.balance()
        if supply > 0.0:
            # At or above the balance the curve is flat: a little more
            # balance or slope asks for no more supply there, and a house
            # cold in that weather was never answered - on 2026-10-06 the
            # balance sat at 10.2 through a night 12 outside and 0.9 K cold.
            # Asked a degree below the balance instead, it climbs at the
            # learner's own pace until the curve reaches the weather.
            outdoor = min(outdoor, balance - 1.0)
        per_balance = (
            self._curve_flow(outdoor, slope, balance + 0.5)
            - self._curve_flow(outdoor, slope, balance - 0.5)
        )
        per_slope = (
            self._curve_flow(outdoor, slope + 0.01, balance)
            - self._curve_flow(outdoor, slope - 0.01, balance)
        ) / 0.02
        weight_balance = (per_balance * tuning.learn_balance_scale) ** 2
        weight_slope = (per_slope * tuning.learn_slope_scale) ** 2 if slope_too else 0.0
        # The balance's own share when the slope may not move, so the
        # rotation above cannot drive the balance harder than a level
        # correction would.
        room = weight_balance + (per_slope * tuning.learn_slope_scale) ** 2
        if room <= 0.0:
            return
        if per_balance != 0.0:
            state.balance_shift = _clamp(
                state.balance_shift
                + supply * per_balance * tuning.learn_balance_scale ** 2 / room,
                tuning.balance_shift_min,
                tuning.balance_shift_max,
            )
        if weight_slope > 0.0:
            state.slope_trim = _clamp(
                state.slope_trim
                + supply * per_slope * tuning.learn_slope_scale ** 2 / room,
                tuning.slope_min - tuning.curve_slope,
                tuning.slope_max - tuning.curve_slope,
            )

    def _learn_from_saturated_offset(self, error: float, dt: float) -> None:
        """The slope correction the regression cannot make - see below."""
        tuning = self.tuning
        state = self.state

        # A balance at its stop is itself the evidence that the shape is wrong.
        # (Written when this was a parallel offset of the supply; the
        # reasoning is the same for the balance, which answers the part of
        # the error that the weather does not explain.)
        #
        # The offset answers the part of the error that is the same at every
        # demand. If it has run out of travel and the house is still off
        # target in the same direction, then by construction the part it
        # cannot reach is the part that varies with demand - which is the
        # slope. Acting on that needs no extra measurement, only the
        # observation that one term has hit its stop.
        #
        # Without this a badly wrong slope hides behind the warm stop. The
        # stop clamps the indoor error to the same value at every demand, so
        # the covariance the slope is identified from goes to zero and the
        # curve looks correctly shaped while the house sits a degree over
        # target with the offset buried at its limit. Measured: a slope of
        # 1.7 against a house wanting 0.7 never moved at all.
        # Deliberately one-sided, because the censoring is one-sided. The
        # warm stop clamps how far above target the house can be measured;
        # nothing clamps how far below, so a curve that is too flat needs no
        # help here and gets none - tried symmetrically, it made a perfectly
        # healthy too-flat curve oscillate, because an offset sitting at its
        # limit is the *correct* answer for a flat curve and not evidence of
        # anything.
        #
        # What is evidence: the offset buried at its lower stop while the
        # house keeps warm-stopping anyway. Then the offset has demonstrably
        # run out of travel and the house is still too warm, so the part it
        # cannot reach is the part that grows with demand - the slope.
        stuck = (
            state.balance_shift <= tuning.balance_shift_min + 1e-6
            and state.seconds_warm >= tuning.learn_warm_trap_seconds
        )
        if stuck:
            state.seconds_offset_saturated += dt
        else:
            state.seconds_offset_saturated = 0.0

        if state.seconds_offset_saturated >= tuning.learn_saturated_seconds:
            # One nudge per accumulated hour, then the clock starts again.
            #
            # Integrating this every cycle instead overshoots, and for the
            # textbook reason: the house answers a change in the curve over
            # hours, so a correction that keeps accumulating while it waits
            # is winding up against dead time. Spending the whole hour in
            # one step and then standing still for the next gives the
            # building time to show what the last step did. Measured on a
            # curve set at 1.8 against a house wanting 0.7, continuous
            # integration undershot to 20.1 C; this settles without it.
            state.slope_trim = _clamp(
                state.slope_trim
                + tuning.learn_saturated_slope_rate
                * (state.seconds_offset_saturated / 3600.0)
                * tuning.room_gain
                * error
                / tuning.design_span,
                tuning.slope_min - tuning.curve_slope,
                tuning.slope_max - tuning.curve_slope,
            )
            state.seconds_offset_saturated = 0.0

    # --- the curve table ------------------------------------------------

    def table_rules(self) -> Rules:
        tuning = self.tuning
        return Rules(
            red_hours=tuning.table_red_hours,
            green_hours=tuning.table_green_hours,
            green_spread=tuning.table_green_spread,
        )

    def table_bins(self) -> list[Bin]:
        return self.points.bins(self.table_rules())

    def _collect_point(
        self,
        inputs: ControlInputs,
        dt: float,
        demand: float,
        indoor: float | None,
        indoor_slow: float | None,
        flow_setpoint: float,
        position: float,
        out: ControlOutput,
    ) -> None:
        """Record what the house needed, once it has sat still long enough.

        Still means: every learning gate open, the house near its target and
        not drifting (the twenty-minute and the two-hour indoor averages
        agree), and the outdoor temperature where its three-hour average is.
        An hour of that, and each cycle after it adds its share.

        What it needed is the supply it got, plus what the indoor error still
        asked for - at the curve's own sensitivity (curve.indoor_sensitivity),
        about two degrees of supply per degree inside, not the room
        correction's gain.
        """
        tuning = self.tuning
        state = self.state
        outdoor = state.outdoor.value
        why, left = self._evidence_gate(inputs, dt, demand, indoor_slow, flow_setpoint, position)
        if not why:
            if indoor is None or outdoor is None:
                why = "no_indoor" if indoor is None else "no_outdoor"
            elif abs(indoor_slow - tuning.room_target) > tuning.table_indoor_band:
                why = "off_target"
            elif abs(indoor - indoor_slow) > tuning.table_indoor_trend:
                why = "indoor_drift"
            elif abs(inputs.outdoor - outdoor) > tuning.table_outdoor_trend:
                why = "outdoor_drift"
        if why:
            state.seconds_steady = 0.0
            self._said(LEARN_TABLE, False, why, left)
            return
        state.seconds_steady += dt
        if state.seconds_steady < tuning.table_steady_seconds:
            self._said(LEARN_TABLE, False, "settling",
                       tuning.table_steady_seconds - state.seconds_steady)
            return

        room = tuning.room_target
        sensitivity = curve.indoor_sensitivity(
            outdoor, room, tuning.design_outdoor,
            self.effective_slope(), self.curvature(), self.balance(),
        )
        need = inputs.flow + sensitivity * (room - indoor_slow) - room
        self.points.add(
            outdoor=outdoor, need=need, wind=inputs.wind, hour=inputs.hour, hours=dt / 3600.0
        )
        out.flags.append("table_point")
        self._said(LEARN_TABLE, True, "", self.points.hours_at(outdoor, inputs.hour))

    def _refresh_table_fit(self, dt: float) -> None:
        """The house model's balance and the table's fit, once an hour."""
        tuning = self.tuning
        self._since_fit += dt
        if self._since_fit < tuning.table_fit_seconds:
            return
        self._since_fit = 0.0

        room = tuning.room_target
        # The house model's balance only from a model the setback would
        # trust - the same judgement, not a second one.
        distrust = self._distrust()
        self.house_balance = None if distrust else self.house.balance(room)
        self.house_night_balance = None if distrust else self.house.night_balance(room)
        balance = self.balance()
        if self._house_balance_steers():
            balance = _clamp(
                self.house_night_balance[0],
                room + tuning.balance_shift_min,
                room + tuning.balance_shift_max,
            )
        fit = fit_curve(
            self.table_bins(),
            room=room,
            design_outdoor=tuning.design_outdoor,
            balance=balance,
            # The installer's curve, not the one in use: the reach limit is
            # how far the curve may stand from it in all. Measured from the
            # curve in use, the same step would be allowed again every hour
            # and the curve would walk wherever the mild dots point.
            slope_prior=tuning.curve_slope,
            curvature=tuning.curve_curvature,
            bend_limit=tuning.curve_bend_limit,
            max_green_gap=tuning.table_max_green_gap,
        )
        # Through no dots the fit is the installer's curve handed back - not
        # something the table found, and not to be shown as if it were.
        self.table_fit = fit if fit.used else None

    def _house_balance_steers(self) -> bool:
        """Whether the table takes the house model's balance - see
        Tuning.table_balance_from_house."""
        return (
            self.tuning.table_balance_from_house
            and self.house_night_balance is not None
            and self.house_night_balance[1] <= self.tuning.table_balance_trust
        )

    def table_status(self) -> str:
        """Whether the curve table steers the curve, or why not: one of
        TABLE_STEERS, TABLE_SWITCHED_OFF, TABLE_LEARNING_OFF, TABLE_NO_FIT,
        TABLE_NO_CHAIN. The checks run in the order a person would fix
        them."""
        if not self.tuning.table_learning:
            return TABLE_SWITCHED_OFF
        if not self.tuning.learn_enabled:
            return TABLE_LEARNING_OFF
        if self.table_fit is None:
            return TABLE_NO_FIT
        if not self.table_fit.steerable:
            return TABLE_NO_CHAIN
        return TABLE_STEERS

    def table_steers(self) -> bool:
        return self.table_status() == TABLE_STEERS

    def _steer_toward_table(self, dt: float) -> None:
        """Move the curve toward the table's fit, slowly.

        Only when asked to (Tuning.table_learning) and while learning is on
        at all. A day's worth of steps is at most table_number_per_day EVR
        curves, table_balance_per_day degrees of balance and
        table_bend_per_day of bend: the house answers over days, and a fit
        that jumps would have the house swing after it.
        """
        tuning = self.tuning
        state = self.state
        fit = self.table_fit
        if fit is None or not self.table_steers() or dt <= 0.0:
            return
        days = dt / 86400.0
        room = tuning.room_target
        design = tuning.design_outdoor

        number = curve.evr_number(self.effective_slope(), room, design)
        goal = curve.evr_number(fit.slope, room, design)
        number = _toward(number, goal, tuning.table_number_per_day * days)
        state.slope_trim = _clamp(
            curve.evr_slope(number, room, design) - tuning.curve_slope,
            tuning.slope_min - tuning.curve_slope,
            tuning.slope_max - tuning.curve_slope,
        )

        if self._house_balance_steers():
            shift = _clamp(
                self.house_night_balance[0] - room,
                tuning.balance_shift_min,
                tuning.balance_shift_max,
            )
            state.balance_shift = _toward(
                state.balance_shift, shift, tuning.table_balance_per_day * days
            )

        if tuning.curve_bend_limit <= 0.0:
            state.bend = None
        elif state.bend is not None or fit.curvature != tuning.curve_curvature:
            state.bend = _toward(
                self.curvature(), fit.curvature, tuning.table_bend_per_day * days
            )

    def _calibrate_expected_delta(
        self, inputs: ControlInputs, demand: float, indoor_slow: float, dt: float
    ) -> None:
        tuning = self.tuning
        state = self.state

        # Calibrate the expected supply/return delta, but only while the
        # house is actually sitting on its target: that is the one condition
        # under which the measured delta is what "normal" looks like.
        # And only where the delta is readable at all: a week of mild-weather
        # near-zero deltas would otherwise teach it to expect nothing, and
        # the first cold week would then read every normal delta as a
        # radiator asking for more. Readable is where the term acts, with
        # the weight it acts with: until 2026-10-14 only full weight
        # counted, from 36.5 C, and Sodalen's October supply of 30-35 had
        # the term trimming on a delta it never calibrated.
        # And only from the delta the term itself reads: the supply sitting
        # still, through its filter - a valve on its way is not "normal".
        read = state.delta_filter.value
        weight = self._delta_weight(inputs.flow, indoor_slow) if inputs.flow is not None else 0.0
        if inputs.ret is None:
            self._said(LEARN_DELTA, False, "no_return", None)
        elif read is None or state.seconds_settled < tuning.delta_settle_seconds:
            self._said(LEARN_DELTA, False, "not_settled", None)
        elif abs(indoor_slow - tuning.room_target) > tuning.delta_cold_guard:
            self._said(LEARN_DELTA, False, "off_target_delta", None)
        elif weight <= 0.0:
            self._said(LEARN_DELTA, False, "lukewarm", None)
        else:
            scale = _clamp(
                read / (tuning.delta_design * min(1.2, demand)),
                tuning.delta_scale_min,
                tuning.delta_scale_max,
            )
            state.delta_scale.update(scale, dt * weight)
            self._said(LEARN_DELTA, True, "", state.delta_scale.value)

    def _learn_gain(
        self,
        inputs: ControlInputs,
        dt: float,
        flow_setpoint: float,
        position: float,
    ) -> None:
        """Measure how many degrees of supply temperature a percent of valve
        travel is worth, from settled operating points only.

        This is the static gain, which is exactly what the inner loop's Kp
        needs - and it is not knowable in advance: it depends on how hot the
        primary side is, how the valve is sized, and where the balancing
        valves ended up. Samples are only taken when the loop is on its
        setpoint, so each one is a genuine (position, temperature) pair
        rather than a point on a transient.
        """
        tuning = self.tuning
        state = self.state

        if dt <= 0.0 or inputs.flow is None:
            self._said(LEARN_GAIN, False, "no_flow", None)
            return
        if abs(flow_setpoint - inputs.flow) > 1.0:
            self._said(LEARN_GAIN, False, "flow_off", None)
            return
        # A cooling boiler is a different valve: the same opening gives less
        # supply every hour. Not a gain worth learning - after a burner
        # lockout on 2026-10-11 it had taught 0.07 against 0.4, and Kp went
        # to its ceiling.
        if inputs.boiler_failing:
            self._said(LEARN_GAIN, False, "boiler_failing", None)
            return
        if inputs.sweeping or state.seconds_since_sweep < tuning.learn_after_override_seconds:
            self._said(LEARN_GAIN, False, "sweeping" if inputs.sweeping else "after_sweep",
                       None if inputs.sweeping
                       else tuning.learn_after_override_seconds - state.seconds_since_sweep)
            return
        # Switched off, the burner went out too and the house cooled: on the
        # way back a boiler warming from cold and a cold house's low return
        # both make an opening worth a different supply than on target.
        # Learnt then, the gain fell from 0.62 to 0.28 in twenty minutes
        # (tests/test_learners_together.py).
        if state.seconds_since_off < tuning.learn_after_override_seconds:
            if state.cold_after == "off":
                self._said(LEARN_GAIN, False, COLD_WHY["off"], None)
            else:
                self._said(LEARN_GAIN, False, "after_off",
                           tuning.learn_after_override_seconds - state.seconds_since_off)
            return

        measured = inputs.position if inputs.position is not None else position
        if measured is None:
            self._said(LEARN_GAIN, False, "no_position", None)
            return
        # With the boiler read, the gain is the valve map's where the valve
        # works (ValveMap.gain_at), followed over gain_horizon_seconds: the
        # share of boiler water per percent there, times the boiler over the
        # return. Supply against position, all there is without the boiler,
        # is biased low on a loop that holds its setpoint - the supply hardly
        # moves while the valve follows the boiler. On 2026-10-13 it said
        # 0.05 against a valve worth 0.2-0.9, and tests/test_real_valve.py
        # finds the same 0.05 on Sodalen's valve.
        if inputs.boiler is not None and inputs.ret is not None:
            lift = float(inputs.boiler) - float(inputs.ret)
            # Warming from cold after a stop: a small difference over a
            # small one, and the supply against position no better.
            if lift < self.valve_map.min_lift:
                self._said(LEARN_GAIN, False, "low_lift", None)
                return
            mapped = self.valve_map.gain_at(float(measured), lift)
            if mapped is None:
                self._said(LEARN_GAIN, False, "map_unknown", None)
                return
            self._said(LEARN_GAIN, True, "", state.process_gain)
            share = 1.0 - math.exp(-dt / tuning.gain_horizon_seconds)
            state.process_gain += (_clamp(mapped, 0.05, 2.0) - state.process_gain) * share
            return

        self._said(LEARN_GAIN, True, "", state.process_gain)
        state.gain_fit.add(float(measured), float(inputs.flow), dt)
        _, gain, identifiable = state.gain_fit.fit(tuning.gain_min_variance)
        if identifiable and 0.05 <= gain <= 2.0:
            state.process_gain = gain


def _toward(value: float, goal: float, step: float) -> float:
    """value moved toward goal by at most step."""
    return value + _clamp(goal - value, -step, step)


def _rising_slowly(last: float | None, wanted: float, dt: float, seconds: float) -> float:
    """wanted, if it is no more than last; else last moved toward it with
    a time constant of seconds. Nothing last, or no time constant, is
    wanted as it is."""
    if last is None or wanted <= last or seconds <= 0.0:
        return wanted
    return last + (wanted - last) * (1.0 - math.exp(-max(0.0, dt) / seconds))


def _clamp(value: float, low: float, high: float) -> float:
    return min(high, max(low, value))
