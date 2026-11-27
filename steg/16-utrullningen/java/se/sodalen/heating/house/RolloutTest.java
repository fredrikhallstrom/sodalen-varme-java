package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static se.sodalen.heating.house.Houses.flat;
import static se.sodalen.heating.house.Houses.turning;
import static se.sodalen.heating.house.Model.LEARN_HOUSE;
import static se.sodalen.heating.house.Model.MODE_COAST;
import static se.sodalen.heating.house.Model.MODE_FAILSAFE;
import static se.sodalen.heating.house.Model.MODE_HEATING;
import static se.sodalen.heating.house.Model.MODE_HOLD;
import static se.sodalen.heating.house.Model.MODE_OFF;
import static se.sodalen.heating.house.Model.MODE_SUMMER;
import static se.sodalen.heating.house.Model.MODE_WARM;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import se.sodalen.heating.curve.PointTable;
import se.sodalen.heating.estimate.Ewma;

/**
 * The rollout: what the house model is told each cycle, and whether the
 * house may be left to coast on what it already has - the trust in the
 * model, the exploration budget, the two horizons of the shunt and the
 * burner, the hysteresis, and the learning hold that keeps a setback back
 * until the curve table has its hours.
 *
 * <p>Python: controller.py (_conditions, _night, _distrust, _observe_house,
 * _learning_hold, _coast), tests/test_coast.py, tests/test_controller.py
 * (TestTheNightForTheHouseModel) and tests/test_learning_status.py - there
 * through step(), here one method at a time, since step() comes in step 20.
 *
 * <p>test_coast.py puts a FixedHold in the house's place, a model that
 * always predicts the same hold. Java's HouseModel is final, so these tests
 * use a taught house instead: they first read how long it holds (H) and then
 * set the leads and margins on either side of H.
 */
class RolloutTest {

    static final double DT = 30.0;
    static final double HOUR = 3600.0;

    static HeatingController made(Tuning tuning) {
        return new HeatingController(tuning);
    }

    static HeatingController made() {
        return made(new Tuning());
    }

    /** test_coast.py's loop(): a learned house, the target 21, the learning hold off, a pellet burner's lead. */
    static Tuning coastTuning() {
        Tuning tuning = new Tuning();
        tuning.roomTarget = 21.0;
        tuning.coastLearningHoldSeconds = 0.0;
        return tuning;
    }

    static HeatingController made(HouseModel house, Tuning tuning) {
        HeatingController loop = new HeatingController(tuning, new LearnedState(), house, null, null, null);
        loop.burnerLeadSeconds(1800.0);
        return loop;
    }

    static HeatingController made(HouseModel house) {
        return made(house, coastTuning());
    }

    static ControlInputs ahead(List<Forecast> forecast) {
        ControlInputs inputs = new ControlInputs(DT);
        inputs.forecast = forecast;
        return inputs;
    }

    /** _conditions: the weather now as the model wants it, the sun from sunlight_now. */
    @Nested
    class TheConditions {
        final HeatingController loop = made();

        @Test
        void withoutAnOutdoorReadingThereAreNone() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.wind = 4.0;
            inputs.windBearing = 90.0;
            inputs.sunlight = 0.4;
            inputs.hour = 7.5;
            assertNull(loop.conditions(inputs), "no outdoor");
            inputs.outdoor = 3.0;
            assertNotNull(loop.conditions(inputs), "with it");
        }

