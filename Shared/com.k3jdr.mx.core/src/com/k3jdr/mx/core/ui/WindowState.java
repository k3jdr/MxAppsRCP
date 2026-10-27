package com.k3jdr.mx.core.ui;

import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.prefs.Prefs;

/**
 * Where the main window was and how big, kept between launches (the apps run with persistState=false, so the e4
 * model is rebuilt from Application.e4xmi each time and the window is placed from these settings).
 */
public final class WindowState {
    private WindowState() {}

    private static final Prefs PREFS = Prefs.of("com.k3jdr.mx.core.window");

    /** Applies the remembered bounds to the window model (call from the life cycle's @ProcessAdditions). */
    public static void restore(MWindow window, String app) {
        Prefs p = PREFS.child(app);
        if (!p.has("w")) return;
        window.setX(p.getInt("x", window.getX()));
        window.setY(p.getInt("y", window.getY()));
        window.setWidth(Math.max(600, p.getInt("w", window.getWidth())));
        window.setHeight(Math.max(400, p.getInt("h", window.getHeight())));
        if (p.getBoolean("maximized", false)) window.getTags().add("shellMaximized");
    }

    /** Remembers the shell's bounds when it closes. */
    public static void track(Shell shell, String app) {
        shell.addListener(SWT.Close, e -> save(shell, app));
    }

    public static void save(Shell shell, String app) {
        if (shell.isDisposed()) return;
        Prefs p = PREFS.child(app);
        boolean max = shell.getMaximized();
        p.putBoolean("maximized", max);
        if (!max && !shell.getMinimized()) {
            Rectangle b = shell.getBounds();
            p.putInt("x", b.x);
            p.putInt("y", b.y);
            p.putInt("w", b.width);
            p.putInt("h", b.height);
        }
    }
}
