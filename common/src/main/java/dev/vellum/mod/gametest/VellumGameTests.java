package dev.vellum.mod.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.vellum.mod.PageData;
import dev.vellum.mod.net.ClosePayload;
import dev.vellum.mod.net.ClosedPayload;
import dev.vellum.mod.net.DataPayload;
import dev.vellum.mod.net.MessagePayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.net.PatchPayload;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * GameTests for the server side: payload codecs and their size limits, the session lifecycle (open, data, patches,
 * messages, rate limiting, closing from either side, players leaving) against mock players, the demo chest, and
 * {@link PageData}, which keeps a page's data on the client.
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
        GameTests.add("page_data", VellumGameTests::pageData);
        SecurityGameTests.init();
    }

    // ---- Payloads ----

    private static void codecs(GameTestHelper h) {
        roundTrip(h, OpenPayload.STREAM_CODEC, new OpenPayload(7, "vellum:vellum/demo/index.html", "", "{\"a\":[1,2]}"));
        roundTrip(h, OpenPayload.STREAM_CODEC, new OpenPayload(8, "", "<p>héllo ✓</p>", "null"));
        roundTrip(h, DataPayload.STREAM_CODEC, new DataPayload(7, "{\"n\":3}"));
        roundTrip(h, PatchPayload.STREAM_CODEC, new PatchPayload(7, "hp", "{\"n\":3}"));
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

    static boolean throwsOn(Runnable action, Class<? extends Throwable> type) {
        try {
            action.run();
            return false;
        } catch (Throwable t) {
            return type.isInstance(t);
        }
    }

    // ---- Page data ----

    private static void pageData(GameTestHelper h) {
        PageData data = new PageData();
        h.assertTrue(data.json() == null, "no data at first");
        JsonObject fields = new JsonObject();
        fields.addProperty("name", "Steve");
        JsonArray mobs = new JsonArray();
        mobs.add("zombie");
        fields.add("mobs", mobs);
        data.set(fields);
        mobs.add("changed later");
        h.assertValueEqual(data.json(), "{\"name\":\"Steve\",\"mobs\":[\"zombie\"]}", "kept as pushed, not as changed after");
        h.assertValueEqual(data.put("hp", new JsonPrimitive(20)), "20", "put returns the value's JSON");
        data.put("name", "\"Alex\"");
        h.assertValueEqual(data.json(), "{\"name\":\"Alex\",\"mobs\":[\"zombie\"],\"hp\":20}", "a field set again keeps its place");
        h.assertTrue(data.put("mobs", JsonNull.INSTANCE) == null, "JSON null removes");
        data.put("hp", (String) null);
        h.assertValueEqual(data.json(), "{\"name\":\"Alex\"}", "removed fields are gone");
        JsonObject merged = new JsonObject();
        merged.addProperty("a\"b", 1);
        data.merge(merged);
        String json = data.json();
        h.assertValueEqual(JsonParser.parseString(json).getAsJsonObject().get("a\"b").getAsInt(), 1, "keys are quoted");
        h.assertValueEqual(data.charsWith("name", 3), json.length() - "\"Alex\"".length() + 3, "the size with a field replaced");
        h.assertValueEqual(data.charsWith("new", 1), json.length() + ",\"new\":1".length(), "the size with a field added");

        // Data kept as one text (a server's) is split when a field is set; anything but an object becomes one.
        data.set("{\"x\": [1, 2], \"y\": true}");
        h.assertValueEqual(data.charsWith("z", 1), "{\"x\":[1,2],\"y\":true,\"z\":1}".length(), "server data is split to measure");
        data.put("z", "1");
        h.assertValueEqual(data.json(), "{\"x\":[1,2],\"y\":true,\"z\":1}", "and patched");
        data.set("[1, 2]");
        data.put("a", "1");
        h.assertValueEqual(data.json(), "{\"a\":1}", "an array becomes an object");
        data.set("{nope");
        data.put("a", "1");
        h.assertValueEqual(data.json(), "{\"a\":1}", "so does malformed data");
        data.set(new JsonObject());
        h.assertValueEqual(data.json(), "{}", "an empty object");
        h.assertValueEqual(data.charsWith("a", 1), "{\"a\":1}".length(), "the size of the first field");
        PageData copy = new PageData();
        copy.set(data);
        copy.put("a", "1");
        h.assertValueEqual(data.json(), "{}", "a copy changes on its own");
        h.succeed();
    }

    // ---- Sessions ----

    private static void sessions(GameTestHelper h) {
        List<CustomPacketPayload> sent = new ArrayList<>();
        withPlayers(h, (p, payload) -> sent.add(payload), (player, other) -> {
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
            session.patch("hp", new JsonPrimitive(7));
            h.assertValueEqual(sent.getLast(), new PatchPayload(session.id(), "hp", "7"), "patch sends only the field");
            session.patch("hp", null);
            h.assertValueEqual(sent.getLast(), new PatchPayload(session.id(), "hp", "null"), "a null patch removes the field");
            JsonPrimitive huge = new JsonPrimitive("z".repeat(OpenPayload.MAX_DATA));
            h.assertTrue(throwsOn(() -> session.patch("s", huge), IllegalArgumentException.class), "patch checks the value's size");
            h.assertTrue(throwsOn(() -> session.patch("k".repeat(PatchPayload.MAX_KEY + 1), null), IllegalArgumentException.class),
                    "patch checks the key's size");

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
        });
        h.succeed();
    }

    private static void rateLimit(GameTestHelper h) {
        withPlayer(h, (p, payload) -> {}, player -> {
            VellumSession session = VellumServer.open(player, "vellum:a.html", null);
            int[] count = {0};
            session.onMessage("spam", (p, value) -> count[0]++);
            for (int i = 0; i < 500; i++) VellumServer.handleMessage(player, new MessagePayload(session.id(), "spam", "1"));
            h.assertTrue(count[0] >= 40 && count[0] < 100, "a burst of 500 messages is cut to about 40, got " + count[0]);
            session.close();
        });
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

    /** Runs {@code body} with a mock player while the server's payloads go to {@code sent}; removes the player after. */
    static void withPlayer(GameTestHelper h, BiConsumer<ServerPlayer, CustomPacketPayload> sent, Consumer<ServerPlayer> body) {
        ServerPlayer player = player(h);
        VellumNetwork.intercept(sent);
        try {
            body.accept(player);
        } finally {
            VellumNetwork.intercept(null);
            leave(h, player);
        }
    }

    /** {@link #withPlayer} with another player beside the first, for what one player must not do to the other's sessions. */
    static void withPlayers(GameTestHelper h, BiConsumer<ServerPlayer, CustomPacketPayload> sent,
                            BiConsumer<ServerPlayer, ServerPlayer> body) {
        ServerPlayer other = player(h);
        try {
            withPlayer(h, sent, player -> body.accept(player, other));
        } finally {
            leave(h, other);
        }
    }
}
