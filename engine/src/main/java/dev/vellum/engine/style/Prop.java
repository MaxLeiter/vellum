package dev.vellum.engine.style;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Every longhand property the engine computes, with its CSS name, whether it inherits, whether a change needs a
 * relayout, how it interpolates, and generic accessors on {@link ComputedStyle}.
 *
 * <p>The CSS parser maps declarations (after shorthand expansion) onto these; the animation engine uses
 * {@link #interpolation} and the accessors to animate any property without a hand-written switch.
 */
@SuppressWarnings("unchecked")
public enum Prop {
    DISPLAY("display", false, true, Interp.DISCRETE, s -> s.display, (s, v) -> s.display = (Display) v),
    POSITION("position", false, true, Interp.DISCRETE, s -> s.position, (s, v) -> s.position = (Position) v),
    BOX_SIZING("box-sizing", false, true, Interp.DISCRETE, s -> s.boxSizing, (s, v) -> s.boxSizing = (BoxSizing) v),
    WIDTH("width", false, true, Interp.LENGTH, s -> s.width, (s, v) -> s.width = (Length) v),
    HEIGHT("height", false, true, Interp.LENGTH, s -> s.height, (s, v) -> s.height = (Length) v),
    MIN_WIDTH("min-width", false, true, Interp.LENGTH, s -> s.minWidth, (s, v) -> s.minWidth = (Length) v),
    MIN_HEIGHT("min-height", false, true, Interp.LENGTH, s -> s.minHeight, (s, v) -> s.minHeight = (Length) v),
    MAX_WIDTH("max-width", false, true, Interp.LENGTH, s -> s.maxWidth, (s, v) -> s.maxWidth = (Length) v),
    MAX_HEIGHT("max-height", false, true, Interp.LENGTH, s -> s.maxHeight, (s, v) -> s.maxHeight = (Length) v),
    TOP("top", false, true, Interp.LENGTH, s -> s.top, (s, v) -> s.top = (Length) v),
    RIGHT("right", false, true, Interp.LENGTH, s -> s.right, (s, v) -> s.right = (Length) v),
    BOTTOM("bottom", false, true, Interp.LENGTH, s -> s.bottom, (s, v) -> s.bottom = (Length) v),
    LEFT("left", false, true, Interp.LENGTH, s -> s.left, (s, v) -> s.left = (Length) v),
    MARGIN_TOP("margin-top", false, true, Interp.LENGTH, s -> s.marginTop, (s, v) -> s.marginTop = (Length) v),
    MARGIN_RIGHT("margin-right", false, true, Interp.LENGTH, s -> s.marginRight, (s, v) -> s.marginRight = (Length) v),
    MARGIN_BOTTOM("margin-bottom", false, true, Interp.LENGTH, s -> s.marginBottom, (s, v) -> s.marginBottom = (Length) v),
    MARGIN_LEFT("margin-left", false, true, Interp.LENGTH, s -> s.marginLeft, (s, v) -> s.marginLeft = (Length) v),
    PADDING_TOP("padding-top", false, true, Interp.LENGTH, s -> s.paddingTop, (s, v) -> s.paddingTop = (Length) v),
    PADDING_RIGHT("padding-right", false, true, Interp.LENGTH, s -> s.paddingRight, (s, v) -> s.paddingRight = (Length) v),
    PADDING_BOTTOM("padding-bottom", false, true, Interp.LENGTH, s -> s.paddingBottom, (s, v) -> s.paddingBottom = (Length) v),
    PADDING_LEFT("padding-left", false, true, Interp.LENGTH, s -> s.paddingLeft, (s, v) -> s.paddingLeft = (Length) v),
    BORDER_TOP_WIDTH("border-top-width", false, true, Interp.FLOAT, s -> s.borderTopWidth, (s, v) -> s.borderTopWidth = (Float) v),
    BORDER_RIGHT_WIDTH("border-right-width", false, true, Interp.FLOAT, s -> s.borderRightWidth, (s, v) -> s.borderRightWidth = (Float) v),
    BORDER_BOTTOM_WIDTH("border-bottom-width", false, true, Interp.FLOAT, s -> s.borderBottomWidth, (s, v) -> s.borderBottomWidth = (Float) v),
    BORDER_LEFT_WIDTH("border-left-width", false, true, Interp.FLOAT, s -> s.borderLeftWidth, (s, v) -> s.borderLeftWidth = (Float) v),
    BORDER_TOP_STYLE("border-top-style", false, true, Interp.DISCRETE, s -> s.borderTopStyle, (s, v) -> s.borderTopStyle = (BorderStyle) v),
    BORDER_RIGHT_STYLE("border-right-style", false, true, Interp.DISCRETE, s -> s.borderRightStyle, (s, v) -> s.borderRightStyle = (BorderStyle) v),
    BORDER_BOTTOM_STYLE("border-bottom-style", false, true, Interp.DISCRETE, s -> s.borderBottomStyle, (s, v) -> s.borderBottomStyle = (BorderStyle) v),
    BORDER_LEFT_STYLE("border-left-style", false, true, Interp.DISCRETE, s -> s.borderLeftStyle, (s, v) -> s.borderLeftStyle = (BorderStyle) v),
    BORDER_TOP_COLOR("border-top-color", false, false, Interp.COLOR, s -> s.borderTopColor, (s, v) -> s.borderTopColor = (Integer) v),
    BORDER_RIGHT_COLOR("border-right-color", false, false, Interp.COLOR, s -> s.borderRightColor, (s, v) -> s.borderRightColor = (Integer) v),
    BORDER_BOTTOM_COLOR("border-bottom-color", false, false, Interp.COLOR, s -> s.borderBottomColor, (s, v) -> s.borderBottomColor = (Integer) v),
    BORDER_LEFT_COLOR("border-left-color", false, false, Interp.COLOR, s -> s.borderLeftColor, (s, v) -> s.borderLeftColor = (Integer) v),
    BORDER_TOP_LEFT_RADIUS("border-top-left-radius", false, false, Interp.LENGTH, s -> s.radiusTopLeft, (s, v) -> s.radiusTopLeft = (Length) v),
    BORDER_TOP_RIGHT_RADIUS("border-top-right-radius", false, false, Interp.LENGTH, s -> s.radiusTopRight, (s, v) -> s.radiusTopRight = (Length) v),
    BORDER_BOTTOM_RIGHT_RADIUS("border-bottom-right-radius", false, false, Interp.LENGTH, s -> s.radiusBottomRight, (s, v) -> s.radiusBottomRight = (Length) v),
    BORDER_BOTTOM_LEFT_RADIUS("border-bottom-left-radius", false, false, Interp.LENGTH, s -> s.radiusBottomLeft, (s, v) -> s.radiusBottomLeft = (Length) v),
    OVERFLOW_X("overflow-x", false, true, Interp.DISCRETE, s -> s.overflowX, (s, v) -> s.overflowX = (Overflow) v),
    OVERFLOW_Y("overflow-y", false, true, Interp.DISCRETE, s -> s.overflowY, (s, v) -> s.overflowY = (Overflow) v),
    /** z-index; the value is an Integer, or null for auto. */
    Z_INDEX("z-index", false, false, Interp.INT, s -> s.zIndexAuto ? null : s.zIndex,
            (s, v) -> { s.zIndexAuto = v == null; s.zIndex = v == null ? 0 : (Integer) v; }),
    OPACITY("opacity", false, false, Interp.FLOAT, s -> s.opacity, (s, v) -> s.opacity = (Float) v),
    VISIBILITY("visibility", true, false, Interp.DISCRETE, s -> s.visibility, (s, v) -> s.visibility = (Visibility) v),
    VERTICAL_ALIGN("vertical-align", false, true, Interp.DISCRETE, s -> s.verticalAlign, (s, v) -> s.verticalAlign = (VerticalAlign) v),
    ASPECT_RATIO("aspect-ratio", false, true, Interp.FLOAT, s -> s.aspectRatio, (s, v) -> s.aspectRatio = (Float) v),
    OBJECT_FIT("object-fit", false, false, Interp.DISCRETE, s -> s.objectFit, (s, v) -> s.objectFit = (ObjectFit) v),
    /** {@code object-position}'s axes ({@link Length#AUTO} until set). */
    OBJECT_POSITION_X("-vellum-object-position-x", false, false, Interp.LENGTH, s -> s.objectPositionX, (s, v) -> s.objectPositionX = (Length) v),
    OBJECT_POSITION_Y("-vellum-object-position-y", false, false, Interp.LENGTH, s -> s.objectPositionY, (s, v) -> s.objectPositionY = (Length) v),

    FLEX_DIRECTION("flex-direction", false, true, Interp.DISCRETE, s -> s.flexDirection, (s, v) -> s.flexDirection = (FlexDirection) v),
    FLEX_WRAP("flex-wrap", false, true, Interp.DISCRETE, s -> s.flexWrap, (s, v) -> s.flexWrap = (FlexWrap) v),
    JUSTIFY_CONTENT("justify-content", false, true, Interp.DISCRETE, s -> s.justifyContent, (s, v) -> s.justifyContent = (Align) v),
    ALIGN_ITEMS("align-items", false, true, Interp.DISCRETE, s -> s.alignItems, (s, v) -> s.alignItems = (Align) v),
    ALIGN_CONTENT("align-content", false, true, Interp.DISCRETE, s -> s.alignContent, (s, v) -> s.alignContent = (Align) v),
    ALIGN_SELF("align-self", false, true, Interp.DISCRETE, s -> s.alignSelf, (s, v) -> s.alignSelf = (Align) v),
    JUSTIFY_ITEMS("justify-items", false, true, Interp.DISCRETE, s -> s.justifyItems, (s, v) -> s.justifyItems = (Align) v),
    JUSTIFY_SELF("justify-self", false, true, Interp.DISCRETE, s -> s.justifySelf, (s, v) -> s.justifySelf = (Align) v),
    FLEX_GROW("flex-grow", false, true, Interp.FLOAT, s -> s.flexGrow, (s, v) -> s.flexGrow = (Float) v),
    FLEX_SHRINK("flex-shrink", false, true, Interp.FLOAT, s -> s.flexShrink, (s, v) -> s.flexShrink = (Float) v),
    FLEX_BASIS("flex-basis", false, true, Interp.LENGTH, s -> s.flexBasis, (s, v) -> s.flexBasis = (Length) v),
    ORDER("order", false, true, Interp.INT, s -> s.order, (s, v) -> s.order = (Integer) v),
    ROW_GAP("row-gap", false, true, Interp.LENGTH, s -> s.rowGap, (s, v) -> s.rowGap = (Length) v),
    COLUMN_GAP("column-gap", false, true, Interp.LENGTH, s -> s.columnGap, (s, v) -> s.columnGap = (Length) v),

    GRID_TEMPLATE_COLUMNS("grid-template-columns", false, true, Interp.DISCRETE, s -> s.gridTemplateColumns, (s, v) -> s.gridTemplateColumns = (List<GridTrack>) v),
    GRID_TEMPLATE_ROWS("grid-template-rows", false, true, Interp.DISCRETE, s -> s.gridTemplateRows, (s, v) -> s.gridTemplateRows = (List<GridTrack>) v),
    GRID_TEMPLATE_AREAS("grid-template-areas", false, true, Interp.DISCRETE, s -> s.gridTemplateAreas, (s, v) -> s.gridTemplateAreas = (List<List<String>>) v),
    GRID_AUTO_COLUMNS("grid-auto-columns", false, true, Interp.DISCRETE, s -> s.gridAutoColumns, (s, v) -> s.gridAutoColumns = (List<GridTrack>) v),
    GRID_AUTO_ROWS("grid-auto-rows", false, true, Interp.DISCRETE, s -> s.gridAutoRows, (s, v) -> s.gridAutoRows = (List<GridTrack>) v),
    GRID_AUTO_FLOW("grid-auto-flow", false, true, Interp.DISCRETE, s -> s.gridAutoFlow, (s, v) -> s.gridAutoFlow = (GridAutoFlow) v),
    GRID_COLUMN_START("grid-column-start", false, true, Interp.DISCRETE, s -> s.gridColumnStart, (s, v) -> s.gridColumnStart = (GridLine) v),
    GRID_COLUMN_END("grid-column-end", false, true, Interp.DISCRETE, s -> s.gridColumnEnd, (s, v) -> s.gridColumnEnd = (GridLine) v),
    GRID_ROW_START("grid-row-start", false, true, Interp.DISCRETE, s -> s.gridRowStart, (s, v) -> s.gridRowStart = (GridLine) v),
    GRID_ROW_END("grid-row-end", false, true, Interp.DISCRETE, s -> s.gridRowEnd, (s, v) -> s.gridRowEnd = (GridLine) v),

    COLOR("color", true, false, Interp.COLOR, s -> s.color, (s, v) -> s.color = (Integer) v),
    FONT_FAMILY("font-family", true, true, Interp.DISCRETE, s -> s.fontFamily, (s, v) -> s.fontFamily = (List<String>) v),
    FONT_SIZE("font-size", true, true, Interp.FLOAT, s -> s.fontSize, (s, v) -> s.fontSize = (Float) v),
    FONT_WEIGHT("font-weight", true, true, Interp.INT, s -> s.fontWeight, (s, v) -> s.fontWeight = (Integer) v),
    FONT_STYLE("font-style", true, true, Interp.DISCRETE, s -> s.fontItalic, (s, v) -> s.fontItalic = (Boolean) v),
    LINE_HEIGHT("line-height", true, true, Interp.FLOAT, s -> s.lineHeight, (s, v) -> s.lineHeight = (Float) v),
    LETTER_SPACING("letter-spacing", true, true, Interp.FLOAT, s -> s.letterSpacing, (s, v) -> s.letterSpacing = (Float) v),
    WORD_SPACING("word-spacing", true, true, Interp.FLOAT, s -> s.wordSpacing, (s, v) -> s.wordSpacing = (Float) v),
    TEXT_INDENT("text-indent", true, true, Interp.FLOAT, s -> s.textIndent, (s, v) -> s.textIndent = (Float) v),
    TEXT_ALIGN("text-align", true, true, Interp.DISCRETE, s -> s.textAlign, (s, v) -> s.textAlign = (TextAlign) v),
    TEXT_TRANSFORM("text-transform", true, true, Interp.DISCRETE, s -> s.textTransform, (s, v) -> s.textTransform = (TextTransform) v),
    TEXT_DECORATION_UNDERLINE("-vellum-underline", true, false, Interp.DISCRETE, s -> s.underline, (s, v) -> s.underline = (Boolean) v),
    TEXT_DECORATION_LINE_THROUGH("-vellum-line-through", true, false, Interp.DISCRETE, s -> s.lineThrough, (s, v) -> s.lineThrough = (Boolean) v),
    WHITE_SPACE("white-space", true, true, Interp.DISCRETE, s -> s.whiteSpace, (s, v) -> s.whiteSpace = (WhiteSpace) v),
    WORD_BREAK("word-break", true, true, Interp.DISCRETE, s -> s.wordBreak, (s, v) -> s.wordBreak = (WordBreak) v),
    TEXT_OVERFLOW("text-overflow", false, false, Interp.DISCRETE, s -> s.textOverflow, (s, v) -> s.textOverflow = (TextOverflow) v),
    LINE_CLAMP("line-clamp", false, true, Interp.DISCRETE, s -> s.lineClamp, (s, v) -> s.lineClamp = (Integer) v),
    TEXT_SHADOW("text-shadow", true, false, Interp.SHADOWS, s -> s.textShadow, (s, v) -> s.textShadow = (List<Shadow>) v),
    CURSOR("cursor", true, false, Interp.DISCRETE, s -> s.cursor, (s, v) -> s.cursor = (Cursor) v),
    POINTER_EVENTS("pointer-events", true, false, Interp.DISCRETE, s -> s.pointerEvents, (s, v) -> s.pointerEvents = (PointerEvents) v),
    USER_SELECT("user-select", true, false, Interp.DISCRETE, s -> s.userSelect, (s, v) -> s.userSelect = (UserSelect) v),
    IMAGE_RENDERING("image-rendering", true, false, Interp.DISCRETE, s -> s.imageRendering, (s, v) -> s.imageRendering = (ImageRendering) v),
    ACCENT_COLOR("accent-color", true, false, Interp.COLOR, s -> s.accentColor, (s, v) -> s.accentColor = (Integer) v),

    BACKGROUND_COLOR("background-color", false, false, Interp.COLOR, s -> s.backgroundColor, (s, v) -> s.backgroundColor = (Integer) v),
    BACKGROUND_LAYERS("background-image", false, false, Interp.DISCRETE, s -> s.backgroundLayers, (s, v) -> s.backgroundLayers = (List<BackgroundLayer>) v),
    BOX_SHADOW("box-shadow", false, false, Interp.SHADOWS, s -> s.boxShadow, (s, v) -> s.boxShadow = (List<Shadow>) v),
    OUTLINE_WIDTH("outline-width", false, false, Interp.FLOAT, s -> s.outlineWidth, (s, v) -> s.outlineWidth = (Float) v),
    OUTLINE_STYLE("outline-style", false, false, Interp.DISCRETE, s -> s.outlineStyle, (s, v) -> s.outlineStyle = (BorderStyle) v),
    OUTLINE_COLOR("outline-color", false, false, Interp.COLOR, s -> s.outlineColor, (s, v) -> s.outlineColor = (Integer) v),
    OUTLINE_OFFSET("outline-offset", false, false, Interp.FLOAT, s -> s.outlineOffset, (s, v) -> s.outlineOffset = (Float) v),
    TRANSFORM("transform", false, false, Interp.TRANSFORM, s -> s.transform, (s, v) -> s.transform = (List<TransformFunction>) v),
    TRANSFORM_ORIGIN_X("-vellum-transform-origin-x", false, false, Interp.LENGTH, s -> s.transformOriginX, (s, v) -> s.transformOriginX = (Length) v),
    TRANSFORM_ORIGIN_Y("-vellum-transform-origin-y", false, false, Interp.LENGTH, s -> s.transformOriginY, (s, v) -> s.transformOriginY = (Length) v),
    TINT("-mc-tint", true, false, Interp.COLOR, s -> s.tint, (s, v) -> s.tint = (Integer) v),
    MODEL_YAW("-mc-yaw", false, false, Interp.FLOAT, s -> s.modelYaw, (s, v) -> s.modelYaw = (Float) v),
    MODEL_PITCH("-mc-pitch", false, false, Interp.FLOAT, s -> s.modelPitch, (s, v) -> s.modelPitch = (Float) v),
    MODEL_SCALE("-mc-model-scale", false, false, Interp.FLOAT, s -> s.modelScale, (s, v) -> s.modelScale = (Float) v),
    ENTITY_FOCUS("-mc-entity-focus", false, false, Interp.DISCRETE, s -> s.entityFocus, (s, v) -> s.entityFocus = (EntityFocus) v),
    GAZE_REACH("-mc-gaze-reach", false, false, Interp.FLOAT, s -> s.gazeReach, (s, v) -> s.gazeReach = (Float) v),
    /** {@code -mc-gaze-limit}'s parts (NaN for none). */
    GAZE_LIMIT_YAW("-vellum-gaze-limit-yaw", false, false, Interp.FLOAT, s -> s.gazeLimitYaw, (s, v) -> s.gazeLimitYaw = (Float) v),
    GAZE_LIMIT_UP("-vellum-gaze-limit-up", false, false, Interp.FLOAT, s -> s.gazeLimitUp, (s, v) -> s.gazeLimitUp = (Float) v),
    GAZE_LIMIT_DOWN("-vellum-gaze-limit-down", false, false, Interp.FLOAT, s -> s.gazeLimitDown, (s, v) -> s.gazeLimitDown = (Float) v),

    SCROLL_BEHAVIOR("scroll-behavior", false, false, Interp.DISCRETE, s -> s.scrollSmooth, (s, v) -> s.scrollSmooth = (Boolean) v),
    SCROLLBAR_WIDTH("scrollbar-width", false, true, Interp.DISCRETE, s -> s.scrollbarWidth, (s, v) -> s.scrollbarWidth = (Integer) v),
    SCROLLBAR_THUMB_COLOR("-vellum-scrollbar-thumb-color", true, false, Interp.COLOR, s -> s.scrollbarThumbColor, (s, v) -> s.scrollbarThumbColor = (Integer) v),
    SCROLLBAR_TRACK_COLOR("-vellum-scrollbar-track-color", true, false, Interp.COLOR, s -> s.scrollbarTrackColor, (s, v) -> s.scrollbarTrackColor = (Integer) v),

    TRANSITION("transition", false, false, Interp.NONE, s -> s.transitions, (s, v) -> s.transitions = (List<TransitionSpec>) v),
    ANIMATION("animation", false, false, Interp.NONE, s -> s.animations, (s, v) -> s.animations = (List<AnimationSpec>) v),
    CONTENT("content", false, true, Interp.DISCRETE, s -> s.content, (s, v) -> s.content = (String) v),
    CUSTOM_PROPERTIES("--*", true, false, Interp.NONE, s -> s.customProperties, (s, v) -> s.customProperties = (Map<String, String>) v);

    /** How a property animates. DISCRETE flips at the midpoint; NONE is not animatable. */
    public enum Interp { NONE, DISCRETE, LENGTH, FLOAT, INT, COLOR, SHADOWS, TRANSFORM }

    public final String cssName;
    public final boolean inherited;
    /** A change requires relayout (otherwise repaint is enough). */
    public final boolean affectsLayout;
    public final Interp interpolation;
    private final Function<ComputedStyle, Object> getter;
    private final BiConsumer<ComputedStyle, Object> setter;

    Prop(String cssName, boolean inherited, boolean affectsLayout, Interp interpolation,
         Function<ComputedStyle, Object> getter, BiConsumer<ComputedStyle, Object> setter) {
        this.cssName = cssName;
        this.inherited = inherited;
        this.affectsLayout = affectsLayout;
        this.interpolation = interpolation;
        this.getter = getter;
        this.setter = setter;
    }

    public Object get(ComputedStyle s) { return getter.apply(s); }

    public void set(ComputedStyle s, Object value) { setter.accept(s, value); }

    private static final Map<String, Prop> BY_NAME = new HashMap<>();

    static {
        for (Prop p : values()) BY_NAME.put(p.cssName, p);
        // Common aliases so transitions can name the CSS property people expect.
        BY_NAME.put("transform-origin", TRANSFORM_ORIGIN_X);
        BY_NAME.put("background", BACKGROUND_COLOR);
    }

    /** Looks up a longhand by CSS name, or null. */
    public static Prop byName(String cssName) {
        return BY_NAME.get(cssName);
    }

    /**
     * Expands a name used in {@code transition-property} into the longhands it covers ({@code border-color},
     * {@code margin}, {@code inset}, {@code padding}, {@code border-radius}, {@code background}...).
     */
    public static EnumSet<Prop> forTransitionName(String name) {
        EnumSet<Prop> set = EnumSet.noneOf(Prop.class);
        switch (name) {
            case "all" -> {
                for (Prop p : values()) if (p.interpolation != Interp.NONE && p.interpolation != Interp.DISCRETE) set.add(p);
                set.add(VISIBILITY); // discrete, but CSS interpolates it specially so fades can hide at the end
            }
            case "margin" -> set.addAll(List.of(MARGIN_TOP, MARGIN_RIGHT, MARGIN_BOTTOM, MARGIN_LEFT));
            case "padding" -> set.addAll(List.of(PADDING_TOP, PADDING_RIGHT, PADDING_BOTTOM, PADDING_LEFT));
            case "inset" -> set.addAll(List.of(TOP, RIGHT, BOTTOM, LEFT));
            case "border-color" -> set.addAll(List.of(BORDER_TOP_COLOR, BORDER_RIGHT_COLOR, BORDER_BOTTOM_COLOR, BORDER_LEFT_COLOR));
            case "border-width" -> set.addAll(List.of(BORDER_TOP_WIDTH, BORDER_RIGHT_WIDTH, BORDER_BOTTOM_WIDTH, BORDER_LEFT_WIDTH));
            case "border" -> {
                set.addAll(forTransitionName("border-color"));
                set.addAll(forTransitionName("border-width"));
            }
            case "border-radius" -> set.addAll(List.of(BORDER_TOP_LEFT_RADIUS, BORDER_TOP_RIGHT_RADIUS,
                    BORDER_BOTTOM_RIGHT_RADIUS, BORDER_BOTTOM_LEFT_RADIUS));
            case "background" -> set.add(BACKGROUND_COLOR);
            case "transform-origin" -> set.addAll(List.of(TRANSFORM_ORIGIN_X, TRANSFORM_ORIGIN_Y));
            case "object-position" -> set.addAll(List.of(OBJECT_POSITION_X, OBJECT_POSITION_Y));
            case "-mc-gaze-limit" -> set.addAll(List.of(GAZE_LIMIT_YAW, GAZE_LIMIT_UP, GAZE_LIMIT_DOWN));
            case "outline" -> set.addAll(List.of(OUTLINE_WIDTH, OUTLINE_COLOR, OUTLINE_OFFSET));
            case "gap" -> set.addAll(List.of(ROW_GAP, COLUMN_GAP));
            case "flex" -> set.addAll(List.of(FLEX_GROW, FLEX_SHRINK, FLEX_BASIS));
            case "font" -> set.addAll(List.of(FONT_SIZE, FONT_WEIGHT, LINE_HEIGHT));
            case "text-decoration" -> set.addAll(List.of(TEXT_DECORATION_UNDERLINE, TEXT_DECORATION_LINE_THROUGH));
            default -> {
                Prop p = byName(name);
                if (p != null) set.add(p);
            }
        }
        return set;
    }
}
