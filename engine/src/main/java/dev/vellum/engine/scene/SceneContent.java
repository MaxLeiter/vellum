package dev.vellum.engine.scene;

import dev.vellum.engine.host.ReplacedContent;

/**
 * The content of a {@code <scene>}, which a host provides as a replaced element (Minecraft's {@code scene}). The
 * engine keeps its {@link SceneModel} up to date every frame, lays out and places its anchors, picks the block under
 * the pointer and fires the scene's events; the host draws what the model describes.
 */
public interface SceneContent extends ReplacedContent {
    SceneModel scene();
}
