"""MineBlend —— 从正在运行的 Minecraft（需安装 MineBlend mod）实时导入带贴图的地图网格。

两种选区方式：
  1. 地图选区窗口：独立窗口显示游戏俯视地图，拖拽框选 → 导入
  2. 游戏内框选：手持「选区棒」左键=点1、右键=点2 → Blender 中「导入游戏内选区」
选区只保存在场景属性里（scene.mineblend.sel_min / sel_max），在 3D 视图中画成线框，不创建任何几何体。
"""

# Blender 4.0 / 4.1 按传统插件读取 bl_info；4.2+ 按扩展读取 blender_manifest.toml（忽略 bl_info）
bl_info = {
    "name": "MineBlend",
    "author": "MineBlend contributors",
    "version": (1, 2, 1),
    "blender": (4, 0, 0),
    "location": "3D 视图 > 侧边栏(N) > MineBlend",
    "description": "从正在运行的 Minecraft 实时导入带贴图的地图网格",
    "category": "Import-Export",
}

import bpy
from bpy.props import BoolProperty, EnumProperty, IntProperty, IntVectorProperty, PointerProperty, StringProperty
from bpy.types import Operator, Panel, PropertyGroup

from . import bridge, importer, mapwindow


def _redraw(self, context):
    mapwindow.tag_view3d_redraw()


class MB_Preferences(bpy.types.AddonPreferences):
    """连接地址放在偏好设置里，不在侧栏显示"""
    bl_idname = __package__

    host: StringProperty(name="地址", default="127.0.0.1",
                         description="MineBlend mod 所在电脑的地址（同一台电脑上用 127.0.0.1）")
    port: IntProperty(name="端口", default=25599, min=1, max=65535,
                      description="与游戏 config/mineblend.json 中的 port 一致")

    def draw(self, context):
        col = self.layout.column()
        col.prop(self, "host")
        col.prop(self, "port")
        col.label(text="一般无需修改；游戏和 Blender 在同一台电脑上时保持 127.0.0.1", icon="INFO")


class _DefaultPrefs:
    host = "127.0.0.1"
    port = 25599


def _prefs():
    try:
        return bpy.context.preferences.addons[__package__].preferences
    except Exception:
        return _DefaultPrefs


class MB_Settings(PropertyGroup):
    # 连接地址来自插件偏好设置（不存进 .blend，也不在侧栏显示）
    @property
    def host(self):
        return _prefs().host

    @property
    def port(self):
        return _prefs().port

    has_sel: BoolProperty(name="有选区", default=False, update=_redraw)
    sel_min: IntVectorProperty(name="最小", size=3, default=(0, 60, 0), update=_redraw,
                               description="选区最小角（MC 坐标 X Y Z，含）")
    sel_max: IntVectorProperty(name="最大", size=3, default=(31, 100, 31), update=_redraw,
                               description="选区最大角（MC 坐标 X Y Z，含）")
    map_auto_y: BoolProperty(name="自动高度", default=True,
                             description="在地图窗口里框选时，按框内地形自动设置最低/最高 Y")
    show_sel_3d: BoolProperty(name="在 3D 视图显示选区", default=True, update=_redraw)

    split_mode: EnumProperty(name="导入方式", items=importer.SPLIT_ITEMS, default="MERGED")

    include_plants: BoolProperty(name="植物", default=True, description="花草、作物、树苗等")
    include_fluids: BoolProperty(name="流体", default=True, description="水和岩浆（以及模组流体）")
    include_block_entities: BoolProperty(name="方块实体", default=True,
                                         description="箱子、告示牌、床、旗帜、头颅、模组家具等由方块实体渲染的模型")
    include_entities: BoolProperty(name="物品/装饰实体", default=True,
                                   description="物品展示框里的物品、盔甲架、掉落物、画、矿车、模组实体等")
    include_mobs: BoolProperty(name="生物", default=False, description="动物、怪物、村民等（不含玩家）")
    cull_boundary: BoolProperty(name="剔除选区边界面", default=False,
                                description="选区外侧被截断处若有方块则不生成该面（面数更少，但模型不封闭）")
    merge_vertices: BoolProperty(name="合并顶点", default=True)
    use_pbr: BoolProperty(name="PBR 材质", default=True,
                          description="游戏里启用的资源包带 LabPBR 贴图（_n 法线、_s 高光）时，导入并连接法线/凹凸、粗糙度、金属度、自发光")
    merge_faces: BoolProperty(name="合并相邻同材质面", default=True,
                              description="把同一平面上相邻、材质相同的整格方块面合并成大面，大幅减少面数"
                                          "（这些材质的贴图改为平铺）。按单个方块拆分时不生效")
    merge_random_rot: BoolProperty(name="统一随机方向", default=True,
                                   description="草方块、泥土、沙子等方块在游戏里每格贴图方向是随机的。开启后合并面时不区分方向，"
                                               "面数少得多（合并后的大面贴图方向统一）；关闭则保留每格的随机方向")
    map_close_after_import: BoolProperty(name="导入后关闭地图窗口", default=True)
    recenter: BoolProperty(name="移到原点", default=False,
                           description="导入后把模型放在世界原点（选区底面中心），否则保持与 MC 坐标一致")
    overwrite_materials: BoolProperty(name="覆盖已有材质/贴图", default=False,
                                      description="重新导入时用游戏中的贴图覆盖同名材质和图片（会丢失手动调整）")

    status: StringProperty(default="")
    connected: BoolProperty(default=False)


