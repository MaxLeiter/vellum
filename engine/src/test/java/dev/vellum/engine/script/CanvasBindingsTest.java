package dev.vellum.engine.script;

import dev.vellum.engine.host.ArraySurface;
import dev.vellum.engine.replaced.CanvasContent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code getContext('2d')} drawing into the canvas's surface (TestHost gives in-memory surfaces). */
class CanvasBindingsTest {
    private static int pixel(Page page, String id, int x, int y) {
        CanvasContent canvas = (CanvasContent) page.byId(id).replaced;
        ArraySurface surface = (ArraySurface) canvas.surface();
        return surface.pixels()[y * surface.width() + x];
    }

    @Test
    void fillsComposeAndClear() {
        Page page = Page.withScript("<canvas id=c width=4 height=2></canvas>", """
                const ctx = document.getElementById('c').getContext('2d');
                ctx.fillStyle = 'mc-gold';
                ctx.fillRect(0, 0, 4, 2);
                ctx.fillStyle = 'rgba(0, 0, 255, 0.5)';
                ctx.fillRect(1.4, 0, 1, 1);
                ctx.clearRect(3, 1, 5, 5);
                """);
        assertEquals(0xFFFFAA00, pixel(page, "c", 0, 0));
        assertEquals(0xFF7F5480, pixel(page, "c", 1, 0), "half blue over gold");
        assertEquals(0, pixel(page, "c", 3, 1));
        assertEquals("#ffaa00 rgba(0, 0, 255, 0.502) 4 2", page.eval("(ctx.fillStyle = 'mc-gold', ctx.fillStyle) + ' ' + "
                + "(ctx.fillStyle = 'rgba(0, 0, 255, 0.5)', ctx.fillStyle) + ' ' + ctx.canvas.width + ' ' + ctx.canvas.height"));
        assertEquals("true null", page.eval("(ctx === document.getElementById('c').getContext('2d')) + ' ' + "
                + "document.body.getContext('2d')"));
    }

    @Test
    void imageDataRoundTrips() {
        Page page = Page.withScript("<canvas id=c width=3 height=3></canvas>", """
                const ctx = document.getElementById('c').getContext('2d');
                const image = ctx.createImageData(2, 1);
                image.data.set([255, 0, 0, 255, 0, 255, 0, 128]);
                ctx.putImageData(image, 1, 2);
                const read = ctx.getImageData(0, 2, 3, 1);
                """);
        assertEquals(0xFFFF0000, pixel(page, "c", 1, 2));
        assertEquals(0x8000FF00, pixel(page, "c", 2, 2));
        assertEquals("0,0,0,0,255,0,0,255,0,255,0,128 Uint8ClampedArray",
                page.eval("Array.prototype.join.call(read.data, ',') + ' ' + read.data.constructor.name"));
    }

    @Test
    void canvasesDrawnBeforeInsertionKeepTheirPixels() {
        Page page = Page.withScript("<div id=box></div>", """
                const c = document.createElement('canvas');
                c.id = 'late';
                c.width = 2;
                c.height = 2;
                c.getContext('2d').fillRect(0, 0, 1, 1);
                document.getElementById('box').appendChild(c);
                const copy = document.createElement('canvas');
                copy.width = 4;
                copy.height = 4;
                copy.id = 'copy';
                document.body.appendChild(copy);
                copy.getContext('2d').drawImage(c, 0, 0, 4, 4);
                """);
        assertSame(page.byId("late").replaced, page.doc.replacedContent(page.byId("late")));
        assertEquals(0xFF000000, pixel(page, "late", 0, 0));
        assertEquals(0xFF000000, pixel(page, "copy", 1, 1), "drawImage scales");
        assertEquals(0, pixel(page, "copy", 2, 2));
        assertEquals("2 2", page.eval("c.width + ' ' + c.height"));
    }
}
