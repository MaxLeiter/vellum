package dev.vellum.engine.anim;

/**
 * A scripted animation ({@code element.animate()}), Web Animations style: the playback control scripts get, as the
 * {@code Animation} object. Implemented by the animation engine's script animations.
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
