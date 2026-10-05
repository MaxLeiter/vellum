package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.UUID;

/**
 * {@code <player-head name="..." uuid="...">}: a player's face (the 8×8 face plus the hat layer) from their skin.
 * Without attributes it shows the local player. Unknown profiles resolve in the background and show the default
 * skin until then.
 */
final class PlayerHeadContent extends McReplaced {
    private static final float PX = 1 / 64f;

    private ResolvableProfile profile;

    PlayerHeadContent(Element element) {
        super(element);
        this.profile = profile();
    }

    @Override
    public float intrinsicWidth() {
        return 16;
    }

    @Override
    public float intrinsicHeight() {
        return 16;
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("name") || name.equals("uuid")) profile = profile();
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        Identifier skin = Minecraft.getInstance().playerSkinRenderCache().getOrDefault(profile).playerSkin().body().texturePath();
        float size = Math.min(width, height);
        x += (width - size) / 2;
        y += (height - size) / 2;
        canvas.blit(skin, x, y, size, size, 8 * PX, 8 * PX, 16 * PX, 16 * PX, -1, false);
        canvas.blit(skin, x, y, size, size, 40 * PX, 8 * PX, 48 * PX, 16 * PX, -1, false);
    }

    private ResolvableProfile profile() {
        String uuid = element.getAttribute("uuid");
        if (uuid != null) {
            try {
                return ResolvableProfile.createUnresolved(UUID.fromString(uuid.strip()));
            } catch (IllegalArgumentException ignored) {
                // Fall back to the name, then the local player.
            }
        }
        String name = element.getAttribute("name");
        if (name != null && !name.isBlank()) return ResolvableProfile.createUnresolved(name.strip());
        return ResolvableProfile.createResolved(Minecraft.getInstance().getGameProfile());
    }
}
