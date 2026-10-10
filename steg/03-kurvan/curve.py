"""The heating curve: supply temperature from outdoor temperature.

    supply = room + slope * span * B ** curvature
    B      = (balance - outdoor) / (balance - design_outdoor), never below 0

Two things set its shape, and each has a parameter of its own:

The radiators. Their output grows with (mean water - room) to the power
n = 1.3 (EN 442, panel radiators; about 1.1 for floor heating), while the
house loses heat in proportion to (inside - outside). Turned around, the
radiators need the demand to the power 1/n: in mild weather the supply falls
more slowly than a straight line, and the curve bulges upwards. With the pump
on a fixed speed the supply/return drop adds a part that is straight, and
the two together come to an exponent of about 0.8 - the curvature.

Free heat. People, appliances and sun heat the house too, so heating is only
needed below a balance temperature some degrees under the room target. That
moves where the curve starts along the outdoor axis, not the whole curve up
or down: free heat is a large share of a mild day's need and a small share
of a cold one's. The balance is what is learned in mild weather.

The slope is the supply at the design outdoor temperature - B is 1 there
whatever the balance - so the cold end is the slope's alone. That is what
lets the two be learned apart: the balance from the autumn, the slope from
the winter.

No Home Assistant here and no state, so the controller, the chart and the
conversion of a stored learned state all use the same arithmetic.
"""

from __future__ import annotations

# The largest share of the design demand an inversion will solve for.
# Beyond it the balance runs off to infinity, and an operating point that
# far out is colder than the house was designed for anyway.
MAX_SHARE = 0.95


def supply(
    outdoor: float,
    room: float,
    design_outdoor: float,
    slope: float,
    curvature: float,
    balance: float,
) -> float:
    """The supply the curve asks for, before any supply limits."""
    span = max(1.0, room - design_outdoor)
    reach = max(1.0, balance - design_outdoor)
    share = max(0.0, (balance - outdoor) / reach)
    return room + slope * span * share ** curvature


def balance_through(
    outdoor: float,
    wanted: float,
    room: float,
    design_outdoor: float,
    slope: float,
    curvature: float,
) -> float:
    """The balance temperature at which the curve gives `wanted` at `outdoor`.

    How the learner moves the curve: it knows how much more or less supply
    the house needed at today's weather, and this finds the balance that
    gives exactly that there, leaving the cold end where the slope has it.
    """
    span = max(1.0, room - design_outdoor)
    excess = wanted - room
    if excess <= 0.0:
        # No heat wanted at this weather: the curve starts right here.
        return outdoor
    share = min(MAX_SHARE, (excess / (slope * span)) ** (1.0 / curvature))
    return (outdoor - share * design_outdoor) / (1.0 - share)


# The curve numbers on an EVR controller's curve map ("Kurvlutning" 0-7),
# as the rise in supply per degree colder, from 20 degrees supply at +20
# outside. Read off the map on the boiler room wall (2026-09-29): straight
# lines, the steps growing towards the steep end. Only a scale for people
# who know the numbers - the curve itself is still the one above.
EVR_RISE = (0.42, 0.58, 0.74, 0.91, 1.32, 1.74, 2.24, 2.73)
EVR_ORIGIN = 20.0


def _evr_rise(slope: float, room: float, design_outdoor: float) -> float:
    """The EVR rise that gives this curve's supply at the design outdoor
    temperature - the one point where the slope alone decides."""
    span = max(1.0, room - design_outdoor)
    supply = room + slope * span
    return (supply - EVR_ORIGIN) / (EVR_ORIGIN - design_outdoor)


def evr_number(slope: float, room: float, design_outdoor: float) -> float:
    """The slope as an EVR curve number, with decimals between the lines
    and carried straight on past 0 and 7."""
    rise = _evr_rise(slope, room, design_outdoor)
    table = EVR_RISE
    index = 0
    while index < len(table) - 2 and rise > table[index + 1]:
        index += 1
    low, high = table[index], table[index + 1]
    return index + (rise - low) / (high - low)


def evr_slope(number: float, room: float, design_outdoor: float) -> float:
    """The slope for an EVR curve number - evr_number() turned around."""
    table = EVR_RISE
    index = min(max(int(number // 1), 0), len(table) - 2)
    low, high = table[index], table[index + 1]
    rise = low + (number - index) * (high - low)
    supply = EVR_ORIGIN + rise * (EVR_ORIGIN - design_outdoor)
    span = max(1.0, room - design_outdoor)
    return (supply - room) / span


def indoor_sensitivity(
    outdoor: float,
    room: float,
    design_outdoor: float,
    slope: float,
    curvature: float,
    balance: float,
) -> float:
    """Degrees more supply the house needs for each degree warmer inside.

    What turns "the house sat 0.3 below target on this supply" into "it
    would have needed this much more". Not the room correction's gain,
    which is a choice about how hard to push; this is the curve's own
    answer, with the balance moving along with the room, as free heat is
    counted against the target, and the loss at design cold growing with
    the warmer inside. About 2.2 at 0 outside for this house.
    """
    step = 0.5
    up = supply(outdoor, room + step, design_outdoor, slope, curvature, balance + step)
    down = supply(outdoor, room - step, design_outdoor, slope, curvature, balance - step)
    return (up - down) / (2.0 * step)


def middle_deviation(
    room: float,
    design_outdoor: float,
    slope: float,
    balance: float,
    curvature: float,
    reference: float,
) -> float:
    """How far a curvature moves the curve, at most, from the same curve
    with the reference curvature - in degrees of supply, signed.

    The bend pivots on the balance and the design point, so the difference
    lives in between; it is looked for there in tenths of a degree.
    """
    if balance <= design_outdoor:
        return 0.0
    worst = 0.0
    steps = 200
    for index in range(1, steps):
        outdoor = balance - (balance - design_outdoor) * index / steps
        gap = (
            supply(outdoor, room, design_outdoor, slope, curvature, balance)
            - supply(outdoor, room, design_outdoor, slope, reference, balance)
        )
        if abs(gap) > abs(worst):
            worst = gap
    return worst
