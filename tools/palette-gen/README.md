# palette-gen — 方块颜色 200 色生成器

生成 `blocks` 地图样式使用的 200 色色表（现有 58 色保留 + 142 新色）以及全部方块的
「方块 → 颜色」映射。

## 用法

```powershell
# 预览统计，不写文件
python tools/palette-gen/generate_palette.py --dry-run

# 正式生成：写入 stonecutter/ 与 mod/ 的 surface_classes.json，
# 并在 doc/review/surface-census/ 生成审阅表、色卡和报告
python tools/palette-gen/generate_palette.py
python tools/palette-gen/generate_palette.py --jar "D:\...\26.2-0.20.1.jar"
```

依赖：Python + numpy + Pillow（DSH 内置 Python 运行时自带），26.2 客户端 jar。

## 数据来源

| 输入 | 用途 |
|---|---|
| `doc/review/surface-census/full-block-audit.csv` | 26.2 全部 1198 个方块名 + 顶面贴图名 |
| `doc/review/surface-census/surface-review.csv` | 5 个测试世界地表频率（聚类权重） |
| 26.2 客户端 jar 内 `assets/minecraft/textures/block/*.png` | 每个方块的顶面贴图平均色 |
| `doc/review/mc26.2-src/.../BlockColors.java` | 群系 tint / 固定 tint 名单（脚本里已转写） |

映射规则：

- 普通方块：顶面贴图 alpha 加权平均色 → 在 196 个静态颜色里取最近；
- 固定 tint（云杉叶/白桦叶/睡莲等）：贴图平均色 × 原版 tint 常量；
- 群系 tint（草/树叶/水等）：GRASS / FOLIAGE / WATER 特殊类；**枯叶堆（leaf_litter）用独立的 DRY_FOLIAGE 干树叶 tint**（原版 `BlockTintSources.dryFoliage()`，不是普通树叶色）；
- 透明/薄片方块（玻璃、玻璃板、铁轨、火把、花…）：同样取色，**不会落到 FALLBACK**；
- 真正无材质/不可能作为顶层的方块（空气、屏障、光源、结构方块）→ FALLBACK；
- 岩浆固定用旧 LAVA 色；`snowy` 覆盖沿用旧配置。

## 输出

| 文件 | 内容 |
|---|---|
| `stonecutter/src/main/resources/assets/voxymap/surface_classes.json` | v7 色表（200 槽位 + ~1100 条 exact） |
| `mod/src/main/resources/assets/voxymap/surface_classes.json` | 同上（NeoForge 副本） |
| `doc/review/surface-census/palette200-classes.csv` | 每个颜色：hex、方块数、示例（人工校正用） |
| `doc/review/surface-census/palette200-review.csv` | 每个方块：新颜色、dE、同色成员（人工校正用） |
| `doc/review/surface-census/palette200-report.txt` | 误差/覆盖率/旧色使用情况 |
| `doc/review/surface-census/palette200-swatch.png` | 200 色色卡 |

## 人工校正流程

1. 先看 `palette200-review.xlsx`（单元格已按真实颜色填充）：
   - `色表200`：每个颜色一行，D 列是颜色填充；
   - `方块映射`：每个方块一行，E 列旧颜色、H 列新颜色都按颜色填充，带中文名/贴图名/dE/是否改色；
   - `dE大于5`：自动映射误差较大的方块，优先处理。
2. 或打开 `palette200-review.html`（交互版）：
   - 「色表」页每个颜色都有取色器，改完实时更新「方块映射」页；
   - 「待校正 dE>5」页可快速过滤；
   - 「导出」页生成 `palette-overrides.json`（类名 → 新 hex），贴回给我即可批量应用。
3. 重新运行生成器（会保持 200 色数量），再跑 `PaletteAudit` / 构建验证。

> 生成审阅表：`python tools/palette-gen/make_review.py`
>
> 已知第一版待校正项：花/作物（罂粟、蒲公英、郁金香等）的顶面贴图是十字 sprite，
> 平均色会混入绿色茎/叶，颜色偏绿；后续可改成「只统计花色像素」或直接 overrides。

## 相关运行时版本号

| 位置 | 版本 |
|---|---|
| `surface_classes.json` | `version: 8` |
| `LiveTileStore.CACHE_VERSION` | 15（像素槽位映射变化） |
| `MapData.CACHE_VERSION`（快照） | 13 |
| `MapData.COLOR_CACHE_VERSION` | 16 |

每次重新生成色表都要同步升这三个运行时缓存版本，否则旧缓存会继续用旧映射。

