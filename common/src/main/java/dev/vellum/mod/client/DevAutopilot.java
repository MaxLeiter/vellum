package dev.vellum.mod.client;

import com.google.gson.JsonParser;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Rotations;
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

/**
 * Dev-only visual check ({@code ./gradlew :neoforge:runClient -Pautopilot} or {@code :fabric:runClient -Pautopilot},
 * i.e. {@code -Dvellum.autopilot=true}):
 * creates a superflat creative world, opens the canvas test, every showcase page and demo, screenshots each to
 * {@code runs/client/screenshots/vellum_<name>.png} at GUI scale 2 (the canvas test and the 3D pages also at 3),
 * logs the frame rate of each (and of benchmark pages of 3D content), hovers the title screen's first button for a
 * burst of screenshots a tick apart (hover effects and animations), drives the templates demo with
 * {@link VellumAutomation} (clicks, typing, Enter) and checks its state, and quits. Armour stands in pages get a
 * render state of the autopilot's ({@link VellumEntities}: arms, one raised, and no base plate), shown on a page of
 * portraits.
 */
public final class DevAutopilot {
    public static final boolean ENABLED = Boolean.getBoolean("vellum.autopilot");
    /** Ticks to let a screen load, animate in and render before its screenshot. */
    private static final int SETTLE = 20;

