package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.scene.SceneModel;
import dev.vellum.mod.client.render.McCanvas;

/**
 * {@code <scene>} on 1.21.1: laid out, with its anchors placed and its block events firing as on 26.x, but nothing
 * drawn. 1.21.1 renders blocks with a different pipeline, which scenes are not written for yet.
 */
public final class SceneContent extends McReplaced implements dev.vellum.engine.scene.SceneContent {
    private final SceneModel model;

    SceneContent(Element element) {
        super(element);
        model = new SceneModel(element);
    }

    @Override
    public SceneModel scene() {
        return model;
    }

    /** None: 1.21.1 draws no blocks. */
    public int drawnQuads() {
        return 0;
    }

    @Override
    public float intrinsicWidth() {
        return SceneModel.WIDTH;
    }

    @Override
    public float intrinsicHeight() {
        return SceneModel.HEIGHT;
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {}
}
