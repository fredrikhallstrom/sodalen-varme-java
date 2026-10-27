"""What this house does when nobody is heating it.

The controller in controller.py answers "what supply temperature does the
house want now". This answers a different question: "if the heat stopped,
how long would the house hold". They are different enough to live apart -
one is a regulator, this is a model of a building.

The physics, divided through by the building's heat capacity so that every
parameter is learnable from temperatures alone:

    dT/dt  =  q                      internal gains - people, cooking, the
                                     television, the boiler in the cellar
            + s * I                  sun through the windows: I is the
                                     light on the walls, kW/m2 (sunlight.py)
            + h * delta              the radiators, when they are on
            - (g0
               + gv * v              wind, from any direction
               + gb * v * cos(dir)   and the part of it that depends on
               + gc * v * sin(dir))  which direction it comes from
              * (T_in - T_out)

Seven parameters, linear in all of them, so one MultiRegression learns the
lot - and the same matrix says how far it is extrapolating, which is what
decides whether a prediction is worth acting on.

Two choices worth knowing about:

* **Wind direction is a smooth pair of terms, not eight sectors.** A house
  is sheltered on some sides and exposed on others, but it does not change
  character between 179 and 181 degrees. cos and sin of the bearing fit that
  with two numbers and need far less data than binning would, and they
  cannot produce a sector nobody has seen.

* **The radiators are a regressor rather than a reason to throw the sample
  away.** Learning only from periods with the heat off would mean learning
  almost nothing in January, which is the season the answer matters in.
  Supply minus return is a direct reading of delivered power, so it earns a
  coefficient and every cycle becomes data.

No Home Assistant imports: see model.py.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field, replace

from .estimators import MultiRegression
from .sunlight import SUNLIGHT_PER_INDEX, WINDOWS_PER_WALLS

# 1, -dT, -v*dT, -v*cos*dT, -v*sin*dT, sun, delta, cos(day), sin(day)
REGRESSORS = 9


def solar_index(elevation: float | None, cloud: float | None) -> float:
    """A stand-in for irradiance, from the sun's height and the cloud cover.

    How high the sun stands multiplied by how much of it gets through: the
    light on flat ground. Until 2026-10-18 this was the sun the control and
    the house model took. Now they take the light on the walls
    (sunlight.Walls), and this, times sunlight.SUNLIGHT_PER_INDEX, only where
    there are no walls to put the sun on.
    """
    if elevation is None or elevation <= 0.0:
        return 0.0
    through = 1.0
    if cloud is not None:
        through = max(0.0, min(1.0, 1.0 - float(cloud) / 100.0))
        # Even a solid overcast passes some diffuse light, and a house with
        # windows notices it.
        through = 0.15 + 0.85 * through
    return math.sin(math.radians(min(90.0, float(elevation)))) * through


def regressors(
    *,
    indoor: float,
    outdoor: float,
    wind: float | None,
    bearing: float | None,
    sun: float,
    delta: float,
    hour: float | None = None,
) -> list[float]:
    """One row of the fit. Kept in one place so the model and the prediction
    can never disagree about what the columns mean."""
    difference = indoor - outdoor
    speed = max(0.0, float(wind or 0.0))
    angle = math.radians(float(bearing or 0.0))
    # A day's worth of household: people up and about, cooking, appliances,
    # the television. Two terms give one cycle with a learned amplitude and
    # a learned phase, so the model works out for itself when this house
    # warms itself and by how much.
    #
    # A fixed window would have been simpler and worse for the same reason
    # wind direction is not eight sectors: a house does not change character
    # at eight o'clock sharp, and guessing the hours is guessing the thing
    # worth measuring. With no clock the pair is zero, which leaves the
    # constant term carrying the average exactly as before.
    daily = 2.0 * math.pi * (float(hour) / 24.0) if hour is not None else None
    return [
        1.0,
        -difference,
        -speed * difference,
        -speed * math.cos(angle) * difference,
        -speed * math.sin(angle) * difference,
        sun,
        max(0.0, delta),
        0.0 if daily is None else math.cos(daily),
        0.0 if daily is None else math.sin(daily),
    ]


@dataclass
class Conditions:
    """The weather at one moment, as the model wants it - and the clock,
    because a household is part of the weather indoors."""

    outdoor: float
    wind: float | None = None
    bearing: float | None = None
    sun: float = 0.0
    # Local hour of day, 0-24, fractional. None where it is not known, which
    # simply leaves the daily terms out.
    hour: float | None = None


@dataclass
class Outlook:
    """What the model thinks will happen if the heat stays off."""

    # Hours until the house reaches the floor, or None if it holds for the
    # whole horizon that was offered.
    hours: float | None
    # True when the answer is "it holds" rather than "it runs out at t".
    holds: bool
    # How far the model is reaching, as a standard deviation in K/h. The
    # margin is built from this, so a model asked about weather it has never
    # seen protects itself without anyone switching a mode.
    uncertainty: float | None
    # False when the fit has not got enough to say anything at all.
    known: bool
    # Hours until the house stops cooling on its own - the weather, the sun
    # or the day turning - while still above the floor; None when that does
    # not happen first within the horizon. What a setback bridges to
    # (Fredrik 2026-10-10).
    turns_at: float | None = None


# Where the physics is known, the fit is told rather than left to find out.
#
# A house left alone loses heat through its walls in proportion to how much
# warmer it is inside than out, and radiators full of hot water heat it.
# Those two signs are not something to learn. But a house under closed-loop
# control makes them hard to learn: the radiators run hardest exactly when
# the house is cooling, because that is what the controller answers, so in
# the data "more radiator" goes with "getting colder". Three mild September
# days fitted both with the wrong sign - a model in which a house left alone
# at -5 C warmed by 0.4 K an hour - and the trust gate rightly refused it,
# in the season a burner stop is worth the most.
#
# So the loss and the radiator terms are floored at the right sign, and
# pulled gently towards plausible values while the data cannot tell them
# apart: a time constant of about sixty hours, and radiators that just hold
# the house at the circuit's design point. Each is worth half a day of data
# - counted in hours, so a change of sample interval does not change it -
# at the spread that term typically has: a few degrees of inside-outside
# difference, a few degrees of delta. A mild week is still mostly prior; a
# three-week horizon of winter outweighs it many times over.
#
# The general wind term is floored at zero too - wind cools a house, from
# whichever direction; only how much depends on the direction. The 23
# September model fitted it backwards, and with the other two terms fixed
# it would have read a windy cold night as a long hold.
LOSS = 1          # coefficient of -(indoor - outdoor), K/h per K
WIND = 2          # coefficient of -wind * (indoor - outdoor)
WIND_COS = 3      # the same, times cos(bearing)
WIND_SIN = 4      # the same, times sin(bearing)
RADIATORS = 6     # coefficient of the supply/return delta
TIME_CONSTANT_HOURS = 60.0
PRIOR_HOURS = 12.0
LOSS_SPREAD = 5.0         # K of inside-outside difference
RADIATOR_SPREAD = 3.0     # K of supply/return delta
WIND_SPREAD = 30.0        # m/s times K: a few metres a second on ten degrees
# Wind adding about five per cent to the loss per metre a second, from
# whichever direction - until the data says which side is exposed.
WIND_SHARE_PER_MS = 0.05
# A house that loses less than this would hold for weeks - not a house.
LOSS_FLOOR = 1.0 / 500.0


def house_priors(design_span: float, design_delta: float, sample_seconds: float) -> dict:
    """The physics the fit is told, for this circuit's design point."""
    samples = PRIOR_HOURS * 3600.0 / max(1.0, sample_seconds)
    loss = 1.0 / TIME_CONSTANT_HOURS
    wind_strength = samples * WIND_SPREAD ** 2
    return {
        LOSS: (loss, samples * LOSS_SPREAD ** 2),
        # Which side is exposed is exactly what three mild days cannot tell:
        # the directional terms start at nothing, so a model that has barely
        # seen wind does not decide that one quarter of it warms the house.
        WIND: (loss * WIND_SHARE_PER_MS, wind_strength),
        WIND_COS: (0.0, wind_strength),
        WIND_SIN: (0.0, wind_strength),
        # At the design point the radiators just replace the loss:
        # radiators * design_delta = loss * design_span.
        RADIATORS: (loss * design_span / max(1.0, design_delta),
                    samples * RADIATOR_SPREAD ** 2),
    }


