package se.sodalen.heating.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * One bin's measurements from one part of the day: a running mean of the
 * supply needed, weighed by the hours, with the spread and where in the bin
 * the measurements fell.
 *
 * <p>Python: the class Cell in points.py. as_dict and from_dict come with
 * the learned state in step 11.
 */
class CellTest {

    static final double CLOSE = 1e-9;

    @Nested
    class Adding {
        @Test
        void aNewCellIsEmpty() {
            Cell cell = new Cell();
            assertEquals(0.0, cell.weight());
            assertEquals(0.0, cell.mean());
            assertEquals(0.0, cell.m2());
            assertEquals(0.0, cell.outdoor());
            assertEquals(0.0, cell.wind());
            assertEquals(0.0, cell.age());
        }

        @Test
        void theFirstMeasurementIsTheMean() {
            Cell cell = new Cell();
            cell.add(10.0, 3.1, 2.0, 1.5);
            assertEquals(10.0, cell.mean(), CLOSE);
            assertEquals(1.5, cell.weight(), CLOSE);
            assertEquals(3.1, cell.outdoor(), CLOSE);
            assertEquals(2.0, cell.wind(), CLOSE);
            assertEquals(0.0, cell.m2(), CLOSE);
        }

        /** An hour of 10 and three of 14: the mean is 13, not 12. */
        @Test
        void eachMeasurementWeighsItsHours() {
            Cell cell = new Cell();
            cell.add(10.0, 3.1, 2.0, 1.0);
            cell.add(14.0, 3.5, 4.0, 3.0);
            assertEquals(13.0, cell.mean(), CLOSE);
            assertEquals(4.0, cell.weight(), CLOSE);
            assertEquals(3.4, cell.outdoor(), CLOSE);
            assertEquals(3.5, cell.wind(), CLOSE);
        }

        /**
         * The squared deviations, each by its hours: 1 h at 3 below the mean
         * and 3 h at 1 above, 9 + 3 = 12. The new mean goes into it, not the
         * old one.
         */
        @Test
        void theDeviationsAreSummedForTheSpread() {
            Cell cell = new Cell();
            cell.add(10.0, 3.1, 2.0, 1.0);
            cell.add(14.0, 3.5, 4.0, 3.0);
            assertEquals(12.0, cell.m2(), CLOSE);
        }

        @Test
        void aMeasurementMakesItNew() {
            Cell cell = new Cell();
            cell.add(10.0, 3.0, 0.0, 2.0);
            cell.fade(0.9, 30.0);
            cell.add(12.0, 3.0, 0.0, 2.0);
            assertEquals(0.0, cell.age());
        }

        /** Nothing measured on nothing: the age it had is left too. */
        @Test
        void noHoursOnAnEmptyCellChangeNothing() {
            Cell cell = new Cell();
            cell.fade(1.0, 5.0);
            cell.add(10.0, 3.0, 2.0, 0.0);
            assertEquals(0.0, cell.mean());
            assertEquals(0.0, cell.weight());
            assertEquals(0.0, cell.outdoor());
            assertEquals(5.0, cell.age());
        }
    }

    @Nested
    class Fading {
        /** Old measurements count for less, not for something else. */
        @Test
        void theWeightAndTheDeviationsFadeTogether() {
            Cell cell = new Cell();
            cell.add(10.0, 3.1, 2.0, 1.0);
            cell.add(14.0, 3.5, 4.0, 3.0);
            cell.fade(0.5, 10.0);
            assertEquals(2.0, cell.weight(), CLOSE);
            assertEquals(6.0, cell.m2(), CLOSE);
            assertEquals(13.0, cell.mean(), CLOSE);
            assertEquals(3.4, cell.outdoor(), CLOSE);
            assertEquals(3.5, cell.wind(), CLOSE);
        }

        @Test
        void theAgeAddsUp() {
            Cell cell = new Cell();
            cell.add(10.0, 3.0, 0.0, 2.0);
            cell.fade(1.0, 3.0);
            cell.fade(0.9, 4.0);
            assertEquals(7.0, cell.age(), CLOSE);
            assertEquals(1.8, cell.weight(), CLOSE);
        }

        @Test
        void aFactorOfOneOnlyAges() {
            Cell cell = new Cell();
            cell.add(10.0, 3.0, 0.0, 2.0);
            cell.fade(1.0, 24.0);
            assertEquals(2.0, cell.weight(), CLOSE);
            assertEquals(24.0, cell.age(), CLOSE);
        }

        /** Faded to nothing, the next measurement is the whole mean. */
        @Test
        void afterFadingToNothingTheNextMeasurementIsAll() {
            Cell cell = new Cell();
            cell.add(10.0, 3.0, 2.0, 2.0);
            cell.fade(0.0, 100.0);
            assertEquals(0.0, cell.weight());
            cell.add(20.0, 5.0, 4.0, 1.0);
            assertEquals(20.0, cell.mean(), CLOSE);
            assertEquals(5.0, cell.outdoor(), CLOSE);
            assertEquals(0.0, cell.m2(), CLOSE);
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        /**
         * "add,need,outdoor,wind,hours|fade,factor,hours|..." played on a new
         * cell, in order.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/cell.csv", delimiter = ';', numLinesToSkip = 1)
        void sameCell(String ops, double weight, double mean, double m2, double outdoor, double wind, double age) {
            Cell cell = new Cell();
            for (String op : ops.split("\\|")) {
                String[] p = op.split(",");
                if (p[0].equals("add")) {
                    cell.add(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                            Double.parseDouble(p[4]));
                } else {
                    cell.fade(Double.parseDouble(p[1]), Double.parseDouble(p[2]));
                }
            }
            assertEquals(weight, cell.weight(), CLOSE * Math.max(1.0, Math.abs(weight)), "weight");
            assertEquals(mean, cell.mean(), CLOSE * Math.max(1.0, Math.abs(mean)), "mean");
            assertEquals(m2, cell.m2(), CLOSE * Math.max(1.0, Math.abs(m2)), "m2");
            assertEquals(outdoor, cell.outdoor(), CLOSE * Math.max(1.0, Math.abs(outdoor)), "outdoor");
            assertEquals(wind, cell.wind(), CLOSE * Math.max(1.0, Math.abs(wind)), "wind");
            assertEquals(age, cell.age(), CLOSE * Math.max(1.0, Math.abs(age)), "age");
        }
    }
}
