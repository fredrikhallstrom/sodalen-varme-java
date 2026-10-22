package se.sodalen.heating.curve;

// Ett fack som kurvanpassningen och diagrammet ser det.
public record Bin(double outdoor, double need, double hours, double spread, int sectors,
                  double wind, double age, Status status, double weight) {
}
