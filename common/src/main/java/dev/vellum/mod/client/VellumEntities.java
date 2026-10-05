package dev.vellum.mod.client;

import dev.vellum.mod.client.replaced.EntityPortrait;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * Client API for how pages draw entities ({@code <entity>}).
 *
 * <pre>{@code
 * // In client setup: draw automatons in screens without the bubbles their renderer adds over their heads.
 * VellumEntities.registerPortraitState(MyEntities.AUTOMATON.get(), AutomatonRenderer::portraitState);
 * }</pre>
 */
public final class VellumEntities {
    private VellumEntities() {}

    /**
     * Makes GUI renders of {@code type} start from the render state {@code state} returns, given the entity and the
     * partial tick, instead of the one its renderer creates. Use it to leave out what your renderer draws for the
     * world only (speech bubbles, labels, effects). Returning null falls back to the renderer's state for that frame.
     *
     * <p>Vellum then poses the state as it does any other: it clears the shadow, outline, name tag, score, leashes
     * and passenger offset, lights it full bright, and sets the body's turn ({@code bodyRot}), the walk animation and
     * the size ({@code scale} becomes 1; the bounding box and eye height are divided by it). The head ({@code yRot},
     * {@code xRot}) is only set with {@code follow-mouse}: otherwise it stays as your state has it. The body fit
     * measures this state too, so whatever it leaves out takes no room in the box.
     *
     * <p>Registering a type again replaces its function. Call during client setup; the function runs on the render
     * thread, once per frame for every {@code <entity>} on screen showing that type.
     */
    public static <T extends Entity> void registerPortraitState(EntityType<T> type,
                                                                BiFunction<? super T, Float, ? extends @Nullable EntityRenderState> state) {
        EntityPortrait.registerState(type, state);
    }
}
