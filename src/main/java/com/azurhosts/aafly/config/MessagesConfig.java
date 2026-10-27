package com.azurhosts.aafly.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Instantané immuable de {@code messages.yml}. Les clés sont des chemins pointés
 * (ex. {@code "errors.no-permission"}, {@code "admin.status"}). Une valeur est soit une chaîne,
 * soit une liste de chaînes. Thread-safe.
 */
public final class MessagesConfig {

    /** Clé du préfixe. */
    public static final String KEY_PREFIX = "prefix";
    /** Clé de la table des seuils. */
    public static final String KEY_TIME_REMAINING = "commands-time-remaining";
    /** Liste d'actions exécutée à l'activation du vol. */
    public static final String KEY_ON_ENABLE = "on-enable";
    /** Liste d'actions exécutée à la désactivation du vol. */
    public static final String KEY_ON_DISABLE = "on-disable";
    /** Liste d'actions exécutée à l'expiration du temps. */
    public static final String KEY_ON_EXPIRE = "on-expire";
    /** Liste d'actions exécutée quand une règle interdit le vol. */
    public static final String KEY_NOT_ALLOWED_HERE = "not-allowed-here";
    /** Version courante du schéma de {@code messages.yml}. */
    public static final int CURRENT_VERSION = 1;

    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;
    private final NavigableMap<Long, List<String>> timeRemaining;

    private MessagesConfig(Map<String, String> strings, Map<String, List<String>> lists,
                           NavigableMap<Long, List<String>> timeRemaining) {
        this.strings = Collections.unmodifiableMap(strings);
        this.lists = Collections.unmodifiableMap(lists);
        this.timeRemaining = Collections.unmodifiableNavigableMap(timeRemaining);
    }

    /**
     * Construit l'instantané depuis le nœud racine de {@code messages.yml}.
     *
     * @param root nœud racine
     * @return messages
     * @throws ConfigException si {@code commands-time-remaining} ou une valeur est mal formée
     */
    public static MessagesConfig from(ConfigurationNode root) throws ConfigException {
        Objects.requireNonNull(root, "root");
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> e : root.childrenMap().entrySet()) {
            String key = String.valueOf(e.getKey());
            if (KEY_TIME_REMAINING.equals(key)) {
                continue;
            }
            flatten(key, e.getValue(), strings, lists, errors);
        }
        NavigableMap<Long, List<String>> thresholds = new TreeMap<>();
        ConfigurationNode tr = root.node(KEY_TIME_REMAINING);
        if (!tr.virtual() && tr.raw() != null) {
            if (!tr.isMap()) {
                errors.add(KEY_TIME_REMAINING + " doit être une table seuil -> liste d'actions");
            } else {
                for (Map.Entry<Object, ? extends ConfigurationNode> e : tr.childrenMap().entrySet()) {
                    String rawKey = String.valueOf(e.getKey()).trim();
                    long seconds;
                    try {
                        seconds = Long.parseLong(rawKey);
                    } catch (NumberFormatException nfe) {
                        errors.add(KEY_TIME_REMAINING + "." + rawKey + " : le seuil doit être un nombre de secondes");
                        continue;
                    }
                    if (seconds <= 0L) {
                        errors.add(KEY_TIME_REMAINING + "." + rawKey + " : le seuil doit être > 0");
                        continue;
                    }
                    List<String> actions = stringList(e.getValue());
                    if (actions == null) {
                        errors.add(KEY_TIME_REMAINING + "." + rawKey + " : liste d'actions attendue");
                        continue;
                    }
                    thresholds.put(seconds, actions);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException("messages.yml invalide : " + String.join(" ; ", errors));
        }
        if (!strings.containsKey(KEY_PREFIX)) {
            strings.put(KEY_PREFIX, "");
        }
        return new MessagesConfig(strings, lists, thresholds);
    }

    private static void flatten(String path, ConfigurationNode node, Map<String, String> strings,
                                Map<String, List<String>> lists, List<String> errors) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> e : node.childrenMap().entrySet()) {
                flatten(path + "." + e.getKey(), e.getValue(), strings, lists, errors);
            }
        } else if (node.isList()) {
            List<String> values = stringList(node);
            if (values == null) {
                errors.add(path + " : liste de textes attendue");
            } else {
                lists.put(path, values);
            }
        } else {
            Object raw = node.raw();
            if (raw != null) {
                strings.put(path, String.valueOf(raw));
            }
        }
    }

    private static @Nullable List<String> stringList(ConfigurationNode node) {
        if (node.isList()) {
            List<String> out = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                if (child.isMap() || child.isList() || child.raw() == null) {
                    return null;
                }
                out.add(String.valueOf(child.raw()));
            }
            return List.copyOf(out);
        }
        if (node.isMap()) {
            return null;
        }
        Object raw = node.raw();
        return raw == null ? List.of() : List.of(String.valueOf(raw));
    }

    /**
     * @return le préfixe brut (MiniMessage/legacy)
     */
    public String prefix() {
        return strings.getOrDefault(KEY_PREFIX, "");
    }

    /**
     * @param key chemin pointé
     * @return la chaîne brute, ou {@code null} si absente (ou si la clé est une liste)
     */
    public @Nullable String get(String key) {
        return strings.get(key);
    }

    /**
     * Valeur sous forme de lignes : une liste telle quelle, une chaîne comme liste à un élément,
     * une clé absente comme liste vide.
     *
     * @param key chemin pointé
     * @return lignes brutes (non modifiable)
     */
    public List<String> list(String key) {
        List<String> l = lists.get(key);
        if (l != null) {
            return l;
        }
        String s = strings.get(key);
        return s == null ? List.of() : List.of(s);
    }

    /**
     * @param key chemin pointé
     * @return {@code true} si la clé est une liste
     */
    public boolean isList(String key) {
        return lists.containsKey(key);
    }

    /**
     * @param key chemin pointé
     * @return {@code true} si la clé existe (chaîne ou liste)
     */
    public boolean has(String key) {
        return strings.containsKey(key) || lists.containsKey(key);
    }

    /**
     * @return seuils {@code commands-time-remaining} : secondes → actions brutes (tri croissant)
     */
    public NavigableMap<Long, List<String>> timeRemaining() {
        return timeRemaining;
    }

    /**
     * @param thresholdSeconds seuil
     * @return actions brutes du seuil, ou liste vide
     */
    public List<String> timeRemainingActions(long thresholdSeconds) {
        return timeRemaining.getOrDefault(thresholdSeconds, List.of());
    }

    /**
     * @return toutes les clés (chaînes et listes)
     */
    public Set<String> keys() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(strings.keySet());
        all.addAll(lists.keySet());
        return Collections.unmodifiableSet(all);
    }
}
