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
 *
 * Storage layout (voxy 0.2.x):
 *  - RocksDB column family "world_sections"
 *  - key: 8 bytes, big-endian section id = (lvl<<60)|((y&0xFF)<<52)|((z&0xFFFFFF)<<28)|((x&0xFFFFFF)<<4)
 *  - value: ZSTD frame of SaveLoadSystem3 data:
 *      long key, long metadata, short[32768] lutIndex, long[lutSize] lut
 *    lutSize = metadata & 0xFFFF
 *  - voxel = lut[lutIndex[i]]; air when (voxel & (((1L<<20)-1)<<27)) == 0
 *  - index i = (y<<10)|(z<<5)|x, SECTION_VOLUME = 32^3
 */
public class VoxyMapPoC {
    static final int VOL = 32 * 32 * 32;

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
        RocksDB db;
        try {
            db = RocksDB.openReadOnly(dbOpts, path, cfds, handles);
            System.out.println("opened read-only");
        } catch (Throwable t) {
            System.out.println("read-only open failed (" + t.getMessage() + "), trying writable copy");
            handles = new ArrayList<>();
            db = RocksDB.open(dbOpts, path, cfds, handles);
        }
        ColumnFamilyHandle wsec = handles.get(1);

        Map<Long, Integer> height = new HashMap<>();
        int keys = 0, sections = 0, cols = 0;
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
                                cols += parseSection(data, lvl, key, height, pixelBlocks);
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
        System.out.println("keys=" + keys + " lvl" + level + " sections=" + sections + " columns=" + cols + " pixels=" + height.size());
        if (height.isEmpty()) {
            System.out.println("no data");
            return;
        }
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

    static int parseSection(byte[] d, int lvl, long key, Map<Long, Integer> out, int pixelBlocks) {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        bb.getLong(); // key
        long meta = bb.getLong();
        int lutSize = (int) (meta & 0xFFFF);
        int base = 16;
        int lutBase = base + VOL * 2;
        if (lutSize <= 0 || lutSize > (1 << 16) || lutBase + lutSize * 8L > d.length) return 0;

        int sx = (int) ((key << 36) >> 40);
        int sy = (int) ((key << 4) >> 56);
        int sz = (int) ((key << 12) >> 40);
        final long AIR = 0L;
        long airMask = ((1L << 20) - 1) << 27;

        int[] top = new int[32 * 32];
        Arrays.fill(top, -1);
        for (int i = 0; i < VOL; i++) {
            int lutIdx = Short.toUnsignedInt(bb.getShort(base + i * 2));
            if (lutIdx >= lutSize) continue;
            long voxel = bb.getLong(lutBase + lutIdx * 8);
            if ((voxel & airMask) == AIR) continue;
            int localX = i & 31;
            int localZ = (i >> 5) & 31;
            int localY = (i >> 10) & 31;
            int c = localZ * 32 + localX;
            if (localY > top[c]) top[c] = localY;
        }
        int scale = 1 << lvl;
        int added = 0;
        for (int c = 0; c < 32 * 32; c++) {
            if (top[c] < 0) continue;
            int localX = c & 31;
            int localZ = c >> 5;
            int wx = (sx * 32 + localX) * scale;
            int wz = (sz * 32 + localZ) * scale;
            int wy = (sy * 32 + top[c]) * scale + scale - 1;
            int px = Math.floorDiv(wx, pixelBlocks);
            int pz = Math.floorDiv(wz, pixelBlocks);
            long pk = ((long) pz << 32) | (px & 0xFFFFFFFFL);
            Integer prev = out.get(pk);
            if (prev == null || wy > prev) out.put(pk, wy);
            added++;
        }
        return added;
    }

    static void render(Map<Long, Integer> height, String out) throws Exception {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Map.Entry<Long, Integer> e : height.entrySet()) {
            int pz = (int) (e.getKey() >> 32);
            int px = (int) (long) e.getKey();
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minZ = Math.min(minZ, pz); maxZ = Math.max(maxZ, pz);
            minY = Math.min(minY, e.getValue()); maxY = Math.max(maxY, e.getValue());
        }
        int w = maxX - minX + 1, h = maxZ - minZ + 1;
        System.out.println("bounds x=" + minX + ".." + maxX + " z=" + minZ + ".." + maxZ + " y=" + minY + ".." + maxY + " size=" + w + "x" + h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (Map.Entry<Long, Integer> e : height.entrySet()) {
            int pz = (int) (e.getKey() >> 32);
            int px = (int) (long) e.getKey();
            int y = e.getValue();
            float t = maxY == minY ? 0.5f : (float) (y - minY) / (maxY - minY);
            int rgb = color(t, y);
            img.setRGB(px - minX, pz - minZ, rgb);
        }
        ImageIO.write(img, "png", new File(out));
    }

    static int color(float t, int y) {
        // low: deep water blue, mid: green, high: grey/white
        if (y < 62) {
            int b = 120 + (int) (t * 80);
            return (0 << 16) | (40 << 8) | b;
        }
        if (t < 0.35f) {
            int g = 90 + (int) (t / 0.35f * 70);
            return (60 << 16) | (g << 8) | (60);
        }
        if (t < 0.7f) {
            int g = 160 + (int) ((t - 0.35f) / 0.35f * 60);
            return (110 << 16) | (g << 8) | (100);
        }
        int v = 190 + (int) ((t - 0.7f) / 0.3f * 60);
        return (v << 16) | (v << 8) | (v);
    }
}
