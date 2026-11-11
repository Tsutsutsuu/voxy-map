package com.voxymap;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;

import java.util.Arrays;
import java.util.Map;

/**
 * Per-biome tint tables for the "blocks" map style, mirroring vanilla's colour resolution.
 *
 * <p>Vanilla resolves block tints per block position through
 * {@code BlockColors}/{@code BiomeColors} ({@code BlockTintCache} only caches, it does not average
 * neighbours):</p>
 * <ul>
 *     <li><b>grass</b> = {@code Biome#getGrassColor(x, z)} = grass colormap sample at the biome's
 *         (temperature, downfall) — nearest cell, no interpolation — passed through the biome's
 *         {@code GrassColorModifier}: {@code DARK_FOREST} halves the brightness with an offset and
 *         {@code SWAMP}/{@code MANGROVE_SWAMP} pick one of two fixed colours from a position noise
 *         ({@code Biome.BIOME_INFO_NOISE}, frequency 0.0225, threshold -0.1). Only swamp and
 *         mangrove swamp are position dependent; every other biome (including dark forest, whose
 *         modifier is a fixed brightness/hue transform) has a single flat grass colour.</li>
 *     <li><b>foliage</b> = {@code Biome#getFoliageColor()} — foliage colormap sample, flat per
 *         biome (no modifier, no position). Used by oak/jungle/acacia/dark_oak/mangrove leaves and
 *         vines.</li>
 *     <li><b>dry foliage</b> = {@code Biome#getDryFoliageColor()} — the separate dry-foliage
 *         colormap, used by leaf litter (vanilla {@code BlockTintSources.dryFoliage()}).</li>
 *     <li><b>water</b> = {@code Biome#getWaterColor()} — a flat per-biome RGB value.</li>
 * </ul>
 *
 * <p>Spruce and birch leaves are <em>not</em> biome-tinted in vanilla ({@code BlockColors}
 * registers constant tints {@code #619961} / {@code #80A755}); they are palette classes instead
 * (see {@link SurfaceClassTable}).</p>
 *
 * <p>Water keeps the map style's depth layering: the pixel starts from the biome's vanilla water
 * colour ({@code Biome#getWaterColor()}) multiplied by the water texture's average (palette WATER
 * factor), then the current palette's depth falloff and the texture's alpha compositing are
 * applied (see {@code MapStyles#compositeWater}), so shallow water looks like translucent vanilla
 * water with the current style's depth layering instead of an over-saturated flat tint.</p>
 */
public final class BiomeTintTable {
    public static final BiomeTintTable INSTANCE = new BiomeTintTable();

    /** Vanilla plains colours: the fallback for unknown biomes. */
    public static final int PLAINS_GRASS = 0x91BD59;
    public static final int PLAINS_FOLIAGE = 0x59AE30;
    public static final int PLAINS_WATER = 0x3F76E4;
    public static final int PLAINS_DRY_FOLIAGE = 0xA37546;

    private static final int SIZE = SurfaceClassTable.BIOME_MASK + 1;

    /** One immutable snapshot of every table, published atomically. */
    private static final class Tables {
        final int[] grass;
        final int[] foliage;
        /** dry-foliage colour (leaf litter) per biome. */
        final int[] dryFoliage;
        /** vanilla flat water colour per biome. */
        final int[] water;
        /** biome instance per packed id, only for position-dependent grass colours (may be null). */
        final Biome[] biomes;
        /** true when grass colour depends on the world position (SWAMP-style modifier). */
        final boolean[] grassDynamic;
        /** texture-average factor of the grass/foliage tint (vanilla tint multiplies the texture). */
        final int grassFactor;
        final int foliageFactor;
        final int dryFoliageFactor;

        Tables(int[] grass, int[] foliage, int[] dryFoliage, int[] water,
               Biome[] biomes, boolean[] grassDynamic, int grassFactor, int foliageFactor,
               int dryFoliageFactor) {
            this.grass = grass;
            this.foliage = foliage;
            this.dryFoliage = dryFoliage;
            this.water = water;
            this.biomes = biomes;
            this.grassDynamic = grassDynamic;
            this.grassFactor = grassFactor;
            this.foliageFactor = foliageFactor;
            this.dryFoliageFactor = dryFoliageFactor;
        }
    }

    private volatile Tables tables;
    private volatile int builtBiomes;
    /** biome registry of the loaded world (26.2 moved biomes out of BuiltInRegistries). */
    private volatile Registry<Biome> registry;
    /** identity of the registry access the tables were captured from (skip per-tick lookups). */
    private volatile RegistryAccess registryAccess;
    /** last scanned biome-name table, kept so a late registry capture can rebuild immediately. */
    private volatile Map<Integer, String> lastNames = Map.of();

    private BiomeTintTable() {
        // no world yet: plains colours, already multiplied by the palette's texture-average factor
        rebuild(Map.of());
    }

    /**
     * Caches the current world's biome registry (client thread, once per world). Needed because
     * 26.2 resolves biomes through {@code level.registryAccess()} instead of a built-in registry,
     * while the tint tables are rebuilt later from a worker thread.
     */
    public void captureRegistry(RegistryAccess access) {
        if (access == registryAccess) return;
        registryAccess = access;
        Registry<Biome> next = null;
        if (access != null) {
            try {
                next = access.lookupOrThrow(Registries.BIOME);
            } catch (Throwable ignored) {
                // registry not synced (yet): keep the plains defaults
            }
        }
        if (next == registry) return;
        registry = next;
        // a registry captured after the mapping scan gets an immediate rebuild of the last names
        rebuild(lastNames);
    }

