package se.sodalen.heating.house;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * The helpers of tests/test_thermal.py: weather to teach by, and the ways a
 * house is run through it while the model watches.
 */
final class Teaching {

    private Teaching() {
    }

    /** The weather at an hour: {outdoor, wind, bearing, sun}. */
    @FunctionalInterface
    interface Weather {
        double[] at(double hour);
    }

    /** A model and the house it was taught by. */
    record Taught(HouseModel model, WeatherHouse house) {
    }

    /** A model taught with the night told to it, and the hours that were night, for the truth. */
    record Nights(HouseModel model, List<Double> hours) {
    }

    static double[] stillCold(double hour) {
        return new double[] {-5.0, 0.0, 0.0, 0.0};
    }

    /** Weather that moves in every direction the model has a term for. */
    static double[] varied(double hour) {
        double outdoor = -5.0 + 10.0 * Math.sin(2.0 * Math.PI * hour / 36.0);
        double wind = 3.0 + 3.0 * Math.sin(2.0 * Math.PI * hour / 17.0);
        double bearing = (hour * 53.0) % 360.0;
        double sun = Math.max(0.0, Math.sin(2.0 * Math.PI * (hour % 24.0) / 24.0 - 1.2)) * 0.8;
        return new double[] {outdoor, wind, bearing, sun};
    }

    /**
     * Weather that moves without a daily rhythm of its own: periods that are
     * not 24 hours and no sun, so the only thing in the data with a day in
     * it is the household.
     */
    static double[] noSun(double hour) {
        double outdoor = -5.0 + 10.0 * Math.sin(2.0 * Math.PI * hour / 36.0);
        double wind = 3.0 + 3.0 * Math.sin(2.0 * Math.PI * hour / 17.0);
        double bearing = (hour * 53.0) % 360.0;
        return new double[] {outdoor, wind, bearing, 0.0};
    }

    /** How clear the sky is, day by day. */
    static final double[] CLEAR = {1.0, 0.1, 0.7, 0.0, 0.9, 0.4, 1.0, 0.2, 0.8, 0.0, 0.5};

    /** Mild weather, wind, and a sun up from seven to five whose sky changes from day to day. */
    static double[] autumn(double hour) {
        double outdoor = 3.0 + 6.0 * Math.sin(2.0 * Math.PI * hour / 36.0);
        double wind = 3.0 + 3.0 * Math.sin(2.0 * Math.PI * hour / 17.0);
        double bearing = (hour * 53.0) % 360.0;
        double clock = hour % 24.0;
        double height = 7.0 < clock && clock < 17.0 ? Math.sin(Math.PI * (clock - 7.0) / 10.0) : 0.0;
        return new double[] {outdoor, wind, bearing, height * CLEAR[(int) Math.floor(hour / 24.0) % CLEAR.length]};
    }

    /** Mild, still weather: nothing the model has not seen when asked about it. */
    static double[] mild(double hour) {
        return new double[] {
            5.0 + 3.0 * Math.sin(2.0 * Math.PI * hour / 30.0),
            1.0 + 0.5 * Math.sin(2.0 * Math.PI * hour / 11.0),
            (hour * 41.0) % 360.0,
            0.0,
        };
    }

    static HouseModel teach(HouseModel model, WeatherHouse house, double hours, Weather weather) {
        return teach(model, house, hours, weather, 600.0, null, 0.0, h -> 0.0, null);
    }

    static HouseModel teach(HouseModel model, WeatherHouse house, double hours, Weather weather,
                            DoubleUnaryOperator delta) {
        return teach(model, house, hours, weather, 600.0, null, 0.0, delta, null);
    }

    static HouseModel teach(HouseModel model, WeatherHouse house, double hours, Weather weather,
                            long seed, double noise) {
        return teach(model, house, hours, weather, 600.0, seed, noise, h -> 0.0, null);
    }

    static HouseModel teach(HouseModel model, WeatherHouse house, double hours, Weather weather,
                            Double startHour) {
        return teach(model, house, hours, weather, 600.0, null, 0.0, h -> 0.0, startHour);
    }

    /**
     * Run a house through some weather and let the model watch. startHour
     * puts a clock on it; left null, the hour is null throughout and the
     * daily terms sit at zero. The noise is uniform in +-noise.
     */
    static HouseModel teach(HouseModel model, WeatherHouse house, double hours, Weather weather,
                            double sampleSeconds, Long seed, double noise, DoubleUnaryOperator delta,
                            Double startHour) {
        Random rng = seed == null ? new Random() : new Random(seed);
        double step = sampleSeconds;
        double elapsed = 0.0;
        while (elapsed < hours * 3600.0) {
            double[] w = weather.at(elapsed / 3600.0);
            double d = delta.applyAsDouble(elapsed / 3600.0);
            Double hour = startHour == null ? null : (startHour + elapsed / 3600.0) % 24.0;
            house.step(step / 3600.0, w[0], w[1], w[2], w[3], d, hour);
            double reading = house.indoor + (noise != 0.0 ? -noise + 2.0 * noise * rng.nextDouble() : 0.0);
            model.observe(reading, new Conditions(w[0], w[1], w[2], w[3], hour), d, step, sampleSeconds);
            elapsed += step;
        }
        return model;
    }

