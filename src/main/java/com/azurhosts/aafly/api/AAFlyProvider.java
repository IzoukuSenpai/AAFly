package com.azurhosts.aafly.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Accès statique à l'{@link AAFlyApi} enregistrée par AAFly dans le {@code ServicesManager}.
 *
 * <p><b>Sécurité :</b> aucun état statique modifiable. Seule l'implémentation enregistrée par le plugin
 * nommé {@value #PLUGIN_NAME} est renvoyée : un autre plugin qui enregistrerait sa propre
 * {@link AAFlyApi} (même avec une priorité supérieure) n'est jamais servi par cette méthode.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class AAFlyProvider {

    /** Nom du plugin fournisseur. */
    public static final String PLUGIN_NAME = "AAFly";

    private AAFlyProvider() {
    }

    /**
     * @return l'API AAFly
     * @throws IllegalStateException si AAFly n'est pas chargé ou pas activé
     */
    public static AAFlyApi get() {
        for (RegisteredServiceProvider<AAFlyApi> registration
                : Bukkit.getServicesManager().getRegistrations(AAFlyApi.class)) {
            if (PLUGIN_NAME.equals(registration.getPlugin().getName()) && registration.getPlugin().isEnabled()) {
                return registration.getProvider();
            }
        }
        throw new IllegalStateException("AAFly n'est pas chargé : API indisponible.");
    }
}
