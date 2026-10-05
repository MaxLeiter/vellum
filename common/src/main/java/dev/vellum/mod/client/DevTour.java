package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.function.IntConsumer;

/**
 * Dev-only scripted tour of the showcase for demo videos ({@code -Ptour}, which also sets {@code vellum.autopilot}).
 * The autopilot creates the world, then this plan drives the pages through Minecraft's own mouse and keyboard
 * handlers, so hover effects, clicks, sounds and scripts behave as they do for a player. With
 * {@code -Pvellum.tour.go=<file>} it waits for that file, which tools/record creates once capture has started.
 */
final class DevTour {
    static final boolean ENABLED = Boolean.getBoolean("vellum.tour");
    private static final String GO = System.getProperty("vellum.tour.go");
    /** Ticks per second of the client tick that runs the steps. */
    private static final int TPS = 20;

    private final Minecraft mc;
    private final Deque<Runnable> steps;
    private final IntConsumer wait;
    private double pointerX, pointerY;

    private DevTour(Minecraft mc, Deque<Runnable> steps, IntConsumer wait) {
        this.mc = mc;
        this.steps = steps;
        this.wait = wait;
    }

    /** Queues the tour after the autopilot has loaded the world. */
    static void plan(Minecraft mc, Deque<Runnable> steps, IntConsumer wait) {
        new DevTour(mc, steps, wait).plan();
    }

    private void plan() {
        steps.add(() -> {
            mc.options.guiScale().set(0); // auto: the largest scale that still fits 320x240
            mc.resizeGui();
            mc.getMusicManager().stopPlaying();
        });
        waitForRecorder();
        steps.add(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.MUSIC_DISC_CREATOR.value(), 1f, 0.45f)));
        pause(0.5);

        // The main menu: parallax landscape, then each button slides and glows under the pointer.
        open(VellumClientCommands.showcaseUrl("title"));
        pause(4);
        for (int i = 1; i <= 3; i++) {
            glide(".panel > button:nth-child(" + i + ")", 0.3);
            pause(0.8);
        }
        glide(".row button.half", 0.3);
        pause(1.5);

        // Flexbox and grid: justify-content rows, then a grid-template-areas dashboard whose cells lift on hover.
        open(VellumClientCommands.demoUrl("layout"));
        pause(2.5);
        scroll(".window", -2);
        pause(1);
        for (String cell : new String[] {".side", ".head", ".main", ".stat", ".foot"}) {
            glide(".dashboard " + cell, 0.3);
            pause(0.5);
        }
        scroll(".window", -3);
        pause(1.5);

        // Keyframes and transitions: spinning and bobbing items, staggered slide-ins, hover lifts.
        open(VellumClientCommands.demoUrl("animation"));
        pause(3);
        for (int i = 1; i <= 3; i++) {
            glide(".buttons button:nth-child(" + i + ")", 0.3);
            pause(0.6);
        }
        pause(1);

        // The trader: hover the rarity glows, add one ware the purse can cover (it starts with 96), buy.
        open(VellumClientCommands.showcaseUrl("shop"));
        pause(2.5);
        for (int i = 1; i <= 3; i++) {
            glide(".wares .card:nth-child(" + i + ")", 0.35);
            pause(0.5);
        }
        glide(".wares .card:nth-child(4)", 0.35);
        pause(0.4);
        click(".wares .card:nth-child(4)");
        pause(1);
        glide("#buy", 0.4);
        pause(0.3);
        click("#buy");
        pause(3.5);

        // The showcase gallery to close on.
        open(VellumClientCommands.showcaseUrl("index"));
        pause(2.5);
        for (int i = 1; i <= 6; i++) {
            glide(".card:nth-child(" + i + ")", 0.3);
            pause(0.3);
        }
        pause(1.5);
        steps.add(() -> Constants.LOG.info("Vellum tour finished"));
    }

    // ---- Steps ----

    private void open(String url) {
        steps.add(() -> VellumScreens.open(url));
    }

    private void pause(double seconds) {
        steps.add(() -> wait.accept((int) Math.round(seconds * TPS)));
    }

    /** Holds the tour until the recorder's go-file exists (when one was given). */
    private void waitForRecorder() {
        if (GO == null) return;
        steps.add(new Runnable() {
            @Override
            public void run() {
                if (!Files.exists(Path.of(GO))) {
                    steps.addFirst(this);
                    wait.accept(2);
                }
            }
        });
    }

    /** Moves the pointer to the centre of the first element matching {@code selector}, eased over {@code seconds}. */
    private void glide(String selector, double seconds) {
        int ticks = Math.max(1, (int) Math.round(seconds * TPS));
        double[] path = new double[4]; // fromX, fromY, toX, toY, fixed when the glide starts
        for (int t = 1; t <= ticks; t++) {
            int step = t;
            steps.add(() -> {
                if (step == 1) {
                    float[] r = rect(selector);
                    if (r == null) return;
                    path[0] = pointerX;
                    path[1] = pointerY;
                    path[2] = r[0] + r[2] / 2;
                    path[3] = r[1] + r[3] / 2;
                }
                double k = ease((double) step / ticks);
                move(path[0] + (path[2] - path[0]) * k, path[1] + (path[3] - path[1]) * k);
            });
        }
    }

    private void click(String selector) {
        steps.add(() -> {
            float[] r = rect(selector);
            if (r == null) return;
            move(r[0] + r[2] / 2, r[1] + r[3] / 2);
            button(true);
            button(false);
        });
    }

    /** Wheel notches over the centre of {@code selector}; negative scrolls down, as the window system reports it. */
    private void scroll(String selector, double notches) {
        steps.add(() -> {
            float[] r = rect(selector);
            if (r == null) return;
            move(r[0] + r[2] / 2, r[1] + r[3] / 2);
            mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, notches);
        });
    }

    // ---- Input and geometry ----

    /** The border box of the first match in GUI coordinates, or null (logged) when nothing matches. */
    private float[] rect(String selector) {
        Document document = mc.gui.screen() instanceof VellumScreen s ? s.driver().document()
                : mc.gui.screen() instanceof VellumContainerScreen<?> c ? c.driver().document() : null;
        Element element = document == null ? null : document.querySelector(selector);
        if (element == null || element.box == null) {
            Constants.LOG.warn("Vellum tour: nothing laid out matches {}", selector);
            return null;
        }
        return Coordinates.boundingRect(element.box);
    }

    /** Moves the pointer to GUI coordinates through the mouse handler, as the window system would. */
    private void move(double gx, double gy) {
        pointerX = gx;
        pointerY = gy;
        Window window = mc.getWindow();
        mc.mouseHandler.onMove(window.handle(), gx * window.getScreenWidth() / window.getGuiScaledWidth(),
                gy * window.getScreenHeight() / window.getGuiScaledHeight(), 0, 0);
    }

    private void button(boolean down) {
        mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0),
                down ? InputConstants.PRESS : InputConstants.RELEASE);
    }

    private static double ease(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }
}
