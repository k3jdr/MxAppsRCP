package com.k3jdr.mxfilemgr.win;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.FileUtils;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinUser;

/**
 * What Windows' shell knows about files (the macOS app's NSWorkspace): type names ("Text Document"), small icons,
 * opening, Open With, Show in Explorer, the Recycle Bin and the shell's own Properties window.
 */
public final class ShellInfo {
    private ShellInfo() {}

    private static final int SHGFI_ICON = 0x100, SHGFI_SMALLICON = 0x1, SHGFI_TYPENAME = 0x400,
            SHGFI_USEFILEATTRIBUTES = 0x10;
    private static final Map<String, String> TYPE_NAMES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> COM = ThreadLocal.withInitial(() -> {
        try {
            Ole32.INSTANCE.CoInitializeEx(null, 2);
        } catch (Throwable t) {
            // the shell calls below still work without COM for plain type names
        }
        return true;
    });

    /** The shell's name for a kind of file: "Text Document", "File folder", "PNG File"; cached by extension. */
    public static String typeName(String ext, boolean folder) {
        String key = folder ? "<dir>" : ext.toLowerCase(Locale.ROOT);
        return TYPE_NAMES.computeIfAbsent(key, k -> {
            String name = null;
            Win32.Shell shell = Win32.shell();
            if (shell != null) {
                COM.get();
                Win32.ShFileInfo info = new Win32.ShFileInfo();
                String probe = folder ? "folder" : "file" + (ext.isEmpty() ? "" : "." + ext);
                Pointer r = shell.SHGetFileInfoW(new WString(probe),
                        folder ? Win32.FILE_ATTRIBUTE_DIRECTORY : Win32.FILE_ATTRIBUTE_NORMAL, info, info.size(),
                        SHGFI_TYPENAME | SHGFI_USEFILEATTRIBUTES);
                if (r != null && Pointer.nativeValue(r) != 0) {
                    info.read();
                    name = com.sun.jna.Native.toString(info.szTypeName);
                }
            }
            if (name == null || name.isBlank()) {
                name = folder ? "File folder" : ext.isEmpty() ? "File" : ext.toUpperCase(Locale.ROOT) + " File";
            }
            return name;
        });
    }

