package mineblend;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * 选区（外扩 1 格）的方块快照，可在任意线程上安全读取，用于剔除相邻面和计算群系染色。
 * 坐标为世界坐标；快照范围外一律视为空气。
 */
final class SnapshotView implements BlockAndTintGetter {
    static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final int x0, y0, z0, sx, sy, sz;      // 含外扩的范围
    final short[] ids;                     // 调色板索引，order: ((y*sz)+z)*sx+x
    final BlockState[] palette;
    final Holder<Biome>[] biomes;   // 每 4×4 列一个（含外扩）
    final int bx, bz;                      // biomes 网格尺寸
    final int bottomY, height;
    final LevelLightEngine light;

    SnapshotView(int x0, int y0, int z0, int sx, int sy, int sz, short[] ids, BlockState[] palette,
                 Holder<Biome>[] biomes, int bx, int bz, int bottomY, int height, LevelLightEngine light) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.ids = ids;
        this.palette = palette;
        this.biomes = biomes;
        this.bx = bx;
        this.bz = bz;
        this.bottomY = bottomY;
        this.height = height;
        this.light = light;
    }

    BlockState state(int x, int y, int z) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return AIR;
        return palette[ids[(ly * sz + lz) * sx + lx] & 0xFFFF];
    }

    Holder<Biome> biome(int x, int z) {
        int cx = Math.max(0, Math.min(bx - 1, (x - x0) >> 2));
        int cz = Math.max(0, Math.min(bz - 1, (z - z0) >> 2));
        return biomes[cz * bx + cx];
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return state(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    //? if <1.21.2 {
    public int getMinBuildHeight() {
    //?} else {
    /*public int getMinY() {
    *///?}
        return bottomY;
    }

    //? if <26.1 {
    @Override
    public float getShade(Direction direction, boolean shaded) {
        return 1f;
    }
    //?} else {
    /*@Override
    public net.minecraft.world.level.CardinalLighting cardinalLighting() {
        return net.minecraft.world.level.CardinalLighting.DEFAULT;
    }
    *///?}

    @Override
    public LevelLightEngine getLightEngine() {
        return light;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        Holder<Biome> b = biome(pos.getX(), pos.getZ());
        if (b == null) return 0xFFFFFF;
        return resolver.getColor(b.value(), pos.getX(), pos.getZ());
    }
}
