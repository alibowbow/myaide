package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 다시 듣기 for each caption of a spoken conversation: the voice behind it as it was heard (an answer's audio as it played,
 * the user's words as the microphone took them), kept in memory only, never written anywhere, the newest MAX_CLIPS and at
 * most MAX_BYTES. A caption without its sound is read aloud instead (Speech, which waits until no conversation is on).
 * One plays at a time and a second tap stops it; while it plays, a conversation that is on stops listening (the voice
 * assistant's microphone is muted, 음성 대화's held), so the voice does not hear its own words again.
 */
final class VoiceClips {
    private VoiceClips() {}
    static final int MAX_CLIPS = 60, MAX_BYTES = 24 * 1024 * 1024;
    /** Sound shorter than this is no clip (a cough, a click). */
    private static final int MIN_MILLIS = 300;
    record Clip(byte[] pcm, int rate) {}
    private static final Map<String, Clip> CLIPS = new LinkedHashMap<>(16, 0.75f, true);
    private static int bytes;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String playing;
    private static volatile Thread player;

    /** Keeps the sound behind a caption (16-bit mono PCM at rate). */
    static synchronized void put(String key, byte[] pcm, int rate) {
        if (key == null || key.isEmpty() || pcm == null || rate <= 0 || pcm.length < rate * 2L * MIN_MILLIS / 1000) return;
        Clip old = CLIPS.remove(key); if (old != null && !shared(old)) bytes -= old.pcm.length;
        Clip clip = new Clip(pcm, rate); CLIPS.put(key, clip); bytes += pcm.length;
        trim(); changed();
    }
    /** The same sound under another caption's key (a line once written where its screen keeps it). */
    static synchronized void alias(String from, String to) {
        Clip clip = CLIPS.get(from); if (clip == null || to == null || to.isEmpty()) return;
        CLIPS.put(to, clip);
        trim(); changed();
    }
    private static boolean shared(Clip clip) { for (Clip c : CLIPS.values()) if (c == clip) return true; return false; }
    private static void trim() {
        java.util.Iterator<Map.Entry<String, Clip>> oldest = CLIPS.entrySet().iterator();
        while ((CLIPS.size() > MAX_CLIPS || bytes > MAX_BYTES) && oldest.hasNext()) {
            Clip c = oldest.next().getValue(); oldest.remove();
            if (!shared(c)) bytes -= c.pcm.length;
        }
    }
    static synchronized boolean has(String key) { return key != null && CLIPS.containsKey(key); }
    static boolean playing(String key) { return key != null && key.equals(playing); }
    /** The clip playing now, or "". */
    static String playingKey() { String k = playing; return k == null ? "" : k; }
    static void listen(Runnable l) { LISTENERS.addIfAbsent(l); }
    static void unlisten(Runnable l) { LISTENERS.remove(l); }
    private static void changed() { MAIN.post(() -> { for (Runnable l : LISTENERS) l.run(); }); }

    /**
     * 다시 듣기: plays the caption's own sound, or stops it when it is the one playing; without its sound the words are read
     * aloud (a second tap stops that too).
     */
    static void toggle(Context context, AppStore store, String key, String text) {
        if (playing(key)) { stop(); return; }
        Clip clip; synchronized (VoiceClips.class) { clip = CLIPS.get(key); }
        stop();
        if (clip == null) { if (text != null && !text.isBlank()) store.speech().toggle("clip:" + key, text); return; }
        play(context.getApplicationContext(), store, key, clip);
    }
    static void stop() { Thread t = player; player = null; if (t != null) t.interrupt(); }

    private static void play(Context context, AppStore store, String key, Clip clip) {
        playing = key; changed();
        Thread t = new Thread(() -> {
            AudioManager manager = context.getSystemService(AudioManager.class);
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build();
            Runnable release = hold(context, store);
            AudioTrack track = null;
            try {
                int min = AudioTrack.getMinBufferSize(clip.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(clip.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min * 2, clip.rate / 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if (manager != null) manager.requestAudioFocus(focus);
                track.play();
                int step = clip.rate / 10 * 2;
                for (int at = 0; at < clip.pcm.length && player == Thread.currentThread(); at += step) {
                    int wrote = track.write(clip.pcm, at, Math.min(step, clip.pcm.length - at));
                    if (wrote < 0) break;
                }
                // What was written plays out before the track goes.
                long end = System.currentTimeMillis() + 2000;
                while (player == Thread.currentThread() && System.currentTimeMillis() < end && track.getPlaybackHeadPosition() < clip.pcm.length / 2) Thread.sleep(40);
            } catch (InterruptedException stopped) { /* stopped by a tap or another clip */ }
            catch (RuntimeException unplayable) { /* the clip stays; nothing plays */ }
            finally {
                if (track != null) try { track.pause(); track.flush(); track.release(); } catch (RuntimeException ignored) {}
                if (manager != null) manager.abandonAudioFocusRequest(focus);
                release.run();
                if (key.equals(playing) && (player == null || player == Thread.currentThread())) { playing = null; changed(); }
                if (player == Thread.currentThread()) player = null;
            }
        }, "gyeot-voice-clip");
        t.setDaemon(true); player = t; t.start();
    }

    /** Stops a conversation that is on from listening while the clip plays; what it returns gives the microphone back. */
    private static Runnable hold(Context context, AppStore store) {
        VoiceChat chat = store.voiceChat;
        if (chat != null && !chat.held()) {
            MAIN.post(() -> chat.hold(true));
            return () -> MAIN.post(() -> { if (store.voiceChat == chat && chat.held()) chat.hold(false); });
        }
        if (store.voiceActive && !store.muted) {
            mute(context);
            return () -> MAIN.post(() -> { if (store.voiceActive && store.muted) mute(context); });
        }
        return () -> {};
    }
    private static void mute(Context context) {
        try { context.startService(new Intent(context, AssistantService.class).setAction(AssistantService.MUTE)); } catch (RuntimeException notRunning) {}
    }
}
