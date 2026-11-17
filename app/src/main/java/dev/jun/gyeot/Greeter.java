package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.GeminiSpeech;
import dev.jun.gyeot.core.Greeting;
import dev.jun.gyeot.core.LiveWarmup;
import dev.jun.gyeot.core.RequestAbort;
import dev.jun.gyeot.core.VoiceLatency;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * 켤 때 인사하기. The voice assistant sounds the moment a call starts, before the Live socket is
 * ready, on a short-lived AudioTrack of its own: never the call's Speaker, so the echo guard, the connection's closes and
 * the Speaker's flushes neither count it nor cut it, and nothing here ever touches the microphone. Where the microphone can
 * hear the output (the phone's speaker, any A2DP device, wired headphones or a line-out, a route Android does not tell) it
 * is a very short soft tick with no words and no audio focus. Only on a headset the microphone cannot hear (a wired headset,
 * Bluetooth SCO, or a USB or BLE headset whose own microphone the call records) is it "네, 말씀하세요." in the assistant's
 * Live voice from a clip saved on the phone, else the tick too. A missing clip is made after an assistant call ends, with one
 * Gemini speech request on the voice key that call used; a failed or unsavable one waits a day. The main screen also warms
 * the network path to the Live host. All of it only while the switch is on. One per process (AppStore.greeter()); main
 * thread unless noted. Nothing here logs.
 */
final class Greeter {
    /** How long after an assistant call ends its missing clip is asked for. */
    static final long AFTER_CALL_MS = 2000;
    /** After its last sample the track stays this long, for the output's own latency (Bluetooth), and at most LATE_MS more. */
    static final long END_MS = 300, LATE_MS = 2000;
    /** Checks of a playing greeting before its track goes whatever the clock says (20 s or more, past any greeting's end). */
    private static final int MAX_CHECKS = 1000;
    /** How the greeting sounded, for the call's log line. */
    static final String CLIP = "저장된 목소리", TICK = "틱 소리";
    /** Settings: the clips not asked for again yet (Greeting.Failures). */
    static final String FAILURES = "greetingClipFailures";
    /**
     * The warm-up's network step, a test seam: under Robolectric it does nothing unless a test puts its own, so no test run
     * ever opens a real connection.
     */
    static volatile LiveWarmup.Connector connector = "robolectric".equals(Build.FINGERPRINT) ? LiveWarmup.NONE : LiveWarmup::handshake;
    /** When the greeting was first heard (System.nanoTime ms, VoiceLatency's clock) and how; main thread. */
    interface Heard { void heard(long at, String how); }
    private record Clip(String key, byte[] pcm) {}

    private final Context context;
    private final AppStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Greeting.Cache cache;
    private final ExecutorService files = daemon("gyeot-greeting"), network = daemon("gyeot-live-warmup");
    /** The greeting's sound: each AudioTrack is made, watched and released on this one thread, one greeting after another. */
    private final ScheduledExecutorService sound = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "gyeot-greeting-sound"); t.setDaemon(true); return t; });
    private final LiveWarmup warmup = new LiveWarmup(host -> connector.warm(host));
    /** The clip last read, so a call does not read the file again. */
    private volatile Clip clip;
    /**
     * The clip request waiting for its time after a call, and the stop of the one being made (RequestAbort.STOPPED once a call
     * started, so a request that has not reached the network yet never goes out); a call's start drops both.
     */
    private Runnable pending;
    private volatile AtomicReference<Runnable> request;
    /** Counts cancels: a request begun before the latest one is neither saved nor counted as failed. */
    private volatile int fills;
    private boolean filling;

    Greeter(Context context, AppStore store) {
        this.context = context.getApplicationContext(); this.store = store;
        cache = new Greeting.Cache(new File(this.context.getFilesDir(), "greeting"));
    }
    private static ExecutorService daemon(String name) { return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; }); }

    /** The saved clip for this speech voice and text, from memory or from the phone (a small read); null when none is saved. Any thread. */
    byte[] clip(String voice, String text) {
        String key = Greeting.key(voice, text);
        Clip held = clip;
        if (held != null && held.key().equals(key)) return held.pcm();
        byte[] pcm = cache.read(key);
        if (pcm != null) clip = new Clip(key, pcm);
        return pcm;
    }

    /**
     * A call may start soon (the main screen showed, MainActivity.onStart): the greeting's clip is read into memory, and the path
     * to the Live host warmed, with the voice key on a validated network that is neither metered nor roaming. Never during a
     * call, and nothing at all with the switch off, as before 켤 때 인사하기.
     */
    void warm() {
        if (store.voiceActive || !store.greetOnStart()) return;
        String voice = Greeting.voice(store.voice());
        if (!voice.isEmpty()) { String text = Greeting.text(store.assistantName()); files.execute(() -> clip(voice, text)); }
        if (store.secrets.hasKey() && unmetered(context)) warmup.maybe(SystemClock.elapsedRealtime(), network);
    }

    /**
     * An assistant call ended: the clip its greeting plays on a headset is made shortly, for the voice and name that call used
     * and on the voice key it used. Not for 기본, whose voice the app cannot know, and not when one is saved or failed within a day.
     */
    void afterCall(String key, String liveVoice, String name) {
        cancelFill();
        String voice = Greeting.voice(liveVoice);
        if (voice.isEmpty() || key == null || key.isBlank()) return;
        String text = Greeting.text(name);
        Runnable later = () -> { pending = null; fill(key, voice, text); };
        pending = later;
        main.postDelayed(later, AFTER_CALL_MS);
    }
    /**
     * A call starts: a clip request waiting for its time is dropped, and one being made is stopped wherever it is (before the
     * network, in its lookup, or on the wire); neither is saved or counted as failed.
     */
    void cancelFill() {
        fills++;
        Runnable waiting = pending; pending = null;
        if (waiting != null) main.removeCallbacks(waiting);
        AtomicReference<Runnable> current = request;
        Runnable stop = current == null ? null : current.getAndSet(RequestAbort.STOPPED);
        // Disconnecting can block on the socket; never on the main thread.
        if (stop != null && stop != RequestAbort.STOPPED) { Thread thread = new Thread(() -> { try { stop.run(); } catch (Exception ignored) {} }, "greeting-abort"); thread.setDaemon(true); thread.start(); }
    }
    /** One Gemini speech request for the clip, unless a call is on, the network is not validated, or it is saved or held off. */
    private void fill(String key, String voice, String text) {
        // No Gemini speech request while it is off (AppStore.geminiSpeech): the greeting keeps a clip saved earlier, or ticks.
        if (!store.geminiSpeech() || store.voiceActive || filling || !store.greetOnStart() || !Speech.online(context)) return;
        String clipKey = Greeting.key(voice, text);
        long now = System.currentTimeMillis();
        if (cache.has(clipKey) || !Greeting.Failures.due(store.settings.getString(FAILURES, ""), clipKey, now)) return;
        int mine = fills; filling = true;
        String model = GeminiSpeech.model(store.gemini.keyModels());
        AtomicReference<Runnable> stop = new AtomicReference<>();
        request = stop;
        files.execute(() -> {
            // 1: saved. -1: the request failed or its audio could not be kept. 0: not asked (the key changed, or a call started).
            int outcome = 0;
            try {
                // Only while the call's key is still the saved voice key (a key replaced since is never used), and checked again
                // right before the request: a call that started since stops it here, or through stop once it is on its way.
                if (key.equals(store.secrets.get()) && mine == fills && !store.voiceActive) {
                    byte[] pcm = Greeting.trim(Speech.GeminiVoice.synthesize(key, model, text, voice, stop));
                    if (mine == fills) { cache.write(clipKey, pcm); outcome = 1; }
                }
            } catch (Exception unsaved) { outcome = -1; }
            int result = outcome;
            main.post(() -> {
                filling = false;
                // An aborted request is not a failure: the next assistant call's end asks again.
                if (result == 0 || mine != fills) return;
                String saved = store.settings.getString(FAILURES, "");
                if (result < 0) store.settings.edit().putString(FAILURES, Greeting.Failures.failed(saved, clipKey, now)).apply();
                else if (saved.contains(clipKey)) store.settings.edit().putString(FAILURES, Greeting.Failures.made(saved, clipKey)).apply();
            });
        });
    }

    /**
     * Greets now, for a call, on the greeting's own output; the sound is chosen and made on its own thread. input tells the
     * call's microphone route (Microphone.input), read there once the microphone has started.
     */
    Playing play(String liveVoice, String name, IntSupplier input, Heard heard) {
        Playing playing = new Playing(Greeting.voice(liveVoice), Greeting.text(name), input, heard);
        sound.execute(playing::begin);
        return playing;
    }

    /**
     * Whether the output goes only where the microphone cannot hear it, so words may be said: a wired headset (it has its own
     * microphone) or Bluetooth SCO, or a USB or BLE headset while the call records from a microphone that is not the phone's
     * own. Wired headphones (no microphone: they may be a car's AUX line or a USB-C adapter's line-out), the phone's speaker,
     * any A2DP device (it may be a car or a speaker) and a route Android does not tell all count as heard.
     */
    private boolean headset(int input) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        List<AudioDeviceInfo> route;
        try { route = audio == null ? List.of() : audio.getAudioDevicesForAttributes(Speech.ATTRIBUTES); }
        catch (RuntimeException unknown) { return false; }
        if (route.isEmpty()) return false;
        for (AudioDeviceInfo device : route) switch (device.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {}
            case AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET -> { if (input == 0 || input == AudioDeviceInfo.TYPE_BUILTIN_MIC) return false; }
            default -> { return false; }
        }
        return true;
    }
    /** A validated default network that is neither metered nor roaming. */
    private static boolean unmetered(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            Network active = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities now = active == null ? null : manager.getNetworkCapabilities(active);
            return now != null && now.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && now.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                && (now.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) || now.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED));
        } catch (RuntimeException unknown) { return false; }
    }

    /** One greeting on its own short-lived AudioTrack: begun, watched and released on the sound thread; stop() from any thread. */
    final class Playing {
        private final String voice, text; private final IntSupplier input; private final Heard heard;
        private volatile boolean stopped;
        private AudioTrack track;
        private AudioFocusRequest focus;
        private Playing(String voice, String text, IntSupplier input, Heard heard) { this.voice = voice; this.text = text; this.input = input; this.heard = heard; }
        private void begin() {
            if (stopped) return;
            byte[] saved = !voice.isEmpty() && headset(input.getAsInt()) ? clip(voice, text) : null;
            boolean spoken = saved != null;
            byte[] pcm = spoken ? saved : Greeting.tick();
            String how = spoken ? CLIP : TICK;
            // Only the spoken clip takes the audio focus, as the Live output does: the tick is too short to duck music or pause a podcast.
            if (spoken) {
                AudioManager audio = context.getSystemService(AudioManager.class);
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(Speech.ATTRIBUTES)
                    .setOnAudioFocusChangeListener(change -> { if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop(); }, main).build();
                // Refused (a phone call has the audio): nothing sounds, as with the Live output.
                if (audio != null && audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { focus = null; return; }
            }
            // Its call ended meanwhile: the release stop() queued gives the focus back.
            if (stopped) return;
            try {
                int frames = pcm.length / 2;
                track = new AudioTrack.Builder().setAudioAttributes(Speech.ATTRIBUTES)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Greeting.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(pcm.length, AudioTrack.getMinBufferSize(Greeting.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                // All of it is in the buffer before it plays, and it starts once it is: a short sound never waits for more.
                track.setStartThresholdInFrames(frames);
                long written = System.nanoTime();
                track.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
                track.play();
                long end = SystemClock.uptimeMillis() + Greeting.millis(pcm) + END_MS;
                sound.schedule(() -> watch(frames, written, how, end, false, 1), 20, TimeUnit.MILLISECONDS);
            } catch (RuntimeException failed) { release(); }
        }
        /** Tells when the first frame was heard (AudioTrack timestamp), then lets the track go once its sound has played. */
        private void watch(int frames, long written, String how, long end, boolean told, int checks) {
            if (stopped || track == null) return;
            if (!told) {
                long at = audible(written);
                if (at != VoiceLatency.NONE) { told = true; long ms = at / 1_000_000; main.post(() -> { if (!stopped) heard.heard(ms, how); }); }
            }
            long now = SystemClock.uptimeMillis();
            boolean played;
            try { played = track.getPlaybackHeadPosition() >= frames; } catch (RuntimeException gone) { played = true; }
            if (now >= end && (played || now >= end + LATE_MS) || checks >= MAX_CHECKS) { release(); return; }
            boolean heardYet = told;
            sound.schedule(() -> watch(frames, written, how, end, heardYet, checks + 1), told ? 50 : 20, TimeUnit.MILLISECONDS);
        }
        private long audible(long written) {
            try {
                AudioTimestamp stamp = new AudioTimestamp();
                return track.getTimestamp(stamp) ? VoiceLatency.audibleAt(stamp.framePosition, stamp.nanoTime, 0, written, Greeting.RATE) : VoiceLatency.NONE;
            } catch (RuntimeException unknown) { return VoiceLatency.NONE; }
        }
        private void release() {
            AudioTrack held = track; track = null;
            if (held != null) { try { held.pause(); held.flush(); } catch (RuntimeException ignored) {} held.release(); }
            AudioFocusRequest request = focus; focus = null;
            AudioManager audio = context.getSystemService(AudioManager.class);
            if (request != null && audio != null) audio.abandonAudioFocusRequest(request);
        }
        /** Silences the greeting now: its call ended, or another app (a phone call) took the audio. */
        void stop() { if (stopped) return; stopped = true; sound.execute(this::release); }
    }
}
