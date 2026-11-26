package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.house.Model.MODES_STOPPED;
import static se.sodalen.heating.house.Model.MODE_COAST;
import static se.sodalen.heating.house.Model.MODE_EMERGENCY;
import static se.sodalen.heating.house.Model.MODE_FAILSAFE;
import static se.sodalen.heating.house.Model.MODE_HEATING;
import static se.sodalen.heating.house.Model.MODE_HOLD;
import static se.sodalen.heating.house.Model.MODE_OFF;
import static se.sodalen.heating.house.Model.MODE_SUMMER;
import static se.sodalen.heating.house.Model.MODE_WARM;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.assertSameDict;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * The mode and the heat source: which stop applies, if any, how far the
 * valve may open for a cold boiler's sake, whether the boiler is too hot for
 * the burner, and whether the burner should run - the hot water, the sweep
 * and the stop delay included.
 *
 * <p>Python: controller.py (_mode, _cold_boiler_cap, _boiler_overheated,
 * _heat_source, _burner_for_heating), tests/test_cold_boiler.py (TestTheCap)
 * and tests/test_controller.py (TestModes, TestHeatSource) - there through
 * step(), here one method at a time with the state set by hand, since step()
 * comes in step 20.
 */
class ModeAndHeatSourceTest {

    static final double DT = 30.0;
    /** One minute, the step of TestHeatSource.hold. */
    static final double MINUTE = 60.0;
    static final double LIMIT = 50.0;

    static HeatingController made(Tuning tuning) {
        return new HeatingController(tuning);
    }

    static HeatingController made() {
        return made(new Tuning());
    }

    /** A cycle with the supply read and the heating on. */
    static ControlInputs cycle() {
        ControlInputs inputs = new ControlInputs(DT);
        inputs.flow = 45.0;
        return inputs;
    }

    static ControlInputs noFlow() {
        ControlInputs inputs = cycle();
        inputs.flow = null;
        return inputs;
    }

    static ControlInputs disabled() {
        ControlInputs inputs = cycle();
        inputs.heatingEnabled = false;
        return inputs;
    }

    static ControlInputs boiler(Double boiler, Double limit) {
        ControlInputs inputs = cycle();
        inputs.boiler = boiler;
        inputs.boilerLimit = limit;
        return inputs;
    }

    static ControlInputs boiler(Double boiler) {
        return boiler(boiler, LIMIT);
    }

    /** TestHeatSource.hold: a stop for this long, in one minute steps; the last cycle's output. */
    static ControlOutput hold(HeatingController loop, double seconds, String mode, boolean hotWater) {
        ControlOutput out = null;
        for (int i = 0; i < (int) (seconds / MINUTE); i++) {
            out = new ControlOutput();
            out.heatSource = loop.heatSource(mode, MINUTE, hotWater, out);
        }
        return out;
    }

    static ControlOutput hold(HeatingController loop, double seconds, String mode) {
        return hold(loop, seconds, mode, false);
    }

    /** _mode: off, summer on the day's average, warm on the room, hold and failsafe without a supply. */
    @Nested
    class TheMode {
        final HeatingController loop = made();

        /** test_disabled_closes_the_valve, the mode of it. */
        @Test
        void aSwitchedOffThermostatIsOffAndClearsBothStops() {
            loop.state.summerStopped = true;
            loop.state.warmStopped = true;
            assertEquals(MODE_OFF, loop.mode(disabled(), 25.0, 25.0));
            assertFalse(loop.state.summerStopped);
            assertFalse(loop.state.warmStopped);
        }

        @Test
        void offOutranksSummerAndWarm() {
            assertEquals(MODE_OFF, loop.mode(disabled(), 25.0, 25.0));
            assertFalse(loop.state.summerStopped, "not stopped for summer either");
        }

        /** test_summer_stop_has_hysteresis: 18 stops, 16 still stopped (over 17 - 1.5), 15 heats again. */
        @Test
        void theSummerStopHasHysteresis() {
            assertEquals(MODE_SUMMER, loop.mode(cycle(), 18.0, null));
            assertEquals(MODE_SUMMER, loop.mode(cycle(), 16.0, null), "just under the threshold: the hysteresis");
            assertEquals(MODE_HEATING, loop.mode(cycle(), 15.0, null));
            assertFalse(loop.state.summerStopped);
        }

        @Test
        void summerStartsExactlyAtTheThreshold() {
            assertEquals(MODE_SUMMER, loop.mode(cycle(), 17.0, null));
            assertTrue(loop.state.summerStopped);
        }

        @Test
        void justUnderTheThresholdIsNotSummer() {
            assertEquals(MODE_HEATING, loop.mode(cycle(), 16.9, null));
            assertFalse(loop.state.summerStopped);
        }

        /** 17 - 1.5 = 15.5: on it the summer holds, it ends only under it. */
        @Test
        void summerEndsOnlyUnderTheThresholdLessTheHysteresis() {
            loop.state.summerStopped = true;
            assertEquals(MODE_SUMMER, loop.mode(cycle(), 15.5, null), "on 15.5");
            assertEquals(MODE_HEATING, loop.mode(cycle(), 15.4, null));
            assertFalse(loop.state.summerStopped);
        }

        /** Threshold 15, hysteresis 3: 15 stops, 12 holds, 11.9 heats. */
        @Test
        void theSummerThresholdAndHysteresisAreSettings() {
            Tuning tuning = new Tuning();
            tuning.summerOutdoor = 15.0;
            tuning.summerHysteresis = 3.0;
            HeatingController set = made(tuning);
            assertEquals(MODE_SUMMER, set.mode(cycle(), 15.0, null));
            assertEquals(MODE_SUMMER, set.mode(cycle(), 12.0, null));
            assertEquals(MODE_HEATING, set.mode(cycle(), 11.9, null));
        }

