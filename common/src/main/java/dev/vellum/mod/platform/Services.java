package dev.vellum.mod.platform;

import java.util.ServiceLoader;

/** Locates the loader-specific implementation of a common interface (META-INF/services). */
public final class Services {
    public static final Platform PLATFORM = load(Platform.class);

    public static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No service implementation for " + clazz.getName()));
    }

    private Services() {}
}
