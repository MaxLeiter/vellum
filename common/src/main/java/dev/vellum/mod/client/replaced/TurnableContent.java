package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.Turntable;

/**
 * 3D content ({@code <entity>}, {@code <model>}): turned, viewed and sized by the element's {@code -mc-yaw},
 * {@code -mc-pitch} and {@code -mc-model-scale}, plus what dragging it added when it has {@code rotatable}
 * ({@link Turntable}), and tinted by {@code -mc-tint}. Read while drawing, when the element has a style.
 */
abstract class TurnableContent extends McReplaced {
    protected TurnableContent(Element element) {
        super(element);
    }

    /** Degrees; positive turns the front to the right. */
    protected final float yaw() {
        return element.computedStyle().modelYaw + Turntable.yaw(element);
    }

    /** Degrees; positive views from above. */
    protected final float pitch() {
        return element.computedStyle().modelPitch + Turntable.pitch(element);
    }

    /** Multiplies the size that fits the box. */
    protected final float modelScale() {
        return element.computedStyle().modelScale;
    }

    /** {@code -mc-tint}: multiplies the picture (ARGB). */
    protected final int tint() {
        return element.computedStyle().tint;
    }
}
