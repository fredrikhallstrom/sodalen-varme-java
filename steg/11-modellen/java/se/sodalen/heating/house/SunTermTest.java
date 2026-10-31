package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.assertSameDict;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;
import static se.sodalen.heating.house.SunTerm.HORIZON_SECONDS;
import static se.sodalen.heating.house.SunTerm.HOUR_SECONDS;
import static se.sodalen.heating.house.SunTerm.LIGHT;
import static se.sodalen.heating.house.SunTerm.LIGHT_DEMAND;
import static se.sodalen.heating.house.SunTerm.MIN_HOURS;
import static se.sodalen.heating.house.SunTerm.MIN_SUNNY_HOURS;
import static se.sodalen.heating.house.SunTerm.SIZE;
import static se.sodalen.heating.house.SunTerm.SUNNY;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.estimate.MultiRegression;

/**
 * The sun's part of the indoor error, learnt. A house whose error is known:
 * 0.2 K of curve level, 0.3 K more per unit of demand, and the sun
 * -5.7 + 4.0 x demand K per kW/m2 of held light - the closed loop's on
 * Sodalen's walls. The fit has to find the sun's part, say how sure it is,
 * and the uncertainty has to come down as the days come. Hourly means, not
 * cycles: the hundred and twenty cycles of an hour are one observation.
 *
 * <p>Python: sunterm.py and tests/test_sunterm.py. Python's random.gauss
 * cannot be reproduced in Java, so taught() draws its noise from
 * java.util.Random(seed).nextGaussian(); the tests have the tolerances the
 * Python ones have, and hold with Java's noise too.
 */
class SunTermTest {

    static final double HOUR = 3600.0;
    static final double DAY = 24.0 * HOUR;

    static double truth(double demand, double light) {
        return 0.2 + 0.3 * demand + (-5.7 + 4.0 * demand) * light;
    }

    /**
     * Demand between 0.2 and 0.7 over a day and a half, and a sun up from
     * seven to five whose sky changes from day to day: {demand, light}.
     */
    static double[] weather(double hour) {
        double demand = 0.45 + 0.25 * Math.sin(2.0 * Math.PI * hour / 36.0);
        double clock = hour % 24.0;
        double[] skies = {1.0, 0.1, 0.6, 0.0, 0.9, 0.3, 0.8};
        double sky = skies[(int) (hour / 24.0) % 7];
        double light = 7.0 < clock && clock < 17.0 ? 0.4 * sky * Math.sin(Math.PI * (clock - 7.0) / 10.0) : 0.0;
        return new double[] {demand, light};
    }

    static SunTerm taught(double days) {
        return taught(days, 0.05, 1, 30.0);
    }

    static SunTerm taught(double days, double noise, long seed, double every) {
        SunTerm term = new SunTerm();
        Random rng = new Random(seed);
        for (int step = 0; step < (int) (days * DAY / every); step++) {
            double[] now = weather(step * every / HOUR);
            term.observe(now[0], now[1], truth(now[0], now[1]) + noise * rng.nextGaussian(), every);
        }
        return term;
    }

