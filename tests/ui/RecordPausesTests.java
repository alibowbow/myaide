package dev.jun.gyeot;

import dev.jun.gyeot.core.RecordPauses;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 녹화·녹음의 일시정지 (준: "녹화 시작 일시정지 및 정지 버튼도 구현해줘"): while paused nothing is written, and the paused span is cut
 * out of the times, so the file plays on without a hole and goes on in the same file after 재개. Pure Java, no Android.
 */
public class RecordPausesTests {
    @Test public void withoutAPauseEveryTimeStaysAsItIs() {
        RecordPauses p = new RecordPauses();
        assertFalse(p.paused());
        assertEquals(0, p.map(0)); assertEquals(1_234_567, p.map(1_234_567));
        assertEquals(5_000, p.elapsed(1_000, 6_000));
    }

    @Test public void aPausedSpanIsLeftOutAndWhatComesAfterMovesUpByIt() {
        RecordPauses p = new RecordPauses();
        assertTrue(p.pause(1_000_000)); assertTrue(p.paused());
        assertFalse("paused already", p.pause(1_500_000));
        assertEquals("before the pause, as it was", 999_999, p.map(999_999));
        assertEquals("taken while paused: left out", -1, p.map(1_000_000));
        assertEquals(-1, p.map(9_000_000));
        assertTrue(p.resume(3_000_000)); assertFalse(p.paused());
        assertFalse("not paused", p.resume(3_100_000));
        assertEquals(-1, p.map(2_999_999));
        assertEquals("the first sample after 재개 follows the last one before the pause", 1_000_000, p.map(3_000_000));
        assertEquals(1_500_000, p.map(3_500_000));
        // A second pause: both spans cut out.
        p.pause(4_000_000); p.resume(4_250_000);
        assertEquals(1_999_999, p.map(3_999_999));
        assertEquals(-1, p.map(4_100_000));
        assertEquals(2_000_000, p.map(4_250_000));
        assertEquals(2_750_000, p.map(5_000_000));
    }

    @Test public void theFilePlaysOnWithoutAHoleAcrossAPause() {
        // 30fps of picture and AAC frames of sound (1024 samples at 48kHz), paused for 7.3 seconds in the middle.
        RecordPauses p = new RecordPauses();
        long frame = 33_333, aac = 1024L * 1_000_000 / 48_000, start = 10_000_000, pause = start + 4_000_000, resume = pause + 7_300_000, end = resume + 4_000_000;
        long lastVideo = -1, maxGap = 0, lastAudio = -1, maxAudioGap = 0, firstAfter = -1;
        long nextVideo = start, nextAudio = start;
        boolean paused = false, asked = false;
        for (long now = start; now < end; now += 1_000) {
            if (!paused && now >= pause && now < resume) { p.pause(now); paused = true; }
            if (paused && now >= resume) { p.resume(now); paused = false; }
            if (now >= nextVideo) {
                // A key frame every 2 seconds, and the one the recorder asks for at 재개.
                boolean key = (nextVideo - start) % 2_000_000 < frame || nextVideo >= resume && !asked;
                if (nextVideo >= resume) asked = true;
                long t = p.video(nextVideo, key);
                if (t >= 0) {
                    if (lastVideo >= 0) { assertTrue("times only go forward", t > lastVideo); maxGap = Math.max(maxGap, t - lastVideo); }
                    if (firstAfter < 0 && nextVideo >= resume) firstAfter = t;
                    lastVideo = t;
                }
                nextVideo += frame;
            }
            if (now >= nextAudio) {
                long t = p.audio(nextAudio);
                if (t >= 0) { if (lastAudio >= 0) { assertTrue(t > lastAudio); maxAudioGap = Math.max(maxAudioGap, t - lastAudio); } lastAudio = t; }
                nextAudio += aac;
            }
        }
        long total = end - start - 7_300_000;
        assertTrue("the file is as long as what was recorded, not with the pause: " + lastVideo, Math.abs(lastVideo - (start + total - frame)) <= 2 * frame);
        assertTrue("the sound too: " + lastAudio, Math.abs(lastAudio - (start + total - aac)) <= 2 * aac);
        assertTrue("no hole where it was paused (picture): " + maxGap, maxGap <= 2 * frame);
        assertTrue("no hole where it was paused (sound): " + maxAudioGap, maxAudioGap <= 2 * aac);
        assertTrue("the picture goes on right after 재개: " + firstAfter, firstAfter >= pause && firstAfter - pause <= 2 * frame);
    }

    @Test public void afterAPictureWasLeftOutTheNextOneWrittenIsAKeyFrame() {
        // An encoder that does not stop while paused: its frames are left out, and the ones after point back to them, so
        // the picture starts again at the next key frame (the recorder asks for one at 재개).
        RecordPauses p = new RecordPauses();
        assertEquals(100, p.video(100, true));
        assertEquals(200, p.video(200, false));
        p.pause(300);
        assertEquals(-1, p.video(300, false));
        p.resume(1_300);
        assertEquals("points back to a frame left out", -1, p.video(1_400, false));
        assertEquals("the key frame asked for at 재개", 500, p.video(1_500, true));
        assertEquals(600, p.video(1_600, false));
        // An encoder that did stop (nothing was left out) goes straight on.
        RecordPauses q = new RecordPauses();
        assertEquals(100, q.video(100, true));
        q.pause(150); q.resume(1_150);
        assertEquals(200, q.video(1_200, false));
    }

    @Test public void theTimeShownLeavesThePausesOut() {
        RecordPauses p = new RecordPauses();
        long start = 1_000;
        p.pause(11_000);
        assertEquals("while paused the time stands still", 10_000, p.elapsed(start, 11_000));
        assertEquals(10_000, p.elapsed(start, 25_000));
        assertEquals(14_000, p.pausedTotal(25_000));
        p.resume(31_000);
        assertEquals(20_000, p.pausedTotal(40_000));
        assertEquals(19_000, p.elapsed(start, 40_000));
        assertEquals("0:00", RecordPauses.clock(0));
        assertEquals("0:07", RecordPauses.clock(7_999));
        assertEquals("12:34", RecordPauses.clock((12 * 60 + 34) * 1000L));
        assertEquals("1:02:03", RecordPauses.clock((3600 + 2 * 60 + 3) * 1000L));
    }
}
