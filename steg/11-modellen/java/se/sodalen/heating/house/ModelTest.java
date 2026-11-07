package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static se.sodalen.heating.house.Model.COLD_CAUSES;
import static se.sodalen.heating.house.Model.DELTA_QUIET_DEMAND;
import static se.sodalen.heating.house.Model.DELTA_QUIET_LUKEWARM;
import static se.sodalen.heating.house.Model.DELTA_QUIET_OFF;
import static se.sodalen.heating.house.Model.DELTA_QUIET_SENSOR;
import static se.sodalen.heating.house.Model.EMERGENCY_FLOW;
import static se.sodalen.heating.house.Model.EMERGENCY_KINDS;
import static se.sodalen.heating.house.Model.EMERGENCY_OFF;
import static se.sodalen.heating.house.Model.EMERGENCY_VALVE;
import static se.sodalen.heating.house.Model.LEARNERS;
import static se.sodalen.heating.house.Model.LEARN_CURVE;
import static se.sodalen.heating.house.Model.LEARN_DELTA;
import static se.sodalen.heating.house.Model.LEARN_GAIN;
import static se.sodalen.heating.house.Model.LEARN_HOUSE;
import static se.sodalen.heating.house.Model.LEARN_SUN;
import static se.sodalen.heating.house.Model.LEARN_TABLE;
import static se.sodalen.heating.house.Model.LEARN_VALVE_MAP;
import static se.sodalen.heating.house.Model.MODES_STOPPED;
import static se.sodalen.heating.house.Model.MODE_COAST;
import static se.sodalen.heating.house.Model.MODE_EMERGENCY;
import static se.sodalen.heating.house.Model.MODE_FAILSAFE;
import static se.sodalen.heating.house.Model.MODE_HEATING;
import static se.sodalen.heating.house.Model.MODE_HOLD;
import static se.sodalen.heating.house.Model.MODE_OFF;
import static se.sodalen.heating.house.Model.MODE_SUMMER;
import static se.sodalen.heating.house.Model.MODE_WARM;
import static se.sodalen.heating.house.Model.OFFSET_ERA_CURVATURE;
import static se.sodalen.heating.house.Model.OFFSET_ERA_OUTDOOR;
import static se.sodalen.heating.house.Model.ROLES;
import static se.sodalen.heating.house.Model.ROLE_CEILING;
import static se.sodalen.heating.house.Model.ROLE_DEFAULTS;
import static se.sodalen.heating.house.Model.ROLE_ROOM;
import static se.sodalen.heating.house.Model.ROLE_SOLAR;
import static se.sodalen.heating.house.Model.TABLE_LEARNING_OFF;
import static se.sodalen.heating.house.Model.TABLE_NO_CHAIN;
import static se.sodalen.heating.house.Model.TABLE_NO_FIT;
import static se.sodalen.heating.house.Model.TABLE_STEERS;
import static se.sodalen.heating.house.Model.TABLE_SWITCHED_OFF;
import static se.sodalen.heating.house.Model.balanceFromOffset;
import static se.sodalen.heating.house.Model.effectiveCurvature;
import static se.sodalen.heating.house.Model.fieldName;
import static se.sodalen.heating.house.Model.truthy;
import static se.sodalen.heating.house.Model.tuningFromDict;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.assertSameDict;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.curve.Curve;
import se.sodalen.heating.estimate.DutyWatch;
import se.sodalen.heating.estimate.Ewma;
import se.sodalen.heating.estimate.Regression;

/**
 * The data model the controller runs on, free of Home Assistant: the
 * constants, what a sensor reading and its fusion look like, everything an
 * installer may turn (Tuning), everything the controller has learnt about
 * this house (LearnedState) and the two bundles a cycle takes in and hands
 * out. The one place with arithmetic is the restoring of a stored state:
 * every field through as_float with its default, three migrations from
 * older files, and the filter lengths retuned afterwards.
 *
 * <p>Python: model.py; tests/test_curve.py (TestStoredOffset),
 * tests/test_learning_status.py (the restarts and the state saved under its
 * first name), tests/test_inputs.py (IndoorTest for the roles); the rest
 * from the code.
 */
class ModelTest {

    static final double HOUR = 3600.0;
    static final double DAY = 24.0 * HOUR;

    /** Python's field names of Tuning, in Python's order. */
    static final String[] PYTHON_FIELDS = {
            "room_target", "design_outdoor", "curve_slope", "curve_curvature", "flow_min", "flow_max",
            "outdoor_filter_seconds", "outdoor_fall_lead", "outdoor_fall_limit", "indoor_filter_seconds",
            "indoor_slow_filter_seconds", "room_gain", "room_error_limit", "warm_stop_margin", "warm_stop_hysteresis",
            "solar_clamp", "override_lowering", "learn_after_override_seconds", "learn_target_wait_max_seconds",
            "learn_target_warming", "learn_sunlight", "learn_after_sun_seconds", "learn_after_sun_warm",
            "learn_after_sun_max_seconds", "learn_sun_term", "learn_sun_error", "sun_heat_seconds",
            "sun_term_horizon_seconds", "learn_after_failsafe_warm", "learn_after_failsafe_max_seconds",
            "learn_over_target", "learn_over_target_max_seconds", "delta_design", "delta_gain", "delta_lower_limit",
            "delta_raise_limit", "delta_cold_guard", "delta_expected_horizon_seconds", "delta_scale_min",
            "delta_scale_max", "delta_enabled", "delta_min_demand", "delta_min_overtemp", "delta_fade",
            "delta_settle_band", "delta_settle_seconds", "delta_filter_seconds", "delta_hold_max_seconds",
            "delta_warmup_seconds", "starved_margin", "starved_horizon_seconds", "starved_share", "starved_min_hours",
            "failsafe_position", "resume_position_max", "resume_hold_seconds", "valve_feedforward_gain",
            "valve_feedforward_step", "failsafe_after_seconds", "emergency_position", "emergency_flow",
            "reseed_after_seconds", "sensor_max_age_seconds", "coast_enabled", "coast_horizon_hours",
            "coast_confidence", "coast_learning_band", "coast_comfort_band", "coast_bridge_only",
            "coast_learning_hold_seconds", "coast_learning_hold_max_seconds", "coast_learning_cell_hours",
            "coast_recovery_gain", "coast_unknown_reach", "coast_min_hours", "shunt_lead_seconds",
            "coast_hysteresis_seconds", "thermal_sample_seconds", "thermal_horizon_seconds",
            "forecast_horizon_seconds", "summer_outdoor", "summer_hysteresis", "summer_filter_seconds",
            "heat_source_off_in_summer", "heat_source_off_when_idle", "heat_source_off_when_disabled",
            "heat_source_off_delay", "boiler_max", "boiler_max_release", "cold_boiler_band", "cold_boiler_clearance",
            "cold_boiler_floor", "cycle_seconds", "pid_kp", "pid_ti", "pid_td", "pid_deadband", "min_position_step",
            "position_drift_limit", "position_drift_seconds", "gain_reference", "gain_scale_min", "gain_scale_max",
            "valve_map_gain", "kp_rise_seconds", "gain_horizon_seconds", "gain_min_variance", "learn_enabled",
            "learn_horizon_seconds", "learn_level_horizon_seconds", "learn_offset_rate", "learn_slope_rate",
            "learn_saturated_seconds", "learn_warm_trap_seconds", "learn_warm_trap_decay",
            "learn_saturated_slope_rate", "learn_slope_pull", "balance_shift_min", "balance_shift_max",
            "learn_balance_scale", "learn_slope_scale", "slope_min", "slope_max", "learn_min_variance",
            "learn_max_flow_error", "learn_position_margin", "learn_min_demand", "learn_settle_seconds",
            "table_learning", "table_max_green_gap", "curve_bend_limit", "table_half_life_days",
            "table_steady_seconds", "table_indoor_band", "table_indoor_trend", "table_outdoor_trend",
            "table_red_hours", "table_green_hours", "table_green_spread", "table_number_per_day",
            "table_balance_per_day", "table_bend_per_day", "table_balance_trust", "table_balance_from_house",
            "night_start_hour", "night_end_hour", "table_fit_seconds"
    };

