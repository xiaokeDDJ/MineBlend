package mineblend;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
//? if <26.1 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.9 {
/*import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
*///?}
//? if >=26.1 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
*///?} else if >=1.21.9 {
/*import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
*///?}

/**
 * 通过一个“录制用”的 VertexConsumerProvider 让游戏把方块实体（箱子、告示牌、床、旗帜、模组家具……）
 * 和实体（物品展示框、盔甲架、掉落物、模组实体……）真正渲染一遍，把顶点截下来变成网格。
 * 必须在客户端渲染线程上调用。
 */
//? if <26.1 {
final class Capture implements MultiBufferSource {
//?} else {
/*final class Capture {
*///?}
    // 渲染类型描述里的贴图：旧版本 "texture[Optional[ns:path]"，1.21.11 起 "Sampler0=TextureBinding[location=ns:path"
    private static final Pattern TEXTURE = Pattern.compile(
            "(?:texture\\[Optional\\[|Sampler0=TextureBinding\\[location=)([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)");
    private static final Pattern NAME = Pattern.compile("^RenderType\\[([^:\\]]+)");

    private final MeshOut out;
    private final Textures tex;
    private final Map<RenderType, VertexConsumer> consumers = new IdentityHashMap<>();
    int blockEntities, entities, failed;

    /** 方块渲染器输出的颜色里含有方向明暗和环境光遮蔽：灰色视为无染色 */
    private final boolean stripShade;

    Capture(MeshOut out, Textures tex) {
        this(out, tex, false);
    }

    Capture(MeshOut out, Textures tex, boolean stripShade) {
        this.out = out;
        this.tex = tex;
        this.stripShade = stripShade;
    }

    //? if <26.1 {
    @Override
    //?}
    public VertexConsumer getBuffer(RenderType layer) {
        return consumers.computeIfAbsent(layer, this::make);
    }

    private VertexConsumer make(RenderType layer) {
        int prim = Mc.primitiveSize(layer);
        String desc = layer.toString();
        Matcher nm = NAME.matcher(desc);
        String name = nm.find() ? nm.group(1) : desc;
        String lname = name.toLowerCase();
        if (prim == 0 || lname.contains("text") || lname.contains("glint") || lname.contains("line")
                || lname.contains("crumbling") || lname.contains("outline") || lname.contains("shadow")
                || lname.contains("lightning") || lname.contains("end_portal") || lname.contains("end_gateway")) {
            return Noop.INSTANCE;
        }
        Matcher m = TEXTURE.matcher(desc);
        ResourceLocation texId = m.find() ? ResourceLocation.tryParse(m.group(1)) : null;
        if (texId != null && (texId.getPath().contains("font") || texId.getPath().contains("shadow"))) return Noop.INSTANCE;
        boolean translucent = lname.contains("translucent");
        return new Recorder(prim, texId, translucent, lname);
    }

    // ------------------------------------------------------------------ driving the renderers