        /** The summer test reads the day-long average it is given, not the reading of the cycle. */
        @Test
        void itReadsTheSummerAverageNotTheReading() {
            ControlInputs hot = cycle();
            hot.outdoor = 25.0;
            assertEquals(MODE_HEATING, loop.mode(hot, 10.0, null), "a hot afternoon on a cool day");
            ControlInputs cool = cycle();
            cool.outdoor = 5.0;
            assertEquals(MODE_SUMMER, loop.mode(cool, 18.0, null), "a cool night in a warm week");
        }

        /** test_warm_house_stops_heating: 22.5 stops, 21.8 still stopped (over 22 - 0.3), 21.5 heats. */
        @Test
        void aWarmHouseStopsHeating() {
            assertEquals(MODE_WARM, loop.mode(cycle(), 5.0, 22.5));
            assertEquals(MODE_WARM, loop.mode(cycle(), 5.0, 21.8));
            assertEquals(MODE_HEATING, loop.mode(cycle(), 5.0, 21.5));
            assertFalse(loop.state.warmStopped);
        }

        /** 21 + 1 = 22: on it the house is not yet warm, the stop wants more. */
        @Test
        void exactlyOnTargetPlusMarginIsNotWarm() {
            assertEquals(MODE_HEATING, loop.mode(cycle(), 5.0, 22.0));
            assertFalse(loop.state.warmStopped);
            assertEquals(MODE_WARM, loop.mode(cycle(), 5.0, 22.1));
            assertTrue(loop.state.warmStopped);
        }

        /** Target 20, margin 2, hysteresis 1: 22.1 stops, 21 holds (20 + 2 - 1, not under it), 20.9 heats. */
        @Test
        void theWarmStopsNumbersAreSettings() {
            Tuning tuning = new Tuning();
            tuning.roomTarget = 20.0;
            tuning.warmStopMargin = 2.0;
            tuning.warmStopHysteresis = 1.0;
            HeatingController set = made(tuning);
            assertEquals(MODE_HEATING, set.mode(cycle(), 5.0, 22.0));
            assertEquals(MODE_WARM, set.mode(cycle(), 5.0, 22.1));
            assertEquals(MODE_WARM, set.mode(cycle(), 5.0, 21.0));
            assertEquals(MODE_HEATING, set.mode(cycle(), 5.0, 20.9));
        }

        @Test
        void noIndoorReadingEndsTheWarmStop() {
            loop.state.warmStopped = true;
            assertEquals(MODE_HEATING, loop.mode(cycle(), 5.0, null));
            assertFalse(loop.state.warmStopped);
        }

        @Test
        void noIndoorReadingIsNotWarm() {
            assertEquals(MODE_HEATING, loop.mode(cycle(), 5.0, null));
        }

        /** Summer returns before the room is looked at: the warm stop is left as it was. */
        @Test
        void summerOutranksWarm() {
            assertEquals(MODE_SUMMER, loop.mode(cycle(), 18.0, 23.0));
            assertFalse(loop.state.warmStopped, "not set");
            loop.state.warmStopped = true;
            assertEquals(MODE_SUMMER, loop.mode(cycle(), 18.0, null));
            assertTrue(loop.state.warmStopped, "nor cleared");
        }

        @Test
        void aWarmHouseNeedsNoSupplyReading() {
            loop.state.secondsWithoutFlow = 1e6;
            assertEquals(MODE_WARM, loop.mode(noFlow(), 5.0, 23.0));
        }

        /** The stops come before the supply is looked at: summer and off need no reading either. */
        @Test
        void summerAndOffNeedNoSupplyReadingEither() {
            loop.state.secondsWithoutFlow = 1e6;
            assertEquals(MODE_SUMMER, loop.mode(noFlow(), 20.0, 20.0));
            ControlInputs off = disabled();
            off.flow = null;
            assertEquals(MODE_OFF, loop.mode(off, 20.0, 20.0));
        }

        /** test_losing_the_supply_sensor_holds_then_goes_to_max_heat: held under 600 s, failsafe from it. */
        @Test
        void losingTheSupplySensorHoldsThenGoesToFailsafe() {
            loop.state.secondsWithoutFlow = 60.0;
            assertEquals(MODE_HOLD, loop.mode(noFlow(), 5.0, 20.0));
            loop.state.secondsWithoutFlow = 599.9;
            assertEquals(MODE_HOLD, loop.mode(noFlow(), 5.0, 20.0));
            loop.state.secondsWithoutFlow = 600.0;
            assertEquals(MODE_FAILSAFE, loop.mode(noFlow(), 5.0, 20.0), "exactly at");
        }

        @Test
        void theFailsafeWaitIsASetting() {
            Tuning tuning = new Tuning();
            tuning.failsafeAfterSeconds = 60.0;
            HeatingController set = made(tuning);
            set.state.secondsWithoutFlow = 30.0;
            assertEquals(MODE_HOLD, set.mode(noFlow(), 5.0, 20.0));
            set.state.secondsWithoutFlow = 60.0;
            assertEquals(MODE_FAILSAFE, set.mode(noFlow(), 5.0, 20.0));
        }

