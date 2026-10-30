import com.github.luben.zstd.ZstdInputStream;
import org.rocksdb.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Voxy LOD -> professional-style relief map, v9.
 *
 * Adds bathymetry: per column it finds the water surface (highest non-air voxel)
 * and, when that voxel is a fluid, the sea floor (first non-fluid below it).
 * Water is coloured by depth (isobaths every 20 blocks), land by elevation
 * (hypsometric ramp, contour lines every 50 blocks), with hillshade.
 */
public class VoxyMapPoC9 {
    static final int VOL = 32 * 32 * 32;
    static final int EMPTY = 0; // sentinel: packed 0 means empty (level 0 != 0 only when data exists; use -1 instead)
    static final long LONG_EMPTY = Long.MIN_VALUE;

    public static void main(String[] args) throws Exception {
        String path = args[0];
        String out = args[1];
        int pixelBlocks = Integer.parseInt(args[2]);
        int seaLevel = args.length > 3 ? Integer.parseInt(args[3]) : 63;
        int maxLevel = 4;

        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("world_sections".getBytes()),
                new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), path, cfds, handles);

        long tA = System.nanoTime();
        // water block ids from id_mappings (gzip NBT, Name = minecraft:water)
        Set<Long> waterIds = loadWaterIds(db, handles.get(2));
        long tB = System.nanoTime();
        System.out.println("water block ids: " + waterIds + " (" + (tB - tA) / 1_000_000 + " ms)");
        long tC = 0;

        long minWX = Long.MAX_VALUE, maxWX = Long.MIN_VALUE;
        long minWZ = Long.MAX_VALUE, maxWZ = Long.MIN_VALUE;
        int keys = 0;
        try (RocksIterator it = db.newIterator(handles.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                keys++;
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int) ((key >>> 60) & 0xF);
                    if (lvl <= maxLevel) {
                        int scale = 1 << lvl;
                        int sx = (int) ((key << 36) >> 40);
                        int sz = (int) ((key << 12) >> 40);
                        long x0 = (long) sx * 32 * scale;
                        long z0 = (long) sz * 32 * scale;
                        minWX = Math.min(minWX, x0);
                        maxWX = Math.max(maxWX, x0 + 32L * scale);
                        minWZ = Math.min(minWZ, z0);
                        maxWZ = Math.max(maxWZ, z0 + 32L * scale);
                    }
                }
                it.next();
            }
        }
        if (keys == 0) { System.out.println("no keys"); return; }
        tC = System.nanoTime();
        System.out.println("range-scan=" + (tC - tB) / 1_000_000 + " ms");
        int w = (int) ((maxWX - minWX + pixelBlocks - 1) / pixelBlocks);
        int h = (int) ((maxWZ - minWZ + pixelBlocks - 1) / pixelBlocks);
        System.out.println("keys=" + keys + " bounds=[" + minWX + ".." + maxWX + "]x[" + minWZ + ".." + maxWZ + "] px=" + w + "x" + h);
        long[] pixels = new long[w * h];
        Arrays.fill(pixels, LONG_EMPTY);

        int[] perLevel = new int[16];
        try (RocksIterator it = db.newIterator(handles.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int) ((key >>> 60) & 0xF);
                    if (lvl <= maxLevel) {
                        byte[] val = it.value();
                        if (val != null && val.length > 16) {
                            byte[] data = decompress(val);
                            if (data != null && data.length >= 16 + VOL * 2) {
                                perLevel[lvl]++;
                                parseSection(data, lvl, key, pixels, w, h, pixelBlocks, minWX, minWZ, waterIds);
                            }
                        }
                    }
                }
                it.next();
            }
        } finally {
            for (ColumnFamilyHandle handle : handles) handle.close();
            db.close();
        }
        long tD = System.nanoTime();
        System.out.println("range-scan+parse=" + (tD - tC) / 1_000_000 + " ms");

        int n = 0;
        for (long p : pixels) if (p != LONG_EMPTY) n++;
        StringBuilder sb = new StringBuilder("sections:");
        for (int l = 0; l <= maxLevel; l++) sb.append(" L").append(l).append("=").append(perLevel[l]);
        sb.append(" pixels=").append(n).append("/").append(w * h);
        System.out.println(sb);

        long tE = System.nanoTime();
        BufferedImage img = render(pixels, w, h, seaLevel, pixelBlocks);
        long tF = System.nanoTime();
        ImageIO.write(img, "png", new File(out));
        long tG = System.nanoTime();
        System.out.println("render=" + (tF - tE) / 1_000_000 + " ms png=" + (tG - tF) / 1_000_000 + " ms");
        System.out.println("written " + out);

        BufferedImage small = new BufferedImage(w / 4, h / 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < small.getHeight(); y++)
            for (int x = 0; x < small.getWidth(); x++)
                small.setRGB(x, y, img.getRGB(x * 4, y * 4));
        String preview = out.replace(".png", "-preview.png");
        ImageIO.write(small, "png", new File(preview));
        System.out.println("written " + preview);
    }

    static byte[] decompress(byte[] in) throws Exception {
        try (ZstdInputStream z = new ZstdInputStream(new ByteArrayInputStream(in))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            z.transferTo(bos);
            return bos.toByteArray();
        }
    }

    static Set<Long> loadWaterIds(RocksDB db, ColumnFamilyHandle cf) throws Exception {
        Set<Long> out = new HashSet<>();
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] k = it.key();
                byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    String text = unzipText(v);
                    if (text != null && text.contains("minecraft:water") && !text.contains("cauldron")) {
                        long be = ((long) (k[0] & 0xFF) << 24) | ((long) (k[1] & 0xFF) << 16)
                                | ((long) (k[2] & 0xFF) << 8) | (k[3] & 0xFF);
                        out.add(be & ((1L << 30) - 1));
                    }
                }
                it.next();
            }
        }
        return out;
    }

    static String unzipText(byte[] v) {
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(v))) {
            byte[] out = g.readAllBytes();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                char c = (char) (b & 0xFF);
                sb.append(c >= 32 && c < 127 ? c : (c == 0 ? ' ' : '.'));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static void parseSection(byte[] d, int lvl, long key, long[] pixels, int w, int h, int pixelBlocks,
                             long minWX, long minWZ, Set<Long> waterIds) {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        bb.getLong();
        long meta = bb.getLong();
        int lutSize = (int) (meta & 0xFFFF);
        int base = 16;
        int lutBase = base + VOL * 2;
        if (lutSize <= 0 || lutSize > (1 << 16) || lutBase + lutSize * 8L > d.length) return;

        int sx = (int) ((key << 36) >> 40);
        int sy = (int) ((key << 4) >> 56);
        int sz = (int) ((key << 12) >> 40);
        long airMask = ((1L << 20) - 1) << 27;

        // per column scan from the top down: water surface = first non-air voxel;
        // if it is fluid, sea floor = first non-fluid voxel below it
        int[] topY = new int[1024];
        int[] floorY = new int[1024];
        boolean[] topFluid = new boolean[1024];
        boolean[] filled = new boolean[1024];
        Arrays.fill(topY, -1);
        Arrays.fill(floorY, -1);
        for (int c = 0; c < 1024; c++) {
            int x = c & 31;
            int z = c >> 5;
            for (int y = 31; y >= 0; y--) {
                int i = (y << 10) | (z << 5) | x;
                int lutIdx = Short.toUnsignedInt(bb.getShort(base + i * 2));
                if (lutIdx >= lutSize) continue;
                long voxel = bb.getLong(lutBase + lutIdx * 8);
                if ((voxel & airMask) == 0) continue;
                if (topY[c] < 0) {
                    topY[c] = y;
                    topFluid[c] = isWater(voxel, waterIds);
                    if (!topFluid[c]) floorY[c] = y;
                } else if (topFluid[c] && floorY[c] < 0 && !isWater(voxel, waterIds)) {
                    floorY[c] = y;
                }
            }
            filled[c] = topY[c] >= 0;
            if (filled[c] && floorY[c] < 0) floorY[c] = topY[c];
        }
        int scale = 1 << lvl;
        for (int c = 0; c < 1024; c++) {
            if (!filled[c]) continue;
            int localX = c & 31;
            int localZ = c >> 5;
            long wx0 = (long) (sx * 32 + localX) * scale;
            long wz0 = (long) (sz * 32 + localZ) * scale;
            int topWorld = (sy * 32 + topY[c]) * scale + scale - 1;
            int floorWorld = (sy * 32 + floorY[c]) * scale + scale - 1;
            if (topFluid[c] && floorY[c] == -2) floorWorld = topWorld; // fluid all the way down: no floor info
            int px0 = (int) ((wx0 - minWX) / pixelBlocks);
            int px1 = (int) ((wx0 + scale - 1 - minWX) / pixelBlocks);
            int pz0 = (int) ((wz0 - minWZ) / pixelBlocks);
            int pz1 = (int) ((wz0 + scale - 1 - minWZ) / pixelBlocks);
            long packed = ((long) lvl << 48) | (((long) (topWorld + 32768)) << 24) | ((floorWorld + 32768) & 0xFFFF);
            for (int pz = pz0; pz <= pz1; pz++) {
                if (pz < 0 || pz >= h) continue;
                int row = pz * w;
                for (int px = px0; px <= px1; px++) {
                    if (px < 0 || px >= w) continue;
                    int idx = row + px;
                    long prev = pixels[idx];
                    if (prev == LONG_EMPTY) {
                        pixels[idx] = packed;
                    } else {
                        int prevLvl = (int) ((prev >>> 48) & 0xF);
                        if (lvl < prevLvl) pixels[idx] = packed;
                        else if (lvl == prevLvl) {
                            int prevTop = (int) ((prev >>> 24) & 0xFFFF) - 32768;
                            if (topWorld > prevTop) pixels[idx] = packed;
                        }
                    }
                }
            }
        }
    }

    static boolean isWater(long voxel, Set<Long> waterIds) {
        long blockId = (voxel >>> 27) & ((1L << 20) - 1);
        return waterIds.contains(blockId);
    }

    static int[] unpack(long p) {
        int top = (int) ((p >>> 24) & 0xFFFF) - 32768;
        int floor = (int) (p & 0xFFFF) - 32768;
        return new int[]{top, floor};
    }

    static BufferedImage render(long[] pixels, int w, int h, int sea, int pixelBlocks) {
        int[] topArr = new int[pixels.length];
        int[] floorArr = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == LONG_EMPTY) { topArr[i] = Integer.MIN_VALUE; floorArr[i] = Integer.MIN_VALUE; }
            else { int[] v = unpack(pixels[i]); topArr[i] = v[0]; floorArr[i] = v[1]; }
        }
        // 3x3 smooth of sea floor (bathymetry) and top (land) for hillshade
        int[] smoothFloor = smooth(floorArr, w, h);
        int[] smoothTop = smooth(topArr, w, h);
        // depth map: real water depth where the water column is known; unknown (coarse LOD drops
        // water voxels) marine pixels are filled by diffusion from real neighbours instead of
        // being blindly treated as shallow.
        float[] depthMap = new float[w * h];
        boolean[] depthKnown = new boolean[w * h];
        boolean[] isWaterPix = new boolean[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int top = topArr[i];
            if (top == Integer.MIN_VALUE) continue;
            int floor = floorArr[i];
            boolean water = floor < top || top <= sea;
            isWaterPix[i] = water;
            if (water && floor < top) { depthMap[i] = top - floor; depthKnown[i] = true; }
        }
        for (int iter = 0; iter < 80; iter++) {
            int changed = 0;
            for (int z = 1; z < h - 1; z++) {
                for (int x = 1; x < w - 1; x++) {
                    int idx = z * w + x;
                    if (!isWaterPix[idx] || depthKnown[idx]) continue;
                    float sum = 0; int cnt = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = idx + dz * w + dx;
                            if (depthKnown[j]) { sum += depthMap[j]; cnt++; }
                        }
                    }
                    if (cnt > 0) { depthMap[idx] = sum / cnt; depthKnown[idx] = true; changed++; }
                }
            }
            if (changed == 0) break;
        }
        final float lx = -0.55f, ly = 0.70f, lz = -0.55f;
        final float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float step = 2f * pixelBlocks;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int idx = z * w + x;
                int top = topArr[idx];
                if (top == Integer.MIN_VALUE) { img.setRGB(x, z, 0); continue; }
                int floor = floorArr[idx];
                // water when a water column exists, or when the surface itself is below sea level
                // (coarse LOD levels drop water voxels, so the surface there is the sea floor)
                boolean water = floor < top || top <= sea;
                int[] base;
                int depth;
                int floorH;
                if (floor < top) {
                    depth = Math.max(1, top - floor);
                    floorH = floor;
                } else {
                    depth = Math.max(1, sea - top + 1);
                    floorH = top;
                }
                int ref = water ? floorH : top;
                int smoothRef = water ? smoothFloor[idx] : smoothTop[idx];
                // shade from the active surface
                float shade = 1f;
                if (x > 0 && z > 0 && x < w - 1 && z < h - 1) {
                    int[] nb = null;
                    if (water) {
                        nb = new int[]{smoothFloor[idx - 1], smoothFloor[idx + 1], smoothFloor[idx - w], smoothFloor[idx + w]};
                    } else {
                        nb = new int[]{smoothTop[idx - 1], smoothTop[idx + 1], smoothTop[idx - w], smoothTop[idx + w]};
                    }
                    if (nb[0] != Integer.MIN_VALUE && nb[1] != Integer.MIN_VALUE && nb[2] != Integer.MIN_VALUE && nb[3] != Integer.MIN_VALUE) {
                        float dzdx = (nb[1] - nb[0]) / step;
                        float dzdy = (nb[3] - nb[2]) / step;
                        float slope = (float) Math.sqrt(dzdx * dzdx + dzdy * dzdy);
                        if (slope > 0.08f) {
                            float nx = -dzdx, ny = 1f, nz = -dzdy;
                            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                            float d = (nx * lx + ny * ly + nz * lz) / (nl * ll);
                            float strength = Math.min(1f, (slope - 0.08f) / 0.6f);
                            shade = 1f + (water ? 0.10f : 0.18f) * d * strength;
                        }
                    }
                }
                shade = Math.max(0.78f, Math.min(1.08f, shade));

                int r, g, b;
                if (water) {
                    float dep = depthKnown[idx] ? depthMap[idx] : (float) Math.max(1, sea - top + 1);
                    int[] c = waterColor(Math.round(dep));
                    r = c[0]; g = c[1]; b = c[2];
                    // isobaths every 20 blocks of sea-floor elevation
                    int nf = Integer.MIN_VALUE;
                    if (x < w - 1 && topArr[idx + 1] != Integer.MIN_VALUE) {
                        nf = Math.min(floorArr[idx + 1], topArr[idx + 1]);
                    } else if (z < h - 1 && topArr[idx + w] != Integer.MIN_VALUE) {
                        nf = Math.min(floorArr[idx + w], topArr[idx + w]);
                    }
                    if (nf != Integer.MIN_VALUE && Math.floorDiv(floorH, 20) != Math.floorDiv(nf, 20)) shade *= 0.75f;
                } else {
                    int[] c = landColor(top, sea);
                    r = c[0]; g = c[1]; b = c[2];
                    // contour lines every 50 blocks
                    if (top > sea) {
                        if (x < w - 1 && topArr[idx + 1] != Integer.MIN_VALUE
                                && Math.floorDiv(top, 50) != Math.floorDiv(topArr[idx + 1], 50)) shade *= 0.70f;
                        else if (z < h - 1 && topArr[idx + w] != Integer.MIN_VALUE
                                && Math.floorDiv(top, 50) != Math.floorDiv(topArr[idx + w], 50)) shade *= 0.70f;
                    }
                    // coastline
                    boolean coast = (x < w - 1 && floorArr[idx + 1] != Integer.MIN_VALUE && floorArr[idx + 1] < topArr[idx + 1] - 1)
                            || (z < h - 1 && floorArr[idx + w] != Integer.MIN_VALUE && floorArr[idx + w] < topArr[idx + w] - 1)
                            || (x > 0 && floorArr[idx - 1] != Integer.MIN_VALUE && floorArr[idx - 1] < topArr[idx - 1] - 1)
                            || (z > 0 && floorArr[idx - w] != Integer.MIN_VALUE && floorArr[idx - w] < topArr[idx - w] - 1);
                    if (coast) shade *= 0.70f;
                }
                r = clamp((int) (r * shade));
                g = clamp((int) (g * shade));
                b = clamp((int) (b * shade));
                img.setRGB(x, z, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    static boolean isFlooded(int[] topArr, int[] floorArr, int x, int z, int w, int h, int sea) {
        return false;
    }

    static int[] smooth(int[] arr, int w, int h) {
        int[] out = new int[arr.length];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int v = arr[zz * w + xx];
                        if (v != Integer.MIN_VALUE) { sum += v; cnt++; }
                    }
                }
                out[z * w + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
            }
        }
        return out;
    }

    static int[] waterColor(int depth) {
        if (depth <= 4) return new int[]{120, 190, 210};
        if (depth <= 12) return new int[]{85, 160, 205};
        if (depth <= 25) return new int[]{60, 125, 190};
        if (depth <= 45) return new int[]{42, 95, 165};
        if (depth <= 70) return new int[]{28, 65, 135};
        return new int[]{14, 38, 95};
    }

    static int[] landColor(int y, int sea) {
        int hh = y - sea;
        if (hh <= 2) return new int[]{210, 200, 160};
        if (hh <= 10) return new int[]{125, 175, 95};
        if (hh <= 40) return new int[]{100, 160, 78};
        if (hh <= 80) return new int[]{84, 143, 70};
        if (hh <= 140) return new int[]{110, 150, 72};
        if (hh <= 220) return new int[]{148, 140, 80};
        if (hh <= 320) return new int[]{158, 132, 90};
        if (hh <= 450) return new int[]{166, 156, 132};
        if (hh <= 580) return new int[]{192, 192, 186};
        if (hh <= 700) return new int[]{220, 225, 224};
        return new int[]{250, 252, 255};
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
