package com.azurhosts.aafly.core;

/** Mode de décompte du temps de vol (config {@code decrement.mode}), appliqué par {@link DecrementPolicy}. */
public enum DecrementMode {
    /** Décompte dès que le vol est activé (allowFlight), même au sol. */
    FLY_MODE,
    /** Décompte uniquement quand le joueur vole réellement ({@code Player#isFlying()}). */
    FLYING_MODE
}
