# Voxy Map（voxymap）

## Mod简介

一个Minecraft世界地图Mod

快速读取 Voxy 的 LOD 数据，生成全屏地形图及群系图

**速度很快**

&nbsp;

目前支持 **Minecraft 1.21.1 NeoForge**

其他版本/加载器支持计划中

**（依赖使用 Roxy Mod 翻译层的 Voxy mod，其他第三方 Voxy 移植版本未测试）**

&nbsp;

**配合 Voxy SeedGen Mod 使用最佳：**

Voxy SeedGen Mod : https://modrinth.com/mod/voxy-seedgen

## 功能概览

- 按 N 打开/关闭全屏地图
- 滚轮缩放、拖拽平移、空格对准玩家位置
- 6 种配色：自然地形、渐变、GMT、航海、彩虹渐变、柔和渐变
- 等高线、山体阴影、水深着色、海洋模糊、补洞、可选去噪
- 自适应色带
- 群系边界叠加 + 群系标签
- 视距框、坐标显示、右键菜单（复制坐标 / 传送 / 导出 PNG）
- 地图自动刷新、磁盘缓存（voxymap-cache/）、多人支持
- 关闭地图自动释放显存

**几乎都能配置**

配置文件：实例 `config/voxymap-client.toml`。

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

```powershell
$env:JAVA_HOME='...\jdk-21.0.12.1+1'
$env:GRADLE_OPTS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Xmx3g'
cd mod
.\gradlew.bat build --no-daemon --no-configuration-cache
```

代理已写入 `mod/gradle.properties`（systemProp.https.proxyHost=127.0.0.1 / proxyPort=7897）。

产物：`mod/build/libs/voxy-map-0.1.0.jar`

## 安装

把 jar 复制到：

```
Minecraft游戏目录\mods\voxy-map-0.1.0.jar
```

## POC / 离线工具

`poc/` 为单文件 Java 工具（用实例的 Voxy storage 离线渲染/诊断），编译运行需要：

- rocksdbjni 10.2.1 jar
- zstd-jni 1.5.6-3 jar

例：
```powershell
java -Xmx8G -cp "<rocksdbjni>;<zstd-jni>;out" VoxyMapPoC10 "<storage目录>" out.png 2 63 4 current
```

## 已知限制

- 缓存不随 Voxy 数据变化自动失效（需手动刷新地图）
- 缓存不随存档删除自动移除（需手动清理，但是缓存文件很小）

