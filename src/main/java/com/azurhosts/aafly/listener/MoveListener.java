package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Détection d'immobilité ({@code decrement.disable-when-static}) : mémorise l'instant du dernier
 * changement de <b>bloc</b> du joueur. Les simples rotations et micro-déplacements dans le même bloc sont
 * ignorés. Coût O(1) par événement (aucune allocation, une recherche dans le cache des profils en ligne),
 * et rien du tout si l'option est désactivée.
 */
public final class MoveListener implements Listener {

    private final Supplier<Settings> settings;
    private final StorageManager storage;

    /**
     * @param settings réglages courants
     * @param storage  stockage (profils en ligne)
     */
    public MoveListener(Supplier<Settings> settings, StorageManager storage) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !settings.get().decrement().disableWhenStatic()) {
            return;
        }
        FlightProfile profile = storage.cached(event.getPlayer().getUniqueId());
        if (profile != null) {
            profile.setLastMoveEpochMillis(System.currentTimeMillis());
        }
    }
}
