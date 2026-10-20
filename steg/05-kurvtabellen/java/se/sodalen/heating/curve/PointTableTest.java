package se.sodalen.heating.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * The curve table: what the house needed, per two degrees outdoors and per
 * six hours of the day, kept for months. A bin's need is the average of its
 * parts of the day, each weighing the same, and it is trusted (green) once
 * most of the day is in it.
 *
 * <p>Python: points.py and the table's half of tests/test_points.py
 * (TestTheTable, TestTheVerdict). The fit through the table is step 9;
 * as_dict and from_dict come with the learned state in step 11.
 */
class PointTableTest {

    static final double CLOSE = 1e-9;

    /** `hours` spread evenly over the given parts of the day, an hour into each. */
    static PointTable filled(PointTable table, double outdoor, double need, double hours, int... sectors) {
        for (int sector : sectors) {
            table.add(outdoor, need, 0.0, sector * 6.0 + 1.0, hours / sectors.length);
        }
        return table;
    }

    static Bin only(PointTable table) {
        List<Bin> bins = table.bins();
        assertEquals(1, bins.size(), "bins");
        return bins.getFirst();
    }

    @Nested
    class TheBins {
        @Test
        void twoDegreesAndFourPartsOfTheDay() {
            assertEquals(2.0, PointTable.BIN_WIDTH);
            assertEquals(4, PointTable.SECTORS);
            assertEquals(6.0, PointTable.SECTOR_HOURS);
        }

        @Test
        void aBinStartsOnAnEvenDegree() {
            assertEquals(2, PointTable.binIndex(4.0));
            assertEquals(2, PointTable.binIndex(5.9));
            assertEquals(0, PointTable.binIndex(0.0));
            assertEquals(0, PointTable.binIndex(1.9));
        }

        /** Rounded down, not towards nought: -0.5 is in the bin from -2 to 0. */
        @Test
        void belowNoughtItRoundsDown() {
            assertEquals(-1, PointTable.binIndex(-0.5));
            assertEquals(-1, PointTable.binIndex(-2.0));
            assertEquals(-2, PointTable.binIndex(-2.1));
            assertEquals(-9, PointTable.binIndex(-18.0));
        }

        @Test
        void twoDegreesApartIsAnotherBin() {
            PointTable table = new PointTable();
            table.add(3.9, 10.0, 0.0, 1.0, 1.0);
            table.add(4.1, 10.0, 0.0, 1.0, 1.0);
            assertEquals(2, table.bins().size());
        }

        @Test
        void theKeyNamesTheBinAndThePartOfTheDay() {
            PointTable table = new PointTable();
            assertEquals("1:0", table.keyAt(3.0, 1.0));
            assertEquals("-2:2", table.keyAt(-3.0, 13.0));
            assertEquals("1:3", table.keyAt(3.0, 23.9));
        }

        @Test
        void thePartsOfTheDayStartAtSixTwelveAndEighteen() {
            PointTable table = new PointTable();
            assertEquals("0:0", table.keyAt(0.0, 5.99));
            assertEquals("0:1", table.keyAt(0.0, 6.0));
            assertEquals("0:2", table.keyAt(0.0, 12.0));
            assertEquals("0:3", table.keyAt(0.0, 18.0));
        }

        /** Hours run round the clock: 25 is one in the night, -1 is 23. */
        @Test
        void pastMidnightEitherWayIsAnotherDay() {
            PointTable table = new PointTable();
            assertEquals("0:0", table.keyAt(0.0, 24.0));
            assertEquals("0:0", table.keyAt(0.0, 25.0));
            assertEquals("0:3", table.keyAt(0.0, -1.0));
            assertEquals("0:2", table.keyAt(0.0, -7.0));
        }

        /** Nobody can say what time of day it was: the first part. */
        @Test
        void withoutAClockItIsTheFirstPart() {
            assertEquals("1:0", new PointTable().keyAt(3.0, null));
        }

        @Test
        void theBinsComeColdestFirst() {
            PointTable table = new PointTable();
            table.add(5.0, 10.0, 0.0, 1.0, 1.0);
            table.add(-7.0, 20.0, 0.0, 1.0, 1.0);
            table.add(1.0, 15.0, 0.0, 1.0, 1.0);
            table.add(-1.0, 16.0, 0.0, 1.0, 1.0);
            List<Double> outdoors = table.bins().stream().map(Bin::outdoor).toList();
            assertEquals(List.of(-7.0, -1.0, 1.0, 5.0), outdoors);
        }
    }

