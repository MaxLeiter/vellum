package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ChatScreen;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Dev-only visual check ({@code ./gradlew :neoforge:runClient -Pautopilot} or {@code :fabric:runClient -Pautopilot},
 * i.e. {@code -Dvellum.autopilot=true}):
 * creates a superflat creative world, opens the canvas test, every showcase page and demo, screenshots each to
 * {@code runs/client/screenshots/vellum_<name>.png} at GUI scale 2 (the canvas test and the 3D pages also at 3),
 * logs the frame rate of each (and of benchmark pages of 3D content), and quits. It drives pages with
 * {@link VellumAutomation}: hovers the title screen's first button for a burst of screenshots a tick apart (hover
 * effects and animations), fills in the templates demo (clicks, typing, Enter), clicks a Mobdex row scrolled out of
 * its list and scrolls the list back, and answers the demo toast overlay through chat; it checks each result, and
 * waits for pages to settle rather than for a fixed time. Armour stands in pages get a render state of the
 * autopilot's ({@link VellumEntities}: arms, one raised, and no base plate), shown on a page of portraits; a
 * conversation card compares soft and default gazes at a pointer resting far below the speakers.
 */
public final class DevAutopilot {
    public static final boolean ENABLED = Boolean.getBoolean("vellum.autopilot");
    /**
     * The fewest ticks before a page's screenshot, so the frame rate logged with it (counted over a second) is the
     * page's own; and how long to wait for a screen that is not a Vellum page.
     */
    private static final int SETTLE = 20;
    /** The most ticks to wait for a condition ({@link #until}) before going on without it. */
    private static final int MAX_POLL = 200;
    /**
     * The most ticks to wait for a page to settle. Some never do: the showcase HUD's timers keep starting transitions
     * and animations, as a game's HUD would.
     */
    private static final int MAX_SETTLE = 60;
    /** The demo toast's Allow button. */
    private static final String ALLOW = ".actions button:first-child";

    private static boolean started, loaded, finished;
    private static final Deque<Runnable> steps = new ArrayDeque<>();
    private static int wait;
    /** How many render states the autopilot's armour stand function made. */
    private static int standStates;
    /**
     * What the steps wait for, polled once a tick: what it means when it holds, what to log instead after
     * {@link #untilMax} ticks (and whether that is a warning), and the ticks polled so far.
     */
    private static @Nullable BooleanSupplier until;
    private static String untilWhat = "", untilGaveUp = "";
    private static boolean untilWarns;
    private static int polled, untilMax;

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
        if (until != null && !poll()) return;
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

