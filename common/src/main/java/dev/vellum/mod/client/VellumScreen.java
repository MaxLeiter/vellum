package dev.vellum.mod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A screen showing a Vellum page. The page draws its own background over the dimmed world (or the title panorama
 * when no world is loaded); Escape closes the screen unless the page handles it. Open one with {@link VellumScreens}.
 */
public class VellumScreen extends Screen implements DocumentDriver.Owner {
    private final DocumentDriver driver;
    private boolean pauses;

    /**
     * @param html    inline HTML, or null to load {@code url}
     * @param session the server session, or -1 for a client-side page
     */
    VellumScreen(String url, @Nullable String html, int session) {
        super(Component.translatable("vellum.screen"));
        this.driver = new DocumentDriver(this, url, html, session);
    }

    /** The page's driver: push data, listen for messages ({@code driver().onMessage(channel, handler)}). */
    public DocumentDriver driver() {
        return driver;
    }

    @Override
    protected void init() {
        driver.resize(width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        driver.extract(g, mouseX, mouseY);
    }

    /**
     * Whether this screen pauses a singleplayer world while it is open, like a vanilla book. Off by default, since
     * pages that talk to the server (shops, conversations) need it running. Minecraft asks every tick, so this can
     * change while the screen is open.
     */
    public VellumScreen pauses(boolean pauses) {
        this.pauses = pauses;
        return this;
    }

    @Override
    public boolean isPauseScreen() {
        return pauses;
    }

    @Override
    public boolean isInGameUi() {
        return minecraft.level != null;
    }

    @Override
    public void removed() {
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

    // ---- Input: the page first, then vanilla (Escape closes) ----

    @Override
    public void mouseMoved(double x, double y) {
        driver.mouseMoved(x, y);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return driver.mouseClicked(event);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return driver.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return driver.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return driver.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return driver.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return driver.charTyped(event);
    }
}
