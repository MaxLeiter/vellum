package dev.vellum.engine.css;

import dev.vellum.engine.css.Decl.Keyword;
import dev.vellum.engine.css.RuleIndex.Entry;
import dev.vellum.engine.css.Selector.PseudoElement;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Prop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns declarations into a {@link ComputedStyle}: picks the winning declaration per longhand (origin and importance,
 * then the matched rules' specificity and order), resolves custom properties and {@code var()}, computes each value
 * against the element (font-size first, then color, then the rest, so em and currentColor are known), and applies
 * inheritance and the CSS-wide keywords.
 *
 * <p>One instance per style engine. It reuses scratch state between calls, so a call must finish before the next
 * starts (calls never nest: nothing calls out of the cascade while it computes).
 */
final class Cascade {
    private static final List<Longhand> LONGHANDS = Properties.all();
    private static final Longhand FONT_SIZE = Properties.of(Prop.FONT_SIZE), COLOR = Properties.of(Prop.COLOR);
    private static final List<Longhand> WITH_INITIAL = LONGHANDS.stream().filter(l -> l.initial != null).toList();
    private static final Map<ListGroup, List<Longhand>> COMPONENTS = new EnumMap<>(ListGroup.class);

    static {
        for (ListGroup g : ListGroup.values()) COMPONENTS.put(g, Properties.components(g));
    }

    private final ValueContext ctx = new ValueContext();
    private final Decl[] winners = new Decl[LONGHANDS.size()];
    /** Ids of the longhands with a winner, so computing and clearing skip the rest. */
    private final int[] declared = new int[LONGHANDS.size()];
    private int declaredCount;
    private final Map<String, Decl> customWinners = new HashMap<>();
    /** Computed values of var() declarations by substituted text, when context-free; cleared each restyle. */
    private final Map<VarKey, Object> varValues = new HashMap<>();

    /** A declaration (by identity) with one substitution result. */
    private record VarKey(Decl decl, String text) {}

    void environment(Host host, float viewportWidth, float viewportHeight, float devicePixelRatio) {
        ctx.environment(host, viewportWidth, viewportHeight, devicePixelRatio);
    }

    /** Starts a restyle pass: forgets cached var() substitutions. */
    void newPass() {
        varValues.clear();
    }

    /** Whether the last computation read element attributes ({@code attr()}), which selectors do not track. */
    boolean readAttributes() {
        return ctx.attributeRead;
    }

    /**
     * The style of {@code element} (or of one of its pseudo-elements) from its matched rules, sorted in cascade order,
     * and its inline declarations. {@code container} is the style of the box's parent for blockification (flex and
     * grid items), null for the root.
     */
    ComputedStyle compute(Element element, ComputedStyle parent, ComputedStyle container, float rem,
                          List<Entry> matched, PseudoElement pseudo, List<Decl> inline) {
        clear();
        for (Longhand l : WITH_INITIAL) win(l.initial);
        for (Entry e : matched) if (e.selector().pseudoElement == pseudo) put(e.decls(), false);
        if (inline != null) put(inline, false);
        for (Entry e : matched) if (e.selector().pseudoElement == pseudo && !e.userAgent()) put(e.decls(), true);
        if (inline != null) put(inline, true);
        for (Entry e : matched) if (e.selector().pseudoElement == pseudo && e.userAgent()) put(e.decls(), true);
        ComputedStyle s = computeWinners(element, parent, ComputedStyle.inheritFrom(parent), rem);
        boolean outOfFlow = s.position.isOutOfFlow();
        if (container != null && (container.display.isFlex() || container.display.isGrid()) && !outOfFlow) {
            s.isFlexOrGridItemHint = true;
            s.display = s.display.blockified();
        }
        if (container == null || outOfFlow) s.display = s.display.blockified();
        return s;
    }

    /**
     * Applies {@code decls} (in order, later wins) on top of a copy of {@code base}, as keyframes do. Adds the
     * properties the declarations set to {@code props}.
     */
    ComputedStyle apply(Element element, ComputedStyle parent, float rem, ComputedStyle base, List<Decl> decls,
                        Set<Prop> props) {
        clear();
        put(decls, false);
        for (Decl d : decls) {
            if (d.property != null) props.add(d.property.prop != null ? d.property.prop : d.property.group.prop);
        }
        return computeWinners(element, parent, base.copy(), rem);
    }

    private void clear() {
        for (int i = 0; i < declaredCount; i++) winners[declared[i]] = null;
        declaredCount = 0;
        customWinners.clear();
        ctx.attributeRead = false;
    }

    private void win(Decl d) {
        int id = d.property.id;
        if (winners[id] == null) declared[declaredCount++] = id;
        winners[id] = d;
    }

    private void put(List<Decl> decls, boolean important) {
        for (int i = 0, n = decls.size(); i < n; i++) {
            Decl d = decls.get(i);
            if (d.important != important) continue;
            if (d.isCustom()) customWinners.put(d.customName, d);
            else win(d);
        }
    }

    private ComputedStyle computeWinners(Element element, ComputedStyle parent, ComputedStyle s, float rem) {
        ctx.element(element, parent, rem);
        if (!customWinners.isEmpty()) s.customProperties = customProperties(s.customProperties, parent);
        applyLonghand(FONT_SIZE, s, parent);
        applyLonghand(COLOR, s, parent);
        ctx.own(s);
        for (int i = 0; i < declaredCount; i++) {
            Longhand l = LONGHANDS.get(declared[i]);
            if (l.group == null && l != FONT_SIZE && l != COLOR) applyLonghand(l, s, parent);
        }
        for (ListGroup g : ListGroup.values()) applyGroup(g, s, parent);
        // Unitless line heights inherit as factors; border and outline widths compute to 0 without a style.
        if (!Float.isNaN(s.lineHeightFactor)) s.lineHeight = s.lineHeightFactor * s.fontSize;
        if (!s.borderTopStyle.isVisible()) s.borderTopWidth = 0;
        if (!s.borderRightStyle.isVisible()) s.borderRightWidth = 0;
        if (!s.borderBottomStyle.isVisible()) s.borderBottomWidth = 0;
        if (!s.borderLeftStyle.isVisible()) s.borderLeftWidth = 0;
        if (!s.outlineStyle.isVisible()) s.outlineWidth = 0;
        return s;
    }

    private void applyLonghand(Longhand l, ComputedStyle s, ComputedStyle parent) {
        Decl d = winners[l.id];
        if (d != null) l.set(s, value(l, d, s, parent));
    }

    /** Assembles a list property (background layers, transitions, animations) when any component was declared. */
    private void applyGroup(ListGroup g, ComputedStyle s, ComputedStyle parent) {
        List<List<?>> lists = null;
        for (Longhand c : COMPONENTS.get(g)) {
            Decl d = winners[c.id];
            if (d == null) continue;
            if (lists == null) lists = new ArrayList<>(g.decompose((List<?>) g.prop.get(s)));
            lists.set(c.component, (List<?>) value(c, d, s, parent));
        }
        if (lists != null) g.prop.set(s, g.assemble(lists));
    }

    /** The computed value of a declaration for this element. */
    private Object value(Longhand l, Decl d, ComputedStyle s, ComputedStyle parent) {
        if (d.keyword != null) return keyword(l, d.keyword, s, parent);
        if (d.constant != null) return d.constant;
        if (d.hasVar) return substituted(l, d, s, parent);
        ctx.baseUrl(d.baseUrl);
        Object v = Decl.parse(l, d.value, ctx);
        return v != null ? v : keyword(l, Keyword.UNSET, s, parent);
    }

    private Object keyword(Longhand l, Keyword k, ComputedStyle s, ComputedStyle parent) {
        boolean inherit = k == Keyword.INHERIT || (k == Keyword.UNSET && l.inherited());
        if (inherit && parent != null) return l.get(parent);
        if (l.initial != null) return value(l, l.initial, s, parent);
        return l.get(ComputedStyle.INITIAL);
    }

    /**
     * A var() declaration's value: substituted, re-expanded when it belongs to a shorthand, then parsed. Invalid
     * results are invalid at computed-value time, which means unset. Context-free results are cached by text, so
     * elements sharing variables share the work.
     */
    private Object substituted(Longhand l, Decl d, ComputedStyle s, ComputedStyle parent) {
        String text = Vars.substitute(d.value, s.customProperties::get);
        if (text == null) return keyword(l, Keyword.UNSET, s, parent);
        VarKey key = new VarKey(d, text);
        Object cached = varValues.get(key);
        if (cached != null) return cached;
        List<ComponentValue> values = CssParser.parseComponentValues(text);
        if (d.pending != null) {
            Map<Longhand, List<ComponentValue>> parts = d.pending.expand(values);
            values = parts == null ? null : parts.get(l);
        }
        Keyword keyword = values == null ? Keyword.UNSET : Decl.keyword(values);
        if (keyword != null) return keyword(l, keyword, s, parent);
        ctx.baseUrl(d.baseUrl);
        ctx.dependent = false;
        Object v = Decl.parse(l, values, ctx);
        if (v == null) return keyword(l, Keyword.UNSET, s, parent);
        if (!ctx.dependent) varValues.put(key, v);
        return v;
    }

    /** The element's custom properties: inherited ones plus its declarations, with var() resolved. */
    private Map<String, String> customProperties(Map<String, String> inherited, ComputedStyle parent) {
        Map<String, String> out = new HashMap<>(inherited);
        Set<String> resolving = new HashSet<>(), done = new HashSet<>();
        for (String name : customWinners.keySet()) resolveCustom(name, out, parent, resolving, done);
        return Collections.unmodifiableMap(out);
    }

    /**
     * Resolves one custom property declared on this element. A reference cycle makes the property invalid
     * (removed), so {@code var()} falls back.
     */
    private String resolveCustom(String name, Map<String, String> out, ComputedStyle parent, Set<String> resolving,
                                 Set<String> done) {
        Decl d = customWinners.get(name);
        if (d == null || done.contains(name)) return out.get(name);
        if (!resolving.add(name)) return null;
        String value;
        if (d.keyword == Keyword.INITIAL) value = null;
        else if (d.keyword != null) value = parent == null ? null : parent.var(name);
        else if (d.hasVar) value = Vars.substitute(d.value, n -> resolveCustom(n, out, parent, resolving, done));
        else value = d.customText;
        resolving.remove(name);
        done.add(name);
        if (value == null) out.remove(name);
        else out.put(name, value);
        return value;
    }
}
