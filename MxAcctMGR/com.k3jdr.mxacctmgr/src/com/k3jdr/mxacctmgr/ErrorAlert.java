package com.k3jdr.mxacctmgr;

import org.eclipse.jface.dialogs.MessageDialog;

import com.k3jdr.mx.core.ui.Clipboards;

/**
 * The app's alert (ContentView's "MxAcctMGR" alert): the message, OK, and Layer 3 Services… when a service was
 * involved — which shows how to start them. Copy puts the message on the clipboard.
 */
public final class ErrorAlert {
    private ErrorAlert() {}

    public static void show(App app, String message) {
        if (app.shell.isDisposed() || message == null || message.isBlank()) return;
        boolean service = message.contains("service") || message.contains("port ");
        String[] buttons = service ? new String[] { "OK", "Layer 3 Services…", "Copy" } : new String[] { "OK", "Copy" };
        MessageDialog d = new MessageDialog(app.shell, "MxAcctMGR", null, message, MessageDialog.WARNING, 0, buttons);
        int choice = d.open();
        String label = choice >= 0 && choice < buttons.length ? buttons[choice] : "OK";
        if (label.equals("Layer 3 Services…")) app.go(Screen.SERVICES);
        else if (label.equals("Copy")) Clipboards.copyText(app.shell.getDisplay(), message);
    }
}
