package se.sodalen.heating.pid;

public record PidGains(double kp, double ti, double td, double deadband, double outMin, double outMax) {
    public PidGains(double kp, double ti, double td) {
        this(kp, ti, td, 0, 0, 100);
    }
}
