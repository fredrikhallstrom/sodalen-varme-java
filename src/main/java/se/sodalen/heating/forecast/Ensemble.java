package se.sodalen.heating.forecast;

import se.sodalen.heating.estimate.MultiRegression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// Betygsätter väderkällorna mot givaren på väggen och väger ihop dem. Offset dras av, källor
// som följs åt får inte rösta två gånger, och samma lösning ger osäkerheten.
public final class Ensemble {

    // Startgissning för en källa ingen betygsatt än, i grader i kvadrat: en och en halv grads fel.
    public static final double PRIOR_VARIANCE = 2.25;
    public static final double DEFAULT_HORIZON_SECONDS = 14.0 * 24.0 * 3600.0;   // dataclassens två veckor
    // Två källor som aldrig varit oense skulle göra matrisen singulär: inget är känt på en tusendel.
    public static final double DIAGONAL_FLOOR = 1e-3;
    private double horizon;
    private double weight;
    private final Map<String, Double> bias;
    private final Map<String, Map<String, Double>> covariance;

    public Ensemble() {
        this(DEFAULT_HORIZON_SECONDS);
    }                   // DEFAULT_HORIZON_SECONDS, allt annat tomt

    public Ensemble(double horizonSeconds) {
        bias = new LinkedHashMap<>();
        covariance = new LinkedHashMap<>();
        weight = 0.0;
        horizon = horizonSeconds;
    }

    private static List<Reading> createOffered(List<Reading> readings) {
        List<Reading> offered = new ArrayList<>();
        for (Reading value : readings) {
            if (value.temperature() != null) {
                offered.add(value);
            }
        }
        return offered;
    }

    private static Double weighted(List<Reading> readings, Weighting weighting, Function<Reading, Double> field) {
        double total = 0.0;
        double share = 0.0;

        for (Reading reading : readings) {
            Double value = field.apply(reading);
            double weight = weighting.weights().getOrDefault(reading.name(), 0.0);
            if (value == null || weight <= 0.0) {
                continue;
            }
            total += weight * value;
            share += weight;
        }
        return share <= 0.0 ? null : total / share;
    }

    private Double decay(double dt) {
        if (horizon <= 0.0 || dt <= 0.0) {
            return 1.0;
        }
        double ratio = dt / horizon;

        return ratio < 30 ? Math.pow(Math.E, -ratio) : 0.0;
    }

    private double[][] matrix(List<String> names) {
        double scale = Math.max(1.0, weight);
        double[][] matrix = new double[names.size()][names.size()];

        int rowIndex = 0;
        for (String first : names) {
            double value;
            double[] row = new double[names.size()];
            int colIndex = 0;
            for (String second : names) {
                Map<String, Double> row_ = covariance.get(first);
                Double stored = row_ == null ? null : row_.get(second);
                if (stored == null) {
                    value = first.equals(second) ? PRIOR_VARIANCE : 0.0;
                } else {
                    value = stored / scale;
                }

                if (rowIndex == colIndex) {
                    value = Math.max(value, 1e-3);

                }
                row[colIndex] = value;
                colIndex++;
            }
            matrix[rowIndex] = row;
            rowIndex++;
        }
        return matrix;

    }

    public double horizon() {
        return horizon;
    }

    public double weight() {
        return weight;
    }                   // avklingat antal betygsatta cykler

    public Map<String, Double> bias() {
        return bias;
    }                   // avklingat medelfel per källa, i grader; kartan själv, i den ordning källorna kom

    public Map<String, Map<String, Double>> covariance() {
        return covariance;
    }                   // avklingad kovarians av felen utan offset, källa mot källa; kartan själv

    public void retune(double horizonSeconds) {
        horizon = horizonSeconds;
    }

    public boolean observe(Double truth, List<Reading> readings, double dt) {
        if (truth == null) {
            return false;
        }

        List<Reading> offered = createOffered(readings);
        if (offered.isEmpty()) {
            return false;
        }

        double decay = decay(dt);
        weight = weight * decay + 1.0;

        Map<String, Double> errors = new LinkedHashMap<>();
        for (Reading reading : offered) {
            double error = reading.temperature() - truth;
            double previous = bias.getOrDefault(reading.name(), error);
            double share = 1.0 / Math.max(1.0, weight);
            bias.put(reading.name(), previous + share * (error - previous));
            errors.put(reading.name(), error - bias.get(reading.name()));
        }

        for (String first : errors.keySet()) {
            Map<String, Double> row = covariance.computeIfAbsent(first, _ -> new LinkedHashMap<>());
            for (String second : errors.keySet()) {
                double previous = row.getOrDefault(second, first.equals(second) ? PRIOR_VARIANCE : 0.0);
                row.put(second, previous * decay + errors.get(first) * errors.get(second));
            }
        }


        return true;
    }                   // betygsätt alla källor som gav en temperatur, mot givaren; true när något betygsattes

