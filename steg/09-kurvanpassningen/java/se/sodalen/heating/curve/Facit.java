package se.sodalen.heating.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reading the answer file: what Python wrote, as Java numbers, bins and lists of outdoor temperatures. */
final class Facit {

    private Facit() {
    }

    /** Python writes nan and inf; Java reads NaN and Infinity. Empty is null. */
    static Double number(String text) {
        return switch (text == null ? "" : text) {
            case "" -> null;
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(text);
        };
    }

    /** "true", "false", or empty for None. */
    static Boolean bool(String text) {
        return text == null || text.isEmpty() ? null : Boolean.valueOf(text);
    }

    /**
     * The bins of one row in their order, "|" between them, each
     * "outdoor,need,hours,spread,sectors,wind,age,weight,status" with the status as
     * Python's string; empty is no bins at all. Note the file's order: weight before
     * status, the record's the other way round.
     */
    static List<Bin> bins(String cell) {
        List<Bin> out = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split("\\|", -1)) {
                out.add(bin(part));
            }
        }
        return out;
    }

    /** One bin's nine fields. */
    private static Bin bin(String text) {
        String[] field = text.split(",", -1);
        assertEquals(9, field.length, "fields of a bin: " + text);
        return new Bin(number(field[0]), number(field[1]), number(field[2]), number(field[3]),
                Integer.parseInt(field[4]), number(field[5]), number(field[6]),
                Status.valueOf(field[8].toUpperCase(Locale.ROOT)), number(field[7]));
    }

    /** "5.0/3.0/-1.0" as the numbers in that order; empty is an empty list (Python's empty tuple). */
    static List<Double> numbers(String cell) {
        List<Double> out = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split("/", -1)) {
                out.add(number(part));
            }
        }
        return out;
    }

    /** Within close of the number, or of one if the number is smaller; null expects null. */
    static void assertClose(Double expected, Double actual, double close, String what) {
        if (expected == null) {
            assertNull(actual, what);
        } else if (expected.isInfinite() || expected.isNaN()) {
            assertEquals(expected, actual, what);
        } else {
            assertNotNull(actual, what);
            assertEquals(expected, actual, close * Math.max(1.0, Math.abs(expected)), what);
        }
    }

    /** The same length, every number close, each named by its place: what[2]. */
    static void assertCloseList(List<Double> expected, List<Double> actual, double close, String what) {
        assertNotNull(actual, what);
        assertEquals(expected.size(), actual.size(), what + ": how many, " + actual);
        for (int i = 0; i < expected.size(); i++) {
            assertClose(expected.get(i), actual.get(i), close, what + "[" + i + "]");
        }
    }
}
