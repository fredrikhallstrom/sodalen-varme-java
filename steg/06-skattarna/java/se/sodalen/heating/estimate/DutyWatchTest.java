package se.sodalen.heating.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static se.sodalen.heating.estimate.Facit.assertClose;
import static se.sodalen.heating.estimate.Facit.number;
import static se.sodalen.heating.estimate.Facit.ops;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * What share of the time something held, over a window that fades. Only
 * time when it could be told counts; how much of that there was is the
 * weight, so a hundred per cent of ten minutes can be told from a hundred
 * per cent of three days.
 *
 * <p>Python: the class DutyWatch in estimators.py, which has no tests of
 * its own there; these are written from the code. as_dict and from_dict
 * come with the learned state in step 11.
 */
class DutyWatchTest {

    static final double CLOSE = 1e-9;
    static final double HOUR = 3600.0;

    /** A window so long that nothing fades while a test runs. */
    static final double FOREVER = 1e15;

    @Nested
    class Counting {
        @Test
        void aNewWatchKnowsNothing() {
            DutyWatch watch = new DutyWatch(HOUR);
            assertEquals(HOUR, watch.seconds());
            assertNull(watch.share());
            assertEquals(0.0, watch.weight());
            assertEquals(0.0, watch.hours());
        }

        @Test
        void theFirstReadingIsTheShare() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(true, 30.0);
            assertEquals(1.0, watch.share());
            assertEquals(30.0, watch.weight());
        }

        /** Held for 30 seconds, then not for 90: a quarter of the time. */
        @Test
        void eachReadingWeighsItsTime() {
            DutyWatch watch = new DutyWatch(FOREVER);
            watch.update(true, 30.0);
            watch.update(false, 90.0);
            assertEquals(0.25, watch.share(), CLOSE);
            assertEquals(120.0, watch.weight(), CLOSE);
        }

        @Test
        void theHoursAreTheWeightInHours() {
            DutyWatch watch = new DutyWatch(FOREVER);
            watch.update(false, 7200.0);
            assertEquals(2.0, watch.hours(), CLOSE);
        }
    }

    @Nested
    class Fading {
        /**
         * An hour held, then an hour not, with a window of an hour: the first
         * hour has faded to e^-1 by then, so the share is e^-1 / (1 + e^-1).
         * The weight fades before the new reading is counted.
         */
        @Test
        void theOldCountsForLess() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(true, HOUR);
            watch.update(false, HOUR);
            double faded = Math.exp(-1.0);
            assertEquals(faded / (1.0 + faded), watch.share(), CLOSE);
            assertEquals(HOUR * (1.0 + faded), watch.weight(), 1e-6);
        }

        /** Time that could not be told is no evidence, but it still passes. */
        @Test
        void timeThatCouldNotBeToldStillFades() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(true, 30.0);
            watch.update(null, HOUR);
            assertEquals(1.0, watch.share());
            assertEquals(30.0 * Math.exp(-1.0), watch.weight(), CLOSE);
        }

        @Test
        void aGapLongerThanTheMathsForgetsEverything() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(true, 30.0);
            watch.update(null, 30.0 * HOUR);
            assertEquals(0.0, watch.weight());
            assertEquals(1.0, watch.share());
        }

        /** Without a window only the latest reading is left. */
        @Test
        void withoutAWindowOnlyTheLatestCounts() {
            DutyWatch watch = new DutyWatch(0.0);
            watch.update(true, 30.0);
            watch.update(false, 30.0);
            assertEquals(0.0, watch.share());
            assertEquals(30.0, watch.weight());
        }
    }

    @Nested
    class TheEdges {
        @Test
        void noTimeChangesNothing() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(true, 30.0);
            watch.update(false, 0.0);
            watch.update(false, -1.0);
            assertEquals(1.0, watch.share());
            assertEquals(30.0, watch.weight());
        }

        @Test
        void nothingToldOnANewWatchIsStillNothing() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(null, 30.0);
            assertNull(watch.share());
            assertEquals(0.0, watch.weight());
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        /** "held,dt|..." on a watch with the window, in order; held is true, false or empty. */
        @ParameterizedTest
        @CsvFileSource(resources = "/duty_watch.csv", delimiter = ';', numLinesToSkip = 1, maxCharsPerColumn = 16384)
        void sameWatch(double seconds, String steps, String share, double weight, double hours) {
            DutyWatch watch = new DutyWatch(seconds);
            for (String op : ops(steps)) {
                String[] p = op.split(",", -1);
                Boolean held = p[0].isEmpty() ? null : Boolean.valueOf(p[0]);
                watch.update(held, number(p[1]));
            }
            assertClose(number(share), watch.share(), "share");
            assertClose(weight, watch.weight(), "weight");
            assertClose(hours, watch.hours(), "hours");
        }
    }
}
