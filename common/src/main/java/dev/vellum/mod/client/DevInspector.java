package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.inspect.BoxHighlight;
import dev.vellum.engine.inspect.Inspection;
import dev.vellum.engine.style.Cursor;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.render.McGui;
import dev.vellum.mod.platform.Services;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;

/**
 * The element inspector, which F12 toggles on a page's screen in a development environment (or with
 * {@code client.inspector=true}): a page of its own ({@link #PAGE}) docked on the right, which reads and edits the
 * inspected page through an {@link Inspection}. The inspected page is laid out in what is left of the screen, and
 * keeps working: only while picking does the pointer pick elements instead of using them. Dragging the panel's left
 * edge resizes it.
 */
final class DevInspector implements DocumentDriver.Owner {
    static final String PAGE = "vellum:vellum/dev/inspector.html";
    /** The panel's width: two fifths of the screen, within these GUI px, until the player drags its edge. */
    private static final int MIN_WIDTH = 140, MAX_WIDTH = 280;
    /** The inspected page keeps at least this much of the screen. */
    private static final int MIN_PAGE = 120;
    /** How near the panel's edge, in GUI px, the pointer grabs it. */
    private static final int EDGE = 2;
    /** The width the player dragged the panel to, kept for later inspectors this session; -1 before any drag. */
    private static int chosenWidth = -1;

    private final DocumentDriver target;
    private final DocumentDriver panel;
    final Inspection inspection = new Inspection();
    /** Where the panel starts, in GUI px: the inspected page's width. */
    private int split;
    private int width, height;
    /** Dragging the panel's edge; a button went down in the panel (it gets the moves and the release, wherever). */
    private boolean resizing, pressedInPanel;

    DevInspector(DocumentDriver target) {
        this.target = target;
        panel = new DocumentDriver(this, PAGE, null, -1);
        panel.inspecting(inspection);
    }

    static boolean enabled() {
        return Services.PLATFORM.isDevelopmentEnvironment() || VellumConfig.CLIENT_INSPECTOR.get();
    }

    static boolean toggleKey(KeyEvent e) {
        return e.key() == InputConstants.KEY_F12;
    }

    /** Lays the panel out for a screen this size; returns the width left for the inspected page. */
    int resize(int width, int height) {
        this.width = width;
        this.height = height;
        int panelWidth = chosenWidth > 0 ? chosenWidth : Math.min(MAX_WIDTH, width * 2 / 5);
        split = width - Math.max(MIN_WIDTH, Math.min(panelWidth, width - MIN_PAGE));
        panel.resize(width - split, height);
        return split;
    }

    /** Where the panel starts, in GUI px. */
    int split() {
        return split;
    }

    void close() {
        panel.close();
    }

    // ---- Owner ----

    @Override
    public void closeDocument() {
        target.toggleInspector();
    }

    @Override
    public @Nullable Screen screen() {
        return target.screen();
    }

    // ---- Rendering ----

    /** Over the inspected page's frame: the highlight, then the panel. */
    void extract(GuiGraphicsExtractor g, @Nullable Document page, int mouseX, int mouseY) {
        inspection.retarget(page);
        if (inspection.picking() && page != null) inspection.hover(mouseX < split ? target.elementAt(mouseX, mouseY) : null);
        if (inspection.highlighted() != null) {
            McCanvas canvas = new McCanvas(g, -1, -1, null);
            BoxHighlight.paint(canvas, inspection.highlighted());
            canvas.finish();
        }
        McGui.pushTranslation(g, split, 0);
        panel.extractPage(g, mouseX - split, mouseY);
        McGui.popTranslation(g);
        if (resizing || onEdge(mouseX)) {
            McGui.fill(g, split - 1, 0, split + 1, height, 0x806FA8DC);
            McGui.cursor(g, Cursor.EW_RESIZE, true);
        }
        // Tooltips are drawn later, where they are asked for: at the pointer itself, untranslated.
        panel.extractTooltip(g, mouseX, mouseY);
    }

    private boolean onEdge(double x) {
        return Math.abs(x - split) <= EDGE;
    }

    // ---- Input (true = consumed: the inspected page doesn't see it) ----

    boolean mouseMoved(double x, double y) {
        if (resizing) {
            chosenWidth = width - (int) Math.round(x);
            target.resize(width, height); // lays out the page and the panel at the new split
            return true;
        }
        if (pressedInPanel || x >= split) return panel.mouseMoved(x - split, y) || true;
        return inspection.picking();
    }

    boolean mouseClicked(MouseButtonEvent e) {
        if (onEdge(e.x())) return resizing = true;
        if (e.x() >= split) {
            pressedInPanel = true;
            return panel.mouseClicked(moved(e)) || true;
        }
        if (!inspection.picking()) return false;
        inspection.select(target.elementAt(e.x(), e.y()));
        return true;
    }

    boolean mouseReleased(MouseButtonEvent e) {
        if (resizing) {
            resizing = false;
            return true;
        }
        if (pressedInPanel) {
            pressedInPanel = false;
            return panel.mouseReleased(moved(e)) || true;
        }
        return e.x() >= split ? panel.mouseReleased(moved(e)) || true : inspection.picking();
    }

    boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return x >= split && (panel.mouseScrolled(x - split, y, scrollX, scrollY) || true);
    }

    /**
     * Every key goes to the panel while one of its fields has focus (Escape too, which the field uses and the screen
     * must not see); otherwise Escape stops picking, else closes the inspector.
     */
    boolean keyPressed(KeyEvent e) {
        if (panel.typing()) {
            panel.keyPressed(e);
            return true;
        }
        if (!e.isEscape()) return false;
        if (inspection.picking()) inspection.pick(false);
        else target.toggleInspector();
        return true;
    }

    boolean keyReleased(KeyEvent e) {
        return panel.typing() && panel.keyReleased(e);
    }

    boolean charTyped(CharacterEvent e) {
        return panel.typing() && panel.charTyped(e);
    }

    private MouseButtonEvent moved(MouseButtonEvent e) {
        return new MouseButtonEvent(e.x() - split, e.y(), e.buttonInfo());
    }
}
