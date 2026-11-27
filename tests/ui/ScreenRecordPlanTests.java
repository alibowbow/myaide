package dev.jun.gyeot;

import dev.jun.gyeot.core.ScreenRecordAudio;
import dev.jun.gyeot.core.ScreenRecordPlan;
import dev.jun.gyeot.core.ScreenRecordPlan.Limits;
import dev.jun.gyeot.core.ScreenRecordPlan.Plan;
import dev.jun.gyeot.core.ScreenRecordPlan.Quality;
import dev.jun.gyeot.core.ScreenRecordPlan.Sound;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 화면 녹화's plan (준: "전체화면 녹화시 화질을 최고로 해도 저화질로 저장돼"): the screen at its own size in HEVC at 60fps and a
 * high bitrate, smaller only when the encoder cannot take it, and the sound's timing. Pure Java, no Android.
 */
public class ScreenRecordPlanTests {
    private static final String HEVC = ScreenRecordPlan.HEVC, AVC = ScreenRecordPlan.AVC;
    /** An encoder taking sizes up to maxWidth×maxHeight (not swapped), pixelRate pixels a second, bitrates up to maxBitrate. */
    private static Limits encoder(String name, String mime, boolean hardware, int align, int maxWidth, int maxHeight, long pixelRate, int maxBitrate) {
        return new Limits(name, mime, hardware, align, align, 96, maxWidth, 96, maxHeight, (long) maxWidth * maxHeight, pixelRate, 1_000, maxBitrate, true);
    }
    private static final long UHD60 = 3840L * 2160 * 60;
    /** A recent flagship: H.264 and HEVC in hardware, the software ones beside them. */
    private static final List<Limits> FLAGSHIP = List.of(
        encoder("c2.android.avc.encoder", AVC, false, 2, 4096, 4096, UHD60, 160_000_000),
        encoder("c2.qti.avc.encoder", AVC, true, 2, 8192, 8192, UHD60, 160_000_000),
        encoder("c2.qti.hevc.encoder", HEVC, true, 2, 8192, 8192, UHD60, 160_000_000));

    @Test public void aTypicalPhoneRecordsItsWholeScreenInHevcAt60() {
        Plan plan = ScreenRecordPlan.best(1440, 3120, Quality.BEST, FLAGSHIP);
        assertEquals("c2.qti.hevc.encoder", plan.encoder());
        assertEquals("never scaled down", 1440, plan.width()); assertEquals(3120, plan.height());
        assertEquals(60, plan.fps()); assertEquals(60f, plan.maxFps(), 0.001f);
        // 0.18 bits per pixel per frame: 48.5Mbps, in whole megabits.
        assertEquals(48_000_000, plan.bitrate());
        assertTrue(plan.vbr());
        assertEquals("1440×3120 · 60fps · HEVC 48Mbps", plan.label());
        assertEquals("1440×3120 · 60fps · HEVC 48Mbps · 기기 소리", ScreenRecordPlan.summary(plan, Sound.DEVICE));
        assertEquals("1440×3120 · 60fps · HEVC 48Mbps · 소리 없음", ScreenRecordPlan.summary(plan, Sound.NONE));
        // Landscape stays landscape.
        Plan wide = ScreenRecordPlan.best(3120, 1440, Quality.BEST, FLAGSHIP);
        assertEquals(3120, wide.width()); assertEquals(1440, wide.height());
    }

