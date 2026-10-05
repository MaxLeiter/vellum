package dev.vellum.engine.host;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.input.Drag;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drag-to-rotate: replaced content takes the press through {@link ReplacedContent#press}, the turntable turns. */
class TurntableTest {
    private double now;
    private final Turntable turntable = new Turntable(() -> now);

    /** A 3D element of the test host that turns when it has {@code rotatable}, as Minecraft's entities do. */
    private final class Spinner implements ReplacedContent {
        private final Element element;

        Spinner(Element element) {
            this.element = element;
        }

        @Override public float intrinsicWidth() { return 40; }
        @Override public float intrinsicHeight() { return 40; }
        @Override public void paint(Canvas canvas, float x, float y, float width, float height) {}

        @Override
        public Drag press(float x, float y) {
            return element.hasAttribute("rotatable") ? turntable.press(x, y) : null;
        }
    }

    private Page page(String html) {
        TestHost host = new TestHost();
        host.replaced.put("spinner", Spinner::new);
        return host.load(html);
    }

    @Test
    void draggingTurnsAndTiltsUntilReleased() {
        Page page = page("<spinner rotatable></spinner>");
        page.down(20, 20);
        now = 10;
        page.move(30, 24);
        assertEquals(10 * Turntable.DEGREES_PER_PX, turntable.yaw(), 1e-4);
        assertEquals(4 * Turntable.DEGREES_PER_PX, turntable.pitch(), 1e-4);
        assertTrue(page.input.isActive(), "the drag holds the pointer");
        now = 200; // held still before letting go: no spin
        page.up(30, 24);
        page.move(60, 24);
        assertEquals(10 * Turntable.DEGREES_PER_PX, turntable.yaw(), 1e-4, "released: moves no longer turn it");
    }

    @Test
    void tiltIsClamped() {
        Page page = page("<spinner rotatable></spinner>");
        page.down(20, 20);
        page.move(20, 220);
        assertEquals(Turntable.MAX_PITCH, turntable.pitch(), 1e-4);
    }

    @Test
    void contentWithoutTheAttributeOrACancelledPressIsNotDragged() {
        Page page = page("<spinner></spinner> <spinner id=b rotatable onmousedown='event.preventDefault()'></spinner>");
        page.down(20, 20);
        page.move(40, 20);
        page.up(40, 20);
        float[] b = page.centre(page.byId("b"));
        page.down(b[0], b[1]);
        page.move(b[0] + 20, b[1]);
        assertEquals(0, turntable.yaw());
    }

    @Test
    void aFlickKeepsSpinningAndEasesOut() {
        Drag drag = turntable.press(0, 0);
        now = 16;
        drag.move(16, 0); // 1 px per ms
        now = 32;
        drag.move(32, 0);
        drag.end();
        float released = turntable.yaw();
        now = 32 + Turntable.EASE_MS;
        float later = turntable.yaw();
        now = 32 + 20 * Turntable.EASE_MS;
        float settled = turntable.yaw();
        assertTrue(later > released, "keeps turning after the release");
        assertTrue(settled - later < later - released, "and slows down");
        now += 1000;
        assertEquals(settled, turntable.yaw(), 0.01, "until it stops");
        turntable.press(0, 0);
        now += 1000;
        assertEquals(settled, turntable.yaw(), 0.01, "a new press stops a spin");
    }
}
