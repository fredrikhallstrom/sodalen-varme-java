package se.sodalen.heating.forecast;

import java.util.Map;

// Vad källorna kom fram till tillsammans. uncertainty är den sammanvägda temperaturens
// standardavvikelse i grader, och weights är vad varje källa till slut var värd.
public record Combined(Double temperature, Double wind, Double bearing, Double cloud, Double uncertainty,
                       Map<String, Double> weights, int sources) {

    public Combined() {
        this(null, null, null, null, null, Map.of(), 0);
    }                   // Pythons Combined(): tomt
}
