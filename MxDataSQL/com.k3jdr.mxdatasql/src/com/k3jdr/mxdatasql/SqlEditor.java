package com.k3jdr.mxdatasql;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.k3jdr.mx.core.ui.Ui;

/**
 * The SQL box (SQLTextView.swift): plain text in a fixed-width font, coloured as you type (SqlHighlighter), with
 * Undo / Redo (Ctrl+Z / Ctrl+Y) and a right-click menu whose own items come first, then Cut / Copy / Paste.
 */
public final class SqlEditor {
    private final StyledText text;
    private final Deque<Change> undo = new ArrayDeque<>();
    private final Deque<Change> redo = new ArrayDeque<>();
    private boolean replaying;
    private boolean settingText;

    private record Change(int start, String removed, String inserted) {}

    /** {@code menuItems} adds the editor's own items to the top of its right-click menu. */
    public SqlEditor(Composite parent, int style, Consumer<Menu> menuItems, Consumer<String> changed) {
        text = new StyledText(parent, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER | style);
        text.setFont(Ui.mono());
        text.setLeftMargin(4);
        text.setTopMargin(4);
        text.setTabs(4);
        text.addExtendedModifyListener(e -> {
            if (!replaying && !settingText) {
                String inserted = text.getTextRange(e.start, e.length);
                undo.push(new Change(e.start, e.replacedText, inserted));
                if (undo.size() > 500) undo.removeLast();
                redo.clear();
            }
            SqlHighlighter.apply(text);
            if (!settingText && changed != null) changed.accept(text.getText());
        });
        text.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0 && (e.stateMask & SWT.ALT) == 0) {
                if (e.keyCode == 'z' && (e.stateMask & SWT.SHIFT) == 0) {
                    undo();
                    e.doit = false;
                } else if (e.keyCode == 'y' || (e.keyCode == 'z' && (e.stateMask & SWT.SHIFT) != 0)) {
                    redo();
                    e.doit = false;
                }
            }
        });
        Menu menu = new Menu(text);
        text.setMenu(menu);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem mi : menu.getItems()) mi.dispose();
            if (menuItems != null) menuItems.accept(menu);
            item(menu, "Undo\tCtrl+Z", !undo.isEmpty() && text.getEditable(), this::undo);
            item(menu, "Redo\tCtrl+Y", !redo.isEmpty() && text.getEditable(), this::redo);
            new MenuItem(menu, SWT.SEPARATOR);
            boolean selected = text.getSelectionCount() > 0;
            item(menu, "Cut\tCtrl+X", selected && text.getEditable(), text::cut);
            item(menu, "Copy\tCtrl+C", selected, text::copy);
            item(menu, "Paste\tCtrl+V", text.getEditable(), text::paste);
            new MenuItem(menu, SWT.SEPARATOR);
            item(menu, "Select All\tCtrl+A", text.getCharCount() > 0, text::selectAll);
        });
    }

    static MenuItem item(Menu menu, String label, boolean enabled, Runnable action) {
        MenuItem mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(label);
        mi.setEnabled(enabled);
        mi.addListener(SWT.Selection, e -> action.run());
        return mi;
    }

    public StyledText control() { return text; }

    public String getText() { return text.getText(); }

    /** Replaces the text from code (opening a table or a file); keeps undo for typing only. */
    public void setText(String value) {
        String v = value == null ? "" : value;
        if (v.equals(text.getText())) return;
        settingText = true;
        try {
            text.setText(v);
            undo.clear();
            redo.clear();
        } finally {
            settingText = false;
        }
        SqlHighlighter.apply(text);
    }

    public void undo() { replay(undo, redo, true); }

    public void redo() { replay(redo, undo, false); }

    private void replay(Deque<Change> from, Deque<Change> to, boolean backwards) {
        if (from.isEmpty()) return;
        Change c = from.pop();
        replaying = true;
        try {
            if (backwards) {
                text.replaceTextRange(c.start(), c.inserted().length(), c.removed());
                text.setCaretOffset(c.start() + c.removed().length());
            } else {
                text.replaceTextRange(c.start(), c.removed().length(), c.inserted());
                text.setCaretOffset(c.start() + c.inserted().length());
            }
        } finally {
            replaying = false;
        }
        to.push(c);
        text.showSelection();
    }
}
