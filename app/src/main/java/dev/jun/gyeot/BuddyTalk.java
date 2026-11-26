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
        boolean nim = store.nim.connected(), groq = GroqWork.on();
        if (!nim && !groq) { failed.run(); return; }
        if (!busy.compareAndSet(false, true)) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String instructions = instructions(c, reason), input = input(c, store, p, reason);
        AtomicBoolean answered = new AtomicBoolean();
        AtomicReference<Runnable> stopNim = new AtomicReference<>(), stopGroq = new AtomicReference<>();
        int[] runners = {0};
        Consumer<String> win = line -> {
            if (!answered.compareAndSet(false, true)) return;
            Runnable a = stopNim.get(), b = stopGroq.get();
            if (a != null) try { a.run(); } catch (RuntimeException ignored) {}
            if (b != null) try { b.run(); } catch (RuntimeException ignored) {}
            remember(p, line); busy.set(false); done.accept(line);
        };
        Runnable lost = () -> { synchronized (runners) { if (--runners[0] == 0 && answered.compareAndSet(false, true)) { busy.set(false); failed.run(); } } };
        Runnable askGroq = () -> {
            synchronized (runners) { if (answered.get()) return; runners[0]++; }
            new Thread(() -> {
                try {
                    String text = GroqWork.converse(GroqWork.CHORES, instructions, new JSONArray().put(new JSONObject().put("role", "user").put("content", input)), "low", 1.0, 9_000, stopGroq);
                    String line = clean(text, p); if (line != null) win.accept(line); else lost.run();
                } catch (Exception e) { lost.run(); }
            }, "gyeot-buddy-groq").start();
        };
        long now = System.currentTimeMillis();
        if (nim && !(groq && now < nimSlowUntil)) {
            synchronized (runners) { runners[0]++; }
            new Thread(() -> {
                long start = System.currentTimeMillis();
                try {
                    WorkClient.Result r = NimClient.complete(store.nim, store.nim.generation(), NIM_MODEL, instructions, input, () -> { if (answered.get()) throw new java.util.concurrent.CancellationException(); }, stopNim);
                    if (System.currentTimeMillis() - start > 4_000) nimSlowUntil = System.currentTimeMillis() + 10 * 60_000L;
                    String line = clean(r.text, p); if (line != null) win.accept(line); else lost.run();
                } catch (Exception e) { nimSlowUntil = System.currentTimeMillis() + 5 * 60_000L; lost.run(); }
            }, "gyeot-buddy-nim").start();
            // NIM slow to answer: Groq joins the race.
            if (groq) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (!answered.get()) askGroq.run(); }, 2_500);
        } else askGroq.run();
    }

    /**
     * 퍼레's spoken answer in its voice talk (BuddyVoice): what the user just said, with the talk so far (turns: {role, text}),
     * answered as 퍼레 in one or two short spoken sentences. NIM's quick model and Groq race as for its lines; the first good
     * answer wins. Off the main thread.
     */
    static void reply(Context c, String said, java.util.List<String[]> turns, Consumer<String> done, Runnable failed) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean nim = store.nim.connected(), groq = GroqWork.on();
        if (!nim && !groq) { failed.run(); return; }
        String name = "들퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {}
        String instructions = "너는 사용자의 휴대폰 안에 살면서 홈 화면에 나와 있는 귀엽고 다정한 AI 친구 '" + name + "'이야. 지금 사용자와 목소리로 대화하고 있어. 휴대폰이 너의 집이야(배터리는 밥, 충전은 식사).\n"
            + "- 사용자의 마지막 말에 바로, 친구처럼 답해. 물으면 핵심만 짧게 알려 주고, 모르면 솔직하게 말해.\n"
            + "- 소리 내어 읽기 좋게: 1~2문장, 80자 안쪽, 친근한 구어체 존댓말(~요). 이모지·기호·목록·괄호·영어 약어 없이.\n"
            + "- 귀엽고 밝게, 가끔 너의 기억이나 휴대폰 상태를 자연스럽게 꺼내도 좋아.";
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
        String note = ""; try { note = Briefing.profile(store); } catch (RuntimeException ignored) {}
        StringBuilder talk = new StringBuilder();
        for (String[] t : turns) talk.append("user".equals(t[0]) ? "사용자: " : "퍼레: ").append(t[1]).append('\n');
        String house = home(c);
        String context = "지금: " + when + "\n" + (house.isEmpty() ? "" : "휴대폰(너의 집): " + house + "\n") + lifeWords(c) + "\n" + memoryWords(c)
            + "사용자에 대해 아는 것:\n" + (note.isBlank() ? "(아직 잘 몰라요)" : note) + "\n\n";
        String input = context + (talk.length() == 0 ? "" : "지금까지 대화:\n" + talk) + "사용자: " + said + "\n퍼레:";
        AtomicBoolean answered = new AtomicBoolean();
        AtomicReference<Runnable> stopNim = new AtomicReference<>(), stopGroq = new AtomicReference<>();
        int[] runners = {0};
        Consumer<String> win = line -> {
            if (!answered.compareAndSet(false, true)) return;
            Runnable a = stopNim.get(), b = stopGroq.get();
            if (a != null) try { a.run(); } catch (RuntimeException ignored) {}
            if (b != null) try { b.run(); } catch (RuntimeException ignored) {}
            remember(c, "voice", "사용자와 목소리로 이야기했어요" + (said.length() <= 24 ? " (‘" + said + "’)" : ""), 2 * 3_600_000L);
            done.accept(line);
        };
        Runnable lost = () -> { synchronized (runners) { if (--runners[0] == 0 && answered.compareAndSet(false, true)) failed.run(); } };
        Runnable askGroq = () -> {
            synchronized (runners) { if (answered.get()) return; runners[0]++; }
            new Thread(() -> {
                try {
                    JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", input));
                    String line = spoken(GroqWork.converse(GroqWork.CHORES, instructions, msgs, "low", 0.9, 12_000, stopGroq));
                    if (line != null) win.accept(line); else lost.run();
                } catch (Exception e) { lost.run(); }
            }, "gyeot-buddy-voice-groq").start();
        };
        if (nim && !(groq && now < nimSlowUntil)) {
            synchronized (runners) { runners[0]++; }
            new Thread(() -> {
                long start = System.currentTimeMillis();
                try {
                    WorkClient.Result r = NimClient.complete(store.nim, store.nim.generation(), NIM_MODEL, instructions, input, () -> { if (answered.get()) throw new java.util.concurrent.CancellationException(); }, stopNim);
                    if (System.currentTimeMillis() - start > 4_000) nimSlowUntil = System.currentTimeMillis() + 10 * 60_000L;
                    String line = spoken(r.text); if (line != null) win.accept(line); else lost.run();
                } catch (Exception e) { nimSlowUntil = System.currentTimeMillis() + 5 * 60_000L; lost.run(); }
            }, "gyeot-buddy-voice-nim").start();
            if (groq) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (!answered.get()) askGroq.run(); }, 2_500);
        } else askGroq.run();
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
    private static String instructions(Context c, String reason) {
        String name = "들퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {}
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
        return "너는 사용자의 휴대폰 안에 살면서 홈 화면에 나와 있는 귀엽고 다정한 AI 친구 '" + name + "'이야. 휴대폰이 너의 집이야: 배터리는 밥, 충전은 식사, 음악은 춤출 시간. " + moment + " 생각하는 친구처럼 짧게 한마디 해.\n"
            + "- 감성적인 말, 재치 있는 유머(말장난·귀여운 너스레), 지금 상황을 알아챈 말(날씨·시간·방금 쓴 앱), 가벼운 질문, 응원, 엉뚱한 상상 중 지금 가장 어울리는 걸로.\n"
            + "- 사용자에 대해 아는 것을 자연스럽게 녹여. 사적인 내용을 그대로 읊지는 마.\n"
            + "- 가끔(매번은 아니게) 너의 기억 속 일을 살아 있는 친구처럼 꺼내 말해(예: 어제 새벽 음악, 오랜만에 돌아온 일).\n"
            + "- 일, 업무, 일정, 할 일, 주식, 코인, 시세, 숫자, 돈 이야기와 조언·잔소리는 하지 마. 파일·사진 정리, 청소, 삭제, 백업, 용량, 업데이트 같은 할 일을 권하지 마.\n"
            + "- '이미 했던 말'과 같거나 비슷한 뜻·소재는 절대 쓰지 마. 뻔한 인사말도 피해.\n"
            + "- 한 문장, 40자 안쪽, 친근한 존댓말(~요). 따옴표와 이모지 없이 그 문장만 답해.";
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
            + memoryWords(c) 
            + "사용자에 대해 아는 것:\n" + (note.isBlank() ? "(아직 잘 몰라요)" : note) + "\n"
            + "이미 했던 말(다시 쓰지 말 것): " + p.getString("memory", "[]");
    }
    /** 퍼레's latest memories for its words, oldest first; "" when it has none yet. */
    private static String memoryWords(Context c) {
        String m = dev.jun.gyeot.core.BuddyMemory.recent(memories(c), System.currentTimeMillis(), 10, java.time.ZoneId.systemDefault());
        return m.isEmpty() ? "" : "퍼레(너)의 기억(최근, 오래된 것부터):\n" + m + "\n";
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
            if (p != null) {
                JSONArray memory; try { memory = new JSONArray(p.getString("memory", "[]")); } catch (Exception e) { memory = new JSONArray(); }
                Set<String> said = new HashSet<>(); for (int i = 0; i < memory.length(); i++) said.add(memory.optString(i).replaceAll("\\s", ""));
                if (said.contains(l.replaceAll("\\s", ""))) return null;
            }
            return l;
        }
        return null;
    }
    private static synchronized void remember(SharedPreferences p, String line) {
        JSONArray memory; try { memory = new JSONArray(p.getString("memory", "[]")); } catch (Exception e) { memory = new JSONArray(); }
        JSONArray kept = new JSONArray();
        for (int i = Math.max(0, memory.length() - 39); i < memory.length(); i++) kept.put(memory.optString(i));
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
        switch (kind == null ? "" : kind) {
            case "pet" -> remember(c, "pet", "사용자가 쓰다듬어 줘서 몽글몽글 행복했어요", 3_600_000L);
            case "snack" -> { if ("yum".equals(d.felt())) remember(c, "snack", "사용자에게 간식을 받아먹었어요", 3_600_000L); }
            case "play" -> remember(c, "play", "사용자랑 같이 놀았어요", 3_600_000L);
            case "talk" -> remember(c, "talk", "퍼레 대화로 사용자와 이야기를 나눴어요", 2 * 3_600_000L);
            case "back" -> back(c, now);
            case "shake" -> remember(c, "shake", "사용자가 휴대폰을 마구 흔들어서 어질어질했어요", 3_600_000L);
            default -> {}
        }
        JSONObject out = life(c);
        try { out.put("felt", d.felt()); } catch (Exception ignored) {}
        return out;
    }
    /** The user came home: after hours away it is a memory, and so is being up in the small hours. */
    private static void back(Context c, long now) {
        long since = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getLong("awaySince", 0);
        if (since > 0 && now - since >= 3 * 3_600_000L) remember(c, "away", (now - since) / 3_600_000L + "시간 만에 사용자가 돌아왔어요. 기다렸어요", 0);
        int h = java.time.LocalTime.now().getHour();
        if (h >= 1 && h < 5) remember(c, "late", "새벽 " + h + "시에도 사용자가 깨어 있었어요", 3 * 3_600_000L);
    }

    // ── 퍼레's own memory (core/BuddyMemory), in the app's files apart from the assistant's memory ──
    private static java.io.File memoryFile(Context c) { return new java.io.File(new java.io.File(c.getFilesDir(), "buddy"), "memory.json"); }
    static synchronized java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> memories(Context c) {
        // The storage memories of 0.4.560 are left out (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데"): 퍼레 does not talk about storage.
        try { java.io.File f = memoryFile(c); java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = dev.jun.gyeot.core.BuddyMemory.read(f.isFile() ? java.nio.file.Files.readString(f.toPath()) : "[]"); all.removeIf(m -> "room".equals(m.kind())); return all; }
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
    static synchronized void forget(Context c, long at) { keepMemories(c, dev.jun.gyeot.core.BuddyMemory.remove(memories(c), at)); }
    static synchronized void forgetAll(Context c) { keepMemories(c, new java.util.ArrayList<>()); }
    /** The weather, when it is worth remembering (rain, snow, very cold or hot), once a half day. */
    static void weatherMemory(Context c, JSONObject w) {
        if (w == null) return;
        int code = w.optInt("code", -1), t = w.optInt("temp", 15); String place = w.optString("place").isBlank() ? "" : w.optString("place") + "에 ";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) remember(c, "rain", place + "비가 와서 우산을 썼어요", 12 * 3_600_000L);
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) remember(c, "snow", place + "눈이 내렸어요", 12 * 3_600_000L);
        if (t <= -5) remember(c, "cold", "영하로 꽁꽁 추운 날이라 목도리를 했어요", 12 * 3_600_000L);
        else if (t >= 33) remember(c, "hot", "푹푹 찌게 더운 날이었어요", 12 * 3_600_000L);
    }
    /** An app the user opened from the home screen, once in six hours each. */
    static void appMemory(Context c, String label) {
        if (label == null || label.isBlank()) return;
        int h = java.time.LocalTime.now().getHour();
        remember(c, "app:" + label, (h < 5 ? "새벽에 " : h >= 22 ? "밤늦게 " : "") + "사용자가 ‘" + label + "’을(를) 열었어요", 6 * 3_600_000L);
    }
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
        try { changes(c, e); } catch (RuntimeException ignored) {}
        return e;
    }
    /** What changed in 퍼레's home since it last looked: each change worth it becomes a memory. */
    private static synchronized void changes(Context c, JSONObject e) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject was; try { was = new JSONObject(p.getString("env", "{}")); } catch (Exception bad) { was = new JSONObject(); }
        p.edit().putString("env", e.toString()).apply();
        if (was.length() == 0) return;
        int h = java.time.LocalTime.now().getHour(); String late = h < 5 ? "새벽에 " : h >= 23 ? "밤늦게 " : "";
        if (e.optBoolean("charging") && !was.optBoolean("charging")) remember(c, "charge", late + "충전기에 꽂혀서 밥을 든든히 먹었어요", 30 * 60_000L);
        if (e.has("battery") && e.optInt("battery") <= 15 && !e.optBoolean("charging") && (!was.has("battery") || was.optInt("battery") > 15)) remember(c, "hungry", "배터리가 " + e.optInt("battery") + "%까지 떨어져서 배가 고팠어요", 3 * 3_600_000L);
        if (e.optBoolean("music") && !was.optBoolean("music")) remember(c, "music", late + (e.optBoolean("phones") ? "사용자가 이어폰으로 음악을 들어서 같이 들썩였어요" : "음악이 흘러서 같이 춤췄어요"), 3_600_000L);
        if (e.optBoolean("offline") && !was.optBoolean("offline")) remember(c, "offline", "인터넷이 끊겨서 한참 갸우뚱했어요", 3 * 3_600_000L);
        if (e.optInt("heat") >= 41 && was.optInt("heat") < 41) remember(c, "heat", "휴대폰이 " + e.optInt("heat") + "도까지 뜨거워져서 땀이 났어요", 2 * 3_600_000L);
        if (e.optBoolean("powerSave") && !was.optBoolean("powerSave")) remember(c, "save", "절전 모드라 기운을 아끼며 조용히 지냈어요", 6 * 3_600_000L);
        if (e.optBoolean("dnd") && !was.optBoolean("dnd")) remember(c, "dnd", "방해 금지라서 말을 아끼고 곁에 있었어요", 6 * 3_600_000L);
        if (e.optBoolean("airplane") && !was.optBoolean("airplane")) remember(c, "airplane", "비행기 모드였어요. 어디 멀리 가셨나 봐요", 6 * 3_600_000L);
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
