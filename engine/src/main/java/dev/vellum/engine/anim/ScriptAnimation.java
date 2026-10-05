package dev.vellum.engine.anim;

import dev.vellum.engine.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code element.animate()} animation. Every control call takes effect at once and asks the engine to restyle
 * the element on the next tick. Finish and cancel callbacks run once, after the tick that observes the change, like
 * the settling of a Web Animations {@code finished} promise.
 */
final class ScriptAnimation extends Player implements Animation {
    private final List<Runnable> finishCallbacks = new ArrayList<>(1);
    private final List<Runnable> cancelCallbacks = new ArrayList<>(1);

    ScriptAnimation(AnimationEngine engine, Element element, KeyframeEffect effect, Timing timing) {
        super(engine, element, timing, effect);
    }

    @Override
    public void play() {
        play(engine.now());
        engine.changed(this);
    }

    @Override
    public void pause() {
        pause(engine.now());
        engine.changed(this);
    }

    @Override
    public void cancel() {
        if (isIdle()) return;
        super.cancel();
        finishCallbacks.clear(); // as a rejected promise: these never run
        cancelCallbacks.forEach(engine::post);
        cancelCallbacks.clear();
        engine.changed(this);
    }

    @Override
    public void finish() {
        finish(engine.now());
        engine.changed(this);
    }

    @Override
    public void reverse() {
        double now = engine.now();
        setRate(-rate(), now);
        play(now);
        engine.changed(this);
    }

    @Override
    public PlayState playState() {
        if (isIdle()) return PlayState.IDLE;
        if (isPaused()) return PlayState.PAUSED;
        return isFinished(engine.now()) ? PlayState.FINISHED : PlayState.RUNNING;
    }

    @Override
    public double currentTime() {
        return currentTime(engine.now());
    }

    @Override
    public void setCurrentTime(double ms) {
        seek(ms, engine.now());
        engine.changed(this);
    }

    @Override
    public double playbackRate() {
        return rate();
    }

    @Override
    public void setPlaybackRate(double rate) {
        setRate(rate, engine.now());
        engine.changed(this);
    }

    @Override
    public void onFinish(Runnable callback) {
        if (playState() == PlayState.FINISHED) callback.run();
        else finishCallbacks.add(callback);
    }

    @Override
    public void onCancel(Runnable callback) {
        cancelCallbacks.add(callback);
    }

    @Override
    void advance(double now) {
        super.advance(now);
        if (!finishCallbacks.isEmpty() && isFinished(now)) {
            finishCallbacks.forEach(engine::post);
            finishCallbacks.clear();
        }
    }

    @Override
    String eventType(Kind kind) {
        return null; // scripts observe scripted animations through callbacks, not DOM events
    }

    @Override
    String eventName() {
        return "";
    }
}
