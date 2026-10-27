package com.k3jdr.mxjobsmgr.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.k3jdr.mx.core.Mx;
import com.k3jdr.mxjobsmgr.model.AppleJson;
import com.k3jdr.mxjobsmgr.model.ApiSubscription;
import com.k3jdr.mxjobsmgr.model.JobNode;
import com.k3jdr.mxjobsmgr.model.Specs.Argument;
import com.k3jdr.mxjobsmgr.model.Specs.AuthStyle;
import com.k3jdr.mxjobsmgr.model.Specs.ScriptSpec;

/**
 * Runs the Python of a Layer 3 "Python Script" step (PythonRunner.swift):
 *
 * <pre>
 *   stdin   {"arguments": {name: value}, "inputs": {output name: value}, "step": title}
 *   stdout  the step's output, as JSON
 *   env     MXJOBS_API_KEY / MXJOBS_BASE_URL from the step's subscription (and the key under the subscription's
 *           key name when that is an environment-variable name, e.g. FINNHUB_TOKEN)
 * </pre>
 *
 * The script is written to %APPDATA%\MxAppsRCP\MxJobsMGR\scripts and run with the step's timeout. The interpreter is
 * the step's own, else the first python.exe on the PATH (the Microsoft Store stubs are skipped), else the py launcher.
 */
public final class PythonRunner {
    private PythonRunner() {}

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final String NONE = "";

    /** The interpreter a step uses: its own setting, else the first one found; null when there is none. */
    public static String locate(String preferred) {
        String wanted = preferred == null ? "" : preferred.trim();
        String hit = CACHE.computeIfAbsent(wanted, w -> {
            String found = find(w);
            return found == null ? NONE : found;
        });
        return hit.isEmpty() ? null : hit;
    }

    /** Forgets what was found (after the user changed an interpreter path). */
    public static void rescan() {
        CACHE.clear();
    }

