package dev.vellum.engine.layout;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.input.Controls;

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
     * Text controls and selects have their text's baseline, where {@code Controls} draws it: single-line ones
     * centred in the content box, a textarea's first line from its top. Other leaves have none (they align by their
     * bottom margin edge).
     */
    private float controlBaseline(LayoutBox box, float contentHeight) {
        if (box.isReplaced()) return Float.NaN;
        Element e = box.element;
        if (!e.tagName().equals("select") && !e.isTextControl()) return Float.NaN;
        FontSpec font = FontSpec.of(box.style);
        float lineHeight = Controls.lineHeight(e, box.style, contentHeight);
        return Controls.glyphTop(box.contentY(), lineHeight, pass.text.glyphHeight(font), 0) + pass.text.ascent(font);
    }
}
