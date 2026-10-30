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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
public class VoxyMapPoC10 {
    static final int VOL = 32 * 32 * 32;
    static final long LONG_EMPTY = Long.MIN_VALUE;
    static final int NO_SOLID = -32768;
    static final boolean DENOISE = false;
    static final java.util.concurrent.atomic.LongAdder DECODE_NS = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder FLUSH_NS = new java.util.concurrent.atomic.LongAdder();
    static String SCHEME = "current";
    static final int NO_TOP = -32767;
    static final int BATCH = 2000;

    record Decoded(long key, long[] cols) {
    }

    public static void main(String[] args) throws Exception {
        String path = args[0];
        String out = args[1];
        int pixelBlocks = Integer.parseInt(args[2]);
        int seaLevel = args.length > 3 ? Integer.parseInt(args[3]) : 63;
        int maxLevel = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        SCHEME = args.length > 5 ? args[5] : "current";

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
                                long tf1 = System.nanoTime();
                                long tf2 = System.nanoTime();
        flush(futures, pixels, w, h, pixelBlocks, minWX, minWZ);
        FLUSH_NS.add(System.nanoTime() - tf2);
                                FLUSH_NS.add(System.nanoTime() - tf1);
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
        System.out.println("decode=" + DECODE_NS.sum() / 1_000_000 + " ms flush=" + FLUSH_NS.sum() / 1_000_000 + " ms");

        {
            int unknownN = 0;
            long firstX = 0, firstZ = 0;
            int firstTop = 0;
            for (int i = 0; i < pixels.length; i++) {
                long p = pixels[i];
                if (p == LONG_EMPTY) continue;
                int top = (int) ((p >>> 24) & 0xFFFF) - 32768;
                int floor = (int) (p & 0xFFFF) - 32768;
                boolean water = (p & (1L << 52)) != 0;
                if (water && floor >= top) {
                    if (unknownN == 0) {
                        int x = i % w, z = i / w;
                        firstX = minWX + (long) x * pixelBlocks;
                        firstZ = minWZ + (long) z * pixelBlocks;
                        firstTop = top;
                    }
                    unknownN++;
                }
            }
            System.out.println("unknown-depth=" + unknownN + " first world=" + firstX + "," + firstZ + " top=" + firstTop);
        }
        for (int[] probe : new int[][]{{23, -89}}) {
            int ppx = (int) ((probe[0] - minWX) / pixelBlocks);
            int ppz = (int) ((probe[1] - minWZ) / pixelBlocks);
            long p = pixels[ppz * w + ppx];
            int top = (int) ((p >>> 24) & 0xFFFF) - 32768;
            int floor = (int) (p & 0xFFFF) - 32768;
            int lvl = (int) ((p >>> 48) & 0xF);
            boolean water = (p & (1L << 52)) != 0;
            System.out.println("probe world=" + probe[0] + "," + probe[1] + " px=" + ppx + "," + ppz
                    + " top=" + top + " floor=" + floor + " lvl=" + lvl + " water=" + water);
        }
        {
            int[] lvlHist = new int[16];
            int noTop = 0;
            for (long p : pixels) {
                if (p == LONG_EMPTY) continue;
                int t = (int) ((p >>> 24) & 0xFFFF) - 32768;
                int l = (int) ((p >>> 48) & 0xF);
                if (t == NO_TOP) noTop++;
                else lvlHist[l]++;
            }
            StringBuilder sb2 = new StringBuilder("top-source levels:");
            for (int l = 0; l < 8; l++) if (lvlHist[l] > 0) sb2.append(" l").append(l).append("=").append(lvlHist[l]);
            sb2.append(" noTop=").append(noTop);
            System.out.println(sb2);
        }
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

    static int betterFloor(int a, int b, int top) {
        boolean okA = a != NO_SOLID && a != Integer.MIN_VALUE && a < top;
        boolean okB = b != NO_SOLID && b != Integer.MIN_VALUE && b < top;
        if (okA && okB) return Math.max(a, b);
        if (okA) return a;
        if (okB) return b;
        return NO_SOLID;
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
            boolean topFluid = (packed & (1L << 52)) != 0;
            int biomeHere = (int) ((packed >>> 53) & 0x1FF);
            int localX = c & 31;
            int localZ = c >> 5;
            long wx0 = (long) (sx * 32 + localX) * scale;
            long wz0 = (long) (sz * 32 + localZ) * scale;
            int px0 = (int) ((wx0 - minWX) / pixelBlocks);
            int px1 = (int) ((wx0 + scale - 1 - minWX) / pixelBlocks);
            int pz0 = (int) ((wz0 - minWZ) / pixelBlocks);
            int pz1 = (int) ((wz0 + scale - 1 - minWZ) / pixelBlocks);
            for (int pz = pz0; pz <= pz1; pz++) {
                if (pz < 0 || pz >= h) continue;
                int row = pz * w;
                for (int px = px0; px <= px1; px++) {
                    if (px < 0 || px >= w) continue;
                    int idx = row + px;
                    long prev = pixels[idx];
                    int solidHere = floor != NO_SOLID ? floor : Integer.MIN_VALUE;
                    int curTop;
                    boolean curWater;
                    int curLvl;
                    int curFloor;
                    int curBiome;
                    if (prev == LONG_EMPTY) {
                        curTop = top;
                        curWater = topFluid;
                        curLvl = lvl;
                        curFloor = NO_SOLID;
                        curBiome = biomeHere;
                    } else {
                        curTop = (int) ((prev >>> 24) & 0xFFFF) - 32768;
                        curWater = (prev & (1L << 52)) != 0;
                        curLvl = (int) ((prev >>> 48) & 0xF);
                        curFloor = (int) (prev & 0xFFFF) - 32768;
                        curBiome = (int) ((prev >>> 53) & 0x1FF);
                        if (top != NO_TOP) {
                            boolean accept = curTop == NO_TOP
                                    || lvl < curLvl
                                    || (lvl == curLvl && top > curTop)
                                    // a much higher coarse-level top means the fine level has no
                                    // surface section here (only buried cave slices): take the coarse surface
                                    || (lvl > curLvl && curTop < 0 && top > curTop + 32);
                            if (accept) {
                                curTop = top;
                                curWater = topFluid;
                                curLvl = lvl;
                                curBiome = biomeHere;
                            }
                        }
                    }
                    // same-level highest solid above the current top: the top is a cave/underground
                    // ceiling, so the real surface is the top of that solid
                    if (curTop != NO_TOP && solidHere != Integer.MIN_VALUE && solidHere > curTop && lvl == curLvl) {
                        curTop = solidHere;
                        curWater = false;
                        curLvl = lvl;
                        curBiome = biomeHere;
                    }
                    // floor: highest solid in the whole column (sea floor for water)
                    if (curTop != NO_TOP && solidHere != Integer.MIN_VALUE && solidHere < curTop) {
                        int prevF = curFloor == NO_SOLID ? Integer.MIN_VALUE : curFloor;
                        if (prevF == Integer.MIN_VALUE || solidHere > prevF) curFloor = solidHere;
                    }
                    if (curTop != NO_TOP && !curWater) curFloor = curTop;
                    if (curFloor == Integer.MIN_VALUE) curFloor = NO_SOLID;
                    long out = ((long) (curTop + 32768)) << 24 | ((curFloor + 32768) & 0xFFFF);
                    if (curWater) out |= (1L << 52);
                    out |= ((long) curBiome) << 53;
                    pixels[idx] = out;
                }
            }
        }
    }

