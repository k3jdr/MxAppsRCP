package com.k3jdr.mxfilemgr.cloud;

/**
 * An object or folder in S3, Azure Blob Storage, Google Cloud Storage or on an SFTP server (S3View.swift's
 * RemoteEntry). {@code key} is the full key, blob name or remote path; folders end with "/". {@code storageClass}
 * is the S3 storage class, the Azure tier, or the SFTP permissions.
 */
public record RemoteEntry(String key, String name, boolean isFolder, long size, long modified, String storageClass) {

    /** The path without the trailing "/" folders carry in their key. */
    public String path() {
        return isFolder && key.endsWith("/") && key.length() > 1 ? key.substring(0, key.length() - 1) : key;
    }
}
