package dev.jun.gyeot.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** One requested still per user turn, shared by a spoken request, the button and the model's tool calls. */
public final class ScreenConversation {
    public static final long RESPONSE_TIMEOUT_MS = 45_000;
    private final PhoneGate gate;
    private Request current;
    private boolean viewing;
    public ScreenConversation(PhoneGate gate) { this.gate = gate; }
    public static final class Request {
        public final Set<String> calls = new LinkedHashSet<>(), replied = new LinkedHashSet<>();
        public boolean prompt, delivered, complete;
        private boolean responseSeen;
        public String app = "", text = "", error;
        private long activity;
    }
    /** Main-thread lifecycle, also queried by the imaging thread before reading/sending a frame. */
    public synchronized Request begin(String call) {
        if (current == null) { gate.interrupt(); viewing = true; current = new Request(); }
        if (call == null) current.prompt = true; else current.calls.add(call);
        return current;
    }
    public synchronized Request current() { return current; }
    public synchronized boolean active(Request request) { return request != null && current == request; }
    public synchronized boolean viewing() { return viewing; }
    /** A new user turn may authorize a new action; old callbacks cannot belong to it. */
    public synchronized void nextTurn() { current = null; viewing = false; }
    /** Reconnection cancels the frame but must not resume the interrupted phone job. */
    public synchronized void cancel() { current = null; }
    public synchronized void cancel(String call) {
        if (current == null) return;
        current.calls.remove(call);
        if (!current.prompt && current.calls.isEmpty()) current = null;
    }
    public synchronized void delivered(Request request, String app, String text, long now) {
        if (!active(request)) return;
        request.app = app; request.text = text; request.delivered = true; request.activity = now;
    }
    public synchronized void progress(long now) {
        if (current != null && current.delivered && !current.complete) { current.activity = now; current.responseSeen = true; }
    }
    public synchronized void complete() { if (current != null && current.delivered && current.responseSeen) current.complete = true; }
    public synchronized boolean timedOut(Request request, long now) {
        return active(request) && request.delivered && !request.complete && now - request.activity >= RESPONSE_TIMEOUT_MS;
    }
}
