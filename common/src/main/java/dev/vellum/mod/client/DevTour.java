package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.Window;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityTypes;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Dev-only scripted tour of the showcase for demo videos ({@code -Ptour}, which also sets {@code vellum.autopilot}).
 * The autopilot creates the world, then this plan drives the pages as a person would: the pointer glides along gentle
 * arcs with eased timing, frame by frame ({@link TourPointer}), presses and holds its clicks, types at a human pace and
 * turns the wheel, all through Minecraft's own mouse and keyboard handlers, so hover effects, clicks, sounds and
 * scripts behave as they do for a player. A cursor is drawn in game ({@link TourCursor}), since recordings don't
 * capture the system's. With {@code vellum.tour.go=<file>} it waits for that file, which tools/record creates once
 * capture has started; with {@code vellum.tour.shots} it saves a screenshot at each beat, without changing the timing.
 * It logs when it starts and finishes and when each beat begins, and the client quits at the end.
 *
 * <p>The plan is a timeline: each action is due a set time into the tour, and the client tick runs the ones that are
 * due, so the length is what the plan adds up to however many actions there are. Gates (a page settling, the
 * recorder starting) hold the rest of the timeline back by as long as they wait.
 *
 * <p>The storyboard, about 50 s: the title screen's parallax and buttons; the Mobdex (a search, a walking polar bear
 * cub, a scroll and a ghast); the Turntable (a flick with momentum, a tilt, then the portraits watching the pointer);
 * the trader (tooltips, the satchel, a purchase); and an end card ({@code dev/endcard.html}).
 */
final class DevTour {
    static final boolean ENABLED = Boolean.getBoolean("vellum.tour");
    private static final String GO = System.getProperty("vellum.tour.go");
    private static final boolean SHOTS = Boolean.getBoolean("vellum.tour.shots");
    /** The music's volume; the pages' sounds play over it at their own. */
    private static final float MUSIC_VOLUME = 0.35f;
    /** How long a click holds the button down, so the cursor's dip and the page's {@code :active} style show. */
    private static final double CLICK_HOLD = 0.12;
    /** The most ticks a beat waits for its page to settle. */
    private static final int MAX_SETTLE = 50;

    /** One thing the tour does, {@code at} seconds into the plan; or a gate, which holds the rest until it opens. */
    private record Step(double at, @Nullable Runnable action, @Nullable Gate gate) {}

    /** Asked once a tick, with the number of ticks asked so far, until it opens. */
    @FunctionalInterface
    private interface Gate {
        boolean open(int ticks);
    }

    private final Minecraft mc;
    private final List<Step> timeline = new ArrayList<>();
    /** Where the plan is, in seconds, while it is being made. */
    private double planned;
    /** The next step to run, and the real time (ms) the plan's 0 s falls at, pushed back by gates as they wait. */
    private int next;
    private long base;
    private int gateTicks;
    private long startedAt;
    private int shots;
    private @Nullable Music music;

    private DevTour(Minecraft mc) {
        this.mc = mc;
    }

    /** Queues the tour after the autopilot has loaded the world: one step that runs the timeline, a tick at a time. */
    static void plan(Minecraft mc, Deque<Runnable> steps) {
        DevTour tour = new DevTour(mc);
        tour.plan();
        steps.add(new Runnable() {
            @Override
            public void run() {
                if (tour.tick()) steps.addFirst(this);
            }
        });
    }

    private void plan() {
        at(() -> {
            // The largest scale that leaves 500 GUI px of width: the showcase pages are about 470 wide, and auto
            // (the largest that fits 320x240) squeezes them into 427x240 at 1280x720 on a retina screen.
            mc.options.guiScale().set(Math.max(1, mc.getWindow().getWidth() / 500));
            mc.resizeGui();
            mc.getMusicManager().stopPlaying();
            seePolarBear();
        });
        if (GO != null) waitForRecorder(Path.of(GO));
        at(() -> {
            startedAt = Util.getMillis();
            Constants.LOG.info("Vellum tour started");
            TourCursor.start();
            TourCursor.fade(0, 0);
            music = new Music();
            mc.getSoundManager().play(music);
        });
        pause(0.3);
        title();
        mobdex();
        turntable();
        shop();
        endCard();
        at(() -> {
            Constants.LOG.info("Vellum tour finished after {} s", seconds());
            TourCursor.stop();
        });
    }

