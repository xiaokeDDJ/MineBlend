package mineblend;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import mineblend.mixin.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 生成游戏俯视地图图片：每列取顶面方块的真实贴图（含群系染色），并按高度差做地图式明暗。 */
final class TopView {
    record Result(byte[] png, int minH, int maxH) {}

    private record Top(NativeImage img, int w, int h, int tintIndex, boolean fluid, int avg) {}

    private static final Textures TEX = new Textures();
    private final BlockState[] palette;
    private final Top[] tops;
    private final Map<Long, Integer> tintCache = new HashMap<>();
    private final Map<Holder<Biome>, Integer> biomeIds = new HashMap<>();
    private final BiomeView view = new BiomeView();

    TopView(BlockState[] palette) {
        this.palette = palette;
        this.tops = new Top[palette.length];
        Minecraft mc = Minecraft.getInstance();
        RandomSource rnd = RandomSource.create(42L);
        for (int i = 1; i < palette.length; i++) {
            try {
                tops[i] = topOf(mc, palette[i], rnd);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 模型里剔除方向为 dir 的四边形（dir = null：不剔除的面） */
    private List<Models.Quad> quadsFacing(Object model, BlockState st, RandomSource rnd, Direction dir) {
        List<Models.Quad> out = new java.util.ArrayList<>();
        for (Models.Quad q : Models.blockQuads(model, view, BlockPos.ZERO, st, rnd, 42L)) if (q.cull == dir) out.add(q);
        return out;
    }

    private Top topOf(Minecraft mc, BlockState st, RandomSource rnd) {
        TextureAtlasSprite sp = null;
        int tint = -1;
        boolean fluid = false;
        FluidState fs = st.getFluidState();
        if (!fs.isEmpty() && !(st.getBlock() instanceof net.minecraft.world.level.block.SimpleWaterloggedBlock)) {
            TextureAtlasSprite[] s = Mc.fluidSprites(fs, view, BlockPos.ZERO);
            if (s != null) {
                if (s.length > 0) sp = s[0];
                fluid = true;
            }
        }
        if (sp == null) {
            Object model = Models.model(st);
            // 从朝上的面和无剔除面里挑最高、朝上的那个；跳过面积为 0 和缺失贴图的面（有些模组模型里有看不见的退化面）
            Models.Quad best = null;
            float bestY = -1e9f;
            for (int pass = 0; pass < 2 && best == null; pass++) {
                for (Direction d : new Direction[]{Direction.UP, null}) {
                    for (Models.Quad q : quadsFacing(model, st, rnd, d)) {
                        if (BlockMesher.degenerate(q)) continue;
                        if (pass == 0 && BlockMesher.isMissing(TEX.quadSprite(q))) continue;
                        float y = 0;
                        for (int i = 0; i < 4; i++) y += q.pos[i * 3 + 1];
                        if (q.face == Direction.UP) y += 100;
                        if (y > bestY) {
                            bestY = y;
                            best = q;
                        }
                    }
                }
            }
            if (best != null) {
                sp = TEX.quadSprite(best);
                if (best.tinted()) tint = best.tintIndex;
            } else {
                return null; // 空模型（如多格方块的 blank 部分）不画
            }
        }
        if (sp == null) return null;
        SpriteContents c = sp.contents();
        NativeImage img = ((SpriteContentsAccessor) c).mineblend$getImage();
        if (img == null) return null;
        int w = Math.min(c.width(), img.getWidth()), h = Math.min(c.height(), img.getHeight());
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = Mc.abgr(img, x, y);
                if ((p >>> 24) < 128) continue;
                r += p & 255;
                g += (p >> 8) & 255;
                b += (p >> 16) & 255;
                n++;
            }
        int avg = n == 0 ? 0xFF808080 : (0xFF << 24) | ((int) (b / n) << 16) | ((int) (g / n) << 8) | (int) (r / n);
        return new Top(img, w, h, fluid ? 0 : tint, fluid, avg);
    }

    private int tint(int pid, Holder<Biome> biome, int x, int z) {
        Top t = tops[pid];
        if (t == null || t.tintIndex < 0) return 0xFFFFFF;
        int bid = biomeIds.computeIfAbsent(biome, k -> biomeIds.size());
        long key = ((long) pid << 20) | bid;
        Integer c = tintCache.get(key);
        if (c != null) return c;
        view.biome = biome;
        BlockPos pos = new BlockPos(x, 64, z);
        int col = -1;
        try {
            if (t.fluid) {
                col = Mc.fluidColor(palette[pid].getFluidState(), view, pos);
            } else {
                col = Mc.blockTint(palette[pid], view, pos, t.tintIndex);
            }
        } catch (Throwable ignored) {
        }
        if (col == -1) col = 0xFFFFFF;
        tintCache.put(key, col);
        return col;
    }

    Result render(short[] heights, short[] ids, Holder<Biome>[] biomes, int nx, int nz, int step, int ppb, int minX, int minZ, int bottom) throws java.io.IOException {
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (short h : heights) {
            if (h == Short.MIN_VALUE || h < bottom) continue;
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
        }
        if (minH == Integer.MAX_VALUE) minH = maxH = 0;

        try (NativeImage out = new NativeImage(nx * ppb, nz * ppb, true)) {
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    int k = iz * nx + ix;
                    int hgt = heights[k];
                    Top t = hgt == Short.MIN_VALUE ? null : tops[ids[k] & 0xFFFF];
                    if (t == null) {
                        for (int j = 0; j < ppb; j++)
                            for (int i = 0; i < ppb; i++) Mc.setAbgr(out, ix * ppb + i, iz * ppb + j, 0);
                        continue;
                    }
                    int tint = tint(ids[k] & 0xFFFF, biomes[k], minX + ix * step, minZ + iz * step);
                    int north = iz > 0 && heights[k - nx] != Short.MIN_VALUE ? heights[k - nx] : hgt;
                    float shade = hgt > north ? 1.12f : hgt < north ? 0.80f : 1f;
                    float tr = ((tint >> 16) & 255) / 255f * shade, tg = ((tint >> 8) & 255) / 255f * shade, tb = (tint & 255) / 255f * shade;
                    for (int j = 0; j < ppb; j++) {
                        for (int i = 0; i < ppb; i++) {
                            int p = Mc.abgr(t.img, i * t.w / ppb, j * t.h / ppb);
                            if ((p >>> 24) < 128) p = t.avg;
                            int r = Math.min(255, (int) ((p & 255) * tr));
                            int g = Math.min(255, (int) (((p >> 8) & 255) * tg));
                            int b = Math.min(255, (int) (((p >> 16) & 255) * tb));
                            Mc.setAbgr(out, ix * ppb + i, iz * ppb + j, 0xFF000000 | (b << 16) | (g << 8) | r);
                        }
                    }
                }
            }
            return new Result(Mc.png(out), minH, maxH);
        }
    }

