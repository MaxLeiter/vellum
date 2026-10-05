package dev.vellum.engine.css;

import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.BackgroundLayer;
import dev.vellum.engine.style.Image;
import dev.vellum.engine.style.Length;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;
import dev.vellum.engine.style.TransitionSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Comma-separated list properties whose CSS longhands cascade separately but which {@link
 * dev.vellum.engine.style.ComputedStyle} stores as one list of records: {@code background-*} →
 * {@link BackgroundLayer}s, {@code transition-*} → {@link TransitionSpec}s, {@code animation-*} →
 * {@link AnimationSpec}s. The first component (image, property, name) decides the number of entries; the other
 * component lists repeat to match, as CSS specifies.
 */
enum ListGroup {
    BACKGROUND(Prop.BACKGROUND_LAYERS, List.of(Longhand.None.VALUE, Length.ZERO, Length.ZERO,
            new BackgroundSize(null, Length.AUTO, Length.AUTO),
            new BackgroundRepeat(BackgroundLayer.Repeat.REPEAT, BackgroundLayer.Repeat.REPEAT),
            BackgroundLayer.Box.BORDER_BOX)),
    TRANSITION(Prop.TRANSITION, List.of("all", 0f, TimingFunction.EASE, 0f)),
    ANIMATION(Prop.ANIMATION, List.of("none", 0f, TimingFunction.EASE, 0f, 1f, AnimationSpec.Direction.NORMAL,
            AnimationSpec.FillMode.NONE, false));

    /** One {@code background-size} entry: {@code cover}/{@code contain}, or a width and height. */
    record BackgroundSize(String keyword, Length width, Length height) {}

    record BackgroundRepeat(BackgroundLayer.Repeat x, BackgroundLayer.Repeat y) {}

    /** The ComputedStyle property holding the assembled list. */
    final Prop prop;
    /** Per component, the value an entry gets when that component's list is empty. */
    private final List<Object> defaults;

    ListGroup(Prop prop, List<Object> defaults) {
        this.prop = prop;
        this.defaults = defaults;
    }

    int size() {
        return defaults.size();
    }

    /** The value an entry gets for {@code component} when that component's list is empty. */
    Object defaultValue(int component) {
        return defaults.get(component);
    }

    /** Builds the computed list from one list per component. */
    List<?> assemble(List<List<?>> components) {
        int count = components.get(0).size();
        List<Object> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object[] v = new Object[size()];
            for (int c = 0; c < v.length; c++) {
                List<?> list = components.get(c);
                v[c] = list.isEmpty() ? defaults.get(c) : list.get(i % list.size());
            }
            Object entry = entry(v);
            if (entry != null) out.add(entry);
        }
        return List.copyOf(out);
    }

    private Object entry(Object[] v) {
        return switch (this) {
            case BACKGROUND -> { // a "none" layer stays: its clip is still the colour's when it is the bottom one
                BackgroundSize size = (BackgroundSize) v[3];
                BackgroundRepeat repeat = (BackgroundRepeat) v[4];
                yield new BackgroundLayer(v[0] instanceof Image image ? image : null, size.keyword(), size.width(),
                        size.height(), (Length) v[1],
                        (Length) v[2], repeat.x(), repeat.y(), (BackgroundLayer.Box) v[5]);
            }
            // Entries that never run (a combined duration that is not positive) stay: the last entry naming a
            // property wins, so "all 1s, opacity 0s" must keep opacity from transitioning (CSS Transitions §2).
            case TRANSITION -> v[0].equals("none") ? null
                    : new TransitionSpec((String) v[0], (Float) v[1], (Float) v[3], (TimingFunction) v[2]);
            case ANIMATION -> v[0].equals("none") ? null
                    : new AnimationSpec((String) v[0], (Float) v[1], (Float) v[3], (TimingFunction) v[2], (Float) v[4],
                    (AnimationSpec.Direction) v[5], (AnimationSpec.FillMode) v[6], (Boolean) v[7]);
        };
    }

    /**
     * The component lists of an assembled list: for {@code inherit}, and as the base for keyframes. An empty list
     * decomposes into the initial values (one default entry: {@code transition-property: all} and so on).
     */
    List<List<?>> decompose(List<?> entries) {
        List<List<?>> out = new ArrayList<>(size());
        for (int c = 0; c < size(); c++) {
            if (entries.isEmpty()) {
                out.add(List.of(defaults.get(c)));
                continue;
            }
            List<Object> component = new ArrayList<>(entries.size());
            for (Object e : entries) component.add(component(e, c));
            out.add(component);
        }
        return out;
    }

    private Object component(Object entry, int c) {
        return switch (this) {
            case BACKGROUND -> {
                BackgroundLayer l = (BackgroundLayer) entry;
                yield switch (c) {
                    case 0 -> l.image() != null ? l.image() : Longhand.None.VALUE;
                    case 1 -> l.positionX();
                    case 2 -> l.positionY();
                    case 3 -> new BackgroundSize(l.sizeKeyword(), l.width(), l.height());
                    case 4 -> new BackgroundRepeat(l.repeatX(), l.repeatY());
                    default -> l.clip();
                };
            }
            case TRANSITION -> {
                TransitionSpec t = (TransitionSpec) entry;
                yield switch (c) {
                    case 0 -> t.property();
                    case 1 -> t.durationMs();
                    case 2 -> t.timing();
                    default -> t.delayMs();
                };
            }
            case ANIMATION -> {
                AnimationSpec a = (AnimationSpec) entry;
                yield switch (c) {
                    case 0 -> a.name();
                    case 1 -> a.durationMs();
                    case 2 -> a.timing();
                    case 3 -> a.delayMs();
                    case 4 -> a.iterations();
                    case 5 -> a.direction();
                    case 6 -> a.fillMode();
                    default -> a.paused();
                };
            }
        };
    }
}