    @Nested
    class Measuring {
        @Test
        void aBinHoldsTheMeanOfWhatWasNeeded() {
            PointTable table = new PointTable();
            table.add(3.1, 10.0, 2.0, 1.0, 1.0);
            table.add(3.5, 14.0, 4.0, 1.0, 3.0);
            Bin bin = only(table);
            assertEquals(13.0, bin.need(), CLOSE);
            assertEquals(4.0, bin.hours(), CLOSE);
            assertEquals(3.4, bin.outdoor(), CLOSE);
            assertEquals(3.5, bin.wind(), CLOSE);
            assertEquals(Math.sqrt((1 * 9 + 3 * 1) / 4.0), bin.spread(), 1e-6);
        }

        @Test
        void theHoursAreCountedPerPartOfTheDay() {
            PointTable table = new PointTable();
            table.add(3.1, 10.0, 0.0, 1.0, 1.0);
            table.add(3.5, 14.0, 0.0, 2.0, 3.0);
            table.add(3.5, 14.0, 0.0, 13.0, 2.0);
            assertEquals(4.0, table.hoursAt(3.0, 4.0), CLOSE);
            assertEquals(2.0, table.hoursAt(2.0, 17.5), CLOSE);
        }

        @Test
        void whereNothingWasMeasuredThereAreNoHours() {
            PointTable table = new PointTable();
            table.add(3.1, 10.0, 0.0, 1.0, 1.0);
            assertEquals(0.0, table.hoursAt(3.0, 7.0));
            assertEquals(0.0, table.hoursAt(5.0, 1.0));
        }

        @Test
        void anEmptyTableHasNoBins() {
            assertEquals(List.of(), new PointTable().bins());
        }

        @Test
        void noTimeIsNoMeasurement() {
            PointTable table = new PointTable();
            table.add(3.0, 10.0, 0.0, 1.0, 0.0);
            table.add(3.0, 10.0, 0.0, 1.0, -1.0);
            assertEquals(List.of(), table.bins());
            assertEquals(0.0, table.hoursAt(3.0, 1.0));
        }

        /** Nothing measured does not make an old bin new. */
        @Test
        void noTimeDoesNotMakeItNew() {
            PointTable table = new PointTable();
            table.add(3.0, 10.0, 0.0, 1.0, 2.0);
            table.fade(10.0, 24.0 * 60);
            table.add(3.0, 12.0, 0.0, 1.0, 0.0);
            assertEquals(10.0, only(table).age(), CLOSE);
        }

        /** A sensor that gave out: not a number, or one past every number. */
        @Test
        void aBrokenTemperatureIsNoMeasurement() {
            PointTable table = new PointTable();
            table.add(Double.NaN, 10.0, 0.0, 1.0, 1.0);
            table.add(Double.POSITIVE_INFINITY, 10.0, 0.0, 1.0, 1.0);
            table.add(3.0, Double.NaN, 0.0, 1.0, 1.0);
            table.add(3.0, Double.NEGATIVE_INFINITY, 0.0, 1.0, 1.0);
            assertEquals(List.of(), table.bins());
        }

        /** No wind measured is calm, and so is a wind below nought. */
        @Test
        void noWindIsCalm() {
            PointTable table = new PointTable();
            table.add(3.0, 10.0, null, 1.0, 1.0);
            table.add(3.0, 10.0, -3.0, 1.0, 1.0);
            table.add(3.0, 10.0, 4.0, 1.0, 2.0);
            assertEquals(2.0, only(table).wind(), CLOSE);
        }