    static Map<String, Object> stored(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** TestItFindsTheSun. */
    @Nested
    class ItFindsTheSun {
        @Test
        void inMildWeather() {
            SunTerm term = taught(14);
            assertEquals(-5.7 + 4.0 * 0.3, term.perKw(0.3), 0.3);
        }

        /** A mild house gives the sun back less than a cold one: its radiators are near their floor already. */
        @Test
        void andInTheCold() {
            SunTerm term = taught(14);
            assertEquals(-5.7 + 4.0 * 0.6, term.perKw(0.6), 0.3);
            assertTrue(term.perKw(0.3) < term.perKw(0.6), term.perKw(0.3) + " < " + term.perKw(0.6));
        }

        @Test
        void whatComesOffTheError() {
            SunTerm term = taught(14);
            assertEquals(term.perKw(0.5) * 0.2, term.correction(0.5, 0.2), 1e-9);
            assertEquals(0.0, term.correction(0.5, 0.0), 0.0);
        }

        @Test
        void withoutSunInTheErrorItFindsNone() {
            SunTerm term = new SunTerm();
            for (int step = 0; step < (int) (14 * DAY / 30.0); step++) {
                double[] now = weather(step * 30.0 / HOUR);
                term.observe(now[0], now[1], 0.2 + 0.3 * now[0], 30.0);
            }
            assertEquals(0.0, term.perKw(0.5), 0.05);
        }
    }

    /** TestHowSureItIs. */
    @Nested
    class HowSureItIs {
        /** Fredrik: let it learn the sun, so the uncertainty comes down. */
        @Test
        void theUncertaintyComesDownWithTheDays() {
            double three = taught(3).spread(0.4);
            double seven = taught(7).spread(0.4);
            double fourteen = taught(14).spread(0.4);
            assertTrue(three > seven, three + " > " + seven);
            assertTrue(seven > fourteen, seven + " > " + fourteen);
        }

        /**
         * The standard error of the sun's worth, which the hours bring down, not
         * the spread of one hour's error around it: a month of hours whose light
         * runs from nothing to a full sun is sure of the sun to well under that
         * spread.
         */
        @Test
        void itIsTheEstimatesUncertaintyNotAnHours() {
            SunTerm term = new SunTerm();
            Random rng = new Random(4);
            for (int i = 0; i < 30 * 24; i++) {
                double demand = 0.2 + 0.5 * rng.nextDouble();
                double light = rng.nextDouble();
                term.observe(demand, light, truth(demand, light) + 0.1 * rng.nextGaussian(), HOUR);
            }
            assertTrue(term.spread(0.45) < 0.3 * term.fit.sigma(), term.spread(0.45) + " < 0.3 * " + term.fit.sigma());
        }

        /** The demands seen are 0.2-0.7: in the middle it is surer than out at 0.95, where it is reaching. */
        @Test
        void leastSureWhereTheWeatherHasLeastBeen() {
            SunTerm term = taught(14);
            assertTrue(term.spread(0.45) < term.spread(0.95), term.spread(0.45) + " < " + term.spread(0.95));
        }

        @Test
        void asAShare() {
            SunTerm term = taught(14);
            assertEquals(term.spread(0.4) / Math.abs(term.perKw(0.4)), term.share(0.4), 1e-9);
            assertTrue(term.share(0.4) < 0.2, "share " + term.share(0.4));
        }

        /**
         * The cycles of an hour say one thing about the sun. Counted one by one
         * they made it a hundred times surer than it was (the prototype's
         * +-0.01 K per kW/m2).
         */
        @Test
        void anHourIsOneObservationHoweverManyCycles() {
            SunTerm quick = taught(7, 0.05, 1, 30.0);
            SunTerm slow = taught(7, 0.05, 1, 300.0);
            assertEquals(1.0, quick.spread(0.4) / slow.spread(0.4), 0.3);
            assertTrue(Math.abs(quick.hours - 7 * 24) <= 1, "hours " + quick.hours);
        }

        @Test
        void knownWhereTheLightTimesTheUncertaintyIsSmall() {
            SunTerm term = taught(14);
            double spread = term.spread(0.4);
            assertTrue(term.known(0.4, 0.09 / spread, 0.1));
            assertFalse(term.known(0.4, 0.11 / spread, 0.1));
        }

        /** Edge: nothing to take off - the night. */
        @Test
        void noLightIsKnown() {
            assertTrue(taught(14).known(0.4, 0.0, 0.1));
        }

        /**
         * A light held at nothing after a restart is not the evening after the
         * sun: the light's waits decide then (test_controller's TestLearningGates
         * found it, 2026-10-18).
         */
        @Test
        void butNotBeforeItHasAnswered() {
            assertFalse(new SunTerm().known(0.4, 0.0, 0.1));
            assertFalse(taught(1.9).known(0.4, 0.0, 0.1));
        }

        /** Edge: an answer from a day of hours is a guess (MIN_HOURS). */
        @Test
        void beforeTwoDaysItIsNotKnown() {
            SunTerm term = taught(1.9);
            assertNull(term.perKw(0.4));
            assertNull(term.spread(0.4));
            assertFalse(term.known(0.4, 0.05, 0.1));
            assertEquals(0.0, term.correction(0.4, 0.3), 0.0);
            assertTrue(taught(2.1).known(0.4, 0.0, 0.1));
        }

        /** Nights only: the error has never met the sun, and any sun is an unknown, however sure the rest is. */
        @Test
        void aSunNeverSeenIsNotKnown() {
            SunTerm term = new SunTerm();
            for (int step = 0; step < (int) (14 * DAY / 300.0); step++) {
                double[] now = weather(step * 300.0 / HOUR);
                term.observe(now[0], 0.0, 0.2 + 0.3 * now[0], 300.0);
            }
            assertFalse(term.known(0.4, 0.05, 0.1));
            assertEquals(0.0, term.correction(0.4, 0.05), 0.0);
        }
    }

    /** TestWhatItTakes. */
    @Nested
    class WhatItTakes {
        @Test
        void underAnHourIsNotYetASample() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.2, -0.5, 3599.0);
            assertEquals(0, term.hours);
            term.observe(0.4, 0.2, -0.5, 1.0);
            assertEquals(1, term.hours);
        }

