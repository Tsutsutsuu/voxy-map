import org.rocksdb.*;
import com.github.luben.zstd.Zstd;
import java.nio.*;
import java.util.*;
public class DumpColumn {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        int wx = Integer.parseInt(a[1]), wz = Integer.parseInt(a[2]);
        Map<Integer, List<Long>> byLevel = new TreeMap<>();
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl = (int)((key>>>60)&0xF);
                    int sx = (int)((key<<36)>>40), sz = (int)((key<<12)>>40);
                    long scale = 1L<<lvl;
                    long x0 = (long)sx*32*scale, z0=(long)sz*32*scale;
                    if (wx>=x0 && wx<x0+32*scale && wz>=z0 && wz<z0+32*scale) {
                        byLevel.computeIfAbsent(lvl, k->new ArrayList<>()).add(key);
                    }
                }
                it.next();
            }
        }
        System.out.println("column world " + wx + "," + wz);
        for (var e : byLevel.entrySet()) {
            int lvl = e.getKey();
            System.out.println("=== lvl=" + lvl + " sections=" + e.getValue().size() + " ===");
            for (long key : e.getValue()) {
                int sx = (int)((key<<36)>>40), sy=(int)((key<<4)>>56), sz=(int)((key<<12)>>40);
                byte[] val = db.get(hs.get(1), ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(key).array());
                if (val == null || val.length < 16) continue;
                byte[] d = decompress(val);
                if (d == null) continue;
                ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                bb.getLong(); long meta = bb.getLong();
                int lutSize=(int)(meta&0xFFFF); int base=16, lutBase=base+32768*2;
                if (lutSize<=0 || lutBase+lutSize*8L>d.length) continue;
                int scale = 1<<lvl;
                long x0=(long)sx*32*scale, y0=(long)sy*32*scale, z0=(long)sz*32*scale;
                int lx = (int)((wx-x0)/scale), lz = (int)((wz-z0)/scale);
                if (lx<0||lx>31||lz<0||lz>31) continue;
                System.out.println("  section sx="+sx+" sy="+sy+" sz="+sz+" worldBase y="+y0+" scale="+scale);
                StringBuilder sb = new StringBuilder("    col: ");
                for (int ly=0; ly<32; ly++) {
                    int i = (ly<<10)|(lz<<5)|lx;
                    int li = Short.toUnsignedInt(bb.getShort(base+i*2));
                    if (li>=lutSize) continue;
                    long v = bb.getLong(lutBase+li*8);
                    long airMask = ((1L<<20)-1)<<27;
                    if ((v&airMask)==0) continue;
                    long blockId = (v>>>27)&((1L<<20)-1);
                    int light = (int)(v>>>56)&0xFF;
                    int worldY = (int)((y0 + ly*scale) + scale - 1);
                    sb.append("y").append(worldY).append("[b").append(blockId).append(",L").append(light).append("] ");
                }
                System.out.println(sb);
            }
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
    }
    static byte[] decompress(byte[] in) throws Exception {
        try (com.github.luben.zstd.ZstdInputStream z = new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(in))) {
            return z.readAllBytes();
        }
    }
}
