package com.voxymap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime surface-material palette for the "blocks" map style.
 *
 * <p>Loads {@code assets/voxymap/surface_classes.json} (200 classes including the reserved
 * FALLBACK entry, i.e. 199 usable slots, flat colours, a small exact name table, wood families and
 * plant keywords). The rule ladder that turns a block registry name into a class is hardcoded here
 * on purpose: it mirrors the offline census classifier ({@code poc/classify_test.py}) one-to-one,
 * keeps the hot decode path free of JSON walking, and only ever runs once per block-state mapping
 * (not per pixel).</p>
 *
 * <p>This class deliberately has no Minecraft imports so it can be reused by offline tools. The
 * biome-tinted colour part lives in {@link BiomeTintTable} (client-side).</p>
 *
 * <p>Two lookup tables are maintained:</p>
 * <ul>
 *     <li>{@link #classOf} — registry name (+ snowy property) to class slot (used when the Voxy
 *         mapping table is scanned);</li>
 *     <li>{@link #slotOf} — Voxy block-state mapping id to class slot (used by the section
 *         decoders for every column).</li>
 * </ul>
 * Slot 0 is reserved for "unknown" and renders as the palette's FALLBACK colour.
 */
public final class SurfaceClassTable {
    public static final SurfaceClassTable INSTANCE = new SurfaceClassTable();

    private static final String RESOURCE = "/assets/voxymap/surface_classes.json";
    /** palette slot width in the packed column (bits 40..47) */
    public static final int SLOT_MASK = 0xFF;
    /** biome id width in the packed column (bits 53..61) */
    public static final int BIOME_MASK = 0x1FF;

    public static final int TINT_NONE = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_FOLIAGE = 2;
    public static final int TINT_WATER = 3;
    /** vanilla dry-foliage tint (leaf litter, {@code BlockTintSources.dryFoliage()}) */
    public static final int TINT_DRY_FOLIAGE = 4;

    private static final int MAX_SLOTS = SLOT_MASK + 1;
    /** mapping ids below this are served from a flat byte array instead of the hash map */
    private static final int FLAT_ID_LIMIT = 1 << 20;

    private volatile State state;
    /** block-state mapping id -> palette slot; rebuilt whenever the Voxy mapping table is scanned. */
    private volatile LongIntMap blockSlots;
    /** flat variant of {@link #blockSlots} (index = mapping id), null when ids are too sparse. */
    private volatile byte[] blockSlotsFlat;

    private SurfaceClassTable() {
    }

    private static final class State {
        int version;
        int fallbackColor = 0x8C8C8C;
        int classCount;
        /** final flat RGB per class (tint colour multiplied by the texture-average factor). */
        final int[] colors = new int[MAX_SLOTS];
        /** per-class texture-average factor, 0xFFFFFF = unchanged (static classes). */
        final int[] factor = new int[MAX_SLOTS];
        /** factor of the first class carrying each tint type (index by tint constant). */
        final int[] tintFactor = {0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF};
        final byte[] tint = new byte[MAX_SLOTS];
        final Map<String, Integer> classSlot = new HashMap<>();
        final Map<String, Integer> exact = new HashMap<>();
        final Map<String, Integer> snowyOverrides = new HashMap<>();
        String[] woodFamilies = new String[0];
        String[] plantKeywords = new String[0];
    }

    /** Palette revision from the JSON ({@code version}); part of the colour/texture cache hash. */
    public int version() {
        return state().version;
    }

    /** Number of palette classes (excluding the reserved unknown/fallback slot). */
    public int classCount() {
        return state().classCount;
    }

    public int fallbackColor() {
        return state().fallbackColor;
    }

    /** Class name for a slot (diagnostics only). */
    public String className(int slot) {
        State s = state();
        for (Map.Entry<String, Integer> e : s.classSlot.entrySet()) {
            if (e.getValue() == slot) return e.getKey();
        }
        return slot == 0 ? "FALLBACK" : "UNKNOWN_" + slot;
    }

    /** Flat RGB colour of a class slot (already texture-factored); unknown slot -> FALLBACK. */
    public int colorOf(int slot) {
        State s = state();
        return slot > 0 && slot < MAX_SLOTS ? s.colors[slot] : s.fallbackColor;
    }

    /**
     * Texture-average factor of a class slot (0xFFFFFF = unchanged). Tinted classes store the
     * vanilla tint colour in {@code hex}; the map must render {@code tint * factor} because in
     * game the tint multiplies the (grayscale) block texture, whose average is ~58%.
     */
    public int factorOf(int slot) {
        State s = state();
        return slot > 0 && slot < MAX_SLOTS ? s.factor[slot] : 0xFFFFFF;
    }

