package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static se.sodalen.heating.house.Facit.array;
import static se.sodalen.heating.house.Facit.assertClose;
import static se.sodalen.heating.house.Facit.assertCloseAll;
import static se.sodalen.heating.house.Facit.bool;
import static se.sodalen.heating.house.Facit.entries;
import static se.sodalen.heating.house.Facit.fields;
import static se.sodalen.heating.house.Facit.number;
import static se.sodalen.heating.house.Facit.ops;
import static se.sodalen.heating.house.Teaching.autumn;
import static se.sodalen.heating.house.Teaching.flat;
import static se.sodalen.heating.house.Teaching.nightsOf;
import static se.sodalen.heating.house.Teaching.regulated;
import static se.sodalen.heating.house.Teaching.regulatedNight;
import static se.sodalen.heating.house.Teaching.teach;
import static se.sodalen.heating.house.Teaching.warmingAt;
import static se.sodalen.heating.house.Thermal.DEFAULT_HORIZON_SECONDS;
import static se.sodalen.heating.house.Thermal.DEFAULT_PRIORS;
import static se.sodalen.heating.house.Thermal.LOSS;
import static se.sodalen.heating.house.Thermal.NIGHT_MIN_NIGHTS;
import static se.sodalen.heating.house.Thermal.NIGHT_REGRESSORS;
import static se.sodalen.heating.house.Thermal.RADIATORS;
import static se.sodalen.heating.house.Thermal.REGRESSORS;
import static se.sodalen.heating.house.Thermal.STEADY_REGRESSORS;
import static se.sodalen.heating.house.Thermal.nightRegressors;
import static se.sodalen.heating.house.Thermal.steadyRegressors;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.house.Teaching.Nights;
import se.sodalen.heating.house.Teaching.Taught;

/**
 * The house model: can it learn a building, and does it know when it
 * cannot? What this house does when nobody is heating it - how fast it
 * cools, what the wind, the sun and the household add - fitted from its own
 * temperatures, and from that how long a setback holds and where the
 * heating curve starts.
 *
 * <p>Python: HouseModel in thermal.py and tests/test_thermal.py, taught by
 * the simulated house in tests/weatherhouse.py. as_dict and from_dict come
 * with the stored state in step 11.
 */
class HouseModelTest {

    static final double CLOSE = 1e-9;
    static final double HOUR = 3600.0;
    static final int DAY = 24;

    @Nested
    class LearningAHouse {
        /** The model's drift, against the house's own arithmetic. */
        @Test
        void itLearnsHowFastTheHouseCools() {
            WeatherHouse house = new WeatherHouse();
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::varied,
                    h -> h % 24.0 < 14.0 ? 4.0 : 0.0);
            for (double[] at : new double[][] {{-10.0, 0.0, 0.0}, {0.0, 5.0, 180.0}, {-15.0, 2.0, 90.0}}) {
                double outdoor = at[0];
                double wind = at[1];
                double bearing = at[2];
                double truth = house.internal - house.lossCoefficient(wind, bearing) * (21.0 - outdoor);
                Double got = model.drift(21.0, new Conditions(outdoor, wind, bearing, 0.0));
                assertNotNull(got);
                assertEquals(truth, got, 0.06);
            }
        }

