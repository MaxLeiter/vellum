package dev.vellum.engine.dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.DoubleConsumer;

/**
 * Timers and animation-frame callbacks for a document: {@code setTimeout}, {@code setInterval} and
 * {@code requestAnimationFrame}. Driven by {@link Document#frame}; everything runs on the render thread.
 *
 * <p>Bounded by the document's {@link dev.vellum.engine.Limits}: at most {@code maxTimers} pending timers and
 * callbacks, and {@code frameScriptTimeMs} of them per frame, after which the rest wait for the next frame.
 */
public final class Scheduler {
    private static final class Timer {
        final int id;
        final double interval;
        final Runnable task;
        double due;
        long seq;

        Timer(int id, double due, double interval, Runnable task) {
            this.id = id;
            this.due = due;
            this.interval = interval;
            this.task = task;
        }
    }

    private record FrameCallback(int id, DoubleConsumer callback) {}

    private final Document document;
    private final PriorityQueue<Timer> timers = new PriorityQueue<>((a, b) ->
            a.due != b.due ? Double.compare(a.due, b.due) : Long.compare(a.seq, b.seq));
    /** Timers that have not fired (one-shot) or been cleared, by id. */
    private final Map<Integer, Timer> live = new HashMap<>();
    /** Callbacks for the next frame, and those of the frame being run (swapped each frame). */
    private List<FrameCallback> frameCallbacks = new ArrayList<>(), running = new ArrayList<>();
    private int nextId = 1;
    private long seq;
    private double now;

    Scheduler(Document document) {
        this.document = document;
    }

    /** The current frame time in ms (monotonic, as {@code performance.now()}). */
    public double now() { return now; }

    public int setTimeout(Runnable task, double delayMs) {
        return schedule(new Timer(nextId++, now + Math.max(0, delayMs), -1, task));
    }

    public int setInterval(Runnable task, double intervalMs) {
        double interval = Math.max(1, intervalMs);
        return schedule(new Timer(nextId++, now + interval, interval, task));
    }

    /** Refuses a new timer or callback past {@code maxTimers}. */
    private void checkCount() {
        int max = document.limits().maxTimers();
        if (live.size() + frameCallbacks.size() >= max) {
            throw new IllegalStateException("A page may have at most " + max + " pending timers and animation frames");
        }
    }

    private int schedule(Timer timer) {
        checkCount();
        timer.seq = seq++;
        timers.add(timer);
        live.put(timer.id, timer);
        return timer.id;
    }

    /** {@code clearTimeout} / {@code clearInterval}; ids of fired or unknown timers are ignored. */
    public void clearTimer(int id) {
        Timer timer = live.remove(id);
        if (timer != null) timers.remove(timer);
    }

    public int requestAnimationFrame(DoubleConsumer callback) {
        checkCount();
        int id = nextId++;
        frameCallbacks.add(new FrameCallback(id, callback));
        return id;
    }

    /** Cancels a callback, also one of the frame being run that has not been called yet. */
    public void cancelAnimationFrame(int id) {
        frameCallbacks.removeIf(c -> c.id == id);
        for (int i = 0; i < running.size(); i++) {
            if (running.get(i) != null && running.get(i).id == id) running.set(i, null);
        }
    }

    /** Whether a frame at {@code nowMs} has callbacks to run: animation frames, or timers that are due. */
    boolean hasWork(double nowMs) {
        return !frameCallbacks.isEmpty() || !timers.isEmpty() && timers.peek().due <= nowMs;
    }

    /**
     * Runs due timers, then frame callbacks, until they have taken {@code frameScriptTimeMs}: the rest wait for the
     * next frame, so an interval storm cannot hang a frame. Called by {@link Document#frame}; public so tests can
     * drive time without the rest of the pipeline.
     */
    public void run(double nowMs) {
        now = nowMs;
        long deadline = Document.clock.getAsLong() + document.limits().frameScriptTimeMs() * 1_000_000L;
        int budget = 1000;
        while (!timers.isEmpty() && timers.peek().due <= nowMs && budget-- > 0 && Document.clock.getAsLong() < deadline) {
            Timer t = timers.poll();
            if (t.interval > 0) {
                t.due = Math.max(t.due + t.interval, nowMs);
                t.seq = seq++;
                timers.add(t);
            } else {
                live.remove(t.id);
            }
            try {
                t.task.run();
            } catch (RuntimeException ex) {
                document.reportError("Error in timer", ex);
            }
        }
        if (frameCallbacks.isEmpty()) return;
        List<FrameCallback> callbacks = frameCallbacks;
        frameCallbacks = running;
        running = callbacks;
        try {
            for (int i = 0; i < running.size(); i++) {
                FrameCallback c = running.get(i);
                if (c == null) continue;
                if (i > 0 && Document.clock.getAsLong() >= deadline) { // out of time: the rest run first next frame
                    List<FrameCallback> later = new ArrayList<>(running.subList(i, running.size()));
                    later.removeIf(Objects::isNull);
                    later.addAll(frameCallbacks);
                    frameCallbacks.clear();
                    frameCallbacks.addAll(later);
                    break;
                }
                try {
                    c.callback.accept(nowMs);
                } catch (RuntimeException ex) {
                    document.reportError("Error in requestAnimationFrame callback", ex);
                }
            }
        } finally {
            running.clear();
        }
    }

    void clear() {
        timers.clear();
        live.clear();
        frameCallbacks.clear();
        running.clear();
    }
}
