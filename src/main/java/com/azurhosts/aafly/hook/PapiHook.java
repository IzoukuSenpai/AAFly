package com.azurhosts.aafly.hook;

import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Suivi de la présence de PlaceholderAPI (dépendance douce).
 *
 * <p>Écoute l'activation et la désactivation de PlaceholderAPI pour rafraîchir le
 * {@link PlaceholderBridge} et (ré)enregistrer les expansions d'AAFly via le callback fourni. Ne
 * référence aucune classe de PlaceholderAPI : il peut être chargé sans PAPI. Main thread uniquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class PapiHook implements Listener {

    /** Nom du plugin PlaceholderAPI. */
    public static final String PLUGIN_NAME = "PlaceholderAPI";

    private final Plugin owner;
    private final PlaceholderBridge bridge;
    private final Runnable onAvailable;
    private final Logger logger;

    /**
     * @param owner       plugin AAFly (pour planifier les tâches)
     * @param bridge      pont de placeholders à rafraîchir
     * @param onAvailable action exécutée quand PAPI devient disponible (enregistrement des expansions) ;
     *                    ne doit pas lever d'exception (elles sont journalisées et ignorées)
     */
    public PapiHook(Plugin owner, PlaceholderBridge bridge, Runnable onAvailable) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.onAvailable = Objects.requireNonNull(onAvailable, "onAvailable");
        this.logger = owner.getLogger();
    }

    /**
     * @return {@code true} si PlaceholderAPI est installé et activé
     */
    public static boolean isPresent() {
        Plugin papi = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return papi != null && papi.isEnabled();
    }

    /**
     * Détection initiale (à appeler une fois dans {@code onEnable}, après l'enregistrement du listener) :
     * rafraîchit le pont et exécute {@code onAvailable} si PAPI est déjà actif.
     */
    public void init() {
        bridge.refresh();
        if (bridge.available()) {
            runCallback();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!PLUGIN_NAME.equals(event.getPlugin().getName())) {
            return;
        }
        bridge.refresh();
        if (bridge.available()) {
            logger.info("PlaceholderAPI détecté : placeholders activés.");
            runCallback();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!PLUGIN_NAME.equals(event.getPlugin().getName())) {
            return;
        }
        // Le plugin peut encore être marqué actif pendant l'événement : re-détection au tick suivant.
        if (owner.isEnabled()) {
            Bukkit.getScheduler().runTask(owner, bridge::refresh);
        }
        logger.info("PlaceholderAPI désactivé : placeholders ignorés.");
    }

    private void runCallback() {
        try {
            onAvailable.run();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Impossible d'enregistrer les expansions PlaceholderAPI.", e);
        }
    }
}
