package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.TimingFunction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * CSS transitions and keyframe animations, plus the Web Animations-style {@code element.animate()} used by scripts.
 *
 * <p>All three are players of keyframe effects (see {@code Player}) sharing the Web Animations timing model. Each
 * animated element keeps its players in {@code Element.animationState}; elements without any have no state, are
 * never visited by {@link #tick}, and keep {@code element.style == element.baseStyle}.
 *
 * <p>Timing events ({@code transitionrun/start/end/cancel}, {@code animationstart/iteration/end/cancel}) and script
 * callbacks are queued while styles update and dispatched at the end of {@link #tick}, so listeners always see
 * consistent styles.
 *
 * <p>Reduced motion ({@link dev.vellum.engine.host.Host#prefersReducedMotion}): transitions do not run (properties
 * change at once, without transition events), and keyframe animations, CSS or scripted, run with no delay and no
 * duration, so they fire their start and end events immediately and show their end state if they fill forwards.
 */
public final class AnimationEngine {
    private final Document document;
    /** States of the elements with animations, in the order they got them. */
    private final List<ElementAnimations> animated = new ArrayList<>();
    /** Events and callbacks waiting for the end of the tick. */
    private List<Runnable> queued = new ArrayList<>();
    /** The {@link Document#domVersion} at the last check that every animated element is still in the document. */
    private int connectedVersion;

    public AnimationEngine(Document document) {
        this.document = document;
    }

    /**
     * Called by the style engine after computing an element's base style. Starts, retargets or cancels
     * transitions (comparing {@code oldBase} with {@code newBase} for properties listed in {@code transition}),
     * starts or stops keyframe animations when {@code animation-name} changes, and sets {@code element.style}.
     * {@code oldBase} is null the first time an element is styled (no transitions then).
     */
    public void styleChanged(Element element, ComputedStyle oldBase, ComputedStyle newBase) {
        ElementAnimations state = stateOf(element);
        if (oldBase == newBase) { // the style engine kept the style: nothing to start or stop
            if (state == null) element.style = newBase;
            return;
        }
        if (state == null) {
            boolean mayAnimate = newBase != null
                    && (!newBase.animations.isEmpty() || oldBase != null && !newBase.transitions.isEmpty());
            if (!mayAnimate) {
                element.style = newBase;
                return;
            }
            state = new ElementAnimations(this, element);
        }
        state.styleChanged(oldBase, newBase, now());
        if (state.isEmpty()) release(state);
        else register(state);
        update(state, newBase);
    }

    /**
     * Advances running transitions and animations to {@code nowMs}, recomputing {@code element.style} for animated
     * elements, firing transition/animation events, and invalidating layout when a layout-affecting property moved.
     * Elements that left the document lose their animations.
     */
    public void tick(double nowMs) {
        boolean treeChanged = document.domVersion() != connectedVersion;
        connectedVersion = document.domVersion();
        for (Iterator<ElementAnimations> it = animated.iterator(); it.hasNext(); ) {
            ElementAnimations state = it.next();
            if (treeChanged && !state.element.isConnected()) state.cancelAll();
            else if (state.needsTick()) state.tick(nowMs);
            else continue;
            update(state, state.element.baseStyle);
            if (state.isEmpty()) {
                it.remove();
                state.element.animationState = null;
            }
        }
        dispatchQueued();
    }

    /**
     * Starts a scripted animation ({@code element.animate}). Keyframes must have offsets set (0..1, sorted); the
     * script layer computes them with {@code StyleEngine.computeDeclarations} and distributes missing offsets.
     * Missing 0% / 100% keyframes animate from / to the underlying value.
     */
    public Animation animate(Element element, List<ResolvedKeyframe> keyframes, Timing timing) {
        ScriptAnimation animation = new ScriptAnimation(this, element,
                KeyframeEffect.of(keyframes, TimingFunction.LINEAR), effective(timing));
        animation.play();
        return animation;
    }

    /** True while anything is running (hosts may use it to keep rendering). */
    public boolean isAnimating() {
        if (!queued.isEmpty()) return true;
        for (ElementAnimations state : animated) if (state.needsTick()) return true;
        return false;
    }

    // ---- For players ----

    /**
     * The current time: the frame's, which the scheduler holds from the start of a frame (before restyle and
     * {@link #tick}) until the next one.
     */
    double now() {
        return document.scheduler().now();
    }

    boolean reducedMotion() {
        return document.host().prefersReducedMotion();
    }

    /** The timing a keyframe animation actually plays with: instant under reduced motion. */
    Timing effective(Timing timing) {
        return reducedMotion() ? timing.instant() : timing;
    }

    List<ResolvedKeyframe> resolveKeyframes(Element element, String name, ComputedStyle base) {
        return document.styleEngine().resolveKeyframes(element, name, base);
    }

    /** Queues an event dispatch or callback for the end of the current (or next) tick. */
    void post(Runnable task) {
        queued.add(task);
    }

    /** A script changed {@code animation}'s playback: attach it to its element, which the next tick restyles. */
    void changed(ScriptAnimation animation) {
        ElementAnimations state = stateOf(animation.element);
        if (state == null) {
            if (animation.isIdle()) return; // not attached, nothing to remove
            register(state = new ElementAnimations(this, animation.element));
        }
        state.attach(animation);
    }

    // ---- Internals ----

    private static ElementAnimations stateOf(Element element) {
        return element.animationState instanceof ElementAnimations state ? state : null;
    }

    private void register(ElementAnimations state) {
        if (state.element.animationState == state) return;
        state.element.animationState = state;
        animated.add(state);
    }

    private void release(ElementAnimations state) {
        if (state.element.animationState != state) return;
        state.element.animationState = null;
        animated.remove(state);
    }

    /** Recomposes the element's style on {@code base}, invalidating layout if a layout property moved. */
    private void update(ElementAnimations state, ComputedStyle base) {
        ComputedStyle before = state.element.style, after = state.compose(base);
        state.element.style = after;
        if (!document.needsLayout() && layoutMoved(before, after)) document.invalidateLayout();
    }

    private static boolean layoutMoved(ComputedStyle before, ComputedStyle after) {
        return before == null || after == null ? before != after : !before.sameLayout(after);
    }

    private void dispatchQueued() {
        if (queued.isEmpty()) return;
        List<Runnable> tasks = queued;
        queued = new ArrayList<>(); // tasks queued by listeners wait for the next tick
        for (Runnable task : tasks) {
            try {
                task.run();
            } catch (RuntimeException e) {
                document.reportError("Error in animation callback", e);
            }
        }
    }
}
