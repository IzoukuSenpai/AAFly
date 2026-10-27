package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.core.SafeLanding;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Annule les dégâts de chute d'un joueur protégé par {@link SafeLanding} (protection à usage unique,
 * valable {@link SafeLanding#FALL_PROTECTION_MILLIS} ms après la coupure du vol).
 */
public final class FallDamageListener implements Listener {

    private final SafeLanding landing;

    /**
     * @param landing atterrissage sécurisé
     */
    public FallDamageListener(SafeLanding landing) {
        this.landing = Objects.requireNonNull(landing, "landing");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (landing.consumeFallProtection(player)) {
            event.setCancelled(true);
        }
    }
}
