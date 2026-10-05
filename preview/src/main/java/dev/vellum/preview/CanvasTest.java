package dev.vellum.preview;

import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.preview.host.PreviewHost;
import dev.vellum.preview.render.ImageCanvas;
import dev.vellum.preview.render.MinecraftFont;

/**
 * The {@code --canvas-test} scene: a fixed sheet of canvas primitives drawn without the engine (rects, gradient
 * quads, triangles, text styles and sizes, nine-sliced sprites, textures, items, clipping, rotation, alpha), to check
 * {@link ImageCanvas} and the font and sprite fidelity against the game. Laid out for 427×240 GUI px.
 */
final class CanvasTest implements Scene {
    static final int WIDTH = 427, HEIGHT = 240;

    private static final int WHITE = 0xFFFFFFFF, LABEL = 0xFFA0A0A0;
    private static final FontSpec TEXT = MinecraftFont.NATIVE;

    private final PreviewHost host;
    private final Document document;

    CanvasTest(PreviewHost host) {
        this.host = host;
        // Replaced content belongs to elements; a detached document provides them without running the engine.
        this.document = Document.create(host, "canvas-test");
    }

    @Override
    public void frame(double nowMs, float width, float height, float scale) {}

    @Override
    public void paint(Canvas c) {
        shapes(c, 6, 4);
        text(c, 6, 70);
        sprites(c, 146, 4);
        images(c, 296, 4);
        stateStack(c, 296, 128);
    }

    private void shapes(Canvas c, float x, float y) {
        c.drawText("Rects & quads", x, y, TEXT, LABEL, 0, true);
        y += 11;
        c.fillRect(x, y, 12, 12, 0xFFE04040);
        c.fillRect(x + 14, y, 12, 12, 0x8040E040);
        c.fillRect(x + 28.3f, y + 0.3f, 12, 12, 0xFF4060E0); // off the pixel grid: covers pixels by their centres
        float hairline = c.devicePixel();
        c.fillRect(x + 44, y, 40, hairline, WHITE);
        c.fillRect(x + 44, y + 4, 40, 1, WHITE);
        c.fillRect(x + 44, y + 8, 40, 2, WHITE);
        c.fillRect(x + 88, y, hairline, 12, WHITE);
        c.fillRect(x + 91, y, 1, 12, WHITE);
        y += 16;
        quad(c, x, y, 60, 16, 0xFFFF0000, 0xFF0000FF, 0xFF0000FF, 0xFFFF0000);
        quad(c, x + 64, y, 30, 16, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00);
        // A triangle is a quad with its last vertex repeated.
        c.fillQuads(new float[] {x + 98, y, x + 126, y + 16, x + 98, y + 16, x + 98, y + 16},
                new int[] {WHITE, 0xFF000000, 0xFFFF8000, 0xFFFF8000}, 1);
        y += 20;
        // Translucent gradient over stripes: shared quad edges must not blend twice.
        for (int i = 0; i < 6; i++) c.fillRect(x + i * 20, y, 10, 14, WHITE);
        quad(c, x, y + 2, 60, 10, 0xC0FF0000, 0x00FF0000, 0x00FF0000, 0xC0FF0000);
        quad(c, x + 60, y + 2, 60, 10, 0x000000FF, 0xC00000FF, 0xC00000FF, 0x000000FF);
    }

    private static void quad(Canvas c, float x, float y, float w, float h, int topLeft, int topRight, int bottomRight, int bottomLeft) {
        c.fillQuads(new float[] {x, y, x + w, y, x + w, y + h, x, y + h}, new int[] {topLeft, topRight, bottomRight, bottomLeft}, 1);
    }

