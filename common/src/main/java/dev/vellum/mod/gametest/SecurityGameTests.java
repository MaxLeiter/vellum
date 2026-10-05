package dev.vellum.mod.gametest;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.vellum.engine.Limits;
import dev.vellum.mod.TokenBucket;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.net.JsonLimits;
import dev.vellum.mod.net.MessagePayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.net.VellumNetwork;
import dev.vellum.mod.registry.Entry;
import dev.vellum.mod.registry.VellumRegistry;
import dev.vellum.mod.server.VellumServer;
import dev.vellum.mod.server.VellumSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * GameTests for what a hostile client or server can send: JSON nesting and strictness, oversized and malformed
 * payloads, the cap on a player's sessions, rate limits, and the settings file's fallbacks.
 */
public final class SecurityGameTests {
    public static final Entry<Consumer<GameTestHelper>> JSON = test("json_limits", SecurityGameTests::json);
    public static final Entry<Consumer<GameTestHelper>> MESSAGES = test("hostile_messages", SecurityGameTests::messages);
    public static final Entry<Consumer<GameTestHelper>> PAYLOADS = test("malformed_payloads", SecurityGameTests::payloads);
    public static final Entry<Consumer<GameTestHelper>> SESSION_CAP = test("session_cap", SecurityGameTests::sessionCap);
    public static final Entry<Consumer<GameTestHelper>> CONFIG = test("config_fallbacks", SecurityGameTests::config);
    public static final Entry<Consumer<GameTestHelper>> BUCKET = test("token_bucket", SecurityGameTests::bucket);

    private SecurityGameTests() {}

    public static void init() {}

    private static Entry<Consumer<GameTestHelper>> test(String name, Consumer<GameTestHelper> body) {
        return VellumRegistry.add(Registries.TEST_FUNCTION, name, () -> body);
    }

    private static void json(GameTestHelper h) {
        h.assertValueEqual(JsonLimits.depth("1"), 0, "depth of a number");
        h.assertValueEqual(JsonLimits.depth("{\"a\":[1,{\"b\":[]}]}"), 4, "depth of nested values");
        h.assertValueEqual(JsonLimits.depth("\"[[[[\\\"[[\""), 0, "brackets in strings don't count");
        h.assertValueEqual(JsonLimits.parse("\"apple\"", 8).getAsString(), "apple", "a string is a message");
        h.assertValueEqual(JsonLimits.parse(" {\"n\": 3} ", 8).getAsJsonObject().get("n").getAsInt(), 3, "an object is a message");
        for (String bad : List.of("NaN", "{\"n\": Infinity}", "{n: 1}", "[1] [2]", "{\"a\":1} // hi", "'single'", "", "[1,]")) {
            h.assertTrue(rejects(bad, 8), "strict parsing refuses " + bad);
        }
        h.assertTrue(rejects("[".repeat(9) + "]".repeat(9), 8), "nesting past the cap is refused");
        // PoC: Gson's lenient parser, which handlers used to get, takes all of these.
        h.assertTrue(JsonParser.parseString("{n: NaN}").getAsJsonObject().get("n").getAsDouble() != 0, "lenient Gson accepts NaN");
        // A hostile client's 8 KiB message nests 4096 deep; the scan refuses it without recursion.
        String deep = "[".repeat(4096) + "]".repeat(4096);
        h.assertTrue(rejects(deep, 255), "a deep message is refused");
        h.succeed();
    }

    private static boolean rejects(String json, int depth) {
        try {
            JsonLimits.parse(json, depth);
            return false;
        } catch (JsonParseException e) {
            return true;
        }
    }

    /** Messages a modified client can send: none of these reach a handler. */
    private static void messages(GameTestHelper h) {
        ServerPlayer player = VellumGameTests.player(h), other = VellumGameTests.player(h);
        VellumNetwork.intercept((p, payload) -> {});
        try {
            VellumSession session = VellumServer.open(player, "vellum:a.html", null);
            List<String> got = new ArrayList<>();
            session.onMessage("buy", (p, v) -> got.add(v.toString()));
            String deep = "[".repeat(4000) + "]".repeat(4000);
            for (String json : List.of(deep, "{\"count\": NaN}", "{count: 1}", "1 2", "{\"count\": 1e999999}x")) {
                VellumServer.handleMessage(player, new MessagePayload(session.id(), "buy", json));
            }
            VellumServer.handleMessage(other, new MessagePayload(session.id(), "buy", "\"spoofed\""));
            VellumServer.handleMessage(player, new MessagePayload(session.id() + 1000, "buy", "\"no session\""));
            VellumServer.handleMessage(player, new MessagePayload(-1, "buy", "\"negative\""));
            h.assertValueEqual(got, List.of(), "hostile messages are dropped");
            // A number too large for a double still parses: handlers must check ranges themselves.
            VellumServer.handleMessage(player, new MessagePayload(session.id(), "buy", "{\"count\": 1e999999}"));
            h.assertValueEqual(got.size(), 1, "a valid but extreme number reaches the handler");
            session.close();
            VellumServer.handleMessage(player, new MessagePayload(session.id(), "buy", "1"));
            h.assertValueEqual(got.size(), 1, "messages after close are dropped");
        } finally {
            VellumNetwork.intercept(null);
            VellumGameTests.leave(h, player);
            VellumGameTests.leave(h, other);
        }
        h.succeed();
    }

