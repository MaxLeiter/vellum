package dev.vellum.mod.client;

/**
 * Client API for how pages draw entities ({@code <entity>}). On Minecraft 26.x it has
 * {@code registerPortraitState}, which starts GUI renders of a type from a render state of the mod's own. Minecraft
 * 1.21.1 has no entity render states, and Vellum does not draw entities in 3D on it yet, so this class is empty here.
 */
public final class VellumEntities {
    private VellumEntities() {}
}
