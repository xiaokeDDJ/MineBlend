package mineblend;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 用游戏里真实的烘焙模型（含模组方块）生成方块网格；剔除规则与游戏相同（Block.shouldDrawSide）。 */
final class BlockMesher {
    static final Direction[] DIRS = Direction.values();
    /** 单位立方体每个面的 4 个角点（逆时针 = 朝外），按 Direction.ordinal(): DOWN UP NORTH SOUTH WEST EAST */
    static final float[][] FACE = {
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
            {0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0},
            {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0},
            {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1},
    };

    record Opts(boolean plants, boolean fluids, boolean blockEntities, boolean entities, boolean mobs, boolean cullBoundary) {}

    private final SnapshotView view;
    private final MeshOut out;
    private final Textures tex;
    private final Opts opts;
    private final int ox, oy, oz; // 选区最小角（世界坐标）

    private final Map<Long, Integer> tintCache = new HashMap<>();
    private final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>();
    private final float[] xyz = new float[12];
    private final float[] uvs = new float[8];

    int failed;

    BlockMesher(SnapshotView view, MeshOut out, Textures tex, Opts opts, int ox, int oy, int oz) {
        this.view = view;
        this.out = out;
        this.tex = tex;
        this.opts = opts;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    /** 四边形面积几乎为 0（某个方向厚度为 0 的元素），游戏中不可见 */
    static boolean degenerate(Models.Quad q) {
        float[] p = q.pos;
        // 两个三角形的面积之和
        double a = triArea(p, 0, 1, 2) + triArea(p, 0, 2, 3);
        return a < 1e-6;
    }

    private static double triArea(float[] p, int a, int b, int c) {
        double ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
        double vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
        double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) / 2;
    }

    static boolean isMissing(TextureAtlasSprite sp) {
        return sp == null || sp.contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
    }

    static boolean isPlant(Block b) {
        return b instanceof BushBlock || b instanceof GrowingPlantBlock || b instanceof SugarCaneBlock || b instanceof WebBlock;
    }

