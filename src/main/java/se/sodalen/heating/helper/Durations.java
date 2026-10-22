package se.sodalen.heating.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Durations {

    public static String durationWords(Double hours) {
        // Null
        if (hours == null) {
            return "–";
        }

        double minutes = BigDecimal.valueOf(hours * 60)
                .add(BigDecimal.valueOf(1e-9))
                .setScale(0, RoundingMode.HALF_UP)
                .doubleValue();
        double weeks = BigDecimal.valueOf(hours / 168)
                .add(BigDecimal.valueOf(1e-9))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
        double days = BigDecimal.valueOf(hours / 24)
                .add(BigDecimal.valueOf(1e-9))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
        double months = BigDecimal.valueOf(hours / 24 / 30.44)
                .add(BigDecimal.valueOf(1e-9))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
        double years = BigDecimal.valueOf(hours / 24 / 365.25)
                .add(BigDecimal.valueOf(1e-9))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
        hours = BigDecimal.valueOf(hours)
                .add(BigDecimal.valueOf(1e-9))
                .doubleValue();

        if (hours >= 8401.44) {
            return yearToString(years);
        } else if (weeks >= 1 && days >= 7 && hours < 730.56) {
            return weeksToString(weeks);
        } else if (hours < (24 * 365.25) && hours > 730.56) {
            return monthToString(months);
        } else if (hours >= 23.5) {
            return daysToString(days);
        } else if (hours >= 1 || minutes == 60) {
            return hoursToString(hours);
        } else {
            return minutesToString(minutes);
        }
    }

    private static String yearToString(double years) {
        // Year calc
        if (years == 1.0) {
            return "1 år";
        }

        if (years >= 10.0 || years % 1 == 0) {
            return String.format("%d år", Integer.parseInt(Double.toString(Math.round(years)).substring(0,
                    Double.toString(years).indexOf("."))));
        }

        return String.format("%.1f år", years).replace(".", ",");
    }

    private static String monthToString(double months) {
        // Month calc
        if (months == 1.0) {
            return "1 månad";
        }

        if (months >= 10 || months % 1 == 0) {
            months = Math.round(months);
            return String.format("%d månader", Integer.parseInt(Double.toString(months).substring(0,
                    Double.toString(months).indexOf("."))));
        }

        return String.format("%.1f månader", months).replace(".", ",");
    }

    private static String weeksToString(double weeks) {
        // Week calc
        if (weeks == 1.0) {
            return "1 vecka";
        }

        if (weeks % 1 == 0) {
            return String.format("%d veckor", Integer.parseInt(Double.toString(weeks).substring(0,
                    Double.toString(weeks).indexOf("."))));
        }
        return String.format("%.1f veckor", weeks).replace(".", ",");
    }


    private static String daysToString(double days) {
        // Day calc
        if (days == 1.0) {
            return "1 dag";
        }

        if (days % 1 == 0) {
            return String.format("%d dagar", Integer.parseInt(Double.toString(days).substring(0,
                    Double.toString(days).indexOf("."))));
        }

        return String.format("%.1f dagar", days).replace(".", ",");
    }


    private static String hoursToString(double hours) {
        // Hours calc
        if (hours >= 10) {
            hours = BigDecimal.valueOf(hours)
                    .setScale(0, RoundingMode.HALF_UP).doubleValue();
        } else {
            hours = BigDecimal.valueOf(hours)
                    .setScale(1, RoundingMode.HALF_UP).doubleValue();
        }

        if (hours >= 10 || hours % 1 == 0) {
            return String.format("%d h", Integer.parseInt(Double.toString(hours).substring(0,
                    Double.toString(hours).indexOf("."))));

        } else {
            return String.format("%.1f h", hours).replace(".", ",");
        }
    }

    private static String minutesToString(double minutes) {
        // Minutes Calc
        if (minutes <= 0.0) {
            return "0 min";
        }

        return String.format("%d min", Integer.parseInt(Double.toString(minutes).substring(0, Double.toString(minutes).indexOf(".")))).replace(".", ",");
    }

    public static String durationWordsSeconds(Double seconds) {

        if (seconds == null) {
            return "–";
        }

        double minutes = BigDecimal.valueOf(seconds / 60).setScale(0, RoundingMode.HALF_UP).doubleValue();

        if (minutes < 0) {
            return "0 min";
        }
        if (minutes >= 60) {
            return durationWords(seconds / 60 / 60);
        }
        return String.format("%d min", Integer.parseInt(Double.toString(minutes).substring(0, Double.toString(minutes).indexOf(".")))).replace(".", ",");
    }
}



