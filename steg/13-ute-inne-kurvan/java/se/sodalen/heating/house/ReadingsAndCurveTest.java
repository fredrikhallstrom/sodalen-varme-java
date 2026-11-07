package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.house.Model.ROLE_CEILING;
import static se.sodalen.heating.house.Model.ROLE_ROOM;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.curve.Curve;
import se.sodalen.heating.estimate.Ewma;

/**
 * The first phase of a cycle: the outdoor temperature on two timescales,
 * the indoor temperature on two, the clocks the learning gates read, and
 * the curve - the outdoor temperature it reads in a cold front, the slope
 * and balance it runs on, and the supply it asks for.
 *
 * <p>Python: controller.py (_outdoor, _indoor, _timers, _curve_outdoor,
 * _curve, _curve_flow, curvature, effective_slope, balance, curve_at),
 * tests/test_controller.py (TestDegradedSensors, TestHeatingCurve,
 * TestLearningGates' clocks) - there through step(), here one method at a
 * time, since step() comes in step 20.
 */
class ReadingsAndCurveTest {

    static final double DT = 30.0;

    static HeatingController made(Tuning tuning) {
        return new HeatingController(tuning);
    }

    static HeatingController made() {
        return made(new Tuning());
    }

    static ControlInputs inputs(double dt) {
        return new ControlInputs(dt);
    }

    static SensorReading room(Double temperature) {
        return new SensorReading("rum", temperature, ROLE_ROOM, 1.0);
    }

    static List<Forecast> ahead(double hours, double outdoor) {
        return List.of(new Forecast(hours, new Conditions(outdoor)));
    }

    /** One cycle's _outdoor with this reading and forecast, the output kept. */
    static Outdoors outdoor(HeatingController loop, Double measured, List<Forecast> forecast, ControlOutput out) {
        ControlInputs cycle = inputs(DT);
        cycle.outdoor = measured;
        cycle.forecast = forecast;
        return loop.outdoor(cycle, DT, out);
    }

    static Outdoors outdoor(HeatingController loop, Double measured) {
        return outdoor(loop, measured, List.of(), new ControlOutput());
    }

    static Indoors indoor(HeatingController loop, List<SensorReading> readings, Double flow, ControlOutput out) {
        ControlInputs cycle = inputs(DT);
        cycle.indoor = readings;
        cycle.flow = flow;
        return loop.indoor(cycle, DT, out);
    }

    static Indoors indoor(HeatingController loop, List<SensorReading> readings) {
        return indoor(loop, readings, 45.0, new ControlOutput());
    }

    /** One cycle's _timers with everything present and ordinary, but for what the test sets. */
    static ControlInputs ordinary(double dt) {
        ControlInputs cycle = inputs(dt);
        cycle.flow = 45.0;
        cycle.ret = 38.0;
        return cycle;
    }

    static ControlOutput timers(HeatingController loop, ControlInputs cycle) {
        ControlOutput out = new ControlOutput();
        loop.timers(cycle, cycle.dt, out);
        return out;
    }

    /** _outdoor: the filtered reading for the curve, the seasonal one for the summer stop. */
    @Nested
    class TheOutdoor {
        @Test
        void aFirstSampleIsTakenWhole() {
            HeatingController loop = made();
            Outdoors got = outdoor(loop, 7.5);
            assertEquals(7.5, got.filtered(), 0.0);
            assertEquals(7.5, got.summer(), 0.0);
            assertEquals(7.5, loop.state.outdoor.value(), 0.0);
            assertEquals(7.5, loop.state.outdoorSummer.value(), 0.0);
        }

        @Test
        void theSummerMeanIsTheSlower() {
            HeatingController loop = made();
            outdoor(loop, 10.0);
            ControlInputs cycle = inputs(3600.0);
            cycle.outdoor = 0.0;
            Outdoors got = loop.outdoor(cycle, 3600.0, new ControlOutput());
            assertTrue(got.filtered() < got.summer(), got.filtered() + " < " + got.summer());
            assertTrue(got.filtered() < 10.0 && got.summer() < 10.0);
        }

        /** The design outdoor temperature is the cold end of the curve: guessing wrong that way costs firewood. */
        @Test
        void noReadingEverAssumesDesignCold() {
            Tuning tuning = new Tuning();
            tuning.designOutdoor = -22.0;
            HeatingController loop = made(tuning);
            ControlOutput out = new ControlOutput();
            Outdoors got = outdoor(loop, null, List.of(), out);
            assertEquals(-22.0, got.filtered(), 0.0);
            assertEquals(-22.0, got.summer(), 0.0, "the summer stop gets the same answer, firmly out of summer");
            assertEquals(List.of("outdoor"), out.degraded);
            assertEquals(List.of(), out.flags);
            assertNull(loop.state.outdoor.value(), "nothing was measured");
        }

        @Test
        void losingTheSensorKeepsTheFilteredValue() {
            HeatingController loop = made();
            Outdoors before = null;
            for (int i = 0; i < 200; i++) {
                before = outdoor(loop, 0.0);
            }
            ControlOutput out = new ControlOutput();
            Outdoors after = outdoor(loop, null, List.of(), out);
            assertEquals(before.filtered(), after.filtered(), 0.0);
            assertEquals(before.summer(), after.summer(), 0.0);
            assertEquals(List.of("outdoor"), out.degraded);
            assertEquals(List.of(), out.flags, "no forecast to follow");
        }

