package com.voxymap;

import java.util.Arrays;
import org.joml.Matrix4f;

/**
 * CPU height-field renderer. Projects the mesh with the camera matrices and rasterises it into an
 * ARGB image plus a depth buffer. Used on 26.2, whose extract-based GUI cannot submit custom 3D
 * geometry, and which already displays the map through a plain dynamic texture.
 */
public final class Map3DSoft {
    // per-frame scratch buffers, reused to avoid allocating tens of MB every frame
    private static float[] sSx;
    private static float[] sSy;
    private static float[] sSinv;
    private static float[] sSwx;
    private static float[] sSwz;
    private static int[] sScol;
    private static int[] sFxaaOut;
    private static int[] sAbgr;

    private Map3DSoft() {
    }

    public static final class Framebuffer {
        public final int width;
        public final int height;
        public final int[] color;
        public final float[] depth;

        public Framebuffer(int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.color = new int[this.width * this.height];
            this.depth = new float[this.width * this.height];
        }

        public void clear(int argb) {
            Arrays.fill(color, argb);
            Arrays.fill(depth, Float.MAX_VALUE);
        }
    }

    public static void render(Map3D.Mesh mesh, Map3D.Camera cam, float fovDegrees,
                              Framebuffer fb, int background, com.voxymap.MapData data,
                              boolean vertexColors, int[] biomeEdges,
                              double rdCenterX, double rdCenterZ, double rdDist, boolean rdEnabled) {
        fb.clear(background);
        float aspect = (float) fb.width / (float) fb.height;
        Matrix4f vp = cam.viewProjection(aspect, fovDegrees);
        // one immutable colour frame for the whole frame: no per-pixel volatile reads and no torn
        // "new pixels + old dimensions" reads
        final com.voxymap.MapData.ColorFrame colors = data.getColorFrame();
        float[] verts = mesh.vertices;
        int[] idx = mesh.indices;
        int vcount = verts.length / 7;
        // scratch arrays are reused across frames (the rasteriser runs on one background thread)
        if (sSx == null || sSx.length < vcount) {
            sSx = new float[vcount];
            sSy = new float[vcount];
            sSinv = new float[vcount];
            sSwx = new float[vcount];
            sSwz = new float[vcount];
            sScol = new int[vcount];
        }
        float[] sx = sSx;
        float[] sy = sSy;
        float[] sinv = sSinv;
        float[] swx = sSwx;
        float[] swz = sSwz;
        int[] scol = sScol;
        // transform every vertex once per frame instead of once per triangle corner
        for (int i = 0; i < vcount; i++) {
            int o = i * 7;
            float x = verts[o];
            float y = verts[o + 1];
            float z = verts[o + 2];
            float cx = vp.m00() * x + vp.m10() * y + vp.m20() * z + vp.m30();
            float cy = vp.m01() * x + vp.m11() * y + vp.m21() * z + vp.m31();
            float cw = vp.m03() * x + vp.m13() * y + vp.m23() * z + vp.m33();
            if (cw <= 0.01f) {
                sinv[i] = -1f;
                continue;
            }
            float inv = 1f / cw;
            sinv[i] = inv;
            sx[i] = (cx * inv * 0.5f + 0.5f) * fb.width;
            sy[i] = (1f - (cy * inv * 0.5f + 0.5f)) * fb.height;
            swx[i] = x;
            swz[i] = z;
            int r = clamp((int) (verts[o + 3] * 255f));
            int g = clamp((int) (verts[o + 4] * 255f));
            int b = clamp((int) (verts[o + 5] * 255f));
            scol[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        int threads = Math.max(1, Math.min(8, Math.min(
                VoxyMapConfig.RENDER_THREADS_3D.get(), Runtime.getRuntime().availableProcessors())));
        if (threads == 1) {
            for (int t = 0; t + 2 < idx.length; t += 3) {
                int a = idx[t];
                int b = idx[t + 1];
                int c = idx[t + 2];
                if (sinv[a] <= 0f || sinv[b] <= 0f || sinv[c] <= 0f) continue;
                // back-face cull: the orbit camera looks down, so faces pointing away are skipped
                float area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
                if (area > 0f) continue;
                triangle(fb, 0, 1, sx[a], sy[a], sinv[a], swx[a], swz[a],
                        sx[b], sy[b], sinv[b], swx[b], swz[b],
                        sx[c], sy[c], sinv[c], swx[c], swz[c], data,
                        scol[a], scol[b], scol[c], vertexColors, colors,
                        biomeEdges, rdCenterX, rdCenterZ, rdDist, rdEnabled);
            }
            return;
        }
        // interleaved rows: worker k owns rows y % threads == k. Two workers can never both own or
        // both miss a row, which is what caused the intermittent horizontal seams at band edges.
        int bands = threads;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(bands);
        for (int band = 0; band < bands; band++) {
            final int fBand = band;
            final int fBands = bands;
            final float[] fsx = sx;
            final float[] fsy = sy;
            final float[] fsinv = sinv;
            final float[] fswx = swx;
            final float[] fswz = swz;
            final int[] fscol = scol;
            final boolean fVertexColors = vertexColors;
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 0; i + 2 < idx.length; i += 3) {
                        int a = idx[i];
                        int b = idx[i + 1];
                        int c = idx[i + 2];
                        if (fsinv[a] <= 0f || fsinv[b] <= 0f || fsinv[c] <= 0f) continue;
                                triangle(fb, fBand, fBands, fsx[a], fsy[a], fsinv[a], fswx[a], fswz[a],
                                fsx[b], fsy[b], fsinv[b], fswx[b], fswz[b],
                                fsx[c], fsy[c], fsinv[c], fswx[c], fswz[c], data,
                                fscol[a], fscol[b], fscol[c], fVertexColors, colors,
                                biomeEdges, rdCenterX, rdCenterZ, rdDist, rdEnabled);
                    }
                } finally {
                    latch.countDown();
                }
            }, "voxymap-3d-band-" + band);
            worker.setDaemon(true);
            worker.start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Alpha-blends an overlay ARGB colour onto an opaque RGB value. */
    private static int blendOverlay(int rgb, int e) {
        int ea = (e >>> 24) & 0xFF;
        if (ea == 0) return rgb;
        int er = (e >> 16) & 0xFF;
        int eg = (e >> 8) & 0xFF;
        int eb = e & 0xFF;
        int rr = (rgb >> 16) & 0xFF;
        int gg = (rgb >> 8) & 0xFF;
        int bb = rgb & 0xFF;
        rr = (rr * (255 - ea) + er * ea) / 255;
        gg = (gg * (255 - ea) + eg * ea) / 255;
        bb = (bb * (255 - ea) + eb * ea) / 255;
        return (rr << 16) | (gg << 8) | bb;
    }

