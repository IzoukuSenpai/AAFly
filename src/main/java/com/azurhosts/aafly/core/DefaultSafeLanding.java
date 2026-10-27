package com.azurhosts.aafly.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/**
 * Atterrissage sécurisé (SPEC §4) : recherche, à la verticale du joueur, le premier bloc non traversable ;
 * s'il est solide, non dangereux et que les deux blocs au-dessus sont libres, puis téléporte le joueur dessus en
 * conservant sa position horizontale, son yaw et son pitch. Une protection anti-chute valable
 * {@link #FALL_PROTECTION_MILLIS} ms est posée dans tous les cas (consommée par le FallDamageListener).
 *
 * <p>Aucune lecture hors du chunk du joueur (colonne x/z courante, déjà chargée). Main thread uniquement.
 */
public final class DefaultSafeLanding implements SafeLanding {

    /** Blocs sur lesquels on ne pose jamais le joueur. */
    private static final Set<Material> DANGEROUS_FLOOR = EnumSet.of(
            Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.POINTED_DRIPSTONE);

    /** Blocs « traversables » mais dangereux pour la tête ou les pieds. */
    private static final Set<Material> DANGEROUS_SPACE = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS);

    /** Distance de chute en dessous de laquelle on ne téléporte pas (aucun dégât de chute possible). */
    private static final double MIN_TELEPORT_DROP = 1.0D;

    private final Map<UUID, Long> protections = new ConcurrentHashMap<>();

    @Override
    public boolean land(Player player) {
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return false;
        }
        if (!isAirborne(player)) {
            return false;
        }
        long now = System.currentTimeMillis();
        protections.put(player.getUniqueId(), now + FALL_PROTECTION_MILLIS);
        player.setFallDistance(0F);
        if (player.isInsideVehicle()) {
            return true;
        }
        Location from = player.getLocation();
        Location target = findFloor(from);
        if (target != null && from.getY() - target.getY() >= MIN_TELEPORT_DROP) {
            player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.setFallDistance(0F);
        }
        return true;
    }

    @Override
    public boolean consumeFallProtection(Player player) {
        Long until = protections.remove(player.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    @Override
    public void forget(Player player) {
        protections.remove(player.getUniqueId());
    }

    /**
     * @param player joueur
     * @return {@code true} si le bloc sous les pieds du joueur est traversable (joueur en l'air)
     */
    public static boolean isAirborne(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        double y = loc.getY() - 0.05D;
        if (y < world.getMinHeight()) {
            return true;
        }
        Block below = world.getBlockAt(loc.getBlockX(), (int) Math.floor(y), loc.getBlockZ());
        return below.isPassable();
    }

    /**
     * Recherche le sol d'atterrissage. La descente s'arrête au <b>premier</b> bloc non traversable ou liquide
     * rencontré : si ce bloc n'est pas un sol sûr (solide, non dangereux, deux blocs libres au-dessus), aucune
     * téléportation n'a lieu. On ne traverse donc jamais un sol plein (fond d'un lac, toit, couche de lave)
     * pour atteindre une cavité fermée située dessous.
     *
     * @param from position actuelle
     * @return position d'atterrissage (orientation conservée), ou {@code null} si aucun sol sûr
     */
    static @Nullable Location findFloor(Location from) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        }
        int x = from.getBlockX();
        int z = from.getBlockZ();
        int min = world.getMinHeight();
        int max = world.getMaxHeight() - 1;
        int startY = Math.min(max, (int) Math.floor(from.getY()));
        Integer floorY = scanColumn(startY, min, max, y -> cell(world.getBlockAt(x, y, z)));
        if (floorY == null) {
            return null;
        }
        Block floor = world.getBlockAt(x, floorY, z);
        double top = floor.getBoundingBox().getMaxY();
        double standY = top > floorY ? top : floorY + 1.0D;
        return new Location(world, from.getX(), standY, from.getZ(), from.getYaw(), from.getPitch());
    }

    /**
     * Descente pure (testable sans serveur) dans une colonne de blocs.
     *
     * @param startY ordonnée de départ (pieds du joueur)
     * @param min    ordonnée minimale du monde (incluse)
     * @param max    ordonnée maximale du monde (incluse)
     * @param column description du bloc à une ordonnée donnée
     * @return ordonnée du bloc de sol sûr, ou {@code null} si le premier obstacle n'est pas un sol sûr
     */
    static @Nullable Integer scanColumn(int startY, int min, int max, IntFunction<Cell> column) {
        for (int y = startY; y >= min; y--) {
            Cell floor = column.apply(y);
            if (floor.passable() && !floor.liquid()) {
                // Air, herbe, torche… : on continue de descendre.
                continue;
            }
            // Premier obstacle (bloc plein ou liquide) : c'est le sol ou rien.
            if (floor.liquid() || !floor.solid() || floor.dangerousFloor()) {
                return null;
            }
            if (y + 2 > max + 1 || !column.apply(y + 1).free() || !column.apply(y + 2).free()) {
                return null;
            }
            return y;
        }
        return null;
    }

    private static Cell cell(Block block) {
        Material type = block.getType();
        return new Cell(block.isPassable(), block.isLiquid(), block.isSolid(),
                DANGEROUS_FLOOR.contains(type), DANGEROUS_SPACE.contains(type));
    }

    /**
     * Propriétés d'un bloc utiles à la recherche du sol.
     *
     * @param passable       traversable
     * @param liquid         liquide
     * @param solid          solide
     * @param dangerousFloor sol interdit (magma, cactus…)
     * @param dangerousSpace espace interdit pour les pieds ou la tête (feu, buisson à baies…)
     */
    record Cell(boolean passable, boolean liquid, boolean solid, boolean dangerousFloor, boolean dangerousSpace) {

        /** Bloc d'air ordinaire. */
        static final Cell AIR = new Cell(true, false, false, false, false);

        /** @return {@code true} si le joueur peut occuper ce bloc sans danger */
        boolean free() {
            return passable && !liquid && !dangerousSpace;
        }
    }
}
