package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading the answer files of step 11: the typed format a stored dict is
 * written in, and a comparison of dicts the way Python compares them.
 *
 * <p>Public, and its own name: step 7 already has a package-private
 * {@code house.Facit}, and the tests of {@code helper} and {@code estimate}
 * read the same format, so they import this one.
 *
 * <p>A dict is {@code key=type:value} entries with {@code |} between them,
 * {@code {}} for an empty dict and an empty cell for None. The types are
 * Python's: {@code s} a text (everything after the first colon, spaces
 * kept), {@code f} a float written with repr ({@code nan}, {@code inf},
 * {@code -inf} included), {@code i} an int, {@code b} a bool written
 * True/False, {@code n} None, {@code l} a list of numbers with {@code ,}
 * between them (an empty element is null, {@code l:} alone an empty list),
 * {@code m} a matrix with rows split by {@code /} and the numbers of a row
 * by {@code ,}, and {@code d} a dict one level down with {@code &} between
 * its entries and {@code ~} instead of {@code =} ({@code d:{}} is empty).
 */
public final class Stored {

    private Stored() {
    }

    /** A number written with repr; Python writes nan and inf, Java reads NaN and Infinity. Empty is null. */
    public static Double number(String text) {
        return switch (text == null ? "" : text) {
            case "" -> null;
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(text);
        };
    }

    /** A number these files never leave empty. */
    public static double required(String text) {
        assertNotNull(text, "a number in the answer file");
        return number(text);
    }

    /** An int, or null for an empty cell. */
    public static Integer integer(String text) {
        return text == null || text.isEmpty() ? null : Integer.valueOf(text);
    }

    /** Strictly "true" or "false"; anything else, empty included, is a broken file. */
    public static boolean bool(String text) {
        return switch (text == null ? "" : text) {
            case "true" -> true;
            case "false" -> false;
            default -> fail("not a boolean in the answer file: " + text);
        };
    }

    /** Empty for None, so null; otherwise strictly "true" or "false". */
    public static Boolean nullableBool(String text) {
        return text == null ? null : bool(text);
    }

