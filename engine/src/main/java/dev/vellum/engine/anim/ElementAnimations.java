package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.css.StyleEngine;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.PseudoElement;
import dev.vellum.engine.style.AnimationSpec;
import dev.vellum.engine.style.ComputedStyle;
import dev.vellum.engine.style.Display;
import dev.vellum.engine.style.Prop;
import dev.vellum.engine.style.TransitionSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * The animations of one element or one of its ::before/::after pseudo-elements (kept in the element's
 * {@code Element.animationState}): CSS transitions, CSS animations and scripted animations, and how they combine
 * into its used style. Later layers win: base, then transitions, then CSS animations in {@code animation-name}
 * order, then scripted animations in creation order.
 */
final class ElementAnimations {
    final Element element;
    /** {@link PseudoElement#NONE} for the element itself. Scripted animations only run on elements. */
    final PseudoElement which;
    private final AnimationEngine engine;
    private final List<CssTransition> transitions = new ArrayList<>();
    private List<CssAnimation> cssAnimations = new ArrayList<>();
    private final List<ScriptAnimation> scripted = new ArrayList<>();
    /** All of the above, bottom layer first; rebuilt by {@link #layer} whenever one of them changes. */
    private final List<Player> players = new ArrayList<>();
    /** Something changed since the last tick (a restyle or a script call), so the next tick must update. */
    private boolean dirty;
    /** Some player's time moves, as of the last tick: finished animations that fill forwards need no ticks. */
    private boolean advancing;
    /** The composed style handed out last ({@code element.style}) and the one the next compose writes. */
    private Composed front = new Composed(), back = new Composed();

    ElementAnimations(AnimationEngine engine, Element element, PseudoElement which) {
        this.engine = engine;
        this.element = element;
        this.which = which;
    }

    boolean isEmpty() {
        return players.isEmpty();
    }

    /** Whether the next tick has work: a player whose time moves, or a change since the last tick. */
    boolean needsTick() {
        return dirty || advancing;
    }

    /** Whether a change waits for the next tick or a player is heading for an end it will reach. */
    boolean isSettling() {
        if (dirty) return true;
        for (Player p : players) if (p.endsBySelf()) return true;
        return false;
    }

    /** The base style changed: update transitions and CSS animations from it (see the engine's styleChanged). */
    void styleChanged(ComputedStyle oldBase, ComputedStyle newBase, double now) {
        boolean rendered = newBase != null && newBase.display != Display.NONE;
        if (rendered) updateTransitions(oldBase, newBase, now);
        else cancel(transitions);
        updateCssAnimations(rendered ? newBase.animations : List.of(), newBase, now);
        layer();
        for (Player p : players) p.sample(now);
        dirty = true;
    }

    /** Advances every player to {@code now} and drops the ones that are done. */
    void tick(double now) {
        advancing = false;
        for (Player p : players) {
            p.advance(now);
            advancing |= p.isAdvancing();
        }
        boolean removed = transitions.removeIf(t -> !t.hasEffect()); // finished or cancelled: the base has taken over
        if (pruneScripted(now) || removed) layer();
        dirty = false;
    }

    /**
     * {@code base} with every current effect applied in layer order, or {@code base} itself when none applies.
     *
     * <p>Two styles alternate as the result, so the one handed out last frame stays intact for comparison
     * ({@link AnimationEngine}'s layout check) and is overwritten the frame after: by then any layout-affecting change
     * between the two has caused a relayout, so a box tree still holding the older one only sees newer paint values.
     */
    ComputedStyle compose(ComputedStyle base) {
        if (base == null) return null;
        Composed target = null;
        for (Player p : players) {
            if (!p.hasEffect()) continue;
            if (target == null) target = back.reset(base);
            target.written.addAll(p.effect.props());
            p.apply(target.style);
        }
        if (target == null) return base;
        back = front;
        front = target;
        return target.style;
    }

    /** Whether a current effect sets color or font-size, which other values are computed from (currentColor, em). */
    boolean setsComputedFrom() {
        for (Player p : players) {
            if (p.hasEffect() && (p.effect.props().contains(Prop.COLOR) || p.effect.props().contains(Prop.FONT_SIZE))) {
                return true;
            }
        }
        return false;
    }

    /** A copy of {@code base} with every current effect applied (outside {@link #compose}'s alternating styles). */
    ComputedStyle applied(ComputedStyle base) {
        ComputedStyle s = base.copy();
        for (Player p : players) p.apply(s);
        return s;
    }

    /** Adds a scripted animation that is (again) playing and marks the element for the next tick. */
    void attach(ScriptAnimation animation) {
        if (!animation.isIdle() && !scripted.contains(animation)) {
            scripted.add(animation);
            layer();
        }
        dirty = true;
    }

    /** Cancels everything (the element left the document). */
    void cancelAll() {
        for (Player p : players) p.cancel();
        transitions.clear();
        cssAnimations.clear();
        scripted.clear();
        players.clear();
    }

    /** A style composed on a base, with the properties effects wrote into it. */
    private static final class Composed {
        ComputedStyle style, base;
        final EnumSet<Prop> written = EnumSet.noneOf(Prop.class);