def S(context) -> MB_Settings:
    return context.scene.mineblend


def _err(op, e):
    op.report({"ERROR"}, str(e))
    return {"CANCELLED"}


def _sel(s):
    mn = [min(s.sel_min[i], s.sel_max[i]) for i in range(3)]
    mx = [max(s.sel_min[i], s.sel_max[i]) for i in range(3)]
    return mn, mx


def _game_selection(s):
    sel = bridge.get_json(s.host, s.port, "/selection")
    if not sel.get("pos1") or not sel.get("pos2"):
        raise bridge.BridgeError("游戏内还没选好两个点（手持选区棒：左键方块=点1，右键方块=点2）")
    a, b = sel["pos1"], sel["pos2"]
    return [min(a[i], b[i]) for i in range(3)], [max(a[i], b[i]) for i in range(3)]


# ====================================================================== operators

class MB_OT_check(Operator):
    bl_idname = "mineblend.check"
    bl_label = "连接 / 刷新"
    bl_description = "检查与 MC 的连接，读取玩家位置"

    def execute(self, context):
        s = S(context)
        try:
            st = bridge.get_json(s.host, s.port, "/status")
        except bridge.BridgeError as e:
            s.connected = False
            s.status = str(e)
            return _err(self, e)
        s.connected = True
        if not st.get("inWorld"):
            s.status = f"已连接 MC {st.get('mcVersion')}，但还没进入存档"
        else:
            world = st.get("worldName") or ("多人服务器" if not st.get("singleplayer") else "")
            s.status = f"已连接：{world} {st.get('dimension')}  玩家 {tuple(st['player'])}"
        self.report({"INFO"}, s.status)
        return {"FINISHED"}


class MB_OT_import_selection(Operator):
    bl_idname = "mineblend.import_selection"
    bl_label = "导入选区"
    bl_description = "导入当前选区（选区很大时会先提醒）"

    @staticmethod
    def _warnings(context):
        s = S(context)
        if not s.has_sel:
            return []
        mn, mx = _sel(s)
        return importer.size_warnings(mn, mx, s.split_mode)

    def invoke(self, context, event):
        if self._warnings(context):
            return context.window_manager.invoke_props_dialog(self, width=460)
        return self.execute(context)

    def draw(self, context):
        col = self.layout.column(align=True)
        for line in self._warnings(context):
            col.label(text=line, icon="ERROR")
        col.separator()
        col.label(text="确定要继续导入吗？")

    def execute(self, context):
        s = S(context)
        if not s.has_sel:
            return _err(self, "还没有选区：打开地图选区窗口框选，或读取游戏内选区")
        mn, mx = _sel(s)
        try:
            _, msg = importer.do_import(context, mn, mx, "sel")
        except bridge.BridgeError as e:
            return _err(self, e)
        self.report({"INFO"}, msg)
        return {"FINISHED"}


class MB_OT_sel_from_game(Operator):
    bl_idname = "mineblend.sel_from_game"
    bl_label = "读取游戏内选区"
    bl_description = "把游戏内用选区棒选的范围设为当前选区（手持选区棒：左键=点1，右键=点2，可对准远处方块；获取：创造物品栏「工具」或 /mb wand）"

    def execute(self, context):
        s = S(context)
        try:
            mn, mx = _game_selection(s)
        except bridge.BridgeError as e:
            return _err(self, e)
        s.sel_min, s.sel_max, s.has_sel = mn, mx, True
        return {"FINISHED"}