    private void text(Canvas c, float x, float y) {
        c.drawText("Text", x, y, TEXT, LABEL, 0, true);
        y += 11;
        words(c, x, y, "Shadow", TEXT, WHITE, 0, true, "No shadow", TEXT, WHITE, 0, false);
        y += 10;
        words(c, x, y, "Bold", font(8, true, false), 0xFFFFFF55, 0, true, "Italic", font(8, false, true), 0xFF55FFFF, 0, true,
                "Both", font(8, true, true), 0xFFFF55FF, 0, true, "Underline", TEXT, WHITE, Canvas.UNDERLINE, true);
        y += 10;
        words(c, x, y, "Strikethrough", TEXT, 0xFFFF5555, Canvas.STRIKETHROUGH, true,
                "Both", TEXT, 0xFF55FF55, Canvas.UNDERLINE | Canvas.STRIKETHROUGH, false);
        y += 11;
        c.drawText("12px text", x, y, font(12, false, false), WHITE, 0, true);
        y += 14;
        c.drawText("16px Bold", x, y, font(16, true, false), 0xFFFFAA00, Canvas.UNDERLINE, true);
        y += 20;
        c.drawText("Àccénts ÿ Łódź Ωμέγα Жж", x, y, TEXT, WHITE, 0, true);
        y += 10;
        c.drawText("Fallback: 日本語 ✓", x, y, TEXT, WHITE, 0, true);
        y += 12;
        c.fillRect(x, y, 128, 14, 0xFFC6C6C6);
        c.drawText("Inventory (#404040)", x + 4, y + 3, TEXT, 0xFF404040, 0, false);
        y += 18;
        c.drawText("Translucent", x, y, TEXT, 0x80FFFFFF, 0, true);
    }

    /** Draws runs of {text, font, colour, decorations, shadow} one after another on a line. */
    private void words(Canvas c, float x, float y, Object... runs) {
        for (int i = 0; i < runs.length; i += 5) {
            String text = (String) runs[i];
            FontSpec font = (FontSpec) runs[i + 1];
            c.drawText(text, x, y, font, (int) runs[i + 2], (int) runs[i + 3], (boolean) runs[i + 4]);
            x += host.fonts().width(text, font) + 5;
        }
    }

    private void sprites(Canvas c, float x, float y) {
        c.drawText("Sprites (nine-slice)", x, y, TEXT, LABEL, 0, true);
        y += 11;
        button(c, "minecraft:widget/button", "Done", x, y, 92, 20, WHITE);
        button(c, "minecraft:widget/button", "Tall", x + 96, y, 40, 40, WHITE);
        y += 22;
        button(c, "minecraft:widget/button_highlighted", "Hovered", x, y, 92, 20, WHITE);
        y += 22;
        button(c, "minecraft:widget/button_disabled", "Disabled", x, y, 92, 20, 0xFFA0A0A0);
        button(c, "minecraft:widget/button", "", x + 96, y + 4, 8, 8, WHITE);
        button(c, "minecraft:widget/button", "", x + 108, y, 28, 12, WHITE);
        y += 22;
        c.drawSprite("minecraft:widget/text_field", x, y, 92, 20, WHITE);
        c.drawText("Text field_", x + 4, y + 6, TEXT, 0xFFE0E0E0, 0, true);
        c.drawSprite("minecraft:widget/checkbox", x + 96, y, 20, 20, WHITE);
        c.drawSprite("minecraft:widget/checkbox_selected", x + 118, y, 20, 20, WHITE);
        y += 22;
        c.drawSprite("minecraft:widget/slider", x, y, 92, 20, WHITE);
        c.drawSprite("minecraft:widget/slider_handle", x + 34, y, 8, 20, WHITE);
        centered(c, "Volume: 40%", x, y, 92, 20, WHITE);
        y += 24;
        // A tooltip as TooltipRenderUtil draws one: both sprites 12px outside the text (3px padding, 9px of shadow).
        float tx = x + 4, ty = y + 4, tw = host.fonts().width("+7 Attack Damage", TEXT), th = 20;
        c.drawSprite("minecraft:tooltip/background", tx - 12, ty - 12, tw + 24, th + 24, WHITE);
        c.drawSprite("minecraft:tooltip/frame", tx - 12, ty - 12, tw + 24, th + 24, WHITE);
        c.drawText("Diamond Sword", tx, ty, TEXT, WHITE, 0, true);
        c.drawText("+7 Attack Damage", tx, ty + 12, TEXT, 0xFF5555FF, 0, true);
        y += 32;
        for (int i = 0; i < 6; i++) c.drawSprite("minecraft:container/slot", x + i * 18, y, 18, 18, WHITE);
        replaced(c, x + 1, y + 1, 16, 16, "item", "id", "minecraft:diamond", "count", "64");
        replaced(c, x + 19, y + 1, 16, 16, "item", "id", "minecraft:stone");
        replaced(c, x + 37, y + 1, 16, 16, "item", "id", "minecraft:diamond_sword", "count", "1");
        replaced(c, x + 55, y + 1, 16, 16, "item", "id", "minecraft:no_such_item", "count", "7");
        replaced(c, x + 73, y + 1, 16, 16, "player-head");
    }

