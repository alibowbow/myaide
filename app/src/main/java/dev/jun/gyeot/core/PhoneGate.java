package dev.jun.gyeot.core;

/** Phone control the user switched on in the app. Every stop starts a new generation, so work queued before it never runs. */
public final class PhoneGate {
    private boolean armed;
    private long generation;
    private final java.util.List<Runnable> revocations = new java.util.ArrayList<>();
    public void arm() { revoke(true); }
    public void stop() { revoke(false); }
    /** Revoke old work while preserving the user's permission, including an already stopped gate. */
    public void interrupt() { revoke(null); }
    private void revoke(Boolean next) {
        java.util.List<Runnable> callbacks;
        synchronized (this) { generation++; if (next != null) armed = next; callbacks = new java.util.ArrayList<>(revocations); revocations.clear(); }
        for (Runnable callback : callbacks) callback.run();
    }
    /** Cancels in-flight model requests when control is stopped or re-armed. Callbacks must not block. */
    public synchronized AutoCloseable watch(long ticket, Runnable revoked) {
        check(ticket); revocations.add(revoked);
        return () -> { synchronized (PhoneGate.this) { revocations.remove(revoked); } };
    }
    public synchronized boolean armed() { return armed; }
    /** One delegated task can be cancelled without switching off the user's whole voice session. */
    public Task task() { return new Task(this, ticket()); }
    public static final class Task {
        private final PhoneGate gate;
        public final long ticket;
        private volatile String stopped;
        private Task(PhoneGate gate, long ticket) { this.gate = gate; this.ticket = ticket; }
        public synchronized void cancel(String reason) { if (stopped == null) stopped = reason == null ? "폰 작업을 취소했어요." : reason; }
        public String stopped() { return stopped; }
        public void check() {
            String reason = stopped;
            if (reason != null) throw new IllegalStateException(reason);
            gate.check(ticket);
        }
    }
    /** Captures the current generation for one request; throws when control is off. */
    public synchronized long ticket() {
        if (!armed) throw new IllegalStateException("폰 조작이 꺼져 있어요. " + Brand.NAME + " 설정에서 ‘폰 조작’을 켜 주세요.");
        return generation;
    }
    /** Re-checked right before acting: a stop (or stop and re-arm) after the ticket cancels the request. */
    public synchronized void check(long ticket) {
        if (!armed || ticket != generation) throw new IllegalStateException("폰 조작이 중지되어 이전 요청을 취소했어요.");
    }
}


