package com.k3jdr.mxdatasql;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.json.Json;
import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mx.dbml.DbmlFiles;
import com.k3jdr.mx.dbml.DiagramWindow;

/** Diagram ▸ Write DBML… and Open DBML… (DiagramCommands.swift DBMLExport). */
public final class DbmlExport {
    private DbmlExport() {}

    private static final Prefs PREFS = Prefs.of("com.k3jdr.mxdatasql");

    /** Asks for the schemas and the file, writes the connected database's schema as DBML, opens the diagram. */
    public static void begin(Shell shell, Session session) {
        PgService svc = session.service();
        String database = session.connectedDatabase;
        if (svc == null || database == null) return;
        session.run(() -> {
            List<String> schemas = new ArrayList<>();
            for (String[] r : svc.rows(DbmlWriter.SCHEMAS_SQL)) schemas.add(r[0]);
            return schemas;
        }, schemas -> {
            if (schemas.isEmpty()) {
                session.showError("Database " + database + " has no tables to diagram.");
                return;
            }
            Options d = new Options(shell, database, schemas);
            if (d.open() != Dialog.OK) return;
            List<String> chosen = new ArrayList<>();
            for (String s : schemas) if (d.chosen.contains(s)) chosen.add(s);
            if (chosen.isEmpty()) {
                session.showError("Choose at least one schema to write.");
                return;
            }
            FileDialog fd = new FileDialog(shell, SWT.SAVE);
            fd.setText("Write DBML");
            fd.setFilterExtensions(new String[] { "*.dbml" });
            fd.setFilterNames(new String[] { "DBML files (*.dbml)" });
            fd.setFileName(database + ".dbml");
            String folder = PREFS.get("dbmlFolder", null);
            if (folder != null) fd.setFilterPath(folder);
            fd.setOverwrite(true);
            String file = fd.open();
            if (file == null) return;
            Path path = Path.of(file);
            if (path.getParent() != null) PREFS.put("dbmlFolder", path.getParent().toString());
            write(shell, session, svc, database, chosen, path, d.openAfter);
        }, null);
    }

    private record Written(String text, DbmlWriter.Result result) {}

    static void write(Shell shell, Session session, PgService svc, String database, List<String> schemas, Path file, boolean openAfter) {
        session.run(() -> {
            DbmlWriter.Result result = DbmlWriter.generate(DbmlWriter.read(svc, schemas), database, schemas);
            String old = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            // Rewriting a diagram that was already arranged keeps its notes and layout.
            String text = DbmlFiles.keepLayout(result.text(), old);
            Json.writeText(file, text);
            return new Written(text, result);
        }, w -> {
            session.infoMessage = "Wrote " + file.getFileName() + ": " + w.result().tables() + " tables, " + w.result().refs() + " relationships";
            if (openAfter) DiagramWindow.open(shell.getDisplay(), w.text(), file);
        }, error -> session.showError("Could not write the DBML file.\n\n" + DatabaseError.message(error)));
    }

    /** Open DBML…: any .dbml file in a diagram window (no connection needed). */
    public static void open(Shell shell) {
        FileDialog fd = new FileDialog(shell, SWT.OPEN);
        fd.setText("Open DBML");
        fd.setFilterExtensions(new String[] { "*.dbml", "*.*" });
        fd.setFilterNames(new String[] { "DBML files (*.dbml)", "All files (*.*)" });
        String folder = PREFS.get("dbmlFolder", null);
        if (folder != null) fd.setFilterPath(folder);
        String file = fd.open();
        if (file == null) return;
        Path path = Path.of(file);
        if (path.getParent() != null) PREFS.put("dbmlFolder", path.getParent().toString());
        try {
            DiagramWindow.open(shell.getDisplay(), path);
        } catch (RuntimeException e) {
            ErrorDialog.show(shell, "Open DBML", "Could not open " + path.getFileName() + ".\n\n" + ErrorDialog.describe(e));
        }
    }

    /** The options under the Save panel on macOS: the schemas to include and whether to open the diagram. */
    private static final class Options extends Dialog {
        final String database;
        final List<String> schemas;
        final Set<String> chosen = new LinkedHashSet<>();
        boolean openAfter = true;

        Options(Shell parent, String database, List<String> schemas) {
            super(parent);
            this.database = database;
            this.schemas = schemas;
            chosen.addAll(schemas);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Write DBML");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(Ui.grid(1, 16, 8));
            Ui.wrap(area, "Writes the schema diagram of " + database + " as DBML. Choose the schemas to include, then where to write it.");
            Ui.label(area, "Schemas", Ui.bold());
            ScrolledComposite scroll = new ScrolledComposite(area, SWT.V_SCROLL | SWT.BORDER);
            Composite list = new Composite(scroll, SWT.NONE);
            list.setLayout(Ui.grid(1, 6, 3));
            for (String s : schemas) {
                Button b = Ui.check(list, s, true, v -> {
                    if (v) chosen.add(s); else chosen.remove(s);
                });
                QuickHelp.help(b, s, "Includes schema " + s + "'s tables, their relationships and a table group.");
            }
            scroll.setContent(list);
            Point size = list.computeSize(SWT.DEFAULT, SWT.DEFAULT);
            list.setSize(size);
            GridData gd = Ui.fillH();
            gd.heightHint = Math.min(size.y + 4, 160);
            gd.widthHint = 340;
            scroll.setLayoutData(gd);
            Button open = Ui.check(area, "Open the diagram after writing", true, v -> openAfter = v);
            QuickHelp.help(open, "Open the Diagram", "Opens the written file in a diagram window.");
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
            createButton(parent, IDialogConstants.OK_ID, "Write…", true);
        }
    }
}
