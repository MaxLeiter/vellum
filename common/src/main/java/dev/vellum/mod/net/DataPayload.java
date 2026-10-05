package dev.vellum.mod.net;

import dev.vellum.mod.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: new {@code vellum.data} (a JSON value) for an open session. */
public record DataPayload(int session, String data) implements CustomPacketPayload {
    public static final Type<DataPayload> TYPE = new Type<>(Constants.id("data"));

    public static final StreamCodec<ByteBuf, DataPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DataPayload::session,
            ByteBufCodecs.stringUtf8(OpenPayload.MAX_DATA), DataPayload::data,
            DataPayload::new);

    @Override
    public Type<DataPayload> type() {
        return TYPE;
    }
}
