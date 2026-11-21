package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * The sound behind a caption's 다시 듣기: the microphone's last seconds with when each piece came (so the user's words can
 * be cut out once they are written down), and a clip with the quiet at both ends taken off. 16-bit little-endian mono PCM.
 * Pure Java.
 */
public final class VoiceClipAudio {
    private VoiceClipAudio() {}

    /** The microphone's recent sound: pieces with the uptime they came at, the oldest dropped past max milliseconds. */
    public static final class Ring {
        private record Piece(long at, byte[] pcm) {}
        private final ArrayDeque<Piece> pieces = new ArrayDeque<>();
        private final long max;
        public Ring(long maxMillis) { max = maxMillis; }
        public synchronized void add(long at, byte[] pcm) {
            if (pcm == null || pcm.length == 0) return;
            pieces.addLast(new Piece(at, pcm.clone()));
            while (!pieces.isEmpty() && at - pieces.peekFirst().at > max) pieces.removeFirst();
        }
        /** The sound that came from (inclusive) to until (inclusive), in order. */
        public synchronized byte[] between(long from, long until) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Piece p : pieces) if (p.at >= from && p.at <= until) out.write(p.pcm, 0, p.pcm.length);
            return out.toByteArray();
        }
        public synchronized void clear() { pieces.clear(); }
    }

    /** How loud 20 ms at offset is (root mean square of its samples). */
    static double rms(byte[] pcm, int offset, int length) {
        long sum = 0; int n = 0;
        for (int i = offset; i + 1 < offset + length && i + 1 < pcm.length; i += 2) { int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)); sum += (long) s * s; n++; }
        return n == 0 ? 0 : Math.sqrt((double) sum / n);
    }

    /**
     * The clip without the quiet before and after its sound (a quarter second kept at each end), at most maxSeconds long;
     * empty when nothing in it is louder than the room.
     */
    public static byte[] trim(byte[] pcm, int rate, int maxSeconds) {
        if (pcm == null || pcm.length < 4) return new byte[0];
        int frame = Math.max(2, rate / 50 * 2), frames = pcm.length / frame;
        if (frames == 0) return new byte[0];
        double[] level = new double[frames]; double peak = 0;
        for (int f = 0; f < frames; f++) { level[f] = rms(pcm, f * frame, frame); peak = Math.max(peak, level[f]); }
        double threshold = Math.max(300, peak * 0.12);
        int first = -1, last = -1;
        for (int f = 0; f < frames; f++) if (level[f] >= threshold) { if (first < 0) first = f; last = f; }
        if (first < 0 || peak < 500) return new byte[0];
        int pad = 12; // frames of 20 ms
        int start = Math.max(0, first - pad) * frame, end = Math.min(frames, last + 1 + pad) * frame;
        int most = rate * 2 * Math.max(1, maxSeconds);
        if (end - start > most) end = start + most;
        byte[] out = new byte[end - start];
        System.arraycopy(pcm, start, out, 0, out.length);
        return out;
    }

    /** Seconds of sound in pcm at rate. */
    public static double seconds(int bytes, int rate) { return rate <= 0 ? 0 : bytes / 2.0 / rate; }
}
