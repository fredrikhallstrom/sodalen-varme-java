package se.sodalen.heating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * The inner loop: supply temperature error in, valve position out.
 * Positional PI with a derivative on the measurement, back-calculated
 * anti-windup, and a deadband that shrinks the error instead of zeroing it.
 *
 * <p>Python: pid.py. It has no tests of its own there - it is tested through
 * the controller - so these are written from its comments.
 */
class PidTest {

    static final double EXACT = 1e-12;

    /** Kp 2, no I, no D, 0-100 %. */
    static final PidGains P_ONLY = new PidGains(2.0, 0.0, 0.0);

    static PidResult step(double setpoint, double measurement, PidGains gains) {
        return Pid.step(setpoint, measurement, null, 0.0, 30.0, gains);
    }

    @Nested
    class TheGains {
        @Test
        void withoutDeadbandAndLimitsItIsZeroDeadbandAndNoughtToAHundred() {
            PidGains gains = new PidGains(1.5, 300.0, 10.0);
            assertEquals(new PidGains(1.5, 300.0, 10.0, 0.0, 0.0, 100.0), gains);
        }
    }

    @Nested
    class TheProportionalPart {
        @Test
        void theErrorIsSetpointLessMeasurement() {
            PidResult out = step(45.0, 40.0, P_ONLY);
            assertEquals(5.0, out.error(), EXACT);
            assertEquals(10.0, out.proportional(), EXACT);
            assertEquals(10.0, out.output(), EXACT);
        }

        /** The output is a position, not a nudge: asked twice, it says the same. */
        @Test
        void itIsAPositionNotANudge() {
            assertEquals(step(45.0, 40.0, P_ONLY), step(45.0, 40.0, P_ONLY));
        }
    }

    /**
     * Shrinking the error by the deadband rather than snapping it to zero keeps
     * the response continuous: a 0.21 K error with a 0.2 K deadband asks for a
     * hair of correction.
     */
    @Nested
    class TheDeadband {
        final PidGains gains = new PidGains(2.0, 0.0, 0.0, 0.2, 0.0, 100.0);

        @Test
        void insideItAsksForNothing() {
            assertEquals(0.0, step(45.0, 44.9, gains).proportional(), EXACT);
        }

        @Test
        void outsideItShrinksTheErrorRatherThanZeroingIt() {
            assertEquals(2.0 * 0.01, step(45.0, 44.79, gains).proportional(), 1e-9);
            assertEquals(2.0 * -0.3, step(45.0, 45.5, gains).proportional(), 1e-9);
        }

        @Test
        void theErrorReportedIsTheWholeOne() {
            assertEquals(0.1, step(45.0, 44.9, gains).error(), 1e-9);
        }
    }

    @Nested
    class TheIntegral {
        @Test
        void itGrowsByKpOverTiTimesErrorTimesDt() {
            PidResult out = Pid.step(45.0, 40.0, null, 20.0, 30.0, new PidGains(2.0, 300.0, 0.0));
            assertEquals(20.0 + 2.0 / 300.0 * 5.0 * 30.0, out.integral(), EXACT);
            assertEquals(10.0 + 21.0, out.output(), EXACT);
        }

        @Test
        void tiNoughtMeansNoIntegralAction() {
            PidResult out = Pid.step(45.0, 40.0, null, 20.0, 30.0, P_ONLY);
            assertEquals(20.0, out.integral(), EXACT);
        }

        @Test
        void noTimeNoChange() {
            PidResult out = Pid.step(45.0, 40.0, null, 20.0, 0.0, new PidGains(2.0, 300.0, 0.0));
            assertEquals(20.0, out.integral(), EXACT);
        }

        @Test
        void itStaysWithinTheOutputRange() {
            PidResult out = Pid.step(45.0, 45.0, null, 150.0, 30.0, new PidGains(2.0, 300.0, 0.0));
            assertEquals(100.0, out.integral(), EXACT);
        }
    }

    /**
     * On the measurement, not the error: the outer loop drops the setpoint by
     * several degrees the instant the override contact closes, and derivative
     * on error would answer that step with a kick at the valve.
     */
    @Nested
    class TheDerivative {
        final PidGains gains = new PidGains(2.0, 0.0, 15.0);

        @Test
        void itAnswersTheMeasurementMoving() {
            PidResult out = Pid.step(45.0, 41.0, 40.0, 50.0, 30.0, gains);
            assertEquals(-2.0 * 15.0 * 1.0 / 30.0, out.derivative(), EXACT);
        }

        @Test
        void notTheSetpointMoving() {
            PidResult before = Pid.step(45.0, 40.0, 40.0, 50.0, 30.0, gains);
            PidResult after = Pid.step(35.0, 40.0, 40.0, 50.0, 30.0, gains);
            assertEquals(0.0, before.derivative(), EXACT);
            assertEquals(0.0, after.derivative(), EXACT);
        }

