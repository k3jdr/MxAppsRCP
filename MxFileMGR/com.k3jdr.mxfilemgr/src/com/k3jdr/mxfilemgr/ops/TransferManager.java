package com.k3jdr.mxfilemgr.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;

import com.k3jdr.mxfilemgr.model.FileOps;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.win.ShellInfo;

/**
 * The background jobs shown in the transfer panel (FileOperations.swift's TransferManager): copies and moves,
 * the Recycle Bin, deletes, archives, and any job a tool starts with {@link #start}. Each job runs on its own
 * thread; when it ends the panes re-list, and a job without problems leaves the panel after two seconds.
 */
public final class TransferManager {
    private static final TransferManager INSTANCE = new TransferManager();

    public static TransferManager get() { return INSTANCE; }

    private final List<TransferJob> jobs = new CopyOnWriteArrayList<>();
    private final List<Runnable> jobListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> filesChangedListeners = new CopyOnWriteArrayList<>();

    public List<TransferJob> jobs() { return List.copyOf(jobs); }

    /** Told (on the UI thread) when a job is added or removed. */
    public void addJobListener(Runnable r) { jobListeners.add(r); }

    /** Told (on the UI thread) when a job ends, so the panes re-list. */
    public void addFilesChangedListener(Runnable r) { filesChangedListeners.add(r); }

    /** Tells the panes that files changed (also used by tools that rename or write files themselves). */
    public void filesChanged() {
        ui(() -> filesChangedListeners.forEach(Runnable::run));
    }

    /** Runs {@code work} in the background as a job shown in the transfer panel. */
    public TransferJob start(String title, Consumer<ProgressReporter> work) {
        TransferJob job = new TransferJob(title);
        ProgressReporter reporter = new ProgressReporter(job);
        jobs.add(job);
        ui(() -> jobListeners.forEach(Runnable::run));
        Thread t = new Thread(() -> {
            try {
                work.accept(reporter);
            } catch (Throwable e) {
                reporter.error(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                finish(job);
            }
        }, "MxFileMGR job: " + title);
        t.setDaemon(true);
        t.start();
        return job;
    }

    private void finish(TransferJob job) {
        job.state = job.isCancelled() ? TransferJob.State.CANCELLED : TransferJob.State.FINISHED;
        job.fraction = 1;
        ui(() -> {
            filesChangedListeners.forEach(Runnable::run);
            jobListeners.forEach(Runnable::run);
            if (job.errors.isEmpty()) {
                Display.getDefault().timerExec(2000, () -> dismiss(job));
            }
        });
    }

    public void dismiss(TransferJob job) {
        if (job.state == TransferJob.State.RUNNING) return;
        if (jobs.remove(job)) jobListeners.forEach(Runnable::run);
    }

    public void clearFinished() {
        jobs.removeIf(j -> j.state != TransferJob.State.RUNNING);
        jobListeners.forEach(Runnable::run);
    }

    private static void ui(Runnable r) {
        Display d = Display.getDefault();
        if (d.isDisposed()) return;
        d.asyncExec(() -> {
            if (!d.isDisposed()) r.run();
        });
    }

    // MARK: Job types

    /** Copies (or moves) each source to its full destination path. */
    public TransferJob copy(List<Path[]> pairs, boolean move, ConflictPolicy policy) {
        if (pairs.isEmpty()) return null;
        String verb = move ? "Moving" : "Copying";
        String title = pairs.size() == 1 ? verb + " “" + FileOps.name(pairs.get(0)[0]) + "”" : verb + " " + pairs.size() + " items";
        return start(title, reporter -> new CopyEngine(move, policy, reporter).run(pairs));
    }

    /** Moves items to the Recycle Bin. Items on drives without one were confirmed for permanent deletion by the caller. */
    public TransferJob recycle(List<Path> paths) {
        return start("Moving " + Fmt.count(paths.size(), "item") + " to the Recycle Bin", reporter -> {
            for (int i = 0; i < paths.size() && !reporter.isCancelled(); i++) {
                Path p = paths.get(i);
                reporter.step(i, paths.size(), FileOps.name(p));
                try {
                    if (ShellInfo.hasRecycleBin(p)) ShellInfo.recycle(p);
                    else CopyEngine.deleteRecursively(p, reporter);
                } catch (Exception e) {
                    reporter.error(FileOps.name(p) + ": " + message(e));
                }
            }
        });
    }

    /** Deletes items for good (Shift+Delete, after asking). */
    public TransferJob delete(List<Path> paths) {
        return start("Deleting " + Fmt.count(paths.size(), "item"), reporter -> {
            for (int i = 0; i < paths.size() && !reporter.isCancelled(); i++) {
                Path p = paths.get(i);
                reporter.step(i, paths.size(), FileOps.name(p));
                try {
                    CopyEngine.deleteRecursively(p, reporter);
                } catch (IOException e) {
                    reporter.error(FileOps.name(p) + ": " + message(e));
                }
            }
        });
    }

    /** Zips {@code items} (all in {@code folder}) into {@code archive}. */
    public TransferJob compress(List<Path> items, Path archive) {
        return start("Compressing to “" + FileOps.name(archive) + "”", reporter -> Archives.zip(items, archive, reporter));
    }

    /** Extracts each archive into its own new folder inside {@code destination}. */
    public TransferJob extract(List<Path> archives, Path destination) {
        return start("Extracting " + Fmt.count(archives.size(), "archive"), reporter -> {
            for (int i = 0; i < archives.size() && !reporter.isCancelled(); i++) {
                Path archive = archives.get(i);
                reporter.step(i, archives.size(), FileOps.name(archive));
                Path target = FileOps.available(destination, FileOps.archiveStem(archive), "");
                try {
                    Files.createDirectories(target);
                    Archives.extract(archive, target, reporter);
                } catch (IOException e) {
                    reporter.error(FileOps.name(archive) + ": " + message(e));
                }
            }
        });
    }

    public static String message(Throwable e) {
        String m = e.getMessage();
        if (e instanceof java.nio.file.AccessDeniedException) return "Access is denied.";
        if (e instanceof java.nio.file.NoSuchFileException) return "It isn't there any more.";
        if (e instanceof java.nio.file.FileAlreadyExistsException) return "An item with that name already exists.";
        if (e instanceof java.nio.file.DirectoryNotEmptyException) return "The folder isn't empty.";
        if (e instanceof java.nio.file.FileSystemException fse && fse.getReason() != null) return fse.getReason();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** Paths of a list of pairs' sources (for the totals). */
    static List<Path> sources(List<Path[]> pairs) {
        List<Path> out = new ArrayList<>();
        for (Path[] p : pairs) out.add(p[0]);
        return out;
    }
}
