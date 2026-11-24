package mineblend;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
//? if <26.1 {
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
//?} else {
/*import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.color.block.BlockTintSource;
*///?}
//? if <1.21.2 {
import net.minecraft.client.renderer.LevelRenderer;
//?} else if <26.1 {
/*import net.minecraft.client.renderer.ShapeRenderer;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.shapes.Shapes;
*///?}
//? if <26.1 {
import com.mojang.blaze3d.vertex.VertexFormat;
//?} else {
/*import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
*///?}

/**
 * 各 Minecraft 版本之间改名、改参数的零散 API 集中在这里，其余代码只调用这些方法。
 * 版本差异用 Stonecutter 的 //? if 条件注释选择（源码里展开的是 1.20.1 的写法）。
 */
final class Mc {
    private Mc() {}

    /** 当前帧的插值进度（0~1） */
    static float partialTick() {
        Minecraft mc = Minecraft.getInstance();
        //? if <1.21 {
        return mc.getFrameTime();
        //?} else if <1.21.2 {
        /*return mc.getTimer().getGameTimeDeltaPartialTick(true);
        *///?} else {
        /*return mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        *///?}
    }

    /** 世界最低的方块 Y */
    static int minY(LevelHeightAccessor l) {
        //? if <1.21.2 {
        return l.getMinBuildHeight();
        //?} else {
        /*return l.getMinY();
        *///?}
    }

    /** 世界最高的方块 Y（含） */
    static int maxY(LevelHeightAccessor l) {
        return minY(l) + l.getHeight() - 1;
    }

    static ResourceLocation id(String namespace, String path) {
        //? if <1.21 {
        return new ResourceLocation(namespace, path);
        //?} else {
        /*return ResourceLocation.fromNamespaceAndPath(namespace, path);
        *///?}
    }

    /** 像素（ABGR，与游戏内存布局一致） */
    static int abgr(NativeImage img, int x, int y) {
        //? if <1.21.2 {
        return img.getPixelRGBA(x, y);
        //?} else {
        /*return swapRB(img.getPixel(x, y));
        *///?}
    }

    static void setAbgr(NativeImage img, int x, int y, int abgr) {
        //? if <1.21.2 {
        img.setPixelRGBA(x, y, abgr);
        //?} else {
        /*img.setPixel(x, y, swapRB(abgr));
        *///?}
    }