    /**
     * The small shell icon of a path (its own icon: programs, shortcuts, drives) or, with {@code byType}, of any
     * file with that name's extension / any folder. Call on the UI thread. Null when the shell has none.
     */
    public static ImageData smallIcon(String pathOrName, boolean byType, boolean folder) {
        Win32.Shell shell = Win32.shell();
        if (shell == null) return null;
        try {
            Win32.ShFileInfo info = new Win32.ShFileInfo();
            int flags = SHGFI_ICON | SHGFI_SMALLICON | (byType ? SHGFI_USEFILEATTRIBUTES : 0);
            int attrs = folder ? Win32.FILE_ATTRIBUTE_DIRECTORY : Win32.FILE_ATTRIBUTE_NORMAL;
            Pointer r = shell.SHGetFileInfoW(new WString(pathOrName), attrs, info, info.size(), flags);
            if (r == null || Pointer.nativeValue(r) == 0) return null;
            info.read();
            if (info.hIcon == null) return null;
            WinDef.HICON icon = new WinDef.HICON(info.hIcon);
            try {
                return toImageData(icon);
            } finally {
                User32.INSTANCE.DestroyIcon(icon);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static ImageData toImageData(WinDef.HICON icon) {
        WinGDI.ICONINFO ii = new WinGDI.ICONINFO();
        if (!User32.INSTANCE.GetIconInfo(icon, ii)) return null;
        WinDef.HDC dc = GDI32.INSTANCE.CreateCompatibleDC(null);
        try {
            if (ii.hbmColor == null) return null;
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO(256);
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            if (GDI32.INSTANCE.GetDIBits(dc, ii.hbmColor, 0, 0, null, bmi, 0) == 0) return null;
            int w = bmi.bmiHeader.biWidth, h = Math.abs(bmi.bmiHeader.biHeight);
            if (w <= 0 || h <= 0 || w > 256 || h > 256) return null;
            byte[] color = bits(dc, ii.hbmColor, w, h);
            if (color == null) return null;
            byte[] mask = ii.hbmMask != null ? bits(dc, ii.hbmMask, w, h) : null;
            boolean hasAlpha = false;
            for (int i = 3; i < color.length; i += 4) if (color[i] != 0) { hasAlpha = true; break; }
            byte[] alpha = new byte[w * h];
            for (int i = 0; i < w * h; i++) {
                if (hasAlpha) alpha[i] = color[i * 4 + 3];
                else alpha[i] = mask == null || (mask[i * 4] == 0 && mask[i * 4 + 1] == 0 && mask[i * 4 + 2] == 0) ? (byte) 255 : 0;
            }
            ImageData data = new ImageData(w, h, 32, new PaletteData(0xFF00, 0xFF0000, 0xFF000000));
            System.arraycopy(color, 0, data.data, 0, Math.min(color.length, data.data.length));
            data.alphaData = alpha;
            return data;
        } finally {
            if (ii.hbmColor != null) GDI32.INSTANCE.DeleteObject(ii.hbmColor);
            if (ii.hbmMask != null) GDI32.INSTANCE.DeleteObject(ii.hbmMask);
            GDI32.INSTANCE.DeleteDC(dc);
        }
    }

    private static byte[] bits(WinDef.HDC dc, WinDef.HBITMAP bitmap, int w, int h) {
        WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO(256);
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = w;
        bmi.bmiHeader.biHeight = -h;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = 0;
        Memory buffer = new Memory((long) w * h * 4);
        if (GDI32.INSTANCE.GetDIBits(dc, bitmap, 0, h, buffer, bmi, 0) == 0) return null;
        return buffer.getByteArray(0, w * h * 4);
    }

    // MARK: Launching

    /** ShellExecute: runs a program (with its own console window for console programs) or opens a document. */
    public static boolean execute(String verb, String file, String parameters, String directory) {
        try {
            WinDef.INT_PTR r = Shell32.INSTANCE.ShellExecute(null, verb, file, parameters, directory, WinUser.SW_SHOWNORMAL);
            return r != null && r.longValue() > 32;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens a file with its default app (or a folder in Explorer). */
    public static boolean open(Path p) {
        return execute(null, p.toString(), null, p.getParent() == null ? null : p.getParent().toString());
    }

    /** Windows' "Open with" chooser. */
    public static boolean openWith(Path p) {
        if (execute("openas", p.toString(), null, null)) return true;
        return execute(null, "rundll32.exe", "shell32.dll,OpenAs_RunDLL " + p, null);
    }

    /** Opens Explorer with the item selected (or the folder when {@code select} is false). */
    public static boolean showInExplorer(Path p, boolean select) {
        if (!select || p.getParent() == null) return execute(null, "explorer.exe", "\"" + p + "\"", null);
        return execute(null, "explorer.exe", "/select,\"" + p + "\"", null);
    }

    /** The shell's own Properties window for an item (Windows Properties…). */
    public static boolean shellProperties(Path p) {
        try {
            ShellAPI.SHELLEXECUTEINFO sei = new ShellAPI.SHELLEXECUTEINFO();
            sei.cbSize = sei.size();
            sei.fMask = 0x0000000C | 0x00000040; // SEE_MASK_INVOKEIDLIST | SEE_MASK_NOCLOSEPROCESS
            sei.lpVerb = "properties";
            sei.lpFile = p.toString();
            sei.nShow = WinUser.SW_SHOW;
            boolean ok = Shell32.INSTANCE.ShellExecuteEx(sei);
            if (sei.hProcess != null) Kernel32.INSTANCE.CloseHandle(sei.hProcess);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Moves an item to the Recycle Bin (SHFileOperation with FOF_ALLOWUNDO). */
    public static void recycle(Path p) throws IOException {
        FileUtils fu = FileUtils.getInstance();
        if (!fu.hasTrash()) throw new IOException("The Recycle Bin isn't available.");
        fu.moveToTrash(new File[] { p.toFile() });
        if (p.toFile().exists()) throw new IOException("Windows didn't move it to the Recycle Bin.");
    }

    /** The drive type of a root such as C:\ (Win32.DRIVE_FIXED, DRIVE_REMOVABLE, DRIVE_REMOTE, …); 0 if unknown. */
    public static int driveType(Path root) {
        try {
            if (root.toString().startsWith("\\\\")) return Win32.DRIVE_REMOTE;
            return Kernel32.INSTANCE.GetDriveType(root.toString());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The drive or share has a Recycle Bin (local fixed drives); elsewhere Windows would delete for good. */
    public static boolean hasRecycleBin(Path p) {
        Path root = p.toAbsolutePath().getRoot();
        return root != null && driveType(root) == Win32.DRIVE_FIXED;
    }
}
