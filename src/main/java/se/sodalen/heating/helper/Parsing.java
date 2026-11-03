package se.sodalen.heating.helper;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

// Att läsa tal säkert: ur sparad JSON och från givare. Botten av stacken, utan egna beroenden.
// Varje funktion svarar med ett standardvärde hellre än att kasta: en reglering som inte startar
// är värre än en som glömt vad den lärt sig.
public final class Parsing {

    // Inget i eller kring ett hus läser utanför detta, och ett värde som gör det är en trasig givare:
    // -273 (bruten kabel) och -999 (Unis markör) faller bort.
    public static final double PLAUSIBLE_LOW = -50.0;    // PLAUSIBLE_C[0]
    public static final double PLAUSIBLE_HIGH = 110.0;   // PLAUSIBLE_C[1]
    // De fasta proberna (tillopp, retur, panna) är DS18B20 och klarar 125; pannan nådde 115,75
    // den 2026-10-06 och visades som saknad, hållen till 110.
    public static final double WIRED_MAX_C = 125.0;

    private Parsing() {
    }

    public static Map<String, ?> asMapping(Object value) {
        if (value instanceof Map) {
            return (Map<String, ?>) value;
        }
        return new LinkedHashMap<>();

    }                   // en Map ger sig själv; allt annat, null med, ger en ny, tom LinkedHashMap

    public static double asFloat(Object value, double fallback) {
        try {
            if (value instanceof Boolean) {
                return (boolean) value ? 1.0 : 0.0;
            }
            double doubleValue = Double.parseDouble(value.toString());
            if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                return fallback;
            }
            return doubleValue;

        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }                   // Pythons float(value): Number, Boolean (1/0), String via parseDouble; annars fallback, NaN och oändligt med

    public static Double asOptionalFloat(Object value) {
        return value == null ? null : asFloat(value, 0.0);
    }                   // null förblir null; allt annat går genom asFloat(value, 0.0)

    public static Double plausibleTemperature(Object raw) {
        return plausibleTemperature(raw, PLAUSIBLE_HIGH);
    }                   // Pythons standardvärde: high = PLAUSIBLE_HIGH

    public static Double plausibleTemperature(Object raw, double high) {
        double value;
            value = asFloat(raw, Double.NaN);

        if (raw == null || value < PLAUSIBLE_LOW || value > high || Double.isNaN(value)){
            return null;
        }

        return value;
    }                   // float(raw) misslyckas, utanför [PLAUSIBLE_LOW, high] eller NaN → null; annars talet

    public static Integer outdoorInUse(List<Map.Entry<String, Double>> readings) {
        return IntStream.range(0, readings.size())
                .filter(i -> readings.get(i).getValue() != null)
                .boxed()
                .min(Comparator.comparing(i -> readings.get(i).getValue()))
                .orElse(null);

    }                   // index för den kallaste som svarar (värdet inte null); lika → lägst index; ingen → null
}
