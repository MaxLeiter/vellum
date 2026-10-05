package dev.vellum.engine.layout;

import dev.vellum.engine.layout.GridPlacement.Placed;
import dev.vellum.engine.style.GridTrack;
import dev.vellum.engine.style.Length;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The grid track sizing algorithm (CSS Grid 1 §11.4–11.8) for one axis: initialise base sizes and growth limits,
 * resolve intrinsic track sizes from the items' contributions (single-span items, then spanning items by span, then
 * items crossing flexible tracks, with the spec's space distribution simplified to equal shares up to limits),
 * maximise tracks, expand flexible tracks and stretch auto tracks. Baseline alignment is not supported.
 */
final class GridTrackSizing {
    private static final float THRESHOLD = 0.01f;

    /** How a sizing function sizes a track. Percentages that cannot resolve behave as auto. */
    enum Kind { FIXED, AUTO, MIN_CONTENT, MAX_CONTENT, FIT_CONTENT, FLEX }

    /** Whether the available space is definite, or the container is sized under a min- or max-content constraint. */
    enum Mode { DEFINITE, MIN_CONTENT, MAX_CONTENT }

    /** A track's sizing functions (normalised) and the algorithm's state. */
    static final class Track {
        final Kind minKind, maxKind;
        /** Fixed sizes in px; for the max function also the fit-content limit or the flex factor. */
        final float minValue, maxValue;
        float base, limit;
        /** Position from the start of the content box, set by the grid layout. */
        float offset;
        /** An empty auto-fit track: zero size, and its gutters collapse. */
        boolean collapsed;
        private float planned, incurred;
        /** The growth limit was infinite before this batch raised it, so it may keep growing (§11.5.1). */
        private boolean infinitelyGrowable;

        Track(Kind minKind, float minValue, Kind maxKind, float maxValue) {
            this.minKind = minKind;
            this.minValue = minValue;
            this.maxKind = maxKind;
            this.maxValue = maxValue;
            this.base = minKind == Kind.FIXED ? minValue : 0;
            this.limit = maxKind == Kind.FIXED ? Math.max(maxValue, base) : Float.POSITIVE_INFINITY;
        }

        /** A track from a (non-repeat) sizing function, resolving percentages against {@code available} (or NaN). */
        static Track of(GridTrack t, float available) {
            return switch (t) {
                case GridTrack.Fixed f -> fixedOrAuto(f.size(), available);
                case GridTrack.Flex f -> new Track(Kind.AUTO, 0, Kind.FLEX, Math.max(0, f.fr()));
                case GridTrack.Keyword k -> new Track(keyword(k.keyword()), 0, keyword(k.keyword()), 0);
                case GridTrack.MinMax m -> {
                    Track min = of(m.min(), available), max = of(m.max(), available);
                    // A flexible minimum is invalid; it behaves as auto.
                    yield min.maxKind == Kind.FLEX ? new Track(Kind.AUTO, 0, max.maxKind, max.maxValue)
                            : new Track(min.minKind, min.minValue, max.maxKind, max.maxValue);
                }
                case GridTrack.FitContent f -> new Track(Kind.AUTO, 0, Kind.FIT_CONTENT,
                        Math.max(0, BoxModel.or(f.limit().resolve(available, Float.NaN), Float.POSITIVE_INFINITY)));
                case GridTrack.Repeat r -> throw new IllegalArgumentException("repeat() must be expanded first");
            };
        }

        private static Track fixedOrAuto(Length l, float available) {
            float v = l.resolve(available, Float.NaN);
            if (Float.isNaN(v)) return new Track(Kind.AUTO, 0, Kind.AUTO, 0);
            return new Track(Kind.FIXED, Math.max(0, v), Kind.FIXED, Math.max(0, v));
        }