HOUSE_FLOORS = {LOSS: LOSS_FLOOR, WIND: 0.0, RADIATORS: 0.0}

# The balance, from the house in equilibrium (HouseModel.balance). The same
# physics as the drift, solved for the inside-outside difference:
#
#     T_in - T_out  =  q/g0  +  (h/g0) * delta  +  (s/g0) * I
#                   +  (1/g0) * -dT/dt  +  the day
#                   -  (gv/g0, gb/g0, gc/g0) * wind terms * (T_in - T_out)
#
# A regulated house holds its target, the radiators answering the weather,
# so the drift's own fit cannot find its pace: its loss and radiator terms
# sit on their priors (a sixty-hour house where the truth was seventeen),
# while the gains and the sun, free, fit against them - in a sunny run the
# internal gain came out negative and the balance 1.8 K over target, and
# Sodalen's read 15.9 +- 9.4. Every term here is a ratio to the loss, which
# a house on its target shows: the difference, the delta that holds it and
# the sun that spares it. And each has the sign physics gives it.
STEADY_REGRESSORS = 7
STEADY_GAINS = 0      # q/g0: K of difference the house's own heat holds
STEADY_RADIATORS = 1  # h/g0: K per K of delta
STEADY_SUN = 2        # s/g0: K per unit of solar index
STEADY_PACE = 3       # 1/g0: hours, times the house cooling (-dT/dt)
STEADY_WIND = 4       # gv/g0, and the direction's two after it
STEADY_FLOORS = {STEADY_GAINS: 0.0, STEADY_RADIATORS: 0.0, STEADY_SUN: 0.0, STEADY_PACE: 0.0,
                 STEADY_WIND: 0.0}