    /** Payload bytes a hostile peer can write: each fails to decode (a clean disconnect), none crashes. */
    private static void payloads(GameTestHelper h) {
        ByteBuf longHtml = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(longHtml, 1);
        ByteBufCodecs.stringUtf8(10).encode(longHtml, "");
        ByteBufCodecs.stringUtf8(OpenPayload.MAX_HTML + 10).encode(longHtml, "x".repeat(OpenPayload.MAX_HTML + 1));
        ByteBufCodecs.stringUtf8(10).encode(longHtml, "null");
        h.assertTrue(failsToDecode(() -> OpenPayload.STREAM_CODEC.decode(longHtml)), "inline HTML over the cap");

        ByteBuf truncated = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(truncated, 1);
        truncated.writeByte(100); // a string length with no bytes after it
        h.assertTrue(failsToDecode(() -> MessagePayload.STREAM_CODEC.decode(truncated)), "truncated message");

        ByteBuf hugeLength = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(hugeLength, 1);
        ByteBufCodecs.VAR_INT.encode(hugeLength, Integer.MAX_VALUE); // claims a 2 GiB channel name
        h.assertTrue(failsToDecode(() -> MessagePayload.STREAM_CODEC.decode(hugeLength)), "string length beyond the cap");

        ByteBuf badVarInt = Unpooled.buffer();
        for (int i = 0; i < 6; i++) badVarInt.writeByte(0xFF);
        h.assertTrue(failsToDecode(() -> MessagePayload.STREAM_CODEC.decode(badVarInt)), "a VarInt longer than 5 bytes");
        h.succeed();
    }

    private static boolean failsToDecode(Runnable decode) {
        try {
            decode.run();
            return false;
        } catch (RuntimeException e) { // DecoderException and others: the connection drops the peer, nothing else breaks
            return true;
        }
    }

    /** A client that never reports closed screens can't make the server keep sessions without bound. */
    private static void sessionCap(GameTestHelper h) {
        ServerPlayer player = VellumGameTests.player(h), other = VellumGameTests.player(h);
        VellumNetwork.intercept((p, payload) -> {});
        try {
            int max = VellumConfig.SERVER_MAX_SESSIONS.get();
            VellumSession theirs = VellumServer.open(other, "vellum:a.html", null);
            List<VellumSession> sessions = new ArrayList<>();
            int[] ended = {0};
            for (int i = 0; i < max + 5; i++) sessions.add(VellumServer.open(player, "vellum:a.html", null).onClose(() -> ended[0]++));
            h.assertValueEqual(sessions.stream().filter(VellumSession::isOpen).count(), (long) max, "open sessions are capped");
            h.assertValueEqual(ended[0], 5, "the oldest sessions were ended");
            h.assertTrue(sessions.getLast().isOpen() && !sessions.getFirst().isOpen(), "the newest stay open");
            h.assertTrue(theirs.isOpen(), "another player's sessions are untouched");
        } finally {
            VellumNetwork.intercept(null);
            VellumGameTests.leave(h, player);
            VellumGameTests.leave(h, other);
        }
        h.succeed();
    }

    /** Bad values fall back to defaults with a warning; good ones apply; limits.* keys make the engine's Limits. */
    private static void config(GameTestHelper h) {
        try {
            Properties p = new Properties();
            p.setProperty("server.messageBurst", "-5");
            p.setProperty("server.maxSessionsPerPlayer", "lots");
            p.setProperty("client.serverPages", "ASK");
            p.setProperty("client.maxSoundVolume", "NaN");
            p.setProperty("client.typingNotice", "yes");
            p.setProperty("server.maxMessageDepth", "12");
            p.setProperty("server.bogus", "1");
            p.setProperty("limits.bogus", "7");
            p.setProperty("limits.maxNodes", "42");
            p.setProperty("limits.maxDepth", "0");
            p.setProperty("limits.heapLimitPercent", "101");
            p.setProperty("limits.maxCanvasSize", "1e9");
            p.setProperty("limits.maxTimers", "99999999999");
            List<String> warnings = VellumConfig.apply(p);
            h.assertValueEqual(VellumConfig.SERVER_MESSAGE_BURST.get(), 40, "out of range falls back");
            h.assertValueEqual(VellumConfig.SERVER_MAX_SESSIONS.get(), 8, "not a number falls back");
            h.assertValueEqual(VellumConfig.CLIENT_SERVER_PAGES.get(), VellumConfig.ServerPages.ASK, "choices ignore case");
            h.assertValueEqual(VellumConfig.CLIENT_MAX_SOUND_VOLUME.get(), 1.0, "NaN falls back");
            h.assertValueEqual(VellumConfig.CLIENT_TYPING_NOTICE.get(), true, "only true/false are booleans");
            h.assertValueEqual(VellumConfig.SERVER_MAX_MESSAGE_DEPTH.get(), 12, "valid values apply");
            h.assertValueEqual(Limits.current(), Limits.DEFAULTS.with("maxNodes", 42), "valid limits apply, invalid ones keep their default");
            h.assertValueEqual(warnings.size(), 10, "one warning per bad value or unknown key: " + warnings);
            h.assertTrue(warnings.contains("limits.heapLimitPercent=101 must be at most 100; using 90"), "limit warnings say why: " + warnings);
        } finally {
            VellumConfig.load(); // back to the file's settings
        }
        h.succeed();
    }

    private static void bucket(GameTestHelper h) {
        TokenBucket b = new TokenBucket(3, 2);
        long t = 1_000_000_000L;
        int taken = 0;
        for (int i = 0; i < 10; i++) if (b.tryTake(t)) taken++;
        h.assertValueEqual(taken, 3, "the burst");
        h.assertFalse(b.tryTake(t + 400_000_000L), "0.8 tokens after 0.4 s");
        h.assertTrue(b.tryTake(t + 500_000_000L), "1 token after 0.5 s");
        h.assertFalse(b.tryTake(t - 1_000_000_000L), "a clock going backwards adds nothing");
        h.succeed();
    }
}
