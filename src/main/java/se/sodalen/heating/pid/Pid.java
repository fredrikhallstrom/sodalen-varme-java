package se.sodalen.heating.pid;

public final class Pid {
    public static PidResult step(double setpoint, double measurement, Double lastMeasurement,
                                 double integral, double dt, PidGains gains) {

        boolean saturated = false;

        double error = setpoint - measurement;
        double working;
        if (error < -gains.deadband()) {
            working = error + gains.deadband();
        } else if (error > gains.deadband()) {
            working = error - gains.deadband();
        } else {
            working = 0.0;
        }


        double proportional = working * gains.kp();
        double derivative = calculateDerivative(measurement, lastMeasurement, dt, gains);

        integral = calculateIntegral(integral, dt, gains, working, saturated);


        double output = proportional + integral + (lastMeasurement != null ? derivative : 0.0);

        double outputCompare = output;
        output = limitOutput(gains, output);

        if (output != outputCompare) {
            saturated = true;
        }

        if (saturated) {
            integral = output - proportional - derivative;
        }

        integral = limitIntegral(gains, integral);

        return new PidResult(output, integral, error, proportional, derivative, saturated);

    }

    private static double calculateIntegral(double integral, double dt, PidGains gains, double error, boolean saturated) {
        if (gains.ti() > 0 && dt > 0) {
            integral += gains.kp() / gains.ti() * error * dt;

        }
        return integral;
    }

    private static double limitOutput(PidGains gains, double output) {
        if (output < gains.outMin()) {
            output = gains.outMin();
        } else if (output > gains.outMax()) {
            output = gains.outMax();
        }
        return output;
    }

    private static double calculateDerivative(double measurement, Double lastMeasurement, double dt, PidGains gains) {
        double derivative;
        if (gains.td() > 0 && dt > 0 && lastMeasurement != null) {
            derivative = (measurement - lastMeasurement) * gains.td() / dt * -gains.kp();
        } else {
            derivative = 0.0;
        }
        return derivative;
    }

    private static double limitIntegral(PidGains gains, double integral) {
        if (integral < gains.outMin()) {
            integral = gains.outMin();
        } else if (integral > gains.outMax()) {
            integral = gains.outMax();
        }
        return integral;
    }

    public static double bumplessIntegral(double position, double proportional, double derivative) {
        position = position - proportional - derivative;
        if (position < 0.0) {
            position = 0.0;
        } else if (position > 100.0) {
            position = 100.0;
        }
        return position;
    }

    public static double bumplessIntegral(double position) {
        return bumplessIntegral(position, 0.0, 0.0);
    }
}