DAY_SECONDS = 24.0 * 3600.0
# A day's means move little in an interval, and every sample re-solves the
# fit: one every three hours, weighed by the three.
STEADY_EVERY_SECONDS = 3.0 * 3600.0

# The balance of the night (HouseModel.night_balance): the heating mostly
# runs then, and the household gives less than the day's mean - Sodalen's
# day replayed to 11.2 where its nights needed about 14. Night is the sun
# down and two hours past its warmth, as the control judges it: an overcast
# day has no sun to speak of but the household's day, and taken in with the
# nights it moved the night's own heat half a degree. One sample per night,
# at least NIGHT_MIN_SECONDS of it, cut at NIGHT_MAX_SECONDS: its means, the
# steady fit's terms but the sun. Few samples - one a night - so the
# radiators', the pace's and the wind's ratios to the loss, which are the
# house's whatever the hour, come from the steady fit as priors worth
# NIGHT_PRIOR_SAMPLES nights; what the nights decide is the house's own heat
# in them.
NIGHT_REGRESSORS = 6
NIGHT_GAINS, NIGHT_RADIATORS, NIGHT_PACE, NIGHT_WIND = 0, 1, 2, 3
NIGHT_FLOORS = {NIGHT_GAINS: 0.0, NIGHT_RADIATORS: 0.0, NIGHT_PACE: 0.0, NIGHT_WIND: 0.0}
NIGHT_MIN_SECONDS = 6.0 * 3600.0
# Nights before it answers: its terms and three more. Seven give one degree
# of freedom, and a spread from one is a guess.
NIGHT_MIN_NIGHTS = NIGHT_REGRESSORS + 3
NIGHT_MAX_SECONDS = 12.0 * 3600.0
NIGHT_PRIOR_SAMPLES = 2.0
# Typical spread of each between nights, for the priors' strength:
# radiators to the 1.3, K/h of net change, wind times difference.
NIGHT_SPREAD = {NIGHT_RADIATORS: 10.0, NIGHT_PACE: 0.05,
                NIGHT_WIND: 30.0, NIGHT_WIND + 1: 30.0, NIGHT_WIND + 2: 30.0}
# Where the days' sun has not varied - the same sky every day - the day's
# means cannot tell it from the house's own heat, and the drift's fit can,
# by the hour the sun comes: its sun over its loss stands in, at the weight
# of half a day's samples spread a tenth of the old index - 0.127 kW/m2 on
# the walls, so a model brought into the new unit keeps its balance. Where
# the cloud has varied the days decide.
SUN_INDEX = 5             # the sun's coefficient in REGRESSORS
STEADY_SUN_STRENGTH = 24.0 * (0.1 * SUNLIGHT_PER_INDEX) ** 2
# The sun's unit in a stored model: the light on the windows, kW/m2, their
# mean by area. A model stored in another is moved into it once, on loading
# (HouseModel.from_dict): one on the two sunny walls (fa7d88b, the day the
# areas came) by WINDOWS_PER_WALLS, and one with no word of its unit - it
# learnt the sun by solar_index - by SUNLIGHT_PER_INDEX.
SUN_UNIT = "windows_kw_m2"
SUN_UNITS_BEFORE = {"walls_kw_m2": WINDOWS_PER_WALLS}
WINDOW_SUN = 2            # the sun's place in the window and the day's sums


