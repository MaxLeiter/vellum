package dev.vellum.mod.gametest;

import dev.vellum.mod.registry.VellumRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.function.Consumer;

/**
 * How a GameTest is declared on this Minecraft version; this is the one for 26.x, where tests are test functions in
 * a registry and their test instances are data ({@code data/vellum/test_instance/<name>.json}: the structure, the
 * ticks). Every version has a GameTests with the same methods ({@code common/versions/<version>/src}).
 */
final class GameTests {
    private GameTests() {}

    /** Declares test function {@code vellum:<name>}; the loader registers it with the mod's other registry entries. */
    static void add(String name, Consumer<GameTestHelper> body) {
        VellumRegistry.add(Registries.TEST_FUNCTION, name, () -> body);
    }
}
