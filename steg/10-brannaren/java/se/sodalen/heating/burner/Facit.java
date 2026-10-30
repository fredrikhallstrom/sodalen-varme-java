package se.sodalen.heating.burner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reading the answer files: what Python wrote, as Java numbers, booleans and the stored dict. */
final class Facit {

    private Facit() {
    }

    /** A number written with repr; these files never leave one empty. */
    static double number(String text) {
        assertNotNull(text, "a number in the answer file");
        return Double.parseDouble(text);
    }

    /** Strictly "true" or "false"; anything else, empty included, is a broken file. */
    static boolean bool(String text) {
        return switch (text == null ? "" : text) {
            case "true" -> true;
            case "false" -> false;
            default -> fail("not a boolean in the answer file: " + text);
        };
    }

    /** Empty for None, so null; otherwise strictly "true" or "false". */
    static Boolean nullableBool(String text) {
        return text == null ? null : bool(text);
    }

    /**
     * The stored dict: empty for None, so null; the literal {} for an empty dict; otherwise
     * key=type:value entries with "|" between them, in the dict's order. The type is Python's:
     * s a text, f a float, i an int, b a bool written True/False, n None (written key=n:).
     * A LinkedHashMap, which keeps the order and takes a null value where Map.of would not.
     */
    static Map<String, Object> stored(String cell) {
        if (cell == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (cell.equals("{}")) {
            return out;
        }
        for (String entry : cell.split("\\|", -1)) {
            int equals = entry.indexOf('=');
            int colon = entry.indexOf(':', equals + 1);
            assertTrue(equals > 0 && colon > equals, "a stored entry is key=type:value: " + entry);
            out.put(entry.substring(0, equals), value(entry.substring(equals + 1, colon), entry.substring(colon + 1)));
        }
        return out;
    }

    /** One stored value from its type tag and text: String, Double, Integer, Boolean or null. */
    private static Object value(String type, String text) {
        return switch (type) {
            case "s" -> text;
            case "f" -> Double.valueOf(text);
            case "i" -> Integer.valueOf(text);
            case "b" -> switch (text) {
                case "True" -> Boolean.TRUE;
                case "False" -> Boolean.FALSE;
                default -> fail("not a Python bool in the answer file: " + text);
            };
            case "n" -> null;
            default -> fail("unknown type tag in the answer file: " + type + ":" + text);
        };
    }
}
