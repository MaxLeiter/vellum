package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Element;
import dev.vellum.engine.dom.PseudoElement;
import dev.vellum.engine.event.TransitionEvent;
import dev.vellum.engine.style.ComputedStyle;

import java.util.Locale;

/**
 * Plays a {@link KeyframeEffect} on an element over time: the playback half of a Web Animations animation (start
 * time, pausing, seeking, playback rate) plus timing events. CSS transitions, CSS animations and scripted animations
 * are all players, so they share one timing model, one way of applying values and one event sequence.
 *
 * <p>The current time is either advancing (a resolved start time on the engine's clock) or held (paused, finished,
 * waiting for the first tick, or at a zero playback rate). A player that starts playing is pending until the next
 * tick, which sets its start time, so the first rendered frame is its first frame.
 */
abstract class Player {
    /** Timing events, named by subclasses as DOM events ({@link #eventType}). */
    enum Kind { RUN, START, ITERATION, END, CANCEL }

    final AnimationEngine engine;
    /** The element animated, or whose {@link #pseudo} pseudo-element is; its events go to the element. */
    final Element element;
    final PseudoElement pseudo;
    Timing timing;
    KeyframeEffect effect;

    private double rate = 1;
    /** Engine time at which the current time was 0, while advancing; NaN otherwise. */
    private double startTime = Double.NaN;
    /** The current time while held; NaN while advancing or idle. */
    private double holdTime = Double.NaN;
    private boolean paused, pending;
    /** The state at the last {@link #sample}; null while idle. */
    private Timing.Sample sample;
    /** The phase and iteration timing events were last reported for; a null phase means idle. */
    private Timing.Phase reportedPhase;
    private double reportedIteration;

    Player(AnimationEngine engine, Element element, PseudoElement pseudo, Timing timing, KeyframeEffect effect) {
        this.engine = engine;
        this.element = element;
        this.pseudo = pseudo;
        this.timing = timing;
        this.effect = effect;
    }

    /** The DOM event type dispatched for {@code kind}, or null when none is. */
    abstract String eventType(Kind kind);

    /** The {@link TransitionEvent#name} of this player's events. */
    abstract String eventName();

    // ---- Playback state ----

    final double currentTime(double now) {
        if (!Double.isNaN(holdTime)) return holdTime;
        return Double.isNaN(startTime) ? Double.NaN : (now - startTime) * rate;
    }

    final double rate() { return rate; }

    final boolean isIdle() { return Double.isNaN(holdTime) && Double.isNaN(startTime); }

    final boolean isPaused() { return paused; }

    /** Whether the current time moves: the player needs ticks. */
    final boolean isAdvancing() { return pending || !Double.isNaN(startTime); }

    /** Whether the current time moves toward an end it reaches: forwards to a finite end, or backwards to 0. */
    final boolean endsBySelf() {
        return isAdvancing() && (rate < 0 || !Double.isInfinite(timing.endTime()));
    }

    final boolean isFinished(double now) {
        if (paused || pending) return false;
        double t = currentTime(now);
        return rate > 0 && t >= timing.endTime() || rate < 0 && t <= 0;
    }

    /** Whether the effect currently applies (at the last {@link #sample}). */
    final boolean hasEffect() { return sample != null && sample.hasEffect(); }

    /** The eased iteration progress at the last {@link #sample}, or NaN without effect. */
    final double progress() { return sample == null ? Double.NaN : sample.progress(); }

    /** Plays from the current time, rewinding first when idle or finished (Web Animations {@code play()}). */
    final void play(double now) {
        double t = currentTime(now), end = timing.endTime();
        if (rate > 0 && (Double.isNaN(t) || t < 0 || t >= end)) t = 0;
        else if (rate < 0 && (Double.isNaN(t) || t <= 0 || t > end)) t = Double.isInfinite(end) ? 0 : end;
        else if (Double.isNaN(t)) t = 0;
        paused = false;
        hold(t, true);
    }

    /**
     * Runs on from the current time without rewinding ({@code animation-play-state: running}). Also restarts the
     * clock of a finished animation whose timing has since been extended.
     */
    final void resume(double now) {
        paused = false;
        seek(currentTime(now), now);
    }

