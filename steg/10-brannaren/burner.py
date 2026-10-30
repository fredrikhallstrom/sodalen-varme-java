"""The burner bolted to the boiler, and how it is asked to fire.

Split out of model.py because the control law never touches any of it:
controller.py decides whether the boiler should be hot, and everything about
*which* burner is on the wall and *which* relay carries that decision lives
here, read by the heat-source layer and the entities that expose it.

Free of Home Assistant, like the rest of the core, because getting a relay
backwards fails expensively in both directions and that is worth testing
without a boiler.
"""

from __future__ import annotations

from dataclasses import dataclass


# The burner bolted to the boiler, which is swapped physically rather than
# selected: pellets this winter, oil another, and the same two relays serve
# whichever is on the wall. So the configuration names the burner and takes
# its timings from here, the way an indoor sensor names a role - swapping the
# burner is meant to be one line, not four numbers to remember.
#
# Neither number is about temperature. Each burner runs its own thermostat on
# the boiler and always has; all this controller ever says is whether the
# boiler should be heated at all. These are about how long that decision has
# to hold, and how long the burner needs to be left alone afterwards.
BURNER_PELLETS = "pellets"
BURNER_OIL = "oil"
BURNERS = (BURNER_PELLETS, BURNER_OIL)

# Swedish because these go on the dashboard the family reads, same as
# MODE_REASONS. "Värmekälla: på" does not say which burner is on the wall,
# and with two that get swapped that is exactly the thing worth seeing.
BURNER_NAMES = {
    BURNER_PELLETS: "pellets",
    BURNER_OIL: "olja",
}

# Which relays the order goes through. The wiring says what is possible;
# this says what is used, and it is remembered per burner because an oil
# burner may have a control input where a pellet burner has none.
PATH_CONTROL = "control"
PATH_POWER = "power"
PATH_BOTH = "both"
CONTROL_PATHS = (PATH_CONTROL, PATH_POWER, PATH_BOTH)

# What the control cable does to call for heat. Said as the circuit rather
# than as on/off: "on" is a relay state, "sluten krets" is the thing the
# burner's own manual talks about, and this is a setting somebody reads off
# a wiring diagram.
CALL_NAMES = {
    "on": "Sluten krets",
    "off": "Bruten krets",
}

CONTROL_PATH_NAMES = {
    PATH_CONTROL: "Styrkabel",
    PATH_POWER: "Huvudström",
    PATH_BOTH: "Styrkabel + huvudström",
}

BURNER_DEFAULTS = {
    # Pellets lights slowly - auger, igniter, a few minutes before there is
    # any heat - so a stop that turns out to be wrong is expensive and the
    # controller should be reluctant to make one. And it needs to finish
    # burning what is already in the grate before the supply is cut.
    BURNER_PELLETS: {
        # From "start" to heat actually arriving: ignition, auger, and the
        # boiler coming up. This is the lead time on a predictive restart -
        # the heating has to begin this far before the house would reach the
        # floor, or it undershoots while the burner is still lighting.
        "start_lead": 1800.0,
        "off_delay": 3600.0,
        "burnout_seconds": 900.0,
        # How long before the sweeper is due the burner goes off, so that
        # boiler and flue are cold when he comes (sweep.py). A pellet boiler
        # holds its heat for hours.
        "sweep_lead_seconds": 6.0 * 3600.0,
        "call_for_heat": "on",
        # The boiler alarm (boilerwatch.py): below this, and not warming,
        # while it should burn, the burner has gone out. The pellet boiler
        # runs about 75 on its own thermostat.
        "alarm_below": 50.0,
    },
    # Oil lights in seconds and stops in seconds. Neither number needs to be
    # generous.
    #
    # Its own thermostat runs the boiler between 77 and 84 C, and on
    # residual heat at low load up to 89 (Fredrik, from the burner on the
    # wall). Nothing here reads that,
    # deliberately: whether the boiler should be hot is this controller's
    # question, how hot is the burner's. But it is the first thing to look
    # at the winter oil goes back on. The boiler is the hot side of the
    # shunt, so a 7 K swing each time the burner cycles is a periodic
    # disturbance the inner loop will chase - a supply temperature that
    # ripples in step with the burner is that, not a badly tuned PI. And
    # flow_max has to stay below the bottom of the swing, or the valve
    # runs to its end stop every time the boiler is at its low point -
    # 65 against a floor of 77 leaves twelve degrees, which is enough.
    #
    # The one thing to do *before* oil goes back on is outside this file:
    # a DS18B20 on the 2PM's Add-On. The Shelly watchdog's over-temperature
    # guard (72 C, see heating_failsafe.js) reads the supply from the Uni
    # over the network and fails open. A network outage takes away both the
    # heartbeat and that reading at once, so the watchdog goes to failsafe
    # at 100 % with no guard - 75 C to the radiators on pellets, up to 89 on
    # oil. With small children in the house that is the difference that
    # matters. A local sensor wins over the network one and does not share
    # its failure.
    BURNER_OIL: {
        # From "start" to heat arriving: ignition and pre-purge, at most
        # about five minutes on this burner (measured, not estimated) - and
        # twice that here, Fredrik's margin (2026-10-01): an oil burner that
        # needs a second try at lighting should not leave the house short.
        # No auger to fill and no grate to catch, which is why this is a
        # third of the pellet figure. It is the lead time on a predictive
        # restart - the heating begins this far before the house would reach
        # the floor - and the boiler alarm's grace after the burner is lit.
        "start_lead": 600.0,
        "off_delay": 1800.0,
        "burnout_seconds": 60.0,
        # No grate full of embers: the boiler and flue only have to cool.
        "sweep_lead_seconds": 2.0 * 3600.0,
        "call_for_heat": "on",
        # Oil runs the boiler 77-84 on its own thermostat, so it is out of
        # line sooner.
        "alarm_below": 65.0,
    },
}


