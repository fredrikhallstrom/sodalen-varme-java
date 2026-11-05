"""Data model for the Sodalen heating controller.

Deliberately free of Home Assistant imports: everything the control law
needs is in these dataclasses, so the whole controller can be exercised by
the tests in heating/tests without Home Assistant installed. The Home
Assistant side (coordinator.py) does nothing but fill a ControlInputs in,
take a ControlOutput out, and persist LearnedState.

Temperatures are degrees Celsius, valve positions percent open (0 = fully
mixed to return water, 100 = fully open to the hot side), times seconds.
"""

from __future__ import annotations

from dataclasses import dataclass, field, fields

from .estimators import (
    DutyWatch,
    Ewma,
    Regression,
)
from . import curve
from .parsing import as_float, as_mapping, as_optional_float
from .sunterm import SunTerm


# How much each kind of indoor sensor is trusted, and how much of the
# reading is stratification rather than room temperature.
#
# The two ceiling sensors sit in the warmest air in the house, so they read
# high, and they read higher the hotter the radiators are - hence a default
# stratification term that scales with (flow - room) rather than a flat
# offset. The solar sensor is in the room the sun cooks in the afternoon; it
# is a real measurement of a real room, but letting it speak with a full
# vote would starve the north side of the house every sunny day, so it gets
# a small weight and its excursion above the other sensors is clamped.
ROLE_ROOM = "room"
ROLE_CEILING = "ceiling"
ROLE_SOLAR = "solar"
ROLES = (ROLE_ROOM, ROLE_CEILING, ROLE_SOLAR)

ROLE_DEFAULTS = {
    ROLE_ROOM: {"weight": 1.0, "stratification": 0.0},
    ROLE_CEILING: {"weight": 0.5, "stratification": 0.06},
    ROLE_SOLAR: {"weight": 0.15, "stratification": 0.0},
}




MODE_OFF = "off"
MODE_SUMMER = "summer"
MODE_WARM = "warm"
MODE_HEATING = "heating"
MODE_HOLD = "hold"
MODE_FAILSAFE = "failsafe"
MODE_COAST = "coast"
# Run by hand - see Tuning.emergency_position. Not a stop: the valve is open
# where it was told and the burner runs.
MODE_EMERGENCY = "emergency"

# The learners, as ControlOutput.learning names them, in the order a card
# lists them.
LEARN_CURVE = "kurva"
LEARN_TABLE = "tabell"
LEARN_HOUSE = "husmodell"
LEARN_DELTA = "delta"
LEARN_GAIN = "ventilforstarkning"
LEARN_VALVE_MAP = "ventilkarta"
# The sun's part of the indoor error (sunterm.py).
LEARN_SUN = "sol"
LEARNERS = (LEARN_CURVE, LEARN_SUN, LEARN_TABLE, LEARN_HOUSE, LEARN_DELTA, LEARN_GAIN,
            LEARN_VALVE_MAP)

# What ControlInputs.emergency can say. Anything else is off.
EMERGENCY_OFF = ""
EMERGENCY_VALVE = "valve"
EMERGENCY_FLOW = "flow"
EMERGENCY_KINDS = frozenset({EMERGENCY_VALVE, EMERGENCY_FLOW})

# The modes in which the valve is deliberately shut and the loop is not
# regulating anything. Named once because it is a property of the mode, not
# a list to be remembered at every call site: this tuple used to be written
# out in three separate places, and adding MODE_COAST meant finding all
# three. Missing one would not have raised - it would have left the burner
# running through a setback, or driven a valve that was meant to be closed.
MODES_STOPPED = frozenset(
    {MODE_OFF, MODE_SUMMER, MODE_WARM, MODE_COAST}
)

# Whether the curve table steers the curve, or why not - one answer, in the
# order a person would fix them (HeatingController.table_status).
TABLE_STEERS = "steers"
TABLE_SWITCHED_OFF = "switched_off"
TABLE_LEARNING_OFF = "learning_off"
TABLE_NO_FIT = "no_fit"
TABLE_NO_CHAIN = "no_chain"

# Why the delta term is silent. Kept apart from the flags because they are
# alternatives - exactly one reason, or none - rather than things that can
# pile up.
DELTA_QUIET_SENSOR = "sensor"
DELTA_QUIET_OFF = "off"
DELTA_QUIET_DEMAND = "demand"
DELTA_QUIET_LUKEWARM = "lukewarm"


@dataclass
class SensorReading:
    """One indoor temperature sensor as the controller sees it."""

    key: str
    temperature: float | None
    role: str = ROLE_ROOM
    weight: float = 1.0
    # Fixed calibration, added to the raw reading. This is the one thing
    # self-learning cannot discover on its own: with no thermometer that is
    # known to be right, a sensor reading 2 K high just makes the house run
    # 2 K cold and every learner happily converges on that. Measured once by
    # hand at sitting height during a still night - see heating/README.md.
    offset: float = 0.0
    # Subtracted per K of (flow - room_target), for sensors mounted in air
    # that gets hotter as the radiators do.
    stratification: float = 0.0

    # How old this sensor's reading may be before it counts as missing, or
    # None to use the circuit's sensor_max_age_seconds.
    #
    # Age is a proxy for "the sensor is still alive", and it is the right
    # proxy only for one that reports on a clock - a radio thermometer whose
    # battery has died keeps its last reading for ever and is otherwise
    # indistinguishable from a working one. An integration that writes only
    # when the value *changes* is a different animal: on a still night it can
    # look two hours old while being perfectly healthy, and for those the
    # honest check is whether Home Assistant says unavailable. Set 0 to turn
    # the age check off for such a sensor.
    max_age: float | None = None
    # False: read, shown and calibratable, but neither fused nor reported
    # as missing - a reference thermometer, not one the house runs on.
    active: bool = True
    # Shown on its own line beside the weighted temperature (CONF_FEATURED).
    # Display only.
    featured: bool = False

    @property
    def available(self) -> bool:
        return self.temperature is not None


@dataclass
class FusedSensor:
    """What the fusion made of one indoor sensor.

    Reported per sensor rather than just as the fused number, because
    "the house is 21.0" hides the two questions anyone actually asks of
    three sensors: which one disagrees, and how much of the disagreement
    the controller listened to.
    """

    key: str
    role: str
    raw: float | None
    corrected: float | None
    weight: float
    # True when this reading was held back from the rest of the house -
    # only the sun room, and only above.
    clamped: bool = False
    # The calibration offset the reading got: the YAML's, or the one the
    # calibration button worked out (calibration.py).
    offset: float = 0.0
    # See SensorReading.active and .featured.
    active: bool = True
    featured: bool = False

    @property
    def contributed(self) -> bool:
        return self.active and self.corrected is not None and self.weight > 0.0

    def as_dict(self) -> dict:
        return {
            "key": self.key,
            "role": self.role,
            "raw": self.raw,
            "corrected": self.corrected,
            "weight": self.weight,
            "clamped": self.clamped,
            "offset": self.offset,
            "active": self.active,
            "featured": self.featured,
        }


