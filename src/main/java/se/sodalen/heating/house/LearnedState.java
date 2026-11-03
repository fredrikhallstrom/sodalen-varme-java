package se.sodalen.heating.house;

import java.util.LinkedHashMap;
import java.util.Map;

import se.sodalen.heating.estimate.DutyWatch;
import se.sodalen.heating.estimate.Ewma;
import se.sodalen.heating.estimate.Regression;

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

    public void forget(Tuning tuning) {
    }                   // kasta det lärda (balans, lutning, böj, förstärkning, nya deltaScale/starved/curveFit/levelFit/gainFit, learnHours), behåll det mätta; sist retune

    public void retune(Tuning tuning) {
    }                   // setSeconds/setHorizon på alla filter och anpassningar från tuningen, sunTerm.fit med

    public Map<String, Object> asDict() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance_shift", balanceShift);
        result.put("slope_trim", slopeTrim);
        result.put("bend", bend);
        result.put("process_gain", processGain);
        result.put("delta_scale", deltaScale);
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

    public static LearnedState fromDict(Map<String, ?> data, Tuning tuning) {
        return null;
    }                   // Pythons from_dict rad för rad, med migreringarna shortfall_cold, seconds_shortfall_cold och curve_offset; sist retune
}
