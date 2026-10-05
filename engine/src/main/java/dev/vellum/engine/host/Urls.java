package dev.vellum.engine.host;

/**
 * URL resolution for document-relative references. Vellum URLs are Minecraft-style ids ({@code ns:path/file.css}),
 * plain relative paths, or anything with a scheme the host understands.
 */
public final class Urls {
    private Urls() {}

    /** {@code url} without its query and fragment: the page or resource it names. */
    public static String withoutQuery(String url) {
        return url.split("[?#]", 2)[0];
    }

    public static String resolve(String base, String relative) {
        if (relative == null || relative.isEmpty()) return base;
        if (hasNamespaceOrScheme(relative)) return relative;
        if (base == null || base.isEmpty()) return relative;
        String prefix = "";
        String path = base;
        int colon = indexOfNamespace(base);
        if (colon >= 0) {
            prefix = base.substring(0, colon + 1);
            path = base.substring(colon + 1);
        }
        String dir;
        if (relative.startsWith("/")) {
            dir = "";
            relative = relative.substring(1);
        } else {
            int slash = path.lastIndexOf('/');
            dir = slash >= 0 ? path.substring(0, slash + 1) : "";
        }
        return prefix + normalize(dir + relative);
    }

    private static boolean hasNamespaceOrScheme(String s) {
        return indexOfNamespace(s) >= 0;
    }

    /** Index of the ':' that ends a namespace or scheme, or -1 if {@code s} is a plain path. */
    private static int indexOfNamespace(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) return -1;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+')) return -1;
        }
        return colon;
    }

    private static String normalize(String path) {
        String[] parts = path.split("/", -1);
        java.util.ArrayDeque<String> out = new java.util.ArrayDeque<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.removeLast();
                continue;
            }
            if (p.isEmpty() && i < parts.length - 1) continue;
            out.addLast(p);
        }
        return String.join("/", out);
    }
}
