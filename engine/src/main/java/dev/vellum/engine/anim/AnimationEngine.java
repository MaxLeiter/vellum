package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.PseudoElement;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.TimingFunction;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * CSS transitions and keyframe animations, plus the Web Animations-style {@code element.animate()} used by scripts,
 * and the used styles they make: {@code Element.style}, {@code beforeStyle} and {@code afterStyle}.
 *
 * <p>All three are players of keyframe effects (see {@code Player}) sharing the Web Animations timing model. Each
 * animated target, an element or its ::before or ::after pseudo-element, keeps its players in an
 * {@code ElementAnimations}, one per target in {@code Element.animationState}; only targets with one are visited by
 * {@link #tick}.
 *
 * <p>A used style is the base style with the target's effects applied, computed at used-value time
 * ({@code StyleEngine.computeUsed}): what the target inherits follows its parent's used values (a pseudo-element's
 * parent is its element), and what it computes from {@code color} or {@code font-size} ({@code currentColor},
 * {@code em}) follows its own animated ones. So animating {@code color} recolours the element's borders, its
 * {@code currentColor} backgrounds and its children's text. A target with nothing animated in it or above it keeps
 * its base style object as its used style.
 *
 * <p>Timing events ({@code transitionrun/start/end/cancel}, {@code animationstart/iteration/end/cancel}) and script
 * callbacks are queued while styles update and dispatched at the end of {@link #tick}, so listeners always see
 * consistent styles. A pseudo-element's events go to its element, naming it in {@code pseudoElement}.
 *
 * <p>Reduced motion ({@link dev.vellum.engine.host.Host#prefersReducedMotion}): transitions do not run (properties
 * change at once, without transition events), and keyframe animations, CSS or scripted, run with no delay and no
 * duration, so they fire their start and end events immediately and show their end state if they fill forwards.
 */
public final class AnimationEngine {
    private final Document document;
    /** States of the animated targets, in the order they got them. */
    private final List<ElementAnimations> animated = new ArrayList<>();
    /** Events and callbacks waiting for the end of the tick. */
    private List<Runnable> queued = new ArrayList<>();
    /** The {@link Document#domVersion} at the last check that every animated element is still in the document. */
    private int connectedVersion;

    public AnimationEngine(Document document) {
        this.document = document;
    }

    /**
     * Called by the style engine after computing the base style of an element ({@code which} is
     * {@link PseudoElement#NONE}) or of its ::before or ::after pseudo-element, the element first and parents before
     * children. Starts, retargets or cancels transitions (comparing {@code oldBase} with {@code newBase} for
     * properties listed in {@code transition}), starts or stops keyframe animations when {@code animation-name}
     * changes, and sets the target's used style. {@code oldBase} is null the first time a target is styled (no
     * transitions then); {@code newBase} is null when it is not rendered.
     */
    public void styleChanged(Element element, PseudoElement which, ComputedStyle oldBase, ComputedStyle newBase) {
        ElementAnimations state = stateOf(element, which);
        if (oldBase != newBase) { // else the style engine kept the style: nothing to start or stop
            boolean mayAnimate = newBase != null
                    && (!newBase.animations.isEmpty() || oldBase != null && !newBase.transitions.isEmpty());
            if (state == null && mayAnimate) state = new ElementAnimations(this, element, which);
            if (state != null) {
                state.styleChanged(oldBase, newBase, now());
                if (state.isEmpty()) release(state);
                else register(state);
            }
        }
        update(element, which);
    }

    /**
     * Advances running transitions and animations to {@code nowMs}, recomputing the used styles of animated targets
     * and of whatever inherits from them, firing transition/animation events, and invalidating layout when a
     * layout-affecting property moved. Elements that left the document lose their animations.
     */
    public void tick(double nowMs) {
        boolean treeChanged = document.domVersion() != connectedVersion;
        connectedVersion = document.domVersion();
        for (Iterator<ElementAnimations> it = animated.iterator(); it.hasNext(); ) {
            ElementAnimations state = it.next();
            if (treeChanged && !state.element.isConnected()) state.cancelAll();
            else if (state.needsTick()) state.tick(nowMs);
            else continue;
            if (state.isEmpty()) {
                it.remove();
                clearSlot(state);
            }
            if (update(state.element, state.which)) updateInheritors(state.element, state.which);
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

    List<ResolvedKeyframe> resolveKeyframes(Element element, PseudoElement which, String name, ComputedStyle base) {
        return document.styleEngine().resolveKeyframes(element, which, name, base);
    }

    /** Queues an event dispatch or callback for the end of the current (or next) tick. */
    void post(Runnable task) {
        queued.add(task);
    }

    /** A script changed {@code animation}'s playback: attach it to its element, which the next tick restyles. */
    void changed(ScriptAnimation animation) {
        ElementAnimations state = stateOf(animation.element, PseudoElement.NONE);
        if (state == null) {
            if (animation.isIdle()) return; // not attached, nothing to remove
            register(state = new ElementAnimations(this, animation.element, PseudoElement.NONE));
        }
        state.attach(animation);
    }

    // ---- Internals ----

    private static ElementAnimations stateOf(Element element, PseudoElement which) {
        return element.animationState instanceof ElementAnimations[] states ? states[which.ordinal()] : null;
    }

    private void register(ElementAnimations state) {
        Element element = state.element;
        ElementAnimations[] states = element.animationState instanceof ElementAnimations[] s
                ? s : new ElementAnimations[PseudoElement.values().length];
        if (states[state.which.ordinal()] == state) return;
        states[state.which.ordinal()] = state;
        element.animationState = states;
        animated.add(state);
    }

    private void release(ElementAnimations state) {
        if (animated.remove(state)) clearSlot(state);
    }

    private static void clearSlot(ElementAnimations state) {
        ElementAnimations[] states = (ElementAnimations[]) state.element.animationState;
        states[state.which.ordinal()] = null;
        for (ElementAnimations s : states) if (s != null) return;
        state.element.animationState = null;
    }

    /**
     * Recomputes a target's used style (see the class comment) and stores it, telling the document when paint order
     * moved or layout must run again. Returns whether values its inheritors read changed.
     */
    private boolean update(Element element, PseudoElement which) {
        StyleEngine styles = document.styleEngine();
        ElementAnimations state = stateOf(element, which);
        ComputedStyle style = styles.computeUsed(element, which, null);
        if (state != null && style != null) {
            if (state.setsComputedFrom()) style = styles.computeUsed(element, which, state.applied(style));
            style = state.compose(style);
        }
        ComputedStyle before = switch (which) {
            case BEFORE -> element.beforeStyle;
            case AFTER -> element.afterStyle;
            default -> element.style;
        };
        if (style == before) return false;
        switch (which) {
            case BEFORE -> element.beforeStyle = style;
            case AFTER -> element.afterStyle = style;
            default -> element.style = style;
        }
        if (before == null || style == null) {
            if (!document.needsLayout()) document.invalidateLayout();
            return true;
        }
        if (!before.sameStacking(style)) document.invalidateStacking();
        // Element boxes paint with the element's live style; pseudo-element boxes and their text keep the style they
        // were laid out with, so any change to theirs lays out again.
        boolean relayout = which == PseudoElement.NONE ? !before.sameLayout(style) : !before.sameAs(style);
        if (relayout && !document.needsLayout()) document.invalidateLayout();
        return !before.sameInherited(style);
    }

    /**
     * After an element's inherited values changed outside a restyle: updates its pseudo-elements and children, and
     * theirs while their inherited values change too. (A restyle reaches every target itself.)
     */
    private void updateInheritors(Element element, PseudoElement which) {
        if (which != PseudoElement.NONE) return;
        update(element, PseudoElement.BEFORE);
        update(element, PseudoElement.AFTER);
        for (int i = 0, n = element.childCount(); i < n; i++) {
            if (element.childAt(i) instanceof Element child && update(child, PseudoElement.NONE)) {
                updateInheritors(child, PseudoElement.NONE);
            }
        }
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
