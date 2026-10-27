package com.azurhosts.aafly.migration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Ligne brute lue dans une source de migration (wFly, TempFly), <b>non validée</b> : les valeurs sont
 * conservées telles que lues (texte, nombre, booléen) et passent par {@link #validate(long)} avant tout import.
 *
 * @param uuid    identifiant du joueur tel que lu
 * @param seconds temps restant (secondes) tel que lu
 * @param flying  état « en vol » tel que lu (peut être absent)
 */
public record SourceRow(@Nullable Object uuid, @Nullable Object seconds, @Nullable Object flying) {

    private static final Pattern DASHED_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern COMPACT_UUID = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,19}$");

    /**
     * Ligne validée, prête à importer.
     *
     * @param uuid    joueur
     * @param seconds temps restant, dans {@code [0, limits.max-seconds]}
     * @param flying  vol voulu
     */
    public record Valid(UUID uuid, long seconds, boolean flying) {
    }

    /**
     * Valide la ligne : UUID au format canonique (avec ou sans tirets), temps entier ≥ 0 et ≤ {@code maxSeconds}.
     * L'état de vol est interprété avec indulgence ({@code true/false}, {@code 1/0}) et vaut {@code false} sinon.
     *
     * @param maxSeconds plafond {@code limits.max-seconds}
     * @return ligne validée, ou vide si la ligne est invalide
     */
    public Optional<Valid> validate(long maxSeconds) {
        UUID id = parseUuid(uuid);
        if (id == null) {
            return Optional.empty();
        }
        Long s = parseSeconds(seconds);
        if (s == null || s < 0L || s > maxSeconds) {
            return Optional.empty();
        }
        return Optional.of(new Valid(id, s, parseFlying(flying)));
    }

    static @Nullable UUID parseUuid(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        if (COMPACT_UUID.matcher(s).matches()) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                    + s.substring(16, 20) + "-" + s.substring(20);
        }
        if (!DASHED_UUID.matcher(s).matches()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static @Nullable Long parseSeconds(@Nullable Object raw) {
        return switch (raw) {
            case null -> null;
            case Long l -> l;
            case Integer i -> i.longValue();
            case Short sh -> sh.longValue();
            case Byte b -> b.longValue();
            case BigInteger bi -> bi.bitLength() < 64 ? bi.longValue() : null;
            case BigDecimal bd -> {
                try {
                    yield bd.longValueExact();
                } catch (ArithmeticException e) {
                    yield null;
                }
            }
            case Double d -> integral(d);
            case Float f -> integral(f.doubleValue());
            case String str -> {
                String t = str.trim();
                if (!DIGITS.matcher(t).matches()) {
                    yield null;
                }
                try {
                    yield Long.parseLong(t);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private static @Nullable Long integral(double d) {
        if (!Double.isFinite(d) || d != Math.rint(d) || Math.abs(d) >= 9.0E18) {
            return null;
        }
        return (long) d;
    }

    static boolean parseFlying(@Nullable Object raw) {
        return switch (raw) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.longValue() == 1L;
            default -> {
                String t = raw.toString().trim().toLowerCase(Locale.ROOT);
                yield t.equals("true") || t.equals("1");
            }
        };
    }
}