    /** Texture-average factor of the class carrying a tint type (see {@link #TINT_GRASS} etc.). */
    public int tintFactor(int tintType) {
        State s = state();
        return tintType > 0 && tintType < s.tintFactor.length ? s.tintFactor[tintType] : 0xFFFFFF;
    }

    /** 0 = static, 1 = grass tint, 2 = foliage tint, 3 = water tint. */
    public int tintOf(int slot) {
        if (slot <= 0 || slot >= MAX_SLOTS) return TINT_NONE;
        return state().tint[slot];
    }

    /**
     * Resolves a block registry name to its palette slot.
     *
     * @param name  full registry name, e.g. {@code minecraft:grass_block} or {@code modid:foo}
     * @param snowy whether the block state has {@code snowy=true} (snow-covered grass/podzol/mycelium)
     */
    public int classOf(String name, boolean snowy) {
        if (name == null || name.isEmpty()) return 0;
        State s = state();
        if (snowy) {
            Integer override = s.snowyOverrides.get(name);
            if (override != null) return override;
        }
        Integer exact = s.exact.get(name);
        if (exact != null) return exact;
        String path = name;
        int colon = name.indexOf(':');
        if (colon >= 0 && colon + 1 < name.length()) path = name.substring(colon + 1);
        return ruleClass(s, path);
    }

    /**
     * The hardcoded rule ladder. Order is significant and mirrors the offline classifier exactly
     * (first match wins): structure/colour overrides first, then stone-family, ice/snow, liquids,
     * wood families, plants, fallback.
     */
    private static int ruleClass(State s, String path) {
        if (path.endsWith("_leaves")) return slotOfClass(s, "FOLIAGE");
        if (path.contains("terracotta")) {
            int idx = path.indexOf("_terracotta");
            if (idx > 0) {
                Integer colored = s.classSlot.get(
                        "TERRACOTTA_" + path.substring(0, idx).toUpperCase(Locale.ROOT));
                if (colored != null) return colored;
            }
            return slotOfClass(s, "TERRACOTTA");
        }
        if (path.contains("red_sandstone")) return slotOfClass(s, "RED_SANDSTONE");
        if (path.contains("sandstone")) return slotOfClass(s, "SANDSTONE");
        if (path.contains("red_sand")) return slotOfClass(s, "RED_SAND");
        if (path.contains("sand")) return slotOfClass(s, "SAND");
        if (path.contains("deepslate")) return slotOfClass(s, "DEEPSLATE");
        if (path.contains("andesite")) return slotOfClass(s, "ANDESITE");
        if (path.contains("diorite")) return slotOfClass(s, "DIORITE");
        if (path.contains("granite")) return slotOfClass(s, "GRANITE");
        if (path.contains("tuff")) return slotOfClass(s, "TUFF");
        if (path.contains("calcite")) return slotOfClass(s, "CALCITE");
        if (path.contains("dripstone")) return slotOfClass(s, "DRIPSTONE");
        if (path.contains("brick")) return slotOfClass(s, "BRICKS");
        if (path.contains("stone") || path.contains("cobble")) return slotOfClass(s, "STONE");
        if (path.contains("packed_ice")) return slotOfClass(s, "PACKED_ICE");
        if (path.contains("blue_ice")) return slotOfClass(s, "BLUE_ICE");
        if (path.contains("ice")) return slotOfClass(s, "ICE");
        if (path.contains("snow")) return slotOfClass(s, "SNOW");
        if (path.contains("water")) return slotOfClass(s, "WATER");
        if (path.contains("lava")) return slotOfClass(s, "LAVA");
        if (path.contains("mushroom")) return slotOfClass(s, "MUSHROOM_BROWN");
        for (String wood : s.woodFamilies) {
            if (path.startsWith(wood) || path.contains("_" + wood)) {
                return slotOfClass(s, "WOOD");
            }
        }
        for (String plant : s.plantKeywords) {
            if (path.contains(plant)) return slotOfClass(s, "GRASS");
        }
        return 0;
    }

    private static int slotOfClass(State s, String name) {
        Integer slot = s.classSlot.get(name);
        return slot == null ? 0 : slot;
    }

    // ------------------------------------------------------------------ mapping ids

    /** Palette slot for a Voxy block-state mapping id (hot path: one per decoded column). */
    public int slotOf(long mappingId) {
        byte[] flat = blockSlotsFlat;
        if (flat != null) {
            // flat stores bytes; slots above 127 must be read unsigned (palette v8 has 199 slots)
            return mappingId >= 0 && mappingId < flat.length ? flat[(int) mappingId] & 0xFF : 0;
        }
        LongIntMap map = blockSlots;
        return map == null ? 0 : map.get(mappingId);
    }

    /** Builds a new mapping-id table; commit publishes it atomically. */
    public MappingBuilder mappingBuilder() {
        state();
        return new MappingBuilder();
    }

