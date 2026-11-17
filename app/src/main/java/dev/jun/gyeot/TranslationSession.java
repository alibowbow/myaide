package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.InterpreterConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** One microphone, two independent translation targets. No generated audio is fed back as input. */
final class TranslationSession {
    interface Listener {
        default void ready() {}
        default void input(String language, String text, boolean interim) {}
        default void output(String target, String text) {}
        default void audio(String target, byte[] pcm) {}
        default void goAway() {}
        default void failed(String reason, boolean retryable) {}
    }
    interface Channel {
        void connect();
        boolean send(JSONObject message);
        void close();
        void afterClosed(Runnable done);
    }
    interface Factory { Channel create(String target, GeminiSession.Listener listener); }
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "translation-playback"); t.setDaemon(true); return t;
    });
    private final InterpreterConfig config;
    private final Listener listener;
    private final String[] targets;
    private final Channel[] channels;
    private final boolean[] connected;
    private final AudioOrder audio = new AudioOrder();
    private ScheduledFuture<?> drain;
    private boolean started, stopped, inputEnded;
    volatile boolean ready;

    TranslationSession(String key, InterpreterConfig config, Listener listener) {
        this(config, listener, (target, callbacks) -> {
            GeminiProtocol.Options options = new GeminiProtocol.Options(); options.translationTargetCode = target;
            GeminiSession session = new GeminiSession(key, GeminiProtocol.INTERPRETER, options, callbacks);
            return new Channel() {
                public void connect() { session.connect(); }
                public boolean send(JSONObject message) { return session.send(message); }
                public void close() { session.close(); }
                public void afterClosed(Runnable done) { session.afterClosed(done); }
            };
        });
    }
    TranslationSession(InterpreterConfig config, Listener listener, Factory factory) {
        this.config = config; this.listener = listener; targets = config.targets();
        channels = new Channel[targets.length]; connected = new boolean[targets.length];
        for (int i = 0; i < targets.length; i++) {
            int index = i;
            channels[i] = factory.create(targets[i], new GeminiSession.Listener() {
                @Override public void ready() { connected(index); }
                @Override public void transcriptLanguage(String text, String language, boolean interim) { input(index, text, language, interim); }
                @Override public void outputText(String text) { output(index, text); }
                @Override public void audio(byte[] pcm) { receivedAudio(index, pcm); }
                @Override public void generationComplete() { completedAudio(index); }
                @Override public void utteranceComplete() { completedAudio(index); }
                // Translate is continuous: an idle peer must never flush the other direction's speech.
                @Override public void goAway() { retire(); }
                @Override public void failed(String reason, boolean retryable) { fail(reason, retryable); }
            });
        }
    }
    synchronized void connect() {
        if (started || stopped) return; started = true;
        for (Channel channel : channels) { if (stopped) return; channel.connect(); }
    }
    private synchronized void connected(int index) {
        if (stopped || ready) return;
        connected[index] = true;
        for (boolean value : connected) if (!value) return;
        ready = true;
        drain = CLOCK.scheduleAtFixedRate(this::drainAudio, 100, 100, TimeUnit.MILLISECONDS);
        listener.ready();
    }
    private synchronized void input(int index, String text, String language, boolean interim) {
        // Input transcription is independent of translated output. One canonical stream avoids
        // duplicates even when the two servers split captions or identify languages differently.
        if (!ready || stopped || index != 0) return;
        String source = InterpreterConfig.matches(config.a, language) ? config.a : InterpreterConfig.matches(config.b, language) ? config.b : "";
        listener.input(source, text, interim);
    }
    private synchronized void output(int index, String text) {
        if (ready && !stopped) listener.output(targets[index], text);
    }
    private synchronized void receivedAudio(int index, byte[] pcm) {
        if (!ready || stopped) return;
        try { play(audio.push(index, pcm, now())); }
        catch (IllegalStateException error) { fail(error.getMessage(), false); }
    }
    private synchronized void completedAudio(int index) { if (ready && !stopped) play(audio.finish(index, now())); }
    private synchronized void drainAudio() { if (ready && !stopped) play(audio.poll(now())); }
    private void play(List<AudioOrder.Part> parts) { for (AudioOrder.Part part : parts) listener.audio(targets[part.channel], part.pcm); }
    private static long now() { return System.nanoTime() / 1_000_000L; }
    synchronized boolean send(JSONObject message) {
        if (!ready || stopped || !GeminiProtocol.translationInput(message)) return false;
        boolean end = message.optJSONObject("realtimeInput").optBoolean("audioStreamEnd");
        if (end && inputEnded) return true;
        inputEnded = end;
        for (Channel channel : channels) {
            if (stopped) return false;
            if (!channel.send(message)) {
                fail("통역 연결이 끊겨 다시 연결해야 해요.", true); return false;
            }
        }
        return true;
    }
    void audio(byte[] pcm) { send(GeminiProtocol.audio(pcm)); }
    private synchronized void fail(String reason, boolean retryable) {
        if (stopped) return; close(); listener.failed(reason, retryable);
    }
    private synchronized void retire() { if (!stopped) { close(); listener.goAway(); } }
    synchronized void close() {
        if (stopped) return; stopped = true; ready = false;
        if (drain != null) drain.cancel(false);
        audio.clear(); for (Channel channel : channels) if (channel != null) channel.close();
    }
    void afterClosed(Runnable done) {
        AtomicInteger remaining = new AtomicInteger(channels.length);
        for (Channel channel : channels) channel.afterClosed(() -> { if (remaining.decrementAndGet() == 0) done.run(); });
    }

    /** Preserve phrase order when the two servers return audio at overlapping times. */
    static final class AudioOrder {
        static final long QUIET_MS = 500;
        static final int LIMIT = 24_000 * 2 * 15;
        static final class Part {
            final int channel; final byte[] pcm;
            Part(int channel, byte[] pcm) { this.channel = channel; this.pcm = pcm; }
        }
        private final ArrayDeque<Part> pending = new ArrayDeque<>();
        private int active = -1, bytes;
        private long quietAt;
        List<Part> push(int channel, byte[] pcm, long now) {
            List<Part> result = new ArrayList<>(poll(now));
            if (pcm.length == 0) return result;
            if (active == -1 || active == channel) {
                active = channel; quietAt = now + QUIET_MS; result.add(new Part(channel, pcm));
            } else {
                if (pcm.length > LIMIT - bytes) throw new IllegalStateException("번역 음성이 너무 많이 겹쳤어요. 잠시 멈춘 뒤 차례로 말해 주세요.");
                pending.add(new Part(channel, pcm)); bytes += pcm.length;
            }
            return result;
        }
        List<Part> poll(long now) {
            List<Part> result = new ArrayList<>();
            if (active != -1 && now < quietAt) return result;
            active = -1;
            while (!pending.isEmpty() && (active == -1 || pending.peek().channel == active)) {
                Part part = pending.remove(); bytes -= part.pcm.length; active = part.channel; result.add(part);
            }
            quietAt = now + QUIET_MS; return result;
        }
        List<Part> finish(int channel, long now) {
            if (active != channel) return List.of(); quietAt = now; return poll(now);
        }
        void clear() { pending.clear(); bytes = 0; active = -1; quietAt = 0; }
    }
}