    /** Runs the steps that are due. False once the timeline is done. */
    private boolean tick() {
        long now = Util.getMillis();
        if (base == 0) base = now;
        while (next < timeline.size()) {
            Step step = timeline.get(next);
            long due = base + Math.round(step.at * 1000);
            if (now < due) break;
            if (step.gate != null) {
                if (!step.gate.open(++gateTicks)) break;
                gateTicks = 0;
                base += now - due; // everything after it moves back by as long as it waited
            } else {
                try {
                    step.action.run();
                } catch (RuntimeException e) {
                    Constants.LOG.error("Vellum tour: a step failed", e);
                }
            }
            next++;
        }
        return next < timeline.size();
    }

    // ---- Beats ----

    /**
     * The title screen fades up from black; the cursor drifts in from the right and the landscape's ranges shift with
     * it, then it runs down the menu (each button slides and glows, with a click) and picks Singleplayer.
     */
    private void title() {
        beat("title");
        open(VellumClientCommands.showcaseUrl("title"));
        place(0.8, 0.72);
        pause(1.1);
        at(() -> TourCursor.fade(1, 0.5));
        glideTo(page -> fraction(0.55, 0.42), 1.1, TourPointer.Ease.IN_OUT);
        pause(0.2);
        glide(".panel > button:nth-child(1)", 0.8);
        pause(0.7);
        shot("title_hand");
        glide(".panel > button:nth-child(2)", 0.4);
        pause(0.4);
        glide(".panel > button:nth-child(3)", 0.4);
        pause(0.4);
        glide(".row button:nth-child(2)", 0.45);
        pause(0.45);
        glide(".panel > button:nth-child(1)", 0.75);
        pause(0.35);
        at(TourPointer::press);
        pause(0.2);
        shot("title_press");
        // Singleplayer closes the page: the Mobdex opens in the same tick, so no frame shows the world.
        at(() -> {
            TourPointer.release();
            openNow(VellumClientCommands.showcaseUrl("mobdex"));
        });
    }

    /**
     * The Mobdex: search for the polar bear (it is picked as the name narrows the list), walk it and make it a cub, let
     * it watch the cursor, then clear the search, scroll the list and pick the ghast.
     */
    private void mobdex() {
        beat("mobdex");
        settle(0.5);
        glide(".search input", 0.8);
        pause(0.15);
        shot("mobdex_ibeam");
        click();
        pause(0.2);
        type("polar", 0.13);
        pause(0.8);
        shot("mobdex_search");
        glide(".keys .key:nth-child(4)", 0.75); // WALK
        pause(0.15);
        click();
        pause(0.55);
        glide(".keys .key:nth-child(3)", 0.35); // BABY
        pause(0.12);
        click();
        pause(0.6);
        shot("mobdex_cub");
        // Over the stage the cub turns its head to the cursor.
        glideTo(page -> rectPoint(page, ".stage", 0.3, 0.35), 0.6, TourPointer.Ease.IN_OUT);
        glideTo(page -> rectPoint(page, ".stage", 0.75, 0.6), 1.0, TourPointer.Ease.SWEEP);
        pause(0.2);
        glide(".search input", 0.65);
        pause(0.12);
        click();
        pause(0.1);
        for (int i = 0; i < 5; i++) {
            key("Backspace");
            pause(0.09);
        }
        pause(0.3);
        glideTo(page -> rectPoint(page, "#list", 0.5, 0.55), 0.55, TourPointer.Ease.IN_OUT);
        for (int i = 0; i < 2; i++) {
            wheel(1);
            pause(0.25);
        }
        pause(0.15);
        glideToRow("minecraft:ghast", 0.5);
        pause(0.15);
        click();
        pause(1.6);
        shot("mobdex_ghast");
    }

    /**
     * The Turntable: the big model swings round under the cursor; a flick sends it spinning, a slower drag tilts it;
     * then the cursor sweeps along the portraits and back above them, and every head follows it.
     */
    private void turntable() {
        beat("turntable");
        open(VellumClientCommands.showcaseUrl("models"));
        settle(0.5);
        glide(".stage model", 0.85);
        pause(0.9);
        shot("models_grab");
        // A flick: released while still moving, so the model keeps spinning and slows down by itself.
        drag(50, 3, 0.35, TourPointer.Ease.IN);
        pause(1.4);
        drag(-40, 14, 0.7, TourPointer.Ease.IN_OUT);
        shot("models_tilt");
        pause(0.4);
        glideTo(page -> rectPoint(page, ".faces", 0.02, 0.15), 0.85, TourPointer.Ease.IN_OUT);
        pause(0.15);
        glideTo(page -> rectPoint(page, ".faces", 0.98, 0.15), 2.2, TourPointer.Ease.SWEEP);
        shot("models_portraits");
        // Back again from above: the heads look up and follow it home.
        glideTo(page -> rectPoint(page, ".faces", 0.15, -0.8), 1.6, TourPointer.Ease.SWEEP);
        pause(0.4);
    }

