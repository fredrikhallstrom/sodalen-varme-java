"""Every sentence the heating says to the family, in one place.

What the controller decides is numbers and flags; how that reads on the
wall dashboard and in the app is decided here. Three clients - dashboard,
phone, router - showing three wordings for the same state is how a family
learns to distrust all three, so each sentence is written once, as a plain
function of plain values, and tested next to the behaviour it describes.

Swedish, because it goes straight onto a dashboard the family reads.

No Home Assistant imports: see model.py.
"""

from __future__ import annotations

from datetime import date, datetime

from .burner import BURNER_NAMES
from .durations import duration_words, duration_words_seconds
from .model import (
    DELTA_QUIET_DEMAND,
    DELTA_QUIET_LUKEWARM,
    DELTA_QUIET_OFF,
    DELTA_QUIET_SENSOR,
    EMERGENCY_FLOW,
    EMERGENCY_OFF,
    EMERGENCY_VALVE,
    LEARNERS,
    LEARN_DELTA,
    LEARN_GAIN,
    LEARN_SUN,
    LEARN_HOUSE,
    LEARN_TABLE,
    MODE_COAST,
    MODE_EMERGENCY,
    MODE_FAILSAFE,
    MODE_HEATING,
    MODE_HOLD,
    MODE_OFF,
    MODE_SUMMER,
    MODE_WARM,
    TABLE_LEARNING_OFF,
    TABLE_NO_CHAIN,
    TABLE_NO_FIT,
    TABLE_STEERS,
    TABLE_SWITCHED_OFF,
)
from .sweep import (
    ABOUT_BURNT_OUT,
    ABOUT_SELF,
    ABOUT_STILL_ON,
    ABOUT_SWEEPER,
    SELF,
    SWEEPER,
    Push,
    SweepView,
)

MODE_REASONS = {
    MODE_OFF: "Värmestyrningen är avstängd",
    MODE_SUMMER: "Sommarstopp - för varmt ute",
    MODE_WARM: "Huset är varmare än målet",
    MODE_HEATING: "Reglerar mot beräknad tilloppstemperatur",
    MODE_HOLD: "Tilloppsgivaren svarar inte - håller ventilläget",
    MODE_FAILSAFE: "Tilloppsgivaren är borta - kör max värme",
    MODE_COAST: "Huset klarar sig utan värme ett tag",
    MODE_EMERGENCY: "Nödkörning",
}


# The emergency select's options, in the order they are offered.
EMERGENCY_NAMES = {
    EMERGENCY_OFF: "Av",
    EMERGENCY_VALVE: "Fast ventilläge",
    EMERGENCY_FLOW: "Fast tillopp",
}


def emergency_reason(kind: str, position: float, flow: float) -> str:
    """The status line while the house is run by hand - with the number, so
    nobody has to go looking for what it was set to."""
    if kind == EMERGENCY_VALVE:
        return f"Nödkörning - fast ventilläge {position:.0f} %"
    if kind == EMERGENCY_FLOW:
        return f"Nödkörning - fast tillopp {flow:.0f} °C"
    return MODE_REASONS[MODE_EMERGENCY]

# Said instead of the mode's reason while a sweep keeps the burner off. The
# mode carries on underneath - the radiators still get what is in the boiler -
# so the reason is the thing that changed.
SWEEP_REASON = "Sotning - brännaren är av tills sotningen är utförd"


def coast_hold_text(
    enabled: bool, known: bool, hours: float | None, why: str = ""
) -> str:
    """How long the house keeps its warmth, in words.

    Here rather than in a dashboard or a phone because three clients showing
    three different sentences for the same state is how a family learns to
    distrust all three. Plain arguments so it can be tested without Home
    Assistant, like everything else in this file.

    The case worth the trouble: hours is None both when the model has
    nothing to say and when the house holds right through the forecast.
    Those are opposite answers - "I do not know" and the best outcome there
    is - and a raw value shows them as the same blank.
    """
    if not enabled:
        return "Avstängd"
    if not known:
        # Saying how far it has got turns "not yet" into "Thursday evening".
        return f"Lär sig huset ännu ({why})" if why else "Lär sig huset ännu"
    if hours is None:
        return "Klarar hela prognosen"
    return f"Cirka {duration_words(hours)}"


