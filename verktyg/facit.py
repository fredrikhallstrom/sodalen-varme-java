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


def _typed2(value) -> str:
    """Steg 10:s typer (s f i b n) och tre till för det skattarna sparar: l en lista av tal med ,
    emellan (tomt = None, nan/inf som Python skriver dem), m en matris med raderna skilda av / och
    talen i en rad av ,, d en nästlad dict en nivå ned med & mellan posterna och ~ i stället för =
    (d:{} = tom). Texter får inte innehålla något av tecknen ; | = & ~ , /."""
    if isinstance(value, dict):
        assert not any(isinstance(v, dict) for v in value.values()), "en nivå räcker"
        return "d:" + ("&".join(f"{key}~{_typed2(v)}" for key, v in value.items()) or "{}")
    if isinstance(value, (list, tuple)):
        if any(isinstance(v, (list, tuple)) for v in value):
            return "m:" + "/".join(_floats(row) for row in value)
        return "l:" + _floats(value)
    return _typed(value)


def _stored2(stored: dict | None) -> str | None:
    """Som _stored, med _typed2:s typer: tomt för None, {} för en tom dict."""
    if stored is None:
        return None
    return "|".join(f"{key}={_typed2(value)}" for key, value in stored.items()) or "{}"


def _same(a, b) -> bool:
    """Pythons ==, fast med nan lika med nan."""
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(_same(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_same(x, y) for x, y in zip(a, b))
    if isinstance(a, float) and isinstance(b, float) and math.isnan(a) and math.isnan(b):
        return True
    return a == b


def model(core, rng) -> None:
    m, p, st, est = core.model, core.parsing, core.sunterm, core.estimators
    Tuning, LearnedState, SunTerm = m.Tuning, m.LearnedState, st.SunTerm
    step = "11-modellen"
    # Vad en sparad fil kan innehålla i stället för ett tal: det as_float och bool() får svälja.
    flat_junk = [None, "abc", "", " 2 ", "21.5", "1e3", True, False, math.nan, math.inf, [], [1.0, 2.0]]
    junk = flat_junk + [{}, {"a": 1.0}]

    def spoil(value, broken, deep=False):
        """Värdet, eller med sannolikheten broken något annat; deep = inne i en nästlad dict,
        där en dict till inte går att skriva."""
        if rng.random() >= broken:
            return value
        return rng.choice(flat_junk if deep else junk)

    # --- parsing.csv -------------------------------------------------------------------------------
    texts = ["21.5", " 2 ", "1e3", "abc", "", "nan", "inf", "-inf", "-999", "NaN", "Infinity", "-Infinity",
             "1e400", "-1e400", "+5", "2.", ".5", "1,5", "21.5 C", "true", "False", " ", "1e", "-50", "110",
             "110.5", "125", "125.5", "115.75", "1.5e3", "-2.5E-3"]
    numbers = [0, 1, -1, 21, -273, -999, 110, 125, 0.0, 21.5, -273.0, -273.15, -50.0, -50.001, -49.999, 110.0,
               110.001, 110.5, 115.75, 125.0, 125.001, 125.5, 1e-9, -1e-9, 1e300, -1e300, math.nan, math.inf,
               -math.inf]
    values = [None, True, False, [], [1.0, 2.0], {}, {"a": 1.0}] + numbers + texts
    while len(values) < 120:
        r = rng.random()
        if r < 0.4:
            values.append(round(rng.uniform(-300.0, 300.0), rng.choice([0, 1, 2, 3])))
        elif r < 0.6:
            values.append(rng.randint(-300, 300))
        elif r < 0.9:
            values.append(rng.choice([str(round(rng.uniform(-60.0, 130.0), rng.choice([0, 1, 2]))),
                                      str(rng.randint(-60, 130)), f"{rng.uniform(-60.0, 130.0):.3e}"]))
        else:
            values.append(rng.choice([None, True, False, math.nan]))
    rows = []
    for value in values:
        fallback = rng.choice([0.0, 0.0, -1.0, 7.5, 99.0])
        rows.append([_typed2(value), fallback, p.as_float(value, fallback), p.as_optional_float(value),
                     p.plausible_temperature(value), p.plausible_temperature(value, p.WIRED_MAX_C)])
    write(step, "parsing.csv",
          ["value", "fallback", "as_float", "as_optional_float", "plausible_default", "plausible_125"], rows)

    # --- outdoor_in_use.csv ------------------------------------------------------------------------
    names = ["ute_nord", "ute_syd", "ute_vast", "ute_ost", "garage", "brygga"]
    cases = [[], [("ute_nord", None)], [("ute_nord", 5.0)], [("ute_nord", 1.0), ("ute_syd", 1.0)],
             [("ute_nord", None), ("ute_syd", 1.0), ("ute_vast", 1.0)],
             [("ute_nord", 2.0), ("ute_syd", -3.5), ("ute_vast", None)],
             [("ute_nord", -20.0), ("ute_syd", -20.0000001)], [("ute_nord", None), ("ute_syd", None)],
             [("ute_nord", 10.0), ("ute_syd", 9.9999), ("ute_vast", 9.9999)], [("ute_nord", -50.0), ("ute_syd", 110.0)],
             [("ute_nord", 3.0), ("ute_syd", None), ("ute_vast", 3.0), ("ute_ost", 2.0)]]
    while len(cases) < 40:
        readings, seen = [], []
        for key in rng.sample(names, rng.randint(1, 5)):
            r = rng.random()
            if r < 0.25:
                value = None
            elif r < 0.4 and seen:
                value = rng.choice(seen)
            else:
                value = round(rng.uniform(-30.0, 30.0), rng.choice([0, 1, 2]))
            if value is not None:
                seen.append(value)
            readings.append((key, value))
        cases.append(readings)
    write(step, "outdoor_in_use.csv", ["readings", "index"],
          [[_stored2(dict(readings)), p.outdoor_in_use(readings)] for readings in cases])

    # --- estimator_dicts.csv -----------------------------------------------------------------------
    kinds = ("ewma", "dutywatch", "regression", "multi")

    def built(kind):
        """Ett objekt byggt med steg 6:s operationer: (objektet, sekunder/horisont, size, ops)."""
        ops = []
        if kind == "ewma":
            seconds = rng.choice([600.0, 3600.0, 10800.0, 0.0, round(rng.uniform(10.0, 5000.0), 1)])
            thing = est.Ewma(seconds)
            for _ in range(rng.randint(0, 8)):
                sample = None if rng.random() < 0.1 else round(rng.uniform(-20.0, 80.0), 2)
                dt = rng.choice([30.0, 30.0, 0.0, round(rng.uniform(1.0, 3000.0), 1)])
                thing.update(sample, dt)
                ops.append("update," + _list([sample, dt]))
            return thing, seconds, None, ops
        if kind == "dutywatch":
            seconds = rng.choice([3600.0, 259200.0, 0.0, round(rng.uniform(60.0, 86400.0), 1)])
            thing = est.DutyWatch(seconds)
            for _ in range(rng.randint(0, 12)):
                held = rng.choice([True, False, None, True])
                dt = rng.choice([30.0, 30.0, 0.0, round(rng.uniform(1.0, 7200.0), 1)])
                thing.update(held, dt)
                ops.append(_bool(held) + "," + _num(dt))
            return thing, seconds, None, ops
        if kind == "regression":
            horizon = rng.choice([3600.0, 259200.0, 43200.0, 0.0, round(rng.uniform(60.0, 1e6), 1)])
            thing = est.Regression(horizon)
            for _ in range(rng.randint(0, 15)):
                dt = rng.choice([30.0, 60.0, 0.0, round(rng.uniform(1.0, 3600.0), 1)])
                if rng.random() < 0.15:
                    thing.fade(dt)
                    ops.append("fade," + _num(dt))
                else:
                    x, y = round(rng.uniform(-1.0, 2.0), 3), round(rng.uniform(-5.0, 5.0), 3)
                    thing.add(x, y, dt)
                    ops.append("add," + _list([x, y, dt]))
            return thing, horizon, None, ops
        size = rng.randint(2, 4)
        horizon = rng.choice([0.0, 1814400.0, 86400.0, round(rng.uniform(5.0, 100.0), 1)])
        thing = est.MultiRegression(size=size, horizon=horizon)
        truth = [round(rng.uniform(-3.0, 3.0), 2) for _ in range(size)]
        for _ in range(rng.randint(0, 12)):
            x = [1.0] + [round(rng.uniform(-2.0, 2.0), 3) for _ in range(size - 1)]
            y = round(sum(t * xi for t, xi in zip(truth, x)) + rng.uniform(-0.5, 0.5), 3)
            dt = rng.choice([1.0, 1.0, 0.0, round(rng.uniform(0.1, 5.0), 2)])
            thing.add(x, y, dt)
            ops.append("add," + _list([y, dt] + x))
        return thing, horizon, size, ops

    def restored(kind, data, seconds, size):
        if kind == "ewma":
            return est.Ewma.from_dict(data, seconds)
        if kind == "dutywatch":
            return est.DutyWatch.from_dict(data, seconds)
        if kind == "regression":
            return est.Regression.from_dict(data, seconds)
        return est.MultiRegression.from_dict(data, size, seconds)

    def junk_dict(kind, size):
        """Något en äldre version sparade eller en fil som skadats: nycklar som saknas, None, text,
        fel typ, och för MultiRegression fel size och fel form på xx/xy."""
        if kind == "ewma":
            d = {"seconds": spoil(rng.choice([600.0, 7.0]), 0.3),
                 "value": spoil(round(rng.uniform(-20.0, 80.0), 2), 0.5)}
        elif kind == "dutywatch":
            d = {"seconds": spoil(3600.0, 0.3), "share": spoil(round(rng.random(), 3), 0.5),
                 "weight": spoil(round(rng.uniform(-10.0, 1e5), 1), 0.5)}
        elif kind == "regression":
            d = {"horizon": spoil(3600.0, 0.3)}
            for key in ("weight", "sum_x", "sum_y", "sum_xx", "sum_xy"):
                d[key] = spoil(round(rng.uniform(-5.0, 50.0), 3), 0.35)
        else:
            xx = [[round(rng.uniform(-5.0, 5.0), 2) for _ in range(size)] for _ in range(size)]
            xy = [round(rng.uniform(-5.0, 5.0), 2) for _ in range(size)]
            r = rng.random()
            if r < 0.1:
                xx = rng.choice(junk)
            elif r < 0.2:
                xx = xx[:-1]
            elif r < 0.3:
                xx[0] = xx[0][:-1]
            elif r < 0.4:
                xx[0][0] = None
            r = rng.random()
            if r < 0.1:
                xy = rng.choice(junk)
            elif r < 0.2:
                xy = xy + [1.0]
            elif r < 0.25:
                xy[0] = None
            d = {"size": rng.choice([size] * 4 + [size + 1, size - 1, float(size), str(size), f"{size}.0", None,
                                                  True, [float(size)]]),
                 "horizon": spoil(3600.0, 0.3), "weight": spoil(round(rng.uniform(0.0, 50.0), 3), 0.3),
                 "xx": xx, "xy": xy, "residual": spoil(round(rng.uniform(0.0, 5.0), 3), 0.3),
                 "residual_data": spoil(round(rng.uniform(0.0, 5.0), 3), 0.3),
                 "yy": spoil(round(rng.uniform(0.0, 50.0), 3), 0.3)}
        for key in list(d):
            if rng.random() < 0.15:
                del d[key]
        if rng.random() < 0.1:
            d["foo"] = 1
        return d

    rows = []
    for kind in kinds:
        for _ in range(15):
            thing, seconds, size, ops = built(kind)
            d = thing.as_dict()
            back = restored(kind, d, seconds, size).as_dict()
            rows.append([kind, seconds, size, "|".join(ops), _stored2(d), _bool(_same(back, d)), _stored2(d),
                         _stored2(back)])
    good = est.MultiRegression(size=3, horizon=0.0)
    for x, y in (([1.0, 0.5, -1.0], 2.0), ([1.0, -0.5, 0.25], 0.5), ([1.0, 1.5, 1.0], 3.5), ([1.0, 0.0, 2.0], 1.0)):
        good.add(x, y, 1.0)
    good = good.as_dict()
    # size som text eller flyttal tas (int("3"), int(3.0)); "3.0" och True gör inte det. yy saknat är
    # None; residual_data saknat är residual, också när residual är skräp.
    fixed_multi = [dict(good, size="3"), dict(good, size=3.0), dict(good, size="3.0"), dict(good, size=True),
                   {k: v for k, v in good.items() if k != "yy"},
                   {k: v for k, v in good.items() if k != "residual_data"},
                   dict({k: v for k, v in good.items() if k != "residual_data"}, residual="abc")]
    for kind in kinds:
        fixed = [None, {}] + (fixed_multi if kind == "multi" else [])
        for i in range(len(fixed) + 11):
            size = (3 if i < len(fixed) else rng.randint(2, 4)) if kind == "multi" else None
            seconds = rng.choice([600.0, 3600.0, 259200.0, 0.0])
            d = fixed[i] if i < len(fixed) else junk_dict(kind, size)
            back = restored(kind, d, seconds, size).as_dict()
            rows.append([kind, seconds, size, None, None, None, _stored2(d), _stored2(back)])
    write(step, "estimator_dicts.csv",
          ["kind", "seconds", "size", "ops", "as_dict", "roundtrip_equal", "stored", "restored_as_dict"], rows)

    # --- sun_term.csv ------------------------------------------------------------------------------
    HOUR = st.HOUR_SECONDS

    def sun_series(hours_total, pieces, broken, dark, noise, skies=None):
        """Observationer (behov, ljus, fel, dt) genom ett väder som test_sunterm.py:s, timme för
        timme eller i delar av timmar, ibland med skräp; avrundade först så att det som skrivs är
        det Python såg."""
        coef = (round(rng.uniform(-0.5, 0.5), 2), round(rng.uniform(0.0, 0.6), 2),
                round(rng.uniform(-8.0, -3.0), 1), round(rng.uniform(0.0, 6.0), 1))
        swing, period = rng.uniform(0.0, 0.4), rng.uniform(20.0, 60.0)
        if skies is None:
            skies = [rng.choice([0.0, rng.uniform(0.0, 1.0), rng.uniform(0.0, 1.0)]) for _ in range(rng.randint(1, 7))]
        obs, hour = [], 0.0
        while hour < hours_total and len(obs) < 400:
            parts = rng.choice(pieces)
            for part in range(parts):
                if len(obs) >= 400:
                    break
                at = hour + part / parts
                demand = 0.45 + swing * math.sin(2.0 * math.pi * at / period)
                clock = at % 24.0
                sky = 0.0 if dark else skies[int(at // 24) % len(skies)]
                light = 0.4 * sky * math.sin(math.pi * (clock - 7.0) / 10.0) if 7.0 < clock < 17.0 else 0.0
                error = coef[0] + coef[1] * demand + (coef[2] + coef[3] * demand) * light + rng.gauss(0.0, noise)
                dt = HOUR / parts
                if rng.random() < 0.04:
                    dt = rng.choice([2000.0, 1000.0, 30.0])
                sample = [round(demand, 3), round(light, 3), round(error, 3), dt]
                if rng.random() < broken:
                    index = rng.randrange(4)
                    sample[index] = rng.choice([None, math.nan, math.inf, -math.inf]
                                               + ([0.0, -30.0] if index == 3 else [-0.05] if index == 1 else []))
                obs.append(tuple(sample))
            hour += 1.0
        return obs

    def observed(obs):
        term = SunTerm()
        for demand, light, error, dt in obs:
            term.observe(demand, light, error, dt)
        return term

    def _observations(obs):
        """"behov,ljus,fel,dt" med | emellan; None som n, nan och inf som Python skriver dem."""
        return "|".join(",".join("n" if v is None else repr(float(v)) for v in o) for o in obs)

    def perturbed(d):
        """as_dict med ett fel i, som en fil från en annan version."""
        d = {"fit": dict(d["fit"]), "hour": list(d["hour"]), "hours": d["hours"], "sunny_hours": d["sunny_hours"]}
        what = rng.choice(["short_hour", "none_in_hour", "hour_text", "hour_nan", "hours_text", "sunny_float",
                           "fit_empty", "fit_size", "fit_ragged", "no_hours", "hours_none", "fit_none"])
        if what == "short_hour":
            d["hour"] = d["hour"][:3]
        elif what == "none_in_hour":
            d["hour"][1] = None
        elif what == "hour_text":
            d["hour"] = "abc"
        elif what == "hour_nan":
            d["hour"][2] = math.nan
        elif what == "hours_text":
            d["hours"] = str(d["hours"])
        elif what == "sunny_float":
            d["sunny_hours"] = d["sunny_hours"] + 0.9
        elif what == "fit_empty":
            d["fit"] = {}
        elif what == "fit_size":
            d["fit"]["size"] = 3
        elif what == "fit_ragged":
            d["fit"]["xx"] = [list(row) for row in d["fit"]["xx"]][:-1]
        elif what == "no_hours":
            del d["hours"]
        elif what == "hours_none":
            d["hours"] = None
        else:
            d["fit"] = None
        return d

    demands = (0.3, 0.45, 0.6, 0.95)
    plans = [("short", 5), ("edge", 6), ("long", 8), ("split", 6), ("dark", 3), ("broken", 5)]
    rows, good_fit, ready = [], None, 0

    def sun_row(obs, stored, horizon):
        term = observed(obs)
        d = term.as_dict()
        answers = []
        for demand in demands:
            answers += [term.per_kw(demand), term.spread(demand), term.share(demand),
                        _bool(term.known(demand, 0.05, 0.1)), term.correction(demand, 0.2)]
        roundtrip = _same(SunTerm.from_dict(d, st.HORIZON_SECONDS).as_dict(), d)
        stored = d if stored == "same" else perturbed(d) if stored == "perturbed" else stored
        back = SunTerm.from_dict(stored, horizon).as_dict()
        return term, [_observations(obs), term.hours, term.sunny_hours] + answers \
            + [_stored2(d), _bool(roundtrip), horizon, _stored2(stored), _stored2(back)]

    for kind, count in plans:
        for i in range(count):
            if kind == "short":
                obs = sun_series(rng.randint(1, 10), [1], 0.1, False, 0.05)
            elif kind == "edge":
                obs = sun_series(rng.choice([47, 48, 49, 50, 72]), [1], 0.0, False, 0.05,
                                 skies=rng.choice([[0.06], [0.0, 0.07], [0.05, 0.0, 0.0], [0.3], [0.0, 0.0, 0.055]]))
            elif kind == "long":
                obs = sun_series(rng.randint(100, 400), [1], 0.0, False, rng.choice([0.0, 0.05, 0.2]))
            elif kind == "split":
                obs = sun_series(400, [1, 1, 2, 3, 4], 0.0, False, 0.05)
            elif kind == "dark":
                obs = sun_series(rng.randint(50, 200), [1], 0.0, True, 0.05)
            else:
                obs = sun_series(rng.randint(60, 300), [1, 1, 2], 0.12, False, 0.05)
            horizon = rng.choice([st.HORIZON_SECONDS] * 3 + [86400.0, 0.0])
            term, row = sun_row(obs, "perturbed" if i % 3 == 2 else "same", horizon)
            ready += term.per_kw(0.5) is not None
            if term.fit.weight > 0.0 and good_fit is None:
                good_fit = term.fit.as_dict()
            rows.append(row)
    for stored in (None, {}, {"hour": "abc"}, {"fit": "abc", "hours": 5},
                   {"fit": good_fit, "hours": "12", "sunny_hours": 7.9},
                   {"fit": good_fit, "hours": -2.5, "sunny_hours": None},
                   {"hour": [1.0, 2.0, 3.0, 4.0], "hours": 3}, {"hour": [1.0, 2.0, 3.0, math.inf], "fit": good_fit}):
        rows.append(sun_row([], stored, rng.choice([st.HORIZON_SECONDS, 86400.0]))[1])
    head = ["observations", "hours", "sunny_hours"]
    for demand in demands:
        head += [f"{name}_{demand}" for name in ("per_kw", "spread", "share", "known", "correction")]
    write(step, "sun_term.csv", head + ["as_dict", "roundtrip_equal", "restore_horizon", "stored", "restored_as_dict"],
          rows)
    print(f"11-modellen: sun_term med svar i {ready} rader")

    # --- tuning_from_dict.csv ----------------------------------------------------------------------
    field_names = [f.name for f in dataclasses.fields(Tuning)]
    bool_names = [f.name for f in dataclasses.fields(Tuning) if isinstance(f.default, bool)]
    default = Tuning()
    unknown = ["unknown", "roomTarget", "room-target", "design_span", "clamp_flow", "Room_Target",
               "gone_in_a_later_version", "ROOM_TARGET"]
    numbers_as_text = ["21.5", " 2 ", "1e3", "-0.5", "+7", "2.", ".5", "1.5e3"]
    garbage = ["abc", "", "1,5", "true", "False", "21.5 C", "x"]

    def float_value():
        r = rng.random()
        if r < 0.35:
            return round(rng.uniform(-50.0, 100000.0), rng.choice([0, 1, 2, 3]))
        if r < 0.45:
            return rng.randint(-50, 100000)
        if r < 0.6:
            return rng.choice(numbers_as_text)
        if r < 0.7:
            return rng.choice(garbage)
        if r < 0.78:
            return None
        if r < 0.86:
            return rng.choice([True, False])
        if r < 0.94:
            return rng.choice([[], [1.0], {}, {"a": 1.0}])
        return rng.choice([math.nan, math.inf, -math.inf])

    def bool_value():
        return rng.choice([True, False, True, False, "false", "true", "False", "0", "1", "", 0, 1, 0.0, 2.5, -1,
                           None, [], [1.0], {}, {"a": 1.0}, math.nan])

    def differs(tuning):
        return {name: getattr(tuning, name) for name in field_names
                if not _same(getattr(tuning, name), getattr(default, name))}

    def tuning_row(data, base):
        base_tuning = None if base is None else m.tuning_from_dict(base)
        result = m.tuning_from_dict(data, base_tuning)
        return [_stored2(base), _stored2(data), _stored2(differs(result))]

    rows = [tuning_row({}, None), tuning_row(None, None),
            tuning_row({"curve_slope": 1.1, "gone_in_a_later_version": 3, "flow_max": "x"}, None),
            tuning_row({key: 1 for key in unknown}, None),
            tuning_row({name: "false" for name in bool_names}, None),
            tuning_row({name: 0 for name in bool_names}, None)]
    while len(rows) < 120:
        keys = rng.sample(field_names, rng.choice([0, 1, 2, 3, 4, 5, 6, 8]))
        if rng.random() < 0.4:
            for key in rng.sample(unknown, rng.randint(1, 2)):
                keys.insert(rng.randint(0, len(keys)), key)
        data = {key: bool_value() if key in bool_names else float_value() for key in keys}
        base = None
        if rng.random() < 0.4:
            base = {key: rng.choice([True, False]) if key in bool_names else round(rng.uniform(-10.0, 1000.0), 2)
                    for key in rng.sample(field_names, rng.randint(1, 4))}
        rows.append(tuning_row(data, base))
    write(step, "tuning_from_dict.csv", ["base", "data", "result"], rows)

    # --- learned_state.csv -------------------------------------------------------------------------
    keys = list(LearnedState().as_dict())
    optional = ("bend", "last_flow_setpoint", "last_flow", "position", "resume_position", "last_feedforward", "last_kp")
    flags = ("warm_stopped", "summer_stopped", "coasting", "recovering", "boiler_overheated", "needs_reseed")
    filters = ("delta_scale", "delta_filter", "outdoor", "outdoor_summer", "indoor", "indoor_slow", "sun_heat")
    fits = ("curve_fit", "level_fit", "gain_fit")
    seconds_choices = [600.0, 3600.0, 43200.0, 86400.0, 259200.0, 1814400.0]
    overridable = {"gain_reference": lambda: round(rng.uniform(0.1, 1.0), 2),
                   "room_target": lambda: rng.choice([21.0, 20.0, 22.0, 23.5]),
                   "design_outdoor": lambda: rng.choice([-18.0, -15.0, -22.0]),
                   "curve_slope": lambda: round(rng.uniform(0.3, 2.2), 2),
                   "curve_curvature": lambda: rng.choice([0.8, 0.6, 1.0, 1.1]),
                   "slope_min": lambda: rng.choice([0.2, 0.5, 1.0]), "slope_max": lambda: rng.choice([2.0, 1.2, 1.0]),
                   "balance_shift_min": lambda: rng.choice([-14.0, -5.0, -2.0, 0.0]),
                   "balance_shift_max": lambda: rng.choice([3.0, 0.0, 1.0, 10.0])}
    for name in ("delta_expected_horizon_seconds", "delta_filter_seconds", "learn_horizon_seconds",
                 "sun_term_horizon_seconds", "outdoor_filter_seconds", "learn_level_horizon_seconds",
                 "gain_horizon_seconds", "starved_horizon_seconds", "summer_filter_seconds", "sun_heat_seconds",
                 "indoor_filter_seconds", "indoor_slow_filter_seconds"):
        overridable[name] = lambda: rng.choice(seconds_choices + [round(rng.uniform(60.0, 1e6), 1)])

    def overrides():
        if rng.random() < 0.5:
            return None
        return {name: overridable[name]() for name in rng.sample(sorted(overridable), rng.randint(1, 4))}

    def ewma_stored(broken):
        return spoil({"seconds": spoil(rng.choice([600.0, 3600.0, 7.0]), broken, True),
                      "value": spoil(rng.choice([None, round(rng.uniform(-20.0, 60.0), 2)]), broken, True)}, broken / 2)

    def duty_stored(broken):
        return spoil({"seconds": 259200.0, "share": spoil(rng.choice([None, round(rng.random(), 3)]), broken, True),
                      "weight": spoil(round(rng.uniform(0.0, 1e5), 1), broken, True)}, broken / 2)

    def regression_stored(broken, adds=None):
        fit = est.Regression(259200.0)
        for _ in range(rng.randint(0, 5) if adds is None else adds):
            fit.add(round(rng.uniform(-0.3, 1.3), 4), round(rng.uniform(-3.0, 3.0), 2), 30.0)
        return spoil({key: spoil(value, broken, True) for key, value in fit.as_dict().items()}, broken / 2)

    def sun_stored(broken):
        d = {"fit": rng.choice([None, "abc", []]),
             "hour": spoil([round(rng.uniform(0.0, 3600.0), 1) for _ in range(4)], broken, True),
             "hours": spoil(rng.randint(0, 300), broken, True), "sunny_hours": spoil(rng.randint(0, 50), broken, True)}
        if rng.random() < 0.5:
            del d["fit"]
        return spoil(d, broken / 2)

    def modern(broken):
        """En fil som as_dict skriver den, med sannolikheten broken för varje värde att vara skräp
        och för varje nyckel att saknas."""
        d = {}
        for key in keys:
            if rng.random() < broken:
                continue
            if key in filters:
                d[key] = ewma_stored(broken)
            elif key == "starved":
                d[key] = duty_stored(broken)
            elif key in fits:
                d[key] = regression_stored(broken)
            elif key == "sun_term":
                d[key] = sun_stored(broken)
            elif key in flags:
                d[key] = spoil(rng.choice([True, False]), broken)
            elif key in optional:
                d[key] = spoil(rng.choice([None, round(rng.uniform(-5.0, 60.0), 2)]), broken)
            elif key == "learning_hold_cell":
                d[key] = spoil(rng.choice([None, "7:1", "12:3", ""]), broken)
            elif key == "cold_after":
                d[key] = spoil(rng.choice([None, "sweep", "coast", "shortfall", "off"]), broken)
            else:
                d[key] = spoil(round(rng.uniform(-10.0, 2e6), rng.choice([0, 1, 2])), broken)
        return d

    def old_offset():
        """Sparat före 2026-09-29: curve_offset i stället för balance_shift."""
        d = modern(0.05) if rng.random() < 0.5 else {}
        d.pop("balance_shift", None)
        if rng.random() < 0.2:
            d["balance_shift"] = rng.choice([None, "abc", -4.0])
        d["curve_offset"] = rng.choice([-5.89, 9.0, 0.0, round(rng.uniform(-15.0, 15.0), 2), "abc", None, "3.5"])
        d["slope_trim"] = rng.choice([0.0, round(rng.uniform(-0.5, 0.5), 3), "x"])
        r = rng.random()
        if r < 0.6:
            d["curve_fit"] = regression_stored(0.0, rng.randint(1, 3))
        elif r < 0.75:
            d.pop("curve_fit", None)
        elif r < 0.9:
            d["curve_fit"] = {"weight": 0.0}
        else:
            d["curve_fit"] = "abc"
        return d

    def old_shortfall():
        """Sparat den morgonen (ba43a64) det hette shortfall_cold och seconds_shortfall_cold."""
        d = modern(0.05) if rng.random() < 0.4 else {}
        d.pop("cold_after", None)
        d.pop("seconds_cold", None)
        d["shortfall_cold"] = rng.choice([True, False, "false", "", 0, 1, None, 1.0, 0.0, [], [1.0]])
        if rng.random() < 0.5:
            d["seconds_shortfall_cold"] = rng.choice([120.0, round(rng.uniform(0.0, 1e5), 1), "abc", None, "77"])
        r = rng.random()
        if r < 0.3:
            d["cold_after"] = rng.choice(["sweep", "coast", "shortfall", "off"])
        elif r < 0.5:
            d["cold_after"] = rng.choice([None, "foo", 3, "", "SWEEP", []])
        if rng.random() < 0.3:
            d["seconds_cold"] = rng.choice([None, "abc", 55.0, 0, "12"])
        return d

    def learned_row(stored, tuning_overrides):
        tuning = m.tuning_from_dict(tuning_overrides) if tuning_overrides else Tuning()
        state = LearnedState.from_dict(stored, tuning)
        d = state.as_dict()
        back = LearnedState.from_dict(d, tuning).as_dict()
        return [_stored2(tuning_overrides), _stored2(stored), state.balance_shift, state.slope_trim, state.bend,
                state.process_gain, state.cold_after, state.seconds_cold, state.seconds_since_off,
                _bool(state.needs_reseed), _bool(state.warm_stopped), state.delta_scale.seconds,
                state.curve_fit.horizon, state.sun_term.fit.horizon, state.outdoor.value, ",".join(d),
                _bool(_same(back, d))]

    fit = est.Regression(Tuning().learn_horizon_seconds)
    fit.add(10.0 / 39.0, 0.0, 30.0)
    rows = [learned_row(stored, None) for stored in
            (None, {}, {"shortfall_cold": True, "seconds_shortfall_cold": 120.0},
             {"balance_shift": -4.0, "curve_offset": 9.0},
             {"curve_offset": -5.89, "slope_trim": 0.0, "curve_fit": fit.as_dict()})]
    for maker, count in ((lambda: None, 2), (dict, 2), (lambda: modern(0.0), 35), (lambda: modern(0.15), 25),
                         (lambda: modern(0.5), 20), (old_offset, 32), (old_shortfall, 29)):
        for _ in range(count):
            rows.append(learned_row(maker(), overrides()))
    write(step, "learned_state.csv",
          ["tuning", "stored", "balance_shift", "slope_trim", "bend", "process_gain", "cold_after", "seconds_cold",
           "seconds_since_off", "needs_reseed", "warm_stopped", "delta_scale_seconds", "curve_fit_horizon",
           "sun_term_horizon", "outdoor_value", "as_dict_keys", "roundtrip_equal"], rows)

    # --- balance_from_offset.csv och effective_curvature.csv ---------------------------------------
    curve_keys = ("room_target", "design_outdoor", "curve_slope", "curve_curvature", "slope_min", "slope_max",
                  "balance_shift_min", "balance_shift_max")
    rows = []
    for _ in range(80):
        tuning_overrides = {key: overridable[key]() for key in curve_keys if rng.random() < 0.3}
        tuning = m.tuning_from_dict(tuning_overrides)
        offset = rng.choice([0.0, -5.89, 9.0, -20.0, 25.0, round(rng.uniform(-15.0, 15.0), 2)])
        slope_trim = rng.choice([0.0, 0.0, 0.0, round(rng.uniform(-0.8, 0.8), 3)])
        state = LearnedState()
        state.slope_trim = slope_trim
        fit_add = None
        if rng.random() < 0.6:
            fit_add = (round(rng.uniform(-0.2, 1.3), 4), round(rng.uniform(-3.0, 3.0), 2), rng.choice([30.0, 0.0, 3600.0]))
            state.curve_fit.add(*fit_add)
        rows.append([offset, slope_trim, None if fit_add is None else _list(fit_add), state.curve_fit.weight,
                     state.curve_fit.mean_x] + [getattr(tuning, key) for key in curve_keys]
                    + [m._balance_from_offset(offset, state, tuning)])
    write(step, "balance_from_offset.csv",
          ["offset", "slope_trim", "fit_add", "fit_weight", "fit_mean_x", *curve_keys, "balance"], rows)

    rows = []
    for bend in (None, None, None, 0.0, 1.1, 0.8, -0.5, 2.0, round(rng.uniform(0.5, 1.5), 3)):
        for curvature in rng.sample([0.8, 0.6, 1.0, 1.1, round(rng.uniform(0.5, 1.5), 3)], 2):
            state = LearnedState()
            state.bend = bend
            tuning = Tuning()
            tuning.curve_curvature = curvature
            rows.append([bend, curvature, m.effective_curvature(tuning, state)])
    write(step, "effective_curvature.csv", ["bend", "curve_curvature", "effective"], rows)


def _sensor(reading) -> str:
    """En SensorReading som key,temperature,role,weight,offset,stratification,active,featured;
    tom temperatur är None, bool som true/false. Nycklarna innehåller inga , | eller ;."""
    temperature = "" if reading.temperature is None else repr(reading.temperature)
    return ",".join([reading.key, temperature, reading.role, repr(reading.weight), repr(reading.offset),
                     repr(reading.stratification), _bool(reading.active), _bool(reading.featured)])


def _args(*values) -> str:
    """Argument med , emellan, tomt för None, flyttal med repr."""
    return ",".join("" if v is None else repr(float(v)) for v in values)


def controller(core, rng) -> None:
    """Steg 12, regulatorns grund: fuse_indoor, sunlight_now, de små hjälparna och nödtexten."""
    m, c, t = core.model, core.controller, core.texts
    step = "12-grunden"
    keys = ["sensor.tak_hall", "sensor.tak_kok", "sensor.solrum", "sensor.sovrum", "sensor.kontor"]

    def reading(i, temperature, role, weight, offset=0.0, stratification=0.0, active=True, featured=False):
        return m.SensorReading(key=keys[i], temperature=temperature, role=role, weight=weight, offset=offset,
                               stratification=stratification, active=active, featured=featured)

    # Kanterna först: solrummet precis på taket (inte hållet), bara solrum (inget att hålla mot),
    # två solrum mot resten, ett tillopp under målet (ingen skiktning), inget tillopp alls.
    made = [
        (21.0, 1.5, 45.0, [reading(0, 21.0, m.ROLE_CEILING, 1.0), reading(2, 22.5, m.ROLE_SOLAR, 0.15)]),
        (21.0, 1.5, 45.0, [reading(0, 21.0, m.ROLE_CEILING, 1.0), reading(2, 22.6, m.ROLE_SOLAR, 0.15)]),
        (21.0, 1.5, 45.0, [reading(2, 30.0, m.ROLE_SOLAR, 0.15), reading(3, 24.0, m.ROLE_SOLAR, 0.15)]),
        (21.0, 1.0, 45.0, [reading(0, 21.0, m.ROLE_ROOM, 1.0), reading(2, 30.0, m.ROLE_SOLAR, 0.15),
                           reading(3, 21.8, m.ROLE_SOLAR, 0.3)]),
        (21.0, 1.5, 18.0, [reading(0, 22.5, m.ROLE_CEILING, 1.0, stratification=0.06)]),
        (21.0, 1.5, None, [reading(0, 22.5, m.ROLE_CEILING, 1.0, stratification=0.06)]),
        (21.0, 1.5, 45.0, [reading(0, 21.0, m.ROLE_ROOM, 1.0, active=False),
                           reading(2, 30.0, m.ROLE_SOLAR, 0.15)]),
        (21.0, 1.5, 45.0, []),
    ]
    for _ in range(1500):
        count = rng.choice([0, 1, 1, 2, 3, 3, 4, 5])
        made.append((
            rng.choice([21.0, round(rng.uniform(18.0, 24.0), 1)]),
            rng.choice([1.5, round(rng.uniform(0.0, 3.0), 2)]),
            None if rng.random() < 0.15 else round(rng.uniform(15.0, 70.0), rng.choice([1, 3])),
            [reading(i,
                     None if rng.random() < 0.1 else round(rng.uniform(14.0, 32.0), rng.choice([1, 2, 6])),
                     rng.choice(m.ROLES),
                     rng.choice([1.0, 0.5, 0.15, 0.0, -0.3, round(rng.uniform(0.05, 1.5), 3)]),
                     offset=rng.choice([0.0, 0.0, round(rng.uniform(-1.5, 1.5), 2)]),
                     stratification=rng.choice([0.0, 0.0, round(rng.uniform(0.01, 0.1), 3), -0.02]),
                     active=rng.random() < 0.85,
                     featured=rng.random() < 0.2)
             for i in range(count)],
        ))
    rows = []
    for target, clamp, flow, readings in made:
        fused, details = c.fuse_indoor(readings, m.Tuning(room_target=target, solar_clamp=clamp), flow)
        rows.append([target, clamp, flow, "|".join(_sensor(r) for r in readings), fused,
                     "/".join(_stored(d.as_dict()) for d in details)])
    write(step, "fuse_indoor.csv", ["room_target", "solar_clamp", "flow", "readings", "fused", "details"], rows)

    rows = []
    for error, band in ((0.5, 0.5), (-0.5, 0.5), (0.0, 0.0), (1.0, 0.0), (-1.0, -0.5), (0.7, 0.5)):
        rows.append(["deadbanded", _args(error, band), c._deadbanded(error, band)])
    for _ in range(400):
        error, band = rng.uniform(-5.0, 5.0), rng.choice([0.0, -0.5, 0.5, rng.uniform(0.0, 2.0)])
        rows.append(["deadbanded", _args(error, band), c._deadbanded(error, band)])
    for value, low, high in ((5.0, 0.0, 1.0), (-5.0, 0.0, 1.0), (0.5, 0.0, 1.0), (0.5, 1.0, 0.0), (5.0, 3.0, 2.0)):
        rows.append(["clamp", _args(value, low, high), c._clamp(value, low, high)])
    for _ in range(300):
        value, low, high = rng.uniform(-10.0, 10.0), rng.uniform(-5.0, 5.0), rng.uniform(-5.0, 5.0)
        rows.append(["clamp", _args(value, low, high), c._clamp(value, low, high)])
    for _ in range(300):
        value, goal = rng.uniform(-10.0, 10.0), rng.uniform(-10.0, 10.0)
        step_size = rng.choice([0.0, -1.0, 0.5, rng.uniform(0.0, 5.0), 100.0])
        rows.append(["toward", _args(value, goal, step_size), c._toward(value, goal, step_size)])
    for last, wanted, dt, seconds in ((8.0, 2.0, 30.0, 900.0), (2.0, 8.0, 900.0, 900.0), (None, 8.0, 30.0, 900.0),
                                      (2.0, 8.0, 30.0, 0.0), (2.0, 8.0, -30.0, 900.0), (2.0, 8.0, 30.0, -900.0),
                                      (2.0, 2.0, 30.0, 900.0)):
        rows.append(["rising_slowly", _args(last, wanted, dt, seconds), c._rising_slowly(last, wanted, dt, seconds)])
    for _ in range(400):
        last = None if rng.random() < 0.15 else rng.uniform(0.0, 10.0)
        wanted = rng.uniform(0.0, 10.0)
        dt = rng.choice([30.0, -30.0, 0.0, rng.uniform(0.0, 3600.0)])
        seconds = rng.choice([900.0, 0.0, -900.0, rng.uniform(1.0, 3600.0)])
        rows.append(["rising_slowly", _args(last, wanted, dt, seconds), c._rising_slowly(last, wanted, dt, seconds)])
    write(step, "helpers.csv", ["function", "args", "expected"], rows)

    rows = []
    for _ in range(400):
        light = rng.choice([None, math.nan, math.inf, -math.inf, -0.2, 0.0, rng.uniform(0.0, 0.9), rng.uniform(0.0, 0.9)])
        elevation = rng.choice([None, rng.uniform(-10.0, 60.0), rng.uniform(-10.0, 60.0)])
        cloud = rng.choice([None, 0.0, 1.0, rng.uniform(0.0, 1.0)])
        cycle = m.ControlInputs(dt=30.0, sunlight=light, sun_elevation=elevation, cloud=cloud)
        rows.append([light, elevation, cloud, c.sunlight_now(cycle)])
    write(step, "sunlight_now.csv", ["sunlight", "sun_elevation", "cloud", "expected"], rows)

    rows = []
    kinds = sorted(m.EMERGENCY_KINDS) + ["x"]
    for position, flow in ((0.5, 44.5), (1.5, 45.5), (2.5, 20.5), (50.5, 35.5), (64.4, 64.6), (99.5, 69.5)):
        for kind in kinds:
            rows.append([kind, position, flow, t.emergency_reason(kind, position, flow)])
    for _ in range(300):
        kind = rng.choice(kinds)
        position = rng.choice([float(rng.randint(0, 100)), rng.randint(0, 100) + 0.5, rng.uniform(0.0, 100.0)])
        flow = rng.choice([float(rng.randint(20, 70)), rng.randint(20, 70) + 0.5, rng.uniform(20.0, 70.0)])
        rows.append([kind, position, flow, t.emergency_reason(kind, position, flow)])
    write(step, "emergency_reason.csv", ["kind", "position", "flow", "expected"], rows)


def _clocks(state, names) -> dict:
    return {name: getattr(state, name) for name in names}


def cycle_start(core, rng) -> None:
    """Steg 13, cykelns början: _outdoor, _indoor, _timers, _curve_outdoor och kurvan."""
    m, c, th = core.model, core.controller, core.thermal
    step = "13-ute-inne-kurvan"
    keys = ["sensor.tak_hall", "sensor.tak_kok", "sensor.solrum", "sensor.sovrum", "sensor.kontor"]

    def reading(i, temperature, role, weight, offset=0.0, stratification=0.0, active=True, featured=False):
        return m.SensorReading(key=keys[i], temperature=temperature, role=role, weight=weight, offset=offset,
                               stratification=stratification, active=active, featured=featured)

    def maybe(value, chance=0.2):
        return None if rng.random() < chance else value

    def temperature(low, high):
        return round(rng.uniform(low, high), rng.choice([1, 2, 6]))

    def a_dt():
        return rng.choice([30.0, 30.0, 0.0, 600.0, 3600.0, round(rng.uniform(1.0, 7200.0), 3)])

    def loop(**tuning):
        return c.HeatingController(m.Tuning(**tuning), m.LearnedState())

    # --- _outdoor: kanterna först. Aldrig mätt (dimensionerande kyla), prognosen i stället för en
    # saknad givare (på gränsen 1,5 h är den med, 1,6 h inte), sommarfiltret utan värde, filter
    # utan tidskonstant, en cykel utan tid.
    def slots():
        if rng.random() < 0.4:
            return []
        weather, hours = [], rng.choice([0.5, 1.0, 1.5, 1.6, 3.0, round(rng.uniform(0.0, 4.0), 2)])
        for _ in range(rng.choice([1, 1, 2, 3])):
            weather.append((hours, th.Conditions(
                outdoor=temperature(-20.0, 25.0), wind=maybe(round(rng.uniform(0.0, 10.0), 1), 0.5),
                bearing=maybe(round(rng.uniform(0.0, 360.0), 0), 0.5), sun=rng.choice([0.0, round(rng.uniform(0.0, 0.8), 3)]),
                hour=maybe(round(rng.uniform(0.0, 24.0), 1), 0.5))))
            hours += rng.choice([1.0, 3.0])
        return weather

    day, three = 24.0 * 3600.0, 3.0 * 3600.0
    made = [
        (-18.0, (three, None), (day, None), None, [], 30.0),
        (-18.0, (three, None), (day, None), None, [(0.5, th.Conditions(outdoor=-5.0))], 30.0),
        (-18.0, (three, 5.0), (day, 5.0), None, [(1.5, th.Conditions(outdoor=-5.0))], 30.0),
        (-18.0, (three, 5.0), (day, 5.0), None, [(1.6, th.Conditions(outdoor=-5.0))], 30.0),
        (-18.0, (three, 5.0), (day, 5.0), None, [(3.0, th.Conditions(outdoor=-5.0))], 30.0),
        (-18.0, (three, 5.0), (day, None), None, [], 30.0),
        (-18.0, (three, 5.0), (day, None), 0.0, [], 30.0),
        (-18.0, (three, None), (day, 5.0), None, [], 30.0),
        (-22.0, (three, 5.0), (day, 5.0), 5.0, [(0.5, th.Conditions(outdoor=-5.0))], 30.0),
        (-18.0, (0.0, 5.0), (day, 5.0), 0.0, [], 30.0),
        (-18.0, (three, 5.0), (day, 5.0), 0.0, [], 0.0),
    ]
    for _ in range(600):
        made.append((
            rng.choice([-18.0, -18.0, -22.0, round(rng.uniform(-30.0, -5.0), 1)]),
            (rng.choice([three, three, 0.0, round(rng.uniform(60.0, 6.0 * 3600.0), 1)]), maybe(temperature(-20.0, 25.0))),
            (rng.choice([day, day, round(rng.uniform(3600.0, 72.0 * 3600.0), 1)]), maybe(temperature(-20.0, 25.0), 0.35)),
            maybe(temperature(-25.0, 30.0), 0.4),
            slots(),
            a_dt(),
        ))
    rows = []
    for design, (o_seconds, o_before), (s_seconds, s_before), measured, weather, dt in made:
        made_loop = loop(design_outdoor=design, outdoor_filter_seconds=o_seconds, summer_filter_seconds=s_seconds)
        made_loop.state.outdoor.value = o_before
        made_loop.state.outdoor_summer.value = s_before
        out = m.ControlOutput()
        filtered, summer = made_loop._outdoor(m.ControlInputs(dt=dt, outdoor=measured, forecast=weather), dt, out)
        rows.append([design, o_seconds, o_before, s_seconds, s_before, measured, _slots(weather), dt,
                     filtered, summer, ",".join(out.degraded), ",".join(out.flags),
                     made_loop.state.outdoor.value, made_loop.state.outdoor_summer.value])
    write(step, "outdoor.csv", ["design_outdoor", "outdoor_seconds", "outdoor_before", "summer_seconds", "summer_before",
                                "measured", "forecast", "dt", "filtered", "summer", "degraded", "flags",
                                "outdoor_after", "summer_after"], rows)

    # --- _indoor: inga givare, givare som tappat värdet (filtret står kvar men svaret är None),
    # en av två borta (indoor_partial), en viktlös (räknas inte som bidragande), inget tillopp.
    twenty, two = 20.0 * 60.0, 2.0 * 3600.0
    made = [
        (21.0, 1.5, 45.0, [reading(0, None, m.ROLE_ROOM, 1.0)], (twenty, None), (two, None), 30.0),
        (21.0, 1.5, 45.0, [reading(0, None, m.ROLE_ROOM, 1.0)], (twenty, 17.0), (two, 17.5), 30.0),
        (21.0, 1.5, 45.0, [reading(0, 21.0, m.ROLE_ROOM, 1.0), reading(1, None, m.ROLE_ROOM, 1.0)], (twenty, None), (two, None), 30.0),
        (21.0, 1.5, 45.0, [reading(0, 21.0, m.ROLE_ROOM, 1.0), reading(1, 21.5, m.ROLE_ROOM, 0.0)], (twenty, 20.0), (two, 20.0), 30.0),
        (21.0, 1.5, 45.0, [reading(0, 21.0, m.ROLE_ROOM, 1.0), reading(1, 21.5, m.ROLE_ROOM, 1.0, active=False)], (twenty, 20.0), (two, 20.0), 30.0),
        (21.0, 1.5, 45.0, [], (twenty, 20.0), (two, 20.0), 30.0),
        (21.0, 1.5, None, [reading(0, 17.0, m.ROLE_ROOM, 1.0)], (twenty, 21.0), (two, 21.0), 3600.0),
        (21.0, 1.5, 45.0, [reading(0, 17.0, m.ROLE_ROOM, 1.0)], (0.0, 21.0), (two, 21.0), 30.0),
    ]
    for _ in range(600):
        count = rng.choice([0, 1, 1, 2, 3, 3, 4])
        made.append((
            rng.choice([21.0, round(rng.uniform(18.0, 24.0), 1)]),
            rng.choice([1.5, round(rng.uniform(0.0, 3.0), 2)]),
            None if rng.random() < 0.15 else round(rng.uniform(15.0, 70.0), rng.choice([1, 3])),
            [reading(i, None if rng.random() < 0.15 else temperature(14.0, 32.0), rng.choice(m.ROLES),
                     rng.choice([1.0, 0.5, 0.15, 0.0, -0.3, round(rng.uniform(0.05, 1.5), 3)]),
                     offset=rng.choice([0.0, 0.0, round(rng.uniform(-1.5, 1.5), 2)]),
                     stratification=rng.choice([0.0, 0.0, round(rng.uniform(0.01, 0.1), 3)]),
                     active=rng.random() < 0.85, featured=rng.random() < 0.2)
             for i in range(count)],
            (rng.choice([twenty, twenty, 0.0, round(rng.uniform(60.0, 3600.0), 1)]), maybe(temperature(16.0, 26.0), 0.3)),
            (rng.choice([two, two, round(rng.uniform(600.0, 6.0 * 3600.0), 1)]), maybe(temperature(16.0, 26.0), 0.3)),
            a_dt(),
        ))
    rows = []
    for target, clamp, flow, readings, (i_seconds, i_before), (s_seconds, s_before), dt in made:
        made_loop = loop(room_target=target, solar_clamp=clamp, indoor_filter_seconds=i_seconds,
                         indoor_slow_filter_seconds=s_seconds)
        made_loop.state.indoor.value = i_before
        made_loop.state.indoor_slow.value = s_before
        out = m.ControlOutput()
        fused, slow = made_loop._indoor(m.ControlInputs(dt=dt, flow=flow, indoor=readings), dt, out)
        rows.append([target, clamp, flow, i_seconds, i_before, s_seconds, s_before, dt,
                     "|".join(_sensor(r) for r in readings), fused, slow, ",".join(out.flags), ",".join(out.degraded),
                     ",".join(_bool(d.contributed) for d in out.indoor_readings),
                     made_loop.state.indoor.value, made_loop.state.indoor_slow.value])
    write(step, "indoor.csv", ["room_target", "solar_clamp", "flow", "indoor_seconds", "indoor_before", "slow_seconds",
                               "slow_before", "dt", "readings", "fused", "slow", "flags", "degraded", "contributed",
                               "indoor_after", "slow_after"], rows)

    # --- _timers: klockorna före och efter, som "namn=f:tal|..." i en fast ordning.
    clocks = ["seconds_since_override", "seconds_since_target_change", "seconds_since_coast", "seconds_since_sun",
              "seconds_over_target", "seconds_since_sweep", "seconds_since_shortfall", "seconds_since_off",
              "seconds_since_failsafe", "seconds_without_flow"]
    fixed = [
        # (learn_sunlight, room_target, learn_over_target, sun_heat_seconds, dt, override, emergency, target_changed,
        #  sunlight, elevation, cloud, sweeping, since_failsafe, flow, ret, sun_heat_before, slow_before, clocks before)
        (0.047, 21.0, 0.3, three, 30.0, False, "", False, 0.047, None, None, False, None, 45.0, 38.0, None, 21.3, None),
        (0.047, 21.0, 0.3, three, 30.0, False, "", False, 0.0471, None, None, False, None, 45.0, 38.0, 0.1, 21.31, None),
        (0.047, 21.0, 0.3, three, 30.0, False, "valve", False, None, 20.0, 0.5, False, -30.0, None, None, None, None, None),
        (0.047, 21.0, 0.3, three, 30.0, False, "flow", True, None, None, None, True, 0.0, 45.0, None, 0.2, 25.0, None),
        (0.047, 21.0, 0.3, three, 30.0, True, "x", False, None, None, None, False, 5000.0, 45.0, 38.0, 0.2, 20.0,
         {"seconds_since_failsafe": 60.0}),
        (0.047, 21.0, 0.3, three, 0.0, False, "", False, 0.3, None, None, False, 30.0, 45.0, 38.0, None, 22.0, None),
    ]
    rows = []
    for i in range(900):
        if i < len(fixed):
            (learn_sunlight, target, over, sun_seconds, dt, override, emergency, changed, light, elevation, cloud,
             sweeping, since_failsafe, flow, ret, sun_before, slow_before, given) = fixed[i]
        else:
            learn_sunlight = rng.choice([0.047, 0.047, 0.0, round(rng.uniform(0.0, 0.3), 3)])
            target = rng.choice([21.0, 21.0, temperature(18.0, 24.0)])
            over = rng.choice([0.3, 0.3, 0.0, round(rng.uniform(0.0, 1.0), 2)])
            sun_seconds = rng.choice([three, three, 600.0])
            dt = a_dt()
            override, changed, sweeping = rng.random() < 0.2, rng.random() < 0.15, rng.random() < 0.15
            emergency = rng.choice(["", "", "", "valve", "flow", "x"])
            light = rng.choice([None, 0.0, 0.047, 0.0471, 0.1, round(rng.uniform(0.0, 0.5), 4)])
            elevation, cloud = maybe(round(rng.uniform(-10.0, 60.0), 2), 0.4), maybe(round(rng.uniform(0.0, 1.0), 2), 0.4)
            since_failsafe = rng.choice([None, None, 0.0, 30.0, -30.0, 5000.0, round(rng.uniform(-100.0, 100000.0), 1)])
            flow, ret = maybe(temperature(20.0, 70.0), 0.25), maybe(temperature(20.0, 60.0), 0.25)
            sun_before, slow_before = maybe(round(rng.uniform(0.0, 0.5), 4)), maybe(temperature(18.0, 25.0), 0.25)
            given = None
        made_loop = loop(learn_sunlight=learn_sunlight, room_target=target, learn_over_target=over,
                         sun_heat_seconds=sun_seconds)
        state = made_loop.state
        for name in clocks:
            setattr(state, name, rng.choice([0.0, 1e6, 1e6, round(rng.uniform(0.0, 90000.0), 3)]))
        for name, value in (given or {}).items():
            setattr(state, name, value)
        state.sun_heat.value = sun_before
        state.indoor_slow.value = slow_before
        before = _clocks(state, clocks)
        out = m.ControlOutput()
        made_loop._timers(m.ControlInputs(
            dt=dt, override_active=override, emergency=emergency, target_changed=changed, sunlight=light,
            sun_elevation=elevation, cloud=cloud, sweeping=sweeping, since_failsafe=since_failsafe, flow=flow, ret=ret,
        ), dt, out)
        rows.append([learn_sunlight, target, over, sun_seconds, dt, _bool(override), emergency, _bool(changed), light,
                     elevation, cloud, _bool(sweeping), since_failsafe, flow, ret, sun_before, slow_before,
                     _stored(before), _stored(_clocks(state, clocks)), state.sun_heat.value, ",".join(out.degraded)])
    write(step, "timers.csv", ["learn_sunlight", "room_target", "learn_over_target", "sun_heat_seconds", "dt", "override",
                               "emergency", "target_changed", "sunlight", "sun_elevation", "cloud", "sweeping",
                               "since_failsafe", "flow", "ret", "sun_heat_before", "slow_before", "before", "after",
                               "sun_heat_after", "degraded"], rows)

    # --- kurvan: _curve_outdoor, _curve, effective_slope, balance, curvature och curve_at.
    curve_fields = ["room_target", "design_outdoor", "curve_slope", "curve_curvature", "slope_min", "slope_max",
                    "flow_min", "flow_max", "outdoor_fall_lead", "outdoor_fall_limit"]
    fixed = [
        (dict(), dict(), None, 0.0, 5.0, None, None, None),
        (dict(room_target=21.0, design_outdoor=-18.0, curve_slope=0.9), dict(), -18.0, -18.0, -18.0, None, None, None),
        (dict(curve_curvature=1.0), dict(slope_trim=0.2), -10.0, -10.0, 5.0, None, None, None),
        (dict(curve_curvature=1.0), dict(balance_shift=-6.0), -10.0, -10.0, 16.0, None, None, None),
        (dict(), dict(), -4.0, 0.0, 0.0, None, None, None),
        (dict(), dict(), -20.0, 0.0, 0.0, None, None, None),
        (dict(outdoor_fall_lead=1.0), dict(), -4.0, 0.0, 0.0, None, None, None),
        (dict(outdoor_fall_lead=0.0), dict(), -4.0, 0.0, 0.0, None, None, None),
        (dict(), dict(), 0.0, 0.0, 0.0, None, None, None),
        (dict(), dict(), 3.0, 0.0, 0.0, None, None, None),
        (dict(flow_max=50.0), dict(), -30.0, -30.0, -30.0, None, None, None),
        (dict(), dict(slope_trim=5.0), 0.0, 0.0, 0.0, 0.9, 21.0, 1.0),
        (dict(), dict(slope_trim=-5.0, bend=1.2), 0.0, 0.0, 0.0, None, 15.0, None),
    ]
    rows = []
    for i in range(700):
        if i < len(fixed):
            tuning_kw, state_kw, measured, filtered, at, at_slope, at_balance, at_curvature = fixed[i]
        else:
            tuning_kw = dict(
                room_target=rng.choice([21.0, 21.0, temperature(18.0, 24.0)]),
                design_outdoor=rng.choice([-18.0, -18.0, -22.0, round(rng.uniform(-30.0, -5.0), 1)]),
                curve_slope=rng.choice([0.9, 0.9, round(rng.uniform(0.3, 2.5), 2)]),
                curve_curvature=rng.choice([0.8, 1.0, 1.3, round(rng.uniform(0.5, 1.5), 2)]),
                slope_min=rng.choice([0.2, 0.2, 0.5]), slope_max=rng.choice([2.0, 2.0, 1.0]),
                flow_min=rng.choice([20.0, 20.0, 25.0]), flow_max=rng.choice([65.0, 65.0, 50.0]),
                outdoor_fall_lead=rng.choice([0.5, 0.5, 0.0, 1.0, round(rng.uniform(0.0, 1.0), 2)]),
                outdoor_fall_limit=rng.choice([4.0, 4.0, 2.0, 0.0, round(rng.uniform(0.0, 8.0), 1)]),
            )
            state_kw = dict(
                slope_trim=rng.choice([0.0, 0.0, round(rng.uniform(-1.5, 1.5), 3), 5.0, -5.0]),
                balance_shift=rng.choice([0.0, 0.0, -6.0, round(rng.uniform(-14.0, 3.0), 2)]),
                bend=maybe(round(rng.uniform(0.5, 1.5), 3), 0.6),
            )
            measured, filtered = maybe(temperature(-30.0, 20.0), 0.3), temperature(-30.0, 20.0)
            at = temperature(-30.0, 25.0)
            at_slope = maybe(round(rng.uniform(0.2, 2.0), 2), 0.5)
            at_balance = maybe(temperature(10.0, 24.0), 0.5)
            at_curvature = maybe(round(rng.uniform(0.5, 1.5), 2), 0.5)
        tuning = m.Tuning(**tuning_kw)
        made_loop = c.HeatingController(tuning, m.LearnedState(**state_kw))
        curve_outdoor = made_loop._curve_outdoor(measured, filtered)
        flow, slope = made_loop._curve(curve_outdoor)
        rows.append([_stored({name: getattr(tuning, name) for name in curve_fields}),
                     made_loop.state.slope_trim, made_loop.state.balance_shift, made_loop.state.bend,
                     measured, filtered, curve_outdoor, slope, made_loop.balance(), made_loop.curvature(), flow,
                     at, made_loop.curve_at(at), at_slope, at_balance, at_curvature,
                     made_loop.curve_at(at, at_slope, at_balance, at_curvature)])
    write(step, "curve.csv", ["tuning", "slope_trim", "balance_shift", "bend", "measured", "filtered", "curve_outdoor",
                              "effective_slope", "balance", "curvature", "curve_flow", "at", "curve_at",
                              "at_slope", "at_balance", "at_curvature", "curve_at_given"], rows)


def delta_and_radiators(core, rng) -> None:
    """Steg 14, deltat och radiatorvakten: _delta_weight, _delta_trim, _delta och _watch_radiators."""
    m, c, est = core.model, core.controller, core.estimators
    step = "14-deltat-radiatorvakten"

    def maybe(value, chance=0.2):
        return None if rng.random() < chance else value

    def temperature(low, high):
        return round(rng.uniform(low, high), rng.choice([1, 2, 6]))

    def a_dt():
        return rng.choice([30.0, 30.0, 30.0, 0.0, 600.0, round(rng.uniform(1.0, 3600.0), 3)])

    def branch(counts, key):
        counts[key] = counts.get(key, 0) + 1

    def show(name, counts):
        print(f"{step}/{name}: " + ", ".join(f"{key} {count}" for key, count in sorted(counts.items())))

    # --- _delta_weight: rampen, brytaren (delta_fade <= 0) och rummet som saknas (då målet).
    fixed = [
        (21.0, 10.0, 5.0, 31.0, 21.0), (21.0, 10.0, 5.0, 36.0, 21.0), (21.0, 10.0, 5.0, 33.5, 21.0),
        (21.0, 10.0, 5.0, 30.9, 21.0), (21.0, 10.0, 5.0, 31.0, None), (21.0, 10.0, 0.0, 31.0, 21.0),
        (21.0, 10.0, 0.0, 30.99, 21.0), (21.0, 10.0, -1.0, 31.0, None), (21.0, 10.0, -1.0, 20.0, None),
        (21.0, 0.0, 5.0, 21.0, 21.0), (21.0, 10.0, 5.0, 10.0, 21.0), (20.0, 10.0, 0.0, 30.0, None),
    ]
    rows = []
    for i in range(300):
        if i < len(fixed):
            target, overtemp, fade, supply, room = fixed[i]
        else:
            target = rng.choice([21.0, 21.0, temperature(18.0, 24.0)])
            overtemp = rng.choice([10.0, 10.0, 0.0, 5.0, round(rng.uniform(0.0, 20.0), 2)])
            fade = rng.choice([5.0, 5.0, 0.0, 0.0, -1.0, round(rng.uniform(0.1, 10.0), 2)])
            supply = temperature(15.0, 70.0)
            room = maybe(temperature(14.0, 30.0), 0.25)
        made_loop = c.HeatingController(m.Tuning(room_target=target, delta_min_overtemp=overtemp, delta_fade=fade),
                                        m.LearnedState())
        rows.append([target, overtemp, fade, supply, room, made_loop._delta_weight(supply, room)])
    write(step, "delta_weight.csv", ["room_target", "delta_min_overtemp", "delta_fade", "supply", "room", "weight"], rows)

    # Det som _delta_trim läser och skriver, i en fast ordning.
    trim_fields = ["delta_warmup_seconds", "delta_settle_band", "delta_settle_seconds", "delta_hold_max_seconds",
                   "delta_filter_seconds", "delta_gain", "delta_lower_limit", "delta_raise_limit"]
    trim_state = ["seconds_since_stop", "last_flow_setpoint", "seconds_settled", "seconds_unsettled",
                  "delta_trim_held"]

    def trim_tuning():
        return dict(
            delta_warmup_seconds=rng.choice([2700.0, 2700.0, 0.0, round(rng.uniform(0.0, 5400.0), 1)]),
            delta_settle_band=rng.choice([1.0, 1.0, 0.0, 0.5, round(rng.uniform(0.0, 3.0), 2)]),
            delta_settle_seconds=rng.choice([300.0, 300.0, 0.0, round(rng.uniform(0.0, 1200.0), 1)]),
            delta_hold_max_seconds=rng.choice([1800.0, 1800.0, 0.0, round(rng.uniform(0.0, 3600.0), 1)]),
            delta_filter_seconds=rng.choice([900.0, 900.0, 0.0, round(rng.uniform(1.0, 3600.0), 1)]),
            delta_gain=rng.choice([1.2, 1.2, 0.0, round(rng.uniform(0.0, 3.0), 3)]),
            delta_lower_limit=rng.choice([6.0, 6.0, 0.0, round(rng.uniform(0.0, 10.0), 2)]),
            delta_raise_limit=rng.choice([2.0, 2.0, 0.0, round(rng.uniform(0.0, 5.0), 2)]),
        )

    def trim_before(tuning, flow, warm_share=0.15):
        """Klockorna före: oftast förbi uppvärmningen, börvärdet oftast nära tilloppet."""
        since_stop = (round(rng.uniform(0.0, tuning["delta_warmup_seconds"]), 1)
                      if rng.random() < warm_share and tuning["delta_warmup_seconds"] > 0.0
                      else rng.choice([1e9, 1e9, tuning["delta_warmup_seconds"], round(rng.uniform(0.0, 20000.0), 1)]))
        band = tuning["delta_settle_band"]
        setpoint = rng.choice([
            None, round(flow + rng.uniform(-band, band), 3), round(flow + rng.uniform(-band, band), 3),
            round(flow + rng.uniform(-band, band), 3), flow + band, round(flow + rng.uniform(-10.0, 10.0), 2),
        ])
        settle = tuning["delta_settle_seconds"]
        hold = tuning["delta_hold_max_seconds"]
        return {
            "seconds_since_stop": since_stop,
            "last_flow_setpoint": setpoint,
            "seconds_settled": rng.choice([0.0, settle, 1e6, round(rng.uniform(0.0, 2.0 * settle + 60.0), 1)]),
            "seconds_unsettled": rng.choice([0.0, 0.0, hold, round(rng.uniform(0.0, hold + 60.0), 1)]),
            "delta_trim_held": rng.choice([0.0, 0.0, round(rng.uniform(-6.0, 2.0), 3)]),
            "delta_filter": maybe(temperature(0.0, 20.0), 0.3),
        }

    def set_state(state, before):
        for name, value in before.items():
            if name == "delta_filter":
                state.delta_filter.value = value
            elif name == "delta_scale":
                state.delta_scale.value = value
            else:
                setattr(state, name, value)

    def get_state(state, names):
        got = {}
        for name in names:
            got[name] = (state.delta_filter.value if name == "delta_filter"
                         else state.delta_scale.value if name == "delta_scale" else getattr(state, name))
        return got

    def trim_branch(out, trim):
        if "delta_warming" in out.flags:
            return "warming"
        if "delta_held_moving" in out.flags:
            return "held"
        if out.delta_read is not None:
            return "settled"
        return "let_go"

    # --- _delta_trim: uppvärmningen, stilla (filtret), på väg (hållet) och på väg för länge (släpper).
    defaults = {name: getattr(m.Tuning(), name) for name in trim_fields}
    base = {"seconds_since_stop": 1e9, "last_flow_setpoint": 45.0, "seconds_settled": 0.0,
            "seconds_unsettled": 0.0, "delta_trim_held": -1.0, "delta_filter": 6.0}
    fixed = [
        # (tuning, before, flow, delta, expected, weight, dt)
        (defaults, dict(base, seconds_since_stop=0.0), 45.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_since_stop=2700.0, seconds_settled=300.0), 45.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_since_stop=2699.9), 45.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_settled=270.0), 46.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_settled=270.0), 46.01, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_settled=260.0), 45.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, last_flow_setpoint=None, seconds_settled=1e6), 45.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_unsettled=1770.0), 50.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_unsettled=1760.0), 50.0, 6.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_settled=1e6, delta_filter=None), 45.0, 2.0, 10.0, 0.5, 30.0),
        (defaults, dict(base, seconds_settled=1e6), 45.0, 30.0, 8.0, 1.0, 30.0),
        (defaults, dict(base, seconds_settled=1e6), 45.0, 6.0, 8.0, 1.0, 0.0),
        (dict(defaults, delta_settle_seconds=0.0), dict(base, last_flow_setpoint=None), 45.0, 6.0, 8.0, 1.0, 0.0),
    ]
    counts = {}
    rows = []
    for i in range(600):
        if i < len(fixed):
            tuning_kw, before, flow, delta, expected, weight, dt = fixed[i]
        else:
            tuning_kw = trim_tuning()
            flow = temperature(20.0, 70.0)
            before = trim_before(tuning_kw, flow)
            delta = rng.choice([round(rng.uniform(-2.0, 25.0), 3), temperature(0.0, 20.0)])
            expected = rng.choice([1.0, 10.0, round(rng.uniform(1.0, 15.0), 3)])
            weight = rng.choice([1.0, 1.0, 0.5, round(rng.uniform(0.0, 1.0), 4)])
            dt = a_dt()
        made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState())
        set_state(made_loop.state, before)
        out = m.ControlOutput()
        trim = made_loop._delta_trim(m.ControlInputs(dt=dt, flow=flow), delta, expected, weight, dt, out)
        branch(counts, trim_branch(out, trim))
        rows.append([_stored({name: tuning_kw[name] for name in trim_fields}), _stored(before), flow, delta, expected,
                     weight, dt, trim, "|".join(out.flags), out.delta_read, out.delta_warming_left,
                     _stored(get_state(made_loop.state, list(before)))])
    write(step, "delta_trim.csv", ["tuning", "before", "flow", "delta", "expected", "weight", "dt", "trim", "flags",
                                   "delta_read", "warming_left", "after"], rows)
    show("delta_trim.csv", counts)

    # --- _delta: tyst av fyra skäl (givare, av, lågt behov, ljumt), annars förväntat delta och
    # rättelsen, som inomhusslingan får sista ordet över åt båda hållen.
    delta_fields = ["delta_enabled", "delta_min_demand", "delta_min_overtemp", "delta_fade", "delta_design",
                    "delta_cold_guard", "room_target"] + trim_fields
    full = dict({name: getattr(m.Tuning(), name) for name in delta_fields})
    settled = {"delta_scale": None, "seconds_since_stop": 1e9, "last_flow_setpoint": 45.0, "seconds_settled": 1e6,
               "seconds_unsettled": 0.0, "delta_trim_held": 0.0, "delta_filter": 8.0}
    fixed = [
        # (tuning, before, flow, ret, indoor, demand, dt, base_flow, room)
        (full, settled, None, 38.0, 21.0, 0.5, 30.0, None, None),
        (full, settled, 45.0, None, 21.0, 0.5, 30.0, None, None),
        (dict(full, delta_enabled=False), settled, 45.0, 38.0, 21.0, 0.5, 30.0, None, None),
        (full, settled, 45.0, 38.0, 21.0, 0.15, 30.0, None, None),
        (full, settled, 45.0, 38.0, 21.0, 0.149, 30.0, None, None),
        (full, settled, 31.0, 26.0, 21.0, 0.5, 30.0, None, None),
        (full, settled, 31.0, 26.0, None, 0.5, 30.0, None, None),
        (full, settled, 45.0, 38.0, 21.0, 0.5, 30.0, 30.0, None),
        (full, settled, 45.0, 38.0, 21.0, 0.5, 30.0, None, 36.0),
        (full, dict(settled, delta_filter=2.0), 45.0, 43.0, 20.0, 0.5, 30.0, None, None),   # kallt: hålls
        (full, dict(settled, delta_filter=2.0), 45.0, 43.0, 20.7, 0.5, 30.0, None, None),   # på vakten: sänks
        (full, dict(settled, delta_filter=2.0), 45.0, 43.0, None, 0.5, 30.0, None, None),   # utan inne: sänks
        (full, dict(settled, delta_filter=20.0), 45.0, 25.0, 22.0, 0.5, 30.0, None, None),
        (full, dict(settled, delta_filter=20.0), 45.0, 25.0, 21.3, 0.5, 30.0, None, None),
        (full, dict(settled, delta_scale=0.05), 45.0, 44.0, 21.0, 0.05 + 0.15, 30.0, None, None),
        (full, dict(settled, delta_scale=2.0), 45.0, 38.0, 21.0, 1.5, 30.0, None, None),
        (full, dict(settled, seconds_since_stop=0.0), 45.0, 38.0, 21.0, 0.5, 30.0, None, None),
    ]
    counts = {}
    rows = []
    for i in range(800):
        if i < len(fixed):
            tuning_kw, before, flow, ret, indoor, demand, dt, base_flow, room = fixed[i]
        else:
            tuning_kw = dict(
                delta_enabled=rng.random() < 0.92,
                delta_min_demand=rng.choice([0.15, 0.15, 0.0, round(rng.uniform(0.0, 0.5), 3)]),
                delta_min_overtemp=rng.choice([10.0, 10.0, 0.0, round(rng.uniform(0.0, 20.0), 2)]),
                delta_fade=rng.choice([5.0, 5.0, 0.0, -1.0, round(rng.uniform(0.1, 10.0), 2)]),
                delta_design=rng.choice([10.0, 10.0, 0.5, round(rng.uniform(1.0, 20.0), 2)]),
                delta_cold_guard=rng.choice([0.3, 0.3, 0.0, round(rng.uniform(0.0, 2.0), 2)]),
                room_target=rng.choice([21.0, 21.0, temperature(18.0, 24.0)]),
                **trim_tuning(),
            )
            flow = maybe(temperature(20.0, 70.0), 0.06)
            ret = maybe(temperature(15.0, 65.0) if flow is None else round(flow - rng.uniform(-2.0, 25.0), 3), 0.06)
            before = dict({"delta_scale": maybe(round(rng.uniform(0.1, 3.0), 4), 0.3)},
                          **trim_before(tuning_kw, 45.0 if flow is None else flow, warm_share=0.08))
            indoor = maybe(temperature(17.0, 25.0), 0.15)
            demand = rng.choice([0.0, 0.1, 0.15, 1.2, 1.5, round(rng.uniform(0.0, 1.5), 3)])
            dt = a_dt()
            base_flow = maybe(temperature(20.0, 70.0), 0.7)
            room = maybe(temperature(17.0, 25.0), 0.75)
        made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState())
        set_state(made_loop.state, before)
        raw = []
        inner = made_loop._delta_trim

        def spy(*args, inner=inner, raw=raw):
            raw.append(inner(*args))
            return raw[-1]

        made_loop._delta_trim = spy
        out = m.ControlOutput()
        delta, expected, trim = made_loop._delta(m.ControlInputs(dt=dt, flow=flow, ret=ret), indoor, demand, out,
                                                 dt, base_flow, room)
        if out.delta_quiet:
            branch(counts, "quiet_" + out.delta_quiet)
        elif "delta_held_cold" in out.flags:
            branch(counts, "held_cold")
        elif raw and raw[0] != 0.0 and trim == 0.0:
            branch(counts, "zeroed_warm")
        elif trim != 0.0:
            branch(counts, "trim_" + ("down" if trim < 0.0 else "up"))
        else:
            branch(counts, "trim_zero")
        rows.append([_stored({name: tuning_kw[name] for name in delta_fields}), _stored(before), flow, ret, indoor,
                     demand, dt, base_flow, room, delta, expected, trim, out.delta_quiet, out.delta_weight,
                     out.delta_readable_from, out.delta_read, out.delta_warming_left, "|".join(out.flags),
                     _stored(get_state(made_loop.state, list(before)))])
    write(step, "delta.csv", ["tuning", "before", "flow", "ret", "indoor", "demand", "dt", "base_flow", "room", "delta",
                              "expected", "trim", "quiet", "weight", "readable_from", "read", "warming_left", "flags",
                              "after"], rows)
    show("delta.csv", counts)

    # --- _watch_radiators: bedöms bara i värmeläge, utan svep och med fullt delta; varningen går upp
    # vid starved_share och ner först under 0,8 av den.
    watch_fields = ["starved_margin", "starved_share", "starved_min_hours", "starved_horizon_seconds", "room_target",
                    "delta_cold_guard", "learn_after_override_seconds"]
    plain = {name: getattr(m.Tuning(), name) for name in watch_fields}
    six = 6.0 * 3600.0
    fixed = [
        # (tuning, since_sweep, (share, weight), starved, mode, indoor, delta, expected, dt, sweeping, delta_weight)
        (plain, 1e9, (0.45, six * 2), True, c.MODE_HEATING, 21.0, 8.0, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.45, six * 2), False, c.MODE_HEATING, 21.0, 8.0, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.39, six * 2), True, c.MODE_HEATING, 21.0, 8.0, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.5, six * 2), False, c.MODE_HEATING, 20.0, 5.6, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.5, six * 2), False, c.MODE_HEATING, 20.7, 5.6, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.9, six - 1.0), False, c.MODE_HEATING, 20.0, 2.0, 8.0, 0.0, False, 1.0),
        (plain, 1e9, (None, 0.0), False, c.MODE_HEATING, 20.0, 2.0, 8.0, 30.0, False, 1.0),
        (plain, 7200.0, (0.6, six * 2), False, c.MODE_HEATING, 20.0, 2.0, 8.0, 30.0, False, 1.0),
        (plain, 7199.0, (0.6, six * 2), False, c.MODE_HEATING, 20.0, 2.0, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.6, six * 2), False, c.MODE_HEATING, 20.0, 2.0, 8.0, 30.0, True, 1.0),
        (plain, 1e9, (0.6, six * 2), False, c.MODE_HEATING, 20.0, 2.0, 8.0, 30.0, False, 0.999),
        (plain, 1e9, (0.6, six * 2), False, c.MODE_HEATING, None, 2.0, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.6, six * 2), False, c.MODE_HEATING, 20.0, None, 8.0, 30.0, False, 1.0),
        (plain, 1e9, (0.6, six * 2), False, c.MODE_HEATING, 20.0, 2.0, None, 30.0, False, 1.0),
        (plain, 1e9, (0.6, six * 2), False, c.MODE_SUMMER, 20.0, 2.0, 8.0, 30.0, False, 1.0),
    ]
    modes = [c.MODE_HEATING] * 6 + [c.MODE_SUMMER, c.MODE_WARM, c.MODE_COAST, c.MODE_HOLD, c.MODE_OFF]
    counts = {}
    rows = []
    for i in range(600):
        if i < len(fixed):
            tuning_kw, since_sweep, (share, weight), starved, mode, indoor, delta, expected, dt, sweeping, \
                delta_weight = fixed[i]
        else:
            tuning_kw = dict(
                starved_margin=rng.choice([0.3, 0.3, 0.0, round(rng.uniform(0.0, 0.8), 3)]),
                starved_share=rng.choice([0.5, 0.5, 0.0, 1.0, round(rng.uniform(0.05, 1.0), 3)]),
                starved_min_hours=rng.choice([6.0, 6.0, 0.0, round(rng.uniform(0.0, 24.0), 2)]),
                starved_horizon_seconds=rng.choice([3.0 * 24.0 * 3600.0, 3.0 * 24.0 * 3600.0, 0.0,
                                                    round(rng.uniform(600.0, 5.0 * 24.0 * 3600.0), 1)]),
                room_target=rng.choice([21.0, 21.0, temperature(18.0, 24.0)]),
                delta_cold_guard=rng.choice([0.3, 0.3, 0.0, round(rng.uniform(0.0, 2.0), 2)]),
                learn_after_override_seconds=rng.choice([7200.0, 7200.0, 0.0, round(rng.uniform(0.0, 14400.0), 1)]),
            )
            since_sweep = rng.choice([1e9, 1e9, 1e9, tuning_kw["learn_after_override_seconds"],
                                      round(rng.uniform(0.0, 20000.0), 1)])
            line = tuning_kw["starved_share"]
            share = rng.choice([None, round(rng.uniform(0.0, 1.0), 4), round(rng.uniform(0.8 * line, line), 4),
                                round(rng.uniform(0.8 * line, line), 4), line, 0.8 * line])
            hours = tuning_kw["starved_min_hours"]
            weight = rng.choice([0.0, hours * 3600.0, round(rng.uniform(0.0, 2.0 * hours * 3600.0 + 3600.0), 1),
                                 round(rng.uniform(hours * 3600.0, 3.0 * hours * 3600.0 + 3600.0), 1)])
            starved = rng.random() < 0.45
            mode = rng.choice(modes)
            target, guard = tuning_kw["room_target"], tuning_kw["delta_cold_guard"]
            indoor = maybe(rng.choice([target - guard, round(target - rng.uniform(0.0, 3.0), 2),
                                       round(target + rng.uniform(-1.0, 2.0), 2)]), 0.1)
            expected = maybe(rng.choice([1.0, 10.0, round(rng.uniform(1.0, 15.0), 3)]), 0.1)
            margin = tuning_kw["starved_margin"]
            delta = maybe(rng.choice([
                round(rng.uniform(-2.0, 20.0), 3),
                None if expected is None else expected - margin * expected,
                None if expected is None else round(expected * (1.0 - margin) - rng.uniform(0.0, 3.0), 3),
            ]), 0.1)
            dt = a_dt()
            sweeping = rng.random() < 0.1
            delta_weight = rng.choice([1.0, 1.0, 1.0, 0.0, 0.999, round(rng.uniform(0.0, 1.0), 3)])
        made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState())
        state = made_loop.state
        state.seconds_since_sweep = since_sweep
        state.starved = est.DutyWatch.from_dict({"share": share, "weight": weight},
                                                tuning_kw["starved_horizon_seconds"])
        made_loop._starved = starved
        watch_before = state.starved.as_dict()
        judged = []
        inner = state.starved.update

        def spy(held, dt, inner=inner, judged=judged):
            judged.append(held)
            inner(held, dt)

        state.starved.update = spy
        out = m.ControlOutput()
        out.delta_weight = delta_weight
        made_loop._watch_radiators(mode, indoor, delta, expected, dt, out, sweeping)
        held = judged[0]
        line = tuning_kw["starved_share"]
        if share is not None and 0.8 * line <= share < line:
            branch(counts, "between_" + ("kept" if starved and out.radiators_starved
                                          else "up_stays_down" if not starved and not out.radiators_starved
                                          else "changed"))
        branch(counts, "judged_" + ("none" if held is None else str(held).lower()))
        branch(counts, f"starved_{str(starved).lower()}_to_{str(out.radiators_starved).lower()}")
        rows.append([_stored({name: tuning_kw[name] for name in watch_fields}), since_sweep, _stored(watch_before),
                     _bool(starved), mode, indoor, delta, expected, dt, _bool(sweeping), delta_weight, _bool(held),
                     _bool(out.radiators_starved), "|".join(out.flags), _stored(state.starved.as_dict()),
                     _bool(made_loop._starved)])
    write(step, "watch_radiators.csv", ["tuning", "seconds_since_sweep", "watch_before", "starved_before", "mode",
                                        "indoor", "delta", "expected", "dt", "sweeping", "delta_weight", "judged_held",
                                        "radiators_starved", "flags", "watch_after", "starved_after"], rows)
    show("watch_radiators.csv", counts)


