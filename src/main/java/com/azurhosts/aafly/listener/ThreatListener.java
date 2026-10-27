package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.api.DisableCause;
import com.azurhosts.aafly.core.DefaultFlightService;
import com.azurhosts.aafly.core.NearbyEnemyDetector;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * Proximité / PvP (SPEC-phase2 A6) :
 * <ul>
 *   <li>{@link PlayerToggleFlightEvent} : un joueur qui (re)décolle avec un vol <b>accordé par AAFly</b>
 *       alors qu'une menace est proche est bloqué (événement annulé) et son vol est suspendu (cause
 *       {@code NEARBY}, réactivé quand il est libre). Évite de contourner la vérification throttlée du ticker
 *       en atterrissant puis redécollant entre deux contrôles. Un vol accordé par un autre plugin, le créatif
 *       et le spectateur ne sont jamais touchés (B3) ;</li>
 *   <li>{@link PluginDisableEvent} : les résolveurs de menace fournis par un plugin désactivé sont retirés.</li>
 * </ul>
 * Aucun travail si {@code pvp.enabled} est faux. Main thread.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class ThreatListener implements Listener {

    private final DefaultFlightService service;
    private final NearbyEnemyDetector detector;
    private final Logger logger;

    /**
     * @param service  service de vol (propriété du vol, suspension)
     * @param detector détection des menaces (résolveurs tiers + résolveur par défaut)
     * @param logger   logger du plugin
     */
    public ThreatListener(DefaultFlightService service, NearbyEnemyDetector detector, Logger logger) {
        this.service = Objects.requireNonNull(service, "service");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!event.isFlying() || !detector.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || !service.ownsFlight(player.getUniqueId())) {
            return;
        }
        if (!detector.hasEnemyNearby(player)) {
            return;
        }
        event.setCancelled(true);
        service.disable(player, DisableCause.NEARBY, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        int removed = detector.registry().unregisterAll(event.getPlugin());
        if (removed > 0) {
            logger.info(removed + " résolveur(s) de menace de " + event.getPlugin().getName()
                    + " retiré(s) (plugin désactivé).");
        }
    }
}
