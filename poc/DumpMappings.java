import org.rocksdb.*;
import java.nio.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
public class DumpMappings {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        int total = 0, water = -1, lava = -1, waterCount = 0;
        try (RocksIterator it = db.newIterator(hs.get(2))) {
            it.seekToFirst();
            while (it.isValid()) {
                total++;
                byte[] k = it.key();
                byte[] v = it.value();
                if (k != null && k.length == 4 && v != null && v.length > 10) {
                    int id = ByteBuffer.wrap(k).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    String text = unzip(v);
                    if (text != null) {
                        if (text.contains("minecraft:water")) { water = id; waterCount++; }
                        if (text.contains("minecraft:lava")) { lava = id; }
                    }
                }
                it.next();
            }
        }
        System.out.println("id_mappings total=" + total + " waterId=" + water + " (count " + waterCount + ") lavaId=" + lava);
        // print a few entries
        try (RocksIterator it = db.newIterator(hs.get(2))) {
            it.seekToFirst();
            int n = 0;
            while (it.isValid() && n < 3) {
                byte[] k = it.key(); byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    int id = ByteBuffer.wrap(k).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    String text = unzip(v);
                    if (text != null && n < 3) { System.out.println("id=" + id + " nbt=" + text.substring(0, Math.min(120, text.length()))); n++; }
                }
                it.next();
            }
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
    static String unzip(byte[] v) {
        try (GZIPInputStream g = new GZIPInputStream(new java.io.ByteArrayInputStream(v))) {
            byte[] out = g.readAllBytes();
            // extract printable ascii
            StringBuilder sb = new StringBuilder();
            for (byte b : out) { char c = (char)(b & 0xFF); sb.append(c >= 32 && c < 127 ? c : (c == 0 ? ' ' : '.')); }
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
