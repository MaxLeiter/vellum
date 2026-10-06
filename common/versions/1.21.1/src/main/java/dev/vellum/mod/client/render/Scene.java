package dev.vellum.mod.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/**
 * What a 3D picture shows ({@code <entity>}, {@code <model>}): an entity, a block or an item, posed, and drawn around
 * the middle of its box where a block is {@code scale} GUI px. On Minecraft 1.21.1 there are no picture-in-picture
 * renderers: {@link McGui#scene} draws a scene straight into the GUI, clipped to its box, as vanilla's inventory
 * draws the player. The transforms are the 26.x Scene's.
 */
public final class Scene {
    /** How a scene is drawn: {@code pose} has the origin in the middle of the box, y down, a block per unit. */
    @FunctionalInterface
    interface Drawer {
        void draw(PoseStack pose, MultiBufferSource buffers, Box box);
    }

    /** The box on screen, in GUI px, and how many GUI px a block is. */
    record Box(int x0, int y0, int x1, int y1, float scale) {}

    /** The lighting vanilla sets for each kind of GUI model. */
    enum Light { ENTITY, ITEMS_3D, ITEMS_FLAT }

    /** The inventory's view of blocks: 30° from above, turned 225°, 0.625 of a slot. */
    private static final float BLOCK_TILT = 30, BLOCK_YAW = 225, BLOCK_SCALE = 0.625F;

    private final Light light;
    private final Drawer drawer;

    private Scene(Light light, Drawer drawer) {
        this.light = light;
        this.drawer = drawer;
    }

    /**
     * An entity, posed as it is now (the caller sets its fields and puts them back), with its origin {@code dx}
     * blocks right of the middle of the box's bottom edge and {@code up} blocks above it; {@code rotation} turns it,
     * and the camera looks along {@code cameraTilt} (for billboards such as name tags).
     */
    public static Scene entity(Entity entity, float partialTick, float dx, float up, Quaternionfc rotation, @Nullable Quaternionfc cameraTilt) {
        return new Scene(Light.ENTITY, (pose, buffers, box) -> {
            // As vanilla's inventory player, with the origin moved from the middle to the bottom edge, then placed.
            pose.translate(dx, (box.y1() - box.y0()) / 2F / box.scale() - up, 0);
            pose.mulPose(new Quaternionf(rotation));
            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            if (cameraTilt != null) dispatcher.overrideCameraOrientation(cameraTilt.conjugate(new Quaternionf()).rotateY((float) Math.PI));
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(entity, 0, 0, 0, 0, partialTick, pose, buffers, LightTexture.FULL_BRIGHT));
            dispatcher.setRenderShadow(true);
        });
    }

    /**
     * A block, as the inventory shows blocks (vanilla's {@code block/block} GUI transform: 30° from above, turned 225°,
     * 0.625 a box), turned by {@code yaw} about its upright axis and seen from {@code pitch} degrees further above, its
     * centre {@code dx} blocks right of the box's middle and {@code dy} below.
     */
    public static Scene block(BlockState state, float yaw, float pitch, float dx, float dy) {
        return new Scene(Light.ITEMS_3D, (pose, buffers, box) -> {
            pose.translate(dx, dy, 0);
            turn(pose, BLOCK_TILT, yaw, pitch);
            pose.mulPose(Axis.YP.rotationDegrees(BLOCK_YAW));
            pose.scale(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE);
            pose.translate(-0.5F, -0.5F, -0.5F);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        });
    }

    /**
     * An item in its GUI transform, so at yaw and pitch 0 it is the inventory icon, turned by {@code yaw} about its
     * upright axis and seen from {@code pitch} degrees further above, its centre {@code dx} blocks right of the box's
     * middle and {@code dy} below. Block-like items are tilted 30° toward the viewer in the inventory, flat ones not, so
     * that is the axis they turn about.
     */
    public static Scene item(ItemStack stack, float yaw, float pitch, float dx, float dy) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, null, 0);
        boolean blockLike = model.usesBlockLight();
        float tilt = blockLike ? BLOCK_TILT : 0;
        return new Scene(blockLike ? Light.ITEMS_3D : Light.ITEMS_FLAT, (pose, buffers, box) -> {
            pose.translate(dx, dy, 0);
            turn(pose, tilt, yaw, pitch);
            pose.mulPose(Axis.XP.rotationDegrees(-tilt)); // the item's GUI transform tilts it again
            mc.getItemRenderer().render(stack, ItemDisplayContext.GUI, false, pose, buffers, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, model);
        });
    }

    /** Faces a GUI model to the viewer, y up, tilted {@code tilt + pitch} degrees from above, turned by yaw. */
    private static void turn(PoseStack pose, float tilt, float yaw, float pitch) {
        pose.scale(1, -1, -1); // y up and facing the viewer, as vanilla draws GUI items
        pose.mulPose(Axis.XP.rotationDegrees(tilt + pitch));
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
    }

    /** Draws the scene with its lighting; {@code pose} is set up for the box ({@link McGui#scene}). */
    void draw(PoseStack pose, MultiBufferSource buffers, Box box) {
        switch (light) {
            case ENTITY -> Lighting.setupForEntityInInventory();
            case ITEMS_3D -> Lighting.setupFor3DItems();
            case ITEMS_FLAT -> Lighting.setupForFlatItems();
        }
        drawer.draw(pose, buffers, box);
    }
}