        private static Kind keyword(Length k) {
            return switch (k.kind) {
                case MIN_CONTENT -> Kind.MIN_CONTENT;
                case MAX_CONTENT -> Kind.MAX_CONTENT;
                default -> Kind.AUTO;
            };
        }

        static Track collapsedTrack() {
            Track t = new Track(Kind.FIXED, 0, Kind.FIXED, 0);
            t.collapsed = true;
            return t;
        }

        boolean flexible() { return maxKind == Kind.FLEX; }
        float flex() { return flexible() ? maxValue : 0; }
        boolean intrinsicMin() { return minKind != Kind.FIXED; }
        boolean contentMin() { return minKind == Kind.MIN_CONTENT || minKind == Kind.MAX_CONTENT; }
        boolean maxContentMin() { return minKind == Kind.MAX_CONTENT; }
        boolean autoMinGrowable() { return minKind == Kind.AUTO && maxKind != Kind.MIN_CONTENT; }
        boolean intrinsicMax() { return maxKind != Kind.FIXED && maxKind != Kind.FLEX; }
        boolean maxContentMax() {
            return maxKind == Kind.AUTO || maxKind == Kind.MAX_CONTENT || maxKind == Kind.FIT_CONTENT;
        }
        float fitContentLimit() { return maxKind == Kind.FIT_CONTENT ? maxValue : Float.POSITIVE_INFINITY; }
        /** The definite limit of the max sizing function: a fixed size or a fit-content argument. */
        float fixedLimit() { return maxKind == Kind.FIXED ? maxValue : fitContentLimit(); }
        float fitLimitedGrowth() { return Math.min(limit, fitContentLimit()); }
    }

    /** What the algorithm needs from an item in this axis: its outer size contributions. */
    interface Contributions {
        float minContent(Placed item);
        float maxContent(Placed item);
        /**
         * The item's minimum contribution when its size or min-size in this axis is definite (the outer size from
         * it), or NaN when its minimum is automatic.
         */
        float definiteMinimum(Placed item);
        /** Whether the item is a scroll container in this axis (its automatic minimum is zero). */
        boolean scrolls(Placed item);
    }

    private final Track[] tracks;
    private final Axis axis;
    private final Mode mode;
    private final float available, gap;
    private final Contributions contributions;

    private GridTrackSizing(Track[] tracks, Axis axis, Mode mode, float available, float gap, Contributions c) {
        this.tracks = tracks;
        this.axis = axis;
        this.mode = mode;
        this.available = available;
        this.gap = gap;
        this.contributions = c;
    }

    /**
     * Sizes {@code tracks} (in place: base sizes are the result). {@code available} is the definite content size in
     * DEFINITE mode; {@code minSize}/{@code maxSize} are the container's content-box min/max sizes (NaN when none),
     * used for an indefinite container's flexible tracks; {@code stretch} is whether content alignment is
     * normal/stretch.
     */
    static void size(Track[] tracks, List<Placed> items, Axis axis, Mode mode, float available, float gap,
                     Contributions c, boolean stretch, float minSize, float maxSize) {
        GridTrackSizing s = new GridTrackSizing(tracks, axis, mode, available, gap, c);
        s.resolveIntrinsicSizes(items);
        s.maximize();
        s.expandFlexibleTracks(items, minSize, maxSize);
        if (stretch) s.stretchAutoTracks(minSize);
    }

    // ---- 11.5 Resolve intrinsic track sizes ----

