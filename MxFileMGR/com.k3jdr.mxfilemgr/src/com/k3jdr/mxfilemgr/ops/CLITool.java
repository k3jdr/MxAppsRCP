package com.k3jdr.mxfilemgr.ops;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs command-line tools — the AWS, Azure and Google Cloud CLIs, sftp, ffmpeg, tar (CLITool.swift): either
 * collecting their output, or inside a transfer-panel job that Stop ends, passing each output line on (progress).
 * Tools are found on the PATH (with PATHEXT, so {@code aws} finds aws.exe and {@code az} az.cmd).
 */
public final class CLITool {
    private CLITool() {}

    /** A tool that failed: its error output, or its exit status. */
    public static final class ToolException extends IOException {
        public final int status;

        public ToolException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    /** The first of {@code names} found on the PATH (or an existing absolute path), else null. */
    public static String find(String... names) {
        String path = System.getenv("PATH");
        String pathext = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String e : pathext.split(";")) if (!e.isBlank()) exts.add(e.toLowerCase(Locale.ROOT));
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Path direct = Path.of(name);
            if (direct.isAbsolute()) {
                if (Files.isRegularFile(direct)) return direct.toString();
                continue;
            }
            if (path == null) continue;
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String ext : exts) {
                    try {
                        Path p = Path.of(dir.trim().replace("\"", ""), name + ext);
                        if (Files.isRegularFile(p) && (!ext.isEmpty() || name.contains("."))) return p.toString();
                    } catch (Exception ignored) {
                        // a malformed PATH entry
                    }
                }
            }
        }
        return null;
    }

    /** The command line for a tool: batch files (.cmd/.bat, as az.cmd and gcloud.cmd are) run through cmd.exe. */
    public static List<String> command(String tool, List<String> args) {
        List<String> cmd = new ArrayList<>();
        String lower = tool.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            cmd.add("cmd.exe");
            cmd.add("/d");
            cmd.add("/s");
            cmd.add("/c");
            StringBuilder line = new StringBuilder("\"").append(quote(tool));
            for (String a : args) line.append(' ').append(quote(a));
            cmd.add(line.append('"').toString());
            return cmd;
        }
        cmd.add(tool);
        cmd.addAll(args);
        return cmd;
    }

    private static String quote(String a) {
        if (a.isEmpty()) return "\"\"";
        if (a.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"' || c == '&' || c == '|' || c == '<' || c == '>' || c == '^')) return a;
        return "\"" + a.replace("\"", "\\\"") + "\"";
    }

    /** Runs the tool and returns its standard output; throws with its error output when it fails. */
    public static byte[] run(String tool, List<String> args, Map<String, String> env) throws IOException {
        return run(tool, args, env, null);
    }

    /** As {@link #run(String, List, Map)}, with {@code input} written to the tool's standard input. */
    public static byte[] run(String tool, List<String> args, Map<String, String> env, String input) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(tool, args));
        if (env != null) pb.environment().putAll(env);
        Process p = pb.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errReader = drain(p.getErrorStream(), err::writeBytes);
        if (input != null) {
            p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        }
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        try {
            p.waitFor();
            errReader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (p.exitValue() != 0) throw new ToolException(message(err.toByteArray(), tool, p.exitValue()), p.exitValue());
        return out;
    }

    public static String runText(String tool, List<String> args, Map<String, String> env) throws IOException {
        return new String(run(tool, args, env), StandardCharsets.UTF_8);
    }

    /**
     * Runs the tool inside a job: Stop ends it, each line of its output goes to {@code onLine} (for progress), and
     * its error output becomes the job's problem when it fails. Returns true when it succeeded.
     */
    public static boolean runJob(String tool, List<String> args, Map<String, String> env, ProgressReporter reporter,
            Consumer<String> onLine) {
        ProcessBuilder pb = new ProcessBuilder(command(tool, args));
        if (env != null) pb.environment().putAll(env);
        Process p;
        try {
            p = pb.start();
            p.getOutputStream().close();
        } catch (IOException e) {
            reporter.error(name(tool) + ": " + TransferManager.message(e));
            return false;
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = lines(p.getInputStream(), onLine, null);
        Thread errReader = lines(p.getErrorStream(), onLine, err);
        try {
            while (p.isAlive()) {
                if (reporter.isCancelled()) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
                Thread.sleep(100);
            }
            outReader.join(2000);
            errReader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        int status = p.exitValue();
        if (status != 0 && !reporter.isCancelled()) {
            reporter.error(message(err.toByteArray(), tool, status));
            return false;
        }
        return status == 0;
    }

    private static Thread drain(InputStream in, Consumer<byte[]> sink) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0;) sink.accept(java.util.Arrays.copyOf(buf, n));
            } catch (IOException ignored) {
                // the process ended
            }
        }, "tool output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Thread lines(InputStream in, Consumer<String> onLine, ByteArrayOutputStream copy) {
        return drain(in, new Consumer<>() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void accept(byte[] chunk) {
                if (copy != null) synchronized (copy) { copy.writeBytes(chunk); }
                if (onLine == null) return;
                for (char c : new String(chunk, StandardCharsets.UTF_8).toCharArray()) {
                    if (c == '\r' || c == '\n') {
                        if (!line.isEmpty()) onLine.accept(line.toString());
                        line.setLength(0);
                    } else {
                        line.append(c);
                    }
                }
            }
        });
    }

    private static String name(String tool) {
        return Path.of(tool).getFileName().toString();
    }

    private static String message(byte[] stderr, String tool, int status) {
        String text = new String(stderr, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? name(tool) + " exited with status " + status : text;
    }

    /** An ISO 8601 date from a CLI's JSON (with or without fractions); 0 when there is none. */
    public static long parseISODate(String text) {
        if (text == null || text.isBlank()) return 0;
        try {
            return OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text.trim()).toEpochMilli();
            } catch (DateTimeParseException e2) {
                return 0;
            }
        }
    }
}
