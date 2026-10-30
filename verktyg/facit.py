"""Facit från Pythonversionen: fall där Java-koden ska svara exakt likadant.

    python3 verktyg/facit.py <sökväg till heating/ i sodalen-router-system>

Läser värmekärnan direkt ur Pythonkoden, räknar ut svaren och skriver dem
som CSV under steg/NN-*/resources/. Kopierar också Pythonfilen som steget
översätter till stegets mapp, så att originalet finns att läsa. Kör det igen
när Pythonversionen har ändrats - då visar testerna vad som skiljer.
"""

from __future__ import annotations

import csv
import dataclasses
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


def _bool(value) -> str:
    return "" if value is None else str(value).lower()


def _floats(values) -> str:
    """En lista där None är tom och oändligheten inf, som Python skriver den."""
    return ",".join(_num(v) for v in values)


def _matrix(rows) -> str:
    return "|".join(_floats(row) for row in rows)


def _configure(priors: dict, floors: dict) -> str:
    """"configure,p,index,väntat,styrka,...,f,index,golv,..." """
    parts = ["configure"]
    for index, (expected, strength) in sorted(priors.items()):
        parts += ["p", str(index), _num(expected), _num(strength)]
    for index, floor in sorted(floors.items()):
        parts += ["f", str(index), _num(floor)]
    return ",".join(parts)


def estimators(core, rng) -> None:
    est = core.estimators

    rows = []
    for _ in range(500):
        seconds = rng.choice([600.0, 600.0, 0.0, -5.0, rng.uniform(10.0, 5000.0), 1e-3])
        start = None if rng.random() < 0.5 else rng.uniform(-20.0, 80.0)
        ewma, ops, outputs = est.Ewma(seconds, value=start), [], []
        for _ in range(rng.randint(1, 12)):
            sample = None if rng.random() < 0.1 else rng.uniform(-20.0, 80.0)
            dt = rng.choice([30.0, 30.0, 0.0, -5.0, rng.uniform(1.0, 1000.0), 1e7,
                             30.0 * seconds, 29.999 * seconds])
            ops.append("update," + _list([sample, dt]))
            outputs.append(ewma.update(sample, dt))
        rows.append([seconds, start, "|".join(ops), _list(outputs), ewma.value])
    write("06-skattarna", "ewma.csv", ["seconds", "start", "ops", "outputs", "value"], rows)

    rows = []
    for _ in range(400):
        seconds = rng.choice([3600.0, 3600.0, 0.0, -1.0, rng.uniform(60.0, 86400.0)])
        watch, ops = est.DutyWatch(seconds), []
        for _ in range(rng.randint(1, 20)):
            held = rng.choice([True, False, None, True])
            dt = rng.choice([30.0, 30.0, 0.0, -1.0, rng.uniform(1.0, 7200.0), 1e8, 30.0 * seconds])
            watch.update(held, dt)
            ops.append(_bool(held) + "," + _num(dt))
        rows.append([seconds, "|".join(ops), watch.share, watch.weight, watch.hours])
    write("06-skattarna", "duty_watch.csv", ["seconds", "ops", "share", "weight", "hours"], rows)

    rows = []
    for _ in range(500):
        horizon = rng.choice([3600.0, 3600.0, 0.0, -1.0, 1e9, rng.uniform(60.0, 1e6)])
        fit, ops = est.Regression(horizon), []
        centre = rng.uniform(-5.0, 5.0)
        spread = rng.choice([0.0, 0.01, 0.3, 1.0, 4.0])
        intercept, slope, noise = rng.uniform(-10.0, 10.0), rng.uniform(-3.0, 3.0), rng.choice([0.0, 0.1, 1.0])
        for _ in range(rng.randint(0, 30)):
            dt = rng.choice([30.0, 60.0, 0.0, -1.0, rng.uniform(1.0, 3600.0), 1e9])
            if rng.random() < 0.15:
                fit.fade(dt)
                ops.append("fade," + _num(dt))
            else:
                x = centre + rng.uniform(-spread, spread)
                y = intercept + slope * x + rng.uniform(-noise, noise)
                fit.add(x, y, dt)
                ops.append("add," + _list([x, y, dt]))
        min_variance = rng.choice([0.01, 0.01, 1e-9, rng.uniform(1e-6, 0.5)])
        a, b, known = fit.fit(min_variance)
        rows.append([horizon, "|".join(ops), min_variance, fit.weight, fit.sum_x, fit.sum_y, fit.sum_xx,
                     fit.sum_xy, fit.mean_x, fit.mean_y, fit.variance_x, a, b, str(known).lower()])
    write("06-skattarna", "regression.csv",
          ["horizon", "ops", "minVariance", "weight", "sumX", "sumY", "sumXx", "sumXy", "meanX", "meanY",
           "varianceX", "intercept", "slope", "identifiable"], rows)

    rows = []
    for _ in range(500):
        size = rng.randint(1, 6)
        kind = rng.choice(["spd", "spd", "general", "nodiagonal", "zerocolumn", "tiny", "small"])
        if kind == "spd":
            b = [[rng.uniform(-1.0, 1.0) for _ in range(size)] for _ in range(size)]
            shift = rng.uniform(0.01, 1.0)
            matrix = [[sum(b[k][r] * b[k][c] for k in range(size)) + (shift if r == c else 0.0)
                       for c in range(size)] for r in range(size)]
        else:
            matrix = [[rng.uniform(-5.0, 5.0) for _ in range(size)] for _ in range(size)]
            if kind == "nodiagonal":
                for i in range(size):
                    matrix[i][i] = 0.0
            elif kind == "zerocolumn":
                column = rng.randrange(size)
                for row in matrix:
                    row[column] = 0.0
            elif kind == "tiny":
                matrix = [[v * 1e-14 for v in row] for row in matrix]
            elif kind == "small":
                matrix = [[v * 1e-10 for v in row] for row in matrix]
        rhs = [rng.uniform(-10.0, 10.0) for _ in range(size)]
        solved = est.solve_symmetric(matrix, rhs)
        rows.append([size, _matrix(matrix), _list(rhs), "" if solved is None else _list(solved)])
    write("06-skattarna", "solve_symmetric.csv", ["size", "matrix", "rhs", "solution"], rows)

    def priors_and_floors(size, truth):
        priors, floors = {}, {}
        for index in range(size):
            if rng.random() < 0.2:
                priors[index] = (rng.uniform(-3.0, 3.0), rng.choice([rng.uniform(0.1, 50.0), 1e3]))
            if rng.random() < 0.25:
                floors[index] = rng.choice([0.0, rng.uniform(-1.0, 1.0)])
                if rng.random() < 0.5:
                    truth[index] = floors[index] - rng.uniform(0.1, 2.0)
        return priors, floors

    rows = []
    for _ in range(400):
        size = rng.randint(1, 5)
        horizon = rng.choice([0.0, 0.0, rng.uniform(5.0, 100.0), -1.0])
        ridge = rng.choice([None, None, 1e-3, 0.0])
        fit = est.MultiRegression(size=size, horizon=horizon) if ridge is None else \
            est.MultiRegression(size=size, horizon=horizon, ridge=ridge)
        truth = [rng.uniform(-3.0, 3.0) for _ in range(size)]
        constant = rng.random() < 0.7
        centre = [rng.uniform(-2.0, 2.0) for _ in range(size)]
        spread = [rng.choice([1.0, 1.0, 3.0, 0.3, 0.0 if rng.random() < 0.3 else 1.0]) for _ in range(size)]
        noise = rng.choice([0.0, 0.1, 1.0])
        ops = []
        priors, floors = priors_and_floors(size, truth)
        if priors or floors:
            fit.configure(priors, floors)
            ops.append(_configure(priors, floors))

        def sample():
            x = [centre[i] + rng.uniform(-spread[i], spread[i]) for i in range(size)]
            if constant:
                x[0] = 1.0
            return x

        for _ in range(rng.randint(0, 40)):
            r = rng.random()
            if r < 0.05:
                fit.coefficients()
                ops.append("coef")
            elif r < 0.08:
                index, factor = rng.randrange(size), rng.choice([1.0, rng.uniform(0.2, 5.0)])
                fit.rescale(index, factor)
                ops.append("rescale," + str(index) + "," + _num(factor))
            elif r < 0.10:
                priors, floors = priors_and_floors(size, list(truth))
                fit.configure(priors, floors)
                ops.append(_configure(priors, floors))
            else:
                x = sample()
                if r < 0.13:
                    x = x + [1.0] if rng.random() < 0.5 or size == 1 else x[:-1]
                y = sum(t * xi for t, xi in zip(truth, x)) + rng.uniform(-noise, noise)
                dt = rng.choice([1.0, 1.0, 0.0, rng.uniform(0.1, 5.0), -1.0, 1e4])
                fit.add(x, y, dt)
                ops.append("add," + _list([y, dt] + x))
        queries = [sample(), [c + 10.0 * max(s, 1.0) for c, s in zip(centre, spread)],
                   [rng.uniform(-5.0, 5.0) for _ in range(size)],
                   [rng.uniform(-5.0, 5.0) for _ in range(size + rng.choice([0, 0, 0, 1]))]]
        rows.append([size, horizon, "" if ridge is None else ridge, "|".join(ops),
                     fit.weight, fit.residual, fit.residual_data, fit.yy, _list(fit.xy), _matrix(fit.xx),
                     "" if fit.coefficients() is None else _list(fit.coefficients()),
                     _num(fit.sigma), _num(fit.in_sample_sigma()),
                     _matrix(queries), _floats(fit.predict(q) for q in queries),
                     _floats(fit.reach(q) for q in queries), _floats(fit.variance(q) for q in queries),
                     _floats(fit.uncertainty(q) for q in queries)])
    write("06-skattarna", "multi_regression.csv",
          ["size", "horizon", "ridge", "ops", "weight", "residual", "residualData", "yy", "xy", "xx",
           "coefficients", "sigma", "inSampleSigma", "queries", "predictions", "reaches", "variances",
           "uncertainties"], rows)


