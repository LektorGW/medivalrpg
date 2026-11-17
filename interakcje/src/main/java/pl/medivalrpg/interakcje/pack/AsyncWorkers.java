// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.pack;

import java.util.HashMap;
import java.util.Map;

/**
 * Async work of the resource pack module that has to be finished before {@code onDisable} returns.
 *
 * <p>Paper closes the plugin's class loader right after {@code onDisable}. A task still running on the async
 * scheduler then fails on the next class it loads ("The plugin classloader for Interakcje has thrown a zip file
 * error" / "zip file closed"), and Paper nags about "not properly shutting down its async tasks". So every async
 * task runs through {@link #run}, and {@link #close} stops new work, interrupts the interruptible tasks (download
 * check: sleeps and HTTP requests react to an interrupt) and waits a bounded time until all running ones end.
 * Anything a task throws after {@link #close} is dropped (the plugin is stopping, nobody needs that error).
 *
 * <p>Plain Java (no Bukkit), unit-tested.
 */
public final class AsyncWorkers {
    /** Running task threads -> may be interrupted. Guarded by {@code this}. */
    private final Map<Thread, Boolean> running = new HashMap<>();
    private volatile boolean closed;

    /** {@link #close} was called: no new work, results are not wanted any more. */
    public boolean closed() {
        return closed;
    }

    /**
     * Runs {@code body} on the current thread, unless already closed.
     *
     * @param interruptible {@link #close} may interrupt the thread (only for work that handles it: sleeping,
     *                      waiting for HTTP; never for file writes)
     */
    public void run(boolean interruptible, Runnable body) {
        Thread self = Thread.currentThread();
        synchronized (this) {
            if (closed) {
                return;
            }
            if (running.containsKey(self)) {
                body.run(); // nested call: the outer one tracks the thread
                return;
            }
            running.put(self, interruptible);
        }
        try {
            body.run();
        } catch (Throwable t) {
            if (!closed) {
                throw t;
            }
            // stopping: e.g. a class that cannot be loaded any more, a refused scheduler call – ignore
        } finally {
            synchronized (this) {
                running.remove(self);
                if (closed && interruptible) {
                    Thread.interrupted(); // our interrupt must not leak into the next task of a pooled thread
                }
                notifyAll();
            }
        }
    }

    /** Number of tasks running right now. */
    public synchronized int active() {
        return running.size();
    }

    /**
     * Stops new work, interrupts the interruptible tasks and waits until all running tasks end.
     *
     * @param timeoutMillis longest wait (main thread during server stop: keep it short)
     * @return tasks still running after the wait (0 = clean stop)
     */
    public int close(long timeoutMillis) {
        long deadline = System.nanoTime() + Math.max(0, timeoutMillis) * 1_000_000L;
        synchronized (this) {
            closed = true;
            running.forEach((thread, interruptible) -> {
                if (interruptible) {
                    thread.interrupt();
                }
            });
            while (!running.isEmpty()) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                try {
                    wait(leftMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return running.size();
        }
    }
}
