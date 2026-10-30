package se.sodalen.heating.burner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.burner.Burner.BURNERS;
import static se.sodalen.heating.burner.Burner.BURNER_DEFAULTS;
import static se.sodalen.heating.burner.Burner.BURNER_NAMES;
import static se.sodalen.heating.burner.Burner.BURNER_OIL;
import static se.sodalen.heating.burner.Burner.BURNER_PELLETS;
import static se.sodalen.heating.burner.Burner.CALL_NAMES;
import static se.sodalen.heating.burner.Burner.CONTROL_PATHS;
import static se.sodalen.heating.burner.Burner.CONTROL_PATH_NAMES;
import static se.sodalen.heating.burner.Burner.PATH_BOTH;
import static se.sodalen.heating.burner.Burner.PATH_CONTROL;
import static se.sodalen.heating.burner.Burner.PATH_POWER;
import static se.sodalen.heating.burner.Burner.burnerSettings;
import static se.sodalen.heating.burner.Burner.planHeatSource;
import static se.sodalen.heating.burner.Burner.relayCallsForHeat;
import static se.sodalen.heating.burner.Burner.relaysInUse;
import static se.sodalen.heating.burner.Facit.bool;
import static se.sodalen.heating.burner.Facit.nullableBool;
import static se.sodalen.heating.burner.Facit.number;
import static se.sodalen.heating.burner.Facit.stored;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * The burner bolted to the boiler, and how it is asked to fire. Which burner
 * is on the wall decides its timings (burner_settings), which path the order
 * goes through decides the relays (relays_in_use), which way the control
 * cable calls for heat decides how a relay sits (relay_calls_for_heat), and
 * a stop goes through the cable first while the supply waits out the
 * burnout (plan_heat_source). None of it is numerics: getting a relay
 * backwards fails expensively in both directions, a house that never heats
 * or a burner that never stops.
 *
 * <p>Python: burner.py; tests/test_controller.py (TestBurnerRelays,
 * TestInvertedControlCable, TestBurnerSettings) and tests/test_sweep.py
 * (TestTheSupplyInASweep, TestTheBurnersTimes).
 */
class BurnerTest {

    /**
     * How the on/off decision reaches two relays. The decision itself is the
     * controller's business. This is the layer below: the burner is swapped
     * physically, the same control cable and main supply serve whichever is
     * bolted on, and the two do different things. Pulling the supply on a
     * burner that is still burning is not the same as asking it to stop.
     */
    @Nested
    class TheRelays {
        /**
         * TestBurnerRelays.plan(**kwargs): plan_heat_source with the test's
         * defaults - wanted, nothing stopped yet, both relays fitted, pellets'
         * burnout of 900 s - and any of them overridden.
         */
        static final class Plan {
            private boolean wanted = true;
            private double secondsStopped = 0.0;
            private boolean hasControl = true;
            private boolean hasPower = true;
            private double burnoutSeconds = 900.0;

            Plan wanted(boolean value) {
                wanted = value;
                return this;
            }

            Plan secondsStopped(double value) {
                secondsStopped = value;
                return this;
            }

            Plan hasControl(boolean value) {
                hasControl = value;
                return this;
            }

            Plan hasPower(boolean value) {
                hasPower = value;
                return this;
            }

            Plan burnoutSeconds(double value) {
                burnoutSeconds = value;
                return this;
            }

            HeatSourcePlan made() {
                return planHeatSource(wanted, secondsStopped, hasControl, hasPower, burnoutSeconds);
            }
        }

        private Plan plan() {
            return new Plan();
        }

        @Test
        void startingPowersItAndAsksForHeat() {
            HeatSourcePlan out = plan().wanted(true).made();
            assertEquals(Boolean.TRUE, out.power());
            assertEquals(Boolean.TRUE, out.control());
            assertFalse(out.burningOut());
        }

        @Test
        void stoppingAsksFirstAndLeavesTheSupplyAlone() {
            HeatSourcePlan out = plan().wanted(false).secondsStopped(0.0).made();
            assertEquals(Boolean.FALSE, out.control());
            // null, not false: the supply is deliberately still on.
            assertNull(out.power());
            assertTrue(out.burningOut());
        }

        @Test
        void theSupplyIsCutOnceItHasBurnedOut() {
            HeatSourcePlan out = plan().wanted(false).secondsStopped(900.0).made();
            assertEquals(Boolean.FALSE, out.control());
            assertEquals(Boolean.FALSE, out.power());
            assertFalse(out.burningOut());
        }

