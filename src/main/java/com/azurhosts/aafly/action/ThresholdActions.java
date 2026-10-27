package com.azurhosts.aafly.action;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Lecture d'une entrée {@code commands-time-remaining} (SPEC-phase2 A8). Deux formes acceptées :
 * <pre>
 * commands-time-remaining:
 *   60:                                   # forme AAFly : liste d'actions
 *     - "[title] &lt;red&gt;1 minute;&lt;gray&gt;de vol"
 *     - "[sound] BLOCK_NOTE_BLOCK_PLING"
 *   30:                                   # forme héritée wFly : { commands: "..." }
 *     commands: "say %player% : 30 s"     # texte ou liste de textes, chacun converti en [command] ...
 * </pre>
 * Dans la forme héritée, une entrée qui commence déjà par un type d'action connu ({@code [message] …})
 * est conservée telle quelle ; sinon elle devient {@code [command] …} (commande console, contenu issu de la
 * configuration uniquement). Les autres clés de la forme héritée sont ignorées. Classe pure, sans serveur.
 *
 * <p>AAFly — Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
public final class ThresholdActions {

    /** Clé de la forme héritée wFly. */
    public static final String LEGACY_COMMANDS = "commands";

    private static final Pattern TYPED = Pattern.compile("^\\s*\\[([A-Za-z_]{1,16})]");

    private ThresholdActions() {
    }

    /**
     * @param node valeur d'un seuil
     * @return actions brutes ({@code "[type] contenu"}), ou {@code null} si la forme n'est pas reconnue
     */
    public static @Nullable List<String> read(ConfigurationNode node) {
        if (node.isMap()) {
            ConfigurationNode commands = node.node(LEGACY_COMMANDS);
            if (commands.virtual() || commands.raw() == null) {
                return null;
            }
            List<String> raws = strings(commands);
            if (raws == null) {
                return null;
            }
            List<String> out = new ArrayList<>(raws.size());
            for (String raw : raws) {
                out.add(convertLegacy(raw));
            }
            return List.copyOf(out);
        }
        return strings(node);
    }

    /**
     * Convertit une commande de la forme héritée wFly.
     *
     * @param raw texte de configuration
     * @return action {@code "[type] contenu"}
     */
    public static String convertLegacy(String raw) {
        var m = TYPED.matcher(raw);
        if (m.find() && ActionType.fromKey(m.group(1)).isPresent()) {
            return raw;
        }
        return "[" + ActionType.COMMAND.key() + "] " + raw.strip();
    }

    private static @Nullable List<String> strings(ConfigurationNode node) {
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
}
