package dev.vellum.mod.net;

import dev.vellum.mod.platform.Network;
import dev.vellum.mod.platform.Services;
import dev.vellum.mod.server.VellumServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.VisibleForTesting;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;

/** The payload registry both loaders iterate, and the common way to send clientbound payloads. */
public final class VellumNetwork {
    /** Bump when a payload's wire format changes (NeoForge refuses mismatched versions). */
    public static final String PROTOCOL_VERSION = "1";

    /** A server-to-client payload. Its client handler is a case in {@code VellumClient.handle}. */
    public record Clientbound<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                             StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {}

    /** A client-to-server payload and its handler, which runs on the server thread. */
    public record Serverbound<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                             StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                             BiConsumer<ServerPlayer, T> handler) {}

    public static final List<Clientbound<?>> CLIENTBOUND = List.of(
            new Clientbound<>(OpenPayload.TYPE, OpenPayload.STREAM_CODEC),
            new Clientbound<>(DataPayload.TYPE, DataPayload.STREAM_CODEC),
            new Clientbound<>(ClosePayload.TYPE, ClosePayload.STREAM_CODEC));

    public static final List<Serverbound<?>> SERVERBOUND = List.of(
            new Serverbound<>(MessagePayload.TYPE, MessagePayload.STREAM_CODEC, VellumServer::handleMessage),
            new Serverbound<>(ClosedPayload.TYPE, ClosedPayload.STREAM_CODEC, VellumServer::handleClosed));

    private static volatile @Nullable Network impl;
    private static volatile @Nullable BiConsumer<ServerPlayer, CustomPacketPayload> interceptor;

    private VellumNetwork() {}

    /** Sends a clientbound payload; false if the player's client cannot receive it (it lacks Vellum). */
    public static boolean sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        BiConsumer<ServerPlayer, CustomPacketPayload> sink = interceptor;
        if (sink != null) {
            sink.accept(player, payload);
            return true;
        }
        Network n = impl;
        if (n == null) impl = n = Services.load(Network.class);
        return n.sendToPlayer(player, payload);
    }

    /**
     * Test hook: routes clientbound payloads to {@code sink} instead of the network, so GameTests can run sessions
     * against mock players (which have no negotiated channels). Pass null to restore the network.
     */
    @VisibleForTesting
    public static void intercept(@Nullable BiConsumer<ServerPlayer, CustomPacketPayload> sink) {
        interceptor = sink;
    }
}
