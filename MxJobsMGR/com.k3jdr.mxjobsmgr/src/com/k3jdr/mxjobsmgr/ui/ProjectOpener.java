package com.k3jdr.mxjobsmgr.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.swt.program.Program;

import com.k3jdr.mx.core.Mx;
import com.k3jdr.mxjobsmgr.model.Specs.IdeChoice;

/**
 * Open Project in … for a subscription's source project (ProjectOpener.swift). VS Code and IntelliJ IDEA through their
 * URL schemes, CLion through its launcher on the PATH, Explorer for "Finder". Mac paths in a jobspace from the Mac
 * (~/MyCodeGIT/MxAppsIOS/Services/…) are found in this PC's MxAppsIOS checkout.
 */
public final class ProjectOpener {
    private ProjectOpener() {}

    /** The folder on this PC: ~ expanded, and a Mac path into MxAppsIOS mapped to the checkout here. */
    public static Path expand(String folder) {
        String p = folder == null ? "" : folder.trim();
        int ios = p.replace('\\', '/').indexOf("/MxAppsIOS/");
        if (ios >= 0 && !Files.isDirectory(Path.of(p.startsWith("~") ? System.getProperty("user.home") + p.substring(1) : p))) {
            String rest = p.replace('\\', '/').substring(ios + "/MxAppsIOS/".length());
            return Mx.iosRepository().resolve(rest.replace('/', File.separatorChar));
        }
        if (p.equals("~")) return Path.of(System.getProperty("user.home"));
        if (p.startsWith("~/") || p.startsWith("~\\")) return Path.of(System.getProperty("user.home"), p.substring(2));
        return Path.of(p);
    }

    /** Opens the folder in the IDE; an error message, or null once it was handed over. */
    public static String open(String folder, IdeChoice ide) {
        if (folder == null || folder.isBlank()) return "No project folder set.";
        Path path = expand(folder);
        if (!Files.isDirectory(path)) return "The project folder isn't on this PC: " + path;
        String forward = path.toString().replace('\\', '/');
        switch (ide) {
            case VSCODE:
                return Program.launch("vscode://file/" + forward + "/") ? null : "Visual Studio Code did not accept the request (is it installed?).";
            case INTELLIJ:
                return Program.launch("idea://open?file=" + forward) ? null : "IntelliJ IDEA did not accept the request (is it installed?).";
            case CLION:
                for (String exe : new String[] { "clion64.exe", "clion.exe", "clion.cmd", "clion" }) {
                    try {
                        new ProcessBuilder(exe, path.toString()).start();
                        return null;
                    } catch (Exception e) {
                        // try the next name
                    }
                }
                return "CLion isn't on the PATH: add its bin folder (or the JetBrains Toolbox scripts) to the PATH.";
            case XCODE:
                return "Xcode runs on macOS only: choose another IDE for this PC.";
            case FINDER:
            default:
                return Program.launch(path.toString()) ? null : "Explorer could not open " + path + ".";
        }
    }
}
