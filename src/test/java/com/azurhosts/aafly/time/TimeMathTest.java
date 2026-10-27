package com.azurhosts.aafly.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimeMathTest {

    @Test
    void clamp() {
        assertEquals(0L, TimeMath.clamp(-5L, 100L));
        assertEquals(50L, TimeMath.clamp(50L, 100L));
        assertEquals(100L, TimeMath.clamp(500L, 100L));
        assertEquals(0L, TimeMath.clamp(5L, -1L));
    }

    @Test
    void additionSaturee() {
        assertEquals(150L, TimeMath.add(100L, 50L, 1_000L));
        assertEquals(1_000L, TimeMath.add(900L, 500L, 1_000L));
        assertEquals(0L, TimeMath.add(10L, -50L, 1_000L));
        assertEquals(1_000L, TimeMath.add(Long.MAX_VALUE, Long.MAX_VALUE, 1_000L));
        assertEquals(0L, TimeMath.add(Long.MIN_VALUE, -1L, 1_000L));
    }

    @Test
    void soustraction() {
        assertEquals(5L, TimeMath.subtract(10L, 5L));
        assertEquals(0L, TimeMath.subtract(10L, 50L));
        assertEquals(10L, TimeMath.subtract(10L, -5L));
        assertEquals(0L, TimeMath.subtract(-3L, 1L));
        assertEquals(0L, TimeMath.subtract(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void multiplication() {
        assertEquals(6L, TimeMath.multiply(2L, 3L));
        assertEquals(Long.MAX_VALUE, TimeMath.multiply(Long.MAX_VALUE, 2L));
        assertEquals(0L, TimeMath.multiply(-2L, 3L));
    }

    @Test
    void conversions() {
        assertEquals(90L, TimeMath.toUnit(90L, TimeUnit.SECONDS));
        assertEquals(1L, TimeMath.toUnit(90L, TimeUnit.MINUTES));
        assertEquals(2L, TimeMath.toUnit(7_300L, TimeUnit.HOURS));
        assertEquals(1L, TimeMath.toUnit(100_000L, TimeUnit.DAYS));
        assertEquals(0L, TimeMath.toUnit(-10L, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> TimeMath.toUnit(1L, TimeUnit.MILLISECONDS));
    }

    @Test
    void restantJusqua() {
        assertEquals(30L, TimeMath.remainingUntil(130L, 100L));
        assertEquals(0L, TimeMath.remainingUntil(100L, 130L));
    }
}
