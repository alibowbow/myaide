package dev.jun.gyeot.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 화면 녹화's choice of encoder, size, frame rate and bitrate (준: "휴대폰 자체에 녹화기능 있는데 전체화면 녹화시 화질을 최고로 해도
 * 저화질로 저장돼"). The phone's own recorder scales the screen down and records at a low bitrate; this one records the screen at
 * its real size, pixel for pixel, at 60fps when the encoder can, with about 0.18 bits per pixel per frame (48Mbps for a
 * 1440×3120 screen at 60fps in HEVC; H.264 gets half as much again, 72Mbps, for the same picture). Smaller only when the
 * encoder cannot take that size: then the largest size with the same shape. HEVC when a hardware HEVC encoder takes the
 * size at its full bitrate, else H.264 (AVC High). The frame rate divides the screen's refresh evenly (see frameRate).
 *
 * Pure Java: the app hands in each encoder's limits (MediaCodecInfo.VideoCapabilities behind {@link Encoder}), so the choice
 * is the same on the phone and in tests.
 */
public final class ScreenRecordPlan {
    private ScreenRecordPlan() {}
    public static final String HEVC = "video/hevc", AVC = "video/avc";
    /** Never above this, whatever the encoder takes: past it a phone's storage fills faster than the picture improves. */
    public static final int MAX_BITRATE = 100_000_000;
    /** How far (as a fraction) a smaller size's shape may differ from the screen's after alignment. */
    static final double ASPECT_SLACK = 0.01;

    /** 화질: 최고 (the screen as it is, 60fps, a high bitrate), 고화질 (the same size and rate, a lighter bitrate), 보통 (1080 wide at most, 30fps). */
    public enum Quality {
        BEST("최고", 0, 60, 0.18), HIGH("고화질", 0, 60, 0.10), NORMAL("보통", 1080, 30, 0.10);
        public final String label;
        /** The short side's cap in pixels; 0 keeps the screen's own size. */
        public final int shortSide;
        public final int fps;
        /** Bits per pixel per frame. */
        public final double bitsPerPixel;
        Quality(String label, int shortSide, int fps, double bitsPerPixel) { this.label = label; this.shortSide = shortSide; this.fps = fps; this.bitsPerPixel = bitsPerPixel; }
        public static Quality of(String name) { for (Quality q : values()) if (q.name().equals(name)) return q; return BEST; }
        public static String[] labels() { Quality[] all = values(); String[] labels = new String[all.length]; for (int i = 0; i < all.length; i++) labels[i] = all[i].label; return labels; }
    }

    /**
     * 녹음할 오디오 (준: "화면만 / 미디어 / 마이크+미디어 — 이걸 설정 가능하게 해줘"), in the order shown: 화면만 (no sound track at
     * all), 미디어 (what the phone plays, from apps that allow it) and 마이크+미디어 (both mixed).
     */
    public enum Sound {
        NONE("화면만"), DEVICE("미디어"), BOTH("마이크+미디어");
        public final String label;
        Sound(String label) { this.label = label; }
        public boolean device() { return this == DEVICE || this == BOTH; }
        public boolean mic() { return this == BOTH; }
        /** Whether the file gets a sound track: 화면만 is an MP4 with the picture alone. */
        public boolean any() { return this != NONE; }
        /**
         * Whether Android's microphone permission is needed: for the microphone, and also for 미디어, because Android lets an
         * app capture what other apps play only with the recording permission (the microphone itself is not opened then).
         */
        public boolean needsRecordPermission() { return any(); }
        /** The saved choice; the old 마이크 alone became 마이크+미디어, and anything unknown 미디어. */
        public static Sound of(String name) {
            if ("MIC".equals(name)) return BOTH;
            for (Sound s : values()) if (s.name().equals(name)) return s;
            return DEVICE;
        }
        public static String[] labels() { Sound[] all = values(); String[] labels = new String[all.length]; for (int i = 0; i < all.length; i++) labels[i] = all[i].label; return labels; }
    }

    /** One video encoder on the phone, as MediaCodecInfo describes it. */
    public interface Encoder {
        String name();
        String mime();
        boolean hardware();
        int widthAlignment();
        int heightAlignment();
        boolean sizeSupported(int width, int height);
        boolean rateSupported(int width, int height, int fps);
        int minBitrate();
        int maxBitrate();
        boolean vbr();
    }

