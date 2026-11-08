package dev.vellum.engine.paint;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the painter puts on the device pixel grid stays on it, and a 1px border or outline is one CSS px on every
 * side, wherever layout puts the box: on device pixels, a quarter off, or exactly halfway between two (a tie, where
 * rounding direction matters), inside transformed contexts and inline. #5, #6 and #10 were each a way to break this.
 */
class PixelGridTest {
    private static final float[] DEVICE_PIXELS = {1, 1 / 2f, 1 / 3f, 1 / 4f};

    /** A page with a box of every kind, offset by {@code o} and grown by {@code s} (fractions of a device pixel). */
    private static String page(float o, float s) {
        return """
                <style>
                  .b { position: absolute; box-sizing: border-box; border: 1px solid #f00; outline: 1px solid #00f; }
                </style>
                <div class=b style="left: %1$spx; top: %2$spx; width: %3$spx; height: %4$spx; padding: %5$spx;
                     outline-offset: %6$spx; overflow: hidden; background: #0f0; background-clip: content-box">
                  text <span style="border: 1px solid #f0f; padding: 0 %6$spx">inline</span>
                </div>
                <div class=b style="left: %7$spx; top: %8$spx; width: %3$spx; height: %4$spx;
                     transform: translate(%6$spx, %9$spx)"><button>ok</button></div>
                <div class=b style="left: 50%%; top: 50%%; width: 50%%; height: 50%%; transform: translate(-50%%, -50%%)"></div>
                <div class=b style="right: %6$spx; bottom: %9$spx; width: %3$spx; height: %4$spx"></div>
                """.formatted(10 + o, 20 + o, 100 + s, 40 + s, 1 + o, o, 150 + o, 70 + s, s);
    }

    @Test
    void edgesStayOnDevicePixelsAndBordersStayEven() {
        List<String> problems = new ArrayList<>();
        int pages = 0;
        for (float dp : DEVICE_PIXELS) {
            // On a device pixel, halfway between two (a tie), a quarter off, and somewhere arbitrary.
            for (float o : new float[] {0, dp / 2, dp / 4, 0.37f}) {
                for (float s : new float[] {0, dp / 2, dp * 1.5f, 0.61f}) {
                    // Odd viewport sizes put percentages and centring on ties too.
                    for (float[] size : new float[][] {{320, 240}, {321, 241}, {320.5f, 240.5f}}) {
                        Page page = new TestHost().load(page(o, s), size[0], size[1]);
                        RecordingCanvas canvas = new RecordingCanvas();
                        canvas.devicePixel = dp;
                        page.paint(canvas);
                        check(canvas, dp, "dp " + dp + " offset " + o + " size +" + s + " viewport " + size[0] + "x" + size[1],
                                problems);
                        pages++;
                    }
                }
            }
        }
        assertTrue(pages > 0);
        assertEquals(List.of(), problems.stream().limit(20).toList(), problems.size() + " problems");
    }

    private static void check(RecordingCanvas canvas, float dp, String where, List<String> problems) {
        int rings = 0;
        for (RecordingCanvas.Call c : canvas.calls) {
            switch (c.op()) {
                case "fillRect", "clipRect", "fillBorder", "drawImage", "drawSprite" -> {
                    onGrid(c.x(), dp, c, where, problems);
                    onGrid(c.y(), dp, c, where, problems);
                    onGrid(c.x() + c.w(), dp, c, where, problems);
                    onGrid(c.y() + c.h(), dp, c, where, problems);
                }
                case "drawText" -> {
                    onGrid(c.x(), dp, c, where, problems);
                    onGrid(c.y(), dp, c, where, problems);
                }
                default -> {}
            }
            if (c.op().equals("fillBorder")) {
                rings++;
                for (int side = 0; side < 4; side++) {
                    float width = c.args()[12 + side];
                    // Inline boxes have no start and end borders on lines they continue across.
                    if (width != 0 && Math.abs(width - 1) > 1e-4f) {
                        problems.add(where + ": " + c + " side " + side + " is " + width + "px, not 1px");
                    }
                }
            }
        }
        if (rings < 8) problems.add(where + ": only " + rings + " borders and outlines painted");
    }

    private static void onGrid(float v, float dp, RecordingCanvas.Call c, String where, List<String> problems) {
        float units = v / dp;
        if (Math.abs(units - Math.round(units)) > 1e-3f) problems.add(where + ": " + c + " has an edge at " + v + ", off the grid");
    }
}
