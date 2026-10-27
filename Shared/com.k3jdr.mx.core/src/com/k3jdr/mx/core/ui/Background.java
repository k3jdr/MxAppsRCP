package com.k3jdr.mx.core.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Control;

/**
 * Work off the UI thread — database queries, HTTP calls, file copies — with the result delivered back on the UI
 * thread (SwiftUI's {@code Task { … }} with {@code @MainActor} updates).
 */
public final class Background {
    private Background() {}

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MxAppsRCP worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Runs {@code work}, then {@code done} with its result on the UI thread (skipped when {@code owner} is
     * disposed). A failure goes to {@code failed}, or to an error dialog when that is null.
     */
    public static <T> Future<?> run(Control owner, Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        return POOL.submit(() -> {
            try {
                T result = work.call();
                Ui.async(owner, () -> {
                    if (done != null) done.accept(result);
                });
            } catch (Throwable t) {
                Ui.async(owner, () -> {
                    if (failed != null) failed.accept(t);
                    else ErrorDialog.show(owner.getShell(), "Something went wrong", t);
                });
            }
        });
    }

    public static <T> Future<?> run(Control owner, Callable<T> work, Consumer<T> done) {
        return run(owner, work, done, null);
    }

    /** Runs {@code work} with no result to deliver. */
    public static Future<?> execute(Runnable work) {
        return POOL.submit(work);
    }
}
