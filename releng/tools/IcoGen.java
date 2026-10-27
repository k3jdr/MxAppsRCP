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
 * Makes a Windows launcher icon (.ico) from an app's PNG icon: 16, 32, 48 and 256 pixels, 32-bit BMP entries (the
 * format the Equinox launcher's icon replacement expects).
 *
 * <pre>java releng\tools\IcoGen.java MxDataSQL\com.k3jdr.mxdatasql\icons\app256.png MxDataSQL\com.k3jdr.mxdatasql.product\MxDataSQL.ico</pre>
 */
public class IcoGen {
    private static final int[] SIZES = { 16, 32, 48, 256 };

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java IcoGen.java <source.png> <target.ico>");
            System.exit(2);
        }
        BufferedImage source = ImageIO.read(new File(args[0]));
        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) images.add(bmpEntry(scale(source, size)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * SIZES.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            header.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            header.put((byte) 0).put((byte) 0);          // colours in palette, reserved
            header.putShort((short) 1).putShort((short) 32);   // planes, bits per pixel
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
