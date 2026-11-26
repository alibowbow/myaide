package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The phone's own speech recognizer in Korean, with no Gemini key: on-device when the phone has Korean for it, else the
 * default recognition service (which may send the audio to its provider, usually Google). One Ear is one listening; it is
 * made, used and always destroyed on the main thread. Partial results come as they are heard.
 */
final class SpeechInput {
    static final String LANGUAGE = "ko-KR";
    /** What one listening hears, on the main thread; heard, silent or failed comes once, last. */
    interface Listener {
        void ready();
        void partial(String words);
        /** The recognizer heard the speech end (it then works out the final words). */
        void ended();
        void heard(String words);
        void failed(String reason);
        /** The listening ended hearing no words (no match, or no speech in time): a failure unless the listener listens again. */
        default void silent(String reason) { failed(reason); }
    }
    /** One listening: start once; finish() asks for the final words now; close() stops and destroys, also after it ended. */
    interface Ear { void start(Listener listener); void finish(); void close(); }
    /** Makes an Ear; tests pass their own. */
    interface Ears {
        Ear open(Context context);
        /** Some recognizer can run here. */
        default boolean available(Context context) { return SpeechInput.available(context); }
        /** The next listening is known to be on-device, so the audio stays on the phone. */
        default boolean onDevice(Context context) { return SpeechInput.onDevice(context); }
    }
    static final Ears DEVICE = Device::new;
    /** Whether on-device recognition has Korean installed, learned once per process (null until asked). */
    private static volatile Boolean localKorean;
    private SpeechInput() {}

