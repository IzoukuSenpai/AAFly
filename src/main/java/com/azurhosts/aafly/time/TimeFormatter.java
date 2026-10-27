package com.azurhosts.aafly.time;

import com.azurhosts.aafly.config.Settings;
import java.util.Objects;

/**
 * Mise en forme des durées selon la section {@code format} de la configuration.
 *
 * <p>Règles :
 * <ul>
 *   <li>les unités désactivées ne « perdent » pas de temps : leur valeur est reportée sur l'unité
 *       activée immédiatement inférieure (jours désactivés → heures &gt; 23 possibles) ;</li>
 *   <li>sous la plus petite unité activée, le reste est tronqué ;</li>
 *   <li>{@code auto-format} masque les unités à zéro ;</li>
 *   <li>une durée nulle donne {@code format.empty}.</li>
 * </ul>
 * Le résultat est une chaîne MiniMessage issue de la configuration (fiable) dans laquelle seuls des
 * nombres sont insérés : il peut être parsé sans risque d'injection. Classe immuable, thread-safe.
 */
public final class TimeFormatter {

    private final Settings.FormatSection format;

    /**
     * @param format section {@code format} validée
     */
    public TimeFormatter(Settings.FormatSection format) {
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * @return la section de configuration utilisée
     */
    public Settings.FormatSection settings() {
        return format;
    }

    /**
     * Formate une durée.
     *
     * @param seconds durée en secondes (valeur négative traitée comme 0)
     * @return chaîne MiniMessage formatée
     */
    public String format(long seconds) {
        long remaining = Math.max(0L, seconds);
        if (remaining == 0L) {
            return format.empty();
        }
        boolean anyUnit = format.days() || format.hours() || format.minutes() || format.seconds();
        if (!anyUnit) {
            // Configuration refusée par ConfigManager ; repli défensif.
            return Long.toString(remaining) + "s";
        }

        long days = 0L;
        long hours = 0L;
        long minutes = 0L;
        long secs = 0L;
        if (format.days()) {
            days = remaining / TimeMath.DAY;
            remaining %= TimeMath.DAY;
        }
        if (format.hours()) {
            hours = remaining / TimeMath.HOUR;
            remaining %= TimeMath.HOUR;
        }
        if (format.minutes()) {
            minutes = remaining / TimeMath.MINUTE;
            remaining %= TimeMath.MINUTE;
        }
        if (format.seconds()) {
            secs = remaining;
        }

        boolean auto = format.autoFormat();
        String dStr = format.days() && (!auto || days > 0) ? unit(format.units().days(), "%days%", days) : "";
        String hStr = format.hours() && (!auto || hours > 0) ? unit(format.units().hours(), "%hours%", hours) : "";
        String mStr = format.minutes() && (!auto || minutes > 0)
                ? unit(format.units().minutes(), "%minutes%", minutes) : "";
        String sStr = format.seconds() && (!auto || secs > 0)
                ? unit(format.units().seconds(), "%seconds%", secs) : "";

        if (dStr.isEmpty() && hStr.isEmpty() && mStr.isEmpty() && sStr.isEmpty()) {
            // Durée non nulle mais inférieure à la plus petite unité affichée : on montre « 0<unité> ».
            return smallestUnitZero();
        }

        String out = format.pattern()
                .replace("%days%", dStr)
                .replace("%hours%", hStr)
                .replace("%minutes%", mStr)
                .replace("%seconds%", sStr);
        return collapseSpaces(out);
    }

    /**
     * Formate une durée ou renvoie le libellé « illimité ».
     *
     * @param seconds   durée en secondes
     * @param unlimited {@code true} si le joueur a le vol illimité
     * @return chaîne MiniMessage formatée
     */
    public String format(long seconds, boolean unlimited) {
        return unlimited ? format.unlimited() : format(seconds);
    }

    /**
     * @return le libellé configuré pour un temps illimité
     */
    public String unlimited() {
        return format.unlimited();
    }

    private String smallestUnitZero() {
        if (format.seconds()) {
            return unit(format.units().seconds(), "%seconds%", 0L);
        }
        if (format.minutes()) {
            return unit(format.units().minutes(), "%minutes%", 0L);
        }
        if (format.hours()) {
            return unit(format.units().hours(), "%hours%", 0L);
        }
        return unit(format.units().days(), "%days%", 0L);
    }

    private static String unit(String template, String token, long value) {
        return template.replace(token, Long.toString(value));
    }

    private static String collapseSpaces(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                if (!lastSpace) {
                    sb.append(c);
                }
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        return sb.toString().trim();
    }
}
