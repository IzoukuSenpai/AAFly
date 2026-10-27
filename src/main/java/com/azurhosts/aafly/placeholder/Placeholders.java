package com.azurhosts.aafly.placeholder;

import com.azurhosts.aafly.AAFlyPlugin;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Point d'entrée de l'intégration PlaceholderAPI (optionnelle).
 *
 * <p>Cette classe <b>ne référence aucune classe de PlaceholderAPI</b> : l'enregistrement effectif est délégué
 * à {@link PapiRegistration}, qui n'est chargée que si PAPI est présent et activé. Sans PAPI, l'instance
 * renvoyée est inerte. Main thread uniquement.
 */
public final class Placeholders {

    /** Nom du plugin PlaceholderAPI. */
    public static final String PAPI_PLUGIN = "PlaceholderAPI";
    /** Nom du plugin wFly (l'expansion de compatibilité n'est pas enregistrée s'il est présent). */
    public static final String WFLY_PLUGIN = "wFly";

    /** Opérations d'enregistrement (implémentées par la classe qui référence PAPI). */
    interface Registration {
        void setWflyCompat(boolean enabled);

        boolean wflyRegistered();

        void unregisterAll();
    }

    private final AAFlyPlugin plugin;
    private final @Nullable FlightPlaceholderResolver resolver;
    private @Nullable Registration registration;

    private Placeholders(AAFlyPlugin plugin, @Nullable FlightPlaceholderResolver resolver,
                         @Nullable Registration registration) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.registration = registration;
    }

    /**
     * Enregistre l'expansion {@code aafly} (et {@code wfly} si {@code compat.wfly-placeholders}) si
     * PlaceholderAPI est présent et activé. À appeler une fois les services initialisés.
     *
     * @param plugin plugin AAFly
     * @return gestionnaire (inerte si PAPI est absent ou si l'enregistrement a échoué) ; jamais {@code null}
     */
    public static Placeholders registerIfPresent(AAFlyPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Plugin papi = Bukkit.getPluginManager().getPlugin(PAPI_PLUGIN);
        if (papi == null || !papi.isEnabled()) {
            plugin.getLogger().info("PlaceholderAPI absent : placeholders %aafly_*% non enregistrés.");
            return new Placeholders(plugin, null, null);
        }
        FlightPlaceholderResolver resolver = new FlightPlaceholderResolver(plugin);
        try {
            Registration reg = new PapiRegistration(plugin, resolver);
            resolver.start();
            Placeholders handle = new Placeholders(plugin, resolver, reg);
            handle.applyCompat();
            return handle;
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING,
                    "Enregistrement des placeholders PlaceholderAPI impossible : intégration désactivée.", e);
            resolver.stop();
            return new Placeholders(plugin, null, null);
        }
    }

    /**
     * @return {@code true} si l'expansion {@code aafly} est enregistrée
     */
    public boolean active() {
        return registration != null;
    }

    /**
     * À appeler après {@code /aafly reload} : applique {@code compat.wfly-placeholders} et vide le cache
     * des durées formatées.
     */
    public void reload() {
        if (resolver != null) {
            resolver.clearFormatCache();
        }
        applyCompat();
    }

    /** Désenregistre les expansions et arrête la tâche de cache (au {@code onDisable}). Idempotent. */
    public void unregister() {
        Registration reg = registration;
        registration = null;
        if (reg != null) {
            try {
                reg.unregisterAll();
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Désenregistrement des placeholders en erreur", e);
            }
        }
        if (resolver != null) {
            resolver.stop();
        }
    }

    private void applyCompat() {
        Registration reg = registration;
        if (reg == null) {
            return;
        }
        boolean wanted;
        try {
            wanted = plugin.settings().compat().wflyPlaceholders();
        } catch (IllegalStateException notLoaded) {
            wanted = false;
        }
        if (wanted && Bukkit.getPluginManager().getPlugin(WFLY_PLUGIN) != null) {
            if (!reg.wflyRegistered()) {
                plugin.getLogger().warning("wFly est installé : l'expansion de compatibilité %wfly_*% d'AAFly "
                        + "n'est pas enregistrée (conflit de préfixe).");
            }
            wanted = false;
        }
        reg.setWflyCompat(wanted);
    }
}
