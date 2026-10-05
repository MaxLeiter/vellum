package dev.vellum.engine.script;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.event.Event;
import dev.vellum.engine.event.EventListener;
import dev.vellum.engine.event.KeyboardEvent;
import dev.vellum.engine.event.Modifiers;
import dev.vellum.engine.event.MouseEvent;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.NativeArray;
import dev.vellum.shadow.rhino.NativeObject;
import dev.vellum.shadow.rhino.Scriptable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles template syntax in a DOM subtree into {@link Binding}s. Directive attributes are removed as they are
 * compiled; v-if chains and v-for templates are replaced by empty text nodes that anchor where their content goes.
 * Expressions are compiled once per site and shared by every v-for item cloned from it.
 */
final class TemplateCompiler {
    /** Raw-text elements, whose text is not markup; template contents are inert ({@link Element#hasInertContent}). */
    private static final Set<String> RAW_TEXT = Set.of("script", "style");
    private static final Pattern FOR = Pattern.compile("\\s*(?:\\(([^)]*)\\)|([\\w$]+))\\s+(?:in|of)\\s+(.+)", Pattern.DOTALL);
    /** A handler that is a method path ({@code save}, {@code state.inc}) is called with the event, keeping {@code this}. */
    private static final Pattern METHOD_PATH = Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*|\\[[^\\[\\]]+])*");
    private static final Pattern FUNCTION = Pattern.compile("^(?:function\\b|(?:[A-Za-z_$][\\w$]*|\\([^()]*\\))\\s*=>)");
    private static final Map<String, Set<String>> KEYS = Map.of("enter", Set.of("Enter"), "esc", Set.of("Escape"),
            "space", Set.of(" ", "Spacebar"), "tab", Set.of("Tab"), "delete", Set.of("Delete", "Backspace"),
            "up", Set.of("ArrowUp"), "down", Set.of("ArrowDown"), "left", Set.of("ArrowLeft"), "right", Set.of("ArrowRight"));
    private static final Map<String, Integer> BUTTONS = Map.of("left", 0, "middle", 1, "right", 2);
    private static final Map<String, Predicate<Modifiers>> SYSTEM_KEYS = Map.of("ctrl", Modifiers::ctrl,
            "shift", Modifiers::shift, "alt", Modifiers::alt, "meta", Modifiers::meta);
    /** A v-model read that must not write the model (an unchecked radio). */
    private static final Object NO_CHANGE = new Object();

    private final RhinoScriptRuntime rt;
    private final List<Element> cloaked;
    private final Map<String, Expr> expressions = new HashMap<>();

    TemplateCompiler(RhinoScriptRuntime rt, List<Element> cloaked) {
        this.rt = rt;
        this.cloaked = cloaked;
    }

    /** Compiles a node, which is replaced by an anchor when it carries v-for or v-if. */
    void compileNode(Node node, Scriptable scope, Binding.Block block) {
        if (node instanceof Text text) {
            Function<Context, String> render = interpolation(text.data(), scope, "text \"" + text.data().strip() + "\"");
            if (render != null) block.add(new Binding.Value<>(render, text::setData));
        } else if (node instanceof Element el && !RAW_TEXT.contains(el.tagName()) && !el.hasInertContent()
                && !el.hasAttribute("v-pre")) {
            if (el.hasAttribute("v-for")) compileFor(el, scope, block);
            else if (el.hasAttribute("v-if")) compileIf(el, scope, block);
            else compileElement(el, scope, block);
        }
    }

    /** Compiles an element's own directives and attributes, then its children. The element itself stays. */
    void compileElement(Element el, Scriptable scope, Binding.Block block) {
        boolean ownsContent = el.hasAttribute("v-text") || el.hasAttribute("v-html");
        for (Map.Entry<String, String> attribute : List.copyOf(el.attributes().entrySet())) {
            if (compileAttribute(el, attribute.getKey(), attribute.getValue(), scope, block)) {
                el.removeAttribute(attribute.getKey());
            }
        }
        if (ownsContent) return;
        for (Node child : List.copyOf(el.childNodes())) {
            if (child.parentNode() == el) compileNode(child, scope, block); // v-if chains detach later siblings
        }
    }

    /** Compiles one attribute; returns whether it was a directive, which then leaves the element. */
    private boolean compileAttribute(Element el, String name, String value, Scriptable scope, Binding.Block block) {
        String where = where(el, name, value);
        if (name.startsWith("@") || name.startsWith("v-on:")) {
            listen(el, name.substring(name.startsWith("@") ? 1 : 5), value, scope, where);
        } else if (name.startsWith(":") || name.startsWith("v-bind:") || name.equals("v-class") || name.equals("v-style")) {
            String attribute = name.substring(name.startsWith(":") ? 1 : name.startsWith("v-bind:") ? 7 : 2);
            if (!attribute.equals("key")) block.add(bind(el, attribute, value(value, where), scope));
        } else if (name.equals("v-model") || name.startsWith("v-model.")) {
            model(el, modifiers(name), value, scope, block, where);
        } else if (name.equals("v-show")) {
            Expr shown = value(value, where);
            block.add(new Binding.Value<>(cx -> Js.bool(shown.eval(cx, scope)), on -> el.toggleAttribute("v-hidden", !on)));
        } else if (name.equals("v-text")) {
            Expr text = value(value, where);
            block.add(new Binding.Value<>(cx -> display(text.eval(cx, scope)), el::setTextContent));
        } else if (name.equals("v-html")) {
            Expr html = value(value, where);
            block.add(new Binding.Value<>(cx -> DomBindings.markup(html.eval(cx, scope)), el::setInnerHTML));
        } else if (name.equals("v-cloak")) {
            cloaked.add(el); // stays until the first digest has run
            return false;
        } else if (name.startsWith("v-else")) {
            rt.report("Error in template " + where, new IllegalStateException("there is no v-if before it"));
        } else if (!name.equals("v-key")) {
            // A plain attribute, perhaps with {{ }} holes.
            Function<Context, String> render = interpolation(value, scope, where);
            if (render != null) block.add(new Binding.Value<>(render, v -> el.setAttribute(name, v)));
            return false;
        }
        return true;
    }

    private static String where(Element el, String attribute, String value) {
        return "<" + el.tagName() + " " + attribute + "=\"" + value + "\">";
    }

    // ---- Expressions and values ----

    /** A value expression, compiled once per site and source. */
    private Expr value(String expression, String where) {
        return statements("(" + expression + "\n)", where); // the newline keeps a trailing comment harmless
    }

    private Expr statements(String code, String where) {
        return expressions.computeIfAbsent(where + '\0' + code, k -> new Expr(rt, code, where));
    }

    /** A scope holding one variable ({@code $event}, {@code $value}, loop variables...) inside {@code parent}. */
    static NativeObject childScope(Scriptable parent, String name, Object value) {
        NativeObject scope = new NativeObject();
        scope.setParentScope(parent);
        scope.setPrototype(null); // so Object.prototype members do not shadow outer names
        if (name != null) scope.put(name, scope, value);
        return scope;
    }

    /** Text with {@code {{ }}} holes as a render function, or null when it has none. */
    private Function<Context, String> interpolation(String text, Scriptable scope, String where) {
        int open = text.indexOf("{{");
        if (open < 0) return null;
        List<Object> parts = new ArrayList<>();
        int pos = 0;
        for (int close; open >= 0 && (close = text.indexOf("}}", open + 2)) >= 0; open = text.indexOf("{{", pos)) {
            if (open > pos) parts.add(text.substring(pos, open));
            parts.add(value(text.substring(open + 2, close), where));
            pos = close + 2;
        }
        if (pos < text.length()) parts.add(text.substring(pos));
        return cx -> {
            StringBuilder out = new StringBuilder();
            for (Object part : parts) out.append(part instanceof Expr e ? display(e.eval(cx, scope)) : part);
            return out.toString();
        };
    }

    /** How a value reads in the page: nothing for null and undefined, JSON for objects. */
    private String display(Object value) {
        return Js.isNullish(value) ? "" : rt.js.display(value);
    }

    /** {@code :attr}: class and style merge with the static attribute; value and checked set live form state. */
    private Binding bind(Element el, String attribute, Expr expr, Scriptable scope) {
        String fixed = el.getAttribute(attribute);
        return switch (attribute) {
            case "class" -> new Binding.Value<>(cx -> join(" ", fixed, flatten(expr.eval(cx, scope), " ",
                    (name, on) -> Js.bool(on) ? name : "")), v -> el.setAttribute("class", v));
            case "style" -> new Binding.Value<>(cx -> join("; ", fixed, flatten(expr.eval(cx, scope), "; ",
                    (name, v) -> isUnset(v) ? "" : StyleBindings.cssName(name) + ": " + Js.str(v))), v -> el.setAttribute("style", v));
            case "checked" -> new Binding.Value<>(cx -> Js.bool(expr.eval(cx, scope)), el::setChecked);
            default -> attribute.equals("value") && el.hasLiveValue()
                    ? new Binding.Value<>(cx -> display(expr.eval(cx, scope)), el::setValue)
                    : new Binding.Value<>(cx -> {
                        Object v = expr.eval(cx, scope);
                        return isUnset(v) ? null : Boolean.TRUE.equals(v) ? "" : Js.str(v);
                    }, v -> {
                        if (v == null) el.removeAttribute(attribute);
                        else el.setAttribute(attribute, v);
                    });
        };
    }

    /** null, undefined and false: no attribute, class or style. */
    private static boolean isUnset(Object value) {
        return Js.isNullish(value) || Boolean.FALSE.equals(value);
    }

    /**
     * A class or style binding value as text: strings as they are, arrays item by item, and objects entry by entry
     * through {@code entry} ({@code {active: on}} or {@code {marginTop: '4px'}}).
     */
    private static String flatten(Object value, String separator, BiFunction<String, Object, String> entry) {
        if (isUnset(value)) return "";
        if (!(value instanceof Scriptable object)) return Js.str(value);
        List<String> parts = new ArrayList<>();
        if (object instanceof NativeArray array) {
            for (Object item : Js.elements(array)) parts.add(flatten(item, separator, entry));
        } else {
            for (Object id : object.getIds()) parts.add(entry.apply(String.valueOf(id), Js.property(object, String.valueOf(id))));
        }
        return join(separator, parts.toArray(String[]::new));
    }

    /** Joins the parts that are not blank. */
    private static String join(String separator, String... parts) {
        return String.join(separator, Arrays.stream(parts).filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    // ---- Structure ----

    private void compileIf(Element first, Scriptable scope, Binding.Block block) {
        Text anchor = rt.document.createTextNode("");
        first.parentNode().insertBefore(anchor, first);
        List<IfBinding.Branch> branches = new ArrayList<>();
        Element el = first;
        String directive = "v-if";
        while (el != null) {
            String condition = el.getAttribute(directive);
            el.removeAttribute(directive);
            el.remove();
            boolean last = directive.equals("v-else");
            branches.add(new IfBinding.Branch(last ? null : value(condition, where(el, directive, condition)), el));
            if (last) break;
            // The next branch is the next element, past whitespace (which goes, as in Vue).
            List<Node> gap = new ArrayList<>();
            Node next = anchor.nextSibling();
            while (next instanceof Text t && t.data().isBlank()) {
                gap.add(next);
                next = next.nextSibling();
            }
            el = null;
            if (next instanceof Element candidate) {
                directive = candidate.hasAttribute("v-else-if") ? "v-else-if" : candidate.hasAttribute("v-else") ? "v-else" : null;
                if (directive != null) {
                    gap.forEach(Node::remove);
                    el = candidate;
                }
            }
        }
        block.add(new IfBinding(this, anchor, branches, scope));
    }

    private void compileFor(Element template, Scriptable scope, Binding.Block block) {
        String spec = template.getAttribute("v-for");
        String where = where(template, "v-for", spec);
        template.removeAttribute("v-for");
        Matcher m = FOR.matcher(spec);
        if (!m.matches()) {
            rt.report("Error in template " + where, new IllegalArgumentException("expected \"item in items\""));
            return;
        }
        List<String> aliases = m.group(1) != null
                ? Arrays.stream(m.group(1).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of(m.group(2));
        Expr key = null, filter = null;
        for (String attribute : List.of(":key", "v-bind:key", "v-key", "v-if")) {
            String value = template.getAttribute(attribute);
            if (value == null) continue;
            template.removeAttribute(attribute);
            Expr expr = value(value, where(template, attribute, value));
            if (attribute.equals("v-if")) filter = expr;
            else key = expr;
        }
        Text anchor = rt.document.createTextNode("");
        template.parentNode().insertBefore(anchor, template);
        template.remove();
        block.add(new ForBinding(rt, this, template, anchor, scope, aliases, value(m.group(3), where), key, filter));
    }

    // ---- Events ----

    private static Set<String> modifiers(String spec) {
        String[] parts = spec.split("\\.");
        return Set.copyOf(Arrays.asList(parts).subList(1, parts.length));
    }

    /** {@code @event.modifiers="handler"}: statements, or a method or function called with {@code $event}. */
    private void listen(Element el, String spec, String code, Scriptable scope, String where) {
        String type = spec.split("\\.")[0];
        Set<String> modifiers = modifiers(spec);
        String trimmed = code.trim();
        Expr handler = statements(METHOD_PATH.matcher(trimmed).matches() ? trimmed + "($event)"
                : FUNCTION.matcher(trimmed).find() ? "(" + trimmed + ")($event)" : code, where);
        el.addEventListener(type, event -> {
            if (!accepts(modifiers, event, el)) return;
            if (modifiers.contains("prevent")) event.preventDefault();
            if (modifiers.contains("stop")) event.stopPropagation();
            rt.enter("Error in template " + where, cx ->
                    handler.eval(cx, childScope(scope, "$event", rt.js.toJs(event)), rt.dom.wrap(el)));
        }, modifiers.contains("capture"), modifiers.contains("once"));
    }

    /** Whether an event passes a handler's filters: {@code .self}, system keys, key names and mouse buttons. */
    private static boolean accepts(Set<String> modifiers, Event event, Element el) {
        if (modifiers.contains("self") && event.target() != el) return false;
        Modifiers held = event instanceof KeyboardEvent k ? k.modifiers : event instanceof MouseEvent m ? m.modifiers : null;
        for (String modifier : modifiers) {
            Predicate<Modifiers> key = SYSTEM_KEYS.get(modifier);
            if (key != null && (held == null || !key.test(held))) return false;
        }
        if (event instanceof MouseEvent mouse) {
            return modifiers.stream().map(BUTTONS::get).filter(Objects::nonNull).allMatch(b -> b == mouse.button);
        }
        if (event instanceof KeyboardEvent keyboard) {
            List<Set<String>> keys = modifiers.stream().map(KEYS::get).filter(Objects::nonNull).toList();
            return keys.isEmpty() || keys.stream().anyMatch(k -> k.contains(keyboard.key));
        }
        return true;
    }

    // ---- v-model ----

    /**
     * Two-way binding: the model's value is rendered into the control on every digest, and the control's input or
     * change events assign {@code model = value}. Checkboxes bind a boolean or, when the model is an array, whether
     * it holds the checkbox's value; radios bind their value when checked; number and range inputs (and
     * {@code .number}) produce numbers; {@code .trim} trims and {@code .lazy} waits for change.
     */
    private void model(Element el, Set<String> modifiers, String expression, Scriptable scope, Binding.Block block, String where) {
        Expr read = value(expression, where);
        Expr write = statements(expression + " = $value", where);
        String type = el.tagName().equals("input") ? el.inputType() : el.tagName();
        boolean numeric = modifiers.contains("number") || type.equals("number") || type.equals("range");
        block.add(switch (type) {
            case "checkbox" -> new Binding.Value<>(cx -> {
                Object model = read.eval(cx, scope);
                return model instanceof NativeArray array
                        ? Js.elements(array).stream().anyMatch(v -> Js.str(v).equals(el.value()))
                        : Js.bool(model);
            }, el::setChecked);
            case "radio" -> new Binding.Value<>(cx -> display(read.eval(cx, scope)).equals(el.value()), el::setChecked);
            // Checked against the DOM every digest: options may render after the model (v-for) or be replaced.
            case "select" -> cx -> {
                String want = display(read.eval(cx, scope));
                Element before = el.selectedOption();
                if (before != null && before.value().equals(want)) return false;
                el.setValue(want);
                return el.selectedOption() != before;
            };
            default -> textModel(el, read, scope, modifiers, numeric, type.equals("range"));
        });
        EventListener update = event -> rt.enter("Error in template " + where, cx -> {
            String own = modifiers.contains("trim") ? el.value().strip() : el.value();
            Object typed = numeric ? number(own) : own;
            Object value = switch (type) {
                case "checkbox" -> {
                    if (!(read.eval(cx, scope) instanceof NativeArray array)) yield el.checked();
                    List<Object> items = new ArrayList<>(Js.elements(array));
                    items.removeIf(v -> Js.str(v).equals(own));
                    if (el.checked()) items.add(typed);
                    yield rt.js.array(items);
                }
                case "radio" -> el.checked() ? typed : NO_CHANGE;
                default -> typed;
            };
            return value == NO_CHANGE ? null : write.eval(cx, childScope(scope, "$value", value));
        });
        if (type.equals("checkbox") || type.equals("radio") || type.equals("select")) {
            el.addEventListener("change", update);
            el.addEventListener("input", update); // either may come first; applying twice is harmless
        } else {
            el.addEventListener(modifiers.contains("lazy") ? "change" : "input", update);
        }
    }

    /**
     * The model of a text field (an input or a textarea), checked against the field's live value on every digest as
     * Vue's vModelText does, rather than against what the binding last rendered: a model reset in the same frame as
     * typing still reaches the field. The field is written only when it differs, so its caret and selection survive.
     * While it has focus, what a modifier would rewrite under the caret is left alone: {@code .trim}'s spaces,
     * {@code .number}'s "1.", and with {@code .lazy} the typing until {@code change} commits it.
     */
    private Binding textModel(Element el, Expr read, Scriptable scope, Set<String> modifiers, boolean numeric,
                              boolean range) {
        String[] rendered = {null}; // the model at the last digest
        return cx -> {
            Object model = read.eval(cx, scope);
            String want = display(model), before = rendered[0], have = el.value();
            rendered[0] = want;
            if (have.equals(want) || numeric && model instanceof Number n && number(have) instanceof Double d
                    && d == n.doubleValue()) return false;
            if (el.isFocused() && !range && (modifiers.contains("lazy") && want.equals(before)
                    || modifiers.contains("trim") && have.strip().equals(want))) return false;
            el.setValue(want);
            return true;
        };
    }

    /** Text as a number when it parses as one (Vue's looseToNumber), else unchanged. */
    private static Object number(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return text;
        }
    }
}
