package com.azurhosts.aafly.rules;

import java.io.Serial;

/**
 * Règle ou condition invalide dans la configuration. Le moteur la journalise (WARN : nom + raison)
 * et ignore la règle ; jamais de crash.
 */
public final class InvalidRuleException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message raison lisible (en français)
     */
    public InvalidRuleException(String message) {
        super(message);
    }

    /**
     * @param message raison lisible
     * @param cause   cause d'origine
     */
    public InvalidRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
