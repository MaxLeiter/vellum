package dev.vellum.engine.style;

/**
 * One layer of {@code background-image} with its size, position, repeat and clip. Layers are painted
 * last-to-first, over {@code background-color}.
 *
 * @param sizeKeyword {@code null} for explicit {@code width}/{@code height} (each may be {@link Length#AUTO}),
 *                    or {@code "cover"} / {@code "contain"}
 */
public record BackgroundLayer(Image image, String sizeKeyword, Length width, Length height,
                              Length positionX, Length positionY, Repeat repeatX, Repeat repeatY, Box clip) {
    public enum Repeat { REPEAT, NO_REPEAT, SPACE, ROUND }
    public enum Box { BORDER_BOX, PADDING_BOX, CONTENT_BOX }

    public static BackgroundLayer simple(Image image) {
        return new BackgroundLayer(image, null, Length.AUTO, Length.AUTO, Length.ZERO, Length.ZERO,
                Repeat.REPEAT, Repeat.REPEAT, Box.BORDER_BOX);
    }
}