        @Test
        void oilBurnsOutFasterThanPellets() {
            double pellets = BURNER_DEFAULTS.get("pellets").burnoutSeconds();
            double oil = BURNER_DEFAULTS.get("oil").burnoutSeconds();
            assertTrue(pellets > oil, pellets + " > " + oil);
            // Sextio sekunder in i en utbrinning: olja är klar, pellets inte.
            assertNull(plan().wanted(false).secondsStopped(60.0).burnoutSeconds(pellets).made().power());
            assertEquals(Boolean.FALSE, plan().wanted(false).secondsStopped(60.0).burnoutSeconds(oil).made().power());
        }

        @Test
        void pelletsIsTheMoreReluctantToBeStopped() {
            double pellets = BURNER_DEFAULTS.get("pellets").offDelay();
            double oil = BURNER_DEFAULTS.get("oil").offDelay();
            assertTrue(pellets > oil, pellets + " > " + oil);
        }

        /** Where this house is until the control cable is connected. */
        @Test
        void withOnlyASupplyItIsCutStraightAway() {
            HeatSourcePlan out = plan().wanted(false).hasControl(false).secondsStopped(0.0).made();
            assertEquals(Boolean.FALSE, out.power());
            assertNull(out.control());
            assertFalse(out.burningOut());
        }

        @Test
        void withOnlyAControlCableTheSupplyIsNeverTouched() {
            HeatSourcePlan out = plan().wanted(false).hasPower(false).made();
            assertEquals(Boolean.FALSE, out.control());
            assertNull(out.power());
        }

        @Test
        void startingNeverWaitsForAnything() {
            for (double stopped : new double[] {0.0, 10.0, 100000.0}) {
                HeatSourcePlan out = plan().wanted(true).secondsStopped(stopped).made();
                assertEquals(Boolean.TRUE, out.power(), "stopped " + stopped);
                assertEquals(Boolean.TRUE, out.control(), "stopped " + stopped);
            }
        }
    }

    /**
     * A burner that fires when the circuit is broken is an ordinary thing,
     * and getting it backwards fails expensively in both directions.
     */
    @Nested
    class TheInvertedCable {
        boolean calls(String callForHeat, boolean call) {
            return relayCallsForHeat(callForHeat, call);
        }

        @Test
        void theOrdinaryWayRound() {
            assertTrue(calls("on", true));
            assertFalse(calls("on", false));
        }

        @Test
        void aBurnerThatFiresOnABrokenCircuit() {
            assertFalse(calls("off", true));
            assertTrue(calls("off", false));
        }

        @Test
        void theTwoAreNeverTheSameThing() {
            for (boolean call : new boolean[] {true, false}) {
                assertNotEquals(calls("on", call), calls("off", call), "call " + call);
            }
        }
    }

    /**
     * Settings that follow the burner rather than the house. The burner gets
     * swapped physically, and when it does, how long a stop has to hold, how
     * long it burns out and which way its control cable calls for heat all
     * change together. Remembering them per burner is what makes a swap one
     * choice instead of four numbers nobody wrote down.
     */
    @Nested
    class TheSettings {
        @Test
        void eachBurnerBringsItsOwn() {
            BurnerSettings pellets = burnerSettings("pellets");
            BurnerSettings oil = burnerSettings("oil");
            assertTrue(pellets.offDelay() > oil.offDelay(), "off_delay");
            assertTrue(pellets.burnoutSeconds() > oil.burnoutSeconds(), "burnout_seconds");
        }

        @Test
        void whatWasRememberedWins() {
            BurnerSettings out = burnerSettings("pellets", Map.of("off_delay", 120.0));
            assertEquals(120.0, out.offDelay(), 0.0);
            // Anything not remembered still comes from the burner.
            assertEquals(BURNER_DEFAULTS.get("pellets").burnoutSeconds(), out.burnoutSeconds(), 0.0);
        }

        @Test
        void swappingBackGetsTheOldSettingsAgain() {
            Map<String, Map<String, Object>> remembered = Map.of(
                    "pellets", Map.of("off_delay", 120.0, "call_for_heat", "off"),
                    "oil", Map.of("off_delay", 900.0));
            assertEquals(120.0, burnerSettings("pellets", remembered.get("pellets")).offDelay(), 0.0);
            assertEquals(900.0, burnerSettings("oil", remembered.get("oil")).offDelay(), 0.0);
            assertEquals("off", burnerSettings("pellets", remembered.get("pellets")).callForHeat());
        }

        /**
         * The one that fails expensively: an oil burner firing on a broken
         * circuit must not inherit the pellet burner's way round.
         */
        @Test
        void theInversionIsRememberedPerBurner() {
            assertEquals("off", burnerSettings("oil", Map.of("call_for_heat", "off")).callForHeat());
            assertEquals("on", burnerSettings("pellets").callForHeat());
        }

