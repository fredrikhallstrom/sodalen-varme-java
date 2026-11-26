package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.house.Model.DELTA_QUIET_DEMAND;
import static se.sodalen.heating.house.Model.DELTA_QUIET_LUKEWARM;
import static se.sodalen.heating.house.Model.DELTA_QUIET_OFF;
import static se.sodalen.heating.house.Model.DELTA_QUIET_SENSOR;
import static se.sodalen.heating.house.Model.LEARN_DELTA;
import static se.sodalen.heating.house.Model.MODE_HEATING;
import static se.sodalen.heating.house.Model.MODE_WARM;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.assertSameDict;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.estimate.DutyWatch;
import se.sodalen.heating.estimate.Ewma;

/**
 * The delta term and the radiator watch: how much heat the radiators take,
 * read from supply minus return, what was expected of them, the correction
 * of the supply it gives - judged only while the supply sits still - and
 * the warning for radiators that take too little heat from a cold house.
 *
 * <p>Python: controller.py (_delta, _said, _forget_delta, _delta_trim,
 * _watch_radiators, _delta_weight), tests/test_delta_settle.py and
 * tests/test_controller.py (TestDeltaCompensation, TestRadiatorWatch) -
 * there through step(), here one method at a time with the state set by
 * hand, since step() comes in step 20.
 *
 * <p>The filter is read fresh after every call ({@code loop.state.deltaFilter.value()}):
 * forgetting it may put a new Ewma in its place.
 */
class DeltaAndRadiatorsTest {

    static final double DT = 30.0;
    /** -5 outside: 26 of the 39 design degrees (21 - -18). */
    static final double DEMAND = 26.0 / 39.0;
    /** The expected delta at DEMAND with no scale learned: 10 * 26/39 = 6.67. */
    static final double EXPECTED = 10.0 * 26.0 / 39.0;
    /** -10 outside, as TestRadiatorWatch: 10 * 31/39 = 7.95. */
    static final double COLD_EXPECTED = 10.0 * 31.0 / 39.0;

    static HeatingController made(Tuning tuning) {
        return new HeatingController(tuning);
    }

    static HeatingController made() {
        return made(new Tuning());
    }

    static ControlInputs cycle(Double flow, Double ret) {
        ControlInputs inputs = new ControlInputs(DT);
        inputs.flow = flow;
        inputs.ret = ret;
        return inputs;
    }

    /** Sat on its setpoint for the five minutes: the next cycle there is judged. Not warming up after a stop. */
    static HeatingController still(HeatingController loop, double setpoint) {
        loop.state.lastFlowSetpoint = setpoint;
        loop.state.secondsSettled = loop.tuning.deltaSettleSeconds;
        return loop;
    }

    /** Something kept for _forget_delta to forget. */
    static HeatingController kept(HeatingController loop) {
        loop.state.deltaFilter = new Ewma(loop.tuning.deltaFilterSeconds, 3.0);
        loop.state.deltaTrimHeld = -2.0;
        loop.state.secondsSettled = 400.0;
        loop.state.secondsUnsettled = 100.0;
        return loop;
    }

    static void assertForgotten(HeatingController loop) {
        assertNull(loop.state.deltaFilter.value(), "the filter is empty");
        assertEquals(loop.tuning.deltaFilterSeconds, loop.state.deltaFilter.seconds(), 0.0, "on the same seconds");
        assertEquals(0.0, loop.state.deltaTrimHeld, 0.0, "nothing held");
        assertEquals(0.0, loop.state.secondsSettled, 0.0, "settled");
        assertEquals(0.0, loop.state.secondsUnsettled, 0.0, "unsettled");
    }

    /** One judging of the trim at 45 on a 45 setpoint, full weight. */
    static double trim(HeatingController loop, double flow, double delta, double expected, ControlOutput out) {
        return loop.deltaTrim(cycle(flow, flow - delta), delta, expected, 1.0, DT, out);
    }

    static double trim(HeatingController loop, double flow, double delta, double expected) {
        return trim(loop, flow, delta, expected, new ControlOutput());
    }

    /** The output of a cycle where _delta gave the delta its full weight. */
    static ControlOutput weighed() {
        ControlOutput out = new ControlOutput();
        out.deltaWeight = 1.0;
        return out;
    }

