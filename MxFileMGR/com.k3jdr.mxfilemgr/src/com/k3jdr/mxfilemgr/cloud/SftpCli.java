package com.k3jdr.mxfilemgr.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.k3jdr.mxfilemgr.model.Places;
import com.k3jdr.mxfilemgr.ops.CLITool;
import com.k3jdr.mxfilemgr.ops.ProgressReporter;

/**
 * Windows' OpenSSH {@code sftp.exe} in batch mode (SFTP.swift's SFTPCLI): one connection per batch of commands,
 * signing in exactly as ssh does — keys, the ssh-agent service and .ssh\config (aliases, ports, ProxyJump). It never
 * prompts (BatchMode=yes): accounts that need a password aren't supported. New host keys are accepted the first
 * time (StrictHostKeyChecking=accept-new); a changed host key is refused.
 */
final class SftpCli {
    private SftpCli() {}

    /** Windows' own OpenSSH sftp first (it reads %USERPROFILE%\.ssh and uses the ssh-agent service), else one on the PATH. */
    static String path() {
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return CLITool.find(root + "\\System32\\OpenSSH\\sftp.exe", "sftp");
    }

    /** Host aliases from .ssh\config (no wildcards), for adding accounts quickly. */
    static List<String> configHosts() {
        List<String> hosts = new ArrayList<>();
        Path config = Places.home().resolve(".ssh").resolve("config");
        List<String> lines;
        try {
            lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return hosts;
        }
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || !parts[0].equalsIgnoreCase("host")) continue;
            for (int i = 1; i < parts.length; i++) {
                String alias = parts[i];
                if (alias.contains("*") || alias.contains("?") || alias.startsWith("!")) continue;
                if (!hosts.contains(alias)) hosts.add(alias);
            }
        }
        return hosts;
    }

    /** Quotes an argument for an sftp batch file. */
    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A local path for a batch file: forward slashes, which Windows' sftp accepts and which need no escaping. */
    static String local(Path p) {
        return quote(p.toString().replace('\\', '/'));
    }

    static List<String> arguments(SftpAccount account, Path batch) {
        List<String> args = new ArrayList<>(List.of("-o", "ConnectTimeout=15", "-o", "StrictHostKeyChecking=accept-new",
                "-o", "ServerAliveInterval=15", "-o", "BatchMode=yes"));
        if (account.port() != null && !account.port().isBlank()) args.addAll(List.of("-P", account.port().trim()));
        args.addAll(List.of("-b", batch.toString(), account.destination()));
        return args;
    }

    private static Path batchFile(List<String> commands) throws IOException {
        Path f = Files.createTempFile("mxfilemgr-sftp-", ".txt");
        Files.writeString(f, String.join("\n", commands) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    /** Runs batch commands in one connection and returns sftp's output. */
    static String run(SftpAccount account, List<String> commands) throws IOException {
        String sftp = path();
        if (sftp == null) throw new IOException("Windows' OpenSSH client (sftp.exe) isn't installed.");
        Path batch = batchFile(commands);
        try {
            return new String(CLITool.run(sftp, arguments(account, batch), null), StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(batch);
        }
    }

    static boolean runJob(SftpAccount account, List<String> commands, ProgressReporter reporter) {
        String sftp = path();
        if (sftp == null) {
            reporter.error("Windows' OpenSSH client (sftp.exe) isn't installed.");
            return false;
        }
        Path batch;
        try {
            batch = batchFile(commands);
        } catch (IOException e) {
            reporter.error("Couldn't write the sftp batch file.");
            return false;
        }
        try {
            return CLITool.runJob(sftp, arguments(account, batch), null, reporter, null);
        } finally {
            try {
                Files.deleteIfExists(batch);
            } catch (IOException ignored) {
                // a temporary file stays
            }
        }
    }

    /** "Remote working directory: /home/me" from {@code pwd}. */
    static String parsePWD(String output) {
        for (String line : output.split("\\R")) {
            int i = line.indexOf("Remote working directory: ");
            if (i >= 0) return line.substring(i + "Remote working directory: ".length()).trim();
        }
        return null;
    }

    /** Parses {@code ls -la}: "drwxr-xr-x  2 me staff  64 Oct 16 12:00 name" (or "… Sep  1  2025 name"). */
    static List<RemoteEntry> parseListing(String output, String folder) {
        List<RemoteEntry> result = new ArrayList<>();
        String base = folder.endsWith("/") ? folder : folder + "/";
        for (String raw : output.split("\\R")) {
            if (raw.startsWith("sftp>")) continue;
            String[] f = raw.trim().split("\\s+", 9);
            if (f.length != 9 || f[0].length() < 10 || "-dlbcps".indexOf(f[0].charAt(0)) < 0) continue;
            char type = f[0].charAt(0);
            String name = f[8];
            if (type == 'l') {
                int arrow = name.indexOf(" -> ");
                if (arrow >= 0) name = name.substring(0, arrow);
            }
            if (name.equals(".") || name.equals("..")) continue;
            boolean folderEntry = type == 'd';
            long size = 0;
            try {
                size = Long.parseLong(f[4]);
            } catch (NumberFormatException ignored) {
                // no size
            }
            result.add(new RemoteEntry(base + name + (folderEntry ? "/" : ""), name, folderEntry, size, parseDate(f[5], f[6], f[7]), f[0]));
        }
        return result;
    }

    static long parseDate(String month, String day, String timeOrYear) {
        try {
            if (timeOrYear.contains(":")) {
                int year = LocalDate.now().getYear();
                LocalDateTime d = LocalDateTime.parse(month + " " + day + " " + year + " " + timeOrYear,
                        DateTimeFormatter.ofPattern("MMM d yyyy HH:mm", Locale.ENGLISH));
                // ls shows the time for the last six months: a date "ahead" of now is from last year.
                if (d.isAfter(LocalDateTime.now().plusDays(1))) d = d.minusYears(1);
                return d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            LocalDate d = LocalDate.parse(month + " " + day + " " + timeOrYear, DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH));
            return d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
