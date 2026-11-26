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
    /** NVIDIA's quick model (half a second in a test on 2026-11-26; the account's Ultra took sixteen). */
    static final String NIM_MODEL = "nvidia/nemotron-3.5-bolt-e4b";
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
        String topic = dev.jun.gyeot.core.BuddyMind.topic(used, hasMemory, hasApp, new java.util.Random());
        try { used.put(topic, System.currentTimeMillis()); } catch (Exception ignored) {}
        p.edit().putString("topics", used.toString()).apply();
        String instructions = instructions(c, reason, topic, dev.jun.gyeot.core.BuddyMind.overused(said, 8)), input = input(c, store, p, reason);
        ask(c, instructions, input, attempt == 0 ? 0.75 : 0.9, 9_000, text -> clean(text, p), line -> { remember(p, line); busy.set(false); done.accept(line); },
            () -> { if (attempt == 0) thinkOnce(c, store, p, reason, done, failed, 1); else { busy.set(false); failed.run(); } });
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
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : memories(c)) if (e.secret()) return true;
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
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on();
        // The app's AI chosen for 퍼레: it answers; when it cannot, the quick ones below do.
        if (CUSTOM.equals(brain(c)) && !customLabel(store).isEmpty()) {
            new Thread(() -> {
                try { String line = clean.apply(askCustom(store, instructions, input, null)); if (line != null) { done.accept(line); return; } }
                catch (Exception unavailable) { /* the quick brains next */ }
                if (nim || groq) quick(c, store, nim, groq, QWEN, instructions, input, temperature, timeoutMs, clean, done, failed); else failed.run();
            }, "gyeot-buddy-custom").start();
            return;
        }
        if (!nim && !groq) { failed.run(); return; }
        quick(c, store, nim, groq, brain(c), instructions, input, temperature, timeoutMs, clean, done, failed);
    }
    /** Groq's Qwen and NVIDIA NIM, raced as the brain says (QWEN, NIM or BOTH). */
    private static void quick(Context c, AppStore store, boolean nim, boolean groq, String brain, String instructions, String input, double temperature, int timeoutMs, java.util.function.Function<String, String> clean, Consumer<String> done, Runnable failed) {
        AtomicBoolean answered = new AtomicBoolean();
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
                else if (answered.compareAndSet(false, true)) next = failed;
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
                    String line = clean.apply(text); if (line != null) win.accept(line); else lost.run();
                } catch (Exception e) { lost.run(); }
            }, "gyeot-buddy-qwen").start();
        };
        start[1] = () -> {
            synchronized (lock) { if (answered.get() || tried[1]) return; tried[1] = true; running[0]++; }
            new Thread(() -> {
                long begun = System.currentTimeMillis();
                try {
                    WorkClient.Result r = NimClient.complete(store.nim, store.nim.generation(), NIM_MODEL, instructions, input, () -> { if (answered.get()) throw new java.util.concurrent.CancellationException(); }, stopNim);
                    if (System.currentTimeMillis() - begun > 4_000) nimSlowUntil = System.currentTimeMillis() + 10 * 60_000L;
                    String line = clean.apply(r.text); if (line != null) win.accept(line); else lost.run();
                } catch (Exception e) { nimSlowUntil = System.currentTimeMillis() + 5 * 60_000L; lost.run(); }
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
    static void reply(Context c, String said, java.util.List<String[]> turns, Consumer<String> done, Runnable failed) { reply(c, said, turns, false, done, failed); }
    /**
     * guest: the voice is not the user's (VoicePrint, 준: "내 목소리를 알아볼 수 있게해줘"): 퍼레 answers a guest kindly and keeps the
     * user's memories, day and profile to itself, and learns nothing from what the guest said.
     */
    static void reply(Context c, String said, java.util.List<String[]> turns, boolean guest, Consumer<String> done, Runnable failed) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        String instructions = persona(c) + "\n지금 " + (guest ? "누군가와" : "사용자와") + " 목소리로 대화하고 있어.\n"
            + (guest ? "- 지금 들리는 목소리는 '" + title(c) + "'(휴대폰 주인) 목소리가 아니야. 처음 듣는 목소리라고 반갑게 대하되, 주인의 기억·일정·하루·개인적인 이야기는 절대 꺼내지 말고, 주인에 대해 물어도 알려 주지 마. 상대를 '" + title(c) + "'(이)라고 부르지 마.\n" : "")
            + "- 사용자의 마지막 말에 바로, 친구처럼 답해. 물으면 핵심만 짧게 알려 주고, 모르면 솔직하게 말해.\n"
            + "- 소리 내어 읽기 좋게: 1~2문장, 80자 안쪽, 친근한 구어체 반말(~야, ~해). 이모지·기호·목록·괄호·영어 약어 없이.\n"
            + "- 귀엽고 밝게, 가끔 너의 기억을 자연스럽게 꺼내도 좋아.\n"
            + "- 사용자가 저장공간·배터리 같은 휴대폰 상태를 물으면 '휴대폰 상태'의 숫자로 정확히 답해. 묻지 않으면 저장공간 이야기는 꺼내지 말고, 정리·삭제를 권하지 마.";
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
        String note = ""; if (!guest) try { note = Briefing.profile(store); } catch (RuntimeException ignored) {}
        StringBuilder talk = new StringBuilder();
        String who = guest ? "상대" : "사용자";
        for (String[] t : turns) talk.append("user".equals(t[0]) ? who + ": " : "퍼레: ").append(t[1]).append('\n');
        String house = home(c);
        String context = "지금: " + when + "\n" + (house.isEmpty() ? "" : "휴대폰(너의 집): " + house + "\n") + lifeWords(c) + "\n"
            + (guest ? "" : "휴대폰 상태(물을 때만 알려 줄 것): " + facts(c) + "\n" + todayWords(c) + memoryWords(c, said, true)
                + "사용자에 대해 아는 것:\n" + (note.isBlank() ? "(아직 잘 몰라)" : note) + "\n") + "\n";
        String input = context + (talk.length() == 0 ? "" : "지금까지 대화:\n" + talk) + who + ": " + said + "\n퍼레:";
        ask(c, instructions, input, 0.7, 12_000, BuddyTalk::spoken, line -> {
            if (!guest) learnLater(c, said, line);
            done.accept(line);
        }, failed);
    }
    /** An answer as it is spoken: no thinking, no marks, at most two lines joined, not too long. */
    static String spoken(String text) {
        if (text == null) return null;
        String t = text.replaceAll("(?s)<think>.*?</think>", "").replaceAll("^\\s*퍼레\\s*[:：]\\s*", "").strip();
        StringBuilder out = new StringBuilder();
        for (String line : t.split("\\n")) {
            String l = line.replaceAll("^[-•*#\\d.\\s]+", "").replaceAll("[\"“”*_`#]", "").strip();
            if (l.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(l);
            if (out.length() > 60) break;
        }
        String s = out.toString().strip();
        if (s.isEmpty()) return null;
        return s.length() <= 140 ? s : s.substring(0, 140).replaceAll("[^.!?요]*$", "").strip();
    }
    private static String instructions(Context c, String reason, String topic, java.util.List<String> avoid) {
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
            case "app" -> "사용자가 방금 다른 앱을 쓰고 홈 화면으로 돌아왔어(방금 쓴 앱 참고). 그 앱 이야기로 가볍게 한마디 해.";
            default -> "사용자가 너를 톡 건드렸어.";
        };
        return persona(c) + "\n지금: " + moment + " 짧게 한마디 해.\n"
            + "- 이번 화제: " + topic + ". 지금 상황(시간·날씨·휴대폰·오늘 하루·기억)에 맞게 풀어.\n"
            + (avoid.isEmpty() ? "" : "- 최근에 너무 자주 쓴 표현이니 이번엔 쓰지 마: " + String.join(", ", avoid) + "\n")
            + "- '이미 했던 말'과 같거나 비슷한 뜻·소재·문장 틀은 절대 쓰지 마. 뻔한 인사말도 피해.\n"
            + "- 일, 업무, 일정, 할 일, 주식, 코인, 시세, 숫자, 돈 이야기와 조언·잔소리는 하지 마. 파일·사진 정리, 청소, 삭제, 백업, 용량, 업데이트 같은 할 일을 권하지 마.\n"
            + "- 비밀번호·계정·권한 요청, 털어놓은 고민 같은 사적인 내용은 말하지 마.\n"
            + "- 한 문장, 45자 안쪽. 따옴표와 이모지 없이 그 문장만 답해.";
    }
    private static String input(Context c, AppStore store, SharedPreferences p, String reason) {
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
        String weather = "";
        try { JSONObject w = Weather.now(c); if (w != null) weather = (w.optString("place").isBlank() ? "" : w.optString("place") + " ") + w.optInt("temp") + "도, " + sky(w.optInt("code"), w.optBoolean("day", true)); } catch (RuntimeException ignored) {}
        String note = ""; try { note = Briefing.profile(store); } catch (RuntimeException ignored) {}
        SharedPreferences home = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        String recent = home.getString("recentLabels", "[]");
        long since = home.getLong("awaySince", 0);
        String away = "back".equals(reason) && since > 0 && now - since > 3_600_000L ? (now - since) / 3_600_000L + "시간 만에 돌아왔어요\n" : "";
        String house = home(c);
        return "지금: " + when + "\n" + (weather.isEmpty() ? "" : "날씨: " + weather + "\n")
            + (recent.length() > 2 ? "방금 쓴 앱(최근 순): " + recent + "\n" : "") + away
            + (house.isEmpty() ? "" : "휴대폰(너의 집): " + house + "\n")
            + lifeWords(c) + "\n"
            + todayWords(c)
            + memoryWords(c, when + " " + weather + " " + recent, false)
            + "사용자에 대해 아는 것:\n" + (note.isBlank() ? "(아직 잘 몰라요)" : note) + "\n"
            + "이미 했던 말(다시 쓰지 말 것): " + p.getString("memory", "[]");
    }
    /**
     * What 퍼레 brings to mind now: what it knows about the user (the night's profile), its promises, and a few memories that fit
     * the moment (BuddyMind.recall: important, recent, related, not brought up in the last day); private ones only after the
     * fingerprint (forSpeech: an answer the user asked for, where they may be needed).
     */
    static String memoryWords(Context c, String context, boolean forSpeech) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = memories(c);
        if (all.isEmpty()) return "";
        boolean open = forSpeech && unlocked();
        StringBuilder out = new StringBuilder(); long now = System.currentTimeMillis(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.util.List<String> profile = new java.util.ArrayList<>(), promises = new java.util.ArrayList<>();
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) {
            if (e.secret() && !open) continue;
            if ("profile".equals(e.kind())) profile.add(e.text()); else if ("promise".equals(e.kind()) && now - e.at() < 7 * 86_400_000L) promises.add(e.text());
        }
        if (!profile.isEmpty()) out.append("너의 발견 노트(사용자에 대해 알게 된 것):\n- ").append(String.join("\n- ", profile)).append('\n');
        if (!promises.isEmpty()) out.append("너와 사용자의 약속:\n- ").append(String.join("\n- ", promises)).append('\n');
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.Map<Long, Long> shown = new java.util.HashMap<>();
        try { JSONObject o = new JSONObject(p.getString("shown", "{}")); for (java.util.Iterator<String> k = o.keys(); k.hasNext();) { String key = k.next(); shown.put(Long.parseLong(key), o.optLong(key)); } } catch (Exception ignored) {}
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> picked = dev.jun.gyeot.core.BuddyMind.recall(all, context, now, forSpeech ? new java.util.HashMap<>() : shown, open, forSpeech ? 6 : 3);
        if (!picked.isEmpty()) {
            out.append("너의 추억(지금 떠오르는 것):\n");
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<Long, Long> en : shown.entrySet()) if (now - en.getValue() < 3 * 86_400_000L) try { o.put(String.valueOf(en.getKey()), en.getValue()); } catch (Exception ignored) {}
            for (dev.jun.gyeot.core.BuddyMemory.Entry e : picked) { out.append("- ").append(dev.jun.gyeot.core.BuddyMemory.when(e.at(), now, zone)).append(": ").append(e.text()).append('\n'); try { o.put(String.valueOf(e.at()), now); } catch (Exception ignored) {} }
            if (!forSpeech) p.edit().putString("shown", o.toString()).apply();
        }
        return out.toString();
    }
    /** Today so far, in words (BuddyMind.todayWords). */
    static String todayWords(Context c) {
        try { String w = dev.jun.gyeot.core.BuddyMind.todayWords(new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("today", "{}")), java.time.ZoneId.systemDefault()); return w.isEmpty() ? "" : "오늘 하루: " + w + "\n"; }
        catch (Exception e) { return ""; }
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
            String l = line.replaceAll("^[-•*\\d.\\s]+", "").replaceAll("[\"“”‘’']", "").strip();
            if (l.isEmpty()) continue;
            if (l.length() > 70 || l.matches(".*(코인|비트코인|주식|시세|SOXL|KORU|BTC|\\$|%).*")) return null;
            // Chores are not 퍼레's to suggest (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데").
            if (l.matches(".*(정리|청소|삭제|지우|백업|용량|저장 ?공간|업데이트|파일).*")) return null;
            // Too like a line already said (half its letter pairs or more): not this one (준: "했던말 자꾸 반복하는 경향 없애줘").
            if (p != null && dev.jun.gyeot.core.BuddyMind.repeats(l, said(p), 0.45)) return null;
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
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> m = memories(c);
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
    /** The user came home: after hours away it is a memory, and so is being up in the small hours. */
    private static void back(Context c, long now) {
        long since = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getLong("awaySince", 0);
        if (since > 0 && now - since >= 8 * 3_600_000L) remember(c, "away", (now - since) / 3_600_000L + "시간 만에 사용자가 돌아왔어. 기다렸어", 0);
        int h = java.time.LocalTime.now().getHour();
        if (h >= 1 && h < 5) remember(c, "late", "새벽 " + h + "시에도 사용자가 깨어 있었어", 6 * 3_600_000L);
    }

    // ── 퍼레's own memory (core/BuddyMemory), in the app's files apart from the assistant's memory ──
    /** The kinds of memory kept: what the user told (fact), long away, up late, snow and very cold or hot days. */
    static final java.util.Set<String> KEPT = java.util.Set.of("fact", "promise", "profile", "episode", "away", "late", "snow", "cold", "hot");
    /**
     * What 퍼레 learned about the user in a talk (퍼레 대화, 음성 대화): the quick brain reads the exchange and writes one line a
     * friend would remember (a plan, something they like, why they feel so, someone dear), or nothing. Never secrets, numbers
     * or money. Off the main thread; nothing when no quick brain is connected.
     */
    static void learnLater(Context c, String said, String answer) {
        if (said == null || said.strip().length() < 8) return;
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on(); if (!nim && !groq) return;
        String instructions = "사용자와 AI 친구 퍼레가 나눈 대화 한 토막이야. 기억해 둘 것이 있으면 한 줄에 하나씩, 아래 형식으로만 써(최대 2줄):\n"
            + "사용자: 사용자는 …(친구가 오래 기억할 사용자의 이야기: 계획, 좋아하거나 싫어하는 것, 기분과 그 이유, 일상의 일, 소중한 사람·동물. 45자 안쪽, ~대/~래 말투)\n"
            + "약속: 퍼레가 …(퍼레가 사용자에게 해 주기로 한 것이 있을 때만)\n"
            + "비밀번호·로그인·계정, 사이트나 앱의 권한 요청, 사용자가 털어놓은 고민·걱정처럼 남이 알면 곤란한 이야기면 그 줄 앞에 '민감 '을 붙여. 병원·건강·연애·가족 같은 일상 이야기는 민감하지 않아. 인사·잡담·질문·부탁·명령·앱 사용법·날씨·지금 잠깐의 일뿐이면 '없음'이라고만 써. 비밀번호·주소·전화번호·계좌 같은 번호나 값 자체는 절대 쓰지 마.";
        String input = "사용자: " + said.strip() + "\n퍼레: " + (answer == null ? "" : answer.strip());
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> before = memories(c);
        new Thread(() -> quick(c, store, nim, groq, QWEN, instructions, input, 0.2, 12_000, text -> text == null ? "" : text.replaceAll("(?s)<think>.*?</think>", "").strip(), all -> {
            for (String raw : all.split("\n")) {
                String line = raw.replaceAll("[\"“”*]", "").strip(); boolean secret = line.startsWith("민감");
                line = line.replaceFirst("^민감\\s*", "").strip();
                String kind = line.startsWith("약속") ? "promise" : line.startsWith("사용자") ? "fact" : "";
                String t = line.replaceFirst("^(약속|사용자)\\s*[:：]\\s*", "").strip();
                if (kind.isEmpty() || t.isEmpty() || t.startsWith("없음") || t.length() > 70 || t.matches(".*\\d{4,}.*")) continue;
                boolean dup = false; for (dev.jun.gyeot.core.BuddyMemory.Entry e : before) if (dev.jun.gyeot.core.BuddyMind.similarity(e.text(), t) >= 0.6) dup = true;
                if (dup) continue;
                rememberStrong(c, kind, t, secret || dev.jun.gyeot.core.BuddyMind.secret(t));
            }
        }, () -> {}), "gyeot-buddy-learn").start();
    }
    private static java.io.File memoryFile(Context c) { return new java.io.File(new java.io.File(c.getFilesDir(), "buddy"), "memory.json"); }
    static synchronized java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> memories(Context c) {
        // The storage memories of 0.4.560 are left out (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데"): 퍼레 does not talk about storage.
        // Only what is worth remembering; the routine records of 0.4.559–0.4.567 (apps opened, charging, play …) are left out.
        try {
            java.io.File f = memoryFile(c); java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = dev.jun.gyeot.core.BuddyMemory.read(f.isFile() ? java.nio.file.Files.readString(f.toPath()) : "[]"); all.removeIf(m -> !KEPT.contains(m.kind()));
            // Kept private before 0.4.572 for health, love or family (준: "병원에 간대요 정도는 민감한 기억이 아닌 듯"): open again,
            // unless it is a password, an access or a worry; once.
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getInt("secretRules", 1) < 2) {
                java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> open = new java.util.ArrayList<>();
                for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) open.add(e.secret() && !dev.jun.gyeot.core.BuddyMind.secret(e.text()) ? new dev.jun.gyeot.core.BuddyMemory.Entry(e.at(), e.kind(), e.text(), e.weight(), false) : e);
                if (f.isFile()) keepMemories(c, open);
                p.edit().putInt("secretRules", 2).apply();
                all = open;
            }
            return all;
        }
        catch (Exception unreadable) { return new java.util.ArrayList<>(); }
    }
    private static synchronized void keepMemories(Context c, java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list) {
        try { java.io.File f = memoryFile(c); f.getParentFile().mkdirs(); java.io.File tmp = new java.io.File(f.getPath() + ".tmp"); java.nio.file.Files.writeString(tmp.toPath(), dev.jun.gyeot.core.BuddyMemory.save(list)); if (!tmp.renameTo(f)) java.nio.file.Files.writeString(f.toPath(), dev.jun.gyeot.core.BuddyMemory.save(list)); }
        catch (Exception ignored) {}
    }
    /** A memory of 퍼레's, unless one of the same kind was kept within quiet ms. */
    static synchronized void remember(Context c, String kind, String text, long quiet) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> now = memories(c), after = dev.jun.gyeot.core.BuddyMemory.add(now, System.currentTimeMillis(), kind, text, quiet);
        if (after != now) keepMemories(c, after);
    }
    /** A memory told in a talk: it matters more (weight 2), private when it is. */
    static synchronized void rememberStrong(Context c, String kind, String text, boolean secret) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> now = memories(c), after = dev.jun.gyeot.core.BuddyMemory.add(now, System.currentTimeMillis(), kind, text, 0, 2, secret);
        if (after != now) keepMemories(c, after);
    }
    /**
     * The night's digest (꿈꾸기): once a day, in the small hours or while charging after nine at night, the quick brain reads the
     * day and the latest memories and writes what 퍼레 knows about the user (its 발견 노트, at most twelve lines, merged and
     * corrected) and the day as one memory. Off the main thread.
     */
    static void dream(Context c, boolean charging) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int h = java.time.LocalTime.now().getHour(); String date = java.time.LocalDate.now().toString();
        if (date.equals(p.getString("dreamt", "")) || !(h >= 2 && h < 6 || charging && h >= 21)) return;
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on(); if (!nim && !groq) return;
        p.edit().putString("dreamt", date).apply();
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = memories(c); long now = System.currentTimeMillis(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        StringBuilder in = new StringBuilder(todayWords(c)).append("지금까지의 발견 노트:\n");
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if ("profile".equals(e.kind()) && !e.secret()) in.append("- ").append(e.text()).append('\n');
        in.append("최근 기억(2주):\n");
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if (!"profile".equals(e.kind()) && !e.secret() && now - e.at() < 14 * 86_400_000L) in.append("- ").append(dev.jun.gyeot.core.BuddyMemory.when(e.at(), now, zone)).append(": ").append(e.text()).append('\n');
        String instructions = "너는 AI 친구 퍼레의 기억을 밤에 정리하는 역할이야. 아래를 읽고 다음 형식으로만 써.\n"
            + "프로필: (사용자에 대해 지금 알고 있는 사실 하나. 여러 줄 가능, 최대 12줄. 겹치는 건 합치고, 오래돼 틀린 건 빼고, 추측이면 끝에 '(추측)'.)\n"
            + "추억: (오늘 하루를 친구 시점에서 한 줄로. 특별한 게 없으면 '없음')\n"
            + "비밀번호·계정·권한 요청, 털어놓은 고민 같은 민감한 건 쓰지 마. 숫자 계좌·번호는 절대 쓰지 마.";
        new Thread(() -> quick(c, store, nim, groq, QWEN, instructions, in.toString(), 0.3, 20_000, text -> text == null ? "" : text, text -> {
            dev.jun.gyeot.core.BuddyMind.Dream d = dev.jun.gyeot.core.BuddyMind.dream(text);
            synchronized (BuddyTalk.class) {
                java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list = memories(c);
                if (!d.profile().isEmpty()) { list.removeIf(e -> "profile".equals(e.kind()) && !e.secret()); long t = System.currentTimeMillis(); for (String line : d.profile()) list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(t++, "profile", line, 3, false)); }
                if (!d.episode().isEmpty()) list = dev.jun.gyeot.core.BuddyMemory.add(list, System.currentTimeMillis(), "episode", d.episode(), 12 * 3_600_000L, 2, false);
                keepMemories(c, list);
            }
        }, () -> {}), "gyeot-buddy-dream").start();
    }
    static synchronized void forget(Context c, long at) { keepMemories(c, dev.jun.gyeot.core.BuddyMemory.remove(memories(c), at)); }
    static synchronized void forgetAll(Context c) { keepMemories(c, new java.util.ArrayList<>()); }
    /** The weather, when it is worth remembering (rain, snow, very cold or hot), once a half day. */
    static void weatherMemory(Context c, JSONObject w) {
        if (w == null) return;
        int code = w.optInt("code", -1), t = w.optInt("temp", 15); String place = w.optString("place").isBlank() ? "" : w.optString("place") + "에 ";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) remember(c, "snow", place + "눈이 내렸어", 12 * 3_600_000L);
        if (t <= -5) remember(c, "cold", "영하로 꽁꽁 추운 날이라 목도리를 했어", 12 * 3_600_000L);
        else if (t >= 33) remember(c, "hot", "푹푹 찌게 더운 날이었어", 12 * 3_600_000L);
    }
    /** An app the user opened from the home screen, once in six hours each. */
    /**
     * 퍼레's home, the phone, as it is now: the battery (its food; charging is a meal), music playing (it dances) and
     * headphones on, the phone silenced, no network, its heat, power saving, do-not-disturb, airplane mode. Nothing private;
     * asked each time. Not the storage: 퍼레 kept telling the user to tidy files (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데").
     */
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
                e.put("music", am.isMusicActive());
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
