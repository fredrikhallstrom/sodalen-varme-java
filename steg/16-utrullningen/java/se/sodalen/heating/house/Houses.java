package se.sodalen.heating.house;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The houses and the weather ahead the hand tests of step 16 use: the
 * helpers of tests/test_coast.py (taught_house, flat_forecast,
 * turning_forecast), on step 7's Teaching and WeatherHouse.
 *
 * <p>A taught house takes a while to teach, so each is taught once and kept.
 * Neither coast() nor distrust() changes a house; a controller made with one
 * only sets its design again, from settings these tests leave alone.
 */
final class Houses {

    private static final Map<String, HouseModel> KEPT = new HashMap<>();

    private Houses() {
    }

    private static synchronized HouseModel kept(String name, Supplier<HouseModel> teach) {
        return KEPT.computeIfAbsent(name, key -> teach.get());
    }

    /** taught_house's delta: the radiators on before two in the afternoon, off after. */
    static double morningHeat(double hour) {
        return hour % 24.0 < 14.0 ? 4.0 : 0.0;
    }

    /**
     * taught_house(hours, noise): WeatherHouse's defaults watched in ten
     * minute samples through the varied weather, without a clock. The noise
     * comes from Java's Random, not Python's: these are for the hand tests,
     * not for comparing with Python.
     */
    static HouseModel taught(double hours, double noise) {
        return Teaching.teach(new HouseModel(), new WeatherHouse(), hours, Teaching::varied, 600.0,
                noise == 0.0 ? null : 4L, noise, Houses::morningHeat, null);
    }

    /** taught_house(): 25 days, 3600 cycles, 599.83 hours. */
    static HouseModel learned() {
        return kept("learned", () -> taught(24.0 * 25.0, 0.0));
    }

    /** taught_house(hours=24): 23.83 hours, under coast_min_hours. */
    static HouseModel day() {
        return kept("day", () -> taught(24.0, 0.0));
    }

    /** taught_house(hours=6). */
    static HouseModel sixHours() {
        return kept("six", () -> taught(6.0, 0.0));
    }

    /** taught_house(hours=24 * 3), clean. */
    static HouseModel threeDays() {
        return kept("three", () -> taught(72.0, 0.0));
    }

    /** taught_house(hours=24 * 3, noise=0.15). */
    static HouseModel threeNoisyDays() {
        return kept("noisy", () -> taught(72.0, 0.15));
    }

    /**
     * A house watched for exactly this many hours: half-hour samples, which
     * add up without rounding, so 12.5 is 12.5 and not 12.4999. The first
     * cycle only takes the reading, so one cycle more than the samples.
     */
    static HouseModel watchedFor(double hours) {
        return kept("watched " + hours, () -> Teaching.teach(new HouseModel(), new WeatherHouse(), hours + 0.5,
                Teaching::varied, 1800.0, null, 0.0, h -> 0.0, null));
    }

    /** Step 7's stove: a house that warms itself at -5, so it does not cool when left alone. */
    static HouseModel stove() {
        return kept("stove", () -> {
            WeatherHouse house = new WeatherHouse();
            house.internal = 2.0;
            house.g0 = 0.005;
            return Teaching.teach(new HouseModel(), house, 24.0 * 20.0, Teaching::varied);
        });
    }

    /** A house that loses next to nothing and has nothing inside: it cools, but its loss sits on the floor. */
    static HouseModel thinWalls() {
        return kept("thin", () -> {
            WeatherHouse house = new WeatherHouse();
            house.g0 = 0.0005;
            house.internal = 0.0;
            return Teaching.teach(new HouseModel(), house, 24.0 * 20.0, Teaching::varied);
        });
    }

    /** flat_forecast(outdoor, wind=, sun=): the same weather every hour from 0 to 12. */
    static List<Forecast> flat(double outdoor, double wind, double sun) {
        List<Forecast> weather = new ArrayList<>();
        for (int h = 0; h <= 12; h++) {
            weather.add(new Forecast(h, new Conditions(outdoor, wind, 0.0, sun)));
        }
        return weather;
    }

    static List<Forecast> flat(double outdoor) {
        return flat(outdoor, 0.0, 0.0);
    }

    /** turning_forecast(): a mild night of 8 that turns into a sunny day of 16 two hours out. */
    static List<Forecast> turning() {
        List<Forecast> weather = new ArrayList<>();
        for (int h = 0; h <= 12; h++) {
            boolean turned = h >= 2;
            weather.add(new Forecast(h, new Conditions(turned ? 16.0 : 8.0, 0.0, 0.0, turned ? 0.7 : 0.0)));
        }
        return weather;
    }
}
