package dev.vellum.mod.platform;

import java.nio.file.Path;

/** Loader-specific facilities that common code needs. */
public interface Platform {
    String loaderName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    Path configDir();
}
