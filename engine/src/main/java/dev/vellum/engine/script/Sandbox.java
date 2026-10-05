package dev.vellum.engine.script;

import dev.vellum.engine.Limits;
import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.ContextFactory;
import dev.vellum.shadow.rhino.ScriptRuntime;
import dev.vellum.shadow.rhino.regexp.RegExpImpl;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The Rhino sandbox. Every {@link Context} is made by this factory: interpreted mode (nothing is compiled to
 * classes), the newest language level, a class shutter that hides every Java class (so neither scripts nor error
 * objects can reach Java), a bounded interpreter stack, and an instruction observer that enforces the budget of
 * the current entry. Global objects are created with {@code initSafeStandardObjects}, so there is no
 * {@code Packages}, {@code java} or {@code JavaAdapter}; {@link Builtins} then caps the built-ins that loop or
 * allocate in Java.
 *
 * <p>The budget of an entry ({@link Limits}) has three parts. Instructions are the real guard against runaway
 * scripts; Rhino's regular expressions count their backtracking steps as instructions too, so a catastrophic pattern
 * runs out of budget like a loop. The wall clock catches what instructions do not count, slow host calls, and is
 * kept off one-off work that a cold JVM makes slow: compiling ({@link #compile}: a page's scripts, a template the
 * first time it shows) does not count, and is charged to the instructions by source length instead; and the entries
 * that load a page (its scripts, the first template render) get the longer load allowance. Memory is the third:
 * the bytes the entry allocates (where the JVM counts them per thread), and the heap as a whole, which must not be
 * nearly full after a collection while a page's script runs.
 */
final class Sandbox extends ContextFactory {
    private static final int OBSERVER_INTERVAL = 10_000;
    /** How often, at most, the heap's post-collection use is read during an entry. */
    private static final long HEAP_CHECK_NS = 20_000_000;

    private static final Sandbox FACTORY = new Sandbox();
    /** The wall clock in ns; tests simulate slow machines with their own. */
    static LongSupplier clock = System::nanoTime;

    /**
     * Thrown when a script runs out of budget. It is an {@link Error} rather than an exception so Rhino unwinds the
     * whole script stack without running {@code catch} or {@code finally} blocks: scripts cannot swallow it.
     */
    static final class BudgetExceeded extends Error {
        /** Whether it was the memory budget: the page may be holding on to what it allocated. */
        final boolean memory;

        BudgetExceeded(String message, boolean memory) {
            super(message, null, false, false);
            this.memory = memory;
        }
    }

    /** Thrown when the heap is nearly full: the page is stopped and its memory let go. */
    static final class HeapExhausted extends Error {
        HeapExhausted(int percent) {
            super("Script stopped: the game is out of memory (the heap is over " + percent + "% full)", null, false, false);
        }
    }

    /** Accounting for the outermost entry on a Context; nested entries share it. */
    private static final class Budget {
        final Limits limits;
        final long timeMs;
        final long allocatedAtStart;
        long instructions;
        long deadline;
        long nextHeapCheck;

        Budget(Limits limits, boolean loading) {
            this.limits = limits;
            this.timeMs = loading ? limits.loadTimeBudgetMs() : limits.timeBudgetMs();
            long now = clock.getAsLong();
            deadline = now + timeMs * 1_000_000;
            nextHeapCheck = now + HEAP_CHECK_NS;
            allocatedAtStart = Memory.allocated();
        }

        void charge(long count) {
            instructions += count;
            long now = clock.getAsLong();
            if (instructions > limits.instructionBudget() || now > deadline) {
                throw new BudgetExceeded("Script stopped: it ran longer than its budget ("
                        + limits.instructionBudget() / 1_000_000 + "M instructions or " + timeMs + " ms)", false);
            }
            checkMemory(now >= nextHeapCheck);
        }

        void checkMemory(boolean heap) {
            if (allocatedAtStart >= 0 && Memory.allocated() - allocatedAtStart > limits.entryAllocation()) {
                throw new BudgetExceeded("Script stopped: it allocated more than " + (limits.entryAllocation() >> 20)
                        + " MiB", true);
            }
            if (heap) {
                nextHeapCheck = clock.getAsLong() + HEAP_CHECK_NS;
                if (Memory.heapOver(limits.heapLimitPercent())) throw new HeapExhausted(limits.heapLimitPercent());
            }
        }
    }

    /** What the JVM reports about memory: bytes this thread allocated, and the heap's use after collections. */
    static final class Memory {
        private static final com.sun.management.ThreadMXBean THREADS = threads();
        private static final List<MemoryPoolMXBean> HEAP = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP && p.getCollectionUsage() != null).toList();

        private Memory() {}

        private static com.sun.management.ThreadMXBean threads() {
            try {
                if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t
                        && t.isThreadAllocatedMemorySupported() && t.isThreadAllocatedMemoryEnabled()) {
                    return t;
                }
            } catch (RuntimeException | LinkageError e) {
                // A JVM without the extension: no per-entry allocation budget, the heap check still applies.
            }
            return null;
        }

        /** Bytes the current thread has allocated so far, or -1 when the JVM does not say. */
        static long allocated() {
            return THREADS == null ? -1 : THREADS.getCurrentThreadAllocatedBytes();
        }

        /** Whether the heap was fuller than {@code percent} after the latest collections. */
        static boolean heapOver(int percent) {
            if (percent >= 100) return false;
            long used = 0;
            for (MemoryPoolMXBean pool : HEAP) {
                MemoryUsage usage = pool.getCollectionUsage();
                if (usage != null) used += usage.getUsed();
            }
            long max = Runtime.getRuntime().maxMemory();
            return max != Long.MAX_VALUE && used * 100 > max * percent;
        }
    }

    private Sandbox() {}

    /** {@link #run(Limits, boolean, Function)} with the installed limits, outside page loading. */
    static <T> T run(Function<Context, T> action) {
        return run(Limits.current(), false, action);
    }

    /**
     * Runs {@code action} inside a sandboxed Context, within {@code limits} (the load allowance of wall-clock time
     * when {@code loading}). A nested call (a script's {@code el.click()} running a listener) reuses the outer
     * Context and its budget.
     */
    static <T> T run(Limits limits, boolean loading, Function<Context, T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        // Rhino finds services (its RegExp engine) through the context class loader; in a modded game that loader may
        // not see our relocated copy.
        thread.setContextClassLoader(Sandbox.class.getClassLoader());
        Context cx = FACTORY.enterContext();
        boolean outermost = cx.getThreadLocal(Budget.class) == null;
        if (outermost) {
            cx.putThreadLocal(Budget.class, new Budget(limits, loading));
            cx.setMaximumInterpreterStackDepth(limits.maxStackDepth());
        }
        try {
            return action.apply(cx);
        } finally {
            if (outermost) cx.removeThreadLocal(Budget.class);
            Context.exit();
            thread.setContextClassLoader(saved);
        }
    }

    /**
     * Checks the memory budget of the current entry now, heap included. Entries that run too few instructions for
     * the observer to look (a timer that keeps one big allocation each time) are checked when they end.
     */
    static void checkMemory() {
        Context cx = Context.getCurrentContext();
        if (cx != null && cx.getThreadLocal(Budget.class) instanceof Budget budget) budget.checkMemory(true);
    }

    /**
     * Compiles {@code source} for the current entry: its time does not count against the wall clock, and it costs one
     * instruction per character, so a script that keeps generating code to compile still runs out of budget.
     */
    static <T> T compile(String source, Supplier<T> compiler) {
        Context cx = Context.getCurrentContext();
        if (cx == null || !(cx.getThreadLocal(Budget.class) instanceof Budget budget)) return compiler.get();
        budget.charge(source.length());
        long start = clock.getAsLong();
        try {
            return compiler.get();
        } finally {
            budget.deadline += clock.getAsLong() - start;
        }
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        cx.setInterpretedMode(true);
        cx.setClassShutter(className -> false);
        cx.setMaximumInterpreterStackDepth(Limits.DEFAULTS.maxStackDepth());
        cx.setInstructionObserverThreshold(OBSERVER_INTERVAL);
        cx.setTrackUnhandledPromiseRejections(true);
        // Belt and braces for the class loader note above: install the RegExp engine directly.
        ScriptRuntime.setRegExpProxy(cx, new RegExpImpl());
        return cx;
    }

    @Override
    protected boolean hasFeature(Context cx, int feature) {
        // Error objects get fileName and lineNumber, which authors expect when logging them. No E4X: there is no XML
        // implementation, and none is wanted.
        if (feature == Context.FEATURE_E4X) return false;
        return feature == Context.FEATURE_LOCATION_INFORMATION_IN_ERROR || super.hasFeature(cx, feature);
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        if (cx.getThreadLocal(Budget.class) instanceof Budget budget) budget.charge(instructionCount);
    }
}
