package dev.vellum.engine.css;

import dev.vellum.engine.css.RuleIndex.Entry;
import dev.vellum.engine.css.RuleIndex.Source;
import dev.vellum.engine.css.Selector.MatchContext;
import dev.vellum.engine.css.Selector.PseudoElement;
import dev.vellum.engine.css.Stylesheet.Keyframe;
import dev.vellum.engine.css.Stylesheet.KeyframesRule;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.Node;
import dev.vellum.engine.dom.Text;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The cascade. Finds the document's stylesheets (the user-agent sheet, {@code <style>} elements and
 * {@code <link rel="stylesheet">}), matches rules, and computes {@code baseStyle} (plus ::before/::after and
 * ::placeholder styles) for every element.
 *
 * <p>Restyles are full-document (DESIGN D-008) but incremental in effect: an element whose matched rules, parent
 * style and container style are unchanged keeps its previous style objects, so identity comparison is enough
 * downstream, and a changed style is compared property by property to decide whether layout must run again. When
 * only hover, active or focus state changed since the last restyle ({@link Document#domVersion} is unchanged),
 * elements whose matching did not read that state keep their matched rules without matching again.
 */
public final class StyleEngine {
    private static final Stylesheet USER_AGENT = loadUserAgentSheet();

    private final Document document;
    private final Host host;
    private final Cascade cascade = new Cascade();
    private final List<Entry> matched = new ArrayList<>();
    private RuleIndex index;
    private List<Source> sources;
    /** The {@link Document#domVersion} the last restyle ran at (and {@link #sources} were collected at). */
    private int styledVersion;
    /** This pass only follows hover, active and focus changes: the DOM and the rules are as last time. */
    private boolean interactionOnly;
    private MediaQuery.Environment environment;
    /** Bumped whenever something every element depends on changes: sheets, media, viewport, root font size. */
    private int generation;
    private float rootFontSize = ComputedStyle.DEFAULT_FONT_SIZE;
    /** Parsed {@code <style>} contents by text, kept while some element still uses them. */
    private Map<String, Stylesheet> styleSheets = new HashMap<>();
    /** Linked and imported sheets by URL, for the life of the document. */
    private final Map<String, Stylesheet> loadedSheets = new HashMap<>();
    private final Map<String, MediaQuery> mediaAttributes = new HashMap<>();

    public StyleEngine(Document document) {
        this.document = document;
        this.host = document.host();
    }

    /**
     * Recomputes styles for the whole document. For each element: sets {@code baseStyle}, calls
     * {@code document.animations().styleChanged(element, oldBase, newBase)} which sets {@code element.style},
     * and invalidates layout when a layout-affecting property changed.
     */
    public void restyle() {
        Element root = document.documentElement();
        if (root == null) return;
        MediaQuery.Environment env = new MediaQuery.Environment(document.viewportWidth(), document.viewportHeight(),
                document.devicePixelRatio(), host.prefersReducedMotion());
        boolean domChanged = sources == null || document.domVersion() != styledVersion;
        styledVersion = document.domVersion();
        List<Source> found = sources;
        if (domChanged) {
            found = new ArrayList<>();
            Map<String, Stylesheet> usedStyles = new HashMap<>();
            collectSheets(document, found, usedStyles);
            styleSheets = usedStyles;
        }
        interactionOnly = !domChanged;
        if (index == null || !env.equals(environment) || !found.equals(sources)) {
            index = RuleIndex.build(USER_AGENT, found, env, this::loadSheet);
            environment = env;
            generation++;
            interactionOnly = false;
            cascade.environment(host, env.width(), env.height(), env.guiScale());
        }
        sources = found;
        cascade.newPass();
        restyle(root, null, null, new MatchContext(root));
    }

    private void restyle(Element el, ComputedStyle parent, ComputedStyle container, MatchContext mc) {
        ElementState state = ElementState.of(el);
        matched.clear();
        boolean rematch = !interactionOnly || !state.matchesSurviveInteraction();
        boolean sameMatches = true;
        if (rematch) {
            mc.interactionRead = false;
            index.match(el, mc, matched);
            sameMatches = state.updateMatches(matched, mc.interactionRead);
        }
        ComputedStyle old = el.baseStyle;
        ComputedStyle base = old;
        if (old == null || !sameMatches || !state.sameInputs(parent, container, generation)) {
            if (!rematch) state.matchesInto(matched);
            float rem = parent == null ? ComputedStyle.DEFAULT_FONT_SIZE : rootFontSize;
            base = reuseIfEqual(old, cascade.compute(el, parent, container, rem, matched, PseudoElement.NONE,
                    state.inline));
            boolean inheritsExplicitly = cascade.inheritedExplicitly();
            boolean readsAttributes = cascade.readAttributes();
            el.beforeStyle = pseudo(el, base, rem, PseudoElement.BEFORE, el.beforeStyle);
            readsAttributes |= cascade.readAttributes();
            el.afterStyle = pseudo(el, base, rem, PseudoElement.AFTER, el.afterStyle);
            readsAttributes |= cascade.readAttributes();
            el.placeholderStyle = el.isTextControl()
                    ? pseudo(el, base, rem, PseudoElement.PLACEHOLDER, el.placeholderStyle) : null;
            state.remember(parent, container, generation, readsAttributes, inheritsExplicitly);
        }
        if (parent == null && base.fontSize != rootFontSize) {
            rootFontSize = base.fontSize; // rem changed: nothing below may be reused
            generation++;
        }
        el.baseStyle = base;
        document.animations().styleChanged(el, old, base);
        ComputedStyle childContainer = base.display == Display.CONTENTS ? container : base;
        for (int i = 0, n = el.childCount(); i < n; i++) {
            if (el.childAt(i) instanceof Element child) restyle(child, base, childContainer, mc);
        }
    }

    /** A pseudo-element's style, or null when no rule targets it (or, for ::before/::after, it has no content). */
    private ComputedStyle pseudo(Element el, ComputedStyle style, float rem, PseudoElement which, ComputedStyle old) {
        ComputedStyle s = null;
        for (Entry e : matched) {
            if (e.selector().pseudoElement != which) continue;
            s = cascade.compute(el, style, style, rem, matched, which, null);
            if (which != PseudoElement.PLACEHOLDER && s.content == null) s = null;
            break;
        }
        if (s != null) return reuseIfEqual(old, s);
        if (old != null) document.invalidateLayout();
        return null;
    }

    /**
     * {@code old} when {@code fresh} computes the same (so unchanged styles keep their identity), else {@code fresh};
     * invalidates layout when the element is new or a layout-affecting property changed.
     */
    private ComputedStyle reuseIfEqual(ComputedStyle old, ComputedStyle fresh) {
        if (old == null) {
            document.invalidateLayout();
            return fresh;
        }
        if (!old.sameLayout(fresh)) {
            document.invalidateLayout();
            return fresh;
        }
        return old.sameAs(fresh) ? old : fresh;
    }

    // ---- Stylesheets ----

    /** Finds {@code <style>} and {@code <link rel=stylesheet>} elements in document order (not inside templates). */
    private void collectSheets(Node node, List<Source> out, Map<String, Stylesheet> usedStyles) {
        for (int i = 0, n = node.childCount(); i < n; i++) {
            if (!(node.childAt(i) instanceof Element e)) continue;
            switch (e.tagName()) {
                case "style" -> {
                    String text = styleText(e);
                    Stylesheet sheet = usedStyles.computeIfAbsent(text, t -> {
                        Stylesheet cached = styleSheets.get(t);
                        return cached != null ? cached : Stylesheet.parse(t, document.url(), host, log(document.url()));
                    });
                    out.add(new Source(sheet, media(e)));
                }
                case "link" -> {
                    String rel = e.getAttribute("rel"), href = e.getAttribute("href");
                    if (rel != null && href != null && Selector.hasToken(rel, "stylesheet", true)) {
                        out.add(new Source(loadSheet(document.resolveUrl(href)), media(e)));
                    }
                }
                default -> {
                    if (!e.hasInertContent()) collectSheets(e, out, usedStyles);
                }
            }
        }
    }

    private static String styleText(Element style) {
        return style.childCount() == 1 && style.childAt(0) instanceof Text t ? t.data() : style.textContent();
    }

    private MediaQuery media(Element e) {
        String media = e.getAttribute("media");
        return media == null || media.isBlank() ? MediaQuery.ALL : mediaAttributes.computeIfAbsent(media, MediaQuery::parse);
    }

    /** A linked or imported sheet by resolved URL; a missing one is reported once and treated as empty. */
    private Stylesheet loadSheet(String url) {
        return loadedSheets.computeIfAbsent(url, u -> {
            String css = host.loadText(u);
            if (css == null) host.log(Host.LogLevel.WARN, "Stylesheet not found: " + u);
            return Stylesheet.parse(css == null ? "" : css, u, host, log(u));
        });
    }

    private Consumer<String> log(String url) {
        return message -> host.log(Host.LogLevel.DEBUG, url + ":" + message);
    }

    private static Stylesheet loadUserAgentSheet() {
        try (InputStream in = StyleEngine.class.getResourceAsStream("/vellum/ua.css")) {
            if (in == null) throw new IllegalStateException("Missing /vellum/ua.css");
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Stylesheet.parse(css, "vellum:ua.css", null, message -> System.err.println("[vellum] ua.css:" + message));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Keyframes and script-provided declarations ----

    /**
     * The keyframes of the {@code @keyframes} rule named {@code name}, computed for {@code element} on top of
     * {@code base}, sorted by offset. Missing 0% / 100% keyframes are NOT synthesised (the animation engine uses the
     * base value there). Returns an empty list when no such rule exists.
     */
    public List<ResolvedKeyframe> resolveKeyframes(Element element, String name, ComputedStyle base) {
        KeyframesRule rule = index == null ? null : index.keyframes.get(name);
        if (rule == null) return List.of();
        // Blocks with the same offset merge in order (later declarations win).
        TreeMap<Float, List<Decl>> byOffset = new TreeMap<>();
        for (Keyframe k : rule.keyframes()) {
            for (Float offset : k.offsets()) byOffset.computeIfAbsent(offset, o -> new ArrayList<>()).addAll(k.decls());
        }
        List<ResolvedKeyframe> out = new ArrayList<>(byOffset.size());
        byOffset.forEach((offset, decls) -> out.add(keyframe(element, base, offset, decls)));
        return out;
    }

    /**
     * Computes the style that results from applying CSS declarations ({@code "opacity: 0; transform: scale(2)"})
     * to {@code element} on top of {@code base}: used by {@code element.animate()} keyframes from scripts.
     * Returns the new style and the set of properties the declarations set, as a keyframe at offset 0.
     */
    public ResolvedKeyframe computeDeclarations(Element element, String declarations, ComputedStyle base) {
        return keyframe(element, base, 0, Stylesheet.declarations(declarations, document.url(), host, log(document.url())));
    }

    /**
     * A keyframe: {@code animation-timing-function} becomes the keyframe's easing; other animation and transition
     * properties are ignored, as in CSS.
     */
    private ResolvedKeyframe keyframe(Element element, ComputedStyle base, float offset, List<Decl> decls) {
        TimingFunction timing = null;
        List<Decl> applied = new ArrayList<>(decls.size());
        Longhand easing = Properties.longhand("animation-timing-function");
        for (Decl d : decls) {
            if (d.property == easing && d.constant instanceof List<?> list && !list.isEmpty()) {
                timing = (TimingFunction) list.get(0);
            } else if (d.isCustom() || (d.property.group != ListGroup.ANIMATION
                    && d.property.group != ListGroup.TRANSITION)) {
                applied.add(d);
            }
        }
        Element parentElement = element.parentElement();
        ComputedStyle parent = parentElement == null ? null : parentElement.baseStyle;
        float rem = parentElement == null ? ComputedStyle.DEFAULT_FONT_SIZE : rootFontSize;
        Set<Prop> props = EnumSet.noneOf(Prop.class);
        ComputedStyle style = cascade.apply(element, parent, rem, base, applied, props);
        return new ResolvedKeyframe(offset, timing, style, Collections.unmodifiableSet(props));
    }

    /**
     * The computed value of {@code property} (CSS name, longhand or common shorthand) serialised as CSS text, for
     * {@code getComputedStyle}. Returns "" for unknown properties.
     */
    public static String computedValue(ComputedStyle style, String property) {
        return ComputedValues.serialize(style, property);
    }
}
