package dev.vellum.engine.css;

/** A CSS token (CSS Syntax 3 §4), with escapes resolved and its source range kept. */
final class Token implements ComponentValue {
    enum Type {
        IDENT, FUNCTION, AT_KEYWORD, HASH, STRING, BAD_STRING, URL, BAD_URL, DELIM, NUMBER, PERCENTAGE, DIMENSION,
        WHITESPACE, CDO, CDC, COLON, SEMICOLON, COMMA, OPEN_SQUARE, CLOSE_SQUARE, OPEN_PAREN, CLOSE_PAREN,
        OPEN_CURLY, CLOSE_CURLY
    }

    final Type type;
    /**
     * The name of an ident, function, at-keyword or hash; the contents of a string or url; the delimiter character;
     * a dimension's unit. Empty for other tokens.
     */
    final String value;
    /** {@link #value} in lower case, for keywords and units (which are ASCII case-insensitive). */
    final String lower;
    /** The numeric value of numbers, percentages and dimensions. */
    final double number;
    /** Whether a numeric token has integer type (no fraction or exponent); for hashes, whether it is an id. */
    final boolean flag;
    private final String source;
    private final int start, end, line;

    Token(Type type, String value, double number, boolean flag, String source, int start, int end, int line) {
        this.type = type;
        this.value = value;
        this.lower = value.toLowerCase(java.util.Locale.ROOT);
        this.number = number;
        this.flag = flag;
        this.source = source;
        this.start = start;
        this.end = end;
        this.line = line;
    }

    @Override public String source() { return source; }
    @Override public int start() { return start; }
    @Override public int end() { return end; }
    @Override public int line() { return line; }

    boolean is(Type t) { return type == t; }

    boolean isIdent(String lowerName) { return type == Type.IDENT && lower.equals(lowerName); }

    boolean isDelim(char c) { return type == Type.DELIM && value.charAt(0) == c; }

    @Override
    public String toString() {
        return type + "(" + text() + ")";
    }
}
