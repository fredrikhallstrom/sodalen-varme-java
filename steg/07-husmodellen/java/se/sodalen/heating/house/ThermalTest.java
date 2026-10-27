package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static se.sodalen.heating.house.Facit.assertClose;
import static se.sodalen.heating.house.Facit.assertCloseAll;
import static se.sodalen.heating.house.Facit.fields;
import static se.sodalen.heating.house.Facit.number;
import static se.sodalen.heating.house.Thermal.DAY_SECONDS;
import static se.sodalen.heating.house.Thermal.DEFAULT_HORIZON_SECONDS;
import static se.sodalen.heating.house.Thermal.DEFAULT_PRIORS;
import static se.sodalen.heating.house.Thermal.HOUSE_FLOORS;
import static se.sodalen.heating.house.Thermal.LOSS;
import static se.sodalen.heating.house.Thermal.LOSS_FLOOR;
import static se.sodalen.heating.house.Thermal.NIGHT_FLOORS;
import static se.sodalen.heating.house.Thermal.NIGHT_GAINS;
import static se.sodalen.heating.house.Thermal.NIGHT_MAX_SECONDS;
import static se.sodalen.heating.house.Thermal.NIGHT_MIN_NIGHTS;
import static se.sodalen.heating.house.Thermal.NIGHT_MIN_SECONDS;
import static se.sodalen.heating.house.Thermal.NIGHT_PACE;
import static se.sodalen.heating.house.Thermal.NIGHT_RADIATORS;
import static se.sodalen.heating.house.Thermal.NIGHT_REGRESSORS;
import static se.sodalen.heating.house.Thermal.NIGHT_SPREAD;
import static se.sodalen.heating.house.Thermal.NIGHT_WIND;
import static se.sodalen.heating.house.Thermal.RADIATORS;
import static se.sodalen.heating.house.Thermal.REGRESSORS;
import static se.sodalen.heating.house.Thermal.STEADY_EVERY_SECONDS;
import static se.sodalen.heating.house.Thermal.STEADY_FLOORS;
import static se.sodalen.heating.house.Thermal.STEADY_GAINS;
import static se.sodalen.heating.house.Thermal.STEADY_PACE;
import static se.sodalen.heating.house.Thermal.STEADY_RADIATORS;
import static se.sodalen.heating.house.Thermal.STEADY_REGRESSORS;
import static se.sodalen.heating.house.Thermal.STEADY_SUN;
import static se.sodalen.heating.house.Thermal.STEADY_SUN_STRENGTH;
import static se.sodalen.heating.house.Thermal.STEADY_WIND;
import static se.sodalen.heating.house.Thermal.STEP_HOURS;
import static se.sodalen.heating.house.Thermal.SUN_INDEX;
import static se.sodalen.heating.house.Thermal.WIND;
import static se.sodalen.heating.house.Thermal.WIND_COS;
import static se.sodalen.heating.house.Thermal.WIND_SIN;
import static se.sodalen.heating.house.Thermal.houseFit;
import static se.sodalen.heating.house.Thermal.housePriors;
import static se.sodalen.heating.house.Thermal.nightFit;
import static se.sodalen.heating.house.Thermal.nightRegressors;
import static se.sodalen.heating.house.Thermal.radiatorExcess;
import static se.sodalen.heating.house.Thermal.regressors;
import static se.sodalen.heating.house.Thermal.solarIndex;
import static se.sodalen.heating.house.Thermal.steadyFit;
import static se.sodalen.heating.house.Thermal.steadyRegressors;
import static se.sodalen.heating.house.Thermal.weatherAt;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.estimate.MultiRegression;
import se.sodalen.heating.estimate.Prior;

/**
 * thermal.py's module level: the physics the house model is built from. The
 * rows of its fits, the priors and floors it is told, the sun's index, the
 * radiators' heat and the weather ahead.
 *
 * <p>Python: the functions and constants of thermal.py, TestSolarIndex and
 * TestTheRadiatorsHeat in tests/test_thermal.py; the rest from the code.
 */
class ThermalTest {

    static final double CLOSE = 1e-9;
    static final double HOUR = 3600.0;

    @Nested
    class TheSolarIndex {
        @Test
        void nightIsNothing() {
            assertEquals(0.0, solarIndex(-5.0, 0.0));
            assertEquals(0.0, solarIndex(null, 0.0));
            assertEquals(0.0, solarIndex(0.0, null));
        }

