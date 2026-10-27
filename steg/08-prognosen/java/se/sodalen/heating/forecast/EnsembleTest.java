package se.sodalen.heating.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static se.sodalen.heating.forecast.Ensemble.DEFAULT_HORIZON_SECONDS;
import static se.sodalen.heating.forecast.Ensemble.DIAGONAL_FLOOR;
import static se.sodalen.heating.forecast.Ensemble.PRIOR_VARIANCE;
import static se.sodalen.heating.forecast.Facit.assertClose;
import static se.sodalen.heating.forecast.Facit.assertCloseCovariance;
import static se.sodalen.heating.forecast.Facit.assertCloseMap;
import static se.sodalen.heating.forecast.Facit.assertSameBearing;
import static se.sodalen.heating.forecast.Facit.bool;
import static se.sodalen.heating.forecast.Facit.covariance;
import static se.sodalen.heating.forecast.Facit.entries;
import static se.sodalen.heating.forecast.Facit.fields;
import static se.sodalen.heating.forecast.Facit.map;
import static se.sodalen.heating.forecast.Facit.names;
import static se.sodalen.heating.forecast.Facit.number;
import static se.sodalen.heating.forecast.Facit.ops;
import static se.sodalen.heating.forecast.Facit.readings;
import static se.sodalen.heating.forecast.Facit.standin;
import static se.sodalen.heating.forecast.Facit.weights;
import static se.sodalen.heating.forecast.Feeding.feed;
import static se.sodalen.heating.forecast.Feeding.sources;
import static se.sodalen.heating.forecast.Feeding.twinsAndAnIndependent;
import static se.sodalen.heating.forecast.Feeding.uniform;
import static se.sodalen.heating.forecast.Feeding.weather;
import static se.sodalen.heating.forecast.Forecasts.STAND_IN_HOURS;
import static se.sodalen.heating.forecast.Forecasts.outdoorFromForecast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.house.Conditions;
import se.sodalen.heating.house.Forecast;

/**
 * Several forecasts into one, and whether the spread it reports is honest.
 * Every source is wrong in its own way: one runs cold because its grid
 * point sits up a hill, two are the same model served by different
 * companies. Each is scored against the sensor on the wall, its bias
 * subtracted and the errors kept as a covariance, so that two copies of
 * one model share a vote and their agreement buys no confidence.
 *
 * <p>Python: Ensemble and outdoor_from_forecast in forecast.py and
 * tests/test_forecast.py. as_dict and from_dict come with the stored state
 * in step 11.
 */
class EnsembleTest {

    /** assertAlmostEqual's places=6. */
    static final double PLACES = 1e-6;
    static final double HALF_HOUR = 1800.0;

    /** The covariance's rows copied, so a later change shows. */
    static Map<String, Map<String, Double>> snapshot(Map<String, Map<String, Double>> covariance) {
        Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        covariance.forEach((name, row) -> copy.put(name, new LinkedHashMap<>(row)));
        return copy;
    }

    /** A source two degrees low is not a bad source, it is a source with an offset. */
    @Nested
    class TheBias {
        /**
         * SMHI reads two degrees low at this house. That is an offset, not
         * a bad source, and once it is known it is as useful as any other.
         */
        @Test
        void aSourceThatRunsColdIsCorrectedNotDiscarded() {
            Ensemble ensemble = feed(new Ensemble(), weather(), sources("low", -2.0, "fair", 0.0));
            assertEquals(-2.0, ensemble.bias().get("low"), 0.25);
            assertEquals(0.0, ensemble.bias().get("fair"), 0.25);

            Combined out = ensemble.combine(List.of(new Reading("low", 3.0), new Reading("fair", 5.0)));
            assertEquals(5.0, out.temperature(), 0.4);
        }

        @Test
        void aSourceThatSaysNothingIsLeftAlone() {
            Ensemble ensemble = feed(new Ensemble(), weather(), sources("a", 1.0, "b", 0.0));
            Map<String, Double> before = new LinkedHashMap<>(ensemble.bias());
            ensemble.observe(5.0, List.of(new Reading("a", null), new Reading("b", null)), HALF_HOUR);
            assertEquals(before, ensemble.bias());
        }
    }

