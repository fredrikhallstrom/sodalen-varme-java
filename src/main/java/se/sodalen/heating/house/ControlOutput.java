package se.sodalen.heating.house;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Vad cykeln bestämde, och nog av skälen för en instrumentpanel: "ventilen står på 62 %" är ingen
// diagnos, "kurvan bad om 44, deltat tog 4" är det. Publika fält i Pythons ordning med Pythons
// standardvärden; listorna och kartan är egna per instans (Pythons default_factory).
public class ControlOutput {

    public Double position = null;
    public Double commandPosition = null;       // null när inget ska skickas till ventilen den här cykeln
    public Double flowSetpoint = null;
    public Double failsafeFlow = null;          // tilloppet Shellyns vakthund håller om HA tystnar
    public Double flowError = null;
    public Double curveFlow = null;
    public double roomTrim = 0.0;
    public double overrideTrim = 0.0;
    public double deltaTrim = 0.0;
    public List<FusedSensor> indoorReadings = new ArrayList<>();
    public Double indoorFused = null;
    public Double indoorSlow = null;            // samma sak på tvåtimmarsfiltret, det inläraren läser
    public Double outdoorFiltered = null;
    public Double outdoorSummer = null;
    public Double delta = null;
    public Double deltaExpected = null;
    public String deltaQuiet = "";              // en av DELTA_QUIET_*, eller "" medan termen arbetar
    public Double valveMapPosition = null;      // var ventilkartan hade satt ventilen; visas, följs aldrig
    public double deltaWeight = 0.0;
    public boolean radiatorsStarved = false;
    public Double deltaReadableFrom = null;
    public Double deltaRead = null;
    public Double deltaWarmingLeft = null;
    public double demand = 0.0;
    public double effectiveSlope = 0.0;
    public double effectiveKp = 0.0;
    public boolean learned = false;
    public String mode = "idle";
    public String reason = "";
    public Boolean heatSource = null;           // null när ingen värmekälla är konfigurerad: inget att säga, inte "av"
    public boolean burnerForHotWater = false;
    public boolean boilerOverheated = false;
    public List<String> degraded = new ArrayList<>();        // givare som saknas eller är gamla
    public List<String> flags = new ArrayList<>();           // reglerbeslut värda att visa
    public Map<String, LearnerStatus> learning = new LinkedHashMap<>();   // varje lärares utlåtande, per LEARN_*-nyckel

    // --- förutseende avstängning ---
    public Double coastHours = null;
    public boolean coastKnown = false;
    public Double coastUncertainty = null;
    public Double coastReach = null;
    public String coastTrust = "";              // varför modellen inte litas på än, på svenska; "" när den gör det
    public Double coastShuntThreshold = null;
    public Double coastBurnerThreshold = null;
    public double coastAllowance = 0.0;
    public double coastMargin = 0.0;
    public Double coastTurnHours = null;
    public Double coastLearningHold = null;
    public boolean coastShunt = false;
    public boolean coastBurner = false;
}
