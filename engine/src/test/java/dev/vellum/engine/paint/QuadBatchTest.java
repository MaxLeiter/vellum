package dev.vellum.engine.paint;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuadBatchTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF;

    @Test
    void normalisesWindingAndDropsDegenerateQuads() {
        QuadBatch batch = new QuadBatch();
        RecordingCanvas c = new RecordingCanvas();
        batch.quad(0, 0, 10, 0, 10, 10, 0, 10, RED); // clockwise on screen: reversed
        batch.quad(0, 0, 0, 10, 10, 10, 10, 0, RED); // vanilla order: kept
        batch.quad(0, 0, 5, 0, 10, 0, 10, 0, RED); // zero area
        batch.quad(0, 0, 0, 10, 10, 10, 10, 0, 0x00FF0000); // transparent
        batch.flush(c);
        assertEquals(2, c.quadCount());
        assertEquals(200, c.quadArea(), 1e-4);
        assertEquals(0, batch.count());
    }

    @Test
    void reversedQuadsKeepTheirVertexColours() {
        QuadBatch batch = new QuadBatch();
        RecordingCanvas c = new RecordingCanvas();
        batch.quad(0, 0, 10, 0, 10, 10, 0, 10, RED, BLUE, BLUE, RED);
        batch.flush(c);
        RecordingCanvas.Call call = c.ops("fillQuads").getFirst();
        for (int v = 0; v < 4; v++) {
            int expected = call.quads()[2 * v] == 0 ? RED : BLUE;
            assertEquals(expected, call.quadColors()[v], "vertex " + v);
        }
    }

    @Test
    void clipsToARoundedRegion() {
        QuadBatch batch = new QuadBatch();
        RecordingCanvas c = new RecordingCanvas();
        float[] radii = new float[8];
        Arrays.fill(radii, 6);
        batch.setClip(0, 0, 30, 20, radii, 0.25f);
        batch.quad(-50, -50, -50, 50, 50, 50, 50, -50, RED);
        batch.clearClip();
        batch.flush(c);
        assertEquals(30 * 20 - (4 - Math.PI) * 36, c.quadArea(), 30 * 20 * 0.01);
        for (RecordingCanvas.Call call : c.ops("fillQuads")) {
            for (int i = 0; i < call.quads().length; i += 2) {
                assertTrue(Shapes.contains(-0.01f, -0.01f, 30.02f, 20.02f, null, call.quads()[i], call.quads()[i + 1]));
            }
        }
    }

    @Test
    void clippingInterpolatesColoursAlongEdges() {
        QuadBatch batch = new QuadBatch();
        RecordingCanvas c = new RecordingCanvas();
        batch.setClip(new float[] {0, 0, 0, 10, 5, 10, 5, 0}, 4);
        batch.quad(0, 0, 0, 10, 10, 10, 10, 0, RED, RED, BLUE, BLUE);
        batch.flush(c);
        RecordingCanvas.Call call = c.ops("fillQuads").getFirst();
        int mid = QuadBatch.lerpArgb(RED, BLUE, 0.5f);
        for (int v = 0; v < call.quadColors().length; v++) {
            if (call.quads()[2 * v] == 5) assertEquals(mid, call.quadColors()[v]);
        }
        assertEquals(50, c.quadArea(), 1e-3);
    }

    @Test
    void intersectedClipCanBeEmpty() {
        QuadBatch batch = new QuadBatch();
        RecordingCanvas c = new RecordingCanvas();
        batch.setClip(0, 0, 10, 10, new float[8], 1);
        batch.intersectClip(20, 20, 5, 5);
        batch.quad(-50, -50, -50, 50, 50, 50, 50, -50, RED);
        batch.flush(c);
        assertEquals(0, c.quadCount());
    }

    @Test
    void halfPlaneClipKeepsThePositiveSide() {
        float[] in = {0, 0, 0, 10, 10, 10, 10, 0}, out = new float[16];
        // A line going up at x = 4: positive side (sign 1) is x >= 4.
        int n = QuadBatch.clipHalfPlane(in, null, 4, 4, 10, 4, 0, 1, out, null);
        assertEquals(4, n);
        for (int i = 0; i < n; i++) assertTrue(out[2 * i] >= 4);
    }
}
