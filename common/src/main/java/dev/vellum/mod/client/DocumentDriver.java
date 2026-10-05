package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.engine.style.Cursor;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.net.ClosedPayload;
import dev.vellum.mod.net.MessagePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Hosts one Vellum document in Minecraft, for whatever shows it ({@link VellumScreen}, {@link VellumContainerScreen}
 * or a HUD overlay): loads the page, keeps its viewport in sync with the GUI-scaled window, runs frame and paint,
 * forwards input (SDL to DOM), switches SDL text input on while a text field has focus, routes messages, and reloads.
 *
 * <p>The document is its own error boundary ({@link Document#error()}): once the engine fails, the page is replaced
 * by an {@link ErrorPanel} until it is reloaded. Render thread only.
 */
public final class DocumentDriver {
    /** What a driver needs from whatever shows it. */
    public interface Owner {
        /** {@code vellum.close()} from the page: close the screen, hide the overlay. */
        void closeDocument();

        /** The screen showing the document (it owns SDL text input), or null for HUD overlays, which take no input. */
        default @Nullable Screen screen() {
            return null;
        }
    }

    private static final Set<DocumentDriver> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    private final Owner owner;
    private final McHost host = new McHost(this);
    private final int session;
    private final Map<String, List<Consumer<JsonElement>>> listeners = new HashMap<>();
    private String url;
    private @Nullable String html;
    private @Nullable String data;
    private @Nullable Document document;
    private @Nullable ErrorPanel error;
    private int width, height;
    private Cursor cursor = Cursor.AUTO;
    private boolean textInput, closeRequested, closedByServer;
    /** A child screen (link confirmation) is up and will return to this one: survive the owner's removal. */
    private boolean suspended;
    private @Nullable String pendingNavigation;

    /**
     * @param url     the page ({@code ns:path/page.html}); also the base for its relative URLs
     * @param html    inline HTML to show instead of loading {@code url}, or null
     * @param session the server session this page belongs to, or -1 for a client-side page
     */
    public DocumentDriver(Owner owner, String url, @Nullable String html, int session) {
        this.owner = owner;
        this.url = url;
        this.html = html;
        this.session = session;
    }

    /** Reloads every loaded document: after a resource reload, {@code /vellum reload}, or a saved source file in dev. */
    public static void reloadAll() {
        for (DocumentDriver d : List.copyOf(LIVE)) d.reload();
    }

    // ---- Lifecycle ----

    /** Sets the viewport (GUI-scaled size); loads the page on first use. */
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        if (document == null && error == null) load();
        else applyViewport(document);
    }

    public void reload() {
        load();
    }

    /** Disposes the document. Tells the server when a session's screen is closed by the player. */
    public void close() {
        if (suspended) {
            suspended = false;
            setTextInput(false);
            return;
        }
        LIVE.remove(this);
        setTextInput(false);
        disposeDocument();
        if (session >= 0 && !closedByServer) VellumClient.sendToServer(new ClosedPayload(session));
    }

    /** The server ended this session; {@link #close()} then won't report it back. */
    void closedByServer() {
        closedByServer = true;
    }

    private void load() {
        disposeDocument();
        LIVE.add(this);
        error = null;
        String source = html != null ? html : VellumResources.loadText(url);
        if (source == null) {
            Constants.LOG.error("Vellum: page not found: {}", url);
            fail("Page not found: " + url, null);
            return;
        }
        Document doc = Document.parse(host, url, source, data);
        document = doc;
        doc.guard(() -> {
            McText.expand(doc);
            return true;
        });
        if (width > 0) applyViewport(doc);
    }

    private void applyViewport(@Nullable Document doc) {
        if (doc != null) doc.setViewport(width, height, Minecraft.getInstance().getWindow().getGuiScale());
    }

    private void disposeDocument() {
        Document doc = document;
        document = null;
        if (doc != null) doc.close();
    }

    // ---- Rendering ----

    /** Runs a frame and paints it (or the error panel). {@code mouseX} is -1 when there is no pointer (HUDs). */
    public void extract(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        runDeferred();
        Document doc = document();
        if (doc != null) {
            doc.frame(Util.getMillis());
            McCanvas canvas = new McCanvas(g, mouseX, mouseY);
            doc.paint(canvas);
            canvas.finish();
            syncTextInput();
            if (cursor != Cursor.AUTO && cursor != Cursor.DEFAULT) g.requestCursor(cursorType(cursor));
        }
        if (error == null && document != null && document.error() != null) {
            fail("Vellum could not show " + name(), document.error());
        }
        if (error != null) error.extract(g, width, height, owner.screen() != null);
    }

    /** The live document, or null while it failed or is not loaded. */
    public @Nullable Document document() {
        return error == null && document != null && document.error() == null ? document : null;
    }

    /** The element under a viewport point (hit testing mirrors paint), or null. */
    public @Nullable Element elementAt(double x, double y) {
        Document doc = document();
        HitResult hit = doc == null ? null : doc.hitTest((float) x, (float) y);
        return hit == null ? null : hit.element();
    }

    // ---- Input (true = consumed) ----

    public boolean mouseMoved(double x, double y) {
        return input(in -> in.mouseMove((float) x, (float) y, KeyNames.current()));
    }

    public boolean mouseClicked(MouseButtonEvent e) {
        return input(in -> in.mouseDown((float) e.x(), (float) e.y(), KeyNames.button(e.button()), KeyNames.modifiers(e.modifiers())));
    }

    public boolean mouseReleased(MouseButtonEvent e) {
        return input(in -> in.mouseUp((float) e.x(), (float) e.y(), KeyNames.button(e.button()), KeyNames.modifiers(e.modifiers())));
    }

    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return input(in -> in.wheel((float) x, (float) y, (float) -scrollX * InputHandler.WHEEL_NOTCH,
                (float) -scrollY * InputHandler.WHEEL_NOTCH, KeyNames.current()));
    }

    /**
     * Key down. Also true for any key but Escape while a text field has focus, so screen shortcuts (the inventory
     * key, hotbar swaps) don't fire while typing.
     */
    public boolean keyPressed(KeyEvent e) {
        String key = KeyNames.key(e.key(), e.keycode(), e.hasShiftDown());
        return input(in -> in.keyDown(key, KeyNames.code(e.key()), KeyNames.modifiers(e.modifiers()))
                || (in.wantsKeyboard() && !e.isEscape()));
    }

    public boolean keyReleased(KeyEvent e) {
        String key = KeyNames.key(e.key(), e.keycode(), e.hasShiftDown());
        return input(in -> in.keyUp(key, KeyNames.code(e.key()), KeyNames.modifiers(e.modifiers())));
    }

    public boolean charTyped(CharacterEvent e) {
        return input(in -> in.charTyped(e.codepointAsString()));
    }

    private boolean input(Predicate<InputHandler> action) {
        Document doc = document();
        if (doc == null) return false;
        boolean consumed = action.test(doc.input());
        syncTextInput();
        return consumed;
    }

    // ---- Messages ----

    /** Replaces {@code vellum.data}; the page's {@code vellum.on('data', fn)} listeners run. Kept across reloads. */
    public void push(JsonElement data) {
        pushData(data.toString());
    }

    void pushData(String json) {
        data = json;
        receive("data", json);
    }

    /** Delivers a message to the page's {@code vellum.on(channel, fn)} listeners. */
    public void receive(String channel, String json) {
        Document doc = document();
        if (doc != null) doc.receive(channel, json);
    }

    /** Handles {@code vellum.send(channel, value)} on the client, alongside (or instead of) a server session. */
    public DocumentDriver onMessage(String channel, Consumer<JsonElement> handler) {
        listeners.computeIfAbsent(channel, c -> new ArrayList<>()).add(handler);
        return this;
    }

    /** From the page: client listeners first, then the server session if there is one. */
    void send(String channel, String json) {
        List<Consumer<JsonElement>> local = listeners.get(channel);
        if (local != null) {
            try {
                JsonElement value = JsonParser.parseString(json);
                for (Consumer<JsonElement> l : List.copyOf(local)) l.accept(value);
            } catch (JsonParseException | IllegalStateException e) {
                Constants.LOG.warn("Vellum: {} sent malformed JSON on '{}'", name(), channel);
            }
        }
        if (session < 0) return;
        if (channel.length() > MessagePayload.MAX_CHANNEL || json.length() > MessagePayload.MAX_JSON) {
            Constants.LOG.warn("Vellum: {} message on '{}' is too large to send ({} chars)", name(), channel, json.length());
            return;
        }
        VellumClient.sendToServer(new MessagePayload(session, channel, json));
    }

    int session() {
        return session;
    }

    String name() {
        return html != null ? "inline page" + (session >= 0 ? " (session " + session + ")" : "") : url;
    }

    // ---- Requests from the page, run between frames (never inside the engine's own dispatch) ----

    void requestClose() {
        closeRequested = true;
    }

    /** {@code <a href>}: another .html page replaces this one; web links ask first, as chat links do. */
    void navigate(String target) {
        pendingNavigation = target;
    }

    private void runDeferred() {
        String target = pendingNavigation;
        pendingNavigation = null;
        if (target != null) {
            Screen screen = owner.screen();
            if (target.startsWith("https://") || target.startsWith("http://")) {
                if (screen != null) {
                    try {
                        URI uri = new URI(target);
                        suspended = true;
                        ConfirmLinkScreen.confirmLinkNow(screen, uri);
                    } catch (URISyntaxException e) {
                        Constants.LOG.warn("Vellum: {} links to a malformed URL: {}", name(), target);
                    }
                }
            } else if (target.split("[?#]", 2)[0].endsWith(".html")) {
                url = target;
                html = null;
                load();
            }
        }
        if (closeRequested) {
            closeRequested = false;
            owner.closeDocument();
        }
    }

    void setCursor(Cursor cursor) {
        this.cursor = cursor;
    }

    // ---- Internals ----

    /** Shows {@code error} instead of the page (the document already logged it through its host). */
    private void fail(String title, @Nullable Throwable e) {
        if (error != null) return;
        error = ErrorPanel.of(title, e);
        setTextInput(false);
    }

    private void syncTextInput() {
        Document doc = document();
        setTextInput(doc != null && doc.input().wantsKeyboard());
    }

    private void setTextInput(boolean on) {
        Screen listener = owner.screen();
        if (listener == null || on == textInput) return;
        textInput = on;
        Minecraft.getInstance().onTextInputFocusChange(listener, on);
    }

    private static CursorType cursorType(Cursor cursor) {
        return switch (cursor) {
            case POINTER, GRAB, GRABBING -> CursorTypes.POINTING_HAND;
            case TEXT -> CursorTypes.IBEAM;
            case EW_RESIZE -> CursorTypes.RESIZE_EW;
            case NS_RESIZE -> CursorTypes.RESIZE_NS;
            case MOVE -> CursorTypes.RESIZE_ALL;
            case NOT_ALLOWED -> CursorTypes.NOT_ALLOWED;
            case CROSSHAIR -> CursorTypes.CROSSHAIR;
            default -> CursorTypes.ARROW;
        };
    }
}
