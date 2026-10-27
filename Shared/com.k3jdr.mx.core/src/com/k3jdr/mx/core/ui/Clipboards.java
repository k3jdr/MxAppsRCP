package com.k3jdr.mx.core.ui;

import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

/** Plain text on and off the clipboard. */
public final class Clipboards {
    private Clipboards() {}

    public static void copyText(Display display, String text) {
        if (text == null || text.isEmpty()) return;
        Clipboard cb = new Clipboard(display);
        try {
            cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            cb.dispose();
        }
    }

    public static String text(Display display) {
        Clipboard cb = new Clipboard(display);
        try {
            Object o = cb.getContents(TextTransfer.getInstance());
            return o instanceof String s ? s : null;
        } finally {
            cb.dispose();
        }
    }
}
