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
import java.util.List;
import java.util.Map;

/**
 * Voxy LOD -> height-map PNG, v4: multi-level merge.
 * Reads levels [minLevel..maxLevel] and prefers the finest level per pixel,
 * falling back to coarser levels where the fine data is missing (SeedGen only
 * writes fine levels near the player / where ground exists).
 */
public class VoxyMapPoC4 {
    static final int VOL = 32 * 32 * 32;

    public static void main(String[] args) throws Exception {
        String path = args[0];
        String out = args[1];
        int minLevel = Integer.parseInt(args[2]);
        int maxLevel = Integer.parseInt(args[3]);
        int pixelBlocks = Integer.parseInt(args[4]);
        int seaLevel = args.length > 5 ? Integer.parseInt(args[5]) : 63;

        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("world_sections".getBytes()),
                new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), path, cfds, handles);

        // pixel key -> packed (level<<24 | (y+32768))
        Map<Long, Integer> pixels = new HashMap<>();
        int keys = 0;
        int[] sectionCount = new int[16];
        try (RocksIterator it = db.newIterator(handles.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                keys++;
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int) ((key >>> 60) & 0xF);
                    if (lvl >= minLevel && lvl <= maxLevel) {
                        byte[] val = it.value();
                        if (val != null && val.length > 16) {
                            byte[] data = decompress(val);
                            if (data != null && data.length >= 16 + VOL * 2) {
                                sectionCount[lvl]++;
                                parseSection(data, lvl, key, pixels, pixelBlocks);
                            }
                        }
                    }
                }
                it.next();
            }
        } finally {
            for (ColumnFamilyHandle h : handles) h.close();
            db.close();
        }

        Map<Long, Integer> height = new HashMap<>(pixels.size());
        for (Map.Entry<Long, Integer> e : pixels.entrySet()) {
            height.put(e.getKey(), (e.getValue() & 0xFFFF) - 32768);
        }
        int n = height.size();
        int[] ys = new int[n];
        int idx = 0;
        for (int y : height.values()) ys[idx++] = y;
        Arrays.sort(ys);
        int lo = n == 0 ? 0 : ys[Math.max(0, (int) (n * 0.005))];
        int hi = n == 0 ? 0 : ys[Math.min(n - 1, (int) (n * 0.995))];
        StringBuilder sb = new StringBuilder("keys=" + keys + " pixels=" + n + " sections:");
        for (int l = minLevel; l <= maxLevel; l++) sb.append(" L").append(l).append("=").append(sectionCount[l]);
        sb.append(" y=").append(n == 0 ? "-" : ys[0] + ".." + ys[n - 1]).append(" render=").append(lo).append("..").append(hi);
        System.out.println(sb);
        render(height, out, lo, hi, seaLevel);
        System.out.println("written " + out);
    }

    static byte[] decompress(byte[] in) throws Exception {
        try (ZstdInputStream z = new ZstdInputStream(new ByteArrayInputStream(in))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            z.transferTo(bos);
            return bos.toByteArray();
        }
    }

    static void parseSection(byte[] d, int lvl, long key, Map<Long, Integer> out, int pixelBlocks) {
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
            int wx = (sx * 32 + localX) * scale + scale / 2;
            int wz = (sz * 32 + localZ) * scale + scale / 2;
            int wy = (sy * 32 + top[c]) * scale + scale - 1;
            int px = Math.floorDiv(wx, pixelBlocks);
            int pz = Math.floorDiv(wz, pixelBlocks);
            long pk = ((long) pz << 32) | (px & 0xFFFFFFFFL);
            int packed = (lvl << 24) | ((wy + 32768) & 0xFFFF);
            Integer prev = out.get(pk);
            if (prev == null) {
                out.put(pk, packed);
            } else {
                int prevLvl = (prev >>> 24) & 0xF;
                if (lvl < prevLvl) {
                    out.put(pk, packed); // finer level wins
                } else if (lvl == prevLvl && ((packed & 0xFFFF) - 32768) > ((prev & 0xFFFF) - 32768)) {
                    out.put(pk, packed); // same level: higher wins
                }
            }
        }
    }

    static void render(Map<Long, Integer> height, String out, int lo, int hi, int sea) throws Exception {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : height.keySet()) {
            int pz = (int) (k >> 32);
            int px = (int) (k & 0xFFFFFFFFL);
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minZ = Math.min(minZ, pz); maxZ = Math.max(maxZ, pz);
        }
        int w = maxX - minX + 1, h = maxZ - minZ + 1;
        System.out.println("bounds x=" + minX + ".." + maxX + " z=" + minZ + ".." + maxZ + " size=" + w + "x" + h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (Map.Entry<Long, Integer> e : height.entrySet()) {
            int pz = (int) (e.getKey() >> 32);
            int px = (int) (e.getKey() & 0xFFFFFFFFL);
            img.setRGB(px - minX, pz - minZ, color(e.getValue(), lo, hi, sea));
        }
        ImageIO.write(img, "png", new File(out));
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