        @Test
        void aBrokenStoredValueFallsBack() {
            Map<String, Object> stored = Map.of("off_delay", "inte ett tal", "unknown", 1);
            BurnerSettings out = burnerSettings("pellets", stored);
            assertEquals(BURNER_DEFAULTS.get("pellets").offDelay(), out.offDelay(), 0.0);
        }

        /** Python's assertIn("off_delay", out): there is an answer, and it has pellets' off_delay. */
        @Test
        void anUnknownBurnerDoesNotExplode() {
            BurnerSettings out = burnerSettings("ved");
            assertNotNull(out);
            assertEquals(BURNER_DEFAULTS.get("pellets").offDelay(), out.offDelay(), 0.0);
        }

        @Test
        void everyBurnerHasANameAndAProfile() {
            for (String key : BURNERS) {
                assertTrue(BURNER_NAMES.containsKey(key), key + " in BURNER_NAMES");
                assertTrue(BURNER_DEFAULTS.containsKey(key), key + " in BURNER_DEFAULTS");
            }
        }

        @Test
        void everyControlPathHasAName() {
            for (String path : CONTROL_PATHS) {
                assertTrue(CONTROL_PATH_NAMES.containsKey(path), path + " in CONTROL_PATH_NAMES");
            }
        }
    }

    /**
     * Off for a sweep means off: the main supply too, whichever path the
     * burner is normally asked through (relays_in_use). Python: test_sweep.py.
     */
    @Nested
    class TheSupplyInASweep {
        @Test
        void aSweepTakesTheSupplyWhateverThePath() {
            assertEquals(new Relays(true, false), relaysInUse("control", true, true), "the cable alone, normally");
            assertEquals(new Relays(true, true), relaysInUse("control", true, true, true, false));
            assertEquals(new Relays(false, true), relaysInUse("power", true, true, true, false));
            assertEquals(new Relays(true, true), relaysInUse("both", true, true));
            assertEquals(new Relays(true, false), relaysInUse("control", true, false, true, false),
                    "no supply relay, nothing to take");
        }

        /** Fredrik 2026-10-06: the guard cuts both the cable and the supply. */
        @Test
        void anOverheatedBoilerTakesBothRelaysWhateverThePath() {
            for (String path : List.of("control", "power", "both")) {
                assertEquals(new Relays(true, true), relaysInUse(path, true, true, false, true), path);
            }
            assertEquals(new Relays(false, true), relaysInUse("power", false, true, false, true));
        }

        @Test
        void andStopsBothAtOnceWithoutABurnout() {
            HeatSourcePlan plan = planHeatSource(false, 0, true, true, 900, true);
            assertEquals(new HeatSourcePlan(false, false, false), plan);
        }

        @Test
        void withTheCableTooTheSupplyWaitsOutTheBurnout() {
            HeatSourcePlan early = planHeatSource(false, 300, true, true, 900);
            assertEquals(new HeatSourcePlan(false, null, true), early);
            HeatSourcePlan late = planHeatSource(false, 900, true, true, 900);
            assertEquals(Boolean.FALSE, late.control());
            assertEquals(Boolean.FALSE, late.power());
        }
    }

    /**
     * Each burner's own lead before the sweeper comes (sweep_lead_seconds),
     * and a change to it remembered. Python: test_sweep.py.
     */
    @Nested
    class TheBurnersTimes {
        @Test
        void eachBurnerHasItsOwnLeadAndRemembersAChange() {
            assertEquals(21600.0, burnerSettings("pellets").sweepLeadSeconds(), 0.0);
            assertEquals(7200.0, burnerSettings("oil").sweepLeadSeconds(), 0.0);
            Map<String, Object> stored = Map.of("sweep_lead_seconds", "3600", "burnout_seconds", 1800);
            BurnerSettings kept = burnerSettings("pellets", stored);
            assertEquals(3600.0, kept.sweepLeadSeconds(), 0.0);
            assertEquals(1800.0, kept.burnoutSeconds(), 0.0);
        }
    }

    /** The module's constants: the two profiles, their order, and the names the family reads. */
    @Nested
    class TheConstants {
        /** Pellets lights slowly and holds its heat for hours: the long timings. */
        @Test
        void thePelletsProfile() {
            BurnerSettings pellets = BURNER_DEFAULTS.get(BURNER_PELLETS);
            assertEquals(1800.0, pellets.startLead(), 0.0, "start_lead");
            assertEquals(3600.0, pellets.offDelay(), 0.0, "off_delay");
            assertEquals(900.0, pellets.burnoutSeconds(), 0.0, "burnout_seconds");
            assertEquals(21600.0, pellets.sweepLeadSeconds(), 0.0, "sweep_lead_seconds: six hours");
            assertEquals("on", pellets.callForHeat(), "call_for_heat");
            assertEquals(50.0, pellets.alarmBelow(), 0.0, "alarm_below");
            assertNull(pellets.controlPath(), "control_path is not in the profile");
        }

