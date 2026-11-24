package mineblend;

import mineblend.mixin.SpriteAtlasTextureAccessor;
import mineblend.mixin.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 收集导出用到的贴图（PNG）。方块贴图取自图集里的单张精灵（动画取第一帧，包含资源包/模组贴图）。 */
final class Textures {
    /** 贴图名 → PNG；null 表示无法获取 */
    final Map<String, byte[]> png = new LinkedHashMap<>();
    /** 动画贴图：贴图名 → 完整帧图 PNG 与帧信息（帧尺寸、帧顺序与时长[tick]、是否插值） */
    final Map<String, byte[]> animPng = new HashMap<>();
    final Map<String, com.google.gson.JsonObject> animInfo = new HashMap<>();
    /** PBR（LabPBR）：贴图名 → 资源包里的 _n（法线/AO/高度）、_s（光滑度/F0/金属/自发光）PNG */
    final Map<String, byte[]> normalPng = new HashMap<>();
    final Map<String, byte[]> specularPng = new HashMap<>();
    private final Map<TextureAtlasSprite, String> spriteNames = new IdentityHashMap<>();
    private final Map<ResourceLocation, AtlasIndex> atlases = new HashMap<>();

    /** 精灵 → 贴图名，如 "minecraft:block/stone" */
    synchronized String sprite(TextureAtlasSprite sp) {
        String name = spriteNames.get(sp);
        if (name != null) return name;
        SpriteContents c = sp.contents();
        name = c.name().toString();
        spriteNames.put(sp, name);
        if (!png.containsKey(name)) {
            png.put(name, spritePng(c));
            byte[] n = companion(c.name(), "_n"), sp2 = companion(c.name(), "_s");
            if (n != null) normalPng.put(name, n);
            if (sp2 != null) specularPng.put(name, sp2);
            try {
                animation(c, name);
            } catch (Throwable t) {
                MineBlendClient.LOG.debug("[MineBlend] 动画信息读取失败 {}", name, t);
            }
        }
        return name;
    }

