// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.storage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One background thread for every file write of the plugin, so writes never block the main thread and are
 * applied in the order they were requested (an older snapshot can never overwrite a newer one).
 */
public final class IoExecutor {

    /** A write that may fail with an I/O error. */
    @FunctionalInterface
    public interface Write {
        void run() throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Nowosci-IO");
        t.setDaemon(true);
        return t;
    });
    private final Logger logger;

    public IoExecutor(final Logger logger) {
        this.logger = logger;
    }

    /** Runs {@code write} in the background; {@code onFailure} (may be null) runs on the I/O thread. */
    public void submit(final String what, final Write write, final Runnable onFailure) {
        try {
            this.executor.execute(() -> this.run(what, write, onFailure));
        } catch (final RejectedExecutionException e) { // after shutdown (plugin disabling): write right away
            this.run(what, write, null);
        }
    }

    private void run(final String what, final Write write, final Runnable onFailure) {
        try {
            write.run();
        } catch (final Exception e) {
            this.logger.log(Level.SEVERE, "Nie udało się zapisać: " + what, e);
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }

    /** Waits for pending writes (plugin disable). */
    public void shutdown() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                this.logger.warning("Zapis plików trwa zbyt długo — przerwano oczekiwanie.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
