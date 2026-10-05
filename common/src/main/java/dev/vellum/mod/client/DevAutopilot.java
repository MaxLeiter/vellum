package dev.vellum.mod.client;

import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Dev-only visual check ({@code ./gradlew :neoforge:runClient -Pautopilot} or {@code :fabric:runClient -Pautopilot},
 * i.e. {@code -Dvellum.autopilot=true}):
 * creates a superflat creative world, opens the canvas test and every demo, screenshots each to
 * {@code runs/client/screenshots/vellum_<name>.png} at GUI scale 2 (the canvas test also at 3), and quits.
 */
public final class DevAutopilot {
    public static final boolean ENABLED = Boolean.getBoolean("vellum.autopilot");
    /** Ticks to let a screen load, animate in and render before its screenshot. */
    private static final int SETTLE = 20;

    private static boolean started, loaded, finished;
    private static final Deque<Runnable> steps = new ArrayDeque<>();
    private static int wait;

    private DevAutopilot() {}

    /** Client tick hook; the loaders call this every client tick when {@link #ENABLED}. */
    public static void tick(Minecraft mc) {
        if (!ENABLED || finished) return;
        if (!started) {
            // A fresh run directory first shows the accessibility onboarding screen instead of the title screen.
            if (!(mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof AccessibilityOnboardingScreen)) return;
            started = true;
            mc.options.onboardingAccessibilityFinished();
            // The window may never have focus while the autopilot runs; don't pause or show tutorial toasts.
            mc.options.pauseOnLostFocus = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            String id = "vellum-autopilot";
            LevelSettings settings = new LevelSettings(id, GameType.CREATIVE,
                    new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(id, settings, new WorldOptions(0, false, false),
                    WorldPresets::createTestWorldDimensions, mc.gui.screen());
            return;
        }
        if (!loaded) {
            if (mc.player == null || mc.level == null) return;
            loaded = true;
            plan(mc);
            wait = 60;
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Runnable step = steps.poll();
        if (step == null) {
            finished = true;
            Constants.LOG.info("Vellum autopilot finished");
            mc.stop();
            return;
        }
        try {
            step.run();
        } catch (RuntimeException e) {
            Constants.LOG.error("Vellum autopilot step failed", e);
        }
    }

    private static void plan(Minecraft mc) {
        command(mc, "gamerule send_command_feedback false");
        command(mc, "time set 6000");
        command(mc, "weather clear 100000");
        guiScale(mc, 2);
        shoot(mc, "canvastest", () -> mc.gui.setScreen(new CanvasTestScreen()));
        guiScale(mc, 3);
        shoot(mc, "canvastest_gui3", () -> {});
        guiScale(mc, 2);
        for (String page : VellumClientCommands.SHOWCASE) {
            shoot(mc, "showcase_" + page, () -> VellumScreens.open(VellumClientCommands.showcaseUrl(page)));
        }
        for (String demo : VellumClientCommands.DEMOS) {
            if (!demo.equals("hud")) shoot(mc, demo, () -> VellumClientCommands.demo(demo));
        }
        shoot(mc, "chest", () -> {
            mc.gui.setScreen(null);
            command(mc, "vellum demo chest");
        });
        shoot(mc, "hud", () -> {
            mc.gui.setScreen(null);
            VellumHud.show(VellumClient.DEMO_HUD);
        });
        steps.add(() -> VellumHud.hide(VellumClient.DEMO_HUD));
    }

    /** Runs {@code open}, lets it settle, and saves a screenshot named {@code vellum_<name>.png}. */
    private static void shoot(Minecraft mc, String name, Runnable open) {
        steps.add(open);
        steps.add(() -> wait = SETTLE);
        steps.add(() -> {
            mc.gui.hud.getChat().clearMessages(false);
            Screenshot.grab(mc.gameDirectory, "vellum_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> Constants.LOG.info("Vellum autopilot: {}", msg.getString()));
            wait = 5;
        });
    }

    private static void guiScale(Minecraft mc, int scale) {
        steps.add(() -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
            wait = 5;
        });
    }

    private static void command(Minecraft mc, String cmd) {
        if (mc.player != null) mc.player.connection.sendCommand(cmd);
    }
}
