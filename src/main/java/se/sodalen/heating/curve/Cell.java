package se.sodalen.heating.curve;

// Ett facks mätningar från en del av dygnet.
public final class Cell {

    private double weight;
    private double mean;

    private double m2;
    private double outdoor;
    private double wind;
    private double age;

    public double weight() {
        return weight;
    }                   // timmar mätning, med glömskan

    public double mean() {
        return mean;
    }                   // tillopp över rumsmålet, K: det löpande medelvärdet

    public double m2() {
        return m2;
    }                   // vägd summa av kvadrerade avvikelser från medelvärdet

    public double outdoor() {
        return outdoor;
    }                   // var i facket mätningarna låg

    public double wind() {
        return wind;
    }

    public double age() {
        return age;
    }                   // timmar sedan senaste mätningen

    public void add(double need, double outdoor, double wind, double hours) {

        double total = weight + hours;
        if (total <= 0.0) {
            return;
        }

        double delta = need - mean;
        mean += delta * hours / total;
        m2 += hours * delta * (need - mean);
        this.outdoor += (outdoor - this.outdoor) * hours / total;
        this.wind += (wind - this.wind) * hours / total;
        weight = total;
        age = 0.0;
    }

    public void fade(double factor, double hours) {
        weight *= factor;
        m2 *= factor;
        age += hours;
    }
}
