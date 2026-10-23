package se.sodalen.heating.estimate;

// Minsta kvadrat-linjen y = a + b*x genom prov som bleknar med tiden.
public final class Regression {
    double horizon;
    double weight = 0.0;
    double sumX = 0.0;
    double sumY = 0.0;
    double sumXx = 0.0;
    double sumXy = 0.0;

    public Regression(double horizon) {
        this.horizon = horizon;
    }                   // tidskonstanten för glömskan, sekunder

    public double horizon() {
        return horizon;
    }

    public double weight() {
        return weight;
    }                   // antal prov, med glömskan

    public double sumX() {
        return sumX;
    }

    public double sumY() {
        return sumY;
    }

    public double sumXx() {
        return sumXx;
    }

    public double sumXy() {
        return sumXy;
    }

    public void add(double x, double y, double dt) {
        double decay = 1.0;
        if (horizon > 0.0 && dt > 0.0) {
            double ratio = dt / horizon;
            decay = ratio < 30.0 ? Math.pow(Math.E, -ratio) : 0.0;
        }
        weight =  weight * decay + 1.0;
        sumX = sumX * decay + x;
        sumY = sumY * decay + y;
        sumXx = sumXx * decay + x * x;
        sumXy = sumXy * decay + x * y;
    }                   // bleknar det gamla med dt, lägger till provet

    public void fade(double dt) {
        if (horizon <= 0.0 || dt <= 0.0) {
            return;
        }
        double ratio = dt / horizon;
        double decay = ratio < 30.0 ? Math.pow(Math.E, -ratio) : 0.0;
        weight *= decay;
        sumX *= decay;
        sumY *= decay;
        sumXx *= decay;
        sumXy *= decay;
    }                   // låter tid gå utan prov

    public double meanX() {
        return weight > 0.0 ? sumX / weight : 0.0;
    }

    public double meanY() {
        return weight > 0.0 ? sumY / weight : 0.0;
    }

    public double varianceX() {
        return weight <= 0.0 ? 0.0 : Math.max(0.0, sumXx / weight - meanX() * meanX());
    }                   // aldrig under noll

    public Fit fit(double minVariance) {
        if (weight < 2.0) {
            return new Fit(0.0, 0.0, false);
        }

        double variance = varianceX();
        if (variance < minVariance) {
            return new Fit(meanY(), 0.0, false);
        }

        double covariance = sumXy / weight - meanX() * meanY();
        double slope = covariance / variance;
        return new Fit(meanY() - slope * meanX(), slope, true);
    }
}