        @Test
        void clearingForgetsEverything() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 40.0, 0, 1, 2, 3);
            table.clear();
            assertEquals(List.of(), table.bins());
            assertEquals(0.0, table.hoursAt(3.0, 1.0));
        }
    }

    @Nested
    class ThePartsOfTheDay {
        /**
         * Thirty hours at noon do not drown three at night: the bin
         * describes the whole day, not the part the heating happened to run.
         */
        @Test
        void thePartsOfTheDayWeighTheSame() {
            PointTable table = new PointTable();
            table.add(3.0, 30.0, 0.0, 2.0, 3.0);
            table.add(3.0, 20.0, 0.0, 13.0, 30.0);
            assertEquals(25.0, only(table).need(), CLOSE);
        }

        /** Night and noon differing is the day, not noise. */
        @Test
        void theSpreadIsWithinThePartsNotBetweenThem() {
            PointTable table = new PointTable();
            table.add(3.0, 30.0, 0.0, 2.0, 10.0);
            table.add(3.0, 20.0, 0.0, 13.0, 10.0);
            assertEquals(0.0, only(table).spread(), CLOSE);
        }

        /** Half an hour in the night is too little to speak for the night. */
        @Test
        void aPartUnderAnHourIsLeftOutOfTheNeed() {
            PointTable table = new PointTable();
            table.add(3.0, 30.0, 0.0, 2.0, 0.5);
            table.add(3.0, 20.0, 0.0, 13.0, 3.0);
            table.add(3.0, 10.0, 0.0, 19.0, 3.0);
            Bin bin = only(table);
            assertEquals(15.0, bin.need(), CLOSE);
            assertEquals(2, bin.sectors());
            assertEquals(6.5, bin.hours(), CLOSE);
        }

        /** An hour exactly is enough for a part to count. */
        @Test
        void anHourExactlyCounts() {
            PointTable table = new PointTable();
            table.add(3.0, 30.0, 0.0, 2.0, 1.0);
            table.add(3.0, 20.0, 0.0, 13.0, 3.0);
            Bin bin = only(table);
            assertEquals(25.0, bin.need(), CLOSE);
            assertEquals(2, bin.sectors());
        }

        /** With no part an hour long, every part counts after all. */
        @Test
        void withNoPartAnHourLongEveryPartCounts() {
            PointTable table = new PointTable();
            table.add(3.0, 30.0, 0.0, 2.0, 0.5);
            table.add(3.0, 20.0, 0.0, 13.0, 0.5);
            Bin bin = only(table);
            assertEquals(25.0, bin.need(), CLOSE);
            assertEquals(0, bin.sectors());
        }

        /** The spread is every part's, the short ones too. */
        @Test
        void theSpreadPoolsEveryPart() {
            PointTable table = new PointTable();
            table.add(3.0, 28.0, 0.0, 2.0, 0.25);
            table.add(3.0, 32.0, 0.0, 2.0, 0.25);
            table.add(3.0, 20.0, 0.0, 13.0, 3.0);
            Bin bin = only(table);
            assertEquals(20.0, bin.need(), CLOSE);
            assertEquals(Math.sqrt(2.0 / 3.5), bin.spread(), CLOSE);
        }

        /** Where in the bin, and the wind, are by the hours, not by the part. */
        @Test
        void theOutdoorAndTheWindAreWeighedByTheHours() {
            PointTable table = new PointTable();
            table.add(3.0, 10.0, 2.0, 1.0, 1.0);
            table.add(3.8, 10.0, 6.0, 13.0, 3.0);
            Bin bin = only(table);
            assertEquals(3.6, bin.outdoor(), CLOSE);
            assertEquals(5.0, bin.wind(), CLOSE);
        }

        @Test
        void theAgeIsTheNewestPart() {
            PointTable table = new PointTable();
            table.add(3.0, 10.0, 0.0, 1.0, 2.0);
            table.fade(10.0, 24.0 * 60);
            table.add(3.0, 10.0, 0.0, 13.0, 2.0);
            assertEquals(0.0, only(table).age(), CLOSE);
            table.fade(5.0, 24.0 * 60);
            assertEquals(5.0, only(table).age(), CLOSE);
        }
    }

    @Nested
    class TheVerdict {
        /**
         * 10 - spread and 10 + spread in equal parts, so that the spread is
         * just that, in every part of the day given.
         */
        Bin judge(double hours, double spread, int... sectors) {
            PointTable table = new PointTable();
            for (int sector : sectors) {
                for (double need : new double[] {10.0 - spread, 10.0 + spread}) {
                    table.add(3.0, need, 0.0, sector * 6.0 + 1.0, hours / sectors.length / 2);
                }
            }
            return only(table);
        }

        Bin judge(double hours) {
            return judge(hours, 0.0, 0, 1, 2, 3);
        }

        @Test
        void theRulesAsTheHouseHasThem() {
            assertEquals(new Rules(3.0, 24.0, 1.5, 3, 1.0), new Rules());
        }

        @Test
        void underThreeHoursIsRedAndUnheard() {
            Bin verdict = judge(2.5);
            assertEquals(Status.RED, verdict.status());
            assertEquals(0.0, verdict.weight());
        }

        @Test
        void aDayOfSteadyMeasurementOverMostOfTheDayIsGreen() {
            Bin verdict = judge(30.0);
            assertEquals(Status.GREEN, verdict.status());
            assertEquals(1.0, verdict.weight());
        }

        @Test
        void tooFewHoursKeepsItYellow() {
            assertEquals(Status.YELLOW, judge(20.0).status());
        }

        @Test
        void tooMuchSpreadKeepsItYellow() {
            assertEquals(Status.YELLOW, judge(30.0, 2.0, 0, 1, 2, 3).status());
        }

        /** The autumn case: heating at night and in the morning only. */
        @Test
        void onlyTheNightsKeepsItYellowHoweverLong() {
            assertEquals(Status.YELLOW, judge(200.0, 0.0, 0, 1).status());
        }

        @Test
        void yellowWeighsMoreWithHoursAndWithTheDayCovered() {
            double few = judge(6.0, 0.0, 0, 1).weight();
            double more = judge(18.0, 0.0, 0, 1).weight();
            double wider = judge(18.0, 0.0, 0, 1, 2).weight();
            assertTrue(0.0 < few && few < more && more < wider && wider <= 0.5, few + " " + more + " " + wider);
        }

        /** Half a green bin's say at most, by the hours and by the share of the day. */
        @Test
        void yellowIsHalfAGreensSayByTheHoursAndTheDay() {
            assertEquals(0.5 * 0.5 * 0.5, judge(12.0, 0.0, 0, 1).weight(), CLOSE);
            assertEquals(0.5 * 1.0 * 0.5, judge(48.0, 0.0, 0, 1).weight(), CLOSE);
            assertEquals(0.5 * 0.5 * 0.75, judge(12.0, 0.0, 0, 1, 2).weight(), CLOSE);
        }

        /** Three hours exactly is no longer red. */
        @Test
        void threeHoursIsYellow() {
            assertEquals(Status.YELLOW, judge(3.0).status());
        }

        /** A day exactly, a spread of 1.5 exactly and three parts exactly: green. */
        @Test
        void onEveryLimitItIsGreen() {
            Bin verdict = judge(24.0, 1.5, 0, 1, 2);
            assertEquals(24.0, verdict.hours());
            assertEquals(1.5, verdict.spread());
            assertEquals(3, verdict.sectors());
            assertEquals(Status.GREEN, verdict.status());
        }

        @Test
        void otherRulesGiveOtherVerdicts() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 12.0, 0, 1);
            Rules easier = new Rules(1.0, 10.0, 1.5, 2, 1.0);
            assertEquals(Status.YELLOW, table.bins().getFirst().status());
            assertEquals(Status.GREEN, table.bins(easier).getFirst().status());
            Rules harder = new Rules(20.0, 48.0, 1.5, 4, 1.0);
            assertEquals(Status.RED, table.bins(harder).getFirst().status());
        }

        /** A part counts towards the day from the rules' hours, not from one hour. */
        @Test
        void thePartsCountFromTheRulesHours() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 8.0, 0, 1, 2, 3);
            assertEquals(4, table.bins().getFirst().sectors());
            assertEquals(0, table.bins(new Rules(3.0, 24.0, 1.5, 3, 2.5)).getFirst().sectors());
        }
    }

    @Nested
    class Fading {
        @Test
        void measurementsFadeByTheirHalfLife() {
            PointTable table = filled(new PointTable(), 3.0, 9.0, 20.0, 0, 1, 2, 3);
            filled(table, 3.0, 11.0, 20.0, 0, 1, 2, 3);
            double spread = only(table).spread();
            table.fade(60 * 24.0, 60 * 24.0);
            Bin bin = only(table);
            assertEquals(20.0, bin.hours(), CLOSE);
            // Old measurements count for less, not for something else.
            assertEquals(10.0, bin.need(), CLOSE);
            assertEquals(spread, bin.spread(), CLOSE);
            assertEquals(1.0, spread, CLOSE);
            assertEquals(60 * 24.0, bin.age(), CLOSE);
        }

        @Test
        void everyPartFades() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 8.0, 0, 2);
            table.fade(48.0, 24.0);
            assertEquals(1.0, table.hoursAt(3.0, 1.0), CLOSE);
            assertEquals(1.0, table.hoursAt(3.0, 13.0), CLOSE);
        }

        /** Without a half-life nothing is forgotten, but it still grows older. */
        @Test
        void withoutAHalfLifeItOnlyAges() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 8.0, 0);
            table.fade(10.0, 0.0);
            table.fade(10.0, -5.0);
            assertEquals(8.0, table.hoursAt(3.0, 1.0), CLOSE);
            assertEquals(20.0, only(table).age(), CLOSE);
        }

        @Test
        void noTimeIsNoFading() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 8.0, 0);
            table.fade(0.0, 24.0);
            table.fade(-5.0, 24.0);
            assertEquals(8.0, table.hoursAt(3.0, 1.0), CLOSE);
            assertEquals(0.0, only(table).age(), CLOSE);
        }

        /** Faded to nothing at all, a bin is no longer shown. */
        @Test
        void aBinFadedToNothingIsGone() {
            PointTable table = filled(new PointTable(), 3.0, 10.0, 8.0, 0);
            table.fade(24.0, 0.001);
            assertEquals(List.of(), table.bins());
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        /** Python writes nan and inf; Java reads NaN and Infinity. Empty is null. */
        static Double number(String text) {
            return switch (text) {
                case "" -> null;
                case "nan" -> Double.NaN;
                case "inf" -> Double.POSITIVE_INFINITY;
                case "-inf" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(text);
            };
        }

        static List<Double> numbers(String cell) {
            List<Double> values = new ArrayList<>();
            for (String part : cell.split(",", -1)) {
                values.add(number(part));
            }
            return values;
        }

        static List<String> words(String cell) {
            return cell == null || cell.isEmpty() ? List.of() : List.of(cell.split(","));
        }

        static List<List<Double>> groups(String cell) {
            List<List<Double>> groups = new ArrayList<>();
            if (cell != null && !cell.isEmpty()) {
                for (String group : cell.split("\\|")) {
                    groups.add(numbers(group));
                }
            }
            return groups;
        }

        static void assertClose(double expected, double actual, String what) {
            assertEquals(expected, actual, CLOSE * Math.max(1.0, Math.abs(expected)), what);
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/key_at.csv", delimiter = ';', numLinesToSkip = 1)
        void sameKey(double outdoor, Double hour, int binIndex, String key) {
            assertEquals(binIndex, PointTable.binIndex(outdoor), "binIndex");
            assertEquals(key, new PointTable().keyAt(outdoor, hour), "keyAt");
        }

        /**
         * "add,outdoor,need,wind,hour,hours|fade,hours,halfLife|clear|..."
         * played on a new table, then its bins, and what it says at six
         * places and times.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/point_table.csv", delimiter = ';', numLinesToSkip = 1,
                maxCharsPerColumn = 16384)
        void sameTable(String ops, boolean defaultRules, String rules, String bins, String statuses,
                       String queries, String hoursAt, String keys) {
            PointTable table = new PointTable();
            for (String op : ops == null ? new String[0] : ops.split("\\|")) {
                String[] p = op.split(",", -1);
                switch (p[0]) {
                    case "add" -> table.add(number(p[1]), number(p[2]), number(p[3]), number(p[4]), number(p[5]));
                    case "fade" -> table.fade(number(p[1]), number(p[2]));
                    default -> table.clear();
                }
            }
            List<Double> r = numbers(rules);
            List<Bin> got = defaultRules ? table.bins()
                    : table.bins(new Rules(r.get(0), r.get(1), r.get(2), r.get(3).intValue(), r.get(4)));

            List<List<Double>> expected = groups(bins);
            List<String> expectedStatus = words(statuses);
            assertEquals(expected.size(), got.size(), "bins: how many");
            for (int i = 0; i < expected.size(); i++) {
                List<Double> e = expected.get(i);
                Bin b = got.get(i);
                String at = "bins[" + i + "].";
                assertClose(e.get(0), b.outdoor(), at + "outdoor");
                assertClose(e.get(1), b.need(), at + "need");
                assertClose(e.get(2), b.hours(), at + "hours");
                assertClose(e.get(3), b.spread(), at + "spread");
                assertEquals(e.get(4).intValue(), b.sectors(), at + "sectors");
                assertClose(e.get(5), b.wind(), at + "wind");
                assertClose(e.get(6), b.age(), at + "age");
                assertEquals(expectedStatus.get(i), b.status().name().toLowerCase(Locale.ROOT), at + "status");
                assertClose(e.get(7), b.weight(), at + "weight");
            }

            List<List<Double>> asked = groups(queries);
            List<Double> hours = numbers(hoursAt);
            List<String> keyed = words(keys);
            for (int i = 0; i < asked.size(); i++) {
                List<Double> q = asked.get(i);
                assertClose(hours.get(i), table.hoursAt(q.get(0), q.get(1)), "hoursAt[" + i + "]");
                assertEquals(keyed.get(i), table.keyAt(q.get(0), q.get(1)), "keyAt[" + i + "]");
            }
        }
    }
}