    private void resolveIntrinsicSizes(List<Placed> items) {
        List<Placed> spanning = new ArrayList<>(), flexible = new ArrayList<>();
        for (Placed item : items) {
            if (crossesFlexible(item)) flexible.add(item);
            else if (item.span(axis) == 1) sizeToSingleItem(item);
            else spanning.add(item);
        }
        for (Track t : tracks) {
            if (t.planned > 0) t.limit = t.limit == Float.POSITIVE_INFINITY ? t.planned : Math.max(t.limit, t.planned);
            t.planned = 0;
            if (t.limit < t.base) t.limit = t.base;
        }
        spanning.sort(Comparator.comparingInt(item -> item.span(axis)));
        for (int i = 0; i < spanning.size(); ) {
            int j = i;
            while (j < spanning.size() && spanning.get(j).span(axis) == spanning.get(i).span(axis)) j++;
            distributeBatch(spanning.subList(i, j), false);
            i = j;
        }
        if (!flexible.isEmpty()) distributeBatch(flexible, true);
        for (Track t : tracks) if (t.limit == Float.POSITIVE_INFINITY) t.limit = t.base;
    }

    private boolean crossesFlexible(Placed item) {
        for (int i = item.start(axis); i < item.end(axis); i++) if (tracks[i].flexible()) return true;
        return false;
    }

    private boolean intrinsicConstraint() {
        return mode != Mode.DEFINITE;
    }

    /** Step 11.5.2: an item spanning one non-flexible track sets its base size and plans its growth limit. */
    private void sizeToSingleItem(Placed item) {
        Track t = tracks[item.start(axis)];
        Contributions c = contributions;
        switch (t.minKind) {
            case MIN_CONTENT -> t.base = Math.max(t.base, c.minContent(item));
            case MAX_CONTENT -> t.base = Math.max(t.base, c.maxContent(item));
            case AUTO -> t.base = Math.max(t.base, autoMinimum(item, t.fixedLimit()));
            default -> { }
        }
        float growth = switch (t.maxKind) {
            case MIN_CONTENT -> c.minContent(item);
            case MAX_CONTENT, AUTO -> c.maxContent(item);
            case FIT_CONTENT -> c.scrolls(item) ? Math.min(c.maxContent(item), t.fitContentLimit())
                    : Math.max(c.minContent(item), Math.min(c.maxContent(item), t.fitContentLimit()));
            default -> 0;
        };
        t.planned = Math.max(t.planned, growth);
    }

    /**
     * The space an item asks of auto minimum tracks: its minimum contribution, or under an intrinsic sizing
     * constraint its min-content contribution limited by the tracks' fixed maximum (and floored by its minimum).
     */
    private float autoMinimum(Placed item, float limit) {
        if (intrinsicConstraint() && !contributions.scrolls(item)) {
            return Math.max(Math.min(contributions.minContent(item), limit), minimum(item));
        }
        return minimum(item);
    }

    /**
     * An item's minimum contribution (§6.6): from its definite size or min-size, else its automatic minimum. That is
     * its min-content contribution (capped by the fixed maximums of the tracks it spans, when they all have one),
     * except zero for scroll containers and items spanning several tracks of which one is flexible.
     */
    private float minimum(Placed item) {
        float definite = contributions.definiteMinimum(item);
        if (!Float.isNaN(definite)) return definite;
        if (contributions.scrolls(item) || (item.span(axis) > 1 && crossesFlexible(item))) return 0;
        return Math.min(contributions.minContent(item), spannedLimit(item, false));
    }

