"""导入：向 mod 请求选区网格并在 Blender 中生成物体与材质。"""
import time

import bpy
import numpy as np
from mathutils import Vector

from . import blender_io as bio
from . import bridge, mesher


def S(context):
    return context.scene.mineblend


WARN_VOLUME = 8_000_000   # 选区超过这么多方块时提醒（不阻止导入）
WARN_OBJECTS = 20_000     # 按单个方块拆分时，可能超过这么多物体就提醒


def size_warnings(mn, mx, mode):
    """超大选区的提醒文字（空列表 = 没问题）。只提醒，不限制导入。"""
    vol = 1
    for i in range(3):
        vol *= abs(mx[i] - mn[i]) + 1
    out = []
    if vol > WARN_VOLUME:
        out.append(f"导入选区过大：{vol:,} 方块，导入可能很慢并占用大量内存，请注意")
    if mode == "BLOCK" and vol > WARN_OBJECTS:
        out.append("按单个方块拆分：可能生成上万个物体，Blender 可能会变得很卡")
    return out


def do_import(context, mn, mx, label):
    s = S(context)
    wm = context.window_manager
    wm.progress_begin(0, 100)
    try:
        t0 = time.time()
        params = dict(x1=mn[0], y1=mn[1], z1=mn[2], x2=mx[0], y2=mx[1], z2=mx[2],
                      plants=int(s.include_plants), fluids=int(s.include_fluids),
                      blockEntities=int(s.include_block_entities), entities=int(s.include_entities),
                      mobs=int(s.include_mobs), cull=int(s.cull_boundary))
        pk = bridge.get_packet(s.host, s.port, "/export", params, timeout=1800)
        h = pk.header
        wm.progress_update(40)
        t1 = time.time()

        md = mesher.assemble(pk, merge=s.merge_vertices)
        if md.faces == 0:
            raise bridge.BridgeError("选区内没有可见的方块或实体（可能全是空气，或区块未生成）")
        wm.progress_update(60)

        # 贴图
        images, anims, pbr_src, pbr_cache = {}, {}, {}, {}
        for t in h.get("textures", []):
            images[t["name"]] = bio.load_png(t["name"], pk.blob(t["offset"], t["length"]), overwrite=s.overwrite_materials)
            if t.get("anim"):
                anims[t["name"]] = t["anim"]
            if t.get("normal") or t.get("specular"):
                pbr_src[t["name"]] = (t.get("normal"), t.get("specular"))
        wm.progress_update(75)

        # 每个（材质, 染色, 叠加层）组合一个材质槽：染色/叠加层都烘焙进贴图副本，材质只有「图像纹理 → 原理化 BSDF」
        mats_info = h["materials"]
        starts = np.zeros(md.faces, np.int64)
        np.cumsum(md.face_size[:-1], out=starts[1:])
        face_rgb = md.loop_color[starts].astype(np.int64) & 0xFFFFFF
        combo = np.stack([md.face_mat.astype(np.int64), face_rgb, md.face_ov_mat, md.face_ov_color & 0xFFFFFF], axis=1)
        ucombo, inv = np.unique(combo, axis=0, return_inverse=True)

        def layer_of(info):
            return info.get("layer") or ("translucent" if info.get("translucent") else "cutout" if info.get("alpha") else "solid")

        def tex_of(mi, rgb, allow_anim=True):
            """返回 (image, 序列帧数)；染色、镂空二值化、动画展开都在这里烘焙进贴图"""
            info = mats_info[mi]
            tname = info.get("texture")
            if not tname:
                return None, 0
            cut = layer_of(info) == "cutout"
            a = anims.get(tname)
            if a is not None and allow_anim:
                try:
                    return bio.anim_sequence(tname, pk.blob(a["offset"], a["length"]), a, rgb, cut,
                                             overwrite=s.overwrite_materials)
                except Exception as e:
                    print("MineBlend: 动画贴图展开失败", tname, e)
            img = images.get(tname) or bpy.data.images.get(bio._short(tname))
            if img is not None and rgb != 0xFFFFFF:
                img = bio.tinted_image(img, rgb, overwrite=s.overwrite_materials)
            if img is not None and cut:
                img = bio.cutout_image(img, overwrite=s.overwrite_materials)
            return img, 0

        def pbr_of(mi):
            tname = mats_info[mi].get("texture")
            if not s.use_pbr or tname not in pbr_src:
                return (None, None)
            if tname not in pbr_cache:
                nb, sb = pbr_src[tname]
                base = images.get(tname)
                aspect = (base.size[1] / base.size[0]) if base is not None and base.size[0] else 1.0
                try:
                    n = bio.pbr_normal(tname, pk.blob(nb["offset"], nb["length"]), aspect, s.overwrite_materials) if nb else None
                    sp = bio.pbr_specular(tname, pk.blob(sb["offset"], sb["length"]), aspect, s.overwrite_materials) if sb else None
                except Exception as e:
                    print("MineBlend: PBR 贴图处理失败", tname, e)
                    n = sp = None
                pbr_cache[tname] = (n, sp)
            return pbr_cache[tname]

        materials, slot_of_name, key_slot = [], {}, np.zeros(len(ucombo), np.int32)
        n_anim = 0
        for i, (mi, rgb, omi, orgb) in enumerate(ucombo.tolist()):
            info = mats_info[mi]
            img, seq = tex_of(mi, rgb)
            suffix = ""
            if omi >= 0 and img is not None and seq == 0:
                oimg, _ = tex_of(omi, orgb, allow_anim=False)
                if oimg is not None:
                    oname = bio.material_name(mats_info[omi]["name"])[3:]
                    comp = bio.overlay_image(img, oimg, f"{img.name}+{oname}#{orgb:06x}", overwrite=s.overwrite_materials)
                    if comp is not None:
                        img = comp
                        suffix = f"+{oname}#{orgb:06x}"
            mat = bio.block_material(info, img, rgb, overwrite=s.overwrite_materials, suffix=suffix, seq_frames=seq,
                                     pbr=pbr_of(mi))
            n_anim += seq > 1
            if mat.name not in slot_of_name:
                slot_of_name[mat.name] = len(materials)
                materials.append(mat)
            key_slot[i] = slot_of_name[mat.name]
        md.face_mat = key_slot[inv.reshape(-1)]
        md.loop_color = None

        # 合并相邻同材质面（按单个方块拆分时没有意义）
        faces_before = md.faces
        if s.merge_faces and s.split_mode != "BLOCK":
            free = None
            if s.merge_random_rot and h.get("randomBlocks"):
                free = np.isin(md.face_block, np.asarray(h["randomBlocks"], np.int32))
            md, slots = mesher.greedy_merge(md, group=md.face_block if s.split_mode == "TYPE" else None, free_rot=free)
            for i in slots:
                for n in materials[i].node_tree.nodes:
                    if n.type == "TEX_IMAGE":
                        n.extension = "REPEAT"  # 合并后的大面 UV 超出 0~1，贴图需平铺
        wm.progress_update(85)

        ox, oy, oz = h["origin"]
        sx, sy, sz = h["size"]
        name = f"MC_{label}_{ox}_{oy}_{oz}"
        base = Vector((-sx / 2.0, sz / 2.0, 0.0)) if s.recenter else Vector((ox, -oz, oy))
        mode = s.split_mode
        created = build_objects(md, materials, h.get("blocks", []), mode, name, base, (ox, oy, oz))
        for o in created:
            o["mc_min"] = [ox, oy, oz]
            o["mc_max"] = [ox + sx - 1, oy + sy - 1, oz + sz - 1]

        for o in context.selected_objects:
            o.select_set(False)
        for o in created[:5000]:
            o.select_set(True)
        context.view_layer.objects.active = created[0]
        obj = created[0]
        wm.progress_update(100)

        st = h.get("stats", {})
        merged = f"（合并前 {faces_before:,}）" if md.faces != faces_before else ""
        n_pbr = sum(1 for v in pbr_cache.values() if v[0] is not None or v[1] is not None)
        msg = (f"导入完成：{sx}×{sy}×{sz}，{len(created):,} 个物体，{md.faces:,} 面{merged}，{len(materials)} 种材质，{len(images)} 张贴图（动画 {n_anim}，PBR {n_pbr}）"
               f"（方块实体 {st.get('blockEntities', 0)}，实体 {st.get('entities', 0)}）"
               f" 游戏端 {t1 - t0:.1f}s / Blender {time.time() - t1:.1f}s")
        if len(created) > WARN_OBJECTS:
            msg += f"；生成了 {len(created):,} 个物体，Blender 可能会变卡"
        if st.get("missingChunks"):
            msg += f"；{st['missingChunks']} 个区块未生成/未加载，按空气处理"
        if st.get("failed"):
            msg += f"；{st['failed']} 个对象建模失败（见游戏日志）"
        s.status = msg
        return obj, msg
    finally:
        wm.progress_end()


