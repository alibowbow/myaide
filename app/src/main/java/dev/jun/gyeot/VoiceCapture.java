package dev.jun.gyeot;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.Utterance;
import dev.jun.gyeot.core.WakeWord;

/**
 * One thing said, from the microphone (16 kHz mono, the recognizer's source): read on its own thread and cut by Utterance;
 * what was said, silence or a failure comes once, on the main thread. stop() ends it without an answer. For 퍼레's voice talk
 * when it knows the user's voice (the words then go to Whisper, the voice to VoicePrint) and for enrolling that voice.
 */
final class VoiceCapture {
    interface Heard {
        void speech(short[] pcm);
        void silent();
        void failed(String why);
        /** Speech began (to show that it hears). */
        default void began() {}
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    /** The failure when Android gives the microphone to another app (a navigation app in front) and this one hears only silence. */
    static final String SILENCED = "silenced";

    /** Starts listening at once; waitMs of nothing said is silent. Needs the microphone permission. */
    static VoiceCapture start(long waitMs, Heard heard) {
        VoiceCapture c = new VoiceCapture();
        Thread t = new Thread(() -> c.run(waitMs, heard), "gyeot-voice-capture");
        t.setDaemon(true); t.start();
        return c;
    }
    void stop() { stopped = true; }

    private void run(long waitMs, Heard heard) {
        int buffer = Math.max(AudioRecord.getMinBufferSize(WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), WakeWord.RATE);
        AudioRecord mic;
        try { mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer); }
        catch (SecurityException | IllegalArgumentException refused) { post(() -> heard.failed("마이크를 열지 못했어요")); return; }
        if (mic.getState() != AudioRecord.STATE_INITIALIZED) { mic.release(); post(() -> heard.failed("마이크를 열지 못했어요")); return; }
        Utterance u = new Utterance(waitMs);
        short[] chunk = new short[800]; boolean began = false; long silencedSince = 0, checkAt = 0;
        try {
            mic.startRecording();
            if (mic.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { post(() -> heard.failed("마이크가 다른 곳에서 쓰이고 있어요")); return; }
            while (!stopped) {
                int n = mic.read(chunk, 0, chunk.length);
                if (n < 0) { post(() -> heard.failed("마이크 소리를 받지 못했어요")); return; }
                // Android silences a capture while another app has the microphone (준: "네이버지도 네비게이션 시작했을때 퍼레음성인식이 안돼"):
                // two seconds of that is said as it is, not waited out as silence.
                long t = android.os.SystemClock.uptimeMillis();
                if (t >= checkAt) {
                    checkAt = t + 500;
                    android.media.AudioRecordingConfiguration rc = mic.getActiveRecordingConfiguration();
                    boolean silenced = rc != null && rc.isClientSilenced();
                    if (!silenced) silencedSince = 0; else if (silencedSince == 0) silencedSince = t; else if (t - silencedSince > 2000) { post(() -> heard.failed(SILENCED)); return; }
                }
                Utterance.State s = u.add(chunk, n);
                if (s == Utterance.State.SPEAKING && !began) { began = true; post(heard::began); }
                if (s == Utterance.State.DONE) { short[] pcm = u.pcm(); post(() -> heard.speech(pcm)); return; }
                if (s == Utterance.State.SILENT) { post(heard::silent); return; }
            }
        } catch (RuntimeException failed) {
            post(() -> heard.failed("마이크 소리를 받지 못했어요"));
        } finally {
            try { mic.stop(); } catch (RuntimeException ignored) {}
            mic.release();
        }
    }
    private void post(Runnable r) { main.post(() -> { if (!stopped) r.run(); }); }
}
