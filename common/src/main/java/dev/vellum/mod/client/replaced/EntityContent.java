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
 * turns it by degrees, and {@code scale} multiplies the fitted size. All of that is read in {@link #pose}.
 */
final class EntityContent extends McReplaced {
    private @Nullable Entity created;
    private @Nullable Level createdIn;

    EntityContent(Element element) {
        super(element);
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
        if (name.equals("type")) createdIn = null;
    }

    @Override
    public void dispose() {
        created = null;
        createdIn = null;
    }

    @Override
    public void draw(McCanvas canvas, Element element, float x, float y, float width, float height) {
        Entity entity = entity();
        if (entity != null) EntityPortrait.draw(canvas, entity, pose(canvas, x, y, width, height), x, y, width, height);
    }

    /** The pose from the element's attributes and the pointer: the one place that decides how the entity stands. */
    private EntityPortrait.Pose pose(McCanvas canvas, float x, float y, float width, float height) {
        float gaze = 0, tilt = 0;
        if (element.hasAttribute("follow-mouse") && canvas.mouseX() >= 0) {
            // As vanilla's inventory: up to about ±30° toward a pointer 40 px away from the eyes.
            gaze = (float) Math.atan((x + width / 2 - canvas.mouseX()) / 40.0F) * 20.0F;
            tilt = (float) Math.atan((y + height / 3 - canvas.mouseY()) / 40.0F) * 20.0F;
        }
        return new EntityPortrait.Pose(number("rotate", 0) + gaze, gaze, tilt, number("scale", 1), 0, 0);
    }

    private @Nullable Entity entity() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;
        if (element.hasAttribute("player")) return mc.player;
        String id = element.getAttribute("id");
        if (id != null) {
            try {
                return level.getEntity(Integer.parseInt(id.strip()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (createdIn != level) { // once per world (and after the type changes), even when the type is unknown
            Identifier type = Identifier.tryParse(attr("type", "minecraft:pig"));
            created = type == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(type)
                    .map(t -> EntityPortrait.create(t, level)).orElse(null);
            createdIn = level;
        }
        return created;
    }
}