    /** An encoder described by plain limits: tests, and a phone whose encoder reports only its ranges. */
    public record Limits(String name, String mime, boolean hardware, int widthAlignment, int heightAlignment, int minWidth, int maxWidth,
                         int minHeight, int maxHeight, long maxPixels, long maxPixelsPerSecond, int minBitrate, int maxBitrate, boolean vbr) implements Encoder {
        public boolean sizeSupported(int width, int height) {
            return width % Math.max(1, widthAlignment) == 0 && height % Math.max(1, heightAlignment) == 0 && width >= minWidth && width <= maxWidth
                && height >= minHeight && height <= maxHeight && (long) width * height <= maxPixels;
        }
        public boolean rateSupported(int width, int height, int fps) { return sizeSupported(width, height) && (long) width * height * fps <= maxPixelsPerSecond; }
    }

    /**
     * What one recording asks the encoder for. fps: the frame rate it is planned and labelled at. maxFps: the exact rate the
     * encoder keeps frames at, the screen's refresh divided by a whole number, so the kept frames stay evenly spaced (45.0 of
     * a 90Hz screen, not 60 of it in an uneven 11ms/22ms pattern). vbr: the encoder's variable bitrate mode (else its default).
     */
    public record Plan(String encoder, String mime, boolean hardware, int width, int height, int fps, int bitrate, boolean vbr, float maxFps) {
        public String codec() { return HEVC.equals(mime) ? "HEVC" : "H.264"; }
        /** "1440×3120 · 60fps · HEVC 48Mbps". */
        public String label() { return width + "×" + height + " · " + fps + "fps · " + codec() + " " + mbps(bitrate) + "Mbps"; }
        public boolean sameAs(Plan other) { return other != null && encoder.equals(other.encoder) && width == other.width && height == other.height && fps == other.fps; }
    }

    /** "48" for 48.5Mbps, "7.6" for 7.6Mbps. */
    public static String mbps(int bitrate) {
        double m = bitrate / 1_000_000.0;
        return m >= 10 ? Long.toString((long) Math.floor(m)) : String.format(Locale.ROOT, "%.1f", Math.floor(m * 10) / 10);
    }

    /** The plan the recording tries first on a 60Hz screen, or null when no encoder here can take the screen at all. */
    public static Plan best(int screenWidth, int screenHeight, Quality quality, List<? extends Encoder> encoders) {
        return best(screenWidth, screenHeight, 60, quality, encoders);
    }
    /** The plan the recording tries first on a screen refreshing refresh times a second (0: unknown, taken as 60). */
    public static Plan best(int screenWidth, int screenHeight, float refresh, Quality quality, List<? extends Encoder> encoders) {
        List<Plan> all = attempts(screenWidth, screenHeight, refresh, quality, encoders);
        return all.isEmpty() ? null : all.get(0);
    }
    public static List<Plan> attempts(int screenWidth, int screenHeight, Quality quality, List<? extends Encoder> encoders) {
        return attempts(screenWidth, screenHeight, 60, quality, encoders);
    }

