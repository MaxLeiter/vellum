package dev.vellum.mod.client;

import dev.vellum.engine.host.FontSpec;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.replaced.EntityPortrait;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /vellum canvastest}: exercises {@link McCanvas} directly, without the engine, so the Minecraft backend can
 * be checked on its own. Each cell is one feature: rectangles (fractional and hairline), per-vertex-colour quads and
 * triangles (in both windings), text sizes and styles, fonts, nine-slice sprites, textures, items, nested clipping,
 * transforms, opacity, the player's face, and several entities in one frame.
 */
final class CanvasTestScreen extends Screen {
    private static final int CELL_W = 104, CELL_H = 74;
    private static final int LABEL = 0xFFA0A0A0;
    private static final FontSpec SMALL = font("minecraft:default", 8, false, false);

    private record Cell(String name, Consumer<McCanvas> draw) {}

    private final List<Cell> cells = List.of(new Cell("rects", this::rects), new Cell("quads", this::quads),
            new Cell("text", this::text), new Cell("fonts", this::fonts), new Cell("sprites", this::sprites),
            new Cell("texture", this::textures), new Cell("items", this::items), new Cell("clip", this::clipping),
            new Cell("transform", this::transforms), new Cell("alpha", this::opacity), new Cell("face", this::face),
            new Cell("border", this::borders), new Cell("entities", this::entities));
    private @Nullable Entity pig;

    CanvasTestScreen() {
        super(Component.literal("Vellum canvas test"));
    }

    private static FontSpec font(String family, float size, boolean bold, boolean italic) {
        return new FontSpec(List.of(family), size, bold, italic);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        McCanvas c = new McCanvas(g, mouseX, mouseY);
        try {
            int columns = Math.max(1, (width - 4) / CELL_W);
            for (int i = 0; i < cells.size(); i++) {
                c.save();
                try {
                    c.translate(4 + (i % columns) * CELL_W, 4 + (i / columns) * CELL_H);
                    c.fillRect(0, 0, CELL_W - 4, CELL_H - 4, 0xC0101018);
                    c.drawText(cells.get(i).name(), 3, 3, SMALL, LABEL, 0, false);
                    c.translate(3, 14);
                    cells.get(i).draw().accept(c);
                } finally {
                    c.restoreToCount(0);
                }
            }
        } finally {
            c.finish();
        }
    }

    private void rects(McCanvas c) {
        c.fillRect(0, 0, 20, 20, 0xFFE04040);
        c.fillRect(24.5f, 0.5f, 20, 20, 0xFF40E040); // half-pixel offset
        c.fillRect(49.25f, 0, 20.5f, 20.75f, 0xFF4040E0);
        float px = c.devicePixel();
        for (int i = 0; i < 8; i++) c.fillRect(0, 26 + i * 4 * px, 90, px, 0xFFFFFFFF); // device-pixel hairlines
        c.fillRect(74, 0, 20, 20, 0x80FFFF00); // translucent
    }

    private void quads(McCanvas c) {
        // A gradient quad (one colour per corner), a triangle (last vertex repeated), and a quad wound the other way.
        c.fillQuads(new float[] {0, 0, 0, 40, 30, 40, 30, 0}, new int[] {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF}, 1);
        c.fillQuads(new float[] {35, 40, 50, 0, 65, 40, 65, 40}, new int[] {0xFFFFC000, 0xFFFF4000, 0xFF8000FF, 0xFF8000FF}, 1);
        c.fillQuads(new float[] {70, 0, 95, 0, 95, 40, 70, 40}, new int[] {0xFF00FFFF, 0xFF00FFFF, 0xFF004040, 0xFF004040}, 1);
    }

    private void text(McCanvas c) {
        c.drawText("Vellum 8px", 0, 0, SMALL, 0xFFFFFFFF, 0, true);
        c.drawText("Bold 12", 0, 10, font("minecraft:default", 12, true, false), 0xFFFFD050, 0, true);
        c.drawText("Italic 16", 0, 24, font("minecraft:default", 16, false, true), 0xFF80C0FF, Canvas.UNDERLINE, false);
        c.drawText("strike", 0, 44, SMALL, 0xFFFF8080, Canvas.STRIKETHROUGH, false);
        c.drawText("x.5", 50.5f, 44.5f, SMALL, 0xFFFFFFFF, 0, false);
    }

    private void fonts(McCanvas c) {
        c.drawText("uniform", 0, 0, font("monospace", 8, false, false), 0xFFFFFFFF, 0, false);
        c.drawText("alt", 0, 12, font("minecraft:alt", 8, false, false), 0xFFFFFFFF, 0, false);
        c.drawText("illager", 0, 24, font("minecraft:illageralt", 8, false, false), 0xFFFFFFFF, 0, false);
        c.drawText("unknown→default", 0, 36, font("nope:nothing", 8, false, false), 0xFFFFFFFF, 0, false);
        c.drawText("50% alpha", 0, 48, SMALL, 0x80FFFFFF, 0, false);
    }

    private void sprites(McCanvas c) {
        c.drawSprite("minecraft:widget/button", 0, 0, 92, 20, -1);
        c.drawText("Button", 46 - Minecraft.getInstance().font.width("Button") / 2f, 6, SMALL, 0xFFFFFFFF, 0, true);
        c.drawSprite("minecraft:widget/button_highlighted", 0, 22, 44.5f, 14, -1);
        c.drawSprite("minecraft:widget/text_field", 48, 22, 44, 14, -1);
        c.drawSprite("minecraft:widget/checkbox_selected", 0, 39, 17, 17, -1);
        c.drawSprite("minecraft:container/slot", 22, 39, 18, 18, 0xFF80FF80);
    }