    @Test public void h264HighWhenThereIsNoHardwareHevc() {
        List<Limits> phone = List.of(encoder("c2.android.hevc.encoder", HEVC, false, 2, 4096, 4096, UHD60, 40_000_000),
            encoder("c2.exynos.h264.encoder", AVC, true, 2, 4096, 4096, UHD60, 120_000_000));
        Plan plan = ScreenRecordPlan.best(1080, 2340, Quality.BEST, phone);
        assertEquals("a software HEVC encoder cannot keep up", "c2.exynos.h264.encoder", plan.encoder());
        assertEquals(AVC, plan.mime()); assertEquals("H.264", plan.codec());
        assertEquals(1080, plan.width()); assertEquals(2340, plan.height()); assertEquals(60, plan.fps());
        // H.264 needs half as many bits again for the same picture: 1080×2340×60×0.27 = 40.9Mbps, not HEVC's 27.
        assertEquals(40_000_000, plan.bitrate());
        // Software encoders only when there is nothing else (an emulator), H.264 first.
        List<Limits> emulator = List.of(encoder("c2.android.hevc.encoder", HEVC, false, 2, 4096, 4096, UHD60, 40_000_000),
            encoder("c2.android.avc.encoder", AVC, false, 2, 4096, 4096, UHD60, 40_000_000));
        assertEquals("c2.android.avc.encoder", ScreenRecordPlan.best(1080, 2340, Quality.BEST, emulator).encoder());
    }

    @Test public void theBiggerPictureWinsOverHevc() {
        // This HEVC encoder takes 2304 lines at most (4096×2304, not turned); H.264 takes the whole screen.
        List<Limits> phone = List.of(encoder("hevc", HEVC, true, 2, 4096, 2304, UHD60, 160_000_000), encoder("avc", AVC, true, 2, 4096, 4096, UHD60, 160_000_000));
        List<Plan> plans = ScreenRecordPlan.attempts(1440, 3120, Quality.BEST, phone);
        assertEquals("avc", plans.get(0).encoder()); assertEquals(1440, plans.get(0).width()); assertEquals(3120, plans.get(0).height());
        assertEquals("hevc", plans.get(1).encoder());
    }

    @Test public void aCappedHevcEncoderLosesToAFullRateH264One() {
        // The HEVC encoder tops out at 20Mbps, well under the 48 the screen needs; H.264 takes its full 72.
        List<Limits> phone = List.of(encoder("hevc", HEVC, true, 2, 8192, 8192, UHD60, 20_000_000), encoder("avc", AVC, true, 2, 8192, 8192, UHD60, 160_000_000));
        List<Plan> plans = ScreenRecordPlan.attempts(1440, 3120, Quality.BEST, phone);
        assertEquals("avc", plans.get(0).encoder()); assertEquals(72_000_000, plans.get(0).bitrate());
        assertEquals("then the capped HEVC at the same size", "hevc", plans.get(1).encoder()); assertEquals(20_000_000, plans.get(1).bitrate());
        // Both at their full rate: HEVC first.
        List<Limits> both = List.of(encoder("hevc", HEVC, true, 2, 8192, 8192, UHD60, 160_000_000), encoder("avc", AVC, true, 2, 8192, 8192, UHD60, 160_000_000));
        assertEquals("hevc", ScreenRecordPlan.best(1440, 3120, Quality.BEST, both).encoder());
    }

    @Test public void tooTallForTheEncoderTakesTheLargestSizeOfTheSameShape() {
        List<Limits> phone = List.of(encoder("avc", AVC, true, 2, 4096, 2304, UHD60, 160_000_000));
        Plan plan = ScreenRecordPlan.best(1440, 3120, Quality.BEST, phone);
        assertEquals(2304, plan.height());
        assertEquals(1064, plan.width());
        assertEquals("same shape", 1440 / 3120.0, plan.width() / (double) plan.height(), 1440 / 3120.0 * 0.01);
        assertEquals(0, plan.width() % 2);
    }