        /** Oil lights in seconds and stops in seconds: neither number is generous. */
        @Test
        void theOilProfile() {
            BurnerSettings oil = BURNER_DEFAULTS.get(BURNER_OIL);
            assertEquals(600.0, oil.startLead(), 0.0, "start_lead");
            assertEquals(1800.0, oil.offDelay(), 0.0, "off_delay");
            assertEquals(60.0, oil.burnoutSeconds(), 0.0, "burnout_seconds");
            assertEquals(7200.0, oil.sweepLeadSeconds(), 0.0, "sweep_lead_seconds: two hours");
            assertEquals("on", oil.callForHeat(), "call_for_heat");
            assertEquals(65.0, oil.alarmBelow(), 0.0, "alarm_below");
            assertNull(oil.controlPath(), "control_path is not in the profile");
        }

        @Test
        void theBurnersArePelletsThenOil() {
            assertEquals("pellets", BURNER_PELLETS);
            assertEquals("oil", BURNER_OIL);
            assertEquals(List.of("pellets", "oil"), BURNERS);
            assertEquals(List.of("pellets", "oil"), List.copyOf(BURNER_DEFAULTS.keySet()));
            assertEquals(List.of("pellets", "oil"), List.copyOf(BURNER_NAMES.keySet()));
        }

        @Test
        void theControlPathsAreControlPowerBoth() {
            assertEquals("control", PATH_CONTROL);
            assertEquals("power", PATH_POWER);
            assertEquals("both", PATH_BOTH);
            assertEquals(List.of("control", "power", "both"), CONTROL_PATHS);
            assertEquals(List.of("control", "power", "both"), List.copyOf(CONTROL_PATH_NAMES.keySet()));
        }

        /** Swedish, because these go on the dashboard the family reads. */
        @Test
        void theNamesTheFamilyReads() {
            assertEquals(Map.of("pellets", "pellets", "oil", "olja"), BURNER_NAMES);
            assertEquals(Map.of("control", "Styrkabel", "power", "Huvudström", "both", "Styrkabel + huvudström"),
                    CONTROL_PATH_NAMES);
            assertEquals(Map.of("on", "Sluten krets", "off", "Bruten krets"), CALL_NAMES);
            assertEquals(List.of("on", "off"), List.copyOf(CALL_NAMES.keySet()));
        }

        @Test
        void theListsAndMapsCannotBeChanged() {
            assertThrows(UnsupportedOperationException.class, () -> BURNERS.add("ved"));
            assertThrows(UnsupportedOperationException.class, () -> BURNERS.set(0, "ved"));
            assertThrows(UnsupportedOperationException.class, () -> CONTROL_PATHS.add("none"));
            assertThrows(UnsupportedOperationException.class, () -> CONTROL_PATHS.removeFirst());
            assertThrows(UnsupportedOperationException.class, () -> BURNER_NAMES.put("ved", "ved"));
            assertThrows(UnsupportedOperationException.class, () -> BURNER_NAMES.remove("oil"));
            assertThrows(UnsupportedOperationException.class, () -> CALL_NAMES.put("yes", "Ja"));
            assertThrows(UnsupportedOperationException.class, () -> CONTROL_PATH_NAMES.put("none", "Ingen"));
            assertThrows(UnsupportedOperationException.class,
                    () -> BURNER_DEFAULTS.put("ved", BURNER_DEFAULTS.get(BURNER_PELLETS)));
            assertThrows(UnsupportedOperationException.class, () -> BURNER_DEFAULTS.clear());
        }
    }

    /** Behaviour the Python tests do not pin, read off burner.py. */
    @Nested
    class FromTheCode {
        /**
         * From the code: BURNER_DEFAULTS.get(burner, BURNER_DEFAULTS[BURNER_PELLETS])
         * - a burner that was never named is as unknown as "ved".
         */
        @Test
        void aNullBurnerGetsPelletsDefaults() {
            assertEquals(BURNER_DEFAULTS.get(BURNER_PELLETS), burnerSettings(null));
            assertEquals(BURNER_DEFAULTS.get(BURNER_PELLETS), burnerSettings(null, Map.of()));
            assertEquals(120.0, burnerSettings(null, Map.of("off_delay", 120.0)).offDelay(), 0.0);
        }

