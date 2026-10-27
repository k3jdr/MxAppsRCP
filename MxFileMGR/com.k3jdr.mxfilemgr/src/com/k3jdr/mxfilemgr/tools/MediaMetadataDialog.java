package com.k3jdr.mxfilemgr.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k3jdr.mx.core.ui.Background;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxfilemgr.FileHelp;
import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.TransferManager;
import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * ffmpeg Meta-data… (MediaMetadata.swift): edits the Title and Description tags of an MP4, M4V, MOV, WebM, MKV,
 * AVI, MP3 or M4A file with ffmpeg, without re-encoding:
 *
 * <pre>
 * ffmpeg -i input.mp4 -map 0 -map_metadata 0 -c copy -ignore_unknown -metadata title=… -metadata description=… output.mp4
 * </pre>
 *
 * -map 0 keeps every stream, -map_metadata 0 the other tags, -ignore_unknown skips data streams the container can't
 * take, and an empty value removes the tag. The result replaces the original once it is written (or is saved as a new
 * file). MOV gets -movflags use_metadata_tags and blanked brand tags; AVI keeps the description in the comment tag;
 * MP3 writes ID3v2.3 and keeps ID3v1. ffmpeg and ffprobe come from the PATH (winget install ffmpeg).
 */
public final class MediaMetadataDialog extends Dialog {
    /** File types the menu offers ffmpeg Meta-data… for. */
    public static final Set<String> EXTENSIONS = Set.of("mp4", "m4v", "mov", "webm", "mkv", "avi", "mp3", "m4a");

    public static boolean supports(Path file) {
        return EXTENSIONS.contains(FileOps.extension(file).toLowerCase(Locale.ROOT));
    }

    public static void open(Workspace ws, Path file) {
        new MediaMetadataDialog(ws, file).open();
    }

    static String ffmpeg() { return CLITool.find("ffmpeg"); }
    static String ffprobe() { return CLITool.find("ffprobe"); }

    /** The tag the Description is kept in: AVI has no description tag, so it uses comment (ICMT). */
    static String descriptionKey(Path p) {
        return FileOps.extension(p).equalsIgnoreCase("avi") ? "comment" : "description";
    }

    /** What ffprobe tells about the file. */
    record Info(Map<String, String> tags, String format, Double duration, List<String> streams, String descriptionKey) {
        String title() { return tags.getOrDefault("title", ""); }
        String description() { return tags.getOrDefault(descriptionKey, ""); }

