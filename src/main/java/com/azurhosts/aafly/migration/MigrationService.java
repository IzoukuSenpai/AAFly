package com.azurhosts.aafly.migration;

import com.azurhosts.aafly.storage.AbstractSqlProfileRepository;
import com.azurhosts.aafly.storage.DefaultStorageManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Orchestration d'une migration (SPEC-phase2 A9) :
 * <ol>
 *   <li>lecture de la source sur un thread dédié {@code AAFly-Migration} (jamais le main thread) ;</li>
 *   <li>import dans <b>une transaction</b> sur {@code AAFly-IO} (ordonné avec les sauvegardes) ;
 *       essai à blanc : rollback systématique ;</li>
 *   <li>retour sur le main thread : profils en ligne rechargés
 *       ({@link DefaultStorageManager#reloadAfterImport}), puis rapport à l'émetteur.</li>
 * </ol>
 * Une seule migration à la fois.
 */
public final class MigrationService {

    /** Source d'une migration. */
    public enum Source {
        /** wFly, base SQLite. */
        WFLY_SQLITE("wFly (SQLite)"),
        /** wFly, base MySQL. */
        WFLY_MYSQL("wFly (MySQL)"),
        /** TempFly, fichier YAML. */
        TEMPFLY("TempFly");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /**
         * @return libellé
         */
        public String label() {
            return label;
        }
    }

    /**
     * Demande de migration.
     *
     * @param source   source
     * @param strategy fusion
     * @param dryRun   essai à blanc
     */
    public record Request(Source source, MergeStrategy strategy, boolean dryRun) {
        public Request {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(strategy, "strategy");
        }
    }

    /**
     * Issue d'une migration, remise sur le main thread.
     *
     * @param report          rapport (null en cas d'échec)
     * @param reloadedOnline  profils en ligne rechargés
     * @param error           message d'échec clair (null en cas de succès)
     */
    public record Outcome(@Nullable ImportReport report, int reloadedOnline, @Nullable String error) {
    }

    private final Plugin plugin;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * @param plugin plugin (scheduler, logger)
     */
    public MigrationService(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
    }

    /**
     * @return {@code true} si une migration est en cours
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Lance une migration (main thread).
     *
     * @param request      demande
     * @param storage      stockage AAFly
     * @param pluginsDir   dossier {@code plugins}
     * @param maxSeconds   {@code limits.max-seconds} (lu sur le main thread)
     * @param defaultSpeed {@code flight.default-speed}
     * @param callback     appelé une fois sur le main thread avec l'issue
     * @return {@code false} si une migration est déjà en cours (rien n'est lancé)
     */
    public boolean start(Request request, DefaultStorageManager storage, Path pluginsDir, long maxSeconds,
                         double defaultSpeed, Consumer<Outcome> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(pluginsDir, "pluginsDir");
        Objects.requireNonNull(callback, "callback");
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AAFly-Migration");
            t.setDaemon(true);
            return t;
        });
        AbstractSqlProfileRepository repo = storage.sqlRepository();
        long now = System.currentTimeMillis() / 1000L;
        CompletableFuture<ImportReport> imported;
        try {
            imported = CompletableFuture
                    .supplyAsync(() -> readSource(request.source(), pluginsDir), reader)
                    .thenCompose(rows -> repo.inTransaction("import " + request.source().label(), !request.dryRun(),
                            c -> ProfileImporter.run(c, rows, request.strategy(), request.dryRun(), maxSeconds,
                                    defaultSpeed, now)));
        } catch (RuntimeException e) {
            reader.shutdownNow();
            running.set(false);
            throw e;
        }
        imported.whenComplete((report, error) -> {
            reader.shutdown();
            runOnMain(() -> finish(request, storage, report, error, callback));
        });
        return true;
    }

    private List<SourceRow> readSource(Source source, Path pluginsDir) {
        return switch (source) {
            case WFLY_SQLITE -> MigrationSources.readWFlySqlite(pluginsDir, logger);
            case WFLY_MYSQL -> MigrationSources.readWFlyMysql(pluginsDir, logger);
            case TEMPFLY -> MigrationSources.readTempFly(pluginsDir);
        };
    }

    private void finish(Request request, DefaultStorageManager storage, @Nullable ImportReport report,
                        @Nullable Throwable error,
                        Consumer<Outcome> callback) {
        if (error != null || report == null) {
            running.set(false);
            String message = describe(error);
            logger.log(Level.WARNING, "Migration " + request.source().label() + " échouée : " + message,
                    error instanceof MigrationException ? null : unwrap(error));
            deliver(callback, new Outcome(null, 0, message));
            return;
        }
        logger.info("Migration " + request.source().label() + " : " + report.summary());
        if (report.dryRun() || report.changed().isEmpty()) {
            running.set(false);
            deliver(callback, new Outcome(report, 0, null));
            return;
        }
        storage.reloadAfterImport(report.changed()).whenComplete((reloaded, reloadError) -> runOnMain(() -> {
            running.set(false);
            if (reloadError != null) {
                logger.log(Level.WARNING, "Import validé mais rechargement des profils en ligne incomplet.",
                        unwrap(reloadError));
            }
            deliver(callback, new Outcome(report, reloaded == null ? 0 : reloaded, null));
        }));
    }

    private void deliver(Consumer<Outcome> callback, Outcome outcome) {
        try {
            callback.accept(outcome);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Rapport de migration non transmis", e);
        }
    }

    private static String describe(@Nullable Throwable error) {
        Throwable t = unwrap(error);
        // Message sans secret : MigrationException (rédigé ici) ou message SQL/stockage (jamais de mot de passe).
        String msg = t == null ? null : t.getMessage();
        return msg == null || msg.isBlank() ? (t == null ? "erreur inconnue" : t.getClass().getSimpleName()) : msg;
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable t) {
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    private void runOnMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) {
            running.set(false); // plugin désactivé : plus de retour possible
        }
    }
}
