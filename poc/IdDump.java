import org.rocksdb.*;
import java.nio.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
public class IdDump {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        int shown = 0;
        try (RocksIterator it = db.newIterator(hs.get(2))) {
            it.seekToFirst();
            while (it.isValid() && shown < 40) {
                byte[] k = it.key();
                byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    long be = ((long)(k[0]&0xFF)<<24)|((long)(k[1]&0xFF)<<16)|((long)(k[2]&0xFF)<<8)|(k[3]&0xFF);
                    String text = unzip(v);
                    String kind = text == null ? "NULL" : (text.contains("Properties") ? "block" : (text.contains("minecraft:") ? "biome" : "other"));
                    String brief = text == null ? "" : text.replaceAll("[^ -~]", "").trim();
                    if (brief.length() > 90) brief = brief.substring(0, 90);
                    System.out.println("key=" + be + " kind=" + kind + " | " + brief);
                    shown++;
                }
                it.next();
            }
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
    static String unzip(byte[] v) {
        try (GZIPInputStream g = new GZIPInputStream(new java.io.ByteArrayInputStream(v))) {
            byte[] out = g.readAllBytes();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) { char c=(char)(b&0xFF); sb.append(c>=32&&c<127?c:(c==0?' ':'.')); }
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
