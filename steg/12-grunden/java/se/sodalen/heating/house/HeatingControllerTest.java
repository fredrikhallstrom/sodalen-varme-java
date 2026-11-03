package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.sodalen.heating.house.HeatingController.COLD_CLOCKS;
import static se.sodalen.heating.house.HeatingController.COLD_WHY;
import static se.sodalen.heating.house.HeatingController.STARVED_RELEASE;
import static se.sodalen.heating.house.HeatingController.SUN_RULE_LIGHT;
import static se.sodalen.heating.house.HeatingController.SUN_RULE_NONE;
import static se.sodalen.heating.house.HeatingController.SUN_RULE_TERM;
import static se.sodalen.heating.house.HeatingController.clamp;
import static se.sodalen.heating.house.HeatingController.deadbanded;
import static se.sodalen.heating.house.HeatingController.risingSlowly;
import static se.sodalen.heating.house.HeatingController.sunlightNow;
import static se.sodalen.heating.house.HeatingController.toward;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.number;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.curve.Curve;
import se.sodalen.heating.curve.PointTable;
import se.sodalen.heating.estimate.Ewma;
import se.sodalen.heating.forecast.Ensemble;
import se.sodalen.heating.valve.ValveMap;

/**
 * The ground the controller stands on: one house's tuning, learned state and
 * learners, held together and retuned together; a new tuning swapped in
 * without losing what was learned, but for the slope's trim when the
 * installer turns the curve; the burner's lead; and the small helpers the
 * later steps lean on. step() and everything it calls come in steps 13-20.
 *
 * <p>Python: controller.py (the module's helpers, HeatingController's
 * constructor, apply_tuning, _retune_learners, burner_lead_seconds),
 * tests/test_controller.py (TestTurningTheCurve) and tests/test_valvemap.py
 * (the rise of the valve's gain).
 */
class HeatingControllerTest {

