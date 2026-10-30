import org.rocksdb.*;
import java.nio.*;
import java.util.*;
public class ProbeWorld {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        int wx = Integer.parseInt(a[1]), wz = Integer.parseInt(a[2]);
        // collect sections covering this column per level
        Map<Integer,List<Integer>> syByLevel = new TreeMap<>();
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int)((key>>>60)&0xF);
                    int sx = (int)((key<<36)>>40), sy = (int)((key<<4)>>56), sz = (int)((key<<12)>>40);
                    if (lvl>4) { it.next(); continue; }
                    long scale = 1L<<lvl;
                    long x0 = (long)sx*32*scale, z0=(long)sz*32*scale, y0=(long)sy*32*scale;
                    if (wx>=x0 && wx<x0+32*scale && wz>=z0 && wz<z0+32*scale) {
                        syByLevel.computeIfAbsent(lvl, k->new ArrayList<>()).add(sy);
                    }
                }
                it.next();
            }
        }
        System.out.println("column world "+wx+","+wz);
        for (var e : syByLevel.entrySet()) {
            int lvl=e.getKey(); var list=e.getValue(); Collections.sort(list);
            System.out.println("lvl="+lvl+" sy="+list);
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
}
