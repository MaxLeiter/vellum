package dev.vellum.engine.css;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.host.Host;
import dev.vellum.engine.host.Urls;
import dev.vellum.engine.style.Colors;
import dev.vellum.engine.style.ComputedStyle;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What relative values need to compute: font sizes for {@code em}/{@code rem}, the viewport, the GUI scale for
 * {@code dp}, {@code currentColor}, the parent's font weight, the element (for {@code attr()}) and the base URL.
 *
 * <p>Reading any element-specific input sets {@link #dependent}. Stylesheets parse every declaration once with a
 * probe context: if the value did not read anything element-specific it is stored as a constant and shared by every
 * element; otherwise it is recomputed per element.
 */
final class ValueContext {
    private float em = ComputedStyle.DEFAULT_FONT_SIZE, rem = ComputedStyle.DEFAULT_FONT_SIZE;
    private float viewportWidth = 320, viewportHeight = 240, devicePixelRatio = 1;
    private int currentColor = Colors.BLACK, parentFontWeight = 400;
    private Element element;
    private String baseUrl = "";
    private Host host;
    boolean dependent;
    /** Set when a value read an attribute ({@code attr()}): such styles must be recomputed on every restyle. */
    boolean attributeRead;
    /** Every attribute {@code attr()} has read, so a change to one restyles. */
    final Set<String> attributesRead = new HashSet<>();

    float em() { dependent = true; return em; }
    float rem() { dependent = true; return rem; }
    float viewportWidth() { dependent = true; return viewportWidth; }
    float viewportHeight() { dependent = true; return viewportHeight; }
    float devicePixelRatio() { dependent = true; return devicePixelRatio; }
    int currentColor() { dependent = true; return currentColor; }
    int parentFontWeight() { dependent = true; return parentFontWeight; }

    /** The element's attribute value, or "" (for {@code attr()}). */
    String attr(String name) {
        dependent = true;
        attributeRead = true;
        attributesRead.add(name.toLowerCase(Locale.ROOT));
        String v = element == null ? null : element.getAttribute(name);
        return v == null ? "" : v;
    }

    /** Resolves a {@code url()} against the declaring stylesheet. Not element-specific. */
    String resolveUrl(String url) {
        return resolve(host, baseUrl, url);
    }

    /** Resolves through the host when there is one (hosts may customise resolution), else {@link Urls}. */
    static String resolve(Host host, String base, String url) {
        return host != null ? host.resolveUrl(base, url) : Urls.resolve(base, url);
    }

    ValueContext environment(Host host, float viewportWidth, float viewportHeight, float devicePixelRatio) {
        this.host = host;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.devicePixelRatio = devicePixelRatio;
        return this;
    }

    /** Sets the element being computed and its parent's style (which font-size em and color's currentColor use). */
    void element(Element element, ComputedStyle parent, float rem) {
        this.element = element;
        this.rem = rem;
        this.em = parent == null ? ComputedStyle.DEFAULT_FONT_SIZE : parent.fontSize;
        this.currentColor = parent == null ? ComputedStyle.INITIAL.color : parent.color;
        this.parentFontWeight = parent == null ? 400 : parent.fontWeight;
    }

    /** After font-size and color are known, other properties resolve em and currentColor against the element. */
    void own(ComputedStyle style) {
        this.em = style.fontSize;
        this.currentColor = style.color;
    }

    /** Sets {@code currentColor}, for values read outside a cascade. */
    ValueContext currentColor(int color) {
        this.currentColor = color;
        return this;
    }

    void baseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }
}
