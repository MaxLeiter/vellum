package dev.vellum.mod;

import java.util.ArrayDeque;

/**
 * Counts events within a sliding window of {@code windowMs}. Times are milliseconds on any clock, passed in so tests
 * can use their own. Not thread-safe.
 */
public final class RecentEvents {
    private final long windowMs;
    /** Times of the events still in the window, newest last. */
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    public RecentEvents(long windowMs) {
        this.windowMs = windowMs;
    }

    /** Records an event at {@code now}; true (and starts over) when it is the {@code threshold}th within the window. */
    public boolean record(long now, int threshold) {
        while (!times.isEmpty() && now - times.peekFirst() > windowMs) times.pollFirst();
        times.addLast(now);
        if (times.size() < threshold) return false;
        times.clear();
        return true;
    }

    public void clear() {
        times.clear();
    }
}
