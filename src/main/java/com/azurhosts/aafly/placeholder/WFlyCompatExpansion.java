package com.azurhosts.aafly.placeholder;

import java.util.List;
import java.util.Objects;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * Expansion de compatibilité {@code %wfly_*%} pour migrer depuis wFly sans réécrire scoreboards et menus :
 * {@code %wfly_fly_remaining%}, {@code %wfly_fly_activate%}, {@code %wfly_fly_remaining_<unité>%}.
 * Enregistrée seulement si {@code compat.wfly-placeholders: true} et si wFly n'est pas lui-même installé.
 * Ne référence PlaceholderAPI que via son héritage : chargée uniquement par {@link Placeholders}.
 */
public final class WFlyCompatExpansion extends PlaceholderExpansion {

    /** Identifiant (préfixe) de l'expansion. */
    public static final String IDENTIFIER = "wfly";

    private static final List<String> PLACEHOLDERS = List.of(
            "%wfly_fly_remaining%", "%wfly_fly_activate%", "%wfly_fly_remaining_seconds%",
            "%wfly_fly_remaining_minutes%", "%wfly_fly_remaining_hours%", "%wfly_fly_remaining_days%");

    private final FlightPlaceholderResolver resolver;
    private final String version;

    /**
     * @param resolver calcul des valeurs
     * @param version  version du plugin
     */
    public WFlyCompatExpansion(FlightPlaceholderResolver resolver, String version) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public String getAuthor() {
        return "AzurHOSTS";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        return PLACEHOLDERS;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        return resolver.wfly(player, params);
    }
}