@dataclass
class Tuning:
    """Everything an installer may want to turn, with defaults for a
    Swedish radiator system designed around 55/45 degrees at -18 outside."""

    # --- Heating curve (feedforward from outdoor temperature) -----------
    room_target: float = 21.0
    design_outdoor: float = -18.0
    curve_slope: float = 0.9
    # The exponent on the share of design demand - see curve.py. Radiator
    # output rises faster than linearly with (water - room), n = 1.3, so
    # the supply has to fall more slowly than the demand: below 1.0, and
    # about 0.8 for panel radiators with the pump on a fixed speed. 1.0 is
    # a straight line; about 0.9 suits floor heating. It pivots about the
    # design point, so it never moves the cold end of the curve.
    #
    # Was 1.1 until 2026-09-29, bent the wrong way on a comment that had
    # the reasoning backwards. A flat parallel offset in mild weather hid
    # it; learning the balance temperature instead would not have.
    curve_curvature: float = 0.8
    flow_min: float = 20.0
    flow_max: float = 65.0
    # A house answers the weather over hours, not minutes, so the curve
    # reads a filtered outdoor temperature. Three hours is a light building;
    # raise it for heavy masonry.
    outdoor_filter_seconds: float = 3.0 * 3600.0
    # When it is colder than that mean, the curve alone reads this share of
    # the way down to the measured temperature: a three-hour mean is behind
    # by three hours of the fall, and the room correction only answers from
    # a house already under target. On the night of 2026-11-05 it fell 6 K
    # in seven hours and the house sank 0.9 K under its target. Warmer than
    # the mean it stays the mean, so a sunny morning takes nothing away, and
    # what the learners read is the mean in both directions.
    outdoor_fall_lead: float = 0.5
    # The most of the gap it counts, for a sensor gone wrong.
    outdoor_fall_limit: float = 4.0

    # --- Indoor feedback ------------------------------------------------
    indoor_filter_seconds: float = 20.0 * 60.0
    indoor_slow_filter_seconds: float = 2.0 * 3600.0
    # Flow temperature correction per K of indoor error. At flow 40 and room
    # 21, three degrees of flow is about 20% more radiator output, which is
    # the right size of answer to being one degree cold.
    room_gain: float = 3.0
    room_error_limit: float = 8.0
    # Above target + this, stop heating outright instead of trimming.
    warm_stop_margin: float = 1.0
    warm_stop_hysteresis: float = 0.3
    # Clamp on how far the solar sensor may read above the other sensors
    # before the fusion stops listening to the excess.
    solar_clamp: float = 1.5

    # --- Override contact ----------------------------------------------
    # A dry contact (wood stove running, night setback, airing the house)
    # that lowers the flow temperature by an adjustable amount.
    override_lowering: float = 5.0
    # Learning stays frozen this long after the contact opens again, so the
    # setback is not slowly learned away as if it were the house being cold.
    learn_after_override_seconds: float = 2.0 * 3600.0
    # After a sweep, a setback, the heat source falling short or the heating
    # switched off, the wait above counts from the house back on target
    # (Fredrik 2026-10-11) - but not for longer than this under it: a curve
    # too low never gets the house there, and that is the one thing it most
    # needs to learn.
    learn_target_wait_max_seconds: float = 6.0 * 3600.0
    # Unless the house is still getting warmer by at least this, K/h: then it
    # is on its way, not stuck. Six hours off on a cold night took a radiator
    # house 8.7 K down and seven and a half hours back, and a wait that gave
    # up on the way let the curve learn the climb
    # (tests/test_learners_together.py).
    learn_target_warming: float = 0.1
    # The sun is free heat: a house it holds over target while it heats
    # says nothing about the curve. Sodalen 7-13 October: the curve learnt
    # sunny hours as too much heat and its balance fell from 11.4 to 8.2,
    # and the night of the 14th stood 0.7 K cold. No learning while the sun
    # gives the windows more than learn_sunlight, kW/m2 (controller.sunlight_now,
    # sunlight.Walls: their mean by area), nor
    # for learn_after_sun_seconds after, while the walls give the day back:
    # on 8 October the hour after sunset took half a degree off the balance.
    # 47 W/m2 is the old index's 0.05 - a clear sky three degrees up, or a
    # full overcast with the sun twenty up - times SUNLIGHT_PER_INDEX, so a
    # house without walls is gated as before. On the walls it shut 1.7 h a
    # day over 8-18 October where the index shut 3.2 (on the windows by
    # area 1.74 h, 2026-10-18): the overcast noons and
    # the sun under the trees are daytime learning now - Fredrik
    # 2026-10-18: it may learn by day, so long as it reckons with the sun
    # on the walls. On Sodalen's walls in the closed loop the curve and the
    # green bins came out nearer the house than with the index
    # (tests/test_learners_together.py, TestTheSunOnTheWalls).
    learn_sunlight: float = 0.047
    learn_after_sun_seconds: float = 2.0 * 3600.0
    # And past that, while the house still stands more than
    # learn_after_sun_warm over target, up to learn_after_sun_max_seconds
    # from the sun: on 14 October the sun went 17:09, the wait ended 19:09
    # with the house 0.85 K over, and the evening took the balance from
    # 15.4 to 13.9 by morning - the house back on target at 22.
    learn_after_sun_warm: float = 0.3
    learn_after_sun_max_seconds: float = 6.0 * 3600.0
    # The sun's part of the indoor error, learnt (sunterm.py; Fredrik
    # 2026-10-18: take the sun away by day so it can learn then too, and let
    # it learn the sun so that its uncertainty comes down). The curve learns
    # in the sun with the sun's part taken off wherever that part is known
    # to within learn_sun_error K - its standard error times the light;
    # elsewhere the gate above stands, and the table and the delta keep it
    # always. The light is held over sun_heat_seconds: the walls and the
    # floors take it and give it back. On Sodalen's windows in the closed
    # loop the curve's mean error on day 10 was 0.20 K where the gate alone
    # left 0.55 (tests/test_learners_together.py).
    learn_sun_term: bool = True
    learn_sun_error: float = 0.1
    sun_heat_seconds: float = 3.0 * 3600.0
    sun_term_horizon_seconds: float = 21.0 * 24.0 * 3600.0
    # The same after the Shelly's watchdog ran the valve while Home
    # Assistant was away: learn_after_override_seconds, and then while the
    # house stands more than learn_after_failsafe_warm over target, up to
    # learn_after_failsafe_max_seconds. The night of 16 October the server
    # stood still 00:14-05:00, the watchdog ran 60-65 C into a house in its
    # warm stop, the house was over target until 02 the night after, and
    # the evening took 0.2 an hour off the balance. A day, because that
    # heat lasted most of one; only over, because the watchdog errs warm.
    learn_after_failsafe_warm: float = 0.3
    learn_after_failsafe_max_seconds: float = 24.0 * 3600.0

    # --- Supply/return delta compensation -------------------------------
    # Expected delta at the design point. Thermostatic radiator valves
    # closing because something else is heating the house collapses the
    # delta, which is the earliest and most honest signal that the curve is
    # asking for more heat than the house wants.
    delta_design: float = 10.0
    delta_gain: float = 1.2
    delta_lower_limit: float = 6.0
    delta_raise_limit: float = 2.0
    # Never let the delta term lower the flow while the house is actually
    # colder than this much below target: the indoor loop has the last word.
    delta_cold_guard: float = 0.3
    # Counted in calibrating time, which is a share of the clock: three
    # days of it found a pump turned down (tests/test_learners_together.py)
    # where a week left the term trimming the other way for weeks - the
    # sun's gate and the house on target keep free heat out of it.
    delta_expected_horizon_seconds: float = 3.0 * 24.0 * 3600.0
    # The circuit's delta is proportional to demand (Q = flow rate * delta,
    # the loss proportional to demand), so what the pump and the radiators
    # change is its scale, and that is what the calibration learns:
    # LearnedState.delta_scale times delta_design. Until 2026-10-14 it
    # learnt an offset, which is right only where it was learnt - Sodalen
    # gave 5.0 K at design on pump 2 and 7.4 on 1 against the 10 here, the
    # expected delta was too high in the cold, and the term took 2-3 K off
    # every cold night. Held to what a circuit can be: a week of radiator
    # valves shut is not a scale.
    delta_scale_min: float = 0.2
    delta_scale_max: float = 3.0
    delta_enabled: bool = True
    # Below this fraction of design demand the delta says nothing useful -
    # barely any water is moving.
    delta_min_demand: float = 0.15
    # Nor below this much supply temperature over the room. The return can
    # never be colder than the room, so at 24 C supply in a 21 C house the
    # delta is at most 3 K - the same size as the error of two DS18B20s
    # added together. Worse, down there the delta mostly measures the shunt
    # itself: with the valve shut the loop only recirculates its own return
    # and the delta falls to zero, which read as "the radiators want less"
    # and pushed the setpoint down, and the valve opening read as the
    # opposite. Logged 23-26 September at 22-28 C supply: the term swung
    # between -3 and +2 K in step with the valve, feeding its oscillation.
    delta_min_overtemp: float = 10.0
    # Over how many K above that the term fades in, rather than switching
    # on at once - a switch flips every cycle for a house sitting on it.
    delta_fade: float = 5.0
    # The delta is judged only while the supply sits still: within
    # delta_settle_band of its setpoint for delta_settle_seconds, through a
    # delta_filter_seconds filter. While the valve is on its way the last
    # correction is kept, for delta_hold_max_seconds at most. Without this
    # the term read the valve's own movement as the radiators' and drove it:
    # setpoint 30.5-38.7 every forty minutes on the morning of 2026-10-08.
    delta_settle_band: float = 1.0
    delta_settle_seconds: float = 300.0
    delta_filter_seconds: float = 900.0
    delta_hold_max_seconds: float = 1800.0
    # Not judged at all this long after the heating comes back from a stop:
    # the radiators and the pipes are cold, and the return comes back far
    # under the supply for half an hour whatever the house needs. Read as
    # demand, it raised the supply by two after the coast of 2026-10-09.
    delta_warmup_seconds: float = 2700.0
    # Radiators taking too little heat while the house is cold - a thermal
    # short circuit, air, a valve stuck shut. Judged only where the delta is
    # fully trusted, on the share of the last few days it held.
    starved_margin: float = 0.3
    starved_horizon_seconds: float = 3.0 * 24.0 * 3600.0
    starved_share: float = 0.5
    starved_min_hours: float = 6.0

    # --- Loss of the flow sensor -----------------------------------------
    # Without a supply temperature there is no inner loop to close, so the
    # valve holds its last position for a grace period and then goes to the
    # same place the Shelly script would put it: maximum heat. A house in a
    # Swedish winter survives too warm; it does not survive frozen pipes.
    # The Shelly script is the layer below this one and covers Home
    # Assistant itself being gone.
    failsafe_position: float = 100.0
    # The furthest the single learned gain may open the valve when the loop
    # starts again after a stop and the valve map cannot answer: a gain from
    # the dead first fifth of the travel asked for 98 % on 2026-10-08.
    resume_position_max: float = 50.0
    # After a stop the valve stands at its start position until the supply
    # reaches its setpoint, this long at most: the water in the pipes is
    # cold, and the sensor sees the valve's work only once it has come
    # round. Without it the first error put 18 % on top of a right start
    # and the integral wound on while waiting (2026-10-08).
    resume_hold_seconds: float = 180.0
    # The valve map as a feedforward while the loop regulates (2026-10-10):
    # each cycle the change in the position it gives for this setpoint,
    # return and boiler goes into the integral, so the valve closes as the
    # boiler warms instead of after the supply has gone too warm - the oil
    # burner swung the boiler 74-83 every 45 minutes and the supply a degree
    # either side with it. The PID trims what the map has wrong. 0 is off;
    # at most valve_feedforward_step a cycle, so a jump in a reading cannot
    # throw the valve.
    valve_feedforward_gain: float = 1.0
    valve_feedforward_step: float = 5.0
    failsafe_after_seconds: float = 600.0

    # --- Emergency running (ControlInputs.emergency) ---------------------
    # Running the house by hand when the regulation cannot be trusted: the
    # valve at a fixed position, which needs no sensor at all, or the supply
    # at a fixed temperature, which keeps the inner loop and takes the curve,
    # the room and every stop away. The boiler's protection and the sweep
    # still stop the burner. Nothing is learnt from it.
    emergency_position: float = 50.0
    emergency_flow: float = 40.0
    # How long the supply sensor has to be gone before the inner loop throws
    # away its integral and starts again from a computed position.
    #
    # A one-wire sensor that blinks out for a single cycle has invalidated
    # nothing: the valve has not moved, the house has not changed, and the
    # integral is still exactly right. Reseeding on every blink is what turns
    # a flaky sensor into an actuator that saws up and down all night - at
    # Sodalen the supply sensor was missing from a quarter of all readings,
    # which cost nine full reseeds in six hours and a valve that ramped from
    # a guess to the end stop each time.
    #
    # Long enough to ride out a dropout, short enough that a genuinely absent
    # sensor does not leave the loop integrating against nothing.
    reseed_after_seconds: float = 300.0

    # --- Sensor staleness -------------------------------------------------
    # A radio sensor that has gone quiet still has a state in Home
    # Assistant, and it looks exactly like a working one. Anything older
    # than this counts as missing, which is what puts the controller into
    # its documented degraded behaviour instead of regulating on the
    # temperature it saw before the battery died.
    sensor_max_age_seconds: float = 1800.0

    # --- Predictive setback ---------------------------------------------
    # Stop heating before the house needs it to stop, using what the weather
    # is about to do and what this building does when left alone.
    coast_enabled: bool = True
    # How far ahead to look. Beyond half a day a forecast is not worth the
    # arithmetic, and the house does not hold that long anyway.
    coast_horizon_hours: float = 12.0
    # How many standard deviations of the model's own uncertainty to keep in
    # hand. Two is about a one-in-twenty chance of being caught out, and the
    # cost is asymmetric - being cold is a comfort failure, leaving the heat
    # on ten minutes too long is a little fuel.
    coast_confidence: float = 2.0
    # How far below target the house may be allowed to drift *while the
    # model is still learning*. This is an exploration budget, not a comfort
    # tolerance: the only way to learn how a house coasts is to let it coast.
    #
    # It shrinks on its own. The allowance is the full band while the model
    # is unsure and goes to zero as it becomes confident, which is what makes
    # "never below target once it has learned" true without anybody deciding
    # when learning ended - and what re-opens the budget the first time the
    # weather goes somewhere the model has no data for.
    coast_learning_band: float = 1.0
    # How far below target the family accepts the house going during a
    # setback, once the model is trusted. Without it the setback could only
    # stop the heat while the house was predicted to stay on target -
    # which, once the learning budget had closed, left it nothing to do
    # but ride out warm stops. A degree is where most people stop noticing;
    # the dashboard can set it anywhere from none to three.
    coast_comfort_band: float = 1.0
    # A setback works against the weather to come (Fredrik 2026-10-10): it
    # may start only when the house model sees the house stop cooling on its
    # own - warmer weather, sun, the day turning - before it reaches the
    # floor, so the stored heat carries it over to the fine weather. Checked
    # every cycle, it starts as early as the house can bridge the gap. A
    # house warm from inside is the warm stop's, and weather that does not
    # turn is no saving, only a cycle. False is the old rule: whenever the
    # house holds longer than the lead.
    coast_bridge_only: bool = True
    # The learning hold (Fredrik 2026-10-07). With the setback starting only
    # from target, a mild night is coast and the way back and nothing else,
    # and the curve is never run still long enough to learn. So where the
    # curve table has fewer than coast_learning_cell_hours for this outdoor
    # bin and this part of the day, a setback may not start until that cell
    # has gained coast_learning_hold_seconds more of measurement - and waits
    # at most coast_learning_hold_max_seconds, so a house that never sits
    # still is not kept from saving for ever. 0 switches it off.
    #
    # Hours in the cell, not stillness in a row (2026-10-09): the first
    # version waited for three still hours on end, which any short break
    # started again - on 2026-10-08 the table gathered six hours during the
    # holds and the count never once reached three.
    coast_learning_hold_seconds: float = 2.0 * 3600.0
    coast_learning_hold_max_seconds: float = 6.0 * 3600.0
    # A quarter of table_green_hours: one part of the day's share of a green
    # bin.
    coast_learning_cell_hours: float = 6.0
    # The room correction's gain on the way back from a setback, until the
    # house is on target again. A setback leaves the house below target on
    # purpose, and the plain correction only holds it there: on 2026-10-06
    # it went from 20.6 at the end of a coast to 20.1 three hours later.
    # At the floor; it eases off to the plain gain at the target.
    coast_recovery_gain: float = 2.0
    # The uncertainty, in K/h, at which the model is considered to know what
    # it is doing. Above this the exploration budget is open.
    # How far outside its own data the house model has to be asked to reach
    # before the full learning band applies. Dimensionless - see
    # MultiRegression.reach. Familiar weather measures around 0.06 and
    # weather the model has never seen around 1.4, so 1.0 puts the budget at
    # essentially nothing once the building is learned and opens it wide the
    # first time the weather leaves what there is evidence for.
    #
    # This replaced an absolute uncertainty threshold of 0.08 K/h, which sat
    # *below* the noise floor these sensors can reach (~0.085) and so pinned
    # the budget at a full degree for ever - the house would never have
    # stopped exploring, whatever it learned.
    coast_unknown_reach: float = 1.0
    # How many hours of samples the house model needs before its predictions
    # may decide anything. Two days, because the daily terms cannot be told
    # from a trend in less than one and from a change in the weather in less
    # than two. Before that the model still learns and the card says how far
    # it has got; it just does not get to stop the heating.
    coast_min_hours: float = 48.0
    # How long from reopening the shunt to heat arriving in the rooms: valve
    # travel plus the water getting round. The burner's own lead comes from
    # its profile and is much longer, which is why the two stop at different
    # times rather than together.
    shunt_lead_seconds: float = 600.0
    # Extra hold the house must promise before a coast is *started*, on top
    # of the lead. Once coasting, the lead alone is enough to carry on.
    #
    # Every other stop here has hysteresis and this one needs it for the same
    # reason, only more so: the hold time is a prediction, and a prediction
    # that lands within a minute of the threshold will cross it back and
    # forth every cycle. Observed at Sodalen as a predicted hold of 9.8
    # minutes against a shunt lead of 10.0, which shut the valve and opened
    # it again every thirty seconds.
    coast_hysteresis_seconds: float = 900.0

    # --- Learning the building -------------------------------------------
    # A slope of a tenth of a degree an hour measured over thirty seconds is
    # entirely sensor noise, so the house model samples on its own clock.
    # Ten minutes suits an indoor sensor that reports continuously. Most do
    # not - a sensor that reports on change with 0.1 K resolution spends most
    # ten-minute windows reading exactly what it read before, which measures
    # a slope of zero and then a whole step. Match this to how often the
    # heaviest indoor sensor actually moves; see sodalen_heating.yaml, where
    # this house measured its own and raised it.
    thermal_sample_seconds: float = 600.0
    thermal_horizon_seconds: float = 21.0 * 24.0 * 3600.0
    # Forecast sources are scored against the sensor on the wall over a
    # fortnight, which is long enough to see a systematic offset and short
    # enough to follow one that is corrected.
    forecast_horizon_seconds: float = 14.0 * 24.0 * 3600.0

    # --- Summer stop ----------------------------------------------------
    summer_outdoor: float = 17.0
    summer_hysteresis: float = 1.5
    # The summer stop reads its own, much slower average than the curve
    # does. Three hours is right for a heating curve - that is roughly how
    # fast the house answers the weather - but it is far too quick to decide
    # a season with: one warm April afternoon reaches 17 degrees on a
    # three-hour mean and stops the heating, and the evening undoes it. A
    # day's mean says "this is the weather now" rather than "this is the
    # afternoon", which is the question the summer stop is actually asking.
    summer_filter_seconds: float = 24.0 * 3600.0

    # --- Heat source ------------------------------------------------------
    # A pellet burner, boiler or heat pump on the hot side of the mixing
    # valve. Closing the valve stops heat reaching the radiators but the
    # burner goes on holding a tank nobody is drawing from, which in summer
    # is pellets burnt for nothing.
    #
    # Two things make this more delicate than it looks. A burner that is
    # started and stopped every few minutes wears out its ignition, so an
    # off decision has to hold for a while before it is acted on - hence the
    # delay, which is long compared to the hysteresis on the stops. And a
    # burner that also makes the household's hot water must not be switched
    # off for the heating's convenience; that installation should leave
    # heat_source unconfigured, or turn these off.
    #
    # Turning it back on is immediate in every case: heat is urgent, and a
    # tank takes time to come up.
    heat_source_off_in_summer: bool = True
    # The warm stop is a normal few hours of a warm house, and cycling a
    # burner over that would be the same short cycling the delay exists to
    # avoid. Off by default.
    heat_source_off_when_idle: bool = False
    heat_source_off_when_disabled: bool = True
    heat_source_off_delay: float = 1800.0
    # The boiler's own guard: at this temperature the burner loses its power
    # whatever else is asked, and gets it back only once the boiler has
    # cooled to the release. 2026-10-06 the boiler went from 73 to 115 C
    # with the burner's own thermostat disconnected for a test, and nothing
    # here cut it. The Shelly on the burner's power has the same limits
    # (shelly/boiler_guard.js), for when Home Assistant is down.
    boiler_max: float = 95.0
    boiler_max_release: float = 85.0

    # --- Inner loop: flow temperature -> valve position ------------------
    cycle_seconds: float = 30.0
    pid_kp: float = 3.5
    pid_ti: float = 150.0
    pid_td: float = 20.0
    pid_deadband: float = 0.2
    min_position_step: float = 1.5
    # How far the valve may report sitting from the position it was last
    # sent, and for how long, before the controller stops believing its own
    # command and sends it again.
    #
    # The deadband above is measured against the last command rather than
    # against the valve, because a three-point actuator is still travelling
    # for most of a cycle. The cost of that is a loop which cannot see a
    # valve something else has moved - the Shelly's own over-temperature
    # guard closing it, the watchdog's anti-seize exercise leaving it shut,
    # a hand on a button. Left alone it would sit inside its deadband until
    # its own setpoint drifted far enough, which in a settled house is hours.
    #
    # Time is what tells "on its way there" from "stopped somewhere else",
    # so this has to be comfortably longer than the actuator's travel time -
    # five minutes against a ninety-second valve. It also bounds a genuine
    # disagreement: if the Shelly is holding the valve somewhere on purpose,
    # the two sides argue once per period instead of every cycle.
    position_drift_limit: float = 5.0
    position_drift_seconds: float = 300.0
    # Static gain the default Kp was tuned for, in K of flow per percent of
    # valve travel. The measured gain is divided into this to rescale Kp, so
    # a valve with twice the authority gets half the gain.
    gain_reference: float = 0.4
    gain_scale_min: float = 0.4
    gain_scale_max: float = 2.5
    # Take that gain from the valve map where the valve stands, and the
    # learned one only where the map cannot say (ValveMap.gain_at). A
    # valve that is flat for its first fifth and steep past a quarter has
    # no single gain: on 2026-10-13 the learned 0.05 gave the highest Kp,
    # the valve was worth 0.6-0.9 past 25 %, and it swung 0-60 %.
    valve_map_gain: bool = True
    # And that Kp gentler at once but bolder only over kp_rise_seconds: on
    # 15 October the map was flat at 10-15 % and steep either side, and the
    # valve, closing there as the burner started, took a Kp of 2.3 one cycle
    # and 8.75 the next and swung 4-19 % for three minutes. 0 follows the
    # map cycle by cycle, as until then.
    kp_rise_seconds: float = 900.0
    gain_horizon_seconds: float = 12.0 * 3600.0
    gain_min_variance: float = 9.0

    # --- Self-learning ---------------------------------------------------
    learn_enabled: bool = True
    # The slope's memory, in hours of learning: the slope needs the weather
    # to have varied, which in a mild autumn takes weeks of the clock.
    learn_horizon_seconds: float = 3.0 * 24.0 * 3600.0
    # The level's memory, in hours of the clock. The level answers the
    # error the house has now; read from the slope's memory it answered the
    # error of weeks ago, and on 2026-10-06 lowered the curve through a
    # night the house spent 0.9 K cold, because warm afternoons from before
    # two days of setbacks still outweighed it.
    learn_level_horizon_seconds: float = 12.0 * 3600.0
    # Per hour, not per cycle. A rate per cycle would silently retune the
    # whole learner the moment cycle_seconds changed, and at a 30 second
    # cycle it ran roughly ten times faster than intended - fast enough to
    # slam the offset into its limit inside a day.
    #
    # 0.08/h means a persistent one-degree indoor error is answered by about
    # a quarter of a degree of supply temperature per hour, so a full
    # correction takes half a day. Slower than feels necessary, on purpose:
    # this is integral action on a process that answers in hours, and the
    # failure mode of too much of it is a house that swings warm and cold on
    # a one-day period. It was 0.15 until 2026-10-25: two evenings a few
    # tenths warm with the household's own heat, which has no gate the way
    # the sun has, took the balance from 16.9 to 13.85, and the night after
    # stood 0.8 K cold with the curve asking too little. In the closed loops
    # (tests/test_learners_together.py) 0.15 also let sunny days walk the
    # balance 2 K off in three weeks, where 0.08 keeps it within 0.6; an
    # evening of 1.5 kW every day still swings it, a third less. The cost
    # is a curve that is right later: on the sun-on-the-walls run the mean
    # error on day 7 is 0.57 K instead of 0.42.
    learn_offset_rate: float = 0.08
    # The slope is a property of the building, so it is learned over days.
    learn_slope_rate: float = 0.02
    # How long the curve offset has to sit against its limit, with the house
    # still off target the same way, before the slope is moved on that
    # evidence alone. An hour, because the offset can touch its stop briefly
    # while the loop is still settling and a slope nudged by that would be
    # answering a transient. See _learn().
    learn_saturated_seconds: float = 3600.0
    # Net time spent in the warm stop before it counts as a trap rather than
    # a warm afternoon. Six hours, and it decays again while the loop heats
    # normally, so it measures a house that keeps overshooting rather than
    # one that had a sunny day.
    learn_warm_trap_seconds: float = 6.0 * 3600.0
    # How fast that clock drains again while the loop heats normally, as a
    # fraction of real time. A quarter, so the count only grows once the
    # house is warm-stopping more than a fifth of the time: a healthy curve
    # measured 0-2 percent here and a trapped one 26-38, which is a wide
    # enough gap that the exact number does not matter much. Draining at
    # full speed instead never accumulated at all, because even a badly
    # trapped house still spends most of its time heating.
    learn_warm_trap_decay: float = 0.25
    # Rate for that correction. Larger than learn_slope_rate on purpose: it
    # only ever runs when the ordinary slope learner has been proven blind,
    # and it has to cover the whole error before the season ends.
    learn_saturated_slope_rate: float = 0.15
    # Per hour, pulling the learned slope back toward the installer's value.
    #
    # Over one winter the outdoor temperature only ever spans a band, and
    # within a band a steeper slope plus a lower offset fits the same data
    # as a flatter slope plus a higher offset. The pair is only weakly
    # identifiable, so without this the two wander along that ridge - three
    # weeks of simulated weather moved a slope that started correct by 0.07
    # - and the wandering only shows up as a mistake when weather colder
    # than anything learned from finally arrives. A half-life of about two
    # months costs a fraction of a degree of learning accuracy and bounds
    # the drift.
    learn_slope_pull: float = 0.0005
    # How far the learned balance temperature may move from the room
    # target, in degrees of outdoor temperature. Down is free heat; a
    # well-insulated house with people in it can need no heat above 10
    # degrees outside, so the room is generous. Up is for a house losing
    # more than its design says, and needs far less.
    balance_shift_min: float = -14.0
    balance_shift_max: float = 3.0
    # How far the balance and the slope are each expected to wander, which
    # decides how a correction is split between them (see
    # HeatingController._raise_curve_at): a correction goes mostly to the
    # one that moves the supply most at that weather, in these units.
    learn_balance_scale: float = 3.0
    learn_slope_scale: float = 0.05
    slope_min: float = 0.2
    slope_max: float = 2.0
    # The slope can only be told apart from the offset once the regression
    # has seen a spread of outdoor temperatures. Below this variance in
    # normalised demand only the offset moves.
    learn_min_variance: float = 0.01
    # Learning needs the inner loop to be delivering the setpoint and the
    # valve to have somewhere left to go, otherwise the curve gets blamed
    # for something it did not do.
    learn_max_flow_error: float = 3.0
    learn_position_margin: float = 3.0
    learn_min_demand: float = 0.15
    learn_settle_seconds: float = 3600.0

    # --- The curve table (points.py, curvefit.py) -----------------------
    # Whether the table may steer the curve. On, it takes over by itself
    # once its trusted dots make an unbroken chain (table_max_green_gap,
    # curvefit.MIN_CHAIN): the fit then steers the slope and the bend, the
    # house model the balance, and the regression stands down - two
    # learners on one curve would undo each other. Until then the table
    # collects, fits and shows while the regression learns as before. Off
    # is the emergency stop: the table never steers.
    table_learning: bool = True
    # How far apart, in degrees outdoors, two trusted dots may be and still
    # be one chain. The bins are two degrees wide, so 2 means neighbouring
    # bins and 4 lets one empty bin between. Fredrik's rule.
    table_max_green_gap: float = 2.0
    # How far the bend may move the middle of the curve from the physics'
    # curve, in degrees of supply. 0 keeps the bend at curve_curvature.
    curve_bend_limit: float = 2.0
    # How long a measurement keeps half its say. Months, so last winter's
    # cold snap still counts when the next one comes.
    table_half_life_days: float = 60.0
    # A measurement is taken once the house has sat still this long: heating
    # on its target, the indoor temperature not drifting, the weather steady.
    table_steady_seconds: float = 3600.0
    table_indoor_band: float = 0.5
    table_indoor_trend: float = 0.15
    # How far the outdoor temperature may be from its three-hour average.
    # For weather changes, not mornings and evenings: a steady 0.4-0.6 K an
    # hour puts the average a degree and more behind, and at 1.0 the morning
    # of 2026-10-08 got nothing from an hour and a half of a perfectly still
    # house. 2.0 since then (Fredrik).
    table_outdoor_trend: float = 2.0
    # When a bin counts - see points.Rules.
    table_red_hours: float = 3.0
    table_green_hours: float = 24.0
    table_green_spread: float = 1.5
    # How fast the curve may follow the fit, per day.
    table_number_per_day: float = 0.1
    table_balance_per_day: float = 0.5
    table_bend_per_day: float = 0.01
    # The house model's balance steers once its standard error is below
    # this, in K. Until then the balance stays where the curve has it.
    table_balance_trust: float = 2.0
    # Whether it steers at all, and then the night's balance. Off since
    # 2026-10-14: the day's balance is the household's day averaged out
    # (thermal.py), and the heating runs mostly at night, when the household
    # gives less - Sodalen replayed to 11.2 against the 14 its nights need.
    # Trusted, it would have walked the curve's balance down half a degree a
    # day, into the cold nights of the 14th. The night's balance
    # (HouseModel.night_balance) is shown beside it until it has been seen
    # to hold, and then this goes on.
    table_balance_from_house: bool = False
    # The night the house model's night balance is taken from: these local
    # hours, the sun down and learn_after_sun_seconds past its warmth. Fixed
    # hours, so every night holds the same share of the household's day -
    # nights begun at sunset took in the evening on overcast days and not on
    # clear ones, and missed a test house's night by a degree.
    night_start_hour: float = 22.0
    night_end_hour: float = 6.0
    # How often the fit is made again.
    table_fit_seconds: float = 3600.0

    def clamp_flow(self, flow: float) -> float:
        return min(self.flow_max, max(self.flow_min, flow))

    @property
    def design_span(self) -> float:
        """Design (room - outdoor) difference, never zero or negative."""
        return max(1.0, self.room_target - self.design_outdoor)