def _conditions(indoor, c) -> str:
    """"inne,ute,vind,riktning,sol,klocka" - tomt för None."""
    return _floats([indoor, c.outdoor, c.wind, c.bearing, c.sun, c.hour])


def _slots(weather) -> str:
    """Prognosen som "timmar:ute:vind:riktning:sol:klocka" med / emellan; tom prognos = tomt."""
    return "/".join(":".join(_num(v) for v in (hours, c.outdoor, c.wind, c.bearing, c.sun, c.hour))
                    for hours, c in weather)


def _answer(pair) -> str:
    """(balans, fel) eller tomt när modellen inte kan svara."""
    return "" if pair is None else _list(pair)


def _outlook(out) -> str:
    return ",".join([_num(out.hours), _bool(out.holds), _num(out.uncertainty), _bool(out.known),
                     _num(out.turns_at)])


def thermal(core, rng) -> None:
    from weatherhouse import WeatherHouse  # i heating/tests, på sys.path sedan load_core
    th = core.thermal
    Conditions = th.Conditions

    def maybe(value, chance=0.2):
        return None if rng.random() < chance else value

    rows = []
    for _ in range(100):
        elevation = maybe(rng.choice([rng.uniform(-10.0, 90.0), rng.uniform(-10.0, 90.0),
                                      rng.uniform(90.0, 120.0)]), 0.1)
        cloud = maybe(rng.choice([rng.uniform(0.0, 100.0), rng.uniform(0.0, 100.0),
                                  rng.uniform(-30.0, 0.0), rng.uniform(100.0, 150.0)]))
        rows.append(["solar_index", _floats([elevation, cloud]), _num(th.solar_index(elevation, cloud))])
    for _ in range(100):
        wind = maybe(rng.choice([rng.uniform(0.0, 15.0), rng.uniform(0.0, 15.0), rng.uniform(-3.0, 0.0)]))
        bearing = maybe(rng.choice([rng.uniform(0.0, 360.0), rng.uniform(0.0, 360.0), rng.uniform(-90.0, 450.0)]))
        hour = maybe(rng.choice([rng.uniform(0.0, 24.0), rng.uniform(0.0, 24.0), rng.uniform(-6.0, 30.0)]))
        args = [rng.uniform(15.0, 25.0), rng.uniform(-30.0, 25.0), wind, bearing, rng.uniform(0.0, 1.2),
                rng.uniform(-5.0, 30.0), hour]
        row = th.regressors(indoor=args[0], outdoor=args[1], wind=wind, bearing=bearing, sun=args[4],
                            delta=args[5], hour=hour)
        rows.append(["regressors", _floats(args), _floats(row)])
    for _ in range(100):
        args = [rng.choice([rng.uniform(20.0, 60.0), 39.0, rng.uniform(0.0, 20.0)]),
                rng.choice([rng.uniform(1.0, 20.0), 10.0, rng.uniform(0.0, 1.0)]),
                rng.choice([1800.0, 600.0, 300.0, rng.uniform(1.0, 3600.0), rng.uniform(0.0, 1.0), 0.0, -5.0])]
        priors = th.house_priors(*args)
        rows.append(["priors", _floats(args),
                     ",".join(f"{index},{_num(expected)},{_num(strength)}"
                              for index, (expected, strength) in sorted(priors.items()))])
    for _ in range(100):
        args = [rng.uniform(0.0, 1.2), rng.uniform(-5.0, 30.0), rng.uniform(-1.0, 1.0)] + \
            [rng.uniform(-50.0, 150.0) for _ in range(3)]
        rows.append(["steady_regressors", _floats(args),
                     _floats(th.steady_regressors(sun=args[0], delta=args[1], slope=args[2], wind=tuple(args[3:])))])
    for _ in range(100):
        args = [rng.uniform(-5.0, 60.0), rng.uniform(-1.0, 1.0)] + [rng.uniform(-50.0, 150.0) for _ in range(3)]
        rows.append(["night_regressors", _floats(args),
                     _floats(th.night_regressors(heat=args[0], slope=args[1], wind=tuple(args[2:])))])
    for _ in range(100):
        indoor = maybe(rng.uniform(15.0, 25.0), 0.1)
        ret = maybe(rng.choice([rng.uniform(25.0, 50.0), rng.uniform(10.0, 22.0)]), 0.1)
        flow = maybe((30.0 if ret is None else ret) + rng.uniform(-2.0, 25.0), 0.1)
        rows.append(["radiator_excess", _floats([flow, ret, indoor]), _num(th.radiator_excess(flow, ret, indoor))])
    write("07-husmodellen", "thermal.csv", ["kind", "args", "expected"], rows)

    def house(warm):
        """Ett hus med slumpade parametrar; warm = ett som värmer sig självt vid -5,
        ibland med en förlust under golvet LOSS_FLOOR."""
        if warm:
            return WeatherHouse(indoor=rng.uniform(19.0, 23.0),
                                g0=rng.choice([rng.uniform(0.0003, 0.0015), rng.uniform(0.004, 0.012)]),
                                g_wind=rng.uniform(0.0, 0.001), g_north=0.0, solar=rng.uniform(0.3, 1.2),
                                internal=rng.uniform(0.8, 1.5), radiator=rng.uniform(0.03, 0.09),
                                daily=rng.uniform(0.0, 0.4), daily_peak=rng.uniform(0.0, 24.0))
        return WeatherHouse(indoor=rng.uniform(19.0, 23.0), g0=rng.uniform(0.03, 0.09),
                            g_wind=rng.uniform(0.0, 0.006), g_north=rng.uniform(-0.004, 0.004),
                            solar=rng.uniform(0.3, 1.2), internal=rng.uniform(0.0, 0.3),
                            radiator=rng.uniform(0.03, 0.09), daily=rng.uniform(0.0, 0.4),
                            daily_peak=rng.uniform(0.0, 24.0))

    def weather(clock0):
        """Väder med slumpade perioder och amplituder, avrundat innan det används; solen
        går efter hushållets klocka. Ibland utan sol, utan vind eller utan riktning hela vägen."""
        base, swing, period = rng.uniform(-15.0, 10.0), rng.uniform(0.0, 10.0), rng.uniform(12.0, 72.0)
        wind_mean, wind_swing, wind_period = rng.uniform(0.0, 6.0), rng.uniform(0.0, 5.0), rng.uniform(5.0, 40.0)
        turn, bearing0 = rng.uniform(-60.0, 60.0), rng.uniform(0.0, 360.0)
        peak, sunrise, sunset = rng.uniform(0.2, 1.2), rng.uniform(6.0, 9.0), rng.uniform(15.0, 19.0)
        skies = [rng.uniform(0.0, 1.0) for _ in range(rng.randint(1, 7))]
        no_sun, no_wind, no_bearing = rng.random() < 0.15, rng.random() < 0.12, rng.random() < 0.12

        def at(hour):
            outdoor = base + swing * math.sin(2.0 * math.pi * hour / period)
            wind = None if no_wind else \
                round(max(0.0, wind_mean + wind_swing * math.sin(2.0 * math.pi * hour / wind_period)), 1)
            bearing = None if no_bearing else round((bearing0 + hour * turn) % 360.0, 1)
            clock = (clock0 + hour) % 24.0
            height = math.sin(math.pi * (clock - sunrise) / (sunset - sunrise)) if sunrise < clock < sunset else 0.0
            sun = 0.0 if no_sun else round(peak * height * skies[int(hour // 24) % len(skies)], 2)
            return round(outdoor, 1), wind, bearing, sun
        return at

    def scenario(long):
        """Ett hus genom sitt väder med modellen som åskådare, frågor här och var och några på slutet."""
        warm = not long and rng.random() < 0.1
        start = maybe(rng.uniform(0.0, 24.0))  # klockan modellen ser, ibland ingen alls
        clock0 = rng.uniform(0.0, 24.0) if start is None else start  # hushållets, solens och nattens klocka
        h, at = house(warm), weather(clock0)
        if long:
            nights, sample_seconds, dt = rng.random() < 0.65, 1800.0, 1800.0
            hours = rng.uniform(12.0, 14.0) * 24.0 if nights else rng.uniform(9.0, 10.0) * 24.0
        else:
            nights = rng.random() < 0.12
            sample_seconds = rng.choice([1800.0, 1800.0, 1800.0, 1800.0, 600.0, 600.0, 300.0])
            split = sample_seconds > 300.0 and rng.random() < 0.14  # dt kortare än provintervallet
            dt = sample_seconds / 3.0 if split else sample_seconds
            hours = rng.uniform(6.0, {1800.0: 30.0, 600.0: 12.0, 300.0: 8.0}[sample_seconds])
            if split:
                hours = rng.uniform(6.0, 12.0) if sample_seconds == 1800.0 else 6.0
            if warm:  # ett varmt hus behöver tid på sig innan förlusten hamnar på golvet
                sample_seconds, dt, hours = 1800.0, 1800.0, rng.uniform(18.0, 40.0)
        mode = rng.choice(["regulated", "regulated", "regulated", "fixed", "off"])
        target, level, period = rng.uniform(19.0, 23.0), rng.uniform(2.0, 15.0), rng.uniform(4.0, 36.0)
        radiators_on, offset = rng.random() < 0.25, rng.uniform(3.0, 10.0)
        noise = rng.choice([0.0, 0.0, 0.05, 0.15])
        total = int(math.ceil(hours * 3600.0 / dt))
        gap_at = (rng.randrange(1, max(2, total // 5)) if long else rng.randrange(1, total)) \
            if rng.random() < 0.15 else None
        design_at = rng.randrange(0, total) if rng.random() < 0.15 else None
        retune_at = rng.randrange(0, total) if rng.random() < 0.15 else None
        asks = 3.0 / total

        def delta_at(hour, clock, outdoor, wind, bearing, sun):
            """Som regulated() i test_thermal.py, ett fast schema, eller värmen av."""
            if mode == "regulated":
                need = h.loss_coefficient(wind, bearing) * (target - outdoor) - h.internal \
                    - h.solar * sun - h.household(clock)
                return max(0.0, need / h.radiator + 8.0 * (target - h.indoor))
            if mode == "fixed":
                return level if (hour % period) < period / 2.0 else 0.0
            return 0.0

        def conditions(hour):
            """Vad som frågas: nära det huset fått se eller långt därifrån."""
            if rng.random() < 0.6:
                outdoor, wind, bearing, sun = at(hour + rng.uniform(0.0, 24.0))
                outdoor = round(outdoor + rng.uniform(-3.0, 3.0), 1)
            else:
                outdoor = round(rng.uniform(-30.0, 25.0), 1)
                wind = maybe(round(rng.uniform(0.0, 15.0), 1), 0.3)
                bearing = maybe(round(rng.uniform(0.0, 360.0), 1), 0.3)
                sun = rng.choice([0.0, round(rng.uniform(0.0, 1.2), 2)])
            when = None if rng.random() < (0.7 if start is None else 0.2) else \
                rng.choice([round((clock0 + hour) % 24.0, 2), round(rng.uniform(0.0, 24.0), 2)])
            return Conditions(outdoor=outdoor, wind=wind, bearing=bearing, sun=sun, hour=when)

        def forecast(hour):
            """2-13 rutor över 1-24 timmar, nära eller långt från det lärda; ibland tom."""
            if rng.random() < 0.03:
                return []
            count, first = rng.randint(2, 13), rng.choice([0.0, 0.0, 1.0, 0.5])
            span, far = rng.uniform(first + 1.0, 24.0), rng.random() < 0.3
            clocked = rng.random() < (0.3 if start is None else 0.8)
            cold, warm_at = conditions(hour), rng.uniform(0.0, span)
            slots = []
            for i in range(count):
                offset = round(first + (span - first) * i / (count - 1), 2)
                when = round((clock0 + hour + offset) % 24.0, 2) if clocked else None
                if far:
                    turned = offset >= warm_at and rng.random() < 0.5
                    c = Conditions(outdoor=round(cold.outdoor + (8.0 if turned else 0.0), 1), wind=cold.wind,
                                   bearing=cold.bearing, sun=0.7 if turned else cold.sun, hour=when)
                else:
                    outdoor, wind, bearing, sun = at(hour + offset)
                    c = Conditions(outdoor=outdoor, wind=wind, bearing=bearing, sun=sun, hour=when)
                slots.append((offset, c))
            return slots

        model, ops, outputs = th.HouseModel(), [], []

        def ask(hour, kind=None):
            kind = kind or rng.choice(["drift", "unc", "reach", "cools", "floor", "balance", "night",
                                       "outlook", "outlook"])
            if kind in ("drift", "unc", "reach"):
                indoor, c = round(rng.uniform(15.0, 25.0), 2), conditions(hour)
                ops.append(kind + "," + _conditions(indoor, c))
                asked = {"drift": model.drift, "unc": model.drift_uncertainty, "reach": model.reach}[kind]
                outputs.append(_num(asked(indoor, c)))
            elif kind in ("balance", "night"):
                indoor = round(rng.uniform(18.0, 24.0), 2)
                ops.append(kind + "," + _num(indoor))
                outputs.append(_answer(model.balance(indoor) if kind == "balance" else model.night_balance(indoor)))
            elif kind == "cools":
                ops.append("cools")
                outputs.append(_bool(model.cools_when_left_alone()))
            elif kind == "floor":
                ops.append("floor")
                outputs.append(_bool(model.loss_on_floor()))
            else:
                indoor = round(h.indoor + rng.uniform(-1.0, 2.0), 2)
                floor, confidence = round(indoor - rng.uniform(0.2, 3.0), 2), round(rng.uniform(0.0, 3.0), 2)
                step_hours, weather = rng.choice([0.25] * 8 + [0.5, 0.1]), forecast(hour)
                ops.append("outlook," + _floats([indoor, floor, confidence, step_hours]) + "," + _slots(weather))
                outputs.append(_outlook(model.outlook(indoor=indoor, floor=floor, weather=weather,
                                                      confidence=confidence, step_hours=step_hours)))

        elapsed, since_sun, step = 0.0, 1e9, 0
        while elapsed < hours * 3600.0:
            hour = elapsed / 3600.0
            clock = (clock0 + hour) % 24.0
            outdoor, wind, bearing, sun = at(hour)
            delta = round(delta_at(hour, clock, outdoor, wind or 0.0, bearing or 0.0, sun), 1)
            h.step(dt / 3600.0, outdoor, wind or 0.0, bearing or 0.0, sun, delta, hour=clock)
            indoor = round(h.indoor + rng.uniform(-noise, noise), 4)
            since_sun = 0.0 if sun > 0.05 else since_sun + dt
            night = None if not nights else \
                ((clock >= 22.0 or clock < 6.0) and not 7.0 < clock < 17.0 and since_sun >= 7200.0)
            radiators = None
            if radiators_on and rng.random() > 0.05:
                ret = indoor + (offset + 0.6 * delta if delta > 0.0 else 0.0)
                radiators = round(th.radiator_excess(ret + delta, ret, indoor), 1)
            when = None if start is None else round(clock, 2)
            if step == design_at:
                span, design_delta = round(rng.uniform(20.0, 60.0), 1), round(rng.uniform(0.5, 20.0), 2)
                seconds = rng.choice([1800.0, 600.0, 300.0, 0.5])
                model.set_design(span, design_delta, seconds)
                ops.append("design," + _floats([span, design_delta, seconds]))
            if step == retune_at:
                seconds = round(rng.uniform(14.0, 60.0), 1) * 86400.0 if long else \
                    rng.choice([round(rng.uniform(1.0, 60.0), 1) * 86400.0, 3600.0, 7.0 * 86400.0])
                model.retune(seconds)
                ops.append("retune," + _num(seconds))
            if step == gap_at:
                ops.append("observe,,,,,,," + _floats([delta, dt, sample_seconds]) + ",,")
                outputs.append(_bool(model.observe(indoor=None, conditions=None, delta=delta, dt=dt,
                                                   sample_seconds=sample_seconds)))
            else:
                c = Conditions(outdoor=outdoor, wind=wind, bearing=bearing, sun=sun, hour=when)
                ops.append("observe," + _floats([indoor, outdoor, wind, bearing, sun, when, delta, dt,
                                                 sample_seconds, radiators]) + "," + _bool(night))
                outputs.append(_bool(model.observe(indoor=indoor, conditions=c, delta=delta, dt=dt,
                                                   sample_seconds=sample_seconds, radiators=radiators,
                                                   night=night)))
            if rng.random() < asks:
                ask(hour)
            elapsed += dt
            step += 1
        for kind in ("drift", "unc", "reach", "cools", "floor", "balance", "outlook"):
            ask(hour, kind)
        if nights or rng.random() < 0.3:
            ask(hour, "night")

        def coefficients(fit):
            return "" if fit.coefficients() is None else _list(fit.coefficients())

        fit, steady, night_fit = model.fit, model.steady, model.night
        return ["|".join(ops), "|".join(outputs), fit.weight, steady.weight, night_fit.weight,
                model.observed_hours, model.last_indoor, model.seconds_since_sample, model.since_steady,
                _floats(model.window), len(model.day), _floats(model.day_totals), _floats(model.stretch),
                _list(fit.xy), _list(steady.xy), _list(night_fit.xy),
                coefficients(fit), coefficients(steady), coefficients(night_fit)]

    rows = [scenario(long=False) for _ in range(195)] + [scenario(long=True) for _ in range(25)]
    write("07-husmodellen", "house_model.csv",
          ["ops", "outputs", "fitWeight", "steadyWeight", "nightWeight", "observedHours", "lastIndoor",
           "secondsSinceSample", "sinceSteady", "window", "dayCount", "dayTotals", "stretch", "fitXy",
           "steadyXy", "nightXy", "fitCoefficients", "steadyCoefficients", "nightCoefficients"], rows)


def _reading(r) -> str:
    """"namn:temperatur:vind:riktning:moln" - tomt för None."""
    return ":".join([r.name] + [_num(v) for v in (r.temperature, r.wind, r.bearing, r.cloud)])


def _readings(readings) -> str:
    return "/".join(_reading(r) for r in readings)


def _map(values: dict) -> str:
    """"namn:värde/namn:värde" i insättningsordning; tomt när den är tom."""
    return "/".join(f"{name}:{_num(value)}" for name, value in values.items())


def _covariance(covariance: dict) -> str:
    """"första:andra:värde/..." rad för rad, kolumn för kolumn, i insättningsordning."""
    return "/".join(f"{first}:{second}:{_num(value)}"
                    for first, row in covariance.items() for second, value in row.items())


def _standin(slots) -> str:
    """Prognosen som "timmar:ute" med / emellan; tom prognos = tomt."""
    return "/".join(f"{_num(hours)}:{_num(c.outdoor)}" for hours, c in slots)


def forecast(core, rng) -> None:
    fc = core.forecast
    Reading, Conditions, solve = fc.Reading, core.thermal.Conditions, core.estimators.solve_symmetric
    asks = ["all", "subset", "one", "never", "empty", "current", "lacking", "north", "none", "standin"]
    found = {"clamp": "klämd vikt", "fallback": "reservsvar", "retune": "retune", "none": "combine utan temperatur",
             "standin": "standin på 1,5 h", "gap": "sanning None", "quiet": "tyst källa", "extras": "vind/riktning/moln"}
    counts = {key: 0 for key in found}

    def answer(ensemble, names):
        """Vad lösningen gör med namnen: klämmer en negativ vikt, faller tillbaka på lika delar, eller inget."""
        if len(names) < 2:
            return None
        solved = solve(ensemble._matrix(names), [1.0] * len(names))
        if solved is None or sum(solved) <= 0.0:
            return "fallback"
        return "clamp" if any(value < 0.0 for value in solved) else None

    def scenario(long):
        """Några källor genom ett väder med ensemblen som domare; frågor här och var och några på slutet.
        Tvillingar delar ett fel; "doubled" ger den ena dubbelt (en negativ vikt att klämma) och
        "identical" exakt samma avläsning (en singulär matris när priorn hunnit klinga av)."""
        kind = rng.choice(["plain"] * 11 + ["twins"] * 2 + ["doubled"] * 2 + ([] if long else ["identical"] * 2))
        if kind == "plain":
            names = rng.sample(["smhi", "yr", "met", "owm", "hill"], rng.choice([2, 2, 3, 3, 4, 5]))
        else:
            names = ["twin_a", "twin_b"] + rng.sample(["lone", "a", "b", "c"], rng.choice([0, 1, 1, 2, 3]))
            rng.shuffle(names)
        twins = ("twin_a", "twin_b")
        offsets = {n: round(rng.uniform(-3.0, 3.0), 4) for n in names}
        noise = {n: round(rng.uniform(0.05, 3.0), 4) for n in names}
        if kind == "identical":
            offsets["twin_b"] = offsets["twin_a"]
        extras = rng.random() < 0.3
        carries = {n: tuple(extras and rng.random() < 0.6 for _ in range(3)) for n in names}
        if kind == "identical":  # kort horisont och många varv, så att priorn på diagonalen försvinner helt
            total, dt, retune_at = rng.randint(45, 100), 3600.0, rng.randint(0, 3)
            horizon = round(rng.uniform(3600.0, 4000.0), 1)
            settled = retune_at + int(32.0 * horizon / dt) + 4  # frågor innan dess vore illa konditionerade
        else:
            r = rng.random()
            total = rng.randint(151, 400) if long else rng.randint(5, 19) if r < 0.1 else \
                int(20 + 131 * rng.random() ** 3.5)
            dt = rng.choice([1800.0] * 6 + [600.0, 3600.0])
            retune_at = rng.randrange(total) if rng.random() < 0.25 else None
            horizon, settled = round(rng.uniform(3600.0, 30 * 86400.0), 1), 0
        sprinkle = kind != "identical" and rng.random() < 0.25
        quiet, q0, q1 = None, 0, 0
        candidates = [n for n in names if kind != "identical" or n not in twins]
        if candidates and rng.random() < 0.25:
            quiet = rng.choice(candidates)
            q0 = 0 if rng.random() < 0.4 else rng.randrange(total)
            q1 = total if rng.random() < 0.3 else min(total, q0 + rng.randint(1, max(1, total // 2)))
        gaps = set(rng.sample(range(total), rng.randint(1, 3))) if rng.random() < 0.2 else set()

        def readings_at(truth, step):
            """Vad källorna säger: var och en med sin offset och sitt brus, tvillingarna med ett delat fel."""
            shared, out = rng.uniform(-2.0, 2.0), []
            for name in names:
                if name == quiet and q0 <= step < q1:
                    temperature = None
                else:
                    temperature = truth + offsets[name]
                    if name not in twins:
                        temperature += rng.uniform(-noise[name], noise[name])
                    elif kind == "identical":
                        temperature += shared
                    else:
                        temperature += (2.0 * shared if kind == "doubled" and name == "twin_b" else shared) \
                            + rng.uniform(-0.05, 0.05)
                    temperature = round(temperature, 4)
                wind, bearing, cloud = [round(rng.uniform(0.0, top), 1) if carried and rng.random() > 0.1 else None
                                        for carried, top in zip(carries[name], (15.0, 360.0, 100.0))]
                out.append(Reading(name, temperature, wind, bearing, cloud))
            return out

        ensemble, ops, outputs, flags = fc.Ensemble(), [], [], set()

        def ask(truth, step, what=None):
            what = what or rng.choice(asks)
            if what in ("all", "subset", "one", "never", "empty"):
                if what == "all":
                    asked = list(names)
                elif what == "subset":
                    asked = rng.sample(names, rng.randint(1, len(names)))
                elif what == "one":
                    asked = [rng.choice(names)]
                elif what == "never":
                    asked = rng.sample(names, rng.randint(0, len(names)))
                    asked.insert(rng.randint(0, len(asked)), "new")
                else:
                    asked = []
                weights, spread = ensemble.weights(asked)
                flags.add(answer(ensemble, asked))
                ops.append("weights," + "/".join(asked))
                outputs.append("/".join(_num(weights[n]) for n in asked) + "," + _num(spread))
            elif what in ("current", "lacking", "north", "none"):
                readings = readings_at(truth, step)
                if what == "lacking":
                    i = rng.randrange(len(readings))
                    r = readings[i]
                    readings[i] = Reading(r.name, None, r.wind, r.bearing, r.cloud)
                elif what == "north":
                    far, near = rng.choice([(350.0, 10.0), (355.0, 5.0), (340.0, 20.0), (10.0, 350.0)])
                    readings = [Reading(names[0], round(truth + offsets[names[0]], 4), bearing=far),
                                Reading(names[1], round(truth + offsets[names[1]], 4), bearing=near)]
                elif what == "none":
                    readings = [Reading(r.name, None, r.wind, r.bearing, r.cloud) for r in readings]
                elif rng.random() < 0.15:
                    readings.append(Reading("new", round(truth + rng.uniform(-3.0, 3.0), 4)))
                out = ensemble.combine(readings)
                flags.add(answer(ensemble, [r.name for r in readings if r.temperature is not None]))
                if out.temperature is None:
                    flags.add("none")
                ops.append("combine," + _readings(readings))
                outputs.append(",".join([_num(out.temperature), _num(out.wind), _num(out.bearing), _num(out.cloud),
                                         _num(out.uncertainty), str(out.sources), _map(out.weights)]))
            else:
                first = rng.choice([round(rng.uniform(0.0, 3.0), 2)] * 4 + [1.5, 1.5001, 1.0, 0.0, 2.0])
                slots = [(round(first + i, 4), Conditions(outdoor=round(truth + rng.uniform(-3.0, 3.0), 1), wind=None,
                                                         bearing=None, sun=0.0, hour=None))
                         for i in range(rng.randint(0, 4))]
                if slots and first == 1.5:
                    flags.add("standin")
                ops.append("standin," + _standin(slots))
                outputs.append(_num(fc.outdoor_from_forecast(slots)))

        value = rng.uniform(-10.0, 10.0)
        if rng.random() < 0.15:
            ask(round(value, 4), 0)  # en fråga innan någon är bedömd
        for step in range(total):
            value += rng.uniform(-1.5, 1.5)
            truth = round(max(-25.0, min(25.0, value)), 4)
            if step == retune_at:
                ensemble.retune(horizon)
                ops.append("retune," + _num(horizon))
                flags.add("retune")
            step_dt = rng.choice([dt] * 18 + [0.0, -10.0]) if sprinkle else dt
            readings = readings_at(truth, step)
            given = None if step in gaps else truth
            if given is None:
                flags.add("gap")
            if any(r.temperature is None for r in readings):
                flags.add("quiet")
            if any(r.wind is not None or r.bearing is not None or r.cloud is not None for r in readings):
                flags.add("extras")
            ops.append("observe," + _num(given) + "," + _num(step_dt) + "," + _readings(readings))
            outputs.append(_bool(ensemble.observe(given, readings, step_dt)))
            if step >= settled and rng.random() < 3.0 / total:
                ask(truth, step)
        for what in ["all", "current"] + rng.sample([a for a in asks if a not in ("all", "current")], 3):
            ask(truth, total - 1, what)
        for flag in flags:
            if flag:
                counts[flag] += 1
        return ["|".join(ops), "|".join(outputs), ensemble.weight, _map(ensemble.bias), _covariance(ensemble.covariance)]

    rows = [scenario(long=False) for _ in range(246)] + [scenario(long=True) for _ in range(4)]
    write("08-prognosen", "ensemble.csv", ["ops", "outputs", "weight", "bias", "covariance"], rows)
    print("08-prognosen: rader med " + ", ".join(f"{found[key]} {counts[key]}" for key in found))


def _bins(bins) -> str:
    """Lådorna som "ute,behov,timmar,spridning,sektorer,vind,ålder,vikt,status" med | emellan."""
    return "|".join(_list([b.outdoor, b.need, b.hours, b.spread]) + f",{b.sectors}," + _list([b.wind, b.age, b.weight])
                    + f",{b.status}" for b in bins)


def _outdoors(values) -> str:
    """Utetemperaturer med / emellan; tomt för en tom följd."""
    return "/".join(_num(v) for v in values)


def curvefit(core, rng) -> None:
    cf, pts, c = core.curvefit, core.points, core.curve
    found = {"bent": "böj sökt", "moved": "böjd kurva", "held": "böjgräns som höll emot", "rejected": "avvikare",
             "coldout": "avvikare = kallaste gröna", "reach0": "räckvidd 0", "under": "balans under design",
             "reach1": "räckvidd 1", "shorter": "kedja kortare än de gröna", "unsteerable": "ej styrbar",
             "empty": "inga lådor", "gap57": "maxGreenGap 5 eller 7", "same": "gröna i samma låda"}
    counts = {key: 0 for key in found}

    def inputs():
        """Vad fit_curve får: mest husets vanliga värden, ibland något annat."""
        room = rng.choice([21.0] * 8 + [20.0, 22.0, 23.0, round(rng.uniform(20.0, 23.0), 1)])
        design = rng.choice([-18.0] * 8 + [-15.0, -20.0, -22.0, round(rng.uniform(-22.0, -15.0), 1)])
        r = rng.random()
        balance = design - round(rng.uniform(0.0, 4.0), 1) if r < 0.06 else rng.choice([20.0, 21.0, 20.5]) \
            if r < 0.12 else round(rng.uniform(8.0, 14.0), 2)
        kw = dict(room=room, design_outdoor=design, balance=balance, slope_prior=round(rng.uniform(0.5, 1.3), 3),
                  curvature=0.8 if rng.random() < 0.7 else rng.choice([0.6, 0.65, 0.7, 0.75, 0.9, 1.0, 1.1,
                                                                        round(rng.uniform(0.6, 1.1), 3)]),
                  bend_limit=2.0 if rng.random() < 0.6 else rng.choice([0.0, 0.5, 1.0, 3.0]))
        if rng.random() < 0.25:
            kw["prior_weight"] = rng.choice([0.0, 0.1, 1.0])
        if rng.random() < 0.6:
            kw["max_green_gap"] = rng.choice([2.0, 3.0, 4.0, 5.0, 6.0, 7.0])
        return kw

    def truth(kw):
        """Den sanna kurvan lådorna mäts från: lutningen oftast nära priorn och balansen nära
        passningens, ibland en bit ifrån."""
        balance = kw["balance"] + rng.choice([0.0, 0.0, rng.uniform(-1.5, 1.5), rng.uniform(-3.0, 3.0)])
        away = rng.choice([rng.uniform(-0.1, 0.1), rng.uniform(-0.2, 0.2), rng.uniform(-0.8, 0.8)])
        slope, curvature = round(min(1.3, max(0.5, kw["slope_prior"] + away)), 3), round(rng.uniform(0.55, 1.2), 3)
        return lambda outdoor: c.supply(outdoor, kw["room"], kw["design_outdoor"], slope, curvature, balance) - kw["room"]

    def outdoors():
        """2-12 utetemperaturer mellan -20 och +12: en stege, ibland gles, eller på måfå."""
        if rng.random() < 0.6:
            count, gap = rng.randint(2, 12), rng.choice([2.0, 2.0, 2.0, 4.0, 6.0])
            start = rng.choice([rng.uniform(4.0, 12.0), rng.uniform(4.0, 12.0), min(12.0, -20.0 + gap * (count - 1))])
            return [round(start - gap * i, 2) for i in range(count) if start - gap * i >= -20.0]
        return [round(rng.uniform(-20.0, 12.0), 2) for _ in range(rng.randint(2, 12))]

    def table_bins(kw, need):
        """En tabell fylld som test_points.py:s dots: timmarna fördelade på delar av dygnet, brus på
        behovet, lite vind och ibland en blekning mitt i."""
        table, noise = pts.PointTable(), rng.choice([0.0, rng.uniform(0.0, 0.5), rng.uniform(0.0, 2.0)])
        wind, temps = rng.choice([0.0, 0.0, rng.uniform(0.0, 8.0)]), outdoors()
        faded = rng.randint(1, len(temps)) if rng.random() < 0.3 else None
        for i, outdoor in enumerate(temps, 1):
            hours = rng.choice([rng.uniform(2.0, 6.0), rng.uniform(6.0, 24.0), rng.uniform(24.0, 60.0),
                                rng.uniform(24.0, 60.0)])
            sectors = rng.choice([(0, 1, 2, 3)] * 6 + [(0, 1, 2), (1, 2, 3), (0, 2, 3), (0, 1), (0,)])
            parts = rng.randint(1, 3)
            for sector in sectors:
                for _ in range(parts):
                    at = round(outdoor + rng.uniform(-0.3, 0.3), 2)
                    table.add(outdoor=at, need=round(need(at) + rng.uniform(-noise, noise), 3),
                              wind=round(wind + rng.uniform(0.0, 2.0), 1) if wind else 0.0,
                              hour=sector * 6.0 + rng.uniform(0.0, 5.9), hours=round(hours / len(sectors) / parts, 3))
            if i == faded:
                table.fade(rng.uniform(1.0, 500.0), rng.choice([60 * 24.0, 30 * 24.0, rng.uniform(24.0, 3000.0)]))
        return table.bins()

    def direct_bins(kw, need, greenless):
        """Lådor byggda för hand: nollvikter, bråkvikter, flera gröna i samma låda, gröna långt isär
        och en ensam grön vid -12. Ordningen blandad."""
        pattern = "random" if greenless else rng.choice(["random", "random", "same", "gaps", "lone"])
        if pattern == "same":
            low = 2.0 * math.floor(rng.uniform(-18.0, 10.0) / 2.0)
            temps = [low + rng.uniform(0.0, 2.0) for _ in range(rng.randint(2, 3))] \
                + [rng.uniform(-20.0, 12.0) for _ in range(rng.randint(0, 4))]
        elif pattern == "gaps":
            temps = rng.sample([10.0, 4.0, -2.0, -8.0, -14.0, -20.0], rng.randint(2, 5))
        elif pattern == "lone":
            temps = [-12.0] + [4.0 + 2.0 * i for i in range(rng.randint(1, 3))] \
                + [rng.uniform(-10.0, 2.0) for _ in range(rng.randint(0, 2))]
        else:
            temps = [rng.uniform(-22.0, 14.0) for _ in range(rng.randint(1, 10))]
        bins = []
        for i, outdoor in enumerate(temps):
            if greenless:
                status = rng.choice([pts.RED, pts.YELLOW])
            elif pattern == "lone":
                status = pts.GREEN if i == 0 or outdoor >= 4.0 else rng.choice([pts.RED, pts.YELLOW])
            elif pattern == "random":
                status = rng.choice([pts.RED, pts.YELLOW, pts.GREEN, pts.GREEN])
            else:
                status = pts.GREEN if rng.random() < 0.85 else pts.YELLOW
            if status == pts.RED:
                weight = 0.0
            elif status == pts.YELLOW:
                weight = rng.choice([round(rng.uniform(0.01, 0.5), 4), round(rng.uniform(0.01, 0.5), 4), 0.0])
            else:
                weight = rng.choice([1.0] * 4 + [round(rng.uniform(0.01, 1.0), 4), 0.0])
            bins.append(pts.Bin(outdoor=round(outdoor, 3), need=round(need(outdoor) + rng.uniform(-2.0, 2.0), 4),
                                hours=round(rng.uniform(0.5, 80.0), 2), spread=round(rng.uniform(0.0, 3.0), 3),
                                sectors=rng.randint(0, 4), wind=round(rng.choice([0.0, rng.uniform(0.0, 10.0)]), 2),
                                age=rng.choice([0.0, round(rng.uniform(0.0, 2000.0), 1)]), status=status,
                                weight=weight))
        rng.shuffle(bins)
        return bins

    def knocked(bins):
        """Ett eller två behov knuffade 4-12 K, som en kamin tänd en hel låda; ibland just den kallaste
        gröna. Sant när det var den."""
        heard = [i for i, b in enumerate(bins) if b.weight > 0.0]
        greens = [i for i in heard if bins[i].status == pts.GREEN]
        coldest = bool(greens) and rng.random() < 0.3
        picks = [min(greens, key=lambda i: bins[i].outdoor)] if coldest \
            else rng.sample(heard, min(len(heard), rng.choice([1, 1, 2])))
        for i in picks:
            shift = rng.choice([1.0, 1.0, -1.0]) * rng.uniform(4.0, 12.0)
            bins[i] = dataclasses.replace(bins[i], need=round(bins[i].need + shift, 4))
        return coldest

    def inside(bins, kw):
        """Går fit_curve:s väg en gång till med modulens egna hjälpare: vad som hände på vägen, och om
        något avgjordes med en hårsmån (edge) - då kan Java svara annorlunda och fallet dras om."""
        room, design, balance = kw["room"], kw["design_outdoor"], kw["balance"]
        gap, curvature, limit = kw.get("max_green_gap"), kw["curvature"], kw["bend_limit"]
        usable = [b for b in bins if b.weight > 0.0]
        greens = [b for b in usable if b.status == pts.GREEN]
        chain = greens if gap is None else cf.green_chain(usable, gap)
        if gap is not None:
            usable = [b for b in usable if b.status != pts.GREEN or any(b is x for x in chain)]
        coldest = min((b.outdoor for b in chain), default=None)
        reach = 0.0
        if coldest is not None and balance > design:
            reach = min(1.0, max(0.0, (balance - coldest) / (balance - design)))
        shape = cf._Shape(room, design, balance)
        prior = cf._Prior(shape, kw["slope_prior"], kw.get("prior_weight", 0.25), reach)
        edge = False

        def solved(items, bend):
            """Lutningen som _Prior.solve ger; en klämning på gränsen är en hårsmån (vid räckvidd 0
            är low == high och klämningen alltid säker)."""
            nonlocal edge
            low, high = prior.low, prior.high
            prior.low, prior.high = -math.inf, math.inf
            raw = prior.solve(items, bend)
            prior.low, prior.high = low, high
            edge = edge or (reach > 0.0 and min(abs(raw - low), abs(raw - high)) < 1e-9)
            return prior.solve(items, bend)

        slope = solved(usable, curvature)
        misses = [abs(b.need - shape.excess(b.outdoor, slope, curvature)) for b in usable]
        edge = edge or any(abs(miss - cf.OUTLIER) < 1e-6 for miss in misses)
        outliers = [b for b, miss in zip(usable, misses) if miss > cf.OUTLIER]
        if outliers:
            usable = [b for b in usable if b not in outliers]
            slope = solved(usable, curvature)
        bent, held = curvature, False
        searched = limit > 0.0 and coldest is not None and coldest <= cf.BEND_COLD
        if searched:
            costs = {curvature: prior.cost(usable, slope, curvature)}  # referensen; på rutnätet samma kostnad
            for bend in cf.BEND_GRID:
                moved = abs(c.middle_deviation(room, design, solved(usable, bend), balance, bend, curvature))
                edge = edge or abs(moved - limit) < 1e-9
                if moved <= limit:
                    cost = prior.cost(usable, prior.solve(usable, bend), bend) + cf.BEND_PRIOR * (bend - curvature) ** 2
                    costs.setdefault(bend, cost)
            ordered = sorted(costs.values())
            edge = edge or (len(ordered) > 1 and ordered[1] - ordered[0] <= 1e-7 * max(1.0, ordered[1]))
            bent = cf._bend(usable, shape, prior, curvature, limit)[0]
            held = cf._bend(usable, shape, prior, curvature, math.inf)[0] != bent
        return dict(reach=reach, greens=len(greens), chain=len(chain), used=len(usable), bent=bent,
                    searched=searched, held=held, edge=edge)

    rows, rerolled = [], 0
    while len(rows) < 400:
        kw, kind = inputs(), rng.choice(["table"] * 27 + ["direct"] * 9 + ["greenless"] * 3 + ["empty"])
        need = truth(kw)
        bins = [] if kind == "empty" else table_bins(kw, need) if kind == "table" \
            else direct_bins(kw, need, kind == "greenless")
        coldout = bool(bins) and rng.random() < 0.3 and knocked(bins)
        seen = inside(bins, kw)
        if seen["edge"] or len(set(bins)) < len(bins):
            rerolled += 1
            continue
        result = cf.fit_curve(bins, **kw)
        assert (result.curvature, result.used) == (seen["bent"], seen["used"])
        chain_gap = rng.choice([0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0])
        linked = cf.green_chain(bins, chain_gap)
        gap, green_bins = kw.get("max_green_gap"), [b for b in bins if b.status == pts.GREEN]
        hits = dict(bent=seen["searched"], moved=result.curvature != kw["curvature"], held=seen["held"],
                    rejected=bool(result.rejected), coldout=coldout, reach0=seen["reach"] == 0.0,
                    under=kw["balance"] <= kw["design_outdoor"], reach1=seen["reach"] == 1.0,
                    shorter=gap is not None and seen["chain"] < seen["greens"], unsteerable=not result.steerable,
                    empty=not bins, gap57=gap in (5.0, 7.0),
                    same=len({pts.bin_index(b.outdoor) for b in green_bins}) < len(green_bins))
        for key, hit in hits.items():
            counts[key] += hit
        rows.append([_bins(bins), kw["room"], kw["design_outdoor"], kw["balance"], kw["slope_prior"], kw["curvature"],
                     kw["bend_limit"], kw.get("prior_weight", 0.25), gap,
                     result.slope, result.curvature, result.coldest_green, result.used, _outdoors(result.rejected),
                     _outdoors(result.chain), _bool(result.steerable), chain_gap,
                     _outdoors(b.outdoor for b in linked)])
    write("09-kurvanpassningen", "curve_fit.csv",
          ["bins", "room", "designOutdoor", "balance", "slopePrior", "curvature", "bendLimit", "priorWeight",
           "maxGreenGap", "slope", "fitCurvature", "coldestGreen", "used", "rejected", "chain", "steerable",
           "chainGap", "greenChain"], rows)
    print("09-kurvanpassningen: rader med " + ", ".join(f"{found[key]} {counts[key]}" for key in found)
          + f"; {rerolled} fall omdragna (hårsmån)")


def _typed(value) -> str:
    """Ett sparat värde med sin typ: s text, f flyttal, i heltal, b bool, n None (tomt efter kolon)."""
    if value is None:
        return "n:"
    if isinstance(value, bool):
        return f"b:{value}"
    if isinstance(value, int):
        return f"i:{value}"
    if isinstance(value, float):
        return f"f:{value!r}"
    return f"s:{value}"


def _stored(stored: dict | None) -> str | None:
    """Det sparade som "nyckel=typ:värde" med | emellan i insättningsordning; tomt för None, {} för en tom dict."""
    if stored is None:
        return None
    return "|".join(f"{key}={_typed(value)}" for key, value in stored.items()) or "{}"


def burner(core, rng) -> None:
    b = core.burner
    numeric = ("start_lead", "off_delay", "burnout_seconds", "sweep_lead_seconds", "alarm_below")
    unknown, garbage = ("unknown", "foo", "sweep_lead"), ("inte ett tal", "1,5", "3600s", "tre")
    found = {"odd": "okänd brännare", "noburner": "brännare None", "nostored": "stored None", "empty": "tom dict",
             "path": "control_path", "unknownkey": "okänd nyckel", "garbage": "skräptext", "nothing": "None-värde",
             "truth": "bool-värde", "callnum": "call_for_heat som tal"}
    counts = {key: 0 for key in found}

    def number():
        """Ett sparat tal som det kan se ut: flyttal, heltal, siffror som text, skräp, None eller bool."""
        r = rng.random()
        if r < 0.30:
            return round(rng.uniform(0.0, 8000.0), rng.choice([0, 1, 2]))
        if r < 0.50:
            return rng.randint(0, 8000)
        if r < 0.70:
            return rng.choice(["3600", "1.5e3", "-2", "+5", "0.5", "900", "60.0", "2e2"])
        if r < 0.85:
            return rng.choice(garbage)
        if r < 0.93:
            return None
        return rng.choice([True, False])

    def value(key):
        if key == "call_for_heat":
            return rng.choice(["on", "off", "ON", "Off", "yes"] * 3 + [1, 0, 1.0, 0.5])
        if key == "control_path":
            return rng.choice(["control", "power", "both", "none"])
        return number()

    def stored():
        """Inget sparat, en tom dict, eller 1-5 av de kända nycklarna, ibland med en okänd bland dem."""
        r = rng.random()
        if r < 0.09:
            return None
        if r < 0.14:
            return {}
        keys = rng.sample(numeric + ("call_for_heat", "control_path"), rng.randint(1, 5))
        if rng.random() < 0.25:
            keys.insert(rng.randrange(len(keys) + 1), rng.choice(unknown))
        return {key: value(key) for key in keys}

    def which():
        """Brännaren: oftast en av de två, ibland en okänd text eller None."""
        r = rng.random()
        if r < 0.42:
            return b.BURNER_PELLETS
        if r < 0.80:
            return b.BURNER_OIL
        if r < 0.94:
            return rng.choice(["ved", "Pellets", "OIL", "olja", "gas"])
        return None

    rows = []
    for _ in range(150):
        name, kept = which(), stored()
        out, items = b.burner_settings(name, kept), list((kept or {}).items())
        hits = dict(odd=name is not None and name not in b.BURNERS, noburner=name is None, nostored=kept is None,
                    empty=kept == {}, path=any(k == "control_path" for k, _ in items),
                    unknownkey=any(k in unknown for k, _ in items),
                    garbage=any(k in numeric and v in garbage for k, v in items),
                    nothing=any(k in numeric and v is None for k, v in items),
                    truth=any(k in numeric and isinstance(v, bool) for k, v in items),
                    callnum=any(k == "call_for_heat" and not isinstance(v, str) for k, v in items))
        for key, hit in hits.items():
            counts[key] += hit
        rows.append([name, _stored(kept), out["start_lead"], out["off_delay"], out["burnout_seconds"],
                     out["sweep_lead_seconds"], out["call_for_heat"], out["alarm_below"], out.get("control_path")])
    write("10-brannaren", "burner_settings.csv",
          ["burner", "stored", "startLead", "offDelay", "burnoutSeconds", "sweepLeadSeconds", "callForHeat",
           "alarmBelow", "controlPath"], rows)
    print("10-brannaren: rader med " + ", ".join(f"{found[key]} {counts[key]}" for key in found))

    paths = (b.PATH_CONTROL, b.PATH_POWER, b.PATH_BOTH, "CONTROL", "none")
    rows = [[path, _bool(hc), _bool(hp), _bool(sw), _bool(oh), *map(_bool, b.relays_in_use(path, hc, hp, sw, oh))]
            for path in paths for hc in (True, False) for hp in (True, False) for sw in (True, False)
            for oh in (True, False)]
    write("10-brannaren", "relays_in_use.csv",
          ["controlPath", "hasControl", "hasPower", "sweeping", "overheated", "control", "power"], rows)

    texts = ("on", "off", "ON", "On", "oN", "OFF", "yes", "true", "1", "sluten", "on_", "0")
    rows = [[text, _bool(call), _bool(b.relay_calls_for_heat(text, call))] for text in texts for call in (True, False)]
    write("10-brannaren", "relay_calls.csv", ["callForHeat", "call", "calls"], rows)

    branches = {"wanted": "wanted", "atonce": "at_once", "nocontrol": "utan styrkabel", "nopower": "utan huvudström",
                "cut": "utbrunnen (>=)", "burnout": "burnout", "exact": "stopped = burnout",
                "exactcut": "varav fram till >="}
    counts = {key: 0 for key in branches}
    rows = []
    for _ in range(160):
        wanted, at_once = rng.random() < 0.22, rng.random() < 0.18
        has_control, has_power = rng.random() < 0.75, rng.random() < 0.68
        burnout = rng.choice([0.0, 60.0, 900.0, 1800.0, round(rng.uniform(1.0, 5000.0), 1)])
        stopped = rng.choice([0.0, -round(rng.uniform(0.5, 900.0), 1), round(rng.uniform(0.0, 5000.0), 1),
                              burnout, burnout, burnout - 0.5, burnout + 0.5])
        plan = b.plan_heat_source(wanted=wanted, seconds_stopped=stopped, has_control=has_control,
                                  has_power=has_power, burnout_seconds=burnout, at_once=at_once)
        branch = ("wanted" if wanted else "atonce" if at_once else "nocontrol" if not has_control
                  else "nopower" if not has_power else "cut" if stopped >= burnout else "burnout")
        counts[branch] += 1
        counts["exact"] += stopped == burnout
        counts["exactcut"] += stopped == burnout and branch == "cut"
        rows.append([_bool(wanted), stopped, _bool(has_control), _bool(has_power), burnout, _bool(at_once),
                     _bool(plan.control), _bool(plan.power), _bool(plan.burning_out)])
    write("10-brannaren", "plan_heat_source.csv",
          ["wanted", "secondsStopped", "hasControl", "hasPower", "burnoutSeconds", "atOnce", "control", "power",
           "burningOut"], rows)
    print("10-brannaren: grenar " + ", ".join(f"{branches[key]} {counts[key]}" for key in branches))


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
    estimators(core, rng)
    thermal(core, rng)
    forecast(core, rng)
    curvefit(core, rng)
    burner(core, rng)
    for step, module in (("01-tider", "durations"), ("02-pid", "pid"), ("03-kurvan", "curve"),
                         ("04-ventilkartan", "valvemap"), ("05-kurvtabellen", "points"),
                         ("06-skattarna", "estimators"), ("07-husmodellen", "thermal"),
                         ("08-prognosen", "forecast"), ("09-kurvanpassningen", "curvefit"),
                         ("10-brannaren", "burner")):
        copy_source(heating, step, module)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