        /** From the code: the dict lookup is exact - "Pellets", "OIL" and "olja" are not burners. */
        @Test
        void theBurnersNameIsLookedUpExactly() {
            for (String name : List.of("Pellets", "OIL", "olja", "gas", "")) {
                assertEquals(BURNER_DEFAULTS.get(BURNER_PELLETS), burnerSettings(name), "'" + name + "'");
            }
        }

        /** From the code: (stored or {}) - nothing stored means the burner's own defaults. */
        @Test
        void aNullStoredMapGetsTheDefaults() {
            assertEquals(BURNER_DEFAULTS.get(BURNER_OIL), burnerSettings("oil", null));
            assertEquals(BURNER_DEFAULTS.get(BURNER_OIL), burnerSettings("oil"));
            assertEquals(BURNER_DEFAULTS.get(BURNER_PELLETS), burnerSettings("pellets", Map.of()));
        }

        /**
         * From the code: control_path is str(value), and dict(...) copies the
         * defaults, which have no control_path of their own - before or after.
         */
        @Test
        void aStoredControlPathComesBackAsTextAndTheDefaultsStillHaveNone() {
            assertEquals("both", burnerSettings("pellets", Map.of("control_path", "both")).controlPath());
            assertEquals("none", burnerSettings("oil", Map.of("control_path", "none")).controlPath());
            assertNull(BURNER_DEFAULTS.get(BURNER_PELLETS).controlPath());
            assertNull(BURNER_DEFAULTS.get(BURNER_OIL).controlPath());
            assertNull(burnerSettings("pellets").controlPath());
        }

        /** From the code: float(value) - a stored 1200 is 1200.0. */
        @Test
        void aStoredIntBecomesADouble() {
            BurnerSettings out = burnerSettings("pellets", Map.of("start_lead", 1200, "alarm_below", 45));
            assertEquals(1200.0, out.startLead(), 0.0);
            assertEquals(45.0, out.alarmBelow(), 0.0);
        }

        /** From the code: float(True) is 1.0 and float(False) is 0.0, odd as a setting but not an error. */
        @Test
        void aStoredBooleanIsOneOrNought() {
            BurnerSettings out = burnerSettings("pellets", Map.of("start_lead", true, "off_delay", false));
            assertEquals(1.0, out.startLead(), 0.0);
            assertEquals(0.0, out.offDelay(), 0.0);
        }

        /** From the code: float("1.5e3") is 1500.0; a sign and a decimal point parse too. */
        @Test
        void aNumericStringWithAnExponentParses() {
            Map<String, Object> stored = Map.of("sweep_lead_seconds", "1.5e3", "start_lead", "+5",
                                                "off_delay", "-2", "alarm_below", "0.5");
            BurnerSettings out = burnerSettings("oil", stored);
            assertEquals(1500.0, out.sweepLeadSeconds(), 0.0);
            assertEquals(5.0, out.startLead(), 0.0);
            assertEquals(-2.0, out.offDelay(), 0.0);
            assertEquals(0.5, out.alarmBelow(), 0.0);
        }

        /** From the code: float(None) raises TypeError and the except says continue - the default stays. */
        @Test
        void aNullStoredValueIsSkippedAndTheDefaultKept() {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("off_delay", null);
            stored.put("alarm_below", null);
            stored.put("burnout_seconds", 120.0);
            BurnerSettings out = burnerSettings("pellets", stored);
            assertEquals(3600.0, out.offDelay(), 0.0);
            assertEquals(50.0, out.alarmBelow(), 0.0);
            assertEquals(120.0, out.burnoutSeconds(), 0.0);
        }

        /** From the code: float("1,5") and float("3600s") raise ValueError - skipped like "inte ett tal". */
        @Test
        void aTextThatIsNotANumberIsSkipped() {
            for (String text : List.of("1,5", "3600s", "tre", "")) {
                BurnerSettings out = burnerSettings("oil", Map.of("off_delay", text));
                assertEquals(1800.0, out.offDelay(), 0.0, "'" + text + "'");
            }
        }

        /** From the code: an unknown key lands in Python's dict and nowhere else; the seven fields are untouched. */
        @Test
        void aStoredUnknownKeyChangesNothing() {
            Map<String, Object> stored = Map.of("unknown", 1, "foo", "bar", "sweep_lead", 10.0);
            assertEquals(BURNER_DEFAULTS.get(BURNER_PELLETS), burnerSettings("pellets", stored));
            assertEquals(BURNER_DEFAULTS.get(BURNER_OIL), burnerSettings("oil", stored));
        }

        /** From the code: a stored key only replaces its own field. */
        @Test
        void aStoredCallForHeatOnlyChangesThatField() {
            BurnerSettings out = burnerSettings("oil", Map.of("call_for_heat", "off"));
            assertEquals(new BurnerSettings(600.0, 1800.0, 60.0, 7200.0, "off", 65.0, null), out);
        }