    /** Polls {@link #until}: true once it holds or the autopilot gave up on it. */
    private static boolean poll() {
        polled++;
        boolean done;
        try {
            done = until.getAsBoolean();
        } catch (RuntimeException e) {
            Constants.LOG.error("Vellum autopilot: waiting until {} failed", untilWhat, e);
            done = true;
        }
        if (!done && polled < untilMax) return false;
        if (done) Constants.LOG.info("Vellum autopilot: {} ({} ticks)", untilWhat, polled);
        else if (untilWarns) Constants.LOG.warn("Vellum autopilot: {} after {} ticks", untilGaveUp, polled);
        else Constants.LOG.info("Vellum autopilot: {} after {} ticks", untilGaveUp, polled);
        until = null;
        return true;
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
        mobdexList(mc);
        // Dragging the Turntable's big model (rotatable): it turns and tilts, and keeps turning when let go.
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        settle("the models page", VellumAutomation::screen, 0);
        drag(mc, "model[rotatable]", 60, 20);
        shoot(mc, "showcase_models_dragged", () -> VellumAutomation.screen().ifPresent(VellumAutomation::leave));
        // Portraits: whole bodies and head-and-shoulders crops at a few sizes, and armour stands drawn from the
        // autopilot's render state (arms, one raised, no base plate). At GUI scale 3 too, where they are sharper.
        shoot(mc, "portraits", () -> VellumScreens.openInline(PORTRAITS, null));
        conversation(mc, "portraits_conversation");
        // The Turntable's portraits watching the pointer over the stage's caption, at both scales.
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        settle("the models page", VellumAutomation::screen, 0);
        hover(".stage p");
        grab(mc, "showcase_models_portraits", 5);
        steps.add(() -> {
            if (standStates > 0) Constants.LOG.info("Vellum autopilot: armour stands drawn from the registered render state");
            else Constants.LOG.error("Vellum autopilot: the registered armour stand render state was never used");
        });
        guiScale(mc, 3);
        shoot(mc, "portraits_gui3", () -> VellumScreens.openInline(PORTRAITS, null));
        conversation(mc, "portraits_conversation_gui3");
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        settle("the models page", VellumAutomation::screen, 0);
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
        settle("the title screen", VellumAutomation::screen, 0);
        hover(".panel button");
        for (int i = 0; i < 4; i++) grab(mc, "showcase_title_hover_" + i, 0);
        steps.add(() -> VellumAutomation.screen().ifPresent(VellumAutomation::leave)); // or the next pages open hovered
        for (String demo : VellumClientCommands.DEMOS) {
            if (!demo.equals("hud")) shoot(mc, demo, () -> VellumClientCommands.demo(demo));
        }
        steps.add(() -> VellumClientCommands.demo("templates"));
        settle("the templates demo", VellumAutomation::screen, 0);
        steps.add(() -> VellumAutomation.screen().ifPresent(page -> {
            page.click(".counter button:last-child");
            page.click(".counter button:last-child");
            page.click(".add input");
            page.type("Mine diamonds");
            page.key("Enter");
            page.leave(); // no hover or tooltip in the screenshot
            String state = page.eval("[state.count, state.todos.length]").map(Object::toString).orElse("none");
            if (state.equals("[2,3]")) Constants.LOG.info("Vellum autopilot: templates demo input works");
            else Constants.LOG.error("Vellum autopilot: templates demo state is {}, expected [2,3]", state);
        }));
        settle("the filled-in templates demo", VellumAutomation::screen, 0);
        grab(mc, "templates_input", 5);
        shoot(mc, "chest", () -> {
            mc.gui.setScreen(null);
            command(mc, "vellum demo chest");
        });
        // The demo HUD's card fades in and out over four seconds, then closes: shoot it a second in, not settled.
        steps.add(() -> {
            mc.gui.setScreen(null);
            VellumHud.show(VellumClient.DEMO_HUD);
        });
        steps.add(() -> wait = SETTLE);
        grab(mc, "hud", 5);
        steps.add(() -> VellumHud.hide(VellumClient.DEMO_HUD));
        toast(mc);
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


    /**
     * A conversation card: speakers above, the reply far below them. The two on the left gaze softly
     * ({@code -mc-gaze-reach} and {@code -mc-gaze-limit}, as Chronicle's speakers do), the two on the right as the
     * inventory's player does; mirrored, so with the pointer on the reply each pair turns by the same geometry.
     */
    private static final String CONVERSATION = """
            <style>
              html { background: #151a24 }
              body { margin: 0; padding: 8px; color: #8b96ad; display: flex; justify-content: center }
              .card { width: 220px; padding: 6px; display: flex; flex-direction: column; align-items: center; gap: 6px;
                      background: #1d2434; outline: 1px solid #34405a }
              .speakers { display: flex; gap: 6px; align-items: flex-end }
              figure { margin: 0; display: flex; flex-direction: column; align-items: center; gap: 2px }
              entity { background: linear-gradient(#26304a, #10141d); outline: 1px solid #34405a }
              .eyes { width: 56px; height: 56px; -mc-entity-focus: eyes }
              .body { width: 36px; height: 72px }
              .soft entity { -mc-gaze-reach: 80px; -mc-gaze-limit: 30deg 9deg }
              p { margin: 0 0 64px; color: #e8f0ff; text-align: center }
            </style>
            <div class="card">
              <div class="speakers">
                <figure class="soft"><entity class="body" player follow-mouse></entity>soft</figure>
                <figure class="soft"><entity class="eyes" type="minecraft:villager" follow-mouse></entity>soft</figure>
                <figure><entity class="eyes" type="minecraft:villager" follow-mouse></entity>default</figure>
                <figure><entity class="body" player follow-mouse></entity>default</figure>
              </div>
              <p>Well met, traveller. The harvest was thin this year, and the roads are worse.</p>
              <button>Farewell</button>
            </div>
            """;

    /**
     * Opens {@link #CONVERSATION}, rests the pointer on its reply, checks the soft speakers' computed gaze and
     * screenshots it as {@code vellum_<name>.png}.
     */
    private static void conversation(Minecraft mc, String name) {
        steps.add(() -> VellumScreens.openInline(CONVERSATION, null));
        settle(name, VellumAutomation::screen, SETTLE);
        hover(".card button");
        steps.add(() -> VellumAutomation.screen().ifPresent(page -> {
            String gaze = page.eval("var s = getComputedStyle(document.querySelector('.soft entity'));"
                    + " s.getPropertyValue('-mc-gaze-reach') + ' / ' + s.getPropertyValue('-mc-gaze-limit')")
                    .map(JsonElement::getAsString).orElse("none");
            if (gaze.equals("80px / 30deg 9deg")) Constants.LOG.info("Vellum autopilot: soft gaze computed as {}", gaze);
            else Constants.LOG.error("Vellum autopilot: the soft gaze computed as {}, expected 80px / 30deg 9deg", gaze);
        }));
        grab(mc, name, 5);
    }

    /**
     * In the open Mobdex: clicks the list's last row, which is scrolled out of the list, so the click has to scroll it
     * into view to land; then turns the wheel over the list and scrolls its first row back into view.
     */
    private static void mobdexList(Minecraft mc) {
        String last = "#list .row:last-child";
        steps.add(() -> VellumAutomation.screen().ifPresent(page -> {
            float[] list = page.rect("#list").orElse(null), row = page.rect(last).orElse(null);
            boolean hidden = list != null && row != null && row[1] >= list[1] + list[3];
            boolean clicked = page.click(last);
            page.leave();
            boolean selected = page.eval("state.sel === filtered()[filtered().length - 1].id")
                    .map(JsonElement::getAsBoolean).orElse(false);
            if (hidden && clicked && selected) Constants.LOG.info("Vellum autopilot: clicking a scrolled-out row works");
            else Constants.LOG.error("Vellum autopilot: Mobdex row click: scrolled out {}, clicked {}, selected {}",
                    hidden, clicked, selected);
        }));
        settle("the Mobdex selection", VellumAutomation::screen, 0);
        grab(mc, "showcase_mobdex_last", 0);
        steps.add(() -> VellumAutomation.screen().ifPresent(page -> {
            if (!page.wheel("#list", -2)) Constants.LOG.error("Vellum autopilot: no wheel over the Mobdex list");
            page.leave();
        }));
        settle("the Mobdex list's scroll", VellumAutomation::screen, 0);
        steps.add(() -> VellumAutomation.screen().ifPresent(page -> {
            double wheeled = scrollTop(page);
            boolean shown = page.scrollIntoView("#list .row:first-child");
            double top = scrollTop(page);
            if (wheeled < listMax(page) && shown && top == 0) {
                Constants.LOG.info("Vellum autopilot: wheel and scrollIntoView work (list scrolled to {}, then 0)", wheeled);
            } else {
                Constants.LOG.error("Vellum autopilot: Mobdex list at {} after the wheel, {} after scrollIntoView ({})",
                        wheeled, top, shown);
            }
        }));
    }

    private static double scrollTop(VellumAutomation page) {
        return page.eval("document.getElementById('list').scrollTop").map(JsonElement::getAsDouble).orElse(-1.0);
    }

    /** How far the Mobdex list scrolls: where clicking its last row left it. */
    private static double listMax(VellumAutomation page) {
        return page.eval("document.getElementById('list').scrollHeight - document.getElementById('list').clientHeight")
                .map(JsonElement::getAsDouble).orElse(-1.0);
    }

    /**
     * The demo toast overlay takes the pointer only while chat is open: hovers and clicks its Allow button through
     * {@link VellumAutomation#hud} with chat open (and checks that it takes none without), screenshots the hover and
     * its tooltip, and checks the answer arrives and the toast closes.
     */
    private static void toast(Minecraft mc) {
        Supplier<Optional<VellumAutomation>> toast = () -> VellumAutomation.hud(VellumClient.DEMO_TOAST);
        String[] answer = new String[1];
        steps.add(() -> {
            mc.gui.setScreen(null);
            VellumHud.show(VellumClient.DEMO_TOAST).onMessage("answer", value -> answer[0] = value.getAsString());
        });
        settle("the toast", toast, 0);
        steps.add(() -> {
            if (toast.get().map(page -> page.hover(ALLOW)).orElse(false)) {
                Constants.LOG.error("Vellum autopilot: the toast took the pointer with no screen open");
            }
            mc.gui.setScreen(new ChatScreen("", false));
        });
        until("the toast took the pointer over chat", () -> toast.get().map(page -> page.hover(ALLOW)).orElse(false));
        settle("the toast's hover and tooltip", toast, 0);
        steps.add(() -> {
            boolean hovered = toast.get().flatMap(page -> page.eval("document.querySelector('" + ALLOW + "').matches(':hover')"))
                    .map(JsonElement::getAsBoolean).orElse(false);
            if (!hovered) Constants.LOG.error("Vellum autopilot: the toast's Allow button is not hovered");
        });
        grab(mc, "toast_hover", 0);
        steps.add(() -> {
            if (!toast.get().map(page -> page.click(ALLOW)).orElse(false)) {
                Constants.LOG.error("Vellum autopilot: could not click the toast's Allow button");
            }
        });
        until("the toast closed", () -> !VellumHud.isShown(VellumClient.DEMO_TOAST));
        steps.add(() -> {
            if ("once".equals(answer[0])) Constants.LOG.info("Vellum autopilot: HUD overlay input works");
            else Constants.LOG.error("Vellum autopilot: the toast answered {}, expected once", answer[0]);
            if (VellumHud.isShown(VellumClient.DEMO_TOAST)) VellumHud.hide(VellumClient.DEMO_TOAST);
            mc.gui.setScreen(null);
        });
    }

    /**
     * Runs {@code open}, waits for the open page to settle (at least {@link #SETTLE} ticks), and saves a screenshot
     * named {@code vellum_<name>.png}.
     */
    private static void shoot(Minecraft mc, String name, Runnable open) {
        steps.add(open);
        settle(name, VellumAutomation::screen, SETTLE);
        grab(mc, name, 5);
    }

    /**
     * Waits, polling once a tick, until {@code page} has settled ({@link VellumAutomation#settled}) and at least
     * {@code minTicks} have passed. With no such page after {@link #SETTLE} ticks (a screen that is not a Vellum
     * page) it goes on, and so it does after {@link #MAX_SETTLE} ticks of a page that keeps changing.
     */
    private static void settle(String what, Supplier<Optional<VellumAutomation>> page, int minTicks) {
        until(what + " settled", () -> polled >= minTicks
                && page.get().map(VellumAutomation::settled).orElse(polled >= SETTLE),
                Math.max(minTicks, MAX_SETTLE), what + " is still changing, going on", false);
    }

    /**
     * Waits until {@code condition} holds, asking once a tick, and logs {@code what} (which says what holding means)
     * with the ticks it took; goes on with a warning after {@link #MAX_POLL} ticks.
     */
    private static void until(String what, BooleanSupplier condition) {
        until(what, condition, MAX_POLL, "gave up waiting until " + what, true);
    }

    private static void until(String what, BooleanSupplier condition, int maxTicks, String gaveUp, boolean warns) {
        steps.add(() -> {
            until = condition;
            untilWhat = what;
            untilGaveUp = gaveUp;
            untilWarns = warns;
            untilMax = maxTicks;
            polled = 0;
        });
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
        });
        settle("the hover over " + selector, VellumAutomation::screen, 0);
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
