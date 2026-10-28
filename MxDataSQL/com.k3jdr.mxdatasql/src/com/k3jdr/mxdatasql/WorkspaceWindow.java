package com.k3jdr.mxdatasql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.json.Json;
import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.dbml.DiagramWindow;

/**
 * A workspace window (ContentView.swift and the document around it): the Database drop-down and the three forms,
 * Quick Help, the status bar, and the workspace document — New, Open, Save, Save As, Duplicate, Revert, Open
 * Recent, the edited state in the title and the question to save on close.
 */
public final class WorkspaceWindow implements Windows.HelpWindow {
    private static final Prefs PREFS = Prefs.of("com.k3jdr.mxdatasql");
    private static final String RECENT = "recentWorkspaces";
    static final List<WorkspaceWindow> OPEN = new ArrayList<>();

    private final Shell shell;
    private final boolean main;
    private final Consumer<String> titleSink;
    Workspace doc = new Workspace();
    private Workspace saved = doc.copy();
    private Path file;

    final Session session;
    private final Combo database;
    private final Label databaseLabel;
    private final Button[] formButtons = new Button[3];
    private final StackLayout stack = new StackLayout();
    private final Composite forms;
    private final ConnectionForm connectionForm;
    private final TableSelectionForm selectionForm;
    private final TableForm tableForm;
    private final StatusBar status;
    private String shownTitle;

    /** Builds the workspace's content in {@code parent} (GridLayout) of {@code shell}; the main window is the e4 one. */
    public WorkspaceWindow(Shell shell, Composite parent, boolean main, Consumer<String> titleSink) {
        this.shell = shell;
        this.main = main;
        this.titleSink = titleSink;
        session = new Session(parent);
        parent.setLayout(Ui.grid(1, 0, 0));

        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(Ui.grid(6, 10, 6));
        bar.setLayoutData(Ui.fillH());
        databaseLabel = Ui.label(bar, "Database:");
        database = new Combo(bar, SWT.READ_ONLY);
        GridData dd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        dd.widthHint = 170;
        database.setLayoutData(dd);
        database.addListener(SWT.Selection, e -> switchDatabase(database.getText()));
        Label gap = new Label(bar, SWT.NONE);
        gap.setLayoutData(new GridData(8, SWT.DEFAULT));
        for (Session.Form f : Session.Form.values()) {
            Button b = new Button(bar, SWT.TOGGLE);
            b.setText(f.label);
            b.addListener(SWT.Selection, e -> showForm(f));
            QuickHelp.help(b, "Forms", "DB Connection sets up the server, DB Table Selection lists its tables, DB Table shows a table's rows under an SQL box.");
            formButtons[f.ordinal()] = b;
        }
        new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());

        forms = new Composite(parent, SWT.NONE);
        forms.setLayout(stack);
        forms.setLayoutData(Ui.fill());
        connectionForm = new ConnectionForm(forms, session, () -> doc.connection, this::documentChanged, this::connect);
        selectionForm = new TableSelectionForm(forms, session, this::openTable);
        tableForm = new TableForm(forms, session, new TableForm.ViewHost() {
            @Override public Workspace.ViewSettings view() { return doc.view; }
            @Override public void viewChanged() { documentChanged(); }
            @Override public void widthsChanged(String table, Map<String, Double> widths) { setWidths(table, widths); }
        });
        QuickHelp.get().createDockedPanel(parent);     // every workspace window docks its own, as on the Mac
        status = new StatusBar(parent, session, () -> doc.connection);
        shell.setMinimumSize(780, 660);

