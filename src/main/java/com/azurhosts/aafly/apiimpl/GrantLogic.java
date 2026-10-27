package com.azurhosts.aafly.apiimpl;

import com.azurhosts.aafly.api.GrantMode;
import com.azurhosts.aafly.api.GrantResult;
import java.util.Objects;

/**
 * Logique pure de {@code AAFlyApi#grant} (SPEC-phase2 B2), sans Bukkit : testable unitairement.
 *
 * <p>Ordre des vérifications : bornes de la demande ({@link IllegalArgumentException}, erreur de programmation
 * de l'appelant) &rarr; {@code DISABLED_BY_CONFIG} &rarr; {@code NOT_LOADED} &rarr; {@code UNLIMITED} &rarr;
 * sémantique du mode. L'émission de l'événement ({@code DENIED_BY_RULE}) et l'activation du vol sont faites
 * ensuite par {@link DefaultAAFlyApi}.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class GrantLogic {

    private GrantLogic() {
    }

    /**
     * État observé au moment de la demande.
     *
     * @param seconds         durée demandée
     * @param mode            mode de crédit
     * @param capSeconds      plafond demandé par l'appelant
     * @param maxSeconds      {@code limits.max-seconds}
     * @param externalAllowed {@code api.allow-external-grants}
     * @param timedFly        {@code true} si le mode timed-fly gère le temps (crédit impossible)
     * @param loaded          {@code true} si le profil du joueur est chargé
     * @param unlimited       {@code true} si le joueur a le vol illimité
     * @param current         solde actuel (secondes)
     * @param flightEnabled   {@code true} si le vol AAFly est activé (voulu)
     */
    public record Input(long seconds, GrantMode mode, long capSeconds, long maxSeconds, boolean externalAllowed,
                        boolean timedFly, boolean loaded, boolean unlimited, long current, boolean flightEnabled) {

        /**
         * @throws NullPointerException si {@code mode} est nul
         */
        public Input {
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * Décision : soit un statut terminal (rien à créditer), soit un solde cible à appliquer.
     *
     * @param status       {@code null} si {@link #targetSeconds} doit être appliqué, sinon le statut final
     * @param remaining    solde à renvoyer pour un statut terminal ({@code -1} si sans objet)
     * @param targetSeconds solde cible (&gt; solde actuel) si {@code status == null}
     */
    public record Decision(GrantResult.Status status, long remaining, long targetSeconds) {

        /** @return {@code true} si un crédit doit être appliqué */
        public boolean credit() {
            return status == null;
        }

        static Decision stop(GrantResult.Status status, long remaining) {
            return new Decision(status, remaining, -1L);
        }

        static Decision apply(long target) {
            return new Decision(null, -1L, target);
        }
    }

    /**
     * @param maxSeconds {@code limits.max-seconds}
     * @param capSeconds plafond demandé
     * @return plafond effectif {@code min(capSeconds, maxSeconds)}, jamais négatif
     */
    public static long effectiveCap(long capSeconds, long maxSeconds) {
        return Math.max(0L, Math.min(capSeconds, maxSeconds));
    }

    /**
     * @param in état observé
     * @return décision
     * @throws IllegalArgumentException si {@code seconds} est hors de {@code [1, cap]}
     */
    public static Decision decide(Input in) {
        Objects.requireNonNull(in, "in");
        long cap = effectiveCap(in.capSeconds(), in.maxSeconds());
        if (in.seconds() < 1L || in.seconds() > cap) {
            throw new IllegalArgumentException("seconds hors bornes : attendu [1, " + cap
                    + "] (min(capSeconds, limits.max-seconds))");
        }
        if (!in.externalAllowed() || in.timedFly()) {
            return Decision.stop(GrantResult.Status.DISABLED_BY_CONFIG, in.loaded() ? Math.max(0L, in.current()) : -1L);
        }
        if (!in.loaded()) {
            return Decision.stop(GrantResult.Status.NOT_LOADED, -1L);
        }
        long current = Math.max(0L, in.current());
        if (in.unlimited()) {
            return Decision.stop(GrantResult.Status.UNLIMITED, -1L);
        }
        return switch (in.mode()) {
            case EXTEND -> {
                long target = Math.min(saturatedAdd(current, in.seconds()), cap);
                // Un solde déjà au-dessus du plafond n'est jamais réduit.
                yield target <= current ? Decision.stop(GrantResult.Status.CAP_REACHED, current) : Decision.apply(target);
            }
            case REFRESH -> in.seconds() <= current
                    ? Decision.stop(GrantResult.Status.ALREADY_ACTIVE, current)
                    : Decision.apply(in.seconds());
            case DENY_IF_ACTIVE -> in.flightEnabled() || current > 0L
                    ? Decision.stop(GrantResult.Status.ALREADY_ACTIVE, current)
                    : Decision.apply(in.seconds());
        };
    }

    private static long saturatedAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }
}
