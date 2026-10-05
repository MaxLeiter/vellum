package dev.vellum.mod.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import dev.vellum.mod.Constants;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.net.ClosedPayload;
import dev.vellum.mod.net.JsonLimits;
import dev.vellum.mod.net.MessagePayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.net.VellumNetwork;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server API: show a Vellum UI on a player's screen and talk to it.
 *
 * <pre>{@code
 * VellumServer.open(player, "mymod:vellum/shop.html", stock)
 *         .onMessage("buy", (p, item) -> buy(p, item.getAsString()))
 *         .onClose(() -> LOG.info("shop closed"));
 * }</pre>
 * The page is loaded on the client: from its resources for {@link #open} (the client needs the mod or resource pack
 * that ships it), or from the HTML the server sends for {@link #openInline}. Call these on the server thread.
 */
public final class VellumServer {
    private static final Gson GSON = new Gson();
    /** Open sessions in the order they were opened, so the oldest of a player's is found first. */
    private static final Map<Integer, VellumSession> SESSIONS = new LinkedHashMap<>();
    private static int nextId = 1;

    private VellumServer() {}

    /**
     * Opens the page at {@code url} ({@code namespace:path/page.html} under {@code assets/}) on the player's screen.
     * If the player's client lacks Vellum the returned session is already closed.
     *
     * @param data the initial {@code vellum.data}; may be null
     */
    public static VellumSession open(ServerPlayer player, String url, @Nullable JsonElement data) {
        if (url.length() > OpenPayload.MAX_URL) throw new IllegalArgumentException("Vellum URL too long: " + url);
        return open(player, url, "", data);
    }

    /** Opens a page sent by the server itself: HTML with inline {@code <style>} and {@code <script>}. */
    public static VellumSession openInline(ServerPlayer player, String html, @Nullable JsonElement data) {
        int max = VellumConfig.SERVER_MAX_INLINE_HTML.get();
        if (html.length() > max) {
            throw new IllegalArgumentException("Inline Vellum page is " + html.length() + " chars; the limit is " + max);
        }
        return open(player, "", html, data);
    }

    /** The open session with this id, if it belongs to {@code player}. */
    public static @Nullable VellumSession session(ServerPlayer player, int id) {
        VellumSession s = SESSIONS.get(id);
        return s != null && s.player().getUUID().equals(player.getUUID()) ? s : null;
    }

    private static VellumSession open(ServerPlayer player, String url, String html, @Nullable JsonElement data) {
        String json = json(data);
        endOldestBeyondCap(player);
        VellumSession session = new VellumSession(nextId++, player);
        SESSIONS.put(session.id(), session);
        if (!VellumNetwork.sendToPlayer(player, new OpenPayload(session.id(), url, html, json))) {
            Constants.LOG.debug("Vellum: {} cannot show {} (no Vellum on the client)", player.getGameProfile().name(), url);
            end(session);
        }
        return session;
    }

    // ---- Networking and lifecycle (called by VellumNetwork and the loaders) ----

    /**
     * A page called {@code vellum.send}. Rate-limited per session; messages over the configured size or nesting
     * depth, and anything but one strict JSON value, are dropped before a handler sees them.
     */
    public static void handleMessage(ServerPlayer sender, MessagePayload payload) {
        VellumSession session = session(sender, payload.session());
        if (session == null || !session.tryAcquire()) return;
        if (payload.json().length() > VellumConfig.SERVER_MAX_MESSAGE.get()) {
            Constants.LOG.debug("Vellum session {}: message on '{}' is too long", payload.session(), payload.channel());
            return;
        }
        JsonElement value;
        try {
            value = JsonLimits.parse(payload.json(), VellumConfig.SERVER_MAX_MESSAGE_DEPTH.get());
        } catch (JsonParseException e) {
            Constants.LOG.debug("Vellum session {}: malformed message on '{}': {}", payload.session(), payload.channel(), e.getMessage());
            return;
        }
        session.dispatch(sender, payload.channel(), value);
    }

    /** The player closed the session's screen. */
    public static void handleClosed(ServerPlayer sender, ClosedPayload payload) {
        VellumSession session = session(sender, payload.session());
        if (session != null) end(session);
    }

    /** Ends every session of a player who left. */
    public static void onPlayerLeft(ServerPlayer player) {
        for (VellumSession s : List.copyOf(SESSIONS.values())) {
            if (s.player().getUUID().equals(player.getUUID())) end(s);
        }
    }

    public static void onServerStopping() {
        for (VellumSession s : List.copyOf(SESSIONS.values())) end(s);
        nextId = 1;
    }

    /**
     * Ends the player's oldest sessions while they have the configured maximum open. A client closes a session when
     * another replaces its screen, but a hostile one might not, and each session holds its handlers.
     */
    private static void endOldestBeyondCap(ServerPlayer player) {
        List<VellumSession> own = new ArrayList<>();
        for (VellumSession s : SESSIONS.values()) if (s.player().getUUID().equals(player.getUUID())) own.add(s);
        int max = VellumConfig.SERVER_MAX_SESSIONS.get();
        for (int i = 0; i <= own.size() - max; i++) end(own.get(i));
    }

    static void end(VellumSession session) {
        SESSIONS.remove(session.id());
        if (session.isOpen()) session.ended();
    }

    /** Serialises data for a payload, failing early (with a useful message) when it is over the configured size. */
    static String json(@Nullable JsonElement value) {
        String s = GSON.toJson(value == null ? JsonNull.INSTANCE : value);
        int max = VellumConfig.SERVER_MAX_DATA.get();
        if (s.length() > max) throw new IllegalArgumentException("Vellum data is " + s.length() + " chars; the limit is " + max);
        return s;
    }
}
