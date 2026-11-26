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

    private static String instructions(Context c, String reason) {
        String name = "들퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {}
        String moment = switch (reason) {
            case "back" -> "사용자가 방금 홈 화면으로 돌아왔어. 반갑게 먼저 말을 걸어.";
            case "pet" -> "사용자가 너를 쓰다듬어 줬어. 기분 좋은 반응을 해.";
            case "poke" -> "사용자가 너를 여러 번 콕콕 찔렀어. 어지러워하며 귀엽게 투덜거려.";
            default -> "사용자가 너를 톡 건드렸어.";
        };
        return "너는 사용자의 휴대폰 홈 화면에 사는 귀엽고 다정한 AI 친구 '" + name + "'이야. " + moment + " 생각하는 친구처럼 짧게 한마디 해.\n"
            + "- 감성적인 말, 재치 있는 유머(말장난·귀여운 너스레), 지금 상황을 알아챈 말(날씨·시간·방금 쓴 앱), 가벼운 질문, 응원, 엉뚱한 상상 중 지금 가장 어울리는 걸로.\n"
            + "- 사용자에 대해 아는 것을 자연스럽게 녹여. 사적인 내용을 그대로 읊지는 마.\n"
            + "- 일, 업무, 일정, 할 일, 주식, 코인, 시세, 숫자, 돈 이야기와 조언·잔소리는 하지 마.\n"
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
        return "지금: " + when + "\n" + (weather.isEmpty() ? "" : "날씨: " + weather + "\n")
            + (recent.length() > 2 ? "방금 쓴 앱(최근 순): " + recent + "\n" : "") + away
            + "사용자에 대해 아는 것:\n" + (note.isBlank() ? "(아직 잘 몰라요)" : note) + "\n"
            + "이미 했던 말(다시 쓰지 말 것): " + p.getString("memory", "[]");
    }
    /** The model's line as 퍼레 says it: the first line, no quotes, short; null when empty, about prices, or already said. */
    static String clean(String text, SharedPreferences p) {
        if (text == null) return null;
        String t = text.replaceAll("(?s)<think>.*?</think>", "").strip();
        for (String line : t.split("\\n")) {
            String l = line.replaceAll("^[-•*\\d.\\s]+", "").replaceAll("[\"“”‘’']", "").strip();
            if (l.isEmpty()) continue;
            if (l.length() > 70 || l.matches(".*(코인|비트코인|주식|시세|SOXL|KORU|BTC|\\$|%).*")) return null;
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
