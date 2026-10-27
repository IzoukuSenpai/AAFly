package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.placeholder.PlaceholderBridge;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Implémentation serveur de {@link ActionRuntime}.
 */
public final class BukkitActionRuntime implements ActionRuntime {

    /** Nombre maximal d'avertissements dédupliqués mémorisés. */
    public static final int MAX_WARN_KEYS = 1024;

    private final Plugin plugin;
    private final Lang lang;
    private final PlaceholderBridge placeholders;
    private final @Nullable Function<Player, String> remainingProvider;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Map<String, Key> soundsByName;

    /**
     * @param plugin            plugin (scheduler, logger)
     * @param lang              rendu des messages (instance rechargée en place)
     * @param placeholders      pont PlaceholderAPI
     * @param remainingProvider temps restant formaté d'un joueur ({@code null} : vide)
     */
    public BukkitActionRuntime(Plugin plugin, Lang lang, PlaceholderBridge placeholders,
                               @Nullable Function<Player, String> remainingProvider) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lang = Objects.requireNonNull(lang, "lang");
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.remainingProvider = remainingProvider;
    }

    @Override
    public Lang lang() {
        return lang;
    }

    @Override
    public PlaceholderBridge placeholders() {
        return placeholders;
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public void dispatchConsole(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void runOnMain(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public Optional<Key> resolveSoundName(String enumName) {
        Map<String, Key> map = soundsByName;
        if (map == null) {
            map = buildSoundIndex();
            soundsByName = map;
        }
        return Optional.ofNullable(map.get(enumName.toUpperCase(Locale.ROOT)));
    }

    /**
     * Index nom de style enum → clé, construit une fois depuis le registre des sons
     * ({@code minecraft:block.note_block.pling} → {@code BLOCK_NOTE_BLOCK_PLING}).
     */
    private Map<String, Key> buildSoundIndex() {
        Map<String, Key> map = new HashMap<>();
        try {
            Registry.SOUNDS.keyStream().forEach(nk -> {
                Key key = Key.key(nk.namespace(), nk.value());
                if (Key.MINECRAFT_NAMESPACE.equals(key.namespace())) {
                    map.putIfAbsent(key.value().replace('.', '_').toUpperCase(Locale.ROOT), key);
                }
            });
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Impossible d'indexer les sons du serveur.", e);
        }
        return Map.copyOf(map);
    }

    @Override
    public @Nullable String remaining(Player player) {
        return remainingProvider == null ? null : remainingProvider.apply(player);
    }

    @Override
    public void warnOnce(String key, String message) {
        if (warned.size() >= MAX_WARN_KEYS) {
            warned.clear();
        }
        if (warned.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    /** Oublie les avertissements déjà émis (après un rechargement). */
    public void clearWarnings() {
        warned.clear();
    }
}