def coast_stop_text(
    shunt: bool, burner: bool, mode: str, enabled: bool = True,
    waiting_for_weather: bool = False, turn_hours: float | None = None,
) -> str:
    """What the predictive setback may stop - the permission.

    Two flags rather than one because they are decided on different
    horizons: the shunt may close as soon as the house holds longer than it
    takes to get heat back into the rooms, the burner not until it also
    holds longer than lighting it costs. Said as a sentence because "Ja /
    Nej" on two rows makes the reader do that reasoning themselves.

    Only the permission. What the setback has actually shut is the status
    line, coast_status_text() - this row once tried to say both in a warm
    stop, and read "the burner may not stop yet" under a burner the warm
    stop's own setting had already turned off.
    """
    if not enabled:
        return "Förutseende avstängning är avslagen"
    if mode in (MODE_HOLD, MODE_FAILSAFE):
        return "Inget får stänga - tilloppsgivaren svarar inte"
    if mode == MODE_EMERGENCY:
        return "Inget får stänga - nödkörning"
    if mode not in (MODE_HEATING, MODE_WARM, MODE_COAST):
        return "Vilar - värmen är redan stoppad"
    # Since 2026-10-10 a setback starts only towards weather that turns.
    turn = ""
    if turn_hours is not None:
        turn = f" - vädret vänder om {duration_words(turn_hours)}"
    if shunt and burner:
        return "Shunt och brännare får stänga" + turn
    if shunt:
        return "Bara shunten får stänga" + turn
    if burner:
        # Only ever acted on in a warm stop, with the valve already shut;
        # "Bara brännaren" read as a burner going out under an open one.
        return "Brännaren får stänga om shunten står på 0 %"
    if waiting_for_weather:
        return "Inget får stänga - inget bättre väder i prognosen"
    return "Inget får stänga än"


def coast_status_text(
    mode: str, burner_stopped: bool, enabled: bool = True, hold_seconds: float | None = None
) -> str:
    """What the predictive setback has shut right now - not what it may.

    "Får stänga" is the permission; this is the effect, so the family can
    tell at a glance whether it is the setback that has the boiler off.
    `burner_stopped` is whether the setback's verdict is what has the
    burner off - see presenter.coast_status(). In the setback's own mode
    the shunt is its doing; in a warm stop the shunt is the warm stop's,
    but the burner being off is the setback's.
    """
    if not enabled:
        return "Sparläge - avslaget"
    if mode == MODE_COAST:
        if burner_stopped:
            return "Sparläge - brännare och shunt stängd"
        return "Sparläge - shunt stängd"
    if mode == MODE_WARM and burner_stopped:
        return "Sparläge - brännare stängd"
    if hold_seconds is not None:
        return f"Sparläge - väntar, kurvan lär sig ({duration_words_seconds(hold_seconds)} kvar)"
    return "Sparläge - ej aktiv avstängning"


# The learners on the card under the heating curve (ControlOutput.learning).
LEARNER_NAMES = {
    "kurva": "Kurvan",
    "sol": "Solens verkan",
    "tabell": "Kurvtabellen",
    "husmodell": "Husmodellen",
    "delta": "Förväntat delta",
    "ventilforstarkning": "Ventilförstärkning",
    "ventilkarta": "Ventilkarta",
}