    private static String find(String wanted) {
        if (!wanted.isEmpty() && !wanted.equalsIgnoreCase("python") && !wanted.equalsIgnoreCase("python3")
                && !wanted.equalsIgnoreCase("/usr/bin/python3")) {
            Path p = Path.of(wanted);
            return Files.isRegularFile(p) ? p.toString() : null;
        }
        String path = System.getenv("PATH");
        List<String> dirs = new ArrayList<>();
        if (path != null) for (String d : path.split(java.io.File.pathSeparator)) if (!d.isBlank()) dirs.add(d.trim());
        for (String exe : new String[] { "python.exe", "python3.exe" }) {
            for (String d : dirs) {
                if (d.toLowerCase().contains("\\windowsapps")) continue;   // the Store's "install Python" stubs
                Path p = Path.of(d, exe);
                if (Files.isRegularFile(p)) return p.toString();
            }
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path programs = Path.of(local, "Programs", "Python");
            if (Files.isDirectory(programs)) {
                try (var stream = Files.list(programs)) {
                    var best = stream.map(d -> d.resolve("python.exe")).filter(Files::isRegularFile)
                            .max((a, b) -> a.getParent().getFileName().toString().compareTo(b.getParent().getFileName().toString()));
                    if (best.isPresent()) return best.get().toString();
                } catch (IOException e) {
                    // look further
                }
            }
        }
        for (String d : dirs) {
            Path p = Path.of(d, "py.exe");
            if (Files.isRegularFile(p)) return p.toString();
        }
        Path windir = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "py.exe");
        return Files.isRegularFile(windir) ? windir.toString() : null;
    }

    /** Runs the step's script once and checks its expected outcome (off the UI thread). */
    public static ApiTester.Result run(JobNode node, ApiSubscription subscription, JsonObject inputs) throws InterruptedException {
        ScriptSpec spec = node.script != null ? node.script : new ScriptSpec();
        String python = locate(spec.interpreter);
        if (python == null) {
            String message = spec.interpreter.isEmpty()
                    ? "No Python found. Install Python from python.org (add it to the PATH) or set the interpreter path on the step."
                    : "Python interpreter not found: " + spec.interpreter;
            return new ApiTester.Result("", null, 0, "", List.of(), message, null);
        }
        if (spec.code.isBlank()) return new ApiTester.Result(python, null, 0, "", List.of(), "The script is empty.", null);

        // Request on stdin
        JsonObject arguments = new JsonObject();
        for (Argument arg : node.arguments) if (!arg.name.isEmpty()) arguments.addProperty(arg.name, ApiTester.substitute(arg.value, List.of()));
        JsonObject request = new JsonObject();
        request.add("arguments", arguments);
        request.add("inputs", inputs == null ? new JsonObject() : inputs);
        request.addProperty("step", node.title);
        byte[] stdin = AppleJson.compact(request).getBytes(StandardCharsets.UTF_8);

        Path folder = Mx.appData(Mx.JOBSMGR).resolve("scripts");
        Path file;
        try {
            Files.createDirectories(folder);
            file = folder.resolve("step-" + java.util.UUID.randomUUID() + ".py");
            Files.writeString(file, spec.code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ApiTester.Result(python, null, 0, "", List.of(), "Could not write the script: " + e.getMessage(), null);
        }

        List<String> command = new ArrayList<>(List.of(python));
        if (Path.of(python).getFileName().toString().equalsIgnoreCase("py.exe")) command.add("-3");
        command.add("-B");
        command.add(file.toString());
        ProcessBuilder pb = new ProcessBuilder(command).directory(folder.toFile());
        Map<String, String> env = pb.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        env.put("MXJOBS_STEP", node.title);
        if (subscription != null) {
            env.put("MXJOBS_BASE_URL", subscription.baseURL);
            if (subscription.auth != AuthStyle.NONE) {
                String key = SecureKeys.key(subscription);
                if (key != null) {
                    env.put("MXJOBS_API_KEY", key);
                    if (isEnvName(subscription.keyName)) env.put(subscription.keyName, key);
                }
            }
        }

        int timeout = Math.max(1, node.exceptions.timeoutSeconds);
        long started = System.nanoTime();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            deleteQuietly(file);
            return new ApiTester.Result(python, null, 0, "", List.of(), "Could not start " + python + ": " + e.getMessage(), null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        Thread feeder = pump(null, process.getOutputStream(), stdin);
        Thread outReader = pump(process.getInputStream(), out, null);
        Thread errReader = pump(process.getErrorStream(), err, null);
        boolean timedOut = false;
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                timedOut = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            outReader.join(2000);
            errReader.join(2000);
            feeder.join(500);
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            deleteQuietly(file);
            throw e;
        }
        double elapsed = (System.nanoTime() - started) / 1e9;
        deleteQuietly(file);
        int exit = timedOut ? -1 : process.exitValue();
        String stdout = out.toString(StandardCharsets.UTF_8), stderr = err.toString(StandardCharsets.UTF_8);
        JsonElement json = Values.parse(stdout);

        List<ApiTester.Check> checks = new ArrayList<>();
        checks.add(new ApiTester.Check("Exit code 0", exit == 0 && !timedOut));
        checks.add(new ApiTester.Check("Output is JSON", json != null));
        checks.addAll(ApiTester.outcomeChecks(node, json));

        String body = json != null ? Values.pretty(json) : stdout;
        if (!stderr.isEmpty()) body += (body.isEmpty() ? "" : "\n\n") + "stderr:\n" + (stderr.length() > 4000 ? stderr.substring(stderr.length() - 4000) : stderr);
        if (body.length() > 20_000) body = body.substring(0, 20_000) + "\n… (truncated)";

        String error = null;
        if (timedOut) {
            error = "Timed out after " + timeout + " s.";
        } else if (exit != 0) {
            String[] lines = stderr.strip().split("\n");
            error = "Python exited with code " + exit + (stderr.isBlank() ? "" : ": " + lines[lines.length - 1].strip());
        } else if (json == null) {
            error = stdout.isBlank() ? "The script printed nothing (expected JSON on stdout)." : "The script's output is not JSON.";
        }
        return new ApiTester.Result(python, exit, elapsed, body, checks, error, json);
    }

    private static Thread pump(InputStream in, OutputStream out, byte[] data) {
        Thread t = new Thread(() -> {
            try {
                if (data != null) {
                    out.write(data);
                    out.close();
                } else {
                    in.transferTo(out);
                }
            } catch (IOException e) {
                // the process ended
            }
        }, "MxJobsMGR python pipe");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // left in the scripts folder
        }
    }

    private static boolean isEnvName(String s) {
        if (s == null || s.isEmpty()) return false;
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (char c : s.toCharArray()) if (!Character.isLetterOrDigit(c) && c != '_') return false;
        return s.toUpperCase().equals(s);
    }
}
