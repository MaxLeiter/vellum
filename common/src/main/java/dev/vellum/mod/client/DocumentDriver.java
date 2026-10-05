package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Urls;
import dev.vellum.engine.dom.Viewport;
import dev.vellum.engine.input.InputHandler;
import dev.vellum.engine.input.Tooltip;
import dev.vellum.engine.paint.HitResult;
import dev.vellum.engine.style.Cursor;
import dev.vellum.mod.Constants;
import dev.vellum.mod.TokenBucket;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.replaced.McReplaced;
import dev.vellum.mod.net.ClosedPayload;
import dev.vellum.mod.net.MessagePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Hosts one Vellum document in Minecraft, for whatever shows it ({@link VellumScreen}, {@link VellumContainerScreen}
 * or a HUD overlay): loads the page, keeps its viewport in sync with the GUI-scaled window, runs frame and paint,
 * shows tooltips, forwards input (SDL to DOM; keys the page leaves alone go on to the mod's {@link #onKey} handlers),
 * switches SDL text input on while a text field has focus, routes messages, and reloads.
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

        /** Where painted {@code <slot>} elements are reported (container screens), or null. */
        default McCanvas.@Nullable SlotSink slots() {
            return null;
        }

        /** Every frame, once the document's frame has run (its layout is current) and before it paints. */
        default void beforePaint(Document document) {}

        /**
         * What the narrator says for the screen showing the document now, all of it, as vanilla puts a screen's
         * narration together ({@link VellumAutomation#narration}); null for owners that are not screens.
         */
        default @Nullable String narration() {
            return null;
        }
    }

    private static final Set<DocumentDriver> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
    /** Title tooltips wrap at this width, as vanilla widget tooltips do ({@code Tooltip.splitTooltip}). */
    private static final int TOOLTIP_WIDTH = 170;
    /** Escapes the page keeps within this time count towards {@code client.forceClosePresses}. */
    private static final long FORCE_CLOSE_WINDOW_MS = 1500;
    /** A web link opens only this soon after the player clicked or pressed a key in the page. */
    private static final long USER_ACTIVATION_MS = 1000;
    /** A close this soon after an Escape press was the player's (ServerPages counts reopen loops by it). */
    private static final long ESCAPE_CLOSE_MS = 500;
    /** {@code client.forceCloseKey} as last read, and the key it names. */
    private static @Nullable String forceCloseName;
    private static InputConstants.@Nullable Key forceCloseKey;

    private final Owner owner;
    private final McHost host = new McHost(this);
    private final PageNarrator narrator = new PageNarrator(this);
    private final int session;
    private final Map<String, List<Consumer<JsonElement>>> listeners = new HashMap<>();
    private final List<Runnable> closeHandlers = new ArrayList<>();
    private final List<Predicate<KeyEvent>> keyHandlers = new ArrayList<>();
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
    /** When the player last clicked or pressed a key in the page, and pressed Escape (Util.getMillis). */
    private long lastUserInput = Long.MIN_VALUE / 2, lastEscape = Long.MIN_VALUE / 2;
    /** Times of recent Escape presses the page kept (consumed), newest last. */
    private final ArrayDeque<Long> keptEscapes = new ArrayDeque<>();
    /** The page's messages ({@link #send}), limited per driver so reloading the page doesn't reset the limit. */
    private final TokenBucket sendRate = new TokenBucket(VellumConfig.CLIENT_MESSAGE_BURST.get(), VellumConfig.CLIENT_MESSAGES_PER_SECOND.get());
    private boolean warnedSendRate, warnedLink;
    /** Whether the last frame asked vanilla for a tooltip (the page's, an item's or a title). */
    private boolean tooltipRequested;
    /**
     * The last title's {@code title-json} and {@code title} (both null before any: a title has at least one), the text
     * made of them, and its lines split at newlines and wrapped, each made when first asked for. So JSON is parsed and
     * lines are split once, not every frame.
     */
    private @Nullable String lastJson, lastText;
    private @Nullable Component title;
    private @Nullable List<Component> titleLines;
    private @Nullable List<FormattedCharSequence> wrappedLines;

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

    /** The driver showing its page in {@code screen}, or null. */
    static @Nullable DocumentDriver of(Screen screen) {
        for (DocumentDriver d : LIVE) if (d.owner.screen() == screen) return d;
        return null;
    }

    /** Reloads every loaded document: after a resource reload, {@code /vellum reload}, or a saved source file in dev. */
    public static void reloadAll() {
        for (DocumentDriver d : List.copyOf(LIVE)) d.reload();
    }

    /** The loaded drivers showing page {@code url} (queries and fragments ignored); never inline pages. */
    static List<DocumentDriver> showing(String url) {
        String page = Urls.withoutQuery(url);
        List<DocumentDriver> drivers = new ArrayList<>();
        for (DocumentDriver d : LIVE) if (d.html == null && Urls.withoutQuery(d.url).equals(page)) drivers.add(d);
        return drivers;
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

    /**
     * Disposes the document (its {@code pagehide} and {@code unload} listeners run first), then runs the
     * {@link #onClose} handlers. Tells the server when a session's screen is closed by the player.
     */
    public void close() {
        if (suspended) {
            suspended = false;
            setTextInput(false);
            return;
        }
        LIVE.remove(this);
        setTextInput(false);
        disposeDocument();
        List<Runnable> handlers = List.copyOf(closeHandlers);
        closeHandlers.clear();
        for (Runnable handler : handlers) {
            try {
                handler.run();
            } catch (RuntimeException e) {
                Constants.LOG.error("Vellum: an onClose handler of {} failed", name(), e);
            }
        }
        if (session >= 0 && !closedByServer) {
            VellumClient.sendToServer(new ClosedPayload(session));
            ServerPages.closed(Util.getMillis() - lastEscape < ESCAPE_CLOSE_MS);
        }
    }

    /**
     * Runs {@code handler} once, when the page closes for good: its screen is closed or replaced by another screen,
     * or its HUD overlay is hidden. Not when the page navigates to another page or reloads, or while a link
     * confirmation screen is open over it. The page's {@code pagehide} and {@code unload} listeners have run by then,
     * so messages they send arrive first.
     */
    public DocumentDriver onClose(Runnable handler) {
        closeHandlers.add(handler);
        return this;
    }

    /** The server ended this session; {@link #close()} then won't report it back. */
    void closedByServer() {
        closedByServer = true;
    }

    private void load() {
        disposeDocument();
        LIVE.add(this);
        error = null;
        lastJson = lastText = null; // translations may have changed
        if (html == null) VellumScreens.pageLoading(url, this);
        String source = html != null ? html : VellumResources.loadText(url);
        if (source == null) {
            Constants.LOG.error("Vellum: page not found: {}", url);
            fail("Page not found: " + url, null);
            return;
        }
        document = Document.parse(host, url, source, data, viewport());
    }

    private void applyViewport(@Nullable Document doc) {
        Viewport v = viewport();
        if (doc != null) doc.setViewport(v.width(), v.height(), v.devicePixelRatio());
    }

    /** The GUI-scaled window, which pages are laid out in. */
    private Viewport viewport() {
        // A minimised or mid-resize window can report 0x0; a page laid out at zero size has nothing to show anyway.
        return new Viewport(Math.max(1, width), Math.max(1, height), Minecraft.getInstance().getWindow().getGuiScale());
    }

    private void disposeDocument() {
        Document doc = document;
        document = null;
        if (doc != null) doc.close();
    }

    // ---- Rendering ----

    /**
     * Runs a frame and paints it (or the error panel), then shows the page's {@code title} tooltip if one is up.
     * {@code mouseX} and {@code mouseY} are the pointer the frame is rendered with, which the page follows
     * ({@link #followPointer}); both are -1 when there is no pointer (a HUD overlay in the HUD layer).
     */
    public void extract(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        extractPage(g, mouseX, mouseY);
        extractTooltip(g, mouseX, mouseY);
    }

    /** {@link #extract} without the title tooltip, for owners that draw over the page and show it later. */
    void extractPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        runDeferred();
        // (-1, -1) is no pointer; other negative positions are off the window's top or left (VellumAutomation.leave).
        if (mouseX != -1 || mouseY != -1) followPointer(mouseX, mouseY);
        tooltipRequested = false;
        Document doc = document();
        if (doc != null) {
            doc.frame(Util.getMillis());
            owner.beforePaint(doc);
            McCanvas canvas = new McCanvas(g, mouseX, mouseY, owner.slots());
            doc.paint(canvas);
            canvas.finish();
            narrator.tick(owner.screen() == null && (mouseX != -1 || mouseY != -1));
            syncTextInput();
            if (cursor != Cursor.AUTO && cursor != Cursor.DEFAULT) g.requestCursor(cursorType(cursor));
        }
        if (error == null && document != null && document.error() != null) {
            fail("Vellum could not show " + name(), document.error());
        }
        if (error != null) error.extract(g, width, height, owner.screen() != null);
        extractTypingNotice(g);
    }

    /**
     * While the player types into a page a server opened: a line at the bottom of the screen, drawn above the page,
     * which the page can neither cover nor detect. Pages can look like any screen, a login form included.
     */
    private void extractTypingNotice(GuiGraphicsExtractor g) {
        if (!serverSession() || !textInput || owner.screen() == null || !VellumConfig.CLIENT_TYPING_NOTICE.get()) return;
        Font font = Minecraft.getInstance().font;
        Component text = Component.translatable("vellum.serverPages.typing");
        int y = height - 17;
        g.nextStratum();
        g.fill(4, y, 4 + font.width(text) + 8, y + 13, 0xE0101010);
        g.text(font, text, 8, y + 3, 0xFFFFD866, false);
    }

    /**
     * Shows the tooltip the page has up ({@link InputHandler#tooltip()}) at the page's pointer; none when
     * {@code mouseX} is -1 (no pointer). On an {@code <item tooltip>} it is the item's vanilla tooltip with the lines
     * of the title that applies after the item's own, unwrapped. Otherwise it is the {@code title} /
     * {@code title-json} tooltip, wrapped like vanilla widget tooltips unless the element has {@code title-nowrap}.
     * Vanilla draws it on top at the end of the frame, and only if nothing set a tooltip before it (a container
     * slot's item, which its screen sets first).
     */
    void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Document doc = document();
        Tooltip tooltip = doc == null || mouseX < 0 ? null : doc.input().tooltip();
        if (tooltip == null) return;
        int x = (int) tooltip.x(), y = (int) tooltip.y();
        if (tooltip.content() != null) {
            List<Component> extra = tooltip.element() == null ? List.of() : titleLines(tooltip);
            if (tooltip.content().replaced instanceof McReplaced content && content.showTooltip(g, extra, x, y)) {
                tooltipRequested = true;
            }
            return;
        }
        Font font = Minecraft.getInstance().font;
        if (tooltip.wrap()) {
            List<FormattedCharSequence> lines = wrappedLines(tooltip);
            if (lines.isEmpty()) return;
            g.setTooltipForNextFrame(font, lines, x, y);
        } else {
            List<Component> lines = titleLines(tooltip);
            if (lines.isEmpty()) return;
            g.setComponentTooltipForNextFrame(font, lines, x, y);
        }
        tooltipRequested = true;
    }

    /** Whether the last {@link #extract} asked vanilla for a tooltip. */
    boolean requestedTooltip() {
        return tooltipRequested;
    }

    /** The title's lines, split at its newlines only: after an item's lines, or alone with {@code title-nowrap}. */
    private List<Component> titleLines(Tooltip tooltip) {
        Component text = title(tooltip);
        if (titleLines == null) titleLines = text == null ? List.of() : McText.lines(text);
        return titleLines;
    }

    /** The title's lines wrapped at {@link #TOOLTIP_WIDTH}, as vanilla wraps widget tooltips. */
    private List<FormattedCharSequence> wrappedLines(Tooltip tooltip) {
        Component text = title(tooltip);
        if (wrappedLines == null) wrappedLines = text == null ? List.of() : Minecraft.getInstance().font.split(text, TOOLTIP_WIDTH);
        return wrappedLines;
    }

    /**
     * The title as text: its {@code title-json}, else its {@code title}; null when neither makes any. Made again only
     * when the attributes change; the same strings, frame after frame, compare by identity.
     */
    private @Nullable Component title(Tooltip tooltip) {
        if (!Objects.equals(tooltip.json(), lastJson) || !Objects.equals(tooltip.text(), lastText)) {
            lastJson = tooltip.json();
            lastText = tooltip.text();
            Component json = lastJson != null ? McText.component(lastJson) : null;
            title = json == null && lastText != null ? Component.literal(lastText) : json;
            titleLines = null;
            wrappedLines = null;
        }
        return title;
    }

    /**
     * Whether the page has stopped changing by itself ({@link Document#settled}). False before it has loaded (a HUD
     * overlay loads at its first draw) and while a navigation or {@code vellum.close()} waits for the next frame;
     * true while the error panel shows.
     */
    boolean settled() {
        if (error != null) return true;
        return document != null && pendingNavigation == null && !closeRequested && document.settled();
    }

    Owner owner() {
        return owner;
    }

    /** What the page gives Minecraft's narrator: the screen's title, the element it reads, live regions. */
    PageNarrator narrator() {
        return narrator;
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

    /**
     * The element under a viewport point unless only the page's background is there ({@code html} or {@code body},
     * whatever their styles), or null: where pages that share the screen let the pointer through.
     */
    public @Nullable Element contentAt(double x, double y) {
        Element hit = elementAt(x, y);
        return hit == null || isBackground(hit) ? null : hit;
    }

    static boolean isBackground(Element element) {
        return element.tagName().equals("html") || element.tagName().equals("body");
    }

    // ---- Input (true = consumed) ----

    public boolean mouseMoved(double x, double y) {
        return input(in -> in.mouseMove((float) x, (float) y, KeyNames.current()));
    }

    public boolean mouseClicked(MouseButtonEvent e) {
        lastUserInput = Util.getMillis();
        return input(in -> in.mouseDown((float) e.x(), (float) e.y(), KeyNames.button(e.button()), KeyNames.modifiers(e.modifiers())));
    }

    public boolean mouseReleased(MouseButtonEvent e) {
        return input(in -> in.mouseUp((float) e.x(), (float) e.y(), KeyNames.button(e.button()), KeyNames.modifiers(e.modifiers())));
    }

    /** The pointer left the page (an interactive HUD overlay whose screen closed): hover ends. */
    void mouseLeave() {
        Document doc = document();
        if (doc != null) doc.input().mouseLeave();
    }

    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return input(in -> in.wheel((float) x, (float) y, (float) -scrollX * InputHandler.WHEEL_NOTCH,
                (float) -scrollY * InputHandler.WHEEL_NOTCH, KeyNames.current()));
    }

    /**
     * Every frame the page is rendered with a pointer. Minecraft tells a screen where the pointer is only when it
     * moves, and drops the first move after a screen opens, so a page opened under a resting cursor would hover
     * nothing until the mouse moved, and HUD overlays get no moves from it at all. Vanilla widgets read the render's
     * pointer instead, and so does this: when the page's pointer ({@link InputHandler#pointer}) is not where the
     * frame's is, the page gets a move there. The frame's pointer is the mouse handler's exact one, which the
     * render's is truncated from, unless the render was given another. Code that moves the pointer must move the
     * mouse handler's too ({@link VellumAutomation} does), or the next frame moves it back.
     */
    private void followPointer(int mouseX, int mouseY) {
        Document doc = document();
        if (doc == null) return;
        Minecraft mc = Minecraft.getInstance();
        double x = mc.mouseHandler.getScaledXPos(mc.getWindow()), y = mc.mouseHandler.getScaledYPos(mc.getWindow());
        if ((int) x != mouseX || (int) y != mouseY) {
            x = mouseX;
            y = mouseY;
        }
        float[] at = doc.input().pointer();
        if (at == null || at[0] != (float) x || at[1] != (float) y) mouseMoved(x, y);
    }

    /**
     * Key down: the page first, then the {@link #onKey} handlers with a key it left alone. Also true for any key but
     * Escape while a text field has focus, so screen shortcuts (the inventory key, hotbar swaps) and the handlers
     * don't fire while typing.
     */
    public boolean keyPressed(KeyEvent e) {
        long now = Util.getMillis();
        if (forceCloseKey(e)) {
            lastEscape = now;
            owner.closeDocument();
            return true;
        }
        lastUserInput = now;
        if (e.isEscape()) lastEscape = now;
        String key = KeyNames.key(e.key(), e.keycode(), e.hasShiftDown());
        boolean used = input(in -> in.keyDown(key, KeyNames.code(e.key()), KeyNames.modifiers(e.modifiers()))
                || (in.wantsKeyboard() && !e.isEscape())) || keyHandled(e);
        if (used && e.isEscape() && keptTooManyEscapes(now)) {
            owner.closeDocument();
            return true;
        }
        return used;
    }

    /**
     * Shift and {@code client.forceCloseKey} (Escape by default): closes the page's screen, whatever the page does.
     * The page never sees the key.
     */
    private boolean forceCloseKey(KeyEvent e) {
        if (owner.screen() == null || !e.hasShiftDown()) return false;
        InputConstants.Key key = forceCloseKey();
        return key != null && e.key() == key.getValue();
    }

    private static InputConstants.@Nullable Key forceCloseKey() {
        String name = VellumConfig.CLIENT_FORCE_CLOSE_KEY.get();
        if (!name.equals(forceCloseName)) {
            forceCloseName = name;
            try {
                forceCloseKey = InputConstants.getKey(name);
            } catch (RuntimeException e) {
                forceCloseKey = null;
            }
            if (forceCloseKey == null || forceCloseKey == InputConstants.UNKNOWN) {
                Constants.LOG.warn("Vellum: client.forceCloseKey={} is no key; using Escape", name);
                forceCloseKey = InputConstants.getKey("key.keyboard.escape");
            }
        }
        return forceCloseKey;
    }

    /**
     * Records an Escape the page kept; true when it is the {@code client.forceClosePresses}th within
     * {@link #FORCE_CLOSE_WINDOW_MS}, so a page that swallows Escape can still be left by pressing it repeatedly.
     */
    private boolean keptTooManyEscapes(long now) {
        if (owner.screen() == null) return false;
        while (!keptEscapes.isEmpty() && now - keptEscapes.peekFirst() > FORCE_CLOSE_WINDOW_MS) keptEscapes.pollFirst();
        keptEscapes.addLast(now);
        if (keptEscapes.size() < VellumConfig.CLIENT_FORCE_CLOSE_PRESSES.get()) return false;
        keptEscapes.clear();
        return true;
    }

    /**
     * Asks {@code handler} about each key press the page leaves alone, before the screen's own keys (Escape, a
     * container screen's inventory key): for a mod's key mappings, which a page can't know. Keys the page uses never
     * reach it: one a {@code keydown} listener cancelled, one a focused control acted on (Enter on a button, arrows on
     * a slider), and every key but Escape while a text field has focus. Returning true consumes the key. Handlers are
     * asked in the order they were added until one returns true, and stay through navigation and reloads, as
     * {@link #onMessage} handlers do.
     */
    public DocumentDriver onKey(Predicate<KeyEvent> handler) {
        keyHandlers.add(handler);
        return this;
    }

    private boolean keyHandled(KeyEvent e) {
        for (Predicate<KeyEvent> handler : List.copyOf(keyHandlers)) {
            try {
                if (handler.test(e)) return true;
            } catch (RuntimeException ex) {
                Constants.LOG.error("Vellum: an onKey handler of {} failed", name(), ex);
            }
        }
        return false;
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

    /** The page's URL ({@code ns:path/page.html}); for inline HTML, the base its relative URLs resolve against. */
    public String url() {
        return url;
    }

    /** The page's {@code vellum.data} as last pushed, or null. */
    public @Nullable JsonElement data() {
        return data == null ? null : JsonParser.parseString(data);
    }

    /** Replaces {@code vellum.data}; the page's {@code vellum.on('data', fn)} listeners run. Kept across reloads. */
    public void push(JsonElement data) {
        pushData(data.toString());
    }

    /**
     * Sets the top-level fields of {@code vellum.data} that {@code fields} has and keeps the others (what the opener
     * passed); as {@link #push}, the page's listeners run.
     */
    public void merge(JsonObject fields) {
        JsonObject merged = data() instanceof JsonObject current ? current : new JsonObject();
        for (var field : fields.entrySet()) merged.add(field.getKey(), field.getValue());
        push(merged);
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
    /**
     * {@code vellum.send}: to this driver's {@link #onMessage} handlers, then to the server if a server opened the
     * page. At most {@code client.messageBurst} at once and {@code client.messagesPerSecond} after, counted per driver
     * so reloading the page or following a link doesn't reset the count; false when the message is dropped.
     */
    boolean send(String channel, String json) {
        if (!sendRate.tryTake()) {
            if (!warnedSendRate) Constants.LOG.warn("Vellum: {} is sending too many messages; dropping some", name());
            warnedSendRate = true;
            return false;
        }
        List<Consumer<JsonElement>> local = listeners.get(channel);
        if (local != null) {
            JsonElement value = null;
            try {
                value = JsonParser.parseString(json);
            } catch (JsonParseException | IllegalStateException e) {
                Constants.LOG.warn("Vellum: {} sent malformed JSON on '{}'", name(), channel);
            }
            // Each handler is the mod's code: one that throws (reading a field the page left out, say) is logged and the
            // rest still run. Its exception never reaches the page, which may not be the mod's own script.
            if (value != null) {
                for (Consumer<JsonElement> l : List.copyOf(local)) {
                    try {
                        l.accept(value);
                    } catch (RuntimeException e) {
                        Constants.LOG.error("Vellum: a handler for '{}' on {} threw", channel, name(), e);
                    }
                }
            }
        }
        if (session < 0) return true;
        if (channel.length() > MessagePayload.MAX_CHANNEL || json.length() > MessagePayload.MAX_JSON) {
            Constants.LOG.warn("Vellum: {} message on '{}' is too large to send ({} chars)", name(), channel, json.length());
            return false;
        }
        VellumClient.sendToServer(new MessagePayload(session, channel, json));
        return true;
    }

    int session() {
        return session;
    }

    /**
     * Whether a server opened this page (it belongs to a server session). Its scripts may then be the server's (an
     * inline page, or a page from a server resource pack), and its {@code vellum.send} messages go to the server:
     * {@link VellumScreens#onPageLoad} hooks should not give such a page anything the server must not see.
     */
    public boolean serverSession() {
        return session >= 0;
    }

    /** Whether a text field in the page has keyboard focus (the player is typing into it). */
    boolean typing() {
        return textInput;
    }

    String name() {
        return html != null ? "inline page" + (session >= 0 ? " (session " + session + ")" : "") : url;
    }

    // ---- Requests from the page, run between frames (never inside the engine's own dispatch) ----

    void requestClose() {
        closeRequested = true;
    }

    /**
     * {@code <a href>}, {@code location.href}, {@code vellum.open}: another .html page replaces this one; web links ask
     * first, as chat links do, and only right after the player clicked or pressed a key in the page, so a script
     * cannot put the confirmation up again and again.
     */
    void navigate(String target) {
        pendingNavigation = target;
    }

    private void runDeferred() {
        String target = pendingNavigation;
        pendingNavigation = null;
        if (target != null) {
            Screen screen = owner.screen();
            if (target.startsWith("https://") || target.startsWith("http://")) {
                if (VellumConfig.CLIENT_WEB_LINKS.get() == VellumConfig.WebLinks.BLOCK) {
                    if (!warnedLink) Constants.LOG.info("Vellum: {} links to {}; web links are blocked (client.webLinks)", name(), target);
                    warnedLink = true;
                } else if (Util.getMillis() - lastUserInput > USER_ACTIVATION_MS) {
                    if (!warnedLink) Constants.LOG.warn("Vellum: {} tried to open {} without a click or key press; ignored", name(), target);
                    warnedLink = true;
                } else if (screen != null) {
                    lastUserInput = Long.MIN_VALUE / 2; // one confirmation per click
                    try {
                        URI uri = new URI(target);
                        suspended = true;
                        ConfirmLinkScreen.confirmLinkNow(screen, uri);
                    } catch (URISyntaxException e) {
                        Constants.LOG.warn("Vellum: {} links to a malformed URL: {}", name(), target);
                    }
                }
            } else if (Urls.withoutQuery(target).endsWith(".html")) {
                url = target;
                html = null;
                load();
                // Another page in the same screen: the narrator reads its title, as for a screen that opens.
                if (screen != null) screen.triggerImmediateNarration(false);
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

    /** The cursor the page asks for where its pointer is: the hovered element's {@code cursor}, as it is used. */
    Cursor cursor() {
        return cursor;
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
