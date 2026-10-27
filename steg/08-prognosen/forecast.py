"""Several forecasts, one number, and an honest spread.

Every source is wrong in its own way. One runs cold because its grid point
sits three hundred metres up a hill; two of them turn out to be the same
underlying model served by different companies. Averaging them treats all of
that as noise to be divided by three, which it is not.

So each source is scored against the one thing in this house that is not a
forecast at all - the outdoor sensor on the wall - and the scores are kept
as a covariance rather than as three separate error bars. That distinction
is the whole module:

* **Bias is subtracted.** A source two degrees low is not a bad source, it
  is a source with an offset, and once that is known it is as useful as any
  other.

* **Correlated sources do not get to vote twice.** The optimal combination
  of correlated estimates is w = S^-1 1 / (1' S^-1 1), which gives two
  identical sources half a weight each without anybody telling it they were
  identical. At Sodalen two of the three agree to a tenth of a degree on
  cloud cover and to the degree on wind bearing; treating that as three
  independent opinions would produce a confidence that is not there.

* **The same solve gives the uncertainty.** 1/(1' S^-1 1) is the variance of
  the combination, which is what the predictive setback needs in order to
  know how much margin to leave. Agreement between genuinely independent
  sources narrows it; agreement between two copies of one model does not.

No Home Assistant imports: see model.py.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .estimators import solve_symmetric

# Starting guess for a source nobody has scored yet, in degrees squared. A
# degree and a half of error: pessimistic enough that a new source cannot
# swing the answer on its first afternoon, small enough to be forgotten
# within a day of real data.
PRIOR_VARIANCE = 2.25


@dataclass
class Reading:
    """One source's opinion of the weather now."""

    name: str
    temperature: float | None = None
    wind: float | None = None
    bearing: float | None = None
    cloud: float | None = None


@dataclass
class Combined:
    """What the sources came to between them."""

    temperature: float | None = None
    wind: float | None = None
    bearing: float | None = None
    cloud: float | None = None
    # Standard deviation of the combined temperature, in degrees.
    uncertainty: float | None = None
    # What each source ended up being worth, for the dashboard: this is
    # where "the one on the hill counts for a tenth" becomes visible.
    weights: dict = field(default_factory=dict)
    sources: int = 0


@dataclass
class Ensemble:
    """Scores the sources against the sensor on the wall, and combines them."""

    horizon: float = 14.0 * 24.0 * 3600.0
    # Decayed mean error per source, in degrees.
    bias: dict = field(default_factory=dict)
    # Decayed covariance of the de-biased errors, source against source.
    covariance: dict = field(default_factory=dict)
    weight: float = 0.0

    def retune(self, horizon: float) -> None:
        self.horizon = horizon

    def _decay(self, dt: float) -> float:
        if self.horizon <= 0.0 or dt <= 0.0:
            return 1.0
        ratio = dt / self.horizon
        return (2.718281828459045 ** -ratio) if ratio < 30.0 else 0.0

    # --- scoring ----------------------------------------------------------

    def observe(self, truth: float | None, readings: list[Reading], dt: float) -> bool:
        """Score every source that offered a temperature, against the sensor.

        Returns True when something was scored. Sources that said nothing
        this cycle are left exactly as they were rather than being counted
        as correct, which is the difference between a quiet source and a
        good one.
        """
        if truth is None:
            return False

        offered = [r for r in readings if r.temperature is not None]
        if not offered:
            return False

        decay = self._decay(dt)
        self.weight = self.weight * decay + 1.0

        errors = {}
        for reading in offered:
            error = float(reading.temperature) - float(truth)
            previous = self.bias.get(reading.name, error)
            # The bias follows the same clock as everything else here, so a
            # source that is recalibrated is followed rather than averaged
            # with its own history.
            share = 1.0 / max(1.0, self.weight)
            self.bias[reading.name] = previous + share * (error - previous)
            errors[reading.name] = error - self.bias[reading.name]

        for first in errors:
            row = self.covariance.setdefault(first, {})
            for second in errors:
                previous = row.get(second, PRIOR_VARIANCE if first == second else 0.0)
                row[second] = previous * decay + errors[first] * errors[second]
        return True

    # --- combining --------------------------------------------------------

    def _matrix(self, names: list[str]) -> list[list[float]]:
        scale = max(1.0, self.weight)
        matrix = []
        for row_index, first in enumerate(names):
            row = []
            for col_index, second in enumerate(names):
                stored = self.covariance.get(first, {}).get(second)
                if stored is None:
                    value = PRIOR_VARIANCE if first == second else 0.0
                else:
                    value = stored / scale
                if row_index == col_index:
                    # Two sources that have never once disagreed would make
                    # the matrix singular. A floor on the diagonal says what
                    # is true anyway: nothing is known to a thousandth.
                    value = max(value, 1e-3)
                row.append(value)
            matrix.append(row)
        return matrix

    def weights(self, names: list[str]) -> tuple[dict, float | None]:
        """Optimal weights for these sources, and the spread that comes with.

        Falls back to equal weights when the covariance cannot be solved,
        which is what "we have not scored anybody yet" looks like.
        """
        if not names:
            return {}, None
        if len(names) == 1:
            variance = self._matrix(names)[0][0]
            return {names[0]: 1.0}, variance ** 0.5

        matrix = self._matrix(names)
        ones = [1.0] * len(names)
        solved = solve_symmetric(matrix, ones)
        if solved is None or sum(solved) <= 0.0:
            share = 1.0 / len(names)
            return {name: share for name in names}, None

        total = sum(solved)
        weights = {name: value / total for name, value in zip(names, solved)}
        # A negative weight is the mathematics telling us one source is
        # useful as a correction to another. It is also how a single bad
        # cycle turns into a wild answer, so it is clamped away and the
        # rest renormalised.
        if any(value < 0.0 for value in weights.values()):
            weights = {name: max(0.0, value) for name, value in weights.items()}
            total_positive = sum(weights.values())
            if total_positive <= 0.0:
                share = 1.0 / len(names)
                weights = {name: share for name in names}
            else:
                weights = {n: v / total_positive for n, v in weights.items()}

        variance = 1.0 / total if total > 0.0 else None
        return weights, None if variance is None else max(0.0, variance) ** 0.5

    def combine(self, readings: list[Reading]) -> Combined:
        """One weather out of many opinions."""
        offered = [r for r in readings if r.temperature is not None]
        if not offered:
            return Combined()

        names = [r.name for r in offered]
        weights, spread = self.weights(names)

        temperature = sum(
            weights[r.name] * (float(r.temperature) - self.bias.get(r.name, 0.0))
            for r in offered
        )

        return Combined(
            temperature=temperature,
            wind=_weighted(readings, weights, "wind"),
            bearing=_weighted_bearing(readings, weights),
            cloud=_weighted(readings, weights, "cloud"),
            uncertainty=spread,
            weights={name: round(value, 4) for name, value in weights.items()},
            sources=len(offered),
        )

    # --- persistence ------------------------------------------------------

    def as_dict(self) -> dict:
        return {
            "horizon": self.horizon,
            "weight": self.weight,
            "bias": dict(self.bias),
            "covariance": {k: dict(v) for k, v in self.covariance.items()},
        }

    @classmethod
    def from_dict(cls, data: dict | None, horizon: float) -> Ensemble:
        data = data if isinstance(data, dict) else {}
        ensemble = cls(horizon=horizon)
        try:
            ensemble.weight = float(data.get("weight") or 0.0)
        except (TypeError, ValueError):
            ensemble.weight = 0.0

        bias = data.get("bias")
        if isinstance(bias, dict):
            for name, value in bias.items():
                try:
                    ensemble.bias[str(name)] = float(value)
                except (TypeError, ValueError):
                    continue

        covariance = data.get("covariance")
        if isinstance(covariance, dict):
            for first, row in covariance.items():
                if not isinstance(row, dict):
                    continue
                kept = {}
                for second, value in row.items():
                    try:
                        kept[str(second)] = float(value)
                    except (TypeError, ValueError):
                        continue
                if kept:
                    ensemble.covariance[str(first)] = kept
        return ensemble


