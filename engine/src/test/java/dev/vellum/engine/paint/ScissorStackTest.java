package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scissors as Minecraft's renderer needs them: never empty, never outside the area it draws. */
class ScissorStackTest {
    @Test
    void clipsIntersectWithTheClipBelowAndTheDrawableArea() {
        ScissorStack clips = new ScissorStack(0, 0, 160, 120);
        assertTrue(clips.push(10.4f, -5, 200, 50.6f));
        assertArrayEquals(new int[] {10, 0, 160, 51}, rect(clips));
        assertTrue(clips.push(0, 40, 20, 100));
        assertArrayEquals(new int[] {10, 40, 20, 51}, rect(clips));
        assertTrue(clips.pop());
        assertTrue(clips.pop());
        assertEquals(0, clips.depth());
        assertThrows(IllegalStateException.class, clips::pop);
    }

    @Test
    void anEmptyClipIsNotPushedAndHidesWhatItHoldsUntilPopped() {
        ScissorStack clips = new ScissorStack(0, 0, 160, 120);
        assertFalse(clips.push(10, 20, 10, 40), "zero wide");
        assertTrue(clips.clippedAway());
        assertFalse(clips.push(0, 0, 100, 100), "inside an empty clip");
        assertFalse(clips.pop());
        assertFalse(clips.pop());
        assertFalse(clips.clippedAway());
        assertTrue(clips.push(0, 0, 100, 100));
    }

    /**
     * The 0.1.2 crash: after {@code Window.setWindowed(320, 240)} a frame still lays out and extracts at the old GUI
     * size (427×240 at scale 2) while the framebuffer is already 320×240, which the renderer draws as 160×120 GUI px.
     * A text field's clip at y 130 was on the GUI screen but below the framebuffer, where the renderer clamped it to
     * zero height and threw. The drawable area makes it empty, so it is never pushed.
     */
    @Test
    void aClipBelowTheFramebufferIsEmpty() {
        Page page = new TestHost().load("<input id=i value='text longer than the field' style='position: absolute; top: 130px; left: 10px; width: 60px'>",
                427, 240);
        Element input = page.byId("i");
        RecordingCanvas.Call clip = page.paint().ops("clipRect").getFirst(); // the field's text
        assertTrue(clip.y() >= 120 && clip.y() + clip.h() <= 240, "on the old GUI screen, below the framebuffer");
        assertEquals(Coordinates.boundingRect(input.box)[1] + 1, clip.y(), 1, "inside the field's border");
        ScissorStack clips = new ScissorStack(0, 0, Math.ceilDiv(320, 2), Math.ceilDiv(240, 2));
        assertFalse(clips.push(clip.x(), clip.y(), clip.x() + clip.w(), clip.y() + clip.h()));
        assertTrue(clips.clippedAway());
    }

    private static int[] rect(ScissorStack clips) {
        return new int[] {clips.left(), clips.top(), clips.right(), clips.bottom()};
    }
}
