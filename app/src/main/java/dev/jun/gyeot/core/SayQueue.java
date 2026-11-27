package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 퍼레's answer said as one stream (준: "음성대화 너무 느려"): its sentences (clips) in the order they were written, each one's
 * sound fetched while the one before it plays, never more than one ahead (a talk cut short has asked for little), and played
 * in that order however their fetches end. A sentence whose sound did not come keeps its place and is said another way (its
 * fallback). The fetches push their sound in (arrived, fetched, failed), one player takes the clips out (take, played). Pure
 * Java; any thread.
 */
public final class SayQueue {
    public enum State { WAITING, FETCHING, READY, PLAYING, DONE, FAILED }
    /** How a sentence with no sound of its own is said: from Typecast's whole file (made for it), else in the phone's voice. */
    public enum Fallback { NONE, WHOLE, PHONE }
    /** One sentence of the answer and its sound as it comes. */
    public static final class Clip {
        /** Its place in the answer, from 0. */
        public final int n; public final String text;
        private volatile State state = State.WAITING;
        private volatile Fallback fallback = Fallback.NONE;
        /** Its sound as it comes: 16-bit mono PCM in even-length chunks, at rate (known before the first chunk). */
        public final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        public volatile int rate;
        /** All of its sound has come (or no more will); bytes so far; size: the whole length when its header told it (else 0). */
        public volatile boolean ended; public volatile long bytes, size;
        /** When its fetch began and when its first sound came (monotonic ms, for the timing log). */
        public volatile long askedAt, firstAt;
        /** Its whole file when the stream gave nothing (Fallback.WHOLE). */
        public volatile java.io.File whole;
        /** Stops its fetch (OpenAiAccount.Http puts the stop in; cancelAll runs it, or marks it so nothing is sent). */
        public final AtomicReference<Runnable> cancel = new AtomicReference<>();
        Clip(int n, String text) { this.n = n; this.text = text == null ? "" : text; }
        public State state() { return state; }
        public Fallback fallback() { return fallback; }
    }
    private final List<Clip> clips = new ArrayList<>();
    /** How many clips the player has taken (the next to play is clips[taken]). */
    private int taken;
    private boolean closed, cancelled;

    /** A sentence written: its clip, waiting its turn to be fetched. After cancelAll it is never fetched or played. */
    public synchronized Clip add(String text) {
        Clip c = new Clip(clips.size(), text);
        if (cancelled) { c.state = State.DONE; return c; }
        clips.add(c); notifyAll();
        return c;
    }
    /** The answer is whole: no more clips; take() returns null once the last is taken. */
    public synchronized void close() { closed = true; notifyAll(); }
    public synchronized boolean closed() { return closed; }
    public synchronized boolean cancelled() { return cancelled; }
    /** Closed with every clip taken, or cancelled: nothing more to play. */
    public synchronized boolean over() { return cancelled || closed && taken >= clips.size(); }

    /**
     * The clip to fetch now, marked FETCHING, or null: the first one waiting, only when the clip before it has been taken (it
     * plays), so at most one is fetched ahead of the one playing; the first one at once.
     */
    public synchronized Clip nextToFetch() {
        if (cancelled) return null;
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            if (c.state != State.WAITING) continue;
            if (i > taken) return null;
            c.state = State.FETCHING; c.askedAt = now();
            return c;
        }
        return null;
    }
    /** Some of a clip's sound (an even number of bytes). */
    public synchronized void arrived(Clip c, byte[] pcm) {
        if (cancelled || pcm == null || pcm.length == 0) return;
        if (c.bytes == 0) c.firstAt = now();
        c.chunks.add(pcm); c.bytes += pcm.length;
        notifyAll();
    }
    /** All of a clip's sound has come. */
    public synchronized void fetched(Clip c) {
        c.ended = true;
        if (c.state == State.FETCHING || c.state == State.WAITING) c.state = State.READY;
        notifyAll();
    }
    /**
     * A clip's fetch failed: with sound already come, that much is said (as fetched); with none, it keeps its place, said as
     * instead tells.
     */
    public synchronized void failed(Clip c, Fallback instead) {
        if (c.bytes > 0) { fetched(c); return; }
        c.ended = true; c.fallback = instead == null ? Fallback.PHONE : instead;
        if (c.state != State.DONE) c.state = State.FAILED;
        notifyAll();
    }
    /**
     * The next clip in order, once it can be played (its first sound came, or it is whole, or it failed and has its fallback),
     * waiting up to waitMs for it; null when none is ready by then or nothing more will come (over()). A taken clip PLAYING
     * (a failed one stays FAILED, for its fallback).
     */
    public synchronized Clip take(long waitMs) throws InterruptedException {
        long until = now() + Math.max(0, waitMs);
        while (!cancelled) {
            if (taken < clips.size()) {
                Clip c = clips.get(taken);
                if (c.state == State.READY || c.state == State.FAILED || c.state == State.FETCHING && c.bytes > 0) {
                    taken++;
                    if (c.state != State.FAILED) c.state = State.PLAYING;
                    return c;
                }
            } else if (closed) return null;
            long left = until - now();
            if (left <= 0) return null;
            wait(left);
        }
        return null;
    }
    /** A clip said to its end. */
    public synchronized void played(Clip c) { c.state = State.DONE; c.chunks.clear(); }
    /**
     * Cut short (the user spoke over 퍼레, or the talk ended): no clip is fetched or played from now on, every fetch is stopped
     * (one not yet sent never goes out), and their sound let go.
     */
    public void cancelAll() {
        List<Clip> all;
        synchronized (this) { cancelled = true; all = new ArrayList<>(clips); notifyAll(); }
        for (Clip c : all) {
            c.chunks.clear();
            Runnable stop = c.cancel.getAndSet(RequestAbort.STOPPED);
            if (stop != null && stop != RequestAbort.STOPPED) RequestAbort.submit(stop);
        }
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
}