        /** The clock without a supply is the mode's to read, not to count. */
        @Test
        void itDoesNotCountTheTimeWithoutASupply() {
            loop.state.secondsWithoutFlow = 120.0;
            loop.mode(noFlow(), 5.0, 20.0);
            assertEquals(120.0, loop.state.secondsWithoutFlow, 0.0);
        }

        @Test
        void otherwiseItHeats() {
            loop.state.secondsWithoutFlow = 1e6;
            assertEquals(MODE_HEATING, loop.mode(cycle(), 5.0, 20.0), "the supply is back");
            assertFalse(loop.state.summerStopped);
            assertFalse(loop.state.warmStopped);
        }
    }

    /** _cold_boiler_cap: the floor from clearance over the limit and down, free a band further up, a line between. */
    @Nested
    class TheColdBoiler {
        final HeatingController loop = made();

        /** test_a_warm_boiler_leaves_the_valve_free. */
        @Test
        void aWarmBoilerLeavesTheValveFree() {
            assertNull(loop.coldBoilerCap(boiler(66.0)));
        }

        /** test_free_from_twelve_degrees_over_the_limit: 62 - 50 - 2 = 10, the band, exactly. */
        @Test
        void freeFromTwelveDegreesOverTheLimit() {
            assertNull(loop.coldBoilerCap(boiler(62.0)));
        }

        /** 61.9: 9.9 of the band's 10, 10 + 90 * 0.99 = 99.1. */
        @Test
        void justUnderTheBandItIsNearlyFree() {
            assertEquals(99.1, loop.coldBoilerCap(boiler(61.9)), 1e-9);
        }

        /** test_the_floor_from_two_degrees_over_the_limit_and_down. */
        @Test
        void theFloorFromTwoDegreesOverTheLimitAndDown() {
            for (double at : new double[] {52.0, 50.0, 45.0}) {
                assertEquals(10.0, loop.coldBoilerCap(boiler(at)), 0.0, "boiler " + at);
            }
        }

        /** test_between_them_a_straight_line: 57 - 50 - 2 = 5, half the band, 10 + 90 / 2 = 55. */
        @Test
        void betweenThemAStraightLine() {
            assertEquals(55.0, loop.coldBoilerCap(boiler(57.0)), 1e-9);
        }

        /** test_the_line_is_where_the_settings_put_it: band 4, floor 20, clearance 0: 2 of 4, 20 + 80 / 2 = 60. */
        @Test
        void theLineIsWhereTheSettingsPutIt() {
            Tuning tuning = new Tuning();
            tuning.coldBoilerBand = 4.0;
            tuning.coldBoilerFloor = 20.0;
            tuning.coldBoilerClearance = 0.0;
            assertEquals(60.0, made(tuning).coldBoilerCap(boiler(52.0)), 1e-9);
        }

        /** test_a_cold_boiler_is_never_shut_entirely: far under the limit the floor still holds. */
        @Test
        void aColdBoilerIsNeverShutEntirely() {
            assertEquals(10.0, loop.coldBoilerCap(boiler(20.0)), 0.0);
        }

        /** test_without_a_burners_limit_there_is_no_guard. */
        @Test
        void withoutABurnersLimitThereIsNoGuard() {
            assertNull(loop.coldBoilerCap(boiler(50.0, null)));
        }

        /** test_nor_without_a_boiler_reading. */
        @Test
        void norWithoutABoilerReading() {
            assertNull(loop.coldBoilerCap(boiler(null, LIMIT)));
        }

        /** test_nought_band_switches_it_off. */
        @Test
        void noughtBandSwitchesItOff() {
            Tuning tuning = new Tuning();
            tuning.coldBoilerBand = 0.0;
            assertNull(made(tuning).coldBoilerCap(boiler(50.0)));
        }

        @Test
        void aNegativeBandSwitchesItOffToo() {
            Tuning tuning = new Tuning();
            tuning.coldBoilerBand = -5.0;
            assertNull(made(tuning).coldBoilerCap(boiler(50.0)));
        }

        /** A floor of 0 is a shut valve at the floor, not "no guard". 57: 0 + 100 / 2. */
        @Test
        void aNoughtFloorShutsTheValveAtIt() {
            Tuning tuning = new Tuning();
            tuning.coldBoilerFloor = 0.0;
            HeatingController set = made(tuning);
            assertEquals(0.0, set.coldBoilerCap(boiler(52.0)), 0.0);
            assertEquals(50.0, set.coldBoilerCap(boiler(57.0)), 1e-9);
        }

        /** Floor 150 is 100: the line is flat. Floor -20 is 0: 57 gives 0 + 100 / 2 = 50. */
        @Test
        void theFloorIsHeldBetweenNoughtAndAHundred() {
            Tuning over = new Tuning();
            over.coldBoilerFloor = 150.0;
            assertEquals(100.0, made(over).coldBoilerCap(boiler(52.0)), 0.0);
            assertEquals(100.0, made(over).coldBoilerCap(boiler(57.0)), 1e-9);
            Tuning under = new Tuning();
            under.coldBoilerFloor = -20.0;
            assertEquals(0.0, made(under).coldBoilerCap(boiler(45.0)), 0.0);
            assertEquals(50.0, made(under).coldBoilerCap(boiler(57.0)), 1e-9);
        }

        /** The limit moves the line with it: 40 + 2 + 5 = 47 is half way. */
        @Test
        void theLimitMovesTheLine() {
            assertEquals(55.0, loop.coldBoilerCap(boiler(47.0, 40.0)), 1e-9);
        }

