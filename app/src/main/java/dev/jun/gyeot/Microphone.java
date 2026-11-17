package dev.jun.gyeot;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import dev.jun.gyeot.core.Loudness;
import java.util.Arrays;

/**
 * 16 kHz mono PCM capture. The reading thread owns the recorder's release so close() never frees it mid-read. In sensitive
 * mode (for quiet speech) it records with the speech-recognition source, skips noise suppression, uses the device's
 * automatic gain when there is one, and raises soft input in software.
 */
final class Microphone implements AutoCloseable {
    private final boolean sensitive;
    private final Loudness loudness = new Loudness();
    Microphone(boolean sensitive) { this.sensitive = sensitive; }
    /** The software gain on the frame just delivered (1 when soft input is not raised); read on the capture thread, in the sink. */
    double gain() { return sensitive ? loudness.gain() : 1.0; }
    interface Sink { void audio(byte[] pcm); void error(String message); }
    private AudioRecord record;
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;
    private volatile boolean running, muted;
    void muted(boolean value) { muted = value; }
    void start(Sink sink) throws Exception {
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("이 기기의 16kHz 마이크를 사용할 수 없어요.");
        int source = sensitive ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        AudioRecord active = new AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 12800));
        if (active.getState() != AudioRecord.STATE_INITIALIZED) { active.release(); throw new IllegalStateException("마이크를 초기화하지 못했어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요."); }
        AcousticEchoCanceler activeEcho = AcousticEchoCanceler.isAvailable() ? AcousticEchoCanceler.create(active.getAudioSessionId()) : null;
        NoiseSuppressor activeNoise = !sensitive && NoiseSuppressor.isAvailable() ? NoiseSuppressor.create(active.getAudioSessionId()) : null;
        AutomaticGainControl gainControl = sensitive && AutomaticGainControl.isAvailable() ? AutomaticGainControl.create(active.getAudioSessionId()) : null;
        if (activeEcho != null) activeEcho.setEnabled(true);
        if (activeNoise != null) activeNoise.setEnabled(true);
        if (gainControl != null) gainControl.setEnabled(true);
        try { active.startRecording(); }
        catch (RuntimeException error) { release(active, activeEcho, activeNoise, gainControl); throw new IllegalStateException("마이크를 시작하지 못했어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요."); }
        if (active.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { release(active, activeEcho, activeNoise, gainControl); throw new IllegalStateException("다른 앱이 마이크를 사용 중이라 시작하지 못했어요."); }
        synchronized (this) { record = active; echo = activeEcho; noise = activeNoise; running = true; }
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[3200];
            try {
                while (running) {
                    int count = active.read(buffer, 0, buffer.length);
                    if (count < 0) { if (running) sink.error("마이크 입력이 중단됐어요. 다른 앱의 마이크 사용 여부를 확인해 주세요."); break; }
                    if (count > 0 && !muted && running) { byte[] pcm = Arrays.copyOf(buffer, count); sink.audio(sensitive ? loudness.apply(pcm) : pcm); }
                }
            } catch (RuntimeException error) { if (running) sink.error("마이크 연결이 종료됐어요."); }
            finally { release(active, activeEcho, activeNoise, gainControl); }
        }, "gyeot-microphone");
        thread.start();
    }
    /**
     * The input this capture is routed to, an AudioDeviceInfo type (the call greeting's check, Greeter); 0 while Android does
     * not say. Any thread.
     */
    int input() {
        AudioRecord active; synchronized (this) { active = record; }
        try { AudioDeviceInfo device = active == null ? null : active.getRoutedDevice(); return device == null ? 0 : device.getType(); }
        catch (RuntimeException released) { return 0; }
    }
    private static void release(AudioRecord active, AcousticEchoCanceler activeEcho, NoiseSuppressor activeNoise, AutomaticGainControl gainControl) {
        try { active.stop(); } catch (Exception ignored) {}
        active.release();
        if (activeEcho != null) activeEcho.release();
        if (activeNoise != null) activeNoise.release();
        if (gainControl != null) gainControl.release();
    }
    @Override public synchronized void close() {
        running = false;
        // stop() unblocks the pending read; the reading thread then releases the recorder and effects.
        if (record != null) { try { record.stop(); } catch (Exception ignored) {} record = null; echo = null; noise = null; }
    }
}