# Why a learner stands still. "{left}" is the wait that is left.
LEARNING_WHY = {
    "off": "Självinlärningen är avstängd",
    "no_indoor": "Ingen innetemperatur",
    "indoor_partial": "Någon innegivare saknas - huset mäts inte helt",
    "no_outdoor": "Utetemperaturen saknas",
    "override": "Override-kontakten är sluten",
    "after_override": "Väntar efter override eller nödkörning ({left} kvar)",
    "sweeping": "Sotning pågår",
    "after_sweep": "Väntar efter sotningen ({left} kvar)",
    "boiler_failing": "Väntar - pannan håller inte temperaturen",
    "sweep_cold": "Väntar efter sotningen, tills huset är på målet",
    "coast_cold": "Väntar efter sparläget, tills huset är på målet",
    "shortfall_cold": "Väntar - pannan räckte inte till, tills huset är på målet",
    "after_shortfall": "Väntar - pannan räckte inte till ({left} kvar)",
    "off_cold": "Väntar efter att värmestyrningen var av, tills huset är på målet",
    "after_off": "Väntar efter att värmestyrningen var av ({left} kvar)",
    "after_coast": "Väntar efter sparläget ({left} kvar)",
    "after_failsafe": "Väntar efter vakthunden ({left} kvar)",
    "failsafe_warm": "Väntar efter vakthunden, tills huset är nere på målet (högst {left} kvar)",
    "over_target": "Väntar tills huset är nere på målet - något annat än kurvan värmde det (högst {left} kvar)",
    "sun": "Solen värmer huset",
    # Not a wait: the curve learning in the sun with the sun's part taken off
    # (sunterm.py) - said beside "Lär sig".
    "sun_off": "solen räknad bort",
    "after_sun": "Väntar efter solen ({left} kvar)",
    "sun_warm": "Väntar efter solen, tills huset är nere på målet (högst {left} kvar)",
    "target_changed": "Målet ändrades nyss ({left} kvar)",
    "low_demand": "För litet värmebehov",
    "flow_off": "Tilloppet är inte på sitt börvärde",
    "valve_end": "Ventilen står vid ändläge",
    "table_steers": "Tabellen styr kurvan",
    "off_target": "Inne mer än en halv grad från målet",
    "indoor_drift": "Innetemperaturen rör sig",
    "outdoor_drift": "Utetemperaturen ändras för snabbt",
    "settling": "Huset är stilla - samlar om {left}",
    "no_return": "Returgivaren saknas",
    "not_settled": "Tilloppet har inte satt sig",
    "off_target_delta": "Huset är inte på målet",
    "lukewarm": "Tilloppet är för ljumt",
    "no_flow": "Tilloppsgivaren svarar inte",
    "no_position": "Ventilläget är okänt",
    "low_lift": "Pannan är för sval mot returen",
    "map_unknown": "Ventilkartan känner inte läget",
    "no_data": "Saknar mätvärden",
}

# The modes as a learner standing still says them.
LEARNING_MODES = {
    MODE_OFF: "värmestyrningen avstängd",
    MODE_SUMMER: "sommarstopp",
    MODE_WARM: "varmstopp",
    MODE_COAST: "sparläge",
    MODE_HOLD: "tilloppsgivaren svarar inte",
    MODE_FAILSAFE: "tilloppsgivaren borta",
    MODE_EMERGENCY: "nödkörning",
}


def _learning_text(key: str, learning: bool, why: str, value: float | None) -> str:
    if learning:
        if key == LEARN_TABLE and value is not None:
            return f"Samlar - {duration_words(value)} i den här rutan"
        if key == LEARN_HOUSE and value is not None:
            return f"Lär sig - {duration_words(value)} sett"
        if key == LEARN_DELTA:
            return "Kalibrerar"
        if key == LEARN_GAIN and value is not None:
            return f"Lär sig - {value:.2f} °C per %".replace(".", ",")
        if why == "sun_off":
            return f"Lär sig - {LEARNING_WHY['sun_off']}"
        if key == LEARN_SUN and why == "sun_term_hours" and value is not None:
            return f"Lär sig - {duration_words(value)} av 2 dagar"
        if key == LEARN_SUN and value is not None:
            return f"Lär sig - osäkerhet {round(100.0 * value)} %"
        return "Lär sig"
    if why.startswith("mode:"):
        mode = why.split(":", 1)[1]
        return f"Står still - {LEARNING_MODES.get(mode, mode)}"
    said = LEARNING_WHY.get(why, why)
    if "{left}" in said:
        said = said.replace("{left}", duration_words_seconds(max(0.0, value or 0.0)))
    return said


