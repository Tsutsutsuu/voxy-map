import com.github.luben.zstd.Zstd;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

/**
 * Voxy LOD -> professional relief map, v10 (performance).
 *
 * v9 features + parallel section decode:
 *  - RocksDB values are ZSTD-decompressed with Zstd.decompress (fast path) in a
 *    thread pool, sections decoded into column arrays, then flushed into the
 *    pixel grid serially (cheap, keeps merge rules simple).
 *  - voxel LUT is read once into arrays instead of random ByteBuffer access.
 */
public class VoxyMapPoC15 {
    static final int VOL = 32 * 32 * 32;
    static final long LONG_EMPTY = Long.MIN_VALUE;
    static final int BATCH = 2000;
    static String SCHEME = "current";

    // gradient palettes: {position 0..1000, r, g, b}
    static final int[][] LAND_GRADIENT = {
            {0, 200, 200, 170},
            {30, 120, 180, 100},
            {100, 90, 150, 80},
            {200, 120, 145, 80},
            {350, 160, 140, 100},
            {500, 170, 165, 150},
            {700, 210, 212, 208},
            {820, 250, 252, 255},
    };
    static final int[][] LAND_TURBO = {
            {0, 60, 120, 180},
            {150, 60, 180, 150},
            {300, 120, 200, 90},
            {500, 220, 220, 80},
            {650, 220, 150, 70},
            {780, 180, 100, 80},
            {900, 250, 250, 250},
    };
    static final int[][] LAND_SOFT = {
            {0, 200, 215, 200},
            {200, 170, 200, 165},
            {400, 190, 200, 160},
            {600, 205, 195, 165},
            {800, 220, 215, 205},
            {900, 245, 245, 242},
    };
    static final int[][] WATER_GRADIENT = {
            {0, 120, 190, 210},
            {300, 60, 125, 190},
            {600, 28, 65, 135},
            {1000, 14, 38, 95},
    };
    static final int[][] WATER_TURBO = {
            {0, 90, 150, 200},
            {400, 40, 90, 160},
            {1000, 10, 25, 70},
    };
    static final int[][] WATER_SOFT = {
            {0, 150, 185, 210},
            {500, 70, 120, 180},
            {1000, 30, 60, 120},
    };

    record Decoded(long key, long[] cols) {
    }

