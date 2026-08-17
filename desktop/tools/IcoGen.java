import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/**
 * Gera um .ico (multi-tamanho, com imagens PNG embutidas — formato aceito pelo
 * Windows Vista+ e pelo jpackage) a partir de um PNG de origem.
 * Uso: java IcoGen <origem.png> <destino.ico>
 */
public class IcoGen {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        java.util.List<byte[]> pngs = new ArrayList<byte[]>();
        for(int s : sizes){
            BufferedImage b = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = b.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, s, s, null);
            g.dispose();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(b, "png", bo);
            pngs.add(bo.toByteArray());
        }
        int n = sizes.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLE16(out, 0);      // reserved
        writeLE16(out, 1);      // type = icon
        writeLE16(out, n);      // count
        int offset = 6 + 16 * n; // header + directory entries
        for(int i = 0; i < n; i++){
            int s = sizes[i];
            byte[] png = pngs.get(i);
            out.write(s >= 256 ? 0 : s);   // width (0 = 256)
            out.write(s >= 256 ? 0 : s);   // height (0 = 256)
            out.write(0);                   // color count
            out.write(0);                   // reserved
            writeLE16(out, 1);              // color planes
            writeLE16(out, 32);             // bits per pixel
            writeLE32(out, png.length);     // size of image data
            writeLE32(out, offset);         // offset of image data
            offset += png.length;
        }
        for(byte[] png : pngs) out.write(png);
        FileOutputStream fo = new FileOutputStream(a[1]);
        fo.write(out.toByteArray());
        fo.close();
        System.out.println("ICO gerado: " + a[1] + "  (" + n + " tamanhos, " + out.size() + " bytes)");
    }
    static void writeLE16(OutputStream o, int v) throws IOException { o.write(v & 0xFF); o.write((v>>8) & 0xFF); }
    static void writeLE32(OutputStream o, int v) throws IOException { o.write(v & 0xFF); o.write((v>>8)&0xFF); o.write((v>>16)&0xFF); o.write((v>>24)&0xFF); }
}
