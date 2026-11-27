package dev.jun.gyeot.core;

/**
 * 화면 녹화's sound and timing, in pure Java: the audio format, mixing the phone's sound with the microphone, the audio clock,
 * and the file's timeline (video and audio on the same clock, the file starting at the first video frame).
 */
public final class ScreenRecordAudio {
    private ScreenRecordAudio() {}
    /** AAC-LC, 48kHz stereo at 256kbps; read in AAC-frame-sized chunks. */
    public static final int RATE = 48_000, CHANNELS = 2, FRAMES = 1024, BITRATE = 256_000;

    /** Below this a mixed sample passes as it is; above it a soft knee bends it under full scale (80% of full scale). */
    static final int KNEE = 26_214;
    /**
     * Adds add into into (count samples). Game and media sound is mastered close to full scale, so speech over it would
     * clip: sums up to KNEE pass unchanged, louder ones bend smoothly towards full scale (tanh), never cut off hard.
     */
    public static void mix(short[] into, short[] add, int count) {
        for (int i = 0; i < count; i++) {
            int sum = into[i] + add[i];
            if (sum >= -KNEE && sum <= KNEE) { into[i] = (short) sum; continue; }
            double x = sum / 32768.0, ax = Math.abs(x);
            double y = Math.signum(x) * (0.8 + 0.2 * Math.tanh((ax - 0.8) / 0.2));
            into[i] = (short) Math.max(-32767, Math.min(32767, Math.round(y * 32767)));
        }
    }
    /** One channel into two: each sample twice (frames of mono into frames of stereo). */
    public static void upmix(short[] mono, int frames, short[] stereo) {
        for (int i = frames - 1; i >= 0; i--) { short s = mono[i]; stereo[2 * i] = s; stereo[2 * i + 1] = s; }
    }

    /**
     * The presentation time of the sound, in microseconds on the video's clock (System.nanoTime). The times are strictly
     * continuous: each chunk starts where the one before ended (the start plus the frames handed out), so the sound never
     * jumps against the picture and players that play it back to back stay in step. Where the sound really was captured
     * (AudioRecord.getTimestamp) is compared with that time before each chunk: when the sound fell behind by more than
     * TOLERANCE_US (an overrun, a chunk the encoder had no room for, a slow microphone clock) the gap is filled with silence,
     * and when it ran ahead (a fast microphone clock) frames are left out. The samples are corrected, never the times.
     */
    public static final class Clock {
        /** How far the sound may drift from when it was captured before it is corrected (lip sync shows from about 45ms). */
        public static final long TOLERANCE_US = 20_000;
        /** A longer gap than this is not filled with silence: the sound starts again where it really is. */
        public static final long MAX_FILL_US = 2_000_000;
        private final int rate;
        private long anchor = Long.MIN_VALUE, frames;
        public Clock(int rate) { this.rate = rate; }
        public boolean started() { return anchor != Long.MIN_VALUE; }
        /**
         * Before a chunk of count frames whose first frame was captured at capturedUs: how many frames to change. Above 0,
         * that many frames of silence go before it; below 0, that many of its first frames are left out (at most the chunk);
         * 0, it goes as it is. The first call sets where the sound starts.
         */
        public int correction(long capturedUs, int count) {
            if (!started()) { anchor = capturedUs; frames = 0; return 0; }
            long drift = capturedUs - now();
            if (Math.abs(drift) <= TOLERANCE_US) return 0;
            if (drift > MAX_FILL_US) { anchor = capturedUs; frames = 0; return 0; }
            return (int) Math.max(-count, drift * rate / 1_000_000);
        }
        /** The time of the next count frames handed out; the clock moves past them. */
        public long next(int count) { long pts = now(); advance(count); return pts; }
        /** count frames were handed out (only what the encoder took: what it had no room for shows as a gap next time). */
        public void advance(int count) { frames += Math.max(0, count); }
        /** The time of the next frame to hand out. */
        public long now() { return anchor + frames * 1_000_000L / rate; }
    }

    /**
     * The file's timeline: it begins at the first video key frame (time 0) and sound from before it is left out. Video times
     * pass as the encoder gave them (an encoder that still makes B-frames gives them out of order, and the muxer keeps that
     * order); only an exact repeat of the last time is moved on by 1µs. Sound only goes forward: a time at or before the
     * last one is left out (the clock keeps it continuous, so that does not happen in a normal recording).
     */
    public static final class Timeline {
        private long base = -1, lastVideo = -1, maxVideo = -1, lastAudio = -1;
        public boolean started() { return base >= 0; }
        /** A video sample's time in the file, or -1 to leave it out (before the first key frame). */
        public long video(long ptsUs, boolean key) {
            if (base < 0) { if (!key) return -1; base = ptsUs; }
            if (ptsUs < base) return -1;
            long t = ptsUs - base;
            if (t == lastVideo) t = lastVideo + 1;
            lastVideo = t; maxVideo = Math.max(maxVideo, t);
            return t;
        }
        /** An audio sample's time in the file, or -1 to leave it out (before the first video frame, or not after the last). */
        public long audio(long ptsUs) {
            if (base < 0 || ptsUs < base) return -1;
            long t = ptsUs - base;
            if (t <= lastAudio) return -1;
            return lastAudio = t;
        }
        /** How long the file is so far, in microseconds. */
        public long duration() { return Math.max(0, Math.max(maxVideo, lastAudio)); }
    }

    /**
     * A first-in first-out store of samples, for the phone's sound in 둘 다 (read without waiting, mixed into the microphone
     * as it comes). It holds a small cushion so a period that arrives a little late does not leave a hole in the middle of
     * the sound, and drops the oldest excess so the sound stays close to now.
     */
    public static final class Fifo {
        private final short[] buffer;
        private final int prime, ceiling;
        private int head, size;
        private boolean primed;
        public Fifo(int capacity) { this(capacity, 0, capacity); }
        /** prime: samples held before the first take and again after it ran dry; ceiling: above it the oldest excess goes. */
        public Fifo(int capacity, int prime, int ceiling) {
            buffer = new short[capacity]; this.prime = Math.max(0, Math.min(prime, capacity)); this.ceiling = Math.max(this.prime, Math.min(ceiling, capacity));
        }
        public int size() { return size; }
        /** Adds count samples; when full, the oldest are dropped. */
        public void put(short[] source, int count) {
            for (int i = 0; i < count; i++) {
                if (size == buffer.length) { head = (head + 1) % buffer.length; size--; }
                buffer[(head + size) % buffer.length] = source[i]; size++;
            }
        }
        /**
         * Takes count samples into target and returns how many were real; the rest of target up to count is silence. Until
         * the cushion (prime) has gathered, before the first take and again after it ran dry, it gives silence. Above
         * ceiling, the oldest samples go first, down to the cushion.
         */
        public int take(short[] target, int count) {
            if (prime > 0 && !primed) {
                if (size < prime + count) { for (int i = 0; i < count; i++) target[i] = 0; return 0; }
                primed = true;
            }
            if (size - count > ceiling) { int drop = size - count - prime; head = (head + drop) % buffer.length; size -= drop; }
            int n = Math.min(count, size);
            for (int i = 0; i < n; i++) { target[i] = buffer[head]; head = (head + 1) % buffer.length; }
            size -= n;
            for (int i = n; i < count; i++) target[i] = 0;
            if (n < count) primed = false;
            return n;
        }
    }
}
