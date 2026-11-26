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
 * and says it in its Typecast voice (TypecastAccount), then listens again. Nothing said for a while (QUIET_HOME, QUIET_OVER), a tap on 퍼레, its ✕ or
 * the notification's 끝내기 end it; another app in front does not (준: "퍼레가 말할때 브라우저라도 켜면 바로 꺼져버려"): the talk
 * lives on its own (one at a time, current()) with a microphone service (BuddyVoiceService), shown on the home screen while it
 * is in front (attach) and over the other apps otherwise (BuddyOverlay). Without a Typecast key, past the month's free characters or when Typecast fails, it speaks with the
 * phone's own voice, a little higher. Everything but the network on the main thread.
 */
final class BuddyVoice {
    /** What 퍼레 shows: listening (with what is heard so far), thinking, speaking (the words), done or error (a last word). */
    interface Show { void state(String state, String text); }
    /** The fingerprint before an answer that may need private memories; then runs either way (passed or not). */
    interface Unlock { void ask(Runnable then); }
    /**
     * Something done on the phone (준: "퍼레는 폰조작이 안되네"): an app opened, a route, an alarm … ("할일" from the answer). Its
     * outcome: what 퍼레 says after (may be empty), whether another app is now in front (the talk goes on over it), and the thing again
     * when it waits for a yes (an alarm or a timer).
     */
    record Outcome(String say, boolean leaves, org.json.JSONObject confirm) {}
    interface Act { Outcome run(org.json.JSONObject act, String heard); }
    private final Context context; private final AppStore store; private final ExecutorService io = java.util.concurrent.Executors.newCachedThreadPool();
    /** The home screen while it is in front (its page shows 퍼레's state; its fingerprint); null when another app is. */
    private volatile Show screen; private volatile Unlock unlock;
    private final BuddyOverlay overlay; private String lastState = "", lastText = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> turns = new ArrayList<>();
    private SpeechInput.Ear ear; private MediaPlayer player; private TextToSpeech phone; private boolean phoneReady;
    private AudioFocusRequest focus;
    private volatile boolean on; private volatile int generation; private int silences;
    /** The speech playing as it streams in, and how to stop its download (end()). */
    private volatile android.media.AudioTrack track; private final java.util.concurrent.atomic.AtomicReference<Runnable> download = new java.util.concurrent.atomic.AtomicReference<>();

    private final Act act;
    /** The thing to do once 퍼레 has said its words, and the one waiting for a yes. */
    private org.json.JSONObject todo, confirming; private String todoHeard = "";
    private static volatile BuddyVoice current;
    /** The one voice talk (made the first time). */
    static synchronized BuddyVoice of(Context context, AppStore store) { if (current == null) current = new BuddyVoice(context, store); return current; }
    static BuddyVoice current() { return current; }
    private BuddyVoice(Context context, AppStore store) {
        this.context = context.getApplicationContext(); this.store = store;
        this.overlay = new BuddyOverlay(this.context, this::stop);
        this.act = (a, heard) -> BuddyActs.run(this.context, a, heard, screen == null && overlay.shown());
    }
    boolean on() { return on; }
    /** The home screen came to the front: it shows the talk (and asks for the fingerprint); the overlay goes. */
    void attach(Show screen, Unlock unlock) {
        this.screen = screen; this.unlock = unlock; overlay.hide();
        if (on) screen.state(lastState, lastText);
    }
    /** The home screen went behind another app: while the talk is on, 퍼레 shows over it. */
    void detach() {
        screen = null; unlock = null;
        if (on) { overlay.show(); overlay.state(lastState, lastText); }
    }
    /** What 퍼레 shows now, on the home screen or over the app in front. */
    private void show(String state, String text) {
        lastState = state; lastText = text == null ? "" : text;
        Show s = screen;
        if (s != null) s.state(state, lastText); else overlay.state(state, lastText);
    }