def learning_rows(learning: dict) -> list[dict]:
    """The card under the heating curve: each learner, whether it is
    learning, and why not - in the order they are listed. A learner that
    said nothing this cycle is left out rather than guessed at."""
    rows = []
    for key in LEARNERS:
        if key not in learning:
            continue
        active, why, value = learning[key]
        rows.append({
            "namn": LEARNER_NAMES[key],
            "aktiv": bool(active),
            "text": _learning_text(key, bool(active), why, value),
        })
    return rows


# What each entry in ControlOutput.degraded is called on a wall.
SENSOR_NAMES = {
    "outdoor": "utegivaren",
    "indoor": "alla innegivare",
    "flow": "tilloppsgivaren",
    "return": "returgivaren",
}

SENSORS_ALL_ANSWER = "Alla givare svarar"


def sensor_health_text(degraded: list[str], silent_indoor: list[str]) -> str:
    """Which sensors are not answering, in one sentence.

    Its own sentence because the loop degrades gracefully by design: without
    the outdoor sensor it keeps regulating on the last filtered value, so
    the house stays the right temperature and nothing on the dashboard
    looked wrong while Netatmo was gone for most of 24-25 September. The
    learning and the forecast scoring stood still the whole time.

    `silent_indoor` names indoor sensors that are not answering while others
    still are - the fusion carries on without them, which is the same kind
    of quiet. When every indoor sensor is gone "indoor" is in `degraded`
    and naming them one by one adds nothing.
    """
    missing = [SENSOR_NAMES.get(key, key) for key in degraded]
    if "indoor" not in degraded:
        missing += [f"innegivaren {name}" for name in silent_indoor]
    if not missing:
        return SENSORS_ALL_ANSWER
    listed = (
        missing[0]
        if len(missing) == 1
        else ", ".join(missing[:-1]) + " och " + missing[-1]
    )
    return f"{listed[0].upper()}{listed[1:]} svarar inte"


# What losing each sensor costs, most serious first. Said next to the
# warning because "utegivaren svarar inte" alone reads as harmless when the
# house is still the right temperature - which it is, by design.
SENSOR_CONSEQUENCES = (
    ("flow", "Utan tilloppsgivare står shunten kvar där den är, och går till "
             "reservläget om det håller i sig."),
    ("indoor", "Utan innetemperatur går regleringen på ren kurva - säkert, men "
               "utan rumskorrigering."),
    ("outdoor", "Regleringen kör vidare på den senast filtrerade "
                "utetemperaturen, men lär sig inte huset och kan inte bedöma "
                "väderprognoserna förrän givaren är tillbaka."),
)


# In place of the outdoor sentence above while the curve follows the kept
# forecast - "the last filtered value" would be the wrong thing to say.
OUTDOOR_ON_FORECAST = (
    "Regleringen följer väderprognosen i stället för utetemperaturen, men "
    "lär sig inte huset och kan inte bedöma väderprognoserna förrän givaren "
    "är tillbaka."
)


def sensor_health_consequence(
    degraded: list[str], silent_indoor: list[str], flags: list[str] = ()
) -> str:
    """What the sensors in sensor_health_text() being gone means, or "".

    One sentence per lost sensor that changes how the loop regulates, most
    serious first. A lost return sensor or a single quiet indoor one changes
    nothing a reader can act on, so those get the plain reassurance.
    """
    said = [
        OUTDOOR_ON_FORECAST if key == "outdoor" and "outdoor_forecast" in flags else text
        for key, text in SENSOR_CONSEQUENCES
        if key in degraded
    ]
    if said:
        return " ".join(said)
    if degraded or silent_indoor:
        return "Regleringen kör vidare med de givare som svarar."
    return ""


