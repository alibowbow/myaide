package dev.jun.gyeot.core;

/**
 * Gentle automatic gain for quiet speech in 16-bit little-endian PCM: soft input is raised up to 4x, the gain falls at once
 * when speech gets loud so nothing clips, and silence keeps the current gain instead of pumping up the noise floor.
 */
public final class Loudness {
    private static final int TARGET = 12000, NOISE_FLOOR = 300, CEILING = 30000;
    private static final double MAX_GAIN = 4.0;
    private double gain = 1.0;
    public double gain() { return gain; }

    public byte[] apply(byte[] pcm) {
        int samples = pcm.length / 2, peak = 0;
        for (int i = 0; i < samples; i++) peak = Math.max(peak, Math.abs(sample(pcm, i)));
        if (peak > NOISE_FLOOR) {
            double wanted = Math.max(1.0, Math.min(MAX_GAIN, (double) TARGET / peak));
            // Down at once for loud speech, up slowly for soft speech.
            gain = wanted < gain ? wanted : gain + (wanted - gain) * 0.2;
            if (peak * gain > CEILING) gain = Math.max(1.0, (double) CEILING / peak);
        }
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