    final void pause(double now) {
        double t = currentTime(now);
        if (Double.isNaN(t)) t = rate >= 0 ? 0 : timing.endTime();
        paused = true;
        hold(t, false);
    }

    /** Jumps to the end in the playing direction. Does nothing when that end is infinitely far. */
    final void finish(double now) {
        double end = timing.endTime();
        if (rate == 0 || rate > 0 && Double.isInfinite(end)) return;
        paused = false;
        hold(rate > 0 ? end : 0, false);
    }

    final void seek(double t, double now) {
        if (Double.isNaN(t)) return;
        if (isIdle()) paused = true; // seeking an idle animation leaves it paused at that time
        boolean beyondEnd = rate > 0 && t >= timing.endTime() || rate < 0 && t <= 0;
        if (paused || pending || rate == 0 || beyondEnd) hold(t, pending);
        else advanceFrom(t, now);
    }

    final void setRate(double rate, double now) {
        double t = currentTime(now);
        this.rate = rate;
        seek(t, now);
    }

    /** Stops without finishing: removes the effect and reports a cancel if the player was before or active. */
    void cancel() {
        if (isIdle()) return;
        if (reportedPhase == Timing.Phase.BEFORE || reportedPhase == Timing.Phase.ACTIVE) {
            fire(Kind.CANCEL, timing.clampedActiveTime(currentTime(engine.now())));
        }
        startTime = holdTime = Double.NaN;
        paused = pending = false;
        sample = null;
        reportedPhase = null;
    }

    private void hold(double t, boolean pending) {
        holdTime = t;
        startTime = Double.NaN;
        this.pending = pending && rate != 0; // at rate 0 the time never moves, so there is nothing to start
    }

    private void advanceFrom(double t, double now) {
        startTime = now - t / rate;
        holdTime = Double.NaN;
        pending = false;
    }

    // ---- Per frame ----

    /** Updates the sampled phase and progress at {@code now} without reporting events. */
    final void sample(double now) {
        sample = isIdle() ? null : timing.sample(currentTime(now), rate < 0);
    }

    /** Applies the effect onto {@code target} if it currently has one. */
    final void apply(ComputedStyle target) {
        if (hasEffect()) effect.apply(target, sample.progress());
    }

    /** Called once per tick: starts pending playback, stops at the end, samples and reports timing events. */
    void advance(double now) {
        if (isIdle()) return;
        if (pending) advanceFrom(holdTime, now);
        if (!Double.isNaN(startTime) && isFinished(now)) hold(rate > 0 ? timing.endTime() : 0, false);
        sample(now);
        report();
    }

    /** Fires the events for the phase change since the last report (CSS Transitions 2 / CSS Animations 2 tables). */
    private void report() {
        Timing.Phase from = reportedPhase, to = sample.phase();
        double start = timing.intervalStart(), end = timing.intervalEnd();
        if (from == null) {
            fire(Kind.RUN, start);
            if (to != Timing.Phase.BEFORE) fire(Kind.START, start);
            if (to == Timing.Phase.AFTER) fire(Kind.END, end);
        } else if (from != to) {
            switch (from) {
                case BEFORE -> {
                    fire(Kind.START, start);
                    if (to == Timing.Phase.AFTER) fire(Kind.END, end);
                }
                case ACTIVE -> fire(Kind.END, to == Timing.Phase.AFTER ? end : start);
                case AFTER -> {
                    fire(Kind.START, end);
                    if (to == Timing.Phase.BEFORE) fire(Kind.END, start);
                }
            }
        } else if (to == Timing.Phase.ACTIVE && sample.iteration() != reportedIteration) {
            fire(Kind.ITERATION, (sample.iteration() + (rate < 0 ? 1 : 0)) * timing.duration());
        }
        reportedPhase = to;
        reportedIteration = sample.iteration();
    }

    private void fire(Kind kind, double elapsedMs) {
        String type = eventType(kind);
        if (type == null) return;
        String name = eventName();
        engine.post(() -> element.dispatchEvent(new TransitionEvent(type, name, pseudo.cssName, (float) (elapsedMs / 1000))));
    }

    /** {@code prefix} followed by the lower-case kind: "transition" and START make "transitionstart". */
    static String named(String prefix, Kind kind) {
        return prefix + kind.name().toLowerCase(Locale.ROOT);
    }
}
