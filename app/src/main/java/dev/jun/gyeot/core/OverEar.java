package dev.jun.gyeot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The microphone while 퍼레 speaks (barge-in, 준: "퍼레가 말하는 중에 끼어들면 멈추고 듣기"): each 20 ms frame is set against what
 * 퍼레 plays at that moment (BargeIn), so its own voice never stops it and a short "응" or a knock does not; the last second is
 * kept. Once the user has talked over it (OVER), what they say is cut from just before their voice began, in those kept frames,
 * on through the live ones (Utterance), so the first words are not lost. After an answer it can hear the next words itself
 * from that moment (listen), with no gap. Pure Java; one thread.
 */
public final class OverEar {
    /** One frame: 20 ms at 16 kHz. */
    public static final int FRAME = 320;
    /** Frames kept (one second), the frames kept before the user's voice began (300 ms), and the gap in it forgiven (100 ms). */
    static final int KEEP = 50, BEFORE = 15, GAP = 5;
    /** ECHO: 퍼레 speaks, not talked over; OVER: the user talked over it (once); then, hearing them, as Utterance.State. */
    public enum Step { ECHO, OVER, WAITING, SPEAKING, DONE, SILENT }
    private record Kept(short[] pcm, boolean voice) {}
    private final BargeIn barge;
    private final ArrayDeque<Kept> kept = new ArrayDeque<>();
    private Utterance cut;

    /** barge: the talk's own (what 퍼레's echo sounds like is kept from answer to answer), listening afresh from now. */
    public OverEar(BargeIn barge) { this.barge = barge; barge.reset(); }

    /** One frame (n samples) and the loudest 퍼레 plays around it now (16-bit RMS, PlayLevel); ignored while hearing. */
    public Step add(short[] frame, int n, double playing) {
        if (n <= 0) return Step.ECHO;
        if (cut != null) return step(cut.add(frame, n));
        boolean over = barge.hear(BargeIn.rms(frame, n), playing, Math.max(1, n / 16));
        kept.addLast(new Kept(java.util.Arrays.copyOf(frame, n), barge.voice()));
        while (kept.size() > KEEP) kept.removeFirst();
        return over ? Step.OVER : Step.ECHO;
    }
    /**
     * After OVER: what the user says, from 300 ms before the run of their voice that stopped 퍼레 (short gaps forgiven) on;
     * waitMs as Utterance's. The step it is at with the kept frames.
     */
    public Step hear(long waitMs) {
        List<Kept> all = new ArrayList<>(kept); kept.clear();
        int onset = all.size(), gap = 0;
        for (int i = all.size() - 1; i >= 0; i--) { if (all.get(i).voice()) { onset = i; gap = 0; } else if (++gap > GAP) break; }
        int from = Math.max(0, Math.min(onset, all.size()) - BEFORE), length = 0;
        for (int i = from; i < all.size(); i++) length += all.get(i).pcm().length;
        short[] said = new short[length]; int at = 0;
        for (int i = from; i < all.size(); i++) { short[] p = all.get(i).pcm(); System.arraycopy(p, 0, said, at, p.length); at += p.length; }
        cut = Utterance.begun(said, waitMs);
        return step(cut.state());
    }
    /** After the answer: the next words from this moment, as a listening of waitMs (no barge-in any more). */
    public void listen(long waitMs) { kept.clear(); cut = new Utterance(waitMs); }
    /** Whether it hears the user now (after OVER or listen). */
    public boolean hearing() { return cut != null; }
    /** What the user said, once DONE. */
    public short[] pcm() { return cut == null ? new short[0] : cut.pcm(); }
    private static Step step(Utterance.State s) {
        return switch (s) { case WAITING -> Step.WAITING; case SPEAKING -> Step.SPEAKING; case DONE -> Step.DONE; case SILENT -> Step.SILENT; };
    }
}
