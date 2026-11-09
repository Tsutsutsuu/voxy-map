package com.voxymap;

import com.github.luben.zstd.Zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Persistent per-world section index for incremental refreshes.
 *
 * <p>It stores, for every <em>world_sections</em> key that was part of the snapshot, a 64-bit hash of
 * the RocksDB value. A later refresh only has to scan keys+values (fast) and decode the sections
 * whose hash changed. The index is only valid for the exact snapshot it was written for, so the
 * snapshot stamp and geometry are part of the file and validated by the caller.</p>
 *
 * <p>Deliberately has no Minecraft dependencies: the offline verification tool can exercise it
 * without booting the game.</p>
 */
public final class VoxyIndex {
    /** "VXI1" */
    public static final int MAGIC = 0x56584931;
    /** 2: stores maxLodLevel so a config change can invalidate the index explicitly. */
    public static final int VERSION = 2;
    /** defensive limits for a corrupt/hostile file */
    private static final int MAX_SECTIONS = 32_000_000;

    public final long stamp;
    /** RocksDB latest sequence number when the scan that produced this index started (-1 = unknown). */
    public final long dbSequence;
    public final long minWX;
    public final long minWZ;
    public final int width;
    public final int height;
    public final int pixelBlocks;
    public final int maxLodLevel;
    /** section keys, strictly ascending (the same order RocksDB iterates) */
    public final long[] keys;
    /** value hash parallel to {@link #keys} */
    public final long[] hashes;

    public VoxyIndex(long stamp, long dbSequence, long minWX, long minWZ, int width, int height,
                     int pixelBlocks, int maxLodLevel, long[] keys, long[] hashes) {
        if (keys.length != hashes.length) throw new IllegalArgumentException("key/hash length mismatch");
        this.stamp = stamp;
        this.dbSequence = dbSequence;
        this.minWX = minWX;
        this.minWZ = minWZ;
        this.width = width;
        this.height = height;
        this.pixelBlocks = pixelBlocks;
        this.maxLodLevel = maxLodLevel;
        this.keys = keys;
        this.hashes = hashes;
    }

    public int count() {
        return keys.length;
    }

    /** Binary search for a section key; returns its position or -1. */
    public int indexOf(long key) {
        int lo = 0, hi = keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long k = keys[mid];
            if (k == key) return mid;
            if (k < key) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    /**
     * 64-bit FNV-1a over the compressed section bytes plus a length mix and avalanche step. The
     * value bytes are already the canonical representation Voxy stores, so any section rewrite is
     * detected; collisions are irrelevant for a cache (worst case: one frame uses previous data
     * for one section, corrected by the periodic full rebuild).
     */
    public static long hashValue(byte[] value) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < value.length; i++) {
            h ^= (value[i] & 0xFF);
            h *= 0x100000001B3L;
        }
        h ^= ((long) value.length) * 0x9E3779B97F4A7C15L;
        // avalanche (splitmix64 finalizer)
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    /** Result of comparing the current DB scan against a previous index. */
    public static final class Diff {
        /** keys that are new or whose value hash changed, ascending */
        public final long[] changedKeys;
        /** keys present in the previous index but gone from the DB */
        public final int removed;

        Diff(long[] changedKeys, int removed) {
            this.changedKeys = changedKeys;
            this.removed = removed;
        }

        public boolean noChanges() {
            return changedKeys.length == 0 && removed == 0;
        }
    }

    /**
     * Two-pointer merge of two ascending key lists. {@code currentKeys/currentHashes} come from a
     * fresh scan; {@code previous} may be null (everything is new).
     */
    public static Diff diff(VoxyIndex previous, long[] currentKeys, long[] currentHashes) {
        if (previous == null) return new Diff(currentKeys.clone(), 0);
        long[] tmp = new long[Math.min(1024, currentKeys.length + 1)];
        int n = 0;
        int i = 0, j = 0;
        while (j < currentKeys.length) {
            long k = currentKeys[j];
            if (i < previous.keys.length && previous.keys[i] < k) {
                i++;
                continue;
            }
            if (i < previous.keys.length && previous.keys[i] == k) {
                if (previous.hashes[i] != currentHashes[j]) {
                    if (n == tmp.length) tmp = Arrays.copyOf(tmp, tmp.length * 2);
                    tmp[n++] = k;
                }
                i++;
                j++;
            } else {
                if (n == tmp.length) tmp = Arrays.copyOf(tmp, tmp.length * 2);
                tmp[n++] = k;
                j++;
            }
        }
        int removed = (previous.keys.length - i);
        return new Diff(Arrays.copyOf(tmp, n), removed);
    }

    /** Loads an index; returns null when the file is missing/corrupt/unsupported. */
    public static VoxyIndex load(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            byte[] data = Files.readAllBytes(file);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            long stamp = in.readLong();
            long seq = in.readLong();
            long minWX = in.readLong();
            long minWZ = in.readLong();
            int w = in.readInt();
            int h = in.readInt();
            int pb = in.readInt();
            int maxLod = in.readInt();
            int count = in.readInt();
            if (count < 0 || count > MAX_SECTIONS || w <= 0 || h <= 0 || pb <= 0) return null;
            int clen = in.readInt();
            if (clen <= 0 || clen > data.length) return null;
            byte[] comp = new byte[clen];
            in.readFully(comp);
            long rawLen = (long) count * 16L;
            if (rawLen > Integer.MAX_VALUE) return null;
            byte[] raw = Zstd.decompress(comp, (int) rawLen);
            if (raw == null || raw.length != rawLen) return null;
            long[] keys = new long[count];
            long[] hashes = new long[count];
            DataInputStream block = new DataInputStream(new ByteArrayInputStream(raw));
            for (int i = 0; i < count; i++) {
                keys[i] = block.readLong();
                hashes[i] = block.readLong();
                if (i > 0 && keys[i] <= keys[i - 1]) return null; // must stay sorted
            }
            return new VoxyIndex(stamp, seq, minWX, minWZ, w, h, pb, maxLod, keys, hashes);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Atomically writes this index (tmp file + move). */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream blockBytes = new ByteArrayOutputStream();
        DataOutputStream block = new DataOutputStream(blockBytes);
        for (int i = 0; i < keys.length; i++) {
            block.writeLong(keys[i]);
            block.writeLong(hashes[i]);
        }
        block.flush();
        byte[] compressed = Zstd.compress(blockBytes.toByteArray(), 3);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(stamp);
        out.writeLong(dbSequence);
        out.writeLong(minWX);
        out.writeLong(minWZ);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(pixelBlocks);
        out.writeInt(maxLodLevel);
        out.writeInt(keys.length);
        out.writeInt(compressed.length);
        out.write(compressed);
        out.flush();

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bos.toByteArray());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public String toString() {
        return "VoxyIndex{sections=" + keys.length + ", seq=" + dbSequence + ", pb=" + pixelBlocks
                + ", maxLod=" + maxLodLevel + '}';
    }
}
