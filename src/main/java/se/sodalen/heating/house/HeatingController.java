package se.sodalen.heating.house;

import se.sodalen.heating.curve.CurveFit;
import se.sodalen.heating.curve.PointTable;
import se.sodalen.heating.forecast.Ensemble;
import se.sodalen.heating.valve.ValveMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static se.sodalen.heating.house.Model.ROLE_SOLAR;
import static se.sodalen.heating.house.Thermal.solarIndex;

// Den yttre slingan: vilken tilloppstemperatur huset vill ha just nu (controller.py). Kaskadreglering,
// som en shuntventil vill ha: den långsamma slingan gör väder och inomhustemperatur till ett
// börvärde för tilloppet, den snabba (pid) gör felet i det till ett ventilläge. Ett hus: håller
// tuningen och det lärda, och stegar på en timer. Steg 12 är grunden; step() och det den anropar
// kommer i steg 13-20.
public class HeatingController {

    // Klockan varje orsak till ett kallt hus väntar på (hålls på noll medan huset fortfarande är
    // under mål efter den), och inlärningskortets kod då.
    public static final Map<String, String> COLD_CLOCKS;
    public static final Map<String, String> COLD_WHY;

    // Hur _evidence_gate bedömer solen.
    public static final String SUN_RULE_LIGHT = "light";
    public static final String SUN_RULE_TERM = "term";
    public static final String SUN_RULE_NONE = "none";

    // Radiatorvarningen går upp vid Tuning.starvedShare och ner först under den här andelen av den.
    // En andel som pendlar på strecket skulle annars slå på och av varningen var och varannan cykel,
    // och pushen till föräldrarna med den.
    public static final double STARVED_RELEASE = 0.8;

    static {
        Map<String, String> clocks = new LinkedHashMap<>();
        clocks.put("sweep", "seconds_since_sweep");
        clocks.put("coast", "seconds_since_coast");
        clocks.put("shortfall", "seconds_since_shortfall");
        clocks.put("off", "seconds_since_off");
        COLD_CLOCKS = Collections.unmodifiableMap(clocks);

        Map<String, String> why = new LinkedHashMap<>();
        why.put("sweep", "sweep_cold");
        why.put("coast", "coast_cold");
        why.put("shortfall", "shortfall_cold");
        why.put("off", "off_cold");
        COLD_WHY = Collections.unmodifiableMap(why);
    }

    public Tuning tuning;
    public LearnedState state;
    // Byggnaden och prognoskällorna bor här i stället för i LearnedState, men sparas bredvid den.
    public HouseModel house;
    public Ensemble ensemble;
    public ValveMap valveMap;               // lär sig bara i bakgrunden
    public PointTable points;               // kurvtabellen
    public CurveFit tableFit = null;        // tabellens senaste anpassning; ingen före den första
    public Balance houseBalance = null;     // husmodellens balans, och nattens
    public Balance houseNightBalance = null;
    double sinceFit = Double.POSITIVE_INFINITY;     // Pythons _since_fit: sekunder sedan tabellen anpassades
    boolean starved = false;                        // Pythons _starved: om radiatorvarningen är uppe
    double burnerLead = 1800.0;                     // Pythons _burner_lead, med getattr:s standardvärde

    public HeatingController(Tuning tuning) {
        this(tuning, null, null, null, null, null);
    }                   // Pythons standardvärden: state, house, ensemble, valve_map och points None

    public HeatingController(Tuning tuning, LearnedState state, HouseModel house, Ensemble ensemble,
                             ValveMap valveMap, PointTable points) {
        this.tuning = tuning;
        if (state == null) {
            state = new LearnedState();
            state.processGain = tuning.gainReference;
        }
        this.state = state;
        if (house == null) {
            house = new HouseModel();
        }
        this.house = house;
        if (ensemble == null) {
            ensemble = new Ensemble();
        }
        this.ensemble = ensemble;
        if (valveMap == null) {
            valveMap = new ValveMap();
        }
        this.valveMap = valveMap;
        if (points == null) {
            points = new PointTable();
        }
        this.points = points;

        this.state.retune(tuning);
        this.retuneLearners();
    }                   // null ger ett nytt standardobjekt (en ny LearnedState med tuningens gainReference); sist state.retune(tuning) och retuneLearners()

