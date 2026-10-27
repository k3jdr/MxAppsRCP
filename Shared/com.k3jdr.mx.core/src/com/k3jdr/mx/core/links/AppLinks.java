package com.k3jdr.mx.core.links;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.program.Program;

import com.k3jdr.mx.core.Mx;

/**
 * Opening another app's link, and registering this app's link schemes with Windows (the macOS Info.plist
 * CFBundleURLTypes). A scheme is registered per user, under HKEY_CURRENT_USER\Software\Classes, pointing at this
 * app's launcher, so a click on mxdatahub://… anywhere opens MxDataHUB with the link as its argument.
 */
public final class AppLinks {
    private AppLinks() {}

    /** Opens the link in its app; false when Windows has no app for the scheme. */
    public static boolean open(AppLink link) {
        return open(link.url());
    }

    public static boolean open(String url) {
        if (isRegistered(url.substring(0, Math.max(0, url.indexOf(':'))))) return Program.launch(url);
        // Not registered: start a sibling app's launcher from the same products folder, if there is one.
        var link = AppLink.parse(url);
        if (link.isEmpty()) return Program.launch(url);
        return launchSibling(link.get().appName(), url);
    }

    /** The launcher of this app (eclipse.launcher), or null when run from the IDE. */
    public static Path ownLauncher() {
        String launcher = System.getProperty("eclipse.launcher");
        return launcher == null ? null : Path.of(launcher);
    }

    /** Another app's launcher installed beside this one (…\MxDataHUB\MxDataHUB.exe), if there is one. */
    public static Path siblingLauncher(String app) {
        Path own = ownLauncher();
        if (own == null) return null;
        List<Path> candidates = new ArrayList<>();
        Path dir = own.getParent();
        if (dir != null) {
            candidates.add(dir.resolve(app + ".exe"));
            Path parent = dir.getParent();
            if (parent != null) {
                candidates.add(parent.resolve(app).resolve(app + ".exe"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static boolean launchSibling(String app, String url) {
        Path exe = siblingLauncher(app);
        if (exe == null) return false;
        try {
            new ProcessBuilder(exe.toString(), url).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether Windows knows an app for {@code scheme}. */
    public static boolean isRegistered(String scheme) {
        if (scheme == null || scheme.isEmpty()) return false;
        return run("reg", "query", "HKCU\\Software\\Classes\\" + scheme, "/ve") == 0
                || run("reg", "query", "HKCR\\" + scheme, "/ve") == 0;
    }

    /**
     * Registers {@code schemes} for this app's launcher, for the current user. Answers what happened, for the
     * status bar; nothing is changed when the app runs without a launcher (from the IDE).
     */
    public static String registerSchemes(String appName, String... schemes) {
        Path exe = ownLauncher();
        if (exe == null) return "Run " + appName + " from its launcher (" + appName + ".exe) to register its links.";
        for (String scheme : schemes) {
            String key = "HKCU\\Software\\Classes\\" + scheme;
            int rc = run("reg", "add", key, "/ve", "/d", "URL:" + appName + " link", "/f");
            rc |= run("reg", "add", key, "/v", "URL Protocol", "/d", "", "/f");
            rc |= run("reg", "add", key + "\\DefaultIcon", "/ve", "/d", "\"" + exe + "\",0", "/f");
            rc |= run("reg", "add", key + "\\shell\\open\\command", "/ve", "/d", "\"" + exe + "\" \"%1\"", "/f");
            if (rc != 0) return "Could not register " + scheme + ":// links.";
        }
        return "Registered " + String.join("://, ", schemes) + ":// links for " + exe + ".";
    }

    private static int run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** The app's launch arguments that are links of one of {@code schemes} (Windows passes the clicked link). */
    public static List<String> linkArguments(String[] args, String... schemes) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        for (String a : args) {
            for (String s : schemes) {
                if (a != null && a.toLowerCase().startsWith(s + ":")) out.add(a);
            }
        }
        return out;
    }

    /** {@link Mx#ACCTMGR} etc. for a scheme. */
    public static String appForScheme(String scheme) {
        return switch (scheme) {
            case AppLink.JOBS_SCHEME, AppLink.LEGACY_JOBS_SCHEME -> Mx.JOBSMGR;
            case AppLink.ACCT_SCHEME, AppLink.LEGACY_ACCT_SCHEME -> Mx.ACCTMGR;
            case AppLink.HUB_SCHEME -> Mx.DATAHUB;
            default -> null;
        };
    }
}