    /** Collects {@code mapping id -> class slot} while the id_mappings table is scanned. */
    public final class MappingBuilder {
        private final LongIntMap map = new LongIntMap(2048);
        private long maxId = -1;
        private boolean any;

        private MappingBuilder() {
        }

        /**
         * @param mappingId Voxy id_mappings key (low 30 bits, the part stored in a voxel)
         * @param text      decompressed mapping text (see {@link #parseBlockName})
         */
        public void add(long mappingId, String text) {
            String name = parseBlockName(text);
            if (name == null) return;
            boolean snowy = propertyTrue(text, "snowy");
            int slot = classOf(name, snowy);
            if (slot == 0) return;
            map.put(mappingId, slot);
            any = true;
            if (mappingId > maxId) maxId = mappingId;
        }

        /** Publishes this table for the section decoders. Empty tables are still published. */
        public void commit() {
            if (!any) {
                blockSlotsFlat = null;
                blockSlots = null;
                return;
            }
            if (maxId >= 0 && maxId < FLAT_ID_LIMIT) {
                byte[] flat = new byte[(int) maxId + 1];
                map.copyValuesTo(flat);
                blockSlotsFlat = flat;
                blockSlots = null;
            } else {
                blockSlotsFlat = null;
                blockSlots = map;
            }
        }
    }

    /**
     * Extracts the registry name from one decompressed Voxy block-state mapping text.
     *
     * <p>Verified format on all five 26.2 test worlds:
     * {@code "|.  . .block_state. .Properties. .snowy .false . .Name .minecraft:grass_block . .id ... |"}.
     * Non-printable NBT bytes are rendered as '.', so the name is located via its ':' separator and
     * expanded to the allowed resource-location character set.</p>
     */
    static String parseBlockName(String text) {
        if (text == null) return null;
        int nameTag = text.indexOf("Name");
        while (nameTag >= 0) {
            int colon = text.indexOf(':', nameTag + 4);
            if (colon > nameTag + 4) {
                int start = colon;
                while (start > nameTag + 4 && isNameChar(text.charAt(start - 1))) start--;
                int end = colon + 1;
                while (end < text.length() && isNameChar(text.charAt(end))) end++;
                if (end > colon + 1) {
                    // a non-printable length byte right before the namespace renders as '.', and
                    // registry names never start with one
                    while (start < colon && text.charAt(start) == '.') start++;
                    String name = text.substring(start, end);
                    // Voxy renders the NBT string terminator as '.', and '.' is a legal resource
                    // location character, so the scan above can swallow it (1.21.1 mapping text:
                    // "Name .minecraft:oak_leaves. .Properties"). A trailing dot then misses the
                    // exact palette lookup and the rule fallback classifies leaves as wood. Strip
                    // it like the offline MappingNames helper does.
                    while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
                    if (!name.isEmpty() && name.indexOf(':') > 0) return name;
                }
            }
            nameTag = text.indexOf("Name", nameTag + 4);
        }
        return null;
    }

    /**
     * True when the mapping text contains the NBT property {@code key} with value {@code true}.
     * The dumped text renders NBT length bytes as '.', so the real form is {@code ".snowy .true"}
     * rather than the readable {@code "snowy true"}; both are accepted (the value word is located
     * by skipping everything that is not a lowercase letter).
     */
    static boolean propertyTrue(String text, String key) {
        if (text == null) return false;
        int from = 0;
        while (true) {
            int idx = text.indexOf(key, from);
            if (idx < 0) return false;
            int afterKey = idx + key.length();
            // the key must end at a separator, not continue as part of another word
            if (afterKey < text.length() && isWordChar(text.charAt(afterKey))) {
                from = afterKey;
                continue;
            }
            int j = afterKey;
            while (j < text.length() && !(text.charAt(j) >= 'a' && text.charAt(j) <= 'z')) j++;
            if (j + 4 <= text.length() && text.regionMatches(j, "true", 0, 4)
                    && (j + 4 == text.length() || !isWordChar(text.charAt(j + 4)))) {
                return true;
            }
            from = afterKey;
        }
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == ':' || c == '.' || c == '-' || c == '/';
    }

    // ------------------------------------------------------------------ JSON load

    private State state() {
        State s = state;
        if (s != null) return s;
        synchronized (this) {
            s = state;
            if (s == null) {
                s = load();
                state = s;
            }
            return s;
        }
    }

