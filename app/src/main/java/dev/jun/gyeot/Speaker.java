package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.VoiceLatency;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Streams 24 kHz PCM. All writes happen on one thread; flush() can be called from any thread and drops stale audio. */
final class Speaker implements AutoCloseable {
    private static final int RATE = 24000, BYTES_PER_SECOND = RATE * 2;
    /** Live models stream faster than real time; budget by duration, not packet count, so long answers are not cut off. */
    private static final long MAX_QUEUED_BYTES = BYTES_PER_SECOND * 300L;
    /**
     * Room echo keeps reaching the microphone briefly after the last sample plays; through a car or a Bluetooth speaker for
     * longer, since their own delay comes on top (core/VoiceRoute, set by the call as the outputs change).
     */
    private volatile long echoTailMs = dev.jun.gyeot.core.VoiceRoute.ROOM_TAIL_MS;
    void echoTail(long ms) { echoTailMs = ms; }
    /** How long a chunk waits for Android to report its first frame as played before the measurement is dropped. */
    private static final long AUDIBLE_WAIT_MS = 5000;
    /** After this long without a chunk, and once the written audio should have played, the track pauses and gives up focus. */
    private static final long IDLE_MS = 300;
    /**
     * How much must be written before the track starts sounding. Android's default is the whole
     * buffer (0.5 s here), and a track waits for it again after every pause or flush, so each answer started up to 0.5 s late.
     * Live streams its audio faster than real time, so 100 ms is enough to start without running dry.
     */
    static final int START_FRAMES = RATE / 10;
    private static final class Packet {
        final int epoch; final byte[] bytes; final Runnable done; final LongConsumer audible;
        Packet(int epoch, byte[] bytes, Runnable done, LongConsumer audible) { this.epoch = epoch; this.bytes = bytes; this.done = done; this.audible = audible; }
    }
    private final LinkedBlockingQueue<Packet> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicInteger epoch = new AtomicInteger();
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioTrack track;
    private volatile boolean running = true;
    /** Estimated uptime (ms) at which everything written so far has finished playing. */
    private volatile long audibleUntil;
    Speaker(Context context) {
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> { if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) flush(); }, main).build();
        track = new AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(BYTES_PER_SECOND / 2, AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2))
            .setTransferMode(AudioTrack.MODE_STREAM).build();
        // The buffer stays 0.5 s deep for smooth playback; only the start waits for less of it.
        try { track.setStartThresholdInFrames(START_FRAMES); } catch (RuntimeException kept) {}
        new Thread(this::loop, "gyeot-speaker").start();
    }
    /** True while speech is queued, playing, or its echo may still be in the room. */
    boolean isAudible() { return !queue.isEmpty() || SystemClock.uptimeMillis() < audibleUntil + echoTailMs; }
    /** True while speech is queued or still playing, without the room-echo tail. */
    boolean isPlaying() { return !queue.isEmpty() || SystemClock.uptimeMillis() < audibleUntil; }
    boolean play(byte[] pcm) { return play(pcm, null); }
    /**
     * As play(pcm); audible, when given, later receives the System.nanoTime milliseconds at which this chunk's first frame
     * was played, from AudioTrack.getTimestamp. It is never called when Android gives no timestamp or the chunk is flushed.
     */
    boolean play(byte[] pcm, LongConsumer audible) {
        if (!running) return false;
        if (pcm.length == 0) return true;
        if (queuedBytes.addAndGet(pcm.length) > MAX_QUEUED_BYTES) { queuedBytes.addAndGet(-pcm.length); return false; }
        queue.offer(new Packet(epoch.get(), pcm, null, audible));
        return true;
    }
    /** Runs done on the main thread after everything queued before it has played, or right away after a flush. */
    void finish(Runnable done) { if (!running || !queue.offer(new Packet(epoch.get(), new byte[0], done, null))) main.post(done); }
    private void loop() {
        // Playback state belongs to this thread alone; flush() only bumps the epoch, so a flush that races play() can never leave writes blocked on a paused track.
        boolean playing = false; int seen = epoch.get();
        // Frames written since the last flush (where Android's frame count restarts), and one chunk waiting to be heard.
        long frames = 0, watchFrame = 0, watchSince = 0, watchWritten = 0; LongConsumer watch = null;
        // Uptime since which polls have found no chunk (0 while chunks arrive): a pending watch polls fast, the idle rule does not.
        long quiet = 0;
        AudioTimestamp stamp = new AudioTimestamp();
        try {
            while (running) {
                long polled = SystemClock.uptimeMillis();
                Packet packet = queue.poll(watch != null ? 20 : IDLE_MS, TimeUnit.MILLISECONDS);
                int current = epoch.get();
                if (current != seen) { seen = current; playing = false; frames = 0; watch = null; }
                if (watch != null && heard(stamp, watch, watchFrame, watchWritten)) watch = null;
                if (watch != null && SystemClock.uptimeMillis() - watchSince > AUDIBLE_WAIT_MS) watch = null;
                if (packet == null) {
                    if (quiet == 0) quiet = polled;
                    long now = SystemClock.uptimeMillis();
                    if (playing && now >= audibleUntil && now - quiet >= IDLE_MS) { playing = false; track.pause(); manager.abandonAudioFocusRequest(focus); }
                    continue;
                }
                quiet = 0;
                queuedBytes.addAndGet(-packet.bytes.length);
                if (packet.epoch != current) { if (packet.done != null) main.post(packet.done); continue; }
                if (packet.done != null) {
                    long deadline = SystemClock.uptimeMillis() + 10000;
                    while (running && packet.epoch == epoch.get() && SystemClock.uptimeMillis() < Math.min(audibleUntil, deadline)) Thread.sleep(20);
                    main.post(packet.done); continue;
                }
                if (!playing) {
                    // Focus can be refused during a phone call; drop the speech instead of talking over it.
                    if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) continue;
                    playing = true; track.play();
                }
                if (packet.audible != null) { watch = packet.audible; watchFrame = frames; watchSince = SystemClock.uptimeMillis(); watchWritten = System.nanoTime(); }
                for (int offset = 0; running && offset < packet.bytes.length;) {
                    if (packet.epoch != epoch.get()) { discardStale(); playing = false; break; }
                    int written = track.write(packet.bytes, offset, Math.min(4096, packet.bytes.length - offset), AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) break;
                    offset += written; frames += written / 2;
                    if (packet.epoch != epoch.get()) { discardStale(); playing = false; break; }
                    long now = SystemClock.uptimeMillis();
                    audibleUntil = Math.max(now, audibleUntil) + written * 1000L / BYTES_PER_SECOND;
                }
            }
        } catch (Exception ignored) {
            // The service owns restart/close; audio focus loss never restarts recording.
        } finally {
            try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) {}
            manager.abandonAudioFocusRequest(focus);
            Packet pending; while ((pending = queue.poll()) != null) if (pending.done != null) main.post(pending.done);
        }
    }
    /**
     * Whether Android has played the watched frame (VoiceLatency.audibleAt decides from the timestamp); until then the
     * watch keeps polling, up to AUDIBLE_WAIT_MS.
     */
    private boolean heard(AudioTimestamp stamp, LongConsumer watch, long frame, long written) {
        try {
            if (!track.getTimestamp(stamp)) return false;
            long at = VoiceLatency.audibleAt(stamp.framePosition, stamp.nanoTime, frame, written, RATE);
            if (at == VoiceLatency.NONE) return false;
            watch.accept(at / 1_000_000);
            return true;
        } catch (Exception e) { return true; }
    }
    /** A flush raced a blocking write; clear whatever stale chunk landed in the track buffer after it. */
    private void discardStale() { try { track.pause(); track.flush(); } catch (Exception ignored) {} }
    void flush() {
        epoch.incrementAndGet();
        Packet pending;
        while ((pending = queue.poll()) != null) { queuedBytes.addAndGet(-pending.bytes.length); if (pending.done != null) main.post(pending.done); }
        audibleUntil = 0;
        // Silences now and unblocks a pending write; the writer thread restarts playback for the next epoch itself.
        try { track.pause(); track.flush(); } catch (Exception ignored) {}
        manager.abandonAudioFocusRequest(focus);
    }
    @Override public void close() { running = false; flush(); }
}
