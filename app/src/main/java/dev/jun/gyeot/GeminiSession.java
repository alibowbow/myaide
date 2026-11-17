package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.Jsons;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class GeminiSession {
    interface Listener {
        default void ready() {}
        /** The socket and its TLS handshake are open; the setup goes out next, and setupComplete (ready) follows. */
        default void opened() {}
        /** The open socket waited this long for the setup's build (sendSetup), when it waited at all. */
        default void setupWaited(long millis) {}
        /** The call's first-turn note (core/FirstTurn) went out on this connection. */
        default void primed() {}
        /** The call's first-turn note was given up on this connection, and why (numbers and fixed words only). */
        default void primerSkipped(String why) {}
        default void startupAudioDropped() {}
        default void audio(byte[] pcm) {}
        default void transcript(String text, boolean interim) {}
        default void transcriptLanguage(String text, String language, boolean interim) { transcript(text, interim); }
        default void outputText(String text) {}
        default void generationComplete() {}
        /** One spoken utterance ended; Extended Thinking can still be processing the request. */
        default void utteranceComplete() {}
        default void interactionStatus(boolean busy) {}
        /** The entire interaction is done (IDLE for Extended Thinking). */
        default void turnComplete() {}
        default void interrupted() {}
        default void tools(JSONArray calls) {}
        default void toolsCancelled(JSONArray ids) {}
        default void grounding(JSONObject value) {}
        default void goAway() {}
        /** Called at most once when the connection ends without close(). Retryable marks network or server drops worth resuming. */
        default void failed(String reason, boolean retryable) {}
        /** This app answered a function call: the reply, how long the call had been open (-1 when unknown) and its scheduling
         * (null for the default; SILENT asks for no spoken reply), for the phone log. */
        default void replied(String id, String name, JSONObject result, long millis, String scheduling) {}
        /** Model audio was left unplayed because the turn's only reply so far was a silent browser receipt (browserQuietOutput). */
        default void quietAudio(int bytes) {}
    }
    private static final String ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";
    private static final int MESSAGE_LIMIT = 4 * 1024 * 1024, CONNECT_TIMEOUT_MS = 15000;
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "gemini-timeouts"); t.setDaemon(true); return t; });
    /** Builds each connection's setup while its socket connects. */
    private static final ExecutorService SETUP = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gemini-setup"); t.setDaemon(true); return t; });
    private static final int SETUP_WAIT_MS = 10_000;
    private final String key, mode;
    private final GeminiProtocol.Options options;
    private final Listener listener;
    private final boolean extended, translating;
    private boolean interactionOpen, idleSeen;
    volatile boolean reasoning;
    // Ordinary Live cannot use NON_BLOCKING scheduling. Keep receipt-only audio local and silent too.
    private volatile boolean browserQuietOutput;
    // A silent browser receipt may share a model tool batch with a requested, meaningful answer.
    // Keep that answer audible regardless of the order in which the tools return.
    private volatile boolean meaningfulToolReply;
    boolean browserQuietOutput() { return browserQuietOutput; }
    /** Function calls this connection received and has neither answered nor seen Gemini cancel, with when each came
     * (System.nanoTime): someone is waiting on them. */
    private final java.util.Map<String, Long> unanswered = new java.util.concurrent.ConcurrentHashMap<>();
    int unanswered() { return unanswered.size(); }
    /** Whether this call is still waiting for its reply. */
    boolean waiting(String id) { return id != null && unanswered.containsKey(id); }
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile dev.jun.gyeot.core.VoiceStartupAudio startupAudio = new dev.jun.gyeot.core.VoiceStartupAudio();
    /**
     * The pre-roll belongs to the call, not to this connection (keepStartupAudio): until setup completes once, a refused or
     * dropped connection leaves the words in it for the next one, and only the call clears it.
     */
    private volatile boolean sharedStartup;
    private volatile Future<String> setup;
    /** The call's first-turn note (core/FirstTurn), sent once the audio stream has started and was still for half a second;
     * null once sent or given up (a voice, dropped words, or anything else said or heard first). */
    private String primer;
    /** The first-turn note went out on this connection. */
    volatile boolean primed;
    private volatile WebSocketClient socket;
    private volatile ScheduledFuture<?> timeout;
    private boolean transportClosed;
    private Runnable closeContinuation;
    /** ready: accepting input now. established: setup completed at least once on this connection. */
    volatile boolean ready, established;
    /**
     * The server heard or answered on this connection (a transcription, a model turn, a function call): audio input worked,
     * so a later close for its content type is the server's own drop (GeminiProtocol.serverAudioDrop) and is resumed.
     */
    volatile boolean served;
    volatile String resumeHandle = "";
    /** The voice call's timings and token counts, for the call's main connection only; null elsewhere. */
    volatile dev.jun.gyeot.core.VoiceLatency.Call latency;
    GeminiSession(String key, String mode, GeminiProtocol.Options options, Listener listener) {
        this.key = key; this.mode = mode; this.options = options; this.listener = listener;
        translating = GeminiProtocol.INTERPRETER.equals(mode);
        extended = !translating && !GeminiProtocol.DICTATION.equals(mode) && GeminiProtocol.extended(options.model);
    }
    String endpoint() { return extended ? ENDPOINT.replace(".v1beta.", ".v1alpha.") : ENDPOINT; }
    void connect() { connect(""); }
    void connect(String resume) {
        try {
            // The setup (instructions and function declarations, about 40,000 characters for an assistant call) is built while
            // DNS, TCP and TLS run, not after them: the first build in a process takes the longest.
            prepareSetup(resume);
            socket = new WebSocketClient(URI.create(endpoint() + URLEncoder.encode(key, StandardCharsets.UTF_8)), new Draft_6455(), null, CONNECT_TIMEOUT_MS) {
                @Override public void onOpen(ServerHandshake handshake) { sendSetup(this); }
                @Override public void onMessage(String text) { receive(text); }
                @Override public void onMessage(ByteBuffer bytes) {
                    if (bytes.remaining() > MESSAGE_LIMIT) { fail("서버 메시지가 너무 커서 연결을 종료했어요.", false); return; }
                    receive(StandardCharsets.UTF_8.decode(bytes).toString());
                }
                @Override public void onClose(int code, String reason, boolean remote) { closed(code, reason); }
                @Override public void onError(Exception error) { fail(Jsons.message(error, key), true); }
            };
            socket.setConnectionLostTimeout(30);
            socket.setTcpNoDelay(true);
            timeout = CLOCK.schedule(() -> { if (!ready) fail("Gemini 연결 시간이 초과됐어요. 네트워크와 키·모델 이용 권한을 확인해 주세요.", true); }, 25, TimeUnit.SECONDS);
            socket.connect();
        } catch (Exception error) { fail(Jsons.message(error, key), false); }
    }
    /** Starts building this connection's setup now, off the calling thread; setupText() waits for it. */
    void prepareSetup(String resume) {
        GeminiProtocol.Options snapshot = options;
        setup = SETUP.submit(() -> GeminiProtocol.setup(mode, snapshot, resume).toString());
    }
    /**
     * The socket's handshake is done (onOpen): say so, then send the setup prepareSetup built, waiting for its build if it
     * is not done yet. A setup that cannot be built ends the connection for good; nothing else is sent before it.
     */
    void sendSetup(WebSocketClient open) {
        // close() may have raced the handshake; never leave an idle socket open.
        if (stopped.get()) { open.close(); return; }
        // The network's part ends here (DNS, TCP, TLS), before any wait for the build, which is told apart (setupWaited).
        listener.opened();
        long start = System.nanoTime();
        String text;
        try { text = setupText(); }
        catch (Exception error) { fail("음성 연결 설정을 만들지 못했어요: " + Jsons.message(error, key), false); return; }
        long waited = (System.nanoTime() - start) / 1_000_000;
        if (waited >= 5) listener.setupWaited(waited);
        open.send(text);
    }
    /** The setup prepareSetup started, as sent on the socket. */
    String setupText() throws Exception {
        Future<String> pending = setup;
        if (pending == null) throw new IllegalStateException("setup not prepared");
        try { return pending.get(SETUP_WAIT_MS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.ExecutionException error) { throw error.getCause() instanceof Exception cause ? cause : error; }
    }
    void closed(int code, String reason) {
        notifyClosed();
        ready = false; cancelTimeout();
        fail("연결 종료 " + code + (reason == null || reason.isBlank() ? "" : ": " + reason), GeminiProtocol.transientClose(code, reason) || served && GeminiProtocol.serverAudioDrop(code, reason));
    }
    void receive(String raw) {
        if (stopped.get()) return;
        if (raw.length() > MESSAGE_LIMIT) { fail("서버 메시지 크기 제한을 초과했어요.", false); return; }
        try {
            JSONObject message = new JSONObject(raw);
            if (message.has("error")) {
                JSONObject error = message.optJSONObject("error");
                String text = error == null ? message.optString("error") : error.optString("message", "Gemini 요청 오류");
                fail(text, GeminiProtocol.transientError(text + " " + (error == null ? "" : error.optString("status"))));
                return;
            }
            dev.jun.gyeot.core.VoiceLatency.Call measured = latency;
            if (message.has("setupComplete") || message.has("setup_complete")) {
                cancelTimeout();
                if (measured != null) measured.setupComplete();
                boolean dropped;
                synchronized(this) {
                    // close() raced this message: a stopped connection takes nothing from the call's pre-roll.
                    if (stopped.get()) return;
                    ready = true; established = true;
                    // A voice already held means the user is talking: the first-turn note is not sent into their words, nor
                    // after words that were dropped. Still room sound lets it go; otherwise it waits for stillness (audio).
                    long now = System.nanoTime()/1_000_000;
                    int note = primer == null ? dev.jun.gyeot.core.VoiceStartupAudio.WAIT : startupAudio.note();
                    if (note == dev.jun.gyeot.core.VoiceStartupAudio.VOICE) skipPrimer("연결 전에 소리가 이어짐(말소리로 봄) · 크기 " + startupAudio.loudest() + " · 연결 준비 " + Math.max(0, now - startupAudio.loudestAt()) + "ms 전");
                    else if (note == dev.jun.gyeot.core.VoiceStartupAudio.DROPPED) skipPrimer("연결 전 첫 말이 보관 한도를 넘어 잘림");
                    int drained = 0;
                    for(byte[] pcm:startupAudio.drain(now))if(send(GeminiProtocol.audio(pcm))){sent();drained++;}
                    if (note == dev.jun.gyeot.core.VoiceStartupAudio.GO && drained > 0) sendPrimer();
                    dropped = startupAudio.takeDropped();
                    // The drain itself can find the held words past their bounds: no note after dropped words either.
                    if (dropped) skipPrimer("연결 전 첫 말이 보관 한도를 넘어 잘림");
                }
                listener.ready();
                if (dropped) listener.startupAudioDropped();
            }
            JSONObject usage = GeminiProtocol.child(message, "usageMetadata", "usage_metadata");
            if (usage != null && measured != null) measured.usage(dev.jun.gyeot.core.VoiceLatency.Usage.from(usage));
            JSONObject update = GeminiProtocol.child(message, "sessionResumptionUpdate", "session_resumption_update");
            if (!translating && update != null && update.optBoolean("resumable")) {
                String handle = update.optString("newHandle", update.optString("new_handle"));
                if (!handle.isBlank()) resumeHandle = handle;
            }
            if (message.has("goAway") || message.has("go_away")) listener.goAway();
            JSONObject cancellation = GeminiProtocol.child(message, "toolCallCancellation", "tool_call_cancellation");
            if (cancellation != null && cancellation.optJSONArray("ids") != null) {
                JSONArray ids = cancellation.optJSONArray("ids");
                for (int i = 0; i < ids.length(); i++) unanswered.remove(ids.optString(i));
                listener.toolsCancelled(ids);
            }
            JSONObject call = GeminiProtocol.child(message, "toolCall", "tool_call");
            JSONObject content = GeminiProtocol.serverContent(message);
            String status = message.optString("interactionStatus", message.optString("interaction_status"));
            if (content != null) status = content.optString("interactionStatus", content.optString("interaction_status", status));
            boolean interrupted = content != null && content.optBoolean("interrupted");
            if (extended && interrupted) { interactionOpen = false; idleSeen = true; setReasoning(false); }
            if (extended && "IN_PROGRESS".equals(status)) idleSeen = false;
            if (extended && !interrupted && !idleSeen && ("IN_PROGRESS".equals(status) || call != null || content != null &&
                (GeminiProtocol.child(content, "modelTurn", "model_turn") != null || GeminiProtocol.child(content, "outputTranscription", "output_transcription") != null))) {
                interactionOpen = true;
                setReasoning(true);
            }
            if (call != null || content != null && (content.optBoolean("turnComplete") || content.optBoolean("turn_complete") || GeminiProtocol.child(content, "modelTurn", "model_turn") != null
                || GeminiProtocol.child(content, "outputTranscription", "output_transcription") != null || GeminiProtocol.child(content, "inputTranscription", "input_transcription") != null
                || GeminiProtocol.child(content, "interimInputTranscription", "interim_input_transcription") != null)) { served = true; dropPrimer(); }
            // The user's final words must reach the controller before tools from the same frame.
            if (content != null) {
                if (interrupted) { meaningfulToolReply = false; browserQuietOutput = false; if (measured != null) measured.interrupted(); listener.interrupted(); }
                JSONObject interim = GeminiProtocol.child(content, "interimInputTranscription", "interim_input_transcription");
                if (interim != null && !interim.optString("text").isBlank()) { if (measured != null) measured.input(); meaningfulToolReply = false; browserQuietOutput = false; listener.transcriptLanguage(interim.optString("text"), interim.optString("languageCode", interim.optString("language_code")), true); }
                JSONObject input = GeminiProtocol.child(content, "inputTranscription", "input_transcription");
                if (input != null && !input.optString("text").isBlank()) { if (measured != null) measured.input(); meaningfulToolReply = false; browserQuietOutput = false; listener.transcriptLanguage(input.optString("text"), input.optString("languageCode", input.optString("language_code")), false); }
            }
            if (call != null && !translating) {
                JSONArray calls = call.optJSONArray("functionCalls");
                if (calls == null) calls = call.optJSONArray("function_calls");
                if (calls != null) {
                    long now = System.nanoTime();
                    for (int i = 0; i < calls.length(); i++) { JSONObject one = calls.optJSONObject(i); if (one != null) unanswered.putIfAbsent(one.optString("id"), now); }
                    listener.tools(calls);
                }
            }
            if (content == null) { completeInteraction(status); return; }
            JSONObject output = GeminiProtocol.child(content, "outputTranscription", "output_transcription");
            if (output != null && !output.optString("text").isBlank()) listener.outputText(output.optString("text"));
            JSONObject turn = GeminiProtocol.child(content, "modelTurn", "model_turn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i); if (part == null) continue;
                    JSONObject data = GeminiProtocol.child(part, "inlineData", "inline_data");
                    if (data != null && data.optString("mimeType", data.optString("mime_type")).startsWith("audio/pcm")) {
                        byte[] pcm = Base64.getDecoder().decode(data.getString("data"));
                        if (!browserQuietOutput) { if (measured != null) measured.output(); listener.audio(pcm); }
                        else listener.quietAudio(pcm.length);
                    }
                    // Audio models provide their canonical text in outputTranscription.
                }
            }
            JSONObject grounding = GeminiProtocol.child(content, "groundingMetadata", "grounding_metadata");
            if (grounding != null) listener.grounding(grounding);
            if (content.optBoolean("generationComplete") || content.optBoolean("generation_complete")) listener.generationComplete();
            if (content.optBoolean("turnComplete") || content.optBoolean("turn_complete")) {
                if (measured != null) measured.turnComplete();
                listener.utteranceComplete();
                // A silent browser receipt silences the rest of its own turn only: the next turn's audio plays even when its
                // input transcript comes after it.
                if (!extended) { meaningfulToolReply = false; browserQuietOutput = false; listener.turnComplete(); }
            }
            if (!interrupted) completeInteraction(status);
        } catch (Exception error) { fail("Gemini 응답을 처리하지 못했어요: " + Jsons.message(error, key), false); }
    }
    boolean send(JSONObject data) {
        if (translating && !GeminiProtocol.translationInput(data)) return false;
        WebSocketClient current = socket;
        if (!stopped.get() && ready && current != null && current.isOpen()) try { current.send(data.toString()); return true; }
        catch (Exception error) { fail(Jsons.message(error, key), true); }
        return false;
    }
    /** One microphone frame: sent, or kept in the pre-roll until setup completes. False once this connection has ended. */
    synchronized boolean audio(byte[] pcm) {
        if(stopped.get())return false;
        boolean streamed = false; long now = System.nanoTime()/1_000_000;
        // The call's own end of speech (the phone log's 말 끝난 뒤): the last frame at speech level, whenever Gemini's caption comes.
        int level = dev.jun.gyeot.core.VoiceStartupAudio.rms(pcm);
        if (level >= 160) { dev.jun.gyeot.core.VoiceLatency.Call measured = latency; if (measured != null) measured.voiced(level); }
        for(byte[] frame:startupAudio.accept(pcm,ready,now))if(send(GeminiProtocol.audio(frame))){sent();streamed=true;}
        // The note waits for half a second of stillness in the stream; two frames in a row above the room's level are the
        // user's voice, and it never goes into their words.
        if (streamed && primer != null) {
            int note = startupAudio.live(pcm, now);
            if (note == dev.jun.gyeot.core.VoiceStartupAudio.GO) sendPrimer();
            else if (note == dev.jun.gyeot.core.VoiceStartupAudio.VOICE) skipPrimer("연결 뒤 조용해지기 전에 소리가 이어짐(말소리로 봄) · 크기 " + startupAudio.loudest());
        }
        return true;
    }
    /**
     * The first-turn note goes out once this connection's audio stream has started (core/FirstTurn): Gemini 3.8 and 3.1 take
     * content sent before the realtime stream as initial history, which needs a setup option this app does not send, while
     * content sent once it runs is appended like the call's other notes. Called with the session's lock held.
     */
    private void sendPrimer() {
        String first = primer; if (first == null) return;
        primer = null;
        if (send(GeminiProtocol.note(first))) { primed = true; listener.primed(); }
    }
    /**
     * The conversation started before the note went (typed words, a notice, a function reply, or the server heard or said
     * something): it is no longer the call's first input, so it never goes (it would also cut an answer being generated).
     */
    private synchronized void dropPrimer() { skipPrimer("다른 말이나 응답이 먼저 오감"); }
    /** Gives the note up for this call and says why, once. Called with the session's lock held. */
    private void skipPrimer(String why) {
        if (primer == null) return;
        primer = null; listener.primerSkipped(why);
    }
    /** The app's own short sound may be in the microphone's frames of this time (VoiceStartupAudio.appSound). */
    void appSound(long from, long to) { startupAudio.appSound(from, to); }
    /**
     * Before the call's first setup completes, its connections share one pre-roll, the call's (AssistantService.firstWords):
     * what this connection held when it was refused or dropped stays there for the next one, whose bounds count from its own
     * start while no words are held (VoiceStartupAudio.keepLead). Call before connect.
     */
    void keepStartupAudio(dev.jun.gyeot.core.VoiceStartupAudio shared) { if (shared != null) { shared.keepLead(); startupAudio = shared; sharedStartup = true; } }
    /**
     * Sends text (core/FirstTurn.PRIMER) as context, asking no reply, once this connection's setup completed and half a second
     * of its audio was still; not at all once a voice was heard by then (two frames in a row above the room's level), when
     * words were dropped, or once anything else went to the model or came from it. Call before connect.
     */
    synchronized void primeFirstTurn(String text) { primer = text == null || text.isBlank() ? null : text; }
    /** Holds a frame in the call's pre-roll while no connection takes it, on the clock its connections use. */
    static void hold(dev.jun.gyeot.core.VoiceStartupAudio shared, byte[] pcm) { shared.accept(pcm, false, System.nanoTime() / 1_000_000); }
    private void sent() { dev.jun.gyeot.core.VoiceLatency.Call measured = latency; if (measured != null) measured.pcmSent(); }
    void clearStartupAudio() { startupAudio.clear(); }
    /** Ends this connection without touching a pre-roll it shares with the call. */
    private void dropStartupAudio() { if (!sharedStartup) startupAudio.clear(); }
    void text(String text) { dropPrimer(); meaningfulToolReply = false; browserQuietOutput = false; send(GeminiProtocol.text(text)); }
    /** An actual async browser result must survive later duplicate silent receipts in the same turn. */
    void browserReport(String text) { dropPrimer(); meaningfulToolReply = true; browserQuietOutput = false; send(GeminiProtocol.text(text)); }
    /** Adds context without prompting a reply. */
    void note(String text) { dropPrimer(); send(GeminiProtocol.note(text)); }
    void tool(String id, String name, JSONObject value) { dropPrimer(); long millis = opened(id); meaningfulToolReply = true; browserQuietOutput = false; send(GeminiProtocol.toolResponse(id, name, value)); listener.replied(id, name, value, millis, null); }
    /** Only Extended Thinking declares all its functions NON_BLOCKING. */
    void tool(String id, String name, JSONObject value, String scheduling) {
        dropPrimer(); long millis = opened(id);
        if (!"SILENT".equals(scheduling)) { meaningfulToolReply = true; browserQuietOutput = false; }
        send(GeminiProtocol.toolResponse(id, name, value, extended ? scheduling : null));
        listener.replied(id, name, value, millis, scheduling);
    }
    /** Receipt-only output stays silent even on blocking Live; captions and tool processing continue. */
    void browserTool(String id, String name, JSONObject value, boolean speak) {
        dropPrimer(); long millis = opened(id);
        if (speak) meaningfulToolReply = true;
        browserQuietOutput = !speak && !meaningfulToolReply;
        send(GeminiProtocol.toolResponse(id, name, value, extended ? (speak ? "WHEN_IDLE" : "SILENT") : null));
        listener.replied(id, name, value, millis, speak ? null : "SILENT");
    }
    /** Marks the call answered; how long it was open, or -1 for a call this connection does not know. */
    private long opened(String id) {
        Long since = id == null ? null : unanswered.remove(id);
        return since == null ? -1 : Math.max(0, (System.nanoTime() - since) / 1_000_000);
    }
    private void setReasoning(boolean busy) {
        if (reasoning == busy) return;
        reasoning = busy; listener.interactionStatus(busy);
    }
    private void completeInteraction(String status) {
        if (!extended || !"IDLE".equals(status)) return;
        boolean complete = interactionOpen; interactionOpen = false; idleSeen = true; meaningfulToolReply = false; browserQuietOutput = false; setReasoning(false);
        if (complete) listener.turnComplete();
    }
    private String safe(String message) { return Jsons.message(new Exception(message == null ? "알 수 없는 연결 오류" : message), key); }
    private void cancelTimeout() { ScheduledFuture<?> current = timeout; if (current != null) current.cancel(false); }
    private void fail(String reason, boolean retryable) {
        if (!stopped.compareAndSet(false, true)) return;
        ready = false; reasoning = false; dropStartupAudio(); cancelTimeout(); listener.failed(safe(reason), retryable);
        shutdown();
    }
    void close() { stopped.set(true); ready = false; reasoning = false; dropStartupAudio(); cancelTimeout(); shutdown(); }
    /** Wait for transport close before handing a resumption handle to another client. */
    void afterClosed(Runnable continuation) {
        synchronized (this) {
            if (!transportClosed && socket != null && !socket.isClosed()) {
                if (closeContinuation != null) throw new IllegalStateException("close continuation already registered");
                closeContinuation = continuation;
                CLOCK.schedule(() -> {
                    WebSocketClient current = socket;
                    if (current != null) { Socket raw = current.getSocket(); if (raw != null) try { raw.close(); } catch (Exception ignored) {} }
                    notifyClosed();
                }, 2, TimeUnit.SECONDS);
                return;
            }
        }
        continuation.run();
    }
    private void notifyClosed() {
        Runnable continuation;
        synchronized (this) { transportClosed = true; continuation = closeContinuation; closeContinuation = null; }
        if (continuation != null) continuation.run();
    }
    /** WebSocketClient.close() is a no-op until the handshake is sent, so a stalled connect or TLS step is aborted at the socket. */
    private void shutdown() {
        WebSocketClient current = socket; if (current == null) return;
        if (!current.isOpen()) { Socket raw = current.getSocket(); if (raw != null) try { raw.close(); } catch (Exception ignored) {} }
        current.close();
    }
}