        /** test_it_is_a_setting_of_the_tuning. */
        @Test
        void itIsASettingOfTheTuning() {
            Tuning tuning = new Tuning();
            assertEquals(List.of(10.0, 2.0, 10.0),
                    List.of(tuning.coldBoilerBand, tuning.coldBoilerClearance, tuning.coldBoilerFloor));
        }
    }

    /** _boiler_overheated: trips at boiler_max, releases at boiler_max_release, held through a missing reading. */
    @Nested
    class TheOverheat {
        final HeatingController loop = made();

        /** test_an_overheated_boiler_cuts_the_burner_whatever_the_house_wants: 95 is the limit itself. */
        @Test
        void itTripsAtTheLimit() {
            assertTrue(loop.boilerOverheated(95.0));
            assertTrue(loop.state.boilerOverheated);
        }

        /** test_just_under_the_limit_it_runs. */
        @Test
        void justUnderTheLimitItDoesNot() {
            assertFalse(loop.boilerOverheated(94.9));
            assertFalse(loop.state.boilerOverheated);
        }

        /** test_it_holds_until_the_boiler_has_cooled_to_the_release: 96, 90, none, 85.1 hold; 85 releases; 90 stays released. */
        @Test
        void itHoldsUntilTheBoilerHasCooledToTheRelease() {
            List<Boolean> verdicts = new ArrayList<>();
            for (Double at : Arrays.asList(96.0, 90.0, null, 85.1, 85.0, 90.0)) {
                verdicts.add(loop.boilerOverheated(at));
            }
            assertEquals(List.of(true, true, true, true, false, false), verdicts);
        }

        @Test
        void betweenTheTwoACoolBoilerStaysCool() {
            assertFalse(loop.boilerOverheated(90.0));
        }

        /** A sensor that drops out is no proof the boiler cooled - nor that it heated. */
        @Test
        void aMissingReadingHoldsEitherState() {
            assertFalse(loop.boilerOverheated(null), "cool stays cool");
            loop.state.boilerOverheated = true;
            assertTrue(loop.boilerOverheated(null), "hot stays hot");
            assertTrue(loop.state.boilerOverheated);
        }

        @Test
        void aCoolBoilerReleasesTheStateItIsGiven() {
            loop.state.boilerOverheated = true;
            assertFalse(loop.boilerOverheated(60.0));
            assertFalse(loop.state.boilerOverheated);
        }

        /** test_the_limits_are_settings: 90 and 70; 89 runs, 90 trips, 75 holds, 70 releases. */
        @Test
        void theLimitsAreSettings() {
            Tuning tuning = new Tuning();
            tuning.boilerMax = 90.0;
            tuning.boilerMaxRelease = 70.0;
            HeatingController set = made(tuning);
            List<Boolean> verdicts = new ArrayList<>();
            for (double at : new double[] {89.0, 90.0, 75.0, 70.0}) {
                verdicts.add(set.boilerOverheated(at));
            }
            assertEquals(List.of(false, true, true, false), verdicts);
        }
    }

    /** _heat_source: the stop clock, the hot boiler, the sweep, the hot water, then the radiators. */
    @Nested
    class TheHeatSource {
        final HeatingController loop = made();

        @Test
        void everyStoppedModeCountsTheTimeStopped() {
            for (String mode : MODES_STOPPED) {
                HeatingController fresh = made();
                fresh.state.secondsStopped = 100.0;
                fresh.heatSource(mode, DT, false, new ControlOutput());
                assertEquals(130.0, fresh.state.secondsStopped, 0.0, mode);
            }
        }

        @Test
        void everyOtherModeStartsItAgain() {
            for (String mode : List.of(MODE_HEATING, MODE_HOLD, MODE_FAILSAFE, MODE_EMERGENCY)) {
                HeatingController fresh = made();
                fresh.state.secondsStopped = 5000.0;
                assertTrue(fresh.heatSource(mode, DT, false, new ControlOutput()), mode);
                assertEquals(0.0, fresh.state.secondsStopped, 0.0, mode);
            }
        }

        /** test_heating_keeps_it_running. */
        @Test
        void heatingKeepsItRunning() {
            ControlOutput out = new ControlOutput();
            assertTrue(loop.heatSource(MODE_HEATING, DT, false, out));
            assertEquals(List.of(), out.flags);
            assertFalse(out.burnerForHotWater);
        }

        /** test_failsafe_wants_it_running: max heat against a cold tank achieves nothing. */
        @Test
        void failsafeWantsItRunning() {
            assertTrue(loop.heatSource(MODE_FAILSAFE, MINUTE, false, new ControlOutput()));
        }

        @Test
        void aHeldValveWantsItRunning() {
            assertTrue(loop.heatSource(MODE_HOLD, MINUTE, false, new ControlOutput()));
        }

        @Test
        void soDoesAnEmergency() {
            assertTrue(loop.heatSource(MODE_EMERGENCY, MINUTE, false, new ControlOutput()));
        }

        /** test_summer_switches_it_off_but_not_straight_away: on after 20 minutes, off after 40. */
        @Test
        void summerSwitchesItOffButNotStraightAway() {
            assertTrue(hold(loop, 20 * MINUTE, MODE_SUMMER).heatSource, "off delay had not elapsed");
            assertFalse(hold(loop, 20 * MINUTE, MODE_SUMMER).heatSource);
        }

        /** 30 minutes of one minute cycles: the thirtieth is 1800, the delay itself. */
        @Test
        void itGoesOffTheCycleTheDelayIsServed() {
            assertTrue(hold(loop, 29 * MINUTE, MODE_SUMMER).heatSource, "1740");
            assertFalse(hold(loop, MINUTE, MODE_SUMMER).heatSource, "1800");
        }

