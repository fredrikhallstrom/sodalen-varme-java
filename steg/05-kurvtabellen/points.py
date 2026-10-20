"""The curve table: what the house needed, per outdoor temperature.

Each hour the house sits still on its target with the heating running, the
supply it took to keep it there is a measurement of the curve - not of the
error against whatever curve was in use at the time, which goes stale the
moment the curve moves, but of the answer itself. The table collects those
measurements in bins of two degrees outdoors and keeps them for months, so
a cold snap in December still counts in February.

Each bin is split into four six-hour parts of the day. The house needs a
good deal less in the middle of the day than at night - sun and household -
and in the autumn the heating only runs at night and in the morning. A bin
filled from the small hours alone would describe the small hours, so a bin's
value is the average of its parts, each weighing the same, and a bin is only
trusted once most of the day is in it.

No Home Assistant here and no clock: the controller passes the hours that
went by. Pure arithmetic, tested on its own.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from .parsing import as_float, as_mapping

BIN_WIDTH = 2.0
SECTORS = 4
SECTOR_HOURS = 24.0 / SECTORS

RED = "red"
YELLOW = "yellow"
GREEN = "green"


@dataclass
class Cell:
    """One bin's measurements from one part of the day."""

    # Hours of measurement, fading with age.
    weight: float = 0.0
    # Supply needed above the room target, K - the running mean.
    mean: float = 0.0
    # Weighted sum of squared deviations from the mean, for the spread.
    m2: float = 0.0
    # Where in the bin the measurements fell, and the wind they had.
    outdoor: float = 0.0
    wind: float = 0.0
    # Hours since the last measurement.
    age: float = 0.0

    def add(self, need: float, outdoor: float, wind: float, hours: float) -> None:
        total = self.weight + hours
        if total <= 0.0:
            return
        delta = need - self.mean
        self.mean += delta * hours / total
        self.m2 += hours * delta * (need - self.mean)
        self.outdoor += (outdoor - self.outdoor) * hours / total
        self.wind += (wind - self.wind) * hours / total
        self.weight = total
        self.age = 0.0

    def fade(self, factor: float, hours: float) -> None:
        # Scaling weight and m2 together leaves the mean and the spread as
        # they were: old measurements count for less, not for something else.
        self.weight *= factor
        self.m2 *= factor
        self.age += hours

    def as_dict(self) -> dict:
        return {
            "w": round(self.weight, 4), "m": round(self.mean, 4), "q": round(self.m2, 4),
            "o": round(self.outdoor, 3), "v": round(self.wind, 2), "a": round(self.age, 2),
        }

    @classmethod
    def from_dict(cls, data) -> "Cell":
        data = as_mapping(data)
        return cls(
            weight=max(0.0, as_float(data.get("w"), 0.0)),
            mean=as_float(data.get("m"), 0.0),
            m2=max(0.0, as_float(data.get("q"), 0.0)),
            outdoor=as_float(data.get("o"), 0.0),
            wind=max(0.0, as_float(data.get("v"), 0.0)),
            age=max(0.0, as_float(data.get("a"), 0.0)),
        )


@dataclass(frozen=True)
class Rules:
    """When a bin counts, and how much. From Tuning, kept apart so the table
    can be tested without one."""

    red_hours: float = 3.0
    green_hours: float = 24.0
    green_spread: float = 1.5
    green_sectors: int = 3
    sector_hours: float = 1.0


@dataclass(frozen=True)
class Bin:
    """One bin as the fit and the chart see it."""

    outdoor: float
    # Supply needed above the room target, K: the parts of the day averaged
    # with equal weight.
    need: float
    hours: float
    # Spread of the measurements within each part of the day, pooled - the
    # noise, not the difference between night and noon.
    spread: float
    sectors: int
    wind: float
    age: float
    status: str
    # How much the fit listens: nothing when red, all when green.
    weight: float