        /** From the code: str(value) - a number stored under call_for_heat comes back as its text. */
        @Test
        void aNumberStoredAsCallForHeatBecomesText() {
            assertEquals("1", burnerSettings("pellets", Map.of("call_for_heat", 1)).callForHeat());
            assertEquals("1.0", burnerSettings("pellets", Map.of("call_for_heat", 1.0)).callForHeat());
        }

        /** From the code: control_path in (PATH_CONTROL, PATH_BOTH) is exact - "none", "CONTROL" and "" match nothing. */
        @Test
        void anUnknownPathGivesNeitherRelay() {
            for (String path : List.of("none", "CONTROL", "", "control ")) {
                assertEquals(new Relays(false, false), relaysInUse(path, true, true), "'" + path + "'");
            }
        }

        /** The tuple's order is (control cable, main supply), read through the record's accessors. */
        @Test
        void theRelaysComeBackAsCableThenSupply() {
            Relays cable = relaysInUse(PATH_CONTROL, true, true);
            assertTrue(cable.control(), "control cable");
            assertFalse(cable.power(), "main supply");
            Relays supply = relaysInUse(PATH_POWER, true, true);
            assertFalse(supply.control(), "control cable");
            assertTrue(supply.power(), "main supply");
        }

        /** From the code: or sweeping - the supply comes with a sweep whatever the path, and overheated takes both. */
        @Test
        void butSweepingStillGivesTheSupplyAndOverheatedGivesBoth() {
            assertEquals(new Relays(false, true), relaysInUse("none", true, true, true, false));
            assertEquals(new Relays(true, true), relaysInUse("none", true, true, false, true));
            assertEquals(new Relays(true, true), relaysInUse("none", true, true, true, true));
        }

        /**
         * The Java trap: a path that was never configured is null, and
         * Python's None in (...) is False, not an exception.
         */
        @Test
        void aNullPathGivesNeitherRelayWithoutAnException() {
            assertEquals(new Relays(false, false), relaysInUse(null, true, true));
            assertEquals(new Relays(false, true), relaysInUse(null, true, true, true, false));
            assertEquals(new Relays(true, true), relaysInUse(null, true, true, false, true));
        }

        /** From the code: return has_control, has_power - the guard takes every relay there is, and none is none. */
        @Test
        void overheatedWithNoRelaysGivesNeither() {
            assertEquals(new Relays(false, false), relaysInUse("both", false, false, false, true));
            assertEquals(new Relays(false, false), relaysInUse("both", false, false, true, true));
            assertEquals(new Relays(false, false), relaysInUse("both", false, false, true, false));
        }

        /** From the code: has_control and ..., has_power and ... - a relay not fitted is never in use. */
        @Test
        void aRelayNotFittedIsNeverInUse() {
            assertEquals(new Relays(false, true), relaysInUse("both", false, true));
            assertEquals(new Relays(true, false), relaysInUse("both", true, false));
            assertEquals(new Relays(false, false), relaysInUse("control", false, true));
            assertEquals(new Relays(false, false), relaysInUse("power", true, false));
            assertEquals(new Relays(false, false), relaysInUse("power", true, false, true, false));
        }

        /** From the code: str(call_for_heat).lower() == "on". */
        @Test
        void relayCallsForHeatIsCaseInsensitive() {
            for (String text : List.of("on", "ON", "On", "oN")) {
                assertTrue(relayCallsForHeat(text, true), text);
                assertFalse(relayCallsForHeat(text, false), text);
            }
        }

        /** From the code: anything that is not "on" is the inverted cable, "off" or not. */
        @Test
        void anythingElseIsTheInvertedCable() {
            for (String text : List.of("off", "OFF", "yes", "1", "sluten", "on_", "", "true")) {
                assertFalse(relayCallsForHeat(text, true), "'" + text + "'");
                assertTrue(relayCallsForHeat(text, false), "'" + text + "'");
            }
        }

        /** str(None) is "None", which is not "on": a null text is the inverted cable, not an exception. */
        @Test
        void aNullCallForHeatCountsAsNotOn() {
            assertFalse(relayCallsForHeat(null, true));
            assertTrue(relayCallsForHeat(null, false));
        }

        /** From the code: True if has_control else None - with nothing fitted, nothing is asked. */
        @Test
        void wantedWithNoRelaysLeavesBothAlone() {
            HeatSourcePlan out = planHeatSource(true, 0.0, false, false, 900.0);
            assertNull(out.control());
            assertNull(out.power());
            assertFalse(out.burningOut());
        }