# What leaves the house under target on its way back - a sweep, a setback,
# the heat source falling short, the heating switched off - each with its
# own wait afterwards.
COLD_CAUSES = ("sweep", "coast", "shortfall", "off")

@dataclass
class LearnedState:
    """What the controller knows about this particular house.

    Persisted across restarts. Losing it is not dangerous - every field has
    a defensible default and the learners rebuild within a day or two - but
    keeping it means a Home Assistant update does not cost the house a cold
    evening.
    """

    # Where the curve starts: the balance temperature below which the house
    # needs heat, as degrees from the room target. Negative is free heat.
    # Replaced a parallel shift of the supply (curve_offset) on 2026-09-29
    # - see curve.py for why.
    balance_shift: float = 0.0
    # Added to Tuning.curve_slope.
    slope_trim: float = 0.0
    # The bend learned from the curve table, or None for Tuning's.
    bend: float | None = None
    # Measured static valve gain, K of flow per percent of travel.
    process_gain: float = 0.4

    # Measured supply/return delta over the delta the design figures
    # expect, so the model calibrates itself to this pump and these
    # radiators (Tuning.delta_scale_min). Long horizon on purpose: an
    # evening of free heat must not be learned as normal, but a rebalanced
    # system - or a pump turned down - must eventually be.
    delta_scale: Ewma = field(default_factory=lambda: Ewma(3.0 * 24.0 * 3600.0))
    # The delta as the term reads it, and what it last made of it - see
    # Tuning.delta_settle_band.
    delta_filter: Ewma = field(default_factory=lambda: Ewma(900.0))
    delta_trim_held: float = 0.0
    seconds_settled: float = 0.0
    seconds_unsettled: float = 0.0
    # Last cycle's supply setpoint, for whether the supply has reached it.
    last_flow_setpoint: float | None = None
    # Time since the heating was last stopped - see
    # Tuning.delta_warmup_seconds. Large until the first stop.
    seconds_since_stop: float = 1e9

    outdoor: Ewma = field(default_factory=lambda: Ewma(3.0 * 3600.0))
    # The same measurement on a season's timescale, for the summer stop.
    outdoor_summer: Ewma = field(default_factory=lambda: Ewma(24.0 * 3600.0))
    indoor: Ewma = field(default_factory=lambda: Ewma(20.0 * 60.0))
    indoor_slow: Ewma = field(default_factory=lambda: Ewma(2.0 * 3600.0))
    # The light on the walls, kW/m2, as the house holds it, and the sun's
    # part of the indoor error learnt against it (Tuning.learn_sun_term).
    sun_heat: Ewma = field(default_factory=lambda: Ewma(3.0 * 3600.0))
    sun_term: SunTerm = field(default_factory=SunTerm)

    curve_fit: Regression = field(default_factory=lambda: Regression(3.0 * 24.0 * 3600.0))
    # The recent indoor error and demand, faded by the clock: the curve's
    # level (Tuning.learn_level_horizon_seconds).
    level_fit: Regression = field(default_factory=lambda: Regression(12.0 * 3600.0))
    gain_fit: Regression = field(default_factory=lambda: Regression(12.0 * 3600.0))

    # Inner loop state, persisted for the same reason: a restart should not
    # dump the integral and drive the valve around while it rebuilds.
    integral: float = 0.0
    last_flow: float | None = None
    # The position the controller last asked for, which is not the same as
    # the one the Shelly reports: the valve is still travelling for most of
    # a cycle. Deadband decisions and bumpless restart use this one.
    position: float | None = None

    # How long the valve has been reporting a position other than the one it
    # was last sent. See Tuning.position_drift_seconds.
    seconds_adrift: float = 0.0
    # How long the curve offset has been stuck against its limit while the
    # house is still off target in the same direction.
    seconds_offset_saturated: float = 0.0
    # Net time in the warm stop: up while it is stopped, down while it heats.
    seconds_warm: float = 0.0
    # How much of the recent heating time the radiators took too little heat
    # from a cold house - see Tuning.starved_margin.
    starved: DutyWatch = field(
        default_factory=lambda: DutyWatch(3.0 * 24.0 * 3600.0)
    )

    learn_hours: float = 0.0
    # How long the house has sat still enough for the curve table.
    seconds_steady: float = 0.0
    # How long the learning hold has kept a setback from starting - see
    # Tuning.coast_learning_hold_seconds. Back to nought when one starts.
    seconds_learning_hold: float = 0.0
    # The table cell the hold is filling and its hours when it began there
    # ("index:part of day", PointTable.key_at) - so what counts is what the
    # hold brought in.
    learning_hold_cell: str | None = None
    learning_hold_base: float = 0.0
    # How long heating has been stopped, for the heat source's off delay.
    # Persisted so a Home Assistant restart in July does not re-light the
    # burner for half an hour.
    seconds_stopped: float = 0.0
    seconds_without_flow: float = 0.0
    # Long ago, not just now: a state with no history has had no override,
    # and zero here told the learner one had just ended - two hours of
    # learning frozen for a contact that is not even wired. Measured the
    # morning a reset still rebuilt the whole state: 2.26 h learned, reset
    # at 08:51, frozen until 10:51, and the house went into its warm stop at
    # 10:24, so the card read 0 h for the rest of the day.
    seconds_since_override: float = 1e6
    # Since the last predictive setback, for the same learning freeze.
    seconds_since_coast: float = 1e6
    # Since the sun last gave the walls more than Tuning.learn_sunlight.
    seconds_since_sun: float = 1e6
    # Since the burner was last kept off for a sweep (sweep.py) - a house
    # living on what was left in the boiler says nothing about the curve.
    seconds_since_sweep: float = 1e6
    # Since the heat source last fell short: the valve wide open and the
    # supply still well under its setpoint (2026-10-11, the oil burner out
    # for five hours). The house it leaves cold says nothing about the curve.
    seconds_since_shortfall: float = 1e6
    # Since the heating was last switched off (MODE_OFF): the valve shut and
    # the house cooling, and a curve that learnt from it on the way back
    # rose 14 K (tests/test_learners_together.py).
    seconds_since_off: float = 1e6
    # Since the Shelly's watchdog last handed the valve back, as its
    # heartbeat answer says (ControlInputs.since_failsafe).
    seconds_since_failsafe: float = 1e6
    # Which of COLD_CAUSES the house is still under target after, and for
    # how long; None once it is back.
    cold_after: str | None = None
    seconds_cold: float = 0.0
    seconds_since_target_change: float = 0.0
    warm_stopped: bool = False
    summer_stopped: bool = False
    # Whether a predictive setback is under way, so that starting one and
    # carrying one on can ask for different things.
    coasting: bool = False
    # From the end of a setback until the house is on target again: the
    # way back gets more room correction (Tuning.coast_recovery_gain).
    recovering: bool = False
    # The boiler reached boiler_max and has not cooled to the release yet.
    boiler_overheated: bool = False
    # Set whenever the remembered integral stops describing the valve, so
    # the next heating cycle reseeds it instead of winding up from zero.
    needs_reseed: bool = True
    # The start position after a stop, and how long it is still held - see
    # Tuning.resume_hold_seconds.
    resume_position: float | None = None
    resume_hold_left: float = 0.0
    # Last cycle's valve-map position, for the feedforward's change - see
    # Tuning.valve_feedforward_gain.
    last_feedforward: float | None = None
    # The Kp the inner loop last used - see Tuning.kp_rise_seconds.
    last_kp: float | None = None

    def forget(self, tuning: Tuning) -> None:
        """Throw away what has been learned, and keep what has been measured.

        Those are different things living in the same object. The curve's
        offset and slope, the valve's gain, the expected delta and the two
        regressions are conclusions about this house and a reset is meant to
        discard them. The filtered outdoor and indoor temperatures, the
        timers and the inner loop's integral are not conclusions - they are
        where the house is right now.

        Discarding the measurements as well does real harm, and it is not
        theoretical: with the outdoor filter emptied and the outdoor sensor
        briefly unavailable, the controller correctly assumed design cold -
        the cold end of the curve - and asked for 56 degrees of supply on a
        mild September morning. The fallback is right. Handing it an empty
        filter for no reason was not.
        """
        self.balance_shift = 0.0
        self.slope_trim = 0.0
        self.bend = None
        self.process_gain = tuning.gain_reference
        self.delta_scale = Ewma(tuning.delta_expected_horizon_seconds)
        self.starved = DutyWatch(tuning.starved_horizon_seconds)
        self.curve_fit = Regression(tuning.learn_horizon_seconds)
        self.level_fit = Regression(tuning.learn_level_horizon_seconds)
        self.gain_fit = Regression(tuning.gain_horizon_seconds)
        self.learn_hours = 0.0
        self.retune(tuning)

    def retune(self, tuning: Tuning) -> None:
        """Adopt filter and horizon lengths from the current tuning.

        Filters carry their own time constant so they can be updated on an
        irregular cycle, which means a tuning change would otherwise be
        ignored by any filter restored from storage.
        """
        self.delta_scale.seconds = tuning.delta_expected_horizon_seconds
        self.sun_heat.seconds = tuning.sun_heat_seconds
        self.sun_term.fit.horizon = tuning.sun_term_horizon_seconds
        self.delta_filter.seconds = tuning.delta_filter_seconds
        self.starved.seconds = tuning.starved_horizon_seconds
        self.outdoor.seconds = tuning.outdoor_filter_seconds
        self.outdoor_summer.seconds = tuning.summer_filter_seconds
        self.indoor.seconds = tuning.indoor_filter_seconds
        self.indoor_slow.seconds = tuning.indoor_slow_filter_seconds
        self.curve_fit.horizon = tuning.learn_horizon_seconds
        self.level_fit.horizon = tuning.learn_level_horizon_seconds
        self.gain_fit.horizon = tuning.gain_horizon_seconds

    def as_dict(self) -> dict:
        return {
            "balance_shift": self.balance_shift,
            "slope_trim": self.slope_trim,
            "bend": self.bend,
            "process_gain": self.process_gain,
            "delta_scale": self.delta_scale.as_dict(),
            "delta_filter": self.delta_filter.as_dict(),
            "delta_trim_held": self.delta_trim_held,
            "seconds_settled": self.seconds_settled,
            "seconds_unsettled": self.seconds_unsettled,
            "last_flow_setpoint": self.last_flow_setpoint,
            "seconds_since_stop": self.seconds_since_stop,
            "starved": self.starved.as_dict(),
            "outdoor": self.outdoor.as_dict(),
            "outdoor_summer": self.outdoor_summer.as_dict(),
            "indoor": self.indoor.as_dict(),
            "indoor_slow": self.indoor_slow.as_dict(),
            "sun_heat": self.sun_heat.as_dict(),
            "sun_term": self.sun_term.as_dict(),
            "curve_fit": self.curve_fit.as_dict(),
            "level_fit": self.level_fit.as_dict(),
            "gain_fit": self.gain_fit.as_dict(),
            "integral": self.integral,
            "last_flow": self.last_flow,
            "position": self.position,
            "seconds_adrift": self.seconds_adrift,
            "seconds_offset_saturated": self.seconds_offset_saturated,
            "seconds_warm": self.seconds_warm,
            "learn_hours": self.learn_hours,
            "seconds_steady": self.seconds_steady,
            "seconds_learning_hold": self.seconds_learning_hold,
            "learning_hold_cell": self.learning_hold_cell,
            "learning_hold_base": self.learning_hold_base,
            "seconds_stopped": self.seconds_stopped,
            "seconds_without_flow": self.seconds_without_flow,
            "seconds_since_override": self.seconds_since_override,
            "seconds_since_coast": self.seconds_since_coast,
            "seconds_since_sun": self.seconds_since_sun,
            "seconds_since_sweep": self.seconds_since_sweep,
            "seconds_since_shortfall": self.seconds_since_shortfall,
            "seconds_since_off": self.seconds_since_off,
            "seconds_since_failsafe": self.seconds_since_failsafe,
            "cold_after": self.cold_after,
            "seconds_cold": self.seconds_cold,
            "seconds_since_target_change": self.seconds_since_target_change,
            "warm_stopped": self.warm_stopped,
            "summer_stopped": self.summer_stopped,
            "coasting": self.coasting,
            "recovering": self.recovering,
            "boiler_overheated": self.boiler_overheated,
            "needs_reseed": self.needs_reseed,
            "resume_position": self.resume_position,
            "resume_hold_left": self.resume_hold_left,
            "last_feedforward": self.last_feedforward,
            "last_kp": self.last_kp,
        }

    @classmethod
    def from_dict(cls, data: dict | None, tuning: Tuning) -> LearnedState:
        data = as_mapping(data)
        state = cls(
            balance_shift=as_float(data.get("balance_shift"), 0.0),
            slope_trim=as_float(data.get("slope_trim"), 0.0),
            bend=as_optional_float(data.get("bend")),
            process_gain=as_float(data.get("process_gain"), tuning.gain_reference),
            # An offset stored before 2026-10-14 is not a scale: started over.
            delta_scale=Ewma.from_dict(
                data.get("delta_scale"), tuning.delta_expected_horizon_seconds
            ),
            delta_filter=Ewma.from_dict(data.get("delta_filter"), tuning.delta_filter_seconds),
            delta_trim_held=as_float(data.get("delta_trim_held"), 0.0),
            seconds_settled=as_float(data.get("seconds_settled"), 0.0),
            seconds_unsettled=as_float(data.get("seconds_unsettled"), 0.0),
            last_flow_setpoint=as_optional_float(data.get("last_flow_setpoint")),
            seconds_since_stop=as_float(data.get("seconds_since_stop"), 1e9),
            starved=DutyWatch.from_dict(
                data.get("starved"), tuning.starved_horizon_seconds
            ),
            outdoor=Ewma.from_dict(data.get("outdoor"), tuning.outdoor_filter_seconds),
            outdoor_summer=Ewma.from_dict(
                data.get("outdoor_summer"), tuning.summer_filter_seconds
            ),
            indoor=Ewma.from_dict(data.get("indoor"), tuning.indoor_filter_seconds),
            indoor_slow=Ewma.from_dict(data.get("indoor_slow"), tuning.indoor_slow_filter_seconds),
            sun_heat=Ewma.from_dict(data.get("sun_heat"), tuning.sun_heat_seconds),
            sun_term=SunTerm.from_dict(data.get("sun_term"), tuning.sun_term_horizon_seconds),
            curve_fit=Regression.from_dict(data.get("curve_fit"), tuning.learn_horizon_seconds),
            level_fit=Regression.from_dict(data.get("level_fit"), tuning.learn_level_horizon_seconds),
            gain_fit=Regression.from_dict(data.get("gain_fit"), tuning.gain_horizon_seconds),
            integral=as_float(data.get("integral"), 0.0),
            last_flow=as_optional_float(data.get("last_flow")),
            position=as_optional_float(data.get("position")),
            seconds_adrift=as_float(data.get("seconds_adrift"), 0.0),
            seconds_offset_saturated=as_float(data.get("seconds_offset_saturated"), 0.0),
            seconds_warm=as_float(data.get("seconds_warm"), 0.0),
            learn_hours=as_float(data.get("learn_hours"), 0.0),
            seconds_steady=as_float(data.get("seconds_steady"), 0.0),
            seconds_learning_hold=as_float(data.get("seconds_learning_hold"), 0.0),
            learning_hold_cell=data.get("learning_hold_cell") if isinstance(data.get("learning_hold_cell"), str) else None,
            learning_hold_base=as_float(data.get("learning_hold_base"), 0.0),
            seconds_stopped=as_float(data.get("seconds_stopped"), 0.0),
            seconds_without_flow=as_float(data.get("seconds_without_flow"), 0.0),
            seconds_since_override=as_float(data.get("seconds_since_override"), 1e6),
            seconds_since_coast=as_float(data.get("seconds_since_coast"), 1e6),
            seconds_since_sun=as_float(data.get("seconds_since_sun"), 1e6),
            seconds_since_sweep=as_float(data.get("seconds_since_sweep"), 1e6),
            seconds_since_shortfall=as_float(data.get("seconds_since_shortfall"), 1e6),
            seconds_since_off=as_float(data.get("seconds_since_off"), 1e6),
            seconds_since_failsafe=as_float(data.get("seconds_since_failsafe"), 1e6),
            # shortfall_cold was its name for a morning (ba43a64).
            cold_after=(data.get("cold_after") if data.get("cold_after") in COLD_CAUSES
                        else "shortfall" if data.get("shortfall_cold") else None),
            seconds_cold=as_float(data.get("seconds_cold", data.get("seconds_shortfall_cold")), 0.0),
            seconds_since_target_change=as_float(data.get("seconds_since_target_change"), 0.0),
            warm_stopped=bool(data.get("warm_stopped", False)),
            summer_stopped=bool(data.get("summer_stopped", False)),
            coasting=bool(data.get("coasting", False)),
            recovering=bool(data.get("recovering", False)),
            boiler_overheated=bool(data.get("boiler_overheated", False)),
            needs_reseed=bool(data.get("needs_reseed", True)),
            resume_position=as_optional_float(data.get("resume_position")),
            resume_hold_left=as_float(data.get("resume_hold_left"), 0.0),
            last_feedforward=as_optional_float(data.get("last_feedforward")),
            last_kp=as_optional_float(data.get("last_kp")),
        )
        if "balance_shift" not in data and "curve_offset" in data:
            state.balance_shift = _balance_from_offset(
                as_float(data.get("curve_offset"), 0.0), state, tuning
            )
        state.retune(tuning)
        return state


