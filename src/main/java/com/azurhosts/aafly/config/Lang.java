package com.azurhosts.aafly.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Rendu des messages : MiniMessage, avec conversion préalable des codes legacy {@code &a}/{@code §a},
 * {@code &#RRGGBB} et {@code &x&R&R&G&G&B&B} présents dans la configuration.
 *
 * <p><b>Sécurité :</b> seules les chaînes de configuration (fiables) sont parsées. Les valeurs dynamiques
 * (pseudos, saisies) doivent passer par {@link #unparsed(String, String)} ou {@link #component(String, ComponentLike)}
 * — jamais par concaténation dans le texte MiniMessage. Le tag {@code <prefix>} est toujours disponible.
 * Thread-safe (rechargement par remplacement de références volatiles).
 */
public final class Lang {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final Pattern BUNGEE_HEX = Pattern.compile("[&§][xX]((?:[&§][0-9a-fA-F]){6})");
    private static final Pattern AMP_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"), Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"), Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"), Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"), Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>"));

    private final Logger logger;
    private final Set<String> warnedMissing = ConcurrentHashMap.newKeySet();
    private volatile MessagesConfig messages;
    private volatile TagResolver prefixResolver;

    /**
     * @param messages messages validés
     * @param logger   logger du plugin
     */
    public Lang(MessagesConfig messages, Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.prefixResolver = buildPrefix(messages);
    }

    /**
     * Remplace les messages (après {@code /aafly reload}).
     *
     * @param newMessages messages validés
     */
    public void reload(MessagesConfig newMessages) {
        Objects.requireNonNull(newMessages, "messages");
        this.prefixResolver = buildPrefix(newMessages);
        this.messages = newMessages;
        warnedMissing.clear();
    }

    private static TagResolver buildPrefix(MessagesConfig m) {
        Component prefix = MM.deserialize(legacyToMiniMessage(m.prefix()));
        return Placeholder.component("prefix", prefix);
    }

    /**
     * @return messages courants
     */
    public MessagesConfig messages() {
        return messages;
    }

    /**
     * Message d'une clé (chaîne ; pour une liste, les lignes sont jointes par des retours à la ligne).
     * Clé absente : renvoie la clé en texte brut et journalise un avertissement une seule fois.
     *
     * @param key       chemin pointé (ex. {@code "fly.enabled"})
     * @param resolvers placeholders dynamiques
     * @return composant
     */
    public Component msg(String key, TagResolver... resolvers) {
        MessagesConfig m = messages;
        String raw = m.get(key);
        if (raw == null) {
            if (m.isList(key)) {
                return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines(key, resolvers));
            }
            warnMissing(key);
            return Component.text(key);
        }
        return parse(raw, resolvers);
    }

    /**
     * Lignes d'une clé (liste, ou chaîne unique).
     *
     * @param key       chemin pointé
     * @param resolvers placeholders dynamiques
     * @return composants (vide si la clé est absente)
     */
    public List<Component> lines(String key, TagResolver... resolvers) {
        MessagesConfig m = messages;
        List<String> raws = m.list(key);
        if (raws.isEmpty() && !m.has(key)) {
            warnMissing(key);
        }
        List<Component> out = new ArrayList<>(raws.size());
        for (String raw : raws) {
            out.add(parse(raw, resolvers));
        }
        return out;
    }

    /**
     * Parse une chaîne <b>issue de la configuration</b> (legacy converti, tag {@code <prefix>} disponible).
     *
     * @param raw       texte MiniMessage/legacy fiable
     * @param resolvers placeholders dynamiques
     * @return composant
     */
    public Component parse(String raw, TagResolver... resolvers) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        TagResolver all = resolvers.length == 0
                ? prefixResolver
                : TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));
        return MM.deserialize(legacyToMiniMessage(raw), all);
    }

    /**
     * Envoie le message d'une clé ; une valeur vide n'est pas envoyée, une liste est envoyée ligne par ligne.
     *
     * @param audience  destinataire
     * @param key       chemin pointé
     * @param resolvers placeholders dynamiques
     */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        if (audience == null) {
            return;
        }
        MessagesConfig m = messages;
        if (m.isList(key)) {
            for (String raw : m.list(key)) {
                if (!raw.isEmpty()) {
                    audience.sendMessage(parse(raw, resolvers));
                }
            }
            return;
        }
        String raw = m.get(key);
        if (raw == null) {
            warnMissing(key);
            audience.sendMessage(Component.text(key));
            return;
        }
        if (!raw.isEmpty()) {
            audience.sendMessage(parse(raw, resolvers));
        }
    }

    /**
     * Tag {@code <time>} à partir d'une durée déjà formatée par {@code TimeFormatter}
     * (chaîne de configuration contenant uniquement des nombres insérés).
     *
     * @param formatted durée formatée
     * @return résolveur du tag {@code time}
     */
    public TagResolver time(String formatted) {
        return Placeholder.component("time", parse(formatted));
    }

    /**
     * Tag nommé dont la valeur est insérée comme texte littéral (jamais interprétée).
     *
     * @param name  nom du tag ({@code [a-z0-9_-]})
     * @param value valeur dynamique (pseudo, saisie…)
     * @return résolveur
     */
    public static TagResolver unparsed(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    /**
     * Tag nommé dont la valeur est un composant.
     *
     * @param name  nom du tag
     * @param value composant
     * @return résolveur
     */
    public static TagResolver component(String name, ComponentLike value) {
        return Placeholder.component(name, value == null ? Component.empty() : value);
    }

    /**
     * Convertit les codes couleur legacy en tags MiniMessage. Sans effet si la chaîne ne contient
     * ni {@code &} ni {@code §}.
     *
     * @param input texte de configuration
     * @return texte MiniMessage
     */
    public static String legacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) {
            return input;
        }
        String s = input;
        Matcher bungee = BUNGEE_HEX.matcher(s);
        if (bungee.find()) {
            StringBuilder sb = new StringBuilder();
            bungee.reset();
            while (bungee.find()) {
                String hex = bungee.group(1).replaceAll("[&§]", "");
                bungee.appendReplacement(sb, Matcher.quoteReplacement("<#" + hex.toLowerCase(java.util.Locale.ROOT) + ">"));
            }
            bungee.appendTail(sb);
            s = sb.toString();
        }
        Matcher hex = AMP_HEX.matcher(s);
        if (hex.find()) {
            StringBuilder sb = new StringBuilder();
            hex.reset();
            while (hex.find()) {
                hex.appendReplacement(sb, Matcher.quoteReplacement("<#" + hex.group(1).toLowerCase(java.util.Locale.ROOT) + ">"));
            }
            hex.appendTail(sb);
            s = sb.toString();
        }
        Matcher legacy = LEGACY.matcher(s);
        if (legacy.find()) {
            StringBuilder sb = new StringBuilder();
            legacy.reset();
            while (legacy.find()) {
                char code = Character.toLowerCase(legacy.group(1).charAt(0));
                legacy.appendReplacement(sb, Matcher.quoteReplacement(LEGACY_TAGS.get(code)));
            }
            legacy.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }

    private void warnMissing(String key) {
        if (warnedMissing.add(key)) {
            logger.warning("Message absent de messages.yml : " + key);
        }
    }
}