        /** Tags other than title and description, for reference. */
        List<String> otherTags() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : tags.entrySet()) {
                if (!e.getKey().equals("title") && !e.getKey().equals(descriptionKey)) out.add(e.getKey() + ": " + e.getValue());
            }
            return out;
        }
    }

    /** Current tags, format, duration and streams, from ffprobe (off the UI thread). */
    static Info read(Path file) throws IOException {
        String probe = ffprobe();
        if (probe == null) throw new IOException("ffprobe isn't installed. Install it with: winget install ffmpeg");
        String text = new String(CLITool.run(probe, List.of("-v", "error", "-print_format", "json", "-show_entries",
                "format=format_name,duration:format_tags:stream=codec_type,codec_name", file.toString()), null), StandardCharsets.UTF_8);
        JsonObject json = text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        JsonObject format = json.has("format") ? json.getAsJsonObject("format") : new JsonObject();
        Map<String, String> tags = new TreeMap<>();
        if (format.has("tags")) {
            for (Map.Entry<String, JsonElement> e : format.getAsJsonObject("tags").entrySet()) {
                tags.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
            }
        }
        Double duration = null;
        if (format.has("duration")) {
            try {
                duration = Double.parseDouble(format.get("duration").getAsString());
            } catch (NumberFormatException ignored) {
                // no duration
            }
        }
        List<String> streams = new ArrayList<>();
        if (json.has("streams")) {
            for (JsonElement e : json.getAsJsonArray("streams")) {
                JsonObject s = e.getAsJsonObject();
                streams.add((s.has("codec_type") ? s.get("codec_type").getAsString() : "?") + " " + (s.has("codec_name") ? s.get("codec_name").getAsString() : "?"));
            }
        }
        return new Info(tags, format.has("format_name") ? format.get("format_name").getAsString() : "", duration, streams, descriptionKey(file));
    }

    /** Extra options per container: QuickTime keeps the description, MP3 keeps its tag versions. */
    static List<String> containerOptions(Path p) {
        return switch (FileOps.extension(p).toLowerCase(Locale.ROOT)) {
            case "mov" -> List.of("-movflags", "use_metadata_tags", "-metadata", "major_brand=", "-metadata", "minor_version=",
                    "-metadata", "compatible_brands=");
            case "mp3" -> List.of("-id3v2_version", "3", "-write_id3v1", "1");
            default -> List.of();
        };
    }

    /** The note under the fields: where this format keeps the description. */
    static String fieldNote(Path p) {
        return switch (FileOps.extension(p).toLowerCase(Locale.ROOT)) {
            case "avi" -> "AVI has no description tag, so Description is stored as the comment (ICMT). An empty field removes that tag.";
            case "mp3" -> "MP3 has no standard description: it is stored as a custom ID3 frame (TXXX:description), which music players don't show. An empty field removes that tag.";
            default -> "An empty field removes that tag.";
        };
    }

    static List<String> arguments(Path input, Path output, String title, String description, boolean progress) {
        List<String> args = new ArrayList<>(List.of("-nostdin", "-v", "error", "-y", "-i", input.toString(),
                "-map", "0", "-map_metadata", "0", "-c", "copy", "-ignore_unknown"));
        args.addAll(containerOptions(input));
        args.addAll(List.of("-metadata", "title=" + title, "-metadata", descriptionKey(input) + "=" + description));
        if (progress) args.addAll(List.of("-progress", "pipe:1", "-nostats"));
        args.add(output.toString());
        return args;
    }

    /** The command as you would type it in a Command Prompt (shown in the dialog). */
    static String commandLine(Path input, Path output, String title, String description) {
        String extra = String.join(" ", containerOptions(input));
        return "ffmpeg -i " + q(FileOps.name(input)) + " -map 0 -map_metadata 0 -c copy -ignore_unknown ^\n"
                + (extra.isEmpty() ? "" : "  " + extra + " ^\n")
                + "  -metadata " + q("title=" + title) + " ^\n  -metadata " + q(descriptionKey(input) + "=" + description) + " ^\n  "
                + q(FileOps.name(output));
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\\\"").replace("\r\n", " ").replace("\n", " ") + "\"";
    }

    /** Writes the tags as a transfer-panel job; {@code destination} null replaces the original. */
    static void write(Path source, String title, String description, Path destination, Double duration) {
        String ffmpeg = ffmpeg();
        if (ffmpeg == null) return;
        boolean replace = destination == null;
        String name = FileOps.name(source);
        String ext = FileOps.extension(source);
        String stem = name.length() > ext.length() + 1 && !ext.isEmpty() ? name.substring(0, name.length() - ext.length() - 1) : name;
        Path output = destination != null ? destination
                : source.resolveSibling("." + stem + ".mxmeta-" + UUID.randomUUID().toString().substring(0, 8) + (ext.isEmpty() ? "" : "." + ext));
        List<String> args = arguments(source, output, title, description, true);
        TransferManager.get().start("Writing meta-data to “" + name + "”", reporter -> {
            reporter.begin(name);
            boolean ok = CLITool.runJob(ffmpeg, args, null, reporter, line -> {
                if (line.startsWith("out_time_us=") && duration != null && duration > 0) {
                    try {
                        long us = Long.parseLong(line.substring("out_time_us=".length()).trim());
                        reporter.progress(Math.max(0, us), (long) (duration * 1_000_000), name);
                    } catch (NumberFormatException ignored) {
                        // "N/A" at the start
                    }
                }
            });
            if (!ok || reporter.isCancelled()) {
                try {
                    Files.deleteIfExists(output);      // only our own output: the temporary or the new file
                } catch (IOException ignored) {
                    // stays behind
                }
                return;
            }
            if (replace) {
                try {
                    Files.move(output, source, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    reporter.error(name + ": " + TransferManager.message(e));
                    try {
                        Files.deleteIfExists(output);
                    } catch (IOException ignored) {
                        // stays behind
                    }
                }
            }
        });
    }

    // MARK: The dialog

    private final Workspace ws;
    private final Path url;
    private Info info;
    private String loadError;
    private Label summary, state, newNameProblem;
    private Text title, description, newName, others, command;
    private Button saveAsNew, otherToggle, commandToggle;
    private Composite newNameRow;
    private Label replaceNote;

    private MediaMetadataDialog(Workspace ws, Path url) {
        super(ws.shell());
        this.ws = ws;
        this.url = url;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("ffmpeg Meta-data — " + FileOps.name(url));
        QuickHelpItem screen = new QuickHelpItem("ffmpeg Meta-data — " + FileOps.name(url), FileHelp.MEDIA_METADATA_DETAIL);
        shell.addListener(SWT.Activate, e -> QuickHelp.get().setScreen(() -> screen));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 14, 8));
        Label head = Ui.label(area, "ffmpeg Meta-data", Ui.subheading());
        head.setLayoutData(Ui.span(2));
        Label file = Ui.label(area, FileOps.name(url));
        file.setForeground(Ui.SECONDARY);
        file.setLayoutData(Ui.span(2));
        summary = Ui.label(area, FileOps.extension(url).toUpperCase(Locale.ROOT) + " · " + Fmt.size(FileOps.size(url)));
        summary.setForeground(Ui.SECONDARY);
        summary.setLayoutData(Ui.span(2));
        QuickHelp.help(summary, "File", "The container, its streams (as ffprobe names them), the duration and the size.");

        state = new Label(area, SWT.WRAP);
        GridData sg = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        sg.widthHint = 480;
        state.setLayoutData(sg);
        QuickHelp.help(state, "Status", "Whether ffmpeg and ffprobe are installed and the current tags could be read.");

        Ui.label(area, "Title:");
        title = new Text(area, SWT.BORDER);
        title.setMessage("e.g. Market Review 2026");
        title.setLayoutData(Ui.fillH());
        QuickHelp.help(title, "Title", "The title tag. An empty field removes it.");
        Label dl = Ui.label(area, "Description:");
        dl.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        description = new Text(area, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData dg = new GridData(SWT.FILL, SWT.FILL, true, true);
        dg.heightHint = 80;
        dg.widthHint = 420;
        description.setLayoutData(dg);
        QuickHelp.help(description, "Description", fieldNote(url));
        new Label(area, SWT.NONE);
        Label note = new Label(area, SWT.WRAP);
        note.setText(fieldNote(url));
        note.setForeground(Ui.SECONDARY);
        GridData ng = new GridData(SWT.FILL, SWT.TOP, true, false);
        ng.widthHint = 400;
        note.setLayoutData(ng);

        saveAsNew = new Button(area, SWT.CHECK);
        saveAsNew.setText("Save as a new file");
        saveAsNew.setLayoutData(Ui.span(2));
        QuickHelp.help(saveAsNew, "Save as a New File", "Writes the tagged copy under a new name next to the original, which stays as it is.");
        newNameRow = new Composite(area, SWT.NONE);
        newNameRow.setLayout(Ui.grid(1, 0, 4));
        newNameRow.setLayoutData(Ui.span(2));
        newName = new Text(newNameRow, SWT.BORDER);
        newName.setLayoutData(Ui.fillH());
        QuickHelp.help(newName, "New File Name", "The new file's name; it keeps the ." + FileOps.extension(url) + " extension.");
        newNameProblem = Ui.label(newNameRow, "");
        newNameProblem.setForeground(Ui.RED);
        newNameProblem.setLayoutData(Ui.fillH());
        replaceNote = Ui.label(area, "The original is replaced once ffmpeg has written the new copy; nothing is re-encoded.");
        replaceNote.setForeground(Ui.SECONDARY);
        replaceNote.setLayoutData(Ui.span(2));

        otherToggle = new Button(area, SWT.TOGGLE);
        otherToggle.setText("Other tags (kept)");
        otherToggle.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        QuickHelp.help(otherToggle, "Other Tags", "The file's other tags, which the new copy keeps as they are.");
        others = new Text(area, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        others.setFont(Ui.mono());
        GridData og = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        og.heightHint = 70;
        others.setLayoutData(og);

        commandToggle = new Button(area, SWT.TOGGLE);
        commandToggle.setText("Command");
        commandToggle.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        QuickHelp.help(commandToggle, "Command", "Shows the equivalent ffmpeg command, to copy into a Command Prompt.");
        command = new Text(area, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
        command.setFont(Ui.mono());
        GridData cg = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        cg.heightHint = 90;
        cg.widthHint = 480;
        command.setLayoutData(cg);
        QuickHelp.help(command, "Command", "The ffmpeg command Apply runs (without its progress options). Select and copy it with Ctrl+C.");

        Ui.setVisible(others, false);
        Ui.setVisible(command, false);
        otherToggle.addListener(SWT.Selection, e -> toggle(others, otherToggle.getSelection()));
        commandToggle.addListener(SWT.Selection, e -> toggle(command, commandToggle.getSelection()));
        saveAsNew.addListener(SWT.Selection, e -> {
            if (saveAsNew.getSelection() && newName.getText().isEmpty()) {
                String ext = FileOps.extension(url);
                String n = FileOps.name(url);
                String stem = ext.isEmpty() ? n : n.substring(0, n.length() - ext.length() - 1);
                newName.setText(FileOps.name(FileOps.available(url.getParent(), stem + " (edited)", ext)));
            }
            update();
        });
        for (Text t : new Text[] { title, description, newName }) t.addListener(SWT.Modify, e -> update());

        title.setEnabled(false);
        description.setEnabled(false);
        if (ffmpeg() == null) {
            state.setForeground(Ui.ORANGE);
            state.setText("ffmpeg isn't installed. Install it with: winget install ffmpeg");
        } else {
            state.setForeground(Ui.SECONDARY);
            state.setText("Reading the current tags…");
            Background.run(area, () -> read(url), this::loaded, err -> {
                loadError = TransferManager.message(err);
                state.setForeground(Ui.RED);
                state.setText(loadError);
                update();
            });
        }
        return area;
    }

    private void toggle(Control c, boolean show) {
        Ui.setVisible(c, show);
        relayout();
    }

    private void relayout() {
        Shell s = getShell();
        Point now = s.getSize();
        Point want = s.computeSize(now.x, SWT.DEFAULT);
        s.setSize(now.x, Math.max(now.y, want.y));
        s.layout(true, true);
    }

    private void loaded(Info read) {
        info = read;
        title.setText(read.title());
        description.setText(read.description());
        title.setEnabled(true);
        description.setEnabled(true);
        state.setText("");
        Ui.setVisible(state, false);
        List<String> parts = new ArrayList<>();
        parts.add(FileOps.extension(url).toUpperCase(Locale.ROOT));
        if (!read.streams().isEmpty()) parts.add(String.join(", ", read.streams()));
        if (read.duration() != null) {
            long s = Math.round(read.duration());
            parts.add(s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60));
        }
        parts.add(Fmt.size(FileOps.size(url)));
        summary.setText(String.join(" · ", parts));
        List<String> other = read.otherTags();
        others.setText(String.join("\r\n", other));
        otherToggle.setEnabled(!other.isEmpty());
        if (other.isEmpty()) otherToggle.setText("Other tags (none)");
        update();
        relayout();
    }

    private Path output() {
        return saveAsNew.getSelection() ? url.resolveSibling(newName.getText().trim()) : url;
    }

    private String nameProblem() {
        if (!saveAsNew.getSelection()) return null;
        String n = newName.getText().trim();
        if (n.isEmpty()) return "Enter a file name.";
        String p = FileOps.nameProblem(n);
        if (p != null) return p;
        if (!FileOps.extension(Path.of(n)).equalsIgnoreCase(FileOps.extension(url))) return "Keep the ." + FileOps.extension(url) + " extension.";
        if (FileOps.exists(output())) return "“" + n + "” already exists.";
        return null;
    }

    private boolean changed() {
        if (info == null) return false;
        return saveAsNew.getSelection() || !title.getText().equals(info.title())
                || !normalize(description.getText()).equals(normalize(info.description()));
    }

    private static String normalize(String s) {
        return s.replace("\r", "");
    }

    private void update() {
        if (title == null || title.isDisposed()) return;
        boolean asNew = saveAsNew.getSelection();
        Ui.setVisible(newNameRow, asNew);
        Ui.setVisible(replaceNote, !asNew);
        String problem = nameProblem();
        newNameProblem.setText(problem == null ? "" : problem);
        command.setText(commandLine(url, output(), title.getText(), description.getText()));
        Button apply = getButton(IDialogConstants.OK_ID);
        if (apply != null) apply.setEnabled(info != null && ffmpeg() != null && problem == null && changed());
        newNameRow.getParent().layout(true, true);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button apply = createButton(parent, IDialogConstants.OK_ID, "Apply", true);
        QuickHelp.help(apply, "Apply", "Runs ffmpeg in the transfer panel: the streams are copied without re-encoding and the original is replaced once the copy is written (or the new file is saved).");
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        update();
    }

    @Override
    protected void okPressed() {
        if (info == null || ffmpeg() == null || nameProblem() != null) return;
        Path destination = saveAsNew.getSelection() ? output() : null;
        String t = title.getText(), d = description.getText().replace("\r\n", "\n");
        write(url, t, d, destination, info.duration());
        if (destination != null && ws.activeTab().url() != null && destination.getParent().equals(ws.activeTab().url())) {
            ws.activeTab().focus(destination);
        }
        super.okPressed();
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 580), p.y);
    }
}