        /** An internet outage takes the cloud sensor; the forecast fetched before it knows the morning is colder. */
        @Test
        void withoutTheSensorTheCurveFollowsTheKeptForecast() {
            HeatingController loop = made();
            for (int i = 0; i < 200; i++) {
                outdoor(loop, 5.0);
            }
            ControlOutput out = null;
            Outdoors got = null;
            for (int i = 0; i < 600; i++) {
                out = new ControlOutput();
                got = outdoor(loop, null, ahead(1.0, -5.0), out);
            }
            assertEquals(List.of("outdoor"), out.degraded, "still degraded");
            assertEquals(List.of("outdoor_forecast"), out.flags);
            assertTrue(got.filtered() < 0.0, "five hours toward -5: " + got.filtered());
            assertTrue(got.summer() < 5.0, "the season follows too: " + got.summer());
        }

        @Test
        void aForecastThatHasRunOutIsNotUsed() {
            HeatingController loop = made();
            for (int i = 0; i < 200; i++) {
                outdoor(loop, 5.0);
            }
            ControlOutput out = new ControlOutput();
            Outdoors got = outdoor(loop, null, ahead(3.0, -5.0), out);
            assertEquals(List.of(), out.flags);
            assertEquals(5.0, got.filtered(), 1e-3);
        }

        /** STAND_IN_HOURS: an hour and a half ahead still stands in, a tenth more does not. */
        @Test
        void onTheStandInLimitItIsStillUsed() {
            ControlOutput on = new ControlOutput();
            outdoor(made(), null, ahead(1.5, -5.0), on);
            assertEquals(List.of("outdoor_forecast"), on.flags);
            ControlOutput over = new ControlOutput();
            Outdoors got = outdoor(made(), null, ahead(1.6, -5.0), over);
            assertEquals(List.of(), over.flags);
            assertEquals(-18.0, got.filtered(), 0.0, "never measured: design cold");
        }

        @Test
        void theForecastIsTheFirstSlots() {
            ControlOutput out = new ControlOutput();
            List<Forecast> weather = List.of(new Forecast(0.5, new Conditions(-5.0)), new Forecast(3.5, new Conditions(-9.0)));
            Outdoors got = outdoor(made(), null, weather, out);
            assertEquals(-5.0, got.filtered(), 0.0);
        }

        @Test
        void aMeasuredTemperatureBeatsTheForecast() {
            ControlOutput out = new ControlOutput();
            Outdoors got = outdoor(made(), 5.0, ahead(1.0, -5.0), out);
            assertEquals(5.0, got.filtered(), 0.0);
            assertEquals(List.of(), out.flags);
            assertEquals(List.of(), out.degraded);
        }

        /** Only the curve follows the forecast: the filters are fed it, inputs.outdoor stays None for the learners. */
        @Test
        void theFiltersAreFedTheForecast() {
            HeatingController loop = made();
            outdoor(loop, null, ahead(1.0, -5.0), new ControlOutput());
            assertEquals(-5.0, loop.state.outdoor.value(), 0.0);
            assertEquals(-5.0, loop.state.outdoorSummer.value(), 0.0);
        }

        @Test
        void aSeasonWithoutAValueIsTheFilteredOne() {
            HeatingController loop = made();
            loop.state.outdoor = new Ewma(loop.tuning.outdoorFilterSeconds, 5.0);
            Outdoors got = outdoor(loop, null);
            assertEquals(5.0, got.filtered(), 0.0);
            assertEquals(5.0, got.summer(), 0.0);
        }

        @Test
        void theFiltersRunOnTheTuningsSeconds() {
            Tuning quick = new Tuning();
            quick.outdoorFilterSeconds = 60.0;
            HeatingController fast = made(quick);
            HeatingController slow = made();
            outdoor(fast, 10.0);
            outdoor(slow, 10.0);
            assertTrue(outdoor(fast, 0.0).filtered() < outdoor(slow, 0.0).filtered());
        }
    }

    /** _indoor: the fusion through the two filters, and what is said when sensors are missing. */
    @Nested
    class TheIndoor {
        @Test
        void aFirstReadingIsTakenWhole() {
            HeatingController loop = made();
            Indoors got = indoor(loop, List.of(room(20.5)));
            assertEquals(20.5, got.fused(), 0.0);
            assertEquals(20.5, got.slow(), 0.0);
            assertEquals(20.5, loop.state.indoor.value(), 0.0);
            assertEquals(20.5, loop.state.indoorSlow.value(), 0.0);
        }

        @Test
        void theSlowFilterLags() {
            HeatingController loop = made();
            for (int i = 0; i < 50; i++) {
                indoor(loop, List.of(room(21.0)));
            }
            Indoors got = indoor(loop, List.of(room(17.0)));
            assertTrue(got.fused() < got.slow(), got.fused() + " < " + got.slow());
            assertTrue(got.slow() < 21.0);
        }

        @Test
        void theSupplyGoesToTheFusion() {
            Indoors got = indoor(made(), List.of(new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.06)), 45.0,
                    new ControlOutput());
            assertEquals(22.5 - 0.06 * (45.0 - 21.0), got.fused(), 1e-9);
        }

