"""独立的「地图选区」窗口：分块读取游戏俯视地图，拖拽框选导入范围。

- 地图按区块分块，后台线程向 mod 请求，边看边加载，不卡界面；缩小时自动换更粗的分块
- 左键拖拽 = 框选（可反复框选，新的替换旧的）；滚轮 = 缩放；中键/右键拖拽 = 平移
- 选区只是场景属性（scene.mineblend.sel_min / sel_max），不创建任何几何体
- 选区内显示「剖面」：只画最低~最高 Y 范围内的方块，调高度时实时看到切开后的样子
"""
import math
import os
import queue
import tempfile
import threading
import time

import blf
import bpy
import gpu
import numpy as np
from gpu_extras.batch import batch_for_shader

from . import bridge
from .section import ColumnSection

# (分块边长[方块], 采样间隔, 每格像素) —— 每块都是 256×256 像素
LODS = [(32, 1, 8), (128, 4, 8), (512, 16, 8), (2048, 64, 8)]
MAX_TILES = 600
FONT = 0


def _shader(name):
    try:
        return gpu.shader.from_builtin(name)
    except Exception:
        return gpu.shader.from_builtin("2D_" + name)


class Tile:
    __slots__ = ("lod", "tx", "tz", "x0", "z0", "size", "state", "tex", "heights", "step", "used", "bottom")

    def __init__(self, lod, tx, tz):
        size = LODS[lod][0]
        self.lod, self.tx, self.tz, self.size = lod, tx, tz, size
        self.x0, self.z0 = tx * size, tz * size
        self.step = LODS[lod][1]
        self.state = "pending"
        self.tex = None
        self.heights = None
        self.bottom = -64
        self.used = time.time()


class Loader(threading.Thread):
    """后台线程：依次请求分块俯视图（纯网络，不碰 bpy）"""

    def __init__(self, host, port):
        super().__init__(daemon=True)
        self.host, self.port = host, port
        self.req = queue.LifoQueue()   # 后进先出：优先加载最近看的位置
        self.done = queue.Queue()
        self.stop = False
        self.player = None
        self._last_status = 0
        self.section_req = None  # (key, path, params)：选区的列数据（剖面用），只保留最新一个，优先处理

    def run(self):
        while not self.stop:
            if time.time() - self._last_status > 2:
                self._last_status = time.time()
                try:
                    st = bridge.get_json(self.host, self.port, "/status", timeout=3)
                    if st.get("inWorld"):
                        self.player = (st["player"], st.get("yaw", 0.0))
                except Exception:
                    pass
            sreq = self.section_req
            if sreq is not None:
                self.section_req = None
                key, path, params = sreq
                try:
                    self.done.put((key, bridge._request(self.host, self.port, path, params, timeout=120), None))
                except Exception as e:
                    self.done.put((key, None, str(e)))
                continue
            try:
                key = self.req.get(timeout=0.1)
            except queue.Empty:
                continue
            lod, tx, tz = key
            size, step, ppb = LODS[lod]
            x0, z0 = tx * size, tz * size
            try:
                data = bridge._request(self.host, self.port, "/topview",
                                       dict(x1=x0, z1=z0, x2=x0 + size - 1, z2=z0 + size - 1, step=step, ppb=ppb),
                                       timeout=120)
                self.done.put((key, data, None))
            except Exception as e:
                self.done.put((key, None, str(e)))


def _png_to_texture(png):
    fd, path = tempfile.mkstemp(suffix=".png", prefix="mineblend_tile_")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(png)
        img = bpy.data.images.load(path, check_existing=False)
        try:
            w, h = img.size
            px = np.empty(w * h * 4, np.float32)
            img.pixels.foreach_get(px)
        finally:
            bpy.data.images.remove(img)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    buf = gpu.types.Buffer("FLOAT", w * h * 4, px)
    return gpu.types.GPUTexture((w, h), format="RGBA8", data=buf)


class Button:
    def __init__(self, label, action, hint=""):
        self.label, self.action, self.hint = label, action, hint
        self.rect = (0, 0, 0, 0)

    def hit(self, x, y):
        x0, y0, x1, y1 = self.rect
        return x0 <= x <= x1 and y0 <= y <= y1


class NumField:
    """顶栏的数值框：左右拖动改值，单击输入数字，两端的 ‹ › 每次 ±1（Shift ±10）"""

    def __init__(self, label, which):
        self.label, self.which = label, which
        self.rect = (0, 0, 0, 0)
        self.arrow = 0

    def hit(self, x, y):
        x0, y0, x1, y1 = self.rect
        return x0 <= x <= x1 and y0 <= y <= y1

    def zone(self, x):
        """-1 = 左箭头, 1 = 右箭头, 0 = 中间"""
        x0, _, x1, _ = self.rect
        if x <= x0 + self.arrow:
            return -1
        if x >= x1 - self.arrow:
            return 1
        return 0