    private static State load() {
        State s = new State();
        try (InputStream in = SurfaceClassTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                System.err.println("[voxymap] surface_classes.json missing; block colours use fallback");
                return s;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("version")) s.version = root.get("version").getAsInt();
            JsonObject classes = root.getAsJsonObject("classes");
            if (classes != null) {
                for (Map.Entry<String, JsonElement> e : classes.entrySet()) {
                    String name = e.getKey();
                    JsonObject def = e.getValue().getAsJsonObject();
                    int color = parseHex(def.has("hex") ? def.get("hex").getAsString() : "#8C8C8C");
                    if ("FALLBACK".equals(name)) {
                        s.fallbackColor = color;
                        continue;
                    }
                    int slot = ++s.classCount;
                    if (slot >= MAX_SLOTS) {
                        throw new IllegalStateException("too many surface classes: " + slot);
                    }
                    s.classSlot.put(name, slot);
                    // Static classes already store their final (top-face average) colour, so their
                    // factor is white. Tinted classes store the vanilla tint colour and the average
                    // brightness of the texture the tint multiplies (grass_block_top ≈ 58%).
                    int factor = parseHex(def.has("factor") ? def.get("factor").getAsString() : "#FFFFFF");
                    s.factor[slot] = factor;
                    s.colors[slot] = mulColor(color, factor);
                    if (def.has("tint")) {
                        int tintType = TINT_NONE;
                        switch (def.get("tint").getAsString()) {
                            case "grass" -> tintType = TINT_GRASS;
                            case "foliage" -> tintType = TINT_FOLIAGE;
                            case "water" -> tintType = TINT_WATER;
                            case "dry_foliage" -> tintType = TINT_DRY_FOLIAGE;
                            default -> {
                            }
                        }
                        s.tint[slot] = (byte) tintType;
                        if (tintType != TINT_NONE) s.tintFactor[tintType] = factor;
                    }
                }
            }
            s.colors[0] = s.fallbackColor;
            readClassMap(root, "exact", s, s.exact);
            readClassMap(root, "snowyOverrides", s, s.snowyOverrides);
            s.woodFamilies = readStrings(root, "woodFamilies");
            s.plantKeywords = readStrings(root, "plantKeywords");
        } catch (Throwable t) {
            System.err.println("[voxymap] surface_classes.json could not be loaded: " + t);
        }
        return s;
    }

    private static void readClassMap(JsonObject root, String key, State s, Map<String, Integer> out) {
        JsonObject obj = root.getAsJsonObject(key);
        if (obj == null) return;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            Integer slot = s.classSlot.get(e.getValue().getAsString());
            if (slot != null) out.put(e.getKey(), slot);
        }
    }

    private static String[] readStrings(JsonObject root, String key) {
        if (!root.has(key)) return new String[0];
        var arr = root.getAsJsonArray(key);
        String[] out = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsString();
        return out;
    }

    private static int parseHex(String hex) {
        String v = hex.startsWith("#") ? hex.substring(1) : hex;
        return Integer.parseInt(v, 16) & 0xFFFFFF;
    }

    /** Component-wise {@code colour * factor / 255}. */
    static int mulColor(int rgb, int factor) {
        if (factor == 0xFFFFFF) return rgb;
        int r = (((rgb >> 16) & 0xFF) * ((factor >> 16) & 0xFF) + 127) / 255;
        int g = (((rgb >> 8) & 0xFF) * ((factor >> 8) & 0xFF) + 127) / 255;
        int b = ((rgb & 0xFF) * (factor & 0xFF) + 127) / 255;
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    // ------------------------------------------------------------------ id -> slot map

    /** Minimal open-addressing long -> int map (mapping tables are tiny; avoids boxing). */
    static final class LongIntMap {
        private long[] keys;   // key + 1, 0 = empty
        private int[] values;
        private int mask;
        private int size;

        LongIntMap(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected * 2)) << 1;
            keys = new long[cap];
            values = new int[cap];
            mask = cap - 1;
        }

        void put(long key, int value) {
            if (value == 0) return;
            long k = key + 1;
            int i = hash(key) & mask;
            while (true) {
                long cur = keys[i];
                if (cur == 0) {
                    keys[i] = k;
                    values[i] = value;
                    if (++size * 4 > keys.length * 3) grow();
                    return;
                }
                if (cur == k) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        int get(long key) {
            long k = key + 1;
            int i = hash(key) & mask;
            while (true) {
                long cur = keys[i];
                if (cur == 0) return 0;
                if (cur == k) return values[i];
                i = (i + 1) & mask;
            }
        }

        /** Writes every value into {@code out}, indexed by mapping id. */
        void copyValuesTo(byte[] out) {
            for (int i = 0; i < keys.length; i++) {
                long cur = keys[i];
                if (cur == 0) continue;
                long id = cur - 1;
                if (id >= 0 && id < out.length) out[(int) id] = (byte) values[i];
            }
        }

        private static int hash(long key) {
            int h = (int) (key ^ (key >>> 32));
            h *= 0x9E3779B1;
            return h ^ (h >>> 16);
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = new int[oldValues.length << 1];
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != 0) put(oldKeys[i] - 1, oldValues[i]);
            }
        }
    }
}

