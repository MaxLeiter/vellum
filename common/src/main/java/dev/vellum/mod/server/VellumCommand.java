package dev.vellum.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Server commands: {@code /vellum demo chest | live}. The client registers the rest of {@code /vellum} (open, demo
 * pages, reload) and forwards these two here ({@code VellumClientCommands.SERVER_DEMOS}).
 */
public final class VellumCommand {
    private VellumCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("vellum")
                //? if >=26 {
                .then(Commands.literal("demo").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                //?} else
                //.then(Commands.literal("demo").requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("chest").executes(c -> {
                            VellumDemos.openChest(c.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(Commands.literal("live").executes(c -> {
                            VellumDemos.openLive(c.getSource().getPlayerOrException());
                            return 1;
                        }))));
    }
}