        /** From the code: wanted is checked first; at_once is only about stopping. */
        @Test
        void wantedIgnoresAtOnce() {
            HeatSourcePlan out = planHeatSource(true, 5000.0, true, true, 900.0, true);
            assertEquals(Boolean.TRUE, out.control());
            assertEquals(Boolean.TRUE, out.power());
            assertFalse(out.burningOut());
        }

        /** From the code: wanted with one relay fitted asks through that one and leaves the other alone. */
        @Test
        void wantedWithOneRelayAsksOnlyThatOne() {
            HeatSourcePlan cable = planHeatSource(true, 0.0, true, false, 900.0);
            assertEquals(Boolean.TRUE, cable.control());
            assertNull(cable.power());
            assertFalse(cable.burningOut());
            HeatSourcePlan supply = planHeatSource(true, 0.0, false, true, 900.0);
            assertNull(supply.control());
            assertEquals(Boolean.TRUE, supply.power());
            assertFalse(supply.burningOut());
        }

        /** From the code: at_once cuts what is fitted - False if has_power else None. */
        @Test
        void atOnceWithOnlyTheSupplyCutsIt() {
            HeatSourcePlan out = planHeatSource(false, 0.0, false, true, 900.0, true);
            assertNull(out.control());
            assertEquals(Boolean.FALSE, out.power());
            assertFalse(out.burningOut());
        }

        @Test
        void atOnceWithOnlyTheCableCutsIt() {
            HeatSourcePlan out = planHeatSource(false, 0.0, true, false, 900.0, true);
            assertEquals(Boolean.FALSE, out.control());
            assertNull(out.power());
            assertFalse(out.burningOut());
        }

        @Test
        void atOnceWithNoRelaysLeavesBothAlone() {
            HeatSourcePlan out = planHeatSource(false, 0.0, false, false, 900.0, true);
            assertNull(out.control());
            assertNull(out.power());
            assertFalse(out.burningOut());
        }

        /** Without at_once the cable is checked before the supply: with neither fitted there is nothing to do. */
        @Test
        void aStopWithNoRelaysLeavesBothAlone() {
            HeatSourcePlan out = planHeatSource(false, 0.0, false, false, 900.0, false);
            assertNull(out.control());
            assertNull(out.power());
            assertFalse(out.burningOut());
        }

        /** From the code: seconds_stopped >= burnout_seconds - equal is enough, half a second short is not. */
        @Test
        void secondsStoppedExactlyBurnoutSecondsCutsTheSupply() {
            HeatSourcePlan atIt = planHeatSource(false, 900.0, true, true, 900.0);
            assertEquals(Boolean.FALSE, atIt.control());
            assertEquals(Boolean.FALSE, atIt.power());
            assertFalse(atIt.burningOut());
            HeatSourcePlan justShort = planHeatSource(false, 899.5, true, true, 900.0);
            assertEquals(Boolean.FALSE, justShort.control());
            assertNull(justShort.power());
            assertTrue(justShort.burningOut());
        }

        /** From the code: the arithmetic says so - a negative seconds_stopped is short of any positive burnout. */
        @Test
        void aNegativeSecondsStoppedIsABurnout() {
            HeatSourcePlan out = planHeatSource(false, -1.0, true, true, 900.0);
            assertEquals(Boolean.FALSE, out.control());
            assertNull(out.power());
            assertTrue(out.burningOut());
        }

        /** From the code: 0.0 >= 0.0 - a burnout of nought is over before it begins. */
        @Test
        void burnoutSecondsNoughtCutsAtOnce() {
            HeatSourcePlan out = planHeatSource(false, 0.0, true, true, 0.0);
            assertEquals(Boolean.FALSE, out.control());
            assertEquals(Boolean.FALSE, out.power());
            assertFalse(out.burningOut());
        }

        /** From the code: not has_power comes before the burnout - with no supply to wait for, nothing burns out. */
        @Test
        void withOnlyTheControlCableBurningOutIsFalse() {
            for (double stopped : new double[] {0.0, 300.0, 900.0, 5000.0}) {
                HeatSourcePlan out = planHeatSource(false, stopped, true, false, 900.0);
                assertEquals(Boolean.FALSE, out.control(), "stopped " + stopped);
                assertNull(out.power(), "stopped " + stopped);
                assertFalse(out.burningOut(), "stopped " + stopped);
            }
        }

        /** From the code: with only the supply it is cut whatever the clock says, and nothing burns out. */
        @Test
        void withOnlyTheSupplyTheClockDoesNotMatter() {
            for (double stopped : new double[] {-5.0, 0.0, 300.0, 900.0, 5000.0}) {
                HeatSourcePlan out = planHeatSource(false, stopped, false, true, 900.0);
                assertNull(out.control(), "stopped " + stopped);
                assertEquals(Boolean.FALSE, out.power(), "stopped " + stopped);
                assertFalse(out.burningOut(), "stopped " + stopped);
            }
        }