        @Test
        void theDetailsAreReported() {
            ControlOutput out = new ControlOutput();
            indoor(made(), List.of(room(21.0), new SensorReading("b", 22.0, ROLE_ROOM, 0.5)), 45.0, out);
            assertEquals(List.of("rum", "b"), out.indoorReadings.stream().map(FusedSensor::key).toList());
        }

        /** Dropping to plain weather compensation is visibly degraded and safe. */
        @Test
        void noIndoorSensorsIsDegraded() {
            HeatingController loop = made();
            ControlOutput out = new ControlOutput();
            Indoors got = indoor(loop, List.of(room(null)), 45.0, out);
            assertNull(got.fused());
            assertNull(got.slow());
            assertEquals(List.of("indoor"), out.degraded);
            assertEquals(List.of(), out.flags, "none of them is not some of them");
            assertEquals(1, out.indoorReadings.size(), "shown all the same");
            assertNull(loop.state.indoor.value(), "the filters were not fed");
        }

        @Test
        void noSensorsAtAllIsDegradedToo() {
            ControlOutput out = new ControlOutput();
            Indoors got = indoor(made(), List.of(), 45.0, out);
            assertNull(got.fused());
            assertEquals(List.of("indoor"), out.degraded);
            assertEquals(List.of(), out.flags);
        }

        /** A stale indoor temperature looks like working feedback: it is not reused. */
        @Test
        void aStaleValueIsNotReused() {
            HeatingController loop = made();
            for (int i = 0; i < 100; i++) {
                indoor(loop, List.of(room(17.0)));
            }
            assertEquals(17.0, indoor(loop, List.of(room(17.0))).fused(), 1e-6);
            Indoors gone = indoor(loop, List.of(room(null)));
            assertNull(gone.fused());
            assertNull(gone.slow());
            assertEquals(17.0, loop.state.indoor.value(), 1e-6, "the filter keeps what it had, unasked");
        }

        @Test
        void partlyAvailableSensorsAreFlaggedButUsed() {
            ControlOutput out = new ControlOutput();
            Indoors got = indoor(made(), List.of(room(21.0), new SensorReading("b", null, ROLE_ROOM, 1.0)), 45.0, out);
            assertEquals(List.of("indoor_partial"), out.flags);
            assertEquals(List.of(), out.degraded);
            assertNotNull(got.fused());
            assertEquals(List.of(true, false), out.indoorReadings.stream().map(FusedSensor::contributed).toList());
        }

        @Test
        void aWeightlessSensorCountsAsMissing() {
            ControlOutput out = new ControlOutput();
            indoor(made(), List.of(room(21.0), new SensorReading("b", 22.0, ROLE_ROOM, 0.0)), 45.0, out);
            assertEquals(List.of("indoor_partial"), out.flags);
        }

