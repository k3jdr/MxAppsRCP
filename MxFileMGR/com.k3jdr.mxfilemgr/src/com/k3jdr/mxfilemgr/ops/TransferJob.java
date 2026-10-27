package com.k3jdr.mxfilemgr.ops;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One background job in the transfer panel (FileOperations.swift's TransferJob): a copy, move, delete, archive,
 * cloud transfer or ffmpeg run, with its progress, Stop, and the problems it met. Written by its worker thread,
 * read by the panel's timer.
 */
public final class TransferJob {
    public enum State { RUNNING, FINISHED, CANCELLED }

    public final String title;
    volatile String detail = "";
    /** 0…1, or negative while the size is unknown (an indeterminate bar). */
    volatile double fraction = -1;
    volatile State state = State.RUNNING;
    final List<String> errors = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    public TransferJob(String title) { this.title = title; }

    public String detail() { return detail; }
    public double fraction() { return fraction; }
    public State state() { return state; }
    public List<String> errors() { return errors; }

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }
}