def steady_regressors(*, sun: float, delta: float, slope: float,
                      wind: tuple[float, float, float] = (0.0, 0.0, 0.0)) -> list[float]:
    """wind: the means of speed, speed times cos and times sin of the
    bearing, each times the inside-outside difference."""
    return [1.0, max(0.0, delta), sun, -slope, -wind[0], -wind[1], -wind[2]]


def radiator_excess(flow: float | None, ret: float | None, indoor: float | None) -> float | None:
    """The radiators' mean water over the room, to the 1.3 their output
    follows (EN 442): their heat, up to a constant, whatever the pump."""
    if flow is None or ret is None or indoor is None:
        return None
    return max(0.0, 0.5 * (flow + ret) - indoor) ** 1.3


def night_regressors(*, heat: float, slope: float,
                     wind: tuple[float, float, float] = (0.0, 0.0, 0.0)) -> list[float]:
    return [1.0, max(0.0, heat), -slope, -wind[0], -wind[1], -wind[2]]


def night_fit(horizon_seconds: float, fit: MultiRegression | None = None) -> MultiRegression:
    fit = fit or MultiRegression(size=NIGHT_REGRESSORS, horizon=horizon_seconds)
    fit.configure({}, NIGHT_FLOORS)
    return fit


def steady_fit(horizon_seconds: float, fit: MultiRegression | None = None) -> MultiRegression:
    fit = fit or MultiRegression(size=STEADY_REGRESSORS, horizon=horizon_seconds)
    fit.configure({}, STEADY_FLOORS)
    return fit

# The defaults' design point - 21 C against -18 C, a delta of ten - until
# the controller hands the model its own tuning.
_DEFAULT_PRIORS = house_priors(39.0, 10.0, 1800.0)


def house_fit(horizon_seconds: float, fit: MultiRegression | None = None) -> MultiRegression:
    fit = fit or MultiRegression(size=REGRESSORS, horizon=horizon_seconds)
    fit.configure(_DEFAULT_PRIORS, HOUSE_FLOORS)
    return fit


