package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drag-to-rotate: pressing a replaced element with {@code rotatable} turns its {@link Turntable}. */
class TurntableTest {
    /** A replaced element of the test host, as Minecraft's entities and models are. */
    private static final class Spinner implements ReplacedContent {
        @Override public float intrinsicWidth() { return 40; }
        @Override public float intrinsicHeight() { return 40; }
        @Override public void paint(Canvas canvas, float x, float y, float width, float height) {}
    }

    private static Page page(String html) {
        TestHost host = new TestHost();
        host.replaced.put("spinner", element -> new Spinner());
        return host.load(html);
    }

    @Test
    void draggingTurnsAndTiltsByHowFarThePointerMoved() {
        // Away from the origin, so viewport and local coordinates differ.
        Page page = page("<div style='padding: 50px 0 0 120px'><spinner id=s rotatable></spinner></div>");
        Element s = page.byId("s");
        float[] at = page.centre(s);
        page.frame(0);
        page.down(at[0], at[1]);
        page.move(at[0] + 10, at[1] + 4);
        assertEquals(10 * Turntable.DEGREES_PER_PX, Turntable.yaw(s), 1e-4);
        assertEquals(4 * Turntable.DEGREES_PER_PX, Turntable.pitch(s), 1e-4);
        assertTrue(page.input.isActive(), "the drag holds the pointer");
        page.frame(200); // held still before letting go: no spin
        page.up(at[0] + 10, at[1] + 4);
        page.move(at[0] + 40, at[1] + 4);
        page.frame(400);
        assertEquals(10 * Turntable.DEGREES_PER_PX, Turntable.yaw(s), 1e-4, "released: moves no longer turn it");
    }

    @Test
    void tiltIsClamped() {
        Page page = page("<spinner id=s rotatable></spinner>");
        page.down(20, 20);
        page.move(20, 220);
        assertEquals(Turntable.MAX_PITCH, Turntable.pitch(page.byId("s")), 1e-4);
    }

    @Test
    void onlyRotatableReplacedElementsTurnAndListenersCanCancel() {
        Page page = page("<spinner id=a></spinner> <spinner id=b rotatable onmousedown='event.preventDefault()'></spinner>"
                + " <div id=c rotatable style='display: inline-block; width: 40px; height: 40px'></div>");
        for (String id : new String[] {"a", "b", "c"}) {
            float[] at = page.centre(page.byId(id));
            page.down(at[0], at[1]);
            page.move(at[0] + 20, at[1]);
            page.up(at[0] + 20, at[1]);
            assertEquals(0, Turntable.yaw(page.byId(id)), id);
        }
        assertFalse(page.input.isActive());
    }

    @Test
    void aFlickKeepsSpinningOnTheFrameClockAndEasesOut() {
        Page page = page("<div style='margin-left: 200px'><spinner id=s rotatable></spinner></div>");
        Element s = page.byId("s");
        float[] at = page.centre(s);
        page.frame(0);
        page.down(at[0], at[1]);
        page.move(at[0] + 16, at[1]); // 1 px per ms
        page.frame(16);
        page.move(at[0] + 32, at[1]);
        page.frame(32);
        page.up(at[0] + 32, at[1]);
        float released = Turntable.yaw(s);
        assertEquals(32 * Turntable.DEGREES_PER_PX, released, 1e-4);

        double later = 32 + Turntable.EASE_MS;
        assertTrue(page.doc.needsFrame(later), "a spinning turntable keeps frames coming");
        page.frame(later);
        float turned = Turntable.yaw(s);
        assertTrue(turned > released, "keeps turning after the release");
        double settledAt = 32 + 20 * Turntable.EASE_MS;
        page.frame(settledAt).paint();
        float settled = Turntable.yaw(s);
        assertTrue(settled - turned < turned - released, "and slows down");
        assertFalse(page.doc.needsFrame(settledAt + 16), "until it stops");

        page.down(at[0], at[1]);
        page.up(at[0], at[1]);
        page.frame(settledAt + 1000);
        assertEquals(settled, Turntable.yaw(s), 0.01, "a new press stops a spin");
    }

    @Test
    void removingAHeldElementLetsTheDocumentIdle() {
        Page page = page("<spinner id=s rotatable></spinner>");
        page.frame(0);
        page.down(20, 20);
        page.byId("s").remove();
        page.frame(16);
        assertFalse(page.input.isActive());
    }
}
