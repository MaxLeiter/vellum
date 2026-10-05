package dev.vellum.engine.security;

import dev.vellum.engine.Limits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitsTest {
    @Test
    void fieldsAreReadAndSetByName() {
        assertTrue(Limits.names().containsAll(java.util.List.of("instructionBudget", "maxNodes", "maxDepth", "heapLimitPercent")));
        assertEquals(512, Limits.DEFAULTS.get("maxDepth"));
        Limits l = Limits.DEFAULTS.with("maxNodes", 5).with("instructionBudget", 1L << 40);
        assertEquals(5, l.maxNodes());
        assertEquals(1L << 40, l.instructionBudget());
        assertEquals(Limits.DEFAULTS.maxDepth(), l.maxDepth());
        for (String name : Limits.names()) assertEquals(Limits.DEFAULTS.get(name), Limits.DEFAULTS.with(name, Limits.DEFAULTS.get(name)).get(name));
    }

    @Test
    void badValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Limits.DEFAULTS.with("nope", 1));
        assertThrows(IllegalArgumentException.class, () -> Limits.DEFAULTS.with("maxNodes", 0));
        assertThrows(IllegalArgumentException.class, () -> Limits.DEFAULTS.with("maxNodes", -5));
        assertThrows(IllegalArgumentException.class, () -> Limits.DEFAULTS.with("maxNodes", 1L << 40));
        assertThrows(IllegalArgumentException.class, () -> Limits.DEFAULTS.with("heapLimitPercent", 101));
    }

    @Test
    void hostsUseTheInstalledLimits() {
        Limits saved = Limits.current();
        try {
            Limits mine = Limits.DEFAULTS.with("maxNodes", 7);
            Limits.setCurrent(mine);
            assertSame(mine, new dev.vellum.engine.host.Host() {
                @Override public dev.vellum.engine.host.FontMetrics fonts() { return null; }
                @Override public String loadText(String url) { return null; }
            }.limits());
        } finally {
            Limits.setCurrent(saved);
        }
    }
}
