"""Reading numbers safely - back out of stored JSON, and off sensors.

Its own module because both model.py and estimators.py restore themselves
from disk, and a stored file is the one input neither of them controls: a
key can be missing, null, a string, or something a previous version wrote in
a different shape. Every one of those has to come back as a usable default
rather than an exception, because the alternative is a controller that will
not start over a field it could have shrugged at.

No imports, by design - this is the bottom of the stack.
"""

from __future__ import annotations


def as_mapping(value) -> dict:
    """Whatever came out of storage, as something safe to call .get on.

    Every from_dict below runs on the way to starting the integration, so a
    storage file that has been hand-edited, half-written by a power cut, or
    left behind by an older version must produce defaults rather than an
    exception. A controller that will not start is worse than one that has
    forgotten what it learned.
    """
    return value if isinstance(value, dict) else {}


def as_float(value, default: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if number == number and abs(number) != float("inf") else default


def as_optional_float(value) -> float | None:
    if value is None:
        return None
    return as_float(value, 0.0)


# Nothing in or around a house reads outside this, and a value that does is
# a broken sensor, not a temperature. Wide enough for a boiler on residual
# heat and a Swedish January; narrow enough to catch what a resistive sensor
# does when its cable fails - -273 C open or shorted, and the -999 marker the
# outdoor divider on the Uni is set to report. Missing is handled everywhere
# downstream; a plausible-looking wrong number is not.
PLAUSIBLE_C = (-50.0, 110.0)
# The wired probes - supply, return, boiler - are DS18B20s, good to 125 C,
# and they fail by going missing, not by reading high the way a nearly
# shorted resistive sensor does. 2026-10-06 the boiler reached 115.75 C and,
# held to 110, showed as missing to the tiles and to the boiler's guard.
WIRED_MAX_C = 125.0


def plausible_temperature(raw, high: float = PLAUSIBLE_C[1]) -> float | None:
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    # NaN fails both comparisons, so it is rejected here too.
    if not PLAUSIBLE_C[0] <= value <= high:
        return None
    return value


def outdoor_in_use(readings) -> int | None:
    """Index of the outdoor (key, value) pair the curve runs on - the
    coldest that answers - or None.

    Sun can only make an outdoor sensor read warm, never cold, and two
    sensors on walls the sun reaches at different hours are rarely both in
    it: the colder is the one in shade. A tie goes to the earlier in the
    list. The price is that a fault reading cold - a loose contact on a
    resistive sensor - is followed too; comparing the two shows it.

    Kept here so the controller's input and what a client is told is "in
    use" cannot follow two different rules.
    """
    answering = [
        (value, index)
        for index, (_key, value) in enumerate(readings)
        if value is not None
    ]
    return min(answering)[1] if answering else None
