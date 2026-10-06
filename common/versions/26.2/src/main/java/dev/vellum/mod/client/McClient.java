package dev.vellum.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

/**
 * The client state and input that Vellum reaches differently on each Minecraft version; this is the one for 26.2.
 * Every version has a McClient with the same methods ({@code common/versions/<version>/src}).
 */
public final class McClient {
    private McClient() {}

    /** The open screen, or null. */
    public static @Nullable Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    /** Opens {@code screen}, or closes the open one with null. */
    public static void setScreen(@Nullable Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Whether a loading overlay (resource reload) covers the screen and takes its input. */
    public static boolean loadingOverlay() {
        return Minecraft.getInstance().gui.overlay() != null;
    }

    /** Whether the player hid the HUD (F1). */
    public static boolean hudHidden() {
        return Minecraft.getInstance().gui.hud.isHidden();
    }

    public static ChatComponent chat() {
        return Minecraft.getInstance().gui.hud.getChat();
    }

    /** Shows a message from the client in chat. */
    public static void systemMessage(Component message) {
        chat().addClientSystemMessage(message);
    }

    /** Where the pointer is, in GUI px. */
    public static double mouseX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledXPos(mc.getWindow());
    }

    public static double mouseY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.getScaledYPos(mc.getWindow());
    }

    /** The local player's skin texture. */
    static Identifier localSkin() {
        Minecraft mc = Minecraft.getInstance();
        return mc.playerSkinRenderCache().getOrDefault(ResolvableProfile.createResolved(mc.getGameProfile())).playerSkin().body().texturePath();
    }

    /** Text input for a text field of {@code owner}: GLFW always sends typed characters, so there is nothing to switch. */
    static void textInput(Screen owner, boolean on) {}

    // ---- Input through Minecraft's own handlers, as a real mouse and keyboard send it (VellumAutomation) ----

    /** Moves the pointer to a window point in screen px, as the mouse handler takes a move. */
    static void moveMouse(double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.setIgnoreFirstMove(); // takes the position without queueing a second move event
        mc.mouseHandler.onMove(window(), x, y);
    }

    /** Presses or releases DOM button {@code button} (0 left, 1 middle, 2 right) where the pointer is. */
    static void mouseButton(int button, boolean press) {
        Minecraft.getInstance().mouseHandler.onButton(window(), new MouseButtonInfo(KeyNames.mcButton(button), 0),
                press ? InputConstants.PRESS : InputConstants.RELEASE);
    }

    /** Turns the wheel where the pointer is; positive {@code notches} scroll down. */
    static void scroll(double notches) {
        Minecraft.getInstance().mouseHandler.onScroll(window(), 0, -notches);
    }

    static void key(KeyEvent event, boolean press) {
        Minecraft.getInstance().keyboardHandler.keyPress(window(), press ? InputConstants.PRESS : InputConstants.RELEASE, event);
    }

    static void typeChar(int codepoint) {
        Minecraft.getInstance().keyboardHandler.charTyped(window(), new CharacterEvent(codepoint));
    }

    private static long window() {
        return Minecraft.getInstance().getWindow().handle();
    }
}
