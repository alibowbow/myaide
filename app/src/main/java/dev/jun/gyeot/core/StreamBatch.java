package dev.jun.gyeot.core;

/**
 * When a main-chat answer that is still being written reaches the screen and the partial column. The screen takes the
 * first piece at once and then what gathered at most every FLUSH ms, so a fast stream redraws a few times a second instead
 * of once per piece; the text so far is saved at most every SAVE ms. Times are the caller's clock in ms (uptime on the
 * phone). Not thread-safe: the caller holds its own lock.
 */
public final class StreamBatch {
    public static final long FLUSH = 250, SAVE = 2_000;
    private boolean scheduled, started;
    private long taken = Long.MIN_VALUE, saved;

    /** A piece arrived at now: the delay before the screen should take what gathered, or -1 when a take is already due. */
    public long piece(long now) {
        if (scheduled) return -1;
        scheduled = true;
        return taken == Long.MIN_VALUE ? 0 : Math.max(0, taken + FLUSH - now);
    }
    /** The screen took what gathered at now; the next piece waits until FLUSH ms after it. */
    public void took(long now) { scheduled = false; taken = now; }
    /** Whether the text so far should be saved at now: SAVE ms after the first piece, then at most every SAVE ms. */
    public boolean save(long now) {
        if (!started) { started = true; saved = now; return false; }
        if (now - saved < SAVE) return false;
        saved = now;
        return true;
    }
}
