package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.TableCopy;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.dialogs.Dialogs;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.model.SortOrder;
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Multi-Rename (Ctrl+M, or F2 with several items marked; MultiRenameView.swift — Total Commander's Multi-Rename
 * Tool): name and extension masks, search and replace, case and a counter, with a live preview that flags invalid
 * names, duplicates and names already taken. Renaming goes through temporary names, so swaps (a→b, b→a) work;
 * Undo reverts the last batch.
 */
public final class MultiRenameWindow {
    private record Row(Path path, String old, String newName, String problem) {}

    private record Loaded(Map<Path, RenameRule.Source> sources, Map<Path, Set<String>> folderNames) {}

    private record Outcome(List<Path[]> done, List<String> failures) {}

    private final Workspace ws;
    private final Shell shell;
    private final Display display;
    private final RenameRule rule = new RenameRule();
    private List<Path> urls;
    private Map<Path, RenameRule.Source> sources = Map.of();
    private Map<Path, Set<String>> folderNames = Map.of();
    private boolean loaded;
    private List<Path[]> undo = List.of();
    private List<Row> rows = List.of();
    private boolean busy;
    private boolean refreshScheduled;

    private Text nameMask, extMask, search, replace;
    private Combo caseMode;
    private Button regex, undoButton, renameButton;
    private Spinner start, step, digits;
    private Table table;
    private Label message;