        /** Edge: half an hour at one sun and half at another is the mean. */
        @Test
        void anHourIsItsMeans() {
            SunTerm a = new SunTerm();
            SunTerm b = new SunTerm();
            a.observe(0.4, 0.1, -0.2, 1800.0);
            a.observe(0.4, 0.3, -0.6, 1800.0);
            b.observe(0.4, 0.2, -0.4, 3600.0);
            assertSameDict(b.fit.asDict(), a.fit.asDict(), 0.0, "fit.as_dict");
        }

        @Test
        void aMissingReadingIsNoSample() {
            SunTerm term = new SunTerm();
            term.observe(null, 0.1, 0.0, 3600.0);
            term.observe(0.4, null, 0.0, 3600.0);
            term.observe(0.4, 0.1, null, 3600.0);
            term.observe(0.4, Double.NaN, 0.0, 3600.0);
            term.observe(0.4, 0.1, Double.POSITIVE_INFINITY, 3600.0);
            assertEquals(0, term.hours);
            assertEquals(0.0, term.fit.weight(), 0.0);
        }

        @Test
        void aLightBelowNothingIsNothing() {
            SunTerm a = new SunTerm();
            SunTerm b = new SunTerm();
            a.observe(0.4, -0.2, -0.5, 3600.0);
            b.observe(0.4, 0.0, -0.5, 3600.0);
            assertSameDict(b.asDict(), a.asDict(), 0.0, "as_dict");
            assertEquals(0, a.sunnyHours);
        }

