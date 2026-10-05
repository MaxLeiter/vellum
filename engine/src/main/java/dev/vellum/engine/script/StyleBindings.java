package dev.vellum.engine.script;

import dev.vellum.engine.css.InlineStyle;
import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Style-related objects: {@code element.style} and {@code getComputedStyle()} (CSSStyleDeclaration, with camelCase
 * property access), {@code classList} (DOMTokenList) and {@code dataset} (DOMStringMap).
 */
final class StyleBindings {
    /** Lower-case vendor prefixes that start a camelCase name ({@code webkitTransform} is {@code -webkit-transform}). */
    private static final Pattern VENDOR = Pattern.compile("^(webkit|moz|ms|mc)[A-Z]");

    private record Computed(Element element, String pseudo) {}

    private final RhinoScriptRuntime rt;
    private final HostClass<Element> declaration;
    private final HostClass<Computed> computed;
    private final HostClass<Element> tokenList;
    private final HostClass<Element> stringMap;

    StyleBindings(RhinoScriptRuntime rt) {
        this.rt = rt;
        declaration = new HostClass<>(rt, "CSSStyleDeclaration", Element.class, null, null).expose("CSSStyleDeclaration");
        declaration.members()
                .method("getPropertyValue", (e, a) -> InlineStyle.getPropertyValue(e, cssName(a.str(0))))
                .method("getPropertyPriority", (e, a) -> InlineStyle.getPropertyPriority(e, cssName(a.str(0))))
                .action("setProperty", (e, a) -> InlineStyle.setProperty(e, cssName(a.str(0)), cssValue(a.get(1)), a.str(2, "")))
                .method("removeProperty", (e, a) -> InlineStyle.removeProperty(e, cssName(a.str(0))))
                .prop("cssText", InlineStyle::cssText, (e, v) -> InlineStyle.setCssText(e, cssValue(v)))
                .get("length", InlineStyle::length)
                .method("item", (e, a) -> InlineStyle.item(e, (int) a.num(0, 0)));

        // Inherits for instanceof; the writers then reject a computed style as their target.
        computed = new HostClass<>(rt, "CSSStyleDeclaration", Computed.class, declaration, null);
        computed.members()
                .method("getPropertyValue", (c, a) -> computedValue(c, cssName(a.str(0))))
                .method("getPropertyPriority", (c, a) -> "")
                .get("cssText", c -> "")
                .get("length", c -> 0);

        tokenList = new HostClass<>(rt, "DOMTokenList", Element.class, null, null).expose("DOMTokenList");
        tokenList.members()
                .get("length", e -> e.classes().size())
                .prop("value", e -> classAttribute(e), (e, v) -> e.setAttribute("class", Js.str(v)))
                .method("toString", (e, a) -> classAttribute(e))
                .method("item", (e, a) -> {
                    List<String> classes = List.copyOf(e.classes());
                    int i = (int) a.num(0, -1);
                    return i >= 0 && i < classes.size() ? classes.get(i) : null;
                })
                .method("contains", (e, a) -> e.hasClass(a.str(0)))
                .action("add", (e, a) -> {
                    for (Object token : a.values()) e.addClass(Js.str(token));
                })
                .action("remove", (e, a) -> {
                    for (Object token : a.values()) e.removeClass(Js.str(token));
                })
                .method("toggle", (e, a) -> {
                    if (!a.has(1)) return e.toggleClass(a.str(0));
                    e.toggleClass(a.str(0), a.bool(1));
                    return a.bool(1);
                })
                .method("replace", (e, a) -> e.replaceClass(a.str(0), a.str(1)))
                .action("forEach", (e, a) -> {
                    List<String> classes = List.copyOf(e.classes());
                    for (int i = 0; i < classes.size(); i++) {
                        rt.js.invoke(a.fn(0), rt.global, classes.get(i), i, companion(e));
                    }
                });

        stringMap = new HostClass<>(rt, "DOMStringMap", Element.class, null, null);
    }

    /** {@code element.style}: the inline style, with any property name readable and writable in camelCase. */
    Scriptable style(Element e) {
        return declaration.wrap(e, new HostObject.Named() {
            @Override public boolean has(String name) { return true; }
            @Override public Object get(String name) { return InlineStyle.getPropertyValue(e, cssName(name)); }
            @Override public void put(String name, Object value) { InlineStyle.setProperty(e, cssName(name), cssValue(value), ""); }
        });
    }

    /** {@code getComputedStyle(element, pseudo)}: live and read-only. */
    Scriptable computedStyle(Element e, String pseudo) {
        Computed target = new Computed(e, pseudo == null ? "" : pseudo);
        return computed.wrap(target, new HostObject.Named() {
            @Override public boolean has(String name) { return true; }
            @Override public Object get(String name) { return computedValue(target, cssName(name)); }
            @Override public void put(String name, Object value) {}
        });
    }

    Scriptable classList(Element e) {
        return tokenList.wrap(e);
    }

    /** {@code dataset}: {@code data-foo-bar} attributes as {@code fooBar} properties. */
    Scriptable dataset(Element e) {
        return stringMap.wrap(e, new HostObject.Named() {
            @Override public boolean has(String key) { return e.hasAttribute(dataAttribute(key)); }
            @Override public Object get(String key) { return e.getAttribute(dataAttribute(key)); }
            @Override public void put(String key, Object value) { e.setAttribute(dataAttribute(key), Js.str(value)); }
            @Override public void delete(String key) { e.removeAttribute(dataAttribute(key)); }
            @Override public Object[] ids() {
                return e.attributes().keySet().stream().filter(n -> n.startsWith("data-"))
                        .map(n -> camelCase(n.substring(5))).toArray();
            }
        });
    }

    private Object companion(Element e) {
        return Js.property(rt.dom.wrap(e), "classList");
    }

    private String computedValue(Computed c, String property) {
        rt.document.flushStyle();
        ComputedStyle style = switch (c.pseudo()) {
            case "::before", ":before" -> c.element().beforeStyle;
            case "::after", ":after" -> c.element().afterStyle;
            default -> c.element().style;
        };
        return style == null ? "" : StyleEngine.computedValue(style, property);
    }

    private static String classAttribute(Element e) {
        String value = e.getAttribute("class");
        return value == null ? "" : value;
    }

    // ---- Names ----

    /**
     * A script property name as a CSS property: {@code backgroundColor} → {@code background-color},
     * {@code webkitTransform} / {@code WebkitTransform} → {@code -webkit-transform}, {@code cssFloat} →
     * {@code float}. Names that already contain a dash (including {@code --custom}) are kept as they are.
     */
    static String cssName(String name) {
        if (name.indexOf('-') >= 0) return name;
        if (name.equals("cssFloat")) return "float";
        return (VENDOR.matcher(name).lookingAt() ? "-" : "") + kebabCase(name);
    }

    /** {@code fooBar} → {@code foo-bar}. */
    static String kebabCase(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) out.append('-').append(Character.toLowerCase(c));
            else out.append(c);
        }
        return out.toString();
    }

    /** {@code foo-bar} → {@code fooBar}. */
    static String camelCase(String dashed) {
        StringBuilder out = new StringBuilder(dashed.length());
        boolean upper = false;
        for (char c : dashed.toCharArray()) {
            if (c == '-') upper = true;
            else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static String dataAttribute(String key) {
        return "data-" + kebabCase(key).toLowerCase(Locale.ROOT);
    }

    /** A value assigned to a style property; null and undefined clear it. */
    private static String cssValue(Object value) {
        return Js.isNullish(value) ? "" : Js.str(value);
    }
}
