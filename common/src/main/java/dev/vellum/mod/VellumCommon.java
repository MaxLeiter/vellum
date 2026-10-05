package dev.vellum.mod;

import com.mojang.brigadier.CommandDispatcher;
import dev.vellum.mod.gametest.VellumGameTests;
import dev.vellum.mod.server.VellumCommand;
import dev.vellum.mod.server.VellumDemos;
import dev.vellum.mod.server.VellumServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-independent entry points. Each loader calls these from its own events: {@link #init()} at mod construction
 * (before registration), then the server hooks.
 */
public final class VellumCommon {
    private VellumCommon() {}

    /** Reads the settings and declares every registry entry. Call before the loader registers {@code VellumRegistry.entries()}. */
    public static void init() {
        VellumConfig.load();
        VellumDemos.init();
        VellumGameTests.init();
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        VellumCommand.register(dispatcher);
    }

    public static void onPlayerLeft(ServerPlayer player) {
        VellumServer.onPlayerLeft(player);
    }

    public static void onServerStopping() {
        VellumServer.onServerStopping();
    }
}
