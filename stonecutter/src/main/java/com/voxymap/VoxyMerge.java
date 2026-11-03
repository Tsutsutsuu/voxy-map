package com.voxymap;

/**
 * Pure per-pixel merge rule for Voxy LOD sections.
 *
 * <p>Extracted from {@code MapData.flushSection} so the full rebuild and the incremental refresh
 * share exactly one implementation (and so an offline tool can verify it without Minecraft
 * classes). A packed column is:</p>
 *
 * <pre>
 * bits  0..15  floor height + 32768   (NO_SOLID sentinel = 0)
 * bits 24..39  top height + 32768     (NO_TOP sentinel = 1)
 * bit  48..51  LOD level that supplied the current top
 * bit  52      water flag
 * bit  53..61  biome id (9 bits)
 * </pre>
 */
public final class VoxyMerge {
    public static final long LONG_EMPTY = Long.MIN_VALUE;
    public static final int NO_SOLID = -32768;
    public static final int NO_TOP = -32767;

    private VoxyMerge() {
    }

    /**
     * Merges one decoded section column into the aggregate pixel value.
     *
     * @param prevPacked current in-memory pixel (or {@link #LONG_EMPTY})
     * @param lvl        LOD level of the section supplying this column
     * @param top        section's surface height, or {@link #NO_TOP} for a buried slice
     * @param floor      section's highest solid height, or {@link #NO_SOLID}
     * @param topFluid   whether the surface voxel is water
     * @param biome      biome id decoded from the section
     */
    public static long mergeColumn(long prevPacked, int lvl, int top, int floor,
                                   boolean topFluid, int biome) {
        int solidHere = floor != NO_SOLID ? floor : Integer.MIN_VALUE;
        int curTop;
        boolean curWater;
        int curLvl;
        int curFloor;
        int curBiome;
        if (prevPacked == LONG_EMPTY) {
            curTop = top;
            curWater = top != NO_TOP && topFluid;
            curLvl = lvl;
            curFloor = floor;
            curBiome = biome;
        } else {
            curTop = (int) ((prevPacked >>> 24) & 0xFFFF) - 32768;
            curWater = (prevPacked & (1L << 52)) != 0;
            curLvl = (int) ((prevPacked >>> 48) & 0xF);
            curFloor = (int) (prevPacked & 0xFFFF) - 32768;
            curBiome = (int) ((prevPacked >>> 53) & 0x1FF);

            // The map only needs two things from a column: its highest solid height and whether
            // its *finest* real surface is water. A top is never chosen by comparing heights across
            // LODs - coarse mip tops are maxima over a cell and produce plateaus when mixed into
            // fine terrain.
            if (top != NO_TOP && lvl < curLvl) {
                curLvl = lvl;
                curTop = top;
                curWater = topFluid;
                curBiome = biome;
            } else if (top != NO_TOP && lvl == curLvl) {
                if (top > curTop) {
                    curTop = top;
                    curWater = topFluid;
                    curBiome = biome;
                }
            } else if (top == NO_TOP && lvl < curLvl) {
                // a finer level exists here but only as a buried slice: the finest level has no
                // visible surface, so do not inherit a coarser water surface
                curLvl = lvl;
                curTop = NO_TOP;
                curWater = false;
                curBiome = biome;
            }
        }

        // highest solid over all LODs is the reliable terrain height; the finest real surface only
        // supplies the water flag (and its water height)
        if (solidHere != Integer.MIN_VALUE
                && (curFloor == NO_SOLID || solidHere > curFloor)) {
            curFloor = solidHere;
        }
        if (!curWater) {
            curTop = curFloor != NO_SOLID ? curFloor : NO_TOP;
        } else if (curFloor != NO_SOLID && curFloor >= curTop) {
            curFloor = curTop - 1;
        }
        if (curFloor == Integer.MIN_VALUE) curFloor = NO_SOLID;
        long out = ((long) (curTop + 32768)) << 24 | ((curFloor + 32768) & 0xFFFF);
        if (curWater) out |= (1L << 52);
        out |= ((long) curBiome) << 53;
        out |= ((long) Math.min(15, Math.max(0, curLvl))) << 48;
        return out;
    }
}
