package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.script.ScriptRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Drives a Vellum page from code, for dev autopilots and in-game checks: finds elements by CSS selector, reads their
 * geometry and text, and sends input through Minecraft's own mouse and keyboard handlers, the path real input takes
 * (so vanilla slot highlights, clicks and shift-clicks work in container screens too). Render thread only.
 *
 * <pre>{@code
 * VellumAutomation.screen().ifPresent(page -> {
 *     page.click("#amount");
 *     page.type("64");
 *     page.key("Enter");
 *     page.leave();
 * });
 * // then, once a tick: VellumAutomation.screen().map(VellumAutomation::settled)
 * }</pre>
 *
 * Positions are GUI px, like the page's own coordinates. A HUD overlay ({@link #hud}) takes input only while it is
 * interactive over the open screen; otherwise its input methods return false and do nothing.
 */
public final class VellumAutomation {
    /** Where {@link #leave} puts the pointer, in GUI px: outside the window, where no page or widget is. */
    private static final float OFF_WINDOW = -16;

    private final DocumentDriver driver;
    /** Whether the page is a HUD overlay's, which takes input through the screen it is interactive over. */
    private final boolean hud;

    private VellumAutomation(DocumentDriver driver, boolean hud) {
        this.driver = driver;
        this.hud = hud;
    }