    /** "1.0,,2.5" is 1.0, null, 2.5; empty is an empty list. */
    public static List<Double> numbers(String cell) {
        List<Double> values = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String part : cell.split(",", -1)) {
                values.add(number(part));
            }
        }
        return values;
    }

    /** "1,2/3,4" is the matrix with the rows 1, 2 and 3, 4; a row may be ragged or hold a null. */
    public static List<List<Double>> matrix(String cell) {
        List<List<Double>> rows = new ArrayList<>();
        if (cell != null && !cell.isEmpty()) {
            for (String row : cell.split("/", -1)) {
                rows.add(numbers(row));
            }
        }
        return rows;
    }

    /** The ops of a step 6 answer file, "op,arg,arg|op,arg|...", empty as none. */
    public static String[] ops(String cell) {
        return cell == null || cell.isEmpty() ? new String[0] : cell.split("\\|", -1);
    }

    /**
     * The stored dict: empty for None, so null; {} for an empty dict; otherwise the typed entries
     * in the dict's order. A LinkedHashMap, which keeps the order and takes a null value where
     * Map.of would not.
     */
    public static Map<String, Object> dict(String cell) {
        return dict(cell, "\\|", '=');
    }

    private static Map<String, Object> dict(String cell, String between, char equals) {
        if (cell == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (cell.equals("{}")) {
            return out;
        }
        for (String entry : cell.split(between, -1)) {
            int at = entry.indexOf(equals);
            assertTrue(at > 0, "a stored entry is key" + equals + "type:value: " + entry);
            out.put(entry.substring(0, at), typed(entry.substring(at + 1)));
        }
        return out;
    }

    /** One typed value, "type:text", as the Java object Python's value becomes; null for None. */
    public static Object typed(String cell) {
        assertNotNull(cell, "a typed value in the answer file");
        int colon = cell.indexOf(':');
        assertTrue(colon > 0, "a typed value is type:text: " + cell);
        String type = cell.substring(0, colon);
        String text = cell.substring(colon + 1);
        return switch (type) {
            case "s" -> text;
            case "f" -> number(text);
            case "i" -> integer(text);
            case "b" -> switch (text) {
                case "True" -> Boolean.TRUE;
                case "False" -> Boolean.FALSE;
                default -> fail("not a Python bool in the answer file: " + text);
            };
            case "n" -> null;
            case "l" -> numbers(text);
            case "m" -> matrix(text);
            case "d" -> dict(text, "&", '~');
            default -> fail("unknown type tag in the answer file: " + type + ":" + text);
        };
    }

    /** Within a billionth, of the number or of one if it is smaller. */
    public static void assertClose(Double expected, Double actual, String what) {
        assertClose(expected, actual, 1e-9, what);
    }

    public static void assertClose(Double expected, Double actual, double close, String what) {
        if (expected == null) {
            assertNull(actual, what);
        } else if (expected.isInfinite() || expected.isNaN()) {
            assertEquals(expected, actual, what);
        } else {
            assertNotNull(actual, what);
            assertEquals(expected, actual, close * Math.max(1.0, Math.abs(expected)), what);
        }
    }

    /**
     * Python's == on two dicts, nan equal to nan: the same keys in the same order, and every
     * value the same kind of thing - null, a bool, a text, an int, a float within {@code close}
     * (relative), a list or a dict, recursively. The sums of a fit are added up the same way
     * as in Python and match to a billionth; its residuals come out of solves and get a
     * millionth whatever {@code close} says. The message names the key that differs.
     */
    public static void assertSameDict(Map<String, ?> expected, Object actual, double close, String what) {
        assertSame(expected, actual, close, what);
    }

    /** As above, within a billionth. */
    public static void assertSameDict(Map<String, ?> expected, Object actual, String what) {
        assertSame(expected, actual, 1e-9, what);
    }

    private static void assertSame(Object expected, Object actual, double close, String path) {
        if (expected == null) {
            assertNull(actual, path + ": Python has None here");
            return;
        }
        assertNotNull(actual, path + ": Python has " + expected + " here, Java null");
        if (expected instanceof Map<?, ?> wanted) {
            assertTrue(actual instanceof Map<?, ?>, path + ": Python has a dict here, Java " + kind(actual));
            Map<?, ?> got = (Map<?, ?>) actual;
            assertEquals(List.copyOf(wanted.keySet()), List.copyOf(got.keySet()), path + ": the keys, in order");
            for (Map.Entry<?, ?> entry : wanted.entrySet()) {
                String key = String.valueOf(entry.getKey());
                assertSame(entry.getValue(), got.get(key), tolerance(key, close), path + "." + key);
            }
        } else if (expected instanceof List<?> wanted) {
            List<?> got = asList(actual, path);
            assertEquals(wanted.size(), got.size(), path + ": how many");
            for (int i = 0; i < wanted.size(); i++) {
                assertSame(wanted.get(i), got.get(i), close, path + "[" + i + "]");
            }
        } else if (expected instanceof Boolean) {
            assertTrue(actual instanceof Boolean, path + ": Python has a bool here, Java " + kind(actual));
            assertEquals(expected, actual, path);
        } else if (expected instanceof String) {
            assertTrue(actual instanceof String, path + ": Python has a text here, Java " + kind(actual));
            assertEquals(expected, actual, path);
        } else if (expected instanceof Integer || expected instanceof Long) {
            assertTrue(actual instanceof Integer || actual instanceof Long || actual instanceof Short
                    || actual instanceof Byte, path + ": Python has an int here, Java " + kind(actual));
            assertEquals(((Number) expected).longValue(), ((Number) actual).longValue(), path);
        } else if (expected instanceof Double wanted) {
            assertTrue(actual instanceof Number, path + ": Python has a float here, Java " + kind(actual));
            assertClose(wanted, ((Number) actual).doubleValue(), close, path);
        } else {
            fail(path + ": the answer file holds a " + kind(expected) + ", which this reader does not know");
        }
    }

    /** residual and residual_data come out of a solve: a millionth, like step 6. */
    private static double tolerance(String key, double close) {
        return key.equals("residual") || key.equals("residual_data") ? Math.max(close, 1e-6) : close;
    }

    private static List<?> asList(Object actual, String path) {
        if (actual instanceof List<?> list) {
            return list;
        }
        if (actual != null && actual.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(actual); i++) {
                out.add(Array.get(actual, i));
            }
            return out;
        }
        return fail(path + ": Python has a list here, Java " + kind(actual));
    }

    private static String kind(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
