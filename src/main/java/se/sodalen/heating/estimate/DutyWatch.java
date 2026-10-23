package se.sodalen.heating.estimate;

import java.util.Map;

// Hur stor del av tiden något höll, över ett fönster som bleknar.
public final class DutyWatch {
    double seconds;
    Double share;
    double weight;

    public DutyWatch(double seconds) {
        this.seconds = seconds;
    }                   // fönstret

    public double seconds() {
        return seconds;
    }

    public Double share() {
        return share;
    }                   // andelen 0-1, null tills något kunnat avgöras

    public double weight() {
        return weight;
    }                   // sekunder som räknats, med glömskan

    public double hours() {
        return weight / 3600.0;
    }                   // weight i timmar

    public Map<String, Double> asDict() {
        return Map.of("seconds", seconds, "share", share, "weight", weight);
    }

    public void update(Boolean held, double dt) {
        if (dt <= 0.0)
            return;
        double ratio = seconds > 0.0 ? dt / seconds : 30.0;
        weight *= ratio < 30.0 ? Math.pow(Math.E, -ratio) : 0.0;
        if (held == null)
            return;
        double total = weight + dt;
        double sample = held ? 1.0 : 0.0;
        share = share == null ? sample : share + (sample -share) * dt / total;
        weight = total;
    }                   // null: gick inte att avgöra, men tiden går ändå
}