@dataclass
class HouseModel:
    """The building, fitted from its own behaviour."""

    fit: MultiRegression = field(
        default_factory=lambda: house_fit(21.0 * 24.0 * 3600.0)
    )
    # The same samples, solved for the difference: the balance's.
    steady: MultiRegression = field(
        default_factory=lambda: steady_fit(21.0 * 24.0 * 3600.0)
    )
    # The interval's sums for it - seconds, and delta, sun, difference and
    # the three wind terms times seconds - and the last day of intervals,
    # those and the indoor temperature at the start. The steady fit takes the day's
    # means: the household's day cancels in them, the sun's varies with the
    # cloud, and the house's own pace is the day's net change. Per interval
    # the timing of a sunny day halved the radiators and the sun.
    window: list = field(default_factory=lambda: [0.0] * 7)
    day: list = field(default_factory=list)
    # The day's sums, kept as it goes rather than added up every interval;
    # not stored - made again from the day.
    day_totals: list = field(default_factory=lambda: [0.0] * 7, repr=False, compare=False)
    # Seconds since the steady fit last took the day's means.
    since_steady: float = 0.0
    # The nights: their fit, and the night under way as seconds, heat,
    # difference and the three wind terms times seconds, and the indoor
    # temperature it began at.
    night: MultiRegression = field(
        default_factory=lambda: night_fit(21.0 * 24.0 * 3600.0)
    )
    stretch: list = field(default_factory=lambda: [0.0] * 6 + [0.0])
    # Indoor temperature and clock at the last sample, for the slope.
    last_indoor: float | None = None
    seconds_since_sample: float = 0.0
    # Wall-clock hours the accepted samples cover. Counted in hours rather
    # than read off the fit's sample count because what the daily terms need
    # is to have *seen days*, and forty samples ten minutes apart are an
    # afternoon, not a day.
    observed_hours: float = 0.0

    def retune(self, horizon_seconds: float) -> None:
        self.fit.horizon = horizon_seconds
        self.steady.horizon = horizon_seconds
        self.night.horizon = horizon_seconds

    def set_design(self, design_span: float, design_delta: float, sample_seconds: float) -> None:
        """Tell the fit this circuit's design point and sample interval, which
        the priors depend on."""
        self.fit.configure(house_priors(design_span, design_delta, sample_seconds), HOUSE_FLOORS)

    def loss_on_floor(self) -> bool:
        """Whether the wall loss is sitting on its floor - the data pushing it
        towards a house that does not cool. A fit like that is not trusted:
        the floor stopped it being impossible, not made it right."""
        beta = self.fit.coefficients()
        return beta is not None and beta[LOSS] <= LOSS_FLOOR + 1e-9

    # --- learning ---------------------------------------------------------

    def observe(
        self,
        *,
        indoor: float | None,
        conditions: Conditions | None,
        delta: float,
        dt: float,
        sample_seconds: float,
        radiators: float | None = None,
        night: bool | None = None,
    ) -> bool:
        """Offer the model a cycle. Returns True when it took a sample.

        night says this cycle is night - the sun down and past its warmth,
        the control's own judgement - for the night's balance; None, not
        known, is not night.

        radiators, when known, is the radiators' water over the room to the
        1.3 (radiator_excess): their heat whatever the pump, which the steady
        fit takes in place of the delta. The delta is heat over the water's
        flow, and a pump turned down mid-horizon mixes two circuits in one
        coefficient - a run that did put the balance 5 K low, and sure of it.
        The drift's fit takes the delta as it is.

        Samples are taken every sample_seconds rather than every cycle,
        because the thing being measured is a slope of about a tenth of a
        degree an hour and thirty seconds of it is entirely sensor noise.
        """
        self.seconds_since_sample += max(0.0, dt)

        if indoor is None or conditions is None:
            # Nothing to measure against; start the interval again so the
            # next slope is not taken across the gap.
            self.last_indoor = None
            self.seconds_since_sample = 0.0
            self.window = [0.0] * 7
            self.day = []
            self.day_totals = [0.0] * 7
            self.stretch = [0.0] * 7
            return False

        span = max(0.0, dt)
        difference = indoor - conditions.outdoor
        speed = max(0.0, float(conditions.wind or 0.0))
        angle = math.radians(float(conditions.bearing or 0.0))
        heat = max(0.0, delta) if radiators is None else max(0.0, radiators)
        for index, value in enumerate((
            1.0, heat, conditions.sun, difference, speed * difference,
            speed * math.cos(angle) * difference, speed * math.sin(angle) * difference,
        )):
            self.window[index] += value * span
        self._stretch(bool(night), indoor, span, heat, difference, speed, angle)

        if self.seconds_since_sample < sample_seconds:
            if self.last_indoor is None:
                self.last_indoor = indoor
            return False

        elapsed = self.seconds_since_sample
        previous = self.last_indoor
        self.last_indoor = indoor
        self.seconds_since_sample = 0.0

        if previous is None or elapsed <= 0.0:
            return False

        # Kelvin per hour: the numbers stay in a range a human can read and
        # the fit stays far away from the edges of floating point.
        slope = (indoor - previous) * 3600.0 / elapsed
        row = regressors(
            indoor=previous,
            outdoor=conditions.outdoor,
            wind=conditions.wind,
            bearing=conditions.bearing,
            sun=conditions.sun,
            delta=delta,
            hour=conditions.hour,
        )
        self.fit.add(row, slope, elapsed)
        self._add_to_day(previous, indoor, elapsed)
        self.observed_hours += elapsed / 3600.0
        return True

    def _stretch(self, night: bool, indoor: float, span: float, heat: float,
                 difference: float, speed: float, angle: float) -> None:
        """A cycle into the night under way, and a night into the night fit
        once it ends or reaches NIGHT_MAX_SECONDS."""
        if night and self.stretch[0] <= 0.0:
            self.stretch = [0.0] * 6 + [indoor]
        if night:
            for index, value in enumerate((
                1.0, heat, difference, speed * difference,
                speed * math.cos(angle) * difference, speed * math.sin(angle) * difference,
            )):
                self.stretch[index] += value * span
        if self.stretch[0] > 0.0 and (not night or self.stretch[0] >= NIGHT_MAX_SECONDS):
            sums = self.stretch
            self.stretch = [0.0] * 7
            if sums[0] >= NIGHT_MIN_SECONDS:
                seconds = sums[0]
                mean = [value / seconds for value in sums[1:6]]
                self.night.add(
                    night_regressors(heat=mean[0], slope=(indoor - sums[6]) * 3600.0 / seconds,
                                     wind=(mean[2], mean[3], mean[4])),
                    mean[1],
                    seconds,
                )
            if night:
                self.stretch = [0.0] * 6 + [indoor]

    def _add_to_day(self, start: float, end: float, elapsed: float) -> None:
        """The interval into the day, and a day's means into the steady fit
        once there is a whole day."""
        sums = self.window
        self.window = [0.0] * 7
        if sums[0] <= 0.0:
            return
        self.day.append(sums + [start])
        totals = [a + b for a, b in zip(self.day_totals, sums)]
        while self.day and totals[0] - self.day[0][0] >= DAY_SECONDS:
            gone = self.day.pop(0)
            totals = [a - b for a, b in zip(totals, gone)]
        self.day_totals = totals
        if totals[0] < DAY_SECONDS:
            return
        self.since_steady += elapsed
        if self.since_steady < STEADY_EVERY_SECONDS:
            return
        weight, self.since_steady = self.since_steady, 0.0
        total = totals[0]
        mean = [totals[k] / total for k in range(1, 7)]
        slope = (end - self.day[0][7]) * 3600.0 / total
        self.steady.add(
            steady_regressors(sun=mean[1], delta=mean[0], slope=slope,
                              wind=(mean[3], mean[4], mean[5])),
            mean[2],
            weight,
        )

    # --- using it ---------------------------------------------------------

    def drift(self, indoor: float, conditions: Conditions) -> float | None:
        """Kelvin per hour, with the radiators off."""
        return self.fit.predict(self._row(indoor, conditions))

    def drift_uncertainty(self, indoor: float, conditions: Conditions) -> float | None:
        return self.fit.uncertainty(self._row(indoor, conditions))

    def reach(self, indoor: float, conditions: Conditions) -> float | None:
        """How far outside its own data this question is. See
        MultiRegression.reach."""
        return self.fit.reach(self._row(indoor, conditions))

    def balance(self, indoor: float) -> tuple[float, float] | None:
        """The outdoor temperature at which this house, unheated, neither
        warms nor cools - still air, no sun, the household's day averaged
        out. That is where the heating curve starts. Returns (balance, its
        standard error in K), or None when the fit cannot say.

        From the steady fit (STEADY_REGRESSORS): the difference the house's
        own heat holds, with no radiators, no sun and no drift. The error is
        the estimate's own, sigma times the square root of the leverage - not
        the spread of a single half-hour reading, which is what a prediction
        has to allow for and not what the balance is uncertain by.
        """
        drift = self.fit.coefficients()
        priors = {}
        if drift is not None and drift[LOSS] > 0.0:
            priors[STEADY_SUN] = (max(0.0, drift[SUN_INDEX]) / drift[LOSS], STEADY_SUN_STRENGTH)
        self.steady.configure(priors, STEADY_FLOORS)
        beta = self.steady.coefficients()
        if beta is None:
            return None
        at = [1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
        reach = self.steady.reach(at)
        sigma = self.steady.in_sample_sigma()
        if reach is None or not math.isfinite(sigma):
            return None
        leverage = max(0.0, (reach + 1.0) ** 2 - 1.0)
        return indoor - beta[STEADY_GAINS], sigma * math.sqrt(leverage)

    def night_balance(self, indoor: float) -> tuple[float, float] | None:
        """The balance of the night - NIGHT_REGRESSORS - as (balance, its
        standard error), or None until the nights can say."""
        steady = self.steady.coefficients()
        priors = {}
        if steady is not None:
            for index, at in ((NIGHT_RADIATORS, STEADY_RADIATORS), (NIGHT_PACE, STEADY_PACE),
                              (NIGHT_WIND, STEADY_WIND), (NIGHT_WIND + 1, STEADY_WIND + 1),
                              (NIGHT_WIND + 2, STEADY_WIND + 2)):
                priors[index] = (steady[at], NIGHT_PRIOR_SAMPLES * NIGHT_SPREAD[index] ** 2)
        self.night.configure(priors, NIGHT_FLOORS)
        beta = self.night.coefficients()
        if beta is None or self.night.weight < NIGHT_MIN_NIGHTS:
            return None
        reach = self.night.reach([1.0, 0.0, 0.0, 0.0, 0.0, 0.0])
        sigma = self.night.in_sample_sigma()
        if reach is None or not math.isfinite(sigma):
            return None
        leverage = max(0.0, (reach + 1.0) ** 2 - 1.0)
        return indoor - beta[NIGHT_GAINS], sigma * math.sqrt(leverage)

    def cools_when_left_alone(self) -> bool | None:
        """Whether the fit believes the one thing a house model must.

        Left alone on a cold, dark, still day with the radiators off, a house
        cools. A fit that says otherwise has not learned the building, it has
        found coefficients that cancel on the few samples it has - and will
        stop cancelling the moment a prediction walks away from them.
        Measured: thirteen samples for nine parameters gave a fit in which
        cold outside, wind and the radiators all had the wrong sign, and it
        predicted a hold of four hours and a quarter that was thirty-five
        minutes an hour later.

        -5 C is well outside September's data on purpose: the loss term is
        the one that grows with the difference, so a cold reference is where
        a wrong sign on it cannot hide behind the constant. The daily terms
        are left out (hour=None) - over a whole day they cancel anyway - and
        the wind is tried too, from every quarter: wind cools a house, and a
        model in which a gale warms it has not learned the building either.
        """
        for wind in (0.0, 5.0, 10.0):
            for bearing in range(0, 360, 45):
                drift = self.drift(
                    21.0,
                    Conditions(outdoor=-5.0, wind=wind, bearing=float(bearing), sun=0.0, hour=None),
                )
                if drift is None:
                    return None
                if drift >= 0.0:
                    return False
                if wind == 0.0:
                    break
        return True

    def _row(self, indoor: float, conditions: Conditions) -> list[float]:
        return regressors(
            indoor=indoor,
            outdoor=conditions.outdoor,
            wind=conditions.wind,
            bearing=conditions.bearing,
            sun=conditions.sun,
            delta=0.0,
            hour=conditions.hour,
        )

    def outlook(
        self,
        *,
        indoor: float,
        floor: float,
        weather: list[tuple[float, Conditions]],
        confidence: float,
        step_hours: float = 0.25,
    ) -> Outlook:
        """How long the house holds above the floor with the heat off.

        `weather` is (hours from now, conditions), which is what a forecast
        looks like once it has been through the ensemble. The walk forward is
        deliberately plain - a quarter hour at a time, asking the model what
        the house is doing and moving it that far.

        The margin is the interesting part. Each step adds the model's own
        uncertainty at that moment, multiplied by the elapsed time, and the
        floor is tested against the pessimistic temperature rather than the
        expected one. Two things fall out of that without any extra
        machinery: the answer is cautious in weather the model has not seen,
        and it becomes less cautious on its own as the fit improves.
        """
        if not weather:
            return Outlook(hours=None, holds=False, uncertainty=None, known=False)

        temperature = indoor
        margin = 0.0
        worst_uncertainty = 0.0
        horizon = weather[-1][0]
        elapsed = 0.0
        # The clock runs forward with the walk. A setback that starts at
        # eight in the evening and lasts until two must lose the household's
        # own warmth on the way through, not carry the evening with it.
        # Backed out to elapsed=0: the first forecast slot is typically an
        # hour out, and its hour is the hour it happens, not this one.
        start_hour = (
            None
            if weather[0][1].hour is None
            else (weather[0][1].hour - weather[0][0]) % 24.0
        )

        turns_at = None
        while elapsed < horizon:
            conditions = _weather_at(weather, elapsed)
            if start_hour is not None:
                conditions = replace(
                    conditions, hour=(start_hour + elapsed) % 24.0
                )
            slope = self.drift(temperature, conditions)
            spread = self.drift_uncertainty(temperature, conditions)
            if slope is None or spread is None:
                return Outlook(hours=None, holds=False, uncertainty=None, known=False)

            worst_uncertainty = max(worst_uncertainty, spread)
            # Weather fine already counts as a turn at once. Warmth from
            # inside shows as the house above target, which is the warm
            # stop's; this asks only whether the weather to come needs heat.
            if turns_at is None and slope >= 0.0:
                turns_at = elapsed
            before = temperature - margin
            temperature += slope * step_hours
            # Perfectly correlated growth, which is the conservative
            # assumption: an error in the fit does not cancel itself out
            # over an evening, it accumulates.
            margin += spread * step_hours * confidence
            after = temperature - margin

            if after <= floor:
                # Interpolate within the step rather than reporting the whole
                # of it. The walk is a quarter hour at a time and the shunt's
                # own lead is ten minutes, so rounding up to the step would
                # make every answer look like at least a quarter of an hour -
                # and a night at minus twenty would read as "holds long
                # enough" purely because of the arithmetic.
                fall = before - after
                share = 1.0 if fall <= 0.0 else max(0.0, min(1.0, (before - floor) / fall))
                return Outlook(
                    hours=elapsed + step_hours * share,
                    holds=False,
                    uncertainty=worst_uncertainty,
                    known=True,
                    turns_at=turns_at,
                )

            elapsed += step_hours

        return Outlook(
            hours=horizon, holds=True, uncertainty=worst_uncertainty, known=True,
            turns_at=turns_at,
        )

    # --- persistence ------------------------------------------------------

    def as_dict(self) -> dict:
        return {
            "fit": self.fit.as_dict(),
            "steady": self.steady.as_dict(),
            "window": list(self.window),
            "day": [list(item) for item in self.day],
            "since_steady": self.since_steady,
            "night": self.night.as_dict(),
            "stretch": list(self.stretch),
            "last_indoor": self.last_indoor,
            "seconds_since_sample": self.seconds_since_sample,
            "observed_hours": self.observed_hours,
            "sun": SUN_UNIT,
        }

    @classmethod
    def from_dict(cls, data: dict | None, horizon_seconds: float) -> HouseModel:
        # Same reason as model.py's _mapping: this runs on the way to
        # starting, and a broken store must give defaults, not an exception.
        data = data if isinstance(data, dict) else {}
        model = cls(
            fit=house_fit(
                horizon_seconds,
                MultiRegression.from_dict(data.get("fit"), REGRESSORS, horizon_seconds),
            ),
            steady=steady_fit(
                horizon_seconds,
                MultiRegression.from_dict(_with_squares(data.get("steady")),
                                          STEADY_REGRESSORS, horizon_seconds),
            ),
            night=night_fit(
                horizon_seconds,
                MultiRegression.from_dict(_with_squares(data.get("night")),
                                          NIGHT_REGRESSORS, horizon_seconds),
            ),
        )
        try:
            stretch = [float(v) for v in data.get("stretch") or []]
        except (TypeError, ValueError):
            stretch = []
        if len(stretch) == 7:
            model.stretch = stretch
        try:
            window = [float(v) for v in data.get("window") or []]
            day = [[float(v) for v in item] for item in data.get("day") or []]
        except (TypeError, ValueError):
            window, day = [], []
        if len(window) == 7 and all(len(item) == 8 for item in day):
            model.window, model.day = window, day
            model.day_totals = [sum(item[k] for item in day) for k in range(7)]
        try:
            model.since_steady = float(data.get("since_steady") or 0.0)
        except (TypeError, ValueError):
            model.since_steady = 0.0
        last = data.get("last_indoor")
        try:
            model.last_indoor = None if last is None else float(last)
        except (TypeError, ValueError):
            model.last_indoor = None
        try:
            model.seconds_since_sample = float(data.get("seconds_since_sample") or 0.0)
        except (TypeError, ValueError):
            model.seconds_since_sample = 0.0
        try:
            model.observed_hours = float(data.get("observed_hours") or 0.0)
        except (TypeError, ValueError):
            model.observed_hours = 0.0
        # A fit discarded for being the wrong width starts again from
        # nothing, and so must the hours it is credited with.
        if model.fit.weight <= 0.0:
            model.observed_hours = 0.0
        if data.get("sun") != SUN_UNIT:
            model._sun_in_walls(SUN_UNITS_BEFORE.get(data.get("sun"), SUNLIGHT_PER_INDEX))
        return model

    def _sun_in_walls(self, factor: float) -> None:
        """What was learnt of the sun by the index, in the light on the
        walls: the drift's fit, the balance's and the day's sums under way.
        The night's has no sun."""
        self.fit.rescale(SUN_INDEX, factor)
        self.steady.rescale(STEADY_SUN, factor)
        self.window[WINDOW_SUN] *= factor
        for item in self.day:
            item[WINDOW_SUN] *= factor
        self.day_totals = [sum(item[k] for item in self.day) for k in range(7)]


def _with_squares(data):
    """A balance fit stored before it kept its squares (2026-10-14, its first
    hours) starts again: without them its error is the guesses', which read
    nothing until the fit has answered for a while."""
    return data if isinstance(data, dict) and data.get("yy") is not None else None


def _weather_at(weather: list[tuple[float, Conditions]], hours: float) -> Conditions:
    """The forecast nearest this many hours out, holding the last one past
    the end rather than extrapolating weather nobody predicted."""
    chosen = weather[0][1]
    for offset, conditions in weather:
        if offset > hours:
            break
        chosen = conditions
    return chosen
