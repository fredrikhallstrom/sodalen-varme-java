package se.sodalen.heating.house;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// Varje mening värmen säger till familjen, på ett ställe (texts.py). Regulatorn bestämmer tal och
// flaggor; hur det låter på väggskärmen och i appen bestäms här. Svenska, för det hamnar rakt på
// en skärm familjen läser. Steg 12 tar de tre regulatorn behöver nu; resten kommer med stegen
// som säger det.
public final class Texts {

    // Skälet som visas för varje läge.
    public static final Map<String, String> MODE_REASONS;
    // Nödkörningsvalets alternativ, i den ordning de erbjuds.
    public static final Map<String, String> EMERGENCY_NAMES;
    // Sägs i stället för lägets skäl medan en sotning håller brännaren av. Läget fortsätter under:
    // radiatorerna får fortfarande det som finns i pannan, så det är skälet som ändrades.
    public static final String SWEEP_REASON = "Sotning - brännaren är av tills sotningen är utförd";

    static {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put(Model.MODE_OFF, "Värmestyrningen är avstängd");
        reasons.put(Model.MODE_SUMMER, "Sommarstopp - för varmt ute");
        reasons.put(Model.MODE_WARM, "Huset är varmare än målet");
        reasons.put(Model.MODE_HEATING, "Reglerar mot beräknad tilloppstemperatur");
        reasons.put(Model.MODE_HOLD, "Tilloppsgivaren svarar inte - håller ventilläget");
        reasons.put(Model.MODE_FAILSAFE, "Tilloppsgivaren är borta - kör max värme");
        reasons.put(Model.MODE_COAST, "Huset klarar sig utan värme ett tag");
        reasons.put(Model.MODE_EMERGENCY, "Nödkörning");
        MODE_REASONS = Collections.unmodifiableMap(reasons);

        Map<String, String> names = new LinkedHashMap<>();
        names.put(Model.EMERGENCY_OFF, "Av");
        names.put(Model.EMERGENCY_VALVE, "Fast ventilläge");
        names.put(Model.EMERGENCY_FLOW, "Fast tillopp");
        EMERGENCY_NAMES = Collections.unmodifiableMap(names);
    }

    private Texts() {
    }

    public static String emergencyReason(String kind, double position, double flow) {

        if (Model.EMERGENCY_VALVE.equals(kind)) {
            return String.format("Nödkörning - fast ventilläge %.0f %%", Math.rint(position));
        }
        if (Model.EMERGENCY_FLOW.equals(kind)) {
            return String.format("Nödkörning - fast tillopp %.0f °C", Math.rint(flow));
        }
        return MODE_REASONS.get(Model.MODE_EMERGENCY);

    }                   // statusraden vid handkörning, med talet: "Nödkörning - fast ventilläge 65 %" / "... fast tillopp 35 °C"; annars MODE_REASONS för nödkörning
}
