package dev.jun.gyeot.core;

/**
 * The user talking over 퍼레 while it speaks (barge-in, 준: "3번 진행" — "퍼레가 말하는 중에 끼어들면 멈추고 듣기"): 20 ms frames
 * of the microphone (with the phone's echo canceller), and a voice that stays well above 퍼레's own echo for about half a
 * second. The level 퍼레's echo leaves is learned in its first 300 ms and follows the quiet frames after; a short "응" or a
 * knock does not count. Pure Java.
 */
public final class TalkOver {
    /** One frame: 20 ms at 16 kHz. */
    public static final int FRAME = 320;
    private static final int LEARN = 15, NEED = 22;
    private static final double LOUDER = 3.0, LEAST = 1400;
    private double floor; private int frames, loud;

    /** One frame (n samples of pcm); true once the user has been talking over 퍼레 long enough. */
    public boolean add(short[] pcm, int n) {
        if (pcm == null || n <= 0) return false;
        double sum = 0; for (int i = 0; i < n; i++) sum += (double) pcm[i] * pcm[i];
        double rms = Math.sqrt(sum / n);
        if (++frames <= LEARN) { floor = Math.max(floor, rms); return false; }
        if (rms > Math.max(floor * LOUDER, LEAST)) loud++;
        else { loud = Math.max(0, loud - 2); floor = floor * 0.95 + rms * 0.05; }
        return loud >= NEED;
    }
}
