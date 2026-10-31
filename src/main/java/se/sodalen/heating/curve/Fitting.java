package se.sodalen.heating.curve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

// bestämma, och böjen söks först när betrodda prickar når in i kylan.
public final class Fitting {

    // EVR-kurvor lutningen får flytta med betrodda prickar ända ner till dimensionerande kyla.
    public static final double MAX_NUMBER_SHIFT = 2.0;
    // Så långt en prick får ligga från anpassningen innan den tas för något annat.
    public static final double OUTLIER = 3.0;
    // Böjen söks bara med betrodda prickar så här kalla.
    public static final double BEND_COLD = -10.0;
    // 71 böjar att pröva, 0,50 till 1,20 i steg om 0,01: Pythons round(0.5 + 0.01 * step, 2).
    public static final List<Double> BEND_GRID = IntStream.rangeClosed(0, 70)
            .mapToObj(step -> Math.round((0.5 + 0.01 * step) * 100.0) / 100.0).toList();
    // Håller böjen vid fysikens värde när prickarna inte kan säga.
    public static final double BEND_PRIOR = 4.0;
    // Betrodda prickar i rad innan anpassningen får styra.
    public static final int MIN_CHAIN = 2;
    public static final double DEFAULT_PRIOR_WEIGHT = 0.25;   // fit_curve:s prior_weight=0.25

    private Fitting() {
    }

    public static List<Bin> greenChain(List<Bin> bins, double maxGap) {

        List<Bin> greens = bins.stream()
                .filter(item -> item.status() == Status.GREEN)
                .sorted(Comparator.comparingInt(item -> -PointTable.binIndex(item.outdoor())))
                .toList();

        int step = Math.max(1, (int) Math.rint(maxGap / PointTable.BIN_WIDTH));
        List<List<Bin>> runs = new ArrayList<>();

        for (Bin item : greens) {
            if (!runs.isEmpty() && PointTable.binIndex(runs.getLast().getLast().outdoor())
                    - PointTable.binIndex(item.outdoor()) <= step) {

                runs.getLast().add(item);
            } else {
                runs.addLast(new ArrayList<>(List.of(item)));
            }
        }

        List<Bin> best = new ArrayList<>();

        for (List<Bin> run : runs) {
            if (run.size() >= best.size()) {
                best = run;
            }
        }
        return best;
    }

    // Lägger kurvan genom tabellens prickar. Med balansen given är lutningen det enda prickarna får
    public static CurveFit fitCurve(List<Bin> bins, double room, double designOutdoor, double balance,
                                    double slopePrior, double curvature, double bendLimit) {
        return fitCurve(bins, room, designOutdoor, balance, slopePrior, curvature, bendLimit, DEFAULT_PRIOR_WEIGHT, null);
    }                   // Pythons standardvärden: prior_weight 0.25 och max_green_gap None

