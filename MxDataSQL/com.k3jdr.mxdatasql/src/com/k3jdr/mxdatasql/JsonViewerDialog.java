package com.k3jdr.mxdatasql;

import java.util.function.Consumer;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;

/**
 * View JSON… (JSONViewer.swift): the cell's JSON formatted like VS Code's Format Document and coloured; the text can
 * be edited (re-coloured as you type), re-formatted with 2 or 4 spaces, copied, and — on editable grids — applied
 * to the cell as a pending edit.
 */
public final class JsonViewerDialog extends Dialog {
    private static final Prefs PREFS = Prefs.of("com.k3jdr.mxdatasql");
    private static final int FORMAT = IDialogConstants.CLIENT_ID + 1;
    private static final int COPY = IDialogConstants.CLIENT_ID + 2;
    private static final int APPLY = IDialogConstants.CLIENT_ID + 3;

    private final String title, subtitle, original;
    private final Consumer<String> apply;
    private StyledText text;
    private Label problem;
    private Combo indent;

    public JsonViewerDialog(Shell parent, String title, String subtitle, String json, Consumer<String> apply) {
        super(parent);
        this.title = title;
        this.subtitle = subtitle;
        this.original = json;
        this.apply = apply;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("View JSON — " + title);
        shell.setMinimumSize(520, 380);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 16, 6));
        Ui.label(area, title, Ui.bold());
        Label sub = Ui.label(area, subtitle, Ui.small());
        sub.setForeground(Ui.SECONDARY);
        text = new StyledText(area, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        text.setFont(Ui.mono());
        text.setLeftMargin(6);
        text.setTopMargin(8);
        text.setLayoutData(Ui.fill());
        text.addListener(SWT.Modify, e -> colour());
        QuickHelp.help(text, "JSON", "The cell's value formatted like VS Code's Format Document and coloured. Edit it here; Format re-indents it"
                + (apply != null ? ", Apply to Cell makes it the cell's pending edit." : "."));
        problem = new Label(area, SWT.WRAP);
        problem.setForeground(Ui.RED);
        problem.setLayoutData(Ui.fillH());
        Composite row = new Composite(area, SWT.NONE);
        row.setLayout(Ui.grid(2, 0, 6));
        Ui.label(row, "Indent:");
        indent = new Combo(row, SWT.READ_ONLY);
        indent.setItems("2 spaces", "4 spaces");
        indent.select(PREFS.getInt("jsonIndentSpaces", 4) == 2 ? 0 : 1);
        indent.addListener(SWT.Selection, e -> {
            PREFS.putInt("jsonIndentSpaces", indent.getSelectionIndex() == 0 ? 2 : 4);
            format(text.getText());
        });
        QuickHelp.help(indent, "Indent", "How many spaces Format indents each level by (remembered).");
        format(original);
        return area;
    }

    private void colour() {
        text.setStyleRanges(JsonFormatter.styles(text.getText()).toArray(StyleRange[]::new));
    }

    private String indentString() { return indent.getSelectionIndex() == 0 ? "  " : "    "; }

    private void format(String source) {
        try {
            text.setText(JsonFormatter.format(source, indentString()));
            problem.setText("");
        } catch (JsonFormatter.FormatError e) {
            text.setText(source);
            problem.setText("⚠ " + e.getMessage());
        }
        colour();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        QuickHelp.help(createButton(parent, FORMAT, "Format", false), "Format", "Re-indents the JSON like VS Code's Format Document; invalid JSON is reported with its line and column.");
        QuickHelp.help(createButton(parent, COPY, "Copy", false), "Copy", "Copies the JSON text.");
        if (apply != null) {
            QuickHelp.help(createButton(parent, APPLY, "Apply to Cell", false), "Apply to Cell",
                    "Makes this text the cell's pending edit; Update… writes it to the table.");
        }
        createButton(parent, IDialogConstants.CANCEL_ID, "Close", true);
    }

    @Override
    protected void buttonPressed(int id) {
        switch (id) {
            case FORMAT -> format(text.getText());
            case COPY -> Clipboards.copyText(getShell().getDisplay(), text.getText());
            case APPLY -> {
                try {
                    JsonFormatter.format(text.getText(), "    ");
                    apply.accept(text.getText().strip());
                    close();
                } catch (JsonFormatter.FormatError e) {
                    problem.setText("⚠ " + e.getMessage());
                }
            }
            default -> super.buttonPressed(id);
        }
    }

    @Override
    protected Point getInitialSize() {
        return new Point(680, 520);
    }

    @Override
    protected boolean isResizable() { return true; }

    static GridData unused() { return null; }
}
