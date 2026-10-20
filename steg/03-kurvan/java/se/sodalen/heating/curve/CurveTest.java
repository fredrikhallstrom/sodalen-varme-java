package se.sodalen.heating.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The heating curve: supply temperature from outdoor temperature.
 *
 * <pre>
 *     supply = room + slope * span * B ^ curvature
 *     B      = (balance - outdoor) / (balance - design_outdoor), never below 0
 * </pre>
 *
 * <p>Python: curve.py and tests/test_curve.py. The learning tests there
 * (TestLearningSplit, TestStoredOffset) need the controller and come with it.
 */
class CurveTest {

    /** Tuning.curve_curvature - the default bend, until the model has its Tuning. */
    static final double CURVATURE = 0.8;

    /**
     * The supply the test house needs to hold 21 degrees, from its own
     * physics (tests/house.py): 5 kW at -18 outside, radiators rated 55/45 at
     * 21 with EN 442's exponent 1.3, a 10 K drop across them at design.
     */
    static double steadySupply(double outdoor) {
        double loss = 128.0;     // W/K of envelope loss: 5 kW at a 39 K difference
        double radiator = 58.8;  // 5 kW at a 29 K mean-water-to-room difference
        double water = 500.0;    // W/K of circulating water: 5 kW across 10 K
        double need = loss * (21.0 - outdoor);
        double meanOverRoom = Math.pow(need / radiator, 1.0 / 1.3);
        return 21.0 + meanOverRoom + need / water / 2.0;
    }

    @Nested
    class TheShape {
        /**
         * The test house's radiators follow EN 442's exponent 1.3 with the pump
         * on a fixed speed; the default curve through its design point has to
         * give what it needs everywhere else too. The old 1.1 was four degrees
         * short at +10.
         */
        @ParameterizedTest(name = "{0} ute")
        @ValueSource(doubles = {15.0, 10.0, 0.0, -10.0})
        void theDefaultCurvatureIsWhatRadiatorsNeed(double outdoor) {
            double slope = (steadySupply(-18.0) - 21.0) / 39.0;
            assertEquals(steadySupply(outdoor), Curve.supply(outdoor, 21.0, -18.0, slope, CURVATURE, 21.0), 0.2);
        }

        @ParameterizedTest(name = "balans {0}")
        @ValueSource(doubles = {8.0, 15.0, 21.0, 23.0})
        void theBalanceNeverMovesTheColdEnd(double balance) {
            assertEquals(21.0 + 0.9 * 39.0, Curve.supply(-18.0, 21.0, -18.0, 0.9, 0.8, balance), 1e-9);
        }

        @Test
        void aboveTheBalanceThereIsNothingToAskFor() {
            assertEquals(21.0, Curve.supply(14.0, 21.0, -18.0, 0.9, 0.8, 12.0));
        }

        @Test
        void balanceThroughIsTheInverse() {
            double[][] cases = {{10.0, 14.0}, {0.0, 19.0}, {-12.0, 12.0}};
            for (double[] c : cases) {
                double wanted = Curve.supply(c[0], 21.0, -18.0, 0.9, 0.8, c[1]);
                assertEquals(c[1], Curve.balanceThrough(c[0], wanted, 21.0, -18.0, 0.9, 0.8), 1e-6);
            }
        }

        /** No heat wanted at this weather: the curve starts right here. */
        @Test
        void noHeatWantedPutsTheBalanceAtTheWeather() {
            assertEquals(7.0, Curve.balanceThrough(7.0, 20.0, 21.0, -18.0, 0.9, 0.8));
        }
    }

    /**
     * The curve numbers on the EVR controller's curve map, read off the one on
     * the boiler room wall: straight lines from 20 supply at +20 outside.
     */
    @Nested
    class TheEvrScale {
        @Test
        void aWholeEvrCurveGivesThatCurvesSupplyAtDesignCold() {
            for (int number = 0; number < Curve.EVR_RISE.size(); number++) {
                double slope = Curve.evrSlope(number, 21.0, -18.0);
                assertEquals(20.0 + Curve.EVR_RISE.get(number) * 38.0, 21.0 + slope * 39.0, 1e-9, "EVR " + number);
            }
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.3, 0.5, 0.86, 0.9, 1.0, 1.5, 2.0, 2.9})
        void theTwoDirectionsAgree(double slope) {
            double number = Curve.evrNumber(slope, 21.0, -18.0);
            assertEquals(slope, Curve.evrSlope(number, 21.0, -18.0), 1e-9);
        }

