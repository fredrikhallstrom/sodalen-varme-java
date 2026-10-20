package se.sodalen.heating.valve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * What share of boiler water each valve position lets through, learned per
 * 5 % band of travel from settled readings:
 *
 * <pre>
 *     flow = ret + share(p) * (boiler - ret)
 * </pre>
 *
 * <p>Python: valvemap.py and tests/test_valvemap.py. The tests there that
 * need the controller (the start after a stop, waiting for the water, the
 * feedforward, the loop's gain from the map) come with it; as_dict,
 * from_dict and summary come with the learned state in step 11.
 */
class ValveMapTest {

    /** Keep the valve still for a while, as the loop would: one reading every 30 s. */
    static Double hold(ValveMap valve, double position, double flow, double ret, double boiler, double seconds) {
        Double learned = null;
        for (int i = 0; i < (int) (seconds / 30.0); i++) {
            learned = valve.observe(position, flow, ret, boiler, 30.0);
        }
        return learned;
    }

    /** With the return at 25 and the boiler at 75: 50 K of lift. */
    static Double hold(ValveMap valve, double position, double share, double seconds) {
        return hold(valve, position, 25.0 + share * 50.0, 25.0, 75.0, seconds);
    }

    /** A map taught a valve whose share is proportional to travel: 1 % per percent. */
    static ValveMap linearValve(double from, double to, double seconds) {
        ValveMap valve = new ValveMap();
        for (double position = from; position <= to; position += 5.0) {
            hold(valve, position, 0.01 * position, seconds);
        }
        return valve;
    }

    static ValveMap linearValve() {
        return linearValve(10.0, 50.0, 3600.0);
    }

    /**
     * The valve as the map had it on 2026-10-13, by then with weeks of data:
     * 4 % boiler water at 15 %, then each band steeper than the last.
     */
    static ValveMap todaysValve() {
        double[] shares = {0.004, 0.022, 0.029, 0.037, 0.057, 0.088, 0.144, 0.204, 0.296, 0.347, 0.413};
        ValveMap valve = new ValveMap();
        for (int i = 0; i < shares.length; i++) {
            hold(valve, 5.0 * i, shares[i], 3600.0);
        }
        return valve;
    }

    /**
     * The valve as measured in the house by 2026-10-08: dead at the start,
     * 6 % boiler water at 20 %, and thin readings at 25-35 %.
     */
    static ValveMap measuredValve() {
        double[][] readings = {{0, 0.004, 7200}, {5, 0.007, 2400}, {10, 0.016, 3600}, {15, 0.032, 3600},
                {20, 0.062, 3600}, {25, 0.089, 600}, {30, 0.138, 600}, {35, 0.188, 900}};
        ValveMap valve = new ValveMap();
        for (double[] r : readings) {
            hold(valve, r[0], r[1], r[2] + 300.0);
        }
        return valve;
    }

    static List<Integer> learnedBands(ValveMap valve) {
        List<Integer> bands = new ArrayList<>();
        for (int band = 0; band < ValveMap.BANDS; band++) {
            if (valve.share(band) != null) {
                bands.add(band);
            }
        }
        return bands;
    }

    @Nested
    class TheBands {
        @Test
        void fiveFromNoughtToAHundred() {
            assertEquals(5.0, ValveMap.BAND);
            assertEquals(21, ValveMap.BANDS);
        }

        @Test
        void aReadingOnABandIsThatBand() {
            ValveMap valve = new ValveMap();
            hold(valve, 20.0, 0.2, 900.0);
            assertEquals(List.of(4), learnedBands(valve));
        }

        /** Rounded to the nearest band, not cut: 18 % is band 4, 7 % band 1. */
        @Test
        void aReadingGoesToTheNearestBand() {
            ValveMap valve = new ValveMap();
            hold(valve, 18.0, 0.2, 900.0);
            hold(valve, 7.0, 0.2, 900.0);
            assertEquals(List.of(1, 4), learnedBands(valve));
        }

        /**
         * Python's round() takes a half to the even neighbour: 12.5 % is
         * band 2 and 22.5 % band 4. Math.round would say 3 and 5.
         */
        @Test
        void halfwayGoesToTheEvenBand() {
            ValveMap valve = new ValveMap();
            hold(valve, 12.5, 0.2, 900.0);
            hold(valve, 22.5, 0.2, 900.0);
            assertEquals(List.of(2, 4), learnedBands(valve));
        }

        @Test
        void pastTheEndsIsTheEndBands() {
            ValveMap valve = new ValveMap();
            hold(valve, 103.0, 0.2, 900.0);
            hold(valve, -2.0, 0.2, 900.0);
            assertEquals(List.of(0, 20), learnedBands(valve));
        }

        @Test
        void anEmptyMapKnowsNothing() {
            ValveMap valve = new ValveMap();
            assertEquals(List.of(), learnedBands(valve));
            assertEquals(0.0, valve.weight(10));
            assertEquals(List.of(), valve.known());
        }
    }

    @Nested
    class Learning {
        /**
         * The supply lags the valve by the actuator's travel and the water's
         * way round, so a reading taken while it moves belongs to no position.
         */
        @Test
        void aMovingValveTeachesNothing() {
            ValveMap valve = new ValveMap();
            for (double position : new double[] {10.0, 12.0, 14.0, 16.0, 18.0, 20.0}) {
                assertNull(valve.observe(position, 30.0, 25.0, 75.0, 30.0));
            }
            assertEquals(List.of(), valve.known());
        }

        @Test
        void aSettledValveTeachesTheShareNotTheTemperature() {
            assertEquals(0.2, hold(new ValveMap(), 20.0, 35.0, 25.0, 75.0, 900.0), 1e-6);
        }

        /** The whole point: 20 % is 20 % whether the boiler is at 60 or 84. */
        @Test
        void theSamePositionReadsTheSameWhateverTheBoiler() {
            ValveMap valve = new ValveMap();
            Double cool = hold(valve, 20.0, 25.0 + 0.2 * 35.0, 25.0, 60.0, 900.0);
            Double hot = hold(valve, 20.0, 25.0 + 0.2 * 59.0, 25.0, 84.0, 900.0);
            assertEquals(cool, hot, 1e-6);
        }

        /** The first reading at a position starts the wait; 300 s later it counts. */
        @Test
        void itWaitsTheSettleTimeAndNotACycleLonger() {
            ValveMap valve = new ValveMap();
            assertNull(valve.observe(20.0, 35.0, 25.0, 75.0, 30.0));
            for (int i = 0; i < 9; i++) {
                assertNull(valve.observe(20.0, 35.0, 25.0, 75.0, 30.0));
            }
            assertFalse(valve.settled());
            assertEquals(0.2, valve.observe(20.0, 35.0, 25.0, 75.0, 30.0), 1e-12);
            assertTrue(valve.settled());
        }

        /** Half a percent is the valve standing still; more is a move. */
        @Test
        void halfAPercentIsStandingStill() {
            ValveMap valve = new ValveMap();
            hold(valve, 20.0, 0.2, 900.0);
            assertEquals(0.2, valve.observe(20.5, 35.0, 25.0, 75.0, 30.0), 1e-12);
            assertTrue(valve.settled());
            assertNull(valve.observe(21.1, 35.0, 25.0, 75.0, 30.0));
            assertFalse(valve.settled());
        }

        /** A creeping valve is measured from where it stopped, not from its last step. */
        @Test
        void creepingIsMeasuredFromWhereItStopped() {
            ValveMap valve = new ValveMap();
            hold(valve, 20.0, 0.2, 900.0);
            assertNotNull(valve.observe(20.4, 35.0, 25.0, 75.0, 30.0));
            assertNull(valve.observe(20.8, 35.0, 25.0, 75.0, 30.0));
            assertFalse(valve.settled());
        }

        @Test
        void noPositionStartsTheWaitAgain() {
            ValveMap valve = new ValveMap();
            hold(valve, 20.0, 0.2, 900.0);
            assertNull(valve.observe(null, 35.0, 25.0, 75.0, 30.0));
            assertFalse(valve.settled());
            assertNull(valve.observe(20.0, 35.0, 25.0, 75.0, 30.0));
        }

        @Test
        void noTimeStartsTheWaitAgain() {
            for (double dt : new double[] {0.0, -5.0}) {
                ValveMap valve = new ValveMap();
                hold(valve, 20.0, 0.2, 900.0);
                assertNull(valve.observe(20.0, 35.0, 25.0, 75.0, dt));
                assertFalse(valve.settled(), "dt " + dt);
            }
        }

        /** A reading that did not come is not the valve moving. */
        @Test
        void aMissingTemperatureDoesNotStartTheWaitAgain() {
            ValveMap valve = new ValveMap();
            hold(valve, 20.0, 0.2, 900.0);
            assertNull(valve.observe(20.0, null, 25.0, 75.0, 30.0));
            assertNull(valve.observe(20.0, 35.0, null, 75.0, 30.0));
            assertNull(valve.observe(20.0, 35.0, 25.0, null, 30.0));
            assertTrue(valve.settled());
            assertEquals(0.2, valve.observe(20.0, 35.0, 25.0, 75.0, 30.0), 1e-12);
        }

        /** A small difference over a small difference is noise. */
        @Test
        void aColdBoilerSaysNothing() {
            assertNull(hold(new ValveMap(), 20.0, 27.0, 25.0, 35.0, 900.0));
            assertNull(hold(new ValveMap(), 20.0, 35.0, 25.0, 20.0, 900.0));
        }

        @Test
        void fifteenDegreesOfLiftIsEnough() {
            assertEquals(0.2, hold(new ValveMap(), 20.0, 28.0, 25.0, 40.0, 900.0), 1e-12);
        }

        /** A supply above the boiler or below the return is a sensor's error, held in. */
        @Test
        void theShareIsHeldBetweenALittleUnderNoughtAndALittleOverAll() {
            assertEquals(1.1, hold(new ValveMap(), 20.0, 90.0, 25.0, 75.0, 900.0), 1e-12);
            assertEquals(-0.05, hold(new ValveMap(), 20.0, 20.0, 25.0, 75.0, 900.0), 1e-12);
        }

        /** 1500 settled seconds at 0.2 and then 1800 at 0.4. */
        @Test
        void aBandIsTheMeanOfItsReadingsByTime() {
            ValveMap valve = new ValveMap();
            hold(valve, 20.0, 0.2, 1800.0);
            hold(valve, 20.0, 0.4, 1800.0);
            assertEquals((1500 * 0.2 + 1800 * 0.4) / 3300, valve.share(4), 1e-4);
            assertEquals(3300.0, valve.weight(4), 5.0);
        }

        /**
         * Old readings fade over the horizon. With an hour instead of a month
         * the last half hour at 0.4 has more say than its time alone gives it.
         */
        @Test
        void oldReadingsFadeOverTheHorizon() {
            ValveMap valve = new ValveMap(3600.0, 300.0, 15.0, 1800.0);
            hold(valve, 20.0, 0.2, 7200.0);
            hold(valve, 20.0, 0.4, 1800.0);
            assertEquals(0.2864, valve.share(4), 1e-3);
        }
    }

    @Nested
    class Prediction {
        @Test
        void theKnownBandsInOrder() {
            List<ValvePoint> known = linearValve().known();
            assertEquals(9, known.size());
            assertEquals(10.0, known.get(0).position());
            assertEquals(0.1, known.get(0).share(), 1e-9);
            assertEquals(50.0, known.get(8).position());
            assertEquals(0.5, known.get(8).share(), 1e-9);
        }

        @Test
        void itReadsTheTableBackwards() {
            // 30 % lets through 30 %: 25 + 0.3 * 50 = 40 C.
            assertEquals(30.0, linearValve().positionFor(40.0, 25.0, 75.0), 1e-6);
        }

        @Test
        void aHotterBoilerNeedsLessValve() {
            ValveMap valve = linearValve();
            Double cool = valve.positionFor(40.0, 25.0, 65.0);
            Double hot = valve.positionFor(40.0, 25.0, 85.0);
            assertNotNull(cool);
            assertNotNull(hot);
            assertTrue(hot < cool);
        }

        @Test
        void theEdgesOfWhatItHasSeenAreStillSeen() {
            ValveMap valve = linearValve();
            assertEquals(10.0, valve.positionFor(30.0, 25.0, 75.0), 1e-6);
            assertEquals(50.0, valve.positionFor(50.0, 25.0, 75.0), 1e-6);
        }

        /** Between two bands with the same share it says the first. */
        @Test
        void aFlatStretchGivesWhereItStarts() {
            ValveMap valve = new ValveMap();
            hold(valve, 10.0, 0.1, 3600.0);
            hold(valve, 15.0, 0.08, 3600.0);
            hold(valve, 20.0, 0.2, 3600.0);
            assertEquals(10.0, valve.positionFor(30.0, 25.0, 75.0), 1e-9);
        }

        @Test
        void itDoesNotGuessOutsideWhatItHasSeen() {
            ValveMap valve = linearValve(10.0, 25.0, 3600.0);
            // Needs a share of 0.8 - far beyond the 25 % it has seen.
            assertNull(valve.positionFor(65.0, 25.0, 75.0));
            assertNull(valve.positionFor(26.0, 25.0, 75.0));
        }

        @Test
        void oneBandIsNotACurve() {
            assertNull(linearValve(20.0, 20.0, 3600.0).positionFor(35.0, 25.0, 75.0));
        }

        @Test
        void thinDataIsNotUsed() {
            assertEquals(List.of(), linearValve(10.0, 50.0, 600.0).known());
        }

        @Test
        void aNoisyBandCannotMakeMoreTravelGiveLess() {
            ValveMap valve = new ValveMap();
            for (double position = 10.0; position <= 50.0; position += 5.0) {
                hold(valve, position, position == 25.0 ? 0.05 : 0.01 * position, 3600.0);
            }
            List<ValvePoint> known = valve.known();
            for (int i = 1; i < known.size(); i++) {
                assertTrue(known.get(i).share() >= known.get(i - 1).share(), "at " + known.get(i).position());
            }
            assertEquals(0.2, known.get(3).share(), 1e-9);
        }

        @Test
        void aColdBoilerSaysNothing() {
            assertNull(linearValve().positionFor(30.0, 25.0, 35.0));
        }

        @Test
        void fifteenDegreesOfLiftIsEnough() {
            assertEquals(20.0, linearValve().positionFor(28.0, 25.0, 40.0), 1e-9);
        }

        @Test
        void withoutAReadingNothing() {
            ValveMap valve = linearValve();
            assertNull(valve.positionFor(null, 25.0, 75.0));
            assertNull(valve.positionFor(40.0, null, 75.0));
            assertNull(valve.positionFor(40.0, 25.0, null));
        }
    }

    /**
     * K of supply per percent of travel where the valve stands: the slope of
     * the share there, times what the boiler has over the return. On
     * 2026-10-13 a single learned gain gave the loop its highest Kp, right for
     * the valve's flat start and five to ten times too much once it opened
     * past 25 %, and the valve swung 0-60 % for most of an hour.
     */
    @Nested
    class TheGainItGives {
        @Test
        void theSlopeOfTheShareTimesTheLift() {
            // 0.01 of boiler water per percent, 50 K of lift.
            assertEquals(0.5, linearValve().gainAt(30.0, 50.0), 1e-6);
        }

        @Test
        void aHotterBoilerMakesEveryPercentWorthMore() {
            ValveMap valve = linearValve();
            assertTrue(valve.gainAt(30.0, 60.0) > valve.gainAt(30.0, 40.0));
        }

        @Test
        void itIsWeakWhereTheValveIsFlatAndStrongWhereItOpens() {
            ValveMap valve = todaysValve();
            assertTrue(valve.gainAt(10.0, 50.0) < 0.15);
            assertTrue(valve.gainAt(35.0, 50.0) > 0.6);
        }

        /** The loop will move the valve either way from here. */
        @Test
        void onABendTheSteeperSideCounts() {
            // 25-30 % takes 5.6 % of boiler water, 20-25 % only 3.1 %.
            assertEquals((0.144 - 0.088) / 5.0 * 50.0, todaysValve().gainAt(25.0, 50.0), 1e-3);
        }

        /** At 27 % the loop is a few percent from 30, where the valve steepens again. */
        @Test
        void andSoDoesTheBandTheNextFewPercentReach() {
            assertEquals((0.204 - 0.144) / 5.0 * 50.0, todaysValve().gainAt(27.0, 50.0), 1e-3);
        }

        /** On a band exactly, only the stretches either side: not the ones a whole band away. */
        @Test
        void onABandItLooksOneBandEitherWay() {
            // At 30: 25-30 and 30-35, of which 30-35 is the steeper. 35-40 is steeper still but too far.
            assertEquals((0.204 - 0.144) / 5.0 * 50.0, todaysValve().gainAt(30.0, 50.0), 1e-3);
        }

        @Test
        void nothingFromAMapThatHasNotSeenTheValveThere() {
            assertNull(linearValve(30.0, 50.0, 3600.0).gainAt(15.0, 50.0));
        }

        @Test
        void oneBandIsNotASlope() {
            assertNull(linearValve(20.0, 20.0, 3600.0).gainAt(20.0, 50.0));
        }

        @Test
        void fifteenDegreesOfLiftIsEnough() {
            assertEquals(0.15, linearValve().gainAt(30.0, 15.0), 1e-9);
        }

        @Test
        void aColdBoilerSaysNothing() {
            assertNull(linearValve().gainAt(30.0, 5.0));
            assertNull(linearValve().gainAt(30.0, null));
            assertNull(linearValve().gainAt(null, 50.0));
        }
    }

    /**
     * Fredrik 2026-10-08: fill the map with what it should be, and let it learn
     * from there. A mixing valve wide open lets all the boiler water through,
     * so the travel the map has not seen is filled on a line from its last
     * trusted band to 1.0 at 100 %, counted as half an hour of data; thin real
     * readings are weighed in by their time, and real data takes over.
     */
    @Nested
    class WhatItShouldBe {
        @Test
        void theBandsItKnowsAreLeftAlone() {
            ValveMap valve = measuredValve();
            Double[] share = new Double[5];
            double[] weight = new double[5];
            for (int band = 0; band < 5; band++) {
                share[band] = valve.share(band);
                weight[band] = valve.weight(band);
            }
            valve.fillPrior();
            for (int band = 0; band < 5; band++) {
                assertEquals(share[band], valve.share(band), "share " + band);
                assertEquals(weight[band], valve.weight(band), "weight " + band);
            }
        }

        /** Between two trusted bands the map reads across; a line would only put a guess there. */
        @Test
        void aThinBandBelowTheLastTrustedOneIsLeftToItsNeighbours() {
            ValveMap valve = new ValveMap();
            hold(valve, 0.0, 0.004, 2400.0);
            hold(valve, 5.0, 0.010, 600.0);
            hold(valve, 20.0, 0.062, 2400.0);
            Double share = valve.share(1);
            double weight = valve.weight(1);
            valve.fillPrior();
            assertEquals(share, valve.share(1));
            assertEquals(weight, valve.weight(1));
        }

        @Test
        void untouchedTravelIsFilledOnALineToAllTheBoilerWater() {
            ValveMap valve = measuredValve();
            valve.fillPrior();
            assertEquals(1.0, valve.share(20), 1e-7);
            assertEquals(0.062 + (1.0 - 0.062) * 30.0 / 80.0, valve.share(10), 5e-4);
            assertEquals(1800.0, valve.weight(10));
        }

        @Test
        void thinReadingsAreWeighedInByTheirTime() {
            ValveMap valve = measuredValve();
            valve.fillPrior();
            double line = 0.062 + (1.0 - 0.062) * 5.0 / 80.0;
            assertEquals((600 * 0.089 + 1200 * line) / 1800, valve.share(5), 5e-4);
            assertTrue(valve.share(5) < line);
        }

        @Test
        void everyBandThenCounts() {
            ValveMap valve = measuredValve();
            valve.fillPrior();
            assertEquals(21, valve.known().size());
        }

        @Test
        void aSecondFillChangesNothing() {
            ValveMap valve = measuredValve();
            valve.fillPrior();
            Double[] share = new Double[ValveMap.BANDS];
            double[] weight = new double[ValveMap.BANDS];
            for (int band = 0; band < ValveMap.BANDS; band++) {
                share[band] = valve.share(band);
                weight[band] = valve.weight(band);
            }
            valve.fillPrior();
            for (int band = 0; band < ValveMap.BANDS; band++) {
                assertEquals(share[band], valve.share(band), "share " + band);
                assertEquals(weight[band], valve.weight(band), "weight " + band);
            }
        }

        @Test
        void anEmptyMapStartsOnALineFromShutToOpen() {
            ValveMap valve = new ValveMap();
            valve.fillPrior();
            assertEquals(0.0, valve.share(0), 1e-12);
            assertEquals(0.5, valve.share(10), 1e-12);
            assertEquals(1.0, valve.share(20), 1e-12);
        }

        /** Trusted all the way to 100 %: nothing left to fill. */
        @Test
        void aMapThatKnowsItAllIsLeftAsItIs() {
            ValveMap valve = linearValve(0.0, 100.0, 3600.0);
            Double before = valve.share(20);
            valve.fillPrior();
            assertEquals(before, valve.share(20));
            assertEquals(1.0, valve.share(20), 1e-9);
        }

        @Test
        void anHourOfRealDataTakesTwoThirdsOfTheSay() {
            ValveMap valve = measuredValve();
            valve.fillPrior();
            hold(valve, 50.0, 0.30, 3600.0 + 300.0);
            double line = 0.062 + (1.0 - 0.062) * 30.0 / 80.0;
            assertEquals(line + (0.30 - line) * 2.0 / 3.0, valve.share(10), 0.005);
        }

        /**
         * 02:00 on 2026-10-08: setpoint 38.6, return 23.3, boiler 85.3. The
         * single gain said 98 %; the valve settled at about 33 % a few minutes
         * later.
         */
        @Test
        void lastNightsStartWouldHaveBeenAThirdOpen() {
            ValveMap valve = measuredValve();
            valve.fillPrior();
            Double position = valve.positionFor(38.6, 23.3, 85.3);
            assertTrue(position > 25.0 && position < 40.0, "at " + position);
        }
    }

    /** How far the map's choice was from where the PID settled, averaged over time. */
    @Nested
    class ShadowScore {
        @Test
        void theGapIsAveragedOverTime() {
            ValveMap valve = new ValveMap();
            valve.score(30.0, 20.0, 60.0);
            valve.score(30.0, 30.0, 60.0);
            assertEquals(5.0, valve.shadowError(), 1e-3);
            assertEquals(120.0, valve.shadowSeconds(), 1e-2);
        }

        @Test
        void theFirstGapIsTheScore() {
            ValveMap valve = new ValveMap();
            valve.score(20.0, 30.0, 60.0);
            assertEquals(10.0, valve.shadowError(), 1e-12);
        }

        /** With a minute's horizon an hour-old gap has faded to a third of its weight. */
        @Test
        void oldGapsFadeOverTheHorizon() {
            ValveMap valve = new ValveMap(60.0, 300.0, 15.0, 1800.0);
            valve.score(30.0, 20.0, 3600.0);
            valve.score(30.0, 30.0, 60.0);
            assertEquals(10.0 - 10.0 * 60.0 / (3600.0 * Math.exp(-1.0) + 60.0), valve.shadowError(), 1e-9);
        }

        @Test
        void nothingToCompareIsNotAPerfectScore() {
            ValveMap valve = new ValveMap();
            valve.score(null, 20.0, 60.0);
            valve.score(20.0, null, 60.0);
            assertNull(valve.shadowError());
            assertEquals(0.0, valve.shadowSeconds());
        }

        @Test
        void noTimeIsNoScore() {
            ValveMap valve = new ValveMap();
            valve.score(30.0, 20.0, 0.0);
            valve.score(30.0, 20.0, -1.0);
            assertNull(valve.shadowError());
        }
    }

    /** Cases worked out by the Python version (verktyg/facit.py). */
    @Nested
    @Tag("python")
    class SameAsPython {
        static final double CLOSE = 1e-9;

        /** "1.5,,2" to [1.5, null, 2.0]; an empty cell is one missing value. */
        static List<Double> numbers(String cell) {
            List<Double> values = new ArrayList<>();
            for (String part : (cell == null ? "" : cell).split(",", -1)) {
                values.add(part.isEmpty() ? null : Double.parseDouble(part));
            }
            return values;
        }

        /** As numbers, but an empty cell is an empty list. */
        static List<Double> list(String cell) {
            return cell == null || cell.isEmpty() ? List.of() : numbers(cell);
        }

        static List<List<Double>> groups(String cell) {
            List<List<Double>> groups = new ArrayList<>();
            if (cell != null && !cell.isEmpty()) {
                for (String group : cell.split("\\|", -1)) {
                    groups.add(numbers(group));
                }
            }
            return groups;
        }

        /** Each segment is one reading, given as many times in a row as it says. */
        static List<Double> replay(ValveMap valve, String segments) {
            List<Double> last = new ArrayList<>();
            for (List<Double> s : groups(segments)) {
                Double learned = null;
                for (int i = 0; i < s.get(5).intValue(); i++) {
                    learned = valve.observe(s.get(0), s.get(1), s.get(2), s.get(3), s.get(4));
                }
                last.add(learned);
            }
            return last;
        }

        static void assertSame(List<Double> expected, List<Double> actual, String what) {
            assertEquals(expected.size(), actual.size(), what + ": how many");
            for (int i = 0; i < expected.size(); i++) {
                if (expected.get(i) == null) {
                    assertNull(actual.get(i), what + "[" + i + "]");
                } else {
                    assertNotNull(actual.get(i), what + "[" + i + "]");
                    assertEquals(expected.get(i), actual.get(i), CLOSE, what + "[" + i + "]");
                }
            }
        }

        static void assertTable(String share, String weight, ValveMap valve) {
            List<Double> shares = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (int band = 0; band < ValveMap.BANDS; band++) {
                shares.add(valve.share(band));
                weights.add(valve.weight(band));
            }
            assertSame(numbers(share), shares, "share");
            assertSame(numbers(weight), weights, "weight");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/observe.csv", delimiter = ';', numLinesToSkip = 1)
        void sameLearning(double horizonSeconds, double settleSeconds, double minLift, double minBandSeconds,
                          String segments, String learned, boolean settled, String share, String weight) {
            ValveMap valve = new ValveMap(horizonSeconds, settleSeconds, minLift, minBandSeconds);
            assertSame(numbers(learned), replay(valve, segments), "observe, last of each segment");
            assertEquals(settled, valve.settled(), "settled");
            assertTable(share, weight, valve);
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/fill_prior.csv", delimiter = ';', numLinesToSkip = 1)
        void sameFill(double horizonSeconds, double settleSeconds, double minLift, double minBandSeconds,
                      String segments, String share, String weight) {
            ValveMap valve = new ValveMap(horizonSeconds, settleSeconds, minLift, minBandSeconds);
            replay(valve, segments);
            valve.fillPrior();
            assertTable(share, weight, valve);
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/predict.csv", delimiter = ';', numLinesToSkip = 1)
        void samePrediction(double horizonSeconds, double settleSeconds, double minLift, double minBandSeconds,
                            String segments, boolean filled, String knownPositions, String knownShares,
                            String positionQueries, String positions, String gainQueries, String gains) {
            ValveMap valve = new ValveMap(horizonSeconds, settleSeconds, minLift, minBandSeconds);
            replay(valve, segments);
            if (filled) {
                valve.fillPrior();
            }
            List<ValvePoint> known = valve.known();
            assertSame(list(knownPositions), known.stream().map(ValvePoint::position).toList(), "known position");
            assertSame(list(knownShares), known.stream().map(ValvePoint::share).toList(), "known share");

            List<Double> answers = new ArrayList<>();
            for (List<Double> q : groups(positionQueries)) {
                answers.add(valve.positionFor(q.get(0), q.get(1), q.get(2)));
            }
            assertSame(numbers(positions), answers, "positionFor");

            List<Double> gained = new ArrayList<>();
            for (List<Double> q : groups(gainQueries)) {
                gained.add(valve.gainAt(q.get(0), q.get(1)));
            }
            assertSame(numbers(gains), gained, "gainAt");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/score.csv", delimiter = ';', numLinesToSkip = 1)
        void sameScore(double horizonSeconds, double settleSeconds, double minLift, double minBandSeconds,
                       String scores, Double shadowError, double shadowSeconds) {
            ValveMap valve = new ValveMap(horizonSeconds, settleSeconds, minLift, minBandSeconds);
            for (List<Double> s : groups(scores)) {
                valve.score(s.get(0), s.get(1), s.get(2));
            }
            if (shadowError == null) {
                assertNull(valve.shadowError(), "shadowError");
            } else {
                assertEquals(shadowError, valve.shadowError(), CLOSE, "shadowError");
            }
            assertEquals(shadowSeconds, valve.shadowSeconds(), CLOSE, "shadowSeconds");
        }
    }
}
