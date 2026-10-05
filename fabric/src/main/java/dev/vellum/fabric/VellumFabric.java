package dev.vellum.fabric;

import dev.vellum.mod.VellumCommon;
import dev.vellum.mod.net.VellumNetwork;
import dev.vellum.mod.registry.Entry;
import dev.vellum.mod.registry.VellumRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class VellumFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        VellumCommon.init();
        registerAll();
        for (VellumNetwork.Clientbound<?> c : VellumNetwork.CLIENTBOUND) clientbound(c);
        for (VellumNetwork.Serverbound<?> s : VellumNetwork.SERVERBOUND) serverbound(s);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> VellumCommon.registerCommands(dispatcher));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VellumCommon.onPlayerLeft(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> VellumCommon.onServerStopping());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerAll() {
        for (Entry<?> entry : VellumRegistry.entries()) {
            Registry registry = BuiltInRegistries.REGISTRY.getValue(entry.registry().identifier());
            if (registry == null) throw new IllegalStateException("Unknown registry " + entry.registry());
            Registry.register(registry, entry.id(), entry.create());
        }
    }

    private static <T extends CustomPacketPayload> void clientbound(VellumNetwork.Clientbound<T> c) {
        PayloadTypeRegistry.clientboundPlay().register(c.type(), c.codec());
    }

    private static <T extends CustomPacketPayload> void serverbound(VellumNetwork.Serverbound<T> s) {
        PayloadTypeRegistry.serverboundPlay().register(s.type(), s.codec());
        ServerPlayNetworking.registerGlobalReceiver(s.type(),
                (payload, context) -> context.server().execute(() -> s.handler().accept(context.player(), payload)));
    }
}
