package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.DefaultFlightService;
import com.azurhosts.aafly.api.DisableCause;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.rules.RuleDecision;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;

/**
 * Changement de monde : si {@code flight.keep-flight-on-world-change} est faux, le vol est coupé (cause
 * {@code WORLD_CHANGE}, atterrissage sûr), même s'il était suspendu. Sinon les règles sont réévaluées immédiatement dans le nouveau
 * monde : vol interdit → suspension (cause {@code RULE}, atterrissage sûr) ; vol autorisé → l'état de vol
 * est réappliqué (certains changements de monde réinitialisent {@code allowFlight}).
 */
public final class WorldChangeListener implements Listener {

    private final Supplier<Settings> settings;
    private final StorageManager storage;
    private final DefaultFlightService service;
    private final RuleEngine rules;

    /**
     * @param settings réglages courants
     * @param storage  stockage
     * @param service  service de vol
     * @param rules    moteur de règles
     */
    public WorldChangeListener(Supplier<Settings> settings, StorageManager storage, DefaultFlightService service,
                               RuleEngine rules) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.service = Objects.requireNonNull(service, "service");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        rules.invalidate(player.getUniqueId());
        if (!profile.flightEnabled()) {
            return; // vol non voulu
        }
        if (!settings.get().flight().keepFlightOnWorldChange()) {
            // Y compris pour un vol suspendu : il ne doit pas être réactivé dans le nouveau monde.
            service.disable(player, DisableCause.WORLD_CHANGE, false);
            return;
        }
        if (profile.isSuspended()) {
            return; // vol suspendu : le ticker gère la réactivation
        }
        RuleDecision decision = service.evaluate(player);
        if (!decision.allowed()) {
            service.suspendByRule(player, decision);
            return;
        }
        service.startFlight(player, profile);
    }
}
