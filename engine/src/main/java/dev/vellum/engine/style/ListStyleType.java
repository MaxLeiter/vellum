package dev.vellum.engine.style;

/** The {@code list-style-type} property: the bullet the user agent stylesheet puts before a {@code <ul>}'s items. */
public enum ListStyleType {
    DISC("• "), CIRCLE("◦ "), SQUARE("▪ "), NONE(null);

    /** The marker text, or null for none. */
    public final String marker;

    ListStyleType(String marker) {
        this.marker = marker;
    }
}