    /** The same samples into a fit, so that two fits configured alike answer alike. */
    static double[] taught(HouseModel house) {
        Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            double[] x = new double[9];
            for (int k = 0; k < x.length; k++) {
                x[k] = random.nextGaussian();
            }
            house.fit().add(x, random.nextGaussian(), 600.0);
        }
        return house.fit().coefficients();
    }

    /** HeatingController(tuning, state=None, house=None, ensemble=None, valve_map=None, points=None). */
    @Nested
    class TheController {
        @Test
        void aNewStateStartsAtTheTuningsGainReference() {
            Tuning tuning = new Tuning();
            tuning.gainReference = 0.6;
            HeatingController made = new HeatingController(tuning);
            assertNotNull(made.state);
            assertEquals(0.6, made.state.processGain, 0.0);
        }

        @Test
        void whatItIsGivenItKeeps() {
            Tuning tuning = new Tuning();
            LearnedState state = new LearnedState();
            HouseModel house = new HouseModel();
            Ensemble ensemble = new Ensemble();
            ValveMap valveMap = new ValveMap();
            PointTable points = new PointTable();
            HeatingController made = new HeatingController(tuning, state, house, ensemble, valveMap, points);
            assertSame(tuning, made.tuning);
            assertSame(state, made.state);
            assertSame(house, made.house);
            assertSame(ensemble, made.ensemble);
            assertSame(valveMap, made.valveMap);
            assertSame(points, made.points);
        }

        @Test
        void whatItIsNotGivenItMakesItsOwn() {
            Tuning tuning = new Tuning();
            HeatingController one = new HeatingController(tuning);
            HeatingController other = new HeatingController(tuning, null, null, null, null, null);
            assertSame(tuning, one.tuning);
            for (HeatingController made : List.of(one, other)) {
                assertNotNull(made.house);
                assertNotNull(made.ensemble);
                assertNotNull(made.valveMap);
                assertNotNull(made.points);
            }
            assertNotSame(one.state, other.state);
            assertNotSame(one.house, other.house);
            assertNotSame(one.ensemble, other.ensemble);
            assertNotSame(one.valveMap, other.valveMap);
            assertNotSame(one.points, other.points);
        }

        @Test
        void aGivenStateKeepsItsGain() {
            Tuning tuning = new Tuning();
            tuning.gainReference = 0.6;
            LearnedState state = new LearnedState();
            state.processGain = 0.9;
            assertEquals(0.9, new HeatingController(tuning, state, null, null, null, null).state.processGain, 0.0);
        }

        /** self.state.retune(tuning): a state stored under another tuning gets this one's lengths. */
        @Test
        void theStateIsRetunedToTheTuning() {
            Tuning tuning = new Tuning();
            tuning.outdoorFilterSeconds = 1234.0;
            tuning.learnHorizonSeconds = 4321.0;
            LearnedState state = new LearnedState();
            state.outdoor = new Ewma(5.0);
            HeatingController made = new HeatingController(tuning, state, null, null, null, null);
            assertEquals(1234.0, made.state.outdoor.seconds(), 0.0);
            assertEquals(4321.0, made.state.curveFit.horizon(), 0.0);
        }

        /** _retune_learners: the house's three fits and the forecast sources get the tuning's horizons. */
        @Test
        void theHouseAndTheForecastsGetTheTuningsHorizons() {
            Tuning tuning = new Tuning();
            tuning.thermalHorizonSeconds = 12345.0;
            tuning.forecastHorizonSeconds = 5432.0;
            HouseModel house = new HouseModel();
            Ensemble ensemble = new Ensemble();
            HeatingController made = new HeatingController(tuning, null, house, ensemble, null, null);
            assertEquals(12345.0, made.house.fit().horizon(), 0.0);
            assertEquals(12345.0, made.house.steady().horizon(), 0.0);
            assertEquals(12345.0, made.house.night().horizon(), 0.0);
            assertEquals(5432.0, made.ensemble.horizon(), 0.0);
        }

        /**
         * house.set_design(design_span, delta_design, thermal_sample_seconds): the house's priors
         * come from the tuning, so its fit answers as one set up so by hand - and not as a house
         * left at the defaults, whose priors are for half-hourly samples, not the tuning's 600 s.
         */
        @Test
        void theHouseGetsTheTuningsDesign() {
            Tuning tuning = new Tuning();
            tuning.roomTarget = 20.0;
            tuning.deltaDesign = 12.0;
            HouseModel byHand = new HouseModel();
            byHand.retune(tuning.thermalHorizonSeconds);
            byHand.setDesign(tuning.designSpan(), tuning.deltaDesign, tuning.thermalSampleSeconds);
            HeatingController made = new HeatingController(tuning);
            assertArrayEquals(taught(byHand), taught(made.house), 1e-12);
            HeatingController plain = new HeatingController(new Tuning());
            double[] untouched = taught(new HouseModel());
            double[] designed = taught(plain.house);
            assertFalse(Math.abs(untouched[0] - designed[0]) < 1e-9, "the defaults are not the tuning's design");
        }

        @Test
        void nothingIsFittedYet() {
            HeatingController made = new HeatingController(new Tuning());
            assertNull(made.tableFit);
            assertNull(made.houseBalance);
            assertNull(made.houseNightBalance);
            assertEquals(Double.POSITIVE_INFINITY, made.sinceFit, 0.0);
            assertFalse(made.starved);
        }
    }

    /**
     * TestTurningTheCurve: whoever turns the curve gets that curve - the slope's learned trim
     * goes when the installer's slope moves. On 2026-10-15 the curve in use was 1.9, from 3.2
     * set and a trim of -1.3; kept, the trim would have run a 1.9 set at 0.3. (The curve in use
     * and the table's refit are steps 13 and 19.)
     */
    @Nested
    class ApplyingTuning {
        HeatingController learned() {
            Tuning tuning = new Tuning();
            tuning.roomTarget = 21.5;
            tuning.curveSlope = Curve.evrSlope(3.16, 21.5, tuning.designOutdoor);
            LearnedState state = new LearnedState();
            state.slopeTrim = -0.245;
            state.balanceShift = -7.7;
            return new HeatingController(tuning, state, null, null, null, null);
        }

        Tuning copy(Tuning tuning) {
            return Model.tuningFromDict(Map.of(), tuning);
        }

        HeatingController turned(HeatingController made, double number) {
            Tuning tuning = copy(made.tuning);
            tuning.curveSlope = Curve.evrSlope(number, 21.5, tuning.designOutdoor);
            made.applyTuning(tuning);
            return made;
        }

        @Test
        void theCurveTurnedTakesTheTrimAway() {
            assertEquals(0.0, turned(learned(), 2.5).state.slopeTrim, 0.0);
        }

        @Test
        void otherTuningKeepsTheTrim() {
            HeatingController made = learned();
            Tuning tuning = copy(made.tuning);
            tuning.flowMax = 60.0;
            made.applyTuning(tuning);
            assertEquals(-0.245, made.state.slopeTrim, 0.0);
        }

        @Test
        void theSameCurveAgainKeepsTheTrim() {
            assertEquals(-0.245, turned(learned(), 3.16).state.slopeTrim, 0.0);
        }

        @Test
        void theBalanceStaysLearned() {
            assertEquals(-7.7, turned(learned(), 2.0).state.balanceShift, 0.0);
        }

        /** abs(new - old) > 1e-9: a slope moved by less than a billionth has not been turned. */
        @Test
        void aBillionthIsNoTurn() {
            HeatingController made = learned();
            Tuning tuning = copy(made.tuning);
            tuning.curveSlope += 1e-10;
            made.applyTuning(tuning);
            assertEquals(-0.245, made.state.slopeTrim, 0.0);
            tuning = copy(made.tuning);
            tuning.curveSlope -= 1e-8;
            made.applyTuning(tuning);
            assertEquals(0.0, made.state.slopeTrim, 0.0, "a hundred millionth is");
        }

        @Test
        void theNewTuningIsTheOneInUseAndTheStateTheSame() {
            HeatingController made = learned();
            LearnedState state = made.state;
            Tuning tuning = copy(made.tuning);
            made.applyTuning(tuning);
            assertSame(tuning, made.tuning);
            assertSame(state, made.state);
        }

        /** _since_fit = inf: the table's fit and the house's balance from the new tuning at the next cycle. */
        @Test
        void theTableRefitsAtTheNextCycle() {
            HeatingController made = learned();
            made.sinceFit = 0.0;
            made.applyTuning(copy(made.tuning));
            assertEquals(Double.POSITIVE_INFINITY, made.sinceFit, 0.0);
        }

        @Test
        void theStateAndTheLearnersAreRetuned() {
            HeatingController made = learned();
            Tuning tuning = copy(made.tuning);
            tuning.indoorFilterSeconds = 777.0;
            tuning.thermalHorizonSeconds = 4444.0;
            tuning.forecastHorizonSeconds = 5555.0;
            made.applyTuning(tuning);
            assertEquals(777.0, made.state.indoor.seconds(), 0.0);
            assertEquals(4444.0, made.house.fit().horizon(), 0.0);
            assertEquals(5555.0, made.ensemble.horizon(), 0.0);
        }

        @Test
        void andTheHouseItsNewDesign() {
            HeatingController made = learned();
            Tuning tuning = copy(made.tuning);
            tuning.deltaDesign = 14.0;
            made.applyTuning(tuning);
            HouseModel byHand = new HouseModel();
            byHand.retune(tuning.thermalHorizonSeconds);
            byHand.setDesign(tuning.designSpan(), tuning.deltaDesign, tuning.thermalSampleSeconds);
            assertArrayEquals(taught(byHand), taught(made.house), 1e-12);
        }
    }

    /** burner_lead_seconds(seconds=None): set by the coordinator from the burner's profile, else half an hour. */
    @Nested
    class TheBurnersLead {
        @Test
        void halfAnHourUntilTold() {
            HeatingController made = new HeatingController(new Tuning());
            assertEquals(1800.0, made.burnerLeadSeconds(), 0.0);
            assertEquals(1800.0, made.burnerLeadSeconds(null), 0.0);
        }

        @Test
        void toldItKeepsIt() {
            HeatingController made = new HeatingController(new Tuning());
            assertEquals(300.0, made.burnerLeadSeconds(300.0), 0.0);
            assertEquals(300.0, made.burnerLeadSeconds(), 0.0);
            assertEquals(300.0, made.burnerLeadSeconds(null), 0.0, "nothing told changes nothing");
            assertEquals(0.0, made.burnerLeadSeconds(0.0), 0.0, "nought is told too");
        }

        @Test
        void eachControllerItsOwn() {
            HeatingController slow = new HeatingController(new Tuning());
            HeatingController quick = new HeatingController(new Tuning());
            slow.burnerLeadSeconds(1800.0);
            quick.burnerLeadSeconds(300.0);
            assertEquals(1800.0, slow.burnerLeadSeconds(), 0.0);
            assertEquals(300.0, quick.burnerLeadSeconds(), 0.0);
        }
    }

    /** _deadbanded: the error as the PID works on it (pid_step's deadband). */
    @Nested
    class TheDeadband {
        @Test
        void noBandIsTheErrorAsItIs() {
            assertEquals(0.7, deadbanded(0.7, 0.0), 0.0);
            assertEquals(-0.7, deadbanded(-0.7, -0.5), 0.0, "a band below nought is none");
        }

        @Test
        void outsideTheBandTheBandComesOff() {
            assertEquals(0.2, deadbanded(0.7, 0.5), 1e-12);
            assertEquals(-0.2, deadbanded(-0.7, 0.5), 1e-12);
        }

        @Test
        void insideTheBandAndOnItsEdgeIsNothing() {
            assertEquals(0.0, deadbanded(0.3, 0.5), 0.0);
            assertEquals(0.0, deadbanded(-0.3, 0.5), 0.0);
            assertEquals(0.0, deadbanded(0.5, 0.5), 0.0, "error > deadband, strictly");
            assertEquals(0.0, deadbanded(-0.5, 0.5), 0.0);
        }
    }

    /** _clamp: min(high, max(low, value)) - and with the limits crossed, high. */
    @Nested
    class TheClamp {
        @Test
        void betweenTheLimits() {
            assertEquals(1.0, clamp(5.0, 0.0, 1.0), 0.0);
            assertEquals(0.0, clamp(-5.0, 0.0, 1.0), 0.0);
            assertEquals(0.5, clamp(0.5, 0.0, 1.0), 0.0);
        }

        /** Not Math.clamp, which throws when low > high; Python's min/max gives high. */
        @Test
        void withTheLimitsCrossedItIsHigh() {
            assertEquals(0.0, clamp(0.5, 1.0, 0.0), 0.0);
            assertEquals(2.0, clamp(5.0, 3.0, 2.0), 0.0);
            assertThrows(IllegalArgumentException.class, () -> Math.clamp(0.5, 1.0, 0.0), "the trap");
        }
    }

    /** _toward: value moved toward goal by at most step. */
    @Nested
    class Toward {
        @Test
        void aStepAtATime() {
            assertEquals(1.5, toward(1.0, 3.0, 0.5), 1e-12);
            assertEquals(0.5, toward(1.0, -3.0, 0.5), 1e-12);
        }

        @Test
        void neverPastTheGoal() {
            assertEquals(3.0, toward(1.0, 3.0, 100.0), 0.0);
            assertEquals(-3.0, toward(1.0, -3.0, 100.0), 0.0);
        }

        @Test
        void noStepIsNoMove() {
            assertEquals(1.0, toward(1.0, 3.0, 0.0), 0.0);
        }

        /** A step below nought crosses the clamp's limits, and the clamp gives the high one: the step itself. */
        @Test
        void aStepBelowNoughtGoesBackward() {
            assertEquals(0.0, toward(1.0, 3.0, -1.0), 0.0);
            assertEquals(0.0, toward(1.0, -3.0, -1.0), 0.0);
        }
    }

    /**
     * _rising_slowly, from test_valvemap.py: the valve's gain is gentler at once and bolder only
     * over Tuning.kp_rise_seconds. At 09:07 on 2026-10-15 the valve went 10, 24, 11, 23, 13, 22 %
     * one cycle to the next with Kp 8.75, 2.3, 6.6, 2.3, 6.6: the gain at each position threw it
     * to the other.
     */
    @Nested
    class RisingSlowly {
        @Test
        void gentlerAtOnce() {
            assertEquals(2.0, risingSlowly(8.0, 2.0, 30.0, 900.0), 0.0);
        }

        @Test
        void bolderByTheTimeConstant() {
            assertEquals(2.0 + 6.0 * (1.0 - Math.exp(-1.0)), risingSlowly(2.0, 8.0, 900.0, 900.0), 1e-9);
        }

        @Test
        void nothingBeforeIsTheMapAsItIs() {
            assertEquals(8.0, risingSlowly(null, 8.0, 30.0, 900.0), 0.0);
        }

        @Test
        void noTimeConstantIsTheMapAsItIs() {
            assertEquals(8.0, risingSlowly(2.0, 8.0, 30.0, 0.0), 0.0);
        }

        @Test
        void noTimePassingIsNoRise() {
            assertEquals(2.0, risingSlowly(2.0, 8.0, -30.0, 900.0), 0.0);
        }

        @Test
        void aTimeConstantBelowZeroIsNone() {
            assertEquals(8.0, risingSlowly(2.0, 8.0, 30.0, -900.0), 0.0);
        }

        @Test
        void theSameAsBeforeIsTheSame() {
            assertEquals(2.0, risingSlowly(2.0, 2.0, 30.0, 900.0), 0.0);
        }
    }

    /** sunlight_now: the light on the walls this cycle, kW/m2, or the old guess in the same unit. */
    @Nested
    class TheSun {
        ControlInputs cycle(Double sunlight, Double elevation, Double cloud) {
            ControlInputs inputs = new ControlInputs(30.0);
            inputs.sunlight = sunlight;
            inputs.sunElevation = elevation;
            inputs.cloud = cloud;
            return inputs;
        }

        @Test
        void theWallsLightAsItIs() {
            assertEquals(0.42, sunlightNow(cycle(0.42, 10.0, 1.0)), 0.0);
        }

        @Test
        void neverLessThanNothing() {
            assertEquals(0.0, sunlightNow(cycle(-0.2, 30.0, 0.0)), 0.0);
        }

        @Test
        void withoutWallsTheSunsHeightAndTheCloud() {
            assertEquals(Thermal.SUNLIGHT_PER_INDEX * Thermal.solarIndex(30.0, 0.2), sunlightNow(cycle(null, 30.0, 0.2)), 0.0);
        }

        /** A light that is not a number is not known: the guess, not a NaN on the walls. */
        @Test
        void aLightThatIsNoNumberIsNotKnown() {
            double guess = Thermal.SUNLIGHT_PER_INDEX * Thermal.solarIndex(30.0, 0.2);
            for (double light : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertEquals(guess, sunlightNow(cycle(light, 30.0, 0.2)), 0.0, String.valueOf(light));
            }
        }

        @Test
        void noSunAndNoLightIsNoGuess() {
            assertEquals(Thermal.SUNLIGHT_PER_INDEX * Thermal.solarIndex(null, null), sunlightNow(cycle(null, null, null)), 0.0);
        }
    }

    @Nested
    class TheConstants {
        /** The clock each cause of a cold house waits on, and the learning card's code then, in COLD_CAUSES' order. */
        @Test
        void theColdClocksAndWhys() {
            assertEquals(Model.COLD_CAUSES, List.copyOf(COLD_CLOCKS.keySet()));
            assertEquals(List.of("seconds_since_sweep", "seconds_since_coast", "seconds_since_shortfall", "seconds_since_off"),
                    List.copyOf(COLD_CLOCKS.values()));
            assertEquals(Model.COLD_CAUSES, List.copyOf(COLD_WHY.keySet()));
            assertEquals(List.of("sweep_cold", "coast_cold", "shortfall_cold", "off_cold"), List.copyOf(COLD_WHY.values()));
        }

        @Test
        void theSunRules() {
            assertEquals("light", SUN_RULE_LIGHT);
            assertEquals("term", SUN_RULE_TERM);
            assertEquals("none", SUN_RULE_NONE);
        }

        @Test
        void theRadiatorWarningComesDownUnderEightTenths() {
            assertEquals(0.8, STARVED_RELEASE, 0.0);
        }

        @Test
        void theMapsAreNotForChanging() {
            assertThrows(UnsupportedOperationException.class, () -> COLD_CLOCKS.put("x", "y"));
            assertThrows(UnsupportedOperationException.class, () -> COLD_WHY.put("x", "y"));
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        static double[] args(String cell) {
            String[] parts = cell.split(",", -1);
            double[] out = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                Double value = number(parts[i]);
                out[i] = value == null ? Double.NaN : value;
            }
            return out;
        }

        /** function;args;expected - deadbanded(error, deadband), clamp(value, low, high), toward(value, goal, step),
         *  rising_slowly(last, wanted, dt, seconds) with an empty last for None. */
        @ParameterizedTest
        @CsvFileSource(resources = "/helpers.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void sameHelper(String function, String args, String expected) {
            double[] a = args(args);
            double answer = switch (function) {
                case "deadbanded" -> deadbanded(a[0], a[1]);
                case "clamp" -> clamp(a[0], a[1], a[2]);
                case "toward" -> toward(a[0], a[1], a[2]);
                case "rising_slowly" -> risingSlowly(args.startsWith(",") ? null : a[0], a[1], a[2], a[3]);
                default -> throw new AssertionError("no such helper: " + function);
            };
            assertClose(number(expected), answer, function + "(" + args + ")");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/sunlight_now.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void sameSun(String sunlight, String elevation, String cloud, String expected) {
            ControlInputs inputs = new ControlInputs(30.0);
            inputs.sunlight = number(sunlight);
            inputs.sunElevation = number(elevation);
            inputs.cloud = number(cloud);
            assertClose(number(expected), sunlightNow(inputs), "sunlight_now");
        }
    }
}
