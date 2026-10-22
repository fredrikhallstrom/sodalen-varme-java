package se.sodalen.heating.valve;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ValveMap {
    public static final double BAND = 5.0;     // 5.0
    public static final int BANDS = 21;       // 21: 0, 5, ... 100 %
    private final double horizonSeconds;
    private final double settleSeconds;
    private final double minLift;
    private final double minBandSeconds;

    private final Double[] share = new Double[BANDS];
    private final double[] weight = new double[BANDS];
    private Double shadowError = null;
    private double shadowSeconds = 0.0;

    private Double lastPosition = null;
    private double stillSeconds = 0.0;

    public ValveMap() {
        this(30.0 * 24.0 * 3600.0, 300.0, 15.0, 1800.0);
    }                   // 30 dygn, 300 s stilla, 15 K lyft, 1800 s per band

    public ValveMap(double horizonSeconds, double settleSeconds, double minLift, double minBandSeconds) {
        this.horizonSeconds = horizonSeconds;
        this.settleSeconds = settleSeconds;
        this.minLift = minLift;
        this.minBandSeconds = minBandSeconds;
    }

    private int band(double position) {
        return Math.clamp((int) Math.rint(position / BAND), 0, BANDS - 1);
    }

    public Double share(int band) {
        return share[band];
    }       // null: inget lärt där än

    public double weight(int band) {
        return weight[band];
    }      // sekunder data, med glömskan

    public Double shadowError() {
        return shadowError;
    }         // null: inget att jämföra med än

    public double shadowSeconds() {
        return shadowSeconds;
    }

    public boolean settled() {
        return stillSeconds >= settleSeconds;
    }

    public Double observe(Double position, Double flow, Double ret, Double boiler, double dt) {
        double result;

        if (position == null || dt <= 0.0) {
            lastPosition = null;
            stillSeconds = 0.0;
            return null;
        }

        if (lastPosition == null || Math.abs(position - lastPosition) > 0.5) {
            lastPosition = position;
            stillSeconds = 0.0;
            return null;
        }

        stillSeconds += dt;

        if (!settled() || (flow == null || ret == null || boiler == null))
            return null;

        double lift = boiler - ret;
        if (lift < minLift)
            return null;

        double shareValue = Math.clamp((flow - ret) / lift, -0.05, 1.1);
        int band = band(position);
        double kept = weight[band] * Math.exp(-dt / horizonSeconds);
        double total = kept + dt;
        Double old = share[band];
        share[band] = (old == null) ? shareValue : old + (shareValue - old) * dt / total;
        weight[band] = total;
        return shareValue;
    }

    public void fillPrior() {
        double anchorPosition = 0.0;
        double anchorShare = 0.0;

        for (int band = 0; band < BANDS; band++) {
            Double shareValue = share[band];
            if (shareValue != null && weight[band] >= minBandSeconds) {
                anchorPosition = band * BAND;
                anchorShare = shareValue;
            }
        }

        for (int band = 0; band < BANDS; band++) {
            double position = band * BAND;
            double weightValue = weight[band];

            if (position < anchorPosition || weightValue >= minBandSeconds) {
                continue;
            }

            double span = 100.0 - anchorPosition;
            double line = span <= 0.0 ? 1.0 : anchorShare + (1.0 - anchorShare) * (position - anchorPosition) / span;

            if (position == anchorPosition) {
                line = anchorShare;
            }

            Double real = share[band];
            double prior = minBandSeconds - weightValue;
            share[band] = real == null || weightValue <= 0.0 ?
                    line : (weightValue * real + prior * line) / minBandSeconds;
            weight[band] = minBandSeconds;
        }
    }

    public List<ValvePoint> known() {
        List<ValvePoint> points = new ArrayList<>();
        double highest = Double.NEGATIVE_INFINITY;

        for (int band = 0; band < BANDS; band++) {
            Double shareValue = share[band];
            if (shareValue == null || weight[band] < minBandSeconds)
                continue;
            highest = Math.max(shareValue, highest);
            points.add(new ValvePoint(band * BAND, highest));
        }

        return points;
    }

    public Double positionFor(Double flow, Double ret, Double boiler) {
        if (flow == null || ret == null || boiler == null || boiler - ret < minLift) {
            return null;
        }

        List<ValvePoint> points = known();
        if (points.size() < 2)
            return null;

        double wanted = (flow - ret) / (boiler - ret);
        if (wanted < points.getFirst().share() || wanted > points.getLast().share()) {
            return null;
        }

        for (int i = 0; i + 1 < points.size(); i++) {
            ValvePoint p0 = points.get(i);
            ValvePoint p1 = points.get(i + 1);
            double s0 = p0.share();
            double s1 = p1.share();
            if (s0 <= wanted && s1 >= wanted) {
                if (s1 - s0 < 1e-9) {
                    return p0.position();
                }
                return p0.position() + (wanted - s0) / (s1 - s0) * (p1.position() - p0.position());
            }
        }
        return null;
    }

    public Double gainAt(Double position, Double lift) {
        if (position == null || lift == null || lift < minLift) {
            return null;
        }

        List<ValvePoint> points = known();
        List<Double> slopes = new ArrayList<>();
        double s1, s0, p0, p1;
        for (int i = 0; i + 1 < points.size(); i++) {
            ValvePoint po0 = points.get(i);
            ValvePoint po1 = points.get(i + 1);
            s0 = po0.share();
            s1 = po1.share();
            p0 = po0.position();
            p1 = po1.position();
            if (p1 > position - BAND && p0 < position + BAND) {
                slopes.add((s1 - s0) / (p1 - p0));
            }
        }

        return slopes.stream()
                .max(Comparator.naturalOrder())
                .map(slope -> slope * lift)
                .orElse(null);
    }

    public void score(Double predicted, Double actual, double dt) {
        if (predicted == null || actual == null || dt <= 0) {
            return;
        }
        double gap = Math.abs(predicted - actual);
        double kept = shadowSeconds * Math.exp(-dt / horizonSeconds);
        double total = kept + dt;
        Double old = shadowError;
        shadowError = old == null ? gap : old + (gap - old) * dt / total;
        shadowSeconds = total;

    }

//    public Map<String, Double> summary() {
//
//        StringBuilder sb = new StringBuilder();
//        for (int band = 0; band < share.length; band++) {
//            sb.append(String.format("%.0f: %d", band * BAND, Math.round(share[band])));
//        }
//        String andelPerLage = sb.toString();
//
//        String lagenMedData = String.valueOf(known().size());
//
//        String timmar = String.valueOf(Math.round(Math.aweight / 3600.0, ));
//        return Map.of(
//                "andel_per_lage", andelPerLage,
//                "lagen_med_data", lagenMedData,
//                "timmar", timmar,
//                "skuggfel", skuggFel,
//                "skuggtimmar", skuggTimmar
//        );
//    }
}