package com.azurhosts.aafly.listener;

import com.azurhosts.aafly.action.TimeThresholdNotifier;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.SafeLanding;
import com.azurhosts.aafly.rules.RuleEngine;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Connexion / déconnexion : chargement asynchrone du profil (thread {@code AAFly-IO}) puis application sur
 * le main thread ({@link FlightService#restore} : décompte hors-ligne, vitesse, réactivation du vol si
 * {@code flightEnabled}) ; à la déconnexion, nettoyage de l'état runtime et sauvegarde asynchrone.
 *
 * <p>Course connexion/déconnexion : si le joueur est parti avant la fin du chargement, le profil est
 * immédiatement déchargé (et sauvegardé) au lieu de rester dans le cache des joueurs en ligne.
 */
public final class ConnectionListener implements Listener {

    private final Plugin plugin;
    private final StorageManager storage;
    private final FlightService service;
    private final SafeLanding landing;
    private final RuleEngine rules;
    private final TimeThresholdNotifier thresholds;

    /**
     * @param plugin     plugin (logger, scheduler)
     * @param storage    stockage
     * @param service    service de vol
     * @param landing    atterrissage sécurisé
     * @param rules      moteur de règles
     * @param thresholds notificateur de seuils
     */
    public ConnectionListener(Plugin plugin, StorageManager storage, FlightService service, SafeLanding landing,
                              RuleEngine rules, TimeThresholdNotifier thresholds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.service = Objects.requireNonNull(service, "service");
        this.landing = Objects.requireNonNull(landing, "landing");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        landing.forget(player);
        if (service instanceof com.azurhosts.aafly.core.DefaultFlightService d) {
            d.forgetPlayer(uuid); // B3 : propriété du vol en mémoire seulement
        }
        rules.invalidate(uuid);
        thresholds.reset(uuid);
        if (storage.cached(uuid) != null) {
            storage.unload(uuid);
        }
    }

    /**
     * Charge et restaure les joueurs déjà connectés (démarrage pendant que des joueurs sont en ligne).
     * À appeler une fois, sur le main thread, après le démarrage du stockage.
     */
    public void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (storage.cached(player.getUniqueId()) == null) {
                load(player);
            }
        }
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        storage.loadOnline(uuid, name).whenComplete((profile, error) -> runOnMain(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Chargement du profil de vol de " + name + " (" + uuid
                        + ") impossible : le vol AAFly restera indisponible jusqu'à la reconnexion.", error);
                return;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                // Déconnecté pendant le chargement : ne pas laisser le profil dans le cache « en ligne ».
                if (storage.cached(uuid) != null) {
                    storage.unload(uuid);
                }
                return;
            }
            FlightProfile loaded = profile;
            if (loaded == null) {
                return;
            }
            loaded.setLastKnownName(online.getName());
            try {
                service.restore(online, loaded);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Restauration du vol de " + name + " en erreur", e);
            }
        }));
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            storage.mainThreadExecutor().execute(task);
        }
    }
}
