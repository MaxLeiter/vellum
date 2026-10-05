package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;

/**
 * Drag-to-rotate for replaced elements with {@code rotatable} ({@code <entity>}, {@code <model>}): dragging
 * horizontally turns the content, dragging vertically tilts the view (clamped to ±{@link #MAX_PITCH}°), and a turn
 * that is released while moving keeps spinning and eases out. The angles are offsets that the content adds to its
 * {@code -mc-yaw} and {@code -mc-pitch} ({@link #yaw(Element)}, {@link #pitch(Element)}); they follow the same sign
 * conventions (dragging right turns the front to the right, dragging down looks from further above).
 *
 * <p>One instance per element, kept in {@code element.controlState} from its first press. Times come from the
 * document's frame clock, and reads are pure functions of it, so painting needs no per-frame update. The input
 * handler keeps frames coming while a released turntable still spins ({@link #moving}).
 */
public final class Turntable {
    /** Degrees per GUI px dragged. */
    public static final float DEGREES_PER_PX = 1.5f;
    /** The furthest a drag tilts the view, in degrees either way. */
    public static final float MAX_PITCH = 60;
    /** How fast a released spin slows down: its speed falls by e every this many ms. */
    static final double EASE_MS = 400;
    /** A release this long after the last movement does not spin. */
    static final double STILL_MS = 80;
    /** A spin with less than this many degrees left to turn has stopped. */
    private static final double SETTLED = 0.05;

    private final Element element;
    private float yaw, pitch;
    /** The yaw speed in degrees per ms: smoothed while dragging, the spin's initial speed after a release. */
    private double speed;
    /** When the drag was released, or NaN while it is held (and before the first press). */
    private double releasedAt = Double.NaN;
    private float lastX, lastY;
    /** When the pointer last moved. */
    private double lastMove;
    /** The time and yaw of the last speed sample. */
    private double sampledAt;
    private float sampledYaw;

    private Turntable(Element element) {
        this.element = element;
    }

    /** The yaw offset dragging gave {@code element}'s content, in degrees (unbounded: spins keep counting). */
    public static float yaw(Element element) {
        return element.controlState instanceof Turntable t ? t.yaw() : 0;
    }

    /** The pitch offset dragging gave {@code element}'s content, in degrees within ±{@link #MAX_PITCH}. */
    public static float pitch(Element element) {
        return element.controlState instanceof Turntable t ? t.pitch : 0;
    }

    /** The turntable of a rotatable element, created on first use. */
    static Turntable of(Element element) {
        Turntable turntable = element.controlState instanceof Turntable t ? t : new Turntable(element);
        element.controlState = turntable;
        return turntable;
    }

    Element element() {
        return element;
    }

    /** Held, or released and still spinning. */
    boolean moving() {
        if (Double.isNaN(releasedAt)) return true;
        return Math.abs(speed) * EASE_MS * Math.exp(-(now() - releasedAt) / EASE_MS) > SETTLED;
    }

    private float yaw() {
        if (Double.isNaN(releasedAt)) return yaw;
        double t = now() - releasedAt;
        return (float) (yaw + speed * EASE_MS * (1 - Math.exp(-t / EASE_MS)));
    }

    /** Takes hold at a viewport point (stopping any spin); the drag follows the pointer until it is released. */
    Drag press(float x, float y) {
        yaw = yaw();
        speed = 0;
        releasedAt = Double.NaN;
        lastX = x;
        lastY = y;
        lastMove = sampledAt = now();
        sampledYaw = yaw;
        return new Drag() {
            @Override
            public void move(float x, float y) {
                drag(x, y);
            }

            @Override
            public void end() {
                release();
            }
        };
    }

    private void drag(float x, float y) {
        double now = now();
        if (x != lastX || y != lastY) {
            yaw += (x - lastX) * DEGREES_PER_PX;
            pitch = Math.clamp(pitch + (y - lastY) * DEGREES_PER_PX, -MAX_PITCH, MAX_PITCH);
            lastX = x;
            lastY = y;
            lastMove = now;
        }
        // The frame clock stands still between frames, and the input handler re-sends the pointer every frame: sample
        // the speed once a frame, over the turn since the last sample.
        if (now > sampledAt) {
            speed = 0.6 * (yaw - sampledYaw) / (now - sampledAt) + 0.4 * speed;
            sampledAt = now;
            sampledYaw = yaw;
        }
    }

    private void release() {
        double now = now();
        if (now - lastMove > STILL_MS) speed = 0;
        releasedAt = now;
    }

    private double now() {
        return element.ownerDocument().scheduler().now();
    }
}
