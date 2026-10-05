package dev.vellum.engine.style;

/**
 * Where an {@code <entity>} goes in its box, by {@code -mc-entity-focus} and {@code object-position}: the point its
 * origin (its feet, on its upright axis) is drawn at, and how many px a block is. The game and the previewer measure
 * their entities differently and frame them here alike.
 *
 * <ul>
 *   <li>The body focus ({@link #body}): the caller fits the entity to the box and says how much room it takes, and
 *   {@code object-position} places that room. Unset, it stands on the bottom edge, centred.</li>
 *   <li>The eyes focus ({@link #eyes}): the box's shorter side spans {@link #EYES_SPAN} of the eye height, and
 *   {@code object-position} places the eye point, the point at its eye height on its upright axis ({@code 50% 40%}
 *   unset). Turning leaves that point where it is.</li>
 * </ul>
 *
 * @param originX        where the entity's origin goes, in the box's coordinates
 * @param originY        as {@code originX}, down
 * @param pixelsPerBlock how many px a block is
 */
public record EntityFraming(float originX, float originY, float pixelsPerBlock) {
    /** With the eyes focus, the box's shorter side spans this much of the eye height... */
    public static final float EYES_SPAN = 0.7f;
    /** ...and at least this many blocks, so mobs with their eyes near the ground (silverfish) still show a head. */
    public static final float MIN_EYES_SPAN = 0.4f;
    /** Where the eyes go when {@code object-position} is unset. */
    private static final Length EYES_X = Length.PERCENT_50, EYES_Y = Length.percent(40);

    /**
     * The point an entity in the box {@code (x, y, width, height)} looks from, as {@code {x, y}}: with the eyes focus
     * its eye point, else a third of the way down the middle of the box, as vanilla's inventory has it.
     */
    public static float[] gazeOrigin(ComputedStyle s, float x, float y, float width, float height) {
        if (s.entityFocus != EntityFocus.EYES) return new float[] {x + width / 2, y + height / 3};
        Length ex = s.objectPositionX.isAuto() ? EYES_X : s.objectPositionX;
        Length ey = s.objectPositionY.isAuto() ? EYES_Y : s.objectPositionY;
        return new float[] {x + ex.resolve(width), y + ey.resolve(height)};
    }

    /**
     * The eyes focus for an entity {@code eyeHeight} blocks tall to its eyes, with its eye point at
     * ({@code eyeX}, {@code eyeY}) ({@link #gazeOrigin}) in a {@code width} × {@code height} box, sized by {@code scale}.
     * The view tilted by {@code tilt} degrees turns about the feet, so the feet move to keep the eyes in place.
     */
    public static EntityFraming eyes(float eyeX, float eyeY, float width, float height, float eyeHeight, float tilt,
                                     float scale) {
        float pixelsPerBlock = Math.min(width, height) / Math.max(MIN_EYES_SPAN, EYES_SPAN * eyeHeight) * scale;
        float drop = eyeHeight * Math.abs((float) Math.cos(Math.toRadians(tilt))) * pixelsPerBlock;
        return new EntityFraming(eyeX, eyeY + drop, pixelsPerBlock);
    }

    /**
     * The body focus for an entity fitted at {@code pixelsPerBlock} into the box {@code (x, y, width, height)}, where
     * it takes {@code roomWidth} × {@code roomHeight} blocks with its origin centred and {@code lift} blocks above
     * the room's bottom edge. {@code object-position} places the room; unset, on the bottom edge, centred.
     */
    public static EntityFraming body(ComputedStyle s, float x, float y, float width, float height, float pixelsPerBlock,
                                     float roomWidth, float roomHeight, float lift) {
        float w = roomWidth * pixelsPerBlock, h = roomHeight * pixelsPerBlock;
        float originX = x + s.objectPositionX.resolve(width - w, (width - w) / 2) + w / 2;
        float originY = y + s.objectPositionY.resolve(height - h, height - h) + h - lift * pixelsPerBlock;
        return new EntityFraming(originX, originY, pixelsPerBlock);
    }
}
