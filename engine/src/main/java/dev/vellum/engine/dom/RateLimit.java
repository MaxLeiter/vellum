package dev.vellum.engine.dom;

/**
 * A token bucket on the frame clock: {@code rate} takes a second, and bursts of up to {@code rate}. Used for what a
 * page may do often but not without end: messages to the server, sounds, log lines.
 */
public final class RateLimit {
    private final int rate;
    private double tokens;
    private double clock;
    private boolean refused;

    public RateLimit(int rate) {
        this.rate = rate;
        this.tokens = rate;
    }

    /** Takes one at {@code nowMs}; false when the allowance is used up. */
    public boolean take(double nowMs) {
        tokens = Math.min(rate, tokens + (nowMs - clock) * rate / 1000);
        clock = nowMs;
        if (tokens < 1) {
            refused = true;
            return false;
        }
        tokens--;
        refused = false;
        return true;
    }

    /** Whether the take before the latest was refused too: callers warn once per run of refusals. */
    public boolean wasRefused() {
        return refused;
    }

    public int rate() {
        return rate;
    }
}
