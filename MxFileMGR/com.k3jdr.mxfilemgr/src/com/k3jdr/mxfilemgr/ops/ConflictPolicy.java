package com.k3jdr.mxfilemgr.ops;

/** What happens when an item already exists at the destination (FileOperations.swift's ConflictPolicy). */
public enum ConflictPolicy {
    ASK("Ask"), OVERWRITE("Replace"), OVERWRITE_OLDER("Replace if older"), SKIP("Skip"), KEEP_BOTH("Keep both (rename)");

    public final String title;

    ConflictPolicy(String title) { this.title = title; }

    /** The answer for one conflict. */
    public enum Decision { OVERWRITE, SKIP, KEEP_BOTH, CANCEL }
}
