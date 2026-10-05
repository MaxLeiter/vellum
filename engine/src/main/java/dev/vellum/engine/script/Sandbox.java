package dev.vellum.engine.script;

import dev.vellum.shadow.rhino.Context;
import dev.vellum.shadow.rhino.ContextFactory;
import dev.vellum.shadow.rhino.ScriptRuntime;
import dev.vellum.shadow.rhino.regexp.RegExpImpl;

import java.util.function.Function;

/**
 * The Rhino sandbox. Every {@link Context} is made by this factory: interpreted mode (nothing is compiled to
 * classes), the newest language level, a class shutter that hides every Java class (so neither scripts nor error
 * objects can reach Java), a bounded interpreter stack, and an instruction observer that enforces the CPU budget of
 * the current entry. Global objects are created with {@code initSafeStandardObjects}, so there is no
 * {@code Packages}, {@code java} or {@code JavaAdapter}.
 */
final class Sandbox extends ContextFactory {
    /** Instructions one entry may run; an interpreted tight loop does roughly 100-250M per second. */
    static final long INSTRUCTION_BUDGET = 50_000_000L;
    /** Wall-clock limit of one entry, checked whenever the observer runs. */
    static final long TIME_BUDGET_MS = 250;
    static final int MAX_STACK_DEPTH = 1000;
    private static final int OBSERVER_INTERVAL = 10_000;

    private static final Sandbox FACTORY = new Sandbox();

    /**
     * Thrown when a script runs out of budget. It is an {@link Error} rather than an exception so Rhino unwinds the
     * whole script stack without running {@code catch} or {@code finally} blocks: scripts cannot swallow it.
     */
    static final class BudgetExceeded extends Error {
        BudgetExceeded() {
            super("Script stopped: it ran longer than its budget (" + INSTRUCTION_BUDGET / 1_000_000
                    + "M instructions or " + TIME_BUDGET_MS + " ms)", null, false, false);
        }
    }

    /** CPU accounting for the outermost entry on a Context; nested entries share it. */
    private static final class Budget {
        long instructions;
        final long deadline = System.nanoTime() + TIME_BUDGET_MS * 1_000_000;
    }

    private Sandbox() {}

    /**
     * Runs {@code action} inside a sandboxed Context. A nested call (a script's {@code el.click()} running a listener)
     * reuses the outer Context and its budget.
     */
    static <T> T run(Function<Context, T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        // Rhino finds services (its RegExp engine) through the context class loader; in a modded game that loader may
        // not see our relocated copy.
        thread.setContextClassLoader(Sandbox.class.getClassLoader());
        Context cx = FACTORY.enterContext();
        boolean outermost = cx.getThreadLocal(Budget.class) == null;
        if (outermost) cx.putThreadLocal(Budget.class, new Budget());
        try {
            return action.apply(cx);
        } finally {
            if (outermost) cx.removeThreadLocal(Budget.class);
            Context.exit();
            thread.setContextClassLoader(saved);
        }
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        cx.setInterpretedMode(true);
        cx.setClassShutter(className -> false);
        cx.setMaximumInterpreterStackDepth(MAX_STACK_DEPTH);
        cx.setInstructionObserverThreshold(OBSERVER_INTERVAL);
        cx.setTrackUnhandledPromiseRejections(true);
        // Belt and braces for the class loader note above: install the RegExp engine directly.
        ScriptRuntime.setRegExpProxy(cx, new RegExpImpl());
        return cx;
    }

    @Override
    protected boolean hasFeature(Context cx, int feature) {
        // Error objects get fileName and lineNumber, which authors expect when logging them.
        return feature == Context.FEATURE_LOCATION_INFORMATION_IN_ERROR || super.hasFeature(cx, feature);
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        if (!(cx.getThreadLocal(Budget.class) instanceof Budget budget)) return;
        budget.instructions += instructionCount;
        if (budget.instructions > INSTRUCTION_BUDGET || System.nanoTime() > budget.deadline) {
            throw new BudgetExceeded();
        }
    }
}
