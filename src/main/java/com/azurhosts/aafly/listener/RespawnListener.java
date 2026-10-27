package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.core.DefaultFlightService;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.rules.RuleDecision;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Réapparition (mort, retour de l'End) : le serveur recrée les capacités du joueur selon son mode de jeu
 * ({@code allowFlight} à faux en survival/adventure) <i>après</i> l'événement. Si le vol AAFly est voulu et
 * non suspendu, on réévalue les règles au tick suivant : vol interdit → suspension (cause {@code RULE}) ;
 * vol autorisé → l'état de vol ({@code allowFlight}, vitesse) est réappliqué. Sinon rien n'est modifié.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class RespawnListener implements Listener {

    private final Plugin plugin;
    private final StorageManager storage;
    private final DefaultFlightService service;
    private final RuleEngine rules;

    /**
     * @param plugin  plugin (scheduler)
     * @param storage stockage
     * @param service service de vol
     * @param rules   moteur de règles
     */
    public RespawnListener(Plugin plugin, StorageManager storage, DefaultFlightService service, RuleEngine rules) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.service = Objects.requireNonNull(service, "service");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Les capacités sont réinitialisées après l'événement : on réapplique au tick suivant.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            FlightProfile profile = storage.cached(uuid);
            if (player == null || !player.isOnline() || profile == null || player.isDead()) {
                return;
            }
            GameMode gm = player.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || !service.isEnabled(player)) {
                return;
            }
            rules.invalidate(uuid);
            RuleDecision decision = service.evaluate(player);
            if (!decision.allowed()) {
                service.suspendByRule(player, decision);
                return;
            }
            service.startFlight(player, profile);
        });
    }
}
