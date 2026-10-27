package com.k3jdr.mx.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The MxAppsRCP family: the six apps, where they keep their files, and where the MxAppsIOS repository (whose
 * Layer 3 services, SRM migrations and samples the RCP apps share) is on this PC.
 */
public final class Mx {
    private Mx() {}

    public static final String DATASQL = "MxDataSQL";
    public static final String DATAHUB = "MxDataHUB";
    public static final String FILEMGR = "MxFileMGR";
    public static final String JOBSMGR = "MxJobsMGR";
    public static final String ACCTMGR = "MxAcctMGR";
    public static final String NOTEONE = "MxNoteONE";

    /** Shown in the status bars and About boxes. */
    public static final String FAMILY = "MxAppsRCP";

    /** %APPDATA%\MxAppsRCP — every app's own folder is under it (the -data instance area, settings, caches). */
    public static Path dataRoot() {
        String appData = System.getenv("APPDATA");
        Path root = appData != null ? Path.of(appData, FAMILY) : Path.of(System.getProperty("user.home"), "." + FAMILY);
        try {
            Files.createDirectories(root);
        } catch (Exception ignored) {
            // the caller reports the failure when it writes there
        }
        return root;
    }

    /** %APPDATA%\MxAppsRCP\<app>. */
    public static Path appData(String app) {
        Path dir = dataRoot().resolve(app);
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
            // the caller reports the failure when it writes there
        }
        return dir;
    }

    /**
     * The MxAppsIOS checkout holding Services/, MxDataHUB/SRM and MxJobsMGR/Samples: the system property or
     * environment variable MXAPPSIOS_HOME, else D:\MyCodeGIT\MxAppsIOS, else ~/MyCodeGIT/MxAppsIOS.
     */
    public static Path iosRepository() {
        String configured = System.getProperty("mxappsios.home", System.getenv("MXAPPSIOS_HOME"));
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path d = Path.of("D:\\MyCodeGIT\\MxAppsIOS");
        if (Files.isDirectory(d)) return d;
        return Path.of(System.getProperty("user.home"), "MyCodeGIT", "MxAppsIOS");
    }
}
