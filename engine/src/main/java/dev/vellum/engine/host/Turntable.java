package dev.vellum.engine.host;

import dev.vellum.engine.input.Drag;

import java.util.function.DoubleSupplier;

/**
 * Drag-to-rotate for 3D replaced content ({@code <entity rotatable>}, {@code <model rotatable>}): dragging
 * horizontally turns the content, dragging vertically tilts the view (clamped to ±{@link #MAX_PITCH}°), and a turn
 * that is released while moving keeps spinning and eases out. The angles are offsets that the content adds to its
 * {@code -mc-yaw} and {@code -mc-pitch}; they follow the same sign conventions (dragging right turns the front to the
 * right, dragging down looks from further above).
 *
 * <p>Reads are pure functions of the clock, so painting needs no per-frame update.
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

    private final DoubleSupplier clock;
    private float yaw, pitch;
    /** The yaw speed in degrees per ms: smoothed while dragging, the spin's initial speed after a release. */
    private double speed;
    private double releasedAt = Double.NaN;
    private boolean dragging;
    private float lastX, lastY;
    private double lastMove;

    /** @param clock the host's monotonic clock in ms (the one documents are framed with) */
    public Turntable(DoubleSupplier clock) {
        this.clock = clock;
    }

    /** The yaw offset now, in degrees (unbounded: spins keep counting). */
    public float yaw() {
        if (dragging || Double.isNaN(releasedAt)) return yaw;
        double t = clock.getAsDouble() - releasedAt;
        return (float) (yaw + speed * EASE_MS * (1 - Math.exp(-t / EASE_MS)));
    }

    /** The pitch offset, in degrees within ±{@link #MAX_PITCH}. */
    public float pitch() {
        return pitch;
    }

    /** Takes hold at a viewport point (stopping any spin); the drag follows the pointer until it is released. */
    public Drag press(float x, float y) {
        yaw = yaw();
        speed = 0;
        releasedAt = Double.NaN;
        dragging = true;
        lastX = x;
        lastY = y;
        lastMove = clock.getAsDouble();
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
        if (x == lastX && y == lastY) return; // hosts re-send the pointer every tick while it is held
        double now = clock.getAsDouble(), dt = Math.max(1, now - lastMove);
        float turn = (x - lastX) * DEGREES_PER_PX;
        yaw += turn;
        pitch = Math.clamp(pitch + (y - lastY) * DEGREES_PER_PX, -MAX_PITCH, MAX_PITCH);
        speed = 0.6 * (turn / dt) + 0.4 * speed;
        lastX = x;
        lastY = y;
        lastMove = now;
    }

    private void release() {
        dragging = false;
        double now = clock.getAsDouble();
        if (now - lastMove > STILL_MS) speed = 0;
        releasedAt = now;
    }
}
