"""把 mod 导出的多边形数据整理成 Blender 网格数组（numpy，不依赖 bpy）。

MC 坐标 (x 东, y 上, z 南) → Blender (X = x, Y = -z, Z = y)，纯旋转，不翻转法线。
"""
import numpy as np


class MeshData:
    def __init__(self, verts, loop_vert, face_size, face_mat, loop_uv, loop_color, face_ov_mat=None, face_ov_color=None,
                 face_block=None, face_pos=None):
        self.face_block = face_block          # (nf,) int32  所属方块/实体类型（header['blocks'] 下标）
        self.face_pos = face_pos              # (nf, 3) int32  所属方块坐标（MC，相对选区最小角）
        self.face_ov_mat = face_ov_mat        # (nf,) int64  叠加层材质（-1 = 无），如草方块侧面的绿色覆盖层
        self.face_ov_color = face_ov_color    # (nf,) int64  叠加层染色
        self.verts = verts            # (nv, 3) float32  Blender 坐标（相对选区最小角）
        self.loop_vert = loop_vert    # (nl,) int32
        self.face_size = face_size    # (nf,) int32  3 或 4
        self.face_mat = face_mat      # (nf,) int32  → header['materials'] 下标
        self.loop_uv = loop_uv        # (nl, 2) float32  Blender UV（v 向上）
        self.loop_color = loop_color  # (nl,) uint32  0xRRGGBB 染色

    @property
    def faces(self):
        return len(self.face_size)


def assemble(packet, merge=True, keep_block_faces=False):
    mats = packet.header.get("materials", [])
    mat_alpha = np.array([bool(m.get("alpha")) for m in mats] + [False], bool)
    h = packet.header
    nv, nf = int(h["verts"]), int(h["faces"])
    buf = packet.data

    def arr(name, dtype, count):
        off, _ = packet.section(name)
        return np.frombuffer(buf, dtype=dtype, count=count, offset=off)

    pos = arr("pos", "<f4", nv * 3).reshape(nv, 3)
    uv = arr("uv", "<f4", nv * 2).reshape(nv, 2)
    fs = arr("faceSize", "u1", nf).astype(np.int32)
    fm = arr("faceMat", "<i4", nf).astype(np.int32)
    fc = arr("faceColor", "<u4", nf)
    sec = packet.header["sections"]
    fb = arr("faceBlock", "<i4", nf) if "faceBlock" in sec else np.zeros(nf, np.int32)
    fp = arr("facePos", "<i4", nf * 3).reshape(nf, 3) if "facePos" in sec else np.zeros((nf, 3), np.int32)
    return build(pos, uv, fs, fm, fc, merge, mat_alpha, fb, fp, keep_block_faces)


PUSH = 0.002  # 重合但不能合成的面（墙上的涂鸦、水渍等贴花）沿法线推出的距离（方块）


