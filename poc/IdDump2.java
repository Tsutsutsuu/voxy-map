import org.rocksdb.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
public class IdDump2 {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        Map<Integer, Integer> kindCount = new TreeMap<>();
        int found = 0;
        String[] biomes = {"minecraft:plains","minecraft:ocean","minecraft:desert","minecraft:forest","minecraft:taiga","minecraft:swamp","minecraft:jungle","minecraft:snowy","minecraft:river","minecraft:beach","minecraft:stony","minecraft:birch","minecraft:dark_forest","minecraft:meadow","minecraft:cherry","minecraft:grove","minecraft:savanna","minecraft:badlands","minecraft:mushroom","minecraft:wind"};
        try (RocksIterator it = db.newIterator(hs.get(2))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] k = it.key();
                byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    long be = ((long)(k[0]&0xFF)<<24)|((long)(k[1]&0xFF)<<16)|((long)(k[2]&0xFF)<<8)|(k[3]&0xFF);
                    int hi = (int)(be >>> 24);
                    kindCount.merge(hi, 1, Integer::sum);
                    String text = unzip(v);
                    if (text != null) {
                        for (String b : biomes) {
                            if (text.contains(b)) {
                                if (found < 30) System.out.println("BIOME KEY=" + be + " | " + text.replaceAll("[^ -~]", "").trim().substring(0, Math.min(80, text.replaceAll("[^ -~]", "").trim().length())));
                                found++;
                                break;
                            }
                        }
                    }
                }
                it.next();
            }
        }
        System.out.println("high-byte distribution: " + kindCount);
        System.out.println("biome-like entries: " + found);
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
