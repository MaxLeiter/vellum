package dev.vellum.mod.client.replaced;

import dev.vellum.engine.dom.Element;
import dev.vellum.mod.client.render.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/**
 * {@code <sprite src="minecraft:widget/button">}: a GUI-atlas sprite at its natural size, honouring its own
 * scaling (stretch, tile, nine-slice) when CSS resizes it.
 */
final class SpriteContent extends McReplaced {
    SpriteContent(Element element) {
        super(element);
    }

    @Override
    public float intrinsicWidth() {
        return size(attr("src", ""))[0];
    }

    @Override
    public float intrinsicHeight() {
        return size(attr("src", ""))[1];
    }

    @Override
    public void draw(McCanvas canvas, Element element, float x, float y, float width, float height) {
        canvas.drawSprite(attr("src", ""), x, y, width, height, -1);
    }

    /** {width, height} of a GUI sprite in px, or NaN when there is no such sprite. */
    static float[] size(String spriteId) {
        Identifier id = Identifier.tryParse(spriteId);
        if (id != null) {
            TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getSprite(id);
            if (sprite.contents().name().equals(id)) return new float[] {sprite.contents().width(), sprite.contents().height()};
        }
        return new float[] {Float.NaN, Float.NaN};
    }
}