        buildMenus();
        session.addListener(this::refresh);
        if (main) {
            Demo.apply(session);
            Demo.shots(this, tableForm);
        }
        OPEN.add(this);
        Windows.register(this);
        parent.addListener(SWT.Dispose, e -> {
            OPEN.remove(this);
            session.dispose();
        });
        refresh();
    }

    /** A workspace in a window of its own (File ▸ New, Open… while this one is in use). */
    static WorkspaceWindow openWindow(Display display) {
        Shell s = new Shell(display, SWT.SHELL_TRIM);
        s.setImages(display.getShells().length > 0 && display.getShells()[0].getImages() != null ? display.getShells()[0].getImages() : new org.eclipse.swt.graphics.Image[0]);
        WorkspaceWindow w = new WorkspaceWindow(s, s, false, s::setText);
        s.addListener(SWT.Close, e -> {
            if (!w.confirmClose()) e.doit = false;
        });
        Rectangle area = display.getPrimaryMonitor().getClientArea();
        int n = OPEN.size();
        s.setBounds(area.x + 60 + 30 * n, area.y + 40 + 30 * n, Math.min(1360, area.width - 120), Math.min(860, area.height - 80));
        s.open();
        return w;
    }

    @Override public Shell shell() { return shell; }

    // MARK: Refresh

    private void refresh() {
        if (shell.isDisposed()) return;
        boolean showDb = session.isConnected() || !session.databases.isEmpty();
        Ui.setVisible(databaseLabel, showDb);
        Ui.setVisible(database, showDb);
        if (showDb) {
            String[] items = session.databases.toArray(String[]::new);
            if (!java.util.Arrays.equals(items, database.getItems())) database.setItems(items);
            String current = session.connectedDatabase != null ? session.connectedDatabase : doc.connection.database;
            int i = session.databases.indexOf(current);
            if (i >= 0 && database.getSelectionIndex() != i) database.select(i);
            database.setEnabled(session.isConnected() && !session.isBusy() && !session.runningSQL);
            database.setToolTipText("Database on " + doc.connection.host + "; choosing another reconnects with the same user and password");
            QuickHelp.help(database, "Database", "The database on " + doc.connection.host
                    + " this workspace works in. Choosing another reconnects with the same user and password and refreshes the table list.");
        }
        for (Session.Form f : Session.Form.values()) formButtons[f.ordinal()].setSelection(session.form == f);
        Control top = switch (session.form) {
            case CONNECTION -> connectionForm.control();
            case TABLE_SELECTION -> selectionForm.control();
            case TABLE -> tableForm.control();
        };
        if (stack.topControl != top) {
            stack.topControl = top;
            forms.layout();
        }
        switch (session.form) {
            case CONNECTION -> {
                connectionForm.refresh();
                shell.setDefaultButton(connectionForm.defaultButton());
            }
            case TABLE_SELECTION -> {
                selectionForm.refresh();
                shell.setDefaultButton(selectionForm.defaultButton());
            }
            case TABLE -> {
                tableForm.refresh();
                shell.setDefaultButton(null);
            }
        }
        status.refresh();
        updateTitle();
        databaseLabel.getParent().layout(true);
    }

    private void showForm(Session.Form f) {
        session.form = f;
        if (f == Session.Form.TABLE_SELECTION && session.highlightedDescription == null && session.highlightedRoutine == null) {
            session.describeHighlighted();
        }
        session.changed();
    }

    // MARK: Document

    public boolean isDirty() { return !doc.equals(saved); }

    private boolean isPristine() { return file == null && !isDirty() && !session.isConnected(); }

    String name() {
        if (file == null) return "Untitled";
        String n = file.getFileName().toString();
        return n.endsWith("." + Workspace.EXTENSION) ? n.substring(0, n.length() - Workspace.EXTENSION.length() - 1) : n;
    }

    void documentChanged() { updateTitle(); }

    private void updateTitle() {
        String t = name() + (isDirty() ? " — Edited" : "") + " — MxDataSQL";
        if (!t.equals(shownTitle)) {
            shownTitle = t;
            titleSink.accept(t);
        }
    }

    /** Column widths set in this window's grid or a table window opened from it. */
    void setWidths(String table, Map<String, Double> widths) {
        if (widths == null || widths.isEmpty()) doc.view.columnWidths.remove(table);
        else doc.view.columnWidths.put(table, new TreeMap<>(widths));
        documentChanged();
    }

    private void load(Path path) throws IOException {
        Workspace w = Workspace.read(Files.readString(path, StandardCharsets.UTF_8));
        doc = w;
        saved = w.copy();
        file = path;
        session.password = "";
        session.loadSavedPassword(doc.connection);
        pushRecent(path);
        session.changed();
    }

    /** Opens a workspace file: in its window if it is open, in this window when it is unused, else in a new one. */
    static void openPath(Display display, Path path, WorkspaceWindow preferred) {
        Path abs = path.toAbsolutePath().normalize();
        for (WorkspaceWindow w : OPEN) {
            if (w.file != null && w.file.toAbsolutePath().normalize().equals(abs)) {
                w.shell.forceActive();
                return;
            }
        }
        WorkspaceWindow target = preferred != null && preferred.isPristine() ? preferred : null;
        if (target == null) for (WorkspaceWindow w : OPEN) if (w.isPristine()) target = w;
        if (target == null) target = openWindow(display);
        try {
            target.load(abs);
            target.shell.forceActive();
        } catch (IOException | RuntimeException e) {
            ErrorDialog.show(target.shell, "Open Workspace", "Could not open " + abs.getFileName() + ".\n\n" + ErrorDialog.describe(e));
        }
    }

    private void openDialog() {
        FileDialog d = new FileDialog(shell, SWT.OPEN);
        d.setText("Open Workspace");
        d.setFilterExtensions(new String[] { "*." + Workspace.EXTENSION, "*.*" });
        d.setFilterNames(new String[] { "MxDataSQL workspaces (*." + Workspace.EXTENSION + ")", "All files (*.*)" });
        String f = d.open();
        if (f != null) openPath(shell.getDisplay(), Path.of(f), this);
    }

    public boolean save() {
        return file == null ? saveAs() : write(file);
    }

    public boolean saveAs() {
        FileDialog d = new FileDialog(shell, SWT.SAVE);
        d.setText("Save Workspace");
        d.setFilterExtensions(new String[] { "*." + Workspace.EXTENSION });
        d.setFilterNames(new String[] { "MxDataSQL workspaces (*." + Workspace.EXTENSION + ")" });
        d.setFileName(name() + "." + Workspace.EXTENSION);
        d.setOverwrite(true);
        String f = d.open();
        if (f == null) return false;
        Path p = Path.of(f);
        if (!p.getFileName().toString().contains(".")) p = Path.of(f + "." + Workspace.EXTENSION);
        return write(p);
    }

    private boolean write(Path path) {
        try {
            Json.writeText(path, doc.write());
            file = path;
            saved = doc.copy();
            pushRecent(path);
            updateTitle();
            return true;
        } catch (IOException | RuntimeException e) {
            ErrorDialog.show(shell, "Save Workspace", "Could not save " + path.getFileName() + ".\n\n" + ErrorDialog.describe(e));
            return false;
        }
    }

    private void duplicate() {
        WorkspaceWindow w = openWindow(shell.getDisplay());
        w.doc = doc.copy();
        w.saved = new Workspace();
        w.session.password = session.password;
        w.session.changed();
        w.connectionForm.refresh();
    }

    private void revert() {
        if (file == null) return;
        try {
            Workspace w = Workspace.read(Files.readString(file, StandardCharsets.UTF_8));
            doc = w;
            saved = w.copy();
            session.changed();
            connectionForm.refresh();
        } catch (IOException | RuntimeException e) {
            ErrorDialog.show(shell, "Revert", "Could not read " + file.getFileName() + ".\n\n" + ErrorDialog.describe(e));
        }
    }

    private static void pushRecent(Path p) {
        PREFS.pushRecent(RECENT, p.toAbsolutePath().toString(), 10);
    }

    /** Asks to save unsaved changes; false when the user cancels. */
    public boolean confirmClose() {
        if (!isDirty()) return true;
        shell.forceActive();
        MessageDialog d = new MessageDialog(shell, "MxDataSQL", null,
                "Do you want to save the changes you made to the workspace “" + name() + "”?\n\nYour changes will be lost if you don't save them.",
                MessageDialog.WARNING, 0, "Save", "Don't Save", "Cancel");
        return switch (d.open()) {
            case 0 -> save();
            case 1 -> true;
            default -> false;
        };
    }

    // MARK: Connection and tables

    private void connect() {
        session.connect(doc.connection, () -> {
            if (!session.isConnected()) return;
            session.form = Session.Form.TABLE_SELECTION;
            if (doc.selectedTable != null) {
                Catalog.TableSummary t = session.table(doc.selectedTable);
                if (t != null) session.highlightedID = t.id();
            }
            session.describeHighlighted();
        });
    }

    private void switchDatabase(String name) {
        if (name.isEmpty() || name.equals(session.connectedDatabase)) return;
        session.switchDatabase(name, doc.connection, ok -> {
            if (ok) {
                doc.connection.database = name;
                documentChanged();
                connectionForm.refresh();
            }
            refresh();
        });
    }

    private void openTable(TableRef ref) {
        doc.selectedTable = ref;
        documentChanged();
        if (session.openInNewWindow) TableWindow.open(this, ref);
        else session.openTable(ref, doc.view.fetchLimit, 0, true, null, null);
    }

    // MARK: Menus

    private void buildMenus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        mb.menu("&File")
                .item("&New Workspace", SWT.MOD1 | 'N', () -> openWindow(shell.getDisplay()))
                .item("&Open…", SWT.MOD1 | 'O', this::openDialog)
                .dynamic("Open &Recent", m -> {
                    for (String f : PREFS.getList(RECENT)) {
                        Path p = Path.of(f);
                        if (Files.exists(p)) m.item(Ui.escape(p.getFileName().toString()), () -> openPath(shell.getDisplay(), p, this));
                    }
                    if (!PREFS.getList(RECENT).isEmpty()) {
                        m.separator();
                        m.item("Clear Menu", () -> PREFS.remove(RECENT));
                    }
                })
                .separator()
                .item("&Close Window", SWT.MOD1 | 'W', shell::close)
                .item("&Save", SWT.MOD1 | 'S', this::save)
                .item("Save &As…", SWT.MOD1 | SWT.SHIFT | 'S', this::saveAs)
                .item("&Duplicate", 0, this::duplicate)
                .item("Re&vert to Saved", 0, this::revert, () -> file != null && isDirty())
                .separator()
                .item("E&xit", SWT.MOD3 | SWT.F4, () -> exitAll(shell));
        EditActions.addStandardItems(mb.menu("&Edit"), () -> {
            Control focus = shell.getDisplay().getFocusControl();
            return focus == tableForm.grid() ? tableForm.editTarget() : null;
        });
        MenuBarBuilder.MenuBuilder view = mb.menu("&View");
        for (Session.Form f : Session.Form.values()) {
            view.radio(f.label + "\tCtrl+" + (f.ordinal() + 1), () -> session.form == f, () -> showForm(f));
        }
        view.separator();
        QuickHelp.get().addMenuItems(view);
        mb.menu("&Table")
                .item("&Run SQL", SWT.MOD1 | SWT.CR, () -> {
                    if (session.form == Session.Form.TABLE) tableForm.runSQL();
                }, () -> session.isConnected() && session.form == Session.Form.TABLE && !session.runningSQL)
                .separator()
                .item("&View…", SWT.MOD1 | SWT.MOD3 | 'L', tableForm::showViewDialog)
                .item("&Update…", SWT.MOD1 | SWT.MOD3 | 'U', () -> {
                    session.form = Session.Form.TABLE;
                    session.changed();
                    tableForm.showUpdateDialog();
                }, tableForm::canUpdate)
                .separator()
                .item("&Export CSV…", SWT.MOD1 | SWT.SHIFT | 'E', tableForm::exportCSV, () -> session.grid != null);
        mb.menu("&Diagram")
                .item("&Write DBML…", SWT.MOD1 | SWT.MOD3 | 'D', () -> DbmlExport.begin(shell, session), session::isConnected)
                .item("&Open DBML…", SWT.MOD1 | SWT.MOD3 | 'O', () -> DbmlExport.open(shell));
        mb.install();
        org.eclipse.swt.widgets.Listener formKeys = e -> {
            if (shell.isDisposed() || shell.getDisplay().getActiveShell() != shell) return;
            if ((e.stateMask & SWT.MODIFIER_MASK) == SWT.MOD1 && e.keyCode >= '1' && e.keyCode <= '3') {
                showForm(Session.Form.values()[e.keyCode - '1']);
                e.doit = false;
            }
        };
        Display display = shell.getDisplay();
        display.addFilter(SWT.KeyDown, formKeys);
        shell.addListener(SWT.Dispose, e -> display.removeFilter(SWT.KeyDown, formKeys));
    }

    /** File ▸ Exit: every workspace asks to save first. */
    static void exitAll(Shell from) {
        for (WorkspaceWindow w : List.copyOf(OPEN)) {
            if (!w.main && !w.shell.isDisposed()) {
                if (!w.confirmClose()) return;
                w.saved = w.doc.copy();
                w.shell.dispose();
            }
        }
        for (WorkspaceWindow w : List.copyOf(OPEN)) if (w.main) w.shell.close();
    }

    /** The main window is closing (the app quits): every workspace window asks to save; false cancels. */
    static boolean confirmQuit() {
        for (WorkspaceWindow w : List.copyOf(OPEN)) {
            if (w.main || w.shell.isDisposed()) continue;
            if (!w.confirmClose()) return false;
        }
        for (WorkspaceWindow w : List.copyOf(OPEN)) {
            if (w.main && !w.confirmClose()) return false;
        }
        for (WorkspaceWindow w : List.copyOf(OPEN)) {
            if (!w.main && !w.shell.isDisposed()) {
                w.saved = w.doc.copy();
                w.shell.dispose();
            }
        }
        return true;
    }

    /** Opens .mxworkspace and .dbml files handed to the app (a double-click, or a second launch). */
    static void openArguments(Display display, List<String> args) {
        for (String a : args) {
            if (a == null || a.isBlank() || a.startsWith("-")) continue;
            Path p = Path.of(a.strip().replace("\"", ""));
            if (!Files.isRegularFile(p)) continue;
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith("." + Workspace.EXTENSION)) openPath(display, p, null);
            else if (n.endsWith(".dbml")) DiagramWindow.open(display, p);
        }
    }

    // MARK: Quick Help

    @Override
    public QuickHelpItem screen() { return Help.screen(session); }

    @Override
    public QuickHelpItem selection() { return Help.selection(session, tableForm.grid()); }
}
