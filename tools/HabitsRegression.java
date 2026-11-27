package dev.jun.gyeot.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONObject;

/**
 * 퍼레's routine suggestion (core/Habits): a "moment → app" habit found in the 하루 타임라인 only when it is real (three days or
 * more in two weeks, most of the moment's times, well above chance, recent), never an app of money, the phone or the system,
 * never a pair with a trigger, answered, or let go twice, at most one a day; the words said and the trigger a yes makes; and
 * the day log's lines for the phone's moments (the car, a device, the charger, a saved place). Run by tools/build.py.
 */
final class HabitsRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** Friday 2026-11-27, 20:00 in Seoul. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    static final long NOW = at(TODAY, 20, 0), MIN = 60_000L, DAY = 24 * 3_600_000L;
    static final String OWN = "dev.jun.gyeot", CAR_ADDRESS = "AA:BB:CC:DD:EE:FF", MAP = "com.nhn.android.nmap";

    public static void main(String[] args) {
        lines();
        visits();
        drives();
        howMany();
        windows();
        chance();
        neverThese();
        triggers();
        asked();
        oneADay();
        stale();
        tracked();
        otherMoments();
        keeping();
        System.out.println("Habits regression checks passed: " + checked);
    }

    static long at(LocalDate d, int hour, int minute) { return d.atTime(hour, minute).atZone(SEOUL).toInstant().toEpochMilli(); }
    static long at(LocalDate d, int hour, int minute, int second) { return at(d, hour, minute) + second * 1000L; }
    static DayLog.Event app(long at, String pkg, String label) { return DayLog.Event.of(at, DayLog.APP, label, label, "", "").withGroup("app:" + pkg).withDuration(4 * MIN); }
    static DayLog.Event car(long at) { return DayLog.car("Kia Motors", CAR_ADDRESS, true, at); }
    static DayLog.Event carOut(long at) { return DayLog.car("Kia Motors", CAR_ADDRESS, false, at); }
    /** A day's other apps, so the day counts as one the apps were seen on (폰 조작 on). */
    static void filler(List<DayLog.Event> e, LocalDate d) {
        e.add(app(at(d, 12, 10), "com.kakao.talk", "카카오톡"));
        e.add(app(at(d, 21, 30), "com.google.android.youtube", "YouTube"));
    }
    /** The car at 08:00 on each of these days back, and Naver Map at 08:02 on the days in maps. */
    static List<DayLog.Event> drives(int[] back, int[] maps) {
        List<DayLog.Event> e = new ArrayList<>();
        for (int b : back) { LocalDate d = TODAY.minusDays(b); e.add(car(at(d, 8, 0))); e.add(carOut(at(d, 8, 40))); filler(e, d); }
        for (int b : maps) e.add(app(at(TODAY.minusDays(b), 8, 2), MAP, "네이버 지도"));
        return e;
    }
    static Habits.Suggestion suggest(List<DayLog.Event> e) { return Habits.suggest(e, List.of(), Map.of(), "", NOW, SEOUL, OWN); }
    static Habits.Suggestion suggest(List<DayLog.Event> e, List<Triggers.Trigger> t) { return Habits.suggest(e, t, Map.of(), "", NOW, SEOUL, OWN); }
    static Habits.Suggestion suggest(List<DayLog.Event> e, Map<String, Habits.Asked> asked) { return Habits.suggest(e, List.of(), asked, "", NOW, SEOUL, OWN); }

    // ── the day log's lines for the phone's moments ──
    static void lines() {
        DayLog.Event in = DayLog.car("Kia Motors", "aa:bb:cc:dd:ee:ff", true, NOW);
        same(DayLog.CAR, in.kind(), "the car's kind"); same("Kia Motors", in.app(), "the car's name"); same("차에 탐", in.text(), "in");
        same("car:in:AA:BB:CC:DD:EE:FF", in.group(), "the group keeps the car's address, upper case");
        same("차에서 내림", DayLog.car("", "", false, NOW).text(), "out"); same("차", DayLog.car("", "", false, NOW).app(), "no name: 차");
        check(DayLog.clean(in) != null, "a car's line is kept");
        DayLog.Event buds = DayLog.device("Galaxy Buds2 Pro", "11:22:33:44:55:66", true, NOW);
        same(DayLog.DEVICE, buds.kind(), "a device"); same("‘Galaxy Buds2 Pro’ 연결", buds.text(), "by its name");
        same("‘Galaxy Buds2 Pro’ 연결 끊김", DayLog.device("Galaxy Buds2 Pro", "11:22:33:44:55:66", false, NOW).text(), "going");
        check(DayLog.device("", "11:22", true, NOW) == null, "no name: no line");
        DayLog.Event plug = DayLog.charge(true, NOW);
        same(DayLog.CHARGE, plug.kind(), "the charger"); same("충전기 꽂음", plug.text(), "in"); same("charge:in", plug.group(), "its group");
        same("충전기 뺌", DayLog.charge(false, NOW).text(), "out");
        DayLog.Event home = DayLog.visit("집", true, NOW);
        same(DayLog.VISIT, home.kind(), "a visit"); same("‘집’ 도착", home.text(), "arrived"); same("집", home.place(), "its place"); same("visit:in:집", home.group(), "its group");
        same("‘회사’에서 나옴", DayLog.visit("회사", false, NOW).text(), "left");
        check(DayLog.visit(" ", true, NOW) == null, "no place: no line");
        // The moments a habit is found from: getting in, plugging in, arriving; never their ends.
        same(new Habits.Moment(Habits.CAR, "", "AA:BB:CC:DD:EE:FF"), Habits.moment(in), "getting in");
        check(Habits.moment(DayLog.car("Kia", CAR_ADDRESS, false, NOW)) == null, "getting out is no moment");
        same(Habits.CHARGE, Habits.moment(plug).kind(), "plugging in");
        check(Habits.moment(DayLog.charge(false, NOW)) == null, "unplugging is no moment");
        same(new Habits.Moment(Habits.VISIT, "집", ""), Habits.moment(home), "arriving");
        check(Habits.moment(DayLog.visit("집", false, NOW)) == null, "leaving is no moment");
        check(Habits.moment(buds) == null, "earbuds are no moment");
        check(Habits.moment(app(NOW, MAP, "네이버 지도")) == null, "an app is no moment");
        // In a day's digest: where, with the places.
        String digest = DayLog.digest(List.of(DayLog.clean(home), DayLog.clean(plug), DayLog.clean(in)), TODAY, SEOUL, 600);
        check(digest.contains("장소: 20:00 ‘집’ 도착"), digest);
        check(digest.contains("차에 탐"), digest);
        check(!digest.contains("충전기"), "the charger tells the prompts nothing: " + digest);
        // "어제 뭐 했더라?": an arrival above the car, the charger and the earbuds last.
        DayLog.Found found = DayLog.find((from, to) -> List.of(DayLog.clean(plug), DayLog.clean(in), DayLog.clean(home), DayLog.clean(buds)), TODAY, "오늘 뭐 했더라", "", "", "", TODAY, SEOUL);
        String day = DayLog.answer(found, TODAY, SEOUL);
        check(day.contains("‘집’ 도착") && day.contains("차에 탐"), day);
    }
    static void visits() {
        List<DayLog.Spot> spots = List.of(new DayLog.Spot("집", 37.5000, 127.0000), new DayLog.Spot("회사", 37.5600, 126.9700));
        // The first fix only says where the phone is: no arrival.
        DayLog.Visits first = DayLog.visits(spots, Map.of(), 37.5003, 127.0002, 30, NOW);
        same("in", first.seen().get("집"), "at home"); same("out", first.seen().get("회사"), "not at work"); check(first.lines().isEmpty(), "a first look is no arrival: " + first.lines());
        // Leaving home: a line; at the edge (inside the margin) nothing changes.
        DayLog.Visits edge = DayLog.visits(spots, first.seen(), 37.5020, 127.0000, 30, NOW + 10 * MIN);
        same("in", edge.seen().get("집"), "222 m: at the edge, as it was"); check(edge.lines().isEmpty(), "no line at the edge");
        DayLog.Visits away = DayLog.visits(spots, edge.seen(), 37.5100, 127.0000, 30, NOW + 20 * MIN);
        same("out", away.seen().get("집"), "1.1 km: out"); same(1, away.lines().size(), "one line"); same("‘집’에서 나옴", away.lines().get(0).text(), "left home");
        // A rough fix (a coarse location is a few km) changes nothing.
        DayLog.Visits rough = DayLog.visits(spots, away.seen(), 37.5000, 127.0000, 2000, NOW + 30 * MIN);
        same("out", rough.seen().get("집"), "a coarse fix is not believed"); check(rough.lines().isEmpty(), "no line from it");
        DayLog.Visits back = DayLog.visits(spots, away.seen(), 37.5001, 127.0001, 20, NOW + 40 * MIN);
        same(1, back.lines().size(), "back home"); same("‘집’ 도착", back.lines().get(0).text(), "arrived"); same(NOW + 40 * MIN, back.lines().get(0).at(), "when it was seen");
        check(DayLog.visits(spots, back.seen(), Double.NaN, Double.NaN, 10, NOW).lines().isEmpty(), "no fix, nothing");
        check(DayLog.visits(List.of(new DayLog.Spot("집", Double.NaN, 1)), Map.of(), 37.5, 127, 10, NOW).seen().isEmpty(), "a place with no coordinates is not watched");
    }

    // ── the car → Naver Map ──
    static void drives() {
        Habits.Suggestion s = suggest(drives(new int[]{5, 4, 3, 2, 1}, new int[]{5, 4, 3, 2, 1}));
        check(s != null, "five mornings: the car, then Naver Map");
        same(Habits.CAR, s.kind(), "the car"); same("네이버 지도", s.app(), "the app as shown"); same(MAP, s.pkg(), "its package");
        same(5, s.days(), "days"); same(5, s.hits(), "hits"); same(5, s.times(), "times"); same(CAR_ADDRESS, s.device(), "which car");
        same("car|" + MAP, s.key(), "what is asked once");
        same("차 타면 네이버 지도 자주 켜더라. 다음부터 차 타면 바로 열어 줄까?", s.say(), "said in passing");
        JSONObject page = s.page();
        same(s.key(), page.optString("key"), "the page's key"); same(s.say(), page.optString("text"), "its words");
        same("열어 줘", page.optString("yes"), "yes"); same("괜찮아", page.optString("no"), "no");
        // A yes makes: the car connecting → open Naver Map, every time.
        Triggers.Trigger t = s.trigger(NOW);
        same(Triggers.BLUETOOTH, t.when().kind(), "a Bluetooth condition"); same(Triggers.CONNECT, t.when().edge(), "connecting");
        check(t.when().car(), "the car"); same(CAR_ADDRESS, t.when().id(), "that car's address");
        same(Triggers.OPEN_APP, t.then().kind(), "open an app"); same("네이버 지도", t.then().target(), "that app");
        check(t.every() && t.active(), "every time, on");
        same("차에 타면 매번 네이버 지도 열어 줘", t.said(), "the words it keeps as the user's");
        same("차에 타면 네이버 지도 열게. 매번.", Triggers.said(t), "said back");
        same("차에 타면 → 네이버 지도 열기", Triggers.title(t), "its row");
        // The habit, matched as a trigger by core/Triggers: the car's link fires it.
        Triggers.Outcome o = Triggers.match(List.of(t), Triggers.Event.bluetooth(Triggers.CONNECT, CAR_ADDRESS, "Kia Motors", NOW + MIN), NOW + MIN);
        same(1, o.fired().size(), "the car fires it");
        check(suggest(new ArrayList<>(), List.of()) == null, "no lines: nothing");
    }
    static void howMany() {
        check(suggest(drives(new int[]{5, 4, 3, 2, 1}, new int[]{2, 1})) == null, "two days only");
        check(suggest(drives(new int[]{2, 1}, new int[]{2, 1})) == null, "two drives only");
        Habits.Suggestion three = suggest(drives(new int[]{3, 2, 1}, new int[]{3, 2, 1}));
        check(three != null && three.days() == 3, "three days is enough");
        // Most of the moment's times: 3 of 6 is not; 4 of 6 is.
        check(suggest(drives(new int[]{6, 5, 4, 3, 2, 1}, new int[]{6, 5, 4})) == null, "half the drives");
        Habits.Suggestion four = suggest(drives(new int[]{6, 5, 4, 3, 2, 1}, new int[]{6, 5, 4, 3}));
        check(four != null && four.hits() == 4 && four.times() == 6, "four of six: " + four);
        // Three days, but more than two weeks apart: not within SPAN_DAYS.
        check(suggest(drives(new int[]{20, 13, 6, 1}, new int[]{20, 6, 1})) == null, "three days spread over three weeks");
        // A habit that just began (the last three drives): from its first day on, every time.
        Habits.Suggestion fresh = suggest(drives(new int[]{6, 5, 4, 3, 2, 1}, new int[]{3, 2, 1}));
        check(fresh != null && fresh.times() == 3, "a habit begun three days ago counts from then: " + fresh);
        // Two drives a day, the map only on the morning one: 5 of 10 is not most of them.
        List<DayLog.Event> e = drives(new int[]{5, 4, 3, 2, 1}, new int[]{5, 4, 3, 2, 1});
        for (int b = 1; b <= 5; b++) e.add(car(at(TODAY.minusDays(b), 18, 30)));
        check(suggest(e) == null, "the evening drives have no map");
    }
    static void windows() {
        // Within five minutes after the car (or a minute before: the link can be late).
        List<DayLog.Event> late = drives(new int[]{3, 2, 1}, new int[]{});
        for (int b = 1; b <= 3; b++) late.add(app(at(TODAY.minusDays(b), 8, 7), MAP, "네이버 지도"));
        check(suggest(late) == null, "seven minutes after is not after the car");
        List<DayLog.Event> edge = drives(new int[]{3, 2, 1}, new int[]{});
        for (int b = 1; b <= 3; b++) edge.add(app(at(TODAY.minusDays(b), 8, 4, 50), MAP, "네이버 지도"));
        check(suggest(edge) != null, "four minutes fifty is");
        List<DayLog.Event> before = drives(new int[]{3, 2, 1}, new int[]{});
        for (int b = 1; b <= 3; b++) before.add(app(at(TODAY.minusDays(b), 7, 59, 20), MAP, "네이버 지도"));
        check(suggest(before) != null, "forty seconds before counts (the link came late)");
        List<DayLog.Event> earlier = drives(new int[]{3, 2, 1}, new int[]{});
        for (int b = 1; b <= 3; b++) earlier.add(app(at(TODAY.minusDays(b), 7, 57), MAP, "네이버 지도"));
        check(suggest(earlier) == null, "three minutes before does not");
        // Lines after now (a clock gone back) are left out.
        List<DayLog.Event> ahead = new ArrayList<>();
        for (int b = 0; b < 3; b++) { LocalDate d = TODAY.plusDays(b + 1); ahead.add(car(at(d, 8, 0))); ahead.add(app(at(d, 8, 2), MAP, "네이버 지도")); filler(ahead, d); }
        check(suggest(ahead) == null, "days ahead of now");
    }
    static void chance() {
        // Random apps at random times, the car and the charger at random times: no habit (fixed seeds, the same run each time).
        for (long seed : new long[]{1, 2, 3, 20261127}) {
            Random r = new Random(seed);
            List<DayLog.Event> e = new ArrayList<>();
            for (int b = 0; b < 21; b++) {
                LocalDate d = TODAY.minusDays(b);
                for (int k = 0; k < 1 + r.nextInt(2); k++) e.add(car(at(d, 7 + r.nextInt(14), r.nextInt(60))));
                e.add(DayLog.charge(true, at(d, 7 + r.nextInt(16), r.nextInt(60))));
                for (int a = 0; a < 20; a++) for (int k = 0; k < 6; k++) e.add(app(at(d, 7 + r.nextInt(16), r.nextInt(60)), "com.random.app" + a, "앱" + a));
            }
            e.removeIf(x -> x.at() > NOW);
            check(suggest(e) == null, "random apps (seed " + seed + "): " + suggest(e));
        }
        // An app opened all day long (a messenger, every 20 minutes) comes after the car anyway: no habit of the car.
        List<DayLog.Event> busy = drives(new int[]{5, 4, 3, 2, 1}, new int[]{});
        for (int b = 1; b <= 5; b++) for (int k = 0; k < 48; k++) busy.add(app(at(TODAY.minusDays(b), 7, 1) + k * 20 * MIN, "com.kakao.talk", "카카오톡"));
        check(suggest(busy) == null, "an app used all day: " + suggest(busy));
        // An app used often (twenty times a day), but always right after the car: still a habit.
        List<DayLog.Event> often = drives(new int[]{5, 4, 3, 2, 1}, new int[]{});
        for (int b = 1; b <= 5; b++) { often.add(app(at(TODAY.minusDays(b), 8, 1), "com.spotify.music", "Spotify")); for (int k = 1; k < 20; k++) often.add(app(at(TODAY.minusDays(b), 9, 0) + k * 40 * MIN, "com.spotify.music", "Spotify")); }
        Habits.Suggestion spotify = suggest(often);
        check(spotify != null && spotify.app().equals("Spotify"), "used often, and every drive: " + spotify);
    }
    static void neverThese() {
        String[][] never = {{"com.kbstar.kbbank", "KB스타뱅킹"}, {"viva.republica.toss", "토스"}, {"com.samsung.android.spay", "Samsung Wallet"}, {"com.shinhan.sbanking", "신한 SOL뱅크"},
            {"com.kiwoom.heromts", "영웅문S#"}, {"com.dunamu.exchange", "업비트"}, {"com.hyundaicard.appcard", "현대카드"}, {"com.samsung.android.dialer", "전화"}, {"com.google.android.dialer", "Phone"},
            {"com.android.settings", "설정"}, {"com.sec.android.app.launcher", "One UI 홈"}, {"com.android.systemui", "System UI"}, {OWN, Brand.NAME}, {"dev.jun.gyeot.debug", "들퍼레 디버그"},
            {"com.samsung.android.app.contacts", "연락처"}};
        for (String[] a : never) {
            check(Habits.skipped(a[0], a[1], OWN), "never suggested: " + a[1]);
            List<DayLog.Event> e = drives(new int[]{5, 4, 3, 2, 1}, new int[]{});
            for (int b = 1; b <= 5; b++) { LocalDate d = TODAY.minusDays(b); e.add(app(at(d, 8, 1), a[0], a[1])); e.add(DayLog.charge(true, at(d, 23, 0))); e.add(app(at(d, 23, 1), a[0], a[1])); }
            check(suggest(e) == null, "after the car and the charger every day, still never: " + a[1] + " → " + suggest(e));
        }
        for (String[] a : new String[][]{{MAP, "네이버 지도"}, {"com.google.android.youtube", "YouTube"}, {"com.kakao.talk", "카카오톡"}, {"com.skt.tmap.ku", "TMAP"}, {"com.netflix.mediaclient", "넷플릭스"}})
            check(!Habits.skipped(a[0], a[1], OWN), "may be suggested: " + a[1]);
        check(Habits.skipped("", "", OWN), "no app at all");
    }
    static void triggers() {
        List<DayLog.Event> e = drives(new int[]{5, 4, 3, 2, 1}, new int[]{5, 4, 3, 2, 1});
        Triggers.Trigger same = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", CAR_ADDRESS), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "차 타면 네이버 지도 켜 줘", NOW - DAY);
        check(suggest(e, List.of(same)) == null, "already a trigger");
        check(suggest(e, List.of(same.switched(false))) == null, "a trigger turned off: the user decided");
        Triggers.Trigger other = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", ""), Triggers.Action.open("티맵"), Triggers.EVERY, "차 타면 티맵 켜 줘", NOW - DAY);
        check(suggest(e, List.of(other)) == null, "the car already opens another app");
        Triggers.Trigger music = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", ""), Triggers.Action.play("아이유", ""), Triggers.EVERY, "차 타면 아이유 틀어 줘", NOW - DAY);
        check(suggest(e, List.of(music)) == null, "the car already plays music");
        Triggers.Trigger tell = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", CAR_ADDRESS), Triggers.Action.tell("안전벨트"), Triggers.EVERY, "차 타면 안전벨트 알려 줘", NOW - DAY);
        check(suggest(e, List.of(tell)) != null, "a reminder does not open anything: still asked");
        Triggers.Trigger plug = Triggers.make(Triggers.Condition.power(Triggers.PLUGGED, 0), Triggers.Action.open("유튜브"), Triggers.EVERY, "충전기 꽂으면 유튜브 켜 줘", NOW - DAY);
        check(suggest(e, List.of(plug)) != null, "the charger's trigger is not the car's");
        Triggers.Trigger buds = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "버즈", "11:22:33:44:55:66"), Triggers.Action.open("멜론"), Triggers.EVERY, "버즈 연결되면 멜론 켜 줘", NOW - DAY);
        check(suggest(e, List.of(buds)) != null, "the earbuds' trigger is not the car's");
        check(Habits.covered(List.of(same), new Habits.Moment(Habits.CAR, "", "")), "covered by the car's");
        Triggers.Trigger home = Triggers.make(Triggers.Condition.place(Triggers.ARRIVE, "우리 집", 37.5, 127, 150), Triggers.Action.open("넷플릭스"), Triggers.EVERY, "집에 오면 넷플릭스", NOW - DAY);
        check(Habits.covered(List.of(home), new Habits.Moment(Habits.VISIT, "우리집", "")), "the same place, written another way");
        check(!Habits.covered(List.of(home), new Habits.Moment(Habits.VISIT, "회사", "")), "another place");
    }
    static void asked() {
        List<DayLog.Event> e = drives(new int[]{5, 4, 3, 2, 1}, new int[]{5, 4, 3, 2, 1});
        String key = "car|" + MAP;
        check(suggest(e, Map.of(key, new Habits.Asked(Habits.REFUSED, 1, NOW - 30 * DAY))) == null, "refused: never again");
        check(suggest(e, Map.of(key, new Habits.Asked(Habits.ACCEPTED, 1, NOW - 30 * DAY))) == null, "accepted: never again");
        check(suggest(e, Map.of(key, new Habits.Asked(Habits.SHOWN, 1, NOW - 3 * DAY))) == null, "let go three days ago: not yet");
        check(suggest(e, Map.of(key, new Habits.Asked(Habits.SHOWN, 1, NOW - 8 * DAY))) != null, "let go once, a week ago: once more");
        check(suggest(e, Map.of(key, new Habits.Asked(Habits.SHOWN, 2, NOW - 20 * DAY))) == null, "let go twice: dropped");
        check(suggest(e, Map.of("charge|" + MAP, new Habits.Asked(Habits.REFUSED, 1, NOW))) != null, "another pair's no is not this one's");
        // The states as they change.
        Map<String, Habits.Asked> a = Habits.shown(Map.of(), key, NOW);
        same(new Habits.Asked(Habits.SHOWN, 1, NOW), a.get(key), "shown once");
        check(!Habits.askable(a, key, NOW + DAY), "not the next day");
        check(Habits.askable(a, key, NOW + Habits.IGNORE_WAIT_MS), "a week later");
        a = Habits.shown(a, key, NOW + 8 * DAY);
        same(2, a.get(key).times(), "shown twice");
        check(!Habits.askable(a, key, NOW + 60 * DAY), "never after the second time");
        Map<String, Habits.Asked> yes = Habits.answered(Habits.shown(Map.of(), key, NOW), key, true, NOW + MIN);
        same(Habits.ACCEPTED, yes.get(key).state(), "answered yes");
        same(Habits.ACCEPTED, Habits.shown(yes, key, NOW + 9 * DAY).get(key).state(), "a later showing never undoes an answer");
        same(Habits.REFUSED, Habits.answered(Map.of(), key, false, NOW).get(key).state(), "answered no");
        check(!Habits.askable(Habits.answered(Map.of(), key, false, NOW), key, NOW + 400 * DAY), "a no lasts");
    }
    static void oneADay() {
        List<DayLog.Event> e = drives(new int[]{5, 4, 3, 2, 1}, new int[]{5, 4, 3, 2, 1});
        check(Habits.suggest(e, List.of(), Map.of(), TODAY.toString(), NOW, SEOUL, OWN) == null, "one was shown today already");
        check(Habits.suggest(e, List.of(), Map.of(), TODAY.minusDays(1).toString(), NOW, SEOUL, OWN) != null, "yesterday's does not stop today's");
        // Two habits: the stronger is asked (the car five days, the charger three).
        for (int b = 1; b <= 3; b++) { LocalDate d = TODAY.minusDays(b); e.add(DayLog.charge(true, at(d, 23, 0))); e.add(app(at(d, 23, 1), "com.google.android.youtube", "YouTube")); }
        Habits.Suggestion s = suggest(e);
        check(s != null && s.kind().equals(Habits.CAR), "the stronger first: " + s);
        Habits.Suggestion next = suggest(e, Map.of(s.key(), new Habits.Asked(Habits.REFUSED, 1, NOW)));
        check(next != null && next.kind().equals(Habits.CHARGE) && next.app().equals("YouTube"), "then the other: " + next);
    }
    static void stale() {
        // Five mornings three weeks ago, and none since though the car came every day: gone.
        int[] all = new int[20]; for (int i = 0; i < 20; i++) all[i] = 20 - i;
        check(suggest(drives(all, new int[]{20, 19, 18, 17, 16})) == null, "a habit given up");
        // The same five mornings, and no car since: not recent.
        check(suggest(drives(new int[]{20, 19, 18, 17, 16}, new int[]{20, 19, 18, 17, 16})) == null, "nothing in the last week");
        // Older than three weeks is not read.
        check(suggest(drives(new int[]{26, 25, 24, 23, 1}, new int[]{26, 25, 24, 23, 1})) == null, "only the last READ_DAYS days");
    }
    static void tracked() {
        // The map on three of six drives (half: no), but on three of the drives the apps were not seen at all (폰 조작 off then).
        List<DayLog.Event> e = new ArrayList<>();
        for (int b = 1; b <= 6; b++) e.add(car(at(TODAY.minusDays(b), 8, 0)));
        for (int b : new int[]{6, 4, 2}) { e.add(app(at(TODAY.minusDays(b), 8, 2), MAP, "네이버 지도")); filler(e, TODAY.minusDays(b)); }
        Habits.Suggestion s = suggest(e);
        check(s != null && s.times() == 3, "only the days the apps were seen count: " + s);
        for (int b : new int[]{5, 3, 1}) filler(e, TODAY.minusDays(b));
        check(suggest(e) == null, "seen those days too: half");
    }
    static void otherMoments() {
        // The charger at night → YouTube.
        List<DayLog.Event> e = new ArrayList<>();
        for (int b = 1; b <= 4; b++) { LocalDate d = TODAY.minusDays(b); e.add(DayLog.charge(true, at(d, 23, 10))); e.add(DayLog.charge(false, at(d, 23, 50))); e.add(app(at(d, 23, 11), "com.google.android.youtube", "YouTube")); e.add(app(at(d, 12, 0), "com.kakao.talk", "카카오톡")); }
        Habits.Suggestion charge = suggest(e);
        check(charge != null, "the charger, then YouTube");
        same("charge|com.google.android.youtube", charge.key(), "its key");
        same("충전기 꽂으면 YouTube 자주 켜더라. 다음부터 충전기 꽂으면 바로 열어 줄까?", charge.say(), "said");
        Triggers.Trigger t = charge.trigger(NOW);
        same(Triggers.POWER, t.when().kind(), "the charger"); same(Triggers.PLUGGED, t.when().edge(), "plugged in");
        same("충전기를 꽂으면 YouTube 열게. 매번.", Triggers.said(t), "said back");
        // Home → Netflix.
        List<DayLog.Event> h = new ArrayList<>();
        for (int b = 1; b <= 3; b++) { LocalDate d = TODAY.minusDays(b); h.add(DayLog.visit("회사", false, at(d, 18, 0))); h.add(DayLog.visit("집", true, at(d, 19, 0))); h.add(app(at(d, 19, 3), "com.netflix.mediaclient", "넷플릭스")); h.add(app(at(d, 12, 0), "com.kakao.talk", "카카오톡")); }
        Habits.Suggestion home = suggest(h);
        check(home != null, "home, then Netflix");
        same("visit:집|com.netflix.mediaclient", home.key(), "its key"); same("집", home.place(), "the place");
        same("집에 오면 넷플릭스 자주 켜더라. 다음부터 집에 오면 바로 열어 줄까?", home.say(), "said");
        Triggers.Trigger th = home.trigger(NOW);
        same(Triggers.PLACE, th.when().kind(), "a place"); same(Triggers.ARRIVE, th.when().edge(), "arriving"); same("집", th.when().name(), "home");
        check(!th.when().located(), "found on the phone (자주 가는 곳)");
        same("집에 도착하면 매번 넷플릭스 열어 줘", th.said(), "the words kept");
        // Leaving work is no moment, and home is not work: the same app at work has no habit there.
        List<DayLog.Event> work = new ArrayList<>();
        for (int b = 1; b <= 3; b++) { LocalDate d = TODAY.minusDays(b); work.add(DayLog.visit("회사", false, at(d, 18, 0))); work.add(app(at(d, 18, 2), "com.netflix.mediaclient", "넷플릭스")); }
        check(suggest(work) == null, "leaving is not arriving");
    }
    static void keeping() {
        Habits.Suggestion s = suggest(drives(new int[]{5, 4, 3, 2, 1}, new int[]{5, 4, 3, 2, 1}));
        same(s, Habits.Suggestion.of(s.json()), "kept and read back");
        check(Habits.Suggestion.of(new JSONObject()) == null, "nothing: none");
        check(Habits.Suggestion.of(new JSONObject().put("key", "x").put("app", "y").put("kind", "weather")) == null, "an unknown moment: none");
        Map<String, Habits.Asked> a = new LinkedHashMap<>();
        a.put("car|a", new Habits.Asked(Habits.SHOWN, 1, NOW)); a.put("charge|b", new Habits.Asked(Habits.REFUSED, 1, NOW - DAY));
        same(a, Habits.load(Habits.save(a)), "the pairs kept and read back");
        check(Habits.load("{broken").isEmpty(), "unreadable: none");
        check(Habits.load("{\"x\":{\"s\":\"maybe\"}}").isEmpty(), "an unknown state is left out");
        Map<String, Habits.Asked> many = new LinkedHashMap<>();
        for (int i = 0; i < Habits.KEPT + 10; i++) many = Habits.shown(many, "car|app" + i, NOW + i);
        same(Habits.KEPT, many.size(), "at most KEPT");
        check(!many.containsKey("car|app0") && many.containsKey("car|app" + (Habits.KEPT + 9)), "the oldest go first");
    }
}
