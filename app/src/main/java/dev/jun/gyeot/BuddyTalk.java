package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레 on the home screen thinks when it speaks (준: "홈화면 퍼레에게 ai두뇌를 장착해, 앵무새처럼 같은말 반복하는 심심이가 아니게",
 * "미리 만들어 두는 게 아니라 AI를 써", "생각을 하고 말하게", "감성적인 이야기도", "나를 이해한 바탕으로 일상적인 말걸기, 업무적인
 * 것 말고", "유우머도"): each time it is tapped or the user comes back, one line written right then from 퍼레's note about the user,
 * the hour, the weather, the apps just opened, how long they were away and what it already said (a memory of the last forty,
 * never said again). No prices, numbers, work or schedule.
 *
 * NVIDIA NIM first, on a fast model; Groq (GPT-OSS, its free tier) when NIM is slow or fails (준: "엔비디아 nim이 잘 가동 안 될
 * 때 백업으로", "엔비디아 nim이 느릴 때는 groq이 더 좋을 수 있어"): Groq joins after 2.5 s without an answer, and right away while
 * NIM has lately been slow; the first good line wins and the other is stopped. Neither connected: none (the page's own lines).
 */
final class BuddyTalk {
    private BuddyTalk() {}
    private static final String PREFS = "buddyTalk";
    /** NVIDIA's quick model (BuddyPrompt.NIM_MODEL). */
    static final String NIM_MODEL = BuddyPrompt.NIM_MODEL;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile long nimSlowUntil;

    /** One line for now (reason: "tap" or "back"), told to done, or failed when no model could answer. Off the main thread. */
    static void think(Context c, String reason, Consumer<String> done, Runnable failed) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        if (!store.nim.connected() && !GroqWork.on() && !(CUSTOM.equals(brain(c)) && !customLabel(store).isEmpty())) { failed.run(); return; }
        if (!busy.compareAndSet(false, true)) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        thinkOnce(c, store, p, reason, done, failed, 0);
    }
    /** One try: a topic not used lately, the words said too often to avoid; a line that repeats one already said is tried once more. */
    private static void thinkOnce(Context c, AppStore store, SharedPreferences p, String reason, Consumer<String> done, Runnable failed, int attempt) {
        java.util.List<String> said = said(p);
        boolean hasMemory = !memories(c).isEmpty(), hasApp = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getString("recentLabels", "[]").length() > 2;
        JSONObject used; try { used = new JSONObject(p.getString("topics", "{}")); } catch (Exception e) { used = new JSONObject(); }
        // Playing alone (board.js "solo:<play>"): the play is the topic.
        boolean solo = reason.startsWith("solo:") || reason.startsWith("answer:");
        java.util.Random random = new java.util.Random();
        String topic = solo ? "지금 하고 있는 놀이" : dev.jun.gyeot.core.BuddyMind.topic(used, hasMemory, hasApp, random);
        if (!solo) { try { used.put(topic, System.currentTimeMillis()); } catch (Exception ignored) {} p.edit().putString("topics", used.toString()).apply(); }
        // What it does not know about the user it asks (준: "아는것이 전투기 밖에 없어?", "모르면 질문을 하면 되자너"): only when
        // the user taps it, now and then, at most once in three hours (준: "맥락과 무관한 무의미한 질문들이 반복돼": coming back
        // from an app or opening the screen asked too); the answer picked is kept (answer: → learnLater).
        if (reason.equals("tap")) {
            String known = knownWords(c, store);
            JSONObject asked; try { asked = new JSONObject(p.getString("asked", "{}")); } catch (Exception e) { asked = new JSONObject(); }
            String wonder = dev.jun.gyeot.core.BuddyMind.curious(known, asked, System.currentTimeMillis(), random);
            boolean thin = dev.jun.gyeot.core.BuddyMind.knows(known) < 6;
            if (!wonder.isEmpty() && random.nextDouble() < (thin ? .35 : .2)) {
                topic = "사용자를 알아 가는 질문: 아직 모르는 '" + wonder + "'";
                try { asked.put(wonder, System.currentTimeMillis()); } catch (Exception ignored) {}
                p.edit().putString("asked", asked.toString()).apply();
            }
        }
        String instructions = instructions(c, reason, topic, avoid(said)), input = input(c, store, p, reason, topic, said);
        // A question's choices (고르기: …) go along on a second line, for the page to show under it.
        ask(c, instructions, input, attempt == 0 ? 0.75 : 0.9, 9_000, text -> { String l = clean(text, p); String picks = l == null ? "" : picks(l, text); return l == null || picks.isEmpty() ? l : l + "\n고르기: " + picks; },
            line -> {
                String first = line.split("\n")[0]; remember(p, first); busy.set(false); done.accept(line);
                // A choice the user picked: something about them, kept as 퍼레 keeps what it hears.
                if (reason.startsWith("answer:")) { String[] qa = reason.substring(7).split("\u0001", 2); if (qa.length == 2) learnLater(c, "(퍼레가 ‘" + qa[0] + "’라고 묻자) " + qa[1], first); }
            },
            () -> { if (attempt == 0) thinkOnce(c, store, p, reason, done, failed, 1); else { busy.set(false); failed.run(); } });
    }
    /** What not to bring up this time: the subjects said too often lately first, then the words used most in the last lines. */
    static java.util.List<String> avoid(java.util.List<String> said) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(dev.jun.gyeot.core.BuddyMind.staleSubjects(said));
        out.addAll(dev.jun.gyeot.core.BuddyMind.overused(said, 8));
        return new java.util.ArrayList<>(out).subList(0, Math.min(14, out.size()));
    }
    // ── 퍼레's own world and heart (core/BuddyWorld): what it has, what it lived, what it likes and wants ──
    private static final String WORLD = "world";
    private static JSONObject worldState(SharedPreferences p) {
        dev.jun.gyeot.core.BuddyWorld.know(BuddyBoard.CLOTHES, BuddyBoard.PLAYS);
        try { return new JSONObject(p.getString(WORLD, "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    /** Something it lived on the home screen (board.js: a play, a toy, clothes), and how it went. */
    static synchronized void lived(Context c, String kind, String key, double joy, String why) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject w = dev.jun.gyeot.core.BuddyWorld.lived(worldState(p), kind, key, joy, why, System.currentTimeMillis());
        p.edit().putString(WORLD, w.toString()).apply();
    }
    /** Its world and heart in words, for the brain (its favorites may move as it is asked: kept). */
    static synchronized String worldWords(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject w = worldState(p);
        String words = dev.jun.gyeot.core.BuddyWorld.words(w, System.currentTimeMillis());
        p.edit().putString(WORLD, w.toString()).apply();
        return words;
    }
    /** Something it wants and does not have (buddy_wish); false when it has it or wished it already. */
    static synchronized boolean wish(Context c, String thing) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject w = worldState(p);
        boolean added = dev.jun.gyeot.core.BuddyWorld.wish(w, thing, System.currentTimeMillis());
        if (added) p.edit().putString(WORLD, w.toString()).apply();
        return added;
    }
    static synchronized java.util.List<String> wishes(Context c) { return dev.jun.gyeot.core.BuddyWorld.wishes(worldState(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE))); }
    static synchronized void unwish(Context c, String thing) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject w = worldState(p); dev.jun.gyeot.core.BuddyWorld.forget(w, thing); p.edit().putString(WORLD, w.toString()).apply();
    }
    /**
     * Everything 퍼레 knows about the user, as words (the night's profile without the user's work, and its memories), for what
     * it may still ask: a note full of work made it think it knew their games and music.
     */
    static String knownWords(Context c, AppStore store) {
        StringBuilder out = new StringBuilder();
        try { out.append(dev.jun.gyeot.core.BuddyMind.everyday(Briefing.profile(store))).append('\n'); } catch (RuntimeException ignored) {}
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : memories(c)) out.append(e.text()).append('\n');
        return out.toString();
    }
    /** What 퍼레 said lately, oldest first. */
    private static java.util.List<String> said(SharedPreferences p) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try { JSONArray m = new JSONArray(p.getString("memory", "[]")); for (int i = 0; i < m.length(); i++) out.add(m.optString(i)); } catch (Exception ignored) {}
        return out;
    }

    // ── what 퍼레 calls the user (준: "호칭은 내가 설정할 수 있게해줘") ──
    static String title(Context c) { String t = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("title", "").strip(); return t.isEmpty() ? dev.jun.gyeot.core.BuddyMind.TITLE : t; }
    static void title(Context c, String t) { String v = t == null ? "" : t.strip(); if (v.length() > 12) v = v.substring(0, 12); c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("title", v).apply(); }
    // ── 퍼레 keeping quiet a while when asked (the quiet tool): it answers when called, but starts no talk on the home screen ──
    static long hushUntil(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("hushUntil", 0); }
    static boolean hushed(Context c) { return System.currentTimeMillis() < hushUntil(c); }
    /** Quiet for ms from now (0 or less: talking again). */
    static void hush(Context c, long ms) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("hushUntil", ms <= 0 ? 0 : System.currentTimeMillis() + ms).apply(); }
    /** 퍼레 as itself (BuddyMind.persona) with its name and the title the user chose. */
    static String persona(Context c) { String name = "퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {} return dev.jun.gyeot.core.BuddyMind.persona(name, title(c)); }

    // ── the user's voice (준: "내 목소리를 알아볼 수 있게해줘"): VoicePrint's profile, kept on the phone only ──
    static dev.jun.gyeot.core.VoicePrint.Profile voice(Context c) { return dev.jun.gyeot.core.VoicePrint.Profile.read(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("voicePrint", "")); }
    static void voice(Context c, dev.jun.gyeot.core.VoicePrint.Profile p) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (p == null || p.enrolled().isEmpty()) e.remove("voicePrint"); else e.putString("voicePrint", p.json());
        e.apply();
    }

    // ── private memories behind the fingerprint (준: "개인정보 중 중요한 내용은 지문인식을 요청해줘", "갤럭시 지문인식") ──
    private static volatile long unlockedUntil;
    /** The fingerprint passed in the last five minutes: private memories may be said. */
    static boolean unlocked() { return System.currentTimeMillis() < unlockedUntil; }
    static void unlock() { unlockedUntil = System.currentTimeMillis() + 5 * 60_000L; }
    /** What the user said may need private memories, there are some, and the fingerprint has not passed lately. */
    static boolean needsUnlock(Context c, String said) {
        if (unlocked() || !dev.jun.gyeot.core.BuddyMind.asksPersonal(said)) return false;
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : memoryView(c)) if (e.secret()) return true;
        return false;
    }

    // ── 퍼레's brain (준: "퍼레 메세지 약간 느린거 같은데 groq qwen 3.8로 두뇌 바꿔줘", "들퍼레앱에서 설정 가능하게 해줘", "Qwen으로 바꿔줘") ──
    static final String BRAIN = "brain", QWEN = "qwen", NIM = "nim", BOTH = "both", CUSTOM = "custom";
    /** Which brain 퍼레 thinks with: Groq's Qwen (the default, quick), NVIDIA NIM, both at once, or any of the app's AIs (CUSTOM, ScopedModels.buddy). */
    static String brain(Context c) { String b = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BRAIN, QWEN); return NIM.equals(b) || BOTH.equals(b) || CUSTOM.equals(b) ? b : QWEN; }
    /** The AI chosen for 퍼레 (CUSTOM) as "engine · model", or "" when none is connected. */
    static String customLabel(AppStore store) {
        try { ScopedModels m = ScopedModels.buddy(store); String engine = m.engine(); if (engine == null || engine.isBlank() || !store.ready(engine)) return ""; return dev.jun.gyeot.core.WorkBook.author(engine) + " · " + new TeamRoster(store, m).label(engine, m.model(engine)); }
        catch (Exception unavailable) { return ""; }
    }
    /** One answer from the AI chosen for 퍼레 (CUSTOM): its engine, model and depth, no tools. Throws when it is not connected. */
    static String askCustom(AppStore store, String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
        ScopedModels m = ScopedModels.buddy(store); String engine = m.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) throw new IllegalStateException("퍼레 두뇌로 고른 AI가 연결되지 않았어요.");
        String model = m.model(engine), effort = m.effort(engine, model);
        return FileAiOperationsUi.complete(store, FileAiOperationsUi.Connection.capture(store, engine), model, effort, instructions, input, () -> {}, cancel);
    }
    static void brain(Context c, String which) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(BRAIN, which).apply(); }
    /** The newest Qwen the user's Groq key lists (its chat models, not guards, coders or vision), else Qwen3 32B. */
    static String qwen(AppStore store) {
        org.json.JSONArray ids = store.groq.models(); String best = null; double newest = -1;
        java.util.regex.Pattern version = java.util.regex.Pattern.compile("qwen-?(\\d+(?:\\.\\d+)?)");
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i), low = id.toLowerCase(Locale.ROOT);
            if (!low.contains("qwen") || low.matches(".*(guard|coder|vl|embed|tts|whisper).*")) continue;
            String tail = low.substring(low.lastIndexOf('/') + 1);
            java.util.regex.Matcher m = version.matcher(tail);
            double v = 0; if (m.find()) try { v = Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
            if (v > newest) { newest = v; best = id; }
        }
        return best != null ? best : "qwen/qwen3-32b";
    }
    /**
     * One answer from 퍼레's brain (brain()): Groq's Qwen first with NVIDIA NIM after four seconds, NIM first with Qwen after
     * two and a half, or both at once; the first good answer wins and the other is stopped. When one is not connected or
     * fails, the other answers. clean makes what 퍼레 says of the text (null: not usable). Off the main thread.
     */
    static void ask(Context c, String instructions, String input, double temperature, int timeoutMs, java.util.function.Function<String, String> clean, Consumer<String> done, Runnable failed) {
        ask(c, instructions, input, temperature, timeoutMs, clean, done, why -> failed.run());
    }
    /** As above; failed gets why the last brain could not answer (why()). */
    static void ask(Context c, String instructions, String input, double temperature, int timeoutMs, java.util.function.Function<String, String> clean, Consumer<String> done, Consumer<String> failed) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on();
        // The app's AI chosen for 퍼레: it answers; when it cannot, the quick ones below do.
        if (CUSTOM.equals(brain(c)) && !customLabel(store).isEmpty()) {
            new Thread(() -> {
                String reason = "빈 답";
                try { String line = clean.apply(askCustom(store, instructions, input, null)); if (line != null) { done.accept(line); return; } }
                catch (Exception unavailable) { reason = why("고른 AI", unavailable, timeoutMs); /* the quick brains next */ }
                if (nim || groq) quick(c, store, nim, groq, QWEN, instructions, input, temperature, timeoutMs, clean, done, failed); else failed.accept(reason);
            }, "gyeot-buddy-custom").start();
            return;
        }
        if (!nim && !groq) { failed.accept("연결된 두뇌 없음"); return; }
        quick(c, store, nim, groq, brain(c), instructions, input, temperature, timeoutMs, clean, done, failed);
    }
    /**
     * Why a brain could not answer, in a few words for 퍼레가 못 한 일 and the app's log: "Groq HTTP 429", "시간 초과 8초", "빈 답",
     * "NVIDIA 연결 끊김" (who: the brain's name).
     */
    static String why(String who, Throwable e, int timeoutMs) {
        if (e instanceof java.io.InterruptedIOException) return "시간 초과 " + Math.round(timeoutMs / 1000.0) + "초";
        if (e instanceof dev.jun.gyeot.core.ModelLimit limit) return who + " HTTP " + limit.status;
        String m = e == null || e.getMessage() == null ? "" : e.getMessage();
        java.util.regex.Matcher http = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(m);
        if (http.find()) return who + " HTTP " + http.group(1);
        if (m.contains("빈 답") || m.contains("내용 없는") || m.contains("답이 없")) return "빈 답";
        if (e instanceof java.io.IOException) return who + " 연결 끊김";
        if (m.isBlank()) return who + " " + (e == null ? "오류" : e.getClass().getSimpleName());
        return m.startsWith(who) ? cut(m, 40) : who + " " + cut(m, 40);
    }
    /** Groq's Qwen and NVIDIA NIM, raced as the brain says (QWEN, NIM or BOTH); failed gets why the last one could not answer. */
    private static void quick(Context c, AppStore store, boolean nim, boolean groq, String brain, String instructions, String input, double temperature, int timeoutMs, java.util.function.Function<String, String> clean, Consumer<String> done, Consumer<String> failed) {
        AtomicBoolean answered = new AtomicBoolean();
        AtomicReference<String> reason = new AtomicReference<>("빈 답");
        AtomicReference<Runnable> stopNim = new AtomicReference<>(), stopGroq = new AtomicReference<>();
        Object lock = new Object(); int[] running = {0}; boolean[] tried = {false, false};
        Runnable[] start = new Runnable[2];
        Consumer<String> win = line -> {
            if (!answered.compareAndSet(false, true)) return;
            Runnable a = stopNim.get(), b = stopGroq.get();
            if (a != null) try { a.run(); } catch (RuntimeException ignored) {}
            if (b != null) try { b.run(); } catch (RuntimeException ignored) {}
            done.accept(line);
        };
        Runnable lost = () -> {
            Runnable next = null;
            synchronized (lock) {
                running[0]--;
                if (answered.get() || running[0] > 0) return;
                if (groq && !tried[0]) next = start[0];
                else if (nim && !tried[1]) next = start[1];
                else if (answered.compareAndSet(false, true)) next = () -> failed.accept(reason.get());
            }
            if (next != null) next.run();
        };
        JSONArray messages = new JSONArray();
        try { messages.put(new JSONObject().put("role", "user").put("content", input)); } catch (Exception ignored) {}
        start[0] = () -> {
            synchronized (lock) { if (answered.get() || tried[0]) return; tried[0] = true; running[0]++; }
            new Thread(() -> {
                String model = qwen(store);
                try {
                    String text;
                    // Qwen's thinking off for speed; a model that refuses the setting is asked again without it.
                    try { text = GroqWork.converse(model, instructions, messages, "none", temperature, timeoutMs, stopGroq); }
                    catch (Exception first) { if (answered.get()) throw first; text = GroqWork.converse(model, instructions, messages, null, temperature, timeoutMs, stopGroq); }
                    String line = clean.apply(text); if (line != null) win.accept(line); else { reason.set("빈 답"); lost.run(); }
                } catch (Exception e) { reason.set(why("Groq", e, timeoutMs)); lost.run(); }
            }, "gyeot-buddy-qwen").start();
        };
        start[1] = () -> {
            synchronized (lock) { if (answered.get() || tried[1]) return; tried[1] = true; running[0]++; }
            new Thread(() -> {
                long begun = System.currentTimeMillis();
                try {
                    WorkClient.Result r = NimClient.complete(store.nim, store.nim.generation(), NIM_MODEL, instructions, input, () -> { if (answered.get()) throw new java.util.concurrent.CancellationException(); }, stopNim);
                    if (System.currentTimeMillis() - begun > 4_000) nimSlowUntil = System.currentTimeMillis() + 10 * 60_000L;
                    String line = clean.apply(r.text); if (line != null) win.accept(line); else { reason.set("빈 답"); lost.run(); }
                } catch (Exception e) { nimSlowUntil = System.currentTimeMillis() + 5 * 60_000L; reason.set(why("NVIDIA", e, timeoutMs)); lost.run(); }
            }, "gyeot-buddy-nim").start();
        };
        android.os.Handler later = new android.os.Handler(android.os.Looper.getMainLooper());
        switch (brain) {
            case NIM -> {
                if (nim && !(groq && System.currentTimeMillis() < nimSlowUntil)) { start[1].run(); if (groq) later.postDelayed(() -> { if (!answered.get()) start[0].run(); }, 2_500); }
                else start[0].run();
            }
            case BOTH -> { if (groq) start[0].run(); if (nim) start[1].run(); }
            default -> { if (groq) { start[0].run(); if (nim) later.postDelayed(() -> { if (!answered.get()) start[1].run(); }, 4_000); } else start[1].run(); }
        }
    }

    /**
     * 퍼레's spoken answer in its voice talk (BuddyVoice): what the user just said, with the talk so far (turns: {role, text}),
     * answered as 퍼레 in one or two short spoken sentences. NIM's quick model and Groq race as for its lines; the first good
     * answer wins. Off the main thread.
     */
    static void reply(Context c, String said, java.util.List<String[]> turns, Consumer<String> done, Runnable failed) { reply(c, said, turns, false, null, done, failed); }

    // ── doing things on the phone (준: "퍼레는 폰조작이 안되네": it said "네이버 지도 바로 열어볼게" and a link, and opened nothing) ──
    /** What 퍼레 may do on the phone, as the model writes it: a second line "할일: {json}" after its words (BuddyPrompt.ACTS). */
    static final String ACTS = BuddyPrompt.ACTS;
    // ── 퍼레와 대화, typed on the home screen (HomeActivity.chat) ──
    /**
     * 퍼레와 대화's instructions: 퍼레 as itself (persona), the things to do in the text form (ACTS), the phone's state, the day,
     * what it knows and remembers now; the assistant's note about the user without their work unless the question is about
     * it (BuddyMind.everyday), as a reference not to bring up first.
     */
    static String chatInstructions(Context c, AppStore store, String question) {
        String about = "";
        try { String all = Briefing.profile(store); about = dev.jun.gyeot.core.BuddyMind.asksWork(question) ? all : dev.jun.gyeot.core.BuddyMind.everyday(all); } catch (RuntimeException ignored) {}
        String now = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date());
        return BuddyPrompt.chatRules(persona(c), now) + corrected(c, question) + ACTS + places(store)
            + BuddyPrompt.CHAT_PHONE
            + "휴대폰 상태: " + facts(c) + "\n" + todayWords(c) + deliveryWords(c) + memoryWords(c, question, true)
            + worldWords(c)
            + (about.isBlank() ? "" : "참고(비서 노트, 먼저 꺼내지 마):\n" + about);
    }
    /** Which brain wrote the last answer of 퍼레와 대화 (chatText), for 퍼레 자동 점검's report. */
    static volatile String chatBy = "";
    /**
     * 퍼레와 대화's written answer, its thinking taken out (HomeActivity.chat, and 퍼레 자동 점검 the very same way): the AI
     * chosen for 퍼레 (CUSTOM), else Groq's Qwen unless the user chose NVIDIA, else NVIDIA NIM streamed (onDelta gets its
     * pieces). current: false once a newer question came (then CancellationException). Throws why no brain could answer.
     * Off the main thread.
     */
    static String chatText(Context c, String question, String history, AtomicReference<Runnable> cancel, java.util.function.BooleanSupplier current, Consumer<String> onDelta) throws Exception {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean custom = CUSTOM.equals(brain(c)) && !customLabel(store).isEmpty();
        boolean qwen = GroqWork.on() && !NIM.equals(brain(c));
        String in = BuddyPrompt.chatInput(history, question.strip());
        if (custom) {
            // The AI the user chose for 퍼레 (화면 꾸미기 → 퍼레 두뇌); when it cannot answer, Qwen or NVIDIA below.
            try {
                String text = askCustom(store, chatInstructions(c, store, question), in, cancel);
                chatBy = "고른 AI · " + customLabel(store);
                return text.replaceAll("(?s)<think>.*?</think>", "").strip();
            } catch (java.util.concurrent.CancellationException stopped) { throw stopped; }
            catch (Exception unavailable) { if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException(); if (!qwen && !store.nim.connected()) throw unavailable; }
        }
        if (qwen) {
            String model = qwen(store), text;
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", in));
            try { text = GroqWork.converse(model, chatInstructions(c, store, question), messages, "none", 0.7, 30_000, cancel); }
            catch (Exception first) { if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException(); text = GroqWork.converse(model, chatInstructions(c, store, question), messages, null, 0.7, 30_000, cancel); }
            chatBy = "Groq · " + model;
            return text.replaceAll("(?s)<think>.*?</think>", "").strip();
        }
        WorkClient.Result answer = NimClient.complete(store.nim, store.nim.generation(), store.nim.model(), chatInstructions(c, store, question), in,
            () -> { if (!current.getAsBoolean()) throw new java.util.concurrent.CancellationException(); }, cancel, onDelta == null ? text -> {} : onDelta);
        chatBy = "NVIDIA · " + store.nim.model();
        return answer.text == null ? "" : answer.text.strip();
    }
    /** The saved places (내비게이션 → 자주 가는 곳): their words go as the destination as said, "집으로 가자" → {"tool":"navigate","destination":"집"}. */
    static String places(AppStore store) {
        java.util.List<dev.jun.gyeot.core.SavedPlaces.Saved> saved = store.savedPlaces(); if (saved.isEmpty()) return "";
        StringBuilder out = new StringBuilder("- 사용자가 저장해 둔 자주 가는 곳이야. 이 말로 길을 부탁하면 묻지 말고 그 부르는 말만 그대로 destination으로 써(괄호 속 이름·주소는 쓰지 마, 예: \"destination\":\"집\"):");
        for (dev.jun.gyeot.core.SavedPlaces.Saved one : saved) out.append(" ").append(String.join("/", one.words())).append("(").append(one.name().isEmpty() ? one.address() : one.name()).append(")");
        return out.append("\n").toString();
    }
    /** The thing to do the answer names ("할일: {…}", or a bare {…} with a tool), its tool one of ACTS', or null (BuddyPrompt.act). */
    static JSONObject act(String text) { return BuddyPrompt.act(text); }
    /** One {…}: JSON, else its key: value pairs as written; null when it names no tool (BuddyPrompt.loose). */
    static JSONObject loose(String braces) { return BuddyPrompt.loose(braces); }
    /** What was heard with 퍼레's name put back where the recognizer took it for 벌레, 버려 … (only while its name is 퍼레). */
    static String heard(Context c, String said) {
        if (said == null || said.isBlank()) return said;
        String name = "퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {}
        return name == null || name.isBlank() || name.contains("퍼레") ? dev.jun.gyeot.core.BuddyMind.nameHeard(said) : said;
    }
    /**
     * One turn of the voice talk for the brain (reply): what was said; the talk so far ({role, text}, "g" third for a guest's;
     * from this talk, the last one or the typed 퍼레와 대화); guest: the voice is not the user's (VoicePrint, 준: "내 목소리를 알아볼 수 있게해줘"), so 퍼레
     * answers kindly, keeps the user's memories, day and profile to itself, does nothing on the phone and learns nothing;
     * doIt: the thing to do the answer names (null: nothing is done); part: each sentence as soon as it is written (null: the
     * whole only); done: the whole answer ("" when every sentence was one 퍼레 does not say unasked, BuddyMind.unasked);
     * failed: why no brain could answer, in a few words (why()); learnNow: what the user said is learned from this answer
     * alone (the voice talk passes false: it learns once from the whole talk, BuddyVoice.learn);
     * recent: what just happened, for the brain to know, a line each with its own words ("방금 한 일: play · ‘아이유 좋은날’ ·
     * YouTube (10초 전)", "방금 하려던 일: …", "퍼레가 방금 ‘…맞출까?’라고 물었어. …"; "" for nothing).
     */
    record Ask(String said, java.util.List<String[]> turns, boolean guest, Consumer<JSONObject> doIt, Consumer<String> part, Consumer<String> done,
               Consumer<String> failed, boolean learnNow, String recent, boolean dry) {
        Ask(String said, java.util.List<String[]> turns, boolean guest, Consumer<JSONObject> doIt, Consumer<String> part, Consumer<String> done,
            Consumer<String> failed, boolean learnNow, String recent) { this(said, turns, guest, doIt, part, done, failed, learnNow, recent, false); }
    }
    /*
     * dry: 퍼레 자동 점검 (BuddyCheckUi) asking the brain as the voice talk does, to see which thing it would do: doIt only
     * records it (nothing is opened, set, sent or said), nothing is learned and no miss is kept for it.
     */
    /** Which brain answered the last voice turn ("Groq · openai/gpt-oss-120b", "글 형식"), for 퍼레 자동 점검's report. */
    static volatile String answeredBy = "";
    static void reply(Context c, String said, java.util.List<String[]> turns, boolean guest, Consumer<JSONObject> doIt, Consumer<String> done, Runnable failed) {
        reply(c, said, turns, guest, doIt, null, done, failed);
    }
    static void reply(Context c, String said, java.util.List<String[]> turns, boolean guest, Consumer<JSONObject> doIt, Consumer<String> part, Consumer<String> done, Runnable failed) {
        reply(c, new Ask(said, turns, guest, doIt, part, done, why -> failed.run(), true, ""));
    }
    /**
     * The answer to a turn of the voice talk, each sentence as soon as it is written (준: "음성대화 너무 느려", the brain streams),
     * so 퍼레 starts speaking before the answer is done; done still gets the whole. With Groq, the things to do are tools it
     * calls (BuddyTools, 준: "{tool:openapp…}를 외쳤다"); without, or when that fails before a word was said, the "할일:" text
     * form. A guest's answer streams too, with no tools. Sentences 퍼레 does not say unasked (cheering, rest, chores, coins:
     * BuddyMind.unasked) are left out, aloud and in done alike.
     */
    static void reply(Context c, Ask ask) {
        // "<앱> 꺼 줘" is done at once, with no brain (준, 2026-11-27 over 네이버 지도: the brain said "네이버지도 닫을게 👋" and
        // called nothing): the app as named, when an app by that name is here, or the one on screen ("이거 꺼 줘").
        String closing = ask.guest() || ask.doIt() == null ? null : dev.jun.gyeot.core.VoiceTurns.closing(ask.said());
        if (closing == null) { withBrain(c, ask); return; }
        new Thread(() -> {
            if (!closing.isEmpty() && BuddyActs.findApp(c, closing) == null) { withBrain(c, ask); return; }
            JSONObject act;
            try { act = loose(new JSONObject().put("tool", "close_app").put("app", closing).toString()); } catch (org.json.JSONException e) { act = null; }
            if (act == null) { withBrain(c, ask); return; }
            ask.doIt().accept(act);
            ask.done().accept(BuddyTools.say(act));
        }, "gyeot-buddy-close").start();
    }
    private static void withBrain(Context c, Ask ask) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        String said = ask.said(); java.util.List<String[]> turns = ask.turns() == null ? java.util.List.of() : ask.turns();
        boolean guest = ask.guest(), learn = ask.learnNow() && !guest;
        // A guest's words never do a thing on the phone.
        Consumer<JSONObject> doIt = guest ? null : ask.doIt();
        Consumer<String> done = ask.done(), failed = ask.failed();
        String instructions = BuddyPrompt.voiceRules(persona(c), title(c), guest)
            // The user setting right what 퍼레 knew (not a thing just done: "아니 멜론으로" is about that, Ask.recent).
            + (guest || ask.recent() != null && !ask.recent().isBlank() ? "" : corrected(c, said))
            + worldWords(c);
        // 퍼레's own tools only when the words are about 퍼레, now or in the last exchange ("고양이 귀 써 볼까?" → "응"): most turns weigh less.
        boolean self = BuddyPrompt.self(said, turns);
        JSONArray tools = doIt == null ? null : self ? BuddyTools.TOOLS : BuddyTools.PHONE;
        String withText = instructions + (doIt == null ? "" : ACTS + places(store)), withTools = doIt == null ? instructions : instructions + BuddyTools.rule(self) + places(store);
        String input = replyInput(c, said, turns, guest, ask.recent());
        AtomicReference<JSONObject> todo = new AtomicReference<>();
        Runnable asText = () -> { answeredBy = "글 형식(도구 없이)"; speedy(c, withText, input, 0.7, text -> {
            todo.set(doIt == null ? null : act(text));
            String words = spoken(text);
            return words == null ? null : sayable(words, said);
        }, line -> {
            String l = line.isBlank() && todo.get() != null ? BuddyTools.say(todo.get()) : line;
            if (learn && todo.get() == null && !l.isBlank()) learnLater(c, said, l);
            if (doIt != null && todo.get() != null) doIt.accept(todo.get());
            done.accept(l);
        }, failed); };
        boolean nimOn = store.nim.connected();
        if (!GroqWork.on() && !nimOn) { toolsSent = 0; asText.run(); return; }
        toolsSent = tools == null ? 0 : tools.length();
        new Thread(() -> {
            Sentences sentences = new Sentences(ask.part(), said);
            String why = "빈 답";
            try {
                // The ways to ask, quickest first: Groq (the chosen model or GPT OSS at its lightest), then NVIDIA's quick model.
                java.util.List<String[]> ways = new java.util.ArrayList<>();
                if (GroqWork.on()) {
                    ScopedModels m = ScopedModels.buddy(store);
                    boolean mine = CUSTOM.equals(brain(c)) && dev.jun.gyeot.core.WorkBook.GROQ.equals(m.engine());
                    String model = mine ? m.model(m.engine()) : GroqWork.CHORES, effort = mine ? m.effort(m.engine(), model) : "low";
                    ways.add(new String[]{GroqAccount.API + "chat/completions", GroqWork.key(), model, store.groqModels.profile(model).normalize(effort).isEmpty() ? null : effort, "Groq"});
                }
                if (nimOn) ways.add(new String[]{dev.jun.gyeot.core.NimProtocol.ENDPOINT, dev.jun.gyeot.core.NimProtocol.key(store.secrets.get(SecretStore.NIM)), NIM_MODEL, null, "NVIDIA"});
                JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", input));
                GroqWork.Turn t = null; String[] used = null;
                for (String[] w : ways) {
                    try { t = GroqWork.stream(w[0], w[1], w[2], withTools, messages, w[3], 0.7, tools, "auto", 9_000, sentences::add, null); used = w; answeredBy = w[4] + " · " + w[2]; break; }
                    catch (Exception next) { why = why(w[4], next, 9_000); if (!sentences.said().isEmpty()) break; }
                }
                if (t == null) throw new IllegalStateException(why);
                sentences.end();
                JSONObject a = doIt == null ? null : BuddyTools.act(t.calls()); if (a == null && doIt != null) a = act(t.text());
                // It said it would do a thing but called no tool ("3분 타이머 시작할게!"): asked once more, a tool this time, all of
                // them (퍼레's own too: the words may have been about it after all), or NOTHING when there was nothing to do ("내일
                // 일정 뭐야" is a question). Only a retry that gave no answer is a miss.
                if (doIt != null && a == null && BuddyTools.promised(said, t.text())) {
                    boolean none = false;
                    try {
                        JSONArray calls = GroqWork.stream(used[0], used[1], used[2], instructions + BuddyTools.rule(true) + places(store), messages, used[3], 0.2, BuddyTools.orNothing(), "required", 6_000, null, null).calls();
                        none = BuddyTools.nothing(calls); a = BuddyTools.act(calls);
                    } catch (Exception refused) { /* the words alone */ }
                    if (a == null && !none && !ask.dry()) BuddyMisses.add(c, said, "할 일 도구", "말로만 하겠다고 하고 도구를 부르지 않았어요", "", BuddyMisses.PROMISED);
                }
                String words = spoken(t.text());
                if ((words == null || words.isBlank()) && a == null) { why = "빈 답"; throw new IllegalStateException(why); }
                // What was said aloud (the same sentences Sentences gave), or the thing's own words when every sentence was left out.
                String line = words == null ? "" : sayable(words, said);
                if (line.isBlank() && a != null) line = BuddyTools.say(a);
                if (a == null) { if (learn && !line.isBlank()) learnLater(c, said, line); }
                else doIt.accept(a);
                done.accept(line == null ? "" : line);
            } catch (Exception failedWithTools) {
                // Words already said: those are the answer; else the text form.
                if (sentences.said().isEmpty()) asText.run(); else done.accept(sentences.said());
            }
        }, "gyeot-buddy-tools").start();
    }
    /**
     * What the brain reads for a turn of the voice talk: the hour, 퍼레's life, and (not for a guest) the phone's state, the
     * day, its memories and the assistant's note about the user, without their work unless they asked about it
     * (BuddyMind.everyday: 퍼레 kept bringing up the user's projects unasked), labelled as a reference not to bring up first;
     * then what was done lately, the talk so far and what was said.
     */
    static String replyInput(Context c, String said, java.util.List<String[]> turns, boolean guest, String recent) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
        String note = "";
        if (!guest) try { String all = Briefing.profile(store); note = dev.jun.gyeot.core.BuddyMind.asksWork(said) ? all : dev.jun.gyeot.core.BuddyMind.everyday(all); } catch (RuntimeException ignored) {}
        String house = said.matches("(?s).*(배터리|충전|밥|음악|노래|와이파이|인터넷|데이터|절전|뜨거|방해|비행기 모드|무음).*") ? home(c) : "";
        String context = "지금: " + when + "\n" + (house.isEmpty() ? "" : "휴대폰(너의 집): " + house + "\n") + lifeWords(c) + "\n"
            + (guest ? "" : "휴대폰 상태(물을 때만 알려 줄 것): " + facts(c) + "\n" + todayWords(c) + deliveryWords(c) + memoryWords(c, said, true)
                + (note.isBlank() ? "" : "참고(비서 노트, 먼저 꺼내지 마):\n" + note + "\n")) + "\n";
        // The talk so far, its last lines each cut short: a long talk does not grow every request (Groq's free tier counts tokens a minute).
        return context + BuddyPrompt.voiceTail(recent, turns, guest, said);
    }
    /** How much of the talk so far goes with each answer: its last lines, each this long at most. */
    static final int TALK_TURNS = BuddyPrompt.TALK_TURNS, TALK_LINE = BuddyPrompt.TALK_LINE;
    /** How many tools went with the last answer (0: the text form), for the voice talk's timing line. */
    static volatile int toolsSent;
    private static String cut(String line, int most) {
        if (line == null) return "";
        return line.codePointCount(0, line.length()) <= most ? line : line.substring(0, line.offsetByCodePoints(0, most)) + "…";
    }
    /** What of a spoken answer 퍼레 says to what was heard: its sentences as Sentences cuts them, without those it does not say unasked. */
    static String sayable(String text, String heard) {
        if (text == null || text.isBlank()) return "";
        Sentences s = new Sentences(piece -> {}, heard); s.add(text); s.end();
        return s.said();
    }
    /**
     * An answer cut into sentences as it streams, each said (spoken: no marks, links or act text) when it is whole; a sentence
     * 퍼레 does not say to what was heard (BuddyMind.unasked: "푹 쉬어" to "오늘 뭐 할까") is left out whole.
     */
    static final class Sentences {
        private final Consumer<String> out; private final String heard; private final StringBuilder buf = new StringBuilder(), all = new StringBuilder();
        Sentences(Consumer<String> out) { this(out, ""); }
        Sentences(Consumer<String> out, String heard) { this.out = out; this.heard = heard == null ? "" : heard; }
        void add(String piece) { if (out == null || piece == null) return; buf.append(piece); emit(false); }
        void end() { if (out != null) emit(true); }
        /** What was said so far. */
        String said() { return all.toString().strip(); }
        private void emit(boolean last) {
            for (int from = 0; ; ) {
                int cut = -1;
                for (int i = from; i < buf.length(); i++) {
                    char ch = buf.charAt(i);
                    if (ch == '\n' || ".!?~…".indexOf(ch) >= 0 && i + 1 < buf.length() && Character.isWhitespace(buf.charAt(i + 1))) { cut = i + 1; break; }
                }
                if (cut < 0) { if (last && buf.length() > 0) { give(buf.toString()); buf.setLength(0); } return; }
                // A sentence of a word or two waits for the next (one sound request, not two tiny ones).
                if (buf.substring(0, cut).strip().codePointCount(0, buf.substring(0, cut).strip().length()) < 6 && !last) { from = cut; continue; }
                String s = buf.substring(0, cut); buf.delete(0, cut); from = 0; give(s);
            }
        }
        private void give(String s) {
            if (s.contains("{") || s.strip().startsWith("할일") || s.strip().startsWith("할 일")) return;
            String clean = spoken(s); if (clean == null || clean.isBlank()) return;
            if (dev.jun.gyeot.core.BuddyMind.unasked(clean, heard)) return;
            if (all.length() > 0) all.append(' ');
            all.append(clean); out.accept(clean);
        }
    }
    /**
     * The voice talk's brain, quick above all (준: "음성대화 너무 느려"): the AI chosen for 퍼레 when it is Groq's (it answers in
     * well under a second); otherwise GPT OSS 120B on Groq at its lightest thinking, then Qwen and NVIDIA as for its lines.
     * A slow chosen AI (Antigravity, Claude …) is left to the written talk. failed gets why the last one could not answer.
     */
    static void speedy(Context c, String instructions, String input, double temperature, java.util.function.Function<String, String> clean, Consumer<String> done, Consumer<String> failed) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on();
        if (!nim && !groq) { ask(c, instructions, input, temperature, 12_000, clean, done, failed); return; }
        new Thread(() -> {
            if (groq) try {
                ScopedModels m = ScopedModels.buddy(store);
                boolean mine = CUSTOM.equals(brain(c)) && dev.jun.gyeot.core.WorkBook.GROQ.equals(m.engine());
                String model = mine ? m.model(m.engine()) : GroqWork.CHORES;
                String effort = mine ? m.effort(m.engine(), model) : "low";
                JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", input));
                String line = clean.apply(GroqWork.converse(model, instructions, messages, store.groqModels.profile(model).normalize(effort).isEmpty() ? null : effort, temperature, 8_000, null));
                if (line != null) { done.accept(line); return; }
            } catch (Exception slow) { /* Qwen and NVIDIA next */ }
            quick(c, store, nim, groq, QWEN, instructions, input, temperature, 8_000, clean, done, failed);
        }, "gyeot-buddy-voice").start();
    }
    /** An answer as it is spoken: no thinking, no marks, at most two lines joined, not too long (BuddyPrompt.spoken). */
    static String spoken(String text) { return BuddyPrompt.spoken(text); }
    private static final java.util.regex.Pattern PICKS = java.util.regex.Pattern.compile("(?m)^\\s*고르기\\s*[:：]\\s*(.+)$");
    /** The choices the brain wrote under a question ("고르기: 응 / 아니 / 글쎄"), two or three short ones joined by " / ", or "". */
    static String picks(String line, String text) {
        if (line == null || text == null || !line.strip().matches("(?s).*\\?\\s*[~!]*$")) return "";
        java.util.regex.Matcher m = PICKS.matcher(text); if (!m.find()) return "";
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String one : m.group(1).split("[/|·,]")) { String v = one.replaceAll("[\"“”‘’']", "").strip(); if (!v.isEmpty() && v.length() <= 12 && out.size() < 3) out.add(v); }
        return out.size() >= 2 ? String.join(" / ", out) : "";
    }
    private static String instructions(Context c, String reason, String topic, java.util.List<String> avoid) {
        if (reason.startsWith("answer:")) {
            String[] qa = reason.substring(7).split("\u0001", 2);
            return persona(c) + "\n지금: 네가 말풍선으로 ‘" + qa[0] + "’라고 물었고, 사용자가 ‘" + (qa.length > 1 ? qa[1] : "") + "’를 골라 답했어. 그 답에 반갑게, 그 답에 맞게 반응해.\n"
                + "- 또 묻지 말고, 잔소리·조언·힘내·쉬어 같은 말은 하지 마.\n- 한 문장, 40자 안쪽, 친근한 반말. 따옴표 없이 그 문장만 답해. 어울리면 이모지나 ^^·ㅎㅎ·ㅠㅠ·(๑˃ᴗ˂)ﻭ 같은 텍스트 이모티콘 하나를 붙여도 좋아(반짝이·별은 빼고).";
        }
        if (reason.startsWith("solo:")) {
            String play = reason.substring(5).replaceAll("[^A-Za-z]", "");
            return persona(c) + "\n지금: 너는 홈 화면 구석에서 혼자 놀고 있어. 지금 하는 놀이는 '" + play + "'(영어 이름)야. 놀면서 혼잣말처럼 한마디 해.\n"
                + "- 그 놀이와 지금 상황(시간·날씨·계절)을 엮어서 귀엽게. 의성어·감탄사(얍, 히힛, 우와)를 섞어도 좋아.\n"
                + (avoid.isEmpty() ? "" : "- 최근에 이미 꺼낸 소재와 표현이니 이번엔 다루지 마: " + String.join(", ", avoid) + "\n")
                + "- 사용자에게 뭘 하라고 하거나 묻지 말고, 일·돈·배터리·잔소리 이야기는 하지 마. 힘내·쉬어 같은 말도 하지 마.\n"
                + "- 한 문장, 25자 안쪽, 친근한 반말. 따옴표 없이 그 문장만 답해. 어울리면 이모지나 ^^·ㅎㅎ·ㅠㅠ·(๑˃ᴗ˂)ﻭ 같은 텍스트 이모티콘 하나를 붙여도 좋아(반짝이·별은 빼고).";
        }
        String moment = switch (reason) {
            case "back" -> "사용자가 방금 홈 화면으로 돌아왔어. 반갑게 먼저 말을 걸어.";
            case "pet" -> "사용자가 너를 쓰다듬어 줬어. 기분 좋은 반응을 해.";
            case "poke" -> "사용자가 너를 여러 번 콕콕 찔렀어. 어지러워하며 귀엽게 투덜거려.";
            case "snack" -> "사용자가 너에게 간식을 줬어. 맛있게 먹은 반응을 해.";
            case "full" -> "사용자가 간식을 또 줬는데 아까 먹어서 배불러. 귀엽게 사양해.";
            case "play" -> "사용자가 너랑 놀아 줬어. 신나는 반응을 해.";
            case "hungry" -> "휴대폰 배터리(너의 밥)가 얼마 안 남았어. 배고프다고 귀엽게 충전을 부탁해.";
            case "charge" -> "방금 충전기가 꽂혔어(밥 시간). 맛있게 먹는 반응을 해.";
            case "music" -> "음악이 흐르고 있어. 리듬 타며 신나게 한마디 해.";
            case "heat" -> "휴대폰이 뜨거워져서 너도 덥고 땀이 나. 귀엽게 호소해.";
            case "save" -> "휴대폰이 절전 모드라 너도 기운을 아끼는 중이야. 느릿하게 한마디 해.";
            case "shake" -> "사용자가 휴대폰을 마구 흔들어서 너는 어지러워. 귀엽게 투덜거려.";
            case "app" -> "사용자가 방금 다른 앱을 쓰고 홈 화면으로 돌아왔어(방금 쓴 앱 참고). 그 앱에서 뭘 했는지는 몰라: 묻지 말고, 그 앱에 대한 너의 느낌으로 가볍게 한마디 해.";
            default -> "사용자가 너를 톡 건드렸어.";
        };
        if (topic.startsWith("사용자를 알아 가는 질문: ")) return persona(c) + "\n지금: " + moment + "\n"
            + "- 이번엔 사용자에 대해 " + topic.substring("사용자를 알아 가는 질문: ".length()) + "을 친구처럼 궁금해하며 물어봐. 짧고 귀엽게, 너 자신의 취향을 살짝 곁들여도 좋아. 물음표로 끝내.\n"
            + "- 일·업무·작업·돈 이야기는 하지 마. 사적인 고민·비밀번호·주소 같은 건 묻지 마.\n"
            + "- 한 문장, 40자 안쪽, 친근한 반말. 따옴표 없이 그 문장만 쓰고, 둘째 줄에 사용자가 눌러서 고를 짧은 답 3개를 '고르기: 답 / 답 / 답'(각 8자 안쪽)으로 써.";
        return persona(c) + "\n지금: " + moment + " 짧게 한마디 해.\n"
            + "- 이번 화제: " + topic + ". 지금 상황(시간·날씨·휴대폰·오늘 하루·기억)에 맞게 풀어.\n"
            + (avoid.isEmpty() ? "" : "- 최근에 이미 꺼낸 소재와 표현이니 이번엔 절대 다루지 마: " + String.join(", ", avoid) + "\n")
            + "- 배터리·충전 이야기는 지금 그게 상황(충전기 꽂음, 배고픔)일 때만 해. '사용자에 대해 아는 것'과 기억은 가끔만, 같은 걸 여러 번 꺼내지 마.\n"
            + "- '지금' 시각은 참고만 해. 아침·저녁·밤 인사나 하루 마무리 말로 시작하지 마. 힘내·쉬어·수고했어 같은 말은 하지 마.\n"
            + "- '이미 했던 말'과 같거나 비슷한 뜻·소재·문장 틀은 절대 쓰지 마. 뻔한 인사말도 피해.\n"
            + "- 일, 업무, 일정, 할 일, 주식, 코인, 시세, 숫자, 돈 이야기와 조언·잔소리는 하지 마. 파일·사진 정리, 청소, 삭제, 백업, 용량, 업데이트 같은 할 일을 권하지 마.\n"
            + "- 비밀번호·계정·권한 요청, 털어놓은 고민 같은 사적인 내용은 말하지 마.\n"
            + "- 사용자에게 묻지 마. 물음표로 끝내지 말고 네 느낌·생각·소식을 말해. 너는 사용자가 앱에서 뭘 했는지, 오늘 뭘 먹고 뭘 했는지 몰라: 모르는 걸 떠보는 질문(뭐 했어? 재밌었어? 새로운 거 알게 됐어?)은 하지 마.\n"
            + "- 한 문장, 45자 안쪽. 따옴표 없이 그 문장만 답해. 어울리면 이모지나 ^^·ㅎㅎ·ㅠㅠ·(๑˃ᴗ˂)ﻭ 같은 텍스트 이모티콘 하나를 붙여도 좋아(반짝이·별은 빼고).";
    }
    /**
     * What 퍼레 sees now. What it knows about the user (the night's profile, its discovery notes) only when this topic is about
     * them, and never a subject it said too often lately (준: "전투기이야기만 수백번 하고 있어": the profile went along every
     * time, and the brain took its biggest subject every time), nor the user's work (BuddyMind.everyday).
     */
    private static String input(Context c, AppStore store, SharedPreferences p, String reason, String topic, java.util.List<String> said) {
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
        String weather = "";
        try { JSONObject w = Weather.now(c); if (w != null) weather = (w.optString("place").isBlank() ? "" : w.optString("place") + " ") + w.optInt("temp") + "도, " + sky(w.optInt("code"), w.optBoolean("day", true)); } catch (RuntimeException ignored) {}
        boolean aboutUser = topic.startsWith("사용자에 대해") || topic.startsWith("너의 기억");
        boolean aboutSelf = topic.startsWith("너의 취향") || topic.startsWith("너 자신의") || reason.startsWith("solo:");
        java.util.Set<String> stale = dev.jun.gyeot.core.BuddyMind.staleSubjects(said);
        String note = ""; if (aboutUser) try { note = dev.jun.gyeot.core.BuddyMind.freshLines(dev.jun.gyeot.core.BuddyMind.everyday(Briefing.profile(store)), stale).strip(); } catch (RuntimeException ignored) {}
        SharedPreferences home = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        String recent = home.getString("recentLabels", "[]");
        long since = home.getLong("awaySince", 0);
        String away = "back".equals(reason) && since > 0 && now - since > 3_600_000L ? (now - since) / 3_600_000L + "시간 만에 돌아왔어요\n" : "";
        // The phone's state only when it is what this moment is about (a charge, hunger, heat, music …): told every time, 퍼레
        // talked about its battery every time.
        String house = java.util.Set.of("charge", "hungry", "heat", "save", "music", "shake").contains(reason) ? home(c) : "";
        return "지금: " + when + "\n" + (weather.isEmpty() ? "" : "날씨: " + weather + "\n")
            + (recent.length() > 2 ? "방금 쓴 앱(최근 순): " + recent + "\n" : "") + away
            + (house.isEmpty() ? "" : "휴대폰(너의 집): " + house + "\n")
            + lifeWords(c) + "\n"
            + todayWords(c)
            + memoryWords(c, when + " " + weather + " " + recent, false, aboutUser, stale)
            + (aboutUser && !note.isBlank() ? "사용자에 대해 아는 것:\n" + note + "\n" : "")
            + (aboutSelf ? worldWords(c) : "")
            + "이미 했던 말(다시 쓰지 말 것): " + p.getString("memory", "[]");
    }
    /**
     * What 퍼레 brings to mind now: what it knows about the user (the night's profile), its promises, and a few memories that fit
     * the moment (BuddyMind.recall: important, recent, related, not brought up in the last day); private ones only after the
     * fingerprint (forSpeech: an answer the user asked for, where they may be needed).
     */
    static String memoryWords(Context c, String context, boolean forSpeech) { return memoryWords(c, context, forSpeech, true, java.util.Set.of()); }
    /** The same; the discovery notes and memories only when aboutUser (the promises always), and nothing on a stale subject. */
    static String memoryWords(Context c, String context, boolean forSpeech, boolean aboutUser, java.util.Set<String> stale) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = new java.util.ArrayList<>();
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : memories(c)) if (dev.jun.gyeot.core.BuddyMind.freshLines(e.text(), stale).equals(e.text())) all.add(e);
        if (all.isEmpty()) return "";
        boolean open = forSpeech && unlocked();
        StringBuilder out = new StringBuilder(); long now = System.currentTimeMillis(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.Map<Long, Long> shown = new java.util.HashMap<>();
        try { JSONObject o = new JSONObject(p.getString("shown", "{}")); for (java.util.Iterator<String> k = o.keys(); k.hasNext();) { String key = k.next(); shown.put(Long.parseLong(key), o.optLong(key)); } } catch (Exception ignored) {}
        java.util.List<String> profile = new java.util.ArrayList<>(), promises = new java.util.ArrayList<>();
        java.util.List<Long> broughtUp = new java.util.ArrayList<>();
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) {
            if (e.secret() && !open) continue;
            if ("profile".equals(e.kind())) { if (aboutUser) profile.add(e.text()); continue; }
            // A promise not yet kept, of the last week; in 퍼레's own lines once a day at most (else it was in every one).
            if (!"promise".equals(e.kind()) || e.done() || now - e.at() >= 7 * 86_400_000L) continue;
            Long last = shown.get(e.at());
            if (!forSpeech && last != null && now - last < 24 * 3_600_000L) continue;
            promises.add(e.text()); broughtUp.add(e.at());
        }
        if (!profile.isEmpty()) out.append("너의 발견 노트(사용자에 대해 알게 된 것):\n- ").append(String.join("\n- ", profile)).append('\n');
        if (!promises.isEmpty()) out.append("너와 사용자의 약속:\n- ").append(String.join("\n- ", promises)).append('\n');
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> picked = !aboutUser ? java.util.List.of() : dev.jun.gyeot.core.BuddyMind.recall(all, context, now, forSpeech ? new java.util.HashMap<>() : shown, open, forSpeech ? 6 : 3);
        if (!picked.isEmpty()) {
            out.append("너의 추억(지금 떠오르는 것):\n");
            for (dev.jun.gyeot.core.BuddyMemory.Entry e : picked) { out.append("- ").append(dev.jun.gyeot.core.BuddyMemory.when(e.at(), now, zone)).append(": ").append(e.text()).append('\n'); broughtUp.add(e.at()); }
        }
        if (!forSpeech && !broughtUp.isEmpty()) {
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<Long, Long> en : shown.entrySet()) if (now - en.getValue() < 3 * 86_400_000L) try { o.put(String.valueOf(en.getKey()), en.getValue()); } catch (Exception ignored) {}
            for (long at : broughtUp) try { o.put(String.valueOf(at), now); } catch (Exception ignored) {}
            p.edit().putString("shown", o.toString()).apply();
        }
        return out.toString();
    }
    /** Today so far, in words (BuddyMind.todayWords). */
    static String todayWords(Context c) {
        try { String w = dev.jun.gyeot.core.BuddyMind.todayWords(new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("today", "{}")), java.time.ZoneId.systemDefault()); return w.isEmpty() ? "" : "오늘 하루: " + w + "\n"; }
        catch (Exception e) { return ""; }
    }
    /**
     * Food on its way, as the delivery app's notifications say (core/Deliveries; "배달 언제 와?"): only for what the user asks,
     * never in 퍼레's own lines (준: "너무 반복해서 알림이야기를 하거나 … 자제", "흘러가듯이 슬쩍 말하는 정도").
     */
    static String deliveryWords(Context c) {
        try {
            if (!NoticeListener.on(c) || !NoticeListener.allowed(c)) return "";
            dev.jun.gyeot.core.Deliveries.Delivery d = NoticeListener.delivery(c);
            return d == null ? "" : "배달(휴대폰 알림에서 읽음): " + dev.jun.gyeot.core.Deliveries.words(d, System.currentTimeMillis(), java.time.ZoneId.systemDefault()) + "\n";
        } catch (RuntimeException unreadable) { return ""; }
    }
    /** Something of today for the day's log: an app's kind opened, a charge, music. */
    static synchronized void today(Context c, String what, String detail) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject log; try { log = new JSONObject(p.getString("today", "{}")); } catch (Exception e) { log = new JSONObject(); }
        p.edit().putString("today", dev.jun.gyeot.core.BuddyMind.today(log, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), what, detail).toString()).apply();
    }
    private static String lifeWords(Context c) {
        long now = System.currentTimeMillis();
        try { return dev.jun.gyeot.core.BuddyLife.words(dev.jun.gyeot.core.BuddyLife.read(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LIFE, ""), now), now); }
        catch (RuntimeException e) { return ""; }
    }
    /** The model's line as 퍼레 says it: the first line, no quotes, short; null when empty, about prices, or already said. */
    static String clean(String text, SharedPreferences p) {
        if (text == null) return null;
        String t = text.replaceAll("(?s)<think>.*?</think>", "").strip();
        for (String line : t.split("\\n")) {
            String l = line.replaceAll("^[-•*\\d.\\s]+", "").replaceAll("[\"“”‘’']", "").replaceAll("[✨⭐🌟💫🌠]", "").strip();
            if (l.isEmpty()) continue;
            if (l.length() > 70 || l.matches(".*(코인|비트코인|주식|시세|SOXL|KORU|BTC|\\$|%).*")) return null;
            // Chores are not 퍼레's to suggest (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데").
            if (dev.jun.gyeot.core.BuddyMind.CHORES.matcher(l).find()) return null;
            // Too like a line already said (half its letter pairs or more): not this one (준: "했던말 자꾸 반복하는 경향 없애줘").
            if (p != null && dev.jun.gyeot.core.BuddyMind.repeats(l, said(p), 0.45)) return null;
            // The same subject again, worded anew (준: "자꾸 전투기 배터리 이야기를 반복해서해"): not this one either.
            if (p != null && dev.jun.gyeot.core.BuddyMind.staleSubject(l, said(p))) return null;
            // No cheering or "go rest" unasked, and not the same first word as lately (준: "자꾸 화이팅하고 쉬래", "반복적인 패턴이 있어").
            if (dev.jun.gyeot.core.BuddyMind.nag(l)) return null;
            if (p != null && dev.jun.gyeot.core.BuddyMind.sameOpening(l, said(p))) return null;
            // Korean that does not read (준: "가끔 한국어를 못알아듣게 말해"): a line cut at its start, or one in another script.
            if (!dev.jun.gyeot.core.BuddyMind.readable(l)) return null;
            return l;
        }
        return null;
    }
    private static synchronized void remember(SharedPreferences p, String line) {
        JSONArray memory; try { memory = new JSONArray(p.getString("memory", "[]")); } catch (Exception e) { memory = new JSONArray(); }
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, memory.length() - 59); i < memory.length(); i++) kept.put(memory.optString(i));
        kept.put(line);
        p.edit().putString("memory", kept.toString()).apply();
    }
    // ── 퍼레's life on the phone (core/BuddyLife): kept here, read by the board and by 퍼레's own words ──
    private static final String LIFE = "life";
    /** 퍼레's life now (faded for the time alone), for the board. */
    static synchronized JSONObject life(Context c) {
        long now = System.currentTimeMillis();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dev.jun.gyeot.core.BuddyLife.State s = dev.jun.gyeot.core.BuddyLife.read(p.getString(LIFE, ""), now);
        p.edit().putString(LIFE, dev.jun.gyeot.core.BuddyLife.save(s)).apply();
        JSONObject out = dev.jun.gyeot.core.BuddyLife.json(s, now);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> m = memoryView(c); // what is kept in memory, the file read only when it changed
        try { out.put("memories", m.size()); if (!m.isEmpty()) out.put("lastMemory", m.get(m.size() - 1).text()); } catch (Exception ignored) {}
        return out;
    }
    /** What the user did for 퍼레 (pet, snack, play, talk, back): its life after, with how it took it ("felt"). */
    static synchronized JSONObject care(Context c, String kind) {
        long now = System.currentTimeMillis();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dev.jun.gyeot.core.BuddyLife.Done d = dev.jun.gyeot.core.BuddyLife.act(dev.jun.gyeot.core.BuddyLife.read(p.getString(LIFE, ""), now), kind, now);
        p.edit().putString(LIFE, dev.jun.gyeot.core.BuddyLife.save(d.state())).apply();
        // Only what is worth remembering (준: "필요없는것들이 다 기록이돼"): not a stroke, a snack, play or a talk as such.
        if ("back".equals(kind)) back(c, now);
        JSONObject out = life(c);
        try { out.put("felt", d.felt()); } catch (Exception ignored) {}
        return out;
    }
    /**
     * The user came home: after hours away it is a memory, and so is being up in the small hours; counted a week to a line
     * (BuddyMemory.tally), so the routine never pushes out what the user told.
     */
    private static void back(Context c, long now) {
        long since = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getLong("awaySince", 0);
        long hours = (now - since) / 3_600_000L;
        if (since > 0 && now - since >= 8 * 3_600_000L) tally(c, now, "away", false, n -> n == 1 ? hours + "시간 만에 사용자가 돌아왔어. 기다렸어" : "한 주 동안 사용자가 오래 비웠다 돌아온 게 " + n + "번이었어. 기다렸어");
        int h = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).getHour();
        if (h >= 1 && h < 5) tally(c, now, "late", true, n -> n == 1 ? "새벽 " + h + "시에도 사용자가 깨어 있었어" : "한 주 동안 새벽까지 사용자가 깨어 있던 날이 " + n + "번이었어");
    }
    /** A routine moment counted in its week's line (BuddyMemory.tally), written only when it changed. */
    static synchronized void tally(Context c, long now, String kind, boolean oncePerDay, java.util.function.IntFunction<String> line) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> was = memoryView(c), after = dev.jun.gyeot.core.BuddyMemory.tally(was, now, java.time.ZoneId.systemDefault(), kind, oncePerDay, line);
        if (after != was) keepMemories(c, after);
    }

    // ── 퍼레's own memory (core/BuddyMemory), in the app's files apart from the assistant's memory ──
    /** The kinds of memory kept: what the user told (fact), long away, up late, snow and very cold or hot days. */
    static final java.util.Set<String> KEPT = java.util.Set.of("fact", "promise", "profile", "episode", "away", "late", "snow", "cold", "hot");
    /**
     * What 퍼레 learned about the user from one exchange (퍼레 대화's typed answer, a question's pick): learned as a talk of two
     * turns (learnTalk).
     */
    static void learnLater(Context c, String said, String answer) {
        if (said == null || said.isBlank()) return;
        java.util.List<String[]> turns = new java.util.ArrayList<>();
        turns.add(new String[]{"user", said.strip()});
        if (answer != null && !answer.isBlank()) turns.add(new String[]{"assistant", answer.strip()});
        learnTalk(c, turns, 0, java.util.List.of());
    }
    /**
     * What the user said in 퍼레 대화, learned with the six turns typed before it as what was already learned (history: the
     * page's [{who, text}], "me" the user).
     */
    static void learnChat(Context c, String history, String question, String answer) {
        java.util.List<String[]> turns = new java.util.ArrayList<>();
        try {
            JSONArray h = new JSONArray(history == null || history.isBlank() ? "[]" : history);
            for (int i = Math.max(0, h.length() - 6); i < h.length(); i++) {
                JSONObject t = h.optJSONObject(i); if (t == null || t.optString("text").isBlank()) continue;
                turns.add(new String[]{"me".equals(t.optString("who")) ? "user" : "assistant", t.optString("text")});
            }
        } catch (Exception unreadable) { turns.clear(); }
        int from = turns.size();
        if (question == null || question.isBlank()) return;
        turns.add(new String[]{"user", question.strip()});
        if (answer != null && !answer.isBlank()) turns.add(new String[]{"assistant", answer.strip()});
        learnTalk(c, turns, from, java.util.List.of());
    }
    /** The learning brain's rules: its lessons as operations on what 퍼레 knows, the secret, number and length rules as before. */
    static final String LEARN_RULES = "사용자와 AI 친구 퍼레의 대화야. 아래 '지금 알고 있는 것'과 비교해서, 배운 것을 한 줄에 하나씩 이 형식으로만 써(최대 4줄):\n"
        + "새로: 사용자는 …(친구가 오래 기억할 사용자의 이야기: 계획, 좋아하거나 싫어하는 것, 기분과 그 이유, 일상의 일, 소중한 사람·동물. 45자 안쪽, ~대/~래 말투)\n"
        + "고침 N: 사용자는 …(사용자가 N번을 고쳐 말했을 때, 새 사실로)\n"
        + "지움 N(사용자가 N번이 아니라고 했을 때)\n"
        + "같음 N(사용자가 N번을 또 말했을 때)\n"
        + "약속: 퍼레가 …(퍼레가 사용자에게 해 주기로 한 것이 있을 때만)\n"
        + "약속 끝 N(퍼레가 N번 약속을 지켰거나 더 필요 없어졌을 때)\n"
        + "'앞의 대화'는 이미 배운 거라 참고만 하고, '대화'와 '한 일'에서만 배워. '한 일'은 취향이 드러날 때만(즐겨 듣는 가수, 자주 찾는 곳) 배워.\n"
        + "비밀번호·로그인·계정, 사이트나 앱의 권한 요청, 사용자가 털어놓은 고민·걱정처럼 남이 알면 곤란한 이야기면 그 줄 앞에 '민감 '을 붙여. 병원·건강·연애·가족 같은 일상 이야기는 민감하지 않아. "
        + "일·작업·돈 이야기는 배우지 마. 인사·잡담·질문·부탁·명령·앱 사용법·날씨·지금 잠깐의 일뿐이면 '없음'이라고만 써. 비밀번호·주소·전화번호·계좌 같은 번호나 값 자체는 절대 쓰지 마.";
    /**
     * What 퍼레 learns from a talk (준: "퍼레의 학습기능 … 강화해줘"), once for the whole of it rather than a request a turn: the
     * user's own turns (a guest's left out; those before from were learned already and go only as what came before) and what
     * was done for them (acts), with what it knows that the talk is about; the quick brain writes what is new, set right, let
     * go or told again (BuddyMind.parseOps), applied here (BuddyMind.learned). Nothing when the user said less than four
     * letters or no quick brain is connected. Off the main thread.
     */
    static void learnTalk(Context c, java.util.List<String[]> turns, int from, java.util.List<String> acts) {
        if (!dev.jun.gyeot.core.BuddyMind.worthLearning(turns, from)) return;
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on(); if (!nim && !groq) return;
        java.util.List<String[]> talk = new java.util.ArrayList<>(turns); java.util.List<String> done = acts == null ? java.util.List.of() : new java.util.ArrayList<>(acts);
        new Thread(() -> {
            String words = dev.jun.gyeot.core.BuddyMind.talkWords(talk, from, true);
            java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> related = dev.jun.gyeot.core.BuddyMind.related(memories(c), words, 6, unlocked());
            String input = dev.jun.gyeot.core.BuddyMind.talkInput(talk, from, done, related);
            quick(c, store, nim, groq, QWEN, LEARN_RULES, input, 0.2, 15_000, text -> text == null ? "" : text.replaceAll("(?s)<think>.*?</think>", "").strip(),
                text -> learned(c, related, text, words), why -> {});
        }, "gyeot-buddy-learn").start();
    }
    /** A talk's lessons (the learning brain's text) applied to what 퍼레 knows, all at once; none on a subject let go ("잊어 줘"). */
    static void learned(Context c, java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> shown, String text, String talk) {
        java.util.List<dev.jun.gyeot.core.BuddyMind.Op> ops = dev.jun.gyeot.core.BuddyMind.parseOps(text);
        if (ops.isEmpty()) return;
        synchronized (BuddyTalk.class) {
            long t = System.currentTimeMillis();
            java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> now = memories(c), after = dev.jun.gyeot.core.BuddyMind.learned(now, shown, ops, talk, t, unlocked(), forgot(c));
            if (!after.equals(now)) keepMemories(c, after);
        }
    }
    // ── subjects the user asked 퍼레 to let go ("커피 얘기 잊어 줘"): not learned again by itself for ninety days ──
    private static final String FORGOT = "forgot";
    /** The subjects let go, each with when it may be learned again (subject → until); over ones left out. */
    static synchronized java.util.Map<String, Long> forgot(Context c) {
        java.util.Map<String, Long> out = new java.util.HashMap<>(); long now = System.currentTimeMillis();
        try { JSONObject o = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FORGOT, "{}")); for (java.util.Iterator<String> k = o.keys(); k.hasNext();) { String key = k.next(); long until = o.optLong(key); if (until > now) out.put(key, until); } }
        catch (Exception unreadable) { out.clear(); }
        return out;
    }
    /** These subjects let go from now (ninety days); at most forty kept, the ones over soonest leave first. */
    static synchronized void forgot(Context c, java.util.Collection<String> subjects) {
        java.util.Map<String, Long> all = forgot(c); long until = System.currentTimeMillis() + dev.jun.gyeot.core.BuddyMind.FORGOT_MS;
        for (String s : subjects) if (s != null && !s.isBlank()) all.put(s.strip(), until);
        keepForgot(c, all);
    }
    /** The subjects of this line taken back (the user asked to keep it after all: "기억해 둬", "되돌려 줘"). */
    static synchronized void unforgot(Context c, String text) {
        java.util.Map<String, Long> all = forgot(c); if (all.isEmpty()) return;
        java.util.Map<String, Long> kept = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Long> f : all.entrySet()) if (!dev.jun.gyeot.core.BuddyMind.forgotten(text, java.util.Map.of(f.getKey(), f.getValue()), System.currentTimeMillis())) kept.put(f.getKey(), f.getValue());
        if (kept.size() != all.size()) keepForgot(c, kept);
    }
    private static void keepForgot(Context c, java.util.Map<String, Long> all) {
        java.util.List<java.util.Map.Entry<String, Long>> list = new java.util.ArrayList<>(all.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, Long> f : list.subList(0, Math.min(40, list.size()))) try { o.put(f.getKey(), f.getValue()); } catch (Exception ignored) {}
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FORGOT, o.toString()).apply();
    }
    /**
     * Memories let go a moment ago, back where they were (by when they were first kept); ones still there stay once, each
     * known by all it holds (two kept in the same millisecond are not mistaken for each other).
     */
    static synchronized void restore(Context c, java.util.Collection<dev.jun.gyeot.core.BuddyMemory.Entry> back) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = memories(c);
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : back) if (!all.contains(e)) all.add(e);
        all.sort(java.util.Comparator.comparingLong(dev.jun.gyeot.core.BuddyMemory.Entry::at));
        keepMemories(c, all);
    }
    /**
     * When the user may be correcting what 퍼레 knew ("아니 나 커피 끊었어", BuddyMind.correcting) and it knows something the
     * words are about: a line for the answer's rules, so it takes it in briefly and answers with the new fact; "" otherwise.
     */
    static String corrected(Context c, String said) {
        if (!dev.jun.gyeot.core.BuddyMind.correcting(said)) return "";
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> known = dev.jun.gyeot.core.BuddyMind.related(memories(c), said, 1, unlocked());
        if (known.isEmpty()) return "";
        return "- 사용자가 네가 알던 걸 고쳐 줬어(예전: ‘" + known.get(0).text() + "’). 짧게 받아들이고 새 사실로 답해. 사과를 길게 하지 마.\n";
    }
    private static java.io.File memoryFile(Context c) { return new java.io.File(new java.io.File(c.getFilesDir(), "buddy"), "memory.json"); }
    /*
     * The memories as last read or written, with the file's size and time then (CLAUDE.md: 한 번 읽은 파일은 다시 읽지 않는다):
     * a thought, an answer and every board build each asked for them several times and read the file every time.
     */
    private static java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> memoryCache;
    private static String memoryPath = "";
    private static long memoryLength = -2, memoryModified = -2;
    /** How many times memory.json was read (tests). */
    static int memoryReads;
    private static void cacheMemories(java.io.File f, java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list) {
        memoryCache = java.util.List.copyOf(list); memoryPath = f.getPath();
        memoryLength = f.isFile() ? f.length() : -1; memoryModified = f.isFile() ? f.lastModified() : -1;
    }
    /** The memories as kept now, not to be changed (the file read only when it changed); BuddyTalk.class held. */
    private static synchronized java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> memoryView(Context c) {
        java.io.File f = memoryFile(c);
        long length = f.isFile() ? f.length() : -1, modified = f.isFile() ? f.lastModified() : -1;
        if (memoryCache != null && f.getPath().equals(memoryPath) && length == memoryLength && modified == memoryModified) return memoryCache;
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> read = readMemories(c, f);
        return read == null ? java.util.List.of() : memoryCache;
    }
    /** The memories, a list of the caller's own to change. Off the main thread (the first time reads a file). */
    static synchronized java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> memories(Context c) { return new java.util.ArrayList<>(memoryView(c)); }
    /** How many memories 퍼레 has, and the newest, for the board (from what is kept in memory). */
    static synchronized int memoryCount(Context c) { return memoryView(c).size(); }
    private static java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> readMemories(Context c, java.io.File f) {
        // The storage memories of 0.4.560 are left out (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데"): 퍼레 does not talk about storage.
        // Only what is worth remembering; the routine records of 0.4.559–0.4.567 (apps opened, charging, play …) are left out.
        try {
            memoryReads++;
            java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = dev.jun.gyeot.core.BuddyMemory.read(f.isFile() ? java.nio.file.Files.readString(f.toPath()) : "[]"); all.removeIf(m -> !KEPT.contains(m.kind()));
            // Kept private before 0.4.572 for health, love or family (준: "병원에 간대요 정도는 민감한 기억이 아닌 듯"): open again,
            // unless it is a password, an access or a worry; once.
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getInt("secretRules", 1) < 2) {
                java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> open = new java.util.ArrayList<>();
                for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) open.add(e.secret() && !dev.jun.gyeot.core.BuddyMind.secret(e.text()) ? new dev.jun.gyeot.core.BuddyMemory.Entry(e.at(), e.kind(), e.text(), e.weight(), false, e.seen(), e.last(), e.done()) : e);
                if (f.isFile()) keepMemories(c, open);
                p.edit().putInt("secretRules", 2).apply();
                all = open;
            }
            cacheMemories(f, all);
            return all;
        }
        catch (Exception unreadable) { memoryCache = null; return null; }
    }
    private static synchronized void keepMemories(Context c, java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list) {
        java.io.File f = memoryFile(c);
        try { f.getParentFile().mkdirs(); java.io.File tmp = new java.io.File(f.getPath() + ".tmp"); java.nio.file.Files.writeString(tmp.toPath(), dev.jun.gyeot.core.BuddyMemory.save(list)); if (!tmp.renameTo(f)) java.nio.file.Files.writeString(f.toPath(), dev.jun.gyeot.core.BuddyMemory.save(list)); cacheMemories(f, list); }
        catch (Exception ignored) { memoryCache = null; }
    }
    /** A memory told in a talk: it matters more (weight 2), private when it is. */
    static synchronized void rememberStrong(Context c, String kind, String text, boolean secret) { rememberStrong(c, kind, text, secret, 2); }
    /** The same, as much as it matters (3: the user asked for it to be kept, "기억해 둬"). */
    static synchronized void rememberStrong(Context c, String kind, String text, boolean secret, int weight) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> now = memories(c), after = dev.jun.gyeot.core.BuddyMemory.add(now, System.currentTimeMillis(), kind, text, 0, weight, secret);
        if (after != now) keepMemories(c, after);
    }
    /**
     * The night's digest (꿈꾸기): once a day, in the small hours, while charging after nine at night, or as the home screen is
     * left after ten (tonight), the quick brain reads the day, the latest memories and what the user told again and again
     * (whatever its age), and writes what 퍼레 knows about the user (its 발견 노트) and the day as one memory. What it writes is
     * merged into the note (BuddyMind.mergeProfile: kept unless taken out with 빼기, a guess matters least, twelve lines at
     * most), nothing on a subject the user let go. The day counts as done only once it answered; a failure is tried again
     * two hours on. Off the main thread.
     */
    static void dream(Context c, boolean charging) { dream(c, charging, false, java.time.ZonedDateTime.now()); }
    static void dream(Context c, boolean charging, boolean tonight, java.time.ZonedDateTime at) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int h = at.getHour(); String date = at.toLocalDate().toString(); long now = at.toInstant().toEpochMilli();
        if (date.equals(p.getString("dreamt", "")) || !(h >= 2 && h < 6 || charging && h >= 21 || tonight && h >= 22)) return;
        if (Math.abs(now - p.getLong("dreamTry", 0)) < 2 * 3_600_000L) return;
        String instructions = "너는 AI 친구 퍼레의 기억을 밤에 정리하는 역할이야. 아래를 읽고 다음 형식으로만 써.\n"
            + "프로필: (사용자에 대해 지금 알고 있는 사실 하나. 여러 줄 가능, 최대 12줄. 지금까지의 노트에 있는 건 그대로 둬도 되고, 겹치는 건 합치고, 추측이면 끝에 '(추측)'.)\n"
            + "빼기: (지금까지의 노트에서 틀렸거나 오래돼 맞지 않는 줄을 그대로. 없으면 쓰지 마.)\n"
            + "추억: (오늘 하루를 친구 시점에서 한 줄로. 특별한 게 없으면 '없음')\n"
            + "비밀번호·계정·권한 요청, 털어놓은 고민 같은 민감한 건 쓰지 마. 숫자 계좌·번호는 절대 쓰지 마.";
        String input = dreamInput(c, now);
        if (night.ask(c, instructions, input, text -> {
            dreamt(c, dev.jun.gyeot.core.BuddyMind.dream(text), now);
            p.edit().putString("dreamt", date).apply();
        }, () -> {})) p.edit().putLong("dreamTry", now).apply();
    }
    /** How the night's digest asks the brain: false when none is connected (tests put their own). */
    interface Night { boolean ask(Context c, String instructions, String input, Consumer<String> done, Runnable failed); }
    static Night night = (c, instructions, input, done, failed) -> {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on(); if (!nim && !groq) return false;
        new Thread(() -> quick(c, store, nim, groq, QWEN, instructions, input, 0.3, 20_000, text -> text == null ? "" : text, done, why -> failed.run()), "gyeot-buddy-dream").start();
        return true;
    };
    /** What the night's digest reads: the day, the note so far, every fact told twice or more, and the last two weeks; nothing private or let go. */
    static String dreamInput(Context c, long now) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = memories(c); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.util.Map<String, Long> forgot = forgot(c);
        StringBuilder in = new StringBuilder(todayWords(c)).append("지금까지의 발견 노트:\n");
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if ("profile".equals(e.kind()) && !e.secret() && !dev.jun.gyeot.core.BuddyMind.forgotten(e.text(), forgot, now)) in.append("- ").append(e.text()).append('\n');
        in.append("여러 번 말해 준 것:\n");
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if ("fact".equals(e.kind()) && e.seen() >= 2 && !e.secret() && !dev.jun.gyeot.core.BuddyMind.forgotten(e.text(), forgot, now)) in.append("- ").append(e.text()).append(" (").append(e.seen()).append("번)\n");
        in.append("최근 기억(2주):\n");
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if (!"profile".equals(e.kind()) && !e.secret() && now - e.last() < 14 * 86_400_000L && !(e.done()) && !dev.jun.gyeot.core.BuddyMind.forgotten(e.text(), forgot, now))
            in.append("- ").append(dev.jun.gyeot.core.BuddyMemory.when(e.at(), now, zone)).append(": ").append(e.text()).append('\n');
        return in.toString();
    }
    /** The night's digest kept: merged into the note (BuddyMind.mergeProfile), the day as one memory unless it is on a subject let go. */
    static void dreamt(Context c, dev.jun.gyeot.core.BuddyMind.Dream d, long now) {
        synchronized (BuddyTalk.class) {
            java.util.Map<String, Long> forgot = forgot(c);
            java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> was = memories(c), list = dev.jun.gyeot.core.BuddyMind.mergeProfile(was, d, now, forgot);
            if (!d.episode().isEmpty() && !dev.jun.gyeot.core.BuddyMind.forgotten(d.episode(), forgot, now)) list = dev.jun.gyeot.core.BuddyMemory.add(list, now, "episode", d.episode(), 12 * 3_600_000L, 2, false);
            if (!list.equals(was)) keepMemories(c, list);
        }
    }
    /** As the home screen is left late in the evening: the night's digest now, if it has not been done today (BuddyTalk.dream). */
    static void dreamTonight(Context c) { dream(c, false, true, java.time.ZonedDateTime.now()); }
    /** The memory kept at at told once more (BuddyMemory.confirm). */
    static synchronized void confirm(Context c, long at) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> now = memories(c), after = dev.jun.gyeot.core.BuddyMemory.confirm(now, at, System.currentTimeMillis());
        if (after != now) keepMemories(c, after);
    }
    /** The memory kept at at said anew, as the user set it right (BuddyMemory.replace). */
    static synchronized void replace(Context c, long at, String text, boolean secret, int weight) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> now = memories(c), after = dev.jun.gyeot.core.BuddyMemory.replace(now, at, text, System.currentTimeMillis(), secret, weight);
        if (after != now) keepMemories(c, after);
    }
    static synchronized void forget(Context c, long at) { keepMemories(c, dev.jun.gyeot.core.BuddyMemory.remove(memories(c), at)); }
    static synchronized void forgetAll(Context c) { keepMemories(c, new java.util.ArrayList<>()); }
    /** These memories gone, each by all it holds (two kept in the same millisecond are not mistaken for each other). */
    static synchronized void forget(Context c, java.util.Collection<dev.jun.gyeot.core.BuddyMemory.Entry> gone) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = memories(c); all.removeAll(gone); keepMemories(c, all);
    }
    /**
     * The weather, when it is worth remembering (snow, very cold or hot): once a day, a week to a line (BuddyMemory.tally);
     * every board build asks, and only a new day writes.
     */
    static void weatherMemory(Context c, JSONObject w) { weatherMemory(c, w, System.currentTimeMillis()); }
    static void weatherMemory(Context c, JSONObject w, long now) {
        if (w == null) return;
        int code = w.optInt("code", -1), t = w.optInt("temp", 15); String place = w.optString("place").isBlank() ? "" : w.optString("place") + "에 ";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) tally(c, now, "snow", true, n -> n == 1 ? place + "눈이 내렸어" : "한 주 동안 눈 온 날이 " + n + "번이었어");
        if (t <= -5) tally(c, now, "cold", true, n -> n == 1 ? "영하로 꽁꽁 추운 날이라 목도리를 했어" : "한 주 동안 영하로 꽁꽁 추운 날이 " + n + "번이었어");
        else if (t >= 33) tally(c, now, "hot", true, n -> n == 1 ? "푹푹 찌게 더운 날이었어" : "한 주 동안 푹푹 찌게 더운 날이 " + n + "번이었어");
    }
    /** An app the user opened from the home screen, once in six hours each. */
    /**
     * 퍼레's home, the phone, as it is now: the battery (its food; charging is a meal), music playing (it dances) and
     * headphones on, the phone silenced, no network, its heat, power saving, do-not-disturb, airplane mode. Nothing private;
     * asked each time. Not the storage: 퍼레 kept telling the user to tidy files (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데").
     */
    /** Whether another app's media session is playing (with notification access), or null when that cannot be read. */
    static Boolean musicPlaying(Context c) {
        try {
            android.media.session.MediaSessionManager m = c.getSystemService(android.media.session.MediaSessionManager.class);
            if (m == null) return null;
            for (android.media.session.MediaController s : m.getActiveSessions(new android.content.ComponentName(c, NoticeListener.class))) {
                if (c.getPackageName().equals(s.getPackageName())) continue;
                android.media.session.PlaybackState p = s.getPlaybackState();
                if (p != null && p.getState() == android.media.session.PlaybackState.STATE_PLAYING) return true;
            }
            return false;
        } catch (SecurityException noAccess) { return null; }
        catch (RuntimeException unknown) { return null; }
    }
    static JSONObject env(Context c) {
        JSONObject e = new JSONObject();
        try {
            android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
            if (bm != null) { int level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY); if (level >= 0 && level <= 100) e.put("battery", level); e.put("charging", bm.isCharging()); }
        } catch (Exception ignored) {}
        try {
            android.content.Intent b = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
            if (b != null) { int t = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1); if (t > 0) e.put("heat", t / 10); }
        } catch (Exception ignored) {}
        try {
            android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
            if (am != null) {
                // A music app playing (준: "음악을 껐는데도 제자리에서 계속 음악감상 모드야"): its media session says so; any
                // sound at all (isMusicActive) counted this app's own pages, whose sound engine stays on after the record stops.
                Boolean playing = musicPlaying(c);
                e.put("music", playing != null ? playing : am.isMusicActive());
                boolean phones = false;
                for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    int t = d.getType();
                    if (t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || t == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) phones = true;
                }
                e.put("phones", phones);
                e.put("silent", am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL);
            }
        } catch (Exception ignored) {}
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            if (cm != null) {
                android.net.Network n = cm.getActiveNetwork(); e.put("offline", n == null);
                android.net.NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                if (caps != null) e.put("wifi", caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI));
            }
        } catch (Exception ignored) {}
        try { android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class); if (pm != null) e.put("powerSave", pm.isPowerSaveMode()); } catch (Exception ignored) {}
        try { android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class); if (nm != null) e.put("dnd", nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL); } catch (Exception ignored) {}
        try { e.put("airplane", android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0); } catch (Exception ignored) {}
        // Asked to keep quiet a while (the quiet tool): the board starts no talk, as in do-not-disturb (the phone's own is "dnd").
        try { if (hushed(c)) e.put("hush", true); } catch (Exception ignored) {}
        // The day's log (a charge, music) and, at night, the night's digest.
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject was = new JSONObject(p.getString("env", "{}")); p.edit().putString("env", e.toString()).apply();
            if (e.optBoolean("charging") && !was.optBoolean("charging")) today(c, "charge", "");
            if (e.optBoolean("music") && !was.optBoolean("music")) today(c, "music", "");
            dream(c, e.optBoolean("charging"));
        } catch (Exception ignored) {}
        return e;
    }
    /**
     * The phone's state as plain facts, for answers when the user asks (준: "내가 지금 저장공간 얼마남았어? 라고 물을 때 답을 못하자나"):
     * the storage left and in all (as the phone's settings count it, in GB), the battery, its heat, the network. Not for
     * 퍼레's own lines, which never bring storage up.
     */
    static String facts(Context c) {
        StringBuilder out = new StringBuilder();
        try {
            android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            double free = fs.getAvailableBytes() / 1e9, total = fs.getTotalBytes() / 1e9;
            if (total > 0) out.append(String.format(Locale.KOREA, "저장공간 남은 %.1fGB / 전체 약 %.0fGB (%d%% 사용). ", free, total, Math.round((1 - free / total) * 100)));
        } catch (Exception ignored) {}
        JSONObject e = env(c);
        if (e.has("battery")) out.append("배터리 ").append(e.optInt("battery")).append("%").append(e.optBoolean("charging") ? " 충전 중" : "").append(". ");
        if (e.optInt("heat") > 0) out.append("배터리 온도 ").append(e.optInt("heat")).append("도. ");
        out.append(e.optBoolean("offline") ? "인터넷 끊김. " : e.optBoolean("wifi") ? "와이파이 연결. " : "모바일 데이터 연결. ");
        if (e.optBoolean("powerSave")) out.append("절전 모드. ");
        if (e.optBoolean("dnd")) out.append("방해 금지. ");
        if (e.optBoolean("airplane")) out.append("비행기 모드. ");
        return out.toString().strip();
    }
    /** The phone as 퍼레's home, in plain words for its own lines. */
    private static String home(Context c) {
        JSONObject e = env(c); StringBuilder out = new StringBuilder();
        if (e.has("battery")) out.append("배터리(너의 밥) ").append(e.optInt("battery")).append("%").append(e.optBoolean("charging") ? ", 지금 충전 중(밥 먹는 중)" : e.optInt("battery") <= 20 ? ", 배고픔" : "").append(". ");
        if (e.optBoolean("music")) out.append(e.optBoolean("phones") ? "사용자가 이어폰으로 음악을 듣는 중. " : "음악이 흐르는 중. ");
        if (e.optBoolean("silent")) out.append("휴대폰이 무음이라 조용히. ");
        if (e.optBoolean("offline")) out.append("인터넷이 끊김. "); else if (e.has("wifi")) out.append(e.optBoolean("wifi") ? "와이파이 연결. " : "모바일 데이터 연결. ");
        if (e.optInt("heat") >= 41) out.append("휴대폰이 ").append(e.optInt("heat")).append("도로 뜨거움. ");
        if (e.optBoolean("powerSave")) out.append("절전 모드. ");
        if (e.optBoolean("dnd")) out.append("방해 금지 모드(말을 아낄 것). ");
        if (e.optBoolean("hush")) out.append("사용자가 조용히 있어 달라고 한 시간(부르면 대답만, 먼저 말 걸지 않기). ");
        if (e.optBoolean("airplane")) out.append("비행기 모드. ");
        return out.toString().strip();
    }
    private static String sky(int code, boolean day) {
        if (code == 0) return day ? "맑음" : "맑은 밤";
        if (code <= 2) return "구름 조금";
        if (code == 3) return "흐림";
        if (code == 45 || code == 48) return "안개";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "비";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "눈";
        if (code >= 95) return "뇌우";
        return "흐림";
    }
}