    private static boolean started, loaded, finished;
    private static final Deque<Runnable> steps = new ArrayDeque<>();
    private static int wait;
    /** How many render states the autopilot's armour stand function made. */
    private static int standStates;

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
        VellumEntities.registerPortraitState(EntityTypes.ARMOR_STAND, (stand, partialTick) -> {
            if (!(mc.getEntityRenderDispatcher().getRenderer(stand).createRenderState(stand, partialTick)
                    instanceof ArmorStandRenderState state)) return null;
            standStates++;
            state.showArms = true;
            state.showBasePlate = false;
            state.rightArmPose = new Rotations(-150, 0, 15);
            return state;
        });
        command(mc, "gamerule send_command_feedback false");
        command(mc, "time set 6000");
        awardKills(mc);
        command(mc, "weather clear 100000");
        if (DevTour.ENABLED) {
            DevTour.plan(mc, steps, ticks -> wait = ticks);
            return;
        }
        guiScale(mc, 2);
        shoot(mc, "canvastest", () -> mc.gui.setScreen(new CanvasTestScreen()));
        guiScale(mc, 3);
        shoot(mc, "canvastest_gui3", () -> {});
        guiScale(mc, 2);
        shoot(mc, "showcase_index", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("index")));
        for (String page : VellumClientCommands.SHOWCASE) {
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
        drag(mc, "model[rotatable]", 60, 20);
        shoot(mc, "showcase_models_dragged", () -> mc.gui.screen().mouseMoved(10, 10));
        // Portraits: whole bodies and head-and-shoulders crops at a few sizes, and armour stands drawn from the
        // autopilot's render state (arms, one raised, no base plate). At GUI scale 3 too, where they are sharper.
        shoot(mc, "portraits", () -> VellumScreens.openInline(PORTRAITS, null));
        // The Turntable's portraits watching the pointer over the stage's caption, at both scales.
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        steps.add(() -> wait = SETTLE);
        hover(".stage p");
        grab(mc, "showcase_models_portraits", 5);
        steps.add(() -> {
            if (standStates > 0) Constants.LOG.info("Vellum autopilot: armour stands drawn from the registered render state");
            else Constants.LOG.error("Vellum autopilot: the registered armour stand render state was never used");
        });
        guiScale(mc, 3);
        shoot(mc, "portraits_gui3", () -> VellumScreens.openInline(PORTRAITS, null));
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        steps.add(() -> wait = SETTLE);
        hover(".stage p");
        grab(mc, "showcase_models_portraits_gui3", 5);
        guiScale(mc, 2);
        // What 3D content costs: the logged fps of 48 spinning entities, models and items (2D, for comparison).
        for (String bench : List.of("entity type='minecraft:zombie'", "model block='minecraft:chest'", "item id='minecraft:chest'")) {
            String tag = bench.split(" ")[0];
            String cell = "<" + bench + " style='width: 48px; height: 48px; animation: spin 4s linear infinite'></" + tag + ">";
            String page = "<style>@keyframes spin { to { -mc-yaw: 360deg } } body { display: flex; flex-wrap: wrap }</style>"
                    + cell.repeat(48);
            shoot(mc, "bench_" + tag, () -> VellumScreens.openInline(page, null));
        }
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("title")));
        steps.add(() -> wait = SETTLE);
        hover(".panel button");
        for (int i = 0; i < 4; i++) grab(mc, "showcase_title_hover_" + i, 0);
        for (String demo : VellumClientCommands.DEMOS) {
            if (!demo.equals("hud")) shoot(mc, demo, () -> VellumClientCommands.demo(demo));
        }
        steps.add(() -> VellumClientCommands.demo("templates"));
        steps.add(() -> wait = SETTLE);
        steps.add(() -> VellumAutomation.screen().ifPresent(page -> {
            page.click(".counter button:last-child");
            page.click(".counter button:last-child");
            page.click(".add input");
            page.type("Mine diamonds");
            page.key("Enter");
            String state = page.eval("[state.count, state.todos.length]").map(Object::toString).orElse("none");
            if (state.equals("[2,3]")) Constants.LOG.info("Vellum autopilot: templates demo input works");
            else Constants.LOG.error("Vellum autopilot: templates demo state is {}, expected [2,3]", state);
            wait = 2; // the page re-renders at its next frame
        }));
        grab(mc, "templates_input", 5);
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

    /**
     * Rows of entities fitted whole and with their eyes focused at 48 and 32 px, then boxes of other shapes with
     * object-position, -mc-pitch and -mc-model-scale.
     */
    private static final String PORTRAITS = """
            <style>
              html { background: #151a24 }
              body { margin: 0; padding: 6px; color: #8b96ad; display: flex; flex-direction: column; gap: 6px }
              .row { display: flex; gap: 4px; align-items: flex-end }
              .row > span { flex: none; width: 48px }
              entity { flex: none; background: linear-gradient(#26304a, #10141d); outline: 1px solid #34405a }
              .s48 entity { width: 48px; height: 48px }
              .s32 entity { width: 32px; height: 32px }
              .eyes entity, entity.eyes { -mc-entity-focus: eyes }
              .tall { width: 32px; height: 64px }
              .wide { width: 64px; height: 32px }
            </style>
            <div class="row s48"><span>body</span>ROW</div>
            <div class="row s48 eyes"><span>eyes</span>ROW</div>
            <div class="row s32 eyes"><span>eyes, 32</span>ROW</div>
            <div class="row"><span>placed</span>
              <entity player class="tall" style="object-position: top"></entity>
              <entity type="minecraft:pig" class="tall" style="object-position: top"></entity>
              <entity type="minecraft:armor_stand" class="tall" style="object-position: center; -mc-pitch: 25deg"></entity>
              <entity player class="tall eyes" follow-mouse></entity>
              <entity type="minecraft:villager" class="wide eyes" follow-mouse></entity>
              <entity type="minecraft:zombie" class="wide eyes" style="object-position: 30% 60%"></entity>
              <entity type="minecraft:iron_golem" class="tall eyes" style="-mc-model-scale: 0.6"></entity>
              <entity type="minecraft:armor_stand" class="tall eyes" style="-mc-pitch: 30deg"></entity>
            </div>
            """.replace("ROW", """
            <entity player follow-mouse></entity><entity type="minecraft:zombie" follow-mouse></entity>\
            <entity type="minecraft:villager" follow-mouse></entity><entity type="minecraft:zombie" baby follow-mouse></entity>\
            <entity type="minecraft:iron_golem"></entity><entity type="minecraft:pig" style="-mc-yaw: 30deg"></entity>\
            <entity type="minecraft:fox"></entity><entity type="minecraft:ghast"></entity>\
            <entity type="minecraft:armor_stand" head="minecraft:golden_helmet"></entity>""");

    /** Runs {@code open}, lets it settle, and saves a screenshot named {@code vellum_<name>.png}. */
    private static void shoot(Minecraft mc, String name, Runnable open) {
        steps.add(open);
        steps.add(() -> wait = SETTLE);
        grab(mc, name, 5);
    }

    /** Saves a screenshot of the last frame as {@code vellum_<name>.png}, then waits {@code ticks}. */
    private static void grab(Minecraft mc, String name, int ticks) {
        steps.add(() -> {
            mc.gui.hud.getChat().clearMessages(false);
            Constants.LOG.info("Vellum autopilot: {} at {} fps", name, mc.getFps());
            Screenshot.grab(mc.gameDirectory, "vellum_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> Constants.LOG.info("Vellum autopilot: {}", msg.getString()));
            wait = ticks;
        });
    }

    /** Hovers the first element of the open Vellum screen matching {@code selector} and lets hover effects settle. */
    private static void hover(String selector) {
        steps.add(() -> {
            if (!VellumAutomation.screen().map(page -> page.hover(selector)).orElse(false)) {
                Constants.LOG.warn("Vellum autopilot: nothing to hover at {}", selector);
            }
            wait = SETTLE;
        });
    }

    /**
     * Drags with the left button from the centre of the open page's first {@code selector} element by (dx, dy) GUI px,
     * a step a tick, as a player would.
     */
    private static void drag(Minecraft mc, String selector, double dx, double dy) {
        MouseButtonInfo left = new MouseButtonInfo(1, 0); // SDL's left button
        double[] from = new double[2];
        steps.add(() -> {
            Document doc = mc.gui.screen() instanceof VellumScreen screen ? screen.driver().document() : null;
            Element target = doc == null ? null : doc.querySelector(selector);
            if (target == null) {
                Constants.LOG.warn("Vellum autopilot: no {} to drag", selector);
                return;
            }
            float[] box = target.getBoundingClientRect();
            from[0] = box[0] + box[2] / 2;
            from[1] = box[1] + box[3] / 2;
            mc.gui.screen().mouseMoved(from[0], from[1]);
            mc.gui.screen().mouseClicked(new MouseButtonEvent(from[0], from[1], left), false);
        });
        int moves = 6;
        for (int i = 1; i <= moves; i++) {
            double t = (double) i / moves;
            steps.add(() -> mc.gui.screen().mouseMoved(from[0] + dx * t, from[1] + dy * t));
        }
        steps.add(() -> mc.gui.screen().mouseReleased(new MouseButtonEvent(from[0] + dx, from[1] + dy, left)));
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
