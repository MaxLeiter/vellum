package dev.vellum.engine.dom;

/**
 * The size of a document's viewport in GUI px, and its device pixels per GUI px (Minecraft's GUI scale). A host
 * passes it when it creates a document, so scripts see the real viewport from the start, and again on resize
 * ({@link Document#setViewport}).
 */
public record Viewport(float width, float height, float devicePixelRatio) {
    /** For documents not shown anywhere yet (tests, tools). */
    public static final Viewport DEFAULT = new Viewport(320, 240, 1);
}