    static Decoded decodeSection(long key, byte[] val, Set<Long> waterIds) {
        long t0 = System.nanoTime();
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
            int topY = -1;
            boolean topFluid = false;
            int solidY = -1;
            boolean hasAir = false;
            boolean hasFluid = false;
            long topVox = 0;
            for (int y = 31; y >= 0; y--) {
                int i = (y << 10) | (z << 5) | x;
                int li = Short.toUnsignedInt(idx[i]);
                if (li >= lutSize) continue;
                long voxel = lut[li];
                if ((voxel & airMask) == 0) { hasAir = true; continue; }
                boolean fluid = isWater(voxel, waterIds);
                if (fluid) hasFluid = true;
                if (topY < 0) {
                    topY = y;
                    topFluid = fluid;
                    topVox = voxel;
                }
                if (!fluid && y > solidY) solidY = y;
            }
            if (topY < 0 && solidY < 0) continue; // empty column
            int topWorld = topY >= 0 ? (sy * 32 + topY) * scale + scale - 1 : NO_TOP;
            // a solid column reaching the section ceiling is a buried slice, not terrain
            if (topY >= 0 && topY == 31 && !topFluid) topWorld = NO_TOP;
            int solidWorld = solidY >= 0 ? (sy * 32 + solidY) * scale + scale - 1 : NO_SOLID;
            long out = (((long) (topWorld + 32768)) << 24) | ((solidWorld + 32768) & 0xFFFF);
            if (topFluid) out |= (1L << 52);
            out |= ((long) (((topVox >>> 47) & 0x1FF))) << 53;
            cols[c] = out;
        }
        DECODE_NS.add(System.nanoTime() - t0);
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

