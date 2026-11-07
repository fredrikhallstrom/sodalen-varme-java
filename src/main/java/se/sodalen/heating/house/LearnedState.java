package se.sodalen.heating.house;

import se.sodalen.heating.estimate.DutyWatch;
import se.sodalen.heating.estimate.Ewma;
import se.sodalen.heating.estimate.Regression;

import java.util.LinkedHashMap;
import java.util.Map;

import static se.sodalen.heating.helper.Parsing.*;

// Vad regleringen vet om just det här huset. Sparas över omstarter: att förlora det är inte farligt,
// varje fält har ett försvarbart standardvärde, men att behålla det gör att en HA-uppdatering inte
// kostar huset en kall kväll. Publika fält i Pythons ordning med Pythons standardvärden.
public class LearnedState {

    public double balanceShift = 0.0;           // balansen som grader från rumsmålet; negativt = gratisvärme
    public double slopeTrim = 0.0;              // läggs till Tuning.curveSlope
    public Double bend = null;                  // böjen från kurvtabellen, eller null för Tunings
    public double processGain = 0.4;            // uppmätt statisk ventilförstärkning, K tillopp per % slag

    public Ewma deltaScale = new Ewma(3.0 * 24.0 * 3600.0);    // uppmätt delta över det väntade: lång horisont med flit
    public Ewma deltaFilter = new Ewma(900.0);
    public double deltaTrimHeld = 0.0;
    public double secondsSettled = 0.0;
    public double secondsUnsettled = 0.0;
    public Double lastFlowSetpoint = null;
    public double secondsSinceStop = 1e9;       // stort tills första stoppet

    public Ewma outdoor = new Ewma(3.0 * 3600.0);
    public Ewma outdoorSummer = new Ewma(24.0 * 3600.0);       // samma mätning på en säsongs tidsskala
    public Ewma indoor = new Ewma(20.0 * 60.0);
    public Ewma indoorSlow = new Ewma(2.0 * 3600.0);
    public Ewma sunHeat = new Ewma(3.0 * 3600.0);              // ljuset på väggarna som huset håller det
    public SunTerm sunTerm = new SunTerm();

    public Regression curveFit = new Regression(3.0 * 24.0 * 3600.0);
    public Regression levelFit = new Regression(12.0 * 3600.0);
    public Regression gainFit = new Regression(12.0 * 3600.0);

    // Inre slingans tillstånd, sparat av samma skäl: en omstart ska inte dumpa integralen.
    public double integral = 0.0;
    public Double lastFlow = null;
    public Double position = null;              // läget regleringen senast bad om, inte det Shellyn rapporterar

    public double secondsAdrift = 0.0;
    public double secondsOffsetSaturated = 0.0;
    public double secondsWarm = 0.0;
    public DutyWatch starved = new DutyWatch(3.0 * 24.0 * 3600.0);

    public double learnHours = 0.0;
    public double secondsSteady = 0.0;
    public double secondsLearningHold = 0.0;
    public String learningHoldCell = null;      // tabellcellen väntan fyller, "index:del av dygnet"
    public double learningHoldBase = 0.0;
    public double secondsStopped = 0.0;
    public double secondsWithoutFlow = 0.0;
    // Länge sedan, inte nyss: ett tomt minne har inte haft någon överstyrning, och noll här frös
    // inlärningen två timmar för en kontakt som inte ens är inkopplad.
    public double secondsSinceOverride = 1e6;
    public double secondsSinceCoast = 1e6;
    public double secondsSinceSun = 1e6;
    public double secondsSinceSweep = 1e6;
    public double secondsSinceShortfall = 1e6;
    public double secondsSinceOff = 1e6;
    public double secondsSinceFailsafe = 1e6;
    public double secondsOverTarget = 0.0;        // så länge inne stått mer än learnOverTarget över mål; noll när det är nere igen
    public String coldAfter = null;             // vilken av COLD_CAUSES huset ännu är under mål efter; null när det är tillbaka
    public double secondsCold = 0.0;
    public double secondsSinceTargetChange = 0.0;
    public boolean warmStopped = false;
    public boolean summerStopped = false;
    public boolean coasting = false;
    public boolean recovering = false;
    public boolean boilerOverheated = false;
    public boolean needsReseed = true;          // sätts när integralen slutat beskriva ventilen
    public Double resumePosition = null;
    public double resumeHoldLeft = 0.0;
    public Double lastFeedforward = null;
    public Double lastKp = null;

