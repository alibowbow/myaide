package dev.jun.gyeot.core;

/**
 * How long the wake word rests before it listens again. After a wake that led to a conversation it
 * rests a few seconds from the call's end. After a wake nobody answered (the call ended with nothing said, or never
 * started) it rests longer each time, since sounds other than the user can match the word over and
 * over. A conversation, or the car
 * connecting anew, clears the count. Times are any steady clock's milliseconds. Not thread-safe: the service's main thread.
 */
public final class WakeRest {
    /** After a wake that was answered: not again for this long, from the call's end (and from the wake, until the call is seen). */
    public static final long COOLDOWN_MS = 8000;
    /** After the first, second, third and later wakes in a row that nobody answered. */
    static final long[] RESTS_MS = {30_000, 120_000, 600_000};
    /** A wake whose call never showed up within this long (a locked screen, a notification not tapped) was not answered. */
    public static final long NO_CALL_MS = 15_000;

    private int unanswered; private long until = Long.MIN_VALUE, wokeAt; private boolean pending, called;

    /** The wake word fired. */
    public void woke(long now) { pending = true; called = false; wokeAt = now; until = now + COOLDOWN_MS; }
    /**
     * Follows the call a wake started. active: a voice call is on now; spoken: whether anything was said in the call that
     * last ended. Returns the rest that began just now in milliseconds when the wake's outcome became known, else 0.
     */
    public long follow(long now, boolean active, boolean spoken) {
        if (!pending) return 0;
        if (active) { called = true; return 0; }
        if (!called && now - wokeAt < NO_CALL_MS) return 0;
        pending = false;
        boolean answered = called && spoken; called = false;
        if (answered) { unanswered = 0; until = now + COOLDOWN_MS; return COOLDOWN_MS; }
        unanswered++;
        long rest = RESTS_MS[Math.min(unanswered, RESTS_MS.length) - 1];
        until = now + rest; return rest;
    }
    /** A new drive: the car connected. */
    public void reset() { unanswered = 0; pending = false; called = false; until = Long.MIN_VALUE; }
    public boolean resting(long now) { return now < until; }
    /** Milliseconds until it may listen again; 0 when it may now. */
    public long left(long now) { return Math.max(0, until - now); }
    /** Wakes in a row that nobody answered. */
    public int unanswered() { return unanswered; }
    /** Whether a wake's outcome is still awaited (its call has not ended, or has not started yet). */
    public boolean pending() { return pending; }
    /** A rest in words: 30초, 2분, 10분. */
    public static String words(long ms) { return ms < 60_000 ? (ms / 1000) + "초" : (ms / 60_000) + "분"; }
}
