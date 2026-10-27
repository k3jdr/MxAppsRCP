package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.Settings;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.win.ClipboardFiles;
import com.k3jdr.mxfilemgr.win.ShellInfo;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * Edit ▸ Show Clipboard (Ctrl+Shift+V, ClipboardView.swift): what's on the clipboard — copied files (with a preview
 * of the selected one, Paste into the active pane, Move Here, Show in Explorer), a picture, styled or plain text, a
 * link — and the formats underneath, kept current while the window is open (it checks Windows' clipboard sequence
 * number twice a second).
 */
public final class ClipboardWindow {
    private static ClipboardWindow instance;
    /** Text beyond this many characters is shown cut short (the clipboard keeps all of it). */
    static final int TEXT_LIMIT = 1_000_000;
    private static final Set<String> PICTURES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "ico", "tif", "tiff");

    public static void open(Workspace ws) {
        if (instance != null && !instance.shell.isDisposed()) {
            if (instance.shell.getMinimized()) instance.shell.setMinimized(false);
            instance.shell.forceActive();
            return;
        }
        instance = new ClipboardWindow(ws);
        instance.shell.open();
        instance.check(true);
        instance.poll();
    }

    private final Workspace ws;
    private final Shell shell;
    private Label summary;
    private Button clear;
    private Composite stack;
    private StackLayout stackLayout;
    private Composite emptyPage, filesPage, imagePage, textPage, linkPage, otherPage;
    private Table table;
    private SashForm sash;
    private Composite previewPane;
    private StackLayout previewStack;
    private Canvas previewCanvas;
    private Text previewText;
    private Label previewName, totals;
    private Button previewToggle, showInExplorer, moveHere, paste;
    private Canvas pictureCanvas;
    private Label pictureInfo, textInfo;
    private Text text;
    private Link link;
    private Label formatsTitle;
    private Text formats;
    private Image picture, previewImage;
    private List<Path> files = List.of();
    private String linkTarget;
    private int lastSequence = Integer.MIN_VALUE;
    private long previewGeneration;

    private ClipboardWindow(Workspace ws) {
        this.ws = ws;
        shell = new Shell(ws.display(), SWT.SHELL_TRIM);
        shell.setImages(ws.shell().getImages());
        shell.setText("Clipboard");
        build();
        Rectangle main = ws.shell().getBounds();
        shell.setBounds(main.x + main.width - 700, main.y + 80, 620, 480);
        shell.setMinimumSize(420, 300);
        shell.addListener(SWT.Activate, e -> {
            QuickHelp.get().setScreen(() -> FileHelp.CLIPBOARD);
            updateFooter();
        });
        shell.addListener(SWT.Dispose, e -> {
            if (picture != null) picture.dispose();
            if (previewImage != null) previewImage.dispose();
            if (instance == this) instance = null;
        });
    }

    // MARK: Building

    private void build() {
        shell.setLayout(Ui.grid(1, 0, 0));
        Composite top = new Composite(shell, SWT.NONE);
        top.setLayout(Ui.grid(2, 10, 6));
        top.setLayoutData(Ui.fillH());
        summary = Ui.label(top, "Clipboard is empty", Ui.bold());
        summary.setLayoutData(Ui.fillH());
        QuickHelp.help(summary, FileHelp.CLIPBOARD.title(), FileHelp.CLIPBOARD.detail(), FileHelp.CLIPBOARD.shortcut());
        clear = Ui.button(top, "Clear Clipboard", () -> {
            ClipboardFiles.clear();
            check(true);
        });
        QuickHelp.help(clear, "Clear Clipboard", "Empties the clipboard, for this app and every other.");

        stack = new Composite(shell, SWT.BORDER);
        stackLayout = new StackLayout();
        stack.setLayout(stackLayout);
        stack.setLayoutData(Ui.fill());

        emptyPage = message(stack, "Nothing on the Clipboard", "Copy something (Ctrl+C) and it shows here.");
        otherPage = message(stack, "Can't Show This Content", "The clipboard holds data in the formats listed below.");
        buildFiles();
        buildPicture();
        buildText();
        buildLink();
        stackLayout.topControl = emptyPage;

        Composite bottom = new Composite(shell, SWT.NONE);
        bottom.setLayout(Ui.grid(1, 10, 4));
        bottom.setLayoutData(Ui.fillH());
        formatsTitle = Ui.label(bottom, "Formats (0)");
        formatsTitle.setForeground(Ui.SECONDARY);
        formats = new Text(bottom, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        formats.setFont(Ui.mono());
        GridData fg = new GridData(SWT.FILL, SWT.FILL, true, false);
        fg.heightHint = 64;
        formats.setLayoutData(fg);
        QuickHelp.help(formats, "Formats", "The forms the clipboard holds this content in (clipboard formats), as the app that copied it offered them.");
        QuickHelp.help(formatsTitle, "Formats", "The forms the clipboard holds this content in (clipboard formats), as the app that copied it offered them.");
    }

    private static Composite message(Composite parent, String title, String detail) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(Ui.grid(1, 30, 8));
        Label t = Ui.label(c, title, Ui.subheading());
        t.setLayoutData(new GridData(SWT.CENTER, SWT.END, true, true));
        Label d = Ui.label(c, detail);
        d.setForeground(Ui.SECONDARY);
        d.setLayoutData(new GridData(SWT.CENTER, SWT.BEGINNING, true, true));
        QuickHelp.help(c, title, detail);
        return c;
    }

    private void buildFiles() {
        filesPage = new Composite(stack, SWT.NONE);
        filesPage.setLayout(Ui.grid(1, 0, 0));
        sash = new SashForm(filesPage, SWT.HORIZONTAL);
        sash.setLayoutData(Ui.fill());
        table = new Table(sash, SWT.MULTI | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        for (String[] c : new String[][] { { "Name", "200" }, { "Folder", "220" }, { "Size", "80" } }) {
            TableColumn col = new TableColumn(table, c[0].equals("Size") ? SWT.RIGHT : SWT.LEFT);
            col.setText(c[0]);
            col.setWidth(Integer.parseInt(c[1]));
        }
        table.addListener(SWT.Selection, e -> showPreview());
        table.addListener(SWT.DefaultSelection, e -> {
            Path p = selectedFile();
            if (p == null || !FileOps.exists(p)) return;
            if (Files.isDirectory(p)) ShellInfo.showInExplorer(p, false);
            else ViewerWindow.open(ws, p);
        });
        QuickHelp.provider(table, (c, pt) -> {
            TableItem ti = table.getItem(pt);
            if (ti != null && ti.getData() instanceof Path p) {
                boolean exists = FileOps.exists(p);
                return new com.k3jdr.mx.core.ui.QuickHelpItem(FileOps.name(p), p + (exists ? "" : " — no longer exists")
                        + ". Select it to preview it on the right; double-click a file to open it in the Viewer (F3).");
            }
            return new com.k3jdr.mx.core.ui.QuickHelpItem("Copied Items",
                    "The files and folders on the clipboard. Select one to preview it on the right; double-click a file to open it in the Viewer (F3).");
        });
        Menu menu = new Menu(table);
        table.setMenu(menu);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem old : menu.getItems()) old.dispose();
            Path p = selectedFile();
            if (p != null && FileOps.exists(p) && !Files.isDirectory(p)) {
                MenuItem view = new MenuItem(menu, SWT.PUSH);
                view.setText("Open in Viewer");
                view.addListener(SWT.Selection, x -> ViewerWindow.open(ws, p));
            }
            MenuItem show = new MenuItem(menu, SWT.PUSH);
            show.setText("Show in Explorer");
            show.setEnabled(p != null && FileOps.exists(p));
            show.addListener(SWT.Selection, x -> ShellInfo.showInExplorer(p, true));
        });

        previewPane = new Composite(sash, SWT.NONE);
        previewPane.setLayout(Ui.grid(1, 0, 0));
        Composite previewBody = new Composite(previewPane, SWT.NONE);
        previewBody.setLayoutData(Ui.fill());
        previewStack = new StackLayout();
        previewBody.setLayout(previewStack);
        previewCanvas = new Canvas(previewBody, SWT.DOUBLE_BUFFERED);
        previewCanvas.setBackground(Ui.WHITE);
        previewCanvas.addListener(SWT.Paint, e -> paintScaled(e.gc, previewCanvas, previewImage));
        previewText = new Text(previewBody, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        previewText.setFont(Ui.mono());
        previewStack.topControl = previewText;
        previewName = Ui.label(previewPane, "");
        previewName.setForeground(Ui.SECONDARY);
        previewName.setLayoutData(Ui.fillH());
        QuickHelp.help(previewCanvas, "Preview", "A preview of the selected copied picture. Double-click the item for the Viewer.");
        QuickHelp.help(previewText, "Preview", "The first lines of the selected copied file. Double-click the item for the Viewer.");
        sash.setWeights(60, 40);

        Composite footer = new Composite(filesPage, SWT.NONE);
        footer.setLayout(Ui.grid(5, 8, 6));
        footer.setLayoutData(Ui.fillH());
        previewToggle = new Button(footer, SWT.CHECK);
        previewToggle.setText("Preview");
        previewToggle.setSelection(Settings.PREFS.getBoolean("clipboardShowsPreview", true));
        previewToggle.addListener(SWT.Selection, e -> {
            Settings.PREFS.putBoolean("clipboardShowsPreview", previewToggle.getSelection());
            applyPreviewToggle();
        });
        QuickHelp.help(previewToggle, "Preview", "Shows or hides the preview of the selected item.");
        totals = Ui.label(footer, "");
        totals.setForeground(Ui.SECONDARY);
        totals.setLayoutData(Ui.fillH());
        QuickHelp.help(totals, "Copied Items", "How many items are on the clipboard, their size, and how many no longer exist.");
        showInExplorer = Ui.button(footer, "Show in Explorer", () -> {
            for (Path p : files) {
                if (FileOps.exists(p)) {
                    ShellInfo.showInExplorer(p, true);
                    return;
                }
            }
        });
        QuickHelp.help(showInExplorer, "Show in Explorer", "Opens Explorer with the first copied item selected.");
        moveHere = Ui.button(footer, "Move Here", () -> transfer(true));
        QuickHelp.help(moveHere, "Move Here", "Moves the copied items into the active pane's folder, as Ctrl+Alt+V does.", "Ctrl+Alt+V");
        paste = Ui.button(footer, "Paste", () -> transfer(false));
        QuickHelp.help(paste, "Paste", "Copies the items into the active pane's folder, as Ctrl+V in the file list does.", "Ctrl+V");
        applyPreviewToggle();
    }

    private void buildPicture() {
        imagePage = new Composite(stack, SWT.NONE);
        imagePage.setLayout(Ui.grid(1, 10, 6));
        pictureCanvas = new Canvas(imagePage, SWT.DOUBLE_BUFFERED);
        pictureCanvas.setLayoutData(Ui.fill());
        pictureCanvas.setBackground(Ui.WHITE);
        pictureCanvas.addListener(SWT.Paint, e -> paintScaled(e.gc, pictureCanvas, picture));
        pictureInfo = Ui.label(imagePage, "");
        pictureInfo.setForeground(Ui.SECONDARY);
        pictureInfo.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        QuickHelp.help(pictureCanvas, "Picture", "The picture on the clipboard, scaled to fit the window. Paste it into an app that takes pictures.");
        QuickHelp.help(pictureInfo, "Picture", "The picture's format and size in pixels.");
    }

    private void buildText() {
        textPage = new Composite(stack, SWT.NONE);
        textPage.setLayout(Ui.grid(1, 0, 0));
        text = new Text(textPage, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        text.setLayoutData(Ui.fill());
        textInfo = Ui.label(textPage, "");
        textInfo.setForeground(Ui.SECONDARY);
        GridData tg = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        tg.verticalIndent = 4;
        textInfo.setLayoutData(tg);
        QuickHelp.help(text, "Text", "The text on the clipboard; styled text (RTF, HTML) shows as its plain text. Select and copy parts of it.");
        QuickHelp.help(textInfo, "Text", "How many characters and lines the clipboard's text has.");
    }

    private void buildLink() {
        linkPage = new Composite(stack, SWT.NONE);
        linkPage.setLayout(Ui.grid(1, 30, 10));
        Label icon = Ui.label(linkPage, "🔗", Ui.heading());
        icon.setLayoutData(new GridData(SWT.CENTER, SWT.END, true, true));
        link = new Link(linkPage, SWT.WRAP | SWT.CENTER);
        GridData lg = new GridData(SWT.CENTER, SWT.BEGINNING, true, true);
        lg.widthHint = 460;
        link.setLayoutData(lg);
        link.addListener(SWT.Selection, e -> {
            if (linkTarget != null) Program.launch(linkTarget);
        });
        QuickHelp.help(link, "Link", "The web or mail link on the clipboard. Click it to open it in your browser or mail app.");
    }

    // MARK: Reading the clipboard

    private void poll() {
        shell.getDisplay().timerExec(500, () -> {
            if (shell.isDisposed()) return;
            check(false);
            poll();
        });
    }

    private void check(boolean force) {
        int seq = ClipboardFiles.sequence();
        if (!force && seq != 0 && seq == lastSequence) return;
        lastSequence = seq;
        try {
            read();
        } catch (SWTException | IllegalArgumentException e) {
            // another app holds the clipboard just now: the next check reads it
            lastSequence = Integer.MIN_VALUE;
        }
    }

    private static boolean has(TransferData[] types, Transfer t) {
        for (TransferData td : types) if (t.isSupportedType(td)) return true;
        return false;
    }

    private void read() {
        Clipboard cb = new Clipboard(shell.getDisplay());
        String[] names;
        try {
            names = cb.getAvailableTypeNames();
            TransferData[] types = cb.getAvailableTypes();
            formatsTitle.setText("Formats (" + names.length + ")");
            formats.setText(String.join("\r\n", names));
            clear.setEnabled(names.length > 0);
            if (names.length == 0) {
                show(emptyPage, "Clipboard is empty");
                return;
            }
            if (has(types, FileTransfer.getInstance()) && cb.getContents(FileTransfer.getInstance()) instanceof String[] list && list.length > 0) {
                List<Path> paths = new ArrayList<>();
                for (String n : list) paths.add(Path.of(n));
                showFiles(paths);
                return;
            }
            if (has(types, ImageTransfer.getInstance()) && cb.getContents(ImageTransfer.getInstance()) instanceof ImageData data) {
                String kind = "Picture";
                for (String n : names) {
                    String u = n.toUpperCase(Locale.ROOT);
                    if (u.contains("PNG")) { kind = "PNG"; break; }
                    if (u.startsWith("CF_DIB") || u.equals("CF_BITMAP")) kind = "Bitmap";
                }
                if (picture != null) picture.dispose();
                picture = new Image(shell.getDisplay(), data);
                pictureInfo.setText(kind + " · " + data.width + " × " + data.height);
                pictureCanvas.redraw();
                show(imagePage, "Clipboard contents: picture");
                return;
            }
            String plain = has(types, TextTransfer.getInstance()) && cb.getContents(TextTransfer.getInstance()) instanceof String s ? s : null;
            boolean styled = has(types, RTFTransfer.getInstance()) || has(types, HTMLTransfer.getInstance());
            if (styled) {
                String shown = plain;
                if (shown == null && cb.getContents(HTMLTransfer.getInstance()) instanceof String html) shown = stripHtml(html);
                if (shown == null && cb.getContents(RTFTransfer.getInstance()) instanceof String rtf) shown = stripRtf(rtf);
                if (shown != null && !shown.isEmpty()) {
                    showText(shown, "Clipboard contents: styled text");
                    return;
                }
            }
            if (plain != null) {
                String trimmed = plain.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && trimmed.chars().noneMatch(Character::isWhitespace)
                        && (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") || lower.startsWith("ftp://"))) {
                    showLink(trimmed);
                } else {
                    showText(plain, "Clipboard contents: text");
                }
                return;
            }
            if (has(types, URLTransfer.getInstance()) && cb.getContents(URLTransfer.getInstance()) instanceof String url && !url.isBlank()) {
                showLink(url.trim());
                return;
            }
            show(otherPage, "Clipboard contents: unknown");
        } finally {
            cb.dispose();
        }
    }

    private void show(Composite page, String title) {
        summary.setText(Ui.escape(title));
        stackLayout.topControl = page;
        stack.layout();
        shell.layout(true, true);
    }

    private void showText(String s, String title) {
        String shown = s.length() > TEXT_LIMIT ? s.substring(0, TEXT_LIMIT) : s;
        text.setText(shown);
        long lines = s.isEmpty() ? 1 : s.chars().filter(c -> c == '\n').count() + 1;
        String info = Fmt.count(s.length(), "character") + " · " + Fmt.count(lines, "line");
        if (s.length() > TEXT_LIMIT) info += " · showing the first " + Fmt.count(TEXT_LIMIT, "character");
        textInfo.setText(info);
        show(textPage, title);
    }

    private void showLink(String url) {
        linkTarget = url;
        link.setText("<a>" + url.replace("&", "&&").replace("<", "").replace(">", "") + "</a>");
        show(linkPage, "Clipboard contents: link");
    }

    private void showFiles(List<Path> paths) {
        files = paths;
        table.removeAll();
        for (Path p : paths) {
            TableItem ti = new TableItem(table, SWT.NONE);
            ti.setData(p);
            ti.setText(0, FileOps.name(p));
            ti.setText(1, p.getParent() == null ? "" : p.getParent().toString());
            boolean exists = FileOps.exists(p);
            ti.setText(2, !exists ? "" : Files.isDirectory(p) ? "folder" : Fmt.size(FileOps.size(p)));
            if (!exists) ti.setForeground(Ui.SECONDARY);
        }
        if (!paths.isEmpty()) table.select(0);
        updateFooter();
        showPreview();
        show(filesPage, "Clipboard contents: " + (paths.size() == 1 ? "1 item" : paths.size() + " items"));
    }

    private void updateFooter() {
        if (paste == null || paste.isDisposed()) return;
        List<Path> existing = new ArrayList<>();
        long bytes = 0;
        int fileCount = 0;
        for (Path p : files) {
            if (!FileOps.exists(p)) continue;
            existing.add(p);
            if (!Files.isDirectory(p)) {
                fileCount++;
                bytes += FileOps.size(p);
            }
        }
        String t = Fmt.count(files.size(), "item");
        if (fileCount > 0) t += ", " + Fmt.size(bytes) + " in files";
        if (existing.size() < files.size()) t += " · " + (files.size() - existing.size()) + " no longer exist";
        totals.setText(t);
        Path folder = ws.activeTab().url();
        paste.setText(Ui.escape(folder == null ? "Paste" : "Paste into “" + BrowserTab.displayName(folder) + "”"));
        paste.setEnabled(folder != null && !existing.isEmpty());
        moveHere.setEnabled(folder != null && !existing.isEmpty());
        showInExplorer.setEnabled(!existing.isEmpty());
        shell.setDefaultButton(paste);
        paste.getParent().layout(true);
    }

    private void transfer(boolean move) {
        Path folder = ws.activeTab().url();
        if (folder == null) return;
        List<Path> existing = new ArrayList<>();
        for (Path p : files) if (FileOps.exists(p)) existing.add(p);
        if (!existing.isEmpty()) ws.transfer(existing, folder, move);
    }

    private void applyPreviewToggle() {
        sash.setMaximizedControl(previewToggle.getSelection() ? null : table);
    }

    private Path selectedFile() {
        int i = table.getSelectionIndex();
        if (i < 0 && table.getItemCount() > 0) i = 0;
        return i >= 0 && table.getItem(i).getData() instanceof Path p ? p : null;
    }

    /** The selected file, else the first: a picture is drawn, a text file's first lines shown. */
    private void showPreview() {
        Path p = selectedFile();
        long gen = ++previewGeneration;
        if (p == null || !FileOps.exists(p)) {
            previewName.setText(p == null ? "" : "No preview: the item no longer exists.");
            previewText.setText("");
            previewStack.topControl = previewText;
            previewText.getParent().layout();
            return;
        }
        previewName.setText(Ui.escape(FileOps.name(p)));
        if (Files.isDirectory(p)) {
            previewText.setText("A folder. Double-click it to open it in Explorer.");
            previewStack.topControl = previewText;
            previewText.getParent().layout();
            return;
        }
        boolean isPicture = PICTURES.contains(FileOps.extension(p).toLowerCase(Locale.ROOT)) && FileOps.size(p) < 40_000_000;
        Background.run(previewPane, () -> isPicture ? (Object) loadImage(p) : (Object) firstLines(p), result -> {
            if (gen != previewGeneration) return;
            if (result instanceof ImageData data) {
                if (previewImage != null) previewImage.dispose();
                previewImage = new Image(shell.getDisplay(), data);
                previewStack.topControl = previewCanvas;
                previewCanvas.redraw();
            } else {
                previewText.setText(result instanceof String s ? s : "");
                previewStack.topControl = previewText;
            }
            previewText.getParent().layout();
        }, err -> {
            if (gen != previewGeneration) return;
            previewText.setText("No preview: " + err.getMessage());
            previewStack.topControl = previewText;
            previewText.getParent().layout();
        });
    }

    private static ImageData loadImage(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            return new ImageData(in);
        } catch (SWTException e) {
            throw new IOException("the picture can't be read");
        }
    }

    private static String firstLines(Path p) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(p)) {
            head = in.readNBytes(64 * 1024);
        }
        for (int i = 0; i < Math.min(head.length, 8192); i++) {
            if (head[i] == 0) return "A binary file: no text preview. Double-click it for the Viewer (Hex).";
        }
        String s = new String(head, StandardCharsets.UTF_8);
        String[] lines = s.split("\\R", -1);
        return lines.length > 200 ? String.join("\r\n", java.util.Arrays.copyOf(lines, 200)) : String.join("\r\n", lines);
    }

    private static void paintScaled(GC gc, Canvas canvas, Image image) {
        if (image == null || image.isDisposed()) return;
        Rectangle area = canvas.getClientArea();
        Rectangle b = image.getBounds();
        if (b.width == 0 || b.height == 0) return;
        double scale = Math.min(1.0, Math.min((area.width - 20) / (double) b.width, (area.height - 20) / (double) b.height));
        if (scale <= 0) return;
        int w = (int) (b.width * scale), h = (int) (b.height * scale);
        gc.setInterpolation(SWT.HIGH);
        gc.drawImage(image, 0, 0, b.width, b.height, area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h);
    }

    private static String stripHtml(String html) {
        String s = html;
        int start = s.indexOf("<!--StartFragment-->");
        int end = s.indexOf("<!--EndFragment-->");
        if (start >= 0 && end > start) s = s.substring(start + 20, end);
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
        return s.trim();
    }

    private static String stripRtf(String rtf) {
        String s = rtf.replaceAll("\\\\par[d]?\\b", "\n")
                .replaceAll("\\{\\\\\\*[^{}]*\\}", "")
                .replaceAll("\\\\[a-zA-Z]+-?\\d* ?", "")
                .replaceAll("[{}]", "");
        return s.trim();
    }
}