    private void button(Canvas c, String sprite, String label, float x, float y, float w, float h, int color) {
        c.drawSprite(sprite, x, y, w, h, WHITE);
        centered(c, label, x, y, w, h, color);
    }

    /** Centred on whole pixels, as vanilla centres button labels. */
    private void centered(Canvas c, String label, float x, float y, float w, float h, int color) {
        float width = host.fonts().width(label, TEXT);
        c.drawText(label, x + (int) ((w - width) / 2), y + (int) ((h - 8) / 2), TEXT, color, 0, true);
    }

    private void images(Canvas c, float x, float y) {
        c.drawText("Textures & items", x, y, TEXT, LABEL, 0, true);
        y += 11;
        String sword = "minecraft:textures/item/diamond_sword.png", stone = "minecraft:textures/block/stone.png";
        c.drawImage(sword, x, y, 32, 32, 0, 0, 1, 1, WHITE, false);
        c.drawImage(sword, x + 36, y, 16, 16, 1, 0, 0, 1, WHITE, false); // flipped
        c.drawImage(stone, x + 36, y + 16, 16, 16, 0, 0, 1, 1, 0xFF60FF60, false); // tinted
        c.drawImage(stone, x + 56, y, 64, 16, 0, 0, 4, 1, WHITE, false); // repeated
        c.drawImage(stone, x + 56, y + 16, 64, 16, 0.25f, 0.25f, 0.5f, 0.5f, WHITE, true); // smooth, a quarter
        y += 36;
        replaced(c, x, y, 32, 32, "item", "id", "minecraft:golden_apple", "count", "16");
        replaced(c, x + 36, y - 4, 24, 36, "entity");
        replaced(c, x + 64, y, 16, 16, "player-head");
        replaced(c, x + 84, y, 16, 16, "img", "src", "minecraft:textures/item/emerald.png");
        replaced(c, x + 104, y, 16, 16, "img", "src", "sprite:minecraft:icon/checkmark");
        c.drawImage("minecraft:textures/missing.png", x + 84, y + 18, 16, 16, 0, 0, 1, 1, WHITE, false);
        c.drawSprite("minecraft:no/such_sprite", x + 104, y + 18, 16, 16, WHITE);
    }

    private void stateStack(Canvas c, float x, float y) {
        c.drawText("Clip, rotate, alpha", x, y, TEXT, LABEL, 0, true);
        y += 12;
        c.fillRect(x - 1, y - 1, 42, 22, 0xFF808080);
        c.save();
        c.clipRect(x, y, 40, 20);
        quad(c, x - 20, y - 10, 80, 40, 0xFF00C0C0, 0xFFC000C0, 0xFFC0C000, 0xFF00C000);
        c.drawText("Clipped text runs on", x + 2, y + 6, TEXT, WHITE, 0, true);
        c.restore();

        c.save();
        c.translate(x + 82, y + 24);
        float angle = (float) Math.toRadians(-15), cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        c.transform(cos, sin, -sin, cos, 0, 0);
        button(c, "minecraft:widget/button", "Rotated", -30, -10, 60, 20, WHITE);
        c.restore();

        y += 40;
        c.save();
        c.multiplyAlpha(0.5f);
        c.fillRect(x, y, 30, 20, 0xFFFF0000);
        c.fillRect(x + 15, y + 8, 30, 20, 0xFF0000FF);
        c.save();
        c.multiplyAlpha(0.5f);
        button(c, "minecraft:widget/button", "25%", x + 52, y, 40, 20, WHITE);
        c.restore();
        c.drawText("50% alpha", x, y + 30, TEXT, WHITE, 0, true);
        c.restore();
        c.drawSprite("minecraft:widget/button", x + 96, y, 30, 20, 0xFF80C0FF); // tinted sprite
    }

    /** Draws the host's replaced content for a detached element with the given tag and attributes. */
    private void replaced(Canvas c, float x, float y, float w, float h, String tag, String... attributes) {
        Element element = document.createElement(tag);
        for (int i = 0; i < attributes.length; i += 2) element.setAttribute(attributes[i], attributes[i + 1]);
        ReplacedContent content = host.createReplaced(element);
        c.drawReplaced(content, element, x, y, w, h);
    }

    private static FontSpec font(float size, boolean bold, boolean italic) {
        return new FontSpec(TEXT.families(), size, bold, italic);
    }
}