    @Test public void alignmentPadsAFewPixelsRatherThanScaling() {
        // 1080 is not a multiple of 16: 1088 wide keeps every pixel (4 black pixels each side) instead of resampling to 1072.
        List<Limits> sixteen = List.of(encoder("avc16", AVC, true, 16, 4096, 4096, UHD60, 160_000_000));
        int[] padded = ScreenRecordPlan.fit(sixteen.get(0), 1080, 2400);
        assertArrayEquals(new int[]{1088, 2400}, padded);
        // When the padded width is too wide for the encoder, the aligned size just under it.
        Limits narrow = new Limits("avc16narrow", AVC, true, 16, 16, 96, 1080, 96, 4096, 1080L * 4096, UHD60, 1_000, 160_000_000, true);
        assertArrayEquals(new int[]{1072, 2400}, ScreenRecordPlan.fit(narrow, 1080, 2400));
        assertTrue("one side padded keeps every pixel", ScreenRecordPlan.exact(1088, 2400, 1080, 2400));
        // Both sides need padding (1080×2340 at 16): Android scales the screen up 0.5% to fit, so every pixel is resampled.
        assertArrayEquals(new int[]{1088, 2352}, ScreenRecordPlan.fit(sixteen.get(0), 1080, 2340));
        assertFalse(ScreenRecordPlan.exact(1088, 2352, 1080, 2340));
        assertFalse("smaller is never exact", ScreenRecordPlan.exact(1072, 2400, 1080, 2400));
        assertTrue(ScreenRecordPlan.exact(1080, 2340, 1080, 2340));
        // So an encoder that takes the screen as it is goes first, even before HEVC; where padding one side is enough, HEVC stays first.
        List<Limits> mixed = List.of(encoder("hevc16", HEVC, true, 16, 4096, 4096, UHD60, 160_000_000), encoder("avc2", AVC, true, 2, 4096, 4096, UHD60, 160_000_000));
        assertEquals("avc2", ScreenRecordPlan.best(1080, 2340, Quality.BEST, mixed).encoder());
        assertEquals("hevc16", ScreenRecordPlan.attempts(1080, 2340, Quality.BEST, mixed).get(1).encoder());
        assertEquals("hevc16", ScreenRecordPlan.best(1080, 2400, Quality.BEST, mixed).encoder());
        // Nothing fits: null, and no plan.
        Limits tiny = encoder("tiny", AVC, true, 2, 320, 100, UHD60, 160_000_000);
        assertNull(ScreenRecordPlan.fit(tiny, 1440, 3120));
        assertTrue(ScreenRecordPlan.attempts(1440, 3120, Quality.BEST, List.of(tiny)).isEmpty());
    }

    @Test public void thirtyWhenSixtyIsTooMuchAndBitratesStayInRange() {
        // The encoder manages 45 screens a second at this size: 30fps, at the same bits per pixel.
        long rate = 1440L * 3120 * 45;
        Plan slow = ScreenRecordPlan.best(1440, 3120, Quality.BEST, List.of(encoder("hevc", HEVC, true, 2, 8192, 8192, rate, 160_000_000)));
        assertEquals(30, slow.fps());
        assertEquals(24_000_000, slow.bitrate());
        // Not even 24 a second at the full size: that size is left out, and the next smaller one runs at 30.
        Plan slower = ScreenRecordPlan.best(1440, 3120, Quality.BEST, List.of(encoder("hevc", HEVC, true, 2, 8192, 8192, 1440L * 3120 * 20, 160_000_000)));
        assertEquals(1080, slower.width()); assertEquals(2340, slower.height()); assertEquals(30, slower.fps());
        // The encoder's top bitrate is the cap.
        Plan capped = ScreenRecordPlan.best(1440, 3120, Quality.BEST, List.of(encoder("hevc", HEVC, true, 2, 8192, 8192, UHD60, 20_000_000)));
        assertEquals(20_000_000, capped.bitrate());
        // And its lowest the floor.
        Limits floor = new Limits("hevc", HEVC, true, 2, 2, 96, 8192, 96, 8192, 8192L * 8192, UHD60, 12_000_000, 160_000_000, true);
        assertEquals(12_000_000, ScreenRecordPlan.best(720, 1560, Quality.NORMAL, List.of(floor)).bitrate());
        // Never above MAX_BITRATE, however large the screen.
        Plan huge = ScreenRecordPlan.best(7680, 4320, Quality.BEST, List.of(encoder("hevc", HEVC, true, 2, 8192, 8192, 8192L * 8192 * 60, 400_000_000)));
        assertEquals(ScreenRecordPlan.MAX_BITRATE, huge.bitrate());
        // No VBR: the encoder's own mode.
        Limits cbr = new Limits("hevc", HEVC, true, 2, 2, 96, 8192, 96, 8192, 8192L * 8192, UHD60, 1_000, 160_000_000, false);
        assertFalse(ScreenRecordPlan.best(1440, 3120, Quality.BEST, List.of(cbr)).vbr());
    }

