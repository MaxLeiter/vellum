package dev.vellum.engine.style;

import org.junit.jupiter.api.Test;

import static dev.vellum.engine.testing.Page.styleOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where an {@code <entity>} goes in its box, the game's and the previewer's alike. */
class EntityFramingTest {
    private static final float EPS = 1e-4f;

    @Test
    void theBodyStandsOnTheBottomEdgeUntilPlaced() {
        // A room 1 × 2 blocks at 10 px a block, in a 40 × 60 box at (100, 200).
        assertFrame(120, 260, 10, EntityFraming.body(styleOf("color: red"), 100, 200, 40, 60, 10, 1, 2, 0));
        assertFrame(105, 220, 10, EntityFraming.body(styleOf("object-position: left top"), 100, 200, 40, 60, 10, 1, 2, 0));
        assertFrame(120, 240, 10, EntityFraming.body(styleOf("object-position: 50% 50%"), 100, 200, 40, 60, 10, 1, 2, 0));
        assertFrame(120, 257, 10, EntityFraming.body(styleOf("color: red"), 100, 200, 40, 60, 10, 1, 2, 0.3f),
                "lifted off the room's bottom by what hangs below the feet");
    }

    @Test
    void theEyesSitAtFiftyFortyUntilPlaced() {
        ComputedStyle eyes = styleOf("-mc-entity-focus: eyes");
        assertArrayEquals(new float[] {16, 12.8f}, EntityFraming.gazeOrigin(eyes, 0, 0, 32, 32), EPS);
        assertArrayEquals(new float[] {109.6f, 219.2f},
                EntityFraming.gazeOrigin(styleOf("-mc-entity-focus: eyes; object-position: 30% 60%"), 100, 200, 32, 32), EPS);
        // Their focus is where the eyes are; the body's is a third of the way down the middle, as in the inventory.
        assertArrayEquals(new float[] {16, 20}, EntityFraming.gazeOrigin(styleOf("color: red"), 0, 0, 32, 60), EPS);
    }

    @Test
    void theEyesFocusSpansPartOfTheEyeHeight() {
        // The shorter side spans 0.7 of the eye height: 2 blocks to the eyes in a 28 × 40 box is 20 px a block.
        assertFrame(14, 12 + 40, 20, EntityFraming.eyes(14, 12, 28, 40, 2, 0, 1), "the feet two blocks below the eyes");
        assertFrame(14, 12 + 80, 40, EntityFraming.eyes(14, 12, 28, 40, 2, 0, 2), "the scale zooms about the eyes");
        assertEquals(28 / EntityFraming.MIN_EYES_SPAN, EntityFraming.eyes(0, 0, 28, 40, 0.1f, 0, 1).pixelsPerBlock(), EPS,
                "at least 0.4 blocks, for eyes near the ground");
        assertFrame(14, 12 + 20, 20, EntityFraming.eyes(14, 12, 28, 40, 2, 60, 1),
                "a tilted view swings the feet about the eyes");
    }

    private static void assertFrame(float x, float y, float pixelsPerBlock, EntityFraming frame) {
        assertFrame(x, y, pixelsPerBlock, frame, null);
    }

    private static void assertFrame(float x, float y, float pixelsPerBlock, EntityFraming frame, String message) {
        assertEquals(x, frame.originX(), EPS, message);
        assertEquals(y, frame.originY(), EPS, message);
        assertEquals(pixelsPerBlock, frame.pixelsPerBlock(), EPS, message);
    }
}