    /**
     * The finding that started this: two of the three weather integrations
     * at Sodalen serve the same underlying model, to a tenth of a degree.
     */
    @Nested
    class CorrelatedSources {
        @Test
        void twoCopiesOfOneModelShareAVote() {
            Ensemble ensemble = twinsAndAnIndependent();
            Map<String, Double> weights = ensemble.weights(List.of("twin_a", "twin_b", "lone")).weights();
            double twins = weights.get("twin_a") + weights.get("twin_b");
            assertEquals(weights.get("lone"), twins, 0.25);
        }

        /**
         * Three sources where two are the same model must not come out more
         * certain than two genuinely independent ones.
         */
        @Test
        void agreementBetweenCopiesDoesNotBuyConfidence() {
            Ensemble ensemble = twinsAndAnIndependent();
            Double withTwins = ensemble.weights(List.of("twin_a", "twin_b", "lone")).spread();
            Double without = ensemble.weights(List.of("twin_a", "lone")).spread();
            assertNotNull(withTwins);
            assertNotNull(without);
            assertEquals(without, withTwins, 0.15);
        }

        @Test
        void independentSourcesDoNarrowIt() {
            Random rng = new Random(9L);
            Ensemble ensemble = new Ensemble();
            for (double truth : weather(600)) {
                ensemble.observe(truth, List.of(
                        new Reading("a", truth + uniform(rng, -2.0, 2.0)),
                        new Reading("b", truth + uniform(rng, -2.0, 2.0)),
                        new Reading("c", truth + uniform(rng, -2.0, 2.0))), HALF_HOUR);
            }
            Double one = ensemble.weights(List.of("a")).spread();
            Double three = ensemble.weights(List.of("a", "b", "c")).spread();
            assertNotNull(one);
            assertNotNull(three);
            assertTrue(three < one, three + " >= " + one);
        }
    }

    /** w = S^-1 1 / (1' S^-1 1), and what is done when that cannot be had. */
    @Nested
    class TheWeighting {
        @Test
        void theMoreAccurateSourceCountsForMore() {
            Ensemble ensemble = feed(new Ensemble(), weather(600), sources("sharp", 0.0, "vague", 0.0),
                    sources("sharp", 0.2, "vague", 3.0), 4L);
            Map<String, Double> weights = ensemble.weights(List.of("sharp", "vague")).weights();
            assertTrue(weights.get("sharp") > weights.get("vague") * 3, weights.toString());
        }

        @Test
        void weightsAlwaysSumToOne() {
            Ensemble ensemble = feed(new Ensemble(), weather(300), sources("a", 0.0, "b", 1.0, "c", -1.0));
            Map<String, Double> weights = ensemble.weights(List.of("a", "b", "c")).weights();
            double sum = 0.0;
            for (double value : weights.values()) {
                assertTrue(value >= 0.0, weights.toString());
                sum += value;
            }
            assertEquals(1.0, sum, PLACES);
            // In the order asked, as Python's dict keeps it.
            assertEquals(List.of("a", "b", "c"), List.copyOf(weights.keySet()));
        }

        @Test
        void beforeAnyScoringTheyShareItEqually() {
            Weighting fresh = new Ensemble().weights(List.of("a", "b", "c"));
            assertEquals(3, fresh.weights().size());
            for (double value : fresh.weights().values()) {
                assertEquals(1.0 / 3.0, value, PLACES);
            }
            assertNotNull(fresh.spread());
        }

        @Test
        void aSourceAddedLaterIsNotTrustedImmediately() {
            Ensemble ensemble = feed(new Ensemble(), weather(600), sources("old", 0.0), sources("old", 0.2), 2L);
            Map<String, Double> weights = ensemble.weights(List.of("old", "brand_new")).weights();
            assertTrue(weights.get("old") > weights.get("brand_new"), weights.toString());
        }

        /** From the code: no names, no weights and no spread. */
        @Test
        void nobodyAskedForIsNobodyWeighted() {
            Weighting nobody = new Ensemble().weights(List.of());
            assertTrue(nobody.weights().isEmpty());
            assertNull(nobody.spread());
        }