def delta_text(
    quiet: str,
    delta: float | None,
    expected: float | None,
    trim: float,
    held_cold: bool,
    readable_from: float | None = None,
    held_moving: bool = False,
    read: float | None = None,
    warming_left: float | None = None,
) -> str:
    """What the delta term is doing, in words.

    Written here for the same reason as the coast texts: the app worked it
    out on its own, and when the term went quiet for a lukewarm supply it
    read the missing expected delta as "requires a return sensor" - a
    wrong reason, stated confidently, on the one screen meant to explain.
    """
    if quiet == DELTA_QUIET_OFF:
        return "Delta-kompensationen är avstängd."
    if quiet == DELTA_QUIET_SENSOR:
        return "Kräver både tillopps- och returgivare."
    if quiet == DELTA_QUIET_DEMAND:
        return "Vilar: värmebehovet är för litet för att deltat ska säga något."
    if quiet == DELTA_QUIET_LUKEWARM:
        said = "Vilar: tilloppet är för ljumt för att deltat ska säga något."
        if readable_from is not None:
            said += f" Den börjar korrigera vid cirka {readable_from:.0f} °C."
        return said
    if warming_left is not None:
        return ("Elementen värms upp efter stoppet - deltat bedöms om "
                f"{duration_words_seconds(max(60.0, warming_left))}.")
    # The numbers the correction stands on: the quarter-hour mean, not the
    # reading of the moment beside it on the card (2026-10-09).
    on = ""
    if read is not None and expected is not None:
        on = f"kvartsmedel {read:.1f} mot {expected:.1f} °C".replace(".", ",")
    if held_moving and abs(trim) > 0.2:
        kept = f"{trim:+.1f}".replace(".", ",")
        return f"Tilloppet är på väg - korrigeringen hålls ({kept} °C{', ' + on if on else ''}) tills det satt sig."
    if held_moving:
        return "Tilloppet är på väg - deltat bedöms när det satt sig."
    if trim < -0.2:
        return (f"Elementen tar mindre värme än väntat{' (' + on + ')' if on else ''}, så tilloppet sänks - "
                "något annat värmer huset.")
    if trim > 0.2:
        return f"Elementen tar mer värme än väntat{' (' + on + ')' if on else ''}, så tilloppet höjs."
    if held_cold:
        return ("Sänkningen pausad: huset är kallare än målet, och "
                "rumsregleringen har sista ordet.")
    if delta is not None and expected is not None:
        # Decimal commas, on a wall in a Swedish house.
        measured = f"{delta:.1f}".replace(".", ",")
        wanted = f"{expected:.1f}".replace(".", ",")
        return f"Nära det förväntade ({measured} mot {wanted} °C), ingen korrigering behövs."
    return ""


BOILER_TEXTS = {
    "ok": "Pannan håller temperaturen",
    "fault": "Driftproblem: pannan sjunker i temperatur trots begärd uppvärmning",
    "recovering": "Pannan värmer igen",
}


def boiler_watch_text(
    state: str, watched: bool, waiting_seconds: float, manual_off: bool, sweeping: bool = False
) -> str:
    """The boiler alarm in words: its state, or why it is not watching."""
    if state != "ok":
        return BOILER_TEXTS.get(state, state)
    if sweeping:
        return "Bevakas inte - brännaren är avstängd för sotning"
    if manual_off:
        return "Bevakas inte - brännaren är avstängd för hand"
    if not watched:
        return "Bevakas inte - ingen värme begärs från pannan"
    if waiting_seconds > 0.0:
        return f"Brännaren startar - bevakas om {duration_words_seconds(max(60.0, waiting_seconds))}"
    return BOILER_TEXTS["ok"]


def table_status_text(status: str, max_gap: float) -> str:
    """Whether the curve table steers the curve, or what it waits for."""
    if status == TABLE_NO_CHAIN:
        if max_gap <= 2.0:
            return "Väntar på två gröna prickar i rad"
        return f"Väntar på två gröna prickar högst {max_gap:.0f} °C isär"
    return {
        TABLE_STEERS: "Styr kurvan",
        TABLE_SWITCHED_OFF: "Avstängd - styr aldrig",
        TABLE_LEARNING_OFF: "Självinlärningen är av",
        TABLE_NO_FIT: "Inga användbara prickar än",
    }.get(status, status)


RADIATORS_FINE = "Inga tecken på fel"


