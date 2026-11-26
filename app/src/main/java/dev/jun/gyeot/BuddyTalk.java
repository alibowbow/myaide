package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What 퍼레 says on the home screen (준: "퍼레가 의미없이 자꾸 코인시세를 반복해서 말해", "생각을 하고 말하게 해줘", "감성적인 이야기도
 * 좋고", "나를 이해한 바탕으로 일상적인 말걸기, 업무적인 것 말고", "유우머도 갖춰서"): a few short everyday lines written by the
 * NVIDIA model the app is connected to, from 퍼레's note about the user, the hour, the day and the weather; kept until the part
 * of the day changes or three hours pass. No prices, numbers, work or schedule in them. Without the model, none (the page has
 * its own gentle lines for the hour).
 */
final class BuddyTalk {
    private BuddyTalk() {}
    private static final String PREFS = "buddyTalk";
    private static final long KEEP_MS = 3 * 3_600_000L;
    private static final AtomicBoolean making = new AtomicBoolean();

    /** The lines for now (maybe none), starting new ones in the background when these are old. Off the main thread. */
    static JSONArray lines(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = p.getLong("at", 0), now = System.currentTimeMillis();
        String part = part(now);
        JSONArray kept; try { kept = new JSONArray(p.getString("lines", "[]")); } catch (Exception e) { kept = new JSONArray(); }
        boolean fresh = now - at < KEEP_MS && part.equals(p.getString("part", ""));
        if (!fresh) make(c, p, now, part);
        return fresh ? kept : new JSONArray();
    }
    private static String part(long now) {
        int h = Integer.parseInt(new SimpleDateFormat("H", Locale.ROOT).format(new Date(now)));
        return h < 5 ? "새벽" : h < 11 ? "아침" : h < 14 ? "점심 무렵" : h < 18 ? "오후" : h < 22 ? "저녁" : "밤";
    }
    private static void make(Context c, SharedPreferences p, long now, String part) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        if (!store.nim.connected() || !making.compareAndSet(false, true)) return;
        // At most one try in ten minutes when it fails (offline), so the home screen never keeps asking.
        if (now - p.getLong("tried", 0) < 10 * 60_000L) { making.set(false); return; }
        p.edit().putLong("tried", now).apply();
        Thread t = new Thread(() -> {
            try {
                String name = "들퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {}
                String note = ""; try { note = Briefing.profile(store); } catch (RuntimeException ignored) {}
                String weather = "";
                try { JSONObject w = Weather.now(c); if (w != null) weather = (w.optString("place").isBlank() ? "" : w.optString("place") + " ") + w.optInt("temp") + "도, " + sky(w.optInt("code"), w.optBoolean("day", true)); } catch (RuntimeException ignored) {}
                String when = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
                String instructions = "너는 사용자의 휴대폰 홈 화면에 사는 귀엽고 다정한 AI 친구 '" + name + "'이야. 사용자가 홈 화면을 볼 때 먼저 건넬 짧은 한마디를 8개 만들어.\n"
                    + "- 일상적이고 따뜻한 말, 감성적인 말, 가벼운 유머(재치 있는 말장난, 귀여운 너스레)를 골고루 섞어. 사람 친구가 툭 건네는 말처럼.\n"
                    + "- 사용자에 대해 아는 것(관심사, 요즘 빠져 있는 것, 생활 패턴)을 자연스럽게 녹여. 하지만 사적인 내용을 그대로 읊지는 마.\n"
                    + "- 지금 시간대와 요일, 날씨에 어울리게.\n"
                    + "- 일, 업무, 일정, 할 일, 주식, 코인, 시세, 숫자, 돈 이야기는 하지 마. 조언이나 잔소리도 하지 마.\n"
                    + "- 한 줄에 35자 안쪽, 친근한 존댓말(~요). 이모지와 따옴표는 쓰지 마. 같은 말투를 되풀이하지 마.\n"
                    + "JSON 문자열 배열로만 답해. 예: [\"…\", \"…\"]";
                String input = "지금: " + when + " (" + part + ")\n" + (weather.isEmpty() ? "" : "날씨: " + weather + "\n")
                    + "사용자에 대해 아는 것:\n" + (note.isBlank() ? "(아직 잘 몰라요)" : note) + "\n"
                    + "얼마 전에 했던 말(겹치지 않게): " + p.getString("lines", "[]");
                WorkClient.Result r = NimClient.complete(store.nim, store.nim.generation(), store.nim.model(), instructions, input, () -> {}, new AtomicReference<>());
                JSONArray made = parse(r.text);
                if (made.length() > 0) p.edit().putString("lines", made.toString()).putLong("at", now).putString("part", part).apply();
            } catch (Exception offline) { /* the page's own lines meanwhile */ }
            finally { making.set(false); }
        }, "gyeot-buddy-talk");
        t.setDaemon(true); t.start();
    }
    /** The lines in the model's answer: short, no prices or numbers left in, at most ten. */
    static JSONArray parse(String text) {
        JSONArray out = new JSONArray();
        if (text == null) return out;
        int from = text.indexOf('['), to = text.lastIndexOf(']');
        if (from < 0 || to <= from) return out;
        try {
            JSONArray a = new JSONArray(text.substring(from, to + 1));
            for (int i = 0; i < a.length() && out.length() < 10; i++) {
                String line = a.optString(i, "").replaceAll("[\"“”]", "").strip();
                if (line.isEmpty() || line.length() > 60 || line.matches(".*(코인|비트코인|주식|시세|SOXL|KORU|BTC|\\$|%).*")) continue;
                out.put(line);
            }
        } catch (Exception unreadable) { /* none */ }
        return out;
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
