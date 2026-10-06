package dev.vellum.mod.client.replaced;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code <player-head name="..." uuid="...">}: a player's face (the 8×8 face plus the hat layer) from their skin,
 * filling the box ({@code object-fit: contain} makes it a square placed by {@code object-position}), multiplied by
 * {@code -mc-tint}. Without attributes it shows the local player. Unknown profiles resolve in the background and show
 * the default skin until then. (The 1.21.1 one: profiles resolve with {@link ResolvableProfile#resolve}, skins load
 * through the skin manager.)
 */
final class PlayerHeadContent extends McReplaced {
    private static final float PX = 1 / 64f;

    private ResolvableProfile profile;
    /** The profile resolving in the background, or null when it needs none (the local player). */
    private @Nullable CompletableFuture<ResolvableProfile> resolving;

    PlayerHeadContent(Element element) {
        super(element);
        load();
    }

    @Override
    public float intrinsicWidth() {
        return 16;
    }

    @Override
    public float intrinsicHeight() {
        return 16;
    }

    /** Until the skin has resolved (or failed to), so automation waits for the real face. */
    @Override
    public boolean loading() {
        GameProfile resolved = resolved();
        if (resolved == null) return resolving != null && !resolving.isDone();
        return !Minecraft.getInstance().getSkinManager().getOrLoad(resolved).isDone();
    }

    @Override
    public void attributeChanged(String name) {
        if (name.equals("name") || name.equals("uuid")) load();
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        GameProfile resolved = resolved();
        ResourceLocation skin = resolved == null ? Minecraft.getInstance().getSkinManager().getInsecureSkin(profile.gameProfile()).texture()
                : Minecraft.getInstance().getSkinManager().getInsecureSkin(resolved).texture();
        int tint = element.computedStyle().tint;
        canvas.blit(skin, x, y, width, height, 8 * PX, 8 * PX, 16 * PX, 16 * PX, tint, false);
        canvas.blit(skin, x, y, width, height, 40 * PX, 8 * PX, 48 * PX, 16 * PX, tint, false);
    }

    /** The profile with its skin textures, or null while it resolves (or when it failed to). */
    private @Nullable GameProfile resolved() {
        if (resolving == null) return profile.gameProfile();
        ResolvableProfile done = resolving.getNow(null);
        return done == null ? null : done.gameProfile();
    }

    private void load() {
        profile = profile();
        resolving = profile.isResolved() ? null : profile.resolve().exceptionally(e -> null);
    }

    private ResolvableProfile profile() {
        String uuid = element.getAttribute("uuid");
        if (uuid != null) {
            try {
                return new ResolvableProfile(Optional.empty(), Optional.of(UUID.fromString(uuid.strip())), new PropertyMap());
            } catch (IllegalArgumentException ignored) {
                // Fall back to the name, then the local player.
            }
        }
        String name = element.getAttribute("name");
        if (name != null && !name.isBlank()) return new ResolvableProfile(Optional.of(name.strip()), Optional.empty(), new PropertyMap());
        return new ResolvableProfile(Minecraft.getInstance().getGameProfile());
    }
}
