package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.house.HeatingController.fuseIndoor;
import static se.sodalen.heating.house.Model.ROLE_CEILING;
import static se.sodalen.heating.house.Model.ROLE_ROOM;
import static se.sodalen.heating.house.Model.ROLE_SOLAR;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.assertSameDict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * Three indoor sensors into the one number the outer loop regulates on. Each
 * reading gets its calibration offset and, while the supply runs over the
 * room target, a stratification term taken off it; the sun room is held from
 * above against the rest of the house, never from below; and a FusedSensor
 * per reading says what the fusion made of it.
 *
 * <p>Python: controller.fuse_indoor, tests/test_controller.py
 * (TestIndoorFusion) and tests/test_calibration.py.
 */
class IndoorFusionTest {

    static SensorReading room(Double temperature) {
        return new SensorReading("rum", temperature, ROLE_ROOM, 1.0);
    }

    static SensorReading room(Double temperature, double offset) {
        return new SensorReading("rum", temperature, ROLE_ROOM, 1.0, offset, 0.0);
    }

    static SensorReading sensor(String key, Double temperature, String role, double weight) {
        return new SensorReading(key, temperature, role, weight);
    }

    static SensorReading inactive(String key, double temperature) {
        return new SensorReading(key, temperature, ROLE_ROOM, 1.0, 0.0, 0.0, null, false, false);
    }

    static SensorReading featured(String key, double temperature) {
        return new SensorReading(key, temperature, ROLE_ROOM, 1.0, 0.0, 0.0, null, true, true);
    }

    static List<String> keys(IndoorFusion fused) {
        return fused.details().stream().map(FusedSensor::key).toList();
    }

    static FusedSensor detail(IndoorFusion fused, String key) {
        return fused.details().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
    }

    /** TestIndoorFusion. */
    @Nested
    class TheFusion {
        final Tuning tuning = new Tuning();

        @Test
        void stratificationIsTakenOffCeilingSensors() {
            List<SensorReading> readings = List.of(new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.06));
            IndoorFusion fused = fuseIndoor(readings, tuning, 45.0);
            assertEquals(22.5 - 0.06 * (45.0 - 21.0), fused.temperature(), 1e-6);
            assertEquals(List.of("tak"), keys(fused));
            assertTrue(fused.details().get(0).contributed());
            assertEquals(22.5, fused.details().get(0).raw(), 1e-6);
            assertEquals(fused.temperature(), fused.details().get(0).corrected(), 1e-6);
        }

