package dev.vellum.engine.input;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * The option list of a {@code <select>}: the rows (options, with optgroup headers) and a highlighted row. Open, it is
 * the dropdown, painted as an overlay in a dark tooltip-like panel below the control (above when it does not fit
 * the viewport) and driven by mouse and keys. Closed selects use the same model to step their selection with the
 * arrow keys.
 *
 * <p>The selection is the DOM's ({@link Element#selectedOption()}): choosing a row selects its option.
 */
final class SelectPopup {
    private static final int MAX_ROWS = 8;
    private static final int BACKGROUND = 0xF0100010, BORDER = 0xFF505050, HIGHLIGHT = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFFFF, SELECTED_TEXT = 0xFFFFFF55, MUTED_TEXT = 0xFF808080;
    private static final float PAD = 4;

    private record Row(Element option, String label, boolean disabled, float indent) {
        boolean selectable() { return option != null && !disabled; }
    }

    private final Element select;
    private final List<Row> rows = new ArrayList<>();
    private final float labelWidth;
    private int highlighted = -1;
    /** Index of the first visible row. */
    private int top;

    SelectPopup(Element select) {
        this.select = select;
        for (Element child : select.children()) {
            if (child.tagName().equals("optgroup")) {
                String label = child.getAttribute("label");
                rows.add(new Row(null, label == null ? "" : label, true, 0));
                for (Element o : child.children()) {
                    if (o.tagName().equals("option")) rows.add(new Row(o, o.label(), o.isDisabled(), PAD));
                }
            } else if (child.tagName().equals("option")) {
                rows.add(new Row(child, child.label(), child.isDisabled(), 0));
            }
        }
        float w = 0;
        for (Row r : rows) w = Math.max(w, r.indent + Controls.measure(select).width(r.label, font()));
        labelWidth = w;
        Element current = select.selectedOption();
        for (int i = 0; i < rows.size(); i++) if (current != null && rows.get(i).option == current) highlighted = i;
        reveal();
    }

    Element select() { return select; }

    boolean isEmpty() { return rows.isEmpty(); }

    // ---- Options ----

    /** Arrow keys and Home/End on a closed select change the selection directly (as on Windows). */
    static boolean stepClosed(Element select, String key) {
        SelectPopup model = new SelectPopup(select);
        String k = switch (key) {
            case "ArrowRight" -> "ArrowDown";
            case "ArrowLeft" -> "ArrowUp";
            default -> key;
        };
        if (!model.navigate(k)) return false;
        model.commit();
        return true;
    }

    /** Makes {@code option} the selection, firing input and change on the select if it changed. */
    private void choose(Element option) {
        if (option == select.selectedOption()) return;
        option.setSelected(true);
        Forms.fireInputAndChange(select);
    }

    // ---- Interaction ----

    /** Chooses the highlighted option. */
    void commit() {
        if (highlighted >= 0 && rows.get(highlighted).selectable()) choose(rows.get(highlighted).option);
    }

    /** Arrows, PageUp/PageDown and Home/End move the highlight over selectable rows. False for other keys. */
    boolean navigate(String key) {
        switch (key) {
            case "ArrowDown" -> moveHighlight(highlighted, 1, 1);
            case "ArrowUp" -> moveHighlight(highlighted, -1, 1);
            case "PageDown" -> moveHighlight(highlighted, 1, MAX_ROWS);
            case "PageUp" -> moveHighlight(highlighted, -1, MAX_ROWS);
            case "Home" -> moveHighlight(-1, 1, 1);
            case "End" -> moveHighlight(rows.size(), -1, 1);
            default -> { return false; }
        }
        return true;
    }

    /** Highlights the selectable row under the pointer. */
    void hover(float x, float y) {
        int i = rowAt(x, y);
        if (i >= 0 && rows.get(i).selectable()) highlighted = i;
    }

