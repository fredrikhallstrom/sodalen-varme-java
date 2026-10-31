package se.sodalen.heating.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.sodalen.heating.helper.Parsing.PLAUSIBLE_HIGH;
import static se.sodalen.heating.helper.Parsing.PLAUSIBLE_LOW;
import static se.sodalen.heating.helper.Parsing.WIRED_MAX_C;
import static se.sodalen.heating.helper.Parsing.asFloat;
import static se.sodalen.heating.helper.Parsing.asMapping;
import static se.sodalen.heating.helper.Parsing.asOptionalFloat;
import static se.sodalen.heating.helper.Parsing.outdoorInUse;
import static se.sodalen.heating.helper.Parsing.plausibleTemperature;
import static se.sodalen.heating.house.Stored.assertClose;
import static se.sodalen.heating.house.Stored.dict;
import static se.sodalen.heating.house.Stored.integer;
import static se.sodalen.heating.house.Stored.number;
import static se.sodalen.heating.house.Stored.required;
import static se.sodalen.heating.house.Stored.typed;

import java.util.AbstractMap;
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
 * Reading numbers safely, out of stored JSON and off sensors. The bottom of
 * the stack: every from_dict in the model runs through as_float and
 * as_mapping on the way to starting the controller, and a stored file that
 * was hand-edited, half-written by a power cut or left by an older version
 * must come back as defaults rather than an exception. plausible_temperature
 * is where a broken sensor (-273 open, -999 the Uni's marker) stops being a
 * temperature, and outdoor_in_use picks the sensor the curve runs on.
 *
 * <p>Python: parsing.py; tests/test_inputs.py (WiredTest, OutdoorTest) for
 * the limits, the rest from the code.
 */
class ParsingTest {

    /** A reading as the controller lists them: Map.entry refuses a null value, this does not. */
    static Map.Entry<String, Double> reading(String key, Double value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }

    @SafeVarargs
    static List<Map.Entry<String, Double>> readings(Map.Entry<String, Double>... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    /** The limits a temperature is believed within, and why the wired probes get a wider one. */
    @Nested
    class TheLimits {
        @Test
        void nothingAroundAHouseReadsOutsideMinusFiftyToOneHundredAndTen() {
            assertEquals(-50.0, PLAUSIBLE_LOW, 0.0);
            assertEquals(110.0, PLAUSIBLE_HIGH, 0.0);
        }

        /** DS18B20s are good to 125, and 2026-10-06 the boiler reached 115.75 and showed as missing. */
        @Test
        void theWiredProbesAreBelievedToOneHundredAndTwentyFive() {
            assertEquals(125.0, WIRED_MAX_C, 0.0);
        }
    }

    /** as_mapping: whatever came out of storage, as something safe to call get on. */
    @Nested
    class AsMapping {
        @Test
        void aMapIsItself() {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("value", 1.0);
            assertSame(stored, asMapping(stored));
        }

        @Test
        void anythingElseIsAnEmptyMap() {
            assertTrue(asMapping(null).isEmpty(), "null");
            assertTrue(asMapping("abc").isEmpty(), "a text");
            assertTrue(asMapping(List.of(1.0, 2.0)).isEmpty(), "a list");
            assertTrue(asMapping(3.0).isEmpty(), "a number");
            assertTrue(asMapping(Boolean.TRUE).isEmpty(), "a bool");
        }

        /** Python's {} is a fresh dict every call; nobody shares it, and it can be written to. */
        @Test
        void theEmptyMapIsNewAndCanBeWrittenTo() {
            Map<String, ?> first = asMapping(null);
            Map<String, ?> second = asMapping(null);
            assertNotSame(first, second);
            @SuppressWarnings("unchecked")
            Map<String, Object> writable = (Map<String, Object>) first;
            writable.put("value", 1.0);
            assertEquals(1, first.size());
            assertTrue(second.isEmpty());
        }
    }

    /** as_float: Python's float(value), or the fallback where it raises or the number is not finite. */
    @Nested
    class AsFloat {
        @Test
        void aNumberIsItsValueWhateverItsBoxing() {
            assertEquals(21.5, asFloat(21.5, 0.0), 0.0);
            assertEquals(48.0, asFloat(48, 0.0), 0.0, "an Integer, as JSON gives one");
            assertEquals(48.0, asFloat(48L, 0.0), 0.0, "a Long");
            assertEquals(1.5, asFloat(1.5f, 0.0), 0.0, "a Float");
            assertEquals(-999.0, asFloat(-999, 0.0), 0.0);
        }

        /** float(True) is 1.0 and float(False) is 0.0. */
        @Test
        void aBoolIsOneOrNought() {
            assertEquals(1.0, asFloat(true, 99.0), 0.0);
            assertEquals(0.0, asFloat(false, 99.0), 0.0);
        }

        @Test
        void aTextThatIsANumberParses() {
            assertEquals(21.5, asFloat("21.5", 0.0), 0.0);
            assertEquals(2.0, asFloat(" 2 ", 0.0), 0.0, "spaces around");
            assertEquals(1000.0, asFloat("1e3", 0.0), 0.0, "an exponent");
            assertEquals(1500.0, asFloat("1.5e3", 0.0), 0.0);
            assertEquals(-0.0025, asFloat("-2.5E-3", 0.0), 0.0);
            assertEquals(5.0, asFloat("+5", 0.0), 0.0, "a plus sign");
            assertEquals(2.0, asFloat("2.", 0.0), 0.0, "a trailing point");
            assertEquals(0.5, asFloat(".5", 0.0), 0.0, "a leading point");
            assertEquals(-999.0, asFloat("-999", 0.0), 0.0);
        }

        @Test
        void aTextThatIsNotANumberIsTheFallback() {
            for (String text : List.of("abc", "", " ", "1,5", "21.5 C", "true", "False", "1e")) {
                assertEquals(7.5, asFloat(text, 7.5), 0.0, "'" + text + "'");
            }
        }

        /** float("nan") and float("inf") parse in Python, and the finite check throws them out again. */
        @Test
        void aTextThatSaysNanOrInfIsTheFallback() {
            for (String text : List.of("nan", "NaN", "inf", "-inf", "Infinity", "-Infinity", "1e400", "-1e400")) {
                assertEquals(7.5, asFloat(text, 7.5), 0.0, "'" + text + "'");
            }
        }

        @Test
        void nanAndInfinityAreTheFallback() {
            assertEquals(7.5, asFloat(Double.NaN, 7.5), 0.0);
            assertEquals(7.5, asFloat(Double.POSITIVE_INFINITY, 7.5), 0.0);
            assertEquals(7.5, asFloat(Double.NEGATIVE_INFINITY, 7.5), 0.0);
            assertEquals(1e300, asFloat(1e300, 7.5), 0.0, "large but finite is kept");
        }

        /** float(None), float([]) and float({}) raise TypeError. */
        @Test
        void nullAListOrAMapIsTheFallback() {
            assertEquals(99.0, asFloat(null, 99.0), 0.0);
            assertEquals(99.0, asFloat(List.of(), 99.0), 0.0);
            assertEquals(99.0, asFloat(List.of(1.0, 2.0), 99.0), 0.0);
            assertEquals(99.0, asFloat(Map.of(), 99.0), 0.0);
            assertEquals(99.0, asFloat(Map.of("a", 1.0), 99.0), 0.0);
        }

        /** The fallback is whatever was asked for, nought, negative or large. */
        @Test
        void theFallbackIsWhatWasAsked() {
            assertEquals(0.0, asFloat("abc", 0.0), 0.0);
            assertEquals(-1.0, asFloat(null, -1.0), 0.0);
            assertEquals(1e6, asFloat(Double.NaN, 1e6), 0.0);
        }
    }

    /** as_optional_float: None stays None; anything else goes through as_float with 0.0. */
    @Nested
    class AsOptionalFloat {
        @Test
        void nullStaysNull() {
            assertNull(asOptionalFloat(null));
        }

        @Test
        void aNumberIsItself() {
            assertEquals(21.5, asOptionalFloat(21.5));
            assertEquals(48.0, asOptionalFloat(48));
            assertEquals(1.0, asOptionalFloat(true));
            assertEquals(1000.0, asOptionalFloat("1e3"));
        }

        /** Not null: a broken value is nought, because only None means "never had one". */
        @Test
        void aBrokenValueIsNoughtNotNull() {
            assertEquals(0.0, asOptionalFloat("abc"));
            assertEquals(0.0, asOptionalFloat(""));
            assertEquals(0.0, asOptionalFloat(Double.NaN));
            assertEquals(0.0, asOptionalFloat(Double.POSITIVE_INFINITY));
            assertEquals(0.0, asOptionalFloat(List.of()));
            assertEquals(0.0, asOptionalFloat(Map.of()));
        }
    }

    /** plausible_temperature: float(raw) within [-50, high], or None. */
    @Nested
    class PlausibleTemperature {
        @Test
        void aTemperatureInTheRangeIsItself() {
            assertEquals(21.5, plausibleTemperature(21.5));
            assertEquals(21.0, plausibleTemperature(21), "an Integer");
            assertEquals(21.5, plausibleTemperature("21.5"), "a text, as a sensor state is");
            assertEquals(2.0, plausibleTemperature(" 2 "));
            assertEquals(-49.999, plausibleTemperature(-49.999));
        }

        @Test
        void theLimitsThemselvesAreInside() {
            assertEquals(-50.0, plausibleTemperature(-50.0));
            assertEquals(110.0, plausibleTemperature(110.0));
            assertEquals(110.0, plausibleTemperature("110"));
            assertEquals(125.0, plausibleTemperature(125.0, WIRED_MAX_C));
        }

        /** A broken resistive sensor: -273 open or shorted, -999 the outdoor divider's marker. */
        @Test
        void aBrokenSensorIsNotATemperature() {
            assertNull(plausibleTemperature(-273));
            assertNull(plausibleTemperature(-273.15));
            assertNull(plausibleTemperature(-999));
            assertNull(plausibleTemperature("-999"));
            assertNull(plausibleTemperature(-50.001));
            assertNull(plausibleTemperature(110.001));
            assertNull(plausibleTemperature(110.5));
            assertNull(plausibleTemperature("400"));
        }

        /**
         * WiredTest.test_a_wired_probe_is_believed_up_to_its_own_range: 2026-10-06 the boiler
         * reached 115.75 and, held to 110, showed as missing. The wired probes are DS18B20s,
         * good to 125; 125.5 is still over, and the outdoor sensor keeps the narrow limit.
         */
        @Test
        void aWiredProbeIsBelievedUpToItsOwnRange() {
            assertNull(plausibleTemperature("115.75"), "with the default high");
            assertEquals(115.75, plausibleTemperature("115.75", WIRED_MAX_C));
            assertEquals(125.0, plausibleTemperature("125", WIRED_MAX_C));
            assertNull(plausibleTemperature("125.5", WIRED_MAX_C));
            assertNull(plausibleTemperature("119.8"), "the outdoor sensor keeps the default");
        }

        /** float("unavailable") and float("varm") raise ValueError: no reading. */
        @Test
        void aTextThatIsNotANumberIsNoReading() {
            for (String text : List.of("unavailable", "unknown", "varm", "", " ", "21.5 C", "1,5")) {
                assertNull(plausibleTemperature(text), "'" + text + "'");
                assertNull(plausibleTemperature(text, WIRED_MAX_C), "'" + text + "' with 125");
            }
        }

        /** NaN fails both comparisons, so it is rejected too; inf fails the upper one. */
        @Test
        void nanAndInfinityAreNoReading() {
            assertNull(plausibleTemperature(Double.NaN));
            assertNull(plausibleTemperature("nan"));
            assertNull(plausibleTemperature(Double.POSITIVE_INFINITY));
            assertNull(plausibleTemperature(Double.NEGATIVE_INFINITY));
            assertNull(plausibleTemperature("inf"));
            assertNull(plausibleTemperature("-inf"));
            assertNull(plausibleTemperature(1e300));
        }

        /** float(None) raises TypeError; float(True) is 1.0, which is a temperature. */
        @Test
        void nullIsNoReadingAndABoolIsOneOrNought() {
            assertNull(plausibleTemperature(null));
            assertNull(plausibleTemperature(null, WIRED_MAX_C));
            assertNull(plausibleTemperature(List.of(1.0)));
            assertNull(plausibleTemperature(Map.of()));
            assertEquals(1.0, plausibleTemperature(true));
            assertEquals(0.0, plausibleTemperature(false));
        }
    }

    /** outdoor_in_use: the index of the coldest outdoor sensor that answers, or None. */
    @Nested
    class OutdoorInUse {
        /** OutdoorTest.test_the_coldest_that_answers_counts: the colder is the one in shade. */
        @Test
        void theColdestThatAnswersCounts() {
            assertEquals(0, outdoorInUse(readings(reading("sensor.norr", 4.0), reading("sensor.soder", 6.5))));
            assertEquals(1, outdoorInUse(readings(reading("sensor.norr", 6.5), reading("sensor.soder", 4.0))));
            assertEquals(1, outdoorInUse(readings(reading("a", 2.0), reading("b", -3.5), reading("c", 1.0))));
        }

        /** OutdoorTest.test_one_that_does_not_answer_leaves_the_other. */
        @Test
        void oneThatDoesNotAnswerLeavesTheOther() {
            assertEquals(1, outdoorInUse(readings(reading("sensor.norr", null), reading("sensor.soder", 6.5))));
            assertEquals(0, outdoorInUse(readings(reading("sensor.norr", 6.5), reading("sensor.soder", null))));
        }

        /** OutdoorTest.test_none_answering_is_no_outdoor_reading. */
        @Test
        void noneAnsweringIsNoOutdoorReading() {
            assertNull(outdoorInUse(readings()));
            assertNull(outdoorInUse(readings(reading("sensor.norr", null))));
            assertNull(outdoorInUse(readings(reading("sensor.norr", null), reading("sensor.soder", null))));
        }

        /** min((value, index)) - at the same value the lower index wins. */
        @Test
        void aTieGoesToTheEarlierInTheList() {
            assertEquals(0, outdoorInUse(readings(reading("a", 1.0), reading("b", 1.0))));
            assertEquals(1, outdoorInUse(readings(reading("a", null), reading("b", 1.0), reading("c", 1.0))));
            assertEquals(1, outdoorInUse(readings(reading("a", 10.0), reading("b", 9.9999), reading("c", 9.9999))));
        }

        /** A ten-millionth colder is colder. */
        @Test
        void theSmallestDifferenceDecides() {
            assertEquals(1, outdoorInUse(readings(reading("a", -20.0), reading("b", -20.0000001))));
            assertEquals(0, outdoorInUse(readings(reading("a", -20.0000001), reading("b", -20.0))));
        }

        /** The answer is the index in the list given, nulls counted. */
        @Test
        void theIndexCountsTheOnesThatDoNotAnswer() {
            assertEquals(3, outdoorInUse(readings(reading("a", 3.0), reading("b", null), reading("c", 3.0),
                    reading("d", 2.0))));
            assertEquals(2, outdoorInUse(readings(reading("a", null), reading("b", null), reading("c", 20.0))));
        }

        /** The price of following the coldest: a fault reading cold is followed too. */
        @Test
        void aFaultReadingColdIsFollowedToo() {
            assertEquals(0, outdoorInUse(readings(reading("a", -50.0), reading("b", 110.0))));
        }
    }

    /** Behaviour the Python tests do not pin, read off parsing.py. */
    @Nested
    class FromTheCode {
        /** The one-argument overload is the two-argument one with PLAUSIBLE_HIGH. */
        @Test
        void theShortOverloadUsesTheDefaultHigh() {
            for (Object raw : Arrays.asList(21.5, 110.0, 110.5, 115.75, "125", -273, null, "abc")) {
                assertEquals(plausibleTemperature(raw, PLAUSIBLE_HIGH), plausibleTemperature(raw),
                        String.valueOf(raw));
            }
        }

        /** The high is whatever was asked, not one of the two constants. */
        @Test
        void anyHighGoes() {
            assertEquals(30.0, plausibleTemperature(30.0, 30.0));
            assertNull(plausibleTemperature(30.5, 30.0));
            assertNull(plausibleTemperature(-50.001, 30.0), "the low never moves");
        }

        /** as_float and plausible_temperature agree on what parses; only the range differs, and the low never moves. */
        @Test
        void whatParsesForOneParsesForTheOther() {
            for (Object raw : Arrays.asList("21.5", " 2 ", "1e3", "+5", "2.", ".5", 48, true, "-49", -50.0)) {
                double parsed = asFloat(raw, Double.NaN);
                assertTrue(!Double.isNaN(parsed), String.valueOf(raw) + " parses");
                Double plausible = plausibleTemperature(raw, 1e6);
                assertNotNull(plausible, String.valueOf(raw) + " within a wide range");
                assertEquals(parsed, plausible, 0.0, String.valueOf(raw));
            }
        }

        /** Python keeps -0.0 as it is; so does Java, and the range includes it. */
        @Test
        void negativeNoughtIsANumber() {
            assertEquals(-0.0, asFloat(-0.0, 7.5), 0.0);
            assertEquals(0.0, plausibleTemperature(-0.0), 0.0);
        }

        /** outdoor_in_use reads only the values; the keys are for whoever asks afterwards. */
        @Test
        void theKeysDoNotMatter() {
            assertEquals(1, outdoorInUse(readings(reading("b", 5.0), reading("a", 4.0))));
            assertEquals(1, outdoorInUse(readings(reading("x", 5.0), reading("x", 4.0))));
            assertEquals(0, outdoorInUse(readings(reading(null, 4.0), reading("a", 5.0))));
        }
    }

    /**
     * Python's own answers, row by row from the two files verktyg/facit.py
     * wrote: as_float, as_optional_float and plausible_temperature over every
     * kind of value a stored file or a sensor state can hold, and outdoor_in_use
     * over lists of readings. An empty cell arrives as null.
     */
    @Nested
    @Tag("python")
    class SameAsPython {
        /**
         * value;fallback;as_float;as_optional_float;plausible_default;plausible_125: the value
         * as one typed cell (s/f/i/b/n/l/d), the fallback given to as_float, and the four answers,
         * empty where Python said None.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/parsing.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false)
        void sameParsing(String value, String fallback, String asFloatOut, String asOptionalFloatOut,
                         String plausibleDefault, String plausible125) {
            Object raw = typed(value);
            String what = "value " + value;
            assertEquals(required(asFloatOut), asFloat(raw, required(fallback)), 0.0, "as_float of " + what);
            assertClose(number(asOptionalFloatOut), asOptionalFloat(raw), 0.0, "as_optional_float of " + what);
            assertClose(number(plausibleDefault), plausibleTemperature(raw), 0.0,
                    "plausible_temperature of " + what);
            assertClose(number(plausible125), plausibleTemperature(raw, WIRED_MAX_C), 0.0,
                    "plausible_temperature(125) of " + what);
        }

        /**
         * readings;index: the readings as key=f:value or key=n: in order ({} for none), and the
         * index outdoor_in_use answered, empty for None.
         */
        @ParameterizedTest
        @CsvFileSource(resources = "/outdoor_in_use.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "",
                ignoreLeadingAndTrailingWhitespace = false)
        void sameOutdoorInUse(String readings, String index) {
            List<Map.Entry<String, Double>> list = new ArrayList<>();
            for (Map.Entry<String, Object> entry : dict(readings).entrySet()) {
                list.add(reading(entry.getKey(), (Double) entry.getValue()));
            }
            assertEquals(integer(index), outdoorInUse(list), "index for " + readings);
        }
    }
}