        /** test_it_comes_back_on_immediately. */
        @Test
        void itComesBackOnImmediately() {
            assertFalse(hold(loop, 60 * MINUTE, MODE_SUMMER).heatSource);
            assertTrue(loop.heatSource(MODE_HEATING, MINUTE, false, new ControlOutput()));
            assertEquals(0.0, loop.state.secondsStopped, 0.0);
        }

        /** test_one_cycle_of_heating_resets_the_delay: 25 minutes, one of heating, 25 more is still on. */
        @Test
        void oneCycleOfHeatingResetsTheDelay() {
            hold(loop, 25 * MINUTE, MODE_SUMMER);
            loop.heatSource(MODE_HEATING, MINUTE, false, new ControlOutput());
            assertTrue(hold(loop, 25 * MINUTE, MODE_SUMMER).heatSource, "the clock should have started again");
        }

        /** The delay counts time stopped, not time in this stop: 20 warm minutes and 15 of summer is 35. */
        @Test
        void aWarmHouseTurningIntoSummerKeepsTheClock() {
            hold(loop, 20 * MINUTE, MODE_WARM);
            assertFalse(hold(loop, 15 * MINUTE, MODE_SUMMER).heatSource);
        }

        /** test_a_warm_house_leaves_it_alone_by_default. */
        @Test
        void aWarmHouseLeavesItAloneByDefault() {
            assertTrue(hold(loop, 6 * 60 * MINUTE, MODE_WARM).heatSource);
        }

        /** test_a_warm_house_can_be_allowed_to_switch_it_off. */
        @Test
        void aWarmHouseCanBeAllowedToSwitchItOff() {
            Tuning tuning = new Tuning();
            tuning.heatSourceOffWhenIdle = true;
            assertFalse(hold(made(tuning), 6 * 60 * MINUTE, MODE_WARM).heatSource);
        }

        /** test_thermostat_off_switches_it_off. */
        @Test
        void aSwitchedOffThermostatSwitchesItOff() {
            assertFalse(hold(loop, 2 * 60 * MINUTE, MODE_OFF).heatSource);
        }

        /** test_summer_can_be_told_to_leave_it_alone. */
        @Test
        void summerCanBeToldToLeaveItAlone() {
            Tuning tuning = new Tuning();
            tuning.heatSourceOffInSummer = false;
            assertTrue(hold(made(tuning), 6 * 60 * MINUTE, MODE_SUMMER).heatSource);
        }

        /** test_an_overheated_boiler_cuts_the_burner_whatever_the_house_wants. */
        @Test
        void anOverheatedBoilerCutsTheBurnerWhateverTheHouseWants() {
            loop.state.boilerOverheated = true;
            ControlOutput out = new ControlOutput();
            assertFalse(loop.heatSource(MODE_HEATING, DT, false, out));
            assertEquals(List.of("boiler_overheat"), out.flags);
        }

        /** test_even_for_the_hot_water, and test_a_sweep_or_a_hot_boiler_never_says_hot_water. */
        @Test
        void anOverheatedBoilerOutranksTheHotWaterAndTheSweep() {
            loop.state.boilerOverheated = true;
            ControlOutput out = new ControlOutput();
            assertFalse(loop.heatSource(MODE_SUMMER, DT, true, out, true));
            assertEquals(List.of("boiler_overheat"), out.flags);
            assertFalse(out.burnerForHotWater);
        }

        @Test
        void anOverheatedBoilerStillCountsTheClock() {
            loop.state.boilerOverheated = true;
            loop.state.secondsStopped = 100.0;
            loop.heatSource(MODE_SUMMER, DT, false, new ControlOutput());
            assertEquals(130.0, loop.state.secondsStopped, 0.0, "stopped");
            loop.heatSource(MODE_HEATING, DT, false, new ControlOutput());
            assertEquals(0.0, loop.state.secondsStopped, 0.0, "heating");
        }

        /** It reads the state _boiler_overheated left; it does not decide it, nor clear it. */
        @Test
        void itReadsTheOverheatItIsGiven() {
            loop.state.boilerOverheated = true;
            ControlOutput out = new ControlOutput();
            assertFalse(loop.heatSource(MODE_HEATING, DT, false, out));
            assertEquals(List.of("boiler_overheat"), out.flags);
            assertTrue(loop.state.boilerOverheated, "still overheated: only boilerOverheated() may clear it");
        }

        /** test_a_sweep_keeps_the_burner_off_even_for_the_hot_water. */
        @Test
        void aSweepKeepsTheBurnerOffEvenForTheHotWater() {
            ControlOutput out = new ControlOutput();
            assertFalse(loop.heatSource(MODE_HEATING, DT, true, out, true));
            assertEquals(List.of("sweeping"), out.flags);
            assertFalse(out.burnerForHotWater, "a sweep never says hot water");
        }

        /** test_at_once_with_no_stop_delay. */
        @Test
        void aSweepStopsItAtOnceWithNoStopDelay() {
            Tuning tuning = new Tuning();
            tuning.heatSourceOffDelay = 3600.0;
            assertFalse(made(tuning).heatSource(MODE_HEATING, DT, false, new ControlOutput(), true));
        }

        /** test_it_lights_again_the_cycle_the_sweep_ends. */
        @Test
        void itLightsAgainTheCycleTheSweepEnds() {
            loop.heatSource(MODE_HEATING, DT, false, new ControlOutput(), true);
            ControlOutput out = new ControlOutput();
            assertTrue(loop.heatSource(MODE_HEATING, DT, false, out, false));
            assertEquals(List.of(), out.flags);
        }

