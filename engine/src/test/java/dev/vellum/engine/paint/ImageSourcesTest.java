package dev.vellum.engine.paint;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.host.ArraySurface;
import dev.vellum.engine.host.PixelSurface;
import dev.vellum.engine.testing.TestHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code sprite:} and {@code canvas:} image URLs read once by the engine, the same in {@code <img>} and CSS. */
class ImageSourcesTest {
    /** Canvases draw as "surface:WxH"; every sprite is 20×10. */
    private final TestHost host = new TestHost() {
        @Override
        public PixelSurface createSurface(int width, int height) {
            return new ArraySurface(width, height) {
                @Override
                public String url() {
                    return "surface:" + width + "x" + height;
                }
            };
        }

        @Override
        public float[] spriteSize(String id) {
            return new float[] {20, 10};
        }
    };

    @Test
    void canvasAndSpriteUrls() {
        Document doc = host.load("""
                <body style='margin: 0'>
                <canvas id=map width=8 height=4 style='display: block'></canvas>
                <div style='width: 16px; height: 16px; background: url(canvas:map) no-repeat; background-size: contain'></div>
                <img id=i src='canvas:map' style='display: block'>
                <img id=s src='sprite:minecraft:icon/x' style='display: block'>
                <sprite id=t src='minecraft:widget/button' style='display: block; width: 40px'></sprite>
                <div style='width: 4px; height: 4px; background-image: url(sprite:minecraft:a)'></div>
                <img src='canvas:missing'>
                """);
        RecordingCanvas c = new RecordingCanvas();
        doc.paint(c);
        List<RecordingCanvas.Call> images = c.ops("drawImage");
        assertEquals(List.of("surface:8x4", "surface:8x4", "surface:8x4"), images.stream().map(RecordingCanvas.Call::text).toList(),
                "the canvas, the background and the img; a missing canvas draws nothing");
        assertArrayEquals(new float[] {0, 4, 16, 8}, images.get(1).bounds(), 1e-4f, "contain keeps the canvas's ratio");
        assertEquals(List.of("minecraft:icon/x", "minecraft:widget/button", "minecraft:a"),
                c.ops("drawSprite").stream().map(RecordingCanvas.Call::text).toList());
        assertEquals(8, doc.getElementById("i").box.width, 1e-4);
        assertEquals(10, doc.getElementById("s").box.height, 1e-4);
        assertEquals(20, doc.getElementById("t").box.height, 1e-4, "a resized sprite keeps its ratio");
    }
}
