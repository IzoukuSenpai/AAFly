package com.azurhosts.aafly.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.azurhosts.aafly.config.Settings;
import org.junit.jupiter.api.Test;

class TimeFormatterTest {

    private static final Settings.UnitsSection PLAIN_UNITS =
            new Settings.UnitsSection("%seconds%s", "%minutes%m", "%hours%h", "%days%d");

    private static TimeFormatter formatter(boolean s, boolean m, boolean h, boolean d, boolean auto) {
        return new TimeFormatter(new Settings.FormatSection(s, m, h, d, auto, "Illimité", "0s",
                "%days% %hours% %minutes% %seconds%", PLAIN_UNITS));
    }

    @Test
    void autoFormatMasqueLesZeros() {
        TimeFormatter f = formatter(true, true, true, true, true);
        assertEquals("1d 2h 3m 4s", f.format(86_400L + 7_200L + 180L + 4L));
        assertEquals("1h 5s", f.format(3_605L));
        assertEquals("45s", f.format(45L));
        assertEquals("2d", f.format(172_800L));
    }

    @Test
    void sansAutoFormat() {
        TimeFormatter f = formatter(true, true, true, true, false);
        assertEquals("0d 1h 0m 5s", f.format(3_605L));
    }

    @Test
    void valeurNulleOuNegative() {
        TimeFormatter f = formatter(true, true, true, true, true);
        assertEquals("0s", f.format(0L));
        assertEquals("0s", f.format(-10L));
    }

    @Test
    void uniteDesactiveeNePerdPasDeTemps() {
        // Jours désactivés : 1 j 1 h -> 25 h.
        TimeFormatter f = formatter(true, true, true, false, true);
        assertEquals("25h", f.format(90_000L));
    }

    @Test
    void secondesDesactivees() {
        TimeFormatter f = formatter(false, true, true, true, true);
        assertEquals("1m", f.format(119L));
        assertEquals("0m", f.format(30L));
    }

    @Test
    void illimite() {
        TimeFormatter f = formatter(true, true, true, true, true);
        assertEquals("Illimité", f.format(10L, true));
        assertEquals("10s", f.format(10L, false));
        assertEquals("Illimité", f.unlimited());
    }

    @Test
    void gabaritParDefaut() {
        TimeFormatter f = new TimeFormatter(Settings.FormatSection.defaults());
        assertEquals("<yellow>1<gold>m <yellow>30<gold>s", f.format(90L));
    }

    @Test
    void grandesValeurs() {
        TimeFormatter f = formatter(true, true, true, true, true);
        assertEquals("106751991167300d 15h 30m 7s", f.format(Long.MAX_VALUE));
    }
}
