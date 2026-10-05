package dev.vellum.engine.dom;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.DoubleConsumer;

/**
 * Timers and animation-frame callbacks for a document: {@code setTimeout}, {@code setInterval} and
 * {@code requestAnimationFrame}. Driven by {@link Document#frame}; everything runs on the render thread.
 */
public final class Scheduler {
    private record Timer(int id, double due, double interval, Runnable task, long seq) {}

    private final Document document;
    private final PriorityQueue<Timer> timers = new PriorityQueue<>((a, b) ->
            a.due != b.due ? Double.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
    private final java.util.Set<Integer> cancelled = new java.util.HashSet<>();
    private List<Object[]> frameCallbacks = new ArrayList<>();
    private int nextId = 1;
    private long seq;
    private double now;

    Scheduler(Document document) {
        this.document = document;
    }

    /** The current frame time in ms (monotonic, as {@code performance.now()}). */
    public double now() { return now; }

    public int setTimeout(Runnable task, double delayMs) {
        int id = nextId++;
        timers.add(new Timer(id, now + Math.max(0, delayMs), -1, task, seq++));
        return id;
    }

    public int setInterval(Runnable task, double intervalMs) {
        int id = nextId++;
        double iv = Math.max(1, intervalMs);
        timers.add(new Timer(id, now + iv, iv, task, seq++));
        return id;
    }

    public void clearTimer(int id) {
        cancelled.add(id);
    }

    public int requestAnimationFrame(DoubleConsumer callback) {
        int id = nextId++;
        frameCallbacks.add(new Object[] {id, callback});
        return id;
    }

    public void cancelAnimationFrame(int id) {
        frameCallbacks.removeIf(o -> (Integer) o[0] == id);
    }

    public boolean hasPendingFrameCallbacks() {
        return !frameCallbacks.isEmpty();
    }

    /**
     * Runs due timers (at most a bounded number, so an interval storm cannot hang a frame), then frame callbacks.
     * Called by {@link Document#frame}; public so tests can drive time without the rest of the pipeline.
     */
    public void run(double nowMs) {
        now = nowMs;
        int budget = 1000;
        while (!timers.isEmpty() && timers.peek().due <= nowMs && budget-- > 0) {
            Timer t = timers.poll();
            if (cancelled.remove(t.id)) continue;
            if (t.interval > 0) timers.add(new Timer(t.id, Math.max(t.due + t.interval, nowMs), t.interval, t.task, seq++));
            try {
                t.task.run();
            } catch (RuntimeException ex) {
                document.reportError("Error in timer", ex);
            }
        }
        if (!frameCallbacks.isEmpty()) {
            List<Object[]> callbacks = frameCallbacks;
            frameCallbacks = new ArrayList<>();
            for (Object[] o : callbacks) {
                try {
                    ((DoubleConsumer) o[1]).accept(nowMs);
                } catch (RuntimeException ex) {
                    document.reportError("Error in requestAnimationFrame callback", ex);
                }
            }
        }
    }

    void clear() {
        timers.clear();
        frameCallbacks.clear();
        cancelled.clear();
    }
}
