"""A length of time in the unit a person reads it in.

Counters that can run away - hours learned, hours watched, hours in a table
cell - are shown in the next larger unit as they grow (Fredrik 2026-10-08):
minutes, hours, days, weeks, months, years. 311 hours is a number nobody
reads; "1,9 veckor" is a fact. Below ten, one decimal; from ten, whole
numbers. The curve card (www/varmekurva-card.js) has the same rule.

No Home Assistant imports, and none of this package's: every text can use it.
"""

from __future__ import annotations

import math

# (hours in one, singular, plural), smallest first.
UNITS = (
    (1.0 / 60.0, "min", "min"),
    (1.0, "h", "h"),
    (24.0, "dag", "dagar"),
    (24.0 * 7.0, "vecka", "veckor"),
    (24.0 * 30.44, "månad", "månader"),
    (24.0 * 365.25, "år", "år"),
)


def _rounded(value: float, whole: bool) -> float:
    """Halves up, as a person rounds - Python's round() takes 1.25 to 1.2.
    The small nudge keeps 1.25, stored as 1.2499999..., a half."""
    step = 1.0 if whole or value >= 10.0 else 0.1
    return math.floor(value / step + 0.5 + 1e-9) * step


def duration_words(hours: float | None) -> str:
    """Hours as "45 min", "2,5 h", "1,8 dagar", "1,9 veckor", "1 år"."""
    if hours is None:
        return "–"
    hours = max(0.0, float(hours))
    index = 0
    for i, (size, _one, _many) in enumerate(UNITS):
        if hours >= size:
            index = i
    while True:
        size, one, many = UNITS[index]
        value = _rounded(hours / size, whole=index == 0)
        # A round that reaches the next unit is said in it: 59.8 minutes is
        # an hour, 23.97 hours a day.
        if index + 1 < len(UNITS) and value * size >= UNITS[index + 1][0]:
            index += 1
            continue
        break
    whole = abs(value - round(value)) < 1e-6
    number = f"{value:.0f}" if whole else f"{value:.1f}".replace(".", ",")
    return f"{number} {one if whole and round(value) == 1 else many}"


def duration_words_seconds(seconds: float | None) -> str:
    return duration_words(None if seconds is None else float(seconds) / 3600.0)
