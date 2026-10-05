package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import dev.vellum.mod.platform.Services;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Client settings from {@code config/vellum-client.properties}, written with defaults on first start. */
public final class VellumConfig {
    /** Pages see {@code prefers-reduced-motion: reduce} (and should skip or shorten animations). */
    public static boolean reducedMotion = false;

    private VellumConfig() {}

    static void load() {
        Path file = Services.PLATFORM.configDir().resolve("vellum-client.properties");
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (Reader in = Files.newBufferedReader(file)) {
                    p.load(in);
                }
            }
            reducedMotion = Boolean.parseBoolean(p.getProperty("reducedMotion", "false"));
            p.setProperty("reducedMotion", Boolean.toString(reducedMotion));
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file)) {
                p.store(out, "Vellum client settings. reducedMotion: UIs see prefers-reduced-motion: reduce");
            }
        } catch (IOException e) {
            Constants.LOG.warn("Vellum: cannot read or write {}: {}", file, e.toString());
        }
    }
}