def burner_settings(burner: str, stored: dict | None = None) -> dict:
    """One burner's settings: its own defaults, with anything remembered
    for that burner on top.

    Remembered per burner rather than per circuit, because swapping the
    burner swaps every one of them at once. Which way the control cable
    calls for heat is a property of the burner and nothing else; so is how
    long it needs to burn out, and how reluctant it is worth being about
    stopping it. Having them follow the name means a swap is one choice
    rather than four numbers to remember.
    """
    values = dict(BURNER_DEFAULTS.get(burner, BURNER_DEFAULTS[BURNER_PELLETS]))
    for key, value in (stored or {}).items():
        if key in ("call_for_heat", "control_path"):
            values[key] = str(value)
        else:
            try:
                values[key] = float(value)
            except (TypeError, ValueError):
                continue
    return values


def relays_in_use(
    control_path: str, has_control: bool, has_power: bool, sweeping: bool = False,
    overheated: bool = False,
) -> tuple[bool, bool]:
    """Which relays carry this cycle's order: (control cable, main supply).

    The chosen path, normally. A sweep adds the supply wherever there is
    one: off for the sweeper - or for the household's own cleaning - means
    off, not a burner standing powered and idle on a control cable that
    says stop. With the cable in use as well, the stop still goes through
    it first and the supply waits out the burnout (plan_heat_source).
    """
    if overheated:
        # The boiler's guard takes every relay there is, whatever the path
        # (Fredrik 2026-10-06): the cable and the supply both.
        return has_control, has_power
    control = has_control and control_path in (PATH_CONTROL, PATH_BOTH)
    power = has_power and (control_path in (PATH_POWER, PATH_BOTH) or sweeping)
    return control, power


def relay_calls_for_heat(call_for_heat: str, call: bool) -> bool:
    """Which way a relay has to sit for `call` to reach the burner.

    A burner that fires when the circuit is broken is an ordinary thing, and
    which way round it goes is a property of the burner rather than of the
    wiring. It lives here, with the rest of what can be tested without a
    boiler, because getting it backwards fails expensively in both
    directions: a house that never heats, or a burner that never stops.
    """
    return (str(call_for_heat).lower() == "on") == call


@dataclass
class HeatSourcePlan:
    """What each relay should be doing this cycle.

    None means leave it alone, which is not the same as False: during a
    burnout the supply is deliberately still on, and saying so separately
    from "should be on" is what keeps the caller from cutting it early.
    """

    control: bool | None = None
    power: bool | None = None
    burning_out: bool = False


def plan_heat_source(
    *,
    wanted: bool,
    seconds_stopped: float,
    has_control: bool,
    has_power: bool,
    burnout_seconds: float,
    at_once: bool = False,
) -> HeatSourcePlan:
    """Turn "heat the boiler / do not" into what the two relays should do.

    Kept here, free of Home Assistant, because getting it wrong is not the
    sort of thing to find out on a boiler. The control cable asks the burner
    to fire; the main supply cuts its power outright. With both fitted the
    stop goes through the control cable first and the supply waits out the
    burner's burnout time, because pulling the supply on a burner that is
    still burning is a different and worse thing than asking it to stop.

    With only the supply fitted there is nothing to ask, so it is cut as
    soon as the decision is made - which is where this house is until the
    control cable is connected.
    """
    if wanted:
        # Power before control: a call for heat down a cable to a burner
        # with no supply is a wire with nothing on the end of it.
        return HeatSourcePlan(control=True if has_control else None,
                              power=True if has_power else None)

    if at_once:
        # An overheated boiler: no burnout to wait for - every minute the
        # burner keeps its power is more heat in a boiler already too hot.
        return HeatSourcePlan(control=False if has_control else None,
                              power=False if has_power else None)

    if not has_control:
        return HeatSourcePlan(power=False if has_power else None)

    if not has_power:
        return HeatSourcePlan(control=False)

    if seconds_stopped >= burnout_seconds:
        return HeatSourcePlan(control=False, power=False)

    return HeatSourcePlan(control=False, power=None, burning_out=True)
