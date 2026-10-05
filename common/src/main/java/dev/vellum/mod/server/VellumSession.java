package dev.vellum.mod.server;

import com.google.gson.JsonElement;
import dev.vellum.mod.Constants;
import dev.vellum.mod.net.ClosePayload;
import dev.vellum.mod.net.DataPayload;
import dev.vellum.mod.net.OpenPayload;
import dev.vellum.mod.net.VellumNetwork;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * One UI open on one player's screen, opened by {@link VellumServer}. Push data to it, listen for its
 * {@code vellum.send(channel, value)} messages, and close it. Use it on the server thread only.
 */
public final class VellumSession {
    /** Messages a page may send in a burst, and per second after that; extra messages are dropped. */
    private static final int BURST = 40, PER_SECOND = 20;

    private final int id;
    private final ServerPlayer player;
    private final Map<String, List<BiConsumer<ServerPlayer, JsonElement>>> handlers = new HashMap<>();
    private final List<Runnable> closeHandlers = new ArrayList<>();
    private boolean open = true;
    private double tokens = BURST;
    private long lastRefill = System.nanoTime();
    private boolean warnedRate;

    VellumSession(int id, ServerPlayer player) {
        this.id = id;
        this.player = player;
    }

    public int id() {
        return id;
    }

    /** The player the session was opened for (after a respawn, handlers receive the current player object). */
    public ServerPlayer player() {
        return player;
    }

    public boolean isOpen() {
        return open;
    }

    /** Replaces {@code vellum.data} on the client; the page's {@code vellum.on('data', fn)} listeners run. */
    public VellumSession push(JsonElement data) {
        if (open) VellumNetwork.sendToPlayer(player, new DataPayload(id, VellumServer.json(data, OpenPayload.MAX_DATA)));
        return this;
    }

    /** Runs {@code handler} for each {@code vellum.send(channel, value)} from the page. */
    public VellumSession onMessage(String channel, BiConsumer<ServerPlayer, JsonElement> handler) {
        handlers.computeIfAbsent(channel, c -> new ArrayList<>()).add(handler);
        return this;
    }

    /** Runs once when the session ends: the player closed the screen, left, or {@link #close()} was called. */
    public VellumSession onClose(Runnable handler) {
        if (open) closeHandlers.add(handler);
        else handler.run();
        return this;
    }

    /** Closes the player's screen (if it still shows this session) and ends the session. */
    public void close() {
        if (!open) return;
        VellumNetwork.sendToPlayer(player, new ClosePayload(id));
        VellumServer.end(this);
    }

    void dispatch(ServerPlayer sender, String channel, JsonElement value) {
        List<BiConsumer<ServerPlayer, JsonElement>> list = handlers.get(channel);
        if (list == null) {
            Constants.LOG.debug("Vellum session {}: no handler for channel '{}'", id, channel);
            return;
        }
        for (BiConsumer<ServerPlayer, JsonElement> h : List.copyOf(list)) {
            try {
                h.accept(sender, value);
            } catch (RuntimeException e) {
                Constants.LOG.error("Vellum session {}: handler for '{}' failed", id, channel, e);
            }
        }
    }

    /** Token bucket: false when the page sends faster than {@link #PER_SECOND} after a {@link #BURST}. */
    boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(BURST, tokens + (now - lastRefill) / 1e9 * PER_SECOND);
        lastRefill = now;
        if (tokens >= 1) {
            tokens--;
            return true;
        }
        if (!warnedRate) {
            warnedRate = true;
            Constants.LOG.warn("Vellum session {}: {} is sending too many messages; dropping some", id, player.getGameProfile().name());
        }
        return false;
    }

    void ended() {
        open = false;
        handlers.clear();
        for (Runnable r : closeHandlers) {
            try {
                r.run();
            } catch (RuntimeException e) {
                Constants.LOG.error("Vellum session {}: close handler failed", id, e);
            }
        }
        closeHandlers.clear();
    }
}
