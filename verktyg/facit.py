"""Facit från Pythonversionen: fall där Java-koden ska svara exakt likadant.

    python3 verktyg/facit.py <sökväg till heating/ i sodalen-router-system>

Läser värmekärnan direkt ur Pythonkoden, räknar ut svaren och skriver dem
som CSV under steg/NN-*/resources/. Kopierar också Pythonfilen som steget
översätter till stegets mapp, så att originalet finns att läsa. Kör det igen
när Pythonversionen har ändrats - då visar testerna vad som skiljer.
"""

from __future__ import annotations

import csv
import random
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_core(heating: Path):
    sys.path.insert(0, str(heating / "tests"))
    from load_core import load  # noqa: E402
    return load()


def write(step: str, name: str, header: list[str], rows: list[list]) -> None:
    folder = ROOT / "steg" / step / "resources"
    folder.mkdir(parents=True, exist_ok=True)
    with open(folder / name, "w", newline="", encoding="utf-8") as f:
        out = csv.writer(f, delimiter=";", lineterminator="\n")
        out.writerow(header)
        for row in rows:
            out.writerow(["" if v is None else repr(v) if isinstance(v, float) else v for v in row])
    print(f"{step}/{name}: {len(rows)} fall")


def copy_source(heating: Path, step: str, module: str) -> None:
    source = heating / "homeassistant" / "custom_components" / "sodalen_heating" / f"{module}.py"
    shutil.copyfile(source, ROOT / "steg" / step / f"{module}.py")


def durations(core, rng) -> None:
    words = core.durations.duration_words
    hours = [0.0, -2.0, 0.75, 1.0, 24.0, 168.0, 24 * 30.44, 24 * 365.25, 59.8 / 60, 23.97, 9.94, 9.96, 30.0, 150.0]
    for size in (1 / 60, 1.0, 24.0, 168.0, 24 * 30.44, 24 * 365.25):
        for k in (0.95, 0.99, 0.999, 1.0, 1.001, 1.05, 1.25, 1.35, 9.94, 9.95, 9.96, 10.0):
            hours.append(size * k)
    while len(hours) < 2000:
        hours.append(10 ** rng.uniform(-3, 5))
    write("01-tider", "durations.csv", ["hours", "expected"], [[h, words(h)] for h in hours])


def pid(core, rng) -> None:
    rows = []
    for _ in range(2000):
        kw = dict(
            setpoint=rng.uniform(20, 70),
            measurement=rng.uniform(15, 75),
            last_measurement=None if rng.random() < 0.15 else rng.uniform(15, 75),
            integral=rng.uniform(-10, 110),
            dt=rng.choice([0.0, 5.0, 30.0, rng.uniform(1, 120)]),
            kp=rng.uniform(0, 8),
            ti=rng.choice([0.0, rng.uniform(30, 900)]),
            td=rng.choice([0.0, 0.0, rng.uniform(1, 60)]),
            deadband=rng.choice([0.0, rng.uniform(0, 1.5)]),
            out_min=rng.choice([0.0, 0.0, 5.0]),
            out_max=rng.choice([100.0, 100.0, 80.0]),
        )
        r = core.pid.pid_step(**kw)
        rows.append(list(kw.values()) + [r.output, r.integral, r.error, r.proportional, r.derivative, str(r.saturated).lower()])
    write("02-pid", "pid_step.csv",
          ["setpoint", "measurement", "lastMeasurement", "integral", "dt", "kp", "ti", "td", "deadband", "outMin", "outMax",
           "output", "integralOut", "error", "proportional", "derivative", "saturated"], rows)
    rows = []
    for _ in range(300):
        p, prop, der = rng.uniform(-20, 120), rng.uniform(-30, 30), rng.uniform(-10, 10)
        rows.append([p, prop, der, core.pid.bumpless_integral(position=p, proportional=prop, derivative=der)])
    write("02-pid", "bumpless.csv", ["position", "proportional", "derivative", "expected"], rows)


def curve(core, rng) -> None:
    c = core.curve
    sup, bal, evr, sens, mid = [], [], [], [], []
    for _ in range(2000):
        args = (rng.uniform(-30, 25), rng.uniform(17, 24), rng.uniform(-30, -5),
                rng.uniform(0.2, 3.0), rng.uniform(0.5, 1.5), rng.uniform(-5, 24))
        sup.append(list(args) + [c.supply(*args)])
        sens.append(list(args) + [c.indoor_sensitivity(*args)])
        outdoor, room, design, slope, curvature, _ = args
        wanted = rng.uniform(room - 2, room + 50)
        bal.append([outdoor, wanted, room, design, slope, curvature,
                    c.balance_through(outdoor, wanted, room, design, slope, curvature)])
    for _ in range(500):
        slope, room, design = rng.uniform(0.1, 3.5), rng.uniform(17, 24), rng.uniform(-30, -5)
        number = rng.uniform(-1, 8)
        evr.append([slope, room, design, c.evr_number(slope, room, design), number, c.evr_slope(number, room, design)])
    for _ in range(300):
        room, design, slope = rng.uniform(17, 24), rng.uniform(-30, -5), rng.uniform(0.2, 3.0)
        balance, curvature, reference = rng.uniform(-35, 24), rng.uniform(0.5, 1.5), rng.uniform(0.5, 1.5)
        mid.append([room, design, slope, balance, curvature, reference,
                    c.middle_deviation(room, design, slope, balance, curvature, reference)])
    head = ["outdoor", "room", "designOutdoor", "slope", "curvature", "balance", "expected"]
    write("03-kurvan", "supply.csv", head, sup)
    write("03-kurvan", "indoor_sensitivity.csv", head, sens)
    write("03-kurvan", "balance_through.csv",
          ["outdoor", "wanted", "room", "designOutdoor", "slope", "curvature", "expected"], bal)
    write("03-kurvan", "evr.csv", ["slope", "room", "designOutdoor", "number", "givenNumber", "slopeForGivenNumber"], evr)
    write("03-kurvan", "middle_deviation.csv",
          ["room", "designOutdoor", "slope", "balance", "curvature", "reference", "expected"], mid)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    heating = Path(sys.argv[1]).resolve()
    core = load_core(heating)
    rng = random.Random(2026_10_10)  # samma fall varje gång
    durations(core, rng)
    pid(core, rng)
    curve(core, rng)
    for step, module in (("01-tider", "durations"), ("02-pid", "pid"), ("03-kurvan", "curve")):
        copy_source(heating, step, module)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
