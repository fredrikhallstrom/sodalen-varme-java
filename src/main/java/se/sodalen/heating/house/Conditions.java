package se.sodalen.heating.house;

// Vädret i ett ögonblick, som modellen vill ha det, och klockan: hushållet är en del av vädret
// inomhus. null där Python har None: vinden, riktningen och timmen kan saknas.
public record Conditions(double outdoor, Double wind, Double bearing, double sun, Double hour) {

    public Conditions(double outdoor) {
        this(outdoor, null, null, 0.0, null);
    }                   // ingen vind, ingen sol, ingen klocka

    public Conditions(double outdoor, Double wind, Double bearing) {
        this(outdoor, wind, bearing, 0.0, null);
    }

    public Conditions(double outdoor, Double wind, Double bearing, double sun) {
        this(outdoor, wind, bearing, sun, null);
    }

    public Conditions withHour(Double hour) {
        return new Conditions(outdoor, wind, bearing, sun, hour);
    }                   // samma väder, annan timme: Pythons replace(conditions, hour=...)
}