@dataclass
class PointTable:
    cells: dict = field(default_factory=dict)

    def add(
        self, *, outdoor: float, need: float, wind: float | None, hour: float | None, hours: float
    ) -> None:
        """One stretch of measurement. `need` is the supply the house needed
        above its target; `hours` how long it was measured over."""
        if hours <= 0.0 or not math.isfinite(outdoor) or not math.isfinite(need):
            return
        key = (bin_index(outdoor), _sector(hour))
        cell = self.cells.get(key)
        if cell is None:
            cell = self.cells[key] = Cell()
        cell.add(need, outdoor, max(0.0, float(wind or 0.0)), hours)

    def fade(self, hours: float, half_life_hours: float) -> None:
        if hours <= 0.0:
            return
        factor = 0.5 ** (hours / half_life_hours) if half_life_hours > 0.0 else 1.0
        for cell in self.cells.values():
            cell.fade(factor, hours)

    def clear(self) -> None:
        self.cells.clear()

    def bins(self, rules: Rules = Rules()) -> list[Bin]:
        by_bin: dict[int, list[Cell]] = {}
        for (index, _sector_index), cell in self.cells.items():
            if cell.weight > 0.0:
                by_bin.setdefault(index, []).append(cell)

        out = []
        for index in sorted(by_bin):
            cells = by_bin[index]
            hours = sum(cell.weight for cell in cells)
            counted = [cell for cell in cells if cell.weight >= rules.sector_hours]
            parts = counted or cells
            need = sum(cell.mean for cell in parts) / len(parts)
            # max(): the running sums can round a hair below zero.
            spread = math.sqrt(max(0.0, sum(cell.m2 for cell in cells) / hours)) if hours > 0.0 else 0.0
            sectors = len(counted)
            status, weight = _judge(hours, spread, sectors, rules)
            out.append(
                Bin(
                    outdoor=sum(cell.outdoor * cell.weight for cell in cells) / hours,
                    need=need,
                    hours=hours,
                    spread=spread,
                    sectors=sectors,
                    wind=sum(cell.wind * cell.weight for cell in cells) / hours,
                    age=min(cell.age for cell in cells),
                    status=status,
                    weight=weight,
                )
            )
        return out

    def key_at(self, outdoor: float, hour: float | None) -> str:
        """The cell an outdoor temperature and an hour fall in, as
        as_dict() names it."""
        return f"{bin_index(outdoor)}:{_sector(hour)}"

    def hours_at(self, outdoor: float, hour: float | None) -> float:
        """Hours of measurement in this outdoor temperature's bin, for this
        part of the day - what the learning hold asks."""
        cell = self.cells.get((bin_index(outdoor), _sector(hour)))
        return cell.weight if cell is not None else 0.0

    def as_dict(self) -> dict:
        return {f"{index}:{sector}": cell.as_dict() for (index, sector), cell in self.cells.items()}

    @classmethod
    def from_dict(cls, data) -> "PointTable":
        table = cls()
        for key, value in as_mapping(data).items():
            try:
                index, sector = (int(part) for part in str(key).split(":"))
            except ValueError:
                continue
            if not 0 <= sector < SECTORS:
                continue
            cell = Cell.from_dict(value)
            if cell.weight > 0.0:
                table.cells[(index, sector)] = cell
        return table


def bin_index(outdoor: float) -> int:
    """The bin an outdoor temperature falls in; neighbouring bins differ by
    one. A bin's mean outdoor temperature lies inside it, so the fit can ask
    the same of a Bin."""
    return math.floor(outdoor / BIN_WIDTH)


def _sector(hour: float | None) -> int:
    # Without a clock every measurement lands in one part of the day, which
    # keeps such a bin from ever being judged whole - the right answer when
    # nobody can say what time of day it described.
    if hour is None:
        return 0
    return int((float(hour) % 24.0) // SECTOR_HOURS)


def _judge(hours: float, spread: float, sectors: int, rules: Rules) -> tuple[str, float]:
    if hours < rules.red_hours:
        return RED, 0.0
    if (
        hours >= rules.green_hours
        and spread <= rules.green_spread
        and sectors >= rules.green_sectors
    ):
        return GREEN, 1.0
    # Growing with the hours and with how much of the day it covers, and at
    # most half a green bin's say until it is one.
    coverage = sectors / SECTORS
    return YELLOW, 0.5 * min(1.0, hours / rules.green_hours) * coverage
