package se.sodalen.heating.estimate;

// Linjen y = intercept + slope * x, och om lutningen alls finns i datan.
public record Fit(double intercept, double slope, boolean identifiable) {
}