    public static CurveFit fitCurve(List<Bin> bins, double room, double designOutdoor, double balance,
                                    double slopePrior, double curvature, double bendLimit,
                                    double priorWeight, Double maxGreenGap) {

        List<Bin> usable = new ArrayList<>();
        for (Bin bin : bins) {
            if (bin.weight() > 0.0) {
                usable.add(bin);
            }
        }

        List<Bin> kept = new ArrayList<>();
        List<Bin> chain = new ArrayList<>();
        if (maxGreenGap == null) {
            for (Bin bin : usable) {
                if (bin.status() == Status.GREEN) {
                    chain.add(bin);
                }
            }
        } else {
            chain = greenChain(usable, maxGreenGap);
            for (Bin bin : usable) {
                if (bin.status() != Status.GREEN) {
                    kept.add(bin);
                } else if (chain.contains(bin)) {
                    kept.add(bin);
                }

            }
            usable = kept;
        }



        List<Double> greens = new ArrayList<>();
        for (Bin bin : chain) {
            greens.add(bin.outdoor());
        }

        Double coldest = greens.stream()
                .min(Comparator.naturalOrder())
                .orElse(null);

        double reach = 0.0;

        if (coldest != null && balance > designOutdoor) {
            reach = Math.clamp((balance - coldest) / (balance - designOutdoor), 0.0, 1.0);
        }

        Shape shape = new Shape(room, designOutdoor, balance);
        Prior prior = new Prior(shape, slopePrior, priorWeight, reach);

        Double slope = prior.solve(usable, curvature);

        List<Bin> outliers = new ArrayList<>();
        for (Bin bin : usable) {
            if (Math.abs(bin.need() - shape.excess(bin.outdoor(), slope, curvature)) > OUTLIER) {
                outliers.add(bin);
            }
        }

        if (!outliers.isEmpty()) {
            kept = usable.stream()
                    .filter(b -> !outliers.contains(b))
                    .toList();
            slope = prior.solve(kept, curvature);
            usable = kept;
        }
        double bent = curvature;

        if (bendLimit > 0.0 && coldest != null && coldest <= BEND_COLD) {
            Bent result = bend(usable, shape, prior, curvature, bendLimit);

            bent = result.curvature();
            slope = result.slope();
        }

        return new CurveFit(slope, balance, bent, coldest, usable.size(), outliers.stream().map(Bin::outdoor).toList(), greens.stream()
                .sorted(Comparator.reverseOrder()).toList());
    }                   // lutningen, och böjen när det finns räckvidd för den, som bäst förklarar prickarna
    // längsta raden betrodda fack högst maxGap grader isär, varm ände först; vid lika den kallare

    private static Bent bend(List<Bin> bins, Shape shape, Prior prior, Double reference, Double limit) {
        Bent best = new Bent(reference, prior.solve(bins, reference));
        Double bestCost = prior.cost(bins, best.slope(), reference);

        for (Double curvature : BEND_GRID) {
            double slope = prior.solve(bins, curvature);
            double moved = Curve.middleDeviation(shape.room, shape.design, slope, shape.balance, curvature, reference);
            if (Math.abs(moved) > limit) {
                continue;
            }
            double away = curvature - reference;
            double cost = prior.cost(bins, slope, curvature) + BEND_PRIOR * away * away;
            if (cost < bestCost) {
                best = new Bent(curvature, slope);
                bestCost = cost;
            }
        }
        return best;
    }


private record Shape(double room, double design, double balance) {

        private Double perSlope(Double outdoor, Double curvature) {
            return Curve.supply(outdoor, room, design, 1.0, curvature, balance) - room;
        }

        private Double excess(Double outdoor, Double slope, Double curvature) {
            return slope * perSlope(outdoor, curvature);
        }
    }

    private static class Prior {
        private final Shape shape;
        private final Double slope;
        private final Double weight;
        private final double low;
        private final double high;

        private Prior(Shape shape, Double slope, Double weight, Double reach) {
            this.shape = shape;
            this.slope = slope;
            this.weight = weight;
            Double number = Curve.evrNumber(slope, shape.room, shape.design);
            Double allowed = MAX_NUMBER_SHIFT * reach;
            this.low = Curve.evrSlope(Math.max(0.0, number - allowed), shape.room, shape.design);
            this.high = Curve.evrSlope(number + allowed, shape.room, shape.design);
        }

        private double solve(List<Bin> bins, Double curvature) {
            double reference = shape.perSlope((shape.balance + shape.design) / 2.0, curvature);
            double strength = weight * reference * reference;
            double top = strength * slope;
            double bottom = strength;

            for (Bin bin : bins) {
                double per = shape.perSlope(bin.outdoor(), curvature);
                top += bin.weight() * bin.need() * per;
                bottom += bin.weight() * per * per;
            }
            double slope = bottom > 0.0 ? top / bottom : this.slope;
            return Math.min(high, Math.max(low, slope));
        }

        private double cost(List<Bin> bins, Double slope, Double curvature) {
            double total = 0.0;
            for (Bin bin : bins) {
                double miss = bin.need() - shape.excess(bin.outdoor(), slope, curvature);
                total += bin.weight() * miss * miss;
            }
            return total;
        }
    }
}
