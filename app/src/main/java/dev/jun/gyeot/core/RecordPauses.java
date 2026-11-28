package dev.jun.gyeot.core;

/**
 * 녹화·녹음의 일시정지 (준: "녹화 시작 일시정지 및 정지 버튼도 구현해줘 녹화화면상에서 바로 조작할 수 있기"): the spans a recording
 * was paused, on the clock its samples are stamped with, and what that makes of each sample's time. A paused span is cut out:
 * a sample taken inside one is left out (nothing is written while paused), and one taken after it moves earlier by every span
 * that ended before it, so the file plays on without a hole and 재개 goes on in the same file. The time shown (elapsed) leaves
 * the pauses out too. Any unit works (microseconds for the recorder's samples, milliseconds for the time shown), as long as
 * one object keeps to one. Thread-safe: the service pauses and resumes, the recorder's threads ask.
 */
public final class RecordPauses {
    private long[] starts = new long[4], ends = new long[4];
    private int count;
    private long closed;
    private static final long NONE = Long.MIN_VALUE;
    /** Where the pause now going on began, or NONE. */
    private long open = NONE;
    /** A picture was left out: the next one written has to be a key frame (the ones after a lost frame point back to it). */
    private boolean cut;

    public synchronized boolean paused() { return open != NONE; }
    /** Pauses at at; false when it was paused already. */
    public synchronized boolean pause(long at) {
        if (open != NONE) return false;
        open = count > 0 ? Math.max(at, ends[count - 1]) : at;
        return true;
    }
    /** Goes on at at; false when it was not paused. */
    public synchronized boolean resume(long at) {
        if (open == NONE) return false;
        long end = Math.max(at, open);
        if (end > open) {
            if (count == starts.length) { starts = java.util.Arrays.copyOf(starts, count * 2); ends = java.util.Arrays.copyOf(ends, count * 2); }
            starts[count] = open; ends[count] = end; count++;
            closed += end - open;
        }
        open = NONE;
        return true;
    }
    /** How long it has been paused in all, up to now (the pause going on counted until now). */
    public synchronized long pausedTotal(long now) { return closed + (open != NONE && now > open ? now - open : 0); }
    /** How long it has recorded since start, the pauses left out: it stands still while paused. */
    public synchronized long elapsed(long start, long now) { return Math.max(0, now - start - pausedTotal(now)); }
    /** A sample's time with the paused spans cut out, or -1 when it was taken while paused (it is left out). */
    public synchronized long map(long pts) {
        if (open != NONE && pts >= open) return -1;
        long shift = 0;
        for (int i = 0; i < count; i++) {
            if (pts < starts[i]) break;
            if (pts < ends[i]) return -1;
            shift += ends[i] - starts[i];
        }
        return pts - shift;
    }
    /**
     * A picture's time (key: a key frame), as map. After a picture was left out (an encoder that went on encoding while
     * paused, or a frame on its way when the pause came), nothing more until the next key frame: the frames after a lost one
     * point back to it and would show broken. The recorder asks the encoder for a key frame at 재개, so that is the next one.
     */
    public synchronized long video(long pts, boolean key) {
        long t = map(pts);
        if (t < 0) { cut = true; return -1; }
        if (cut) { if (!key) return -1; cut = false; }
        return t;
    }
    /** A sound sample's time, as map (sound frames stand alone: none waits for another). */
    public long audio(long pts) { return map(pts); }

    /** The time on the recording's controls: "0:07", "12:34", "1:02:03". */
    public static String clock(long millis) {
        long s = Math.max(0, millis / 1000), h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        return h > 0 ? h + ":" + two(m) + ":" + two(sec) : m + ":" + two(sec);
    }
    private static String two(long n) { return n < 10 ? "0" + n : String.valueOf(n); }
}