        @Test
        void aSweepStillCountsTheClock() {
            loop.state.secondsStopped = 100.0;
            loop.heatSource(MODE_SUMMER, DT, false, new ControlOutput(), true);
            assertEquals(130.0, loop.state.secondsStopped, 0.0);
        }

        /** The four arguments are Python's sweeping=False: summer with the hot water on is on. */
        @Test
        void withoutTheSweepArgumentThereIsNoSweep() {
            ControlOutput out = new ControlOutput();
            assertTrue(loop.heatSource(MODE_SUMMER, DT, true, out));
            assertEquals(List.of("hot_water"), out.flags);
        }

        /** test_hot_water_keeps_the_burner_running_through_summer. */
        @Test
        void hotWaterKeepsTheBurnerRunningThroughSummer() {
            ControlOutput out = hold(loop, 2 * 60 * MINUTE, MODE_SUMMER, true);
            assertTrue(out.heatSource);
            assertEquals(List.of("hot_water"), out.flags);
        }

        /** test_it_outranks_a_switched_off_thermostat_too. */
        @Test
        void hotWaterOutranksASwitchedOffThermostatToo() {
            assertTrue(hold(loop, 2 * 60 * MINUTE, MODE_OFF, true).heatSource);
        }

        /** test_without_it_summer_still_stops_the_burner, and test_without_hot_water_it_is_never_said. */
        @Test
        void withoutHotWaterSummerStillStopsTheBurner() {
            ControlOutput out = hold(loop, 2 * 60 * MINUTE, MODE_SUMMER, false);
            assertFalse(out.heatSource);
            assertFalse(out.burnerForHotWater);
            assertEquals(List.of(), out.flags);
        }

        /** test_in_summer_it_is_the_hot_water_that_has_the_burner_on. */
        @Test
        void inSummerItIsTheHotWaterThatHasTheBurnerOn() {
            assertTrue(hold(loop, 2 * 60 * MINUTE, MODE_SUMMER, true).burnerForHotWater);
        }

        /** test_a_heating_house_runs_it_for_the_radiators. */
        @Test
        void aHeatingHouseRunsItForTheRadiators() {
            ControlOutput out = new ControlOutput();
            assertTrue(loop.heatSource(MODE_HEATING, DT, true, out));
            assertEquals(List.of("hot_water"), out.flags);
            assertFalse(out.burnerForHotWater);
        }

        /** test_inside_the_stop_delay_the_radiators_still_have_it_on: 29 minutes is 1740, 31 is 1860. */
        @Test
        void insideTheStopDelayTheRadiatorsStillHaveItOn() {
            ControlOutput before = hold(loop, 29 * MINUTE, MODE_SUMMER, true);
            ControlOutput after = hold(loop, 2 * MINUTE, MODE_SUMMER, true);
            assertEquals(List.of(false, true), List.of(before.burnerForHotWater, after.burnerForHotWater));
            assertTrue(before.heatSource);
            assertTrue(after.heatSource);
        }

        /** The clock keeps running through the hot water: 120 minutes of it are 7200 s stopped. */
        @Test
        void theClockRunsThroughTheHotWater() {
            hold(loop, 2 * 60 * MINUTE, MODE_SUMMER, true);
            assertEquals(7200.0, loop.state.secondsStopped, 1e-9);
        }

        /** test_turning_it_off_lets_the_burner_stop_at_once. */
        @Test
        void turningTheHotWaterOffLetsTheBurnerStopAtOnce() {
            hold(loop, 2 * 60 * MINUTE, MODE_SUMMER, true);
            assertFalse(loop.heatSource(MODE_SUMMER, MINUTE, false, new ControlOutput()));
        }

        /** test_a_setback_that_may_stop_the_burner_leaves_it_to_the_hot_water. */
        @Test
        void aSetbackThatMayStopTheBurnerLeavesItToTheHotWater() {
            ControlOutput may = new ControlOutput();
            ControlOutput keeps = new ControlOutput();
            may.coastBurner = true;
            assertTrue(loop.heatSource(MODE_COAST, DT, true, may));
            assertTrue(loop.heatSource(MODE_COAST, DT, true, keeps));
            assertEquals(List.of(true, false), List.of(may.burnerForHotWater, keeps.burnerForHotWater));
        }

        /** test_asking_the_radiators_raises_no_flag_of_theirs. */
        @Test
        void askingTheRadiatorsRaisesNoFlagOfTheirs() {
            ControlOutput forWater = new ControlOutput();
            ControlOutput alone = new ControlOutput();
            forWater.coastBurner = true;
            alone.coastBurner = true;
            assertTrue(loop.heatSource(MODE_WARM, DT, true, forWater));
            assertFalse(loop.heatSource(MODE_WARM, DT, false, alone));
            assertEquals(List.of("hot_water"), forWater.flags);
            assertEquals(List.of("coast_burner"), alone.flags);
            assertTrue(forWater.burnerForHotWater);
        }

        /** A warm stop whose forecast keeps the burner: no flag, the burner on. */
        @Test
        void aWarmHouseWithoutTheCoastsVerdictSaysNothing() {
            ControlOutput out = new ControlOutput();
            assertTrue(loop.heatSource(MODE_WARM, DT, false, out));
            assertEquals(List.of(), out.flags);
        }

