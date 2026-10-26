package dev.vellum.engine.scene;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;

/**
 * The content of a {@code <scene>}, which a host provides as a replaced element (Minecraft's {@code scene}) by
 * extending this and drawing what its {@link SceneModel} describes. The engine keeps the model up to date every frame
 * after layout, lays out and places its anchors, picks the block under the pointer and fires the scene's events.
 */
public abstract class SceneContent implements ReplacedContent {
    private final SceneModel model;

    protected SceneContent(Element element) {
        model = new SceneModel(element);
    }

    public final SceneModel scene() { return model; }

    /** None: a scene is as large as its box, 160×120 unless CSS sizes it ({@code ua.css}). */
    @Override public float intrinsicWidth() { return Float.NaN; }
    @Override public float intrinsicHeight() { return Float.NaN; }

    /** While a block is hovered, its name ({@link SceneModel#tooltipBlock}). */
    @Override
    public boolean showsTooltip() {
        return model.tooltipBlock() != null;
    }

    /** Its {@code <scene-anchor>}s. */
    @Override
    public final boolean laysOutChild(Element child) {
        return child.tagName().equals(SceneModel.ANCHOR);
    }

    @Override
    public final boolean afterLayout() {
        return model.update();
    }

    @Override
    public final void attributeChanged(String name) {
        model.attributeChanged(name);
    }

    @Override
    public final void subtreeChanged(Element element, String attribute) {
        model.subtreeChanged(element, attribute);
    }
}
