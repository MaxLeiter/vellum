//? if >=26 {
package dev.vellum.mod.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The blocks of one scene group as a level the block and fluid renderers can read, so faces between neighbours are
 * culled and fluids meet their neighbours as in the world. Everything else is air. It has no light engine, and no
 * face shading: a scene is lit like the inventory, by the GUI's directional light, so nothing of the player's
 * dimension or time of day shows. Tints are a plains biome's (the world's, when one is loaded).
 */
final class SceneLevel implements BlockAndTintGetter {
    /** Faces keep their colour; the GUI light shades them. */
    static final CardinalLighting UNSHADED = new CardinalLighting(1, 1, 1, 1, 1, 1);
    /** Plains, for when no world is loaded. */
    private static final int GRASS = 0xFF91BD59, FOLIAGE = 0xFF77AB2F, DRY_FOLIAGE = 0xFFA08A67, WATER = 0xFF3F76E4;

    private final Map<BlockPos, BlockState> blocks;
    private final Map<BlockPos, BlockEntity> blockEntities;
    private final @Nullable Biome plains;
    private final int minY, height;

    SceneLevel(Map<BlockPos, BlockState> blocks, Map<BlockPos, BlockEntity> blockEntities) {
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        var level = Minecraft.getInstance().level;
        this.plains = level == null ? null
                : level.registryAccess().lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).map(h -> h.value()).orElse(null);
        int lo = 0, hi = 1;
        for (BlockPos p : blocks.keySet()) {
            lo = Math.min(lo, p.getY());
            hi = Math.max(hi, p.getY() + 1);
        }
        this.minY = lo - 1;
        this.height = hi - lo + 2;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return UNSHADED;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver color) {
        if (plains != null) return color.getColor(plains, pos.getX(), pos.getZ());
        if (color == BiomeColors.GRASS_COLOR_RESOLVER) return GRASS;
        if (color == BiomeColors.FOLIAGE_COLOR_RESOLVER) return FOLIAGE;
        if (color == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) return DRY_FOLIAGE;
        if (color == BiomeColors.WATER_COLOR_RESOLVER) return WATER;
        return -1;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = blocks.get(pos);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }
}
//?}