    /**
     * The trader: two legendary wares' tooltips, the satchel trimmed with its stepper, two wares added (the total
     * counts up), and the purchase with its confetti.
     */
    private void shop() {
        beat("shop");
        open(VellumClientCommands.showcaseUrl("shop"));
        settle(0.5);
        glide(".wares .card:nth-child(1) item", 0.85);
        pause(1.1);
        shot("shop_tooltip");
        glide(".wares .card:nth-child(3) item", 0.45);
        pause(1.0);
        // Both golden apples out of the satchel: a click on its minus each.
        glide(".lines .line:nth-child(1) .stepper button:first-child", 0.85);
        pause(0.15);
        click();
        pause(0.3);
        click();
        pause(0.4);
        glide(".wares .card:nth-child(3)", 0.7);
        pause(0.12);
        click();
        pause(0.5);
        glide(".wares .card:nth-child(6)", 0.5);
        pause(0.12);
        click();
        pause(0.55);
        glide("#buy", 0.7);
        pause(0.2);
        click();
        pause(0.5);
        shot("shop_purchase");
        pause(1.6);
    }

    /** The end card: the cursor slips away to the corner and fades while the logo and lines rise in. */
    private void endCard() {
        beat("end card");
        open("vellum:vellum/dev/endcard.html");
        glideTo(page -> fraction(0.93, 0.9), 1.0, TourPointer.Ease.IN_OUT);
        at(() -> TourCursor.fade(0, 0.6));
        pause(1.6);
        at(() -> {
            if (music != null) music.fadeOut(2.4);
        });
        pause(1.0);
        shot("endcard");
        pause(1.4);
    }

    // ---- Steps ----

    /** Runs {@code action} where the plan is now. */
    private void at(Runnable action) {
        timeline.add(new Step(planned, action, null));
    }

    /** Holds the rest of the plan until {@code gate} opens. */
    private void gate(Gate gate) {
        timeline.add(new Step(planned, null, gate));
    }

    private void pause(double seconds) {
        planned += seconds;
    }

    /** Logs the beat about to start, with the time since the tour started. */
    private void beat(String name) {
        at(() -> Constants.LOG.info("Vellum tour: {} at {} s", name, seconds()));
    }

    private void open(String url) {
        at(() -> openNow(url));
    }

    /** Opens a page and puts the pointer back where the tour has it (a screen opening moves it to the system's). */
    private void openNow(String url) {
        VellumScreens.open(url);
        TourPointer.apply();
    }

    /**
     * Waits {@code minSeconds}, then until the open page has settled ({@link VellumAutomation#settled}: loaded, its
     * entrance transitions done), for at most {@link #MAX_SETTLE} ticks.
     */
    private void settle(double minSeconds) {
        pause(minSeconds);
        gate(ticks -> {
            if (VellumAutomation.screen().map(VellumAutomation::settled).orElse(false)) return true;
            if (ticks < MAX_SETTLE) return false;
            Constants.LOG.info("Vellum tour: the page is still changing after {} ticks, going on", ticks);
            return true;
        });
    }

    /** Holds the tour until {@code go} exists: tools/record creates it once it is capturing. A stale one is deleted first. */
    private void waitForRecorder(Path go) {
        at(() -> {
            try {
                Files.deleteIfExists(go);
            } catch (IOException e) {
                Constants.LOG.warn("Vellum tour: can't delete the old {}", go, e);
            }
            Constants.LOG.info("Vellum tour: waiting for the recorder to create {}", go);
        });
        gate(ticks -> Files.exists(go));
    }

    /** Puts the pointer at a fraction of the window's width and height. */
    private void place(double fx, double fy) {
        at(() -> {
            double[] to = fraction(fx, fy);
            TourPointer.place(to[0], to[1]);
        });
    }

    /**
     * Glides the pointer onto the first element matching {@code selector}, where {@link VellumAutomation#hover}
     * would put it, over {@code seconds}.
     */
    private void glide(String selector, double seconds) {
        glideTo(page -> page.pointerTarget(selector).map(to -> new double[] {to[0], to[1]}).orElseGet(() -> {
            Constants.LOG.warn("Vellum tour: the pointer can't reach {}", selector);
            return null;
        }), seconds, TourPointer.Ease.IN_OUT);
    }