def build(pos, uv, fs, fm, fc, merge=True, mat_alpha=None, fb=None, fp=None, keep_block_faces=False):
    """keep_block_faces：按单个方块拆分时为 True —— 不同方块之间重合的面（相邻方块贴在一起的两面）都保留，
    各自沿法线推出一点，保证每个方块单独移动时都是完整的封闭模型。"""
    nf = len(fs)
    if nf == 0:
        z = np.zeros(0, np.int32)
        return MeshData(np.zeros((0, 3), np.float32), z, z, z, np.zeros((0, 2), np.float32), np.zeros(0, np.uint32),
                        z.astype(np.int64), z.astype(np.int64), z, np.zeros((0, 3), np.int32))
    if fb is None:
        fb = np.zeros(nf, np.int32)
    if fp is None:
        fp = np.zeros((nf, 3), np.int32)

    bl = np.empty_like(pos)
    bl[:, 0] = pos[:, 0]
    bl[:, 1] = -pos[:, 2]
    bl[:, 2] = pos[:, 1]

    # 重合面检测（覆盖层合成、去重、贴花推出）总是按量化后的位置判断，与「合并顶点」选项无关；
    # 选项只决定输出网格里相同位置的顶点是否共用
    mverts, kinv = _merge(bl)
    if merge:
        verts, inv = mverts, kinv
    else:
        verts, inv = bl, np.arange(len(bl), dtype=np.int64)

    starts = np.zeros(nf, np.int64)
    np.cumsum(fs[:-1], out=starts[1:])

    # (nf, 4) 位置索引，三角形第 4 个填 -1
    idx4 = np.full((nf, 4), -1, np.int64)
    for k in (3, 4):
        m = fs == k
        if m.any():
            idx4[m, :k] = kinv[starts[m, None] + np.arange(k)[None, :]]
    a, b, c, d = idx4.T
    tri = fs == 3
    ok = (a != b) & (b != c) & (a != c)
    ok &= tri | ((c != d) & (d != a) & (b != d))

    # 重合的面：
    #  - 材质和染色都相同（双面面片、重复面）→ 只留一个
    #  - 同朝向、同 UV 的另一张贴图（如草方块侧面的覆盖层）→ 记录下来，合成进底层贴图
    #  - 其它（墙上的涂鸦/水渍贴花、背靠背的不同方块）→ 都保留，带透明的那个沿法线推出一点，避免闪烁和互相吞掉
    key = np.sort(idx4, axis=1)
    if keep_block_faces:
        # 按单个方块拆分：只在同一个方块内部找重合面（覆盖层合成、去重），不同方块贴在一起的面各自保留
        key = np.concatenate([key, np.asarray(fp, np.int64)], axis=1)
    keep = np.zeros(nf, bool)
    push = np.zeros(nf, np.float32)
    ov_mat = np.full(nf, -1, np.int64)
    ov_col = np.zeros(nf, np.int64)
    if mat_alpha is None:
        mat_alpha = np.zeros(int(fm.max()) + 2 if nf else 1, bool)
    cand = np.nonzero(ok)[0]
    if len(cand):
        _, first, ginv = np.unique(key[cand], axis=0, return_index=True, return_inverse=True)
        keep[cand[first]] = True
        kept_of = cand[first][ginv.reshape(-1)]
        dup = kept_of != cand
        layers = {}
        for f, k in zip(cand[dup], kept_of[dup]):
            if fm[f] == fm[k] and fc[f] == fc[k]:
                continue
            n = int(fs[k])
            A, B = idx4[k, :n], idx4[f, :n]
            r = np.nonzero(B == A[0])[0]
            same_dir = bool(len(r)) and fs[f] == fs[k] and np.array_equal(B[(np.arange(n) + r[0]) % n], A)
            if same_dir and ov_mat[k] < 0 and mat_alpha[fm[f]]:
                rot = (np.arange(n) + r[0]) % n
                if np.allclose(uv[starts[f] + rot], uv[starts[k]:starts[k] + n], atol=1e-4):
                    ov_mat[k] = fm[f]
                    ov_col[k] = int(fc[f])
                    continue
            keep[f] = True
            depth = layers.get(k, 0) + 1
            layers[k] = depth
            if mat_alpha[fm[k]] and not mat_alpha[fm[f]]:
                push[k] = PUSH * depth  # 底下那层带透明：把它推到上面
            else:
                push[f] = PUSH * depth

    loops_keep = np.repeat(keep, fs)
    loop_vert = inv[loops_keep].astype(np.int32)
    loop_uv = uv[loops_keep].astype(np.float32).copy()
    loop_uv[:, 1] = 1.0 - loop_uv[:, 1]
    fs_k = fs[keep]
    loop_color = np.repeat(fc[keep], fs_k)

    # 需要推出的面：复制它的顶点并沿面法线平移
    push_k = push[keep]
    if (push_k > 0).any():
        starts_k = np.zeros(len(fs_k), np.int64)
        np.cumsum(fs_k[:-1], out=starts_k[1:])
        sel = np.nonzero(push_k > 0)[0]
        v0 = verts[loop_vert[starts_k[sel]]]
        v1 = verts[loop_vert[starts_k[sel] + 1]]
        v2 = verts[loop_vert[starts_k[sel] + 2]]
        nrm = np.cross(v1 - v0, v2 - v0)
        nrm /= np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-12)
        loops_sel = np.concatenate([starts_k[i] + np.arange(fs_k[i]) for i in sel])
        owner = np.repeat(np.arange(len(sel)), fs_k[sel])
        new_v = verts[loop_vert[loops_sel]] + nrm[owner] * push_k[sel][owner, None]
        loop_vert = loop_vert.copy()
        loop_vert[loops_sel] = len(verts) + np.arange(len(loops_sel))
        verts = np.concatenate([verts, new_v.astype(verts.dtype)])

    # 删除没被用到的顶点
    used = np.zeros(len(verts), bool)
    used[loop_vert] = True
    remap = np.cumsum(used, dtype=np.int64) - 1
    verts = verts[used]
    loop_vert = remap[loop_vert].astype(np.int32)

    return MeshData(verts.astype(np.float32), loop_vert, fs_k.astype(np.int32), fm[keep].astype(np.int32),
                    loop_uv, loop_color, ov_mat[keep], ov_col[keep],
                    np.asarray(fb)[keep].astype(np.int32), np.asarray(fp)[keep].astype(np.int32))


