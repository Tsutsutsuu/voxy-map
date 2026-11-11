package com.voxymap;

/**
 * Colour palettes for the map. Kept in sync with the offline PoC's six schemes:
 * current, gradient, gmt, nautical, gradient-turbo, gradient-soft.
 *
 * <p>{@link #BLOCKS} is the seventh style: land pixels are coloured by surface material class
 * (see {@link SurfaceClassTable}) times the shared hillshade; water pixels start from the biome's
 * vanilla water colour and apply the {@code current} palette's depth falloff on top (see
 * {@link #depthShadeWater}), so shallow water matches vanilla's hue and deep water still darkens
 * like the current style. It is rendered by a separate branch in {@code MapData} so the six
 * palettes below are untouched.</p>
 */
public final class MapStyles {
    public static final String[] NAMES = {"blocks", "current", "gradient", "gmt", "nautical", "gradient-turbo", "gradient-soft"};
    /** Material/block-colour style id. */
    public static final String BLOCKS = "blocks";

    /** Depth buckets used by {@link #depthShadeWater}; the last bucket is reused deeper down. */
    private static final int WATER_SHADE_DEPTHS = 128;
    /**
     * 8.8 fixed-point channel multipliers of the current water ramp relative to its shallowest
     * colour, packed as {@code r | g<<10 | b<<20}. Computed once from {@link #waterColor}, so the
     * blocks style keeps exactly the current style's depth layers while using vanilla's water hue.
     */
    private static final int[] WATER_SHADE = new int[WATER_SHADE_DEPTHS];

    static {
        int shallow = waterColor("current", 0);
        int sr = (shallow >> 16) & 0xFF;
        int sg = (shallow >> 8) & 0xFF;
        int sb = shallow & 0xFF;
        for (int d = 0; d < WATER_SHADE_DEPTHS; d++) {
            int c = waterColor("current", d);
            int fr = (int) Math.round(((c >> 16) & 0xFF) * 256.0 / sr);
            int fg = (int) Math.round(((c >> 8) & 0xFF) * 256.0 / sg);
            int fb = (int) Math.round((c & 0xFF) * 256.0 / sb);
            WATER_SHADE[d] = Math.min(1023, fr) | (Math.min(1023, fg) << 10) | (Math.min(1023, fb) << 20);
        }
    }

    /** True for the material (block colour) style, which bypasses the height palettes. */
    public static boolean isBlocks(String style) {
        return BLOCKS.equals(style);
    }

