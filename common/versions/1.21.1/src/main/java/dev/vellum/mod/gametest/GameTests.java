package dev.vellum.mod.gametest;

import dev.vellum.mod.Constants;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * How a GameTest is declared on this Minecraft version; this is the one for 1.21.1, where tests come from annotated
 * methods. {@link #tests} is a {@link GameTestGenerator} that makes a test of each declared body, in the arena
 * structure and with the ticks of the 26.x test instances ({@code data/vellum/test_instance/*.json}). NeoForge
 * registers this class in {@code RegisterGameTestsEvent}; Fabric lists it as a {@code fabric-gametest} entrypoint.
 */
public final class GameTests {
    /** As every test instance has them. */
    private static final String STRUCTURE = Constants.MOD_ID + ":test/arena";
    private static final int MAX_TICKS = 40, SETUP_TICKS = 1;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();

    /** Fabric makes an instance of each {@code fabric-gametest} entrypoint class. */
    public GameTests() {}

    /** Declares test {@code vellum:<name>}. */
    static void add(String name, Consumer<GameTestHelper> body) {
        TESTS.put(name, body);
    }

    @GameTestGenerator
    public static List<TestFunction> tests() {
        List<TestFunction> tests = new ArrayList<>();
        TESTS.forEach((name, body) -> tests.add(new TestFunction(Constants.MOD_ID, Constants.MOD_ID + ":" + name, STRUCTURE,
                MAX_TICKS, SETUP_TICKS, true, body)));
        return tests;
    }
}
