package dev.vellum.mod.net;

import dev.vellum.mod.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the server closed a session; the client closes its screen if it still shows it. */
public record ClosePayload(int session) implements CustomPacketPayload {
    public static final Type<ClosePayload> TYPE = new Type<>(Constants.id("close"));

    public static final StreamCodec<ByteBuf, ClosePayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(ClosePayload::new, ClosePayload::session);

    @Override
    public Type<ClosePayload> type() {
        return TYPE;
    }
}
