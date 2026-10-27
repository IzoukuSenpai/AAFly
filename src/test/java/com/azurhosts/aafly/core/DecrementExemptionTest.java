package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.DecrementPolicy.Exemption;
import org.junit.jupiter.api.Test;

/**
 * Exemptions de décompte (SPEC-phase2 A4) et coexistence (B3) de {@link DecrementPolicy}.
 * Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class DecrementExemptionTest {

    @Test
    void exemptions() {
        assertEquals(Exemption.NONE, DecrementPolicy.exemption(false, false, false, false, true));
        assertEquals(Exemption.UNLIMITED, DecrementPolicy.exemption(true, false, false, false, true));
        assertEquals(Exemption.CREATIVE, DecrementPolicy.exemption(false, true, false, false, true));
        assertEquals(Exemption.SPECTATOR, DecrementPolicy.exemption(false, false, true, false, true));
        assertEquals(Exemption.BYPASS, DecrementPolicy.exemption(false, false, false, true, true));
        // bypass sans rules.bypass-no-decrement : le temps est décompté normalement.
        assertEquals(Exemption.NONE, DecrementPolicy.exemption(false, false, false, true, false));
    }

    @Test
    void prioriteDesModesNatifs() {
        assertEquals(Exemption.SPECTATOR, DecrementPolicy.exemption(true, true, true, true, true));
        assertEquals(Exemption.CREATIVE, DecrementPolicy.exemption(true, true, false, true, true));
    }

    @Test
    void opNonExempteImplicitement() {
        // OP sans permission explicite : la valeur par défaut « OP » ne vaut pas permission.
        assertFalse(DecrementPolicy.permissionGranted(true, true, false));
        assertTrue(DecrementPolicy.permissionGranted(true, true, true));
        assertTrue(DecrementPolicy.permissionGranted(true, false, false));
        assertFalse(DecrementPolicy.permissionGranted(false, true, true));
        // Donc un OP non illimité et sans bypass explicite n'a aucune exemption.
        assertEquals(Exemption.NONE, DecrementPolicy.exemption(
                DecrementPolicy.permissionGranted(true, true, false), false, false,
                DecrementPolicy.permissionGranted(true, true, false), true));
    }

    @Test
    void volDUnAutrePluginJamaisDecompte() {
        assertFalse(DecrementPolicy.shouldDecrement(false, DecrementMode.FLY_MODE, true, true, false, 3, 0L, 0L,
                false, true, () -> false));
        assertTrue(DecrementPolicy.shouldDecrement(true, DecrementMode.FLY_MODE, true, true, false, 3, 0L, 0L,
                false, true, () -> false));
    }
}
