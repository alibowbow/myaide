package dev.jun.gyeot.core;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded device resources, not a limit on a room's tool calls. */
public final class WorkRoomPool {
    private final ThreadPoolExecutor executor;
    private final IdentityHashMap<Object, Task> tasks = new IdentityHashMap<>();

    public WorkRoomPool() { this(3, 9); }

    public WorkRoomPool(int workers, int waiting) {
        AtomicInteger names = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(waiting), runnable -> {
                    Thread thread = new Thread(runnable, "work-room-" + names.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized boolean execute(Object token, Runnable action) {
        if (token == null || action == null) throw new NullPointerException();
        if (tasks.containsKey(token)) return false;
        Task task = new Task(token, action);
        tasks.put(token, task);
        try { executor.execute(task); return true; }
        catch (RejectedExecutionException rejected) { tasks.remove(token); return false; }
    }

    /** Only removes work that has not begun. Running tasks retain their physical slot. */
    public synchronized boolean cancelQueued(Object token) {
        Task task = tasks.get(token);
        if (task == null || task.started || !executor.remove(task)) return false;
        tasks.remove(token);
        return true;
    }

    public synchronized boolean contains(Object token) { return tasks.containsKey(token); }
    public synchronized int activeCount() {
        int count = 0;
        for (Task task : tasks.values()) if (task.started) count++;
        return count;
    }
    public synchronized int queuedCount() { return tasks.size() - activeCount(); }
    public synchronized boolean hasRoom() {
        return !executor.isShutdown() && tasks.size() < executor.getMaximumPoolSize() + executor.getQueue().remainingCapacity() + executor.getQueue().size();
    }
    public synchronized void shutdownNow() {
        List<Runnable> abandoned = executor.shutdownNow();
        for (Runnable runnable : abandoned) {
            Task task = (Task) runnable;
            if (tasks.get(task.token) == task) tasks.remove(task.token);
        }
    }

    private final class Task implements Runnable {
        final Object token;
        final Runnable action;
        boolean started;
        Task(Object token, Runnable action) { this.token = token; this.action = action; }
        public void run() {
            synchronized (WorkRoomPool.this) { started = true; }
            try { action.run(); }
            finally {
                synchronized (WorkRoomPool.this) {
                    if (tasks.get(token) == this) tasks.remove(token);
                }
            }
        }
    }
}
