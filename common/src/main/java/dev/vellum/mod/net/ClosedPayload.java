package dev.vellum.mod.net;

import dev.vellum.mod.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the player closed a session's screen (or it was replaced by another). */
public record ClosedPayload(int session) implements CustomPacketPayload {
    public static final Type<ClosedPayload> TYPE = new Type<>(Constants.id("closed"));

    public static final StreamCodec<ByteBuf, ClosedPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(ClosedPayload::new, ClosedPayload::session);

    @Override
    public Type<ClosedPayload> type() {
        return TYPE;
    }
}
