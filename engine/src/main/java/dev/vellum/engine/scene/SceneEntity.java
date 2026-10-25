package dev.vellum.engine.scene;

import dev.vellum.engine.dom.Element;

/**
 * An {@code <entity>} in a scene, where its {@code at} and {@code -mc-translate} put it (its feet, in scene
 * coordinates). The host draws it from the element's own content, turned by its {@code -mc-yaw}.
 */
public record SceneEntity(Element element, float x, float y, float z) {}
