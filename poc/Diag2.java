import org.rocksdb.*;
import java.nio.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
public class Diag2 {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        System.out.println("=== water/lava mappings (key hex + decodings) ===");
        try (RocksIterator it = db.newIterator(hs.get(2))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] k = it.key(); byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    String text = unzip(v);
                    if (text != null && (text.contains("minecraft:water") || text.contains("minecraft:lava"))) {
                        long be = ((long)(k[0]&0xFF)<<24)|((long)(k[1]&0xFF)<<16)|((long)(k[2]&0xFF)<<8)|(k[3]&0xFF);
                        long le = ((long)(k[3]&0xFF)<<24)|((long)(k[2]&0xFF)<<16)|((long)(k[1]&0xFF)<<8)|(k[0]&0xFF);
                        System.out.println("hex=" + hex(k) + " be=" + be + " le=" + le
                            + " beType=" + (be>>>30) + " beId30=" + (be&((1L<<30)-1)) + " beId24=" + (be&0xFFFFFF)
                            + " | " + text.substring(0, Math.min(80, text.length())));
                    }
                }
                it.next();
            }
        }
        System.out.println("=== top block ids in level1 sections (histogram) ===");
        Map<Long,Long> hist = new HashMap<>();
        int sections = 0;
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid() && sections < 200) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int)((key>>>60)&0xF);
                    if (lvl == 1) {
                        byte[] val = it.value();
                        if (val != null && val.length > 16) {
                            byte[] data = decompress(val);
                            if (data != null) { countBlocks(data, hist); sections++; }
                        }
                    }
                }
                it.next();
            }
        }
        hist.entrySet().stream().sorted((x,y)->Long.compare(y.getValue(), x.getValue())).limit(15)
            .forEach(e->System.out.println("blockId=" + e.getKey() + " count=" + e.getValue()));
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
    static void countBlocks(byte[] d, Map<Long,Long> hist) {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        bb.getLong(); long meta = bb.getLong(); int lutSize=(int)(meta&0xFFFF);
        int base=16, lutBase=base+32768*2;
        for (int i=0;i<lutSize;i++){
            long voxel = bb.getLong(lutBase+i*8);
            long blockId = (voxel>>>27)&((1L<<20)-1);
            hist.merge(blockId, 1L, Long::sum);
        }
    }
    static byte[] decompress(byte[] in) throws Exception {
        try (com.github.luben.zstd.ZstdInputStream z = new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(in))) {
            return z.readAllBytes();
        }
    }
    static String unzip(byte[] v) {
        try (GZIPInputStream g = new GZIPInputStream(new java.io.ByteArrayInputStream(v))) {
            byte[] out = g.readAllBytes();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) { char c = (char)(b & 0xFF); sb.append(c >= 32 && c < 127 ? c : (c == 0 ? ' ' : '.')); }
            return sb.toString();
        } catch (Exception e) { return null; }
    }
    static String hex(byte[] b) { StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02x", x)); return sb.toString(); }
}
