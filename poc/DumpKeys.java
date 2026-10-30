import org.rocksdb.*;
import java.nio.*;
import java.util.*;
public class DumpKeys {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        Map<Integer,int[]> hist = new TreeMap<>();
        Map<Long,Integer> keyCount = new HashMap<>();
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int)((key>>>60)&0xF);
                    int sy = (int)((key<<4)>>56);
                    int sx = (int)((key<<36)>>40);
                    int sz = (int)((key<<12)>>40);
                    hist.computeIfAbsent(lvl, k->new int[4]);
                    int[] h = hist.get(lvl);
                    h[0]++;
                    h[1]=Math.min(h[1]==0?sy:h[1], sy); h[2]=Math.max(h[2], sy);
                    h[3]=Math.min(h[3]==0?sx:h[3], Math.min(sx,sz));
                }
                it.next();
            }
        }
        for (var e : hist.entrySet()) {
            int[] h = e.getValue();
            System.out.println("lvl="+e.getKey()+" count="+h[0]+" syMin="+h[1]+" syMax="+h[2]);
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
}
