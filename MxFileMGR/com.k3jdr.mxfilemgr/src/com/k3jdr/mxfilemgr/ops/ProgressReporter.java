package com.k3jdr.mxfilemgr.ops;

/**
 * Progress reporting from a job's worker thread (FileOperations.swift's ProgressReporter): total bytes, the file
 * being worked on and the bytes done so far, absolute progress from a command-line tool, steps, and errors.
 */
public final class ProgressReporter {
    private final TransferJob job;
    private long total, done, current;

    ProgressReporter(TransferJob job) { this.job = job; }

    public TransferJob job() { return job; }

    public boolean isCancelled() { return job.isCancelled(); }

    public void cancel() { job.cancel(); }

    public synchronized void setTotal(long bytes) {
        total = bytes;
        post();
    }

    public synchronized void begin(String name) {
        current = 0;
        job.detail = name;
        post();
    }

    public synchronized void fileProgress(long bytes) {
        current = bytes;
        post();
    }

    public synchronized void add(long bytes) {
        done += bytes;
        current = 0;
        post();
    }

    /** Absolute progress reported by an external tool (the AWS CLI, ffmpeg). */
    public synchronized void progress(long doneBytes, long totalBytes, String detail) {
        total = totalBytes;
        done = doneBytes;
        current = 0;
        if (detail != null) job.detail = detail;
        post();
    }

    public synchronized void step(int index, int count, String name) {
        total = count;
        done = index;
        current = 0;
        job.detail = name;
        post();
    }

    public void detail(String text) { job.detail = text; }

    public void error(String message) {
        if (message != null && !message.isBlank()) job.errors.add(message.trim());
    }

    private void post() {
        job.fraction = total > 0 ? Math.min(1.0, (double) (done + current) / total) : -1;
    }
}
