package com.k3jdr.mxfilemgr.win;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.dnd.ByteArrayTransfer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.widgets.Display;

/**
 * Files on the clipboard the way Explorer puts them there (the Mac's NSPasteboard file URLs): CF_HDROP with
 * "Preferred DropEffect" — copy, or move for Cut — plus the paths as text for other apps.
 */
public final class ClipboardFiles {
    private ClipboardFiles() {}

    public static final int COPY = 1, MOVE = 2;

    public record Contents(List<Path> files, boolean cut) {}

    /** Explorer's "Preferred DropEffect": a DWORD saying whether pasting should copy or move. */
    public static final class DropEffectTransfer extends ByteArrayTransfer {
        private static final String NAME = "Preferred DropEffect";
        private static final int ID = registerType(NAME);
        private static final DropEffectTransfer INSTANCE = new DropEffectTransfer();

        public static DropEffectTransfer getInstance() { return INSTANCE; }

        @Override
        protected String[] getTypeNames() { return new String[] { NAME }; }

        @Override
        protected int[] getTypeIds() { return new int[] { ID }; }

        @Override
        protected void javaToNative(Object object, TransferData transferData) {
            int v = (Integer) object;
            super.javaToNative(new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) }, transferData);
        }

        @Override
        protected Object nativeToJava(TransferData transferData) {
            Object o = super.nativeToJava(transferData);
            if (!(o instanceof byte[] b) || b.length < 4) return null;
            return (b[0] & 0xFF) | (b[1] & 0xFF) << 8 | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
        }

        @Override
        protected boolean validate(Object object) { return object instanceof Integer; }
    }

    public static void put(Display display, List<Path> paths, boolean cut) {
        if (paths.isEmpty()) return;
        String[] names = paths.stream().map(Path::toString).toArray(String[]::new);
        Clipboard cb = new Clipboard(display);
        try {
            cb.setContents(new Object[] { names, cut ? MOVE : COPY, String.join("\r\n", names) },
                    new Transfer[] { FileTransfer.getInstance(), DropEffectTransfer.getInstance(), TextTransfer.getInstance() });
        } finally {
            cb.dispose();
        }
    }

    public static Contents get(Display display) {
        Clipboard cb = new Clipboard(display);
        try {
            List<Path> files = new ArrayList<>();
            if (cb.getContents(FileTransfer.getInstance()) instanceof String[] names) {
                for (String n : names) files.add(Path.of(n));
            }
            boolean cut = cb.getContents(DropEffectTransfer.getInstance()) instanceof Integer effect && (effect & MOVE) != 0
                    && (effect & COPY) == 0;
            return new Contents(files, cut);
        } finally {
            cb.dispose();
        }
    }

    /** Empties the system clipboard (Clear Clipboard). */
    public static boolean clear() {
        Win32.User u = Win32.user();
        if (u == null || !u.OpenClipboard(null)) return false;
        try {
            return u.EmptyClipboard();
        } finally {
            u.CloseClipboard();
        }
    }

    /** Changes each time anything is put on the clipboard (to follow it without reading it). */
    public static int sequence() {
        Win32.User u = Win32.user();
        return u == null ? 0 : u.GetClipboardSequenceNumber();
    }
}
