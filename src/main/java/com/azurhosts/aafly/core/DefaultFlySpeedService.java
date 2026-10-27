package com.azurhosts.aafly.core;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.storage.StorageManager;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Vitesse de vol (SPEC §8 {@code /flyspeed}) : niveaux 1..10 mappés sur 0.1..1.0, plafond par
 * permission {@code aafly.speed.<n>}. La vitesse appliquée est toujours bornée par le plafond courant
 * du joueur (une permission retirée fait redescendre la vitesse à la prochaine application), sans
 * jamais descendre sous {@code flight.default-speed}. Main thread uniquement.
 */
public final class DefaultFlySpeedService implements FlySpeedService {

    private static final String PERM_LEVEL_PREFIX = PERM_SPEED + ".";

    private final StorageManager storage;
    private final Supplier<Settings> settings;

    /**
     * @param storage  stockage (profils en ligne)
     * @param settings réglages courants
     */
    public DefaultFlySpeedService(StorageManager storage, Supplier<Settings> settings) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public int maxLevel(Player player) {
        for (int n = MAX_LEVEL; n >= MIN_LEVEL; n--) {
            if (player.hasPermission(PERM_LEVEL_PREFIX + n)) {
                return n;
            }
        }
        return player.hasPermission(PERM_SPEED) ? MAX_LEVEL : MIN_LEVEL;
    }

    @Override
    public boolean setLevel(Player player, int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL || level > maxLevel(player)) {
            return false;
        }
        FlightProfile profile = storage.cached(player.getUniqueId());
        if (profile == null) {
            return false;
        }
        profile.setFlySpeed(FlySpeedService.levelToSpeed(level));
        storage.markDirty(profile);
        apply(player, profile);
        return true;
    }

    @Override
    public void apply(Player player, FlightProfile profile) {
        int cap = Math.max(maxLevel(player), FlySpeedService.speedToLevel(settings.get().flight().defaultSpeed()));
        double speed = Math.min(FlightProfile.clampSpeed(profile.flySpeed()), FlySpeedService.levelToSpeed(cap));
        player.setFlySpeed((float) FlightProfile.clampSpeed(speed));
    }
}
