package se.sodalen.heating.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.curve.Dots.DESIGN;
import static se.sodalen.heating.curve.Dots.ROOM;
import static se.sodalen.heating.curve.Dots.bin;
import static se.sodalen.heating.curve.Dots.dots;
import static se.sodalen.heating.curve.Dots.fit;
import static se.sodalen.heating.curve.Dots.greenOn;
import static se.sodalen.heating.curve.Dots.greensOn;
import static se.sodalen.heating.curve.Dots.keywords;
import static se.sodalen.heating.curve.Dots.need;
import static se.sodalen.heating.curve.Dots.outdoors;
import static se.sodalen.heating.curve.Dots.plus;
import static se.sodalen.heating.curve.Dots.withMoreNeed;
import static se.sodalen.heating.curve.Dots.withNeed;
import static se.sodalen.heating.curve.Facit.assertClose;
import static se.sodalen.heating.curve.Facit.assertCloseList;
import static se.sodalen.heating.curve.Facit.bins;
import static se.sodalen.heating.curve.Facit.bool;
import static se.sodalen.heating.curve.Facit.number;
import static se.sodalen.heating.curve.Facit.numbers;
import static se.sodalen.heating.curve.Fitting.BEND_COLD;
import static se.sodalen.heating.curve.Fitting.BEND_GRID;
import static se.sodalen.heating.curve.Fitting.BEND_PRIOR;
import static se.sodalen.heating.curve.Fitting.DEFAULT_PRIOR_WEIGHT;
import static se.sodalen.heating.curve.Fitting.MAX_NUMBER_SHIFT;
import static se.sodalen.heating.curve.Fitting.MIN_CHAIN;
import static se.sodalen.heating.curve.Fitting.OUTLIER;
import static se.sodalen.heating.curve.Fitting.fitCurve;
import static se.sodalen.heating.curve.Fitting.greenChain;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * The curve through the table's dots. Given the balance, the slope is the
 * one number left that the dots decide, by weighted least squares with the
 * installer's slope as a quarter of a dot's say, and it may only move as
 * far as the trusted dots reach. The bend is looked for once trusted dots
 * reach into the cold, and held to bend_limit. Dots far from the fit are
 * dropped and the fit made again, once. And Fredrik's rule: with
 * max_green_gap, trusted dots only count as an unbroken chain.
 *
 * <p>Python: curvefit.py and the fit's half of tests/test_points.py
 * (TestTheFit, TestTheChain, TestTheBend). The table itself is step 5.
 */
class CurveFitTest {

    /** assertAlmostEqual's places=6. */
    static final double PLACES = 1e-6;

    /** TestTheBend.WIDE: trusted dots from +7 down to -17, a degree short of design cold. */
    static final double[] WIDE = {7.0, 3.0, -1.0, -5.0, -9.0, -13.0, -17.0};

