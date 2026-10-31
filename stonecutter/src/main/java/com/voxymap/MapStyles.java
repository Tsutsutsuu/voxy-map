package com.voxymap;

/**
 * Colour palettes for the map. Kept in sync with the offline PoC's six schemes:
 * current, gradient, gmt, nautical, gradient-turbo, gradient-soft.
 */
public final class MapStyles {
    public static final String[] NAMES = {"current", "gradient", "gmt", "nautical", "gradient-turbo", "gradient-soft"};

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
