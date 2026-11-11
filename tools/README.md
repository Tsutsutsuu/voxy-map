# 26.2 游戏内验收脚本

这三个脚本自动完成「方块颜色」样式在真实客户端里的验收：构建带脚本测试的 jar、启动游戏、
依次切换 7 套样式并截图、切 2D/3D、测冷启动首开时间、用 TileTrace 统计单瓦片着色耗时。

## 前置条件

- 客户端实例：`D:\Minecraft\AxolotlLauncher\Data\profiles\26.2-Fabric 0.20.1`（含 Voxy、5 个测试世界）。
- Java 25：`D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64`。
- 基线 jar（仅性能对比需要）：`tools/baseline/voxy-map-baseline-harness.jar`；
  缺失时脚本会从 git worktree `C:\Users\Tsutsutsu\Documents\voxy-map-baseline-head`（HEAD 干净代码）
  重新编译。worktree 不存在且没有基线 jar 时，性能对比会自动跳过。

## 一键运行

```powershell
# 完整矩阵：5 个世界 + 7 样式 + 2D/3D + 冷启动性能（约 25-35 分钟，会多次弹游戏窗口）
pwsh -File tools\verify-26.2.ps1

# 只跑样式/2D/3D，不跑性能（约 12 分钟）
pwsh -File tools\verify-26.2.ps1 -SkipPerf

# 只跑部分世界
pwsh -File tools\verify-26.2.ps1 -Worlds w1,w3 -SkipPerf

# 基线 jar 需要重新编译时
pwsh -File tools\verify-26.2.ps1 -RebuildBaseline
```

单世界调试（也会构建测试 jar）：

```powershell
pwsh -File tools\run-selftest.ps1 -Build -World '新的世界' -Styles all -View3d
pwsh -File tools\run-selftest.ps1 -World '新的世界3' -Styles core           # 用默认 feature jar
pwsh -File tools\run-selftest.ps1 -World '新的世界' -Cold -ColdStyle current -Tag w1-cold
```

## 产物

- `tools/selftest-results/<tag>/report.json` — 结果、firstOpenMs、每样式 settle 时间、3D 状态、错误/警告
- `tools/selftest-results/<tag>/*.png` — 2D/3D 截图
- `tools/selftest-results/<tag>/tile-trace.log` — 每个瓦片的 color_work 耗时
- `tools/logs/` — 每次运行的完整控制台日志

性能口径：`blocks` 单瓦片 `color_work` 均值/中位数 ≤ `current` +5%；
冷启动 `firstOpenMs`（feature, current） ≤ 基线（HEAD） +3%。

## 注意

- 脚本会临时把实例 `mods` 里的 `voxy-map-*.jar` 换成测试 jar，结束后自动还原；
  即使上一次运行被强行终止，下次启动也会先自愈（`*.voxymaptest-backup` → 原名）。
- 生产 jar 里**没有**测试代码：脚本测试类在 `tools/selftest`，只有加 `-Pselftest` 构建才会打进
  `voxy-map-0.3.0+26.2+fabric-selftest.jar`；正常构建产物是纯 mod。
- `_crops/`、`scripttest/`、`verify-quick/` 之类是调试中间产物，可随时删除。