SPLIT_ITEMS = [
    ("MERGED", "合并为一个物体", "整个选区导入为一个网格物体"),
    ("TYPE", "按方块类型拆分", "每种方块（以及每种实体/方块实体）一个物体"),
    ("BLOCK", "按单个方块拆分", "每个方块一个物体，原点在方块底面中心（物体数可能非常多）"),
]


def _short_id(bid):
    if bid.startswith("entity:"):
        bid = bid[len("entity:"):]
        prefix = "实体_"
    else:
        prefix = ""
    if bid.startswith("minecraft:"):
        bid = bid[len("minecraft:"):]
    return prefix + bid.replace(":", "_")


def _sub_mesh(md, faces, fstart, pivot):
    """取 faces 这些面组成一个子网格（顶点平移 -pivot）"""
    fsz = md.face_size[faces]
    total = int(fsz.sum())
    offs = np.repeat(np.cumsum(fsz) - fsz, fsz)
    loops = np.repeat(fstart[faces], fsz) + (np.arange(total) - offs)
    lv = md.loop_vert[loops]
    uv_idx, inv = np.unique(lv, return_inverse=True)

    class _M:
        pass
    m = _M()
    m.verts = md.verts[uv_idx] - np.asarray(pivot, np.float32)
    m.loop_vert = inv.reshape(-1).astype(np.int32)
    m.face_size = fsz
    m.loop_uv = md.loop_uv[loops]
    m.loop_color = None
    used, m.face_mat = np.unique(md.face_mat[faces], return_inverse=True)
    m.face_mat = m.face_mat.reshape(-1).astype(np.int32)
    return m, used


