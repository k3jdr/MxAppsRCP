package com.k3jdr.mxfilemgr.tools;

import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * What the tool windows share: a top-level window with the app's icon, its size and place remembered, and Quick
 * Help telling about the window (and what's selected in it) while it is in front.
 */
final class ToolWindows {
    private ToolWindows() {}

    private static int cascade;

    /** A new tool window; {@code key} names its remembered bounds. */
    static Shell create(Workspace ws, String key, String title, int width, int height, Supplier<QuickHelpItem> screen,
            Supplier<QuickHelpItem> selection) {
        Shell shell = new Shell(ws.display(), SWT.SHELL_TRIM);
        shell.setText(title);
        if (!ws.shell().isDisposed()) shell.setImages(ws.shell().getImages());
        Rectangle saved = savedBounds(key);
        Rectangle area = ws.display().getClientArea();
        if (saved != null && area.intersects(saved)) {
            int off = (cascade++ % 6) * 24;
            shell.setBounds(saved.x + off, saved.y + off, saved.width, saved.height);
        } else {
            Rectangle main = ws.shell().isDisposed() ? area : ws.shell().getBounds();
            int off = (cascade++ % 6) * 24;
            int x = main.x + Math.max(0, (main.width - width) / 2) + off;
            int y = main.y + Math.max(0, (main.height - height) / 3) + off;
            shell.setBounds(x, y, width, height);
        }
        shell.addListener(SWT.Activate, e -> {
            QuickHelp.get().setScreen(screen);
            QuickHelp.get().setSelection(selection);
        });
        shell.addListener(SWT.Close, e -> saveBounds(key, shell));
        shell.addListener(SWT.Dispose, e -> {
            // Back to the main window's help once this window is gone.
            QuickHelp.get().setScreen(() -> FileHelp.MAIN);
            QuickHelp.get().setSelection(() -> {
                Workspace current = Workspace.current();
                return current == null ? null : FileHelp.selection(current.activeTab());
            });
        });
        return shell;
    }

    static Rectangle savedBounds(String key) {
        String raw = Settings.PREFS.get("window." + key, null);
        if (raw == null) return null;
        try {
            String[] p = raw.split(",");
            Rectangle r = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            return r.width >= 200 && r.height >= 150 ? r : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void saveBounds(String key, Shell shell) {
        if (shell.isDisposed() || shell.getMaximized() || shell.getMinimized()) return;
        Rectangle b = shell.getBounds();
        Settings.PREFS.put("window." + key, b.x + "," + b.y + "," + b.width + "," + b.height);
    }

    /** Runs {@code r} on the UI thread later, unless the display is gone. */
    static void ui(Display display, Runnable r) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (!display.isDisposed()) r.run();
        });
    }
}