    static final Map<Long, String> BIOME_NAMES = new HashMap<>();

    static Set<Long> loadWaterIds(RocksDB db, ColumnFamilyHandle cf) throws Exception {
        Set<Long> out = new HashSet<>();
        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] k = it.key();
                byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    long be = ((long) (k[0] & 0xFF) << 24) | ((long) (k[1] & 0xFF) << 16)
                            | ((long) (k[2] & 0xFF) << 8) | (k[3] & 0xFF);
                    String text = unzipText(v);
                    if (text != null && ((be >>> 24) == 0x80)) {
                        int hi = text.indexOf("minecraft:");
                        if (hi < 0) hi = text.indexOf(':');
                        if (hi >= 0) {
                            int end = hi;
                            while (end < text.length()) {
                                char ch = text.charAt(end);
                                if (ch == ' ' || ch == '.' || ch == '	') break;
                                end++;
                            }
                            BIOME_NAMES.put(be & ((1L << 30) - 1), text.substring(hi, end));
                        }
                    }
                    if (text != null && text.contains("minecraft:water") && !text.contains("cauldron")) {
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
        long tR0 = System.nanoTime();
        int[] topArr = new int[pixels.length];
        int[] floorArr = new int[pixels.length];
        boolean[] isWaterPix = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == LONG_EMPTY) { topArr[i] = Integer.MIN_VALUE; floorArr[i] = Integer.MIN_VALUE; }
            else {
                int t = (int) ((pixels[i] >>> 24) & 0xFFFF) - 32768;
                int fl = (int) (pixels[i] & 0xFFFF) - 32768;
                boolean tw = (pixels[i] & (1L << 52)) != 0;
                if (t == NO_TOP) {
                    t = fl != NO_SOLID ? fl : Integer.MIN_VALUE;
                    tw = false;
                }
                topArr[i] = t;
                isWaterPix[i] = tw;
                floorArr[i] = t == Integer.MIN_VALUE ? Integer.MIN_VALUE : ((tw && fl != NO_SOLID && fl < t) ? fl : t);
            }
        }
        long tUnpack = System.nanoTime();
        // fill interior data holes: connected empty regions that do not touch the map border
        // (and are small enough) get values spread inwards from the surrounding terrain
        {
            final int maxHoleArea = 500;
            boolean[] visited = new boolean[w * h];
            int[] queue = new int[w * h];
            for (int s = 0; s < w * h; s++) {
                if (visited[s] || topArr[s] != Integer.MIN_VALUE) continue;
                int qh = 0, qt = 0;
                queue[qt++] = s;
                visited[s] = true;
                boolean touchesBorder = false;
                while (qh < qt) {
                    int idx = queue[qh++];
                    int x = idx % w;
                    int z = idx / w;
                    if (x == 0 || x == w - 1 || z == 0 || z == h - 1) touchesBorder = true;
                    for (int dz = -1; dz <= 1; dz++) {
                        int zz = z + dz;
                        if (zz < 0 || zz >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            int j = zz * w + xx;
                            if (topArr[j] == Integer.MIN_VALUE && !visited[j]) {
                                visited[j] = true;
                                queue[qt++] = j;
                            }
                        }
                    }
                }
                if (qt > maxHoleArea) continue; // large empty region = unexplored
                if (touchesBorder && qt > 200) continue; // medium region touching the rim = map edge
                boolean any = true;
                while (any) {
                    any = false;
                    int[] toFill = new int[qt];
                    int[] vals = new int[qt];
                    int n = 0;
                    for (int t = 0; t < qt; t++) {
                        int idx = queue[t];
                        if (topArr[idx] != Integer.MIN_VALUE) continue;
                        int x = idx % w, z = idx / w;
                        int sum = 0, cnt = 0;
                        for (int dz = -1; dz <= 1; dz++) {
                            int zz = z + dz;
                            if (zz < 0 || zz >= h) continue;
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx;
                                if (xx < 0 || xx >= w) continue;
                                int v = topArr[zz * w + xx];
                                if (v != Integer.MIN_VALUE) {
                                    sum += v;
                                    cnt++;
                                }
                            }
                        }
                        if (cnt > 0) {
                            toFill[n] = idx;
                            vals[n] = sum / cnt;
                            n++;
                        }
                    }
                    if (n == 0) break;
                    for (int t = 0; t < n; t++) {
                        topArr[toFill[t]] = vals[t];
                        isWaterPix[toFill[t]] = false;
                        floorArr[toFill[t]] = vals[t];
                    }
                    any = true;
                }
            }
        }
        long tFill = System.nanoTime();
        // denoise isolated land-height outliers produced by LOD merging
        if (DENOISE) {
            int[] den = topArr.clone();
            for (int z = 1; z < h - 1; z++) {
                for (int x = 1; x < w - 1; x++) {
                    int idx = z * w + x;
                    if (isWaterPix[idx] || topArr[idx] == Integer.MIN_VALUE) continue;
                    int[] vals = new int[9];
                    int n = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = idx + dz * w + dx;
                            if (!isWaterPix[j] && topArr[j] != Integer.MIN_VALUE) vals[n++] = topArr[j];
                        }
                    }
                    if (n >= 5) {
                        int[] sorted = java.util.Arrays.copyOf(vals, n);
                        java.util.Arrays.sort(sorted);
                        int medv = sorted[n / 2];
                        if (Math.abs(topArr[idx] - medv) > 20) den[idx] = medv;
                    }
                }
            }
            topArr = den;
        }
        long tDenoise = System.nanoTime();
        int[] smoothFloor = smooth(floorArr, w, h);
        int[] smoothTop = smooth(topArr, w, h);
        float[] depthMap = new float[w * h];
        boolean[] depthKnown = new boolean[w * h];
        {
            int unknownN = 0;
            for (int i = 0; i < pixels.length; i++) {
                if (topArr[i] == Integer.MIN_VALUE) continue;
                if (isWaterPix[i] && floorArr[i] >= topArr[i]) unknownN++;
            }
            System.out.println("unknown-depth water pixels: " + unknownN);
        }
        long tSmooth = System.nanoTime();
        // water depth: real where known; the coarse LOD keeps the water surface but drops the
        // sea floor, so unknown coastal water stays shallow and unknown open sea gets a deep fill.
        float[] knownVals = new float[w * h];
        int knownN = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (topArr[i] == Integer.MIN_VALUE) continue;
            int floor = floorArr[i];
            if (isWaterPix[i] && floor < topArr[i]) {
                depthMap[i] = topArr[i] - floor;
                depthKnown[i] = true;
                knownVals[knownN++] = depthMap[i];
            }
        }
        float fillDepth = 20f;
        if (knownN > 0) {
            Arrays.sort(knownVals, 0, knownN);
            fillDepth = knownVals[Math.min(knownN - 1, (int) (knownN * 0.9f))];
        }
        // coastal band: water within K pixels of land keeps its shallow colour; farther
        // unknown water is open sea and gets the deep fill value
        final int coastalK = 6;
        boolean[] coastalBand = new boolean[w * h];
        int[] q = new int[w * h];
        int qn = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (topArr[i] == Integer.MIN_VALUE) continue;
            if (!isWaterPix[i]) {
                coastalBand[i] = true;
                q[qn++] = i;
            }
        }
        int[] next = new int[w * h];
        for (int layer = 0; layer < coastalK && qn > 0; layer++) {
            int nn = 0;
            for (int t = 0; t < qn; t++) {
                int idx = q[t];
                int x = idx % w, z = idx / w;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int j = zz * w + xx;
                        if (!coastalBand[j] && isWaterPix[j]) {
                            coastalBand[j] = true;
                            next[nn++] = j;
                        }
                    }
                }
            }
            int[] tmp = q;
            q = next;
            next = tmp;
            qn = nn;
        }
        for (int i = 0; i < pixels.length; i++) {
            if (isWaterPix[i] && !depthKnown[i]) {
                depthMap[i] = coastalBand[i] ? 2f : fillDepth;
                depthKnown[i] = true;
            }
        }

        long tDepth = System.nanoTime();
        // 3-pass separable box blur (sliding window) of the water depth only:
        // approximates a gaussian, O(N) per pass, much faster and smoother than a big kernel
        {
            final int R = 8;
            float[] cur = depthMap;
            float[] hbuf = new float[w * h];
            float[] vbuf = new float[w * h];
            for (int pass = 0; pass < 2; pass++) {
                for (int z = 0; z < h; z++) {
                    int row = z * w;
                    float sum = 0;
                    int cnt = 0;
                    for (int x = 0; x < w && x <= R; x++) {
                        if (isWaterPix[row + x]) { sum += cur[row + x]; cnt++; }
                    }
                    for (int x = 0; x < w; x++) {
                        int idx = row + x;
                        hbuf[idx] = isWaterPix[idx] && cnt > 0 ? sum / cnt : cur[idx];
                        int add = x + R + 1;
                        int rem = x - R;
                        if (add < w && isWaterPix[row + add]) { sum += cur[row + add]; cnt++; }
                        if (rem >= 0 && isWaterPix[row + rem]) { sum -= cur[row + rem]; cnt--; }
                    }
                }
                for (int x = 0; x < w; x++) {
                    float sum = 0;
                    int cnt = 0;
                    for (int z = 0; z < h && z <= R; z++) {
                        int idx = z * w + x;
                        if (isWaterPix[idx]) { sum += hbuf[idx]; cnt++; }
                    }
                    for (int z = 0; z < h; z++) {
                        int idx = z * w + x;
                        vbuf[idx] = isWaterPix[idx] && cnt > 0 ? sum / cnt : hbuf[idx];
                        int add = z + R + 1;
                        int rem = z - R;
                        if (add < h && isWaterPix[add * w + x]) { sum += hbuf[add * w + x]; cnt++; }
                        if (rem >= 0 && isWaterPix[rem * w + x]) { sum -= hbuf[rem * w + x]; cnt--; }
                    }
                }
                float[] tmp2 = cur;
                cur = vbuf;
                vbuf = tmp2;
            }
            depthMap = cur;
        }
        long tBlur = System.nanoTime();
        // land ramp from sea level up to the map's highest point (green -> white)
        int rampLo = sea, rampHi = sea + 820;
        {
            int maxH = Integer.MIN_VALUE;
            for (int i = 0; i < w * h; i++) {
                int top = topArr[i];
                if (top == Integer.MIN_VALUE || isWaterPix[i]) continue;
                if (top > maxH) maxH = top;
            }
            if (maxH != Integer.MIN_VALUE && maxH - sea >= 40) {
                rampLo = sea;
                rampHi = maxH;
            }
        }
        // contour mask from the smoothed land height, then drop small fragments by connected-area size
        int ramLo = sea, ramHi = sea + 820;
        {
            int[] landVals = new int[w * h];
            int n = 0;
            for (int i = 0; i < w * h; i++) {
                if (topArr[i] != Integer.MIN_VALUE && !isWaterPix[i]) landVals[n++] = topArr[i];
            }
            if (n > 100) {
                java.util.Arrays.sort(landVals, 0, n);
                ramLo = landVals[(int) (n * 0.01f)];
                ramHi = landVals[Math.min(n - 1, (int) (n * 0.99f))];
            }
        }
        int contourInterval = Math.max(10, (ramHi - ramLo) / 12);
        boolean[] contourRaw = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            if (topArr[i] == Integer.MIN_VALUE) continue;
            if (isWaterPix[i]) continue; // water: no contours
            int t = smoothTop[i];
            if (t == Integer.MIN_VALUE) continue;
            int x = i % w, z = i / w;
            if (x < w - 1 && smoothTop[i + 1] != Integer.MIN_VALUE && Math.floorDiv(t, contourInterval) != Math.floorDiv(smoothTop[i + 1], contourInterval)) {
                contourRaw[i] = true;
            } else if (z < h - 1 && smoothTop[i + w] != Integer.MIN_VALUE && Math.floorDiv(t, contourInterval) != Math.floorDiv(smoothTop[i + w], contourInterval)) {
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
        long tContour = System.nanoTime();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] imgData = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float step = 2f * pixelBlocks;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int idx = z * w + x;
                int top = topArr[idx];
                if (top == Integer.MIN_VALUE) { imgData[idx] = 0; continue; }
                int floor = floorArr[idx];
                boolean water = isWaterPix[idx];
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
                            shade = 1f + (water ? 0.10f : 0.18f) * d * strength;
                        }
                    }
                }
                shade = Math.max(0.78f, Math.min(1.08f, shade));
                int rgb;
                if (water) {
                    float dep = depthMap[idx];
                    rgb = waterColor(Math.round(dep));
                } else {
                    int effY = sea + (int) ((long) (top - sea) * 820L / Math.max(1, rampHi - sea));
                    rgb = landColor(effY, sea);
                    if (contour[idx]) shade *= 0.70f;
                    boolean coast = (x < w - 1 && floorArr[idx + 1] != Integer.MIN_VALUE && floorArr[idx + 1] < topArr[idx + 1])
                            || (z < h - 1 && floorArr[idx + w] != Integer.MIN_VALUE && floorArr[idx + w] < topArr[idx + w])
                            || (x > 0 && floorArr[idx - 1] != Integer.MIN_VALUE && floorArr[idx - 1] < topArr[idx - 1])
                            || (z > 0 && floorArr[idx - w] != Integer.MIN_VALUE && floorArr[idx - w] < topArr[idx - w]);
                    if (coast) shade *= 0.70f;
                }
                if (z == 1386 && x == 441) {
                    StringBuilder sb2 = new StringBuilder("DBG top=" + top + " shade=" + shade + " contour=" + contour[idx] + " smoothTop=" + smoothTop[idx] + " | neighbors:");
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int j = (z + dz) * w + (x + dx);
                            long pj = pixels[j];
                            int tj = pj == LONG_EMPTY ? -99999 : (int) ((pj >>> 24) & 0xFFFF) - 32768;
                            int lj = pj == LONG_EMPTY ? -1 : (int) ((pj >>> 48) & 0xF);
                            sb2.append(" ").append(tj).append("/l").append(lj);
                        }
                    }
                    System.out.println(sb2);
                }
                int r = clamp((int) (((rgb >> 16) & 0xFF) * shade));
                int g = clamp((int) (((rgb >> 8) & 0xFF) * shade));
                int b = clamp((int) ((rgb & 0xFF) * shade));
                imgData[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        long tMain = System.nanoTime();
        System.out.println("render breakdown: unpack=" + (tUnpack - tR0) / 1_000_000
                + " fill=" + (tFill - tUnpack) / 1_000_000
                + " denoise=" + (tDenoise - tFill) / 1_000_000
                + " smooth=" + (tSmooth - tDenoise) / 1_000_000
                + " depth=" + (tDepth - tSmooth) / 1_000_000
                + " blur=" + (tBlur - tDepth) / 1_000_000
                + " contour=" + (tContour - tBlur) / 1_000_000
                + " main=" + (tMain - tContour) / 1_000_000 + " ms");
        // ---- biome overlay: boundary lines + localised-ish labels ----
        if (false) {
            int[] biomeArr = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                if (pixels[i] == LONG_EMPTY) continue;
                biomeArr[i] = (int) ((pixels[i] >>> 53) & 0x1FF);
            }
            long tb0 = System.nanoTime();
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int idx = z * w + x;
                    if (pixels[idx] == LONG_EMPTY) continue;
                    boolean edge = false;
                    if (x < w - 1 && pixels[idx + 1] != LONG_EMPTY && biomeArr[idx] != biomeArr[idx + 1]) edge = true;
                    else if (z < h - 1 && pixels[idx + w] != LONG_EMPTY && biomeArr[idx] != biomeArr[idx + w]) edge = true;
                    if (edge && (x + z) % 6 < 4) {
                        int c = imgData[idx];
                        int r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
                        imgData[idx] = (((r + 255) / 2) << 16) | (((gg + 255) / 2) << 8) | ((bb + 255) / 2);
                        if (x + 1 < w) {
                            int c2 = imgData[idx + 1];
                            imgData[idx + 1] = (((((c2 >> 16) & 0xFF) + 255) / 2) << 16) | (((((c2 >> 8) & 0xFF) + 255) / 2) << 8) | (((c2 & 0xFF) + 255) / 2);
                        }
                        if (z + 1 < h) {
                            int c3 = imgData[idx + w];
                            imgData[idx + w] = (((((c3 >> 16) & 0xFF) + 255) / 2) << 16) | (((((c3 >> 8) & 0xFF) + 255) / 2) << 8) | (((c3 & 0xFF) + 255) / 2);
                        }
                    }
                }
            }
            long tb1 = System.nanoTime();
            java.awt.Graphics2D g2d = img.createGraphics();
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 22));
            g2d.setColor(java.awt.Color.WHITE);
            boolean[] visited = new boolean[w * h];
            int[] bq = new int[w * h];
            for (int st = 0; st < w * h; st++) {
                if (visited[st] || pixels[st] == LONG_EMPTY) continue;
                int qh = 0, qt = 0;
                bq[qt++] = st;
                visited[st] = true;
                long sumX = 0, sumZ = 0;
                int b0 = biomeArr[st];
                while (qh < qt) {
                    int idx2 = bq[qh++];
                    int x = idx2 % w, z = idx2 / w;
                    sumX += x;
                    sumZ += z;
                    if (x > 0) {
                        int j = idx2 - 1;
                        if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; }
                    }
                    if (x < w - 1) {
                        int j = idx2 + 1;
                        if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; }
                    }
                    if (z > 0) {
                        int j = idx2 - w;
                        if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; }
                    }
                    if (z < h - 1) {
                        int j = idx2 + w;
                        if (!visited[j] && pixels[j] != LONG_EMPTY && biomeArr[j] == b0) { visited[j] = true; bq[qt++] = j; }
                    }
                }
                if (qt >= 300) {
                    String name = BIOME_NAMES.get((long) b0);
                    if (name != null) {
                        String shortName = name.startsWith("minecraft:") ? name.substring(10) : name;
                        int cx = (int) (sumX / qt);
                        int cz = (int) (sumZ / qt);
                        g2d.drawString(shortName, cx - g2d.getFontMetrics().stringWidth(shortName) / 2, cz + 7);
                    }
                }
            }
            long tb2 = System.nanoTime();
            System.out.println("biome overlay: boundary=" + (tb1 - tb0) / 1_000_000 + " ms labels=" + (tb2 - tb1) / 1_000_000 + " ms");
            g2d.dispose();
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
                if (depth <= 3) return (235 << 16) | (243 << 8) | 250;
                if (depth <= 8) return (215 << 16) | (230 << 8) | 245;
                if (depth <= 14) return (192 << 16) | (214 << 8) | 236;
                if (depth <= 22) return (168 << 16) | (198 << 8) | 228;
                if (depth <= 32) return (145 << 16) | (182 << 8) | 220;
                if (depth <= 44) return (122 << 16) | (165 << 8) | 210;
                if (depth <= 58) return (100 << 16) | (148 << 8) | 198;
                if (depth <= 74) return (82 << 16) | (132 << 8) | 186;
                return (66 << 16) | (116 << 8) | 172;
            default:
                if (depth <= 3) return (150 << 16) | (205 << 8) | 222;
                if (depth <= 6) return (130 << 16) | (195 << 8) | 215;
                if (depth <= 10) return (110 << 16) | (182 << 8) | 210;
                if (depth <= 15) return (92 << 16) | (168 << 8) | 205;
                if (depth <= 21) return (74 << 16) | (150 << 8) | 198;
                if (depth <= 28) return (60 << 16) | (132 << 8) | 190;
                if (depth <= 37) return (48 << 16) | (114 << 8) | 178;
                if (depth <= 48) return (38 << 16) | (96 << 8) | 163;
                if (depth <= 61) return (30 << 16) | (78 << 8) | 145;
                if (depth <= 76) return (22 << 16) | (60 << 8) | 125;
                return (14 << 16) | (44 << 8) | 100;
        }
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

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
