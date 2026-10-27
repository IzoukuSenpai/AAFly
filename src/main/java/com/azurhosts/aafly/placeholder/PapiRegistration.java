package com.azurhosts.aafly.placeholder;

import com.azurhosts.aafly.AAFlyPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import org.jspecify.annotations.Nullable;

/**
 * Seule classe (avec les deux expansions) qui manipule PlaceholderAPI. N'est instanciée par
 * {@link Placeholders} qu'après avoir vérifié que PAPI est activé. Main thread uniquement.
 */
final class PapiRegistration implements Placeholders.Registration {

    private final AAFlyPlugin plugin;
    private final FlightPlaceholderResolver resolver;
    private final String version;
    private final AAFlyExpansion aafly;
    private @Nullable WFlyCompatExpansion wfly;

    PapiRegistration(AAFlyPlugin plugin, FlightPlaceholderResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.version = plugin.getPluginMeta().getVersion();
        this.aafly = new AAFlyExpansion(resolver, version);
        if (!aafly.register()) {
            throw new IllegalStateException("PlaceholderAPI a refusé l'expansion '" + AAFlyExpansion.IDENTIFIER + "'.");
        }
        plugin.getLogger().info("Placeholders %aafly_*% enregistrés auprès de PlaceholderAPI.");
    }

    @Override
    public void setWflyCompat(boolean enabled) {
        if (enabled && wfly == null) {
            if (PlaceholderAPI.isRegistered(WFlyCompatExpansion.IDENTIFIER)) {
                plugin.getLogger().warning("Une expansion '" + WFlyCompatExpansion.IDENTIFIER
                        + "' est déjà enregistrée : compatibilité %wfly_*% ignorée.");
                return;
            }
            WFlyCompatExpansion exp = new WFlyCompatExpansion(resolver, version);
            if (exp.register()) {
                wfly = exp;
                plugin.getLogger().info("Placeholders de compatibilité %wfly_*% enregistrés.");
            } else {
                plugin.getLogger().warning("PlaceholderAPI a refusé l'expansion de compatibilité '"
                        + WFlyCompatExpansion.IDENTIFIER + "'.");
            }
        } else if (!enabled && wfly != null) {
            wfly.unregister();
            wfly = null;
            plugin.getLogger().info("Placeholders de compatibilité %wfly_*% désenregistrés.");
        }
    }

    @Override
    public boolean wflyRegistered() {
        return wfly != null;
    }

    @Override
    public void unregisterAll() {
        if (wfly != null) {
            wfly.unregister();
            wfly = null;
        }
        if (aafly.isRegistered()) {
            aafly.unregister();
        }
    }
}
