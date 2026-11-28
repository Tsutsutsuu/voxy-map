# Voxy Map — 技术说明 / Technical Reference

> 面向想了解实现细节、调参或二次开发的人。精简的安装/使用说明见 [README_NEW.md](README_NEW.md)。
> For install/usage see [README_NEW.md](README_NEW.md); this document covers how it works and how to tune it.

[简体中文](#简体中文) | [English](#english)

---

# 简体中文

## 1. 总览

```
Voxy 的 RocksDB LOD 存储
  └─(只读实例 + 重开修复)─ VoxyIndex / VoxyTileSource
        └─ 解码 section、合并所有 LOD 层级 → 每地图像素一个 packed long
              └─ LiveTileStore：256×256 地图瓦片 + 每瓦片动态纹理 + 磁盘 .vxt 缓存
                    ├─ 2D：按视口绘制瓦片（DynamicTexture）
                    └─ 3D：Gpu3DMap 在后台把瓦片网格化成顶点/索引，atlas 纹理 + 独立 GPU pass
```

- 纯客户端；不加载区块、不生成地形，数据全部来自 Voxy 已经存下的 LOD。
- 主世界专用；下界/末地不建图。
- Fabric 26.2 / 26.1.2 共用同一份 Stonecutter 源码；NeoForge 1.21.1 是同一批源码 + `compat/` 兼容层。

## 2. Voxy 数据读取

- 以 **只读实例** 打开 Voxy 的 RocksDB（`RocksDB.openReadOnly`），列族 `world_sections`、`id_mappings`。
- section key（大端 8 字节）：`(lvl<<60) | ((y&0xFF)<<52) | ((z&0xFFFFFF)<<28) | ((x&0xFFFFFF)<<4)`。
- section value：ZSTD 帧 → 体素数组 `long[32768]` + LUT。体素位域：
  `light(8bit)<<56 | biome(9bit)<<47 | blockId(20bit)<<27`；空气判定为 blockId 段为 0。
- `id_mappings`（4 字节 key，`0x40` block_state / `0x80` biome）：biome 值是 GZIP 的 NBT 文本，用来还原注册名；`biomeId` 会被写进合并结果。
- **只读句柄是快照语义**：Voxy 之后写入的 section 看不到，必须 `closeDb()` 后重新 `openReadOnly`，并清空 `sourceEmpty` 负缓存与 section 索引（`VoxyTileSource.invalidateIndex()`）。地图打开期间会按扫描耗时自适应地周期性重扫（live 事件活跃时约 3 s 起，静默后 10 s / 30 s 兜底）。
- **live 事件**：`VoxyLiveProbe` 用反射挂到 Voxy `WorldEngine` 的 section dirty 回调（Fabric 原生 Voxy 与 NeoForge 经 Roxy 都实测可用），把新 section 直接送进瓦片更新；反射目标不匹配时自动退回周期扫描，2D 仍能工作。
- 合并规则（每列）：找最高非空气 `top`、最高非水固体 `solid`、`topFluid`；同层用 solid 补洞穴顶、粗层级在 `top<0` 时补全、`topY==31 && !topFluid` 视为地下（NO_TOP）；最终输出 packed long：`lvl<<48 | topFluid<<52 | (top+32768)<<24 | (floor+32768) | biomeId<<53`，哨兵 `NO_SOLID=-32768`、`NO_TOP=-32767`。
- 兼容层：`RocksBridge` 通过反射访问 Voxy 自带 jar-in-jar 的 rocksdbjni，避免 JPMS split-package；在 NeoForge 上通过 module layer 查找类。

## 3. 瓦片与磁盘缓存

- 地图被切成 **256×256 地图像素** 的瓦片；每瓦片持有解码后的像素（packed long）、RGB、群系 ID 与一张 `DynamicTexture`（注册名 `voxymap:live_<tx>_<tz>_<textureHash>`）。换配色/群系覆盖只重建受影响瓦片的纹理。
- 每存档一个缓存目录 `voxymap-cache/<storage 父目录名>/`：`tiles/*.vxt`（每瓦片压缩像素）、`source-index.bin`（section 索引）、`state.properties`（该世界的样式/叠加层/比例/缩放）。
- 旧全局路径的 `map.vxm` / `color.vxc` 仍会在旧目录里识别，但统一瓦片路径不使用它们。
- 缓存版本：`.vxt` = 18、快照 = 14、颜色缓存 = 21（三个版本号在 Fabric 26.2/26.1.2 与 NeoForge 1.21.1 必须一致），调色板 JSON `version` = 9；只有影响像素内容的改动才升版本。加载时校验地图比例，不一致即丢弃重建。
- 回收：`cacheKeepCount` 按最近写入保留若干存档缓存；`diskCacheMaxMb` 限制单存档磁盘占用；内存侧有 `tileResidentCap` 与纹理显存预算 `gpuTextureBudgetMb`（默认每瓦片 256 KiB）。

## 4. 2D 渲染与颜色管线

- 2D 只绘制视口内的瓦片（含视距裁剪），缩放/平移按 `frameDt` 做动画与插值。
- 颜色管线（后台 loader 线程，进度分段上报）：解包 → 补洞（可选） → 去噪（可选） → 水深 BFS（10 级） → 海洋 box blur（仅水） → 自适应色带与等高线 → 配色 + 山体阴影 → 群系边界。
- 光照：2D 与高级 3D 共用同一套方向光（`lightStrength` / `lightAmbient` + 每风格强度系数，blocks = 1.0、nautical = 0.3、其余 0.7、水 0.4）。陆地法线取**原始高度**（不再 3×3 平均，平均会把小起伏抹平）；水面法线取**模糊后的 depthMap**（水色本身来自模糊深度，两者同源才不会有噪声状阴影）。
- 等高线：每世界叠加层（左下角按钮，状态写进 `state.properties` 的 `contours`）；按原始高度差生成；`autoHideContours3d` 生效时在 3D 内切换只改会话覆盖，不写盘。
- `ignorePlantHeight`：草类方块按调色板里的 `plantHeight`（矮草/蕨/灌木 1 格、高草/大蕨 2 格）从高度里扣掉，只显示地面。目前不在配置屏上，仅保留为默认值。
- 7 套配色（默认 `blocks` 方块颜色，菜单第一项）：`blocks`、`current`、`gradient`、`gmt`、`nautical`、`gradient-turbo`、`gradient-soft`。`blocks` 使用 200 色材质色表（旧 58 色 + 142 新色，草/树叶/干树叶/水按群系 tint）并叠加同一套山影；其余 6 套为高度配色，`adaptiveColorRamp` 按世界实际高度拉伸色带，`landMaxRamp` 只把陆地色带拉伸到地图最高点（水色不变）。
- 群系边界：4-邻接比较出边界像素，按 `(x+z)&1` 棋盘格保留一半形成虚线；边界也烘焙进 3D atlas 纹理。
- 群系标签：锚点取区域**最大内切圆中心**（距离变换），按最小面积过滤；整图构建上限 6,000,000 px（约 91 个瓦片），超限或失败则退化为"视口区域标签"（中心量化到 1024 px、半径量化到 256 px，重建节流 400 ms、失败退避 5 s）；标签被按钮/菜单/文字框压住时隐藏。

## 5. 3D GPU 地形

- 每瓦片在后台线程生成网格；重建判定用 `stamp`（瓦片版本、颜色版本、配色哈希、地图比例、海平面、垂直比例、右/下邻居的 coloredVersion）。
- 三级精度（地图像素/顶点）：近 `1`（默认 ≤768 格）、中 `4`（≤2048 格）、远 `32`，距离按真实相机 eye 的球形距离计算；先远景铺满再逐步细化；切换带 15% 滞回；步长必须整除 256。
- 瓦片边缘有向下的裙边（默认 16 格、双绕序），遮住不同精度之间的接缝；边界为未加载数据的段跳过，避免地图边缘出现"方管"。
- 纹理：1024×1024 RGBA8 图集分页，每页 16 个 256² 槽位，NEAREST + clamp、无 mipmap；只上传该瓦片的 256² 子区域（复用 256 KB staging buffer）。atlas 上限 = `gpuTextureBudgetMb × 4` 个瓦片（默认 1024），超限时每帧按相机距离保留最近的瓦片、最远的先淘汰。
- 渲染：独立 GPU pass，逐瓦片视锥剔除（6 平面 AABB，每帧无分配）+ 背面剔除；全亮（直接复用 2D 未打光配色）。
  - Fabric 26.2：私有 `D32_FLOAT` 深度附件 + **反向 Z**（clear 0.0、`GREATER_THAN_OR_EQUAL`、近平面 0.05）。
  - Fabric 26.1.2：私有 D32_FLOAT + **标准 Z**（clear 1.0、`LESS_THAN_OR_EQUAL`）。
  - NeoForge 1.21.1：复用主目标自带的 **24bit 定点深度 + 标准 Z**（clear 1.0 / LEQUAL），近平面取 4.0 以保住远景深度精度（相机最近 64 格，不会裁到地形）。
- 高级 3D 外观（`advanced3dRender`，默认开）：地形直接复用 2D 彩色贴图（含烘焙好的山影），只在其上叠加**逐格 AO** 与**侧面方向光 + 垂直渐变**；离屏 pass 与自定义复合四边形已删除（0.5.4 前的一次性能改动），磁盘/显存都只走同一份 atlas。
- 体素地形（`gpuTerrainVoxel`，默认开，仅近景 step）：每格一个平面顶 + 相邻更低时的垂直侧墙；顶面按"等高 + 同 AO"贪心合并，侧墙按朝向给系数 西 0.92 / 北 0.90 / 东 0.74 / 南 0.76，底部再暗 18%；瓦片边界只画一侧裙边，已有侧墙覆盖处跳过，避免共面 z-fighting。
- 程序化屏幕空间天空：按 pitch + FOV 求地平线 NDC，画两条渐变带（天空色 → 地平线色 → 下方色），无几何体、无深度写入；相机离地 3000 格起在 1000 格内淡出为黑天并关闭雾。
- 水平雾（blocks 样式）：终点固定为 `fogDistance3d`（默认 10000 格），起点随相机离地高度（约 2× 高度，最小 600），俯视 85° 附近平滑关闭；只取水平（XZ）距离，所以高度与俯仰不会加雾。
- 版本适配：Fabric 26.1.2 的天空与雾只是**调用形状**不同（`RenderPipelines.BLOCK_SNIPPET` 取代 bind group layout、`CommandEncoder.mapBuffer` 取代 `GpuBuffer.map`、`pass.draw(firstVertex, vertexCount)` 的参数顺序与 26.2 相反），差异全部收在 `BlazeCompat`，三个平台观感一致。
- 渲染距离带：贴地 +1.05 的黄色半透明三角带，64 段/边，每帧动态更新（混合、深度测试、不写深度）。
- 3D 交互：左键平移、中键拖动环绕、滚轮缩放（插值）、空格缓动定位；射线拾取被地图像素 AABB 裁剪并校验空数据；玩家箭头浮在地形上方；3D 群系标签有距离限制、+6 抬高与地形/控件遮挡。
- 相机：进入 3D 时"无缝俯视"（位置/比例与 2D 对应），退出时 0.22 s 旋转归位；中键短按=切换、按住拖动=环绕（按按压时长与位移区分）；可按世界记住 3D 相机。
- 资源生命周期：`keep3dTilesIn2d`（默认开）让 2D↔3D 切换不重建 GPU 地形；关图/换世界按配置清理；旧 buffer 延迟 3 帧释放；`Q` 面板把 `mesh` / `atlas` / `depth` 显存分项显示。

## 6. 群系标签（2D/3D 共用数据）

- 2D 与 3D 共用同一份标签数据（锚点 + 群系 ID + 宽度），文字始终是屏幕空间绘制，不烘焙进纹理。
- 构建触发键是 `biomeVersion()`：只有像素真的变化才自增（逐出瓦片不触发）；2D 下按 `zoom × uiScale` 估算可见性，3D 下由相机距离门控。
- 标签显示会跳过被按钮/浮层/对话框/信息条覆盖的位置（`collectLabelBlockers`）。

## 7. 配置与每世界状态

- 71 个配置项（配置屏显示的全集），6 个标签页：常规 / 地图绘制 / 数据与刷新 / 3D 视图 / 性能 / 调试，页内再分小节；每项有说明文字，支持开关、下拉、滑块（可拖动或输入数值）。`drawContours` 保留为"新世界的默认等高线开关"，不再出现在配置屏（改由左下角按钮按世界切换）。
- 草稿模式：`取消` 丢弃、`应用` 保存不关闭、`完成` 保存并关闭、`重置` 恢复出厂默认。
- 存储：Fabric 为 `config/voxymap-client.json`（平铺 key）；NeoForge 为 `config/voxymap-client.toml`（`ModConfigSpec`，分组 `ui/map/data/view3d/perf/debug`，另有一个对界面隐藏的 `legacy` 组保存旧键）。
- 配置只决定**新世界**默认值；每个世界的样式、叠加层（群系边界 / 视距框 / 等高线）、地图比例、缩放（以及 3D 相机）存在该世界缓存目录的 `state.properties` 里。
- 版本号标记：Fabric 存在 JSON 的 `configVersion`，NeoForge 存在 TOML 的 `[meta] configVersion`，语义与下面的规则一致。
- **配置版本号规则**：`configVersion` 只在**需要重置所有用户配置**时递增（例如默认值整体改变、旧配置会与新逻辑冲突）。普通的默认值微调、新增配置项、说明文字和分组调整都不要递增；缺少新配置项时按默认值加载即可。递增会让所有用户已保存的配置被默认值覆盖一次，属于破坏性操作。

<a id="配置参考"></a>

### 配置参考（按页）

| 页/组 | 关键项 | 说明 |
| --- | --- | --- |
| 常规 / `ui` | `showCoordinates`、`showHint`、`teleportEnabled`、`defaultZoom`、`rememberZoom`、`arrowStyle` | 交互与界面 |
| 地图绘制 / `map` | `defaultStyle`、`biomeColors`、`adaptiveColorRamp`、`landMaxRamp`、`drawHillshade`、`lightStrength`、`lightAmbient`、`waterBlur*`、`drawWaterDepth`、`autoSeaLevel`、`seaLevelOverride`、`contourDensity`、`minContourArea`、`denoise`、`fillHoles`、`ignorePlantHeight`（仅默认值）、`drawContours`（仅默认值） | 配色、光照与地形处理 |
| 数据与刷新 / `data` | `liveTiles`、`mapScale`、`maxLodLevel`、`cacheKeepCount`、`diskCacheMaxMb` | 数据源、比例与缓存 |
| 3D 视图 / `view3d` | `fov3d`、`verticalScale3d`、`rotateSens3d`、`panSens3d`、`zoomSens3d`、`remember3dView`、`advanced3dRender`、`advanced3dAo`、`fogDistance3d`、`autoHideContours3d`、`gpuTerrain*`、`gpuTerrainVoxel`、`minDistance3d`、`maxDistance3d`、`playerMarker*`、`biomeLabel3dRange`、`keep3dTilesIn2d` | 相机、高级 3D 外观、网格精度、标记与标签 |
| 性能 / `perf` | `tileResidentCap`、`gpuTextureBudgetMb`、`gpu3dBuildThreads`、`gpu3dBatchBuild`、`skipWorldRender`、`releaseTexturesOnClose` | 内存/显存/线程 |
| 调试 / `debug` | `debugEnabled`、`debugRefreshChat`、`debugVoxyEvents`、`debugTileTrace` | 调试输出与追踪 |

调优建议：开图掉帧先确认 `skipWorldRender=true`；远景模糊就降 `mapScale`（调粗）或关群系叠加；显存吃紧降 `gpuTextureBudgetMb`；磁盘吃紧降 `diskCacheMaxMb`；老机器把 `gpu3dBuildThreads` 设小或 `gpu3dBatchBuild` 降低。

## 8. 多版本 / 多加载器适配

- **Fabric 26.2 / 26.1.2（Stonecutter）**：共享源码；版本差异集中在
  `BlazeCompat.java`（Blaze3D API 形状：纹理格式、索引类型、拓扑、RenderPass 参数/清屏色、`submit`、主目标访问、pipeline builder 差异）、
  `HudMixin` / `ItemInHandRendererMixin`（26.1.2 的 `Gui` / `renderHandsWithItems`）以及 `stonecutter.gradle.kts` 的字符串替换（`Minecraft.gui.screen()` ↔ `Minecraft.screen`、`gui.hud.getChat()` ↔ `gui.getChat()`）。
- **NeoForge 1.21.1**：26.2 源码逐文件复制到 `mod/src`，平台差异集中到兼容层：
  - `compat/GuiGraphicsExtractor` + `Pose2D` + `GuiPipelines`：把 26.2 的 extract 两阶段 GUI 映射到 1.21.1 的 `GuiGraphics` 立即绘制；
  - `compat/gpu/*`：用 `GlStateManager`/`RenderSystem`/`ShaderInstance` 重实现 Blaze3D 的 `GpuBuffer`/`GpuTexture`/`RenderPass`/`RenderPipeline`，自带 `voxymap:terrain`、`voxymap:overlay`、`voxymap:terrain3d`、`voxymap:sky` 四个 core shader；每瓦片一个 VBO/IBO/VAO；
  - 高级 3D 的天空用不读矩阵的 NDC shader；水平雾走 `voxymap:terrain3d` 的 `FogStart`/`FogEnd`/`FogColor` uniform —— 1.21.1 的 `ShaderInstance.setDefaultUniforms` 会用 `RenderSystem.setShaderFogStart/End/Color` 写入，正好等价 26.2 的雾 UBO（没有 UBO，也就不需要 fence/环缓冲）；
  - 3D pass 的绘制点在 `GameRenderer.render` 的世界清深度之后、GUI 批处理之前（1.21.1 没有 `GuiRenderer`），避免打断第三方 GUI 批处理 mod 的 VAO 缓存；
  - `VoxyMapConfig` 把 26.2 的 `Value<T>` 访问门面套在 NeoForge TOML 上，业务代码无需改动；
  - 修复 1.21.1 细节：`GuiGraphics.blit` 无 tint 路径不会自己开混合（补 `enableBlend`），`BufferUploader` VAO 缓存需在 pass 收尾 `invalidate()`，`DynamicTexture`/`GlStateManager.upload` 的 state 需显式 pin。
- **数据来源**：Fabric 直接读 Voxy；NeoForge 由 Roxy 桥接 Fabric 版 Voxy（实测 NeoForge 21.1.265 + Roxy 0.4.1 + Voxy 0.2.16-beta）。

## 9. 构建与仓库结构

```bash
# Fabric（26.2 / 26.1.2，共用源码）—— 需要 JDK 25
cd stonecutter
./gradlew build            # 或 .\gradlew.bat build
# 产物：versions/26.2-fabric/build/libs/voxy-map-<ver>+26.2+fabric.jar
#       versions/26.1-fabric/build/libs/voxy-map-<ver>+26.1.2+fabric.jar

# NeoForge 1.21.1 —— 需要 JDK 21
cd mod
./gradlew build
# 产物：mod/build/libs/voxy-map-<ver>+1.21.1+neoforge.jar
```

- `stonecutter/`：Fabric 多版本工程（共享源码 + `versions/` 各节点产物）。
- `mod/`：NeoForge 1.21.1 工程（`compat/` 是平台兼容层，`mixin/GameRendererMixin` 负责 `skipWorldRender`）。
- `mod/tools/`：开发者工具（javac 快检、绕过启动器直接拉起实例、可选游戏内自测脚本）。
- `poc/`：离线读取 Voxy storage 的 Java 单文件工具，用于诊断/渲染验证。

## 10. 已知限制与排查

- 仅主世界；下界/末地不建图。
- 群系按注册名 path 反查，多个模组存在同名群系时可能显示错误（概率低）。
- Fabric 26.1.2 在 `skipWorldRender` 下关图有极低概率触发 NVIDIA 驱动崩溃（26.2 无此问题）。
- Fabric **26.1.2 的程序化天空与水平雾自 2026-11-29 起已实现**（早期版本确实没有，旧记录已更正）：差异只是调用形状（`BLOCK_SNIPPET` 管线、`CommandEncoder.mapBuffer`、`pass.draw(firstVertex, vertexCount)`），见踩坑记录 §32.3；`:26.1-fabric` 实机自测（含俯仰截图）通过。
- 3D 远景深度精度：Fabric 用私有 D32F；NeoForge 1.21.1 用主目标 24bit 定点深度，极远景若出现 z-fighting 可加大近平面或改自建 D32F。
- 地图边缘黑色 = Voxy 还没存下数据（没装 SeedGen 时最常见），不是渲染错误；数据不更新先点「刷新」。

---

# English

## 1. Overview

```
Voxy's RocksDB LOD storage
  └─(read-only instance, reopened to see new writes)─ VoxyIndex / VoxyTileSource
        └─ decode sections, merge every LOD level → one packed long per map pixel
              └─ LiveTileStore: 256×256 map tiles + one dynamic texture per tile + .vxt disk cache
                    ├─ 2D: draw the tiles in view (DynamicTexture)
                    └─ 3D: Gpu3DMap meshes tiles on workers, uploads per-tile buffers, draws via a GPU pass
```

- Client-side only; no chunk loading and no world generation — everything comes from the LOD data Voxy already stored.
- Overworld only; the Nether/End are not mapped.
- Fabric 26.2 / 26.1.2 share one Stonecutter source tree; NeoForge 1.21.1 is the same sources plus the `compat/` layer.

## 2. Reading Voxy's data

- Opens Voxy's RocksDB as a **read-only instance** (`RocksDB.openReadOnly`), column families `world_sections` and `id_mappings`.
- Section key (big-endian 8 bytes): `(lvl<<60) | ((y&0xFF)<<52) | ((z&0xFFFFFF)<<28) | ((x&0xFFFFFF)<<4)`.
- Section value: ZSTD frame → voxel array `long[32768]` + LUT. Voxel bits: `light(8)<<56 | biome(9)<<47 | blockId(20)<<27`.
- `id_mappings` (4-byte keys, `0x40` block_state / `0x80` biome): biome values are GZIP-compressed NBT used to recover registry names.
- **A read-only handle is a snapshot**: sections written afterwards are invisible. The index must reopen the DB (`VoxyTileSource.invalidateIndex()`: close + `openReadOnly`) and clear the `sourceEmpty` negative cache; the map also rescans periodically (about every 3 s while live updates are flowing, falling back to 10 s / 30 s when quiet).
- **Live updates**: `VoxyLiveProbe` attaches by reflection to Voxy's `WorldEngine` section-dirty callback (works with native Fabric Voxy *and* the Roxy bridge on NeoForge). If the reflection targets don't match, it silently falls back to periodic scanning.
- Merge rule per column: highest non-air `top`, highest non-water `solid`, `topFluid`; same-level `solid` completes cave ceilings, coarser levels fill when `top<0`, `topY==31 && !topFluid` is treated as underground (NO_TOP). Output packed long: `lvl<<48 | topFluid<<52 | (top+32768)<<24 | (floor+32768) | biomeId<<53`, sentinels `NO_SOLID=-32768`, `NO_TOP=-32767`.

## 3. Tiles and disk cache

- The map is split into **256×256-pixel tiles**. Each tile keeps its decoded packed pixels, RGB, biome IDs and one `DynamicTexture` (registered as `voxymap:live_<tx>_<tz>_<textureHash>`); a palette/overlay change only rebuilds the affected textures.
- One cache directory per world: `voxymap-cache/<storage parent>/` with `tiles/*.vxt` (compressed pixels per tile), `source-index.bin` (section index) and `state.properties` (that world's style, overlays, map scale and zoom).
- Legacy `map.vxm` / `color.vxc` files are still recognised in old directories but are not used by the unified tile path.
- Cache versions: `.vxt` = 18, snapshot = 14 and colour cache = 21 (identical on every loader), palette JSON `version` = 9; only changes that affect pixel content bump them. Loading validates the map scale and discards mismatching caches.
- Eviction: `cacheKeepCount` (keep the N most recently written world caches), `diskCacheMaxMb` (per-world disk cap), `tileResidentCap` and the `gpuTextureBudgetMb` texturing budget (256 KiB per tile) in memory.

## 4. 2D rendering and the colour pipeline

- 2D draws only the tiles in view; zoom/pan are animated from `frameDt`.
- Colour pipeline (background loader thread, segmented progress): unpack → fill holes → denoise → water-depth BFS (10 levels) → ocean box blur (water only) → adaptive ramp + contours → palette + hillshade → biome edges.
- Lighting: one directional-light model shared by the 2D map and the advanced 3D view (`lightStrength` / `lightAmbient` plus a per-style factor: blocks 1.0, nautical 0.3, the other palettes 0.7, water 0.4). Land normals use the **raw heights** (the old 3×3 average flattened small relief); water normals use the **blurred depthMap**, because the water colour itself comes from that blurred depth.
- Contours are a per-world overlay (bottom-left button, persisted as `contours` in `state.properties`) and are derived from raw height differences; with `autoHideContours3d` on, toggling them inside 3D only changes a session override and is never written to disk.
- `ignorePlantHeight` subtracts the palette's `plantHeight` (1 for short grass/fern/bush, 2 for tall grass/large fern) from the height, so only the ground shows. It is a default-only option and is not on the config screen.
- Seven palettes (default `blocks`, the first menu entry): `blocks`, `current`, `gradient`, `gmt`, `nautical`, `gradient-turbo`, `gradient-soft`. `blocks` uses the 200-colour material palette (58 kept + 142 new; grass/leaves/dry foliage/water use their vanilla biome tints) with the same hillshade; the other six are height palettes where `adaptiveColorRamp` stretches to the world's real height range and `landMaxRamp` stretches land only (water colours stay put).
- Biome boundaries: 4-neighbour comparison, then `(x+z)&1` checkerboard keeps half the pixels as a dashed line; the same edge mask is baked into the 3D atlas.
- Biome labels: anchor at the **centre of the largest inscribed circle** (distance transform), filtered by minimum area. A full-map build is capped at 6,000,000 px (≈91 tiles); beyond that (or on failure) it falls back to viewport-local labels (centre quantised to 1024 px, radius to 256 px, 400 ms throttle, 5 s failure backoff). Labels are hidden when a button/menu/text plate would cover them.

## 5. 3D GPU terrain

- Each tile is meshed on a worker thread; rebuilds are driven by a `stamp` (tile version, colour version, palette hash, map scale, sea level, vertical scale and the right/bottom neighbours' coloured versions).
- Three precision levels (map pixels per vertex): near `1` (default ≤768 blocks), mid `4` (≤2048), far `32`; distances use the real camera eye (spherical). Far step fills the map first, then near tiles refine; a 15% hysteresis band prevents rebuild churn; steps must divide 256.
- Tile borders get a downward skirt (default 16 blocks, both windings) to hide LOD seams; segments next to unloaded data are skipped so the map edge does not grow a "curtain".
- Textures: 1024×1024 RGBA8 atlas pages, 16 slots of 256² per page, NEAREST + clamp, no mipmaps; only the tile's 256² sub-region is uploaded (one reused 256 KB staging buffer). Atlas cap = `gpuTextureBudgetMb × 4` tiles (1024 by default); over budget, the nearest tiles win and the farthest are evicted first.
- Rendering: a dedicated GPU pass with per-tile frustum culling (six AABB planes, no per-frame allocation) and back-face culling; full-bright (the unlit 2D colours are reused).
  - Fabric 26.2: private `D32_FLOAT` depth + **reversed Z** (clear 0.0, `GREATER_THAN_OR_EQUAL`, near 0.05).
  - Fabric 26.1.2: private `D32_FLOAT` + **standard Z** (clear 1.0, `LESS_THAN_OR_EQUAL`).
  - NeoForge 1.21.1: the main target's **24-bit fixed-point depth + standard Z** (clear 1.0 / LEQUAL); near plane 4.0 keeps far-depth precision (the camera never gets closer than 64 blocks).
- Advanced 3D look (`advanced3dRender`, on by default): the terrain re-uses the 2D coloured texture (baked hillshade included) and only adds **per-cell AO** plus **directional side walls with a vertical gradient**. The offscreen pass and its composite quad are gone, so disk and VRAM only ever hold the one shared atlas.
- Voxel terrain (`gpuTerrainVoxel`, on by default, near step only): one flat top per cell plus vertical side walls wherever a neighbour is lower; tops are greedily merged over equal height + equal AO, walls use 西/west 0.92, north 0.90, east 0.74, south 0.76 face factors with a bottom that is another 18% darker. Each tile border emits exactly one skirt and skips it where a wall already covers the edge (no coplanar z-fighting).
- Procedural screen-space sky: the horizon's NDC y is derived from pitch + FOV and drawn as two gradient bands (sky → horizon → below-horizon colour), with no geometry and no depth write. Above 3000 blocks of camera height it fades to a black sky over 1000 blocks and fog turns off.
- Horizontal fog (blocks style only): the end distance is the fixed `fogDistance3d` (10000 blocks by default), the start follows the camera's height above the terrain (about 2× that height, minimum 600) and it fades out near the 85° top-down view. Only the horizontal (XZ) distance counts, so height and pitch never add fog.
- Version adaptation: Fabric 26.1.2's sky and fog only differ in **call shapes** (`RenderPipelines.BLOCK_SNIPPET` instead of bind-group layouts, `CommandEncoder.mapBuffer` instead of `GpuBuffer.map`, and `pass.draw(firstVertex, vertexCount)` has the reverse argument order), all isolated in `BlazeCompat`; the three platforms look the same.
- Render-distance ribbon: translucent yellow triangle strip hugging the terrain at +1.05, 64 segments per side, updated every frame (blended, depth-tested, no depth write).
- 3D interaction: left-drag pans, middle-drag orbits, wheel zoom (interpolated), Space eases the view to the player; ray picks are clipped to the map AABB and validate against empty data; the player arrow floats above the terrain; 3D biome labels have a distance limit, +6 lift, and terrain/control occlusion.
- Camera: entering 3D is a seamless top-down match of the 2D view; exiting plays a 0.22 s rotation back; a short middle click toggles the view while a middle drag orbits (distinguished by press duration and movement). The 3D camera is remembered per world.
- Resource lifecycle: `keep3dTilesIn2d` (default on) keeps the GPU terrain when returning to 2D; closing the map / changing worlds cleans up according to the config; retired buffers are released after 3 frames; the `Q` panel reports `mesh` / `atlas` / `depth` VRAM separately.

## 6. 2D/3D shared biome labels

- 2D and 3D share the same label data (anchor + biome id + width); text is always drawn in screen space and never baked into a texture.
- Builds are keyed by `biomeVersion()`, which only advances when pixels actually change (evicting tiles does not trigger it). 2D gates on `zoom × uiScale`; 3D gates on camera distance.
- Labels skip positions covered by buttons, floating menus, dialogs or info plates (`collectLabelBlockers`).

## 7. Config and per-world state

- 71 options across six tabs: General / Map / Map data / 3D View / Perf / Debug, split into sections inside each tab, each with a description; toggles, dropdowns and sliders (drag or type a value). `drawContours` remains only as the "default contour toggle for new worlds" and is no longer listed on the config screen (the bottom-left button switches it per world).
- Draft mode: `Cancel` discards, `Apply` saves without closing, `Done` saves and closes, `Reset` restores factory defaults.
- Storage: Fabric writes `config/voxymap-client.json` (flat keys); NeoForge writes `config/voxymap-client.toml` via `ModConfigSpec` (groups `ui/map/data/view3d/perf/debug`, plus a hidden `legacy` group holding retired keys).
- The config only sets defaults for **new worlds**; each world's style, overlays (biome borders / render-distance box / contours), map scale, zoom (and 3D camera) live in that world's `state.properties`.
- Version marker: `configVersion` in the Fabric JSON and in the NeoForge TOML's `[meta]` group, with the rule below.
- **Config version rule**: bump `configVersion` only when **every user's config must be reset** (for example a wholesale default change or an old config that conflicts with new logic). Do not bump it for ordinary default tweaks, new options, description edits or section reordering; load missing options with their defaults instead. A bump overwrites every user's saved settings with defaults once and is a destructive operation.

<a id="config-reference"></a>

### Config reference (by page)

| Page / group | Key options | Notes |
| --- | --- | --- |
| General / `ui` | `showCoordinates`, `showHint`, `teleportEnabled`, `defaultZoom`, `rememberZoom`, `arrowStyle` | interaction and UI |
| Map / `map` | `defaultStyle`, `biomeColors`, `adaptiveColorRamp`, `landMaxRamp`, `drawHillshade`, `lightStrength`, `lightAmbient`, `waterBlur*`, `drawWaterDepth`, `autoSeaLevel`, `seaLevelOverride`, `contourDensity`, `minContourArea`, `denoise`, `fillHoles`, `ignorePlantHeight` (default only), `drawContours` (default only) | palette, lighting and terrain processing |
| Data / `data` | `liveTiles`, `mapScale`, `maxLodLevel`, `cacheKeepCount`, `diskCacheMaxMb` | data source, scale and caches |
| 3D View / `view3d` | `fov3d`, `verticalScale3d`, `rotateSens3d`, `panSens3d`, `zoomSens3d`, `remember3dView`, `advanced3dRender`, `advanced3dAo`, `fogDistance3d`, `autoHideContours3d`, `gpuTerrain*`, `gpuTerrainVoxel`, `minDistance3d`, `maxDistance3d`, `playerMarker*`, `biomeLabel3dRange`, `keep3dTilesIn2d` | camera, advanced 3D look, mesh precision, marker and labels |
| Perf / `perf` | `tileResidentCap`, `gpuTextureBudgetMb`, `gpu3dBuildThreads`, `gpu3dBatchBuild`, `skipWorldRender`, `releaseTexturesOnClose` | memory / VRAM / threads |
| Debug / `debug` | `debugEnabled`, `debugRefreshChat`, `debugVoxyEvents`, `debugTileTrace` | debug output and tracing |

Tuning: low FPS → keep `skipWorldRender=true`; blurry distance → coarser `mapScale` or disable the biome overlay; VRAM pressure → lower `gpuTextureBudgetMb`; disk pressure → lower `diskCacheMaxMb`; older machines → fewer `gpu3dBuildThreads` / lower `gpu3dBatchBuild`.

## 8. Multi-version / multi-loader adaptation

- **Fabric 26.2 / 26.1.2 (Stonecutter)**: shared sources. Version differences live in `BlazeCompat.java` (Blaze3D API shape: texture format, index type, topology, RenderPass arguments/clear colour, `submit`, main-target access, pipeline builder), `HudMixin` / `ItemInHandRendererMixin` (26.1.2's `Gui` / `renderHandsWithItems`) and string replacements in `stonecutter.gradle.kts` (`Minecraft.gui.screen()` ↔ `Minecraft.screen`, `gui.hud.getChat()` ↔ `gui.getChat()`).
- **NeoForge 1.21.1**: the 26.2 sources are copied file by file into `mod/src`, with platform differences confined to the compat layer:
  - `compat/GuiGraphicsExtractor` + `Pose2D` + `GuiPipelines` map 26.2's two-phase GUI onto 1.21.1's immediate `GuiGraphics`;
  - `compat/gpu/*` re-implements Blaze3D's `GpuBuffer`/`GpuTexture`/`RenderPass`/`RenderPipeline` on `GlStateManager`/`RenderSystem`/`ShaderInstance`, with four custom core shaders (`voxymap:terrain`, `voxymap:overlay`, `voxymap:terrain3d`, `voxymap:sky`) and one VBO/IBO/VAO per tile;
  - the advanced 3D sky uses an NDC shader that reads no matrices, and the horizontal fog uses `voxymap:terrain3d`'s `FogStart`/`FogEnd`/`FogColor` uniforms — `ShaderInstance.setDefaultUniforms` fills them from `RenderSystem.setShaderFogStart/End/Color`, the 1.21.1 equivalent of 26.2's fog UBO (no UBO means no fence/ring buffer needed);
  - the 3D pass runs in `GameRenderer.render` after the world depth clear and before any GUI batch (1.21.1 has no `GuiRenderer`), so third-party immediate-GUI mods keep their VAO caches intact;
  - `VoxyMapConfig` wraps the 26.2 `Value<T>` facade around NeoForge's TOML so the business code stays unchanged;
  - 1.21.1 gotchas handled: `GuiGraphics.blit`'s untinted path does not enable blending itself, `BufferUploader`'s VAO cache must be invalidated after the pass, and `DynamicTexture`/`GlStateManager.upload` state has to be pinned.
- **Data source**: Fabric reads Voxy directly; NeoForge uses Roxy to bridge the Fabric Voxy build (tested: NeoForge 21.1.265 + Roxy 0.4.1 + Voxy 0.2.16-beta).

## 9. Build and repository layout

```bash
# Fabric (26.2 / 26.1.2, shared sources) — needs JDK 25
cd stonecutter
./gradlew build            # or .\gradlew.bat build
# outputs: versions/26.2-fabric/build/libs/voxy-map-<ver>+26.2+fabric.jar
#          versions/26.1-fabric/build/libs/voxy-map-<ver>+26.1.2+fabric.jar

# NeoForge 1.21.1 — needs JDK 21
cd mod
./gradlew build
# output: mod/build/libs/voxy-map-<ver>+1.21.1+neoforge.jar
```

- `stonecutter/`: Fabric multi-version project (shared sources, per-node `versions/`).
- `mod/`: NeoForge 1.21.1 project (`compat/` is the platform layer; `mixin/GameRendererMixin` implements `skipWorldRender`).
- `mod/tools/`: developer tools (fast javac check, launcher-free instance launch, optional in-game self-test).
- `poc/`: single-file Java tools that read an instance's Voxy storage offline for diagnostics/rendering.

## 10. Known limitations and troubleshooting

- Overworld only; the Nether/End are not mapped.
- Biomes are resolved by registry path, so identically named biomes from different mods may collide (unlikely).
- Fabric 26.1.2 has a rare, not-yet-reproducible NVIDIA driver crash when closing the map with `skipWorldRender` enabled; 26.2 is not affected.
- Fabric **26.1.2 gained the procedural sky and horizontal fog on 2026-11-29** (earlier builds did not have them; the old note is corrected): only the call shapes differ (`BLOCK_SNIPPET` pipeline, `CommandEncoder.mapBuffer`, reversed `pass.draw(firstVertex, vertexCount)`), see the pitfalls document §32.3; the `:26.1-fabric` in-game self-test with a tilted 3D screenshot passes.
- 3D far-depth precision: Fabric uses a private D32F attachment; NeoForge 1.21.1 uses the main target's 24-bit fixed-point depth. If far terrain ever z-fights, raise the near plane or move the pass to a private D32F target.
- Black map edges mean Voxy has no data there yet (most common without SeedGen) — not a rendering error. If the data changed but the map looks old, press **Refresh**.

