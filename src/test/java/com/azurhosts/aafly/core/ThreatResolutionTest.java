package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.api.ThreatResolver.Verdict;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Combinaison des résolveurs de menace (SPEC-phase2 A6) et validité du cache des placeholders.
 * Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class ThreatResolutionTest {

    @Test
    void combinaisonDesAvisTiers() {
        assertEquals(Verdict.ABSTAIN, ThreatResolverRegistry.combine(List.of()));
        assertEquals(Verdict.ABSTAIN, ThreatResolverRegistry.combine(Arrays.asList(Verdict.ABSTAIN, null)));
        assertEquals(Verdict.NOT_THREAT, ThreatResolverRegistry.combine(List.of(Verdict.ABSTAIN, Verdict.NOT_THREAT)));
        assertEquals(Verdict.THREAT, ThreatResolverRegistry.combine(List.of(Verdict.NOT_THREAT, Verdict.THREAT)));
    }

    @Test
    void residuelParDefautEtChoixSur() {
        // Un avis tiers tranche.
        assertTrue(NearbyEnemyDetector.decide(Verdict.THREAT, Verdict.NOT_THREAT));
        assertFalse(NearbyEnemyDetector.decide(Verdict.NOT_THREAT, Verdict.THREAT));
        // Abstention : le résolveur par défaut décide ; abstention ou absence d'avis = menace.
        assertFalse(NearbyEnemyDetector.decide(Verdict.ABSTAIN, Verdict.NOT_THREAT));
        assertTrue(NearbyEnemyDetector.decide(Verdict.ABSTAIN, Verdict.THREAT));
        assertTrue(NearbyEnemyDetector.decide(Verdict.ABSTAIN, Verdict.ABSTAIN));
        assertTrue(NearbyEnemyDetector.decide(Verdict.ABSTAIN, null));
    }

    @Test
    void cacheDeDeuxSecondes() {
        int now = 1_000;
        int expires = now + BuiltinThreatResolver.CACHE_TICKS;
        assertTrue(BuiltinThreatResolver.isFresh(expires, now));
        assertTrue(BuiltinThreatResolver.isFresh(expires, expires - 1));
        assertFalse(BuiltinThreatResolver.isFresh(expires, expires));
        // Débordement du compteur de ticks : comparaison toujours correcte.
        assertTrue(BuiltinThreatResolver.isFresh(Integer.MIN_VALUE + 10, Integer.MAX_VALUE - 10));
    }
}