    void captureBlockEntities(ClientLevel world, int x0, int y0, int z0, int x1, int y1, int z1) {
        Minecraft mc = Minecraft.getInstance();
        BlockEntityRenderDispatcher berd = mc.getBlockEntityRenderDispatcher();
        float td = Mc.partialTick();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!world.getChunkSource().hasChunk(cx, cz)) continue;
                LevelChunk chunk = world.getChunk(cx, cz);
                List<BlockEntity> list = new ArrayList<>(chunk.getBlockEntities().values());
                for (BlockEntity be : list) {
                    BlockPos p = be.getBlockPos();
                    if (p.getX() < x0 || p.getX() > x1 || p.getY() < y0 || p.getY() > y1 || p.getZ() < z0 || p.getZ() > z1) continue;
                    //? if <1.21.9 {
                    @SuppressWarnings("unchecked")
                    BlockEntityRenderer<BlockEntity> r = (BlockEntityRenderer<BlockEntity>) berd.getRenderer(be);
                    //?} else {
                    /*@SuppressWarnings("unchecked")
                    BlockEntityRenderer<BlockEntity, BlockEntityRenderState> r = (BlockEntityRenderer<BlockEntity, BlockEntityRenderState>) berd.getRenderer(be);
                    *///?}
                    if (r == null) continue;
                    PoseStack ms = new PoseStack();
                    ms.translate(p.getX() - x0, p.getY() - y0, p.getZ() - z0);
                    out.at(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString(),
                            p.getX() - x0, p.getY() - y0, p.getZ() - z0);
                    try {
                        //? if <1.21.9 {
                        r.render(be, td, ms, this, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                        //?} else {
                        /*BlockEntityRenderState state = r.createRenderState();
                        r.extractRenderState(be, state, td, Vec3.atCenterOf(p), null);
                        state.lightCoords = LightTexture.FULL_BRIGHT;
                        r.submit(state, ms, submits(), camera());
                        renderSubmits();
                        *///?}
                        finish();
                        blockEntities++;
                    } catch (Throwable t) {
                        if (failed++ < 5) MineBlendClient.LOG.warn("[MineBlend] 方块实体渲染失败 {}", be.getType(), t);
                    }
                }
            }
        }
    }

    void captureEntities(ClientLevel world, int x0, int y0, int z0, int x1, int y1, int z1, boolean mobs) {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher erd = mc.getEntityRenderDispatcher();
        float td = Mc.partialTick();
        AABB box = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
        List<Entity> list = world.getEntities(null, box);
        //? if <1.21.9 {
        boolean shadows = mc.options.entityShadows().get();
        erd.setRenderShadow(false);
        //?}
        try {
            for (Entity e : list) {
                if (e instanceof Player) continue;
                boolean decor = !(e instanceof LivingEntity) || e instanceof ArmorStand;
                if (!decor && !mobs) continue;
                if (e.isInvisible() && !(e instanceof ArmorStand)) continue;
                Vec3 p = e.getPosition(td);
                PoseStack ms = new PoseStack();
                out.at("entity:" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                        (int) Math.floor(p.x) - x0, (int) Math.floor(p.y) - y0, (int) Math.floor(p.z) - z0);
                try {
                    //? if <1.21.2 {
                    erd.render(e, p.x - x0, p.y - y0, p.z - z0, e.getViewYRot(td), td, ms, this, LightTexture.FULL_BRIGHT);
                    //?} else if <1.21.9 {
                    /*erd.render(e, p.x - x0, p.y - y0, p.z - z0, td, ms, this, LightTexture.FULL_BRIGHT);
                    *///?} else {
                    /*EntityRenderState es = erd.extractEntity(e, td);
                    es.lightCoords = LightTexture.FULL_BRIGHT;
                    es.shadowPieces.clear();
                    erd.submit(es, camera(), p.x - x0, p.y - y0, p.z - z0, ms, submits());
                    renderSubmits();
                    *///?}
                    finish();
                    entities++;
                } catch (Throwable t) {
                    if (failed++ < 5) MineBlendClient.LOG.warn("[MineBlend] 实体渲染失败 {}", e.getType(), t);
                }
            }
        } finally {
            //? if <1.21.9 {
            erd.setRenderShadow(shadows);
            //?}
        }
    }

    //? if >=26.1 {
    /*// 26.1 起特征渲染直接写显卡，没有可替换的缓冲：这里自己实现提交收集器，收到模型/方块/物品就立刻画进录制器
    private Grab grab;
    private CameraRenderState cam;

    private SubmitNodeCollector submits() {
        if (grab == null) grab = new Grab();
        return grab;
    }

    private void renderSubmits() {}

    private CameraRenderState camera() {
        if (cam == null) {
            cam = new CameraRenderState();
            cam.pos = Vec3.ZERO;
                        cam.blockPos = BlockPos.ZERO;
            cam.orientation = new org.joml.Quaternionf();
            cam.initialized = true;
        }
        return cam;
    }

    private void bakedQuad(PoseStack.Pose pose, net.minecraft.client.resources.model.geometry.BakedQuad q, VertexConsumer vc, int[] tints) {
        com.mojang.blaze3d.vertex.QuadInstance qi = new com.mojang.blaze3d.vertex.QuadInstance();
        int ti = q.materialInfo().tintIndex();
        qi.setColor(q.materialInfo().isTinted() && tints != null && ti >= 0 && ti < tints.length ? tints[ti] : 0xFFFFFFFF);
        vc.putBakedQuad(pose, q, qi);
    }

    private final class Grab implements SubmitNodeCollector {
        @Override
        public net.minecraft.client.renderer.OrderedSubmitNodeCollector order(int i) {
            return this;
        }

        @Override
        public void submitShadow(PoseStack ms, float r, List<EntityRenderState.ShadowPiece> pieces) {}

        @Override
        public void submitNameTag(PoseStack ms, Vec3 v, int i, net.minecraft.network.chat.Component c, boolean b, int j, CameraRenderState cam) {}

        @Override
        public void submitText(PoseStack ms, float x, float y, net.minecraft.util.FormattedCharSequence s, boolean b,
                               net.minecraft.client.gui.Font.DisplayMode m, int a, int c, int d, int e) {}

        @Override
        public void submitTextBackground(PoseStack ms, float a, float b, float c, float d, int e, net.minecraft.client.gui.Font.DisplayMode m, int f) {}

        @Override
        public void submitFlame(PoseStack ms, EntityRenderState s, org.joml.Quaternionf q) {}

        @Override
        public void submitLeash(PoseStack ms, EntityRenderState.LeashState s) {}

        @Override
        public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S state, PoseStack ms, RenderType type,
                                    int light, int overlay, int color, net.minecraft.client.renderer.texture.UvMapping uv, int outline) {
            VertexConsumer vc = getBuffer(type);
            if (uv != null) vc = uv.wrap(vc);
            model.setupAnim(state);
            model.renderToBuffer(ms, vc, light, overlay, color);
        }

        @Override
        public <S> void submitCrumblingOverlay(net.minecraft.client.model.Model<? super S> m, S s, PoseStack ms, RenderType t, int a, int b, int c,
                                               net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay o) {}

        @Override
        public void submitMovingBlock(PoseStack ms, net.minecraft.client.renderer.block.MovingBlockRenderState s, int i) {}

        @Override
        public void submitBlockModel(PoseStack ms, RenderType type, List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                     int[] tints, int light, int overlay, int color) {
            VertexConsumer vc = getBuffer(type);
            for (var part : parts) {
                for (int d = 0; d <= 6; d++) {
                    for (var q : part.getQuads(d < 6 ? Models.DIRS[d] : null)) bakedQuad(ms.last(), q, vc, tints);
                }
            }
        }

        @Override
        public void submitBreakingBlockModel(PoseStack ms, List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> p, int i, boolean b) {}

        @Override
        public void submitShapeOutline(PoseStack ms, net.minecraft.world.phys.shapes.VoxelShape s, RenderType t, int c, float w, boolean b) {}

        @Override
        public void submitItem(PoseStack ms, net.minecraft.world.item.ItemDisplayContext ctx, int light, int overlay, int outline, int[] tints,
                               net.minecraft.client.resources.model.geometry.ItemQuads quads,
                               net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil) {
            for (var q : quads.all()) bakedQuad(ms.last(), q, getBuffer(q.materialInfo().itemRenderType()), tints);
        }

        @Override
        public void submitCustomGeometry(PoseStack ms, RenderType type, SubmitNodeCollector.CustomGeometryRenderer r) {
            r.render(ms.last(), getBuffer(type));
        }

        @Override
        public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState s) {}

        @Override
        public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group g, CameraRenderState c, boolean b) {}
    }
    *///?} else if >=1.21.9 {
    /*// 1.21.9 起方块实体/实体先「提交」到收集器，再由特征渲染器统一画进缓冲：这里把缓冲换成录制器
    private SubmitNodeStorage storage;
    private FeatureRenderDispatcher features;
    private CameraRenderState cam;

    private SubmitNodeStorage submits() {
        if (storage == null) {
            storage = new SubmitNodeStorage();
            Minecraft mc = Minecraft.getInstance();
            Sink sink = new Sink(this);
            features = new FeatureRenderDispatcher(storage, mc.getBlockRenderer(), sink, mc.getAtlasManager(),
                    mc.renderBuffers().outlineBufferSource(), sink, mc.font);
        }
        return storage;
    }

    private CameraRenderState camera() {
        if (cam == null) {
            cam = new CameraRenderState();
            cam.pos = Vec3.ZERO;
            cam.entityPos = Vec3.ZERO;
            cam.blockPos = BlockPos.ZERO;
            cam.orientation = new org.joml.Quaternionf();
            cam.initialized = true;
        }
        return cam;
    }

    private void renderSubmits() {
        features.renderAllFeatures();
        storage.clear();
    }

    // 特征渲染器要的缓冲：所有绘制都转给录制器，不真正提交到显卡
    private static final class Sink extends MultiBufferSource.BufferSource {
        private final Capture cap;

        Sink(Capture cap) {
            super(new ByteBufferBuilder(256), new java.util.LinkedHashMap<>());
            this.cap = cap;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return cap.getBuffer(type);
        }

        @Override
        public void endBatch() {}

        @Override
        public void endBatch(RenderType type) {}

        @Override
        public void endLastBatch() {}
    }
    *///?}

    /** 提交所有录制器里还没结束的最后一个顶点（1.21 起顶点没有 endVertex，要等下一个顶点或渲染结束） */
    void finish() {
        for (VertexConsumer vc : consumers.values()) {
            if (vc instanceof Recorder r) r.finishVertex();
        }
    }

    // ------------------------------------------------------------------ vertex recorders

    private final class Recorder implements VertexConsumer {
        final int prim;
        final ResourceLocation texId;
        final boolean translucent;
        final String layerName;
        final float[] px = new float[12], tuv = new float[8];
        final float[] xyz = new float[12], uvs = new float[8];
        int n;
        double x, y, z;
        int r = 255, g = 255, b = 255;
        float u, v;
        boolean fixed;
        int fr, fg, fb;
        int faceColor;
        boolean resolved;
        Textures.AtlasIndex atlas;
        String plainTexture;

        Recorder(int prim, ResourceLocation texId, boolean translucent, String layerName) {
            this.prim = prim;
            this.texId = texId;
            this.translucent = translucent;
            this.layerName = layerName;
        }

        boolean pending;

        private void pos(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private void col(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        //? if <1.21 {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            pos(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            col(r, g, b);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            end();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            fixed = true;
            fr = r;
            fg = g;
            fb = b;
        }

        @Override
        public void unsetDefaultColor() {
            fixed = false;
        }
        //?} else {
        /*@Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (pending) end();
            pending = true;
            pos(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            col(r, g, b);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
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

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
        *///?}
        //? if >=1.21.11 {
        /*@Override
        public VertexConsumer setColor(int argb) {
            col((argb >> 16) & 255, (argb >> 8) & 255, argb & 255);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float w) {
            return this;
        }
        *///?}
        //? if >=26.3 {
        /*@Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }
        *///?}

        void finishVertex() {
            if (pending) {
                pending = false;
                end();
            }
        }

        /** 一个顶点的数据齐了：记下来，凑满一个面就输出 */
        private void end() {
            if (n == 0) {
                faceColor = fixed ? (fr << 16 | fg << 8 | fb) : (r << 16 | g << 8 | b);
                if (stripShade) {
                    int cr = (faceColor >> 16) & 255, cg = (faceColor >> 8) & 255, cb = faceColor & 255;
                    int mx = Math.max(cr, Math.max(cg, cb)), mn = Math.min(cr, Math.min(cg, cb));
                    if (mx - mn < 12 || mx == 0) faceColor = 0xFFFFFF;
                    else { // 去掉明暗：把最亮通道拉回原值附近，并量化以减少材质数量
                        float k = 255f / mx;
                        cr = Math.min(255, Math.round(cr * k / 16) * 16);
                        cg = Math.min(255, Math.round(cg * k / 16) * 16);
                        cb = Math.min(255, Math.round(cb * k / 16) * 16);
                        faceColor = cr << 16 | cg << 8 | cb;
                    }
                }
            }
            px[n * 3] = (float) x;
            px[n * 3 + 1] = (float) y;
            px[n * 3 + 2] = (float) z;
            tuv[n * 2] = u;
            tuv[n * 2 + 1] = v;
            r = g = b = 255;
            if (++n == prim) {
                flush();
                n = 0;
            }
        }

        private void flush() {
            if (!resolved) {
                resolved = true;
                if (texId != null) {
                    atlas = tex.atlas(texId);
                    if (atlas == null) plainTexture = tex.resource(texId);
                }
            }
            System.arraycopy(px, 0, xyz, 0, prim * 3);
            String name = null;
            if (atlas != null) {
                float cu = 0, cv = 0;
                for (int i = 0; i < prim; i++) {
                    cu += tuv[i * 2];
                    cv += tuv[i * 2 + 1];
                }
                TextureAtlasSprite sp = atlas.find(cu / prim, cv / prim);
                if (sp != null) {
                    name = tex.sprite(sp);
                    float u0 = sp.getU0(), du = sp.getU1() - u0, v0 = sp.getV0(), dv = sp.getV1() - v0;
                    for (int i = 0; i < prim; i++) {
                        uvs[i * 2] = du == 0 ? 0 : (tuv[i * 2] - u0) / du;
                        uvs[i * 2 + 1] = dv == 0 ? 0 : (tuv[i * 2 + 1] - v0) / dv;
                    }
                }
            } else if (plainTexture != null) {
                name = plainTexture;
                System.arraycopy(tuv, 0, uvs, 0, prim * 2);
            }
            int mat;
            if (name != null) {
                mat = out.mat(name, name, true, translucent, 0);
            } else {
                String key = "untextured:" + (texId != null ? texId : layerName);
                mat = out.mat(key, null, false, translucent, 0);
                System.arraycopy(tuv, 0, uvs, 0, prim * 2);
            }
            out.face(prim, xyz, uvs, mat, faceColor);
        }
    }

    private static final class Noop implements VertexConsumer {
        static final Noop INSTANCE = new Noop();

        //? if <1.21 {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {}

        @Override
        public void defaultColor(int r, int g, int b, int a) {}

        @Override
        public void unsetDefaultColor() {}
        //?} else {
        /*@Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
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

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
        *///?}
        //? if >=1.21.11 {
        /*@Override
        public VertexConsumer setColor(int argb) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float w) {
            return this;
        }
        *///?}
        //? if >=26.3 {
        /*@Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }
        *///?}
    }
}