        @Test
        void theNumbersRiseWithTheSlope() {
            List<Double> numbers = new ArrayList<>();
            for (int s = 3; s < 30; s++) {
                numbers.add(Curve.evrNumber(s / 10.0, 21.0, -18.0));
            }
            assertEquals(numbers.stream().sorted().toList(), numbers);
        }

        /** 0.9, the slope set here, lands a tenth above EVR 3. */
        @Test
        void thisHouseIsAboutCurveThree() {
            assertEquals(3.1, Curve.evrNumber(0.9, 21.0, -18.0), 0.05);
        }

        /** Read off a wall: nobody may write on it from the code. */
        @Test
        void theMapCannotBeChanged() {
            assertThrows(UnsupportedOperationException.class, () -> Curve.EVR_RISE.set(0, 9.9));
        }
    }

    /**
     * Degrees more supply the house needs for each degree warmer inside - what
     * turns "the house sat 0.3 below target" into "it would have needed this
     * much more". About two at nought outside with the balance at 17.
     */
    @Nested
    class IndoorSensitivity {
        @Test
        void aboutTwoAtNoughtOutside() {
            assertEquals(2.0, Curve.indoorSensitivity(0.0, 21.0, -18.0, 0.9, 0.8, 17.0), 0.05);
        }
    }

    /**
     * How far a curvature moves the curve, at most, from the same curve with
     * the reference curvature - in degrees of supply, signed.
     */
    @Nested
    class MiddleDeviation {
        @Test
        void theSameBendDoesNotMoveIt() {
            assertEquals(0.0, Curve.middleDeviation(21.0, -18.0, 0.9, 17.0, 0.8, 0.8));
        }

        @Test
        void aStraighterCurveIsLowerInTheMiddle() {
            assertEquals(-2.9, Curve.middleDeviation(21.0, -18.0, 0.9, 17.0, 1.0, 0.8), 0.05);
        }

        @Test
        void aBalanceBelowTheDesignColdHasNoMiddle() {
            assertEquals(0.0, Curve.middleDeviation(21.0, -18.0, 0.9, -20.0, 1.2, 0.8));
        }
    }

    /** Cases worked out by the Python version (verktyg/facit.py). */
    @Nested
    class SameAsPython {
        static final double CLOSE = 1e-9;

        @ParameterizedTest
        @CsvFileSource(resources = "/supply.csv", delimiter = ';', numLinesToSkip = 1)
        void sameSupply(double outdoor, double room, double designOutdoor, double slope, double curvature,
                        double balance, double expected) {
            assertEquals(expected, Curve.supply(outdoor, room, designOutdoor, slope, curvature, balance), CLOSE);
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/balance_through.csv", delimiter = ';', numLinesToSkip = 1)
        void sameBalanceThrough(double outdoor, double wanted, double room, double designOutdoor, double slope,
                                double curvature, double expected) {
            assertEquals(expected, Curve.balanceThrough(outdoor, wanted, room, designOutdoor, slope, curvature),
                    CLOSE);
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/indoor_sensitivity.csv", delimiter = ';', numLinesToSkip = 1)
        void sameIndoorSensitivity(double outdoor, double room, double designOutdoor, double slope,
                                   double curvature, double balance, double expected) {
            assertEquals(expected,
                    Curve.indoorSensitivity(outdoor, room, designOutdoor, slope, curvature, balance), CLOSE);
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/evr.csv", delimiter = ';', numLinesToSkip = 1)
        void sameEvr(double slope, double room, double designOutdoor, double number, double givenNumber,
                     double slopeForGivenNumber) {
            assertEquals(number, Curve.evrNumber(slope, room, designOutdoor), CLOSE, "evrNumber");
            assertEquals(slopeForGivenNumber, Curve.evrSlope(givenNumber, room, designOutdoor), CLOSE, "evrSlope");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/middle_deviation.csv", delimiter = ';', numLinesToSkip = 1)
        void sameMiddleDeviation(double room, double designOutdoor, double slope, double balance,
                                 double curvature, double reference, double expected) {
            assertEquals(expected,
                    Curve.middleDeviation(room, designOutdoor, slope, balance, curvature, reference), CLOSE);
        }
    }
}
