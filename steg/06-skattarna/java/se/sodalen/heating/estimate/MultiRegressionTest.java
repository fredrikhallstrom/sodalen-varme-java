package se.sodalen.heating.estimate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.estimate.Facit.array;
import static se.sodalen.heating.estimate.Facit.assertClose;
import static se.sodalen.heating.estimate.Facit.matrix;
import static se.sodalen.heating.estimate.Facit.number;
import static se.sodalen.heating.estimate.Facit.numbers;
import static se.sodalen.heating.estimate.Facit.ops;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * Least squares in several variables, fading like Regression, with an
 * honest opinion of how far it is guessing. The house model learns the
 * house with it: how fast it cools, how much the wind and the sun add.
 * The whole matrix is kept, not just the fit, because variance(x) is what
 * makes the model less sure the further it is asked to reach.
 *
 * <p>Python: solve_symmetric and MultiRegression in estimators.py, and
 * TestMultiRegression in tests/test_controller.py, TestARegressorInAnotherUnit,
 * TestTheSpreadInSample and TestPhysicsTheFitIsTold in tests/test_thermal.py.
 * as_dict and from_dict come with the learned state in step 11.
 */
class MultiRegressionTest {

    static final double CLOSE = 1e-9;

    /** Every row added with dt 1 to a fit of the rows' size that never forgets. */
    static MultiRegression fit(double[][] xs, double[] ys) {
        MultiRegression fit = new MultiRegression(xs[0].length, 0.0);
        for (int i = 0; i < xs.length; i++) {
            fit.add(xs[i], ys[i], 1.0);
        }
        return fit;
    }

    /** y = 2a at a = -5..5, with an intercept column. */
    static MultiRegression aLine() {
        MultiRegression fit = new MultiRegression(2, 0.0);
        for (int a = -5; a <= 5; a++) {
            fit.add(new double[] {1.0, a}, 2.0 * a, 1.0);
        }
        return fit;
    }

    @Nested
    class Solving {
        @Test
        void itSolvesASmallSystem() {
            double[] x = MultiRegression.solveSymmetric(new double[][] {{4.0, 1.0}, {1.0, 3.0}}, new double[] {1.0, 2.0});
            assertArrayEquals(new double[] {1.0 / 11.0, 7.0 / 11.0}, x, CLOSE);
        }

        @Test
        void oneByOneIsADivision() {
            assertArrayEquals(new double[] {1.5}, MultiRegression.solveSymmetric(new double[][] {{2.0}}, new double[] {3.0}));
        }

        /** A nought on the diagonal: the largest number in the column is the pivot, so the rows swap. */
        @Test
        void itSwapsRowsForTheLargestPivot() {
            double[] x = MultiRegression.solveSymmetric(new double[][] {{0.0, 1.0}, {1.0, 0.0}}, new double[] {2.0, 3.0});
            assertArrayEquals(new double[] {3.0, 2.0}, x, CLOSE);
        }

        /** Two rows that say the same thing do not tell two unknowns apart. */
        @Test
        void aSingularSystemHasNoAnswer() {
            assertNull(MultiRegression.solveSymmetric(new double[][] {{1.0, 2.0}, {2.0, 4.0}}, new double[] {1.0, 2.0}));
        }

        /** A pivot under 10^-12 counts as nought; one above it does not. */
        @Test
        void aPivotUnderATrillionthIsNought() {
            assertNull(MultiRegression.solveSymmetric(new double[][] {{1e-13}}, new double[] {1.0}));
            assertArrayEquals(new double[] {1e11}, MultiRegression.solveSymmetric(new double[][] {{1e-11}}, new double[] {1.0}), 1e-3);
        }

        /** It works on copies: the caller's matrix and right-hand side are as they were. */
        @Test
        void itLeavesWhatItWasGivenAlone() {
            double[][] matrix = {{4.0, 1.0}, {1.0, 3.0}};
            double[] rhs = {1.0, 2.0};
            MultiRegression.solveSymmetric(matrix, rhs);
            assertArrayEquals(new double[] {4.0, 1.0}, matrix[0]);
            assertArrayEquals(new double[] {1.0, 3.0}, matrix[1]);
            assertArrayEquals(new double[] {1.0, 2.0}, rhs);
        }
    }

