package se.sodalen.heating.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.sodalen.heating.house.Stored.required;
import static se.sodalen.heating.house.Texts.EMERGENCY_NAMES;
import static se.sodalen.heating.house.Texts.MODE_REASONS;
import static se.sodalen.heating.house.Texts.SWEEP_REASON;
import static se.sodalen.heating.house.Texts.emergencyReason;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * The sentences the controller says about its mode, the three it needs of
 * texts.py now: the reason for each mode, the emergency's names and status
 * line, and what is said instead while a sweep keeps the burner off.
 * Swedish, because it goes straight onto a dashboard the family reads. The
 * rest of texts.py comes with the steps that say it.
 *
 * <p>Python: texts.py (MODE_REASONS, EMERGENCY_NAMES, emergency_reason,
 * SWEEP_REASON).
 */
class TextsTest {

    @Nested
    class TheReasons {
        @Test
        void oneForEveryModeInPythonsOrder() {
            assertEquals(List.of(Model.MODE_OFF, Model.MODE_SUMMER, Model.MODE_WARM, Model.MODE_HEATING, Model.MODE_HOLD,
                    Model.MODE_FAILSAFE, Model.MODE_COAST, Model.MODE_EMERGENCY), List.copyOf(MODE_REASONS.keySet()));
        }

        @Test
        void wordForWord() {
            assertEquals(List.of("Värmestyrningen är avstängd", "Sommarstopp - för varmt ute", "Huset är varmare än målet",
                    "Reglerar mot beräknad tilloppstemperatur", "Tilloppsgivaren svarar inte - håller ventilläget",
                    "Tilloppsgivaren är borta - kör max värme", "Huset klarar sig utan värme ett tag", "Nödkörning"),
                    List.copyOf(MODE_REASONS.values()));
        }

        @Test
        void theEmergencysNamesInTheOrderTheyAreOffered() {
            assertEquals(List.of(Model.EMERGENCY_OFF, Model.EMERGENCY_VALVE, Model.EMERGENCY_FLOW),
                    List.copyOf(EMERGENCY_NAMES.keySet()));
            assertEquals(List.of("Av", "Fast ventilläge", "Fast tillopp"), List.copyOf(EMERGENCY_NAMES.values()));
        }

        /** Said instead of the mode's reason while a sweep keeps the burner off: the mode carries on underneath. */
        @Test
        void theSweep() {
            assertEquals("Sotning - brännaren är av tills sotningen är utförd", SWEEP_REASON);
        }

        @Test
        void notForChanging() {
            assertThrows(UnsupportedOperationException.class, () -> MODE_REASONS.put("x", "y"));
            assertThrows(UnsupportedOperationException.class, () -> EMERGENCY_NAMES.put("x", "y"));
        }
    }

    /** emergency_reason: the status line while the house is run by hand - with the number, so nobody has to go looking. */
    @Nested
    class TheEmergency {
        @Test
        void aFixedValve() {
            assertEquals("Nödkörning - fast ventilläge 65 %", emergencyReason(Model.EMERGENCY_VALVE, 65.0, 35.0));
        }

        @Test
        void aFixedSupply() {
            assertEquals("Nödkörning - fast tillopp 35 °C", emergencyReason(Model.EMERGENCY_FLOW, 65.0, 35.0));
        }

        @Test
        void anyOtherKindIsTheModesReason() {
            for (String kind : new String[] {Model.EMERGENCY_OFF, "x", "VALVE", null}) {
                assertEquals("Nödkörning", emergencyReason(kind, 65.0, 35.0), String.valueOf(kind));
            }
        }

        @Test
        void wholeNumbersRoundedToTheNearest() {
            assertEquals("Nödkörning - fast ventilläge 65 %", emergencyReason(Model.EMERGENCY_VALVE, 64.6, 35.0));
            assertEquals("Nödkörning - fast ventilläge 64 %", emergencyReason(Model.EMERGENCY_VALVE, 64.4, 35.0));
            assertEquals("Nödkörning - fast tillopp 36 °C", emergencyReason(Model.EMERGENCY_FLOW, 0.0, 35.7));
        }

        /** f"{x:.0f}" rounds half to even, as Python's round does: 50.5 is 50, 51.5 is 52. Java's %.0f rounds half up. */
        @Test
        void aHalfGoesToTheEvenOne() {
            assertEquals("Nödkörning - fast ventilläge 50 %", emergencyReason(Model.EMERGENCY_VALVE, 50.5, 35.0));
            assertEquals("Nödkörning - fast ventilläge 52 %", emergencyReason(Model.EMERGENCY_VALVE, 51.5, 35.0));
            assertEquals("Nödkörning - fast ventilläge 0 %", emergencyReason(Model.EMERGENCY_VALVE, 0.5, 35.0));
            assertEquals("Nödkörning - fast tillopp 44 °C", emergencyReason(Model.EMERGENCY_FLOW, 0.0, 44.5));
            assertEquals("Nödkörning - fast tillopp 46 °C", emergencyReason(Model.EMERGENCY_FLOW, 0.0, 45.5));
        }
    }

    @Nested
    @Tag("python")
    class SameAsPython {
        @ParameterizedTest
        @CsvFileSource(resources = "/emergency_reason.csv", delimiter = ';', numLinesToSkip = 1, nullValues = "")
        void sameReason(String kind, String position, String flow, String expected) {
            assertEquals(expected, emergencyReason(kind, required(position), required(flow)),
                    kind + " " + position + " " + flow);
        }
    }
}