    /** A watch that has seen this share over these hours (weight in seconds), on the tuning's horizon. */
    static DutyWatch recorded(Double share, double hours) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("share", share);
        data.put("weight", hours * 3600.0);
        return DutyWatch.fromDict(data, new Tuning().starvedHorizonSeconds);
    }

    /** TestRadiatorWatch.run_hours: hours of heating cycles at -10 outside, the delta fully weighed. */
    static ControlOutput runHours(HeatingController loop, double hours, double indoor, double delta) {
        ControlOutput out = null;
        for (int i = 0; i < (int) (hours * 120); i++) {
            out = weighed();
            loop.watchRadiators(MODE_HEATING, indoor, delta, COLD_EXPECTED, DT, out);
        }
        return out;
    }

    /** _delta_weight: a ramp over delta_fade from delta_min_overtemp of supply over the room. */
    @Nested
    class TheWeight {
        final HeatingController loop = made();

        /** test_it_fades_in_rather_than_switching. */
        @Test
        void itFadesInRatherThanSwitching() {
            assertEquals(0.0, loop.deltaWeight(31.0, 21.0), 0.0);
            assertEquals(0.5, loop.deltaWeight(33.5, 21.0), 1e-12);
            assertEquals(1.0, loop.deltaWeight(36.0, 21.0), 0.0);
        }

        /** A tenth of a degree either side of the threshold is a tenth of a degree's worth of trim. */
        @Test
        void aTenthEitherSideOfTheThresholdIsATenthsWorth() {
            assertTrue(loop.deltaWeight(31.1, 21.0) - loop.deltaWeight(30.9, 21.0) < 0.05);
        }

        @Test
        void itIsHeldBetweenNoughtAndOne() {
            assertEquals(0.0, loop.deltaWeight(20.0, 21.0), 0.0, "under the room");
            assertEquals(1.0, loop.deltaWeight(60.0, 21.0), 0.0);
        }

        @Test
        void noRoomIsTheTarget() {
            assertEquals(0.5, loop.deltaWeight(33.5, null), 1e-12);
            Tuning tuning = new Tuning();
            tuning.roomTarget = 20.0;
            assertEquals(0.7, made(tuning).deltaWeight(33.5, null), 1e-12, "(33.5 - 20 - 10) / 5");
        }

        @Test
        void theRoomGivenCounts() {
            assertEquals(0.3, loop.deltaWeight(33.5, 22.0), 1e-12, "(33.5 - 22 - 10) / 5");
        }

        @Test
        void theThresholdAndTheFadeAreSettings() {
            Tuning tuning = new Tuning();
            tuning.deltaMinOvertemp = 5.0;
            tuning.deltaFade = 2.0;
            HeatingController set = made(tuning);
            assertEquals(0.5, set.deltaWeight(27.0, 21.0), 1e-12, "(27 - 21 - 5) / 2");
            assertEquals(1.0, set.deltaWeight(33.5, 21.0), 0.0);
        }

        /** No fade is the old switch: on from the threshold itself. */
        @Test
        void noFadeIsASwitch() {
            Tuning tuning = new Tuning();
            tuning.deltaFade = 0.0;
            HeatingController switched = made(tuning);
            assertEquals(1.0, switched.deltaWeight(31.0, 21.0), 0.0, "on the threshold");
            assertEquals(0.0, switched.deltaWeight(30.9, 21.0), 0.0);
            assertEquals(1.0, switched.deltaWeight(31.0, null), 0.0, "no room is the target here too");
        }

        @Test
        void aNegativeFadeIsASwitchToo() {
            Tuning tuning = new Tuning();
            tuning.deltaFade = -1.0;
            assertEquals(1.0, made(tuning).deltaWeight(31.0, 21.0), 0.0);
            assertEquals(0.0, made(tuning).deltaWeight(30.0, 21.0), 0.0);
        }
    }

    /** _delta's four quiet reasons: each says why, forgets what it kept and gives no correction. */
    @Nested
    class WhenItIsQuiet {
        /** test_no_return_sensor_disables_the_term. */
        @Test
        void noReturnSensorQuietsIt() {
            HeatingController loop = kept(made());
            ControlOutput out = new ControlOutput();
            Delta got = loop.delta(cycle(45.0, null), 21.0, DEMAND, out, DT, null, null);
            assertEquals(new Delta(null, null, 0.0), got);
            assertEquals(DELTA_QUIET_SENSOR, out.deltaQuiet);
            assertForgotten(loop);
        }

        @Test
        void noSupplySensorQuietsItToo() {
            HeatingController loop = kept(made());
            ControlOutput out = new ControlOutput();
            assertEquals(new Delta(null, null, 0.0), loop.delta(cycle(null, 43.0), 21.0, DEMAND, out, DT, null, null));
            assertEquals(DELTA_QUIET_SENSOR, out.deltaQuiet);
            assertForgotten(loop);
        }

        @Test
        void aMissingSensorIsSaidBeforeTheSwitch() {
            Tuning tuning = new Tuning();
            tuning.deltaEnabled = false;
            ControlOutput out = new ControlOutput();
            made(tuning).delta(cycle(45.0, null), 21.0, DEMAND, out, DT, null, null);
            assertEquals(DELTA_QUIET_SENSOR, out.deltaQuiet);
        }

        /** test_the_term_can_be_switched_off: the delta is still read and given. */
        @Test
        void theTermCanBeSwitchedOff() {
            Tuning tuning = new Tuning();
            tuning.deltaEnabled = false;
            HeatingController loop = kept(made(tuning));
            ControlOutput out = new ControlOutput();
            Delta got = loop.delta(cycle(45.0, 44.0), 21.0, DEMAND, out, DT, null, null);
            assertEquals(1.0, got.delta(), 1e-9);
            assertNull(got.expected());
            assertEquals(0.0, got.trim(), 0.0);
            assertEquals(DELTA_QUIET_OFF, out.deltaQuiet);
            assertForgotten(loop);
        }

        /** test_mild_weather_says_nothing_useful: 16 outside is 5/39 = 0.13 of the design demand. */
        @Test
        void lowDemandSaysNothingUseful() {
            HeatingController loop = kept(made());
            ControlOutput out = new ControlOutput();
            Delta got = loop.delta(cycle(24.0, 23.8), 21.0, 5.0 / 39.0, out, DT, null, null);
            assertEquals(0.2, got.delta(), 1e-9);
            assertNull(got.expected());
            assertEquals(0.0, got.trim(), 0.0);
            assertEquals(DELTA_QUIET_DEMAND, out.deltaQuiet);
            assertForgotten(loop);
        }

        @Test
        void onTheMinimumDemandItSpeaks() {
            ControlOutput out = new ControlOutput();
            Delta got = still(made(), 45.0).delta(cycle(45.0, 43.0), 21.0, 0.15, out, DT, null, null);
            assertEquals("", out.deltaQuiet);
            assertEquals(1.5, got.expected(), 1e-12, "10 * 0.15");
        }

        @Test
        void theMinimumDemandIsASetting() {
            Tuning tuning = new Tuning();
            tuning.deltaMinDemand = 0.5;
            ControlOutput out = new ControlOutput();
            made(tuning).delta(cycle(45.0, 43.0), 21.0, 0.4, out, DT, null, null);
            assertEquals(DELTA_QUIET_DEMAND, out.deltaQuiet);
        }

        /** test_a_lukewarm_supply_says_nothing_useful: 24 in a 21 house cannot give more than 3 of delta. */
        @Test
        void aLukewarmSupplySaysNothingUseful() {
            HeatingController loop = kept(made());
            ControlOutput out = new ControlOutput();
            Delta got = loop.delta(cycle(24.0, 23.9), 21.0, 16.0 / 39.0, out, DT, null, null);
            assertEquals(0.1, got.delta(), 1e-9);
            assertNull(got.expected());
            assertEquals(0.0, got.trim(), 0.0);
            assertEquals(DELTA_QUIET_LUKEWARM, out.deltaQuiet);
            assertEquals(0.0, out.deltaWeight, 0.0);
            assertForgotten(loop);
        }

        /** The room plus delta_min_overtemp: from there the delta is read at all. */
        @Test
        void aLukewarmSupplySaysWhereItIsReadFrom() {
            ControlOutput out = new ControlOutput();
            made().delta(cycle(24.0, 23.9), 21.0, 0.5, out, DT, null, null);
            assertEquals(31.0, out.deltaReadableFrom, 1e-12);
            out = new ControlOutput();
            made().delta(cycle(24.0, 23.9), 19.0, 0.5, out, DT, null, null);
            assertEquals(29.0, out.deltaReadableFrom, 1e-12, "the indoor temperature is the room");
            out = new ControlOutput();
            made().delta(cycle(24.0, 23.9), 19.0, 0.5, out, DT, null, 20.0);
            assertEquals(30.0, out.deltaReadableFrom, 1e-12, "the room given before the indoor");
            out = new ControlOutput();
            made().delta(cycle(24.0, 23.9), null, 0.5, out, DT, null, null);
            assertEquals(31.0, out.deltaReadableFrom, 1e-12, "neither: the target");
        }

        @Test
        void speakingItSaysNoReadableFrom() {
            ControlOutput out = new ControlOutput();
            still(made(), 45.0).delta(cycle(45.0, 43.0), 21.0, DEMAND, out, DT, null, null);
            assertNull(out.deltaReadableFrom);
            assertEquals("", out.deltaQuiet);
        }

        /** test_quiet_forgets_what_it_kept: after a quiet cycle a moving supply keeps nothing. */
        @Test
        void quietForgetsWhatItKept() {
            HeatingController loop = still(made(), 45.0);
            assertTrue(loop.delta(cycle(45.0, 44.0), 21.0, DEMAND, new ControlOutput(), DT, null, null).trim() < 0.0);
            loop.delta(cycle(45.0, null), 21.0, DEMAND, new ControlOutput(), DT, null, null);
            Delta got = loop.delta(cycle(40.0, 39.0), 21.0, DEMAND, new ControlOutput(), DT, null, null);
            assertEquals(0.0, got.trim(), 0.0);
        }
    }

    /** _delta's weight: from the base flow and the room when they are given. */
    @Nested
    class WhatTheWeightReads {
        /** test_the_gate_reads_the_setpoint_not_the_supply: either side of the threshold, the same answer. */
        @Test
        void theWeightReadsTheBaseFlowNotTheSupply() {
            for (double flow : List.of(30.5, 31.5)) {
                ControlOutput out = new ControlOutput();
                Delta got = still(made(), flow).delta(cycle(flow, flow - 0.1), 21.0, 16.0 / 39.0, out, DT, 45.0, null);
                assertEquals("", out.deltaQuiet, "flow " + flow);
                assertEquals(1.0, out.deltaWeight, 0.0, "flow " + flow);
                // -1.2 * (10 * 16/39 - 0.1) = -4.80
                assertEquals(-1.2 * (10.0 * 16.0 / 39.0 - 0.1), got.trim(), 1e-9, "flow " + flow);
            }
        }

        @Test
        void aLukewarmBaseFlowIsQuietOverAWarmSupply() {
            ControlOutput out = new ControlOutput();
            made().delta(cycle(45.0, 43.0), 21.0, DEMAND, out, DT, 30.0, null);
            assertEquals(DELTA_QUIET_LUKEWARM, out.deltaQuiet);
        }

        /** (40 - 28 - 10) / 5 = 0.4: the room given, not the indoor temperature. */
        @Test
        void theRoomGivenIsWeighedNotTheIndoor() {
            ControlOutput out = new ControlOutput();
            still(made(), 40.0).delta(cycle(40.0, 38.0), 21.0, DEMAND, out, DT, null, 28.0);
            assertEquals(0.4, out.deltaWeight, 1e-12);
        }

        @Test
        void noRoomGivenIsTheIndoor() {
            ControlOutput out = new ControlOutput();
            still(made(), 40.0).delta(cycle(40.0, 38.0), 28.0, DEMAND, out, DT, null, null);
            assertEquals(0.4, out.deltaWeight, 1e-12);
        }

        /** test_it_speaks_again_once_the_supply_is_warm: 34 in a 21 house weighs (34 - 21 - 10) / 5 = 0.6. */
        @Test
        void itSpeaksAgainOnceTheSupplyIsWarm() {
            ControlOutput out = new ControlOutput();
            Delta got = still(made(), 34.0).delta(cycle(34.0, 33.9), 21.0, 16.0 / 39.0, out, DT, null, null);
            assertEquals(0.6, out.deltaWeight, 1e-12);
            assertEquals(10.0 * 16.0 / 39.0, got.expected(), 1e-9);
            // 0.6 * -1.2 * (4.10 - 0.1) = -2.88
            assertEquals(0.6 * -1.2 * (10.0 * 16.0 / 39.0 - 0.1), got.trim(), 1e-9);
        }

        /** The short form is Python's defaults: no time, no base flow, no room. */
        @Test
        void theShortFormIsNoTimeNoBaseNoRoom() {
            Delta shortForm = still(made(), 45.0).delta(cycle(45.0, 43.0), 21.0, DEMAND, new ControlOutput());
            Delta longForm = still(made(), 45.0).delta(cycle(45.0, 43.0), 21.0, DEMAND, new ControlOutput(), 0.0, null, null);
            assertEquals(longForm, shortForm);
            assertEquals(-1.2 * (EXPECTED - 2.0), shortForm.trim(), 1e-9, "no time on a still supply is judged");
        }
    }

    /** _delta's expected delta: max(1, design * min(1.2, demand) * scale). */
    @Nested
    class TheExpectedDelta {
        Delta at(HeatingController loop, double demand) {
            return still(loop, 45.0).delta(cycle(45.0, 43.0), 21.0, demand, new ControlOutput(), DT, null, null);
        }

        /** test_collapsed_delta_lowers_the_supply_temperature: 2 against 6.67 is radiators taking almost nothing. */
        @Test
        void aCollapsedDeltaLowersTheSupply() {
            Delta got = at(made(), DEMAND);
            assertEquals(2.0, got.delta(), 1e-9);
            assertEquals(EXPECTED, got.expected(), 1e-9);
            // -1.2 * (6.67 - 2) = -5.6
            assertEquals(-1.2 * (EXPECTED - 2.0), got.trim(), 1e-9);
        }

        @Test
        void noScaleLearnedIsOne() {
            assertEquals(5.0, at(made(), 0.5).expected(), 1e-12);
        }

        /** test_the_expected_delta_is_the_circuits_scale_of_the_design. */
        @Test
        void theScaleMultipliesIt() {
            HeatingController loop = made();
            loop.state.deltaScale = new Ewma(loop.tuning.deltaExpectedHorizonSeconds, 0.5);
            assertEquals(0.5 * EXPECTED, at(loop, DEMAND).expected(), 1e-9);
        }

        @Test
        void theDemandCountsToOnePointTwoAtMost() {
            assertEquals(12.0, at(made(), 1.5).expected(), 1e-12);
            assertEquals(11.0, at(made(), 1.1).expected(), 1e-12);
        }

        /** 10 * 0.2 * 0.2 = 0.4: never under one. */
        @Test
        void neverUnderOne() {
            HeatingController loop = made();
            loop.state.deltaScale = new Ewma(loop.tuning.deltaExpectedHorizonSeconds, 0.2);
            assertEquals(1.0, at(loop, 0.2).expected(), 0.0);
        }

        @Test
        void theDesignIsASetting() {
            Tuning tuning = new Tuning();
            tuning.deltaDesign = 8.0;
            assertEquals(4.0, at(made(tuning), 0.5).expected(), 1e-12);
        }

        /** test_a_bigger_delta_than_expected_raises_it: -1.2 * (6.67 - 12) = 6.4, held to 2. */
        @Test
        void aBiggerDeltaThanExpectedRaisesIt() {
            Delta got = still(made(), 45.0).delta(cycle(45.0, 33.0), 20.9, DEMAND, new ControlOutput(), DT, null, null);
            assertEquals(2.0, got.trim(), 0.0);
        }

        /** test_the_correction_is_limited_in_both_directions: -15 outside, 36/39 of the demand. */
        @Test
        void theCorrectionIsLimitedInBothDirections() {
            // -1.2 * (9.23 - 0) = -11.1, held to -6
            Delta down = still(made(), 55.0).delta(cycle(55.0, 55.0), null, 36.0 / 39.0, new ControlOutput(), DT, null, null);
            assertEquals(-6.0, down.trim(), 0.0);
            // -1.2 * (6.67 - 35) = 34, held to 2
            Delta up = still(made(), 55.0).delta(cycle(55.0, 20.0), 20.9, DEMAND, new ControlOutput(), DT, null, null);
            assertEquals(2.0, up.trim(), 0.0);
        }
    }

    /** _delta_trim: judged only while the supply sits still; held while it moves; let go after half an hour. */
    @Nested
    class TheTrim {
        /** Judged at once on an empty filter: the reading itself, -1.2 * (6 - 2) = -4.8. */
        @Test
        void aStillSupplyIsJudgedOnItsReading() {
            HeatingController loop = still(made(), 45.0);
            loop.state.secondsUnsettled = 600.0;
            ControlOutput out = new ControlOutput();
            assertEquals(-4.8, trim(loop, 45.0, 2.0, 6.0, out), 1e-12);
            assertEquals(-4.8, loop.state.deltaTrimHeld, 1e-12, "and held");
            assertEquals(2.0, loop.state.deltaFilter.value(), 0.0, "the filter's first sample is taken whole");
            assertEquals(2.0, out.deltaRead, 0.0);
            assertEquals(0.0, loop.state.secondsUnsettled, 0.0);
            assertEquals(330.0, loop.state.secondsSettled, 0.0);
            assertEquals(List.of(), out.flags);
            assertNull(out.deltaWarmingLeft);
        }

        /** The quarter-hour filter: 4 toward 2 over 900 s is 2 + 2/e = 2.74. */
        @Test
        void throughTheFilter() {
            HeatingController loop = still(made(), 45.0);
            loop.state.deltaFilter = new Ewma(loop.tuning.deltaFilterSeconds, 4.0);
            ControlOutput out = new ControlOutput();
            double got = loop.deltaTrim(cycle(45.0, 43.0), 2.0, 6.0, 1.0, 900.0, out);
            double read = 2.0 + 2.0 * Math.exp(-1.0);
            assertEquals(read, loop.state.deltaFilter.value(), 1e-12);
            assertEquals(read, out.deltaRead, 1e-12);
            assertEquals(-1.2 * (6.0 - read), got, 1e-12);
        }

        @Test
        void theWeightScalesIt() {
            HeatingController loop = still(made(), 45.0);
            assertEquals(-2.4, loop.deltaTrim(cycle(45.0, 43.0), 2.0, 6.0, 0.5, DT, new ControlOutput()), 1e-12);
            assertEquals(-2.4, loop.state.deltaTrimHeld, 1e-12);
        }

        /** -1.2 * 10 = -12, held to -6, then half of it: the weight after the limit. */
        @Test
        void theLimitsComeBeforeTheWeight() {
            HeatingController loop = still(made(), 45.0);
            assertEquals(-3.0, loop.deltaTrim(cycle(45.0, 45.0), 0.0, 10.0, 0.5, DT, new ControlOutput()), 1e-12);
            loop = still(made(), 45.0);
            assertEquals(1.0, loop.deltaTrim(cycle(45.0, 35.0), 10.0, 6.0, 0.5, DT, new ControlOutput()), 1e-12,
                    "-1.2 * -4 = 4.8, held to 2, then half");
        }

        @Test
        void theLimitsAndTheGainAreSettings() {
            Tuning tuning = new Tuning();
            tuning.deltaGain = 0.5;
            tuning.deltaLowerLimit = 1.0;
            tuning.deltaRaiseLimit = 3.0;
            assertEquals(-1.0, trim(still(made(tuning), 45.0), 45.0, 2.0, 6.0), 1e-12, "-0.5 * 4 = -2, held to -1");
            assertEquals(2.5, trim(still(made(tuning), 45.0), 45.0, 11.0, 6.0), 1e-12, "-0.5 * -5 = 2.5");
            assertEquals(3.0, trim(still(made(tuning), 45.0), 45.0, 20.0, 6.0), 1e-12, "-0.5 * -14 = 7, held to 3");
        }

        /** test_nothing_is_judged_for_the_first_five_minutes: nine cycles of 30 s are 270, the tenth 300. */
        @Test
        void nothingIsJudgedForTheFirstFiveMinutes() {
            HeatingController loop = made();
            loop.state.lastFlowSetpoint = 45.0;
            for (int i = 1; i <= 9; i++) {
                ControlOutput out = new ControlOutput();
                assertEquals(0.0, trim(loop, 45.0, 2.0, 6.0, out), 0.0, "cycle " + i);
                assertEquals(List.of("delta_held_moving"), out.flags, "cycle " + i);
            }
            assertEquals(-4.8, trim(loop, 45.0, 2.0, 6.0), 1e-12, "five minutes");
        }

        @Test
        void noSetpointYetIsNotStill() {
            HeatingController loop = made();
            loop.state.secondsSettled = 400.0;
            ControlOutput out = new ControlOutput();
            assertEquals(0.0, trim(loop, 45.0, 2.0, 6.0, out), 0.0);
            assertEquals(0.0, loop.state.secondsSettled, 0.0);
            assertEquals(List.of("delta_held_moving"), out.flags);
        }

        /** test_within_a_degree_counts_as_still. */
        @Test
        void withinADegreeCountsAsStill() {
            HeatingController loop = still(made(), 45.0);
            for (int i = 0; i < 4; i++) {
                ControlOutput out = new ControlOutput();
                trim(loop, 44.1, 4.0, 6.0, out);
                assertFalse(out.flags.contains("delta_held_moving"));
            }
            assertEquals(420.0, loop.state.secondsSettled, 0.0, "300 and four cycles");
        }

        @Test
        void onTheBandIsStillOverItIsNot() {
            HeatingController loop = still(made(), 45.0);
            trim(loop, 46.0, 2.0, 6.0);
            assertEquals(330.0, loop.state.secondsSettled, 0.0, "a degree over");
            trim(loop, 46.1, 2.0, 6.0);
            assertEquals(0.0, loop.state.secondsSettled, 0.0, "more: starts over");
        }

        /** Band 3 and wait 60: 2.5 off is still, and judged on the second cycle; 3.1 off starts over. */
        @Test
        void theBandAndTheWaitAreSettings() {
            Tuning tuning = new Tuning();
            tuning.deltaSettleBand = 3.0;
            tuning.deltaSettleSeconds = 60.0;
            HeatingController loop = made(tuning);
            loop.state.lastFlowSetpoint = 45.0;
            assertEquals(0.0, trim(loop, 42.5, 2.0, 6.0), 0.0, "30 of 60: still on its way");
            assertEquals(-4.8, trim(loop, 42.5, 2.0, 6.0), 1e-12, "60 of 60: judged");
            trim(loop, 41.9, 2.0, 6.0);
            assertEquals(0.0, loop.state.secondsSettled, 0.0, "over the band: starts over");
        }

        /** test_the_last_correction_is_kept. */
        @Test
        void whileMovingTheLastCorrectionIsKept() {
            HeatingController loop = still(made(), 45.0);
            double before = trim(loop, 45.0, 2.0, 6.0);
            ControlOutput out = null;
            for (int i = 0; i < 10; i++) {
                out = new ControlOutput();
                assertEquals(before, trim(loop, 40.0, 7.5, 6.0, out), 0.0);
            }
            assertEquals(List.of("delta_held_moving"), out.flags);
            assertEquals(300.0, loop.state.secondsUnsettled, 0.0);
            assertEquals(2.0, loop.state.deltaFilter.value(), 0.0, "the filter is not fed while it moves");
        }

        /** test_a_collapse_of_the_delta_then_does_not_drive_it_to_its_limit. */
        @Test
        void aCollapseWhileMovingDoesNotDriveItToItsLimit() {
            HeatingController loop = still(made(), 45.0);
            double before = trim(loop, 45.0, 4.5, 6.0);
            double got = 0.0;
            for (int i = 0; i < 20; i++) {
                got = trim(loop, 42.0, 0.1, 6.0);
            }
            assertEquals(before, got, 0.0);
        }

        /** test_nor_does_a_slug_of_hot_water_drive_it_up. */
        @Test
        void norDoesASlugOfHotWater() {
            HeatingController loop = still(made(), 45.0);
            double before = trim(loop, 45.0, 4.5, 6.0);
            double got = 0.0;
            for (int i = 0; i < 20; i++) {
                got = trim(loop, 48.0, 8.0, 6.0);
            }
            assertEquals(before, got, 0.0);
        }

        /** test_and_the_mean_behind_a_kept_correction. */
        @Test
        void andTheMeanBehindAKeptCorrection() {
            HeatingController loop = still(made(), 45.0);
            loop.state.deltaFilter = new Ewma(loop.tuning.deltaFilterSeconds, 3.0);
            loop.state.secondsSettled = 0.0;
            ControlOutput out = new ControlOutput();
            trim(loop, 40.0, 7.0, 6.0, out);
            assertEquals(3.0, out.deltaRead, 0.0);
            ControlOutput empty = new ControlOutput();
            trim(still(made(), 45.0), 40.0, 7.0, 6.0, empty);
            assertNull(empty.deltaRead, "nothing kept, nothing read");
        }

        /** test_after_half_an_hour_without_settling_it_lets_go: 59 cycles of 30 s are 1770, the sixtieth 1800. */
        @Test
        void afterHalfAnHourWithoutSettlingItLetsGo() {
            HeatingController loop = still(made(), 45.0);
            double before = trim(loop, 45.0, 2.0, 6.0);
            for (int i = 0; i < 59; i++) {
                assertEquals(before, trim(loop, 40.0, 2.0, 6.0), 0.0, "cycle " + (i + 1));
            }
            ControlOutput out = new ControlOutput();
            assertEquals(0.0, trim(loop, 40.0, 2.0, 6.0, out), 0.0);
            assertEquals(0.0, loop.state.deltaTrimHeld, 0.0);
            assertNull(loop.state.deltaFilter.value(), "starts again afresh");
            assertEquals(loop.tuning.deltaFilterSeconds, loop.state.deltaFilter.seconds(), 0.0);
            assertEquals(List.of(), out.flags, "let go is not held");
        }

        /** test_none_once_it_has_let_go. */
        @Test
        void noneOnceItHasLetGo() {
            HeatingController loop = still(made(), 45.0);
            trim(loop, 45.0, 2.0, 6.0);
            loop.state.secondsUnsettled = 1770.0;
            ControlOutput out = new ControlOutput();
            trim(loop, 40.0, 2.0, 6.0, out);
            assertNull(out.deltaRead);
        }

        @Test
        void theHoldIsASetting() {
            Tuning tuning = new Tuning();
            tuning.deltaHoldMaxSeconds = 60.0;
            HeatingController loop = still(made(tuning), 45.0);
            double before = trim(loop, 45.0, 2.0, 6.0);
            assertEquals(before, trim(loop, 40.0, 2.0, 6.0), 0.0);
            assertEquals(0.0, trim(loop, 40.0, 2.0, 6.0), 0.0, "60 s");
        }

        /** test_the_half_hour_counts_from_the_last_time_it_sat_still: 1500 + 1500 would have let go. */
        @Test
        void theHalfHourCountsFromTheLastTimeItSatStill() {
            HeatingController loop = still(made(), 45.0);
            double before = trim(loop, 45.0, 2.0, 6.0);
            for (int i = 0; i < 50; i++) {
                trim(loop, 40.0, 2.0, 6.0);
            }
            for (int i = 0; i < 10; i++) {
                trim(loop, 45.0, 2.0, 6.0);
            }
            assertEquals(0.0, loop.state.secondsUnsettled, 0.0, "sat still five minutes");
            double got = 0.0;
            for (int i = 0; i < 50; i++) {
                got = trim(loop, 40.0, 2.0, 6.0);
            }
            assertEquals(before, got, 1e-12);
            assertEquals(loop.state.deltaTrimHeld, got, 0.0);
        }

        /** test_and_starts_again_afresh_once_settled: the old mean is gone, the new one starts on its reading. */
        @Test
        void andStartsAgainAfreshOnceSettled() {
            HeatingController loop = still(made(), 45.0);
            trim(loop, 45.0, 0.5, 6.0);
            for (int i = 0; i < 60; i++) {
                trim(loop, 40.0, 0.5, 6.0);
            }
            for (int i = 1; i <= 9; i++) {
                assertEquals(0.0, trim(loop, 45.0, 5.0, 6.0), 0.0, "settling, cycle " + i);
            }
            assertEquals(-1.2, trim(loop, 45.0, 5.0, 6.0), 1e-12, "-1.2 * (6 - 5)");
            assertEquals(5.0, loop.state.deltaFilter.value(), 0.0);
        }

        /** test_a_short_dip_moves_it_only_a_filters_worth: 4.4 * (1 - e^(-1/30)) = 0.14 of reading, 0.17 of trim. */
        @Test
        void aShortDipMovesItOnlyAFiltersWorth() {
            HeatingController loop = still(made(), 45.0);
            loop.state.deltaFilter = new Ewma(loop.tuning.deltaFilterSeconds, 4.5);
            double before = -1.2 * (6.0 - 4.5);
            double got = trim(loop, 45.0, 0.1, 6.0);
            assertTrue(Math.abs(got - before) < 0.5, got + " against " + before);
            assertEquals(-1.2 * (6.0 - (4.5 - 4.4 * (1.0 - Math.exp(-1.0 / 30.0)))), got, 1e-12);
        }

        /** test_a_lasting_change_is_followed: an hour toward 0.5 from 4.5 reads 0.5 + 4/e^4 = 0.57. */
        @Test
        void aLastingChangeIsFollowed() {
            HeatingController loop = still(made(), 45.0);
            loop.state.deltaFilter = new Ewma(loop.tuning.deltaFilterSeconds, 4.5);
            double got = 0.0;
            for (int i = 0; i < 120; i++) {
                got = trim(loop, 45.0, 0.5, 4.0);
            }
            assertEquals(0.5 + 4.0 * Math.exp(-4.0), loop.state.deltaFilter.value(), 1e-9);
            assertTrue(got < -3.0, "-1.2 * (4 - 0.57) = -4.1: " + got);
        }

        /** test_for_three_quarters_of_an_hour_nothing_is_judged: forty minutes after a stop. */
        @Test
        void afterAStopNothingIsJudged() {
            HeatingController loop = kept(still(made(), 45.0));
            loop.state.secondsSinceStop = 2400.0;
            ControlOutput out = new ControlOutput();
            assertEquals(0.0, trim(loop, 45.0, 9.0, 6.0, out), 0.0);
            assertEquals(List.of("delta_warming"), out.flags);
            assertNull(loop.state.deltaFilter.value(), "the warm-up is not in the mean either");
            assertEquals(loop.tuning.deltaFilterSeconds, loop.state.deltaFilter.seconds(), 0.0);
            assertEquals(0.0, loop.state.deltaTrimHeld, 0.0);
            assertEquals(0.0, loop.state.secondsSettled, 0.0);
            assertNull(out.deltaRead);
        }

        /** test_an_old_mean_is_thrown_away_not_carried_through. */
        @Test
        void theWarmUpThrowsAnOldMeanAway() {
            HeatingController loop = still(made(), 45.0);
            loop.state.secondsSinceStop = 60.0;
            loop.state.deltaFilter = new Ewma(loop.tuning.deltaFilterSeconds, 9.0);
            trim(loop, 45.0, 9.0, 6.0);
            assertNull(loop.state.deltaFilter.value());
        }

        /** test_what_is_left_is_said: 2700 - 900. */
        @Test
        void whatIsLeftIsSaid() {
            HeatingController loop = still(made(), 45.0);
            loop.state.secondsSinceStop = 900.0;
            ControlOutput out = new ControlOutput();
            trim(loop, 45.0, 9.0, 6.0, out);
            assertEquals(1800.0, out.deltaWarmingLeft, 0.0);
        }

        /** test_after_that_it_is: the three quarters of an hour are up. */
        @Test
        void onTheWarmUpLineItIsJudged() {
            HeatingController loop = still(made(), 45.0);
            loop.state.secondsSinceStop = 2700.0;
            ControlOutput out = new ControlOutput();
            assertEquals(2.0, trim(loop, 45.0, 9.0, 6.0, out), 0.0, "-1.2 * -3 = 3.6, held to 2");
            assertFalse(out.flags.contains("delta_warming"));
            assertNull(out.deltaWarmingLeft);
        }

        @Test
        void theWarmUpIsASetting() {
            Tuning tuning = new Tuning();
            tuning.deltaWarmupSeconds = 600.0;
            HeatingController loop = still(made(tuning), 45.0);
            loop.state.secondsSinceStop = 900.0;
            assertEquals(2.0, trim(loop, 45.0, 9.0, 6.0), 0.0);
        }

        /** test_a_house_heating_all_along_is_judged_at_once: a fresh state has not stopped. */
        @Test
        void aHouseHeatingAllAlongIsJudgedAtOnce() {
            assertTrue(trim(still(made(), 45.0), 45.0, 9.0, 6.0) > 0.0);
        }
    }

    /** _delta's last word: the indoor loop's, in both directions. */
    @Nested
    class TheRoomHasTheLastWord {
        Delta at(double ret, Double indoor, ControlOutput out) {
            return still(made(), 45.0).delta(cycle(45.0, ret), indoor, DEMAND, out, DT, null, null);
        }

        /** test_a_cold_house_keeps_its_heat: closed valves somewhere in a cold house are not free heat. */
        @Test
        void aColdHouseKeepsItsHeat() {
            ControlOutput out = new ControlOutput();
            assertEquals(0.0, at(43.0, 19.5, out).trim(), 0.0);
            assertEquals(List.of("delta_held_cold"), out.flags);
        }

        @Test
        void aColdHouseIsUnderTheGuard() {
            ControlOutput out = new ControlOutput();
            assertEquals(0.0, at(43.0, 20.6, out).trim(), 0.0);
            ControlOutput over = new ControlOutput();
            assertEquals(-1.2 * (EXPECTED - 2.0), at(43.0, 20.8, over).trim(), 1e-9, "inside the guard");
            assertEquals(List.of(), over.flags);
        }

        /** Strict on both sides: exactly target - guard is not cold, exactly target + guard is not warm. */
        @Test
        void onTheGuardTheRoomHasNoSay() {
            ControlOutput cold = new ControlOutput();
            assertEquals(-1.2 * (EXPECTED - 2.0), at(43.0, 21.0 - 0.3, cold).trim(), 1e-9, "on the cold guard");
            assertEquals(List.of(), cold.flags);
            assertEquals(2.0, at(25.0, 21.0 + 0.3, new ControlOutput()).trim(), 0.0, "on the warm guard");
        }

        /** test_a_warm_house_gets_no_extra: and says nothing of it. */
        @Test
        void aWarmHouseGetsNoExtra() {
            ControlOutput out = new ControlOutput();
            assertEquals(0.0, at(25.0, 22.0, out).trim(), 0.0);
            assertEquals(List.of(), out.flags);
            assertEquals(2.0, at(25.0, 21.2, new ControlOutput()).trim(), 0.0, "inside the guard");
        }

        @Test
        void aColdHouseMayBeRaised() {
            assertEquals(2.0, at(25.0, 19.5, new ControlOutput()).trim(), 0.0);
        }

        @Test
        void aWarmHouseMayBeLowered() {
            assertEquals(-1.2 * (EXPECTED - 2.0), at(43.0, 22.0, new ControlOutput()).trim(), 1e-9);
        }

        @Test
        void noIndoorLeavesItBe() {
            ControlOutput out = new ControlOutput();
            assertEquals(-1.2 * (EXPECTED - 2.0), at(43.0, null, out).trim(), 1e-9);
            assertEquals(List.of(), out.flags);
        }

        @Test
        void theGuardIsASetting() {
            Tuning tuning = new Tuning();
            tuning.deltaColdGuard = 1.0;
            ControlOutput out = new ControlOutput();
            Delta got = still(made(tuning), 45.0).delta(cycle(45.0, 43.0), 20.5, DEMAND, out, DT, null, null);
            assertEquals(-1.2 * (EXPECTED - 2.0), got.trim(), 1e-9);
        }

        /** The room given is for the weight; the guard reads the indoor temperature. */
        @Test
        void theGuardReadsTheIndoorNotTheRoom() {
            ControlOutput out = new ControlOutput();
            Delta got = still(made(), 45.0).delta(cycle(45.0, 43.0), 19.5, DEMAND, out, DT, null, 21.0);
            assertEquals(0.0, got.trim(), 0.0);
            assertEquals(List.of("delta_held_cold"), out.flags);
        }

        /** What is held is still what was judged: the guard only answers this cycle. */
        @Test
        void theHeldCorrectionIsNotOverruled() {
            HeatingController loop = still(made(), 45.0);
            loop.delta(cycle(45.0, 43.0), 19.5, DEMAND, new ControlOutput(), DT, null, null);
            assertEquals(-1.2 * (EXPECTED - 2.0), loop.state.deltaTrimHeld, 1e-9);
        }

        /** test_the_room_still_has_the_last_word_over_a_kept_correction. */
        @Test
        void overAKeptCorrectionToo() {
            HeatingController loop = made();
            loop.state.lastFlowSetpoint = 45.0;
            loop.state.deltaTrimHeld = -2.0;
            ControlOutput out = new ControlOutput();
            Delta got = loop.delta(cycle(40.0, 39.0), 20.0, DEMAND, out, DT, null, null);
            assertEquals(0.0, got.trim(), 0.0);
            assertEquals(List.of("delta_held_moving", "delta_held_cold"), out.flags);
        }
    }

    /** _said and _forget_delta. */
    @Nested
    class WhatItSays {
        /** Outside a cycle there is no output to say it in. */
        @Test
        void withoutACycleNothingIsSaid() {
            HeatingController loop = made();
            assertNull(loop.out, "none by default");
            assertDoesNotThrow(() -> loop.said(LEARN_DELTA, false, "no_data", null));
            assertNull(loop.out);
        }

        @Test
        void inACycleItIsSaidInItsOutput() {
            HeatingController loop = made();
            ControlOutput output = new ControlOutput();
            loop.out = output;
            loop.said(LEARN_DELTA, false, "wait", 120.0);
            assertEquals(Map.of(LEARN_DELTA, new LearnerStatus(false, "wait", 120.0)), output.learning);
        }

        @Test
        void aSecondVerdictReplacesTheFirst() {
            HeatingController loop = made();
            ControlOutput output = new ControlOutput();
            loop.out = output;
            loop.said(LEARN_DELTA, false, "wait", 120.0);
            loop.said(Model.LEARN_CURVE, true, "", null);
            loop.said(LEARN_DELTA, true, "", null);
            assertEquals(new LearnerStatus(true, "", null), output.learning.get(LEARN_DELTA));
            assertEquals(List.of(LEARN_DELTA, Model.LEARN_CURVE), List.copyOf(output.learning.keySet()),
                    "in the order first said");
        }

        @Test
        void forgettingEmptiesTheFilterAndTheClocks() {
            HeatingController loop = kept(made());
            loop.forgetDelta();
            assertForgotten(loop);
        }

        /** The filter is emptied, not replaced by a default one: its time constant is the tuning's. */
        @Test
        void forgettingKeepsTheFiltersTimeConstant() {
            Tuning tuning = new Tuning();
            tuning.deltaFilterSeconds = 300.0;
            HeatingController loop = kept(made(tuning));
            loop.forgetDelta();
            assertForgotten(loop);
            assertEquals(300.0, loop.state.deltaFilter.seconds(), 0.0);
        }

        /** The scale is a week's learning, and the setpoint is the loop's: neither is the delta's to forget. */
        @Test
        void forgettingKeepsTheScaleAndTheSetpoint() {
            HeatingController loop = kept(made());
            loop.state.deltaScale = new Ewma(loop.tuning.deltaExpectedHorizonSeconds, 0.7);
            loop.state.lastFlowSetpoint = 44.0;
            loop.forgetDelta();
            assertEquals(0.7, loop.state.deltaScale.value(), 0.0);
            assertEquals(44.0, loop.state.lastFlowSetpoint, 0.0);
        }
    }

    /** _watch_radiators: a low delta from a cold house, over a good share of the last few days. */
    @Nested
    class TheRadiatorWatch {
        /** One cycle that would be held, if judged. */
        void starving(HeatingController loop, String mode, ControlOutput out, boolean sweeping) {
            loop.watchRadiators(mode, 19.5, 2.0, COLD_EXPECTED, DT, out, sweeping);
        }

        static void assertNotJudged(HeatingController loop) {
            assertNull(loop.state.starved.share(), "nothing decided");
            assertEquals(0.0, loop.state.starved.hours(), 0.0);
        }

        static void assertJudged(HeatingController loop, double share) {
            assertEquals(share, loop.state.starved.share(), 0.0);
            assertEquals(DT / 3600.0, loop.state.starved.hours(), 1e-12);
        }

        @Test
        void aColdHouseWithALowDeltaIsHeld() {
            HeatingController loop = made();
            loop.watchRadiators(MODE_HEATING, 19.5, 2.0, COLD_EXPECTED, DT, weighed());
            assertJudged(loop, 1.0);
        }

        @Test
        void onlyWhileHeating() {
            HeatingController loop = made();
            starving(loop, MODE_WARM, weighed(), false);
            assertNotJudged(loop);
        }

        /** test_a_sweep_is_not_judged_at_all: a boiler left to cool looks just like a radiator fault. */
        @Test
        void aSweepIsNotJudged() {
            HeatingController loop = made();
            starving(loop, MODE_HEATING, weighed(), true);
            assertNotJudged(loop);
        }

        /** test_a_sweep_is_not_judged_even_with_no_time_to_recover: its own gate, apart from the time after it. */
        @Test
        void aSweepIsNotJudgedEvenWithNoTimeToRecover() {
            Tuning tuning = new Tuning();
            tuning.learnAfterOverrideSeconds = 0.0;
            HeatingController loop = made(tuning);
            loop.state.secondsSinceSweep = 0.0;
            starving(loop, MODE_HEATING, weighed(), true);
            assertNotJudged(loop);
            starving(loop, MODE_HEATING, weighed(), false);
            assertJudged(loop, 1.0);
        }

        @Test
        void notForTwoHoursAfterASweep() {
            HeatingController loop = made();
            loop.state.secondsSinceSweep = 7199.0;
            starving(loop, MODE_HEATING, weighed(), false);
            assertNotJudged(loop);
            loop.state.secondsSinceSweep = 7200.0;
            starving(loop, MODE_HEATING, weighed(), false);
            assertJudged(loop, 1.0);
        }

        /** test_a_lukewarm_supply_is_not_judged_at_all: only where the delta is fully trusted. */
        @Test
        void onlyAtTheFullWeight() {
            HeatingController loop = made();
            ControlOutput out = new ControlOutput();
            out.deltaWeight = 0.99;
            starving(loop, MODE_HEATING, out, false);
            assertNotJudged(loop);
        }

        @Test
        void notWithoutAnIndoorADeltaOrAnExpectedOne() {
            HeatingController loop = made();
            loop.watchRadiators(MODE_HEATING, null, 2.0, COLD_EXPECTED, DT, weighed());
            loop.watchRadiators(MODE_HEATING, 19.5, null, COLD_EXPECTED, DT, weighed());
            loop.watchRadiators(MODE_HEATING, 19.5, 2.0, null, DT, weighed());
            assertNotJudged(loop);
        }

        /** Not judged, the time still passes: what was seen fades, the share stays. 6 h * e^(-1/72) = 5.92. */
        @Test
        void unjudgedTimeStillPasses() {
            HeatingController loop = made();
            loop.state.starved = recorded(0.8, 6.0);
            ControlOutput out = weighed();
            loop.watchRadiators(MODE_WARM, 19.5, 2.0, COLD_EXPECTED, 3600.0, out);
            assertEquals(0.8, loop.state.starved.share(), 0.0);
            assertEquals(6.0 * Math.exp(-1.0 / 72.0), loop.state.starved.hours(), 1e-9);
            assertFalse(out.radiatorsStarved, "under the six hours now");
        }

        /** test_free_heat_is_not_a_fault: the sun or a stove, which the delta term is for. */
        @Test
        void aWarmHouseIsNotHeld() {
            HeatingController loop = made();
            loop.watchRadiators(MODE_HEATING, 21.5, 2.0, COLD_EXPECTED, DT, weighed());
            assertJudged(loop, 0.0);
        }

        /** test_a_healthy_delta_in_a_cold_house_is_not_a_fault: the curve too low, not the radiators. */
        @Test
        void aHealthyDeltaIsNotHeld() {
            HeatingController loop = made();
            loop.watchRadiators(MODE_HEATING, 19.5, 7.5, COLD_EXPECTED, DT, weighed());
            assertJudged(loop, 0.0);
        }

        /** Under the target by the guard: 21 - 0.3 = 20.7. */
        @Test
        void coldIsUnderTheGuard() {
            HeatingController held = made();
            held.watchRadiators(MODE_HEATING, 20.6, 2.0, COLD_EXPECTED, DT, weighed());
            assertJudged(held, 1.0);
            HeatingController not = made();
            not.watchRadiators(MODE_HEATING, 20.8, 2.0, COLD_EXPECTED, DT, weighed());
            assertJudged(not, 0.0);
        }

        /** Low is the margin's share of the expected under it: 10 - 6.9 = 3.1 is, 10 - 7.1 = 2.9 is not. */
        @Test
        void lowIsAShareOfTheExpected() {
            HeatingController held = made();
            held.watchRadiators(MODE_HEATING, 19.5, 6.9, 10.0, DT, weighed());
            assertJudged(held, 1.0);
            HeatingController not = made();
            not.watchRadiators(MODE_HEATING, 19.5, 7.1, 10.0, DT, weighed());
            assertJudged(not, 0.0);
        }

        @Test
        void theMarginIsASetting() {
            Tuning tuning = new Tuning();
            tuning.starvedMargin = 0.1;
            HeatingController loop = made(tuning);
            loop.watchRadiators(MODE_HEATING, 19.5, 8.5, 10.0, DT, weighed());
            assertJudged(loop, 1.0);
        }

        /** No time passing changes nothing in the watch: the share and the hours are read as recorded. */
        @Test
        void upAtTheShareOverTheHours() {
            HeatingController loop = made();
            loop.state.starved = recorded(0.5, 6.0);
            ControlOutput out = weighed();
            starving(loop, MODE_HEATING, out, false);
            loop.watchRadiators(MODE_HEATING, 19.5, 2.0, COLD_EXPECTED, 0.0, out = weighed());
            assertTrue(out.radiatorsStarved);
            assertTrue(loop.starved);
            assertEquals(List.of("radiators_starved"), out.flags);
        }

        @Test
        void notUnderTheShare() {
            HeatingController loop = made();
            loop.state.starved = recorded(0.49, 7.0);
            ControlOutput out = weighed();
            loop.watchRadiators(MODE_HEATING, 19.5, 2.0, COLD_EXPECTED, 0.0, out);
            assertFalse(out.radiatorsStarved);
            assertFalse(loop.starved);
            assertEquals(List.of(), out.flags);
        }

        @Test
        void notUnderTheHours() {
            HeatingController loop = made();
            loop.state.starved = recorded(1.0, 5.99);
            ControlOutput out = weighed();
            loop.watchRadiators(MODE_HEATING, 19.5, 2.0, COLD_EXPECTED, 0.0, out);
            assertFalse(out.radiatorsStarved);
        }

        @Test
        void noShareIsNone() {
            HeatingController loop = made();
            loop.state.starved = recorded(null, 7.0);
            ControlOutput out = weighed();
            loop.watchRadiators(MODE_WARM, 19.5, 2.0, COLD_EXPECTED, 0.0, out);
            assertFalse(out.radiatorsStarved);
        }

        @Test
        void theShareAndTheHoursAreSettings() {
            Tuning tuning = new Tuning();
            tuning.starvedShare = 0.3;
            tuning.starvedMinHours = 2.0;
            HeatingController loop = made(tuning);
            loop.state.starved = recorded(0.31, 2.5);
            ControlOutput out = weighed();
            loop.watchRadiators(MODE_WARM, 19.5, 2.0, COLD_EXPECTED, 0.0, out);
            assertTrue(out.radiatorsStarved);
        }

        /** test_a_share_on_the_line_does_not_flicker: up at 0.5, down only under 0.8 of it, 0.4. */
        @Test
        void aShareOnTheLineDoesNotFlicker() {
            HeatingController loop = made();
            loop.starved = true;
            loop.state.starved = recorded(0.41, 7.0);
            ControlOutput out = weighed();
            loop.watchRadiators(MODE_WARM, 21.0, 7.5, COLD_EXPECTED, 0.0, out);
            assertTrue(out.radiatorsStarved, "under the line it went up at: still up");
            loop.state.starved = recorded(0.39, 7.0);
            loop.watchRadiators(MODE_WARM, 21.0, 7.5, COLD_EXPECTED, 0.0, out = weighed());
            assertFalse(out.radiatorsStarved, "under the release line: down");
            assertFalse(loop.starved);
            loop.state.starved = recorded(0.49, 7.0);
            loop.watchRadiators(MODE_WARM, 21.0, 7.5, COLD_EXPECTED, 0.0, out = weighed());
            assertFalse(out.radiatorsStarved, "and it takes the full share to go up again");
            loop.state.starved = recorded(0.5, 7.0);
            loop.watchRadiators(MODE_WARM, 21.0, 7.5, COLD_EXPECTED, 0.0, out = weighed());
            assertTrue(out.radiatorsStarved);
        }

        @Test
        void theReleaseIsFourFifths() {
            assertEquals(0.8, HeatingController.STARVED_RELEASE, 0.0);
        }

        /** test_a_cold_house_with_a_collapsed_delta_is_reported: 8 h of 72 weigh 72 * (1 - e^(-1/9)) = 7.6 h. */
        @Test
        void aColdHouseWithACollapsedDeltaIsReported() {
            HeatingController loop = made();
            ControlOutput out = runHours(loop, 8, 19.5, 2.0);
            assertTrue(out.radiatorsStarved);
            assertEquals(List.of("radiators_starved"), out.flags);
            assertEquals(1.0, loop.state.starved.share(), 0.0);
            assertEquals(72.0 * (1.0 - Math.exp(-8.0 / 72.0)), loop.state.starved.hours(), 0.01);
        }

        @Test
        void freeHeatIsNotAFault() {
            assertFalse(runHours(made(), 8, 21.5, 2.0).radiatorsStarved);
        }

        @Test
        void aHealthyDeltaInAColdHouseIsNotAFault() {
            assertFalse(runHours(made(), 8, 19.5, 7.5).radiatorsStarved);
        }

        /** test_an_evening_is_not_enough: cold rooms after a setback, radiators still warming up. */
        @Test
        void anEveningIsNotEnough() {
            HeatingController loop = made();
            ControlOutput out = runHours(loop, 2, 19.5, 2.0);
            assertFalse(out.radiatorsStarved);
            assertEquals(1.0, loop.state.starved.share(), 0.0, "all of it held, but only two hours");
        }

        /** test_it_clears_once_the_radiators_take_heat_again: 5.4 h held against 20.4 h not is 0.21. */
        @Test
        void itClearsOnceTheRadiatorsTakeHeatAgain() {
            HeatingController loop = made();
            assertTrue(runHours(loop, 8, 19.5, 2.0).radiatorsStarved);
            ControlOutput out = runHours(loop, 24, 19.5, 7.5);
            assertFalse(out.radiatorsStarved);
            assertFalse(loop.starved);
            assertEquals(List.of(), out.flags);
        }

        @Test
        void theOutputIsTheWarning() {
            HeatingController loop = made();
            loop.starved = true;
            loop.state.starved = recorded(0.0, 7.0);
            ControlOutput out = weighed();
            out.radiatorsStarved = true;
            loop.watchRadiators(MODE_WARM, 21.0, 7.5, COLD_EXPECTED, 0.0, out);
            assertFalse(out.radiatorsStarved);
            assertFalse(loop.starved);
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        static final List<String> TRIM_STATE = List.of("seconds_since_stop", "last_flow_setpoint", "seconds_settled",
                "seconds_unsettled", "delta_trim_held", "delta_filter");

        static List<String> words(String cell) {
            return cell == null || cell.isEmpty() ? List.of() : Arrays.asList(cell.split("\\|", -1));
        }

        /** The state _delta_trim reads and writes, from a stored dict; the filter on its own seconds. */
        static void setTrimState(LearnedState state, Map<String, Object> before) {
            assertEquals(TRIM_STATE, before.keySet().stream().filter(TRIM_STATE::contains).toList(), "the facit's state");
            state.secondsSinceStop = (Double) before.get("seconds_since_stop");
            state.lastFlowSetpoint = (Double) before.get("last_flow_setpoint");
            state.secondsSettled = (Double) before.get("seconds_settled");
            state.secondsUnsettled = (Double) before.get("seconds_unsettled");
            state.deltaTrimHeld = (Double) before.get("delta_trim_held");
            state.deltaFilter = new Ewma(state.deltaFilter.seconds(), (Double) before.get("delta_filter"));
        }

        static Map<String, Object> trimState(LearnedState state) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seconds_since_stop", state.secondsSinceStop);
            out.put("last_flow_setpoint", state.lastFlowSetpoint);
            out.put("seconds_settled", state.secondsSettled);
            out.put("seconds_unsettled", state.secondsUnsettled);
            out.put("delta_trim_held", state.deltaTrimHeld);
            out.put("delta_filter", state.deltaFilter.value());
            return out;
        }

        /** room_target;delta_min_overtemp;delta_fade;supply;room;weight. */
        @ParameterizedTest
        @CsvFileSource(resources = "/delta_weight.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameDeltaWeight(String roomTarget, String minOvertemp, String fade, String supply, String room,
                             String weight) {
            Tuning tuning = new Tuning();
            tuning.roomTarget = required(roomTarget);
            tuning.deltaMinOvertemp = required(minOvertemp);
            tuning.deltaFade = required(fade);
            assertClose(required(weight), made(tuning).deltaWeight(required(supply), number(room)), "weight");
        }

        /** tuning;before;flow;delta;expected;weight;dt;trim;flags;delta_read;warming_left;after - the tuning's
         *  trim fields and the trim's state (the filter by its value) as "name=f:value|...", flags by "|". */
        @ParameterizedTest
        @CsvFileSource(resources = "/delta_trim.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameDeltaTrim(String tuningCell, String before, String flow, String delta, String expected, String weight,
                           String dt, String trim, String flags, String deltaRead, String warmingLeft, String after) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            setTrimState(loop.state, dict(before));
            double seconds = required(dt);
            ControlInputs inputs = new ControlInputs(seconds);
            inputs.flow = number(flow);
            ControlOutput out = new ControlOutput();
            double got = loop.deltaTrim(inputs, required(delta), required(expected), required(weight), seconds, out);
            assertClose(required(trim), got, "trim");
            assertEquals(words(flags), out.flags, "flags");
            assertClose(number(deltaRead), out.deltaRead, "delta_read");
            assertClose(number(warmingLeft), out.deltaWarmingLeft, "delta_warming_left");
            assertSameDict(dict(after), trimState(loop.state), "state after");
        }

        /** tuning;before;flow;ret;indoor;demand;dt;base_flow;room;delta;expected;trim;quiet;weight;readable_from;
         *  read;warming_left;flags;after - the state as for the trim, delta_scale (its value) first. */
        @ParameterizedTest
        @CsvFileSource(resources = "/delta.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameDelta(String tuningCell, String before, String flow, String ret, String indoor, String demand, String dt,
                       String baseFlow, String room, String delta, String expected, String trim, String quiet,
                       String weight, String readableFrom, String read, String warmingLeft, String flags,
                       String after) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            Map<String, Object> was = dict(before);
            loop.state.deltaScale = new Ewma(loop.state.deltaScale.seconds(), (Double) was.get("delta_scale"));
            setTrimState(loop.state, was);
            double seconds = required(dt);
            ControlInputs inputs = new ControlInputs(seconds);
            inputs.flow = number(flow);
            inputs.ret = number(ret);
            ControlOutput out = new ControlOutput();
            Delta got = loop.delta(inputs, number(indoor), required(demand), out, seconds, number(baseFlow), number(room));
            assertClose(number(delta), got.delta(), "delta");
            assertClose(number(expected), got.expected(), "expected");
            assertClose(required(trim), got.trim(), "trim");
            assertEquals(quiet == null ? "" : quiet, out.deltaQuiet, "delta_quiet");
            assertClose(required(weight), out.deltaWeight, "delta_weight");
            assertClose(number(readableFrom), out.deltaReadableFrom, "delta_readable_from");
            assertClose(number(read), out.deltaRead, "delta_read");
            assertClose(number(warmingLeft), out.deltaWarmingLeft, "delta_warming_left");
            assertEquals(words(flags), out.flags, "flags");
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("delta_scale", loop.state.deltaScale.value());
            state.putAll(trimState(loop.state));
            assertSameDict(dict(after), state, "state after");
        }

        /** tuning;seconds_since_sweep;watch_before;starved_before;mode;indoor;delta;expected;dt;sweeping;delta_weight;
         *  judged_held;radiators_starved;flags;watch_after;starved_after - the watch as its as_dict; judged_held
         *  is what _watch_radiators fed it (empty for None), seen in watch_after. */
        @ParameterizedTest
        @CsvFileSource(resources = "/watch_radiators.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameWatchRadiators(String tuningCell, String sinceSweep, String watchBefore, String starvedBefore,
                                String mode, String indoor, String delta, String expected, String dt, String sweeping,
                                String deltaWeight, String judgedHeld, String radiatorsStarved, String flags,
                                String watchAfter, String starvedAfter) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            Map<String, Object> watch = dict(watchBefore);
            loop.state.starved = DutyWatch.fromDict(watch, (Double) watch.get("seconds"));
            loop.state.secondsSinceSweep = required(sinceSweep);
            loop.starved = bool(starvedBefore);
            ControlOutput out = new ControlOutput();
            out.deltaWeight = required(deltaWeight);
            loop.watchRadiators(mode, number(indoor), number(delta), number(expected), required(dt), out,
                    bool(sweeping));
            assertEquals(bool(radiatorsStarved), out.radiatorsStarved, "radiators_starved");
            assertEquals(bool(starvedAfter), loop.starved, "_starved after");
            assertEquals(words(flags), out.flags, "flags");
            assertSameDict(dict(watchAfter), loop.state.starved.asDict(), "starved after");
        }
    }
}
