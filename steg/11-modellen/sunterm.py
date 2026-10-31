"""The sun's part of the indoor error, learnt (2026-10-18).

The curve learns from the indoor error while it heats, and a sunny day holds
the house over target: free heat the curve must not learn as too much of
its own. Until now the learning stood down while the sun was on the walls,
and the evening after. Fredrik asked to take the sun away by day so that it
could learn then too - and to let it learn the sun, so that how sure it is
comes up.

Taking it away by the house model's physics did not work. A supply lowered
by what the sun is worth in degrees outside (sun compensation) made the
curve 1.4 K wrong in the closed loop where the gate left it 0.5, with the
sun's true worth (5.3 K with no gate behind it): the sun warms the rooms at
once, the radiators answer minutes to hours later, and in the evening the
held sun keeps the supply down after the light has gone.

What works is to measure the sun where it lands - in the indoor error the
curve learns from - against the light on the walls as the house holds it
(sunlight.Walls, smoothed over Tuning.sun_heat_seconds) and against the
demand, because a mild house has its radiators near their floor and gives
the sun back less than a cold one:

    error = a + b demand + (c + d demand) light

On Sodalen's two sunny walls in the closed loop that read -5.7 + 4.0 demand
K per kW/m2, and on its windows the curve was right sooner: a mean error of
0.20 K on day 10 where the gate alone left 0.55. The curve learns from the error less the
last term, in the sun too, wherever that term is known well enough
(Tuning.learn_sun_error); elsewhere the gate on the light stands as it was.

Hourly means, not cycles: the hundred and twenty cycles of an hour are one
observation, and counted one by one they made the fit a hundred times
surer than it was.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from .estimators import MultiRegression
from .parsing import as_float, as_mapping

SIZE = 4                       # 1, demand, light, light x demand
LIGHT, LIGHT_DEMAND = 2, 3
HOUR_SECONDS = 3600.0
HORIZON_SECONDS = 21.0 * 24.0 * 3600.0
# Two days of hours before it answers, and of them some with the sun up: a
# fit of a day is a guess, and one that has never seen the sun knows nothing
# of it however sure the rest is.
MIN_HOURS = 48
MIN_SUNNY_HOURS = 6
SUNNY = 0.02                   # kW/m2 of an hour's mean light


def _finite(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


@dataclass
class SunTerm:
    """The fit, the hour under way and how many hours it has had."""

    fit: MultiRegression = field(
        default_factory=lambda: MultiRegression(size=SIZE, horizon=HORIZON_SECONDS)
    )
    # Seconds, and seconds times demand, light and error, of the hour under way.
    hour: list = field(default_factory=lambda: [0.0, 0.0, 0.0, 0.0])
    hours: int = 0
    sunny_hours: int = 0

    def observe(self, demand, light, error, dt) -> None:
        """A cycle that says something about the curve but for the sun."""
        demand, light, error, dt = (_finite(v) for v in (demand, light, error, dt))
        if demand is None or light is None or error is None or dt is None or dt <= 0.0:
            return
        hour = self.hour
        hour[0] += dt
        hour[1] += demand * dt
        hour[2] += max(0.0, light) * dt
        hour[3] += error * dt
        if hour[0] < HOUR_SECONDS:
            return
        seconds = hour[0]
        demand, light, error = hour[1] / seconds, hour[2] / seconds, hour[3] / seconds
        self.fit.add([1.0, demand, light, light * demand], error, seconds)
        self.hour = [0.0, 0.0, 0.0, 0.0]
        self.hours += 1
        if light > SUNNY:
            self.sunny_hours += 1

    def _ready(self) -> bool:
        return (self.hours >= MIN_HOURS and self.sunny_hours >= MIN_SUNNY_HOURS
                and self.fit.coefficients() is not None)

    def per_kw(self, demand: float) -> float | None:
        """K of indoor error per kW/m2 of held light at this demand; None
        until it answers."""
        if not self._ready():
            return None
        beta = self.fit.coefficients()
        return beta[LIGHT] + beta[LIGHT_DEMAND] * demand

    def spread(self, demand: float) -> float | None:
        """The standard error of per_kw: the fit's spread over the leverage
        of that combination."""
        if not self._ready():
            return None
        whole = self.fit.variance([0.0, 0.0, 1.0, demand])
        sigma = self.fit.sigma
        if whole is None or not math.isfinite(sigma):
            return None
        return max(0.0, whole - sigma * sigma) ** 0.5

    def share(self, demand: float) -> float | None:
        """The uncertainty as a share of the sun's worth."""
        worth, spread = self.per_kw(demand), self.spread(demand)
        if worth is None or spread is None or worth == 0.0:
            return None
        return spread / abs(worth)

    def known(self, demand: float, light: float, limit: float) -> bool:
        """Whether what this light does to the error is known to within
        limit K. Never before the term has answered - not even with no light:
        a light held at nothing after a restart is not the evening after the
        sun, which the light's own waits are for. Once it has, no light is
        known: there is nothing to take off."""
        spread = self.spread(demand)
        if spread is None:
            return False
        return light <= 0.0 or light * spread <= limit

    def correction(self, demand: float, light: float) -> float:
        """K of the indoor error that is the sun's; nought until it answers."""
        worth = self.per_kw(demand)
        return 0.0 if worth is None or light <= 0.0 else worth * light

    # --- persistence ------------------------------------------------------

    def as_dict(self) -> dict:
        return {"fit": self.fit.as_dict(), "hour": list(self.hour), "hours": self.hours,
                "sunny_hours": self.sunny_hours}

    @classmethod
    def from_dict(cls, data: dict | None, horizon: float) -> SunTerm:
        data = as_mapping(data)
        term = cls(fit=MultiRegression.from_dict(data.get("fit"), SIZE, horizon))
        hour = data.get("hour")
        if isinstance(hour, list) and len(hour) == 4 and all(_finite(v) is not None for v in hour):
            term.hour = [float(v) for v in hour]
        if term.fit.weight > 0.0:
            term.hours = int(as_float(data.get("hours"), 0.0))
            term.sunny_hours = int(as_float(data.get("sunny_hours"), 0.0))
        return term
