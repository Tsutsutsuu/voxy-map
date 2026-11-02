package com.voxymap;

import java.util.Set;

/**
 * Decodes Voxy's live {@code WorldSection.copyData()} array into the same packed column format
 * used by the RocksDB full/incremental pipeline (see {@link VoxyMerge}).
 *
 * <p>Voxy stores one mapping id per voxel in a 32x32x32 array; the compressed RocksDB section
 * stores a LUT and 16-bit LUT indices, so the same scan works on both representations.</p>
 */
public final class VoxySectionDecoder {
    public static final int VOL = 32 * 32 * 32;
    /** block id field of a packed Voxy voxel */
    private static final long BLOCK_MASK = (1L << 20) - 1;
    private static final long AIR_MASK = BLOCK_MASK << 27;

    private VoxySectionDecoder() {
    }

    /**
     * @return 1024 packed columns (x + z*32), or null when the section is not decodable
     */
    public static long[] decode(long key, long[] data, Set<Long> waterIds) {
        if (data == null || data.length < VOL) return null;
        int lvl = (int) ((key >>> 60) & 0xF);
        int sy = (int) ((key << 4) >> 56);
        int scale = 1 << lvl;
        long[] cols = new long[1024];
        java.util.Arrays.fill(cols, VoxyMerge.LONG_EMPTY);
        for (int c = 0; c < 1024; c++) {
            int x = c & 31;
            int z = c >> 5;
            int topY = -1;
            boolean topFluid = false;
            int solidY = -1;
            long topVox = 0;
            for (int y = 31; y >= 0; y--) {
                long voxel = data[(y << 10) | (z << 5) | x];
                if ((voxel & AIR_MASK) == 0) continue;
                boolean fluid = waterIds != null && waterIds.contains((voxel >>> 27) & BLOCK_MASK);
                if (topY < 0) {
                    topY = y;
                    topFluid = fluid;
                    topVox = voxel;
                }
                if (!fluid && y > solidY) solidY = y;
            }
            if (topY < 0 && solidY < 0) continue;
            int topWorld = topY >= 0 ? (sy * 32 + topY) * scale + scale - 1 : VoxyMerge.NO_TOP;
            // a solid column reaching the section ceiling is a buried slice, not terrain
            if (topY >= 0 && topY == 31 && !topFluid) topWorld = VoxyMerge.NO_TOP;
            int solidWorld = solidY >= 0 ? (sy * 32 + solidY) * scale + scale - 1 : VoxyMerge.NO_SOLID;
            long out = (((long) (topWorld + 32768)) << 24) | ((solidWorld + 32768) & 0xFFFF);
            if (topFluid) out |= (1L << 52);
            out |= ((long) (((topVox >>> 47) & 0x1FF))) << 53;
            cols[c] = out;
        }
        return cols;
    }
}