    void start() {
        if (on) return;
        on = true; silences = 0; turns.clear(); generation++; lastTalk = android.os.SystemClock.uptimeMillis();
        store.buddyTalking = true; store.changed();
        BuddyVoiceService.start(context);
        if (screen == null) overlay.show();
        // 퍼레's short words in its own voice, made once (VoicePresets; 준: "프리셋 보이스 100개", "타입캐스트로").
        VoicePresets.prepare(context, store);
        // The user's voice known and Whisper at hand: 퍼레 listens itself, to know whose voice it is (준: "내 목소리를 알아볼 수 있게해줘").
        voice = BuddyTalk.voice(context); ownEar = voice.ready() && GroqWork.on(); ownBroken = false; deviceOff = false; heardSome = false;
        if (!ownEar && !SpeechInput.DEVICE.available(context)) { end("error", "이 휴대폰에서는 음성 인식을 쓸 수 없어요"); return; }
        listen();
    }
    private volatile dev.jun.gyeot.core.VoicePrint.Profile voice; private boolean ownEar, ownBroken; private VoiceCapture capture;
    /** The phone's recognizer could not hear over another app (an error, or only silence before anything was heard): 퍼레's own ear from then on. */
    private boolean deviceOff, heardSome;
    /** Over another app, the phone's recognizer cannot hear: 퍼레's own ear (Groq) takes over when it can. */
    private boolean ownEarCanTakeOver() { return screen == null && !ownEar && !deviceOff && !ownBroken && GroqWork.on(); }
    private final java.util.concurrent.atomic.AtomicReference<Runnable> whisper = new java.util.concurrent.atomic.AtomicReference<>();
    void stop() { if (on) end("done", ""); }