        /**
         * From the code: a negative weight is the mathematics using one
         * source to correct another, and one bad cycle away from a wild
         * answer, so it is clamped to nothing and the rest renormalised.
         * Two cycles with no time between them, b's error twice a's: the
         * stored covariance is [[6.25, 8], [8, 18.25]] over a weight of 2,
         * which gives b a weight of about -0.2.
         */
        @Test
        void aNegativeWeightIsClampedAndTheRestRenormalised() {
            Ensemble ensemble = new Ensemble();
            ensemble.observe(5.0, List.of(new Reading("a", 7.0), new Reading("b", 9.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 3.0), new Reading("b", 1.0)), 0.0);
            assertEquals(6.25, ensemble.covariance().get("a").get("a"), 0.0);
            assertEquals(8.0, ensemble.covariance().get("a").get("b"), 0.0);
            assertEquals(18.25, ensemble.covariance().get("b").get("b"), 0.0);

            Weighting clamped = ensemble.weights(List.of("a", "b"));
            assertEquals(1.0, clamped.weights().get("a"), 1e-12);
            assertEquals(0.0, clamped.weights().get("b"), 0.0);
            // The spread is still the solve's: 1 / (1' S^-1 1) with S = [[3.125, 4], [4, 9.125]].
            double determinant = 3.125 * 9.125 - 4.0 * 4.0;
            double total = (9.125 - 4.0 + 3.125 - 4.0) / determinant;
            assertEquals(Math.sqrt(1.0 / total), clamped.spread(), 1e-9);
        }

        /**
         * From the code: two sources that are the same source, scored until
         * the prior has decayed to nothing, leave a covariance the solve
         * cannot invert. That is answered with equal shares and no spread,
         * which is also what "nobody scored yet" looks like.
         */
        @Test
        void aCovarianceThatCannotBeSolvedFallsBackToEqualShares() {
            Ensemble ensemble = new Ensemble(HALF_HOUR);
            for (int cycle = 0; cycle < 60; cycle++) {
                double offset = cycle % 2 == 0 ? 2.0 : -2.0;
                ensemble.observe(5.0, List.of(new Reading("a", 5.0 + offset), new Reading("b", 5.0 + offset)),
                        HALF_HOUR);
            }
            Weighting fallen = ensemble.weights(List.of("a", "b"));
            assertEquals(0.5, fallen.weights().get("a"), 0.0);
            assertEquals(0.5, fallen.weights().get("b"), 0.0);
            assertNull(fallen.spread());

            Combined out = ensemble.combine(List.of(new Reading("a", 5.0), new Reading("b", 5.0)));
            assertNull(out.uncertainty());
            assertEquals(Map.of("a", 0.5, "b", 0.5), out.weights());
        }

        /**
         * From the code: nothing is known to a thousandth. Thirty horizons
         * of silence drop the stored covariance to exactly nothing, and the
         * floor on the diagonal is what the solve then sees.
         */
        @Test
        void nothingIsKnownToAThousandth() {
            assertEquals(1e-3, DIAGONAL_FLOOR, 0.0);
            Ensemble ensemble = new Ensemble(100.0);
            ensemble.observe(5.0, List.of(new Reading("a", 7.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 3.0)), 3000.0);
            assertEquals(0.0, ensemble.covariance().get("a").get("a"), 0.0);
            assertEquals(Math.sqrt(DIAGONAL_FLOOR), ensemble.weights(List.of("a")).spread(), 1e-12);
        }

        /**
         * A source that goes quiet keeps its cross term while its partner's own variance is
         * dropped outright: the matrix turns indefinite and the solve's total comes out negative.
         * That is answered with equal shares and no spread, not with the clamped solve.
         */
        @Test
        void aQuietSourcesCrossTermCanMakeTheSolveFallBack() {
            Ensemble ensemble = new Ensemble(100.0);
            ensemble.observe(5.0, List.of(new Reading("a", 7.0), new Reading("b", 7.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 9.0), new Reading("b", 9.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 8.0)), 3000.0);
            assertEquals(0.0, ensemble.covariance().get("a").get("a"), 0.0);
            assertEquals(1.0, ensemble.covariance().get("a").get("b"), 0.0);
            Weighting fallen = ensemble.weights(List.of("a", "b"));
            assertEquals(0.5, fallen.weights().get("a"), 0.0);
            assertEquals(0.5, fallen.weights().get("b"), 0.0);
            assertNull(fallen.spread());
        }
    }

    /** One weather out of many opinions. */
    @Nested
    class Combining {
        @Test
        void nothingOfferedIsNotAnAnswer() {
            Combined out = new Ensemble().combine(List.of(new Reading("a", null)));
            assertNull(out.temperature());
            assertEquals(0, out.sources());
        }

