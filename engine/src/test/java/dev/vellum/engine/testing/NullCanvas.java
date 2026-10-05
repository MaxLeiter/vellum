package dev.vellum.engine.testing;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;

/** A canvas that draws nothing and keeps only the save count, for tests that paint for the side effects. */
public final class NullCanvas implements Canvas {
    private int saves;

    @Override public void save() { saves++; }
    @Override public void restore() { if (saves > 0) saves--; }
    @Override public int saveCount() { return saves; }
    @Override public void translate(float dx, float dy) {}
    @Override public void transform(float a, float b, float c, float d, float e, float f) {}
    @Override public void multiplyAlpha(float alpha) {}
    @Override public void clipRect(float x, float y, float width, float height) {}
    @Override public void fillRect(float x, float y, float width, float height, int argb) {}
    @Override public void fillQuads(float[] xy, int[] colors, int quadCount) {}
    @Override public void drawText(String text, float x, float y, FontSpec font, int argb, int decorations, boolean shadow) {}

    @Override
    public void drawImage(String url, float x, float y, float width, float height, float u0, float v0, float u1, float v1,
                          int tint, boolean smooth) {}

    @Override public void drawSprite(String spriteId, float x, float y, float width, float height, int tint) {}
}