    @Nested
    class Fitting {
        @Test
        void aNewFitIsEmpty() {
            MultiRegression fit = new MultiRegression(3, 60.0);
            assertEquals(3, fit.size());
            assertEquals(60.0, fit.horizon());
            assertEquals(MultiRegression.RIDGE, fit.ridge());
            assertEquals(1e-6, MultiRegression.RIDGE);
            assertEquals(0.0, fit.weight());
            assertArrayEquals(new double[3], fit.xy());
            assertEquals(3, fit.xx().length);
            assertArrayEquals(new double[3], fit.xx()[2]);
            assertEquals(0.0, fit.yy());
            assertEquals(0.0, fit.residual());
            assertEquals(0.0, fit.residualData());
            assertEquals(1e-3, new MultiRegression(2, 0.0, 1e-3).ridge());
        }

        @Test
        void itRecoversAKnownPlane() {
            double[] truth = {2.0, -3.0, 0.5};
            MultiRegression fit = new MultiRegression(3, 0.0);
            for (int a = -3; a <= 3; a++) {
                for (int b = -3; b <= 3; b++) {
                    double[] x = {1.0, a, b};
                    fit.add(x, 2.0 - 3.0 * a + 0.5 * b, 1.0);
                }
            }
            assertArrayEquals(truth, fit.coefficients(), 1e-4);
            assertEquals(2.0 - 3.0 * 2.0 + 0.5 * 4.0, fit.predict(new double[] {1.0, 2.0, 4.0}), 1e-4);
        }

        /** It keeps the sums: x x' and x y and y squared, each faded like the weight. */
        @Test
        void itKeepsTheSums() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.add(new double[] {1.0, 2.0}, 3.0, 1.0);
            fit.add(new double[] {1.0, 4.0}, 5.0, 1.0);
            assertEquals(2.0, fit.weight());
            assertArrayEquals(new double[] {8.0, 26.0}, fit.xy());
            assertArrayEquals(new double[] {2.0, 6.0}, fit.xx()[0]);
            assertArrayEquals(new double[] {6.0, 20.0}, fit.xx()[1]);
            assertEquals(34.0, fit.yy());
        }

        /** It needs one sample more than it has coefficients. */
        @Test
        void tooLittleDataSaysNothing() {
            MultiRegression fit = new MultiRegression(3, 0.0);
            fit.add(new double[] {1.0, 1.0, 1.0}, 1.0, 1.0);
            assertNull(fit.coefficients());
            assertNull(fit.predict(new double[] {1.0, 1.0, 1.0}));
            assertNull(fit.variance(new double[] {1.0, 1.0, 1.0}));
            assertNull(fit.reach(new double[] {1.0, 1.0, 1.0}));
        }

