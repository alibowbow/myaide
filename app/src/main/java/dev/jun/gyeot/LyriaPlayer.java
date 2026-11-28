package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 배경음악 (Lyria RealTime): music Gemini's Lyria model makes live from a mood ("calm piano, warm"), played as it streams
 * (48 kHz stereo PCM) and steered without stopping when the mood changes. Asked for in 음성 대화 (core/Bgm); 마이튜브 playing
 * stops it, and starting it pauses 마이튜브. Its volume is lower while a voice call is on, and Android ducks it under the
 * assistant's voice. One per process; LyriaService keeps it up in the background with its notification.
 */
final class LyriaPlayer {
    private static final String ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateMusic?key=";
    static final String MODEL = "models/lyria-realtime-exp";
    private static final int RATE = 48_000;
    /** Its volume while a voice call is on (the assistant's voice and the microphone come first), and otherwise. */
    static final float IN_CALL = 0.35f, ALONE = 0.8f;
    private static LyriaPlayer instance;
    static synchronized LyriaPlayer get(Context context) { if (instance == null) instance = new LyriaPlayer(context.getApplicationContext()); return instance; }
    /** Stops it when it plays (마이튜브 started); nothing when it was never made. */
    /** Whether its music is playing now (for "음악 꺼" said to 퍼레). */
    static boolean on() { LyriaPlayer one = instance; return one != null && one.active(); }
    static void stopIfPlaying() { LyriaPlayer one = instance; if (one != null && one.active()) one.main.post(one::stop); }

    private final Context context;
    final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile WebSocketClient socket; private volatile boolean ready, playing;
    private volatile String prompt = "", status = "";
    private volatile int bpm;
    private AudioTrack track; private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private AudioFocusRequest focus;
    private LyriaPlayer(Context context) { this.context = context; }

