package dev.vellum.mod.client;

import com.google.gson.JsonParser;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Dev-only visual check ({@code ./gradlew :neoforge:runClient -Pautopilot} or {@code :fabric:runClient -Pautopilot},
 * i.e. {@code -Dvellum.autopilot=true}):
 * creates a superflat creative world, opens the canvas test, every showcase page and demo, screenshots each to
 * {@code runs/client/screenshots/vellum_<name>.png} at GUI scale 2 (the canvas test and the 3D pages also at 3),
 * logs the frame rate of each (and of benchmark pages of 3D content), and quits.
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
            // Uncapped frame rates, so the logged fps measure what the pages cost.
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
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
        awardKills(mc);
        command(mc, "weather clear 100000");
        guiScale(mc, 2);
        shoot(mc, "canvastest", () -> mc.gui.setScreen(new CanvasTestScreen()));
        guiScale(mc, 3);
        shoot(mc, "canvastest_gui3", () -> {});
        guiScale(mc, 2);
        for (String page : Stream.concat(Stream.of("index"), VellumClientCommands.SHOWCASE.stream()).toList()) {
            shoot(mc, "showcase_" + page, () -> VellumScreens.open(VellumClientCommands.showcaseUrl(page)));
        }
        // The 3D pages again at GUI scale 3, where models and entities are drawn at a different resolution, and the
        // turntables a second later, having turned.
        guiScale(mc, 3);
        shoot(mc, "showcase_models_gui3", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        shoot(mc, "showcase_models_gui3_later", () -> {});
        shoot(mc, "showcase_mobdex_gui3", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("mobdex"),
                JsonParser.parseString("{\"start\": {\"mob\": \"ghast\"}}")));
        guiScale(mc, 2);
        shoot(mc, "showcase_mobdex_unseen", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("mobdex"),
                JsonParser.parseString("{\"start\": {\"mob\": \"warden\"}}")));
        // Dragging the Turntable's big model (rotatable): it turns and tilts, and keeps turning when let go.
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        steps.add(() -> wait = SETTLE);
        drag(mc, 700, 250, 760, 270);
        shoot(mc, "showcase_models_dragged", () -> mc.gui.screen().mouseMoved(10, 10));
        // What 3D content costs: the logged fps of 48 spinning entities, models and items (2D, for comparison).
        for (String bench : List.of("entity type='minecraft:zombie'", "model block='minecraft:chest'", "item id='minecraft:chest'")) {
            String cell = "<" + bench + " style='width: 48px; height: 48px; animation: spin 4s linear infinite'></" + bench.split(" ")[0] + ">";
            String page = "<style>@keyframes spin { to { -mc-yaw: 360deg } } body { display: flex; flex-wrap: wrap }</style>"
                    + cell.repeat(48);
            shoot(mc, "bench_" + bench.split(" ")[0], () -> VellumScreens.openInline(page, null));
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
            Constants.LOG.info("Vellum autopilot: {} at {} fps", name, mc.getFps());
            Screenshot.grab(mc.gameDirectory, "vellum_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> Constants.LOG.info("Vellum autopilot: {}", msg.getString()));
            wait = 5;
        });
    }

    /** Drags with the left button between two GUI points on the open screen, a step a tick, as a player would. */
    private static void drag(Minecraft mc, double x0, double y0, double x1, double y1) {
        MouseButtonInfo left = new MouseButtonInfo(1, 0); // SDL's left button
        steps.add(() -> {
            mc.gui.screen().mouseMoved(x0, y0);
            mc.gui.screen().mouseClicked(new MouseButtonEvent(x0, y0, left), false);
        });
        int moves = 6;
        for (int i = 1; i <= moves; i++) {
            double x = x0 + (x1 - x0) * i / moves, y = y0 + (y1 - y0) * i / moves;
            steps.add(() -> mc.gui.screen().mouseMoved(x, y));
        }
        steps.add(() -> mc.gui.screen().mouseReleased(new MouseButtonEvent(x1, y1, left)));
    }

    private static void guiScale(Minecraft mc, int scale) {
        steps.add(() -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
            wait = 5;
        });
    }

    /** Kill statistics for the Mobdex: some mobs caught (defeated), some only seen (defeated by). */
    private static void awardKills(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) return;
            for (EntityType<?> type : List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER, EntityTypes.SPIDER,
                    EntityTypes.PIG, EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.CHICKEN, EntityTypes.SLIME, EntityTypes.SQUID)) {
                player.awardStat(Stats.ENTITY_KILLED.get(type), 3);
            }
            for (EntityType<?> type : List.of(EntityTypes.ENDERMAN, EntityTypes.WITCH, EntityTypes.GHAST, EntityTypes.BLAZE)) {
                player.awardStat(Stats.ENTITY_KILLED_BY.get(type), 1);
            }
        });
    }

    private static void command(Minecraft mc, String cmd) {
        if (mc.player != null) mc.player.connection.sendCommand(cmd);
    }
}