    public static LearnedState fromDict(Map<String, ?> data, Tuning tuning) {
        data = asMapping(data);
        LearnedState state = new LearnedState();

        state.balanceShift = asFloat(data.get("balance_shift"), 0.0);
        state.slopeTrim = asFloat(data.get("slope_trim"), 0.0);
        state.bend = asOptionalFloat(data.get("bend"));
        state.processGain = asFloat(data.get("process_gain"), tuning.gainReference);
        state.deltaScale = Ewma.fromDict(asMapping(data.get("delta_scale")), tuning.deltaExpectedHorizonSeconds);
        state.deltaFilter = Ewma.fromDict(asMapping(data.get("delta_filter")), tuning.deltaFilterSeconds);
        state.deltaTrimHeld = asFloat(data.get("delta_trim_held"), 0.0);
        state.secondsOverTarget = asFloat(data.get("seconds_over_target"), 0.0);
        state.secondsSettled = asFloat(data.get("seconds_settled"), 0.0);
        state.secondsUnsettled = asFloat(data.get("seconds_unsettled"), 0.0);
        state.lastFlowSetpoint = asOptionalFloat(data.get("last_flow_setpoint"));
        state.secondsSinceStop = asFloat(data.get("seconds_since_stop"), 1e9);
        state.starved = DutyWatch.fromDict(asMapping(data.get("starved")), tuning.starvedHorizonSeconds);
        state.outdoor = Ewma.fromDict(asMapping(data.get("outdoor")), tuning.outdoorFilterSeconds);
        state.outdoorSummer = Ewma.fromDict(asMapping(data.get("outdoor_summer")), tuning.summerFilterSeconds);
        state.indoor = Ewma.fromDict(asMapping(data.get("indoor")), tuning.indoorFilterSeconds);
        state.indoorSlow = Ewma.fromDict(asMapping(data.get("indoor_slow")), tuning.indoorSlowFilterSeconds);
        state.sunHeat = Ewma.fromDict(asMapping(data.get("sun_heat")), tuning.sunHeatSeconds);
        state.sunTerm = SunTerm.fromDict(asMapping(data.get("sun_term")), tuning.sunTermHorizonSeconds);
        state.curveFit = Regression.fromDict(asMapping(data.get("curve_fit")), tuning.learnHorizonSeconds);
        state.levelFit = Regression.fromDict(asMapping(data.get("level_fit")), tuning.learnLevelHorizonSeconds);
        state.gainFit = Regression.fromDict(asMapping(data.get("gain_fit")), tuning.gainHorizonSeconds);
        state.integral = asFloat(data.get("integral"), 0.0);
        state.lastFlow = asOptionalFloat(data.get("last_flow"));
        state.position = asOptionalFloat(data.get("position"));
        state.secondsAdrift = asFloat(data.get("seconds_adrift"), 0.0);
        state.secondsOffsetSaturated = asFloat(data.get("seconds_offset_saturated"), 0.0);
        state.secondsWarm = asFloat(data.get("seconds_warm"), 0.0);
        state.learnHours = asFloat(data.get("learn_hours"), 0.0);
        state.secondsSteady = asFloat(data.get("seconds_steady"), 0.0);
        state.secondsLearningHold = asFloat(data.get("seconds_learning_hold"), 0.0);
        state.learningHoldCell = data.get("learning_hold_cell") instanceof String ? (String) data.get("learning_hold_cell") : null;
        state.learningHoldBase = asFloat(data.get("learning_hold_base"), 0.0);
        state.secondsStopped = asFloat(data.get("seconds_stopped"), 0.0);
        state.secondsWithoutFlow = asFloat(data.get("seconds_without_flow"), 0.0);
        state.secondsSinceOverride = asFloat(data.get("seconds_since_override"), 1e6);
        state.secondsSinceCoast = asFloat(data.get("seconds_since_coast"), 1e6);
        state.secondsSinceSun = asFloat(data.get("seconds_since_sun"), 1e6);
        state.secondsSinceSweep = asFloat(data.get("seconds_since_sweep"), 1e6);
        state.secondsSinceShortfall = asFloat(data.get("seconds_since_shortfall"), 1e6);
        state.secondsSinceOff = asFloat(data.get("seconds_since_off"), 1e6);
        state.secondsSinceFailsafe = asFloat(data.get("seconds_since_failsafe"), 1e6);
        state.coldAfter = data.get("cold_after") instanceof String s && Model.COLD_CAUSES.contains(s)
                ? s : Model.truthy(data.get("shortfall_cold")) ? "shortfall"
                : null;
        state.secondsCold = asFloat(data.containsKey("seconds_cold") ? data.get("seconds_cold") : data.get("seconds_shortfall_cold"), 0.0);
        state.secondsSinceTargetChange = asFloat(data.get("seconds_since_target_change"), 0.0);
        state.warmStopped = Model.truthy(data.get("warm_stopped"));
        state.summerStopped = Model.truthy(data.get("summer_stopped"));
        state.coasting = Model.truthy(data.get("coasting"));
        state.recovering = Model.truthy(data.get("recovering"));
        state.boilerOverheated = Model.truthy(data.get("boiler_overheated"));
        state.needsReseed = !data.containsKey("needs_reseed") || Model.truthy(data.get("needs_reseed"));
        state.resumePosition = asOptionalFloat(data.get("resume_position"));
        state.resumeHoldLeft = asFloat(data.get("resume_hold_left"), 0.0);
        state.lastFeedforward = asOptionalFloat(data.get("last_feedforward"));
        state.lastKp = asOptionalFloat(data.get("last_kp"));

        if (!data.containsKey("balance_shift") && data.containsKey("curve_offset")) {
            state.balanceShift = Model.balanceFromOffset(asFloat(data.get("curve_offset"), 0.0), state, tuning);

        }
        state.retune(tuning);
        return state;
    }// Pythons from_dict rad för rad, med migreringarna shortfall_cold, seconds_shortfall_cold och curve_offset; sist retune