    /** 从当前启用的资源包里读取配套贴图（如 block/stone → textures/block/stone_n.png），没有则返回 null */
    private static byte[] companion(ResourceLocation id, String suffix) {
        try {
            ResourceLocation rid = Mc.id(id.getNamespace(), "textures/" + id.getPath() + suffix + ".png");
            Optional<Resource> r = Minecraft.getInstance().getResourceManager().getResource(rid);
            if (r.isEmpty()) return null;
            try (InputStream in = r.get().open()) {
                byte[] data = in.readAllBytes();
                return data.length > 8 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G' ? data : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 若精灵是动画（图片里有多帧），记录完整帧图和 .mcmeta 里的帧顺序/时长 */
    private void animation(SpriteContents c, String name) throws Exception {
        NativeImage src = ((SpriteContentsAccessor) c).mineblend$getImage();
        if (src == null) return;
        int fw = c.width(), fh = c.height();
        int cols = src.getWidth() / fw, rows = src.getHeight() / fh;
        int total = cols * rows;
        if (total <= 1) return;

        int frametime = 1;
        boolean interpolate = false;
        com.google.gson.JsonArray framesIn = null;
        ResourceLocation id = c.name();
        ResourceLocation meta = Mc.id(id.getNamespace(), "textures/" + id.getPath() + ".png.mcmeta");
        Optional<Resource> r = Minecraft.getInstance().getResourceManager().getResource(meta);
        if (r.isPresent()) {
            try (InputStream in = r.get().open()) {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                        new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("animation")) {
                    com.google.gson.JsonObject a = root.getAsJsonObject("animation");
                    if (a.has("frametime")) frametime = Math.max(1, a.get("frametime").getAsInt());
                    if (a.has("interpolate")) interpolate = a.get("interpolate").getAsBoolean();
                    if (a.has("frames")) framesIn = a.getAsJsonArray("frames");
                }
            }
        }
        com.google.gson.JsonArray frames = new com.google.gson.JsonArray();
        if (framesIn != null && framesIn.size() > 0) {
            for (com.google.gson.JsonElement e : framesIn) {
                int idx, time = frametime;
                if (e.isJsonObject()) {
                    idx = e.getAsJsonObject().get("index").getAsInt();
                    if (e.getAsJsonObject().has("time")) time = Math.max(1, e.getAsJsonObject().get("time").getAsInt());
                } else idx = e.getAsInt();
                if (idx < 0 || idx >= total) continue;
                com.google.gson.JsonArray f = new com.google.gson.JsonArray();
                f.add(idx);
                f.add(time);
                frames.add(f);
            }
        } else {
            for (int i = 0; i < total; i++) {
                com.google.gson.JsonArray f = new com.google.gson.JsonArray();
                f.add(i);
                f.add(frametime);
                frames.add(f);
            }
        }
        if (frames.size() <= 1) return;
        com.google.gson.JsonObject info = new com.google.gson.JsonObject();
        info.addProperty("frameWidth", fw);
        info.addProperty("frameHeight", fh);
        info.addProperty("columns", cols);
        info.addProperty("interpolate", interpolate);
        info.add("frames", frames);
        animInfo.put(name, info);
        animPng.put(name, Mc.png(src));
    }

    private static byte[] spritePng(SpriteContents c) {
        try {
            NativeImage src = ((SpriteContentsAccessor) c).mineblend$getImage();
            if (src == null) return null;
            int w = Math.min(c.width(), src.getWidth()), h = Math.min(c.height(), src.getHeight());
            try (NativeImage img = new NativeImage(w, h, false)) {
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) Mc.setAbgr(img, x, y, Mc.abgr(src, x, y));
                return Mc.png(img);
            }
        } catch (Throwable t) {
            MineBlendClient.LOG.debug("[MineBlend] 精灵导出失败 {}", c.name(), t);
            return null;
        }
    }

    /** 普通贴图文件（实体贴图等），直接从资源包读取 PNG */
    synchronized String resource(ResourceLocation id) {
        String name = id.toString();
        if (png.containsKey(name)) return png.get(name) == null ? null : name;
        byte[] data = null;
        try {
            Optional<Resource> r = Minecraft.getInstance().getResourceManager().getResource(id);
            if (r.isPresent()) {
                try (InputStream in = r.get().open()) {
                    data = in.readAllBytes();
                }
                if (data.length < 8 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G') data = null;
            }
        } catch (Throwable ignored) {
        }
        png.put(name, data);
        return data == null ? null : name;
    }

    /** 若 id 是精灵图集，返回其索引（用于把图集 UV 换算回单张精灵） */
    synchronized AtlasIndex atlas(ResourceLocation id) {
        if (atlases.containsKey(id)) return atlases.get(id);
        AtlasIndex idx = null;
        AbstractTexture t = Mc.texture(id);
        if (t instanceof TextureAtlas atlas) idx = new AtlasIndex(((SpriteAtlasTextureAccessor) atlas).mineblend$getSprites().values());
        atlases.put(id, idx);
        return idx;
    }

    /** 方块图集 */
    AtlasIndex blockAtlas() {
        return atlas(TextureAtlas.LOCATION_BLOCKS);
    }

    /**
     * 四边形真正使用的精灵：按 UV 中心在图集里查找。有些模组模型里 BakedQuad 自带的 sprite 与 UV 指向的区域不一致，
     * 直接用它换算会得到 0~4 这样的 UV，在 Blender 里表现为贴图被平铺成点阵。
     */
    TextureAtlasSprite quadSprite(Models.Quad q) {
        TextureAtlasSprite sp = q.sprite;
        float cu = 0, cv = 0;
        for (int i = 0; i < 4; i++) {
            cu += q.uv[i * 2];
            cv += q.uv[i * 2 + 1];
        }
        cu /= 4;
        cv /= 4;
        if (sp != null && cu >= sp.getU0() && cu <= sp.getU1() && cv >= sp.getV0() && cv <= sp.getV1()) return sp;
        AtlasIndex idx = blockAtlas();
        TextureAtlasSprite found = idx == null ? null : idx.find(cu, cv);
        if (found != null) {
            mismatched++;
            return found;
        }
        return sp;
    }

    int mismatched;

    /** 图集 UV → 精灵的网格索引 */
    static final class AtlasIndex {
        static final int G = 64;
        final List<TextureAtlasSprite>[] cells;

        @SuppressWarnings("unchecked")
        AtlasIndex(Iterable<TextureAtlasSprite> sprites) {
            cells = new List[G * G];
            for (TextureAtlasSprite s : sprites) {
                int x0 = clamp((int) (s.getU0() * G)), x1 = clamp((int) (s.getU1() * G - 1e-6));
                int y0 = clamp((int) (s.getV0() * G)), y1 = clamp((int) (s.getV1() * G - 1e-6));
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) {
                        int k = y * G + x;
                        if (cells[k] == null) cells[k] = new ArrayList<>(2);
                        cells[k].add(s);
                    }
            }
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(G - 1, v));
        }

        TextureAtlasSprite find(float u, float v) {
            List<TextureAtlasSprite> l = cells[clamp((int) (v * G)) * G + clamp((int) (u * G))];
            if (l == null) return null;
            for (TextureAtlasSprite s : l)
                if (u >= s.getU0() && u <= s.getU1() && v >= s.getV0() && v <= s.getV1()) return s;
            return null;
        }
    }
}
