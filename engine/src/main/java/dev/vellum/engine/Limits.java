package dev.vellum.engine;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Every cap the engine puts on a page: script CPU and memory, DOM and CSS size, canvases, logging and messages. Pages
 * can come from servers (DESIGN.md, "Security"), so each cap is set well above what a real page needs and well below
 * what would freeze or crash the game.
 *
 * <p>A document reads its limits from {@link dev.vellum.engine.host.Host#limits()}, which defaults to
 * {@link #current()}. Code that has no document at hand (the CSS parser) reads {@link #current()} directly. A mod
 * loader installs configured limits with {@link #setCurrent}; {@link #with(String, long)} builds them by field name,
 * with the same names as the record components ({@code maxNodes}, {@code instructionBudget}...).
 *
 * @param instructionBudget   script instructions one entry (a script, a listener, a timer...) may run; 50M
 * @param timeBudgetMs        wall-clock ms one entry may take; 1000
 * @param loadTimeBudgetMs    wall-clock ms an entry may take while the page loads (cold JVM); 10000
 * @param maxStackDepth       nested script calls, beyond which an InternalError is thrown; 1000
 * @param maxBudgetOverruns   entries stopped by the CPU or memory budget before the whole page is stopped; 3
 * @param frameScriptTimeMs   ms of timers and animation-frame callbacks per frame; the rest wait a frame; 100
 * @param slowFrameMs         a frame (scripts, style, layout, paint) slower than this counts as slow; 200
 * @param maxSlowFrames       slow frames in a row before the page is stopped; 25
 * @param entryAllocation     bytes one entry may allocate (where the JVM can count them); 256 MiB
 * @param heapLimitPercent    a script entry stops its page when the heap is fuller than this after a GC (100: off); 90
 * @param maxStringLength     characters a string built by repeat(), padStart() or padEnd() may have; 16M
 * @param maxArrayLength      length of an array that built-ins may iterate or create, and arguments to apply(); 1M
 * @param maxBufferBytes      bytes of an ArrayBuffer or typed array; 16 MiB (a 2048 px square canvas's image data)
 * @param maxBigIntBits       bits of a BigInt that arithmetic or parsing produces (process-wide); 65536
 * @param maxTimers           pending timers plus animation-frame callbacks of a page; 10000
 * @param maxMarkupLength     characters innerHTML, outerHTML, insertAdjacentHTML and v-html take; 1M
 * @param storageQuota        characters (keys plus values) in each of localStorage and sessionStorage; 256K
 * @param maxLogLength        characters of one console message; longer ones are cut; 4096
 * @param logRate             console messages and script errors logged per second (also the burst); 50
 * @param sendRate            vellum.send messages per second (also the burst); 20
 * @param soundRate           vellum.playSound calls per second (also the burst); 20
 * @param maxForItems         items one v-for renders; 10000
 * @param maxTemplatePasses   template update passes per frame before giving up on a binding that keeps changing; 10
 * @param maxNodes            nodes in a page's document; inserting more fails; 100000
 * @param maxDepth            element nesting depth; the parser flattens deeper markup, scripts get an error; 512
 * @param maxCssNesting       nesting of CSS functions and blocks ({@code calc(}, {@code :is(}, rules); 32
 * @param maxSelectorParts    simple selectors in one complex selector, counting those inside :is() and friends; 256
 * @param maxListItems        items of a CSS list value (shadows); longer lists are invalid; 64
 * @param maxGridTracks       grid tracks a track list may expand to with repeat(); 100000
 * @param maxVarLength        characters a value may have after var() substitution; 65536
 * @param maxCanvasSize       pixels a canvas may have on each side; 2048
 * @param maxCanvasPixels     pixels of all canvases of a page together; 16M (four 2048 px squares)
 * @param maxInlinePageLength characters of a page a server sends inline (enforced by the network layer); 200000
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
        int sendRate,
        int soundRate,
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
        int maxCanvasPixels,
        int maxInlinePageLength) {

    public static final Limits DEFAULTS = new Limits(
            50_000_000L, 1000, 10_000, 1000, 3, 100, 200, 25,
            256L << 20, 90, 1 << 24, 1 << 20, 1 << 24, 1 << 16, 10_000, 1 << 20, 256 * 1024, 4096, 50, 20, 20,
            10_000, 10,
            100_000, 512, 32, 256, 64, 100_000, 65_536, 2048, 2048 * 2048 * 4, 200_000);

    private static volatile Limits current = DEFAULTS;

    public Limits {
        Object[] values = {instructionBudget, timeBudgetMs, loadTimeBudgetMs, maxStackDepth, maxBudgetOverruns,
                frameScriptTimeMs, slowFrameMs, maxSlowFrames, entryAllocation, heapLimitPercent, maxStringLength,
                maxArrayLength, maxBufferBytes, maxBigIntBits, maxTimers, maxMarkupLength, storageQuota, maxLogLength,
                logRate, sendRate, soundRate, maxForItems, maxTemplatePasses, maxNodes, maxDepth, maxCssNesting,
                maxSelectorParts, maxListItems, maxGridTracks, maxVarLength, maxCanvasSize, maxCanvasPixels,
                maxInlinePageLength};
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
