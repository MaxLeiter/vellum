package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Canvas;

/**
 * {@code <scene>} on 1.21.1: laid out, with its anchors placed and its block events firing as on 26.x, but nothing
 * drawn. 1.21.1 renders blocks with a different pipeline, which scenes are not written for yet.
 */
public final class SceneContent extends dev.vellum.engine.scene.SceneContent {
    SceneContent(Element element) {
        super(element);
    }

    /** None: it draws no blocks, so it names none either. */
    @Override
    public boolean showsTooltip() {
        return false;
    }

    @Override
    public void paint(Canvas canvas, float x, float y, float width, float height) {}
}
