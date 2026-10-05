package dev.vellum.engine.anim;

/**
 * A running scripted animation ({@code element.animate()}), Web Animations style. STUB: implemented by the animation
 * workstream; the method set is the contract the script bindings rely on.
 */
public interface Animation {
    enum PlayState { IDLE, RUNNING, PAUSED, FINISHED }

    void play();
    void pause();
    void cancel();
    void finish();
    void reverse();
    PlayState playState();
    /** Current time in ms since the start (including delay), or NaN when idle. */
    double currentTime();
    void setCurrentTime(double ms);
    double playbackRate();
    void setPlaybackRate(double rate);
    /** Runs when the animation finishes (immediately if already finished). */
    void onFinish(Runnable callback);
    /** Runs when the animation is cancelled. */
    void onCancel(Runnable callback);
}
