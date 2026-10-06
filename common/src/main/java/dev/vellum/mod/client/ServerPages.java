package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.TokenBucket;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.net.ClosePayload;
import dev.vellum.mod.net.ClosedPayload;
import dev.vellum.mod.net.DataPayload;
import dev.vellum.mod.net.JsonLimits;
import dev.vellum.mod.net.OpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * What a client lets a server do with pages. Every server page goes through here:
 * <ul>
 *   <li>{@code client.serverPages}: allow, ask once per server visit, or block;</li>
 *   <li>size and nesting caps on the page and its data ({@code client.maxInlineHtmlChars}, {@code client.maxDataChars},
 *       {@code client.maxDataDepth});</li>
 *   <li>a page waits while the player is in another kind of screen (chat, the pause menu, options, a sign, a
 *       confirmation), so a server cannot take keystrokes meant for chat or keep the player out of the pause menu;
 *       container screens and other Vellum screens are replaced, as vanilla replaces them with a container;</li>
 *   <li>a rate limit on opens ({@code client.openBurst}, {@code client.opensPerSecond}); a waiting page is replaced
 *       by a newer one;</li>
 *   <li>a cap on reopen loops: a server that opens a page within a second of the player closing one with Escape,
 *       {@code client.reopenStrikes} times in a row within 15 seconds, can't open pages for
 *       {@code client.reopenBlockSeconds}.</li>
 * </ul>
 * A page that is refused, replaced while waiting or blocked is reported closed, so the server's session ends.
 * Render thread only; the state is per connection.
 */
final class ServerPages {
    /**
     * An open this soon after the player closed a server page with Escape is a reopen. A server loop reopens within a
     * tick or two plus the round trip; a player who closes a shop and clicks its keeper again takes longer.
     */
    private static final long REOPEN_MS = 1000;
    private static final long STRIKE_WINDOW_MS = 15_000;

    private static @Nullable ClientPacketListener connection;
    private static @Nullable OpenPayload pending;
    private static @Nullable String pendingData;
    /** Whether the player allowed pages from this server (ask mode), or null until asked. */
    private static @Nullable Boolean allowed;
    private static boolean asking, replacing;
    private static TokenBucket opens = newOpenBucket();
    private static long lastPlayerClose = Long.MIN_VALUE / 2, blockedUntil;
    private static final ArrayDeque<Long> strikes = new ArrayDeque<>();
    private static final Set<String> refusalsLogged = new HashSet<>();

    private ServerPages() {}

    // ---- Payloads ----

    static void open(OpenPayload p) {
        sameConnection();
        long now = now();
        String refused = refusal(p, now);
        if (refused != null) {
            // Once per reason and visit: a hostile server answering each refusal with a new page would flood the log.
            if (refusalsLogged.add(refused)) Constants.LOG.info("Vellum: not showing the server's pages: {}", refused);
            refuse(p.session());
            return;
        }
        if (now - lastPlayerClose >= REOPEN_MS) strikes.clear(); // only reopens in a row count
        else if (strike(now)) {
            refuse(p.session());
            return;
        }
        if (pending != null) refuse(pending.session());
        pending = p;
        pendingData = p.data();
        tryShow();
    }

    static void data(DataPayload p) {
        if (!dataFits(p.data())) {
            Constants.LOG.warn("Vellum: dropped data for session {}: over client.maxDataChars or client.maxDataDepth", p.session());
            return;
        }
        if (pending != null && pending.session() == p.session()) {
            pendingData = p.data();
            return;
        }
        VellumScreen screen = sessionScreen(p.session());
        if (screen != null) screen.driver().pushData(p.data());
    }

    static void close(ClosePayload p) {
        if (pending != null && pending.session() == p.session()) {
            pending = null;
            return;
        }
        VellumScreen screen = sessionScreen(p.session());
        if (screen != null) {
            screen.driver().closedByServer();
            screen.onClose();
        }
    }

    /** A server page closed without the server asking; {@code byEscape} when the player pressed Escape to close it. */
    static void closed(boolean byEscape) {
        if (byEscape && !replacing) lastPlayerClose = now();
    }

    /** Whether this server is stopped from opening pages for reopening them (for the dev autopilot). */
    static boolean blocked() {
        return now() < blockedUntil;
    }

    /** Every client tick: shows a waiting page once the player's screen and the rate limit allow it. */
    static void tick() {
        sameConnection();
        if (pending != null) tryShow();
    }

    // ---- Internals ----

