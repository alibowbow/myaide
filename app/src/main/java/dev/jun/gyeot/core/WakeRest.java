package dev.jun.gyeot.core;

/**
 * How long the wake word rests before it listens again. After a wake that led to a conversation the user asked something
 * in, it rests a few seconds from the call's end, or two minutes when the user turned the call off themselves. After a wake
 * nobody asked anything in (it never started, nothing was said, or only "꺼", "그만", "잘못 불렀어"), it rests two minutes,
 * and after a second one in a row for the rest of the drive: sounds other than the user (the radio, music, its own voice
 * through the car) can match the word over and over, and turning it off must keep it off. A conversation clears the
 * count; so does a new drive, when the car connects again after being away for DRIVE_GAP_MS. Times are any steady clock's
 * milliseconds. Not thread-safe: the service's main thread.
 */
public final class WakeRest {
    /** After a wake that was answered: not again for this long, from the call's end (and from the wake, until the call is seen). */
    public static final long COOLDOWN_MS = 8000;
    /** After a wake that was answered and then turned off by the user: they want quiet for a while. */
    static final long STOPPED_MS = 120_000;
    /** Until the drive ends: the car disconnects and comes back after DRIVE_GAP_MS. */
    static final long DRIVE = Long.MAX_VALUE / 4;
    /** After the first, and the second and later, wakes in a row that nobody answered. */
    static final long[] RESTS_MS = {120_000, DRIVE};
    /** A wake whose call never showed up within this long (a locked screen, a notification not tapped) was not answered. */
    public static final long NO_CALL_MS = 15_000;
    /** The car away this long and back again is a new drive; a shorter drop of Bluetooth is the same one. */
    static final long DRIVE_GAP_MS = 10 * 60_000L;

    private int unanswered; private long until = Long.MIN_VALUE, wokeAt, away = Long.MIN_VALUE; private boolean pending, called;

    /** The wake word fired. */
    public void woke(long now) { pending = true; called = false; wokeAt = now; until = now + COOLDOWN_MS; }
    /**
     * Follows the call a wake started. active: a voice call is on now; asked: whether the user asked for anything in the call
     * that last ended (UserWords.asked); stopped: whether the user turned that call off. Returns the rest that began just now
     * in milliseconds when the wake's outcome became known, else 0.
     */
    public long follow(long now, boolean active, boolean asked, boolean stopped) {
        if (!pending) return 0;
        if (active) { called = true; return 0; }
        if (!called && now - wokeAt < NO_CALL_MS) return 0;
        pending = false;
        boolean answered = called && asked; called = false;
        if (answered) { unanswered = 0; long rest = stopped ? STOPPED_MS : COOLDOWN_MS; until = now + rest; return rest; }
        unanswered++;
        long rest = RESTS_MS[Math.min(unanswered, RESTS_MS.length) - 1];
        until = now + rest; return rest;
    }
    /** The chosen car connected: a new drive when it was away long enough (or never seen leaving), else the same one. */
    public void connected(long now) {
        if (away != Long.MIN_VALUE && now - away < DRIVE_GAP_MS) return;
        unanswered = 0; pending = false; called = false; until = Long.MIN_VALUE;
    }
    /** The chosen car disconnected. */
    public void disconnected(long now) { away = now; }
    public boolean resting(long now) { return now < until; }
    /** Whether it rests for the rest of this drive. */
    public boolean restingForDrive(long now) { return until - now > DRIVE / 2; }
    /** Milliseconds until it may listen again; 0 when it may now. */
    public long left(long now) { return Math.max(0, until - now); }
    /** Wakes in a row that nobody answered. */
    public int unanswered() { return unanswered; }
    /** Whether a wake's outcome is still awaited (its call has not ended, or has not started yet). */
    public boolean pending() { return pending; }
    /** A rest in words: 30초, 2분, 이번 운행 동안. */
    public static String words(long ms) { return ms > DRIVE / 2 ? "이번 운행 동안" : ms < 60_000 ? (ms / 1000) + "초" : (ms / 60_000) + "분"; }
    /**
     * Whether a line the user said asks for something: not empty, and not only a word to stop or dismiss the call ("꺼",
     * "그만해", "됐어", "잘못 불렀어", "취소").
     */
    public static boolean asked(String said) {
        String s = said == null ? "" : said.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}…~]", "");
        if (s.isEmpty()) return false;
        // The stop word with at most a short ending (줘, 해, 요, 어 …): "아니 그게 아니라 내일 일정 알려 줘" is a request.
        return !s.matches("(?:(?:야|아|어|음|응|네|에이)(?=.))?(?:꺼|끄|그만|종료|닫아|됐|아니|취소|필요없|잘못|안불렀|부른거아|조용|stop|cancel|nevermind|bye|잘가|끊어).{0,5}");
    }
}
