package dev.jun.gyeot.core;

import org.json.JSONObject;

/**
 * 음성 비서의 대화 연결, whichever service speaks it: Gemini Live (GeminiSession, the default) or a realtime voice of GPT
 * or Grok (RealtimeSession). AssistantService talks to the call's connection through these methods only; what the
 * connection hears and says comes back through the listener it was made with. Calls come from the main thread unless
 * noted; audio() comes from the microphone's thread.
 */
public interface LiveVoiceSession {
    /** Opens the connection; resume is a resumption handle from an earlier connection of the same call ("" for none). */
    void connect(String resume);
    /** Ends this connection for good; nothing is reported after it. */
    void close();
    /** Runs continuation once the transport has closed (or at once when it has), before another connection takes over. */
    void afterClosed(Runnable continuation);
    /** Accepting input now. */
    boolean ready();
    /** The handle a reconnect resumes this conversation with; "" when the service gives none. */
    String resumeHandle();
    void resumeHandle(String handle);
    /** The model is still working on the request (Extended Thinking) after its spoken turn ended. */
    boolean reasoning();
    /** The call's timings and token counts, for the call's main connection only. */
    void latency(VoiceLatency.Call call);

    /** One 16 kHz microphone frame: sent, or kept in the pre-roll until the connection is ready. False once it has ended. */
    boolean audio(byte[] pcm);
    /** The app's own short sound may be in the microphone's frames of this time (VoiceStartupAudio.appSound). */
    void appSound(long from, long to);
    /** Holds the call's first words in the call's own pre-roll until this connection is ready. Call before connect. */
    void keepStartupAudio(VoiceStartupAudio shared);
    /** The call's first-turn note (core/FirstTurn); a connection that has no use for it ignores it. Call before connect. */
    void primeFirstTurn(String text);
    void clearStartupAudio();
    /** The microphone was muted: the audio stream ends here. */
    void endAudio();
    /** The user spoke over the answer through the phone's speaker (core/BargeIn): the answer being made stops. */
    default void bargeIn() {}

    /** A turn of the user's that the model answers (typed words, or the app's notice that asks for a reply). */
    void text(String text);
    /** A background browser result, told as text() is. */
    void browserReport(String text);
    /** Context for the model without asking it to answer. */
    void note(String text);
    /** The result of a function call, which the model answers. */
    void tool(String id, String name, JSONObject value);
    /** As tool(), with Live's scheduling: SILENT asks for no spoken reply, INTERRUPT for one now; null for the default. */
    void tool(String id, String name, JSONObject value, String scheduling);
    /** A browser receipt (speak false) stays silent; a result (speak true) is answered. */
    void browserTool(String id, String name, JSONObject value, boolean speak);
    /** Model audio is being left unplayed because the turn's only reply so far was a silent browser receipt. */
    boolean browserQuietOutput();
    /** Function calls received and neither answered nor cancelled. */
    int unanswered();
    /** Whether this call is still waiting for its reply. */
    boolean waiting(String id);

    /** Whether the model can take a still image (look_at_screen, a phone step's screenshot). */
    boolean sees();
    /** A camera frame (CameraView): video on Gemini Live; a connection that cannot take it drops it. Any thread. */
    void cameraFrame(String jpegBase64);
    /** A still image of the screen as input, answered with the tool's result; false when it did not go. */
    boolean image(String jpegBase64);
    /** The screen's image with the user's request about it (ScreenView.ask), in one turn the model answers; false when it did not go. */
    boolean imageTurn(String jpegBase64, String app, String text);
}