    /**
     * Water colour for the "blocks" style: the biome's vanilla water colour is the shallow anchor
     * and the current palette's depth falloff is multiplied on top. Depth 0-3 → vanilla water
     * colour unchanged; 76+ → the current palette's deep-water ratio (≈0.09/0.21/0.45), i.e. the
     * same layering as the {@code current} style but anchored at vanilla's hue.
     */
    public static int depthShadeWater(int biomeWater, int depth) {
        int d = depth < 0 ? 0 : Math.min(WATER_SHADE_DEPTHS - 1, depth);
        int f = WATER_SHADE[d];
        int r = (((biomeWater >> 16) & 0xFF) * (f & 0x3FF)) >> 8;
        int g = (((biomeWater >> 8) & 0xFF) * ((f >>> 10) & 0x3FF)) >> 8;
        int b = ((biomeWater & 0xFF) * ((f >>> 20) & 0x3FF)) >> 8;
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /**
     * Vanilla {@code water_still.png} is a grayscale texture (#B1B1B1) with alpha 180/255, and the
     * biome water colour is multiplied onto it. The palette's WATER factor already applies the
     * gray average, so {@link #compositeWater} only has to composite the translucent surface over
     * an estimated background (the in-game water is alpha blended over the sea floor / fog).
     */
    private static final int WATER_ALPHA = 180;
    /** Estimated shallow sea floor (mean of sand/gravel/clay/stone/dirt top colours). */
    private static final int SEABED_SHALLOW = 0x9A9084;
    /** Deep-water background: water fog / dark sea floor. */
    private static final int SEABED_DEEP = 0x10141F;
    /** Depth over which the background estimate fades from the shallow sea floor to deep fog. */
    private static final int SEABED_DEPTH_SPAN = 32;

    /**
     * Final water pixel for the "blocks" style: {@code biomeWaterTint * water texture average} (the
     * texture average is pre-applied by the palette WATER factor) with the current palette's depth
     * falloff, then alpha-composited over an estimated background because vanilla water is
     * translucent (texture alpha 180/255). The background fades from a sandy shallow-sea-floor
     * tone to dark deep-water fog with depth, which also removes the over-saturation of a pure
     * opaque tint.
     */
    public static int compositeWater(int texturedTint, int depth) {
        int fg = depthShadeWater(texturedTint, depth);
        int t = depth < 0 ? 0 : Math.min(255, depth * 255 / SEABED_DEPTH_SPAN);
        int bg = mix(SEABED_SHALLOW, SEABED_DEEP, t / 255f);
        int r = ((((fg >> 16) & 0xFF) * WATER_ALPHA + ((bg >> 16) & 0xFF) * (255 - WATER_ALPHA)) / 255);
        int g = ((((fg >> 8) & 0xFF) * WATER_ALPHA + ((bg >> 8) & 0xFF) * (255 - WATER_ALPHA)) / 255);
        int b = (((fg & 0xFF) * WATER_ALPHA + (bg & 0xFF) * (255 - WATER_ALPHA)) / 255);
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    private static final int[][] LAND_GRADIENT = {
            {0, 200, 200, 170}, {30, 120, 180, 100}, {100, 90, 150, 80}, {200, 120, 145, 80},
            {350, 160, 140, 100}, {500, 170, 165, 150}, {700, 210, 212, 208}, {820, 250, 252, 255},
    };
    private static final int[][] LAND_TURBO = {
            {0, 60, 120, 180}, {150, 60, 180, 150}, {300, 120, 200, 90}, {500, 220, 220, 80},
            {650, 220, 150, 70}, {780, 180, 100, 80}, {900, 250, 250, 250},
    };
    private static final int[][] LAND_SOFT = {
            {0, 200, 215, 200}, {200, 170, 200, 165}, {400, 190, 200, 160},
            {600, 205, 195, 165}, {800, 220, 215, 205}, {900, 245, 245, 242},
    };
    private static final int[][] WATER_GRADIENT = {
            {0, 120, 190, 210}, {300, 60, 125, 190}, {600, 28, 65, 135}, {1000, 14, 38, 95},
    };
    private static final int[][] WATER_TURBO = {
            {0, 90, 150, 200}, {400, 40, 90, 160}, {1000, 10, 25, 70},
    };
    private static final int[][] WATER_SOFT = {
            {0, 150, 185, 210}, {500, 70, 120, 180}, {1000, 30, 60, 120},
    };
    private MapStyles() {
    }

    public static int landColor(String style, int y, int sea, int lo, int hi, boolean adaptive) {
        if (y < sea) {
            // Below-sea land is just a slightly darker continuation of the coast colour. Deriving
            // it from the actual sea-level colour keeps the hue identical (no magenta/blue slab)
            // and makes the depression barely noticeable, which is what this palette needs.
            int coast = landColor(style, sea, sea, lo, hi, adaptive);
            int depth = sea - y;
            float darken = 0.94f - 0.10f * Math.min(1f, depth / 32f);
            int lowland = darken(coast, darken);
            float blend = Math.min(1f, depth / 4f);
            return mix(coast, lowland, blend);
        }
        int hh = adaptive ? (int) (((long) (y - lo)) * 820L / Math.max(1, hi - lo)) : (y - sea);
        switch (style) {
            case "gmt":
                if (hh <= 5) return rgb(150, 200, 190);
                if (hh <= 20) return rgb(140, 190, 120);
                if (hh <= 50) return rgb(190, 210, 120);
                if (hh <= 90) return rgb(230, 220, 130);
                if (hh <= 150) return rgb(220, 190, 120);
                if (hh <= 220) return rgb(190, 150, 110);
                if (hh <= 320) return rgb(160, 120, 100);
                if (hh <= 450) return rgb(190, 185, 180);
                if (hh <= 600) return rgb(220, 220, 218);
                return rgb(250, 250, 250);
            case "nautical":
                if (hh <= 2) return rgb(245, 240, 225);
                if (hh <= 20) return rgb(235, 232, 215);
                if (hh <= 80) return rgb(210, 218, 200);
                if (hh <= 200) return rgb(180, 195, 175);
                if (hh <= 400) return rgb(150, 165, 150);
                return rgb(235, 235, 235);
            case "gradient":
                return grad(LAND_GRADIENT, hh / 820f);
            case "gradient-turbo":
                return grad(LAND_TURBO, hh / 900f);
            case "gradient-soft":
                return grad(LAND_SOFT, hh / 900f);
            default: // current
                if (hh <= 2) return rgb(210, 200, 160);
                if (hh <= 10) return rgb(125, 175, 95);
                if (hh <= 40) return rgb(100, 160, 78);
                if (hh <= 80) return rgb(84, 143, 70);
                if (hh <= 140) return rgb(110, 150, 72);
                if (hh <= 220) return rgb(148, 140, 80);
                if (hh <= 320) return rgb(158, 132, 90);
                if (hh <= 450) return rgb(166, 156, 132);
                if (hh <= 580) return rgb(192, 192, 186);
                if (hh <= 700) return rgb(220, 225, 224);
                return rgb(250, 252, 255);
        }
    }

    private static int darken(int color, float factor) {
        return rgb(
                clamp((int) (((color >> 16) & 0xFF) * factor)),
                clamp((int) (((color >> 8) & 0xFF) * factor)),
                clamp((int) ((color & 0xFF) * factor)));
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return rgb(
                clamp((int) (ar + (br - ar) * t)),
                clamp((int) (ag + (bg - ag) * t)),
                clamp((int) (ab + (bb - ab) * t)));
    }

    public static int waterColor(String style, int depth) {
        switch (style) {
            case "gmt":
                if (depth <= 5) return rgb(140, 190, 215);
                if (depth <= 15) return rgb(90, 150, 200);
                if (depth <= 35) return rgb(50, 105, 170);
                if (depth <= 70) return rgb(25, 60, 120);
                return rgb(10, 30, 80);
            case "nautical":
                if (depth <= 3) return rgb(235, 243, 250);
                if (depth <= 8) return rgb(215, 230, 245);
                if (depth <= 14) return rgb(192, 214, 236);
                if (depth <= 22) return rgb(168, 198, 228);
                if (depth <= 32) return rgb(145, 182, 220);
                if (depth <= 44) return rgb(122, 165, 210);
                if (depth <= 58) return rgb(100, 148, 198);
                if (depth <= 74) return rgb(82, 132, 186);
                return rgb(66, 116, 172);
            case "gradient":
                return grad(WATER_GRADIENT, depth / 80f);
            case "gradient-turbo":
                return grad(WATER_TURBO, depth / 80f);
            case "gradient-soft":
                return grad(WATER_SOFT, depth / 80f);
            default: // current
                if (depth <= 3) return rgb(150, 205, 222);
                if (depth <= 6) return rgb(130, 195, 215);
                if (depth <= 10) return rgb(110, 182, 210);
                if (depth <= 15) return rgb(92, 168, 205);
                if (depth <= 21) return rgb(74, 150, 198);
                if (depth <= 28) return rgb(60, 132, 190);
                if (depth <= 37) return rgb(48, 114, 178);
                if (depth <= 48) return rgb(38, 96, 163);
                if (depth <= 61) return rgb(30, 78, 145);
                if (depth <= 76) return rgb(22, 60, 125);
                return rgb(14, 44, 100);
        }
    }

    private static int grad(int[][] stops, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int p = Math.round(t * 1000);
        for (int i = 0; i < stops.length - 1; i++) {
            if (p <= stops[i + 1][0]) {
                float f = (p - stops[i][0]) / (float) (stops[i + 1][0] - stops[i][0]);
                return rgb(
                        clamp((int) (stops[i][1] + (stops[i + 1][1] - stops[i][1]) * f)),
                        clamp((int) (stops[i][2] + (stops[i + 1][2] - stops[i][2]) * f)),
                        clamp((int) (stops[i][3] + (stops[i + 1][3] - stops[i][3]) * f)));
            }
        }
        int last = stops.length - 1;
        return rgb(stops[last][1], stops[last][2], stops[last][3]);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}

