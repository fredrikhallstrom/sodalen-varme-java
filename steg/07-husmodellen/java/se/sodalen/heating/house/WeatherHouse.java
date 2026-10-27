package se.sodalen.heating.house;

/**
 * A house whose thermal behaviour is known exactly, for the model to find:
 * a building with a heat capacity, a wind-dependent loss and some windows,
 * written so that the parameters the HouseModel is supposed to learn are
 * sitting right there to compare against.
 *
 * <p>Wind matters more from the north than from the south, which is the
 * asymmetry the direction terms exist to find. And the household has a day,
 * peaking in the evening, which is the rhythm the daily terms exist to find.
 *
 * <p>Python: tests/weatherhouse.py. The fields are the dataclass's, with its
 * defaults.
 */
final class WeatherHouse {
    public double indoor = 21.0;
    /** Kelvin per hour per Kelvin of difference, with no wind. */
    public double g0 = 0.055;
    /** Extra, per metre per second - more from the north than the south. */
    public double gWind = 0.004;
    public double gNorth = 0.003;
    /** Kelvin per hour at full sun. */
    public double solar = 0.9;
    /** Kelvin per hour from people, appliances, the boiler. */
    public double internal = 0.10;
    /** Kelvin per hour per Kelvin of supply/return delta. */
    public double radiator = 0.06;
    /** The household's own day, on top of internal: amplitude in K/h and the hour it peaks. */
    public double daily = 0.0;
    public double dailyPeak = 18.0;

    double household(Double hour) {
        if (daily == 0.0 || hour == null) {
            return 0.0;
        }
        return daily * Math.cos(2.0 * Math.PI * (hour - dailyPeak) / 24.0);
    }

    double lossCoefficient(double wind, double bearing) {
        return g0 + gWind * wind + gNorth * wind * Math.cos(Math.toRadians(bearing));
    }

    double step(double hours, double outdoor, double wind, double bearing, double sun, double delta, Double hour) {
        double slope = internal
                + household(hour)
                + solar * sun
                + radiator * delta
                - lossCoefficient(wind, bearing) * (indoor - outdoor);
        indoor += slope * hours;
        return slope;
    }

    /**
     * How long it really takes to reach the floor, with the heat off; null
     * when it holds for 96 hours. The clock runs forward with the walk when
     * it is given one, because a house left alone at eight in the evening
     * spends the night losing the household as well as the weather.
     */
    Double holdHours(double floor, double outdoor, double wind, double bearing, double sun, double step, Double hour) {
        double temperature = indoor;
        double elapsed = 0.0;
        while (elapsed < 96.0) {
            double slope = internal
                    + household(hour == null ? null : (hour + elapsed) % 24.0)
                    + solar * sun
                    - lossCoefficient(wind, bearing) * (temperature - outdoor);
            temperature += slope * step;
            elapsed += step;
            if (temperature <= floor) {
                return elapsed;
            }
        }
        return null;
    }
}