        /**
         * 350 and 10 degrees average to 0, not to 180 - and getting it
         * wrong would put a northerly on the sheltered side of the house.
         */
        @Test
        void windDirectionIsAveragedOnTheCircle() {
            Combined out = new Ensemble().combine(List.of(
                    new Reading("a", 5.0, null, 350.0, null),
                    new Reading("b", 5.0, null, 10.0, null)));
            assertNotNull(out.bearing());
            assertTrue(out.bearing() > 359.0 || out.bearing() < 1.0, String.valueOf(out.bearing()));
        }

        /**
         * From the code: Python's % is never negative, so a westerly is 270,
         * not the -90 that atan2 gives; two sources either side of west
         * average to it.
         */
        @Test
        void aWesterlyIsTwoHundredAndSeventyNotMinusNinety() {
            Combined one = new Ensemble().combine(List.of(new Reading("a", 5.0, null, 270.0, null)));
            assertEquals(270.0, one.bearing(), PLACES);
            Combined two = new Ensemble().combine(List.of(
                    new Reading("a", 5.0, null, 200.0, null),
                    new Reading("b", 5.0, null, 340.0, null)));
            assertEquals(270.0, two.bearing(), PLACES);
        }

        @Test
        void aSourceWithNoWindDoesNotDragItTowardZero() {
            Combined out = new Ensemble().combine(List.of(
                    new Reading("a", 5.0, 8.0, null, null),
                    new Reading("b", 5.0, null, null, null)));
            assertEquals(8.0, out.wind(), PLACES);
        }

        @Test
        void itReportsWhatEachSourceWasWorth() {
            Ensemble ensemble = feed(new Ensemble(), weather(300), sources("a", 0.0, "b", 0.0));
            Combined out = ensemble.combine(List.of(new Reading("a", 5.0), new Reading("b", 5.0)));
            assertEquals(Set.of("a", "b"), out.weights().keySet());
            assertEquals(2, out.sources());
        }

        /** From the code: a source's known offset is taken out before its temperature counts. */
        @Test
        void theBiasIsTakenOutOfTheCombinedTemperature() {
            Ensemble ensemble = feed(new Ensemble(), weather(50), sources("warm", 2.0));
            assertEquals(2.0, ensemble.bias().get("warm"), 1e-9);
            Combined out = ensemble.combine(List.of(new Reading("warm", 7.0)));
            assertEquals(5.0, out.temperature(), PLACES);
            // A source never scored has no offset to take out.
            assertEquals(7.0, ensemble.combine(List.of(new Reading("new", 7.0))).temperature(), 0.0);
        }

        /**
         * From the code: a reading without a temperature gets no weight, so
         * its wind, bearing and cloud count for nothing either.
         */
        @Test
        void aReadingWithoutATemperatureLendsNothingNotEvenItsWind() {
            Combined out = new Ensemble().combine(List.of(
                    new Reading("a", 5.0, 3.0, 90.0, 20.0),
                    new Reading("b", null, 12.0, 270.0, 100.0)));
            assertEquals(1, out.sources());
            assertEquals(Set.of("a"), out.weights().keySet());
            assertEquals(5.0, out.temperature(), 0.0);
            assertEquals(3.0, out.wind(), PLACES);
            assertEquals(90.0, out.bearing(), PLACES);
            assertEquals(20.0, out.cloud(), PLACES);
        }

        /**
         * From the code: the weights the result carries are Python's
         * round(value, 4), for the dashboard; the ones the solve gives are
         * not. Source a scored twice with no time between, errors 2 and 4,
         * has a variance of 3.25 over a weight of 2 against b's prior of
         * 2.25, so the weights are 18/31 and 13/31.
         */
        @Test
        void theWeightsComeRoundedToFourDecimals() {
            Ensemble ensemble = new Ensemble();
            ensemble.observe(5.0, List.of(new Reading("a", 7.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 9.0)), 0.0);
            Map<String, Double> solved = ensemble.weights(List.of("a", "b")).weights();
            assertEquals(18.0 / 31.0, solved.get("a"), 1e-12);
            assertEquals(13.0 / 31.0, solved.get("b"), 1e-12);

            Combined out = ensemble.combine(List.of(new Reading("a", 5.0), new Reading("b", 5.0)));
            assertEquals(0.5806, out.weights().get("a"), 0.0);
            assertEquals(0.4194, out.weights().get("b"), 0.0);
            assertEquals(List.of("a", "b"), List.copyOf(out.weights().keySet()));
        }

