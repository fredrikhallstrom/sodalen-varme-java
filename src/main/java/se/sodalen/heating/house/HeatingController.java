package se.sodalen.heating.house;

import se.sodalen.heating.curve.Curve;
import se.sodalen.heating.curve.CurveFit;
import se.sodalen.heating.curve.PointTable;
import se.sodalen.heating.estimate.DutyWatch;
import se.sodalen.heating.estimate.Ewma;
import se.sodalen.heating.forecast.Ensemble;
import se.sodalen.heating.forecast.Forecasts;
import se.sodalen.heating.valve.ValveMap;

import java.util.*;

import static se.sodalen.heating.house.Model.*;
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
    ControlOutput out = null;

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

        Double outdoor = inputs.outdoor;
        if (outdoor == null) {
            out.degraded.add("outdoor");
            outdoor = Forecasts.outdoorFromForecast(inputs.forecast);

            if (outdoor != null) {
                out.flags.add("outdoor_forecast");
            }
        }
        Double filtered = state.outdoor.update(outdoor, dt);
        Double seasonal = state.outdoorSummer.update(outdoor, dt);

        if (filtered == null) {
            return new Outdoors(tuning.designOutdoor, tuning.designOutdoor);
        }

        return new Outdoors(filtered, seasonal != null ? seasonal : filtered);
    }                   // ute på två tidsskalor; ingen givare: degraded "outdoor" och prognosens tal med flaggan "outdoor_forecast"; aldrig mätt: designOutdoor för båda; sommaren utan värde: den filtrerade

    Indoors indoor(ControlInputs inputs, double dt, ControlOutput out) {

        IndoorFusion indoorFusion = fuseIndoor(inputs.indoor, tuning, inputs.flow);
        out.indoorReadings = indoorFusion.details();

        double contributing = 0.0;
        for (FusedSensor item : indoorFusion.details()) {
            if (item.contributed()) {
                contributing += 1;
            }
        }

        if (contributing != 0 && contributing < indoorFusion.details().size()) {
            out.flags.add("indoor_partial");
        }

        if (indoorFusion.temperature() == null) {
            out.degraded.add("indoor");
            return new Indoors(null, null);
        }


        return new Indoors(
                state.indoor.update(indoorFusion.temperature(), dt),
                state.indoorSlow.update(indoorFusion.temperature(), dt));
    }                   // fuseIndoor till out.indoorReadings; några men inte alla bidrar: flaggan "indoor_partial"; inget tal: degraded "indoor" och (null, null) utan att röra filtren; annars båda filtren uppdaterade

    void timers(ControlInputs inputs, double dt, ControlOutput out) {

        LearnedState stateValue = this.state;

        if (inputs.overrideActive || Model.EMERGENCY_KINDS.contains(inputs.emergency)) {
            stateValue.secondsSinceOverride = 0.0;
        } else {
            stateValue.secondsSinceOverride += dt;
        }

        if (inputs.targetChanged) {
            stateValue.secondsSinceTargetChange = 0.0;
        } else {
            stateValue.secondsSinceTargetChange += dt;
        }

        stateValue.secondsSinceCoast += dt;

        double light = sunlightNow(inputs);
        stateValue.sunHeat.update(light, dt);
        if (light > tuning.learnSunlight) {
            stateValue.secondsSinceSun = 0.0;
        } else {
            stateValue.secondsSinceSun += dt;
        }

        Double slow = stateValue.indoorSlow.value();
        if (slow != null && slow > tuning.roomTarget + tuning.learnOverTarget) {
            stateValue.secondsOverTarget += dt;
        } else {
            stateValue.secondsOverTarget = 0.0;
        }

        if (inputs.sweeping) {
            stateValue.secondsSinceSweep = 0.0;
        } else {
            stateValue.secondsSinceSweep += dt;
        }

        stateValue.secondsSinceShortfall += dt;
        stateValue.secondsSinceOff += dt;
        stateValue.secondsSinceFailsafe += dt;

        if (inputs.sinceFailsafe != null) {
            stateValue.secondsSinceFailsafe = Math.clamp(inputs.sinceFailsafe, 0.0, stateValue.secondsSinceFailsafe);
        }

        if (inputs.flow == null) {
            stateValue.secondsWithoutFlow += dt;
            out.degraded.add("flow");
        } else {
            stateValue.secondsWithoutFlow = 0.0;
        }

        if (inputs.ret == null) {
            out.degraded.add("return");
        }

    }                   // klockorna i state: override/nödkörning, målbyte, sparläget (bara upp), solen (sunHeat och > learnSunlight), över mål på indoorSlow, sotning, brist/av/vakthund (upp; since_failsafe drar ner, aldrig under 0), utan tillopp (degraded "flow"), retur (degraded "return")

    double curveOutdoor(Double measured, double filtered) {
        return measured == null || measured >= filtered ?
                filtered : filtered - tuning.outdoorFallLead * Math.min(filtered - measured, tuning.outdoorFallLimit);
    }                   // det ute kurvan läser: den filtrerade, och i en kallfront outdoorFallLead av vägen ner mot den mätta, högst outdoorFallLimit grader

    CurvePoint curve(double outdoor) {
        return new CurvePoint(curveFlow(outdoor, effectiveSlope(), balance()), effectiveSlope());
    }                   // curveFlow(outdoor, effectiveSlope(), balance()) och lutningen den räknade med

    public double curvature() {
        return effectiveCurvature(tuning, state);
    }                   // Model.effectiveCurvature(tuning, state)

    public double effectiveSlope() {
        return clamp(
                tuning.curveSlope + state.slopeTrim,
                tuning.slopeMin,
                tuning.slopeMax
        );
    }                   // curveSlope + state.slopeTrim, hållen mellan slopeMin och slopeMax

    public double balance() {
        return tuning.roomTarget + state.balanceShift;
    }                   // roomTarget + state.balanceShift: utetemperaturen under vilken huset behöver värme

    double curveFlow(double outdoor, double slope, double balance) {
        return Curve.supply(
                outdoor,
                tuning.roomTarget,
                tuning.designOutdoor,
                slope,
                curvature(),
                balance
        );
    }                   // Curve.supply(outdoor, roomTarget, designOutdoor, slope, curvature(), balance)

    double curveFlow(double outdoor, double slope, double balance, Double curvature) {
        return Curve.supply(
                outdoor,
                tuning.roomTarget,
                tuning.designOutdoor,
                slope,
                curvature == null ? this.curvature() : curvature,
                balance);
    }                   // samma med en given böj; null är curvature()

    public double curveAt(double outdoor) {
        return curveAt(outdoor, null, null, null);

    }                   // kurvan i bruk vid en utetemperatur, hållen till flowMin-flowMax: för att rita den

    public double curveAt(double outdoor, Double slope, Double balance, Double curvature) {

        double flow = curveFlow(
                outdoor,
                slope == null ? effectiveSlope() : slope,
                balance == null ? balance() : balance,
                curvature
        );

        return tuning.clampFlow(flow);
    }                   // samma med given lutning, balans och böj; null är den i bruk

    // --- Steg 14: deltat och radiatorvakten ------------------------------------------------------

    Delta delta(ControlInputs inputs, Double indoor, double demand, ControlOutput out) {
        return delta(inputs, indoor, demand, out, 0.0, null, null);
    }                   // Pythons _delta med standardvärdena: dt 0.0, baseFlow null, room null

    Delta delta(ControlInputs inputs, Double indoor, double demand, ControlOutput out,
                double dt, Double baseFlow, Double room) {

        if (inputs.flow == null || inputs.ret == null) {
            out.deltaQuiet = DELTA_QUIET_SENSOR;
            forgetDelta();
            return new Delta(null, null, 0.0);
        }

        double delta = inputs.flow - inputs.ret;

        if (!tuning.deltaEnabled) {
            out.deltaQuiet = DELTA_QUIET_OFF;
            forgetDelta();
            return new Delta(delta, null, 0.0);
        }

        if (demand < tuning.deltaMinDemand) {
            out.deltaQuiet = DELTA_QUIET_DEMAND;
            forgetDelta();
            return new Delta(delta, null, 0.0);
        }

        if (room == null) {
            room = indoor;
        }
        double weight = deltaWeight(
                baseFlow == null ? inputs.flow : baseFlow,
                room
        );
        out.deltaWeight = weight;
        if (weight <= 0.0) {
            out.deltaQuiet = DELTA_QUIET_LUKEWARM;
            forgetDelta();
            out.deltaReadableFrom = (
                    (room == null ? tuning.roomTarget : room) + tuning.deltaMinOvertemp
            );
            return new Delta(delta, null, 0.0);
        }

        Double scale = state.deltaScale.value();

        double expected = Math.max(
                1.0, tuning.deltaDesign * Math.min(1.2, demand) * (scale == null ? 1.0 : scale)
        );

        double trim = deltaTrim(inputs, delta, expected, weight, dt, out);

        if (indoor != null) {
            if (trim < 0.0 && indoor < tuning.roomTarget - tuning.deltaColdGuard) {
                trim = 0.0;
                out.flags.add("delta_held_cold");
            } else if (trim > 0.0 && indoor > tuning.roomTarget + tuning.deltaColdGuard) {
                trim = 0.0;
            }
        }

        return new Delta(delta, expected, trim);

    }                   // korrigeringen ur hur mycket värme radiatorerna tar; tyst (DELTA_QUIET_*) utan givare, avstängd, lågt behov eller ljummet tillopp; inneslingan har sista ordet åt båda håll

    void said(String key, boolean learning, String why, Double value) {

        if (out != null) {
            out.learning.put(key, new LearnerStatus(learning, why, value));
        }
    }                   // en lärares utlåtande den här cykeln i out.learning; utan cykel (out == null) sägs ingenting

    void forgetDelta() {

        state.deltaFilter = new Ewma(state.deltaFilter.seconds(), null);
        state.deltaTrimHeld = 0.0;
        state.secondsSettled = 0.0;
        state.secondsUnsettled = 0.0;

    }                   // deltafiltret tomt, den hållna korrigeringen 0, settled och unsettled 0

    double deltaTrim(ControlInputs inputs, double delta, double expected, double weight, double dt, ControlOutput out) {

        if (state.secondsSinceStop < tuning.deltaWarmupSeconds) {
            state.deltaFilter = new Ewma(tuning.deltaFilterSeconds, null);
            state.deltaTrimHeld = 0.0;
            state.secondsSettled = 0.0;
            out.deltaWarmingLeft = tuning.deltaWarmupSeconds - state.secondsSinceStop;
            out.flags.add("delta_warming");
            return 0.0;
        }

        Double setpoint = state.lastFlowSetpoint;

        if (setpoint != null && Math.abs(inputs.flow - setpoint) <= tuning.deltaSettleBand) {
            state.secondsSettled += dt;
        } else {
            state.secondsSettled = 0.0;
        }

        if (state.secondsSettled < tuning.deltaSettleSeconds) {
            state.secondsUnsettled += dt;
            if (state.secondsUnsettled >= tuning.deltaHoldMaxSeconds) {
                state.deltaFilter = new Ewma(tuning.deltaFilterSeconds, null);
                state.deltaTrimHeld = 0.0;
                return 0.0;
            }
            out.flags.add("delta_held_moving");
            out.deltaRead = state.deltaFilter.value();
            return state.deltaTrimHeld;
        }

        state.secondsUnsettled = 0.0;
        double read = state.deltaFilter.update(delta, dt);
        out.deltaRead = read;
        state.deltaTrimHeld = weight * clamp(
                -tuning.deltaGain * (expected - read),
                -tuning.deltaLowerLimit,
                tuning.deltaRaiseLimit
        );

        return state.deltaTrimHeld;
    }                   // korrigeringen, dömd bara när tilloppet står stilla; uppvärmning efter stopp: 0 och "delta_warming"; på väg: den hållna, i högst deltaHoldMaxSeconds

    void watchRadiators(String mode, Double indoor, Double delta, Double expected, double dt, ControlOutput out) {
        watchRadiators(mode, indoor, delta, expected, dt, out, false);
    }                   // Pythons _watch_radiators med sweeping False

    void watchRadiators(String mode, Double indoor, Double delta, Double expected, double dt, ControlOutput out,
                        boolean sweeping) {
        boolean judged = MODE_HEATING.equals(mode)
                && !sweeping
                && state.secondsSinceSweep >= tuning.learnAfterOverrideSeconds
                && out.deltaWeight >= 1.0
                && indoor != null
                && delta != null
                && expected != null;

        Boolean held = null;

        if (judged) {
            held = expected - delta >= tuning.starvedMargin * expected
                    && indoor < tuning.roomTarget - tuning.deltaColdGuard;
        }

        state.starved.update(held, dt);
        double line = tuning.starvedShare * (starved ? STARVED_RELEASE : 1.0);
        starved = state.starved.hours() >= tuning.starvedMinHours
                && (state.starved.share() == null ? 0.0 : state.starved.share()) >= line;
        out.radiatorsStarved = starved;
        if (out.radiatorsStarved) {
            out.flags.add("radiators_starved");
        }


    }                   // radiatorer som tar för lite värme ur ett kallt hus; döms bara i värmeläge, utan sotning, med fullt deltavikt; varningen upp vid starvedShare, ned först under STARVED_RELEASE av den

    double deltaWeight(double supply, Double room) {
        double over = supply - (room == null ? tuning.roomTarget : room);
        if (tuning.deltaFade <= 0.0) {
            return over >= tuning.deltaMinOvertemp ? 1.0 : 0.0;
        }

        return clamp((over - tuning.deltaMinOvertemp) / tuning.deltaFade, 0.0, 1.0);
    }                   // hur mycket deltat är värt, 0 till 1, ur tilloppet över rummet: en ramp över deltaFade från deltaMinOvertemp; deltaFade <= 0 är en brytare

    // --- Steg 15: läget och värmekällan -----------------------------------------------------------

    String mode(ControlInputs inputs, double outdoorSummer, Double indoor) {

        if (!inputs.heatingEnabled) {
            state.summerStopped = false;
            state.warmStopped = false;
            return MODE_OFF;
        }

        if (state.summerStopped) {
            if (outdoorSummer < tuning.summerOutdoor - tuning.summerHysteresis) {
                state.summerStopped = false;
            }
        } else if (outdoorSummer >= tuning.summerOutdoor) {
                state.summerStopped = true;
        }

        if (state.summerStopped) {
            return MODE_SUMMER;
        }

        if (indoor == null) {
            state.warmStopped = false;
        } else if (state.warmStopped) {
            if (indoor < tuning.roomTarget + tuning.warmStopMargin - tuning.warmStopHysteresis) {
                state.warmStopped = false;
            }
        } else if (indoor > tuning.roomTarget + tuning.warmStopMargin) {
            state.warmStopped = true;
        }

        if (state.warmStopped) {
            return MODE_WARM;
        }

        if (inputs.flow == null) {
            if (state.secondsWithoutFlow >= tuning.failsafeAfterSeconds) {
                return MODE_FAILSAFE;
            }
            return MODE_HOLD;
        }

        return MODE_HEATING;
    }                   // vilket stopp som gäller, om något: MODE_OFF, MODE_SUMMER (dygnssnittet med hysteres), MODE_WARM (inne över mål + marginal, med hysteres), MODE_FAILSAFE/MODE_HOLD utan tilloppsgivare, annars MODE_HEATING

    Double coldBoilerCap(ControlInputs inputs) {

        if (tuning.coldBoilerBand <= 0.0 || inputs.boiler == null || inputs.boilerLimit == null) {
            return null;
        }

        double over = inputs.boiler - inputs.boilerLimit - tuning.coldBoilerClearance;
        if (over >= tuning.coldBoilerBand) {
            return null;
        }

        double floor = clamp(tuning.coldBoilerFloor, 0.0, 100.0);

        return floor + (100.0 - floor) * clamp(over / tuning.coldBoilerBand, 0.0, 1.0);
    }                   // pannskyddet: hur långt ventilen får öppna i procent för pannans skull, null när pannan är varm nog (eller band <= 0, eller boiler/boilerLimit saknas); en linje från coldBoilerFloor till 100 över coldBoilerBand

    boolean boilerOverheated(Double boiler) {

        if (boiler != null) {
            if (boiler >= tuning.boilerMax) {
                state.boilerOverheated = true;
            } else if (boiler <= tuning.boilerMaxRelease) {
                state.boilerOverheated = false;
            }
        }

        return state.boilerOverheated;
    }                   // för het för brännaren: slår till vid boilerMax, släpper vid boilerMaxRelease, hålls genom en saknad mätning

    boolean heatSource(String mode, double dt, boolean hotWater, ControlOutput out) {
        return heatSource(mode, dt, hotWater, out, false);
    }                   // Pythons _heat_source med sweeping False

    boolean heatSource(String mode, double dt, boolean hotWater, ControlOutput out, boolean sweeping) {

        boolean stopped = MODES_STOPPED.contains(mode);
        if (stopped) {
            state.secondsStopped += dt;
        } else {
            state.secondsStopped = 0.0;
        }

        if (state.boilerOverheated) {
            out.flags.add("boiler_overheat");
            return false;
        }

        if (sweeping) {
            out.flags.add("sweeping");
            return false;
        }

        if (hotWater) {
            out.flags.add("hot_water");
            out.burnerForHotWater = !burnerForHeating(mode, out, stopped);
            return true;
        }

        if (MODE_WARM.equals(mode) && out.coastBurner) {
            out.flags.add("coast_burner");
        }

        return burnerForHeating(mode, out, stopped);
    }                   // ska brännaren gå: secondsStopped räknas i stoppade lägen; överhettad panna ("boiler_overheat") och sotning ("sweeping") går före allt, varmvattnet ("hot_water") före varje stopp, sedan burnerForHeating

    boolean burnerForHeating(String mode, ControlOutput out, boolean stopped) {

        if (MODE_COAST.equals(mode)) {
            return !out.coastBurner;
        }

        if (!stopped) {
            return true;
        }

        if (MODE_WARM.equals(mode) && out.coastBurner) {
            return false;
        }

        boolean maySwitchOff = (MODE_SUMMER.equals(mode) && tuning.heatSourceOffInSummer)
                || (MODE_WARM.equals(mode) && tuning.heatSourceOffWhenIdle)
                || (MODE_OFF.equals(mode) && tuning.heatSourceOffWhenDisabled);

        if (!maySwitchOff) {
            return true;
        }

        return state.secondsStopped < tuning.heatSourceOffDelay;
    }                   // vill radiatorerna ha brännaren, varmvattnet oräknat: coast = inte coastBurner; inte stoppat = ja; varm med coastBurner = nej; får stängas av enligt inställningen för läget, och först efter heatSourceOffDelay
}