    @Test public void theFrameRateDividesTheScreensRefreshEvenly() {
        // 60 of a 120Hz screen (every second frame), 45 of a 90Hz one (not 60 in an uneven 11ms/22ms pattern), 48 of 144Hz.
        Plan p120 = ScreenRecordPlan.best(1440, 3120, 120f, Quality.BEST, FLAGSHIP);
        assertEquals(60, p120.fps()); assertEquals(60f, p120.maxFps(), 0.001f); assertEquals(48_000_000, p120.bitrate());
        Plan p90 = ScreenRecordPlan.best(1440, 3120, 90f, Quality.BEST, FLAGSHIP);
        assertEquals(45, p90.fps()); assertEquals(45f, p90.maxFps(), 0.001f);
        assertEquals("the bitrate follows the rate", 36_000_000, p90.bitrate());
        assertEquals("1440×3120 · 45fps · HEVC 36Mbps", p90.label());
        assertEquals(48, ScreenRecordPlan.best(1440, 3120, 144f, Quality.BEST, FLAGSHIP).fps());
        // A screen reporting 59.94 or 60.0001 is 60; one reporting nothing is taken as 60.
        Plan ntsc = ScreenRecordPlan.best(1440, 3120, 59.94f, Quality.BEST, FLAGSHIP);
        assertEquals(60, ntsc.fps()); assertEquals(59.94f, ntsc.maxFps(), 0.001f);
        assertEquals(60, ScreenRecordPlan.best(1440, 3120, 60.0001f, Quality.BEST, FLAGSHIP).fps());
        assertEquals(60, ScreenRecordPlan.best(1440, 3120, 0f, Quality.BEST, FLAGSHIP).fps());
        // 보통 wants 30: 30 of 60, 90 and 120Hz alike.
        assertEquals(30, ScreenRecordPlan.best(1440, 3120, 90f, Quality.NORMAL, FLAGSHIP).fps());
        assertEquals(30, ScreenRecordPlan.best(1440, 3120, 120f, Quality.NORMAL, FLAGSHIP).fps());
        // The encoder's own limit still counts: 90Hz with an encoder that manages 40 a second here gives 30 (90/3).
        Limits forty = encoder("hevc", HEVC, true, 2, 8192, 8192, 1440L * 3120 * 40, 160_000_000);
        assertEquals(30, ScreenRecordPlan.best(1440, 3120, 90f, Quality.BEST, List.of(forty)).fps());
    }

    @Test public void levelsTakeThePlansBitrate() {
        // HEVC Main 5.1 holds 40Mbps, under the 48 a 1440×3120 screen at 60 gets: Main 5.2 (60Mbps).
        assertEquals(0x40000, ScreenRecordPlan.level(HEVC, 1440, 3120, 60, 48_000_000));
        assertEquals("Main 5.1 when the bitrate fits it", 0x10000, ScreenRecordPlan.level(HEVC, 1440, 3120, 60, 40_000_000));
        assertEquals("Main 5 at 30fps", 0x4000, ScreenRecordPlan.level(HEVC, 1080, 2340, 30, 7_500_000));
        assertEquals("Main 6.1 for 100Mbps", 0x400000, ScreenRecordPlan.level(HEVC, 1440, 3120, 60, 100_000_000));
        // H.264: 17550 macroblocks a frame at 60 a second is level 5.2; High's cap is 1.25 times the table's.
        assertEquals(0x10000, ScreenRecordPlan.level(AVC, 1440, 3120, 60, 72_000_000));
        assertEquals(0x4000, ScreenRecordPlan.level(AVC, 1080, 2340, 30, 20_000_000));
        assertEquals("8K at 60 is 6.1", 0x40000, ScreenRecordPlan.level(AVC, 7680, 4320, 60, 100_000_000));
        assertEquals("past 6.2: none", 0, ScreenRecordPlan.level(AVC, 8192, 8192, 120, 100_000_000));
        assertEquals(0, ScreenRecordPlan.level("video/av01", 1440, 3120, 60, 48_000_000));
    }

