package se.sodalen.heating.curve;

import java.util.List;
import java.util.stream.IntStream;

// Lägger kurvan genom tabellens prickar. Med balansen given är lutningen det enda prickarna får
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
        return null;
    }                   // längsta raden betrodda fack högst maxGap grader isär, varm ände först; vid lika den kallare

    public static CurveFit fitCurve(List<Bin> bins, double room, double designOutdoor, double balance,
                                    double slopePrior, double curvature, double bendLimit) {
        return null;
    }                   // Pythons standardvärden: prior_weight 0.25 och max_green_gap None

    public static CurveFit fitCurve(List<Bin> bins, double room, double designOutdoor, double balance,
                                    double slopePrior, double curvature, double bendLimit,
                                    double priorWeight, Double maxGreenGap) {
        return null;
    }                   // lutningen, och böjen när det finns räckvidd för den, som bäst förklarar prickarna
}