        @Test
        void itCarriesEveryReading() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = 3.0;
            inputs.wind = 4.0;
            inputs.windBearing = 90.0;
            inputs.sunlight = 0.4;
            inputs.hour = 7.5;
            assertEquals(new Conditions(3.0, 4.0, 90.0, 0.4, 7.5), loop.conditions(inputs));
        }

        @Test
        void whatIsMissingStaysMissing() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = -2.0;
            Conditions got = loop.conditions(inputs);
            assertNotNull(got);
            assertNull(got.wind(), "wind");
            assertNull(got.bearing(), "bearing");
            assertNull(got.hour(), "hour");
            assertEquals(HeatingController.sunlightNow(inputs), got.sun(), 0.0, "sun");
        }

        @Test
        void withoutTheWallsTheSunComesFromItsHeightAndTheClouds() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = 5.0;
            inputs.sunElevation = 30.0;
            inputs.cloud = 20.0;
            Conditions got = loop.conditions(inputs);
            assertNotNull(got);
            assertTrue(got.sun() > 0.0, "the sun is up");
            assertEquals(HeatingController.sunlightNow(inputs), got.sun(), 0.0);
        }

        @Test
        void theWallsLightOutranksTheSunsHeight() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = 5.0;
            inputs.sunElevation = 30.0;
            inputs.cloud = 0.0;
            inputs.sunlight = 0.25;
            assertEquals(0.25, loop.conditions(inputs).sun(), 0.0);
        }

        @Test
        void aNegativeLightIsNoLight() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = 5.0;
            inputs.sunlight = -0.3;
            assertEquals(0.0, loop.conditions(inputs).sun(), 0.0);
        }

        @Test
        void aLightThatIsNoNumberFallsBackOnTheSunsHeight() {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = 5.0;
            inputs.sunElevation = 30.0;
            inputs.cloud = 0.0;
            inputs.sunlight = Double.NaN;
            Conditions got = loop.conditions(inputs);
            assertTrue(got.sun() > 0.0);
            assertEquals(HeatingController.sunlightNow(inputs), got.sun(), 0.0);
        }
    }

    /** _night: 22 to 6, the sun down and past its warmth. */
    @Nested
    class TheNight {

        boolean night(Tuning tuning, Double hour, Double elevation, double sinceSun) {
            HeatingController loop = made(tuning);
            loop.state.secondsSinceSun = sinceSun;
            ControlInputs inputs = new ControlInputs(DT);
            inputs.hour = hour;
            inputs.sunElevation = elevation;
            return loop.night(inputs);
        }

        boolean night(Double hour, Double elevation, double sinceSun) {
            return night(new Tuning(), hour, elevation, sinceSun);
        }

        boolean night(Double hour, Double elevation) {
            return night(hour, elevation, 1e6);
        }

        boolean night(Double hour) {
            return night(hour, -20.0);
        }

        /** test_from_ten_to_six_across_midnight. */
        @Test
        void fromTenToSixAcrossMidnight() {
            assertTrue(night(22.5));
            assertTrue(night(3.0));
        }

        /** test_its_edges. */
        @Test
        void itsEdges() {
            assertTrue(night(22.0), "22.0");
            assertFalse(night(21.99), "21.99");
            assertTrue(night(5.99), "5.99");
            assertFalse(night(6.0), "6.0");
        }

        @Test
        void theDayIsNotNight() {
            assertFalse(night(12.0), "noon");
            assertTrue(night(0.0), "midnight");
        }

        /** test_a_window_that_does_not_cross_midnight. */
        @Test
        void aWindowThatDoesNotCrossMidnight() {
            Tuning tuning = new Tuning();
            tuning.nightStartHour = 1.0;
            tuning.nightEndHour = 5.0;
            assertTrue(night(tuning, 3.0, -20.0, 1e6), "3");
            assertFalse(night(tuning, 23.0, -20.0, 1e6), "23");
            assertTrue(night(tuning, 1.0, -20.0, 1e6), "1, the start");
            assertFalse(night(tuning, 5.0, -20.0, 1e6), "5, the end");
            assertFalse(night(tuning, 0.5, -20.0, 1e6), "0.5, before it");
        }

        /** Start and end the same: Python's "else" branch, every hour is in the window. */
        @Test
        void aWindowThatStartsWhereItEndsIsEveryHour() {
            Tuning tuning = new Tuning();
            tuning.nightStartHour = 3.0;
            tuning.nightEndHour = 3.0;
            assertTrue(night(tuning, 12.0, -20.0, 1e6), "12");
            assertTrue(night(tuning, 3.0, -20.0, 1e6), "3");
        }

        /** test_not_with_the_sun_up: a May morning at five. */
        @Test
        void notWithTheSunUp() {
            assertFalse(night(5.0, 2.0));
            assertTrue(night(5.0, -2.0), "the same hour with the sun down");
        }

        @Test
        void theHorizonItselfIsDown() {
            assertTrue(night(23.0, 0.0), "0.0");
            assertFalse(night(23.0, 0.01), "0.01");
        }

        /** test_nor_while_the_house_holds_the_sun. */
        @Test
        void norWhileTheHouseHoldsTheSun() {
            double wait = new Tuning().learnAfterSunSeconds;
            assertFalse(night(22.5, -20.0, wait - 60.0), "a minute short");
            assertTrue(night(22.5, -20.0, wait), "exactly");
        }

        @Test
        void theWaitAfterTheSunIsASetting() {
            Tuning tuning = new Tuning();
            tuning.learnAfterSunSeconds = 600.0;
            assertTrue(night(tuning, 23.0, -20.0, 600.0), "600");
            assertFalse(night(tuning, 23.0, -20.0, 599.0), "599");
        }

        /** test_without_the_clock_or_the_sun_it_is_not. */
        @Test
        void withoutTheClockOrTheSunItIsNot() {
            assertFalse(night(null), "no clock");
            assertFalse(night(22.5, null), "no sun");
            assertTrue(night(22.5), "both");
        }

        /** Python's hour % 24: 24 is midnight, 46 is ten in the evening, 30 is six in the morning. */
        @Test
        void anHourPastTwentyFourGoesRoundTheClock() {
            assertTrue(night(24.0), "24 = 0");
            assertTrue(night(46.0), "46 = 22");
            assertFalse(night(30.0), "30 = 6");
            assertFalse(night(36.0), "36 = 12");
        }

        /**
         * An hour on the window's edge is on it exactly: (hour % 24 + 24) % 24 turns 23.52 into
         * 23.519999999999996 and 0.24 into 0.23999999999999844. Add the 24 only to a negative rest.
         */
        @Test
        void anHourOnTheEdgeStaysOnIt() {
            Tuning late = new Tuning();
            late.nightStartHour = 6.0;
            late.nightEndHour = 23.52;
            assertFalse(night(late, 23.52, -20.0, 1e6), "23.52 is the end");
            assertTrue(night(late, 23.5, -20.0, 1e6), "23.5 is before it");
            Tuning early = new Tuning();
            early.nightStartHour = 0.24;
            early.nightEndHour = 6.0;
            assertTrue(night(early, 0.24, -20.0, 1e6), "0.24 is the start");
            assertFalse(night(early, 0.2, -20.0, 1e6), "0.2 is before it");
        }

        /** Python's % is never negative: -1 is eleven in the evening, -3 nine. Java's % would leave -3. */
        @Test
        void aNegativeHourGoesRoundTheClockToo() {
            assertTrue(night(-1.0), "-1 = 23");
            assertFalse(night(-3.0), "-3 = 21");
            assertFalse(night(-12.0), "-12 = 12");
            Tuning tuning = new Tuning();
            tuning.nightStartHour = 1.0;
            tuning.nightEndHour = 5.0;
            assertTrue(night(tuning, -21.0, -20.0, 1e6), "-21 = 3");
        }
    }

    /** _distrust: why the house model may not decide yet, "" when it may. */
    @Nested
    class TheDistrust {

        String distrust(HouseModel house, double minHours) {
            Tuning tuning = new Tuning();
            tuning.coastMinHours = minHours;
            return made(house, tuning).distrust();
        }

        String distrust(HouseModel house) {
            return distrust(house, 48.0);
        }

        @Test
        void aNewHouseHasNoHoursYet() {
            assertEquals("0 av 48 h data", distrust(new HouseModel()));
        }

        /** taught_house(hours=24): 23.83 hours. */
        @Test
        void aDayIsHalfOfTwo() {
            assertEquals("24 av 48 h data", distrust(Houses.day()));
        }

        /** Python's .0f rounds a half to the even number, String.format's %.0f rounds it up. */
        @Test
        void halvesAreRoundedToTheEvenNumber() {
            HouseModel twelve = Houses.watchedFor(12.5);
            HouseModel thirteen = Houses.watchedFor(13.5);
            assertEquals(12.5, twelve.observedHours(), 0.0, "the house has watched exactly 12.5 hours");
            assertEquals(13.5, thirteen.observedHours(), 0.0, "and this one 13.5");
            assertEquals("12 av 48 h data", distrust(twelve));
            assertEquals("14 av 48 h data", distrust(thirteen));
        }

        @Test
        void theHoursNeededAreRoundedTheSameWay() {
            assertEquals("0 av 48 h data", distrust(new HouseModel(), 47.5));
            assertEquals("0 av 48 h data", distrust(new HouseModel(), 48.5));
            assertEquals("0 av 46 h data", distrust(new HouseModel(), 46.5));
            assertEquals("0 av 47 h data", distrust(new HouseModel(), 46.6));
        }

        /** taught_house(): 25 days of a known building. */
        @Test
        void aLearnedHouseIsTrusted() {
            assertEquals("", distrust(Houses.learned()));
        }

        @Test
        void theHoursNeededAreASetting() {
            assertEquals("600 av 1000 h data", distrust(Houses.learned(), 1000.0));
            assertEquals("24 av 600 h data", distrust(Houses.day(), 600.0));
        }

        /** Exactly the hours it needs is enough. */
        @Test
        void exactlyTheHoursNeededIsEnough() {
            HouseModel learned = Houses.learned();
            assertEquals("", distrust(learned, learned.observedHours()));
            assertEquals("12 av 13 h data", distrust(Houses.watchedFor(12.5), 12.5 + 1e-9));
        }

        /** No hours needed, and a model that cannot say yet whether the house cools. */
        @Test
        void enoughHoursButNoFitIsTooLittleData() {
            assertEquals("för lite data", distrust(new HouseModel(), 0.0));
        }

        /** A house that warms itself when left alone has not been seen in enough weather. */
        @Test
        void aHouseThatWarmsItselfIsNotTrusted() {
            HouseModel stove = Houses.stove();
            assertEquals(Boolean.FALSE, stove.coolsWhenLeftAlone(), "the stove warms itself");
            assertEquals("för lite varierad data för att lita på modellen än", distrust(stove));
        }

        /** It cools, but the loss through the walls is the floor's, not the data's. */
        @Test
        void aLossOnTheFloorIsNotTrustedEither() {
            HouseModel thin = Houses.thinWalls();
            assertEquals(Boolean.TRUE, thin.coolsWhenLeftAlone(), "it cools");
            assertTrue(thin.lossOnFloor(), "on the floor");
            assertEquals("för lite varierad data för att lita på modellen än", distrust(thin));
        }

        /** The hours come first: a stove watched too briefly is told how many hours it has. */
        @Test
        void theHoursAreAskedFirst() {
            assertEquals("480 av 500 h data", distrust(Houses.stove(), 500.0));
        }
    }

    /** _observe_house: the house model sees every cycle, and says so first. */
    @Nested
    class TheHouseWatcher {
        final HeatingController loop = made();

        ControlInputs cycle(Double outdoor) {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = outdoor;
            inputs.hour = 12.0;
            inputs.sunElevation = 20.0;
            return inputs;
        }

        LearnerStatus watch(ControlInputs inputs, Double indoor, Double delta, double dt) {
            loop.out = new ControlOutput();
            loop.observeHouse(inputs, indoor, delta, dt);
            return loop.out.learning.get(LEARN_HOUSE);
        }

        /** test_outside_heating_it_says_the_mode: "the house model watches in every mode". */
        @Test
        void withAReadingAndTheWeatherItWatches() {
            assertEquals(new LearnerStatus(true, "", 0.0), watch(cycle(-5.0), 21.0, 4.0, 600.0));
        }

        /** test_without_indoor_the_house_model_says_so. */
        @Test
        void withoutIndoorTheHouseModelSaysSo() {
            assertEquals(new LearnerStatus(false, "no_indoor", null), watch(cycle(-5.0), null, 4.0, 600.0));
        }

        @Test
        void withoutOutdoorItSaysThat() {
            assertEquals(new LearnerStatus(false, "no_outdoor", null), watch(cycle(null), 21.0, 4.0, 600.0));
        }

        @Test
        void withNeitherTheIndoorIsSaid() {
            assertEquals(new LearnerStatus(false, "no_indoor", null), watch(cycle(null), null, 4.0, 600.0));
        }

        /** The verdict comes before the cycle is shown: the hours it had, not the hours it has. */
        @Test
        void theNumberIsTheHoursObservedBeforeTheCycle() {
            List<Double> said = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                said.add(watch(cycle(-5.0), 21.0, 4.0, 600.0).number());
            }
            assertEquals(0.0, said.get(0), 0.0, "first: nothing yet");
            assertEquals(0.0, said.get(1), 0.0, "second: the first cycle only took the reading");
            assertEquals(600.0 / HOUR, said.get(2), 1e-12, "third: the second cycle's sample");
            assertEquals(2 * 600.0 / HOUR, loop.house.observedHours(), 1e-12, "and the third's");
        }

        @Test
        void theHouseIsShownTheCycle() {
            watch(cycle(-5.0), 21.0, 4.0, 600.0);
            assertEquals(21.0, loop.house.lastIndoor(), "the reading");
            watch(cycle(-5.0), 21.4, 4.0, 600.0);
            assertEquals(21.4, loop.house.lastIndoor(), "the next");
            assertEquals(600.0 / HOUR, loop.house.observedHours(), 1e-12, "a sample of ten minutes");
        }

        /** Python shows the house the cycle without a reading too, and the model forgets its last one. */
        @Test
        void itIsShownACycleWithoutAReadingToo() {
            watch(cycle(-5.0), 21.0, 4.0, 300.0);
            assertEquals(21.0, loop.house.lastIndoor());
            assertEquals(300.0, loop.house.secondsSinceSample(), 0.0);
            watch(cycle(-5.0), null, 4.0, 300.0);
            assertNull(loop.house.lastIndoor(), "forgotten");
            assertEquals(0.0, loop.house.secondsSinceSample(), 0.0);
        }

        /** The house samples on Tuning.thermal_sample_seconds, not on the cycle. */
        @Test
        void itSamplesOnTheTuningsClock() {
            Tuning tuning = new Tuning();
            tuning.thermalSampleSeconds = 1800.0;
            HeatingController slow = made(tuning);
            for (int i = 0; i < 4; i++) {
                slow.out = new ControlOutput();
                slow.observeHouse(cycle(-5.0), 21.0, 4.0, 600.0);
            }
            assertEquals(0.5, slow.house.observedHours(), 1e-12, "one sample of half an hour");
            assertEquals(600.0, slow.house.secondsSinceSample(), 0.0, "and ten minutes towards the next");
        }

        /** delta or 0.0: a cycle without a delta heats with nothing. Window[1] is the heat times the seconds. */
        @Test
        void aMissingDeltaIsNoHeat() {
            watch(cycle(-5.0), 21.0, null, DT);
            assertEquals(0.0, loop.house.window()[1], 0.0, "no delta");
            HeatingController other = made();
            other.out = new ControlOutput();
            other.observeHouse(cycle(-5.0), 21.0, 5.0, DT);
            assertEquals(5.0 * DT, other.house.window()[1], 1e-9, "a delta of five");
        }

        /** radiator_excess(flow, ret, indoor) is what the radiators gave, when both are read. */
        @Test
        void theRadiatorsAreReadFromTheSupplyAndTheReturn() {
            ControlInputs inputs = cycle(-5.0);
            inputs.flow = 50.0;
            inputs.ret = 40.0;
            watch(inputs, 21.0, 5.0, DT);
            assertEquals(Math.pow(24.0, 1.3) * DT, loop.house.window()[1], 1e-9, "the radiators, not the delta");

            HeatingController noReturn = made();
            ControlInputs half = cycle(-5.0);
            half.flow = 50.0;
            noReturn.out = new ControlOutput();
            noReturn.observeHouse(half, 21.0, 5.0, DT);
            assertEquals(5.0 * DT, noReturn.house.window()[1], 1e-9, "without the return, the delta");
        }

        /** The model is given _conditions: the difference, the wind and the sun on the walls. */
        @Test
        void itIsGivenTheWeather() {
            ControlInputs inputs = cycle(1.0);
            inputs.wind = 4.0;
            inputs.windBearing = 0.0;
            inputs.sunlight = 0.5;
            watch(inputs, 21.0, 0.0, DT);
            double[] window = loop.house.window();
            assertEquals(DT, window[0], 1e-9, "seconds");
            assertEquals(0.5 * DT, window[2], 1e-9, "sun");
            assertEquals(20.0 * DT, window[3], 1e-9, "difference");
            assertEquals(4.0 * 20.0 * DT, window[4], 1e-9, "wind");
            assertEquals(4.0 * 20.0 * DT, window[5], 1e-9, "wind from the north");
        }

        /** _night goes with the cycle: at night the model starts a stretch of it. */
        @Test
        void itIsToldTheNight() {
            HeatingController dark = made();
            dark.state.secondsSinceSun = 1e6;
            ControlInputs night = cycle(-5.0);
            night.hour = 23.0;
            night.sunElevation = -10.0;
            dark.out = new ControlOutput();
            dark.observeHouse(night, 21.0, 4.0, DT);
            assertEquals(DT, dark.house.stretch()[0], 1e-9, "a night begun");

            loop.state.secondsSinceSun = 1e6;
            watch(cycle(-5.0), 21.0, 4.0, DT);
            assertEquals(0.0, loop.house.stretch()[0], 0.0, "noon is no night");
            assertNotNull(loop.out.learning.get(LEARN_HOUSE));
        }
    }

    /** _learning_hold: a setback that may start waits for two more hours in this cell of the table. */
    @Nested
    class TheLearningHold {

        /** TestLearningHold.held: two hours' wait, given up after six. */
        HeatingController held(Double outdoor) {
            Tuning tuning = new Tuning();
            tuning.coastLearningHoldSeconds = 2 * HOUR;
            tuning.coastLearningHoldMaxSeconds = 6 * HOUR;
            HeatingController loop = made(tuning);
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), outdoor);
            return loop;
        }

        HeatingController held() {
            return held(8.5);
        }

        /** TestLearningHold.gain. */
        void gain(HeatingController loop, double hours, double outdoor, double hour) {
            loop.points.add(outdoor, 10.0, 0.0, hour, hours);
        }

        void gain(HeatingController loop, double hours) {
            gain(loop, hours, 8.5, 2.0);
        }

        ControlInputs at(Double hour, double dt) {
            ControlInputs inputs = new ControlInputs(dt);
            inputs.hour = hour;
            return inputs;
        }

        ControlInputs at(Double hour) {
            return at(hour, DT);
        }

        boolean hold(HeatingController loop, ControlInputs inputs, ControlOutput out) {
            loop.out = out;
            return loop.learningHold(inputs, out);
        }

        /** test_with_no_measurements_here_the_setback_waits. */
        @Test
        void withNoMeasurementsHereTheSetbackWaits() {
            HeatingController loop = held();
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(3.0), out));
            assertEquals(List.of("learning_hold"), out.flags);
            assertEquals(2 * HOUR, out.coastLearningHold, 0.0, "what is left");
            assertEquals(DT, loop.state.secondsLearningHold, 0.0, "the wait counted");
            assertEquals("4:0", loop.state.learningHoldCell, "8.5 is bin 4, three o'clock the night's part");
            assertEquals(0.0, loop.state.learningHoldBase, 0.0, "from the cell's hours");
        }

        @Test
        void withoutAnOutdoorTemperatureNothingIsHeld() {
            HeatingController loop = held(null);
            ControlOutput out = new ControlOutput();
            assertFalse(hold(loop, at(3.0), out));
            assertEquals(List.of(), out.flags);
            assertNull(out.coastLearningHold);
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0);
            assertNull(loop.state.learningHoldCell);
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), 8.5);
            assertTrue(hold(loop, at(3.0), new ControlOutput()), "and with it");
        }

        /** test_what_is_left_counts_down_with_the_tables_hours. */
        @Test
        void whatIsLeftCountsDownWithTheTablesHours() {
            HeatingController loop = held();
            hold(loop, at(3.0), new ControlOutput());
            gain(loop, 1.5);
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(3.0), out));
            assertEquals(0.5 * HOUR, out.coastLearningHold, 1e-9);
            assertEquals(2 * DT, loop.state.secondsLearningHold, 0.0);
        }

        /** test_once_the_cell_has_two_more_hours_it_may_start. */
        @Test
        void onceTheCellHasTwoMoreHoursItMayStart() {
            HeatingController loop = held();
            assertTrue(hold(loop, at(3.0), new ControlOutput()));
            gain(loop, 2.0);
            ControlOutput out = new ControlOutput();
            assertFalse(hold(loop, at(3.0), out));
            assertEquals(List.of(), out.flags);
            assertNull(out.coastLearningHold);
            assertEquals(DT, loop.state.secondsLearningHold, 0.0, "the wait stops counting");
        }

        /** left <= 0 is no wait, and the cell is still the one it looked at. */
        @Test
        void nothingLeftIsNoWait() {
            Tuning tuning = new Tuning();
            tuning.coastLearningHoldSeconds = 0.0;
            HeatingController loop = made(tuning);
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), 8.5);
            gain(loop, 1.0);
            ControlOutput out = new ControlOutput();
            assertFalse(hold(loop, at(3.0), out));
            assertEquals(List.of(), out.flags);
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0);
            assertEquals("4:0", loop.state.learningHoldCell, "a new cell is taken before what is left is worked out");
            assertEquals(1.0, loop.state.learningHoldBase, 1e-12);
        }

        /** test_hours_from_before_the_hold_do_not_count_towards_it. */
        @Test
        void hoursFromBeforeTheHoldDoNotCountTowardsIt() {
            HeatingController loop = held();
            gain(loop, 1.0);
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(3.0), out));
            assertEquals(2 * HOUR, out.coastLearningHold, 1e-9);
            assertEquals(1.0, loop.state.learningHoldBase, 1e-12);
        }

        /** test_in_another_cell_it_counts_from_that_cells_hours. */
        @Test
        void inAnotherCellItCountsFromThatCellsHours() {
            HeatingController loop = held();
            hold(loop, at(3.0), new ControlOutput());
            gain(loop, 1.5);
            gain(loop, 1.0, 8.5, 8.0);
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(9.0), out));
            assertEquals(2 * HOUR, out.coastLearningHold, 1e-9);
            assertEquals("4:1", loop.state.learningHoldCell);
            assertEquals(1.0, loop.state.learningHoldBase, 1e-12);
        }

        /** Another bin is another cell too. */
        @Test
        void anotherOutdoorTemperatureIsAnotherCell() {
            HeatingController loop = held();
            hold(loop, at(3.0), new ControlOutput());
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), -3.0);
            assertTrue(hold(loop, at(23.0), new ControlOutput()));
            assertEquals("-2:3", loop.state.learningHoldCell, "-3 is bin -2, eleven at night the last part");
        }

        /** The same cell keeps the count it started from. */
        @Test
        void theSameCellKeepsItsBase() {
            HeatingController loop = held();
            gain(loop, 1.0);
            hold(loop, at(3.0), new ControlOutput());
            gain(loop, 0.5);
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(4.0), out));
            assertEquals(1.0, loop.state.learningHoldBase, 1e-12, "still the hours from before");
            assertEquals(1.5 * HOUR, out.coastLearningHold, 1e-9);
        }

        /** test_the_start_of_the_count_survives_a_restart: the cell and its base come from the state. */
        @Test
        void theStartOfTheCountSurvivesARestart() {
            HeatingController loop = held();
            loop.state.learningHoldCell = "4:0";
            loop.state.learningHoldBase = 1.0;
            gain(loop, 2.5);
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(3.0), out));
            assertEquals(0.5 * HOUR, out.coastLearningHold, 1e-9, "1.5 of the 2 hours since the restart's base");
            assertEquals(1.0, loop.state.learningHoldBase, 1e-12);
        }

        /** test_where_the_table_has_its_hours_there_is_no_wait, and test_a_few_hours_there_are_not_enough. */
        @Test
        void whereTheTableHasItsHoursThereIsNoWait() {
            HeatingController full = held();
            gain(full, 6.0);
            ControlOutput out = new ControlOutput();
            assertFalse(hold(full, at(3.0), out));
            assertEquals(List.of(), out.flags);
            assertNull(full.state.learningHoldCell, "nothing to start counting");

            HeatingController few = held();
            gain(few, 5.9);
            assertTrue(hold(few, at(3.0), new ControlOutput()), "5.9 hours are not enough");
        }

        @Test
        void theCellsHoursAreASetting() {
            HeatingController loop = held();
            loop.tuning.coastLearningCellHours = 1.0;
            gain(loop, 1.0);
            assertFalse(hold(loop, at(3.0), new ControlOutput()));
            loop.tuning.coastLearningCellHours = 1.5;
            assertTrue(hold(loop, at(3.0), new ControlOutput()));
        }

        /** test_in_the_afternoon_the_afternoons_hours_are_what_count. */
        @Test
        void inTheAfternoonTheAfternoonsHoursAreWhatCount() {
            HeatingController loop = held();
            gain(loop, 6.0, 8.5, 14.0);
            assertFalse(hold(loop, at(15.0), new ControlOutput()), "15");
            assertTrue(hold(loop, at(3.0), new ControlOutput()), "3 is another part");
        }

        /** test_hours_at_another_time_of_day_do_not_count. */
        @Test
        void hoursAtAnotherTimeOfDayDoNotCount() {
            HeatingController loop = held();
            gain(loop, 24.0, 8.5, 15.0);
            assertTrue(hold(loop, at(3.0), new ControlOutput()));
        }

        /** test_nor_hours_at_another_outdoor_temperature. */
        @Test
        void norHoursAtAnotherOutdoorTemperature() {
            HeatingController loop = held();
            gain(loop, 24.0, 2.5, 3.0);
            assertTrue(hold(loop, at(3.0), new ControlOutput()));
        }

        /** Without a clock the hours are the night's part, as the table keeps them. */
        @Test
        void withoutAClockItIsTheFirstPart() {
            HeatingController loop = held();
            gain(loop, 6.0, 8.5, 2.0);
            assertFalse(hold(loop, at(null), new ControlOutput()));
            HeatingController empty = held();
            assertTrue(hold(empty, at(null), new ControlOutput()));
            assertEquals("4:0", empty.state.learningHoldCell);
        }

        /** test_the_wait_is_counted_and_given_up_after_six_hours. */
        @Test
        void theWaitIsCountedAndGivenUpAfterSixHours() {
            HeatingController loop = held();
            hold(loop, at(3.0), new ControlOutput());
            hold(loop, at(3.0), new ControlOutput());
            assertEquals(60.0, loop.state.secondsLearningHold, 0.0);
            loop.state.secondsLearningHold = 6 * HOUR;
            ControlOutput out = new ControlOutput();
            assertFalse(hold(loop, at(3.0), out), "exactly six hours");
            assertEquals(List.of(), out.flags);
            assertEquals(6 * HOUR, loop.state.secondsLearningHold, 0.0, "not counted on");
            loop.state.secondsLearningHold = 6 * HOUR - 1.0;
            assertTrue(hold(loop, at(3.0), new ControlOutput()), "a second short");
        }

        @Test
        void theLongestWaitIsASetting() {
            HeatingController loop = held();
            loop.tuning.coastLearningHoldMaxSeconds = 600.0;
            loop.state.secondsLearningHold = 600.0;
            assertFalse(hold(loop, at(3.0), new ControlOutput()));
            loop.state.secondsLearningHold = 570.0;
            assertTrue(hold(loop, at(3.0), new ControlOutput()));
            assertEquals(600.0, loop.state.secondsLearningHold, 0.0);
        }

        /** max(0, dt): a clock that went back counts nothing, but the wait still holds. */
        @Test
        void aNegativeCycleCountsNothing() {
            HeatingController loop = held();
            ControlOutput out = new ControlOutput();
            assertTrue(hold(loop, at(3.0, -30.0), out));
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0);
            assertEquals(2 * HOUR, out.coastLearningHold, 0.0);
        }

        @Test
        void itCountsTheCyclesLength() {
            HeatingController loop = held();
            hold(loop, at(3.0, 300.0), new ControlOutput());
            assertEquals(300.0, loop.state.secondsLearningHold, 0.0);
        }

        @Test
        void itsFlagComesAfterThoseAlreadySaid() {
            HeatingController loop = held();
            ControlOutput out = new ControlOutput();
            out.flags.add("earlier");
            hold(loop, at(3.0), out);
            assertEquals(List.of("earlier", "learning_hold"), out.flags);
        }
    }

    /**
     * _coast: one prediction read at two horizons. Called directly with the
     * mode the cycle would otherwise have; the learning hold is off unless a
     * test turns it on.
     */
    @Nested
    class TheCoast {

        String coast(HeatingController loop, double indoor, List<Forecast> forecast, String mode, ControlOutput out) {
            loop.out = out;
            return loop.coast(ahead(forecast), indoor, mode, out);
        }

        /** The hold H the learned house predicts at 21.8 before eight degrees that do not turn. */
        double held(Tuning tuning) {
            ControlOutput out = new ControlOutput();
            coast(made(Houses.learned(), tuning), 21.8, flat(8.0), MODE_WARM, out);
            assertTrue(out.coastKnown, "the learned house knows");
            assertNotNull(out.coastHours, "and does not hold the whole forecast");
            return out.coastHours;
        }

        /** Python's fixed_hold: a hold that bridges whatever the weather, which bridge_only=False gives. */
        Tuning bridging() {
            Tuning tuning = coastTuning();
            tuning.coastBridgeOnly = false;
            return tuning;
        }

        // --- TestTheDecision -----------------------------------------------------------------

        /** test_a_warm_house_before_a_fine_day_coasts. */
        @Test
        void aWarmHouseBeforeAFineDayCoasts() {
            HeatingController loop = made(Houses.learned());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_COAST, coast(loop, 21.8, turning(), MODE_HEATING, out));
            assertTrue(out.coastShunt);
            assertEquals(List.of("coast"), out.flags);
            assertTrue(loop.state.coasting);
        }

        /** test_a_mild_night_that_does_not_turn_does_not. */
        @Test
        void aMildNightThatDoesNotTurnDoesNot() {
            HeatingController loop = made(Houses.learned());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 21.8, flat(8.0), MODE_HEATING, out));
            assertTrue(out.coastKnown, "it was worked out");
            assertFalse(out.coastShunt);
            assertNull(out.coastTurnHours);
            assertFalse(loop.state.coasting);
            assertEquals(List.of(), out.flags);
        }

        /** test_switched_back_to_the_old_rule_it_does. */
        @Test
        void switchedBackToTheOldRuleItDoes() {
            HeatingController loop = made(Houses.learned(), bridging());
            assertEquals(MODE_COAST, coast(loop, 21.8, flat(8.0), MODE_HEATING, new ControlOutput()));
        }

        /** test_it_says_when_the_weather_turns. */
        @Test
        void itSaysWhenTheWeatherTurns() {
            ControlOutput out = new ControlOutput();
            coast(made(Houses.learned()), 21.8, turning(), MODE_HEATING, out);
            assertNotNull(out.coastTurnHours);
            assertEquals(2.0, out.coastTurnHours, 0.5);
        }

        /** test_a_cold_night_does_not. */
        @Test
        void aColdNightDoesNot() {
            Tuning tuning = coastTuning();
            tuning.coastComfortBand = 0.0;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(made(Houses.learned(), tuning), 21.05, flat(-20.0), MODE_HEATING, out));
            assertTrue(out.coastKnown);
            assertFalse(out.coastShunt);
        }

        /** test_a_comfort_margin_is_what_it_may_spend. */
        @Test
        void aComfortMarginIsWhatItMaySpend() {
            Tuning tight = coastTuning();
            tight.coastComfortBand = 0.0;
            Tuning loose = coastTuning();
            loose.coastComfortBand = 1.0;
            ControlOutput tightOut = new ControlOutput();
            ControlOutput looseOut = new ControlOutput();
            coast(made(Houses.learned(), tight), 21.05, flat(2.0), MODE_HEATING, tightOut);
            coast(made(Houses.learned(), loose), 21.05, flat(2.0), MODE_HEATING, looseOut);
            assertTrue(looseOut.coastMargin >= 1.0, "margin " + looseOut.coastMargin);
            assertNotNull(tightOut.coastHours);
            assertTrue((looseOut.coastHours == null ? 99.0 : looseOut.coastHours) > tightOut.coastHours);
        }

        /** test_without_a_forecast_it_will_not_guess: and a coast under way ends. */
        @Test
        void withoutAForecastItWillNotGuess() {
            HeatingController loop = made(Houses.learned());
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 21.8, List.of(), MODE_HEATING, out));
            assertFalse(loop.state.coasting);
            assertFalse(out.coastKnown);
            assertEquals("", out.coastTrust, "not even the trust is asked");
            assertNull(out.coastHours);
            assertEquals(List.of(), out.flags);
        }

        @Test
        void noForecastAtAllIsNoneEither() {
            HeatingController loop = made(Houses.learned());
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_WARM, loop.coast(ahead(null), 23.0, MODE_WARM, out));
            assertFalse(loop.state.coasting);
            assertFalse(out.coastKnown);
        }

        /** Only the hours up to coast_horizon_hours are read; with none left it returns as it came. */
        @Test
        void aForecastOnlyBeyondTheHorizonChangesNothing() {
            HeatingController loop = made(Houses.learned());
            loop.state.coasting = true;
            List<Forecast> late = List.of(new Forecast(13.0, new Conditions(8.0, 0.0, 0.0, 0.0)),
                    new Forecast(14.0, new Conditions(8.0, 0.0, 0.0, 0.0)));
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 21.8, late, MODE_HEATING, out));
            assertTrue(loop.state.coasting, "not even the coast under way is ended");
            assertEquals("", out.coastTrust);
            assertFalse(out.coastKnown);
            assertNull(out.coastShuntThreshold);
            assertEquals(List.of(), out.flags);
        }

        /** The horizon is a setting, and the slot on it is read. */
        @Test
        void theHorizonIsASetting() {
            Tuning tuning = coastTuning();
            tuning.coastHorizonHours = 1.0;
            HeatingController loop = made(Houses.learned(), tuning);
            List<Forecast> weather = turning();
            ControlOutput out = new ControlOutput();
            coast(loop, 21.8, weather, MODE_WARM, out);
            Outlook expected = Houses.learned().outlook(21.8, 21.0 - out.coastMargin, weather.subList(0, 2),
                    tuning.coastConfidence);
            assertEquals(expected.known(), out.coastKnown);
            assertClose(expected.hours(), out.coastHours, 1e-12, "hours on two slots");
            assertClose(expected.turnsAt(), out.coastTurnHours, 1e-12, "the turn at 2 is past the horizon");
        }

        /** test_it_can_be_switched_off_entirely. */
        @Test
        void itCanBeSwitchedOffEntirely() {
            Tuning tuning = coastTuning();
            tuning.coastEnabled = false;
            HeatingController loop = made(Houses.learned(), tuning);
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 21.8, turning(), MODE_HEATING, out));
            assertFalse(loop.state.coasting);
            assertFalse(out.coastKnown);
            assertFalse(out.coastShunt);
        }

        @Test
        void withoutAnIndoorReadingItWillNotGuess() {
            HeatingController loop = made(Houses.learned());
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, loop.coast(ahead(turning()), null, MODE_HEATING, out));
            assertFalse(loop.state.coasting);
            assertFalse(out.coastKnown);
            assertEquals("", out.coastTrust);
        }

        /** test_an_untaught_model_never_coasts: it says why, and the budget is the whole band. */
        @Test
        void anUntaughtModelNeverCoasts() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 0.7;
            tuning.coastComfortBand = 0.4;
            HeatingController loop = made(new HouseModel(), tuning);
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 21.8, flat(8.0), MODE_HEATING, out));
            assertEquals("0 av 48 h data", out.coastTrust);
            assertEquals(0.7, out.coastAllowance, 0.0, "the whole learning band");
            assertEquals(0.7, out.coastMargin, 0.0, "the larger of the two");
            assertFalse(out.coastKnown);
            assertNull(out.coastHours);
            assertNull(out.coastShuntThreshold, "nothing predicted");
            assertFalse(loop.state.coasting);
        }

        @Test
        void anUntaughtModelsMarginIsTheComfortBandWhenThatIsLarger() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 0.7;
            tuning.coastComfortBand = 1.2;
            ControlOutput out = new ControlOutput();
            coast(made(new HouseModel(), tuning), 21.8, flat(8.0), MODE_HEATING, out);
            assertEquals(1.2, out.coastMargin, 0.0);
            assertEquals(0.7, out.coastAllowance, 0.0);
        }

        @Test
        void aDayOfDataIsTooLittleAndSaysHowLittle() {
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(made(Houses.day()), 21.8, turning(), MODE_HEATING, out));
            assertEquals("24 av 48 h data", out.coastTrust);
        }

        /** A learned house's trust reads "": nothing in the way. */
        @Test
        void aLearnedHouseIsTrusted() {
            HeatingController loop = made(Houses.learned());
            ControlOutput out = new ControlOutput();
            out.coastTrust = "stale";
            coast(loop, 21.8, turning(), MODE_HEATING, out);
            assertEquals("", out.coastTrust);
        }

        // --- the house's own answers ----------------------------------------------------------

        /** The uncertainty, the reach and the hold are the house's, on the first slot and the floor. */
        @Test
        void itAsksTheHouse() {
            HouseModel house = Houses.learned();
            Tuning tuning = coastTuning();
            tuning.coastComfortBand = 0.6;
            tuning.coastConfidence = 1.5;
            List<Forecast> weather = turning();
            ControlOutput out = new ControlOutput();
            coast(made(house, tuning), 21.8, weather, MODE_HEATING, out);
            Conditions first = weather.get(0).conditions();
            assertClose(house.driftUncertainty(21.8, first), out.coastUncertainty, 1e-12, "uncertainty");
            assertClose(house.reach(21.8, first), out.coastReach, 1e-12, "reach");
            double margin = Math.max(0.6, out.coastAllowance);
            assertEquals(margin, out.coastMargin, 1e-12, "margin");
            Outlook outlook = house.outlook(21.8, 21.0 - margin, weather, 1.5);
            assertEquals(outlook.known(), out.coastKnown, "known");
            assertClose(outlook.hours(), out.coastHours, 1e-12, "hours");
            assertClose(outlook.turnsAt(), out.coastTurnHours, 1e-12, "turns at");
        }

        /** share = min(1, max(0, reach) / coast_unknown_reach), and the budget the band times it. */
        @Test
        void theBudgetIsTheBandTimesTheShareOfTheReach() {
            for (double unknown : new double[] {1.0, 0.5, 0.01}) {
                Tuning tuning = coastTuning();
                tuning.coastLearningBand = 0.8;
                tuning.coastUnknownReach = unknown;
                ControlOutput out = new ControlOutput();
                coast(made(Houses.learned(), tuning), 21.8, flat(-30.0, 20.0, 0.0), MODE_HEATING, out);
                assertNotNull(out.coastReach, "unknown " + unknown);
                double share = Math.min(1.0, Math.max(0.0, out.coastReach) / unknown);
                assertEquals(0.8 * share, out.coastAllowance, 1e-12, "unknown " + unknown);
            }
        }

        @Test
        void noUnknownReachIsNoBudget() {
            Tuning tuning = coastTuning();
            tuning.coastUnknownReach = 0.0;
            tuning.coastComfortBand = 0.3;
            ControlOutput out = new ControlOutput();
            coast(made(Houses.learned(), tuning), 21.8, flat(-30.0, 20.0, 0.0), MODE_HEATING, out);
            assertNotNull(out.coastReach);
            assertTrue(out.coastReach > 0.0, "strange weather");
            assertEquals(0.0, out.coastAllowance, 0.0);
            assertEquals(0.3, out.coastMargin, 0.0, "the comfort band alone");
        }

        // --- TestTheTwoHorizons -------------------------------------------------------------

        /** test_a_short_hold_closes_the_shunt_but_keeps_the_burner: past the shunt's threshold, short of the burner's. */
        @Test
        void aShortHoldClosesTheShuntButKeepsTheBurner() {
            double hold = held(bridging());
            HeatingController loop = made(Houses.learned(), bridging());
            loop.burnerLeadSeconds(hold * HOUR);
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_COAST, coast(loop, 21.8, flat(8.0), MODE_HEATING, out));
            assertTrue(out.coastShunt);
            assertFalse(out.coastBurner);
        }

        /** test_a_long_hold_stops_the_burner_too. */
        @Test
        void aLongHoldStopsTheBurnerToo() {
            ControlOutput out = new ControlOutput();
            coast(made(Houses.learned()), 21.5, flat(14.0, 0.0, 0.6), MODE_HEATING, out);
            assertTrue(out.coastBurner);
        }

        /** test_a_slow_burner_is_stopped_more_reluctantly_than_a_quick_one: a minute either side of H. */
        @Test
        void aSlowBurnerIsStoppedMoreReluctantlyThanAQuickOne() {
            double hold = held(bridging());
            double others = 1800.0 + 900.0;
            HeatingController slow = made(Houses.learned(), bridging());
            slow.burnerLeadSeconds(hold * HOUR - others + 60.0);
            HeatingController quick = made(Houses.learned(), bridging());
            quick.burnerLeadSeconds(hold * HOUR - others - 60.0);
            ControlOutput slowOut = new ControlOutput();
            ControlOutput quickOut = new ControlOutput();
            coast(slow, 21.8, flat(8.0), MODE_HEATING, slowOut);
            coast(quick, 21.8, flat(8.0), MODE_HEATING, quickOut);
            assertFalse(slowOut.coastBurner, "slow");
            assertTrue(quickOut.coastBurner, "quick");
        }

        /** The thresholds come from the settings alone, the margin only before a coast has begun. */
        @Test
        void theThresholdsAreTheLeadsAndTheMargin() {
            Tuning tuning = coastTuning();
            tuning.shuntLeadSeconds = 700.0;
            tuning.coastHysteresisSeconds = 500.0;
            tuning.heatSourceOffDelay = 1300.0;
            HeatingController loop = made(Houses.learned(), tuning);
            loop.burnerLeadSeconds(400.0);
            ControlOutput before = new ControlOutput();
            coast(loop, 21.8, flat(8.0), MODE_WARM, before);
            assertEquals(1200.0 / HOUR, before.coastShuntThreshold, 1e-12, "shunt, before");
            assertEquals(2200.0 / HOUR, before.coastBurnerThreshold, 1e-12, "burner, before");

            loop.state.coasting = true;
            ControlOutput during = new ControlOutput();
            coast(loop, 21.8, flat(8.0), MODE_HEATING, during);
            assertEquals(700.0 / HOUR, during.coastShuntThreshold, 1e-12, "shunt, during");
            assertEquals(1700.0 / HOUR, during.coastBurnerThreshold, 1e-12, "burner, during");
        }

        // --- TestItReportsInEveryMode -----------------------------------------------------------

        /** test_the_budget_is_worked_out_during_a_warm_stop. */
        @Test
        void theBudgetIsWorkedOutDuringAWarmStop() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 1.0;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_WARM, coast(made(Houses.threeNoisyDays(), tuning), 23.5, flat(8.0), MODE_WARM, out));
            assertTrue(out.coastAllowance > 0.0);
            assertNotNull(out.coastUncertainty);
        }

        /** test_and_the_holding_time_as_well. */
        @Test
        void andTheHoldingTimeAsWell() {
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_WARM, coast(made(Houses.learned()), 23.5, flat(8.0), MODE_WARM, out));
            assertTrue(out.coastKnown);
            assertNotNull(out.coastHours);
        }

        /** test_but_it_does_not_act_from_a_stop, test_nor_while_the_thermostat_is_off: worked out, not acted on. */
        @Test
        void butItActsOnlyFromHeating() {
            for (String mode : List.of(MODE_WARM, MODE_OFF, MODE_SUMMER, MODE_HOLD, MODE_FAILSAFE, MODE_COAST)) {
                HeatingController loop = made(Houses.learned());
                loop.state.coasting = true;
                ControlOutput out = new ControlOutput();
                assertEquals(mode, coast(loop, 21.8, turning(), mode, out), mode);
                assertTrue(out.coastKnown, mode + ": still reported");
                assertTrue(out.coastShunt, mode + ": the shunt might");
                assertFalse(loop.state.coasting, mode + ": a coast under way ends");
                assertEquals(List.of(), out.flags, mode);
            }
        }

        /** test_an_untaught_model_still_reports_its_budget. */
        @Test
        void anUntaughtModelStillReportsItsBudget() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 1.0;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_WARM, coast(made(new HouseModel(), tuning), 23.5, flat(8.0), MODE_WARM, out));
            assertFalse(out.coastKnown);
            assertEquals(1.0, out.coastAllowance, 0.0);
        }

        // --- TestOnlyFromTarget --------------------------------------------------------------

        /** test_a_house_below_target_starts_no_coast. */
        @Test
        void aHouseBelowTargetStartsNoCoast() {
            HeatingController loop = made(Houses.learned(), bridging());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 20.9, flat(8.0), MODE_HEATING, out));
            assertTrue(out.coastShunt, "the shunt might");
            assertFalse(loop.state.coasting);
        }

        /** test_on_target_it_does. */
        @Test
        void onTargetItDoes() {
            HeatingController loop = made(Houses.learned(), bridging());
            assertEquals(MODE_COAST, coast(loop, 21.0, flat(8.0), MODE_HEATING, new ControlOutput()));
            assertTrue(loop.state.coasting);
        }

        /** test_once_begun_it_carries_on_below_target_to_the_floor. */
        @Test
        void onceBegunItCarriesOnBelowTarget() {
            HeatingController loop = made(Houses.learned(), bridging());
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_COAST, coast(loop, 20.7, flat(8.0), MODE_HEATING, out));
            assertEquals(List.of("coast"), out.flags);
        }

        /** test_after_one_the_next_waits_for_the_house_to_be_back_on_target. */
        @Test
        void afterOneTheNextWaitsForTheHouseToBeBackOnTarget() {
            HeatingController loop = made(Houses.learned(), bridging());
            List<String> modes = new ArrayList<>();
            for (double indoor : new double[] {20.8, 20.95, 21.0}) {
                modes.add(coast(loop, indoor, flat(8.0), MODE_HEATING, new ControlOutput()));
            }
            assertEquals(List.of(MODE_HEATING, MODE_HEATING, MODE_COAST), modes);
        }

        /** A coast under way whose shunt may no longer stay shut ends. */
        @Test
        void aCoastEndsWhenTheShuntMayNotStayShut() {
            HeatingController loop = made(Houses.learned());
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coast(loop, 21.8, flat(8.0), MODE_HEATING, out));
            assertFalse(loop.state.coasting);
            assertTrue(out.coastKnown);
        }

        @Test
        void theCoastFlagComesAfterThoseAlreadySaid() {
            ControlOutput out = new ControlOutput();
            out.flags.add("earlier");
            coast(made(Houses.learned()), 21.8, turning(), MODE_HEATING, out);
            assertEquals(List.of("earlier", "coast"), out.flags);
        }

        // --- TestHysteresis: the leads set a minute either side of the hold H ------------------

        Tuning around(double lead, double margin) {
            Tuning tuning = bridging();
            tuning.shuntLeadSeconds = lead;
            tuning.coastHysteresisSeconds = margin;
            return tuning;
        }

        /** test_a_hold_just_over_the_lead_does_not_start_a_coast: a minute over the lead, the margin on top. */
        @Test
        void aHoldJustOverTheLeadDoesNotStartACoast() {
            double hold = held(bridging()) * HOUR;
            ControlOutput out = new ControlOutput();
            HeatingController loop = made(Houses.learned(), around(hold - 60.0, 900.0));
            assertEquals(MODE_HEATING, coast(loop, 21.8, flat(8.0), MODE_HEATING, out));
            assertFalse(out.coastShunt);
            assertTrue(out.coastKnown);
        }

        /** test_a_hold_past_the_margin_does. */
        @Test
        void aHoldPastTheMarginDoes() {
            double hold = held(bridging()) * HOUR;
            HeatingController loop = made(Houses.learned(), around(hold - 960.0, 900.0));
            assertEquals(MODE_COAST, coast(loop, 21.8, flat(8.0), MODE_HEATING, new ControlOutput()));
        }

        /** test_once_coasting_the_lead_alone_keeps_it_going. */
        @Test
        void onceCoastingTheLeadAloneKeepsItGoing() {
            double hold = held(bridging()) * HOUR;
            HeatingController loop = made(Houses.learned(), around(hold - 60.0, 900.0));
            loop.state.coasting = true;
            assertEquals(MODE_COAST, coast(loop, 21.8, flat(8.0), MODE_HEATING, new ControlOutput()));
            assertTrue(loop.state.coasting);
        }

        /** test_and_it_stops_when_the_lead_itself_runs_out. */
        @Test
        void andItStopsWhenTheLeadItselfRunsOut() {
            double hold = held(bridging()) * HOUR;
            HeatingController loop = made(Houses.learned(), around(hold + 60.0, 900.0));
            loop.state.coasting = true;
            assertEquals(MODE_HEATING, coast(loop, 21.8, flat(8.0), MODE_HEATING, new ControlOutput()));
            assertFalse(loop.state.coasting);
        }

        /** test_the_boundary_no_longer_flaps: the hold on the lead, twenty cycles, one decision. */
        @Test
        void theBoundaryNoLongerFlaps() {
            double hold = held(bridging()) * HOUR;
            HeatingController loop = made(Houses.learned(), around(hold, 900.0));
            Set<String> modes = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                modes.add(coast(loop, 21.8, flat(8.0), MODE_HEATING, new ControlOutput()));
            }
            assertEquals(Set.of(MODE_HEATING), modes);
        }

        /**
         * On the floor on a cold night the hold is nought exactly: the first step of the outlook ends
         * below the floor with nothing of it spent. Python compares held > needed strictly, so a lead of
         * nought with a coast under way (no margin) is not cleared: a hold equal to the threshold stops.
         */
        @Test
        void aHoldExactlyOnTheLeadIsNotEnough() {
            HeatingController loop = made(Houses.learned(), around(0.0, 900.0));
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            String mode = coast(loop, 20.0, flat(-20.0), MODE_HEATING, out);
            assertTrue(out.coastKnown);
            assertEquals(0.0, out.coastHours, 0.0, "the house is on the floor already");
            assertEquals(0.0, out.coastShuntThreshold, 0.0, "the lead alone, no margin while coasting");
            assertFalse(out.coastShunt, "0 > 0 is false");
            assertEquals(MODE_HEATING, mode);
            assertFalse(loop.state.coasting);
        }

        /** The burner's threshold is compared the same way: equal is not over. */
        @Test
        void aHoldExactlyOnTheBurnersCostIsNotEnoughEither() {
            Tuning tuning = around(0.0, 900.0);
            tuning.heatSourceOffDelay = 0.0;
            HeatingController loop = made(Houses.learned(), tuning);
            loop.burnerLeadSeconds(0.0);
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            coast(loop, 20.0, flat(-20.0), MODE_HEATING, out);
            assertEquals(0.0, out.coastHours, 0.0);
            assertEquals(0.0, out.coastBurnerThreshold, 0.0);
            assertFalse(out.coastBurner, "0 > 0 is false");
        }

        /**
         * A house that holds the whole forecast is held for ever, not for the horizon: a threshold past the
         * horizon is cleared all the same. coast_hours still reads the horizon, as the outlook says.
         */
        @Test
        void aHouseThatHoldsTheWholeForecastClearsAnyThreshold() {
            Tuning tuning = around(13.0 * HOUR, 0.0);
            tuning.coastHorizonHours = 12.0;
            tuning.heatSourceOffDelay = 0.0;
            HeatingController loop = made(Houses.learned(), tuning);
            loop.burnerLeadSeconds(100.0 * HOUR);
            ControlOutput out = new ControlOutput();
            String mode = coast(loop, 22.0, Houses.flat(16.0, 0.0, 0.7), MODE_HEATING, out);
            assertTrue(out.coastKnown);
            assertEquals(12.0, out.coastHours, 1e-9, "the horizon, because it holds all of it");
            assertTrue(out.coastShunt, "13 h is less than for ever");
            assertTrue(out.coastBurner, "and so are 100 h");
            assertEquals(MODE_COAST, mode);
        }

        /** test_leaving_heating_clears_the_coast_state. */
        @Test
        void leavingHeatingClearsTheCoastState() {
            HeatingController loop = made(Houses.learned());
            coast(loop, 21.8, turning(), MODE_HEATING, new ControlOutput());
            assertTrue(loop.state.coasting);
            assertEquals(MODE_OFF, coast(loop, 21.8, turning(), MODE_OFF, new ControlOutput()));
            assertFalse(loop.state.coasting);
        }

        /** test_the_burner_gets_the_same_margin: over the burner's lead and delay, under them with the margin. */
        @Test
        void theBurnerGetsTheSameMargin() {
            double hold = held(bridging()) * HOUR;
            HeatingController loop = made(Houses.learned(), around(600.0, 900.0));
            loop.burnerLeadSeconds(hold - 1800.0 - 60.0);
            ControlOutput first = new ControlOutput();
            ControlOutput second = new ControlOutput();
            assertEquals(MODE_COAST, coast(loop, 21.8, flat(8.0), MODE_HEATING, first));
            coast(loop, 21.8, flat(8.0), MODE_HEATING, second);
            assertFalse(first.coastBurner, "first");
            assertTrue(second.coastBurner, "second");
        }

        // --- TestTheExplorationBudget ------------------------------------------------------------

        /** test_a_model_with_barely_any_data_is_given_the_whole_band. */
        @Test
        void aModelWithBarelyAnyDataIsGivenTheWholeBand() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 1.0;
            ControlOutput out = new ControlOutput();
            coast(made(Houses.sixHours(), tuning), 21.8, flat(8.0), MODE_HEATING, out);
            assertTrue(out.coastAllowance > 0.5, "allowance " + out.coastAllowance);
        }

        /** test_noise_alone_does_not_buy_exploration. */
        @Test
        void noiseAloneDoesNotBuyExploration() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 1.0;
            ControlOutput clean = new ControlOutput();
            ControlOutput noisy = new ControlOutput();
            coast(made(Houses.threeDays(), tuning), 21.8, flat(8.0), MODE_HEATING, clean);
            coast(made(Houses.threeNoisyDays(), tuning), 21.8, flat(8.0), MODE_HEATING, noisy);
            assertNotNull(noisy.coastReach, "worked out");
            assertEquals(clean.coastAllowance, noisy.coastAllowance, 0.02);
            assertTrue(noisy.coastAllowance < 0.1, "allowance " + noisy.coastAllowance);
        }

        /** test_a_confident_model_is_given_almost_none. */
        @Test
        void aConfidentModelIsGivenAlmostNone() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 1.0;
            ControlOutput out = new ControlOutput();
            coast(made(Houses.learned(), tuning), 21.8, flat(8.0), MODE_HEATING, out);
            assertNotNull(out.coastReach, "worked out");
            assertTrue(out.coastAllowance < 0.35, "allowance " + out.coastAllowance);
        }

        /** test_strange_weather_re_opens_it_without_any_mode_changing. */
        @Test
        void strangeWeatherReOpensIt() {
            Tuning tuning = coastTuning();
            tuning.coastLearningBand = 1.0;
            HeatingController loop = made(Houses.learned(), tuning);
            ControlOutput familiar = new ControlOutput();
            ControlOutput strange = new ControlOutput();
            coast(loop, 21.8, flat(5.0, 3.0, 0.0), MODE_HEATING, familiar);
            coast(loop, 21.8, flat(-30.0, 20.0, 0.0), MODE_HEATING, strange);
            assertTrue(strange.coastAllowance > familiar.coastAllowance);
        }

        /** test_the_band_is_a_setting. */
        @Test
        void theBandIsASetting() {
            Tuning wideTuning = coastTuning();
            wideTuning.coastLearningBand = 1.0;
            Tuning tightTuning = coastTuning();
            tightTuning.coastLearningBand = 0.2;
            ControlOutput wide = new ControlOutput();
            ControlOutput tight = new ControlOutput();
            coast(made(Houses.learned(), wideTuning), 21.8, flat(-30.0, 20.0, 0.0), MODE_HEATING, wide);
            coast(made(Houses.learned(), tightTuning), 21.8, flat(-30.0, 20.0, 0.0), MODE_HEATING, tight);
            assertTrue(tight.coastAllowance <= 0.2);
            assertTrue(wide.coastAllowance > tight.coastAllowance);
        }

        // --- the learning hold, asked only when a setback would start ----------------------------

        HeatingController holding(Tuning tuning) {
            tuning.coastLearningHoldSeconds = 2 * HOUR;
            tuning.coastLearningHoldMaxSeconds = 6 * HOUR;
            HeatingController loop = made(Houses.learned(), tuning);
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), 8.5);
            return loop;
        }

        String coastAt(HeatingController loop, double indoor, List<Forecast> forecast, String mode, ControlOutput out) {
            ControlInputs inputs = ahead(forecast);
            inputs.hour = 3.0;
            loop.out = out;
            return loop.coast(inputs, indoor, mode, out);
        }

        /** test_with_no_measurements_here_the_setback_waits. */
        @Test
        void anEmptyTableHoldsTheSetbackBack() {
            HeatingController loop = holding(coastTuning());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coastAt(loop, 21.8, turning(), MODE_HEATING, out));
            assertTrue(out.coastShunt);
            assertEquals(List.of("learning_hold"), out.flags);
            assertEquals(2 * HOUR, out.coastLearningHold, 0.0);
            assertEquals(DT, loop.state.secondsLearningHold, 0.0);
            assertFalse(loop.state.coasting);
        }

        /** test_where_the_table_has_its_hours_there_is_no_wait. */
        @Test
        void aFullCellLetsItStart() {
            HeatingController loop = holding(coastTuning());
            loop.points.add(8.5, 10.0, 0.0, 2.0, 6.0);
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_COAST, coastAt(loop, 21.8, turning(), MODE_HEATING, out));
            assertEquals(List.of("coast"), out.flags);
        }

        /** test_it_never_ends_a_setback_under_way. */
        @Test
        void itNeverEndsASetbackUnderWay() {
            HeatingController loop = holding(coastTuning());
            loop.state.coasting = true;
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_COAST, coastAt(loop, 21.8, turning(), MODE_HEATING, out));
            assertEquals(List.of("coast"), out.flags);
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0, "not asked, not counted");
            assertNull(out.coastLearningHold);
        }

        /** test_when_no_setback_would_start_anyway_nothing_is_held_or_counted. */
        @Test
        void whenTheShuntMayNotCloseNothingIsHeldOrCounted() {
            HeatingController loop = holding(coastTuning());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coastAt(loop, 21.8, flat(8.0), MODE_HEATING, out));
            assertTrue(out.coastKnown);
            assertFalse(out.coastShunt);
            assertEquals(List.of(), out.flags);
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0);
            assertNull(loop.state.learningHoldCell);
        }

        @Test
        void belowTargetItIsNotAskedEither() {
            HeatingController loop = holding(bridging());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_HEATING, coastAt(loop, 20.9, flat(8.0), MODE_HEATING, out));
            assertTrue(out.coastShunt);
            assertEquals(List.of(), out.flags);
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0);
        }

        @Test
        void norFromAStop() {
            HeatingController loop = holding(coastTuning());
            ControlOutput out = new ControlOutput();
            assertEquals(MODE_WARM, coastAt(loop, 21.8, turning(), MODE_WARM, out));
            assertTrue(out.coastShunt);
            assertEquals(List.of(), out.flags);
            assertEquals(0.0, loop.state.secondsLearningHold, 0.0);
        }

        /** test_nought_switches_it_off. */
        @Test
        void noughtSwitchesItOff() {
            Tuning tuning = coastTuning();
            HeatingController loop = made(Houses.learned(), tuning);
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), 8.5);
            assertEquals(MODE_COAST, coastAt(loop, 21.8, turning(), MODE_HEATING, new ControlOutput()));
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        static final double MODEL = 1e-6;

        /** coast_houses.csv, played once: name -> the house. */
        static Map<String, HouseModel> recipes;

        static synchronized HouseModel house(String name) {
            if (name.equals("ny")) {
                return new HouseModel();
            }
            if (recipes == null) {
                recipes = readRecipes();
            }
            HouseModel house = recipes.get(name);
            assertNotNull(house, "no recipe in coast_houses.csv for " + name);
            return house;
        }

        static Map<String, HouseModel> readRecipes() {
            Map<String, HouseModel> houses = new HashMap<>();
            try (InputStream in = RolloutTest.class.getResourceAsStream("/coast_houses.csv")) {
                assertNotNull(in, "coast_houses.csv");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                assertEquals("name;ops", reader.readLine(), "the header");
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] cells = line.split(";", -1);
                    assertEquals(2, cells.length, "name;ops");
                    houses.put(cells[0], played(cells[1]));
                }
            } catch (IOException e) {
                fail("coast_houses.csv: " + e);
            }
            return houses;
        }

        /** observe,indoor,outdoor,wind,bearing,sun,hour,delta,dt,sampleSeconds,radiators,night, as in step 7. */
        static HouseModel played(String ops) {
            HouseModel house = new HouseModel();
            for (String op : Facit.ops(ops)) {
                String[] f = Facit.fields(op);
                assertEquals("observe", f[0], "only observe ops");
                Conditions conditions = f[2].isEmpty() ? null : Facit.conditions(f, 2);
                house.observe(number(f[1]), conditions, required(f[7]), required(f[8]), required(f[9]),
                        number(f[10]), Facit.bool(f[11]));
            }
            return house;
        }

        static List<String> words(String cell) {
            return cell == null || cell.isEmpty() ? List.of() : Arrays.asList(cell.split("\\|", -1));
        }

        static String text(String cell) {
            return cell == null ? "" : cell;
        }

        /** outdoor:need:wind:hour:hours/... in order. */
        static void fill(PointTable points, String cell) {
            if (cell == null || cell.isEmpty()) {
                return;
            }
            for (String entry : cell.split("/", -1)) {
                String[] p = entry.split(":", -1);
                points.add(required(p[0]), required(p[1]), number(p[2]), number(p[3]), required(p[4]));
            }
        }

        static void outdoor(HeatingController loop, String cell) {
            loop.state.outdoor = new Ewma(loop.state.outdoor.seconds(), number(cell));
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/coast.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameCoast(String house, String tuningCell, String burnerLead, String coastingBefore,
                       String holdSecondsBefore, String holdCellBefore, String holdBaseBefore,
                       String outdoorFiltered, String table, String indoor, String mode, String forecast,
                       String hour, String dt, String result, String coastingAfter, String holdSecondsAfter,
                       String holdCellAfter, String holdBaseAfter, String flags, String coastTrust,
                       String coastUncertainty, String coastReach, String coastAllowance, String coastMargin,
                       String coastKnown, String coastHours, String coastShuntThreshold, String coastTurnHours,
                       String coastShunt, String coastBurnerThreshold, String coastBurner,
                       String coastLearningHold) {
            HeatingController loop = new HeatingController(Model.tuningFromDict(dict(tuningCell)), new LearnedState(),
                    house(house), null, null, null);
            loop.burnerLeadSeconds(required(burnerLead));
            loop.state.coasting = bool(coastingBefore);
            loop.state.secondsLearningHold = required(holdSecondsBefore);
            loop.state.learningHoldCell = holdCellBefore;
            loop.state.learningHoldBase = required(holdBaseBefore);
            outdoor(loop, outdoorFiltered);
            fill(loop.points, table);
            ControlInputs inputs = new ControlInputs(required(dt));
            inputs.forecast = Facit.weather(forecast);
            inputs.hour = number(hour);
            ControlOutput out = new ControlOutput();
            loop.out = out;

            assertEquals(result, loop.coast(inputs, number(indoor), mode, out), "result");

            assertEquals(bool(coastingAfter), loop.state.coasting, "coasting after");
            assertClose(required(holdSecondsAfter), loop.state.secondsLearningHold, "seconds_learning_hold after");
            assertEquals(holdCellAfter, loop.state.learningHoldCell, "learning_hold_cell after");
            assertClose(required(holdBaseAfter), loop.state.learningHoldBase, "learning_hold_base after");
            assertEquals(words(flags), out.flags, "flags");
            assertEquals(text(coastTrust), out.coastTrust, "coast_trust");
            assertClose(number(coastUncertainty), out.coastUncertainty, MODEL, "coast_uncertainty");
            assertClose(number(coastReach), out.coastReach, MODEL, "coast_reach");
            assertClose(required(coastAllowance), out.coastAllowance, MODEL, "coast_allowance");
            assertClose(required(coastMargin), out.coastMargin, MODEL, "coast_margin");
            assertEquals(bool(coastKnown), out.coastKnown, "coast_known");
            assertClose(number(coastHours), out.coastHours, MODEL, "coast_hours");
            assertClose(number(coastShuntThreshold), out.coastShuntThreshold, "coast_shunt_threshold");
            assertClose(number(coastTurnHours), out.coastTurnHours, MODEL, "coast_turn_hours");
            assertEquals(bool(coastShunt), out.coastShunt, "coast_shunt");
            assertClose(number(coastBurnerThreshold), out.coastBurnerThreshold, "coast_burner_threshold");
            assertEquals(bool(coastBurner), out.coastBurner, "coast_burner");
            assertClose(number(coastLearningHold), out.coastLearningHold, "coast_learning_hold");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/distrust.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameDistrust(String house, String coastMinHours, String result) {
            Tuning tuning = new Tuning();
            tuning.coastMinHours = required(coastMinHours);
            HeatingController loop = new HeatingController(tuning, new LearnedState(), house(house), null, null, null);
            assertEquals(text(result), loop.distrust(), "result");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/night.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameNight(String start, String end, String learnAfterSun, String secondsSinceSun, String hour,
                       String sunElevation, String result) {
            Tuning tuning = new Tuning();
            tuning.nightStartHour = required(start);
            tuning.nightEndHour = required(end);
            tuning.learnAfterSunSeconds = required(learnAfterSun);
            HeatingController loop = made(tuning);
            loop.state.secondsSinceSun = required(secondsSinceSun);
            ControlInputs inputs = new ControlInputs(DT);
            inputs.hour = number(hour);
            inputs.sunElevation = number(sunElevation);
            assertEquals(bool(result), loop.night(inputs), "result");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/conditions.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameConditions(String outdoor, String wind, String windBearing, String sunElevation, String cloud,
                            String sunlight, String hour, String given, String cOutdoor, String cWind,
                            String cBearing, String cSun, String cHour) {
            ControlInputs inputs = new ControlInputs(DT);
            inputs.outdoor = number(outdoor);
            inputs.wind = number(wind);
            inputs.windBearing = number(windBearing);
            inputs.sunElevation = number(sunElevation);
            inputs.cloud = number(cloud);
            inputs.sunlight = number(sunlight);
            inputs.hour = number(hour);
            Conditions got = made().conditions(inputs);
            if (!bool(given)) {
                assertNull(got, "None");
                return;
            }
            assertNotNull(got, "given");
            assertClose(required(cOutdoor), got.outdoor(), "outdoor");
            assertClose(number(cWind), got.wind(), "wind");
            assertClose(number(cBearing), got.bearing(), "bearing");
            assertClose(required(cSun), got.sun(), "sun");
            assertClose(number(cHour), got.hour(), "hour");
        }

        /** cycles: flow:ret:outdoor:wind:wind_bearing:sun_elevation:cloud:sunlight:hour:indoor:delta:dt/...;
         *  learning: learning,why,value/... one per cycle. */
        @ParameterizedTest
        @CsvFileSource(resources = "/observe_house.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameObserveHouse(String tuningCell, String secondsSinceSun, String cycles, String learning,
                              String observedHours, String lastIndoor, String secondsSinceSample,
                              String fitWeight) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            loop.state.secondsSinceSun = required(secondsSinceSun);
            String[] steps = cycles.split("/", -1);
            String[] verdicts = learning.split("/", -1);
            assertEquals(steps.length, verdicts.length, "a verdict per cycle");
            for (int i = 0; i < steps.length; i++) {
                String[] c = steps[i].split(":", -1);
                assertEquals(12, c.length, "cycle " + i);
                ControlInputs inputs = new ControlInputs(required(c[11]));
                inputs.flow = number(c[0]);
                inputs.ret = number(c[1]);
                inputs.outdoor = number(c[2]);
                inputs.wind = number(c[3]);
                inputs.windBearing = number(c[4]);
                inputs.sunElevation = number(c[5]);
                inputs.cloud = number(c[6]);
                inputs.sunlight = number(c[7]);
                inputs.hour = number(c[8]);
                loop.out = new ControlOutput();
                loop.observeHouse(inputs, number(c[9]), number(c[10]), required(c[11]));
                String[] v = verdicts[i].split(",", -1);
                LearnerStatus got = loop.out.learning.get(LEARN_HOUSE);
                assertNotNull(got, "cycle " + i + ": said");
                assertEquals(bool(v[0]), got.learning(), "cycle " + i + ": learning");
                assertEquals(v[1], got.why(), "cycle " + i + ": why");
                assertClose(number(v[2]), got.number(), "cycle " + i + ": value");
            }
            assertClose(required(observedHours), loop.house.observedHours(), "observed_hours");
            assertClose(number(lastIndoor), loop.house.lastIndoor(), "last_indoor");
            assertClose(required(secondsSinceSample), loop.house.secondsSinceSample(), "seconds_since_sample");
            assertClose(required(fitWeight), loop.house.fit().weight(), "fit_weight");
        }

        @ParameterizedTest
        @CsvFileSource(resources = "/learning_hold.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 65536)
        void sameLearningHold(String tuningCell, String outdoorFiltered, String holdSecondsBefore,
                              String holdCellBefore, String holdBaseBefore, String table, String hour, String dt,
                              String result, String holdSecondsAfter, String holdCellAfter, String holdBaseAfter,
                              String coastLearningHold, String flags) {
            HeatingController loop = made(Model.tuningFromDict(dict(tuningCell)));
            outdoor(loop, outdoorFiltered);
            loop.state.secondsLearningHold = required(holdSecondsBefore);
            loop.state.learningHoldCell = holdCellBefore;
            loop.state.learningHoldBase = required(holdBaseBefore);
            fill(loop.points, table);
            ControlInputs inputs = new ControlInputs(required(dt));
            inputs.hour = number(hour);
            ControlOutput out = new ControlOutput();
            loop.out = out;

            assertEquals(bool(result), loop.learningHold(inputs, out), "result");

            assertClose(required(holdSecondsAfter), loop.state.secondsLearningHold, "seconds_learning_hold after");
            assertEquals(holdCellAfter, loop.state.learningHoldCell, "learning_hold_cell after");
            assertClose(required(holdBaseAfter), loop.state.learningHoldBase, "learning_hold_base after");
            assertClose(number(coastLearningHold), out.coastLearningHold, "coast_learning_hold");
            assertEquals(words(flags), out.flags, "flags");
        }
    }
}
