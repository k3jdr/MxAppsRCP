package com.k3jdr.mxfilemgr.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.k3jdr.mxfilemgr.model.FileItem;
import com.k3jdr.mxfilemgr.win.ShellInfo;

/**
 * Small icons (the Mac app's NSWorkspace icons and SF Symbols): the shell's own icons for files, folders and
 * drives — by type, or per file for programs and shortcuts — and a few drawn ones for the sidebar's windows.
 * Images are cached for the life of the display. UI thread only.
 */
public final class Icons {
    private Icons() {}

    private static final Map<String, Image> CACHE = new HashMap<>();
    private static final Set<String> OWN_ICON = Set.of("exe", "ico", "lnk", "url", "cpl", "scr", "msc", "appref-ms");

    public static Image forItem(FileItem item) {
        if (item.isDrive()) return forPath(item.path(), "drive");
        if (item.isDirectory()) return folder();
        String ext = item.ext().toLowerCase(Locale.ROOT);
        if (OWN_ICON.contains(ext)) {
            Image own = cached("path:" + item.path(), () -> ShellInfo.smallIcon(item.path().toString(), false, false));
            if (own != null) return own;
        }
        Image byType = cached("ext:" + ext, () -> ShellInfo.smallIcon(ext.isEmpty() ? "file" : "file." + ext, true, false));
        return byType != null ? byType : document();
    }

    /** The shell's icon of a particular folder or drive (Desktop, Documents, OneDrive, C:). */
    public static Image forPath(Path p, String fallback) {
        Image own = cached("path:" + p, () -> ShellInfo.smallIcon(p.toString(), false, true));
        if (own != null) return own;
        return named(fallback);
    }

    public static Image folder() {
        Image shell = cached("<dir>", () -> ShellInfo.smallIcon("folder", true, true));
        return shell != null ? shell : drawn("folder");
    }

    public static Image document() { return drawn("doc"); }

    /** A drawn icon: folder, doc, drive, cloud, server, add, up, sftp, network. */
    public static Image named(String name) { return drawn(name); }

    private interface DataSource {
        ImageData get();
    }

    private static Image cached(String key, DataSource source) {
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Image img = null;
        ImageData data = source.get();
        if (data != null) {
            Display d = Display.getCurrent();
            img = new Image(d, data);
            d.disposeExec(img::dispose);
        }
        CACHE.put(key, img);
        return img;
    }

    private static Image drawn(String name) {
        String key = "drawn:" + name;
        Image existing = CACHE.get(key);
        if (existing != null) return existing;
        Display d = Display.getCurrent();
        Image img = new Image(d, 16, 16);
        GC gc = new GC(img);
        gc.setAntialias(SWT.ON);
        gc.setBackground(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(0, 0, 16, 16);
        draw(gc, name);
        gc.dispose();
        ImageData data = img.getImageData();
        img.dispose();
        // Make the background transparent.
        int bg = data.palette.getPixel(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND).getRGB());
        data.transparentPixel = bg;
        Image result = new Image(d, data);
        d.disposeExec(result::dispose);
        CACHE.put(key, result);
        return result;
    }

    private static void draw(GC gc, String name) {
        Color dark = new Color(new RGB(90, 90, 100));
        switch (name) {
            case "folder" -> {
                gc.setBackground(new Color(new RGB(240, 196, 80)));
                gc.fillRoundRectangle(1, 3, 6, 4, 2, 2);
                gc.fillRoundRectangle(1, 5, 14, 9, 2, 2);
            }
            case "doc" -> {
                gc.setBackground(new Color(new RGB(255, 255, 255)));
                gc.fillRectangle(3, 1, 10, 14);
                gc.setForeground(dark);
                gc.drawRectangle(3, 1, 10, 14);
                gc.drawLine(5, 5, 11, 5);
                gc.drawLine(5, 8, 11, 8);
                gc.drawLine(5, 11, 9, 11);
            }
            case "drive" -> {
                gc.setBackground(new Color(new RGB(170, 175, 185)));
                gc.fillRoundRectangle(1, 5, 14, 7, 3, 3);
                gc.setBackground(new Color(new RGB(60, 200, 90)));
                gc.fillRectangle(11, 8, 2, 2);
            }
            case "cloud" -> {
                gc.setBackground(new Color(new RGB(60, 130, 220)));
                gc.fillOval(2, 6, 7, 7);
                gc.fillOval(5, 3, 8, 8);
                gc.fillOval(8, 6, 7, 7);
                gc.fillRectangle(5, 9, 7, 4);
            }
            case "server" -> {
                gc.setBackground(new Color(new RGB(110, 115, 130)));
                gc.fillRectangle(2, 2, 12, 5);
                gc.fillRectangle(2, 9, 12, 5);
                gc.setBackground(new Color(new RGB(80, 220, 120)));
                gc.fillRectangle(4, 4, 2, 2);
                gc.fillRectangle(4, 11, 2, 2);
            }
            case "network" -> {
                gc.setForeground(new Color(new RGB(60, 130, 220)));
                gc.setLineWidth(2);
                gc.drawOval(2, 2, 12, 12);
                gc.setLineWidth(1);
                gc.drawLine(2, 8, 14, 8);
                gc.drawOval(5, 2, 6, 12);
            }
            case "sftp" -> {
                gc.setBackground(new Color(new RGB(40, 40, 48)));
                gc.fillRoundRectangle(1, 2, 14, 12, 3, 3);
                gc.setForeground(new Color(new RGB(120, 230, 120)));
                gc.drawLine(4, 6, 6, 8);
                gc.drawLine(6, 8, 4, 10);
                gc.drawLine(8, 10, 11, 10);
            }
            case "add" -> {
                gc.setBackground(new Color(new RGB(40, 160, 80)));
                gc.fillOval(1, 1, 14, 14);
                gc.setBackground(new Color(new RGB(255, 255, 255)));
                gc.fillRectangle(7, 4, 2, 8);
                gc.fillRectangle(4, 7, 8, 2);
            }
            case "up" -> {
                gc.setBackground(dark);
                gc.fillPolygon(new int[] { 8, 2, 13, 8, 10, 8, 10, 14, 6, 14, 6, 8, 3, 8 });
            }
            case "teams" -> {
                gc.setBackground(new Color(new RGB(80, 89, 201)));
                gc.fillRoundRectangle(1, 3, 10, 10, 3, 3);
                gc.fillOval(10, 4, 5, 5);
                gc.setForeground(new Color(new RGB(255, 255, 255)));
                gc.drawLine(3, 5, 9, 5);
                gc.drawLine(6, 5, 6, 11);
            }
            default -> {
                gc.setBackground(dark);
                gc.fillOval(4, 4, 8, 8);
            }
        }
    }
}
