package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * {@code <entity>}: a live entity render, fitted to the content box.
 * <ul>
 *   <li>{@code <entity player>}: the local player;</li>
 *   <li>{@code <entity type="minecraft:pig">}: a client-side entity of that type, created once per world;</li>
 *   <li>{@code <entity id="123">}: an entity in the world by network id.</li>
 * </ul>
 * {@code follow-mouse} turns its head and body toward the pointer (like the inventory's player), {@code rotate}
 * turns it by degrees, and {@code scale} multiplies the fitted size. Attributes are read when they change.
 */
final class EntityContent extends McReplaced {
    private boolean player, followMouse;
    /** The world entity's network id, or null for a created entity of {@link #type}. */
    private @Nullable Integer id;
    private @Nullable Identifier type;
    private float rotate, scale;
    private @Nullable Entity created;
    private @Nullable Level createdIn;

    EntityContent(Element element) {
        super(element);
        load();
    }

    @Override
    public float intrinsicWidth() {
        return 48;
    }

    @Override
    public float intrinsicHeight() {
        return 48;
    }

    @Override
    public void attributeChanged(String name) {
        load();
    }

    @Override
    public void dispose() {
        created = null;
        createdIn = null;
    }

    @Override
    protected void draw(McCanvas canvas, float x, float y, float width, float height) {
        Entity entity = entity();
        if (entity != null) EntityPortrait.draw(canvas, entity, pose(canvas, x, y, width, height), x, y, width, height);
    }

    private void load() {
        player = element.hasAttribute("player");
        followMouse = element.hasAttribute("follow-mouse");
        float networkId = element.numberAttribute("id", Float.NaN);
        id = Float.isNaN(networkId) ? null : (int) networkId;
        Identifier newType = Identifier.tryParse(attr("type", "minecraft:pig"));
        if (newType == null || !newType.equals(type)) createdIn = null; // create again
        type = newType;
        rotate = element.numberAttribute("rotate", 0);
        scale = element.numberAttribute("scale", 1);
    }

    /** The pose from the element's attributes and the pointer: the one place that decides how the entity stands. */
    private EntityPortrait.Pose pose(McCanvas canvas, float x, float y, float width, float height) {
        float gaze = 0, tilt = 0;
        if (followMouse && canvas.mouseX() >= 0) {
            // As vanilla's inventory: up to about ±30° toward a pointer 40 px away from the eyes.
            gaze = (float) Math.atan((x + width / 2 - canvas.mouseX()) / 40.0F) * 20.0F;
            tilt = (float) Math.atan((y + height / 3 - canvas.mouseY()) / 40.0F) * 20.0F;
        }
        return new EntityPortrait.Pose(rotate + gaze, gaze, tilt, scale, 0, 0);
    }

    private @Nullable Entity entity() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;
        if (player) return mc.player;
        if (id != null) return level.getEntity(id);
        if (createdIn != level) { // once per world (and after the type changes), even when the type is unknown
            created = type == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(type)
                    .map(t -> EntityPortrait.create(t, level)).orElse(null);
            createdIn = level;
        }
        return created;
    }
}
