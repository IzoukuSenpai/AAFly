package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;

/**
 * Analyse des actions {@code "[type] contenu"} (SPEC §6), sans dépendance au serveur : le
 * {@link ActionRuntime} n'est utilisé qu'à l'exécution.
 *
 * <ul>
 *   <li>{@code [message]}, {@code [actionbar]}, {@code [subtitle]} : texte MiniMessage/legacy ;</li>
 *   <li>{@code [title] titre;sous-titre} (sous-titre facultatif) ;</li>
 *   <li>{@code [command] cmd} : console ; {@code [player] cmd} : refusé sauf
 *       {@code actions.allow-player-commands: true} (le {@code /} initial est retiré) ;</li>
 *   <li>{@code [sound] NOM[;volume;pitch]} : nom enum ({@code BLOCK_NOTE_BLOCK_PLING}) ou clé
 *       ({@code minecraft:block.note_block.pling}), volume [0, 10], pitch [0, 2].</li>
 * </ul>
 * Thread-safe (l'unique réglage est volatile).
 */
public final class DefaultActionParser implements ActionParser {

    /** Longueur maximale d'une action brute. */
    public static final int MAX_RAW_LENGTH = 1024;
    /** Nombre maximal d'actions par liste. */
    public static final int MAX_ACTIONS = 64;

    private static final Pattern FORMAT = Pattern.compile("^\\s*\\[([A-Za-z_]{1,16})]\\s?(.*)$", Pattern.DOTALL);
    private static final Pattern SOUND_ENUM = Pattern.compile("^[A-Z0-9_]{1,128}$");
    private static final Pattern SOUND_KEY = Pattern.compile("^([a-z0-9_.\\-]{1,64}:)?[a-z0-9_.\\-/]{1,128}$");
    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    private final ActionRuntime runtime;
    private final Logger logger;
    private volatile boolean allowPlayerCommands;

