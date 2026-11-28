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
    /**
     * 목소리 크기 (목소리와 대화 → 말하기와 알림): each chunk is brought up to the chosen level just before it is written
     * (core/VoiceLevel), higher while a car or a Bluetooth speaker is connected. The writer thread alone uses these.
     */
    private final dev.jun.gyeot.core.VoiceLevel leveler = new dev.jun.gyeot.core.VoiceLevel();
    private boolean intoCar; private long outputsAt = Long.MIN_VALUE;
    /** How often the connected outputs are looked at again for intoCar. */
    private static final long OUTPUTS_MS = 2000;
    private final Context app;
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioTrack track;
    private volatile boolean running = true;
    /** Estimated uptime (ms) at which everything written so far has finished playing. */
    private volatile long audibleUntil;
    /**
     * How loud each recent write plays, and when (uptime ms): the microphone's echo is measured against it (core/BargeIn).
     * Written by the writer thread, read by the microphone's.
     */
    private final dev.jun.gyeot.core.PlayLevel levels = new dev.jun.gyeot.core.PlayLevel();
    /**
     * The loudest speech playing around now (the last 700 ms to the next 100 ms), as 16-bit RMS; 0 when nothing plays. The
     * window spans the pause between two words or sentences of one answer, so its room echo is never taken for the user.
     */
    double level() { return levels.level(SystemClock.uptimeMillis()); }
    Speaker(Context context) { this(context, false, false); }
    Speaker(Context context, boolean call) { this(context, call, false); }
    /**
     * A lesson read aloud (판서), played as media: Android lets a screen recording take in only media, game and unknown
     * sound, never an assistant's or a call's (준: "판서강의는 왜 녹화할 때 미디어 소리가 녹음이 안되지?"), so 판서's voice is media
     * like a video's, and follows the media volume.
     */
    static Speaker media(Context context) { return new Speaker(context, false, true); }
    /**
     * call: played as a call's voice (with CallRoute), so the phone's echo cancelling hears it and takes it out of the
     * microphone, and a user speaking over it is heard at an ordinary voice; otherwise as an assistant's speech.
     */
    private Speaker(Context context, boolean call, boolean media) {
        app = context.getApplicationContext();
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(call ? AudioAttributes.USAGE_VOICE_COMMUNICATION : media ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
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
                // As it will sound: the echo guard below measures what is really played (core/BargeIn).
                byte[] bytes = leveler.apply(packet.bytes, mode(), intoCar());
                for (int offset = 0; running && offset < bytes.length;) {
                    if (packet.epoch != epoch.get()) { discardStale(); playing = false; break; }
                    int written = track.write(bytes, offset, Math.min(4096, bytes.length - offset), AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) break;
                    offset += written; frames += written / 2;
                    if (packet.epoch != epoch.get()) { discardStale(); playing = false; break; }
                    long now = SystemClock.uptimeMillis(), from = Math.max(now, audibleUntil);
                    audibleUntil = from + written * 1000L / BYTES_PER_SECOND;
                    levels.add(from, audibleUntil, dev.jun.gyeot.core.BargeIn.rms(java.util.Arrays.copyOfRange(bytes, offset - written, offset)));
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
    /** The 목소리 크기 chosen now (read for each chunk, so a change applies at once); as it comes outside the app's process. */
    private dev.jun.gyeot.core.VoiceLevel.Mode mode() {
        try { return ((GyeotApp) app).store().voiceLevel(); } catch (RuntimeException outside) { return dev.jun.gyeot.core.VoiceLevel.Mode.OFF; }
    }
    /** intoCar(Context, AudioManager), looked at again at most every OUTPUTS_MS. */
    private boolean intoCar() {
        long now = SystemClock.uptimeMillis();
        if (outputsAt == Long.MIN_VALUE || now - outputsAt >= OUTPUTS_MS) { outputsAt = now; intoCar = intoCar(app, manager); }
        return intoCar;
    }
    /**
     * Whether the voice goes into a car or a Bluetooth speaker: a connected Bluetooth output that is not earphones
     * (core/VoiceRoute.loudBluetooth, the echo guard's own rule; the wake word's car always counts).
     */
    static boolean intoCar(Context context, AudioManager audio) {
        if (audio == null) return false;
        try {
            String car = "";
            try { car = ((GyeotApp) context.getApplicationContext()).store().settings.getString(WakeWordFiles.CAR, ""); } catch (RuntimeException none) { /* no car chosen */ }
            java.util.List<dev.jun.gyeot.core.VoiceRoute.Output> outputs = new java.util.ArrayList<>();
            for (android.media.AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = device.getType();
                if (type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) continue;
                String address = device.getAddress() == null ? "" : device.getAddress();
                outputs.add(new dev.jun.gyeot.core.VoiceRoute.Output(dev.jun.gyeot.core.VoiceRoute.Kind.BLUETOOTH, bluetoothClass(context, address), !car.isEmpty() && car.equalsIgnoreCase(address)));
            }
            return dev.jun.gyeot.core.VoiceRoute.loudBluetooth(outputs);
        } catch (RuntimeException unknown) { return false; }
    }
    /** A Bluetooth device's class (headphones, hands-free, car audio …), or -1 when Android does not tell this app. */
    private static int bluetoothClass(Context context, String address) {
        try {
            android.bluetooth.BluetoothManager bluetooth = context.getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter adapter = bluetooth == null ? null : bluetooth.getAdapter();
            if (adapter == null || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return -1;
            android.bluetooth.BluetoothClass kind = adapter.getRemoteDevice(address).getBluetoothClass();
            return kind == null ? -1 : kind.getDeviceClass();
        } catch (RuntimeException denied) { return -1; }
    }
    void flush() {
        epoch.incrementAndGet();
        Packet pending;
        while ((pending = queue.poll()) != null) { queuedBytes.addAndGet(-pending.bytes.length); if (pending.done != null) main.post(pending.done); }
        audibleUntil = 0; levels.clear();
        // Silences now and unblocks a pending write; the writer thread restarts playback for the next epoch itself.
        try { track.pause(); track.flush(); } catch (Exception ignored) {}
        manager.abandonAudioFocusRequest(focus);
    }
    @Override public void close() { running = false; flush(); }
}
