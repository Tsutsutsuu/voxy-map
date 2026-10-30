import com.github.luben.zstd.Zstd;
import java.io.*;
import java.nio.*;
import java.util.*;
public class CacheProbe {
    public static void main(String[] a) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a[0]));
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int magic = dis.readInt();
        int ver = dis.readInt();
        int w = dis.readInt(), h = dis.readInt();
        long mwx = dis.readLong(), mwz = dis.readLong();
        int pb = dis.readInt();
        long mtime = dis.readLong();
        int bioN = dis.readInt();
        Map<Integer,String> names = new HashMap<>();
        for (int i = 0; i < bioN; i++) { int id = dis.readInt(); names.put(id, dis.readUTF()); }
        int clen = dis.readInt();
        byte[] comp = new byte[clen]; dis.readFully(comp);
        byte[] raw = Zstd.decompress(comp, w*h*8);
        long[] px = new long[w*h];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(px);
        System.out.println("cache v" + ver + " " + w + "x" + h + " minWX=" + mwx + " minWZ=" + mwz + " pb=" + pb + " names=" + bioN);
        int wx = Integer.parseInt(a[1]), wz = Integer.parseInt(a[2]);
        int gx = (int) Math.floorDiv((long) wx - mwx, pb), gz = (int) Math.floorDiv((long) wz - mwz, pb);
        long p = px[gz*w + gx];
        System.out.println("pixel " + gx + "," + gz + " top=" + ((int)((p>>>24)&0xFFFF)-32768) + " biome=" + ((int)((p>>>53)&0x1FF)) + " name=" + names.get((int)((p>>>53)&0x1FF)));
        for (int dz=-1;dz<=1;dz++) for (int dx=-1;dx<=1;dx++) {
            int j = (gz+dz)*w + (gx+dx);
            if (j<0||j>=px.length) continue;
            long q = px[j];
            int bi = (int)((q>>>53)&0x1FF);
            System.out.println("  neighbor " + (gx+dx) + "," + (gz+dz) + " top=" + ((int)((q>>>24)&0xFFFF)-32768) + " biome=" + bi + " " + names.get(bi));
        }
    }
}