def _merge(v):
    """按 1/1024 方块网格量化后合并重复顶点。"""
    q = np.round(v.astype(np.float64) * 1024.0).astype(np.int64)
    q -= q.min(axis=0)
    mx = q.max(axis=0)
    if (mx < (1 << 21)).all():
        key = (q[:, 0] << 42) | (q[:, 1] << 21) | q[:, 2]
        _, idx, inv = np.unique(key, return_index=True, return_inverse=True)
    else:
        _, idx, inv = np.unique(q, axis=0, return_index=True, return_inverse=True)
    return v[idx], inv.reshape(-1).astype(np.int64)


# ---------------------------------------------------------------- 合并相邻同材质面（贪心合并）

def _cover(cells, swap):
    """贪心矩形覆盖：按行扫描，先横向延伸再纵向延伸。swap=True 时交换两轴（先纵后横）。"""
    if swap:
        cells = {(b, a): k for (a, b), k in cells.items()}
    used = set()
    rects = []
    for (ca, cb) in sorted(cells, key=lambda t: (t[1], t[0])):
        if (ca, cb) in used:
            continue
        w = 1
        while (ca + w, cb) in cells and (ca + w, cb) not in used:
            w += 1
        h = 1
        while all((ca + i, cb + h) in cells and (ca + i, cb + h) not in used for i in range(w)):
            h += 1
        for j in range(h):
            for i in range(w):
                used.add((ca + i, cb + j))
        rects.append((ca, cb, w, h))
    if swap:
        rects = [(cb, ca, h, w) for (ca, cb, w, h) in rects]
    return rects


