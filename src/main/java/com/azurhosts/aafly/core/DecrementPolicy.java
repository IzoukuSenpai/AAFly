package com.azurhosts.aafly.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Politique de décompte du temps de vol (SPEC §4 « FlightTicker », étapes 2 et 3) : décide, pour un tick,
 * si une seconde doit être retirée à un joueur dont le vol est actif. Classe pure (aucun appel serveur),
 * testable isolément ; le ticker lui fournit l'état déjà lu du joueur.
 *
 * <p>Ordre des tests (du moins coûteux au plus coûteux) :
 * <ol>
 *   <li>{@code allowFlight} retiré (par un autre plugin) : pas de décompte ;</li>
 *   <li>{@link DecrementMode#FLYING_MODE} : pas de décompte si le joueur ne vole pas réellement ;</li>
 *   <li>{@code decrement.disable-when-static} : pas de décompte si aucun mouvement depuis
 *       {@code static-delay-seconds} (minimum 1 s) ;</li>
 *   <li>contournement {@code aafly.bypass.rules} : pas de décompte si {@code rules.bypass-no-decrement},
 *       sinon décompte (les {@code decrement-rules} ne s'appliquent pas aux joueurs qui contournent) ;</li>
 *   <li>{@code decrement-rules} : pas de décompte si une règle correspond (évaluation paresseuse).</li>
 * </ol>
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class DecrementPolicy {

    private DecrementPolicy() {
    }

    /**
     * Exemption de décompte (SPEC-phase2 A4), par ordre de priorité. Un opérateur n'est <b>pas</b> exempté
     * implicitement : il ne l'est que s'il est illimité ({@code aafly.unlimited} explicite ou
     * {@code flight.unlimited-for-op: true}) ou s'il contourne explicitement les règles.
     */
    public enum Exemption {
        /** Aucune exemption : la politique de décompte s'applique. */
        NONE,
        /** Mode spectateur (vol natif). */
        SPECTATOR,
        /** Mode créatif (vol natif). */
        CREATIVE,
        /** Vol illimité. */
        UNLIMITED,
        /** Contournement des règles avec {@code rules.bypass-no-decrement: true}. */
        BYPASS
    }

    /**
     * @param unlimited         joueur illimité (déjà calculé, OP compris selon {@code flight.unlimited-for-op})
     * @param creative          mode créatif
     * @param spectator         mode spectateur
     * @param bypass            contournement des règles (permission explicite)
     * @param bypassNoDecrement {@code rules.bypass-no-decrement}
     * @return exemption applicable ({@link Exemption#NONE} si le temps doit suivre la politique normale)
     */
    public static Exemption exemption(boolean unlimited, boolean creative, boolean spectator, boolean bypass,
                                      boolean bypassNoDecrement) {
        if (spectator) {
            return Exemption.SPECTATOR;
        }
        if (creative) {
            return Exemption.CREATIVE;
        }
        if (unlimited) {
            return Exemption.UNLIMITED;
        }
        if (bypass && bypassNoDecrement) {
            return Exemption.BYPASS;
        }
        return Exemption.NONE;
    }

    /**
     * Règle d'attribution d'une permission spéciale ({@code aafly.unlimited}, {@code aafly.bypass.rules}…) :
     * un opérateur n'hérite pas de la valeur par défaut « OP », la permission doit lui être explicitement
     * accordée.
     *
     * @param hasPermission {@code Permissible#hasPermission}
     * @param op            joueur opérateur
     * @param explicitlySet {@code Permissible#isPermissionSet}
     * @return {@code true} si la permission est réellement accordée
     */
    public static boolean permissionGranted(boolean hasPermission, boolean op, boolean explicitlySet) {
        return hasPermission && (!op || explicitlySet);
    }

    /**
     * Variante de coexistence (SPEC-phase2 B3) : aucun décompte si le vol physique n'a pas été accordé par
     * AAFly (vol d'un autre plugin), sinon
     * {@link #shouldDecrement(DecrementMode, boolean, boolean, boolean, long, long, long, boolean, boolean,
     * BooleanSupplier)}.
     *
     * @param ownsFlight           AAFly possède le vol physique du joueur
     * @param mode                 mode de décompte
     * @param allowFlight          {@code Player#getAllowFlight()}
     * @param flying               {@code Player#isFlying()}
     * @param disableWhenStatic    {@code decrement.disable-when-static}
     * @param staticDelaySeconds   {@code decrement.static-delay-seconds}
     * @param lastMoveEpochMillis  dernier mouvement de bloc (ms epoch)
     * @param nowMillis            instant courant (ms epoch)
     * @param bypass               contournement des règles
     * @param bypassNoDecrement    {@code rules.bypass-no-decrement}
     * @param decrementRuleMatches évaluation paresseuse des {@code decrement-rules}
     * @return {@code true} si une seconde doit être décomptée
     */
    public static boolean shouldDecrement(boolean ownsFlight, DecrementMode mode, boolean allowFlight, boolean flying,
                                          boolean disableWhenStatic, long staticDelaySeconds,
                                          long lastMoveEpochMillis, long nowMillis,
                                          boolean bypass, boolean bypassNoDecrement,
                                          BooleanSupplier decrementRuleMatches) {
        if (!ownsFlight) {
            return false;
        }
        return shouldDecrement(mode, allowFlight, flying, disableWhenStatic, staticDelaySeconds,
                lastMoveEpochMillis, nowMillis, bypass, bypassNoDecrement, decrementRuleMatches);
    }

    /**
     * @param mode                    mode de décompte ({@code decrement.mode})
     * @param allowFlight             {@code Player#getAllowFlight()}
     * @param flying                  {@code Player#isFlying()}
     * @param disableWhenStatic       {@code decrement.disable-when-static}
     * @param staticDelaySeconds      {@code decrement.static-delay-seconds} (ramené à 1 minimum)
     * @param lastMoveEpochMillis     dernier mouvement de bloc mémorisé (ms epoch)
     * @param nowMillis               instant courant (ms epoch)
     * @param bypass                  le joueur contourne les règles
     * @param bypassNoDecrement       {@code rules.bypass-no-decrement}
     * @param decrementRuleMatches    {@code true} si une {@code decrement-rule} correspond ; appelé au plus une fois,
     *                                et seulement si les tests précédents n'ont pas tranché
     * @return {@code true} si une seconde doit être décomptée
     */
    public static boolean shouldDecrement(DecrementMode mode, boolean allowFlight, boolean flying,
                                          boolean disableWhenStatic, long staticDelaySeconds,
                                          long lastMoveEpochMillis, long nowMillis,
                                          boolean bypass, boolean bypassNoDecrement,
                                          BooleanSupplier decrementRuleMatches) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(decrementRuleMatches, "decrementRuleMatches");
        if (!allowFlight) {
            return false;
        }
        if (mode == DecrementMode.FLYING_MODE && !flying) {
            return false;
        }
        if (disableWhenStatic && isStatic(lastMoveEpochMillis, nowMillis, staticDelaySeconds)) {
            return false;
        }
        if (bypass) {
            return !bypassNoDecrement;
        }
        return !decrementRuleMatches.getAsBoolean();
    }

    /**
     * @param lastMoveEpochMillis dernier mouvement (ms epoch)
     * @param nowMillis           instant courant (ms epoch)
     * @param staticDelaySeconds  délai d'immobilité (ramené à 1 minimum)
     * @return {@code true} si le joueur est immobile depuis au moins le délai
     */
    static boolean isStatic(long lastMoveEpochMillis, long nowMillis, long staticDelaySeconds) {
        long delay = Math.max(1L, staticDelaySeconds);
        long delayMillis = delay >= Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : delay * 1000L;
        return nowMillis - lastMoveEpochMillis >= delayMillis;
    }
}
