package com.azurhosts.aafly.testutil;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Serveur Bukkit minimal pour les tests : installé dans {@link Bukkit} par réflexion et retiré par
 * {@link #close()}. Le thread qui l'installe est le « main thread » ; {@code runTask} met la tâche en file,
 * exécutée par {@link #pump(Future)} ; {@code runTaskTimer}/{@code runTaskLater} ne s'exécutent jamais.
 *
 * <p>Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class TestServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger("AAFlyTestServer");

    public final Thread mainThread = Thread.currentThread();
    public final Map<UUID, Player> players = new HashMap<>();
    public final List<RegisteredServiceProvider<?>> registrations = new ArrayList<>();
    public final List<Object> events = new ArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    public final Server server;

    private TestServer() {
        BukkitTask task = Fakes.proxy(BukkitTask.class, Map.of(), null);
        BukkitScheduler scheduler = Fakes.proxy(BukkitScheduler.class, Map.of(
                "runTask", a -> {
                    if (a.length == 2 && a[1] instanceof Runnable r) {
                        queue.add(r);
                    }
                    return task;
                },
                "runTaskTimer", a -> task,
                "runTaskLater", a -> task), null);
        PluginManager pm = Fakes.proxy(PluginManager.class, Map.of(
                "callEvent", a -> {
                    events.add(a[0]);
                    return null;
                }), null);
        ServicesManager services = Fakes.proxy(ServicesManager.class, Map.of(
                "getRegistrations", a -> {
                    List<RegisteredServiceProvider<?>> out = new ArrayList<>();
                    for (RegisteredServiceProvider<?> r : registrations) {
                        if (a[0] instanceof Class<?> c && r.getService() == c) {
                            out.add(r);
                        }
                    }
                    return out;
                }), null);
        Map<String, Function<Object[], Object>> answers = new HashMap<>();
        answers.put("isPrimaryThread", a -> Thread.currentThread() == mainThread);
        answers.put("getLogger", a -> LOG);
        answers.put("getScheduler", a -> scheduler);
        answers.put("getPluginManager", a -> pm);
        answers.put("getServicesManager", a -> services);
        answers.put("getPlayer", a -> a[0] instanceof UUID id ? players.get(id) : null);
        answers.put("getOnlinePlayers", a -> List.copyOf(players.values()));
        this.server = Fakes.proxy(Server.class, answers, null);
    }

    /**
     * Installe le serveur de test dans {@link Bukkit}.
     *
     * @return serveur installé
     */
    public static TestServer install() {
        TestServer s = new TestServer();
        setServer(s.server);
        return s;
    }

    /**
     * @param name     nom du plugin
     * @param enabled  plugin activé
     * @param dataDir  dossier de données (ou {@code null})
     * @return plugin simulé, rattaché à ce serveur
     */
    public Plugin plugin(String name, boolean enabled, File dataDir) {
        Logger logger = Logger.getLogger("AAFlyTest." + name);
        return Fakes.proxy(Plugin.class, Map.of(
                "getName", a -> name,
                "isEnabled", a -> enabled,
                "getServer", a -> server,
                "getLogger", a -> logger,
                "getDataFolder", a -> dataDir), null);
    }

    /**
     * @param player joueur à déclarer en ligne
     */
    public void join(Player player) {
        players.put(player.getUniqueId(), player);
    }

    /** Exécute les tâches en file (main thread). */
    public void runPending() {
        Runnable r;
        while ((r = queue.poll()) != null) {
            r.run();
        }
    }

    /**
     * Exécute les tâches « main thread » jusqu'à la fin de la future (10 s au plus).
     *
     * @param future future attendue
     */
    public void pump(Future<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!future.isDone()) {
            runPending();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("future non terminée");
            }
            Thread.onSpinWait();
        }
        runPending();
    }

    @Override
    public void close() {
        setServer(null);
    }

    private static void setServer(Server server) {
        try {
            Field f = Bukkit.class.getDeclaredField("server");
            f.setAccessible(true);
            f.set(null, server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
