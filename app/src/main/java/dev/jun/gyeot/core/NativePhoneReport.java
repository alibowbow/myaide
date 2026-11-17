package dev.jun.gyeot.core;

/**
 * The voice model's report after its own phone steps: once a phone_action
 * is asked, what the model says is held until the local completion check has looked at the screen, then played, replaced by
 * a notice, or turned into one more working turn. One phase, one version:
 *
 * <pre>
 * IDLE ──acting()──▶ ACTING ──beginCheck()──▶ CHECKING ──finish: reset()──▶ IDLE
 *                      ▲                          │
 *                      └──── keepWorking() ◀──────┘   (an unverified report gets one more working turn)
 * reset() from any phase: the user spoke, the job was stopped, the connection was replaced, the call started.
 * </pre>
 *
 * The version changes on every acting() and reset(), so a check that started before either is stale (current() is false) and
 * may neither play nor speak; only one check runs at a time.
 */
public final class NativePhoneReport {
    public enum Phase { IDLE, ACTING, CHECKING }
    private volatile Phase phase = Phase.IDLE;
    private volatile long version;

    /** A phone_action was asked: what the model says next is held for the check. */
    public synchronized void acting() { if (phase != Phase.CHECKING) phase = Phase.ACTING; version++; }
    /** Reports are held (a phone step was asked, or its report is being checked). */
    public boolean holding() { return phase != Phase.IDLE; }
    public Phase phase() { return phase; }
    /** Starts the check of a held report: its version, or -1 when one is already running (a second one never starts). */
    public synchronized long beginCheck() {
        if (phase == Phase.CHECKING) return -1;
        phase = Phase.CHECKING; return version;
    }
    /** That check is still the one to finish: nothing reset or re-armed the report since it began. */
    public boolean current(long checked) { return checked >= 0 && checked == version && phase == Phase.CHECKING; }
    /** An unverified report gets one more working turn: reports stay held for it. */
    public synchronized void keepWorking(long checked) { if (current(checked)) phase = Phase.ACTING; }
    /** Nothing held any more: the check finished, the user spoke, the job stopped or the connection changed. */
    public synchronized void reset() { phase = Phase.IDLE; version++; }
}
