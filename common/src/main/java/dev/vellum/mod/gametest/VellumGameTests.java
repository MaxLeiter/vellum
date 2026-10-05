package dev.vellum.mod.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.vellum.mod.net.ClosePayload;
import dev.vellum.mod.net.ClosedPayload;
import dev.vellum.mod.net.DataPayload;
import dev.vellum.mod.net.MessagePayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.net.VellumNetwork;
import dev.vellum.mod.server.VellumDemos;
import dev.vellum.mod.server.VellumServer;
import dev.vellum.mod.server.VellumSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * GameTests for the server side: payload codecs and their size limits, the session lifecycle (open, data,
 * messages, rate limiting, closing from either side, players leaving) against mock players, and the demo chest.
 */
public final class VellumGameTests {
    private VellumGameTests() {}

    /** Declares the tests ({@link GameTests#add}), these and {@link SecurityGameTests}'. */
    public static void init() {
        GameTests.add("payload_codecs", VellumGameTests::codecs);
        GameTests.add("payload_limits", VellumGameTests::limits);
        GameTests.add("session_lifecycle", VellumGameTests::sessions);
        GameTests.add("session_rate_limit", VellumGameTests::rateLimit);
        GameTests.add("demo_chest_menu", VellumGameTests::chest);
        SecurityGameTests.init();
    }

    // ---- Payloads ----

    private static void codecs(GameTestHelper h) {
        roundTrip(h, OpenPayload.STREAM_CODEC, new OpenPayload(7, "vellum:vellum/demo/index.html", "", "{\"a\":[1,2]}"));
        roundTrip(h, OpenPayload.STREAM_CODEC, new OpenPayload(8, "", "<p>héllo ✓</p>", "null"));
        roundTrip(h, DataPayload.STREAM_CODEC, new DataPayload(7, "{\"n\":3}"));
        roundTrip(h, ClosePayload.STREAM_CODEC, new ClosePayload(Integer.MAX_VALUE));
        roundTrip(h, MessagePayload.STREAM_CODEC, new MessagePayload(7, "buy", "\"diamond\""));
        roundTrip(h, ClosedPayload.STREAM_CODEC, new ClosedPayload(0));
        h.succeed();
    }

