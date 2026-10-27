package com.k3jdr.mxfilemgr.cloud;

import com.k3jdr.mxfilemgr.workspace.Workspace;

/**
 * The cloud object store windows (Favorites and Go ▸ Amazon S3, Azure Blob Storage, Google Cloud Storage): each
 * works through the service's installed command-line tool, so the app never stores keys. One window per service.
 */
public final class CloudWindows {
    private CloudWindows() {}

    /** The AWS CLI is installed and a profile is set up (Favorites shows Amazon S3 then, as on the Mac). */
    public static boolean isS3Available() {
        return S3Backend.cli() != null && !S3Backend.profiles().isEmpty();
    }

    public static boolean isAzureInstalled() { return AzureBackend.cli() != null; }

    public static boolean isGCSInstalled() { return GcsBackend.cli() != null; }

    public static void openS3(Workspace ws) {
        RemoteBrowser.open(ws, "s3", S3Backend::new);
    }

    public static void openAzure(Workspace ws) {
        RemoteBrowser.open(ws, "azure", AzureBackend::new);
    }

    public static void openGCS(Workspace ws) {
        RemoteBrowser.open(ws, "gcs", GcsBackend::new);
    }
}
