import org.rocksdb.*;
import java.nio.*;
import java.util.*;
public class CompareLevels2 {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        Map<Integer, Long> found = new TreeMap<>();
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int)((key>>>60)&0xF);
                    int sx = (int)((key<<36)>>40);
                    int sy = (int)((key<<4)>>56);
                    int sz = (int)((key<<12)>>40);
                    if (lvl<=3 && sx>=-2 && sx<=2 && sz>=-2 && sz<=2 && sy==0) found.putIfAbsent(lvl, key);
                }
                it.next();
            }
        }
        for (var e : found.entrySet()) {
            long key = e.getValue();
            int lvl = (int)((key>>>60)&0xF);
            int sx = (int)((key<<36)>>40);
            int sy = (int)((key<<4)>>56);
            int sz = (int)((key<<12)>>40);
            byte[] keyBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(key).array();
            byte[] val = db.get(hs.get(1), keyBytes);
            if (val == null) { System.out.println("lvl="+lvl+" null"); continue; }
            byte[] data = decompress(val);
            System.out.println("lvl="+lvl+" sx="+sx+" sy="+sy+" sz="+sz+" worldX0="+(sx*32*(1<<lvl))+" worldY0="+(sy*32*(1<<lvl))+" worldZ0="+(sz*32*(1<<lvl)));
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            bb.getLong(); long meta=bb.getLong(); int lutSize=(int)(meta&0xFFFF);
            int base=16, lutBase=base+32768*2; long airMask=((1L<<20)-1)<<27;
            int[] top=new int[1024]; Arrays.fill(top,-1);
            for (int i=0;i<32768;i++){
                int li=Short.toUnsignedInt(bb.getShort(base+i*2));
                if (li>=lutSize) continue;
                long v=bb.getLong(lutBase+li*8);
                if ((v&airMask)==0) continue;
                int x=i&31, z=(i>>5)&31, y=(i>>10)&31, c=z*32+x;
                if (y>top[c]) top[c]=y;
            }
            int scale=1<<lvl, mx=-1;
            for (int c=0;c<1024;c++) if (top[c]>mx) mx=top[c];
            System.out.println("  maxTop ly="+mx+" -> worldY="+((sy*32+mx)*scale+scale-1));
            StringBuilder sb=new StringBuilder("  tops 8x8: ");
            for (int z=0;z<8;z++){ for(int x=0;x<8;x++){ sb.append(top[z*32+x]).append(" "); } sb.append("| "); }
            System.out.println(sb);
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
    static byte[] decompress(byte[] in) throws Exception {
        try (com.github.luben.zstd.ZstdInputStream z = new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(in))) {
            return z.readAllBytes();
        }
    }
}
