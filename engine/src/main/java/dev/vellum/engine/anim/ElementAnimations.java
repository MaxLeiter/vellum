package dev.vellum.engine.anim;

import dev.vellum.engine.css.ResolvedKeyframe;
import dev.vellum.engine.dom.Element;
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
 * The animations of one element (its {@code Element.animationState}): CSS transitions, CSS animations and scripted
 * animations, and how they combine into {@code element.style}. Later layers win: base, then transitions, then CSS
 * animations in {@code animation-name} order, then scripted animations in creation order.
 */
final class ElementAnimations {
    final Element element;
    private final AnimationEngine engine;
    private final List<CssTransition> transitions = new ArrayList<>();
    private List<CssAnimation> cssAnimations = new ArrayList<>();
    private final List<ScriptAnimation> scripted = new ArrayList<>();
    /** Every property an effect has set on this element, to tell when animating moved layout. */
    private final EnumSet<Prop> animated = EnumSet.noneOf(Prop.class);
    /** Something changed since the last tick (a restyle or a script call), so the next tick must update. */
    private boolean dirty;

    ElementAnimations(AnimationEngine engine, Element element) {
        this.engine = engine;
        this.element = element;
    }

    boolean isEmpty() {
        return transitions.isEmpty() && cssAnimations.isEmpty() && scripted.isEmpty();
    }

    /** Whether the next tick has work: a player whose time moves, or a change since the last tick. */
    boolean needsTick() {
        if (dirty) return true;
        for (Player p : players()) if (p.isAdvancing()) return true;
        return false;
    }

    /** The base style changed: update transitions and CSS animations from it (see the engine's styleChanged). */
    void styleChanged(ComputedStyle oldBase, ComputedStyle newBase, double now) {
        boolean rendered = newBase != null && newBase.display != Display.NONE;
        if (rendered) updateTransitions(oldBase, newBase, now);
        else cancel(transitions);
        updateCssAnimations(rendered ? newBase.animations : List.of(), newBase, now);
        for (Player p : players()) p.sample(now);
        dirty = true;
    }

    /** Advances every player to {@code now} and drops the ones that are done. */
    void tick(double now) {
        for (Player p : players()) p.advance(now);
        transitions.removeIf(t -> !t.hasEffect()); // finished or cancelled; the base value has taken over
        pruneScripted(now);
        dirty = false;
    }

    /**
     * {@code base} with every current effect applied in layer order, onto a copy made when the first one applies:
     * {@code base} itself when none does.
     */
    ComputedStyle compose(ComputedStyle base) {
        if (base == null) return null;
        ComputedStyle style = base;
        for (Player p : players()) {
            if (!p.hasEffect()) continue;
            if (style == base) style = base.copy();
            animated.addAll(p.effect.props());
            p.apply(style);
        }
        return style;
    }

    /** Whether an animated property that affects layout differs between two styles of this element. */
    boolean layoutChanged(ComputedStyle before, ComputedStyle after) {
        if (before == after) return false;
        if (before == null || after == null) return true;
        for (Prop p : animated) {
            if (p.affectsLayout && !Objects.equals(p.get(before), p.get(after))) return true;
        }
        return false;
    }

    /** Adds a scripted animation that is (again) playing and marks the element for the next tick. */
    void attach(ScriptAnimation animation) {
        if (!animation.isIdle() && !scripted.contains(animation)) scripted.add(animation);
        dirty = true;
    }

    /** Cancels everything (the element left the document). */
    void cancelAll() {
        for (Player p : players()) p.cancel();
        transitions.clear();
        cssAnimations.clear();
        scripted.clear();
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
            for (Prop prop : Prop.forTransitionName(spec.property())) {
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
            next = CssTransition.start(engine, element, prop, prop.get(oldBase), after, spec, now);
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
                List<ResolvedKeyframe> keyframes = engine.resolveKeyframes(element, spec.name(), base);
                if (keyframes.isEmpty()) continue; // no such @keyframes rule: nothing runs
                animation = new CssAnimation(engine, element, spec, base, keyframes, now);
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
     * removal, so repeated {@code fill: "forwards"} animations do not pile up). Scripts can replay them.
     */
    private void pruneScripted(double now) {
        if (scripted.isEmpty()) return;
        EnumSet<Prop> covered = EnumSet.noneOf(Prop.class);
        for (int i = scripted.size() - 1; i >= 0; i--) {
            ScriptAnimation a = scripted.get(i);
            boolean finished = a.isFinished(now);
            if (a.isIdle() || finished && (!a.hasEffect() || covered.containsAll(a.effect.props()))) {
                scripted.remove(i);
            } else if (finished) {
                covered.addAll(a.effect.props());
            }
        }
    }

    // ---- Helpers ----

    /** Every player, bottom layer first. */
    private List<Player> players() {
        List<Player> all = new ArrayList<>(transitions.size() + cssAnimations.size() + scripted.size());
        all.addAll(transitions);
        all.addAll(cssAnimations);
        all.addAll(scripted);
        return all;
    }

    private static void cancel(List<? extends Player> players) {
        for (Player p : players) p.cancel();
        players.clear();
    }
}
