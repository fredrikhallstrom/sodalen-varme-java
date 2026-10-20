package se.sodalen.heating.pid;

public record PidResult(double output, double integral, double error,
                        double proportional, double derivative, boolean saturated) {
}