    /** Python's defaults, field by field. */
    static final Object[][] PYTHON_DEFAULTS = {
            {"roomTarget", 21.0},
            {"designOutdoor", -18.0},
            {"curveSlope", 0.9},
            {"curveCurvature", 0.8},
            {"flowMin", 20.0},
            {"flowMax", 65.0},
            {"outdoorFilterSeconds", 3.0 * 3600.0},
            {"indoorFilterSeconds", 20.0 * 60.0},
            {"indoorSlowFilterSeconds", 2.0 * 3600.0},
            {"roomGain", 3.0},
            {"roomErrorLimit", 8.0},
            {"warmStopMargin", 1.0},
            {"warmStopHysteresis", 0.3},
            {"solarClamp", 1.5},
            {"overrideLowering", 5.0},
            {"learnAfterOverrideSeconds", 2.0 * 3600.0},
            {"learnTargetWaitMaxSeconds", 6.0 * 3600.0},
            {"learnTargetWarming", 0.1},
            {"learnSunlight", 0.047},
            {"learnAfterSunSeconds", 2.0 * 3600.0},
            {"learnAfterSunWarm", 0.3},
            {"learnAfterSunMaxSeconds", 6.0 * 3600.0},
            {"learnSunTerm", true},
            {"learnSunError", 0.1},
            {"sunHeatSeconds", 3.0 * 3600.0},
            {"sunTermHorizonSeconds", 21.0 * 24.0 * 3600.0},
            {"learnAfterFailsafeWarm", 0.3},
            {"learnAfterFailsafeMaxSeconds", 24.0 * 3600.0},
            {"deltaDesign", 10.0},
            {"deltaGain", 1.2},
            {"deltaLowerLimit", 6.0},
            {"deltaRaiseLimit", 2.0},
            {"deltaColdGuard", 0.3},
            {"deltaExpectedHorizonSeconds", 3.0 * 24.0 * 3600.0},
            {"deltaScaleMin", 0.2},
            {"deltaScaleMax", 3.0},
            {"deltaEnabled", true},
            {"deltaMinDemand", 0.15},
            {"deltaMinOvertemp", 10.0},
            {"deltaFade", 5.0},
            {"deltaSettleBand", 1.0},
            {"deltaSettleSeconds", 300.0},
            {"deltaFilterSeconds", 900.0},
            {"deltaHoldMaxSeconds", 1800.0},
            {"deltaWarmupSeconds", 2700.0},
            {"starvedMargin", 0.3},
            {"starvedHorizonSeconds", 3.0 * 24.0 * 3600.0},
            {"starvedShare", 0.5},
            {"starvedMinHours", 6.0},
            {"failsafePosition", 100.0},
            {"resumePositionMax", 50.0},
            {"resumeHoldSeconds", 180.0},
            {"valveFeedforwardGain", 1.0},
            {"valveFeedforwardStep", 5.0},
            {"failsafeAfterSeconds", 600.0},
            {"emergencyPosition", 50.0},
            {"emergencyFlow", 40.0},
            {"reseedAfterSeconds", 300.0},
            {"sensorMaxAgeSeconds", 1800.0},
            {"coastEnabled", true},
            {"coastHorizonHours", 12.0},
            {"coastConfidence", 2.0},
            {"coastLearningBand", 1.0},
            {"coastComfortBand", 1.0},
            {"coastBridgeOnly", true},
            {"coastLearningHoldSeconds", 2.0 * 3600.0},
            {"coastLearningHoldMaxSeconds", 6.0 * 3600.0},
            {"coastLearningCellHours", 6.0},
            {"coastRecoveryGain", 2.0},
            {"coastUnknownReach", 1.0},
            {"coastMinHours", 48.0},
            {"shuntLeadSeconds", 600.0},
            {"coastHysteresisSeconds", 900.0},
            {"thermalSampleSeconds", 600.0},
            {"thermalHorizonSeconds", 21.0 * 24.0 * 3600.0},
            {"forecastHorizonSeconds", 14.0 * 24.0 * 3600.0},
            {"summerOutdoor", 17.0},
            {"summerHysteresis", 1.5},
            {"summerFilterSeconds", 24.0 * 3600.0},
            {"heatSourceOffInSummer", true},
            {"heatSourceOffWhenIdle", false},
            {"heatSourceOffWhenDisabled", true},
            {"heatSourceOffDelay", 1800.0},
            {"boilerMax", 95.0},
            {"boilerMaxRelease", 85.0},
            {"cycleSeconds", 30.0},
            {"pidKp", 3.5},
            {"pidTi", 150.0},
            {"pidTd", 20.0},
            {"pidDeadband", 0.2},
            {"minPositionStep", 1.5},
            {"positionDriftLimit", 5.0},
            {"positionDriftSeconds", 300.0},
            {"gainReference", 0.4},
            {"gainScaleMin", 0.4},
            {"gainScaleMax", 2.5},
            {"valveMapGain", true},
            {"kpRiseSeconds", 900.0},
            {"gainHorizonSeconds", 12.0 * 3600.0},
            {"gainMinVariance", 9.0},
            {"learnEnabled", true},
            {"learnHorizonSeconds", 3.0 * 24.0 * 3600.0},
            {"learnLevelHorizonSeconds", 12.0 * 3600.0},
            {"learnOffsetRate", 0.08},
            {"learnSlopeRate", 0.02},
            {"learnSaturatedSeconds", 3600.0},
            {"learnWarmTrapSeconds", 6.0 * 3600.0},
            {"learnWarmTrapDecay", 0.25},
            {"learnSaturatedSlopeRate", 0.15},
            {"learnSlopePull", 0.0005},
            {"balanceShiftMin", -14.0},
            {"balanceShiftMax", 3.0},
            {"learnBalanceScale", 3.0},
            {"learnSlopeScale", 0.05},
            {"slopeMin", 0.2},
            {"slopeMax", 2.0},
            {"learnMinVariance", 0.01},
            {"learnMaxFlowError", 3.0},
            {"learnPositionMargin", 3.0},
            {"learnMinDemand", 0.15},
            {"learnSettleSeconds", 3600.0},
            {"tableLearning", true},
            {"tableMaxGreenGap", 2.0},
            {"curveBendLimit", 2.0},
            {"tableHalfLifeDays", 60.0},
            {"tableSteadySeconds", 3600.0},
            {"tableIndoorBand", 0.5},
            {"tableIndoorTrend", 0.15},
            {"tableOutdoorTrend", 2.0},
            {"tableRedHours", 3.0},
            {"tableGreenHours", 24.0},
            {"tableGreenSpread", 1.5},
            {"tableNumberPerDay", 0.1},
            {"tableBalancePerDay", 0.5},
            {"tableBendPerDay", 0.01},
            {"tableBalanceTrust", 2.0},
            {"tableBalanceFromHouse", false},
            {"nightStartHour", 22.0},
            {"nightEndHour", 6.0},
            {"tableFitSeconds", 3600.0},
    };

    static final Set<String> BOOLEAN_FIELDS = Set.of("deltaEnabled", "coastEnabled", "coastBridgeOnly",
            "heatSourceOffInSummer", "heatSourceOffWhenIdle", "heatSourceOffWhenDisabled", "valveMapGain",
            "learnEnabled", "learnSunTerm", "tableLearning", "tableBalanceFromHouse");

