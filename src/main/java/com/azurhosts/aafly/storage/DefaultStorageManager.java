package com.azurhosts.aafly.storage;

import com.azurhosts.aafly.config.Settings;
import com.azurhosts.aafly.core.FlightProfile;
import com.azurhosts.aafly.core.FlightProfileSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Implémentation de {@link StorageManager} (SPEC §11.2 et §12).
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li>Tout l'état (caches, dirty-set) est confiné au <b>main thread</b> ; les {@link FlightProfile}
 *       n'en sortent jamais : seuls des {@link FlightProfileSnapshot} immuables partent vers l'I/O.</li>
 *   <li>L'I/O s'exécute sur un executor <b>mono-thread</b> nommé {@code AAFly-IO} : les opérations
 *       SQL sont traitées dans l'ordre de soumission (une lecture soumise après une écriture voit
 *       cette écriture), ce qui évite toute relecture d'une donnée périmée à la reconnexion.</li>
 *   <li>Le retour sur le main thread passe par {@code BukkitScheduler#runTask} ; aucun appel à l'API
 *       Bukkit n'est fait depuis {@code AAFly-IO}.</li>
 * </ul>
 *
 * <h2>Invariant</h2>
 * Au plus une instance de {@link FlightProfile} par UUID en mémoire : en ligne, cache hors-ligne
 * (LRU 256 / 10 min) ou en attente d'écriture ({@code pendingWrites}). Toute lecture en base se termine
 * par une re-vérification de ces trois emplacements avant de créer une instance.
 *
 * <h2>Persistance</h2>
 * Dirty-set écrit toutes les {@code storage.save-interval-seconds} (capture des instantanés sur le main
 * thread, écriture groupée sur {@code AAFly-IO}), sauvegarde asynchrone à la déconnexion, flush
 * synchrone borné à {@value StorageManager#SHUTDOWN_TIMEOUT_SECONDS} s à l'arrêt. Un échec d'écriture
 * remet le profil en file pour le flush suivant ; un échec de lecture n'entraîne <b>jamais</b> la création
 * d'un profil vierge (qui écraserait les données en base).
 */
public final class DefaultStorageManager implements StorageManager {

    /** Nom des threads I/O. */
    public static final String IO_THREAD_NAME = "AAFly-IO";
    /** Intervalle minimal de sauvegarde périodique (secondes). */
    public static final int MIN_SAVE_INTERVAL_SECONDS = 10;
    /** Période du nettoyage du cache hors-ligne (ticks : 60 s). */
    private static final long CLEANUP_PERIOD_TICKS = 20L * 60L;
    /** Délai maximal de l'initialisation synchrone du stockage. */
    private static final long START_TIMEOUT_SECONDS = 30L;

    private final Plugin plugin;
    private final Logger logger;
    private final StorageType type;
    private final int saveIntervalSeconds;
    private final DoubleSupplier defaultSpeed;
    private final ExecutorService io;
    private final AbstractSqlProfileRepository repository;
    private final Executor mainThread = this::runOnMain;

    // --- état confiné au main thread ---
    private final Map<UUID, FlightProfile> online = new HashMap<>();
    private final Collection<FlightProfile> onlineView = Collections.unmodifiableCollection(online.values());
    private final OfflineProfileCache offline = new OfflineProfileCache();
    /** Profils modifiés à écrire, y compris ceux évincés du cache hors-ligne avant écriture. */
    private final Map<UUID, FlightProfile> pendingWrites = new LinkedHashMap<>();
    /** Joueurs dont la session est ouverte (loadOnline appelé, unload pas encore). */
    private final Set<UUID> sessions = new HashSet<>();

    private @Nullable BukkitTask saveTask;
    private @Nullable BukkitTask cleanupTask;
    private volatile boolean started;
    private volatile boolean stopped;

    /**
     * Crée l'executor {@code AAFly-IO} et le dépôt correspondant à {@code storage.type}. Aucune
     * connexion n'est ouverte ici (voir {@link #start()} / {@link #startSync()}).
     *
     * @param plugin       plugin propriétaire (scheduler, dossier de données, logger)
     * @param storage      section {@code storage} (figée : le stockage n'est pas rechargé à chaud)
     * @param defaultSpeed fournisseur de {@code flight.default-speed} (lu à chaque création de profil)
     * @throws StorageException si la configuration MySQL est invalide
     */
    public DefaultStorageManager(Plugin plugin, Settings.StorageSection storage, DoubleSupplier defaultSpeed) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        Objects.requireNonNull(storage, "storage");
        this.type = storage.type();
        this.saveIntervalSeconds = Math.max(MIN_SAVE_INTERVAL_SECONDS, storage.saveIntervalSeconds());
        this.defaultSpeed = Objects.requireNonNull(defaultSpeed, "defaultSpeed");
        this.io = newIoExecutor(logger);
        try {
            this.repository = switch (type) {
                case SQLITE -> new SqliteProfileRepository(
                        plugin.getDataFolder().toPath().resolve(SqliteProfileRepository.DEFAULT_FILE_NAME), io, logger);
                case MYSQL -> new MySqlProfileRepository(storage.mysql(), io, logger);
            };
        } catch (RuntimeException e) {
            io.shutdownNow();
            throw e;
        }
    }

    /**
     * Crée l'executor I/O mono-thread (threads démons nommés {@value #IO_THREAD_NAME}).
     *
     * @param logger journal pour les exceptions non capturées
     * @return executor
     */
    public static ExecutorService newIoExecutor(Logger logger) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            int n = counter.incrementAndGet();
            Thread t = new Thread(r, n == 1 ? IO_THREAD_NAME : IO_THREAD_NAME + "-" + n);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    logger.log(Level.SEVERE, "Erreur non gérée dans " + thread.getName(), ex));
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    public CompletableFuture<Void> start() {
        return repository.init().thenRunAsync(this::onStarted, mainThread);
    }

    /**
     * Variante bloquante de {@link #start()} pour {@code onEnable} : attend l'initialisation du schéma
     * (au plus 30 s) puis planifie les tâches. À appeler sur le main thread.
     * <p><b>Ne jamais faire {@code start().join()} sur le main thread</b> : la future de {@link #start()}
     * se termine sur le main thread (interblocage).
     *
     * @throws StorageException si la base est inaccessible ou le schéma incompatible
     */
    public void startSync() {
        try {
            repository.init().get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Initialisation du stockage interrompue.", e);
        } catch (TimeoutException e) {
            throw new StorageException("Initialisation du stockage trop longue (" + START_TIMEOUT_SECONDS
                    + " s, " + repository.describe() + ").", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new StorageException("Initialisation du stockage impossible (" + repository.describe()
                    + ") : " + cause.getMessage(), cause);
        }
        onStarted();
    }

    private void onStarted() {
        if (stopped || started) {
            return;
        }
        long period = saveIntervalSeconds * 20L;
        saveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::periodicFlush, period, period);
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupOffline,
                CLEANUP_PERIOD_TICKS, CLEANUP_PERIOD_TICKS);
        started = true;
        logger.info("Stockage prêt : " + repository.describe() + " (sauvegarde toutes les "
                + saveIntervalSeconds + " s).");
    }

    @Override
    public void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        cancel(saveTask);
        cancel(cleanupTask);
        saveTask = null;
        cleanupTask = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);

        // 1. Capture (main thread) : tous les joueurs en ligne (lastUpdate = maintenant) + profils modifiés.
        long now = nowEpochSeconds();
        List<FlightProfile> batch = new ArrayList<>(online.size() + pendingWrites.size());
        Set<UUID> seen = new HashSet<>();
        for (FlightProfile p : online.values()) {
            p.setLastUpdateEpochSeconds(now);
            batch.add(p);
            seen.add(p.uuid());
        }
        collectDirty(batch, seen);
        List<FlightProfileSnapshot> snapshots = snapshotAndClear(batch);

        // 2. Écriture finale, en attente bornée (les sauvegardes de déconnexion déjà en file passent avant).
        if (!snapshots.isEmpty()) {
            try {
                repository.saveAll(snapshots).get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                logger.info(snapshots.size() + " profil(s) sauvegardé(s) à l'arrêt.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.severe("Sauvegarde finale interrompue : " + snapshots.size() + " profil(s) peut-être perdus.");
            } catch (TimeoutException e) {
                logger.severe("Sauvegarde finale non terminée après " + SHUTDOWN_TIMEOUT_SECONDS + " s : "
                        + snapshots.size() + " profil(s) peut-être perdus.");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logger.log(Level.SEVERE, "Sauvegarde finale en erreur : " + snapshots.size()
                        + " profil(s) non sauvegardé(s).", cause);
            }
        }

        // 3. Arrêt de l'executor puis fermeture du pool.
        io.shutdown();
        try {
            if (!io.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                List<Runnable> dropped = io.shutdownNow();
                logger.warning("Arrêt forcé du thread " + IO_THREAD_NAME + " (" + dropped.size()
                        + " opération(s) abandonnée(s)).");
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
        repository.close();
        online.clear();
        offline.clear();
        pendingWrites.clear();
        sessions.clear();
    }

    // ------------------------------------------------------------------ accès

    @Override
    public StorageType type() {
        return type;
    }

    @Override
    public ProfileRepository repository() {
        return repository;
    }

    @Override
    public Executor ioExecutor() {
        return io;
    }

    @Override
    public Executor mainThreadExecutor() {
        return mainThread;
    }

    @Override
    public @Nullable FlightProfile cached(UUID uuid) {
        return online.get(uuid);
    }

    @Override
    public Collection<FlightProfile> onlineProfiles() {
        return onlineView;
    }

    /**
     * @return nombre de profils hors-ligne en cache (diagnostic {@code /aafly about})
     */
    public int offlineCacheSize() {
        return offline.size();
    }

    /**
     * @return nombre de profils en attente d'écriture hors caches (diagnostic)
     */
    public int pendingWriteCount() {
        return pendingWrites.size();
    }

    // ------------------------------------------------------------------ chargement

    @Override
    public CompletableFuture<FlightProfile> loadOnline(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        return onMain(() -> {
            if (stopped) {
                return CompletableFuture.failedFuture(new StorageException("Stockage arrêté."));
            }
            sessions.add(uuid);
            FlightProfile current = online.get(uuid);
            if (current != null) {
                current.setLastKnownName(name);
                return CompletableFuture.completedFuture(current);
            }
            // remove() en O(1) : les deux branches ci-dessous retirent de toute façon le profil hors-ligne.
            FlightProfile local = offline.remove(uuid);
            if (local == null) {
                local = pendingWrites.get(uuid);
            }
            // En MySQL multi-serveurs, un profil propre peut avoir été modifié ailleurs : on relit la base.
            // Un profil modifié localement (dirty ou en attente d'écriture) reste la référence.
            if (local != null && (type == StorageType.SQLITE || local.isDirty() || pendingWrites.containsKey(uuid))) {
                promoteOnline(local, name);
                return CompletableFuture.completedFuture(local);
            }
            // Sinon (MySQL, profil propre) : déjà retiré du cache hors-ligne, relu en base ci-dessous
            // (après toute écriture déjà en file).
            return repository.find(uuid).thenApplyAsync(found -> {
                FlightProfile existing = lookup(uuid);
                FlightProfile profile;
                if (existing != null) {
                    profile = existing;
                } else if (found.isPresent()) {
                    profile = FlightProfile.fromSnapshot(found.get());
                } else {
                    profile = FlightProfile.createNew(uuid, name, defaultSpeed.getAsDouble(), nowEpochSeconds());
                }
                if (sessions.contains(uuid) && !stopped) {
                    offline.remove(uuid);
                    pendingWrites.remove(uuid);
                    promoteOnline(profile, name);
                } else {
                    // Le joueur est reparti pendant la lecture : on garde l'instance hors-ligne.
                    profile.setLastKnownName(name);
                    cacheOffline(profile);
                }
                return profile;
            }, mainThread);
        });
    }

    private void promoteOnline(FlightProfile profile, String name) {
        profile.setLastKnownName(name);
        online.put(profile.uuid(), profile);
        pendingWrites.remove(profile.uuid()); // un profil en ligne modifié est suivi par le scan des profils en ligne
    }

    @Override
    public CompletableFuture<Optional<FlightProfile>> loadExisting(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return onMain(() -> {
            FlightProfile known = lookup(uuid);
            if (known != null && isAuthoritative(known)) {
                return CompletableFuture.completedFuture(Optional.of(known));
            }
            if (stopped) {
                return CompletableFuture.failedFuture(new StorageException("Stockage arrêté."));
            }
            return reload(uuid);
        });
    }

    /**
     * Une instance en mémoire fait foi si le stockage n'est pas partagé (SQLite), si le joueur est en ligne
     * ici, ou si elle porte des modifications locales non encore écrites. Sinon (MySQL, profil hors-ligne
     * propre), la base a pu être modifiée par un autre serveur : il faut la relire (même règle que
     * {@link #loadOnline}).
     */
    private boolean isAuthoritative(FlightProfile profile) {
        UUID uuid = profile.uuid();
        return type == StorageType.SQLITE || online.containsKey(uuid) || profile.isDirty()
                || pendingWrites.containsKey(uuid);
    }

    /**
     * Évince l'instance hors-ligne propre (périmée possible) puis relit la base. La lecture passe en file
     * sur {@code AAFly-IO} après toute écriture déjà soumise.
     */
    private CompletableFuture<Optional<FlightProfile>> reload(UUID uuid) {
        FlightProfile cached = offline.peek(uuid);
        if (cached != null && !isAuthoritative(cached)) {
            offline.remove(uuid);
        }
        return repository.find(uuid).thenApplyAsync(found -> found.map(this::adopt), mainThread);
    }

    @Override
    public CompletableFuture<Optional<FlightProfile>> findByName(String name) {
        if (name == null || !FlightProfile.NAME_PATTERN.matcher(name).matches()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return onMain(() -> {
            for (FlightProfile p : online.values()) {
                if (p.lastKnownName().equalsIgnoreCase(name)) {
                    return CompletableFuture.completedFuture(Optional.of(p));
                }
            }
            for (FlightProfile p : pendingWrites.values()) {
                if (p.lastKnownName().equalsIgnoreCase(name)) {
                    return CompletableFuture.completedFuture(Optional.of(p));
                }
            }
            UUID cachedUuid = null;
            for (FlightProfile p : offline.values()) {
                if (p.lastKnownName().equalsIgnoreCase(name)) {
                    if (isAuthoritative(p)) {
                        offline.get(p.uuid()); // rafraîchit l'accès LRU
                        return CompletableFuture.completedFuture(Optional.of(p));
                    }
                    cachedUuid = p.uuid(); // MySQL : sert seulement à résoudre l'UUID, profil relu en base
                    break;
                }
            }
            if (stopped) {
                return CompletableFuture.failedFuture(new StorageException("Stockage arrêté."));
            }
            if (cachedUuid != null) {
                return reload(cachedUuid);
            }
            return repository.findByName(name).thenApplyAsync(found -> found.map(this::adopt), mainThread);
        });
    }

    /** Résout un instantané lu en base vers l'instance unique (existante ou nouvelle, mise en cache). */
    private FlightProfile adopt(FlightProfileSnapshot snapshot) {
        FlightProfile existing = lookup(snapshot.uuid());
        if (existing != null) {
            return existing;
        }
        FlightProfile profile = FlightProfile.fromSnapshot(snapshot);
        cacheOffline(profile);
        return profile;
    }

    /** Instance en mémoire pour ce joueur, où qu'elle soit (main thread). */
    private @Nullable FlightProfile lookup(UUID uuid) {
        FlightProfile p = online.get(uuid);
        if (p != null) {
            return p;
        }
        p = offline.get(uuid);
        if (p != null) {
            return p;
        }
        return pendingWrites.get(uuid);
    }

    private void cacheOffline(FlightProfile profile) {
        pendingWrites.remove(profile.uuid());
        if (profile.isDirty()) {
            pendingWrites.put(profile.uuid(), profile);
        }
        for (FlightProfile evicted : offline.put(profile)) {
            if (evicted.isDirty()) {
                pendingWrites.put(evicted.uuid(), evicted);
            }
        }
    }

    // ------------------------------------------------------------------ écriture

    @Override
    public void markDirty(FlightProfile profile) {
        Objects.requireNonNull(profile, "profile");
        FlightProfile current = lookup(profile.uuid());
        if (current != null && current != profile) {
            // Instance périmée détenue par l'appelant : l'écrire écraserait la version courante.
            logger.warning("markDirty ignoré pour " + profile.uuid() + " : instance périmée (profil rechargé entre-temps).");
            return;
        }
        profile.markDirty();
        if (!online.containsKey(profile.uuid())) {
            pendingWrites.put(profile.uuid(), profile);
        }
    }

    @Override
    public void unload(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        sessions.remove(uuid);
        FlightProfile profile = online.remove(uuid);
        if (profile == null) {
            return; // chargement en cours : loadOnline placera le profil hors-ligne à son retour
        }
        profile.setLastUpdateEpochSeconds(nowEpochSeconds());
        FlightProfileSnapshot snapshot = profile.snapshot();
        profile.clearDirty();
        pendingWrites.remove(uuid);
        // Conservé hors-ligne : une reconnexion rapide retrouve la même instance, sans relecture.
        for (FlightProfile evicted : offline.put(profile)) {
            if (evicted.isDirty()) {
                pendingWrites.put(evicted.uuid(), evicted);
            }
        }
        if (stopped) {
            return;
        }
        repository.upsert(snapshot).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Sauvegarde du profil " + uuid + " à la déconnexion en erreur,"
                        + " nouvel essai au prochain flush.", unwrap(error));
                runOnMain(() -> requeue(List.of(profile)));
            }
        });
    }

    @Override
    public CompletableFuture<Void> flush() {
        return onMain(() -> {
            if (stopped) {
                return CompletableFuture.completedFuture(null);
            }
            List<FlightProfile> batch = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            long now = nowEpochSeconds();
            for (FlightProfile p : online.values()) {
                if (p.isDirty()) {
                    p.setLastUpdateEpochSeconds(now);
                    batch.add(p);
                    seen.add(p.uuid());
                }
            }
            collectDirty(batch, seen);
            if (batch.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            List<FlightProfileSnapshot> snapshots = snapshotAndClear(batch);
            return repository.saveAll(snapshots).whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Sauvegarde périodique de " + snapshots.size()
                            + " profil(s) en erreur, nouvel essai au prochain flush.", unwrap(error));
                    runOnMain(() -> requeue(batch));
                }
            });
        });
    }

    @Override
    public CompletableFuture<Void> save(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return onMain(() -> {
            if (stopped) {
                return CompletableFuture.completedFuture(null);
            }
            FlightProfile profile = lookup(uuid);
            if (profile == null || !profile.isDirty()) {
                return CompletableFuture.completedFuture(null);
            }
            FlightProfileSnapshot snapshot = profile.snapshot();
            profile.clearDirty();
            pendingWrites.remove(uuid);
            return repository.upsert(snapshot).whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Sauvegarde du profil " + uuid
                            + " en erreur, nouvel essai au prochain flush.", unwrap(error));
                    runOnMain(() -> requeue(List.of(profile)));
                }
            });
        });
    }

    private void periodicFlush() {
        flush().exceptionally(error -> null); // déjà journalisé
    }

    private void cleanupOffline() {
        for (FlightProfile evicted : offline.evictExpired()) {
            if (evicted.isDirty()) {
                pendingWrites.put(evicted.uuid(), evicted);
            }
        }
    }

    /** Ajoute les profils hors-ligne modifiés (cache + en attente) non encore vus. Vide {@code pendingWrites}. */
    private void collectDirty(List<FlightProfile> batch, Set<UUID> seen) {
        for (FlightProfile p : offline.values()) {
            if (p.isDirty() && seen.add(p.uuid())) {
                batch.add(p);
            }
        }
        for (FlightProfile p : pendingWrites.values()) {
            if (p.isDirty() && seen.add(p.uuid())) {
                batch.add(p);
            }
        }
        pendingWrites.clear();
    }

    private static List<FlightProfileSnapshot> snapshotAndClear(List<FlightProfile> batch) {
        List<FlightProfileSnapshot> out = new ArrayList<>(batch.size());
        for (FlightProfile p : batch) {
            out.add(p.snapshot());
            p.clearDirty();
        }
        return out;
    }

    /** Après un échec d'écriture (main thread) : remet les profils en file sans casser l'unicité des instances. */
    private void requeue(List<FlightProfile> failed) {
        if (stopped) {
            return;
        }
        for (FlightProfile p : failed) {
            FlightProfile current = lookup(p.uuid());
            if (current == null) {
                p.markDirty();
                pendingWrites.put(p.uuid(), p);
            } else if (current == p) {
                p.markDirty();
                if (!online.containsKey(p.uuid())) {
                    pendingWrites.put(p.uuid(), p);
                }
            } else {
                logger.warning("Profil " + p.uuid() + " rechargé entre-temps : la modification non sauvegardée"
                        + " est abandonnée au profit de la version courante.");
            }
        }
    }

    // ------------------------------------------------------------------ threads

    /** Exécute sur le main thread (immédiatement si on y est déjà). */
    private void runOnMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            // Plugin désactivé : plus de retour possible sur le main thread, la tâche est abandonnée.
            logger.fine("Tâche de retour au main thread abandonnée (plugin désactivé).");
        }
    }

    /** Exécute {@code body} sur le main thread et aplatit la future obtenue. */
    private <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> body) {
        if (plugin.getServer().isPrimaryThread()) {
            try {
                return body.get();
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.supplyAsync(body, mainThread).thenCompose(f -> f);
    }

    private static void cancel(@Nullable BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException)
                && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    /**
     * @return chemin du fichier SQLite, ou vide en MySQL (diagnostic)
     */
    public Optional<Path> sqliteFile() {
        return repository instanceof SqliteProfileRepository s ? Optional.of(s.file()) : Optional.empty();
    }
}
