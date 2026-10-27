package se.sodalen.heating.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.sodalen.heating.house.Conditions;
import se.sodalen.heating.house.Forecast;

/** Reading the answer file: what Python wrote, as Java numbers, readings and maps. */
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

    /** The ops of one row, "|" between them; the last one is never empty. */
    static String[] ops(String cell) {
        return cell == null || cell.isEmpty() ? new String[0] : cell.split("\\|");
    }

    /** The answers, one per op that answers; the last one may be empty (a stand-in that found nothing) and is kept. */
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

    /** "smhi:1.5:3.2::40.0" is smhi's reading: temperature, wind, bearing, cloud, empty between the colons for None. */
    static Reading reading(String text) {
        String[] parts = text.split(":", -1);
        return new Reading(parts[0], number(parts[1]), number(parts[2]), number(parts[3]), number(parts[4]));
    }

    /** "name:t:w:b:c/name:t:w:b:c" as the readings in that order; empty is no readings at all. */
    static List<Reading> readings(String cell) {
        List<Reading> out = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split("/", -1)) {
                out.add(reading(part));
            }
        }
        return out;
    }

    /** "smhi/yr/met" as the names in that order; empty is nobody. */
    static List<String> names(String cell) {
        return cell == null || cell.isEmpty() ? List.of() : List.of(cell.split("/", -1));
    }

    /** "hours:outdoor/hours:outdoor" as the weather ahead; empty is no forecast at all. */
    static List<Forecast> standin(String cell) {
        List<Forecast> slots = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String slot : cell.split("/", -1)) {
                String[] parts = slot.split(":", -1);
                slots.add(new Forecast(number(parts[0]), new Conditions(number(parts[1]))));
            }
        }
        return slots;
    }

    /** "name:value/name:value" as a map in that order; empty is an empty map. */
    static Map<String, Double> map(String cell) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split("/", -1)) {
                String[] parts = part.split(":", -1);
                out.put(parts[0], number(parts[1]));
            }
        }
        return out;
    }

    /** "0.3/0.7" as the weights Python answered for these names, in the order they were asked; empty is empty. */
    static Map<String, Double> weights(List<String> names, String cell) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!names.isEmpty()) {
            String[] values = cell.split("/", -1);
            assertEquals(names.size(), values.length, "how many weights");
            for (int i = 0; i < names.size(); i++) {
                out.put(names.get(i), number(values[i]));
            }
        }
        return out;
    }

    /** "first:second:value/..." as the covariance, rows and columns in that order; empty is empty. */
    static Map<String, Map<String, Double>> covariance(String cell) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split("/", -1)) {
                String[] parts = part.split(":", -1);
                out.computeIfAbsent(parts[0], first -> new LinkedHashMap<>()).put(parts[1], number(parts[2]));
            }
        }
        return out;
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
            assertNotNull(actual, what);
            assertEquals(expected, actual, close * Math.max(1.0, Math.abs(expected)), what);
        }
    }

    /** The same keys in the same order, every value close. */
    static void assertCloseMap(Map<String, Double> expected, Map<String, Double> actual, double close, String what) {
        assertNotNull(actual, what);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()), what + ": keys in order");
        expected.forEach((key, value) -> assertClose(value, actual.get(key), close, what + "[" + key + "]"));
    }

    /** The same rows in the same order, each with the same columns in the same order, every value close. */
    static void assertCloseCovariance(Map<String, Map<String, Double>> expected,
                                      Map<String, Map<String, Double>> actual, double close, String what) {
        assertNotNull(actual, what);
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()), what + ": rows in order");
        expected.forEach((first, row) -> assertCloseMap(row, actual.get(first), close, what + "[" + first + "]"));
    }

    /**
     * A bearing is an angle: 360 and 0 are the same direction, and a mean a hair west of north is
     * 360.0 in Python (its % never goes negative) where Java's own % would say -0.0 or 0.0. It
     * still has to be a bearing, 0 to 360: a westerly answered as -90 is Java's % left as it is.
     */
    static void assertSameBearing(Double expected, Double actual, double close, String what) {
        if (expected == null) {
            assertNull(actual, what);
            return;
        }
        assertNotNull(actual, what);
        assertTrue(0.0 <= actual && actual <= 360.0, () -> what + ": a bearing is 0 to 360, not " + actual);
        double around = Math.abs(expected - actual) % 360.0;
        double apart = Math.min(around, 360.0 - around);
        assertTrue(apart <= close * Math.max(1.0, Math.abs(expected)),
                () -> what + ": expected <" + expected + "> but was <" + actual + ">, "
                        + apart + " apart on the circle");
    }
}
