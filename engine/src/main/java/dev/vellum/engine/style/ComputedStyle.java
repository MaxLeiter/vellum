package dev.vellum.engine.style;

import java.util.List;
import java.util.Map;

/**
 * The computed style of one element: the result of the cascade, inheritance and value computation.
 *
 * <p>This is a plain mutable struct for speed. The style engine builds one per element on every restyle and never
 * mutates it after handing it out; the animation engine produces a separate copy with animated values applied
 * ({@link dev.vellum.engine.dom.Element#style()}), so layout and paint always read a stable snapshot.
 *
 * <p>Conventions: lengths that may be percentages or keywords are {@link Length}; border and outline widths are
 * resolved px floats (0 when the style is none); colours are ARGB ints with {@code currentColor} already resolved;
 * relative units (em, rem, vw...) are already converted to px. {@link Prop} lists every property with its metadata
 * and gives generic access for transitions and animations.
 */
public final class ComputedStyle implements Cloneable {
    /** Default font size, in GUI px. Minecraft's font is drawn on an 8px em, so 8px renders at scale 1. */
    public static final float DEFAULT_FONT_SIZE = 8f;
    /** Ratio of Minecraft's line height (9) to its em (8): {@code line-height: normal}. */
    public static final float NORMAL_LINE_HEIGHT = 9f / 8f;
    public static final String DEFAULT_FONT = "minecraft:default";

    // ---- Box ----
    public Display display = Display.INLINE;
    public Position position = Position.STATIC;
    public BoxSizing boxSizing = BoxSizing.CONTENT_BOX;
    public Length width = Length.AUTO, height = Length.AUTO;
    public Length minWidth = Length.AUTO, minHeight = Length.AUTO;
    public Length maxWidth = Length.NONE, maxHeight = Length.NONE;
    public Length top = Length.AUTO, right = Length.AUTO, bottom = Length.AUTO, left = Length.AUTO;
    public Length marginTop = Length.ZERO, marginRight = Length.ZERO, marginBottom = Length.ZERO, marginLeft = Length.ZERO;
    public Length paddingTop = Length.ZERO, paddingRight = Length.ZERO, paddingBottom = Length.ZERO, paddingLeft = Length.ZERO;
    public float borderTopWidth, borderRightWidth, borderBottomWidth, borderLeftWidth;
    public BorderStyle borderTopStyle = BorderStyle.NONE, borderRightStyle = BorderStyle.NONE,
            borderBottomStyle = BorderStyle.NONE, borderLeftStyle = BorderStyle.NONE;
    public int borderTopColor = Colors.BLACK, borderRightColor = Colors.BLACK,
            borderBottomColor = Colors.BLACK, borderLeftColor = Colors.BLACK;
    /** Corner radii. A percentage resolves against the box width horizontally and the height vertically. */
    public Length radiusTopLeft = Length.ZERO, radiusTopRight = Length.ZERO,
            radiusBottomRight = Length.ZERO, radiusBottomLeft = Length.ZERO;
    public Overflow overflowX = Overflow.VISIBLE, overflowY = Overflow.VISIBLE;
    public boolean zIndexAuto = true;
    public int zIndex;
    public float opacity = 1f;
    public Visibility visibility = Visibility.VISIBLE;
    public VerticalAlign verticalAlign = VerticalAlign.BASELINE;
    /** width / height, or NaN for {@code auto}. */
    public float aspectRatio = Float.NaN;
    public ObjectFit objectFit = ObjectFit.FILL;

    // ---- Flexbox and box alignment ----
    public FlexDirection flexDirection = FlexDirection.ROW;
    public FlexWrap flexWrap = FlexWrap.NOWRAP;
    public Align justifyContent = Align.NORMAL;
    public Align alignItems = Align.NORMAL;
    public Align alignContent = Align.NORMAL;
    public Align alignSelf = Align.AUTO;
    public Align justifyItems = Align.NORMAL;
    public Align justifySelf = Align.AUTO;
    public float flexGrow = 0f;
    public float flexShrink = 1f;
    public Length flexBasis = Length.AUTO;
    public int order;
    public Length rowGap = Length.ZERO, columnGap = Length.ZERO;

    // ---- Grid ----
    public List<GridTrack> gridTemplateColumns = List.of(), gridTemplateRows = List.of();
    /** Rows of area names from {@code grid-template-areas} ("." for empty cells), or null for none. */
    public List<List<String>> gridTemplateAreas;
    public List<GridTrack> gridAutoColumns = List.of(new GridTrack.Keyword(Length.AUTO));
    public List<GridTrack> gridAutoRows = List.of(new GridTrack.Keyword(Length.AUTO));
    public GridAutoFlow gridAutoFlow = GridAutoFlow.ROW;
    public GridLine gridColumnStart = GridLine.AUTO, gridColumnEnd = GridLine.AUTO;
    public GridLine gridRowStart = GridLine.AUTO, gridRowEnd = GridLine.AUTO;