    /** ABGR ↔ ARGB：交换红、蓝通道 */
    static int swapRB(int c) {
        return (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
    }

    /** 编码成 PNG */
    static byte[] png(NativeImage img) throws IOException {
        //? if <1.21.2 {
        return img.asByteArray();
        //?} else {
        /*Path tmp = Files.createTempFile("mineblend", ".png");
        try {
            img.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
        *///?}
    }

    static Holder<Biome> plains(Level w) {
        //? if <1.21.2 {
        return w.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        //?} else {
        /*return w.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        *///?}
    }

    /** 完整不透明方块（会挡住相邻方块的面） */
    static boolean solidRender(BlockState st, BlockGetter g, BlockPos p) {
        //? if <1.21.2 {
        return st.isSolidRender(g, p);
        //?} else {
        /*return st.isSolidRender();
        *///?}
    }

    /** 花草等方块的随机偏移 */
    static Vec3 offset(BlockState st, BlockGetter g, BlockPos p) {
        //? if <1.21.2 {
        return st.getOffset(g, p);
        //?} else {
        /*return st.getOffset(p);
        *///?}
    }

    /** 方块在 dir 方向的面是否可见（与游戏剔除规则相同） */
    static boolean faceVisible(BlockState st, BlockGetter g, BlockPos pos, Direction dir, BlockPos.MutableBlockPos npos) {
        npos.setWithOffset(pos, dir);
        //? if <1.21.2 {
        return Block.shouldRenderFace(st, g, pos, dir, npos);
        //?} else {
        /*return Block.shouldRenderFace(st, g.getBlockState(npos), dir);
        *///?}
    }

    /** 已加载的纹理（图集等），不存在时可能返回 null */
    static AbstractTexture texture(ResourceLocation id) {
        //? if <1.21.2 {
        return Minecraft.getInstance().getTextureManager().getTexture(id, null);
        //?} else {
        /*return Minecraft.getInstance().getTextureManager().getTexture(id);
        *///?}
    }

    static void lineBox(PoseStack ms, VertexConsumer vc, AABB box, float r, float g, float b, float a) {
        //? if <1.21.2 {
        LevelRenderer.renderLineBox(ms, vc, box, r, g, b, a);
        //?} else if <1.21.11 {
        /*ShapeRenderer.renderLineBox(ms, vc, box, r, g, b, a);
        *///?} else if <26.1 {
        /*ShapeRenderer.renderShape(ms, vc, Shapes.create(box), 0, 0, 0, argb(r, g, b, a), 2f);
        *///?}
    }

    static int argb(float r, float g, float b, float a) {
        return (Math.round(a * 255) << 24) | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** 画线用的渲染类型 */
    static RenderType lines() {
        //? if <1.21.11 {
        return RenderType.lines();
        //?} else {
        /*return RenderTypes.lines();
        *///?}
    }

    /** 维度 id，如 minecraft:overworld */
    static String dimensionId(Level l) {
        //? if <1.21.11 {
        return l.dimension().location().toString();
        //?} else {
        /*return l.dimension().identifier().toString();
        *///?}
    }

    /** 游戏版本号，如 1.21.11 */
    static String gameVersion() {
        //? if <1.21.6 {
        return net.minecraft.SharedConstants.getCurrentVersion().getName();
        //?} else {
        /*return net.minecraft.SharedConstants.getCurrentVersion().name();
        *///?}
    }

    /** 方块的渲染层：0 实心，1 镂空，2 半透明 */
    static int layerKind(BlockState st) {
        //? if <1.21.11 {
        RenderType t = ItemBlockRenderTypes.getChunkRenderType(st);
        return t == RenderType.solid() ? 0 : t == RenderType.translucent() ? 2 : 1;
        //?} else if <26.1 {
        /*ChunkSectionLayer t = ItemBlockRenderTypes.getChunkRenderType(st);
        return t == ChunkSectionLayer.SOLID ? 0 : t == ChunkSectionLayer.TRANSLUCENT ? 2 : 1;
        *///?} else {
        /*return 0; // 26.1 起渲染层按四边形给出（见 Models.Quad.layer）
        *///?}
    }

    //? if >=1.21.11 {
    /*static int layerKind(ChunkSectionLayer t) {
        return t == ChunkSectionLayer.SOLID ? 0 : t == ChunkSectionLayer.TRANSLUCENT ? 2 : 1;
    }
    *///?}

    /** 流体的渲染层：0 实心，1 镂空，2 半透明 */
    static int fluidLayerKind(FluidState fs) {
        //? if <1.21.11 {
        RenderType t = ItemBlockRenderTypes.getRenderLayer(fs);
        return t == RenderType.solid() ? 0 : t == RenderType.translucent() ? 2 : 1;
        //?} else if <26.1 {
        /*ChunkSectionLayer t = ItemBlockRenderTypes.getRenderLayer(fs);
        return t == ChunkSectionLayer.SOLID ? 0 : t == ChunkSectionLayer.TRANSLUCENT ? 2 : 1;
        *///?} else {
        /*return layerKind(fluidModel(fs).layer());
        *///?}
    }

    //? if >=26.1 {
    /*static FluidModel fluidModel(FluidState fs) {
        return Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fs);
    }
    *///?}

    /** 流体贴图：[0] 静止，[1] 流动（可能为 null） */
    static TextureAtlasSprite[] fluidSprites(FluidState fs, BlockAndTintGetter view, BlockPos pos) {
        //? if <26.1 {
        FluidRenderHandler h = FluidRenderHandlerRegistry.INSTANCE.get(fs.getType());
        return h == null ? null : h.getFluidSprites(view, pos, fs);
        //?} else {
        /*FluidModel m = fluidModel(fs);
        if (m == null) return null;
        return new TextureAtlasSprite[]{m.stillMaterial() == null ? null : m.stillMaterial().sprite(),
                m.flowingMaterial() == null ? null : m.flowingMaterial().sprite()};
        *///?}
    }

    /** 流体染色（0xRRGGBB，-1 = 不染色） */
    static int fluidColor(FluidState fs, BlockAndTintGetter view, BlockPos pos) {
        //? if <26.1 {
        FluidRenderHandler h = FluidRenderHandlerRegistry.INSTANCE.get(fs.getType());
        return h == null ? -1 : h.getFluidColor(view, pos, fs);
        //?} else {
        /*FluidModel m = fluidModel(fs);
        BlockTintSource src = m == null ? null : m.tintSource();
        return src == null ? -1 : src.colorInWorld(fs.createLegacyBlock(), view, pos);
        *///?}
    }

    /** 方块染色（草、树叶等；0xRRGGBB，-1 = 不染色） */
    static int blockTint(BlockState st, BlockAndTintGetter view, BlockPos pos, int tintIndex) {
        //? if <26.1 {
        return Minecraft.getInstance().getBlockColors().getColor(st, view, pos, tintIndex);
        //?} else {
        /*BlockTintSource src = Minecraft.getInstance().getBlockColors().getTintSource(st, tintIndex);
        return src == null ? -1 : src.colorInWorld(st, view, pos);
        *///?}
    }

    /** 渲染类型每个图元的顶点数：4 四边形，3 三角形，0 其它（线、点等） */
    static int primitiveSize(RenderType t) {
        //? if <26.1 {
        VertexFormat.Mode m = t.mode();
        return m == VertexFormat.Mode.QUADS ? 4 : m == VertexFormat.Mode.TRIANGLES ? 3 : 0;
        //?} else {
        /*PrimitiveTopology m = t.primitiveTopology();
        return m == PrimitiveTopology.QUADS ? 4 : m == PrimitiveTopology.TRIANGLES ? 3 : 0;
        *///?}
    }

    /** 聊天栏消息 */
    static void chat(Player p, Component c) {
        //? if <26.1 {
        p.displayClientMessage(c, false);
        //?} else {
        /*p.sendSystemMessage(c);
        *///?}
    }

    /** 快捷栏上方的提示 */
    static void actionBar(Player p, Component c) {
        //? if <26.1 {
        p.displayClientMessage(c, true);
        //?} else {
        /*p.sendOverlayMessage(c);
        *///?}
    }
}
