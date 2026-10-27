package com.azurhosts.aafly.action;

import com.azurhosts.aafly.config.Lang;
import com.azurhosts.aafly.core.FlightProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Gabarit d'action pré-analysé au chargement : texte de configuration découpé en segments
 * (littéraux, variables AAFly {@code %player%}/{@code %uuid%}/{@code %remaining%}, et placeholders
 * externes {@code %…%} confiés à PlaceholderAPI).
 *
 * <p><b>Sécurité.</b> La substitution se fait en une seule passe sur les segments : une valeur insérée
 * n'est jamais ré-analysée (pas d'injection de placeholder ni de tag). Règles :
 * <ul>
 *   <li>{@code %player%} : pseudo validé {@code ^[A-Za-z0-9_]{1,16}$}, sinon action ignorée ;</li>
 *   <li>{@code %uuid%} : UUID strictement formé, sinon action ignorée ;</li>
 *   <li>{@code %remaining%} : texte formaté issu de la configuration ;</li>
 *   <li>{@code %remaining_seconds%} : entier {@code ^[0-9]{1,19}$}, sinon action ignorée ;</li>
 *   <li>placeholders externes : dans un <i>message</i>, insérés comme composant (codes couleur legacy
 *       seulement, aucun tag MiniMessage interprété) ; dans une <i>commande</i>, la valeur doit respecter
 *       {@link #SAFE_COMMAND_VALUE} (pas d'espace, pas de caractère de contrôle), sinon action ignorée.
 *       Un placeholder non résolu reste tel quel (texte de configuration).</li>
 * </ul>
 * Classe pure et immuable : utilisable sans serveur.
 */
public final class ActionTemplate {

    /** Placeholder potentiel : {@code %identifiant%} sans espace ni {@code %} interne. */
    public static final Pattern TOKEN = Pattern.compile("%([^%\\s]{1,128})%");
    /** Valeurs PlaceholderAPI admises dans une commande. */
    public static final Pattern SAFE_COMMAND_VALUE = Pattern.compile("^[\\p{L}\\p{N}_.,:+\\-]{0,64}$");
    /** Format strict d'un UUID. */
    public static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** Format strict de {@code %remaining_seconds%} : entier positif. */
    public static final Pattern SECONDS_PATTERN = Pattern.compile("^[0-9]{1,19}$");
    /** Longueur maximale d'une valeur PlaceholderAPI insérée dans un message. */
    public static final int MAX_EXTERNAL_LENGTH = 256;

    private static final String TAG_PREFIX = "aafly_v";
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern AMP_CODE = Pattern.compile("&([0-9a-fk-orA-FK-ORxX#])");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** Segment de gabarit. */
    public sealed interface Segment permits Literal, Variable, External {
    }

    /**
     * Texte littéral de configuration.
     *
     * @param text texte
     */
    public record Literal(String text) implements Segment {
    }

    /**
     * Variable AAFly ({@link Action#VAR_PLAYER}, {@link Action#VAR_UUID}, {@link Action#VAR_REMAINING}).
     *
     * @param name nom de la variable
     */
    public record Variable(String name) implements Segment {
    }

    /**
     * Placeholder externe (PlaceholderAPI).
     *
     * @param token jeton complet, {@code %} inclus
     */
    public record External(String token) implements Segment {
    }

    /**
     * Texte MiniMessage prêt à parser avec ses résolveurs (valeurs dynamiques échappées).
     *
     * @param text     texte MiniMessage (configuration + tags {@code <aafly_vN>})
     * @param resolver résolveur des tags dynamiques
     */
    public record MiniMessageText(String text, TagResolver resolver) {
    }

    private final String raw;
    private final List<Segment> segments;
    private final boolean external;

    private ActionTemplate(String raw, List<Segment> segments) {
        this.raw = raw;
        this.segments = List.copyOf(segments);
        boolean ext = false;
        for (Segment s : this.segments) {
            if (s instanceof External) {
                ext = true;
                break;
            }
        }
        this.external = ext;
    }

    /**
     * Découpe un texte de configuration en segments.
     *
     * @param raw texte brut
     * @return gabarit
     */
    public static ActionTemplate parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        List<Segment> segments = new ArrayList<>();
        Matcher m = TOKEN.matcher(raw);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                segments.add(new Literal(raw.substring(last, m.start())));
            }
            String name = m.group(1);
            switch (name) {
                case Action.VAR_PLAYER, Action.VAR_UUID, Action.VAR_REMAINING, Action.VAR_REMAINING_SECONDS ->
                        segments.add(new Variable(name));
                default -> segments.add(new External(m.group()));
            }
            last = m.end();
        }
        if (last < raw.length()) {
            segments.add(new Literal(raw.substring(last)));
        }
        return new ActionTemplate(raw, segments);
    }

    /**
     * @return texte brut d'origine
     */
    public String raw() {
        return raw;
    }

    /**
     * @return segments (non modifiable)
     */
    public List<Segment> segments() {
        return segments;
    }

    /**
     * @return {@code true} si le gabarit contient des placeholders externes
     */
    public boolean usesPlaceholders() {
        return external;
    }

    /**
     * Rendu en texte brut pour une commande.
     *
     * @param vars variables ({@code player}, {@code uuid}, {@code remaining})
     * @param papi résolution d'un jeton PlaceholderAPI (identité si absent)
     * @return commande prête à exécuter
     * @throws ActionSkippedException si une valeur est invalide ou dangereuse
     */
    public String renderCommand(Map<String, String> vars, UnaryOperator<String> papi) throws ActionSkippedException {
        StringBuilder sb = new StringBuilder(raw.length() + 32);
        for (Segment s : segments) {
            switch (s) {
                case Literal l -> sb.append(l.text());
                case Variable v -> sb.append(variablePlain(v.name(), vars));
                case External e -> {
                    String resolved = papi.apply(e.token());
                    if (resolved == null || resolved.equals(e.token())) {
                        sb.append(e.token());
                    } else if (SAFE_COMMAND_VALUE.matcher(resolved).matches()) {
                        sb.append(resolved);
                    } else {
                        throw new ActionSkippedException("valeur de " + e.token()
                                + " refusée dans une commande (caractères non autorisés)");
                    }
                }
            }
        }
        String out = sb.toString().strip();
        if (CONTROL.matcher(out).find()) {
            throw new ActionSkippedException("commande contenant des caractères de contrôle");
        }
        return out;
    }

    /**
     * Rendu MiniMessage pour un message, une barre d'action ou un titre.
     *
     * @param vars variables
     * @param papi résolution d'un jeton PlaceholderAPI
     * @return texte et résolveurs
     * @throws ActionSkippedException si {@code %player%} ou {@code %uuid%} est invalide
     */
    public MiniMessageText renderMiniMessage(Map<String, String> vars, UnaryOperator<String> papi)
            throws ActionSkippedException {
        StringBuilder sb = new StringBuilder(raw.length() + 32);
        List<TagResolver> resolvers = new ArrayList<>();
        int index = 0;
        for (Segment s : segments) {
            switch (s) {
                case Literal l -> sb.append(l.text());
                case Variable v -> {
                    String tag = TAG_PREFIX + index++;
                    String value = vars.get(v.name());
                    if (Action.VAR_REMAINING.equals(v.name())) {
                        // Texte formaté issu de la configuration (TimeFormatter) : interprété.
                        resolvers.add(Placeholder.parsed(tag, Lang.legacyToMiniMessage(stripControl(value))));
                    } else {
                        resolvers.add(Placeholder.unparsed(tag, variablePlain(v.name(), vars)));
                    }
                    sb.append('<').append(tag).append('>');
                }
                case External e -> {
                    String resolved = papi.apply(e.token());
                    if (resolved == null || resolved.equals(e.token())) {
                        sb.append(e.token());
                    } else {
                        String tag = TAG_PREFIX + index++;
                        resolvers.add(Placeholder.component(tag, legacyComponentText(resolved)));
                        sb.append('<').append(tag).append('>');
                    }
                }
            }
        }
        return new MiniMessageText(sb.toString(), TagResolver.resolver(resolvers));
    }

    private static String variablePlain(String name, Map<String, String> vars) throws ActionSkippedException {
        String value = vars.get(name);
        return switch (name) {
            case Action.VAR_PLAYER -> {
                if (value == null || !FlightProfile.NAME_PATTERN.matcher(value).matches()) {
                    throw new ActionSkippedException("pseudo invalide pour %player%");
                }
                yield value;
            }
            case Action.VAR_UUID -> {
                if (value == null || !UUID_PATTERN.matcher(value).matches()) {
                    throw new ActionSkippedException("UUID invalide pour %uuid%");
                }
                yield value;
            }
            case Action.VAR_REMAINING -> MiniMessage.miniMessage().stripTags(Lang.legacyToMiniMessage(stripControl(value)));
            case Action.VAR_REMAINING_SECONDS -> {
                if (value == null || !SECONDS_PATTERN.matcher(value).matches()) {
                    throw new ActionSkippedException("valeur invalide ou indisponible pour %remaining_seconds%");
                }
                yield value;
            }
            default -> throw new ActionSkippedException("variable inconnue");
        };
    }

    private static String stripControl(String value) {
        return value == null ? "" : CONTROL.matcher(value).replaceAll("");
    }

    /**
     * Convertit une valeur externe en composant : seuls les codes couleur legacy sont interprétés
     * (jamais de MiniMessage, donc ni clic ni survol injectables).
     *
     * @param value valeur résolue
     * @return composant
     */
    static net.kyori.adventure.text.Component legacyComponentText(String value) {
        String v = stripControl(value);
        if (v.length() > MAX_EXTERNAL_LENGTH) {
            v = v.substring(0, MAX_EXTERNAL_LENGTH);
        }
        v = AMP_CODE.matcher(v).replaceAll(LegacyComponentSerializer.SECTION_CHAR + "$1");
        return LEGACY.deserialize(v);
    }

    @Override
    public String toString() {
        return raw;
    }
}