        @Test
        void higherSunIsMore() {
            assertTrue(solarIndex(45.0, 0.0) > solarIndex(10.0, 0.0));
        }

        @Test
        void cloudTakesMostOfItButNotAll() {
            double clear = solarIndex(40.0, 0.0);
            double overcast = solarIndex(40.0, 100.0);
            assertTrue(overcast < clear);
            assertTrue(overcast > 0.0);
        }

        /** The sine of the sun's height: a half at thirty degrees, with no cloud or a clear sky alike. */
        @Test
        void itIsTheSineOfTheHeight() {
            assertEquals(0.5, solarIndex(30.0, null), CLOSE);
            assertEquals(0.5, solarIndex(30.0, 0.0), CLOSE);
            assertEquals(1.0, solarIndex(90.0, null), CLOSE);
        }

        /** Even a solid overcast passes some diffuse light, and a house with windows notices it. */
        @Test
        void aSolidOvercastPassesFifteenPercent() {
            assertEquals(0.15 * solarIndex(40.0, null), solarIndex(40.0, 100.0), CLOSE);
            assertEquals((0.15 + 0.85 * 0.5) * solarIndex(40.0, null), solarIndex(40.0, 50.0), CLOSE);
        }

        @Test
        void theHeightAndTheCloudAreClampedToTheirRanges() {
            assertEquals(solarIndex(90.0, null), solarIndex(120.0, null));
            assertEquals(solarIndex(40.0, 100.0), solarIndex(40.0, 150.0));
            assertEquals(solarIndex(40.0, 0.0), solarIndex(40.0, -20.0));
        }
    }

    /** radiatorExcess: the radiators' heat whatever the pump. */
    @Nested
    class TheRadiatorsHeat {
        @Test
        void itIsTheMeanWaterOverTheRoomToTheOnePointThree() {
            assertEquals(Math.pow(20.0, 1.3), radiatorExcess(45.0, 35.0, 20.0), 1e-7);
        }

        /** A slower pump: a wider delta round the same mean water. */
        @Test
        void theSameHeatForTheSameMeanWhateverTheDelta() {
            assertEquals(radiatorExcess(45.0, 35.0, 20.0), radiatorExcess(42.0, 38.0, 20.0));
        }

        @Test
        void waterAtTheRoomGivesNothing() {
            assertEquals(0.0, radiatorExcess(21.0, 19.0, 20.0));
        }

        @Test
        void waterUnderTheRoomGivesNothingNotLess() {
            assertEquals(0.0, radiatorExcess(18.0, 16.0, 20.0));
        }

        @Test
        void aMissingReadingIsNoAnswer() {
            assertNull(radiatorExcess(null, 35.0, 20.0));
            assertNull(radiatorExcess(45.0, null, 20.0));
            assertNull(radiatorExcess(45.0, 35.0, null));
        }
    }

    /** The rows of the three fits: what the columns mean, by hand. */
    @Nested
    class TheRows {
        /**
         * 21 inside, 1 outside, an easterly of 4 m/s, a sun of 0.3, a delta
         * of 7 at six in the morning: the difference is 20, the wind terms
         * are the speed times it along cos and sin of the bearing, and the
         * day is a quarter of the way round.
         */
        @Test
        void aRowIsTheNineTermsOfThePhysics() {
            double[] row = regressors(21.0, 1.0, 4.0, 90.0, 0.3, 7.0, 6.0);
            assertEquals(REGRESSORS, row.length);
            assertArrayEquals(new double[] {1.0, -20.0, -80.0, 0.0, -80.0, 0.3, 7.0, 0.0, 1.0}, row, CLOSE);
        }

        @Test
        void noClockGivesZeroDailyTerms() {
            double[] row = regressors(21.0, 1.0, 4.0, 90.0, 0.3, 7.0, null);
            assertEquals(0.0, row[7]);
            assertEquals(0.0, row[8]);
            assertArrayEquals(new double[] {1.0, -20.0, -80.0, 0.0, -80.0, 0.3, 7.0}, Arrays.copyOf(row, 7), CLOSE);
        }