def build_objects(md, materials, blocks, mode, name, base, origin):
    root = bio.get_collection()
    if mode == "MERGED":
        obj = bio.make_mesh_object(name, md, materials, collection=root)
        obj.location = base
        return [obj]

    nf = md.faces
    fstart = np.zeros(nf, np.int64)
    np.cumsum(md.face_size[:-1], out=fstart[1:])
    fb = md.face_block.astype(np.int64)
    if mode == "TYPE":
        key = fb
    else:
        p = md.face_pos.astype(np.int64) + 32768
        key = (fb << 48) | (p[:, 0] << 32) | (p[:, 1] << 16) | p[:, 2]
    order = np.argsort(key, kind="stable")
    ukeys, starts = np.unique(key[order], return_index=True)
    ends = list(starts[1:]) + [nf]

    col = bpy.data.collections.new(name)
    root.children.link(col)
    ox, oy, oz = origin
    created = []
    for k, a, b in zip(ukeys.tolist(), starts.tolist(), ends):
        faces = order[a:b]
        bi = int(md.face_block[faces[0]])
        bid = blocks[bi] if 0 <= bi < len(blocks) else "unknown"
        if mode == "TYPE":
            pivot = (0.0, 0.0, 0.0)
            oname = "MC_" + _short_id(bid)
        else:
            px, py, pz = (int(v) for v in md.face_pos[faces[0]])
            pivot = (px + 0.5, -(pz + 0.5), float(py))
            oname = f"{_short_id(bid)}_{px + ox}_{py + oy}_{pz + oz}"
        sub, used = _sub_mesh(md, faces, fstart, pivot)
        obj = bio.make_mesh_object(oname, sub, [materials[i] for i in used], collection=col)
        obj.location = base + Vector(pivot)
        obj["mc_block"] = bid
        if mode == "BLOCK":
            obj["mc_pos"] = [px + ox, py + oy, pz + oz]
        created.append(obj)
    return created
