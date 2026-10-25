package dev.vellum.neoforge;

import dev.vellum.mod.platform.Platform;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class NeoForgePlatform implements Platform {
    //? if >=26 {
    @Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.isProduction(); }
    //?} else
    //@Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.production; }
    @Override public Path configDir() { return FMLPaths.CONFIGDIR.get(); }
}