    public void forget(Tuning tuning) {
        balanceShift = 0.0;
        slopeTrim = 0.0;
        bend = null;
        processGain = tuning.gainReference;
        deltaScale = new Ewma(tuning.deltaExpectedHorizonSeconds);
        starved = new DutyWatch(tuning.starvedHorizonSeconds);
        curveFit = new Regression(tuning.learnHorizonSeconds);
        levelFit = new Regression(tuning.learnLevelHorizonSeconds);
        gainFit = new Regression(tuning.gainHorizonSeconds);
        learnHours = 0.0;
        retune(tuning);
    }                   // kasta det lärda (balans, lutning, böj, förstärkning, nya deltaScale/starved/curveFit/levelFit/gainFit, learnHours), behåll det mätta; sist retune

    public void retune(Tuning tuning) {
        deltaScale.setSeconds(tuning.deltaExpectedHorizonSeconds);
        sunHeat.setSeconds(tuning.sunHeatSeconds);
        sunTerm.fit.setHorizon(tuning.sunTermHorizonSeconds);
        deltaFilter.setSeconds(tuning.deltaFilterSeconds);
        starved.setSeconds(tuning.starvedHorizonSeconds);
        outdoor.setSeconds(tuning.outdoorFilterSeconds);
        outdoorSummer.setSeconds(tuning.summerFilterSeconds);
        indoor.setSeconds(tuning.indoorFilterSeconds);
        indoorSlow.setSeconds(tuning.indoorSlowFilterSeconds);
        curveFit.setHorizon(tuning.learnHorizonSeconds);
        levelFit.setHorizon(tuning.learnLevelHorizonSeconds);
        gainFit.setHorizon(tuning.gainHorizonSeconds);
    }                   // setSeconds/setHorizon på alla filter och anpassningar från tuningen, sunTerm.fit med

    public Map<String, Object> asDict() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance_shift", balanceShift);
        result.put("slope_trim", slopeTrim);
        result.put("bend", bend);
        result.put("process_gain", processGain);
        result.put("delta_scale", deltaScale.asDict());
        result.put("delta_filter", deltaFilter.asDict());
        result.put("delta_trim_held", deltaTrimHeld);
        result.put("seconds_settled", secondsSettled);
        result.put("seconds_unsettled", secondsUnsettled);
        result.put("last_flow_setpoint", lastFlowSetpoint);
        result.put("seconds_since_stop", secondsSinceStop);
        result.put("starved", starved.asDict());
        result.put("outdoor", outdoor.asDict());
        result.put("outdoor_summer", outdoorSummer.asDict());
        result.put("indoor", indoor.asDict());
        result.put("indoor_slow", indoorSlow.asDict());
        result.put("sun_heat", sunHeat.asDict());
        result.put("sun_term", sunTerm.asDict());
        result.put("curve_fit", curveFit.asDict());
        result.put("level_fit", levelFit.asDict());
        result.put("gain_fit", gainFit.asDict());
        result.put("integral", integral);
        result.put("last_flow", lastFlow);
        result.put("position", position);
        result.put("seconds_adrift", secondsAdrift);
        result.put("seconds_offset_saturated", secondsOffsetSaturated);
        result.put("seconds_warm", secondsWarm);
        result.put("learn_hours", learnHours);
        result.put("seconds_steady", secondsSteady);
        result.put("seconds_learning_hold", secondsLearningHold);
        result.put("learning_hold_cell", learningHoldCell);
        result.put("learning_hold_base", learningHoldBase);
        result.put("seconds_stopped", secondsStopped);
        result.put("seconds_without_flow", secondsWithoutFlow);
        result.put("seconds_since_override", secondsSinceOverride);
        result.put("seconds_since_coast", secondsSinceCoast);
        result.put("seconds_since_sun", secondsSinceSun);
        result.put("seconds_since_sweep", secondsSinceSweep);
        result.put("seconds_since_shortfall", secondsSinceShortfall);
        result.put("seconds_since_off", secondsSinceOff);
        result.put("seconds_since_failsafe", secondsSinceFailsafe);
        result.put("seconds_over_target", secondsOverTarget);
        result.put("cold_after", coldAfter);
        result.put("seconds_cold", secondsCold);
        result.put("seconds_since_target_change", secondsSinceTargetChange);
        result.put("warm_stopped", warmStopped);
        result.put("summer_stopped", summerStopped);
        result.put("coasting", coasting);
        result.put("recovering", recovering);
        result.put("boiler_overheated", boilerOverheated);
        result.put("needs_reseed", needsReseed);
        result.put("resume_position", resumePosition);
        result.put("resume_hold_left", resumeHoldLeft);
        result.put("last_feedforward", lastFeedforward);
        result.put("last_kp", lastKp);

        return result;
    }                   // Pythons 54 nycklar i Pythons ordning (LinkedHashMap), nästlade asDict() för filtren, sunTerm och anpassningarna
}