        /**
         * From the code: one source nobody has scored carries the whole
         * answer, with the prior's degree and a half of doubt.
         */
        @Test
        void oneFreshSourceHasWeightOneAndSpreadOneAndAHalf() {
            Combined out = new Ensemble().combine(List.of(new Reading("a", 5.0)));
            assertEquals(5.0, out.temperature(), 0.0);
            assertEquals(Map.of("a", 1.0), out.weights());
            assertEquals(Math.sqrt(PRIOR_VARIANCE), out.uncertainty(), PLACES);
            assertEquals(1.5, out.uncertainty(), PLACES);
            assertEquals(1, out.sources());
            assertNull(out.wind());
            assertNull(out.bearing());
            assertNull(out.cloud());
        }
    }

    /** From the code: what one cycle of observe does, and what it leaves alone. */
    @Nested
    class Scoring {
        @Test
        void noTruthIsFalseAndChangesNothing() {
            Ensemble ensemble = new Ensemble();
            assertFalse(ensemble.observe(null, List.of(new Reading("a", 5.0)), HALF_HOUR));
            assertEquals(0.0, ensemble.weight(), 0.0);
            assertTrue(ensemble.bias().isEmpty());
            assertTrue(ensemble.covariance().isEmpty());

            // Nor once it has learned something.
            feed(ensemble, weather(10), sources("a", 1.0, "b", 0.0), sources("a", 0.5, "b", 0.5), 3L);
            double weight = ensemble.weight();
            Map<String, Double> bias = new LinkedHashMap<>(ensemble.bias());
            Map<String, Map<String, Double>> covariance = snapshot(ensemble.covariance());
            assertFalse(ensemble.observe(null, List.of(new Reading("a", 5.0), new Reading("b", 5.0)), HALF_HOUR));
            assertEquals(weight, ensemble.weight(), 0.0);
            assertEquals(bias, ensemble.bias());
            assertEquals(covariance, ensemble.covariance());
        }

        /** Not even the weight is stepped: a cycle nobody took part in did not happen. */
        @Test
        void nobodyOfferingIsFalseAndChangesNothing() {
            Ensemble ensemble = feed(new Ensemble(), weather(10), sources("a", 1.0, "b", 0.0),
                    sources("a", 0.5, "b", 0.5), 3L);
            double weight = ensemble.weight();
            Map<String, Double> bias = new LinkedHashMap<>(ensemble.bias());
            Map<String, Map<String, Double>> covariance = snapshot(ensemble.covariance());

            assertFalse(ensemble.observe(5.0, List.of(new Reading("a"), new Reading("b", null)), HALF_HOUR));
            assertFalse(ensemble.observe(5.0, List.of(), HALF_HOUR));
            assertEquals(weight, ensemble.weight(), 0.0);
            assertEquals(bias, ensemble.bias());
            assertEquals(covariance, ensemble.covariance());
        }

        @Test
        void eachScoredCycleAddsOneToTheWeightWhenDtIsZero() {
            Ensemble ensemble = new Ensemble();
            for (int cycle = 1; cycle <= 5; cycle++) {
                assertTrue(ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 0.0));
                assertEquals(cycle, ensemble.weight(), 0.0);
            }
            // Time running backwards is no time at all.
            assertTrue(ensemble.observe(5.0, List.of(new Reading("a", 6.0)), -10.0));
            assertEquals(6.0, ensemble.weight(), 0.0);
        }