def mode_and_heat_source(core, rng) -> None:
    """Steg 15, läget och värmekällan: _mode, _cold_boiler_cap, _boiler_overheated, _heat_source
    och _burner_for_heating."""
    m, c = core.model, core.controller
    step = "15-laget-varmekallan"

    def maybe(value, chance=0.2):
        return None if rng.random() < chance else value

    def temperature(low, high):
        return round(rng.uniform(low, high), rng.choice([1, 2, 6]))

    def a_dt():
        return rng.choice([30.0, 30.0, 30.0, 0.0, 600.0, round(rng.uniform(1.0, 3600.0), 3)])

    def branch(counts, key):
        counts[key] = counts.get(key, 0) + 1

    def show(name, counts):
        print(f"{step}/{name}: " + ", ".join(f"{key} {count}" for key, count in sorted(counts.items())))

    def loop(**tuning):
        return c.HeatingController(m.Tuning(**tuning), m.LearnedState())

    # --- _mode: av, sommar (in, kvar, ut, och inom hysteresen åt båda hållen), varmt (likadant,
    # och inget inne som släpper det), failsafe mot håll (precis på gränsen också) och värme.
    mode_fields = ["summer_outdoor", "summer_hysteresis", "room_target", "warm_stop_margin", "warm_stop_hysteresis",
                   "failsafe_after_seconds"]
    plain = {name: getattr(m.Tuning(), name) for name in mode_fields}
    fixed = [
        # (tuning, (summer_before, warm_before), enabled, outdoor_summer, indoor, flow, seconds_without_flow)
        (plain, (True, True), False, 20.0, 23.0, 45.0, 0.0),
        (plain, (False, False), True, 17.0, 21.0, 45.0, 0.0),
        (plain, (False, False), True, 16.99, 21.0, 45.0, 0.0),
        (plain, (True, False), True, 15.5, 21.0, 45.0, 0.0),
        (plain, (True, False), True, 15.49, 21.0, 45.0, 0.0),
        (plain, (False, False), True, 16.0, 21.0, 45.0, 0.0),
        (plain, (False, False), True, 10.0, 22.0, 45.0, 0.0),
        (plain, (False, False), True, 10.0, 22.01, 45.0, 0.0),
        (plain, (False, True), True, 10.0, 21.7, 45.0, 0.0),
        (plain, (False, True), True, 10.0, 21.69, 45.0, 0.0),
        (plain, (False, True), True, 10.0, None, 45.0, 0.0),
        (plain, (False, False), True, 10.0, 21.0, None, 600.0),
        (plain, (False, False), True, 10.0, 21.0, None, 599.0),
        (plain, (False, False), True, 10.0, None, None, 0.0),
        (dict(plain, failsafe_after_seconds=0.0), (False, False), True, 10.0, 21.0, None, 0.0),
    ]
    counts = {}
    rows = []
    for i in range(600):
        if i < len(fixed):
            tuning_kw, (summer_before, warm_before), enabled, outdoor_summer, indoor, flow, without = fixed[i]
        else:
            tuning_kw = dict(
                summer_outdoor=rng.choice([17.0, 17.0, round(rng.uniform(10.0, 22.0), 1)]),
                summer_hysteresis=rng.choice([1.5, 1.5, 0.0, round(rng.uniform(0.0, 4.0), 2)]),
                room_target=rng.choice([21.0, 21.0, temperature(18.0, 24.0)]),
                warm_stop_margin=rng.choice([1.0, 1.0, 0.0, round(rng.uniform(0.0, 3.0), 2)]),
                warm_stop_hysteresis=rng.choice([0.3, 0.3, 0.0, round(rng.uniform(0.0, 1.5), 2)]),
                failsafe_after_seconds=rng.choice([600.0, 600.0, 0.0, round(rng.uniform(0.0, 3600.0), 1)]),
            )
            summer_before, warm_before = rng.random() < 0.35, rng.random() < 0.4
            enabled = rng.random() < 0.9
            s, h = tuning_kw["summer_outdoor"], tuning_kw["summer_hysteresis"]
            outdoor_summer = rng.choice([s, s - h, round(s - rng.uniform(0.0, h), 3), round(s - rng.uniform(0.0, h), 3),
                                         round(s + rng.uniform(-h - 2.0, 2.0), 2), round(rng.uniform(-20.0, 25.0), 2),
                                         round(rng.uniform(-20.0, s - h), 2), round(rng.uniform(-20.0, s - h), 2)])
            line = tuning_kw["room_target"] + tuning_kw["warm_stop_margin"]
            hy = tuning_kw["warm_stop_hysteresis"]
            indoor = maybe(rng.choice([line, line - hy, round(line - rng.uniform(0.0, hy), 3),
                                       round(line + rng.uniform(-hy - 1.0, 1.0), 2), temperature(17.0, 25.0)]), 0.12)
            flow = maybe(temperature(20.0, 70.0), 0.35)
            limit = tuning_kw["failsafe_after_seconds"]
            without = rng.choice([0.0, limit, limit, round(rng.uniform(0.0, 2.0 * limit + 60.0), 1), 1e6])
        made_loop = loop(**tuning_kw)
        state = made_loop.state
        state.summer_stopped, state.warm_stopped = summer_before, warm_before
        state.seconds_without_flow = without
        before = {"summer_stopped": summer_before, "warm_stopped": warm_before}
        mode = made_loop._mode(m.ControlInputs(dt=30.0, heating_enabled=enabled, flow=flow), outdoor_summer, indoor)
        after = {"summer_stopped": state.summer_stopped, "warm_stopped": state.warm_stopped}
        branch(counts, "mode_" + mode)
        s, h = tuning_kw["summer_outdoor"], tuning_kw["summer_hysteresis"]
        if enabled:
            branch(counts, f"summer_{str(summer_before).lower()}_to_{str(after['summer_stopped']).lower()}")
            if s - h <= outdoor_summer < s:
                branch(counts, "summer_band_" + ("kept" if summer_before else "not_entered"))
            if not after["summer_stopped"]:
                if indoor is None:
                    branch(counts, f"warm_none_{str(warm_before).lower()}")
                else:
                    branch(counts, f"warm_{str(warm_before).lower()}_to_{str(after['warm_stopped']).lower()}")
                    line = tuning_kw["room_target"] + tuning_kw["warm_stop_margin"]
                    low = line - tuning_kw["warm_stop_hysteresis"]
                    if low <= indoor <= line:
                        branch(counts, "warm_band_" + ("kept" if warm_before else "not_entered"))
                if mode in (c.MODE_FAILSAFE, c.MODE_HOLD) and without == tuning_kw["failsafe_after_seconds"]:
                    branch(counts, "failsafe_at_limit")
        rows.append([_stored(dict(tuning_kw)), _stored(before), _bool(enabled), outdoor_summer, indoor, flow, without,
                     mode, _stored(after)])
    write(step, "mode.csv", ["tuning", "before", "heating_enabled", "outdoor_summer", "indoor", "flow",
                             "seconds_without_flow", "mode", "after"], rows)
    show("mode.csv", counts)

    # --- _cold_boiler_cap: avstängd (band <= 0), inget att räkna på, över bandet (precis på också),
    # under taket (golvet), linjen emellan, och ett golv utanför 0-100.
    fixed = [
        # (band, clearance, floor, boiler, limit)
        (10.0, 2.0, 10.0, 72.0, 60.0), (10.0, 2.0, 10.0, 71.99, 60.0), (10.0, 2.0, 10.0, 62.0, 60.0),
        (10.0, 2.0, 10.0, 67.0, 60.0), (10.0, 2.0, 10.0, 55.0, 60.0), (10.0, 2.0, 10.0, None, 60.0),
        (10.0, 2.0, 10.0, 70.0, None), (0.0, 2.0, 10.0, 55.0, 60.0), (-1.0, 2.0, 10.0, 55.0, 60.0),
        (10.0, 2.0, -5.0, 55.0, 60.0), (10.0, 2.0, 120.0, 55.0, 60.0), (10.0, 2.0, 0.0, 67.0, 60.0),
        (10.0, 2.0, 100.0, 67.0, 60.0), (10.0, 0.0, 10.0, 60.0, 60.0),
    ]
    counts = {}
    rows = []
    for i in range(400):
        if i < len(fixed):
            band, clearance, floor, boiler, limit = fixed[i]
        else:
            band = rng.choice([10.0, 10.0, 10.0, 0.0, -1.0, round(rng.uniform(0.5, 20.0), 2)])
            clearance = rng.choice([2.0, 2.0, 0.0, round(rng.uniform(-2.0, 5.0), 2)])
            floor = rng.choice([10.0, 10.0, 0.0, -5.0, 120.0, 100.0, round(rng.uniform(0.0, 100.0), 1)])
            limit = maybe(rng.choice([60.0, 65.0, round(rng.uniform(40.0, 80.0), 1)]), 0.08)
            if limit is None:
                boiler = maybe(temperature(20.0, 95.0), 0.1)
            else:
                start = limit + clearance
                boiler = maybe(rng.choice([start + band, start, round(start + rng.uniform(-5.0, band + 5.0), 2),
                                           round(start + rng.uniform(0.0, band), 3), temperature(20.0, 95.0)]), 0.08)
        made_loop = loop(cold_boiler_band=band, cold_boiler_clearance=clearance, cold_boiler_floor=floor)
        cap = made_loop._cold_boiler_cap(m.ControlInputs(dt=30.0, boiler=boiler, boiler_limit=limit))
        if band <= 0.0:
            branch(counts, "none_band")
        elif boiler is None or limit is None:
            branch(counts, "none_missing")
        else:
            over = float(boiler) - float(limit) - clearance
            if over == band:
                branch(counts, "over_exactly_band")
            if over >= band:
                branch(counts, "none_over_band")
            elif over <= 0.0:
                branch(counts, "floor" + ("_below_zero" if over < 0.0 else "_at_zero"))
            else:
                branch(counts, "line")
            if over < band:
                if floor < 0.0 or floor > 100.0:
                    branch(counts, "floor_outside")
                elif floor == 0.0:
                    branch(counts, "floor_zero")
        rows.append([band, clearance, floor, boiler, limit, cap])
    write(step, "cold_boiler_cap.csv", ["cold_boiler_band", "cold_boiler_clearance", "cold_boiler_floor", "boiler",
                                        "boiler_limit", "cap"], rows)
    show("cold_boiler_cap.csv", counts)

    # --- _boiler_overheated: slår till vid boiler_max, släpper vid release, hålls emellan och
    # genom en saknad givare.
    fixed = [
        (95.0, 85.0, False, 95.0), (95.0, 85.0, False, 94.99), (95.0, 85.0, True, 85.0), (95.0, 85.0, True, 85.01),
        (95.0, 85.0, True, 90.0), (95.0, 85.0, False, 90.0), (95.0, 85.0, True, None), (95.0, 85.0, False, None),
        (85.0, 95.0, True, 90.0), (85.0, 95.0, False, 90.0),
    ]
    counts = {}
    rows = []
    for i in range(300):
        if i < len(fixed):
            high, release, before, boiler = fixed[i]
        else:
            high = rng.choice([95.0, 95.0, round(rng.uniform(70.0, 100.0), 1)])
            release = rng.choice([85.0, 85.0, high, round(rng.uniform(60.0, high), 1), round(rng.uniform(60.0, 105.0), 1)])
            before = rng.random() < 0.5
            low, top = min(high, release), max(high, release)
            boiler = maybe(rng.choice([high, release, round(rng.uniform(low, top), 2), round(rng.uniform(low, top), 2),
                                       temperature(50.0, 105.0)]), 0.15)
        made_loop = loop(boiler_max=high, boiler_max_release=release)
        made_loop.state.boiler_overheated = before
        result = made_loop._boiler_overheated(boiler)
        if boiler is None:
            branch(counts, f"none_{str(before).lower()}")
        elif boiler >= high:
            branch(counts, "trip" + ("_exact" if boiler == high else ""))
        elif boiler <= release:
            branch(counts, "release" + ("_exact" if boiler == release else ""))
        else:
            branch(counts, f"held_{str(before).lower()}")
        rows.append([high, release, _bool(before), boiler, _bool(result)])
    write(step, "boiler_overheated.csv", ["boiler_max", "boiler_max_release", "before", "boiler", "result"], rows)
    show("boiler_overheated.csv", counts)

    # --- _heat_source och _burner_for_heating.
    source_fields = ["heat_source_off_in_summer", "heat_source_off_when_idle", "heat_source_off_when_disabled",
                     "heat_source_off_delay"]
    defaults = {name: getattr(m.Tuning(), name) for name in source_fields}
    modes = [c.MODE_OFF, c.MODE_SUMMER, c.MODE_WARM, c.MODE_HEATING, c.MODE_HOLD, c.MODE_FAILSAFE, c.MODE_COAST,
             c.MODE_EMERGENCY]
    stopped_modes = (c.MODE_OFF, c.MODE_SUMMER, c.MODE_WARM, c.MODE_COAST)

    def source_tuning():
        return dict(
            heat_source_off_in_summer=rng.random() < 0.6,
            heat_source_off_when_idle=rng.random() < 0.5,
            heat_source_off_when_disabled=rng.random() < 0.6,
            heat_source_off_delay=rng.choice([1800.0, 1800.0, 0.0, round(rng.uniform(0.0, 3600.0), 1)]),
        )

    def may_switch_off(tuning_kw, mode):
        return ((mode == c.MODE_SUMMER and tuning_kw["heat_source_off_in_summer"])
                or (mode == c.MODE_WARM and tuning_kw["heat_source_off_when_idle"])
                or (mode == c.MODE_OFF and tuning_kw["heat_source_off_when_disabled"]))

    idle = dict(defaults, heat_source_off_when_idle=True)
    fixed = [
        # (tuning, seconds_stopped, overheated, mode, dt, hot_water, sweeping, coast_burner)
        (defaults, 0.0, True, c.MODE_HEATING, 30.0, True, True, False),
        (defaults, 0.0, False, c.MODE_HEATING, 30.0, True, True, False),
        (defaults, 0.0, False, c.MODE_HEATING, 30.0, True, False, False),
        (defaults, 1e6, False, c.MODE_SUMMER, 30.0, True, False, False),
        (defaults, 1770.0, False, c.MODE_SUMMER, 30.0, False, False, False),
        (defaults, 1769.0, False, c.MODE_SUMMER, 30.0, False, False, False),
        (defaults, 1771.0, False, c.MODE_SUMMER, 30.0, False, False, False),
        (defaults, 1e6, False, c.MODE_WARM, 30.0, False, False, False),
        (idle, 1e6, False, c.MODE_WARM, 30.0, False, False, False),
        (defaults, 0.0, False, c.MODE_WARM, 30.0, False, False, True),
        (defaults, 0.0, False, c.MODE_WARM, 30.0, True, False, True),
        (defaults, 0.0, False, c.MODE_COAST, 30.0, False, False, True),
        (defaults, 0.0, False, c.MODE_COAST, 30.0, False, False, False),
        (defaults, 0.0, False, c.MODE_COAST, 30.0, True, False, True),
        (dict(defaults, heat_source_off_when_disabled=False), 1e6, False, c.MODE_OFF, 30.0, False, False, False),
        (defaults, 1e6, False, c.MODE_OFF, 30.0, False, False, False),
        (dict(defaults, heat_source_off_in_summer=False), 1e6, False, c.MODE_SUMMER, 30.0, False, False, False),
        (defaults, 1e6, False, c.MODE_EMERGENCY, 30.0, False, False, False),
        (defaults, 1e6, False, c.MODE_FAILSAFE, 30.0, False, False, False),
        (dict(defaults, heat_source_off_delay=0.0), 0.0, False, c.MODE_SUMMER, 0.0, False, False, False),
    ]
    counts = {}
    rows = []
    for i in range(800):
        if i < len(fixed):
            tuning_kw, before, overheated, mode, dt, hot_water, sweeping, coast_burner = fixed[i]
        else:
            tuning_kw = source_tuning()
            dt = a_dt()
            delay = tuning_kw["heat_source_off_delay"]
            before = rng.choice([0.0, delay, delay - dt, delay - dt, round(rng.uniform(0.0, 2.0 * delay + 60.0), 1), 1e6])
            overheated, sweeping = rng.random() < 0.1, rng.random() < 0.1
            hot_water, coast_burner = rng.random() < 0.25, rng.random() < 0.35
            mode = rng.choice(modes)
        made_loop = loop(**tuning_kw)
        state = made_loop.state
        state.seconds_stopped = before
        state.boiler_overheated = overheated
        out = m.ControlOutput()
        out.coast_burner = coast_burner
        result = made_loop._heat_source(mode, dt, hot_water, out, sweeping)
        stopped = mode in stopped_modes
        if overheated:
            branch(counts, "overheat")
        elif sweeping:
            branch(counts, "sweep")
        else:
            if hot_water:
                branch(counts, f"hot_water_for_{str(out.burner_for_hot_water).lower()}")
            if mode == c.MODE_WARM and coast_burner:
                branch(counts, "warm_coast_burner" + ("_hot_water" if hot_water else ""))
            elif mode == c.MODE_COAST:
                branch(counts, f"coast_{str(not coast_burner).lower()}")
            elif not stopped:
                branch(counts, "running")
            elif not may_switch_off(tuning_kw, mode):
                branch(counts, f"kept_on_{mode}")
            else:
                after = state.seconds_stopped
                branch(counts, f"may_off_{mode}_" + ("waiting" if after < tuning_kw["heat_source_off_delay"] else "off"))
                if after == tuning_kw["heat_source_off_delay"]:
                    branch(counts, "at_delay")
                elif before < tuning_kw["heat_source_off_delay"] < after:
                    branch(counts, "crossed_delay")
        rows.append([_stored(dict(tuning_kw)), before, _bool(overheated), mode, dt, _bool(hot_water), _bool(sweeping),
                     _bool(coast_burner), _bool(result), _bool(out.burner_for_hot_water), "|".join(out.flags),
                     state.seconds_stopped])
    write(step, "heat_source.csv", ["tuning", "seconds_stopped_before", "boiler_overheated", "mode", "dt", "hot_water",
                                    "sweeping", "coast_burner", "result", "burner_for_hot_water", "flags",
                                    "seconds_stopped_after"], rows)
    show("heat_source.csv", counts)

    # _burner_for_heating får stopped som argument, så också fall där det inte stämmer med läget.
    fixed = [
        (defaults, 1800.0, c.MODE_SUMMER, False, True), (defaults, 1799.0, c.MODE_SUMMER, False, True),
        (defaults, 1e6, c.MODE_HEATING, False, True), (defaults, 1e6, c.MODE_SUMMER, False, False),
        (defaults, 0.0, c.MODE_COAST, True, False), (defaults, 0.0, c.MODE_COAST, False, True),
        (defaults, 0.0, c.MODE_WARM, True, True), (defaults, 1e6, c.MODE_WARM, False, True),
        (idle, 1e6, c.MODE_WARM, False, True), (defaults, 1e6, c.MODE_OFF, False, True),
    ]
    counts = {}
    rows = []
    for i in range(400):
        if i < len(fixed):
            tuning_kw, seconds, mode, coast_burner, stopped = fixed[i]
        else:
            tuning_kw = source_tuning()
            delay = tuning_kw["heat_source_off_delay"]
            seconds = rng.choice([0.0, delay, delay, round(rng.uniform(0.0, 2.0 * delay + 60.0), 1), 1e6])
            mode = rng.choice(modes)
            coast_burner, stopped = rng.random() < 0.35, rng.random() < 0.6
        made_loop = loop(**tuning_kw)
        made_loop.state.seconds_stopped = seconds
        out = m.ControlOutput()
        out.coast_burner = coast_burner
        result = made_loop._burner_for_heating(mode, out, stopped)
        if mode == c.MODE_COAST:
            branch(counts, f"coast_{str(result).lower()}")
        elif not stopped:
            branch(counts, "not_stopped" + ("_but_stop_mode" if mode in stopped_modes else ""))
        elif mode == c.MODE_WARM and coast_burner:
            branch(counts, "warm_coast_burner")
        elif not may_switch_off(tuning_kw, mode):
            branch(counts, "kept_on" + ("_running_mode" if mode not in stopped_modes else ""))
        else:
            branch(counts, f"may_off_{str(result).lower()}" + ("_at_delay" if seconds == tuning_kw["heat_source_off_delay"]
                                                                 else ""))
        rows.append([_stored(dict(tuning_kw)), seconds, mode, _bool(coast_burner), _bool(stopped), _bool(result)])
    write(step, "burner_for_heating.csv", ["tuning", "seconds_stopped", "mode", "coast_burner", "stopped", "result"],
          rows)
    show("burner_for_heating.csv", counts)


