"""Facit från Pythonversionen: fall där Java-koden ska svara exakt likadant.

    python3 verktyg/facit.py <sökväg till heating/ i sodalen-router-system>

Läser värmekärnan direkt ur Pythonkoden, räknar ut svaren och skriver dem
som CSV under steg/NN-*/resources/. Kopierar också Pythonfilen som steget
översätter till stegets mapp, så att originalet finns att läsa. Kör det igen
när Pythonversionen har ändrats - då visar testerna vad som skiljer.
"""

from __future__ import annotations

import csv
import math
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


def _num(value) -> str:
    return "" if value is None else repr(float(value))


def _list(values) -> str:
    return ",".join(_num(v) for v in values)


def _segments(segments) -> str:
    """Avläsningar som "läge,tillopp,retur,panna,dt,antal" med | emellan: samma
    avläsning antal gånger i rad, som när ventilen står still. Tomt = None."""
    return "|".join(_list(s[:5]) + "," + str(s[5]) for s in segments)


def valvemap(core, rng) -> None:
    ValveMap = core.valvemap.ValveMap

    def made():
        if rng.random() < 0.5:
            return ValveMap()
        return ValveMap(horizon_seconds=rng.uniform(3600.0, 60 * 24 * 3600.0),
                        settle_seconds=rng.choice([0.0, 300.0, rng.uniform(10.0, 900.0)]),
                        min_lift=rng.choice([0.0, 15.0, rng.uniform(1.0, 30.0)]),
                        min_band_seconds=rng.choice([0.0, 1800.0, rng.uniform(60.0, 3600.0)]))

    def settings(valve):
        return [valve.horizon_seconds, valve.settle_seconds, valve.min_lift, valve.min_band_seconds]

    def replay(valve, segments):
        last = []
        for position, flow, ret, boiler, dt, count in segments:
            learned = None
            for _ in range(count):
                learned = valve.observe(position, flow, ret, boiler, dt)
            last.append(learned)
        return last

    def wandering():
        """En ventil som rör sig hit och dit, med luckor i avläsningarna."""
        segments, position = [], rng.choice([0.0, 12.5, 22.5, 50.0, rng.uniform(-5.0, 105.0)])
        for _ in range(rng.randint(1, 12)):
            if rng.random() < 0.1:
                at = None
            else:
                position += rng.choice([0.0, 0.0, 0.3, -0.3, 0.5, -0.5, 0.6, -0.6, rng.uniform(-30.0, 30.0)])
                at = position
            ret = None if rng.random() < 0.05 else rng.uniform(20.0, 45.0)
            boiler = None if rng.random() < 0.05 else (ret or 30.0) + rng.uniform(-5.0, 60.0)
            share = rng.uniform(-0.2, 1.3)
            flow = None if rng.random() < 0.05 else (ret or 30.0) + share * ((boiler or 70.0) - (ret or 30.0))
            dt = rng.choice([30.0, 30.0, 30.0, 0.0, -5.0, rng.uniform(1.0, 120.0)])
            segments.append((at, flow, ret, boiler, dt, rng.choice([1, 2, 5, 10, 11, 30, rng.randint(1, 200)])))
        return segments

    def taught(valve):
        """En karta som lärt sig en ventil band för band, som i huset."""
        shape = rng.choice([lambda p: p / 100.0, lambda p: (p / 100.0) ** 2, lambda p: 0.004 + 0.4 * (p / 50.0) ** 1.8])
        noise = rng.choice([0.0, 0.01, 0.05])
        segments = []
        for p in sorted(rng.sample(range(0, 105, 5), rng.randint(0, 14))):
            ret = rng.uniform(22.0, 40.0)
            boiler = ret + rng.uniform(16.0, 60.0)
            share = shape(p) + rng.uniform(-noise, noise)
            seconds = rng.choice([300.0, 600.0, 900.0, 1800.0, 2400.0, 3600.0, 7200.0]) + valve.settle_seconds
            segments.append((float(p), ret + share * (boiler - ret), ret, boiler, 30.0, int(seconds / 30.0) + 1))
        return segments

    rows = []
    for _ in range(500):
        valve = made()
        segments = wandering()
        last = replay(valve, segments)
        rows.append(settings(valve) + [_segments(segments), _list(last), str(valve.settled()).lower(),
                                       _list(valve.share), _list(valve.weight)])
    write("04-ventilkartan", "observe.csv",
          ["horizonSeconds", "settleSeconds", "minLift", "minBandSeconds", "segments", "learned", "settled",
           "share", "weight"], rows)

    rows = []
    for _ in range(300):
        valve = made()
        segments = taught(valve)
        replay(valve, segments)
        valve.fill_prior()
        rows.append(settings(valve) + [_segments(segments), _list(valve.share), _list(valve.weight)])
    write("04-ventilkartan", "fill_prior.csv",
          ["horizonSeconds", "settleSeconds", "minLift", "minBandSeconds", "segments", "share", "weight"], rows)

    rows = []
    for _ in range(500):
        valve = made()
        segments = taught(valve)
        replay(valve, segments)
        filled = rng.random() < 0.4
        if filled:
            valve.fill_prior()
        known = valve.known()
        asked, answers = [], []
        for _ in range(6):
            ret = None if rng.random() < 0.05 else rng.uniform(20.0, 40.0)
            boiler = None if rng.random() < 0.05 else (ret or 30.0) + rng.uniform(0.0, 60.0)
            wanted = rng.uniform(-0.1, 1.1)
            flow = None if rng.random() < 0.05 else (ret or 30.0) + wanted * ((boiler or 70.0) - (ret or 30.0))
            asked.append((flow, ret, boiler))
            answers.append(valve.position_for(flow, ret, boiler))
        gains, gained = [], []
        for _ in range(6):
            position = None if rng.random() < 0.05 else rng.choice([rng.uniform(-5.0, 105.0), 2.5 * rng.randint(0, 40)])
            lift = None if rng.random() < 0.05 else rng.choice([rng.uniform(0.0, 70.0), 15.0, 50.0])
            gains.append((position, lift))
            gained.append(valve.gain_at(position, lift))
        rows.append(settings(valve) + [_segments(segments), str(filled).lower(),
                                       _list(p for p, _ in known), _list(s for _, s in known),
                                       "|".join(_list(q) for q in asked), _list(answers),
                                       "|".join(_list(q) for q in gains), _list(gained)])
    write("04-ventilkartan", "predict.csv",
          ["horizonSeconds", "settleSeconds", "minLift", "minBandSeconds", "segments", "filled",
           "knownPositions", "knownShares", "positionQueries", "positions", "gainQueries", "gains"], rows)

    rows = []
    for _ in range(300):
        valve = made()
        scores = []
        for _ in range(rng.randint(1, 20)):
            predicted = None if rng.random() < 0.1 else rng.uniform(0.0, 100.0)
            actual = None if rng.random() < 0.1 else rng.uniform(0.0, 100.0)
            dt = rng.choice([30.0, 60.0, 0.0, -1.0, rng.uniform(1.0, 7200.0)])
            scores.append((predicted, actual, dt))
            valve.score(predicted, actual, dt)
        rows.append(settings(valve) + ["|".join(_list(s) for s in scores),
                                       _num(valve.shadow_error), _num(valve.shadow_seconds)])
    write("04-ventilkartan", "score.csv",
          ["horizonSeconds", "settleSeconds", "minLift", "minBandSeconds", "scores", "shadowError",
           "shadowSeconds"], rows)


