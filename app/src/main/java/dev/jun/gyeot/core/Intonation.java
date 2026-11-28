package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether something said ends going up, as a question does (준: "퍼레가 의문문을 못 알아 들어", "음악소리들려? 라고 말하면
 * 음악소리들려로 알아들어"): speech recognition hands over words without the voice's rise, so "들려?" and "들려" read the same.
 * The pitch (autocorrelation, 70–400 Hz, every 10 ms over the loud part of the last two seconds) of the last fifth of the voiced
 * stretches against the rest: 12 % higher and still climbing is a question. 16 kHz mono. Pure Java.
 */
public final class Intonation {
    private Intonation() {}
    static final int RATE = 16_000, HOP = 160, SPAN = 640, LO = RATE / 400, HI = RATE / 70;

    public static boolean rising(short[] pcm) {
        float[] f0 = contour(pcm);
        int n = f0.length; if (n < 15) return false;
        int tail = Math.max(4, n / 5);
        float[] head = Arrays.copyOfRange(f0, 0, n - tail), end = Arrays.copyOfRange(f0, n - tail, n);
        float before = median(head), after = median(end);
        // Still climbing at the very end: the last few above the first few of the tail.
        float firstHalf = median(Arrays.copyOfRange(end, 0, tail / 2)), lastHalf = median(Arrays.copyOfRange(end, tail / 2, tail));
        return after > before * 1.12f && lastHalf >= firstHalf;
    }
    /** The voiced pitches of the last two seconds of sound, in order (Hz). */
    static float[] contour(short[] pcm) {
        if (pcm == null || pcm.length < SPAN * 2) return new float[0];
        int frames = (pcm.length - SPAN) / HOP + 1;
        float[] energy = new float[frames]; float peak = -200f;
        for (int f = 0; f < frames; f++) {
            double sum = 0; int at = f * HOP; for (int k = 0; k < SPAN; k++) { double x = pcm[at + k] / 32768.0; sum += x * x; }
            energy[f] = (float) (10 * Math.log10(sum / SPAN + 1e-10)); peak = Math.max(peak, energy[f]);
        }
        int last = frames - 1; while (last > 0 && energy[last] < peak - 25f) last--;
        int first = Math.max(0, last - 200);
        List<Float> out = new ArrayList<>(); double[] seg = new double[SPAN];
        for (int f = first; f <= last; f++) {
            if (energy[f] < peak - 25f) continue;
            int at = f * HOP; double mean = 0;
            for (int k = 0; k < SPAN; k++) { seg[k] = pcm[at + k] / 32768.0; mean += seg[k]; }
            mean /= SPAN; double zero = 0; for (int k = 0; k < SPAN; k++) { seg[k] -= mean; zero += seg[k] * seg[k]; }
            if (zero <= 0) continue;
            double best = -2; int lag = LO;
            for (int l = LO; l < HI; l++) { double s = 0; for (int k = 0; k + l < SPAN; k++) s += seg[k] * seg[k + l]; s /= zero; if (s > best) { best = s; lag = l; } }
            if (best > 0.5) out.add((float) RATE / lag);
        }
        float[] f0 = new float[out.size()]; for (int i = 0; i < f0.length; i++) f0[i] = out.get(i);
        return f0;
    }
    private static float median(float[] v) { if (v.length == 0) return 0; float[] s = v.clone(); Arrays.sort(s); return s[s.length / 2]; }
}
