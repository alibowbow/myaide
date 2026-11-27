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
 * phone's own voice, a little higher. An answer is said sentence by sentence as it is written, the next one's sound fetched
 * while one plays (Pipe). The user can talk over it (Over: its own voice never stops it, their first words are kept), and a few
 * words are answered on the phone with no AI (core/VoiceTurns: a bare call, 그만, 뭐라고?, 잠깐, a no). A talk goes on from the
 * typed 퍼레와 대화 or from the last talk (start(seed)), and one brain failure asks to hear it again rather than ending it. A
 * thing promised waits when the user cuts in ("아니 멜론으로" lets it go), a read-back can be corrected ("아니 8시로"), the next
 * answer knows what was just done (Ask.recent), and an app opened that did not come up is said once. What the talk taught
 * is learned once, from all of it and what was done (learn()), not a request a turn; a guest's turns never.
 * Everything but the network, the microphone and the playing sound on the main thread.
 */
final class BuddyVoice {
    /**
     * What 퍼레 shows: listening (with what is heard so far), thinking, speaking (the words, as their sound starts), gap (between
     * two sentences, nothing sounding: the words stay, the mouth rests), done or error (a last word).
     */
    interface Show {
        void state(String state, String text);
        /** As state(state, text); ms, when more than 0: how long the words take to say (the bubble types them over that time). */
        default void state(String state, String text, int ms) { state(state, text); }
        /** As state(state, text, ms); go: what a tap on these words opens ("fix:overlay" …, BuddyMisses.fixIntent), "" nothing. */
        default void state(String state, String text, int ms, String go) { state(state, text, ms); }
    }
    /** The fingerprint before an answer that may need private memories; then runs either way (passed or not). */
    interface Unlock { void ask(Runnable then); }
    /**
     * Something done on the phone (준: "퍼레는 폰조작이 안되네"): an app opened, a route, an alarm … ("할일" from the answer). Its
     * outcome: what 퍼레 says after (may be empty), whether another app is now in front (the talk goes on over it), the thing again
     * when it waits for a yes (an alarm or a timer), and whether it went wrong (kept in 퍼레가 못 한 일: not learned from).
     */
    record Outcome(String say, boolean leaves, org.json.JSONObject confirm, boolean missed) {
        Outcome(String say, boolean leaves, org.json.JSONObject confirm) { this(say, leaves, confirm, false); }
    }
    /** answers: heard answers 퍼레's own read-back (BuddyActs.run); late: who hears of an app that did not come up. */
    interface Act { Outcome run(org.json.JSONObject act, String heard, boolean answers, BuddyActs.Late late); }
    private final Context context; private final AppStore store; private final ExecutorService io = java.util.concurrent.Executors.newCachedThreadPool();
    /** The home screen while it is in front (its page shows 퍼레's state; its fingerprint); null when another app is. */
    private volatile Show screen; private volatile Unlock unlock;
    private final BuddyOverlay overlay; private String lastState = "", lastText = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> turns = new ArrayList<>();
    private SpeechInput.Ear ear; private MediaPlayer player; private TextToSpeech phone; private boolean phoneReady;
    private AudioFocusRequest focus;
    private volatile boolean on; private volatile int generation; private int silences;
    /** The answer being said (its sentences fetched and played in order), until it is said or cut short; main thread. */
    private Pipe pipe;

    private final Act act;
    /**
     * The thing to do once 퍼레 has said its words (with the words that asked for it, and whether they answered 퍼레's own
     * read-back), and the one waiting for a yes (with the question 퍼레 asked for it).
     */
    private org.json.JSONObject todo, confirming; private String todoHeard = "", confirmAsk = ""; private boolean todoCorrects;
    private static volatile BuddyVoice current;
    /** The one voice talk (made the first time). */
    static synchronized BuddyVoice of(Context context, AppStore store) { if (current == null) current = new BuddyVoice(context, store); return current; }
    static BuddyVoice current() { return current; }
    /** The phone's recognizer (tests put their own). */
    static SpeechInput.Ears ears = SpeechInput.DEVICE;
    /** A talk of its own (tests); the app uses of(). */
    BuddyVoice(Context context, AppStore store) {
        this.context = context.getApplicationContext(); this.store = store;
        this.overlay = new BuddyOverlay(this.context, this::stop);
        this.act = (a, heard, answers, late) -> BuddyActs.run(this.context, a, heard, screen == null && overlay.shown(), answers, late);
    }
    boolean on() { return on; }
    /** The home screen came to the front: it shows the talk (and asks for the fingerprint); the overlay goes. */
    void attach(Show screen, Unlock unlock) {
        this.screen = screen; this.unlock = unlock; overlay.hide();
        // A talk that ended while another app was in front: the home screen still showed it talking (준: "말도 안하는데 입을 자꾸
        // 움직여", "제자리에서 계속"), so it is told it ended.
        if (on) screen.state(lastState, lastText); else screen.state("idle", "");
    }
    /** The home screen went behind another app: while the talk is on, 퍼레 shows over it. */
    void detach() {
        screen = null; unlock = null;
        if (on) { overlay.show(); overlay.state(lastState, lastText); }
    }
    /** What 퍼레 shows now, on the home screen or over the app in front. */
    private void show(String state, String text) { show(state, text, 0); }
    /** As show(state, text), with how long its words take to say (0: not known); a gap keeps the words said last. */
    private void show(String state, String text, int ms) {
        boolean gap = "gap".equals(state);
        lastState = state; if (!gap) lastText = text == null ? "" : text;
        // The words that say an app did not come up open the way through it when tapped (late()).
        String go = "speaking".equals(state) && !lateGo.isEmpty() && lastText.equals(lateLine) ? lateGo : "";
        Show s = screen;
        if (s != null) s.state(state, gap ? "" : lastText, ms, go); else overlay.state(state, lastText);
    }