    static double deadbanded(double error, double deadband) {

        if (deadband <= 0.0) {
            return error;
        }

        if (error > deadband) {
            return error - deadband;
        }

        if (error < -deadband) {
            return error + deadband;
        }

        return 0.0;
    }                   // felet som PID:en ser det: ingen zon (<= 0) → felet; utanför zonen minus zonen; i och på kanten 0

    public static double sunlightNow(ControlInputs inputs) {

        Double light = inputs.sunlight;

        if (light != null && Double.isFinite(light)) {
            return Math.max(0.0, light);
        }

        return Thermal.SUNLIGHT_PER_INDEX * solarIndex(inputs.sunElevation, inputs.cloud);
    }                   // solen på väggarna nu, kW/m2: inputs.sunlight om det är ett ändligt tal (aldrig under 0), annars SUNLIGHT_PER_INDEX * solarIndex(sunElevation, cloud)

    public static IndoorFusion fuseIndoor(List<SensorReading> readings, Tuning tuning, Double flow) {

        List<FusedSensor> details = new ArrayList<>();

        for (SensorReading reading : readings) {
            double weight = Math.max(0.0, reading.weight());
            Double value = null;
            if (reading.temperature() != null) {
                value = reading.temperature() + reading.offset();
                if (flow != null) {
                    value -= reading.stratification() * Math.max(0.0, flow - tuning.roomTarget);
                }
            }
            details.add(
                    new FusedSensor(
                            reading.key(),
                            reading.role(),
                            reading.temperature(),
                            value,
                            weight,
                            false,
                            reading.offset(),
                            reading.active(),
                            reading.featured()
                    )
            );
        }

        List<FusedSensor> contributing = new ArrayList<>();
        for (FusedSensor item : details) {
            if (item.contributed()) {
                contributing.add(item);
            }
        }

        if (contributing.isEmpty()) {
            return new IndoorFusion(null, details);
        }

        List<FusedSensor> others = new ArrayList<>();
        for (FusedSensor item : contributing) {
            if (!item.role().equals(ROLE_SOLAR)) {
                others.add(item);
            }
        }

        List<FusedSensor> pool = others.isEmpty() ? contributing : others;

        double sumWeight = 0.0;
        double sumCorrected = 0.0;
        for (FusedSensor item : pool) {
            sumWeight += item.weight();
            sumCorrected += item.corrected() * item.weight();
        }
        double reference = sumCorrected / sumWeight;

        double total = 0.0;
        double totalWeight = 0.0;

        for (int i = 0; i < details.size(); i++) {
            FusedSensor item = details.get(i);
            if (!item.contributed()) {
                continue;
            }
            double value = item.corrected();
            if (item.role().equals(ROLE_SOLAR) && !others.isEmpty()) {
                double capped = Math.min(value, reference + tuning.solarClamp);
                if (capped < value) {
                    details.set(i, new FusedSensor(
                            item.key(),
                            item.role(),
                            item.raw(),
                            item.corrected(),
                            item.weight(),
                            true,
                            item.offset(),
                            item.active(),
                            item.featured()
                            )
                    );
                }
                value = capped;
            }
            total += value * item.weight();
            totalWeight += item.weight();
        }

        return new IndoorFusion(total / totalWeight, details);
    }                   // inomhusgivarna till ett tal: offset och skiktning per avläsning, solrummet hållet ovanifrån mot resten av huset; en FusedSensor per avläsning

    static double toward(double value, double goal, double step) {
        return value + clamp(goal - value, -step, step);
    }                   // value flyttat mot goal med högst step

    static double risingSlowly(Double last, double wanted, double dt, double seconds) {

        if (last == null || wanted <= last || seconds <= 0.0) {
            return wanted;
        }
        return last + (wanted - last) * (1.0 - Math.exp(-Math.max(0.0, dt / seconds)));
    }                   // wanted om det inte är mer än last; annars last mot wanted med tidskonstanten seconds. Inget last, eller ingen tidskonstant: wanted

