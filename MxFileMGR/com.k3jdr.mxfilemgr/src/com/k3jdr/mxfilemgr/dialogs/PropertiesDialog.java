package com.k3jdr.mxfilemgr.dialogs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Properties (Ctrl+I, Sheets.swift's PropertiesSheet): kind, total size (counted in the background), dates and
 * owner; change the attributes (Read-only, Hidden, Archive, System — the Windows counterpart of the Mac's
 * permission bits), optionally for everything inside a folder, and the modification date.
 */
public final class PropertiesDialog extends Dialog {
    private static final String[] ATTRS = { "readonly", "hidden", "archive", "system" };
    private static final String[] ATTR_TITLES = { "Read-only", "Hidden", "Archive", "System" };

    private final Workspace ws;
    private final List<Path> paths;
    private final Button[] attrButtons = new Button[4];
    private final boolean[] original = new boolean[4];
    private Button recursive;
    private DateTime date, time;
    private LocalDateTime originalModified;
    private Label size;

    private PropertiesDialog(Workspace ws, List<Path> paths) {
        super(ws.shell());
        this.ws = ws;
        this.paths = paths;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    public static void open(Workspace ws, List<Path> paths) {
        new PropertiesDialog(ws, paths).open();
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(paths.size() == 1 ? FileOps.name(paths.get(0)) + " Properties" : paths.size() + " Items Properties");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(1, 14, 10));
        Path first = paths.get(0);
        boolean single = paths.size() == 1;
        Ui.label(area, single ? FileOps.name(first) : paths.size() + " items", Ui.subheading());
        Text where = Ui.selectable(area, first.getParent() == null ? first.toString() : first.getParent().toString(), SWT.NONE);
        where.setForeground(Ui.SECONDARY);
        where.setLayoutData(Ui.fillH());

        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayout(Ui.grid(2, 0, 6));
        grid.setLayoutData(Ui.fillH());
        DosFileAttributes a = null;
        try {
            a = Files.readAttributes(first, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            // shown as unknown
        }
        boolean folder = a != null && a.isDirectory();
        row(grid, "Kind:", single ? ShellInfo.typeName(FileOps.extension(first), folder) + (a != null && FileOps.isLink(first, a) ? " (link)" : "")
                : "Multiple items");
        size = row(grid, "Size:", "Calculating…");
        if (single && a != null) {
            row(grid, "Created:", Fmt.longDate(a.creationTime().toMillis()));
            row(grid, "Accessed:", Fmt.longDate(a.lastAccessTime().toMillis()));
            String owner;
            try {
                owner = Files.getOwner(first, LinkOption.NOFOLLOW_LINKS).getName();
            } catch (IOException | UnsupportedOperationException e) {
                owner = "?";
            }
            row(grid, "Owner:", owner);
        }
        Ui.label(grid, "Modified:").setForeground(Ui.SECONDARY);
        Composite when = new Composite(grid, SWT.NONE);
        when.setLayout(Ui.grid(2, 0, 6));
        date = new DateTime(when, SWT.DATE | SWT.DROP_DOWN | SWT.BORDER);
        time = new DateTime(when, SWT.TIME | SWT.MEDIUM | SWT.BORDER);
        long mod = a != null ? a.lastModifiedTime().toMillis() : System.currentTimeMillis();
        originalModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(mod), ZoneId.systemDefault()).withNano(0);
        date.setDate(originalModified.getYear(), originalModified.getMonthValue() - 1, originalModified.getDayOfMonth());
        time.setTime(originalModified.getHour(), originalModified.getMinute(), originalModified.getSecond());
        QuickHelp.help(date, "Modified", "The modification date; change it, then Apply. Applies to every item shown.");
        QuickHelp.help(time, "Modified", "The modification time; change it, then Apply.");

        Group attrs = new Group(area, SWT.NONE);
        attrs.setText("Attributes");
        attrs.setLayout(Ui.grid(4, 8, 12));
        attrs.setLayoutData(Ui.fillH());
        for (int i = 0; i < 4; i++) {
            attrButtons[i] = new Button(attrs, SWT.CHECK);
            attrButtons[i].setText(ATTR_TITLES[i]);
            if (a != null) {
                original[i] = switch (i) {
                    case 0 -> a.isReadOnly();
                    case 1 -> a.isHidden();
                    case 2 -> a.isArchive();
                    default -> a.isSystem();
                };
            }
            attrButtons[i].setSelection(original[i]);
            QuickHelp.help(attrButtons[i], ATTR_TITLES[i], switch (i) {
                case 0 -> "Read-only files can't be changed or deleted without clearing this first.";
                case 1 -> "Hidden items are left out of the lists unless Show Hidden Files (Ctrl+Shift+.) is on.";
                case 2 -> "Marks the item for backup programs; Windows sets it when the file changes.";
                default -> "Marks files Windows itself uses; such items are usually hidden too.";
            });
        }
        if (paths.stream().anyMatch(FileOps::isRealDirectory)) {
            recursive = new Button(attrs, SWT.CHECK);
            recursive.setText("Apply to enclosed items");
            recursive.setLayoutData(Ui.span(4));
            QuickHelp.help(recursive, "Apply to enclosed items", "Changes the attributes of everything inside the folders too.");
        }
        QuickHelp.help(area, FileHelp.PROPERTIES.title(), FileHelp.PROPERTIES.detail(), FileHelp.PROPERTIES.shortcut());

        Background.run(area, () -> FileOps.totals(paths, () -> area.isDisposed()), t -> {
            if (!size.isDisposed()) {
                size.setText(Fmt.size(t[0]) + " (" + Fmt.bytes(t[0]) + " bytes), " + Fmt.count(t[1], "item"));
                size.getParent().layout();
            }
        });
        return area;
    }

    private static Label row(Composite grid, String title, String value) {
        Ui.label(grid, title).setForeground(Ui.SECONDARY);
        Label l = Ui.label(grid, value);
        l.setLayoutData(Ui.fillH());
        return l;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button win = createButton(parent, IDialogConstants.CLIENT_ID + 1, "Windows Properties…", false);
        QuickHelp.help(win, "Windows Properties", "Opens Explorer's own Properties window for the first item: sharing, security, previous versions and details.");
        createButton(parent, IDialogConstants.OK_ID, "Apply", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == IDialogConstants.CLIENT_ID + 1) {
            ShellInfo.shellProperties(paths.get(0));
            return;
        }
        super.buttonPressed(id);
    }

    @Override
    protected void okPressed() {
        LocalDateTime chosen = LocalDateTime.of(date.getYear(), date.getMonth() + 1, date.getDay(), time.getHours(), time.getMinutes(), time.getSeconds());
        boolean dateChanged = !chosen.equals(originalModified);
        boolean[] now = new boolean[4];
        boolean attrsChanged = false;
        for (int i = 0; i < 4; i++) {
            now[i] = attrButtons[i].getSelection();
            if (now[i] != original[i]) attrsChanged = true;
        }
        boolean deep = recursive != null && recursive.getSelection();
        List<String> failures = new ArrayList<>();
        FileTime ft = FileTime.from(chosen.atZone(ZoneId.systemDefault()).toInstant());
        for (Path p : paths) {
            try {
                if (attrsChanged) setAttributes(p, now, false);
                if (dateChanged) {
                    boolean ro = (Boolean) Files.getAttribute(p, "dos:readonly", LinkOption.NOFOLLOW_LINKS);
                    if (ro) Files.setAttribute(p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                    Files.setLastModifiedTime(p, ft);
                    if (ro) Files.setAttribute(p, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS);
                }
                if (attrsChanged && deep && FileOps.isRealDirectory(p)) {
                    Files.walkFileTree(p, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes at) {
                            if (!dir.equals(p)) {
                                try {
                                    setAttributes(dir, now, true);
                                } catch (IOException e) {
                                    failures.add(FileOps.name(dir));
                                }
                                if (FileOps.isLink(dir, at)) return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes at) {
                            try {
                                setAttributes(file, now, false);
                            } catch (IOException e) {
                                failures.add(FileOps.name(file));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            failures.add(FileOps.name(file));
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (IOException e) {
                failures.add(FileOps.name(p) + ": " + TransferManager.message(e));
            }
        }
        TransferManager.get().filesChanged();
        super.okPressed();
        if (!failures.isEmpty()) {
            Dialogs.message(ws.shell(), "Some items couldn't be changed.", String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
        }
    }

    /** Sets the attributes that were changed in the dialog. Folders inside keep their read-only flag. */
    private void setAttributes(Path p, boolean[] values, boolean innerFolder) throws IOException {
        for (int i = 0; i < 4; i++) {
            if (values[i] == original[i] || (innerFolder && i == 0)) continue;
            Files.setAttribute(p, "dos:" + ATTRS[i], values[i], LinkOption.NOFOLLOW_LINKS);
        }
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 480), p.y);
    }
}