        @Test
        void stratificationScalesWithTheSupplyTemperature() {
            List<SensorReading> readings = List.of(new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.06));
            double mild = fuseIndoor(readings, tuning, 30.0).temperature();
            double cold = fuseIndoor(readings, tuning, 60.0).temperature();
            assertTrue(mild > cold, mild + " > " + cold);
        }

        @Test
        void calibrationOffsetIsApplied() {
            assertEquals(20.7, fuseIndoor(List.of(room(20.0, 0.7)), tuning, 45.0).temperature(), 1e-6);
        }

        /** A reference thermometer beside the others: read, never obeyed. */
        @Test
        void anInactiveSensorIsShownButNotFused() {
            IndoorFusion fused = fuseIndoor(List.of(room(21.0), inactive("ref", 25.0)), tuning, 45.0);
            assertEquals(21.0, fused.temperature(), 1e-6);
            assertEquals(25.0, fused.details().get(1).corrected());
            assertFalse(fused.details().get(1).contributed());
            assertEquals(false, fused.details().get(1).asDict().get("active"));
            IndoorFusion control = fuseIndoor(List.of(room(21.0), sensor("ref", 25.0, ROLE_ROOM, 1.0)), tuning, 45.0);
            assertEquals(23.0, control.temperature(), 1e-6, "active, it would count");
        }

        /** Featured is for the app's list; the fusion must not notice it. */
        @Test
        void aFeaturedSensorIsMarkedAndFusedLikeAnyOther() {
            IndoorFusion plain = fuseIndoor(List.of(room(21.0), sensor("kok", 23.0, ROLE_ROOM, 1.0)), tuning, 45.0);
            IndoorFusion fused = fuseIndoor(List.of(room(21.0), featured("kok", 23.0)), tuning, 45.0);
            assertEquals(plain.temperature(), fused.temperature(), 1e-9);
            assertEquals(List.of(false, true), fused.details().stream().map(d -> d.asDict().get("featured")).toList());
        }

        @Test
        void anInactiveSensorAloneIsNoIndoorTemperature() {
            assertNull(fuseIndoor(List.of(inactive("ref", 22.0)), tuning, 45.0).temperature());
        }

        @Test
        void sunBakedRoomIsClampedFromAbove() {
            List<SensorReading> readings = List.of(sensor("tak1", 21.0, ROLE_CEILING, 0.5),
                    sensor("tak2", 21.0, ROLE_CEILING, 0.5), sensor("sol", 30.0, ROLE_SOLAR, 0.15));
            double fused = fuseIndoor(readings, tuning, 45.0).temperature();
            double naive = (21.0 * 0.5 + 21.0 * 0.5 + 30.0 * 0.15) / 1.15;
            assertTrue(fused < naive, fused + " < " + naive);
            // Clamped to the rest of the house plus solar_clamp, then weighted.
            assertEquals((21.0 * 0.5 + 21.0 * 0.5 + 22.5 * 0.15) / 1.15, fused, 1e-6);
        }

        @Test
        void aClampedReadingSaysItWasClamped() {
            IndoorFusion fused = fuseIndoor(List.of(sensor("tak1", 21.0, ROLE_CEILING, 0.5),
                    sensor("sol", 30.0, ROLE_SOLAR, 0.15)), tuning, 45.0);
            assertTrue(detail(fused, "sol").clamped());
            assertFalse(detail(fused, "tak1").clamped());
            // The raw and corrected values are still reported honestly; only the
            // weight the fusion gave it was cut.
            assertEquals(30.0, detail(fused, "sol").corrected(), 1e-6);
        }

        @Test
        void aColdRoomIsNotClamped() {
            List<SensorReading> readings = List.of(sensor("tak1", 21.0, ROLE_CEILING, 0.5),
                    sensor("tak2", 21.0, ROLE_CEILING, 0.5), sensor("sol", 17.0, ROLE_SOLAR, 0.15));
            assertEquals((21.0 * 0.5 + 21.0 * 0.5 + 17.0 * 0.15) / 1.15,
                    fuseIndoor(readings, tuning, 45.0).temperature(), 1e-6);
        }

        @Test
        void solarAloneStillCounts() {
            IndoorFusion fused = fuseIndoor(List.of(sensor("sol", 25.0, ROLE_SOLAR, 0.15)), tuning, 45.0);
            assertEquals(25.0, fused.temperature(), 1e-6);
            assertFalse(fused.details().get(0).clamped(), "nothing to clamp against when it is the only sensor left");
        }

        @Test
        void unavailableAndZeroWeightSensorsAreSkipped() {
            IndoorFusion fused = fuseIndoor(List.of(room(null), sensor("ignorerad", 30.0, ROLE_ROOM, 0.0), room(20.0)),
                    tuning, 45.0);
            assertEquals(20.0, fused.temperature(), 1e-6);
            // Reported either way - a sensor that is not being listened to is
            // exactly the thing you want to see on a dashboard.
            assertEquals(3, fused.details().size());
            assertEquals(List.of(false, false, true), fused.details().stream().map(FusedSensor::contributed).toList());
            assertEquals(30.0, fused.details().get(1).raw(), 1e-6);
        }

        @Test
        void nothingAvailableSaysSo() {
            IndoorFusion fused = fuseIndoor(List.of(room(null)), tuning, 45.0);
            assertNull(fused.temperature());
            assertEquals(1, fused.details().size());
            assertFalse(fused.details().get(0).contributed());
            IndoorFusion none = fuseIndoor(List.of(), tuning, 45.0);
            assertNull(none.temperature());
            assertEquals(List.of(), none.details());
        }
    }

    /** test_calibration.py: the fusion reading a calibrated sensor back. */
    @Nested
    class TheCalibration {
        Double corrected(double raw, double offset, double stratification, Double flow, double target) {
            Tuning tuning = new Tuning();
            tuning.roomTarget = target;
            SensorReading reading = new SensorReading("sensor.tak_hall", raw, ROLE_ROOM, 1.0, offset, stratification);
            return fuseIndoor(List.of(reading), tuning, flow).details().get(0).corrected();
        }

        /** calibrate(reference=20.6, raw=21.9) gives the offset -1.3, and the fusion then reads the reference. */
        @Test
        void theFusionThenReadsTheReference() {
            assertEquals(20.6, corrected(21.9, 20.6 - 21.9, 0.0, 40.0, 21.0), 1e-9);
        }

        /**
         * The fusion takes stratification * (flow - target) off a ceiling sensor; the offset is
         * worked out with it, so the corrected reading is the reference at that moment - and
         * the term still moves with the flow afterwards, as it should.
         */
        @Test
        void aCeilingSensorWithHotRadiatorsToo() {
            double offset = 21.0 - (22.5 - 0.05 * (45.0 - 21.0));
            assertEquals(21.0, corrected(22.5, offset, 0.05, 45.0, 21.0), 1e-9);
            assertEquals(21.75, corrected(22.5, offset, 0.05, 30.0, 21.0), 1e-9);
        }

        /** stratification_term: nothing without a flow, nothing below the target. */
        @Test
        void theTermIsTheFusions() {
            assertEquals(22.5, corrected(22.5, 0.0, 0.05, null, 21.0), 0.0);
            assertEquals(22.5, corrected(22.5, 0.0, 0.05, 18.0, 21.0), 0.0, "below the target is none");
            assertEquals(21.5, corrected(22.5, 0.0, 0.05, 41.0, 21.0), 1e-9);
        }

        @Test
        void theFusionReportsTheOffsetItUsed() {
            SensorReading reading = new SensorReading("s", 21.0, ROLE_ROOM, 1.0, -0.4, 0.0);
            assertEquals(-0.4, fuseIndoor(List.of(reading), new Tuning(), null).details().get(0).asDict().get("offset"));
        }
    }

    /** Behaviour the Python tests do not pin, read off controller.fuse_indoor. */
    @Nested
    class FromTheCode {
        final Tuning tuning = new Tuning();

        @Test
        void oneDetailPerReadingInTheirOrder() {
            List<SensorReading> readings = List.of(room(null), sensor("b", 22.0, ROLE_SOLAR, 0.15),
                    sensor("c", 20.0, ROLE_CEILING, 0.5), inactive("d", 19.0));
            assertEquals(List.of("rum", "b", "c", "d"), keys(fuseIndoor(readings, tuning, 45.0)));
        }

        @Test
        void theDetailsCarryTheReadingsFields() {
            SensorReading reading = new SensorReading("x", 21.4, ROLE_CEILING, 0.7, -0.3, 0.0, 600.0, true, true);
            FusedSensor item = fuseIndoor(List.of(reading), tuning, 45.0).details().get(0);
            assertEquals("x", item.key());
            assertEquals(ROLE_CEILING, item.role());
            assertEquals(21.4, item.raw());
            assertEquals(21.4 - 0.3, item.corrected(), 1e-12);
            assertEquals(0.7, item.weight(), 0.0);
            assertFalse(item.clamped());
            assertEquals(-0.3, item.offset(), 0.0);
            assertTrue(item.active());
            assertTrue(item.featured());
        }

        /** max(0.0, reading.weight): a weight below nought is no weight, and is reported as nought. */
        @Test
        void aWeightBelowNoughtIsNoWeight() {
            IndoorFusion fused = fuseIndoor(List.of(room(21.0), sensor("neg", 30.0, ROLE_ROOM, -0.3)), tuning, 45.0);
            assertEquals(21.0, fused.temperature(), 1e-12);
            assertEquals(0.0, fused.details().get(1).weight(), 0.0);
            assertFalse(fused.details().get(1).contributed());
        }

        @Test
        void aMissingTemperatureIsNoValueEvenWithAnOffset() {
            FusedSensor item = fuseIndoor(List.of(new SensorReading("x", null, ROLE_ROOM, 1.0, 0.5, 0.06)), tuning, 45.0)
                    .details().get(0);
            assertNull(item.raw());
            assertNull(item.corrected());
            assertEquals(0.5, item.offset(), 0.0);
        }

        /** if reading.stratification and flow is not None: none of it without a flow, or with none to take. */
        @Test
        void noStratificationWithoutAFlowOrWithoutAnyToTake() {
            SensorReading flat = new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.0);
            assertEquals(22.5, fuseIndoor(List.of(flat), tuning, 60.0).temperature(), 0.0);
            SensorReading strat = new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.06);
            assertEquals(22.5, fuseIndoor(List.of(strat), tuning, null).temperature(), 0.0);
        }

        /** max(0.0, flow - room_target): a supply under the target adds nothing back. */
        @Test
        void aSupplyUnderTheTargetTakesNothingOff() {
            SensorReading strat = new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.06);
            assertEquals(22.5, fuseIndoor(List.of(strat), tuning, 15.0).temperature(), 0.0);
        }

        @Test
        void theTargetIsTheTunings() {
            Tuning warm = new Tuning();
            warm.roomTarget = 23.0;
            SensorReading strat = new SensorReading("tak", 22.5, ROLE_CEILING, 1.0, 0.0, 0.06);
            assertEquals(22.5 - 0.06 * (45.0 - 23.0), fuseIndoor(List.of(strat), warm, 45.0).temperature(), 1e-12);
        }

        /** The reference is the rest of the house, weighted: 20 at 1 and 22 at 3 is 21.5, and 30 is held at 23. */
        @Test
        void theSunRoomIsHeldAgainstTheRestWeighted() {
            List<SensorReading> readings = List.of(sensor("a", 20.0, ROLE_ROOM, 1.0), sensor("b", 22.0, ROLE_CEILING, 3.0),
                    sensor("sol", 30.0, ROLE_SOLAR, 0.5));
            assertEquals((20.0 * 1.0 + 22.0 * 3.0 + 23.0 * 0.5) / 4.5, fuseIndoor(readings, tuning, 45.0).temperature(),
                    1e-12);
        }

        @Test
        void theClampIsTheTunings() {
            Tuning tight = new Tuning();
            tight.solarClamp = 0.5;
            List<SensorReading> readings = List.of(sensor("a", 21.0, ROLE_ROOM, 1.0), sensor("sol", 30.0, ROLE_SOLAR, 1.0));
            assertEquals((21.0 + 21.5) / 2.0, fuseIndoor(readings, tight, 45.0).temperature(), 1e-12);
        }

        /** capped < value: exactly on the clamp is not clamped. */
        @Test
        void exactlyAtTheClampIsNotClamped() {
            IndoorFusion fused = fuseIndoor(List.of(sensor("a", 21.0, ROLE_ROOM, 1.0), sensor("sol", 22.5, ROLE_SOLAR, 0.15)),
                    tuning, 45.0);
            assertFalse(detail(fused, "sol").clamped());
            IndoorFusion over = fuseIndoor(List.of(sensor("a", 21.0, ROLE_ROOM, 1.0), sensor("sol", 22.6, ROLE_SOLAR, 0.15)),
                    tuning, 45.0);
            assertTrue(detail(over, "sol").clamped());
        }

        @Test
        void twoSunRoomsAreEachHeld() {
            IndoorFusion fused = fuseIndoor(List.of(sensor("a", 21.0, ROLE_ROOM, 1.0), sensor("sol1", 30.0, ROLE_SOLAR, 0.5),
                    sensor("sol2", 22.0, ROLE_SOLAR, 0.5)), tuning, 45.0);
            assertTrue(detail(fused, "sol1").clamped());
            assertFalse(detail(fused, "sol2").clamped());
            assertEquals((21.0 + 22.5 * 0.5 + 22.0 * 0.5) / 2.0, fused.temperature(), 1e-12);
        }

        /** others or contributing: sun rooms alone are their own reference, and nothing holds them. */
        @Test
        void sunRoomsAloneAreNotHeld() {
            IndoorFusion fused = fuseIndoor(List.of(sensor("sol1", 30.0, ROLE_SOLAR, 0.15), sensor("sol2", 24.0, ROLE_SOLAR, 0.15)),
                    tuning, 45.0);
            assertEquals(27.0, fused.temperature(), 1e-12);
            assertFalse(fused.details().get(0).clamped());
            assertFalse(fused.details().get(1).clamped());
        }

        /** The rest of the house is the rest that contributed: an inactive room holds nothing back. */
        @Test
        void aRestThatDoesNotCountHoldsNothingBack() {
            IndoorFusion fused = fuseIndoor(List.of(inactive("a", 21.0), sensor("sol", 30.0, ROLE_SOLAR, 0.15)), tuning, 45.0);
            assertEquals(30.0, fused.temperature(), 1e-12);
            assertFalse(detail(fused, "sol").clamped());
        }

        /** The details are records: the clamped one is a new FusedSensor in its place, the rest untouched. */
        @Test
        void theClampedOneIsANewRecordInItsPlace() {
            List<SensorReading> readings = List.of(sensor("a", 21.0, ROLE_ROOM, 1.0), sensor("sol", 30.0, ROLE_SOLAR, 0.15),
                    sensor("b", 21.0, ROLE_CEILING, 1.0));
            IndoorFusion fused = fuseIndoor(readings, tuning, 45.0);
            assertEquals(List.of("a", "sol", "b"), keys(fused));
            assertEquals(List.of(false, true, false), fused.details().stream().map(FusedSensor::clamped).toList());
            assertEquals(30.0, fused.details().get(1).corrected(), 0.0, "corrected stays the reading");
            assertEquals(0.15, fused.details().get(1).weight(), 0.0);
        }
    }

    /** "key,temperature,role,weight,offset,stratification,active,featured|..."; an empty temperature is None. */
    static List<SensorReading> readings(String cell) {
        List<SensorReading> out = new ArrayList<>();
        if (cell == null) {
            return out;
        }
        for (String one : cell.split("\\|", -1)) {
            String[] p = one.split(",", -1);
            out.add(new SensorReading(p[0], number(p[1]), p[2], required(p[3]), required(p[4]), required(p[5]), null,
                    Stored.bool(p[6]), Stored.bool(p[7])));
        }
        return out;
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        /** room_target;solar_clamp;flow;readings;fused;details - details as_dict, / between them. */
        @ParameterizedTest
        @CsvFileSource(resources = "/fuse_indoor.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameFusion(String roomTarget, String solarClamp, String flow, String readings, String fused, String details) {
            Tuning tuning = new Tuning();
            tuning.roomTarget = required(roomTarget);
            tuning.solarClamp = required(solarClamp);
            IndoorFusion out = fuseIndoor(readings(readings), tuning, number(flow));
            assertClose(number(fused), out.temperature(), "fused");
            List<Map<String, Object>> expected = details == null ? List.of()
                    : Arrays.stream(details.split("/", -1)).map(Stored::dict).toList();
            assertEquals(expected.size(), out.details().size(), "one detail per reading");
            for (int i = 0; i < expected.size(); i++) {
                assertSameDict(expected.get(i), out.details().get(i).asDict(), "details[" + i + "]");
            }
        }
    }
}