    static double clamp(double value, double low, double high) {
        return Math.min(high, Math.max(low, value));
    }                   // min(high, max(low, value)); inte Math.clamp, som kastar när low > high

    public void applyTuning(Tuning tuning) {
        if (Math.abs(tuning.curveSlope - this.tuning.curveSlope) > 1e-9) {
            state.slopeTrim = 0.0;
        }

        sinceFit = Double.POSITIVE_INFINITY;
        this.tuning = tuning;
        state.retune(tuning);
        retuneLearners();
    }                   // ny tuning utan att tappa det lärda: slopeTrim nollas bara när curveSlope flyttats mer än 1e-9; sinceFit = oändligt; retune som i konstruktorn

    void retuneLearners() {

        house.retune(tuning.thermalHorizonSeconds);
        house.setDesign(
                tuning.designSpan(),
                tuning.deltaDesign,
                tuning.thermalSampleSeconds
        );
        ensemble.retune(tuning.forecastHorizonSeconds);
    }                   // house.retune(thermalHorizonSeconds), house.setDesign(designSpan(), deltaDesign, thermalSampleSeconds), ensemble.retune(forecastHorizonSeconds)

    public double burnerLeadSeconds() {
        return burnerLead;
    }                   // hur långt innan värmen behövs brännaren måste få veta; 1800 tills någon sagt annat

    public double burnerLeadSeconds(Double seconds) {
        if (seconds != null) {
            burnerLead = seconds;
        }
        return burnerLead;
    }                   // seconds sätter (null låter bli), och svaret är det som gäller

    // --- Steg 13: ute, inne, klockorna och kurvan ---------------------------------------------

    Outdoors outdoor(ControlInputs inputs, double dt, ControlOutput out) {
        return null;
    }                   // ute på två tidsskalor; ingen givare: degraded "outdoor" och prognosens tal med flaggan "outdoor_forecast"; aldrig mätt: designOutdoor för båda; sommaren utan värde: den filtrerade

    Indoors indoor(ControlInputs inputs, double dt, ControlOutput out) {
        return null;
    }                   // fuseIndoor till out.indoorReadings; några men inte alla bidrar: flaggan "indoor_partial"; inget tal: degraded "indoor" och (null, null) utan att röra filtren; annars båda filtren uppdaterade

    void timers(ControlInputs inputs, double dt, ControlOutput out) {
    }                   // klockorna i state: override/nödkörning, målbyte, sparläget (bara upp), solen (sunHeat och > learnSunlight), över mål på indoorSlow, sotning, brist/av/vakthund (upp; since_failsafe drar ner, aldrig under 0), utan tillopp (degraded "flow"), retur (degraded "return")

    double curveOutdoor(Double measured, double filtered) {
        return 0.0;
    }                   // det ute kurvan läser: den filtrerade, och i en kallfront outdoorFallLead av vägen ner mot den mätta, högst outdoorFallLimit grader

    CurvePoint curve(double outdoor) {
        return null;
    }                   // curveFlow(outdoor, effectiveSlope(), balance()) och lutningen den räknade med

    public double curvature() {
        return 0.0;
    }                   // Model.effectiveCurvature(tuning, state)

    public double effectiveSlope() {
        return 0.0;
    }                   // curveSlope + state.slopeTrim, hållen mellan slopeMin och slopeMax

    public double balance() {
        return 0.0;
    }                   // roomTarget + state.balanceShift: utetemperaturen under vilken huset behöver värme

    double curveFlow(double outdoor, double slope, double balance) {
        return 0.0;
    }                   // Curve.supply(outdoor, roomTarget, designOutdoor, slope, curvature(), balance)

    double curveFlow(double outdoor, double slope, double balance, Double curvature) {
        return 0.0;
    }                   // samma med en given böj; null är curvature()

    public double curveAt(double outdoor) {
        return 0.0;
    }                   // kurvan i bruk vid en utetemperatur, hållen till flowMin-flowMax: för att rita den

    public double curveAt(double outdoor, Double slope, Double balance, Double curvature) {
        return 0.0;
    }                   // samma med given lutning, balans och böj; null är den i bruk
}
