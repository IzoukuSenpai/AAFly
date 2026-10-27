package com.azurhosts.aafly.storage;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Ligne de la table {@code aafly_tokens} au moment de la création d'un jeton.
 *
 * @param tokenId             identifiant unique du jeton (nonce)
 * @param seconds             temps de vol contenu (&gt; 0)
 * @param createdBy           créateur (joueur), {@code null} si console
 * @param createdAtEpochSeconds date de création (epoch, secondes)
 */
public record TokenRecord(UUID tokenId, long seconds, @Nullable UUID createdBy, long createdAtEpochSeconds) {
    public TokenRecord {
        Objects.requireNonNull(tokenId, "tokenId");
        if (seconds <= 0L) {
            throw new IllegalArgumentException("seconds doit être > 0");
        }
    }
}
