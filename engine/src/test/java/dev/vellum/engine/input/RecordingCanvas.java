package dev.vellum.engine.input;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.host.ReplacedContent;
import dev.vellum.engine.paint.Canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A canvas that records draw calls as readable strings (ignoring state calls other than clips). */
final class RecordingCanvas implements Canvas {
    final List<String> calls = new ArrayList<>();

    @Override public void save() {}
    @Override public void restore() {}
    @Override public int saveCount() { return 0; }
    @Override public void translate(float dx, float dy) {}
    @Override public void transform(float a, float b, float c, float d, float e, float f) {}
    @Override public void multiplyAlpha(float alpha) {}

    @Override
    public void clipRect(float x, float y, float width, float height) {
        calls.add("clip " + f(x) + "," + f(y) + " " + f(width) + "x" + f(height));
    }

    @Override
    public void fillRect(float x, float y, float width, float height, int argb) {
        calls.add(String.format("rect %s,%s %sx%s #%08x", f(x), f(y), f(width), f(height), argb));
    }

    @Override
    public void fillQuads(float[] xy, int[] colors, int quadCount) {
        calls.add("quads " + quadCount);
    }

    @Override
    public void fillRoundedRect(float x, float y, float width, float height, float[] radii, int argb) {
        calls.add(String.format("round %s,%s %sx%s #%08x", f(x), f(y), f(width), f(height), argb));
    }

    @Override
    public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {
        calls.add(String.format("text '%s' %s,%s #%08x%s", text, f(x), f(y), argb, shadow ? " shadow" : ""));
    }

    @Override
    public void drawImage(String url, float x, float y, float width, float height, float u0, float v0, float u1, float v1,
                          int tint, boolean smooth) {
        calls.add("image " + url);
    }

    @Override
    public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {
        calls.add("sprite " + spriteId + " " + f(x) + "," + f(y) + " " + f(width) + "x" + f(height));
    }

    @Override
    public void drawReplaced(ReplacedContent content, Element element, float x, float y, float width, float height) {
        calls.add("replaced");
    }

    List<String> matching(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).toList();
    }

    private static String f(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
