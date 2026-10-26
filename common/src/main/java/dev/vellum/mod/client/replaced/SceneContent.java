//? if >=26 {
package dev.vellum.mod.client.replaced;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vellum.engine.dom.Element;
import dev.vellum.engine.paint.Canvas;
import dev.vellum.engine.scene.SceneBlock;
import dev.vellum.engine.scene.SceneCamera;
import dev.vellum.engine.scene.SceneEntity;
import dev.vellum.engine.scene.SceneGroup;
import dev.vellum.engine.scene.SceneModel;
import dev.vellum.mod.Constants;
import dev.vellum.mod.client.render.McCanvas;
import dev.vellum.mod.client.render.McGui;
import dev.vellum.mod.client.render.Scene;
import dev.vellum.mod.client.render.SceneMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code <scene>}: a small world of blocks and entities in a box, as its {@link SceneModel} describes it, drawn as one
 * picture. Each block group is meshed once ({@link SceneMesh}) and again only when its blocks change or resources
 * reload; showing, fading and moving groups and turning the camera only draws the meshes again. A scene without
 * entities that did not change since the last frame keeps its picture and costs a blit. The hovered block's name is
 * its tooltip.
 */
public final class SceneContent extends dev.vellum.engine.scene.SceneContent implements OwnTooltip {
    /** How deep the picture's depth range may be used, in device px either way (vanilla's is ±1000). */
    private static final float DEPTH = 900;

    private final SceneModel model = scene();
    /** Block states by their text; empty for text that names no block. */
    private final Map<String, Optional<BlockState>> states = new HashMap<>();
    private final Map<SceneGroup, Built> meshes = new IdentityHashMap<>();
    private final Set<String> warned = new HashSet<>();
    /** Changes whenever a mesh is rebuilt, so the kept picture goes too. */
    private int generation;
    private int drawnQuads;

    /** A group's mesh, and what it was built from. */
    private record Built(int version, Object models, SceneMesh mesh) {}

    /** What a picture without entities shows: equal keys draw the same. */
    private record Key(int generation, SceneCamera camera, List<Float> groups, float depthScale) {}

    SceneContent(Element element) {
        super(element);
    }

    /** How many block quads the last frame drew (for the dev autopilot's checks). */
    public int drawnQuads() {
        return drawnQuads;
    }

    @Override
    public boolean showTooltip(GuiGraphicsExtractor g, List<Component> extra, int x, int y) {
        SceneBlock block = model.tooltipBlock();
        BlockState state = block == null ? null : state(block.state());
        if (state == null) return false;
        List<Component> lines = new ArrayList<>();
        lines.add(state.getBlock().getName());
        lines.addAll(extra);
        McGui.componentTooltip(g, Minecraft.getInstance().font, lines, x, y);
        return true;
    }

    @Override
    public void dispose() {
        meshes.clear();
    }

    @Override
    public void paint(Canvas c, float x, float y, float width, float height) {
        if (!(c instanceof McCanvas canvas)) return;
        SceneCamera camera = model.camera();
        int tint = model.element().computedStyle().tint;
        if (camera == null || camera.pixelsPerBlock() <= 0 || !canvas.sceneVisible(tint, x, y, width, height)) return;
        updateMeshes();
        float[] m = camera.matrix();
        // The view's depth, in device px either way of the centre, squeezed to fit the picture's depth range.
        float[] b = model.bounds();
        float depth = 0;
        for (int corner = 0; corner < 8; corner++) {
            float cx = (corner & 1) == 0 ? b[0] : b[3], cy = (corner & 2) == 0 ? b[1] : b[4], cz = (corner & 4) == 0 ? b[2] : b[5];
            depth = Math.max(depth, Math.abs(m[8] * cx + m[9] * cy + m[10] * cz + m[11]) + camera.pixelsPerBlock());
        }
        float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float depthScale = Math.min(1, DEPTH / Math.max(1, depth * guiScale));
        Matrix4f view = new Matrix4f(
                m[0], m[4], m[8] * depthScale, 0,
                m[1], m[5], m[9] * depthScale, 0,
                m[2], m[6], m[10] * depthScale, 0,
                m[3] - width / 2, m[7] - height / 2, m[11] * depthScale, 1);
        List<SceneGroup> groups = model.groups();
        List<SceneEntity> entities = model.entities();
        drawnQuads = 0;
        for (SceneGroup g : groups) {
            Built built = meshes.get(g);
            if (g.visible() && built != null) drawnQuads += built.mesh().quads();
        }
        Object key = entities.isEmpty() ? new Key(generation, camera, groupState(groups), depthScale) : null;
        canvas.drawScene(Scene.of(this, key, (pose, out, picture) -> {
            pose.mulPose(view);
            submitGroups(groups, pose, out);
            submitEntities(entities, pose, out);
        }), 1, tint, x, y, width, height);
    }

