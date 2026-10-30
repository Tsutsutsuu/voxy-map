import org.rocksdb.*;
import java.nio.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
public class IdDump4 {
    public static void main(String[] a) throws Exception {
        RocksDB.loadLibrary();
        List<ColumnFamilyDescriptor> cfds = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            new ColumnFamilyDescriptor("world_sections".getBytes()),
            new ColumnFamilyDescriptor("id_mappings".getBytes()));
        List<ColumnFamilyHandle> hs = new ArrayList<>();
        RocksDB db = RocksDB.openReadOnly(new DBOptions().setCreateIfMissing(false), a[0], cfds, hs);
        Map<Long, String> biomes = new TreeMap<>();
        try (RocksIterator it = db.newIterator(hs.get(2))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] k = it.key();
                byte[] v = it.value();
                if (k != null && k.length == 4 && v != null) {
                    long be = ((long)(k[0]&0xFF)<<24)|((long)(k[1]&0xFF)<<16)|((long)(k[2]&0xFF)<<8)|(k[3]&0xFF);
                    if ((be >>> 24) == 0x80) {
                        String text = unzip(v);
                        if (text != null) {
                            int m = text.indexOf("minecraft:");
                            if (m < 0) m = text.indexOf(':');
                            if (m >= 0) {
                                int e = m;
                                while (e < text.length()) {
                                    char ch = text.charAt(e);
                                    boolean ok = (ch>='a'&&ch<='z')||(ch>='0'&&ch<='9')||ch=='_'||ch==':'||ch=='.'||ch=='-';
                                    if (!ok) break;
                                    e++;
                                }
                                biomes.put(be & 0x3FFFFFFFL, text.substring(m, e));
                            }
                        }
                    }
                }
                it.next();
            }
        }
        System.out.println("biome entries: " + biomes.size());
        long maxId = biomes.isEmpty() ? 0 : ((TreeMap<Long,String>)biomes).lastKey();
        System.out.println("max biome id: " + maxId + " (9-bit max 511, overflow=" + (maxId > 511) + ")");
        int shown = 0;
        for (Map.Entry<Long,String> e : biomes.entrySet()) {
            if (shown++ >= 12) break;
            System.out.println("  id=" + e.getKey() + " " + e.getValue());
        }
        System.out.println("ice_spikes entries:");
        for (Map.Entry<Long,String> e : biomes.entrySet()) {
            if (e.getValue().contains("ice_spikes")) System.out.println("  id=" + e.getKey() + " " + e.getValue());
        }
        System.out.println("lavender entries:");
        for (Map.Entry<Long,String> e : biomes.entrySet()) {
            if (e.getValue().contains("lavender")) System.out.println("  id=" + e.getKey() + " " + e.getValue());
        }
        // sample voxel at column
        int wx = Integer.parseInt(a[1]), wz = Integer.parseInt(a[2]);
        try (RocksIterator it = db.newIterator(hs.get(1))) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] kb = it.key();
                if (kb != null && kb.length == 8) {
                    long key = ByteBuffer.wrap(kb).order(ByteOrder.BIG_ENDIAN).getLong();
                    int lvl=(int)((key>>>60)&0xF); int sx=(int)((key<<36)>>40); int sy=(int)((key<<4)>>56); int sz=(int)((key<<12)>>40);
                    long scale=1L<<lvl; long x0=(long)sx*32*scale; long z0=(long)sz*32*scale;
                    if (wx>=x0 && wx<x0+32*scale && wz>=z0 && wz<z0+32*scale && lvl<=1) {
                        int lx=(int)((wx-x0)/scale), lz=(int)((wz-z0)/scale);
                        byte[] val = db.get(hs.get(1), kb);
                        byte[] d = decompress(val);
                        if (d == null) { it.next(); continue; }
                        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
                        bb.getLong(); long meta=bb.getLong();
                        int lutSize=(int)(meta&0xFFFF); int base=16; int lutBase=base+32768*2;
                        if (lutSize<=0) { it.next(); continue; }
                        int ty=-1; long tv=0;
                        for (int ly=31; ly>=0; ly--) {
                            int i2=(ly<<10)|(lz<<5)|lx;
                            int li=Short.toUnsignedInt(bb.getShort(base+i2*2));
                            if (li>=lutSize) continue;
                            long v=bb.getLong(lutBase+li*8);
                            long airMask=((1L<<20)-1)<<27;
                            if ((v&airMask)==0) continue;
                            ty=ly; tv=v; break;
                        }
                        if (ty<0) { it.next(); continue; }
                        long wy=(sy*32+ty)*scale+scale-1;
                        long biomeField=(tv>>>47)&0x1FF;
                        System.out.println("column lvl="+lvl+" y="+wy+" biomeField="+biomeField+" name="+biomes.get(biomeField));
                    }
                }
                it.next();
            }
        }
        hs.forEach(ColumnFamilyHandle::close); db.close();
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
            for (byte b : out) { char c=(char)(b&0xFF); sb.append(c>=32&&c<127?c:(c==0?' ':'.')); }
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
