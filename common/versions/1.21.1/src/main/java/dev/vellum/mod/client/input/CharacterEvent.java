package dev.vellum.mod.client.input;

/** A character typed, on Minecraft 1.21.1. */
public record CharacterEvent(int codepoint) {
    public String codepointAsString() {
        return Character.toString(codepoint);
    }
}
