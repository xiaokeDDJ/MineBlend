package mineblend;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
//? if <1.21.5 {
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
//?} else {
/*import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
*///?}

/**
 * 方块模型的版本差异集中在这里，统一转换成 {@link Quad}，其余代码只处理 Quad。
 * <ul>
 * <li>1.21.4 及以前：BakedModel.getQuads(state, 方向, random)，动态模型（Fabric 渲染 API）另外让方块渲染器画一遍再截取</li>
 * <li>1.21.5 起：BlockStateModel；通过 Fabric 渲染 API 的 emitQuads 一次取出全部四边形（原版与动态模型都一样）</li>
 * </ul>
 */
final class Models {
    private Models() {}

    static final Direction[] DIRS = Direction.values();

    /** 一个四边形：4 个顶点的位置（方块内坐标）和图集 UV */
    static final class Quad {
        final float[] pos = new float[12];
        final float[] uv = new float[8];
        int tintIndex = -1;
        /** 渲染层（0 实心，1 镂空，2 半透明）；-1 = 跟随方块（26.1 起每个四边形自带） */
        int layer = -1;
        /** 朝向 */
        Direction face;
        /** 剔除方向：该方向的相邻方块挡住时不画；null = 总是画 */
        Direction cull;
        /** 模型自带的精灵（可能为 null，届时按 UV 在图集里查找） */
        TextureAtlasSprite sprite;

        boolean tinted() {
            return tintIndex >= 0;
        }
    }

    /** 方块状态对应的模型（各版本类型不同，用 Object 传递） */
    static Object model(BlockState st) {
        //? if <26.1 {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(st);
        //?} else {
        /*return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st);
        *///?}
    }

    /** 模型是「随机变体」（草方块、泥土、沙子等每格贴图方向随机） */
    static boolean isRandomVariant(Object model) {
        //? if <1.21.5 {
        return model instanceof net.minecraft.client.resources.model.WeightedBakedModel;
        //?} else {
        /*return model instanceof net.minecraft.client.resources.model.WeightedVariants;
        *///?}
    }

    /** 模型由 Fabric 渲染 API 动态生成、没有普通四边形，需要让方块渲染器画一遍再截取（只有旧版本需要） */
    static boolean isDynamic(Object model) {
        //? if <1.21.5 {
        return !((FabricBakedModel) model).isVanillaAdapter();
        //?} else {
        /*return false;
        *///?}
    }

    //? if >=1.21.5 {
    /*private static final ThreadLocal<MutableMesh> MESH = ThreadLocal.withInitial(() -> Renderer.get().mutableMesh());
    *///?}

    /** 方块的全部四边形（各带剔除方向）；seed 与游戏渲染该位置时相同 */
    static List<Quad> blockQuads(Object model, BlockAndTintGetter view, BlockPos pos, BlockState st, RandomSource rnd, long seed) {
        List<Quad> out = new ArrayList<>();
        //? if <1.21.5 {
        for (int d = 0; d <= 6; d++) {
            Direction dir = d < 6 ? DIRS[d] : null;
            rnd.setSeed(seed);
            for (BakedQuad q : ((BakedModel) model).getQuads(st, dir, rnd)) {
                Quad r = of(q);
                r.cull = dir;
                out.add(r);
            }
        }
        //?} else {
        /*MutableMesh mesh = MESH.get();
        mesh.clear();
        rnd.setSeed(seed);
        ((FabricBlockStateModel) model).emitQuads(mesh.emitter(), view, pos, st, rnd, d -> false);
        mesh.forEach(q -> {
            Quad r = new Quad();
            for (int i = 0; i < 4; i++) {
                r.pos[i * 3] = q.x(i);
                r.pos[i * 3 + 1] = q.y(i);
                r.pos[i * 3 + 2] = q.z(i);
                r.uv[i * 2] = q.u(i);
                r.uv[i * 2 + 1] = q.v(i);
            }
            r.tintIndex = q.tintIndex();
            r.face = q.nominalFace();
            r.cull = q.cullFace();
            r.layer = quadLayer(q);
            out.add(r);
        });
        *///?}
        return out;
    }

    //? if >=26.1 {
    /*private static int quadLayer(net.fabricmc.fabric.api.renderer.v1.mesh.QuadView q) {
        return Mc.layerKind(q.chunkLayer());
    }
    *///?} else if >=1.21.5 {
    /*private static int quadLayer(net.fabricmc.fabric.api.renderer.v1.mesh.QuadView q) {
        return -1;
    }
    *///?}

    //? if <1.21.5 {
    static Quad of(BakedQuad q) {
        Quad r = new Quad();
        int[] vd = q.getVertices();
        int stride = vd.length / 4;
        for (int i = 0; i < 4; i++) {
            int b = i * stride;
            r.pos[i * 3] = Float.intBitsToFloat(vd[b]);
            r.pos[i * 3 + 1] = Float.intBitsToFloat(vd[b + 1]);
            r.pos[i * 3 + 2] = Float.intBitsToFloat(vd[b + 2]);
            r.uv[i * 2] = Float.intBitsToFloat(vd[b + 4]);
            r.uv[i * 2 + 1] = Float.intBitsToFloat(vd[b + 5]);
        }
        r.tintIndex = q.isTinted() ? q.getTintIndex() : -1;
        r.face = q.getDirection();
        r.sprite = q.getSprite();
        return r;
    }
    //?}

    /** 让游戏的方块渲染器把动态模型画进 cap（只有旧版本会走到这里） */
    static void tesselate(BlockAndTintGetter view, Object model, BlockState st, BlockPos pos, PoseStack ms, Capture cap, long seed) {
        //? if <1.21.5 {
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().tesselateBlock(view, (BakedModel) model, st, pos, ms,
                cap.getBuffer(ItemBlockRenderTypes.getChunkRenderType(st)), true, RandomSource.create(), seed, OverlayTexture.NO_OVERLAY);
        //?}
    }
}