        /** The drift at a wind of ten over the drift in still air, at -5. */
        double windEffect(WeatherHouse house) {
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::varied);
            double still = model.drift(21.0, new Conditions(-5.0, 0.0, 0.0));
            double blowing = model.drift(21.0, new Conditions(-5.0, 10.0, 0.0));
            return blowing - still;
        }

        /**
         * By how much, against the house's own figures - the fit is told a
         * wind prior, so a direction alone would be the prior talking. A
         * house that loses nothing to wind has to come out near nothing.
         */
        @Test
        void itLearnsThatWindCostsHeat() {
            WeatherHouse house = new WeatherHouse();
            double truth = -(house.gWind + house.gNorth) * 10.0 * (21.0 - -5.0);
            assertEquals(truth, windEffect(house), 0.15);
            WeatherHouse sheltered = new WeatherHouse();
            sheltered.gWind = 0.0;
            sheltered.gNorth = 0.0;
            assertEquals(0.0, windEffect(sheltered), 0.1);
        }

        /**
         * This house loses more to a northerly than a southerly, and the two
         * cos/sin terms are what let the fit say so without ever having been
         * given a sector to fill in.
         */
        @Test
        void itLearnsThatTheDirectionMatters() {
            WeatherHouse house = new WeatherHouse();
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::varied);
            double north = model.drift(21.0, new Conditions(-5.0, 8.0, 0.0));
            double south = model.drift(21.0, new Conditions(-5.0, 8.0, 180.0));
            double truth = -2.0 * house.gNorth * 8.0 * (21.0 - -5.0);
            assertEquals(truth, north - south, 0.15);
        }

        @Test
        void itLearnsThatTheSunPutsHeatBack() {
            HouseModel model = teach(new HouseModel(), new WeatherHouse(), DAY * 20, Teaching::varied);
            double dark = model.drift(21.0, new Conditions(-5.0, 2.0, 0.0, 0.0));
            double bright = model.drift(21.0, new Conditions(-5.0, 2.0, 0.0, 1.0));
            assertTrue(bright - dark > 0.5, "bright - dark " + (bright - dark));
        }
    }

    @Nested
    class HowLongItHolds {
        Taught taught() {
            WeatherHouse house = new WeatherHouse();
            return new Taught(teach(new HouseModel(), house, DAY * 20, Teaching::varied), house);
        }

        @Test
        void itGetsTheHoldingTimeAboutRight() {
            Taught taught = taught();
            taught.house().indoor = 22.0;
            Double truth = taught.house().holdHours(21.0, -5.0, 0.0, 0.0, 0.0, 0.25, null);
            Outlook out = taught.model().outlook(22.0, 21.0, flat(-5.0), 0.0);
            assertTrue(out.known());
            assertNotNull(out.hours());
            assertEquals(truth, out.hours(), 1.0);
        }

        @Test
        void windShortensIt() {
            HouseModel model = taught().model();
            Outlook calm = model.outlook(22.0, 21.0, flat(-5.0, 0.0, 0.0, 0.0, 24.0), 0.0);
            Outlook gale = model.outlook(22.0, 21.0, flat(-5.0, 12.0, 0.0, 0.0, 24.0), 0.0);
            assertTrue(gale.hours() < calm.hours(), gale.hours() + " >= " + calm.hours());
        }

        @Test
        void aMildSunnyDayCanHoldRightThrough() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(22.0, 21.0, flat(12.0, 1.0, 0.0, 0.7, 12.0), 0.0);
            assertTrue(out.holds());
        }

        /** Same weather, same house - asking for more confidence must never produce a longer answer. */
        @Test
        void theMarginMakesItMoreCareful() {
            HouseModel model = teach(new HouseModel(), new WeatherHouse(), DAY * 20, Teaching::varied, 7L, 0.15);
            Outlook bold = model.outlook(22.0, 21.0, flat(-5.0), 0.0);
            Outlook careful = model.outlook(22.0, 21.0, flat(-5.0), 3.0);
            // Strictly: a margin that changed nothing would pass "never longer".
            assertTrue(careful.hours() < bold.hours(), careful.hours() + " >= " + bold.hours());
        }

        /**
         * Fredrik 2026-10-10: the setback is to work against the weather to
         * come - how long before the fine weather the heat can go off. The
         * walk notes when the house stops cooling on its own.
         */
        @Test
        void whereTheWeatherTurnsItSaysWhen() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(22.0, 20.0, warmingAt(2), 0.0);
            assertNotNull(out.turnsAt());
            assertEquals(2.0, out.turnsAt(), 0.5);
            assertTrue(out.holds(), "and from there it holds");
        }

        @Test
        void weatherThatDoesNotTurnHasNoTurn() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(22.0, 20.0, flat(-5.0, 0.0, 0.0, 0.0, 12.0), 0.0);
            assertNull(out.turnsAt());
        }

        /** The house would have to be heated before the fine weather comes. */
        @Test
        void aTurnAfterTheFloorDoesNotCount() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(21.2, 21.0, warmingAt(8), 0.0);
            assertFalse(out.holds());
            assertNull(out.turnsAt());
        }

        @Test
        void fineWeatherAlreadyIsATurnAtOnce() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(22.0, 21.0, flat(16.0, 1.0, 0.0, 0.7, 12.0), 0.0);
            assertTrue(out.holds());
            assertEquals(0.0, out.turnsAt());
        }

        /**
         * Mild sun for an hour, then cold again until the floor: the heat
         * was still not needed until the spell, so the turn is said.
         */
        @Test
        void aShortFineSpellCountsEvenIfItCoolsAgain() {
            HouseModel model = taught().model();
            List<Forecast> weather = new ArrayList<>();
            for (int h = 0; h < 13; h++) {
                boolean fine = 1 <= h && h < 3;
                weather.add(new Forecast(h, new Conditions(fine ? 16.0 : 8.0, 1.0, 0.0, fine ? 0.7 : 0.0)));
            }
            Outlook out = model.outlook(22.0, 20.0, weather, 0.0);
            assertFalse(out.holds());
            assertEquals(1.0, out.turnsAt(), 0.3);
        }

        @Test
        void itWillNotAnswerBeforeItHasLearned() {
            Outlook out = new HouseModel().outlook(22.0, 21.0, flat(-5.0), 1.0);
            assertFalse(out.known());
            assertNull(out.hours());
        }

        @Test
        void noForecastIsNotAnAnswerEither() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(22.0, 21.0, List.of(), 1.0);
            assertFalse(out.known());
        }

        /** From the code: a house already under the floor has no hold at all, not a negative one. */
        @Test
        void startingUnderTheFloorIsNoHoldAtAll() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(20.0, 21.0, flat(-5.0), 0.0);
            assertTrue(out.known());
            assertFalse(out.holds());
            assertEquals(0.0, out.hours(), 0.0);
        }

        /**
         * From the code: the walk is a quarter hour at a time and the shunt's
         * own lead is ten minutes, so the crossing is interpolated within the
         * step; rounded up to it, every answer would look like at least a
         * quarter of an hour. A house two hundredths over the floor, cooling
         * more than a degree an hour, has a minute or so.
         */
        @Test
        void anAnswerUnderAQuarterOfAnHourIsNotRoundedUpToTheStep() {
            HouseModel model = taught().model();
            Outlook out = model.outlook(21.02, 21.0, flat(-5.0), 0.0);
            assertTrue(out.known());
            assertFalse(out.holds());
            assertTrue(out.hours() < 0.1, "hours " + out.hours());
        }

        /** From the code: the step is the caller's; a finer walk reaches about the same answer. */
        @Test
        void aFinerStepGivesAboutTheSameAnswer() {
            HouseModel model = taught().model();
            Outlook quarter = model.outlook(22.0, 21.0, flat(-5.0), 0.0);
            Outlook tenth = model.outlook(22.0, 21.0, flat(-5.0), 0.0, 0.1);
            assertFalse(quarter.holds());
            assertEquals(quarter.hours(), tenth.hours(), 0.3);
            assertEquals(quarter.uncertainty(), tenth.uncertainty(), 1e-6);
        }
    }

    /**
     * The property the whole design leans on: the model does not switch
     * between learning and knowing, it becomes less sure the further it is
     * asked to reach - so weather it has never seen declines to be acted on
     * without anybody changing a mode.
     */
    @Nested
    class ConfidenceIsLocal {
        HouseModel taughtOnMildStillWeather() {
            return teach(new HouseModel(), new WeatherHouse(), DAY * 20, Teaching::mild, 3L, 0.05);
        }

        @Test
        void itIsLeastSureAboutWeatherItHasNeverSeen() {
            HouseModel model = taughtOnMildStillWeather();
            double familiar = model.driftUncertainty(21.0, new Conditions(5.0, 1.0, 0.0));
            double unheardOf = model.driftUncertainty(21.0, new Conditions(-25.0, 18.0, 0.0));
            assertTrue(unheardOf > familiar, unheardOf + " <= " + familiar);
        }

        @Test
        void theOutlookItOffersCarriesThatDoubt() {
            HouseModel model = taughtOnMildStillWeather();
            Outlook known = model.outlook(22.0, 21.0, flat(5.0, 1.0, 0.0, 0.0, 12.0), 2.0);
            Outlook strange = model.outlook(22.0, 21.0, flat(-25.0, 18.0, 0.0, 0.0, 12.0), 2.0);
            assertTrue(known.uncertainty() > 0.0);
            assertTrue(strange.uncertainty() > known.uncertainty(), strange.uncertainty() + " <= " + known.uncertainty());
        }
    }

    @Nested
    class Sampling {
        /** A tenth of a degree an hour is entirely noise over half a minute. */
        @Test
        void itDoesNotMeasureASlopeOverThirtySeconds() {
            HouseModel model = new HouseModel();
            int took = 0;
            for (int i = 0; i < 10; i++) {
                if (model.observe(21.0, new Conditions(0.0), 0.0, 30.0, 600.0)) {
                    took++;
                }
            }
            assertEquals(0, took);
        }

        @Test
        void itTakesOneOnceTheIntervalHasPassed() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 19; i++) {
                model.observe(21.0, new Conditions(0.0), 0.0, 30.0, 600.0);
            }
            assertTrue(model.observe(21.1, new Conditions(0.0), 0.0, 30.0, 600.0));
        }

        /** Losing the sensor for an hour must not be read as the house having cooled across it. */
        @Test
        void aGapInTheReadingsDoesNotBecomeASlope() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 25; i++) {
                model.observe(21.0, new Conditions(0.0), 0.0, 30.0, 600.0);
            }
            model.observe(null, null, 0.0, 3600.0, 600.0);
            assertNull(model.lastIndoor());
            assertEquals(0.0, model.secondsSinceSample());
        }

        /** From the code: the gap also starts the interval, the day and the night under way again. */
        @Test
        void aGapClearsTheWindowTheDayAndTheNightUnderWay() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 3; i++) {
                model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0, null, true);
            }
            model.observe(21.0, new Conditions(0.0), 5.0, 300.0, 600.0, null, true);
            assertEquals(300.0, model.window()[0]);
            assertEquals(2, model.day().size());
            assertEquals(1800.0, model.dayTotals()[0]);
            assertEquals(2100.0, model.stretch()[0]);
            assertEquals(21.0, model.stretch()[6]);

            model.observe(null, null, 0.0, 600.0, 600.0);
            assertArrayEquals(new double[7], model.window());
            assertTrue(model.day().isEmpty());
            assertArrayEquals(new double[7], model.dayTotals());
            assertArrayEquals(new double[7], model.stretch());
            assertNull(model.lastIndoor());
            // The first reading after it is a start, not a slope.
            assertFalse(model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0, null, true));
            assertTrue(model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0, null, true));
        }

        /** From the code: a cycle with no time in it, or time backwards, adds nothing. */
        @Test
        void aCycleWithoutTimeCountsNothing() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 100; i++) {
                assertFalse(model.observe(21.0, new Conditions(0.0), 5.0, 0.0, 600.0));
                assertFalse(model.observe(21.0, new Conditions(0.0), 5.0, -60.0, 600.0));
            }
            assertEquals(0.0, model.secondsSinceSample());
            assertArrayEquals(new double[7], model.window(), 0.0);
            assertEquals(0.0, model.fit().weight());
        }

        /** From the code: no sample interval and no time is no slope either - the guard against dividing by nothing. */
        @Test
        void withoutASampleIntervalOrTimeNothingIsMeasured() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 3; i++) {
                assertFalse(model.observe(21.0, new Conditions(0.0), 0.0, 0.0, 0.0));
            }
            assertEquals(0.0, model.fit().weight());
            assertEquals(0.0, model.observedHours());
        }

        /** From the code: the hours are the accepted samples', and the first reading only starts the clock. */
        @Test
        void theObservedHoursAreTheSamplesTaken() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 10; i++) {
                model.observe(21.0, new Conditions(0.0), 0.0, 600.0, 600.0);
            }
            assertEquals(1.5, model.observedHours(), CLOSE);
            // Nine samples, each a hair faded by the ten minutes to the next.
            assertEquals(9.0, model.fit().weight(), 0.02);
        }

        /**
         * From the code: the day is the intervals of the last 24 hours, each
         * the window's seven sums and the indoor it began at. An interval
         * that pushes the day over 24 hours drops as many old ones as it
         * takes - an hour in one go drops six ten-minute intervals.
         */
        @Test
        void theDayKeepsTheLastTwentyFourHoursOfIntervals() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 150; i++) {
                model.observe(21.0 + 0.01 * i, new Conditions(0.0), 5.0, 600.0, 600.0);
            }
            assertEquals(144, model.day().size());
            assertEquals(DAY * HOUR, model.dayTotals()[0]);
            double[] first = model.day().getFirst();
            assertEquals(8, first.length);
            assertEquals(600.0, first[0]);
            assertEquals(3000.0, first[1]);
            // The first reading only started the clock, and its 1200 s interval has long been dropped:
            // the oldest kept is the 144th from the end, begun at the sixth reading.
            assertEquals(21.05, first[7], CLOSE);
            assertEquals(22.48, model.day().getLast()[7], CLOSE);

            model.observe(22.5, new Conditions(0.0), 5.0, 3600.0, 600.0);
            assertEquals(139, model.day().size());
            assertEquals(3600.0, model.day().getLast()[0]);
            assertEquals(DAY * HOUR, model.dayTotals()[0], CLOSE);
            double seconds = 0.0;
            for (double[] interval : model.day()) {
                seconds += interval[0];
            }
            assertEquals(model.dayTotals()[0], seconds, CLOSE);
        }

        /**
         * From the code: once there is a whole day, the steady fit takes the
         * day's means every three hours of samples, weighed by the three; a
         * sample counts towards the next three only after the day is whole.
         */
        @Test
        void theSteadyFitTakesTheDaysMeansEveryThreeHours() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 144; i++) {
                model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0);
            }
            // 143 samples, the first of them 1200 s: a whole day, and that sample counted towards the three hours.
            assertEquals(DAY * HOUR, model.dayTotals()[0]);
            assertEquals(600.0, model.sinceSteady());
            assertEquals(0.0, model.steady().weight());
            for (int i = 0; i < 16; i++) {
                model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0);
            }
            assertEquals(10200.0, model.sinceSteady());
            assertEquals(0.0, model.steady().weight());
            model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0);
            assertEquals(0.0, model.sinceSteady());
            assertEquals(1.0, model.steady().weight());
            for (int i = 0; i < 18; i++) {
                model.observe(21.0, new Conditions(0.0), 5.0, 600.0, 600.0);
            }
            assertEquals(1.0 + Math.exp(-3.0 * HOUR / DEFAULT_HORIZON_SECONDS), model.steady().weight(), CLOSE);
        }

        /** From the code: the day's sums take the radiators' heat when it is known, else the delta. */
        @Test
        void theRadiatorsHeatReplacesTheDeltaInTheDaysSums() {
            HouseModel byDelta = new HouseModel();
            byDelta.observe(21.0, new Conditions(1.0, 2.0, 90.0, 0.3), 5.0, 300.0, 600.0);
            assertArrayEquals(new double[] {300.0, 1500.0, 90.0, 6000.0, 12000.0, 0.0, 12000.0},
                    byDelta.window(), 1e-9);

            HouseModel byWater = new HouseModel();
            byWater.observe(21.0, new Conditions(1.0, 2.0, 90.0, 0.3), 5.0, 300.0, 600.0, 12.0, null);
            assertEquals(3600.0, byWater.window()[1]);

            HouseModel cooling = new HouseModel();
            cooling.observe(21.0, new Conditions(1.0), -3.0, 300.0, 600.0);
            assertEquals(0.0, cooling.window()[1]);
        }
    }

    /**
     * A house gets warmth from the people in it, and they keep hours. Nobody
     * tells the model what those hours are: it is given the clock and two
     * terms, and the amplitude and the phase come out of the fit.
     */
    @Nested
    class TheHouseholdsOwnDay {
        Taught taught() {
            WeatherHouse house = new WeatherHouse();
            house.daily = 0.35;
            house.dailyPeak = 18.0;
            return new Taught(teach(new HouseModel(), house, DAY * 30, Teaching::noSun, 0.0), house);
        }

        Conditions conditions(double hour) {
            return new Conditions(-5.0, 3.0, 0.0, 0.0, hour);
        }

        @Test
        void itFindsThatTheHouseWarmsItselfInTheEvening() {
            Taught taught = taught();
            double evening = taught.model().drift(21.0, conditions(18.0));
            double smallHours = taught.model().drift(21.0, conditions(6.0));
            assertTrue(evening > smallHours);
            // Peak to trough is twice the amplitude: a number the house knows and the model was never given.
            assertEquals(2.0 * taught.house().daily, evening - smallHours, 0.06);
        }

        @Test
        void itFindsTheHourItPeaks() {
            Taught taught = taught();
            double peak = 0.0;
            double most = Double.NEGATIVE_INFINITY;
            for (int h = 0; h < 48; h++) {
                double drift = taught.model().drift(21.0, conditions(h / 2.0));
                if (drift > most) {
                    most = drift;
                    peak = h / 2.0;
                }
            }
            // Circular distance, so 23:30 against 00:00 is half an hour.
            double off = Math.abs(peak - taught.house().dailyPeak) % 24.0;
            assertTrue(Math.min(off, 24.0 - off) < 1.5, "peak at " + peak);
        }

        /** A house whose integration never reports an hour must still get a usable model. */
        @Test
        void withoutAClockItSimplyDoesNotUseTheTerms() {
            WeatherHouse house = new WeatherHouse();
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::noSun);
            double got = model.drift(21.0, new Conditions(-10.0, 0.0, 0.0));
            double truth = house.internal - house.lossCoefficient(0.0, 0.0) * (21.0 - -10.0);
            assertEquals(truth, got, 0.06);
        }
    }

    /**
     * The reason the daily terms had to go in at all: a setback that starts
     * at eight in the evening runs into the night, and the household's
     * warmth drains away underneath it. Same weather, same house, same
     * indoor temperature - a different answer, and it has to be the clock
     * that makes the difference.
     */
    @Nested
    class ASetbackWalksThroughTheClock {
        Taught taught() {
            WeatherHouse house = new WeatherHouse();
            house.daily = 0.35;
            house.dailyPeak = 18.0;
            return new Taught(teach(new HouseModel(), house, DAY * 30, Teaching::noSun, 0.0), house);
        }

        /** A flat forecast with a clock running through it. */
        List<Forecast> weather(double startHour, double outdoor) {
            List<Forecast> weather = new ArrayList<>();
            for (int h = 0; h <= 24; h++) {
                weather.add(new Forecast(h, new Conditions(outdoor, 2.0, 0.0, 0.0, (startHour + h) % 24.0)));
            }
            return weather;
        }

        /**
         * Mild weather, started at the household's own peak: right now the
         * house is gaining heat on its own, and a model that held the hour
         * still would answer "it holds, indefinitely". It does not hold: the
         * evening drains away under the setback and the house reaches the
         * floor in the small hours - roughly when the simulated house does.
         */
        @Test
        void aSetbackStartedAtThePeakDoesNotKeepThePeak() {
            Taught taught = taught();
            double instant = taught.model().drift(21.5, new Conditions(17.0, 2.0, 0.0, 0.0, 18.0));
            assertTrue(instant > 0.0, "the house warms itself right now");

            Outlook out = taught.model().outlook(22.0, 21.0, weather(18.0, 17.0), 0.0);
            assertFalse(out.holds());
            taught.house().indoor = 22.0;
            Double truth = taught.house().holdHours(21.0, 17.0, 2.0, 0.0, 0.0, 0.25, 18.0);
            assertEquals(truth, out.hours(), 0.5);
        }

        /** The household peaks in the evening, so six in the morning is the loneliest the building gets. */
        @Test
        void theSmallHoursHoldShorterThanTheEvening() {
            HouseModel model = taught().model();
            Outlook evening = model.outlook(22.0, 21.0, weather(18.0, -2.0), 0.0);
            Outlook smallHours = model.outlook(22.0, 21.0, weather(4.0, -2.0), 0.0);
            assertTrue(evening.known() && smallHours.known());
            double eveningHours = evening.hours() == null ? 1e9 : evening.hours();
            assertTrue(smallHours.hours() < eveningHours, smallHours.hours() + " >= " + eveningHours);
        }

        @Test
        void andItAgreesWithTheHouse() {
            Taught taught = taught();
            for (double start : new double[] {8.0, 20.0}) {
                taught.house().indoor = 22.0;
                Double truth = taught.house().holdHours(21.0, -2.0, 2.0, 0.0, 0.0, 0.25, start);
                Outlook out = taught.model().outlook(22.0, 21.0, weather(start, -2.0), 0.0);
                String at = "kl " + (int) start;
                if (truth == null) {
                    assertTrue(out.holds(), at);
                    continue;
                }
                assertEquals(truth, out.hours(), 1.5, at);
            }
        }

        /**
         * From the code: a forecast's first slot is typically hours out, and
         * its hour is the hour it happens, not this one. The clock is backed
         * out to now, so a forecast that begins three hours out at one in
         * the morning is the same walk as one that begins now at ten at night.
         */
        @Test
        void theClockIsBackedOutToNowFromTheFirstSlot() {
            HouseModel model = taught().model();
            List<Forecast> fromNow = new ArrayList<>();
            List<Forecast> fromThreeHoursOut = new ArrayList<>();
            for (int h = 0; h <= 24; h++) {
                Forecast slot = new Forecast(h, new Conditions(-2.0, 2.0, 0.0, 0.0, (22.0 + h) % 24.0));
                fromNow.add(slot);
                if (h >= 3) {
                    fromThreeHoursOut.add(slot);
                }
            }
            assertEquals(1.0, fromThreeHoursOut.getFirst().conditions().hour());
            Outlook expected = model.outlook(22.0, 21.0, fromNow, 0.0);
            assertTrue(expected.known());
            assertEquals(expected, model.outlook(22.0, 21.0, fromThreeHoursOut, 0.0));
        }
    }

    /** Where the curve starts, from the house left to itself. */
    @Nested
    class TheBalance {
        @Test
        void itFindsWhereTheHouseHoldsByItself() {
            WeatherHouse house = new WeatherHouse();
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::varied);
            Balance balance = model.balance(21.0);
            assertNotNull(balance);
            double truth = 21.0 - house.internal / house.lossCoefficient(0.0, 0.0);
            assertEquals(truth, balance.outdoor(), 0.5);
            assertTrue(balance.error() < 1.0, "error " + balance.error());
        }

        @Test
        void itDoesNotAnswerBeforeItHasLearned() {
            assertNull(new HouseModel().balance(21.0));
        }

        /**
         * driftUncertainty carries the spread of one half-hour reading; the
         * balance is uncertain by far less once there are many.
         */
        @Test
        void itsErrorIsTheEstimatesNotASingleReadings() {
            HouseModel model = teach(new HouseModel(), new WeatherHouse(), DAY * 20, Teaching::varied, 2L, 0.15);
            Balance balance = model.balance(21.0);
            double reading = model.driftUncertainty(21.0, new Conditions(balance.outdoor(), 0.0, 0.0));
            double loss = model.fit().coefficients()[LOSS];
            assertTrue(balance.error() < 0.5 * reading / loss, balance.error() + " >= " + 0.5 * reading / loss);
        }
    }

    /**
     * From the house in equilibrium, over whole days (STEADY_REGRESSORS). A
     * house held at its target cannot show the drift's fit its pace - the
     * loss and the radiators sit on their priors - and its gains and its
     * sun, free, fit against them: Sodalen's read 15.9 +- 9.4.
     */
    @Nested
    class TheBalanceOfAHouseInService {
        double truth(WeatherHouse house) {
            return 21.0 - house.internal / house.g0;
        }

        @Test
        void itFindsItWithTheSunAndTheWind() {
            WeatherHouse house = new WeatherHouse();
            house.daily = 0.1;
            house.dailyPeak = 18.0;
            Balance balance = regulated(house, DAY * 21, Teaching::autumn).balance(21.0);
            assertNotNull(balance);
            assertEquals(truth(house), balance.outdoor(), 0.3);
            assertTrue(balance.error() < 0.5, "error " + balance.error());
        }

        @Test
        void aHouseWarmedByMoreOfItsOwnFindsItFurtherDown() {
            WeatherHouse warm = new WeatherHouse();
            warm.internal = 0.3;
            Balance balance = regulated(warm, DAY * 21, Teaching::autumn).balance(21.0);
            assertNotNull(balance);
            assertEquals(truth(warm), balance.outdoor(), 0.3);
        }

        @Test
        void itWaitsForAWholeDay() {
            HouseModel model = regulated(new WeatherHouse(), 23.0, Teaching::autumn);
            assertNull(model.balance(21.0));
            assertEquals(0.0, model.steady().weight());
        }

        @Test
        void aGapStartsTheDayAgain() {
            HouseModel model = regulated(new WeatherHouse(), 20.0, Teaching::autumn);
            assertFalse(model.day().isEmpty());
            model.observe(null, null, 0.0, 600.0, 600.0);
            assertTrue(model.day().isEmpty());
        }

        /** Its own heat cannot be negative: a sunny run fitted -0.02 K/h and put the balance 1.8 K over the target. */
        @Test
        void aHouseWithNoHeatOfItsOwnHoldsNothingOverTheTarget() {
            WeatherHouse house = new WeatherHouse();
            house.internal = 0.0;
            Balance balance = regulated(house, DAY * 21, Teaching::autumn).balance(21.0);
            assertNotNull(balance);
            assertTrue(balance.outdoor() <= 21.0 + 1e-9, "balance " + balance.outdoor());
            assertEquals(21.0, balance.outdoor(), 0.3);
        }

        /** As the night's: guesses right, fit not. */
        @Test
        void itsErrorIsTheDaysSpreadNotTheGuesses() {
            HouseModel model = new HouseModel();
            Random rng = new Random(14);
            for (int i = 0; i < 9; i++) {
                double sun = 0.3 * rng.nextDouble();
                double delta = 5.0 + 25.0 * rng.nextDouble();
                double slope = -0.05 + 0.1 * rng.nextDouble();
                double[] row = steadyRegressors(sun, delta, slope, new double[] {50.0 * rng.nextDouble(), 0.0, 0.0});
                model.steady().add(row, 10.0 + 0.5 * row[1] + 9.0 * row[2] + 0.5 * rng.nextGaussian(), 1.0);
            }
            for (double delta : new double[] {12.0, 22.0}) {
                double[] row = steadyRegressors(0.1, delta, 0.0, new double[] {20.0, 0.0, 0.0});
                model.steady().add(row, model.steady().predict(row), 1.0);
            }
            Balance balance = model.balance(21.0);
            assertNotNull(balance);
            assertTrue(balance.error() > 0.1, "error " + balance.error());
        }
    }

    /**
     * HouseModel.nightBalance: the house over its nights, 22 to 6 - when the
     * heating runs, and the household gives less than its day.
     */
    @Nested
    class TheNight {
        double truth(WeatherHouse house, List<Double> nights) {
            double household = 0.0;
            for (double hour : nights) {
                household += house.household(hour);
            }
            double gains = house.internal + household / nights.size();
            return 21.0 - gains / house.g0;
        }

        WeatherHouse house(double internal, double daily) {
            WeatherHouse house = new WeatherHouse();
            house.internal = internal;
            house.daily = daily;
            house.dailyPeak = 18.0;
            return house;
        }

        @Test
        void aHouseWarmInTheEveningHoldsLessAtNight() {
            WeatherHouse house = house(0.3, 0.35);
            Nights taught = regulatedNight(house, DAY * 28, Teaching::autumn);
            Balance night = taught.model().nightBalance(21.0);
            assertNotNull(night);
            assertEquals(truth(house, taught.hours()), night.outdoor(), 0.3);
            assertTrue(night.error() < 0.5, "error " + night.error());
            assertTrue(night.outdoor() > taught.model().balance(21.0).outdoor() + 2.0,
                    night.outdoor() + " against the day's " + taught.model().balance(21.0).outdoor());
        }

        @Test
        void withoutARhythmTheNightIsTheDay() {
            WeatherHouse house = new WeatherHouse();
            Nights taught = regulatedNight(house, DAY * 21, Teaching::autumn);
            Balance night = taught.model().nightBalance(21.0);
            assertNotNull(night);
            assertEquals(truth(house, taught.hours()), night.outdoor(), 0.3);
            assertEquals(taught.model().balance(21.0).outdoor(), night.outdoor(), 0.3);
        }

        /**
         * Ten nights of the same weather: the radiators give the same each
         * night, and the nights alone cannot tell their heat from the
         * house's own - the day's ratios decide it.
         */
        @Test
        void nightsAlikeTakeTheRadiatorsFromTheDay() {
            WeatherHouse house = house(0.3, 0.35);
            Teaching.Weather stillNights = hour -> {
                double[] w = autumn(hour);
                double clock = hour % 24.0;
                return new double[] {7.0 < clock && clock < 21.0 ? w[0] : 2.0, 0.0, 0.0, w[3]};
            };
            Nights taught = regulatedNight(house, DAY * 10, stillNights);
            Balance night = taught.model().nightBalance(21.0);
            assertNotNull(night);
            assertEquals(truth(house, taught.hours()), night.outdoor(), 0.5);
        }

        /**
         * A house aired at night gives less than nothing of its own: the
         * night's balance is held at the inside temperature, not above it.
         */
        @Test
        void aNightWhoseOwnHeatCameOutBelowNoneIsHeldAtNone() {
            WeatherHouse house = house(0.1, 0.35);
            Nights taught = regulatedNight(house, DAY * 21, Teaching::autumn);
            assertTrue(truth(house, taught.hours()) > 21.0);
            Balance night = taught.model().nightBalance(21.0);
            assertNotNull(night);
            assertEquals(21.0, night.outdoor(), 1e-6);
        }

        /**
         * Nights off any plane, and the last two exactly where the fit of
         * the first eight put them: the guesses were right, the fit is not.
         */
        @Test
        void itsErrorIsTheNightsSpreadNotTheGuesses() {
            HouseModel model = new HouseModel();
            Random rng = new Random(13);
            for (int i = 0; i < 8; i++) {
                double heat = 10.0 + 30.0 * rng.nextDouble();
                double slope = -0.05 + 0.1 * rng.nextDouble();
                double[] row = nightRegressors(heat, slope, new double[] {50.0 * rng.nextDouble(), 0.0, 0.0});
                model.night().add(row, 15.0 + 0.4 * row[1] + 0.5 * rng.nextGaussian(), 1.0);
            }
            for (double heat : new double[] {20.0, 30.0}) {
                double[] row = nightRegressors(heat, 0.0, new double[] {20.0, 0.0, 0.0});
                model.night().add(row, model.night().predict(row), 1.0);
            }
            assertTrue(model.night().weight() >= NIGHT_MIN_NIGHTS - 1e-9);
            Balance night = model.nightBalance(21.0);
            assertNotNull(night);
            assertTrue(night.error() > 0.1, "error " + night.error());
        }

        /** Thirty-three cycles of ten minutes are not a night; thirty-six, six hours to the second, are. */
        @Test
        void aNightShorterThanSixHoursIsNotOne() {
            HouseModel model = nightsOf(new HouseModel(), 24, hour -> hour < 5.5);
            assertEquals(0.0, model.night().weight());
            model = nightsOf(new HouseModel(), 24, hour -> hour < 6.1);
            assertEquals(1.0, model.night().weight());
            model = nightsOf(new HouseModel(), 24, hour -> hour < 6.0);
            assertEquals(1.0, model.night().weight());
        }

        @Test
        void aNightLongerThanTwelveHoursIsCut() {
            HouseModel model = nightsOf(new HouseModel(), 30, hour -> true);
            // Two nights, the first faded by the twelve hours to the second.
            double faded = Math.exp(-12.0 / (21.0 * 24.0));
            assertEquals(1.0 + faded, model.night().weight(), 1e-9);
        }

        /** Seven nights give six terms one degree of freedom, and a spread from one is a guess: nine, three. */
        @Test
        void itWaitsForNineNights() {
            WeatherHouse house = house(0.3, 0.0);
            assertNull(regulatedNight(house, DAY * 8 + 12, Teaching::autumn).model().nightBalance(21.0));
            assertNotNull(regulatedNight(house(0.3, 0.0), DAY * 10 + 12, Teaching::autumn).model().nightBalance(21.0));
        }

        /** Readings a tenth of a degree apart, nine nights: the error is there from the first answer, and holds the truth. */
        @Test
        void itsErrorIsNoSurerThanTheNights() {
            WeatherHouse house = house(0.3, 0.35);
            Random rng = new Random(11);
            Nights taught = regulatedNight(house, DAY * 9 + 12, Teaching::autumn, () -> 0.1 * rng.nextGaussian());
            Balance night = taught.model().nightBalance(21.0);
            assertNotNull(night);
            assertTrue(night.error() > 0.05, "error " + night.error());
            assertTrue(Math.abs(night.outdoor() - truth(house, taught.hours())) < 3.0 * night.error(),
                    night.outdoor() + " against " + truth(house, taught.hours()) + " +- " + night.error());
        }

        @Test
        void notKnowingIsNotNight() {
            HouseModel model = nightsOf(new HouseModel(), 24, hour -> null);
            assertEquals(0.0, model.night().weight());
            assertNull(model.nightBalance(21.0));
        }

        @Test
        void aGapDropsTheNightUnderWay() {
            HouseModel model = nightsOf(new HouseModel(), 4, hour -> true);
            model.observe(null, null, 0.0, 600.0, 600.0);
            nightsOf(model, 4, hour -> true);
            nightsOf(model, 1, hour -> false);
            assertEquals(0.0, model.night().weight());
        }
    }

    /** What the model is told rather than finds out: its fits, their horizons and the physics. From the code. */
    @Nested
    class WhatItIsTold {
        @Test
        void aNewModelHasThreeEmptyFits() {
            HouseModel model = new HouseModel();
            assertEquals(REGRESSORS, model.fit().size());
            assertEquals(STEADY_REGRESSORS, model.steady().size());
            assertEquals(NIGHT_REGRESSORS, model.night().size());
            assertEquals(DEFAULT_HORIZON_SECONDS, model.fit().horizon());
            assertEquals(DEFAULT_HORIZON_SECONDS, model.steady().horizon());
            assertEquals(DEFAULT_HORIZON_SECONDS, model.night().horizon());
            assertEquals(0.0, model.fit().weight());
            assertEquals(0.0, model.steady().weight());
            assertEquals(0.0, model.night().weight());
            assertArrayEquals(new double[7], model.window());
            assertTrue(model.day().isEmpty());
            assertArrayEquals(new double[7], model.dayTotals());
            assertEquals(0.0, model.sinceSteady());
            assertArrayEquals(new double[7], model.stretch());
            assertNull(model.lastIndoor());
            assertEquals(0.0, model.secondsSinceSample());
            assertEquals(0.0, model.observedHours());
        }

        @Test
        void retuneSetsAllThreeHorizons() {
            HouseModel model = new HouseModel();
            model.retune(36.0 * HOUR);
            assertEquals(36.0 * HOUR, model.fit().horizon());
            assertEquals(36.0 * HOUR, model.steady().horizon());
            assertEquals(36.0 * HOUR, model.night().horizon());
        }

        /** Twelve identical samples cannot tell the radiators from the rest, so the prior decides - the design point's. */
        @Test
        void setDesignChangesTheRadiatorPrior() {
            HouseModel model = new HouseModel();
            for (int i = 0; i < 13; i++) {
                model.observe(21.0, new Conditions(0.0), 5.0, 1800.0, 1800.0);
            }
            assertEquals(12.0, model.fit().weight(), 0.1);
            double expected = DEFAULT_PRIORS.get(RADIATORS).expected();
            assertEquals(expected, model.fit().coefficients()[RADIATORS], 1e-3);
            model.setDesign(78.0, 10.0, 1800.0);
            assertEquals(2.0 * expected, model.fit().coefficients()[RADIATORS], 1e-3);
        }

        @Test
        void theLossIsNotOnTheFloorForAHouseThatCools() {
            assertFalse(new HouseModel().lossOnFloor());
            HouseModel model = teach(new HouseModel(), new WeatherHouse(), DAY * 20, Teaching::varied);
            assertFalse(model.lossOnFloor());
        }

        /** A house that loses next to nothing through its walls: the floor stopped the fit, not the data. */
        @Test
        void theLossSitsOnTheFloorForAHouseThatBarelyCools() {
            WeatherHouse house = new WeatherHouse();
            house.g0 = 0.0005;
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::varied);
            assertTrue(model.lossOnFloor(), "loss " + model.fit().coefficients()[LOSS]);
        }

        @Test
        void whetherItCoolsWhenLeftAloneIsUnknownUntilItHasLearned() {
            assertNull(new HouseModel().coolsWhenLeftAlone());
        }

        @Test
        void aTaughtHouseCoolsWhenLeftAloneAndOneThatWarmsItselfDoesNot() {
            HouseModel model = teach(new HouseModel(), new WeatherHouse(), DAY * 20, Teaching::varied);
            assertEquals(Boolean.TRUE, model.coolsWhenLeftAlone());

            WeatherHouse stove = new WeatherHouse();
            stove.internal = 2.0;
            stove.g0 = 0.005;
            HouseModel warming = teach(new HouseModel(), stove, DAY * 20, Teaching::varied);
            assertEquals(Boolean.FALSE, warming.coolsWhenLeftAlone());
        }

        /**
         * From the code: the wind is tried too, from every quarter. A house
         * that a north wind warms has not learned the building either, and
         * only the wind shows it: in still air it cools like any other.
         */
        @Test
        void aHouseWarmedByTheWindIsNotTrustedEither() {
            WeatherHouse house = new WeatherHouse();
            house.gNorth = -0.012;
            HouseModel model = teach(new HouseModel(), house, DAY * 20, Teaching::varied);
            assertTrue(model.drift(21.0, new Conditions(-5.0, 0.0, 0.0)) < 0.0, "still air cools it");
            assertEquals(Boolean.FALSE, model.coolsWhenLeftAlone());
        }
    }

    /**
     * The answer file from thermal.py: cycles and questions on a new model,
     * every answer checked as it comes and the model's state at the end.
     * Sums are within a billionth; what comes out of a solve a little looser.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        static final double SOLVED = 1e-6;

        /**
         * ops is "observe,indoor,outdoor,wind,bearing,sun,hour,delta,dt,sampleSeconds,radiators,night|drift,
         * indoor,outdoor,wind,bearing,sun,hour|balance,indoor|cools|floor|design,span,delta,seconds|retune,
         * seconds|outlook,indoor,floor,confidence,stepHours,weather|...", an observe with no indoor the gap,
         * and outputs one entry per op that answers, in order. The rest is the model afterwards.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/house_model.csv", delimiter = ';', numLinesToSkip = 1,
                maxCharsPerColumn = 1_000_000)
        void sameModel(String ops, String outputs, double fitWeight, double steadyWeight, double nightWeight,
                       double observedHours, String lastIndoor, double secondsSinceSample, double sinceSteady,
                       String window, int dayCount, String dayTotals, String stretch, String fitXy,
                       String steadyXy, String nightXy, String fitCoefficients, String steadyCoefficients,
                       String nightCoefficients) {
            HouseModel model = new HouseModel();
            String[] steps = ops(ops);
            String[] answers = entries(outputs);
            int answered = 0;
            for (int i = 0; i < steps.length; i++) {
                String[] f = fields(steps[i]);
                String what = "ops[" + i + "] " + f[0];
                switch (f[0]) {
                    case "observe" -> {
                        Double indoor = number(f[1]);
                        Conditions given = indoor == null ? null : Facit.conditions(f, 2);
                        boolean taken = model.observe(indoor, given, number(f[7]), number(f[8]), number(f[9]),
                                number(f[10]), bool(f[11]));
                        assertEquals(bool(answers[answered++]), taken, what);
                    }
                    case "drift" -> assertClose(number(answers[answered++]),
                            model.drift(number(f[1]), Facit.conditions(f, 2)), SOLVED, what);
                    case "unc" -> assertClose(number(answers[answered++]),
                            model.driftUncertainty(number(f[1]), Facit.conditions(f, 2)), SOLVED, what);
                    case "reach" -> assertClose(number(answers[answered++]),
                            model.reach(number(f[1]), Facit.conditions(f, 2)), SOLVED, what);
                    case "balance" -> assertBalance(answers[answered++], model.balance(number(f[1])), what);
                    case "night" -> assertBalance(answers[answered++], model.nightBalance(number(f[1])), what);
                    case "cools" -> assertEquals(bool(answers[answered++]), model.coolsWhenLeftAlone(), what);
                    case "floor" -> assertEquals(bool(answers[answered++]), model.lossOnFloor(), what);
                    case "design" -> model.setDesign(number(f[1]), number(f[2]), number(f[3]));
                    case "retune" -> model.retune(number(f[1]));
                    case "outlook" -> assertOutlook(answers[answered++],
                            model.outlook(number(f[1]), number(f[2]), Facit.weather(f[5]), number(f[3]),
                                    number(f[4])),
                            what);
                    default -> fail("unknown op " + what);
                }
            }
            assertEquals(answers.length, answered, "every answer used");

            assertClose(fitWeight, model.fit().weight(), CLOSE, "fitWeight");
            assertClose(steadyWeight, model.steady().weight(), CLOSE, "steadyWeight");
            assertClose(nightWeight, model.night().weight(), CLOSE, "nightWeight");
            assertClose(observedHours, model.observedHours(), CLOSE, "observedHours");
            assertClose(number(lastIndoor), model.lastIndoor(), CLOSE, "lastIndoor");
            assertClose(secondsSinceSample, model.secondsSinceSample(), CLOSE, "secondsSinceSample");
            assertClose(sinceSteady, model.sinceSteady(), CLOSE, "sinceSteady");
            assertCloseAll(window, model.window(), CLOSE, "window");
            assertEquals(dayCount, model.day().size(), "dayCount");
            assertCloseAll(dayTotals, model.dayTotals(), CLOSE, "dayTotals");
            assertCloseAll(stretch, model.stretch(), CLOSE, "stretch");
            assertCloseAll(fitXy, model.fit().xy(), CLOSE, "fitXy");
            assertCloseAll(steadyXy, model.steady().xy(), CLOSE, "steadyXy");
            assertCloseAll(nightXy, model.night().xy(), CLOSE, "nightXy");
            assertCloseAll(fitCoefficients, model.fit().coefficients(), SOLVED, "fitCoefficients");
            assertCloseAll(steadyCoefficients, model.steady().coefficients(), SOLVED, "steadyCoefficients");
            assertCloseAll(nightCoefficients, model.night().coefficients(), SOLVED, "nightCoefficients");
        }

        /** "outdoor,error", or empty when the model cannot say. */
        static void assertBalance(String expected, Balance actual, String what) {
            if (expected.isEmpty()) {
                assertNull(actual, what);
                return;
            }
            assertNotNull(actual, what);
            double[] e = array(expected);
            assertClose(e[0], actual.outdoor(), SOLVED, what + " outdoor");
            assertClose(e[1], actual.error(), SOLVED, what + " error");
        }

        /** "hours,holds,uncertainty,known,turnsAt": the numbers empty for None; turnsAt is a sum of steps. */
        static void assertOutlook(String expected, Outlook actual, String what) {
            String[] e = fields(expected);
            assertClose(number(e[0]), actual.hours(), SOLVED, what + " hours");
            assertEquals(bool(e[1]), actual.holds(), what + " holds");
            assertClose(number(e[2]), actual.uncertainty(), SOLVED, what + " uncertainty");
            assertEquals(bool(e[3]), actual.known(), what + " known");
            assertClose(number(e[4]), actual.turnsAt(), CLOSE, what + " turnsAt");
        }
    }
}