        /** No wind, a wind below nought or no bearing: the wind terms are nought, the rest as before. */
        @Test
        void noWindGivesZeroWindTerms() {
            for (Double wind : new Double[] {null, 0.0, -3.0}) {
                double[] row = regressors(21.0, 1.0, wind, null, 0.3, 7.0, null);
                assertArrayEquals(new double[] {1.0, -20.0, 0.0, 0.0, 0.0, 0.3, 7.0, 0.0, 0.0}, row, 0.0);
            }
            double[] noBearing = regressors(21.0, 1.0, 4.0, null, 0.3, 7.0, null);
            assertArrayEquals(new double[] {1.0, -20.0, -80.0, -80.0, 0.0, 0.3, 7.0, 0.0, 0.0}, noBearing, 0.0);
        }

        @Test
        void aNegativeDeltaIsClampedToNought() {
            assertEquals(0.0, regressors(21.0, 1.0, 4.0, 90.0, 0.3, -3.0, 6.0)[RADIATORS]);
            assertEquals(7.0, regressors(21.0, 1.0, 4.0, 90.0, 0.3, 7.0, 6.0)[RADIATORS]);
        }

        /** 1, the delta, the sun, minus the slope and minus the three wind means. */
        @Test
        void aSteadyRowIsTheDaysMeans() {
            double[] row = steadyRegressors(0.3, 7.0, 0.02, new double[] {4.0, 1.0, 2.0});
            assertEquals(STEADY_REGRESSORS, row.length);
            assertArrayEquals(new double[] {1.0, 7.0, 0.3, -0.02, -4.0, -1.0, -2.0}, row, 0.0);
            assertEquals(0.0, steadyRegressors(0.3, -7.0, 0.02, new double[] {4.0, 1.0, 2.0})[STEADY_RADIATORS]);
        }

        /** 1, the radiators' heat, minus the slope and minus the three wind means. */
        @Test
        void aNightRowIsTheNightsMeans() {
            double[] row = nightRegressors(12.0, -0.05, new double[] {3.0, 0.5, -1.0});
            assertEquals(NIGHT_REGRESSORS, row.length);
            assertArrayEquals(new double[] {1.0, 12.0, 0.05, -3.0, -0.5, 1.0}, row, 0.0);
            assertEquals(0.0, nightRegressors(-12.0, -0.05, new double[] {3.0, 0.5, -1.0})[NIGHT_RADIATORS]);
        }
    }

    /** Priors and floors on the house's fit - see house_priors and HOUSE_FLOORS in thermal.py. */
    @Nested
    class ThePhysicsItIsTold {
        /**
         * Half a day of samples at 1800 s is 24 of them. The loss is a
         * sixty-hour house, worth 24 samples at a spread of 5 K; the wind a
         * twentieth of it per m/s, worth 24 at 30; the radiators hold the
         * design point, 39 K of span for 10 K of delta, worth 24 at 3.
         */
        @Test
        void thePriorsAtTheDesignPointByHand() {
            Map<Integer, Prior> priors = housePriors(39.0, 10.0, 1800.0);
            assertEquals(5, priors.size());
            assertEquals(1.0 / 60.0, priors.get(LOSS).expected(), CLOSE);
            assertEquals(24.0 * 25.0, priors.get(LOSS).strength(), CLOSE);
            assertEquals(0.05 / 60.0, priors.get(WIND).expected(), CLOSE);
            assertEquals(24.0 * 900.0, priors.get(WIND).strength(), CLOSE);
            assertEquals(39.0 / 600.0, priors.get(RADIATORS).expected(), CLOSE);
            assertEquals(24.0 * 9.0, priors.get(RADIATORS).strength(), CLOSE);
        }

        /** Which side is exposed is what three mild days cannot tell: the directional terms start at nothing. */
        @Test
        void theDirectionalWindTermsStartAtNothing() {
            Map<Integer, Prior> priors = housePriors(39.0, 10.0, 1800.0);
            assertEquals(0.0, priors.get(WIND_COS).expected());
            assertEquals(0.0, priors.get(WIND_SIN).expected());
            assertEquals(priors.get(WIND).strength(), priors.get(WIND_COS).strength());
            assertEquals(priors.get(WIND).strength(), priors.get(WIND_SIN).strength());
        }

