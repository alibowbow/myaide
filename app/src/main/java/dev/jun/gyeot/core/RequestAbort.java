package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A stuck disconnect must not block the UI or create a thread for every click. */
public final class RequestAbort {
    private RequestAbort() {}
    private static final ThreadPoolExecutor CLOSE = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(32), runnable -> {
                Thread thread = new Thread(runnable, "request-abort");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    // AtomicReference has identity equality. Values do not retain their weak owner.
    // Coalesced by live controls, not a constant-size second queue.
    private static final Map<AtomicReference<Runnable>, Boolean> RETRY = new WeakHashMap<>();
    private static final ScheduledExecutorService TICK = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "request-abort-retry");
        thread.setDaemon(true);
        return thread;
    });
    static { TICK.scheduleWithFixedDelay(RequestAbort::retry, 100L, 100L, TimeUnit.MILLISECONDS); }

    /**
     * A request's stop reference after its caller stopped it before the request's network step put its own stop in (the call
     * greeting's clip request does: Greeter.cancelFill). register then refuses, so nothing is sent at all.
     */
    public static final Runnable STOPPED = () -> {};

    /**
     * Puts a request's stop in place before its first network I/O (DirectHttps, OpenAiAccount.Http): false when the caller has
     * stopped it already (STOPPED). Anything else in the reference is replaced, as a plain set would.
     */
    public static boolean register(AtomicReference<Runnable> owner, Runnable stop) {
        if (owner == null) return true;
        while (true) {
            Runnable seen = owner.get();
            if (seen == STOPPED) return false;
            if (owner.compareAndSet(seen, stop)) return true;
        }
    }

    public static boolean submit(Runnable abort) {
        if (abort == null) return true;
        try {
            CLOSE.execute(() -> { try { abort.run(); } catch (Throwable ignored) {} });
            return true;
        } catch (RejectedExecutionException full) { return false; }
    }

    public static void cancel(AtomicReference<Runnable> owner) {
        synchronized (RETRY) {
            Runnable abort = owner.getAndSet(null);
            if (submit(abort)) { RETRY.remove(owner); return; }
            // Never restore an old callback over a newer request's callback.
            owner.compareAndSet(null, abort);
            if (owner.get() != null) RETRY.put(owner, Boolean.TRUE);
            else RETRY.remove(owner);
        }
    }

    private static void retry() {
        ArrayList<AtomicReference<Runnable>> owners = new ArrayList<>();
        synchronized (RETRY) {
            for (Map.Entry<AtomicReference<Runnable>, Boolean> entry : RETRY.entrySet())
                owners.add(entry.getKey());
        }
        for (AtomicReference<Runnable> owner : owners) if (owner != null) cancel(owner);
    }
}