    /** Some recognizer can run here: an on-device one or a recognition service Android lets this app see. */
    static boolean available(Context context) {
        try { return SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context); }
        catch (RuntimeException unknown) { return false; }
    }
    /** On-device with Korean, as learned by a listening in this process; false while unknown (the default service may hear it). */
    static boolean onDevice(Context context) {
        try { return Boolean.TRUE.equals(localKorean) && SpeechRecognizer.isOnDeviceRecognitionAvailable(context); }
        catch (RuntimeException unknown) { return false; }
    }
    static Intent intent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Punctuation where the recognizer can (a "?" for a question; 준: "퍼레가 의문문을 못 알아 들어").
            .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
            // 퍼레's name, so it is not heard as 벌레 or 버려 (준: "퍼레가 자기 이름을 못알아듣고"); recognizers that cannot bias ignore it.
            .putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new java.util.ArrayList<>(java.util.List.of("퍼레", "퍼레야", "들퍼레")));
    }
    /** What the user is told when a recognizer error ends a listening. */
    static String reason(int error) {
        return switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말씀을 알아듣지 못했어요. 다시 말해 주세요.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "음성 입력에는 마이크 권한이 필요해요.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 다른 곳에서 쓰이고 있어요. 잠시 뒤 다시 해 주세요.";
            case SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "음성 인식 서비스에 연결하지 못했어요. 인터넷을 확인해 주세요.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "기기 음성 인식에 한국어가 없어요. Android 설정에서 음성 인식 언어를 확인해 주세요.";
            case SpeechRecognizer.ERROR_AUDIO -> "마이크 소리를 받지 못했어요.";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "음성 인식 요청이 많아요. 잠시 뒤 다시 해 주세요.";
            default -> "음성을 알아듣지 못했어요. 다시 해 주세요.";
        };
    }
    private static boolean korean(List<String> languages) {
        if (languages != null) for (String language : languages) if (language != null && language.toLowerCase(Locale.ROOT).startsWith("ko")) return true;
        return false;
    }

    /** Android's SpeechRecognizer: on-device for ko-KR when it is installed (checkRecognitionSupport), else the default service. */
    static final class Device implements Ear {
        private final Context context;
        private SpeechRecognizer recognizer;
        private Listener listener;
        private boolean onDevice, started, finishing, done, heardSpeech;
        Device(Context context) { this.context = context.getApplicationContext(); }

        @Override public void start(Listener listener) {
            if (this.listener != null) return;
            this.listener = listener;
            boolean local;
            try { local = SpeechRecognizer.isOnDeviceRecognitionAvailable(context); } catch (RuntimeException unknown) { local = false; }
            if (!local || Boolean.FALSE.equals(localKorean)) { listen(false); return; }
            if (Boolean.TRUE.equals(localKorean)) { listen(true); return; }
            // First use in this process: ask the on-device service whether Korean is installed, then listen with the answer.
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context); onDevice = true;
                recognizer.checkRecognitionSupport(intent(), context.getMainExecutor(), new RecognitionSupportCallback() {
                    @Override public void onSupportResult(RecognitionSupport support) { localKorean = korean(support.getInstalledOnDeviceLanguages()); decided(); }
                    @Override public void onError(int error) { localKorean = false; decided(); }
                });
            } catch (RuntimeException refused) { localKorean = false; listen(false); }
        }
        private void decided() {
            if (done) return;
            if (Boolean.TRUE.equals(localKorean)) { begin(); return; }
            destroy(); listen(false);
        }
        private void listen(boolean local) {
            if (done) return;
            destroy();
            if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) { fail("이 기기에서 쓸 수 있는 음성 인식이 없어요. Android 설정에서 음성 인식 서비스를 확인해 주세요."); return; }
            try { recognizer = local ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context) : SpeechRecognizer.createSpeechRecognizer(context); onDevice = local; }
            catch (RuntimeException refused) { fail("음성 인식을 시작하지 못했어요."); return; }
            begin();
        }
        private void begin() {
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { if (!done) listener.ready(); }
                @Override public void onBeginningOfSpeech() { heardSpeech = true; }
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { if (!done) listener.ended(); }
                @Override public void onError(int error) { failed(error); }
                @Override public void onResults(Bundle results) { if (done) return; String words = first(results); done = true; destroy(); listener.heard(words); }
                @Override public void onPartialResults(Bundle partial) { String words = first(partial); if (!done && !words.isBlank()) listener.partial(words); }
                @Override public void onEvent(int type, Bundle params) {}
            });
            try { recognizer.startListening(intent()); started = true; }
            catch (RuntimeException refused) { fail("음성 인식을 시작하지 못했어요."); return; }
            // A release before the recognizer started still ends the listening.
            if (finishing) recognizer.stopListening();
        }
        private void failed(int error) {
            if (done) return;
            boolean language = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
            // The on-device recognizer said it has no Korean after all: the default service listens instead, once, before any speech.
            if (onDevice && language && !heardSpeech && !finishing && SpeechRecognizer.isRecognitionAvailable(context)) { localKorean = false; listen(false); return; }
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { done = true; destroy(); listener.silent(reason(error)); return; }
            fail(reason(error));
        }
        private void fail(String reason) { if (done) return; done = true; destroy(); listener.failed(reason); }
        private static String first(Bundle results) {
            ArrayList<String> found = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return found == null || found.isEmpty() || found.get(0) == null ? "" : found.get(0).strip();
        }
        @Override public void finish() { if (done || finishing) return; finishing = true; if (started) recognizer.stopListening(); }
        @Override public void close() { done = true; destroy(); }
        private void destroy() { SpeechRecognizer current = recognizer; recognizer = null; started = false; if (current != null) try { current.destroy(); } catch (RuntimeException ignored) {} }
    }

    /**
     * The main-chat composer mic on the phone's recognizer (ComposerDictation, no Gemini key): the words go into the draft
     * only. The recognizer ends by itself when the speech stops, which counts as 완료.
     */
    static final class Dictation implements ComposerDictation.Session {
        private final Ear ear;
        private ComposerDictation.Listener listener;
        private boolean finished;
        Dictation(Context context, Ears ears) { ear = ears.open(context); }
        @Override public void start(ComposerDictation.Listener listener) {
            this.listener = listener;
            ear.start(new Listener() {
                @Override public void ready() { listener.ready(); }
                @Override public void partial(String words) { listener.preview(words); }
                @Override public void ended() { end(); }
                @Override public void heard(String words) { end(); if (words.isBlank()) listener.failed("말씀을 알아듣지 못했어요."); else listener.complete(words); }
                @Override public void failed(String reason) { listener.failed(reason); }
            });
        }
        /** The recognizer stopped by itself: the dialog moves on to 확정 as if 완료 was pressed. */
        private void end() { if (finished) return; finished = true; listener.limitReached(); }
        @Override public void finish() { finished = true; ear.finish(); }
        @Override public void close() { ear.close(); }
    }
}