    private MultiRenameWindow(Workspace ws, List<Path> items) {
        this.ws = ws;
        this.display = ws.display();
        List<Path> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> SortOrder.NATURAL.compare(FileOps.name(a), FileOps.name(b)));
        this.urls = sorted;
        shell = ToolWindows.create(ws, "multirename", title(), 820, 560, () -> FileHelp.MULTI_RENAME, this::selectionHelp);
        shell.setLayout(Ui.grid(1, 0, 0));
        buildForm();
        buildTable();
        buildFooter();
        shell.open();
        nameMask.setFocus();
        nameMask.selectAll();
        reload();
    }

    public static void open(Workspace ws, List<Path> items) {
        if (items == null || items.isEmpty()) {
            ws.display().beep();
            return;
        }
        new MultiRenameWindow(ws, items);
    }

    private String title() {
        return "Multi-Rename — " + Fmt.count(urls.size(), "item");
    }

    // MARK: Form

    private void buildForm() {
        Composite form = new Composite(shell, SWT.NONE);
        form.setLayout(Ui.grid(4, 12, 8));
        form.setLayoutData(Ui.fillH());

        Ui.label(form, "Name mask:");
        nameMask = field(form, "[N]", true, "Name mask",
                "The new name, built from placeholders and text: [N] the old name, [N2-5] its characters 2 to 5, [N3] the 3rd one, "
                        + "[C] the counter, [P] the parent folder, [YMD] and [hms] the modification date and time.");
        Ui.label(form, "Extension:");
        extMask = field(form, "[E]", false, "Extension mask", "The new extension: [E] keeps the old one; empty leaves the extension off. The same placeholders work here.");
        ((GridData) extMask.getLayoutData()).widthHint = 100;

        Ui.label(form, "Search for:");
        search = field(form, "", true, "Search for", "Text (or a regular expression) to replace in the new names. Case matters.");
        Ui.label(form, "Replace with:");
        replace = field(form, "", false, "Replace with", "What replaces each match; with regular expressions $1, $2… insert the groups.");
        ((GridData) replace.getLayoutData()).widthHint = 170;

        Ui.label(form, "Case:");
        Composite caseRow = new Composite(form, SWT.NONE);
        caseRow.setLayout(Ui.grid(2, 0, 10));
        caseMode = new Combo(caseRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        for (RenameRule.CaseMode m : RenameRule.CaseMode.values()) caseMode.add(m.title);
        caseMode.select(0);
        caseMode.addListener(SWT.Selection, e -> changed());
        QuickHelp.help(caseMode, "Case", "Changes the case of the whole new name: lowercase, UPPERCASE or Title Case (each word capitalised).");
        regex = new Button(caseRow, SWT.CHECK);
        regex.setText("Regular expressions");
        regex.addListener(SWT.Selection, e -> changed());
        QuickHelp.help(regex, "Regular expressions", "Search for is a regular expression (Java syntax); an invalid one is replaced as plain text.");

        Ui.label(form, "Counter [C]:");
        Composite counter = new Composite(form, SWT.NONE);
        counter.setLayout(Ui.grid(6, 0, 4));
        start = spinner(counter, "start", 0, 999_999, 1, "Counter start", "The first value of [C].");
        step = spinner(counter, "step", 1, 1000, 1, "Counter step", "How much [C] grows from one item to the next.");
        digits = spinner(counter, "digits", 1, 9, 1, "Counter digits", "The least number of digits of [C], padded with zeros (3 makes 001, 002…).");

        Label placeholders = Ui.label(form, "Placeholders: [N] name · [N2-5] characters 2–5 · [E] extension · [C] counter · [P] parent folder · [YMD] date · [hms] time");
        placeholders.setForeground(Ui.SECONDARY);
        placeholders.setLayoutData(Ui.span(4));
        QuickHelp.help(placeholders, "Placeholders", FileHelp.MULTI_RENAME.detail());
    }

    private Text field(Composite parent, String value, boolean fill, String title, String help) {
        Text t = new Text(parent, SWT.BORDER | SWT.SINGLE);
        t.setText(value);
        t.setLayoutData(fill ? Ui.fillH() : new GridData(SWT.LEFT, SWT.CENTER, false, false));
        t.addListener(SWT.Modify, e -> changed());
        QuickHelp.help(t, title, help);
        return t;
    }

    private Spinner spinner(Composite parent, String label, int min, int max, int value, String title, String help) {
        Ui.label(parent, label).setForeground(Ui.SECONDARY);
        Spinner s = new Spinner(parent, SWT.BORDER);
        s.setMinimum(min);
        s.setMaximum(max);
        s.setSelection(value);
        s.addListener(SWT.Modify, e -> changed());
        QuickHelp.help(s, title, help);
        return s;
    }

    // MARK: Preview

    private void buildTable() {
        table = new Table(shell, SWT.MULTI | SWT.FULL_SELECTION | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLayoutData(Ui.fill());
        String[] titles = { "Old Name", "New Name", "" };
        int[] widths = { 300, 330, 120 };
        for (int i = 0; i < titles.length; i++) {
            TableColumn c = new TableColumn(table, SWT.LEFT);
            c.setText(titles[i]);
            c.setWidth(widths[i]);
        }
        QuickHelp.provider(table, (c, p) -> {
            TableItem item = table.getItem(p);
            if (item != null && item.getData() instanceof Row r) {
                String what = r.problem() != null ? r.problem() + ": this name can't be used, so Rename is off until it is fixed."
                        : r.newName().equals(r.old()) ? "The name stays as it is." : "Becomes “" + r.newName() + "”.";
                return new QuickHelpItem(r.old(), what + " In " + (r.path().getParent() == null ? "" : r.path().getParent().toString()) + ".");
            }
            return new QuickHelpItem("Preview", "The old and new names as the masks make them now. Red names are invalid, duplicated or already taken.");
        });
        TableCopy.addMenuItems(table);
    }

    private void buildFooter() {
        Composite footer = new Composite(shell, SWT.NONE);
        footer.setLayout(Ui.grid(3, 10, 8));
        footer.setLayoutData(Ui.fillH());
        message = new Label(footer, SWT.NONE);
        message.setForeground(Ui.SECONDARY);
        message.setLayoutData(Ui.fillH());
        QuickHelp.help(message, "Multi-Rename", "What the last Rename or Undo did.");
        undoButton = Ui.button(footer, "Undo", this::performUndo);
        undoButton.setEnabled(false);
        QuickHelp.help(undoButton, "Undo", "Gives the files of the last batch their old names back.");
        renameButton = Ui.button(footer, "Rename", this::rename);
        GridData gd = new GridData(SWT.END, SWT.CENTER, false, false);
        gd.widthHint = 90;
        renameButton.setLayoutData(gd);
        QuickHelp.help(renameButton, "Rename", "Renames the items as the preview shows. Off while a name is invalid, duplicated or taken.", "Enter");
        shell.setDefaultButton(renameButton);
    }

    private QuickHelpItem selectionHelp() {
        if (table == null || table.isDisposed() || table.getSelectionCount() == 0) return null;
        return new QuickHelpItem(Fmt.count(table.getSelectionCount(), "Row") + " Selected", "Copy Row copies the old and new names of the selected rows.");
    }

    private void readRule() {
        rule.nameMask = nameMask.getText();
        rule.extMask = extMask.getText();
        rule.search = search.getText();
        rule.replace = replace.getText();
        rule.useRegex = regex.getSelection();
        rule.caseMode = RenameRule.CaseMode.values()[Math.max(0, caseMode.getSelectionIndex())];
        rule.counterStart = start.getSelection();
        rule.counterStep = step.getSelection();
        rule.counterDigits = digits.getSelection();
    }

    private void changed() {
        if (refreshScheduled || shell.isDisposed()) return;
        refreshScheduled = true;
        display.timerExec(80, () -> {
            refreshScheduled = false;
            if (!shell.isDisposed()) refreshPreview();
        });
    }

    private static String key(Path folder) {
        return folder == null ? "" : folder.toString().toLowerCase(Locale.ROOT);
    }

    private List<Row> preview() {
        readRule();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            Path p = urls.get(i);
            RenameRule.Source s = sources.get(p);
            if (s == null) s = new RenameRule.Source(FileOps.name(p), false, p.getParent() == null ? "" : FileOps.name(p.getParent()), 0);
            names.add(rule.newName(s, i));
        }
        Map<String, Integer> seen = new HashMap<>();
        Map<String, Set<String>> sourceNames = new HashMap<>();
        for (int i = 0; i < urls.size(); i++) {
            Path folder = urls.get(i).getParent();
            seen.merge(key(folder) + "\n" + names.get(i).toLowerCase(Locale.ROOT), 1, Integer::sum);
            sourceNames.computeIfAbsent(key(folder), k -> new HashSet<>()).add(FileOps.name(urls.get(i)).toLowerCase(Locale.ROOT));
        }
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            Path p = urls.get(i);
            String old = FileOps.name(p), name = names.get(i);
            String lower = name.toLowerCase(Locale.ROOT);
            Path folder = p.getParent();
            String problem = null;
            if (name.equals(".") || name.equals("..") || FileOps.nameProblem(name) != null) {
                problem = "Invalid name";
            } else if (seen.getOrDefault(key(folder) + "\n" + lower, 0) > 1) {
                problem = "Duplicate";
            } else if (!lower.equals(old.toLowerCase(Locale.ROOT))
                    && !sourceNames.getOrDefault(key(folder), Set.of()).contains(lower)
                    && folderNames.getOrDefault(folder, Set.of()).contains(lower)) {
                problem = "Already exists";
            }
            out.add(new Row(p, old, name, problem));
        }
        return out;
    }

    private void refreshPreview() {
        rows = preview();
        int top = table.getTopIndex();
        table.setRedraw(false);
        table.removeAll();
        boolean problems = false, changes = false;
        for (Row r : rows) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] { r.old(), r.newName(), r.problem() == null ? "" : r.problem() });
            item.setData(r);
            if (r.problem() != null) {
                item.setForeground(1, Ui.RED);
                item.setForeground(2, Ui.RED);
                problems = true;
            } else if (r.newName().equals(r.old())) {
                item.setForeground(1, Ui.SECONDARY);
            } else {
                changes = true;
            }
        }
        table.setTopIndex(top);
        table.setRedraw(true);
        renameButton.setEnabled(loaded && !busy && !problems && changes);
        undoButton.setEnabled(!busy && !undo.isEmpty());
    }

    // MARK: Reading the items

    private void reload() {
        List<Path> list = List.copyOf(urls);
        loaded = false;
        refreshPreview();
        message.setText("Reading the items…");
        Background.run(shell, () -> read(list), l -> {
            sources = l.sources();
            folderNames = l.folderNames();
            loaded = true;
            if (message.getText().startsWith("Reading")) message.setText("");
            refreshPreview();
        });
    }

    private static Loaded read(List<Path> list) {
        Map<Path, RenameRule.Source> src = new HashMap<>();
        Map<Path, Set<String>> folders = new HashMap<>();
        for (Path p : list) {
            boolean dir = false;
            long modified = 0;
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                dir = a.isDirectory() && !FileOps.isLink(p, a);
                modified = a.lastModifiedTime().toMillis();
            } catch (IOException e) {
                // shown with its name only
            }
            Path parent = p.getParent();
            src.put(p, new RenameRule.Source(FileOps.name(p), dir, parent == null ? "" : FileOps.name(parent), modified));
            if (parent != null && !folders.containsKey(parent)) {
                Set<String> names = new HashSet<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent)) {
                    for (Path c : ds) names.add(FileOps.name(c).toLowerCase(Locale.ROOT));
                } catch (IOException e) {
                    // no taken names known
                }
                folders.put(parent, names);
            }
        }
        return new Loaded(src, folders);
    }

    // MARK: Renaming

    private void rename() {
        List<Path[]> moves = new ArrayList<>();
        for (Row r : rows) {
            if (r.problem() != null) return;
            if (!r.newName().equals(r.old())) moves.add(new Path[] { r.path(), r.path().resolveSibling(r.newName()) });
        }
        if (moves.isEmpty()) return;
        run(moves, false);
    }

    private void performUndo() {
        if (undo.isEmpty()) return;
        run(undo, true);
    }

    private void run(List<Path[]> moves, boolean undoing) {
        busy = true;
        renameButton.setEnabled(false);
        undoButton.setEnabled(false);
        Background.run(shell, () -> applyMoves(moves), outcome -> {
            busy = false;
            Map<Path, Path> map = new HashMap<>();
            for (Path[] m : outcome.done()) map.put(m[0], m[1]);
            List<Path> next = new ArrayList<>();
            for (Path p : urls) next.add(map.getOrDefault(p, p));
            urls = next;
            if (undoing) {
                undo = List.of();
                message.setText("Undid " + Fmt.count(outcome.done().size(), "rename") + ".");
            } else {
                List<Path[]> back = new ArrayList<>();
                for (Path[] m : outcome.done()) back.add(new Path[] { m[1], m[0] });
                undo = back;
                message.setText("Renamed " + Fmt.count(outcome.done().size(), "item") + ".");
            }
            shell.setText(title());
            TransferManager.get().filesChanged();
            if (!outcome.failures().isEmpty()) {
                Dialogs.message(shell, undoing ? "Some items couldn't be renamed back." : "Some items couldn't be renamed.",
                        String.join("\n", outcome.failures().subList(0, Math.min(20, outcome.failures().size()))));
            }
            reload();
        }, err -> {
            busy = false;
            Dialogs.error(shell, "Couldn't rename", err);
            reload();
        });
    }

    /** Moves in two passes through temporary names, so swaps work; a move that fails goes back to its old name. */
    static Outcome applyMoves(List<Path[]> moves) {
        List<Path[]> staged = new ArrayList<>();          // original, temporary, final
        List<String> failures = new ArrayList<>();
        for (Path[] m : moves) {
            Path temp = m[0].resolveSibling(".mxrename-" + UUID.randomUUID());
            try {
                Files.move(m[0], temp);
                staged.add(new Path[] { m[0], temp, m[1] });
            } catch (IOException e) {
                failures.add(FileOps.name(m[0]) + ": " + TransferManager.message(e));
            }
        }
        List<Path[]> done = new ArrayList<>();
        for (Path[] s : staged) {
            try {
                Files.move(s[1], s[2]);
                done.add(new Path[] { s[0], s[2] });
            } catch (IOException e) {
                try {
                    Files.move(s[1], s[0]);
                } catch (IOException ignored) {
                    failures.add(FileOps.name(s[0]) + " was left as " + FileOps.name(s[1]));
                }
                failures.add(FileOps.name(s[2]) + ": " + TransferManager.message(e));
            }
        }
        return new Outcome(done, failures);
    }
}
