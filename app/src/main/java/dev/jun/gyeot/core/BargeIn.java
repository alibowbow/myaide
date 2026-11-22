package dev.jun.gyeot.core;

/**
 * While the assistant speaks through the phone's own speaker, the microphone hears its echo as well as the user. The call
 * does not send that sound (it would cut its own answer short), so a user who speaks over it would not be heard. This tells
 * the user's voice from the echo: each microphone frame is set against the speech playing at that moment, scaled by how
 * loud the echo has been in frames where only the echo was heard. A frame clearly louder than that is the user; enough of
 * them close together, and the user is speaking over the answer. Pure Java; one per call, any one thread at a time.
 */
public final class BargeIn {
    /** Below this (16-bit RMS) nothing counts as a voice: room noise, a breath. */
    static final double FLOOR = 600;
    /** How much louder than the loudest echo lately a frame must be to be the user (2 ≈ 6 dB). */
    static final double MARGIN = 2.0;
    /** The echo ratio assumed before any has been measured: high, so an unmeasured echo is never taken for the user. */
    static final double START_RATIO = 3.0;
    /** Playback quieter than this (16-bit RMS) leaves no echo to measure: there, any voice above FLOOR is the user's. */
    static final double QUIET_PLAYBACK = 200;
    /** Echo-only frames remembered (about five seconds of 100 ms frames), and how many are needed before they are trusted. */
    static final int WINDOW = 50, LEARNED = 5;
    /** How long the user must speak (ms, a short gap forgiven) before the answer stops: a cough or a knock is shorter. */
    static final int NEEDED_MS = 400;

    private final double[] ratios = new double[WINDOW];
    private int count, next;
    private double score;

    /** The RMS of 16-bit little-endian PCM. */
    public static double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return 0;
        long sum = 0; int samples = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) { int s = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8)); sum += (long) s * s; }
        return Math.sqrt(sum / (double) samples);
    }

    /**
     * One microphone frame: its RMS, the loudest speech the speaker played around it (16-bit RMS) and its length. True once the
     * user has spoken over the answer long enough; reset() before listening for the next time.
     */
    public synchronized boolean hear(double mic, double playing, int ms) {
        boolean measurable = playing >= QUIET_PLAYBACK;
        double echo = measurable ? ratio() * playing : 0;
        boolean voice = mic > Math.max(FLOOR, MARGIN * echo);
        // Only the echo teaches what the echo sounds like: a frame taken for the user's voice is never learned.
        if (!voice && measurable) { ratios[next] = mic / playing; next = (next + 1) % WINDOW; count++; }
        score = voice ? score + ms : Math.max(0, score - ms / 2.0);
        return score >= NEEDED_MS;
    }
    /** The loudest echo, against what was playing, in recent echo-only frames (START_RATIO until enough were heard). */
    double ratio() {
        if (count < LEARNED) return START_RATIO;
        double max = 0; for (int i = 0; i < Math.min(count, WINDOW); i++) max = Math.max(max, ratios[i]);
        return max;
    }
    /** Starts listening afresh for the next time (what the echo sounds like is kept). */
    public synchronized void reset() { score = 0; }
}