    /** A stored dict with any values, null included; Map.of takes neither null nor mixed types kindly. */
    static Map<String, Object> stored(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** Tuning's public instance fields, which is what tuning_from_dict writes. */
    static List<Field> tuningFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : Tuning.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    static Field tuningField(String name) {
        for (Field field : tuningFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return fail("Tuning has no public field " + name);
    }

    static Object get(Tuning tuning, Field field) {
        try {
            return field.get(tuning);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object get(Tuning tuning, String name) {
        return get(tuning, tuningField(name));
    }

    /** "roomTarget" to "room_target": the test's own, so that Model.fieldName is tested, not trusted. */
    static String snake(String camel) {
        StringBuilder out = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Python's == on two tuning values: nan equal to nan. */
    static void assertSameValue(Object expected, Object actual, String what) {
        if (expected instanceof Boolean) {
            assertEquals(expected, actual, what);
        } else {
            assertEquals(((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 0.0, what);
        }
    }

    /** The module's constants: roles, modes, learners, and the sets and lists that must take a null. */
    @Nested
    class TheConstants {
        @Test
        void theRolesAndTheirDefaults() {
            assertEquals("room", ROLE_ROOM);
            assertEquals("ceiling", ROLE_CEILING);
            assertEquals("solar", ROLE_SOLAR);
            assertEquals(List.of("room", "ceiling", "solar"), ROLES);
            assertEquals(List.of("room", "ceiling", "solar"), List.copyOf(ROLE_DEFAULTS.keySet()));
            for (String role : ROLES) {
                assertEquals(List.of("weight", "stratification"), List.copyOf(ROLE_DEFAULTS.get(role).keySet()), role);
            }
            assertEquals(1.0, ROLE_DEFAULTS.get(ROLE_ROOM).get("weight"), 0.0);
            assertEquals(0.0, ROLE_DEFAULTS.get(ROLE_ROOM).get("stratification"), 0.0);
            assertEquals(0.5, ROLE_DEFAULTS.get(ROLE_CEILING).get("weight"), 0.0);
            assertEquals(0.06, ROLE_DEFAULTS.get(ROLE_CEILING).get("stratification"), 0.0);
            assertEquals(0.15, ROLE_DEFAULTS.get(ROLE_SOLAR).get("weight"), 0.0);
            assertEquals(0.0, ROLE_DEFAULTS.get(ROLE_SOLAR).get("stratification"), 0.0);
        }

        /** IndoorTest.test_a_role_not_known_is_a_room: the lookup of an unknown role must not throw. */
        @Test
        void anUnknownRoleIsLookedUpWithoutAnException() {
            assertNull(ROLE_DEFAULTS.get("vind"));
            assertNull(ROLE_DEFAULTS.get(null));
            assertEquals(ROLE_DEFAULTS.get(ROLE_ROOM), ROLE_DEFAULTS.getOrDefault("vind", ROLE_DEFAULTS.get(ROLE_ROOM)));
        }

        @Test
        void theModes() {
            assertEquals("off", MODE_OFF);
            assertEquals("summer", MODE_SUMMER);
            assertEquals("warm", MODE_WARM);
            assertEquals("heating", MODE_HEATING);
            assertEquals("hold", MODE_HOLD);
            assertEquals("failsafe", MODE_FAILSAFE);
            assertEquals("coast", MODE_COAST);
            assertEquals("emergency", MODE_EMERGENCY);
        }

        /** The modes in which the valve is deliberately shut: named once, so adding MODE_COAST meant one place. */
        @Test
        void theStoppedModes() {
            assertEquals(Set.of("off", "summer", "warm", "coast"), MODES_STOPPED);
            for (String mode : List.of(MODE_HEATING, MODE_HOLD, MODE_FAILSAFE, MODE_EMERGENCY, "idle", "")) {
                assertFalse(MODES_STOPPED.contains(mode), mode);
            }
            assertFalse(MODES_STOPPED.contains(null), "a mode never set is not a stop, nor an exception");
        }

        /** Swedish keys, because ControlOutput.learning names them for the card. */
        @Test
        void theLearnersInTheOrderACardListsThem() {
            assertEquals("kurva", LEARN_CURVE);
            assertEquals("tabell", LEARN_TABLE);
            assertEquals("husmodell", LEARN_HOUSE);
            assertEquals("delta", LEARN_DELTA);
            assertEquals("ventilforstarkning", LEARN_GAIN);
            assertEquals("ventilkarta", LEARN_VALVE_MAP);
            assertEquals("sol", LEARN_SUN);
            assertEquals(List.of("kurva", "sol", "tabell", "husmodell", "delta", "ventilforstarkning", "ventilkarta"),
                    LEARNERS);
        }

        @Test
        void theEmergencyKinds() {
            assertEquals("", EMERGENCY_OFF);
            assertEquals("valve", EMERGENCY_VALVE);
            assertEquals("flow", EMERGENCY_FLOW);
            assertEquals(Set.of("valve", "flow"), EMERGENCY_KINDS);
            assertFalse(EMERGENCY_KINDS.contains(EMERGENCY_OFF), "off is anything else");
            assertFalse(EMERGENCY_KINDS.contains(null));
        }

        @Test
        void theTableAndDeltaAnswers() {
            assertEquals("steers", TABLE_STEERS);
            assertEquals("switched_off", TABLE_SWITCHED_OFF);
            assertEquals("learning_off", TABLE_LEARNING_OFF);
            assertEquals("no_fit", TABLE_NO_FIT);
            assertEquals("no_chain", TABLE_NO_CHAIN);
            assertEquals("sensor", DELTA_QUIET_SENSOR);
            assertEquals("off", DELTA_QUIET_OFF);
            assertEquals("demand", DELTA_QUIET_DEMAND);
            assertEquals("lukewarm", DELTA_QUIET_LUKEWARM);
        }

        /** from_dict asks COLD_CAUSES whether a stored cold_after is one of them - with None among the answers. */
        @Test
        void theColdCausesTakeANull() {
            assertEquals(List.of("sweep", "coast", "shortfall", "off"), COLD_CAUSES);
            assertFalse(COLD_CAUSES.contains(null));
            assertFalse(COLD_CAUSES.contains("foo"));
            assertFalse(COLD_CAUSES.contains("SWEEP"));
        }

        @Test
        void theOffsetEra() {
            assertEquals(1.1, OFFSET_ERA_CURVATURE, 0.0, "the curvature the default had before 2026-09-29");
            assertEquals(10.0, OFFSET_ERA_OUTDOOR, 0.0, "mild autumn, when the change was made");
        }

        @Test
        void theListsSetsAndMapsCannotBeChanged() {
            assertThrows(UnsupportedOperationException.class, () -> ROLES.add("vind"));
            assertThrows(UnsupportedOperationException.class, () -> LEARNERS.removeFirst());
            assertThrows(UnsupportedOperationException.class, () -> COLD_CAUSES.set(0, "x"));
            assertThrows(UnsupportedOperationException.class, () -> EMERGENCY_KINDS.add("x"));
            assertThrows(UnsupportedOperationException.class, () -> MODES_STOPPED.remove(MODE_OFF));
            assertThrows(UnsupportedOperationException.class, () -> ROLE_DEFAULTS.put("vind", Map.of()));
            assertThrows(UnsupportedOperationException.class, () -> ROLE_DEFAULTS.get(ROLE_ROOM).put("weight", 2.0));
        }
    }

    /** SensorReading and FusedSensor: Python's dataclasses with their defaults, as records. */
    @Nested
    class TheReadings {
        @Test
        void aReadingWithOnlyAKeyAndATemperatureIsARoomSensor() {
            SensorReading reading = new SensorReading("sensor.vardagsrum", 21.3);
            assertEquals("sensor.vardagsrum", reading.key());
            assertEquals(21.3, reading.temperature());
            assertEquals(ROLE_ROOM, reading.role());
            assertEquals(1.0, reading.weight(), 0.0);
            assertEquals(0.0, reading.offset(), 0.0);
            assertEquals(0.0, reading.stratification(), 0.0);
            assertNull(reading.maxAge(), "None: the circuit's sensor_max_age_seconds");
            assertTrue(reading.active());
            assertFalse(reading.featured());
        }

        @Test
        void aReadingWithARoleAndAWeight() {
            SensorReading reading = new SensorReading("sensor.taket", 23.1, ROLE_CEILING, 0.5);
            assertEquals(ROLE_CEILING, reading.role());
            assertEquals(0.5, reading.weight(), 0.0);
            assertEquals(0.0, reading.offset(), 0.0);
            assertEquals(0.0, reading.stratification(), 0.0);
            assertNull(reading.maxAge());
            assertTrue(reading.active());
            assertFalse(reading.featured());
        }

        /** IndoorTest.test_a_sensor_takes_its_roles_defaults_and_the_yamls_offset, as a reading is built. */
        @Test
        void aReadingWithAnOffsetAndAStratification() {
            SensorReading reading = new SensorReading("sensor.taket", 23.1, ROLE_CEILING, 0.5, -0.5, 0.06);
            assertEquals(-0.5, reading.offset(), 0.0);
            assertEquals(0.06, reading.stratification(), 0.0);
            assertNull(reading.maxAge());
            assertTrue(reading.active());
            assertFalse(reading.featured());
        }

        /** IndoorTest.test_a_sensors_own_limit_is_kept_with_it. */
        @Test
        void aReadingWithEverything() {
            SensorReading reading = new SensorReading("sensor.x", null, ROLE_ROOM, 1.0, 0.0, 0.0, 300.0, false, true);
            assertNull(reading.temperature());
            assertEquals(300.0, reading.maxAge());
            assertFalse(reading.active());
            assertTrue(reading.featured());
        }

        /** available: the temperature is not None. */
        @Test
        void aReadingIsAvailableWhenItHasATemperature() {
            assertTrue(new SensorReading("a", 20.0).available());
            assertTrue(new SensorReading("a", -0.0).available(), "nought is a temperature");
            assertFalse(new SensorReading("a", null).available());
        }

        @Test
        void aFusedSensorWithTheFiveThatAreAlwaysGiven() {
            FusedSensor fused = new FusedSensor("sensor.x", ROLE_SOLAR, 24.0, 22.5, 0.15);
            assertEquals("sensor.x", fused.key());
            assertEquals(ROLE_SOLAR, fused.role());
            assertEquals(24.0, fused.raw());
            assertEquals(22.5, fused.corrected());
            assertEquals(0.15, fused.weight(), 0.0);
            assertFalse(fused.clamped());
            assertEquals(0.0, fused.offset(), 0.0);
            assertTrue(fused.active());
            assertFalse(fused.featured());
        }

        /** contributed: active, with a corrected temperature, and a weight over nought. */
        @Test
        void aFusedSensorContributedWhenActiveWithAValueAndAWeight() {
            assertTrue(new FusedSensor("a", ROLE_ROOM, 21.0, 21.0, 1.0).contributed());
            assertFalse(new FusedSensor("a", ROLE_ROOM, 21.0, null, 1.0).contributed(), "no corrected value");
            assertFalse(new FusedSensor("a", ROLE_ROOM, null, null, 1.0).contributed(), "missing");
            assertFalse(new FusedSensor("a", ROLE_ROOM, 21.0, 21.0, 0.0).contributed(), "weight nought");
            assertFalse(new FusedSensor("a", ROLE_ROOM, 21.0, 21.0, -1.0).contributed(), "weight under nought");
            assertFalse(new FusedSensor("a", ROLE_ROOM, 21.0, 21.0, 1.0, false, 0.0, false, true).contributed(),
                    "not active: a reference thermometer");
            assertTrue(new FusedSensor("a", ROLE_ROOM, 21.0, 21.0, 1.0, true, 0.8, true, false).contributed(),
                    "clamped still contributes");
        }

        /** as_dict: Python's keys in Python's order, None as null, the bools as bools. */
        @Test
        void aFusedSensorAsADict() {
            FusedSensor fused = new FusedSensor("sensor.x", ROLE_CEILING, 23.1, 22.4, 0.5, true, -0.5, true, false);
            Map<String, Object> dict = fused.asDict();
            assertEquals(List.of("key", "role", "raw", "corrected", "weight", "clamped", "offset", "active", "featured"),
                    List.copyOf(dict.keySet()));
            assertEquals("sensor.x", dict.get("key"));
            assertEquals(ROLE_CEILING, dict.get("role"));
            assertEquals(23.1, dict.get("raw"));
            assertEquals(22.4, dict.get("corrected"));
            assertEquals(0.5, dict.get("weight"));
            assertEquals(Boolean.TRUE, dict.get("clamped"));
            assertEquals(-0.5, dict.get("offset"));
            assertEquals(Boolean.TRUE, dict.get("active"));
            assertEquals(Boolean.FALSE, dict.get("featured"));
            Map<String, Object> missing = new FusedSensor("sensor.y", ROLE_ROOM, null, null, 1.0).asDict();
            assertTrue(missing.containsKey("raw") && missing.get("raw") == null, "raw None is a key with null");
            assertNull(missing.get("corrected"));
        }
    }

    /** Tuning: Python's defaults, field for field, and tuning_from_dict. */
    @Nested
    class TheTuning {
        @Test
        void theOnesThatMatterMost() {
            Tuning tuning = new Tuning();
            assertEquals(21.0, tuning.roomTarget, 0.0);
            assertEquals(-18.0, tuning.designOutdoor, 0.0);
            assertEquals(0.9, tuning.curveSlope, 0.0);
            assertEquals(0.8, tuning.curveCurvature, 0.0, "0.8 since 2026-09-29, not 1.1");
            assertEquals(20.0, tuning.flowMin, 0.0);
            assertEquals(65.0, tuning.flowMax, 0.0);
            assertEquals(0.08, tuning.learnOffsetRate, 0.0, "0.08 since 2026-10-25, not 0.15");
            assertEquals(3.5, tuning.pidKp, 0.0);
            assertEquals(150.0, tuning.pidTi, 0.0);
            assertEquals(20.0, tuning.pidTd, 0.0);
            assertEquals(21.0 * 24.0 * 3600.0, tuning.sunTermHorizonSeconds, 0.0);
            assertEquals(-14.0, tuning.balanceShiftMin, 0.0);
            assertEquals(3.0, tuning.balanceShiftMax, 0.0);
            assertTrue(tuning.learnEnabled);
            assertTrue(tuning.coastBridgeOnly);
            assertFalse(tuning.heatSourceOffWhenIdle);
            assertFalse(tuning.tableBalanceFromHouse, "off since 2026-10-14");
        }

        /** Every default, against the table read off model.py. */
        @Test
        void everyDefaultIsPythons() {
            Tuning tuning = new Tuning();
            for (Object[] row : PYTHON_DEFAULTS) {
                String name = (String) row[0];
                Object value = get(tuning, name);
                if (row[1] instanceof Boolean expected) {
                    assertEquals(expected, value, name);
                } else {
                    assertEquals((Double) row[1], (Double) value, 0.0, name);
                }
            }
        }

        /** 147 public fields with Python's names in camelCase, 11 of them boolean and the rest double. */
        @Test
        void theFieldsArePythonsInCamelCase() {
            List<Field> fields = tuningFields();
            assertEquals(147, fields.size(), "how many");
            assertEquals(147, PYTHON_FIELDS.length);
            Set<String> names = new LinkedHashSet<>();
            for (Field field : fields) {
                names.add(field.getName());
                assertFalse(Modifier.isFinal(field.getModifiers()), field.getName() + " is not final");
                if (BOOLEAN_FIELDS.contains(field.getName())) {
                    assertEquals(boolean.class, field.getType(), field.getName());
                } else {
                    assertEquals(double.class, field.getType(), field.getName());
                }
            }
            for (String python : PYTHON_FIELDS) {
                assertTrue(names.contains(fieldName(python)), python + " as " + fieldName(python));
            }
        }

        @Test
        void designSpanIsRoomLessDesignOutdoorButNeverUnderOne() {
            Tuning tuning = new Tuning();
            assertEquals(39.0, tuning.designSpan(), 0.0);
            tuning.designOutdoor = -22.0;
            assertEquals(43.0, tuning.designSpan(), 0.0);
            tuning.designOutdoor = 20.5;
            assertEquals(1.0, tuning.designSpan(), 0.0, "half a degree is a degree");
            tuning.designOutdoor = 25.0;
            assertEquals(1.0, tuning.designSpan(), 0.0, "never negative");
        }

        @Test
        void clampFlowHoldsTheSupplyBetweenMinAndMax() {
            Tuning tuning = new Tuning();
            assertEquals(45.0, tuning.clampFlow(45.0), 0.0);
            assertEquals(20.0, tuning.clampFlow(12.0), 0.0);
            assertEquals(65.0, tuning.clampFlow(80.0), 0.0);
            assertEquals(20.0, tuning.clampFlow(20.0), 0.0);
            assertEquals(65.0, tuning.clampFlow(65.0), 0.0);
        }

        /** min(flow_max, max(flow_min, flow)): with the limits crossed, flow_max wins, and nothing throws. */
        @Test
        void clampFlowWithTheLimitsCrossedGivesFlowMax() {
            Tuning tuning = new Tuning();
            tuning.flowMin = 30.0;
            tuning.flowMax = 25.0;
            assertEquals(25.0, tuning.clampFlow(28.0), 0.0);
            assertEquals(25.0, tuning.clampFlow(10.0), 0.0);
            assertEquals(25.0, tuning.clampFlow(40.0), 0.0);
        }

        @Test
        void fromNothingIsTheDefaults() {
            for (Tuning tuning : List.of(tuningFromDict(null), tuningFromDict(Map.of()), tuningFromDict(null, null),
                    tuningFromDict(Map.of(), new Tuning()))) {
                for (Field field : tuningFields()) {
                    assertSameValue(get(new Tuning(), field), get(tuning, field), field.getName());
                }
            }
        }

        @Test
        void aKnownKeySetsItsField() {
            Tuning tuning = tuningFromDict(stored("room_target", 22.0, "curve_slope", 1.1, "learn_enabled", false));
            assertEquals(22.0, tuning.roomTarget, 0.0);
            assertEquals(1.1, tuning.curveSlope, 0.0);
            assertFalse(tuning.learnEnabled);
            assertEquals(0.8, tuning.curveCurvature, 0.0, "the rest is the default");
        }

        /** if key not in values: continue - a key that is not a field is dropped, however near. */
        @Test
        void anUnknownKeyIsIgnored() {
            Tuning tuning = tuningFromDict(stored("unknown", 1, "gone_in_a_later_version", 3, "design_span", 99.0,
                    "clamp_flow", 1.0, "roomTarget", 25.0, "Room_Target", 25.0, "ROOM_TARGET", 25.0,
                    "room-target", 25.0, "room_target ", 25.0));
            for (Field field : tuningFields()) {
                assertSameValue(get(new Tuning(), field), get(tuning, field), field.getName());
            }
        }

        /** float(value): an int, a bool, a numeric text; what float() refuses leaves the field alone. */
        @Test
        void aNumberFieldIsReadLikePythonsFloat() {
            Tuning tuning = tuningFromDict(stored("room_target", 22, "flow_max", "70", "flow_min", " 25 ",
                    "curve_slope", "1e0", "pid_kp", true, "pid_ti", false));
            assertEquals(22.0, tuning.roomTarget, 0.0, "an int");
            assertEquals(70.0, tuning.flowMax, 0.0, "a text");
            assertEquals(25.0, tuning.flowMin, 0.0, "a text with spaces");
            assertEquals(1.0, tuning.curveSlope, 0.0, "an exponent");
            assertEquals(1.0, tuning.pidKp, 0.0, "True");
            assertEquals(0.0, tuning.pidTi, 0.0, "False");
            Tuning untouched = tuningFromDict(stored("room_target", "abc", "flow_max", null, "flow_min", "",
                    "curve_slope", List.of(1.0), "pid_kp", Map.of(), "pid_ti", "1,5"));
            assertEquals(21.0, untouched.roomTarget, 0.0, "a text that is no number");
            assertEquals(65.0, untouched.flowMax, 0.0, "None");
            assertEquals(20.0, untouched.flowMin, 0.0, "an empty text");
            assertEquals(0.9, untouched.curveSlope, 0.0, "a list");
            assertEquals(3.5, untouched.pidKp, 0.0, "a dict");
            assertEquals(150.0, untouched.pidTi, 0.0, "a comma");
        }

        /** float("nan") and float("inf") are floats: tuning_from_dict keeps them, as_float would not. */
        @Test
        void nanAndInfinityAreKeptInANumberField() {
            Tuning tuning = tuningFromDict(stored("boiler_max", Double.NaN, "curve_bend_limit", Double.POSITIVE_INFINITY,
                    "starved_min_hours", "-inf"));
            assertTrue(Double.isNaN(tuning.boilerMax), "nan");
            assertEquals(Double.POSITIVE_INFINITY, tuning.curveBendLimit, 0.0, "inf");
            assertEquals(Double.NEGATIVE_INFINITY, tuning.starvedMinHours, 0.0, "the text -inf");
        }

        /** bool(value): "false" is true, "" is false, 0 is false, 2.5 is true, None is false, [] is false. */
        @Test
        void aBooleanFieldIsReadLikePythonsBool() {
            Tuning tuning = tuningFromDict(stored("learn_enabled", "false", "delta_enabled", "", "coast_enabled", 0,
                    "coast_bridge_only", 2.5, "heat_source_off_in_summer", null, "valve_map_gain", List.of(),
                    "table_learning", List.of(1.0), "heat_source_off_when_idle", Map.of("a", 1.0),
                    "heat_source_off_when_disabled", Double.NaN, "learn_sun_term", 0.0, "table_balance_from_house", -1));
            assertTrue(tuning.learnEnabled, "\"false\" is a non-empty text, so true");
            assertFalse(tuning.deltaEnabled, "an empty text");
            assertFalse(tuning.coastEnabled, "0");
            assertTrue(tuning.coastBridgeOnly, "2.5");
            assertFalse(tuning.heatSourceOffInSummer, "None");
            assertFalse(tuning.valveMapGain, "an empty list");
            assertTrue(tuning.tableLearning, "a list with something in it");
            assertTrue(tuning.heatSourceOffWhenIdle, "a dict with something in it");
            assertTrue(tuning.heatSourceOffWhenDisabled, "nan is true");
            assertFalse(tuning.learnSunTerm, "0.0");
            assertTrue(tuning.tableBalanceFromHouse, "-1");
        }

        /** {field.name: getattr(base or Tuning(), ...)}: the base's values carry over, the base itself is untouched. */
        @Test
        void aBaseIsCopiedNotChanged() {
            Tuning base = new Tuning();
            base.roomTarget = 22.0;
            base.learnEnabled = false;
            base.flowMax = 60.0;
            Tuning tuning = tuningFromDict(stored("flow_max", 70.0, "pid_kp", 4.0), base);
            assertNotSame(base, tuning);
            assertEquals(22.0, tuning.roomTarget, 0.0, "carried over from the base");
            assertFalse(tuning.learnEnabled, "carried over from the base");
            assertEquals(70.0, tuning.flowMax, 0.0, "the data wins over the base");
            assertEquals(4.0, tuning.pidKp, 0.0);
            assertEquals(60.0, base.flowMax, 0.0, "the base keeps its own");
            assertEquals(3.5, base.pidKp, 0.0, "the base keeps its own");
            Tuning copy = tuningFromDict(null, base);
            assertNotSame(base, copy);
            for (Field field : tuningFields()) {
                assertSameValue(get(base, field), get(copy, field), field.getName());
            }
            copy.roomTarget = 19.0;
            assertEquals(22.0, base.roomTarget, 0.0, "a copy, not the base");
        }
    }

    /** LearnedState: its defaults, forget and retune, as_dict and from_dict with the migrations. */
    @Nested
    class TheLearnedState {
        @Test
        void theDefaultsOfANewState() {
            LearnedState state = new LearnedState();
            assertEquals(0.0, state.balanceShift, 0.0);
            assertEquals(0.0, state.slopeTrim, 0.0);
            assertNull(state.bend, "None: the tuning's curvature");
            assertEquals(0.4, state.processGain, 0.0);
            assertEquals(3.0 * DAY, state.deltaScale.seconds(), 0.0);
            assertNull(state.deltaScale.value());
            assertEquals(900.0, state.deltaFilter.seconds(), 0.0);
            assertEquals(0.0, state.deltaTrimHeld, 0.0);
            assertEquals(0.0, state.secondsSettled, 0.0);
            assertEquals(0.0, state.secondsUnsettled, 0.0);
            assertNull(state.lastFlowSetpoint);
            assertEquals(1e9, state.secondsSinceStop, 0.0, "large until the first stop");
            assertEquals(3.0 * HOUR, state.outdoor.seconds(), 0.0);
            assertEquals(24.0 * HOUR, state.outdoorSummer.seconds(), 0.0);
            assertEquals(20.0 * 60.0, state.indoor.seconds(), 0.0);
            assertEquals(2.0 * HOUR, state.indoorSlow.seconds(), 0.0);
            assertEquals(3.0 * HOUR, state.sunHeat.seconds(), 0.0);
            assertNotNull(state.sunTerm);
            assertEquals(SunTerm.HORIZON_SECONDS, state.sunTerm.fit.horizon(), 0.0);
            assertEquals(3.0 * DAY, state.curveFit.horizon(), 0.0);
            assertEquals(12.0 * HOUR, state.levelFit.horizon(), 0.0);
            assertEquals(12.0 * HOUR, state.gainFit.horizon(), 0.0);
            assertEquals(0.0, state.integral, 0.0);
            assertNull(state.lastFlow);
            assertNull(state.position);
            assertEquals(0.0, state.secondsAdrift, 0.0);
            assertEquals(0.0, state.secondsOffsetSaturated, 0.0);
            assertEquals(0.0, state.secondsWarm, 0.0);
            assertEquals(3.0 * DAY, state.starved.seconds(), 0.0);
            assertNull(state.starved.share());
            assertEquals(0.0, state.learnHours, 0.0);
            assertEquals(0.0, state.secondsSteady, 0.0);
            assertEquals(0.0, state.secondsLearningHold, 0.0);
            assertNull(state.learningHoldCell);
            assertEquals(0.0, state.learningHoldBase, 0.0);
            assertEquals(0.0, state.secondsStopped, 0.0);
            assertEquals(0.0, state.secondsWithoutFlow, 0.0);
            assertEquals(1e6, state.secondsSinceOverride, 0.0, "long ago, not just now");
            assertEquals(1e6, state.secondsSinceCoast, 0.0);
            assertEquals(1e6, state.secondsSinceSun, 0.0);
            assertEquals(1e6, state.secondsSinceSweep, 0.0);
            assertEquals(1e6, state.secondsSinceShortfall, 0.0);
            assertEquals(1e6, state.secondsSinceOff, 0.0);
            assertEquals(1e6, state.secondsSinceFailsafe, 0.0);
            assertNull(state.coldAfter);
            assertEquals(0.0, state.secondsCold, 0.0);
            assertEquals(0.0, state.secondsSinceTargetChange, 0.0);
            assertFalse(state.warmStopped);
            assertFalse(state.summerStopped);
            assertFalse(state.coasting);
            assertFalse(state.recovering);
            assertFalse(state.boilerOverheated);
            assertTrue(state.needsReseed, "a new state reseeds the integral");
            assertNull(state.resumePosition);
            assertEquals(0.0, state.resumeHoldLeft, 0.0);
            assertNull(state.lastFeedforward);
            assertNull(state.lastKp);
        }

        /** Two states do not share their filters. */
        @Test
        void eachStateHasItsOwnFiltersAndFits() {
            LearnedState a = new LearnedState();
            LearnedState b = new LearnedState();
            assertNotSame(a.outdoor, b.outdoor);
            assertNotSame(a.curveFit, b.curveFit);
            assertNotSame(a.sunTerm, b.sunTerm);
            assertNotSame(a.starved, b.starved);
            a.outdoor.update(5.0, 30.0);
            assertNull(b.outdoor.value());
        }

        /** A state that has learnt and measured things, for forget and retune. */
        LearnedState lived() {
            LearnedState state = new LearnedState();
            state.balanceShift = -4.0;
            state.slopeTrim = 0.2;
            state.bend = 0.9;
            state.processGain = 0.7;
            state.deltaScale.update(1.4, 30.0);
            state.deltaFilter.update(6.0, 30.0);
            state.outdoor.update(5.0, 30.0);
            state.outdoorSummer.update(12.0, 30.0);
            state.indoor.update(21.2, 30.0);
            state.indoorSlow.update(21.1, 30.0);
            state.sunHeat.update(0.1, 30.0);
            state.sunTerm.observe(0.4, 0.1, -0.2, 3600.0);
            state.curveFit.add(0.3, -0.4, 30.0);
            state.levelFit.add(0.3, -0.4, 30.0);
            state.gainFit.add(40.0, 45.0, 30.0);
            state.starved.update(true, 30.0);
            state.integral = 12.5;
            state.lastFlow = 44.0;
            state.position = 55.0;
            state.learnHours = 30.0;
            state.secondsSinceOverride = 500.0;
            state.warmStopped = true;
            state.coldAfter = "coast";
            state.secondsCold = 90.0;
            state.needsReseed = false;
            return state;
        }

        /**
         * forget: the conclusions about this house go, the measurements stay. Handing an empty
         * outdoor filter to the curve asked for 56 degrees of supply on a mild September morning.
         */
        @Test
        void forgetDropsTheLearnedAndKeepsTheMeasured() {
            LearnedState state = lived();
            Ewma outdoor = state.outdoor;
            Regression oldCurve = state.curveFit;
            Tuning tuning = new Tuning();
            tuning.gainReference = 0.55;
            state.forget(tuning);
            assertEquals(0.0, state.balanceShift, 0.0);
            assertEquals(0.0, state.slopeTrim, 0.0);
            assertNull(state.bend);
            assertEquals(0.55, state.processGain, 0.0, "the tuning's reference, not 0.4");
            assertNull(state.deltaScale.value(), "a new delta scale");
            assertNull(state.starved.share(), "a new starved watch");
            assertNotSame(oldCurve, state.curveFit, "a new curve fit, not the old one emptied");
            assertEquals(0.0, state.curveFit.weight(), 0.0);
            assertEquals(0.0, state.levelFit.weight(), 0.0);
            assertEquals(0.0, state.gainFit.weight(), 0.0);
            assertEquals(0.0, state.learnHours, 0.0);
            // What stays: where the house is right now.
            assertSame(outdoor, state.outdoor, "the same outdoor filter");
            assertEquals(5.0, state.outdoor.value());
            assertEquals(12.0, state.outdoorSummer.value());
            assertEquals(21.2, state.indoor.value());
            assertEquals(21.1, state.indoorSlow.value());
            assertEquals(0.1, state.sunHeat.value());
            assertEquals(6.0, state.deltaFilter.value(), "the delta filter is a measurement");
            assertEquals(1, state.sunTerm.hours, "the sun term is kept");
            assertEquals(12.5, state.integral, 0.0);
            assertEquals(44.0, state.lastFlow);
            assertEquals(55.0, state.position);
            assertEquals(500.0, state.secondsSinceOverride, 0.0);
            assertTrue(state.warmStopped);
            assertEquals("coast", state.coldAfter);
            assertEquals(90.0, state.secondsCold, 0.0);
            assertFalse(state.needsReseed);
        }

        /** forget ends with retune: the new objects and the old filters all take the tuning's lengths. */
        @Test
        void forgetRetunesEverything() {
            LearnedState state = lived();
            Tuning tuning = new Tuning();
            tuning.deltaExpectedHorizonSeconds = 1000.0;
            tuning.starvedHorizonSeconds = 2000.0;
            tuning.learnHorizonSeconds = 3000.0;
            tuning.learnLevelHorizonSeconds = 4000.0;
            tuning.gainHorizonSeconds = 5000.0;
            tuning.outdoorFilterSeconds = 6000.0;
            tuning.sunTermHorizonSeconds = 7000.0;
            state.forget(tuning);
            assertEquals(1000.0, state.deltaScale.seconds(), 0.0);
            assertEquals(2000.0, state.starved.seconds(), 0.0);
            assertEquals(3000.0, state.curveFit.horizon(), 0.0);
            assertEquals(4000.0, state.levelFit.horizon(), 0.0);
            assertEquals(5000.0, state.gainFit.horizon(), 0.0);
            assertEquals(6000.0, state.outdoor.seconds(), 0.0);
            assertEquals(7000.0, state.sunTerm.fit.horizon(), 0.0);
        }

        /** retune: every filter and fit takes its length from the tuning; nothing else moves. */
        @Test
        void retuneTakesTheLengthsFromTheTuning() {
            LearnedState state = lived();
            Tuning tuning = new Tuning();
            tuning.deltaExpectedHorizonSeconds = 101.0;
            tuning.sunHeatSeconds = 102.0;
            tuning.sunTermHorizonSeconds = 103.0;
            tuning.deltaFilterSeconds = 104.0;
            tuning.starvedHorizonSeconds = 105.0;
            tuning.outdoorFilterSeconds = 106.0;
            tuning.summerFilterSeconds = 107.0;
            tuning.indoorFilterSeconds = 108.0;
            tuning.indoorSlowFilterSeconds = 109.0;
            tuning.learnHorizonSeconds = 110.0;
            tuning.learnLevelHorizonSeconds = 111.0;
            tuning.gainHorizonSeconds = 112.0;
            state.retune(tuning);
            assertEquals(101.0, state.deltaScale.seconds(), 0.0, "delta_scale");
            assertEquals(102.0, state.sunHeat.seconds(), 0.0, "sun_heat");
            assertEquals(103.0, state.sunTerm.fit.horizon(), 0.0, "sun_term.fit");
            assertEquals(104.0, state.deltaFilter.seconds(), 0.0, "delta_filter");
            assertEquals(105.0, state.starved.seconds(), 0.0, "starved");
            assertEquals(106.0, state.outdoor.seconds(), 0.0, "outdoor");
            assertEquals(107.0, state.outdoorSummer.seconds(), 0.0, "outdoor_summer");
            assertEquals(108.0, state.indoor.seconds(), 0.0, "indoor");
            assertEquals(109.0, state.indoorSlow.seconds(), 0.0, "indoor_slow");
            assertEquals(110.0, state.curveFit.horizon(), 0.0, "curve_fit");
            assertEquals(111.0, state.levelFit.horizon(), 0.0, "level_fit");
            assertEquals(112.0, state.gainFit.horizon(), 0.0, "gain_fit");
            assertEquals(5.0, state.outdoor.value(), "the values stay");
            assertEquals(1.0, state.curveFit.weight(), 0.0, "the sums stay");
            assertEquals(-4.0, state.balanceShift, 0.0, "the learned stays");
            assertEquals(0.7, state.processGain, 0.0);
        }

        @Test
        void asDictHasPythonsFiftyFiveKeysInOrder() {
            List<String> keys = List.of("balance_shift", "slope_trim", "bend", "process_gain", "delta_scale",
                    "delta_filter", "delta_trim_held", "seconds_settled", "seconds_unsettled", "last_flow_setpoint",
                    "seconds_since_stop", "starved", "outdoor", "outdoor_summer", "indoor", "indoor_slow", "sun_heat",
                    "sun_term", "curve_fit", "level_fit", "gain_fit", "integral", "last_flow", "position",
                    "seconds_adrift", "seconds_offset_saturated", "seconds_warm", "learn_hours", "seconds_steady",
                    "seconds_learning_hold", "learning_hold_cell", "learning_hold_base", "seconds_stopped",
                    "seconds_without_flow", "seconds_since_override", "seconds_since_coast", "seconds_since_sun",
                    "seconds_since_sweep", "seconds_since_shortfall", "seconds_since_off", "seconds_since_failsafe",
                    "seconds_over_target",
                    "cold_after", "seconds_cold", "seconds_since_target_change", "warm_stopped", "summer_stopped",
                    "coasting", "recovering", "boiler_overheated", "needs_reseed", "resume_position",
                    "resume_hold_left", "last_feedforward", "last_kp");
            assertEquals(55, keys.size());
            assertEquals(keys, List.copyOf(new LearnedState().asDict().keySet()));
        }

        @Test
        void asDictHoldsTheValuesAndTheNestedDicts() {
            LearnedState state = lived();
            Map<String, Object> dict = state.asDict();
            assertEquals(-4.0, dict.get("balance_shift"));
            assertEquals(0.2, dict.get("slope_trim"));
            assertEquals(0.9, dict.get("bend"));
            assertEquals(0.7, dict.get("process_gain"));
            assertSameDict(state.deltaScale.asDict(), dict.get("delta_scale"), 0.0, "delta_scale");
            assertSameDict(state.starved.asDict(), dict.get("starved"), 0.0, "starved");
            assertSameDict(state.outdoor.asDict(), dict.get("outdoor"), 0.0, "outdoor");
            assertSameDict(state.sunTerm.asDict(), dict.get("sun_term"), 0.0, "sun_term");
            assertSameDict(state.curveFit.asDict(), dict.get("curve_fit"), 0.0, "curve_fit");
            assertSameDict(state.gainFit.asDict(), dict.get("gain_fit"), 0.0, "gain_fit");
            assertEquals(12.5, dict.get("integral"));
            assertEquals(44.0, dict.get("last_flow"));
            assertEquals(55.0, dict.get("position"));
            assertTrue(dict.containsKey("last_flow_setpoint") && dict.get("last_flow_setpoint") == null,
                    "None is a key with null");
            assertTrue(dict.containsKey("learning_hold_cell") && dict.get("learning_hold_cell") == null);
            assertEquals("coast", dict.get("cold_after"));
            assertEquals(90.0, dict.get("seconds_cold"));
            assertEquals(Boolean.TRUE, dict.get("warm_stopped"));
            assertEquals(Boolean.FALSE, dict.get("coasting"));
            assertEquals(Boolean.FALSE, dict.get("needs_reseed"));
            assertEquals(1e6, dict.get("seconds_since_coast"));
        }

        @Test
        void itSurvivesARestart() {
            LearnedState state = lived();
            Tuning tuning = new Tuning();
            LearnedState back = LearnedState.fromDict(state.asDict(), tuning);
            assertSameDict(state.asDict(), back.asDict(), 0.0, "as_dict after the round trip");
            assertEquals(-4.0, back.balanceShift, 0.0);
            assertEquals(0.9, back.bend);
            assertEquals(5.0, back.outdoor.value());
            assertEquals("coast", back.coldAfter);
            assertFalse(back.needsReseed);
            assertEquals(1, back.sunTerm.hours);
        }

        @Test
        void fromNothingIsANewStateRetuned() {
            Tuning tuning = new Tuning();
            tuning.gainReference = 0.6;
            tuning.outdoorFilterSeconds = 1234.0;
            for (Map<String, Object> nothing : Arrays.asList(null, stored())) {
                LearnedState state = LearnedState.fromDict(nothing, tuning);
                assertEquals(0.0, state.balanceShift, 0.0);
                assertNull(state.bend);
                assertEquals(0.6, state.processGain, 0.0, "the tuning's gain reference");
                assertEquals(1e9, state.secondsSinceStop, 0.0);
                assertEquals(1e6, state.secondsSinceOverride, 0.0);
                assertTrue(state.needsReseed);
                assertNull(state.coldAfter);
                assertNull(state.outdoor.value());
                assertEquals(1234.0, state.outdoor.seconds(), 0.0, "retuned");
            }
        }

        /** test_learning_status.test_a_state_from_before_it_was_never_off. */
        @Test
        void aStateFromBeforeItWasNeverOff() {
            assertEquals(1e6, LearnedState.fromDict(stored(), new Tuning()).secondsSinceOff, 0.0);
        }

        /** test_learning_status.test_a_state_saved_under_its_first_name_is_read: for a morning (ba43a64). */
        @Test
        void aStateSavedUnderItsFirstNameIsRead() {
            LearnedState back = LearnedState.fromDict(stored("shortfall_cold", true, "seconds_shortfall_cold", 120.0),
                    new Tuning());
            assertEquals("shortfall", back.coldAfter);
            assertEquals(120.0, back.secondsCold, 0.0);
        }

        /** test_learning_status.test_it_survives_a_restart (both): cold_after and its seconds come back. */
        @Test
        void theColdCauseSurvivesARestart() {
            for (String cause : COLD_CAUSES) {
                LearnedState state = new LearnedState();
                state.coldAfter = cause;
                state.secondsCold = 1800.0;
                state.secondsSinceOff = 4000.0;
                state.secondsSinceShortfall = 5000.0;
                LearnedState back = LearnedState.fromDict(state.asDict(), new Tuning());
                assertEquals(cause, back.coldAfter);
                assertEquals(1800.0, back.secondsCold, 0.0);
                assertEquals(4000.0, back.secondsSinceOff, 0.0);
                assertEquals(5000.0, back.secondsSinceShortfall, 0.0);
            }
        }

        /**
         * test_curve.TestStoredOffset.test_the_house_gets_the_supply_it_had_where_it_was_learned:
         * a state saved before 2026-09-29 holds a parallel supply offset, learned around +11
         * outside; the balance shift it becomes is free heat.
         */
        @Test
        void aStoredOffsetBecomesABalanceShift() {
            Tuning tuning = new Tuning();
            Regression fit = new Regression(tuning.learnHorizonSeconds);
            fit.add(10.0 / 39.0, 0.0, 30.0);
            LearnedState state = LearnedState.fromDict(stored("curve_offset", -5.89, "slope_trim", 0.0,
                    "curve_fit", fit.asDict()), tuning);
            assertTrue(state.balanceShift < -3.0, "the offset was free heat: " + state.balanceShift);
            assertTrue(state.balanceShift >= tuning.balanceShiftMin, "within the limits");
            assertEquals(1.0, state.curveFit.weight(), 0.0, "the fit itself is restored");
        }

        /** test_curve.TestStoredOffset.test_a_state_already_on_the_balance_is_left_alone. */
        @Test
        void aStateAlreadyOnTheBalanceIsLeftAlone() {
            LearnedState state = LearnedState.fromDict(stored("balance_shift", -4.0, "curve_offset", 9.0), new Tuning());
            assertEquals(-4.0, state.balanceShift, 0.0);
        }

        /** The migration matches the curve at OFFSET_ERA_OUTDOOR without a fit, at the fit's mean demand with one. */
        @Test
        void theOffsetIsMatchedWhereItWasLearned() {
            Tuning tuning = new Tuning();
            LearnedState plain = LearnedState.fromDict(stored("curve_offset", -5.89), tuning);
            Regression fit = new Regression(tuning.learnHorizonSeconds);
            fit.add(20.0 / 39.0, 0.0, 30.0);
            LearnedState colder = LearnedState.fromDict(stored("curve_offset", -5.89, "curve_fit", fit.asDict()),
                    tuning);
            assertTrue(plain.balanceShift < 0.0, "free heat: " + plain.balanceShift);
            assertTrue(colder.balanceShift != plain.balanceShift, "the weather it was learned in matters");
            LearnedState state = new LearnedState();
            assertEquals(plain.balanceShift, balanceFromOffset(-5.89, state, tuning), 0.0,
                    "what from_dict does is _balance_from_offset on the restored state");
        }

        /** The clamp: a large positive offset is balance_shift_max, a large negative one in cold weather balance_shift_min. */
        @Test
        void theMigratedBalanceIsClamped() {
            Tuning tuning = new Tuning();
            assertEquals(tuning.balanceShiftMax, LearnedState.fromDict(stored("curve_offset", 25.0), tuning)
                    .balanceShift, 0.0);
            Regression cold = new Regression(tuning.learnHorizonSeconds);
            cold.add(20.0 / 39.0, 0.0, 30.0);
            assertEquals(tuning.balanceShiftMin, LearnedState.fromDict(stored("curve_offset", -20.0,
                    "curve_fit", cold.asDict()), tuning).balanceShift, 0.0);
        }
    }

    /** ControlInputs and ControlOutput: Python's defaults, and every instance with its own lists. */
    @Nested
    class TheInputsAndOutput {
        @Test
        void inputsHaveOnlyTheCycleTimeAndDefaults() {
            ControlInputs in = new ControlInputs(30.0);
            assertEquals(30.0, in.dt, 0.0);
            assertNull(in.flow);
            assertNull(in.ret);
            assertNull(in.outdoor);
            assertNotNull(in.indoor);
            assertTrue(in.indoor.isEmpty());
            assertFalse(in.overrideActive);
            assertNull(in.position);
            assertFalse(in.targetChanged);
            assertTrue(in.heatingEnabled);
            assertFalse(in.hotWater);
            assertNull(in.wind);
            assertNull(in.windBearing);
            assertNull(in.sunElevation);
            assertNull(in.cloud);
            assertNull(in.sunlight);
            assertNull(in.hour);
            assertNotNull(in.forecast);
            assertTrue(in.forecast.isEmpty());
            assertNull(in.boiler);
            assertFalse(in.boilerFailing);
            assertFalse(in.sweeping);
            assertEquals(EMERGENCY_OFF, in.emergency);
            assertNull(in.sinceFailsafe);
        }

        @Test
        void inputsHaveTheirOwnLists() {
            ControlInputs a = new ControlInputs(30.0);
            ControlInputs b = new ControlInputs(30.0);
            a.indoor.add(new SensorReading("x", 21.0));
            a.forecast.add(new Forecast(1.0, new Conditions(5.0)));
            assertTrue(b.indoor.isEmpty());
            assertTrue(b.forecast.isEmpty());
            assertNotSame(a.indoor, b.indoor);
            assertNotSame(a.forecast, b.forecast);
        }

        @Test
        void outputHasPythonsDefaults() {
            ControlOutput out = new ControlOutput();
            assertNull(out.position);
            assertNull(out.commandPosition);
            assertNull(out.flowSetpoint);
            assertNull(out.failsafeFlow);
            assertNull(out.flowError);
            assertNull(out.curveFlow);
            assertEquals(0.0, out.roomTrim, 0.0);
            assertEquals(0.0, out.overrideTrim, 0.0);
            assertEquals(0.0, out.deltaTrim, 0.0);
            assertNotNull(out.indoorReadings);
            assertTrue(out.indoorReadings.isEmpty());
            assertNull(out.indoorFused);
            assertNull(out.indoorSlow);
            assertNull(out.outdoorFiltered);
            assertNull(out.outdoorSummer);
            assertNull(out.delta);
            assertNull(out.deltaExpected);
            assertEquals("", out.deltaQuiet);
            assertNull(out.valveMapPosition);
            assertEquals(0.0, out.deltaWeight, 0.0);
            assertFalse(out.radiatorsStarved);
            assertNull(out.deltaReadableFrom);
            assertNull(out.deltaRead);
            assertNull(out.deltaWarmingLeft);
            assertEquals(0.0, out.demand, 0.0);
            assertEquals(0.0, out.effectiveSlope, 0.0);
            assertEquals(0.0, out.effectiveKp, 0.0);
            assertFalse(out.learned);
            assertEquals("idle", out.mode);
            assertEquals("", out.reason);
            assertNull(out.heatSource, "None: nothing to say about a heat source that is not configured");
            assertFalse(out.burnerForHotWater);
            assertFalse(out.boilerOverheated);
            assertNotNull(out.degraded);
            assertTrue(out.degraded.isEmpty());
            assertNotNull(out.flags);
            assertTrue(out.flags.isEmpty());
            assertNotNull(out.learning);
            assertTrue(out.learning.isEmpty());
            assertNull(out.coastHours);
            assertFalse(out.coastKnown);
            assertNull(out.coastUncertainty);
            assertNull(out.coastReach);
            assertEquals("", out.coastTrust);
            assertNull(out.coastShuntThreshold);
            assertNull(out.coastBurnerThreshold);
            assertEquals(0.0, out.coastAllowance, 0.0);
            assertEquals(0.0, out.coastMargin, 0.0);
            assertNull(out.coastTurnHours);
            assertNull(out.coastLearningHold);
            assertFalse(out.coastShunt);
            assertFalse(out.coastBurner);
        }

        /** field(default_factory=list): two outputs never share a list, or the learning map. */
        @Test
        void outputsHaveTheirOwnListsAndMap() {
            ControlOutput a = new ControlOutput();
            ControlOutput b = new ControlOutput();
            a.degraded.add("flow");
            a.flags.add("delta_held");
            a.indoorReadings.add(new FusedSensor("x", ROLE_ROOM, 21.0, 21.0, 1.0));
            a.learning.put(LEARN_CURVE, new LearnerStatus(true, "", null));
            assertTrue(b.degraded.isEmpty());
            assertTrue(b.flags.isEmpty());
            assertTrue(b.indoorReadings.isEmpty());
            assertTrue(b.learning.isEmpty());
            assertNotSame(a.degraded, b.degraded);
            assertNotSame(a.flags, b.flags);
            assertNotSame(a.indoorReadings, b.indoorReadings);
            assertNotSame(a.learning, b.learning);
        }

        /** The learning map keeps the order the learners are put in, and takes a null number. */
        @Test
        void theLearningMapKeepsItsOrder() {
            ControlOutput out = new ControlOutput();
            for (String learner : LEARNERS) {
                out.learning.put(learner, new LearnerStatus(false, "off", null));
            }
            assertEquals(LEARNERS, List.copyOf(out.learning.keySet()));
            LearnerStatus status = new LearnerStatus(false, "after_override", 1200.0);
            assertFalse(status.learning());
            assertEquals("after_override", status.why());
            assertEquals(1200.0, status.number());
            assertEquals(new LearnerStatus(true, "", null), new LearnerStatus(true, "", null));
        }
    }

    /** Behaviour the Python tests do not pin, read off model.py. */
    @Nested
    class FromTheCode {
        /** bool(value): None, 0, 0.0, "", [] and {} are false; everything else, "false" and nan included, is true. */
        @Test
        void truthyIsPythonsBool() {
            assertFalse(truthy(null));
            assertFalse(truthy(false));
            assertTrue(truthy(true));
            assertFalse(truthy(0));
            assertFalse(truthy(0.0));
            assertFalse(truthy(-0.0));
            assertFalse(truthy(0L));
            assertTrue(truthy(1));
            assertTrue(truthy(2.5));
            assertTrue(truthy(-1));
            assertTrue(truthy(Double.NaN), "nan is true in Python");
            assertFalse(truthy(""));
            assertTrue(truthy("false"), "\"false\" is a non-empty text");
            assertTrue(truthy(" "));
            assertTrue(truthy("0"));
            assertFalse(truthy(List.of()));
            assertTrue(truthy(List.of(1.0)));
            assertFalse(truthy(Map.of()));
            assertTrue(truthy(Map.of("a", 1.0)));
            assertTrue(truthy(new Object()));
        }

        /** snake_case to camelCase: a capital after every underscore. */
        @Test
        void fieldNameIsPythonsNameInCamelCase() {
            assertEquals("roomTarget", fieldName("room_target"));
            assertEquals("pidKp", fieldName("pid_kp"));
            assertEquals("learnSunTerm", fieldName("learn_sun_term"));
            assertEquals("coastLearningHoldMaxSeconds", fieldName("coast_learning_hold_max_seconds"));
            assertEquals("x", fieldName("x"));
            assertEquals("roomTarget", fieldName("roomTarget"), "already camelCase stays");
        }

        /** effective_curvature: the table's bend when there is one, else the setting - and 0.0 is a bend. */
        @Test
        void effectiveCurvatureIsTheBendOrTheSetting() {
            Tuning tuning = new Tuning();
            LearnedState state = new LearnedState();
            assertEquals(0.8, effectiveCurvature(tuning, state), 0.0);
            tuning.curveCurvature = 1.1;
            assertEquals(1.1, effectiveCurvature(tuning, state), 0.0);
            state.bend = 0.95;
            assertEquals(0.95, effectiveCurvature(tuning, state), 0.0);
            state.bend = 0.0;
            assertEquals(0.0, effectiveCurvature(tuning, state), 0.0, "0.0 is not None");
        }

        /** The slope in the migration is clamped to [slope_min, slope_max]: a trim past the limit is the limit. */
        @Test
        void theMigrationClampsTheSlope() {
            Tuning tuning = new Tuning();
            LearnedState atMax = new LearnedState();
            atMax.slopeTrim = tuning.slopeMax - tuning.curveSlope;
            LearnedState past = new LearnedState();
            past.slopeTrim = 5.0;
            assertEquals(balanceFromOffset(-3.0, atMax, tuning), balanceFromOffset(-3.0, past, tuning), 1e-9);
            LearnedState atMin = new LearnedState();
            atMin.slopeTrim = tuning.slopeMin - tuning.curveSlope;
            LearnedState under = new LearnedState();
            under.slopeTrim = -5.0;
            assertEquals(balanceFromOffset(-3.0, atMin, tuning), balanceFromOffset(-3.0, under, tuning), 1e-9);
        }

        /** More offset, more balance - until the limits. */
        @Test
        void theMigrationGrowsWithTheOffsetUntilTheLimits() {
            Tuning tuning = new Tuning();
            LearnedState state = new LearnedState();
            double last = Double.NEGATIVE_INFINITY;
            for (double offset = -8.0; offset <= 8.0; offset += 2.0) {
                double balance = balanceFromOffset(offset, state, tuning);
                assertTrue(balance >= last, offset + ": " + balance + " >= " + last);
                assertTrue(balance >= tuning.balanceShiftMin && balance <= tuning.balanceShiftMax, offset + ": " + balance);
                last = balance;
            }
            assertEquals(tuning.balanceShiftMax, balanceFromOffset(25.0, state, tuning), 0.0);
        }

        /**
         * balance_through returns the outdoor itself when the old curve sits under the room: an
         * offset that large puts the balance at the weather it was learned in, 10 outside with no
         * fit, so -11 from the room - and only a fit from colder weather reaches balance_shift_min.
         */
        @Test
        void anOffsetThatLeavesTheOldCurveUnderTheRoomPutsTheBalanceAtItsWeather() {
            Tuning tuning = new Tuning();
            assertEquals(OFFSET_ERA_OUTDOOR - tuning.roomTarget, balanceFromOffset(-20.0, new LearnedState(), tuning),
                    0.0);
            LearnedState cold = new LearnedState();
            cold.curveFit.add(20.0 / 39.0, 0.0, 30.0);
            assertEquals(tuning.balanceShiftMin, balanceFromOffset(-20.0, cold, tuning), 0.0,
                    "learned at +1 outside: -20 from the room, clamped");
        }

        /** No offset and no fit: the old curve is the new one at OFFSET_ERA_OUTDOOR with the old bend. */
        @Test
        void noOffsetIsAlmostNoBalanceShift() {
            Tuning tuning = new Tuning();
            tuning.curveCurvature = OFFSET_ERA_CURVATURE;
            assertEquals(0.0, balanceFromOffset(0.0, new LearnedState(), tuning), 1e-9,
                    "with the old bend the curves are the same curve");
        }

        /**
         * demand ** _OFFSET_ERA_CURVATURE, not demand ** curve_curvature: the old curve was bent 1.1
         * whatever the tuning's bend is now. With the default 0.8 even an offset of nought moves the
         * balance, because a 1.1 curve sits under a 0.8 one at 10 outside and the new curve has to
         * come down to pass through it.
         */
        @Test
        void theOldCurveIsBentOnePointOneWhateverTheTuningsBendIs() {
            Tuning tuning = new Tuning();
            assertEquals(0.8, tuning.curveCurvature, 0.0, "the default bend is not the era's");
            double shift = balanceFromOffset(0.0, new LearnedState(), tuning);
            assertTrue(shift < -1.0, "the balance comes down to meet the old curve: " + shift);
            double demand = (tuning.roomTarget - OFFSET_ERA_OUTDOOR) / tuning.designSpan();
            double old = tuning.roomTarget + tuning.curveSlope * tuning.designSpan() * Math.pow(demand, OFFSET_ERA_CURVATURE);
            assertEquals(Curve.balanceThrough(OFFSET_ERA_OUTDOOR, old, tuning.roomTarget, tuning.designOutdoor,
                    tuning.curveSlope, tuning.curveCurvature) - tuning.roomTarget, shift, 1e-9,
                    "the old supply at 10 outside, matched by the curve with the tuning's bend");
        }

        /** as_float(data.get(key), default): a value of another type takes the default, a numeric one is read. */
        @Test
        void fromDictReadsEveryNumberLikeAsFloat() {
            LearnedState state = LearnedState.fromDict(stored("balance_shift", -4, "slope_trim", "0.25",
                    "process_gain", "abc", "integral", true, "seconds_since_stop", null, "learn_hours", Double.NaN,
                    "seconds_since_sun", List.of(1.0)), new Tuning());
            assertEquals(-4.0, state.balanceShift, 0.0, "an Integer");
            assertEquals(0.25, state.slopeTrim, 0.0, "a text");
            assertEquals(0.4, state.processGain, 0.0, "a text that is no number: the default");
            assertEquals(1.0, state.integral, 0.0, "True");
            assertEquals(1e9, state.secondsSinceStop, 0.0, "None: the default, 1e9 here");
            assertEquals(0.0, state.learnHours, 0.0, "nan: the default");
            assertEquals(1e6, state.secondsSinceSun, 0.0, "a list: the default");
        }

        /** as_optional_float for the ones that may be None: None stays, junk is 0.0. */
        @Test
        void fromDictReadsTheOptionalOnesWithNoneKept() {
            LearnedState state = LearnedState.fromDict(stored("bend", null, "last_flow", "abc", "position", 55,
                    "resume_position", "12.5", "last_kp", Double.POSITIVE_INFINITY), new Tuning());
            assertNull(state.bend);
            assertEquals(0.0, state.lastFlow, "junk is nought, not None");
            assertEquals(55.0, state.position);
            assertEquals(12.5, state.resumePosition);
            assertEquals(0.0, state.lastKp, "inf is nought");
            assertNull(state.lastFlowSetpoint, "missing is None");
            assertNull(state.lastFeedforward);
            assertEquals(0.9, LearnedState.fromDict(stored("bend", 0.9), new Tuning()).bend);
        }

        /** bool(data.get("warm_stopped", False)): the key present with None is False, "false" is True. */
        @Test
        void theFlagsAreReadLikePythonsBool() {
            LearnedState state = LearnedState.fromDict(stored("warm_stopped", "false", "summer_stopped", 1,
                    "coasting", "", "recovering", 2.5, "boiler_overheated", null), new Tuning());
            assertTrue(state.warmStopped, "\"false\" is true");
            assertTrue(state.summerStopped);
            assertFalse(state.coasting);
            assertTrue(state.recovering);
            assertFalse(state.boilerOverheated, "None is false");
        }

        /** needs_reseed defaults to True - but a key present with None is bool(None), False. */
        @Test
        void needsReseedIsTrueWhenMissingAndFalseWhenNone() {
            assertTrue(LearnedState.fromDict(stored(), new Tuning()).needsReseed);
            assertTrue(LearnedState.fromDict(stored("needs_reseed", true), new Tuning()).needsReseed);
            assertFalse(LearnedState.fromDict(stored("needs_reseed", false), new Tuning()).needsReseed);
            assertFalse(LearnedState.fromDict(stored("needs_reseed", null), new Tuning()).needsReseed);
            assertFalse(LearnedState.fromDict(stored("needs_reseed", 0), new Tuning()).needsReseed);
            assertTrue(LearnedState.fromDict(stored("needs_reseed", "no"), new Tuning()).needsReseed);
        }

        /** "balance_shift" not in data: the key stops the migration, even when its value is None or junk. */
        @Test
        void aBalanceShiftKeyStopsTheMigrationWhateverItsValue() {
            Tuning tuning = new Tuning();
            assertEquals(0.0, LearnedState.fromDict(stored("balance_shift", null, "curve_offset", 9.0), tuning)
                    .balanceShift, 0.0, "None: as_float gives 0.0 and the offset is ignored");
            assertEquals(0.0, LearnedState.fromDict(stored("balance_shift", "abc", "curve_offset", 9.0), tuning)
                    .balanceShift, 0.0, "junk: the same");
            assertTrue(LearnedState.fromDict(stored("curve_offset", 9.0), tuning).balanceShift > 0.0,
                    "without the key the offset is migrated");
        }

        /** A curve_offset that is junk is as_float(…, 0.0): migrated as an offset of nought. */
        @Test
        void aJunkOffsetIsMigratedAsNought() {
            Tuning tuning = new Tuning();
            assertEquals(LearnedState.fromDict(stored("curve_offset", 0.0), tuning).balanceShift,
                    LearnedState.fromDict(stored("curve_offset", "abc"), tuning).balanceShift, 0.0);
            assertEquals(LearnedState.fromDict(stored("curve_offset", 0.0), tuning).balanceShift,
                    LearnedState.fromDict(stored("curve_offset", null), tuning).balanceShift, 0.0);
        }

        /** The migration runs on the restored state: its slope_trim and curve_fit, and the tuning given. */
        @Test
        void theMigrationUsesTheRestoredSlopeTrimAndFit() {
            Tuning tuning = new Tuning();
            LearnedState withTrim = LearnedState.fromDict(stored("curve_offset", -5.89, "slope_trim", 0.4), tuning);
            LearnedState withoutTrim = LearnedState.fromDict(stored("curve_offset", -5.89), tuning);
            assertTrue(withTrim.balanceShift != withoutTrim.balanceShift);
            LearnedState expected = new LearnedState();
            expected.slopeTrim = 0.4;
            assertEquals(balanceFromOffset(-5.89, expected, tuning), withTrim.balanceShift, 0.0);
        }

        /** data.get("seconds_cold", data.get("seconds_shortfall_cold")): the reserve only when the key is missing. */
        @Test
        void secondsColdTakesTheOldNameOnlyWhenTheKeyIsMissing() {
            Tuning tuning = new Tuning();
            assertEquals(120.0, LearnedState.fromDict(stored("seconds_shortfall_cold", 120.0), tuning).secondsCold, 0.0);
            assertEquals(77.0, LearnedState.fromDict(stored("seconds_shortfall_cold", "77"), tuning).secondsCold, 0.0);
            assertEquals(0.0, LearnedState.fromDict(stored("seconds_cold", null, "seconds_shortfall_cold", 120.0), tuning)
                    .secondsCold, 0.0, "the key with None is None, not the reserve");
            assertEquals(55.0, LearnedState.fromDict(stored("seconds_cold", 55.0, "seconds_shortfall_cold", 120.0), tuning)
                    .secondsCold, 0.0);
            assertEquals(0.0, LearnedState.fromDict(stored("seconds_shortfall_cold", "abc"), tuning).secondsCold, 0.0);
        }

        /** cold_after in COLD_CAUSES wins; else "shortfall" if shortfall_cold is truthy; else None. */
        @Test
        void coldAfterIsACauseOrTheOldFlag() {
            Tuning tuning = new Tuning();
            assertEquals("coast", LearnedState.fromDict(stored("cold_after", "coast", "shortfall_cold", true), tuning)
                    .coldAfter, "a cause wins over the old flag");
            assertEquals("shortfall", LearnedState.fromDict(stored("cold_after", "foo", "shortfall_cold", true), tuning)
                    .coldAfter, "an unknown cause and the old flag");
            assertEquals("shortfall", LearnedState.fromDict(stored("shortfall_cold", List.of(1.0)), tuning).coldAfter,
                    "the old flag as anything truthy");
            assertEquals("shortfall", LearnedState.fromDict(stored("shortfall_cold", "false"), tuning).coldAfter,
                    "\"false\" is truthy");
            assertNull(LearnedState.fromDict(stored("cold_after", "foo"), tuning).coldAfter);
            assertNull(LearnedState.fromDict(stored("cold_after", "SWEEP"), tuning).coldAfter, "exact");
            assertNull(LearnedState.fromDict(stored("cold_after", null), tuning).coldAfter);
            assertNull(LearnedState.fromDict(stored("cold_after", 3), tuning).coldAfter);
            assertNull(LearnedState.fromDict(stored("cold_after", "", "shortfall_cold", 0), tuning).coldAfter);
            assertNull(LearnedState.fromDict(stored("shortfall_cold", false), tuning).coldAfter);
            assertNull(LearnedState.fromDict(stored("shortfall_cold", ""), tuning).coldAfter);
        }

        /** learning_hold_cell: taken only as a str - "" included; a number or None is None. */
        @Test
        void theLearningHoldCellIsTakenOnlyAsText() {
            Tuning tuning = new Tuning();
            assertEquals("7:1", LearnedState.fromDict(stored("learning_hold_cell", "7:1"), tuning).learningHoldCell);
            assertEquals("", LearnedState.fromDict(stored("learning_hold_cell", ""), tuning).learningHoldCell,
                    "an empty text is a text");
            assertNull(LearnedState.fromDict(stored("learning_hold_cell", 3), tuning).learningHoldCell);
            assertNull(LearnedState.fromDict(stored("learning_hold_cell", null), tuning).learningHoldCell);
            assertNull(LearnedState.fromDict(stored("learning_hold_cell", true), tuning).learningHoldCell);
            assertNull(LearnedState.fromDict(stored("learning_hold_cell", Map.of()), tuning).learningHoldCell);
            assertNull(LearnedState.fromDict(stored(), tuning).learningHoldCell);
        }

        /** The nested dicts go through their own from_dict: junk is a new filter with the tuning's length. */
        @Test
        void theNestedDictsAreRestoredWithTheTuningsLengths() {
            Tuning tuning = new Tuning();
            tuning.outdoorFilterSeconds = 1111.0;
            tuning.deltaExpectedHorizonSeconds = 2222.0;
            tuning.starvedHorizonSeconds = 3333.0;
            tuning.learnHorizonSeconds = 4444.0;
            tuning.sunTermHorizonSeconds = 5555.0;
            LearnedState state = LearnedState.fromDict(stored("outdoor", stored("seconds", 5.0, "value", 7.5),
                    "delta_scale", "abc", "starved", List.of(), "curve_fit", null,
                    "sun_term", stored("fit", "abc", "hours", 50)), tuning);
            assertEquals(7.5, state.outdoor.value());
            assertEquals(1111.0, state.outdoor.seconds(), 0.0, "not the stored 5.0");
            assertNull(state.deltaScale.value());
            assertEquals(2222.0, state.deltaScale.seconds(), 0.0);
            assertEquals(3333.0, state.starved.seconds(), 0.0);
            assertEquals(0.0, state.curveFit.weight(), 0.0);
            assertEquals(4444.0, state.curveFit.horizon(), 0.0);
            assertEquals(0, state.sunTerm.hours, "hours without a fit");
            assertEquals(5555.0, state.sunTerm.fit.horizon(), 0.0);
        }

        /** from_dict ends with retune: a stored filter length never survives the tuning. */
        @Test
        void fromDictRetunesAtTheEnd() {
            LearnedState state = lived();
            Tuning tuning = new Tuning();
            tuning.indoorFilterSeconds = 42.0;
            tuning.gainHorizonSeconds = 43.0;
            LearnedState back = LearnedState.fromDict(state.asDict(), tuning);
            assertEquals(42.0, back.indoor.seconds(), 0.0);
            assertEquals(43.0, back.gainFit.horizon(), 0.0);
            assertEquals(21.2, back.indoor.value(), "the value is the stored one");
        }

        LearnedState lived() {
            LearnedState state = new LearnedState();
            state.indoor.update(21.2, 30.0);
            state.gainFit.add(40.0, 45.0, 30.0);
            return state;
        }

        /** An unknown key in the stored dict changes nothing. */
        @Test
        void anUnknownStoredKeyChangesNothing() {
            LearnedState state = LearnedState.fromDict(stored("foo", 1, "curve_offset_old", 9.0, "balanceShift", -4.0),
                    new Tuning());
            assertSameDict(new LearnedState().asDict(), state.asDict(), 0.0, "as a new state");
        }

        /** Tuning.clampFlow with the limits crossed must not throw: Math.clamp would. */
        @Test
        void clampFlowNeverThrows() {
            Tuning tuning = new Tuning();
            tuning.flowMin = 50.0;
            tuning.flowMax = 40.0;
            assertEquals(40.0, tuning.clampFlow(45.0), 0.0);
        }

        /** The overload without a base is the one with base None. */
        @Test
        void theShortOverloadIsTheLongOneWithNoBase() {
            Map<String, Object> data = stored("room_target", 22.5, "learn_enabled", 0);
            Tuning one = tuningFromDict(data);
            Tuning two = tuningFromDict(data, null);
            for (Field field : tuningFields()) {
                assertSameValue(get(two, field), get(one, field), field.getName());
            }
        }
    }

    /**
     * Python's own answers, row by row from four files verktyg/facit.py wrote:
     * tuning_from_dict over dicts of every shape, from_dict over stored states
     * modern, old and broken, _balance_from_offset over the tunings and fits,
     * and effective_curvature. An empty cell arrives as null.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        /**
         * base;data;result: the base as the fields set on a standard Tuning (empty for none),
         * the data (empty for None, {} for an empty dict), and the fields of the result that
         * differ from Tuning() as key=f:… or key=b:… ({} when none do).
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/tuning_from_dict.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 16384)
        void sameTuning(String base, String data, String result) {
            Tuning baseTuning = base == null ? null : tuningFromDict(dict(base));
            Map<String, Object> baseBefore = baseTuning == null ? null : values(baseTuning);
            Tuning tuning = tuningFromDict(dict(data), baseTuning);
            Map<String, Object> differs = dict(result);
            Tuning standard = new Tuning();
            for (Field field : tuningFields()) {
                String key = snake(field.getName());
                Object expected = differs.containsKey(key) ? differs.get(key) : get(standard, field);
                assertSameValue(expected, get(tuning, field), key);
            }
            for (String key : differs.keySet()) {
                assertNotNull(tuningField(fieldName(key)), key + " is a field");
            }
            if (baseTuning != null) {
                assertEquals(baseBefore, values(baseTuning), "the base is not changed");
            }
        }

        Map<String, Object> values(Tuning tuning) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Field field : tuningFields()) {
                out.put(field.getName(), get(tuning, field));
            }
            return out;
        }

        /**
         * tuning;stored;balance_shift;slope_trim;bend;process_gain;cold_after;seconds_cold;
         * seconds_since_off;needs_reseed;warm_stopped;delta_scale_seconds;curve_fit_horizon;
         * sun_term_horizon;outdoor_value;as_dict_keys;roundtrip_equal: the tuning as deviations
         * from the standard (empty for Tuning()), the stored dict (empty for None, with d: for the
         * nested ones), the fields from_dict gave, three lengths after the retune, the outdoor
         * filter's value, as_dict's keys in order, and whether Python's round trip was equal.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/learned_state.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameState(String tuningCell, String stored, String balanceShift, String slopeTrim, String bend,
                       String processGain, String coldAfter, String secondsCold, String secondsSinceOff,
                       String needsReseed, String warmStopped, String deltaScaleSeconds, String curveFitHorizon,
                       String sunTermHorizon, String outdoorValue, String asDictKeys, String roundtripEqual) {
            Tuning tuning = tuningCell == null ? new Tuning() : tuningFromDict(dict(tuningCell));
            LearnedState state = LearnedState.fromDict(dict(stored), tuning);
            // The migrated balance comes through a pow and a root: a millionth.
            assertClose(required(balanceShift), state.balanceShift, 1e-6, "balance_shift");
            assertEquals(required(slopeTrim), state.slopeTrim, 0.0, "slope_trim");
            assertClose(number(bend), state.bend, 0.0, "bend");
            assertEquals(required(processGain), state.processGain, 0.0, "process_gain");
            assertEquals(coldAfter, state.coldAfter, "cold_after");
            assertEquals(required(secondsCold), state.secondsCold, 0.0, "seconds_cold");
            assertEquals(required(secondsSinceOff), state.secondsSinceOff, 0.0, "seconds_since_off");
            assertEquals(bool(needsReseed), state.needsReseed, "needs_reseed");
            assertEquals(bool(warmStopped), state.warmStopped, "warm_stopped");
            assertEquals(required(deltaScaleSeconds), state.deltaScale.seconds(), 0.0, "delta_scale.seconds");
            assertEquals(required(curveFitHorizon), state.curveFit.horizon(), 0.0, "curve_fit.horizon");
            assertEquals(required(sunTermHorizon), state.sunTerm.fit.horizon(), 0.0, "sun_term.fit.horizon");
            assertClose(number(outdoorValue), state.outdoor.value(), 0.0, "outdoor.value");
            Map<String, Object> dict = state.asDict();
            assertEquals(Arrays.asList(asDictKeys.split(",", -1)), List.copyOf(dict.keySet()), "as_dict keys");
            assertTrue(bool(roundtripEqual), "Python's own round trip was equal");
            assertSameDict(dict, LearnedState.fromDict(dict, tuning).asDict(), 0.0, "the round trip");
        }

        /**
         * offset;slope_trim;fit_add;fit_weight;fit_mean_x;room_target;design_outdoor;curve_slope;
         * curve_curvature;slope_min;slope_max;balance_shift_min;balance_shift_max;balance: the
         * offset, the state's slope_trim, one add (x,y,dt) on a fresh state's curve_fit (empty for
         * none) with the weight and mean it gives, the tuning's curve fields, and the balance
         * shift _balance_from_offset answered.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/balance_from_offset.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false)
        void sameBalance(String offset, String slopeTrim, String fitAdd, String fitWeight, String fitMeanX,
                         String roomTarget, String designOutdoor, String curveSlope, String curveCurvature,
                         String slopeMin, String slopeMax, String balanceShiftMin, String balanceShiftMax,
                         String balance) {
            Tuning tuning = new Tuning();
            tuning.roomTarget = required(roomTarget);
            tuning.designOutdoor = required(designOutdoor);
            tuning.curveSlope = required(curveSlope);
            tuning.curveCurvature = required(curveCurvature);
            tuning.slopeMin = required(slopeMin);
            tuning.slopeMax = required(slopeMax);
            tuning.balanceShiftMin = required(balanceShiftMin);
            tuning.balanceShiftMax = required(balanceShiftMax);
            LearnedState state = new LearnedState();
            state.slopeTrim = required(slopeTrim);
            if (fitAdd != null) {
                String[] p = fitAdd.split(",", -1);
                state.curveFit.add(required(p[0]), required(p[1]), required(p[2]));
            }
            assertClose(required(fitWeight), state.curveFit.weight(), "curve_fit.weight");
            assertClose(required(fitMeanX), state.curveFit.meanX(), "curve_fit.mean_x");
            assertClose(required(balance), balanceFromOffset(required(offset), state, tuning), 1e-6, "balance");
        }

        /** bend;curve_curvature;effective: the state's bend (empty for None), the tuning's curvature, the answer. */
        @ParameterizedTest
        @CsvFileSource(resources = "/effective_curvature.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false)
        void sameCurvature(String bend, String curveCurvature, String effective) {
            Tuning tuning = new Tuning();
            tuning.curveCurvature = required(curveCurvature);
            LearnedState state = new LearnedState();
            state.bend = number(bend);
            assertEquals(required(effective), effectiveCurvature(tuning, state), 0.0, "effective_curvature");
        }
    }
}