    public static void main(String[] args) throws Exception {
        String path = args[0];
        String out = args[1];
        int pixelBlocks = Integer.parseInt(args[2]);
        int seaLevel = args.length > 3 ? Integer.parseInt(args[3]) : 63;
        SCHEME = args.length > 4 ? args[4] : "current";
        System.out.println("scheme=" + SCHEME);
        int maxLevel = 4;

        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("world_sections".getBytes()),
                new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), path, cfds, handles);

        long tA = System.nanoTime();
        Set<Long> waterIds = loadWaterIds(db, handles.get(2));
        long tB = System.nanoTime();
        System.out.println("water ids=" + waterIds + " (" + (tB - tA) / 1_000_000 + " ms)");

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
        long tC = System.nanoTime();
        int w = (int) ((maxWX - minWX + pixelBlocks - 1) / pixelBlocks);
        int h = (int) ((maxWZ - minWZ + pixelBlocks - 1) / pixelBlocks);
        System.out.println("keys=" + keys + " bounds=[" + minWX + ".." + maxWX + "]x[" + minWZ + ".." + maxWZ + "] px=" + w + "x" + h
                + " (" + (tC - tB) / 1_000_000 + " ms)");
        long[] pixels = new long[w * h];
        Arrays.fill(pixels, LONG_EMPTY);

        // ---- parallel decode + serial flush ----
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int[] perLevel = new int[16];
        List<Future<Decoded>> futures = new ArrayList<>(BATCH);
        long tD = System.nanoTime();
        int submitted = 0;
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
                            perLevel[lvl]++;
                            final long fkey = key;
                            final byte[] fval = val;
                            futures.add(pool.submit((Callable<Decoded>) () -> decodeSection(fkey, fval, waterIds)));
                            submitted++;
                            if (futures.size() >= BATCH) {
                                flush(futures, pixels, w, h, pixelBlocks, minWX, minWZ);
                                futures.clear();
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
        flush(futures, pixels, w, h, pixelBlocks, minWX, minWZ);
        pool.shutdown();
        long tE = System.nanoTime();
        System.out.println("decode+flush=" + (tE - tD) / 1_000_000 + " ms (" + submitted + " sections, " + threads + " threads)");

        int n = 0;
        for (long p : pixels) if (p != LONG_EMPTY) n++;
        StringBuilder sb = new StringBuilder("sections:");
        for (int l = 0; l <= maxLevel; l++) sb.append(" L").append(l).append("=").append(perLevel[l]);
        sb.append(" pixels=").append(n).append("/").append(w * h);
        System.out.println(sb);

        long tF = System.nanoTime();
        BufferedImage img = render(pixels, w, h, seaLevel, pixelBlocks);
        long tG = System.nanoTime();
        ImageIO.write(img, "png", new File(out));
        long tH = System.nanoTime();
        System.out.println("render=" + (tG - tF) / 1_000_000 + " ms png=" + (tH - tG) / 1_000_000 + " ms");
        System.out.println("written " + out);

        BufferedImage small = new BufferedImage(w / 4, h / 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < small.getHeight(); y++)
            for (int x = 0; x < small.getWidth(); x++)
                small.setRGB(x, y, img.getRGB(x * 4, y * 4));
        ImageIO.write(small, "png", new File(out.replace(".png", "-preview.png")));
    }

    static void flush(List<Future<Decoded>> futures, long[] pixels, int w, int h, int pixelBlocks, long minWX, long minWZ) throws Exception {
        for (Future<Decoded> f : futures) {
            Decoded d = f.get();
            if (d == null) continue;
            flushSection(d.key(), d.cols(), pixels, w, h, pixelBlocks, minWX, minWZ);
        }
    }

    static void flushSection(long key, long[] cols, long[] pixels, int w, int h, int pixelBlocks, long minWX, long minWZ) {
        int lvl = (int) ((key >>> 60) & 0xF);
        int sx = (int) ((key << 36) >> 40);
        int sz = (int) ((key << 12) >> 40);
        int scale = 1 << lvl;
        for (int c = 0; c < 1024; c++) {
            long packed = cols[c];
            if (packed == LONG_EMPTY) continue;
            int top = (int) ((packed >>> 24) & 0xFFFF) - 32768;
            int floor = (int) (packed & 0xFFFF) - 32768;
            int localX = c & 31;
            int localZ = c >> 5;
            long wx0 = (long) (sx * 32 + localX) * scale;
            long wz0 = (long) (sz * 32 + localZ) * scale;
            int px0 = (int) ((wx0 - minWX) / pixelBlocks);
            int px1 = (int) ((wx0 + scale - 1 - minWX) / pixelBlocks);
            int pz0 = (int) ((wz0 - minWZ) / pixelBlocks);
            int pz1 = (int) ((wz0 + scale - 1 - minWZ) / pixelBlocks);
            long pix = ((long) lvl << 48) | (((long) (top + 32768)) << 24) | ((floor + 32768) & 0xFFFF);
            for (int pz = pz0; pz <= pz1; pz++) {
                if (pz < 0 || pz >= h) continue;
                int row = pz * w;
                for (int px = px0; px <= px1; px++) {
                    if (px < 0 || px >= w) continue;
                    int idx = row + px;
                    long prev = pixels[idx];
                    if (prev == LONG_EMPTY) {
                        pixels[idx] = pix;
                    } else {
                        int prevLvl = (int) ((prev >>> 48) & 0xF);
                        if (lvl < prevLvl) pixels[idx] = pix;
                        else if (lvl == prevLvl) {
                            int prevTop = (int) ((prev >>> 24) & 0xFFFF) - 32768;
                            if (top > prevTop) pixels[idx] = pix;
                        }
                    }
                }
            }
        }
    }

    static Decoded decodeSection(long key, byte[] val, Set<Long> waterIds) {
        byte[] d;
        try {
            d = decompress(val);
        } catch (Exception e) {
            return null;
        }
        if (d == null || d.length < 16 + VOL * 2) return null;
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        bb.getLong();
        long meta = bb.getLong();
        int lutSize = (int) (meta & 0xFFFF);
        int base = 16;
        int lutBase = base + VOL * 2;
        if (lutSize <= 0 || lutSize > (1 << 16) || lutBase + lutSize * 8L > d.length) return null;

        short[] idx = new short[VOL];
        for (int i = 0; i < VOL; i++) idx[i] = bb.getShort(base + i * 2);
        long[] lut = new long[lutSize];
        for (int i = 0; i < lutSize; i++) lut[i] = bb.getLong(lutBase + i * 8);

        int sy = (int) ((key << 4) >> 56);
        long airMask = ((1L << 20) - 1) << 27;
        long[] cols = new long[1024];
        Arrays.fill(cols, LONG_EMPTY);
        int scale = 1 << (int) ((key >>> 60) & 0xF);
        for (int c = 0; c < 1024; c++) {
            int x = c & 31;
            int z = c >> 5;
            int topY = -1, floorY = -1;
            boolean fluid = false;
            for (int y = 31; y >= 0; y--) {
                int i = (y << 10) | (z << 5) | x;
                int li = Short.toUnsignedInt(idx[i]);
                if (li >= lutSize) continue;
                long voxel = lut[li];
                if ((voxel & airMask) == 0) continue;
                if (topY < 0) {
                    topY = y;
                    fluid = isWater(voxel, waterIds);
                    if (!fluid) floorY = y;
                } else if (fluid && floorY < 0 && !isWater(voxel, waterIds)) {
                    floorY = y;
                }
            }
            if (topY < 0) continue;
            if (floorY < 0) floorY = topY;
            int topWorld = (sy * 32 + topY) * scale + scale - 1;
            int floorWorld = (sy * 32 + floorY) * scale + scale - 1;
            cols[c] = (((long) (topWorld + 32768)) << 24) | ((floorWorld + 32768) & 0xFFFF);
        }
        return new Decoded(key, cols);
    }

    static byte[] decompress(byte[] in) throws Exception {
        long sz = Zstd.getFrameContentSize(in);
        if (sz > 0 && sz < 50_000_000) {
            return Zstd.decompress(in, (int) sz);
        }
        try (ZstdInputStream z = new ZstdInputStream(new ByteArrayInputStream(in))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(700_000);
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

    static boolean isWater(long voxel, Set<Long> waterIds) {
        long blockId = (voxel >>> 27) & ((1L << 20) - 1);
        return waterIds.contains(blockId);
    }

    // ------------------------- rendering (same as v9) -------------------------

    static BufferedImage render(long[] pixels, int w, int h, int sea, int pixelBlocks) {
        int[] topArr = new int[pixels.length];
        int[] floorArr = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == LONG_EMPTY) { topArr[i] = Integer.MIN_VALUE; floorArr[i] = Integer.MIN_VALUE; }
            else { topArr[i] = (int) ((pixels[i] >>> 24) & 0xFFFF) - 32768; floorArr[i] = (int) (pixels[i] & 0xFFFF) - 32768; }
        }
        int[] smoothFloor = smooth(floorArr, w, h);
        int[] smoothTop = smooth(topArr, w, h);
        float[] depthMap = new float[w * h];
        boolean[] depthKnown = new boolean[w * h];
        boolean[] isWaterPix = new boolean[w * h];
        // multi-source BFS: only unknown marine pixels are visited, layer by layer
        int[] cur = new int[w * h];
        int curN = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (topArr[i] == Integer.MIN_VALUE) continue;
            int floor = floorArr[i];
            boolean water = floor < topArr[i] || topArr[i] <= sea;
            isWaterPix[i] = water;
            if (water && floor < topArr[i]) { depthMap[i] = topArr[i] - floor; depthKnown[i] = true; }
            else if (water) cur[curN++] = i;
        }
        while (curN > 0) {
            int[] next = new int[curN];
            int nextN = 0, filled = 0;
            for (int k = 0; k < curN; k++) {
                int idx = cur[k];
                int x = idx % w, z = idx / w;
                float sum = 0; int cnt = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = zz * w + xx;
                        if (depthKnown[j]) { sum += depthMap[j]; cnt++; }
                    }
                }
                if (cnt > 0) { depthMap[idx] = sum / cnt; depthKnown[idx] = true; filled++; }
                else next[nextN++] = idx;
            }
            if (filled == 0) break;
            cur = next;
            curN = nextN;
        }

        // contour mask from the smoothed land height, then drop small fragments by connected-area size
        boolean[] contourRaw = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            if (topArr[i] == Integer.MIN_VALUE) continue;
            if (floorArr[i] < topArr[i] || topArr[i] <= sea) continue; // water: no contours
            int t = smoothTop[i];
            if (t == Integer.MIN_VALUE) continue;
            int x = i % w, z = i / w;
            if (x < w - 1 && smoothTop[i + 1] != Integer.MIN_VALUE && Math.floorDiv(t, 50) != Math.floorDiv(smoothTop[i + 1], 50)) {
                contourRaw[i] = true;
            } else if (z < h - 1 && smoothTop[i + w] != Integer.MIN_VALUE && Math.floorDiv(t, 50) != Math.floorDiv(smoothTop[i + w], 50)) {
                contourRaw[i] = true;
            }
        }
        boolean[] contour = new boolean[w * h];
        {
            int[] queue = new int[w * h];
            boolean[] seen = new boolean[w * h];
            for (int start = 0; start < w * h; start++) {
                if (!contourRaw[start] || seen[start]) continue;
                int qh = 0, qt = 0;
                queue[qt++] = start;
                seen[start] = true;
                while (qh < qt) {
                    int idx2 = queue[qh++];
                    int x2 = idx2 % w, z2 = idx2 / w;
                    for (int dz = -1; dz <= 1; dz++) {
                        int zz = z2 + dz;
                        if (zz < 0 || zz >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x2 + dx;
                            if (xx < 0 || xx >= w) continue;
                            int j = zz * w + xx;
                            if (contourRaw[j] && !seen[j]) { seen[j] = true; queue[qt++] = j; }
                        }
                    }
                }
                if (qt >= 40) {
                    for (int k = 0; k < qt; k++) contour[queue[k]] = true;
                }
            }
        }

        final float lx = -0.55f, ly = 0.70f, lz = -0.55f;
        final float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] imgData = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float step = 2f * pixelBlocks;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int idx = z * w + x;
                int top = topArr[idx];
                if (top == Integer.MIN_VALUE) { imgData[idx] = 0; continue; }
                int floor = floorArr[idx];
                boolean water = floor < top || top <= sea;
                int depth = 1, floorH = top;
                if (floor < top) { depth = Math.max(1, top - floor); floorH = floor; }
                float shade = 1f;
                if (x > 0 && z > 0 && x < w - 1 && z < h - 1) {
                    int nb0, nb1, nb2, nb3;
                    if (water) { nb0 = smoothFloor[idx - 1]; nb1 = smoothFloor[idx + 1]; nb2 = smoothFloor[idx - w]; nb3 = smoothFloor[idx + w]; }
                    else { nb0 = smoothTop[idx - 1]; nb1 = smoothTop[idx + 1]; nb2 = smoothTop[idx - w]; nb3 = smoothTop[idx + w]; }
                    if (nb0 != Integer.MIN_VALUE && nb1 != Integer.MIN_VALUE && nb2 != Integer.MIN_VALUE && nb3 != Integer.MIN_VALUE) {
                        float dzdx = (nb1 - nb0) / step;
                        float dzdy = (nb3 - nb2) / step;
                        float slope = (float) Math.sqrt(dzdx * dzdx + dzdy * dzdy);
                        if (slope > 0.08f) {
                            float nx = -dzdx, ny = 1f, nz = -dzdy;
                            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                            float d = (nx * lx + ny * ly + nz * lz) / (nl * ll);
                            float strength = Math.min(1f, (slope - 0.08f) / 0.6f);
                            float landStrength = SCHEME.equals("nautical") ? 0.06f : 0.18f;
                            shade = 1f + (water ? 0.10f : landStrength) * d * strength;
                        }
                    }
                }
                shade = Math.max(0.78f, Math.min(1.08f, shade));
                int rgb;
                if (water) {
                    float dep = depthKnown[idx] ? depthMap[idx] : (float) Math.max(1, sea - top + 1);
                    rgb = waterColor(Math.round(dep));
                } else {
                    rgb = landColor(top, sea);
                    if (contour[idx]) shade *= SCHEME.equals("nautical") ? 0.62f : 0.70f;
                    boolean coast = (x < w - 1 && floorArr[idx + 1] != Integer.MIN_VALUE && floorArr[idx + 1] < topArr[idx + 1])
                            || (z < h - 1 && floorArr[idx + w] != Integer.MIN_VALUE && floorArr[idx + w] < topArr[idx + w])
                            || (x > 0 && floorArr[idx - 1] != Integer.MIN_VALUE && floorArr[idx - 1] < topArr[idx - 1])
                            || (z > 0 && floorArr[idx - w] != Integer.MIN_VALUE && floorArr[idx - w] < topArr[idx - w]);
                    if (coast) shade *= 0.70f;
                }
                int r = clamp((int) (((rgb >> 16) & 0xFF) * shade));
                int g = clamp((int) (((rgb >> 8) & 0xFF) * shade));
                int b = clamp((int) ((rgb & 0xFF) * shade));
                imgData[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return img;
    }

    static int[] smooth(int[] arr, int w, int h) {
        int[] tmp = new int[arr.length];
        int[] out = new int[arr.length];
        for (int z = 0; z < h; z++) {
            int row = z * w;
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    int v = arr[row + xx];
                    if (v != Integer.MIN_VALUE) { sum += v; cnt++; }
                }
                tmp[row + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
            }
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    int v = tmp[zz * w + x];
                    if (v != Integer.MIN_VALUE) { sum += v; cnt++; }
                }
                out[z * w + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
            }
        }
        return out;
    }

    static int grad(int[][] stops, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int p = Math.round(t * 1000);
        for (int i = 0; i < stops.length - 1; i++) {
            if (p <= stops[i + 1][0]) {
                float f = (p - stops[i][0]) / (float) (stops[i + 1][0] - stops[i][0]);
                int r = clamp((int) (stops[i][1] + (stops[i + 1][1] - stops[i][1]) * f));
                int g = clamp((int) (stops[i][2] + (stops[i + 1][2] - stops[i][2]) * f));
                int b = clamp((int) (stops[i][3] + (stops[i + 1][3] - stops[i][3]) * f));
                return (r << 16) | (g << 8) | b;
            }
        }
        int last = stops.length - 1;
        return (stops[last][1] << 16) | (stops[last][2] << 8) | stops[last][3];
    }

    static int waterColor(int depth) {
        if (SCHEME.startsWith("gradient")) {
            int[][] stops = SCHEME.equals("gradient-turbo") ? WATER_TURBO : SCHEME.equals("gradient-soft") ? WATER_SOFT : WATER_GRADIENT;
            return grad(stops, depth / 80f);
        }
        switch (SCHEME) {
            case "gmt":
                if (depth <= 5) return (140 << 16) | (190 << 8) | 215;
                if (depth <= 15) return (90 << 16) | (150 << 8) | 200;
                if (depth <= 35) return (50 << 16) | (105 << 8) | 170;
                if (depth <= 70) return (25 << 16) | (60 << 8) | 120;
                return (10 << 16) | (30 << 8) | 80;
            case "nautical":
                if (depth <= 5) return (215 << 16) | (228 << 8) | 240;
                if (depth <= 15) return (170 << 16) | (200 << 8) | 225;
                if (depth <= 35) return (120 << 16) | (165 << 8) | 205;
                if (depth <= 60) return (75 << 16) | (125 << 8) | 180;
                return (35 << 16) | (80 << 8) | 140;
            default:
                if (depth <= 4) return (120 << 16) | (190 << 8) | 210;
                if (depth <= 12) return (85 << 16) | (160 << 8) | 205;
                if (depth <= 25) return (60 << 16) | (125 << 8) | 190;
                if (depth <= 45) return (42 << 16) | (95 << 8) | 165;
                if (depth <= 70) return (28 << 16) | (65 << 8) | 135;
                return (14 << 16) | (38 << 8) | 95;
        }
    }

    static int landColor(int y, int sea) {
        int hh = y - sea;
        if (SCHEME.startsWith("gradient")) {
            int[][] stops = SCHEME.equals("gradient-turbo") ? LAND_TURBO : SCHEME.equals("gradient-soft") ? LAND_SOFT : LAND_GRADIENT;
            return grad(stops, hh / 820f);
        }
        switch (SCHEME) {
            case "gmt":
                if (hh <= 5) return (150 << 16) | (200 << 8) | 190;
                if (hh <= 20) return (140 << 16) | (190 << 8) | 120;
                if (hh <= 50) return (190 << 16) | (210 << 8) | 120;
                if (hh <= 90) return (230 << 16) | (220 << 8) | 130;
                if (hh <= 150) return (220 << 16) | (190 << 8) | 120;
                if (hh <= 220) return (190 << 16) | (150 << 8) | 110;
                if (hh <= 320) return (160 << 16) | (120 << 8) | 100;
                if (hh <= 450) return (190 << 16) | (185 << 8) | 180;
                if (hh <= 600) return (220 << 16) | (220 << 8) | 218;
                return (250 << 16) | (250 << 8) | 250;
            case "nautical":
                if (hh <= 2) return (245 << 16) | (240 << 8) | 225;
                if (hh <= 20) return (235 << 16) | (232 << 8) | 215;
                if (hh <= 80) return (210 << 16) | (218 << 8) | 200;
                if (hh <= 200) return (180 << 16) | (195 << 8) | 175;
                if (hh <= 400) return (150 << 16) | (165 << 8) | 150;
                return (235 << 16) | (235 << 8) | 235;
            default:
                if (hh <= 2) return (210 << 16) | (200 << 8) | 160;
                if (hh <= 10) return (125 << 16) | (175 << 8) | 95;
                if (hh <= 40) return (100 << 16) | (160 << 8) | 78;
                if (hh <= 80) return (84 << 16) | (143 << 8) | 70;
                if (hh <= 140) return (110 << 16) | (150 << 8) | 72;
                if (hh <= 220) return (148 << 16) | (140 << 8) | 80;
                if (hh <= 320) return (158 << 16) | (132 << 8) | 90;
                if (hh <= 450) return (166 << 16) | (156 << 8) | 132;
                if (hh <= 580) return (192 << 16) | (192 << 8) | 186;
                if (hh <= 700) return (220 << 16) | (225 << 8) | 224;
                return (250 << 16) | (252 << 8) | 255;
        }
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}

