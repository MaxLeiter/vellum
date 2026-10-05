package dev.vellum.mod.registry;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.function.Supplier;

/**
 * A registry entry declared in common code and created lazily by the loader during its registration
 * phase (NeoForge {@code RegisterEvent}, Fabric {@code onInitialize}).
 */
public final class Entry<T> implements Supplier<T> {
    private final ResourceKey<? extends Registry<T>> registry;
    private final Identifier id;
    private final Supplier<? extends T> factory;
    private T value;

    Entry(ResourceKey<? extends Registry<T>> registry, Identifier id, Supplier<? extends T> factory) {
        this.registry = registry;
        this.id = id;
        this.factory = factory;
    }

    public ResourceKey<? extends Registry<T>> registry() {
        return registry;
    }

    public Identifier id() {
        return id;
    }

    /** Creates the value (once). Called by the loader while its registry is open. */
    public T create() {
        if (value == null) value = factory.get();
        return value;
    }

    @Override
    public T get() {
        if (value == null) throw new IllegalStateException("Registry entry " + id + " used before registration");
        return value;
    }
}
