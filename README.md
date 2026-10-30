# Voxy Map（voxymap）

Minecraft 1.21.1 NeoForge 客户端模组：读取 Voxy 的 LOD 数据，生成全屏地形图。

## 目录结构

```
voxy-map/
├── mod/                  Gradle 工程（源码 + 构建脚本）
│   ├── src/main/java/com/voxymap/   模组源码
│   ├── src/main/resources/          资源（lang、纹理）
│   ├── build.gradle / gradle.properties / settings.gradle
│   ├── gradle/ gradlew gradlew.bat  构建包装器
│   └── libs/            rocksdbjni 编译依赖（compileOnly，运行时不打包）
└── poc/                 离线验证工具（Java 单文件，POC 与数据诊断）
```

## 构建

依赖 JDK 21：`C:\Users\Tsutsutsu\.jdks\jdk-21.0.12.1+1`

```powershell
$env:JAVA_HOME='C:\Users\Tsutsutsu\.jdks\jdk-21.0.12.1+1'
$env:GRADLE_OPTS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Xmx3g'
cd mod
.\gradlew.bat build --no-daemon --no-configuration-cache
```

代理已写入 `mod/gradle.properties`（systemProp.https.proxyHost=127.0.0.1 / proxyPort=7897）。

产物：`mod/build/libs/voxy-map-0.1.0.jar`

## 安装

把 jar 复制到：

```
D:\Minecraft\AxolotlLauncher\Data\profiles\1.21.1-NeoForge 21.1.265\mods\voxy-map-0.1.0.jar
```

## 功能概览

- 按 N 打开/关闭全屏地图（按键可在「选项 → 控制 → 按键绑定 → Voxy Map」修改）
- 滚轮缩放、拖拽平移、空格对准玩家
- 6 种配色：自然地形、渐变、GMT、航海、彩虹渐变、柔和渐变
- 等高线、山体阴影、水深着色、海洋模糊、补洞、可选去噪
- 自适应色带（可切换"陆地拉伸到最高点"模式）
- 群系边界叠加（蓝色虚线）+ 群系标签（字号两段式，可加半透明底）
- 视距框（黄色）、坐标显示、右键菜单（复制坐标 / 传送 / 导出 PNG）
- 自动刷新、磁盘缓存（voxymap-cache/）、多人支持
- 关闭地图释放纹理（约 560MB）

## 配置

配置文件：实例 `config/voxymap-client.toml`（默认值 = 当前使用配置）。

## POC / 离线工具

`poc/` 为单文件 Java 工具（用实例的 Voxy storage 离线渲染/诊断），编译运行需要：

- rocksdbjni 10.2.1 jar
- zstd-jni 1.5.6-3 jar

例：
```powershell
java -Xmx8G -cp "<rocksdbjni>;<zstd-jni>;out" VoxyMapPoC10 "<storage目录>" out.png 2 63 4 current
```

## 已知限制

- 颜色调色板为 6 套预定义
- 群系标签在 GUI 缩放 ≥4 且缩小地图时为半字号
- 缓存不随 Voxy 数据变化自动失效（需手动刷新地图）