class MB_OT_sel_to_game(Operator):
    bl_idname = "mineblend.sel_to_game"
    bl_label = "发送到游戏"
    bl_description = "把当前选区显示到游戏内"

    def execute(self, context):
        s = S(context)
        if not s.has_sel:
            return _err(self, "还没有选区")
        (x0, y0, z0), (x1, y1, z1) = _sel(s)
        try:
            bridge.get_json(s.host, s.port, "/selection/set", dict(x1=x0, y1=y0, z1=z0, x2=x1, y2=y1, z2=z1))
        except bridge.BridgeError as e:
            return _err(self, e)
        return {"FINISHED"}


class MB_OT_sel_clear(Operator):
    bl_idname = "mineblend.sel_clear"
    bl_label = "清除选区"

    def execute(self, context):
        S(context).has_sel = False
        return {"FINISHED"}


# ====================================================================== UI

def _wrap(text, n):
    out = []
    for para in text.split("\n"):
        while len(para) > n:
            out.append(para[:n])
            para = para[n:]
        out.append(para)
    return out


class _Base:
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "MineBlend"


class MB_PT_main(_Base, Panel):
    bl_label = "MineBlend"
    bl_idname = "MB_PT_main"

    def draw(self, context):
        s = S(context)
        lay = self.layout
        lay.operator("mineblend.check", icon="LINKED" if s.connected else "UNLINKED")
        if s.status:
            box = lay.box()
            col = box.column(align=True)
            col.scale_y = 0.8
            for line in _wrap(s.status, 30):
                col.label(text=line)


class MB_PT_selection(_Base, Panel):
    bl_label = "选区"
    bl_parent_id = "MB_PT_main"

    def draw(self, context):
        s = S(context)
        lay = self.layout
        r = lay.row()
        r.scale_y = 1.4
        r.operator("mineblend.map_window", icon="WORLD")
        row = lay.row(align=True)
        row.operator("mineblend.sel_from_game", icon="IMPORT", text="读取游戏内选区")
        row.operator("mineblend.sel_to_game", icon="EXPORT", text="发送到游戏")
        if s.has_sel:
            col = lay.column(align=True)
            col.prop(s, "sel_min")
            col.prop(s, "sel_max")
            mn, mx = _sel(s)
            size = [mx[i] - mn[i] + 1 for i in range(3)]
            sub = lay.column(align=True)
            sub.scale_y = 0.8
            sub.label(text=f"大小 {size[0]}×{size[1]}×{size[2]} = {size[0] * size[1] * size[2]:,} 方块")
            warn = importer.size_warnings(mn, mx, s.split_mode)
            if warn:
                box = lay.box()
                wc = box.column(align=True)
                wc.scale_y = 0.8
                wc.alert = True
                for w in warn:
                    for i, line in enumerate(_wrap(w, 26)):
                        wc.label(text=line, icon="ERROR" if i == 0 else "BLANK1")
            row = lay.row(align=True)
            row.prop(s, "show_sel_3d", text="显示线框", toggle=True)
            row.operator("mineblend.sel_clear", icon="X", text="清除")
            lay.prop(s, "split_mode", text="")
            r = lay.row()
            r.scale_y = 1.4
            r.operator("mineblend.import_selection", icon="IMPORT")
        else:
            lay.label(text="还没有选区", icon="INFO")


class MB_PT_options(_Base, Panel):
    bl_label = "导入选项"
    bl_parent_id = "MB_PT_main"
    bl_options = {"DEFAULT_CLOSED"}

    def draw(self, context):
        s = S(context)
        lay = self.layout
        col = lay.column(align=True)
        col.prop(s, "include_plants")
        col.prop(s, "include_fluids")
        col.prop(s, "include_block_entities")
        col.prop(s, "include_entities")
        col.prop(s, "include_mobs")
        lay.prop(s, "split_mode")
        col = lay.column(align=True)
        col.prop(s, "map_auto_y")
        col.prop(s, "cull_boundary")
        col.prop(s, "merge_vertices")
        col.prop(s, "merge_faces")
        sub = col.row()
        sub.enabled = s.merge_faces
        sub.separator(factor=2.0)
        sub.prop(s, "merge_random_rot")
        col.prop(s, "use_pbr")
        col.prop(s, "recenter")
        col.prop(s, "overwrite_materials")


classes = (
    MB_Preferences, MB_Settings,
    MB_OT_check, MB_OT_import_selection,
    MB_OT_sel_from_game, MB_OT_sel_to_game, MB_OT_sel_clear,
    MB_PT_main, MB_PT_selection, MB_PT_options,
)


def register():
    for c in classes:
        bpy.utils.register_class(c)
    bpy.types.Scene.mineblend = PointerProperty(type=MB_Settings)
    mapwindow.register()


def unregister():
    mapwindow.unregister()
    del bpy.types.Scene.mineblend
    for c in reversed(classes):
        bpy.utils.unregister_class(c)