def greedy_merge(md, group=None, free_rot=None):
    """把同一平面上相邻、同材质、UV 方向相同的整格方块面合并成大矩形。
    只处理「1×1、轴对齐、对齐整数网格、UV 恰好铺满一张贴图」的四边形；合并后 UV 超出 0~1（贴图需平铺）。
    group：可选 (nf,) 数组，不同值的面不会合并（按类型拆分时传方块类型）。
    free_rot：可选 (nf,) bool，为 True 的面贴图方向本来就是随机的（草方块、泥土、沙子等随机变体），
              合并时不区分方向，统一用该组里最常见的方向。
    返回 (新的 MeshData, 参与了合并的材质槽集合)。"""
    nf = md.faces
    if nf == 0:
        return md, set()
    fs = md.face_size
    fstart = np.zeros(nf, np.int64)
    np.cumsum(fs[:-1], out=fstart[1:])
    mats = md.face_mat.astype(np.int64)

    # UV 越界的材质（模组模型常见）不参与合并，保留原样
    loop_face = np.repeat(np.arange(nf), fs)
    over = (md.loop_uv < -1e-4) | (md.loop_uv > 1 + 1e-4)
    bad_mat = np.zeros(int(mats.max()) + 1, bool)
    bad_mat[np.unique(mats[loop_face[over.any(axis=1)]])] = True

    quad = np.nonzero((fs == 4) & ~bad_mat[mats])[0]
    if len(quad) == 0:
        return md, set()
    li = fstart[quad][:, None] + np.arange(4)[None, :]
    P = md.verts[md.loop_vert[li]].astype(np.float64)      # (n,4,3)
    UV = md.loop_uv[li].astype(np.float64)                  # (n,4,2)
    # 游戏烘焙模型时 UV 会向内收缩约 1/1000（防止贴图边缘串色），整格面的 UV 实际是 0.001~0.999
    snap = np.round(UV)
    UV = np.where(np.abs(UV - snap) < 0.01, snap, UV)
    lo, hi = P.min(axis=1), P.max(axis=1)
    ext = hi - lo
    flat = np.abs(ext) < 1e-5
    unit = np.abs(ext - 1) < 1e-5
    ok = (flat.sum(axis=1) == 1) & (unit.sum(axis=1) == 2)
    axis = np.argmax(flat, axis=1)
    # 平面内两轴
    ax_a = np.where(axis == 0, 1, 0)
    ax_b = np.where(axis == 2, 1, 2)
    n = len(quad)
    rows = np.arange(n)
    a0 = lo[rows, ax_a]
    b0 = lo[rows, ax_b]
    ok &= (np.abs(a0 - np.round(a0)) < 1e-5) & (np.abs(b0 - np.round(b0)) < 1e-5)
    # 每个顶点在格子内的局部坐标 (da, db) ∈ {0,1}
    da = np.round(P[rows[:, None], np.arange(4)[None, :], ax_a[:, None]] - a0[:, None]).astype(np.int64)
    db = np.round(P[rows[:, None], np.arange(4)[None, :], ax_b[:, None]] - b0[:, None]).astype(np.int64)
    corner = da * 2 + db                                     # 0:(0,0) 1:(0,1) 2:(1,0) 3:(1,1)
    ok &= np.all(np.sort(corner, axis=1) == np.arange(4)[None, :], axis=1)
    # 按角排列 UV
    order = np.argsort(corner, axis=1)
    U = UV[rows[:, None], order]                             # (n,4,2) 角 00,01,10,11
    c = U[:, 0]
    col_a = U[:, 2] - c
    col_b = U[:, 1] - c
    ok &= np.all(np.abs(U[:, 3] - (c + col_a + col_b)) < 1e-4, axis=1)       # 仿射
    M = np.round(np.concatenate([col_a, col_b], axis=1)).astype(np.int64)     # (n,4)
    ok &= np.all(np.abs(np.concatenate([col_a, col_b], axis=1) - M) < 1e-4, axis=1)
    det = M[:, 0] * M[:, 3] - M[:, 1] * M[:, 2]
    ok &= np.abs(det) == 1
    # 法线方向
    nrm = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
    sign = np.sign(nrm[rows, axis]).astype(np.int64)
    ok &= sign != 0
    plane = np.round(lo[rows, axis] * 1024).astype(np.int64)

    cand = np.nonzero(ok)[0]
    if len(cand) == 0:
        return md, set()
    g = np.zeros(nf, np.int64) if group is None else np.asarray(group).astype(np.int64)
    Mk = M[cand].copy()
    if free_rot is not None:
        fr = np.asarray(free_rot, bool)[quad[cand]]
        Mk[fr] = 9  # 不区分方向
    key = np.stack([mats[quad[cand]], g[quad[cand]], axis[cand], sign[cand], plane[cand],
                    Mk[:, 0], Mk[:, 1], Mk[:, 2], Mk[:, 3]], axis=1)
    _, ginv = np.unique(key, axis=0, return_inverse=True)
    ginv = ginv.reshape(-1)

    new_verts, new_uv, new_src = [], [], []
    removed = np.zeros(nf, bool)
    order_g = np.argsort(ginv, kind="stable")
    bounds = np.r_[0, np.nonzero(np.diff(ginv[order_g]))[0] + 1, len(order_g)]
    A0 = np.round(a0).astype(np.int64)
    B0 = np.round(b0).astype(np.int64)
    CU = np.array([0, 1, 1, 0], np.int64)   # 矩形四角（乘 w）
    CV = np.array([0, 0, 1, 1], np.int64)   # 矩形四角（乘 h）
    for gi in range(len(bounds) - 1):
        idx = cand[order_g[bounds[gi]:bounds[gi + 1]]]       # 下标属于 quad 数组
        if len(idx) < 2:
            continue
        cells = dict(zip(zip(A0[idx].tolist(), B0[idx].tolist()), idx.tolist()))
        rects = _cover(cells, False)
        alt = _cover(cells, True)
        if len(alt) < len(rects):
            rects = alt
        R = np.asarray(rects, np.int64).reshape(-1, 4)
        R = R[(R[:, 2] > 1) | (R[:, 3] > 1)]                # 单格面保持原样（包括它自己的贴图方向）
        if len(R) == 0:
            continue
        # 组里属于单格矩形之外的面都被大面取代
        single = {(r[0], r[1]) for r in rects if r[2] == 1 and r[3] == 1}
        removed[quad[[k for cell, k in cells.items() if cell not in single]]] = True
        # 贴图方向：组内最常见的那种（方向固定的组里本来就只有一种）
        Mi = M[idx]
        if (Mi == Mi[0]).all():
            m, rep = Mi[0], idx[0]
        else:
            um, inv_m, ucnt = np.unique(Mi, axis=0, return_inverse=True, return_counts=True)
            best = int(np.argmax(ucnt))
            m = um[best]
            rep = idx[int(np.nonzero(inv_m.reshape(-1) == best)[0][0])]
        k0 = idx[0]
        ax, aa, bb = int(axis[k0]), int(ax_a[k0]), int(ax_b[k0])
        ko = np.array([cells[(int(r[0]), int(r[1]))] for r in R], np.int64)
        same = np.all(M[ko] == m, axis=1)
        cc = np.where(same[:, None], c[ko], c[rep])               # (n,2)
        U = R[:, 2:3] * CU[None, :]                               # (n,4)
        V = R[:, 3:4] * CV[None, :]
        pts = np.empty((len(R), 4, 3), np.float64)
        pts[:, :, ax] = lo[k0, ax]
        pts[:, :, aa] = R[:, 0:1] + U
        pts[:, :, bb] = R[:, 1:2] + V
        uvs = np.stack([cc[:, 0:1] + m[0] * U + m[2] * V, cc[:, 1:2] + m[1] * U + m[3] * V], axis=2)
        # 绕向：与原面法线一致（整组相同，只需判断一次）
        e = np.zeros(3)
        f = np.zeros(3)
        e[aa] = 1.0
        f[bb] = 1.0
        if np.sign(np.cross(e, f)[ax]) != sign[k0]:
            pts = pts[:, ::-1]
            uvs = uvs[:, ::-1]
        new_verts.append(pts)
        new_uv.append(uvs)
        new_src.append(quad[ko])

    if not new_verts:
        return md, set()

    src = np.concatenate(new_src)
    merged_slots = set(np.unique(md.face_mat[src]).tolist())
    keep = ~removed
    loops_keep = np.repeat(keep, fs)
    nv0 = len(md.verts)
    nm = len(src)
    verts = np.concatenate([md.verts, np.concatenate(new_verts).astype(np.float32).reshape(-1, 3)])
    loop_vert = np.concatenate([md.loop_vert[loops_keep], nv0 + np.arange(nm * 4, dtype=np.int64)])
    loop_uv = np.concatenate([md.loop_uv[loops_keep], np.concatenate(new_uv).astype(np.float32).reshape(-1, 2)])
    face_size = np.concatenate([fs[keep], np.full(nm, 4, fs.dtype)])
    face_mat = np.concatenate([md.face_mat[keep], md.face_mat[src]])
    face_block = np.concatenate([md.face_block[keep], md.face_block[src]]) if md.face_block is not None else None
    face_pos = np.concatenate([md.face_pos[keep], md.face_pos[src]]) if md.face_pos is not None else None

    # 合并新顶点里重复的（相邻矩形共用的角），并去掉不再使用的顶点
    q = np.round(verts.astype(np.float64) * 1024).astype(np.int64)
    _, first, inv = np.unique(q, axis=0, return_index=True, return_inverse=True)
    loop_vert = inv.reshape(-1)[loop_vert]
    used = np.zeros(len(first), bool)
    used[loop_vert] = True
    remap = np.cumsum(used) - 1
    verts = verts[first][used]
    loop_vert = remap[loop_vert].astype(np.int32)

    out = MeshData(verts.astype(np.float32), loop_vert, face_size.astype(np.int32), face_mat.astype(np.int32),
                   loop_uv.astype(np.float32), None, None, None, face_block, face_pos)
    return out, merged_slots
