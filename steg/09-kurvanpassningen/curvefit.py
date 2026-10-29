"""The curve through the table's dots.

Given the balance - where the curve starts, which the house model knows
better than the dots do - the slope is the one number left that the dots
decide, and for a given bend it is plain weighted least squares: each dot
says how much supply the house needed above its target, and the curve says
slope times a known shape. The installer's slope is a prior worth a quarter
of one trusted dot - enough to keep an empty table where it is, little
enough not to drag a full one: at a whole dot, four trusted bins were still
pulled a fifth of the way back toward it.

Two guards, both from reading what a fit of mild weather alone would do:

* The slope may only move as far as the dots reach. A slope learned at 0
  outside is an extrapolation at -18, and an error there grows about two
  and a half times on the way. With trusted dots down to T, the slope may
  move MAX_NUMBER_SHIFT EVR curves times how far T is towards the design
  temperature.
* The bend is only looked for once trusted dots reach well into the cold,
  because the bend is the shape between the two ends and needs both. It
  may move the middle of the curve at most `bend_limit` degrees from the
  physics' curve; 0 keeps it there.

And one rule from Fredrik: trusted dots only count as an unbroken chain, at
most `max_green_gap` degrees from one to the next. A lone trusted dot at -10
with nothing between it and +4 would otherwise give the slope most of its
reach and start the bend on its own. A trusted dot beyond a gap waits, heard
neither in the fit nor in the reach, until the bins between have filled.
With the longest chain two or more long, the fit may steer (`steerable`).

Dots far from the fit - a lit stove, a window left open - are dropped and
the fit made again. No Home Assistant, no state.
"""

from __future__ import annotations

from dataclasses import dataclass

from . import curve
from .points import BIN_WIDTH, GREEN, Bin, bin_index

# EVR curves the slope may move with trusted dots all the way to design cold.
MAX_NUMBER_SHIFT = 2.0
# How far a dot may lie from the fit before it is taken for something else.
OUTLIER = 3.0
# The bend is only looked for with trusted dots this cold.
BEND_COLD = -10.0
BEND_GRID = [round(0.5 + 0.01 * step, 2) for step in range(71)]  # 0.50-1.20
# Keeps the bend at the physics' value when the dots cannot tell.
BEND_PRIOR = 4.0
# Trusted dots in a row before the fit may steer.
MIN_CHAIN = 2


@dataclass(frozen=True)
class CurveFit:
    slope: float
    balance: float
    curvature: float
    # The coldest trusted dot, or None - how far the fit is measurement.
    coldest_green: float | None
    used: int
    rejected: tuple[float, ...]
    # The trusted dots that counted, warm end first: the chain.
    chain: tuple[float, ...] = ()

    @property
    def steerable(self) -> bool:
        """Enough of a chain for the curve to follow the fit."""
        return len(self.chain) >= MIN_CHAIN


def green_chain(bins: list[Bin], max_gap: float) -> list[Bin]:
    """The longest run of trusted bins at most `max_gap` degrees apart, warm
    end first; the colder of two equally long runs, since cold dots say more
    about the slope. Empty without a trusted bin."""
    greens = sorted(
        (item for item in bins if item.status == GREEN),
        key=lambda item: -bin_index(item.outdoor),
    )
    step = max(1, int(round(max_gap / BIN_WIDTH)))
    runs: list[list[Bin]] = []
    for item in greens:
        if runs and bin_index(runs[-1][-1].outdoor) - bin_index(item.outdoor) <= step:
            runs[-1].append(item)
        else:
            runs.append([item])
    best: list[Bin] = []
    for run in runs:
        if len(run) >= len(best):
            best = run
    return best


