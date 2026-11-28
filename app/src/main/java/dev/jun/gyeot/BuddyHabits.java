package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.DayLog;
import dev.jun.gyeot.core.Habits;
import dev.jun.gyeot.core.SavedPlaces;
import dev.jun.gyeot.core.Triggers;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * 퍼레's routine suggestion on the phone (core/Habits; 준's plan, stage 2: "같은 행동이 반복되면 퍼레가 루틴을 딱 한 번 제안해요"):
 * looked for at most once a day, when the home screen first comes to the front (HomeActivity, off the main thread), from the
 * last three weeks of 하루 기록 (DayLog's book: a day's file read once while it stays the same) and the 조건 알림 kept. The one
 * found is said once in passing on the home screen with two small choices (board.js boardRoutine, gone in seconds unless
 * tapped); shown and not answered counts as let go (a week, then never). 열어 줘 makes the trigger (Triggers.make → BuddyTriggers.add:
 * that moment → open that app, every time) and says it back in one line; 괜찮아 is kept so it is never asked again.
 */
final class BuddyHabits {
    private BuddyHabits() {}
    static final String PREFS = "gyeot_habits";
    private static final String ASKED = "asked", SHOWN_DAY = "shownDay", CHECKED = "checked", PENDING = "pending";
    private static final Object LOCK = new Object();
    /** When now is (tests put their own clock). */
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String today(long now) { DayLog.Book b = DayLog.book(); return Instant.ofEpochMilli(now).atZone(b == null ? java.time.ZoneId.systemDefault() : b.zone).toLocalDate().toString(); }
    private static Map<String, Habits.Asked> asked(SharedPreferences p) { return Habits.load(p.getString(ASKED, "{}")); }
    private static Habits.Suggestion pending(SharedPreferences p) {
        try { String s = p.getString(PENDING, ""); return s.isEmpty() ? null : Habits.Suggestion.of(new JSONObject(s)); } catch (Exception broken) { return null; }
    }

    /**
     * The suggestion to show now, or null: looked for once a day (DayLog on), then the one found today until it is shown; none
     * once one was shown today. Off the main thread.
     */
    static Habits.Suggestion due(Context c) {
        DayLog.Book b = DayLog.book();
        if (b == null || !DayLog.on()) return null;
        long now = clock.getAsLong(); String day = today(now);
        SharedPreferences p = prefs(c);
        synchronized (LOCK) {
            if (day.equals(p.getString(SHOWN_DAY, ""))) return null;
            List<Triggers.Trigger> triggers = TriggerStore.list(c);
            if (day.equals(p.getString(CHECKED, ""))) {
                Habits.Suggestion s = pending(p);
                return s != null && Habits.askable(asked(p), s.key(), now) && !Habits.covered(triggers, s.moment()) ? s : null;
            }
            LocalDate d = LocalDate.parse(day);
            Habits.Suggestion s = Habits.suggest(b.range(d.minusDays(Habits.READ_DAYS - 1L), d), triggers, asked(p), p.getString(SHOWN_DAY, ""), now, b.zone, c.getPackageName());
            p.edit().putString(CHECKED, day).putString(PENDING, s == null ? "" : s.json().toString()).apply();
            if (s != null) TriggerWatch.log(c, "● 루틴 제안 찾음: " + Habits.when(s.moment()) + " → " + s.app() + " (" + s.days() + "일, " + s.hits() + "/" + s.times() + "번)");
            return s;
        }
    }
    /** For the page: {key, text, yes, no}. */
    static String page(Habits.Suggestion s) { return s.page().toString(); }

    /**
     * What the page heard: "shown" (said on the home screen: let go until answered, no other today), "yes" or "no" (the
     * user's tap). Returns what 퍼레 says back ("" none), and the tap that clears what the trigger still needs ("" none).
     */
    static String[] heard(Context c, String key, String what) {
        long now = clock.getAsLong();
        SharedPreferences p = prefs(c);
        Habits.Suggestion s;
        synchronized (LOCK) {
            s = pending(p);
            if (s == null || key == null || !s.key().equals(key)) return new String[]{"", ""};
            if ("shown".equals(what)) {
                p.edit().putString(ASKED, Habits.save(Habits.shown(asked(p), key, now))).putString(SHOWN_DAY, today(now)).apply();
                TriggerWatch.log(c, "● 루틴 제안: " + s.say());
                return new String[]{"", ""};
            }
            if (!"yes".equals(what) && !"no".equals(what)) return new String[]{"", ""};
            boolean yes = "yes".equals(what);
            p.edit().putString(ASKED, Habits.save(Habits.answered(asked(p), key, yes, now))).putString(PENDING, "").putString(SHOWN_DAY, today(now)).apply();
        }
        TriggerWatch.log(c, "● 루틴 제안 " + ("yes".equals(what) ? "받음: " : "거절: ") + Habits.when(s.moment()) + " → " + s.app());
        return "yes".equals(what) ? make(c, s, now) : new String[]{"알겠어, 다시 안 물어볼게.", ""};
    }
    /** The trigger a yes makes, kept and listened for (BuddyTriggers.add), said back in one line; what it still needs, one tap away. */
    static String[] make(Context c, Habits.Suggestion s, long now) {
        Triggers.Trigger t = s.trigger(now);
        if (Habits.VISIT.equals(s.kind())) {
            SavedPlaces.Saved saved = SavedPlaces.match(s.place(), ((GyeotApp) c.getApplicationContext()).store().savedPlaces());
            if (saved == null) return new String[]{"‘" + s.place() + "’ 자리를 몰라서 못 걸었어. 자주 가는 곳에 다시 저장해 줘.", ""};
            t = Triggers.make(t.when().at(s.place(), saved.lat(), saved.lng()), t.then(), t.mode(), t.said(), now);
        }
        Triggers.Trigger made;
        try { made = BuddyTriggers.add(c, t); }
        catch (IllegalStateException full) { return new String[]{full.getMessage(), ""}; }
        String back = "좋아! " + Triggers.said(made);
        String fix = TriggerWatch.problem(c, made)[1];
        if (fix.isEmpty()) return new String[]{back, ""};
        String why = switch (fix.replaceAll(":.*$", "")) {
            case "allow" -> fix.endsWith("location") ? "도착을 알려면 위치를 ‘항상 허용’해 줘. 눌러서 허용할 수 있어." : "차에 탄 걸 알려면 ‘근처 기기’ 권한이 필요해. 눌러서 허용해 줘.";
            case "pick" -> "어느 블루투스 기기가 차인지 한 번만 골라 줘.";
            default -> "";
        };
        TriggerFire.ask(c, fix, why);
        return new String[]{why.isEmpty() ? back : back + " " + why, fix};
    }
    /** For tests: nothing asked, nothing looked for. */
    static void clear(Context c) { synchronized (LOCK) { prefs(c).edit().clear().commit(); } }
}