        @Test
        void noTimeIsNoSample() {
            SunTerm term = new SunTerm();
            for (Double dt : Arrays.asList(0.0, -30.0, Double.NaN)) {
                term.observe(0.4, 0.1, -0.2, dt);
            }
            term.observe(0.4, 0.1, -0.2, 3599.0);
            assertEquals(0, term.hours);
        }
    }

    /** TestKeepingIt. */
    @Nested
    class KeepingIt {
        @Test
        void itSurvivesARestart() {
            SunTerm term = taught(5);
            term.observe(0.4, 0.2, -0.5, 1200.0);
            SunTerm back = SunTerm.fromDict(term.asDict(), HORIZON_SECONDS);
            assertSameDict(term.asDict(), back.asDict(), 0.0, "as_dict");
            assertEquals(term.perKw(0.4), back.perKw(0.4));
        }

        @Test
        void theHorizonIsTheTunings() {
            SunTerm back = SunTerm.fromDict(taught(3).asDict(), 7.0 * DAY);
            assertEquals(7.0 * DAY, back.fit.horizon(), 0.0);
        }

        @Test
        void nothingStoredIsATermThatKnowsNothing() {
            for (Map<String, Object> junk : Arrays.asList(null, stored(), stored("fit", "nej"),
                    stored("hour", List.of("x")), stored("hours", "många"),
                    stored("fit", "nej", "hours", 50, "sunny_hours", 9))) {
                SunTerm back = SunTerm.fromDict(junk, HORIZON_SECONDS);
                assertEquals(0, back.hours, String.valueOf(junk));
                assertEquals(0, back.sunnyHours, String.valueOf(junk));
                assertNull(back.perKw(0.4), String.valueOf(junk));
            }
        }
    }

    /** Behaviour the Python tests do not pin, read off sunterm.py. */
    @Nested
    class FromTheCode {
        @Test
        void theConstants() {
            assertEquals(4, SIZE, "1, demand, light, light x demand");
            assertEquals(2, LIGHT);
            assertEquals(3, LIGHT_DEMAND);
            assertEquals(3600.0, HOUR_SECONDS, 0.0);
            assertEquals(21.0 * 24.0 * 3600.0, HORIZON_SECONDS, 0.0);
            assertEquals(48, MIN_HOURS);
            assertEquals(6, MIN_SUNNY_HOURS);
            assertEquals(0.02, SUNNY, 0.0);
        }

        @Test
        void aNewTermHasAnEmptyFitOfFourAndAnEmptyHour() {
            SunTerm term = new SunTerm();
            assertNotNull(term.fit);
            assertEquals(SIZE, term.fit.size());
            assertEquals(HORIZON_SECONDS, term.fit.horizon(), 0.0);
            assertEquals(0.0, term.fit.weight(), 0.0);
            assertArrayEqualsExactly(new double[] {0.0, 0.0, 0.0, 0.0}, term.hour);
            assertEquals(0, term.hours);
            assertEquals(0, term.sunnyHours);
            assertNull(term.perKw(0.4));
            assertNull(term.spread(0.4));
            assertNull(term.share(0.4));
        }

        /** hour[0] += dt, hour[1] += demand dt, hour[2] += max(0, light) dt, hour[3] += error dt. */
        @Test
        void theHourUnderWayIsSecondsAndSecondsTimesEach() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.1, -0.2, 1800.0);
            assertArrayEqualsExactly(new double[] {1800.0, 720.0, 180.0, -360.0}, term.hour);
            term.observe(0.6, -0.5, 0.1, 600.0);
            assertArrayEqualsExactly(new double[] {2400.0, 1080.0, 180.0, -300.0}, term.hour);
            assertEquals(0, term.hours, "not an hour yet");
            assertEquals(0.0, term.fit.weight(), 0.0);
        }

        /** The hour is handed to the fit as its means over the seconds it took, and starts again from nought. */
        @Test
        void anHourGoesIntoTheFitAsMeansAndStartsAgain() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.1, -0.2, 2000.0);
            term.observe(0.4, 0.1, -0.2, 2000.0);
            assertEquals(1, term.hours);
            assertArrayEqualsExactly(new double[] {0.0, 0.0, 0.0, 0.0}, term.hour);
            assertEquals(1.0, term.fit.weight(), 0.0, "one sample, whatever the seconds");
            Map<String, ?> fit = term.fit.asDict();
            assertEquals(List.of(1.0 * -0.2, 0.4 * -0.2, 0.1 * -0.2, 0.1 * 0.4 * -0.2), fit.get("xy"),
                    "x = 1, demand, light, light x demand");
        }

        /** The fit decays by the seconds the hour took: a long hour fades the old samples more. */
        @Test
        void theFitFadesByTheHoursSeconds() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.1, -0.2, 3600.0);
            term.observe(0.4, 0.1, -0.2, 7200.0);
            double decay = Math.pow(Math.E, -7200.0 / HORIZON_SECONDS);
            assertEquals(1.0 * decay + 1.0, term.fit.weight(), 1e-12);
        }

        /** light > SUNNY, strictly, on the hour's mean. */
        @Test
        void aSunnyHourIsOneWhoseMeanLightIsOverSunny() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.0, 0.0, 3600.0);
            assertEquals(0, term.sunnyHours, "dark");
            term.observe(0.4, 0.0201, 0.0, 3600.0);
            assertEquals(1, term.sunnyHours, "just over");
            term.observe(0.4, 0.5, 0.0, 1800.0);
            term.observe(0.4, 0.0, 0.0, 1800.0);
            assertEquals(2, term.sunnyHours, "half an hour of sun is a sunny hour on the mean");
            assertEquals(3, term.hours);
        }

        /** light > SUNNY, not >=: an hour whose mean light is exactly SUNNY is not a sunny hour, however it got there. */
        @Test
        void anHourExactlyAtSunnyIsNotSunny() {
            SunTerm term = new SunTerm();
            term.observe(0.4, SUNNY, 0.0, 3600.0);
            assertEquals(1, term.hours);
            assertEquals(0, term.sunnyHours, "a whole hour at SUNNY");
            term.observe(0.4, 2.0 * SUNNY, 0.0, 1800.0);
            term.observe(0.4, 0.0, 0.0, 1800.0);
            assertEquals(2, term.hours);
            assertEquals(0, term.sunnyHours, "half an hour at twice SUNNY and half in the dark: the mean is SUNNY");
            term.observe(0.4, 0.0201, 0.0, 3600.0);
            assertEquals(1, term.sunnyHours, "over, by however little");
        }

        /** dt <= 0 is no time at all: nothing goes into the hour, not even a negative share of it. */
        @Test
        void noTimeAndNegativeTimeAddNothingToTheHour() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.1, -0.2, -30.0);
            term.observe(0.4, 0.1, -0.2, 0.0);
            assertArrayEqualsExactly(new double[] {0.0, 0.0, 0.0, 0.0}, term.hour);
            term.observe(0.4, 0.1, -0.2, 1800.0);
            term.observe(0.4, 0.1, -0.2, -600.0);
            term.observe(0.4, 0.1, -0.2, 0.0);
            assertArrayEqualsExactly(new double[] {1800.0, 720.0, 180.0, -360.0}, term.hour);
            assertEquals(0, term.hours, "the half hour stands, nothing was taken off it");
            assertEquals(0.0, term.fit.weight(), 0.0);
        }

        /** _finite: an Integer is a number; infinity and nan are not; dt must be positive and finite. */
        @Test
        void onlyFiniteReadingsAndPositiveTimeCount() {
            SunTerm term = new SunTerm();
            term.observe(Double.NEGATIVE_INFINITY, 0.1, 0.0, 3600.0);
            term.observe(0.4, 0.1, Double.NaN, 3600.0);
            term.observe(0.4, 0.1, 0.0, Double.POSITIVE_INFINITY);
            term.observe(0.4, 0.1, 0.0, null);
            assertEquals(0.0, term.hour[0], 0.0, "nothing counted");
            term.observe(0.4, Double.NEGATIVE_INFINITY, 0.0, 3600.0);
            assertEquals(0.0, term.hour[0], 0.0, "an infinite light is not a dark one");
        }

        /** _ready: hours and sunny hours and a fit that answers - all three. */
        @Test
        void readyTakesHoursSunnyHoursAndAFitThatAnswers() {
            SunTerm term = taught(2.1);
            assertNotNull(term.perKw(0.4), "taught: it answers");
            SunTerm fewHours = SunTerm.fromDict(term.asDict(), HORIZON_SECONDS);
            fewHours.hours = MIN_HOURS - 1;
            assertNull(fewHours.perKw(0.4), "an hour short");
            SunTerm fewSunny = SunTerm.fromDict(term.asDict(), HORIZON_SECONDS);
            fewSunny.sunnyHours = MIN_SUNNY_HOURS - 1;
            assertNull(fewSunny.perKw(0.4), "a sunny hour short");
            SunTerm exactly = SunTerm.fromDict(term.asDict(), HORIZON_SECONDS);
            exactly.hours = MIN_HOURS;
            exactly.sunnyHours = MIN_SUNNY_HOURS;
            assertNotNull(exactly.perKw(0.4), "exactly enough is enough");
            SunTerm noFit = new SunTerm();
            noFit.hours = 100;
            noFit.sunnyHours = 50;
            assertNull(noFit.perKw(0.4), "hours without a fit");
            assertNull(noFit.spread(0.4));
            assertFalse(noFit.known(0.4, 0.0, 0.1));
            assertEquals(0.0, noFit.correction(0.4, 0.3), 0.0);
        }

        /** beta[LIGHT] + beta[LIGHT_DEMAND] * demand: a line in the demand. */
        @Test
        void perKwIsALineInTheDemand() {
            SunTerm term = taught(7);
            double[] beta = term.fit.coefficients();
            assertEquals(beta[LIGHT] + beta[LIGHT_DEMAND] * 0.37, term.perKw(0.37), 1e-12);
            assertEquals(term.perKw(0.6) - term.perKw(0.3), term.perKw(0.9) - term.perKw(0.6), 1e-9);
        }

        /** spread: the fit's variance at (0, 0, 1, demand) less sigma squared, rooted. */
        @Test
        void spreadIsTheLeverageOfTheLightAtThatDemand() {
            SunTerm term = taught(7);
            double whole = term.fit.variance(new double[] {0.0, 0.0, 1.0, 0.4});
            double sigma = term.fit.sigma();
            assertEquals(Math.sqrt(Math.max(0.0, whole - sigma * sigma)), term.spread(0.4), 1e-12);
        }

        /** known: light <= 0 or light * spread <= limit, once it answers. */
        @Test
        void knownIsTheLightTimesTheSpreadAgainstTheLimit() {
            SunTerm term = taught(7);
            double spread = term.spread(0.4);
            assertTrue(term.known(0.4, 0.0999 / spread, 0.1), "just under the limit");
            assertTrue(term.known(0.4, -1.0, 0.0), "a negative light");
            assertFalse(term.known(0.4, 1e-9 / spread, 0.0), "a limit of nought and any light");
        }

        /** correction: worth times light, nought for no light or a light below nought. */
        @Test
        void correctionIsWorthTimesLight() {
            SunTerm term = taught(7);
            assertEquals(term.perKw(0.4) * 0.3, term.correction(0.4, 0.3), 1e-12);
            assertEquals(0.0, term.correction(0.4, -0.3), 0.0);
            assertEquals(0.0, term.correction(0.4, 0.0), 0.0);
        }

        /** share: spread over the size of the worth; None before it answers. */
        @Test
        void shareIsSpreadOverTheWorth() {
            SunTerm term = taught(7);
            assertEquals(term.spread(0.6) / Math.abs(term.perKw(0.6)), term.share(0.6), 1e-12);
            assertNull(taught(1.0).share(0.6));
        }

        /** {"fit": fit.as_dict(), "hour": list(self.hour), "hours", "sunny_hours"} - and hour is a copy. */
        @Test
        void asDictHasPythonsKeysAndCopiesTheHour() {
            SunTerm term = new SunTerm();
            term.observe(0.4, 0.1, -0.2, 1800.0);
            Map<String, ?> dict = term.asDict();
            assertEquals(List.of("fit", "hour", "hours", "sunny_hours"), List.copyOf(dict.keySet()));
            assertSameDict(term.fit.asDict(), dict.get("fit"), 0.0, "fit");
            assertEquals(List.of(1800.0, 720.0, 180.0, -360.0), dict.get("hour"));
            assertEquals(0, dict.get("hours"), "an int");
            assertEquals(0, dict.get("sunny_hours"), "an int");
            term.observe(0.4, 0.1, -0.2, 600.0);
            assertEquals(List.of(1800.0, 720.0, 180.0, -360.0), dict.get("hour"), "the dict kept its copy");
            assertNotSame(dict.get("hour"), term.asDict().get("hour"));
        }

        /** The hour list is taken only as four finite numbers; ints are numbers. */
        @Test
        void theStoredHourIsTakenOnlyAsFourFiniteNumbers() {
            assertArrayEqualsExactly(new double[] {1.0, 2.0, 3.0, 4.0},
                    SunTerm.fromDict(stored("hour", List.of(1.0, 2.0, 3.0, 4.0)), HORIZON_SECONDS).hour);
            assertArrayEqualsExactly(new double[] {1.0, 2.0, 3.0, 4.0},
                    SunTerm.fromDict(stored("hour", List.of(1, 2, 3, 4)), HORIZON_SECONDS).hour);
            double[] fresh = {0.0, 0.0, 0.0, 0.0};
            for (Object hour : Arrays.asList(List.of(1.0, 2.0, 3.0), List.of(1.0, 2.0, 3.0, 4.0, 5.0),
                    Arrays.asList(1.0, null, 3.0, 4.0), List.of(1.0, 2.0, Double.NaN, 4.0),
                    List.of(1.0, 2.0, 3.0, Double.POSITIVE_INFINITY), List.of("1", "2", "3", "x"), "abc", 4.0,
                    Map.of())) {
                assertArrayEqualsExactly(fresh, SunTerm.fromDict(stored("hour", hour), HORIZON_SECONDS).hour,
                        String.valueOf(hour));
            }
        }

        /** hours and sunny_hours are taken only when the fit has weight - a count without a fit means nothing. */
        @Test
        void theHoursAreTakenOnlyWithAFitBehindThem() {
            SunTerm taught = taught(3);
            Map<String, ?> good = taught.fit.asDict();
            SunTerm without = SunTerm.fromDict(stored("hours", 50, "sunny_hours", 9), HORIZON_SECONDS);
            assertEquals(0, without.hours);
            assertEquals(0, without.sunnyHours);
            SunTerm with = SunTerm.fromDict(stored("fit", good, "hours", 50, "sunny_hours", 9), HORIZON_SECONDS);
            assertEquals(50, with.hours);
            assertEquals(9, with.sunnyHours);
            SunTerm emptyFit = SunTerm.fromDict(stored("fit", new MultiRegression(SIZE, HORIZON_SECONDS).asDict(),
                    "hours", 50, "sunny_hours", 9), HORIZON_SECONDS);
            assertEquals(0, emptyFit.hours, "a fit of weight nought is no fit");
        }

        /** int(as_float(...)): "12" is 12, 7.9 is 7, -2.5 is -2, None and "abc" are 0. */
        @Test
        void theHoursAreReadLikePythonsIntOfAsFloat() {
            Map<String, ?> good = taught(3).fit.asDict();
            SunTerm back = SunTerm.fromDict(stored("fit", good, "hours", "12", "sunny_hours", 7.9), HORIZON_SECONDS);
            assertEquals(12, back.hours);
            assertEquals(7, back.sunnyHours);
            back = SunTerm.fromDict(stored("fit", good, "hours", -2.5, "sunny_hours", null), HORIZON_SECONDS);
            assertEquals(-2, back.hours);
            assertEquals(0, back.sunnyHours);
            back = SunTerm.fromDict(stored("fit", good, "hours", "abc"), HORIZON_SECONDS);
            assertEquals(0, back.hours);
            assertEquals(0, back.sunnyHours, "missing");
        }

        /** The fit comes through MultiRegression.from_dict with SIZE and the horizon given. */
        @Test
        void theFitIsRestoredWithSizeFourAndTheHorizonGiven() {
            SunTerm term = taught(3);
            SunTerm back = SunTerm.fromDict(term.asDict(), 86400.0);
            assertEquals(SIZE, back.fit.size());
            assertEquals(86400.0, back.fit.horizon(), 0.0);
            assertEquals(term.fit.weight(), back.fit.weight(), 0.0);
            for (Object fit : Arrays.asList(null, "abc", List.of(), Map.of(), stored("size", 3))) {
                SunTerm junk = SunTerm.fromDict(stored("fit", fit), 86400.0);
                assertEquals(0.0, junk.fit.weight(), 0.0, String.valueOf(fit));
                assertEquals(SIZE, junk.fit.size(), String.valueOf(fit));
                assertEquals(86400.0, junk.fit.horizon(), 0.0, String.valueOf(fit));
            }
        }

        /** A restored term goes on learning as the original would have. */
        @Test
        void aRestoredTermGoesOnLearning() {
            SunTerm term = taught(2.0);
            SunTerm back = SunTerm.fromDict(term.asDict(), HORIZON_SECONDS);
            for (int step = 0; step < (int) (DAY / 30.0); step++) {
                double[] now = weather(2.0 * 24.0 + step * 30.0 / HOUR);
                double error = truth(now[0], now[1]);
                term.observe(now[0], now[1], error, 30.0);
                back.observe(now[0], now[1], error, 30.0);
            }
            assertSameDict(term.asDict(), back.asDict(), 0.0, "after the same day");
            assertEquals(term.perKw(0.4), back.perKw(0.4));
        }
    }

    static void assertArrayEqualsExactly(double[] expected, double[] actual) {
        assertArrayEqualsExactly(expected, actual, "hour");
    }

    static void assertArrayEqualsExactly(double[] expected, double[] actual, String what) {
        assertNotNull(actual, what);
        assertEquals(expected.length, actual.length, what + ": how many");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 0.0, what + "[" + i + "]");
        }
    }

    /**
     * Python's own answers, row by row from sun_term.csv: sequences of
     * observations through a weather like the tests', hour by hour or in parts
     * of hours, some with junk in them, and what the term then knew at four
     * demands; its as_dict, the round trip, and a stored dict (the same, or
     * one from another version) restored with a horizon. An empty cell arrives
     * as null.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        /** "demand,light,error,dt|..." with n for None and nan/inf as Python writes them. */
        void observe(SunTerm term, String observations) {
            if (observations == null) {
                return;
            }
            for (String one : observations.split("\\|", -1)) {
                String[] p = one.split(",", -1);
                term.observe(reading(p[0]), reading(p[1]), reading(p[2]), reading(p[3]));
            }
        }

        Double reading(String text) {
            return text.equals("n") ? null : number(text);
        }

        /**
         * observations;hours;sunny_hours; then per_kw_D;spread_D;share_D;known_D;correction_D
         * for D in 0.3, 0.45, 0.6, 0.95 (known with light 0.05 and limit 0.1, correction with
         * light 0.2); then as_dict;roundtrip_equal;restore_horizon;stored;restored_as_dict.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/sun_term.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameTerm(String observations, String hours, String sunnyHours,
                      String perKw03, String spread03, String share03, String known03, String correction03,
                      String perKw045, String spread045, String share045, String known045, String correction045,
                      String perKw06, String spread06, String share06, String known06, String correction06,
                      String perKw095, String spread095, String share095, String known095, String correction095,
                      String asDict, String roundtripEqual, String restoreHorizon, String stored,
                      String restoredAsDict) {
            SunTerm term = new SunTerm();
            observe(term, observations);
            assertEquals(Integer.parseInt(hours), term.hours, "hours");
            assertEquals(Integer.parseInt(sunnyHours), term.sunnyHours, "sunny_hours");
            String[][] answers = {
                {perKw03, spread03, share03, known03, correction03},
                {perKw045, spread045, share045, known045, correction045},
                {perKw06, spread06, share06, known06, correction06},
                {perKw095, spread095, share095, known095, correction095},
            };
            double[] demands = {0.3, 0.45, 0.6, 0.95};
            // The answers come out of solves: a millionth, like step 6.
            double solved = 1e-6;
            for (int i = 0; i < demands.length; i++) {
                String at = " at " + demands[i];
                assertClose(number(answers[i][0]), term.perKw(demands[i]), solved, "per_kw" + at);
                assertClose(number(answers[i][1]), term.spread(demands[i]), solved, "spread" + at);
                assertClose(number(answers[i][2]), term.share(demands[i]), solved, "share" + at);
                assertEquals(bool(answers[i][3]), term.known(demands[i], 0.05, 0.1), "known" + at);
                assertClose(number(answers[i][4]), term.correction(demands[i], 0.2), solved, "correction" + at);
            }
            Map<String, ?> dict = term.asDict();
            assertSameDict(dict(asDict), dict, "as_dict");
            assertTrue(bool(roundtripEqual), "Python's own round trip was equal");
            assertSameDict(dict, SunTerm.fromDict(dict, HORIZON_SECONDS).asDict(), 0.0, "the round trip");
            assertSameDict(dict(restoredAsDict), SunTerm.fromDict(dict(stored), required(restoreHorizon)).asDict(),
                    "restored from " + stored);
        }
    }
}
