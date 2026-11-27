package dev.jun.gyeot;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Surface;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.ScreenRecordAudio;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 화면 녹화's engine: the whole screen into a VirtualDisplay at the screen's own size, a hardware video encoder (HEVC, else
 * H.264 High) at the bitrate ScreenRecordPlan chose, the phone's sound and/or the microphone into AAC, and both into an MP4 in
 * Movies/들퍼레 (MediaStore, pending while it is written). Runs on its own threads; the service calls start() and stop() from
 * its worker thread, never the main thread. Every failure releases what was opened, and a file without a picture is deleted.
 */
final class ScreenRecorder {
    /** Called on a recorder thread when the recording has to stop by itself (an encoder stopped, the storage is full). */
    interface Stop { void now(String why); }
    /** What was saved: the files (more than one for a recording past 3.5GB), their size and length, and what went wrong. */
    record Result(List<Uri> files, long bytes, long durationUs, String problem, String note) {
        boolean saved() { return !files.isEmpty(); }
    }

    // ── The phone's encoders ─────────────────────────────────────────────

    /** One encoder on this phone, through MediaCodecInfo's capabilities. */
    static final class Codec implements ScreenRecordPlan.Encoder {
        private final MediaCodecInfo info;
        private final String mime;
        private final MediaCodecInfo.VideoCapabilities video;
        private final MediaCodecInfo.EncoderCapabilities encoder;
        /** The best level this encoder lists for H.264 High or HEVC Main (either tier), or 0 when it lists none. */
        final int topLevel;
        Codec(MediaCodecInfo info, String mime, MediaCodecInfo.CodecCapabilities caps) {
            this.info = info; this.mime = mime; video = caps.getVideoCapabilities();
            MediaCodecInfo.EncoderCapabilities e = null; try { e = caps.getEncoderCapabilities(); } catch (RuntimeException none) { /* not offered */ }
            encoder = e;
            int profile = ScreenRecordPlan.AVC.equals(mime) ? ScreenRecordPlan.AVC_HIGH : ScreenRecordPlan.HEVC_MAIN, level = 0;
            if (caps.profileLevels != null) for (MediaCodecInfo.CodecProfileLevel p : caps.profileLevels) if (p.profile == profile) level = Math.max(level, p.level);
            topLevel = level;
        }
        @Override public String name() { return info.getName(); }
        @Override public String mime() { return mime; }
        @Override public boolean hardware() { return info.isHardwareAccelerated() && !info.isSoftwareOnly(); }
        @Override public int widthAlignment() { return Math.max(1, video.getWidthAlignment()); }
        @Override public int heightAlignment() { return Math.max(1, video.getHeightAlignment()); }
        @Override public boolean sizeSupported(int width, int height) { try { return video.isSizeSupported(width, height); } catch (RuntimeException e) { return false; } }
        @Override public boolean rateSupported(int width, int height, int fps) {
            try {
                if (!video.isSizeSupported(width, height)) return false;
                if (video.areSizeAndRateSupported(width, height, fps)) return true;
                // Some encoders report what they do as performance points rather than rate ranges.
                List<MediaCodecInfo.VideoCapabilities.PerformancePoint> points = video.getSupportedPerformancePoints();
                if (points == null) return false;
                MediaCodecInfo.VideoCapabilities.PerformancePoint wanted = new MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, fps);
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint point : points) if (point.covers(wanted)) return true;
                return false;
            } catch (RuntimeException e) { return false; }
        }
        @Override public int minBitrate() { try { return video.getBitrateRange().getLower(); } catch (RuntimeException e) { return 0; } }
        @Override public int maxBitrate() { try { return video.getBitrateRange().getUpper(); } catch (RuntimeException e) { return 0; } }
        @Override public boolean vbr() {
            try { return encoder != null && encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR); } catch (RuntimeException e) { return false; }
        }
    }

    private static volatile List<Codec> found;
    /** The HEVC and H.264 encoders that take a Surface, read once (MediaCodecList asks the media service; call it off the main thread). */
    static List<Codec> encoders() {
        List<Codec> list = found;
        if (list != null) return list;
        list = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || info.isAlias()) continue;
                for (String type : info.getSupportedTypes()) {
                    String mime = type.toLowerCase(Locale.ROOT);
                    if (!mime.equals(ScreenRecordPlan.HEVC) && !mime.equals(ScreenRecordPlan.AVC)) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (caps.getVideoCapabilities() == null || !takesSurface(caps)) continue;
                        list.add(new Codec(info, mime, caps));
                    } catch (RuntimeException unreadable) { /* this one is skipped */ }
                }
            }
        } catch (RuntimeException unavailable) { /* no encoders listed */ }
        found = Collections.unmodifiableList(list);
        return found;
    }
    /** Forgets the encoders read before (tests add their own). */
    static void rescan() { found = null; }
    private static boolean takesSurface(MediaCodecInfo.CodecCapabilities caps) {
        if (caps.colorFormats == null) return false;
        for (int format : caps.colorFormats) if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
        return false;
    }
    /** The plans for this screen (refresh: its refresh rate, 0 when unknown), best first (see ScreenRecordPlan.attempts). Off the main thread. */
    static List<ScreenRecordPlan.Plan> plans(int width, int height, float refresh, ScreenRecordPlan.Quality quality) {
        return ScreenRecordPlan.attempts(width, height, refresh, quality, encoders());
    }
    /** The screen's refresh rate (0 when unknown): the frame rate is an even share of it (60 of 120Hz, 45 of 90Hz). */
    static float refreshRate(Context context) {
        try { Display d = context.getDisplay(); if (d != null) return d.getMode().getRefreshRate(); } catch (RuntimeException notVisual) { /* the default display below */ }
        try {
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            Display d = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) return d.getMode().getRefreshRate();
        } catch (RuntimeException unknown) { /* unknown */ }
        return 0;
    }
    private static Codec codec(String name) { for (Codec c : encoders()) if (c.name().equals(name)) return c; return null; }

    // ── One recording ────────────────────────────────────────────────────

    private static final int RATE = ScreenRecordAudio.RATE, CHANNELS = ScreenRecordAudio.CHANNELS, FRAMES = ScreenRecordAudio.FRAMES;
    private final Context context;
    private final MediaProjection projection;
    private final int dpi;
    private final float refresh;
    private final ScreenRecordPlan.Quality quality;
    private final ScreenRecordPlan.Sound sound;
    private final Stop stop;
    /** The screen's size as last reported, and the size the picture encoder in use was planned for (they differ after a refused turn). */
    private int screenWidth, screenHeight, plannedWidth, plannedHeight;
    private volatile Picture picture;
    private VirtualDisplay display;
    private MediaCodec audio;
    private AudioRecord primary, secondary;
    private AcousticEchoCanceler echo;
    private int primaryChannels, secondaryChannels;
    private Mux mux;
    private Thread audioThread;
    private volatile boolean ending;
    private volatile String problem = "", note = "";
    private int turns;

    ScreenRecorder(Context context, MediaProjection projection, int width, int height, int dpi, float refresh, ScreenRecordPlan.Quality quality, ScreenRecordPlan.Sound sound, Stop stop) {
        this.context = context.getApplicationContext(); this.projection = projection;
        screenWidth = plannedWidth = width; screenHeight = plannedHeight = height;
        this.dpi = Math.max(120, dpi); this.refresh = refresh; this.quality = quality; this.sound = sound; this.stop = stop;
    }

    /** One picture encoder: the codec, the Surface the screen is drawn into, its plan, and the thread that writes what it makes. */
    private final class Picture {
        final MediaCodec codec;
        final Surface input;
        final ScreenRecordPlan.Plan plan;
        Thread thread;
        /** Set once its end of stream was asked for: it writes what is left until then, and errors no longer stop the recording. */
        volatile boolean closing;
        volatile long deadline;
        Picture(MediaCodec codec, Surface input, ScreenRecordPlan.Plan plan) { this.codec = codec; this.input = input; this.plan = plan; }
        void start() { thread = new Thread(() -> drainVideo(this), "screen-record-video"); thread.start(); }
        /** Asks for its end of stream and waits (at most millis) until what it still had is in the file. */
        void finish(long millis) {
            deadline = System.nanoTime() + millis * 1_000_000L; closing = true;
            try { codec.signalEndOfInputStream(); } catch (RuntimeException ignored) { /* the encoder already stopped */ }
            join(thread, millis + 1000);
        }
        void release() {
            closing = true;
            try { codec.stop(); } catch (RuntimeException ignored) { /* not started */ }
            try { codec.release(); } catch (RuntimeException ignored) { /* gone */ }
            input.release();
        }
    }

    /** Opens everything and starts recording; returns the plan in use. On a failure it throws, and release() undoes what was opened. */
    ScreenRecordPlan.Plan start() throws IOException {
        List<ScreenRecordPlan.Plan> plans = plans(screenWidth, screenHeight, refresh, quality);
        if (plans.isEmpty()) throw new IOException("이 휴대폰에서 화면을 담을 영상 인코더를 찾지 못했어요");
        Picture first = openPicture(plans);
        picture = first;
        boolean withSound = sound.any() && openAudio();
        mux = new Mux(context, LocalDateTime.now(), withSound, stop);
        // After the encoder runs: on Android 14 and later a projection gives only one VirtualDisplay, so it is made once, last,
        // and a turned screen resizes this one (see resize).
        display = projection.createVirtualDisplay(Brand.NAME + " 화면 녹화", first.plan.width(), first.plan.height(), dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, first.input, null, null);
        if (display == null) throw new IOException("화면을 담을 가상 화면을 만들지 못했어요");
        first.start();
        if (withSound) { audioThread = new Thread(this::runAudio, "screen-record-audio"); audioThread.start(); }
        return first.plan;
    }
    ScreenRecordPlan.Plan plan() { Picture p = picture; return p == null ? null : p.plan; }
    /** Why the sound is missing or partial, in plain words ("" when nothing). */
    String note() { return note; }

    /**
     * The screen turned, folded or changed resolution (width×height: its new size). Android fits the new screen into the
     * recording's frame, so a phone turned to a landscape game would fill a third of a portrait frame. Instead a new encoder
     * is opened at the new size, the one VirtualDisplay is moved onto it and resized (Android 14 gives a projection only one),
     * and the old encoder finishes into the current file; the recording goes on in the next file (_2, _3 …), since an MP4
     * track keeps one picture size. A phone whose hardware runs only one encoder this large gets the new one once the old
     * one has ended; one that refuses the new size goes on at the old size. On the service's worker thread. Returns the new
     * plan, or null when nothing changed.
     */
    ScreenRecordPlan.Plan resize(int width, int height) {
        Picture old = picture;
        if (ending || display == null || old == null || mux == null || width <= 0 || height <= 0) return null;
        if (width == screenWidth && height == screenHeight) return null;
        screenWidth = width; screenHeight = height;
        // Turned back after a refused turn: the display and the encoder still have this size.
        if (width == plannedWidth && height == plannedHeight) return null;
        List<ScreenRecordPlan.Plan> plans = plans(width, height, refresh, quality);
        if (plans.isEmpty()) { note = "화면을 돌린 뒤의 크기는 영상 인코더가 받지 않아서 원래 틀에 맞춰 담았어요"; return null; }
        // Beside the running encoder first, so the picture goes straight on.
        Picture next = null;
        try { next = openPicture(plans); } catch (IOException busy) { /* after the old one, below */ }
        if (next != null && !moveTo(next)) { next.release(); next = null; }
        if (next == null) try { display.setSurface(null); } catch (RuntimeException ignored) { /* gone: stops below */ }
        // The old encoder writes the frames it still has into the current file.
        old.finish(3000);
        old.release();
        picture = null;
        boolean turned = true;
        if (next == null) {
            next = reopen(plans);
            if (next == null) { turned = false; next = reopen(plans(plannedWidth, plannedHeight, refresh, quality)); }
            if (next == null) { problem = "화면을 돌린 뒤 영상 인코더를 다시 열지 못했어요"; stop.now(problem); return null; }
            if (!turned) note = "화면을 돌린 뒤의 크기는 영상 인코더가 받지 않아서 원래 틀에 맞춰 담았어요";
        }
        // The next file begins with the new encoder's first frame.
        mux.newPicture();
        picture = next; turns++;
        if (turned) { plannedWidth = width; plannedHeight = height; }
        next.start();
        return next.plan;
    }
    /** An encoder for these plans, with the display moved onto it; null when none opens or the display refuses. */
    private Picture reopen(List<ScreenRecordPlan.Plan> plans) {
        if (plans.isEmpty()) return null;
        Picture p;
        try { p = openPicture(plans); } catch (IOException refused) { return null; }
        if (moveTo(p)) return p;
        p.release();
        return null;
    }
    /** The display drawn into this encoder's Surface at its size. */
    private boolean moveTo(Picture p) {
        try {
            display.setSurface(null);
            display.resize(p.plan.width(), p.plan.height(), dpi);
            display.setSurface(p.input);
            return true;
        } catch (RuntimeException refused) { return false; }
    }

    /**
     * The first plan an encoder accepts (HEVC refused → H.264, a size refused → the next smaller one). Each plan is tried with
     * its profile and level pinned (the encoder's best listed level, then the lowest that takes the plan's bitrate), then
     * with the encoder's own choices and none of the optional keys.
     */
    private Picture openPicture(List<ScreenRecordPlan.Plan> plans) throws IOException {
        Exception last = null;
        for (ScreenRecordPlan.Plan p : plans) {
            for (int[] profile : profiles(p, codec(p.encoder()))) {
                MediaCodec c = null; Surface s = null;
                try {
                    c = MediaCodec.createByCodecName(p.encoder());
                    c.configure(videoFormat(p, profile[0], profile[1], profile[2] == 1), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    s = c.createInputSurface();
                    c.start();
                    return new Picture(c, s, p);
                } catch (Exception refused) {
                    last = refused;
                    if (s != null) s.release();
                    if (c != null) try { c.release(); } catch (RuntimeException ignored) { /* already gone */ }
                }
            }
        }
        throw new IOException("영상 인코더가 이 화면 크기를 받지 않았어요" + (last == null ? "" : " (" + last.getClass().getSimpleName() + ")"));
    }
    /** {profile, level, full}: what to try for one plan, in order. A 0 profile leaves profile and level to the encoder. */
    private static List<int[]> profiles(ScreenRecordPlan.Plan p, Codec codec) {
        boolean hevc = ScreenRecordPlan.HEVC.equals(p.mime());
        int profile = hevc ? ScreenRecordPlan.HEVC_MAIN : ScreenRecordPlan.AVC_HIGH;
        int top = codec == null ? 0 : codec.topLevel;
        int fit = ScreenRecordPlan.level(p.mime(), p.width(), p.height(), p.fps(), p.bitrate());
        List<int[]> list = new ArrayList<>();
        if (top > 0) list.add(new int[]{profile, top, 1});
        // HEVC Main is every HEVC encoder's; H.264 High only when the encoder lists it.
        if (fit > 0 && fit != top && (top > 0 || hevc)) list.add(new int[]{profile, fit, 1});
        list.add(new int[]{0, 0, 1});
        list.add(new int[]{0, 0, 0});
        return list;
    }
    private static MediaFormat videoFormat(ScreenRecordPlan.Plan p, int profile, int level, boolean full) {
        MediaFormat f = MediaFormat.createVideoFormat(p.mime(), p.width(), p.height());
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate());
        f.setInteger(MediaFormat.KEY_FRAME_RATE, p.fps());
        // A key frame every 2 seconds: still easy to seek, and more of the bits go to the frames between (a key frame of a
        // screen is many times larger than the frames after it). A 3.5GB part still ends at the next one.
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // A 90 or 120Hz screen gives more frames than the plan: the encoder keeps every first, second or third, evenly.
        f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, p.maxFps() > 0 ? p.maxFps() : p.fps());
        // A still screen sends no new frames: the last one is repeated, so the video keeps time and ends where it stopped.
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (p.vbr()) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (full) {
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, p.fps());
            // The screen's colours as players expect HD video: BT.709, limited range, SDR (left to the vendor they shift).
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        }
        // Profile and level together (one without the other is ignored): left to itself an encoder may pick a level whose
        // cap is under the plan's bitrate.
        if (profile > 0 && level > 0) { f.setInteger(MediaFormat.KEY_PROFILE, profile); f.setInteger(MediaFormat.KEY_LEVEL, level); }
        return f;
    }

    /** The sound sources and the AAC encoder; false (with note set) when there is no sound to record. */
    private boolean openAudio() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { note = "마이크 권한이 없어서 소리 없이 녹화했어요"; return false; }
        AudioRecord device = null, mic = null;
        if (sound.device()) { device = deviceSound(); if (device == null) note = "기기 소리는 담지 못했어요 (앱이 막았거나 휴대폰이 허용하지 않아요)"; }
        if (sound.mic()) { mic = microphone(); if (mic == null) note = "마이크 소리는 담지 못했어요 (다른 앱이 마이크를 쓰는 중일 수 있어요)"; }
        // 둘 다: the speaker's sound also reaches the microphone, so it would be heard twice; the echo canceller takes it out.
        if (mic != null && device != null) { echo = echoCanceller(mic); if (echo == null) note = "스피커 소리가 마이크에도 담겨요 · 이어폰을 쓰면 깨끗해요"; }
        // 둘 다: the microphone keeps time (it always delivers), the phone's sound is mixed in as it comes.
        primary = mic != null ? mic : device; secondary = mic != null ? device : null;
        if (primary == null) return false;
        primaryChannels = primary.getChannelCount(); if (secondary != null) secondaryChannels = secondary.getChannelCount();
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, ScreenRecordAudio.BITRATE);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES * CHANNELS * 2 * 2);
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audio.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audio.start();
            // Started here to know whether it records at all. What it gathers before the picture begins is stamped with when it
            // was captured (see runAudio), so the timeline leaves it out instead of playing it late.
            primary.startRecording();
            if (primary.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("not recording");
            if (secondary != null) { secondary.startRecording(); if (secondary.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { secondary.release(); secondary = null; note = "기기 소리는 담지 못했어요"; } }
            return true;
        } catch (Exception failed) {
            note = "소리를 담지 못해서 화면만 녹화했어요";
            releaseAudio();
            return false;
        }
    }
    private AudioFormat pcm(int mask) { return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(mask).build(); }
    /** Half a second at least: the thread that reads also encodes and waits on the file, and a short buffer would overrun. */
    private static int bufferBytes(int mask) {
        int channels = mask == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        return Math.max(AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT) * 4, RATE * channels * 2 / 2);
    }
    /** What the phone plays (apps that allow capture: media, games and the rest). */
    @SuppressWarnings("MissingPermission")
    private AudioRecord deviceSound() {
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioRecord r = new AudioRecord.Builder().setAudioFormat(pcm(AudioFormat.CHANNEL_IN_STEREO)).setBufferSizeInBytes(bufferBytes(AudioFormat.CHANNEL_IN_STEREO))
                .setAudioPlaybackCaptureConfig(config).build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception | LinkageError refused) { /* none */ }
        return null;
    }
    /**
     * The microphone: the input phones tune for video recording (CAMCORDER, real stereo on most) first, then the plain
     * microphone in stereo, then in mono (doubled to stereo when mixed).
     */
    @SuppressWarnings("MissingPermission")
    private AudioRecord microphone() {
        int[][] tries = {{MediaRecorder.AudioSource.CAMCORDER, AudioFormat.CHANNEL_IN_STEREO}, {MediaRecorder.AudioSource.MIC, AudioFormat.CHANNEL_IN_STEREO},
            {MediaRecorder.AudioSource.MIC, AudioFormat.CHANNEL_IN_MONO}};
        for (int[] t : tries) {
            try {
                AudioRecord r = new AudioRecord.Builder().setAudioSource(t[0]).setAudioFormat(pcm(t[1])).setBufferSizeInBytes(bufferBytes(t[1])).build();
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (Exception refused) { /* the next one */ }
        }
        return null;
    }
    private static AcousticEchoCanceler echoCanceller(AudioRecord mic) {
        try {
            if (!AcousticEchoCanceler.isAvailable()) return null;
            AcousticEchoCanceler e = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (e == null) return null;
            if (e.setEnabled(true) == AudioEffect.SUCCESS) return e;
            e.release();
        } catch (RuntimeException unavailable) { /* none */ }
        return null;
    }

    private void drainVideo(Picture p) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int index = p.codec.dequeueOutputBuffer(info, 20_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (p.closing && System.nanoTime() > p.deadline) return; continue; }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { mux.videoFormat(p.codec.getOutputFormat()); continue; }
                if (index < 0) continue;
                ByteBuffer data = p.codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && data != null) mux.video(data, info);
                p.codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        } catch (Exception failed) {
            if (ending || p.closing) return;
            problem = failed instanceof IOException && failed.getMessage() != null ? failed.getMessage() : "영상 인코더가 멈췄어요 (" + failed.getClass().getSimpleName() + ")";
            stop.now(problem);
        }
    }

    /**
     * Reads the sound, mixes the phone's sound into the microphone for 둘 다, and hands it to the AAC encoder on a strictly
     * continuous clock (ScreenRecordAudio.Clock). Each chunk is placed by when its first frame was really captured
     * (AudioRecord.getTimestamp, on the same clock as the picture): silence fills what was lost, frames go when the sound
     * runs ahead, so it stays with the picture for the whole file.
     */
    private void runAudio() {
        short[] pcm = new short[FRAMES * CHANNELS], other = new short[FRAMES * CHANNELS], raw = new short[FRAMES * CHANNELS], quiet = new short[FRAMES * CHANNELS];
        ScreenRecordAudio.Clock clock = new ScreenRecordAudio.Clock(RATE);
        // The phone's sound comes in periods of its own: about 40ms is held back, so one that arrives a little late is not a hole.
        ScreenRecordAudio.Fifo fifo = secondary == null ? null : new ScreenRecordAudio.Fifo(RATE * CHANNELS / 2, RATE * CHANNELS / 25, RATE * CHANNELS / 10);
        AudioRecord mixed = secondary;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        AudioTimestamp stamp = new AudioTimestamp(), fresh = new AudioTimestamp();
        boolean stamped = false;
        long read = 0, nextStamp = 0;
        try {
            while (!ending) {
                int frames = read(primary, primaryChannels, pcm, raw, true);
                if (frames < 0) { if (!ending) { note = "소리를 담다가 멈췄어요"; mux.audioGone(); } break; }
                if (frames == 0) continue;
                long first = read; read += frames;
                long now = System.nanoTime();
                if (now >= nextStamp) {
                    nextStamp = now + 250_000_000L;
                    try {
                        if (primary.getTimestamp(fresh, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && fresh.nanoTime > 0) {
                            stamp.framePosition = fresh.framePosition; stamp.nanoTime = fresh.nanoTime; stamped = true;
                        }
                    } catch (RuntimeException unavailable) { /* the reading time below */ }
                }
                // When the chunk's first frame was captured; before the first timestamp, read as "just now".
                long captured = stamped ? stamp.nanoTime / 1000 + (first - stamp.framePosition) * 1_000_000L / RATE : now / 1000 - frames * 1_000_000L / RATE;
                if (fifo != null) {
                    int n;
                    while ((n = read(mixed, secondaryChannels, other, raw, false)) > 0) fifo.put(other, n * CHANNELS);
                    if (n < 0) {
                        // The phone's sound stopped for good: the microphone goes on alone.
                        try { mixed.release(); } catch (RuntimeException ignored) { /* gone */ }
                        secondary = null; mixed = null; fifo = null; note = "기기 소리가 끊겼어요";
                    } else {
                        fifo.take(other, frames * CHANNELS);
                        ScreenRecordAudio.mix(pcm, other, frames * CHANNELS);
                    }
                }
                if (!clock.started()) {
                    // The clock starts one chunk before the first sound, and that chunk is silence: the encoder names its format at
                    // once (so the file can start with the first frame), and the times it is fed never jump.
                    clock.correction(captured - FRAMES * 1_000_000L / RATE, FRAMES);
                    clock.advance(feed(quiet, 0, FRAMES, clock.now(), false)); drainAudio(info, false);
                }
                int change = clock.correction(captured, frames), from = 0;
                if (change > 0) silence(quiet, change, clock, info);
                else if (change < 0) from = -change;
                if (from < frames) { clock.advance(feed(pcm, from, frames - from, clock.now(), false)); drainAudio(info, false); }
            }
            feed(quiet, 0, 0, clock.started() ? clock.now() : System.nanoTime() / 1000, true);
            drainAudio(info, true);
        } catch (IOException unwritten) {
            // The file could not take the sound (the storage is full): the recording stops and keeps what it has.
            if (!ending) stop.now(unwritten.getMessage());
        } catch (Exception failed) {
            if (!ending) note = "소리를 담다가 멈췄어요 (" + failed.getClass().getSimpleName() + ")";
            mux.audioGone();
        }
    }
    /** Frames of silence at the clock's time, in AAC-frame chunks, so the sound has no hole where frames were lost. */
    private void silence(short[] quiet, int frames, ScreenRecordAudio.Clock clock, MediaCodec.BufferInfo info) throws IOException {
        while (frames > 0 && !ending) {
            int done = feed(quiet, 0, Math.min(frames, FRAMES), clock.now(), false);
            clock.advance(done);
            drainAudio(info, false);
            if (done == 0) return;
            frames -= done;
        }
    }
    /** Frames read into out as stereo; 0 when nothing was waiting (without blocking), below 0 on an error. */
    private static int read(AudioRecord record, int channels, short[] out, short[] raw, boolean blocking) {
        int mode = blocking ? AudioRecord.READ_BLOCKING : AudioRecord.READ_NON_BLOCKING;
        if (channels >= 2) { int n = record.read(out, 0, FRAMES * CHANNELS, mode); return n < 0 ? n : n / CHANNELS; }
        int n = record.read(raw, 0, FRAMES, mode);
        if (n > 0) ScreenRecordAudio.upmix(raw, n, out);
        return n;
    }
    /**
     * Hands frames of stereo PCM (from frame from) to the encoder, the first at pts; end: its end of stream. Returns how many
     * frames it took: what the encoder had no room for is left out, and the caller's clock moves only by what was taken.
     */
    private int feed(short[] pcm, int from, int frames, long pts, boolean end) throws IOException {
        int total = frames * CHANNELS * 2, offset = 0;
        MediaCodec.BufferInfo info = null;
        for (int tries = 0; ; ) {
            int index = audio.dequeueInputBuffer(10_000);
            if (index < 0) {
                if (++tries >= (end ? 50 : 4)) return offset / (CHANNELS * 2);
                // Its input waits on its output: what it has made goes to the file, then another try.
                if (info == null) info = new MediaCodec.BufferInfo();
                drainAudio(info, false);
                continue;
            }
            ByteBuffer buffer = audio.getInputBuffer(index);
            if (buffer == null) return offset / (CHANNELS * 2);
            buffer.clear();
            int bytes = end ? 0 : Math.min(total - offset, buffer.remaining()) & ~3;
            if (bytes > 0) buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, from * CHANNELS + offset / 2, bytes / 2);
            audio.queueInputBuffer(index, 0, bytes, pts + (offset / (CHANNELS * 2)) * 1_000_000L / RATE, end ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            offset += bytes;
            if (end || offset >= total || bytes == 0) return offset / (CHANNELS * 2);
        }
    }
    private void drainAudio(MediaCodec.BufferInfo info, boolean untilEnd) throws IOException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (true) {
            int index = audio.dequeueOutputBuffer(info, untilEnd ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!untilEnd || System.nanoTime() > deadline) return; continue; }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { mux.audioFormat(audio.getOutputFormat()); continue; }
            if (index < 0) continue;
            ByteBuffer data = audio.getOutputBuffer(index);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && data != null) mux.audio(data, info);
            audio.releaseOutputBuffer(index, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /**
     * Ends the recording and finishes the file: no more frames, the sound's and the picture's encoders run to their end, the
     * MP4 is closed and published (or deleted when it has no picture). Safe to call after a failed start.
     */
    Result stop() {
        ending = true;
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) { /* gone */ } display = null; }
        // A blocking read returns once the capture stops; the sound's thread then ends its encoder.
        stopRecording(primary); stopRecording(secondary);
        join(audioThread, 3000);
        Picture p = picture;
        if (p != null && p.thread != null) p.finish(3000);
        String why = turns == 0 ? note : note.isEmpty() ? "화면을 돌린 곳에서 파일을 나눠 저장했어요" : note + " · 화면을 돌린 곳에서 파일을 나눠 저장했어요";
        Result result = mux == null ? new Result(List.of(), 0, 0, problem, why) : mux.finish(problem, why);
        release();
        return result;
    }
    /** Releases everything opened (encoders, display, capture, an unfinished file). */
    void release() {
        ending = true;
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) { /* gone */ } display = null; }
        Picture p = picture; picture = null;
        if (p != null) p.release();
        releaseAudio();
        if (mux != null) mux.abandon();
    }
    private void releaseAudio() {
        if (audio != null) { try { audio.stop(); } catch (RuntimeException ignored) { /* not started */ } try { audio.release(); } catch (RuntimeException ignored) { /* gone */ } audio = null; }
        if (echo != null) { try { echo.release(); } catch (RuntimeException ignored) { /* gone */ } echo = null; }
        if (primary != null) { primary.release(); primary = null; }
        if (secondary != null) { secondary.release(); secondary = null; }
    }
    private static void stopRecording(AudioRecord record) { if (record != null) try { record.stop(); } catch (RuntimeException ignored) { /* not recording */ } }
    private static void join(Thread thread, long millis) {
        if (thread == null) return;
        try { thread.join(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /**
     * Parts left pending by a recording that never finished (the app was stopped from the task manager, lost the
     * microphone permission, crashed or was killed while it recorded): they have no MP4 index, are hidden from the gallery
     * and Files, and still hold their full size for days. Deleted before a new recording starts; off the main thread.
     * Returns what was deleted, in plain words, or "".
     */
    static String clearUnfinished(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Uri videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Bundle query = new Bundle();
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns.IS_PENDING + "=1 AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            new String[]{Environment.DIRECTORY_MOVIES + "/" + Brand.NAME + "/", context.getPackageName(), Brand.NAME + "_화면녹화_%"});
        int count = 0; long bytes = 0;
        try (Cursor rows = resolver.query(videos, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE}, query, null)) {
            if (rows == null) return "";
            while (rows.moveToNext()) {
                long size = rows.isNull(1) ? 0 : rows.getLong(1);
                try { if (resolver.delete(ContentUris.withAppendedId(videos, rows.getLong(0)), null, null) > 0) { count++; bytes += size; } }
                catch (RuntimeException kept) { /* the next one */ }
            }
        } catch (RuntimeException unreadable) { return ""; }
        return count == 0 ? "" : "지난번에 끝내지 못한 녹화 " + count + "개(" + ScreenRecordPlan.size(bytes) + ")를 지웠어요";
    }

    // ── The file ─────────────────────────────────────────────────────────

    /**
     * The MP4 in Movies/들퍼레, through MediaStore: pending while written, published when finished. The video and audio
     * tracks are added once both formats are known (picture alone when the sound never comes), samples on the shared
     * timeline. Past 3.5GB it continues in a new file at the next key frame (_2, _3 …), since an MP4 written with 32-bit
     * offsets ends at 4GB, and likewise when the screen turned (a new picture encoder at the new size: see newPicture).
     * Stops the recording when the storage is nearly full.
     */
    static final class Mux {
        private static final long PART_BYTES = 3_500_000_000L, PENDING_BYTES = 96L << 20, WAIT_FOR_SOUND_US = 3_000_000, LOW_SPACE = 300L << 20;
        private record Sample(byte[] data, long pts, int flags) {}
        private final ContentResolver resolver;
        private final LocalDateTime at;
        private final boolean wantSound;
        private final Stop stop;
        private MediaFormat videoFormat, audioFormat;
        private boolean soundGone, started, finished, spaceWarned, nextPicture;
        private MediaMuxer muxer;
        private ParcelFileDescriptor fd;
        private Uri uri;
        private int part = 1, videoTrack = -1, audioTrack = -1;
        private ScreenRecordAudio.Timeline timeline = new ScreenRecordAudio.Timeline();
        private final List<Uri> saved = new ArrayList<>();
        private long partBytes, partFrames, bytes, duration, nextSpaceCheck;
        private final ArrayDeque<Sample> pending = new ArrayDeque<>();
        private long pendingBytes;
        private String note = "";

        Mux(Context context, LocalDateTime at, boolean wantSound, Stop stop) throws IOException {
            resolver = context.getContentResolver(); this.at = at; this.wantSound = wantSound; this.stop = stop;
            open();
        }
        private void open() throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, ScreenRecordPlan.fileName(at, part));
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + Brand.NAME);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri made;
            try { made = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values); }
            catch (RuntimeException refused) { throw new IOException("영상 저장 위치(" + ScreenRecordPlan.FOLDER + ")를 만들지 못했어요", refused); }
            if (made == null) throw new IOException("영상 저장 위치(" + ScreenRecordPlan.FOLDER + ")를 만들지 못했어요");
            uri = made;
            try {
                fd = resolver.openFileDescriptor(uri, "rw");
                if (fd == null) throw new IOException("영상 파일을 열지 못했어요");
                muxer = new MediaMuxer(fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException | RuntimeException failed) {
                closeFile(); delete(uri); uri = null;
                throw failed instanceof IOException io ? io : new IOException("영상 파일을 열지 못했어요", failed);
            }
        }
        synchronized void videoFormat(MediaFormat format) throws IOException {
            if (nextPicture && started) { nextPicture = false; videoFormat = format; nextPart(); return; }
            nextPicture = false; videoFormat = format; begin();
        }
        /**
         * A new picture encoder takes over (the screen turned), after the old one wrote all it had: its format begins the
         * next file. Before the file has begun, what the old one left waiting is dropped (a different size) and the new
         * format begins it.
         */
        synchronized void newPicture() {
            if (finished) return;
            if (!started) { pending.clear(); pendingBytes = 0; videoFormat = null; return; }
            nextPicture = true;
        }
        synchronized void audioFormat(MediaFormat format) throws IOException { if (audioFormat == null) { audioFormat = format; begin(); } }
        /** The sound stopped for good: the file goes on with the picture alone. */
        synchronized void audioGone() {
            soundGone = true;
            try { begin(); } catch (IOException failed) { stop.now(failed.getMessage()); }
        }
        private void begin() throws IOException {
            if (started || finished || muxer == null || videoFormat == null) return;
            if (wantSound && audioFormat == null && !soundGone) return;
            try {
                videoTrack = muxer.addTrack(videoFormat);
                audioTrack = audioFormat != null && !soundGone ? muxer.addTrack(audioFormat) : -1;
                muxer.start();
            } catch (RuntimeException refused) { throw new IOException("영상 파일을 시작하지 못했어요", refused); }
            started = true;
            while (!pending.isEmpty()) { Sample s = pending.poll(); writeVideo(ByteBuffer.wrap(s.data()), 0, s.data().length, s.pts(), s.flags()); }
            pendingBytes = 0;
        }
        synchronized void video(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException {
            if (finished) return;
            if (!started) {
                byte[] copy = new byte[info.size]; data.position(info.offset); data.get(copy, 0, info.size);
                pending.add(new Sample(copy, info.presentationTimeUs, info.flags)); pendingBytes += info.size;
                Sample first = pending.peekFirst();
                // The sound never named its format: the picture alone rather than nothing.
                if (pendingBytes > PENDING_BYTES || info.presentationTimeUs - first.pts() > WAIT_FOR_SOUND_US) { soundGone = true; note = "소리를 담지 못해서 화면만 녹화했어요"; begin(); }
                return;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && partBytes >= PART_BYTES) nextPart();
            writeVideo(data, info.offset, info.size, info.presentationTimeUs, info.flags);
            long now = System.nanoTime();
            if (now >= nextSpaceCheck) {
                nextSpaceCheck = now + 5_000_000_000L;
                if (!spaceWarned && free() < LOW_SPACE) { spaceWarned = true; stop.now("저장 공간이 거의 없어서 녹화를 멈췄어요"); }
            }
        }
        synchronized void audio(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException {
            if (finished || !started || audioTrack < 0) return;
            long t = timeline.audio(info.presentationTimeUs);
            if (t >= 0) write(audioTrack, data, info.offset, info.size, t, 0);
        }
        private void writeVideo(ByteBuffer data, int offset, int size, long pts, int flags) throws IOException {
            boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            long t = timeline.video(pts, key);
            if (t >= 0) write(videoTrack, data, offset, size, t, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        }
        private void write(int track, ByteBuffer data, int offset, int size, long time, int flags) throws IOException {
            MediaCodec.BufferInfo out = new MediaCodec.BufferInfo(); out.set(offset, size, time, flags);
            data.limit(offset + size); data.position(offset);
            try { muxer.writeSampleData(track, data, out); }
            catch (RuntimeException failed) { throw new IOException(free() < LOW_SPACE ? "저장 공간이 부족해서 녹화를 멈췄어요" : "영상 파일에 쓰지 못했어요", failed); }
            partBytes += size; bytes += size;
            if (track == videoTrack) partFrames++;
        }
        /**
         * The next file of a long or turned recording, beginning at this key frame (with videoFormat: the picture's size now).
         * A file that got no picture (the screen turned before its first frame) is deleted and its name used again.
         */
        private void nextPart() throws IOException {
            if (closePart()) part++;
            partBytes = 0; partFrames = 0; timeline = new ScreenRecordAudio.Timeline();
            open();
            try {
                videoTrack = muxer.addTrack(videoFormat);
                audioTrack = audioFormat != null && !soundGone ? muxer.addTrack(audioFormat) : -1;
                muxer.start();
            } catch (RuntimeException refused) { throw new IOException("다음 영상 파일을 시작하지 못했어요", refused); }
        }
        /** Closes the file being written: published (true) when it is a whole MP4 with a picture, deleted when not. */
        private boolean closePart() {
            boolean whole = false;
            if (muxer != null) {
                if (started) try { muxer.stop(); whole = partFrames > 0; } catch (RuntimeException broken) { /* deleted below */ }
                try { muxer.release(); } catch (RuntimeException ignored) { /* gone */ }
                muxer = null;
            }
            if (whole) duration += timeline.duration();
            closeFile();
            if (uri == null) return false;
            boolean kept = whole && publish(uri);
            if (kept) saved.add(uri); else delete(uri);
            uri = null;
            return kept;
        }
        /** Finishes the last file and says what was saved. */
        synchronized Result finish(String problem, String soundNote) {
            if (!finished) { finished = true; closePart(); pending.clear(); }
            String why = soundNote == null || soundNote.isEmpty() ? note : note.isEmpty() || soundNote.contains(note) ? soundNote : soundNote + " · " + note;
            return new Result(List.copyOf(saved), bytes, duration, problem == null ? "" : problem, why);
        }
        /** After a failed start: the unfinished file goes. */
        synchronized void abandon() {
            if (finished) return;
            finished = true;
            if (muxer != null) { try { muxer.release(); } catch (RuntimeException ignored) { /* gone */ } muxer = null; }
            closeFile();
            if (uri != null) { delete(uri); uri = null; }
        }
        private void closeFile() { if (fd != null) { try { fd.close(); } catch (IOException ignored) { /* closed */ } fd = null; } }
        private boolean publish(Uri file) {
            ContentValues values = new ContentValues(); values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { return resolver.update(file, values, null, null) == 1; } catch (RuntimeException refused) { return false; }
        }
        private void delete(Uri file) { try { resolver.delete(file, null, null); } catch (RuntimeException ignored) { /* best effort */ } }
        @SuppressWarnings("deprecation")
        private static long free() {
            try { return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes(); } catch (RuntimeException unknown) { return Long.MAX_VALUE; }
        }
    }
}
