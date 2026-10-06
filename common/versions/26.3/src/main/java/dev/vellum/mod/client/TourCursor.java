package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.vellum.engine.style.Cursor;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLMouse;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The cursor the dev tour draws in game ({@code -Ptour}), since a screen recording does not capture the system's
 * and the tour moves Minecraft's pointer, not the system's. Drawn last, in a stratum of its own above everything the
 * frame has (the screen, its tooltips and dropdowns, HUD overlays over it): the loaders call
 * {@link #extractAboveScreen} after the open screen is drawn and {@link #extractHud} as the last HUD layer for when no
 * screen is open. It shows what the page under the pointer asks for with CSS {@code cursor} (a pointing hand, an
 * I-beam, an open or gripping hand, else the arrow), dips when the button is pressed, and sends a ring out from each
 * press. The shapes are drawn at the window's device-pixel size ({@link CursorArt}) and placed on whole device
 * pixels, so they stay crisp at any GUI scale. While the tour runs the system cursor is hidden and the system's
 * mouse events are switched off, so a stray hand on the mouse can't move or click anything. Render thread only.
 */
public final class TourCursor {
    /** Whether the loaders hook the cursor in: only for the tour, never in normal play. */
    public static final boolean ENABLED = DevTour.ENABLED;

    /** How much bigger than the system's cursor this one is. */
    private static final double SIZE = 1.6;
    /** How small a press makes the cursor, and in how many steps it gets there (each a shape drawn once). */
    private static final double PRESSED_SCALE = 0.85;
    private static final int PRESS_STEPS = 6;
    /** How quickly the cursor dips on a press and comes back on release: the time constant, in seconds. */
    private static final double PRESS_IN = 0.025, PRESS_OUT = 0.06;
    /** A press's ring: how long it lasts, how far it grows (points), its width and soft edge, and how bright it starts. */
    private static final double RING_SECONDS = 0.34, RING_FROM = 3, RING_TO = 15, RING_WIDTH = 1.8, RING_SOFT = 1.2;
    private static final float RING_ALPHA = 0.8f;
    private static final int RING_SEGMENTS = 48;
    private static final int[] MOUSE_EVENTS = {SDLEvents.SDL_EVENT_MOUSE_MOTION, SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN,
            SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP, SDLEvents.SDL_EVENT_MOUSE_WHEEL};

    /** A cursor shape at one press step, in a texture of its own, and where its hot spot is in device px. */
    private record Sprite(Identifier id, int width, int height, double hotX, double hotY) {}

    /** A press's ring: where it was, in device px, and when. */
    private record Ring(double x, double y, long start) {}

    private static boolean active;
    /** The cursor's opacity: fading from {@code fadeFrom} to {@code fadeTo} over {@code fadeNanos} from {@code fadeStart}. */
    private static float fadeFrom = 1, fadeTo = 1;
    private static long fadeStart, fadeNanos = 1;
    /** How far into its press dip the cursor is, 0 to 1. */
    private static double press;
    private static boolean wasPressed;
    private static long lastFrame;
    private static final List<Ring> RINGS = new ArrayList<>();
    /** The shapes drawn so far, for {@link #spriteScale} device px per point; dropped when that changes. */
    private static final Map<CursorArt.Kind, Sprite[]> SPRITES = new EnumMap<>(CursorArt.Kind.class);
    private static double spriteScale;

    private TourCursor() {}

    /** The tour has begun: draw the cursor, and hide the system's and switch its mouse events off. */
    static void start() {
        active = true;
        for (int event : MOUSE_EVENTS) SDLEvents.SDL_SetEventEnabled(event, false);
        SDLMouse.SDL_HideCursor();
    }

    /** The tour is over: the system cursor and mouse are back, and the shapes are freed. */
    static void stop() {
        active = false;
        for (int event : MOUSE_EVENTS) SDLEvents.SDL_SetEventEnabled(event, true);
        SDLMouse.SDL_ShowCursor();
        releaseSprites();
    }

    /** Fades the cursor to {@code alpha} (0 hides it) over {@code seconds}. */
    static void fade(float alpha, double seconds) {
        long now = Util.getNanos();
        fadeFrom = alpha(now);
        fadeTo = alpha;
        fadeStart = now;
        fadeNanos = Math.max(1, (long) (seconds * 1e9));
    }

    // ---- Loader hooks ----

    /**
     * After the open screen has been drawn with its tooltips (NeoForge {@code ScreenEvent.Render.Post} for the current
     * screen, at the lowest priority; Fabric {@code ScreenEvents.afterExtract}), and after the HUD overlays drawn over
     * it.
     */
    public static void extractAboveScreen(GuiGraphicsExtractor g) {
        if (active) extract(g);
    }

    /** The last HUD layer: the cursor while no screen is open and the game does not have the mouse. */
    public static void extractHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (active && mc.gui.screen() == null && !mc.mouseHandler.isMouseGrabbed()) extract(g);
    }

    // ---- Drawing ----

    /**
     * Draws the cursor where this frame's pointer is, the one the screen was drawn with, so hover and cursor agree;
     * then moves the pointer on for the next frame.
     */
    private static void extract(GuiGraphicsExtractor g) {
        SDLMouse.SDL_HideCursor(); // again: the game may show it, as when it gives the mouse back
        draw(g);
        TourPointer.update();
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        long now = Util.getNanos();
        double dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9;
        lastFrame = now;
        int guiScale = w.getGuiScale();
        double gx = mc.mouseHandler.getScaledXPos(w), gy = mc.mouseHandler.getScaledYPos(w);
        double x = gx * guiScale, y = gy * guiScale; // device px
        double pointScale = SIZE * w.getWidth() / Math.max(1, w.getScreenWidth());

        boolean down = TourPointer.pressed();
        if (down && !wasPressed) RINGS.add(new Ring(x, y, now));
        wasPressed = down;
        press += ((down ? 1 : 0) - press) * (1 - Math.exp(-dt / (down ? PRESS_IN : PRESS_OUT)));
        RINGS.removeIf(r -> now - r.start > RING_SECONDS * 1e9);

        float alpha = alpha(now);
        if (alpha <= 0) return;
        g.nextStratum(); // above everything drawn so far
        McCanvas canvas = new McCanvas(g, -1, -1);
        canvas.transform(1f / guiScale, 0, 0, 1f / guiScale, 0, 0); // device px from here on
        for (Ring ring : RINGS) ring(canvas, ring, now, pointScale, alpha);
        Sprite sprite = sprite(kind(gx, gy), (int) Math.round(press * PRESS_STEPS), pointScale);
        canvas.blit(sprite.id, Math.round(x - sprite.hotX), Math.round(y - sprite.hotY), sprite.width, sprite.height,
                0, 0, 1, 1, ARGB.white(alpha), false);
        canvas.finish();
    }

    private static float alpha(long now) {
        double t = Math.clamp((double) (now - fadeStart) / fadeNanos, 0, 1);
        return (float) (fadeFrom + (fadeTo - fadeFrom) * t);
    }

    /** The shape for what the page under the pointer asks for (an overlay's over the screen's). */
    private static CursorArt.Kind kind(double gx, double gy) {
        DocumentDriver driver = VellumHud.pointerDriverAt(gx, gy);
        Screen screen = Minecraft.getInstance().gui.screen();
        if (driver == null && screen != null) driver = DocumentDriver.of(screen);
        Cursor cursor = driver == null ? Cursor.DEFAULT : driver.cursor();
        return switch (cursor) {
            case POINTER -> CursorArt.Kind.HAND;
            case TEXT -> CursorArt.Kind.IBEAM;
            case GRAB -> CursorArt.Kind.GRAB;
            case GRABBING -> CursorArt.Kind.GRABBING;
            default -> CursorArt.Kind.ARROW;
        };
    }

    /**
     * A ring growing out from a press and fading: a band with soft edges, as quads whose outer and inner vertices
     * are transparent.
     */
    private static void ring(McCanvas canvas, Ring ring, long now, double pointScale, float alpha) {
        double t = (now - ring.start) / (RING_SECONDS * 1e9);
        double grow = 1 - Math.pow(1 - t, 3);
        double r = (RING_FROM + (RING_TO - RING_FROM) * grow) * pointScale;
        double half = RING_WIDTH * (1 - 0.4 * t) * pointScale / 2, soft = RING_SOFT * pointScale;
        int solid = ARGB.white((float) (RING_ALPHA * alpha * Math.pow(1 - t, 1.5))), clear = solid & 0xFFFFFF;
        double[] radii = {r - half - soft, r - half, r + half, r + half + soft};
        int[] colors = {clear, solid, solid, clear};
        int bands = radii.length - 1, quads = RING_SEGMENTS * bands;
        float[] xy = new float[quads * 8];
        int[] vertexColors = new int[quads * 4];
        for (int s = 0, q = 0; s < RING_SEGMENTS; s++) {
            double a0 = 2 * Math.PI * s / RING_SEGMENTS, a1 = 2 * Math.PI * (s + 1) / RING_SEGMENTS;
            for (int b = 0; b < bands; b++, q++) {
                double[] corners = {a0, radii[b], a0, radii[b + 1], a1, radii[b + 1], a1, radii[b]};
                int[] cornerColors = {colors[b], colors[b + 1], colors[b + 1], colors[b]};
                for (int v = 0; v < 4; v++) {
                    double angle = corners[v * 2], radius = Math.max(0, corners[v * 2 + 1]);
                    xy[q * 8 + v * 2] = (float) (ring.x + Math.cos(angle) * radius);
                    xy[q * 8 + v * 2 + 1] = (float) (ring.y + Math.sin(angle) * radius);
                    vertexColors[q * 4 + v] = cornerColors[v];
                }
            }
        }
        canvas.fillQuads(xy, vertexColors, quads);
    }

    // ---- Shapes ----

    /** {@code kind} at press step {@code step}, drawn for {@code pointScale} device px per point on first use. */
    private static Sprite sprite(CursorArt.Kind kind, int step, double pointScale) {
        if (pointScale != spriteScale) {
            releaseSprites();
            spriteScale = pointScale;
        }
        Sprite[] steps = SPRITES.computeIfAbsent(kind, k -> new Sprite[PRESS_STEPS + 1]);
        if (steps[step] != null) return steps[step];
        double scale = 1 - (1 - PRESSED_SCALE) * step / PRESS_STEPS;
        CursorArt.Image art = CursorArt.draw(kind, pointScale * scale);
        NativeImage pixels = new NativeImage(art.width(), art.height(), false);
        for (int py = 0; py < art.height(); py++) {
            for (int px = 0; px < art.width(); px++) pixels.setPixel(px, py, art.argb()[py * art.width() + px]);
        }
        String name = "tour_cursor/" + kind.name().toLowerCase(Locale.ROOT) + "_" + step;
        Identifier id = Constants.id(name);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Vellum " + name, pixels));
        return steps[step] = new Sprite(id, art.width(), art.height(), art.hotX(), art.hotY());
    }

    private static void releaseSprites() {
        for (Sprite[] steps : SPRITES.values()) {
            for (Sprite sprite : steps) if (sprite != null) Minecraft.getInstance().getTextureManager().release(sprite.id);
        }
        SPRITES.clear();
    }
}
