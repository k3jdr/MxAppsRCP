package com.k3jdr.mxdatasql;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.prefs.Prefs;

/**
 * SQL files (SQLFiles.swift): the SQL folder and the last 10 files opened or saved, remembered for the whole app,
 * and the Open / Save dialogs that start in the SQL folder.
 */
public final class SqlLibrary {
    private SqlLibrary() {}

    private static final Prefs PREFS = Prefs.of("com.k3jdr.mxdatasql");
    private static final String FOLDER = "sqlFolder";
    private static final String RECENT = "sqlRecent";
    private static final int RECENT_LIMIT = 10;

    public static Path folder() {
        String f = PREFS.get(FOLDER, null);
        if (f == null) return null;
        Path p = Path.of(f);
        return Files.isDirectory(p) ? p : null;
    }

    /** Choose / Change SQL Folder…: the folder whose .sql files the SQL Folder menu lists. */
    public static Path chooseFolder(Shell shell) {
        DirectoryDialog d = new DirectoryDialog(shell);
        d.setText("Choose SQL Folder");
        d.setMessage("MxDataSQL lists the .sql files in this folder and opens Save and Open dialogs there.");
        Path current = folder();
        d.setFilterPath(current != null ? current.toString() : System.getProperty("user.home") + "\\Documents");
        String chosen = d.open();
        if (chosen == null) return null;
        PREFS.put(FOLDER, chosen);
        return Path.of(chosen);
    }

    /** The .sql files in the SQL folder, by name. */
    public static List<Path> folderFiles() {
        Path folder = folder();
        List<Path> out = new ArrayList<>();
        if (folder == null) return out;
        try (Stream<Path> s = Files.list(folder)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".sql")
                    && !p.getFileName().toString().startsWith(".")).forEach(out::add);
        } catch (IOException e) {
            return out;
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    public static List<Path> recentFiles() {
        List<Path> out = new ArrayList<>();
        for (String s : PREFS.getList(RECENT)) {
            Path p = Path.of(s);
            if (Files.exists(p)) out.add(p);
        }
        return out;
    }

    public static void noteRecent(Path file) {
        PREFS.pushRecent(RECENT, file.toAbsolutePath().toString(), RECENT_LIMIT);
    }

    public static void clearRecent() {
        PREFS.remove(RECENT);
    }

    public static Path openDialog(Shell shell) {
        FileDialog d = new FileDialog(shell, SWT.OPEN);
        d.setText("Open SQL File");
        d.setFilterExtensions(new String[] { "*.sql", "*.*" });
        d.setFilterNames(new String[] { "SQL files (*.sql)", "All files (*.*)" });
        Path folder = folder();
        if (folder != null) d.setFilterPath(folder.toString());
        String f = d.open();
        return f == null ? null : Path.of(f);
    }

    public static Path saveDialog(Shell shell, String suggestedName) {
        FileDialog d = new FileDialog(shell, SWT.SAVE);
        d.setText("Save SQL");
        d.setFilterExtensions(new String[] { "*.sql" });
        d.setFilterNames(new String[] { "SQL files (*.sql)" });
        d.setFileName(suggestedName);
        d.setOverwrite(true);
        Path folder = folder();
        if (folder != null) d.setFilterPath(folder.toString());
        String f = d.open();
        return f == null ? null : Path.of(f);
    }

    /** UTF-8, else Latin-1. */
    public static String read(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString().replace("\r\n", "\n");
        } catch (CharacterCodingException e) {
            return new String(data, StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
        }
    }

    /** UTF-8 with a final line break. */
    public static void write(String text, Path file) throws IOException {
        String contents = text.endsWith("\n") ? text : text + "\n";
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
