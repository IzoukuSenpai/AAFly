package com.azurhosts.aafly.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Analyse des durées saisies par les joueurs/administrateurs.
 *
 * <p>Formats acceptés (insensibles à la casse, espaces ignorés) :
 * <ul>
 *   <li>un entier positif = secondes : {@code 90} ;</li>
 *   <li>une suite de groupes {@code <nombre><unité>} avec les unités {@code d} (ou {@code j}),
 *       {@code h}, {@code m}, {@code s}, chacune au plus une fois, dans n'importe quel ordre :
 *       {@code 1d2h30m15s}, {@code 2h 30m}, {@code 45m}.</li>
 * </ul>
 * Toute entrée invalide, trop longue ou provoquant un dépassement de capacité est rejetée
 * (résultat vide). Aucune exception n'est levée sur une entrée utilisateur.
 */
public final class DurationParser {

    /** Longueur maximale d'une saisie (protection contre les entrées abusives). */
    public static final int MAX_INPUT_LENGTH = 32;
    /** Nombre maximal de chiffres par groupe. */
    private static final int MAX_DIGITS = 12;

    private DurationParser() {
    }

    /**
     * Analyse une durée.
     *
     * @param input saisie brute (peut être {@code null})
     * @return la durée en secondes (≥ 0), ou vide si la saisie est invalide
     */
    public static OptionalLong parse(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INPUT_LENGTH) {
            return OptionalLong.empty();
        }
        String s = stripSpaces(trimmed).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        if (isAllDigits(s)) {
            if (s.length() > 19) { // Long.MAX_VALUE compte 19 chiffres
                return OptionalLong.empty();
            }
            try {
                return OptionalLong.of(Long.parseLong(s));
            } catch (NumberFormatException overflow) {
                return OptionalLong.empty();
            }
        }
        long total = 0L;
        boolean seenD = false;
        boolean seenH = false;
        boolean seenM = false;
        boolean seenS = false;
        int i = 0;
        int len = s.length();
        try {
            while (i < len) {
                int start = i;
                while (i < len && isAsciiDigit(s.charAt(i))) {
                    i++;
                }
                int digits = i - start;
                if (digits == 0 || digits > MAX_DIGITS || i >= len) {
                    return OptionalLong.empty();
                }
                long value = Long.parseLong(s, start, i, 10);
                char unit = s.charAt(i++);
                long factor;
                switch (unit) {
                    case 'd', 'j' -> {
                        if (seenD) {
                            return OptionalLong.empty();
                        }
                        seenD = true;
                        factor = TimeMath.DAY;
                    }
                    case 'h' -> {
                        if (seenH) {
                            return OptionalLong.empty();
                        }
                        seenH = true;
                        factor = TimeMath.HOUR;
                    }
                    case 'm' -> {
                        if (seenM) {
                            return OptionalLong.empty();
                        }
                        seenM = true;
                        factor = TimeMath.MINUTE;
                    }
                    case 's' -> {
                        if (seenS) {
                            return OptionalLong.empty();
                        }
                        seenS = true;
                        factor = 1L;
                    }
                    default -> {
                        return OptionalLong.empty();
                    }
                }
                total = Math.addExact(total, Math.multiplyExact(value, factor));
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total);
    }

    /**
     * Analyse une durée et vérifie qu'elle appartient à {@code [min, max]}.
     *
     * @param input saisie brute
     * @param min   borne inférieure incluse (en pratique ≥ 1)
     * @param max   borne supérieure incluse
     * @return la durée, ou vide si invalide ou hors bornes
     */
    public static OptionalLong parseBounded(String input, long min, long max) {
        OptionalLong parsed = parse(input);
        if (parsed.isEmpty()) {
            return parsed;
        }
        long v = parsed.getAsLong();
        return v < min || v > max ? OptionalLong.empty() : parsed;
    }

    /**
     * Suggestions de tab-complétion pour une saisie partielle (ex. {@code "10"} → {@code 10s, 10m, 10h, 10d}).
     *
     * @param partial saisie partielle (peut être vide)
     * @return suggestions (jamais {@code null})
     */
    public static List<String> suggestions(String partial) {
        List<String> out = new ArrayList<>();
        String p = partial == null ? "" : partial.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            out.add("30s");
            out.add("10m");
            out.add("1h");
            out.add("1d");
            return out;
        }
        if (p.length() >= MAX_INPUT_LENGTH) {
            return out;
        }
        char last = p.charAt(p.length() - 1);
        if (isAsciiDigit(last)) {
            for (String unit : new String[]{"s", "m", "h", "d"}) {
                String candidate = p + unit;
                if (parse(candidate).isPresent()) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    private static String stripSpaces(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isAsciiDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
