# 发布前自动化检查（Fabric 26.2 / 26.1.2）

发版前的"快速体检"：一条命令完成**构建 → 装 harness → 跑真实客户端场景 → 按阈值判 PASS/FAIL**。
1.21.1（NeoForge）不在覆盖范围内，按仓库惯例单独手动回移验证。

## 一键运行

```powershell
# 两个版本都跑（约 18–22 分钟，会先后弹 8 次游戏窗口）
pwsh -File tools\verify-release.ps1

# 只跑一个版本
pwsh -File tools\verify-release.ps1 -Versions 26.1.2

# 复用已构建的 harness jar，跳过 gradle
pwsh -File tools\verify-release.ps1 -SkipBuild

# 加跑低缩放冷开（zoom 0.04，576 可视瓦片）
pwsh -File tools\verify-release.ps1 -Full

# 跳过样式/3D（只查性能与灰块）
pwsh -File tools\verify-release.ps1 -SkipFunctional

# 保留测试世界（默认跑完删除 rc-*-cold / rc-*-grey）
pwsh -File tools\verify-release.ps1 -KeepWorlds
```

退出码：`0` = 全部 PASS，`1` = 有失败（CI/脚本可直接用）。

## 场景矩阵与判据

| 场景 | 做什么 | 判据 |
|---|---|---|
| `cold` | 新建世界（固定种子），出生 2.5 s 后开图，probe 25 s | 报告 PASS、无 errors、首帧 ≤ 15 s |
| `warm` | 同一世界热缓存重开，probe 15 s | 报告 PASS、首帧 ≤ 10 s、`cacheH > 0` |
| `functional` | 同一世界跑完全部 7 套样式 + 2D→3D 一致性 | 7 套样式都测到、`missingStyles` 为空、3D entered+parity |
| `grey` | 新建世界 + SeedGen 生成中，zoom 0.08，开图 2 s，probe 40 s；**关阴影/等高线 + FALLBACK 涂品红**，截图 20 s/40 s | 最后一张截图品红像素 = 0、可见 FALLBACK 瓦片 `fbVis=0`、允许单帧瞬时 ≤500 px |
| `lowzoom`（`-Full`） | 热世界冷缓存重开，zoom 0.04 | 报告 PASS、首帧 ≤ 20 s |

**灰块判据为什么不用"灰色像素"**：调色板里 STONE `#7E7E7E`、ANDESITE `#888889`、GRAVEL `#847F7F`、
DIORITE `#BDBCBD` 本身就是灰的，hillshade/等高线还会改亮度。所以用
`-Dvoxymap.debugFallback=true` 把 FALLBACK（slot 0）涂成品红 `#FF00FF`，并 `-NoShading` 关掉阴影/等高线，
只数品红；再用解码期计数 `fbVis/fbTiles` 兜底。详见
[doc/灰色群系bug-定位与修复-2026-11-27.md](../doc/灰色群系bug-定位与修复-2026-11-27.md)。

## 产物

```
tools/release-checks/<yyyyMMdd-HHmmss>/
  summary.md            # 人读：每个场景 PASS/FAIL + 关键指标
  summary.json          # 机读：同内容 + 每项检查明细
  build-<version>.log   # gradle 构建日志
  console-<version>-<scenario>.log   # 每次 run-selftest 的完整输出
tools/selftest-results/<tag>/        # 每场景明细：report.json、截图、tile-trace.log
```

## 单场景调试（run-selftest.ps1）

`run-selftest.ps1` 已支持 `-Version 26.2|26.1.2`（自动选实例、版本 id、gradle 工程），其余参数不变：

```powershell
# 构建该版本 harness
pwsh -File tools\run-selftest.ps1 -Version 26.1.2 -Build -BuildOnly

# 26.1.2：新建世界、开图 +2 s、probe 30 s
pwsh -File tools\run-selftest.ps1 -Version 26.1.2 -CreateWorld 'myworld' -OpenDelayMs 2000 -ProbeMs 30000 `
     -Tag 2612-cold -TimeoutSec 300 -DeleteWorld

# 26.2：样式/3D 功能回归
pwsh -File tools\run-selftest.ps1 -Version 26.2 -World '新的世界B' -Styles all -View3d

# 灰块调试图（多点截图 + 品红）
pwsh -File tools\run-selftest.ps1 -Version 26.2 -CreateWorld 'grey' -OpenDelayMs 2000 -ProbeMs 40000 `
     -Zoom 0.08 -NoShading -DebugFallback -ShotAtMs '20000,40000' -Tag grey-check

# 复现修复前行为（A/B）
pwsh -File tools\run-selftest.ps1 -Version 26.2 -CreateWorld 'grey' -OpenDelayMs 2000 -ProbeMs 40000 `
     -Zoom 0.08 -NoShading -DebugFallback -DisableFallbackFix -ShotAtMs '20000,40000' -Tag grey-before
```

harness jar 按版本命名：`tools/feature/voxy-map-feature-harness-26.2.jar` /
`...-26.1.2.jar`（旧的无版本名文件仅作兜底）。生产 jar 里**没有**测试代码，harness 只在
`-Pselftest` 构建时打进 `voxy-map-*-selftest.jar`。

## 辅助工具

- `tools/analyze-grey.py` — 截图品红/FALLBACK 计数：`python tools/analyze-grey.py <png...> [-m] [--json]`
- `tools/analyze-tile-trace.py` — `tile-trace.log` 各阶段耗时/丢弃率/每瓦片重复
- `tools/verify-26.2.ps1` — 旧的"5 个世界 + 7 样式 + 性能对比"完整矩阵（更慢，发版前可选做）

## 前置条件

- 实例：`D:\Minecraft\AxolotlLauncher\Data\profiles\26.2-Fabric 0.20.1`（Voxy + SeedGen + Xaero）
  与 `...\26.1.2-Fabric 0.20.1`（Voxy 0.2.18 + SeedGen 26.1.2）。
- Java 25：`D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64`。
- Python（仅 grey 场景判图）：PATH 里的 `python`，缺失时回退 DSH 自带解释器。

## 注意

- 每次运行会把实例 `mods` 里的 `voxy-map-*.jar` 换成测试 jar，结束（或下次启动自愈）时还原；
  被强杀后留下的 `*.voxymaptest-backup` 会在下次运行开头自动修复。
- `grey` / `cold` 场景的测试世界默认跑完即删（连同该世界的 `voxymap-cache/<hash>`）。
- 同一实例同一时间只能跑一个场景（脚本顺序执行）；跑的时候不要手动开游戏。
- 26.1.2 的日志初始化行由 Stonecutter 条件生成（26.2 打印 26.2、26.1.2 打印 26.1.2）。