        /** Makes {@link #style} a copy of {@code base}: by restoring what effects wrote when it already was one. */
        Composed reset(ComputedStyle base) {
            if (style == null || this.base != base) {
                style = base.copy();
                this.base = base;
            } else {
                for (Prop p : written) p.set(style, p.get(base));
            }
            written.clear();
            return this;
        }
    }

    // ---- Transitions ----

    /**
     * CSS Transitions 1 §3 for a base style change: starts, retargets, reverses and cancels transitions. They only
     * start between two rendered styles and not under reduced motion, and are cancelled when their property is no
     * longer listed or its combined duration is not positive.
     */
    private void updateTransitions(ComputedStyle oldBase, ComputedStyle newBase, double now) {
        boolean canStart = oldBase != null && oldBase.display != Display.NONE && !engine.reducedMotion();
        EnumSet<Prop> listed = EnumSet.noneOf(Prop.class);
        List<TransitionSpec> specs = newBase.transitions;
        for (int i = specs.size() - 1; i >= 0; i--) { // the last entry naming a property wins
            TransitionSpec spec = specs.get(i);
            boolean enabled = canStart && Math.max(spec.durationMs(), 0) + spec.delayMs() > 0;
            for (Prop prop : StyleEngine.transitionProperties(spec.property())) {
                if (listed.add(prop)) updateTransition(prop, enabled ? spec : null, oldBase, prop.get(newBase), now);
            }
        }
        transitions.removeIf(t -> {
            if (listed.contains(t.prop)) return false;
            t.cancel();
            return true;
        });
    }

    /** Updates the transition of {@code prop} towards {@code after}; a null {@code spec} disables it. */
    private void updateTransition(Prop prop, TransitionSpec spec, ComputedStyle oldBase, Object after, double now) {
        CssTransition running = null;
        for (CssTransition t : transitions) if (t.prop == prop) running = t;
        CssTransition next;
        if (running == null) {
            if (spec == null) return;
            next = CssTransition.start(engine, element, which, prop, prop.get(oldBase), after, spec, now);
        } else {
            if (spec != null && Objects.equals(running.to, after)) return; // still heading for the new value
            transitions.remove(running);
            next = running.retarget(after, spec, now);
        }
        if (next != null) transitions.add(next);
    }

    // ---- CSS animations ----

    /**
     * Matches {@code animation-name} entries to the running animations by name (repeated names pair up from the
     * end), so changing other animation properties or unrelated styles never restarts an animation. Unmatched
     * animations are cancelled; new names start if a {@code @keyframes} rule defines them.
     */
    private void updateCssAnimations(List<AnimationSpec> specs, ComputedStyle base, double now) {
        if (specs.isEmpty() && cssAnimations.isEmpty()) return;
        List<CssAnimation> unmatched = new ArrayList<>(cssAnimations);
        CssAnimation[] matched = new CssAnimation[specs.size()];
        for (int i = specs.size() - 1; i >= 0; i--) {
            AnimationSpec spec = specs.get(i);
            CssAnimation animation = takeLast(unmatched, spec.name());
            if (animation != null) {
                animation.update(spec, base, now);
            } else {
                List<ResolvedKeyframe> keyframes = engine.resolveKeyframes(element, which, spec.name(), base);
                if (keyframes.isEmpty()) continue; // no such @keyframes rule: nothing runs
                animation = new CssAnimation(engine, element, which, spec, base, keyframes, now);
            }
            matched[i] = animation;
        }
        cancel(unmatched);
        List<CssAnimation> next = new ArrayList<>(matched.length);
        for (CssAnimation a : matched) if (a != null) next.add(a);
        cssAnimations = next;
    }

    private static CssAnimation takeLast(List<CssAnimation> animations, String name) {
        for (int i = animations.size() - 1; i >= 0; i--) {
            if (animations.get(i).name.equals(name)) return animations.remove(i);
        }
        return null;
    }

    // ---- Scripted animations ----

    /**
     * Drops scripted animations that no longer affect the element: cancelled ones, finished ones that do not fill,
     * and finished filling ones whose every property a later finished filling one sets (Web Animations' automatic
     * removal, so repeated {@code fill: "forwards"} animations do not pile up). Scripts can replay them. Returns
     * whether any was dropped.
     */
    private boolean pruneScripted(double now) {
        if (scripted.isEmpty()) return false;
        int before = scripted.size();
        EnumSet<Prop> covered = null;
        for (int i = scripted.size() - 1; i >= 0; i--) {
            ScriptAnimation a = scripted.get(i);
            boolean finished = a.isFinished(now);
            if (a.isIdle() || finished && (!a.hasEffect() || covered != null && covered.containsAll(a.effect.props()))) {
                scripted.remove(i);
            } else if (finished) {
                if (covered == null) covered = EnumSet.noneOf(Prop.class);
                covered.addAll(a.effect.props());
            }
        }
        return scripted.size() != before;
    }

    // ---- Helpers ----

    /** Rebuilds {@link #players} from the three layers. */
    private void layer() {
        players.clear();
        players.addAll(transitions);
        players.addAll(cssAnimations);
        players.addAll(scripted);
    }

    private static void cancel(List<? extends Player> players) {
        for (Player p : players) p.cancel();
        players.clear();
    }
}
