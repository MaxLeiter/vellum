package dev.vellum.mod.net;

import dev.vellum.mod.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: {@code vellum.send(channel, value)} from a session's page; {@code json} is the value as JSON. */
public record MessagePayload(int session, String channel, String json) implements CustomPacketPayload {
    public static final Type<MessagePayload> TYPE = new Type<>(Constants.id("message"));
    public static final int MAX_CHANNEL = 64;
    /** Serverbound payloads are capped at 32 KiB; messages are small by design. */
    public static final int MAX_JSON = 8192;

    public static final StreamCodec<ByteBuf, MessagePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MessagePayload::session,
            ByteBufCodecs.stringUtf8(MAX_CHANNEL), MessagePayload::channel,
            ByteBufCodecs.stringUtf8(MAX_JSON), MessagePayload::json,
            MessagePayload::new);

    @Override
    public Type<MessagePayload> type() {
        return TYPE;
    }
}