    /** Mouseup over the list: chooses the option under the pointer. False when that is not a selectable row. */
    boolean release(float x, float y) {
        int i = rowAt(x, y);
        if (i < 0 || !rows.get(i).selectable()) return false;
        choose(rows.get(i).option);
        return true;
    }

    /** Scrolls the list by a wheel delta in px, at least one row per notch. */
    void wheel(float dy) {
        int n = Math.round(dy / rowHeight());
        if (n == 0 && dy != 0) n = dy > 0 ? 1 : -1;
        top = Math.max(0, Math.min(maxTop(), top + n));
    }

    boolean contains(float x, float y) {
        return Dom.inside(bounds(), x, y);
    }

    /** Steps {@code count} selectable rows from {@code from} in direction {@code dir}, stopping at the last one found. */
    private void moveHighlight(int from, int dir, int count) {
        for (int i = from + dir; i >= 0 && i < rows.size() && count > 0; i += dir) {
            if (rows.get(i).selectable()) {
                highlighted = i;
                count--;
            }
        }
        reveal();
    }

    /** Scrolls so the highlighted row is visible. */
    private void reveal() {
        if (highlighted < 0) return;
        top = Math.max(Math.min(top, highlighted), highlighted - MAX_ROWS + 1);
        top = Math.max(0, Math.min(maxTop(), top));
    }

    private int maxTop() {
        return Math.max(0, rows.size() - MAX_ROWS);
    }

    private int rowAt(float x, float y) {
        if (!contains(x, y)) return -1;
        int i = top + (int) ((y - bounds()[1] - 1) / rowHeight());
        return i < Math.min(rows.size(), top + MAX_ROWS) ? i : -1;
    }

    // ---- Geometry and paint ----

    private FontSpec font() {
        return FontSpec.of(select.computedStyle());
    }

    private float rowHeight() {
        return Controls.measure(select).glyphHeight(font()) + 3;
    }

    /**
     * {x, y, width, height} in viewport px: under the select as painted (its bounding box, transforms included), or
     * above it when only that fits.
     */
    private float[] bounds() {
        Document doc = select.ownerDocument();
        float[] r = select.getBoundingClientRect();
        float sx = r[0], sy = r[1], sh = r[3];
        float width = Math.max(r[2], labelWidth + 2 * PAD + 2);
        float height = Math.min(rows.size(), MAX_ROWS) * rowHeight() + 2;
        float y = sy + sh;
        if (y + height > doc.viewportHeight() && sy - height >= 0) y = sy - height;
        float x = Math.max(0, Math.min(sx, doc.viewportWidth() - width));
        return new float[] {x, y, width, height};
    }

    void paint(Canvas canvas) {
        float[] b = bounds();
        float x = b[0], y = b[1], w = b[2], h = b[3];
        canvas.fillRect(x, y, w, h, BACKGROUND);
        canvas.fillRect(x, y, w, 1, BORDER);
        canvas.fillRect(x, y + h - 1, w, 1, BORDER);
        canvas.fillRect(x, y + 1, 1, h - 2, BORDER);
        canvas.fillRect(x + w - 1, y + 1, 1, h - 2, BORDER);
        canvas.save();
        canvas.clipRect(x + 1, y + 1, w - 2, h - 2);
        FontSpec font = font();
        float rowHeight = rowHeight(), textOffset = (rowHeight - Controls.measure(select).glyphHeight(font)) / 2;
        Element current = select.selectedOption();
        for (int i = top; i < Math.min(rows.size(), top + MAX_ROWS); i++) {
            Row r = rows.get(i);
            float ry = y + 1 + (i - top) * rowHeight;
            if (i == highlighted) canvas.fillRect(x + 1, ry, w - 2, rowHeight, HIGHLIGHT);
            int color = !r.selectable() ? MUTED_TEXT : r.option == current ? SELECTED_TEXT : TEXT;
            canvas.drawText(r.label, x + 1 + PAD + r.indent, ry + textOffset, font, color, 0, true);
        }
        canvas.restore();
    }
}