    void run(int sx, int sy, int sz) {
        BlockState[] pal = view.palette;
        int n = pal.length;
        String[] blockIds = new String[n];
        String[] fluidIds = new String[n];
        for (int i = 0; i < n; i++) {
            blockIds[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(pal[i].getBlock()).toString();
            FluidState f = pal[i].getFluidState();
            fluidIds[i] = f.isEmpty() ? blockIds[i]
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(f.createLegacyBlock().getBlock()).toString();
        }
        boolean[] opaque = new boolean[n];
        Object[] models = new Object[n];
        int[] layers = new int[n];
        for (int i = 0; i < n; i++) {
            BlockState st = pal[i];
            try {
                opaque[i] = !st.isAir() && Mc.solidRender(st, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (Throwable t) {
                opaque[i] = false;
            }
            if (st.getRenderShape() == RenderShape.MODEL) {
                models[i] = Models.model(st);
                layers[i] = Mc.layerKind(st);
            }
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos npos = new BlockPos.MutableBlockPos();
        RandomSource rnd = RandomSource.create();
        short[] ids = view.ids;
        int psx = view.sx, psz = view.sz;
        int strideY = psx * psz;

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int idx = ((y + 1) * psz + (z + 1)) * psx + (x + 1);
                    int pid = ids[idx] & 0xFFFF;
                    BlockState st = pal[pid];
                    if (st.isAir()) continue;
                    if (opaque[pid]
                            && opaque[ids[idx + 1] & 0xFFFF] && opaque[ids[idx - 1] & 0xFFFF]
                            && opaque[ids[idx + psx] & 0xFFFF] && opaque[ids[idx - psx] & 0xFFFF]
                            && opaque[ids[idx + strideY] & 0xFFFF] && opaque[ids[idx - strideY] & 0xFFFF]) continue;

                    int wx = ox + x, wy = oy + y, wz = oz + z;
                    pos.set(wx, wy, wz);
                    try {
                        FluidState fs = st.getFluidState();
                        if (opts.fluids() && !fs.isEmpty()) {
                            out.at(fluidIds[pid], x, y, z);
                            fluid(fs, pos, npos, x, y, z);
                        }
                        out.at(blockIds[pid], x, y, z);

                        Object model = models[pid];
                        if (model == null) continue;
                        if (Models.isRandomVariant(model)) out.markRandom();
                        if (!opts.plants() && isPlant(st.getBlock())) continue;
                        int layer = layers[pid];
                        boolean alpha = layer != 0;
                        boolean translucent = layer == 2;
                        int light = st.getLightEmission();
                        Vec3 off = Mc.offset(st, view, pos);
                        float bx = (float) (x + off.x), by = (float) (y + off.y), bz = (float) (z + off.z);
                        long seed = st.getSeed(pos);
                        List<Models.Quad> quads = Models.blockQuads(model, view, pos, st, rnd, seed);
                        int visKnown = 0, visible = 0; // 每个方向的可见性只算一次
                        for (Models.Quad q : quads) {
                            if (q.cull != null) {
                                int bit = 1 << q.cull.ordinal();
                                if ((visKnown & bit) == 0) {
                                    visKnown |= bit;
                                    if (Mc.faceVisible(st, view, pos, q.cull, npos)) visible |= bit;
                                }
                                if ((visible & bit) == 0) continue;
                            }
                            if (degenerate(q)) continue; // 面积为 0 的面游戏里看不见（常见于模组模型，且常带缺失贴图）
                            if (q.layer >= 0) quad(q, st, pid, pos, bx, by, bz, q.layer != 0, q.layer == 2, light);
                            else quad(q, st, pid, pos, bx, by, bz, alpha, translucent, light);
                        }
                        // 模型没有普通四边形：若是 Fabric 渲染 API 的动态模型（旧版本），让游戏的方块渲染器画一遍再截取顶点；
                        // 否则（如 Yuushya 多格方块的空模型 blank）本来就不可见，什么都不生成。
                        if (quads.isEmpty() && Models.isDynamic(model)) renderDynamic(st, model, pos, x, y, z, seed);
                    } catch (Throwable t) {
                        if (failed++ < 5) MineBlendClient.LOG.warn("[MineBlend] 方块建模失败 {} @ {}", st, pos, t);
                    }
                }
            }
        }
    }

    private Capture dynCapture;

    private void renderDynamic(BlockState st, Object model, BlockPos pos, int x, int y, int z, long seed) {
        if (dynCapture == null) dynCapture = new Capture(out, tex, true);
        PoseStack ms = new PoseStack();
        ms.translate(x, y, z);
        Models.tesselate(view, model, st, pos, ms, dynCapture, seed);
        dynCapture.finish();
        dynamic++;
    }

    int dynamic;

    private void quad(Models.Quad q, BlockState st, int pid, BlockPos pos, float bx, float by, float bz,
                      boolean alpha, boolean translucent, int light) {
        TextureAtlasSprite sp = tex.quadSprite(q);
        float u0 = sp.getU0(), du = sp.getU1() - u0, v0 = sp.getV0(), dv = sp.getV1() - v0;
        if (du == 0) du = 1;
        if (dv == 0) dv = 1;
        for (int i = 0; i < 4; i++) {
            xyz[i * 3] = q.pos[i * 3] + bx;
            xyz[i * 3 + 1] = q.pos[i * 3 + 1] + by;
            xyz[i * 3 + 2] = q.pos[i * 3 + 2] + bz;
            uvs[i * 2] = (q.uv[i * 2] - u0) / du;
            uvs[i * 2 + 1] = (q.uv[i * 2 + 1] - v0) / dv;
        }
        String name = tex.sprite(sp);
        int mat = out.mat(name, name, alpha, translucent, light);
        int color = q.tinted() ? tint(st, pid, pos, q.tintIndex) : 0xFFFFFF;
        out.face(4, xyz, uvs, mat, color);
    }

    private int tint(BlockState st, int pid, BlockPos pos, int tintIndex) {
        Holder<Biome> b = view.biome(pos.getX(), pos.getZ());
        int bid = biomeIds.computeIfAbsent(b, k -> biomeIds.size());
        long key = ((long) pid << 32) | ((long) (tintIndex & 0xFFFF) << 16) | (bid & 0xFFFF);
        Integer c = tintCache.get(key);
        if (c != null) return c;
        int col;
        try {
            col = Mc.blockTint(st, view, pos, tintIndex);
        } catch (Throwable t) {
            col = -1;
        }
        if (col == -1) col = 0xFFFFFF;
        tintCache.put(key, col);
        return col;
    }

    private void fluid(FluidState fs, BlockPos pos, BlockPos.MutableBlockPos npos, int x, int y, int z) {
        TextureAtlasSprite[] sprites = Mc.fluidSprites(fs, view, pos);
        if (sprites == null || sprites.length == 0 || sprites[0] == null) return;
        int color = Mc.fluidColor(fs, view, pos);
        if (color == -1) color = 0xFFFFFF;
        float height = fs.getHeight(view, pos);
        if (height <= 0) return;
        boolean translucent = Mc.fluidLayerKind(fs) == 2;
        int light = fs.createLegacyBlock().getLightEmission();
        for (Direction dir : DIRS) {
            npos.setWithOffset(pos, dir);
            FluidState nf = view.getFluidState(npos);
            if (!nf.isEmpty() && nf.getType().isSame(fs.getType())) continue;
            BlockState ns = view.getBlockState(npos);
            if (!(dir == Direction.UP && height < 1f) && Mc.solidRender(ns, view, npos)) continue;
            boolean side = dir.getAxis().isHorizontal();
            TextureAtlasSprite sp = side && sprites.length > 1 && sprites[1] != null ? sprites[1] : sprites[0];
            float uvScale = sp == sprites[0] ? 1f : 0.5f;
            float[] f = FACE[dir.ordinal()];
            for (int i = 0; i < 4; i++) {
                float cx = f[i * 3], cy = f[i * 3 + 1] * height, cz = f[i * 3 + 2];
                xyz[i * 3] = x + cx;
                xyz[i * 3 + 1] = y + cy;
                xyz[i * 3 + 2] = z + cz;
                faceUv(dir, cx, cy, cz, uvScale, i);
            }
            String name = tex.sprite(sp);
            int mat = out.mat(name, name, translucent, translucent, light);
            out.face(4, xyz, uvs, mat, color);
        }
    }

    /** 模型没有任何四边形（例如纯 Fabric 渲染 API 的模组模型）时，用轮廓碰撞箱 + 粒子贴图兜底 */
    private void fallbackBoxes(BlockState st, TextureAtlasSprite sprite, BlockPos pos, int x, int y, int z, boolean alpha, int light) {
        if (sprite == null) return;
        VoxelShape shape = st.getShape(view, pos);
        if (shape.isEmpty()) return;
        String name = tex.sprite(sprite);
        int mat = out.mat(name, name, alpha, false, light);
        int n = 0;
        for (AABB b : shape.toAabbs()) {
            if (n++ > 32) break;
            for (Direction dir : DIRS) {
                float[] f = FACE[dir.ordinal()];
                for (int i = 0; i < 4; i++) {
                    float cx = (float) (f[i * 3] == 0 ? b.minX : b.maxX);
                    float cy = (float) (f[i * 3 + 1] == 0 ? b.minY : b.maxY);
                    float cz = (float) (f[i * 3 + 2] == 0 ? b.minZ : b.maxZ);
                    xyz[i * 3] = x + cx;
                    xyz[i * 3 + 1] = y + cy;
                    xyz[i * 3 + 2] = z + cz;
                    faceUv(dir, cx, cy, cz, 1f, i);
                }
                out.face(4, xyz, uvs, mat, 0xFFFFFF);
            }
        }
    }

    private void faceUv(Direction dir, float cx, float cy, float cz, float s, int i) {
        float u, v;
        switch (dir.getAxis()) {
            case Y -> {
                u = cx;
                v = cz;
            }
            case Z -> {
                u = cx;
                v = 1 - cy;
            }
            default -> {
                u = cz;
                v = 1 - cy;
            }
        }
        uvs[i * 2] = u * s;
        uvs[i * 2 + 1] = v * s;
    }
}