def fit_curve(
    bins: list[Bin],
    *,
    room: float,
    design_outdoor: float,
    balance: float,
    slope_prior: float,
    curvature: float,
    bend_limit: float,
    prior_weight: float = 0.25,
    max_green_gap: float | None = None,
) -> CurveFit:
    """The slope (and bend, once there is reach for it) that best explain
    the dots, given the balance. With `max_green_gap`, only the chain of
    trusted dots counts (see green_chain); without, every trusted dot."""
    usable = [item for item in bins if item.weight > 0.0]
    if max_green_gap is None:
        chain = [item for item in usable if item.status == GREEN]
    else:
        chain = green_chain(usable, max_green_gap)
        usable = [item for item in usable if item.status != GREEN or any(item is c for c in chain)]
    greens = [item.outdoor for item in chain]
    coldest = min(greens) if greens else None
    reach = 0.0
    if coldest is not None and balance > design_outdoor:
        reach = min(1.0, max(0.0, (balance - coldest) / (balance - design_outdoor)))

    shape = _Shape(room, design_outdoor, balance)
    prior = _Prior(shape, slope_prior, prior_weight, reach)

    slope = prior.solve(usable, curvature)
    outliers = [
        item for item in usable
        if abs(item.need - shape.excess(item.outdoor, slope, curvature)) > OUTLIER
    ]
    if outliers:
        usable = [item for item in usable if item not in outliers]
        slope = prior.solve(usable, curvature)

    bent = curvature
    if bend_limit > 0.0 and coldest is not None and coldest <= BEND_COLD:
        bent, slope = _bend(usable, shape, prior, curvature, bend_limit)

    return CurveFit(
        slope=slope,
        balance=balance,
        curvature=bent,
        coldest_green=coldest,
        used=len(usable),
        rejected=tuple(item.outdoor for item in outliers),
        chain=tuple(sorted(greens, reverse=True)),
    )


class _Shape:
    """The curve's supply above the room, for a slope and a bend."""

    def __init__(self, room: float, design_outdoor: float, balance: float) -> None:
        self.room = room
        self.design = design_outdoor
        self.balance = balance

    def per_slope(self, outdoor: float, curvature: float) -> float:
        return curve.supply(outdoor, self.room, self.design, 1.0, curvature, self.balance) - self.room

    def excess(self, outdoor: float, slope: float, curvature: float) -> float:
        return slope * self.per_slope(outdoor, curvature)


class _Prior:
    """Least squares with the installer's slope as one dot's worth of say,
    then held to what the dots' reach allows."""

    def __init__(self, shape: _Shape, slope: float, weight: float, reach: float) -> None:
        self.shape = shape
        self.slope = slope
        self.weight = weight
        number = curve.evr_number(slope, shape.room, shape.design)
        allowed = MAX_NUMBER_SHIFT * reach
        self.low = curve.evr_slope(max(0.0, number - allowed), shape.room, shape.design)
        self.high = curve.evr_slope(number + allowed, shape.room, shape.design)

    def solve(self, bins: list[Bin], curvature: float) -> float:
        reference = self.shape.per_slope(
            (self.shape.balance + self.shape.design) / 2.0, curvature
        )
        strength = self.weight * reference * reference
        top = strength * self.slope
        bottom = strength
        for item in bins:
            per = self.shape.per_slope(item.outdoor, curvature)
            top += item.weight * item.need * per
            bottom += item.weight * per * per
        slope = top / bottom if bottom > 0.0 else self.slope
        return min(self.high, max(self.low, slope))

    def cost(self, bins: list[Bin], slope: float, curvature: float) -> float:
        total = 0.0
        for item in bins:
            miss = item.need - self.shape.excess(item.outdoor, slope, curvature)
            total += item.weight * miss * miss
        return total


def _bend(
    bins: list[Bin], shape: _Shape, prior: _Prior, reference: float, limit: float
) -> tuple[float, float]:
    best = (reference, prior.solve(bins, reference))
    best_cost = prior.cost(bins, best[1], reference)
    for curvature in BEND_GRID:
        slope = prior.solve(bins, curvature)
        moved = curve.middle_deviation(
            shape.room, shape.design, slope, shape.balance, curvature, reference
        )
        if abs(moved) > limit:
            continue
        cost = prior.cost(bins, slope, curvature) + BEND_PRIOR * (curvature - reference) ** 2
        if cost < best_cost:
            best, best_cost = (curvature, slope), cost
    return best