    /** Steps 11.5.3–4: items spanning several tracks (one batch per span), or the items crossing flexible tracks. */
    private void distributeBatch(List<Placed> batch, boolean flex) {
        Contributions c = contributions;
        for (Placed item : batch) {
            if (!spans(item, Track::intrinsicMin)) continue;
            float space = autoMinimum(item, spannedLimit(item, true));
            if (flex && intrinsicConstraint() && !c.scrolls(item)) {
                space = Math.max(minimum(item), flexScaled(item, space));
            }
            ToDoubleFunction<Track> limit = c.scrolls(item) ? Track::fitLimitedGrowth : t -> t.limit;
            distributeToBase(item, space, flex, Track::intrinsicMin, limit, false);
        }
        flushBase();
        for (Placed item : batch) {
            if (!spans(item, Track::contentMin)) continue;
            distributeToBase(item, c.minContent(item), flex, Track::contentMin, t -> t.limit, false);
        }
        flushBase();
        if (mode == Mode.MAX_CONTENT) {
            for (Placed item : batch) {
                if (!spans(item, t -> t.autoMinGrowable() || t.maxContentMin())) continue;
                float space = Math.min(c.maxContent(item), spannedLimit(item, true));
                if (flex) space = flexScaled(item, space);
                if (spans(item, Track::maxContentMin)) {
                    distributeToBase(item, space, flex, Track::maxContentMin, t -> Float.POSITIVE_INFINITY, true);
                } else {
                    distributeToBase(item, space, flex, Track::autoMinGrowable, Track::fitLimitedGrowth, true);
                }
            }
            flushBase();
        }
        for (Placed item : batch) {
            if (!spans(item, Track::maxContentMin)) continue;
            distributeToBase(item, c.maxContent(item), flex, Track::maxContentMin, t -> t.limit, true);
        }
        flushBase();
        for (Track t : tracks) if (t.limit < t.base) t.limit = t.base;
        if (flex) return;
        for (Placed item : batch) {
            if (spans(item, Track::intrinsicMax)) distributeToLimit(item, c.minContent(item), Track::intrinsicMax);
        }
        flushLimit(true);
        for (Placed item : batch) {
            if (spans(item, Track::maxContentMax)) distributeToLimit(item, c.maxContent(item), Track::maxContentMax);
        }
        flushLimit(false);
    }

    /**
     * The part of an item's space that flexible tracks with a flex factor sum below 1 take: the space beyond the
     * inflexible tracks scales by that sum (as browsers do for fr sums under 1).
     */
    private float flexScaled(Placed item, float space) {
        float inflexible = 0, flexSum = 0;
        for (int i = item.start(axis); i < item.end(axis); i++) {
            Track t = tracks[i];
            if (t.flexible()) flexSum += t.flex();
            else inflexible += t.base;
        }
        return inflexible + Math.max(space - inflexible, 0) * Math.min(flexSum, 1);
    }

    private boolean spans(Placed item, Predicate<Track> filter) {
        for (int i = item.start(axis); i < item.end(axis); i++) if (filter.test(tracks[i])) return true;
        return false;
    }

    /**
     * The sum of the spanned tracks' fixed maximums (with fit-content arguments when {@code withFitContent}) and the
     * gaps between them, or infinity when any track has none.
     */
    private float spannedLimit(Placed item, boolean withFitContent) {
        float sum = gap * (item.span(axis) - 1);
        for (int i = item.start(axis); i < item.end(axis); i++) {
            Track t = tracks[i];
            sum += withFitContent ? t.fixedLimit() : t.maxKind == Kind.FIXED ? t.maxValue : Float.POSITIVE_INFINITY;
        }
        return sum;
    }

    /** The spanned tracks' base sizes (or growth limits, infinite ones counting as the base) plus gaps. */
    private float spannedSize(Placed item, boolean limits) {
        float sum = gap * (item.span(axis) - 1);
        for (int i = item.start(axis); i < item.end(axis); i++) {
            Track t = tracks[i];
            sum += limits && t.limit != Float.POSITIVE_INFINITY ? t.limit : t.base;
        }
        return sum;
    }

