package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Turntable;
import dev.vellum.engine.input.Drag;
import dev.vellum.engine.style.ComputedStyle;
import net.minecraft.util.Util;

/**
 * 3D content ({@code <entity>}, {@code <model>}): turned, viewed and sized by the element's {@code -mc-yaw},
 * {@code -mc-pitch} and {@code -mc-model-scale}, plus what dragging it added when it has {@code rotatable}, and
 * tinted by {@code -mc-tint}.
 */
abstract class TurnableContent extends McReplaced {
    /** On the clock documents are framed with ({@code DocumentDriver}), so spins ease out in step with animations. */
    private final Turntable turntable = new Turntable(Util::getMillis);

    protected TurnableContent(Element element) {
        super(element);
    }

    @Override
    public final Drag press(float x, float y) {
        return element.hasAttribute("rotatable") ? turntable.press(x, y) : null;
    }

    /** Degrees; positive turns the front to the right. */
    protected final float yaw() {
        return style().modelYaw + turntable.yaw();
    }

    /** Degrees; positive views from above. */
    protected final float pitch() {
        return style().modelPitch + turntable.pitch();
    }

    /** Multiplies the size that fits the box. */
    protected final float modelScale() {
        return style().modelScale;
    }

    /** {@code -mc-tint}: multiplies the picture (ARGB). */
    protected final int tint() {
        return style().tint;
    }

    private ComputedStyle style() {
        ComputedStyle s = element.style;
        return s == null ? ComputedStyle.INITIAL : s;
    }
}
