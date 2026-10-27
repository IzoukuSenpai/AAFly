package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azurhosts.aafly.core.DefaultSafeLanding.Cell;
import com.azurhosts.aafly.core.DefaultSafeLanding.Kind;
import com.azurhosts.aafly.core.DefaultSafeLanding.ScanResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Atterrissage sûr phase 2 (SPEC-phase2 A3) : profondeur bornée, eau en repli, jamais de traversée d'un
 * bloc plein, vérification des positions de repli, limitation à une mémorisation par seconde.
 * Developped by AzurHOSTS.com and powered by AzurWare.fr.
 */
class SafeLandingFallbackTest {

    private static final Cell AIR = Cell.AIR;
    private static final Cell STONE = new Cell(false, false, true, false, false);
    private static final Cell WATER = new Cell(true, true, false, false, false);
    private static final Cell LAVA = new Cell(true, true, false, true, true);
    private static final Cell MAGMA = new Cell(false, false, true, true, false);
    private static final Cell FIRE = new Cell(true, false, false, true, true);

    private static ScanResult scan(Map<Integer, Cell> column, int startY, int maxScan) {
        return DefaultSafeLanding.scan(startY, -64, 319, maxScan, y -> column.getOrDefault(y, AIR));
    }

    @Test
    void solDansLaProfondeur() {
        Map<Integer, Cell> c = new HashMap<>();
        c.put(60, STONE);
        assertEquals(new ScanResult(Kind.FLOOR, 60), scan(c, 80, 64));
    }

    @Test
    void solHorsProfondeurIgnore() {
        Map<Integer, Cell> c = new HashMap<>();
        c.put(10, STONE);
        // 80 → 17 inspectés (64 blocs) : le sol à 10 est hors de portée → repli.
        assertEquals(Kind.NONE, scan(c, 80, 64).kind());
        assertEquals(Kind.FLOOR, scan(c, 80, 71).kind());
    }

    @Test
    void jamaisSousLeMinimumDuMonde() {
        assertEquals(Kind.NONE, DefaultSafeLanding.scan(-60, -64, 319, 4096, y -> {
            if (y < -64) {
                throw new AssertionError("lecture sous le minimum du monde : " + y);
            }
            return AIR;
        }).kind());
    }

    @Test
    void eauAccepteeEnRepli() {
        Map<Integer, Cell> c = new HashMap<>();
        c.put(50, STONE);
        c.put(51, WATER);
        c.put(52, WATER);
        assertEquals(new ScanResult(Kind.WATER, 52), scan(c, 80, 64));
        // La forme historique (sol uniquement) ne retient pas l'eau.
        assertEquals(null, DefaultSafeLanding.scanColumn(80, -64, 319, y -> c.getOrDefault(y, AIR)));
    }

    @Test
    void premierObstacleDangereuxBloqueSansTraverser() {
        Map<Integer, Cell> lave = new HashMap<>();
        lave.put(40, STONE);
        lave.put(60, LAVA);
        assertEquals(Kind.BLOCKED, scan(lave, 80, 64).kind());

        Map<Integer, Cell> magma = new HashMap<>();
        magma.put(40, STONE);
        magma.put(60, MAGMA);
        assertEquals(Kind.BLOCKED, scan(magma, 80, 64).kind());

        Map<Integer, Cell> toitEnFeu = new HashMap<>();
        toitEnFeu.put(40, STONE);
        toitEnFeu.put(60, STONE);
        toitEnFeu.put(61, FIRE);
        assertEquals(Kind.BLOCKED, scan(toitEnFeu, 80, 64).kind());
    }

    @Test
    void positionDeRepliVerifiee() {
        assertTrue(DefaultSafeLanding.isStandable(STONE, AIR, AIR));
        assertFalse(DefaultSafeLanding.isStandable(MAGMA, AIR, AIR));
        assertFalse(DefaultSafeLanding.isStandable(WATER, AIR, AIR));
        assertFalse(DefaultSafeLanding.isStandable(AIR, AIR, AIR));
        assertFalse(DefaultSafeLanding.isStandable(STONE, FIRE, AIR));
        assertFalse(DefaultSafeLanding.isStandable(STONE, AIR, STONE));
        assertFalse(DefaultSafeLanding.isStandable(STONE, WATER, AIR));
    }

    @Test
    void memorisationAuPlusUneFoisParSeconde() {
        assertTrue(DefaultSafeLanding.shouldSample(Long.MIN_VALUE, 0L));
        assertFalse(DefaultSafeLanding.shouldSample(10_000L, 10_999L));
        assertTrue(DefaultSafeLanding.shouldSample(10_000L, 11_000L));
    }

    @Test
    void profondeurBornee() {
        assertEquals(1, DefaultSafeLanding.clampScan(0));
        assertEquals(1, DefaultSafeLanding.clampScan(-5));
        assertEquals(64, DefaultSafeLanding.clampScan(64));
        assertEquals(4096, DefaultSafeLanding.clampScan(Integer.MAX_VALUE));
    }
}
