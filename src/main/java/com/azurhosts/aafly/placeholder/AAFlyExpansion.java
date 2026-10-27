package com.azurhosts.aafly.placeholder;

import java.util.List;
import java.util.Objects;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * Expansion PlaceholderAPI {@code %aafly_*%} (SPEC §9). Interne au plugin : {@link #persist()} vaut
 * {@code true} (survit à {@code /papi reload}). Cette classe référence l'API de PlaceholderAPI : elle n'est
 * chargée que par {@link Placeholders} après vérification de la présence de PAPI.
 */
public final class AAFlyExpansion extends PlaceholderExpansion {

    /** Identifiant (préfixe) de l'expansion. */
    public static final String IDENTIFIER = "aafly";

    private static final List<String> PLACEHOLDERS = List.of(
            "%aafly_remaining%", "%aafly_remaining_seconds%", "%aafly_remaining_minutes%",
            "%aafly_remaining_hours%", "%aafly_remaining_days%", "%aafly_enabled%", "%aafly_flying%",
            "%aafly_unlimited%", "%aafly_speed%", "%aafly_cooldown%", "%aafly_cooldown_seconds%");

    private final FlightPlaceholderResolver resolver;
    private final String version;

    /**
     * @param resolver calcul des valeurs
     * @param version  version du plugin
     */
    public AAFlyExpansion(FlightPlaceholderResolver resolver, String version) {
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
        return resolver.aafly(player, params);
    }
}
