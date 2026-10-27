package com.azurhosts.aafly.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DurationParserTest {

    @Test
    void entierEnSecondes() {
        assertEquals(OptionalLong.of(90L), DurationParser.parse("90"));
        assertEquals(OptionalLong.of(0L), DurationParser.parse("0"));
        assertEquals(OptionalLong.of(5L), DurationParser.parse("  5 "));
    }

    @Test
    void formatComposite() {
        assertEquals(OptionalLong.of(86_400L + 7_200L + 1_800L + 15L), DurationParser.parse("1d2h30m15s"));
        assertEquals(OptionalLong.of(9_000L), DurationParser.parse("2h 30m"));
        assertEquals(OptionalLong.of(2_700L), DurationParser.parse("45M"));
        assertEquals(OptionalLong.of(172_800L), DurationParser.parse("2j"));
        assertEquals(OptionalLong.of(3_601L), DurationParser.parse("1s1h"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "-5", "1x", "h", "1h1h", "1d2j", "1.5h", "10m5", "+3",
            "999999999999999999999", "99999999999999d", "1h-2m", "１２"})
    void entreesInvalides(String input) {
        assertTrue(DurationParser.parse(input).isEmpty(), () -> "devrait être rejeté : " + input);
    }

    @Test
    void nullEtTropLong() {
        assertTrue(DurationParser.parse(null).isEmpty());
        assertTrue(DurationParser.parse("1".repeat(DurationParser.MAX_INPUT_LENGTH + 1)).isEmpty());
    }

    @Test
    void depassementDeCapacite() {
        // 106751991167300 jours dépasse Long.MAX_VALUE secondes.
        assertTrue(DurationParser.parse("999999999999d").isPresent());
        assertTrue(DurationParser.parse("9223372036854775807").isPresent());
        assertTrue(DurationParser.parse("9223372036854775808").isEmpty());
    }

    @Test
    void bornes() {
        assertEquals(OptionalLong.of(10L), DurationParser.parseBounded("10", 1, 100));
        assertTrue(DurationParser.parseBounded("0", 1, 100).isEmpty());
        assertTrue(DurationParser.parseBounded("101", 1, 100).isEmpty());
        assertTrue(DurationParser.parseBounded("zz", 1, 100).isEmpty());
    }

    @Test
    void suggestions() {
        assertEquals(java.util.List.of("10s", "10m", "10h", "10d"), DurationParser.suggestions("10"));
        assertTrue(DurationParser.suggestions("1h").isEmpty());
        assertEquals(4, DurationParser.suggestions("").size());
    }
}
