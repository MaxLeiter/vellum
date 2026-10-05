package dev.vellum.engine.paint;

import dev.vellum.engine.testing.Page;
import dev.vellum.engine.testing.RecordingCanvas;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code sprite:} and {@code canvas:} image URLs read once by the engine, the same in {@code <img>} and CSS. */
class ImageSourcesTest {
    /** Canvases draw as "surface:WxH" (the test host's); every sprite is 20×10. */
    private final TestHost host = new TestHost() {
        @Override
        public float[] spriteSize(String id) {
            return new float[] {20, 10};
        }
    };

    @Test
    void canvasAndSpriteUrls() {
        Page page = host.load("""
                <body style='margin: 0'>
                <canvas id=map width=8 height=4 style='display: block'></canvas>
                <div style='width: 16px; height: 16px; background: url(canvas:map) no-repeat; background-size: contain'></div>
                <img id=i src='canvas:map' style='display: block'>
                <img id=s src='sprite:minecraft:icon/x' style='display: block'>
                <sprite id=t src='minecraft:widget/button' style='display: block; width: 40px'></sprite>
                <div style='width: 4px; height: 4px; background-image: url(sprite:minecraft:a)'></div>
                <img src='canvas:missing'>
                """);
        RecordingCanvas c = page.paint();
        List<RecordingCanvas.Call> images = c.ops("drawImage");
        assertEquals(List.of("surface:8x4", "surface:8x4", "surface:8x4"), images.stream().map(RecordingCanvas.Call::text).toList(),
                "the canvas, the background and the img; a missing canvas draws nothing");
        assertArrayEquals(new float[] {0, 4, 16, 8}, images.get(1).bounds(), 1e-4f, "contain keeps the canvas's ratio");
        assertEquals(List.of("minecraft:icon/x", "minecraft:widget/button", "minecraft:a"),
                c.ops("drawSprite").stream().map(RecordingCanvas.Call::text).toList());
        assertEquals(8, page.byId("i").box.width, 1e-4);
        assertEquals(10, page.byId("s").box.height, 1e-4);
        assertEquals(20, page.byId("t").box.height, 1e-4, "a resized sprite keeps its ratio");
    }
}
