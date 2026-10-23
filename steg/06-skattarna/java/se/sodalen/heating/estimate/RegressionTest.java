package se.sodalen.heating.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.estimate.Facit.assertClose;
import static se.sodalen.heating.estimate.Facit.number;
import static se.sodalen.heating.estimate.Facit.ops;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * A straight line y = a + b*x through samples that fade with time, kept as
 * five running sums and no list. The curve learns its offset with it, and
 * the valve its gain.
 *
 * <p>Python: the class Regression in estimators.py and TestRegression in
 * tests/test_controller.py. as_dict and from_dict come with the learned
 * state in step 11.
 */
class RegressionTest {

    static final double CLOSE = 1e-9;
    static final double HOUR = 3600.0;

    static Regression line(double horizon, double... xy) {
        Regression fit = new Regression(horizon);
        for (int i = 0; i < xy.length; i += 2) {
            fit.add(xy[i], xy[i + 1], 1.0);
        }
        return fit;
    }

    @Nested
    class Adding {
        @Test
        void aNewFitIsEmpty() {
            Regression fit = new Regression(HOUR);
            assertEquals(HOUR, fit.horizon());
            assertEquals(0.0, fit.weight());
            assertEquals(0.0, fit.sumX());
            assertEquals(0.0, fit.sumY());
            assertEquals(0.0, fit.sumXx());
            assertEquals(0.0, fit.sumXy());
        }

        @Test
        void aSampleIsAddedToEverySum() {
            Regression fit = new Regression(0.0);
            fit.add(2.0, 3.0, 1.0);
            fit.add(4.0, 5.0, 1.0);
            assertEquals(2.0, fit.weight());
            assertEquals(6.0, fit.sumX());
            assertEquals(8.0, fit.sumY());
            assertEquals(20.0, fit.sumXx());
            assertEquals(26.0, fit.sumXy());
        }

        /** What was there fades by the time since, then the new sample counts whole. */
        @Test
        void whatWasSeenFadesBeforeTheNewSample() {
            Regression fit = new Regression(HOUR);
            fit.add(1.0, 2.0, 1.0);
            fit.add(3.0, 4.0, HOUR);
            double e = Math.exp(-1.0);
            assertEquals(e + 1.0, fit.weight(), CLOSE);
            assertEquals(e + 3.0, fit.sumX(), CLOSE);
            assertEquals(2.0 * e + 4.0, fit.sumY(), CLOSE);
            assertEquals(e + 9.0, fit.sumXx(), CLOSE);
            assertEquals(2.0 * e + 12.0, fit.sumXy(), CLOSE);
        }

        @Test
        void itForgetsOldSamples() {
            Regression fit = new Regression(HOUR);
            for (int i = 0; i < 50; i++) {
                fit.add(0.5, 10.0, 60.0);
            }
            for (int i = 0; i < 200; i++) {
                fit.add(0.5, 0.0, 60.0);
            }
            assertTrue(fit.meanY() < 0.5, "meanY " + fit.meanY());
        }

        /** No horizon, no time or time backwards: nothing fades. */
        @Test
        void withoutAHorizonOrTimeNothingFades() {
            Regression noHorizon = new Regression(0.0);
            noHorizon.add(1.0, 1.0, 1.0);
            noHorizon.add(1.0, 1.0, 1e9);
            Regression noTime = new Regression(HOUR);
            noTime.add(1.0, 1.0, 1.0);
            noTime.add(1.0, 1.0, 0.0);
            noTime.add(1.0, 1.0, -5.0);
            assertEquals(2.0, noHorizon.weight());
            assertEquals(3.0, noTime.weight(), CLOSE);
        }

        @Test
        void aGapLongerThanTheMathsForgetsEverything() {
            Regression fit = new Regression(HOUR);
            fit.add(5.0, 5.0, 1.0);
            fit.add(1.0, 2.0, 30.0 * HOUR);
            assertEquals(1.0, fit.weight());
            assertEquals(1.0, fit.sumX());
            assertEquals(2.0, fit.sumY());
        }
    }

    @Nested
    class Fading {
        /** fade lets time pass without a sample, so a fit that learns now and then still means "lately". */
        @Test
        void timeAloneFadesWhatWasSeen() {
            Regression fit = new Regression(HOUR);
            fit.add(1.0, 2.0, 30.0);
            fit.fade(HOUR);
            double weight = fit.weight();
            double meanY = fit.meanY();
            fit.add(1.0, -1.0, 0.0);
            double e = Math.exp(-1.0);
            assertEquals(e, weight, CLOSE);
            assertEquals(2.0, meanY, CLOSE);
            assertEquals((2.0 * e - 1.0) / (e + 1.0), fit.meanY(), CLOSE);
        }

        @Test
        void noTimeFadesNothing() {
            Regression fit = new Regression(HOUR);
            fit.add(1.0, 2.0, 30.0);
            fit.fade(0.0);
            fit.fade(-10.0);
            assertEquals(1.0, fit.weight());
            assertEquals(2.0, fit.sumY());
        }

        @Test
        void withoutAHorizonFadingDoesNothing() {
            Regression fit = new Regression(0.0);
            fit.add(1.0, 2.0, 1.0);
            fit.fade(1e6);
            assertEquals(1.0, fit.weight());
            assertEquals(1.0, fit.sumXx());
        }

