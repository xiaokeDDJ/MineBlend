# MineBlend

把**正在运行的 Minecraft 世界**实时导入 Blender：在 Blender 里打开游戏的俯视地图框选一块区域（或在游戏里用「选区棒」框选），一键生成带真实贴图的网格。

## ✨ 核心特点：纯净导入

MineBlend 导入的场景**干净、可直接用于制作**，不会往你的工程里塞任何多余的东西：

- **材质只有两个节点**：每个材质就是「图像纹理 → 原理化 BSDF」，没有节点组、没有颜色混合节点、没有自定义着色器。打开材质就能看懂、能直接改。
- **染色已烘焙进贴图**：草、树叶、水的群系颜色直接写进贴图副本，不需要额外的相乘节点或颜色属性。
- **不创建辅助物体**：选区只是数值，在 3D 视图里画成线框，不会留下线框盒、预览平面之类的几何体。
- **平直着色、像素风贴图**：全部为平直着色，贴图使用最近邻插值，保持方块的清晰边缘。
- **材质命名清晰**：材质名就是贴图名（如 `MC_block/stone`），贴图打包在 `.blend` 内。
- **按需才加节点**：只有在游戏里启用了带 LabPBR 贴图的资源包时，才会额外连接法线、粗糙度等节点，而且可以在导入设置里关掉。

![导入后的材质节点：只有一个图像纹理和一个原理化 BSDF](docs/images/clean-nodes.webp)
<sub>导入后的材质节点：一个图像纹理 + 一个原理化 BSDF，没有任何多余节点。</sub>

### 按方块类型拆分

除了「合并为一个物体」，还可以按方块类型拆分（每种方块一个物体，方便统一替换材质或隐藏某类方块），或按单个方块拆分（每个方块一个物体，原点在方块底面中心，方便做动画）。

![按方块类型拆分导入](docs/images/split-by-type.webp)
<sub>按方块类型拆分：大纲视图里每种方块（以及每种实体）各是一个物体。</sub>

### 面数优化：合并相邻同材质面

把同一平面上相邻、材质相同的整格方块面合并成大面，墙面、地面、屋顶这类整块方块的面数会明显减少（实测约减少七成），合并前后贴图效果完全一致。

![合并相邻同材质面后的网格](docs/images/face-merge.webp)
<sub>开启「合并相邻同材质面」后，整块的墙面和地面都合并成了大面。</sub>

### 跟随游戏内加载的材质

贴图直接取自游戏**当前启用的资源包**：没有资源包就是原版或模组默认贴图，启用了资源包就用资源包的贴图。在游戏里换了资源包后重新导入，Blender 里的贴图会自动替换。

![跟随游戏内资源包导入的贴图](docs/images/resource-pack.webp)
<sub>导入的贴图与游戏里看到的一致，材质依旧只有图像纹理 + 原理化 BSDF。</sub>

## 组成

| 部分 | 运行在 | 作用 |
|---|---|---|
| **MineBlend mod**（`mineblend-<版本>+mc<MC版本>.jar`） | Minecraft 1.20.1 / 1.21.11 / 26.3（Fabric） | 选区棒；在本机开一个只读接口，把游戏里的方块模型、贴图、方块实体和实体交给 Blender |
| **MineBlend Blender 插件**（`mineblend-blender-<版本>.zip`） | Blender 4.0 ~ 5.3 | 地图选区窗口；生成网格和原理化 BSDF 材质 |

## 全部功能

- **实时读取**：直接从正在运行的游戏读取，不需要先导出存档。单人游戏可以读取存档中所有已生成的区块（不会生成新地形）。
- **真实模型与贴图**：使用游戏里实际的方块模型，原版和模组方块都支持；贴图来自游戏当前启用的资源包。
- **方块实体与实体**：箱子、告示牌、床、旗帜、头颅、模组家具；物品展示框里的物品、盔甲架、掉落物、画等；生物可选。
- **流体与动画贴图**：水、岩浆的高度与流动；动画贴图（水、岩浆、风扇等）按游戏里的帧时间展开成循环播放的图像序列。
- **渲染层**：镂空（树叶、玻璃板）的 Alpha 按游戏规则处理；半透明（水、彩色玻璃）单独成材质；发光方块带自发光。
- **PBR**：资源包带 LabPBR 贴图（`_n` / `_s`）时，自动连接法线、凹凸、粗糙度、金属度和自发光。
- **三种导入方式**：合并为一个物体 / 按方块类型拆分 / 按单个方块拆分。
- **合并相邻同材质面**：大幅减少整块方块的面数；草方块、泥土、沙子这类贴图方向随机的方块也能合成大面。
- **两种框选方式**：Blender 里的独立地图选区窗口（分块加载、拖拽框选、自动高度），或游戏内的选区棒（左右键都能对准远处方块选点）。
- **实时剖面预览**：地图窗口里调最低 / 最高 Y 时，选区内即时显示该高度范围的俯视剖面，能直接看到切开后的房间、地下室和洞穴。
- **兼容 Blender 4.0 ~ 5.3**。

