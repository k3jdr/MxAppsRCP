package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * The F3 viewer (Total Commander's Lister; ViewerView.swift): Preview shows pictures scaled to fit, Text shows the
 * file as text (Ctrl+F finds, F3 finds the next one, Wrap lines folds long ones), Hex shows its bytes. Quick View
 * (Space in a file list) is one reusable viewer window that follows the active pane's selection; Space or Escape
 * closes it.
 */
public final class ViewerWindow {
    enum Mode {
        PREVIEW("Preview"), TEXT("Text"), HEX("Hex");

        final String title;

        Mode(String title) { this.title = title; }
    }

    static final int TEXT_LIMIT = 20 << 20;
    static final int HEX_LIMIT = 1 << 20;
    static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "ico", "tif", "tiff");
    static final Set<String> TEXT_TYPES = Set.of("txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "html", "htm", "css",
            "js", "mjs", "ts", "tsx", "jsx", "java", "swift", "c", "h", "cpp", "hpp", "cc", "cs", "py", "rb", "go", "rs", "kt", "kts",
            "sh", "bash", "bat", "cmd", "ps1", "psm1", "ini", "cfg", "conf", "yaml", "yml", "toml", "sql", "properties", "gradle",
            "php", "pl", "lua", "r", "m", "mm", "tex", "svg", "gitignore", "gitattributes", "env", "srt", "vtt", "reg", "inf",
            "nfo", "diff", "patch", "e4xmi", "product", "target", "mf", "classpath", "project", "editorconfig", "rtf");

    private static ViewerWindow quick;

    private final Workspace ws;
    private final boolean isQuick;
    private final Shell shell;
    private final Display display;
    private Path file;
    private Mode mode = Mode.TEXT;
    private long generation;
    private Image image;
    /** Said before the file's details: why another mode than the one asked for is shown. */
    private String notice, pendingNotice;

    private Button[] modeButtons;
    private Button wrap, open, showInPane;
    private Label note;
    private Text pathLabel;
    private Composite body;
    private final StackLayout stack = new StackLayout();
    private Canvas canvas;
    private StyledText text;
    private Label placeholder;
    private Composite findBar;
    private Text findText;
    private Label findNote;
    private Listener keys;

    private ViewerWindow(Workspace ws, Path file, boolean isQuick) {
        this.ws = ws;
        this.isQuick = isQuick;
        this.display = ws.display();
        this.file = file;
        shell = ToolWindows.create(ws, isQuick ? "quickview" : "viewer", title(), isQuick ? 600 : 860, isQuick ? 520 : 660,
                () -> isQuick ? new QuickHelpItem("Quick View", "A quick look at the item selected in the active pane; it follows the selection. "
                        + "Space or Escape closes it. " + FileHelp.VIEWER.detail(), "Space") : FileHelp.VIEWER,
                () -> this.file == null ? null : new QuickHelpItem(FileOps.name(this.file), this.file.toString()));
        shell.setLayout(Ui.grid(1, 0, 0));
        build();
        keys = this::key;
        display.addFilter(SWT.KeyDown, keys);
        shell.addListener(SWT.Dispose, e -> {
            display.removeFilter(SWT.KeyDown, keys);
            if (image != null && !image.isDisposed()) image.dispose();
            if (quick == this) quick = null;
        });
        shell.open();
        load(file);
    }

    /** F3: a viewer window of its own for {@code file}. */
    public static void open(Workspace ws, Path file) {
        new ViewerWindow(ws, file, false);
    }

    /** Quick View (Space): a viewer window that follows the active pane's selection. */
    public static boolean isQuickViewOpen() {
        return quick != null && !quick.shell.isDisposed();
    }

    public static void showQuickView(Workspace ws, Path file) {
        if (isQuickViewOpen()) {
            quick.load(file);
        } else {
            quick = new ViewerWindow(ws, file, true);
        }
        // Give the keyboard back to the file list, so arrowing on updates the view.
        if (!ws.shell().isDisposed()) ws.shell().setActive();
    }

    public static void closeQuickView() {
        if (isQuickViewOpen()) quick.shell.close();
        quick = null;
    }

    public static void updateQuickView(Path file) {
        if (isQuickViewOpen() && file != null && !file.equals(quick.file)) quick.load(file);
    }

    private String title() {
        return (isQuick ? "Quick View — " : "") + (file == null ? "" : FileOps.name(file));
    }

    // MARK: Window

    private void build() {
        Composite bar = new Composite(shell, SWT.NONE);
        bar.setLayout(Ui.grid(7, 8, 8));
        bar.setLayoutData(Ui.fillH());
        Composite modes = new Composite(bar, SWT.NONE);
        modes.setLayout(Ui.grid(3, 0, 0));
        modeButtons = new Button[Mode.values().length];
        for (Mode m : Mode.values()) {
            Button b = new Button(modes, SWT.TOGGLE);
            b.setText(m.title);
            b.addListener(SWT.Selection, e -> setMode(m, true));
            modeButtons[m.ordinal()] = b;
            QuickHelp.help(b, m.title, switch (m) {
                case PREVIEW -> "Shows pictures (PNG, JPEG, GIF, BMP, ICO, TIFF) scaled to fit the window.";
                case TEXT -> "Shows the file as text: Ctrl+F finds, F3 finds the next match, Wrap lines folds long lines.";
                case HEX -> "Shows the file's bytes in hexadecimal with their characters, 16 to a line (the first megabyte).";
            });
        }
        wrap = new Button(bar, SWT.CHECK);
        wrap.setText("Wrap lines");
        wrap.setSelection(true);
        wrap.addListener(SWT.Selection, e -> text.setWordWrap(mode == Mode.TEXT && wrap.getSelection()));
        QuickHelp.help(wrap, "Wrap lines", "Folds lines longer than the window instead of scrolling sideways.");
        note = new Label(bar, SWT.NONE);
        note.setForeground(Ui.SECONDARY);
        note.setLayoutData(Ui.fillH());
        QuickHelp.help(note, "Details", "The file's encoding or picture size, and whether only its beginning is shown.");
        Button find = Ui.button(bar, "Find…", this::showFind);
        QuickHelp.help(find, "Find", "Finds text in the Text or Hex view.", "Ctrl+F");
        open = Ui.button(bar, "Open", () -> {
            if (file != null && !ShellInfo.open(file)) display.beep();
        });
        QuickHelp.help(open, "Open", "Opens the file with its default app.");
        showInPane = Ui.button(bar, "Show in Pane", () -> {
            if (file != null) ws.reveal(file);
        });
        QuickHelp.help(showInPane, "Show in Pane", "Shows the file in the active pane, selected, and brings the main window to the front.");
        new Label(bar, SWT.NONE).setLayoutData(new GridData(0, 0));

        pathLabel = Ui.selectable(shell, "", SWT.SINGLE);
        pathLabel.setForeground(Ui.SECONDARY);
        GridData pg = Ui.fillH();
        pg.horizontalIndent = 8;
        pathLabel.setLayoutData(pg);
        QuickHelp.help(pathLabel, "Folder", "The folder the file is in.");

        findBar = new Composite(shell, SWT.NONE);
        findBar.setLayout(Ui.grid(5, 8, 6));
        GridData fb = Ui.fillH();
        findBar.setLayoutData(fb);
        Ui.label(findBar, "Find:");
        findText = new Text(findBar, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        GridData ft = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        ft.widthHint = 260;
        findText.setLayoutData(ft);
        findText.addListener(SWT.DefaultSelection, e -> find((e.stateMask & SWT.SHIFT) == 0));
        findText.addListener(SWT.Modify, e -> findNote.setText(""));
        QuickHelp.help(findText, "Find", "Text to find, ignoring case. Enter finds the next match, Shift+Enter the previous one; Escape closes the bar.", "Ctrl+F");
        Button prev = Ui.button(findBar, "▲", () -> find(false));
        QuickHelp.help(prev, "Previous", "Finds the previous match.", "Shift+F3");
        Button next = Ui.button(findBar, "▼", () -> find(true));
        QuickHelp.help(next, "Next", "Finds the next match.", "F3");
        findNote = new Label(findBar, SWT.NONE);
        findNote.setForeground(Ui.SECONDARY);
        findNote.setLayoutData(Ui.fillH());
        QuickHelp.help(findNote, "Find", "Says when the text isn't found.");
        Ui.setVisible(findBar, false);

        body = new Composite(shell, SWT.BORDER);
        body.setLayoutData(Ui.fill());
        body.setLayout(stack);
        canvas = new Canvas(body, SWT.DOUBLE_BUFFERED);
        canvas.setBackground(Ui.PANEL);
        canvas.addListener(SWT.Paint, e -> paint(e.gc));
        canvas.addListener(SWT.Resize, e -> canvas.redraw());
        QuickHelp.help(canvas, "Preview", "The picture, scaled down to fit the window. Text or Hex shows its bytes instead.");
        text = new StyledText(body, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        text.setFont(Ui.mono());
        text.setMargins(6, 6, 6, 6);
        QuickHelp.help(text, FileHelp.VIEWER.title(), FileHelp.VIEWER.detail(), FileHelp.VIEWER.shortcut());
        placeholder = new Label(body, SWT.CENTER | SWT.WRAP);
        QuickHelp.help(placeholder, "Viewer", "The file is being read.");
        stack.topControl = placeholder;
    }

    private void key(org.eclipse.swt.widgets.Event e) {
        if (!(e.widget instanceof Control c) || c.isDisposed() || c.getShell() != shell) return;
        boolean ctrl = (e.stateMask & SWT.MOD1) != 0;
        if (ctrl && (e.keyCode == 'f' || e.keyCode == 'F')) {
            showFind();
            e.doit = false;
        } else if (e.keyCode == SWT.F3) {
            find((e.stateMask & SWT.SHIFT) == 0);
            e.doit = false;
        } else if (e.keyCode == SWT.ESC) {
            e.doit = false;
            if (findBar.getVisible()) hideFind();
            else shell.close();
        } else if (isQuick && e.character == ' ' && e.stateMask == 0 && e.widget != findText) {
            e.doit = false;
            shell.close();
        }
    }

    // MARK: Loading

    private void load(Path p) {
        file = p;
        long gen = ++generation;
        shell.setText(title());
        pathLabel.setText(p.getParent() == null ? p.toString() : p.getParent().toString());
        note.setText("");
        placeholder.setText("\n\nReading “" + FileOps.name(p) + "”…");
        stack.topControl = placeholder;
        body.layout();
        Background.run(shell, () -> detect(p), m -> {
            if (gen == generation) setMode(m, false);
        });
    }

    /** Pictures open in Preview, text-like files (by extension, or no NUL bytes at the start) in Text, others in Hex. */
    static Mode detect(Path p) {
        String ext = FileOps.extension(p).toLowerCase(Locale.ROOT);
        if (IMAGES.contains(ext)) return Mode.PREVIEW;
        if (TEXT_TYPES.contains(ext)) return Mode.TEXT;
        try (InputStream in = Files.newInputStream(p)) {
            byte[] head = in.readNBytes(8192);
            for (byte b : head) if (b == 0) return Mode.HEX;
            return Mode.TEXT;
        } catch (IOException e) {
            return Mode.TEXT;
        }
    }

    private void setMode(Mode m, boolean byUser) {
        Mode target = m;
        notice = pendingNotice;
        pendingNotice = null;
        boolean picture = IMAGES.contains(FileOps.extension(file).toLowerCase(Locale.ROOT));
        if (target == Mode.PREVIEW && !picture) {
            target = Mode.TEXT;
            if (byUser) notice = "No preview for this kind of file: showing it as text.";
        }
        mode = target;
        for (Mode x : Mode.values()) modeButtons[x.ordinal()].setSelection(x == mode);
        wrap.setEnabled(mode == Mode.TEXT);
        Path p = file;
        long gen = ++generation;
        placeholder.setText("\n\nReading “" + FileOps.name(p) + "”…");
        stack.topControl = placeholder;
        body.layout();
        switch (mode) {
            case PREVIEW -> Background.run(shell, () -> new ImageLoader().load(p.toString())[0], data -> {
                if (gen != generation) return;
                showImage(data, p);
            }, err -> {
                if (gen != generation) return;
                pendingNotice = "The picture can't be read: showing it as text.";
                setMode(Mode.TEXT, false);
            });
            case TEXT -> Background.run(shell, () -> readText(p), r -> {
                if (gen == generation) showText(r[0], r[1], true);
            });
            case HEX -> Background.run(shell, () -> hexDump(p), r -> {
                if (gen == generation) showText(r[0], r[1], false);
            });
        }
    }

    private void showImage(ImageData data, Path p) {
        if (image != null && !image.isDisposed()) image.dispose();
        image = new Image(display, data);
        note.setText(FileOps.extension(p).toUpperCase(Locale.ROOT) + " · " + data.width + " × " + data.height + " · " + Fmt.size(FileOps.size(p)));
        stack.topControl = canvas;
        body.layout();
        canvas.redraw();
    }

    private void paint(GC gc) {
        Rectangle area = canvas.getClientArea();
        if (image == null || image.isDisposed() || area.width <= 0 || area.height <= 0) return;
        Rectangle b = image.getBounds();
        double scale = Math.min(1.0, Math.min((double) (area.width - 16) / b.width, (double) (area.height - 16) / b.height));
        int w = Math.max(1, (int) Math.round(b.width * scale)), h = Math.max(1, (int) Math.round(b.height * scale));
        gc.setInterpolation(SWT.HIGH);
        gc.setAntialias(SWT.ON);
        gc.drawImage(image, 0, 0, b.width, b.height, (area.width - w) / 2, (area.height - h) / 2, w, h);
    }

    private void showText(String content, String info, boolean wrapAllowed) {
        if (image != null && !image.isDisposed()) {
            image.dispose();
            image = null;
        }
        note.setText(notice == null ? info : notice + "  ·  " + info);
        text.setWordWrap(wrapAllowed && wrap.getSelection());
        text.setText(content);
        text.setTopIndex(0);
        text.setSelection(0);
        stack.topControl = text;
        body.layout();
    }

    static String[] readText(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] data = in.readNBytes(TEXT_LIMIT);
            long size = FileOps.size(p);
            TextDecoding.Decoded d = TextDecoding.decode(data);
            String truncated = size > data.length ? "  ·  showing the first " + Fmt.size(data.length) : "";
            return new String[] { d.text(), d.encoding() + truncated };
        } catch (IOException e) {
            return new String[] { "", "Can't read the file." };
        }
    }

    static String[] hexDump(Path p) {
        byte[] data;
        try (InputStream in = Files.newInputStream(p)) {
            data = in.readNBytes(HEX_LIMIT);
        } catch (IOException e) {
            return new String[] { "", "Can't read the file." };
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(data.length * 4 + 64);
        for (int offset = 0; offset < data.length; offset += 16) {
            int end = Math.min(offset + 16, data.length);
            out.append(String.format("%08x  ", offset));
            for (int i = 0; i < 16; i++) {
                if (offset + i < end) {
                    int b = data[offset + i] & 0xFF;
                    out.append(hex[b >> 4]).append(hex[b & 0xF]).append(' ');
                } else {
                    out.append("   ");
                }
                if (i == 7) out.append(' ');
            }
            out.append(" |");
            for (int i = offset; i < end; i++) {
                int b = data[i] & 0xFF;
                out.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            out.append("|\n");
        }
        long size = FileOps.size(p);
        return new String[] { out.toString(), size > data.length ? "showing the first " + Fmt.size(data.length) + " of " + Fmt.size(size) : Fmt.size(size) };
    }

    // MARK: Find

    private void showFind() {
        if (mode == Mode.PREVIEW) setMode(Mode.TEXT, true);
        Ui.setVisible(findBar, true);
        shell.layout(true, true);
        String sel = text.getSelectionText();
        if (!sel.isEmpty() && sel.length() < 200 && sel.indexOf('\n') < 0) findText.setText(sel);
        findText.setFocus();
        findText.selectAll();
    }

    private void hideFind() {
        Ui.setVisible(findBar, false);
        shell.layout(true, true);
        text.setFocus();
    }

    private void find(boolean forward) {
        String needle = findText.getText();
        if (needle.isEmpty()) {
            showFind();
            return;
        }
        String content = text.getText();
        int n = needle.length(), len = content.length();
        org.eclipse.swt.graphics.Point sel = text.getSelection();
        int found = -1;
        if (forward) {
            int from = sel.y > sel.x ? sel.x + 1 : sel.x;
            found = search(content, needle, from, len - n, 1);
            if (found < 0) found = search(content, needle, 0, Math.min(from - 1, len - n), 1);
        } else {
            int from = sel.x - 1;
            found = search(content, needle, Math.min(from, len - n), 0, -1);
            if (found < 0) found = search(content, needle, len - n, Math.max(from + 1, 0), -1);
        }
        if (found < 0) {
            findNote.setText("Not found");
            display.beep();
            return;
        }
        findNote.setText("");
        text.setSelection(found, found + n);
        text.showSelection();
    }

    private static int search(String content, String needle, int from, int to, int step) {
        int n = needle.length();
        if (step > 0) {
            for (int i = Math.max(0, from); i <= to; i++) if (content.regionMatches(true, i, needle, 0, n)) return i;
        } else {
            for (int i = from; i >= Math.max(0, to); i--) if (content.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }
}
