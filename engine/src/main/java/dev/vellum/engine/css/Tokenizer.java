package dev.vellum.engine.css;

import dev.vellum.engine.css.Token.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * The CSS Syntax 3 tokenizer (§4.3). Never fails: malformed input becomes bad-string, bad-url or delimiter tokens,
 * which the parsers treat as errors where it matters. Comments are dropped. Tokens carry their line number.
 */
final class Tokenizer {
    private static final int EOF = -1;

    private final String src;
    private final List<Token> out = new ArrayList<>();
    private int pos;
    private int line = 1, linePos;

    private Tokenizer(String src) {
        this.src = src;
    }

    static List<Token> tokenize(String src) {
        Tokenizer t = new Tokenizer(src);
        t.run();
        return t.out;
    }

    private void run() {
        while (true) {
            skipComments();
            if (pos >= src.length()) return;
            int start = pos;
            Token.Type type = null;
            String value = "";
            double number = 0;
            boolean flag = false;
            int c = src.charAt(pos++);
            switch (c) {
                case ' ', '\t', '\n', '\r', '\f' -> {
                    while (isWhitespace(peek(0))) pos++;
                    type = Type.WHITESPACE;
                }
                case '"', '\'' -> {
                    StringBuilder sb = new StringBuilder();
                    type = consumeString(c, sb);
                    value = sb.toString();
                }
                case '#' -> {
                    if (isName(peek(0)) || isValidEscape(peek(0), peek(1))) {
                        flag = startsIdent(peek(0), peek(1), peek(2));
                        type = Type.HASH;
                        value = consumeName();
                    }
                }
                case '(' -> type = Type.OPEN_PAREN;
                case ')' -> type = Type.CLOSE_PAREN;
                case '[' -> type = Type.OPEN_SQUARE;
                case ']' -> type = Type.CLOSE_SQUARE;
                case '{' -> type = Type.OPEN_CURLY;
                case '}' -> type = Type.CLOSE_CURLY;
                case ',' -> type = Type.COMMA;
                case ':' -> type = Type.COLON;
                case ';' -> type = Type.SEMICOLON;
                case '<' -> {
                    if (src.startsWith("!--", pos)) {
                        pos += 3;
                        type = Type.CDO;
                    }
                }
                case '@' -> {
                    if (startsIdent(peek(0), peek(1), peek(2))) {
                        type = Type.AT_KEYWORD;
                        value = consumeName();
                    }
                }
                default -> {
                    pos--;
                    if (startsNumber(c, peek(1), peek(2))) {
                        emitNumeric(start);
                        continue;
                    }
                    if (c == '-' && peek(1) == '-' && peek(2) == '>') {
                        pos += 3;
                        type = Type.CDC;
                    } else if (startsIdent(c, peek(1), peek(2))) {
                        emitIdentLike(start);
                        continue;
                    } else {
                        pos++;
                    }
                }
            }
            if (type == null) {
                type = Type.DELIM;
                value = String.valueOf((char) c);
            }
            emit(type, value, number, flag, start);
        }
    }

    private void emit(Type type, String value, double number, boolean flag, int start) {
        // Lines are counted lazily, from the previous token: \r\n counts once, \r and \f count as newlines.
        for (int i = linePos; i < start; i++) {
            char ch = src.charAt(i);
            if (ch == '\n' || ch == '\f' || (ch == '\r' && (i + 1 >= src.length() || src.charAt(i + 1) != '\n'))) line++;
        }
        linePos = start;
        out.add(new Token(type, value, number, flag, src, start, pos, line));
    }

    private void skipComments() {
        while (src.startsWith("/*", pos)) {
            int close = src.indexOf("*/", pos + 2);
            pos = close < 0 ? src.length() : close + 2;
        }
    }

    private int peek(int offset) {
        int i = pos + offset;
        return i < src.length() ? src.charAt(i) : EOF;
    }

    // ---- Numbers ----

    private void emitNumeric(int start) {
        boolean integer = true;
        if (peek(0) == '+' || peek(0) == '-') pos++;
        while (isDigit(peek(0))) pos++;
        if (peek(0) == '.' && isDigit(peek(1))) {
            integer = false;
            pos++;
            while (isDigit(peek(0))) pos++;
        }
        int e = peek(0);
        if ((e == 'e' || e == 'E') && (isDigit(peek(1)) || ((peek(1) == '+' || peek(1) == '-') && isDigit(peek(2))))) {
            integer = false;
            pos += 2;
            while (isDigit(peek(0))) pos++;
        }
        double number = Double.parseDouble(src.substring(start, pos));
        if (startsIdent(peek(0), peek(1), peek(2))) {
            emit(Type.DIMENSION, consumeName(), number, integer, start);
        } else if (peek(0) == '%') {
            pos++;
            emit(Type.PERCENTAGE, "", number, integer, start);
        } else {
            emit(Type.NUMBER, "", number, integer, start);
        }
    }

