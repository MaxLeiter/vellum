package dev.vellum.fabric;

import dev.vellum.mod.platform.Platform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class FabricPlatform implements Platform {
    @Override public String loaderName() { return "Fabric"; }
    @Override public boolean isModLoaded(String modId) { return FabricLoader.getInstance().isModLoaded(modId); }
    @Override public boolean isDevelopmentEnvironment() { return FabricLoader.getInstance().isDevelopmentEnvironment(); }
    @Override public Path configDir() { return FabricLoader.getInstance().getConfigDir(); }
}
