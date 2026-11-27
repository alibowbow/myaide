package dev.jun.gyeot.core;

/**
 * How loud the speech being played is, and when (uptime ms): the microphone's echo is measured against it (core/BargeIn).
 * Each write's span (when it sounds, from and to) with its RMS; the loudest around a moment. For the edge assistant's speaker
 * (Speaker) and 퍼레's voice (BuddyVoice). Written by the thread that plays, read by the microphone's. Pure Java.
 */
public final class PlayLevel {
    /** Spans remembered: small writes (a streamed chunk can be a few milliseconds) still fill the window below. */
    private static final int SPANS = 256;
    private final long[] levelFrom = new long[SPANS], levelTo = new long[SPANS];
    private final double[] levels = new double[SPANS];
    private int levelNext;

    /** A write that plays from from to to (uptime ms) at level (16-bit RMS). */
    public synchronized void add(long from, long to, double level) {
        int i = levelNext; levelNext = (levelNext + 1) % levels.length;
        levelFrom[i] = from; levelTo[i] = to; levels[i] = level;
    }
    /**
     * The loudest speech playing around now (the last 700 ms to the next 100 ms), as 16-bit RMS; 0 when nothing plays. The
     * window spans the pause between two words or sentences of one answer, so its room echo is never taken for the user.
     */
    public synchronized double level(long now) {
        double loudest = 0;
        for (int i = 0; i < levels.length; i++) if (levelTo[i] >= now - 700 && levelFrom[i] <= now + 100) loudest = Math.max(loudest, levels[i]);
        return loudest;
    }
    /**
     * What plays now has ended at now (a sound whose length was not known ahead, the phone's voice): its span stops here, so
     * the window above runs from its real end, not from a guess.
     */
    public synchronized void end(long now) {
        for (int i = 0; i < levels.length; i++) if (levelFrom[i] <= now && levelTo[i] > now) levelTo[i] = now;
    }
    /** Nothing plays any more (flushed, cut short). */
    public synchronized void clear() { java.util.Arrays.fill(levelTo, 0); java.util.Arrays.fill(levelFrom, 0); java.util.Arrays.fill(levels, 0); }
}
