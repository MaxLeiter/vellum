package dev.vellum.mod.client;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.Accessible;
import dev.vellum.engine.input.Narration;
import dev.vellum.mod.Constants;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationTrigger;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Hands a page's narration ({@link Narration}) to Minecraft's narrator (Ctrl+B), phrased as vanilla phrases its
 * widgets ("Done button. Left click to activate").
 *
 * <p>Screens narrate as vanilla screens do: when one opens, and after input once the pointer rests (750 ms) or a
 * button or key was pressed (200 ms), vanilla's {@code Screen} collects its title ({@link #title}: the page's
 * {@code <title>}) and its widget ({@link #addNarratedElement}: the element the page reads) and says what changed.
 * Every frame a page is drawn while the narrator listens, its live regions' announcements are said, queued or
 * interrupting ({@link #tick}). A HUD overlay over a screen reads the element under the pointer itself, once the
 * pointer has rested there as long as vanilla waits, since the screen under it knows nothing of it.
 */
final class PageNarrator {
    /** How long the pointer rests before an overlay reads what it is on: vanilla's wait after a mouse move. */
    private static final long HOVER_DELAY_MS = 750;
    /** What Vellum handed the narrator since {@link #record} started, or null while no one records. */
    private static @Nullable List<String> recording;

    private final DocumentDriver driver;
    /** The focused element last read (vanilla's last narratable): after it, the hovered element gets its turn. */
    private @Nullable Element lastFocused;
    /** The page's title and the component made of it, so the same title is the same component. */
    private @Nullable String title;
    private @Nullable Component titleComponent;
    /** An overlay's hovered element: what it says, since when the pointer has been on it, and what was last said. */
    private @Nullable String hoverText, hoverSaid;
    private long hoverSince;

    PageNarrator(DocumentDriver driver) {
        this.driver = driver;
    }

    // ---- The screen's narration ----

    /** The screen's title: the page's {@code <title>}, else {@code fallback} (also before the page has loaded). */
    Component title(Component fallback) {
        Document doc = driver.document();
        String text = doc == null ? "" : doc.title();
        if (text.isEmpty()) return fallback;
        if (!text.equals(title)) {
            title = text;
            titleComponent = Component.literal(text);
        }
        return Objects.requireNonNull(titleComponent);
    }

    /**
     * For {@code Screen.updateNarratedWidget}: adds the element the page reads now as vanilla adds a widget, its place
     * among the page's tab stops and (when focused) the screen's Tab hint, then the element itself one level in. That
     * is the focused element, unless it was the last one read; then the one under the pointer; then the focused one
     * again, as vanilla picks among its widgets. False when the page reads nothing, so the screen says what vanilla
     * says then.
     */
    boolean addNarratedElement(NarrationElementOutput output) {
        Document doc = driver.document();
        if (doc == null) return false;
        Narration narration = doc.input().narration();
        Accessible focused = narration.focused(), hovered = narration.hovered();
        Accessible read = focused != null && focused.element() != lastFocused ? focused : hovered != null ? hovered : focused;
        if (read == null) return false;
        boolean focus = focused != null && read.element() == focused.element();
        if (focus) lastFocused = read.element();
        if (read.count() > 1) {
            output.add(NarratedElementType.POSITION,
                    Component.translatable("narrator.position.screen", read.position(), read.count()));
            if (focus) output.add(NarratedElementType.USAGE, Component.translatable("narration.component_list.usage"));
        }
        narrate(read, focus, output.nest());
        return true;
    }

    /** What vanilla's narration of a screen says now, all of it, from its {@code updateNarrationState}. */
    static String collect(Consumer<NarrationElementOutput> updateNarrationState) {
        ScreenNarrationCollector collector = new ScreenNarrationCollector();
        collector.update(updateNarrationState, NarrationTrigger.MOUSE);
        return collector.collectNarrationText(true);
    }

    // ---- Live regions and overlays ----

    /**
     * Every frame the page is drawn, after its frame, while the narrator listens: says what its live regions announce,
     * and with {@code readsPointer} (a HUD overlay over a screen) the element under the pointer once it has rested
     * there for {@link #HOVER_DELAY_MS}.
     */
    void tick(boolean readsPointer) {
        Document doc = driver.document();
        if (doc == null || !listening()) {
            hoverText = hoverSaid = null;
            return;
        }
        Narration narration = doc.input().narration();
        // The first assertive announcement of a frame cuts off what was being said; the rest of the frame queue after
        // it, assertive ones first, so two alerts arriving together are both heard.
        List<Narration.Announcement> announcements = narration.announcements(); // reading takes them
        boolean cut = false;
        for (Narration.Announcement a : announcements) {
            if (!a.interrupt()) continue;
            say(Component.literal(a.text()), !cut);
            cut = true;
        }
        for (Narration.Announcement a : announcements) {
            if (!a.interrupt()) say(Component.literal(a.text()), false);
        }
        boolean announced = !announcements.isEmpty();
        Accessible hovered = readsPointer ? narration.hovered() : null;
        // Keys stay with the screen under an overlay, so its elements read as the pointer uses them.
        String text = hovered == null ? null : collect(out -> narrate(hovered, false, out));
        long now = Util.getMillis();
        if (!Objects.equals(text, hoverText)) {
            hoverText = text;
            hoverSince = now;
        }
        if (text == null) hoverSaid = null;
        else if (!text.equals(hoverSaid) && now - hoverSince >= HOVER_DELAY_MS) {
            hoverSaid = text;
            say(Component.literal(text), !announced); // never cuts off this frame's announcements
        }
    }

    /** Whether anyone listens: the narrator reads system messages, or {@link #record} is on. */
    static boolean listening() {
        if (recording != null) return true;
        Minecraft mc = Minecraft.getInstance();
        return mc.getNarrator().isActive() && mc.options.narrator().get().shouldNarrateSystem();
    }

    /** Says {@code text}: cutting off what the narrator is saying, or after it. */
    private static void say(Component text, boolean interrupt) {
        String said = text.getString();
        if (said.isEmpty()) return;
        Constants.LOG.debug("Vellum narrates{}: {}", interrupt ? " at once" : "", said);
        if (recording != null) recording.add(said);
        GameNarrator narrator = Minecraft.getInstance().getNarrator();
        if (interrupt) narrator.saySystemNow(text);
        else narrator.saySystemQueued(text);
    }

    /** Starts recording what Vellum hands the narrator, if it was not already, and returns the list it records into. */
    static List<String> record() {
        if (recording == null) recording = new ArrayList<>();
        return recording;
    }

    static void stopRecording() {
        recording = null;
    }

    // ---- Vanilla's phrasing ----

    /** An element as vanilla narrates a widget: its title, how to use it (unless disabled), and its hint. */
    private static void narrate(Accessible a, boolean focused, NarrationElementOutput output) {
        int[] tab = a.tabPosition();
        if (tab != null) output.add(NarratedElementType.POSITION, Component.translatable("narrator.position.tab", tab[0], tab[1]));
        output.add(NarratedElementType.TITLE, title(a));
        String usage = a.disabled() ? null : usage(a, focused);
        if (usage != null) output.add(NarratedElementType.USAGE, Component.translatable(usage));
        if (a.hint() != null) output.add(NarratedElementType.HINT, Component.literal(a.hint()));
    }

    /**
     * The element's title in vanilla's words where vanilla has them: "Done button", "Volume: 40 slider",
     * "Name edit box: Steve", "Checkbox: Hints: ON", "Item: Iron Sword", "Quests tab"; Vellum's own for links and
     * radios; the name alone for anything else.
     */
    static Component title(Accessible a) {
        Component name = Component.literal(a.name());
        boolean checked = Boolean.TRUE.equals(a.checked());
        return switch (a.role()) {
            case BUTTON -> Component.translatable("gui.narrate.button", name);
            case COMBOBOX -> Component.translatable("gui.narrate.button", nameValue(name, a.value()));
            case LINK -> Component.translatable("vellum.narrate.link", name);
            case CHECKBOX -> Component.translatable("narration.checkbox", CommonComponents.optionStatus(name, checked));
            case RADIO -> Component.translatable("vellum.narration.radio", CommonComponents.optionStatus(name, checked));
            case SLIDER -> Component.translatable("gui.narrate.slider", nameValue(name, a.value()));
            case TEXTBOX -> Component.translatable("gui.narrate.editBox", name, a.value() == null ? "" : a.value());
            case TAB -> Component.translatable("gui.narrate.tab", name);
            case ITEM, SLOT -> {
                if (a.value() == null) yield name;
                Component item = Component.translatable("narration.item", a.value());
                yield a.name().equals(a.value()) ? item : CommonComponents.joinForNarration(name, item);
            }
            case GENERIC, IMG -> name;
        };
    }

    private static Component nameValue(Component name, @Nullable String value) {
        if (value == null || value.isEmpty()) return name;
        return name.getString().isEmpty() ? Component.literal(value) : CommonComponents.optionNameValue(name, Component.literal(value));
    }

    /**
     * How to use it, as vanilla says for its widgets, for the pointer or the keyboard. Checkboxes toggle with Space
     * and radios move with the arrows in Vellum (Enter does neither, as in HTML), so those say so.
     */
    private static @Nullable String usage(Accessible a, boolean focused) {
        boolean checked = Boolean.TRUE.equals(a.checked());
        return switch (a.role()) {
            case BUTTON, COMBOBOX -> focused ? "narration.button.usage.focused" : "narration.button.usage.hovered";
            case LINK -> focused ? "narration.link.usage.focused" : "narration.link.usage.hovered";
            case CHECKBOX -> focused ? "vellum.narration.checkbox.usage.focused." + (checked ? "uncheck" : "check")
                    : "narration.checkbox.usage.hovered." + (checked ? "uncheck" : "check");
            case RADIO -> focused ? "narration.selection.usage" : checked ? null : "narration.checkbox.usage.hovered.check";
            case SLIDER -> focused ? "narration.slider.usage.focused" : "narration.slider.usage.hovered";
            default -> null;
        };
    }
}
