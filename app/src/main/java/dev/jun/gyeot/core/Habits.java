package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 퍼레's routine suggestion (준's plan, stage 2: "같은 행동이 반복되면 퍼레가 루틴을 딱 한 번 제안해요"). From the 하루 타임라인
 * (core/DayLog), a "moment → app" habit: an app that comes up within a few minutes after the same moment (the car's Bluetooth
 * connecting, the charger plugged in, arriving at a saved place) on at least MIN_DAYS days within SPAN_DAYS, on most of that
 * moment's times (SHARE), and well above how often the app comes up anyway (an app this likely in such a window by chance,
 * BUSY, is no habit of a moment: one used all day long; so SHARE is always more than twice its chance). Never the home screen or the system's own screens, 들퍼레 itself, the phone, or a bank's, a card's,
 * securities' or a pay app (퍼레 does not talk money). A pair that already has a 조건 알림 (or whose moment already opens
 * something), was answered, or was let go twice is never asked; let go once, not again for IGNORE_WAIT_MS; at most one a day.
 * Also the words said ("차 타면 네이버 지도 자주 켜더라. 다음부터 차 타면 바로 열어 줄까?") and the trigger a yes makes
 * (core/Triggers.make: that moment → open that app, every time). (core/Routines is the user's own 한마디 루틴, not this.) Pure Java.
 */
public final class Habits {
    private Habits() {}
    /** The moments: the car's Bluetooth connecting, the charger plugged in, arriving at a saved place. */
    public static final String CAR = "car", CHARGE = "charge", VISIT = "visit";
    /** An app counts for a moment when it comes up from BEFORE_MS before it (the link or the fix may be late) to AFTER_MS after. */
    public static final long AFTER_MS = 5 * 60_000L, BEFORE_MS = 60_000L, DAY_MS = 24 * 3_600_000L, IGNORE_WAIT_MS = 7 * DAY_MS;
    /** Days read; the span its days must fall in; how many days; how recent the last; how often it may be let go; records kept. */
    public static final int READ_DAYS = 21, SPAN_DAYS = 14, MIN_DAYS = 3, RECENT_DAYS = 7, IGNORES = 2, KEPT = 120;
    /** Its share of the moment's times; an app this likely in any such window anyway is no habit. */
    public static final double SHARE = 0.6, BUSY = 0.25;
    /** The waking day an app can come up in (how often it comes up anyway). */
    static final double AWAKE_MS = 16 * 3_600_000.0;
    /** The two choices under the question. */
    public static final String YES = "열어 줘", NO = "괜찮아";
    /** What became of a pair asked: shown and not answered (let go), answered yes or no. */
    public static final String SHOWN = "shown", ACCEPTED = "yes", REFUSED = "no";

    // ── moments ───────────────────────────────────────────────
    /** A moment the day log saw: its kind, the saved place's word (VISIT), the car's address (CAR; "" unknown). */
    public record Moment(String kind, String place, String device) {
        public String key() { return VISIT.equals(kind) ? VISIT + ":" + place : kind; }
    }
    /** The moment a line is, or null: the car connecting, the charger plugged in, arriving at a saved place (not their ends). */
    public static Moment moment(DayLog.Event e) {
        if (e == null) return null;
        String g = e.group();
        if (DayLog.CAR.equals(e.kind()) && g.startsWith("car:in:")) return new Moment(CAR, "", g.substring(7));
        if (DayLog.CHARGE.equals(e.kind()) && g.equals("charge:in")) return new Moment(CHARGE, "", "");
        if (DayLog.VISIT.equals(e.kind()) && g.startsWith("visit:in:") && !e.place().isBlank()) return new Moment(VISIT, e.place().strip(), "");
        return null;
    }

    // ── apps never suggested ──────────────────────────────────
    private static final Pattern SYSTEM = Pattern.compile("(?i)^(?:android|com\\.android\\.systemui|com\\.android\\.settings|com\\.samsung\\.android\\.app\\.settings|com\\.android\\.(?:permissioncontroller|packageinstaller)"
        + "|com\\.google\\.android\\.(?:permissioncontroller|packageinstaller)|com\\.samsung\\.android\\.app\\.(?:taskedge|cocktailbarservice|routines))$|launcher|nexuslauncher|trebuchet|lawnchair|teslacoilsw|\\.home$|recents|systemui");
    private static final Pattern SYSTEM_LABEL = Pattern.compile("(?i)^(?:설정|settings|홈|홈 ?화면|one ui 홈|system ?ui|시스템 ?ui|런처|launcher)$");
    private static final Pattern PHONE = Pattern.compile("(?i)dialer|incallui|telecom|com\\.android\\.phone|telephonyui|callui|contacts");
    private static final Pattern PHONE_LABEL = Pattern.compile("(?i)^(?:전화|phone|연락처|contacts|통화)$");
    /** Never suggested: no app, the home screen or the system's own screens, 들퍼레 itself (own: its package), the phone, money. */
    public static boolean skipped(String pkg, String label, String own) {
        String p = pkg == null ? "" : pkg.strip(), l = label == null ? "" : label.strip();
        if (p.isEmpty() && l.isEmpty()) return true;
        if (own != null && !own.isBlank() && (p.equals(own) || p.startsWith(own + "."))) return true;
        if (l.equals(Brand.NAME)) return true;
        if (!p.isEmpty() && (SYSTEM.matcher(p).find() || PHONE.matcher(p).find())) return true;
        if (SYSTEM_LABEL.matcher(l).matches() || PHONE_LABEL.matcher(l).matches()) return true;
        return NoticeActions.moneyApp(p, l);
    }

    // ── what was asked ────────────────────────────────────────
    /** A pair asked: SHOWN (not answered: let go), ACCEPTED or REFUSED; how many times it was shown; when last. */
    public record Asked(String state, int times, long at) {
        public JSONObject json() { return obj("s", state, "n", times, "t", at); }
        public static Asked of(JSONObject o) {
            if (o == null || !List.of(SHOWN, ACCEPTED, REFUSED).contains(o.optString("s"))) return null;
            return new Asked(o.optString("s"), Math.max(1, o.optInt("n", 1)), o.optLong("t"));
        }
    }
    public static String save(Map<String, Asked> all) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Asked> e : (all == null ? Map.<String, Asked>of() : all).entrySet()) put(o, e.getKey(), e.getValue().json());
        return o.toString();
    }
    /** The pairs asked as kept; anything unreadable is left out. */
    public static Map<String, Asked> load(String json) {
        Map<String, Asked> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(json == null || json.isBlank() ? "{}" : json);
            for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) { String key = k.next(); Asked a = Asked.of(o.optJSONObject(key)); if (a != null) out.put(key, a); }
        } catch (Exception broken) { /* none */ }
        return out;
    }
    /** Whether a pair may be asked now: never asked, or let go once at least IGNORE_WAIT_MS ago; never once answered or let go twice. */
    public static boolean askable(Map<String, Asked> asked, String key, long now) {
        Asked a = asked == null ? null : asked.get(key);
        if (a == null) return true;
        if (!SHOWN.equals(a.state())) return false;
        return a.times() < IGNORES && now - a.at() >= IGNORE_WAIT_MS;
    }
    /** The pairs after one was shown (until an answer comes, it counts as let go). */
    public static Map<String, Asked> shown(Map<String, Asked> asked, String key, long now) {
        Map<String, Asked> out = new LinkedHashMap<>(asked == null ? Map.of() : asked);
        Asked was = out.remove(key);
        if (was != null && !SHOWN.equals(was.state())) { out.put(key, was); return trim(out); }
        out.put(key, new Asked(SHOWN, was == null ? 1 : was.times() + 1, now));
        return trim(out);
    }
    /** The pairs after one was answered: yes or no, never asked again. */
    public static Map<String, Asked> answered(Map<String, Asked> asked, String key, boolean yes, long now) {
        Map<String, Asked> out = new LinkedHashMap<>(asked == null ? Map.of() : asked);
        Asked was = out.remove(key);
        out.put(key, new Asked(yes ? ACCEPTED : REFUSED, was == null ? 1 : was.times(), now));
        return trim(out);
    }
    /** At most KEPT: the oldest go first (they are in the order last changed). */
    private static Map<String, Asked> trim(Map<String, Asked> all) {
        while (all.size() > KEPT) all.remove(all.keySet().iterator().next());
        return all;
    }

    // ── what is found ─────────────────────────────────────────
    /**
     * One habit found. key: the moment and the app (what is asked once); kind, place, device: the moment; app: its name as shown,
     * pkg: its package ("" unknown); days: the days it held in its span; hits and times: how often it came of the moment's times
     * since; last: the last time it came; say: the words said.
     */
    public record Suggestion(String key, String kind, String place, String device, String app, String pkg, int days, int hits, int times, long last, String say) {
        public Moment moment() { return new Moment(kind, place, device); }
        /** The trigger's condition: the car connecting (by its address when known), the charger plugged in, arriving at the place (found later). */
        public Triggers.Condition condition() {
            return switch (kind) {
                case CAR -> Triggers.Condition.bluetooth(Triggers.CONNECT, "차", device);
                case CHARGE -> Triggers.Condition.power(Triggers.PLUGGED, 0);
                default -> Triggers.Condition.place(Triggers.ARRIVE, place, Double.NaN, Double.NaN, 0);
            };
        }
        public Triggers.Action action() { return Triggers.Action.open(app); }
        /** The words the trigger keeps as the user's ("차에 타면 매번 네이버 지도 열어 줘"). */
        public String heard() {
            String when = switch (kind) { case CAR -> "차에 타면"; case CHARGE -> "충전기 꽂으면"; default -> place + "에 도착하면"; };
            return when + " 매번 " + app + " 열어 줘";
        }
        /** The trigger a yes makes: this moment → open this app, every time (core/Triggers.make). */
        public Triggers.Trigger trigger(long now) { return Triggers.make(condition(), action(), Triggers.EVERY, heard(), now); }
        public double share() { return times <= 0 ? 0 : hits / (double) times; }
        public JSONObject json() { return obj("key", key, "kind", kind, "place", place, "device", device, "app", app, "pkg", pkg, "days", days, "hits", hits, "times", times, "last", last, "say", say); }
        /** For the home screen: {key, text, yes, no}. */
        public JSONObject page() { return obj("key", key, "text", say, "yes", YES, "no", NO); }
        public static Suggestion of(JSONObject o) {
            if (o == null || o.optString("key").isEmpty() || o.optString("app").isBlank() || !List.of(CAR, CHARGE, VISIT).contains(o.optString("kind"))) return null;
            return new Suggestion(o.optString("key"), o.optString("kind"), o.optString("place"), o.optString("device"), o.optString("app"), o.optString("pkg"),
                o.optInt("days"), o.optInt("hits"), o.optInt("times"), o.optLong("last"), o.optString("say"));
        }
    }
    /** "차 타면", "충전기 꽂으면", "집에 오면". */
    public static String when(Moment m) {
        return switch (m.kind()) { case CAR -> "차 타면"; case CHARGE -> "충전기 꽂으면"; default -> m.place() + "에 오면"; };
    }
    /** The words said once, in passing: "차 타면 네이버 지도 자주 켜더라. 다음부터 차 타면 바로 열어 줄까?" (never money: no such app). */
    public static String say(Moment m, String app) { String w = when(m); return w + " " + app + " 자주 켜더라. 다음부터 " + w + " 바로 열어 줄까?"; }

    private record Session(String id, String pkg, String label, long at, LocalDate day) {}
    private record Seen(Moment m, long at, LocalDate day) {}
    private record Held(int days, int hits, int times, long last) {}
    private static LocalDate day(long at, ZoneId zone) { return Instant.ofEpochMilli(at).atZone(zone).toLocalDate(); }
    private static String id(String pkg, String label) { return pkg.isEmpty() ? "app:" + label : pkg; }

    /**
     * The one habit to suggest now, or null: from the day log's lines (the last READ_DAYS days; others are left out), the
     * triggers kept (any, on or off), the pairs asked before, and the day one was last shown (lastShown, "yyyy-MM-dd": at most
     * one a day). own: 들퍼레's package. The strongest pair wins: the most days, then the largest share, then the latest.
     */
    public static Suggestion suggest(List<DayLog.Event> events, List<Triggers.Trigger> triggers, Map<String, Asked> asked, String lastShown, long now, ZoneId zone, String own) {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        LocalDate today = day(now, z);
        if (today.toString().equals(lastShown) || events == null) return null;
        LocalDate first = today.minusDays(READ_DAYS - 1L);
        List<Seen> seen = new ArrayList<>(); List<Session> sessions = new ArrayList<>(); Set<LocalDate> tracked = new HashSet<>();
        for (DayLog.Event e : events) {
            if (e == null || e.at() > now) continue;
            LocalDate d = day(e.at(), z);
            if (d.isBefore(first)) continue;
            if (DayLog.APP.equals(e.kind())) {
                // A day the apps were seen at all (폰 조작 on): only those days' moments count.
                tracked.add(d);
                String pkg = e.group().startsWith("app:") ? e.group().substring(4).strip() : "";
                if (skipped(pkg, e.app(), own)) continue;
                sessions.add(new Session(id(pkg, e.app().strip()), pkg, e.app().strip(), e.at(), d));
                continue;
            }
            Moment m = moment(e);
            if (m != null) seen.add(new Seen(m, e.at(), d));
        }
        if (seen.isEmpty() || sessions.isEmpty() || tracked.isEmpty()) return null;
        sessions.sort(Comparator.comparingLong(Session::at));
        seen.sort(Comparator.comparingLong(Seen::at));
        Map<String, Integer> opened = new HashMap<>(); Map<String, Session> latest = new HashMap<>();
        for (Session s : sessions) { opened.merge(s.id(), 1, Integer::sum); latest.put(s.id(), s); }
        Map<String, List<Seen>> byMoment = new LinkedHashMap<>();
        for (Seen s : seen) if (tracked.contains(s.day())) byMoment.computeIfAbsent(s.m().key(), k -> new ArrayList<>()).add(s);
        Suggestion best = null;
        for (List<Seen> times : byMoment.values()) {
            Map<String, List<Seen>> hits = new LinkedHashMap<>();
            for (Seen s : times) {
                Set<String> apps = new LinkedHashSet<>();
                for (Session x : sessions) {
                    if (x.at() < s.at() - BEFORE_MS) continue;
                    if (x.at() > s.at() + AFTER_MS) break;
                    apps.add(x.id());
                }
                for (String app : apps) hits.computeIfAbsent(app, k -> new ArrayList<>()).add(s);
            }
            for (Map.Entry<String, List<Seen>> h : hits.entrySet()) {
                String app = h.getKey();
                // How likely the app comes up in such a window anyway: its sessions a day the apps were seen, over the waking day.
                double base = Math.min(1, opened.getOrDefault(app, 0) / (double) tracked.size() * (BEFORE_MS + AFTER_MS) / AWAKE_MS);
                if (base > BUSY) continue;
                Held held = held(times, h.getValue(), today);
                if (held == null) continue;
                List<Seen> got = h.getValue();
                Moment m = got.get(got.size() - 1).m();
                Session s = latest.get(app);
                String key = m.key() + "|" + app;
                if (!askable(asked, key, now) || covered(triggers, m)) continue;
                Suggestion one = new Suggestion(key, m.kind(), m.place(), m.device(), s.label(), s.pkg(), held.days(), held.hits(), held.times(), held.last(), say(m, s.label()));
                if (best == null || one.days() > best.days() || one.days() == best.days() && (one.share() > best.share() || one.share() == best.share() && one.last() > best.last())) best = one;
            }
        }
        return best;
    }
    /**
     * Whether an app's times after a moment make a habit: on MIN_DAYS days or more within some SPAN_DAYS, the last within
     * RECENT_DAYS, and from that span's first day on, on at least SHARE of the moment's times.
     */
    private static Held held(List<Seen> times, List<Seen> hits, LocalDate today) {
        TreeSet<LocalDate> days = new TreeSet<>(); for (Seen s : hits) days.add(s.day());
        if (days.size() < MIN_DAYS || days.last().isBefore(today.minusDays(RECENT_DAYS - 1L))) return null;
        Held best = null;
        for (LocalDate from : days) {
            int n = days.subSet(from, true, from.plusDays(SPAN_DAYS - 1L), true).size();
            if (n < MIN_DAYS) continue;
            int all = 0, got = 0; long last = 0;
            for (Seen s : times) if (!s.day().isBefore(from)) all++;
            for (Seen s : hits) if (!s.day().isBefore(from)) { got++; last = Math.max(last, s.at()); }
            double share = all == 0 ? 0 : got / (double) all;
            if (share < SHARE) continue;
            if (best == null || n > best.days() || n == best.days() && share > best.hits() / (double) best.times()) best = new Held(n, got, all, last);
        }
        return best;
    }
    /** The moment already has a trigger that opens something (that app or another, on or off): nothing to suggest there. */
    public static boolean covered(List<Triggers.Trigger> triggers, Moment m) {
        for (Triggers.Trigger t : triggers == null ? List.<Triggers.Trigger>of() : triggers) {
            Triggers.Condition c = t.when();
            boolean same = switch (m.kind()) {
                case CAR -> Triggers.BLUETOOTH.equals(c.kind()) && Triggers.CONNECT.equals(c.edge()) && (c.car() || !m.device().isEmpty() && m.device().equalsIgnoreCase(c.id()));
                case CHARGE -> Triggers.POWER.equals(c.kind()) && Triggers.PLUGGED.equals(c.edge());
                default -> Triggers.PLACE.equals(c.kind()) && Triggers.ARRIVE.equals(c.edge()) && SavedPlaces.key(c.name()).equals(SavedPlaces.key(m.place()));
            };
            if (same && List.of(Triggers.OPEN_APP, Triggers.NAVIGATE, Triggers.PLAY).contains(t.then().kind())) return true;
        }
        return false;
    }
}