    @Test public void qualities() {
        Plan high = ScreenRecordPlan.best(1440, 3120, Quality.HIGH, FLAGSHIP);
        assertEquals(1440, high.width()); assertEquals(60, high.fps()); assertEquals(26_000_000, high.bitrate());
        Plan normal = ScreenRecordPlan.best(1440, 3120, Quality.NORMAL, FLAGSHIP);
        assertEquals(1080, normal.width()); assertEquals(2340, normal.height()); assertEquals(30, normal.fps());
        assertEquals(7_500_000, normal.bitrate()); assertEquals("1080×2340 · 30fps · HEVC 7.5Mbps", normal.label());
        // 보통 on a screen already 1080 wide keeps it.
        assertEquals(1080, ScreenRecordPlan.best(1080, 2400, Quality.NORMAL, FLAGSHIP).width());
        assertArrayEquals(new String[]{"최고", "고화질", "보통"}, Quality.labels());
        assertEquals(Quality.BEST, Quality.of("")); assertEquals(Quality.NORMAL, Quality.of("NORMAL"));
    }

    @Test public void attemptsFallBackToH264ThenToSmallerSizes() {
        List<Plan> plans = ScreenRecordPlan.attempts(1440, 3120, Quality.BEST, FLAGSHIP);
        assertEquals("HEVC first", "c2.qti.hevc.encoder", plans.get(0).encoder());
        assertEquals("then H.264 at the same size", "c2.qti.avc.encoder", plans.get(1).encoder());
        assertEquals(1440, plans.get(1).width());
        assertEquals("then the next smaller size", 1080, plans.get(2).width()); assertEquals(2340, plans.get(2).height());
        int last = Integer.MAX_VALUE;
        for (Plan p : plans) { if (p.encoder().equals("c2.qti.hevc.encoder")) { assertTrue("each smaller than the one before", p.width() < last); last = p.width(); } }
        assertTrue("software encoders are left out when there is hardware", plans.stream().noneMatch(p -> p.encoder().startsWith("c2.android")));
        for (int i = 0; i < plans.size(); i++) for (int j = i + 1; j < plans.size(); j++) assertFalse("no repeats", plans.get(i).sameAs(plans.get(j)));
        assertTrue(ScreenRecordPlan.attempts(1440, 3120, Quality.BEST, List.of()).isEmpty());
        assertTrue(ScreenRecordPlan.attempts(0, 0, Quality.BEST, FLAGSHIP).isEmpty());
        assertEquals("이 휴대폰에서 화면을 담을 영상 인코더를 찾지 못했어요", ScreenRecordPlan.summary(null, Sound.DEVICE));
    }