    // ---- Text (inherited) ----
    public int color = Colors.BLACK;
    /** Font family names in preference order. Minecraft font ids ({@code minecraft:uniform}) or aliases. */
    public List<String> fontFamily = List.of(DEFAULT_FONT);
    public float fontSize = DEFAULT_FONT_SIZE;
    public int fontWeight = 400;
    public boolean fontItalic;
    /** Resolved line height in px, or NaN for {@code normal} ({@link #NORMAL_LINE_HEIGHT} times the font size). */
    public float lineHeight = Float.NaN;
    /** For unitless line-heights: the factor, which is what inherits. NaN otherwise. */
    public float lineHeightFactor = Float.NaN;
    public float letterSpacing;
    public float wordSpacing;
    public float textIndent;
    public TextAlign textAlign = TextAlign.START;
    public TextTransform textTransform = TextTransform.NONE;
    /**
     * Text decorations. Unlike CSS these simply inherit (CSS propagates them to inline descendants, which looks the
     * same for text). They map onto Minecraft's underline and strikethrough styles.
     */
    public boolean underline, lineThrough;
    public WhiteSpace whiteSpace = WhiteSpace.NORMAL;
    public WordBreak wordBreak = WordBreak.NORMAL;
    public TextOverflow textOverflow = TextOverflow.CLIP;
    /** Line clamp ({@code -webkit-line-clamp} / {@code line-clamp}); 0 for none. */
    public int lineClamp;
    public List<Shadow> textShadow = List.of();
    public Cursor cursor = Cursor.AUTO;
    public PointerEvents pointerEvents = PointerEvents.AUTO;
    public UserSelect userSelect = UserSelect.AUTO;
    public ImageRendering imageRendering = ImageRendering.PIXELATED;
    /** Accent for form controls (checkbox tick, range fill, caret). */
    public int accentColor = 0xFF5B8BD9;

    // ---- Background, border decorations, effects ----
    public int backgroundColor = Colors.TRANSPARENT;
    /** Background image layers, topmost first. */
    public List<BackgroundLayer> backgroundLayers = List.of();
    public List<Shadow> boxShadow = List.of();
    public float outlineWidth;
    public BorderStyle outlineStyle = BorderStyle.NONE;
    public int outlineColor = Colors.BLACK;
    public float outlineOffset;
    public List<TransformFunction> transform = List.of();
    public Length transformOriginX = Length.PERCENT_50, transformOriginY = Length.PERCENT_50;
    /**
     * Tint multiplied into images, items and sprites ({@code -mc-tint}); white for none. A Vellum extension, handy
     * for greying out disabled slots or colouring white sprites.
     */
    public int tint = Colors.WHITE;

    // ---- Scrolling ----
    public boolean scrollSmooth = true;
    /** {@code scrollbar-width}: 0 none, 1 thin, 2 auto. */
    public int scrollbarWidth = 2;
    public int scrollbarThumbColor = 0x80FFFFFF, scrollbarTrackColor = 0x20000000;

    // ---- Animation ----
    public List<TransitionSpec> transitions = List.of();
    public List<AnimationSpec> animations = List.of();

    // ---- Generated content and custom properties ----
    /** {@code content} for ::before / ::after; null means none. */
    public String content;
    /** Custom properties ({@code --name}), raw token text. Inherited; treat as immutable and replace on write. */
    public Map<String, String> customProperties = Map.of();

    /** The initial values of every property. Never mutate it. */
    public static final ComputedStyle INITIAL = new ComputedStyle();

    public ComputedStyle copy() {
        try {
            return (ComputedStyle) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** A fresh style inheriting the inherited properties of {@code parent} (or initial values when null). */
    public static ComputedStyle inheritFrom(ComputedStyle parent) {
        ComputedStyle s = new ComputedStyle();
        if (parent == null) return s;
        for (Prop p : Prop.INHERITED) p.set(s, p.get(parent));
        s.lineHeightFactor = parent.lineHeightFactor;
        return s;
    }

    // ---- Derived helpers used by layout and paint ----

    /** The used line height in px. */
    public float usedLineHeight() {
        if (!Float.isNaN(lineHeight)) return lineHeight;
        return fontSize * NORMAL_LINE_HEIGHT;
    }

    public boolean isBold() { return fontWeight >= 600; }

    public boolean isScrollContainer() {
        return overflowX.scrolls() || overflowY.scrolls();
    }

    public boolean hasTransform() { return !transform.isEmpty(); }

    /** True when this element starts a stacking context (z-index on a positioned box, opacity, transform...). */
    public boolean createsStackingContext() {
        return (!zIndexAuto && (position.isPositioned() || isFlexOrGridItemHint)) || opacity < 1f
                || !transform.isEmpty() || position == Position.FIXED;
    }

    /** Set by the style engine for children of flex and grid containers, where z-index applies without position. */
    public boolean isFlexOrGridItemHint;

    public boolean hasBorder() {
        return borderTopWidth > 0 || borderRightWidth > 0 || borderBottomWidth > 0 || borderLeftWidth > 0;
    }

    public boolean hasRadius() {
        return !radiusTopLeft.equals(Length.ZERO) || !radiusTopRight.equals(Length.ZERO)
                || !radiusBottomRight.equals(Length.ZERO) || !radiusBottomLeft.equals(Length.ZERO);
    }

    /** The custom property value, or null. */
    public String var(String name) {
        return customProperties.get(name);
    }
}
