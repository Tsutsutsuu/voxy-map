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
import java.util.List;

/**
 * Voxy LOD -> height-map PNG, v6: full multi-level merge with areal fill.
 *
 * Fixes the "stippled" coarse levels: a level-L voxel stands for 2^L blocks, so it
 * must paint its whole 2^L x 2^L block footprint (all covered pixels), not only the
 * centre pixel. Finer levels still win per pixel; coarser levels only fill empties.
 */
public class VoxyMapPoC6 {
    static final int VOL = 32 * 32 * 32;
    static final int EMPTY = 0;

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

        long minWX = Long.MAX_VALUE, maxWX = Long.MIN_VALUE;
        long minWZ = Long.MAX_VALUE, maxWZ = Long.MIN_VALUE;
        int[] sectionCount = new int[16];
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
                        sectionCount[lvl]++;
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
        int w = (int) ((maxWX - minWX + pixelBlocks - 1) / pixelBlocks);
        int h = (int) ((maxWZ - minWZ + pixelBlocks - 1) / pixelBlocks);
        System.out.println("keys=" + keys + " bounds=[" + minWX + ".." + maxWX + "]x[" + minWZ + ".." + maxWZ + "] px=" + w + "x" + h);
        int[] pixels = new int[w * h];

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
                                parseSection(data, lvl, key, pixels, w, h, pixelBlocks, minWX, minWZ);
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

        int n = 0;
        for (int p : pixels) if (p != EMPTY) n++;
        int[] heights = new int[n];
        int k = 0;
        for (int p : pixels) if (p != EMPTY) heights[k++] = (p & 0xFFFF) - 32768;
        Arrays.sort(heights);
        int lo = n == 0 ? 0 : heights[Math.max(0, (int) (n * 0.005))];
        int hi = n == 0 ? 0 : heights[Math.min(n - 1, (int) (n * 0.995))];
        StringBuilder sb = new StringBuilder("sections:");
        for (int l = 0; l <= maxLevel; l++) sb.append(" L").append(l).append("=").append(perLevel[l]);
        sb.append(" pixels=").append(n).append("/").append(w * h).append(" render=").append(lo).append("..").append(hi);
        System.out.println(sb);

        BufferedImage img = render(pixels, w, h, lo, hi, seaLevel);
        ImageIO.write(img, "png", new File(out));
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

    static void parseSection(byte[] d, int lvl, long key, int[] pixels, int w, int h, int pixelBlocks, long minWX, long minWZ) {
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

        int[] top = new int[32 * 32];
        Arrays.fill(top, -1);
        for (int i = 0; i < VOL; i++) {
            int lutIdx = Short.toUnsignedInt(bb.getShort(base + i * 2));
            if (lutIdx >= lutSize) continue;
            long voxel = bb.getLong(lutBase + lutIdx * 8);
            if ((voxel & airMask) == 0) continue;
            int localX = i & 31;
            int localZ = (i >> 5) & 31;
            int localY = (i >> 10) & 31;
            int c = localZ * 32 + localX;
            if (localY > top[c]) top[c] = localY;
        }
        int scale = 1 << lvl;
        for (int c = 0; c < 32 * 32; c++) {
            if (top[c] < 0) continue;
            int localX = c & 31;
            int localZ = c >> 5;
            long wx0 = (long) (sx * 32 + localX) * scale;
            long wz0 = (long) (sz * 32 + localZ) * scale;
            int wy = (sy * 32 + top[c]) * scale + scale - 1;
            int px0 = (int) ((wx0 - minWX) / pixelBlocks);
            int px1 = (int) ((wx0 + scale - 1 - minWX) / pixelBlocks);
            int pz0 = (int) ((wz0 - minWZ) / pixelBlocks);
            int pz1 = (int) ((wz0 + scale - 1 - minWZ) / pixelBlocks);
            int packed = (lvl << 24) | ((wy + 32768) & 0xFFFF);
            for (int pz = pz0; pz <= pz1; pz++) {
                if (pz < 0 || pz >= h) continue;
                int row = pz * w;
                for (int px = px0; px <= px1; px++) {
                    if (px < 0 || px >= w) continue;
                    int idx = row + px;
                    int prev = pixels[idx];
                    if (prev == EMPTY) {
                        pixels[idx] = packed;
                    } else {
                        int prevLvl = (prev >>> 24) & 0xF;
                        if (lvl < prevLvl) {
                            pixels[idx] = packed;
                        } else if (lvl == prevLvl) {
                            int prevY = (prev & 0xFFFF) - 32768;
                            if (wy > prevY) pixels[idx] = packed;
                        }
                    }
                }
            }
        }
    }

    static BufferedImage render(int[] pixels, int w, int h, int lo, int hi, int sea) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int p = pixels[z * w + x];
                if (p == EMPTY) img.setRGB(x, z, 0);
                else img.setRGB(x, z, color((p & 0xFFFF) - 32768, lo, hi, sea));
            }
        return img;
    }

    static int color(int y, int lo, int hi, int sea) {
        if (y <= sea) {
            int d = Math.min(sea - y, 80);
            return (clamp(25) << 16) | (clamp(55 + d / 3) << 8) | clamp(130 + d);
        }
        float t = hi <= sea ? 0 : (float) (y - sea) / (hi - sea);
        t = Math.max(0, Math.min(1, t));
        if (t < 0.15f) return (60 << 16) | (145 << 8) | (75);
        if (t < 0.35f) return (95 << 16) | (155 << 8) | (80);
        if (t < 0.55f) return (125 << 16) | (135 << 8) | (80);
        if (t < 0.75f) return (145 << 16) | (130 << 8) | (95);
        if (t < 0.9f) return (165 << 16) | (160 << 8) | (150);
        return (240 << 16) | (245 << 8) | (250);
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