# How far ahead the first forecast hour may lie and still stand in for now.
# The outlook starts at the next whole hour, so up to one hour ahead is the
# normal case; more means the fetched forecast has run out.
STAND_IN_HOURS = 1.5


def outdoor_from_forecast(forecast: list) -> float | None:
    """The forecast's temperature for now, or None.

    Stands in for a missing outdoor sensor. The forecast is kept from the
    last fetch that worked, so it outlasts an internet outage that takes a
    cloud sensor with it: on 2026-09-28 Netatmo was gone for three hours,
    and the curve held the temperature from before. A forecast from the
    small hours knows about the morning; a frozen reading does not. Once
    the fetched hours run out this answers None, and the curve goes back to
    holding the last value.
    """
    if not forecast:
        return None
    ahead, conditions = forecast[0]
    if ahead > STAND_IN_HOURS:
        return None
    return getattr(conditions, "outdoor", None)


def _weighted(readings: list[Reading], weights: dict, field_name: str) -> float | None:
    """Weighted mean of whichever sources offered this field.

    Renormalised over the ones that did, because a source with no wind in it
    should not quietly drag the answer toward zero.
    """
    total = 0.0
    share = 0.0
    for reading in readings:
        value = getattr(reading, field_name)
        weight = weights.get(reading.name, 0.0)
        if value is None or weight <= 0.0:
            continue
        total += weight * float(value)
        share += weight
    return None if share <= 0.0 else total / share


def _weighted_bearing(readings: list[Reading], weights: dict) -> float | None:
    """Wind direction, averaged the way angles have to be.

    350 degrees and 10 degrees average to 0, not to 180, so the mean is
    taken on the unit circle. Getting this wrong would put a northerly on
    the sheltered side of the house.
    """
    import math

    east = 0.0
    north = 0.0
    share = 0.0
    for reading in readings:
        if reading.bearing is None:
            continue
        weight = weights.get(reading.name, 0.0)
        if weight <= 0.0:
            continue
        angle = math.radians(float(reading.bearing))
        east += weight * math.sin(angle)
        north += weight * math.cos(angle)
        share += weight
    if share <= 0.0 or (east == 0.0 and north == 0.0):
        return None
    return math.degrees(math.atan2(east, north)) % 360.0
