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
 * A mean that follows its samples with a time constant, by the time that
 * actually passed: Home Assistant is not a real-time system, and a cycle
 * that comes late has to count as the longer step it was.
 *
 * <p>Python: the class Ewma in estimators.py and TestEwma in
 * tests/test_controller.py. as_dict and from_dict come with the learned
 * state in step 11.
 */
class EwmaTest {

    static final double CLOSE = 1e-9;

    @Nested
    class Starting {
        @Test
        void aNewFilterHasNoValue() {
            Ewma filter = new Ewma(600.0);
            assertEquals(600.0, filter.seconds());
            assertNull(filter.value());
        }

        @Test
        void itCanStartFromAValue() {
            assertEquals(7.0, new Ewma(600.0, 7.0).value());
        }

        @Test
        void theFirstSampleIsTakenWhole() {
            Ewma filter = new Ewma(600.0);
            assertEquals(10.0, filter.update(10.0, 30.0));
            assertEquals(10.0, filter.value());
        }
    }

    @Nested
    class Following {
        /** One time constant: 1 - e^-1 of the way, 63 %. */
        @Test
        void itReaches63PercentAfterOneTimeConstant() {
            Ewma filter = new Ewma(600.0, 0.0);
            filter.update(10.0, 600.0);
            assertEquals(10.0 * (1.0 - Math.exp(-1.0)), filter.value(), 1e-6);
        }

        /** Five minutes in one late cycle is the same as ten of thirty seconds. */
        @Test
        void irregularCyclesFilterByElapsedTime() {
            Ewma oneStep = new Ewma(600.0, 0.0);
            oneStep.update(10.0, 300.0);
            Ewma manySteps = new Ewma(600.0, 0.0);
            for (int i = 0; i < 10; i++) {
                manySteps.update(10.0, 30.0);
            }
            assertEquals(oneStep.value(), manySteps.value(), 1e-6);
        }

        @Test
        void whatItGivesBackIsItsNewValue() {
            Ewma filter = new Ewma(600.0, 0.0);
            Double given = filter.update(10.0, 120.0);
            assertEquals(filter.value(), given);
            assertEquals(10.0 * (1.0 - Math.exp(-0.2)), given, CLOSE);
        }
    }

    @Nested
    class TheEdges {
        @Test
        void aMissingSampleLeavesTheValueAlone() {
            Ewma filter = new Ewma(600.0, 7.0);
            assertEquals(7.0, filter.update(null, 30.0));
            assertEquals(7.0, filter.value());
        }

        @Test
        void aMissingSampleOnAnEmptyFilterIsStillNothing() {
            Ewma filter = new Ewma(600.0);
            assertNull(filter.update(null, 30.0));
            assertNull(filter.value());
        }

        @Test
        void itSurvivesAGapLongerThanTheMaths() {
            Ewma filter = new Ewma(600.0, 0.0);
            filter.update(10.0, 10_000_000.0);
            assertEquals(10.0, filter.value(), 1e-6);
        }

        /** From thirty time constants on, the sample is all: exactly, not nearly. */
        @Test
        void thirtyTimeConstantsAreTheWholeWay() {
            Ewma filter = new Ewma(600.0, 0.0);
            filter.update(10.0, 30.0 * 600.0);
            assertEquals(10.0, filter.value());
        }

        /** No time, time backwards or no time constant: the sample is the value. */
        @Test
        void withoutTimeOrATimeConstantTheSampleIsTakenWhole() {
            Ewma noTime = new Ewma(600.0, 0.0);
            noTime.update(10.0, 0.0);
            Ewma backwards = new Ewma(600.0, 0.0);
            backwards.update(10.0, -5.0);
            Ewma noConstant = new Ewma(0.0, 0.0);
            noConstant.update(10.0, 30.0);
            Ewma negative = new Ewma(-5.0, 0.0);
            negative.update(10.0, 30.0);
            assertEquals(10.0, noTime.value());
            assertEquals(10.0, backwards.value());
            assertEquals(10.0, noConstant.value());
            assertEquals(10.0, negative.value());
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        /** "update,sample,dt|..." on a filter with the time constant and start value, in order. */
        @ParameterizedTest
        @CsvFileSource(resources = "/ewma.csv", delimiter = ';', numLinesToSkip = 1, maxCharsPerColumn = 16384)
        void sameEwma(double seconds, String start, String steps, String outputs, String value) {
            Ewma filter = new Ewma(seconds, number(start));
            // One answer per update, null as an empty place: a single null is an empty cell.
            String[] expected = (outputs == null ? "" : outputs).split(",", -1);
            String[] all = ops(steps);
            assertEquals(all.length, expected.length, "updates");
            for (int i = 0; i < all.length; i++) {
                String[] p = all[i].split(",", -1);
                assertClose(number(expected[i]), filter.update(number(p[1]), number(p[2])), "update[" + i + "]");
            }
            assertClose(number(value), filter.value(), "value");
        }
    }
}
