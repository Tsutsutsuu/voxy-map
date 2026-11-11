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
 * bits 40..47  surface material class slot (0 = unknown; "blocks" style only)
 * bit  48..51  LOD level that supplied the current top
 * bit  52      water flag
 * bit  53..61  biome id (9 bits)
 * </pre>
 *
 * <p>The merge also guarantees a semantic invariant: a column's top can never be a water surface
 * that lies <em>below</em> its highest solid block. Enclosed water bodies (cave rivers, aquifers,
 * underground lakes) are covered by solid rock, so the visible surface is that rock; the packed
 * pixel becomes a dry land pixel at {@code floor} with the ceiling block's material slot/biome.</p>
 */
public final class VoxyMerge {
    public static final long LONG_EMPTY = Long.MIN_VALUE;
    /**
     * Packed-column marker for "this section contains only air at this column", as opposed to
     * {@link #LONG_EMPTY} = "this section has no data for the column at all". Decoders emit it so
     * the merge can tell an empty section above a buried slice (the slice's ceiling is then the real
     * surface) from a missing section above it (coarser LODs may still supply the fallback height).
     */
    public static final long EMPTY_COLUMN = Long.MIN_VALUE + 1;
    public static final int NO_SOLID = -32768;
    public static final int NO_TOP = -32767;

    private VoxyMerge() {
    }