        /** The coast's own verdict is said only in the warm stop. */
        @Test
        void theCoastBurnerFlagIsTheWarmStopsOnly() {
            ControlOutput coast = new ControlOutput();
            coast.coastBurner = true;
            assertFalse(loop.heatSource(MODE_COAST, DT, false, coast));
            assertEquals(List.of(), coast.flags, "coast");
            ControlOutput heating = new ControlOutput();
            heating.coastBurner = true;
            assertTrue(loop.heatSource(MODE_HEATING, DT, false, heating));
            assertEquals(List.of(), heating.flags, "heating");
        }

        /** Its flags come after those already said this cycle. */
        @Test
        void itsFlagsAreAddedToThoseAlreadySaid() {
            ControlOutput out = new ControlOutput();
            out.flags.add("earlier");
            loop.heatSource(MODE_HEATING, DT, true, out);
            assertEquals(List.of("earlier", "hot_water"), out.flags);
        }

        /** Without hot water nothing is said of it: the output keeps its default, and the flag is not raised. */
        @Test
        void itSaysHotWaterOnlyWhenThereIsSome() {
            loop.state.secondsStopped = 7200.0;
            ControlOutput out = new ControlOutput();
            assertFalse(loop.heatSource(MODE_SUMMER, DT, false, out), "summer after the delay: off");
            assertFalse(out.burnerForHotWater);
            assertFalse(out.flags.contains("hot_water"));
        }
    }

    /** _burner_for_heating: whether the radiators want the burner, the hot water left out. */
    @Nested
    class TheBurnerForHeating {
        final HeatingController loop = made();

        static ControlOutput coasting(boolean burner) {
            ControlOutput out = new ControlOutput();
            out.coastBurner = burner;
            return out;
        }

        /** No clock to sit through: the coast's verdict already holds the delay. */
        @Test
        void aCoastAsksOnlyTheCoastsVerdict() {
            loop.state.secondsStopped = 0.0;
            assertFalse(loop.burnerForHeating(MODE_COAST, coasting(true), true));
            assertTrue(loop.burnerForHeating(MODE_COAST, coasting(false), true));
            loop.state.secondsStopped = 1e6;
            assertTrue(loop.burnerForHeating(MODE_COAST, coasting(false), true), "nor after it");
        }

        @Test
        void notStoppedWantsIt() {
            loop.state.secondsStopped = 1e6;
            assertTrue(loop.burnerForHeating(MODE_HEATING, new ControlOutput(), false));
            assertTrue(loop.burnerForHeating(MODE_HEATING, coasting(true), false));
        }

        /** Hold and failsafe come as stopped=true by hand only; they are in no switch-off setting. */
        @Test
        void aHoldOrAFailsafeIsNeverSwitchedOff() {
            loop.state.secondsStopped = 1e6;
            assertTrue(loop.burnerForHeating(MODE_HOLD, new ControlOutput(), true));
            assertTrue(loop.burnerForHeating(MODE_FAILSAFE, new ControlOutput(), true));
        }

        /** The forecast answers first, the clock not looked at, the idle setting off. */
        @Test
        void aWarmStopWithTheCoastsVerdictStopsItAtOnce() {
            loop.state.secondsStopped = 0.0;
            assertFalse(loop.tuning.heatSourceOffWhenIdle);
            assertFalse(loop.burnerForHeating(MODE_WARM, coasting(true), true));
        }

        @Test
        void aWarmStopIsLeftAloneByDefault() {
            loop.state.secondsStopped = 1e6;
            assertTrue(loop.burnerForHeating(MODE_WARM, new ControlOutput(), true));
        }

        @Test
        void summerStopsItAfterTheDelay() {
            loop.state.secondsStopped = 1799.0;
            assertTrue(loop.burnerForHeating(MODE_SUMMER, new ControlOutput(), true));
            loop.state.secondsStopped = 1800.0;
            assertFalse(loop.burnerForHeating(MODE_SUMMER, new ControlOutput(), true), "exactly at");
        }

        @Test
        void offStopsItAfterTheDelay() {
            loop.state.secondsStopped = 1799.0;
            assertTrue(loop.burnerForHeating(MODE_OFF, new ControlOutput(), true));
            loop.state.secondsStopped = 1800.0;
            assertFalse(loop.burnerForHeating(MODE_OFF, new ControlOutput(), true));
        }

        @Test
        void aWarmStopAllowedToStopsItAfterTheDelay() {
            Tuning tuning = new Tuning();
            tuning.heatSourceOffWhenIdle = true;
            HeatingController set = made(tuning);
            set.state.secondsStopped = 1799.0;
            assertTrue(set.burnerForHeating(MODE_WARM, new ControlOutput(), true));
            set.state.secondsStopped = 1800.0;
            assertFalse(set.burnerForHeating(MODE_WARM, new ControlOutput(), true));
        }

        @Test
        void eachStopsSettingIsItsOwn() {
            Tuning summer = new Tuning();
            summer.heatSourceOffInSummer = false;
            HeatingController noSummer = made(summer);
            noSummer.state.secondsStopped = 1e6;
            assertTrue(noSummer.burnerForHeating(MODE_SUMMER, new ControlOutput(), true), "summer left alone");
            assertFalse(noSummer.burnerForHeating(MODE_OFF, new ControlOutput(), true), "off still stops it");

            Tuning off = new Tuning();
            off.heatSourceOffWhenDisabled = false;
            HeatingController noOff = made(off);
            noOff.state.secondsStopped = 1e6;
            assertTrue(noOff.burnerForHeating(MODE_OFF, new ControlOutput(), true), "off left alone");
            assertFalse(noOff.burnerForHeating(MODE_SUMMER, new ControlOutput(), true), "summer still stops it");
        }