    /** Everything about the groups that shows in the picture, for its key. */
    private static List<Float> groupState(List<SceneGroup> groups) {
        List<Float> out = new ArrayList<>();
        for (SceneGroup g : groups) {
            out.add(g.visible() ? g.opacity() : -1);
            if (g.moved()) for (float f : g.matrix()) out.add(f);
        }
        return out;
    }

    private void submitGroups(List<SceneGroup> groups, PoseStack pose, SubmitNodeCollector out) {
        for (SceneGroup g : groups) {
            Built built = meshes.get(g);
            if (!g.visible() || built == null || built.mesh().isEmpty()) continue;
            pose.pushPose();
            if (g.moved()) {
                float[] t = g.matrix();
                pose.mulPose(new Matrix4f(t[0], t[4], t[8], 0, t[1], t[5], t[9], 0, t[2], t[6], t[10], 0, t[3], t[7], t[11], 1));
            }
            built.mesh().submit(pose, out, g.opacity());
            pose.popPose();
        }
    }

    private static void submitEntities(List<SceneEntity> entities, PoseStack pose, SubmitNodeCollector out) {
        if (entities.isEmpty()) return;
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        CameraRenderState camera = new CameraRenderState();
        for (SceneEntity e : entities) {
            if (!(e.element().replaced instanceof EntityContent content) || e.element().computedStyle().opacity < 0.5F) continue;
            EntityRenderState state = content.sceneState();
            if (state == null) continue;
            dispatcher.submit(state, camera, e.x(), e.y(), e.z(), pose, out);
        }
    }

    /** Meshes the groups whose blocks changed since they were last meshed, or all of them after a resource reload. */
    private void updateMeshes() {
        Object models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        List<SceneGroup> groups = model.groups();
        meshes.keySet().retainAll(new HashSet<>(groups));
        for (SceneGroup g : groups) {
            Built built = meshes.get(g);
            if (built != null && built.version() == g.version() && built.models() == models) continue;
            meshes.put(g, new Built(g.version(), models, mesh(g)));
            generation++;
        }
    }

    private SceneMesh mesh(SceneGroup group) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        for (SceneBlock b : group.blocks()) {
            BlockState state = state(b.state());
            if (state == null) continue;
            BlockPos pos = new BlockPos(b.x(), b.y(), b.z());
            blocks.put(pos, state);
            CompoundTag nbt = b.nbt() == null ? null : ItemStacks.snbt(b.nbt(), "<block nbt> at " + pos.toShortString());
            BlockEntity blockEntity = SceneMesh.blockEntity(pos, state, nbt);
            if (blockEntity != null) blockEntities.put(pos, blockEntity);
        }
        return SceneMesh.build(blocks, blockEntities);
    }

    /** The block state {@code text} names, or null (logged once) when it names none. */
    private @Nullable BlockState state(String text) {
        return states.computeIfAbsent(text, t -> {
            try {
                return Optional.of(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, t, false).blockState());
            } catch (CommandSyntaxException e) {
                if (warned.add(t)) Constants.LOG.warn("Vellum: <scene> has an unknown block state {}: {}", t, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    @Override
    public String toString() {
        return "scene " + Arrays.toString(model.bounds());
    }
}
//?}
