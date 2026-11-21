package dev.jun.gyeot.core;

/**
 * When a GPT-Live call is up, for one call attempt: the phone took the server's answer (SDP applied), WebRTC connected,
 * and the server said its session is ready (READY, which may come first: the sideband opens before the SDP is applied).
 * Up is told exactly once, and only with all three; a failure is told once; after a failure or close nothing more is
 * told, so a late READY never reads as success. Pure Java; any thread.
 */
public final class LiveGate {
    public enum Step { APPLIED, CONNECTED, READY }
    private boolean applied, connected, ready, failed, closed, up;

    /** A step reached; true exactly once, the moment the call is up. */
    public synchronized boolean reached(Step step) {
        if (failed || closed) return false;
        switch (step) { case APPLIED -> applied = true; case CONNECTED -> connected = true; case READY -> ready = true; }
        if (up || !(applied && connected && ready)) return false;
        up = true;
        return true;
    }
    /** The attempt failed: true the first time (to be told), false once it already failed or was closed. */
    public synchronized boolean fail() { if (failed || closed) return false; failed = true; return true; }
    public synchronized void close() { closed = true; }
    /** Failed or closed: what comes late is left alone. */
    public synchronized boolean over() { return failed || closed; }
    public synchronized boolean up() { return up; }
    /** The steps not reached yet, for why it is not up ("" when up). */
    public synchronized String missing() {
        StringBuilder out = new StringBuilder();
        if (!applied) out.append("SDP 적용");
        if (!connected) out.append(out.length() == 0 ? "" : " · ").append("connected");
        if (!ready) out.append(out.length() == 0 ? "" : " · ").append("서버 READY");
        return out.toString();
    }
}
