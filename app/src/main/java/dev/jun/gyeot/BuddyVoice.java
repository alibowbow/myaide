package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레's voice talk on the home screen (준: "퍼레 음성대화는 퍼레를 꾹 누르고 있으면 실행되게 해줘", "우측 바를 당겨서 버블이 뜨는
 * 음성비서 모드와는 별도야"): held, 퍼레 listens (the phone's speech recognizer, in Korean), answers as itself (BuddyTalk.reply)
 * and says it in its Typecast voice (TypecastAccount), then listens again. Two silences in a row, a tap on 퍼레 or leaving the
 * home screen end it. Without a Typecast key, past the month's free characters or when Typecast fails, it speaks with the
 * phone's own voice, a little higher. Everything but the network on the main thread.
 */
final class BuddyVoice {
    /** What 퍼레 shows: listening (with what is heard so far), thinking, speaking (the words), done or error (a last word). */
    interface Show { void state(String state, String text); }
    /** The fingerprint before an answer that may need private memories; then runs either way (passed or not). */
    interface Unlock { void ask(Runnable then); }
    private final Context context; private final AppStore store; private final ExecutorService io; private final Show show;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> turns = new ArrayList<>();
    private SpeechInput.Ear ear; private MediaPlayer player; private TextToSpeech phone; private boolean phoneReady;
    private AudioFocusRequest focus;
    private volatile boolean on; private volatile int generation; private int silences;
    /** The speech playing as it streams in, and how to stop its download (end()). */
    private volatile android.media.AudioTrack track; private final java.util.concurrent.atomic.AtomicReference<Runnable> download = new java.util.concurrent.atomic.AtomicReference<>();

    private final Unlock unlock;
    BuddyVoice(Context context, AppStore store, ExecutorService io, Show show, Unlock unlock) { this.context = context.getApplicationContext(); this.store = store; this.io = io; this.show = show; this.unlock = unlock; }
    boolean on() { return on; }

    void start() {
        if (on) return;
        on = true; silences = 0; turns.clear(); generation++;
        store.buddyTalking = true; store.changed();
        if (!SpeechInput.DEVICE.available(context)) { end("error", "이 휴대폰에서는 음성 인식을 쓸 수 없어요"); return; }
        listen();
    }
    void stop() { if (on) end("done", ""); }

