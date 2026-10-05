package dev.vellum.engine.paint;

import java.util.Arrays;

/**
 * Clip rectangles for a host whose renderer clips with scissors on whole units (Minecraft's GUI px). Each clip is
 * rounded and intersected with the clip below it, the first with the area the renderer can draw. A clip with nothing
 * left is kept here but must not reach the renderer, which rejects zero-sized scissors (Minecraft only at draw time,
 * after clamping them to the framebuffer: a scissor wholly below the window is zero tall there); while one is active,
 * nothing should be drawn. The host mirrors {@code Canvas.save}/{@code restore} with {@link #depth()} and
 * {@link #pop()}.
 */
public final class ScissorStack {
    /** {x0, y0, x1, y1} per clip, the drawable area first; an empty clip has x1 = x0. */
    private int[] rects = new int[4 * 16];
    private int depth;
    /** Empty clips on the stack. */
    private int empty;

    /** A stack over the area the renderer draws: [x0, x1) × [y0, y1). */
    public ScissorStack(int x0, int y0, int x1, int y1) {
        set(0, x0, y0, x1, y1);
    }

    /**
     * Pushes the clip of a rectangle (rounded to whole units). Returns true when the host must push it as a scissor
     * ({@link #left()}..{@link #bottom()}), false when nothing of it is left: then nothing draws until it is popped.
     */
    public boolean push(float x0, float y0, float x1, float y1) {
        int o = 4 * depth++;
        if (rects.length < o + 8) rects = Arrays.copyOf(rects, rects.length * 2);
        int l = Math.max(Math.round(x0), rects[o]), t = Math.max(Math.round(y0), rects[o + 1]);
        int r = Math.min(Math.round(x1), rects[o + 2]), b = Math.min(Math.round(y1), rects[o + 3]);
        boolean visible = empty == 0 && l < r && t < b;
        if (visible) set(depth, l, t, r, b);
        else {
            set(depth, l, t, l, t);
            empty++;
        }
        return visible;
    }

    /** Pops the last clip; true when it was pushed as a scissor, which the host pops too. */
    public boolean pop() {
        if (depth == 0) throw new IllegalStateException("Scissor stack underflow");
        int o = 4 * depth--;
        boolean visible = rects[o] < rects[o + 2];
        if (!visible) empty--;
        return visible;
    }

    /** Clips pushed and not popped. */
    public int depth() {
        return depth;
    }

    /** Whether an empty clip is active: nothing shows. */
    public boolean clippedAway() {
        return empty > 0;
    }

    /** The current clip (the drawable area when none is pushed). */
    public int left() { return rects[4 * depth]; }
    public int top() { return rects[4 * depth + 1]; }
    public int right() { return rects[4 * depth + 2]; }
    public int bottom() { return rects[4 * depth + 3]; }

    private void set(int index, int x0, int y0, int x1, int y1) {
        int o = 4 * index;
        rects[o] = x0;
        rects[o + 1] = y0;
        rects[o + 2] = x1;
        rects[o + 3] = y1;
    }
}
