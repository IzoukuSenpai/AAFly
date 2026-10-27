package com.azurhosts.aafly.api;

import com.azurhosts.aafly.AAFlyPlugin;
import com.azurhosts.aafly.api.events.FlightTimeChangeEvent;
import com.azurhosts.aafly.core.DisableCause;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightService;
import com.azurhosts.aafly.core.TimeChangeResult;
import com.azurhosts.aafly.core.ToggleResult;
import com.azurhosts.aafly.rules.ConditionFactory;
import com.azurhosts.aafly.rules.ConditionRegistry;
import com.azurhosts.aafly.time.TimeFormatter;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * Implémentation de {@link FlightApi}, publiée dans le {@code ServicesManager} de Bukkit.
 *
 * <p><b>Sécurité :</b> toutes les méthodes exigent le main thread (une {@link IllegalStateException} est
 * levée sinon) afin qu'aucun plugin tiers ne puisse modifier un profil depuis un thread asynchrone.
 * Les modifications passent par {@link FlightService} : bornes, timed-fly et événements s'appliquent
 * (raison {@link FlightTimeChangeEvent.Reason#API}, cause {@link DisableCause#API}).
 */
public final class DefaultFlightApi implements FlightApi {

    private final FlightService service;
    private final ConditionRegistry conditions;
    private final Supplier<TimeFormatter> formatter;

    /**
     * @param service    service de vol
     * @param conditions registre des conditions
     * @param formatter  formateur de durées courant
     */
    public DefaultFlightApi(FlightService service, ConditionRegistry conditions, Supplier<TimeFormatter> formatter) {
        this.service = Objects.requireNonNull(service, "service");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    /**
     * Crée l'API à partir des services du plugin et l'enregistre dans le {@code ServicesManager}
     * (priorité {@code Normal}). À appeler sur le main thread, une fois {@code flightService},
     * {@code conditionRegistry} et {@code timeFormatter} initialisés.
     *
     * @param plugin plugin AAFly
     * @return l'API enregistrée
     */
    public static DefaultFlightApi register(AAFlyPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        DefaultFlightApi api = new DefaultFlightApi(plugin.flightService(), plugin.conditionRegistry(),
                plugin::timeFormatter);
        plugin.getServer().getServicesManager().register(FlightApi.class, api, plugin, ServicePriority.Normal);
        return api;
    }

    @Override
    public OptionalLong remainingSeconds(UUID uuid) {
        requireMainThread();
        FlightProfile profile = service.profile(Objects.requireNonNull(uuid, "uuid"));
        return profile == null ? OptionalLong.empty() : OptionalLong.of(profile.remainingSeconds());
    }

    @Override
    public boolean isFlightEnabled(UUID uuid) {
        requireMainThread();
        FlightProfile profile = service.profile(Objects.requireNonNull(uuid, "uuid"));
        return profile != null && profile.flightEnabled() && !profile.isSuspended();
    }

    @Override
    public boolean hasUnlimited(Player player) {
        requireMainThread();
        return service.hasUnlimited(Objects.requireNonNull(player, "player"));
    }

    @Override
    public CompletableFuture<TimeChangeResult> addTime(UUID uuid, long seconds) {
        requireMainThread();
        return service.addTime(Objects.requireNonNull(uuid, "uuid"), seconds, null, FlightTimeChangeEvent.Reason.API);
    }

    @Override
    public CompletableFuture<TimeChangeResult> removeTime(UUID uuid, long seconds) {
        requireMainThread();
        return service.removeTime(Objects.requireNonNull(uuid, "uuid"), seconds, null,
                FlightTimeChangeEvent.Reason.API);
    }

    @Override
    public CompletableFuture<TimeChangeResult> setTime(UUID uuid, long seconds) {
        requireMainThread();
        return service.setTime(Objects.requireNonNull(uuid, "uuid"), seconds, null, FlightTimeChangeEvent.Reason.API);
    }

    @Override
    public ToggleResult enableFlight(Player player) {
        requireMainThread();
        return service.enable(Objects.requireNonNull(player, "player"), DisableCause.API, false);
    }

    @Override
    public ToggleResult disableFlight(Player player) {
        requireMainThread();
        return service.disable(Objects.requireNonNull(player, "player"), DisableCause.API, false);
    }

    @Override
    public void registerCondition(String type, ConditionFactory factory) {
        requireMainThread();
        conditions.register(Objects.requireNonNull(type, "type"), Objects.requireNonNull(factory, "factory"));
    }

    @Override
    public String formatTime(long seconds) {
        return formatter.get().format(seconds);
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("L'API AAFly doit être appelée depuis le main thread du serveur.");
        }
    }
}