    /**
     * Grass colour for a packed-column biome id at a world position.
     *
     * <p>Most biomes return their flat colormap colour; the two swamp biomes have a
     * {@code SWAMP} grass modifier whose colour comes from a position noise, so they are evaluated
     * at the actual position exactly like vanilla ({@code Biome#getGrassColor(x, z)}). The vanilla
     * tint is then multiplied by the block texture's average brightness, because in game the tint
     * multiplies the (grayscale) grass texture.</p>
     */
    public int grass(int biomeId, int worldX, int worldZ) {
        int id = biomeId & SurfaceClassTable.BIOME_MASK;
        Tables t = tables;
        if (t.grassDynamic[id]) {
            Biome biome = t.biomes[id];
            if (biome != null) {
                return SurfaceClassTable.mulColor(biome.getGrassColor(worldX, worldZ), t.grassFactor);
            }
        }
        return t.grass[id];
    }

    /** Foliage colour for a packed-column biome id (vanilla: flat per biome, no position). */
    public int foliage(int biomeId) {
        return tables.foliage[biomeId & SurfaceClassTable.BIOME_MASK];
    }

    /** Dry-foliage colour for a packed-column biome id (leaf litter; falls back to plains). */
    public int dryFoliage(int biomeId) {
        return tables.dryFoliage[biomeId & SurfaceClassTable.BIOME_MASK];
    }

    /** Vanilla flat water colour for a packed-column biome id (falls back to plains). */
    public int water(int biomeId) {
        return tables.water[biomeId & SurfaceClassTable.BIOME_MASK];
    }

    /**
     * Rebuilds the tables from the world's biome mapping table ({@code biome mapping id -> registry
     * name}). Cheap (one client-registry lookup per biome, a few hundred at most) and idempotent;
     * call it after each id_mappings scan / world change.
     */
    public void rebuild(Map<Integer, String> biomeNames) {
        lastNames = biomeNames == null ? Map.of() : biomeNames;
        Registry<Biome> reg = registry;
        int grassFactor = SurfaceClassTable.INSTANCE.tintFactor(SurfaceClassTable.TINT_GRASS);
        int foliageFactor = SurfaceClassTable.INSTANCE.tintFactor(SurfaceClassTable.TINT_FOLIAGE);
        int dryFoliageFactor = SurfaceClassTable.INSTANCE.tintFactor(SurfaceClassTable.TINT_DRY_FOLIAGE);
        int waterFactor = SurfaceClassTable.INSTANCE.tintFactor(SurfaceClassTable.TINT_WATER);
        int[] g = new int[SIZE];
        int[] f = new int[SIZE];
        int[] d = new int[SIZE];
        int[] w = new int[SIZE];
        Biome[] biomes = new Biome[SIZE];
        boolean[] dynamic = new boolean[SIZE];
        // vanilla tint multiplied by the texture average (see SurfaceClassTable factors)
        Arrays.fill(g, SurfaceClassTable.mulColor(PLAINS_GRASS, grassFactor));
        Arrays.fill(f, SurfaceClassTable.mulColor(PLAINS_FOLIAGE, foliageFactor));
        Arrays.fill(d, SurfaceClassTable.mulColor(PLAINS_DRY_FOLIAGE, dryFoliageFactor));
        Arrays.fill(w, SurfaceClassTable.mulColor(PLAINS_WATER, waterFactor));
        int built = 0;
        if (reg != null) {
            for (Map.Entry<Integer, String> e : lastNames.entrySet()) {
                String name = e.getValue();
                if (name == null) continue;
                Identifier id = Identifier.tryParse(name);
                if (id == null) continue;
                Biome biome = reg.getValue(id);
                if (biome == null) continue;
                int slot = e.getKey() & SurfaceClassTable.BIOME_MASK;
                BiomeSpecialEffects.GrassColorModifier modifier =
                        biome.getSpecialEffects().grassColorModifier();
                if (modifier == BiomeSpecialEffects.GrassColorModifier.SWAMP) {
                    // swamp/mangrove swamp pick one of two colours from a position noise: keep the
                    // biome so the renderer can call getGrassColor(x, z) like vanilla does.
                    // (DARK_FOREST is position independent: its formula only scales the base colour.)
                    biomes[slot] = biome;
                    dynamic[slot] = true;
                }
                // final displayed value: vanilla tint * texture average
                g[slot] = SurfaceClassTable.mulColor(biome.getGrassColor(0.0, 0.0), grassFactor);
                f[slot] = SurfaceClassTable.mulColor(biome.getFoliageColor(), foliageFactor);
                d[slot] = SurfaceClassTable.mulColor(biome.getDryFoliageColor(), dryFoliageFactor);
                w[slot] = SurfaceClassTable.mulColor(biome.getWaterColor(), waterFactor);
                built++;
            }
        }
        tables = new Tables(g, f, d, w, biomes, dynamic, grassFactor, foliageFactor,
                dryFoliageFactor);
        builtBiomes = built;
    }

    /** Number of biomes resolved to a real client Biome in the last rebuild (diagnostics). */
    public int builtBiomes() {
        return builtBiomes;
    }
}