    /** The tuples of outdoor temperatures the Python tests compare: the same length, each within delta. */
    static void assertOutdoors(List<Double> expected, List<Double> actual, double delta) {
        assertEquals(expected.size(), actual.size(), "how many: " + actual);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), delta, "[" + i + "] of " + actual);
        }
    }

    static void assertColdest(double expected, CurveFit result) {
        assertNotNull(result.coldestGreen(), "coldest_green");
        assertEquals(expected, result.coldestGreen(), 0.0);
    }

    /** The slope: least squares through the dots, held to what their reach allows. */
    @Nested
    class TheFit {
        @Test
        void itFindsTheSlopeTheDotsWereMadeWith() {
            List<Bin> made = dots(0.75, 11.0, 0.8, 5.0, 1.0, -3.0, -7.0, -11.0, -15.0);
            assertEquals(0.75, fit(made, keywords().prior(0.9)).slope(), 0.02);
        }

        /**
         * Yellow dots are heard, but the slope may only go as far as the
         * trusted dots reach - and with none, that is nowhere.
         */
        @Test
        void withoutATrustedDotItDoesNotMove() {
            List<Bin> made = dots(0.6, 11.0, 0.8, new double[] {3.0, -1.0}, 20.0);
            for (Bin item : made) {
                assertEquals(Status.YELLOW, item.status(), "at " + item.outdoor());
            }
            assertEquals(0.9, fit(made, keywords().prior(0.9)).slope(), 0.0);
        }

        @Test
        void mildTrustedDotsMoveItOnlyPartOfTheWay() {
            List<Bin> made = dots(0.6, 11.0, 0.8, 7.0, 5.0);
            CurveFit result = fit(made, keywords().prior(0.9));
            double reach = (11.0 - 5.0) / (11.0 - DESIGN);
            double allowed = MAX_NUMBER_SHIFT * reach;
            double moved = Curve.evrNumber(0.9, ROOM, DESIGN) - Curve.evrNumber(result.slope(), ROOM, DESIGN);
            assertEquals(allowed, moved, PLACES, "held at the reach limit");
            assertTrue(result.slope() > 0.6, "slope " + result.slope());
        }

        /**
         * Truth at EVR 2, a curve and a tenth from the prior: beyond what
         * dots to +5 allow, within what dots to -12 do.
         */
        @Test
        void colderTrustedDotsLetItGoFurther() {
            double mild = fit(dots(0.7, 11.0, 0.8, 7.0, 5.0), keywords().prior(0.9)).slope();
            double cold = fit(dots(0.7, 11.0, 0.8, 7.0, 5.0, -5.0, -12.0), keywords().prior(0.9)).slope();
            assertTrue(cold < mild, cold + " >= " + mild);
            assertEquals(0.7, cold, 0.02);
        }

        /** A stove lit through a whole bin's measurements. */
        @Test
        void aDotFarFromTheRestIsLeftOut() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.0, 1.0, -3.0, -7.0, -11.0);
            Bin odd = made.get(2);
            made.set(2, withNeed(odd, odd.need() + 8.0));
            CurveFit result = fit(made, keywords().prior(0.8));
            assertOutdoors(List.of(odd.outdoor()), result.rejected(), 0.0);
            assertEquals(0.8, result.slope(), 0.01);
        }

        @Test
        void theColdestTrustedDotIsReported() {
            List<Bin> made = plus(dots(0.8, 11.0, 0.8, 5.0, -3.0), dots(0.8, 11.0, 0.8, new double[] {-9.0}, 10.0));
            CurveFit result = fit(made);
            assertNotNull(result.coldestGreen());
            assertEquals(-3.0, result.coldestGreen(), 0.01);
        }
    }

    /** Only an unbroken chain of trusted dots counts (curvefit.green_chain). */
    @Nested
    class TheChain {
        @Test
        void theLongestRunWins() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 9.0, 7.0, 1.0, -1.0, -3.0);
            assertOutdoors(List.of(1.0, -1.0, -3.0), outdoors(greenChain(made, 2.0)), PLACES);
        }

        @Test
        void atATieTheColderOne() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 9.0, 7.0, -1.0, -3.0);
            assertOutdoors(List.of(-1.0, -3.0), outdoors(greenChain(made, 2.0)), PLACES);
        }

        @Test
        void yellowDotsDoNotJoinIt() {
            List<Bin> made = plus(dots(0.8, 11.0, 0.8, 5.0, 1.0), dots(0.8, 11.0, 0.8, new double[] {3.0}, 10.0));
            assertEquals(1, greenChain(made, 2.0).size(), "a yellow +3 does not bridge");
        }

        /** Means near the far edges of neighbouring bins are still neighbours. */
        @Test
        void theGapCountsBinsNotTheMeanOutdoorTemperature() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.9, 2.1);
            assertEquals(2, greenChain(made, 2.0).size());
        }

        @Test
        void greensBeyondAGapAreLeftOutOfTheFit() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.0, 3.0, -11.0);
            CurveFit result = fit(made, keywords().maxGreenGap(2.0));
            assertOutdoors(List.of(5.0, 3.0), result.chain(), 0.0);
            assertColdest(3.0, result);
            assertEquals(2, result.used());
            assertTrue(result.steerable());
        }

        @Test
        void withoutTheRuleEveryGreenCounts() {
            CurveFit result = fit(dots(0.8, 11.0, 0.8, 5.0, -11.0));
            assertColdest(-11.0, result);
            assertOutdoors(List.of(5.0, -11.0), result.chain(), 0.0);
        }
    }

    /** The bend: looked for only with trusted dots in the cold, and held to bend_limit. */
    @Nested
    class TheBend {
        @Test
        void itIsNotLookedForWithoutColdTrustedDots() {
            List<Bin> made = dots(0.9, 11.0, 0.7, 7.0, 3.0, -1.0, -5.0);
            assertEquals(0.8, fit(made).curvature(), 0.0);
        }

        @Test
        void withThemItFindsTheBendTheDotsWereMadeWith() {
            CurveFit result = fit(dots(0.9, 11.0, 0.7, WIDE));
            assertEquals(0.7, result.curvature(), 0.02);
            assertEquals(0.9, result.slope(), 0.03);
        }

        @Test
        void theLimitHoldsHoweverBentTheDotsAre() {
            CurveFit result = fit(dots(0.9, 11.0, 0.55, WIDE), keywords().bendLimit(1.0));
            double moved = Curve.middleDeviation(ROOM, DESIGN, result.slope(), 11.0, result.curvature(), 0.8);
            assertTrue(Math.abs(moved) <= 1.0 + 1e-9, "moved " + moved);
            assertTrue(result.curvature() < 0.8, "bent the right way, as far as allowed: " + result.curvature());
        }

        @Test
        void aLimitOfNoughtKeepsThePhysics() {
            assertEquals(0.8, fit(dots(0.9, 11.0, 0.6, WIDE), keywords().bendLimit(0.0)).curvature(), 0.0);
        }
    }

    /** The CurveFit record and the module's constants. */
    @Nested
    class TheRecord {
        CurveFit withChain(List<Double> chain) {
            return new CurveFit(0.9, 11.0, 0.8, null, 0, List.of(), chain);
        }

        /** len(chain) >= MIN_CHAIN: two trusted dots in a row before the fit may steer. */
        @Test
        void steerableNeedsAChainOfTwo() {
            assertEquals(2, MIN_CHAIN);
            assertFalse(withChain(List.of()).steerable());
            assertFalse(withChain(List.of(5.0)).steerable());
            assertTrue(withChain(List.of(5.0, 3.0)).steerable());
            assertTrue(withChain(List.of(5.0, 3.0, 1.0)).steerable());
        }

        /** Python's chain=(): the six-argument constructor leaves the chain empty and the rest as given. */
        @Test
        void withoutAChainGivenItIsEmpty() {
            CurveFit result = new CurveFit(0.9, 11.0, 0.8, -3.0, 4, List.of(1.0));
            assertEquals(List.of(), result.chain());
            assertFalse(result.steerable());
            assertEquals(0.9, result.slope(), 0.0);
            assertEquals(11.0, result.balance(), 0.0);
            assertEquals(0.8, result.curvature(), 0.0);
            assertColdest(-3.0, result);
            assertEquals(4, result.used());
            assertEquals(List.of(1.0), result.rejected());
        }

        @Test
        void rejectedAndChainComeBackAsGiven() {
            List<Double> rejected = List.of(-3.0, 5.0);
            List<Double> chain = List.of(5.0, 1.0, -3.0);
            CurveFit result = new CurveFit(0.8, 11.0, 0.75, -3.0, 4, rejected, chain);
            assertEquals(rejected, result.rejected());
            assertEquals(chain, result.chain());
            assertTrue(result.steerable());
            CurveFit nothing = new CurveFit(0.8, 11.0, 0.75, null, 0, List.of(), List.of());
            assertEquals(List.of(), nothing.rejected());
            assertEquals(List.of(), nothing.chain());
            assertNull(nothing.coldestGreen());
        }

        @Test
        void theConstantsAsPythonHasThem() {
            assertEquals(2.0, MAX_NUMBER_SHIFT, 0.0);
            assertEquals(3.0, OUTLIER, 0.0);
            assertEquals(-10.0, BEND_COLD, 0.0);
            assertEquals(4.0, BEND_PRIOR, 0.0);
            assertEquals(2, MIN_CHAIN);
            assertEquals(0.25, DEFAULT_PRIOR_WEIGHT, 0.0);
        }

        /**
         * Python's [round(0.5 + 0.01 * step, 2) for step in range(71)]: the
         * eighth value is 0.57 exactly, not the 0.5700000000000001 that
         * 0.5 + 0.01 * 7 gives.
         */
        @Test
        void theBendGridIsSeventyOneHundredthsFromHalfToOneTwenty() {
            assertEquals(71, BEND_GRID.size());
            assertEquals(0.5, BEND_GRID.getFirst(), 0.0);
            assertEquals(1.2, BEND_GRID.getLast(), 0.0);
            assertEquals(0.57, BEND_GRID.get(7), 0.0);
            for (int step = 0; step < BEND_GRID.size(); step++) {
                double hundredth = Double.parseDouble(String.format(Locale.ROOT, "%.2f", 0.5 + 0.01 * step));
                assertEquals(hundredth, BEND_GRID.get(step), 0.0, "step " + step);
            }
        }

        @Test
        void theBendGridCannotBeChanged() {
            assertThrows(UnsupportedOperationException.class, () -> BEND_GRID.add(0.9));
            assertThrows(UnsupportedOperationException.class, () -> BEND_GRID.set(0, 0.9));
            assertThrows(UnsupportedOperationException.class, () -> BEND_GRID.removeFirst());
        }
    }

    /** Behaviour the Python tests do not pin, read off curvefit.py. */
    @Nested
    class FromTheCode {
        /** From the code: usable is the bins with weight > 0; a red bin is not heard, whatever it says. */
        @Test
        void aBinWithoutWeightIsNeitherHeardNorCounted() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.0, 1.0, -3.0);
            CurveFit alone = fit(made);
            CurveFit withRed = fit(plus(made, List.of(bin(-15.0, 60.0, Status.RED, 0.0))));
            assertEquals(3, withRed.used());
            assertEquals(List.of(), withRed.rejected());
            assertEquals(alone.slope(), withRed.slope(), 0.0);
        }

        /**
         * From the code: the chain is taken from the usable bins, so a green
         * with no weight is not in it either - not the coldest, not counted,
         * never an outlier however far off it lies.
         */
        @Test
        void aGreenWithoutWeightIsNotInTheChainEither() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.0, 1.0, -3.0);
            Bin weightless = bin(-12.0, need(0.8, 11.0, 0.8, -12.0) + 7.0, Status.GREEN, 0.0);
            CurveFit result = fit(plus(made, List.of(weightless)));
            assertColdest(-3.0, result);
            assertOutdoors(List.of(5.0, 1.0, -3.0), result.chain(), 0.0);
            assertEquals(3, result.used());
            assertEquals(List.of(), result.rejected());
            assertEquals(fit(made).slope(), result.slope(), 0.0);
        }

        /**
         * From the code: with prior_weight 0 the installer's slope has no say
         * and the slope is the plain least squares of the dots; with trusted
         * dots all the way to design cold the reach does not hold it back
         * either. The default quarter of a dot pulls it a little towards the
         * prior.
         */
        @Test
        void withoutAPriorTheSlopeIsTheDotsAlone() {
            List<Bin> made = dots(0.75, 11.0, 0.8, 5.0, 1.0, -3.0, -7.0, -11.0, -15.0, -18.0);
            assertEquals(0.75, fit(made, keywords().priorWeight(0.0)).slope(), PLACES);
            double pulled = fit(made).slope();
            assertTrue(pulled > 0.75 + 0.001 && pulled < 0.9, "pulled " + pulled);
        }

        /** From the code: nothing to fit leaves the prior, and a result that says so. */
        @Test
        void withNoUsableBinsTheSlopeIsThePrior() {
            List<Bin> reds = List.of(bin(5.0, 30.0, Status.RED, 0.0), bin(-5.0, 1.0, Status.RED, 0.0));
            for (List<Bin> bins : List.of(List.<Bin>of(), reds)) {
                CurveFit result = fit(bins);
                String what = bins.size() + " bins";
                assertEquals(0.9, result.slope(), 0.0, what);
                assertEquals(0.8, result.curvature(), 0.0, what);
                assertEquals(0, result.used(), what);
                assertNull(result.coldestGreen(), what);
                assertEquals(List.of(), result.chain(), what);
                assertEquals(List.of(), result.rejected(), what);
                assertFalse(result.steerable(), what);
            }
        }

        /**
         * From the code: with no prior weight and no bins the normal
         * equations have nothing in them, and the prior is answered outright
         * rather than 0 / 0.
         */
        @Test
        void withNoWeightAtAllThePriorIsAnsweredOutright() {
            CurveFit result = fit(List.of(), keywords().priorWeight(0.0));
            assertEquals(0.9, result.slope(), 0.0);
            assertEquals(0, result.used());
            assertNull(result.coldestGreen());
            assertFalse(result.steerable());
        }

        /**
         * From the code: the reach is only worked out when the balance is
         * above the design outdoor temperature; at or below it the reach is
         * nought and the slope stays at the prior, cold trusted dots or not.
         */
        @Test
        void aBalanceAtOrBelowDesignGivesNoReach() {
            List<Bin> made = dots(0.6, 11.0, 0.8, 5.0, -3.0, -11.0, -18.0);
            for (double balance : new double[] {DESIGN, -20.0}) {
                CurveFit result = fit(made, keywords().balance(balance));
                String what = "balance " + balance;
                assertEquals(0.9, result.slope(), 0.0, what);
                assertEquals(0.8, result.curvature(), 0.0, what);
                assertEquals(balance, result.balance(), 0.0, what);
                assertColdest(-18.0, result);
                assertOutdoors(List.of(5.0, -3.0, -11.0, -18.0), result.chain(), 0.0);
            }
            // The same dots with the balance they were made at do move it.
            assertEquals(0.6, fit(made).slope(), 0.02);
        }

        /**
         * From the code: step = max(1, round(max_gap / BIN_WIDTH)) with
         * Python's banker's rounding. Trusted bins two, three, four and five
         * bins apart: a gap of 5 bridges two bins (2.5 rounds to 2), 6 three,
         * 7 four (3.5 rounds to 4), 9 still four (4.5 rounds to 4) and 10
         * all five.
         */
        @Test
        void theGapRoundsToBinsTheWayPythonRounds() {
            List<Bin> greens = greensOn(0.8, 11.0, 0.8, 11.0, 7.0, 1.0, -7.0, -17.0);
            assertOutdoors(List.of(11.0, 7.0), outdoors(greenChain(greens, 5.0)), 0.0);
            assertOutdoors(List.of(11.0, 7.0, 1.0), outdoors(greenChain(greens, 6.0)), 0.0);
            assertOutdoors(List.of(11.0, 7.0, 1.0, -7.0), outdoors(greenChain(greens, 7.0)), 0.0);
            assertOutdoors(List.of(11.0, 7.0, 1.0, -7.0), outdoors(greenChain(greens, 9.0)), 0.0);
            assertOutdoors(List.of(11.0, 7.0, 1.0, -7.0, -17.0), outdoors(greenChain(greens, 10.0)), 0.0);
        }

        /** From the code: the step is at least one bin, so a gap under a bin's width still joins neighbours. */
        @Test
        void theStepIsAtLeastOneBin() {
            List<Bin> greens = greensOn(0.8, 11.0, 0.8, 9.0, 7.0, 1.0);
            assertOutdoors(List.of(9.0, 7.0), outdoors(greenChain(greens, 0.5)), 0.0);
            assertOutdoors(List.of(9.0, 7.0), outdoors(greenChain(greens, 1.0)), 0.0);
        }

        /**
         * From the code: the runs are made on the greens sorted warm end
         * first, so of two equally long runs the colder wins whatever order
         * the bins came in - and with every run a single bin, the coldest.
         */
        @Test
        void atATieTheColderRunWinsWhateverTheInputOrder() {
            double[][] orders = {{9.0, 7.0, -1.0, -3.0}, {-3.0, -1.0, 7.0, 9.0}, {-1.0, 9.0, -3.0, 7.0}};
            for (double[] order : orders) {
                List<Bin> greens = greensOn(0.8, 11.0, 0.8, order);
                assertOutdoors(List.of(-1.0, -3.0), outdoors(greenChain(greens, 2.0)), 0.0);
            }
            List<Bin> apart = greensOn(0.8, 11.0, 0.8, 11.0, 7.0, 1.0, -7.0, -17.0);
            assertOutdoors(List.of(-17.0), outdoors(greenChain(apart, 2.0)), 0.0);
        }

        /** From the code: the sort is stable, so two greens in one bin keep their input order in the chain. */
        @Test
        void twoGreensInOneBinKeepTheirInputOrder() {
            Bin high = greenOn(0.8, 11.0, 0.8, 3.8);
            Bin low = greenOn(0.8, 11.0, 0.8, 3.2);
            Bin next = greenOn(0.8, 11.0, 0.8, 1.0);
            assertOutdoors(List.of(3.8, 3.2, 1.0), outdoors(greenChain(List.of(high, low, next), 2.0)), 0.0);
            assertOutdoors(List.of(3.2, 3.8, 1.0), outdoors(greenChain(List.of(low, high, next), 2.0)), 0.0);
        }

        /** From the code: every dot beyond OUTLIER of the first fit goes, and used counts what is left. */
        @Test
        void twoOutliersAreBothRejected() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.0, 1.0, -3.0, -7.0, -11.0, -15.0);
            made = withMoreNeed(withMoreNeed(made, -3.0, 8.0), -11.0, -8.0);
            CurveFit result = fit(made);
            assertOutdoors(List.of(-11.0, -3.0), result.rejected(), 0.0);
            assertEquals(4, result.used());
            assertEquals(0.8, result.slope(), 0.01);
        }

        /**
         * From the code: outliers are judged against the first fit, and once.
         * A stove-lit bin at -9 drags the first fit up; a lightly weighted
         * dot at -15 that lies exactly on the true curve is then more than
         * OUTLIER below that fit and goes with it, though against the second
         * fit, made without the stove, it is well within.
         */
        @Test
        void anOutlierIsJudgedAgainstTheFirstFitNotTheSecond() {
            List<Bin> bins = greensOn(0.8, 11.0, 0.8, 9.0, 7.0, 5.0);
            bins.add(bin(-9.0, need(0.8, 11.0, 0.8, -9.0) + 10.0, Status.YELLOW, 0.5));
            Bin honest = bin(-15.0, need(0.8, 11.0, 0.8, -15.0), Status.YELLOW, 0.1);
            bins.add(honest);
            CurveFit result = fit(bins, keywords().bendLimit(0.0));
            assertOutdoors(List.of(-9.0, -15.0), result.rejected(), 0.0);
            assertEquals(3, result.used());
            double perSlope = Curve.supply(-15.0, ROOM, DESIGN, 1.0, 0.8, 11.0) - ROOM;
            double missAgainstTheSecond = honest.need() - result.slope() * perSlope;
            assertTrue(Math.abs(missAgainstTheSecond) < OUTLIER - 1.0, "miss " + missAgainstTheSecond);
        }

        /**
         * From the code: coldest <= BEND_COLD. At -9.9 the physics' bend
         * stays and the slope is the plain fit's; at -10.0 the same dots,
         * made with a bend of 0.65, bend the curve.
         */
        @Test
        void theBendStartsAtExactlyMinusTen() {
            double[] warm = {5.0, 1.0, -3.0, -7.0};
            List<Bin> nearly = greensOn(0.9, 11.0, 0.65, warm);
            nearly.add(greenOn(0.9, 11.0, 0.65, -9.9));
            List<Bin> there = greensOn(0.9, 11.0, 0.65, warm);
            there.add(greenOn(0.9, 11.0, 0.65, BEND_COLD));

            CurveFit held = fit(nearly);
            assertEquals(0.8, held.curvature(), 0.0);
            assertEquals(fit(nearly, keywords().bendLimit(0.0)).slope(), held.slope(), 0.0);
            assertColdest(-9.9, held);

            CurveFit bent = fit(there);
            assertTrue(bent.curvature() < 0.8, "curvature " + bent.curvature());
            assertColdest(-10.0, bent);
        }

        /** From the code: bend_limit > 0 is the switch; nought, or less, keeps the physics however cold the dots. */
        @Test
        void aLimitOfNoughtOrLessKeepsTheCurvature() {
            List<Bin> cold = greensOn(0.9, 11.0, 0.7, WIDE);
            CurveFit free = fit(cold);
            assertTrue(free.curvature() < 0.8, "the dots would bend it: " + free.curvature());
            for (double limit : new double[] {0.0, -1.0}) {
                CurveFit held = fit(cold, keywords().bendLimit(limit));
                String what = "limit " + limit;
                assertEquals(0.8, held.curvature(), 0.0, what);
                assertColdest(-17.0, held);
                assertEquals(7, held.used(), what);
                assertEquals(List.of(), held.rejected(), what);
            }
        }

        /** From the code: the chain comes back sorted warm end first, whatever order the bins came in. */
        @Test
        void theChainIsSortedWarmEndFirst() {
            CurveFit result = fit(greensOn(0.8, 11.0, 0.8, -3.0, 5.0, 1.0, -7.0));
            assertOutdoors(List.of(5.0, 1.0, -3.0, -7.0), result.chain(), 0.0);
            assertColdest(-7.0, result);
        }

        /** From the code: rejected is in the order the bins were used, not sorted. */
        @Test
        void rejectedKeepsTheOrderOfTheBins() {
            double[] warmFirst = {7.0, 5.0, 3.0, 1.0, -1.0, -3.0, -5.0};
            List<Bin> bins = withMoreNeed(withMoreNeed(greensOn(0.8, 11.0, 0.8, warmFirst), 5.0, 9.0), -3.0, -9.0);
            assertOutdoors(List.of(5.0, -3.0), fit(bins).rejected(), 0.0);
            assertOutdoors(List.of(-3.0, 5.0), fit(bins.reversed()).rejected(), 0.0);
            assertEquals(5, fit(bins).used());
        }

        /**
         * From the code: with max_green_gap only the chain's greens count,
         * but a yellow beyond the gap is still heard; a green beyond it is
         * not even looked at for outliers.
         */
        @Test
        void yellowsBeyondTheGapAreStillHeardGreensAreNot() {
            List<Bin> bins = greensOn(0.8, 11.0, 0.8, 5.0, 3.0);
            bins.add(bin(-11.0, need(0.8, 11.0, 0.8, -11.0), Status.YELLOW, 0.3));
            bins.add(bin(-15.0, need(0.8, 11.0, 0.8, -15.0) + 20.0, Status.GREEN, 1.0));
            CurveFit result = fit(bins, keywords().maxGreenGap(2.0));
            assertOutdoors(List.of(5.0, 3.0), result.chain(), 0.0);
            assertColdest(3.0, result);
            assertEquals(3, result.used());
            assertEquals(List.of(), result.rejected());
        }

        /** From the code: the short fitCurve is the long one with prior_weight 0.25 and no gap. */
        @Test
        void theShortCallIsTheLongOneWithTheDefaults() {
            List<Bin> made = dots(0.8, 11.0, 0.8, 5.0, 1.0, -3.0);
            CurveFit shortCall = fitCurve(made, ROOM, DESIGN, 11.0, 0.9, 0.8, 2.0);
            CurveFit longCall = fitCurve(made, ROOM, DESIGN, 11.0, 0.9, 0.8, 2.0, 0.25, null);
            assertEquals(longCall, shortCall);
            // And a quarter is really the default: with no prior at all the dots' own 0.8 comes back.
            assertEquals(0.8, fitCurve(made, ROOM, DESIGN, 11.0, 0.9, 0.8, 2.0, 0.0, null).slope(), PLACES);
            assertTrue(shortCall.slope() > 0.8 + 0.005, "pulled towards the prior: " + shortCall.slope());
        }

        /** From the code: the slope's floor is EVR curve 0, however far a low prior minus the shift would go. */
        @Test
        void theFloorNeverGoesBelowCurveNought() {
            List<Bin> flat = greensOn(0.33, 11.0, 0.8, 5.0, 1.0, -3.0, -7.0, -11.0, -15.0, -18.0);
            CurveFit result = fit(flat, keywords().prior(0.5).bendLimit(0.0));
            assertEquals(Curve.evrSlope(0.0, ROOM, DESIGN), result.slope(), 0.0);
            assertEquals(7, result.used());
            assertEquals(List.of(), result.rejected());
        }

        /** From the code: reach is at most 1, so a trusted dot colder than design cold buys no more than the shift. */
        @Test
        void aDotColderThanDesignDoesNotStretchTheReachPastOne() {
            List<Bin> deep = greensOn(0.65, 11.0, 0.8, 5.0, 1.0, -3.0, -7.0, -11.0, -15.0, -20.0);
            CurveFit result = fit(deep, keywords().prior(1.3).bendLimit(0.0));
            double floor = Curve.evrSlope(Curve.evrNumber(1.3, ROOM, DESIGN) - MAX_NUMBER_SHIFT, ROOM, DESIGN);
            assertEquals(floor, result.slope(), 0.0);
            assertEquals(7, result.used());
        }

        /** From the code: the grid starts at 0.50, and dots bent that far find it when the limit lets them. */
        @Test
        void theGridReachesDownToAHalf() {
            CurveFit result = fit(greensOn(0.9, 11.0, 0.45, WIDE), keywords().bendLimit(10.0));
            assertEquals(0.5, result.curvature(), 0.0);
        }
    }

    /**
     * The answer file: each row's bins through the nine-argument fitCurve with
     * the row's inputs, every field of the result checked, then green_chain on
     * the same bins - all of them, usable or not - with the row's own gap.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        /** Within a billionth of the number, or of one if the number is smaller. */
        static final double CLOSE = 1e-9;

        /**
         * bins;room;designOutdoor;balance;slopePrior;curvature;bendLimit;priorWeight;maxGreenGap;
         * slope;fitCurvature;coldestGreen;used;rejected;chain;steerable;chainGap;greenChain: the
         * bins and the fit's inputs as given to fit_curve (maxGreenGap empty for None, so null),
         * what it answered (coldestGreen empty for None), and an independent
         * green_chain(bins, chainGap) on all the bins. An empty cell arrives as null.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/curve_fit.csv", delimiter = ';', numLinesToSkip = 1,
                maxCharsPerColumn = 1 << 20, nullValues = "")
        void sameFit(String bins, String room, String designOutdoor, String balance, String slopePrior,
                     String curvature, String bendLimit, String priorWeight, String maxGreenGap,
                     String slope, String fitCurvature, String coldestGreen, String used, String rejected,
                     String chain, String steerable, String chainGap, String greenChainOfAll) {
            List<Bin> given = bins(bins);
            CurveFit result = fitCurve(given, number(room), number(designOutdoor), number(balance),
                    number(slopePrior), number(curvature), number(bendLimit), number(priorWeight),
                    number(maxGreenGap));
            assertClose(number(slope), result.slope(), CLOSE, "slope");
            assertClose(number(balance), result.balance(), 0.0, "balance");
            assertClose(number(fitCurvature), result.curvature(), CLOSE, "curvature");
            assertClose(number(coldestGreen), result.coldestGreen(), CLOSE, "coldestGreen");
            assertEquals(Integer.parseInt(used), result.used(), "used");
            assertCloseList(numbers(rejected), result.rejected(), CLOSE, "rejected");
            assertCloseList(numbers(chain), result.chain(), CLOSE, "chain");
            assertEquals(bool(steerable), result.steerable(), "steerable");
            assertCloseList(numbers(greenChainOfAll), outdoors(greenChain(given, number(chainGap))), CLOSE,
                    "greenChain");
        }
    }
}