def points(core, rng) -> None:
    pts = core.points

    def hour():
        """En tid på dygnet, ibland utanför 0-24 och ibland ingen klocka alls."""
        return rng.choice([None, rng.uniform(0.0, 24.0), rng.uniform(0.0, 24.0), rng.uniform(-48.0, 72.0),
                           float(rng.randint(-30, 54)),
                           rng.choice([0.0, 6.0, 12.0, 18.0, 24.0, -6.0, -0.5, 5.999, 6.001, 23.999])])

    def outdoor():
        return rng.choice([rng.uniform(-35.0, 25.0), rng.uniform(-35.0, 25.0), float(rng.randint(-20, 15)),
                           2.0 * rng.randint(-15, 10) + rng.choice([-1e-9, 0.0, 1e-9, 0.001, 1.999])])

    rows = []
    for _ in range(1500):
        at, when = outdoor(), hour()
        rows.append([at, when, pts.bin_index(at), pts.PointTable().key_at(at, when)])
    write("05-kurvtabellen", "key_at.csv", ["outdoor", "hour", "binIndex", "key"], rows)

    rows = []
    for _ in range(400):
        cell, ops = pts.Cell(), []
        for _ in range(rng.randint(1, 15)):
            if rng.random() < 0.3:
                factor = rng.choice([rng.random(), 1.0, 0.0, 0.5])
                hours = rng.choice([rng.uniform(0.0, 48.0), 0.0, 24.0])
                cell.fade(factor, hours)
                ops.append("fade," + _list([factor, hours]))
            else:
                need, at, wind = rng.uniform(-5.0, 50.0), rng.uniform(-30.0, 20.0), rng.uniform(0.0, 12.0)
                hours = rng.choice([rng.uniform(0.01, 6.0), rng.uniform(0.01, 6.0), 1.0, 0.0, -1.0])
                cell.add(need, at, wind, hours)
                ops.append("add," + _list([need, at, wind, hours]))
        rows.append(["|".join(ops), cell.weight, cell.mean, cell.m2, cell.outdoor, cell.wind, cell.age])
    write("05-kurvtabellen", "cell.csv", ["ops", "weight", "mean", "m2", "outdoor", "wind", "age"], rows)

    rows = []
    for _ in range(600):
        table, ops, centre = pts.PointTable(), [], rng.uniform(-20.0, 12.0)
        for _ in range(rng.randint(0, 60)):
            r = rng.random()
            if r < 0.08:
                hours = rng.choice([rng.uniform(0.0, 72.0), 0.0, -1.0, 60 * 24.0])
                half = rng.choice([60 * 24.0, 24.0, 0.0, -5.0, rng.uniform(1.0, 2000.0), 1e-3])
                table.fade(hours, half)
                ops.append("fade," + _list([hours, half]))
            elif r < 0.10:
                table.clear()
                ops.append("clear")
            else:
                at = rng.choice([centre + rng.uniform(-3.0, 3.0)] * 30 + [math.nan, math.inf, -math.inf])
                need = rng.choice([25.0 - 0.9 * at + rng.gauss(0.0, 2.0)] * 30 + [math.nan, math.inf, -math.inf])
                wind = None if rng.random() < 0.1 else rng.choice([rng.uniform(0.0, 10.0), -rng.uniform(0.0, 3.0)])
                when = hour()
                hours = rng.choice([rng.uniform(0.05, 3.0), rng.uniform(0.05, 3.0), 0.25, 1.0, 6.0, 0.0, -0.5])
                table.add(outdoor=at, need=need, wind=wind, hour=when, hours=hours)
                ops.append("add," + _list([at, need, wind, when, hours]))
        if rng.random() < 0.5:
            rules, made = pts.Rules(), True
        else:
            rules, made = pts.Rules(red_hours=rng.uniform(0.0, 6.0), green_hours=rng.uniform(6.0, 48.0),
                                    green_spread=rng.uniform(0.3, 3.0), green_sectors=rng.randint(1, 4),
                                    sector_hours=rng.choice([0.0, 1.0, rng.uniform(0.2, 5.0)])), False
        bins = table.bins(rules)
        queries = [(centre + rng.uniform(-4.0, 4.0), hour()) for _ in range(6)]
        rows.append(["|".join(ops), str(made).lower(),
                     _list([rules.red_hours, rules.green_hours, rules.green_spread, rules.green_sectors,
                            rules.sector_hours]),
                     "|".join(_list([b.outdoor, b.need, b.hours, b.spread, b.sectors, b.wind, b.age, b.weight])
                              for b in bins),
                     ",".join(b.status for b in bins),
                     "|".join(_list(q) for q in queries),
                     _list(table.hours_at(*q) for q in queries),
                     ",".join(table.key_at(*q) for q in queries)])
    write("05-kurvtabellen", "point_table.csv",
          ["ops", "defaultRules", "rules", "bins", "statuses", "queries", "hoursAt", "keys"], rows)


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
    valvemap(core, rng)
    points(core, rng)
    for step, module in (("01-tider", "durations"), ("02-pid", "pid"), ("03-kurvan", "curve"),
                         ("04-ventilkartan", "valvemap"), ("05-kurvtabellen", "points")):
        copy_source(heating, step, module)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