        @Test
        void allAvailableIsNotFlagged() {
            ControlOutput out = new ControlOutput();
            indoor(made(), List.of(room(21.0), new SensorReading("b", 22.0, ROLE_ROOM, 0.5)), 45.0, out);
            assertEquals(List.of(), out.flags);
            assertEquals(List.of(), out.degraded);
        }
    }

    /** _timers: the clocks the learning gates read, advanced here before the mode is known. */
    @Nested
    class TheTimers {
        @Test
        void aFreshStateHasHadNoOverrideForAMillionSeconds() {
            HeatingController loop = made();
            timers(loop, ordinary(DT));
            assertEquals(1e6 + DT, loop.state.secondsSinceOverride, 0.0);
        }

        @Test
        void anOverrideResetsItsClock() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.overrideActive = true;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceOverride, 0.0);
            timers(loop, ordinary(DT));
            assertEquals(DT, loop.state.secondsSinceOverride, 0.0);
        }

        /** The house was not run by the curve, then or for a while after. */
        @Test
        void emergencyRunningCountsAsAnOverride() {
            for (String kind : List.of(Model.EMERGENCY_VALVE, Model.EMERGENCY_FLOW)) {
                HeatingController loop = made();
                ControlInputs cycle = ordinary(DT);
                cycle.emergency = kind;
                timers(loop, cycle);
                assertEquals(0.0, loop.state.secondsSinceOverride, 0.0, kind);
            }
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.emergency = "x";
            timers(loop, cycle);
            assertEquals(1e6 + DT, loop.state.secondsSinceOverride, 0.0, "not an emergency kind");
        }

        @Test
        void aTargetChangeResetsItsClock() {
            HeatingController loop = made();
            timers(loop, ordinary(DT));
            assertEquals(DT, loop.state.secondsSinceTargetChange, 0.0, "a fresh state's is nought");
            ControlInputs cycle = ordinary(DT);
            cycle.targetChanged = true;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceTargetChange, 0.0);
        }

        /** Reset in step() once the mode is known: here it only runs. */
        @Test
        void theCoastClockOnlyRunsHere() {
            HeatingController loop = made();
            loop.state.coasting = true;
            timers(loop, ordinary(DT));
            assertEquals(1e6 + DT, loop.state.secondsSinceCoast, 0.0);
        }

        @Test
        void lightOverTheThresholdIsSun() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.sunlight = 0.1;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceSun, 0.0);
            assertEquals(0.1, loop.state.sunHeat.value(), 0.0, "the held light is fed every cycle");
            timers(loop, ordinary(DT));
            assertEquals(DT, loop.state.secondsSinceSun, 0.0);
        }

        @Test
        void onTheThresholdIsNoSun() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.sunlight = loop.tuning.learnSunlight;
            timers(loop, cycle);
            assertEquals(1e6 + DT, loop.state.secondsSinceSun, 0.0);
        }

        @Test
        void theThresholdIsASetting() {
            Tuning tuning = new Tuning();
            tuning.learnSunlight = 0.2;
            HeatingController loop = made(tuning);
            ControlInputs cycle = ordinary(DT);
            cycle.sunlight = 0.1;
            timers(loop, cycle);
            assertEquals(1e6 + DT, loop.state.secondsSinceSun, 0.0);
        }

        /** Without walls the old guess from the sun's height and the clouds decides, as sunlightNow says. */
        @Test
        void withoutWallsTheSunIsGuessed() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.sunElevation = 40.0;
            cycle.cloud = 0.0;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceSun, 0.0);
            assertEquals(HeatingController.sunlightNow(cycle), loop.state.sunHeat.value(), 0.0);
        }

        /** The slow indoor temperature, which _indoor has just updated: the house, not a cooking hour. */
        @Test
        void overTargetCountsOnTheSlowFilter() {
            HeatingController loop = made();
            loop.state.indoorSlow = new Ewma(loop.tuning.indoorSlowFilterSeconds, 21.4);
            timers(loop, ordinary(DT));
            assertEquals(DT, loop.state.secondsOverTarget, 0.0);
            timers(loop, ordinary(DT));
            assertEquals(2 * DT, loop.state.secondsOverTarget, 0.0);
            loop.state.indoorSlow = new Ewma(loop.tuning.indoorSlowFilterSeconds, 21.3);
            timers(loop, ordinary(DT));
            assertEquals(0.0, loop.state.secondsOverTarget, 0.0, "on the margin is on target");
        }

        @Test
        void noIndoorTemperatureIsNotOverTarget() {
            HeatingController loop = made();
            loop.state.secondsOverTarget = 500.0;
            timers(loop, ordinary(DT));
            assertEquals(0.0, loop.state.secondsOverTarget, 0.0);
        }

        @Test
        void theMarginIsASetting() {
            Tuning tuning = new Tuning();
            tuning.learnOverTarget = 1.0;
            HeatingController loop = made(tuning);
            loop.state.indoorSlow = new Ewma(tuning.indoorSlowFilterSeconds, 21.9);
            timers(loop, ordinary(DT));
            assertEquals(0.0, loop.state.secondsOverTarget, 0.0);
        }

        @Test
        void aSweepResetsItsClock() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.sweeping = true;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceSweep, 0.0);
            timers(loop, ordinary(DT));
            assertEquals(DT, loop.state.secondsSinceSweep, 0.0);
        }

        /** Reset elsewhere in step(), once the setpoint and the mode are known. */
        @Test
        void shortfallAndOffOnlyRunHere() {
            HeatingController loop = made();
            timers(loop, ordinary(600.0));
            assertEquals(1e6 + 600.0, loop.state.secondsSinceShortfall, 0.0);
            assertEquals(1e6 + 600.0, loop.state.secondsSinceOff, 0.0);
        }

        /** From the heartbeat's answer, which is a cycle old: it starts the clock, and never winds it back up. */
        @Test
        void theShellySaysWhenItHandedTheValveBack() {
            HeatingController loop = made();
            timers(loop, ordinary(DT));
            assertTrue(loop.state.secondsSinceFailsafe > 24 * 3600.0);
            ControlInputs cycle = ordinary(DT);
            cycle.sinceFailsafe = 0.0;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceFailsafe, 0.0);
            cycle.sinceFailsafe = 30.0;
            timers(loop, cycle);
            assertEquals(30.0, loop.state.secondsSinceFailsafe, 0.0);
            // A Shelly that restarted has forgotten: the clock runs on.
            timers(loop, ordinary(DT));
            assertEquals(60.0, loop.state.secondsSinceFailsafe, 0.0);
            cycle.sinceFailsafe = 5000.0;
            timers(loop, cycle);
            assertEquals(90.0, loop.state.secondsSinceFailsafe, 0.0);
        }

        @Test
        void aReportFromBeforeZeroIsZero() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.sinceFailsafe = -30.0;
            timers(loop, cycle);
            assertEquals(0.0, loop.state.secondsSinceFailsafe, 0.0);
        }

        @Test
        void noSupplyReadingCountsAndIsDegraded() {
            HeatingController loop = made();
            ControlInputs cycle = ordinary(DT);
            cycle.flow = null;
            ControlOutput out = timers(loop, cycle);
            assertEquals(DT, loop.state.secondsWithoutFlow, 0.0);
            assertEquals(List.of("flow"), out.degraded);
            out = timers(loop, ordinary(DT));
            assertEquals(0.0, loop.state.secondsWithoutFlow, 0.0, "back: the count starts over");
            assertEquals(List.of(), out.degraded);
        }

        @Test
        void noReturnReadingIsDegraded() {
            ControlInputs cycle = ordinary(DT);
            cycle.ret = null;
            assertEquals(List.of("return"), timers(made(), cycle).degraded);
            cycle.flow = null;
            assertEquals(List.of("flow", "return"), timers(made(), cycle).degraded, "in Python's order");
        }

        @Test
        void theClocksRunOnTheTimeThatPassed() {
            HeatingController loop = made();
            timers(loop, ordinary(7200.0));
            assertEquals(1e6 + 7200.0, loop.state.secondsSinceSweep, 0.0);
            timers(loop, ordinary(0.0));
            assertEquals(1e6 + 7200.0, loop.state.secondsSinceSweep, 0.0, "no time, no change");
        }
    }

    /** _curve_outdoor: the filtered temperature, and in a cold front part of the way down to the measured one. */
    @Nested
    class TheColdFront {
        final HeatingController loop = made();

        @Test
        void noMeasurementIsTheFilteredOne() {
            assertEquals(0.0, loop.curveOutdoor(null, 0.0), 0.0);
            assertEquals(-3.5, loop.curveOutdoor(null, -3.5), 0.0);
        }

        /** A sunny morning takes nothing away: warmer than the mean it stays the mean. */
        @Test
        void warmerThanTheMeanStaysTheMean() {
            assertEquals(0.0, loop.curveOutdoor(3.0, 0.0), 0.0);
            assertEquals(0.0, loop.curveOutdoor(0.0, 0.0), 0.0, "equal is not a fall");
        }

        /** The night of 2026-11-05 it fell 6 K in seven hours and the house sank 0.9 K under its target. */
        @Test
        void halfWayDownInAColdFront() {
            assertEquals(-2.0, loop.curveOutdoor(-4.0, 0.0), 1e-12);
            assertEquals(-6.0, loop.curveOutdoor(-7.0, -5.0), 1e-12, "half of two");
        }

        /** The most of the gap it counts, for a sensor gone wrong. */
        @Test
        void heldToTheLimit() {
            assertEquals(-2.0, loop.curveOutdoor(-20.0, 0.0), 1e-12);
            assertEquals(-2.0, loop.curveOutdoor(-4.0, 0.0), 1e-12, "the limit is four: just reached");
        }

        @Test
        void theLeadIsASetting() {
            Tuning tuning = new Tuning();
            tuning.outdoorFallLead = 1.0;
            assertEquals(-4.0, made(tuning).curveOutdoor(-4.0, 0.0), 1e-12);
            tuning.outdoorFallLead = 0.0;
            assertEquals(0.0, made(tuning).curveOutdoor(-4.0, 0.0), 0.0, "nought switches it off");
        }

        @Test
        void theLimitIsASetting() {
            Tuning tuning = new Tuning();
            tuning.outdoorFallLimit = 2.0;
            assertEquals(-1.0, made(tuning).curveOutdoor(-20.0, 0.0), 1e-12);
            tuning.outdoorFallLimit = 0.0;
            assertEquals(0.0, made(tuning).curveOutdoor(-20.0, 0.0), 0.0);
        }
    }

    /** _curve, effective_slope, balance, curvature, _curve_flow and curve_at. */
    @Nested
    class TheCurve {
        @Test
        void theDesignPointGivesTheDesignedSupply() {
            Tuning tuning = new Tuning();
            tuning.roomTarget = 21.0;
            tuning.designOutdoor = -18.0;
            tuning.curveSlope = 0.9;
            CurvePoint got = made(tuning).curve(-18.0);
            assertEquals(21.0 + 0.9 * 39.0, got.flow(), 1e-6);
            assertEquals(0.9, got.slope(), 0.0, "the slope it ran on");
        }

        @Test
        void curvaturePivotsOnTheDesignPoint() {
            Tuning flat = new Tuning();
            flat.curveCurvature = 1.0;
            Tuning bentTuning = new Tuning();
            bentTuning.curveCurvature = 1.3;
            HeatingController straight = made(flat);
            HeatingController bent = made(bentTuning);
            assertEquals(straight.curve(-18.0).flow(), bent.curve(-18.0).flow(), 1e-6);
            assertTrue(bent.curve(5.0).flow() < straight.curve(5.0).flow());
        }

        /** curve_curvature=1.0 keeps the curve straight, so the slope's contribution is exactly slope * (target - outdoor). */
        @Test
        void aLearnedSlopeMovesTheCurve() {
            Tuning straight = new Tuning();
            straight.curveCurvature = 1.0;
            HeatingController plain = made(straight);
            HeatingController steeper = made(straight);
            steeper.state.slopeTrim = 0.2;
            assertEquals(plain.curve(-10.0).flow() + 0.2 * 31.0, steeper.curve(-10.0).flow(), 1e-6);
            assertEquals(1.1, steeper.curve(-10.0).slope(), 1e-12);
        }

        /** Free heat lowers the curve, most in mild weather and not at all at the design point. */
        @Test
        void aLearnedBalanceLowersItMostInMildWeather() {
            Tuning straight = new Tuning();
            straight.curveCurvature = 1.0;
            HeatingController plain = made(straight);
            HeatingController freer = made(straight);
            freer.state.balanceShift = -6.0;
            assertTrue(freer.curve(-10.0).flow() < plain.curve(-10.0).flow());
            assertTrue(freer.curveAt(5.0) - plain.curveAt(5.0) < freer.curveAt(-10.0) - plain.curveAt(-10.0));
            assertEquals(plain.curveAt(-18.0), freer.curveAt(-18.0), 1e-6);
            // And below the balance there is no heat to ask for.
            assertEquals(straight.clampFlow(21.0), freer.curveAt(16.0), 1e-6);
        }

        @Test
        void theSlopeIsTheInstallersPlusTheTrim() {
            HeatingController loop = made();
            assertEquals(0.9, loop.effectiveSlope(), 0.0);
            loop.state.slopeTrim = -0.25;
            assertEquals(0.65, loop.effectiveSlope(), 1e-12);
        }

        @Test
        void theSlopeIsHeldToItsLimits() {
            HeatingController loop = made();
            loop.state.slopeTrim = 5.0;
            assertEquals(loop.tuning.slopeMax, loop.effectiveSlope(), 0.0);
            assertEquals(loop.tuning.slopeMax, loop.curve(0.0).slope(), 0.0);
            loop.state.slopeTrim = -5.0;
            assertEquals(loop.tuning.slopeMin, loop.effectiveSlope(), 0.0);
        }

        @Test
        void theBalanceIsTheTargetPlusTheShift() {
            HeatingController loop = made();
            assertEquals(21.0, loop.balance(), 0.0);
            loop.state.balanceShift = -6.0;
            assertEquals(15.0, loop.balance(), 0.0);
        }

        /** The curve table's bend when it has learned one, otherwise the setting (model.effective_curvature). */
        @Test
        void theCurvatureIsTheTablesWhenItHasOne() {
            HeatingController loop = made();
            assertEquals(0.8, loop.curvature(), 0.0);
            loop.state.bend = 1.2;
            assertEquals(1.2, loop.curvature(), 0.0);
            assertEquals(Model.effectiveCurvature(loop.tuning, loop.state), loop.curvature(), 0.0);
        }

        @Test
        void theCurveIsTheCurveModules() {
            HeatingController loop = made();
            loop.state.slopeTrim = 0.1;
            loop.state.balanceShift = -2.0;
            loop.state.bend = 0.7;
            Tuning t = loop.tuning;
            assertEquals(Curve.supply(-3.0, t.roomTarget, t.designOutdoor, 1.0, 0.7, 19.0), loop.curve(-3.0).flow(), 1e-12);
        }

        @Test
        void curveFlowWithoutABendUsesTheOneInUse() {
            HeatingController loop = made();
            Tuning t = loop.tuning;
            double expected = Curve.supply(0.0, t.roomTarget, t.designOutdoor, 0.9, 0.8, 21.0);
            assertEquals(expected, loop.curveFlow(0.0, 0.9, 21.0), 1e-12);
            assertEquals(expected, loop.curveFlow(0.0, 0.9, 21.0, null), 1e-12);
            assertEquals(Curve.supply(0.0, t.roomTarget, t.designOutdoor, 0.9, 1.0, 21.0),
                    loop.curveFlow(0.0, 0.9, 21.0, 1.0), 1e-12);
        }

        /** The curve is not held to the supply limits: step() clamps the whole setpoint, curve_at clamps for drawing. */
        @Test
        void theCurveItselfIsNotClamped() {
            Tuning tuning = new Tuning();
            tuning.flowMax = 50.0;
            tuning.flowMin = 25.0;
            HeatingController loop = made(tuning);
            assertTrue(loop.curve(-30.0).flow() > 50.0);
            assertEquals(50.0, loop.curveAt(-30.0), 0.0);
            assertEquals(21.0, loop.curve(25.0).flow(), 0.0, "over the balance the curve asks for the room itself");
            assertEquals(25.0, loop.curveAt(25.0), 0.0);
        }

        @Test
        void curveAtIsTheCurveInUseByDefault() {
            HeatingController loop = made();
            loop.state.slopeTrim = 0.1;
            loop.state.balanceShift = -2.0;
            assertEquals(loop.tuning.clampFlow(loop.curve(-3.0).flow()), loop.curveAt(-3.0), 1e-12);
            assertEquals(loop.curveAt(-3.0), loop.curveAt(-3.0, null, null, null), 0.0, "nothing given is the default");
        }

        @Test
        void curveAtWithAGivenSlopeBalanceAndBend() {
            HeatingController loop = made();
            Tuning t = loop.tuning;
            assertEquals(t.clampFlow(Curve.supply(0.0, t.roomTarget, t.designOutdoor, 1.2, 1.0, 17.0)),
                    loop.curveAt(0.0, 1.2, 17.0, 1.0), 1e-12);
            assertEquals(t.clampFlow(Curve.supply(0.0, t.roomTarget, t.designOutdoor, 1.2, 0.8, 21.0)),
                    loop.curveAt(0.0, 1.2, null, null), 1e-12, "each one alone");
            assertNotEquals(loop.curveAt(0.0), loop.curveAt(0.0, 1.2, null, null));
        }

        @Test
        void theRecordsSayWhatTheyAre() {
            CurvePoint point = new CurvePoint(44.0, 0.9);
            assertEquals(44.0, point.flow(), 0.0);
            assertEquals(0.9, point.slope(), 0.0);
            Outdoors outdoors = new Outdoors(1.0, 2.0);
            assertEquals(1.0, outdoors.filtered(), 0.0);
            assertEquals(2.0, outdoors.summer(), 0.0);
            Indoors indoors = new Indoors(null, null);
            assertNull(indoors.fused());
            assertFalse(new Indoors(21.0, 21.0).fused() == null);
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        static final List<String> CLOCKS = List.of("seconds_since_override", "seconds_since_target_change",
                "seconds_since_coast", "seconds_since_sun", "seconds_over_target", "seconds_since_sweep",
                "seconds_since_shortfall", "seconds_since_off", "seconds_since_failsafe", "seconds_without_flow");

        static List<String> words(String cell) {
            return cell == null || cell.isEmpty() ? List.of() : Arrays.asList(cell.split(",", -1));
        }

        static List<SensorReading> readings(String cell) {
            List<SensorReading> out = new ArrayList<>();
            if (cell == null) {
                return out;
            }
            for (String one : cell.split("\\|", -1)) {
                String[] p = one.split(",", -1);
                out.add(new SensorReading(p[0], number(p[1]), p[2], required(p[3]), required(p[4]), required(p[5]), null,
                        bool(p[6]), bool(p[7])));
            }
            return out;
        }

        static void clock(LearnedState state, String name, double value) {
            switch (name) {
                case "seconds_since_override" -> state.secondsSinceOverride = value;
                case "seconds_since_target_change" -> state.secondsSinceTargetChange = value;
                case "seconds_since_coast" -> state.secondsSinceCoast = value;
                case "seconds_since_sun" -> state.secondsSinceSun = value;
                case "seconds_over_target" -> state.secondsOverTarget = value;
                case "seconds_since_sweep" -> state.secondsSinceSweep = value;
                case "seconds_since_shortfall" -> state.secondsSinceShortfall = value;
                case "seconds_since_off" -> state.secondsSinceOff = value;
                case "seconds_since_failsafe" -> state.secondsSinceFailsafe = value;
                case "seconds_without_flow" -> state.secondsWithoutFlow = value;
                default -> throw new AssertionError("no such clock: " + name);
            }
        }

        static Map<String, Double> clocks(LearnedState state) {
            Map<String, Double> out = new LinkedHashMap<>();
            out.put("seconds_since_override", state.secondsSinceOverride);
            out.put("seconds_since_target_change", state.secondsSinceTargetChange);
            out.put("seconds_since_coast", state.secondsSinceCoast);
            out.put("seconds_since_sun", state.secondsSinceSun);
            out.put("seconds_over_target", state.secondsOverTarget);
            out.put("seconds_since_sweep", state.secondsSinceSweep);
            out.put("seconds_since_shortfall", state.secondsSinceShortfall);
            out.put("seconds_since_off", state.secondsSinceOff);
            out.put("seconds_since_failsafe", state.secondsSinceFailsafe);
            out.put("seconds_without_flow", state.secondsWithoutFlow);
            return out;
        }

        /** design_outdoor;outdoor_seconds;outdoor_before;summer_seconds;summer_before;measured;forecast;dt;
         *  filtered;summer;degraded;flags;outdoor_after;summer_after - the filters' seconds are the tuning's,
         *  their values set before the cycle; the forecast as Facit.weather reads it. */
        @ParameterizedTest
        @CsvFileSource(resources = "/outdoor.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameOutdoor(String designOutdoor, String outdoorSeconds, String outdoorBefore, String summerSeconds,
                         String summerBefore, String measured, String forecast, String dt, String filtered,
                         String summer, String degraded, String flags, String outdoorAfter, String summerAfter) {
            Tuning tuning = new Tuning();
            tuning.designOutdoor = required(designOutdoor);
            tuning.outdoorFilterSeconds = required(outdoorSeconds);
            tuning.summerFilterSeconds = required(summerSeconds);
            HeatingController loop = made(tuning);
            loop.state.outdoor = new Ewma(tuning.outdoorFilterSeconds, number(outdoorBefore));
            loop.state.outdoorSummer = new Ewma(tuning.summerFilterSeconds, number(summerBefore));
            double seconds = required(dt);
            ControlInputs cycle = inputs(seconds);
            cycle.outdoor = number(measured);
            cycle.forecast = Facit.weather(forecast);
            ControlOutput out = new ControlOutput();
            Outdoors got = loop.outdoor(cycle, seconds, out);
            assertClose(required(filtered), got.filtered(), "filtered");
            assertClose(required(summer), got.summer(), "summer");
            assertEquals(words(degraded), out.degraded, "degraded");
            assertEquals(words(flags), out.flags, "flags");
            assertClose(number(outdoorAfter), loop.state.outdoor.value(), "outdoor.value after");
            assertClose(number(summerAfter), loop.state.outdoorSummer.value(), "outdoor_summer.value after");
        }

        /** room_target;solar_clamp;flow;indoor_seconds;indoor_before;slow_seconds;slow_before;dt;readings;
         *  fused;slow;flags;degraded;contributed;indoor_after;slow_after. */
        @ParameterizedTest
        @CsvFileSource(resources = "/indoor.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameIndoor(String roomTarget, String solarClamp, String flow, String indoorSeconds, String indoorBefore,
                        String slowSeconds, String slowBefore, String dt, String readings, String fused, String slow,
                        String flags, String degraded, String contributed, String indoorAfter, String slowAfter) {
            Tuning tuning = new Tuning();
            tuning.roomTarget = required(roomTarget);
            tuning.solarClamp = required(solarClamp);
            tuning.indoorFilterSeconds = required(indoorSeconds);
            tuning.indoorSlowFilterSeconds = required(slowSeconds);
            HeatingController loop = made(tuning);
            loop.state.indoor = new Ewma(tuning.indoorFilterSeconds, number(indoorBefore));
            loop.state.indoorSlow = new Ewma(tuning.indoorSlowFilterSeconds, number(slowBefore));
            double seconds = required(dt);
            ControlInputs cycle = inputs(seconds);
            cycle.flow = number(flow);
            cycle.indoor = readings(readings);
            ControlOutput out = new ControlOutput();
            Indoors got = loop.indoor(cycle, seconds, out);
            assertClose(number(fused), got.fused(), "fused");
            assertClose(number(slow), got.slow(), "slow");
            assertEquals(words(flags), out.flags, "flags");
            assertEquals(words(degraded), out.degraded, "degraded");
            assertEquals(words(contributed), out.indoorReadings.stream().map(d -> String.valueOf(d.contributed())).toList(),
                    "contributed");
            assertClose(number(indoorAfter), loop.state.indoor.value(), "indoor.value after");
            assertClose(number(slowAfter), loop.state.indoorSlow.value(), "indoor_slow.value after");
        }

        /** learn_sunlight;room_target;learn_over_target;sun_heat_seconds;dt;override;emergency;target_changed;
         *  sunlight;sun_elevation;cloud;sweeping;since_failsafe;flow;ret;sun_heat_before;slow_before;before;after;
         *  sun_heat_after;degraded - the clocks before and after as "name=f:value|...". An empty emergency is "". */
        @ParameterizedTest
        @CsvFileSource(resources = "/timers.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameTimers(String learnSunlight, String roomTarget, String learnOverTarget, String sunHeatSeconds, String dt,
                        String override, String emergency, String targetChanged, String sunlight, String sunElevation,
                        String cloud, String sweeping, String sinceFailsafe, String flow, String ret,
                        String sunHeatBefore, String slowBefore, String before, String after, String sunHeatAfter,
                        String degraded) {
            Tuning tuning = new Tuning();
            tuning.learnSunlight = required(learnSunlight);
            tuning.roomTarget = required(roomTarget);
            tuning.learnOverTarget = required(learnOverTarget);
            tuning.sunHeatSeconds = required(sunHeatSeconds);
            HeatingController loop = made(tuning);
            for (Map.Entry<String, Object> clock : dict(before).entrySet()) {
                clock(loop.state, clock.getKey(), (Double) clock.getValue());
            }
            loop.state.sunHeat = new Ewma(tuning.sunHeatSeconds, number(sunHeatBefore));
            loop.state.indoorSlow = new Ewma(tuning.indoorSlowFilterSeconds, number(slowBefore));
            double seconds = required(dt);
            ControlInputs cycle = inputs(seconds);
            cycle.overrideActive = bool(override);
            cycle.emergency = emergency == null ? Model.EMERGENCY_OFF : emergency;
            cycle.targetChanged = bool(targetChanged);
            cycle.sunlight = number(sunlight);
            cycle.sunElevation = number(sunElevation);
            cycle.cloud = number(cloud);
            cycle.sweeping = bool(sweeping);
            cycle.sinceFailsafe = number(sinceFailsafe);
            cycle.flow = number(flow);
            cycle.ret = number(ret);
            ControlOutput out = new ControlOutput();
            loop.timers(cycle, seconds, out);
            Map<String, Object> expected = dict(after);
            Map<String, Double> got = clocks(loop.state);
            assertEquals(CLOCKS, List.copyOf(expected.keySet()), "the facit's clocks");
            for (String name : CLOCKS) {
                assertClose((Double) expected.get(name), got.get(name), name);
            }
            assertClose(number(sunHeatAfter), loop.state.sunHeat.value(), "sun_heat.value after");
            assertEquals(words(degraded), out.degraded, "degraded");
        }

        /** tuning;slope_trim;balance_shift;bend;measured;filtered;curve_outdoor;effective_slope;balance;curvature;
         *  curve_flow;at;curve_at;at_slope;at_balance;at_curvature;curve_at_given - the tuning's curve fields as
         *  "name=f:value|...", curve_at(at) by default and with the three given (empty for None). */
        @ParameterizedTest
        @CsvFileSource(resources = "/curve.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameCurve(String tuningCell, String slopeTrim, String balanceShift, String bend, String measured,
                       String filtered, String curveOutdoor, String effectiveSlope, String balance, String curvature,
                       String curveFlow, String at, String curveAt, String atSlope, String atBalance, String atCurvature,
                       String curveAtGiven) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            loop.state.slopeTrim = required(slopeTrim);
            loop.state.balanceShift = required(balanceShift);
            loop.state.bend = number(bend);
            double reads = loop.curveOutdoor(number(measured), required(filtered));
            assertClose(required(curveOutdoor), reads, "curve_outdoor");
            CurvePoint point = loop.curve(reads);
            assertClose(required(effectiveSlope), point.slope(), "effective_slope");
            assertClose(required(effectiveSlope), loop.effectiveSlope(), "effective_slope()");
            assertClose(required(balance), loop.balance(), "balance");
            assertClose(required(curvature), loop.curvature(), "curvature");
            assertClose(required(curveFlow), point.flow(), "curve_flow");
            assertClose(required(curveAt), loop.curveAt(required(at)), "curve_at");
            assertClose(required(curveAtGiven),
                    loop.curveAt(required(at), number(atSlope), number(atBalance), number(atCurvature)), "curve_at given");
        }
    }
}
