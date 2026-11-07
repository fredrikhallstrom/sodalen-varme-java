package se.sodalen.heating.house;

import java.util.LinkedHashMap;
import java.util.Map;

// Vad sammanvägningen gjorde av en inomhusgivare: råvärdet, det rättade, vikten, om den hölls
// tillbaka (bara solrummet, bara uppåt) och kalibreringen den fick. Rapporteras per givare, för
// "huset är 21,0" döljer vilken som avviker och hur mycket regleringen lyssnade på den.
public record FusedSensor(String key, String role, Double raw, Double corrected, double weight, boolean clamped,
                          double offset, boolean active, boolean featured) {

    // Pythons standardvärden, som en kortare konstruktor du skriver själv:
    //   FusedSensor(String key, String role, Double raw, Double corrected, double weight)   // clamped false, offset 0.0,
    //                                                                                      // active true, featured false


    public FusedSensor(String key, String role, Double raw, Double corrected, double weight) {
        this(key, role, raw, corrected, weight, false, 0.0, true, false);
    }

    public boolean contributed() {
        return active && corrected != null && weight > 0.0;
    }                   // aktiv, med ett rättat värde och en vikt över noll

    public Map<String, Object> asDict() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("key",key);
        result.put("role", role);
        result.put("raw", raw);
        result.put("corrected", corrected);
        result.put("weight", weight);
        result.put("clamped", clamped);
        result.put("offset", offset);
        result.put("active", active);
        result.put("featured", featured);

        return result;
    }                   // Pythons nycklar i Pythons ordning: key, role, raw, corrected, weight, clamped, offset, active, featured
}
