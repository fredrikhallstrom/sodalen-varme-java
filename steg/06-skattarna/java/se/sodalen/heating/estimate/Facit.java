package se.sodalen.heating.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