    /**
     * Every plan to try, in order. First the largest size each encoder takes, ranked by how many of the screen's pixels it
     * keeps, then whether it keeps them exactly (not resampled, see {@link #exact}), then 60fps before 30, then how near
     * the encoder lets the bitrate come to what the quality asks (an HEVC encoder capped at 20Mbps loses to a full-rate
     * H.264 one), then HEVC before H.264 and hardware before software. Then the same at the next smaller sizes (short side
     * 1440, 1080, 720). The recorder tries the next one when an encoder refuses to configure, so a refused HEVC falls back
     * to H.264 and a refused size to the next smaller one. A size the encoder cannot run at 24fps or more is left out.
     */
    public static List<Plan> attempts(int screenWidth, int screenHeight, float refresh, Quality quality, List<? extends Encoder> encoders) {
        List<Plan> plans = new ArrayList<>();
        if (screenWidth <= 0 || screenHeight <= 0 || encoders == null) return plans;
        List<Encoder> ranked = ranked(encoders);
        if (ranked.isEmpty()) return plans;
        int shortSide = Math.min(screenWidth, screenHeight);
        double scale = quality.shortSide > 0 && shortSide > quality.shortSide ? quality.shortSide / (double) shortSide : 1;
        int targetWidth = (int) Math.round(screenWidth * scale), targetHeight = (int) Math.round(screenHeight * scale);
        List<int[]> tiers = new ArrayList<>(); tiers.add(new int[]{targetWidth, targetHeight});
        int targetShort = Math.min(targetWidth, targetHeight);
        for (int cap : new int[]{1440, 1080, 720}) {
            if (cap >= targetShort) continue;
            double s = cap / (double) targetShort;
            tiers.add(new int[]{(int) Math.round(targetWidth * s), (int) Math.round(targetHeight * s)});
        }
        for (int t = 0; t < tiers.size(); t++) {
            List<Plan> tier = new ArrayList<>();
            for (Encoder encoder : ranked) {
                int[] size = fit(encoder, tiers.get(t)[0], tiers.get(t)[1]);
                if (size == null) continue;
                float rate = frameRate(encoder, size[0], size[1], quality.fps, refresh);
                if (rate <= 0) continue;
                int fps = Math.round(rate);
                tier.add(new Plan(encoder.name(), encoder.mime(), encoder.hardware(), size[0], size[1], fps, bitrate(encoder, size[0], size[1], fps, quality), encoder.vbr(), rate));
            }
            if (t == 0) {
                // A stable sort: ties keep the encoders' rank (HEVC, hardware first).
                tier.sort((a, b) -> {
                    int kept = Long.compare(kept(b, targetWidth, targetHeight), kept(a, targetWidth, targetHeight));
                    if (kept != 0) return kept;
                    int exact = Boolean.compare(exact(b.width(), b.height(), targetWidth, targetHeight), exact(a.width(), a.height(), targetWidth, targetHeight));
                    if (exact != 0) return exact;
                    if (a.fps() != b.fps()) return Integer.compare(b.fps(), a.fps());
                    double fa = fullness(a, quality), fb = fullness(b, quality);
                    return Math.abs(fa - fb) > 0.05 ? Double.compare(fb, fa) : 0;
                });
            }
            for (Plan p : tier) { boolean seen = false; for (Plan q : plans) if (q.sameAs(p)) { seen = true; break; } if (!seen) plans.add(p); }
        }
        return plans.size() > 12 ? new ArrayList<>(plans.subList(0, 12)) : plans;
    }
    /** How many of width×height's pixels a plan keeps: all of them unless Android has to scale the screen down into it. */
    private static long kept(Plan p, int width, int height) {
        double s = Math.min(1, Math.min(p.width() / (double) width, p.height() / (double) height));
        return Math.round(width * (double) height * s * s);
    }
    /** How near the plan's bitrate is to what the quality asks of its codec (1: all of it; less when the encoder caps it). */
    private static double fullness(Plan p, Quality quality) {
        double ideal = ideal(p.mime(), p.width(), p.height(), p.fps(), quality);
        return ideal <= 0 ? 1 : Math.min(1, p.bitrate() / ideal);
    }

    /**
     * Hardware encoders of HEVC first, then H.264; software ones only on a phone without any hardware encoder (an emulator),
     * H.264 first there, since a software HEVC encoder cannot keep up with a phone screen.
     */
    static List<Encoder> ranked(List<? extends Encoder> encoders) {
        List<Encoder> hardware = new ArrayList<>(), software = new ArrayList<>();
        for (String mime : new String[]{HEVC, AVC}) for (Encoder e : encoders) if (mime.equals(e.mime()) && e.hardware()) hardware.add(e);
        if (!hardware.isEmpty()) return hardware;
        for (String mime : new String[]{AVC, HEVC}) for (Encoder e : encoders) if (mime.equals(e.mime())) software.add(e);
        return software;
    }

