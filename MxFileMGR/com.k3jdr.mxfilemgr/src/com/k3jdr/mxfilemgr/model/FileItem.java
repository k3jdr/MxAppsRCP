package com.k3jdr.mxfilemgr.model;

import java.nio.file.Path;

/**
 * One entry of a folder listing (FileModels.swift's FileItem). {@code isDirectory} is a folder you can enter,
 * including a symbolic link or junction to one; {@code isLink} marks symbolic links and junctions (shown in
 * italics); {@code isHidden} items are dimmed. In "This PC" the entries are drives ({@code isDrive}), their size
 * the drive's capacity.
 */
public record FileItem(Path path, String name, boolean isDirectory, boolean isLink, boolean isHidden, boolean isSystem,
        boolean isOnlineOnly, long size, long modified, long created, int attributes, String kind, boolean isDrive,
        long free) {

    /** The extension without the dot ("" for folders and names like ".gitignore"). */
    public String ext() {
        if (isDirectory || isDrive) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }

    public boolean isReadOnly() { return (attributes & 0x1) != 0; }
}
