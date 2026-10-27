package com.k3jdr.mxfilemgr.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * The few Windows APIs MxFileMGR calls directly through JNA (the jobs the macOS app does with lstat, copyfile(3),
 * NSWorkspace icons and NSPasteboard): listing a folder in one pass with its attributes and reparse tags, copying a
 * file with progress, the shell's type names and icons, and the clipboard's change counter.
 */
public final class Win32 {
    private Win32() {}

    public static final int FILE_ATTRIBUTE_READONLY = 0x1;
    public static final int FILE_ATTRIBUTE_HIDDEN = 0x2;
    public static final int FILE_ATTRIBUTE_SYSTEM = 0x4;
    public static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    public static final int FILE_ATTRIBUTE_ARCHIVE = 0x20;
    public static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    public static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
    public static final int FILE_ATTRIBUTE_OFFLINE = 0x1000;
    public static final int FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS = 0x400000;
    public static final int IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003;
    public static final int IO_REPARSE_TAG_SYMLINK = 0xA000000C;

    public static final int DRIVE_REMOVABLE = 2, DRIVE_FIXED = 3, DRIVE_REMOTE = 4, DRIVE_CDROM = 5, DRIVE_RAMDISK = 6;

    /** WIN32_FIND_DATAW. */
    @Structure.FieldOrder({ "dwFileAttributes", "ftCreationLow", "ftCreationHigh", "ftAccessLow", "ftAccessHigh",
            "ftWriteLow", "ftWriteHigh", "nFileSizeHigh", "nFileSizeLow", "dwReserved0", "dwReserved1", "cFileName",
            "cAlternateFileName" })
    public static class FindData extends Structure {
        public int dwFileAttributes;
        public int ftCreationLow, ftCreationHigh, ftAccessLow, ftAccessHigh, ftWriteLow, ftWriteHigh;
        public int nFileSizeHigh, nFileSizeLow;
        public int dwReserved0, dwReserved1;
        public char[] cFileName = new char[260];
        public char[] cAlternateFileName = new char[14];

        public String name() { return Native.toString(cFileName); }
        public long fileSize() { return ((long) nFileSizeHigh << 32) | (nFileSizeLow & 0xFFFFFFFFL); }
        public long modified() { return fileTimeToMillis(ftWriteLow, ftWriteHigh); }
        public long created() { return fileTimeToMillis(ftCreationLow, ftCreationHigh); }
    }

    /** SHFILEINFOW. */
    @Structure.FieldOrder({ "hIcon", "iIcon", "dwAttributes", "szDisplayName", "szTypeName" })
    public static class ShFileInfo extends Structure {
        public Pointer hIcon;
        public int iIcon;
        public int dwAttributes;
        public char[] szDisplayName = new char[260];
        public char[] szTypeName = new char[80];
    }

    /** CopyFileEx's progress routine: return 0 to go on, 1 to cancel (the partial copy is removed). */
    public interface CopyProgress extends StdCallLibrary.StdCallCallback {
        int invoke(long totalSize, long transferred, long streamSize, long streamTransferred, int streamNumber,
                int reason, Pointer source, Pointer destination, Pointer data);
    }

    public interface Kernel extends StdCallLibrary {
        Pointer FindFirstFileExW(WString fileName, int infoLevel, FindData data, int searchOp, Pointer filter, int flags);
        boolean FindNextFileW(Pointer handle, FindData data);
        boolean FindClose(Pointer handle);
        boolean CopyFileExW(WString source, WString destination, CopyProgress progress, Pointer data,
                IntByReference cancel, int flags);
        int GetFileAttributesW(WString fileName);
        boolean SetFileAttributesW(WString fileName, int attributes);
    }

    public interface Shell extends StdCallLibrary {
        Pointer SHGetFileInfoW(WString path, int attributes, ShFileInfo info, int size, int flags);
    }

    public interface User extends StdCallLibrary {
        int GetClipboardSequenceNumber();
        boolean OpenClipboard(Pointer owner);
        boolean EmptyClipboard();
        boolean CloseClipboard();
    }

    private static Kernel kernel;
    private static Shell shell;
    private static User user;
    private static boolean failed;

    /** kernel32, or null when JNA can't load it (the callers then use java.nio). */
    public static synchronized Kernel kernel() {
        if (kernel == null && !failed) {
            try {
                kernel = Native.load("kernel32", Kernel.class);
            } catch (Throwable t) {
                failed = true;
            }
        }
        return kernel;
    }

    public static synchronized Shell shell() {
        if (shell == null) {
            try {
                shell = Native.load("shell32", Shell.class);
            } catch (Throwable t) {
                return null;
            }
        }
        return shell;
    }

    public static synchronized User user() {
        if (user == null) {
            try {
                user = Native.load("user32", User.class);
            } catch (Throwable t) {
                return null;
            }
        }
        return user;
    }

    public static long fileTimeToMillis(int low, int high) {
        long ft = ((long) high << 32) | (low & 0xFFFFFFFFL);
        if (ft == 0) return 0;
        return ft / 10_000L - 11_644_473_600_000L;
    }

    /** The path with the \\?\ prefix when it is longer than the classic MAX_PATH allows. */
    public static String longPath(String path) {
        if (path.length() < 240 || path.startsWith("\\\\?\\")) return path;
        if (path.startsWith("\\\\")) return "\\\\?\\UNC\\" + path.substring(2);
        return "\\\\?\\" + path;
    }

    public static boolean isInvalid(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == -1L;
    }
}
