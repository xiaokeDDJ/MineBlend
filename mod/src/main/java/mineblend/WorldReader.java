package mineblend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import mineblend.BridgeServer.ApiException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** 从正在运行的世界读取方块。单人游戏读集成服务器的世界（可加载存档中未加载的区块），否则读客户端已加载区块。 */
public final class WorldReader {
    private WorldReader() {}

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static <T> T onClient(Supplier<T> fn) throws Exception {
        return mc().submit(fn).get(600, TimeUnit.SECONDS);
    }

    /** 在拥有该世界的线程上执行（集成服务器线程或客户端渲染线程） */
    static <T> T onWorld(Function<Level, T> fn) throws Exception {
        Minecraft mc = mc();
        ResourceKey<Level> key = onClient(() -> mc.level == null ? null : mc.level.dimension());
        if (key == null) throw new ApiException("游戏当前不在世界中，请先进入存档");
        IntegratedServer srv = mc.getSingleplayerServer();
        if (srv != null) {
            ServerLevel sw = srv.getLevel(key);
            if (sw != null) return srv.submit(() -> fn.apply(sw)).get(600, TimeUnit.SECONDS);
        }
        return mc.submit(() -> fn.apply(mc.level)).get(600, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ status / selection

    static JsonObject status() throws Exception {
        return onClient(() -> {
            Minecraft mc = mc();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("mod", "mineblend");
            o.addProperty("modVersion", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("mineblend")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
            o.addProperty("mcVersion", Mc.gameVersion());
            o.addProperty("inWorld", mc.level != null && mc.player != null);
            if (mc.level != null && mc.player != null) {
                o.addProperty("dimension", Mc.dimensionId(mc.level));
                o.addProperty("singleplayer", mc.getSingleplayerServer() != null);
                if (mc.getSingleplayerServer() != null) o.addProperty("worldName", mc.getSingleplayerServer().getWorldData().getLevelName());
                o.addProperty("minY", Mc.minY(mc.level));
                o.addProperty("maxY", Mc.maxY(mc.level));
                o.add("player", arr(mc.player.blockPosition()));
                o.addProperty("yaw", mc.player.getYRot());
            }
            return o;
        });
    }

    static JsonObject selection() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        BlockPos a = Selection.pos1, b = Selection.pos2;
        o.add("pos1", a == null ? null : arr(a));
        o.add("pos2", b == null ? null : arr(b));
        o.addProperty("dimension", Selection.dimension);
        return o;
    }

    static JsonObject setSelection(int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        return onClient(() -> {
            Minecraft mc = mc();
            if (mc.level == null) throw new ApiException("游戏当前不在世界中");
            Selection.setBoth(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), Mc.dimensionId(mc.level));
            if (mc.player != null) {
                int[] s = Selection.size();
                Mc.chat(mc.player, Component.literal(String.format("§6[MineBlend]§r Blender 已设置选区 §b%d %d %d §r→ §b%d %d %d §7(%d×%d×%d)",
                        x1, y1, z1, x2, y2, z2, s[0], s[1], s[2])));
            }
            return selection();
        });
    }

    static JsonArray arr(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    // ------------------------------------------------------------------ chunk access

    /** 已加载 → 直接用；单人存档里已存在（region 文件里有）→ 从磁盘加载；从未生成 → null（当空气），不触发地形生成 */
    static final class ChunkSource {
        final Level world;
        final Map<Long, ChunkAccess> cache = new HashMap<>();
        final Map<Long, int[]> regionHeaders = new HashMap<>();
        Path regionDir;
        int missing;

        ChunkSource(Level world) {
            this.world = world;
            if (world instanceof ServerLevel sw) {
                Path root = sw.getServer().getWorldPath(LevelResource.ROOT);
                regionDir = DimensionType.getStorageFolder(sw.dimension(), root).resolve("region");
            }
        }

        ChunkAccess get(int cx, int cz) {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (cache.containsKey(key)) return cache.get(key);
            ChunkAccess c = load(cx, cz);
            if (c == null) missing++;
            cache.put(key, c);
            return c;
        }

        private ChunkAccess load(int cx, int cz) {
            if (world instanceof ServerLevel sw) {
                ChunkAccess loaded = sw.getChunkSource().getChunkNow(cx, cz);
                if (loaded != null) return loaded;
                if (!existsOnDisk(cx, cz)) return null;
                return sw.getChunk(cx, cz);
            }
            return world.getChunkSource().hasChunk(cx, cz) ? world.getChunk(cx, cz) : null;
        }

        private boolean existsOnDisk(int cx, int cz) {
            if (regionDir == null) return false;
            int rx = cx >> 5, rz = cz >> 5;
            long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
            int[] header = regionHeaders.computeIfAbsent(key, k -> readHeader(regionDir.resolve("r." + rx + "." + rz + ".mca")));
            return header.length > 0 && header[(cx & 31) + (cz & 31) * 32] != 0;
        }

        private static int[] readHeader(Path p) {
            if (!Files.isRegularFile(p)) return new int[0];
            try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
                if (f.length() < 4096) return new int[0];
                int[] h = new int[1024];
                for (int i = 0; i < 1024; i++) h[i] = f.readInt();
                return h;
            } catch (IOException e) {
                return new int[0];
            }
        }

        Holder<Biome> biome(int x, int y, int z, Holder<Biome> fallback) {
            ChunkAccess c = get(x >> 4, z >> 4);
            if (c == null) return fallback;
            try {
                return c.getNoiseBiome(x >> 2, y >> 2, z >> 2);
            } catch (Throwable t) {
                return fallback;
            }
        }
    }

    static Holder<Biome> plains(Level w) {
        return Mc.plains(w);
    }

    static final class PaletteBuilder {
        final Map<BlockState, Integer> index = new IdentityHashMap<>();
        final List<BlockState> states = new ArrayList<>();

        PaletteBuilder() {
            states.add(Blocks.AIR.defaultBlockState());
        }

        int idOf(BlockState st) {
            if (st.isAir()) return 0;
            Integer i = index.get(st);
            if (i != null) return i;
            int id = states.size();
            if (id >= 65535) throw new ApiException("方块种类过多（超过 65535）");
            states.add(st);
            index.put(st, id);
            return id;
        }
    }

    // ------------------------------------------------------------------ export

    static byte[] export(int x1, int y1, int z1, int x2, int y2, int z2, BlockMesher.Opts opts) throws Exception {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int minYReq = Math.min(y1, y2), maxYReq = Math.max(y1, y2);
        long t0 = System.currentTimeMillis();

        // 1) 世界线程：拍快照（外扩 1 格用于剔除相邻面）
        Object[] snap = onWorld(w -> {
            int minY = Math.max(minYReq, Mc.minY(w));
            int maxY = Math.min(maxYReq, Mc.maxY(w));
            if (maxY < minY) throw new ApiException("选区的 Y 范围不在世界高度内");
            int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
            long padded = (long) (sx + 2) * (sy + 2) * (sz + 2);
            if (padded > Integer.MAX_VALUE - 16)
                throw new ApiException(String.format("选区过大: %,d 方块，超过单次能处理的最大范围（约 21 亿方块），请分几次导入", (long) sx * sy * sz));

            int px0 = minX - 1, py0 = minY - 1, pz0 = minZ - 1;
            int psx = sx + 2, psy = sy + 2, psz = sz + 2;
            short[] ids;
            try {
                ids = new short[psx * psy * psz];
            } catch (OutOfMemoryError e) {
                throw new ApiException(String.format("游戏内存不足，无法读取 %,d 方块的选区，请缩小选区或分几次导入", (long) sx * sy * sz));
            }
            PaletteBuilder pal = new PaletteBuilder();
            ChunkSource src = new ChunkSource(w);
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            int wBottom = Mc.minY(w), wTop = Mc.maxY(w);
            for (int cx = px0 >> 4; cx <= (px0 + psx - 1) >> 4; cx++) {
                for (int cz = pz0 >> 4; cz <= (pz0 + psz - 1) >> 4; cz++) {
                    ChunkAccess c = src.get(cx, cz);
                    if (c == null) continue;
                    int xa = Math.max(px0, cx << 4), xb = Math.min(px0 + psx - 1, (cx << 4) + 15);
                    int za = Math.max(pz0, cz << 4), zb = Math.min(pz0 + psz - 1, (cz << 4) + 15);
                    for (int y = Math.max(py0, wBottom); y <= Math.min(py0 + psy - 1, wTop); y++) {
                        boolean padY = y < minY || y > maxY;
                        int si = c.getSectionIndex(y);
                        if (si >= 0 && si < c.getSections().length && c.getSections()[si].hasOnlyAir()) continue;
                        for (int z = za; z <= zb; z++) {
                            boolean padZ = z < minZ || z > maxZ;
                            int base = ((y - py0) * psz + (z - pz0)) * psx - px0;
                            for (int x = xa; x <= xb; x++) {
                                boolean pad = padY || padZ || x < minX || x > maxX;
                                if (pad && !opts.cullBoundary()) continue;
                                BlockState st = c.getBlockState(mp.set(x, y, z));
                                if (st.isAir()) continue;
                                ids[base + x] = (short) pal.idOf(st);
                            }
                        }
                    }
                }
            }
            // 群系（每 4×4 列一个，取选区中部高度）
            int bx = (psx + 3) / 4, bz = (psz + 3) / 4;
            @SuppressWarnings("unchecked")
            Holder<Biome>[] biomes = new Holder[bx * bz];
            Holder<Biome> fallback = plains(w);
            int midY = (minY + maxY) / 2;
            for (int j = 0; j < bz; j++)
                for (int i = 0; i < bx; i++)
                    biomes[j * bx + i] = src.biome(px0 + i * 4 + 1, midY, pz0 + j * 4 + 1, fallback);

            SnapshotView view = new SnapshotView(px0, py0, pz0, psx, psy, psz, ids,
                    pal.states.toArray(new BlockState[0]), biomes, bx, bz, Mc.minY(w), w.getHeight(), w.getLightEngine());
            return new Object[]{view, new int[]{minY, sx, sy, sz, src.missing}};
        });
        SnapshotView view = (SnapshotView) snap[0];
        int[] dims = (int[]) snap[1];
        int minY = dims[0], sx = dims[1], sy = dims[2], sz = dims[3];
        long t1 = System.currentTimeMillis();

        // 2) 当前（接口工作）线程：用烘焙模型建网格，不占用游戏线程
        MeshOut out = new MeshOut();
        Textures tex = new Textures();
        BlockMesher mesher = new BlockMesher(view, out, tex, opts, minX, minY, minZ);
        mesher.run(sx, sy, sz);
        int blockFaces = out.faces();
        long t2 = System.currentTimeMillis();

        // 3) 渲染线程：录制方块实体和实体
        int[] capStats = new int[3];
        if (opts.blockEntities() || opts.entities()) {
            onClient(() -> {
                Minecraft mc = mc();
                if (mc.level == null) return null;
                Capture cap = new Capture(out, tex);
                int mx = minX + sx - 1, my = minY + sy - 1, mz = minZ + sz - 1;
                if (opts.blockEntities()) cap.captureBlockEntities(mc.level, minX, minY, minZ, mx, my, mz);
                if (opts.entities()) cap.captureEntities(mc.level, minX, minY, minZ, mx, my, mz, opts.mobs());
                capStats[0] = cap.blockEntities;
                capStats[1] = cap.entities;
                capStats[2] = cap.failed;
                return null;
            });
        }
        long t3 = System.currentTimeMillis();

        JsonObject h = new JsonObject();
        h.addProperty("ok", true);
        JsonArray origin = new JsonArray();
        origin.add(minX);
        origin.add(minY);
        origin.add(minZ);
        h.add("origin", origin);
        JsonArray size = new JsonArray();
        size.add(sx);
        size.add(sy);
        size.add(sz);
        h.add("size", size);
        JsonObject stats = new JsonObject();
        stats.addProperty("blockFaces", blockFaces);
        stats.addProperty("capturedFaces", out.faces() - blockFaces);
        stats.addProperty("blockEntities", capStats[0]);
        stats.addProperty("entities", capStats[1]);
        stats.addProperty("failed", mesher.failed + capStats[2]);
        stats.addProperty("spriteFixed", tex.mismatched);
        stats.addProperty("dynamicModels", mesher.dynamic);
        stats.addProperty("missingChunks", dims[4]);
        stats.addProperty("msSnapshot", t1 - t0);
        stats.addProperty("msMesh", t2 - t1);
        stats.addProperty("msCapture", t3 - t2);
        h.add("stats", stats);
        h.add("materials", out.materialsJson());
        JsonArray blockArr = new JsonArray();
        for (String b : out.blocks) blockArr.add(b);
        h.add("blocks", blockArr);
        JsonArray rnd = new JsonArray();
        for (int b : out.randomBlocks) rnd.add(b);
        h.add("randomBlocks", rnd);
        return packMesh(h, out, tex);
    }

    private static byte[] packMesh(JsonObject h, MeshOut out, Textures tex) {
        int nv = out.pos.size() / 3, nf = out.faces();
        Map<String, byte[]> pngs = new java.util.LinkedHashMap<>();
        for (MeshOut.Mat m : out.mats)
            if (m.texture != null && tex.png.get(m.texture) != null) pngs.put(m.texture, tex.png.get(m.texture));

        long off = 0;
        JsonObject sec = new JsonObject();
        off = section(sec, "pos", off, nv * 12L);
        off = section(sec, "uv", off, nv * 8L);
        off = section(sec, "faceSize", off, align4(nf));
        off = section(sec, "faceMat", off, nf * 4L);
        off = section(sec, "faceColor", off, nf * 4L);
        off = section(sec, "faceBlock", off, nf * 4L);
        off = section(sec, "facePos", off, nf * 12L);
        JsonArray texArr = new JsonArray();
        List<byte[]> blobs = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : pngs.entrySet()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", e.getKey());
            t.addProperty("offset", off);
            t.addProperty("length", e.getValue().length);
            off += align4(e.getValue().length);
            blobs.add(e.getValue());
            byte[] strip = tex.animPng.get(e.getKey());
            for (String kind : new String[]{"normal", "specular"}) {
                byte[] extra = (kind.equals("normal") ? tex.normalPng : tex.specularPng).get(e.getKey());
                if (extra == null) continue;
                JsonObject x = new JsonObject();
                x.addProperty("offset", off);
                x.addProperty("length", extra.length);
                off += align4(extra.length);
                blobs.add(extra);
                t.add(kind, x);
            }
            if (strip != null) {
                JsonObject a = tex.animInfo.get(e.getKey()).deepCopy();
                a.addProperty("offset", off);
                a.addProperty("length", strip.length);
                off += align4(strip.length);
                blobs.add(strip);
                t.add("anim", a);
            }
            texArr.add(t);
        }
        h.add("sections", sec);
        h.add("textures", texArr);
        h.addProperty("verts", nv);
        h.addProperty("faces", nf);
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        int hlen = align4(hb.length);
        long total = 4L + hlen + off;
        if (total > Integer.MAX_VALUE - 16) throw new ApiException("导出数据超过 2GB，请缩小选区");

        ByteBuffer buf = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(hlen);
        buf.put(hb);
        while (buf.position() < 4 + hlen) buf.put((byte) ' ');
        for (int i = 0; i < nv * 3; i++) buf.putFloat(out.pos.getFloat(i));
        for (int i = 0; i < nv * 2; i++) buf.putFloat(out.uv.getFloat(i));
        for (int i = 0; i < nf; i++) buf.put(out.faceSize.getByte(i));
        for (int i = nf; i < align4(nf); i++) buf.put((byte) 0);
        for (int i = 0; i < nf; i++) buf.putInt(out.faceMat.getInt(i));
        for (int i = 0; i < nf; i++) buf.putInt(out.faceColor.getInt(i));
        for (int i = 0; i < nf; i++) buf.putInt(out.faceBlock.getInt(i));
        for (int i = 0; i < nf * 3; i++) buf.putInt(out.facePos.getInt(i));
        for (byte[] png : blobs) {
            buf.put(png);
            for (int i = png.length; i < align4(png.length); i++) buf.put((byte) 0);
        }
        return buf.array();
    }

