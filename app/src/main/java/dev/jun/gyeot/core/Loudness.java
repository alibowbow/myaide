package dev.jun.gyeot.core;

/**
 * 작게 말해도 알아듣기: soft speech raised toward a steady level before it goes to the voice model, and only speech. The room's
 * own noise (its level is followed as it changes) is never raised, so a fan, a car or music is not blown up into something
 * the model takes for words. Down at once for loud speech, up gently for soft; never past CEILING.
 */
public final class Loudness {
    private static final int TARGET = 9000, CEILING = 28000, SPEECH_PEAK = 700;
    private static final double MAX_GAIN = 2.5, ABOVE_NOISE = 2.5;
    private double gain = 1.0, noise = -1;
    public double gain() { return gain; }

    public byte[] apply(byte[] pcm) {
        int samples = pcm.length / 2, peak = 0; double sum = 0;
        for (int i = 0; i < samples; i++) { int s = sample(pcm, i); peak = Math.max(peak, Math.abs(s)); sum += (double) s * s; }
        if (samples == 0) return pcm;
        double rms = Math.sqrt(sum / samples);
        // The room's level: it falls quickly to a quiet moment and rises slowly, so speech does not count as noise.
        if (noise < 0) noise = rms;
        noise = rms < noise ? noise + (rms - noise) * 0.3 : noise + (rms - noise) * 0.01;
        boolean speech = peak > SPEECH_PEAK && rms > Math.max(120, noise * ABOVE_NOISE);
        if (speech) {
            double wanted = Math.max(1.0, Math.min(MAX_GAIN, (double) TARGET / peak));
            gain = wanted < gain ? wanted : gain + (wanted - gain) * 0.15;
        } else gain += (1.0 - gain) * 0.4; // noise between words: back toward its own level
        if (peak * gain > CEILING) gain = Math.max(1.0, (double) CEILING / Math.max(1, peak));
        if (gain <= 1.01) return pcm;
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < samples; i++) {
            int value = (int) Math.round(sample(pcm, i) * gain);
            value = Math.max(-32768, Math.min(32767, value));
            out[2 * i] = (byte) value; out[2 * i + 1] = (byte) (value >> 8);
        }
        if ((pcm.length & 1) == 1) out[pcm.length - 1] = pcm[pcm.length - 1];
        return out;
    }
    private static int sample(byte[] pcm, int index) { return (short) ((pcm[2 * index] & 0xff) | (pcm[2 * index + 1] << 8)); }
}