## 安装

在 [Releases](../../releases) 下载最新版本。

### 1. Minecraft mod

- 环境：**Fabric Loader** + 对应版本的 **Fabric API**
- 按游戏版本选一个 jar，放进该版本的 `mods` 文件夹：

| Minecraft | mod 文件 | Java |
|---|---|---|
| 1.20.1 | `mineblend-<版本>+mc1.20.1.jar` | 17 及以上 |
| 1.21.11 | `mineblend-<版本>+mc1.21.11.jar` | 21 及以上 |
| 26.3 | `mineblend-<版本>+mc26.3.jar` | 25 及以上 |

三个版本功能相同，Blender 插件通用。

### 2. Blender 插件

| Blender 版本 | 安装包 | 安装方法 |
|---|---|---|
| 4.2 ~ 5.x | `mineblend-blender-<版本>.zip` | 编辑 → 偏好设置 → 获取扩展 → 右上角下拉菜单 → **从磁盘安装** |
| 4.0 / 4.1 | `mineblend-blender-<版本>-legacy.zip` | 编辑 → 偏好设置 → 插件 → **安装**，然后勾选启用 |

### 从 Mine2Blend 升级

1.1.0 起 mod 和插件的 ID 都改成了 `mineblend`，游戏内指令从 `/m2b` 改为 `/mb`。

- 删掉 `mods` 里的 `mine2blend-*.jar`，放入新的 `mineblend-<版本>.jar`。游戏配置会自动沿用。
- 在 Blender 偏好设置里**卸载旧的 Mine2Blend**，再安装 MineBlend。不卸载的话侧栏会同时出现两个标签页。

## 使用

1. 启动 Minecraft，进入存档。
2. 在 Blender 的 3D 视图里按 **N**，打开侧栏的 **MineBlend** 标签页，点 **连接 / 刷新**。
3. 选择导入范围（两种方式任选其一）：
   - **地图选区窗口**：点「打开地图选区窗口」，会打开一个独立窗口，显示游戏的俯视地图。
     - 左键拖拽框选（可以反复框选）；滚轮缩放；中键或右键拖动平移；Home 回到玩家位置。
     - 框选时会按地形自动设置高度范围。顶部的「最低 Y / 最高 Y」是数值框：左右拖动改值，单击直接输入数字，两端的 ‹ › 每次 ±1。
     - 选区里显示的是这个高度范围的**实时剖面**：拖动数值时画面跟着变化，被切开的地方越深越暗。
     - 右侧是导入设置面板。按 **Enter** 导入，导入后窗口自动关闭。
   - **游戏内选区棒**：在创造物品栏「工具」页找到「选区棒」，或输入 `/mb wand`。**左键设点 1，右键设点 2**，对准触及范围内或远处的方块都可以（最远 512 格）。然后在 Blender 侧栏点「读取游戏内选区」，再点「导入选区」。
4. 选区只保存为数值，不会创建任何辅助物体；主 3D 视图里会用一个橙色线框显示选区。

### 游戏内指令

| 指令 | 作用 |
|---|---|
| `/mb wand` | 获取选区棒（单人游戏，或多人游戏的创造模式） |
| `/mb pos1`、`/mb pos2` | 把脚下位置设为点 1 / 点 2 |
| `/mb expand <n>` | 把选区向各个方向扩大 n 格 |
| `/mb clear` | 清除选区 |
| `/mb info` | 查看接口状态和当前选区 |

### 导入选项

| 选项 | 说明 |
|---|---|
| 导入方式 | 合并为一个物体 / 按方块类型拆分 / 按单个方块拆分 |
| 植物、流体、方块实体、物品/装饰实体、生物 | 选择要包含的内容 |
| 合并相邻同材质面 | 把相邻的整格方块面合并成大面；这些材质的贴图改为平铺。需要做贴图绘制或烘焙时可以关掉 |
| 统一随机方向 | 草方块、泥土、沙子等方块每格贴图方向是随机的，开启后合并时不区分方向，面数少得多（合并后的大面贴图方向统一）；关闭则保留每格的随机方向 |
| PBR 材质 | 资源包带 LabPBR 贴图时连接法线、凹凸、粗糙度、金属度、自发光 |
| 合并顶点、剔除选区边界面、移到原点、覆盖已有材质 | 网格和材质的细节选项 |

## 配置

- **Blender**：连接地址在 编辑 → 偏好设置 → 插件（扩展）→ MineBlend 中设置，默认为本机 `127.0.0.1:25599`，一般不需要修改。
- **Minecraft**：配置文件是 `config/mineblend.json`（从 Mine2Blend 升级时会自动沿用旧的 `config/mine2blend.json`）。

