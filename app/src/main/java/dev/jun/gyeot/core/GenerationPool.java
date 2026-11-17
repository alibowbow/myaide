package dev.jun.gyeot.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded shared capacity for independent image/video requests. Ordinary chat has its own executor. */
public final class GenerationPool extends ThreadPoolExecutor {
    public static final int PARALLEL = 3, WAITING = 9;
    public GenerationPool() {
        super(PARALLEL, PARALLEL, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(WAITING), r -> {
            Thread thread = new Thread(r, "gyeot-generation"); thread.setDaemon(true); return thread;
        });
    }
    public boolean hasRoom() { return getActiveCount() + getQueue().size() < PARALLEL + WAITING; }
    /** Batch slots are accepted only when the user actually requested multiple creations. */
    public static int voiceIndex(String heard, org.json.JSONObject args) {
        int index = args.optInt("batch_index", 0);
        if (index < 0 || index >= PARALLEL + WAITING || (args.has("batch_index") && (!(args.opt("batch_index") instanceof Number n) || n.doubleValue() != index)))
            throw new IllegalArgumentException("동시 생성 번호를 확인해 주세요.");
        if (index > 0 && (heard == null || !heard.matches("(?is).*(동시|병렬|각각|여러|[2-9]\\s*(장|개|편)|두\\s*(장|개|편)|세\\s*(장|개|편)|네\\s*(장|개|편)|multiple|two|three).*")))
            throw new IllegalArgumentException("여러 개 생성을 요청한 경우에만 추가 생성할 수 있어요.");
        return index;
    }
    public static final class Control {
        public volatile boolean stopped;
        public volatile Thread thread;
        public final AtomicReference<Runnable> request = new AtomicReference<>();
        private final StringBuilder partial = new StringBuilder();
        public synchronized void partial(String delta) { if (!stopped && delta != null && partial.length() < 10_000) partial.append(TeamPlan.clip(delta, 10_000 - partial.length())); }
        public synchronized String partial() { return partial.toString(); }
        public synchronized void clearPartial() { partial.setLength(0); }
        public void check() { if (stopped || Thread.currentThread().isInterrupted()) throw new CancellationException("작업을 멈췄어요."); }
        public synchronized void bind() {
            check();
            thread = Thread.currentThread();
        }
        public synchronized void unbind() {
            if (thread == Thread.currentThread()) {
                thread = null;
                if (stopped) Thread.interrupted();
            }
        }
        public synchronized void stop() {
            stopped = true;
            // Ownership is protected by the same monitor as bind/unbind, so an old
            // control cannot interrupt a pool thread after it moves to a new run.
            Thread worker = thread; if (worker != null) worker.interrupt();
            RequestAbort.cancel(request);
        }
    }
}
