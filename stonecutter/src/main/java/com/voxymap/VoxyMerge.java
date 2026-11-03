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
            curWater = topFluid;
            curLvl = lvl;
            // keep the decoded solid height: a buried slice's ceiling is the only height
            // information available when no section above holds the real surface
            curFloor = floor;
            curBiome = biome;
        } else {
            curTop = (int) ((prevPacked >>> 24) & 0xFFFF) - 32768;
            curWater = (prevPacked & (1L << 52)) != 0;
            curLvl = (int) ((prevPacked >>> 48) & 0xF);
            curFloor = (int) (prevPacked & 0xFFFF) - 32768;
            curBiome = (int) ((prevPacked >>> 53) & 0x1FF);
            if (top != NO_TOP) {
                // Finest known level wins whenever it has a real surface, even when a coarse
                // mip is higher. A coarse surface is only usable when the finer data is a
                // buried slice (NO_TOP) and the coarse top is above the known solid floor.
                // The old rule `lvl > curLvl && curTop < 0` also let coarse water overwrite
                // valid sub-sea-level land, creating fake water pits.
                boolean accept;
                if (curTop == NO_TOP) {
                    accept = curFloor == NO_SOLID || top >= curFloor;
                } else if (lvl < curLvl) {
                    accept = true;
                } else if (lvl == curLvl) {
                    accept = top > curTop;
                } else {
                    accept = false;
                }
                if (accept) {
                    curTop = top;
                    curWater = topFluid;
                    curLvl = lvl;
                    curBiome = biome;
                }
            }
        }
        // same-level highest solid above the current top: the top is a cave/underground
        // ceiling, so the real surface is the top of that solid
        if (curTop != NO_TOP && solidHere != Integer.MIN_VALUE && solidHere > curTop && lvl == curLvl) {
            curTop = solidHere;
            curWater = false;
            curLvl = lvl;
            curBiome = biome;
        }
        // A "buried slice" (solid up to the section ceiling) still knows a lower bound for
        // the surface. Keep the highest such solid: when the surface happens to lie in the
        // section's top row and no section above has data, dropping the column produced the
        // black ring of holes on high mountains.
        if (curTop == NO_TOP && solidHere != Integer.MIN_VALUE
                && (curFloor == NO_SOLID || solidHere > curFloor)) {
            curFloor = solidHere;
        }
        // floor: highest solid anywhere in the column (sea floor for water)
        if (curTop != NO_TOP && solidHere != Integer.MIN_VALUE && solidHere < curTop) {
            int prevF = curFloor == NO_SOLID ? Integer.MIN_VALUE : curFloor;
            if (prevF == Integer.MIN_VALUE || solidHere > prevF) curFloor = solidHere;
        }
        if (curTop != NO_TOP && !curWater) curFloor = curTop;
        if (curFloor == Integer.MIN_VALUE) curFloor = NO_SOLID;
        long out = ((long) (curTop + 32768)) << 24 | ((curFloor + 32768) & 0xFFFF);
        if (curWater) out |= (1L << 52);
        out |= ((long) curBiome) << 53;
        // the merge above compares LOD levels; the level must be stored for later sections
        out |= ((long) curLvl) << 48;
        return out;
    }
}
