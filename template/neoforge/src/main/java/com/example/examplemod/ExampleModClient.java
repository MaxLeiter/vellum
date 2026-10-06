package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** The client side of the mod: a /notes command that opens the notes page. */
@Mod(value = ExampleModClient.MOD_ID, dist = Dist.CLIENT)
public final class ExampleModClient {
    public static final String MOD_ID = "examplemod";

    public ExampleModClient() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("notes").executes(context -> {
                    // After the chat screen closes, or it would close the page.
                    Minecraft.getInstance().schedule(Notes::open);
                    return 1;
                })));
    }
}
