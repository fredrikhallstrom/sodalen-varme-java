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
        return burnerSettings(burner, null);
    }                   // Pythons standardvärde: stored None

    public static BurnerSettings burnerSettings(String burner, Map<String, ?> stored) {
        BurnerSettings values = BURNER_DEFAULTS.getOrDefault(burner, BURNER_DEFAULTS.get(BURNER_PELLETS));
        double startLead = values.startLead();
        double offDelay = values.offDelay();
        double burnoutSeconds = values.burnoutSeconds();
        double sweepLeadSeconds = values.sweepLeadSeconds();
        String callForHeat = values.callForHeat();
        double alarmBelow = values.alarmBelow();
        String controlPath = values.controlPath();


        if (stored != null) {
            for (Map.Entry<String, ?> entry : stored.entrySet()) {
                Object value = entry.getValue();
                switch (entry.getKey()) {
                    case "call_for_heat" -> callForHeat = String.valueOf(value);
                    case "control_path" -> controlPath = String.valueOf(value);
                    case "start_lead", "off_delay", "burnout_seconds", "sweep_lead_seconds", "alarm_below" -> {
                        Double number = asDouble(value);
                        if (number == null) {
                            continue;
                        }

                        switch (entry.getKey()) {
                            case "start_lead" -> startLead = number;
                            case "off_delay" -> offDelay = number;
                            case "burnout_seconds" -> burnoutSeconds = number;
                            case "sweep_lead_seconds" -> sweepLeadSeconds = number;
                            default -> alarmBelow = number;
                        }

                    }
                    default -> {
                    }
                }
            }                   // brännarens standardvärden med det sparade ovanpå; trasigt sparat faller tillbaka
        }
        return new BurnerSettings(startLead, offDelay, burnoutSeconds, sweepLeadSeconds, callForHeat, alarmBelow, controlPath);
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Relays relaysInUse(String controlPath, boolean hasControl, boolean hasPower) {
        return relaysInUse(controlPath, hasControl, hasPower, false, false);
    }                   // Pythons standardvärden: sweeping False, overheated False

    public static Relays relaysInUse(String controlPath, boolean hasControl, boolean hasPower,
                                     boolean sweeping, boolean overheated) {

        if (overheated) {
            return new Relays(hasControl, hasPower);
        }

        boolean control = hasControl && (PATH_CONTROL.equals(controlPath) || PATH_BOTH.equals(controlPath));
        boolean power = hasPower && (PATH_POWER.equals(controlPath) || PATH_BOTH.equals(controlPath) || sweeping);

        return new Relays(control, power);
    }                   // vilka reläer som bär cykelns order: den valda vägen, sotningen tar strömmen, överhettning allt

    public static boolean relayCallsForHeat(String callForHeat, boolean call) {
        return "on".equalsIgnoreCase(callForHeat) == call;
    }                   // åt vilket håll reläet ska stå för att call ska nå brännaren

    public static HeatSourcePlan planHeatSource(boolean wanted, double secondsStopped, boolean hasControl,
                                                boolean hasPower, double burnoutSeconds) {
        return planHeatSource(wanted, secondsStopped, hasControl, hasPower, burnoutSeconds, false);
    }                   // Pythons standardvärde: at_once False

    public static HeatSourcePlan planHeatSource(boolean wanted, double secondsStopped, boolean hasControl,
                                                boolean hasPower, double burnoutSeconds, boolean atOnce) {

        if (wanted) {
            return new HeatSourcePlan(hasControl ? Boolean.TRUE : null, hasPower ? Boolean.TRUE : null, false);
        }

        if (atOnce) {
            return new HeatSourcePlan(hasControl ? Boolean.FALSE : null, hasPower ? Boolean.FALSE : null, false);
        }

        if (!hasControl) {
            return new HeatSourcePlan(null, hasPower ? Boolean.FALSE : null, false);
        }

        if (!hasPower) {
            return new HeatSourcePlan(false, null, false);
        }

        if (secondsStopped >= burnoutSeconds) {
            return new HeatSourcePlan(false, false, false);
        }
        return new HeatSourcePlan(false, null, true);
    }                   // "värm pannan / inte" till vad de två reläerna ska göra; stoppet går genom styrkabeln först
}