    /**
     * Distributes an item's extra space to the base sizes of the affected tracks it spans (§11.5.1): equal shares
     * (flex factors for flexible tracks) up to their limits, then beyond them to tracks that may grow.
     */
    private void distributeToBase(Placed item, float space, boolean flex, Predicate<Track> affected,
                                  ToDoubleFunction<Track> limit, boolean maximum) {
        List<Track> spanned = spanned(item);
        Predicate<Track> filter = flex ? affected.and(Track::flexible) : affected;
        if (spanned.stream().noneMatch(filter)) return;
        float flexSum = 0;
        for (Track t : spanned) if (filter.test(t)) flexSum += t.flex();
        ToDoubleFunction<Track> proportion = flex && flexSum > 0 ? Track::flex : t -> 1;
        float extra = Math.max(0, space - spannedSize(item, false));
        extra = upToLimits(extra, spanned, filter, proportion, limit);
        if (extra > THRESHOLD) {
            Predicate<Track> beyond = filter.and(maximum ? Track::maxContentMax : Track::intrinsicMax);
            if (spanned.stream().noneMatch(beyond)) beyond = filter;
            upToLimits(extra, spanned, beyond, proportion, Track::fitContentLimit);
        }
        for (Track t : spanned) {
            t.planned = Math.max(t.planned, t.incurred);
            t.incurred = 0;
        }
    }

    /** Distributes an item's extra space to the growth limits of the affected tracks it spans. */
    private void distributeToLimit(Placed item, float space, Predicate<Track> affected) {
        List<Track> spanned = spanned(item);
        float extra = Math.max(0, space - spannedSize(item, true));
        List<Track> growable = new ArrayList<>();
        for (Track t : spanned) {
            boolean unlimited = t.infinitelyGrowable || t.fitLimitedGrowth() == Float.POSITIVE_INFINITY;
            if (affected.test(t) && unlimited) growable.add(t);
        }
        if (!growable.isEmpty()) {
            for (Track t : growable) t.incurred = extra / growable.size();
        } else {
            upToLimits(extra, spanned, affected, t -> 1, Track::fitContentLimit,
                    t -> t.limit == Float.POSITIVE_INFINITY ? t.base : t.limit);
        }
        for (Track t : spanned) {
            t.planned = Math.max(t.planned, t.incurred);
            t.incurred = 0;
        }
    }

    private float upToLimits(float space, List<Track> spanned, Predicate<Track> affected,
                             ToDoubleFunction<Track> proportion, ToDoubleFunction<Track> limit) {
        return upToLimits(space, spanned, affected, proportion, limit, t -> t.base);
    }

    /** Shares {@code space} among the affected tracks in proportion, none past its limit; returns what is left. */
    private static float upToLimits(float space, List<Track> spanned, Predicate<Track> affected,
                                    ToDoubleFunction<Track> proportion, ToDoubleFunction<Track> limit,
                                    ToDoubleFunction<Track> size) {
        for (int iteration = 0; iteration <= spanned.size() && space > THRESHOLD; iteration++) {
            float proportions = 0, step = Float.POSITIVE_INFINITY;
            for (Track t : spanned) {
                float room = (float) (limit.applyAsDouble(t) - size.applyAsDouble(t)) - t.incurred;
                if (!affected.test(t) || room <= 0) continue;
                float p = (float) proportion.applyAsDouble(t);
                proportions += p;
                step = Math.min(step, room / p);
            }
            if (proportions == 0) break;
            step = Math.min(step, space / proportions);
            for (Track t : spanned) {
                float room = (float) (limit.applyAsDouble(t) - size.applyAsDouble(t)) - t.incurred;
                if (!affected.test(t) || room <= 0) continue;
                float increase = step * (float) proportion.applyAsDouble(t);
                t.incurred += increase;
                space -= increase;
            }
        }
        return space;
    }

    private List<Track> spanned(Placed item) {
        return List.of(tracks).subList(item.start(axis), item.end(axis));
    }

    private void flushBase() {
        for (Track t : tracks) {
            t.base += t.planned;
            t.planned = 0;
        }
    }

    private void flushLimit(boolean markInfinitelyGrowable) {
        for (Track t : tracks) {
            boolean raised = t.planned > 0;
            if (raised) t.limit = (t.limit == Float.POSITIVE_INFINITY ? t.base : t.limit) + t.planned;
            t.infinitelyGrowable = raised && markInfinitelyGrowable;
            t.planned = 0;
        }
    }

    // ---- 11.6–11.8 ----