    private void textures(McCanvas c) {
        Identifier ore = Identifier.withDefaultNamespace("textures/block/diamond_ore.png");
        c.blit(ore, 0, 0, 32, 32, 0, 0, 1, 1, -1, false);
        c.blit(ore, 36, 0, 32, 32, 0, 0, 1, 1, 0xFFFF8040, false); // tinted
        c.blit(ore, 72, 0, 20, 20, 0.25f, 0.25f, 0.75f, 0.75f, -1, false); // UV sub-region
        c.drawImage("minecraft:textures/block/oak_planks.png", 0, 36, 92, 16, 0, 0, 92 / 16f, 1, -1, false); // repeat
    }

    private void items(McCanvas c) {
        c.drawItem(new ItemStack(Items.DIAMOND_SWORD), 0, 0, 16, true);
        c.drawItem(new ItemStack(Items.DIAMOND_SWORD), 20, 0, 32, true);
        c.drawItem(new ItemStack(Items.COBBLESTONE, 64), 56, 0, 16, true);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() / 2);
        c.drawItem(pick, 76, 0, 16, true);
        c.drawItem(new ItemStack(Items.GOLDEN_APPLE), 56.5f, 20.5f, 24, false);
    }

    private void clipping(McCanvas c) {
        c.save();
        c.clipRect(0, 0, 60, 44);
        c.fillQuads(new float[] {-20, -20, -20, 80, 120, 80, 120, -20}, new int[] {0xFF203080, 0xFF802030, 0xFF208030, 0xFF808020}, 1);
        c.drawText("clipped text that runs on", 2, 2, SMALL, 0xFFFFFFFF, 0, false);
        c.save();
        c.clipRect(20, 14, 60, 20); // intersects the outer clip
        c.fillRect(0, 0, 100, 60, 0xC0FFFFFF);
        c.restore();
        c.restore();
        c.fillRect(64, 0, 30, 44, 0xFF404040); // after restore: not clipped
    }

    private void transforms(McCanvas c) {
        c.save();
        c.translate(46, 19);
        c.transform((float) Math.cos(0.3), (float) Math.sin(0.3), (float) -Math.sin(0.3), (float) Math.cos(0.3), 0, 0);
        c.fillRect(-34, -9, 68, 18, 0xFF305070);
        c.drawSprite("minecraft:widget/button", -31, -7, 30, 14, -1);
        c.drawText("rotated", 2, -4, SMALL, 0xFFFFFFFF, 0, true);
        c.restore();
        c.save();
        c.translate(2, 40);
        c.transform(1.5f, 0, 0, 1.5f, 0, 0);
        c.drawText("scaled 1.5", 0, 0, SMALL, 0xFFFFE080, 0, false);
        c.restore();
    }

    private void opacity(McCanvas c) {
        c.save();
        c.multiplyAlpha(0.5f);
        c.fillRect(0, 0, 60, 40, 0xFFE04040);
        c.drawText("50%", 4, 4, SMALL, 0xFFFFFFFF, 0, false);
        c.drawItem(new ItemStack(Items.EMERALD), 36, 4, 16, false); // shown at exactly 0.5
        c.save();
        c.multiplyAlpha(0.5f);
        c.fillRect(20, 20, 60, 30, 0xFF40E040);
        c.drawText("25%", 24, 30, SMALL, 0xFFFFFFFF, 0, false);
        c.drawItem(new ItemStack(Items.EMERALD), 60, 30, 16, false); // hidden: items can't fade
        c.restore();
        c.restore();
    }

    private void face(McCanvas c) {
        Minecraft mc = Minecraft.getInstance();
        Identifier skin = mc.playerSkinRenderCache().getOrDefault(ResolvableProfile.createResolved(mc.getGameProfile())).playerSkin().body().texturePath();
        float px = 1 / 64f;
        c.blit(skin, 0, 0, 32, 32, 8 * px, 8 * px, 16 * px, 16 * px, -1, false);
        c.blit(skin, 0, 0, 32, 32, 40 * px, 8 * px, 48 * px, 16 * px, -1, false);
        c.blit(skin, 40, 8, 16, 16, 8 * px, 8 * px, 16 * px, 16 * px, -1, false);
    }

    private void borders(McCanvas c) {
        c.fillBorder(new float[] {0, 0, 44, 30}, new float[8], new float[] {2, 4, 6, 8},
                new int[] {0xFFE04040, 0xFF40E040, 0xFF4040E0, 0xFFE0E040});
        c.fillRoundedRect(50, 0, 40, 30, new float[] {6, 6, 6, 6, 6, 6, 6, 6}, 0xFF8060C0);
        c.fillBorder(new float[] {0, 34, 90, 20}, new float[8], new float[] {2, 2, 2, 2},
                new int[] {0xFFFFFFFF, 0xFF555555, 0xFF555555, 0xFFFFFFFF}); // a vanilla-style bevel
    }

    /** Several entities of one type in one frame: each needs its own picture-in-picture renderer. */
    private void entities(McCanvas c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (pig == null) pig = EntityPortrait.create(EntityTypes.PIG, mc.level);
        if (pig == null) return;
        EntityPortrait.draw(c, pig, EntityPortrait.Pose.FRONT, -1, 0, 0, 30, 40);
        EntityPortrait.draw(c, pig, new EntityPortrait.Pose(60, 0, 0, 0, 1, 0), -1, 30, 0, 30, 40);
        EntityPortrait.draw(c, mc.player, new EntityPortrait.Pose(-30, 0, 20, 10, 1, 0), -1, 62, 0, 30, 56);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
