package com.azurhosts.aafly.action;

import java.io.Serial;

/** Action mal formée dans la configuration (type inconnu, contenu vide, son invalide…). */
public final class InvalidActionException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message raison lisible (en français)
     */
    public InvalidActionException(String message) {
        super(message);
    }
}
