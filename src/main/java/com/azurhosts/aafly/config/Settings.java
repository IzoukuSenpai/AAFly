package com.azurhosts.aafly.config;

import com.azurhosts.aafly.core.DecrementMode;
import com.azurhosts.aafly.storage.StorageType;
import java.util.List;
import java.util.Objects;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Instantané immuable et validé de {@code config.yml} (voir SPEC §10).
 *
 * <p>Chaque section est un record Configurate ; les noms de composants sont convertis en
 * {@code lower-case-dashed} (ex. {@code saveIntervalSeconds} ↔ {@code save-interval-seconds}).
 * Les valeurs {@code null} (clé absente) sont remplacées par les valeurs par défaut.
 * Une instance n'est produite que par {@link ConfigManager} après validation complète.
 *
 * @param configVersion  version du schéma de configuration
 * @param debug          active les logs de débogage
 * @param storage        section {@code storage}
 * @param limits         section {@code limits}
 * @param decrement      section {@code decrement}
 * @param flight         section {@code flight}
 * @param actionbar      section {@code actionbar}
 * @param rules          section {@code rules}
 * @param pvp            section {@code pvp}
 * @param give           section {@code give}
 * @param token          section {@code token}
 * @param timedFly       section {@code timed-fly}
 * @param format         section {@code format}
 * @param actions        section {@code actions}
 * @param compat         section {@code compat}
 * @param commands       section {@code commands}
 * @param flyRules       nœud brut {@code fly-rules} (copie détachée, interprétée par le RuleEngine)
 * @param decrementRules nœud brut {@code decrement-rules} (copie détachée)
 */
