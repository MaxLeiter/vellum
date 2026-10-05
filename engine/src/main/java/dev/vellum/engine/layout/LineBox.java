package dev.vellum.engine.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of an inline formatting context. Coordinates are relative to the owning block box's border-box origin.
 * {@code baseline} is the y of the alphabetic baseline in the same space.
 */
public final class LineBox {
    public float x, y, width, height, baseline;
    /** Fragments in visual (left-to-right) order. */
    public final List<Fragment> fragments = new ArrayList<>();

    LineBox(float x, float y, float width, float height, float baseline) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.baseline = baseline;
    }
}