        @Test
        void withoutALastMeasurementThereIsNone() {
            assertEquals(0.0, Pid.step(45.0, 41.0, null, 50.0, 30.0, gains).derivative(), EXACT);
        }

        @Test
        void norWithoutTime() {
            assertEquals(0.0, Pid.step(45.0, 41.0, 40.0, 50.0, 0.0, gains).derivative(), EXACT);
        }
    }

    @Nested
    class TheLimits {
        @Test
        void theOutputIsHeldWithinThemAndSaysSo() {
            PidResult high = step(80.0, 20.0, P_ONLY);
            PidResult low = step(20.0, 80.0, P_ONLY);
            assertEquals(100.0, high.output(), EXACT);
            assertEquals(0.0, low.output(), EXACT);
            assertTrue(high.saturated());
            assertTrue(low.saturated());
            assertFalse(step(45.0, 40.0, P_ONLY).saturated());
        }

        @Test
        void otherLimitsToo() {
            PidGains gains = new PidGains(2.0, 0.0, 0.0, 0.0, 5.0, 80.0);
            assertEquals(80.0, step(80.0, 20.0, gains).output(), EXACT);
            assertEquals(5.0, step(20.0, 80.0, gains).output(), EXACT);
        }

        /**
         * Back-calculation: the integral is put where it would have had to be
         * for the limited output to be what the controller asked for. That is
         * what lets the valve come off an end stop on the first cycle the error
         * changes sign, instead of after however long it takes to unwind an
         * integral that ran away while the valve was already open.
         */
        @Test
        void atALimitTheIntegralIsWhatTheLimitNeeds() {
            PidGains gains = new PidGains(2.0, 300.0, 0.0);
            PidResult out = Pid.step(60.0, 40.0, null, 90.0, 30.0, gains);
            assertEquals(100.0, out.output(), EXACT);
            assertEquals(100.0 - 40.0, out.integral(), EXACT);
        }

        @Test
        void soTheValveComesOffTheStopTheFirstCycleTheErrorTurns() {
            PidGains gains = new PidGains(2.0, 300.0, 0.0);
            double integral = 90.0;
            for (int i = 0; i < 20; i++) {
                integral = Pid.step(60.0, 40.0, null, integral, 30.0, gains).integral();
            }
            assertEquals(60.0, integral, EXACT, "held where the stop needs it, not run away");
            PidResult turned = Pid.step(60.0, 60.5, null, integral, 30.0, gains);
            assertEquals(-1.0 + 60.0 - 0.1, turned.output(), 1e-9, "off the stop at once");
        }
    }

    /**
     * The integral that makes the loop's first output equal the position the
     * valve is already at. Starting from nought would slam the valve shut on
     * the first cycle after every restart.
     */
    @Nested
    class Bumpless {
        @Test
        void theFirstOutputIsWhereTheValveIs() {
            PidGains gains = new PidGains(2.0, 300.0, 0.0);
            double integral = Pid.bumplessIntegral(37.0, 2.0 * 1.5, 0.0);
            PidResult out = Pid.step(45.0, 43.5, null, integral, 0.0, gains);
            assertEquals(37.0, out.output(), EXACT);
        }

        @Test
        void withNothingElseItIsThePosition() {
            assertEquals(37.0, Pid.bumplessIntegral(37.0), EXACT);
        }

        @Test
        void neverOutsideNoughtToAHundred() {
            assertEquals(0.0, Pid.bumplessIntegral(5.0, 10.0, 0.0), EXACT);
            assertEquals(100.0, Pid.bumplessIntegral(95.0, -10.0, 0.0), EXACT);
        }
    }

    /** 2000 + 300 cases worked out by the Python version (verktyg/facit.py). */
    @Nested
    class SameAsPython {
        static final double CLOSE = 1e-9;

        @ParameterizedTest
        @CsvFileSource(resources = "/pid_step.csv", delimiter = ';', numLinesToSkip = 1)
        void sameStep(double setpoint, double measurement, Double lastMeasurement, double integral, double dt,
                      double kp, double ti, double td, double deadband, double outMin, double outMax,
                      double output, double integralOut, double error, double proportional, double derivative,
                      boolean saturated) {
            PidResult out = Pid.step(setpoint, measurement, lastMeasurement, integral, dt,
                    new PidGains(kp, ti, td, deadband, outMin, outMax));
            assertEquals(output, out.output(), CLOSE, "output");
            assertEquals(integralOut, out.integral(), CLOSE, "integral");
            assertEquals(error, out.error(), CLOSE, "error");
            assertEquals(proportional, out.proportional(), CLOSE, "proportional");
            assertEquals(derivative, out.derivative(), CLOSE, "derivative");
            assertEquals(saturated, out.saturated(), "saturated");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/bumpless.csv", delimiter = ';', numLinesToSkip = 1)
        void sameBumpless(double position, double proportional, double derivative, double expected) {
            assertEquals(expected, Pid.bumplessIntegral(position, proportional, derivative), CLOSE);
        }
    }
}