def rollout(core, rng) -> None:
    """Steg 16, utrullningen: _coast, _distrust, _learning_hold, _observe_house, _night och _conditions."""
    from weatherhouse import WeatherHouse  # i heating/tests, på sys.path sedan load_core
    m, c, th = core.model, core.controller, core.thermal
    Conditions = th.Conditions
    step = "16-utrullningen"

    def maybe(value, chance=0.2):
        return None if rng.random() < chance else value

    def temperature(low, high):
        return round(rng.uniform(low, high), rng.choice([1, 2, 6]))

    def a_dt():
        return rng.choice([30.0, 30.0, 30.0, 0.0, -30.0, 600.0, round(rng.uniform(1.0, 3600.0), 3)])

    def branch(counts, key):
        counts[key] = counts.get(key, 0) + 1

    def show(name, counts):
        print(f"{step}/{name}: " + ", ".join(f"{key} {count}" for key, count in sorted(counts.items())))

    # --- husen: test_coast.py:s taught_house, skriven om här (test_coast kör load() när den importeras)
    # och med varje observe sparad som steg 7:s op. halv och halv3 tar 1800 s-prov, för med 600 s blir
    # observed_hours aldrig precis 12.5 eller 13.5 (summan av sjättedelar).
    def taught_house(hours=24 * 25, noise=0.0, house=None, seconds=600.0):
        noise_rng = random.Random(4)
        house = house or WeatherHouse()
        model, ops = th.HouseModel(), []
        elapsed = 0.0
        while elapsed < hours * 3600.0:
            hour = elapsed / 3600.0
            outdoor = -5.0 + 10.0 * math.sin(2 * math.pi * hour / 36.0)
            wind = 3.0 + 3.0 * math.sin(2 * math.pi * hour / 17.0)
            bearing = (hour * 53.0) % 360.0
            sun = max(0.0, math.sin(2 * math.pi * (hour % 24) / 24.0 - 1.2)) * 0.8
            delta = 4.0 if (hour % 24) < 14 else 0.0
            house.step(seconds / 3600.0, outdoor, wind, bearing, sun, delta)
            reading = house.indoor + (noise_rng.uniform(-noise, noise) if noise else 0.0)
            model.observe(indoor=reading, conditions=Conditions(outdoor=outdoor, wind=wind, bearing=bearing, sun=sun),
                          delta=delta, dt=seconds, sample_seconds=seconds)
            ops.append("observe," + _floats([reading, outdoor, wind, bearing, sun, None, delta, seconds, seconds, None])
                       + ",")
            elapsed += seconds
        return model, ops

    recipes = {
        "lard": taught_house(),
        "brus": taught_house(hours=24 * 3, noise=0.15),
        "kort": taught_house(hours=24),
        "halv": taught_house(hours=13.0, seconds=1800.0),
        "halv3": taught_house(hours=14.0, seconds=1800.0),
        "varm": taught_house(hours=24 * 30, seconds=1800.0,
                             house=WeatherHouse(g0=0.001, g_wind=0.0, g_north=0.0, internal=1.2)),
    }
    write(step, "coast_houses.csv", ["name", "ops"], [[name, "|".join(ops)] for name, (_, ops) in recipes.items()])
    houses = {name: model for name, (model, _) in recipes.items()}
    houses["ny"] = th.HouseModel()
    names = list(houses)
    for name in names:
        made_loop = c.HeatingController(m.Tuning(), m.LearnedState(), house=houses[name])
        print(f"{step}/hus {name}: observed_hours {houses[name].observed_hours!r}, "
              f"cools {houses[name].cools_when_left_alone()}, floor {houses[name].loss_on_floor()}, "
              f"_distrust() {made_loop._distrust()!r}")
    assert houses["halv"].observed_hours == 12.5 and houses["halv3"].observed_hours == 13.5
    assert houses["varm"].cools_when_left_alone() is False or houses["varm"].loss_on_floor()

    def table_ops(outdoor, hour, cell_hours):
        """Kurvtabellen som (ute, behov, vind, klocka, timmar): tom, slumpad, och ibland timmar i
        just den cell som (ute, klocka) hamnar i - under, på och över cell_hours."""
        if rng.random() < 0.3:
            return []
        entries = []
        for _ in range(rng.randint(0, 4)):
            entries.append((temperature(-25.0, 15.0), round(rng.uniform(0.0, 30.0), 2), maybe(round(rng.uniform(0.0, 10.0), 1)),
                            maybe(round(rng.uniform(0.0, 24.0), 2)), rng.choice([0.5, 1.0, round(rng.uniform(0.0, 4.0), 2)])))
        if outdoor is not None and rng.random() < 0.7:
            total = rng.choice([cell_hours, cell_hours - 0.5, cell_hours + 0.5, 0.5, round(rng.uniform(0.0, cell_hours + 2.0), 2)])
            first = round(total / 2.0, 2) if rng.random() < 0.35 else total
            for hours in ([first, total - first] if first != total else [total]):
                entries.append((outdoor, round(rng.uniform(0.0, 30.0), 2), maybe(round(rng.uniform(0.0, 10.0), 1)),
                                hour, hours))
        if rng.random() < 0.05:
            entries.append((temperature(-25.0, 15.0), 5.0, None, None, 0.0))  # timmar 0: läggs inte till
        rng.shuffle(entries)
        return entries

    def a_table(entries):
        table = core.points.PointTable()
        for outdoor, need, wind, hour, hours in entries:
            table.add(outdoor=outdoor, need=need, wind=wind, hour=hour, hours=hours)
        return table

    def table_cell(entries):
        return "/".join(":".join(_num(v) for v in entry) for entry in entries)

    def hold_before(outdoor, hour, tuning_kw, points):
        """Väntans tillstånd före: noll, på maxtiden, strax under, slump; cellen ingen, samma eller en annan."""
        top = tuning_kw["coast_learning_hold_max_seconds"]
        seconds = rng.choice([0.0, 0.0, top, max(0.0, top - 30.0), round(rng.uniform(0.0, top + 600.0), 1)])
        same = None if outdoor is None else points.key_at(outdoor, hour)
        other = points.key_at(temperature(-25.0, 15.0), maybe(round(rng.uniform(0.0, 24.0), 2)))
        cell = rng.choice([None, None, same, same, same, other])
        hours = 0.0 if outdoor is None else points.hours_at(outdoor, hour)
        base = rng.choice([0.0, hours, hours - tuning_kw["coast_learning_hold_seconds"] / 3600.0,
                           round(hours - rng.uniform(0.0, 4.0), 2), round(rng.uniform(0.0, 8.0), 2)])
        return seconds, cell, base

    def an_hour():
        return maybe(rng.choice([round(rng.uniform(0.0, 24.0), 2), round(rng.uniform(0.0, 24.0), 2), 0.0, 23.99, 8.0,
                                 16.0, round(rng.uniform(-24.0, 48.0), 2)]), 0.25)

    # --- _coast
    coast_modes = [c.MODE_HEATING] * 7 + [c.MODE_WARM, c.MODE_SUMMER, c.MODE_OFF, c.MODE_COAST, c.MODE_HOLD,
                                          c.MODE_FAILSAFE]

    def flat_forecast(outdoor, hours=12, wind=0.0, bearing=0.0, sun=0.0):
        return [(float(h), Conditions(outdoor=outdoor, wind=wind, bearing=bearing, sun=sun)) for h in range(hours + 1)]

    def turning_forecast(cold=8.0, warm=16.0, at=2, hours=12):
        return [(float(h), Conditions(outdoor=cold if h < at else warm, wind=0.0, bearing=0.0,
                                      sun=0.0 if h < at else 0.7)) for h in range(hours + 1)]

    def a_forecast(outdoor, horizon):
        kind = rng.choice(["empty", "flat", "flat", "flat", "turning", "turning", "slots", "slots", "beyond",
                           "beyond_only", "zero"])
        base = outdoor if outdoor is not None and rng.random() < 0.6 else temperature(-20.0, 12.0)
        if kind == "empty":
            return kind, []
        if kind == "flat":
            return kind, flat_forecast(base, rng.choice([12, 12, 6, 24, rng.randint(1, 30)]),
                                       wind=rng.choice([0.0, 0.0, round(rng.uniform(0.0, 10.0), 1)]),
                                       bearing=rng.choice([0.0, round(rng.uniform(0.0, 360.0), 1)]),
                                       sun=rng.choice([0.0, 0.0, round(rng.uniform(0.0, 1.0), 2)]))
        if kind == "turning":
            return kind, turning_forecast(cold=base, warm=round(base + rng.uniform(2.0, 20.0), 1),
                                          at=rng.randint(0, 12), hours=rng.choice([12, 12, 6, 24]))
        if kind == "zero":
            return kind, [(rng.choice([0.0, -1.0]), Conditions(outdoor=base, wind=0.0, bearing=0.0, sun=0.0))]
        count = rng.randint(1, 13)
        first = rng.choice([0.0, 0.0, 1.0, 0.5])
        if kind == "beyond_only":
            first = horizon + rng.choice([0.5, 1.0, round(rng.uniform(0.01, 12.0), 2)])
        span = rng.uniform(first + 1.0, first + 24.0)
        clock = maybe(round(rng.uniform(0.0, 24.0), 2), 0.5)
        slots = []
        for i in range(count):
            offset = round(first + (span - first) * i / max(1, count - 1), 2)
            when = None if clock is None else round((clock + offset) % 24.0, 2)
            slots.append((offset, Conditions(outdoor=round(base + rng.uniform(-4.0, 6.0), 1),
                                             wind=maybe(round(rng.uniform(0.0, 12.0), 1)),
                                             bearing=maybe(round(rng.uniform(0.0, 360.0), 1)),
                                             sun=rng.choice([0.0, round(rng.uniform(0.0, 1.0), 2)]), hour=when)))
        if kind == "beyond":
            slots.append((horizon + round(rng.uniform(0.5, 6.0), 2), Conditions(outdoor=base, wind=None, bearing=None,
                                                                              sun=0.0)))
        return kind, slots

    def coast_tuning(house):
        return dict(
            coast_enabled=rng.random() < 0.93,
            coast_horizon_hours=rng.choice([12.0, 12.0, 12.0, 6.0, 3.0, 24.0, round(rng.uniform(0.5, 24.0), 2)]),
            coast_confidence=rng.choice([2.0, 2.0, 0.0, 1.0, round(rng.uniform(0.0, 3.0), 2)]),
            coast_learning_band=rng.choice([1.0, 1.0, 0.0, round(rng.uniform(0.0, 2.0), 2)]),
            coast_comfort_band=rng.choice([1.0, 1.0, 0.0, round(rng.uniform(0.0, 2.0), 2)]),
            coast_bridge_only=rng.random() < 0.6,
            coast_learning_hold_seconds=rng.choice([7200.0, 7200.0, 0.0, round(rng.uniform(0.0, 14400.0), 1)]),
            coast_learning_hold_max_seconds=rng.choice([21600.0, 21600.0, 0.0, round(rng.uniform(0.0, 43200.0), 1)]),
            coast_learning_cell_hours=rng.choice([6.0, 6.0, 0.0, round(rng.uniform(0.0, 12.0), 2)]),
            coast_unknown_reach=rng.choice([1.0, 1.0, 0.0, -1.0, round(rng.uniform(0.01, 5.0), 3)]),
            coast_min_hours=rng.choice([48.0, 48.0, 48.0, 0.0, 12.0, 24.0, 600.0, round(rng.uniform(0.0, 700.0), 1)])
            if house not in ("lard", "brus") or rng.random() < 0.15 else 48.0,
            shunt_lead_seconds=rng.choice([600.0, 600.0, 60.0, 0.0, 7200.0, 36000.0, round(rng.uniform(0.0, 20000.0), 1)]),
            coast_hysteresis_seconds=rng.choice([900.0, 900.0, 0.0, round(rng.uniform(0.0, 3600.0), 1)]),
            heat_source_off_delay=rng.choice([1800.0, 1800.0, 0.0, round(rng.uniform(0.0, 7200.0), 1)]),
            room_target=rng.choice([21.0, 21.0, temperature(19.0, 23.0)]),
        )

    counts, rows, redrawn = {}, [], 0
    weights = ["lard"] * 6 + ["brus"] * 4 + ["kort", "halv", "halv3", "varm", "varm", "ny"]
    while len(rows) < 800:
        # En fjärdedel är en nedvärmning som kan börja: ett lärt hus i värme, på målet eller över,
        # milt väder - så att väntan på kurvtabellen och starten får många rader.
        setback = rng.random() < 0.25
        name = rng.choice(["lard", "lard", "brus"]) if setback else rng.choice(weights)
        house = houses[name]
        tuning_kw = coast_tuning(name)
        burner_lead = rng.choice([1800.0, 1800.0, 300.0, 0.0, round(rng.uniform(0.0, 3600.0), 1)])
        coasting = rng.random() < 0.35
        outdoor = maybe(temperature(-20.0, 12.0), 0.12)
        if setback:
            tuning_kw.update(coast_enabled=True, coast_min_hours=48.0,
                             shunt_lead_seconds=rng.choice([600.0, 600.0, 60.0, 1800.0]))
            coasting, outdoor = rng.random() < 0.1, temperature(0.0, 14.0)
        hour = an_hour()
        entries = table_ops(outdoor, hour, tuning_kw["coast_learning_cell_hours"])
        target = tuning_kw["room_target"]
        indoor = maybe(rng.choice([target, target, round(target + rng.uniform(0.0, 2.0), 2),
                                   round(target + rng.uniform(0.0, 2.0), 2), round(target - rng.uniform(0.0, 1.5), 2),
                                   temperature(15.0, 26.0)]), 0.06)
        mode = rng.choice(coast_modes)
        kind, forecast = a_forecast(outdoor, tuning_kw["coast_horizon_hours"])
        if setback:
            indoor = rng.choice([target, round(target + rng.uniform(0.0, 1.5), 2)])
            mode = c.MODE_HEATING if rng.random() < 0.9 else mode
            while kind in ("empty", "beyond_only"):
                kind, forecast = a_forecast(outdoor, tuning_kw["coast_horizon_hours"])
        dt = a_dt()

        made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState(), house=house)
        made_loop.burner_lead_seconds(burner_lead)
        made_loop.points = a_table(entries)
        state = made_loop.state
        hold_seconds, hold_cell, hold_base = hold_before(outdoor, hour, tuning_kw, made_loop.points)
        state.coasting = coasting
        state.seconds_learning_hold, state.learning_hold_cell, state.learning_hold_base = hold_seconds, hold_cell, hold_base
        state.outdoor.value = outdoor
        out = m.ControlOutput()
        made_loop._out = out
        seen = []
        house.outlook = lambda **kw: seen.append(th.HouseModel.outlook(house, **kw)) or seen[-1]
        try:
            result = made_loop._coast(m.ControlInputs(dt=dt, hour=hour, forecast=forecast), indoor, mode, out)
        finally:
            del house.outlook
        if seen and out.coast_shunt_threshold is not None:
            held = float("inf") if seen[0].holds else (seen[0].hours or 0.0)
            if abs(held - out.coast_shunt_threshold) < 1e-5 or abs(held - out.coast_burner_threshold) < 1e-5:
                redrawn += 1
                continue

        if not tuning_kw["coast_enabled"]:
            branch(counts, "early_disabled")
        elif indoor is None:
            branch(counts, "early_no_indoor")
        elif not forecast:
            branch(counts, "early_no_forecast")
        elif not any(h <= tuning_kw["coast_horizon_hours"] for h, _ in forecast):
            branch(counts, f"beyond_horizon_only_coasting_{str(coasting).lower()}")
        elif out.coast_trust:
            branch(counts, "distrust_" + {"för lite data": "little", "": "",
                                          "för lite varierad data för att lita på modellen än": "varied"}.get(
                out.coast_trust, "hours") + f"_coasting_{str(coasting).lower()}")
        else:
            branch(counts, "trusted")
            if out.coast_reach is None:
                branch(counts, "reach_none")
            elif tuning_kw["coast_unknown_reach"] <= 0.0:
                branch(counts, "allowance_zero_unknown_reach")
            if not out.coast_known:
                branch(counts, "unknown")
            else:
                branch(counts, f"shunt_{str(out.coast_shunt).lower()}_burner_{str(out.coast_burner).lower()}")
                if seen[0].holds:
                    branch(counts, "holds")
                if tuning_kw["coast_bridge_only"] and out.coast_turn_hours is None:
                    branch(counts, "bridge_only_flat")
                if tuning_kw["coast_hysteresis_seconds"] == 0.0:
                    branch(counts, "hysteresis_zero")
                if kind == "beyond":
                    branch(counts, "beyond_dropped")
                if mode != c.MODE_HEATING:
                    branch(counts, "not_heating")
                elif coasting:
                    branch(counts, f"carried_{str(state.coasting).lower()}")
                elif indoor < target:
                    branch(counts, "below_target")
                elif "learning_hold" in out.flags:
                    branch(counts, "learning_hold_held")
                elif out.coast_shunt:
                    branch(counts, "coast_started" if state.coasting else "?")
                    if outdoor is not None and tuning_kw["coast_learning_hold_seconds"] > 0.0:
                        branch(counts, "learning_hold_passed")
                else:
                    branch(counts, "no_shunt")
        rows.append([name, _stored(dict(tuning_kw)), burner_lead, _bool(coasting), hold_seconds, hold_cell, hold_base,
                     outdoor, table_cell(entries), indoor, mode, _slots(forecast), hour, dt, result,
                     _bool(state.coasting), state.seconds_learning_hold, state.learning_hold_cell,
                     state.learning_hold_base, "|".join(out.flags), out.coast_trust, out.coast_uncertainty,
                     out.coast_reach, out.coast_allowance, out.coast_margin, _bool(out.coast_known), out.coast_hours,
                     out.coast_shunt_threshold, out.coast_turn_hours, _bool(out.coast_shunt),
                     out.coast_burner_threshold, _bool(out.coast_burner), out.coast_learning_hold])
    write(step, "coast.csv", ["house", "tuning", "burner_lead", "coasting_before", "hold_seconds_before",
                              "hold_cell_before", "hold_base_before", "outdoor_filtered", "table", "indoor", "mode",
                              "forecast", "hour", "dt", "result", "coasting_after", "hold_seconds_after",
                              "hold_cell_after", "hold_base_after", "flags", "coast_trust", "coast_uncertainty",
                              "coast_reach", "coast_allowance", "coast_margin", "coast_known", "coast_hours",
                              "coast_shunt_threshold", "coast_turn_hours", "coast_shunt", "coast_burner_threshold",
                              "coast_burner", "coast_learning_hold"], rows)
    show("coast.csv", counts)
    print(f"{step}/coast.csv: {redrawn} omkastade nära en tröskel")

    # --- _distrust: alla hus mot gränser kring deras timmar.
    rows = []
    for name in names:
        for needed in (48.0, 47.5, 48.5, 0.0, 12.0, 12.5, 13.0, 13.5, 24.0, 600.0, 600.5, 1000.0):
            made_loop = c.HeatingController(m.Tuning(coast_min_hours=needed), m.LearnedState(), house=houses[name])
            rows.append([name, needed, made_loop._distrust()])
    write(step, "distrust.csv", ["house", "coast_min_hours", "result"], rows)

    # --- _night
    fixed = [
        # (start, end, after_sun, since_sun, hour, elevation)
        (22.0, 6.0, 7200.0, 7200.0, 22.0, 0.0), (22.0, 6.0, 7200.0, 7200.0, 21.99, 0.0),
        (22.0, 6.0, 7200.0, 7200.0, 5.99, 0.0), (22.0, 6.0, 7200.0, 7200.0, 6.0, 0.0),
        (22.0, 6.0, 7200.0, 7199.0, 23.0, 0.0), (22.0, 6.0, 7200.0, 7200.0, 23.0, 0.01),
        (22.0, 6.0, 7200.0, 1e6, -1.0, -10.0), (22.0, 6.0, 7200.0, 1e6, 25.0, -10.0),
        (22.0, 6.0, 7200.0, 1e6, 46.0, -10.0), (22.0, 6.0, 7200.0, 1e6, None, -10.0),
        (22.0, 6.0, 7200.0, 1e6, 23.0, None), (1.0, 5.0, 7200.0, 1e6, 1.0, -5.0), (1.0, 5.0, 7200.0, 1e6, 5.0, -5.0),
        (1.0, 5.0, 7200.0, 1e6, 0.99, -5.0), (3.0, 3.0, 7200.0, 1e6, 3.0, -5.0), (3.0, 3.0, 7200.0, 1e6, 2.0, -5.0),
        (22.0, 6.0, 0.0, 0.0, 0.0, 0.0), (22.0, 6.0, 7200.0, 1e6, -0.01, -1.0), (22.0, 6.0, 7200.0, 1e6, 24.0, -1.0),
    ]
    counts, rows = {}, []
    for i in range(400):
        if i < len(fixed):
            start, end, after_sun, since_sun, hour, elevation = fixed[i]
        else:
            start = rng.choice([22.0, 22.0, 0.0, 6.0, round(rng.uniform(0.0, 24.0), 2)])
            end = rng.choice([6.0, 6.0, start, 24.0, round(rng.uniform(0.0, 24.0), 2)])
            after_sun = rng.choice([7200.0, 7200.0, 0.0, round(rng.uniform(0.0, 20000.0), 1)])
            since_sun = rng.choice([1e6, after_sun, after_sun, after_sun - 30.0, 0.0, round(rng.uniform(0.0, 20000.0), 1)])
            hour = maybe(rng.choice([start, end, round(start - 0.01, 2), round(end - 0.01, 2), 23.0,
                                     round(rng.uniform(0.0, 24.0), 2), round(rng.uniform(0.0, 24.0), 2),
                                     round(rng.uniform(-48.0, 72.0), 2), 24.0, -1.0]), 0.1)
            elevation = maybe(rng.choice([0.0, 0.01, -0.01, -10.0, round(rng.uniform(-30.0, 50.0), 2)]), 0.1)
        made_loop = c.HeatingController(m.Tuning(night_start_hour=start, night_end_hour=end,
                                                 learn_after_sun_seconds=after_sun), m.LearnedState())
        made_loop.state.seconds_since_sun = since_sun
        result = made_loop._night(m.ControlInputs(dt=30.0, hour=hour, sun_elevation=elevation))
        if hour is None or elevation is None:
            branch(counts, "unknown")
        else:
            branch(counts, ("wraps" if start > end else "same" if start == end else "within") + f"_{str(result).lower()}")
            if not 0.0 <= hour < 24.0:
                branch(counts, "hour_outside_day")
        rows.append([start, end, after_sun, since_sun, hour, elevation, _bool(result)])
    write(step, "night.csv", ["night_start_hour", "night_end_hour", "learn_after_sun_seconds", "seconds_since_sun",
                              "hour", "sun_elevation", "result"], rows)
    show("night.csv", counts)

    # --- _conditions
    rows = []
    for i in range(300):
        outdoor = maybe(temperature(-30.0, 30.0), 0.15)
        wind = maybe(round(rng.uniform(0.0, 15.0), 1))
        bearing = maybe(round(rng.uniform(0.0, 360.0), 1))
        elevation = maybe(rng.choice([round(rng.uniform(-20.0, 60.0), 2), 0.0, -5.0, 30.0]))
        cloud = maybe(rng.choice([round(rng.uniform(0.0, 100.0), 1), 0.0, 100.0, 50.0]))
        sunlight = maybe(rng.choice([round(rng.uniform(0.0, 1.0), 3), round(rng.uniform(0.0, 1.0), 3), -0.2,
                                     float("nan"), float("inf"), 0.0]), 0.45)
        hour = maybe(round(rng.uniform(0.0, 24.0), 2))
        got = core.controller.HeatingController(m.Tuning(), m.LearnedState())._conditions(
            m.ControlInputs(dt=30.0, outdoor=outdoor, wind=wind, wind_bearing=bearing, sun_elevation=elevation,
                            cloud=cloud, sunlight=sunlight, hour=hour))
        answer = [None] * 5 if got is None else [got.outdoor, got.wind, got.bearing, got.sun, got.hour]
        rows.append([outdoor, wind, bearing, elevation, cloud, sunlight, hour, _bool(got is not None)] + answer)
    write(step, "conditions.csv", ["outdoor", "wind", "wind_bearing", "sun_elevation", "cloud", "sunlight", "hour",
                                   "given", "c_outdoor", "c_wind", "c_bearing", "c_sun", "c_hour"], rows)

    # --- _observe_house: följder av cykler på ett färskt hus.
    counts, rows = {}, []
    for _ in range(300):
        tuning_kw = dict(
            thermal_sample_seconds=rng.choice([600.0, 600.0, 1800.0, 300.0, 30.0, round(rng.uniform(30.0, 3600.0), 1)]),
            night_start_hour=rng.choice([22.0, 22.0, round(rng.uniform(0.0, 24.0), 2)]),
            night_end_hour=rng.choice([6.0, 6.0, round(rng.uniform(0.0, 24.0), 2)]),
            learn_after_sun_seconds=rng.choice([7200.0, 7200.0, 0.0, round(rng.uniform(0.0, 20000.0), 1)]),
        )
        since_sun = rng.choice([1e6, 1e6, 0.0, tuning_kw["learn_after_sun_seconds"], round(rng.uniform(0.0, 20000.0), 1)])
        made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState())
        made_loop.state.seconds_since_sun = since_sun
        sample = tuning_kw["thermal_sample_seconds"]
        dt = rng.choice([sample, sample, sample, sample / 2.0, 30.0, round(rng.uniform(1.0, 2.0 * sample), 1)])
        night = rng.random() < 0.3
        indoor0, outdoor0 = temperature(17.0, 24.0), temperature(-20.0, 15.0)
        cycles, learned = [], []
        for k in range(rng.randint(1, 12)):
            this_dt = dt if rng.random() < 0.85 else rng.choice([0.0, 30.0, round(rng.uniform(1.0, 3600.0), 1)])
            indoor = maybe(round(indoor0 + rng.uniform(-0.5, 0.5), 2), 0.1)
            outdoor = maybe(round(outdoor0 + rng.uniform(-1.0, 1.0), 1), 0.1)
            ret = maybe(temperature(20.0, 50.0), 0.4)
            flow = None if ret is None and rng.random() < 0.8 else maybe(round((ret or 30.0) + rng.uniform(-2.0, 20.0), 2), 0.1)
            fields = dict(
                flow=flow, ret=ret, outdoor=outdoor, wind=maybe(round(rng.uniform(0.0, 12.0), 1)),
                wind_bearing=maybe(round(rng.uniform(0.0, 360.0), 1)),
                sun_elevation=-10.0 if night else maybe(round(rng.uniform(-20.0, 50.0), 2)),
                cloud=maybe(round(rng.uniform(0.0, 100.0), 1)),
                sunlight=maybe(round(rng.uniform(0.0, 0.8), 3), 0.6),
                hour=23.0 if night else maybe(round(rng.uniform(0.0, 24.0), 2)),
            )
            delta = maybe(round(rng.uniform(0.0, 15.0), 2), 0.15)
            made_loop._out = m.ControlOutput()
            made_loop._observe_house(m.ControlInputs(dt=this_dt, **fields), indoor, delta, this_dt)
            learning, why, value = made_loop._out.learning[c.LEARN_HOUSE]
            branch(counts, f"learning_{str(learning).lower()}" + (f"_{why}" if why else ""))
            if made_loop._night(m.ControlInputs(dt=this_dt, **fields)):
                branch(counts, "night")
            if indoor is not None and c.radiator_excess(flow, ret, indoor) is not None:
                branch(counts, "radiators")
            cycles.append(":".join(_num(v) for v in list(fields.values()) + [indoor, delta, this_dt]))
            learned.append(",".join([_bool(learning), why, _num(value)]))
        house = made_loop.house
        branch(counts, "took_samples" if house.fit.weight > 0.0 else "no_samples")
        rows.append([_stored(dict(tuning_kw)), since_sun, "/".join(cycles), "/".join(learned), house.observed_hours,
                     house.last_indoor, house.seconds_since_sample, house.fit.weight])
    write(step, "observe_house.csv", ["tuning", "seconds_since_sun", "cycles", "learning", "observed_hours",
                                      "last_indoor", "seconds_since_sample", "fit_weight"], rows)
    show("observe_house.csv", counts)

    # --- _learning_hold
    fixed = [
        # (tuning, outdoor, seconds, cell, base, entries, hour, dt); None som cell = ingen ännu,
        # "same" = cellen (ute, klocka) hamnar i
        ((7200.0, 21600.0, 6.0), None, 0.0, None, 0.0, [], 12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 0.0, None, 0.0, [(-5.0, 10.0, None, 12.0, 6.0)], 12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 0.0, None, 0.0, [(-5.0, 10.0, None, 12.0, 5.5)], 12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 21600.0, "same", 0.0, [], 12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 21570.0, "same", 0.0, [], 12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 100.0, "same", 1.0, [(-5.0, 10.0, None, 12.0, 1.5), (-5.0, 9.0, 2.0, 12.0, 1.5)],
         12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 100.0, "same", 1.5, [(-5.0, 10.0, None, 12.0, 1.5), (-5.0, 9.0, 2.0, 12.0, 1.5)],
         12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 100.0, "other", 1.0, [(-5.0, 10.0, None, 12.0, 3.0)], 12.0, 30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 100.0, "same", 0.0, [], 12.0, -30.0),
        ((7200.0, 21600.0, 6.0), -5.0, 0.0, None, 0.0, [], None, 30.0),
        ((0.0, 21600.0, 6.0), -5.0, 0.0, None, 0.0, [], 12.0, 30.0),
    ]
    counts, rows = {}, []
    for i in range(500):
        if i < len(fixed):
            (hold, top, cell_hours), outdoor, seconds, cell, base, entries, hour, dt = fixed[i]
            tuning_kw = dict(coast_learning_hold_seconds=hold, coast_learning_hold_max_seconds=top,
                             coast_learning_cell_hours=cell_hours)
            made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState())
            made_loop.points = a_table(entries)
            if cell == "same":
                cell = made_loop.points.key_at(outdoor, hour)
            elif cell == "other":
                cell = made_loop.points.key_at(outdoor + 10.0, hour)
        else:
            tuning_kw = dict(
                coast_learning_hold_seconds=rng.choice([7200.0, 7200.0, 0.0, round(rng.uniform(0.0, 14400.0), 1)]),
                coast_learning_hold_max_seconds=rng.choice([21600.0, 21600.0, 0.0, round(rng.uniform(0.0, 43200.0), 1)]),
                coast_learning_cell_hours=rng.choice([6.0, 6.0, 0.0, round(rng.uniform(0.0, 12.0), 2)]),
            )
            outdoor = maybe(temperature(-20.0, 12.0), 0.1)
            hour = an_hour()
            dt = a_dt()
            entries = table_ops(outdoor, hour, tuning_kw["coast_learning_cell_hours"])
            made_loop = c.HeatingController(m.Tuning(**tuning_kw), m.LearnedState())
            made_loop.points = a_table(entries)
            seconds, cell, base = hold_before(outdoor, hour, tuning_kw, made_loop.points)
        state = made_loop.state
        state.outdoor.value = outdoor
        state.seconds_learning_hold, state.learning_hold_cell, state.learning_hold_base = seconds, cell, base
        out = m.ControlOutput()
        made_loop._out = out
        result = made_loop._learning_hold(m.ControlInputs(dt=dt, hour=hour), out)
        if outdoor is None:
            branch(counts, "no_outdoor")
        else:
            hours = made_loop.points.hours_at(outdoor, hour)
            if hours >= tuning_kw["coast_learning_cell_hours"]:
                branch(counts, "cell_full")
            elif seconds >= tuning_kw["coast_learning_hold_max_seconds"]:
                branch(counts, "max_reached" + ("_exact" if seconds == tuning_kw["coast_learning_hold_max_seconds"] else ""))
            else:
                branch(counts, "same_cell" if cell == made_loop.points.key_at(outdoor, hour) else "new_cell")
                left = tuning_kw["coast_learning_hold_seconds"] - (hours - state.learning_hold_base) * 3600.0
                if left == 0.0:
                    branch(counts, "left_zero")
                branch(counts, f"held_{str(result).lower()}" + ("_negative_dt" if result and dt < 0.0 else ""))
        rows.append([_stored(dict(tuning_kw)), outdoor, seconds, cell, base, table_cell(entries), hour, dt, _bool(result),
                     state.seconds_learning_hold, state.learning_hold_cell, state.learning_hold_base,
                     out.coast_learning_hold, "|".join(out.flags)])
    write(step, "learning_hold.csv", ["tuning", "outdoor_filtered", "hold_seconds_before", "hold_cell_before",
                                      "hold_base_before", "table", "hour", "dt", "result", "hold_seconds_after",
                                      "hold_cell_after", "hold_base_after", "coast_learning_hold", "flags"], rows)
    show("learning_hold.csv", counts)


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
    model(core, rng)
    controller(core, rng)  # sist: stegen före får samma slumptal som förut
    cycle_start(core, rng)
    delta_and_radiators(core, rng)
    mode_and_heat_source(core, rng)
    rollout(core, rng)
    for step, *modules in (("01-tider", "durations"), ("02-pid", "pid"), ("03-kurvan", "curve"),
                           ("04-ventilkartan", "valvemap"), ("05-kurvtabellen", "points"),
                           ("06-skattarna", "estimators"), ("07-husmodellen", "thermal"),
                           ("08-prognosen", "forecast"), ("09-kurvanpassningen", "curvefit"),
                           ("10-brannaren", "burner"), ("11-modellen", "model", "parsing", "sunterm"),
                           ("12-grunden", "controller", "texts"),
                           ("13-ute-inne-kurvan", "controller"),
                           ("14-deltat-radiatorvakten", "controller"),
                           ("15-laget-varmekallan", "controller"),
                           ("16-utrullningen", "controller")):
        for module in modules:
            copy_source(heating, step, module)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
