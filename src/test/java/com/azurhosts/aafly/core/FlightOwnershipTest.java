package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests de {@link FlightOwnership} (SPEC-phase2 B3) : AAFly ne retire que le vol qu'il a accordé.
 * Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class FlightOwnershipTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void volNonAccordeParAAFlyNonPossede() {
        FlightOwnership o = new FlightOwnership();
        assertFalse(o.owns(A));
        // Retirer un vol non possédé : refusé, l'appelant ne doit toucher ni allowFlight ni flying.
        assertFalse(o.release(A));
        assertFalse(FlightOwnership.mayRevoke(o.owns(A), false));
    }

    @Test
    void accordePuisRetire() {
        FlightOwnership o = new FlightOwnership();
        assertTrue(o.claim(A));
        assertFalse(o.claim(A), "idempotent");
        assertTrue(o.owns(A));
        assertFalse(o.owns(B));
        assertTrue(o.release(A));
        assertFalse(o.owns(A));
        assertFalse(o.release(A), "un second retrait ne touche plus au vol");
    }

    @Test
    void jamaisDeRetraitEnCreatifOuSpectateur() {
        assertTrue(FlightOwnership.mayRevoke(true, false));
        assertFalse(FlightOwnership.mayRevoke(true, true));
        assertFalse(FlightOwnership.mayRevoke(false, true));
    }

    @Test
    void perteAuProfitDUnTiers() {
        // Possédé mais allowFlight retiré par un tiers (hors mode natif) : propriété cédée.
        assertTrue(FlightOwnership.lostToThirdParty(true, false, false));
        assertFalse(FlightOwnership.lostToThirdParty(true, true, false));
        assertFalse(FlightOwnership.lostToThirdParty(false, false, false));
        assertFalse(FlightOwnership.lostToThirdParty(true, false, true));
    }

    @Test
    void arretNeConcerneQueLesVolsPossedes() {
        FlightOwnership o = new FlightOwnership();
        o.claim(A);
        assertEquals(1, o.snapshot().size());
        assertTrue(o.snapshot().contains(A));
        assertFalse(o.snapshot().contains(B));
        o.forget(A);
        assertEquals(0, o.size());
    }
}
