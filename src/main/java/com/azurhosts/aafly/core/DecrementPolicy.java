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
