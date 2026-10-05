package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TimingFunction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What an animation changes: per property, keyframes of (offset, value, easing to the next keyframe). Sampled at an
 * iteration progress and applied onto a style. Transitions are one property with two keyframes; CSS and scripted
 * animations are built from {@link ResolvedKeyframe}s.
 */
final class KeyframeEffect {
    /** Stands for the underlying value (the style below this animation) in a missing 0% or 100% keyframe. */
    private static final Object UNDERLYING = new Object();

    private final Track[] tracks;
    private final EnumSet<Prop> props = EnumSet.noneOf(Prop.class);

    private KeyframeEffect(Track[] tracks) {
        this.tracks = tracks;
        for (Track track : tracks) props.add(track.prop);
    }

    /**
     * An effect from keyframes sorted by offset. Each property animates through the keyframes that specify it,
     * starting and ending at the underlying value when no keyframe specifies it at 0% or 100%. Keyframes without
     * their own timing function, and those missing endpoints, ease with {@code defaultEasing}.
     */
    static KeyframeEffect of(List<ResolvedKeyframe> keyframes, TimingFunction defaultEasing) {
        EnumSet<Prop> props = EnumSet.noneOf(Prop.class);
        for (ResolvedKeyframe k : keyframes) props.addAll(k.props());
        List<Track> tracks = new ArrayList<>(props.size());
        for (Prop prop : props) {
            if (prop.interpolation != Prop.Interp.NONE) tracks.add(Track.of(prop, keyframes, defaultEasing));
        }
        return new KeyframeEffect(tracks.toArray(Track[]::new));
    }

    /** A single property going linearly from {@code from} to {@code to}. */
    static KeyframeEffect between(Prop prop, Object from, Object to) {
        TimingFunction linear = TimingFunction.LINEAR;
        return new KeyframeEffect(new Track[] {
                new Track(prop, new float[] {0, 1}, new Object[] {from, to}, new TimingFunction[] {linear, linear})});
    }

    /** The properties this effect sets. Read-only (an EnumSet, so unions with it are cheap). */
    Set<Prop> props() {
        return props;
    }

    /** Sets every animated property of {@code target} to its value at iteration {@code progress}. */
    void apply(ComputedStyle target, double progress) {
        for (Track track : tracks) track.apply(target, progress);
    }

    /** One property's keyframes; {@code easings[i]} eases the segment from keyframe i to i + 1. */
    private record Track(Prop prop, float[] offsets, Object[] values, TimingFunction[] easings) {
        static Track of(Prop prop, List<ResolvedKeyframe> keyframes, TimingFunction defaultEasing) {
            List<ResolvedKeyframe> own = new ArrayList<>(keyframes.size());
            for (ResolvedKeyframe k : keyframes) if (k.props().contains(prop)) own.add(k);
            int head = own.getFirst().offset() > 0 ? 1 : 0;
            int tail = own.getLast().offset() < 1 ? 1 : 0;
            int n = head + own.size() + tail;
            float[] offsets = new float[n];
            Object[] values = new Object[n];
            TimingFunction[] easings = new TimingFunction[n];
            for (int i = 0; i < own.size(); i++) {
                ResolvedKeyframe k = own.get(i);
                offsets[head + i] = k.offset();
                values[head + i] = prop.get(k.style());
                easings[head + i] = k.timing() != null ? k.timing() : defaultEasing;
            }
            if (head == 1) {
                values[0] = UNDERLYING;
                easings[0] = defaultEasing;
            }
            if (tail == 1) {
                offsets[n - 1] = 1;
                values[n - 1] = UNDERLYING;
            }
            return new Track(prop, offsets, values, easings);
        }

        void apply(ComputedStyle target, double progress) {
            int i = 0;
            while (i < offsets.length - 2 && progress >= offsets[i + 1]) i++;
            float span = offsets[i + 1] - offsets[i];
            float t = span == 0 ? 1 : (float) ((progress - offsets[i]) / span);
            // Overshooting progress (from an easing beyond 0..1) extrapolates the first or last segment linearly.
            float eased = t < 0 || t > 1 ? t : easings[i].apply(t);
            prop.set(target, Interpolate.value(prop, valueAt(i, target), valueAt(i + 1, target), eased));
        }

        private Object valueAt(int i, ComputedStyle target) {
            return values[i] == UNDERLYING ? prop.get(target) : values[i];
        }
    }
}
