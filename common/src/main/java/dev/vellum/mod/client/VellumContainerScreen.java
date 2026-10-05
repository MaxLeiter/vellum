package dev.vellum.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

/**
 * A container screen whose layout is a Vellum page. Each {@code <slot index="n">} element places menu slot
 * {@code n}: every frame the slot's 16×16 item area goes where the element's content box is painted, so slots follow
 * scrolling, transforms and animations. Slots that are not painted (no element, hidden, scrolled out of their clip)
 * move off-screen. Vanilla still draws slot items, highlights, tooltips and the carried stack, on top of the page,
 * and handles slot clicks, drags and shift-clicks; everything else goes to the page.
 *
 * <p>Register one for a menu type with {@link VellumScreens#registerContainer}. The page's {@code vellum.data} is
 * {@code {title, inventory, slots: [{id, count, name}, ...]}}, updated when the menu's contents change, so pages can
 * show totals or filter slots by name.
 */
public class VellumContainerScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> implements DocumentDriver.Owner {
    private static final int OFF_SCREEN = -10_000;

    private final DocumentDriver driver;
    private final McCanvas.SlotSink slotSink = this::placeSlot;
    /** Copies of the stacks the page was last sent, so data goes out only when the menu's contents change. */
    private @Nullable ItemStack[] sent = new ItemStack[0];
    /** Whether the current press went to the page (its release goes there too) rather than to vanilla. */
    private boolean pagePress;

    public VellumContainerScreen(M menu, Inventory inventory, Component title, String url) {
        super(menu, inventory, title);
        this.driver = new DocumentDriver(this, url, null, -1);
        pushSlotData();
        for (Slot slot : menu.slots) hide(slot); // until the page paints them
    }

    public DocumentDriver driver() {
        return driver;
    }

    @Override
    protected void init() {
        super.init();
        // Slots are placed in screen coordinates from the page's layout.
        leftPos = 0;
        topPos = 0;
        driver.resize(width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        for (Slot slot : menu.slots) hide(slot); // painting puts back the slots it draws
        driver.extract(g, mouseX, mouseY); // the page first, so vanilla's slot layer lands on top of it
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    @Override
    public McCanvas.SlotSink slots() {
        return slotSink;
    }

    private void placeSlot(int index, int x, int y) {
        if (index >= menu.slots.size()) return;
        Slot slot = menu.slots.get(index);
        slot.x = x;
        slot.y = y;
    }

    @Override
    protected void containerTick() {
        pushSlotData();
    }

    /** Sends the title and slot contents to the page when they changed. */
    private void pushSlotData() {
        boolean changed = sent.length != menu.slots.size();
        if (changed) sent = new ItemStack[menu.slots.size()];
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
        driver.pushData(data.toString());
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
        return hit != null && (hit.tagName().equals("html") || hit.tagName().equals("body"));
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        pagePress = slotAt(event.x(), event.y()) == null && driver.mouseClicked(event);
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
