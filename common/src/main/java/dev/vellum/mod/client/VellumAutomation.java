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
 * });
 * }</pre>
 *
 * Positions are GUI px, like the page's own coordinates. HUD overlays take no input: their input methods return
 * false and do nothing.
 */
public final class VellumAutomation {
    private final DocumentDriver driver;
    /** The screen showing the page, which input goes to; null for HUD overlays. */
    private final @Nullable Screen screen;

    private VellumAutomation(DocumentDriver driver, @Nullable Screen screen) {
        this.driver = driver;
        this.screen = screen;
    }

    /** The open Vellum screen ({@link VellumScreen} or {@link VellumContainerScreen}), if its page is showing. */
    public static Optional<VellumAutomation> screen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        DocumentDriver driver = screen == null ? null : DocumentDriver.of(screen);
        return driver == null ? Optional.empty() : Optional.of(new VellumAutomation(driver, screen));
    }

    /** A shown HUD overlay ({@link VellumHud}), if its page is showing. */
    public static Optional<VellumAutomation> hud(Identifier id) {
        DocumentDriver driver = VellumHud.driver(id);
        return driver == null ? Optional.empty() : Optional.of(new VellumAutomation(driver, null));
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
        e.ownerDocument().flushLayout();
        return e.box == null ? Optional.empty() : Optional.of(e.getBoundingClientRect());
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

    // ---- Pointer ----

    /** Moves the pointer to the centre of the first element matching {@code selector}; false when it has no box. */
    public boolean hover(String selector) {
        float[] r = rect(selector).orElse(null);
        if (screen == null || r == null || r[2] <= 0 || r[3] <= 0) return false;
        moveTo(r[0] + r[2] / 2, r[1] + r[3] / 2);
        return true;
    }

    /** A left click at the centre of the first element matching {@code selector}. */
    public boolean click(String selector) {
        return click(selector, 0);
    }

    /** A click with {@code button} (0 left, 1 middle, 2 right, as in DOM events) at the element's centre. */
    public boolean click(String selector, int button) {
        if (!hover(selector)) return false;
        MouseButtonInfo info = new MouseButtonInfo(button + 1, 0);
        long window = Minecraft.getInstance().getWindow().handle();
        MouseHandler mouse = Minecraft.getInstance().mouseHandler;
        mouse.onButton(window, info, InputConstants.PRESS);
        mouse.onButton(window, info, InputConstants.RELEASE);
        return true;
    }

    /** Turns the wheel over the element's centre; positive notches scroll down (DOM {@code deltaY}), negative up. */
    public boolean wheel(String selector, double notches) {
        if (!hover(selector)) return false;
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, -notches);
        return true;
    }

    /**
     * Puts the pointer at (x, y): where Minecraft's mouse handler has it (clicks and render-time hover, such as
     * vanilla's slot highlight, read it there), then a move event to the screen, as the handler sends one.
     */
    private void moveTo(float x, float y) {
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
     * {@code "a"}, {@code "A"} with shift) on the current keyboard layout. False when no key gives it.
     */
    public boolean key(String domKey) {
        KeyEvent event = screen == null ? null : KeyNames.event(domKey);
        if (event == null) return false;
        long window = Minecraft.getInstance().getWindow().handle();
        Minecraft.getInstance().keyboardHandler.keyPress(window, InputConstants.PRESS, event);
        Minecraft.getInstance().keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
        return true;
    }

    /** Types {@code text} like a keyboard: per character, its key down, the character, its key up. */
    public void type(String text) {
        if (screen == null) return;
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().handle();
        text.codePoints().forEach(cp -> {
            KeyEvent event = KeyNames.event(Character.toString(cp));
            if (event != null) mc.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
            mc.keyboardHandler.charTyped(window, new CharacterEvent(cp));
            if (event != null) mc.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
        });
    }

    private @Nullable Element find(String selector) {
        Document doc = driver.document();
        return doc == null ? null : doc.querySelector(selector);
    }
}