    /** Glides the pointer to where {@code target} says on the open page (worked out when the glide starts). */
    private void glideTo(Function<VellumAutomation, double @Nullable []> target, double seconds, TourPointer.Ease ease) {
        at(() -> {
            double[] to = VellumAutomation.screen().map(target).orElse(null);
            if (to != null) TourPointer.glide(to[0], to[1], seconds, TourPointer.BEND, ease, null);
        });
        pause(seconds);
    }

    /** Glides onto the Mobdex row of mob {@code id}. */
    private void glideToRow(String id, double seconds) {
        glideTo(page -> page.eval("BY_ID['" + id + "'].no").map(JsonElement::getAsInt)
                .flatMap(no -> page.pointerTarget("#row-" + no)).map(to -> new double[] {to[0], to[1]}).orElse(null),
                seconds, TourPointer.Ease.IN_OUT);
    }

    /** Presses, holds and releases the left button where the pointer is. */
    private void click() {
        at(TourPointer::press);
        pause(CLICK_HOLD);
        at(TourPointer::release);
    }

    /**
     * Drags with the left button from where the pointer is by (dx, dy) GUI px over {@code seconds}, timed by
     * {@code ease}, and lets go in the frame it arrives: still moving with {@link TourPointer.Ease#IN}, so a turntable
     * keeps spinning.
     */
    private void drag(double dx, double dy, double seconds, TourPointer.Ease ease) {
        at(() -> {
            TourPointer.press();
            TourPointer.glide(TourPointer.x() + dx, TourPointer.y() + dy, seconds, TourPointer.BEND / 2, ease, TourPointer::release);
        });
        pause(seconds);
    }

    /** Types {@code text} into whatever has focus, a character every {@code interval} seconds. */
    private void type(String text, double interval) {
        text.codePoints().forEach(cp -> {
            at(() -> VellumAutomation.screen().ifPresent(page -> page.type(Character.toString(cp))));
            pause(interval);
        });
    }

    private void key(String domKey) {
        at(() -> VellumAutomation.screen().ifPresent(page -> page.key(domKey)));
    }

    /** Turns the wheel where the pointer is; positive notches scroll down. */
    private void wheel(double notches) {
        at(() -> TourPointer.wheel(notches));
    }

    /** With {@code vellum.tour.shots}: saves the last frame as {@code tour_<n>_<name>.png} in the screenshots folder. */
    private void shot(String name) {
        if (!SHOTS) return;
        at(() -> {
            String file = String.format(Locale.ROOT, "tour_%02d_%s.png", ++shots, name);
            Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> Constants.LOG.info("Vellum tour: {} at {} s", msg.getString(), seconds()));
        });
    }

    // ---- Geometry ----

    /** A point at a fraction of the window's width and height, in GUI px. */
    private double[] fraction(double fx, double fy) {
        Window w = mc.getWindow();
        return new double[] {w.getGuiScaledWidth() * fx, w.getGuiScaledHeight() * fy};
    }

    /** A point at a fraction of the first element matching {@code selector}'s box (outside it below 0 or above 1). */
    private static double @Nullable [] rectPoint(VellumAutomation page, String selector, double fx, double fy) {
        return page.rect(selector).map(r -> new double[] {r[0] + r[2] * fx, r[1] + r[3] * fy}).orElseGet(() -> {
            Constants.LOG.warn("Vellum tour: no box for {}", selector);
            return null;
        });
    }

    private String seconds() {
        return String.format(Locale.ROOT, "%.2f", (Util.getMillis() - startedAt) / 1000.0);
    }

    // ---- World ----

    /** A polar bear seen (it defeated the player once), for the Mobdex: logged, but not caught, so no stamp. */
    private void seePolarBear() {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.awardStat(Stats.ENTITY_KILLED_BY.get(EntityTypes.POLAR_BEAR), 1);
        });
    }

    /** The music under the tour: a music disc at {@link #MUSIC_VOLUME}, which can fade out at the end. */
    private static final class Music extends AbstractTickableSoundInstance {
        /** How much quieter each tick makes it while it fades; 0 until then. */
        private float step;

        Music() {
            super(SoundEvents.MUSIC_DISC_CREATOR.value(), SoundSource.UI, SoundInstance.createUnseededRandom());
            volume = MUSIC_VOLUME;
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
        }

        /** Fades to silence over {@code seconds}, then stops. */
        void fadeOut(double seconds) {
            step = (float) (MUSIC_VOLUME / (seconds * 20));
        }

        @Override
        public void tick() {
            if (step == 0) return;
            volume = Math.max(0, volume - step);
            if (volume == 0) stop();
        }
    }
}
