package se.sodalen.heating;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Counters that can run away are shown in the next larger unit as they grow
 * (Fredrik 2026-10-08): minutes, hours, days, weeks, months, years. 311 hours
 * on a card is a number nobody reads; "1,9 veckor" is a fact.
 *
 * <p>Python: durations.py and tests/test_durations.py.
 */
class DurationsTest {

    static String words(Double hours) {
        return Durations.durationWords(hours);
    }

    @Nested
    class TheUnit {
        @Test
        void underAnHourInMinutes() {
            assertEquals("45 min", words(0.75));
        }

        @Test
        void underADayInHours() {
            assertEquals("2,5 h", words(2.5));
            assertEquals("13 h", words(13.4));
        }

        @Test
        void underAWeekInDays() {
            assertEquals("1,8 dagar", words(42.0));
            assertEquals("1 dag", words(24.0));
            assertEquals("6,3 dagar", words(151.0));
        }

        @Test
        void underAMonthInWeeks() {
            assertEquals("1,1 veckor", words(190.0));
            assertEquals("1 vecka", words(168.0));
        }

        @Test
        void underAYearInMonths() {
            assertEquals("1,5 månader", words(24 * 45.0));
            assertEquals("1 månad", words(24 * 30.44));
        }

        @Test
        void beyondThatInYears() {
            assertEquals("1 år", words(24 * 365.25));
            assertEquals("12 år", words(24 * 365.25 * 12));
        }

        @Test
        void fromTenWholeNumbers() {
            assertEquals("12 h", words(12.4));
            assertEquals("9,9 h", words(9.94));
        }

        /** Thirteen days is more than a week, so it is said in weeks. */
        @Test
        void houseModels311Hours() {
            assertEquals("1,9 veckor", words(311.0));
        }
    }

    @Nested
    class TheEdges {
        @Test
        void nothingIsADash() {
            assertEquals("–", words(null));
        }

        @Test
        void neverBelowNought() {
            assertEquals("0 min", words(-2.0));
        }

        /** 59.8 minutes rounds to 60: one hour, not "60 min". */
        @Test
        void aRoundThatReachesTheNextUnitIsSaidInIt() {
            assertEquals("1 h", words(59.8 / 60.0));
            assertEquals("1 dag", words(23.97));
        }

        /** As a person rounds. Python's round() takes 1.25 to 1.2; so would a careless Java one. */
        @Test
        void halvesGoUp() {
            assertEquals("1,3 dagar", words(30.0));
            assertEquals("6,3 dagar", words(150.0));
        }

        @Test
        void secondsToo() {
            assertEquals("45 min", Durations.durationWordsSeconds(2700.0));
            assertEquals("–", Durations.durationWordsSeconds(null));
        }
    }

    /**
     * The decimal comma is the text's, not the computer's: sodalenserver may
     * well run with an English locale. Egyptian Arabic writes its own digits
     * and its own decimal sign (٢٫٥), which no replace(".", ",") mends.
     */
    @Nested
    class WhateverTheMachinesLanguage {
        private final Locale before = Locale.getDefault();

        @AfterEach
        void restore() {
            Locale.setDefault(before);
        }

        @Test
        void englishStillSaysAComma() {
            Locale.setDefault(Locale.US);
            assertEquals("2,5 h", words(2.5));
        }

        @Test
        void swedishToo() {
            Locale.setDefault(Locale.of("sv", "SE"));
            assertEquals("2,5 h", words(2.5));
        }

        /** Every unit, with a decimal and without, as Python says them. */
        @ParameterizedTest(name = "{0}: {1} h -> {2}")
        @CsvSource(delimiter = ';', value = {
            "en-US;0.75;45 min",
            "en-US;2.5;2,5 h",
            "en-US;13.4;13 h",
            "en-US;36.0;1,5 dagar",
            "en-US;252.0;1,5 veckor",
            "en-US;1080.0;1,5 månader",
            "en-US;13149.0;1,5 år",
            "en-US;105192.0;12 år",
            "ar-EG;0.75;45 min",
            "ar-EG;2.5;2,5 h",
            "ar-EG;13.4;13 h",
            "ar-EG;36.0;1,5 dagar",
            "ar-EG;252.0;1,5 veckor",
            "ar-EG;1080.0;1,5 månader",
            "ar-EG;13149.0;1,5 år",
            "ar-EG;105192.0;12 år",
        })
        void everyUnit(String language, double hours, String expected) {
            Locale.setDefault(Locale.forLanguageTag(language));
            assertEquals(expected, words(hours));
        }

        @Test
        void secondsToo() {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("45 min", Durations.durationWordsSeconds(2700.0));
        }
    }

    /** 2000 cases worked out by the Python version (verktyg/facit.py). */
    @Nested
    class SameAsPython {
        @ParameterizedTest(name = "{0} h -> {1}")
        @CsvFileSource(resources = "/durations.csv", delimiter = ';', numLinesToSkip = 1)
        void sameWords(double hours, String expected) {
            assertEquals(expected, words(hours));
        }
    }
}