    /** The delta a controller would ask for: the weather's need and what is left of the error. */
    private static double demanded(WeatherHouse house, double[] w, double hour, double target) {
        double need = house.lossCoefficient(w[1], w[2]) * (target - w[0]) - house.internal
                - house.solar * w[3] - house.household(hour);
        return Math.max(0.0, need / house.radiator + 8.0 * (target - house.indoor));
    }

    /**
     * The house held at 21 by its radiators, as a controller holds it, ten
     * minutes a cycle: the data a house in service gives, the radiators
     * running hardest when it would cool.
     */
    static HouseModel regulated(WeatherHouse house, double hours, Weather weather) {
        double target = 21.0;
        double sampleSeconds = 600.0;
        HouseModel model = new HouseModel();
        double elapsed = 0.0;
        while (elapsed < hours * 3600.0) {
            double[] w = weather.at(elapsed / 3600.0);
            double hour = (elapsed / 3600.0) % 24.0;
            double delta = demanded(house, w, hour, target);
            house.step(sampleSeconds / 3600.0, w[0], w[1], w[2], w[3], delta, hour);
            model.observe(house.indoor, new Conditions(w[0], w[1], w[2], w[3], hour), delta, sampleSeconds,
                    sampleSeconds);
            elapsed += sampleSeconds;
        }
        return model;
    }

    static Nights regulatedNight(WeatherHouse house, double hours, Weather weather) {
        return regulatedNight(house, hours, weather, null);
    }

    /**
     * regulated(), with the night told as the control tells it: 22 to 6, the
     * sun down and two hours past its warmth. noise, when given, is added to
     * every reading.
     */
    static Nights regulatedNight(WeatherHouse house, double hours, Weather weather, DoubleSupplier noise) {
        double target = 21.0;
        double step = 600.0;
        HouseModel model = new HouseModel();
        double elapsed = 0.0;
        double sinceSun = 1e9;
        List<Double> nights = new ArrayList<>();
        while (elapsed < hours * 3600.0) {
            double[] w = weather.at(elapsed / 3600.0);
            double hour = (elapsed / 3600.0) % 24.0;
            double delta = demanded(house, w, hour, target);
            house.step(step / 3600.0, w[0], w[1], w[2], w[3], delta, hour);
            sinceSun = w[3] > 0.05 ? 0.0 : sinceSun + step;
            boolean night = (hour >= 22.0 || hour < 6.0) && !(7.0 < hour && hour < 17.0) && sinceSun >= 7200.0;
            if (night) {
                nights.add(hour);
            }
            double reading = house.indoor + (noise != null ? noise.getAsDouble() : 0.0);
            model.observe(reading, new Conditions(w[0], w[1], w[2], w[3], hour), delta, step, step, null, night);
            elapsed += step;
        }
        return new Nights(model, nights);
    }

    /** hours of a still house, told night or not (or nothing: null) by night(hour). */
    static HouseModel nightsOf(HouseModel model, double hours, DoubleFunction<Boolean> night) {
        for (int index = 0; index < (int) (hours * 6); index++) {
            double hour = index / 6.0;
            model.observe(21.0, new Conditions(0.0, null, null, 0.0, hour % 24.0), 5.0, 600.0, 600.0, null,
                    night.apply(hour));
        }
        return model;
    }

    static List<Forecast> flat(double outdoor) {
        return flat(outdoor, 0.0, 0.0, 0.0, 24.0);
    }

    /** The same weather at every whole hour up to hours. */
    static List<Forecast> flat(double outdoor, double wind, double bearing, double sun, double hours) {
        List<Forecast> weather = new ArrayList<>();
        for (int h = 0; h <= (int) hours; h++) {
            weather.add(new Forecast(h, new Conditions(outdoor, wind, bearing, sun)));
        }
        return weather;
    }

    static List<Forecast> warmingAt(int turn) {
        return warmingAt(turn, 8.0, 16.0, 12);
    }

    /** Cold until turn, then a mild sunny day. */
    static List<Forecast> warmingAt(int turn, double cold, double warm, int hours) {
        List<Forecast> weather = new ArrayList<>();
        for (int h = 0; h <= hours; h++) {
            weather.add(new Forecast(h, new Conditions(h < turn ? cold : warm, 1.0, 0.0, h < turn ? 0.0 : 0.7)));
        }
        return weather;
    }
}
