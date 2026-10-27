package com.azurhosts.aafly.token;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.config.Lang;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Encaissement des jetons au clic droit (SPEC §7).
 *
 * <ul>
 *   <li>Un clic droit avec un jeton annule l'interaction vanilla (pas de pose de bloc, pas d'ouverture de
 *       conteneur) puis lance {@link FlyTokenService#redeem}.</li>
 *   <li>Anti-spam : clic ignoré si un encaissement est déjà en cours pour ce joueur, et au plus un traitement
 *       toutes les {@value #CLICK_COOLDOWN_MILLIS} ms (évite aussi le double événement main/seconde main).</li>
 *   <li>Échec : message au joueur, l'item reste intact.</li>
 * </ul>
 * Main thread uniquement (événements Bukkit).
 */
public final class TokenListener implements Listener {

    /** Délai minimal entre deux clics traités pour un même joueur. */
    static final long CLICK_COOLDOWN_MILLIS = 250L;

    private final AAFlyPlugin plugin;
    private final DefaultFlyTokenService service;
    /** Dernier clic traité par joueur (main thread uniquement). */
    private final Map<UUID, Long> lastClick = new HashMap<>();

    /**
     * @param plugin  plugin (messages, format des durées)
     * @param service service des jetons
     */
    public TokenListener(AAFlyPlugin plugin, DefaultFlyTokenService service) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!service.isToken(item)) {
            return;
        }
        // Un plugin de protection a explicitement interdit l'usage de l'item : on respecte ce refus.
        boolean denied = event.useItemInHand() == Event.Result.DENY;
        // Jamais d'usage vanilla d'un jeton (pose, ouverture d'un conteneur, consommation…).
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (denied) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(uuid);
        if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
            return;
        }
        lastClick.put(uuid, now);
        if (service.isRedeeming(uuid)) {
            return;
        }
        OptionalLong seconds = service.tokenSeconds(item);
        try {
            // L'item passé est l'instance de l'événement ; le service relit et consomme l'item dans
            // l'inventaire seulement après succès en base.
            // Garde-fou : la notification (API Bukkit) ne s'exécute jamais hors du main thread.
            service.redeem(player, item).thenAccept(result -> {
                if (Bukkit.isPrimaryThread()) {
                    notify(player, result, seconds);
                } else {
                    plugin.storage().mainThreadExecutor().execute(() -> notify(player, result, seconds));
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Encaissement du jeton en erreur", e);
            send(player, "token.error");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        // Filet de sécurité si token.material est un bloc : un jeton n'est jamais posé.
        if (service.isToken(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastClick.remove(uuid);
        service.forget(uuid);
    }

    /** Main thread (les futures du service se complètent sur le main thread). */
    private void notify(Player player, RedeemResult result, OptionalLong seconds) {
        if (!player.isOnline()) {
            return;
        }
        switch (result) {
            case SUCCESS -> {
                Lang lang = plugin.lang();
                TagResolver time = lang.time(plugin.timeFormatter().format(seconds.orElse(0L)));
                lang.send(player, "token.redeemed", time);
            }
            case INVALID_SIGNATURE -> send(player, "token.invalid");
            case ALREADY_REDEEMED -> send(player, "token.already-redeemed");
            case LIMIT_EXCEEDED -> send(player, "token.limit-exceeded");
            case DISABLED -> send(player, "errors.feature-disabled");
            case TIMED_FLY_LOCKED -> send(player, "errors.timed-fly-locked");
            case STORAGE_ERROR -> send(player, "token.error");
            case NOT_A_TOKEN -> {
                // rien : l'item n'était pas un jeton
            }
        }
    }

    private void send(Player player, String key) {
        plugin.lang().send(player, key);
    }
}
