package se.sodalen.heating.forecast;

import java.util.List;
import java.util.Map;

// Betygsätter väderkällorna mot givaren på väggen och väger ihop dem. Offset dras av, källor
// som följs åt får inte rösta två gånger, och samma lösning ger osäkerheten.
public final class Ensemble {

    // Startgissning för en källa ingen betygsatt än, i grader i kvadrat: en och en halv grads fel.
    public static final double PRIOR_VARIANCE = 2.25;
    public static final double DEFAULT_HORIZON_SECONDS = 14.0 * 24.0 * 3600.0;   // dataclassens två veckor
    // Två källor som aldrig varit oense skulle göra matrisen singulär: inget är känt på en tusendel.
    public static final double DIAGONAL_FLOOR = 1e-3;

    public Ensemble() {
    }                   // DEFAULT_HORIZON_SECONDS, allt annat tomt

    public Ensemble(double horizonSeconds) {
    }

    public double horizon() {
        return 0.0;
    }

    public double weight() {
        return 0.0;
    }                   // avklingat antal betygsatta cykler

    public Map<String, Double> bias() {
        return null;
    }                   // avklingat medelfel per källa, i grader; kartan själv, i den ordning källorna kom

    public Map<String, Map<String, Double>> covariance() {
        return null;
    }                   // avklingad kovarians av felen utan offset, källa mot källa; kartan själv

    public void retune(double horizonSeconds) {
    }

    public boolean observe(Double truth, List<Reading> readings, double dt) {
        return false;
    }                   // betygsätt alla källor som gav en temperatur, mot givaren; true när något betygsattes

    public Weighting weights(List<String> names) {
        return null;
    }                   // de bästa vikterna för de här källorna och spridningen; lika delar när det inte går att lösa

    public Combined combine(List<Reading> readings) {
        return null;
    }                   // ett väder av många uppfattningar
}
