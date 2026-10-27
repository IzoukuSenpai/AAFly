package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.core.DefaultFlightService;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Changement de mode de jeu :
 * <ul>
 *   <li>vers creative/spectator : rien n'est modifié (le jeu gère le vol, le vol AAFly reste « voulu »
 *       et le temps n'est pas décompté) ;</li>
 *   <li>vers survival/adventure : le jeu remet {@code allowFlight} à faux après l'événement ; si le vol AAFly
 *       est actif, son état ({@code allowFlight}, {@code flying} en l'air, vitesse) est réappliqué au tick
 *       suivant, le vol n'est donc jamais « cassé ». Sinon rien n'est modifié.</li>
 * </ul>
 */
public final class GameModeListener implements Listener {

    private final Plugin plugin;
    private final StorageManager storage;
    private final DefaultFlightService service;

    /**
     * @param plugin  plugin (scheduler)
     * @param storage stockage
     * @param service service de vol
     */
    public GameModeListener(Plugin plugin, StorageManager storage, DefaultFlightService service) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode target = event.getNewGameMode();
        if (target == GameMode.CREATIVE || target == GameMode.SPECTATOR) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        FlightProfile profile = storage.cached(uuid);
        if (profile == null || !profile.flightEnabled() || profile.isSuspended()) {
            return;
        }
        // Le serveur applique les capacités du nouveau mode après l'événement : on réapplique au tick suivant.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            FlightProfile current = storage.cached(uuid);
            if (player == null || !player.isOnline() || current == null) {
                return;
            }
            GameMode now = player.getGameMode();
            if (now == GameMode.CREATIVE || now == GameMode.SPECTATOR) {
                return;
            }
            if (service.isEnabled(player)) {
                service.startFlight(player, current);
            }
        });
    }
}
