package dev.vellum.mod.net;

import dev.vellum.mod.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: sets one top-level field of an open session's {@code vellum.data} to {@code value} (a JSON
 * value), or removes it when {@code value} is JSON null.
 */
public record PatchPayload(int session, String key, String value) implements CustomPacketPayload {
    public static final Type<PatchPayload> TYPE = new Type<>(Constants.id("patch"));
    public static final int MAX_KEY = 256;

    public static final StreamCodec<ByteBuf, PatchPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PatchPayload::session,
            ByteBufCodecs.stringUtf8(MAX_KEY), PatchPayload::key,
            ByteBufCodecs.stringUtf8(OpenPayload.MAX_DATA), PatchPayload::value,
            PatchPayload::new);

    @Override
    public Type<PatchPayload> type() {
        return TYPE;
    }
}
