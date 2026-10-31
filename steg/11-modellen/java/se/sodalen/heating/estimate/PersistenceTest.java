package se.sodalen.heating.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.assertSameDict;
import static se.sodalen.heating.house.Stored.bool;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.ops;
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
 * The estimators written to storage and read back: as_dict and from_dict on
 * Ewma, DutyWatch, Regression and MultiRegression, and the time constants
 * the tuning sets afterwards. A stored file is the one input the controller
 * does not control: a key can be missing, null, text, or the shape an older
 * version wrote, and every one of those comes back as a usable default
 * rather than an exception. The time constant and the horizon never come
 * from the file; they come from the tuning that restores it.
 *
 * <p>Python: estimators.py (as_dict, from_dict); tests/test_controller.py
 * (TestMultiRegression.test_it_survives_a_round_trip,
 * test_a_stored_fit_of_the_wrong_shape_is_discarded); the rest from the code.
 */
class PersistenceTest {

    static final double HOUR = 3600.0;
    static final double DAY = 24.0 * HOUR;

    /** A stored dict with any values, null included; Map.of takes neither null nor mixed types kindly. */
    static Map<String, Object> stored(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    static List<String> keys(Map<String, ?> dict) {
        return new ArrayList<>(dict.keySet());
    }

    static double value(Map<String, ?> dict, String key) {
        Object value = dict.get(key);
        assertNotNull(value, key);
        return ((Number) value).doubleValue();
    }

    /** {"seconds": seconds, "value": value}, value None until the first sample. */
    @Nested
    class TheEwma {
        @Test
        void aNewFilterStoresItsSecondsAndNoValue() {
            Map<String, ?> dict = new Ewma(HOUR).asDict();
            assertEquals(List.of("seconds", "value"), keys(dict));
            assertEquals(HOUR, value(dict, "seconds"), 0.0);
            assertNull(dict.get("value"));
        }

        @Test
        void aFilterWithAValueStoresIt() {
            Ewma filter = new Ewma(HOUR);
            filter.update(21.5, 30.0);
            assertEquals(21.5, value(filter.asDict(), "value"), 0.0);
        }

        @Test
        void itSurvivesARestart() {
            Ewma filter = new Ewma(HOUR, 20.0);
            filter.update(22.0, 300.0);
            Ewma back = Ewma.fromDict(filter.asDict(), HOUR);
            assertEquals(filter.value(), back.value());
            assertEquals(HOUR, back.seconds(), 0.0);
            assertSameDict(filter.asDict(), back.asDict(), 0.0, "as_dict after the round trip");
        }

        /** The time constant is the tuning's, whatever the file says. */
        @Test
        void theSecondsAreTheTuningsNotTheFiles() {
            Ewma back = Ewma.fromDict(stored("seconds", 600.0, "value", 21.0), 3.0 * HOUR);
            assertEquals(3.0 * HOUR, back.seconds(), 0.0);
            assertEquals(21.0, back.value());
        }

        @Test
        void nothingStoredIsAFilterThatHasSeenNothing() {
            for (Map<String, Object> junk : Arrays.asList(null, stored(), stored("seconds", 600.0),
                    stored("value", null))) {
                Ewma back = Ewma.fromDict(junk, HOUR);
                assertNull(back.value(), String.valueOf(junk));
                assertEquals(HOUR, back.seconds(), 0.0, String.valueOf(junk));
            }
        }

        /** as_optional_float: None stays None, a broken value is 0.0, an int or a numeric text is itself. */
        @Test
        void theValueIsReadLikeAnyStoredNumber() {
            assertEquals(48.0, Ewma.fromDict(stored("value", 48), HOUR).value(), "an Integer from JSON");
            assertEquals(2.0, Ewma.fromDict(stored("value", " 2 "), HOUR).value(), "a numeric text");
            assertEquals(1.0, Ewma.fromDict(stored("value", true), HOUR).value(), "a bool");
            assertEquals(0.0, Ewma.fromDict(stored("value", "abc"), HOUR).value(), "a text that is no number");
            assertEquals(0.0, Ewma.fromDict(stored("value", ""), HOUR).value(), "an empty text");
            assertEquals(0.0, Ewma.fromDict(stored("value", Double.NaN), HOUR).value(), "nan");
            assertEquals(0.0, Ewma.fromDict(stored("value", List.of()), HOUR).value(), "a list");
        }

        @Test
        void setSecondsShowsInAsDict() {
            Ewma filter = new Ewma(HOUR, 20.0);
            filter.setSeconds(DAY);
            assertEquals(DAY, filter.seconds(), 0.0);
            assertEquals(DAY, value(filter.asDict(), "seconds"), 0.0);
            assertEquals(20.0, filter.value(), "the value is untouched");
        }
    }

    /** {"seconds", "share", "weight"}, share None until something could be judged. */
    @Nested
    class TheDutyWatch {
        @Test
        void aNewWatchStoresItsSecondsNoShareAndNoWeight() {
            Map<String, ?> dict = new DutyWatch(3.0 * DAY).asDict();
            assertEquals(List.of("seconds", "share", "weight"), keys(dict));
            assertEquals(3.0 * DAY, value(dict, "seconds"), 0.0);
            assertNull(dict.get("share"));
            assertEquals(0.0, value(dict, "weight"), 0.0);
        }

        @Test
        void itSurvivesARestart() {
            DutyWatch watch = new DutyWatch(3.0 * DAY);
            watch.update(true, 30.0);
            watch.update(false, 30.0);
            watch.update(null, 60.0);
            DutyWatch back = DutyWatch.fromDict(watch.asDict(), 3.0 * DAY);
            assertEquals(watch.share(), back.share());
            assertEquals(watch.weight(), back.weight(), 0.0);
            assertSameDict(watch.asDict(), back.asDict(), 0.0, "as_dict after the round trip");
        }

        @Test
        void theSecondsAreTheTuningsNotTheFiles() {
            DutyWatch back = DutyWatch.fromDict(stored("seconds", HOUR, "share", 0.5, "weight", 600.0), DAY);
            assertEquals(DAY, back.seconds(), 0.0);
            assertEquals(0.5, back.share());
            assertEquals(600.0, back.weight(), 0.0);
        }

        @Test
        void nothingStoredIsAWatchThatHasSeenNothing() {
            for (Map<String, Object> junk : Arrays.asList(null, stored(), stored("share", null),
                    stored("seconds", "abc"))) {
                DutyWatch back = DutyWatch.fromDict(junk, HOUR);
                assertNull(back.share(), String.valueOf(junk));
                assertEquals(0.0, back.weight(), 0.0, String.valueOf(junk));
                assertEquals(HOUR, back.seconds(), 0.0, String.valueOf(junk));
            }
        }

        /** share through as_optional_float (None stays, junk is 0.0), weight through as_float(…, 0.0). */
        @Test
        void aBrokenShareIsNoughtAndABrokenWeightIsNought() {
            assertEquals(0.0, DutyWatch.fromDict(stored("share", "abc"), HOUR).share());
            assertEquals(0.0, DutyWatch.fromDict(stored("share", Double.NaN), HOUR).share());
            assertEquals(1000.0, DutyWatch.fromDict(stored("share", "1e3"), HOUR).share(), "not clamped to 0-1");
            assertEquals(1.0, DutyWatch.fromDict(stored("weight", true), HOUR).weight(), 0.0);
            assertEquals(0.0, DutyWatch.fromDict(stored("weight", Double.NaN), HOUR).weight(), 0.0);
            assertEquals(0.0, DutyWatch.fromDict(stored("weight", Map.of()), HOUR).weight(), 0.0);
            assertEquals(0.0, DutyWatch.fromDict(stored("weight", null), HOUR).weight(), 0.0);
            assertEquals(-10.0, DutyWatch.fromDict(stored("weight", -10.0), HOUR).weight(), 0.0, "not clamped");
        }

        @Test
        void setSecondsShowsInAsDict() {
            DutyWatch watch = new DutyWatch(HOUR);
            watch.update(true, 30.0);
            watch.setSeconds(DAY);
            assertEquals(DAY, watch.seconds(), 0.0);
            assertEquals(DAY, value(watch.asDict(), "seconds"), 0.0);
            assertEquals(1.0, watch.share(), "the share is untouched");
            assertEquals(30.0, watch.weight(), 0.0, "the weight is untouched");
        }
    }

    /** {"horizon", "weight", "sum_x", "sum_y", "sum_xx", "sum_xy"}: the five sums and the horizon. */
    @Nested
    class TheRegression {
        @Test
        void aNewFitStoresItsHorizonAndFiveNoughts() {
            Map<String, ?> dict = new Regression(3.0 * DAY).asDict();
            assertEquals(List.of("horizon", "weight", "sum_x", "sum_y", "sum_xx", "sum_xy"), keys(dict));
            assertEquals(3.0 * DAY, value(dict, "horizon"), 0.0);
            for (String key : List.of("weight", "sum_x", "sum_y", "sum_xx", "sum_xy")) {
                assertEquals(0.0, value(dict, key), 0.0, key);
            }
        }

        @Test
        void theSumsAreStoredUnderPythonsNames() {
            Regression fit = new Regression(0.0);
            fit.add(2.0, 3.0, 30.0);
            Map<String, ?> dict = fit.asDict();
            assertEquals(1.0, value(dict, "weight"), 0.0);
            assertEquals(2.0, value(dict, "sum_x"), 0.0);
            assertEquals(3.0, value(dict, "sum_y"), 0.0);
            assertEquals(4.0, value(dict, "sum_xx"), 0.0);
            assertEquals(6.0, value(dict, "sum_xy"), 0.0);
        }

        @Test
        void itSurvivesARestart() {
            Regression fit = new Regression(3.0 * DAY);
            fit.add(0.3, -0.5, 30.0);
            fit.fade(600.0);
            fit.add(0.7, 1.2, 30.0);
            Regression back = Regression.fromDict(fit.asDict(), 3.0 * DAY);
            assertSameDict(fit.asDict(), back.asDict(), 0.0, "as_dict after the round trip");
            assertEquals(fit.fit(0.01), back.fit(0.01));
        }

        /** The horizon is the tuning's; the stored "horizon" is never read. */
        @Test
        void theHorizonIsTheTuningsNotTheFiles() {
            Regression back = Regression.fromDict(stored("horizon", HOUR, "weight", 2.0, "sum_x", 1.0,
                    "sum_y", 2.0, "sum_xx", 1.0, "sum_xy", 2.0), 12.0 * HOUR);
            assertEquals(12.0 * HOUR, back.horizon(), 0.0);
            assertEquals(2.0, back.weight(), 0.0);
            assertEquals(1.0, back.sumX(), 0.0);
            assertEquals(2.0, back.sumY(), 0.0);
            assertEquals(1.0, back.sumXx(), 0.0);
            assertEquals(2.0, back.sumXy(), 0.0);
        }

        @Test
        void nothingStoredIsAFitThatHasSeenNothing() {
            for (Map<String, Object> junk : Arrays.asList(null, stored(), stored("weight", null),
                    stored("horizon", "abc"))) {
                Regression back = Regression.fromDict(junk, HOUR);
                assertEquals(0.0, back.weight(), 0.0, String.valueOf(junk));
                assertEquals(0.0, back.sumXx(), 0.0, String.valueOf(junk));
                assertEquals(HOUR, back.horizon(), 0.0, String.valueOf(junk));
            }
        }

        /** Each sum through as_float(…, 0.0) on its own: a broken one is nought, the rest stay. */
        @Test
        void aBrokenSumIsNoughtAndTheRestAreKept() {
            Regression back = Regression.fromDict(stored("weight", 3.0, "sum_x", "abc", "sum_y", Double.NaN,
                    "sum_xx", Double.POSITIVE_INFINITY, "sum_xy", "1e3"), HOUR);
            assertEquals(3.0, back.weight(), 0.0);
            assertEquals(0.0, back.sumX(), 0.0, "a text");
            assertEquals(0.0, back.sumY(), 0.0, "nan");
            assertEquals(0.0, back.sumXx(), 0.0, "inf");
            assertEquals(1000.0, back.sumXy(), 0.0, "a numeric text");
        }

        @Test
        void setHorizonShowsInAsDict() {
            Regression fit = new Regression(HOUR);
            fit.add(1.0, 1.0, 30.0);
            fit.setHorizon(3.0 * DAY);
            assertEquals(3.0 * DAY, fit.horizon(), 0.0);
            assertEquals(3.0 * DAY, value(fit.asDict(), "horizon"), 0.0);
            assertEquals(1.0, fit.weight(), 0.0, "the sums are untouched");
        }
    }

    /**
     * {"size", "horizon", "weight", "xx", "xy", "residual", "residual_data", "yy"}: the matrix
     * form, and the one rule that matters - a stored fit of another size answers a different
     * question and is thrown away.
     */
    @Nested
    class TheMultiRegression {
        /** TestMultiRegression.fit(rows): a fit of two with horizon 0 over the rows. */
        MultiRegression fit(List<double[]> xs, List<Double> ys) {
            MultiRegression fit = new MultiRegression(xs.get(0).length, 0.0);
            for (int i = 0; i < xs.size(); i++) {
                fit.add(xs.get(i), ys.get(i), 1.0);
            }
            return fit;
        }

        MultiRegression line() {
            List<double[]> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (int a = -5; a <= 5; a++) {
                xs.add(new double[] {1.0, a});
                ys.add(2.0 * a);
            }
            return fit(xs, ys);
        }

        @Test
        void aNewFitStoresItsShapeAndNoughts() {
            Map<String, ?> dict = new MultiRegression(3, DAY).asDict();
            assertEquals(List.of("size", "horizon", "weight", "xx", "xy", "residual", "residual_data", "yy"),
                    keys(dict));
            assertEquals(3, dict.get("size"), "size is an int, as Python stores it");
            assertEquals(DAY, value(dict, "horizon"), 0.0);
            assertEquals(0.0, value(dict, "weight"), 0.0);
            assertEquals(List.of(List.of(0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0)),
                    dict.get("xx"));
            assertEquals(List.of(0.0, 0.0, 0.0), dict.get("xy"));
            assertEquals(0.0, value(dict, "residual"), 0.0);
            assertEquals(0.0, value(dict, "residual_data"), 0.0);
            assertEquals(0.0, value(dict, "yy"), 0.0);
        }

        /** test_it_survives_a_round_trip. */
        @Test
        void itSurvivesARoundTrip() {
            MultiRegression reg = line();
            MultiRegression back = MultiRegression.fromDict(reg.asDict(), 2, 0.0);
            assertSameDict(reg.asDict(), back.asDict(), 0.0, "as_dict after the round trip");
            assertEquals(reg.predict(new double[] {1.0, 3.0}), back.predict(new double[] {1.0, 3.0}), 1e-9);
        }

        /**
         * test_a_stored_fit_of_the_wrong_shape_is_discarded: a regressor added or dropped
         * means the old numbers answer a different question. Starting over is the only honest
         * option.
         */
        @Test
        void aStoredFitOfTheWrongShapeIsDiscarded() {
            MultiRegression reg = line();
            MultiRegression back = MultiRegression.fromDict(reg.asDict(), 6, 0.0);
            assertEquals(0.0, back.weight(), 0.0);
            assertNull(back.coefficients());
            assertEquals(6, back.size());
            assertEquals(6, back.xx().length, "a fresh matrix of the size asked for");
        }

        /** [list(row) for row in self.xx]: as_dict hands out copies, so a caller cannot change the fit. */
        @Test
        void asDictHandsOutCopies() {
            MultiRegression reg = line();
            Map<String, ?> first = reg.asDict();
            Map<String, ?> second = reg.asDict();
            assertNotSame(first.get("xx"), second.get("xx"));
            assertNotSame(first.get("xy"), second.get("xy"));
            assertEquals(first.get("xx"), second.get("xx"));
            assertEquals(first.get("xy"), second.get("xy"));
        }

        /** The horizon is the tuning's, whatever the file says; so is the size. */
        @Test
        void theHorizonIsTheTuningsNotTheFiles() {
            MultiRegression reg = new MultiRegression(2, HOUR);
            reg.add(new double[] {1.0, 2.0}, 3.0, 1.0);
            MultiRegression back = MultiRegression.fromDict(reg.asDict(), 2, DAY);
            assertEquals(DAY, back.horizon(), 0.0);
            assertEquals(DAY, value(back.asDict(), "horizon"), 0.0);
            assertEquals(1.0, back.weight(), 0.0);
        }

        @Test
        void nothingStoredIsAFitThatHasSeenNothing() {
            for (Map<String, Object> junk : Arrays.asList(null, stored(), stored("size", 3),
                    stored("size", 3, "xx", "abc", "xy", List.of(0.0, 0.0, 0.0)),
                    stored("size", 3, "xx", List.of(List.of(0.0, 0.0, 0.0)), "xy", "abc"))) {
                MultiRegression back = MultiRegression.fromDict(junk, 3, HOUR);
                assertEquals(0.0, back.weight(), 0.0, String.valueOf(junk));
                assertNull(back.coefficients(), String.valueOf(junk));
                assertEquals(3, back.size(), String.valueOf(junk));
                assertEquals(HOUR, back.horizon(), 0.0, String.valueOf(junk));
            }
        }

        /** int(data.get("size") or 0): "2", 2.0 and 2.9 are 2; "2.0", True, a list and 1.9 are not. */
        @Test
        void theStoredSizeIsReadLikePythonsInt() {
            Map<String, ?> good = line().asDict();
            MultiRegression reg = line();
            for (Object size : Arrays.asList(2, 2.0, "2", 2L, 2.9)) {
                Map<String, Object> dict = new LinkedHashMap<>(good);
                dict.put("size", size);
                MultiRegression back = MultiRegression.fromDict(dict, 2, 0.0);
                assertEquals(reg.weight(), back.weight(), 0.0, "size " + size + " is 2");
            }
            for (Object size : Arrays.asList("2.0", true, List.of(2.0), null, "abc", 3, 1.9)) {
                Map<String, Object> dict = new LinkedHashMap<>(good);
                dict.put("size", size);
                MultiRegression back = MultiRegression.fromDict(dict, 2, 0.0);
                assertEquals(0.0, back.weight(), 0.0, "size " + size + " is not 2");
            }
        }

        /** xx and xy of the wrong shape, or with a value float() refuses: a new fit. */
        @Test
        void aMatrixOfTheWrongShapeIsDiscarded() {
            Map<String, ?> good = line().asDict();
            List<Object> xxs = new ArrayList<>();
            xxs.add(List.of(List.of(1.0, 2.0)));
            xxs.add(List.of(List.of(1.0), List.of(1.0, 2.0)));
            xxs.add(List.of(List.of(1.0, 2.0, 3.0), List.of(1.0, 2.0)));
            xxs.add(Arrays.asList(Arrays.asList(null, 2.0), List.of(1.0, 2.0)));
            xxs.add(List.of(List.of(1.0, "abc"), List.of(1.0, 2.0)));
            xxs.add(List.of(1.0, 2.0));
            xxs.add(List.of());
            xxs.add(Map.of());
            xxs.add(true);
            for (Object xx : xxs) {
                Map<String, Object> dict = new LinkedHashMap<>(good);
                dict.put("xx", xx);
                assertEquals(0.0, MultiRegression.fromDict(dict, 2, 0.0).weight(), 0.0, "xx " + xx);
            }
            for (Object xy : Arrays.asList(List.of(1.0), List.of(1.0, 2.0, 3.0), Arrays.asList(null, 1.0),
                    List.of(1.0, "x"), List.of(), 1.0, Map.of())) {
                Map<String, Object> dict = new LinkedHashMap<>(good);
                dict.put("xy", xy);
                assertEquals(0.0, MultiRegression.fromDict(dict, 2, 0.0).weight(), 0.0, "xy " + xy);
            }
        }

        /** Integers in the matrix, as JSON may give them, are floats. */
        @Test
        void integersInTheMatrixAreRead() {
            Map<String, Object> dict = stored("size", 2, "horizon", 0.0, "weight", 4,
                    "xx", List.of(List.of(4, 2), List.of(2, 6)), "xy", List.of(2, 1),
                    "residual", 0, "residual_data", 0, "yy", 1);
            MultiRegression back = MultiRegression.fromDict(dict, 2, 0.0);
            assertEquals(4.0, back.weight(), 0.0);
            assertEquals(4.0, back.xx()[0][0], 0.0);
            assertEquals(6.0, back.xx()[1][1], 0.0);
            assertEquals(1.0, back.xy()[1], 0.0);
            assertEquals(List.of(List.of(4.0, 2.0), List.of(2.0, 6.0)), back.asDict().get("xx"));
        }

        /** Stored before yy was kept: None, and in_sample_sigma falls back to sigma. */
        @Test
        void aFileWithoutYyGivesNull() {
            Map<String, Object> dict = new LinkedHashMap<>(line().asDict());
            dict.remove("yy");
            MultiRegression back = MultiRegression.fromDict(dict, 2, 0.0);
            assertNull(back.asDict().get("yy"));
            assertEquals(back.sigma(), back.inSampleSigma(), 0.0);
            assertEquals(line().weight(), back.weight(), 0.0, "the rest is read");
            dict.put("yy", null);
            assertNull(MultiRegression.fromDict(dict, 2, 0.0).asDict().get("yy"), "yy None");
        }

        /** Stored before residual_data existed: the model's own residual is the only history. */
        @Test
        void aFileWithoutResidualDataTakesTheResidual() {
            Map<String, Object> dict = new LinkedHashMap<>(line().asDict());
            dict.put("residual", 2.5);
            dict.remove("residual_data");
            MultiRegression back = MultiRegression.fromDict(dict, 2, 0.0);
            assertEquals(2.5, back.residual(), 0.0);
            assertEquals(2.5, back.residualData(), 0.0);
            dict.put("residual", "abc");
            back = MultiRegression.fromDict(dict, 2, 0.0);
            assertEquals(0.0, back.residual(), 0.0, "a broken residual is nought");
            assertEquals(0.0, back.residualData(), 0.0, "and so is the fallback");
        }

        /** Configuration, not state: priors and floors are not persisted, nor is the ridge. */
        @Test
        void priorsFloorsAndRidgeAreNotStored() {
            MultiRegression reg = new MultiRegression(2, HOUR, 1e-3);
            reg.configure(Map.of(1, new Prior(2.0, 5.0)), Map.of(0, 0.0));
            reg.add(new double[] {1.0, 2.0}, 3.0, 1.0);
            Map<String, ?> dict = reg.asDict();
            assertEquals(8, dict.size(), "eight keys: " + keys(dict));
            assertTrue(!dict.containsKey("priors") && !dict.containsKey("floors") && !dict.containsKey("ridge"));
            MultiRegression back = MultiRegression.fromDict(dict, 2, HOUR);
            assertEquals(MultiRegression.RIDGE, back.ridge(), 0.0, "the restored fit has the default ridge");
        }

        @Test
        void setHorizonShowsInAsDict() {
            MultiRegression reg = new MultiRegression(2, HOUR);
            reg.add(new double[] {1.0, 2.0}, 3.0, 1.0);
            reg.setHorizon(21.0 * DAY);
            assertEquals(21.0 * DAY, reg.horizon(), 0.0);
            assertEquals(21.0 * DAY, value(reg.asDict(), "horizon"), 0.0);
            assertEquals(1.0, reg.weight(), 0.0, "the sums are untouched");
        }
    }

    /** Behaviour read off estimators.py and parsing.py that the classes share. */
    @Nested
    class FromTheCode {
        /** as_mapping: from_dict on nothing at all is from_dict on an empty dict. */
        @Test
        void nullIsAnEmptyDictForEveryone() {
            assertSameDict(Ewma.fromDict(stored(), HOUR).asDict(), Ewma.fromDict(null, HOUR).asDict(), 0.0,
                    "Ewma");
            assertSameDict(DutyWatch.fromDict(stored(), HOUR).asDict(), DutyWatch.fromDict(null, HOUR).asDict(),
                    0.0, "DutyWatch");
            assertSameDict(Regression.fromDict(stored(), HOUR).asDict(), Regression.fromDict(null, HOUR).asDict(),
                    0.0, "Regression");
            assertSameDict(MultiRegression.fromDict(stored(), 2, HOUR).asDict(),
                    MultiRegression.fromDict(null, 2, HOUR).asDict(), 0.0, "MultiRegression");
        }

        /** An unknown key lands in Python's dict and nowhere else. */
        @Test
        void anUnknownKeyChangesNothing() {
            assertEquals(21.0, Ewma.fromDict(stored("value", 21.0, "foo", 1), HOUR).value());
            assertEquals(0.5, DutyWatch.fromDict(stored("share", 0.5, "foo", 1), HOUR).share());
            assertEquals(2.0, Regression.fromDict(stored("weight", 2.0, "foo", 1), HOUR).weight(), 0.0);
        }

        /** as_dict is a new dict every time, with the values of that moment. */
        @Test
        void asDictIsASnapshot() {
            Ewma filter = new Ewma(HOUR);
            Map<String, ?> before = filter.asDict();
            filter.update(21.0, 30.0);
            Map<String, ?> after = filter.asDict();
            assertNotSame(before, after);
            assertNull(before.get("value"));
            assertEquals(21.0, value(after, "value"), 0.0);
        }

        /** The restored fit goes on learning like the original. */
        @Test
        void aRestoredFitGoesOnLearning() {
            MultiRegression reg = new MultiRegression(2, 0.0);
            MultiRegression back = MultiRegression.fromDict(reg.asDict(), 2, 0.0);
            for (int a = -5; a <= 5; a++) {
                reg.add(new double[] {1.0, a}, 2.0 * a, 1.0);
                back.add(new double[] {1.0, a}, 2.0 * a, 1.0);
            }
            assertSameDict(reg.asDict(), back.asDict(), 0.0, "after the same samples");
            assertEquals(reg.predict(new double[] {1.0, 3.0}), back.predict(new double[] {1.0, 3.0}), 1e-9);
        }
    }

    /**
     * Python's own answers, row by row from estimator_dicts.csv: objects built
     * with step 6's operations and their as_dict, the round trip through
     * from_dict, and stored dicts of every broken shape and what from_dict made
     * of them. An empty cell arrives as null.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        /**
         * kind;seconds;size;ops;as_dict;roundtrip_equal;stored;restored_as_dict: the kind
         * (ewma, dutywatch, regression, multi), the seconds or horizon (also given to
         * from_dict), the size (multi only), the ops in step 6's format (empty for a junk row),
         * Python's as_dict typed, whether its round trip was equal, and a stored dict (typed,
         * or broken) with what from_dict(stored, …).as_dict() gave.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/estimator_dicts.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false, maxCharsPerColumn = 16384)
        void sameDicts(String kind, String seconds, String size, String steps, String asDict,
                       String roundtripEqual, String stored, String restoredAsDict) {
            double horizon = required(seconds);
            int n = size == null ? 0 : Integer.parseInt(size);
            if (asDict != null) {
                Map<String, ?> built = built(kind, horizon, n, steps);
                assertSameDict(dict(asDict), built, "as_dict");
                assertTrue(bool(roundtripEqual), "Python's own round trip was equal");
                assertSameDict(built, restored(kind, built, horizon, n), 0.0, "the round trip");
            }
            assertSameDict(dict(restoredAsDict), restored(kind, dict(stored), horizon, n), "restored from " + stored);
        }

        Map<String, ?> built(String kind, double horizon, int size, String steps) {
            switch (kind) {
                case "ewma" -> {
                    Ewma filter = new Ewma(horizon);
                    for (String op : ops(steps)) {
                        String[] p = op.split(",", -1);
                        filter.update(number(p[1]), required(p[2]));
                    }
                    return filter.asDict();
                }
                case "dutywatch" -> {
                    DutyWatch watch = new DutyWatch(horizon);
                    for (String op : ops(steps)) {
                        String[] p = op.split(",", -1);
                        watch.update(p[0].isEmpty() ? null : Boolean.valueOf(p[0]), required(p[1]));
                    }
                    return watch.asDict();
                }
                case "regression" -> {
                    Regression fit = new Regression(horizon);
                    for (String op : ops(steps)) {
                        String[] p = op.split(",", -1);
                        if (p[0].equals("add")) {
                            fit.add(required(p[1]), required(p[2]), required(p[3]));
                        } else {
                            fit.fade(required(p[1]));
                        }
                    }
                    return fit.asDict();
                }
                default -> {
                    MultiRegression fit = new MultiRegression(size, horizon);
                    for (String op : ops(steps)) {
                        String[] p = op.split(",", -1);
                        fit.add(Arrays.stream(p, 3, p.length).mapToDouble(Double::parseDouble).toArray(),
                                required(p[1]), required(p[2]));
                    }
                    return fit.asDict();
                }
            }
        }

        Map<String, ?> restored(String kind, Map<String, ?> data, double horizon, int size) {
            return switch (kind) {
                case "ewma" -> Ewma.fromDict(data, horizon).asDict();
                case "dutywatch" -> DutyWatch.fromDict(data, horizon).asDict();
                case "regression" -> Regression.fromDict(data, horizon).asDict();
                default -> MultiRegression.fromDict(data, size, horizon).asDict();
            };
        }
    }
}
