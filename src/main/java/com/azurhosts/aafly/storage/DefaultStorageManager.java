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
import java.util.OptionalLong;
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
 * Implémentation de {@link StorageManager} (SPEC §11.2, §12 ; SPEC-phase2 A5).
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li>Tout l'état (caches, dirty-set, écritures en cours, backoff) est confiné au <b>main thread</b> ;
 *       les {@link FlightProfile} n'en sortent jamais : seuls des {@link FlightProfileSnapshot} immuables
 *       partent vers l'I/O.</li>
 *   <li>L'I/O s'exécute sur un executor <b>mono-thread possédé par ce gestionnaire</b> nommé
 *       {@code AAFly-IO} : les opérations SQL sont traitées dans l'ordre de soumission.</li>
 *   <li>Le retour sur le main thread passe par {@code BukkitScheduler#runTask} ; aucun appel à l'API
 *       Bukkit n'est fait depuis {@code AAFly-IO}.</li>
 * </ul>
 *
 * <h2>Invariant</h2>
 * Au plus une instance de {@link FlightProfile} par UUID en mémoire : en ligne, cache hors-ligne
 * (LRU 256 / 10 min) ou en attente d'écriture ({@code pendingWrites}). Toute lecture en base se termine
 * par une re-vérification de ces trois emplacements avant de créer une instance. Quand une instance
 * quitte la mémoire, le dépôt en est informé ({@link AbstractSqlProfileRepository#release(UUID)}) pour
 * oublier sa base de verrou optimiste.
 *
 * <h2>Persistance</h2>
 * Dirty-set écrit toutes les {@code storage.save-interval-seconds}, sauvegarde asynchrone à la
 * déconnexion, flush synchrone borné à l'arrêt puis écriture de {@code aafly_server.shutdown_at}.
 * Le drapeau {@code dirty} n'est effacé <b>qu'après</b> succès de l'écriture, et seulement si le profil
 * n'a pas changé depuis l'instantané écrit. Après un échec, le profil reste à écrire et le flush
 * périodique est différé selon un backoff exponentiel borné ({@value #BACKOFF_BASE_SECONDS} s à
 * {@value #BACKOFF_MAX_SECONDS} s, avec une nouvelle tentative planifiée). Un échec de lecture
 * n'entraîne <b>jamais</b> la création d'un profil vierge (qui écraserait les données en base).
 *
 * <h2>Arrêt</h2>
 * Flush final borné à {@value StorageManager#SHUTDOWN_TIMEOUT_SECONDS} s, puis {@code shutdown()} de
 * l'executor, {@code awaitTermination(10 s)}, {@code shutdownNow()} si nécessaire, fermeture du pool.
 */
public final class DefaultStorageManager implements StorageManager {

    /** Nom des threads I/O. */
    public static final String IO_THREAD_NAME = "AAFly-IO";
    /** Intervalle minimal de sauvegarde périodique (secondes). */
    public static final int MIN_SAVE_INTERVAL_SECONDS = 10;
    /** Premier délai de nouvelle tentative après un échec d'écriture (secondes). */
    public static final long BACKOFF_BASE_SECONDS = 5L;
    /** Délai maximal entre deux tentatives après échecs répétés (secondes). */
    public static final long BACKOFF_MAX_SECONDS = 300L;
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
    /** Profils modifiés à écrire qui ne sont ni en ligne ni dans le cache hors-ligne (évincés avant écriture). */
    private final Map<UUID, FlightProfile> pendingWrites = new LinkedHashMap<>();
    /** Joueurs dont la session est ouverte (loadOnline appelé, unload pas encore). */
    private final Set<UUID> sessions = new HashSet<>();
    /** Nombre d'écritures soumises et non terminées, par joueur (le flush périodique ne les double pas). */
    private final Map<UUID, Integer> inFlight = new HashMap<>();
    /** Joueurs en cours de relecture après import : aucune écriture ne doit être soumise. */
    private final Set<UUID> reloading = new HashSet<>();
    private int consecutiveFailures;
    private long backoffUntilMillis;

    private @Nullable BukkitTask saveTask;
    private @Nullable BukkitTask cleanupTask;
    private @Nullable BukkitTask retryTask;
    private volatile boolean started;
    private volatile boolean stopped;
    private volatile OptionalLong lastShutdownAt = OptionalLong.empty();

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
        // Une entrée propre expirée quitte la mémoire : la base de verrou optimiste est oubliée.
        // Une écriture encore en cours garde l'instance suivie (en cas d'échec elle reste à écrire).
        offline.setExpiryListener(p -> {
            if (inFlight.containsKey(p.uuid())) {
                pendingWrites.put(p.uuid(), p);
            } else {
                releaseIfGone(p.uuid());
            }
        });
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

    /**
     * Délai de nouvelle tentative après {@code failures} échecs consécutifs : {@value #BACKOFF_BASE_SECONDS} s
     * doublé à chaque échec, plafonné à {@value #BACKOFF_MAX_SECONDS} s.
     *
     * @param failures échecs consécutifs (≥ 1)
     * @return délai en secondes
     */
    public static long backoffSeconds(int failures) {
        int exp = Math.max(0, Math.min(failures - 1, 16));
        return Math.min(BACKOFF_MAX_SECONDS, BACKOFF_BASE_SECONDS << exp);
    }

    // ------------------------------------------------------------------ cycle de vie

    @Override
    public CompletableFuture<Void> start() {
        return repository.init()
                .thenCompose(ignored -> repository.serverValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT))
                .thenAcceptAsync(v -> {
                    lastShutdownAt = v;
                    onStarted();
                }, mainThread);
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
            lastShutdownAt = repository.serverValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT)
                    .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    /**
     * @return heure du dernier arrêt propre enregistrée en base ({@code aafly_server.shutdown_at}, epoch
     *         secondes), lue au démarrage ; vide si inconnue
     */
    public OptionalLong lastShutdownAt() {
        return lastShutdownAt;
    }

    @Override
    public void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        cancel(saveTask);
        cancel(cleanupTask);
        cancel(retryTask);
        saveTask = null;
        cleanupTask = null;
        retryTask = null;
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
        collectDirty(batch, seen, true);
        List<FlightProfileSnapshot> snapshots = snapshots(batch);

        // 2. Écriture finale puis heure d'arrêt, en attente bornée (les écritures déjà en file passent avant).
        try {
            if (!snapshots.isEmpty()) {
                Set<UUID> unwritten = repository.saveReporting(snapshots)
                        .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                logger.info((snapshots.size() - unwritten.size()) + " profil(s) sauvegardé(s) à l'arrêt.");
                if (!unwritten.isEmpty()) {
                    logger.severe(unwritten.size() + " profil(s) non sauvegardé(s) à l'arrêt (conflits de version).");
                }
            }
            repository.putServerValue(AbstractSqlProfileRepository.KEY_SHUTDOWN_AT, now)
                    .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.severe("Sauvegarde finale interrompue : " + snapshots.size() + " profil(s) peut-être perdus.");
        } catch (TimeoutException e) {
            logger.severe("Sauvegarde finale non terminée après " + SHUTDOWN_TIMEOUT_SECONDS + " s : "
                    + snapshots.size() + " profil(s) peut-être perdus.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            logger.log(Level.SEVERE, "Sauvegarde finale en erreur : " + snapshots.size()
                    + " profil(s) peut-être non sauvegardé(s).", cause);
        }

        // 3. Executor possédé : shutdown → awaitTermination(10 s) → shutdownNow ; puis fermeture du pool.
        io.shutdown();
        try {
            if (!io.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
        inFlight.clear();
        reloading.clear();
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

    /**
     * @return dépôt SQL concret (import, valeurs serveur)
     */
    public AbstractSqlProfileRepository sqlRepository() {
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

    /**
     * @return échecs d'écriture consécutifs (diagnostic ; 0 si la dernière écriture a réussi)
     */
    public int consecutiveWriteFailures() {
        return consecutiveFailures;
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
            // Un profil modifié localement (dirty, en attente ou en cours d'écriture) reste la référence.
            if (local != null && (type == StorageType.SQLITE || local.isDirty() || pendingWrites.containsKey(uuid)
                    || inFlight.containsKey(uuid) || reloading.contains(uuid))) {
                promoteOnline(local, name);
                return CompletableFuture.completedFuture(local);
            }
            if (local != null) {
                // Instance propre abandonnée (MySQL) : la base de verrou optimiste repartira de la relecture.
                repository.release(uuid);
            }
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
                || pendingWrites.containsKey(uuid) || inFlight.containsKey(uuid) || reloading.contains(uuid);
    }

    /**
     * Évince l'instance hors-ligne propre (périmée possible) puis relit la base. La lecture passe en file
     * sur {@code AAFly-IO} après toute écriture déjà soumise.
     */
    private CompletableFuture<Optional<FlightProfile>> reload(UUID uuid) {
        FlightProfile cached = offline.peek(uuid);
        if (cached != null && !isAuthoritative(cached)) {
            offline.remove(uuid);
            repository.release(uuid);
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

    /** Instance en mémoire sans effet sur le cache (ni accès LRU, ni expiration). */
    private @Nullable FlightProfile peekInstance(UUID uuid) {
        FlightProfile p = online.get(uuid);
        if (p != null) {
            return p;
        }
        p = offline.peek(uuid);
        return p != null ? p : pendingWrites.get(uuid);
    }

    private void cacheOffline(FlightProfile profile) {
        pendingWrites.remove(profile.uuid());
        if (profile.isDirty()) {
            pendingWrites.put(profile.uuid(), profile);
        }
        handleEvicted(offline.put(profile));
    }

    /** Profils sortis du cache hors-ligne : les modifiés restent à écrire, les propres quittent la mémoire. */
    private void handleEvicted(List<FlightProfile> evicted) {
        for (FlightProfile e : evicted) {
            if (e.isDirty() || inFlight.containsKey(e.uuid())) {
                pendingWrites.put(e.uuid(), e);
            } else {
                releaseIfGone(e.uuid());
            }
        }
    }

    /** Si plus aucune instance n'est en mémoire pour ce joueur, le dépôt oublie sa base. */
    private void releaseIfGone(UUID uuid) {
        if (peekInstance(uuid) == null && !stopped) {
            repository.release(uuid);
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
        if (!online.containsKey(profile.uuid()) && !offline.contains(profile)) {
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
        pendingWrites.remove(uuid);
        // Conservé hors-ligne : une reconnexion rapide retrouve la même instance, sans relecture.
        handleEvicted(offline.put(profile));
        if (stopped) {
            return;
        }
        if (reloading.contains(uuid)) {
            profile.markDirty(); // écrit après la relecture en cours (import)
            return;
        }
        // Sauvegarde au quit, même si rien n'a changé (lastUpdate sert au décompte hors-ligne).
        submit(List.of(profile), "à la déconnexion");
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
                if (p.isDirty() && writable(p.uuid())) {
                    p.setLastUpdateEpochSeconds(now);
                    batch.add(p);
                    seen.add(p.uuid());
                }
            }
            collectDirty(batch, seen, false);
            if (batch.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return submit(batch, "périodique");
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
            if (profile == null || !profile.isDirty() || reloading.contains(uuid)) {
                return CompletableFuture.completedFuture(null);
            }
            return submit(List.of(profile), "immédiate");
        });
    }

    /**
     * Soumet l'écriture des profils (main thread). Le drapeau {@code dirty} reste posé jusqu'au retour :
     * effacé en cas de succès si le profil n'a pas changé entre-temps, conservé (et suivi) sinon.
     *
     * @param profiles profils (instances courantes)
     * @param label    libellé pour les logs
     * @return future complétée après écriture (échouée si l'écriture a échoué)
     */
    private CompletableFuture<Void> submit(List<FlightProfile> profiles, String label) {
        return submitTracked(profiles, label).thenApply(outcome -> {
            Throwable error = outcome.error();
            if (error != null) {
                throw error instanceof CompletionException ce ? ce : new CompletionException(error);
            }
            return null;
        });
    }

    /**
     * Issue d'une écriture soumise.
     *
     * @param error     erreur (toute l'écriture a échoué), ou {@code null}
     * @param unwritten joueurs non écrits (tous si {@code error} n'est pas nul)
     */
    private record WriteOutcome(@Nullable Throwable error, Set<UUID> unwritten) {
    }

    /**
     * Comme {@link #submit}, mais la future n'échoue jamais : elle donne les joueurs non écrits. Elle se termine
     * <b>après</b> la planification de {@link #onWriteDone} sur le main thread (qui s'exécute donc avant toute
     * continuation planifiée ensuite sur le main thread).
     */
    private CompletableFuture<WriteOutcome> submitTracked(List<FlightProfile> profiles, String label) {
        List<FlightProfileSnapshot> snapshots = snapshots(profiles);
        for (FlightProfile p : profiles) {
            inFlight.merge(p.uuid(), 1, Integer::sum);
        }
        CompletableFuture<AbstractSqlProfileRepository.WriteReport> write = repository.saveDetailed(snapshots);
        return write.handle((report, error) -> {
            runOnMain(() -> onWriteDone(profiles, snapshots, report, error, label));
            if (error != null) {
                Set<UUID> all = new HashSet<>();
                for (FlightProfileSnapshot s : snapshots) {
                    all.add(s.uuid());
                }
                return new WriteOutcome(error, all);
            }
            return new WriteOutcome(null, report.unwritten());
        });
    }

    private void onWriteDone(List<FlightProfile> profiles, List<FlightProfileSnapshot> snapshots,
                             AbstractSqlProfileRepository.@Nullable WriteReport report, @Nullable Throwable error,
                             String label) {
        if (stopped) {
            return;
        }
        Set<UUID> unwritten = report == null ? null : report.unwritten();
        for (int i = 0; i < profiles.size(); i++) {
            FlightProfile p = profiles.get(i);
            UUID uuid = p.uuid();
            inFlight.computeIfPresent(uuid, (k, n) -> n <= 1 ? null : n - 1);
            boolean written = error == null && unwritten != null && !unwritten.contains(uuid);
            if (written) {
                if (p.isDirty() && snapshots.get(i).equals(p.snapshot())) {
                    p.clearDirty();
                }
                Long remote = report.remoteDeltas().get(uuid);
                if (remote != null) {
                    absorbRemoteDelta(p, remote);
                }
            } else {
                p.markDirty();
            }
            settle(p, !written);
        }
        if (error != null) {
            consecutiveFailures++;
            long delay = backoffSeconds(consecutiveFailures);
            backoffUntilMillis = System.currentTimeMillis() + delay * 1000L;
            logger.log(Level.WARNING, "Sauvegarde " + label + " de " + profiles.size() + " profil(s) en erreur ("
                    + consecutiveFailures + " échec(s) consécutif(s)), nouvel essai dans " + delay + " s.", unwrap(error));
            scheduleRetry(delay);
        } else {
            consecutiveFailures = 0;
            backoffUntilMillis = 0L;
        }
    }

    /**
     * SPEC-phase2 A5 : ré-applique en mémoire l'écart dû aux écritures distantes constaté par le verrou optimiste
     * (autre serveur, {@code /aafly take} distant…), puis le confirme au dépôt dans le même tick (la base passe dans
     * les nouvelles coordonnées de la mémoire). Ignoré si l'instance n'est plus la courante ou si une relecture
     * après import est en cours (elle fixera la valeur). Si le solde tombe à 0, le ticker expire le vol au
     * prochain tick.
     */
    private void absorbRemoteDelta(FlightProfile p, long delta) {
        UUID uuid = p.uuid();
        if (delta == 0L || reloading.contains(uuid) || peekInstance(uuid) != p) {
            return;
        }
        boolean wasDirty = p.isDirty();
        long old = p.remainingSeconds();
        long target = Math.max(0L, AbstractSqlProfileRepository.saturatedAdd(old, delta));
        p.setRemainingSeconds(target);
        repository.absorbRemoteDelta(uuid, delta);
        if (!wasDirty && target - old == delta) {
            p.clearDirty(); // mémoire identique à la base
        } else {
            markDirty(p); // valeur bornée à 0 : la base suivra la mémoire à la prochaine écriture
        }
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Écart distant de " + delta + " s ré-appliqué au profil " + uuid + " (" + old + " -> "
                    + target + ").");
        }
    }

    /** Après une écriture : garde le profil suivi s'il reste à écrire, libère l'instance sinon. */
    private void settle(FlightProfile p, boolean failed) {
        UUID uuid = p.uuid();
        FlightProfile current = peekInstance(uuid);
        if (current == null) {
            if (p.isDirty()) {
                pendingWrites.put(uuid, p);
            } else if (!inFlight.containsKey(uuid)) {
                repository.release(uuid);
            }
            return;
        }
        if (current != p) {
            if (failed) {
                logger.warning("Profil " + uuid + " rechargé entre-temps : la modification non sauvegardée"
                        + " est abandonnée au profit de la version courante.");
            }
            return;
        }
        boolean cached = online.containsKey(uuid) || offline.contains(p);
        if (p.isDirty()) {
            if (!cached) {
                pendingWrites.put(uuid, p);
            }
        } else if (!cached && !inFlight.containsKey(uuid)) {
            pendingWrites.remove(uuid);
            repository.release(uuid);
        }
    }

    private void scheduleRetry(long delaySeconds) {
        if (retryTask != null || stopped) {
            return;
        }
        try {
            retryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                retryTask = null;
                flush().exceptionally(e -> null); // déjà journalisé
            }, Math.max(1L, delaySeconds * 20L));
        } catch (IllegalPluginAccessException e) {
            logger.fine("Nouvel essai de sauvegarde non planifié (plugin désactivé).");
        }
    }

    private boolean writable(UUID uuid) {
        return !inFlight.containsKey(uuid) && !reloading.contains(uuid);
    }

    private void periodicFlush() {
        if (System.currentTimeMillis() < backoffUntilMillis) {
            return; // backoff après échec : la tentative planifiée s'en charge
        }
        flush().exceptionally(error -> null); // déjà journalisé
    }

    private void cleanupOffline() {
        handleEvicted(offline.evictExpired());
    }

    /**
     * Ajoute les profils hors-ligne modifiés (cache + en attente) non encore vus.
     *
     * @param all {@code true} à l'arrêt : inclut les profils en cours d'écriture ou de relecture
     */
    private void collectDirty(List<FlightProfile> batch, Set<UUID> seen, boolean all) {
        for (FlightProfile p : offline.values()) {
            if (p.isDirty() && (all || writable(p.uuid())) && seen.add(p.uuid())) {
                batch.add(p);
            }
        }
        for (FlightProfile p : pendingWrites.values()) {
            if (p.isDirty() && (all || writable(p.uuid())) && seen.add(p.uuid())) {
                batch.add(p);
            }
        }
    }

    private static List<FlightProfileSnapshot> snapshots(List<FlightProfile> batch) {
        List<FlightProfileSnapshot> out = new ArrayList<>(batch.size());
        for (FlightProfile p : batch) {
            out.add(p.snapshot());
        }
        return out;
    }

    // ------------------------------------------------------------------ import externe (migration)

    /**
     * Après un import qui a modifié des lignes {@code aafly_profiles} (SPEC-phase2 A9), à appeler sur le
     * main thread : les profils en ligne concernés sont d'abord écrits (le verrou optimiste fusionne leurs
     * modifications locales avec la valeur importée) puis relus ; leur temps restant en mémoire prend la
     * valeur en base <b>plus</b> les changements locaux faits pendant la relecture (décompte, crédits), qui ne
     * sont donc jamais perdus. Si l'écriture préalable d'un profil échoue, il n'est pas relu : sa prochaine
     * écriture versionnée fusionnera d'elle-même avec la valeur importée. Les instances hors-ligne propres sont
     * abandonnées (relues à la demande) ; les instances hors-ligne modifiées seront fusionnées à leur prochaine
     * écriture.
     *
     * @param changed joueurs dont la ligne a été créée ou modifiée par l'import
     * @return future du nombre de profils en ligne rechargés, complétée sur le main thread
     */
    public CompletableFuture<Integer> reloadAfterImport(Collection<UUID> changed) {
        Objects.requireNonNull(changed, "changed");
        Set<UUID> ids = Set.copyOf(changed);
        return onMain(() -> {
            if (stopped) {
                return CompletableFuture.failedFuture(new StorageException("Stockage arrêté."));
            }
            List<FlightProfile> onlineChanged = new ArrayList<>();
            for (UUID uuid : ids) {
                FlightProfile on = online.get(uuid);
                if (on != null) {
                    onlineChanged.add(on);
                    continue;
                }
                FlightProfile mem = peekInstance(uuid);
                if (mem == null) {
                    repository.release(uuid);
                } else if (!mem.isDirty() && !inFlight.containsKey(uuid) && !reloading.contains(uuid)) {
                    offline.remove(uuid);
                    pendingWrites.remove(uuid);
                    repository.release(uuid);
                }
            }
            if (onlineChanged.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            // 0. Aucune autre écriture de ces joueurs jusqu'à l'application de la relecture ; valeur en mémoire
            //    au moment de l'instantané écrit (référence des changements locaux faits pendant la relecture).
            List<FlightProfile> targets = new ArrayList<>();
            Map<UUID, Long> localAtSubmit = new HashMap<>();
            for (FlightProfile p : onlineChanged) {
                if (reloading.add(p.uuid())) {
                    targets.add(p);
                    localAtSubmit.put(p.uuid(), p.remainingSeconds());
                }
            }
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            // 1. Écriture des modifications locales (fusionnées avec l'import par le verrou optimiste).
            List<FlightProfile> dirty = new ArrayList<>();
            for (FlightProfile p : targets) {
                if (p.isDirty()) {
                    dirty.add(p);
                }
            }
            CompletableFuture<WriteOutcome> written = dirty.isEmpty()
                    ? CompletableFuture.completedFuture(new WriteOutcome(null, Set.of()))
                    : submitTracked(dirty, "avant relecture");
            // 2. Relecture, seulement si l'écriture a réussi, puis application en mémoire (main thread).
            return written.thenComposeAsync(outcome -> {
                List<CompletableFuture<Boolean>> reads = new ArrayList<>();
                for (FlightProfile p : targets) {
                    UUID uuid = p.uuid();
                    if (stopped || outcome.unwritten().contains(uuid)) {
                        // Écriture échouée : surtout ne pas remplacer la base (les modifications locales seraient
                        // perdues) ; la prochaine écriture versionnée fusionnera avec la valeur importée (le
                        // profil est resté marqué modifié par onWriteDone, exécuté avant cette continuation).
                        reloading.remove(uuid);
                        continue;
                    }
                    long reference = localAtSubmit.get(uuid);
                    reads.add(repository.refresh(uuid).handleAsync((found, error) -> {
                        reloading.remove(uuid);
                        if (error != null) {
                            logger.log(Level.WARNING, "Relecture du profil " + uuid + " après import impossible.",
                                    unwrap(error));
                            return false;
                        }
                        if (found.isEmpty() || peekInstance(uuid) != p) {
                            return false;
                        }
                        long db = found.get().remainingSeconds();
                        // Base relue : (v, db, db). Mémoire = db + changements locaux depuis l'instantané écrit,
                        // ce qui garde la mémoire et la base dans les mêmes coordonnées.
                        long localChange = p.remainingSeconds() - reference; // deux valeurs ≥ 0
                        long target = Math.max(0L, AbstractSqlProfileRepository.saturatedAdd(db, localChange));
                        boolean wasDirty = p.isDirty();
                        p.setRemainingSeconds(target);
                        if (target != db || wasDirty) {
                            markDirty(p);
                        } else {
                            p.clearDirty(); // valeur identique à la base
                        }
                        return true;
                    }, mainThread));
                }
                return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(v -> {
                    int n = 0;
                    for (CompletableFuture<Boolean> f : reads) {
                        if (Boolean.TRUE.equals(f.join())) {
                            n++;
                        }
                    }
                    return n;
                });
            }, mainThread);
        });
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