    private float usedSpace() {
        float used = gaps();
        for (Track t : tracks) used += t.base;
        return used;
    }

    float gaps() {
        return gapTotal(tracks, gap);
    }

    /** Total gutter size: gaps between tracks, where collapsed (empty auto-fit) tracks lose theirs. */
    static float gapTotal(Track[] tracks, float gap) {
        int visible = 0;
        for (Track t : tracks) if (!t.collapsed) visible++;
        return gap * Math.max(0, visible - 1);
    }

    /** 11.6: grows tracks to their growth limits with the free space (all the way under max-content). */
    private void maximize() {
        if (mode == Mode.MAX_CONTENT) {
            for (Track t : tracks) t.base = t.limit;
        } else if (mode == Mode.DEFINITE) {
            float free = available - usedSpace();
            if (free <= 0) return;
            List<Track> all = List.of(tracks);
            upToLimits(free, all, t -> true, t -> 1, Track::fitLimitedGrowth);
            for (Track t : tracks) {
                t.base += t.incurred;
                t.incurred = 0;
            }
        }
    }

    /** 11.7: sizes flexible tracks from the largest fr size that fits, or from the items' needs when indefinite. */
    private void expandFlexibleTracks(List<Placed> items, float minSize, float maxSize) {
        boolean any = false;
        for (Track t : tracks) any |= t.flexible();
        if (!any || mode == Mode.MIN_CONTENT) return;
        float fr;
        if (mode == Mode.DEFINITE) {
            fr = available - usedSpace() <= 0 ? 0 : frSize(List.of(tracks), available - gaps());
        } else {
            fr = 0;
            for (Track t : tracks) if (t.flexible()) fr = Math.max(fr, t.flex() > 1 ? t.base / t.flex() : t.base);
            for (Placed item : items) {
                if (crossesFlexible(item)) {
                    float space = contributions.maxContent(item) - gap * (item.span(axis) - 1);
                    fr = Math.max(fr, frSize(spanned(item), space));
                }
            }
            float size = gaps();
            for (Track t : tracks) size += t.flexible() ? Math.max(t.base, t.flex() * fr) : t.base;
            if (!Float.isNaN(minSize) && size < minSize) fr = frSize(List.of(tracks), minSize - gaps());
            else if (!Float.isNaN(maxSize) && size > maxSize) fr = frSize(List.of(tracks), maxSize - gaps());
        }
        for (Track t : tracks) if (t.flexible()) t.base = Math.max(t.base, t.flex() * fr);
    }

    /** The size of 1fr (§12.7.1): leftover space over the flex factors, treating too-small flexible tracks as fixed. */
    private static float frSize(List<Track> tracks, float space) {
        if (space <= 0) return 0;
        float fr = Float.POSITIVE_INFINITY;
        for (int iteration = 0; iteration <= tracks.size(); iteration++) {
            float used = 0, flexSum = 0;
            for (Track t : tracks) {
                if (t.flexible() && t.flex() * fr >= t.base) flexSum += t.flex();
                else used += t.base;
            }
            float previous = fr;
            fr = (space - used) / Math.max(flexSum, 1);
            boolean valid = true;
            for (Track t : tracks) {
                if (t.flexible() && t.flex() * fr < t.base && t.flex() * previous >= t.base) valid = false;
            }
            if (valid) break;
        }
        return fr;
    }

    /** 11.8: auto-max tracks share the remaining free space (up to the container's min size when indefinite). */
    private void stretchAutoTracks(float minSize) {
        int autos = 0;
        for (Track t : tracks) if (t.maxKind == Kind.AUTO) autos++;
        if (autos == 0) return;
        float free = mode == Mode.DEFINITE ? available - usedSpace()
                : !Float.isNaN(minSize) ? minSize - usedSpace() : 0;
        if (free <= 0) return;
        for (Track t : tracks) if (t.maxKind == Kind.AUTO) t.base += free / autos;
    }
}
