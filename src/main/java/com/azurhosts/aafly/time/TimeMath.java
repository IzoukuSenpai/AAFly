package com.azurhosts.aafly.time;

import java.util.concurrent.TimeUnit;

/**
 * Arithmétique de durées (en secondes) avec saturation : jamais d'overflow, jamais de valeur négative.
 * Toutes les méthodes sont pures et thread-safe.
 */
public final class TimeMath {

    /** Secondes par minute. */
    public static final long MINUTE = 60L;
    /** Secondes par heure. */
    public static final long HOUR = 3_600L;
    /** Secondes par jour. */
    public static final long DAY = 86_400L;

    private TimeMath() {
    }

    /**
     * Borne {@code value} dans {@code [0, max]}.
     *
     * @param value valeur quelconque
     * @param max   borne supérieure (une valeur négative est traitée comme 0)
     * @return valeur bornée
     */
    public static long clamp(long value, long max) {
        long upper = Math.max(0L, max);
        if (value < 0L) {
            return 0L;
        }
        return Math.min(value, upper);
    }

    /**
     * Ajoute {@code delta} (positif ou négatif) à {@code current} avec saturation dans {@code [0, max]}.
     *
     * @param current valeur courante
     * @param delta   variation signée
     * @param max     borne supérieure
     * @return résultat saturé
     */
    public static long add(long current, long delta, long max) {
        long sum;
        try {
            sum = Math.addExact(current, delta);
        } catch (ArithmeticException overflow) {
            sum = delta > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return clamp(sum, max);
    }

    /**
     * Soustrait {@code amount} (traité en valeur absolue positive) de {@code current}, plancher à 0.
     *
     * @param current valeur courante (une valeur négative est traitée comme 0)
     * @param amount  quantité à retirer (une valeur négative est ignorée)
     * @return résultat ≥ 0
     */
    public static long subtract(long current, long amount) {
        long base = Math.max(0L, current);
        if (amount <= 0L) {
            return base;
        }
        return amount >= base ? 0L : base - amount;
    }

    /**
     * Multiplication saturée à {@link Long#MAX_VALUE} (opérandes négatifs traités comme 0).
     *
     * @param a premier facteur
     * @param b second facteur
     * @return produit saturé ≥ 0
     */
    public static long multiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Convertit une durée en secondes vers une unité entière (arrondi inférieur).
     * Seules SECONDS, MINUTES, HOURS et DAYS sont acceptées.
     *
     * @param seconds durée en secondes (une valeur négative donne 0)
     * @param unit    unité cible
     * @return nombre entier d'unités
     * @throws IllegalArgumentException pour une unité inférieure à la seconde
     */
    public static long toUnit(long seconds, TimeUnit unit) {
        long s = Math.max(0L, seconds);
        return switch (unit) {
            case SECONDS -> s;
            case MINUTES -> s / MINUTE;
            case HOURS -> s / HOUR;
            case DAYS -> s / DAY;
            default -> throw new IllegalArgumentException("Unité non supportée : " + unit);
        };
    }

    /**
     * Secondes restantes jusqu'à {@code targetEpochSeconds} (0 si la date est passée).
     *
     * @param targetEpochSeconds échéance (epoch, secondes)
     * @param nowEpochSeconds    instant présent (epoch, secondes)
     * @return secondes restantes ≥ 0
     */
    public static long remainingUntil(long targetEpochSeconds, long nowEpochSeconds) {
        return subtract(targetEpochSeconds, nowEpochSeconds);
    }
}