    /** 调色板第 pid 项的顶面贴图（ppb×ppb，RGBA8，未染色，透明像素用平均色补上）写入 out；没有可画的顶面返回 false */
    boolean tile(int pid, int ppb, byte[] out, int off) {
        Top t = pid < tops.length ? tops[pid] : null;
        if (t == null) return false;
        for (int j = 0; j < ppb; j++) {
            for (int i = 0; i < ppb; i++) {
                int p = Mc.abgr(t.img, i * t.w / ppb, j * t.h / ppb);  // ABGR
                if ((p >>> 24) < 128) p = t.avg;
                int o = off + (j * ppb + i) * 4;
                out[o] = (byte) (p & 255);
                out[o + 1] = (byte) ((p >> 8) & 255);
                out[o + 2] = (byte) ((p >> 16) & 255);
                out[o + 3] = (byte) 255;
            }
        }
        return true;
    }

    /** 调色板第 pid 项在该群系下的染色（0xRRGGBB；不染色的方块为白色） */
    int tintColor(int pid, Holder<Biome> biome) {
        return tint(pid, biome, 0, 0) & 0xFFFFFF;
    }

    /** 只提供群系颜色的视图（染色计算用） */
    private static final class BiomeView implements BlockAndTintGetter {
        Holder<Biome> biome;

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
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return biome == null ? 0xFFFFFF : resolver.getColor(biome.value(), pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return SnapshotView.AIR;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return SnapshotView.AIR.getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        //? if <1.21.2 {
        public int getMinBuildHeight() {
        //?} else {
        /*public int getMinY() {
        *///?}
            return -64;
        }
    }
}
