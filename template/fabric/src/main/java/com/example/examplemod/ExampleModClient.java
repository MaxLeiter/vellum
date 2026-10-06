package com.example.examplemod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;

/** The client side of the mod: a /notes command that opens the notes page. */
public final class ExampleModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("notes").executes(context -> {
                    // After the chat screen closes, or it would close the page.
                    Minecraft.getInstance().schedule(Notes::open);
                    return 1;
                })));
    }
}