    private static void triangle(Framebuffer fb, int rowPhase, int rowStride,
                                 float x0, float y0, float i0, float wx0, float wz0,
                                 float x1, float y1, float i1, float wx1, float wz1,
                                 float x2, float y2, float i2, float wx2, float wz2,
                                 com.voxymap.MapData data, int c0, int c1, int c2, boolean vertexColors,
                                 com.voxymap.MapData.ColorFrame colors,
                                 int[] biomeEdges,
                                 double rdCenterX, double rdCenterZ, double rdDist, boolean rdEnabled) {
        // frustum cull: reject triangles entirely outside the screen before computing bounds
        float triMinX = Math.min(x0, Math.min(x1, x2));
        float triMaxX = Math.max(x0, Math.max(x1, x2));
        float triMinY = Math.min(y0, Math.min(y1, y2));
        float triMaxY = Math.max(y0, Math.max(y1, y2));
        if (triMaxX < 0f || triMinX > fb.width || triMaxY < 0f || triMinY > fb.height) return;
        int minX = clamp((int) Math.floor(triMinX), 0, fb.width - 1);
        int maxX = clamp((int) Math.ceil(triMaxX), 0, fb.width - 1);
        int minY = clamp((int) Math.floor(triMinY), 0, fb.height - 1);
        int maxY = clamp((int) Math.ceil(triMaxY), 0, fb.height - 1);
        if (minX > maxX || minY > maxY) return;
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        float invArea = 1f / area;
        int mapW = data.getRenderWidthPx();
        int mapH = data.getRenderHeightPx();
        if (mapW <= 0 || mapH <= 0) return;
        int pb = data.getPixelBlocks();
        long minWX = data.getRenderMinWX();
        long minWZ = data.getRenderMinWZ();
        for (int y = minY; y <= maxY; y++) {
            if (rowStride > 1 && (y - rowPhase) % rowStride != 0) continue;
            float cy = y + 0.5f;
            int row = y * fb.width;
            for (int x = minX; x <= maxX; x++) {
                float cx = x + 0.5f;
                float w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) * invArea;
                if (w0 < -1e-4f) continue;
                float w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) * invArea;
                if (w1 < -1e-4f) continue;
                float w2 = 1f - w0 - w1;
                if (w2 < -1e-4f) continue;
                float invW = w0 * i0 + w1 * i1 + w2 * i2;
                if (invW <= 1e-6f) continue;
                float depth = 1f / invW;
                int di = row + x;
                if (depth >= fb.depth[di]) continue;
                fb.depth[di] = depth;
                // perspective-correct world position at this pixel, then sample the map colour there
                float wx = (w0 * wx0 * i0 + w1 * wx1 * i1 + w2 * wx2 * i2) / invW;
                float wz = (w0 * wz0 * i0 + w1 * wz1 * i1 + w2 * wz2 * i2) / invW;
                int mx = (int) ((wx - minWX) / pb);
                int mz = (int) ((wz - minWZ) / pb);
                if (mx < 0) mx = 0;
                else if (mx >= mapW) mx = mapW - 1;
                if (mz < 0) mz = 0;
                else if (mz >= mapH) mz = mapH - 1;
                if (vertexColors) {
                    // LOD debug view: interpolate the per-vertex tint instead of sampling the map
                    int r = (int) (w0 * ((c0 >> 16) & 0xFF) + w1 * ((c1 >> 16) & 0xFF) + w2 * ((c2 >> 16) & 0xFF));
                    int g = (int) (w0 * ((c0 >> 8) & 0xFF) + w1 * ((c1 >> 8) & 0xFF) + w2 * ((c2 >> 8) & 0xFF));
                    int b = (int) (w0 * (c0 & 0xFF) + w1 * (c1 & 0xFF) + w2 * (c2 & 0xFF));
                    fb.color[di] = 0xFF000000 | (r << 16) | (g << 8) | b;
                } else {
                    int rgb = 0;
                    if (colors != null && !data.isTile2DMode()) {
                        int cw = colors.width;
                        int ch = colors.height;
                        int ccol = mx < cw ? mx : cw - 1;
                        int crow = mz < ch ? mz : ch - 1;
                        if (ccol >= 0 && crow >= 0) rgb = colors.rgb[crow * cw + ccol];
                    } else if (data.isTile2DMode()) {
                        rgb = data.getRgbAtWorld((int) wx, (int) wz);
                    }
                    if (!data.isTile2DMode() && biomeEdges != null && mx + mz * mapW < biomeEdges.length) {
                        rgb = blendOverlay(rgb, biomeEdges[mx + mz * mapW]);
                    }
                    if (rdEnabled) {
                        // analytic render-distance box in world space: only the four sides of the
                        // square are painted (the perpendicular coordinate is clamped to the box),
                        // so this can never turn into a "#" cross
                        double band = pb * 0.5;
                        double dx = wx - rdCenterX;
                        double dz = wz - rdCenterZ;
                        boolean onVertical = Math.abs(Math.abs(dx) - rdDist) <= band
                                && Math.abs(dz) <= rdDist + band;
                        boolean onHorizontal = Math.abs(Math.abs(dz) - rdDist) <= band
                                && Math.abs(dx) <= rdDist + band;
                        if (onVertical || onHorizontal) {
                            rgb = blendOverlay(rgb, 0xE0FFFF00);
                        }
                    }
                    fb.color[di] = 0xFF000000 | rgb;
                }
            }
        }
    }

    /**
     * Converts the ARGB framebuffer to the byte layout NativeImage expects (ABGR ints, RGBA bytes in
     * little-endian memory). Intended for a background thread: the result can then be written to
     * {@code NativeImage.getPixelBytes()} with one bulk put on the main thread instead of millions of
     * setPixelABGR calls.
     */
    public static int[] toAbgr(Framebuffer fb) {
        int n = fb.width * fb.height;
        if (sAbgr == null || sAbgr.length < n) sAbgr = new int[n];
        int[] src = fb.color;
        int[] dst = sAbgr;
        for (int i = 0; i < n; i++) {
            int c = src[i];
            int a = (c >>> 24) & 0xFF;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            dst[i] = (a << 24) | (b << 16) | (g << 8) | r;
        }
        return dst;
    }

    /**
     * Cheap FXAA-style edge smoothing: only pixels next to a strong luma edge are blended with their
     * neighbours along the dominant gradient, so flat areas stay sharp.
     */
    public static void applyFxaa(Framebuffer fb) {
        int w = fb.width;
        int h = fb.height;
        int[] src = fb.color;
        if (sFxaaOut == null || sFxaaOut.length < src.length) {
            sFxaaOut = new int[src.length];
        }
        int[] out = sFxaaOut;
        System.arraycopy(src, 0, out, 0, src.length);
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        for (int k = 0; k < threads; k++) {
            final int phase = k;
            Thread th = new Thread(() -> {
                try {
                    for (int y = 1 + phase; y < h - 1; y += threads) {
                        int row = y * w;
                        for (int x = 1; x < w - 1; x++) {
                            int i = row + x;
                            int lc = luma(src[i]);
                            int ln = luma(src[i - 1]);
                            int ls = luma(src[i + 1]);
                            int lu = luma(src[i - w]);
                            int ld = luma(src[i + w]);
                            int max = Math.max(Math.max(ln, ls), Math.max(lu, ld));
                            int min = Math.min(Math.min(ln, ls), Math.min(lu, ld));
                            if (max - min < 40) {
                                continue;
                            }
                            int hn = Math.abs(ln - ls);
                            int vn = Math.abs(lu - ld);
                            if (hn >= vn) {
                                // blend horizontally
                                out[i] = blend(src[i], src[i - 1], src[i + 1]);
                            } else {
                                out[i] = blend(src[i], src[i - w], src[i + w]);
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }, "voxymap-fxaa-" + k);
            th.setDaemon(true);
            th.start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.arraycopy(out, 0, src, 0, src.length);
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
    }

    private static int blend(int mid, int a, int b) {
        int r = ((mid >> 16) & 0xFF) * 2 + ((a >> 16) & 0xFF) + ((b >> 16) & 0xFF);
        int g = ((mid >> 8) & 0xFF) * 2 + ((a >> 8) & 0xFF) + ((b >> 8) & 0xFF);
        int bl = (mid & 0xFF) * 2 + (a & 0xFF) + (b & 0xFF);
        return 0xFF000000 | ((r / 4) << 16) | ((g / 4) << 8) | (bl / 4);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