    String prompt() { return prompt; }
    String status() { return status; }
    boolean playing() { return playing; }
    boolean active() { return socket != null; }
    void listen(Runnable listener) { listeners.addIfAbsent(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }
    private void changed(String state) { status = state; main.post(() -> { for (Runnable each : listeners) each.run(); LyriaService.update(context); }); }

    /**
     * Plays music for a mood (a new connection when none is open, else the mood changes smoothly); bpm 60–200, or 0 for the
     * model's own. 마이튜브 pauses. Returns what to say: "" when it started or changed, else why not.
     */
    String play(String mood, int beats) {
        String text = mood == null ? "" : mood.strip(); if (text.isEmpty()) return "어떤 분위기로 만들지 알려 주세요.";
        prompt = text; if (beats > 0) bpm = beats;
        MyTubePlayer tube = MyTubePlayer.get(context); if (tube.playing()) tube.pause();
        if (socket != null && ready) { steer(); if (!playing) { control("PLAY"); playing = true; } changed("재생 중 · " + prompt); return ""; }
        if (socket != null) return ""; // connecting: the setup sends this mood
        String key;
        try { key = ((GyeotApp) context).store().secrets.workKey(); } catch (Exception e) { key = ""; }
        if (key.isBlank()) { changed(""); return "배경음악을 만들려면 설정에서 Gemini API 키를 넣어 주세요."; }
        changed("Lyria에 연결하는 중…");
        String secret = key;
        try {
            WebSocketClient client = new WebSocketClient(URI.create(ENDPOINT + URLEncoder.encode(secret, StandardCharsets.UTF_8)), new Draft_6455(), null, 15_000) {
                @Override public void onOpen(ServerHandshake handshake) { try { send(new JSONObject().put("setup", new JSONObject().put("model", MODEL)).toString()); } catch (Exception ignored) {} }
                @Override public void onMessage(String message) { receive(message); }
                @Override public void onMessage(ByteBuffer bytes) { receive(StandardCharsets.UTF_8.decode(bytes).toString()); }
                @Override public void onClose(int code, String reason, boolean remote) { closed(this, reason == null ? "" : reason.replace(secret, "…")); }
                @Override public void onError(Exception error) { closed(this, error.getMessage() == null ? "연결 오류" : error.getMessage().replace(secret, "…")); }
            };
            client.setConnectionLostTimeout(30); socket = client; client.connect();
            LyriaService.start(context);
            return "";
        } catch (Exception e) { socket = null; changed(""); return "Lyria에 연결하지 못했어요."; }
    }
    /** Pauses (the connection stays, so it plays on from there). */
    void pause() { if (socket == null || !playing) return; control("PAUSE"); playing = false; AudioTrack t = track; if (t != null) try { t.pause(); t.flush(); } catch (RuntimeException ignored) {} chunks.clear(); changed("멈춤 · " + prompt); }
    void resume() { if (socket == null || playing) return; MyTubePlayer tube = MyTubePlayer.get(context); if (tube.playing()) tube.pause(); control("PLAY"); playing = true; AudioTrack t = track; if (t != null) try { t.play(); } catch (RuntimeException ignored) {} changed("재생 중 · " + prompt); }
    void toggle() { if (playing) pause(); else resume(); }
    /** Ends the music and the connection. */
    void stop() {
        WebSocketClient current = socket; socket = null; ready = false; playing = false;
        if (current != null) { try { current.send(new JSONObject().put("playbackControl", "STOP").toString()); } catch (Exception ignored) {} try { current.close(); } catch (Exception ignored) {} }
        stopAudio(); changed(""); LyriaService.stop(context);
    }
    /** A voice call started or ended: its volume follows (lower under the call). */
    void loudness() { AudioTrack t = track; if (t != null) try { t.setVolume(((GyeotApp) context).store().voiceActive ? IN_CALL : ALONE); } catch (RuntimeException ignored) {} }

    private void receive(String text) {
        try {
            JSONObject message = new JSONObject(text);
            if (message.has("setupComplete")) { ready = true; steer(); control("PLAY"); playing = true; changed("재생 중 · " + prompt); return; }
            JSONObject content = message.optJSONObject("serverContent");
            JSONArray audio = content == null ? null : content.optJSONArray("audioChunks");
            for (int i = 0; audio != null && i < audio.length(); i++) {
                String data = audio.optJSONObject(i) == null ? "" : audio.optJSONObject(i).optString("data");
                if (!data.isEmpty() && playing) { startAudio(); chunks.offer(Base64.getDecoder().decode(data)); }
            }
            if (message.has("filteredPrompt")) changed("이 분위기로는 만들 수 없대요 · 다른 말로 해 보세요");
        } catch (Exception ignored) { /* A message it cannot read is skipped. */ }
    }
    private void closed(WebSocketClient which, String why) {
        if (socket != which) return;
        socket = null; ready = false; playing = false; stopAudio();
        changed(why.isBlank() ? "" : "배경음악 연결이 끝났어요 · " + why);
        LyriaService.stop(context);
    }
    /** The mood (and tempo) for the music now. */
    private void steer() {
        WebSocketClient current = socket; if (current == null) return;
        try {
            current.send(new JSONObject().put("clientContent", new JSONObject().put("weightedPrompts", new JSONArray().put(new JSONObject().put("text", prompt).put("weight", 1.0)))).toString());
            JSONObject config = new JSONObject().put("temperature", 1.1);
            if (bpm >= 60 && bpm <= 200) config.put("bpm", bpm);
            current.send(new JSONObject().put("musicGenerationConfig", config).toString());
        } catch (Exception ignored) {}
    }
    private void control(String what) { WebSocketClient current = socket; if (current == null) return; try { current.send(new JSONObject().put("playbackControl", what).toString()); } catch (Exception ignored) {} }

    private synchronized void startAudio() {
        if (track != null) return;
        AudioManager audio = context.getSystemService(AudioManager.class);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        // In a voice call it takes no audio focus: taking it would cut the assistant's voice (Speaker stops on a loss). It plays
        // lower instead (loudness). Outside a call it takes focus as music does, and another app's music ends it.
        if (!((GyeotApp) context).store().voiceActive) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> { if (change == AudioManager.AUDIOFOCUS_LOSS) main.post(this::stop); }).build();
            if (audio != null) audio.requestAudioFocus(focus);
        }
        int size = Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), RATE * 4);
        AudioTrack mine = new AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build();
        track = mine; loudness(); mine.play();
        // Follows a voice call starting or ending while it plays.
        Runnable[] follow = new Runnable[1];
        follow[0] = () -> { if (track != mine) return; loudness(); main.postDelayed(follow[0], 1000); };
        main.postDelayed(follow[0], 1000);
        new Thread(() -> {
            try { while (track == mine) { byte[] pcm = chunks.take(); if (pcm.length == 0) break; mine.write(pcm, 0, pcm.length); } }
            catch (InterruptedException | RuntimeException done) { /* stopped */ }
        }, "lyria-audio").start();
    }
    private synchronized void stopAudio() {
        AudioTrack old = track; track = null; chunks.clear(); chunks.offer(new byte[0]);
        if (old != null) { try { old.pause(); old.flush(); old.stop(); } catch (RuntimeException ignored) {} old.release(); }
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null && focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }
}
