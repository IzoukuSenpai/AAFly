package com.azurhosts.aafly.action;

import java.io.Serial;

/**
 * Une action ne peut pas être exécutée pour ce joueur (pseudo invalide pour {@code %player%},
 * valeur PlaceholderAPI dangereuse dans une commande…). L'action est ignorée et un WARN est émis.
 */
public final class ActionSkippedException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message raison lisible (en français)
     */
    public ActionSkippedException(String message) {
        super(message);
    }
}