def effective_curvature(tuning: Tuning, state: LearnedState) -> float:
    """The bend the curve runs on: the curve table's when it has learned
    one, otherwise the setting."""
    return tuning.curve_curvature if state.bend is None else state.bend


# The curve a stored curve_offset was learned on: a straight parallel shift
# of the supply, bent by the curvature the default then had.
_OFFSET_ERA_CURVATURE = 1.1
# Where to match the old curve when there is no record of the weather it
# was learned in: mild autumn, which is when the change was made.
_OFFSET_ERA_OUTDOOR = 10.0


def _balance_from_offset(offset: float, state: "LearnedState", tuning: Tuning) -> float:
    """The balance shift that gives the house the supply it had.

    Matched at the weather the offset was learned in - the curve fit's mean
    demand - so the house gets the same supply there the day after the
    change as the day before, and nothing jumps. Only the shape away from
    that point changes, which is the point of the change.
    """
    room = tuning.room_target
    outdoor = _OFFSET_ERA_OUTDOOR
    if state.curve_fit.weight > 0.0:
        outdoor = room - state.curve_fit.mean_x * tuning.design_span
    slope = min(tuning.slope_max, max(tuning.slope_min, tuning.curve_slope + state.slope_trim))
    demand = max(0.0, room - outdoor) / tuning.design_span
    old = room + slope * tuning.design_span * demand ** _OFFSET_ERA_CURVATURE + offset
    balance = curve.balance_through(
        outdoor, old, room, tuning.design_outdoor, slope, tuning.curve_curvature
    )
    return min(tuning.balance_shift_max, max(tuning.balance_shift_min, balance - room))