    /** The open Vellum screen ({@link VellumScreen} or {@link VellumContainerScreen}), if its page is showing. */
    public static Optional<VellumAutomation> screen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        DocumentDriver driver = screen == null ? null : DocumentDriver.of(screen);
        return driver == null ? Optional.empty() : Optional.of(new VellumAutomation(driver, false));
    }

    /**
     * A shown HUD overlay ({@link VellumHud}). Its page can be read at any time. Pointer input reaches it only while
     * it is interactive over the open screen: the screen is one it was registered for, and a frame has drawn the
     * overlay above it with the pointer. Then {@link #hover}, {@link #click} and {@link #wheel} take the real input
     * path a screen's page does. Otherwise they return false: the overlay is drawn in the HUD layer without the
     * pointer, and a real click there would go to the screen or the game.
     */
    public static Optional<VellumAutomation> hud(Identifier id) {
        DocumentDriver driver = VellumHud.driver(id);
        return driver == null ? Optional.empty() : Optional.of(new VellumAutomation(driver, true));
    }

    // ---- Reading the page ----

    /** Whether an element matches {@code selector}. */
    public boolean exists(String selector) {
        return find(selector) != null;
    }

    /**
     * The border box of the first element matching {@code selector} as {x, y, width, height} in GUI px, as painted
     * (after scrolling and transforms, as {@code getBoundingClientRect()}); empty when nothing matches or it has no
     * box.
     */
    public Optional<float[]> rect(String selector) {
        Element e = find(selector);
        if (e == null) return Optional.empty();
        float[] rect = e.getBoundingClientRect();
        return e.box == null ? Optional.empty() : Optional.of(rect);
    }

    /** The {@code textContent} of the first element matching {@code selector}. */
    public Optional<String> text(String selector) {
        return Optional.ofNullable(find(selector)).map(Element::textContent);
    }

    /**
     * Runs {@code js} in the page's script sandbox and returns its completion value as JSON: {@code eval("count")},
     * {@code eval("document.title")}. Empty when it has no JSON form (undefined, a function, an error, which is
     * reported like any script error) or the page has no scripting. For assertions in dev tooling.
     */
    public Optional<JsonElement> eval(String js) {
        Document doc = driver.document();
        ScriptRuntime scripts = doc == null ? null : doc.scripts();
        String json = scripts == null ? null : scripts.evaluateToJson(js, driver.name() + "#automation");
        return json == null ? Optional.empty() : Optional.of(JsonParser.parseString(json));
    }

    /**
     * Whether the page has stopped changing by itself, so what it shows now is what it will keep showing: no restyle,
     * relayout or repaint pending, no smooth scroll, no template update or {@code vellum.nextTick} callback waiting,
     * no transition or finite animation running, no drag or turntable spin, and no {@code title} tooltip waiting out
     * its delay. Infinite animations, timers ({@code setTimeout}, {@code setInterval}), animation-frame callbacks
     * and a text field's blinking caret don't count, as they never stop; wait for what a timer changes with
     * {@link #text} or {@link #eval}. False until the page has loaded and painted, and until the frame after input
     * or {@link #eval} (either may change it); true while the page shows its error panel. Poll it once a tick.
     */
    public boolean settled() {
        return driver.settled();
    }

    // ---- Pointer ----

    /**
     * Moves the pointer onto the first element matching {@code selector}: to the centre of the part of it that shows
     * (its border box cut to the scroll containers it is in and to the window), after scrolling it into view as
     * {@link #scrollIntoView} does when none of it shows. False, leaving the pointer where it was (and any such
     * scroll done), when nothing matches, it has no box, it cannot be brought into view, or something else is on top
     * at that point (another element, a HUD overlay over the screen), so a real pointer there would not reach it;
     * also when the page takes no input now ({@link #hud}). An overlay sees the pointer move at its next frame, as
     * with a real mouse.
     */
    public boolean hover(String selector) {
        Screen screen = inputScreen();
        Element e = find(selector);
        float[] at = screen == null || e == null ? null : e.ownerDocument().pointerTarget(e);
        // A click goes to the topmost interactive overlay with content there, else to the screen.
        if (at == null || VellumHud.pointerDriverAt(at[0], at[1]) != (hud ? driver : null)) return false;
        moveTo(screen, at[0], at[1]);
        return true;
    }

    /** A left click on the first element matching {@code selector}, where {@link #hover} puts the pointer. */
    public boolean click(String selector) {
        return click(selector, 0);
    }

    /**
     * A click with {@code button} (0 left, 1 middle, 2 right, as in DOM events) where {@link #hover} puts the pointer;
     * false, sending nothing, when hover is. The pointer stays there, so the element keeps {@code :hover} and its
     * {@code title} tooltip can show in later frames: {@link #leave} moves it away.
     */
    public boolean click(String selector, int button) {
        if (!hover(selector)) return false;
        MouseButtonInfo info = new MouseButtonInfo(button + 1, 0);
        long window = Minecraft.getInstance().getWindow().handle();
        MouseHandler mouse = Minecraft.getInstance().mouseHandler;
        mouse.onButton(window, info, InputConstants.PRESS);
        mouse.onButton(window, info, InputConstants.RELEASE);
        return true;
    }

    /**
     * Turns the wheel where {@link #hover} puts the pointer; positive notches scroll down (DOM {@code deltaY}),
     * negative up. False, sending nothing, when hover is. Like {@link #click}, leaves the pointer there.
     */
    public boolean wheel(String selector, double notches) {
        if (!hover(selector)) return false;
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, -notches);
        return true;
    }

    /**
     * Moves the pointer off every element, to a point outside the window: Vellum pages (screens and interactive
     * overlays) and vanilla screens hover nothing there, so no {@code :hover} style, {@code title} tooltip, item
     * tooltip or slot highlight is left in the next frame. Call it after {@link #click} or {@link #wheel} before a
     * screenshot. The pointer is shared, so this is the same through any page; it does nothing while no screen is
     * open (the game has the mouse).
     */
    public void leave() {
        Screen open = Minecraft.getInstance().gui.screen();
        if (open != null) moveTo(open, OFF_WINDOW, OFF_WINDOW);
    }

    /**
     * Scrolls the scroll containers the first element matching {@code selector} is in so that it shows, instantly and
     * by the least scroll (none when it already shows whole). True when some of it shows afterwards; false when
     * nothing matches, it has no box, or a clip no scroller moves hides it. Pointer input does this by itself for a
     * target none of which shows.
     */
    public boolean scrollIntoView(String selector) {
        Element e = find(selector);
        return e != null && e.ownerDocument().reveal(e, true) != null;
    }

    /**
     * Puts the pointer at (x, y): where Minecraft's mouse handler has it (clicks and render-time hover, such as
     * vanilla's slot highlight, read it there), then a move event to the screen, as the handler sends one.
     */
    private static void moveTo(Screen screen, float x, float y) {
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        MouseHandler mouse = mc.mouseHandler;
        mouse.setIgnoreFirstMove(); // takes the position without queueing a second move event
        mouse.onMove(w.handle(), x * w.getScreenWidth() / w.getGuiScaledWidth(),
                y * w.getScreenHeight() / w.getGuiScaledHeight(), 0, 0);
        screen.mouseMoved(x, y);
        screen.afterMouseMove();
    }

    // ---- Keyboard ----

    /**
     * Presses and releases the key that gives DOM key {@code domKey} ({@code "Enter"}, {@code "ArrowDown"},
     * {@code "a"}, {@code "A"} with shift) on the current keyboard layout. False when no key gives it, or the page
     * takes no input now. Keys go to whatever has keyboard focus, which is never a HUD overlay: through {@link #hud}
     * they reach the screen the overlay is interactive over (chat's input box, for
     * {@link VellumHud.Input#WHEN_CHAT_OPEN}).
     */
    public boolean key(String domKey) {
        KeyEvent event = inputScreen() == null ? null : KeyNames.event(domKey);
        if (event == null) return false;
        long window = Minecraft.getInstance().getWindow().handle();
        Minecraft.getInstance().keyboardHandler.keyPress(window, InputConstants.PRESS, event);
        Minecraft.getInstance().keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
        return true;
    }

    /**
     * Types {@code text} like a keyboard: per character, its key down, the character, its key up. Like {@link #key}, it
     * goes to whatever has keyboard focus, and does nothing while the page takes no input.
     */
    public void type(String text) {
        if (inputScreen() == null) return;
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().handle();
        text.codePoints().forEach(cp -> {
            KeyEvent event = KeyNames.event(Character.toString(cp));
            if (event != null) mc.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
            mc.keyboardHandler.charTyped(window, new CharacterEvent(cp));
            if (event != null) mc.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
        });
    }

    // ---- Internals ----

    /**
     * The screen real input goes through to reach the page now: the open screen while it shows the page, or for an
     * overlay, while the overlay has the pointer over it. Null when input would not reach the page.
     */
    private @Nullable Screen inputScreen() {
        if (hud) return VellumHud.pointerScreen(driver);
        Minecraft mc = Minecraft.getInstance();
        Screen open = mc.gui.overlay() == null ? mc.gui.screen() : null; // a loading overlay takes no input
        return open != null && DocumentDriver.of(open) == driver ? open : null;
    }

    private @Nullable Element find(String selector) {
        Document doc = driver.document();
        return doc == null ? null : doc.querySelector(selector);
    }
}
