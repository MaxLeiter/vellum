package dev.vellum.mod;

/**
 * A rate limit: up to {@code burst} at once, then {@code perSecond}. Times are {@link System#nanoTime()} values, passed
 * in so tests can use their own clock. Not thread-safe.
 */
public final class TokenBucket {
    private final double burst, perSecond;
    private double tokens;
    private long last;
    private boolean started;

    public TokenBucket(double burst, double perSecond) {
        this.burst = burst;
        this.perSecond = perSecond;
        this.tokens = burst;
    }

    /** Takes a token if one is left at {@code nowNanos}. */
    public boolean tryTake(long nowNanos) {
        refill(nowNanos);
        if (tokens < 1) return false;
        tokens--;
        return true;
    }

    /** Whether {@link #tryTake} would succeed at {@code nowNanos}, without taking. */
    public boolean available(long nowNanos) {
        refill(nowNanos);
        return tokens >= 1;
    }

    public boolean tryTake() {
        return tryTake(System.nanoTime());
    }

    private void refill(long now) {
        if (started) tokens = Math.min(burst, tokens + Math.max(0, now - last) / 1e9 * perSecond);
        started = true;
        last = now;
    }
}