public record Settings(
        int configVersion,
        boolean debug,
        StorageSection storage,
        LimitsSection limits,
        DecrementSection decrement,
        FlightSection flight,
        ActionBarSection actionbar,
        RulesSection rules,
        PvpSection pvp,
        GiveSection give,
        TokenSection token,
        TimedFlySection timedFly,
        FormatSection format,
        ActionsSection actions,
        CompatSection compat,
        CommandsSection commands,
        ConfigurationNode flyRules,
        ConfigurationNode decrementRules
) {

    /** Version courante du schéma de {@code config.yml}. */
    public static final int CURRENT_VERSION = 1;

    public Settings {
        storage = Objects.requireNonNullElseGet(storage, StorageSection::defaults);
        limits = Objects.requireNonNullElseGet(limits, LimitsSection::defaults);
        decrement = Objects.requireNonNullElseGet(decrement, DecrementSection::defaults);
        flight = Objects.requireNonNullElseGet(flight, FlightSection::defaults);
        actionbar = Objects.requireNonNullElseGet(actionbar, ActionBarSection::defaults);
        rules = Objects.requireNonNullElseGet(rules, RulesSection::defaults);
        pvp = Objects.requireNonNullElseGet(pvp, PvpSection::defaults);
        give = Objects.requireNonNullElseGet(give, GiveSection::defaults);
        token = Objects.requireNonNullElseGet(token, TokenSection::defaults);
        timedFly = Objects.requireNonNullElseGet(timedFly, TimedFlySection::defaults);
        format = Objects.requireNonNullElseGet(format, FormatSection::defaults);
        actions = Objects.requireNonNullElseGet(actions, ActionsSection::defaults);
        compat = Objects.requireNonNullElseGet(compat, CompatSection::defaults);
        commands = Objects.requireNonNullElseGet(commands, CommandsSection::defaults);
        flyRules = flyRules == null ? BasicConfigurationNode.root() : flyRules;
        decrementRules = decrementRules == null ? BasicConfigurationNode.root() : decrementRules;
    }

    /** Section {@code storage}. */
    @ConfigSerializable
    public record StorageSection(StorageType type, int saveIntervalSeconds, MysqlSection mysql) {
        public StorageSection {
            type = Objects.requireNonNullElse(type, StorageType.SQLITE);
            mysql = Objects.requireNonNullElseGet(mysql, MysqlSection::defaults);
        }

        public static StorageSection defaults() {
            return new StorageSection(StorageType.SQLITE, 300, MysqlSection.defaults());
        }
    }

    /**
     * Section {@code storage.mysql}. Le mot de passe n'est jamais inclus dans {@link #toString()}.
     */
    @ConfigSerializable
    public record MysqlSection(String host, int port, String database, String username, String password,
                               boolean useSsl, int poolSize) {
        public MysqlSection {
            host = Objects.requireNonNullElse(host, "localhost");
            database = Objects.requireNonNullElse(database, "aafly");
            username = Objects.requireNonNullElse(username, "aafly");
            password = Objects.requireNonNullElse(password, "");
        }

        public static MysqlSection defaults() {
            return new MysqlSection("localhost", 3306, "aafly", "aafly", "", false, 6);
        }

        @Override
        public String toString() {
            return "MysqlSection[host=" + host + ", port=" + port + ", database=" + database
                    + ", username=" + username + ", password=***, useSsl=" + useSsl + ", poolSize=" + poolSize + "]";
        }
    }

    /** Section {@code limits}. */
    @ConfigSerializable
    public record LimitsSection(long maxSeconds) {
        public static LimitsSection defaults() {
            return new LimitsSection(315_360_000L);
        }
    }

    /** Section {@code decrement}. */
    @ConfigSerializable
    public record DecrementSection(DecrementMode mode, boolean disableWhenStatic, int staticDelaySeconds,
                                   boolean offline) {
        public DecrementSection {
            mode = Objects.requireNonNullElse(mode, DecrementMode.FLYING_MODE);
        }

        public static DecrementSection defaults() {
            return new DecrementSection(DecrementMode.FLYING_MODE, false, 3, false);
        }
    }

    /** Section {@code flight}. */
    @ConfigSerializable
    public record FlightSection(boolean tpOnFloorWhenDisabled, boolean autoReactivateAfterRule,
                                boolean unlimitedForOp, boolean keepFlightOnWorldChange, double defaultSpeed) {
        public static FlightSection defaults() {
            return new FlightSection(true, true, false, true, 0.1D);
        }
    }

    /** Section {@code actionbar}. */
    @ConfigSerializable
    public record ActionBarSection(boolean enabled, boolean hideIfUnlimited, String message) {
        public ActionBarSection {
            message = Objects.requireNonNullElse(message, "<gold>Vol : <yellow>%aafly_remaining%");
        }

        public static ActionBarSection defaults() {
            return new ActionBarSection(false, true, "<gold>Vol : <yellow>%aafly_remaining%");
        }
    }

    /** Section {@code rules}. */
    @ConfigSerializable
    public record RulesSection(int checkIntervalSeconds, boolean bypassNoDecrement) {
        public static RulesSection defaults() {
            return new RulesSection(1, true);
        }
    }

    /** Section {@code pvp}. */
    @ConfigSerializable
    public record PvpSection(boolean enabled, int radius, int checkIntervalSeconds, List<String> bypassPlaceholders) {
        public PvpSection {
            bypassPlaceholders = bypassPlaceholders == null ? List.of() : List.copyOf(bypassPlaceholders);
        }

        public static PvpSection defaults() {
            return new PvpSection(false, 5, 1, List.of());
        }
    }

    /** Section {@code give}. */
    @ConfigSerializable
    public record GiveSection(boolean enabled, long cooldownSeconds, long minSeconds, long maxSeconds) {
        public static GiveSection defaults() {
            return new GiveSection(true, 0L, 5L, 3_600L);
        }
    }

    /** Section {@code token}. */
    @ConfigSerializable
    public record TokenSection(boolean enabled, String material, String name, List<String> lore, int customModelData,
                               long minSeconds, long maxSeconds, long cooldownSeconds) {
        public TokenSection {
            material = Objects.requireNonNullElse(material, "FEATHER");
            name = Objects.requireNonNullElse(name, "<aqua>Jeton de vol <gray>(%remaining%)");
            lore = lore == null ? List.of() : List.copyOf(lore);
        }

        public static TokenSection defaults() {
            return new TokenSection(true, "FEATHER", "<aqua>Jeton de vol <gray>(%remaining%)",
                    List.of("<gray>Clic droit pour encaisser", "<dark_gray>AAFly by AzurHOSTS"), 0,
                    60L, 86_400L, 10L);
        }
    }

    /** Section {@code timed-fly}. */
    @ConfigSerializable
    public record TimedFlySection(boolean enabled, boolean decrementCooldownOffline, boolean resetAfterDisable,
                                  List<TimedFlyGroup> groups) {
        public TimedFlySection {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        public static TimedFlySection defaults() {
            return new TimedFlySection(false, false, false, List.of(
                    new TimedFlyGroup("vip", 120L, 30L),
                    new TimedFlyGroup("default", 10L, 60L)));
        }
    }

    /**
     * Groupe timed-fly : {@code fly-time} secondes de vol, puis {@code cooldown} secondes de recharge.
     *
     * @param name     nom du groupe (permission {@code aafly.group.<name>} ou groupe LuckPerms)
     * @param flyTime  temps de vol rechargé, en secondes
     * @param cooldown durée de recharge, en secondes
     */
    @ConfigSerializable
    public record TimedFlyGroup(String name, long flyTime, long cooldown) {
        public TimedFlyGroup {
            name = Objects.requireNonNullElse(name, "");
        }
    }

    /** Section {@code format}. */
    @ConfigSerializable
    public record FormatSection(boolean seconds, boolean minutes, boolean hours, boolean days, boolean autoFormat,
                                String unlimited, String empty, String pattern, UnitsSection units) {
        public FormatSection {
            unlimited = Objects.requireNonNullElse(unlimited, "Illimité");
            empty = Objects.requireNonNullElse(empty, "0s");
            pattern = Objects.requireNonNullElse(pattern, "%days% %hours% %minutes% %seconds%");
            units = Objects.requireNonNullElseGet(units, UnitsSection::defaults);
        }

        public static FormatSection defaults() {
            return new FormatSection(true, true, true, true, true, "Illimité", "0s",
                    "%days% %hours% %minutes% %seconds%", UnitsSection.defaults());
        }
    }

    /** Section {@code format.units} : gabarit d'affichage de chaque unité. */
    @ConfigSerializable
    public record UnitsSection(String seconds, String minutes, String hours, String days) {
        public UnitsSection {
            seconds = Objects.requireNonNullElse(seconds, "<yellow>%seconds%<gold>s");
            minutes = Objects.requireNonNullElse(minutes, "<yellow>%minutes%<gold>m");
            hours = Objects.requireNonNullElse(hours, "<yellow>%hours%<gold>h");
            days = Objects.requireNonNullElse(days, "<yellow>%days%<gold>j");
        }

        public static UnitsSection defaults() {
            return new UnitsSection("<yellow>%seconds%<gold>s", "<yellow>%minutes%<gold>m",
                    "<yellow>%hours%<gold>h", "<yellow>%days%<gold>j");
        }
    }

    /** Section {@code actions}. */
    @ConfigSerializable
    public record ActionsSection(boolean allowPlayerCommands) {
        public static ActionsSection defaults() {
            return new ActionsSection(false);
        }
    }

    /** Section {@code compat}. */
    @ConfigSerializable
    public record CompatSection(boolean wflyPlaceholders, boolean wflyPermissions) {
        public static CompatSection defaults() {
            return new CompatSection(true, true);
        }
    }

    /** Section {@code commands}. */
    @ConfigSerializable
    public record CommandsSection(List<String> flyAliases, long toggleCooldownMillis) {
        /** Délai par défaut entre deux {@code /fly} (anti-spam). */
        public static final long DEFAULT_TOGGLE_COOLDOWN_MILLIS = 1_000L;
        /** Délai maximal accepté pour {@code commands.toggle-cooldown-millis}. */
        public static final long MAX_TOGGLE_COOLDOWN_MILLIS = 60_000L;

        public CommandsSection {
            flyAliases = flyAliases == null ? List.of() : List.copyOf(flyAliases);
        }

        public static CommandsSection defaults() {
            return new CommandsSection(List.of(), DEFAULT_TOGGLE_COOLDOWN_MILLIS);
        }
    }

    /**
     * Valeurs par défaut complètes (utile pour les tests et comme repli).
     *
     * @return réglages par défaut
     */
    public static Settings defaults() {
        return new Settings(CURRENT_VERSION, false, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        // Évite d'exposer les nœuds bruts ; le mot de passe MySQL est déjà masqué par MysqlSection.
        return "Settings[configVersion=" + configVersion + ", debug=" + debug + ", storage=" + storage
                + ", limits=" + limits + ", decrement=" + decrement + ", flight=" + flight + "]";
    }
}
