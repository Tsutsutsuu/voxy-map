import org.rocksdb.*;
import java.nio.*;
import java.util.*;
public class DumpKeys2 {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        Map<Integer, Map<Integer,Integer>> hist = new TreeMap<>();
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int)((key>>>60)&0xF);
                    int sy = (int)((key<<4)>>56);
                    hist.computeIfAbsent(lvl, k->new TreeMap<>()).merge(sy,1,Integer::sum);
                }
                it.next();
            }
        }
        for (int lvl : new int[]{0,1,4}) {
            var m = hist.get(lvl);
            if (m != null) System.out.println("lvl="+lvl+" sy->count: "+m);
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
}
