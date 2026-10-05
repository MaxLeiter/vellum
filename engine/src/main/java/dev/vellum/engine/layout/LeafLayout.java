package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;

/**
 * Boxes without laid-out content: replaced elements (sized by {@link LayoutPass} from their natural size) and form
 * controls (sized purely by CSS; {@code input.Controls} paints their content). Their only job here is a baseline,
 * so controls line up with surrounding text the way browsers align them.
 */
final class LeafLayout implements FormattingContext {
    private final LayoutPass pass;

    LeafLayout(LayoutPass pass) {
        this.pass = pass;
    }

    @Override
    public LayoutResult layoutContent(LayoutBox box, float contentWidth, float contentHeight, float percentHeight,
                                      boolean measure) {
        float baseline = controlBaseline(box, BoxModel.or(contentHeight, 0));
        return new LayoutResult(0, baseline, baseline, MarginSet.EMPTY, MarginSet.EMPTY, false);
    }

    @Override
    public float intrinsicContentWidth(LayoutBox box, boolean max) {
        return 0;
    }

    /**
     * Single-line text controls draw their text centred in the content box, a textarea from its top; their baseline
     * is that text's. Other leaves have none (they align by their bottom margin edge).
     */
    private float controlBaseline(LayoutBox box, float contentHeight) {
        if (box.isReplaced()) return Float.NaN;
        Element e = box.element;
        boolean multiline = e.tagName().equals("textarea");
        boolean singleLine = e.tagName().equals("select") || e.isTextControl() && !multiline;
        if (!singleLine && !multiline) return Float.NaN;
        FontSpec font = FontSpec.of(box.style);
        float glyph = pass.fonts.glyphHeight(font);
        float space = singleLine ? contentHeight : box.style.usedLineHeight();
        return box.contentY() + (space - glyph) / 2 + pass.fonts.ascent(font);
    }
}
