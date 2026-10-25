//? if >=26 {
package dev.vellum.mod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.vellum.mod.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One scene group's blocks, meshed once and drawn as often as needed: the block models' quads as the world meshes
 * them (faces between neighbours culled, models picked by their states, fluids meeting their neighbours), kept as
 * plain vertex arrays, plus the render states of their block entities (chests, signs, banners, beds). Drawing it
 * only copies the vertices out, so a scene that moves or fades costs no model work.
 *
 * <p>Blocks are lit like the inventory's: full bright, no face shading or ambient occlusion baked in, and drawn with
 * the item render types so the GUI's directional light shades them.
 */
public final class SceneMesh {
    /** Floats per vertex: position, uv and normal. */
    private static final int FLOATS = 8;

    private final Vertices opaque = new Vertices(), translucent = new Vertices();
    private final List<PlacedState> blockEntities = new ArrayList<>();

    private record PlacedState(BlockPos pos, BlockEntityRenderState state) {}

    private SceneMesh() {}

    /**
     * Meshes {@code blocks} (their states by position) as one group, with their block entities
     * ({@link #blockEntity}) by position.
     */
    public static SceneMesh build(Map<BlockPos, BlockState> blocks, Map<BlockPos, BlockEntity> blockEntities) {
        Minecraft mc = Minecraft.getInstance();
        SceneMesh mesh = new SceneMesh();
        SceneLevel level = new SceneLevel(blocks, blockEntities);
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
        FluidRenderer fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
        BlockQuadOutput quads = (x, y, z, quad, instance) -> mesh.layer(quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos pos = e.getKey();
            BlockState state = e.getValue();
            try {
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    // The fluid renderer writes positions within the chunk section: move them back.
                    mesh.opaque.fluidAt(pos);
                    mesh.translucent.fluidAt(pos);
                    fluidRenderer.tesselate(level, pos, mesh::layer, state, fluid);
                    mesh.opaque.fluidAt(null);
                    mesh.translucent.fluidAt(null);
                }
                if (state.getRenderShape() == RenderShape.MODEL) {
                    blockRenderer.tesselateBlock(quads, pos.getX(), pos.getY(), pos.getZ(), level, pos, state,
                            mc.getModelManager().getBlockStateModelSet().get(state), state.getSeed(pos));
                }
                BlockEntity blockEntity = blockEntities.get(pos);
                BlockEntityRenderState rendered = blockEntity == null ? null : extract(mc.getBlockEntityRenderDispatcher(), blockEntity);
                if (rendered != null) mesh.blockEntities.add(new PlacedState(pos, rendered));
            } catch (RuntimeException ex) {
                Constants.LOG.warn("Vellum: cannot draw {} in a scene: {}", state, ex.toString());
            }
        }
        return mesh;
    }

    /**
     * A block entity for {@code state} at {@code pos}: loaded from {@code nbt} when given, else empty; null when the
     * block has none. It has no level, as block entities in item renders have none, so renderers draw it at rest.
     */
    public static @Nullable BlockEntity blockEntity(BlockPos pos, BlockState state, net.minecraft.nbt.@Nullable CompoundTag nbt) {
        if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock block)) return null;
        var level = Minecraft.getInstance().level;
        if (nbt != null && level != null) {
            net.minecraft.nbt.CompoundTag tag = nbt.copy();
            BlockEntity probe = block.newBlockEntity(pos, state);
            if (probe == null) return null;
            if (!tag.contains("id")) {
                tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(probe.getType()).toString());
            }
            BlockEntity loaded = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
            if (loaded != null) return loaded;
        }
        return block.newBlockEntity(pos, state);
    }

    /**
     * The block entity's render state, as its renderer makes one without a level, then corrected for what a level
     * would have told it: a chest's facing and half. Lit full bright.
     */
    private static <E extends BlockEntity, S extends BlockEntityRenderState> @Nullable S extract(BlockEntityRenderDispatcher dispatcher, E blockEntity) {
        BlockEntityRenderer<E, S> renderer = dispatcher.getRenderer(blockEntity);
        if (renderer == null || !blockEntity.getType().isValid(blockEntity.getBlockState())) return null;
        S state = renderer.createRenderState();
        renderer.extractRenderState(blockEntity, state, 0, Vec3.ZERO, null);
        BlockState block = blockEntity.getBlockState();
        if (state instanceof ChestRenderState chest && block.hasProperty(ChestBlock.FACING)) {
            chest.facing = block.getValue(ChestBlock.FACING);
            if (block.hasProperty(ChestBlock.TYPE)) chest.type = block.getValue(ChestBlock.TYPE);
        }
        state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
        return state;
    }

    private Vertices layer(ChunkSectionLayer layer) {
        return layer == ChunkSectionLayer.TRANSLUCENT ? translucent : opaque;
    }

    /** How many block and fluid quads it has. */
    public int quads() {
        return (opaque.count + translucent.count) / 4;
    }

    /** Whether it draws anything. */
    public boolean isEmpty() {
        return opaque.count == 0 && translucent.count == 0 && blockEntities.isEmpty();
    }

    /**
     * Submits it in {@code pose}'s space (block coordinates), its vertices' alpha times {@code alpha}. Faded, every
     * quad draws translucent; block entities draw only while it is at least half opaque, as items do.
     */
    public void submit(PoseStack pose, SubmitNodeCollector out, float alpha) {
        boolean faded = alpha < 1;
        RenderType solidType = faded ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet();
        if (opaque.count > 0) out.submitCustomGeometry(pose, solidType, (p, buffer) -> opaque.emit(p, buffer, alpha));
        if (translucent.count > 0) {
            out.submitCustomGeometry(pose, Sheets.translucentBlockItemSheet(), (p, buffer) -> translucent.emit(p, buffer, alpha));
        }
        if (alpha < 0.5F || blockEntities.isEmpty()) return;
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        CameraRenderState camera = new CameraRenderState();
        for (PlacedState be : blockEntities) {
            pose.pushPose();
            pose.translate(be.pos().getX(), be.pos().getY(), be.pos().getZ());
            dispatcher.submit(be.state(), pose, out, camera);
            pose.popPose();
        }
    }

    /**
     * Vertices as the block and fluid renderers write them, kept in arrays. Light and overlay are dropped (it is drawn
     * full bright); fluids get their faces' real normals, which the fluid renderer leaves pointing up.
     */
    private static final class Vertices implements VertexConsumer {
        float[] data = new float[FLOATS * 256];
        int[] colors = new int[256];
        int count;
        private @Nullable BlockPos fluid;

        /** Fluid vertices come within the chunk section of {@code pos}; null for block vertices. */
        void fluidAt(@Nullable BlockPos pos) {
            fluid = pos;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords,
                              float nx, float ny, float nz) {
            if (count == colors.length) {
                colors = Arrays.copyOf(colors, count * 2);
                data = Arrays.copyOf(data, count * 2 * FLOATS);
            }
            if (fluid != null) {
                x += fluid.getX() - (fluid.getX() & 15);
                y += fluid.getY() - (fluid.getY() & 15);
                z += fluid.getZ() - (fluid.getZ() & 15);
            }
            int i = count * FLOATS;
            data[i] = x;
            data[i + 1] = y;
            data[i + 2] = z;
            data[i + 3] = u;
            data[i + 4] = v;
            data[i + 5] = nx;
            data[i + 6] = ny;
            data[i + 7] = nz;
            colors[count++] = color;
            if (fluid != null && count % 4 == 0) faceNormal(count - 4);
        }

        /** Sets the four vertices from {@code first} to the normal of the quad they make. */
        private void faceNormal(int first) {
            int a = first * FLOATS, b = a + FLOATS, c = b + FLOATS;
            Vector3f e1 = new Vector3f(data[b] - data[a], data[b + 1] - data[a + 1], data[b + 2] - data[a + 2]);
            Vector3f e2 = new Vector3f(data[c] - data[a], data[c + 1] - data[a + 1], data[c + 2] - data[a + 2]);
            Vector3f n = e1.cross(e2);
            if (n.lengthSquared() < 1e-12F) return;
            n.normalize();
            for (int v = 0; v < 4; v++) {
                int i = (first + v) * FLOATS;
                data[i + 5] = n.x;
                data[i + 6] = n.y;
                data[i + 7] = n.z;
            }
        }

        /** Copies the vertices into {@code buffer}, through {@code pose}, the alpha times {@code alpha}. */
        void emit(PoseStack.Pose pose, VertexConsumer buffer, float alpha) {
            Matrix4f m = pose.pose();
            Vector3f p = new Vector3f(), n = new Vector3f();
            int full = LightCoordsUtil.FULL_BRIGHT, overlay = OverlayTexture.NO_OVERLAY;
            for (int v = 0; v < count; v++) {
                int i = v * FLOATS;
                m.transformPosition(data[i], data[i + 1], data[i + 2], p);
                pose.transformNormal(data[i + 5], data[i + 6], data[i + 7], n);
                int color = colors[v];
                if (alpha < 1) color = (Math.round((color >>> 24) * alpha) << 24) | (color & 0xFFFFFF);
                buffer.addVertex(p.x, p.y, p.z, color, data[i + 3], data[i + 4], overlay, full, n.x, n.y, n.z);
            }
        }

        // Only the call above is used by the block and fluid renderers; the rest keep the interface whole.

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            addVertex(x, y, z, -1, 0, 0, 0, 0, 0, 1, 0);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor(a << 24 | r << 16 | g << 8 | b);
        }

        @Override
        public VertexConsumer setColor(int color) {
            if (count > 0) colors[count - 1] = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (count > 0) {
                data[(count - 1) * FLOATS + 3] = u;
                data[(count - 1) * FLOATS + 4] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        //? if >=26.3 {
        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }
        //?}

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (count > 0) {
                int i = (count - 1) * FLOATS;
                data[i + 5] = x;
                data[i + 6] = y;
                data[i + 7] = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
//?}
