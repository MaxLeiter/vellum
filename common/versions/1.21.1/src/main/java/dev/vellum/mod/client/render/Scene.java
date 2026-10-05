package dev.vellum.mod.client.render;

/**
 * What a 3D picture shows ({@code <entity>}, {@code <model>}). Minecraft 1.21.1 has no picture-in-picture renderer,
 * and Vellum does not draw 3D content on it yet ({@code McClient.SCENES} is false): {@code <entity>} and
 * {@code <model>} draw their 2D fallbacks instead, and no scene is ever made.
 */
public final class Scene {
    private Scene() {}
}