        @Test
        void withDtEqualToTheHorizonTheSecondCyclesWeightIsOneOverEPlusOne() {
            Ensemble ensemble = new Ensemble(3600.0);
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 3600.0);
            assertEquals(1.0, ensemble.weight(), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 3600.0);
            assertEquals(Math.pow(Math.E, -1) + 1.0, ensemble.weight(), 1e-12);
        }

        /** From the code: no horizon, zero or less, means nothing is ever forgotten. */
        @Test
        void withoutAHorizonNothingIsForgotten() {
            for (double horizon : new double[] {0.0, -1.0}) {
                Ensemble ensemble = new Ensemble(horizon);
                for (int cycle = 0; cycle < 3; cycle++) {
                    ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 86400.0);
                }
                assertEquals(3.0, ensemble.weight(), 0.0, "horizon " + horizon);
            }
        }

        /**
         * From the code: just under thirty horizons on a trace of the old
         * weight remains; at thirty it is dropped outright rather than
         * multiplied by e^-30.
         */
        @Test
        void thirtyHorizonsOnTheOldWeightIsDroppedOutright() {
            Ensemble ensemble = new Ensemble(100.0);
            for (int cycle = 0; cycle < 3; cycle++) {
                ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 0.0);
            }
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 2999.0);
            assertTrue(ensemble.weight() > 1.0, "weight " + ensemble.weight());
            assertEquals(3.0 * Math.pow(Math.E, -29.99) + 1.0, ensemble.weight(), 1e-12);
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 3000.0);
            assertEquals(1.0, ensemble.weight(), 0.0);
        }

        @Test
        void retuneChangesTheHorizon() {
            Ensemble ensemble = new Ensemble();
            assertEquals(14.0 * 24.0 * 3600.0, DEFAULT_HORIZON_SECONDS, 0.0);
            assertEquals(DEFAULT_HORIZON_SECONDS, ensemble.horizon(), 0.0);
            ensemble.retune(3600.0);
            assertEquals(3600.0, ensemble.horizon(), 0.0);
            // And the decay follows it.
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 3600.0);
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 3600.0);
            assertEquals(Math.pow(Math.E, -1) + 1.0, ensemble.weight(), 1e-12);
        }

        /** The bias starts at the first error and, with no time between the cycles, moves half way on the second. */
        @Test
        void theFirstCyclesErrorBecomesTheBiasExactly() {
            Ensemble ensemble = new Ensemble();
            assertTrue(ensemble.observe(5.0, List.of(new Reading("a", 7.5), new Reading("b", 4.0)), HALF_HOUR));
            assertEquals(2.5, ensemble.bias().get("a"), 0.0);
            assertEquals(-1.0, ensemble.bias().get("b"), 0.0);
            assertEquals(List.of("a", "b"), List.copyOf(ensemble.bias().keySet()));

            ensemble.observe(5.0, List.of(new Reading("a", 9.5), new Reading("b", 4.0)), 0.0);
            assertEquals(3.5, ensemble.bias().get("a"), 0.0);
            assertEquals(-1.0, ensemble.bias().get("b"), 0.0);
        }

        /**
         * A source that said nothing this cycle is left exactly as it was
         * rather than counted as correct: the difference between a quiet
         * source and a good one. Only the pairs that were scored move.
         */
        @Test
        void aQuietSourceKeepsItsBiasAndItsCovarianceRow() {
            Ensemble ensemble = feed(new Ensemble(), weather(20), sources("a", 1.0, "b", -0.5),
                    sources("a", 0.5, "b", 0.5), 3L);
            double weight = ensemble.weight();
            double biasB = ensemble.bias().get("b");
            Map<String, Double> rowB = new LinkedHashMap<>(ensemble.covariance().get("b"));
            double aAgainstB = ensemble.covariance().get("a").get("b");
            double aAgainstA = ensemble.covariance().get("a").get("a");

            assertTrue(ensemble.observe(5.0, List.of(new Reading("a", 9.0), new Reading("b")), HALF_HOUR));
            assertTrue(ensemble.weight() > weight);
            assertEquals(biasB, ensemble.bias().get("b"), 0.0);
            assertEquals(rowB, ensemble.covariance().get("b"));
            assertEquals(aAgainstB, ensemble.covariance().get("a").get("b"), 0.0);
            double aAgainstANow = ensemble.covariance().get("a").get("a");
            assertNotEquals(aAgainstA, aAgainstANow);
        }

        /**
         * From the code: the first error of a source is its bias exactly,
         * so the de-biased error is zero and the stored diagonal is the
         * prior as it was; the next error moves it. No time between the
         * cycles, so nothing decays in between.
         */
        @Test
        void aScoredSourcesDiagonalStartsFromThePrior() {
            Ensemble ensemble = new Ensemble();
            ensemble.observe(5.0, List.of(new Reading("a", 7.0), new Reading("b", 4.0)), 0.0);
            assertEquals(PRIOR_VARIANCE, ensemble.covariance().get("a").get("a"), 0.0);
            assertEquals(PRIOR_VARIANCE, ensemble.covariance().get("b").get("b"), 0.0);
            assertEquals(0.0, ensemble.covariance().get("a").get("b"), 0.0);
            assertEquals(0.0, ensemble.covariance().get("b").get("a"), 0.0);
            assertEquals(List.of("a", "b"), List.copyOf(ensemble.covariance().keySet()));
            assertEquals(List.of("a", "b"), List.copyOf(ensemble.covariance().get("a").keySet()));
            assertEquals(1.5, ensemble.weights(List.of("a")).spread(), 0.0);

            // Errors 2 then 4: the bias comes to 3, the de-biased error to 1, and 1 is added.
            ensemble.observe(5.0, List.of(new Reading("a", 9.0), new Reading("b", 4.0)), 0.0);
            assertEquals(PRIOR_VARIANCE + 1.0, ensemble.covariance().get("a").get("a"), 0.0);
            assertEquals(PRIOR_VARIANCE, ensemble.covariance().get("b").get("b"), 0.0);
            // What the solve sees is the stored value over the weight, two cycles now.
            assertEquals(Math.sqrt((PRIOR_VARIANCE + 1.0) / 2.0), ensemble.weights(List.of("a")).spread(), 1e-12);
        }

        /**
         * A source that joins once the weight is above one starts from its own first error, not
         * from zero: bias.get(name, error) in the Python, so its first de-biased error is 0.
         */
        @Test
        void aSourceThatJoinsLaterStartsFromItsOwnFirstError() {
            Ensemble ensemble = new Ensemble();
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 6.0)), 0.0);
            ensemble.observe(5.0, List.of(new Reading("a", 6.0), new Reading("b", 8.0)), 0.0);
            assertEquals(3.0, ensemble.weight(), 0.0);
            assertEquals(3.0, ensemble.bias().get("b"), 0.0);
            assertEquals(PRIOR_VARIANCE, ensemble.covariance().get("b").get("b"), 0.0);
        }

        /** The maps keep the readings' order, as Python's dicts do; a HashMap of b and a would not. */
        @Test
        void theOrderIsTheReadingsNotTheAlphabet() {
            Ensemble ensemble = new Ensemble();
            ensemble.observe(5.0, List.of(new Reading("b", 6.0), new Reading("a", 7.0)), 0.0);
            assertEquals(List.of("b", "a"), List.copyOf(ensemble.bias().keySet()));
            assertEquals(List.of("b", "a"), List.copyOf(ensemble.covariance().keySet()));
            assertEquals(List.of("b", "a"), List.copyOf(ensemble.covariance().get("a").keySet()));
            assertEquals(List.of("b", "a"), List.copyOf(ensemble.weights(List.of("b", "a")).weights().keySet()));
            Combined out = ensemble.combine(List.of(new Reading("b", 6.0), new Reading("a", 7.0)));
            assertEquals(List.of("b", "a"), List.copyOf(out.weights().keySet()));
        }
    }

    /** outdoor_from_forecast: the fetched forecast standing in for a missing outdoor sensor. */
    @Nested
    class TheStandIn {
        @Test
        void anEmptyOrMissingForecastIsNoAnswer() {
            assertNull(outdoorFromForecast(List.of()));
            assertNull(outdoorFromForecast(null));
        }

        /** The outlook starts at the next whole hour, so up to an hour ahead is the normal case. */
        @Test
        void aFirstSlotAnHourOutStandsIn() {
            List<Forecast> forecast = List.of(
                    new Forecast(1.0, new Conditions(-3.5)),
                    new Forecast(2.0, new Conditions(-4.0)));
            assertEquals(-3.5, outdoorFromForecast(forecast), 0.0);
            assertEquals(2.0, outdoorFromForecast(List.of(new Forecast(0.0, new Conditions(2.0)))), 0.0);
        }

        /** Two hours out means the fetched forecast has run out; only the first slot is looked at. */
        @Test
        void twoHoursOutIsNoAnswer() {
            List<Forecast> runOut = List.of(
                    new Forecast(2.0, new Conditions(-3.5)),
                    new Forecast(3.0, new Conditions(-4.0)));
            assertNull(outdoorFromForecast(runOut));
            assertNull(outdoorFromForecast(List.of(new Forecast(1.5001, new Conditions(-3.5)))));
        }

        @Test
        void exactlyAnHourAndAHalfStillStandsIn() {
            assertEquals(1.5, STAND_IN_HOURS, 0.0);
            assertEquals(-3.5, outdoorFromForecast(List.of(new Forecast(1.5, new Conditions(-3.5)))), 0.0);
        }

        @Test
        void aSlotWithoutConditionsIsNoAnswer() {
            assertNull(outdoorFromForecast(List.of(new Forecast(1.0, null))));
        }
    }

    /** Reading and Combined: Python's dataclasses and their defaults. */
    @Nested
    class TheRecords {
        @Test
        void theShortConstructorsOfReadingLeaveTheRestNull() {
            Reading quiet = new Reading("smhi");
            assertEquals("smhi", quiet.name());
            assertNull(quiet.temperature());
            assertNull(quiet.wind());
            assertNull(quiet.bearing());
            assertNull(quiet.cloud());
            assertEquals(new Reading("smhi", null, null, null, null), quiet);

            Reading plain = new Reading("smhi", 5.0);
            assertEquals("smhi", plain.name());
            assertEquals(5.0, plain.temperature(), 0.0);
            assertNull(plain.wind());
            assertNull(plain.bearing());
            assertNull(plain.cloud());
            assertEquals(new Reading("smhi", 5.0, null, null, null), plain);
        }

        @Test
        void anEmptyCombinedHasNothingInIt() {
            Combined empty = new Combined();
            assertNull(empty.temperature());
            assertNull(empty.wind());
            assertNull(empty.bearing());
            assertNull(empty.cloud());
            assertNull(empty.uncertainty());
            assertTrue(empty.weights().isEmpty());
            assertEquals(0, empty.sources());
            assertEquals(empty, new Ensemble().combine(List.of()));
        }
    }

    /** The answer file: each row replayed on a fresh ensemble, every answer checked as it comes, the state last. */
    @Nested
    @Tag("python")
    class SameAsPython {
        /** What is stored is within a billionth; what has been through the solve within a millionth. */
        static final double STORED = 1e-9;
        static final double SOLVED = 1e-6;

        /**
         * ops;outputs;weight;bias;covariance: the ops with "|" between them, one output entry per op
         * that answers, and the ensemble's weight, bias and covariance after the last op.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/ensemble.csv", delimiter = ';', numLinesToSkip = 1, maxCharsPerColumn = 1_000_000)
        void sameEnsemble(String ops, String outputs, String weightAfter, String biasAfter, String covarianceAfter) {
            Ensemble ensemble = new Ensemble();
            String[] sequence = ops(ops);
            String[] answers = entries(outputs);
            int answered = 0;
            for (int i = 0; i < sequence.length; i++) {
                String[] op = fields(sequence[i]);
                String what = "ops[" + i + "] " + op[0];
                switch (op[0]) {
                    case "observe" -> assertEquals(bool(answers[answered++]),
                            ensemble.observe(number(op[1]), readings(op[3]), number(op[2])), what);
                    case "weights" -> {
                        List<String> names = names(op[1]);
                        String[] expected = fields(answers[answered++]);
                        Weighting weighting = ensemble.weights(names);
                        assertCloseMap(weights(names, expected[0]), weighting.weights(), SOLVED, what + " weights");
                        assertClose(number(expected[1]), weighting.spread(), SOLVED, what + " spread");
                    }
                    case "combine" -> {
                        String[] expected = fields(answers[answered++]);
                        Combined combined = ensemble.combine(readings(op[1]));
                        assertClose(number(expected[0]), combined.temperature(), SOLVED, what + " temperature");
                        assertClose(number(expected[1]), combined.wind(), SOLVED, what + " wind");
                        assertSameBearing(number(expected[2]), combined.bearing(), SOLVED, what + " bearing");
                        assertClose(number(expected[3]), combined.cloud(), SOLVED, what + " cloud");
                        assertClose(number(expected[4]), combined.uncertainty(), SOLVED, what + " uncertainty");
                        assertEquals(Integer.parseInt(expected[5]), combined.sources(), what + " sources");
                        assertCloseMap(map(expected[6]), combined.weights(), SOLVED, what + " weights");
                    }
                    case "retune" -> ensemble.retune(number(op[1]));
                    case "standin" -> assertClose(number(answers[answered++]),
                            outdoorFromForecast(standin(op[1])), STORED, what);
                    default -> fail(what + ": an op the test does not know");
                }
            }
            assertEquals(answers.length, answered, "how many answers were used");
            assertClose(number(weightAfter), ensemble.weight(), STORED, "weight after the last op");
            assertCloseMap(map(biasAfter), ensemble.bias(), STORED, "bias after the last op");
            assertCloseCovariance(covariance(covarianceAfter), ensemble.covariance(), STORED,
                    "covariance after the last op");
        }
    }
}
