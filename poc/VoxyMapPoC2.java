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
 * Minimal offline PoC: read Voxy's RocksDB LOD store and render a height-map PNG.
 * v2: fixed world height range (-64..320) + water/land coloring.
 */
public class VoxyMapPoC2 {
    static final int VOL = 32 * 32 * 32;
    static final int WORLD_MIN_Y = -64;
    static final int WORLD_MAX_Y = 320;
    static final int SEA_LEVEL = 63;

    public static void main(String[] args) throws Exception {
        String path = args[0];
        String out = args[1];
        int level = Integer.parseInt(args[2]);
        int pixelBlocks = Integer.parseInt(args[3]);

        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("world_sections".getBytes()),
                new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        DBOptions dbOpts = new DBOptions().setCreateIfMissing(false);
        RocksDB db = RocksDB.openReadOnly(dbOpts, path, cfds, handles);
        ColumnFamilyHandle wsec = handles.get(1);

        Map<Long, Integer> height = new HashMap<>();
        int keys = 0, sections = 0;
        try (RocksIterator it = db.newIterator(wsec)) {
            it.seekToFirst();
            while (it.isValid()) {
                keys++;
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int) ((key >>> 60) & 0xF);
                    if (lvl == level) {
                        byte[] val = it.value();
                        if (val != null && val.length > 16) {
                            byte[] data = decompress(val);
                            if (data != null && data.length >= 16 + VOL * 2) {
                                sections++;
                                parseSection(data, lvl, key, height, pixelBlocks);
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
        System.out.println("keys=" + keys + " lvl" + level + " sections=" + sections + " pixels=" + height.size());
        render(height, out);
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
        bb.getLong(); // key
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
            if (wy < WORLD_MIN_Y || wy > WORLD_MAX_Y) continue;
            int px = Math.floorDiv(wx, pixelBlocks);
            int pz = Math.floorDiv(wz, pixelBlocks);
            long pk = ((long) pz << 32) | (px & 0xFFFFFFFFL);
            Integer prev = out.get(pk);
            if (prev == null || wy > prev) out.put(pk, wy);
        }
    }

    static void render(Map<Long, Integer> height, String out) throws Exception {
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
            img.setRGB(px - minX, pz - minZ, color(e.getValue()));
        }
        ImageIO.write(img, "png", new File(out));
    }

    static int color(int y) {
        if (y <= SEA_LEVEL) {
            int d = Math.min(SEA_LEVEL - y, 60);
            int r = 20, g = 60 + d / 2, b = 120 + d;
            return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
        int h = y - SEA_LEVEL;
        if (h < 12) return (60 << 16) | (140 << 8) | (70);
        if (h < 40) return (90 << 16) | (150 << 8) | (80);
        if (h < 80) return (120 << 16) | (130 << 8) | (80);
        if (h < 130) return (140 << 16) | (125 << 8) | (90);
        if (h < 180) return (150 << 16) | (150 << 8) | (150);
        return (235 << 16) | (240 << 8) | (245);
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