        @Test
        void theDelayIsASetting() {
            Tuning tuning = new Tuning();
            tuning.heatSourceOffDelay = 3600.0;
            HeatingController set = made(tuning);
            set.state.secondsStopped = 1800.0;
            assertTrue(set.burnerForHeating(MODE_SUMMER, new ControlOutput(), true));
            set.state.secondsStopped = 3600.0;
            assertFalse(set.burnerForHeating(MODE_SUMMER, new ControlOutput(), true));
        }

        /** It asks only: no flag, no clock, no hot water. */
        @Test
        void itAsksOnly() {
            loop.state.secondsStopped = 1800.0;
            ControlOutput out = coasting(true);
            loop.burnerForHeating(MODE_WARM, out, true);
            loop.burnerForHeating(MODE_SUMMER, out, true);
            assertEquals(List.of(), out.flags);
            assertFalse(out.burnerForHotWater);
            assertEquals(1800.0, loop.state.secondsStopped, 0.0);
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        static List<String> words(String cell) {
            return cell == null || cell.isEmpty() ? List.of() : Arrays.asList(cell.split("\\|", -1));
        }

        static Map<String, Object> stops(LearnedState state) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summer_stopped", state.summerStopped);
            out.put("warm_stopped", state.warmStopped);
            return out;
        }

        /** tuning;before;heating_enabled;outdoor_summer;indoor;flow;seconds_without_flow;mode;after - the stops
         *  as "summer_stopped=b:...|warm_stopped=b:...". */
        @ParameterizedTest
        @CsvFileSource(resources = "/mode.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameMode(String tuningCell, String before, String heatingEnabled, String outdoorSummer, String indoor,
                      String flow, String secondsWithoutFlow, String mode, String after) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            Map<String, Object> was = dict(before);
            assertEquals(List.of("summer_stopped", "warm_stopped"), List.copyOf(was.keySet()), "the facit's state");
            loop.state.summerStopped = (Boolean) was.get("summer_stopped");
            loop.state.warmStopped = (Boolean) was.get("warm_stopped");
            loop.state.secondsWithoutFlow = required(secondsWithoutFlow);
            ControlInputs inputs = new ControlInputs(DT);
            inputs.heatingEnabled = bool(heatingEnabled);
            inputs.flow = number(flow);
            assertEquals(mode, loop.mode(inputs, required(outdoorSummer), number(indoor)), "mode");
            assertSameDict(dict(after), stops(loop.state), "state after");
        }

        /** cold_boiler_band;cold_boiler_clearance;cold_boiler_floor;boiler;boiler_limit;cap. */
        @ParameterizedTest
        @CsvFileSource(resources = "/cold_boiler_cap.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameColdBoilerCap(String band, String clearance, String floor, String boiler, String limit, String cap) {
            Tuning tuning = new Tuning();
            tuning.coldBoilerBand = required(band);
            tuning.coldBoilerClearance = required(clearance);
            tuning.coldBoilerFloor = required(floor);
            assertClose(number(cap), made(tuning).coldBoilerCap(boiler(number(boiler), number(limit))), "cap");
        }

        /** boiler_max;boiler_max_release;before;boiler;result. */
        @ParameterizedTest
        @CsvFileSource(resources = "/boiler_overheated.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameBoilerOverheated(String max, String release, String before, String boiler, String result) {
            Tuning tuning = new Tuning();
            tuning.boilerMax = required(max);
            tuning.boilerMaxRelease = required(release);
            HeatingController loop = made(tuning);
            loop.state.boilerOverheated = bool(before);
            assertEquals(bool(result), loop.boilerOverheated(number(boiler)), "result");
            assertEquals(bool(result), loop.state.boilerOverheated, "state after");
        }

        /** tuning;seconds_stopped_before;boiler_overheated;mode;dt;hot_water;sweeping;coast_burner;result;
         *  burner_for_hot_water;flags;seconds_stopped_after - flags by "|". */
        @ParameterizedTest
        @CsvFileSource(resources = "/heat_source.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameHeatSource(String tuningCell, String stoppedBefore, String overheated, String mode, String dt,
                            String hotWater, String sweeping, String coastBurner, String result,
                            String burnerForHotWater, String flags, String stoppedAfter) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            loop.state.secondsStopped = required(stoppedBefore);
            loop.state.boilerOverheated = bool(overheated);
            ControlOutput out = new ControlOutput();
            out.coastBurner = bool(coastBurner);
            boolean got = loop.heatSource(mode, required(dt), bool(hotWater), out, bool(sweeping));
            assertEquals(bool(result), got, "result");
            assertEquals(bool(burnerForHotWater), out.burnerForHotWater, "burner_for_hot_water");
            assertEquals(words(flags), out.flags, "flags");
            assertClose(required(stoppedAfter), loop.state.secondsStopped, "seconds_stopped after");
        }

        /** tuning;seconds_stopped;mode;coast_burner;stopped;result. */
        @ParameterizedTest
        @CsvFileSource(resources = "/burner_for_heating.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameBurnerForHeating(String tuningCell, String secondsStopped, String mode, String coastBurner,
                                  String stopped, String result) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            loop.state.secondsStopped = required(secondsStopped);
            ControlOutput out = new ControlOutput();
            out.coastBurner = bool(coastBurner);
            assertEquals(bool(result), loop.burnerForHeating(mode, out, bool(stopped)), "result");
            assertEquals(required(secondsStopped), loop.state.secondsStopped, 0.0, "the clock is not its to count");
        }
    }
}