class MB_OT_map_window(bpy.types.Operator):
    bl_idname = "mineblend.map_window"
    bl_label = "打开地图选区窗口"
    bl_description = "打开独立窗口显示游戏俯视地图，在里面拖拽框选要导入的范围"

    _active = None  # 同一时间只有一个地图窗口

    # ------------------------------------------------------------------ setup

    def invoke(self, context, event):
        s = context.scene.mineblend
        if MB_OT_map_window._active is not None:
            self.report({"INFO"}, "地图选区窗口已经打开")
            return {"CANCELLED"}
        try:
            st = bridge.get_json(s.host, s.port, "/status")
        except bridge.BridgeError as e:
            self.report({"ERROR"}, str(e))
            return {"CANCELLED"}
        if not st.get("inWorld"):
            self.report({"ERROR"}, "游戏还没进入存档")
            return {"CANCELLED"}
        _remove_legacy_objects()

        # 新开一个只有一个区域的窗口
        wm = context.window_manager
        before = {w.as_pointer() for w in wm.windows}
        src_area = context.area or next(a for a in context.window.screen.areas)
        region = next((r for r in src_area.regions if r.type == "WINDOW"), None)
        try:
            with context.temp_override(window=context.window, area=src_area, region=region):
                bpy.ops.screen.area_dupli("INVOKE_DEFAULT")
        except Exception:
            pass
        new = [w for w in wm.windows if w.as_pointer() not in before]
        if not new:
            bpy.ops.wm.window_new()
            new = [w for w in wm.windows if w.as_pointer() not in before]
        if not new:
            self.report({"ERROR"}, "无法打开新窗口")
            return {"CANCELLED"}
        win = new[0]
        area = max(win.screen.areas, key=lambda a: a.width * a.height)
        area.type = "TEXT_EDITOR"
        sp = area.spaces.active
        for attr in ("show_region_ui", "show_region_toolbar", "show_region_tool_header", "show_region_header",
                     "show_region_hud", "show_region_footer", "show_line_numbers"):
            try:
                setattr(sp, attr, False)
            except Exception:
                pass
        self.win, self.area = win, area
        self.win_ptr, self.area_ptr = win.as_pointer(), area.as_pointer()

        self.st_min_y, self.st_max_y = int(st.get("minY", -64)), int(st.get("maxY", 319))
        px, py, pz = st["player"]
        self.cx, self.cz = px + 0.5, pz + 0.5
        self.scale = 4.0  # 每方块像素
        self.tiles = {}
        self.loader = Loader(s.host, s.port)
        self.loader.player = (st["player"], st.get("yaw", 0.0))
        self.loader.start()
        self.drag = None      # ("select", x0, z0) / ("pan", mx, my, cx, cz)
        self.cursor = None    # (mx, my) 区域坐标
        self.message = "左键拖拽框选 · 滚轮缩放 · 中键/右键拖拽平移 · 高度框可拖动或单击输入 · Enter 导入 · Esc 关闭"
        self.buttons_top = [
            Button("导入选区", self.do_import, "Enter"),
            Button("回到玩家", self.go_player, "Home"),
            Button("读取游戏内选区", self.from_game),
            Button("发送到游戏", self.to_game),
            Button("导入设置", self.toggle_panel),
            Button("关闭", self.close, "Esc"),
        ]
        self.fields = [NumField("最低 Y", 0), NumField("最高 Y", 1)]
        self.buttons_y = [Button("自动高度", lambda shift: self.auto_y())]
        self.fdrag = None     # [field, 起点 mx, 起始值, 是否已拖动]
        self.edit = None      # [field, 文本, 是否整体选中]
        self.world_bottom, self.world_top = self.st_min_y, self.st_max_y
        self.cols = None      # 选区的列数据（ColumnSection），换选区时重新获取
        self.cols_requested = None
        self.sec_tex = None   # 当前高度范围的剖面贴图，(键, GPUTexture, dp)
        self.sec_ms = 0.0
        self.sec_pending = False
        self.confirm = None   # 超大选区导入确认：((选区, 导入方式), 时间)
        self.closing = False
        self.show_panel = True
        self.buttons_side = []
        self.panel_rect = (0, 0, 0, 0)

        self.handle = bpy.types.SpaceTextEditor.draw_handler_add(self.draw_map, (), "WINDOW", "POST_PIXEL")
        self.timer = wm.event_timer_add(0.05, window=win)
        r = self._region()
        with context.temp_override(window=win, area=area, region=r):
            wm.modal_handler_add(self)
        MB_OT_map_window._active = self
        area.tag_redraw()
        return {"RUNNING_MODAL"}

    def _region(self):
        return next((r for r in self.area.regions if r.type == "WINDOW"), None)

    def _alive(self):
        wm = bpy.context.window_manager
        for w in wm.windows:
            if w.as_pointer() == self.win_ptr:
                return any(a.as_pointer() == self.area_ptr for a in w.screen.areas)
        return False

    def cleanup(self, context):
        if MB_OT_map_window._active is self:
            MB_OT_map_window._active = None
        self.loader.stop = True
        try:
            bpy.types.SpaceTextEditor.draw_handler_remove(self.handle, "WINDOW")
        except Exception:
            pass
        try:
            context.window_manager.event_timer_remove(self.timer)
        except Exception:
            pass
        self.tiles.clear()
        self.cols = None
        self.sec_tex = None

    def cancel(self, context):
        self.cleanup(context)

    # ------------------------------------------------------------------ coordinates

    def to_screen(self, x, z, region):
        return region.width / 2 + (x - self.cx) * self.scale, region.height / 2 - (z - self.cz) * self.scale

    def to_world(self, mx, my, region):
        return self.cx + (mx - region.width / 2) / self.scale, self.cz - (my - region.height / 2) / self.scale

    def lod(self):
        if self.scale >= 2:
            return 0
        if self.scale >= 0.5:
            return 1
        if self.scale >= 0.125:
            return 2
        return 3

    def visible_tiles(self, region, lod):
        size = LODS[lod][0]
        xa, za = self.to_world(0, region.height, region)
        xb, zb = self.to_world(region.width, 0, region)
        tx0, tx1 = math.floor(xa / size), math.floor(xb / size)
        tz0, tz1 = math.floor(za / size), math.floor(zb / size)
        cx, cz = math.floor(self.cx / size), math.floor(self.cz / size)
        keys = [(lod, tx, tz) for tx in range(tx0, tx1 + 1) for tz in range(tz0, tz1 + 1)]
        keys.sort(key=lambda k: -((k[1] - cx) ** 2 + (k[2] - cz) ** 2))  # 远的先入队，LIFO 下近的先加载
        return keys

    def request_visible(self):
        region = self._region()
        if region is None:
            return
        lod = self.lod()
        for key in self.visible_tiles(region, lod):
            t = self.tiles.get(key)
            if t is None:
                t = Tile(*key)
                self.tiles[key] = t
                self.loader.req.put(key)
            t.used = time.time()
        if len(self.tiles) > MAX_TILES:
            old = sorted(self.tiles.values(), key=lambda t: t.used)[: len(self.tiles) - MAX_TILES]
            for t in old:
                if t.state != "pending":
                    del self.tiles[(t.lod, t.tx, t.tz)]

    def receive(self, limit=6):
        n = 0
        while n < limit:
            try:
                key, data, err = self.loader.done.get_nowait()
            except queue.Empty:
                break
            if key[0] == "cols":
                if err:
                    self.message = "剖面数据加载失败：" + err
                else:
                    try:
                        pk = bridge.Packet(data)
                        self.cols = ColumnSection(pk.header, pk.data, key, pk.base)
                        self.sec_tex = None
                    except Exception as e:
                        self.message = f"剖面数据解析失败：{e}"
                n += 1
                continue
            t = self.tiles.get(key)
            if t is None:
                continue
            if err:
                t.state = "error"
                self.message = "加载失败：" + err
                continue
            try:
                pk = bridge.Packet(data)
                h = pk.header
                off, ln = h["sections"]["b0"]
                t.tex = _png_to_texture(pk.blob(off, ln))
                if "b1" in h["sections"]:
                    off, ln = h["sections"]["b1"]
                    nx, nz = h["nx"], h["nz"]
                    t.heights = np.frombuffer(pk.blob(off, nx * nz * 2), "<i2").reshape(nz, nx).copy()
                    t.bottom = int(h.get("bottom", -64))
                t.state = "ready"
            except Exception as e:
                t.state = "error"
                self.message = f"分块解析失败：{e}"
            n += 1
        return n

    # ------------------------------------------------------------------ selection helpers

    def sel(self):
        s = bpy.context.scene.mineblend
        if not s.has_sel:
            return None
        return tuple(s.sel_min), tuple(s.sel_max)

    def heights_in(self, x0, z0, x1, z1):
        """框内已加载的顶面高度（优先最细的分块）"""
        vals = []
        for lod in range(len(LODS)):
            for t in self.tiles.values():
                if t.lod != lod or t.heights is None:
                    continue
                if t.x0 > x1 or t.x0 + t.size <= x0 or t.z0 > z1 or t.z0 + t.size <= z0:
                    continue
                st = t.step
                ix0 = max(0, (x0 - t.x0) // st)
                ix1 = min(t.heights.shape[1] - 1, (x1 - t.x0) // st)
                iz0 = max(0, (z0 - t.z0) // st)
                iz1 = min(t.heights.shape[0] - 1, (z1 - t.z0) // st)
                if ix1 < ix0 or iz1 < iz0:
                    continue
                v = t.heights[iz0:iz1 + 1, ix0:ix1 + 1].ravel()
                v = v[v >= t.bottom]  # 未生成/空列不算
                if len(v):
                    vals.append(v)
            if vals:
                break
        return np.concatenate(vals) if vals else None

    def auto_y(self):
        s = bpy.context.scene.mineblend
        if not s.has_sel:
            return
        (x0, _, z0), (x1, _, z1) = self.sel()
        v = self.heights_in(x0, z0, x1, z1)
        if v is None:
            self.message = "框内地图还没加载完，暂时无法自动计算高度"
            return
        lo, hi = int(v.min()), int(v.max())
        s.sel_min[1] = lo - 4
        s.sel_max[1] = hi + 1
        self.message = f"自动高度：Y {lo - 4} ~ {hi + 1}（地面最低 {lo}，最高 {hi}）"

    def get_y(self, which):
        s = bpy.context.scene.mineblend
        return s.sel_min[1] if which == 0 else s.sel_max[1]

    def set_y(self, which, v):
        """设置最低/最高 Y；超过另一端时把另一端一起推过去"""
        s = bpy.context.scene.mineblend
        if not s.has_sel:
            return
        v = max(self.world_bottom, min(self.world_top, int(v)))
        if which == 0:
            s.sel_min[1] = v
            if s.sel_max[1] < v:
                s.sel_max[1] = v
        else:
            s.sel_max[1] = v
            if s.sel_min[1] > v:
                s.sel_min[1] = v
        (x0, y0, z0), (x1, y1, z1) = self.sel()
        n = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)
        self.message = f"高度 Y {y0}~{y1}（{n:,} 方块）· 选区内显示该高度范围的剖面 · Enter 导入"
        tag_view3d_redraw()

    def adjust_y(self, which, d):
        if self.sel():
            self.set_y(which, self.get_y(which) + d)

    def commit_edit(self):
        if self.edit is None:
            return
        f, txt, _ = self.edit
        self.edit = None
        try:
            self.set_y(f.which, int(txt))
        except ValueError:
            if txt.strip():
                self.message = f"「{txt}」不是整数"

    def edit_key(self, event):
        """输入模式下的按键；返回 True 表示已处理"""
        if event.value != "PRESS" or event.type in {"MOUSEMOVE", "INBETWEEN_MOUSEMOVE", "TIMER"} or "MOUSE" in event.type:
            return False
        f, txt, fresh = self.edit
        if event.type in {"RET", "NUMPAD_ENTER"}:
            self.commit_edit()
        elif event.type == "ESC":
            self.edit = None
        elif event.type == "TAB":
            self.commit_edit()
            other = self.fields[1 - f.which]
            self.edit = [other, str(self.get_y(other.which)), True]
        elif event.type == "BACK_SPACE":
            self.edit = [f, "" if fresh else txt[:-1], False]
        elif event.type == "DEL":
            self.edit = [f, "", False]
        elif event.unicode and event.unicode in "-0123456789":
            self.edit = [f, event.unicode if fresh else (txt + event.unicode)[:6], False]
        return True  # 其它键在输入时一律吞掉（避免 Enter 导入、Esc 关窗）

    def update_section(self):
        """选区范围（X/Z）变了就重新获取这块区域的列数据；高度变化不需要请求，本地即时重算"""
        sel = self.sel()
        if sel is None or (self.drag and self.drag[0] == "select"):
            return
        (x0, _, z0), (x1, _, z1) = sel
        key = ("cols", x0, z0, x1, z1)
        if key == self.cols_requested:
            return
        n = max(x1 - x0 + 1, z1 - z0 + 1)
        step = max(1, math.ceil(n / 256))
        self.loader.section_req = (key, "/columns", dict(x1=x0, z1=z0, x2=x1, z2=z1, step=step, ppb=8))
        self.cols_requested = key

    def section_texture(self, y0, y1):
        """当前高度范围的剖面贴图（只在高度或列数据变化时重算，几毫秒）"""
        c = self.cols
        k = (c.key, y0, y1)
        if self.sec_tex is None or self.sec_tex[0] != k:
            t0 = time.perf_counter()
            dp = c.display_ppb()
            px = c.image(y0, y1, dp)
            h, w = px.shape[:2]
            buf = gpu.types.Buffer("FLOAT", w * h * 4, px.ravel())
            self.sec_tex = (k, gpu.types.GPUTexture((w, h), format="RGBA8", data=buf), dp)
            self.sec_ms = (time.perf_counter() - t0) * 1000
        return self.sec_tex[1]

    def set_rect(self, xa, za, xb, zb):
        s = bpy.context.scene.mineblend
        x0, x1 = sorted((math.floor(xa), math.floor(xb)))
        z0, z1 = sorted((math.floor(za), math.floor(zb)))
        had = s.has_sel
        y0, y1 = (s.sel_min[1], s.sel_max[1]) if had else (0, 0)
        s.sel_min = (x0, y0, z0)
        s.sel_max = (x1, y1, z1)
        s.has_sel = True
        if s.map_auto_y or not had:
            self.auto_y()
        tag_view3d_redraw()

    # ------------------------------------------------------------------ actions

    def do_import(self, shift=False):
        sel = self.sel()
        if sel is None:
            self.message = "还没有选区：在地图上按住左键拖拽框选"
            return
        from . import importer
        s = bpy.context.scene.mineblend
        warn = importer.size_warnings(sel[0], sel[1], s.split_mode)
        key = (sel, s.split_mode)
        if warn and not (self.confirm and self.confirm[0] == key and time.time() - self.confirm[1] < 10):
            # 超大选区：先提醒，10 秒内再按一次 Enter（或再点「导入选区」）才导入
            self.confirm = (key, time.time())
            self.message = "⚠ " + "；".join(warn) + "。确定要导入请再按一次 Enter"
            return
        self.confirm = None
        self.message = "正在导入……"
        self.area.tag_redraw()
        try:
            _, msg = importer.do_import(bpy.context, sel[0], sel[1], "map")
            self.message = msg
            bpy.context.scene.mineblend.status = msg
            if bpy.context.scene.mineblend.map_close_after_import:
                self.closing = True
        except bridge.BridgeError as e:
            self.message = f"导入失败：{e}"

    def go_player(self, shift=False):
        p = self.loader.player
        if p:
            self.cx, self.cz = p[0][0] + 0.5, p[0][2] + 0.5

    def from_game(self, shift=False):
        s = bpy.context.scene.mineblend
        try:
            sel = bridge.get_json(s.host, s.port, "/selection")
        except bridge.BridgeError as e:
            self.message = str(e)
            return
        if not sel.get("pos1") or not sel.get("pos2"):
            self.message = "游戏内还没选好两个点（手持选区棒：左键方块=点1，右键方块=点2）"
            return
        a, b = sel["pos1"], sel["pos2"]
        s.sel_min = [min(a[i], b[i]) for i in range(3)]
        s.sel_max = [max(a[i], b[i]) for i in range(3)]
        s.has_sel = True
        self.cx = (s.sel_min[0] + s.sel_max[0] + 1) / 2
        self.cz = (s.sel_min[2] + s.sel_max[2] + 1) / 2
        self.message = "已读取游戏内选区"
        tag_view3d_redraw()

    def to_game(self, shift=False):
        s = bpy.context.scene.mineblend
        sel = self.sel()
        if sel is None:
            return
        (x0, y0, z0), (x1, y1, z1) = sel
        try:
            bridge.get_json(s.host, s.port, "/selection/set", dict(x1=x0, y1=y0, z1=z0, x2=x1, y2=y1, z2=z1))
            self.message = "已发送到游戏，游戏里会显示这个选区"
        except bridge.BridgeError as e:
            self.message = str(e)

    def toggle_panel(self, shift=False):
        self.show_panel = not self.show_panel

    def close(self, shift=False):
        self.closing = True

    def _set_cursor(self, c):
        if getattr(self, "_cursor", None) != c:
            self._cursor = c
            try:
                self.win.cursor_set(c)
            except Exception:
                pass

    # ------------------------------------------------------------------ events

    def modal(self, context, event):
        if not self._alive():
            self.cleanup(context)
            return {"CANCELLED"}
        if self.closing:
            self.cleanup(context)
            try:
                with context.temp_override(window=self.win):
                    bpy.ops.wm.window_close()
            except Exception:
                pass
            return {"FINISHED"}
        region = self._region()
        if region is None:
            return {"PASS_THROUGH"}
        if event.type == "TIMER":
            self.request_visible()
            self.update_section()
            if self.receive():
                self.area.tag_redraw()
            elif self.loader.player:
                self.area.tag_redraw()
            return {"PASS_THROUGH"}

        if self.edit is not None and self.edit_key(event):
            self.area.tag_redraw()
            return {"RUNNING_MODAL"}

        ui = context.preferences.system.ui_scale
        mx, my = event.mouse_x - region.x, event.mouse_y - region.y
        inside = 0 <= mx <= region.width and 0 <= my <= region.height
        if not inside and self.drag is None and self.fdrag is None:
            return {"PASS_THROUGH"}
        self.cursor = (mx, my)
        redraw = True
        over_field = next((f for f in self.fields if f.hit(mx, my)), None)
        self._set_cursor("SCROLL_X" if (over_field and self.edit is None and over_field.zone(mx) == 0) or self.fdrag
                         else "TEXT" if over_field and self.edit is not None else "DEFAULT")

        if event.type == "MOUSEMOVE":
            if self.fdrag:
                f, sx, v0, moved = self.fdrag
                dx = mx - sx
                if moved or abs(dx) > 3 * ui:
                    self.fdrag[3] = True
                    self.set_y(f.which, v0 + int(dx / (6 * ui)) * (10 if event.shift else 1))
            elif self.drag and self.drag[0] == "pan":
                _, sx, sy, cx, cz = self.drag
                self.cx = cx - (mx - sx) / self.scale
                self.cz = cz + (my - sy) / self.scale
        elif event.type in {"WHEELUPMOUSE", "WHEELDOWNMOUSE", "TRACKPADZOOM"}:
            if over_field is not None:
                d = 1 if event.type == "WHEELUPMOUSE" else -1
                self.adjust_y(over_field.which, d * (10 if event.shift else 1))
            elif event.ctrl and self.sel():
                self.adjust_y(1 if not event.shift else 0, 1 if event.type == "WHEELUPMOUSE" else -1)
            else:
                f = 1.25 if event.type == "WHEELUPMOUSE" else 0.8
                wx, wz = self.to_world(mx, my, region)
                self.scale = min(64.0, max(1 / 64, self.scale * f))
                self.cx = wx - (mx - region.width / 2) / self.scale
                self.cz = wz + (my - region.height / 2) / self.scale
        elif event.type in {"MIDDLEMOUSE", "RIGHTMOUSE"}:
            if event.value == "PRESS":
                self.drag = ("pan", mx, my, self.cx, self.cz)
            elif event.value == "RELEASE" and self.drag and self.drag[0] == "pan":
                self.drag = None
        elif event.type == "LEFTMOUSE":
            if event.value == "PRESS":
                if self.edit is not None:
                    if self.edit[0].hit(mx, my):
                        return {"RUNNING_MODAL"}
                    self.commit_edit()
                if over_field is not None:
                    if not self.sel():
                        self.message = "先在地图上框选一个范围，再调整高度"
                    elif over_field.zone(mx):
                        self.adjust_y(over_field.which, over_field.zone(mx) * (10 if event.shift else 1))
                    else:
                        self.fdrag = [over_field, mx, self.get_y(over_field.which), False]
                    self.area.tag_redraw()
                    return {"RUNNING_MODAL"}
                for b in self.buttons_top + self.buttons_y + self.buttons_side:
                    if b.hit(mx, my):
                        b.action(event.shift)
                        self.area.tag_redraw()
                        return {"RUNNING_MODAL"}
                px0, py0, px1, py1 = self.panel_rect
                if self.show_panel and px0 <= mx <= px1 and py0 <= my <= py1:
                    return {"RUNNING_MODAL"}  # 点在设置面板空白处
                wx, wz = self.to_world(mx, my, region)
                self.drag = ("select", wx, wz)
            elif event.value == "RELEASE" and self.fdrag:
                f, _, _, moved = self.fdrag
                self.fdrag = None
                if not moved:  # 单击 = 输入数字
                    self.edit = [f, str(self.get_y(f.which)), True]
            elif event.value == "RELEASE" and self.drag and self.drag[0] == "select":
                _, xa, za = self.drag
                xb, zb = self.to_world(mx, my, region)
                self.drag = None
                self.set_rect(xa, za, xb, zb)
                s = context.scene.mineblend
                n = (s.sel_max[0] - s.sel_min[0] + 1) * (s.sel_max[1] - s.sel_min[1] + 1) * (s.sel_max[2] - s.sel_min[2] + 1)
                self.message = f"已框选 {s.sel_max[0] - s.sel_min[0] + 1}×{s.sel_max[2] - s.sel_min[2] + 1}，Y {s.sel_min[1]}~{s.sel_max[1]}（{n:,} 方块）· Enter 导入"
                from . import importer
                warn = importer.size_warnings(tuple(s.sel_min), tuple(s.sel_max), s.split_mode)
                if warn:
                    self.message = "⚠ " + "；".join(warn)
        elif event.type in {"RET", "NUMPAD_ENTER"} and event.value == "PRESS":
            self.do_import()
        elif event.type == "HOME" and event.value == "PRESS":
            self.go_player()
        elif event.type == "ESC" and event.value == "PRESS":
            if self.fdrag:
                self.set_y(self.fdrag[0].which, self.fdrag[2])
                self.fdrag = None
            elif self.drag:
                self.drag = None
            else:
                self.closing = True
        else:
            redraw = False
        if redraw:
            self.area.tag_redraw()
        return {"RUNNING_MODAL"}

    # ------------------------------------------------------------------ drawing

    def draw_map(self):
        ctx = bpy.context
        if ctx.area is None or ctx.area.as_pointer() != self.area_ptr:
            return
        region = ctx.region
        W, H = region.width, region.height
        ui = ctx.preferences.system.ui_scale
        col = _shader("UNIFORM_COLOR")
        img = _shader("IMAGE")
        gpu.state.blend_set("ALPHA")

        def rect(x0, y0, x1, y1, c):
            b = batch_for_shader(col, "TRI_FAN", {"pos": [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]})
            col.bind()
            col.uniform_float("color", c)
            b.draw(col)

        def outline(x0, y0, x1, y1, c, w=1.0):
            gpu.state.line_width_set(w)
            b = batch_for_shader(col, "LINE_LOOP", {"pos": [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]})
            col.bind()
            col.uniform_float("color", c)
            b.draw(col)
            gpu.state.line_width_set(1.0)

        def text(x, y, s, size=13, c=(1, 1, 1, 1)):
            blf.size(FONT, size * ui)
            blf.color(FONT, *c)
            blf.position(FONT, x, y, 0)
            blf.draw(FONT, s)
            return blf.dimensions(FONT, s)

        rect(0, 0, W, H, (0.11, 0.11, 0.12, 1))

        # 地图分块：先画粗的再画细的
        cur = self.lod()
        loading = 0
        for lod in sorted(range(len(LODS)), reverse=True):
            if lod < cur:
                continue
            for t in self.tiles.values():
                if t.lod != lod:
                    continue
                if t.state == "pending" and lod == cur:
                    loading += 1
                if t.tex is None:
                    continue
                x0, ytop = self.to_screen(t.x0, t.z0, region)
                x1, ybot = self.to_screen(t.x0 + t.size, t.z0 + t.size, region)
                if x1 < 0 or x0 > W or ytop < 0 or ybot > H:
                    continue
                b = batch_for_shader(img, "TRI_FAN", {"pos": [(x0, ybot), (x1, ybot), (x1, ytop), (x0, ytop)],
                                                      "texCoord": [(0, 0), (1, 0), (1, 1), (0, 1)]})
                img.bind()
                img.uniform_sampler("image", t.tex)
                b.draw(img)

        # 选区剖面：只画最低~最高 Y 之间的方块（越深越暗），调高度时本地即时重算
        sel_now = self.sel()
        c = self.cols
        selecting = self.drag and self.drag[0] == "select"
        self.sec_pending = False
        if sel_now and not selecting:
            (x0, y0, z0), (x1, y1, z1) = sel_now
            if c is not None and c.key[1:] == (x0, z0, x1, z1):
                sx0, sy0 = self.to_screen(x0, z1 + 1, region)
                sx1, sy1 = self.to_screen(x1 + 1, z0, region)
                rect(sx0, sy0, sx1, sy1, (0.06, 0.06, 0.07, 1))
                tex = self.section_texture(y0, y1)
                u1 = (x1 + 1 - x0) / (c.nx * c.step)
                v0 = 1 - (z1 + 1 - z0) / (c.nz * c.step)
                b = batch_for_shader(img, "TRI_FAN", {"pos": [(sx0, sy0), (sx1, sy0), (sx1, sy1), (sx0, sy1)],
                                                      "texCoord": [(0, v0), (u1, v0), (u1, 1), (0, 1)]})
                img.bind()
                img.uniform_sampler("image", tex)
                b.draw(img)
            else:
                self.sec_pending = True

        # 区块网格（放大时）
        if self.scale >= 3:
            xa, za = self.to_world(0, H, region)
            xb, zb = self.to_world(W, 0, region)
            pts = []
            for cx in range(math.floor(xa / 16), math.floor(xb / 16) + 2):
                sx, _ = self.to_screen(cx * 16, 0, region)
                pts += [(sx, 0), (sx, H)]
            for cz in range(math.floor(za / 16), math.floor(zb / 16) + 2):
                _, sy = self.to_screen(0, cz * 16, region)
                pts += [(0, sy), (W, sy)]
            b = batch_for_shader(col, "LINES", {"pos": pts})
            col.bind()
            col.uniform_float("color", (1, 1, 1, 0.08))
            b.draw(col)

        # 选区
        sel = self.sel()
        if self.drag and self.drag[0] == "select" and self.cursor:
            _, xa, za = self.drag
            xb, zb = self.to_world(*self.cursor, region)
            x0, x1 = sorted((math.floor(xa), math.floor(xb)))
            z0, z1 = sorted((math.floor(za), math.floor(zb)))
            sel_rect, dragging = (x0, z0, x1, z1), True
        elif sel:
            sel_rect, dragging = (sel[0][0], sel[0][2], sel[1][0], sel[1][2]), False
        else:
            sel_rect = None
        if sel_rect:
            x0, z0, x1, z1 = sel_rect
            sx0, sy0 = self.to_screen(x0, z1 + 1, region)
            sx1, sy1 = self.to_screen(x1 + 1, z0, region)
            rect(sx0, sy0, sx1, sy1, (1.0, 0.75, 0.1, 0.12))
            outline(sx0, sy0, sx1, sy1, (1.0, 0.75, 0.1, 1.0), 2.0)
            label = f"{x1 - x0 + 1} × {z1 - z0 + 1}"
            big = False
            if sel and not dragging:
                label += f"   剖面 Y {sel[0][1]} ~ {sel[1][1]}" + ("（加载中…）" if self.sec_pending else "")
                from . import importer
                big = bool(importer.size_warnings(sel[0], sel[1], ctx.scene.mineblend.split_mode))
                if big:
                    label += "   ⚠ 导入选区过大，请注意"
            text(sx0 + 4, sy1 + 6, label, 13, (1.0, 0.45, 0.2, 1) if big else (1.0, 0.85, 0.3, 1))

        # 玩家
        if self.loader.player:
            (px, _, pz), yaw = self.loader.player
            sx, sy = self.to_screen(px + 0.5, pz + 0.5, region)
            a = math.radians(yaw)
            d = 9 * ui
            fx, fy = -math.sin(a), -math.cos(a)  # MC: yaw 0 = 南(+z)，屏幕上 +z 向下
            tip = (sx + fx * d, sy + fy * d)
            left = (sx - fy * d * 0.6 - fx * d * 0.5, sy + fx * d * 0.6 - fy * d * 0.5)
            right = (sx + fy * d * 0.6 - fx * d * 0.5, sy - fx * d * 0.6 - fy * d * 0.5)
            b = batch_for_shader(col, "TRIS", {"pos": [tip, left, right]})
            col.bind()
            col.uniform_float("color", (1, 0.25, 0.25, 1))
            b.draw(col)

        # 顶部按钮
        bh, pad = 24 * ui, 6 * ui
        x, y = pad, H - pad - bh
        rect(0, y - pad, W, H, (0.08, 0.08, 0.09, 0.85))
        blf.size(FONT, 13 * ui)
        for btn in self.buttons_top:
            label = btn.label + (f"  {btn.hint}" if btn.hint else "")
            w = blf.dimensions(FONT, label)[0] + 16 * ui
            btn.rect = (x, y, x + w, y + bh)
            hover = self.cursor and btn.hit(*self.cursor)
            rect(x, y, x + w, y + bh, (0.3, 0.3, 0.33, 1) if hover else (0.2, 0.2, 0.22, 1))
            text(x + 8 * ui, y + 7 * ui, label)
            x += w + pad

        # 高度范围：可拖动、可输入的数值框
        s = ctx.scene.mineblend
        x += pad * 2
        fw, aw = 84 * ui, 16 * ui
        for f in self.fields:
            x += text(x, y + 7 * ui, f.label, 13, (0.8, 0.8, 0.8, 1))[0] + 6 * ui
            f.rect = (x, y, x + fw, y + bh)
            f.arrow = aw
            editing = self.edit is not None and self.edit[0] is f
            hover = self.cursor and f.hit(*self.cursor)
            active = editing or (self.fdrag and self.fdrag[0] is f)
            rect(x, y, x + fw, y + bh, (0.12, 0.12, 0.13, 1) if editing else (0.3, 0.3, 0.33, 1) if hover or active
                 else (0.2, 0.2, 0.22, 1))
            outline(x, y, x + fw, y + bh, (1, 0.75, 0.1, 0.9) if active else (1, 1, 1, 0.1), 1.5 if active else 1.0)
            blf.size(FONT, 13 * ui)
            if editing:
                txt, fresh = self.edit[1], self.edit[2]
                tw = blf.dimensions(FONT, txt)[0]
                tx = x + (fw - tw) / 2
                if fresh and txt:
                    rect(tx - 2 * ui, y + 4 * ui, tx + tw + 2 * ui, y + bh - 4 * ui, (0.25, 0.45, 0.7, 1))
                text(tx, y + 7 * ui, txt, 13, (1, 1, 1, 1))
                if not fresh or not txt:
                    rect(tx + tw + 1 * ui, y + 5 * ui, tx + tw + 2.5 * ui, y + bh - 5 * ui, (1, 1, 1, 0.9))
            else:
                val = str(self.get_y(f.which)) if s.has_sel else "-"
                tw = blf.dimensions(FONT, val)[0]
                text(x + (fw - tw) / 2, y + 7 * ui, val, 13, (1, 0.85, 0.3, 1))
                ac = (1, 1, 1, 0.85) if hover else (1, 1, 1, 0.45)
                text(x + 5 * ui, y + 7 * ui, "‹", 13, ac)
                text(x + fw - 11 * ui, y + 7 * ui, "›", 13, ac)
            x += fw + 12 * ui
        for i, btn in enumerate(self.buttons_y):
            label = btn.label
            w = blf.dimensions(FONT, label)[0] + 14 * ui
            btn.rect = (x, y, x + w, y + bh)
            hover = self.cursor and btn.hit(*self.cursor)
            on = s.map_auto_y
            rect(x, y, x + w, y + bh, (0.25, 0.45, 0.7, 1) if on else (0.3, 0.3, 0.33, 1) if hover else (0.2, 0.2, 0.22, 1))
            text(x + 7 * ui, y + 7 * ui, label)
            x += w + 4 * ui

        # 右侧导入设置面板
        self.buttons_side = []
        if self.show_panel:
            self.draw_panel(region, ui, rect, outline, text, y - pad)

        # 底部状态栏
        rect(0, 0, W, 26 * ui, (0.08, 0.08, 0.09, 0.85))
        parts = []
        if self.cursor and 0 <= self.cursor[1] <= H:
            wx, wz = self.to_world(*self.cursor, region)
            hv = self.heights_in(math.floor(wx), math.floor(wz), math.floor(wx), math.floor(wz))
            parts.append(f"X {math.floor(wx)}  Z {math.floor(wz)}" + (f"  地面 Y {int(hv[0])}" if hv is not None else ""))
        if sel:
            (x0, y0, z0), (x1, y1, z1) = sel
            parts.append(f"选区 X {x0}~{x1}  Y {y0}~{y1}  Z {z0}~{z1}")
        if loading:
            parts.append(f"加载中 {loading} 块")
        parts.append(self.message)
        text(8 * ui, 8 * ui, "   |   ".join(parts), 12, (0.85, 0.85, 0.85, 1))
        gpu.state.blend_set("NONE")


SPLIT_LABELS = [("MERGED", "合并为一个物体"), ("TYPE", "按方块类型拆分"), ("BLOCK", "按单个方块拆分")]
PANEL_TOGGLES = [
    ("包含", None),
    ("include_plants", "植物"),
    ("include_fluids", "流体"),
    ("include_block_entities", "方块实体"),
    ("include_entities", "物品/装饰实体"),
    ("include_mobs", "生物"),
    ("网格", None),
    ("merge_faces", "合并相邻同材质面"),
    ("merge_random_rot", "　统一随机方向（面数更少）"),
    ("merge_vertices", "合并顶点"),
    ("use_pbr", "PBR 材质（资源包）"),
    ("cull_boundary", "剔除选区边界面"),
    ("recenter", "移到原点"),
    ("overwrite_materials", "覆盖已有材质/贴图"),
    ("窗口", None),
    ("map_close_after_import", "导入后关闭此窗口"),
]


def _panel_draw(self, region, ui, rect, outline, text, top):
    s = bpy.context.scene.mineblend
    W = region.width
    pw = 220 * ui
    row = 22 * ui
    x0 = W - pw - 8 * ui
    n_rows = 2 + len(SPLIT_LABELS) + len(PANEL_TOGGLES)
    y1 = top - 8 * ui
    y0 = max(34 * ui, y1 - (n_rows * row + 16 * ui))
    self.panel_rect = (x0, y0, W - 8 * ui, y1)
    rect(x0, y0, W - 8 * ui, y1, (0.08, 0.08, 0.09, 0.9))
    outline(x0, y0, W - 8 * ui, y1, (1, 1, 1, 0.12))
    y = y1 - row
    text(x0 + 10 * ui, y + 6 * ui, "导入设置", 13, (1, 0.85, 0.3, 1))
    y -= row
    text(x0 + 10 * ui, y + 6 * ui, "导入方式", 12, (0.65, 0.65, 0.65, 1))

    def add(label, on, action, radio=False):
        nonlocal y
        y -= row
        b = Button(label, action)
        b.rect = (x0 + 4 * ui, y, W - 12 * ui, y + row)
        hover = self.cursor and b.hit(*self.cursor)
        if hover:
            rect(*b.rect, (1, 1, 1, 0.06))
        bx, by, bs = x0 + 12 * ui, y + 5 * ui, 12 * ui
        if radio:
            outline(bx, by, bx + bs, by + bs, (0.8, 0.8, 0.8, 1))
            if on:
                rect(bx + 3 * ui, by + 3 * ui, bx + bs - 3 * ui, by + bs - 3 * ui, (1, 0.75, 0.1, 1))
        else:
            rect(bx, by, bx + bs, by + bs, (0.25, 0.45, 0.7, 1) if on else (0.22, 0.22, 0.24, 1))
            outline(bx, by, bx + bs, by + bs, (0.8, 0.8, 0.8, 1))
            if on:
                text(bx + 2 * ui, by + 1 * ui, "✓", 11)
        text(bx + bs + 8 * ui, y + 6 * ui, label, 12, (0.92, 0.92, 0.92, 1) if on else (0.75, 0.75, 0.75, 1))
        self.buttons_side.append(b)

    for key, label in SPLIT_LABELS:
        add(label, s.split_mode == key, lambda shift, k=key: setattr(bpy.context.scene.mineblend, "split_mode", k), True)
    for prop, label in PANEL_TOGGLES:
        if label is None:
            y -= row
            text(x0 + 10 * ui, y + 6 * ui, prop, 12, (0.65, 0.65, 0.65, 1))
            continue
        add(label, getattr(s, prop), lambda shift, p=prop: setattr(bpy.context.scene.mineblend, p, not getattr(bpy.context.scene.mineblend, p)))


MB_OT_map_window.draw_panel = _panel_draw


def tag_view3d_redraw():
    for w in bpy.context.window_manager.windows:
        for a in w.screen.areas:
            if a.type == "VIEW_3D":
                a.tag_redraw()


def _remove_legacy_objects():
    """旧版本用来框选的几何体（MC_选区 线框盒、MC_地图预览 平面）已不再需要"""
    for name in ("MC_选区", "MC_地图预览"):
        o = bpy.data.objects.get(name)
        if o is not None:
            me = o.data
            bpy.data.objects.remove(o)
            if me is not None and me.users == 0:
                bpy.data.meshes.remove(me)
    img = bpy.data.images.get("MC_地图预览图")
    if img is not None and img.users == 0:
        bpy.data.images.remove(img)


# ---------------------------------------------------------------- 3D 视图中的选区线框（只是绘制，不是物体）

_view_handle = None


def _draw_selection_3d():
    ctx = bpy.context
    s = getattr(ctx.scene, "mineblend", None)
    if s is None or not s.has_sel or not s.show_sel_3d:
        return
    (x0, y0, z0), (x1, y1, z1) = s.sel_min, s.sel_max
    # MC (x, y, z) → Blender (x, -z, y)，方块 [x, x+1]
    X0, X1 = x0, x1 + 1
    Y0, Y1 = -(z1 + 1), -z0
    Z0, Z1 = y0, y1 + 1
    c = [(X0, Y0, Z0), (X1, Y0, Z0), (X1, Y1, Z0), (X0, Y1, Z0),
         (X0, Y0, Z1), (X1, Y0, Z1), (X1, Y1, Z1), (X0, Y1, Z1)]
    e = [(0, 1), (1, 2), (2, 3), (3, 0), (4, 5), (5, 6), (6, 7), (7, 4), (0, 4), (1, 5), (2, 6), (3, 7)]
    pts = [c[i] for a in e for i in a]
    sh = _shader("UNIFORM_COLOR")
    gpu.state.blend_set("ALPHA")
    gpu.state.line_width_set(2.0)
    gpu.state.depth_test_set("NONE")
    b = batch_for_shader(sh, "LINES", {"pos": pts})
    sh.bind()
    sh.uniform_float("color", (1.0, 0.75, 0.1, 0.9))
    b.draw(sh)
    gpu.state.line_width_set(1.0)
    gpu.state.blend_set("NONE")


def register():
    global _view_handle
    bpy.utils.register_class(MB_OT_map_window)
    _view_handle = bpy.types.SpaceView3D.draw_handler_add(_draw_selection_3d, (), "WINDOW", "POST_VIEW")


def unregister():
    global _view_handle
    if _view_handle is not None:
        bpy.types.SpaceView3D.draw_handler_remove(_view_handle, "WINDOW")
        _view_handle = None
    if MB_OT_map_window._active is not None:
        MB_OT_map_window._active.loader.stop = True
        try:
            bpy.types.SpaceTextEditor.draw_handler_remove(MB_OT_map_window._active.handle, "WINDOW")
        except Exception:
            pass
        MB_OT_map_window._active = None
    bpy.utils.unregister_class(MB_OT_map_window)