    /**
     * The largest size the encoder takes with the shape of width×height. The size itself first, padded up to the encoder's
     * alignment when it needs it: Android fits the screen into the recording with one scale for both sides, so padding one
     * side keeps every pixel (a few black pixels at that edge), while padding both scales the screen up by a fraction of a
     * percent (resampled, but sharper than the aligned size just under it). Then smaller sizes of the same shape. Null when
     * the encoder takes none.
     */
    public static int[] fit(Encoder encoder, int width, int height) {
        int wa = Math.max(2, encoder.widthAlignment()), ha = Math.max(2, encoder.heightAlignment());
        int w = up(width, wa), h = up(height, ha);
        if (encoder.sizeSupported(w, h)) return new int[]{w, h};
        w = down(width, wa); h = down(height, ha);
        if (w != width || h != height) { if (encoder.sizeSupported(w, h) && close(w, h, width, height)) return new int[]{w, h}; }
        double aspect = width / (double) height;
        for (int hh = down(height, ha) - ha; hh >= 144; hh -= ha) {
            int ww = (int) Math.round(hh * aspect / wa) * wa;
            if (ww < 2 || ww > width) ww = down((int) Math.floor(hh * aspect), wa);
            if (ww < 2) break;
            if (!close(ww, hh, width, height)) continue;
            if (encoder.sizeSupported(ww, hh)) return new int[]{ww, hh};
        }
        return null;
    }
    /**
     * Whether a recording of w×h keeps a width×height screen pixel for pixel: Android's one scale for both sides,
     * min(w/width, h/height), is exactly 1 (the size itself, or one side padded).
     */
    public static boolean exact(int w, int h, int width, int height) {
        return w >= width && h >= height && (w == width || h == height);
    }
    private static boolean close(int w, int h, int width, int height) {
        double aspect = width / (double) height;
        return Math.abs(w / (double) h - aspect) / aspect <= ASPECT_SLACK;
    }
    private static int up(int value, int alignment) { return (value + alignment - 1) / alignment * alignment; }
    private static int down(int value, int alignment) { return value / alignment * alignment; }

    /** Below this many frames a second a size is not worth recording at: the next smaller size is tried instead. */
    static final int MIN_FPS = 24;
    /**
     * The frame rate for this size: the highest one, up to what the quality wants, that divides the screen's refresh evenly
     * (60 on a 60 or 120Hz screen, 45 on a 90Hz one, 48 on a 144Hz one) and that the encoder takes at this size. The encoder
     * then keeps every first, second or third frame, so motion stays even. 0 when it takes none from MIN_FPS up.
     */
    public static float frameRate(Encoder encoder, int width, int height, int wanted, float refresh) {
        float screen = refresh >= 20 && refresh <= 1000 ? refresh : 60;
        for (int n = 1; n <= 16; n++) {
            float rate = screen / n;
            if (rate < MIN_FPS - 0.5f) break;
            if (rate > wanted + 0.5f) continue;
            if (encoder.rateSupported(width, height, (int) Math.ceil(rate - 0.01f))) return rate;
        }
        return 0;
    }

    /** H.264 needs about half as many bits again as HEVC for the same picture. */
    static final double AVC_FACTOR = 1.5;
    /**
     * The quality's bits per pixel per frame (half as much again for H.264), in whole megabits from 10Mbps up (tenths
     * below), within MAX_BITRATE and the encoder's own range.
     */
    public static int bitrate(Encoder encoder, int width, int height, int fps, Quality quality) {
        double bits = ideal(encoder.mime(), width, height, fps, quality);
        long rounded = bits >= 10_000_000 ? (long) Math.floor(bits / 1_000_000) * 1_000_000 : Math.max(100_000, (long) Math.floor(bits / 100_000) * 100_000);
        rounded = Math.min(rounded, MAX_BITRATE);
        if (encoder.maxBitrate() > 0) rounded = Math.min(rounded, encoder.maxBitrate());
        if (encoder.minBitrate() > 0) rounded = Math.max(rounded, encoder.minBitrate());
        return (int) rounded;
    }
    private static double ideal(String mime, int width, int height, int fps, Quality quality) {
        return (double) width * height * fps * quality.bitsPerPixel * (AVC.equals(mime) ? AVC_FACTOR : 1);
    }

    // ── Profile levels ───────────────────────────────────────────────────

