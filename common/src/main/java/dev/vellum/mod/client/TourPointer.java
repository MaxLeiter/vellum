package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The dev tour's pointer ({@link DevTour}): where it is, and the motions that move it, frame by frame rather than tick
 * by tick, so the drawn cursor ({@link TourCursor}), hover effects, gazes and turntable drags move smoothly in a 60 fps
 * recording. A motion follows a gentle arc (a quadratic Bézier bowed to one side of the straight line) with eased
 * timing, as a hand moves a mouse. The position is pushed into Minecraft's mouse handler every frame, and again after
 * a screen opens (opening one resyncs the handler to wherever the system pointer is). Buttons and the wheel go
 * through the mouse handler too, the path real input takes. Render thread only.
 */
final class TourPointer {
    /** Timing curves for a motion's progress. */
    enum Ease {
        /** Cubic ease-in-out: sets off and arrives gently. */
        IN_OUT {
            @Override
            double at(double t) {
                return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            }
        },
        /** Quadratic ease-in: still speeding up at the end, for a flick that lets go while moving. */
        IN {
            @Override
            double at(double t) {
                return t * t;
            }
        },
        /** Sine ease-in-out: an even, unhurried sweep. */
        SWEEP {
            @Override
            double at(double t) {
                return (1 - Math.cos(Math.PI * t)) / 2;
            }
        };

        abstract double at(double t);
    }

    private static final MouseButtonInfo LEFT = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);
    /** Arcs bow out by this fraction of their length unless a motion asks for another. */
    static final double BEND = 0.14;

    /** Where the pointer is, in GUI px; NaN before {@link #place}. */
    private static double x = Double.NaN, y = Double.NaN;
    private static @Nullable Motion motion;
    private static boolean pressed;

    /** A move from (x0, y0) through the control point (cx, cy) to (x1, y1), over {@code nanos} from {@code start}. */
    private record Motion(double x0, double y0, double cx, double cy, double x1, double y1, long start, long nanos,
                          Ease ease, @Nullable Runnable done) {
        double progress(long now) {
            return Math.clamp((double) (now - start) / nanos, 0, 1);
        }
    }

    private TourPointer() {}

    static double x() {
        return x;
    }

    static double y() {
        return y;
    }

    /** Puts the pointer at (x, y) GUI px at once, ending any motion without its end action. */
    static void place(double gx, double gy) {
        motion = null;
        x = gx;
        y = gy;
        apply();
    }

    /**
     * Starts moving the pointer to (toX, toY) GUI px over {@code seconds}, along an arc bowed out by {@code bend} of
     * its length (sagging downwards, or to the right when the move is mostly vertical), timed by {@code ease}. Runs
     * {@code done} in the frame it arrives. A motion still running is finished first.
     */
    static void glide(double toX, double toY, double seconds, double bend, Ease ease, @Nullable Runnable done) {
        finish();
        double dx = toX - x, dy = toY - y, length = Math.hypot(dx, dy);
        // The perpendicular that points down (or right): a wrist pivoting below the hand draws arcs that sag.
        double nx = -dy, ny = dx;
        if (Math.abs(dx) >= Math.abs(dy) ? ny < 0 : nx < 0) {
            nx = -nx;
            ny = -ny;
        }
        double k = length < 8 ? 0 : bend; // short hops go straight
        double cx = (x + toX) / 2 + nx * k, cy = (y + toY) / 2 + ny * k;
        motion = new Motion(x, y, cx, cy, toX, toY, Util.getNanos(), Math.max(1, (long) (seconds * 1e9)), ease, done);
    }

    /** Whether a motion is under way. */
    static boolean moving() {
        return motion != null;
    }

    /** Every frame: moves the pointer along the motion under way, and keeps it where the tour put it. */
    static void update() {
        Motion m = motion;
        if (m != null) {
            double t = m.progress(Util.getNanos()), k = m.ease.at(t), u = 1 - k;
            x = u * u * m.x0 + 2 * u * k * m.cx + k * k * m.x1;
            y = u * u * m.y0 + 2 * u * k * m.cy + k * k * m.y1;
            if (t >= 1) arrive(m);
        }
        apply();
    }

    /** Ends the motion under way at its destination, running its end action. */
    static void finish() {
        Motion m = motion;
        if (m == null) return;
        x = m.x1;
        y = m.y1;
        apply();
        arrive(m);
    }

    private static void arrive(Motion m) {
        if (motion != m) return;
        motion = null;
        if (m.done != null) m.done.run();
    }

    /**
     * Puts the mouse handler's pointer where the tour's is, as a move event: the open screen gets it as a real move,
     * and a page follows it at its next frame in any case. Nothing while the game has the mouse.
     */
    static void apply() {
        Minecraft mc = Minecraft.getInstance();
        if (Double.isNaN(x) || mc.mouseHandler.isMouseGrabbed()) return;
        Window w = mc.getWindow();
        double sx = x * w.getScreenWidth() / w.getGuiScaledWidth(), sy = y * w.getScreenHeight() / w.getGuiScaledHeight();
        MouseHandler mouse = mc.mouseHandler;
        if (mouse.xpos() != sx || mouse.ypos() != sy) mouse.onMove(w.handle(), sx, sy, 0, 0);
        // Keeps the frame rate up: with no input for a minute, the game would drop to 30 fps.
        mc.getFramerateLimitTracker().onInputReceived();
    }

    // ---- Buttons and wheel ----

    /** Presses the left button where the pointer is. */
    static void press() {
        button(InputConstants.PRESS);
    }

    /** Releases the left button where the pointer is. */
    static void release() {
        button(InputConstants.RELEASE);
    }

    /**
     * Whether the tour holds the left button down. (The mouse handler's own flag follows the button only while no
     * screen is open, for the game's attack and use keys.)
     */
    static boolean pressed() {
        return pressed;
    }

    private static void button(int action) {
        finish();
        pressed = action == InputConstants.PRESS;
        apply();
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.onButton(mc.getWindow().handle(), LEFT, action);
    }

    /** Turns the wheel where the pointer is; positive notches scroll down. */
    static void wheel(double notches) {
        finish();
        apply();
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, -notches);
    }
}
