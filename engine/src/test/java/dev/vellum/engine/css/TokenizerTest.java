package dev.vellum.engine.css;

import dev.vellum.engine.css.Token.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerTest {
    /** Tokens as "TYPE:value", whitespace omitted. */
    private static String tokens(String css) {
        return Tokenizer.tokenize(css).stream().filter(t -> !t.is(Type.WHITESPACE))
                .map(t -> t.type + (t.value.isEmpty() ? "" : ":" + t.value)).collect(Collectors.joining(" "));
    }

    private static Token single(String css) {
        List<Token> t = Tokenizer.tokenize(css);
        assertEquals(1, t.size(), () -> "tokens of " + css + ": " + t);
        return t.get(0);
    }

    @Test
    void identsFunctionsAndAtKeywords() {
        assertEquals("IDENT:color COLON IDENT:red SEMICOLON", tokens("color: red;"));
        assertEquals("FUNCTION:rgb NUMBER COMMA NUMBER CLOSE_PAREN", tokens("rgb(1,2)"));
        assertEquals("AT_KEYWORD:media IDENT:screen", tokens("@media screen"));
        assertEquals("IDENT:--my-var IDENT:-webkit-x IDENT:_u", tokens("--my-var -webkit-x _u"));
        assertEquals("IDENT:minecraft COLON IDENT:uniform", tokens("minecraft:uniform"));
    }

    @Test
    void numbersPercentagesDimensions() {
        Token t = single("12.5px");
        assertEquals(Type.DIMENSION, t.type);
        assertEquals(12.5, t.number);
        assertEquals("px", t.lower);
        assertFalse(t.flag);
        assertTrue(single("+3").flag);
        assertEquals(-0.5, single("-.5").number);
        assertEquals(1000, single("1e3").number);
        assertEquals(Type.PERCENTAGE, single("50%").type);
        assertEquals("em", single("1EM").lower);
        assertEquals("DIMENSION:n NUMBER", tokens("2n+1"));
        assertEquals("DIMENSION:n-1", tokens("2n-1"));
        assertEquals("NUMBER DELIM:- NUMBER", tokens("1 - 2"));
    }

    @Test
    void stringsAndEscapes() {
        assertEquals("a\"b", single("\"a\\\"b\"").value);
        assertEquals("it's", single("'it\\'s'").value);
        assertEquals("A", single("\"\\41\"").value);
        assertEquals("ab", single("\"a\\\nb\"").value);
        assertEquals("\uFFFD", single("\"\\0\"").value);
        assertEquals(Type.BAD_STRING, Tokenizer.tokenize("\"abc\ndef").get(0).type);
        assertEquals("unterminated", single("\"unterminated").value);
        assertEquals("IDENT:a.b", tokens("a\\.b"));
        assertEquals("IDENT:\u2764x", tokens("\\2764 x"));
    }

    @Test
    void hashes() {
        Token id = single("#main");
        assertEquals(Type.HASH, id.type);
        assertTrue(id.flag);
        Token color = single("#123abc");
        assertEquals("123abc", color.value);
        assertFalse(color.flag);
        assertEquals("DELIM:#", tokens("#"));
    }

    @Test
    void urls() {
        Token u = single("url(  img/a.png  )");
        assertEquals(Type.URL, u.type);
        assertEquals("img/a.png", u.value);
        assertEquals("FUNCTION:url STRING:a b.png CLOSE_PAREN", tokens("url( \"a b.png\")"));
        assertEquals(Type.BAD_URL, single("url(a b)").type);
        assertEquals(Type.BAD_URL, single("url(a\"b)").type);
        assertEquals("URL:a)b", tokens("url(a\\)b)"));
    }

    @Test
    void commentsCdoCdcAndDelims() {
        assertEquals("IDENT:a IDENT:b", tokens("a/* comment */ b /* unterminated"));
        assertEquals("CDO IDENT:x CDC", tokens("<!-- x -->"));
        assertEquals("DELIM:> DELIM:+ DELIM:~ DELIM:* DELIM:! DELIM:<", tokens("> + ~ * ! <"));
        assertEquals("OPEN_CURLY OPEN_SQUARE OPEN_PAREN CLOSE_PAREN CLOSE_SQUARE CLOSE_CURLY", tokens("{[()]}"));
    }

    @Test
    void lineNumbersCountEveryNewlineStyle() {
        List<Token> t = Tokenizer.tokenize("a\nb\r\nc\rd\fe");
        assertEquals(List.of(1, 2, 3, 4, 5), t.stream().filter(x -> x.is(Type.IDENT)).map(Token::line).toList());
    }

    @Test
    void sourceTextIsExact() {
        List<ComponentValue> values = CssParser.parseComponentValues("calc( 1px  +  2em )   red");
        assertEquals("calc( 1px  +  2em )   red", ComponentValue.text(values));
        assertEquals("calc( 1px  +  2em )", values.get(0).text());
    }
}