    /** H.264 High and HEVC Main, as MediaCodecInfo.CodecProfileLevel numbers them (fixed by the Android API). */
    public static final int AVC_HIGH = 0x08, HEVC_MAIN = 0x01;
    /** H.264's levels 3 to 6.2: Android's number, macroblocks a second, macroblocks a frame, kbit/s (Main; High takes 1.25×). */
    private static final long[][] AVC_LEVELS = {
        {0x100, 40_500, 1_620, 10_000}, {0x200, 108_000, 3_600, 14_000}, {0x400, 216_000, 5_120, 20_000},
        {0x800, 245_760, 8_192, 20_000}, {0x1000, 245_760, 8_192, 50_000}, {0x2000, 522_240, 8_704, 50_000},
        {0x4000, 589_824, 22_080, 135_000}, {0x8000, 983_040, 36_864, 240_000}, {0x10000, 2_073_600, 36_864, 240_000},
        {0x20000, 4_177_920, 139_264, 240_000}, {0x40000, 8_355_840, 139_264, 480_000}, {0x80000, 16_711_680, 139_264, 800_000}};
    /** HEVC's levels 3 to 6.2: Android's Main-tier number (High tier is twice it), luma samples a frame, a second, kbit/s Main tier, High tier. */
    private static final long[][] HEVC_LEVELS = {
        {0x40, 552_960, 16_588_800, 6_000, 0}, {0x100, 983_040, 33_177_600, 10_000, 0},
        {0x400, 2_228_224, 66_846_720, 12_000, 30_000}, {0x1000, 2_228_224, 133_693_440, 20_000, 50_000},
        {0x4000, 8_912_896, 267_386_880, 25_000, 100_000}, {0x10000, 8_912_896, 534_773_760, 40_000, 160_000},
        {0x40000, 8_912_896, 1_069_547_520, 60_000, 240_000}, {0x100000, 35_651_584, 1_069_547_520, 60_000, 240_000},
        {0x400000, 35_651_584, 2_139_095_040, 120_000, 480_000}, {0x1000000, 35_651_584, 4_278_190_080L, 240_000, 800_000}};

    /**
     * The lowest level of H.264 High or HEVC Main that takes this size, rate and bitrate (HEVC's Main tier first, then its
     * High tier), as Android numbers it; 0 when none does. An encoder left to choose its own level may take a lower one and
     * hold the bitrate under that level's cap (HEVC Main 5.1 allows 40Mbps, under a 1440×3120 screen's 48).
     */
    public static int level(String mime, int width, int height, int fps, int bitrate) {
        if (AVC.equals(mime)) {
            long mbW = (width + 15) / 16, mbH = (height + 15) / 16, frame = mbW * mbH;
            for (long[] l : AVC_LEVELS) {
                double side = Math.sqrt(8.0 * l[2]);
                if (frame <= l[2] && mbW <= side && mbH <= side && frame * fps <= l[1] && bitrate <= l[3] * 1250) return (int) l[0];
            }
            return 0;
        }
        if (!HEVC.equals(mime)) return 0;
        long samples = (long) width * height;
        for (int tier = 3; tier <= 4; tier++) {
            for (long[] l : HEVC_LEVELS) {
                if (l[tier] == 0) continue;
                double side = Math.sqrt(8.0 * l[1]);
                if (samples <= l[1] && width <= side && height <= side && samples * fps <= l[2] && bitrate <= l[tier] * 1000) return (int) (tier == 3 ? l[0] : l[0] << 1);
            }
        }
        return 0;
    }

    /** The row's line: "1440×3120 · 60fps · HEVC 48Mbps · 미디어". */
    public static String summary(Plan plan, Sound sound) {
        if (plan == null) return "이 휴대폰에서 화면을 담을 영상 인코더를 찾지 못했어요";
        return plan.label() + " · " + sound.label;
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
    /** "들퍼레_화면녹화_20261126_231500.mp4"; a long recording's later parts end in _2, _3 … */
    public static String fileName(LocalDateTime at, int part) {
        return Brand.NAME + "_화면녹화_" + STAMP.format(at) + (part > 1 ? "_" + part : "") + ".mp4";
    }
    /** Where the recordings go, under the phone's shared storage. */
    public static final String FOLDER = "Movies/" + Brand.NAME;

    /** "312MB", "1.4GB". */
    public static String size(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fGB", bytes / 1_000_000_000.0);
        return Math.max(1, Math.round(bytes / 1_000_000.0)) + "MB";
    }
}
