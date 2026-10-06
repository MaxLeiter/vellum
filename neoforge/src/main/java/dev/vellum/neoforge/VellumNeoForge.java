package dev.vellum.neoforge;

import dev.vellum.mod.Constants;
import dev.vellum.mod.VellumCommon;
import dev.vellum.mod.net.VellumNetwork;
import dev.vellum.mod.registry.Entry;
import dev.vellum.mod.registry.VellumRegistry;
import net.minecraft.core.Registry;
//? if <26 {
/*import dev.vellum.mod.gametest.GameTests;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
*///?}
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.RegisterEvent;
//? if <26
//import java.util.function.Consumer;

@Mod(Constants.MOD_ID)
public final class VellumNeoForge {
    //? if <26 {
    /*// What clientbound payloads do: NeoForge 21.1 takes their handlers here, with the payload types, so the client
    // entry point installs them.
    public static Consumer<CustomPacketPayload> clientHandler = payload -> {};
    *///?}

    public VellumNeoForge(IEventBus modBus, ModContainer container) {
        VellumCommon.init();
        modBus.addListener(VellumNeoForge::register);
        modBus.addListener(VellumNeoForge::registerPayloads);
        //? if <26 {
        /*// 1.21.1's GameTests come from annotated classes: Vellum's are made by a generator (on 26.x they are registry
        // entries, registered with the others).
        modBus.addListener((RegisterGameTestsEvent e) -> e.register(GameTests.class));
        *///?}

        var bus = NeoForge.EVENT_BUS;
        bus.addListener((RegisterCommandsEvent e) -> VellumCommon.registerCommands(e.getDispatcher()));
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) VellumCommon.onPlayerLeft(player);
        });
        bus.addListener((ServerStoppingEvent e) -> VellumCommon.onServerStopping());
    }

    /** Registers every common entry whose registry matches this event. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(RegisterEvent event) {
        for (Entry<?> entry : VellumRegistry.entries()) {
            if (!entry.registry().equals(event.getRegistryKey())) continue;
            Entry raw = entry;
            event.register((ResourceKey<? extends Registry<Object>>) (ResourceKey) entry.registry(), entry.id(), raw::create);
        }
    }

    /** Clientbound payloads; their client handlers are added by {@code VellumNeoForgeClient}. */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional: servers without Vellum, and Vellum servers with vanilla clients, can still connect.
        PayloadRegistrar registrar = event.registrar(VellumNetwork.PROTOCOL_VERSION).optional();
        for (VellumNetwork.Clientbound<?> c : VellumNetwork.CLIENTBOUND) clientbound(registrar, c);
        for (VellumNetwork.Serverbound<?> s : VellumNetwork.SERVERBOUND) serverbound(registrar, s);
    }

    private static <T extends CustomPacketPayload> void clientbound(PayloadRegistrar registrar, VellumNetwork.Clientbound<T> c) {
        //? if >=26 {
        registrar.playToClient(c.type(), c.codec());
        //?} else
        //registrar.playToClient(c.type(), c.codec(), (payload, context) -> clientHandler.accept(payload));
    }

    /** Serverbound handlers run on the server thread (the registrar's default HandlerThread.MAIN). */
    private static <T extends CustomPacketPayload> void serverbound(PayloadRegistrar registrar, VellumNetwork.Serverbound<T> s) {
        registrar.playToServer(s.type(), s.codec(), (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) s.handler().accept(player, payload);
        });
    }
}
