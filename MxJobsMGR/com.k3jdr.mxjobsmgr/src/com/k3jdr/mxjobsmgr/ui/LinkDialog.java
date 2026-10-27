package com.k3jdr.mxjobsmgr.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxjobsmgr.model.Rtf;
import com.k3jdr.mxjobsmgr.model.Specs.StepLink;

/**
 * The Link dialog (Shared/Links LinkSheet in the Mac apps): where a link goes — a web page, an email address or a page
 * in MxNoteONE — the words shown for it, and its hover text. The hover text is kept as RTF, as the Mac apps keep it;
 * here it is edited as plain text (formatting the Mac app gave it is kept until the text is changed).
 */
public final class LinkDialog extends Dialog {
    private final String subject, detail, whereKept, words;
    private final StepLink initial;
    private Text address, text, tip, title;
    private StepLink result;

    private LinkDialog(Shell parent, String subject, String detail, StepLink initial, String words, String whereKept) {
        super(parent);
        this.subject = subject;
        this.detail = detail;
        this.initial = initial;
        this.words = words;
        this.whereKept = whereKept;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    /** Edits a link; the edited link (an empty address takes it off), or null when cancelled. */
    public static StepLink edit(Shell parent, String subject, String detail, StepLink initial, String words, String whereKept) {
        LinkDialog d = new LinkDialog(parent, subject, detail, initial, words, whereKept);
        return d.open() == OK ? d.result : null;
    }

    /** Opens a link: a web page, mail, or another app's link (mxnoteone://, mxdatahub://). */
    public static void follow(String address) {
        if (address == null || address.isBlank()) return;
        String a = address.trim();
        if (a.contains("@") && !a.contains(":")) a = "mailto:" + a;
        else if (!a.contains(":")) a = "https://" + a;
        Program.launch(a);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(initial == null ? "Add Link" : "Edit Link");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 14, 8));
        Label head = Ui.label(area, subject, Ui.subheading());
        head.setLayoutData(Ui.span(2));
        Label d = Ui.label(area, detail);
        d.setForeground(Ui.SECONDARY);
        d.setLayoutData(Ui.span(2));
        address = field(area, "Address:", initial != null ? initial.address : "", "https://…, name@example.com or mxnoteone://…",
                "Where the link goes: a web page, an email address, or a page in MxNoteONE (paste a link copied there).");
        text = field(area, "Words:", initial != null && initial.text != null ? initial.text : "", words,
                "The words shown for the link; empty for “" + words + "”.");
        title = field(area, "Title:", initial != null && initial.title != null ? initial.title : "", "optional",
                "The title of the page it opens (MxNoteONE links carry it).");
        Label tl = Ui.label(area, "Hover text:");
        tl.setLayoutData(new GridData(SWT.BEGINNING, SWT.TOP, false, false));
        tip = new Text(area, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        String plain = initial == null ? null : initial.tipPlain();
        tip.setText(plain == null ? "" : plain);
        GridData tg = new GridData(SWT.FILL, SWT.FILL, true, true);
        tg.widthHint = 380;
        tg.heightHint = 110;
        tip.setLayoutData(tg);
        QuickHelp.help(tip, "Hover text", "Shown when the pointer rests on the step or palette item.");
        Label kept = new Label(area, SWT.WRAP);
        kept.setText(Ui.escape(whereKept));
        kept.setForeground(Ui.SECONDARY);
        GridData kg = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        kg.widthHint = 420;
        kept.setLayoutData(kg);
        return area;
    }

    private Text field(Composite area, String label, String value, String prompt, String help) {
        Ui.label(area, label);
        Text t = new Text(area, SWT.BORDER);
        t.setText(value);
        t.setMessage(prompt);
        t.setLayoutData(Ui.fillH());
        QuickHelp.help(t, label.replace(":", ""), help);
        return t;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        if (initial != null) createButton(parent, IDialogConstants.CLIENT_ID, "Remove Link", false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Save", true);
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == IDialogConstants.CLIENT_ID) {
            result = new StepLink();
            result.address = "";
            setReturnCode(OK);
            close();
            return;
        }
        super.buttonPressed(id);
    }

    @Override
    protected void okPressed() {
        StepLink l = new StepLink();
        l.address = address.getText().trim();
        l.text = text.getText().isBlank() ? null : text.getText().trim();
        l.title = title.getText().isBlank() ? null : title.getText().trim();
        String plain = initial == null ? null : initial.tipPlain();
        String now = tip.getText().strip();
        l.tip = now.equals(plain == null ? "" : plain) && initial != null ? initial.tip : Rtf.fromPlain(now);
        result = l;
        super.okPressed();
    }
}
