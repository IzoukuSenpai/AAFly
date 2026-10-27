package com.azurhosts.aafly.apiimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.api.ApiVersion;
import com.azurhosts.aafly.api.GrantMode;
import com.azurhosts.aafly.api.GrantRequest;
import com.azurhosts.aafly.api.GrantResult.Status;
import org.junit.jupiter.api.Test;

/** Logique pure de {@code AAFlyApi#grant} (SPEC-phase2 B2). Developped by AzurHOSTS.com and powered by AzurWare.fr. */
class GrantLogicTest {

    private static final long MAX = 315_360_000L;

    private static GrantLogic.Input in(long seconds, GrantMode mode, long cap, long current, boolean enabled) {
        return new GrantLogic.Input(seconds, mode, cap, MAX, true, false, true, false, current, enabled);
    }

    private static void assertStop(GrantLogic.Decision d, Status status, long remaining) {
        assertFalse(d.credit());
        assertEquals(status, d.status());
        assertEquals(remaining, d.remaining());
    }

    private static void assertCredit(GrantLogic.Decision d, long target) {
        assertTrue(d.credit());
        assertEquals(target, d.targetSeconds());
    }

    @Test
    void extendAjouteEtPlafonne() {
        assertCredit(GrantLogic.decide(in(600, GrantMode.EXTEND, 7200, 100, false)), 700);
        assertCredit(GrantLogic.decide(in(600, GrantMode.EXTEND, 7200, 7000, true)), 7200);
        assertStop(GrantLogic.decide(in(600, GrantMode.EXTEND, 7200, 7200, true)), Status.CAP_REACHED, 7200);
    }

    @Test
    void extendNeReduitJamaisUnSoldeAuDessusDuPlafond() {
        assertStop(GrantLogic.decide(in(60, GrantMode.EXTEND, 120, 5000, false)), Status.CAP_REACHED, 5000);
    }

    @Test
    void refresh() {
        assertCredit(GrantLogic.decide(in(600, GrantMode.REFRESH, 7200, 100, true)), 600);
        assertStop(GrantLogic.decide(in(600, GrantMode.REFRESH, 7200, 600, true)), Status.ALREADY_ACTIVE, 600);
        assertStop(GrantLogic.decide(in(600, GrantMode.REFRESH, 7200, 900, false)), Status.ALREADY_ACTIVE, 900);
    }

    @Test
    void denyIfActive() {
        assertCredit(GrantLogic.decide(in(600, GrantMode.DENY_IF_ACTIVE, 7200, 0, false)), 600);
        assertStop(GrantLogic.decide(in(600, GrantMode.DENY_IF_ACTIVE, 7200, 0, true)), Status.ALREADY_ACTIVE, 0);
        assertStop(GrantLogic.decide(in(600, GrantMode.DENY_IF_ACTIVE, 7200, 1, false)), Status.ALREADY_ACTIVE, 1);
    }

    @Test
    void capEffectifEstLeMinimumAvecMaxSeconds() {
        assertEquals(100L, GrantLogic.effectiveCap(7200, 100));
        assertEquals(7200L, GrantLogic.effectiveCap(7200, MAX));
        GrantLogic.Input over = new GrantLogic.Input(150, GrantMode.EXTEND, 7200, 100, true, false, true, false, 0,
                false);
        assertThrows(IllegalArgumentException.class, () -> GrantLogic.decide(over));
        assertCredit(GrantLogic.decide(new GrantLogic.Input(100, GrantMode.EXTEND, 7200, 100, true, false, true,
                false, 0, false)), 100);
    }

    @Test
    void horsBornesLeveIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> GrantLogic.decide(in(7201, GrantMode.EXTEND, 7200, 0, false)));
        assertThrows(IllegalArgumentException.class, () -> GrantLogic.decide(in(0, GrantMode.EXTEND, 7200, 0, false)));
        // Erreur de programmation signalée même si les crédits externes sont désactivés.
        assertThrows(IllegalArgumentException.class, () -> GrantLogic.decide(
                new GrantLogic.Input(0, GrantMode.EXTEND, 10, MAX, false, false, true, false, 0, false)));
    }

    @Test
    void ordreDesStatutsTerminaux() {
        // Désactivé par config > non chargé > illimité.
        assertStop(GrantLogic.decide(new GrantLogic.Input(10, GrantMode.EXTEND, 60, MAX, false, false, false, true,
                0, false)), Status.DISABLED_BY_CONFIG, -1);
        assertStop(GrantLogic.decide(new GrantLogic.Input(10, GrantMode.EXTEND, 60, MAX, true, true, true, false,
                30, false)), Status.DISABLED_BY_CONFIG, 30);
        assertStop(GrantLogic.decide(new GrantLogic.Input(10, GrantMode.EXTEND, 60, MAX, true, false, false, true,
                0, false)), Status.NOT_LOADED, -1);
        assertStop(GrantLogic.decide(new GrantLogic.Input(10, GrantMode.EXTEND, 60, MAX, true, false, true, true,
                30, true)), Status.UNLIMITED, -1);
    }

    @Test
    void additionSatureeSansDebordement() {
        long big = Long.MAX_VALUE - 5;
        assertCredit(GrantLogic.decide(new GrantLogic.Input(10, GrantMode.EXTEND, Long.MAX_VALUE, Long.MAX_VALUE,
                true, false, true, false, big, false)), Long.MAX_VALUE);
    }

    @Test
    void validationDeLaDemande() {
        assertThrows(IllegalArgumentException.class, () -> new GrantRequest(10, GrantMode.EXTEND, 60, true, "AAItems"));
        assertThrows(IllegalArgumentException.class, () -> new GrantRequest(10, GrantMode.EXTEND, 60, true, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantRequest(10, GrantMode.EXTEND, 60, true, "a".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> new GrantRequest(10, GrantMode.EXTEND, 60, true, "a b"));
        assertThrows(IllegalArgumentException.class, () -> new GrantRequest(0, GrantMode.EXTEND, 60, true, "aaitems"));
        assertThrows(IllegalArgumentException.class, () -> new GrantRequest(10, GrantMode.EXTEND, 0, true, "aaitems"));
        assertThrows(NullPointerException.class, () -> new GrantRequest(10, null, 60, true, "aaitems"));
        GrantRequest ok = new GrantRequest(10, GrantMode.EXTEND, 60, true, "aaitems:plume_de_vol-1.0");
        assertEquals("aaitems:plume_de_vol-1.0", ok.source());
    }

    @Test
    void compatibiliteDeVersion() {
        ApiVersion v = ApiVersion.CURRENT;
        assertEquals("1.0.0", v.toString());
        assertTrue(v.isCompatibleWith(ApiVersion.parse("1.0.0")));
        assertTrue(new ApiVersion(1, 2, 0).isCompatibleWith(new ApiVersion(1, 1, 5)));
        assertFalse(new ApiVersion(1, 0, 0).isCompatibleWith(new ApiVersion(1, 1, 0)));
        assertFalse(new ApiVersion(2, 0, 0).isCompatibleWith(new ApiVersion(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> ApiVersion.parse("1.0"));
        assertThrows(IllegalArgumentException.class, () -> new ApiVersion(-1, 0, 0));
    }
}
