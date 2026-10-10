"""The inner loop: supply temperature error in, valve position out.

Kept apart from the controller because it is the one piece of this project
with textbook answers, and because the outer loop is where all the
house-specific judgement lives. Positional PI with a filtered derivative on
the measurement, back-calculated anti-windup, and a deadband that shrinks
the error instead of zeroing it.

The output is an absolute valve position, not a nudge. A three-point
actuator driven through a Shelly cover is a position servo with its own
travel time, so asking it repeatedly for "2% more" would stack commands it
has not finished executing; asking for "62%" is idempotent and survives a
missed cycle.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class PidResult:
    output: float
    integral: float
    error: float
    proportional: float
    derivative: float
    saturated: bool


def pid_step(
    *,
    setpoint: float,
    measurement: float,
    last_measurement: float | None,
    integral: float,
    dt: float,
    kp: float,
    ti: float,
    td: float,
    deadband: float = 0.0,
    out_min: float = 0.0,
    out_max: float = 100.0,
) -> PidResult:
    error = setpoint - measurement

    # Shrinking the error by the deadband rather than snapping it to zero
    # keeps the response continuous: a 0.21 K error with a 0.2 K deadband
    # asks for a hair of correction, not for the 0.21 K worth it would ask
    # for the moment it crossed the threshold.
    if deadband > 0.0:
        if error > deadband:
            working_error = error - deadband
        elif error < -deadband:
            working_error = error + deadband
        else:
            working_error = 0.0
    else:
        working_error = error

    proportional = kp * working_error

    derivative = 0.0
    if td > 0.0 and dt > 0.0 and last_measurement is not None:
        # On the measurement, not the error: the outer loop drops the
        # setpoint by several degrees the instant the override contact
        # closes, and derivative on error would answer that step with a
        # kick at the valve for no reason at all.
        derivative = -kp * td * (measurement - last_measurement) / dt

    if ti > 0.0 and dt > 0.0:
        integral += (kp / ti) * working_error * dt

    raw = proportional + integral + derivative
    output = min(out_max, max(out_min, raw))
    saturated = output != raw

    if saturated:
        # Back-calculation: put the integral where it would have had to be
        # for the limited output to be what the controller asked for. This
        # is what lets the valve come off an end stop on the first cycle
        # the error changes sign, instead of after however long it takes to
        # unwind an integral that ran away while the valve was already shut.
        integral = output - proportional - derivative

    integral = min(out_max, max(out_min, integral))

    return PidResult(
        output=output,
        integral=integral,
        error=error,
        proportional=proportional,
        derivative=derivative,
        saturated=saturated,
    )


def bumpless_integral(
    *, position: float, proportional: float = 0.0, derivative: float = 0.0
) -> float:
    """Integral that makes the loop's first output equal the position the
    valve is already at.

    Starting from zero would slam the valve shut on the first cycle after
    every restart and then spend twenty minutes winding back to where it
    was. Used when the controller has no remembered position: first start,
    storage lost, or coming back from failsafe.
    """
    return min(100.0, max(0.0, position - proportional - derivative))