    @Test public void namesSizesAndSound() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 26, 23, 15, 0);
        assertEquals("들퍼레_화면녹화_20261126_231500.mp4", ScreenRecordPlan.fileName(at, 1));
        assertEquals("들퍼레_화면녹화_20261126_231500_2.mp4", ScreenRecordPlan.fileName(at, 2));
        assertEquals("Movies/들퍼레", ScreenRecordPlan.FOLDER);
        assertEquals("312MB", ScreenRecordPlan.size(312_400_000)); assertEquals("1.4GB", ScreenRecordPlan.size(1_400_000_000L)); assertEquals("1MB", ScreenRecordPlan.size(10));
        assertEquals("48", ScreenRecordPlan.mbps(48_500_000)); assertEquals("7.6", ScreenRecordPlan.mbps(7_650_000));
        assertArrayEquals(new String[]{"기기 소리", "마이크", "둘 다", "없음"}, Sound.labels());
        assertTrue(Sound.DEVICE.needsRecordPermission()); assertTrue(Sound.MIC.needsRecordPermission()); assertTrue(Sound.BOTH.needsRecordPermission());
        assertFalse(Sound.NONE.needsRecordPermission());
        assertTrue(Sound.BOTH.device() && Sound.BOTH.mic()); assertFalse(Sound.DEVICE.mic()); assertFalse(Sound.MIC.device());
        assertEquals(Sound.DEVICE, Sound.of("?")); assertEquals(Sound.BOTH, Sound.of("BOTH"));
    }

    // ── Sound and timing ────────────────────────────────────────────────

    @Test public void mixingBendsLoudSumsInsteadOfClippingAndMonoBecomesStereo() {
        short[] a = {1000, 30000, -30000, 0, 26000, 32767, -32768}, b = {2000, 10000, -10000, -5, 214, 32767, -32768};
        ScreenRecordAudio.mix(a, b, 7);
        assertEquals("quiet sums pass as they are", 3000, a[0]); assertEquals(-5, a[3]); assertEquals("up to the knee too", 26214, a[4]);
        // 40000 would clip; it bends under full scale instead, the same both ways.
        assertTrue("" + a[1], a[1] > 32000 && a[1] < Short.MAX_VALUE); assertEquals(-a[1], a[2]);
        // Two full-scale sources: at most full scale, never wrapped round or cut to the edge on one side only.
        assertTrue(a[5] > 32700 && a[5] <= Short.MAX_VALUE); assertTrue(a[6] < -32700 && a[6] >= -Short.MAX_VALUE);
        // Smooth past the knee: louder sums never come out quieter.
        short previous = 0;
        for (int sum = 20_000; sum <= 65_000; sum += 500) {
            short[] x = {(short) (sum / 2)}, y = {(short) (sum - sum / 2)};
            ScreenRecordAudio.mix(x, y, 1);
            assertTrue(sum + ": " + x[0] + " after " + previous, x[0] >= previous); previous = x[0];
        }
        short[] stereo = new short[6];
        ScreenRecordAudio.upmix(new short[]{1, 2, 3}, 3, stereo);
        assertArrayEquals(new short[]{1, 1, 2, 2, 3, 3}, stereo);
        // In place: the mono samples at the start of the same array.
        short[] same = {7, 8, 0, 0};
        ScreenRecordAudio.upmix(same, 2, same);
        assertArrayEquals(new short[]{7, 7, 8, 8}, same);
    }

    @Test public void theAudioClockStaysContinuousAndCorrectsTheSamplesNotTheTimes() {
        ScreenRecordAudio.Clock clock = new ScreenRecordAudio.Clock(48_000);
        long chunk = 1024 * 1_000_000L / 48_000; // 21333µs
        // The first chunk sets the start: when it was captured.
        assertEquals(0, clock.correction(1_000_000, 1024));
        assertEquals(1_000_000, clock.next(1024));
        // Captured a little early or late: the times still go on by the samples handed out.
        assertEquals(0, clock.correction(1_000_000 + chunk + 3_000, 1024));
        assertEquals(1_000_000 + chunk, clock.next(1024));
        assertEquals(0, clock.correction(1_000_000 + 2 * chunk - 4_000, 1024));
        assertEquals(1_000_000 + 2 * chunk, clock.next(1024), 1);
        // 100ms were lost (an overrun, a chunk the encoder had no room for): that much silence goes first.
        long now = clock.now();
        int fill = clock.correction(now + 100_000, 1024);
        assertEquals(4_800, fill);
        clock.advance(fill);
        assertEquals("the sound is back with the picture, no jump in time", now + 100_000, clock.now());
        // The microphone ran ahead: frames are left out, never more than the chunk.
        assertEquals(-1_024, clock.correction(clock.now() - 30_000, 1024));
        assertEquals(-1_200, clock.correction(clock.now() - 25_000, 2048));
        // Within 20ms: as it is.
        assertEquals(0, clock.correction(clock.now() + 19_000, 1024));
        assertEquals(0, clock.correction(clock.now() - 19_000, 1024));
        // A gap of more than 2 seconds is not filled: the clock starts again where the sound really is.
        long later = clock.now() + 5_000_000;
        assertEquals(0, clock.correction(later, 1024));
        assertEquals(later, clock.now());
    }

    @Test public void theFileBeginsAtTheFirstKeyFrame() {
        ScreenRecordAudio.Timeline timeline = new ScreenRecordAudio.Timeline();
        assertEquals("sound before any picture is left out", -1, timeline.audio(900));
        assertEquals("a frame before the first key frame is left out", -1, timeline.video(950, false));
        assertFalse(timeline.started());
        assertEquals(0, timeline.video(1_000, true));
        assertTrue(timeline.started());
        assertEquals("sound from before the first frame is left out", -1, timeline.audio(999));
        assertEquals(500, timeline.audio(1_500));
        assertEquals(16_666, timeline.video(17_666, false));
        // An encoder that still makes B-frames gives an earlier time after a later one: it passes as it is (the muxer keeps
        // the order); only an exact repeat of the last time is moved on.
        assertEquals(16_000, timeline.video(17_000, false));
        assertEquals(16_001, timeline.video(17_000, false));
        assertEquals("before the first key frame", -1, timeline.video(500, false));
        assertEquals("audio only forward: an earlier time is left out, not squeezed in", -1, timeline.audio(1_200));
        assertEquals(-1, timeline.audio(1_500));
        assertEquals(21_833, timeline.audio(22_833));
        assertEquals(21_833, timeline.duration());
    }

    @Test public void theFifoKeepsTheNewestAndPadsWithSilence() {
        ScreenRecordAudio.Fifo fifo = new ScreenRecordAudio.Fifo(4);
        fifo.put(new short[]{1, 2, 3}, 3);
        fifo.put(new short[]{4, 5}, 2);
        assertEquals(4, fifo.size());
        short[] out = new short[6];
        assertEquals(4, fifo.take(out, 6));
        assertArrayEquals("the oldest was dropped; the rest is silence", new short[]{2, 3, 4, 5, 0, 0}, out);
        assertEquals(0, fifo.size());
        assertEquals(0, fifo.take(out, 2));
    }

    @Test public void theFifoHoldsACushionAndDropsTheOldestExcess() {
        // A cushion of 8 samples, at most 20 left after a take.
        ScreenRecordAudio.Fifo fifo = new ScreenRecordAudio.Fifo(100, 8, 20);
        short[] out = new short[4];
        fifo.put(seq(1, 8), 8);
        assertEquals("the cushion gathers first", 0, fifo.take(out, 4)); assertArrayEquals(new short[4], out);
        fifo.put(seq(9, 4), 4);
        assertEquals(4, fifo.take(out, 4)); assertArrayEquals(seq(1, 4), out);
        // Far above the ceiling: the oldest go, down to the cushion, so the sound stays close to now.
        fifo.put(seq(13, 20), 20);
        assertEquals(4, fifo.take(out, 4)); assertArrayEquals(seq(21, 4), out);
        assertEquals(8, fifo.size());
        assertEquals(4, fifo.take(out, 4)); assertArrayEquals(seq(25, 4), out);
        assertEquals(4, fifo.take(out, 4)); assertArrayEquals(seq(29, 4), out);
        // Run dry: silence, and the cushion gathers again before anything comes out (no holes mid-sound).
        assertEquals(0, fifo.take(out, 4)); assertArrayEquals(new short[4], out);
        fifo.put(seq(40, 4), 4);
        assertEquals(0, fifo.take(out, 4));
        fifo.put(seq(44, 8), 8);
        assertEquals(4, fifo.take(out, 4)); assertArrayEquals(seq(40, 4), out);
    }
    private static short[] seq(int from, int count) { short[] s = new short[count]; for (int i = 0; i < count; i++) s[i] = (short) (from + i); return s; }
}
