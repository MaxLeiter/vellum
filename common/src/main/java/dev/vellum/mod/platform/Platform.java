package dev.vellum.mod.platform;

import java.nio.file.Path;

/** Loader-specific facilities that common code needs. */
public interface Platform {
    boolean isDevelopmentEnvironment();

    Path configDir();

    boolean isModLoaded(String modId);
}