        /** A sample interval or a design delta under a second or a Kelvin counts as one. */
        @Test
        void aSecondAndAKelvinAreTheLeast() {
            assertEquals(housePriors(39.0, 1.0, 1.0), housePriors(39.0, 0.5, 0.5));
            assertEquals(housePriors(39.0, 1.0, 1.0), housePriors(39.0, -2.0, 0.0));
            assertEquals(12.0 * HOUR * 25.0, housePriors(39.0, 1.0, 1.0).get(LOSS).strength(), CLOSE);
            assertEquals(39.0 / 60.0, housePriors(39.0, 1.0, 1.0).get(RADIATORS).expected(), CLOSE);
        }

        /** A shorter interval is more samples in the half day, so a stronger prior; the expectations do not move. */
        @Test
        void aShorterIntervalMakesThePriorsStronger() {
            Map<Integer, Prior> coarse = housePriors(39.0, 10.0, 1800.0);
            Map<Integer, Prior> fine = housePriors(39.0, 10.0, 600.0);
            for (int index : coarse.keySet()) {
                assertEquals(coarse.get(index).expected(), fine.get(index).expected(), "expected " + index);
                assertEquals(3.0 * coarse.get(index).strength(), fine.get(index).strength(), CLOSE);
            }
        }

        @Test
        void theDefaultPriorsAreTheDefaultsDesignPoint() {
            assertEquals(housePriors(39.0, 10.0, 1800.0), DEFAULT_PRIORS);
        }

        @Test
        void theFloors() {
            assertEquals(Map.of(LOSS, LOSS_FLOOR, WIND, 0.0, RADIATORS, 0.0), HOUSE_FLOORS);
            assertEquals(1.0 / 500.0, LOSS_FLOOR);
            assertEquals(Map.of(STEADY_GAINS, 0.0, STEADY_RADIATORS, 0.0, STEADY_SUN, 0.0, STEADY_PACE, 0.0,
                    STEADY_WIND, 0.0), STEADY_FLOORS);
            assertEquals(Map.of(NIGHT_GAINS, 0.0, NIGHT_RADIATORS, 0.0, NIGHT_PACE, 0.0, NIGHT_WIND, 0.0),
                    NIGHT_FLOORS);
        }

        @Test
        void theConstantsAreThermalPys() {
            assertEquals(9, REGRESSORS);
            assertEquals(1, LOSS);
            assertEquals(2, WIND);
            assertEquals(3, WIND_COS);
            assertEquals(4, WIND_SIN);
            assertEquals(5, SUN_INDEX);
            assertEquals(6, RADIATORS);
            assertEquals(7, STEADY_REGRESSORS);
            assertEquals(0, STEADY_GAINS);
            assertEquals(1, STEADY_RADIATORS);
            assertEquals(2, STEADY_SUN);
            assertEquals(3, STEADY_PACE);
            assertEquals(4, STEADY_WIND);
            assertEquals(6, NIGHT_REGRESSORS);
            assertEquals(0, NIGHT_GAINS);
            assertEquals(1, NIGHT_RADIATORS);
            assertEquals(2, NIGHT_PACE);
            assertEquals(3, NIGHT_WIND);
            assertEquals(9, NIGHT_MIN_NIGHTS);
            assertEquals(24.0 * HOUR, DAY_SECONDS);
            assertEquals(3.0 * HOUR, STEADY_EVERY_SECONDS);
            assertEquals(6.0 * HOUR, NIGHT_MIN_SECONDS);
            assertEquals(12.0 * HOUR, NIGHT_MAX_SECONDS);
            assertEquals(Map.of(NIGHT_RADIATORS, 10.0, NIGHT_PACE, 0.05, NIGHT_WIND, 30.0, NIGHT_WIND + 1, 30.0,
                    NIGHT_WIND + 2, 30.0), NIGHT_SPREAD);
            assertEquals(24.0 * 0.094 * 0.094, STEADY_SUN_STRENGTH, CLOSE);
            assertEquals(21.0 * 24.0 * HOUR, DEFAULT_HORIZON_SECONDS);
            assertEquals(0.25, STEP_HOURS);
        }
    }

    /** The three fits a model is made of. */
    @Nested
    class TheFits {
        @Test
        void eachFitHasItsRowsSizeAndTheHorizonItIsGiven() {
            assertEquals(REGRESSORS, houseFit(HOUR).size());
            assertEquals(STEADY_REGRESSORS, steadyFit(HOUR).size());
            assertEquals(NIGHT_REGRESSORS, nightFit(HOUR).size());
            for (MultiRegression fit : List.of(houseFit(HOUR), steadyFit(HOUR), nightFit(HOUR))) {
                assertEquals(HOUR, fit.horizon());
                assertEquals(0.0, fit.weight());
            }
            assertEquals(DEFAULT_HORIZON_SECONDS, houseFit(DEFAULT_HORIZON_SECONDS).horizon());
        }

