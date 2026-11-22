package dev.jun.gyeot.core;

/**
 * 목소리 크기: the assistant's voice brought up to a steady level just before it plays. Voices come at different levels
 * (GPT's quieter than others), and through a car on the motorway a quiet one is lost under the road. Speech quieter than
 * the level asked for is raised, by up to MAX_GAIN; louder speech is never turned down. A peak limiter then keeps every
 * sample under CEILING, so a raised voice never clips. The level is learned from the speech itself, 10 ms at a time and
 * only from frames above QUIET: the pauses between words and sentences keep the gain as it was, so the background is
 * never pumped up. The level rises at once with louder speech and falls back slowly, so soft syllables keep their place
 * under the loud ones. 16-bit mono little-endian PCM at RATE, one stream: not thread-safe (the speaker's writer thread).
 * Pure Java; no Android.
 */
public final class VoiceLevel {
    /** OFF: as it comes. EVEN: quiet voices raised to TARGET_EVEN, and to TARGET_LOUD into a car or a Bluetooth speaker. LOUD: TARGET_LOUD everywhere. */
    public enum Mode { OFF, EVEN, LOUD }
    public static final int RATE = 24_000;
    /** One level measurement: 10 ms. */
    static final int FRAME = RATE / 100;
    /** The level wanted (16-bit RMS of the speech's loud frames): about -16 dBFS, and about -12 dBFS. */
    static final double TARGET_EVEN = 5200, TARGET_LOUD = 8200;
    /** At most this much louder (×4, 12 dB): a voice that quiet is mostly room or line noise. */
    static final double MAX_GAIN = 4.0;
    /** A frame quieter than this (16-bit RMS, about -40 dBFS) is a pause: it teaches nothing about the voice's level. */
    static final double QUIET = 330;
    /** No sample goes above this (about -1 dBFS). */
    static final double CEILING = 29_000;
    /** Per 10 ms frame: how far the level follows louder speech (at once) and quieter speech (slowly, over about a second). */
    static final double RISE = 0.5, FALL = 0.01;
    /** Per sample: how fast the gain comes down (about 4 ms) and goes up (about 200 ms). */
    static final double GAIN_DOWN = 0.01, GAIN_UP = 0.0002;
    /** Per sample: how the limiter lets go after a peak (about 50 ms). */
    static final double RELEASE = 0.99917;

    /** The voice's level so far (16-bit RMS squared); NaN until speech is heard. */
    private double level = Double.NaN;
    private double gain = 1.0, peak = 0;

    /** The level the mode asks for (16-bit RMS); 0 for OFF. intoCar: the voice plays into a car or a Bluetooth speaker. */
    public static double target(Mode mode, boolean intoCar) {
        return switch (mode == null ? Mode.OFF : mode) {
            case OFF -> 0;
            case EVEN -> intoCar ? TARGET_LOUD : TARGET_EVEN;
            case LOUD -> TARGET_LOUD;
        };
    }
    /** The gain now applied (1 when nothing is raised), for the phone log and tests. */
    public double gain() { return gain; }

    /**
     * The chunk as it should play: the same array for OFF, else a new one. The gain carries on from the chunk before, so
     * consecutive chunks of one answer (and the answers after it) join without a step.
     */
    public byte[] apply(byte[] pcm, Mode mode, boolean intoCar) {
        double wanted = target(mode, intoCar);
        if (wanted <= 0 || pcm == null || pcm.length < 2) return pcm;
        int samples = pcm.length / 2;
        byte[] out = new byte[pcm.length];
        for (int start = 0; start < samples; start += FRAME) {
            int end = Math.min(samples, start + FRAME);
            double sum = 0;
            for (int i = start; i < end; i++) { double s = sample(pcm, i); sum += s * s; }
            double power = sum / (end - start);
            boolean first = Double.isNaN(level) && power > QUIET * QUIET;
            if (power > QUIET * QUIET) level = first ? power : level + (power - level) * (power > level ? RISE : FALL);
            double goal = Double.isNaN(level) ? 1.0 : Math.max(1.0, Math.min(MAX_GAIN, wanted / Math.sqrt(level)));
            // The first speech heard sets the gain at once, so the start of the first answer is not left quiet.
            if (first) gain = goal;
            for (int i = start; i < end; i++) {
                gain += (goal - gain) * (goal < gain ? GAIN_DOWN : GAIN_UP);
                double y = sample(pcm, i) * gain, a = Math.abs(y);
                // Never below this sample, so the limited sample is at most CEILING.
                peak = Math.max(a, peak * RELEASE);
                if (peak > CEILING) y *= CEILING / peak;
                int value = (int) Math.round(y);
                value = Math.max(-32768, Math.min(32767, value));
                out[2 * i] = (byte) value; out[2 * i + 1] = (byte) (value >> 8);
            }
        }
        if ((pcm.length & 1) == 1) out[pcm.length - 1] = pcm[pcm.length - 1];
        return out;
    }
    private static int sample(byte[] pcm, int index) { return (short) ((pcm[2 * index] & 0xff) | (pcm[2 * index + 1] << 8)); }
}
