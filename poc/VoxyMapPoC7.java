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
 * Voxy LOD -> colored height-map PNG, v7.
 *
 * v6 multi-level merge + areal fill, plus:
 *  - finer colour ramp (depth-graded water, beach, grass, hills, mountains, snow)
 *  - hillshade from the height field (fixed NW light) for 3D relief
 */
public class VoxyMapPoC7 {
    static final int VOL = 32 * 32 * 32;
    static final int EMPTY = 0;
    // palette stops: {y - seaLevel, r, g, b}
    static final int[][] PALETTE = {
            {-120, 8, 28, 70},
            {-70, 12, 40, 95},
            {-40, 20, 60, 125},
            {-18, 33, 85, 155},
            {-6, 48, 108, 178},
            {-1, 62, 128, 192},
            {0, 205, 195, 160},
            {3, 190, 180, 140},
            {8, 118, 170, 92},
            {18, 98, 158, 78},
            {36, 86, 142, 70},
            {60, 104, 146, 72},
            {95, 118, 140, 76},
            {135, 138, 138, 84},
            {175, 150, 130, 96},
            {215, 158, 148, 122},
            {260, 166, 160, 148},
            {310, 178, 174, 166},
            {380, 178, 176, 170},
            {450, 192, 192, 188},
            {520, 205, 206, 204},
            {600, 220, 222, 222},
            {680, 238, 240, 242},
            {760, 252, 253, 255},
    };

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
        StringBuilder sb = new StringBuilder("sections:");
        for (int l = 0; l <= maxLevel; l++) sb.append(" L").append(l).append("=").append(perLevel[l]);
        sb.append(" pixels=").append(n).append("/").append(w * h);
        System.out.println(sb);

        BufferedImage img = render(pixels, w, h, seaLevel, pixelBlocks);
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
                        if (lvl < prevLvl) pixels[idx] = packed;
                        else if (lvl == prevLvl) {
                            int prevY = (prev & 0xFFFF) - 32768;
                            if (wy > prevY) pixels[idx] = packed;
                        }
                    }
                }
            }
        }
    }

    static BufferedImage render(int[] pixels, int w, int h, int sea, int pixelBlocks) {
        int[] hs = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            hs[i] = pixels[i] == EMPTY ? Integer.MIN_VALUE : (pixels[i] & 0xFFFF) - 32768;
        }
        // smooth height field for hillshade (3x3 box, EMPTY ignored)
        int[] smooth = new int[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, cnt = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < 0 || zz >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int v = hs[zz * w + xx];
                        if (v != Integer.MIN_VALUE) { sum += v; cnt++; }
                    }
                }
                smooth[z * w + x] = cnt == 0 ? Integer.MIN_VALUE : sum / cnt;
            }
        }
        // light from north-west: (-0.55, 0.70, -0.55)
        final float lx = -0.55f, ly = 0.70f, lz = -0.55f;
        final float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float step = 2f * pixelBlocks;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int idx = z * w + x;
                int y = hs[idx];
                if (y == Integer.MIN_VALUE) { img.setRGB(x, z, 0); continue; }
                int[] base = palette(y, sea);
                float shade = 1f;
                if (x > 0 && z > 0 && x < w - 1 && z < h - 1) {
                    int hl = smooth[idx - 1], hr = smooth[idx + 1], hu = smooth[idx - w], hd = smooth[idx + w];
                    if (hl != Integer.MIN_VALUE && hr != Integer.MIN_VALUE && hu != Integer.MIN_VALUE && hd != Integer.MIN_VALUE) {
                        float dzdx = (hr - hl) / step;
                        float dzdy = (hd - hu) / step;
                        float slope = (float) Math.sqrt(dzdx * dzdx + dzdy * dzdy);
                        if (slope > 0.08f) {
                            float nx = -dzdx, ny = 1f, nz = -dzdy;
                            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                            float d = (nx * lx + ny * ly + nz * lz) / (nl * ll);
                            float strength = Math.min(1f, (slope - 0.08f) / 0.6f);
                            shade = 1f + 0.18f * d * strength;
                            if (y <= sea) shade = 1f + 0.04f * d;
                        }
                    }
                }
                shade = Math.max(0.78f, Math.min(1.08f, shade));
                int r = clamp((int) (base[0] * shade));
                int g = clamp((int) (base[1] * shade));
                int b = clamp((int) (base[2] * shade));
                img.setRGB(x, z, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /** Interpolate the palette by height above sea level. Water is handled separately. */
    static int[] palette(int y, int sea) {
        if (y <= sea) {
            // depth-graded water: shallow (62,128,192) -> deep (8,28,70)
            int d = sea - y;
            float t = Math.min(1f, d / 80f);
            return new int[]{ (int) (62 + (8 - 62) * t), (int) (128 + (28 - 128) * t), (int) (192 + (70 - 192) * t) };
        }
        int hh = y - sea;
        if (hh <= PALETTE[0][0]) return new int[]{PALETTE[0][1], PALETTE[0][2], PALETTE[0][3]};
        for (int i = 0; i < PALETTE.length - 1; i++) {
            int h0 = PALETTE[i][0], h1 = PALETTE[i + 1][0];
            if (hh >= h0 && hh <= h1) {
                float t = (float) (hh - h0) / (h1 - h0);
                return new int[]{
                        (int) (PALETTE[i][1] + (PALETTE[i + 1][1] - PALETTE[i][1]) * t),
                        (int) (PALETTE[i][2] + (PALETTE[i + 1][2] - PALETTE[i][2]) * t),
                        (int) (PALETTE[i][3] + (PALETTE[i + 1][3] - PALETTE[i][3]) * t)
                };
            }
        }
        int last = PALETTE.length - 1;
        return new int[]{PALETTE[last][1], PALETTE[last][2], PALETTE[last][3]};
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
