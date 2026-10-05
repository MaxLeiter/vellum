package dev.vellum.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vellum.engine.dom.Document;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Coordinates;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * A container screen whose layout is a Vellum page. Each {@code <slot index="n">} element places menu slot
 * {@code n}: every frame the slot's 16×16 item area goes where the element's content box is painted, so slots follow
 * scrolling, transforms and animations. Slots that are not painted (no element, hidden, scrolled out of their clip)
 * move off-screen. Vanilla still draws slot items, highlights, tooltips and the carried stack, on top of the page,
 * and handles slot clicks, drags and shift-clicks; everything else goes to the page.
 *
 * <p>The screen's GUI area ({@code leftPos}, {@code topPos}, {@code imageWidth}, {@code imageHeight}, which JEI, REI
 * and other mods read to lay out beside it) is the page's content, updated every frame: the elements marked
 * {@code data-vellum-bounds}, or {@code body}'s in-flow children ({@link Coordinates#contentBounds}). Slot positions
 * are relative to it, as in vanilla.
 *
 * <p>Register one for a menu type with {@link VellumScreens#registerContainer}. The page's {@code vellum.data} is
 * {@code {title, inventory, slots: [{id, count, name}, ...]}}, plus the fields of the registration's data function
 * (the mod's own: an entity id, a tier), updated when the menu's contents or those fields change, so pages can show
 * totals, filter slots by name, or show the mod's state.
 *
 * <p>The narrator reads it as a {@link VellumScreen}: the page's {@code <title>} if it has one (else the menu's title),
 * then its focused or hovered element; a hovered {@code <slot>} reads as its item ("Item: Coal").
 */
public class VellumContainerScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> implements DocumentDriver.Owner {
    private static final int OFF_SCREEN = -10_000;

    private final DocumentDriver driver;
    private final McCanvas.SlotSink slotSink = this::placeSlot;
    /** The mod's fields for {@code vellum.data}, or null. */
    private final @Nullable Function<? super M, ? extends JsonObject> extra;
    /** Copies of the stacks the page was last sent, so data goes out only when the menu's contents change. */
    private @Nullable ItemStack[] sent = new ItemStack[0];
    /** A copy of the extra fields the page was last sent. */
    private @Nullable JsonObject sentExtra;
    private boolean extraFailed;
    /** Whether the current press went to the page (its release goes there too) rather than to vanilla. */
    private boolean pagePress;

    public VellumContainerScreen(M menu, Inventory inventory, Component title, String url) {
        this(menu, inventory, title, url, null);
    }

    /**
     * @param extra fields to add to {@code vellum.data}, from the menu (called on open and every client tick; the
     *              page gets new data when its result changes), or null
     */
    public VellumContainerScreen(M menu, Inventory inventory, Component title, String url,
                                 @Nullable Function<? super M, ? extends JsonObject> extra) {
        super(menu, inventory, title);
        this.driver = new DocumentDriver(this, url, null, -1);
        this.extra = extra;
        pushSlotData();
        for (Slot slot : menu.slots) hide(slot); // until the page paints them
    }

    public DocumentDriver driver() {
        return driver;
    }

    @Override
    protected void init() {
        super.init();
        setGuiArea(0, 0, width, height); // the whole screen until the page has laid out
        driver.resize(width, height);
    }

    /** The GUI area is where the page's content is, as laid out for this frame (slots are placed relative to it). */
    @Override
    public void beforePaint(Document document) {
        float[] r = Coordinates.contentBounds(document);
        if (r == null) {
            setGuiArea(0, 0, width, height);
            return;
        }
        int x0 = (int) Math.floor(r[0]), y0 = (int) Math.floor(r[1]);
        setGuiArea(x0, y0, (int) Math.ceil(r[0] + r[2]) - x0, (int) Math.ceil(r[1] + r[3]) - y0);
    }

    private void setGuiArea(int left, int top, int width, int height) {
        leftPos = left;
        topPos = top;
        imageWidth = width;
        imageHeight = height;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        for (Slot slot : menu.slots) hide(slot); // painting puts back the slots it draws
        driver.extractPage(g, mouseX, mouseY); // the page first, so vanilla's slot layer lands on top of it
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    /** A hovered slot's item tooltip first; the page's tooltip (an {@code <item tooltip>}'s or a title) where there is none. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractTooltip(g, mouseX, mouseY);
        if (hoveredSlot == null || !hoveredSlot.hasItem()) driver.extractTooltip(g, mouseX, mouseY);
    }

    @Override
    public McCanvas.SlotSink slots() {
        return slotSink;
    }

    /** Vanilla's slot positions are relative to the GUI area. */
    private void placeSlot(int index, int x, int y) {
        if (index >= menu.slots.size()) return;
        Slot slot = menu.slots.get(index);
        slot.x = x - leftPos;
        slot.y = y - topPos;
    }

    @Override
    protected void containerTick() {
        pushSlotData();
    }

    /** Sends the title, slot contents and extra fields to the page when they changed. */
    private void pushSlotData() {
        JsonObject fields = extraFields();
        boolean changed = !Objects.equals(fields, sentExtra);
        sentExtra = fields;
        if (sent.length != menu.slots.size()) {
            sent = new ItemStack[menu.slots.size()];
            changed = true;
        }
        for (int i = 0; i < sent.length; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (sent[i] == null || !ItemStack.matches(sent[i], stack)) {
                sent[i] = stack.copy();
                changed = true;
            }
        }
        if (!changed) return;
        JsonArray slots = new JsonArray();
        for (ItemStack stack : sent) {
            JsonObject o = new JsonObject();
            o.addProperty("id", stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            o.addProperty("count", stack.getCount());
            o.addProperty("name", stack.isEmpty() ? "" : stack.getHoverName().getString());
            slots.add(o);
        }
        JsonObject data = new JsonObject();
        data.addProperty("title", title.getString());
        data.addProperty("inventory", playerInventoryTitle.getString());
        data.add("slots", slots);
        if (fields != null) fields.entrySet().forEach(e -> data.add(e.getKey(), e.getValue()));
        driver.pushData(data.toString());
    }

    /** A copy of the mod's fields now (it may reuse and change its object), or null; a failing function is logged once. */
    private @Nullable JsonObject extraFields() {
        if (extra == null) return null;
        try {
            JsonObject fields = extra.apply(menu);
            return fields == null ? null : fields.deepCopy();
        } catch (RuntimeException e) {
            if (!extraFailed) Constants.LOG.error("Vellum: the data function of {} failed", title.getString(), e);
            extraFailed = true;
            return null;
        }
    }

    /** The page's {@code <title>}, else the menu's title. */
    @Override
    public Component getTitle() {
        return driver.narrator().title(super.getTitle());
    }

    /** The page's focused or hovered element (a slot reads as its item); with none, what vanilla says. */
    @Override
    protected void updateNarratedWidget(NarrationElementOutput output) {
        if (!driver.narrator().addNarratedElement(output)) super.updateNarratedWidget(output);
    }

    @Override
    public String narration() {
        return PageNarrator.collect(this::updateNarrationState);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // The page draws its own titles.
    }

    @Override
    public void removed() {
        super.removed();
        driver.close();
    }

    @Override
    public void closeDocument() {
        onClose();
    }

    @Override
    public Screen screen() {
        return this;
    }

    /** Dropping the carried stack happens outside the page's content: where only html or body is under the pointer. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        Element hit = driver.elementAt(mouseX, mouseY);
        return hit != null && DocumentDriver.isBackground(hit);
    }

    private static void hide(Slot slot) {
        slot.x = OFF_SCREEN;
        slot.y = OFF_SCREEN;
    }

    private @Nullable Slot slotAt(double x, double y) {
        for (Slot slot : menu.slots) {
            if (slot.isActive() && isHovering(slot.x, slot.y, 16, 16, x, y)) return slot;
        }
        return null;
    }

    // ---- Input: slots go to vanilla, everything else to the page ----

    @Override
    public void mouseMoved(double x, double y) {
        driver.mouseMoved(x, y);
    }

    /**
     * Presses on slots go to vanilla, and so do presses on the page's background while a stack is carried: vanilla drops
     * it ({@link #hasClickedOutside}), as outside a vanilla container.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean vanilla = slotAt(event.x(), event.y()) != null
                || !menu.getCarried().isEmpty() && driver.contentAt(event.x(), event.y()) == null;
        pagePress = !vanilla && driver.mouseClicked(event);
        return pagePress || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pagePress) {
            pagePress = false;
            return driver.mouseReleased(event);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return (slotAt(x, y) == null && driver.mouseScrolled(x, y, scrollX, scrollY)) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return driver.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return driver.keyReleased(event) || super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return driver.charTyped(event);
    }
}