        /**
         * Python's keyword defaults - at_once=False, sweeping=False,
         * overheated=False, stored=None: each short overload is the long one
         * with them written out.
         */
        @Test
        void theShortOverloadsAreTheLongOnesWithTheDefaults() {
            for (boolean wanted : new boolean[] {true, false}) {
                for (boolean hasControl : new boolean[] {true, false}) {
                    for (boolean hasPower : new boolean[] {true, false}) {
                        for (double stopped : new double[] {0.0, 900.0}) {
                            assertEquals(planHeatSource(wanted, stopped, hasControl, hasPower, 900.0, false),
                                    planHeatSource(wanted, stopped, hasControl, hasPower, 900.0));
                        }
                    }
                }
            }
            for (String path : List.of("control", "power", "both", "none")) {
                for (boolean hasControl : new boolean[] {true, false}) {
                    for (boolean hasPower : new boolean[] {true, false}) {
                        assertEquals(relaysInUse(path, hasControl, hasPower, false, false),
                                relaysInUse(path, hasControl, hasPower), path);
                    }
                }
            }
            for (String burner : List.of("pellets", "oil", "ved")) {
                assertEquals(burnerSettings(burner, null), burnerSettings(burner), burner);
            }
        }
    }

    /**
     * Python's own answers, row by row from the four files verktyg/facit.py
     * wrote: the settings over stored dicts of every shape, every path and
     * relay combination, every spelling of the call, and the plan across all
     * six branches. An empty cell arrives as null.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        /**
         * burner;stored;startLead;offDelay;burnoutSeconds;sweepLeadSeconds;callForHeat;alarmBelow;controlPath:
         * the burner (empty for None) and the stored dict as key=type:value entries
         * (empty for None, {} for an empty dict), then the seven fields
         * burner_settings answered, controlPath empty when the dict has none.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/burner_settings.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void sameSettings(String burner, String stored, String startLead, String offDelay, String burnoutSeconds,
                          String sweepLeadSeconds, String callForHeat, String alarmBelow, String controlPath) {
            BurnerSettings result = burnerSettings(burner, stored(stored));
            assertEquals(number(startLead), result.startLead(), 0.0, "startLead");
            assertEquals(number(offDelay), result.offDelay(), 0.0, "offDelay");
            assertEquals(number(burnoutSeconds), result.burnoutSeconds(), 0.0, "burnoutSeconds");
            assertEquals(number(sweepLeadSeconds), result.sweepLeadSeconds(), 0.0, "sweepLeadSeconds");
            assertEquals(callForHeat, result.callForHeat(), "callForHeat");
            assertEquals(number(alarmBelow), result.alarmBelow(), 0.0, "alarmBelow");
            assertEquals(controlPath, result.controlPath(), "controlPath");
        }

        /**
         * controlPath;hasControl;hasPower;sweeping;overheated;control;power: every
         * path, known and not, with every combination of the four flags, and the
         * two relays relays_in_use answered.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/relays_in_use.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void sameRelays(String controlPath, String hasControl, String hasPower, String sweeping, String overheated,
                        String control, String power) {
            Relays result = relaysInUse(controlPath, bool(hasControl), bool(hasPower), bool(sweeping),
                    bool(overheated));
            assertEquals(bool(control), result.control(), "control");
            assertEquals(bool(power), result.power(), "power");
        }

        /**
         * callForHeat;call;calls: every spelling of the call, each with call true
         * and false, and what relay_calls_for_heat answered.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/relay_calls.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void sameCall(String callForHeat, String call, String calls) {
            assertEquals(bool(calls), relayCallsForHeat(callForHeat, bool(call)), "calls");
        }

        /**
         * wanted;secondsStopped;hasControl;hasPower;burnoutSeconds;atOnce;control;power;burningOut:
         * plan_heat_source's inputs and its plan, control and power empty for None,
         * so null, which is not the same as false.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/plan_heat_source.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void samePlan(String wanted, String secondsStopped, String hasControl, String hasPower,
                      String burnoutSeconds, String atOnce, String control, String power, String burningOut) {
            HeatSourcePlan result = planHeatSource(bool(wanted), number(secondsStopped), bool(hasControl),
                    bool(hasPower), number(burnoutSeconds), bool(atOnce));
            assertEquals(nullableBool(control), result.control(), "control");
            assertEquals(nullableBool(power), result.power(), "power");
            assertEquals(bool(burningOut), result.burningOut(), "burningOut");
        }
    }
}
