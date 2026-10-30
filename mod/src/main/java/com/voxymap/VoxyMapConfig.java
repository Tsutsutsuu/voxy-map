package com.voxymap;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration. Exposed through the NeoForge mods screen Config button.
 */
public final class VoxyMapConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue AUTO_REFRESH;
    public static final ModConfigSpec.IntValue REFRESH_DISTANCE_CHUNKS;
    public static final ModConfigSpec.IntValue INITIAL_REFRESH_DELAY_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_STYLE;
    public static final ModConfigSpec.IntValue MAP_SCALE;
    public static final ModConfigSpec.IntValue MAX_LOD_LEVEL;
    public static final ModConfigSpec.BooleanValue AUTO_SEA_LEVEL;
    public static final ModConfigSpec.IntValue SEA_LEVEL_OVERRIDE;
    public static final ModConfigSpec.BooleanValue ADAPTIVE_COLOR_RAMP;
    public static final ModConfigSpec.BooleanValue MIN_MAX_RAMP;
    public static final ModConfigSpec.BooleanValue WATER_BLUR;
    public static final ModConfigSpec.IntValue WATER_BLUR_RADIUS;
    public static final ModConfigSpec.IntValue WATER_BLUR_PASSES;
    public static final ModConfigSpec.BooleanValue DENOISE;
    public static final ModConfigSpec.BooleanValue FILL_HOLES;
    public static final ModConfigSpec.BooleanValue DRAW_CONTOURS;
    public static final ModConfigSpec.BooleanValue DRAW_HILLSHADE;
    public static final ModConfigSpec.BooleanValue DRAW_WATER_DEPTH;
    public static final ModConfigSpec.BooleanValue SHOW_COORDINATES;
    public static final ModConfigSpec.BooleanValue TELEPORT_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue SKIP_WORLD_RENDER;
    public static final ModConfigSpec.IntValue CACHE_KEEP_COUNT;
    public static final ModConfigSpec.IntValue MIN_CONTOUR_AREA;
    public static final ModConfigSpec.IntValue CONTOUR_DENSITY;
    public static final ModConfigSpec.BooleanValue SHOW_BIOMES;
    public static final ModConfigSpec.ConfigValue<String> ARROW_STYLE;
    public static final ModConfigSpec.BooleanValue SHOW_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue RELEASE_TEXTURES_ON_CLOSE;
    public static final ModConfigSpec.IntValue BIOME_LABEL_MIN_AREA;
    public static final ModConfigSpec.BooleanValue BIOME_LABEL_BACKGROUND;
    public static final ModConfigSpec.DoubleValue DEFAULT_ZOOM;
    public static final ModConfigSpec.BooleanValue SHOW_HINT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("自动刷新 / Automatic refresh").push("refresh");
        AUTO_REFRESH = b
                .comment("玩家移动较远或超过初始延迟后自动重新生成地图 / Automatically regenerate the map when the player moves far away or after the initial delay")
                .translation("voxymap.config.autoRefresh")
                .define("autoRefresh", true);
        REFRESH_DISTANCE_CHUNKS = b
                .comment("玩家移动超过该区块数后刷新 / Refresh once the player moved this many chunks since the last update")
                .translation("voxymap.config.refreshDistanceChunks")
                .defineInRange("refreshDistanceChunks", 128, 16, 1024);
        INITIAL_REFRESH_DELAY_MINUTES = b
                .comment("进入世界多少分钟后刷新一次（0 禁用初始刷新） / Refresh once this many minutes after joining a world (0 disables the initial refresh)")
                .translation("voxymap.config.initialRefreshDelayMinutes")
                .defineInRange("initialRefreshDelayMinutes", 3, 0, 60);
        b.pop();

        b.comment("地图数据 / Map data").push("data");
        MAP_SCALE = b
                .comment("地图比例：1 到 1/8（值 1-8 = 每像素方块数；1 最精细也最慢，8 最粗但最快） / Map scale: 1 to 1/8 (value 1-8 = blocks per pixel; 1 = sharpest and slowest, 8 = coarsest and fastest)")
                .translation("voxymap.config.mapScale")
                .defineInRange("mapScale", 2, 1, 8);
        MAX_LOD_LEVEL = b
                .comment("合并的最高 Voxy LOD 层级（0-4），更低的值会跳过远处的粗数据 / Highest Voxy LOD level to merge (0-4). Lower values skip far coarse data")
                .translation("voxymap.config.maxLodLevel")
                .defineInRange("maxLodLevel", 4, 0, 4);
        CACHE_KEEP_COUNT = b
                .comment("最多保留几个存档缓存（按最近写入排序，多余的自动删除；1 表示只保留当前存档） / How many world caches to keep (oldest are deleted automatically; 1 keeps only the current world)")
                .translation("voxymap.config.cacheKeepCount")
                .defineInRange("cacheKeepCount", 5, 1, 100);
        AUTO_SEA_LEVEL = b
                .comment("从世界读取海平面（自动适配地形模组） / Read the sea level from the world (adapts to terrain mods)")
                .translation("voxymap.config.autoSeaLevel")
                .define("autoSeaLevel", true);
        SEA_LEVEL_OVERRIDE = b
                .comment("关闭自动海平面时使用的海平面 / Sea level used when autoSeaLevel is disabled")
                .translation("voxymap.config.seaLevelOverride")
                .defineInRange("seaLevelOverride", 63, -64, 384);
        b.pop();

        b.comment("Rendering").push("rendering");
        DEFAULT_STYLE = b
                .comment("默认配色：current、gradient、gmt、nautical、gradient-turbo、gradient-soft / Default palette: current, gradient, gmt, nautical, gradient-turbo, gradient-soft")
                .translation("voxymap.config.defaultStyle")
                .define("defaultStyle", "current");
        DRAW_CONTOURS = b
                .comment("在陆地上绘制等高线 / Draw contour lines on land")
                .translation("voxymap.config.drawContours")
                .define("drawContours", true);
        MIN_CONTOUR_AREA = b
                .comment("丢弃小于该像素数的等高线碎片 / Drop contour fragments smaller than this many pixels")
                .translation("voxymap.config.minContourArea")
                .defineInRange("minContourArea", 40, 0, 1000);
        CONTOUR_DENSITY = b
                .comment("陆地高度范围内绘制多少条等高线（越大越密） / How many contour lines span the land height range (higher = denser)")
                .translation("voxymap.config.contourDensity")
                .defineInRange("contourDensity", 12, 1, 24);
        SHOW_BIOMES = b
                .comment("打开地图时显示群系边界叠加层 / Show the biome boundary overlay when opening the map")
                .translation("voxymap.config.showBiomes")
                .define("showBiomes", false);
        ARROW_STYLE = b
                .comment("箭头样式：简洁（clean）或经典（classic） / Arrow marker style: clean or classic")
                .translation("voxymap.config.arrowStyle")
                .define("arrowStyle", "clean");
        SHOW_RENDER_DISTANCE = b
                .comment("Render distance overlay: show the player render distance as a yellow box")
                .translation("voxymap.config.showRenderDistance")
                .define("showRenderDistance", false);
        RELEASE_TEXTURES_ON_CLOSE = b
                .comment("Release map textures when closing the map (frees VRAM)")
                .translation("voxymap.config.releaseTexturesOnClose")
                .define("releaseTexturesOnClose", true);
        BIOME_LABEL_MIN_AREA = b
                .comment("显示群系名所需的最小群系面积（地图像素） / Minimum biome region area (map pixels) needed to show its name")
                .translation("voxymap.config.biomeLabelMinArea")
                .defineInRange("biomeLabelMinArea", 600, 1, 5000);
        BIOME_LABEL_BACKGROUND = b
                .comment("在群系文字标签后面绘制半透明背景 / Draw a semi-transparent background behind biome labels")
                .translation("voxymap.config.biomeLabelBackground")
                .define("biomeLabelBackground", true);
        DRAW_HILLSHADE = b
                .comment("为地形应用光照阴影 / Apply hillshade to the terrain")
                .translation("voxymap.config.drawHillshade")
                .define("drawHillshade", true);
        DRAW_WATER_DEPTH = b
                .comment("按海底深度为水体着色 / Colour water by bathymetry depth")
                .translation("voxymap.config.drawWaterDepth")
                .define("drawWaterDepth", true);
        DEFAULT_ZOOM = b
                .comment("打开地图时的初始缩放 / Initial zoom when opening the map")
                .translation("voxymap.config.defaultZoom")
                .defineInRange("defaultZoom", 0.5, 0.04, 8.0);
        SKIP_WORLD_RENDER = b
                .comment("打开地图时跳过世界渲染以提升帧率（若与其他模组冲突可关闭） / Skip rendering the world while the map is open (better FPS; disable if it conflicts with other mods)")
                .translation("voxymap.config.skipWorldRender")
                .define("skipWorldRender", true);
        ADAPTIVE_COLOR_RAMP = b
                .comment("将陆地色带拉伸到世界的实际高度范围 / Stretch the land colour ramp to the world's actual height range")
                .translation("voxymap.config.adaptiveColorRamp")
                .define("adaptiveColorRamp", true);
        MIN_MAX_RAMP = b
                .comment("只影响陆地：海平线（绿）拉伸到地图最高点（白）；水的颜色不变 / Land only: sea level (green) up to the map's highest point (white); water colours are unchanged")
                .translation("voxymap.config.landMaxRamp")
                .define("landMaxRamp", true);
        WATER_BLUR = b
                .comment("模糊水深以平滑海底噪点 / Blur the water depth to smooth out sea-floor speckles")
                .translation("voxymap.config.waterBlur")
                .define("waterBlur", true);
        WATER_BLUR_RADIUS = b
                .comment("海洋模糊半径（地图像素） / Water blur radius in map pixels")
                .translation("voxymap.config.waterBlurRadius")
                .defineInRange("waterBlurRadius", 8, 1, 16);
        WATER_BLUR_PASSES = b
                .comment("海洋模糊遍数（越多越平滑） / Water blur passes (more = smoother)")
                .translation("voxymap.config.waterBlurPasses")
                .defineInRange("waterBlurPasses", 2, 1, 5);
        DENOISE = b
                .comment("移除陆地上的孤立异常高度 / Remove isolated height outliers on land")
                .translation("voxymap.config.denoise")
                .define("denoise", false);
        FILL_HOLES = b
                .comment("用邻域中位数填充小的内部空洞 / Fill small interior data holes with the local median")
                .translation("voxymap.config.fillHoles")
                .define("fillHoles", true);
        b.pop();

        b.comment("Interface").push("ui");
        SHOW_COORDINATES = b
                .comment("在屏幕底部显示鼠标坐标 / Show cursor coordinates at the bottom of the screen")
                .translation("voxymap.config.showCoordinates")
                .define("showCoordinates", true);
        SHOW_HINT = b
                .comment("在左上角显示操作提示 / Show the control hint in the top-left corner")
                .translation("voxymap.config.showHint")
                .define("showHint", true);
        TELEPORT_ENABLED = b
                .comment("在右键菜单中提供传送选项 / Offer the teleport entry in the right-click menu")
                .translation("voxymap.config.teleportEnabled")
                .define("teleportEnabled", true);
        DEBUG_ENABLED = b
                .comment("调试：在地图界面按 Q 显示/隐藏调试信息 / Debug: press Q in the map screen to toggle the debug panel")
                .translation("voxymap.config.debugEnabled")
                .define("debugEnabled", true);
        b.pop();

        SPEC = b.build();
    }

    private VoxyMapConfig() {
    }
}
