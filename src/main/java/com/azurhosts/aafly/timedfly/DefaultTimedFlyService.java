package com.azurhosts.aafly.timedfly;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.time.TimeMath;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * Implémentation du mode timed-fly (SPEC §4).
 *
 * <p>Cycle d'un joueur :
 * <ol>
 *   <li>Profil neuf (temps 0, aucune recharge) : {@link #refillIfReady} crédite immédiatement le
 *       {@code fly-time} du groupe.</li>
 *   <li>Le joueur vole et consomme son temps (décompte normal du ticker).</li>
 *   <li>Le vol se coupe (expiration ou désactivation) : {@link #onFlightStopped} démarre la recharge
 *       ({@code cooldownUntil = now + cooldown}) et remet le temps à 0 si {@code reset-after-disable}.</li>
 *   <li>Recharge écoulée et vol coupé : {@link #refillIfReady} remet le temps à {@code fly-time}.</li>
 * </ol>
 * La recharge n'est jamais appliquée pendant un vol actif (sinon le vol deviendrait infini). Un temps
 * supérieur au {@code fly-time} du groupe (ancien solde) n'est jamais réduit.
 *
 * <p>{@code cooldownUntilEpochSeconds == 0} signifie « aucune recharge en cours ». Toutes les opérations
 * passent par {@link TimeMath} (saturation, jamais négatif). Main thread uniquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class DefaultTimedFlyService implements TimedFlyService {

    private final GroupResolver resolver;
    private volatile Settings settings;

    /**
     * @param settings réglages validés
     * @param resolver résolveur de groupes
     */
    public DefaultTimedFlyService(Settings settings, GroupResolver resolver) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public boolean enabled() {
        return settings.timedFly().enabled();
    }

    @Override
    public Optional<Settings.TimedFlyGroup> groupOf(Player player) {
        Settings.TimedFlySection tf = settings.timedFly();
        if (!tf.enabled()) {
            return Optional.empty();
        }
        return resolver.resolve(player, tf.groups());
    }

    @Override
    public boolean refillIfReady(Player player, FlightProfile profile, long nowEpochSeconds) {
        if (!enabled() || profile.flightEnabled()) {
            return false;
        }
        long cooldownUntil = profile.cooldownUntilEpochSeconds();
        boolean ready = cooldownUntil == 0L
                // Aucune recharge en cours : seul un profil sans temps (nouveau joueur) est crédité.
                ? profile.remainingSeconds() == 0L
                : cooldownUntil <= nowEpochSeconds;
        if (!ready) {
            return false;
        }
        Optional<Settings.TimedFlyGroup> group = groupOf(player);
        if (group.isEmpty()) {
            return false;
        }
        long flyTime = TimeMath.clamp(group.get().flyTime(), settings.limits().maxSeconds());
        if (flyTime <= 0L) {
            return false;
        }
        profile.setCooldownUntilEpochSeconds(0L);
        if (profile.remainingSeconds() >= flyTime) {
            // Solde déjà supérieur ou égal : recharge consommée sans réduire le temps existant.
            return false;
        }
        profile.setRemainingSeconds(flyTime);
        return true;
    }

    @Override
    public void onFlightStopped(Player player, FlightProfile profile, long nowEpochSeconds) {
        Settings.TimedFlySection tf = settings.timedFly();
        if (!tf.enabled()) {
            return;
        }
        if (profile.cooldownUntilEpochSeconds() > nowEpochSeconds) {
            // Recharge déjà en cours (appel en double) : on ne la prolonge pas.
            return;
        }
        long cooldown = groupOf(player).map(Settings.TimedFlyGroup::cooldown).orElse(0L);
        // Toujours > 0 : marque la recharge comme démarrée, même avec un cooldown nul.
        long until = TimeMath.add(Math.max(1L, nowEpochSeconds), Math.max(0L, cooldown), Long.MAX_VALUE);
        profile.setCooldownUntilEpochSeconds(until);
        if (tf.resetAfterDisable()) {
            profile.setRemainingSeconds(0L);
        }
    }

    @Override
    public void applyOffline(FlightProfile profile, long nowEpochSeconds) {
        Settings.TimedFlySection tf = settings.timedFly();
        if (!tf.enabled() || tf.decrementCooldownOffline()) {
            // decrement-cooldown-offline : l'échéance est absolue, la recharge a déjà avancé hors-ligne.
            return;
        }
        long cooldownUntil = profile.cooldownUntilEpochSeconds();
        long lastUpdate = profile.lastUpdateEpochSeconds();
        if (cooldownUntil == 0L || lastUpdate <= 0L || lastUpdate >= nowEpochSeconds
                || cooldownUntil <= lastUpdate) {
            // Pas de recharge en cours à la déconnexion, ou horodatage incohérent : rien à geler.
            return;
        }
        // Recharge gelée hors-ligne : l'échéance est reportée de la durée passée hors-ligne.
        long remainingAtLogout = cooldownUntil - lastUpdate;
        profile.setCooldownUntilEpochSeconds(TimeMath.add(nowEpochSeconds, remainingAtLogout, Long.MAX_VALUE));
    }

    @Override
    public long cooldownRemaining(FlightProfile profile, long nowEpochSeconds) {
        if (!enabled()) {
            return 0L;
        }
        long until = profile.cooldownUntilEpochSeconds();
        return until == 0L ? 0L : TimeMath.remainingUntil(until, nowEpochSeconds);
    }

    @Override
    public void reload(Settings newSettings) {
        this.settings = Objects.requireNonNull(newSettings, "settings");
    }
}
