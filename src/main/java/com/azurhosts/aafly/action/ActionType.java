package com.azurhosts.aafly.action;

import java.util.Locale;
import java.util.Optional;

/** Types d'actions reconnus dans {@code "[type] contenu"}. */
public enum ActionType {
    /** Message chat (MiniMessage). */
    MESSAGE("message"),
    /** Barre d'action. */
    ACTIONBAR("actionbar"),
    /** Titre ({@code titre;sous-titre}). */
    TITLE("title"),
    /** Sous-titre seul. */
    SUBTITLE("subtitle"),
    /** Commande exécutée par la console. */
    COMMAND("command"),
    /** Commande exécutée par le joueur (seulement si {@code actions.allow-player-commands}). */
    PLAYER("player"),
    /** Son ({@code NOM[;volume;pitch]}). */
    SOUND("sound");

    private final String key;

    ActionType(String key) {
        this.key = key;
    }

    /**
     * @return identifiant tel qu'écrit entre crochets
     */
    public String key() {
        return key;
    }

    /**
     * @param key identifiant (insensible à la casse)
     * @return le type correspondant, ou vide
     */
    public static Optional<ActionType> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (ActionType t : values()) {
            if (t.key.equals(k)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
