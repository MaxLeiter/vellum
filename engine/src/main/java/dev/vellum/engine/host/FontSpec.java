package dev.vellum.engine.host;

import dev.vellum.engine.style.ComputedStyle;

import java.util.List;
import java.util.Objects;

/**
 * A font request: the family list from CSS, the size in px (8 is Minecraft's native size), bold and italic.
 * Letter spacing is applied by the engine, not the host.
 *
 * <p>Immutable, compared by value. Each computed style memoizes its spec ({@link ComputedStyle#font()}), so the
 * same instance comes back for unchanged text styles and hosts can keep what they resolve for it (the font object,
 * the glyph table) in {@link #hostFont}, instead of looking it up per measurement.
 */
public final class FontSpec {
    private final List<String> families;
    private final float size;
    private final boolean bold, italic;
    /** The host's resolved font for this spec; see {@link #hostFont()}. */
    private Object hostFont;

    public FontSpec(List<String> families, float size, boolean bold, boolean italic) {
        this.families = families;
        this.size = size;
        this.bold = bold;
        this.italic = italic;
    }

    /** The style's font: memoized on the style, so this is cheap to call per text run. */
    public static FontSpec of(ComputedStyle s) {
        return s.font();
    }

    public List<String> families() { return families; }
    public float size() { return size; }
    public boolean bold() { return bold; }
    public boolean italic() { return italic; }

    /** Scale relative to the native 8px em. */
    public float scale() {
        return size / ComputedStyle.DEFAULT_FONT_SIZE;
    }

    /**
     * A slot for the host's resolved font. Specs are shared between documents and outlive resource reloads, so a
     * host stores something it can validate (its own instance, a cache generation) and re-resolves when that fails.
     * Render thread only.
     */
    public Object hostFont() {
        return hostFont;
    }

    public void setHostFont(Object hostFont) {
        this.hostFont = hostFont;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof FontSpec f && Float.compare(size, f.size) == 0 && bold == f.bold
                && italic == f.italic && families.equals(f.families);
    }

    @Override
    public int hashCode() {
        return Objects.hash(families, size, bold, italic);
    }

    @Override
    public String toString() {
        return "FontSpec[" + families + " " + size + "px" + (bold ? " bold" : "") + (italic ? " italic" : "") + "]";
    }
}
