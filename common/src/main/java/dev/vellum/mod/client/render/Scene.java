package dev.vellum.mod.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/**
 * What a 3D picture ({@link GuiSceneRenderState}) shows, resolved for one frame: an entity, a block or an item,
 * posed, and submitted around the middle of the picture where a block is {@code scale} GUI px.
 *
 * <p>Pictures of the same still model are kept between frames ({@link #key}). Two scenes are equal when the same
 * owner shows the same still model, which an owner (an element) does at most once a frame: pools that match states
 * between frames (NeoForge's) hand such a picture the renderer that already holds it, and states in one frame stay
 * distinct. Scenes without a key (entities, animated items) are only equal to themselves.
 */
public final class Scene {
    /** How a picture is drawn: {@code pose} has the origin in its middle, y down, a block per unit. */
    @FunctionalInterface
    interface Drawer {
        void submit(PoseStack pose, SubmitNodeCollector out, GuiSceneRenderState picture);
    }

    /** The inventory's view of blocks: 30° from above, turned 225°, 0.625 of a slot. */
    private static final float BLOCK_TILT = 30, BLOCK_YAW = 225, BLOCK_SCALE = 0.625F;

    private final @Nullable Object owner;
    private final @Nullable Object key;
    private final Lighting.Entry lighting;
    private final Drawer drawer;

    private Scene(@Nullable Object owner, @Nullable Object key, Lighting.Entry lighting, Drawer drawer) {
        this.owner = owner;
        this.key = key;
        this.lighting = lighting;
        this.drawer = drawer;
    }

    /**
     * An entity standing on the bottom edge of the picture, centred, with its origin {@code lift} blocks above it;
     * {@code rotation} turns it, and the camera looks along {@code cameraTilt} (for billboards such as name tags).
     */
    public static Scene entity(EntityRenderState state, float lift, Quaternionfc rotation, @Nullable Quaternionfc cameraTilt) {
        return new Scene(null, null, Lighting.Entry.ENTITY_IN_UI, (pose, out, picture) -> {
            // As vanilla's GuiEntityRenderer, with the origin moved from the middle down to the bottom edge.
            pose.translate(0, (picture.y1() - picture.y0()) / 2F / picture.scale() - lift, 0);
            pose.rotate(rotation);
            CameraRenderState camera = new CameraRenderState();
            if (cameraTilt != null) camera.orientation = cameraTilt.conjugate(new Quaternionf()).rotateY((float) Math.PI);
            Minecraft.getInstance().getEntityRenderDispatcher().submit(state, camera, 0, 0, 0, pose, out);
        });
    }

    /**
     * A block model, as resolved by {@code BlockModelResolver}, as the inventory shows blocks (vanilla's
     * {@code block/block} GUI transform: 30° from above, turned 225°, 0.625 a box), turned by {@code yaw} about its
     * upright axis and seen from {@code pitch} degrees further above. {@code identity} changes when its model does
     * (the model manager's block model); {@code owner} draws it at most once a frame.
     */
    public static Scene block(Object owner, BlockModelRenderState state, Object identity, float yaw, float pitch) {
        return new Scene(owner, new ModelKey(identity, yaw, pitch), Lighting.Entry.ITEMS_3D, (pose, out, picture) -> {
            turn(pose, BLOCK_TILT, yaw, pitch);
            pose.rotateDegrees(Axis.YP, BLOCK_YAW);
            pose.scale(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE);
            pose.translate(-0.5F, -0.5F, -0.5F);
            state.submit(pose, out, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        });
    }

    /**
     * An item model resolved for the GUI, so at yaw and pitch 0 it is the inventory icon, turned by {@code yaw} about
     * its upright axis and seen from {@code pitch} degrees further above. Block-like items are tilted 30° toward the
     * viewer in the inventory, flat ones not, so that is the axis they turn about.
     */
    public static Scene item(Object owner, TrackingItemStackRenderState state, float yaw, float pitch) {
        Object key = state.isAnimated() ? null : new ModelKey(state.getModelIdentity(), yaw, pitch);
        boolean blockLike = state.usesBlockLight();
        float tilt = blockLike ? BLOCK_TILT : 0;
        return new Scene(owner, key, blockLike ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT, (pose, out, picture) -> {
            turn(pose, tilt, yaw, pitch);
            pose.rotateDegrees(Axis.XP, -tilt); // the item's GUI transform tilts it again
            state.submit(pose, out, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        });
    }

    private record ModelKey(Object model, float yaw, float pitch) {}

    /** Faces a GUI model to the viewer, y up, tilted {@code tilt + pitch} degrees from above, turned by yaw. */
    private static void turn(PoseStack pose, float tilt, float yaw, float pitch) {
        pose.scale(1, -1, -1); // y up and facing the viewer, as vanilla draws GUI items
        pose.rotateDegrees(Axis.XP, tilt + pitch);
        pose.rotateDegrees(Axis.YP, -yaw);
    }

    /** What the picture shows, for keeping it between frames; null when it must be drawn every frame. */
    @Nullable Object key() {
        return key;
    }

    void submit(PoseStack pose, SubmitNodeCollector out, GuiSceneRenderState picture) {
        Minecraft.getInstance().gameRenderer.lighting().setupFor(lighting);
        drawer.submit(pose, out, picture);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || key != null && o instanceof Scene s && s.owner == owner && key.equals(s.key);
    }

    @Override
    public int hashCode() {
        return key == null ? System.identityHashCode(this) : System.identityHashCode(owner) * 31 + key.hashCode();
    }
}