    private static <T> void roundTrip(GameTestHelper h, StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, value);
        T decoded = codec.decode(buf);
        h.assertValueEqual(decoded, value, "round trip");
        h.assertValueEqual(buf.readableBytes(), 0, "bytes left after decoding");
    }

    private static void limits(GameTestHelper h) {
        String bigHtml = "x".repeat(OpenPayload.MAX_HTML + 1);
        h.assertTrue(throwsOn(() -> OpenPayload.STREAM_CODEC.encode(Unpooled.buffer(), new OpenPayload(1, "", bigHtml, "null")), EncoderException.class),
                "oversized inline HTML must not encode");
        String bigJson = "\"" + "y".repeat(MessagePayload.MAX_JSON) + "\"";
        h.assertTrue(throwsOn(() -> MessagePayload.STREAM_CODEC.encode(Unpooled.buffer(), new MessagePayload(1, "c", bigJson)), EncoderException.class),
                "oversized messages must not encode");
        // A hostile client writing past the limit is rejected while decoding.
        ByteBuf crafted = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(crafted, 1);
        ByteBufCodecs.stringUtf8(1000).encode(crafted, "c".repeat(MessagePayload.MAX_CHANNEL + 1));
        ByteBufCodecs.stringUtf8(10).encode(crafted, "1");
        h.assertTrue(throwsOn(() -> MessagePayload.STREAM_CODEC.decode(crafted), DecoderException.class), "oversized channel must not decode");
        // The server API fails early with a clear error rather than at the network layer.
        ServerPlayer player = player(h);
        try {
            h.assertTrue(throwsOn(() -> VellumServer.openInline(player, bigHtml, null), IllegalArgumentException.class), "openInline checks size");
            JsonObject huge = new JsonObject();
            huge.addProperty("s", "z".repeat(OpenPayload.MAX_DATA));
            h.assertTrue(throwsOn(() -> VellumServer.open(player, "vellum:x.html", huge), IllegalArgumentException.class), "open checks data size");
        } finally {
            leave(h, player);
        }
        h.succeed();
    }

    private static boolean throwsOn(Runnable action, Class<? extends Throwable> type) {
        try {
            action.run();
            return false;
        } catch (Throwable t) {
            return type.isInstance(t);
        }
    }

    // ---- Sessions ----

    private static void sessions(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        ServerPlayer player = player(h), other = player(h);
        VellumNetwork.intercept((p, payload) -> sent.add(payload));
        try {
            JsonObject data = new JsonObject();
            data.addProperty("coins", 3);
            VellumSession session = VellumServer.open(player, "vellum:vellum/demo/templates.html", data);
            h.assertTrue(session.isOpen(), "session opens");
            OpenPayload open = (OpenPayload) sent.getLast();
            h.assertValueEqual(open.session(), session.id(), "open payload session");
            h.assertValueEqual(open.url(), "vellum:vellum/demo/templates.html", "open payload url");
            h.assertValueEqual(open.data(), "{\"coins\":3}", "open payload data");

            List<String> received = new ArrayList<>();
            int[] closes = {0};
            session.onMessage("buy", (p, value) -> received.add(p.getGameProfile().name() + ":" + value.getAsString()))
                    .onClose(() -> closes[0]++);
            VellumServer.handleMessage(player, new MessagePayload(session.id(), "buy", "\"apple\""));
            VellumServer.handleMessage(player, new MessagePayload(session.id(), "buy", "{not json"));
            VellumServer.handleMessage(player, new MessagePayload(session.id(), "sell", "1"));
            VellumServer.handleMessage(other, new MessagePayload(session.id(), "buy", "\"stolen\""));
            h.assertValueEqual(received, List.of("test-mock-player:apple"), "only the owner's well-formed messages reach the handler");

            session.push(new JsonPrimitive(42));
            h.assertValueEqual(sent.getLast(), new DataPayload(session.id(), "42"), "push sends data");

            VellumServer.handleClosed(player, new ClosedPayload(session.id()));
            h.assertFalse(session.isOpen(), "client close ends the session");
            h.assertValueEqual(closes[0], 1, "onClose runs once");
            VellumServer.handleMessage(player, new MessagePayload(session.id(), "buy", "\"late\""));
            h.assertValueEqual(received.size(), 1, "messages after close are ignored");

            VellumSession second = VellumServer.openInline(player, "<p>hi</p>", null);
            int[] secondCloses = {0};
            second.onClose(() -> secondCloses[0]++);
            second.close();
            h.assertValueEqual(sent.getLast(), new ClosePayload(second.id()), "server close tells the client");
            second.close();
            h.assertValueEqual(secondCloses[0], 1, "closing twice runs onClose once");

            VellumSession third = VellumServer.open(player, "vellum:a.html", null);
            VellumServer.onPlayerLeft(player);
            h.assertFalse(third.isOpen(), "leaving ends the player's sessions");
            h.assertTrue(VellumServer.session(player, third.id()) == null, "ended sessions are forgotten");
        } finally {
            VellumNetwork.intercept(null);
            leave(h, player);
            leave(h, other);
        }
        h.succeed();
    }

    private static void rateLimit(GameTestHelper h) {
        ServerPlayer player = player(h);
        VellumNetwork.intercept((p, payload) -> {});
        try {
            VellumSession session = VellumServer.open(player, "vellum:a.html", null);
            int[] count = {0};
            session.onMessage("spam", (p, value) -> count[0]++);
            for (int i = 0; i < 500; i++) VellumServer.handleMessage(player, new MessagePayload(session.id(), "spam", "1"));
            h.assertTrue(count[0] >= 40 && count[0] < 100, "a burst of 500 messages is cut to about 40, got " + count[0]);
            session.close();
        } finally {
            VellumNetwork.intercept(null);
            leave(h, player);
        }
        h.succeed();
    }

    // ---- Demo chest ----

    private static void chest(GameTestHelper h) {
        ServerPlayer player = player(h);
        try {
            VellumDemos.openChest(player);
            h.assertValueEqual(player.containerMenu.getType(), VellumDemos.CHEST, "the demo chest has its own menu type");
            h.assertValueEqual(player.containerMenu.slots.size(), 27 + 36, "chest and player inventory slots");
            h.assertTrue(player.containerMenu.getSlot(0).getItem().is(Items.DIAMOND_SWORD), "the chest holds the sample items");
            player.closeContainer();
        } finally {
            leave(h, player);
        }
        h.succeed();
    }

    /** A player with a loopback connection; remove it with {@link #leave}. */
    @SuppressWarnings("removal")
    static ServerPlayer player(GameTestHelper h) {
        return h.makeMockServerPlayerInLevel();
    }

    static void leave(GameTestHelper h, ServerPlayer player) {
        h.getLevel().getServer().getPlayerList().remove(player);
    }
}
