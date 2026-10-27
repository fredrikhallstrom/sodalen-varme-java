package se.sodalen.heating.forecast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The helpers of tests/test_forecast.py: weather for the sources to be
 * wrong about, and the ways an ensemble is fed it while it scores them.
 * The noise is java.util.Random's, not Python's, so the numbers differ
 * from the Python tests' and the properties checked have to hold either
 * way.
 */
final class Feeding {

    private Feeding() {
    }

    /** Python's rng.uniform(a, b). */
    static double uniform(Random rng, double a, double b) {
        return a + (b - a) * rng.nextDouble();
    }

    /** {name: value, ...} in the order given, the way Python's dict literal reads. */
    static Map<String, Double> sources(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("names and values come in pairs");
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            map.put((String) namesAndValues[i], ((Number) namesAndValues[i + 1]).doubleValue());
        }
        return map;
    }

    static Ensemble feed(Ensemble ensemble, double[] truths, Map<String, Double> offsets) {
        return feed(ensemble, truths, offsets, null, 1L, 1800.0);
    }

    static Ensemble feed(Ensemble ensemble, double[] truths, Map<String, Double> offsets,
                         Map<String, Double> noise, long seed) {
        return feed(ensemble, truths, offsets, noise, seed, 1800.0);
    }

    /**
     * Run truths past the ensemble, each source seeing truth + its own
     * offset + its own noise, uniform in +-noise. offsets and noise are
     * keyed by source name, in the order the readings are to come; noise
     * null means none for any of them.
     */
    static Ensemble feed(Ensemble ensemble, double[] truths, Map<String, Double> offsets,
                         Map<String, Double> noise, long seed, double dt) {
        Random rng = new Random(seed);
        for (double truth : truths) {
            List<Reading> readings = new ArrayList<>();
            for (Map.Entry<String, Double> source : offsets.entrySet()) {
                double amplitude = noise == null ? 0.0 : noise.get(source.getKey());
                readings.add(new Reading(source.getKey(),
                        truth + source.getValue() + uniform(rng, -amplitude, amplitude)));
            }
            ensemble.observe(truth, readings, dt);
        }
        return ensemble;
    }

    static double[] weather() {
        return weather(400, 0L);
    }

    static double[] weather(int n) {
        return weather(n, 0L);
    }

    /** A temperature that walks up to a degree and a half a step, reported within +-25. */
    static double[] weather(int n, long seed) {
        Random rng = new Random(seed);
        double value = 5.0;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            value += uniform(rng, -1.5, 1.5);
            out[i] = Math.max(-25.0, Math.min(25.0, value));
        }
        return out;
    }

    static Ensemble twinsAndAnIndependent() {
        return twinsAndAnIndependent(5L);
    }

    /** Two sources that share their error, and one that does not, scored over 600 half hours. */
    static Ensemble twinsAndAnIndependent(long seed) {
        Random rng = new Random(seed);
        Ensemble ensemble = new Ensemble();
        for (double truth : weather(600)) {
            double shared = uniform(rng, -2.0, 2.0);
            List<Reading> readings = List.of(
                    new Reading("twin_a", truth + shared + uniform(rng, -0.05, 0.05)),
                    new Reading("twin_b", truth + shared + uniform(rng, -0.05, 0.05)),
                    new Reading("lone", truth + uniform(rng, -2.0, 2.0)));
            ensemble.observe(truth, readings, 1800.0);
        }
        return ensemble;
    }
}
