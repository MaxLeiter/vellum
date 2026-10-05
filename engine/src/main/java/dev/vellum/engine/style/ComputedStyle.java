package dev.vellum.engine.style;

import dev.vellum.engine.host.FontFamilies;
import dev.vellum.engine.host.FontSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The computed style of one element: the result of the cascade, inheritance and value computation.
 *
 * <p>This is a plain mutable struct for speed. The style engine builds one per element on every restyle and never
 * mutates it after handing it out; the animation engine produces a separate copy with animated values applied
 * ({@link dev.vellum.engine.dom.Element#style}), so layout and paint always read a stable snapshot.
 *
 * <p>Conventions: lengths that may be percentages or keywords are {@link Length}; border and outline widths are
 * resolved px floats (0 when the style is none); colours are ARGB ints with {@code currentColor} already resolved
 * (in used styles against the animated colour); relative units (em, rem, vw...) are already converted to
 * px. {@link Prop} lists every property with its metadata and gives generic access for transitions and animations.
 */
public final class ComputedStyle implements Cloneable {
    /** Default font size, in GUI px. Minecraft's font is drawn on an 8px em, so 8px renders at scale 1. */
    public static final float DEFAULT_FONT_SIZE = 8f;
    /** Ratio of Minecraft's line height (9) to its em (8): {@code line-height: normal}. */
    public static final float NORMAL_LINE_HEIGHT = 9f / 8f;
    public static final String DEFAULT_FONT = FontFamilies.DEFAULT;

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
    /**
     * {@code object-position}, per axis: where replaced content sits in its box ({@link #objectX}). {@link Length#AUTO}
     * until a rule sets it, which centres most content (CSS's initial {@code 50% 50%}, and what it serialises as)
     * while an {@code <entity>} keeps its own default.
     */
    public Length objectPositionX = Length.AUTO, objectPositionY = Length.AUTO;

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
     * Text decorations. Unlike CSS these inherit (CSS propagates them to inline descendants, which looks the
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
    /** Background layers, topmost first ({@code none} layers too: the last one clips the colour). */
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

    // ---- 3D content (entities and models; Vellum extensions) ----
    /**
     * How 3D content is turned ({@code -mc-yaw}) and viewed ({@code -mc-pitch}), in degrees: positive yaw turns its
     * front to the right, positive pitch looks at it from above. Unbounded, so {@code 0} to {@code 360deg} animates a
     * full turn.
     */
    public float modelYaw, modelPitch;
    /** {@code -mc-model-scale}: multiplies the size that fits 3D content into its box. */
    public float modelScale = 1f;
    /** {@code -mc-entity-focus}: an entity's whole body fits its box, or its head and shoulders fill it. */
    public EntityFocus entityFocus = EntityFocus.BODY;
    /**
     * How a {@code follow-mouse} entity's head turns toward the pointer ({@link #gazeYaw}, {@link #gazePitch}):
     * {@code -mc-gaze-reach}, in px, is the falloff distance; {@code -mc-gaze-limit} caps the turn to either side,
     * the tilt up and the tilt down, in degrees (NaN for none).
     */
    public float gazeReach = 40f;
    public float gazeLimitYaw = Float.NaN, gazeLimitUp = Float.NaN, gazeLimitDown = Float.NaN;

    // ---- Scrolling ----
    public boolean scrollSmooth = true;
    /** {@code scrollbar-width}: 0 none, 1 thin, 2 auto. */
    public int scrollbarWidth = 2;
    public int scrollbarThumbColor = 0x80FFFFFF, scrollbarTrackColor = 0x20000000;

    // ---- Tooltips (inherited) ----
    /**
     * {@code -mc-tooltip-delay}: how long, in ms, the pointer rests on an element before its {@code title} tooltip
     * shows. Half a second unless a rule sets it, as browsers wait for title tooltips; {@code 0ms} shows it at once, as
     * vanilla shows a slot's item.
     */
    public float tooltipDelay = 500f;

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
        if (parent != null) s.copyInheritedFrom(parent);
        return s;
    }

    // ---- Comparison and inheritance, field by field ----
    // Hand-written so the style engine compares and inherits without boxing every value through Prop's accessors.
    // Each method covers exactly the properties its Prop flag selects (ComputedStyleTest cross-checks them by
    // reflection), so a new property must be added here as well as to Prop.

    /** Copies the inherited properties ({@link Prop#inherited}, plus the line-height factor) of {@code parent}. */
    public void copyInheritedFrom(ComputedStyle parent) {
        visibility = parent.visibility;
        color = parent.color;
        fontFamily = parent.fontFamily;
        fontSize = parent.fontSize;
        fontWeight = parent.fontWeight;
        fontItalic = parent.fontItalic;
        lineHeight = parent.lineHeight;
        lineHeightFactor = parent.lineHeightFactor;
        letterSpacing = parent.letterSpacing;
        wordSpacing = parent.wordSpacing;
        textIndent = parent.textIndent;
        textAlign = parent.textAlign;
        textTransform = parent.textTransform;
        underline = parent.underline;
        lineThrough = parent.lineThrough;
        whiteSpace = parent.whiteSpace;
        wordBreak = parent.wordBreak;
        textShadow = parent.textShadow;
        cursor = parent.cursor;
        pointerEvents = parent.pointerEvents;
        userSelect = parent.userSelect;
        imageRendering = parent.imageRendering;
        accentColor = parent.accentColor;
        tint = parent.tint;
        scrollbarThumbColor = parent.scrollbarThumbColor;
        scrollbarTrackColor = parent.scrollbarTrackColor;
        tooltipDelay = parent.tooltipDelay;
        customProperties = parent.customProperties;
    }

    /**
     * Whether the inherited properties (those {@link #copyInheritedFrom} copies) equal {@code o}'s: a child that
     * only inherits from its parent computes the same style under either.
     */
    public boolean sameInherited(ComputedStyle o) {
        return this == o || visibility == o.visibility && color == o.color && fontFamily.equals(o.fontFamily)
                && same(fontSize, o.fontSize) && fontWeight == o.fontWeight && fontItalic == o.fontItalic
                && same(lineHeight, o.lineHeight) && same(lineHeightFactor, o.lineHeightFactor)
                && same(letterSpacing, o.letterSpacing) && same(wordSpacing, o.wordSpacing)
                && same(textIndent, o.textIndent) && textAlign == o.textAlign && textTransform == o.textTransform
                && underline == o.underline && lineThrough == o.lineThrough && whiteSpace == o.whiteSpace
                && wordBreak == o.wordBreak && textShadow.equals(o.textShadow) && cursor == o.cursor
                && pointerEvents == o.pointerEvents && userSelect == o.userSelect
                && imageRendering == o.imageRendering && accentColor == o.accentColor && tint == o.tint
                && scrollbarThumbColor == o.scrollbarThumbColor && scrollbarTrackColor == o.scrollbarTrackColor
                && same(tooltipDelay, o.tooltipDelay) && customProperties.equals(o.customProperties);
    }

    /**
     * Whether the properties that affect layout ({@link Prop#affectsLayout}) equal {@code o}'s, and both have a
     * transform or neither does: a transform's value only moves paint, but having one makes the box the containing
     * block of its positioned descendants.
     */
    public boolean sameLayout(ComputedStyle o) {
        return this == o || hasTransform() == o.hasTransform()
                && display == o.display && position == o.position && boxSizing == o.boxSizing
                && width.equals(o.width) && height.equals(o.height) && minWidth.equals(o.minWidth)
                && minHeight.equals(o.minHeight) && maxWidth.equals(o.maxWidth) && maxHeight.equals(o.maxHeight)
                && top.equals(o.top) && right.equals(o.right) && bottom.equals(o.bottom) && left.equals(o.left)
                && marginTop.equals(o.marginTop) && marginRight.equals(o.marginRight)
                && marginBottom.equals(o.marginBottom) && marginLeft.equals(o.marginLeft)
                && paddingTop.equals(o.paddingTop) && paddingRight.equals(o.paddingRight)
                && paddingBottom.equals(o.paddingBottom) && paddingLeft.equals(o.paddingLeft)
                && same(borderTopWidth, o.borderTopWidth) && same(borderRightWidth, o.borderRightWidth)
                && same(borderBottomWidth, o.borderBottomWidth) && same(borderLeftWidth, o.borderLeftWidth)
                && borderTopStyle == o.borderTopStyle && borderRightStyle == o.borderRightStyle
                && borderBottomStyle == o.borderBottomStyle && borderLeftStyle == o.borderLeftStyle
                && overflowX == o.overflowX && overflowY == o.overflowY && verticalAlign == o.verticalAlign
                && same(aspectRatio, o.aspectRatio)
                && flexDirection == o.flexDirection && flexWrap == o.flexWrap && justifyContent == o.justifyContent
                && alignItems == o.alignItems && alignContent == o.alignContent && alignSelf == o.alignSelf
                && justifyItems == o.justifyItems && justifySelf == o.justifySelf && same(flexGrow, o.flexGrow)
                && same(flexShrink, o.flexShrink) && flexBasis.equals(o.flexBasis) && order == o.order
                && rowGap.equals(o.rowGap) && columnGap.equals(o.columnGap)
                && gridTemplateColumns.equals(o.gridTemplateColumns) && gridTemplateRows.equals(o.gridTemplateRows)
                && Objects.equals(gridTemplateAreas, o.gridTemplateAreas)
                && gridAutoColumns.equals(o.gridAutoColumns) && gridAutoRows.equals(o.gridAutoRows)
                && gridAutoFlow == o.gridAutoFlow && gridColumnStart.equals(o.gridColumnStart)
                && gridColumnEnd.equals(o.gridColumnEnd) && gridRowStart.equals(o.gridRowStart)
                && gridRowEnd.equals(o.gridRowEnd)
                && fontFamily.equals(o.fontFamily) && same(fontSize, o.fontSize) && fontWeight == o.fontWeight
                && fontItalic == o.fontItalic && same(lineHeight, o.lineHeight)
                && same(letterSpacing, o.letterSpacing) && same(wordSpacing, o.wordSpacing)
                && same(textIndent, o.textIndent) && textAlign == o.textAlign && textTransform == o.textTransform
                && whiteSpace == o.whiteSpace && wordBreak == o.wordBreak && lineClamp == o.lineClamp
                && Objects.equals(content, o.content) && scrollbarWidth == o.scrollbarWidth;
    }

    /** Whether every property, and the engine's own fields, equal {@code o}'s. */
    public boolean sameAs(ComputedStyle o) {
        return this == o || sameLayout(o)
                && borderTopColor == o.borderTopColor && borderRightColor == o.borderRightColor
                && borderBottomColor == o.borderBottomColor && borderLeftColor == o.borderLeftColor
                && radiusTopLeft.equals(o.radiusTopLeft) && radiusTopRight.equals(o.radiusTopRight)
                && radiusBottomRight.equals(o.radiusBottomRight) && radiusBottomLeft.equals(o.radiusBottomLeft)
                && zIndexAuto == o.zIndexAuto && (zIndexAuto || zIndex == o.zIndex) && same(opacity, o.opacity)
                && visibility == o.visibility && objectFit == o.objectFit
                && objectPositionX.equals(o.objectPositionX) && objectPositionY.equals(o.objectPositionY)
                && color == o.color && same(lineHeightFactor, o.lineHeightFactor) && underline == o.underline
                && lineThrough == o.lineThrough && textOverflow == o.textOverflow && textShadow.equals(o.textShadow)
                && cursor == o.cursor && pointerEvents == o.pointerEvents && userSelect == o.userSelect
                && imageRendering == o.imageRendering && accentColor == o.accentColor
                && backgroundColor == o.backgroundColor && backgroundLayers.equals(o.backgroundLayers)
                && boxShadow.equals(o.boxShadow) && same(outlineWidth, o.outlineWidth)
                && outlineStyle == o.outlineStyle && outlineColor == o.outlineColor
                && same(outlineOffset, o.outlineOffset) && transform.equals(o.transform)
                && transformOriginX.equals(o.transformOriginX) && transformOriginY.equals(o.transformOriginY)
                && tint == o.tint && same(modelYaw, o.modelYaw) && same(modelPitch, o.modelPitch)
                && same(modelScale, o.modelScale) && entityFocus == o.entityFocus && same(gazeReach, o.gazeReach)
                && same(gazeLimitYaw, o.gazeLimitYaw) && same(gazeLimitUp, o.gazeLimitUp)
                && same(gazeLimitDown, o.gazeLimitDown) && scrollSmooth == o.scrollSmooth
                && scrollbarThumbColor == o.scrollbarThumbColor
                && scrollbarTrackColor == o.scrollbarTrackColor && same(tooltipDelay, o.tooltipDelay)
                && transitions.equals(o.transitions)
                && animations.equals(o.animations) && customProperties.equals(o.customProperties)
                && isFlexOrGridItemHint == o.isFlexOrGridItemHint;
    }

    /**
     * Whether paint order is the same under {@code o}: the same positioning and z-index, and opacity and transforms
     * starting a stacking context alike.
     */
    public boolean sameStacking(ComputedStyle o) {
        return this == o || position == o.position && zIndexAuto == o.zIndexAuto && (zIndexAuto || zIndex == o.zIndex)
                && (opacity < 1f) == (o.opacity < 1f) && hasTransform() == o.hasTransform()
                && isFlexOrGridItemHint == o.isFlexOrGridItemHint;
    }

    /** Float equality as boxed values compare: NaN (normal, auto) equals NaN. */
    private static boolean same(float a, float b) {
        return Float.compare(a, b) == 0;
    }

    // ---- Derived helpers used by layout and paint ----

    /** The used line height in px. */
    public float usedLineHeight() {
        if (!Float.isNaN(lineHeight)) return lineHeight;
        return fontSize * NORMAL_LINE_HEIGHT;
    }

    public boolean isBold() { return fontWeight >= 600; }

    /**
     * How far right of its box's left edge content {@code free} px narrower than the box starts
     * ({@code object-position}; negative {@code free} for content wider than the box). Centred when unset.
     */
    public float objectX(float free) {
        return objectPositionX.resolve(free, free / 2);
    }

    /** As {@link #objectX}, down from the top edge. */
    public float objectY(float free) {
        return objectPositionY.resolve(free, free / 2);
    }

    /**
     * A square as wide as the shorter side of the box {@code (x, y, width, height)} times {@code scale}, placed in it
     * by {@code object-position}, as {@code {x, y, size}}: where a {@code <model>} draws its model.
     */
    public float[] objectSquare(float x, float y, float width, float height, float scale) {
        float size = Math.min(width, height) * scale;
        return new float[] {x + objectX(width - size), y + objectY(height - size), size};
    }

    /**
     * How far a {@code follow-mouse} entity's head turns toward a pointer {@code dx} px right of its eyes, in degrees
     * (positive to the right): {@code 40° × atan(dx / reach)}, as vanilla's inventory turns the player's head (the
     * body takes half), so at most about 63°, then capped by the yaw limit.
     */
    public float gazeYaw(float dx) {
        return gaze(dx, gazeLimitYaw, gazeLimitYaw);
    }

    /** As {@link #gazeYaw}, for a pointer {@code dy} px above the eyes: positive tilts the head up. */
    public float gazePitch(float dy) {
        return gaze(dy, gazeLimitUp, gazeLimitDown);
    }

    /** The turn toward a pointer {@code d} px away, at most {@code plus} one way and {@code minus} the other. */
    private float gaze(float d, float plus, float minus) {
        float turn = (float) (gazeReach > 0 ? Math.atan(d / gazeReach) : Math.signum(d) * Math.PI / 2) * 40f;
        if (!Float.isNaN(plus)) turn = Math.min(turn, plus);
        return Float.isNaN(minus) ? turn : Math.max(turn, -minus);
    }

    /**
     * The font of this style. Memoized: the spec is kept while the font fields are unchanged, so text runs, form
     * controls and hit tests share one instance (and the host's resolution cached on it). Copies share it too, and
     * since the animation engine writes animated values into copies, the cache is checked against the fields on
     * every read rather than trusted.
     */
    public FontSpec font() {
        FontSpec f = font;
        if (f == null || f.families() != fontFamily || Float.compare(f.size(), fontSize) != 0 || f.bold() != isBold()
                || f.italic() != fontItalic) {
            font = f = new FontSpec(fontFamily, fontSize, isBold(), fontItalic);
        }
        return f;
    }

    /** Cache for {@link #font()}; not a property. */
    private transient FontSpec font;

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

    /** Whether a background layer has an image (a {@code background-image} other than {@code none}). */
    public boolean hasBackgroundImage() {
        for (BackgroundLayer layer : backgroundLayers) if (layer.image() != null) return true;
        return false;
    }

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
