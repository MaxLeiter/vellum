package dev.vellum.mod.net;

import dev.vellum.mod.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: show a UI for a server session. Exactly one of {@code url} (a page the client has, e.g. bundled in
 * a mod or a resource pack) and {@code html} (an inline page sent by the server) is non-empty.
 *
 * @param session the server's id for this UI; messages and data refer to it
 * @param data    the initial {@code vellum.data}, a JSON value
 */
public record OpenPayload(int session, String url, String html, String data) implements CustomPacketPayload {
    public static final Type<OpenPayload> TYPE = new Type<>(Constants.id("open"));
    public static final int MAX_URL = 256;
    /** Inline pages and data stay well inside the 1 MiB clientbound payload limit, even at 3 bytes per char. */
    public static final int MAX_HTML = 200_000;
    public static final int MAX_DATA = 100_000;

    public static final StreamCodec<ByteBuf, OpenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenPayload::session,
            ByteBufCodecs.stringUtf8(MAX_URL), OpenPayload::url,
            ByteBufCodecs.stringUtf8(MAX_HTML), OpenPayload::html,
            ByteBufCodecs.stringUtf8(MAX_DATA), OpenPayload::data,
            OpenPayload::new);

    @Override
    public Type<OpenPayload> type() {
        return TYPE;
    }
}
