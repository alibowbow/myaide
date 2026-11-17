package dev.jun.gyeot.core;

/** One-shot interruption of one safe model response, never cancellation of the owning run. */
public final class WorkInterrupt {
    private final Runnable changed;
    private Ticket active;
    private boolean closed;
    public WorkInterrupt(Runnable changed) { this.changed = changed; }
    public static final class Ticket {
        private final String token = java.util.UUID.randomUUID().toString();
        private final Runnable abort;
        private volatile boolean requested;
        private Ticket(Runnable abort) { this.abort = abort; }
        public boolean requested() { return requested; }
    }
    public Ticket open(Runnable abort) {
        Ticket ticket;
        synchronized (this) {
            if (closed || abort == null) return null;
            if (active != null) throw new IllegalStateException("An interruptible request is already active");
            active = ticket = new Ticket(abort);
        }
        changed.run(); return ticket;
    }
    public synchronized boolean available() { return !closed && active != null && !active.requested; }
    public synchronized boolean requested() { return active != null && active.requested; }
    public synchronized String token() { return available() ? active.token : ""; }
    public Runnable request() { return request(token()); }
    /** Capture the OLD transport. A delayed closer must never consult a shared cancellation reference. */
    public Runnable request(String expectedToken) {
        Runnable abort;
        synchronized (this) {
            if (!available() || !active.token.equals(expectedToken)) return null;
            active.requested = true; abort = active.abort;
        }
        changed.run(); return abort;
    }
    public void end(Ticket ticket) {
        boolean update;
        synchronized (this) { update = ticket != null && active == ticket; if (update) active = null; }
        if (update) changed.run();
    }
    public void close() {
        synchronized (this) { closed = true; active = null; }
        changed.run();
    }
}