    private void listen() {
        if (!on) return;
        closeEar();
        int mine = generation;
        show.state("listening", "");
        ear = SpeechInput.DEVICE.open(context);
        ear.start(new SpeechInput.Listener() {
            @Override public void ready() {}
            @Override public void partial(String words) { if (on && mine == generation) show.state("listening", words); }
            @Override public void ended() {}
            @Override public void heard(String words) {
                if (!on || mine != generation) return;
                closeEar();
                if (words == null || words.isBlank()) { quiet(); return; }
                silences = 0; think(words.strip());
            }
            @Override public void failed(String reason) { if (on && mine == generation) end("error", reason); }
            @Override public void silent(String reason) { if (on && mine == generation) quiet(); }
        });
    }
    /** Nothing heard: once, it listens again; twice in a row, the talk ends. */
    private void quiet() {
        closeEar();
        if (++silences >= 2) { end("done", "또 불러 주세요!"); return; }
        main.postDelayed(this::listen, 250);
    }
    private void think(String said) {
        if (unlock != null && BuddyTalk.needsUnlock(context, said)) {
            show.state("thinking", said); int mine = generation;
            unlock.ask(() -> { if (on && mine == generation) answer(said); });
            return;
        }
        answer(said);
    }
    private void answer(String said) {
        show.state("thinking", said);
        int mine = generation;
        List<String[]> before = new ArrayList<>(turns);
        try {
            io.execute(() -> BuddyTalk.reply(context, said, before,
                line -> main.post(() -> { if (on && mine == generation) { turns.add(new String[]{"user", said}); turns.add(new String[]{"assistant", line}); say(line); } }),
                () -> main.post(() -> { if (on && mine == generation) end("error", "지금은 대답이 떠오르지 않아요. 조금 뒤에 다시 불러 주세요"); })));
        } catch (RuntimeException stopped) { end("error", ""); }
    }
    /**
     * The answer, said: Typecast streamed (it starts as the first sound arrives, 준: "라이브가 아니라 딜레이가 있긴하겠네"), else
     * Typecast whole, else the phone's voice. Then it listens again.
     */
    private void say(String line) {
        show.state("speaking", line);
        int mine = generation;
        TypecastAccount t = store.typecast;
        JSONObject voice = t.voice();
        if (!t.connected() || voice.optString("id").isBlank() || !t.fits(line.length())) { speakPhone(line); return; }
        Thread worker = new Thread(() -> {
            int played = stream(t, voice, line, mine);
            if (played > 0) { t.count(line.length()); main.post(() -> { if (on && mine == generation) spoken(); }); return; }
            if (played < 0 || !on || mine != generation) return; // stopped
            File out = null;
            try { out = synth(context, t, voice, line); t.count(line.length()); } catch (Exception failed) { out = null; }
            File got = out;
            main.post(() -> { if (!on || mine != generation) return; if (got == null) speakPhone(line); else play(got); });
        }, "gyeot-buddy-say");
        worker.setDaemon(true); worker.start();
    }
    /**
     * Typecast's streamed speech (WAV: a header, then 16-bit mono PCM as it is made) played through an AudioTrack as it comes.
     * Returns 1 when it was played to the end, 0 when nothing could be played (the caller tries the whole file), -1 when it
     * was stopped.
     */
    private int stream(TypecastAccount t, JSONObject voice, String line, int mine) {
        android.media.AudioTrack out = null; boolean started = false;
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", TypecastAccount.API + "/v1/text-to-speech/stream",
                Map.of("X-API-KEY", t.key(), "Content-Type", "application/json", "Accept", "audio/wav"), request(voice, line, "wav").toString().getBytes(StandardCharsets.UTF_8), 30_000, download)) {
            if (response.status >= 300) { OpenAiAccount.Http.read(response.body, 64 * 1024); return 0; }
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(response.body, 16 * 1024));
            int rate = wavHeader(in);
            int min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            out = new android.media.AudioTrack.Builder().setAudioAttributes(Speech.ATTRIBUTES)
                .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 4, rate)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build();
            track = out;
            byte[] buffer = new byte[8192]; int n, carry = 0; long frames = 0;
            while ((n = in.read(buffer, carry, buffer.length - carry)) > 0) {
                if (!on || mine != generation) return -1;
                int have = carry + n, even = have & ~1;
                if (!started && even > 0) { started = true; main.post(() -> focus(true)); out.play(); }
                if (even > 0) { out.write(buffer, 0, even); frames += even / 2; }
                carry = have - even; if (carry == 1) buffer[0] = buffer[even];
            }
            if (!started) return 0;
            // Played to its last frame (or stopped on the way).
            while (on && mine == generation && out.getPlaybackHeadPosition() < frames) Thread.sleep(30);
            return on && mine == generation ? 1 : -1;
        } catch (Exception failed) {
            return !on || mine != generation ? -1 : started ? 1 : 0;
        } finally {
            if (out != null) { try { out.stop(); } catch (RuntimeException ignored) {} out.release(); }
            if (track == out) track = null;
            download.set(null);
        }
    }
    /** The sample rate from a streamed WAV header (RIFF … fmt … data), the stream left at the first sample. */
    private static int wavHeader(java.io.DataInputStream in) throws java.io.IOException {
        byte[] id = new byte[4]; in.readFully(id);
        if (!"RIFF".equals(new String(id, StandardCharsets.US_ASCII))) throw new java.io.IOException("not a wav");
        in.readInt(); in.readFully(id);
        int rate = 32_000;
        for (int chunks = 0; chunks < 16; chunks++) {
            in.readFully(id); String name = new String(id, StandardCharsets.US_ASCII);
            int size = Integer.reverseBytes(in.readInt());
            if ("data".equals(name)) return rate;
            if ("fmt ".equals(name)) {
                byte[] fmt = new byte[Math.max(16, size)]; in.readFully(fmt, 0, size);
                rate = (fmt[4] & 0xff) | (fmt[5] & 0xff) << 8 | (fmt[6] & 0xff) << 16 | (fmt[7] & 0xff) << 24;
                int channels = (fmt[2] & 0xff) | (fmt[3] & 0xff) << 8, bits = (fmt[14] & 0xff) | (fmt[15] & 0xff) << 8;
                if (channels != 1 || bits != 16) throw new java.io.IOException("unexpected wav");
            } else in.skipNBytes(size & 0xffffffffL);
        }
        throw new java.io.IOException("no data");
    }
    /** The request for a line in 퍼레's voice: its model, happy when the voice can be, a touch quick. */
    private static JSONObject request(JSONObject voice, String line, String format) throws Exception {
        String model = voice.optString("model", "ssfm-v30");
        JSONArray emotions = voice.optJSONArray("emotions"); boolean happy = false;
        for (int i = 0; emotions != null && i < emotions.length(); i++) if ("happy".equals(emotions.optString(i))) happy = true;
        JSONObject prompt = new JSONObject().put("emotion_preset", happy ? "happy" : "normal").put("emotion_intensity", 1.1);
        if (!"ssfm-v21".equals(model)) prompt.put("emotion_type", "preset");
        return new JSONObject().put("voice_id", voice.optString("id")).put("text", line).put("model", model).put("language", "kor")
            .put("prompt", prompt).put("output", new JSONObject().put("audio_format", format).put("audio_tempo", 1.05));
    }
    /** Typecast's speech for the line, as an mp3 in the cache. */
    static File synth(Context c, TypecastAccount t, JSONObject voice, String line) throws Exception {
        JSONObject body = request(voice, line, "mp3");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", TypecastAccount.API + "/v1/text-to-speech",
                Map.of("X-API-KEY", t.key(), "Content-Type", "application/json", "Accept", "audio/mpeg"), body.toString().getBytes(StandardCharsets.UTF_8), 30_000, null)) {
            if (response.status >= 300) { OpenAiAccount.Http.read(response.body, 64 * 1024); throw new IllegalStateException("Typecast HTTP " + response.status); }
            File dir = new File(c.getCacheDir(), "buddy-voice"); dir.mkdirs();
            File out = new File(dir, "say.mp3");
            try (java.io.InputStream in = response.body; java.io.OutputStream o = new java.io.FileOutputStream(out)) { in.transferTo(o); }
            return out;
        }
    }
    private void play(File mp3) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(Speech.ATTRIBUTES);
            player.setDataSource(mp3.getPath());
            player.setOnCompletionListener(p -> spoken());
            player.setOnErrorListener((p, what, extra) -> { spoken(); return true; });
            player.prepare();
            focus(true); player.start();
        } catch (Exception failed) { releasePlayer(); spoken(); }
    }
    /** The phone's own voice, a little higher and quicker, for 퍼레. */
    private void speakPhone(String line) {
        int mine = generation;
        Runnable go = () -> {
            if (!on || mine != generation) return;
            phone.setPitch(1.35f); phone.setSpeechRate(1.05f);
            phone.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { main.post(BuddyVoice.this::spoken); }
                @Override public void onError(String id) { main.post(BuddyVoice.this::spoken); }
            });
            focus(true);
            if (phone.speak(line, TextToSpeech.QUEUE_FLUSH, null, "buddy") != TextToSpeech.SUCCESS) spoken();
        };
        if (phone != null && phoneReady) { go.run(); return; }
        if (phone != null) { main.postDelayed(go, 600); return; }
        phone = new TextToSpeech(context, status -> main.post(() -> {
            phoneReady = status == TextToSpeech.SUCCESS && phone != null && phone.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE;
            if (phoneReady) go.run(); else spoken();
        }));
    }
    /** Said: listen again (the loop goes on until silence or a tap). */
    private void spoken() {
        focus(false); releasePlayer();
        if (on) main.postDelayed(this::listen, 350);
    }
    private void end(String state, String words) {
        on = false; generation++;
        if (store.buddyTalking) { store.buddyTalking = false; store.changed(); }
        Runnable cut = download.getAndSet(null); if (cut != null) try { cut.run(); } catch (RuntimeException ignored) {}
        android.media.AudioTrack playing = track; if (playing != null) try { playing.pause(); playing.flush(); } catch (RuntimeException ignored) {}
        closeEar(); releasePlayer(); focus(false);
        if (phone != null) { try { phone.stop(); phone.shutdown(); } catch (RuntimeException ignored) {} phone = null; phoneReady = false; }
        show.state(state, words == null ? "" : words);
    }
    private void closeEar() { if (ear != null) { try { ear.close(); } catch (RuntimeException ignored) {} ear = null; } }
    private void releasePlayer() { if (player != null) { try { player.stop(); } catch (RuntimeException ignored) {} try { player.release(); } catch (RuntimeException ignored) {} player = null; } }
    /** Music and other sounds lower while 퍼레 speaks. */
    private void focus(boolean take) {
        AudioManager am = context.getSystemService(AudioManager.class); if (am == null) return;
        try {
            if (take) { if (focus == null) { focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(Speech.ATTRIBUTES).build(); am.requestAudioFocus(focus); } }
            else if (focus != null) { am.abandonAudioFocusRequest(focus); focus = null; }
        } catch (RuntimeException ignored) {}
    }

    /** A voice's sample from Typecast (no characters spent), one at a time. */
    private static MediaPlayer preview;
    static void preview(String url) {
        if (preview != null) { try { preview.release(); } catch (RuntimeException ignored) {} preview = null; }
        if (url == null || !url.startsWith("https://")) return;
        try {
            MediaPlayer p = new MediaPlayer(); preview = p;
            p.setAudioAttributes(Speech.ATTRIBUTES); p.setDataSource(url);
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnCompletionListener(done -> { try { done.release(); } catch (RuntimeException ignored) {} if (preview == done) preview = null; });
            p.prepareAsync();
        } catch (Exception failed) { preview = null; }
    }
}
