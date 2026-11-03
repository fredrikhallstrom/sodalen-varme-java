package se.sodalen.heating.house;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Datamodellens konstanter och de tre fria funktionerna i model.py. Inget Home Assistant här:
// allt reglerlagen behöver bor i de här klasserna, så att hela regleringen kan provas utan HA.
public final class Model {

    // Hur mycket varje slags inomhusgivare litas på, och hur mycket av avläsningen som är
    // skiktning i stället för rumstemperatur.
    public static final String ROLE_ROOM = "room";
    public static final String ROLE_CEILING = "ceiling";
    public static final String ROLE_SOLAR = "solar";
    public static final List<String> ROLES;
    public static final Map<String, Map<String, Double>> ROLE_DEFAULTS;   // roll → {"weight", "stratification"}

    public static final String MODE_OFF = "off";
    public static final String MODE_SUMMER = "summer";
    public static final String MODE_WARM = "warm";
    public static final String MODE_HEATING = "heating";
    public static final String MODE_HOLD = "hold";
    public static final String MODE_FAILSAFE = "failsafe";
    public static final String MODE_COAST = "coast";
    // Kört för hand: inget stopp, ventilen står där den sattes och brännaren går.
    public static final String MODE_EMERGENCY = "emergency";

    // Lärarna, som ControlOutput.learning namnger dem, i den ordning ett kort listar dem.
    public static final String LEARN_CURVE = "kurva";
    public static final String LEARN_TABLE = "tabell";
    public static final String LEARN_HOUSE = "husmodell";
    public static final String LEARN_DELTA = "delta";
    public static final String LEARN_GAIN = "ventilforstarkning";
    public static final String LEARN_VALVE_MAP = "ventilkarta";
    public static final String LEARN_SUN = "sol";                        // solens del av inomhusfelet (sunterm.py)
    public static final List<String> LEARNERS;

    // Vad ControlInputs.emergency kan säga. Allt annat är av.
    public static final String EMERGENCY_OFF = "";
    public static final String EMERGENCY_VALVE = "valve";
    public static final String EMERGENCY_FLOW = "flow";
    public static final Set<String> EMERGENCY_KINDS;

    // Lägena där ventilen är avsiktligt stängd och slingan inte reglerar något. Namngivet en gång:
    // tupeln stod utskriven på tre ställen, och ett glömt ställe hade lämnat brännaren igång.
    public static final Set<String> MODES_STOPPED;

    // Om kurvtabellen styr kurvan, eller varför inte: ett svar.
    public static final String TABLE_STEERS = "steers";
    public static final String TABLE_SWITCHED_OFF = "switched_off";
    public static final String TABLE_LEARNING_OFF = "learning_off";
    public static final String TABLE_NO_FIT = "no_fit";
    public static final String TABLE_NO_CHAIN = "no_chain";

    // Varför deltatermen är tyst: exakt ett skäl, eller inget.
    public static final String DELTA_QUIET_SENSOR = "sensor";
    public static final String DELTA_QUIET_OFF = "off";
    public static final String DELTA_QUIET_DEMAND = "demand";
    public static final String DELTA_QUIET_LUKEWARM = "lukewarm";

    // Vad som lämnar huset under mål på väg tillbaka: sotning, avstängning, värmekällan som inte
    // räckte, värmen avslagen, var och en med sin egen väntan efteråt.
    public static final List<String> COLD_CAUSES;

    // Kurvan en sparad curve_offset lärdes på: en parallellförskjutning av tilloppet, böjd med den
    // krökning standardvärdet hade då; och vädret den matchas i när inget är känt: mild höst.
    public static final double OFFSET_ERA_CURVATURE = 1.1;
    public static final double OFFSET_ERA_OUTDOOR = 10.0;

    // Pythons dictar, tupler och frozenset har en ordning och svarar lugnt på en okänd eller
    // None-nyckel. List.of/Set.of/Map.of kastar på contains(null) och get(null), så allt byggs
    // muterbart och låses sedan.
    static {
        ROLES = Collections.unmodifiableList(Arrays.asList(ROLE_ROOM, ROLE_CEILING, ROLE_SOLAR));

        Map<String, Map<String, Double>> roleDefaults = new LinkedHashMap<>();
        roleDefaults.put(ROLE_ROOM, roleDefault(1.0, 0.0));
        roleDefaults.put(ROLE_CEILING, roleDefault(0.5, 0.06));
        roleDefaults.put(ROLE_SOLAR, roleDefault(0.15, 0.0));
        ROLE_DEFAULTS = Collections.unmodifiableMap(roleDefaults);

        LEARNERS = Collections.unmodifiableList(Arrays.asList(
                LEARN_CURVE, LEARN_SUN, LEARN_TABLE, LEARN_HOUSE, LEARN_DELTA, LEARN_GAIN, LEARN_VALVE_MAP));

        EMERGENCY_KINDS = Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(EMERGENCY_VALVE, EMERGENCY_FLOW)));
        MODES_STOPPED = Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.asList(MODE_OFF, MODE_SUMMER, MODE_WARM, MODE_COAST)));

        COLD_CAUSES = Collections.unmodifiableList(Arrays.asList("sweep", "coast", "shortfall", "off"));
    }

    private static Map<String, Double> roleDefault(double weight, double stratification) {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("weight", weight);
        values.put("stratification", stratification);
        return Collections.unmodifiableMap(values);
    }

    private Model() {
    }

    public static double effectiveCurvature(Tuning tuning, LearnedState state) {
        return 0.0;
    }                   // böjen kurvan går på: state.bend när tabellen lärt en, annars tuning.curveCurvature

    static double balanceFromOffset(double offset, LearnedState state, Tuning tuning) {
        return 0.0;
    }                   // balansförskjutningen som ger huset det tillopp det hade där offseten lärdes; Pythons _balance_from_offset, rad för rad

    public static Tuning tuningFromDict(Map<String, ?> data) {
        return null;
    }                   // Pythons standardvärde: base None

    public static Tuning tuningFromDict(Map<String, ?> data, Tuning base) {
        return null;
    }                   // en ny Tuning kopierad från base (eller standard); okända nycklar hoppas över, bool-fält får bool(value), resten float(value) eller hoppas över

    public static String fieldName(String pythonName) {
        return null;
    }                   // "room_target" → "roomTarget": dela på '_', versal efter varje

    public static boolean truthy(Object value) {
        return false;
    }                   // Pythons bool(value): null, false, 0, "", tomma listor och kartor är falska; allt annat sant, "false" och NaN med
}
