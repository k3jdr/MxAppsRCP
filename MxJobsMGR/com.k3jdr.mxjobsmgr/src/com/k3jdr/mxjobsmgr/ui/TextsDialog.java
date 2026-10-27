package com.k3jdr.mxjobsmgr.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.NodeKind;

/** Edit Element… (a palette element's title and description) and Edit Step… (a step's title and description). */
public final class TextsDialog extends Dialog {
    private final String heading, sub, titleValue, titlePrompt, titleHelp, descValue, descPrompt, descHelp, note;
    private final String resetTitle, resetDesc;
    private final int color;
    private Text title, desc;
    private String[] result;

    private TextsDialog(Shell parent, String heading, String sub, int color, String titleValue, String titlePrompt, String titleHelp,
                        String descValue, String descPrompt, String descHelp, String note, String resetTitle, String resetDesc) {
        super(parent);
        this.heading = heading;
        this.sub = sub;
        this.color = color;
        this.titleValue = titleValue;
        this.titlePrompt = titlePrompt;
        this.titleHelp = titleHelp;
        this.descValue = descValue;
        this.descPrompt = descPrompt;
        this.descHelp = descHelp;
        this.note = note;
        this.resetTitle = resetTitle;
        this.resetDesc = resetDesc;
    }

    /** Renames a palette element and rewrites its description. */
    public static void editElement(Shell parent, NodeKind kind) {
        TextsDialog d = new TextsDialog(parent, "Edit Element", "Layer " + kind.layer.number + " · " + kind.layer.title, kind.layer.rgb,
                PaletteEdits.title(kind), kind.title,
                "The element's name in the palette, and the title of new steps added from it. Leave it empty for the built-in name, " + kind.title + ".",
                PaletteEdits.summary(kind), kind.summary, "The line under the element's name in the palette. Leave it empty for the built-in one.",
                "Built in: " + kind.title + " — " + kind.summary + "\nKept on this PC with MxJobsMGR's palette, the same in every jobspace. Steps already on a canvas keep their titles.",
                kind.title, kind.summary);
        if (d.open() == OK) PaletteEdits.set(kind, d.result[0], d.result[1]);
    }

    /** Edit Step…: the step's title and description (its notes). */
    public static String[] editStep(Shell parent, JobNode node) {
        TextsDialog d = new TextsDialog(parent, "Edit Step", node.kind.title + " · Layer " + node.layer().number + " · " + node.layer().title,
                node.layer().rgb, node.title, PaletteEdits.title(node.kind), "The step's name on the canvas, in the run log and in the inspector.",
                node.notes, "What this step is for", "Your notes on the step (Notes in the inspector), kept in the jobspace.", null, null, null);
        return d.open() == OK ? d.result : null;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(heading);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 14, 8));
        Ui.label(area, heading, Ui.subheading()).setLayoutData(Ui.span(2));
        Label s = Ui.label(area, sub);
        s.setForeground(Colors.of(color));
        s.setLayoutData(Ui.span(2));
        Ui.label(area, "Title:");
        title = new Text(area, SWT.BORDER);
        title.setText(titleValue);
        title.setMessage(titlePrompt);
        GridData tg = Ui.fillH();
        tg.widthHint = 340;
        title.setLayoutData(tg);
        QuickHelp.help(title, "Title", titleHelp);
        Label dl = Ui.label(area, "Description:");
        dl.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false));
        desc = new Text(area, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        desc.setText(descValue);
        desc.setMessage(descPrompt);
        GridData dg = new GridData(SWT.FILL, SWT.FILL, true, true);
        dg.heightHint = 70;
        desc.setLayoutData(dg);
        QuickHelp.help(desc, "Description", descHelp);
        if (note != null) {
            Label n = new Label(area, SWT.WRAP);
            n.setText(Ui.escape(note));
            n.setForeground(Ui.SECONDARY);
            GridData ng = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            ng.widthHint = 400;
            n.setLayoutData(ng);
        }
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        if (resetTitle != null) {
            var reset = createButton(parent, IDialogConstants.CLIENT_ID, "Reset to Built-in", false);
            QuickHelp.help(reset, "Reset to Built-in", "Puts back the element's built-in name and description (saved with Save).");
        }
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Save", true);
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == IDialogConstants.CLIENT_ID) {
            title.setText(resetTitle);
            desc.setText(resetDesc);
            return;
        }
        super.buttonPressed(id);
    }

    @Override
    protected void okPressed() {
        result = new String[] { title.getText(), desc.getText() };
        super.okPressed();
    }
}
