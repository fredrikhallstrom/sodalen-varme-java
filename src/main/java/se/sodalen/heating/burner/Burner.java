package se.sodalen.heating.burner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Brännaren på pannan och hur den ombeds elda: vilken som sitter där, vilka reläer som bär ordern,
// och vad reläerna ska göra. Regulatorn säger bara om pannan ska vara varm.
public final class Burner {

    // Brännaren byts fysiskt, inte väljs: samma två reläer tjänar den som sitter på väggen.
    public static final String BURNER_PELLETS = "pellets";
    public static final String BURNER_OIL = "oil";
    public static final List<String> BURNERS = List.of(BURNER_PELLETS, BURNER_OIL);
    // Svenska, för panelen familjen läser.
    public static final Map<String, String> BURNER_NAMES;

    // Vilka reläer ordern går genom: styrkabeln, huvudströmmen eller båda.
    public static final String PATH_CONTROL = "control";
    public static final String PATH_POWER = "power";
    public static final String PATH_BOTH = "both";
    public static final List<String> CONTROL_PATHS = List.of(PATH_CONTROL, PATH_POWER, PATH_BOTH);

    // Vad styrkabeln gör för att kalla på värme, sagt som kretsen i brännarens manual.
    public static final Map<String, String> CALL_NAMES;

    public static final Map<String, String> CONTROL_PATH_NAMES;

    // Pellets tänder långsamt och måste brinna ut; olja tänder på sekunder. start_lead, off_delay,
    // burnout_seconds, sweep_lead_seconds, call_for_heat, alarm_below; inget control_path.
    public static final Map<String, BurnerSettings> BURNER_DEFAULTS;

    // Pythons dictar har en ordning och svarar None på en okänd eller saknad nyckel. Map.of har
    // ingen ordning och kastar på get(null), så kartorna byggs som LinkedHashMap och låses sedan.
    static {
        Map<String, String> burnerNames = new LinkedHashMap<>();
        burnerNames.put(BURNER_PELLETS, "pellets");
        burnerNames.put(BURNER_OIL, "olja");
        BURNER_NAMES = Collections.unmodifiableMap(burnerNames);

        Map<String, String> callNames = new LinkedHashMap<>();
        callNames.put("on", "Sluten krets");
        callNames.put("off", "Bruten krets");
        CALL_NAMES = Collections.unmodifiableMap(callNames);

        Map<String, String> pathNames = new LinkedHashMap<>();
        pathNames.put(PATH_CONTROL, "Styrkabel");
        pathNames.put(PATH_POWER, "Huvudström");
        pathNames.put(PATH_BOTH, "Styrkabel + huvudström");
        CONTROL_PATH_NAMES = Collections.unmodifiableMap(pathNames);

        Map<String, BurnerSettings> defaults = new LinkedHashMap<>();
        defaults.put(BURNER_PELLETS, new BurnerSettings(1800.0, 3600.0, 900.0, 6.0 * 3600.0, "on", 50.0, null));
        defaults.put(BURNER_OIL, new BurnerSettings(600.0, 1800.0, 60.0, 2.0 * 3600.0, "on", 65.0, null));
        BURNER_DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    private Burner() {
    }

    public static BurnerSettings burnerSettings(String burner) {
        return null;
    }                   // Pythons standardvärde: stored None

    public static BurnerSettings burnerSettings(String burner, Map<String, ?> stored) {
        return null;
    }                   // brännarens standardvärden med det sparade ovanpå; trasigt sparat faller tillbaka

    public static Relays relaysInUse(String controlPath, boolean hasControl, boolean hasPower) {
        return null;
    }                   // Pythons standardvärden: sweeping False, overheated False

    public static Relays relaysInUse(String controlPath, boolean hasControl, boolean hasPower,
                                     boolean sweeping, boolean overheated) {
        return null;
    }                   // vilka reläer som bär cykelns order: den valda vägen, sotningen tar strömmen, överhettning allt

    public static boolean relayCallsForHeat(String callForHeat, boolean call) {
        return false;
    }                   // åt vilket håll reläet ska stå för att call ska nå brännaren

    public static HeatSourcePlan planHeatSource(boolean wanted, double secondsStopped, boolean hasControl,
                                                boolean hasPower, double burnoutSeconds) {
        return null;
    }                   // Pythons standardvärde: at_once False

    public static HeatSourcePlan planHeatSource(boolean wanted, double secondsStopped, boolean hasControl,
                                                boolean hasPower, double burnoutSeconds, boolean atOnce) {
        return null;
    }                   // "värm pannan / inte" till vad de två reläerna ska göra; stoppet går genom styrkabeln först
}
