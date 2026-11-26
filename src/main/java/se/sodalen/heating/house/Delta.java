package se.sodalen.heating.house;

// Deltatermens svar, Pythons tupel (delta, expected, trim) ur _delta: det uppmätta deltat
// tillopp minus retur (null utan givare), det väntade (null när termen är tyst) och
// korrigeringen av tilloppet i grader (0.0 när den är tyst).
public record Delta(Double delta, Double expected, double trim) {
}
