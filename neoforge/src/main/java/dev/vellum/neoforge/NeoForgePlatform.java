package dev.vellum.neoforge;

import dev.vellum.mod.platform.Platform;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class NeoForgePlatform implements Platform {
    @Override public String loaderName() { return "NeoForge"; }
    @Override public boolean isModLoaded(String modId) { return ModList.get().isLoaded(modId); }
    //? if >=26 {
    @Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.isProduction(); }
    //?} else
    /*@Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.production; }*/
    @Override public Path configDir() { return FMLPaths.CONFIGDIR.get(); }
}
