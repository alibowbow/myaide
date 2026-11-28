package dev.jun.gyeot.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 미니앱에 다리 놓기 (core/PageBridge): the families a page's calls need, the arguments checked and tidied (times read, words
 * cut, a past or far time refused), the tools kept to a safe few (never money, a deletion, a call or a job; a trigger only
 * made, a schedule never a website), the limits per page and call, the grants kept and decided, the memories found without a
 * secret, the AI's answer as the page gets it, and the script put first in the page. Run by tools/build.py.
 */
final class PageBridgeRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** Friday 2026-11-27, 20:00 in Seoul. */
    static final long NOW = LocalDate.of(2026, 11, 27).atTime(20, 0).atZone(SEOUL).toInstant().toEpochMilli(), MIN = 60_000L, DAY = 86_400_000L;

    public static void main(String[] args) throws Exception {
        families();
        notify_();
        calendar();
        memory();
        ask();
        tools();
        limits();
        grants();
        recall();
        answers();
        page();
        System.out.println("PageBridgeRegression: " + checked + " checks passed");
    }
    static JSONObject msg(String call, Object... pairs) throws Exception {
        JSONObject a = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) a.put((String) pairs[i], pairs[i + 1]);
        return new JSONObject().put("type", "gyeot").put("id", "gy-1").put("call", call).put("args", a);
    }
    static PageBridge.Call read(String call, Object... pairs) throws Exception { return PageBridge.read(msg(call, pairs), NOW, SEOUL); }
    /** The reason a call is refused with, and its code; fails when it is not refused. */
    static String[] refused(JSONObject m) {
        try { PageBridge.read(m, NOW, SEOUL); } catch (PageBridge.Refused r) { return new String[]{r.code, r.getMessage()}; }
        throw new AssertionError("not refused: " + m);
    }

    static void families() throws Exception {
        same(PageBridge.NOTIFY, PageBridge.family("notify"), "notify: 알림");
        same(PageBridge.CALENDAR, PageBridge.family("calendar.list"), "calendar.list: 일정");
        same(PageBridge.CALENDAR, PageBridge.family("calendar.add"), "calendar.add: 일정");
        same(PageBridge.MEMORY, PageBridge.family("memory.recall"), "memory.recall: 기억");
        same(PageBridge.MEMORY, PageBridge.family("memory.remember"), "memory.remember: 기억");
        same(PageBridge.DAY, PageBridge.family("day.recall"), "day.recall: 하루 기록");
        same(PageBridge.AI, PageBridge.family("ask"), "ask: AI");
        same(PageBridge.TOOL, PageBridge.family("tool"), "tool: 도구");
        same("", PageBridge.family("permissions"), "permissions needs no grant");
        same("", PageBridge.family("notify.cancel"), "taking its own notification away needs no grant");
        same("abc-123", read("notify.cancel", "id", "abc-123").args().optString("id"), "the notification's id");
        same("", read("notify.cancel").args().optString("id"), "no id: all of its own");
        check(refused(msg("notify.cancel", "id", "../x y"))[0].equals(PageBridge.BAD), "an odd id");
        check(PageBridge.family("pay") == null && PageBridge.family("") == null && PageBridge.family(null) == null, "no such call");
        same(6, PageBridge.FAMILIES.size(), "six families");
        for (String f : PageBridge.FAMILIES) check(!PageBridge.label(f).isEmpty() && !PageBridge.allows(f).isEmpty(), "each family is named and explained: " + f);
        same("하루 기록", PageBridge.label(PageBridge.DAY), "the day's label");
        same("‘가계부’가 일정을 읽으려 해요", PageBridge.asking("가계부", "calendar.list", new JSONObject()), "the sheet's title, 가 after a vowel");
        same("‘물 마시기 앱’이 알림을 예약하려 해요", PageBridge.asking("물 마시기 앱", "notify", new JSONObject().put("at", NOW + DAY)), "이 after a consonant, 예약 for a time");
        same("‘공부 카드’가 AI에게 물어보려 해요", PageBridge.asking("공부 카드", "ask", new JSONObject()), "ask");
        same("‘타이머’가 ‘타이머’ 도구를 쓰려 해요", PageBridge.asking("타이머", "tool", new JSONObject().put("name", "timer")), "a tool by its Korean name");
        same("이 페이지가 알림을 보내려 해요", PageBridge.asking("", "notify", new JSONObject()), "no title");
        // The id: what the answer goes back under; none fit to answer to is refused before anything.
        same("gy-1", PageBridge.id(msg("notify")), "the id");
        check(refused(new JSONObject().put("call", "notify"))[0].equals(PageBridge.BAD), "no id: bad");
        check(PageBridge.id(new JSONObject().put("id", "a b<script>")).isEmpty(), "an id with odd characters is none");
        check(PageBridge.id(new JSONObject().put("id", "x".repeat(41))).isEmpty(), "an id too long is none");
        String[] unknown = refused(msg("delete_everything"));
        check(unknown[0].equals(PageBridge.BAD) && unknown[1].contains("없는 기능") && unknown[1].contains("calendar.list"), "an unknown call says what there is: " + unknown[1]);
        check(refused(new JSONObject().put("id", "gy-1").put("call", "notify").put("args", "title"))[1].contains("객체"), "args must be an object");
    }

    static void notify_() throws Exception {
        PageBridge.Call c = read("notify", "title", "  물 마시기\n시간 ", "text", "한 컵!");
        same("물 마시기 시간", c.args().optString("title"), "the title, spaces tidied");
        same("한 컵!", c.args().optString("text"), "the text");
        same(0L, c.args().optLong("at"), "no time: now");
        same(PageBridge.NOTIFY, c.family(), "its family");
        check(refused(msg("notify", "text", "내용만"))[1].contains("제목"), "a notification needs a title");
        same(60, read("notify", "title", "가".repeat(200)).args().optString("title").length(), "a title cut at 60");
        same(300, read("notify", "title", "t", "text", "나".repeat(1000)).args().optString("text").length(), "a text cut at 300");
        long at = NOW + 2 * 3_600_000L;
        same(at, read("notify", "title", "t", "at", at).args().optLong("at"), "milliseconds");
        same(at, read("notify", "title", "t", "at", String.valueOf(at)).args().optLong("at"), "milliseconds as digits");
        same(LocalDate.of(2026, 11, 28).atTime(9, 0).atZone(SEOUL).toInstant().toEpochMilli(), read("notify", "title", "t", "at", "2026-11-28T09:00").args().optLong("at"), "a local time, in Seoul");
        same(LocalDate.of(2026, 11, 28).atTime(9, 0).atZone(SEOUL).toInstant().toEpochMilli(), read("notify", "title", "t", "at", "2026-11-28 09:00").args().optLong("at"), "with a space");
        same(LocalDate.of(2026, 11, 28).atTime(0, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), read("notify", "title", "t", "at", "2026-11-28T00:00:00Z").args().optLong("at"), "UTC");
        same(LocalDate.of(2026, 11, 28).atTime(9, 0).atZone(SEOUL).toInstant().toEpochMilli(), read("notify", "title", "t", "at", "2026-11-28T09:00+09:00").args().optLong("at"), "an offset");
        same(0L, read("notify", "title", "t", "at", NOW + 1000).args().optLong("at"), "a second from now is now");
        check(refused(msg("notify", "title", "t", "at", NOW - 3 * MIN))[1].contains("지난"), "a past time is refused");
        check(refused(msg("notify", "title", "t", "at", NOW + 400 * DAY))[1].contains("1년"), "past a year is refused");
        check(refused(msg("notify", "title", "t", "at", "내일 아침"))[1].contains("시각"), "words for a time are not read");
        check(refused(msg("notify", "title", "t", "at", -5))[0].equals(PageBridge.BAD), "a negative time");
        same(0L, read("notify", "title", "t", "at", "").args().optLong("at"), "an empty time: now");
    }

    static void calendar() throws Exception {
        PageBridge.Call list = read("calendar.list");
        same(NOW, list.args().optLong("from"), "from: now");
        same(NOW + 7 * DAY, list.args().optLong("to"), "to: a week");
        long day28 = LocalDate.of(2026, 11, 28).atStartOfDay(SEOUL).toInstant().toEpochMilli();
        PageBridge.Call days = read("calendar.list", "from", "2026-11-28", "to", "2026-11-30");
        same(day28, days.args().optLong("from"), "a day: its start");
        same(day28 + 3 * DAY, days.args().optLong("to"), "a last day: through its end");
        check(refused(msg("calendar.list", "from", "2026-11-30", "to", "2026-11-28"))[1].contains("뒤"), "to before from");
        check(refused(msg("calendar.list", "from", "2026-01-01", "to", "2026-12-31"))[1].contains("92"), "more than 92 days");
        check(refused(msg("calendar.list", "from", "다음 주"))[0].equals(PageBridge.BAD), "words are not a date");

        PageBridge.Call add = read("calendar.add", "title", "치과", "start", "2026-11-28T15:00", "place", "강남역");
        same("치과", add.args().optString("title"), "the title");
        long three = LocalDate.of(2026, 11, 28).atTime(15, 0).atZone(SEOUL).toInstant().toEpochMilli();
        same(three, add.args().optLong("start"), "the start");
        same(three + 3_600_000L, add.args().optLong("end"), "an hour when no end");
        check(!add.args().optBoolean("allDay"), "a time: not all day");
        same("강남역", add.args().optString("place"), "the place");
        PageBridge.Call allDay = read("calendar.add", "title", "여행", "start", "2026-12-24", "end", "2026-12-26");
        check(allDay.args().optBoolean("allDay"), "a day alone: all day");
        same(LocalDate.of(2026, 12, 27).atStartOfDay(SEOUL).toInstant().toEpochMilli(), allDay.args().optLong("end"), "an all-day end: through that day");
        check(refused(msg("calendar.add", "start", "2026-11-28T15:00"))[1].contains("이름"), "an event needs a title");
        check(refused(msg("calendar.add", "title", "x"))[1].contains("start"), "an event needs a start");
        check(refused(msg("calendar.add", "title", "x", "start", "2026-11-28T15:00", "end", "2026-11-28T14:00"))[1].contains("뒤"), "end before start");
        check(refused(msg("calendar.add", "title", "x", "start", "2026-11-01", "end", "2026-12-31"))[1].contains("31일"), "longer than 31 days");
        check(refused(msg("calendar.add", "title", "x", "start", "2040-01-01"))[0].equals(PageBridge.BAD), "too far ahead");
        same(100, read("calendar.add", "title", "가".repeat(300), "start", "2026-11-28").args().optString("title").length(), "a title cut at 100");

        // The events the page gets: titles, times and places, never notes or attendees; in the range; at most 50.
        List<CalendarQuery.Event> events = new java.util.ArrayList<>();
        events.add(new CalendarQuery.Event("회의", three, three + 3_600_000L, false, "본사", "업무", "비밀 메모: 연봉 협상", List.of("김부장")));
        events.add(new CalendarQuery.Event("지난 일", NOW - 3 * DAY, NOW - 2 * DAY, false, "", "개인"));
        for (int i = 0; i < 60; i++) events.add(new CalendarQuery.Event("e" + i, three + i * MIN, three + (i + 1) * MIN, false, "", "개인"));
        JSONArray shown = PageBridge.events(events, NOW, NOW + 7 * DAY);
        same(50, shown.length(), "at most 50");
        JSONObject first = shown.getJSONObject(0);
        same("회의", first.optString("title"), "the event's title");
        same("본사", first.optString("place"), "its place");
        check(!first.has("description") && !first.has("attendees") && !first.has("calendar") && !first.toString().contains("연봉") && !first.toString().contains("김부장"), "no notes, attendees or calendar: " + first);
        for (int i = 0; i < shown.length(); i++) check(!"지난 일".equals(shown.getJSONObject(i).optString("title")), "outside the range: left out");
    }

    static void memory() throws Exception {
        same("고양이", read("memory.recall", "query", "고양이").args().optString("query"), "a query");
        check(refused(msg("memory.recall"))[1].contains("찾을"), "no query");
        check(refused(msg("day.recall", "query", " "))[1].contains("찾을"), "a blank query");
        same("어제 뭐 했어", read("day.recall", "query", "어제 뭐 했어").args().optString("query"), "the day's query");
        same("나는 고양이 키워", read("memory.remember", "text", "나는 고양이 키워").args().optString("text"), "a memory");
        String[] secret = refused(msg("memory.remember", "text", "내 비밀번호는 1234야"));
        check(secret[0].equals(PageBridge.BLOCKED) && secret[1].contains("비밀번호"), "a password is never remembered: " + secret[1]);
        check(refused(msg("memory.remember", "text", "계좌 110-123-456789"))[0].equals(PageBridge.BLOCKED), "an account number");
        check(refused(msg("memory.remember", "text", "카드 1234 5678 9012 3456"))[0].equals(PageBridge.BLOCKED), "a card number");
        check(refused(msg("memory.remember", "text", "x"))[0].equals(PageBridge.BAD), "too short");
        same(200, read("memory.remember", "text", "기".repeat(500)).args().optString("text").length(), "cut at 200");
    }

    static void ask() throws Exception {
        PageBridge.Call a = read("ask", "prompt", "광합성을 한 줄로");
        same("광합성을 한 줄로", a.args().optString("prompt"), "the prompt");
        same("text", a.args().optString("kind"), "text by default");
        same("json", read("ask", "prompt", "p", "kind", "JSON").args().optString("kind"), "json");
        same("short", read("ask", "prompt", "p", "kind", "short").args().optString("kind"), "short");
        check(refused(msg("ask", "prompt", "p", "kind", "image"))[1].contains("kind"), "an unknown kind");
        check(refused(msg("ask"))[1].contains("prompt"), "no prompt");
        check(refused(msg("ask", "prompt", 42))[1].contains("prompt"), "a prompt that is not words");
        check(refused(msg("ask", "prompt", "가".repeat(4001)))[1].contains("4000"), "past 4000 characters");
    }

    static void tools() throws Exception {
        for (String safe : new String[]{"open_app", "navigate", "timer", "alarm", "weather", "play", "media", "when_event", "schedule_job", "message", "email", "share", "memo"})
            check(PageBridge.TOOLS.contains(safe), "a safe tool: " + safe);
        for (String never : new String[]{"forget", "phone_job", "web_job", "work_job", "research_job", "call", "quiet", "call_me", "settings", "app_settings", "close_app", "buddy_move", "home_page", "make_music", "volume", "brightness"})
            check(!PageBridge.TOOLS.contains(never), "not for a page: " + never);
        // Reasons, never a silent no.
        check(PageBridge.toolRefusal("pay", new JSONObject()).contains("결제"), "pay: money");
        check(PageBridge.toolRefusal("bank_transfer", new JSONObject()).contains("송금"), "a transfer: money");
        check(PageBridge.toolRefusal("forget", new JSONObject()).contains("지우"), "forget: deleting");
        check(PageBridge.toolRefusal("delete_file", new JSONObject()).contains("지우"), "delete: deleting");
        check(PageBridge.toolRefusal("phone_job", new JSONObject().put("request", "x")).contains("대신 조작"), "phone_job: no jobs");
        check(PageBridge.toolRefusal("work_job", new JSONObject()).contains("gyeot.ask"), "work_job points to ask");
        check(PageBridge.toolRefusal("call", new JSONObject().put("number", "0101234")).contains("전화"), "a call: never");
        check(PageBridge.toolRefusal("show_password", new JSONObject()).contains("비밀번호"), "credentials: never");
        String other = PageBridge.toolRefusal("quiet", new JSONObject());
        check(other.contains("쓸 수 없어요") && other.contains("timer"), "another: says which there are: " + other);
        check(PageBridge.toolRefusal("", new JSONObject()).contains("name"), "no name");
        // Allowed, with what each needs.
        check(PageBridge.toolRefusal("timer", new JSONObject().put("seconds", 180)) == null, "a timer");
        check(PageBridge.toolRefusal("timer", new JSONObject()).contains("seconds"), "a timer needs its length");
        check(PageBridge.toolRefusal("timer", new JSONObject().put("seconds", 200_000)) != null, "a timer past a day");
        check(PageBridge.toolRefusal("alarm", new JSONObject().put("hour", 7).put("minute", 30)) == null, "an alarm");
        check(PageBridge.toolRefusal("alarm", new JSONObject().put("hour", 25)).contains("hour"), "an alarm's hour");
        check(PageBridge.toolRefusal("alarm", new JSONObject().put("hour", 7).put("minute", 75)).contains("minute"), "an alarm's minute");
        check(PageBridge.toolRefusal("alarm", new JSONObject().put("hour", "7")) != null, "an alarm's hour as a number");
        check(PageBridge.toolRefusal("open_app", new JSONObject().put("app", "카카오톡")) == null, "open an app");
        check(PageBridge.toolRefusal("open_app", new JSONObject()).contains("app"), "open which app");
        check(PageBridge.toolRefusal("navigate", new JSONObject().put("destination", "집")) == null, "a route");
        check(PageBridge.toolRefusal("play", new JSONObject().put("query", "아이유 좋은날")) == null, "a song");
        check(PageBridge.toolRefusal("weather", new JSONObject()) == null, "the weather");
        check(PageBridge.toolRefusal("open_url", new JSONObject().put("url", "http://x.com")).contains("https"), "only https");
        check(PageBridge.toolRefusal("open_url", new JSONObject().put("url", "javascript:alert(1)")) != null, "no script address");
        check(PageBridge.toolRefusal("message", new JSONObject().put("who", "엄마").put("text", "곧 도착")) == null, "a text written ready (a draft)");
        // Never words for money, a purchase or a deletion in any argument; plain values only.
        check(PageBridge.toolRefusal("message", new JSONObject().put("text", "엄마한테 10만원 송금해")).contains("결제"), "a draft that asks for a transfer");
        check(PageBridge.toolRefusal("search", new JSONObject().put("query", "쿠팡에서 휴지 주문해")) != null, "an order");
        check(PageBridge.toolRefusal("memo", new JSONObject().put("text", "내 비밀번호 0000")) != null, "a password in a memo");
        check(PageBridge.toolRefusal("open_app", new JSONObject().put("app", new JSONObject().put("x", 1))).contains("글자"), "nested values");
        check(PageBridge.toolRefusal("memo", new JSONObject().put("text", "가".repeat(3000))).contains("길어요"), "args too long");
        // when_event: only made, and only a tell, an app, a route, a song or a draft when it fires.
        check(PageBridge.toolRefusal("when_event", new JSONObject().put("when", "집에 도착하면").put("then", "tell")) == null, "a trigger made");
        check(PageBridge.toolRefusal("when_event", new JSONObject().put("when", "차에 타면").put("then", "open_app").put("what", "네이버 지도")) == null, "a trigger that opens an app");
        check(PageBridge.toolRefusal("when_event", new JSONObject().put("command", "delete").put("when", "x")).contains("만들기만"), "a trigger deleted: never");
        check(PageBridge.toolRefusal("when_event", new JSONObject().put("command", "list")) != null, "listed: never (other pages' triggers)");
        check(PageBridge.toolRefusal("when_event", new JSONObject().put("when", "x").put("then", "phone_job")).contains("then"), "a trigger that runs a job: never");
        check(PageBridge.toolRefusal("when_event", new JSONObject().put("then", "tell")).contains("when"), "a trigger needs its when");
        // schedule_job: a time, never a website.
        check(PageBridge.toolRefusal("schedule_job", new JSONObject().put("request", "오늘 날씨 요약").put("time", "07:30").put("repeat", "daily")) == null, "a schedule");
        check(PageBridge.toolRefusal("schedule_job", new JSONObject().put("request", "x").put("time", "7시")).contains("HH:MM"), "a schedule's time");
        check(PageBridge.toolRefusal("schedule_job", new JSONObject().put("request", "x").put("time", "07:30").put("url", "https://bank.example")).contains("웹사이트"), "a website schedule: never");
        // Read through a message: a blocked tool is BLOCKED with its reason, the arguments kept.
        String[] r = refused(msg("tool", "name", "forget", "args", new JSONObject().put("what", "다")));
        same(PageBridge.BLOCKED, r[0], "blocked, not bad");
        PageBridge.Call t = read("tool", "name", "Timer", "args", new JSONObject().put("seconds", 60));
        same("timer", t.args().optString("name"), "the name, small letters");
        same(60, t.args().getJSONObject("args").optInt("seconds"), "its arguments");
        same(0, read("tool", "name", "coin").args().getJSONObject("args").length(), "no arguments: none");
        check(refused(msg("tool", "name", "timer", "args", "60"))[1].contains("객체"), "tool args must be an object");
    }

    static void limits() {
        PageBridge.Limits l = new PageBridge.Limits();
        for (int i = 0; i < 3; i++) check(l.take("project:a", "notify", NOW + i) == null, "three notifications a minute");
        String fourth = l.take("project:a", "notify", NOW + 10);
        check(fourth != null && fourth.contains("1분에 3번"), "the fourth waits: " + fourth);
        check(l.take("project:b", "notify", NOW + 10) == null, "another page has its own");
        check(l.take("project:a", "ask", NOW + 10) == null, "another call has its own");
        check(l.take("project:a", "notify", NOW + MIN + 5) == null, "a minute later: again");
        // A day's limit: 30 notifications.
        PageBridge.Limits d = new PageBridge.Limits(); int ok = 0; String why = null;
        for (int i = 0; i < 40; i++) { why = d.take("p", "notify", NOW + i * 2 * MIN); if (why == null) ok++; }
        same(30, ok, "thirty a day");
        check(why != null && why.contains("하루에 30번"), "then the day's limit: " + why);
        check(d.take("p", "notify", NOW + DAY + 81 * MIN) == null, "a day later: again");
        // AI: six a minute.
        PageBridge.Limits a = new PageBridge.Limits(); int asks = 0;
        for (int i = 0; i < 10; i++) if (a.take("p", "ask", NOW + i) == null) asks++;
        same(6, asks, "six AI calls a minute");
        same(6, PageBridge.rate("tool")[0], "six tools a minute");
        // A day's reminders set in one go: those set for later count apart from those shown now.
        try {
            same("notify", PageBridge.bucket(read("notify", "title", "지금")), "shown now: notify's count");
            same("notify.later", PageBridge.bucket(read("notify", "title", "나중", "at", NOW + 3_600_000L)), "set for later: its own count");
            same("ask", PageBridge.bucket(read("ask", "prompt", "p")), "another call: by its name");
        } catch (Exception impossible) { throw new AssertionError(impossible); }
        PageBridge.Limits later = new PageBridge.Limits(); int set = 0;
        for (int i = 0; i < 14; i++) if (later.take("p", "notify.later", NOW + i) == null) set++;
        same(14, set, "fourteen reminders set at once");
        check(later.take("p", "notify", NOW + 20) == null, "and one shown now still");
        a.forget("p");
        check(a.take("p", "ask", NOW + 20) == null, "a page forgotten starts again");
    }

    static void grants() {
        JSONObject g = PageBridge.grants("");
        same(PageBridge.ASK, PageBridge.state(g, PageBridge.CALENDAR), "nothing kept: ask");
        String kept = PageBridge.granted(g, PageBridge.CALENDAR, PageBridge.ALLOW);
        JSONObject back = PageBridge.grants(kept);
        same(PageBridge.ALLOW, PageBridge.state(back, PageBridge.CALENDAR), "allowed, kept");
        same(PageBridge.ASK, PageBridge.state(back, PageBridge.NOTIFY), "other families untouched");
        back = PageBridge.grants(PageBridge.granted(back, PageBridge.NOTIFY, PageBridge.DENY));
        same(PageBridge.DENY, PageBridge.state(back, PageBridge.NOTIFY), "denied, kept");
        back = PageBridge.grants(PageBridge.granted(back, PageBridge.CALENDAR, PageBridge.ASK));
        same(PageBridge.ASK, PageBridge.state(back, PageBridge.CALENDAR), "revoked: asks again");
        same(PageBridge.DENY, PageBridge.state(back, PageBridge.NOTIFY), "the other choice stays");
        check(PageBridge.grants("{broken").length() == 0, "unreadable: nothing");
        check(PageBridge.grants("{\"calendar\":\"maybe\",\"bank\":\"allow\"}").length() == 0, "unknown states and families are left out");
        check(!PageBridge.grants(PageBridge.granted(new JSONObject(), "bank", PageBridge.ALLOW)).has("bank"), "an unknown family is never kept");
        same("go", PageBridge.decide(PageBridge.ALLOW, false), "allowed: go");
        same("go", PageBridge.decide(PageBridge.ASK, true), "이번만: go while open");
        same("refuse", PageBridge.decide(PageBridge.DENY, false), "denied: refused, no sheet");
        same("ask", PageBridge.decide(PageBridge.ASK, false), "nothing kept: the sheet");
        JSONObject states = PageBridge.states(back);
        same(6, states.length(), "every family's state");
        same(PageBridge.DENY, states.optString(PageBridge.NOTIFY), "its state");
        check(PageBridge.deniedWhy(PageBridge.CALENDAR).contains("일정") && PageBridge.deniedWhy(PageBridge.CALENDAR).contains("권한"), "the way to change it");
    }

    static void recall() {
        List<String> notes = List.of("사용자는 고양이 두 마리를 키워", "사용자의 비밀번호는 qwer1234", "차는 지하 2층에 세웠어", "사용자는 고양이 사료로 로얄캐닌을 써", "계좌 110-123-456789", "사용자는 커피를 좋아해");
        List<String> found = PageBridge.recall("고양이 사료 뭐 써?", notes, 5);
        same(2, found.size(), "two about the cat: " + found);
        same("사용자는 고양이 사료로 로얄캐닌을 써", found.get(0), "the most matching first");
        check(PageBridge.recall("비밀번호", notes, 5).isEmpty(), "never a password");
        check(PageBridge.recall("계좌", notes, 5).isEmpty(), "never an account number");
        same(List.of("차는 지하 2층에 세웠어"), PageBridge.recall("차는 어디에", notes, 5), "the particle 는 left out of 차는");
        same(1, PageBridge.recall("고양이", notes, 1).size(), "at most most");
        check(PageBridge.recall("", notes, 5).isEmpty(), "nothing asked: nothing");
        check(PageBridge.secretLike("주민번호 900101-1234567") && PageBridge.secretLike("API 키 sk-abc") && !PageBridge.secretLike("고양이가 좋아"), "secrets known");
        same(200, PageBridge.recall("나나", List.of("가" + "나".repeat(400)), 5).get(0).length(), "a long note cut");
    }

    static void answers() throws Exception {
        JSONObject ok = PageBridge.ok("gy-1", new JSONArray().put("a"));
        check(ok.optBoolean("ok") && "gyeot".equals(ok.optString("type")) && "gy-1".equals(ok.optString("id")) && ok.optJSONArray("value").length() == 1, "an answer");
        JSONObject no = PageBridge.no("gy-2", PageBridge.DENIED, "거부했어요");
        check(!no.optBoolean("ok") && PageBridge.DENIED.equals(no.optString("code")) && "거부했어요".equals(no.optString("error")), "a refusal with its code and reason");
        same("하지 못했어요.", PageBridge.no("x", null, "").optString("error"), "a refusal always says something");
        same(PageBridge.FAILED, PageBridge.no("x", null, "").optString("code"), "failed by default");
        // The AI's answer as the page gets it.
        same("광합성은 빛으로 양분을 만드는 일이야.", PageBridge.askValue("  광합성은 빛으로 양분을 만드는 일이야.\n", "text"), "words");
        Object json = PageBridge.askValue("```json\n{\"q\":\"1+1\",\"a\":\"2\"}\n```", "json");
        check(json instanceof JSONObject o && "2".equals(o.optString("a")), "json in a fence: parsed");
        check(PageBridge.askValue("[1,2]", "json") instanceof JSONArray, "a json list");
        try { PageBridge.askValue("그건 몰라요", "json"); check(false, "not json"); } catch (PageBridge.Refused r) { check(r.getMessage().contains("JSON"), "not json: said"); }
        try { PageBridge.askValue(" ", "text"); check(false, "empty"); } catch (PageBridge.Refused r) { check(r.code.equals(PageBridge.FAILED), "empty: failed"); }
        String rules = PageBridge.askInstructions("공부 카드", "json");
        check(rules.contains("공부 카드") && rules.contains("키") && rules.contains("JSON"), "the instructions: the page's name, never keys, json");
        check(PageBridge.askInstructions("x", "short").contains("짧게"), "short");
        // 폰 조작 기록's line.
        same("페이지 ‘가계부’ · 일정 읽기 · 허용", PageBridge.log("가계부", "calendar.list", new JSONObject(), "허용"), "the log line");
        same("페이지 ‘타이머’ · 도구 타이머 · 됨", PageBridge.log("타이머", "tool", new JSONObject().put("name", "timer"), "됨"), "a tool's log line");
    }

    static void page() {
        String tag = PageBridge.tag(PageBridge.PROJECT_SCRIPT);
        same("<script src=\"https://projects.gyeot.invalid/.gyeot/bridge.js\"></script>", tag, "the tag");
        same("<!doctype html><html><head>" + tag + "<title>t</title></head><body></body></html>",
            PageBridge.inject("<!doctype html><html><head><title>t</title></head><body></body></html>", tag), "first in the head");
        same("<HTML><HEAD lang=\"ko\">" + tag + "</HEAD></HTML>", PageBridge.inject("<HTML><HEAD lang=\"ko\"></HEAD></HTML>", tag), "a head with attributes, any case");
        same("<html><header>x</header></html>".replace("<html>", "<html>" + tag), PageBridge.inject("<html><header>x</header></html>", tag), "<header> is not <head>");
        same("<!DOCTYPE html>" + tag + "<p>x</p>", PageBridge.inject("<!DOCTYPE html><p>x</p>", tag), "after the doctype, never before it");
        same(tag + "<p>x</p>", PageBridge.inject("<p>x</p>", tag), "a bare fragment: at the start");
        // After the theme mark ProjectBook puts first in the head: both before the page's own scripts.
        String themed = ProjectBook.themed("<!doctype html><html><head><script>own()</script></head></html>", true);
        String both = PageBridge.inject(themed, tag);
        check(both.indexOf(tag) < both.indexOf("own()") && both.indexOf("data-theme") < both.indexOf("own()"), "before the page's own scripts: " + both);
    }
}
