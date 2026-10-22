package se.sodalen.heating.curve;

// När ett fack räknas, och hur mycket.
public record Rules(double redHours, double greenHours, double greenSpread, int greenSectors, double sectorHours) {

    public Rules() {
        this(3.0, 24.0, 1.5, 3, 1.0);
    }                   // 3 h, 24 h, 1,5 K, 3 delar, 1 h per del
}
