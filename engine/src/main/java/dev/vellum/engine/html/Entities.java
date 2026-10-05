package dev.vellum.engine.html;

import java.util.HashMap;
import java.util.Map;

/** Character references: numeric ({@code &#123;}, {@code &#x1F600;}) and the commonly used named entities. */
final class Entities {
    private static final Map<String, String> NAMED = new HashMap<>();

    static {
        String[] pairs = {
                "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ",
                "copy", "©", "reg", "®", "trade", "™", "hellip", "…", "mdash", "—",
                "ndash", "–", "lsquo", "‘", "rsquo", "’", "ldquo", "“", "rdquo", "”",
                "laquo", "«", "raquo", "»", "bull", "•", "middot", "·", "times", "×",
                "divide", "÷", "deg", "°", "plusmn", "±", "minus", "−", "para", "¶",
                "sect", "§", "larr", "←", "uarr", "↑", "rarr", "→", "darr", "↓",
                "harr", "↔", "hearts", "♥", "diams", "♦", "clubs", "♣", "spades", "♠",
                "star", "☆", "check", "✓", "cross", "✗", "infin", "∞", "ensp", " ",
                "emsp", " ", "thinsp", " ", "zwnj", "‌", "zwj", "‍", "shy", "­",
                "frac12", "½", "frac14", "¼", "frac34", "¾", "sup2", "²", "sup3", "³",
                "euro", "€", "pound", "£", "yen", "¥", "cent", "¢", "iexcl", "¡",
                "iquest", "¿", "dagger", "†", "Dagger", "‡", "loz", "◊", "le", "≤",
                "ge", "≥", "ne", "≠", "asymp", "≈", "prime", "′", "Prime", "″",
        };
        for (int i = 0; i < pairs.length; i += 2) NAMED.put(pairs[i], pairs[i + 1]);
    }

    private Entities() {}

    /** Decodes character references. In attributes, a named reference followed by '=' or an alphanumeric is left as is. */
    static String decode(String s, boolean inAttribute) {
        int amp = s.indexOf('&');
        if (amp < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (i + 1 < s.length() && s.charAt(i + 1) == '#') {
                int j = i + 2;
                boolean hex = j < s.length() && (s.charAt(j) == 'x' || s.charAt(j) == 'X');
                if (hex) j++;
                int start = j;
                while (j < s.length() && (hex ? Character.digit(s.charAt(j), 16) >= 0 : Character.isDigit(s.charAt(j)))) j++;
                if (j > start) {
                    try {
                        int cp = Integer.parseInt(s.substring(start, j), hex ? 16 : 10);
                        if (cp == 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
                        sb.appendCodePoint(cp);
                    } catch (NumberFormatException e) {
                        sb.append('�');
                    }
                    i = j < s.length() && s.charAt(j) == ';' ? j + 1 : j;
                    continue;
                }
            } else if (semi > i + 1 && semi - i <= 12) {
                String name = s.substring(i + 1, semi);
                String v = NAMED.get(name);
                if (v != null) {
                    sb.append(v);
                    i = semi + 1;
                    continue;
                }
            } else if (!inAttribute) {
                // Legacy references without a semicolon (&amp &lt &gt &nbsp &copy).
                for (String legacy : new String[] {"amp", "lt", "gt", "nbsp", "copy", "quot"}) {
                    if (s.startsWith(legacy, i + 1)) {
                        sb.append(NAMED.get(legacy));
                        i += 1 + legacy.length();
                        legacy = null;
                        break;
                    }
                }
                if (i < s.length() && s.charAt(i) != '&') continue;
            }
            sb.append('&');
            i++;
        }
        return sb.toString();
    }
}
