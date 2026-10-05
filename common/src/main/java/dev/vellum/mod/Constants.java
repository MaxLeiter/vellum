package dev.vellum.mod;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Constants {
    public static final String MOD_ID = "vellum";
    public static final String MOD_NAME = "Vellum";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {}

    /** {@code vellum:<path>}. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
