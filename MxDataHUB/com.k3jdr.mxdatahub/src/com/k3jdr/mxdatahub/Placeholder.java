package com.k3jdr.mxdatahub;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Ui;

/**
 * An empty state in the middle of a pane, as SwiftUI's ContentUnavailableView: a title, a sentence that can be
 * selected and copied, and buttons.
 */
final class Placeholder extends Composite {
    private final Label title;
    private final Text detail;
    private final Composite buttons;

    Placeholder(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));
        Composite box = new Composite(this, SWT.NONE);
        box.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
        GridLayout gl = Ui.grid(1, 10, 8);
        box.setLayout(gl);
        title = new Label(box, SWT.CENTER);
        title.setFont(Ui.subheading());
        title.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        detail = new Text(box, SWT.READ_ONLY | SWT.WRAP | SWT.CENTER | SWT.MULTI);
        detail.setForeground(Ui.SECONDARY);
        GridData dg = new GridData(SWT.CENTER, SWT.TOP, true, false);
        dg.widthHint = 460;
        detail.setLayoutData(dg);
        buttons = new Composite(box, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, true, false));
        buttons.setLayout(Ui.grid(4, 0, 8));
        addListener(SWT.Resize, e -> layout(true, true));
    }

    /** Shows the title and sentence; clears the buttons (add new ones with {@link #button}). */
    Placeholder show(String titleText, String detailText) {
        title.setText(Ui.escape(titleText));
        detail.setText(detailText == null ? "" : detailText);
        detail.setBackground(getBackground());
        Ui.setVisible(detail, detailText != null && !detailText.isEmpty());
        for (Control c : buttons.getChildren()) c.dispose();
        layout(true, true);
        return this;
    }

    Button button(String text, Runnable action) {
        Button b = Ui.button(buttons, text, action);
        buttons.setLayout(Ui.grid(Math.max(1, buttons.getChildren().length), 0, 8));
        layout(true, true);
        return b;
    }

    Composite buttons() { return buttons; }
}
