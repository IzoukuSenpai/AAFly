package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Tests de {@link DecrementPolicy} (SPEC §4 « FlightTicker », étapes 2 et 3). */
class DecrementPolicyTest {

    private static final long NOW = 1_000_000L;
    private static final BooleanSupplier NO_RULE = () -> false;
    private static final BooleanSupplier RULE_MATCHES = () -> true;

    private static boolean decide(DecrementMode mode, boolean allow, boolean flying, boolean staticCheck,
                                  long lastMove, boolean bypass, boolean bypassNoDec, BooleanSupplier rules) {
        return DecrementPolicy.shouldDecrement(mode, allow, flying, staticCheck, 5, lastMove, NOW,
                bypass, bypassNoDec, rules);
    }

    @Test
    void modes() {
        assertTrue(decide(DecrementMode.FLY_MODE, true, false, false, NOW, false, true, NO_RULE));
        assertFalse(decide(DecrementMode.FLYING_MODE, true, false, false, NOW, false, true, NO_RULE));
        assertTrue(decide(DecrementMode.FLYING_MODE, true, true, false, NOW, false, true, NO_RULE));
        assertFalse(decide(DecrementMode.FLY_MODE, false, true, false, NOW, false, true, NO_RULE));
    }

    @Test
    void immobilite() {
        assertFalse(decide(DecrementMode.FLY_MODE, true, true, true, NOW - 5_000L, false, true, NO_RULE));
        assertTrue(decide(DecrementMode.FLY_MODE, true, true, true, NOW - 4_999L, false, true, NO_RULE));
        assertTrue(decide(DecrementMode.FLY_MODE, true, true, false, NOW - 60_000L, false, true, NO_RULE));
    }

    @Test
    void contournementEtReglesDeDecompte() {
        AtomicInteger calls = new AtomicInteger();
        BooleanSupplier counted = () -> {
            calls.incrementAndGet();
            return true;
        };
        assertFalse(decide(DecrementMode.FLY_MODE, true, true, false, NOW, true, true, counted));
        assertTrue(decide(DecrementMode.FLY_MODE, true, true, false, NOW, true, false, counted));
        // Les decrement-rules ne sont pas consultées pour un joueur qui contourne les règles.
        assertTrue(calls.get() == 0);
        assertFalse(decide(DecrementMode.FLY_MODE, true, true, false, NOW, false, true, RULE_MATCHES));
        assertTrue(decide(DecrementMode.FLY_MODE, true, true, false, NOW, false, true, NO_RULE));
    }
}
