# mod/tools — 1.21.1 回移的构建/验证工具

这些脚本只用于开发与验证，不会打进 mod jar。

## check.ps1 — 快速编译检查（并行安全）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/check.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/check.ps1 -Filter MapScreen.java,MapConfigScreen.java
```

- 用 `gradle dumpCompileClasspath` 导出的 `mod/build/compile-classpath.txt` 直接 `javac` 编译整个
  `mod/src/main/java`，**不占 Gradle 项目锁**（多人/多 agent 并行改代码时不会互相等待）。
- 输出只在 stderr 里 grep `error:`；`-Filter` 只显示命中文件名的错误，并报告 filter 之外的错误数。
- 首次使用前（或 gradle 依赖变化后）先跑一次 `cd mod; .\gradlew.bat dumpCompileClasspath`
  （`build.gradle` 里的自定义 task）生成 classpath 文件。

## launch-direct.ps1 — 直接拉起 Axolotl 实例（绕过启动器 GUI）

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/launch-direct.ps1 -PrintOnly   # 只打印/校验命令
pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/launch-direct.ps1 -World 'New World'
pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/launch-direct.ps1 -World 'New World' -ExtraJvmArgs '-Dvoxymap.selftest=true'
```

- 从实例 `version json` 重建 java 命令行：正确处理 `include_in_classpath=false`（如新 ASM）、
  `-p` 模块路径、natives、assets、quickPlay 单机世界；工作目录设为实例目录（部分 mod 用相对路径写 logs/config）。
- 需要 Java 25（实例里的 c2me-opencl 是 class file 69）；默认路径见脚本参数。
- `launch-instance.ps1` 是早期用 `Start-Process` 的版本（在本机会被 CTRL_C 杀掉），仅留作参考。

## selftest/SelfTest.java — 自动游戏内冒烟测试（临时）

`SelfTest` 不进正式构建。要跑验证时把它拷到 `mod/src/main/java/com/voxymap/`，并在
`VoxyMapMod.clientTick` 开头加一行 `SelfTest.tick(mc);`，然后用
`-ExtraJvmArgs '-Dvoxymap.selftest=true'` 启动。它会：等世界 → 开图并强制 2D → 截图 → 切 3D + 倾斜相机 →
截图 → 回 2D → 截图 → 开配置屏 → 截图 → 自动退出；日志打 `[selftest]` 前缀，截图落在实例 `screenshots/`。
验证完记得把 `SelfTest.java` 和那行钩子删掉（发布 jar 里不能有它）。

## 诊断记录：1.21.1 回移中踩过的两个坑

1. **第三方 GUI 批处理 mod 的 VAO 缓存**：3D pass 若在 `MapScreen.render()` 里执行，会打断 1.21.1
   `BufferUploader` 的 immediate-buffer/VAO 缓存（完整 modpack 下 GUI 文字/箭头贴图花屏，只留必需 mod 正常）。
   修复：把 3D pass 与 GPU 上传挪到 `GameRenderer.render` 的世界清深度之后、GUI 批处理之前
   （`GameRendererMixin`，`RenderSystem.clear` 后注入），并在 pass 收尾恢复 clear 值/视口/VAO/纹理单元。
2. **箭头半透明像素发黑**：1.21.1 `GuiGraphics.blit()` 的无 tint 路径不会自己 `enableBlend()`（带 tint 的才会），
   26.2 的 `GUI_TEXTURED` pipeline 自带混合。修复：`compat/GuiGraphicsExtractor.blit()` 里显式
   `RenderSystem.enableBlend(); defaultBlendFunc();`。
