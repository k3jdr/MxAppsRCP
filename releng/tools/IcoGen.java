import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Makes a Windows launcher icon (.ico) from an app's PNG icon: 16, 32 and 48 pixels in 256 colours and 16, 32, 48
 * and 256 pixels in true colour, all BMP entries — the images the Equinox launcher carries, which the product
 * build's icon replacement (IconExe) swaps one for one.
 *
 * <pre>java releng\tools\IcoGen.java MxDataSQL\com.k3jdr.mxdatasql\icons\app256.png MxDataSQL\com.k3jdr.mxdatasql.product\MxDataSQL.ico</pre>
 */
public class IcoGen {
    /** The launcher's own icons: 256-colour 16, 32, 48 and true-colour 16, 32, 48, 256 (size, bits). */
    private static final int[][] ENTRIES = { { 16, 8 }, { 32, 8 }, { 48, 8 }, { 16, 32 }, { 32, 32 }, { 48, 32 }, { 256, 32 } };

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java IcoGen.java <source.png> <target.ico>");
            System.exit(2);
        }
        BufferedImage source = ImageIO.read(new File(args[0]));
        List<byte[]> images = new ArrayList<>();
        for (int[] e : ENTRIES) {
            BufferedImage img = scale(source, e[0]);
            images.add(e[1] == 8 ? bmpEntry8(img) : bmpEntry(img));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * ENTRIES.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) ENTRIES.length);
        int offset = 6 + 16 * ENTRIES.length;
        for (int i = 0; i < ENTRIES.length; i++) {
            int s = ENTRIES[i][0];
            header.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            header.put((byte) 0).put((byte) 0);          // colours in palette (0 = 256 or none), reserved
            header.putShort((short) 1).putShort((short) ENTRIES[i][1]);   // planes, bits per pixel
            header.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        out.write(header.array());
        for (byte[] img : images) out.write(img);
        try (FileOutputStream f = new FileOutputStream(args[1])) {
            out.writeTo(f);
        }
        System.out.println("wrote " + args[1]);
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return dst;
    }

    /**
     * A 256-colour entry: BITMAPINFOHEADER, a fixed palette (a 6×6×6 colour cube and 40 greys), bottom-up indices and
     * the AND mask (transparent where the source is mostly transparent).
     */
    private static byte[] bmpEntry8(BufferedImage img) {
        int s = img.getWidth();
        int[] palette = new int[256];
        int n = 0;
        for (int r = 0; r < 6; r++) for (int g = 0; g < 6; g++) for (int b = 0; b < 6; b++)
            palette[n++] = (r * 51) << 16 | (g * 51) << 8 | (b * 51);
        for (int i = 0; n < 256; i++) {
            int v = Math.round(i * 255f / 39f);
            palette[n++] = v << 16 | v << 8 | v;
        }
        int pixelRow = ((s + 3) / 4) * 4;
        int maskRow = ((s + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + 256 * 4 + pixelRow * s + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(s).putInt(s * 2).putShort((short) 1).putShort((short) 8);
        b.putInt(0).putInt(pixelRow * s).putInt(0).putInt(0).putInt(256).putInt(0);
        for (int c : palette) b.put((byte) c).put((byte) (c >> 8)).put((byte) (c >> 16)).put((byte) 0);
        for (int y = s - 1; y >= 0; y--) {
            for (int x = 0; x < s; x++) b.put((byte) nearest(palette, img.getRGB(x, y)));
            for (int x = s; x < pixelRow; x++) b.put((byte) 0);
        }
        for (int y = s - 1; y >= 0; y--) {
            byte[] row = new byte[maskRow];
            for (int x = 0; x < s; x++) {
                if ((img.getRGB(x, y) >>> 24) < 128) row[x / 8] |= (byte) (0x80 >> (x % 8));
            }
            b.put(row);
        }
        return b.array();
    }

    private static int nearest(int[] palette, int argb) {
        int r = argb >> 16 & 0xff, g = argb >> 8 & 0xff, bl = argb & 0xff;
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int dr = (palette[i] >> 16 & 0xff) - r, dg = (palette[i] >> 8 & 0xff) - g, db = (palette[i] & 0xff) - bl;
            int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** A BITMAPINFOHEADER, bottom-up BGRA pixels and the AND mask, as an .ico entry holds them. */
    private static byte[] bmpEntry(BufferedImage img) {
        int s = img.getWidth();
        int maskRow = ((s + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(s).putInt(s * 2).putShort((short) 1).putShort((short) 32);
        b.putInt(0).putInt(s * s * 4).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = s - 1; y >= 0; y--) {
            for (int x = 0; x < s; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        for (int y = s - 1; y >= 0; y--) {
            byte[] row = new byte[maskRow];
            for (int x = 0; x < s; x++) {
                if ((img.getRGB(x, y) >>> 24) < 128) row[x / 8] |= (byte) (0x80 >> (x % 8));
            }
            b.put(row);
        }
        return b.array();
    }
}
