package se.sodalen.heating.curve;

import java.util.List;

// Kurvan genom tabellens prickar: lutning och böj givet balansen, och hur mycket av det som är
// mätning. rejected är avvikarnas utetemperaturer, chain de betrodda prickar som räknades, varm ände först.
public record CurveFit(double slope, double balance, double curvature, Double coldestGreen, int used,
                       List<Double> rejected, List<Double> chain) {

    public CurveFit(double slope, double balance, double curvature, Double coldestGreen, int used,
                    List<Double> rejected) {
        this(slope, balance, curvature, coldestGreen, used, rejected, List.of());
    }                   // Pythons chain=(): tom kedja

    public boolean steerable() {
        return chain.size() >= Fitting.MIN_CHAIN;
    }                   // kedja nog för att kurvan ska få följa anpassningen: minst MIN_CHAIN
}