    static int align4(int n) {
        return (n + 3) & ~3;
    }

    static long section(JsonObject sec, String name, long off, long len) {
        JsonArray a = new JsonArray();
        a.add(off);
        a.add(len);
        sec.add(name, a);
        return off + len;
    }

    /** 头部 JSON + 若干二进制段 */
    static byte[] pack(JsonObject h, byte[]... blobs) {
        JsonObject sec = new JsonObject();
        long off = 0;
        for (int i = 0; i < blobs.length; i++) off = section(sec, "b" + i, off, align4(blobs[i].length));
        h.add("sections", sec);
        byte[] hb = h.toString().getBytes(StandardCharsets.UTF_8);
        int hlen = align4(hb.length);
        ByteBuffer buf = ByteBuffer.allocate((int) (4 + hlen + off)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(hlen);
        buf.put(hb);
        while (buf.position() < 4 + hlen) buf.put((byte) ' ');
        for (byte[] b : blobs) {
            buf.put(b);
            for (int i = b.length; i < align4(b.length); i++) buf.put((byte) 0);
        }
        return buf.array();
    }

    // ------------------------------------------------------------------ top-down map

    static byte[] topview(int x1, int z1, int x2, int z2, int step, int ppb) throws Exception {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int nx = (maxX - minX) / step + 1, nz = (maxZ - minZ) / step + 1;
        ppb = Math.max(1, Math.min(16, ppb));
        if ((long) nx * nz * ppb * ppb > 64L * 1024 * 1024) throw new ApiException("预览图过大，请减小半径、增大采样间隔或减小每格像素");

        Object[] res = onWorld(w -> {
            PaletteBuilder pal = new PaletteBuilder();
            short[] heights = new short[nx * nz];
            short[] ids = new short[nx * nz];
            @SuppressWarnings("unchecked")
            Holder<Biome>[] biomes = new Holder[nx * nz];
            ChunkSource src = new ChunkSource(w);
            Holder<Biome> fallback = plains(w);
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            int bottom = Mc.minY(w);
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    int x = minX + ix * step, z = minZ + iz * step;
                    int k = iz * nx + ix;
                    ChunkAccess c = src.get(x >> 4, z >> 4);
                    if (c == null) {
                        heights[k] = Short.MIN_VALUE;
                        continue;
                    }
                    int top = c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                    BlockState st = top >= bottom ? c.getBlockState(mp.set(x, top, z)) : Blocks.AIR.defaultBlockState();
                    // 跳过顶上的花草等无碰撞装饰，取下面的地面
                    for (int i = 0; i < 4 && top > bottom && st.getFluidState().isEmpty()
                            && st.getCollisionShape(w, mp).isEmpty() && !st.isAir(); i++) {
                        BlockState below = c.getBlockState(mp.set(x, top - 1, z));
                        if (below.isAir()) break;
                        top--;
                        st = below;
                    }
                    heights[k] = (short) top;
                    ids[k] = (short) pal.idOf(st);
                    biomes[k] = src.biome(x, top, z, fallback);
                }
            }
            return new Object[]{pal.states.toArray(new BlockState[0]), heights, ids, biomes, bottom};
        });
        BlockState[] palette = (BlockState[]) res[0];
        short[] heights = (short[]) res[1];
        short[] ids = (short[]) res[2];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = (Holder<Biome>[]) res[3];
        int bottom = (int) res[4];