    /**
     * Merges an all-air section column into the aggregate pixel.
     *
     * <p>Only one state is affected: when the finest solid level currently looks like a "buried
     * slice" (its top voxel reached the section ceiling) and this empty section starts above that
     * ceiling, the ceiling block is the real terrain surface - there is nothing above it. In that
     * case coarser LOD solids must no longer raise the fallback height: a lvl4 mip solid is a
     * maximum over its 16x16 cell and can belong to a neighbouring hill, which is exactly how a
     * shoreline column ended up at y=112 instead of y=63.</p>
     *
     * @param sectionMinWorldY world Y of this section's lowest block (empty and above the ceiling)
     */
    public static long mergeEmptyColumn(long prevPacked, int lvl, int sectionMinWorldY) {
        if (prevPacked == LONG_EMPTY) return prevPacked;
        boolean curWater = (prevPacked & (1L << 52)) != 0;
        // enclosed water is already demoted to dry land by the water invariant in mergeColumn
        if (curWater) return prevPacked;
        boolean buried = (prevPacked & (1L << 20)) != 0;
        int solidLvl = (int) ((prevPacked >>> 16) & 0xF);
        int floor = (int) (prevPacked & 0xFFFF) - 32768;
        if (!buried || solidLvl != lvl || floor == NO_SOLID || sectionMinWorldY <= floor) {
            return prevPacked;
        }
        // lock the ceiling as the real surface: coarser solids can no longer raise it
        return prevPacked & ~(1L << 20);
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
     * @param slot       surface material class of this section's top voxel (0 = unknown)
     */
    public static long mergeColumn(long prevPacked, int lvl, int top, int floor,
                                   boolean topFluid, int biome, int slot) {
        int solidHere = floor != NO_SOLID ? floor : Integer.MIN_VALUE;
        int curTop;
        boolean curWater;
        int curLvl;
        int curFloor;
        int curBiome;
        int curSlot;
        /** material of the block at {@link #curFloor}: what a dry column actually shows. */
        int curFloorSlot;
        /** biome of the block at {@link #curFloor} (used when an enclosed water surface is demoted). */
        int curFloorBiome;
        int curSolidLvl;
        boolean curSolidBuried;
        if (prevPacked == LONG_EMPTY) {
            curTop = top;
            curWater = top != NO_TOP && topFluid;
            curLvl = lvl;
            curFloor = floor;
            curBiome = biome;
            curFloorBiome = biome;
            // A buried slice's "top voxel" is its ceiling solid, i.e. the block the dry fallback
            // height will show; a water surface's slot is WATER, so its seabed material is unknown.
            curSlot = top != NO_TOP ? slot : 0;
            curFloorSlot = curWater || floor == NO_SOLID ? 0 : slot;
            curSolidLvl = lvl;
            curSolidBuried = top == NO_TOP;
        } else {
            curTop = (int) ((prevPacked >>> 24) & 0xFFFF) - 32768;
            curWater = (prevPacked & (1L << 52)) != 0;
            curLvl = (int) ((prevPacked >>> 48) & 0xF);
            curFloor = (int) (prevPacked & 0xFFFF) - 32768;
            curBiome = (int) ((prevPacked >>> 53) & 0x1FF);
            curSlot = (int) ((prevPacked >>> 40) & 0xFF);
            // on dry pixels the stored slot is exactly the floor block; on water pixels it is WATER
            curFloorSlot = curWater ? 0 : curSlot;
            curFloorBiome = curBiome;
            curSolidLvl = (int) ((prevPacked >>> 16) & 0xF);
            curSolidBuried = (prevPacked & (1L << 20)) != 0;

            // The map only needs two things from a column: its highest solid height and whether
            // its *finest* real surface is water. A top is never chosen by comparing heights across
            // LODs - coarse mip tops are maxima over a cell and produce plateaus when mixed into
            // fine terrain.
            if (top != NO_TOP && lvl < curLvl) {
                curLvl = lvl;
                curTop = top;
                curWater = topFluid;
                curBiome = biome;
                curSlot = slot;
            } else if (top != NO_TOP && lvl == curLvl) {
                if (top > curTop) {
                    curTop = top;
                    curWater = topFluid;
                    curBiome = biome;
                    curSlot = slot;
                }
            } else if (top == NO_TOP && lvl < curLvl) {
                // a finer level exists here but only as a buried slice: the finest level has no
                // visible surface, so do not inherit a coarser water surface (or its material)
                curLvl = lvl;
                curTop = NO_TOP;
                curWater = false;
                curBiome = biome;
                curSlot = 0;
            }
        }

        // Solid height is level-aware: the finest level that has any solid wins. A coarser solid
        // may only fill above a buried fine slice (its ceiling is not the real terrain height).
        // This keeps flat/valley columns at lvl0 precision instead of maxing every column against
        // the coarsest LOD.
        if (solidHere != Integer.MIN_VALUE) {
            if (lvl < curSolidLvl) {
                // A finer level determines whether coarser solids may fill: if the finer level
                // has a real surface its exact solid wins; if it is only a buried slice, keep the
                // highest solid and stay in "buried" mode so all coarser levels can be compared
                // regardless of arrival order.
                int nextFloor = top != NO_TOP ? solidHere : Math.max(solidHere, curFloor);
                if (nextFloor != curFloor || top != NO_TOP) {
                    // the new section's ceiling block is the block at the visible height
                    curFloorSlot = slot;
                    curFloorBiome = biome;
                }
                curFloor = nextFloor;
                curSolidLvl = lvl;
                curSolidBuried = top == NO_TOP;
            } else if (lvl == curSolidLvl) {
                if (solidHere > curFloor) {
                    curFloor = solidHere;
                    curFloorSlot = slot;
                    curFloorBiome = biome;
                }
                if (top != NO_TOP) curSolidBuried = false;
            } else if (curSolidBuried && solidHere > curFloor) {
                // still the same finest (buried) level underneath: only raise the fallback height
                if (curWater && solidHere > curTop) {
                    // a coarse mip solid is a maximum over its cell, not this column's sea floor:
                    // never let it poke through a (finer) surface water pixel
                } else {
                    curFloor = solidHere;
                    curFloorSlot = slot;
                    curFloorBiome = biome;
                }
            }
        }
        if (!curWater) {
            curTop = curFloor != NO_SOLID ? curFloor : NO_TOP;
            // the dry pixel shows the block at the fallback height, not the (possibly buried) top:
            // its material slot AND its biome come from that block. The biome must not stay on an
            // underground/dry top that was discarded (e.g. a sculk block at y=9 under a mountain
            // whose surface is stony_peaks), otherwise biome borders/labels use the cave biome.
            curSlot = curFloorSlot;
            curBiome = curFloorBiome;
        } else if (curFloor != NO_SOLID && curFloor > curTop) {
            // Semantic invariant: a water surface cannot sit below the column's highest solid
            // block. floor > top means the "water surface" is covered by solid rock (cave river,
            // aquifer, underground lake), so the visible surface is that rock. This is the exact
            // failure mode when the real terrain surface lands on a section's local ceiling
            // (topY == 31): the decoder reports it as a buried slice, and a deeper fluid surface
            // becomes the finest-level "top" because coarse LOD tops are never allowed to
            // override a finer level. Demote it to dry land at floor instead of clamping floor
            // below the water.
            curWater = false;
            curTop = curFloor;
            curSlot = curFloorSlot;
            curBiome = curFloorBiome;
            curLvl = curSolidLvl;
        } else if (curFloor != NO_SOLID && curFloor >= curTop) {
            // quantization can make a legit seabed land exactly on the water surface
            curFloor = curTop - 1;
        }
        if (curFloor == Integer.MIN_VALUE) curFloor = NO_SOLID;
        long out = ((long) (curTop + 32768)) << 24 | ((curFloor + 32768) & 0xFFFF);
        if (curWater) out |= (1L << 52);
        out |= ((long) curBiome) << 53;
        out |= ((long) (curSlot & 0xFF)) << 40;
        out |= ((long) Math.min(15, Math.max(0, curLvl))) << 48;
        out |= ((long) Math.min(15, Math.max(0, curSolidLvl))) << 16;
        if (curSolidBuried) out |= 1L << 20;
        return out;
    }
}
