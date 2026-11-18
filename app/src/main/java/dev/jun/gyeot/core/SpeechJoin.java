package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * Joins pieces of speech that were made apart (a script read a sentence at a time) so they sound like one reading.
 * Each piece comes with its own stretch of silence before and after it, a different length every time; played as
 * they are, sentences follow one another now too soon and now too late. Here the silence at both ends of a piece is
 * taken off as its sound passes through, and a pause of one length is put after it: the breath a reader takes after a
 * sentence, a shorter one where a long sentence was cut. Silence inside a piece is left as it is.
 * Sound is 16-bit mono PCM at 24 kHz. Not thread-safe.
 */
public final class SpeechJoin {
    public static final int RATE = 24000;
    /** The pause after a sentence, and after a piece that is not the end of one. */
    public static final int SENTENCE_MS = 340, CLAUSE_MS = 130;
    static final int FRAME = RATE / 100 * 2, QUIET = 220, LEAD = 3, TAIL = 4;
    private final ByteArrayOutputStream rest = new ByteArrayOutputStream(), held = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> lead = new ArrayDeque<>();
    private boolean started;

    /** The pause a piece is followed by: a sentence's when it ends one, a clause's otherwise. */
    public static int pause(String piece) {
        String text = piece == null ? "" : piece.strip();
        while (!text.isEmpty() && "\"'”’)]』」".indexOf(text.charAt(text.length() - 1)) >= 0) text = text.substring(0, text.length() - 1);
        return !text.isEmpty() && ".?!。？！…".indexOf(text.charAt(text.length() - 1)) >= 0 ? SENTENCE_MS : CLAUSE_MS;
    }
    public static int millis(int bytes) { return (int) (bytes * 1000L / (RATE * 2)); }

    /** More of the piece's sound; what of it can be played now (nothing while it is still silent, or silent again). */
    public byte[] feed(byte[] pcm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (pcm == null || pcm.length == 0) return out.toByteArray();
        rest.write(pcm, 0, pcm.length);
        byte[] all = rest.toByteArray(); int at = 0;
        for (; at + FRAME <= all.length; at += FRAME) {
            byte[] frame = java.util.Arrays.copyOfRange(all, at, at + FRAME);
            if (quiet(frame, frame.length)) {
                // Before the first sound only the last moment of quiet is kept; after it, quiet waits to see what follows.
                if (!started) { lead.add(frame); if (lead.size() > LEAD) lead.poll(); } else held.write(frame, 0, FRAME);
                continue;
            }
            if (!started) { started = true; for (byte[] before : lead) out.write(before, 0, before.length); lead.clear(); }
            else if (held.size() > 0) { byte[] waited = held.toByteArray(); out.write(waited, 0, waited.length); held.reset(); }
            out.write(frame, 0, FRAME);
        }
        rest.reset(); rest.write(all, at, all.length - at);
        return out.toByteArray();
    }
    /** The piece is over: the last of its sound, without the silence it ended in, and the pause that follows it. */
    public byte[] end(int pauseMs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] last = rest.toByteArray(); int whole = last.length - last.length % 2;
        if (started && whole > 0 && !quiet(last, whole)) { byte[] waited = held.toByteArray(); out.write(waited, 0, waited.length); held.reset(); out.write(last, 0, whole); }
        // A moment of the quiet the voice fell into, so its last sound is not cut short.
        byte[] tail = held.toByteArray(); out.write(tail, 0, Math.min(tail.length, TAIL * FRAME));
        boolean heard = started; rest.reset(); held.reset(); lead.clear(); started = false;
        if (!heard) return new byte[0];
        byte[] pause = new byte[Math.max(0, pauseMs) * RATE / 1000 * 2]; out.write(pause, 0, pause.length);
        return out.toByteArray();
    }
    private static boolean quiet(byte[] pcm, int length) {
        for (int i = 0; i + 1 < length; i += 2) { int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)); if (sample > QUIET || sample < -QUIET) return false; }
        return true;
    }
}
