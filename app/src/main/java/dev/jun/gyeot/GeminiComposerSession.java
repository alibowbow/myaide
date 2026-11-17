package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiProtocol;
import org.json.JSONObject;
import java.util.concurrent.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Independent, bounded Live STT; never borrows the assistant's session or model preferences. */
final class GeminiComposerSession implements ComposerDictation.Session {
    static final String MODEL = "gemini-3.5-transcribe-live";
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "composer-live-timeouts"); t.setDaemon(true); return t; });
    interface Transport { void connect(); boolean send(JSONObject message); void close(); }
    interface TransportFactory {
        Transport create(GeminiSession.Listener listener);
        /** Without the language hint (languageCodes empty), after Transcribe refused it at setup. */
        default Transport createWithoutLanguage(GeminiSession.Listener listener) { return create(listener); }
    }
    interface Capture { void start(Microphone.Sink sink) throws Exception; void close(); }
    private final TransportFactory transportFactory;
    private final Capture capture;
    private final long finalTimeoutMs, quietDrainMs;
    private ComposerDictation.Listener listener;
    private Transport transport;
    private ScheduledFuture<?> duration, finalTimeout, drain;
    private boolean started, ready, finishing, closed, limitSent;
    private int bytes;
    private long transcriptEpoch;
    private final StringBuilder committed = new StringBuilder();

    GeminiComposerSession(String key) {
        this(new TransportFactory() {
            public Transport create(GeminiSession.Listener callback) { return live(key, GeminiProtocol.KOREAN, callback); }
            public Transport createWithoutLanguage(GeminiSession.Listener callback) { return live(key, "", callback); }
        }, new Capture() {
            private final Microphone microphone = new Microphone(false);
            public void start(Microphone.Sink sink) throws Exception { microphone.start(sink); }
            public void close() { microphone.close(); }
        }, 20_000, 600);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Gemini API 키가 없어요.");
    }
    GeminiComposerSession(TransportFactory transportFactory, Capture capture, long finalTimeoutMs, long quietDrainMs) {
        this.transportFactory = transportFactory; this.capture = capture; this.finalTimeoutMs = finalTimeoutMs; this.quietDrainMs = quietDrainMs;
    }
    private static Transport live(String key, String language, GeminiSession.Listener callback) {
        GeminiProtocol.Options options = new GeminiProtocol.Options(); options.manualDictation = true; options.speechLanguage = language;
        GeminiSession live = new GeminiSession(key, GeminiProtocol.DICTATION, options, callback);
        return new Transport() {
            public void connect() { live.connect(); }
            public boolean send(JSONObject message) { return live.send(message); }
            public void close() { live.close(); }
        };
    }
    /** The connection whose callbacks count; an earlier one refused at setup is ignored. */
    private int connection;
    private boolean withoutLanguage;
    @Override public synchronized void start(ComposerDictation.Listener listener) {
        if (started || closed) throw new IllegalStateException("음성 입력이 이미 시작됐어요.");
        started = true; this.listener = listener;
        open(false);
    }
    private synchronized void open(boolean plain) {
        int mine = ++connection;
        GeminiSession.Listener callback = new GeminiSession.Listener() {
            @Override public void ready() { if (current(mine)) connected(); }
            @Override public void transcript(String text, boolean interim) { if (current(mine)) transcriptReceived(text, interim); }
            @Override public void failed(String reason, boolean retryable) { if (current(mine)) refused(reason); }
            @Override public void goAway() { if (current(mine)) fail("Gemini Live 세션이 종료되어 음성 입력을 취소했어요. 다시 시도해 주세요."); }
        };
        transport = plain ? transportFactory.createWithoutLanguage(callback) : transportFactory.create(callback);
        transport.connect();
    }
    private synchronized boolean current(int mine) { return mine == connection; }
    /** Transcribe refused the language hint at setup: connect once more without it (an empty list). */
    private synchronized void refused(String reason) {
        if (!closed && !ready && !withoutLanguage && GeminiProtocol.languageRefused(reason)) {
            withoutLanguage = true; Transport old = transport; if (old != null) old.close(); open(true); return;
        }
        fail("Gemini Live 전사 연결을 확인해 주세요. 인식 결과는 입력하지 않았어요.");
    }
    private synchronized void connected() {
        if (closed || finishing || ready) return;
        // Explicit manual VAD is documented for push-to-talk. audioStreamEnd is for automatic/hybrid VAD;
        // here activityEnd is the finalization signal, avoiding an earlier silence turn completing the draft.
        if (!transport.send(obj("realtimeInput", obj("activityStart", obj())))) { fail("Gemini에 음성 시작을 알리지 못했어요."); return; }
        ready = true;
        try {
            capture.start(new Microphone.Sink() {
                @Override public void audio(byte[] pcm) { audioReceived(pcm); }
                @Override public void error(String message) { fail("마이크 입력이 중단됐어요. 다시 시도해 주세요."); }
            });
            if (closed) { capture.close(); return; }
            duration = CLOCK.schedule(this::limit, 120_000, TimeUnit.MILLISECONDS);
            listener.ready();
        } catch (Exception error) { fail("마이크를 시작하지 못했어요. 권한과 다른 앱의 마이크 사용을 확인해 주세요."); }
    }
    private synchronized void audioReceived(byte[] pcm) {
        if (closed || finishing || !ready || limitSent || pcm == null || pcm.length == 0) return;
        if ((pcm.length & 1) != 0) { fail("마이크 PCM 형식을 확인하지 못해 음성 입력을 취소했어요."); return; }
        if (bytes + pcm.length > MAX_BYTES) { limit(); return; }
        bytes += pcm.length;
        if (!transport.send(GeminiProtocol.audio(pcm))) fail("Gemini에 음성을 보내지 못했어요. 다시 시도해 주세요.");
        else if (bytes == MAX_BYTES) limit();
    }
    private synchronized void transcriptReceived(String text, boolean interim) {
        if (closed || text == null || text.isBlank()) return;
        ++transcriptEpoch;
        // Interim hypotheses replace the preview only. They are NEVER committed or appended to the draft.
        if (interim) {
            if (finishing && drain != null) { drain.cancel(false); drain = null; }
            if (!finishing) listener.preview(text);
            return;
        }
        if (committed.length() + text.length() > 100_000) { fail("전사 결과가 너무 길어 입력을 취소했어요."); return; }
        committed.append(text);
        // inputTranscription is authoritative per segment, NOT a promise that all segments arrived.
        // Drain a short quiet window after the latest final; newer interim/final events postpone it.
        // The overall finalization deadline still bounds a stalled stream. Live/device validation
        // is required: the API does not document a dedicated all-transcript-complete marker.
        if (finishing) {
            if (drain != null) drain.cancel(false);
            long epoch = transcriptEpoch;
            drain = CLOCK.schedule(() -> finalized(epoch), quietDrainMs, TimeUnit.MILLISECONDS);
        }
    }
    private synchronized void limit() {
        if (closed || finishing || limitSent) return;
        limitSent = true; capture.close(); listener.limitReached();
    }
    @Override public synchronized void finish() {
        if (closed || finishing) return;
        if (!ready) { fail("Gemini 연결이 완료되지 않아 음성 입력을 취소했어요."); return; }
        finishing = true; capture.close(); if (duration != null) duration.cancel(false);
        if (bytes == 0) { fail("녹음된 소리가 없어요. 다시 말해 주세요."); return; }
        // Set the timeout before sending; even a synchronous mocked completion must cancel it.
        finalTimeout = CLOCK.schedule(() -> fail("Gemini가 마지막 말을 확정하지 못했어요. 초안은 바꾸지 않았어요."), finalTimeoutMs, TimeUnit.MILLISECONDS);
        if (!transport.send(obj("realtimeInput", obj("activityEnd", obj())))) fail("Gemini에 음성 종료를 알리지 못했어요.");
    }
    private synchronized void finalized(long epoch) {
        if (closed || !finishing || epoch != transcriptEpoch) return;
        String result = committed.toString().strip();
        if (result.isEmpty()) { fail("인식된 말이 없어요. 다시 말해 주세요."); return; }
        ComposerDictation.Listener callback = listener; close(); callback.complete(result);
    }
    private synchronized void fail(String reason) {
        if (closed) return;
        ComposerDictation.Listener callback = listener; close(); if (callback != null) callback.failed(reason);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; ready = false; ++transcriptEpoch;
        if (duration != null) duration.cancel(false); if (finalTimeout != null) finalTimeout.cancel(false); if (drain != null) drain.cancel(false);
        capture.close(); if (transport != null) transport.close(); committed.setLength(0);
    }
}