    public Weighting weights(List<String> names) {

        if (names == null || names.isEmpty()) {
            return new Weighting(new LinkedHashMap<>(), null);
        }

        if (names.size() == 1) {
            double variance = matrix(names)[0][0];
            return new Weighting(Map.of(names.getFirst(), 1.0), Math.pow(variance, 0.5));
        }

        double[][] matrix = matrix(names);
        double[] ones = new double[names.size()];
        Arrays.fill(ones, 1.0);

        double[] solved = MultiRegression.solveSymmetric(matrix, ones);
        if (solved == null || Arrays.stream(solved).sum() <= 0.0) {
            double share = 1.0 / names.size();

            Map<String, Double> result = new LinkedHashMap<>();
            for (String name : names) {
                result.put(name, share);
            }
            return new Weighting(result, null);
        }

        double total = Arrays.stream(solved).sum();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            weights.put(names.get(i), solved[i] / total);
        }

        boolean anyNegative = false;
        for (double value : weights.values()) {
            if (value < 0.0) {
                anyNegative = true;
                break;
            }
        }

        if (anyNegative) {
            Map<String, Double> clamped = new LinkedHashMap<>();

            for (Map.Entry<String, Double> entry : weights.entrySet()) {
                clamped.put(entry.getKey(), Math.max(0.0, entry.getValue()));
            }
            double totalPositive = clamped.values().stream().mapToDouble(Double::doubleValue).sum();
            if (totalPositive <= 0.0) {
                double share = 1.0 / names.size();
                for (String name : names) {
                    weights.put(name, share);
                }
            } else {
                for (Map.Entry<String, Double> entry : clamped.entrySet()) {
                    weights.put(entry.getKey(), (entry.getValue() / totalPositive));
                }
            }
        }

        Double variance = total > 0.0 ? 1.0 / total : null;

        return new Weighting(weights, variance == null ? null : Math.sqrt(Math.max(0.0, variance)));
    }                   // de bästa vikterna för de här källorna och spridningen; lika delar när det inte går att lösa

    public Combined combine(List<Reading> readings) {

        List<Reading> offered = createOffered(readings);

        if (offered.isEmpty()) {
            return new Combined();
        }

        List<String> names = new ArrayList<>();
        for (Reading reading : offered) {
            names.add(reading.name());
        }

        Weighting weighting = weights(names);

        double temperature = 0.0;
        for (Reading reading : offered) {
            temperature += weighting.weights().get(reading.name()) * (reading.temperature()
                    - bias.getOrDefault(reading.name(), 0.0));
        }
        Map<String, Double> mapToReturn = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : weighting.weights().entrySet()) {
            mapToReturn.put(entry.getKey(),
                    BigDecimal.valueOf(entry.getValue()).setScale(4, RoundingMode.HALF_EVEN).doubleValue());
        }

        return new Combined(
                temperature,
                weighted(readings, weighting, Reading::wind),
                weightedBearing(readings, weighting),
                weighted(readings, weighting, Reading::cloud),
                weighting.spread(),
                mapToReturn,
                offered.size()
        );
    }                   // ett väder av många uppfattningar

    private Double weightedBearing(List<Reading> readings, Weighting weighting) {
        double east = 0.0;
        double north = 0.0;
        double share = 0.0;

        for (Reading reading : readings) {
            if (reading.bearing() == null) {
                continue;
            }

            double weight = weighting.weights().getOrDefault(reading.name(), 0.0);
            if (weight <= 0.0) {
                continue;
            }

            double angle = Math.toRadians(reading.bearing());
            east += weight * Math.sin(angle);
            north += weight * Math.cos(angle);
            share += weight;
        }

        if (share <= 0.0 || (east == 0.0 && north == 0.0)) {
            return null;
        }

        double degrees = Math.toDegrees(Math.atan2(east, north));
        double remainder = degrees % 360;
        return remainder < 0.0 ? remainder + 360 : remainder;
    }
}
