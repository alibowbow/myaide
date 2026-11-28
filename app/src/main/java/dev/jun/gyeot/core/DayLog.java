package dev.jun.gyeot.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 하루 타임라인 (준이 승인한 계획 2단계): the user's day gathered into one stream, so 퍼레 answers "지난주 그 집 어디였지?", "어제 뭐
 * 했더라?", "그저께 주문한 거 뭐였지?" from real lines, and 오늘의 제안 and the night's memory grow on it.
 *
 * <p>One event: when, its kind (NOTICE … JOB, or a kind from outside such as the triggers' "car"), the app or source, a short
 * text, maybe a place and a ref to open. One compact file a day (yyyy-MM-dd.jsonl in the app's own storage), each line an
 * event; a repeat (a chat's next message, the same app again, a job ending) appends its merged self under the same id, the
 * last one counting, and the file is folded when such lines pile up. Each day is capped by kind; ninety days are kept unless
 * the user chose another length. Codes, passwords and long numbers are never kept; a bank's, a card's or a pay app's notice
 * is kept only as "알림". The calendar is not copied in: its events are read live when a day is asked about (DayLogs).
 *
 * <p>Written off the caller's thread ({@link #add}: any thread, nothing when the user turned it off); a file once read is kept
 * while its size and time stay the same. Asked about: the days and times said (period), what is asked about (ask), the
 * words that matter (words), the lines found (find) and said with their time and app (answer), never a guess; and a short
 * digest of a day for the prompts (digest). Pure Java.
 */
public final class DayLog {
    private DayLog() {}

    public static final String NOTICE = "notice", CALENDAR = "calendar", APP = "app", ORDER = "order", PLAN = "plan", PLACE = "place",
        TALK = "talk", CHAT = "chat", WORK = "work", LAB = "lab", ACT = "act", JOB = "job";
    /** The phone's moments (what 퍼레's habits are found from, core/Habits): the car's Bluetooth, another paired audio device, the charger, a saved place. */
    public static final String CAR = "car", DEVICE = "device", CHARGE = "charge", VISIT = "visit";
    /** Days kept unless the user chose another (KEEPS: the choices). */
    public static final int KEEP_DAYS = 90;
    public static final int[] KEEPS = {7, 30, 90, 365};
    /** Most characters of a text, a message's body, an app's name, a place, a ref and a group; most events a day; most of a spoken answer. */
    public static final int TEXT = 80, BODY = 24, APP_NAME = 24, PLACE_NAME = 60, REF = 120, GROUP = 80, DAY_MOST = 900, SPOKEN = 260;
    /**
     * Repeats within MERGE_MS are one line; an app back within APP_GAP_MS is the same session; a neighbourhood is one line while
     * the user stays (AREA_MS); a session shorter than MIN_SESSION_MS is a flick, one longer than MAX_SESSION_MS a screen left on.
     */
    public static final long MERGE_MS = 30 * 60_000L, APP_GAP_MS = 3 * 60_000L, AREA_MS = 6 * 3_600_000L, MIN_SESSION_MS = 10_000L, MAX_SESSION_MS = 3 * 3_600_000L;
    /** Most events of a kind a day. */
    public static int most(String kind) { return NOTICE.equals(kind) ? 300 : APP.equals(kind) ? 200 : 150; }
    /** What 퍼레 says when asked while the user has turned it off. */
    public static final String OFF = "하루 기록이 꺼져 있어서 지난 일은 찾을 수 없어. 화면 꾸미기 → 하루 기록에서 켤 수 있어.";

    // ── one event ──────────────────────────────────────────────────
    /**
     * id: its own (a merged repeat keeps it); at: when it began; group: what repeats merge under ("" = kind, app and text);
     * count: how many times it came; duration: how long (an app's session); last: when it came last or ended.
     */
    public record Event(String id, long at, String kind, String app, String text, String place, String ref, String group, int count, long duration, long last) {
        public static Event of(long at, String kind, String app, String text, String place, String ref) {
            return new Event("", at, kind == null ? "" : kind, n(app), n(text), n(place), n(ref), "", 1, 0, at);
        }
        public Event withGroup(String g) { return new Event(id, at, kind, app, text, place, ref, n(g), count, duration, last); }
        public Event withDuration(long d) { long dd = Math.max(0, d); return new Event(id, at, kind, app, text, place, ref, group, count, dd, Math.max(last, at + dd)); }
        Event withId(String i) { return new Event(i, at, kind, app, text, place, ref, group, count, duration, last); }
        /** When it ended: its session's end, or when it came last. */
        public long end() { return Math.max(last, at + duration); }
        /** What repeats merge under: the group given, else the kind, app and text. */
        public String key() { return group.isEmpty() ? kind + "|" + app + "|" + text : group; }
        public JSONObject json() {
            JSONObject o = new JSONObject();
            Jsons.put(o, "i", id); Jsons.put(o, "t", at); Jsons.put(o, "k", kind); Jsons.put(o, "a", app); Jsons.put(o, "x", text);
            if (!place.isEmpty()) Jsons.put(o, "p", place);
            if (!ref.isEmpty()) Jsons.put(o, "r", ref);
            if (!group.isEmpty()) Jsons.put(o, "g", group);
            if (count > 1) Jsons.put(o, "n", count);
            if (duration > 0) Jsons.put(o, "d", duration);
            if (last > at) Jsons.put(o, "e", last);
            return o;
        }
        public static Event of(JSONObject o) {
            if (o == null || o.optString("i").isEmpty() || o.optLong("t") <= 0 || o.optString("k").isEmpty()) return null;
            long at = o.optLong("t");
            return new Event(o.optString("i"), at, o.optString("k"), o.optString("a"), o.optString("x"), o.optString("p"), o.optString("r"), o.optString("g"),
                Math.max(1, o.optInt("n", 1)), Math.max(0, o.optLong("d")), Math.max(at, o.optLong("e", at)));
        }
    }
    private static String n(String s) { return s == null ? "" : s; }
    private static final Pattern KIND = Pattern.compile("[a-z][a-z_]{1,15}");
    /** Never kept: a code, a password, a token, a long number (masked first: a phone, a card, a tracking number). */
    static boolean secret(String s) { return s != null && !s.isEmpty() && (Notices.secret(s) || Memory.sensitive(s)); }
    /** One line of words: spaces made one, the SMS sender's tag gone, long numbers masked. */
    static String tidy(String s) { return NoticeActions.masked(NoticeActions.flat(n(s)).replaceAll("\\s+", " ")).strip(); }
    static String cut(String s, int most) {
        String v = n(s).strip();
        if (v.codePointCount(0, v.length()) <= most) return v;
        return v.substring(0, v.offsetByCodePoints(0, most - 1)).strip() + "…";
    }
    /** The event as kept: its kind a plain word, its words tidied and cut, nothing secret; null when it cannot be kept. */
    public static Event clean(Event e) {
        if (e == null || e.kind() == null || !KIND.matcher(e.kind()).matches() || e.at() <= 0) return null;
        String text = tidy(e.text()), app = tidy(e.app()), place = tidy(e.place());
        if (secret(text) || secret(app) || secret(place)) return null;
        if (text.isEmpty() && app.isEmpty()) return null;
        return new Event(n(e.id()), e.at(), e.kind(), cut(app, APP_NAME), cut(text, TEXT), cut(place, PLACE_NAME), cut(e.ref(), REF), cut(e.group(), GROUP),
            Math.max(1, e.count()), Math.max(0, e.duration()), Math.max(e.at(), e.last()));
    }

    // ── what the sources give, as events ──────────────────────────
    private static final Pattern MAP_APP = Pattern.compile("(?i)com\\.nhn\\.android\\.nmap|net\\.daum\\.android\\.map|com\\.kakao\\.map|com\\.skt\\.(?:tmap|skaf)|\\btmap\\b|com\\.google\\.android\\.apps\\.maps|네이버\\s*지도|카카오\\s*맵|티맵");
    /**
     * A notification as a line (알림 비서 kept it: Notices.keep): a messenger's sender and the start of the message, another
     * app's title; a bank's, a card's or a pay app's (or a payment's words) only "알림"; ads, the phone's own news and food
     * (kept as an order: delivery) left out; a map app's is a place. Null when it is not kept.
     */
    public static Event notice(String pkg, String label, String title, String text, long at) {
        String p = n(pkg).strip(), l = n(label).isBlank() ? p : n(label).strip(), t = NoticeActions.flat(title), x = NoticeActions.flat(text);
        if (t.isEmpty() && x.isEmpty()) return null;
        String all = t + " " + x;
        if (secret(all)) return null;
        if (NoticeActions.moneyApp(p, l) || NoticeActions.moneyText(all)) return Event.of(at, NOTICE, l, "알림", "", "").withGroup("n:" + p + ":money");
        if (Deliveries.app(p, l)) return null;
        if (Notices.noise(new Notices.Notice(p, l, t, x, at))) return null;
        boolean chat = NoticeActions.messenger(p, l);
        String sender = t.matches("[\\d\\s+\\-()#*]*") ? "" : t;
        String words = chat ? (sender.isEmpty() ? "" : cut(sender, 16) + ": ") + cut(x.isEmpty() ? t : x, BODY) : cut(t.isEmpty() ? x : t, 40);
        String kind = MAP_APP.matcher(p + " " + l).find() ? PLACE : NOTICE;
        return Event.of(at, kind, l, words, "", "").withGroup("n:" + p + ":" + cut(chat ? sender : t.isEmpty() ? x : t, 24));
    }
    private static final Pattern STORE_BRACKET = Pattern.compile("^[\\[［【]\\s*([^\\]］】]{2,24}?)\\s*[\\]］】]");
    private static final Pattern STORE_AT = Pattern.compile("([가-힣A-Za-z0-9&'][가-힣A-Za-z0-9&'· ]{1,22}?)\\s*에서\\s*(?:주문|음식|조리|배달|접수|만들|준비|픽업)");
    private static final Pattern GENERIC_STORE = Pattern.compile("(?i)배달의\\s*민족|배민|쿠팡|요기요|땡겨요|web\\s*발신|웹\\s*발신|광고|알림|공지|안내|주문|배달|고객님|가게|매장|사장님");
    /** The store a delivery notice names ("[홍콩반점] …", "BHC치킨 강남점에서 주문을 …"), or "". */
    public static String store(String title, String text) {
        for (String s : new String[]{NoticeActions.flat(title), NoticeActions.flat(text)}) {
            Matcher m = STORE_BRACKET.matcher(s);
            if (m.find() && !GENERIC_STORE.matcher(m.group(1)).find()) return m.group(1).strip();
            m = STORE_AT.matcher(s);
            while (m.find()) { String v = storeName(m.group(1)); if (!v.isEmpty()) return v; }
        }
        return "";
    }
    /** The words before 에서 without the notice's own words ahead of them ("주문 접수 BHC치킨 강남점" → "BHC치킨 강남점"), three at most. */
    private static String storeName(String words) {
        List<String> parts = new ArrayList<>(List.of(n(words).strip().split("\\s+")));
        while (!parts.isEmpty() && (GENERIC_STORE.matcher(parts.get(0)).find() || STORE_NOISE.matcher(parts.get(0)).matches())) parts.remove(0);
        if (parts.size() > 3) parts = parts.subList(parts.size() - 3, parts.size());
        String v = String.join(" ", parts).strip();
        return v.length() >= 2 && !GENERIC_STORE.matcher(v).find() ? v : "";
    }
    private static final Pattern STORE_NOISE = Pattern.compile("접수|완료|확인|시작|도착|예정|조리|음식|준비|중|\\d+.*|[~·]+");
    /**
     * Food on its way (core/Deliveries), as lines: the order once (whatever stage it is first seen at), and when it came or was
     * called off; the steps between are not lines. before: the reading before this one (null: none).
     */
    public static Event delivery(Deliveries.Delivery d, Deliveries.Delivery before) {
        if (d == null) return null;
        boolean ended = d.stage() == Deliveries.Stage.DONE || d.stage() == Deliveries.Stage.CANCELED;
        boolean fresh = before == null || before.stage() == Deliveries.Stage.DONE || before.stage() == Deliveries.Stage.CANCELED
            || d.at() - before.at() > 3 * 3_600_000L || !n(before.app()).equals(n(d.app()));
        if (ended && before != null && before.stage() == d.stage() && d.at() - before.at() < 3 * 3_600_000L) return null;
        if (!ended && !fresh) return null;
        String stage = d.stage() == Deliveries.Stage.DONE ? "배달 완료" : d.stage() == Deliveries.Stage.CANCELED ? "주문 취소" : "주문";
        String store = store(d.title(), d.text());
        return Event.of(d.at(), ORDER, d.name(), stage + (store.isEmpty() ? "" : " · " + store), store, "").withGroup("o:" + d.app() + ":" + stage + ":" + store);
    }
    private static final String[] DAYS = {"월", "화", "수", "목", "금", "토", "일"};
    private static String dow(LocalDate d) { return DAYS[d.getDayOfWeek().getValue() - 1]; }
    /** "11월 19일(목) 19:00" (or without the time). */
    static String dated(long t, boolean timed, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(zone);
        return z.getMonthValue() + "월 " + z.getDayOfMonth() + "일(" + dow(z.toLocalDate()) + ")" + (timed ? String.format(Locale.ROOT, " %02d:%02d", z.getHour(), z.getMinute()) : "");
    }
    /** A parcel (an order), a booking or a messenger's plan (a plan) read from a notice (core/NoticeActions); null for none. */
    public static Event act(NoticeActions.Act a, ZoneId zone) {
        if (a == null) return null;
        switch (a.kind()) {
            case NoticeActions.PARCEL -> {
                String it = a.what().isBlank() ? "택배" : "‘" + cut(a.what(), 24) + "’";
                String how = switch (a.stage()) { case NoticeActions.OUT -> "배송 출발"; case NoticeActions.DONE -> "배송 완료" + (a.place().isBlank() ? "" : "(" + a.place() + ")"); default -> "발송"; };
                return Event.of(a.at(), ORDER, a.name(), it + " " + how, "", "").withGroup("p:" + a.key() + ":" + a.stage());
            }
            case NoticeActions.BOOKING -> {
                String what = !a.place().isBlank() ? a.place() : a.what().replaceAll("\\s*예약$", "");
                String text = (NoticeActions.CANCELED.equals(a.stage()) ? "예약 취소" : "예약") + " · ‘" + cut(what, 30) + "’" + (a.begin() > 0 ? " " + dated(a.begin(), a.timed(), zone) : "");
                return Event.of(a.at(), PLAN, a.name(), text, a.place(), "").withGroup("b:" + a.key() + ":" + a.stage());
            }
            case NoticeActions.PLAN -> {
                String text = "약속 · " + cut(a.what(), 30) + (a.begin() > 0 ? " · " + dated(a.begin(), true, zone) : "");
                return Event.of(a.at(), PLAN, a.name(), text, a.place(), "").withGroup("m:" + a.key());
            }
            default -> { return null; }
        }
    }
    /** A route, a place or a search opened in a maps app (outcome as DeviceActions gives it: navigation, route, place, search). */
    public static Event navigated(String app, String outcome, String name, String address, long at) {
        String place = n(name).strip(), where = n(address).strip();
        if (place.isEmpty()) return null;
        boolean search = !"navigation".equals(outcome) && !"route".equals(outcome) && !"place".equals(outcome);
        String what = search ? "지도에서 ‘" + place + "’ 찾아봄" : "‘" + place + "’ " + ("place".equals(outcome) ? "위치 보기" : "길 안내");
        String kept = search ? "" : place + (where.isEmpty() || where.equals(place) ? "" : " (" + where + ")");
        return Event.of(at, PLACE, n(app).isBlank() ? "지도" : app, what, kept, "");
    }
    /** The neighbourhood the phone last knew (coarse location, only when already allowed), one line while the user stays. */
    public static Event area(String place, long at) {
        String p = n(place).strip();
        if (p.isEmpty()) return null;
        return Event.of(at, PLACE, "위치", "‘" + p + "’ 근처", p, "").withGroup("area:" + p);
    }
    /** A place looked up (내비게이션's search, 네이버 지역 검색): what was asked, and the first found. */
    public static Event looked(String query, String name, String address, long at) {
        String q = n(query).strip(); if (q.isEmpty()) return null;
        String first = n(name).strip(), where = n(address).strip();
        return Event.of(at, PLACE, "내비게이션", "‘" + q + "’ 찾아봄", first.isEmpty() ? "" : first + (where.isEmpty() ? "" : " (" + where + ")"), "");
    }
    /**
     * What 퍼레 did when asked (BuddyActs), in a few words: an alarm, a song, a search, a message written (to whom, never its
     * words), an event filled in, an app opened … and whether it was done. Null for acts that tell nothing (a torch, the volume,
     * a coin, its toys), for those kept elsewhere (a route: navigated; a job: job), and for the asking itself (day_recall).
     */
    public static Event buddy(JSONObject act, boolean done, long at) {
        if (act == null) return null;
        String tool = act.optString("tool").strip(), app = act.optString("app").strip(), who = act.optString("who").strip();
        String q = act.optString("query").strip();
        String what = switch (tool) {
            case "alarm" -> String.format(Locale.ROOT, "알람 %02d:%02d", Math.max(0, Math.min(23, act.optInt("hour"))), Math.max(0, Math.min(59, act.optInt("minute"))));
            case "timer" -> { long s = Math.max(0, act.optLong("seconds")); yield "타이머 " + (s >= 60 && s % 60 == 0 ? s / 60 + "분" : s + "초"); }
            case "play" -> q.isEmpty() ? "음악 틀기" : "‘" + cut(q, 30) + "’ 틀기";
            case "search" -> q.isEmpty() ? null : "검색 ‘" + cut(q, 30) + "’";
            case "open_app" -> app.isEmpty() ? null : app + " 열기";
            case "calendar_add" -> act.optString("title").isBlank() ? null : "일정 채움 ‘" + cut(act.optString("title"), 24) + "’" + (act.optString("date").isBlank() ? "" : " " + act.optString("date").strip());
            case "message" -> "문자 써 둠" + (who.isEmpty() ? "" : " · " + who);
            case "call" -> "전화 준비" + (who.isEmpty() ? "" : " · " + who);
            case "email" -> "메일 써 둠" + (who.isEmpty() ? "" : " · " + who);
            case "share" -> (app.isEmpty() ? "" : app + "으로 ") + "보낼 준비";
            case "memo" -> act.optString("text").isBlank() ? null : "메모 ‘" + cut(act.optString("text"), 30) + "’";
            case "make_music" -> "음악 만들기" + (act.optString("mood").isBlank() ? "" : " ‘" + cut(act.optString("mood"), 24) + "’");
            case "open_url" -> act.optString("url").isBlank() ? null : "웹 페이지 열기 ‘" + cut(BuddyJobs.host(act.optString("url")), 30) + "’";
            case "translate" -> "번역";
            default -> null;
        };
        if (what == null) return null;
        String source = tool.equals("open_app") || tool.equals("share") || app.isEmpty() ? "퍼레" : app;
        return Event.of(at, ACT, source, what + (done ? "" : " · 못 함"), "", "");
    }
    /** A job 퍼레 took on (core/BuddyJobs): what was asked, and how it ended; one line a job, said again as it ends. */
    public static Event job(BuddyJobs.Job j, long at) {
        if (j == null) return null;
        // A job the engine would not take after all (BuddyJobs.drop: stopped, ended at 1) is said as not taken.
        String how = switch (j.state()) { case RUNNING -> ""; case DONE -> " · 끝남"; case FAILED -> " · 막힘"; case WAITING -> " · 확인 필요"; case STOPPED -> j.ended() == 1 ? " · 못 맡음" : " · 멈춤"; };
        Event e = Event.of(j.started, JOB, j.who, "‘" + cut(j.request, 30) + "’ 맡음" + how, "", j.open()).withGroup("job:" + j.id);
        return new Event(e.id(), e.at(), e.kind(), e.app(), e.text(), e.place(), e.ref(), e.group(), 1, 0, Math.max(j.started, at));
    }
    /**
     * A talk with 퍼레 (or the voice assistant), as its topic: the user's first turn worth it (a guest's never, nor one that
     * says a worry, a password or the like), and how many more; null when there is none.
     */
    public static Event talk(List<String[]> turns, int from, String source, long at) {
        List<String> mine = new ArrayList<>();
        for (int i = Math.max(0, from); turns != null && i < turns.size(); i++) {
            String[] t = turns.get(i);
            if (t == null || t.length < 2 || !"user".equals(t[0]) || BuddyMind.guestTurn(t) || t[1] == null) continue;
            String w = t[1].replaceAll("\\s+", " ").strip();
            if (w.replaceAll("[\\s\\p{Punct}·…~]", "").length() < 4) continue;
            mine.add(w);
        }
        String topic = null; int others = 0;
        for (String w : mine) {
            if (BuddyMind.secret(w) || secret(w)) continue;
            if (topic == null) topic = w; else others++;
        }
        if (topic == null) return null;
        return Event.of(at, TALK, n(source).isBlank() ? "퍼레" : source, "‘" + cut(topic, 30) + "’" + (others > 0 ? " 외 " + others + "번" : ""), "", "");
    }
    // ── the phone's moments: the car, a paired audio device, the charger, a saved place ──
    /** A device's address as kept in a group (upper case, nothing else). */
    private static String address(String a) { return n(a).strip().toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F:]", ""); }
    /**
     * The car's Bluetooth connecting (in) or going: the car 조건 알림 and 호출어 know, or a device with a car's name. Its group
     * keeps the device's address, so a link back within MERGE_MS is the same drive and the habit knows which car.
     */
    public static Event car(String device, String address, boolean in, long at) {
        String name = n(device).strip();
        return Event.of(at, CAR, name.isEmpty() ? "차" : name, in ? "차에 탐" : "차에서 내림", "", "").withGroup("car:" + (in ? "in" : "out") + ":" + address(address));
    }
    /** Another paired audio device (earbuds, a speaker) connecting or going, by its name; null without one. */
    public static Event device(String device, String address, boolean in, long at) {
        String name = n(device).strip();
        if (name.isEmpty()) return null;
        return Event.of(at, DEVICE, name, "‘" + cut(name, 24) + "’ " + (in ? "연결" : "연결 끊김"), "", "").withGroup("bt:" + (in ? "in" : "out") + ":" + address(address));
    }
    /** The charger plugged in (in) or out. */
    public static Event charge(boolean in, long at) { return Event.of(at, CHARGE, "충전", in ? "충전기 꽂음" : "충전기 뺌", "", "").withGroup("charge:" + (in ? "in" : "out")); }
    /** Arrived at a saved place (in) or left it, by the user's word for it ("집", "회사"); null without one. */
    public static Event visit(String place, boolean in, long at) {
        String p = n(place).strip();
        if (p.isEmpty()) return null;
        return Event.of(at, VISIT, "장소", "‘" + cut(p, 24) + "’" + (in ? " 도착" : "에서 나옴"), p, "").withGroup("visit:" + (in ? "in" : "out") + ":" + cut(p, 40));
    }
    /** A saved place as the day log watches it: the user's word for it and where it is. */
    public record Spot(String name, double lat, double lng) {}
    /** A saved place's circle (m), how far past it counts as gone, and the worst fix that is believed. */
    public static final int VISIT_RADIUS = 150, VISIT_MARGIN = 150, VISIT_ACCURACY = 250;
    /** What a fix says: each place "in", "out" or as it was (at the edge, or a fix too rough), and the arrivals and leavings as lines. */
    public record Visits(Map<String, String> seen, List<Event> lines) {}
    /**
     * A location fix the phone already had (no new one is asked for), against the saved places: a place first seen only says
     * where the phone is (it is no arrival); after that, out → in is an arrival and in → out a leaving. A fix rougher than
     * VISIT_ACCURACY (a coarse location is a few km) changes nothing.
     */
    public static Visits visits(List<Spot> spots, Map<String, String> seen, double lat, double lng, double accuracy, long at) {
        Map<String, String> next = new LinkedHashMap<>(); List<Event> lines = new ArrayList<>();
        Map<String, String> was = seen == null ? Map.of() : seen;
        boolean good = !Double.isNaN(lat) && !Double.isNaN(lng) && accuracy >= 0 && accuracy <= VISIT_ACCURACY;
        for (Spot s : spots == null ? List.<Spot>of() : spots) {
            if (s == null || n(s.name()).isBlank() || Double.isNaN(s.lat()) || Double.isNaN(s.lng())) continue;
            String before = was.getOrDefault(s.name(), "");
            String now = before;
            if (good) {
                double d = Triggers.meters(s.lat(), s.lng(), lat, lng);
                now = d <= VISIT_RADIUS ? "in" : d >= VISIT_RADIUS + VISIT_MARGIN + accuracy ? "out" : before;
            }
            if (!now.isEmpty()) next.put(s.name(), now);
            if (!before.isEmpty() && !now.equals(before)) { Event e = visit(s.name(), now.equals("in"), at); if (e != null) lines.add(e); }
        }
        return new Visits(next, lines);
    }
    /** A titled thing begun (a 작업창 room, a research, a main chat message, a voice call's title), with what opens it. */
    public static Event titled(String kind, String app, String title, String ref, long at) {
        String t = n(title).replaceAll("\\s+", " ").strip();
        if (t.isEmpty() || BuddyMind.secret(t)) return null;
        return Event.of(at, kind, app, "‘" + cut(t, 40) + "’", "", ref);
    }

    // ── apps used: the front app's sessions ────────────────────────
    /**
     * Which app is in front, from the window-state events the accessibility service already gets: a session from when an app
     * came until another app, the home screen or the screen going off; an app's name "" (the keyboard, the shade, a window that
     * is no app) changes nothing. Sessions under MIN_SESSION_MS are flicks; one over MAX_SESSION_MS is cut (a screen left on).
     * In memory only, on one thread.
     */
    public static final class Sessions {
        private String pkg = "", label = ""; private long since;
        /** An app came to the front: the one before, ended, as an event (or null). */
        public synchronized Event front(String p, String name, long at) {
            if (p == null || p.isBlank() || name == null || name.isBlank()) return null;
            if (p.equals(pkg)) return null;
            Event ended = end(at);
            pkg = p; label = name.strip(); since = at;
            return ended;
        }
        /** The home screen came, or the screen went off: the app in front, ended. */
        public synchronized Event away(long at) { Event ended = end(at); pkg = ""; label = ""; since = 0; return ended; }
        public synchronized String current() { return pkg; }
        /** Nothing in front any more, and no line for it (turned off, 모두 지우기). */
        public synchronized void forget() { pkg = ""; label = ""; since = 0; }
        private Event end(long at) {
            if (pkg.isEmpty() || since <= 0) return null;
            long d = at - since;
            if (d < MIN_SESSION_MS) return null;
            return Event.of(since, APP, label, label, "", "").withGroup("app:" + pkg).withDuration(Math.min(d, MAX_SESSION_MS));
        }
    }
    /** "12분", "1시간 20분", "1분" (under a minute). */
    public static String minutes(long ms) {
        long m = Math.max(1, Math.round(ms / 60_000.0));
        return m < 60 ? m + "분" : m / 60 + "시간" + (m % 60 == 0 ? "" : " " + m % 60 + "분");
    }

    // ── the files: one a day ───────────────────────────────────────
    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.jsonl");
    /**
     * The days kept in a folder, a file each; each read once and kept while its size and time stay the same (what it writes
     * itself it knows without reading). Thread-safe; IO on the caller's thread (DayLog.add calls it on its own).
     */
    public static final class Book {
        public final File dir; public final ZoneId zone;
        private static final class Day { final LinkedHashMap<String, Event> live = new LinkedHashMap<>(); long size = -1, modified = -1; int lines, dropped; }
        private final LinkedHashMap<LocalDate, Day> days = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<LocalDate, Day> eldest) { return size() > 16; }
        };
        private int reads;
        public Book(File dir, ZoneId zone) { this.dir = dir; this.zone = zone == null ? ZoneId.systemDefault() : zone; }
        private File file(LocalDate d) { return new File(dir, d + ".jsonl"); }
        public LocalDate dayOf(long at) { return Instant.ofEpochMilli(at).atZone(zone).toLocalDate(); }
        /** How many times a file was read (tests: read once). */
        public synchronized int reads() { return reads; }
        private Day load(LocalDate date) {
            File f = file(date);
            long size = f.isFile() ? f.length() : -1, modified = size >= 0 ? f.lastModified() : -1;
            Day d = days.get(date);
            if (d != null && d.size == size && d.modified == modified) return d;
            Day fresh = new Day(); fresh.size = size; fresh.modified = modified;
            if (size > 0) {
                reads++;
                try {
                    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                        if (line.isBlank()) continue;
                        fresh.lines++;
                        try { Event e = Event.of(new JSONObject(line)); if (e != null) fresh.live.put(e.id(), e); } catch (Exception broken) { /* left out */ }
                    }
                } catch (Exception unreadable) { fresh.live.clear(); }
            }
            if (d != null) fresh.dropped = d.dropped;
            days.put(date, fresh);
            return fresh;
        }
        /** The day's events, in the order they first came. */
        public synchronized List<Event> day(LocalDate date) { return new ArrayList<>(load(date).live.values()); }
        /** How many events the day holds (read only when the file changed). */
        public synchronized int count(LocalDate date) { return load(date).live.size(); }
        /** How many were left out of a day for its caps (since this book opened it). */
        public synchronized int dropped(LocalDate date) { Day d = days.get(date); return d == null ? 0 : d.dropped; }
        /**
         * Keeps an event: merged into the same thing come again (same group or kind, app and text, within its window: a chat's
         * next message, the same app back, a job ending), else a new line unless its kind or the day is full. Returns what was
         * kept, or null.
         */
        public synchronized Event put(Event raw) {
            Event e = clean(raw); if (e == null) return null;
            LocalDate date = dayOf(e.at());
            Day day = load(date);
            String key = e.key(); Event prev = null;
            List<Event> all = new ArrayList<>(day.live.values());
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).key().equals(key) && all.get(i).kind().equals(e.kind())) { prev = all.get(i); break; }
            Event out = prev == null ? null : merge(prev, e);
            if (out == null) {
                int ofKind = 0; for (Event v : all) if (v.kind().equals(e.kind())) ofKind++;
                if (ofKind >= most(e.kind()) || all.size() >= DAY_MOST) { day.dropped++; return null; }
                out = e.withId(e.id().isEmpty() ? UUID.randomUUID().toString().replace("-", "").substring(0, 10) : e.id());
            }
            File f = file(date);
            try {
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return null;
                try (FileOutputStream w = new FileOutputStream(f, true)) { w.write((out.json().toString() + "\n").getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception unwritable) { return null; }
            day.live.put(out.id(), out); day.lines++;
            day.size = f.length(); day.modified = f.lastModified();
            if (day.lines > day.live.size() * 2 + 40) fold(date, day);
            return out;
        }
        private static Event merge(Event prev, Event e) {
            boolean app = APP.equals(e.kind()), job = e.group().startsWith("job:"), area = e.group().startsWith("area:");
            long gap = app ? e.at() - prev.end() : e.at() - prev.last();
            long window = app ? APP_GAP_MS : job ? Long.MAX_VALUE : area ? AREA_MS : MERGE_MS;
            if (gap > window || e.at() < prev.at() - window) return null;
            long at = Math.min(prev.at(), e.at());
            if (app) {
                long duration = prev.duration() + e.duration(), last = Math.max(prev.end(), e.end());
                return new Event(prev.id(), at, prev.kind(), e.app(), e.text(), prev.place(), prev.ref(), prev.group(), prev.count(), duration, last);
            }
            long last = Math.max(prev.last(), e.last());
            if (job) return new Event(prev.id(), at, prev.kind(), e.app(), e.text(), prev.place(), e.ref().isEmpty() ? prev.ref() : e.ref(), prev.group(), 1, 0, last);
            int count = NOTICE.equals(e.kind()) && prev.text().equals(e.text()) ? prev.count() : prev.count() + e.count();
            return new Event(prev.id(), at, prev.kind(), e.app(), e.text(), e.place().isEmpty() ? prev.place() : e.place(), e.ref().isEmpty() ? prev.ref() : e.ref(), prev.group(), count, 0, last);
        }
        /** The day's file written again with only what counts (its merged lines folded away). */
        private void fold(LocalDate date, Day day) {
            File f = file(date), tmp = new File(dir, date + ".jsonl.tmp");
            StringBuilder b = new StringBuilder(); for (Event e : day.live.values()) b.append(e.json()).append('\n');
            try {
                Files.write(tmp.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
                if (!tmp.renameTo(f)) { Files.write(f.toPath(), b.toString().getBytes(StandardCharsets.UTF_8)); tmp.delete(); }
                day.lines = day.live.size(); day.size = f.length(); day.modified = f.lastModified();
            } catch (Exception unwritable) { tmp.delete(); }
        }
        /** The events of these days, oldest first (at most 400 days). */
        public synchronized List<Event> range(LocalDate from, LocalDate to) {
            List<Event> out = new ArrayList<>();
            if (from == null || to == null) return out;
            LocalDate a = from.isAfter(to) ? to : from, z = from.isAfter(to) ? from : to;
            if (ChronoUnit.DAYS.between(a, z) > 400) a = z.minusDays(400);
            for (LocalDate d = a; !d.isAfter(z); d = d.plusDays(1)) if (file(d).isFile() || days.containsKey(d)) out.addAll(load(d).live.values());
            out.sort(Comparator.comparingLong(Event::at));
            return out;
        }
        /** One kind's events across days, oldest first (a routine detector: the car connected every morning). */
        public synchronized List<Event> kind(String kind, LocalDate from, LocalDate to) {
            List<Event> out = new ArrayList<>(); for (Event e : range(from, to)) if (e.kind().equals(kind)) out.add(e); return out;
        }
        /** The days that have a file, oldest first. */
        public synchronized List<LocalDate> kept() {
            List<LocalDate> out = new ArrayList<>();
            String[] names = dir.list();
            for (String name : names == null ? new String[0] : names) { Matcher m = NAME.matcher(name); if (m.matches()) try { out.add(LocalDate.parse(m.group(1))); } catch (RuntimeException odd) { /* not ours */ } }
            out.sort(Comparator.naturalOrder());
            return out;
        }
        /** The oldest day kept, or null. */
        public synchronized LocalDate first() { List<LocalDate> k = kept(); return k.isEmpty() ? null : k.get(0); }
        /** All the files' bytes. */
        public synchronized long bytes() { long b = 0; for (LocalDate d : kept()) b += file(d).length(); return b; }
        /** Days before the last keep days go; returns how many. */
        public synchronized int prune(LocalDate today, int keep) {
            LocalDate cutoff = today.minusDays(Math.max(1, keep) - 1L); int gone = 0;
            for (LocalDate d : kept()) if (d.isBefore(cutoff)) { if (file(d).delete()) gone++; days.remove(d); }
            return gone;
        }
        /** 모두 지우기: every day gone. */
        public synchronized void clear() {
            String[] names = dir.list();
            for (String name : names == null ? new String[0] : names) if (name.endsWith(".jsonl") || name.endsWith(".jsonl.tmp")) new File(dir, name).delete();
            days.clear();
        }
    }

    // ── the app's book: written off the caller's thread ────────────
    private static volatile Book book;
    private static volatile BooleanSupplier onCheck = () -> false;
    private static volatile IntSupplier keepDays = () -> KEEP_DAYS;
    private static volatile LocalDate pruned;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-day-log"); t.setDaemon(true); return t; });
    /** The app's book, whether it is on (the user's switch) and how many days it keeps; null closes it. */
    public static void open(Book b, BooleanSupplier on, IntSupplier keep) {
        book = b; onCheck = on == null ? () -> false : on; keepDays = keep == null ? () -> KEEP_DAYS : keep; pruned = null;
    }
    public static Book book() { return book; }
    /** Whether lines are kept now (a book open and the user's switch on). */
    public static boolean on() {
        if (book == null) return false;
        try { return onCheck.getAsBoolean(); } catch (RuntimeException unreadable) { return false; }
    }
    public static int keep() { try { return Math.max(1, Math.min(3650, keepDays.getAsInt())); } catch (RuntimeException unreadable) { return KEEP_DAYS; } }
    /** A line now, from any thread (the phone's moments too: CAR, DEVICE, CHARGE, VISIT); nothing while it is off. */
    public static void add(String kind, String app, String text) { add(kind, app, text, "", ""); }
    public static void add(String kind, String app, String text, String place, String ref) { add(Event.of(System.currentTimeMillis(), kind, app, text, place, ref)); }
    /** An event, kept on the day log's own thread; nothing while it is off. */
    public static void add(Event e) {
        if (e == null || !on()) return;
        try {
            IO.execute(() -> {
                Book b = book; if (b == null || !on()) return;
                try { b.put(e); prune(b); } catch (RuntimeException failed) { /* not kept */ }
            });
        } catch (RuntimeException stopped) { /* not kept */ }
    }
    /** Runs on the day log's own thread (after what was added before it). */
    public static void run(Runnable r) {
        if (r == null) return;
        try { IO.execute(() -> { try { r.run(); } catch (RuntimeException failed) { /* nothing */ } }); } catch (RuntimeException stopped) { /* nothing */ }
    }
    /** Waits until what was added so far is written (tests; never on the main thread). */
    public static boolean flush() {
        try { IO.submit(() -> {}).get(10, TimeUnit.SECONDS); return true; } catch (Exception late) { return false; }
    }
    private static void prune(Book b) {
        LocalDate today = LocalDate.now(b.zone);
        if (today.equals(pruned)) return;
        pruned = today; b.prune(today, keep());
    }

    // ── asked about: the days and times said ──────────────────────
    /** The days asked about, how to say them, the hours of the day said (-1: none), and whether the words named them. */
    public record Range(LocalDate from, LocalDate to, String label, int fromHour, int toHour, boolean said) {
        public long days() { return ChronoUnit.DAYS.between(from, to) + 1; }
    }
    private static final String AFTER = "(?=$|[\\s,.?!~]|에|엔|의|는|도|부터|까지|쯤|동안|내내|이랑|랑|하고)";
    private static final Pattern KOREAN_DATE = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일"),
        SLASH = Pattern.compile("(?<![\\d:/.])(\\d{1,2})/(\\d{1,2})(?![\\d/:])"),
        DAYS_AGO = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*일\\s*전"),
        WORD_AGO = Pattern.compile("(하루|이틀|사흘|나흘|닷새|엿새|이레|열흘)\\s*전"),
        FEW_DAYS = Pattern.compile("며칠\\s*전"),
        WEEK_AGO = Pattern.compile("일주일\\s*전|한\\s*주\\s*전|1\\s*주\\s*전"),
        LAST_WEEKDAY = Pattern.compile("(지지난|지난|저번)\\s*주\\s*([월화수목금토일])요일"),
        THIS_WEEKDAY = Pattern.compile("이번\\s*주\\s*([월화수목금토일])요일"),
        TWO_WEEKS = Pattern.compile("지지난\\s*주" + AFTER),
        LAST_WEEKEND = Pattern.compile("(?:지난|저번)\\s*주\\s*말|(?:지난|저번)\\s*주말"),
        WEEKEND = Pattern.compile("주말"),
        LAST_WEEK = Pattern.compile("(?:지난|저번)\\s*주" + AFTER + "|지난\\s*한\\s*주"),
        THIS_WEEK = Pattern.compile("이번\\s*주" + AFTER),
        LAST_MONTH = Pattern.compile("(?:지난|저번)\\s*달|전\\s*달"),
        THIS_MONTH = Pattern.compile("이번\\s*달|이달"),
        WEEKDAY = Pattern.compile("([월화수목금토일])요일"),
        THREE_BEFORE = Pattern.compile("그끄저께|그끄제"),
        TWO_BEFORE = Pattern.compile("그저께|그제|엊그저께|엊그제"),
        YESTERDAY = Pattern.compile("어제|어저께|어젯밤|어젯저녁"),
        TODAY_WORDS = Pattern.compile("오늘|아까|방금|좀\\s*전|조금\\s*전"),
        RECENT = Pattern.compile("최근|요즘|요새|근래"),
        BEFORE = Pattern.compile("지난번|저번에|저번|예전에|전에");
    private static final Map<String, Integer> COUNT = Map.of("하루", 1, "이틀", 2, "사흘", 3, "나흘", 4, "닷새", 5, "엿새", 6, "이레", 7, "열흘", 10);
    private static int weekday(String d) { for (int i = 0; i < DAYS.length; i++) if (DAYS[i].equals(d)) return i + 1; return 1; }
    private static String md(LocalDate d) { return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일"; }
    private static Range one(LocalDate day, LocalDate today) {
        long back = ChronoUnit.DAYS.between(day, today);
        String label = back == 0 ? "오늘" : back == 1 ? "어제(" + md(day) + ")" : back == 2 ? "그저께(" + md(day) + ")" : md(day) + "(" + dow(day) + ")";
        return new Range(day, day, label, -1, -1, true);
    }
    private static Range span(LocalDate from, LocalDate to, String name) {
        String end = from.getMonthValue() == to.getMonthValue() && from.getYear() == to.getYear() ? to.getDayOfMonth() + "일" : md(to);
        return new Range(from, to, name + "(" + md(from) + (from.equals(to) ? "" : "~" + end) + ")", -1, -1, true);
    }
    private static LocalDate date(int year, int month, int day) { try { return LocalDate.of(year, month, day); } catch (RuntimeException impossible) { return null; } }
    /**
     * The days the words name, or null: 오늘·아까, 어제·어젯밤, 그저께, 그끄저께, N일 전·사흘 전·며칠 전·일주일 전, (지난주·이번 주)
     * 금요일, 지난주·지지난주·이번 주, 지난 주말, 지난달·이번 달, 11월 20일, 11/20, 요즘, 지난번; with 아침·점심·저녁·밤 the hours.
     * A date said without a year is the last one before today. "전주" is a city, not last week.
     */
    public static Range period(String words, LocalDate today) {
        if (words == null || words.isBlank() || today == null) return null;
        String w = words.replaceAll("\\s+", " ").strip();
        Range r = days(w, today);
        if (r == null) return null;
        int[] h = hours(w);
        return h == null ? r : new Range(r.from(), r.to(), r.label(), h[0], h[1], r.said());
    }
    private static Range days(String w, LocalDate today) {
        Matcher m;
        if ((m = KOREAN_DATE.matcher(w)).find() || (m = SLASH.matcher(w)).find()) {
            LocalDate day = date(today.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (day == null) return null;
            if (day.isAfter(today)) day = day.minusYears(1);
            return one(day, today);
        }
        if ((m = DAYS_AGO.matcher(w)).find()) { int k = Integer.parseInt(m.group(1)); if (k >= 1 && k <= 60) return one(today.minusDays(k), today); }
        if ((m = WORD_AGO.matcher(w)).find()) return one(today.minusDays(COUNT.getOrDefault(m.group(1), 1)), today);
        if (FEW_DAYS.matcher(w).find()) return span(today.minusDays(6), today.minusDays(2), "며칠 전");
        if (WEEK_AGO.matcher(w).find()) return span(today.minusDays(8), today.minusDays(6), "일주일 전쯤");
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        if ((m = LAST_WEEKDAY.matcher(w)).find()) return one(monday.minusWeeks(m.group(1).startsWith("지지") ? 2 : 1).plusDays(weekday(m.group(2)) - 1L), today);
        if ((m = THIS_WEEKDAY.matcher(w)).find()) { LocalDate day = monday.plusDays(weekday(m.group(1)) - 1L); if (day.isAfter(today)) day = day.minusWeeks(1); return one(day, today); }
        if (TWO_WEEKS.matcher(w).find()) return span(monday.minusDays(14), monday.minusDays(8), "지지난주");
        if (LAST_WEEKEND.matcher(w).find()) return span(monday.minusDays(2), monday.minusDays(1), "지난 주말");
        if (WEEKEND.matcher(w).find()) {
            boolean now = today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY;
            return now ? span(monday.plusDays(5), today, "이번 주말") : span(monday.minusDays(2), monday.minusDays(1), "지난 주말");
        }
        if (LAST_WEEK.matcher(w).find()) return span(monday.minusDays(7), monday.minusDays(1), "지난주");
        if (THIS_WEEK.matcher(w).find()) return span(monday, today, "이번 주");
        if (LAST_MONTH.matcher(w).find()) { LocalDate first = today.withDayOfMonth(1).minusMonths(1); return span(first, first.plusMonths(1).minusDays(1), "지난달"); }
        if (THIS_MONTH.matcher(w).find()) return span(today.withDayOfMonth(1), today, "이번 달");
        if ((m = WEEKDAY.matcher(w)).find()) {
            int want = weekday(m.group(1)), back = (today.getDayOfWeek().getValue() - want + 7) % 7;
            return one(today.minusDays(back == 0 ? 7 : back), today);
        }
        if (THREE_BEFORE.matcher(w).find()) return one(today.minusDays(3), today);
        if (TWO_BEFORE.matcher(w).find()) return one(today.minusDays(2), today);
        if (YESTERDAY.matcher(w).find()) return one(today.minusDays(1), today);
        if (TODAY_WORDS.matcher(w).find()) return one(today, today);
        if (RECENT.matcher(w).find()) return span(today.minusDays(6), today, "요즘");
        if (BEFORE.matcher(w).find()) return new Range(today.minusDays(29), today, "최근 한 달", -1, -1, false);
        return null;
    }
    /** The hours of the day the words name, {from, to}, or null. */
    private static int[] hours(String w) {
        if (w.contains("새벽")) return new int[]{0, 6};
        if (w.contains("아침")) return new int[]{5, 11};
        if (w.contains("점심")) return new int[]{11, 15};
        if (w.contains("저녁")) return new int[]{17, 22};
        if (w.contains("밤")) return new int[]{19, 24};
        if (w.contains("오전")) return new int[]{0, 12};
        if (w.contains("오후")) return new int[]{12, 18};
        return null;
    }
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    /** The days the brain gave (YYYY-MM-DD), or null; a year it guessed wrong becomes the last one before today. */
    public static Range dates(String from, String to, LocalDate today) {
        LocalDate a = iso(from, today), z = iso(to, today);
        if (a == null && z == null) return null;
        if (a == null) a = z; if (z == null) z = a;
        if (a.isAfter(z)) { LocalDate t = a; a = z; z = t; }
        if (z.isAfter(today)) z = today;
        if (a.isAfter(z)) a = z;
        return a.equals(z) ? one(a, today) : span(a, z, a.plusDays(6).equals(z) && a.getDayOfWeek() == DayOfWeek.MONDAY ? "그 주" : "그동안");
    }
    private static LocalDate iso(String s, LocalDate today) {
        Matcher m = ISO.matcher(n(s)); if (!m.find()) return null;
        LocalDate d = date(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if (d == null) return null;
        if (d.isAfter(today) || ChronoUnit.DAYS.between(d, today) > 400) { d = date(today.getYear(), d.getMonthValue(), d.getDayOfMonth()); if (d != null && d.isAfter(today)) d = d.minusYears(1); }
        return d;
    }

    // ── asked about: what ──────────────────────────────────────────
    /** What is asked about: a place, an order, a plan, an app, a talk, a notice, the day as a whole, or anything. */
    public enum Ask { PLACE, ORDER, PLAN, APP, TALK, NOTICE, DID, ANY }
    private static final Pattern ORDER_W = Pattern.compile("주문|시킨|시켰|배달|택배|샀|산\\s*거|산\\s*게|구매|구입|배송"),
        PLAN_W = Pattern.compile("예약|약속|일정|예매|티켓|표\\s*끊|만나기로|모임"),
        PLACE_W = Pattern.compile("어디|장소|그\\s*집|가게|식당|카페|맛집|술집|음식점|레스토랑|거기|그곳|그\\s*곳|갔던|갔었|간\\s*곳|간\\s*데|들렀|위치|주소|동네|길\\s*안내|다녀온"),
        APP_W = Pattern.compile("앱|어플|유튜브|넷플릭스|틱톡|인스타|게임|봤던|본\\s*거|본\\s*게|틀었"),
        TALK_W = Pattern.compile("얘기|이야기|말했|물어봤|대화|부탁|맡긴|맡겼"),
        NOTICE_W = Pattern.compile("알림|연락|문자|카톡|메시지|전화\\s*왔|누가\\s*보냈|왔었"),
        DID_W = Pattern.compile("뭐\\s*했|뭘\\s*했|하루|일과|어땠|어떻게\\s*보냈|뭐\\s*하고"),
        SELF_W = Pattern.compile("퍼레|너한테|너에게|네가|니가|너가");
    /** What the words ask about (the order of the checks decides: "배달 시킨 데 어디" is an order, "퍼레한테 시킨 일" a talk). */
    public static Ask ask(String words) {
        String w = n(words);
        if (SELF_W.matcher(w).find() && (TALK_W.matcher(w).find() || w.matches("(?s).*(시켰|시킨|맡긴|맡겼|부탁|한\\s*일).*"))) return Ask.TALK;
        if (ORDER_W.matcher(w).find()) return Ask.ORDER;
        if (PLAN_W.matcher(w).find()) return Ask.PLAN;
        if (PLACE_W.matcher(w).find()) return Ask.PLACE;
        if (NOTICE_W.matcher(w).find()) return Ask.NOTICE;
        if (APP_W.matcher(w).find()) return Ask.APP;
        if (TALK_W.matcher(w).find()) return Ask.TALK;
        if (DID_W.matcher(w).find()) return Ask.DID;
        return Ask.ANY;
    }
    private static Set<String> kindsOf(Ask a) {
        return switch (a) {
            case PLACE -> Set.of(PLACE, PLAN, ORDER, CALENDAR);
            case ORDER -> Set.of(ORDER);
            case PLAN -> Set.of(PLAN, CALENDAR);
            case APP -> Set.of(APP);
            case TALK -> Set.of(TALK, CHAT, WORK, LAB, JOB, ACT);
            case NOTICE -> Set.of(NOTICE);
            default -> Set.of();
        };
    }
    /** What was looked through, as words (when nothing was found). */
    private static String looked(Ask a) {
        return switch (a) {
            case PLACE -> "길 안내·지도 검색·예약·약속·배달 가게·지도 알림";
            case ORDER -> "배달·택배";
            case PLAN -> "예약·약속·캘린더";
            case APP -> "쓴 앱";
            case TALK -> "퍼레와 대화·메인 대화·작업창·연구실·퍼레가 한 일";
            case NOTICE -> "알림";
            default -> "알림·앱·장소·주문·예약·대화·퍼레가 한 일";
        };
    }
    private static final Pattern PAST = Pattern.compile("어제|어젯|그제|그저께|엊그제|그끄저께|그끄제|지난\\s*주|저번\\s*주|지지난|지난\\s*주말|지난\\s*달|저번\\s*달|지난번|저번에|저번|아까|며칠\\s*전|(?<!\\d)\\d{1,2}\\s*일\\s*전|하루\\s*전|이틀\\s*전|사흘\\s*전|나흘\\s*전|일주일\\s*전|\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|[월화수목금토일]요일|예전에|전에"),
        RECALL = Pattern.compile("였지|였더라|이었지|었지|았지|했지|했더라|했었|더라|갔던|갔었|간\\s*(?:데|곳|집)|시킨|시켰|주문한|주문했|샀던|샀었|산\\s*거|봤던|봤었|본\\s*거|뭐였|어디였|언제였|누구였|기억\\s*나|기억\\s*안\\s*나|뭐\\s*했|뭘\\s*했|어땠|왔었|봤지|봤더라|썼지|썼더라|갔지|갔더라|먹었지|먹었더라|얼마나\\s*(?:봤|썼|했)");
    /** Whether the words ask what was (a time gone by and a word that asks back): "지난주 그 집 어디였지?", "어제 뭐 했더라?". */
    public static boolean asksPast(String said) {
        String w = n(said);
        return PAST.matcher(w).find() && RECALL.matcher(w).find();
    }
    /** Words that only ask, never what to find: the time words, the asking words, the kinds of things (their kind is Ask's). */
    private static final Set<String> STOP = Set.copyOf(List.of("그", "그거", "그게", "그걸", "거", "것", "게", "뭐", "뭘", "무슨", "무엇", "어디", "어디서", "어디였지", "어딘지", "언제", "누구", "누가", "였지", "였더라",
        "이었지", "했더라", "했지", "했었지", "기억나", "기억", "기억해", "알려줘", "알려", "찾아줘", "찾아", "줘", "좀", "나", "내가", "제가", "우리", "너", "퍼레", "퍼레야", "있잖아", "있었지",
        "그때", "그날", "거기", "그곳", "데", "곳", "집", "장소", "위치", "주소", "이름", "상호", "정보", "내용", "기록", "방문", "다녀온", "갔던", "갔었지", "간", "시킨", "시켰지", "시켰던",
        "주문", "배달", "택배", "구매", "구입", "배송", "예약", "약속", "일정", "예매", "티켓", "모임", "앱", "어플", "얘기", "이야기", "대화", "부탁", "알림", "연락", "문자", "메시지", "전화",
        "하루", "일과", "일", "한", "했던", "하던", "많이", "자주", "제일", "가장", "먹은", "먹었던", "먹던", "본", "봤던", "산", "샀던", "들은", "다시", "혹시", "근데", "그럼",
        "오늘", "어제", "그제", "그저께", "아까", "방금", "지난주", "이번주", "저번주", "저번", "지난번", "지난달", "이번달", "요즘", "최근", "주말", "아침", "점심", "저녁", "밤", "새벽", "오전", "오후",
        "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일", "전", "전에", "며칠", "사흘", "나흘", "이틀", "하루",
        "얼마나", "얼마", "몇", "몇번", "번", "동안", "시간", "시청", "사용", "이용", "쓴", "들른", "보낸", "누구한테", "누구랑"));
    /** Kinds of places: a hint of what to find, not a word it must hold ("카페": the places of that day, said as such). */
    private static final Set<String> SOFT = Set.of("카페", "식당", "맛집", "술집", "가게", "음식점", "레스토랑", "빵집", "병원", "마트", "편의점");
    /*
     * Particles: the longer ones always; 은·는·을·를·에·의 from three letters, 이·가·도·과·와·로·랑·야 from four (을지로, 제주도 and
     * 사과 keep theirs); a word that still ends in one is also matched without it (holds).
     */
    private static final Pattern JOSA = Pattern.compile("(?:에서는|에서도|에서|에게|한테|으로|이랑|하고|까지|부터|처럼|이야)$"),
        JOSA3 = Pattern.compile("(?<=..)(?:은|는|을|를|에|의)$"), JOSA4 = Pattern.compile("(?<=...)(?:이|가|도|과|와|로|랑|야)$");
    private static final Pattern VERB_END = Pattern.compile("(?:했던|하던|했었|했지|했어|했더라|한|할|하는|였지|였더라|이었지|었지|았지|더라|던)$");
    /** A verb said in the past (봤지, 시켰지, 갔었던, 맛있던): a syllable closed by ㅆ, which a noun almost never has. */
    private static boolean past(String w) {
        for (int i = 0; i < w.length(); i++) { char ch = w.charAt(i); if (ch >= 0xAC00 && ch <= 0xD7A3 && (ch - 0xAC00) % 28 == 20) return true; }
        return false;
    }
    /**
     * The words that matter in what was said (what to find): without the time and asking words, the particles, and 집 at the end
     * of a place's name ("노가리집" → 노가리).
     */
    public static List<String> words(String said) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : n(said).split("[^가-힣A-Za-z0-9]+")) {
            String w = raw.strip();
            if (w.isEmpty() || STOP.contains(w) || past(w)) continue;
            for (int i = 0; i < 3; i++) {
                String before = w; w = VERB_END.matcher(w).replaceAll(""); w = JOSA.matcher(w).replaceAll(""); w = JOSA3.matcher(w).replaceAll(""); w = JOSA4.matcher(w).replaceAll("");
                if (w.equals(before)) break;
            }
            if (w.length() > 2 && w.endsWith("집")) w = w.substring(0, w.length() - 1);
            if (w.length() < 2 || STOP.contains(w) || w.matches("\\d+[월일시분초번개주년차]?") || PAST.matcher(w).matches()) continue;
            if (ORDER_W.matcher(w).matches() || PLAN_W.matcher(w).matches()) continue;
            out.add(w.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }
    private static final Map<String, List<String>> ALSO = Map.of("유튜브", List.of("youtube"), "카톡", List.of("카카오톡", "kakaotalk"), "배민", List.of("배달의민족"), "인스타", List.of("instagram"),
        "넷플릭스", List.of("netflix"), "네이버지도", List.of("네이버지도", "naver map"), "쿠팡", List.of("coupang"));
    private static String flatText(Event e) { return (e.app() + " " + e.text() + " " + e.place()).toLowerCase(Locale.ROOT).replaceAll("[\\s‘’'\"·()]", ""); }
    private static boolean holds(Event e, String word) {
        String all = flatText(e), w = word.replace(" ", "");
        if (all.contains(w)) return true;
        if (w.length() >= 3 && "이가도과와로랑야은는을를에의".indexOf(w.charAt(w.length() - 1)) >= 0 && all.contains(w.substring(0, w.length() - 1))) return true;
        for (String other : ALSO.getOrDefault(w, List.of())) if (all.contains(other.replace(" ", ""))) return true;
        return false;
    }
    /** How useful a line is for what is asked (a booking with its place or a route above the neighbourhood). */
    private static int worth(Event e, Ask a) {
        if (a == Ask.PLACE) {
            if (PLAN.equals(e.kind())) return e.place().isEmpty() ? 2 : 5;
            if (CALENDAR.equals(e.kind())) return e.place().isEmpty() ? 1 : 4;
            if (PLACE.equals(e.kind())) return e.group().startsWith("area:") ? 1 : e.text().endsWith("길 안내") || e.text().endsWith("위치 보기") ? 5 : e.text().endsWith("찾아봄") ? 3 : 2;
            if (ORDER.equals(e.kind())) return e.place().isEmpty() ? 0 : 3;
            return 0;
        }
        return switch (e.kind()) { case PLAN, CALENDAR, ORDER -> 4; case PLACE -> e.group().startsWith("area:") ? 1 : 4; case JOB, ACT, WORK, LAB, TALK, CHAT -> 3; case APP, VISIT -> 2; case CHARGE, DEVICE -> 0; default -> 1; };
    }

    /** Where the lines come from: the book's days, with the calendar's events read live (DayLogs). */
    public interface Source { List<Event> events(LocalDate from, LocalDate to); }
    /**
     * What was found: for which days and what, the words that must be in it (said) and the hints (a kind of place, the brain's
     * own words), the lines, how many were looked through, whether the days around had to be looked at, whether only the hints
     * were missing, and the oldest day kept.
     */
    public record Found(Range range, Ask ask, List<String> words, List<String> hints, List<Event> hits, int looked, boolean widened, boolean hintless, LocalDate first) {}
    /**
     * The lines for a question (heard: the user's own words, which decide; query, from, to: what the brain made of them). The
     * days said (else the brain's, else the last month), what is asked, then the lines holding the words said (none holding
     * them: nothing, said as such), the best for what is asked first; with nothing on the days said, the week around them.
     */
    public static Found find(Source source, LocalDate first, String heard, String query, String from, String to, LocalDate today, ZoneId zone) {
        Range r = period(heard, today);
        if (r == null) r = period(query, today);
        if (r == null) r = dates(from, to, today);
        if (r == null) r = new Range(today.minusDays(29), today, "최근 한 달", -1, -1, false);
        Ask a = ask(heard); if (a == Ask.ANY) a = ask(query);
        List<String> said = new ArrayList<>(), hints = new ArrayList<>();
        for (String w : words(heard)) if (SOFT.contains(w)) hints.add(w); else said.add(w);
        for (String w : words(query)) if (!said.contains(w) && !hints.contains(w)) hints.add(w);
        Found f = look(source, r, a, said, hints, today, zone, false, first);
        if (f.hits().isEmpty() && r.said() && r.days() <= 7) {
            LocalDate wideTo = r.to().plusDays(7).isAfter(today) ? today : r.to().plusDays(7);
            Range wide = new Range(r.from().minusDays(7), wideTo, r.label(), r.fromHour(), r.toHour(), true);
            Found w = look(source, wide, a, said, hints, today, zone, true, first);
            if (!w.hits().isEmpty()) return w;
            return new Found(r, a, said, hints, List.of(), w.looked(), true, false, first);
        }
        return f;
    }
    private static Found look(Source source, Range r, Ask a, List<String> said, List<String> hints, LocalDate today, ZoneId zone, boolean widened, LocalDate first) {
        List<Event> all = new ArrayList<>();
        for (Event e : source == null ? List.<Event>of() : source.events(r.from(), r.to())) {
            if (e == null) continue;
            if (r.fromHour() >= 0) { int h = Instant.ofEpochMilli(e.at()).atZone(zone).getHour(); if (h < r.fromHour() || h >= r.toHour()) continue; }
            all.add(e);
        }
        Set<String> kinds = kindsOf(a);
        List<Event> fit = new ArrayList<>();
        for (Event e : all) if ((kinds.isEmpty() || kinds.contains(e.kind())) && (a != Ask.PLACE || worth(e, a) > 0)) fit.add(e);
        List<Event> hits; boolean hintless = false;
        if (!said.isEmpty()) {
            hits = matching(fit, said);
            if (hits.isEmpty()) hits = matching(all, said);
        } else if (!hints.isEmpty() && a != Ask.DID && !(a == Ask.ANY && fit.isEmpty())) {
            hits = matching(fit, hints);
            if (hits.isEmpty() && a != Ask.ANY) { hits = new ArrayList<>(fit); hintless = !hits.isEmpty(); }
        } else hits = new ArrayList<>(fit);
        Ask ask = a;
        if (ask == Ask.PLACE) { boolean better = hits.stream().anyMatch(e -> worth(e, Ask.PLACE) >= 2); if (better) hits.removeIf(e -> worth(e, Ask.PLACE) < 2); }
        if (ask == Ask.DID || ask == Ask.ANY && said.isEmpty() && hints.isEmpty()) hits.sort(Comparator.comparingLong(Event::at));
        else {
            long middle = widened ? (r.from().plusDays(r.days() / 2).atStartOfDay(zone).toInstant().toEpochMilli()) : 0;
            hits.sort(Comparator.comparingInt((Event e) -> -score(e, said, hints)).thenComparingInt(e -> -worth(e, ask))
                .thenComparingLong(e -> widened ? Math.abs(e.at() - middle) : -e.at()));
        }
        return new Found(r, a, said, hints, hits, all.size(), widened, hintless, first);
    }
    private static List<Event> matching(List<Event> events, List<String> words) {
        List<Event> out = new ArrayList<>(); for (Event e : events) if (score(e, words, List.of()) > 0) out.add(e); return out;
    }
    private static int score(Event e, List<String> said, List<String> hints) {
        int s = 0; for (String w : said) if (holds(e, w)) s += 10; for (String w : hints) if (holds(e, w)) s += 1; return s;
    }

    // ── answered: real lines with their time and app ──────────────
    private static String clock(ZonedDateTime z) {
        int h = z.getHour();
        return (h < 12 ? "오전 " : "오후 ") + (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.ROOT, "%02d", z.getMinute());
    }
    /** When, as said: "오늘 오전 9:05", "어제 오후 6:50", "그저께 …", "지난주 목요일(11월 19일) 오후 6:40", "11월 3일(화) 오전 10:00". */
    public static String when(long at, LocalDate today, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(at).atZone(zone); LocalDate d = z.toLocalDate();
        long back = ChronoUnit.DAYS.between(d, today);
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        String day = back == 0 ? "오늘" : back == 1 ? "어제" : back == 2 ? "그저께"
            : !d.isBefore(monday) && back > 0 ? "이번 주 " + dow(d) + "요일(" + md(d) + ")"
            : !d.isBefore(monday.minusDays(7)) && d.isBefore(monday) ? "지난주 " + dow(d) + "요일(" + md(d) + ")"
            : md(d) + "(" + dow(d) + ")";
        return day + " " + clock(z);
    }
    /** The words of one line: what, how long (an app), how many (a chat's messages), and where when the words do not say it. */
    private static String what(Event e) {
        StringBuilder b = new StringBuilder();
        if (APP.equals(e.kind())) b.append(minutes(e.duration()));
        else {
            b.append(e.text());
            if (e.count() > 1) b.append(NOTICE.equals(e.kind()) ? " 외 " + (e.count() - 1) + "개" : " (" + e.count() + "번)");
            if (!e.place().isEmpty()) {
                String name = e.place().replaceAll("\\s*\\(.*$", "").strip();
                if (!e.text().contains(name)) b.append(" (").append(e.place()).append(")");
                else if (e.place().length() > name.length() && !e.text().contains(e.place())) b.append(" ").append(e.place().substring(name.length()).strip());
            }
        }
        return b.toString();
    }
    /** One line as said: "지난주 목요일(11월 19일) 오후 6:40 · 네이버 지도 · ‘을지로 노가리집’ 길 안내 (서울 중구 을지로 123)". */
    public static String line(Event e, LocalDate today, ZoneId zone) { return when(e.at(), today, zone) + " · " + e.app() + " · " + what(e); }
    /** The answer to say (off: OFF): the lines found, at most three, or the day in a few lines; with nothing, what was looked through. */
    public static String answer(Found f, LocalDate today, ZoneId zone) {
        if (f == null) return OFF;
        Range r = f.range();
        if (f.hits().isEmpty()) {
            String since = f.first() != null && f.first().isAfter(r.from()) ? " 기록은 " + md(f.first()) + "부터 있어." : f.first() == null ? " 아직 쌓인 기록이 없어." : "";
            if (f.looked() == 0) return r.label() + (f.widened() ? "과 그 앞뒤 일주일엔" : "엔") + " 하루 기록이 하나도 없어." + since;
            String thing = !f.words().isEmpty() ? "‘" + String.join(" ", f.words()) + "’" + Brand.josa(String.join(" ", f.words()), "은", "는").substring(String.join(" ", f.words()).length())
                : switch (f.ask()) { case PLACE -> "간 곳은"; case ORDER -> "주문은"; case PLAN -> "예약·약속은"; case APP -> "쓴 앱 기록은"; case TALK -> "그런 대화는"; case NOTICE -> "그런 알림은"; default -> "그런 기록은"; };
            return cut(r.label() + (f.widened() ? "과 그 앞뒤 일주일" : "") + " 하루 기록 " + f.looked() + "개를 봤는데 " + thing + " 없었어. " + looked(f.ask()) + " 기록까지 봤어." + since, SPOKEN);
        }
        if (f.ask() == Ask.DID || f.ask() == Ask.ANY && f.words().isEmpty() && f.hints().isEmpty()) return said(f.hits(), r, today, zone);
        if (f.ask() == Ask.APP) return used(f, today, zone);
        List<String> lines = new ArrayList<>();
        for (Event e : f.hits()) { if (lines.size() >= 3) break; lines.add(line(e, today, zone)); }
        String head = f.widened() ? r.label() + "엔 없었고, 가까운 날에 이게 있었어: "
            : f.hintless() ? r.label() + "엔 ‘" + String.join(" ", f.hints().subList(0, Math.min(1, f.hints().size()))) + "’라고 적힌 곳은 없었어. 그때 기록은 이거야: "
            : lines.size() > 1 ? r.label() + "에 찾은 기록이야: " : "";
        StringBuilder b = new StringBuilder(head);
        for (int i = 0; i < lines.size(); i++) {
            String next = (i == 0 ? "" : " / ") + lines.get(i);
            if (b.length() + next.length() > SPOKEN - 20 && i > 0) break;
            b.append(next);
        }
        b.append('.');
        int more = f.hits().size() - lines.size();
        if (more > 0 && b.length() < SPOKEN - 16) b.append(" 그 밖에 ").append(more).append("개 더 있어.");
        return cut(b.toString(), SPOKEN);
    }
    /** Apps used, as said: each app's time added up over the days asked, and how many times it was opened ("YouTube 1시간 50분(3번)"). */
    private static String used(Found f, LocalDate today, ZoneId zone) {
        Map<String, long[]> apps = new LinkedHashMap<>();
        for (Event e : f.hits()) if (APP.equals(e.kind())) { long[] a = apps.computeIfAbsent(e.app(), k -> new long[2]); a[0] += e.duration(); a[1]++; }
        List<Map.Entry<String, long[]>> top = new ArrayList<>(apps.entrySet()); top.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, long[]> a : top.subList(0, Math.min(4, top.size()))) parts.add(a.getKey() + " " + minutes(a.getValue()[0]) + (a.getValue()[1] > 1 ? "(" + a.getValue()[1] + "번)" : ""));
        String day = f.range().label().replaceAll("\\(.*\\)$", "");
        String head = f.range().from().equals(f.range().to()) && (day.equals("오늘") || day.equals("어제") || day.equals("그저께")) ? day + (day.equals("오늘") ? "은 " : "는 ") : f.range().label() + (f.widened() ? "엔 없었고, 가까운 날엔 " : "엔 ");
        return cut(head + String.join(", ", parts) + " 썼어." + (f.widened() ? "" : ""), SPOKEN);
    }
    /** A day (or a few) in a few lines, as said: its plans, places, orders and what 퍼레 did, then the apps used most. */
    private static String said(List<Event> events, Range r, LocalDate today, ZoneId zone) {
        boolean oneDay = r.from().equals(r.to());
        List<Event> items = new ArrayList<>(); Map<String, Long> apps = new LinkedHashMap<>(); int notices = 0;
        for (Event e : events) {
            if (APP.equals(e.kind())) apps.merge(e.app(), e.duration(), Long::sum);
            else if (NOTICE.equals(e.kind())) notices += e.count();
            else items.add(e);
        }
        items.sort(Comparator.comparingInt((Event e) -> -worth(e, Ask.DID)).thenComparingLong(Event::at));
        List<Event> picked = new ArrayList<>(items.subList(0, Math.min(5, items.size())));
        picked.sort(Comparator.comparingLong(Event::at));
        List<String> parts = new ArrayList<>();
        for (Event e : picked) {
            ZonedDateTime z = Instant.ofEpochMilli(e.at()).atZone(zone);
            parts.add((oneDay ? "" : md(z.toLocalDate()) + " ") + clock(z) + " " + e.app() + " " + what(e));
        }
        List<Map.Entry<String, Long>> top = new ArrayList<>(apps.entrySet()); top.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        List<String> used = new ArrayList<>(); for (Map.Entry<String, Long> a : top.subList(0, Math.min(3, top.size()))) used.add(a.getKey() + " " + minutes(a.getValue()));
        if (!used.isEmpty()) parts.add("앱은 " + String.join(", ", used));
        if (notices > 0 && parts.size() < 3) parts.add("알림 " + notices + "개");
        String day = oneDay ? r.label().replaceAll("\\(.*\\)$", "") : r.label();
        String head = (day.equals("오늘") ? "오늘은" : day.equals("어제") || day.equals("그저께") ? day + "는" : r.label() + "엔") + " 이런 일이 있었어: ";
        StringBuilder b = new StringBuilder(head);
        for (int i = 0; i < parts.size(); i++) {
            String next = (i == 0 ? "" : " · ") + parts.get(i);
            if (b.length() + next.length() > SPOKEN - 2) break;
            b.append(next);
        }
        return b.append('.').toString();
    }

    // ── the day for the prompts (오늘의 제안, the night's memory) ───
    /**
     * A day in a few short lines for a prompt, at most most characters: its plans, places, orders and parcels, the apps used
     * most, the notices counted by app (a chat's senders, never the words), the talks and titles, and what 퍼레 did; "" for a
     * day with nothing.
     */
    public static String digest(List<Event> events, LocalDate date, ZoneId zone, int most) {
        if (events == null || events.isEmpty() || most < 40) return "";
        List<Event> list = new ArrayList<>(events); list.sort(Comparator.comparingLong(Event::at));
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String s : new String[]{"일정·약속", "장소", "주문·택배", "앱", "알림", "대화·작업", "퍼레가 한 일", "그 밖"}) sections.put(s, new ArrayList<>());
        Map<String, Long> apps = new LinkedHashMap<>(); Map<String, Integer> byApp = new LinkedHashMap<>(); Map<String, Map<String, Integer>> senders = new LinkedHashMap<>();
        for (Event e : list) {
            ZonedDateTime z = Instant.ofEpochMilli(e.at()).atZone(zone);
            String hm = String.format(Locale.ROOT, "%02d:%02d", z.getHour(), z.getMinute());
            switch (e.kind()) {
                case CALENDAR, PLAN -> sections.get("일정·약속").add(hm + " " + e.app() + " " + what(e));
                case PLACE -> sections.get("장소").add(e.group().startsWith("area:") ? e.place() + " 근처" : hm + " " + e.app() + " " + what(e));
                case VISIT -> sections.get("장소").add(hm + " " + e.text());
                case CHARGE, DEVICE -> { } // the charger and the earbuds tell the prompts nothing (they are for habits and recall)
                case ORDER -> sections.get("주문·택배").add(hm + " " + e.app() + " " + what(e));
                case APP -> apps.merge(e.app(), e.duration(), Long::sum);
                case NOTICE -> {
                    byApp.merge(e.app(), e.count(), Integer::sum);
                    int colon = e.text().indexOf(": ");
                    if (colon > 0 && !"알림".equals(e.text())) senders.computeIfAbsent(e.app(), k -> new LinkedHashMap<>()).merge(e.text().substring(0, colon), e.count(), Integer::sum);
                }
                case TALK, CHAT, WORK, LAB -> sections.get("대화·작업").add(e.app() + " " + what(e));
                case ACT, JOB -> sections.get("퍼레가 한 일").add(hm + " " + (e.app().equals("퍼레") ? "" : e.app() + " ") + what(e));
                default -> sections.get("그 밖").add(hm + " " + e.app() + " " + what(e));
            }
        }
        List<Map.Entry<String, Long>> topApps = new ArrayList<>(apps.entrySet()); topApps.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Long> a : topApps.subList(0, Math.min(6, topApps.size()))) sections.get("앱").add(a.getKey() + " " + minutes(a.getValue()));
        List<Map.Entry<String, Integer>> topNotices = new ArrayList<>(byApp.entrySet()); topNotices.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Integer> a : topNotices.subList(0, Math.min(5, topNotices.size()))) {
            List<Map.Entry<String, Integer>> who = new ArrayList<>(senders.getOrDefault(a.getKey(), Map.of()).entrySet()); who.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
            List<String> names = new ArrayList<>(); for (Map.Entry<String, Integer> s : who.subList(0, Math.min(3, who.size()))) names.add(s.getKey() + " " + s.getValue());
            sections.get("알림").add(a.getKey() + " " + a.getValue() + "개" + (names.isEmpty() ? "" : "(" + String.join("·", names) + ")"));
        }
        StringBuilder out = new StringBuilder(date.getMonthValue() + "월 " + date.getDayOfMonth() + "일(" + dow(date) + ") 하루 기록:\n");
        int per = Math.max(60, (most - out.length()) / 4);
        for (Map.Entry<String, List<String>> s : sections.entrySet()) {
            if (s.getValue().isEmpty()) continue;
            StringBuilder line = new StringBuilder("- ").append(s.getKey()).append(": ");
            int shown = 0;
            for (String item : s.getValue()) {
                String next = (shown == 0 ? "" : " · ") + item;
                if (line.length() + next.length() > per) break;
                line.append(next); shown++;
            }
            if (shown == 0) line.append(cut(s.getValue().get(0), per - line.length()));
            int more = s.getValue().size() - Math.max(1, shown);
            if (more > 0) line.append(" 외 ").append(more).append("개");
            if (out.length() + line.length() + 1 > most) break;
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }
}