        TopView.Result img = new TopView(palette).render(heights, ids, biomes, nx, nz, step, ppb, minX, minZ, bottom);

        JsonObject h = new JsonObject();
        h.addProperty("ok", true);
        h.addProperty("minX", minX);
        h.addProperty("minZ", minZ);
        h.addProperty("nx", nx);
        h.addProperty("nz", nz);
        h.addProperty("step", step);
        h.addProperty("ppb", ppb);
        h.addProperty("bottom", bottom);
        h.addProperty("minH", img.minH());
        h.addProperty("maxH", img.maxH());
        // b1: 每列顶面高度 int16（无数据 = -32768），供 Blender 地图窗口自动计算选区高度
        ByteBuffer hb = ByteBuffer.allocate(heights.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : heights) hb.putShort(v);
        return pack(h, img.png(), hb.array());
    }

    // ------------------------------------------------------------------ column runs (选区剖面，Blender 本地实时计算)

    /**
     * 选区内每一列从世界底部到地表的方块，按「连续相同方块」压缩成段。Blender 拿到后，调高度时在本地即时算出剖面图。
     * 段的含义：第 i 段从 ys[i] 开始，到下一段开始前一格结束；每列最后补一段空气（地表 +1 起）。
     */
    static byte[] columns(int x1, int z1, int x2, int z2, int step, int ppb) throws Exception {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int nx = (maxX - minX) / step + 1, nz = (maxZ - minZ) / step + 1;
        ppb = Math.max(1, Math.min(16, ppb));
        if ((long) nx * nz > 512L * 512) throw new ApiException("剖面范围过大，请增大采样间隔");
        final int fppb = ppb;

        Object[] res = onWorld(w -> {
            PaletteBuilder pal = new PaletteBuilder();
            int n = nx * nz;
            int[] offs = new int[n + 1];
            it.unimi.dsi.fastutil.shorts.ShortArrayList ys = new it.unimi.dsi.fastutil.shorts.ShortArrayList();
            it.unimi.dsi.fastutil.shorts.ShortArrayList ids = new it.unimi.dsi.fastutil.shorts.ShortArrayList();
            short[] biomeOf = new short[n];
            List<Holder<Biome>> biomes = new ArrayList<>();
            Map<Holder<Biome>, Integer> biomeIdx = new HashMap<>();
            ChunkSource src = new ChunkSource(w);
            Holder<Biome> fallback = plains(w);
            int bottom = Mc.minY(w);
            for (int iz = 0; iz < nz; iz++) {
                for (int ix = 0; ix < nx; ix++) {
                    int k = iz * nx + ix;
                    offs[k] = ys.size();
                    int x = minX + ix * step, z = minZ + iz * step;
                    ChunkAccess c = src.get(x >> 4, z >> 4);
                    if (c == null) continue;
                    int lx = x & 15, lz = z & 15;
                    int surf = c.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                    net.minecraft.world.level.chunk.LevelChunkSection[] secs = c.getSections();
                    int prev = -1;
                    for (int y = bottom; y <= surf; y++) {
                        int si = c.getSectionIndex(y);
                        if (si < 0 || si >= secs.length) break;
                        net.minecraft.world.level.chunk.LevelChunkSection sec = secs[si];
                        int id;
                        if (sec == null || sec.hasOnlyAir()) {
                            id = 0;
                            if (prev != 0) {
                                ys.add((short) y);
                                ids.add((short) 0);
                                prev = 0;
                            }
                            y = (c.getSectionYFromSectionIndex(si) << 4) + 15; // 跳过整个空段
                            continue;
                        }
                        id = pal.idOf(sec.getBlockState(lx, y & 15, lz));
                        if (id != prev) {
                            ys.add((short) y);
                            ids.add((short) id);
                            prev = id;
                        }
                    }
                    ys.add((short) (surf + 1));
                    ids.add((short) 0);
                    Holder<Biome> b = src.biome(x, surf, z, fallback);
                    Integer bi = biomeIdx.get(b);
                    if (bi == null) {
                        bi = biomes.size();
                        biomes.add(b);
                        biomeIdx.put(b, bi);
                    }
                    biomeOf[k] = (short) (int) bi;
                }
            }
            offs[n] = ys.size();
            return new Object[]{pal.states.toArray(new BlockState[0]), offs, ys.toShortArray(), ids.toShortArray(), biomeOf, biomes, bottom};
        });
        BlockState[] palette = (BlockState[]) res[0];
        int[] offs = (int[]) res[1];
        short[] ys = (short[]) res[2];
        short[] ids = (short[]) res[3];
        short[] biomeOf = (short[]) res[4];
        @SuppressWarnings("unchecked")
        List<Holder<Biome>> biomes = (List<Holder<Biome>>) res[5];
        int bottom = (int) res[6];

        TopView tv = new TopView(palette);
        int P = palette.length, B = Math.max(1, biomes.size());
        byte[] tiles = new byte[P * fppb * fppb * 4];
        ByteBuffer tint = ByteBuffer.allocate(P * B * 4).order(ByteOrder.LITTLE_ENDIAN);
        JsonArray empty = new JsonArray();
        for (int i = 0; i < P; i++) {
            BlockState st = palette[i];
            boolean decor = !st.isAir() && st.getFluidState().isEmpty()
                    && st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            // 空气、没有可画的顶面、无碰撞的装饰（花草、火把等）在剖面里当作透明，显示下面的方块
            if (st.isAir() || decor || !tv.tile(i, fppb, tiles, i * fppb * fppb * 4)) empty.add(i);
            for (int b = 0; b < B; b++) tint.putInt(biomes.isEmpty() ? 0xFFFFFF : tv.tintColor(i, biomes.get(b)));
        }

        JsonObject h = new JsonObject();
        h.addProperty("ok", true);
        h.addProperty("minX", minX);
        h.addProperty("minZ", minZ);
        h.addProperty("nx", nx);
        h.addProperty("nz", nz);
        h.addProperty("step", step);
        h.addProperty("ppb", fppb);
        h.addProperty("bottom", bottom);
        h.addProperty("palette", P);
        h.addProperty("biomes", B);
        h.add("empty", empty);
        ByteBuffer ob = ByteBuffer.allocate(offs.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : offs) ob.putInt(v);
        ByteBuffer yb = ByteBuffer.allocate(ys.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : ys) yb.putShort(v);
        ByteBuffer ib = ByteBuffer.allocate(ids.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : ids) ib.putShort(v);
        ByteBuffer bb = ByteBuffer.allocate(biomeOf.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : biomeOf) bb.putShort(v);
        // b0 每列段起点(int32, n+1)  b1 段起始 Y(int16)  b2 段方块(uint16, 调色板下标)  b3 每列群系(uint16)
        // b4 调色板贴图 RGBA8(P×ppb×ppb，未染色)  b5 染色表 int32 0xRRGGBB (P×B)
        return pack(h, ob.array(), yb.array(), ib.array(), bb.array(), tiles, tint.array());
    }

}