    // ---- Idents, functions and urls ----

    private void emitIdentLike(int start) {
        String name = consumeName();
        if (peek(0) != '(') {
            emit(Type.IDENT, name, 0, false, start);
            return;
        }
        pos++;
        if (name.equalsIgnoreCase("url")) {
            int save = pos;
            while (isWhitespace(peek(0))) pos++;
            if (peek(0) != '"' && peek(0) != '\'') {
                emitUrl(start);
                return;
            }
            pos = save; // a quoted url() is an ordinary function holding a string
        }
        emit(Type.FUNCTION, name, 0, false, start);
    }

    private void emitUrl(int start) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek(0);
            if (c == EOF) break;
            pos++;
            if (c == ')') break;
            if (isWhitespace(c)) {
                while (isWhitespace(peek(0))) pos++;
                if (peek(0) == ')' || peek(0) == EOF) {
                    if (peek(0) == ')') pos++;
                    break;
                }
                consumeBadUrl(start);
                return;
            }
            if (c == '"' || c == '\'' || c == '(' || isNonPrintable(c)) {
                consumeBadUrl(start);
                return;
            }
            if (c == '\\') {
                if (!isValidEscape(c, peek(0))) {
                    consumeBadUrl(start);
                    return;
                }
                sb.appendCodePoint(consumeEscape());
            } else {
                sb.append((char) c);
            }
        }
        emit(Type.URL, sb.toString(), 0, false, start);
    }

    private void consumeBadUrl(int start) {
        while (peek(0) != EOF) {
            int c = src.charAt(pos++);
            if (c == ')') break;
            if (c == '\\' && isValidEscape(c, peek(0))) consumeEscape();
        }
        emit(Type.BAD_URL, "", 0, false, start);
    }

    private Type consumeString(int quote, StringBuilder sb) {
        while (true) {
            int c = peek(0);
            if (c == EOF) return Type.STRING;
            if (c == '\n' || c == '\r' || c == '\f') return Type.BAD_STRING; // the newline is not consumed
            pos++;
            if (c == quote) return Type.STRING;
            if (c == '\\') {
                int n = peek(0);
                if (n == EOF) continue;
                if (n == '\n' || n == '\f') pos++;
                else if (n == '\r') pos += peek(1) == '\n' ? 2 : 1;
                else sb.appendCodePoint(consumeEscape());
            } else {
                sb.append((char) c);
            }
        }
    }

    private String consumeName() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek(0);
            if (isName(c)) {
                sb.append((char) c);
                pos++;
            } else if (isValidEscape(c, peek(1))) {
                pos++;
                sb.appendCodePoint(consumeEscape());
            } else {
                return sb.toString();
            }
        }
    }

    /** Consumes an escape after its backslash and returns the code point. */
    private int consumeEscape() {
        int c = peek(0);
        if (c == EOF) return 0xFFFD;
        if (!isHex(c)) {
            pos++;
            if (Character.isHighSurrogate((char) c) && Character.isLowSurrogate((char) peek(0))) {
                return Character.toCodePoint((char) c, src.charAt(pos++));
            }
            return c;
        }
        int value = 0;
        for (int i = 0; i < 6 && isHex(peek(0)); i++) value = value * 16 + Character.digit(src.charAt(pos++), 16);
        if (peek(0) == '\r' && peek(1) == '\n') pos += 2;
        else if (isWhitespace(peek(0))) pos++;
        boolean invalid = value == 0 || value > Character.MAX_CODE_POINT || (value >= 0xD800 && value <= 0xDFFF);
        return invalid ? 0xFFFD : value;
    }

    // ---- Character classes (§4.2) ----

    static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isDigit(int c) { return c >= '0' && c <= '9'; }

    private static boolean isHex(int c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isNameStart(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
    }

    private static boolean isName(int c) {
        return isNameStart(c) || isDigit(c) || c == '-';
    }

    private static boolean isNonPrintable(int c) {
        return (c >= 0 && c <= 8) || c == 0x0B || (c >= 0x0E && c <= 0x1F) || c == 0x7F;
    }

    private static boolean isValidEscape(int c1, int c2) {
        return c1 == '\\' && c2 != '\n' && c2 != '\r' && c2 != '\f' && c2 != EOF;
    }

    private static boolean startsIdent(int c1, int c2, int c3) {
        if (c1 == '-') return isNameStart(c2) || c2 == '-' || isValidEscape(c2, c3);
        if (isNameStart(c1)) return true;
        return isValidEscape(c1, c2);
    }

    private static boolean startsNumber(int c1, int c2, int c3) {
        if (c1 == '+' || c1 == '-') return isDigit(c2) || (c2 == '.' && isDigit(c3));
        if (c1 == '.') return isDigit(c2);
        return isDigit(c1);
    }
}