        @Test
        void oneMoreSampleThanCoefficientsIsEnough() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.add(new double[] {1.0, 0.0}, 1.0, 1.0);
            fit.add(new double[] {1.0, 1.0}, 3.0, 1.0);
            assertNull(fit.coefficients());
            fit.add(new double[] {1.0, 2.0}, 5.0, 1.0);
            assertArrayEquals(new double[] {1.0, 2.0}, fit.coefficients(), 1e-4);
        }

        /**
         * Two columns that move together carry one answer between them; the
         * ridge keeps the solve from exploding on it - finite, and still
         * predicting what the data says, however it splits the answer.
         */
        @Test
        void twoRegressorsThatMoveTogetherDoNotBreakTheSolve() {
            MultiRegression fit = new MultiRegression(3, 0.0);
            for (int a = -5; a <= 5; a++) {
                fit.add(new double[] {1.0, a, a}, 3.0 * a, 1.0);
            }
            double[] beta = fit.coefficients();
            assertNotNull(beta);
            for (double b : beta) {
                assertTrue(Double.isFinite(b) && Math.abs(b) < 100.0, Arrays.toString(beta));
            }
            assertEquals(6.0, fit.predict(new double[] {1.0, 2.0, 2.0}), 1e-2);
        }

        @Test
        void itForgetsOldSamples() {
            MultiRegression fit = new MultiRegression(2, 10.0);
            for (int i = 0; i < 50; i++) {
                fit.add(new double[] {1.0, 1.0}, 100.0, 1.0);
            }
            for (int i = 0; i < 50; i++) {
                fit.add(new double[] {1.0, 1.0}, 0.0, 1.0);
            }
            assertTrue(Math.abs(fit.predict(new double[] {1.0, 1.0})) < 1.0);
        }

        /** A crash here would stop the control cycle, and with it the heartbeat the Shelly watches. */
        @Test
        void aSampleOfTheWrongShapeIsDroppedNotThrown() {
            MultiRegression fit = new MultiRegression(3, 0.0);
            fit.add(new double[] {1.0, 2.0}, 5.0, 1.0);
            fit.add(new double[] {1.0, 2.0, 3.0, 4.0}, 5.0, 1.0);
            assertEquals(0.0, fit.weight());
            assertNull(fit.predict(new double[] {1.0, 2.0}));
            assertNull(fit.variance(new double[] {1.0, 2.0}));
        }

        @Test
        void aQuestionOfTheWrongShapeHasNoAnswer() {
            MultiRegression fit = aLine();
            assertNull(fit.predict(new double[] {1.0}));
            assertNull(fit.reach(new double[] {1.0, 2.0, 3.0}));
            assertNull(fit.variance(new double[] {1.0}));
            assertNull(fit.uncertainty(new double[] {1.0}));
        }

        /** The answer is kept until a sample changes it, and then it is new. */
        @Test
        void aNewSampleGivesANewAnswer() {
            MultiRegression fit = aLine();
            double before = fit.coefficients()[1];
            fit.add(new double[] {1.0, 10.0}, 0.0, 1.0);
            assertTrue(fit.coefficients()[1] < before, "slope " + fit.coefficients()[1]);
        }
    }

    @Nested
    class WhatItIsTold {
        /** Where x never varies the slope is not in the data and the prior sets it; where it does, the data wins. */
        @Test
        void aPriorDecidesOnlyWhatTheDataCannot() {
            MultiRegression flat = new MultiRegression(2, 0.0);
            flat.configure(Map.of(1, new Prior(0.3, 24.0 * 4.0)), Map.of());
            for (int i = 0; i < 40; i++) {
                flat.add(new double[] {1.0, 2.0}, 1.0, 1.0);
            }
            assertEquals(0.3, flat.coefficients()[1], 5e-3);

            MultiRegression varied = new MultiRegression(2, 0.0);
            varied.configure(Map.of(1, new Prior(0.3, 24.0 * 4.0)), Map.of());
            for (int i = 0; i < 2000; i++) {
                varied.add(new double[] {1.0, i % 40}, 1.0 + 0.05 * (i % 40), 1.0);
            }
            assertEquals(0.05, varied.coefficients()[1], 5e-3);
        }

        /** A slope of -0.5 under a floor of nought: the slope is nought and the intercept the mean. */
        @Test
        void aFloorHoldsWhateverTheDataSays() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.configure(Map.of(), Map.of(1, 0.0));
            double sum = 0.0;
            for (int x = 0; x < 20; x++) {
                fit.add(new double[] {1.0, x}, 5.0 - 0.5 * x, 1.0);
                sum += 5.0 - 0.5 * x;
            }
            double[] beta = fit.coefficients();
            assertEquals(0.0, beta[1]);
            assertEquals(sum / 20.0, beta[0], 1e-6);
        }

        @Test
        void aFloorThatDoesNotBindChangesNothing() {
            MultiRegression plain = aLine();
            MultiRegression floored = new MultiRegression(2, 0.0);
            floored.configure(Map.of(), Map.of(1, -10.0));
            for (int a = -5; a <= 5; a++) {
                floored.add(new double[] {1.0, a}, 2.0 * a, 1.0);
            }
            assertArrayEquals(plain.coefficients(), floored.coefficients(), CLOSE);
        }

        /**
         * The best fit that keeps every floor, by optimality: at the answer no
         * allowed step lowers the cost. A free coefficient has no gradient, a
         * pinned one a gradient that only pushes it further below its floor.
         * The first Python version pinned whatever fell below and never let
         * go, and could pin one too many.
         */
        @Test
        void theFloorsGiveTheBestFitThatKeepsThem() {
            Random rng = new Random(7);
            for (int round = 0; round < 300; round++) {
                MultiRegression fit = new MultiRegression(3, 0.0);
                fit.configure(Map.of(), Map.of(1, 0.0, 2, 0.0));
                for (int i = 0; i < 40; i++) {
                    double a = -3.0 + 6.0 * rng.nextDouble();
                    double[] x = {1.0, a, -a + 0.3 * rng.nextGaussian()};
                    fit.add(x, rng.nextGaussian() - 0.8 * x[1] - 0.5 * x[2], 1.0);
                }
                double[] beta = fit.coefficients();
                double ridge = fit.ridge() * Math.max(1.0, fit.weight());
                double[] gradient = new double[3];
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        gradient[r] += (fit.xx()[r][c] + (r == c ? ridge : 0.0)) * beta[c];
                    }
                    gradient[r] -= fit.xy()[r];
                }
                String at = "round " + round;
                for (int index : new int[] {1, 2}) {
                    assertTrue(beta[index] >= -1e-12, at);
                    if (beta[index] > 1e-9) {
                        assertEquals(0.0, gradient[index], 1e-6, at);
                    } else {
                        assertTrue(gradient[index] >= -1e-6, at);
                    }
                }
                assertEquals(0.0, gradient[0], 1e-6, at);
            }
        }

        /** It keeps its own copies: changing the maps afterwards changes nothing. */
        @Test
        void itKeepsItsOwnCopyOfWhatItIsTold() {
            Map<Integer, Double> floors = new HashMap<>();
            Map<Integer, Prior> priors = new HashMap<>();
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.configure(priors, floors);
            floors.put(1, 0.0);
            priors.put(1, new Prior(5.0, 1e6));
            for (int a = -5; a <= 5; a++) {
                fit.add(new double[] {1.0, a}, -2.0 * a, 1.0);
            }
            assertEquals(-2.0, fit.coefficients()[1], 1e-4);
        }

        /** Told something new, the answer is new. */
        @Test
        void beingToldSomethingNewGivesANewAnswer() {
            MultiRegression fit = aLine();
            assertEquals(2.0, fit.coefficients()[1], 1e-4);
            fit.configure(Map.of(), Map.of(1, 3.0));
            assertEquals(3.0, fit.coefficients()[1]);
        }

        /** A prior makes the estimate sensible, never the fit surer. */
        @Test
        void confidenceIsTheDatasAlone() {
            MultiRegression plain = new MultiRegression(2, 0.0);
            MultiRegression told = new MultiRegression(2, 0.0);
            told.configure(Map.of(1, new Prior(0.3, 1000.0)), Map.of());
            for (int x = 0; x < 30; x++) {
                plain.add(new double[] {1.0, x % 3}, 1.0 + 0.1 * (x % 3), 1.0);
                told.add(new double[] {1.0, x % 3}, 1.0 + 0.1 * (x % 3), 1.0);
            }
            assertEquals(plain.reach(new double[] {1.0, 50.0}), told.reach(new double[] {1.0, 50.0}));
        }

        /** sigma decides how safe a setback is: what it was told must not make it surer than what it measured. */
        @Test
        void aPriorNeverMakesTheSpreadSmaller() {
            Random rng = new Random(3);
            MultiRegression plain = new MultiRegression(2, 0.0);
            MultiRegression told = new MultiRegression(2, 0.0);
            told.configure(Map.of(1, new Prior(0.3, 500.0)), Map.of(1, 0.0));
            for (int i = 0; i < 60; i++) {
                double[] x = {1.0, 2.0 * rng.nextDouble()};
                double y = 1.0 - 0.4 * x[1] + 0.5 * rng.nextGaussian();
                plain.add(x, y, 1.0);
                told.add(x, y, 1.0);
            }
            assertTrue(told.sigma() >= plain.sigma() - 1e-12, told.sigma() + " < " + plain.sigma());
            assertTrue(told.residualData() > 0.0);
        }
    }

    @Nested
    class HowSureItIs {
        @Test
        void itIsMoreCertainWhereItHasData() {
            MultiRegression fit = aLine();
            assertTrue(fit.uncertainty(new double[] {1.0, 0.0}) < fit.uncertainty(new double[] {1.0, 100.0}));
        }

        /** Twice as far out is not a little further out: the margin grows with the reach. */
        @Test
        void extrapolationIsPunishedInProportion() {
            MultiRegression fit = aLine();
            double close = fit.uncertainty(new double[] {1.0, 20.0});
            double further = fit.uncertainty(new double[] {1.0, 60.0});
            assertTrue(further > close * 2.0, further + " <= 2 * " + close);
        }

        @Test
        void aNoisyFitIsUncertainEverywhere() {
            double[] noise = {3.0, -4.0, 1.0, 5.0, -2.0, -5.0, 4.0, 0.5, -3.0, 2.0, -1.0};
            MultiRegression clean = aLine();
            MultiRegression noisy = new MultiRegression(2, 0.0);
            for (int a = -5; a <= 5; a++) {
                noisy.add(new double[] {1.0, a}, 2.0 * a + noise[a + 5], 1.0);
            }
            assertTrue(noisy.uncertainty(new double[] {1.0, 0.0}) > clean.uncertainty(new double[] {1.0, 0.0}));
        }

        @Test
        void theUncertaintyIsTheRootOfTheVariance() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            double[] noise = {0.3, -0.2, 0.1, -0.4, 0.2};
            for (int a = 0; a < 5; a++) {
                fit.add(new double[] {1.0, a}, a + noise[a], 1.0);
            }
            double[] x = {1.0, 7.0};
            assertEquals(Math.sqrt(fit.variance(x)), fit.uncertainty(x), CLOSE);
        }

        /** With no more samples than coefficients there is nothing to judge by. */
        @Test
        void withoutDegreesOfFreedomItIsNotSureOfAnything() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.add(new double[] {1.0, 1.0}, 1.0, 1.0);
            fit.add(new double[] {1.0, 2.0}, 2.0, 1.0);
            assertEquals(Double.POSITIVE_INFINITY, fit.sigma());
            assertEquals(Double.POSITIVE_INFINITY, fit.inSampleSigma());
        }

        /** The reach grows the further from the data it is asked, from nought where the data is. */
        @Test
        void theReachGrowsWithTheDistance() {
            MultiRegression fit = aLine();
            double home = fit.reach(new double[] {1.0, 0.0});
            double near = fit.reach(new double[] {1.0, 10.0});
            double far = fit.reach(new double[] {1.0, 100.0});
            assertTrue(home >= 0.0 && home < 0.1, "home " + home);
            assertTrue(home < near && near < far, home + ", " + near + ", " + far);
        }
    }

    @Nested
    class TheSpreadInSample {
        /** 4000 samples with noise of 0.5: it finds the 0.5. */
        @Test
        void itFindsTheNoise() {
            Random rng = new Random(5);
            MultiRegression fit = new MultiRegression(2, 0.0);
            for (int i = 0; i < 4000; i++) {
                double x = 10.0 * rng.nextDouble();
                fit.add(new double[] {1.0, x}, 1.0 + 2.0 * x + 0.5 * rng.nextGaussian(), 1.0);
            }
            assertEquals(0.5, fit.inSampleSigma(), 0.03);
            assertEquals(fit.sigma(), fit.inSampleSigma(), 0.03);
        }

        @Test
        void itReadsTheNoiseAtTheFirstAnswer() {
            Random rng = new Random(7);
            MultiRegression fit = new MultiRegression(2, 0.0);
            for (int i = 0; i < 8; i++) {
                double x = 10.0 * rng.nextDouble();
                fit.add(new double[] {1.0, x}, 1.0 + 2.0 * x + 0.5 * rng.nextGaussian(), 1.0);
            }
            assertTrue(fit.inSampleSigma() > 0.1, "inSampleSigma " + fit.inSampleSigma());
        }

        /**
         * Three samples off a line give two coefficients one residual; the
         * fit made no guess before the third, so sigma has nothing.
         */
        @Test
        void whereTheGuessesWereRightAndTheFitIsNot() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.add(new double[] {1.0, 0.0}, 0.0, 1.0);
            fit.add(new double[] {1.0, 1.0}, 1.5, 1.0);
            fit.add(new double[] {1.0, 2.0}, 2.0, 1.0);
            assertEquals(0.0, fit.sigma());
            assertTrue(fit.inSampleSigma() > 0.1, "inSampleSigma " + fit.inSampleSigma());
        }

        /** 1, 2 and 3: the mean is 2, the squares 2, over two degrees 1. */
        @Test
        void itIsTheTextbookEstimate() {
            MultiRegression fit = new MultiRegression(1, 0.0);
            fit.add(new double[] {1.0}, 1.0, 1.0);
            fit.add(new double[] {1.0}, 2.0, 1.0);
            fit.add(new double[] {1.0}, 3.0, 1.0);
            assertEquals(1.0, fit.inSampleSigma(), 1e-5);
        }

        @Test
        void aPriorNeverMakesItSurer() {
            Random rng = new Random(8);
            MultiRegression plain = new MultiRegression(2, 0.0);
            MultiRegression told = new MultiRegression(2, 0.0);
            told.configure(Map.of(1, new Prior(5.0, 500.0)), Map.of());
            for (int i = 0; i < 30; i++) {
                double x = 10.0 * rng.nextDouble();
                double y = 1.0 + 2.0 * x + 0.5 * rng.nextGaussian();
                plain.add(new double[] {1.0, x}, y, 1.0);
                told.add(new double[] {1.0, x}, y, 1.0);
            }
            assertTrue(told.inSampleSigma() >= plain.inSampleSigma());
        }
    }

    @Nested
    class AnotherUnit {
        /** 40 samples of y = 2 + 0.5 a + 3 s. */
        MultiRegression fitted() {
            Random rng = new Random(3);
            MultiRegression fit = new MultiRegression(3, 0.0);
            for (int i = 0; i < 40; i++) {
                double[] x = {1.0, -2.0 + 4.0 * rng.nextDouble(), rng.nextDouble()};
                fit.add(x, 2.0 + 0.5 * x[1] + 3.0 * x[2], 1.0);
            }
            return fit;
        }

        /** Counted in a unit twice as large, its coefficient is halved - and not the answer asked for before. */
        @Test
        void itsCoefficientIsDividedByTheFactor() {
            MultiRegression fit = fitted();
            fit.coefficients();
            fit.rescale(2, 2.0);
            assertArrayEquals(new double[] {2.0, 0.5, 1.5}, fit.coefficients(), 1e-5);
        }

        @Test
        void theSameSkyPredictsTheSame() {
            MultiRegression before = fitted();
            MultiRegression after = fitted();
            after.rescale(2, 1.27);
            for (double[] x : new double[][] {{1.0, 0.3, 0.2}, {1.0, -1.0, 0.9}}) {
                double[] scaled = {x[0], x[1], 1.27 * x[2]};
                assertEquals(before.predict(x), after.predict(scaled), 1e-5);
                assertEquals(before.variance(x), after.variance(scaled), 1e-5 * before.variance(x));
            }
        }

        /** Only the sums with that regressor in them change: its row and column, once each where they cross. */
        @Test
        void onlyItsRowAndColumnChange() {
            MultiRegression fit = new MultiRegression(2, 0.0);
            fit.add(new double[] {1.0, 2.0}, 3.0, 1.0);
            fit.rescale(1, 10.0);
            assertArrayEquals(new double[] {1.0, 20.0}, fit.xx()[0]);
            assertArrayEquals(new double[] {20.0, 400.0}, fit.xx()[1]);
            assertArrayEquals(new double[] {3.0, 60.0}, fit.xy());
            assertEquals(1.0, fit.weight());
            assertEquals(9.0, fit.yy());
        }

        @Test
        void aFactorOfOneChangesNothing() {
            MultiRegression fit = fitted();
            double[] xy = fit.xy().clone();
            double[] row = fit.xx()[2].clone();
            fit.rescale(2, 1.0);
            assertArrayEquals(xy, fit.xy());
            assertArrayEquals(row, fit.xx()[2]);
        }

        @Test
        void anEmptyFitStaysEmpty() {
            MultiRegression fit = new MultiRegression(3, 0.0);
            fit.rescale(2, 1.27);
            assertNull(fit.coefficients());
            assertArrayEquals(new double[3], fit.xy());
        }

        /** A unit is a positive number; anything else is an error, and the fit is left as it was. */
        @Test
        void noSuchFactor() {
            for (double factor : new double[] {0.0, -1.27, Double.NaN, Double.POSITIVE_INFINITY}) {
                MultiRegression fit = fitted();
                double[] xy = fit.xy().clone();
                assertThrows(IllegalArgumentException.class, () -> fit.rescale(2, factor), "factor " + factor);
                assertArrayEquals(xy, fit.xy());
            }
        }

        @Test
        void noSuchRegressor() {
            MultiRegression fit = fitted();
            assertThrows(IllegalArgumentException.class, () -> fit.rescale(3, 1.27));
            assertThrows(IllegalArgumentException.class, () -> fit.rescale(-1, 1.27));
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        @ParameterizedTest
        @CsvFileSource(resources = "/solve_symmetric.csv", delimiter = ';', numLinesToSkip = 1, maxCharsPerColumn = 16384)
        void sameSolve(int size, String matrix, String rhs, String solution) {
            double[] solved = MultiRegression.solveSymmetric(matrix(matrix), array(rhs));
            List<Double> expected = numbers(solution);
            if (expected.isEmpty()) {
                assertNull(solved, "solution");
                return;
            }
            assertNotNull(solved, "solution");
            assertEquals(size, solved.length, "size");
            for (int i = 0; i < size; i++) {
                assertClose(expected.get(i), solved[i], 1e-7, "solution[" + i + "]");
            }
        }

        /** "configure,p,index,expected,strength,...,f,index,floor,..." as the maps it stands for. */
        static void configure(MultiRegression fit, String[] p) {
            Map<Integer, Prior> priors = new HashMap<>();
            Map<Integer, Double> floors = new HashMap<>();
            int i = 1;
            while (i < p.length) {
                if (p[i].equals("p")) {
                    priors.put(Integer.parseInt(p[i + 1]), new Prior(number(p[i + 2]), number(p[i + 3])));
                    i += 4;
                } else {
                    floors.put(Integer.parseInt(p[i + 1]), number(p[i + 2]));
                    i += 3;
                }
            }
            fit.configure(priors, floors);
        }

        static void assertCloseAll(String expected, double[] actual, double close, String what) {
            List<Double> e = numbers(expected);
            if (e.isEmpty()) {
                assertNull(actual, what);
                return;
            }
            assertNotNull(actual, what);
            assertEquals(e.size(), actual.length, what + ": how many");
            for (int i = 0; i < e.size(); i++) {
                assertClose(e.get(i), actual[i], close, what + "[" + i + "]");
            }
        }

        /**
         * "add,y,dt,x0,x1,...|rescale,index,factor|coef|configure,...|..." on a
         * new fit of the size, horizon and ridge (empty: the default), then its
         * sums and what it says at four places. coef only asks for the
         * coefficients, so that an answer kept too long shows.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/multi_regression.csv", delimiter = ';', numLinesToSkip = 1,
                maxCharsPerColumn = 65536)
        void sameFit(int size, double horizon, String ridge, String steps, double weight, double residual,
                     double residualData, double yy, String xy, String xx, String coefficients, String sigma,
                     String inSampleSigma, String queries, String predictions, String reaches, String variances,
                     String uncertainties) {
            MultiRegression fit = ridge == null || ridge.isEmpty() ? new MultiRegression(size, horizon)
                    : new MultiRegression(size, horizon, number(ridge));
            for (String op : ops(steps)) {
                String[] p = op.split(",", -1);
                switch (p[0]) {
                    case "add" -> fit.add(Arrays.stream(p, 3, p.length).mapToDouble(Double::parseDouble).toArray(),
                            number(p[1]), number(p[2]));
                    case "rescale" -> fit.rescale(Integer.parseInt(p[1]), number(p[2]));
                    case "coef" -> fit.coefficients();
                    default -> configure(fit, p);
                }
            }
            // The sums are added up the same way as in Python; the answers come out of solves, a little looser.
            double tight = 1e-9;
            double solved = 1e-6;
            assertClose(weight, fit.weight(), tight, "weight");
            assertClose(yy, fit.yy(), tight, "yy");
            assertCloseAll(xy, fit.xy(), tight, "xy");
            String[] rows = xx.split("\\|", -1);
            assertEquals(size, fit.xx().length, "xx: how many rows");
            for (int r = 0; r < size; r++) {
                assertCloseAll(rows[r], fit.xx()[r], tight, "xx[" + r + "]");
            }
            assertClose(residual, fit.residual(), solved, "residual");
            assertClose(residualData, fit.residualData(), solved, "residualData");
            assertCloseAll(coefficients, fit.coefficients(), solved, "coefficients");
            assertClose(number(sigma), fit.sigma(), solved, "sigma");
            assertClose(number(inSampleSigma), fit.inSampleSigma(), solved, "inSampleSigma");

            double[][] asked = matrix(queries);
            List<Double> predicted = numbers(predictions);
            List<Double> reached = numbers(reaches);
            List<Double> varied = numbers(variances);
            List<Double> unsure = numbers(uncertainties);
            for (int i = 0; i < asked.length; i++) {
                String at = "[" + i + "]";
                assertClose(predicted.get(i), fit.predict(asked[i]), solved, "predict" + at);
                assertClose(reached.get(i), fit.reach(asked[i]), solved, "reach" + at);
                assertClose(varied.get(i), fit.variance(asked[i]), solved, "variance" + at);
                assertClose(unsure.get(i), fit.uncertainty(asked[i]), solved, "uncertainty" + at);
            }
        }
    }
}
