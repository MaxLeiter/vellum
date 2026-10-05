package dev.vellum.engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every cap the engine puts on a page: script CPU and memory, DOM and CSS size, canvases and logging. Pages
 * can come from servers (DESIGN.md, "Security"), so each cap is set well above what a real page needs and well below
 * what would freeze or crash the game.
 *
 * <p>A document reads its limits from {@link dev.vellum.engine.host.Host#limits()}, which defaults to
 * {@link #current()}. Code that has no document at hand (the CSS parser) reads {@link #current()} directly. A mod
 * loader installs configured limits with {@link #setCurrent}; {@link #with(String, long)} builds them by field name,
 * with the same names as the record components ({@code maxNodes}, {@code instructionBudget}...).
 *
 * <p>What reaches past the page is the host's to limit, since only the host knows what lasts across reloads: how
 * often {@code vellum.send} and {@code vellum.playSound} go through ({@link dev.vellum.engine.host.Host#send} may
 * refuse), and how large a page a server may send.
 *
 * <p>{@link #describe} says what each field guards, in the words the config file's comments use.
 */
public record Limits(
        long instructionBudget,
        int timeBudgetMs,
        int loadTimeBudgetMs,
        int maxStackDepth,
        int maxBudgetOverruns,
        int frameScriptTimeMs,
        int slowFrameMs,
        int maxSlowFrames,
        long entryAllocation,
        int heapLimitPercent,
        int maxStringLength,
        int maxArrayLength,
        int maxBufferBytes,
        int maxBigIntBits,
        int maxTimers,
        int maxMarkupLength,
        int storageQuota,
        int maxLogLength,
        int logRate,
        int maxForItems,
        int maxTemplatePasses,
        int maxNodes,
        int maxDepth,
        int maxCssNesting,
        int maxSelectorParts,
        int maxListItems,
        int maxGridTracks,
        int maxVarLength,
        int maxCanvasSize,
        int maxCanvasPixels) {

    public static final Limits DEFAULTS = new Limits(
            50_000_000L, 1000, 10_000, 1000, 3, 100, 200, 25,
            256L << 20, 90, 1 << 24, 1 << 20, 1 << 24, 1 << 16, 10_000, 1 << 20, 256 * 1024, 4096, 50,
            10_000, 10,
            100_000, 512, 32, 256, 64, 100_000, 65_536, 2048, 2048 * 2048 * 4);

    private static volatile Limits current = DEFAULTS;

    public Limits {
        Object[] values = {instructionBudget, timeBudgetMs, loadTimeBudgetMs, maxStackDepth, maxBudgetOverruns,
                frameScriptTimeMs, slowFrameMs, maxSlowFrames, entryAllocation, heapLimitPercent, maxStringLength,
                maxArrayLength, maxBufferBytes, maxBigIntBits, maxTimers, maxMarkupLength, storageQuota, maxLogLength,
                logRate, maxForItems, maxTemplatePasses, maxNodes, maxDepth, maxCssNesting,
                maxSelectorParts, maxListItems, maxGridTracks, maxVarLength, maxCanvasSize, maxCanvasPixels};
        RecordComponent[] components = Limits.class.getRecordComponents();
        for (int i = 0; i < values.length; i++) {
            if (((Number) values[i]).longValue() < 1) {
                throw new IllegalArgumentException(components[i].getName() + " must be at least 1");
            }
        }
        if (heapLimitPercent > 100) throw new IllegalArgumentException("heapLimitPercent must be at most 100");
    }

    /** The limits of hosts that do not choose their own: {@link #DEFAULTS} until a loader installs others. */
    public static Limits current() {
        return current;
    }

    /** Installs the limits that hosts use by default, for documents created from now on. */
    public static void setCurrent(Limits limits) {
        current = Objects.requireNonNull(limits);
    }

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("instructionBudget", "Script instructions one entry (a script, a listener, a timer...) may run."),
            Map.entry("timeBudgetMs", "Milliseconds one entry may take."),
            Map.entry("loadTimeBudgetMs", "Milliseconds an entry may take while the page loads, when a game that has just started is slow."),
            Map.entry("maxStackDepth", "Nested script calls; one more throws an InternalError the script can catch."),
            Map.entry("maxBudgetOverruns", "Entries stopped by the CPU or memory budget before the whole page is stopped."),
            Map.entry("frameScriptTimeMs", "Milliseconds of timers and animation-frame callbacks per frame; the rest wait a frame."),
            Map.entry("slowFrameMs", "A frame (scripts, style, layout and paint) slower than this many milliseconds counts as slow."),
            Map.entry("maxSlowFrames", "Slow frames in a row before the page is stopped."),
            Map.entry("entryAllocation", "Bytes one entry may allocate, where the JVM can count them (256 MiB)."),
            Map.entry("heapLimitPercent", "A script stops its page when the heap is fuller than this percentage after a collection. 100 turns it off."),
            Map.entry("maxStringLength", "Characters a string from repeat(), padStart(), padEnd(), replace() or join() may have (16M)."),
            Map.entry("maxArrayLength", "Length of an array built-ins may iterate or create, of apply() arguments, and pieces of split() or match() (1M)."),
            Map.entry("maxBufferBytes", "Bytes of an ArrayBuffer or typed array (16 MiB, the image data of a 2048 px square canvas)."),
            Map.entry("maxBigIntBits", "Bits of a BigInt that arithmetic or parsing produces. One value for the whole game."),
            Map.entry("maxTimers", "Pending timers and animation-frame callbacks of a page."),
            Map.entry("maxMarkupLength", "Characters innerHTML, outerHTML, insertAdjacentHTML and v-html take (1M)."),
            Map.entry("storageQuota", "Characters (keys plus values) in each of localStorage and sessionStorage (256K)."),
            Map.entry("maxLogLength", "Characters of one console message; longer ones are cut."),
            Map.entry("logRate", "Console messages and script errors a page may log per second, and at once."),
            Map.entry("maxForItems", "Items one v-for renders."),
            Map.entry("maxTemplatePasses", "Template update passes per frame before giving up on a binding that keeps changing."),
            Map.entry("maxNodes", "Nodes in a page's document; inserting more throws."),
            Map.entry("maxDepth", "Element nesting; the parser flattens deeper markup and scripts get an error. Also the deepest JSON.parse() reads."),
            Map.entry("maxCssNesting", "Nesting of CSS functions and blocks (calc(), :is(), nested rules)."),
            Map.entry("maxSelectorParts", "Simple selectors in one selector, counting those inside :is() and the like."),
            Map.entry("maxListItems", "Items of a CSS list value (shadows); longer lists are invalid."),
            Map.entry("maxGridTracks", "Grid tracks a track list may expand to with repeat()."),
            Map.entry("maxVarLength", "Characters a value may have after var() substitution."),
            Map.entry("maxCanvasSize", "Pixels a canvas may have on each side."),
            Map.entry("maxCanvasPixels", "Pixels of all of a page's canvases together (16M, four 2048 px squares)."));

    /** What the field {@code name} guards, in one sentence: the comment above its key in the config file. */
    public static String describe(String name) {
        String d = DESCRIPTIONS.get(name);
        if (d == null) throw new IllegalArgumentException("Unknown limit: " + name);
        return d;
    }

    /** The field names, in order: the keys a config file uses. */
    public static List<String> names() {
        return Arrays.stream(Limits.class.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** The value of the field {@code name}. */
    public long get(String name) {
        RecordComponent c = component(name);
        try {
            return ((Number) c.getAccessor().invoke(this)).longValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A copy with the field {@code name} set to {@code value}. Throws {@link IllegalArgumentException} for an unknown
     * name or a value out of range (below 1, too large for the field, or a percentage above 100), so a config loader
     * can keep the default and warn.
     */
    public Limits with(String name, long value) {
        RecordComponent[] components = Limits.class.getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean found = false;
        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = components[i].getAccessor().invoke(this);
                if (components[i].getName().equals(name)) {
                    found = true;
                    if (types[i] == int.class) {
                        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
                            throw new IllegalArgumentException(name + " must be at most " + Integer.MAX_VALUE);
                        }
                        values[i] = (int) value;
                    } else {
                        values[i] = value;
                    }
                }
            }
            if (!found) throw new IllegalArgumentException("Unknown limit: " + name);
            Constructor<Limits> constructor = Limits.class.getDeclaredConstructor(types);
            return constructor.newInstance(values);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalStateException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RecordComponent component(String name) {
        for (RecordComponent c : Limits.class.getRecordComponents()) if (c.getName().equals(name)) return c;
        throw new IllegalArgumentException("Unknown limit: " + name);
    }
}
