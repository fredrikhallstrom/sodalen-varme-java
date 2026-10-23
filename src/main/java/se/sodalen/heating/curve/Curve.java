package se.sodalen.heating.curve;

import java.util.List;

public final class Curve {
    private Curve() {}

    public static final List<Double> EVR_RISE =
            List.of(0.42, 0.58, 0.74, 0.91, 1.32, 1.74, 2.24, 2.73);

    private static final double EVR_ORIGIN = 20.0;
    private static final double MAX_SHARE = 0.95;

    private static double evrRise(double slope, double room, double designOutdoor) {
        double span = Math.max(1.0, (room - designOutdoor));
        double supply = room + slope * span;

        return (supply - EVR_ORIGIN) / (EVR_ORIGIN - designOutdoor);
    }

    public static double supply(double outdoor, double room, double designOutdoor,
                                double slope, double curvature, double balance) {

        double reach = Math.max(1.0, (balance - designOutdoor));
        double share = Math.max(0.0, (balance - outdoor) / reach);

        double span = room - designOutdoor;
        if (span <= 1) {
            span = 1;
        }

        return room + slope * span * Math.pow(share, curvature);
    }

    public static double balanceThrough(double outdoor, double wanted, double room,
                                        double designOutdoor, double slope, double curvature) {

        double span = Math.max(room - designOutdoor, 1);
        double excess = wanted - room;
        if (excess <= 0) {
            return outdoor;
        }

        double share = Math.min(MAX_SHARE, Math.pow(excess / (slope * span), (1.0 / curvature)));

        return (outdoor - share * designOutdoor) / (1.0 - share);
    }

    public static double evrNumber(double slope, double room, double designOutdoor) {
        double low;
        double high;
        double rise = evrRise(slope, room, designOutdoor);
        List<Double> evrTable = EVR_RISE;

        int index = 0;
        while (index < evrTable.size() - 2 && rise > evrTable.get(index + 1)) {
            index++;
        }
        low = evrTable.get(index);
        high = evrTable.get(index + 1);

        return index + (rise - low) / (high - low);

    }

    public static double evrSlope(double number, double room, double designOutdoor) {
        double low;
        double high;
        double rise;
        double supply;
        double span;
        double result;
        List<Double> evrTable = EVR_RISE;
        int index = Math.clamp(
                ((int) Math.floor(number)),
                0,
                evrTable.size() - 2);

        low = evrTable.get(index);
        high = evrTable.get(index + 1);
        rise = low + (number - index) * (high - low);
        supply = EVR_ORIGIN + rise * (EVR_ORIGIN - designOutdoor);
        span = Math.max(1.0, room - designOutdoor);
        result = (supply - room) / span;

        return result;
    }

    public static double indoorSensitivity(double outdoor, double room, double designOutdoor,
                                           double slope, double curvature, double balance) {

        double step = 0.5;
        double result;
        double up;
        double down;

        up = supply(outdoor, room + step, designOutdoor, slope, curvature, balance + step);
        down = supply(outdoor, room - step, designOutdoor, slope, curvature, balance - step);

        result = (up - down) / (2.0 * step);

        return result;
    }

    public static double middleDeviation(double room, double designOutdoor, double slope,
                                         double balance, double curvature, double reference) {

        double worst;
        double steps;

        if (balance <= designOutdoor) {
            return 0.0;
        }

        worst = 0.0;
        steps = 200;

        for (int i = 1; i < steps; i++) {
            double outdoor = balance - (balance - designOutdoor) * i / steps;
            double gap = (
                    supply(outdoor, room, designOutdoor, slope, curvature, balance) -
                            supply(outdoor, room, designOutdoor, slope, reference, balance)
            );
            if (Math.abs(gap) > Math.abs(worst)) {
                worst = gap;
            }
        }

        return worst;
    }

}