    void start() { start(null); }
    /**
     * A talk, going on from what came before (준: "음성상호작용을 더 강화해줘"): the turns typed in 퍼레와 대화 (seed, [[role, text]
     * …] as JSON) when there are some, else this talk's last turns when it ended less than REMEMBER_MS ago, else none.
     */
    void start(String seed) {
        if (on) return;
        List<String[]> typed = seed(seed);
        boolean recent = endedAt != 0 && android.os.SystemClock.elapsedRealtime() - endedAt < REMEMBER_MS;
        List<String[]> kept = !typed.isEmpty() ? typed : recent ? new ArrayList<>(turns.subList(Math.max(0, turns.size() - SEED_TURNS), turns.size())) : List.of();
        turns.clear(); turns.addAll(kept); learnedTo = turns.size(); acts.clear();
        // What was done last goes on with the talk it was done in (its own two minutes still count).
        if (!(recent && typed.isEmpty())) lastAct = null;
        held = null; meant = null; pendingAsk = null; heardNow = false; lateLine = ""; lateGo = "";
        on = true; silences = 0; failures = 0; generation++; lastTalk = android.os.SystemClock.uptimeMillis(); talkFrom = lastTalk;
        // What 퍼레's echo sounds like is learned afresh for this talk (the phone may be in another room, or on a speaker now).
        barge = new dev.jun.gyeot.core.BargeIn(); cutAt = 0; lastLine = ""; lastSound = null; answers = 0; awaited = 0;
        music = false; overMusic = false; saidAt = 0; hintsFor("");
        store.buddyTalking = true; store.changed();
        BuddyVoiceService.start(context);
        if (screen == null) overlay.show();
        // 퍼레's short words in its own voice, made once (VoicePresets; 준: "프리셋 보이스 100개", "타입캐스트로").
        VoicePresets.prepare(context, store);
        // The user's voice known and Whisper at hand: 퍼레 listens itself, to know whose voice it is (준: "내 목소리를 알아볼 수 있게해줘").
        voice = BuddyTalk.voice(context); ownEar = voice.ready() && GroqWork.on(); ownBroken = false; deviceOff = false; heardSome = false;
        if (!ownEar && !ears.available(context)) { end("error", "이 휴대폰에서는 음성 인식을 쓸 수 없어요"); return; }
        listen();
    }
    /** How long after a talk ended its turns are still there for the next one, and how many of them (typed ones too) go on. */
    static final long REMEMBER_MS = 10 * 60_000L; static final int SEED_TURNS = 8;
    /** When the last talk ended (elapsed ms since boot, sleep included; 0: none yet). */
    long endedAt;
    /** The turns typed in 퍼레와 대화 (JSON [[role, text] …]), the last SEED_TURNS, each cut to 200 letters; none when unreadable. */
    static List<String[]> seed(String json) {
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray t = a.optJSONArray(i); if (t == null) continue;
                String text = t.optString(1, "").strip(); if (text.isEmpty()) continue;
                out.add(new String[]{"user".equals(t.optString(0)) ? "user" : "assistant", text.length() > 200 ? text.substring(0, 200) : text});
            }
        } catch (Exception unreadable) { /* none */ }
        return new ArrayList<>(out.subList(Math.max(0, out.size() - SEED_TURNS), out.size()));
    }
    private volatile dev.jun.gyeot.core.VoicePrint.Profile voice; private boolean ownEar, ownBroken; private VoiceCapture capture;
    /** The phone's recognizer could not hear over another app (an error, or only silence before anything was heard): 퍼레's own ear from then on. */
    private boolean deviceOff, heardSome;
    /** When 퍼레's ear last had to take over from the phone's recognizer over another app (uptime ms), for the next talks. */
    private static long earTookOver = -600_000;
    /** Over another app, the phone's recognizer cannot hear: 퍼레's own ear (Groq) takes over when it can. */
    private boolean ownEarCanTakeOver() { return screen == null && !ownEar && !deviceOff && !ownBroken && GroqWork.on(); }
    private final java.util.concurrent.atomic.AtomicReference<Runnable> whisper = new java.util.concurrent.atomic.AtomicReference<>();
    void stop() { if (on) end("done", ""); }

    private void listen() {
        if (!on) return;
        // One microphone at a time: the one that listened over 퍼레 lets it go first (a frame, 300 ms at most).
        stopOver(); overGone();
        closeEar(); listenAt = android.os.SystemClock.uptimeMillis(); heardNow = false;
        int mine = generation;
        prepareCues(); checkMusic();
        SpeechInput.hints(hints);
        // The phone's own recognizer first, also over another app (준: "다른 앱 위에서도 휴대폰 기본 음성인식 먼저 쓰기"); 퍼레's own
        // ear when the user's voice is known (it needs the sound) or when the recognizer could not hear there.
        if (ownEar || deviceOff && !ownBroken && GroqWork.on()) { show("listening", idle()); listenOwn(mine); return; }
        // Over another app that keeps the microphone (a navigation app: 준 "네이버지도 앱 위에서 음성대화 시 반응이 느리고"), the
        // phone's recognizer hears only silence for seconds before 퍼레's ear takes over: its ear at once when that app records
        // now, or when the ear had to take over in the last ten minutes.
        if (screen == null && !ownBroken && GroqWork.on() && (micTaken() || android.os.SystemClock.uptimeMillis() - earTookOver < 600_000)) {
            deviceOff = true; store.phoneLog("● 퍼레 음성: 다른 앱이 마이크를 쓰는 중이라 바로 퍼레의 귀로 들어요");
            show("listening", idle()); listenOwn(mine); return;
        }
        // The recognizer shows it listens once it is ready to hear (ready()), so words said before that are not lost; until then
        // what 퍼레 said stays with its mouth at rest.
        if ("speaking".equals(lastState)) show("gap", ""); else if (!"gap".equals(lastState) && !"listening".equals(lastState)) show("thinking", "");
        ear = ears.open(context);
        boolean[] some = {false};
        ear.start(new SpeechInput.Listener() {
            @Override public void ready() { if (on && mine == generation) show("listening", idle()); }
            @Override public void partial(String words) {
                if (!on || mine != generation || words == null || words.isBlank()) return;
                // Over music, a song's words are neither shown nor taken for words not made out.
                String heard = BuddyTalk.heard(context, words);
                if (gate(heard) == null) return;
                some[0] = true; heardNow = true; show("listening", heard);
            }
            @Override public void ended() {}
            @Override public void heard(String words) {
                if (!on || mine != generation) return;
                heardAt = android.os.SystemClock.uptimeMillis(); wordsAt = heardAt;
                closeEar();
                if (words == null || words.isBlank()) { quiet(some[0]); return; }
                if (screen == null && guidance(words)) { quiet(false); return; } // the navigation app's own voice
                String said = gate(BuddyTalk.heard(context, words.strip()));
                if (said == null) { passed(); return; } // a song's words over the music, not said to 퍼레
                // Words 퍼레 answers itself (core/VoiceTurns) have no "음…" before them.
                heardSome = true; silences = 0; micSince = 0; if (turn(said, false) == dev.jun.gyeot.core.VoiceTurns.Kind.NONE) filler(said); think(said, false);
            }
            @Override public void failed(String reason) {
                if (!on || mine != generation) return;
                if (ownEarCanTakeOver()) { closeEar(); deviceOff = true; earTookOver = android.os.SystemClock.uptimeMillis(); listen(); return; }
                // Over another app the recognizer fails while that app has the microphone: it waits and tries again.
                if (screen == null && waitForMicrophone(reason)) return;
                end("error", reason);
            }
            @Override public void silent(String reason) { if (on && mine == generation) { if (!some[0] && micTaken()) { closeEar(); if (switchEar()) return; } quiet(some[0]); } }
        });
    }
    /**
     * 퍼레's own ear: the microphone cut at the end of what was said (Utterance), the voice checked against the user's
     * (VoicePrint: the owner's confident ones are learned, another voice is a guest), the words by Whisper in Korean. When
     * the microphone or Whisper fails, the phone's recognizer listens from then on.
     */
    private void listenOwn(int mine) {
        capture = VoiceCapture.start(7000, new VoiceCapture.Heard() {
            @Override public void speech(short[] pcm) { if (on && mine == generation) { capture = null; heardAt = android.os.SystemClock.uptimeMillis(); if (!gated()) filler(null); hear(pcm, mine, false); } }
            @Override public void silent() { if (on && mine == generation) { capture = null; quiet(false); } }
            @Override public void failed(String why) {
                if (!on || mine != generation) return;
                capture = null;
                // Silenced by another app: not broken, it waits for the microphone (and says why, with the way to fix it).
                if (VoiceCapture.SILENCED.equals(why)) { if (!waitForMicrophone(why)) end("error", micWords()); return; }
                ownEar = false; ownBroken = true; listen();
            }
            @Override public void began() { if (on && mine == generation) { heardNow = true; show("listening", gated() ? "♪" : "…"); } }
        });
    }
    /**
     * What was said, to Whisper and VoicePrint. fromOverMic: from the microphone that listened over 퍼레 (the call's, with the
     * echo canceller), which sounds unlike the one the voice was enrolled with: there a voice unlike the user's is not taken
     * for a guest, and nothing is learned (the owner must never become a guest).
     */
    private void hear(short[] pcm, int mine, boolean fromOverMic) {
        // Over music it is not known yet whether this was said to 퍼레: the note stays until it is.
        if (!gated()) show("thinking", "");
        dev.jun.gyeot.core.VoicePrint.Profile known = voice;
        String prompt = dev.jun.gyeot.core.VoiceHints.prompt(lastLine, hints);
        try {
            io.execute(() -> {
                float[] mark = dev.jun.gyeot.core.VoicePrint.mark(pcm);
                dev.jun.gyeot.core.VoicePrint.Who who = dev.jun.gyeot.core.VoicePrint.who(known, mark);
                if (fromOverMic && who == dev.jun.gyeot.core.VoicePrint.Who.STRANGER) who = dev.jun.gyeot.core.VoicePrint.Who.UNSURE;
                if (who == dev.jun.gyeot.core.VoicePrint.Who.OWNER && !fromOverMic) { dev.jun.gyeot.core.VoicePrint.Profile learned = known.learn(mark); voice = learned; BuddyTalk.voice(context, learned); }
                boolean guest = who == dev.jun.gyeot.core.VoicePrint.Who.STRANGER;
                String words;
                try { words = GroqWork.words(dev.jun.gyeot.core.Utterance.wav(pcm, dev.jun.gyeot.core.WakeWord.RATE), prompt, whisper); } catch (Exception failed) { words = null; }
                // The voice went up at the end: a question, though the words carry no "?" (준: "음악소리들려? 라고 말하면 음악소리들려로 알아들어").
                if (words != null && !words.isBlank() && !words.strip().endsWith("?") && dev.jun.gyeot.core.Intonation.rising(pcm)) words = words.strip().replaceAll("[.!。]+$", "") + "?";
                String got = words == null ? null : BuddyTalk.heard(context, words);
                main.post(() -> {
                    if (!on || mine != generation) return;
                    // Whisper could not make it out at all: "다시 말해 줄래?", then the phone's recognizer listens from now on.
                    if (got == null) { ownEar = false; ownBroken = true; cue("again", true, () -> main.postDelayed(() -> { if (on && mine == generation) listen(); }, 150)); return; }
                    if (got.isBlank()) { quiet(false); return; }
                    if (screen == null && guidance(got)) { quiet(false); return; }
                    String said = gate(got);
                    if (said == null) { passed(); return; }
                    wordsAt = android.os.SystemClock.uptimeMillis();
                    heardSome = true; silences = 0; micSince = 0; think(said, guest);
                });
            });
        } catch (RuntimeException stopped) { end("error", ""); }
    }
    // ── another app's microphone (준: "네이버지도 네비게이션 시작했을때 퍼레음성인식이 안돼", "네비게이션 모드 앱위에서") ──
    /** Whether another app is recording now (this one's ear is closed when it asks): a navigation app's voice commands … */
    private boolean micTaken() {
        try { android.media.AudioManager a = context.getSystemService(android.media.AudioManager.class); return a != null && !a.getActiveRecordingConfigurations().isEmpty(); }
        catch (RuntimeException unknown) { return false; }
    }
    /**
     * The phone's recognizer (another app's process) gets only silence while the app in front records; 퍼레's own ear may still
     * hear, as Android lets an app with an accessibility service (폰 조작) share the microphone. True when it switched.
     */
    private boolean switchEar() {
        if (ownEar || deviceOff || ownBroken || !GroqWork.on()) return false;
        deviceOff = true; earTookOver = android.os.SystemClock.uptimeMillis(); store.phoneLog("● 퍼레 음성: 다른 앱이 마이크를 써서 퍼레의 귀로 들어요");
        show("listening", "다른 앱이 마이크를 쓰는 중이라 내 귀로 들을게");
        main.postDelayed(this::listen, 300); return true;
    }
    private String micWords() {
        return "다른 앱이 마이크를 쓰고 있어서 지금은 못 들어." + (PhoneService.connected() ? "" : " 폰 조작(접근성)을 켜 주면 같이 들을 수 있어.");
    }
    private long micSince;
    /**
     * The microphone is another app's for now: 퍼레 shows why, keeps it with the way to fix it (폰 조작 on: then Android shares the
     * microphone with 퍼레), and tries again every few seconds while the talk may go on. False when it is time to stop.
     */
    private boolean waitForMicrophone(String reason) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTalk >= QUIET_OVER) return false;
        if (switchEar()) return true;
        if (micSince == 0 || now - micSince > QUIET_OVER) {
            micSince = now;
            store.phoneLog("● 퍼레 음성: 마이크를 못 받음 (" + reason + ") · 다른 앱 녹음 " + (micTaken() ? "있음" : "없음") + " · 폰 조작 " + (PhoneService.connected() ? "연결" : "꺼짐"));
            BuddyMisses.add(context, "", "음성 대화", "다른 앱이 마이크를 쓰고 있어서 못 들었어요", PhoneService.connected() ? "" : "accessibility");
        }
        show("listening", micWords());
        int mine = generation;
        main.postDelayed(() -> { if (on && mine == generation) listen(); }, 4000);
        return true;
    }
    // ── music playing over another app, and the words the ears are told (core/VoiceHints) ──
    /**
     * Whether music plays (another app's), and whether it plays while 퍼레 is over another app: then only words with its name,
     * or a few said to the music itself, get through (준: a song's words were answered). Learned when a listening starts,
     * while 퍼레 makes no sound of its own; main thread.
     */
    private boolean music, overMusic;
    /** When 퍼레 last finished saying an answer (uptime ms): just after a question of its own, the reply needs no name. */
    private long saidAt;
    /** How long after 퍼레 asked something the words heard are taken as the reply, music or not. */
    static final long REPLY_MS = 8000;
    /** How music is known (tests put their own). */
    static java.util.function.Predicate<Context> musicCheck = BuddyVoice::musicOn;
    /** Whether music plays now, learned on io while 퍼레 is silent (its own sound would count); the note in the bubble follows. */
    private void checkMusic() {
        if (cue != null || pipe != null || player != null) return;
        java.util.function.Predicate<Context> check = musicCheck;
        try {
            io.execute(() -> {
                boolean m; try { m = check.test(context); } catch (RuntimeException unknown) { m = false; }
                boolean playing = m;
                main.post(() -> {
                    if (!on) return;
                    boolean was = gated(); music = playing; overMusic = playing && screen == null;
                    if (gated() != was && "listening".equals(lastState) && (lastText.isEmpty() || "♪".equals(lastText))) show("listening", idle());
                });
            });
        } catch (RuntimeException stopped) { /* the talk ended */ }
    }
    /** Words without 퍼레's name are let go now: music plays over another app, and nothing waits for a reply. */
    private boolean gated() {
        if (!overMusic || screen != null || confirming != null) return false;
        boolean asked = voiced(lastLine).endsWith("?") && android.os.SystemClock.uptimeMillis() - saidAt < REPLY_MS;
        return !asked;
    }
    /** What a listening shows before words come: a note while the song's words are let go (VoiceHints.musicGate). */
    private String idle() { return gated() ? "♪" : ""; }
    /** What of said gets through (core/VoiceHints.musicGate): all of it, the request after 퍼레's name, or null for a song's words. */
    private String gate(String said) { return gated() ? dev.jun.gyeot.core.VoiceHints.musicGate(said, true) : said; }
    /** A song's words let go: listening goes on, and the talk still ends after its quiet (lastTalk is left as it was). */
    private void passed() {
        store.phoneLog("● 퍼레 음성: 음악 중 이름 없는 말 넘김");
        heardSome = true; quiet(false);
    }
    /** The words both ears lean toward (core/VoiceHints), made on io for what comes after line. */
    private volatile List<String> hints = List.of();
    private void hintsFor(String line) {
        try { io.execute(() -> hints = hints(context, store, line)); } catch (RuntimeException stopped) { /* the talk ended */ }
    }
    /** 퍼레's names, the saved places' names, the apps just used, 퍼레's toys, clothes and plays, the names in line. Off the main thread. */
    static List<String> hints(Context c, AppStore store, String line) {
        List<String> names = new ArrayList<>(List.of("퍼레", "퍼레야", "들퍼레"));
        List<String> places = new ArrayList<>();
        try { for (dev.jun.gyeot.core.SavedPlaces.Saved p : store.savedPlaces()) { places.addAll(p.words()); places.add(p.name()); } } catch (RuntimeException unreadable) {}
        List<String> labels = new ArrayList<>();
        try {
            JSONArray recent = new JSONArray(c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getString("recentLabels", "[]"));
            for (int i = 0; i < recent.length(); i++) labels.add(recent.optString(i));
        } catch (Exception unreadable) {}
        List<String> own = new ArrayList<>();
        for (Map<String, String> things : List.of(BuddyBoard.TOYS, BuddyBoard.PLAYS, BuddyBoard.CLOTHES)) {
            java.util.Set<String> kinds = new java.util.HashSet<>();
            for (Map.Entry<String, String> e : things.entrySet()) if (!e.getValue().isEmpty() && kinds.add(e.getValue()) && kinds.size() <= 10) own.add(e.getKey());
        }
        return dev.jun.gyeot.core.VoiceHints.build(names, places, labels, own, line);
    }
    /** Words heard over a navigation app that are its own guidance, not the user's ("300미터 앞에서 우회전입니다"). */
    static boolean guidance(String said) {
        if (said == null) return false;
        String t = said.replaceAll("\\s+", "");
        if (t.contains("퍼레")) return false;
        return t.matches(".*((\\d+|[일이삼사오육칠팔구십백천]+)(미터|m|킬로|km)(앞|후|전방)|좌회전|우회전|유턴|차선|경로를|안내를시작|안내를종료|목적지|도착했습니다|과속|단속|보호구역|톨게이트|고속도로|진입|출구|교차로|터널|분기점|합류).*");
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
        // Cut short with a thing promised, then nothing said: it is done after all (think() lets it go on a correction).
        if (held != null && !misheard) { doHeld(); return; }
        // Over another app the recognizer may get only silence: before ending, 퍼레's own ear gets one more try.
        if (silences + 1 >= 2 && !heardSome && ownEarCanTakeOver()) { deviceOff = true; main.postDelayed(this::listen, 150); return; }
        if (screen == null && !misheard && micTaken() && switchEar()) return;
        silences++;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTalk >= (screen == null ? QUIET_OVER : QUIET_HOME)) { end("done", BYE_WORDS); return; }
        int mine = generation;
        Runnable again = () -> { if (on && mine == generation) listen(); };
        // Words that were not made out: "응? 다시 말해 줄래?" in its own voice (after the sound, so it does not hear itself).
        // Silence: it waits quietly. A recognizer that gave up at once is not asked again at once.
        if (misheard) { cue("again", true, () -> main.postDelayed(again, 150)); return; }
        main.postDelayed(again, now - listenAt < 1500 ? 1200 : 250);
    }

    // ── 퍼레's short words, at once (VoicePresets) ──
    /**
     * One of 퍼레's short words: its file opened on io and prepared asynchronously (never on the main thread's way), played
     * once. The ones a listening may need next (think, again) are made ahead as it starts (prepareCues), so they start at once.
     * Main thread.
     */
    private final class Cue {
        final File file; final String words;
        private MediaPlayer p; private boolean prepared, playing, over, shows; private Runnable after;
        Cue(File file) {
            this.file = file; this.words = VoicePresets.words(file);
            try { io.execute(this::open); } catch (RuntimeException stopped) { over = true; }
        }
        private void open() {
            MediaPlayer m = new MediaPlayer();
            try { m.setAudioAttributes(Speech.ATTRIBUTES); m.setDataSource(file.getPath()); }
            catch (Exception broken) { m.release(); main.post(this::ended); return; }
            main.post(() -> opened(m));
        }
        private void opened(MediaPlayer m) {
            if (over) { m.release(); return; }
            p = m;
            m.setOnPreparedListener(x -> { prepared = true; if (playing) start(); });
            m.setOnCompletionListener(x -> ended());
            m.setOnErrorListener((x, what, extra) -> { ended(); return true; });
            try { m.prepareAsync(); } catch (RuntimeException broken) { ended(); }
        }
        /** Plays it as soon as it is prepared (shown as 퍼레 speaking when shows), then after. */
        void play(boolean shows, Runnable after) {
            if (over) { if (cue == this) cue = null; if (after != null) after.run(); return; }
            this.shows = shows; this.after = after; playing = true;
            // Not prepared yet: the answer waits a little for it (the player looks at cueEnds again until it has started).
            cueEnds = android.os.SystemClock.uptimeMillis() + 600;
            if (prepared) start();
        }
        private void start() {
            if (over || cue != this) return;
            try { focus(true); p.start(); } catch (RuntimeException broken) { ended(); return; }
            int ms = Math.max(200, p.getDuration());
            cueEnds = android.os.SystemClock.uptimeMillis() + ms;
            if (shows) show("speaking", words, ms);
        }
        /** Said to its end (or it could not be): let go, then after. */
        private void ended() {
            if (over) return;
            boolean was = playing; let();
            if (was) { if (!on && player == null && pipe == null) focus(false); if (after != null) after.run(); }
        }
        /** Let go without after (cut short, or made ahead and not needed). */
        void let() {
            over = true;
            if (cue == this) { cue = null; cueEnds = 0; }
            cues.values().remove(this);
            MediaPlayer m = p; p = null;
            if (m != null) { try { m.stop(); } catch (RuntimeException ignored) {} try { m.release(); } catch (RuntimeException ignored) {} }
        }
    }
    /** The short word playing (or about to), and the ones made ahead by kind. */
    private Cue cue; private volatile long cueEnds;
    private final Map<String, Cue> cues = new java.util.HashMap<>();
    /** The short words a listening may need next, made ahead: "음…" when the user stops, "다시 말해 줄래?" when unclear. */
    private void prepareCues() {
        for (String kind : new String[]{"think", "again"}) {
            if (cues.containsKey(kind)) continue;
            File f = VoicePresets.pick(context, store, kind);
            if (f != null) cues.put(kind, new Cue(f));
        }
    }
    /** A sound while the answer is made, right as the user stops talking (most turns, a different one each time). */
    private void filler(String said) { if (Math.random() < .8) thinkCue(said); }
    /**
     * "음…" while the answer is made: a calm one when the user is down (core/VoiceTone.calm: not "오, 좋아!" to "나 오늘 너무
     * 속상해"), else any (made ahead).
     */
    private void thinkCue(String said) {
        if (said == null || !dev.jun.gyeot.core.VoiceTone.calm(said, java.time.LocalTime.now().getHour())) { cue("think", false, null); return; }
        cued = "think";
        File f = VoicePresets.pick(context, store, "think", true);
        cue(f == null ? null : new Cue(f), false, null);
    }
    /** What the user said last for an answer: how its voice is said (core/VoiceTone). */
    private String toneSaid = "";
    /** The kind of the short word asked for last (tests). */
    String cued = "";
    /**
     * One of 퍼레's short words of this kind (made ahead when it was), shown as it starts when shows; then after (on the main
     * thread; at once when none is made yet). The words, or null when there were none.
     */
    private String cue(String kind, boolean shows, Runnable after) {
        cued = kind;
        Cue c = cues.remove(kind);
        if (c == null) { File f = VoicePresets.pick(context, store, kind); if (f != null) c = new Cue(f); }
        return cue(c, shows, after);
    }
    private String cue(Cue c, boolean shows, Runnable after) {
        if (c == null) { if (after != null) after.run(); return null; }
        stopCue(); cue = c; c.play(shows, after);
        return c.words;
    }
    private void stopCue() { Cue c = cue; cue = null; cueEnds = 0; if (c != null) c.let(); }
    // ── a few words answered on the phone, with no AI and no Typecast (core/VoiceTurns) ──
    /** How long after the user cut 퍼레 short "잠깐" is a wait, not talk. */
    static final long JUST_CUT = 3000;
    /** When the user last spoke over 퍼레 (uptime ms; 0: not in this talk). */
    private long cutAt;
    /** What 퍼레 said last, and its sound when the whole of it came streamed (to say it again just as it was). */
    private String lastLine = ""; private Sound lastSound;
    private record Sound(byte[] pcm, int rate) {}
    /** What said is, of the words 퍼레 answers itself; musicOn: whether music plays (only a bare 그만 asks). */
    private dev.jun.gyeot.core.VoiceTurns.Kind turn(String said, boolean musicOn) {
        boolean cut = cutAt != 0 && android.os.SystemClock.uptimeMillis() - cutAt < JUST_CUT;
        return dev.jun.gyeot.core.VoiceTurns.of(said, cut, confirming != null, musicOn);
    }
    /** Music from another app is playing (its media session, else any media sound but this app's own speech). Off the main thread. */
    static boolean musicOn(Context context) {
        Boolean playing = BuddyTalk.musicPlaying(context);
        if (playing != null) return playing;
        try {
            AudioManager am = context.getSystemService(AudioManager.class);
            if (am != null) for (android.media.AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations())
                if (c.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA) return true;
        } catch (RuntimeException unknown) { /* not known: not playing */ }
        return false;
    }
    /**
     * The words 퍼레 answers itself (준: "음성상호작용을 더 강화해줘"): true when said was one of them. A bare call: "불렀어?" and it
     * listens. An end: a goodbye, the talk ends. 그만 with music playing: the music stops. "뭐라고?": the last answer again, as
     * it sounded. "잠깐" right after the user cut it short: it just listens. A no to an alarm or a timer waiting for a yes.
     */
    private boolean answeredHere(String said, boolean guest) {
        dev.jun.gyeot.core.VoiceTurns.Kind kind = turn(said, false);
        if (kind == dev.jun.gyeot.core.VoiceTurns.Kind.BYE) kind = turn(said, music);
        int mine = generation;
        Runnable listens = () -> main.postDelayed(() -> { if (on && mine == generation) listen(); }, 150);
        switch (kind) {
            case CALL -> { show("thinking", said); cue("hello", true, listens); }
            case BYE -> { show("thinking", said); turns.add(talkTurn("user", said, guest)); end("done", BYE_WORDS); }
            case PAUSE -> {
                if (guest || act == null) return false;
                show("thinking", said);
                try { doNow(new JSONObject().put("tool", "media").put("command", "pause"), said); } catch (org.json.JSONException never) { listen(); }
            }
            case REPEAT -> {
                if (lastLine.isBlank()) return false;
                show("thinking", said); sayAgain();
            }
            case HUSH -> listen();
            case NO -> {
                confirming = null; confirmAsk = ""; show("thinking", said);
                turns.add(talkTurn("user", said, guest)); turns.add(talkTurn("assistant", "알았어, 안 할게.", guest));
                File okay = VoicePresets.line(context, store, "알았어!");
                cue(okay == null ? null : new Cue(okay), true, listens);
            }
            default -> { return false; }
        }
        return true;
    }
    /** The last answer again: its own sound when all of it came streamed, else said anew. */
    private void sayAgain() {
        Sound s = lastSound; String line = lastLine;
        if (s == null) { say(line); return; }
        Pipe p = pipe(); p.again(line, s.pcm(), s.rate()); p.close();
    }
    /** What a talk ended by the user says last (a goodbye in 퍼레's own voice when one is made). */
    static final String BYE_WORDS = "또 불러 줘!";
    /** guest: not the user's voice; the fingerprint, when what was asked needs it, shows it was the user after all. */
    private void think(String said, boolean guest) {
        long now = android.os.SystemClock.uptimeMillis();
        lastTalk = now;
        // A voice not the user's: the talk so far (typed, or kept from the last talk), and what was done in it, are not theirs to go on from.
        if (guest) { learn(); turns.clear(); learnedTo = 0; lastAct = null; meant = null; pendingAsk = null; }
        // A thing promised when the user cut in (talkedOver): a correction or a wait lets it go (the answer is told what it
        // was), a yes does it, other words have it done first and are answered after. A guest's words neither do it nor let
        // it go (a guest never does a thing on the phone): it waits for the user, or the quiet (quiet()).
        org.json.JSONObject h = guest ? null : held; if (!guest) held = null;
        if (h != null && act != null) {
            dev.jun.gyeot.core.VoiceTurns.Kind k = turn(said, false);
            if (k == dev.jun.gyeot.core.VoiceTurns.Kind.NO || k == dev.jun.gyeot.core.VoiceTurns.Kind.HUSH || dev.jun.gyeot.core.VoiceTurns.corrects(said)) {
                meant = h;
                BuddyMisses.add(context, said, BuddyActs.about(h), "하기 전에 고쳐 말해서 안 했어요", "", BuddyMisses.CORRECTED);
                store.phoneLog("● 퍼레 음성: 하려던 일을 고쳐 말해서 안 함 (" + BuddyActs.about(h) + ")");
            } else if (dev.jun.gyeot.core.DeviceActions.affirmative(said)) { show("thinking", said); doNow(h, heldHeard, heldCorrects); return; }
            else { show("thinking", said); doFirst(h, heldHeard, heldCorrects, said, () -> think(said, guest)); return; }
        } else if (lastAct != null && now - lastAct.at() < CORRECT_MS && dev.jun.gyeot.core.VoiceTurns.corrects(said)) {
            // What was just done, said to be the wrong thing ("아니 멜론으로"): kept, and the answer does it again the way said.
            BuddyMisses.add(context, said, lastAct.what(), "방금 한 일을 고쳐 말했어요", "", BuddyMisses.CORRECTED);
        }
        if (answeredHere(said, guest)) return;
        // An alarm or a timer waiting for a yes: this was the answer. Neither a yes nor a no (answeredHere) may correct it
        // ("아니 8시로"): the next answer is told what was asked, and the same thing with the values said is set at once.
        if (confirming != null) {
            org.json.JSONObject waiting = confirming; String asked = confirmAsk; confirming = null; confirmAsk = "";
            if (!guest && act != null && dev.jun.gyeot.core.DeviceActions.affirmative(said)) { show("thinking", said); doNow(waiting, said); return; }
            if (!guest) pendingAsk = new Pending(asked, waiting);
        }
        if (unlock != null && BuddyTalk.needsUnlock(context, said)) {
            show("thinking", said); int mine = generation;
            unlock.ask(() -> { if (on && mine == generation) answer(said, guest && !BuddyTalk.unlocked()); });
            return;
        }
        answer(said, guest);
    }
    /** How many of the user's words went to the brain in this talk (the words 퍼레 answers itself never do). */
    int answers;
    /** How 퍼레's brain is asked for an answer (tests put their own). */
    static java.util.function.BiConsumer<Context, BuddyTalk.Ask> brain = BuddyTalk::reply;
    /** The brain's failures in a row in this talk: the first asks to hear it again, the second ends the talk. */
    private int failures;
    /** Thinking this long after the words with no sound yet: one more "음…" (once a turn). */
    static final long STILL_MS = 3500;
    /** The turn whose answer is awaited (answers' count then; 0: none). */
    private int awaited;
    private void answer(String said, boolean guest) {
        int turn = ++answers; awaited = turn; toneSaid = said == null ? "" : said;
        show("thinking", said);
        int mine = generation;
        List<String[]> before = new ArrayList<>(turns);
        partsUsed = false; answeredAt = 0;
        Pending asked = pendingAsk; String recent = recent(guest); pendingAsk = null;
        // A guest's words never do a thing on the phone. The same thing as the read-back they answered: their values are enough.
        BuddyTalk.Ask ask = new BuddyTalk.Ask(said, before, guest, guest || act == null ? null : a -> main.post(() -> {
                if (on && mine == generation) { todo = a; todoHeard = said; todoCorrects = asked != null && asked.act().optString("tool").equals(a.optString("tool")); }
            }),
            part -> main.post(() -> { if (on && mine == generation) sayPart(part); }),
            line -> main.post(() -> { if (on && mine == generation) answered(said, line, guest); }),
            why -> main.post(() -> { if (on && mine == generation) brainFailed(said, why); }), false, recent);
        java.util.function.BiConsumer<Context, BuddyTalk.Ask> b = brain;
        try { io.execute(() -> b.accept(context, ask)); } catch (RuntimeException stopped) { end("error", ""); return; }
        // Still thinking well after the words (a slow brain, a long tool): one more short sound, so 퍼레 does not go quiet.
        long now = android.os.SystemClock.uptimeMillis(), from = wordsAt != 0 && now - wordsAt < STILL_MS ? wordsAt : now;
        main.postDelayed(() -> {
            if (on && mine == generation && awaited == turn && "thinking".equals(lastState) && cue == null && (pipe == null || !pipe.begun)) thinkCue(said);
        }, from + STILL_MS - now);
    }
    /**
     * The brain's whole answer: into the talk and said. Nothing to say (every sentence was one 퍼레 does not say unasked) and
     * nothing to do: a little sound, and it listens.
     */
    private void answered(String said, String line, boolean guest) {
        failures = 0; awaited = 0;
        if (answeredAt == 0) answeredAt = android.os.SystemClock.uptimeMillis();
        turns.add(talkTurn("user", said, guest));
        if ((line == null || line.isBlank()) && !partsUsed) {
            learnSome();
            org.json.JSONObject now = todo; todo = null;
            if (now != null && act != null) { doNow(now, todoHeard, todoCorrects); return; }
            int mine = generation;
            cue("react", true, () -> main.postDelayed(() -> { if (on && mine == generation) listen(); }, 150));
            return;
        }
        turns.add(talkTurn("assistant", line, guest)); learnSome();
        finish(line);
    }
    /**
     * No brain could answer (why: "Groq HTTP 429", "시간 초과 8초" …): kept in 퍼레가 못 한 일 and the app's log; the first time
     * "다시 말해 줄래?" and it listens, the second time in a row the talk ends.
     */
    private void brainFailed(String said, String why) {
        failures++; awaited = 0;
        String reason = why == null || why.isBlank() ? "답 없음" : why;
        BuddyMisses.add(context, said, "음성 대화 두뇌", reason + " (" + new java.text.SimpleDateFormat("HH:mm", Locale.KOREA).format(new java.util.Date()) + ")", "");
        store.phoneLog("● 퍼레 음성: 두뇌가 답하지 못함 (" + reason + ")" + (failures >= 2 ? " · 두 번째라 끝내요" : " · 다시 들어요"));
        if (failures >= 2) { end("error", "지금은 대답이 안 떠올라. 조금 있다 다시 불러 줘"); return; }
        if (pipe != null) { pipe.close(); return; } // some of it was said after all: the talk goes on after it
        int mine = generation;
        cue("again", true, () -> main.postDelayed(() -> { if (on && mine == generation) listen(); }, 150));
    }
    // ── the answer said as it is written (준: "음성대화 너무 느려"): each sentence fetched while the one before it plays ──
    private boolean partsUsed, saying;
    /** A sentence of the answer, written: into the answer's voice, said in its turn. */
    private void sayPart(String part) {
        partsUsed = true; if (answeredAt == 0) answeredAt = android.os.SystemClock.uptimeMillis();
        pipe().add(part);
    }
    /** The whole answer: said when no sentence came on its own; the talk goes on once the last one is said. */
    private void finish(String line) {
        Pipe p = pipe();
        if (!partsUsed) p.add(line);
        lastSaid(line);
        p.close();
    }
    /** A line on its own (after a thing done), said as an answer of one sentence. */
    private void say(String line) { Pipe p = pipe(); p.add(line); lastSaid(line); p.close(); }
    /** What 퍼레 said last: kept to say again, and its names given to the ears for what comes next (VoiceHints). */
    private void lastSaid(String line) {
        String was = lastLine; lastLine = line == null ? "" : line;
        if (!lastLine.equals(was)) hintsFor(lastLine);
    }
    /** The answer's voice, made with its first sentence (the sound of the one before it is let go: it is not the last any more). */
    private Pipe pipe() { if (pipe == null) { pipe = new Pipe(); saying = true; lastSound = null; } return pipe; }
    /** The answer being said stops: nothing more of it fetched or played, its sound silenced now. */
    private void stopPipe() { Pipe p = pipe; pipe = null; if (p != null) p.stop(); playLevel.clear(); }
    /** How loud 퍼레's voice plays now, and when: its own echo is told from the user's by it (core/BargeIn). */
    private final dev.jun.gyeot.core.PlayLevel playLevel = new dev.jun.gyeot.core.PlayLevel();
    /**
     * The level taken for a sound whose samples this app does not see (the phone's voice, Typecast's whole file): a usual
     * speaking level, so its echo is learned against it as against the streamed one.
     */
    static final double SPOKEN = 4000;
    /** Most of an answer's sound kept to say it again ("뭐라고?"): about half a minute. */
    static final int KEEP_BYTES = 2 * 1024 * 1024;
    /** Quiet this long after a sentence's sound, the next not come yet: 퍼레's mouth rests ("gap"). */
    static final int GAP_MS = 150;
    /** The short breath written between two sentences that follow each other at once. */
    static final int BETWEEN_MS = 120;
    /** How long words take to say when their sound has not told it yet. */
    static int sayMs(String words) { return words == null ? 0 : words.codePointCount(0, words.length()) * 95; }
    /**
     * One answer's voice (core/SayQueue). With Typecast: each sentence's streamed sound asked for on io as soon as the one
     * before it starts playing (one ahead), and one player (gyeot-buddy-say) saying them in order through one AudioTrack that
     * starts at 100 ms of sound, a short breath between them; a sentence whose stream gave nothing is said from Typecast's whole
     * file, else in the phone's voice, in its place. Without Typecast the phone's voice says them one after another. 퍼레 shows
     * each sentence as its sound starts (with how long it lasts, so the bubble types along with it) and rests its mouth when the
     * next one has not come; once the last is said it listens again (spoken()). How loud each piece plays, and when, goes to
     * playLevel (the over-mic tells 퍼레's echo by it), and the answer's sound is kept to say it again as it was ("뭐라고?").
     */
    private final class Pipe {
        final int mine = generation;
        final dev.jun.gyeot.core.SayQueue queue = new dev.jun.gyeot.core.SayQueue();
        final TypecastAccount t = store.typecast; final JSONObject voice = t.voice();
        final boolean typecast = t.connected() && !voice.optString("id").isBlank();
        /**
         * How the whole answer is said (core/VoiceTone), from what the user said and its first sentence: Typecast's emotion
         * and tempo for every sentence, and the phone's voice lower and slower when it is a sad moment (low).
         */
        volatile dev.jun.gyeot.core.VoiceTone.Tone tone; private boolean low;
        /** The player's track, silenced at once by stop() (the player lets it go). */
        volatile android.media.AudioTrack track;
        private Thread player;
        /** The phone's voice: sentences held until the short sound has ended, and how many are not said yet (main thread). */
        private final List<dev.jun.gyeot.core.SayQueue.Clip> held = new ArrayList<>(); private int unsaid; private long heldAt;
        /** Its first sentence has come to be said (its sound, or the phone's voice starting): 퍼레 is not just thinking any more. */
        volatile boolean begun;
        /** The answer's sound as played (the player's alone until said()), at keptRate; whole: every sentence of it streamed and kept. */
        private final java.io.ByteArrayOutputStream kept = new java.io.ByteArrayOutputStream(); private int keptRate; private volatile boolean whole = true;

        boolean live() { return on && mine == generation; }
        /** A sentence: asked for at once when it is its turn (Typecast), or into the phone's voice. Main thread. */
        void add(String line) {
            if (tone == null) {
                int hour = java.time.LocalTime.now().getHour();
                tone = dev.jun.gyeot.core.VoiceTone.of(toneSaid, line, hour, emotions(voice));
                low = "sad".equals(dev.jun.gyeot.core.VoiceTone.of(toneSaid, line, hour, dev.jun.gyeot.core.VoiceTone.ALL).preset());
            }
            dev.jun.gyeot.core.SayQueue.Clip clip = queue.add(line);
            if (!typecast) { phone(clip); return; }
            startPlayer();
            fetchNext();
        }
        /** The answer said before, again from its own sound (nothing fetched). Main thread. */
        void again(String line, byte[] pcm, int rate) {
            dev.jun.gyeot.core.SayQueue.Clip clip = queue.add(line);
            clip.rate = rate; clip.size = pcm.length;
            for (int i = 0; i < pcm.length; i += 8192) queue.arrived(clip, java.util.Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 8192)));
            queue.fetched(clip);
            startPlayer();
        }
        private void startPlayer() { if (player == null) { player = new Thread(this::play, "gyeot-buddy-say"); player.setDaemon(true); player.start(); } }
        /** No more sentences: the player ends after the last one (the phone's voice, after its last). */
        void close() { queue.close(); if (player == null && unsaid == 0) said(); }
        void stop() {
            queue.cancelAll();
            android.media.AudioTrack playing = track; if (playing != null) try { playing.pause(); playing.flush(); } catch (RuntimeException ignored) {}
        }
        /** All said: the mouth rests, then 퍼레 does what it promised or listens again. Main thread. */
        void said() {
            if (!live() || pipe != this) return;
            lastSound = whole && kept.size() > 0 ? new Sound(kept.toByteArray(), keptRate) : null;
            show("gap", ""); spoken();
        }
        /** Some of the answer's sound as it plays, kept to say it again; a sentence said another way, or too much, keeps none. */
        private void keep(byte[] pcm, int length, int rate) {
            if (!whole) return;
            if (keptRate != 0 && keptRate != rate || kept.size() + length > KEEP_BYTES) { whole = false; kept.reset(); return; }
            keptRate = rate; kept.write(pcm, 0, length);
        }
        /** The next sentence's sound asked for, when it is its turn (core/SayQueue.nextToFetch). */
        private void fetchNext() {
            dev.jun.gyeot.core.SayQueue.Clip c = queue.nextToFetch();
            if (c == null) return;
            try { io.execute(() -> fetch(c)); } catch (RuntimeException stopped) { queue.failed(c, dev.jun.gyeot.core.SayQueue.Fallback.PHONE); }
        }
        /** A sentence's sound from Typecast, into its clip as it streams; its whole file when the stream gave nothing (io). */
        private void fetch(dev.jun.gyeot.core.SayQueue.Clip clip) {
            // Shown with its emoji, said without them (준: "이모지도 쓸 수 있게 해줘").
            String words = voiced(clip.text);
            if (words.isEmpty()) { queue.fetched(clip); return; }
            if (tone == null) tone = usual(voice); // a clip not come through add() (none today): 퍼레's usual voice
            if (!t.fits(words.length())) { queue.failed(clip, dev.jun.gyeot.core.SayQueue.Fallback.PHONE); return; }
            if (stream(clip, words) > 0) { queue.fetched(clip); return; }
            if (!live() || queue.cancelled()) return;
            try { clip.whole = synth(context, t, voice, words, "say-" + clip.n + ".mp3", tone); t.count(words.length()); queue.failed(clip, dev.jun.gyeot.core.SayQueue.Fallback.WHOLE); }
            catch (Exception failed) { queue.failed(clip, dev.jun.gyeot.core.SayQueue.Fallback.PHONE); }
        }
        /** Typecast's streamed speech (WAV: a header, then 16-bit mono PCM as it is made) into the clip; the bytes that came. */
        private long stream(dev.jun.gyeot.core.SayQueue.Clip clip, String words) {
            long got = 0;
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", TypecastAccount.API + "/v1/text-to-speech/stream",
                    Map.of("X-API-KEY", t.key(), "Content-Type", "application/json", "Accept", "audio/wav"), request(voice, words, "wav", tone).toString().getBytes(StandardCharsets.UTF_8), 30_000, clip.cancel)) {
                if (response.status >= 300) { OpenAiAccount.Http.read(response.body, 64 * 1024); return 0; }
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(response.body, 16 * 1024));
                Wav wav = wavHeader(in);
                clip.rate = wav.rate(); if (wav.size() > 0 && wav.size() <= wav.rate() * 2L * 60) clip.size = wav.size();
                byte[] buffer = new byte[8192]; int n, carry = 0;
                while ((n = in.read(buffer, carry, buffer.length - carry)) > 0) {
                    if (!live() || queue.cancelled()) return got;
                    int have = carry + n, even = have & ~1;
                    if (even > 0) {
                        if (got == 0) t.count(words.length()); // spent once its sound comes
                        queue.arrived(clip, java.util.Arrays.copyOf(buffer, even)); got += even;
                    }
                    carry = have - even; if (carry == 1) buffer[0] = buffer[even];
                }
                return got;
            } catch (Exception failed) { return got; }
        }
        /** The player: the sentences in order through one track (made again only when the sample rate changes), then said(). */
        private void play() {
            android.media.AudioTrack out = null; int rate = 0; long frames = 0;
            // Since when nothing has sounded after a sentence (0: still sounding), whether the mouth was told to rest, and
            // whether anything of the answer has sounded yet.
            long quietSince = 0; boolean resting = true, first = true, sounded = false;
            try {
                while (live()) {
                    dev.jun.gyeot.core.SayQueue.Clip clip = queue.take(20);
                    if (clip == null) {
                        if (queue.over()) break;
                        // The next one not here yet: once the last one has sounded out, the mouth rests a moment later.
                        if (!resting) {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (quietSince == 0 && (out == null || head(out) >= frames)) quietSince = now;
                            if (quietSince != 0 && now - quietSince >= GAP_MS) { resting = true; main.post(() -> { if (live()) show("gap", ""); }); }
                        }
                        continue;
                    }
                    fetchNext(); // the one after it, while this one plays
                    if (first) {
                        first = false; begun = true;
                        // The short sound first (its "음…" while it thought), the answer right after it; the microphone then
                        // listens for the user over it.
                        long until = android.os.SystemClock.uptimeMillis() + 2500; boolean waited = false;
                        while (live() && cueEnds > android.os.SystemClock.uptimeMillis() && android.os.SystemClock.uptimeMillis() < until) { Thread.sleep(20); waited = true; }
                        if (waited) pause(60);
                        main.post(() -> { if (live()) listenOver(); });
                    }
                    String words = voiced(clip.text);
                    boolean failed = clip.state() == dev.jun.gyeot.core.SayQueue.State.FAILED;
                    if (!failed && clip.bytes > 0 && (out == null || clip.rate != rate)) {
                        if (out != null) { drain(out, frames, rate); release(out); out = null; frames = 0; }
                        try { out = track(clip.rate); rate = clip.rate; track = out; } catch (RuntimeException broken) { failed = true; rate = 0; }
                    }
                    if (failed) {
                        if (out != null) drain(out, frames, rate);
                        whole = false; instead(clip, words); sounded = true;
                        quietSince = android.os.SystemClock.uptimeMillis(); resting = false;
                    } else if (clip.bytes == 0) {
                        // Nothing to say (an emoji alone): shown a moment to read it.
                        if (out != null) drain(out, frames, rate);
                        main.post(() -> { if (live()) show("speaking", clip.text, 900); });
                        pause(900);
                        quietSince = android.os.SystemClock.uptimeMillis() - GAP_MS; resting = false;
                    } else {
                        long now = android.os.SystemClock.uptimeMillis();
                        boolean drained = frames == 0 || head(out) >= frames;
                        long silence = drained ? (quietSince == 0 ? 0 : now - quietSince) + 100 : BETWEEN_MS;
                        // Two sentences one right after the other: a short breath between them.
                        if (!drained) { byte[] breath = new byte[rate * BETWEEN_MS / 1000 * 2]; frames += write(out, breath, frames, rate); keep(breath, breath.length, rate); }
                        boolean sounding = false;
                        while (live()) {
                            byte[] chunk = clip.chunks.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (chunk == null) { if (clip.ended && clip.chunks.isEmpty()) break; continue; }
                            if (!sounding) {
                                sounding = true;
                                if (out.getPlayState() != android.media.AudioTrack.PLAYSTATE_PLAYING) { main.post(() -> { if (live()) focus(true); }); out.play(); }
                                // Shown as its sound starts, after what is still in the track ahead of it; typed over its length.
                                long ahead = Math.max(0, (frames - head(out)) * 1000 / rate), known = clip.ended ? clip.bytes : clip.size;
                                int ms = known > 0 ? (int) (known / 2 * 1000 / rate) : sayMs(words);
                                main.postDelayed(() -> { if (live()) show("speaking", clip.text, ms); }, ahead);
                                timing("타입캐스트"); if (sounded) timing(clip, silence);
                                sounded = true;
                            }
                            long w = write(out, chunk, frames, rate); frames += w;
                            keep(chunk, (int) w * 2, rate);
                        }
                        quietSince = 0; resting = false;
                    }
                    queue.played(clip);
                }
                // The last one sounded to its end, then 퍼레 listens again.
                if (out != null) drain(out, frames, rate);
                if (live()) main.post(this::said);
            } catch (InterruptedException | RuntimeException broken) {
                // A track that broke on the way: what is left is not said, the talk goes on.
                whole = false;
                if (live()) main.post(this::said);
            } finally {
                if (out != null) release(out);
                track = null;
            }
        }
        /** A sentence with no streamed sound, said in its place: from Typecast's whole file, else in the phone's voice. */
        private void instead(dev.jun.gyeot.core.SayQueue.Clip clip, String words) throws InterruptedException {
            java.util.concurrent.CountDownLatch over = new java.util.concurrent.CountDownLatch(1);
            File whole = clip.fallback() == dev.jun.gyeot.core.SayQueue.Fallback.WHOLE ? clip.whole : null;
            main.post(() -> {
                if (!live()) { over.countDown(); return; }
                if (whole != null) playWhole(whole, clip.text, over::countDown);
                else speakPhone(words, low, () -> { if (live()) { phoneSounds(words); show("speaking", clip.text, sayMs(words)); timing("휴대폰 목소리"); } }, over::countDown);
            });
            while (live() && !over.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) { /* said, or the talk moved on */ }
        }
        /** Without Typecast: the phone's voice says the sentences one after another, after the short sound. Main thread. */
        private void phone(dev.jun.gyeot.core.SayQueue.Clip clip) {
            unsaid++; held.add(clip);
            if (held.size() > 1) return; // goes with the one held before it
            heldAt = android.os.SystemClock.uptimeMillis();
            phoneHeld();
        }
        private void phoneHeld() {
            if (!live()) return;
            // The short sound first (it may still be starting), 2.5 s at most.
            long at = android.os.SystemClock.uptimeMillis();
            if (cueEnds > at && at - heldAt < 2500) { main.postDelayed(this::phoneHeld, Math.min(cueEnds - at, 2500 - (at - heldAt)) + 60); return; }
            begun = true;
            listenOver();
            List<dev.jun.gyeot.core.SayQueue.Clip> now = new ArrayList<>(held); held.clear();
            for (dev.jun.gyeot.core.SayQueue.Clip c : now) {
                String words = voiced(c.text);
                speakPhone(words, low, () -> { if (live()) { phoneSounds(words); show("speaking", c.text, sayMs(words)); timing("휴대폰 목소리"); } },
                    () -> { unsaid--; if (unsaid == 0 && queue.closed()) said(); });
            }
        }
        /** Waits ms while the talk goes on. */
        private void pause(long ms) throws InterruptedException {
            long until = android.os.SystemClock.uptimeMillis() + ms;
            while (live() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20);
        }
        /** Until the track has played what was written (or the talk moved on), a second past its time at most. */
        private void drain(android.media.AudioTrack out, long frames, int rate) throws InterruptedException {
            long until = android.os.SystemClock.uptimeMillis() + Math.max(0, frames - head(out)) * 1000 / Math.max(1, rate) + 1000;
            while (live() && head(out) < frames && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20);
        }
        /**
         * Frames written to the track (blocking while it is full); fewer when it was stopped. before: the frames written ahead
         * of these. Each piece's level goes to playLevel with when it will sound: after what is still in the track before it.
         */
        private long write(android.media.AudioTrack out, byte[] pcm, long before, int rate) {
            int done = 0;
            while (done < pcm.length && live()) {
                int w = out.write(pcm, done, Math.min(4096, pcm.length - done)); if (w <= 0) break;
                long to = android.os.SystemClock.uptimeMillis() + Math.max(0, before + (done + w) / 2 - head(out)) * 1000 / rate;
                double level = dev.jun.gyeot.core.BargeIn.rms(java.util.Arrays.copyOfRange(pcm, done, done + w));
                if (level > 0) playLevel.add(to - w / 2 * 1000L / rate, to, level);
                done += w;
            }
            return done / 2;
        }
        private void release(android.media.AudioTrack out) {
            try { out.stop(); } catch (RuntimeException ignored) {}
            out.release(); if (track == out) track = null;
        }
    }
    /** The frames a track has played. */
    private static long head(android.media.AudioTrack out) { return out.getPlaybackHeadPosition() & 0xffffffffL; }
    /**
     * An answer's track: 16-bit mono at rate, half a second deep for smooth playback, starting once 100 ms of it is written
     * (Android's default waits for the whole buffer, so every answer started up to half a second late; Speaker.START_FRAMES).
     */
    private static android.media.AudioTrack track(int rate) {
        int min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        android.media.AudioTrack out = new android.media.AudioTrack.Builder().setAudioAttributes(Speech.ATTRIBUTES)
            .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(min * 4, rate)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build();
        if (out.getState() != android.media.AudioTrack.STATE_INITIALIZED) { out.release(); throw new IllegalStateException("no track"); }
        try { out.setStartThresholdInFrames(rate / 10); } catch (RuntimeException kept) {}
        return out;
    }
    /** A streamed WAV's sample rate and the length its data chunk gives (while it streams often not known: 0 or all ones). */
    private record Wav(int rate, long size) {}
    /** The sample rate and data length from a streamed WAV header (RIFF … fmt … data), the stream left at the first sample. */
    private static Wav wavHeader(java.io.DataInputStream in) throws java.io.IOException {
        byte[] id = new byte[4]; in.readFully(id);
        if (!"RIFF".equals(new String(id, StandardCharsets.US_ASCII))) throw new java.io.IOException("not a wav");
        in.readInt(); in.readFully(id);
        int rate = 32_000;
        for (int chunks = 0; chunks < 16; chunks++) {
            in.readFully(id); String name = new String(id, StandardCharsets.US_ASCII);
            int size = Integer.reverseBytes(in.readInt());
            if ("data".equals(name)) return new Wav(rate, size & 0xffffffffL);
            if ("fmt ".equals(name)) {
                byte[] fmt = new byte[Math.max(16, size)]; in.readFully(fmt, 0, size);
                rate = (fmt[4] & 0xff) | (fmt[5] & 0xff) << 8 | (fmt[6] & 0xff) << 16 | (fmt[7] & 0xff) << 24;
                int channels = (fmt[2] & 0xff) | (fmt[3] & 0xff) << 8, bits = (fmt[14] & 0xff) | (fmt[15] & 0xff) << 8;
                if (channels != 1 || bits != 16) throw new java.io.IOException("unexpected wav");
            } else in.skipNBytes(size & 0xffffffffL);
        }
        throw new java.io.IOException("no data");
    }
    /** The voice's own emotions (Typecast's list for it; none known: plain only). */
    static List<String> emotions(JSONObject voice) {
        JSONArray e = voice == null ? null : voice.optJSONArray("emotions"); List<String> out = new ArrayList<>();
        for (int i = 0; e != null && i < e.length(); i++) out.add(e.optString(i));
        return out;
    }
    /** 퍼레's usual voice: happy when the voice can be, a touch quick (its short words, made once, always say it so). */
    static dev.jun.gyeot.core.VoiceTone.Tone usual(JSONObject voice) { return dev.jun.gyeot.core.VoiceTone.of("", "", 12, emotions(voice)); }
    /** The request for a line in 퍼레's voice: its model, and the tone the moment asks for (core/VoiceTone). */
    static JSONObject request(JSONObject voice, String line, String format, dev.jun.gyeot.core.VoiceTone.Tone tone) throws Exception {
        String model = voice.optString("model", "ssfm-v30");
        JSONObject prompt = new JSONObject().put("emotion_preset", tone.preset()).put("emotion_intensity", tone.intensity());
        if (!"ssfm-v21".equals(model)) prompt.put("emotion_type", "preset");
        return new JSONObject().put("voice_id", voice.optString("id")).put("text", line).put("model", model).put("language", "kor")
            .put("prompt", prompt).put("output", new JSONObject().put("audio_format", format).put("audio_tempo", tone.tempo()));
    }
    /** Typecast's speech for the line, as an mp3 in the cache, in 퍼레's usual voice. */
    static File synth(Context c, TypecastAccount t, JSONObject voice, String line) throws Exception { return synth(c, t, voice, line, "say.mp3", usual(voice)); }
    /** As above, into a file of its own name (two sentences of one answer may each need one), in the answer's tone. */
    static File synth(Context c, TypecastAccount t, JSONObject voice, String line, String name, dev.jun.gyeot.core.VoiceTone.Tone tone) throws Exception {
        JSONObject body = request(voice, line, "mp3", tone);
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", TypecastAccount.API + "/v1/text-to-speech",
                Map.of("X-API-KEY", t.key(), "Content-Type", "application/json", "Accept", "audio/mpeg"), body.toString().getBytes(StandardCharsets.UTF_8), 30_000, null)) {
            if (response.status >= 300) { OpenAiAccount.Http.read(response.body, 64 * 1024); throw new IllegalStateException("Typecast HTTP " + response.status); }
            File dir = new File(c.getCacheDir(), "buddy-voice"); dir.mkdirs();
            File out = new File(dir, name);
            try (java.io.InputStream in = response.body; java.io.OutputStream o = new java.io.FileOutputStream(out)) { in.transferTo(o); }
            return out;
        }
    }
    /**
     * How long each step took, in the app's log (준: "음성대화 너무 느려", "타입캐스트 딜레이가 어마어마한데"): from the end of what
     * was said to its words, to 퍼레's answer, to the first sound (once an answer).
     */
    private volatile long heardAt, wordsAt, answeredAt;
    private void timing(String voice) {
        long now = android.os.SystemClock.uptimeMillis();
        if (heardAt == 0 || answeredAt == 0) return;
        try { store.phoneLog(String.format(java.util.Locale.ROOT, "● 퍼레 음성: 글자로 %.1f초 · 대답 %.1f초 · %s 첫 소리 %.1f초 (모두 %.1f초) · 도구 %d개", (wordsAt - heardAt) / 1000.0, (answeredAt - wordsAt) / 1000.0, voice, (now - answeredAt) / 1000.0, (now - heardAt) / 1000.0, BuddyTalk.toolsSent)); }
        catch (RuntimeException ignored) {}
        heardAt = 0; answeredAt = 0;
    }
    /**
     * A later sentence's own wait: its sound after it was asked for (while the one before it played), and the quiet before it
     * (a breath, under 0.2 s, when it was there in time).
     */
    private void timing(dev.jun.gyeot.core.SayQueue.Clip clip, long silence) {
        if (clip.askedAt == 0 || clip.firstAt == 0) return;
        try { store.phoneLog(String.format(java.util.Locale.ROOT, "● 퍼레 음성: 문장 %d 첫 소리 %.1f초 · 문장 사이 %.1f초", clip.n + 1, (clip.firstAt - clip.askedAt) / 1000.0, silence / 1000.0)); }
        catch (RuntimeException ignored) {}
    }
    /** Typecast's whole file for a sentence, prepared away from the main thread's way, shown as it starts; then after. */
    private void playWhole(File mp3, String text, Runnable after) {
        releasePlayer();
        try {
            MediaPlayer p = new MediaPlayer(); player = p;
            p.setAudioAttributes(Speech.ATTRIBUTES); p.setDataSource(mp3.getPath());
            Runnable finished = () -> { if (player == p) releasePlayer(); after.run(); };
            p.setOnCompletionListener(x -> finished.run());
            p.setOnErrorListener((x, what, extra) -> { finished.run(); return true; });
            p.setOnPreparedListener(x -> {
                if (player != p || !on) { finished.run(); return; }
                focus(true); x.start(); spokenAt(x.getDuration()); show("speaking", text, x.getDuration()); timing("타입캐스트(통째로)");
            });
            p.prepareAsync();
        } catch (Exception failed) { releasePlayer(); after.run(); }
    }
    // ── talking over 퍼레 (barge-in, core/OverEar): it stops, does what it promised, and hears the user from their first word ──
    /** The talk's echo guard: what 퍼레's own voice sounds like in the microphone, kept from answer to answer (core/BargeIn). */
    private volatile dev.jun.gyeot.core.BargeIn barge = new dev.jun.gyeot.core.BargeIn();
    /**
     * The over-mic: one microphone (the call's, with the phone's echo canceller and noise suppressor) from an answer's first
     * sound. hears: Whisper at hand, so a user who talks over 퍼레 is heard by this same microphone (from the second kept
     * before they stopped it), with no gap; listening: the answer said, it hears the next words itself (퍼레's own ear).
     */
    private static final class Over {
        final java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(true);
        final boolean hears; volatile boolean listening; Thread thread;
        Over(boolean hears) { this.hears = hears; }
    }
    private Over over;
    /** While 퍼레 speaks, the microphone waits for the user's voice over it. */
    private void listenOver() {
        if (over != null || !on) return;
        Over o = new Over(GroqWork.on() && !ownBroken); over = o;
        dev.jun.gyeot.core.BargeIn b = barge;
        o.thread = new Thread(() -> overMic(o, b), "gyeot-buddy-over");
        o.thread.setDaemon(true); o.thread.start();
    }
    /**
     * The over-mic's thread: each 20 ms frame set against what 퍼레 plays then (playLevel), so its own voice never stops it.
     * The user over it: 퍼레 stops at once (talkedOver); with Whisper the same microphone goes on to cut what they say. What
     * was said, silence, or nothing comes once, after the microphone is let go (overEnded).
     */
    private void overMic(Over o, dev.jun.gyeot.core.BargeIn b) {
        android.media.AudioRecord mic = null; android.media.audiofx.AcousticEchoCanceler echo = null; android.media.audiofx.NoiseSuppressor noise = null;
        short[] said = null; boolean silent = false, cut = false;
        try {
            int min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            mic = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 6400));
            if (mic.getState() == android.media.AudioRecord.STATE_INITIALIZED) {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) { echo = android.media.audiofx.AcousticEchoCanceler.create(mic.getAudioSessionId()); if (echo != null) echo.setEnabled(true); }
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) { noise = android.media.audiofx.NoiseSuppressor.create(mic.getAudioSessionId()); if (noise != null) noise.setEnabled(true); }
                mic.startRecording();
                dev.jun.gyeot.core.OverEar ear = new dev.jun.gyeot.core.OverEar(b); short[] frame = new short[dev.jun.gyeot.core.OverEar.FRAME];
                boolean began = false;
                while (o.run.get()) {
                    int n = mic.read(frame, 0, frame.length); if (n <= 0) break;
                    // The answer said: the words that come next, from this moment, waited for as long as a listening waits.
                    if (o.listening && !ear.hearing()) ear.listen(7000);
                    dev.jun.gyeot.core.OverEar.Step step = ear.add(frame, n, ear.hearing() ? 0 : playLevel.level(android.os.SystemClock.uptimeMillis()));
                    if (step == dev.jun.gyeot.core.OverEar.Step.OVER) {
                        main.post(() -> { if (over == o) talkedOver(o.hears); });
                        if (!o.hears) break;
                        cut = true; step = ear.hear(4000);
                    }
                    if (step == dev.jun.gyeot.core.OverEar.Step.SPEAKING && !began) { began = true; main.post(() -> { if (over == o && on) { heardNow = true; show("listening", gated() ? "♪" : "…"); } }); }
                    if (step == dev.jun.gyeot.core.OverEar.Step.DONE) { said = ear.pcm(); break; }
                    if (step == dev.jun.gyeot.core.OverEar.Step.SILENT) { silent = true; break; }
                }
            }
        } catch (RuntimeException unavailable) { /* no barge-in this time */ }
        finally {
            if (echo != null) try { echo.release(); } catch (RuntimeException ignored) {}
            if (noise != null) try { noise.release(); } catch (RuntimeException ignored) {}
            if (mic != null) { try { mic.stop(); } catch (RuntimeException ignored) {} mic.release(); }
        }
        short[] heard = said; boolean none = silent, afterCut = cut;
        main.post(() -> overEnded(o, heard, none, afterCut));
    }
    /**
     * The over-mic let go of the microphone by itself: what the user said is heard (Whisper, VoicePrint as from this
     * microphone); silence after the answer is a quiet turn; silence after cutting 퍼레 short, or a microphone that failed
     * while it was to listen, listens again the usual way.
     */
    private void overEnded(Over o, short[] said, boolean silent, boolean afterCut) {
        if (over != o) return; // stopped: the talk moved on
        over = null;
        if (!on) return;
        if (said != null) { heardAt = android.os.SystemClock.uptimeMillis(); if (!gated()) filler(null); hear(said, generation, true); return; }
        if (o.listening && silent && !afterCut) { quiet(false); return; }
        if (afterCut && silent && held != null) { doHeld(); return; } // nothing said after cutting in: the thing promised is done
        if (o.listening || afterCut) listen();
    }
    /** The over-mic stopped last, letting the microphone go. */
    private Thread overLeaving;
    /** The over-mic stops (it lets the microphone go within a frame). */
    private void stopOver() {
        Over o = over; over = null;
        if (o == null) return;
        o.run.set(false); overLeaving = o.thread;
    }
    /** Until the over-mic stopped last has let the microphone go (a frame; 300 ms at most), so the next ear can have it. */
    private void overGone() {
        Thread t = overLeaving; overLeaving = null;
        if (t != null && t.isAlive()) try { t.join(300); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
    }
    /** A sound whose samples this app does not see starts now, for ms: its echo is measured against a usual speaking level. */
    private void spokenAt(long ms) { long now = android.os.SystemClock.uptimeMillis(); playLevel.add(now, now + Math.max(0, ms), SPOKEN); }
    /**
     * A line in the phone's voice starts sounding now: its level goes on until it is said (phoneSaid ends it), as how long
     * the phone takes is not known (a guess that ran short left its last words unmeasured, and their echo could stop 퍼레).
     * A silent line (an emoji alone) plays nothing: no level, so its quiet is not learned as 퍼레's echo.
     */
    private void phoneSounds(String words) {
        if (words == null || words.isBlank()) return;
        long now = android.os.SystemClock.uptimeMillis();
        playLevel.add(now, now + Math.max(4000, sayMs(words) * 3L), SPOKEN);
    }
    /**
     * The user spoke over 퍼레: what it was saying stops (the rest of the answer too), a thing already promised waits for what
     * they say (held: think() does it or lets it go, the quiet does it), and it listens; hearing: the over-mic is already
     * cutting what they say.
     */
    private void talkedOver(boolean hearing) {
        if (!on || !saying) return;
        generation++;
        stopPipe(); releasePlayer(); stopCue();
        if (phone != null) try { phone.stop(); } catch (RuntimeException ignored) {}
        phoneSaying.clear(); phoneWaiting.clear();
        saying = false; focus(false);
        long now = android.os.SystemClock.uptimeMillis();
        lastTalk = now; silences = 0; cutAt = now;
        store.phoneLog("● 퍼레 음성: 말하는 중에 사용자가 말해서 멈추고 들어요");
        // A thing promised is held, not done: what they say may be "아니 멜론으로" (think()); silence does it (quiet()).
        org.json.JSONObject promised = todo; todo = null;
        if (promised != null && act != null) { held = promised; heldHeard = todoHeard; heldCorrects = todoCorrects; }
        // The user is speaking now (they cut in): nothing is said over them (late()) while the over-mic cuts their words.
        if (hearing && over != null) { listenAt = now; heardNow = true; prepareCues(); show("listening", ""); return; }
        stopOver();
        main.postDelayed(this::listen, 150); // the microphone let go first
    }
    /** What is said aloud of a line: its words without emoji or symbols. */
    static String voiced(String line) {
        return line == null ? "" : dev.jun.gyeot.core.BuddyMind.withoutEmoticons(line).replaceAll("[\\p{So}\\p{Cs}\\x{FE0F}\\x{200D}\\x{20E3}]", "").replaceAll("\\s+", " ").strip();
    }
    // ── the phone's own voice, a little higher and quicker, for 퍼레: lines one after another, each told as it starts and ends ──
    private record PhoneLine(String words, boolean low, Runnable started, Runnable finished, int mine) {}
    private final Map<String, PhoneLine> phoneSaying = new java.util.HashMap<>();
    private final List<PhoneLine> phoneWaiting = new ArrayList<>();
    private int phoneIds;
    /**
     * words in the phone's voice after what it says now (QUEUE_ADD); started as its sound starts, finished once it is said (or
     * not). low: a sad moment (core/VoiceTone), said a little lower and slower.
     */
    private void speakPhone(String words, boolean low, Runnable started, Runnable finished) {
        PhoneLine line = new PhoneLine(words == null ? "" : words, low, started, finished, generation);
        if (phone != null && phoneReady) { phoneSay(line); return; }
        phoneWaiting.add(line);
        if (phone != null) return; // being made
        TextToSpeech[] made = new TextToSpeech[1];
        made[0] = new TextToSpeech(context, status -> main.post(() -> {
            if (phone != made[0]) return;
            phoneReady = status == TextToSpeech.SUCCESS && phone.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE;
            if (phoneReady) {
                phone.setPitch(1.35f); phone.setSpeechRate(1.05f);
                phone.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { main.post(() -> { PhoneLine l = phoneSaying.get(id); if (l != null && l.started() != null) l.started().run(); }); }
                    @Override public void onDone(String id) { main.post(() -> phoneSaid(id)); }
                    @Override public void onError(String id) { main.post(() -> phoneSaid(id)); }
                    @Override public void onStop(String id, boolean interrupted) { main.post(() -> phoneSaid(id)); }
                });
            }
            List<PhoneLine> waiting = new ArrayList<>(phoneWaiting); phoneWaiting.clear();
            for (PhoneLine l : waiting) phoneSay(l);
        }));
        phone = made[0];
    }
    private void phoneSay(PhoneLine line) {
        if (!on || line.mine() != generation || phone == null || !phoneReady) { line.finished().run(); return; }
        String id = "buddy-" + (++phoneIds);
        phoneSaying.put(id, line); focus(true);
        // Nothing to say aloud (an emoji alone): a moment of quiet, so it is still shown.
        // Its pitch and rate go with each line as it is queued.
        phone.setPitch(line.low() ? 1.2f : 1.35f); phone.setSpeechRate(line.low() ? 1.0f : 1.05f);
        int queued = line.words().isEmpty() ? phone.playSilentUtterance(900, TextToSpeech.QUEUE_ADD, id) : phone.speak(line.words(), TextToSpeech.QUEUE_ADD, null, id);
        if (queued != TextToSpeech.SUCCESS) { phoneSaying.remove(id); line.finished().run(); }
    }
    /** A line in the phone's voice said (or stopped): what it played ends now (phoneSounds), then finished. */
    private void phoneSaid(String id) {
        PhoneLine l = phoneSaying.remove(id); if (l == null) return;
        playLevel.end(android.os.SystemClock.uptimeMillis());
        l.finished().run();
    }
    /** Said: what was promised is done now, then it listens again (the loop goes on until silence or a tap). */
    private void spoken() {
        saying = false; pipe = null;
        focus(false); releasePlayer();
        if (!on) { stopOver(); return; }
        lastTalk = android.os.SystemClock.uptimeMillis(); saidAt = lastTalk;
        org.json.JSONObject now = todo; todo = null;
        if (now != null && act != null) { stopOver(); doNow(now, todoHeard, todoCorrects); return; }
        // 퍼레's own ear: the microphone that listened over the answer hears the next words from this moment, with no gap.
        Over o = over;
        if (ownEar && o != null && o.hears) { o.listening = true; closeEar(); listenAt = lastTalk; heardNow = false; prepareCues(); checkMusic(); show("listening", idle()); return; }
        stopOver();
        main.postDelayed(this::listen, 350);
    }
    /** A thing to do, off the main thread (a place search may wait on the network), then its words. */
    private void doNow(org.json.JSONObject a, String heard) { doNow(a, heard, false); }
    /** answers: heard answered 퍼레's own read-back (an alarm whose hour it says is set without asking again). */
    private void doNow(org.json.JSONObject a, String heard, boolean answers) {
        int mine = generation; BuddyActs.Late late = late();
        try { io.execute(() -> { Outcome o = act.run(a, heard, answers, late); main.post(() -> { if (on && mine == generation) { did(a, o); done(o); } }); }); }
        catch (RuntimeException stopped) { main.postDelayed(this::listen, 350); }
    }
    /**
     * A thing held from before the user cut in, done before their words are answered (then): what it says goes into the
     * talk for the answer to know (Ask.recent tells it too); one that waits for a yes asks it first, the words kept in the talk.
     */
    private void doFirst(org.json.JSONObject a, String heard, boolean answers, String said, Runnable then) {
        int mine = generation; BuddyActs.Late late = late();
        try {
            io.execute(() -> {
                Outcome o = act.run(a, heard, answers, late);
                main.post(() -> {
                    if (!on || mine != generation) return;
                    did(a, o);
                    if (o != null && o.confirm() != null) { turns.add(talkTurn("user", said, false)); done(o); return; }
                    if (o != null && o.say() != null && !o.say().isBlank()) turns.add(new String[]{"assistant", o.say()});
                    then.run();
                });
            });
        } catch (RuntimeException stopped) { then.run(); }
    }
    /** The thing held from before the user cut in, done now: they said nothing more after all. */
    private void doHeld() { org.json.JSONObject h = held; held = null; if (h != null && act != null) doNow(h, heldHeard, heldCorrects); else listen(); }
    /** After a thing done: its words (a question when it waits for a yes), and the talk ends when another app came up. */
    private void done(Outcome o) {
        lastTalk = android.os.SystemClock.uptimeMillis();
        if (o == null) { main.postDelayed(this::listen, 350); return; }
        if (o.confirm() != null) { confirming = o.confirm(); confirmAsk = o.say() == null ? "" : o.say(); }
        // Another app came up: the talk goes on over it (BuddyOverlay), 퍼레 listening again.
        if (o.leaves()) { if (o.say() != null && !o.say().isBlank()) { say(o.say()); return; } main.postDelayed(this::listen, 2500); return; }
        if (o.say() != null && !o.say().isBlank()) { turns.add(new String[]{"assistant", o.say()}); say(o.say()); return; }
        main.postDelayed(this::listen, 350);
    }
    // ── what was done on the phone, held or asked about, for the next answer to know (준: "퍼레의 판단력 … 강화해줘") ──
    /** A read-back answered with neither a yes nor a no: the question, and the thing it asked about. */
    private record Pending(String question, org.json.JSONObject act) {}
    private Pending pendingAsk;
    /** A thing promised and held because the user cut in before it was done (talkedOver), with what it was for. */
    private org.json.JSONObject held; private String heldHeard = ""; private boolean heldCorrects;
    /** A thing held and then let go (the user corrected it): told to the next answer, once. */
    private org.json.JSONObject meant;
    /** The last thing done on the phone in this talk: what it was (BuddyActs.about), what it said, when (uptime ms). */
    record LastAct(String what, String said, long at) {}
    LastAct lastAct;
    /** How long the last thing done is told to the brain, and how long after it a correction counts as one of it. */
    static final long RECENT_MS = 120_000, CORRECT_MS = 30_000;
    /** Whether any of the user's words have been heard in this listening (then nothing is said over them, late()). */
    private boolean heardNow;
    /** The words that said an app did not come up, and what a tap on them opens. */
    private String lateLine = "", lateGo = "";
    /** A thing done (not one waiting for a yes): kept as the last thing done. */
    private void did(org.json.JSONObject a, Outcome o) {
        if (a == null || o != null && o.confirm() != null) return;
        lastAct = new LastAct(BuddyActs.about(a), o == null || o.say() == null ? "" : voiced(o.say()), android.os.SystemClock.uptimeMillis());
        // What it did for the user, done (not gone wrong), for the talk's learning: a song, a place, a search, a plan.
        String line = o == null || o.missed() ? "" : BuddyActs.line(a, System.currentTimeMillis());
        if (!line.isEmpty()) acts.add(line);
    }
    /**
     * What just happened, for the next answer (Ask.recent), a line each: the last thing done (under RECENT_MS ago), the
     * thing let go when the user cut in, and the read-back they answered with neither a yes nor a no. None for a guest.
     */
    private String recent(boolean guest) {
        if (guest) { meant = null; return ""; }
        List<String> lines = new ArrayList<>();
        long now = android.os.SystemClock.uptimeMillis();
        LastAct l = lastAct;
        if (l != null && now - l.at() < RECENT_MS) lines.add("방금 한 일: " + l.what() + (l.said().isBlank() ? "" : " · " + l.said()) + " (" + ago(now - l.at()) + ")");
        if (meant != null) lines.add("방금 하려던 일: " + BuddyActs.about(meant) + " (사용자가 고쳐 말해서 아직 안 했어)");
        meant = null;
        if (pendingAsk != null) lines.add("퍼레가 방금 ‘" + pendingAsk.question() + "’라고 물었어. 사용자가 고쳐 말하면 고친 값으로 같은 도구를 불러.");
        return String.join("\n", lines);
    }
    private static String ago(long ms) { return ms < 5000 ? "방금" : ms < 60_000 ? ms / 1000 + "초 전" : ms / 60_000 + "분 전"; }
    /** Who hears of an app this talk opened that did not come up (BuddyActs.verify): late(), while the talk is the same one. */
    private BuddyActs.Late late() {
        long since = talkFrom;
        return (what, fix) -> main.post(() -> { if (on && talkFrom == since) late(what, fix); });
    }
    /** When this talk began (uptime ms): a late word from an earlier talk is not said in this one. */
    private long talkFrom;
    /**
     * An app 퍼레 opened did not come up (폰 조작 sees what is in front): said once, only while 퍼레 just listens and nothing
     * of the user's has been heard yet, never on success; the words open the way through when tapped (BuddyMisses.fixIntent).
     */
    private void late(String what, String fix) {
        if (saying || cue != null || awaited != 0 || heardNow || pipe != null || !"listening".equals(lastState)) return;
        String line = "어, ‘" + what + "’ 안 열렸어.";
        store.phoneLog("● 퍼레 음성: " + what + " 안 열려서 말해요");
        generation++; closeEar(); stopOver();
        lateLine = line; lateGo = fix == null || fix.isEmpty() ? "" : "fix:" + fix;
        turns.add(new String[]{"assistant", line});
        say(line);
    }
    // ── what the talk taught (준: "퍼레의 학습기능 … 강화해줘"): learned once from the whole of it, not a request a turn ──
    /** A turn of the talk: {role, text}, with "g" when the voice was a guest's (never learned from). */
    static String[] talkTurn(String role, String text, boolean guest) { return guest ? new String[]{role, text, "g"} : new String[]{role, text}; }
    /** Who learns from a talk (BuddyTalk.learnTalk; tests put their own): its turns, the first not learned yet, what was done. */
    interface Learner { void learn(Context context, List<String[]> turns, int from, List<String> acts); }
    static Learner learner = BuddyTalk::learnTalk;
    /** The turns from this one on are not learned from yet; what was done for the user since then (BuddyActs.line). */
    private int learnedTo; private final List<String> acts = new ArrayList<>();
    /** A long talk learns as it goes too, every this many of the user's turns. */
    static final int LEARN_TURNS = 6;
    /** After the user's turn: a long talk learns what it has so far (LEARN_TURNS of the user's turns not learned yet). */
    private void learnSome() {
        int mine = 0;
        for (int i = Math.min(learnedTo, turns.size()); i < turns.size(); i++) if ("user".equals(turns.get(i)[0]) && !dev.jun.gyeot.core.BuddyMind.guestTurn(turns.get(i))) mine++;
        if (mine >= LEARN_TURNS) learn();
    }
    /**
     * What the talk taught since it last learned, learned now, once (when it ends, every LEARN_TURNS of the user's turns,
     * before a guest's words, and when the service goes): the user's turns and 퍼레's, a guest's left out, and what was done.
     * Nothing when the user said nothing new.
     */
    void learn() {
        int from = Math.min(learnedTo, turns.size());
        boolean mine = false;
        for (int i = from; i < turns.size() && !mine; i++) mine = "user".equals(turns.get(i)[0]) && !dev.jun.gyeot.core.BuddyMind.guestTurn(turns.get(i));
        List<String[]> talk = new ArrayList<>(turns); List<String> done = new ArrayList<>(acts);
        learnedTo = turns.size(); acts.clear();
        if (!mine) return;
        try { learner.learn(context, talk, from, done); } catch (RuntimeException failed) { /* nothing learned this time */ }
    }
    /** The voice service going (BuddyVoiceService.onDestroy, main thread): what the talk taught is not lost with it. */
    static void flushLearning() { BuddyVoice t = current; if (t != null) t.learn(); }
    private void end(String state, String words) {
        learn();
        on = false; generation++; todo = null; confirming = null; confirmAsk = ""; held = null; meant = null; pendingAsk = null; endedAt = android.os.SystemClock.elapsedRealtime(); stopOver(); stopPipe(); saying = false; lastSound = null; lastLine = ""; toneSaid = "";
        for (Cue c : new ArrayList<>(cues.values())) c.let();
        if (store.buddyTalking) { store.buddyTalking = false; store.changed(); }
        Runnable listening = whisper.getAndSet(null); if (listening != null) try { listening.run(); } catch (RuntimeException ignored) {}
        closeEar(); releasePlayer(); focus(false); SpeechInput.hints(null);
        if (phone != null) { try { phone.stop(); phone.shutdown(); } catch (RuntimeException ignored) {} phone = null; phoneReady = false; }
        phoneSaying.clear(); phoneWaiting.clear();
        // After the quiet, a goodbye in its own voice (the bubble shows what it says).
        String bye = "done".equals(state) && BYE_WORDS.equals(words) ? cue("bye", false, null) : null;
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