| 键 | 默认值 | 说明 |
|---|---|---|
| `port` | `25599` | 接口端口 |
| `bindAddress` | `127.0.0.1` | 监听地址，默认只允许本机访问 |
| `warnVolume` | `8000000` | 选区超过这么多方块时在游戏里提醒「导入选区过大」（只提醒，不限制导入） |
| `extraWandItem` | `""` | 额外当作选区工具的物品 ID，例如 `minecraft:blaze_rod` |

## 安全说明

- mod 的接口**默认只监听本机**（`127.0.0.1`），局域网和互联网上的其他设备都无法访问。
- 接口是**只读**的，只能读取世界数据和设置选区，不能修改世界，也不能执行指令。
- 来自浏览器的请求（带 `Origin`、`Referer`、`Sec-Fetch-*` 请求头）一律拒绝；`Host` 不是本机地址的请求也会拒绝（防止 DNS 重绑定），以免网页偷偷访问本机接口。
- 只接受 GET 请求。
- 如果把 `bindAddress` 改成 `0.0.0.0`（例如 Blender 在另一台电脑上），同一网络里的其他设备也能读取你的世界数据。请只在可信网络中这样做，用完后改回 `127.0.0.1`。

## 已知限制

- 方块实体和实体只能读取客户端已加载的部分（渲染距离内）。
- 多人服务器上只能读取客户端已加载的区块。
- 导入大小没有限制，超大选区会先提醒；选区大到游戏内存不够时会提示「游戏内存不足」，请缩小选区或给游戏分配更多内存。
- 动态贴图（地图画、联网皮肤、告示牌文字）、光影包效果、OptiFine CTM 连接纹理不导出。
- 动画贴图是磁盘上的图像序列，无法打包进 `.blend`：已保存的 `.blend` 会写到它旁边的 `mc_anim/` 文件夹，移动工程时请一起拷贝。

## 从源码构建

- **mod**：一套源码用 [Stonecutter](https://stonecutter.kikugie.dev/) 同时构建 1.20.1 / 1.21.11 / 26.3 三个版本。需要 JDK 25 运行 Gradle（26.3 要求）；构建 1.20.1 和 1.21.11 时会编译成 Java 17 / 21 的字节码。

  ```bash
  cd mod
  ./gradlew chiseledBuild      # 构建全部版本，jar 收集到 mod/build/libs/
  ./gradlew :1.21.11:build     # 只构建某一个版本
  ```

  源码以 1.20.1 为基准，其它版本的差异写在 `//? if` 条件注释里，零散的接口差异集中在 `Mc.java`，方块模型的差异集中在 `Models.java`。

- **Blender 插件**：`blender/mineblend/` 就是插件本身。
  - 4.2 及以上：把这个目录里的文件（`blender_manifest.toml` 在 zip 根目录）打成 zip，用「从磁盘安装」。
  - 4.0 / 4.1：把整个 `mineblend` 文件夹打成 zip，用「插件 → 安装」。

## 协议

[GPL-3.0-or-later](LICENSE)

---

## English summary

**MineBlend** imports a region of a *running* Minecraft world into Blender as a textured mesh.

**Clean import is the core idea.** Every material is just *Image Texture → Principled BSDF*: no node groups, no mix nodes, no custom shaders. Biome tints are baked into the textures. The selection never creates helper objects. Shading is flat, and textures use closest-pixel interpolation. Extra PBR nodes are added only when a LabPBR resource pack is active, and you can turn them off.

- Renamed from Mine2Blend in 1.1.0 (mod/extension id `mineblend`, command `/mb`). Uninstall the old Mine2Blend add-on and remove `mine2blend-*.jar` when upgrading.
- **Mod** (Minecraft 1.20.1 / 1.21.11 / 26.3, Fabric + Fabric API; one jar per Minecraft version): adds a selection wand (`/mb wand`; left click = point 1, right click = point 2, works on distant blocks up to 512 away) and a read-only local HTTP interface (`127.0.0.1:25599` by default) that serves block models, textures, block entities and entities.
- **Blender add-on** (Blender 4.0 – 5.3): opens a standalone top-down map window of the live world. Drag to select an area, press Enter to import. The Y range fields can be dragged or typed into, and the selection shows a live cross-section of that height range. You can also import the in-game wand selection.
- Real block models, including modded blocks. Textures come from the game's active resource packs, with LabPBR normal/specular support. Animated textures are imported as image sequences. Materials are Image Texture → Principled BSDF with flat shading.
- Three import modes (merged, split by block type, split per block). An optional greedy merge of adjacent faces with the same material cuts the face count. Randomly rotated textures (grass, dirt, sand) can be merged too.
- Security: the interface binds to localhost only. It rejects browser-originated requests and non-local `Host` headers (DNS rebinding), and accepts GET requests only. It cannot modify the world or run commands.

Install: put `mineblend-<version>.jar` into `mods`. In Blender 4.2+, install `mineblend-blender-<version>.zip` via *Get Extensions → Install from Disk*. In Blender 4.0/4.1, install `…-legacy.zip` via *Add-ons → Install*.

License: GPL-3.0-or-later.