        /**
         * Ten identical samples cannot tell the terms apart, so the priors
         * decide every term they cover and the constant takes what is left:
         * the house fit has been told the default priors.
         */
        @Test
        void theHouseFitIsToldThePriors() {
            MultiRegression fit = houseFit(0.0);
            double[] x = regressors(21.0, 0.0, 3.0, 0.0, 0.0, 5.0, null);
            for (int i = 0; i < 10; i++) {
                fit.add(x, 0.1, 1.0);
            }
            double[] beta = fit.coefficients();
            assertEquals(DEFAULT_PRIORS.get(LOSS).expected(), beta[LOSS], 1e-4);
            assertEquals(DEFAULT_PRIORS.get(WIND).expected(), beta[WIND], 1e-4);
            assertEquals(0.0, beta[WIND_COS], 1e-4);
            assertEquals(0.0, beta[WIND_SIN], 1e-4);
            assertEquals(DEFAULT_PRIORS.get(RADIATORS).expected(), beta[RADIATORS], 1e-4);
            assertEquals(0.1, fit.predict(x), 1e-6);
        }

        /** The loss cannot fall under its floor, nor the balance fits' own heat under nought. */
        @Test
        void theFitsHoldTheirFloors() {
            MultiRegression house = houseFit(0.0);
            for (int outdoor = -10; outdoor <= 10; outdoor++) {
                // A house that warms with the difference: unphysical, and floored.
                house.add(regressors(21.0, outdoor, null, null, 0.0, 0.0, null), 0.1 + 0.03 * (21.0 - outdoor), 1.0);
            }
            assertEquals(LOSS_FLOOR, house.coefficients()[LOSS]);

            MultiRegression steady = steadyFit(0.0);
            MultiRegression night = nightFit(0.0);
            for (int heat = 10; heat <= 40; heat += 3) {
                steady.add(steadyRegressors(0.0, heat, 0.0, new double[3]), -1.0 + 0.5 * heat, 1.0);
                night.add(nightRegressors(heat, 0.0, new double[3]), -1.0 + 0.4 * heat, 1.0);
            }
            assertEquals(0.0, steady.coefficients()[STEADY_GAINS]);
            assertEquals(0.0, night.coefficients()[NIGHT_GAINS]);
        }
    }

    /** weatherAt: the forecast nearest this many hours out. */
    @Nested
    class TheWeatherAhead {
        final Conditions first = new Conditions(1.0);
        final Conditions second = new Conditions(2.0);
        final Conditions third = new Conditions(3.0);
        final List<Forecast> weather = List.of(new Forecast(1.0, first), new Forecast(3.0, second), new Forecast(6.0, third));

        @Test
        void beforeTheFirstSlotItIsTheFirst() {
            assertEquals(first, weatherAt(weather, 0.0));
            assertEquals(first, weatherAt(weather, 0.99));
        }

        /** Between two slots it is the last one not after the hour: a slot's hour is when it begins. */
        @Test
        void betweenSlotsItIsTheLastNotAfter() {
            assertEquals(first, weatherAt(weather, 1.0));
            assertEquals(first, weatherAt(weather, 2.5));
            assertEquals(second, weatherAt(weather, 3.0));
            assertEquals(second, weatherAt(weather, 5.9));
        }

        /** Past the end it holds the last rather than extrapolating weather nobody predicted. */
        @Test
        void pastTheEndItHoldsTheLast() {
            assertEquals(third, weatherAt(weather, 6.0));
            assertEquals(third, weatherAt(weather, 100.0));
        }

        @Test
        void oneSlotIsTheWeatherWhateverTheHour() {
            List<Forecast> one = List.of(new Forecast(2.0, second));
            assertEquals(second, weatherAt(one, 0.0));
            assertEquals(second, weatherAt(one, 50.0));
        }
    }

