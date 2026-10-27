package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

/** Reading the answer files: what Python wrote, as Java numbers. */
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

    /** "1.0,,2.5" is 1.0, null, 2.5. */
    static List<Double> numbers(String cell) {
        List<Double> values = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split(",", -1)) {
                values.add(number(part));
            }
        }
        return values;
    }

    static double[] array(String cell) {
        List<Double> values = numbers(cell);
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** "1,2|3,4" is the matrix with the rows 1, 2 and 3, 4. */
    static double[][] matrix(String cell) {
        if (cell == null || cell.isEmpty()) {
            return new double[0][];
        }
        String[] rows = cell.split("\\|", -1);
        double[][] out = new double[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            out[i] = array(rows[i]);
        }
        return out;
    }

    static String[] ops(String cell) {
        return cell == null || cell.isEmpty() ? new String[0] : cell.split("\\|");
    }

    /** The answers, one per op that answers; the last one may be empty (a balance not yet known) and is kept. */
    static String[] entries(String cell) {
        return cell == null || cell.isEmpty() ? new String[0] : cell.split("\\|", -1);
    }

    /** The comma-separated fields of one op or one answer, empty fields kept. */
    static String[] fields(String text) {
        return text == null ? new String[0] : text.split(",", -1);
    }

    /** "true", "false", or empty for None. */
    static Boolean bool(String text) {
        return text == null || text.isEmpty() ? null : Boolean.valueOf(text);
    }

    /** outdoor, wind, bearing, sun, hour from five fields in a row (the five after an indoor), empty for None. */
    static Conditions conditions(String[] fields, int from) {
        return new Conditions(number(fields[from]), number(fields[from + 1]), number(fields[from + 2]),
                number(fields[from + 3]), number(fields[from + 4]));
    }

    /** "hours:outdoor:wind:bearing:sun:hour/..." as the weather ahead; empty is no forecast at all. */
    static List<Forecast> weather(String cell) {
        List<Forecast> slots = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String slot : cell.split("/", -1)) {
                String[] parts = slot.split(":", -1);
                slots.add(new Forecast(number(parts[0]), conditions(parts, 1)));
            }
        }
        return slots;
    }

    /** Within a billionth, of the number or of one if it is smaller. */
    static void assertClose(Double expected, Double actual, String what) {
        assertClose(expected, actual, 1e-9, what);
    }

    static void assertClose(Double expected, Double actual, double close, String what) {
        if (expected == null) {
            assertNull(actual, what);
        } else if (expected.isInfinite() || expected.isNaN()) {
            assertEquals(expected, actual, what);
        } else {
            assertEquals(expected, actual, close * Math.max(1.0, Math.abs(expected)), what);
        }
    }

    /** Every number of the cell against the array, element by element; an empty cell means null. */
    static void assertCloseAll(String expected, double[] actual, double close, String what) {
        List<Double> values = numbers(expected);
        if (values.isEmpty()) {
            assertNull(actual, what);
            return;
        }
        assertNotNull(actual, what);
        assertEquals(values.size(), actual.length, what + ": how many");
        for (int i = 0; i < values.size(); i++) {
            assertClose(values.get(i), actual[i], close, what + "[" + i + "]");
        }
    }
}
