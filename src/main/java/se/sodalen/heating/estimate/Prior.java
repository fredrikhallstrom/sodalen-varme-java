package se.sodalen.heating.estimate;

// Det fysiken redan säger om en koefficient: väntat värde och hur mycket det väger.
public record Prior(double expected, double strength) {
}
