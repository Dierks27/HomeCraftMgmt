package com.dierks.homecraft.games.gen.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executor;

/**
 * The one thread plans are made on (GEN-SPEC §3.3 step 1): a daemon called
 * {@value #NAME}, at the lowest priority, so a golf course's few seconds of simulated putts never
 * compete with the server's tick. A plan never touches the world, the database or live config:
 * everything it needs is copied into its {@code PlanInput} on the main thread first, and its
 * result is handed back through the engine's inbox, which the main thread drains.
 *
 * <p>A plan that runs past the engine's safety kill is cancelled through its input; one that
 * ignores that is left behind on a thread of its own ({@link #restart}), so the next plan never
 * waits for it.
 */
public final class PlannerThread implements Executor {

    /** The thread's name. */
    public static final String NAME = "hcm-gen-planner";

    private volatile ExecutorService exec = create();

    private static ExecutorService create() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, NAME);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @Override
    public void execute(Runnable task) {
        try {
            exec.execute(task);
        } catch (RejectedExecutionException e) {
            // shut down: the engine is stopping, and its inbox is ignored anyway
        }
    }

    /** Give up on whatever is running (interrupting it) and start a fresh thread for what comes next. */
    public synchronized void restart() {
        exec.shutdownNow();
        exec = create();
    }

    /** Stop for good (the game stopped). */
    public synchronized void shutdown() {
        exec.shutdownNow();
    }
}
