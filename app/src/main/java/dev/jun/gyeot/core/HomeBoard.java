package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레 홈, the home-screen board of 화면 꾸미기 (준: "내 휴대폰 자체를 거대한 인터랙티브 ai ui/ux로", "자비스 같이", "사이버틱하게
 * 디자인 하지 말기", "닌텐도나 애플 like"): what the live wallpaper shows page by page and what its board says. Each launcher
 * page has its slot (준: "1페이지는 기존 배경화면 2페이지 3페이지...에만 앱에서 넣은화면"): the user's own photo, the board, or a
 * web piece; the board is the day at a glance (the next event, the quotes the user watches, the latest notices, 퍼레's
 * suggestion and work) and keeps to the top half, the rest being the home screen's own icons. Locked, it says nothing private. 퍼레 itself is still called with the edge handle (준: "코어대신 현재의 우측 당기는 바 그대로
 * 유지"), so the board has no summon button of its own. Pure Java.
 */
public final class HomeBoard {
    private HomeBoard() {}
    /** The slot kinds besides a web address (https://…) or one of the user's pages (page:…). */
    public static final String PHOTO = "photo", HOME = "home";
    public static final int MOST_SLOTS = 6;

    /** One launcher page: what it shows and its name in 화면 꾸미기. */
    public record Slot(String src, String title) {
        public JSONObject json() { return Jsons.obj("src", src, "title", title); }
        public boolean web() { return src.startsWith("https://") || src.startsWith("page:"); }
    }
    /** The pages saved, or the single wallpaper chosen before pages existed (the same on every page). */
    public static List<Slot> slots(String saved, String single, String singleTitle) {
        List<Slot> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved);
            for (int i = 0; i < a.length() && out.size() < MOST_SLOTS; i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String src = o.optString("src");
                if (valid(src)) out.add(new Slot(src, o.optString("title", name(src))));
            }
        } catch (Exception unreadable) { out.clear(); }
        if (out.isEmpty() && valid(single)) out.add(new Slot(single, singleTitle == null || singleTitle.isBlank() ? name(single) : singleTitle));
        return out;
    }
    public static String save(List<Slot> slots) {
        JSONArray a = new JSONArray();
        for (Slot s : slots) if (valid(s.src()) && a.length() < MOST_SLOTS) a.put(s.json());
        return a.toString();
    }
    static boolean valid(String src) {
        return src != null && (src.equals(PHOTO) || src.equals(HOME) || src.startsWith("https://") || (src.startsWith("page:") && src.length() > 5));
    }
    public static String name(String src) { return PHOTO.equals(src) ? "원래 사진" : HOME.equals(src) ? "퍼레 홈" : src == null ? "" : src; }
    /** One web page on every page: loaded straight, as the wallpaper was before pages (nothing framed around it). */
    public static boolean direct(List<Slot> slots) { return slots.size() == 1 && slots.get(0).web(); }

    /**
     * The launcher page under the wallpaper from its offsets (0 the first, fractional mid-swipe), or -1 when the launcher does
     * not tell (step 0 or 1: one page, or a home screen that never scrolls its wallpaper).
     */
    public static double page(float xOffset, float xStep) {
        if (!(xStep > 0f) || xStep >= 1f || Float.isNaN(xOffset)) return -1;
        return Math.max(0, xOffset / xStep);
    }
    public static int pages(float xStep) { return xStep > 0f && xStep < 1f ? Math.round(1f / xStep) + 1 : 1; }
    /**
     * Where the board starts before (or without) the launcher's offsets: the first page when they have been seen before,
     * else the first page that is not the photo (a launcher that never says where it is would otherwise show only the photo).
     */
    public static int start(List<Slot> slots, boolean offsetsSeen) {
        if (offsetsSeen) return 0;
        for (int i = 0; i < slots.size(); i++) if (!PHOTO.equals(slots.get(i).src())) return i;
        return 0;
    }

    public record Event(String title, long begin, long end, boolean allDay, String place) {}
    /** The next event to show: the timed one going on or next today, else today's all-day one; null when the day is clear. */
    public static Event next(List<CalendarQuery.Event> events, long now) {
        CalendarQuery.Event timed = null, allDay = null;
        for (CalendarQuery.Event e : events == null ? List.<CalendarQuery.Event>of() : events) {
            if (e.end() <= now) continue;
            if (e.allDay()) { if (allDay == null) allDay = e; continue; }
            if (e.begin() - now > 36 * 3_600_000L) continue;
            if (timed == null || e.begin() < timed.begin()) timed = e;
        }
        CalendarQuery.Event e = timed != null ? timed : allDay;
        return e == null ? null : new Event(e.title() == null || e.title().isBlank() ? "제목 없는 일정" : e.title().strip(), e.begin(), e.end(), e.allDay(), e.location() == null ? "" : e.location().strip());
    }
    /** app: the app's name as shown; pkg: its package (for its icon). */
    public record Notice(String app, String pkg, String title, String text, long at) {}
    /** 퍼레's suggestion: what it is, and the request that starts it (ask) when the user hands it over. */
    public record Suggestion(String title, String body, String ask) {
        public Suggestion(String title, String body) { this(title, body, ""); }
    }
    /** What the app knows for the board now. calendar/notices: null when that is off (the board then leaves its card out). */
    public record Known(long now, boolean locked, boolean calendarOn, Event next, int eventsLeft,
                        List<Notice> notices, int noticeCount, List<Suggestion> suggestions, int working, boolean lab, String name,
                        List<Quote> quotes) {}
    /** One price the user watches: BTC, and SOXL · KORU as Binance's perpetuals that follow the ETFs (준: "Btc랑 soxl koru"). */
    public record Quote(String symbol, double price, double change) {}
    /** The Binance USDⓈ-M symbols the board shows, in order. */
    public static final List<String> WATCH = List.of("BTCUSDT", "SOXLUSDT", "KORUUSDT");

    /** The board's data. Locked: only what anyone may see (the quotes, 퍼레's name), nothing of the user's day. */
    public static JSONObject json(Known k) {
        JSONObject o = Jsons.obj("now", k.now(), "locked", k.locked(), "name", k.name() == null || k.name().isBlank() ? "들퍼레" : k.name());
        JSONArray quotes = new JSONArray();
        for (Quote q : k.quotes() == null ? List.<Quote>of() : k.quotes()) if (q.price() > 0) quotes.put(Jsons.obj("symbol", q.symbol(), "price", q.price(), "change", q.change()));
        Jsons.put(o, "quotes", quotes);
        if (k.locked()) return o;
        Jsons.put(o, "calendarOn", k.calendarOn());
        if (k.next() != null) Jsons.put(o, "next", Jsons.obj("title", clip(k.next().title(), 60), "begin", k.next().begin(), "end", k.next().end(), "allDay", k.next().allDay(), "place", clip(k.next().place(), 40)));
        Jsons.put(o, "eventsLeft", Math.max(0, k.eventsLeft()));
        if (k.notices() != null) {
            JSONArray n = new JSONArray();
            for (Notice x : k.notices()) if (n.length() < 3) n.put(Jsons.obj("app", clip(x.app(), 20), "pkg", x.pkg() == null ? "" : x.pkg(), "title", clip(x.title(), 50), "text", clip(x.text(), 80), "at", x.at()));
            Jsons.put(o, "notices", n); Jsons.put(o, "noticeCount", Math.max(n.length(), k.noticeCount()));
        }
        JSONArray s = new JSONArray();
        for (Suggestion x : k.suggestions() == null ? List.<Suggestion>of() : k.suggestions()) if (s.length() < 5) s.put(Jsons.obj("title", clip(x.title(), 60), "body", clip(x.body(), 200), "ask", clip(x.ask(), 300)));
        Jsons.put(o, "suggestions", s);
        Jsons.put(o, "working", Math.max(0, k.working())); Jsons.put(o, "lab", k.lab());
        return o;
    }
    /** Binance's 24-hour tickers ([{symbol,lastPrice,priceChangePercent}…]) as quotes, in the order asked (BTCUSDT → BTC). */
    public static List<Quote> quotes(String body, List<String> order) {
        List<Quote> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(body);
            for (String want : order) for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.optJSONObject(i);
                if (t == null || !want.equals(t.optString("symbol"))) continue;
                double price = parse(t.optString("lastPrice")), change = parse(t.optString("priceChangePercent"));
                if (price > 0) out.add(new Quote(want.replaceAll("USDT$", ""), price, change));
            }
        } catch (Exception unreadable) { out.clear(); }
        return out;
    }
    private static double parse(String s) { try { return Double.parseDouble(s); } catch (RuntimeException e) { return 0; } }
    private static String clip(String s, int most) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.codePointCount(0, t.length()) <= most ? t : t.substring(0, t.offsetByCodePoints(0, most - 1)) + "…";
    }
}
