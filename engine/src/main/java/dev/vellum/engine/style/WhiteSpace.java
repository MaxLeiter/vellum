package dev.vellum.engine.style;

/** The {@code white-space} property. */
public enum WhiteSpace {
    NORMAL, NOWRAP, PRE, PRE_WRAP, PRE_LINE, BREAK_SPACES;

    /** Whether runs of spaces and tabs collapse to one space. */
    public boolean collapsesSpaces() { return this == NORMAL || this == NOWRAP || this == PRE_LINE; }
    /** Whether newlines in the source are preserved as forced breaks. */
    public boolean preservesNewlines() { return this != NORMAL && this != NOWRAP; }
    /** Whether lines may wrap at soft wrap opportunities. */
    public boolean wraps() { return this != NOWRAP && this != PRE; }
}
