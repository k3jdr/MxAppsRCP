package com.k3jdr.mxacctmgr.invest;

import org.eclipse.jface.dialogs.MessageDialog;

import com.k3jdr.mxacctmgr.App;

/** "Not saved: CODE changed elsewhere" — keep editing, or revert to the saved version (ContentView's save-conflict alert). */
final class ConflictDialog {
    private ConflictDialog() {}

    static void show(App app, String code, String message) {
        String text = (message == null ? "" : message)
                + "\n\nYour edits are still here. Revert reads the saved version (yours are lost); or keep editing and copy what you need first.";
        MessageDialog d = new MessageDialog(app.shell, "Not saved: " + code + " changed elsewhere", null, text, MessageDialog.WARNING, 0,
                "Keep Editing", "Revert to the Saved Version");
        if (d.open() == 1) app.port.revert();
    }
}