    private static @Nullable String refusal(OpenPayload p, long now) {
        if (VellumConfig.CLIENT_SERVER_PAGES.get() == VellumConfig.ServerPages.BLOCK) return "server pages are blocked (client.serverPages)";
        if (Boolean.FALSE.equals(allowed)) return "the player declined pages from this server";
        if (now < blockedUntil) return "the server kept reopening pages";
        if (p.html().length() > VellumConfig.CLIENT_MAX_INLINE_HTML.get()) return "the page is over client.maxInlineHtmlChars";
        if (!dataFits(p.data())) return "its data is over client.maxDataChars or client.maxDataDepth";
        return null;
    }

    private static boolean dataFits(String json) {
        return json.length() <= VellumConfig.CLIENT_MAX_DATA.get() && JsonLimits.depth(json) <= VellumConfig.CLIENT_MAX_DATA_DEPTH.get();
    }

    /** Counts a reopen; true (and the server is blocked for a while) when there were too many lately. */
    private static boolean strike(long now) {
        while (!strikes.isEmpty() && now - strikes.peekFirst() > STRIKE_WINDOW_MS) strikes.pollFirst();
        strikes.addLast(now);
        if (strikes.size() < VellumConfig.CLIENT_REOPEN_STRIKES.get()) return false;
        strikes.clear();
        int seconds = VellumConfig.CLIENT_REOPEN_BLOCK_SECONDS.get();
        blockedUntil = now + seconds * 1000L;
        if (pending != null) {
            refuse(pending.session());
            pending = null;
        }
        Constants.LOG.warn("Vellum: the server reopened pages as they were closed; blocking its pages for {} s", seconds);
        McClient.systemMessage(Component.translatable("vellum.serverPages.blocked", seconds));
        return true;
    }

    private static void tryShow() {
        OpenPayload p = pending;
        if (p == null || asking) return;
        Minecraft mc = Minecraft.getInstance();
        if (!replaceable(McClient.screen())) return;
        if (VellumConfig.CLIENT_SERVER_PAGES.get() == VellumConfig.ServerPages.ASK && allowed == null) {
            ask(mc);
            return;
        }
        if (Boolean.FALSE.equals(allowed) || VellumConfig.CLIENT_SERVER_PAGES.get() == VellumConfig.ServerPages.BLOCK) {
            refuse(p.session());
            pending = null;
            return;
        }
        if (!opens.tryTake()) return;
        pending = null;
        VellumScreen screen = new VellumScreen(p.html().isEmpty() ? p.url() : "", p.html().isEmpty() ? null : p.html(), p.session());
        screen.driver().pushData(pendingData);
        pendingData = null;
        // The screen this replaces reports its session closed; that is not the player closing it.
        replacing = true;
        try {
            McClient.setScreen(screen);
        } finally {
            replacing = false;
        }
    }

    /** Screens a server page may replace: none, a container screen, a Vellum screen. Others make it wait. */
    private static boolean replaceable(@Nullable Screen screen) {
        return screen == null || screen instanceof VellumScreen || screen instanceof AbstractContainerScreen<?>;
    }

    /** Asks once per server visit; the question replaces the screen, as the page would have. */
    private static void ask(Minecraft mc) {
        asking = true;
        McClient.setScreen(new ConfirmScreen(yes -> {
            asking = false;
            allowed = yes;
            McClient.setScreen(null);
            if (!yes && pending != null) {
                refuse(pending.session());
                pending = null;
            }
            tryShow();
        }, Component.translatable("vellum.serverPages.ask.title"), Component.translatable("vellum.serverPages.ask.message"),
                Component.translatable("vellum.serverPages.ask.allow"), Component.translatable("vellum.serverPages.ask.deny")));
    }

    private static void refuse(int session) {
        VellumClient.sendToServer(new ClosedPayload(session));
    }

    /** Starts over when the client joined another server (or the same one again). */
    private static void sameConnection() {
        ClientPacketListener now = Minecraft.getInstance().getConnection();
        if (now == connection) return;
        connection = now;
        pending = null;
        pendingData = null;
        allowed = null;
        asking = false;
        opens = newOpenBucket();
        strikes.clear();
        refusalsLogged.clear();
        blockedUntil = 0;
        lastPlayerClose = Long.MIN_VALUE / 2;
    }

    private static TokenBucket newOpenBucket() {
        return new TokenBucket(VellumConfig.CLIENT_OPEN_BURST.get(), VellumConfig.CLIENT_OPENS_PER_SECOND.get());
    }

    private static @Nullable VellumScreen sessionScreen(int session) {
        return McClient.screen() instanceof VellumScreen s && s.driver().session() == session ? s : null;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }
}
