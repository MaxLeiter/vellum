package dev.vellum.mod.registry;

import dev.vellum.mod.Constants;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** All of the mod's registry entries, in declaration order. Loaders iterate this to register them. */
public final class VellumRegistry {
    private static final List<Entry<?>> ENTRIES = new ArrayList<>();

    private VellumRegistry() {}

    public static <T> Entry<T> add(ResourceKey<? extends Registry<T>> registry, String path, Supplier<? extends T> factory) {
        Entry<T> e = new Entry<>(registry, Constants.id(path), factory);
        ENTRIES.add(e);
        return e;
    }

    public static List<Entry<?>> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }
}
