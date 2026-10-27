package com.azurhosts.aafly.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests de la descente de {@link DefaultSafeLanding#scanColumn} : le joueur ne doit jamais traverser un sol
 * plein (fond d'un lac, toit, lave) pour atterrir dans une cavité située dessous.
 */
class SafeLandingScanTest {

    private static final DefaultSafeLanding.Cell AIR = DefaultSafeLanding.Cell.AIR;
    private static final DefaultSafeLanding.Cell STONE = new DefaultSafeLanding.Cell(false, false, true, false, false);
    private static final DefaultSafeLanding.Cell WATER = new DefaultSafeLanding.Cell(true, true, false, false, false);
    private static final DefaultSafeLanding.Cell LAVA = new DefaultSafeLanding.Cell(true, true, false, true, true);
    private static final DefaultSafeLanding.Cell FIRE = new DefaultSafeLanding.Cell(true, false, false, true, true);
    private static final DefaultSafeLanding.Cell GRASS = new DefaultSafeLanding.Cell(true, false, false, false, false);

    private static @Nullable Integer scan(Map<Integer, DefaultSafeLanding.Cell> column, int startY) {
        return DefaultSafeLanding.scanColumn(startY, 0, 255, y -> column.getOrDefault(y, AIR));
    }

    @Test
    void solOrdinaireTrouve() {
        Map<Integer, DefaultSafeLanding.Cell> c = new HashMap<>();
        c.put(60, STONE);
        c.put(61, GRASS);
        assertEquals(60, scan(c, 80));
    }

    @Test
    void solSousDeLEauRenvoieNull() {
        Map<Integer, DefaultSafeLanding.Cell> c = new HashMap<>();
        c.put(50, STONE);           // cavité scellée : sol 50, air 51-52
        c.put(53, STONE);
        c.put(54, STONE);
        c.put(55, STONE);           // fond du lac
        c.put(56, WATER);
        c.put(57, WATER);
        assertNull(scan(c, 80));
    }

    @Test
    void toitInondeNeMenePasALaCaviteDessous() {
        Map<Integer, DefaultSafeLanding.Cell> c = new HashMap<>();
        c.put(40, STONE);           // sol de la base
        c.put(45, STONE);           // toit
        c.put(46, WATER);
        assertNull(scan(c, 80));
    }

    @Test
    void lavePlafondRenvoieNull() {
        Map<Integer, DefaultSafeLanding.Cell> c = new HashMap<>();
        c.put(40, STONE);
        c.put(60, LAVA);
        assertNull(scan(c, 80));
    }

    @Test
    void feuSurLeToitRenvoieNull() {
        Map<Integer, DefaultSafeLanding.Cell> c = new HashMap<>();
        c.put(40, STONE);           // sol de la base, libre dessus
        c.put(60, STONE);           // toit
        c.put(61, FIRE);
        assertNull(scan(c, 80));
    }

    @Test
    void plafondTropBasRenvoieNull() {
        Map<Integer, DefaultSafeLanding.Cell> c = new HashMap<>();
        c.put(40, STONE);
        c.put(60, STONE);
        c.put(62, STONE);           // un seul bloc libre au-dessus de 60
        assertNull(scan(c, 61));    // premier obstacle (60) sans deux blocs libres : pas de sol plus bas
    }

    @Test
    void videSansSolRenvoieNull() {
        assertNull(scan(new HashMap<>(), 80));
    }
}