@dataclass
class ControlInputs:
    """One cycle's worth of measurements."""

    dt: float
    flow: float | None = None
    ret: float | None = None
    outdoor: float | None = None
    indoor: list[SensorReading] = field(default_factory=list)
    override_active: bool = False
    # Valve position as reported by the Shelly, if it knows one.
    position: float | None = None
    target_changed: bool = False
    heating_enabled: bool = True
    # True while this boiler is making the household's hot water. Nothing to
    # do with heating the house, and it outranks every reason to stop.
    hot_water: bool = False
    # Wind and sun now, and the weather ahead as (hours from now, conditions)
    # once the sources have been combined. Plain tuples rather than a type
    # from thermal.py, which would make model.py import it and it imports
    # model.py.
    wind: float | None = None
    wind_bearing: float | None = None
    sun_elevation: float | None = None
    cloud: float | None = None
    # The sun on the house's walls now, kW/m2 (sunlight.Walls, from the place,
    # the clock and the cloud). None where there are no walls configured:
    # then the sun is the old guess from its height and the cloud.
    sunlight: float | None = None
    # Local hour of day, 0-24. The household is a heat source with a daily
    # rhythm, and the model learns that rhythm rather than being told it.
    hour: float | None = None
    forecast: list = field(default_factory=list)
    # The boiler's own temperature, for the valve map. None without a
    # boiler sensor, and nothing but the map reads it.
    boiler: float | None = None
    # True while the boiler watch (boilerwatch.py) says the boiler is failing
    # or recovering: a boiler cooling while it should burn. The same opening
    # then gives less supply every hour, so the valve's gain is not learnt.
    boiler_failing: bool = False
    # True while the burner is kept off for a sweep (sweep.py). Outranks even
    # the hot water: the sweeper's brush goes where the flame was.
    sweeping: bool = False
    # Emergency running chosen on the dashboard - EMERGENCY_VALVE,
    # EMERGENCY_FLOW, or anything else for the ordinary regulation.
    emergency: str = EMERGENCY_OFF
    # Seconds since the Shelly's watchdog last handed the valve back, from
    # its last heartbeat answer; None when it has not taken over since it
    # started, or could not be asked.
    since_failsafe: float | None = None