    private void listen() {
        if (!on) return;
        stopOver();
        closeEar(); listenAt = android.os.SystemClock.uptimeMillis();
        int mine = generation;
        show("listening", "");
        // The phone's own recognizer first, also over another app (준: "다른 앱 위에서도 휴대폰 기본 음성인식 먼저 쓰기"); 퍼레's own
        // ear when the user's voice is known (it needs the sound) or when the recognizer could not hear there.
        if (ownEar || deviceOff && !ownBroken && GroqWork.on()) { listenOwn(mine); return; }
        ear = SpeechInput.DEVICE.open(context);
        boolean[] some = {false};
        ear.start(new SpeechInput.Listener() {
            @Override public void ready() {}
            @Override public void partial(String words) { if (on && mine == generation) { if (words != null && !words.isBlank()) some[0] = true; show("listening", BuddyTalk.heard(context, words)); } }
            @Override public void ended() {}
            @Override public void heard(String words) {
                if (!on || mine != generation) return;
                heardAt = android.os.SystemClock.uptimeMillis(); wordsAt = heardAt;
                closeEar();
                if (words == null || words.isBlank()) { quiet(some[0]); return; }
                heardSome = true; silences = 0; filler(); think(BuddyTalk.heard(context, words.strip()), false);
            }
            @Override public void failed(String reason) {
                if (!on || mine != generation) return;
                if (ownEarCanTakeOver()) { closeEar(); deviceOff = true; listen(); return; }
                end("error", reason);
            }
            @Override public void silent(String reason) { if (on && mine == generation) quiet(some[0]); }
        });
    }
    /**
     * 퍼레's own ear: the microphone cut at the end of what was said (Utterance), the voice checked against the user's
     * (VoicePrint: the owner's confident ones are learned, another voice is a guest), the words by Whisper in Korean. When
     * the microphone or Whisper fails, the phone's recognizer listens from then on.
     */
    private void listenOwn(int mine) {
        capture = VoiceCapture.start(7000, new VoiceCapture.Heard() {
            @Override public void speech(short[] pcm) { if (on && mine == generation) { capture = null; heardAt = android.os.SystemClock.uptimeMillis(); filler(); hear(pcm, mine); } }
            @Override public void silent() { if (on && mine == generation) { capture = null; quiet(false); } }
            @Override public void failed(String why) { if (on && mine == generation) { capture = null; ownEar = false; ownBroken = true; listen(); } }
            @Override public void began() { if (on && mine == generation) show("listening", "…"); }
        });
    }
    private void hear(short[] pcm, int mine) {
        show("thinking", "");
        dev.jun.gyeot.core.VoicePrint.Profile known = voice;
        try {
            io.execute(() -> {
                float[] mark = dev.jun.gyeot.core.VoicePrint.mark(pcm);
                dev.jun.gyeot.core.VoicePrint.Who who = dev.jun.gyeot.core.VoicePrint.who(known, mark);
                if (who == dev.jun.gyeot.core.VoicePrint.Who.OWNER) { dev.jun.gyeot.core.VoicePrint.Profile learned = known.learn(mark); voice = learned; BuddyTalk.voice(context, learned); }
                String words;
                try { words = GroqWork.words(dev.jun.gyeot.core.Utterance.wav(pcm, dev.jun.gyeot.core.WakeWord.RATE), whisper); } catch (Exception failed) { words = null; }
                // The voice went up at the end: a question, though the words carry no "?" (준: "음악소리들려? 라고 말하면 음악소리들려로 알아들어").
                if (words != null && !words.isBlank() && !words.strip().endsWith("?") && dev.jun.gyeot.core.Intonation.rising(pcm)) words = words.strip().replaceAll("[.!。]+$", "") + "?";
                String got = words == null ? null : BuddyTalk.heard(context, words);
                main.post(() -> {
                    if (!on || mine != generation) return;
                    if (got == null) { ownEar = false; ownBroken = true; listen(); return; }
                    if (got.isBlank()) { quiet(false); return; }
                    wordsAt = android.os.SystemClock.uptimeMillis();
                    heardSome = true; silences = 0; think(got, who == dev.jun.gyeot.core.VoicePrint.Who.STRANGER);
                });
            });
        } catch (RuntimeException stopped) { end("error", ""); }
    }
    /**
     * How long 퍼레 waits for words before the talk ends (준: "들퍼레가 너무 빨리 꺼져", "홈화면에서는 15초 다른 앱 위에서는 3분으로
     * 하자"): from the last words, the user's or its own; over another app the user is doing something there.
     */
    static final long QUIET_HOME = 15_000, QUIET_OVER = 180_000;
    private long lastTalk, listenAt;
    /**
     * Nothing heard: it listens again until the talk has been quiet for QUIET_HOME (QUIET_OVER over another app). misheard:
     * words began to come but none were made out; only then it asks to hear them again (준: "아무 소리도 안했는데 다시말해줄래는 뭐임").
     */
    private void quiet(boolean misheard) {
        closeEar();
        // Over another app the recognizer may get only silence: before ending, 퍼레's own ear gets one more try.
        if (silences + 1 >= 2 && !heardSome && ownEarCanTakeOver()) { deviceOff = true; main.postDelayed(this::listen, 150); return; }
        silences++;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTalk >= (screen == null ? QUIET_OVER : QUIET_HOME)) { end("done", "또 불러 줘!"); return; }
        int mine = generation;
        Runnable again = () -> { if (on && mine == generation) listen(); };
        // Words that were not made out: "응? 다시 말해 줄래?" in its own voice (after the sound, so it does not hear itself).
        // Silence: it waits quietly. A recognizer that gave up at once is not asked again at once.
        if (misheard) { String said = cue("again", () -> main.postDelayed(again, 150)); if (said != null) show("speaking", said); return; }
        main.postDelayed(again, now - listenAt < 1500 ? 1200 : 250);
    }

    // ── 퍼레's short words, at once (VoicePresets) ──
    private MediaPlayer cue; private volatile long cueEnds;
    /** A sound while the answer is made, right as the user stops talking (most turns, a different one each time). */
    private void filler() { if (Math.random() < .8) cue("think", null); }
    /** One of 퍼레's short words of this kind, then after (on the main thread; at once when none is made yet); the words said, or null. */
    private String cue(String kind, Runnable after) {
        java.io.File f = VoicePresets.pick(context, store, kind);
        if (f == null) { if (after != null) after.run(); return null; }
        stopCue();
        try {
            MediaPlayer p = new MediaPlayer(); cue = p;
            p.setAudioAttributes(Speech.ATTRIBUTES); p.setDataSource(f.getPath());
            Runnable finished = () -> { cueEnds = 0; if (cue == p) cue = null; try { p.release(); } catch (RuntimeException ignored) {} if (!on && player == null && track == null) focus(false); if (after != null) after.run(); };
            p.setOnCompletionListener(x -> finished.run());
            p.setOnErrorListener((x, what, extra) -> { finished.run(); return true; });
            p.prepare(); focus(true); p.start();
            cueEnds = android.os.SystemClock.uptimeMillis() + Math.max(200, p.getDuration());
            return VoicePresets.words(f);
        } catch (Exception failed) { stopCue(); if (after != null) after.run(); return null; }
    }
    private void stopCue() {
        MediaPlayer p = cue; cue = null; cueEnds = 0;
        if (p != null) { try { p.stop(); } catch (RuntimeException ignored) {} try { p.release(); } catch (RuntimeException ignored) {} }
    }
    /** guest: not the user's voice; the fingerprint, when what was asked needs it, shows it was the user after all. */
    private void think(String said, boolean guest) {
        lastTalk = android.os.SystemClock.uptimeMillis();
        // An alarm or a timer waiting for a yes: this was the answer.
        if (confirming != null) {
            org.json.JSONObject waiting = confirming; confirming = null;
            if (!guest && act != null && dev.jun.gyeot.core.DeviceActions.affirmative(said)) { show("thinking", said); doNow(waiting, said); return; }
        }
        if (unlock != null && BuddyTalk.needsUnlock(context, said)) {
            show("thinking", said); int mine = generation;
            unlock.ask(() -> { if (on && mine == generation) answer(said, guest && !BuddyTalk.unlocked()); });
            return;
        }
        answer(said, guest);
    }
    private void answer(String said, boolean guest) {
        show("thinking", said);
        int mine = generation;
        List<String[]> before = new ArrayList<>(turns);
        try {
            streaming = true; partsUsed = false; parts.clear(); answeredAt = 0;
            io.execute(() -> BuddyTalk.reply(context, said, before, guest, guest || act == null ? null : a -> main.post(() -> { if (on && mine == generation) { todo = a; todoHeard = said; } }),
                part -> main.post(() -> { if (on && mine == generation) sayPart(part); }),
                line -> main.post(() -> { if (on && mine == generation) { if (answeredAt == 0) answeredAt = android.os.SystemClock.uptimeMillis(); turns.add(new String[]{"user", said}); turns.add(new String[]{"assistant", line}); finish(line); } }),
                () -> main.post(() -> { if (on && mine == generation) end("error", "지금은 대답이 안 떠올라. 조금 있다 다시 불러 줘"); })));
        } catch (RuntimeException stopped) { end("error", ""); }
    }
    /**
     * The answer, said: Typecast streamed (it starts as the first sound arrives, 준: "라이브가 아니라 딜레이가 있긴하겠네"), else
     * Typecast whole, else the phone's voice. Then it listens again.
     */
    // ── the answer as it is written (준: "음성대화 너무 느려"): each sentence said as it comes, the next waiting its turn ──
    private final java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
    private boolean streaming, partsUsed, saying;
    /** A sentence of the answer, written: said now, or after the one being said. */
    private void sayPart(String part) {
        partsUsed = true; if (answeredAt == 0) answeredAt = android.os.SystemClock.uptimeMillis();
        if (saying) parts.add(part); else say(part);
    }
    /** The whole answer: said when no sentence came on its own, else the talk goes on once the last one is said. */
    private void finish(String line) {
        streaming = false;
        if (!partsUsed) { say(line); return; }
        if (!saying && parts.isEmpty()) spoken();
    }
    private void say(String line) {
        saying = true;
        // The short sound first, the answer right after it.
        long wait = cueEnds - android.os.SystemClock.uptimeMillis();
        if (wait > 0) { int mine = generation; main.postDelayed(() -> { if (on && mine == generation) say(line); }, Math.min(wait, 2500) + 60); return; }
        show("speaking", line);
        listenOver();
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
                if (!started && even > 0) { started = true; main.post(() -> focus(true)); out.play(); timing("타입캐스트"); }
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
    /**
     * How long each step took, in the app's log (준: "음성대화 너무 느려", "타입캐스트 딜레이가 어마어마한데"): from the end of what
     * was said to its words, to 퍼레's answer, to the first sound.
     */
    private volatile long heardAt, wordsAt, answeredAt;
    private void timing(String voice) {
        long now = android.os.SystemClock.uptimeMillis();
        if (heardAt == 0 || answeredAt == 0) return;
        try { store.phoneLog(String.format(java.util.Locale.ROOT, "● 퍼레 음성: 글자로 %.1f초 · 대답 %.1f초 · %s 첫 소리 %.1f초 (모두 %.1f초)", (wordsAt - heardAt) / 1000.0, (answeredAt - wordsAt) / 1000.0, voice, (now - answeredAt) / 1000.0, (now - heardAt) / 1000.0)); }
        catch (RuntimeException ignored) {}
        heardAt = 0; answeredAt = 0;
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
            focus(true); player.start(); timing("타입캐스트(통째로)");
        } catch (Exception failed) { releasePlayer(); spoken(); }
    }
    // ── talking over 퍼레 (barge-in, core/TalkOver): it stops, does what it promised, and listens ──
    private java.util.concurrent.atomic.AtomicBoolean overRun;
    /** While 퍼레 speaks, the microphone (with the phone's echo canceller) waits for the user's voice over it. */
    private void listenOver() {
        if (overRun != null || !on) return;
        java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(true); overRun = run;
        int mine = generation;
        Thread t = new Thread(() -> {
            android.media.AudioRecord mic = null; android.media.audiofx.AcousticEchoCanceler echo = null; android.media.audiofx.NoiseSuppressor noise = null;
            try {
                int min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                mic = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 6400));
                if (mic.getState() != android.media.AudioRecord.STATE_INITIALIZED) return;
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) { echo = android.media.audiofx.AcousticEchoCanceler.create(mic.getAudioSessionId()); if (echo != null) echo.setEnabled(true); }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) { noise = android.media.audiofx.NoiseSuppressor.create(mic.getAudioSessionId()); if (noise != null) noise.setEnabled(true); }
                mic.startRecording();
                dev.jun.gyeot.core.TalkOver over = new dev.jun.gyeot.core.TalkOver(); short[] frame = new short[dev.jun.gyeot.core.TalkOver.FRAME];
                while (run.get()) {
                    int n = mic.read(frame, 0, frame.length); if (n <= 0) return;
                    if (over.add(frame, n)) { run.set(false); main.post(() -> { if (on && mine == generation) talkedOver(); }); return; }
                }
            } catch (RuntimeException unavailable) { /* no barge-in this time */ }
            finally {
                if (echo != null) try { echo.release(); } catch (RuntimeException ignored) {}
                if (noise != null) try { noise.release(); } catch (RuntimeException ignored) {}
                if (mic != null) { try { mic.stop(); } catch (RuntimeException ignored) {} mic.release(); }
            }
        }, "gyeot-buddy-over");
        t.setDaemon(true); t.start();
    }
    private void stopOver() { java.util.concurrent.atomic.AtomicBoolean run = overRun; overRun = null; if (run != null) run.set(false); }
    /** The user spoke over 퍼레: what it was saying stops (the rest of the answer too), a thing already promised is done, then it listens. */
    private void talkedOver() {
        if (!on || !saying) return;
        stopOver(); generation++;
        Runnable cut = download.getAndSet(null); if (cut != null) try { cut.run(); } catch (RuntimeException ignored) {}
        android.media.AudioTrack playing = track; if (playing != null) try { playing.pause(); playing.flush(); } catch (RuntimeException ignored) {}
        releasePlayer(); stopCue();
        if (phone != null) try { phone.stop(); } catch (RuntimeException ignored) {}
        saying = false; streaming = false; parts.clear(); focus(false);
        lastTalk = android.os.SystemClock.uptimeMillis(); silences = 0;
        store.phoneLog("● 퍼레 음성: 말하는 중에 사용자가 말해서 멈추고 들어요");
        org.json.JSONObject promised = todo; todo = null;
        if (promised != null && act != null) { doNow(promised, todoHeard); return; }
        main.postDelayed(this::listen, 150); // the microphone let go first
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
            focus(true); timing("휴대폰 목소리");
            if (phone.speak(line, TextToSpeech.QUEUE_FLUSH, null, "buddy") != TextToSpeech.SUCCESS) spoken();
        };
        if (phone != null && phoneReady) { go.run(); return; }
        if (phone != null) { main.postDelayed(go, 600); return; }
        phone = new TextToSpeech(context, status -> main.post(() -> {
            phoneReady = status == TextToSpeech.SUCCESS && phone != null && phone.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE;
            if (phoneReady) go.run(); else spoken();
        }));
    }
    /** Said: what was promised is done now, then it listens again (the loop goes on until silence or a tap). */
    private void spoken() {
        saying = false;
        String next = on ? parts.poll() : null;
        if (next != null) { say(next); return; }
        if (on && streaming) return; // more of the answer on its way
        stopOver(); focus(false); releasePlayer();
        if (!on) return;
        lastTalk = android.os.SystemClock.uptimeMillis();
        org.json.JSONObject now = todo; todo = null;
        if (now != null && act != null) { doNow(now, todoHeard); return; }
        main.postDelayed(this::listen, 350);
    }
    /** A thing to do, off the main thread (a place search may wait on the network), then its words. */
    private void doNow(org.json.JSONObject a, String heard) {
        int mine = generation;
        try { io.execute(() -> { Outcome o = act.run(a, heard); main.post(() -> { if (on && mine == generation) done(o); }); }); }
        catch (RuntimeException stopped) { main.postDelayed(this::listen, 350); }
    }
    /** After a thing done: its words (a question when it waits for a yes), and the talk ends when another app came up. */
    private void done(Outcome o) {
        lastTalk = android.os.SystemClock.uptimeMillis();
        if (o == null) { main.postDelayed(this::listen, 350); return; }
        if (o.confirm() != null) confirming = o.confirm();
        // Another app came up: the talk goes on over it (BuddyOverlay), 퍼레 listening again.
        if (o.leaves()) { if (o.say() != null && !o.say().isBlank()) { say(o.say()); return; } main.postDelayed(this::listen, 900); return; }
        if (o.say() != null && !o.say().isBlank()) { turns.add(new String[]{"assistant", o.say()}); say(o.say()); return; }
        main.postDelayed(this::listen, 350);
    }
    private void end(String state, String words) {
        on = false; generation++; todo = null; confirming = null; stopOver(); parts.clear(); streaming = false; saying = false;
        if (store.buddyTalking) { store.buddyTalking = false; store.changed(); }
        Runnable cut = download.getAndSet(null); if (cut != null) try { cut.run(); } catch (RuntimeException ignored) {}
        Runnable listening = whisper.getAndSet(null); if (listening != null) try { listening.run(); } catch (RuntimeException ignored) {}
        android.media.AudioTrack playing = track; if (playing != null) try { playing.pause(); playing.flush(); } catch (RuntimeException ignored) {}
        closeEar(); releasePlayer(); focus(false);
        if (phone != null) { try { phone.stop(); phone.shutdown(); } catch (RuntimeException ignored) {} phone = null; phoneReady = false; }
        // After the quiet, a goodbye in its own voice (the bubble shows what it says).
        String bye = "done".equals(state) && "또 불러 줘!".equals(words) ? cue("bye", null) : null;
        if (bye == null) stopCue();
        show(state, bye != null ? bye : words == null ? "" : words);
        BuddyVoiceService.stop(context);
        // The last word stays a moment over the other app, then 퍼레 goes.
        int ended = generation; main.postDelayed(() -> { if (!on && generation == ended) overlay.hide(); }, words == null || words.isBlank() ? 0 : 1800);
    }
    private void closeEar() {
        if (ear != null) { try { ear.close(); } catch (RuntimeException ignored) {} ear = null; }
        if (capture != null) { capture.stop(); capture = null; }
    }
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
