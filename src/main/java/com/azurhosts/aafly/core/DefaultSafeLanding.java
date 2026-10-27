package com.azurhosts.aafly.core;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/**
 * Atterrissage sécurisé (SPEC §4, SPEC-phase2 A3), dans cet ordre :
 * <ol>
 *   <li><b>Colonne du joueur</b> : descente d'au plus {@code flight.safe-landing.max-scan} blocs (jamais sous
 *       {@code world.getMinHeight()}). La descente s'arrête au <b>premier</b> bloc non traversable ou liquide :
 *       on ne traverse jamais un bloc plein (fond d'un lac, toit, couche de lave). Ce premier obstacle est
 *       retenu s'il est un sol sûr (solide, non dangereux, pieds et tête libres) ; de l'eau (non mortelle)
 *       est acceptée comme repli si les deux blocs au-dessus sont libres ; tout autre obstacle (lave, magma,
 *       cactus, plafond trop bas…) ou l'absence d'obstacle passe au repli suivant ;</li>
 *   <li><b>Repli 1</b> : dernière position sûre mémorisée ({@link #observe}, au plus une vérification par seconde
 *       et par joueur, sur changement de bloc), revérifiée au moment de l'atterrissage, dans le même monde et
 *       seulement si son chunk est chargé ;</li>
 *   <li><b>Repli 2</b> : point d'apparition du monde, vérifié par la même routine (position elle-même, puis
 *       descente bornée) ;</li>
 *   <li>sinon aucune téléportation.</li>
 * </ol>
 * Dans tous les cas : yaw/pitch conservés, {@code fallDistance = 0} et protection anti-chute valable
 * {@link #FALL_PROTECTION_MILLIS} ms (consommée par le FallDamageListener). Main thread uniquement.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class DefaultSafeLanding implements SafeLanding {

    /** Profondeur de recherche par défaut ({@code flight.safe-landing.max-scan}). */
    public static final int DEFAULT_MAX_SCAN = 64;
    /** Borne absolue de la profondeur de recherche (défense contre une valeur aberrante). */
    static final int MAX_SCAN_LIMIT = 4096;
    /** Intervalle minimal entre deux vérifications de « dernière position sûre » d'un même joueur. */
    static final long OBSERVE_INTERVAL_MILLIS = 1_000L;

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

    /** Issue de la descente dans une colonne. */
    enum Kind {
        /** Sol sûr trouvé. */
        FLOOR,
        /** Premier obstacle = eau (non mortelle), deux blocs libres au-dessus : repli accepté. */
        WATER,
        /** Premier obstacle dangereux ou sans place pour le joueur : rien en dessous n'est retenu. */
        BLOCKED,
        /** Aucun obstacle dans la profondeur de recherche. */
        NONE
    }

    /**
     * @param kind issue
     * @param y    ordonnée du bloc porteur (sol ou eau), significative pour {@link Kind#FLOOR}/{@link Kind#WATER}
     */
    record ScanResult(Kind kind, int y) {
        static final ScanResult BLOCKED = new ScanResult(Kind.BLOCKED, 0);
        static final ScanResult NONE = new ScanResult(Kind.NONE, 0);

        boolean landable() {
            return kind == Kind.FLOOR || kind == Kind.WATER;
        }
    }

    /** Dernière position sûre : monde, position horizontale exacte, bloc porteur. */
    private record SafeSpot(UUID worldId, double x, double z, int blockX, int floorY, int blockZ) {
    }

    /** État par joueur pour {@link #observe} (main thread). */
    private static final class Memory {
        private @Nullable SafeSpot spot;
        private long lastSampleMillis = Long.MIN_VALUE;
    }

    private final Map<UUID, Long> protections = new ConcurrentHashMap<>();
    /** Dernières positions sûres (main thread uniquement). */
    private final Map<UUID, Memory> memory = new HashMap<>();
    private final IntSupplier maxScan;

    /** Atterrissage avec la profondeur de recherche par défaut ({@value #DEFAULT_MAX_SCAN} blocs). */
    public DefaultSafeLanding() {
        this(() -> DEFAULT_MAX_SCAN);
    }

    /**
     * @param maxScan profondeur de recherche courante ({@code flight.safe-landing.max-scan}, relue à chaque
     *                atterrissage : compatible {@code /aafly reload}), bornée à [1, {@value #MAX_SCAN_LIMIT}]
     */
    public DefaultSafeLanding(IntSupplier maxScan) {
        this.maxScan = Objects.requireNonNull(maxScan, "maxScan");
    }

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
        Location target = findTarget(player, from);
        if (target != null && needsTeleport(from, target)) {
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
        UUID id = player.getUniqueId();
        protections.remove(id);
        memory.remove(id);
    }

    @Override
    public void observe(Player player) {
        long now = System.currentTimeMillis();
        Memory m = memory.computeIfAbsent(player.getUniqueId(), k -> new Memory());
        if (!shouldSample(m.lastSampleMillis, now)) {
            return;
        }
        m.lastSampleMillis = now;
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        int floorY = (int) Math.floor(loc.getY() - 0.05D);
        if (floorY < world.getMinHeight() || floorY + 2 >= world.getMaxHeight()) {
            return;
        }
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        // La position n'est retenue que si elle est réellement sûre (jamais sur la foi de onGround, falsifiable).
        if (standable(world, bx, floorY, bz)) {
            m.spot = new SafeSpot(world.getUID(), loc.getX(), loc.getZ(), bx, floorY, bz);
        }
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

    // ------------------------------------------------------------------ recherche de la cible

    private @Nullable Location findTarget(Player player, Location from) {
        World world = from.getWorld();
        if (world == null) {
            return null;
        }
        int limit = clampScan(maxScan.getAsInt());
        Location column = findInColumn(world, from.getX(), from.getY(), from.getZ(), limit, from);
        if (column != null) {
            return column;
        }
        Location remembered = rememberedTarget(player, world, from);
        if (remembered != null) {
            return remembered;
        }
        return spawnTarget(world, from, limit);
    }

    /** Colonne (x, z) à partir de y : sol sûr, ou eau en repli ; {@code null} sinon. */
    private static @Nullable Location findInColumn(World world, double x, double y, double z, int limit,
                                                   Location orientation) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int min = world.getMinHeight();
        int max = world.getMaxHeight() - 1;
        int startY = Math.min(max, (int) Math.floor(y));
        ScanResult r = scan(startY, min, max, limit, yy -> cell(world.getBlockAt(bx, yy, bz)));
        if (!r.landable()) {
            return null;
        }
        double standY = r.kind() == Kind.WATER ? r.y() + 1.0D : standY(world.getBlockAt(bx, r.y(), bz), r.y());
        return new Location(world, x, standY, z, orientation.getYaw(), orientation.getPitch());
    }

    /** Repli 1 : dernière position sûre, revérifiée (même monde, chunk chargé, toujours sûre). */
    private @Nullable Location rememberedTarget(Player player, World world, Location from) {
        Memory m = memory.get(player.getUniqueId());
        SafeSpot spot = m == null ? null : m.spot;
        if (m == null || spot == null || !spot.worldId().equals(world.getUID())) {
            return null;
        }
        if (!world.isChunkLoaded(spot.blockX() >> 4, spot.blockZ() >> 4)
                || !standable(world, spot.blockX(), spot.floorY(), spot.blockZ())) {
            m.spot = null; // plus sûre (ou chunk déchargé) : oubliée
            return null;
        }
        double standY = standY(world.getBlockAt(spot.blockX(), spot.floorY(), spot.blockZ()), spot.floorY());
        return new Location(world, spot.x(), standY, spot.z(), from.getYaw(), from.getPitch());
    }

    /** Repli 2 : point d'apparition du monde, vérifié par la même routine. */
    private static @Nullable Location spawnTarget(World world, Location from, int limit) {
        Location spawn = world.getSpawnLocation();
        int bx = spawn.getBlockX();
        int by = spawn.getBlockY();
        int bz = spawn.getBlockZ();
        double x = bx + 0.5D;
        double z = bz + 0.5D;
        int floorY = by - 1;
        if (floorY >= world.getMinHeight() && floorY + 2 < world.getMaxHeight() && standable(world, bx, floorY, bz)) {
            return new Location(world, x, standY(world.getBlockAt(bx, floorY, bz), floorY), z,
                    from.getYaw(), from.getPitch());
        }
        return findInColumn(world, x, by, z, limit, from);
    }

    private static boolean standable(World world, int x, int floorY, int z) {
        return isStandable(cell(world.getBlockAt(x, floorY, z)), cell(world.getBlockAt(x, floorY + 1, z)),
                cell(world.getBlockAt(x, floorY + 2, z)));
    }

    private static double standY(Block floor, int floorY) {
        double top = floor.getBoundingBox().getMaxY();
        return top > floorY ? top : floorY + 1.0D;
    }

    /** Téléportation utile : toujours si la cible est ailleurs, sinon seulement pour une chute d'au moins 1 bloc. */
    private static boolean needsTeleport(Location from, Location target) {
        boolean sameColumn = Objects.equals(from.getWorld(), target.getWorld())
                && from.getBlockX() == target.getBlockX() && from.getBlockZ() == target.getBlockZ();
        return !sameColumn || from.getY() - target.getY() >= MIN_TELEPORT_DROP;
    }

    // ------------------------------------------------------------------ logique pure (testable sans serveur)

    /**
     * Forme historique de la descente (sol sûr uniquement, profondeur illimitée), conservée pour les tests et
     * la compatibilité : ordonnée du sol sûr, ou {@code null} si le premier obstacle n'en est pas un (eau
     * comprise).
     *
     * @param startY ordonnée de départ (pieds du joueur)
     * @param min    ordonnée minimale du monde (incluse)
     * @param max    ordonnée maximale du monde (incluse)
     * @param column description du bloc à une ordonnée donnée
     * @return ordonnée du bloc de sol sûr, ou {@code null}
     */
    static @Nullable Integer scanColumn(int startY, int min, int max, IntFunction<Cell> column) {
        ScanResult r = scan(startY, min, max, Integer.MAX_VALUE, column);
        return r.kind() == Kind.FLOOR ? r.y() : null;
    }

    /**
     * Descente dans une colonne : au plus {@code maxScan} blocs inspectés à partir de {@code startY}, jamais
     * sous {@code min}. Le premier bloc non traversable ou liquide décide seul (il n'est jamais traversé).
     *
     * @param startY  ordonnée de départ (pieds du joueur)
     * @param min     ordonnée minimale du monde (incluse)
     * @param max     ordonnée maximale du monde (incluse)
     * @param maxScan nombre maximal de blocs inspectés (ramené à 1 minimum)
     * @param column  description du bloc à une ordonnée donnée
     * @return issue de la descente
     */
    static ScanResult scan(int startY, int min, int max, int maxScan, IntFunction<Cell> column) {
        long lowest = Math.max((long) min, (long) startY - (Math.max(1L, maxScan) - 1L));
        for (int y = startY; y >= lowest; y--) {
            Cell c = column.apply(y);
            if (c.passable() && !c.liquid()) {
                // Air, herbe, torche… : on continue de descendre.
                continue;
            }
            // Premier obstacle (bloc plein ou liquide) : c'est le sol (ou l'eau) ou rien.
            boolean room = y + 2 <= max + 1 && column.apply(y + 1).free() && column.apply(y + 2).free();
            if (c.liquid()) {
                return !c.dangerousFloor() && room ? new ScanResult(Kind.WATER, y) : ScanResult.BLOCKED;
            }
            if (!c.solid() || c.dangerousFloor() || !room) {
                return ScanResult.BLOCKED;
            }
            return new ScanResult(Kind.FLOOR, y);
        }
        return ScanResult.NONE;
    }

    /**
     * @param below bloc porteur
     * @param feet  bloc des pieds
     * @param head  bloc de la tête
     * @return {@code true} si un joueur peut s'y tenir sans danger
     */
    static boolean isStandable(Cell below, Cell feet, Cell head) {
        return !below.passable() && !below.liquid() && below.solid() && !below.dangerousFloor()
                && feet.free() && head.free();
    }

    /**
     * @param lastSampleMillis dernière vérification ({@link Long#MIN_VALUE} : jamais)
     * @param nowMillis        instant courant
     * @return {@code true} si une nouvelle vérification est permise (au plus une par seconde)
     */
    static boolean shouldSample(long lastSampleMillis, long nowMillis) {
        return lastSampleMillis == Long.MIN_VALUE || nowMillis - lastSampleMillis >= OBSERVE_INTERVAL_MILLIS;
    }

    /**
     * @param configured profondeur configurée
     * @return profondeur bornée à [1, {@value #MAX_SCAN_LIMIT}]
     */
    static int clampScan(int configured) {
        return Math.max(1, Math.min(MAX_SCAN_LIMIT, configured));
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
     * @param dangerousFloor sol interdit (magma, cactus, lave…)
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
