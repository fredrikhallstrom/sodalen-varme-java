package se.sodalen.heating.house;

// Vad kurvan begär vid en utetemperatur, Pythons tupel (curve_flow, effective_slope) ur _curve:
// tilloppet och lutningen det räknades med.
public record CurvePoint(double flow, double slope) {
}
