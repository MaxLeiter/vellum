package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Window;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.script.ScriptRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
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
    /** The moves a {@link #drag} takes, as a quick hand would make them. */
    private static final int DRAG_STEPS = 6;
    private static final MouseButtonInfo LEFT = new MouseButtonInfo(KeyNames.mcButton(0), 0);

    private final DocumentDriver driver;
    /** Whether the page is a HUD overlay's, which takes input through the screen it is interactive over. */
    private final boolean hud;

    private VellumAutomation(DocumentDriver driver, boolean hud) {
        this.driver = driver;
        this.hud = hud;
    }

    /** The open Vellum screen ({@link VellumScreen} or {@link VellumContainerScreen}), if its page is showing. */
    public static Optional<VellumAutomation> screen() {
        Screen screen = McClient.screen();
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
     * Whether the page's last frame showed a tooltip: a {@code title} that has waited out its delay, or an
     * {@code <item tooltip>}'s. Not while a container screen's slot shows its item's tooltip instead.
     */
    public boolean tooltipShown() {
        return driver.requestedTooltip();
    }

    /**
     * What Minecraft's narrator says for this page's screen now, all of it, as vanilla puts a screen's narration
     * together when the screen opens: the screen's title (the page's {@code <title>}), then the element the page reads
     * (the focused one, else the one under the pointer) in vanilla's widget phrasing, such as
     * {@code "Emperor Cualius. Reply 1: About the letter button. Left click to activate"}. Empty for a HUD overlay.
     * Works with the narrator off, for checks; the narrator itself waits for the pointer to rest before reading.
     */
    public Optional<String> narration() {
        return Optional.ofNullable(driver.owner().narration());
    }

    /**
     * Starts recording what Vellum hands Minecraft's narrator besides a screen's own narration ({@link #narration}):
     * what live regions announce and what HUD overlays read under the pointer, each as the text said. While it
     * records, pages work out what they would say even with the narrator off. Returns the list it records into, the
     * same one until {@link #stopRecordingNarration}. Render thread only.
     */
    public static List<String> recordNarration() {
        return PageNarrator.record();
    }

    /** Stops {@link #recordNarration}: pages narrate only while the narrator is on again. */
    public static void stopRecordingNarration() {
        PageNarrator.stopRecording();
    }

    /**
     * Whether the page has stopped changing by itself ({@link Document#settled}), so what it shows now is what it
     * will keep showing; poll it once a tick. Also false until the page has loaded (a HUD overlay loads at its first
     * draw) and while a navigation or {@code vellum.close()} waits for the next frame, and true while the page shows
     * its error panel.
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
        float[] at = screen == null ? null : aim(selector);
        if (at == null) return false;
        moveTo(screen, at[0], at[1]);
        return true;
    }

    /**
     * Where {@link #hover} would put the pointer, {x, y} in GUI px, without moving it (an element none of which
     * shows is scrolled into view, as hover does); empty when hover would return false. For pointer paths of your
     * own, such as a glide toward the element.
     */
    public Optional<float[]> pointerTarget(String selector) {
        return Optional.ofNullable(inputScreen() == null ? null : aim(selector));
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
        McClient.mouseButton(button, true);
        McClient.mouseButton(button, false);
        return true;
    }

    /**
     * Turns the wheel where {@link #hover} puts the pointer; positive notches scroll down (DOM {@code deltaY}),
     * negative up. False, sending nothing, when hover is. Like {@link #click}, leaves the pointer there.
     */
    public boolean wheel(String selector, double notches) {
        if (!hover(selector)) return false;
        McClient.scroll(notches);
        return true;
    }

    /**
     * Drags with the left button from where {@link #hover} puts the pointer to (dx, dy) GUI px from there: presses,
     * moves there in a few steps with the button held, and releases, through the mouse handler as {@link #click}
     * does, all within this call. The screen gets the move and drag calls a real drag brings: a {@code rotatable}
     * element turns and tilts (and stops there, since no time passed to give it speed), a range slider or scrollbar
     * follows. False, sending nothing, when hover is. The pointer stays where the drag ended.
     */
    public boolean drag(String selector, float dx, float dy) {
        Screen screen = inputScreen();
        float[] at = screen == null ? null : aim(selector);
        if (at == null) return false;
        moveTo(screen, at[0], at[1]);
        McClient.mouseButton(0, true);
        float px = at[0], py = at[1];
        for (int i = 1; i <= DRAG_STEPS; i++) {
            float x = at[0] + dx * i / DRAG_STEPS, y = at[1] + dy * i / DRAG_STEPS;
            moveTo(screen, x, y);
            // With a button held the handler sends a drag too, unless an overlay holding the press takes it (the
            // loaders' hooks ask VellumHud first).
            MouseButtonEvent event = new MouseButtonEvent(x, y, LEFT);
            if (!VellumHud.mouseDragged(event)) {
                //? if >=26 {
                screen.mouseDragged(event, x - px, y - py);
                //?} else
                //screen.mouseDragged(x, y, LEFT.button(), x - px, y - py);
            }
            px = x;
            py = y;
        }
        McClient.mouseButton(0, false);
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
        Screen open = McClient.screen();
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
        Window w = Minecraft.getInstance().getWindow();
        McClient.moveMouse(x * w.getScreenWidth() / w.getGuiScaledWidth(), y * w.getScreenHeight() / w.getGuiScaledHeight());
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
        McClient.key(event, true);
        McClient.key(event, false);
        return true;
    }

    /**
     * Types {@code text} like a keyboard: per character, its key down, the character, its key up. Like {@link #key}, it
     * goes to whatever has keyboard focus, and does nothing while the page takes no input.
     */
    public void type(String text) {
        if (inputScreen() == null) return;
        text.codePoints().forEach(cp -> {
            KeyEvent event = KeyNames.event(Character.toString(cp));
            if (event != null) McClient.key(event, true);
            McClient.typeChar(cp);
            if (event != null) McClient.key(event, false);
        });
    }

    // ---- Internals ----

    /**
     * Where pointer input reaches the first element matching {@code selector} ({@link Document#pointerTarget}), or
     * null when nothing matches or a real pointer there would reach something else.
     */
    private float @Nullable [] aim(String selector) {
        Element e = find(selector);
        float[] at = e == null ? null : e.ownerDocument().pointerTarget(e);
        // A click goes to the topmost interactive overlay with content there, else to the screen.
        return at != null && VellumHud.pointerDriverAt(at[0], at[1]) == (hud ? driver : null) ? at : null;
    }

    /**
     * The screen real input goes through to reach the page now: the open screen while it shows the page, or for an
     * overlay, while the overlay has the pointer over it. Null when input would not reach the page.
     */
    private @Nullable Screen inputScreen() {
        if (hud) return VellumHud.pointerScreen(driver);
        Screen open = McClient.loadingOverlay() ? null : McClient.screen(); // a loading overlay takes no input
        return open != null && DocumentDriver.of(open) == driver ? open : null;
    }

    private @Nullable Element find(String selector) {
        Document doc = driver.document();
        return doc == null ? null : doc.querySelector(selector);
    }
}