    /** Conditions: the weather at one moment, null where Python has None. */
    @Nested
    class TheConditions {
        @Test
        void outdoorAloneLeavesTheRestUnknownAndTheSunDown() {
            Conditions c = new Conditions(-5.0);
            assertEquals(-5.0, c.outdoor());
            assertNull(c.wind());
            assertNull(c.bearing());
            assertEquals(0.0, c.sun());
            assertNull(c.hour());
        }

        @Test
        void theShortConstructorsFillInTheRest() {
            assertEquals(new Conditions(-5.0, 3.0, 90.0, 0.0, null), new Conditions(-5.0, 3.0, 90.0));
            assertEquals(new Conditions(-5.0, 3.0, 90.0, 0.4, null), new Conditions(-5.0, 3.0, 90.0, 0.4));
            assertEquals(new Conditions(-5.0, null, null, 0.0, null), new Conditions(-5.0, null, null));
        }

        @Test
        void withHourKeepsTheRest() {
            Conditions c = new Conditions(-5.0, 3.0, 90.0, 0.4);
            assertEquals(new Conditions(-5.0, 3.0, 90.0, 0.4, 18.5), c.withHour(18.5));
            assertEquals(c, c.withHour(18.5).withHour(null));
            assertNull(c.hour());
        }

        @Test
        void theOtherRecordsCarryTheirFields() {
            Forecast slot = new Forecast(2.0, new Conditions(-5.0));
            assertEquals(2.0, slot.hours());
            assertEquals(new Conditions(-5.0), slot.conditions());
            Balance balance = new Balance(12.5, 0.4);
            assertEquals(12.5, balance.outdoor());
            assertEquals(0.4, balance.error());
            Outlook outlook = new Outlook(3.5, false, 0.02, true, 1.25);
            assertEquals(3.5, outlook.hours());
            assertFalse(outlook.holds());
            assertEquals(0.02, outlook.uncertainty());
            assertTrue(outlook.known());
            assertEquals(1.25, outlook.turnsAt());
            assertEquals(new Outlook(null, false, null, false, null), new Outlook(null, false, null, false, null));
        }
    }

    /** The answer file from thermal.py: its functions on Python's inputs, every number within a billionth. */
    @Nested
    @Tag("python")
    class SameAsPython {
        /** kind;args;expected, the args and the expected comma-separated with empty for None. */
        @ParameterizedTest
        @CsvFileSource(resources = "/thermal.csv", delimiter = ';', numLinesToSkip = 1, maxCharsPerColumn = 1_000_000)
        void sameFunction(String kind, String args, String expected) {
            String[] a = fields(args);
            String what = kind + "(" + args + ")";
            switch (kind) {
                case "solar_index" -> assertClose(number(expected), solarIndex(number(a[0]), number(a[1])), CLOSE, what);
                case "regressors" -> assertCloseAll(expected, regressors(number(a[0]), number(a[1]), number(a[2]),
                        number(a[3]), number(a[4]), number(a[5]), number(a[6])), CLOSE, what);
                case "priors" -> {
                    // index,expected,strength for each of the five priors, sorted by index
                    Map<Integer, Prior> priors = housePriors(number(a[0]), number(a[1]), number(a[2]));
                    String[] e = fields(expected);
                    assertEquals(15, e.length, what + ": how many numbers");
                    assertEquals(5, priors.size(), what + ": how many priors");
                    for (int i = 0; i < e.length; i += 3) {
                        int index = number(e[i]).intValue();
                        Prior prior = priors.get(index);
                        assertNotNull(prior, what + " [" + index + "]");
                        assertClose(number(e[i + 1]), prior.expected(), CLOSE, what + " [" + index + "] expected");
                        assertClose(number(e[i + 2]), prior.strength(), CLOSE, what + " [" + index + "] strength");
                    }
                }
                case "steady_regressors" -> assertCloseAll(expected, steadyRegressors(number(a[0]), number(a[1]),
                        number(a[2]), new double[] {number(a[3]), number(a[4]), number(a[5])}), CLOSE, what);
                case "night_regressors" -> assertCloseAll(expected, nightRegressors(number(a[0]), number(a[1]),
                        new double[] {number(a[2]), number(a[3]), number(a[4])}), CLOSE, what);
                case "radiator_excess" -> assertClose(number(expected),
                        radiatorExcess(number(a[0]), number(a[1]), number(a[2])), CLOSE, what);
                default -> fail("unknown kind " + kind);
            }
        }
    }
}
