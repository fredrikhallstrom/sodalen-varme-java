package se.sodalen.heating.curve;

import java.util.ArrayList;
import java.util.List;

/**
 * The helpers of tests/test_points.py around the fit: ROOM and DESIGN, a
 * table filled from a known curve (what a house wanting exactly that curve
 * would have measured), bins written straight down, and fit_curve with the
 * Python tests' keyword defaults.
 */
final class Dots {

    static final double ROOM = 21.0;
    static final double DESIGN = -18.0;
    /** The Python default sectors=(0, 1, 2, 3): every part of the day. */
    static final int[] ALL_DAY = {0, 1, 2, 3};

    private Dots() {
    }

    /** `hours` spread evenly over the given parts of the day, an hour into each. */
    static PointTable filled(PointTable table, double outdoor, double need, double hours, int[] sectors,
                             double wind) {
        for (int sector : sectors) {
            table.add(outdoor, need, wind, sector * 6.0 + 1.0, hours / sectors.length);
        }
        return table;
    }

    /** filled with the Python defaults: the whole day, no wind. */
    static PointTable filled(PointTable table, double outdoor, double need, double hours) {
        return filled(table, outdoor, need, hours, ALL_DAY, 0.0);
    }

    /** What the curve asks for above the room at this weather: a dot's need. */
    static double need(double slope, double balance, double curvature, double outdoor) {
        return Curve.supply(outdoor, ROOM, DESIGN, slope, curvature, balance) - ROOM;
    }

    /** dots(slope, balance, curvature, outdoors): forty hours over the whole day at each. */
    static List<Bin> dots(double slope, double balance, double curvature, double... outdoors) {
        return dots(slope, balance, curvature, outdoors, 40.0, ALL_DAY);
    }

    /** dots(..., hours=...): the whole day, fewer hours. */
    static List<Bin> dots(double slope, double balance, double curvature, double[] outdoors, double hours) {
        return dots(slope, balance, curvature, outdoors, hours, ALL_DAY);
    }

    /**
     * A table filled from a known curve - what a house wanting exactly that
     * curve would have measured - as its bins, coldest first, in a list a
     * test may change.
     */
    static List<Bin> dots(double slope, double balance, double curvature, double[] outdoors, double hours,
                          int[] sectors) {
        PointTable table = new PointTable();
        for (double outdoor : outdoors) {
            filled(table, outdoor, need(slope, balance, curvature, outdoor), hours, sectors, 0.0);
        }
        return new ArrayList<>(table.bins());
    }

    /** Python's list +: the bins of both, the first ones first. */
    static List<Bin> plus(List<Bin> first, List<Bin> second) {
        List<Bin> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    /** Bin(**{**bin.__dict__, "need": need}): the same bin with another need. */
    static Bin withNeed(Bin bin, double need) {
        return new Bin(bin.outdoor(), need, bin.hours(), bin.spread(), bin.sectors(), bin.wind(), bin.age(),
                bin.status(), bin.weight());
    }

    /** The same bins with the one at this outdoor temperature needing `more` on top: a stove lit through it. */
    static List<Bin> withMoreNeed(List<Bin> bins, double outdoor, double more) {
        List<Bin> out = new ArrayList<>(bins.size());
        for (Bin bin : bins) {
            out.add(bin.outdoor() == outdoor ? withNeed(bin, bin.need() + more) : bin);
        }
        return out;
    }

    /** A bin written straight down; the fit reads only outdoor, need, status and weight. */
    static Bin bin(double outdoor, double need, Status status, double weight) {
        return new Bin(outdoor, need, 40.0, 0.0, 4, 0.0, 0.0, status, weight);
    }

    /** A trusted bin lying exactly on the curve with these parameters. */
    static Bin greenOn(double slope, double balance, double curvature, double outdoor) {
        return bin(outdoor, need(slope, balance, curvature, outdoor), Status.GREEN, 1.0);
    }

    /** Trusted bins on the curve, in the order given, in a list a test may add to. */
    static List<Bin> greensOn(double slope, double balance, double curvature, double... outdoors) {
        List<Bin> greens = new ArrayList<>();
        for (double outdoor : outdoors) {
            greens.add(greenOn(slope, balance, curvature, outdoor));
        }
        return greens;
    }

    /** The outdoor temperatures of these bins, in their order. */
    static List<Double> outdoors(List<Bin> bins) {
        List<Double> out = new ArrayList<>(bins.size());
        for (Bin bin : bins) {
            out.add(bin.outdoor());
        }
        return out;
    }

    /** The Python helper fit(bins) with every keyword at its default. */
    static CurveFit fit(List<Bin> bins) {
        return fit(bins, keywords());
    }

    /** fit(bins, **keywords): fit(made, keywords().prior(0.8).maxGreenGap(2.0)). */
    static CurveFit fit(List<Bin> bins, Keywords keywords) {
        return keywords.fit(bins);
    }

    static Keywords keywords() {
        return new Keywords();
    }

    /**
     * The keyword arguments of the Python helper, each starting at its
     * default (balance 11.0, prior 0.9, curvature 0.8, bend_limit 2.0,
     * max_green_gap None), and fit_curve's own prior_weight besides. With
     * neither a prior weight nor a gap given, the call is the short fitCurve,
     * as the Python helper leaves prior_weight to fit_curve's default.
     */
    static final class Keywords {
        private double balance = 11.0;
        private double prior = 0.9;
        private double curvature = 0.8;
        private double bendLimit = 2.0;
        private Double maxGreenGap = null;
        private Double priorWeight = null;

        private Keywords() {
        }

        Keywords balance(double value) {
            balance = value;
            return this;
        }

        Keywords prior(double value) {
            prior = value;
            return this;
        }

        Keywords curvature(double value) {
            curvature = value;
            return this;
        }

        Keywords bendLimit(double value) {
            bendLimit = value;
            return this;
        }

        Keywords maxGreenGap(Double value) {
            maxGreenGap = value;
            return this;
        }

        Keywords priorWeight(double value) {
            priorWeight = value;
            return this;
        }

        CurveFit fit(List<Bin> bins) {
            if (priorWeight == null && maxGreenGap == null) {
                return Fitting.fitCurve(bins, ROOM, DESIGN, balance, prior, curvature, bendLimit);
            }
            double weight = priorWeight == null ? Fitting.DEFAULT_PRIOR_WEIGHT : priorWeight;
            return Fitting.fitCurve(bins, ROOM, DESIGN, balance, prior, curvature, bendLimit, weight, maxGreenGap);
        }
    }
}