    /**
     * @param runtime             services d'exécution (non appelés pendant l'analyse)
     * @param logger              logger (WARN des actions invalides)
     * @param allowPlayerCommands valeur de {@code actions.allow-player-commands}
     */
    public DefaultActionParser(ActionRuntime runtime, Logger logger, boolean allowPlayerCommands) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.allowPlayerCommands = allowPlayerCommands;
    }

    /**
     * Applique {@code actions.allow-player-commands}. À appeler <b>avant</b> de recharger les règles
     * et les messages (les actions déjà analysées ne sont pas modifiées).
     *
     * @param settings réglages validés
     */
    public void reload(Settings settings) {
        this.allowPlayerCommands = settings.actions().allowPlayerCommands();
    }

    /**
     * @return {@code true} si {@code [player]} est autorisé
     */
    public boolean allowPlayerCommands() {
        return allowPlayerCommands;
    }

    @Override
    public Action parse(String raw) throws InvalidActionException {
        return parse(raw, "config");
    }

    @Override
    public Action parse(String raw, String origin) throws InvalidActionException {
        String o = origin == null || origin.isBlank() ? "config" : origin;
        if (raw == null || raw.isBlank()) {
            throw new InvalidActionException("action vide");
        }
        if (raw.length() > MAX_RAW_LENGTH) {
            throw new InvalidActionException("action trop longue (max " + MAX_RAW_LENGTH + " caractères)");
        }
        Matcher m = FORMAT.matcher(raw);
        if (!m.matches()) {
            throw new InvalidActionException("format attendu : \"[type] contenu\"");
        }
        String typeKey = m.group(1);
        ActionType type = ActionType.fromKey(typeKey).orElseThrow(() -> new InvalidActionException(
                "type d'action inconnu '" + typeKey.toLowerCase(Locale.ROOT)
                        + "' (message, actionbar, title, subtitle, command, player, sound)"));
        String content = m.group(2);
        return switch (type) {
            case MESSAGE, ACTIONBAR, SUBTITLE -> {
                requireContent(content);
                yield new ConfiguredAction.TextAction(type, o, raw, ActionTemplate.parse(content), runtime);
            }
            case TITLE -> parseTitle(o, raw, content);
            case COMMAND, PLAYER -> parseCommand(type, o, raw, content);
            case SOUND -> parseSound(o, raw, content);
        };
    }

    private Action parseTitle(String origin, String raw, String content) throws InvalidActionException {
        requireContent(content);
        int sep = content.indexOf(';');
        String title = sep < 0 ? content : content.substring(0, sep);
        String subtitle = sep < 0 ? "" : content.substring(sep + 1);
        if (title.isBlank() && subtitle.isBlank()) {
            throw new InvalidActionException("titre et sous-titre vides");
        }
        return new ConfiguredAction.TitleAction(origin, raw,ActionTemplate.parse(title), ActionTemplate.parse(subtitle), runtime);
    }

    private Action parseCommand(ActionType type, String origin, String raw, String content) throws InvalidActionException {
        if (type == ActionType.PLAYER && !allowPlayerCommands) {
            throw new InvalidActionException("[player] désactivé (actions.allow-player-commands: false)");
        }
        String cmd = content.strip();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        cmd = cmd.strip();
        if (cmd.isEmpty()) {
            throw new InvalidActionException("commande vide");
        }
        if (LINE_BREAK.matcher(cmd).find()) {
            throw new InvalidActionException("la commande ne doit pas contenir de retour à la ligne");
        }
        return new ConfiguredAction.CommandAction(type, origin, raw,ActionTemplate.parse(cmd), runtime);
    }

    private Action parseSound(String origin, String raw, String content) throws InvalidActionException {
        String[] parts = content.strip().split(";", -1);
        if (parts.length != 1 && parts.length != 3) {
            throw new InvalidActionException("son : format attendu NOM ou NOM;volume;pitch");
        }
        String name = parts[0].strip();
        if (name.isEmpty()) {
            throw new InvalidActionException("son : nom manquant");
        }
        float volume = 1.0F;
        float pitch = 1.0F;
        if (parts.length == 3) {
            volume = parseFloat(parts[1], "volume", 0.0F, 10.0F);
            pitch = parseFloat(parts[2], "pitch", 0.0F, 2.0F);
        }
        // Sons validés au chargement (SPEC-phase2 A7) quand le serveur fournit la liste des sons.
        Set<Key> known = runtime.knownSounds();
        if (SOUND_ENUM.matcher(name).matches()) {
            Key resolved = runtime.resolveSoundName(name).orElse(null);
            if (resolved == null && known != null) {
                throw new InvalidActionException("son inconnu « " + shorten(name) + " »");
            }
            return new ConfiguredAction.SoundAction(origin, raw, resolved, name, volume, pitch, runtime);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!SOUND_KEY.matcher(lower).matches()) {
            throw new InvalidActionException("son : nom invalide");
        }
        Key key;
        try {
            key = Key.key(lower);
        } catch (InvalidKeyException e) {
            throw new InvalidActionException("son : clé invalide");
        }
        // Seul l'espace minecraft: est vérifiable ; les sons de pack de ressources sont acceptés tels quels.
        if (known != null && Key.MINECRAFT_NAMESPACE.equals(key.namespace()) && !known.contains(key)) {
            throw new InvalidActionException("son inconnu « " + shorten(lower) + " »");
        }
        return new ConfiguredAction.SoundAction(origin, raw, key, null, volume, pitch, runtime);
    }

    private static float parseFloat(String raw, String what, float min, float max) throws InvalidActionException {
        String s = raw.strip();
        if (s.isEmpty() || s.length() > 16) {
            throw new InvalidActionException("son : " + what + " invalide");
        }
        float f;
        try {
            f = Float.parseFloat(s);
        } catch (NumberFormatException e) {
            throw new InvalidActionException("son : " + what + " invalide");
        }
        if (!Float.isFinite(f) || f < min || f > max) {
            throw new InvalidActionException("son : " + what + " hors limites [" + min + ", " + max + "]");
        }
        return f;
    }

    private static void requireContent(String content) throws InvalidActionException {
        if (content == null || content.isBlank()) {
            throw new InvalidActionException("contenu vide");
        }
    }

    @Override
    public List<Action> parseAll(List<String> raws, String context) {
        if (raws == null || raws.isEmpty()) {
            return List.of();
        }
        List<Action> out = new ArrayList<>(Math.min(raws.size(), MAX_ACTIONS));
        for (String raw : raws) {
            if (out.size() >= MAX_ACTIONS) {
                logger.warning("[" + context + "] plus de " + MAX_ACTIONS + " actions : les suivantes sont ignorées.");
                break;
            }
            try {
                out.add(parse(raw, context));
            } catch (InvalidActionException e) {
                logger.warning("[" + context + "] Action ignorée « " + shorten(raw) + " » : " + e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Version courte et sans caractère de contrôle d'un texte, pour les logs.
     *
     * @param raw texte
     * @return texte tronqué à 80 caractères
     */
    static String shorten(String raw) {
        if (raw == null) {
            return "null";
        }
        String v = raw.replaceAll("\\p{Cntrl}", " ");
        return v.length() > 80 ? v.substring(0, 80) + "…" : v;
    }
}
