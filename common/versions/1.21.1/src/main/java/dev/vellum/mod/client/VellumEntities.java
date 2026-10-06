package dev.vellum.mod.client;

/**
 * Client API for how pages draw entities ({@code <entity>}). On Minecraft 26.x it has
 * {@code registerPortraitState}, which starts GUI renders of a type from a render state of the mod's own. Minecraft
 * 1.21.1 has no entity render states (Vellum poses the entity itself for the draw), so this class has nothing here.
 */
public final class VellumEntities {
    private VellumEntities() {}
}
