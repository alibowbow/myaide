package dev.jun.gyeot.core;

import java.util.Arrays;

/**
 * One thing said, cut from the microphone (16 kHz mono): it opens when 30 ms of sound stand 9 dB above the room's quiet
 * (keeping the 0.3 s before), and closes after 0.8 s of quiet once at least a quarter second was spoken, or at MAX. Nothing
 * said within the wait: silent. The room's quiet follows the quietest recent 10 ms (it drops at once, rises slowly, and
 * hardly at all while someone speaks), so a steady hum does not count as speech and a long sentence is not cut. For 퍼레's voice talk, where the words go to Whisper and the voice to VoicePrint, and
 * for reading the sentences that enroll the user's voice. Pure Java.
 */
public final class Utterance {
    public enum State { WAITING, SPEAKING, DONE, SILENT }
    static final int BLOCK = 160, PRE = 30, START = 3, QUIET = 80, MIN_SPEECH = 25, MAX = 1500;
    static final float SPEECH_DB = 9f, FLOOR_DB = -62f;
    private final int waitBlocks;
    private short[] pcm = new short[WakeWord.RATE * 4]; private int length;
    private final short[] block = new short[BLOCK]; private int filled;
    private float floor = Float.NaN; private int loud, quiet, speech, blocks, opened = -1;
    private State state = State.WAITING;

    /** waitMs: how long nothing said ends it as silent. */
    public Utterance(long waitMs) { this.waitBlocks = (int) Math.max(1, waitMs / 10); }
    public State state() { return state; }
    /** What was said, from just before it opened to its end (empty until DONE). */
    public short[] pcm() { return state == State.DONE ? Arrays.copyOf(pcm, length) : new short[0]; }
    /** Seconds of speech heard so far (for a level shown while listening). */
    public double spoken() { return speech / 100.0; }

    /** Feeds samples as they come; the state after them. */
    public State add(short[] samples, int count) {
        for (int i = 0; i < count && (state == State.WAITING || state == State.SPEAKING); i++) {
            block[filled++] = samples[i];
            if (filled == BLOCK) { filled = 0; step(); }
        }
        return state;
    }
    private void step() {
        blocks++;
        double sum = 0; for (short s : block) sum += (double) s * s;
        float e = (float) (10 * Math.log10(sum / BLOCK / (32768.0 * 32768.0) + 1e-10));
        if (Float.isNaN(floor)) floor = e;
        boolean voiced = e > floor + SPEECH_DB && e > FLOOR_DB;
        // Speech barely lifts the floor (a long sentence must not become the room's quiet); a quiet room's own sound does.
        floor = e < floor ? e : floor + (voiced ? 0.0005f : 0.02f) * (e - floor);
        append(block);
        if (state == State.WAITING) {
            loud = voiced ? loud + 1 : 0;
            // Only the last PRE blocks wait in the buffer.
            if (length > PRE * BLOCK) { System.arraycopy(pcm, length - PRE * BLOCK, pcm, 0, PRE * BLOCK); length = PRE * BLOCK; }
            if (loud >= START) { state = State.SPEAKING; opened = blocks; speech = loud; quiet = 0; }
            else if (blocks >= waitBlocks) state = State.SILENT;
            return;
        }
        if (voiced) { speech++; quiet = 0; } else quiet++;
        if (quiet >= QUIET) {
            if (speech >= MIN_SPEECH) { length -= (QUIET - 20) * BLOCK; state = State.DONE; }
            // A knock or a cough: back to waiting.
            else { state = blocks >= waitBlocks ? State.SILENT : State.WAITING; loud = 0; speech = 0; }
        } else if (blocks - opened >= MAX) state = State.DONE;
    }
    private void append(short[] b) {
        if (length + b.length > pcm.length) pcm = Arrays.copyOf(pcm, pcm.length * 2);
        System.arraycopy(b, 0, pcm, length, b.length); length += b.length;
    }

    /** The samples as a WAV file (16-bit mono PCM). */
    public static byte[] wav(short[] samples, int rate) {
        int data = samples.length * 2; byte[] out = new byte[44 + data];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(data);
        for (short s : samples) b.putShort(s);
        return out;
    }
}
