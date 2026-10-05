package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.event.TransitionEvent;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.testing.TestHost;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the animation engine the way the pipeline does, without the (stubbed) style and layout engines: tests
 * hand-build base styles and call {@link #restyle} and {@link #tick}. Keyframes come from {@link #keyframes}, and
 * every timing event on {@link #el} is recorded in {@link #events} as {@code type:name@seconds}.
 */
final class AnimFixture {
    static final List<String> EVENT_TYPES = List.of("transitionrun", "transitionstart", "transitionend",
            "transitioncancel", "animationstart", "animationiteration", "animationend", "animationcancel");

    final Document doc;
    final AnimationEngine engine;
    final Element el;
    final Map<String, List<ResolvedKeyframe>> keyframes = new HashMap<>();
    /** Keyframe resolutions, as {@code name} per call. */
    final List<String> resolved = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    AnimFixture() {
        this(new TestHost());
    }

    AnimFixture(TestHost host) {
        doc = Document.create(host, "test:anim.html");
        engine = doc.animations();
        engine.setKeyframeResolver((element, name, base) -> {
            resolved.add(name);
            return keyframes.getOrDefault(name, List.of());
        });
        el = doc.body().appendChild(doc.createElement("div"));
        for (String type : EVENT_TYPES) {
            el.addEventListener(type, e -> {
                TransitionEvent t = (TransitionEvent) e;
                events.add(t.type + ":" + t.name + "@" + t.elapsedSeconds);
            });
        }
    }

    /** A block-level style with the given (prop, value) pairs set. */
    static ComputedStyle style(Object... pairs) {
        ComputedStyle s = new ComputedStyle();
        s.display = Display.BLOCK;
        for (int i = 0; i < pairs.length; i += 2) ((Prop) pairs[i]).set(s, pairs[i + 1]);
        return s;
    }

    /** A keyframe setting the given (prop, value) pairs. */
    static ResolvedKeyframe keyframe(float offset, TimingFunction timing, Object... pairs) {
        ComputedStyle s = style(pairs);
        Set<Prop> props = new HashSet<>();
        for (int i = 0; i < pairs.length; i += 2) props.add((Prop) pairs[i]);
        return new ResolvedKeyframe(offset, timing, s, props);
    }

    /** Gives {@link #el} a new base style at time {@code t}, as the style engine does during a frame. */
    void restyle(double t, ComputedStyle base) {
        restyle(el, t, base);
    }

    void restyle(Element element, double t, ComputedStyle base) {
        doc.scheduler().run(t);
        ComputedStyle old = element.baseStyle;
        element.baseStyle = base;
        engine.styleChanged(element, old, base);
    }

    void tick(double t) {
        doc.scheduler().run(t);
        engine.tick(t);
    }

    float opacity() {
        return el.style.opacity;
    }

    /** Clears the document's layout flag; the layout engine is not available to do it here. */
    void clearLayoutDirty() {
        try {
            Field f = Document.class.getDeclaredField("layoutDirty");
            f.setAccessible(true);
            f.setBoolean(doc, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    List<String> takeEvents() {
        List<String> out = List.copyOf(events);
        events.clear();
        return out;
    }
}
