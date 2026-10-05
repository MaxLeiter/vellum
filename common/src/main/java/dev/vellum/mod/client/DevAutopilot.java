package dev.vellum.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.vellum.mod.Constants;
import dev.vellum.mod.VellumConfig;
import dev.vellum.mod.server.VellumServer;
import dev.vellum.mod.server.VellumSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Rotations;
import net.minecraft.network.chat.Component;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Dev-only visual check ({@code ./gradlew :neoforge:runClient -Pautopilot} or {@code :fabric:runClient -Pautopilot},
 * i.e. {@code -Dvellum.autopilot=true}): creates a superflat creative world, opens the canvas test, every showcase
 * page and demo, screenshots each to {@code runs/client/screenshots/vellum_<name>.png} at GUI scale 2 (the canvas
 * test and the 3D pages also at 3), logs the frame rate of each (and of benchmark pages of 3D content), and quits.
 *
 * <p>It drives pages with {@link VellumAutomation}: hovers the title screen's first button for a burst of screenshots
 * a tick apart, drags a turntable, opens a page under the resting cursor (hovered within its first frames, without a
 * mouse move), hovers items and titles for their tooltips, checks that Chronicle's map pins show their titles at once
 * and what the narrator reads on its conversation, closes a page with a mod's key ({@link DocumentDriver#onKey}),
 * fills in the templates demo, clicks a Mobdex row scrolled out of its list and scrolls the list back, and answers the
 * demo toast overlay through chat. It waits for pages to settle rather than
 * for a fixed time, checks each result ({@link #check}), and logs how many checks failed when it finishes. Its own
 * pages are in {@code assets/vellum/vellum/dev/}: portraits whose armour stands get the autopilot's render state
 * ({@link VellumEntities}: arms, one raised, and no base plate), and a conversation card comparing soft and default
 * gazes at a pointer resting far below the speakers.
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
    /** The checks made so far ({@link #check}) and how many of them failed. */
    private static int checks, failures;

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
            if (failures == 0) Constants.LOG.info("Vellum autopilot finished: {} checks, {} failed", checks, failures);
            else Constants.LOG.error("Vellum autopilot finished: {} checks, {} failed", checks, failures);
            mc.stop();
            return;
        }
        try {
            step.run();
        } catch (RuntimeException e) {
            fail("a step failed", e);
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
            DevTour.plan(mc, steps);
            return;
        }
        guiScale(mc, 2);
        serverPages(mc);
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
        // Dragging the Turntable's big model (rotatable): it turns and tilts.
        open("the models page", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
        steps.add(() -> check(VellumAutomation.screen().map(page -> page.drag("model[rotatable]", 60, 20)).orElse(false),
                "dragged the Turntable's model", "could not drag the Turntable's model"));
        shoot(mc, "showcase_models_dragged", () -> VellumAutomation.screen().ifPresent(VellumAutomation::leave));
        portraits(mc);
        // What 3D content costs: the logged fps of 48 spinning entities, models and items (2D, for comparison).
        for (String bench : List.of("entity type='minecraft:zombie'", "model block='minecraft:chest'", "item id='minecraft:chest'")) {
            String tag = bench.split(" ")[0];
            String cell = "<" + bench + " style='width: 48px; height: 48px; animation: spin 4s linear infinite'></" + tag + ">";
            String page = "<style>@keyframes spin { to { -mc-yaw: 360deg } } body { display: flex; flex-wrap: wrap }</style>"
                    + cell.repeat(48);
            shoot(mc, "bench_" + tag, () -> VellumScreens.openInline(page, null));
        }
        open("the title screen", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("title")));
        hover(".panel button");
        for (int i = 0; i < 4; i++) grab(mc, "showcase_title_hover_" + i, 0);
        leave(); // or the next pages open hovered
        firstHover(mc);
        tooltips(mc);
        chronicle(mc);
        modKeys(mc);
        for (String demo : VellumClientCommands.DEMOS) {
            if (!demo.equals("hud")) shoot(mc, demo, () -> VellumClientCommands.demo(demo));
        }
        open("the templates demo", () -> VellumClientCommands.demo("templates"));
        onPage("the templates demo", page -> {
            page.click(".counter button:last-child");
            page.click(".counter button:last-child");
            page.click(".add input");
            page.type("Mine diamonds");
            page.key("Enter");
            page.leave(); // no hover or tooltip in the screenshot
            String state = page.eval("[state.count, state.todos.length]").map(Object::toString).orElse("none");
            check(state.equals("[2,3]"), "templates demo input works", "templates demo state is {}, expected [2,3]", state);
        });
        settle("the filled-in templates demo", 0);
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
     * The autopilot's portraits page, the conversation card, and the Turntable's portraits watching the pointer over
     * the stage's caption, at GUI scales 2 and 3 (where they are sharper).
     */
    private static void portraits(Minecraft mc) {
        for (int scale : new int[] {2, 3}) {
            String suffix = scale == 2 ? "" : "_gui3";
            guiScale(mc, scale);
            shoot(mc, "portraits" + suffix, () -> VellumScreens.open(devUrl("portraits")));
            // The conversation card with the pointer on its reply, far below the speakers.
            steps.add(() -> VellumScreens.open(devUrl("conversation")));
            settle("portraits_conversation" + suffix, SETTLE);
            hover(".card button");
            grab(mc, "portraits_conversation" + suffix, 5);
            open("the models page", () -> VellumScreens.open(VellumClientCommands.showcaseUrl("models")));
            hover(".stage p");
            grab(mc, "showcase_models_portraits" + suffix, 5);
        }
        guiScale(mc, 2);
        steps.add(() -> check(standStates > 0, "armour stands drawn from the registered render state",
                "the registered armour stand render state was never used"));
    }

    /**
     * Opens the first hover page ({@code dev/first_hover.html}) with the cursor resting at the window's centre, as a
     * screen opening leaves it: the mouse handler has the position, and no move event reaches the screen (Minecraft
     * drops the first move after a screen opens). The button must be hovered within the first frames, with no mouse
     * movement; half a second later its title tooltip shows, and the screenshot has both.
     */
    private static void firstHover(Minecraft mc) {
        steps.add(() -> {
            Window w = mc.getWindow();
            MouseHandler mouse = mc.mouseHandler;
            mouse.setIgnoreFirstMove(); // takes the position without a move event
            mouse.onMove(w.handle(), w.getScreenWidth() / 2.0, w.getScreenHeight() / 2.0, 0, 0);
            VellumScreens.open(devUrl("first_hover"));
        });
        onPage("the first hover page", page -> {
            int at = page.eval("hoveredAt").map(JsonElement::getAsInt).orElse(-1);
            check(at >= 1 && at <= 3, "a page opened under a resting cursor is hovered from frame {}",
                    "the button under the resting cursor was not hovered in the first frames ({})", at);
        });
        settle("the first hover's tooltip", 0);
        grab(mc, "first_hover", 0);
        leave();
    }

    /**
     * Tooltips on the shop row page ({@code dev/shop_row.html}): an {@code <item tooltip>} in a row with a
     * {@code title-json} (the item's lines, then the row's, in one box, none wrapped); the same row's title alone
     * beside the item (wrapped at 170 px); a {@code title-nowrap} title; and the showcase shop's wares, whose items
     * show their tooltip with the card's rarity and price.
     */
    private static void tooltips(Minecraft mc) {
        open("the shop row", () -> VellumScreens.open(devUrl("shop_row")));
        hover("#row item");
        steps.add(() -> check(VellumAutomation.screen().map(VellumAutomation::tooltipShown).orElse(false),
                "the item's tooltip shows with the row's lines", "no tooltip over the shop row's item"));
        grab(mc, "tooltip_item_title", 0);
        hover("#row");
        grab(mc, "tooltip_title_wrapped", 0);
        hover("#nowrap");
        grab(mc, "tooltip_title_nowrap", 0);
        steps.add(() -> VellumScreens.open(VellumClientCommands.showcaseUrl("shop")));
        settle("the shop", SETTLE);
        hover(".card:first-child item");
        grab(mc, "showcase_shop_tooltip", 0);
        leave();
    }

    /**
     * Chronicle's map and conversation ({@code dev/chronicle.html}). Tooltips: a map pin shows its title on the first
     * frame after the pointer arrives (the map's {@code -mc-tooltip-delay: 0ms}), while a row with the default delay
     * shows nothing then and its title half a second later. Narration, recorded rather than heard
     * ({@link VellumAutomation#recordNarration}): the screen reads the page's title, then a hovered reply as vanilla
     * reads a button ("Reply 1: About the letter button"); the log reads its last line when the page opens, then each
     * new line, the reply's first and the answer a moment later; the aria-hidden flourish is never read.
     */
    private static void chronicle(Minecraft mc) {
        String greeting = "Emperor Cualius. Greetings, stranger! Have you done what I asked?";
        String answer = "Emperor Cualius. Then take it to Candacona, and quickly.";
        open("the Chronicle page", () -> {
            VellumAutomation.recordNarration().clear();
            VellumScreens.open(devUrl("chronicle"));
        });
        moveOnto("#rauca");
        steps.add(() -> check(shownNow(), "a map pin's title shows on the first frame (-mc-tooltip-delay: 0ms)",
                "a map pin's title did not show at once"));
        moveOnto("#note");
        steps.add(() -> check(!shownNow(), "a row with the default delay shows no title on the first frame",
                "a row with the default delay showed its title at once"));
        settle("the note's tooltip", 0);
        steps.add(() -> check(shownNow(), "and shows it once its half second is up", "the note's title never showed"));
        grab(mc, "chronicle_tooltip", 0);
        onPage("the Chronicle page", page -> {
            String narration = page.narration().orElse("");
            check(narration.startsWith("Emperor Cualius"), "the narrator reads the page's title: '{}'",
                    "the narration '{}' does not start with the page's title", narration);
        });
        hover("#reply");
        onPage("the Chronicle page", page -> {
            String reply = Component.translatable("gui.narrate.button", "Reply 1: About the letter").getString();
            String narration = page.narration().orElse("");
            check(narration.contains(reply) && !narration.contains("~"), "the narration '{}' reads the hovered reply as '{}'",
                    "the narration '{}' has no '{}', or reads the aria-hidden flourish", narration, reply);
            page.click("#reply");
        });
        until("the log's answer was narrated", ticks -> VellumAutomation.recordNarration().contains(answer));
        steps.add(() -> {
            List<String> recorded = VellumAutomation.recordNarration();
            check(recorded.equals(List.of(greeting, "You. About the letter.", answer)),
                    "the log reads its last line on opening, then each new line: {}",
                    "the log narrated {}, expected its greeting, the reply and the answer", recorded);
            VellumAutomation.stopRecordingNarration();
        });
        leave();
    }

    /** Moves the pointer onto the first element of the open page matching {@code selector}, without waiting. */
    private static void moveOnto(String selector) {
        steps.add(() -> {
            if (!VellumAutomation.screen().map(page -> page.hover(selector)).orElse(false)) fail("nothing to hover at {}", selector);
        });
    }

    /** Whether the open page's last frame showed a tooltip. */
    private static boolean shownNow() {
        return VellumAutomation.screen().map(VellumAutomation::tooltipShown).orElse(false);
    }

    /**
     * A mod's key on Chronicle's book ({@code dev/mod_keys.html}, {@link DocumentDriver#onKey}): a handler that
     * closes the page on J. Typed into a focused field, J is text and the handler is not asked; neither is it for a
     * key the page cancels. With nothing focused, J reaches it and the page closes.
     */
    private static void modKeys(Minecraft mc) {
        VellumScreen[] book = new VellumScreen[1];
        List<String> asked = new ArrayList<>();
        open("the mod keys page", () -> {
            book[0] = VellumScreens.open(devUrl("mod_keys"));
            book[0].driver().onKey(e -> {
                String key = KeyNames.key(e.key(), e.keycode(), e.hasShiftDown());
                asked.add(key);
                if (!key.equals("j")) return false;
                book[0].onClose();
                return true;
            });
        });
        onPage("the mod keys page", page -> {
            page.click("#name");
            page.type("j");
            String typed = page.eval("document.getElementById('name').value").map(JsonElement::getAsString).orElse("");
            page.click("p"); // nothing focused
            page.leave();
            page.key("ArrowLeft");
            check(typed.equals("j") && asked.isEmpty() && mc.gui.screen() == book[0],
                    "keys the page uses (typing, a cancelled key) don't reach the mod's handler",
                    "the field has '{}', the mod's handler was asked about {}", typed, asked);
            page.key("j");
            check(mc.gui.screen() != book[0] && asked.equals(List.of("j")), "the mod's key handler closed the page",
                    "J left the page open; the mod's handler was asked about {}", asked);
        });
    }

    /**
     * In the open Mobdex: clicks the list's last row, which is scrolled out of the list, so the click has to scroll it
     * into view to land; then turns the wheel over the list and scrolls its first row back into view.
     */
    private static void mobdexList(Minecraft mc) {
        String last = "#list .row:last-child";
        onPage("the Mobdex", page -> {
            float[] list = page.rect("#list").orElse(null), row = page.rect(last).orElse(null);
            boolean hidden = list != null && row != null && row[1] >= list[1] + list[3];
            boolean clicked = page.click(last);
            page.leave();
            boolean selected = page.eval("state.sel === filtered()[filtered().length - 1].id")
                    .map(JsonElement::getAsBoolean).orElse(false);
            check(hidden && clicked && selected, "clicking a scrolled-out row works",
                    "Mobdex row click: scrolled out {}, clicked {}, selected {}", hidden, clicked, selected);
        });
        settle("the Mobdex selection", 0);
        grab(mc, "showcase_mobdex_last", 0);
        onPage("the Mobdex", page -> {
            if (!page.wheel("#list", -2)) fail("no wheel over the Mobdex list");
            page.leave();
        });
        settle("the Mobdex list's scroll", 0);
        onPage("the Mobdex", page -> {
            double wheeled = scrollTop(page);
            boolean shown = page.scrollIntoView("#list .row:first-child");
            double top = scrollTop(page);
            check(wheeled < listMax(page) && shown && top == 0, "wheel and scrollIntoView work (list scrolled to {}, then 0)",
                    "Mobdex list at {} after the wheel, {} after scrollIntoView ({})", wheeled, top, shown);
        });
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
     * its tooltip, checks the overlay narrates the hovered button, and checks the answer arrives and the toast closes.
     */
    private static void toast(Minecraft mc) {
        Supplier<Optional<VellumAutomation>> toast = () -> VellumAutomation.hud(VellumClient.DEMO_TOAST);
        String[] answer = new String[1];
        String allow = Component.translatable("gui.narrate.button", "Allow").getString();
        steps.add(() -> VellumAutomation.recordNarration().clear());
        steps.add(() -> {
            mc.gui.setScreen(null);
            VellumHud.show(VellumClient.DEMO_TOAST).onMessage("answer", value -> answer[0] = value.getAsString());
        });
        settle("the toast", toast, 0);
        steps.add(() -> {
            check(!toast.get().map(page -> page.hover(ALLOW)).orElse(false), "the toast takes no pointer with no screen open",
                    "the toast took the pointer with no screen open");
            mc.gui.setScreen(new ChatScreen("", false));
        });
        until("the toast took the pointer over chat", ticks -> toast.get().map(page -> page.hover(ALLOW)).orElse(false));
        settle("the toast's hover and tooltip", toast, 0);
        steps.add(() -> check(toast.get().flatMap(page -> page.eval("document.querySelector('" + ALLOW + "').matches(':hover')"))
                .map(JsonElement::getAsBoolean).orElse(false), "the toast's Allow button is hovered",
                "the toast's Allow button is not hovered"));
        grab(mc, "toast_hover", 0);
        // The chat screen under the toast knows nothing of it, so the overlay reads its hovered button itself.
        until("the toast's hovered button was narrated",
                ticks -> VellumAutomation.recordNarration().stream().anyMatch(s -> s.startsWith(allow)));
        steps.add(() -> {
            List<String> said = VellumAutomation.recordNarration();
            check(said.stream().anyMatch(s -> s.startsWith(allow)), "the toast overlay narrated {}, reading its hovered '{}'",
                    "the toast overlay narrated {}, nothing starting '{}'", said, allow);
            VellumAutomation.stopRecordingNarration();
        });
        steps.add(() -> {
            if (!toast.get().map(page -> page.click(ALLOW)).orElse(false)) fail("could not click the toast's Allow button");
        });
        until("the toast closed", ticks -> !VellumHud.isShown(VellumClient.DEMO_TOAST));
        steps.add(() -> {
            check("once".equals(answer[0]), "HUD overlay input works", "the toast answered {}, expected once", answer[0]);
            if (VellumHud.isShown(VellumClient.DEMO_TOAST)) VellumHud.hide(VellumClient.DEMO_TOAST);
            mc.gui.setScreen(null);
        });
    }

    // ---- Steps ----

    /** One of the autopilot's own pages, in {@code assets/vellum/vellum/dev/}. */
    private static String devUrl(String page) {
        return "vellum:vellum/dev/" + page + ".html";
    }

    /** Runs {@code open} and waits for the page it opens to settle. */
    private static void open(String what, Runnable open) {
        steps.add(open);
        settle(what, 0);
    }

    /**
     * Runs {@code open}, waits for the open page to settle (at least {@link #SETTLE} ticks), and saves a screenshot
     * named {@code vellum_<name>.png}.
     */
    private static void shoot(Minecraft mc, String name, Runnable open) {
        steps.add(open);
        settle(name, SETTLE);
        grab(mc, name, 5);
    }

    /** Runs {@code action} on the open Vellum screen's page; a failed check when none is open. */
    private static void onPage(String what, Consumer<VellumAutomation> action) {
        steps.add(() -> VellumAutomation.screen().ifPresentOrElse(action, () -> fail("no page open for {}", what)));
    }

    /** {@link #settle(String, Supplier, int)} for the open screen's page. */
    private static void settle(String what, int minTicks) {
        settle(what, VellumAutomation::screen, minTicks);
    }

    /**
     * Waits, asking once a tick, until {@code page} has settled ({@link VellumAutomation#settled}) and at least
     * {@code minTicks} have passed. With no such page after {@link #SETTLE} ticks (a screen that is not a Vellum
     * page) it goes on, and so it does after {@link #MAX_SETTLE} ticks of a page that keeps changing.
     */
    private static void settle(String what, Supplier<Optional<VellumAutomation>> page, int minTicks) {
        until(what + " settled", ticks -> ticks >= minTicks && page.get().map(VellumAutomation::settled).orElse(ticks >= SETTLE),
                Math.max(minTicks, MAX_SETTLE), what + " is still changing, going on", false);
    }

    /**
     * Waits until {@code done} holds, asking once a tick with the number of ticks asked so far, and logs {@code what}
     * (which says what holding means) with the ticks it took; goes on with a warning after {@link #MAX_POLL} ticks.
     */
    private static void until(String what, IntPredicate done) {
        until(what, done, MAX_POLL, "gave up waiting until " + what, true);
    }

    /** A step that runs again the next tick, ahead of the others, until {@code done} holds or {@code maxTicks} pass. */
    private static void until(String what, IntPredicate done, int maxTicks, String gaveUp, boolean warn) {
        steps.add(new Runnable() {
            private int ticks;

            @Override
            public void run() {
                ticks++;
                if (done.test(ticks)) Constants.LOG.info("Vellum autopilot: {} ({} ticks)", what, ticks);
                else if (ticks < maxTicks) steps.addFirst(this);
                else if (warn) Constants.LOG.warn("Vellum autopilot: {} after {} ticks", gaveUp, ticks);
                else Constants.LOG.info("Vellum autopilot: {} after {} ticks", gaveUp, ticks);
            }
        });
    }

    /** Hovers the first element of the open Vellum screen matching {@code selector} and lets hover effects settle. */
    private static void hover(String selector) {
        steps.add(() -> {
            if (!VellumAutomation.screen().map(page -> page.hover(selector)).orElse(false)) fail("nothing to hover at {}", selector);
        });
        settle("the hover over " + selector, 0);
    }

    /** Moves the pointer off the open page, so the next pages and screenshots have nothing hovered. */
    private static void leave() {
        steps.add(() -> VellumAutomation.screen().ifPresent(VellumAutomation::leave));
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

    private static void guiScale(Minecraft mc, int scale) {
        steps.add(() -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
            wait = 5;
        });
    }

    // ---- Checks ----

    /**
     * Logs {@code pass} when {@code ok}, else {@code fail} as an error, each with {@code args} for its placeholders.
     * Failures count toward the total logged when the autopilot finishes.
     */
    private static void check(boolean ok, String pass, String fail, Object... args) {
        if (!ok) {
            fail(fail, args);
            return;
        }
        checks++;
        Constants.LOG.info("Vellum autopilot: " + pass, args);
    }

    /** A failed check: logs {@code message} as an error (a throwable last in {@code args} with its stack trace). */
    private static void fail(String message, Object... args) {
        checks++;
        failures++;
        Constants.LOG.error("Vellum autopilot: " + message, args);
    }

    // ---- Server pages (ServerPages, DocumentDriver's close keys) ----

    /** The integrated server's opens and closes of the autopilot's sessions, and the keys its pages saw. */
    private static final List<String> serverLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Whether the server reopens its page whenever the player closes it, as a hostile server would. */
    private static volatile boolean reopen;

    /** A page that keeps Escape, reports the keys it sees, and pushes nothing. */
    private static final String STUBBORN_PAGE = """
            <body style="background:#203040"><p>Stubborn page</p><input id="name">
            <script>addEventListener('keydown', e => { vellum.send('key', e.key); if (e.key === 'Escape') e.preventDefault() })</script>
            </body>""";

    /**
     * What a hostile server can and can't do with pages: a page that keeps Escape still closes on Shift+Escape and
     * on Escape pressed three times; a page opened while chat is open waits for chat to close; a server that reopens
     * its page as the player closes it is stopped; a script can't open a web link without a click; data nested
     * deeper than {@code client.maxDataDepth} is dropped; a page's messages are capped per screen, across reloads.
     */
    private static void serverPages(Minecraft mc) {
        Supplier<Integer> session = () -> mc.gui.screen() instanceof VellumScreen s ? s.driver().session() : -1;
        // Shift+Escape: closes at once, and the page never sees it.
        steps.add(() -> serverOpen(mc, STUBBORN_PAGE));
        until("the server's page is shown", ticks -> session.get() >= 0);
        steps.add(() -> pressEscape(mc, true));
        until("Shift+Escape closed the server's page", ticks -> mc.gui.screen() == null && serverLog.contains("closed"));
        steps.add(() -> check(serverLog.stream().noneMatch(e -> e.startsWith("key")), "the page never saw Shift+Escape",
                "the page saw Shift+Escape: {}", serverLog));
        // Escape three times: the page keeps the first two. (Waits between closing and opening, so the reopen
        // guard doesn't count these.)
        steps.add(() -> wait = 30);
        steps.add(serverLog::clear);
        steps.add(() -> serverOpen(mc, STUBBORN_PAGE));
        until("the server's page is shown again", ticks -> session.get() >= 0);
        steps.add(() -> pressEscape(mc, false));
        steps.add(() -> pressEscape(mc, false));
        steps.add(() -> check(session.get() >= 0, "the page kept two Escapes", "two Escapes closed the page"));
        steps.add(() -> pressEscape(mc, false));
        until("three Escapes closed the page", ticks -> mc.gui.screen() == null);
        steps.add(() -> wait = 30);
        // A page the server opens while chat is open waits until chat closes.
        steps.add(() -> mc.gui.setScreen(new ChatScreen("", false)));
        steps.add(() -> serverOpen(mc, "<p>After chat</p>"));
        steps.add(() -> wait = 10);
        steps.add(() -> check(mc.gui.screen() instanceof ChatScreen, "a server page waits while chat is open",
                "a server page replaced chat"));
        steps.add(() -> mc.gui.setScreen(null));
        until("the waiting page is shown once chat closed", ticks -> session.get() >= 0);
        // A script can't open a web link by itself.
        steps.add(() -> serverOpen(mc, "<script>setTimeout(() => location.href = 'https://example.com/', 50)</script><p>Link</p>"));
        steps.add(() -> wait = 20);
        steps.add(() -> check(session.get() >= 0, "a script's web link without a click was ignored",
                "a script opened {} without a click", mc.gui.screen()));
        // Data nested past client.maxDataDepth is dropped.
        steps.add(() -> serverRun(mc, player -> {
            VellumSession s = VellumServer.openInline(player, "<p>Data</p>", JsonParser.parseString("{\"ok\": 1}"));
            s.push(JsonParser.parseString("[".repeat(100) + "]".repeat(100)));
        }));
        until("the data page is shown", ticks -> mc.gui.screen() instanceof VellumScreen v && v.driver().data() instanceof com.google.gson.JsonObject);
        steps.add(() -> wait = 5);
        steps.add(() -> check(mc.gui.screen() instanceof VellumScreen v && v.driver().data() instanceof com.google.gson.JsonObject o && o.has("ok"),
                "deeply nested data was dropped", "deeply nested data reached the page"));
        // vellum.send is capped per screen (client.messageBurst), and reloading the page doesn't reset the count.
        steps.add(() -> {
            serverLog.clear();
            serverOpen(mc, "<p>Spam</p><script>for (let i = 0; i < 100; i++) vellum.send('spam', i)</script>");
        });
        until("the spamming page is shown", ticks -> session.get() >= 0);
        steps.add(() -> wait = 5);
        steps.add(() -> {
            if (mc.gui.screen() instanceof VellumScreen v) v.driver().reload();
        });
        steps.add(() -> wait = 5);
        steps.add(() -> {
            long sent = serverLog.stream().filter("spam"::equals).count();
            int burst = VellumConfig.CLIENT_MESSAGE_BURST.get();
            check(sent >= burst && sent < 2L * burst, "a page got {} of 200 messages through, across a reload (burst {})",
                    "a page got {} of 200 messages through across a reload; expected one burst of {} and a little", sent, burst);
        });
        // Typing into a server's page shows Vellum's notice over it.
        steps.add(() -> serverOpen(mc, "<body style='background:#2a2a40;padding:20px'><p>Sign in</p><input autofocus></body>"));
        until("the sign-in page is shown", ticks -> session.get() >= 0 && mc.gui.screen() instanceof VellumScreen v && v.driver().typing());
        onPage("the sign-in page", page -> page.type("hunter2"));
        grab(mc, "server_page_typing", 5);
        // A server that reopens its page whenever it is closed is stopped after client.reopenStrikes reopens.
        steps.add(() -> {
            reopen = true;
            serverLog.clear();
            serverOpen(mc, "<p>Again</p>");
        });
        for (int i = 0; i <= VellumConfig.CLIENT_REOPEN_STRIKES.get(); i++) {
            until("the reopened page is shown, or the server is stopped", ticks -> session.get() >= 0 || ServerPages.blocked());
            steps.add(() -> {
                if (session.get() >= 0) pressEscape(mc, false);
            });
            steps.add(() -> wait = 2);
        }
        steps.add(() -> {
            reopen = false;
            check(mc.gui.screen() == null && ServerPages.blocked(), "the reopen loop was stopped after {} opens",
                    "the reopen loop went on: {} opens", serverLog.stream().filter("opened"::equals).count());
        });
    }

    private static void serverOpen(Minecraft mc, String html) {
        serverRun(mc, player -> open(player, html));
    }

    private static void open(ServerPlayer player, String html) {
        serverLog.add("opened");
        VellumServer.openInline(player, html, null)
                .onMessage("key", (p, key) -> serverLog.add("key " + key))
                .onMessage("spam", (p, value) -> serverLog.add("spam"))
                .onClose(() -> {
                    serverLog.add("closed");
                    if (reopen) player.level().getServer().execute(() -> open(player, html));
                });
    }

    private static void serverRun(Minecraft mc, Consumer<ServerPlayer> action) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        UUID id = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) action.accept(player);
        });
    }

    /** Escape through Minecraft's keyboard handler, as the player presses it. */
    private static void pressEscape(Minecraft mc, boolean shift) {
        KeyEvent event = new KeyEvent(InputConstants.KEY_ESCAPE, 0, shift ? InputConstants.MOD_SHIFT : 0);
        long window = mc.getWindow().handle();
        mc.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
        mc.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
    }

    // ---- World ----

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
