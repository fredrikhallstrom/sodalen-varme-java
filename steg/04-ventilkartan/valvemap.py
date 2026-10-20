"""What share of boiler water each valve position lets through.

A mixing valve does not set a supply temperature, it sets a share: at
position p the supply is

    flow = ret + share(p) * (boiler - ret)

so the same 20 % gives more supply with the boiler at 84 degrees than at 60,
and the pellet burner swings between those every half hour. The inner loop's
single learned gain (K per percent) cannot see that, and has to chase every
swing with feedback. A learned share(p) could instead say where the valve
should be for this setpoint, this return and this boiler - a feedforward.

This module learns and predicts. While the loop regulates, the controller
only asks it what position it would have chosen and compares that with where
the PID settled. The one place it is used is the start after a stop
(controller._resume_position, 2026-10-08): a single learned gain from the
dead first fifth of the travel sent the valve to 98 % with the boiler at
85 degrees, and the supply to 57.

Travel it has not seen is filled with what it should be (fill_prior):
a line from its last trusted band to all the boiler water at 100 %,
counted as half an hour of data, so real readings take over as they come.

Learned per 5 % band of travel, not as a formula. Valves are anything but
linear - measured here in September 2026 the first 10-20 % let through about
3 % boiler water, close to a dead zone - and a lookup table learns whatever
shape the valve has without being told it. Only settled readings count: the
position unchanged long enough for the actuator to have finished travelling
and the water to have got round.

No Home Assistant imports: see model.py.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

BAND = 5.0
BANDS = int(100.0 / BAND) + 1


def _band(position: float) -> int:
    return max(0, min(BANDS - 1, int(round(position / BAND))))


@dataclass
class ValveMap:
    # How long a reading keeps counting. A month: long enough to fill the
    # bands the valve visits in a week of steady weather, short enough that
    # a rebalanced system or a replaced valve is relearned in a season.
    horizon_seconds: float = 30.0 * 24.0 * 3600.0
    # The position has to have stood this long before the supply is taken
    # to be what it leads to: the actuator's travel plus the water's way
    # round the loop and past the sensor.
    settle_seconds: float = 300.0
    # Below this much boiler over return the share is a small difference
    # divided by a small difference - a cold boiler says nothing.
    min_lift: float = 15.0
    # Seconds of settled data a band needs before it is used to predict.
    min_band_seconds: float = 1800.0

    share: list = field(default_factory=lambda: [None] * BANDS)
    weight: list = field(default_factory=lambda: [0.0] * BANDS)
    # Mean absolute gap, in percent of travel, between what this map would
    # have chosen and where the PID actually settled.
    shadow_error: float | None = None
    shadow_seconds: float = 0.0

    # Not persisted: where the valve is and how long it has stood there.
    _last_position: float | None = field(default=None, repr=False)
    _still_seconds: float = field(default=0.0, repr=False)

    # --- learning ------------------------------------------------------

    def settled(self) -> bool:
        return self._still_seconds >= self.settle_seconds

    def observe(
        self,
        position: float | None,
        flow: float | None,
        ret: float | None,
        boiler: float | None,
        dt: float,
    ) -> float | None:
        """Take one cycle's reading. Returns the share it learned from, or
        None when the reading did not count."""
        if position is None or dt <= 0.0:
            self._last_position = None
            self._still_seconds = 0.0
            return None
        if self._last_position is None or abs(position - self._last_position) > 0.5:
            self._last_position = position
            self._still_seconds = 0.0
            return None
        self._still_seconds += dt

        if not self.settled() or None in (flow, ret, boiler):
            return None
        lift = boiler - ret
        if lift < self.min_lift:
            return None

        share = max(-0.05, min(1.1, (flow - ret) / lift))
        band = _band(position)
        kept = self.weight[band] * math.exp(-dt / self.horizon_seconds)
        total = kept + dt
        old = self.share[band]
        self.share[band] = share if old is None else old + (share - old) * dt / total
        self.weight[band] = total
        return share

    def fill_prior(self) -> None:
        """Fill the travel not yet learned with what it should be - see the
        module docstring. Bands with enough data are left alone; thin real
        readings are weighed in by their time against the line. Doing it
        twice changes nothing: every band then has min_band_seconds."""
        anchor_position, anchor_share = 0.0, 0.0
        for band in range(BANDS):
            share = self.share[band]
            if share is not None and self.weight[band] >= self.min_band_seconds:
                anchor_position, anchor_share = band * BAND, share
        for band in range(BANDS):
            position = band * BAND
            weight = self.weight[band]
            if position < anchor_position or weight >= self.min_band_seconds:
                continue
            span = 100.0 - anchor_position
            line = 1.0 if span <= 0.0 else (
                anchor_share + (1.0 - anchor_share) * (position - anchor_position) / span
            )
            if position == anchor_position:
                line = anchor_share
            real = self.share[band]
            prior = self.min_band_seconds - weight
            self.share[band] = line if real is None or weight <= 0.0 else (
                (weight * real + prior * line) / self.min_band_seconds
            )
            self.weight[band] = self.min_band_seconds

    # --- prediction ----------------------------------------------------

    def known(self) -> list[tuple[float, float]]:
        """(position, share) for every band with enough data, the share
        made non-decreasing so the table can be read backwards - more
        travel never lets through less boiler water, whatever the noise
        in one band says."""
        points = []
        highest = -math.inf
        for band in range(BANDS):
            share = self.share[band]
            if share is None or self.weight[band] < self.min_band_seconds:
                continue
            highest = max(highest, share)
            points.append((band * BAND, highest))
        return points

    def position_for(
        self, flow: float | None, ret: float | None, boiler: float | None
    ) -> float | None:
        """The position that should give this supply temperature, or None
        when the map has not seen enough of the valve to say."""
        if None in (flow, ret, boiler) or boiler - ret < self.min_lift:
            return None
        points = self.known()
        if len(points) < 2:
            return None
        wanted = (flow - ret) / (boiler - ret)
        if wanted < points[0][1] or wanted > points[-1][1]:
            # Outside what has been seen. Extrapolating a valve curve is
            # exactly the guess this table exists to avoid.
            return None
        for (p0, s0), (p1, s1) in zip(points, points[1:]):
            if s0 <= wanted <= s1:
                if s1 - s0 < 1e-9:
                    return p0
                return p0 + (wanted - s0) / (s1 - s0) * (p1 - p0)
        return None

    def gain_at(self, position: float | None, lift: float | None) -> float | None:
        """K of supply per percent of travel at this position with this
        much boiler over the return, or None when the map has not seen the
        valve around there.

        The slope of the share times the lift. Of the bands either side the
        steeper counts: the loop will move the valve both ways from here,
        and on a valve that steepens as it opens a Kp for the flat side is
        too much for the steep one.
        """
        if position is None or lift is None or lift < self.min_lift:
            return None
        points = self.known()
        slopes = [
            (s1 - s0) / (p1 - p0)
            for (p0, s0), (p1, s1) in zip(points, points[1:])
            if p1 > position - BAND and p0 < position + BAND
        ]
        if not slopes:
            return None
        return max(slopes) * lift

    def score(self, predicted: float | None, actual: float | None, dt: float) -> None:
        """How far this map's choice was from where the PID settled. Only
        worth doing on a settled valve on its setpoint - the caller knows
        when that is."""
        if predicted is None or actual is None or dt <= 0.0:
            return
        gap = abs(predicted - actual)
        kept = self.shadow_seconds * math.exp(-dt / self.horizon_seconds)
        total = kept + dt
        old = self.shadow_error
        self.shadow_error = gap if old is None else old + (gap - old) * dt / total
        self.shadow_seconds = total

    # --- persistence ---------------------------------------------------

    def summary(self) -> dict:
        """What a dashboard needs: the table in percent, how much of the
        travel it covers, and how well it would have done."""
        return {
            "andel_per_lage": {
                f"{band * BAND:.0f}": round(100.0 * share, 1)
                for band, share in enumerate(self.share)
                if share is not None and self.weight[band] >= self.min_band_seconds
            },
            "lagen_med_data": len(self.known()),
            "timmar": round(sum(self.weight) / 3600.0, 1),
            "skuggfel": None if self.shadow_error is None else round(self.shadow_error, 1),
            "skuggtimmar": round(self.shadow_seconds / 3600.0, 1),
        }

    def as_dict(self) -> dict:
        return {
            "share": list(self.share),
            "weight": list(self.weight),
            "shadow_error": self.shadow_error,
            "shadow_seconds": self.shadow_seconds,
        }

    @classmethod
    def from_dict(cls, data: dict | None, **settings) -> "ValveMap":
        valve = cls(**settings)
        if not isinstance(data, dict):
            return valve
        share = data.get("share")
        weight = data.get("weight")
        if isinstance(share, list) and len(share) == BANDS:
            valve.share = [
                None if value is None else float(value) for value in share
            ]
        if isinstance(weight, list) and len(weight) == BANDS:
            valve.weight = [float(value or 0.0) for value in weight]
        if isinstance(data.get("shadow_error"), (int, float)):
            valve.shadow_error = float(data["shadow_error"])
        if isinstance(data.get("shadow_seconds"), (int, float)):
            valve.shadow_seconds = float(data["shadow_seconds"])
        return valve