@dataclass
class ControlOutput:
    """What the cycle decided, and enough of its reasoning to put on a
    dashboard. Every trim is reported separately: "the valve is at 62%" is
    not a diagnosis, "the curve asked for 44, the delta took 4 off" is."""

    position: float | None = None
    # None when nothing should be sent to the valve this cycle.
    command_position: float | None = None
    flow_setpoint: float | None = None
    # The supply the Shelly's watchdog holds if Home Assistant goes quiet
    # (heating_failsafe.js, holdSeconds), sent with every heartbeat.
    failsafe_flow: float | None = None
    flow_error: float | None = None
    curve_flow: float | None = None
    room_trim: float = 0.0
    override_trim: float = 0.0
    delta_trim: float = 0.0
    # The three sensors fused into one number and lightly filtered - what
    # the outer loop regulates on and what the thermostat shows.
    indoor_readings: list[FusedSensor] = field(default_factory=list)
    indoor_fused: float | None = None
    # The same thing on a two-hour filter, which is what the learner reads:
    # a curve should not be adjusted because someone opened a window.
    indoor_slow: float | None = None
    outdoor_filtered: float | None = None
    # The outdoor temperature the curve read, which is colder than the
    # filtered one in a cold front (Tuning.outdoor_fall_lead).
    curve_outdoor: float | None = None
    # The slower average the summer stop is decided on.
    outdoor_summer: float | None = None
    delta: float | None = None
    delta_expected: float | None = None
    # Why the delta term said nothing this cycle, when it did not: one of
    # the DELTA_QUIET_* keys in controller.py, or "" while it is working.
    delta_quiet: str = ""
    # Where the valve map would have put the valve for this setpoint, or
    # None when it has not seen enough to say. Shown, never acted on.
    valve_map_position: float | None = None
    # How much the delta term is listened to, 0 to 1 - see _delta_weight().
    delta_weight: float = 0.0
    # True while the radiators have taken too little heat from a cold house
    # for long enough to say so.
    radiators_starved: bool = False
    # The supply temperature at which a lukewarm delta starts to count.
    delta_readable_from: float | None = None
    # The quarter-hour mean of the delta the term judged on - or holds a
    # correction from - this cycle; None while it is quiet or has let go.
    delta_read: float | None = None
    # Seconds left of the warm-up after a stop, while it keeps the delta
    # from being judged.
    delta_warming_left: float | None = None
    demand: float = 0.0
    effective_slope: float = 0.0
    effective_kp: float = 0.0
    learned: bool = False
    mode: str = "idle"
    reason: str = ""
    # Whether the heat source should be running. None when none is
    # configured, which is the difference between "should be off" and
    # "nothing to say about it".
    heat_source: bool | None = None
    # True when it is the hot water that has the burner on: the radiators
    # alone would have had it off.
    burner_for_hot_water: bool = False
    boiler_overheated: bool = False
    # Sensors that are missing or stale.
    degraded: list[str] = field(default_factory=list)
    # Control decisions worth showing, e.g. the delta term being held back
    # because the house is cold.
    flags: list[str] = field(default_factory=list)
    # Each learner's verdict this cycle, by LEARN_* key: (learning, why not -
    # a code texts.learning_rows() puts in words, "" while learning - and a
    # number that goes with it: seconds left of a wait, hours learned).
    learning: dict = field(default_factory=dict)

    # --- predictive setback ----------------------------------------------
    # How long the house is expected to hold above the floor with no heat,
    # and whether the model was able to say anything at all.
    coast_hours: float | None = None
    coast_known: bool = False
    # The model's uncertainty at the weather being asked about, in K/h, and
    # the exploration budget it currently justifies. The second going to
    # zero is what "it has learned" looks like from outside.
    coast_uncertainty: float | None = None
    # Dimensionless: 0 at the heart of the model's data, 1 and up outside it.
    coast_reach: float | None = None
    # Why the model is not trusted yet, in Swedish for the dashboard, or ""
    # when it is.
    coast_trust: str = ""
    # The thresholds the prediction was actually measured against, in hours.
    # Reported rather than recomputed by a reader because hysteresis moves
    # them: starting a coast costs more than carrying one on, so the same
    # predicted hold can be a yes or a no depending on what happened last
    # cycle. Comparing against the wrong one is how a flapping decision
    # looks inexplicable in a log.
    coast_shunt_threshold: float | None = None
    coast_burner_threshold: float | None = None
    coast_allowance: float = 0.0
    # The dip the setback plans to: max(comfort band, learning allowance).
    coast_margin: float = 0.0
    # Hours until the house stops cooling on its own - what a setback
    # bridges to - or None when it does not before the floor.
    coast_turn_hours: float | None = None
    # Seconds of still time the learning hold is still waiting for, or None
    # while it is not holding anything back.
    coast_learning_hold: float | None = None
    # What the two layers were allowed to do, and why.
    coast_shunt: bool = False
    coast_burner: bool = False


def tuning_from_dict(data: dict | None, base: Tuning | None = None) -> Tuning:
    """Build a Tuning from a plain dict, ignoring keys that are not fields.

    Used for both the YAML block and the runtime number entities, so an
    unknown key is dropped rather than raising: a stored value left behind
    by an older version must not stop the heating from starting.
    """
    values = {field.name: getattr(base or Tuning(), field.name) for field in fields(Tuning)}
    for key, value in (data or {}).items():
        if key not in values:
            continue
        if isinstance(values[key], bool):
            values[key] = bool(value)
            continue
        try:
            values[key] = float(value)
        except (TypeError, ValueError):
            continue
    return Tuning(**values)