def radiator_warning_text(starved: bool, share: float | None) -> str:
    """The radiator warning in words, or RADIATORS_FINE.

    Says what it cannot know as plainly as what it can: which radiator is
    beyond one pair of pipe sensors.
    """
    if not starved:
        return RADIATORS_FINE
    said = (
        "Elementen tar mindre värme än väntat fast huset är kallt. "
        "Möjliga orsaker: luft i ett element, ett element som kortsluter "
        "tillopp till retur, eller en ventil som fastnat stängd."
    )
    if share is not None:
        said += f" Gällt {100.0 * share:.0f} % av värmetiden de senaste dygnen."
    return said


def heat_source_text(status: dict) -> str:
    """Which burner, and what it has been asked to do.

    The burner gets swapped physically, so "på" on its own leaves out the
    half of the answer that changes: whether the thing on the wall is the
    pellet burner or the oil one. Saying both means the tile still reads
    right the winter after a swap, without anyone having to remember.

    The burnout state is worth its own word too - the burner has been told
    to stop but still has its supply, and a cut a few minutes from now is
    expected rather than a fault.
    """
    if not status.get("configured"):
        return "ej konfigurerad"

    burner = BURNER_NAMES.get(status.get("brannare"), status.get("brannare") or "okänd")
    if not status.get("available"):
        said = f"{burner}, ingen kontakt"
    elif status.get("brinner_ut"):
        said = f"{burner}, brinner ut"
    elif status.get("state") == "off":
        said = f"{burner}, av för sotning" if status.get("sotning") else f"{burner}, av"
    else:
        said = f"{burner}, på"

    # Last, because it is the part that changes by the minute. Whole
    # degrees: the burner's own hysteresis is seven of them.
    boiler = status.get("panna")
    return said if boiler is None else f"{said}, panna {boiler:.0f} °C"


def heat_source_reason(wanted: bool | None, flags: list[str], for_hot_water: bool) -> str:
    """Why the burner should be on or off, or "" with none configured.

    The app had sentences of its own here until 2026-10-18, and would have
    said "Ska vara på så länge värmen är igång" all summer of a burner
    running for the hot water.
    """
    if wanted is None:
        return ""
    if "boiler_overheat" in flags:
        return "Avstängd tills pannan har svalnat."
    if "sweeping" in flags:
        return "Avstängd för sotning tills den är utförd."
    if for_hot_water:
        return "På för varmvattnet - värmen behöver den inte just nu."
    if wanted:
        return "Ska vara på så länge värmen är igång."
    return "Stängd av eftersom värmen är stoppad."


# The flags the family gets a line of their own for: those no other sentence
# says. The rest are in the delta's, the setback's, the sweep's, the burner's
# or the sensors' own words, or are the controller's business (table_point).
# Never a flag's own name: the app showed "hot_water" under the indoor
# sensors, a flag it had no words for (2026-10-18).
FLAG_TEXTS = {
    "indoor_partial": "Någon innegivare räknas inte in just nu - regleringen kör vidare med de andra.",
}


def flag_texts(flags: list[str]) -> list[str]:
    """The lines for this cycle's flags, in their order."""
    return [FLAG_TEXTS[flag] for flag in flags if flag in FLAG_TEXTS]


def failsafe_text(status: dict) -> str:
    """What the Shelly's watchdog script says about itself.

    The one sensor whose value does not come from the control cycle. "ingen
    kontakt" is the interesting state: it means the script is not answering,
    so the layer that is supposed to save the house if Home Assistant dies
    is itself unverified.
    """
    if not status.get("configured"):
        return "ej konfigurerat"
    if not status.get("available"):
        return "ingen kontakt"
    if status.get("failsafe"):
        return "max värme aktivt"
    if not status.get("enabled", True):
        return "avstängt i Shelly"
    return "vakar"


# --- sweeping (sweep.py) -------------------------------------------------------

WEEKDAYS = ("måndag", "tisdag", "onsdag", "torsdag", "fredag", "lördag", "söndag")
MONTHS = (
    "januari", "februari", "mars", "april", "maj", "juni",
    "juli", "augusti", "september", "oktober", "november", "december",
)


