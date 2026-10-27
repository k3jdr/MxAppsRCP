package com.k3jdr.mx.dbml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.json.Json;
import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;

/**
 * A Diagram window (MxDataSQL's DiagramWindow.swift, DiagramModel.swift, DiagramCanvas.swift and the diagram part of
 * DiagramCommands.swift): the canvas, a toolbar (relationship curve, add note, arrange, zoom, save), a status line,
 * and the Diagram menu. Save writes the notes and layout into the .dbml file (the rest of the file is kept as is);
 * closing it with unsaved changes asks whether to save them.
 */
public final class DiagramWindow implements DbmlCanvasHost {
    private static final String[] DBML_FILTER = { "*.dbml", "*.*" };
    private static final String[] DBML_FILTER_NAMES = { "DBML files (*.dbml)", "All files (*.*)" };

    private final Shell shell;
    private final DbmlCanvas canvas;
    private Path file;
    private boolean dirty;
    private String infoMessage;
    private Combo curve;
    private Button zoomButton, saveButton;
    private Label status, statusRight;

    // MARK: Opening

    /**
     * Opens the .dbml file in a new diagram window; shows an error (and returns null) if it can't be read or has no
     * tables.
     */
    public static DiagramWindow open(Display display, Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            showError(display, "Could not open " + file.getFileName() + ".\n\n" + ErrorDialog.describe(ex));
            return null;
        }
        return open(display, text, file);
    }

    /** Same, with the text already in memory (just written by Write DBML). */
    public static DiagramWindow open(Display display, String fileText, Path file) {
        DbmlDiagram diagram = DbmlDiagram.fromFileText(fileText);
        DbmlSchema schema = diagram.schema();
        if (schema.tables().isEmpty()) {
            String name = file != null ? file.getFileName().toString() : "Untitled.dbml";
            DbmlSchema.Problem first = schema.problems().isEmpty() ? null : schema.problems().get(0);
            showError(display, name + " has no tables." + (first != null ? "\n\nLine " + first.line() + ": " + first.message() : ""));
            return null;
        }
        DiagramWindow w = new DiagramWindow(display, diagram, file);
        w.shell.open();
        w.canvas.setFocus();
        return w;
    }

    private static void showError(Display display, String message) {
        Shell parent = display.getActiveShell();
        if (parent == null) {
            for (Shell s : display.getShells()) {
                if (!s.isDisposed() && s.isVisible()) {
                    parent = s;
                    break;
                }
            }
        }
        boolean temporary = parent == null;
        if (temporary) parent = new Shell(display);
        try {
            ErrorDialog.show(parent, "Open DBML", message);
        } finally {
            if (temporary) parent.dispose();
        }
    }

    public Shell shell() { return shell; }

    public DbmlCanvas canvas() { return canvas; }

    /** The .dbml file the diagram is saved into (null until Save As). */
    public Path file() { return file; }

    public boolean isDirty() { return dirty; }

    // MARK: The window

    private DiagramWindow(Display display, DbmlDiagram diagram, Path file) {
        this.file = file;
        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setLayout(Ui.grid(1, 0, 0));
        Image[] images = null;
        for (Shell s : display.getShells()) {
            if (s != shell && !s.isDisposed() && s.getImages().length > 0) {
                images = s.getImages();
                break;
            }
        }
        if (images != null) shell.setImages(images);

        Composite toolbar = new Composite(shell, SWT.NONE);
        toolbar.setLayoutData(Ui.fillH());
        canvas = new DbmlCanvas(shell, SWT.H_SCROLL | SWT.V_SCROLL, diagram);
        canvas.setZoom(diagram.zoom != null ? diagram.zoom : 1);
        canvas.setHost(this);
        buildToolbar(toolbar);
        canvas.setLayoutData(Ui.fill());
        new Label(shell, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(Ui.fillH());
        Composite statusLine = new Composite(shell, SWT.NONE);
        statusLine.setLayoutData(Ui.fillH());
        statusLine.setLayout(Ui.grid(2, 0, 10));
        ((org.eclipse.swt.layout.GridLayout) statusLine.getLayout()).marginWidth = 12;
        ((org.eclipse.swt.layout.GridLayout) statusLine.getLayout()).marginHeight = 5;
        status = new Label(statusLine, SWT.NONE);
        status.setLayoutData(Ui.fillH());
        statusRight = new Label(statusLine, SWT.NONE);
        statusRight.setForeground(Ui.SECONDARY);
        statusRight.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        QuickHelp.help(statusLine, "Status", "How many tables, relationships and notes the diagram has, what's selected, and whether there are unsaved changes.");

        buildMenus();
        shell.addListener(SWT.Close, e -> e.doit = canClose());
        shell.setMinimumSize(760, 480);
        shell.setSize(1180, 800);
        Shell active = display.getActiveShell();
        Rectangle area = active != null && active != shell ? active.getBounds() : display.getPrimaryMonitor().getClientArea();
        shell.setLocation(area.x + Math.max(0, (area.width - 1180) / 2), area.y + Math.max(0, (area.height - 800) / 2));
        update();
    }

    private void buildToolbar(Composite bar) {
        bar.setLayout(Ui.grid(9, 6, 4));
        ((org.eclipse.swt.layout.GridLayout) bar.getLayout()).marginWidth = 10;
        Label curveLabel = Ui.label(bar, "Curve:");
        curve = new Combo(bar, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (DbmlCurveStyle s : DbmlCurveStyle.values()) curve.add(s.title());
        curve.select(0);
        curve.addListener(SWT.Selection, e -> {
            DbmlSchema.Ref ref = selectedRef();
            int k = curve.getSelectionIndex();
            if (ref != null && k >= 0) canvas.change("Line Style", d -> d.setStyle(DbmlCurveStyle.values()[k], ref.id()));
        });
        QuickHelp.help(curveLabel, "Curve", "How the selected relationship is drawn: Automatic, Straight Line, Quadratic Bézier (one control point) or Cubic Bézier (two); Bézier curves show control points to drag. Select a relationship to change its curve.");
        Label filler = new Label(bar, SWT.NONE);
        filler.setLayoutData(Ui.fillH());
        Button addNote = Ui.button(bar, "Add Note", this::addNoteAtVisibleCenter);
        QuickHelp.help(addNote, "Add Note", "Add a sticky note (or double-click empty canvas).");
        addNote.setToolTipText("Add a sticky note (or double-click empty canvas)");
        Button arrange = Ui.button(bar, "Arrange", this::autoArrange);
        QuickHelp.help(arrange, "Arrange", "Arrange the tables by schema, related tables together (resets curves). Right-click a schema to select, copy or export it.");
        arrange.setToolTipText("Arrange the tables by schema, related tables together (resets curves)");
        Button zoomOut = Ui.button(bar, " − ", () -> setZoom(canvas.getZoom() / 1.25));
        QuickHelp.help(zoomOut, "Zoom Out", "Show the diagram smaller.", "Ctrl+-");
        zoomOut.setToolTipText("Zoom Out (Ctrl+-)");
        zoomButton = Ui.button(bar, "100%", () -> setZoom(1));
        GridData zg = new GridData();
        zg.widthHint = 58;
        zoomButton.setLayoutData(zg);
        QuickHelp.help(zoomButton, "Zoom", "The scale the diagram is shown at. Click for 100%.", "Ctrl+0");
        zoomButton.setToolTipText("Click for 100%");
        Button zoomIn = Ui.button(bar, " + ", () -> setZoom(canvas.getZoom() * 1.25));
        QuickHelp.help(zoomIn, "Zoom In", "Show the diagram larger.", "Ctrl+=");
        zoomIn.setToolTipText("Zoom In (Ctrl+=)");
        saveButton = Ui.button(bar, "Save", this::save);
        QuickHelp.help(saveButton, "Save", "Save the notes and layout into the .dbml file (the rest of the file is kept as is).", "Ctrl+S");
        QuickHelp.help(curve, "Curve", "How the selected relationship is drawn; Bézier curves show control points to drag. Select a relationship to change its curve.");
    }

    private void buildMenus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        MenuBarBuilder.MenuBuilder diagram = mb.menu("&Diagram");
        diagram.item("&Save Diagram", SWT.MOD1 | 'S', this::save)
                .item("Save Diagram &As…", 0, this::saveAs)
                .separator()
                .item("Add &Note", 0, this::addNoteAtVisibleCenter)
                .item("A&rrange Tables", 0, this::autoArrange)
                .dynamic("&Export Schema", m -> {
                    for (String name : canvas.getSchema().schemas()) m.item(name.replace("&", "&&"), () -> exportSchema(name));
                })
                .separator()
                .item("Zoom &In", SWT.MOD1 | '=', () -> setZoom(canvas.getZoom() * 1.25))
                .item("Zoom &Out", SWT.MOD1 | '-', () -> setZoom(canvas.getZoom() / 1.25))
                .item("&Actual Size", SWT.MOD1 | '0', () -> setZoom(1))
                .separator()
                .item("&Close", SWT.MOD1 | 'W', shell::close);
        MenuBarBuilder.MenuBuilder edit = mb.menu("&Edit");
        EditActions.addStandardItems(edit, () -> new EditActions.Target() {
            @Override public boolean canSelectAll() { return true; }
            @Override public void selectAll() { canvas.selectAllTables(); }
            @Override public boolean canDelete() { return canvas.canDeleteSelection(); }
            @Override public void delete() { canvas.deleteSelection(); }
        });
        try {
            QuickHelp.get().addMenuItems(mb.menu("&View"));
        } catch (RuntimeException | LinkageError ex) {
            // Without the platform's preferences (a test outside the app) there is no Quick Help menu.
            Menu bar = mb.bar();
            MenuItem last = bar.getItem(bar.getItemCount() - 1);
            if ("&View".equals(last.getText())) {
                if (last.getMenu() != null) last.getMenu().dispose();
                last.dispose();
            }
        }
        mb.install();
    }

    // MARK: State shown

    private String title() {
        return file != null ? file.getFileName().toString() : "Untitled.dbml";
    }

    private String baseName() {
        if (file == null) return null;
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private DbmlSchema.Ref selectedRef() {
        String id = canvas.getSelection().ref();
        return id == null ? null : canvas.getSchema().ref(id);
    }

    private void update() {
        if (shell.isDisposed()) return;
        shell.setText(title() + (dirty ? " — Edited" : ""));
        DbmlSchema s = canvas.getSchema();
        DbmlDiagram d = canvas.getDiagram();
        String counts = s.tables().size() + " tables · " + s.refs().size() + " relationships · " + (d.notes.size() + s.notes().size()) + " notes";
        String sel = selectionText(s, d);
        status.setText(Ui.escape(counts + (sel != null ? " — " + sel : "")));
        statusRight.setText(Ui.escape(dirty ? "Edited" : infoMessage != null ? infoMessage : ""));
        status.getParent().layout(true);
        DbmlSchema.Ref ref = selectedRef();
        curve.setEnabled(ref != null);
        curve.select((ref != null ? d.style(ref) : DbmlCurveStyle.AUTOMATIC).ordinal());
        curve.setToolTipText(ref == null ? "Select a relationship to change its curve"
                : "How the selected relationship is drawn; Bézier curves show control points to drag");
        zoomButton.setText(Math.round(canvas.getZoom() * 100) + "%");
        saveButton.setToolTipText("Save the notes and layout into " + title() + " (Ctrl+S)");
    }

    private String selectionText(DbmlSchema s, DbmlDiagram d) {
        DbmlSelection sel = canvas.getSelection();
        if (sel.tables().size() == 1) {
            String id = sel.tables().iterator().next();
            long n = s.refs().stream().filter(r -> r.from().table().equals(id) || r.to().table().equals(id)).count();
            DbmlSchema.Table t = s.table(id);
            return id + ": " + (t != null ? t.columns().size() : 0) + " columns, " + n + " relationship" + (n == 1 ? "" : "s");
        }
        if (sel.tables().size() > 1) return sel.tables().size() + " tables selected (drag one to move them all)";
        DbmlSchema.Ref r = selectedRef();
        if (r != null) return r.text() + " (" + d.style(r).title() + ")";
        if (sel.note() != null) return "note " + sel.note();
        return "Drag tables and notes; click a relationship to reshape it; double-click empty space for a note; right-click a schema to select, copy or export it";
    }

    private void changed() {
        dirty = true;
        update();
    }

    // MARK: Commands

    private void setZoom(double z) {
        canvas.setZoom(Math.max(DbmlCanvas.MIN_ZOOM, Math.min(DbmlCanvas.MAX_ZOOM, z)));
        update();
    }

    /** Add Note: a note in the middle of what's shown, to type in. */
    public void addNoteAtVisibleCenter() {
        DbmlRect v = canvas.visibleArea();
        DbmlPoint center = v.width() <= 0 || v.height() <= 0 ? new DbmlPoint(200, 200) : new DbmlPoint(v.midX() - 110, v.midY() - 60);
        canvas.addNote(center, "Note");
    }

    /** The tables arranged by schema, related tables together (resets curves). */
    public void autoArrange() {
        canvas.change("Arrange Tables", DbmlDiagram::arrange);
    }

    /** Writes the diagram (notes and layout) back into the file; the rest of the file is kept as is. */
    public boolean save() {
        if (file == null) return saveAs();
        canvas.endNoteEditing(true);
        DbmlDiagram d = canvas.getDiagram();
        d.zoom = canvas.getZoom();
        try {
            Json.writeText(file, d.fileText());
            canvas.setDiagram(d);
            dirty = false;
            infoMessage = "Saved " + title();
            update();
            return true;
        } catch (IOException | RuntimeException ex) {
            ErrorDialog.show(shell, "Diagram", "Could not save " + title() + ": " + ErrorDialog.describe(ex));
            return false;
        }
    }

    public boolean saveAs() {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Save Diagram As");
        fd.setFilterExtensions(DBML_FILTER);
        fd.setFilterNames(DBML_FILTER_NAMES);
        fd.setFileName(file != null ? file.getFileName().toString() : "diagram.dbml");
        if (file != null && file.toAbsolutePath().getParent() != null) fd.setFilterPath(file.toAbsolutePath().getParent().toString());
        fd.setOverwrite(true);
        String chosen = fd.open();
        if (chosen == null) return false;
        file = Path.of(chosen);
        return save();
    }

    /** A schema to a .dbml file of its own (its tables and enums, relationships, table group, layout and notes). */
    public void exportSchema(String name) {
        exportSchema(canvas, canvas.getDiagram().subset(name, baseName()), name);
    }

    @Override
    public void exportSchema(DbmlCanvas c, DbmlDiagram part, String name) {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Export Schema");
        fd.setFilterExtensions(DBML_FILTER);
        fd.setFilterNames(DBML_FILTER_NAMES);
        String base = baseName();
        fd.setFileName((base != null ? base + "-" : "") + name + ".dbml");
        if (file != null && file.toAbsolutePath().getParent() != null) fd.setFilterPath(file.toAbsolutePath().getParent().toString());
        fd.setOverwrite(true);
        String chosen = fd.open();
        if (chosen == null) return;
        Path out = Path.of(chosen);
        part.zoom = canvas.getZoom();
        try {
            Json.writeText(out, part.fileText());
            int tables = part.schema().tables().size();
            infoMessage = "Exported " + name + " to " + out.getFileName() + ": " + tables + " table" + (tables == 1 ? "" : "s");
            update();
        } catch (IOException | RuntimeException ex) {
            ErrorDialog.show(shell, "Diagram", "Could not export " + name + ": " + ErrorDialog.describe(ex));
        }
    }

    /** Asks about unsaved changes before closing: Save, Don't Save or Cancel. */
    private boolean canClose() {
        canvas.endNoteEditing(true);
        if (!dirty) return true;
        MessageDialog dialog = new MessageDialog(shell, "Diagram", null,
                "Save the diagram changes to " + title() + "?\n\nTable positions, curves and notes are saved into the .dbml file.",
                MessageDialog.QUESTION, 0, "Save", "Don't Save", "Cancel");
        return switch (dialog.open()) {
            case 0 -> save();
            case 1 -> true;
            default -> false;
        };
    }

    // MARK: The canvas's host

    @Override
    public void didChange(DbmlCanvas c, DbmlDiagram old, String name) {
        changed();
    }

    @Override
    public void selectionDidChange(DbmlCanvas c) {
        update();
    }

    @Override
    public void zoomDidChange(DbmlCanvas c) {
        update();
    }

    @Override
    public String schemaExportTitle() {
        return "Export Schema…";
    }

    @Override
    public String diagramTitle() {
        return baseName();
    }

    @Override
    public void addMenuItems(DbmlCanvas c, Menu menu, DbmlPoint point, DbmlHit hit) {
        MenuItem save = new MenuItem(menu, SWT.PUSH);
        save.setText("Save Diagram");
        save.addListener(SWT.Selection, e -> save());
    }

    /** For tests: the window's menu bar items, by label. */
    List<String> menuTitles() {
        return java.util.Arrays.stream(shell.getMenuBar().getItems()).map(MenuItem::getText).toList();
    }
}