        @Test
        void aLongEnoughWaitFadesEverything() {
            Regression fit = new Regression(HOUR);
            fit.add(1.0, 2.0, 1.0);
            fit.fade(30.0 * HOUR);
            assertEquals(0.0, fit.weight());
            assertEquals(0.0, fit.sumXy());
        }
    }

    @Nested
    class TheMeans {
        @Test
        void theMeansAreTheSumsOverTheWeight() {
            Regression fit = line(0.0, 1.0, 10.0, 3.0, 20.0);
            assertEquals(2.0, fit.meanX(), CLOSE);
            assertEquals(15.0, fit.meanY(), CLOSE);
            assertEquals(1.0, fit.varianceX(), CLOSE);
        }

        @Test
        void anEmptyFitHasNoughtEverywhere() {
            Regression fit = new Regression(HOUR);
            assertEquals(0.0, fit.meanX());
            assertEquals(0.0, fit.meanY());
            assertEquals(0.0, fit.varianceX());
        }

        /** 0.1 three times rounds to a variance a hair below nought; it is nought. */
        @Test
        void theVarianceIsNeverBelowNought() {
            Regression fit = line(0.0, 0.1, 1.0, 0.1, 1.0, 0.1, 1.0);
            assertEquals(0.0, fit.varianceX());
        }
    }

    @Nested
    class Fitting {
        @Test
        void itRecoversAKnownLine() {
            Regression fit = new Regression(1e9);
            for (double x : new double[] {0.2, 0.4, 0.6, 0.8, 1.0}) {
                fit.add(x, 3.0 + 2.0 * x, 1.0);
            }
            Fit result = fit.fit(0.01);
            assertTrue(result.identifiable());
            assertEquals(2.0, result.slope(), 1e-6);
            assertEquals(3.0, result.intercept(), 1e-6);
        }

        /** With every sample at the same x the slope is not in the data: the mean is all it says. */
        @Test
        void theSlopeIsNotThereWithoutSpread() {
            Regression fit = new Regression(1e9);
            for (int i = 0; i < 50; i++) {
                fit.add(0.7, 1.5, 1.0);
            }
            Fit result = fit.fit(0.01);
            assertFalse(result.identifiable());
            assertEquals(0.0, result.slope());
            assertEquals(1.5, result.intercept(), 1e-6);
        }

        @Test
        void tooLittleDataSaysNothing() {
            Regression fit = new Regression(1e9);
            fit.add(0.5, 1.0, 1.0);
            assertEquals(new Fit(0.0, 0.0, false), fit.fit(0.01));
        }

        @Test
        void twoSamplesAreEnough() {
            Fit result = line(0.0, 0.0, 1.0, 2.0, 5.0).fit(0.01);
            assertTrue(result.identifiable());
            assertEquals(2.0, result.slope(), CLOSE);
            assertEquals(1.0, result.intercept(), CLOSE);
        }

        /** A variance of exactly the least asked for is enough. */
        @Test
        void exactlyTheLeastVarianceIsEnough() {
            Regression fit = line(0.0, 0.0, 1.0, 2.0, 5.0);
            assertTrue(fit.fit(1.0).identifiable());
            assertFalse(fit.fit(1.0000001).identifiable());
        }

        /** Faded samples count less: two old points at y = 0 and two new at y = 4. */
        @Test
        void theNewSamplesWeighMore() {
            Regression fit = new Regression(HOUR);
            fit.add(0.0, 0.0, 1.0);
            fit.add(2.0, 0.0, 1.0);
            fit.add(0.0, 4.0, HOUR);
            fit.add(2.0, 4.0, 1.0);
            Fit result = fit.fit(0.01);
            assertTrue(result.intercept() > 2.0, "intercept " + result.intercept());
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        /** "add,x,y,dt|fade,dt|..." on a fit with the horizon, then its sums, means and fit. */
        @ParameterizedTest
        @CsvFileSource(resources = "/regression.csv", delimiter = ';', numLinesToSkip = 1, maxCharsPerColumn = 16384)
        void sameRegression(double horizon, String steps, double minVariance, double weight, double sumX,
                            double sumY, double sumXx, double sumXy, double meanX, double meanY, double varianceX,
                            double intercept, double slope, boolean identifiable) {
            Regression fit = new Regression(horizon);
            for (String op : ops(steps)) {
                String[] p = op.split(",", -1);
                if (p[0].equals("add")) {
                    fit.add(number(p[1]), number(p[2]), number(p[3]));
                } else {
                    fit.fade(number(p[1]));
                }
            }
            assertClose(weight, fit.weight(), "weight");
            assertClose(sumX, fit.sumX(), "sumX");
            assertClose(sumY, fit.sumY(), "sumY");
            assertClose(sumXx, fit.sumXx(), "sumXx");
            assertClose(sumXy, fit.sumXy(), "sumXy");
            assertClose(meanX, fit.meanX(), "meanX");
            assertClose(meanY, fit.meanY(), "meanY");
            // The variance is a difference of two near-equal numbers; a last digit of either shows in it.
            assertClose(varianceX, fit.varianceX(), 1e-6, "varianceX");
            Fit result = fit.fit(minVariance);
            assertEquals(identifiable, result.identifiable(), "identifiable");
            assertClose(intercept, result.intercept(), 1e-6, "intercept");
            assertClose(slope, result.slope(), 1e-6, "slope");
        }
    }
}