def day_words(day: date, today: date) -> str:
    """A day as the family says it: "i dag", "i morgon", "i går", otherwise
    "tisdag 14 oktober"."""
    ahead = (day - today).days
    if ahead == 0:
        return "i dag"
    if ahead == 1:
        return "i morgon"
    if ahead == -1:
        return "i går"
    return f"{WEEKDAYS[day.weekday()]} {day.day} {MONTHS[day.month - 1]}"


def moment_words(moment: datetime, now: datetime) -> str:
    """A time seen from now: "kl 01:00" today, "i natt kl 01:00" for the small
    hours of tomorrow, otherwise the day and the time."""
    local = moment.astimezone(now.tzinfo)
    at = f"kl {local:%H:%M}"
    ahead = (local.date() - now.date()).days
    if ahead == 0:
        return at
    if ahead == 1 and local.hour < 6:
        return f"i natt {at}"
    return f"{day_words(local.date(), now.date())} {at}"


def sweep_status_text(view: SweepView) -> str:
    """The sweeps in one line: under way, the sweeper's date, or nothing."""
    now = view.now
    if view.active == SWEEPER:
        return f"Sotningsläge - brännaren av sedan {moment_words(view.since, now)}"
    if view.active == SELF:
        if view.burnt_out is not None and view.burnt_out > now:
            until = moment_words(view.burnt_out, now)
            if view.power_cut:
                return f"Egensotning - brännaren är strömlös och svalnar till {until}"
            return f"Egensotning - brännaren brinner ut till {until}"
        return "Egensotning - klar att sota"
    if view.day is not None:
        return f"Sotaren kommer {day_words(view.day, now.date())} kl {view.arrival}"
    return "Inget sotningsdatum"


def self_sweep_text(view: SweepView) -> str:
    """The household's own cleaning: when next, or why there is no next."""
    if view.active == SELF:
        return "Pågår"
    if view.self_interval_days <= 0:
        return "Inte planerad"
    if view.self_next is None:
        return f"Var {view.self_interval_days}:e dag, räknat från första egensotningen"
    when = day_words(view.self_next, view.now.date())
    if view.self_overdue:
        return f"Försenad - skulle ha gjorts {when}"
    return f"Nästa {when}"


def sweep_push_text(push: Push, view: SweepView) -> tuple[str, str]:
    """A reminder's title and body. Written so the push alone is enough:
    what, when, and what to press afterwards."""
    now = view.now
    today = now.date()
    if push.about == ABOUT_SWEEPER:
        said = f"Sotaren kommer {day_words(view.day, today)} kl {view.arrival}."
        if view.stop is not None and view.stop > now:
            said += f" Brännaren stängs av {moment_words(view.stop, now)}."
        if view.immersion:
            said += " Elpatronen kopplas in under tiden."
        return "Sotning", said
    if push.about == ABOUT_STILL_ON:
        since = moment_words(view.since, now)
        if view.active == SELF:
            return ("Egensotningen pågår fortfarande",
                    f"Brännaren är av sedan {since}. Tryck Egensotning utförd "
                    "när du är klar, så startar den igen.")
        return ("Sotningsläget är fortfarande på",
                f"Brännaren är av sedan {since}. Tryck Sotning utförd när "
                "sotaren har varit här, så startar den igen.")
    if push.about == ABOUT_SELF:
        when = day_words(view.self_next, today)
        if push.days_before > 0:
            return "Egensotning", f"Dags att sota pannan {when}."
        if push.days_before == 0:
            return "Egensotning", "Dags att sota pannan i dag."
        return ("Egensotning",
                f"Egensotningen skulle ha gjorts {when}. Sota pannan, eller "
                "hoppa över perioden.")
    if push.about == ABOUT_BURNT_OUT:
        if view.power_cut:
            return "Egensotning", "Brännaren har svalnat - dags att sota."
        return "Egensotning", "Brännaren har brunnit ut - dags att sota."
    return "Sotning", ""
