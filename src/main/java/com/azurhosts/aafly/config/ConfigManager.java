package com.azurhosts.aafly.config;

import com.azurhosts.aafly.storage.StorageType;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurationOptions;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.util.NamingSchemes;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Chargement, complétion et validation de {@code config.yml} et {@code messages.yml}.
 *
 * <ul>
 *   <li>Premier lancement : copie des fichiers par défaut du jar (commentaires conservés).</li>
 *   <li>Clés manquantes : ajoutées depuis les défauts sans écraser l'existant ; le fichier est alors
 *       réécrit après une sauvegarde {@code .bak}.</li>
 *   <li>Validation stricte (types, bornes, règles) : toute erreur lève {@link ConfigException} avec un
 *       message clair. {@link #reload()} est atomique : en cas d'échec, l'état précédent est conservé.</li>
 * </ul>
 * Les tables de règles ({@code fly-rules}, {@code decrement-rules}) et {@code commands-time-remaining}
 * ne sont jamais complétées entrée par entrée (seulement créées si totalement absentes).
 * Appeler depuis le main thread (ou au démarrage). Aucun secret n'est journalisé.
 */
public final class ConfigManager {

    /** Nom du fichier de configuration. */
    public static final String CONFIG_FILE = "config.yml";
    /** Nom du fichier de messages. */
    public static final String MESSAGES_FILE = "messages.yml";

    /** Valeur maximale acceptée pour {@code limits.max-seconds} (100 ans). */
    public static final long ABSOLUTE_MAX_SECONDS = 3_153_600_000L;

    private static final Set<String> CONFIG_NO_DEEP_MERGE = Set.of("fly-rules", "decrement-rules",
            "timed-fly.groups", "pvp.bypass-placeholders", "commands.fly-aliases", "token.lore");
    private static final Set<String> MESSAGES_NO_DEEP_MERGE = Set.of(MessagesConfig.KEY_TIME_REMAINING);

    private static final Pattern GROUP_NAME = Pattern.compile("^[a-z0-9_\\-]{1,32}$");
    private static final Pattern ALIAS = Pattern.compile("^[a-z0-9_]{1,32}$");
    private static final Pattern MATERIAL = Pattern.compile("^[A-Za-z0-9_:]{1,64}$");
    private static final Pattern DB_NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.\\-:\\[\\]]{1,253}$");

    private static final ObjectMapper.Factory MAPPER_FACTORY = ObjectMapper.factoryBuilder()
            .defaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED)
            .build();

    private final Path dataFolder;
    private final Logger logger;
    private final Function<String, @Nullable InputStream> resources;

    private volatile @Nullable Settings settings;
    private volatile @Nullable MessagesConfig messages;

    /**
     * @param dataFolder dossier du plugin ({@code plugins/AAFly})
     * @param logger     logger du plugin
     * @param resources  accès aux ressources du jar (ex. {@code plugin::getResource})
     */
    public ConfigManager(Path dataFolder, Logger logger, Function<String, @Nullable InputStream> resources) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /**
     * Chargement initial.
     *
     * @throws ConfigException si un fichier est illisible ou invalide
     */
    public synchronized void load() throws ConfigException {
        reload();
    }

    /**
     * Recharge les deux fichiers. Atomique : les nouveaux réglages ne remplacent les anciens que si
     * tout est valide.
     *
     * @throws ConfigException si un fichier est illisible ou invalide (l'état précédent est conservé)
     */
    public synchronized void reload() throws ConfigException {
        ConfigurationNode configRoot = loadWithDefaults(CONFIG_FILE, CONFIG_NO_DEEP_MERGE);
        ConfigurationNode messagesRoot = loadWithDefaults(MESSAGES_FILE, MESSAGES_NO_DEEP_MERGE);
        Settings newSettings = parseSettings(configRoot);
        validate(newSettings);
        MessagesConfig newMessages = MessagesConfig.from(messagesRoot);
        this.settings = newSettings;
        this.messages = newMessages;
    }

    /**
     * @return réglages courants
     * @throws IllegalStateException si aucun chargement n'a réussi
     */
    public Settings settings() {
        Settings s = settings;
        if (s == null) {
            throw new IllegalStateException("Configuration non chargée");
        }
        return s;
    }

    /**
     * @return messages courants
     * @throws IllegalStateException si aucun chargement n'a réussi
     */
    public MessagesConfig messages() {
        MessagesConfig m = messages;
        if (m == null) {
            throw new IllegalStateException("Messages non chargés");
        }
        return m;
    }

    /**
     * @return dossier du plugin
     */
    public Path dataFolder() {
        return dataFolder;
    }

    // ------------------------------------------------------------------ chargement

    private ConfigurationNode loadWithDefaults(String fileName, Set<String> noDeepMerge) throws ConfigException {
        byte[] defaultBytes = readResource(fileName);
        ConfigurationNode defaults;
        try {
            defaults = loaderFor(defaultBytes).load();
        } catch (ConfigurateException e) {
            throw new ConfigException("Ressource par défaut " + fileName + " illisible : " + e.getMessage(), e);
        }

        Path file = dataFolder.resolve(fileName);
        try {
            Files.createDirectories(dataFolder);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Files.write(file, defaultBytes);
                logger.info(fileName + " créé avec les valeurs par défaut.");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException(fileName + " n'est pas un fichier régulier.");
            }
        } catch (IOException e) {
            throw new ConfigException("Impossible de créer " + fileName + " : " + e.getMessage(), e);
        }

        YamlConfigurationLoader loader = loaderFor(file);
        ConfigurationNode root;
        try {
            root = loader.load();
        } catch (ConfigurateException e) {
            throw new ConfigException(fileName + " : YAML invalide (" + e.getMessage() + ")", e);
        }
        if (!root.isMap() && root.raw() != null) {
            throw new ConfigException(fileName + " : la racine doit être une table YAML.");
        }

        List<String> added = new ArrayList<>();
        try {
            addMissing(root, defaults, "", noDeepMerge, added);
        } catch (SerializationException e) {
            throw new ConfigException(fileName + " : impossible de compléter les clés (" + e.getMessage() + ")", e);
        }
        // SPEC-phase2 A7 : clés inconnues signalées (WARN), jamais supprimées.
        List<String> unknown = new ArrayList<>();
        collectUnknown(root, defaults, "", noDeepMerge, unknown);
        if (!unknown.isEmpty()) {
            logger.warning(fileName + " : " + unknown.size() + " clé(s) inconnue(s) ignorée(s) (conservées dans le "
                    + "fichier) : " + String.join(", ", unknown.subList(0, Math.min(unknown.size(), 20)))
                    + (unknown.size() > 20 ? ", …" : ""));
        }
        if (!added.isEmpty()) {
            logger.info(fileName + " : " + added.size() + " clé(s) manquante(s) ajoutée(s) : " + String.join(", ", added));
            try {
                Files.copy(file, file.resolveSibling(fileName + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                loader.save(root);
                logger.info(fileName + " mis à jour (copie de l'original : " + fileName + ".bak).");
            } catch (IOException e) {
                // Non bloquant : les valeurs par défaut sont utilisées en mémoire.
                logger.warning("Impossible d'écrire " + fileName + " complété : " + e.getMessage());
            }
        }
        return root;
    }

    /**
     * Ajoute dans {@code target} les clés de {@code defaults} absentes, récursivement, sans rien écraser.
     */
    static void addMissing(ConfigurationNode target, ConfigurationNode defaults, String path,
                           Set<String> noDeepMerge, List<String> added) throws SerializationException {
        if (!defaults.isMap()) {
            return;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> e : defaults.childrenMap().entrySet()) {
            Object key = e.getKey();
            String childPath = path.isEmpty() ? String.valueOf(key) : path + "." + key;
            ConfigurationNode def = e.getValue();
            ConfigurationNode tgt = target.node(key);
            if (tgt.virtual() || (tgt.raw() == null && !tgt.isMap() && !tgt.isList())) {
                tgt.from(def);
                added.add(childPath);
            } else if (def.isMap() && tgt.isMap() && !noDeepMerge.contains(childPath)) {
                addMissing(tgt, def, childPath, noDeepMerge, added);
            }
        }
    }

    /**
     * Relève les clés de {@code target} absentes de {@code defaults} (sections libres de {@code noDeepMerge}
     * exclues : règles, groupes, seuils...). Ne modifie rien.
     */
    static void collectUnknown(ConfigurationNode target, ConfigurationNode defaults, String path,
                               Set<String> noDeepMerge, List<String> unknown) {
        if (!target.isMap() || !defaults.isMap()) {
            return;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> e : target.childrenMap().entrySet()) {
            String childPath = path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey();
            ConfigurationNode def = defaults.node(e.getKey());
            if (def.virtual()) {
                unknown.add(childPath);
            } else if (!noDeepMerge.contains(childPath)) {
                collectUnknown(e.getValue(), def, childPath, noDeepMerge, unknown);
            }
        }
    }

    private byte[] readResource(String name) throws ConfigException {
        try (InputStream in = resources.apply(name)) {
            if (in == null) {
                throw new ConfigException("Ressource " + name + " absente du jar.");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ConfigException("Ressource " + name + " illisible : " + e.getMessage(), e);
        }
    }

    private static ConfigurationOptions options() {
        return ConfigurationOptions.defaults()
                .serializers(builder -> builder.registerAnnotatedObjects(MAPPER_FACTORY));
    }

    private static YamlConfigurationLoader loaderFor(Path file) {
        return YamlConfigurationLoader.builder()
                .path(file)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(options())
                .build();
    }

    private static YamlConfigurationLoader loaderFor(byte[] content) {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content),
                        StandardCharsets.UTF_8)))
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(options())
                .build();
    }

    // ------------------------------------------------------------------ lecture typée

    /**
     * Convertit le nœud racine de {@code config.yml} en {@link Settings} (sans validation des bornes).
     *
     * @param root nœud racine
     * @return réglages
     * @throws ConfigException si un type est incorrect (ex. texte au lieu d'un nombre, enum inconnue)
     */
    static Settings parseSettings(ConfigurationNode root) throws ConfigException {
        Integer version = scalar(root, "config-version", Integer.class);
        Boolean debug = scalar(root, "debug", Boolean.class);
        ConfigurationNode flyRules = rulesNode(root, "fly-rules");
        ConfigurationNode decrementRules = rulesNode(root, "decrement-rules");
        return new Settings(
                version == null ? Settings.CURRENT_VERSION : version,
                debug != null && debug,
                section(root, "storage", Settings.StorageSection.class),
                section(root, "limits", Settings.LimitsSection.class),
                section(root, "decrement", Settings.DecrementSection.class),
                section(root, "flight", Settings.FlightSection.class),
                section(root, "actionbar", Settings.ActionBarSection.class),
                section(root, "rules", Settings.RulesSection.class),
                section(root, "pvp", Settings.PvpSection.class),
                section(root, "give", Settings.GiveSection.class),
                section(root, "token", Settings.TokenSection.class),
                section(root, "timed-fly", Settings.TimedFlySection.class),
                section(root, "format", Settings.FormatSection.class),
                section(root, "actions", Settings.ActionsSection.class),
                section(root, "compat", Settings.CompatSection.class),
                section(root, "commands", Settings.CommandsSection.class),
                flyRules,
                decrementRules,
                section(root, "api", Settings.ApiSection.class),
                section(root, "integrations", Settings.IntegrationsSection.class));
    }

    private static <T> @Nullable T section(ConfigurationNode root, String key, Class<T> type) throws ConfigException {
        ConfigurationNode node = root.node(key);
        if (node.virtual() || node.raw() == null) {
            return null;
        }
        if (!node.isMap()) {
            throw new ConfigException("config.yml : la section '" + key + "' doit être une table.");
        }
        try {
            return node.get(type);
        } catch (SerializationException e) {
            throw new ConfigException("config.yml : section '" + key + "' invalide (" + e.getMessage() + ")", e);
        }
    }

    private static <T> @Nullable T scalar(ConfigurationNode root, String key, Class<T> type) throws ConfigException {
        ConfigurationNode node = root.node(key);
        if (node.virtual() || node.raw() == null) {
            return null;
        }
        try {
            return node.get(type);
        } catch (SerializationException e) {
            throw new ConfigException("config.yml : valeur '" + key + "' invalide (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Copie brute d'une section de règles. Aucune validation ici (SPEC §5) : une section ou une règle
     * invalide est journalisée en WARN puis ignorée par {@code RuleSet.load}, sans jamais désactiver le plugin.
     */
    private static ConfigurationNode rulesNode(ConfigurationNode root, String key) {
        return root.node(key).copy();
    }

    // ------------------------------------------------------------------ validation

    /**
     * Valide les bornes et la cohérence des réglages.
     *
     * @param s réglages
     * @throws ConfigException avec la liste de toutes les erreurs détectées
     */
    static void validate(Settings s) throws ConfigException {
        List<String> errors = new ArrayList<>();

        if (s.configVersion() < 1 || s.configVersion() > Settings.CURRENT_VERSION) {
            errors.add("config-version doit valoir entre 1 et " + Settings.CURRENT_VERSION);
        }

        Settings.StorageSection st = s.storage();
        range(errors, "storage.save-interval-seconds", st.saveIntervalSeconds(), 10, 86_400);
        Settings.MysqlSection my = st.mysql();
        range(errors, "storage.mysql.port", my.port(), 1, 65_535);
        range(errors, "storage.mysql.pool-size", my.poolSize(), 1, 64);
        if (st.type() == StorageType.MYSQL) {
            if (!HOST.matcher(my.host()).matches()) {
                errors.add("storage.mysql.host invalide");
            }
            if (!DB_NAME.matcher(my.database()).matches()) {
                errors.add("storage.mysql.database invalide (lettres, chiffres, _ et -, 64 max.)");
            }
            if (my.username().isBlank() || my.username().length() > 64) {
                errors.add("storage.mysql.username doit être renseigné (64 caractères max.)");
            }
        }

        long max = s.limits().maxSeconds();
        range(errors, "limits.max-seconds", max, 1, ABSOLUTE_MAX_SECONDS);

        range(errors, "decrement.static-delay-seconds", s.decrement().staticDelaySeconds(), 1, 3_600);

        double speed = s.flight().defaultSpeed();
        if (!Double.isFinite(speed) || speed < 0.1D || speed > 1.0D) {
            errors.add("flight.default-speed doit être compris entre 0.1 et 1.0");
        }
        range(errors, "flight.safe-landing.max-scan", s.flight().safeLanding().maxScan(), 1, 4_096);

        if (s.actionbar().message().length() > 512) {
            errors.add("actionbar.message trop long (512 caractères max.)");
        }

        range(errors, "rules.check-interval-seconds", s.rules().checkIntervalSeconds(), 1, 60);

        Settings.PvpSection pvp = s.pvp();
        range(errors, "pvp.radius", pvp.radius(), 1, 64);
        range(errors, "pvp.check-interval-seconds", pvp.checkIntervalSeconds(), 1, 60);
        if (pvp.bypassPlaceholders().size() > 16) {
            errors.add("pvp.bypass-placeholders : 16 entrées maximum");
        }
        for (String p : pvp.bypassPlaceholders()) {
            if (p == null || p.isBlank() || p.length() > 128) {
                errors.add("pvp.bypass-placeholders : entrée vide ou trop longue");
                break;
            }
        }

        Settings.GiveSection give = s.give();
        range(errors, "give.cooldown-seconds", give.cooldownSeconds(), 0, 86_400L * 30);
        range(errors, "give.min-seconds", give.minSeconds(), 1, max);
        range(errors, "give.max-seconds", give.maxSeconds(), 1, max);
        if (give.maxSeconds() < give.minSeconds()) {
            errors.add("give.max-seconds doit être >= give.min-seconds");
        }

        Settings.TokenSection token = s.token();
        if (!MATERIAL.matcher(token.material()).matches()) {
            errors.add("token.material invalide");
        }
        if (token.name().length() > 256) {
            errors.add("token.name trop long (256 caractères max.)");
        }
        if (token.lore().size() > 16) {
            errors.add("token.lore : 16 lignes maximum");
        }
        for (String line : token.lore()) {
            if (line != null && line.length() > 256) {
                errors.add("token.lore : ligne trop longue (256 caractères max.)");
                break;
            }
        }
        if (token.customModelData() < 0) {
            errors.add("token.custom-model-data doit être >= 0");
        }
        range(errors, "token.cooldown-seconds", token.cooldownSeconds(), 0, 86_400L * 30);
        range(errors, "token.min-seconds", token.minSeconds(), 1, max);
        range(errors, "token.max-seconds", token.maxSeconds(), 1, max);
        if (token.maxSeconds() < token.minSeconds()) {
            errors.add("token.max-seconds doit être >= token.min-seconds");
        }

        Settings.TimedFlySection tf = s.timedFly();
        Set<String> groupNames = new HashSet<>();
        for (Settings.TimedFlyGroup g : tf.groups()) {
            if (g == null) {
                errors.add("timed-fly.groups : entrée vide");
                continue;
            }
            if (!GROUP_NAME.matcher(g.name()).matches()) {
                errors.add("timed-fly.groups : nom de groupe invalide '" + safe(g.name())
                        + "' (minuscules, chiffres, _ et -, 32 max.)");
            } else if (!groupNames.add(g.name())) {
                errors.add("timed-fly.groups : groupe '" + g.name() + "' en double");
            }
            range(errors, "timed-fly.groups." + safe(g.name()) + ".fly-time", g.flyTime(), 1, max);
            range(errors, "timed-fly.groups." + safe(g.name()) + ".cooldown", g.cooldown(), 0, ABSOLUTE_MAX_SECONDS);
        }
        if (tf.enabled() && tf.groups().isEmpty()) {
            errors.add("timed-fly.enabled est vrai mais timed-fly.groups est vide");
        }

        Settings.FormatSection f = s.format();
        if (!(f.seconds() || f.minutes() || f.hours() || f.days())) {
            errors.add("format : au moins une unité doit être activée");
        }
        if (f.pattern().isBlank()) {
            errors.add("format.pattern ne doit pas être vide");
        }

        long toggleCooldown = s.commands().toggleCooldownMillis();
        if (toggleCooldown < 0L || toggleCooldown > Settings.CommandsSection.MAX_TOGGLE_COOLDOWN_MILLIS) {
            errors.add("commands.toggle-cooldown-millis doit être entre 0 et "
                    + Settings.CommandsSection.MAX_TOGGLE_COOLDOWN_MILLIS);
        }
        for (String alias : s.commands().flyAliases()) {
            if (alias == null || !ALIAS.matcher(alias).matches()) {
                errors.add("commands.fly-aliases : alias invalide '" + safe(alias) + "' (minuscules, chiffres, _)");
            }
        }

        if (!errors.isEmpty()) {
            throw new ConfigException("config.yml invalide : " + String.join(" ; ", errors));
        }
    }

    private static void range(List<String> errors, String key, long value, long min, long max) {
        if (value < min || value > max) {
            errors.add(key + " doit être compris entre " + min + " et " + max + " (actuel : " + value + ")");
        }
    }

    /** Tronque une valeur affichée dans un message d'erreur (évite les logs démesurés). */
    private static String safe(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        String v = value.replaceAll("[\\r\\n\\t]", " ");
        return v.length() > 40 ? v.substring(0, 40) + "…" : v;
    }
}
