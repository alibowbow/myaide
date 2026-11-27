package dev.jun.gyeot.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 하루 타임라인 (core/DayLog): the user's day gathered into one stream, one small file a day, so 퍼레 answers "지난주 그 집
 * 어디였지?", "어제 뭐 했더라?", "그저께 주문한 거 뭐였지?" from real lines. The days and times said, what is asked about, what is
 * kept and what never is (codes, passwords, money), repeats collapsed, the caps, the files read once, the answers, and the
 * short digest the prompts carry. Run by tools/build.py.
 */
final class DayLogRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** Friday 2026-11-27 13:00 in Seoul. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    static final long NOW = ZonedDateTime.of(2026, 11, 27, 13, 0, 0, 0, SEOUL).toInstant().toEpochMilli();
    static long t(int month, int day, int hour, int minute) { return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, SEOUL).toInstant().toEpochMilli(); }
    static LocalDate d(int month, int day) { return LocalDate.of(2026, month, day); }

    public static void main(String[] args) throws Exception {
        periods();
        asks();
        words();
        privacy();
        notices();
        orders();
        places();
        buddy();
        talks();
        sessions();
        book();
        merging();
        caps();
        recall();
        digest();
        kinds();
        off();
        System.out.println("DayLog regression checks passed: " + checked);
    }

    // ── the days and times said ────────────────────────────────────
    static void period(String words, LocalDate from, LocalDate to) {
        DayLog.Range r = DayLog.period(words, TODAY);
        check(r != null, "a period in “" + words + "”");
        same(from, r.from(), "from of “" + words + "”"); same(to, r.to(), "to of “" + words + "”");
    }
    static void periods() {
        period("오늘 뭐 했지", TODAY, TODAY);
        period("어제 뭐 했더라", d(11, 26), d(11, 26));
        period("그저께 주문한 거 뭐였지", d(11, 25), d(11, 25));
        period("그제 간 데", d(11, 25), d(11, 25));
        period("지난주 그 집 어디였지", d(11, 16), d(11, 22));
        period("저번 주에 간 카페", d(11, 16), d(11, 22));
        period("지지난주에 뭐 시켰지", d(11, 9), d(11, 15));
        period("이번 주에 본 거", d(11, 23), TODAY);
        period("지난주 금요일에 간 곳", d(11, 20), d(11, 20));
        period("화요일에 시킨 거", d(11, 24), d(11, 24));
        period("금요일에 뭐 했어", d(11, 20), d(11, 20));
        period("11월 20일에 어디 갔었지", d(11, 20), d(11, 20));
        period("11/19 저녁에 간 데", d(11, 19), d(11, 19));
        period("사흘 전에 주문한 거", d(11, 24), d(11, 24));
        period("3일 전에 간 데", d(11, 24), d(11, 24));
        period("지난달에 간 식당", d(10, 1), d(10, 31));
        period("이번 달에 예약한 거", d(11, 1), TODAY);
        period("지난 주말에 간 데", d(11, 21), d(11, 22));
        period("요즘 자주 쓴 앱", d(11, 21), TODAY);
        period("12월 30일에 간 데", LocalDate.of(2025, 12, 30), LocalDate.of(2025, 12, 30));
        DayLog.Range night = DayLog.period("어젯밤에 시킨 거", TODAY);
        same(d(11, 26), night.from(), "어젯밤 is yesterday"); same(19, night.fromHour(), "from the evening");
        DayLog.Range evening = DayLog.period("어제 저녁에 간 데", TODAY);
        same(17, evening.fromHour(), "저녁 from five"); same(22, evening.toHour(), "to ten");
        check(DayLog.period("그 집 어디였지", TODAY) == null, "no period said");
        check(DayLog.period("전주에 간 집", TODAY) == null, "전주 is the city, not last week");
        same("지난주(11월 16일~22일)", DayLog.period("지난주 그 집", TODAY).label(), "the label says the days");
        same("어제(11월 26일)", DayLog.period("어제 뭐 했지", TODAY).label(), "yesterday's label");
        // The brain's own dates, when the words name none; a year it guessed wrong becomes this year's.
        DayLog.Range brain = DayLog.dates("2026-11-16", "2026-11-22", TODAY);
        same(d(11, 16), brain.from(), "from as given"); same(d(11, 22), brain.to(), "to as given");
        same(d(11, 20), DayLog.dates("2024-11-20", "", TODAY).from(), "a guessed year");
        check(DayLog.dates("", "", TODAY) == null, "none given");
        check(DayLog.dates("내일", "", TODAY) == null, "not a date");
    }

    // ── what is asked about ────────────────────────────────────────
    static void asks() {
        same(DayLog.Ask.PLACE, DayLog.ask("지난주 그 집 어디였지?"), "그 집");
        same(DayLog.Ask.PLACE, DayLog.ask("어제 간 카페 이름 뭐였지"), "카페");
        same(DayLog.Ask.ORDER, DayLog.ask("그저께 주문한 거 뭐였지?"), "주문");
        same(DayLog.Ask.ORDER, DayLog.ask("어제 배달 시킨 데 어디였더라"), "배달 before 어디");
        same(DayLog.Ask.PLAN, DayLog.ask("지난주 예약한 거 언제였지"), "예약");
        same(DayLog.Ask.DID, DayLog.ask("어제 뭐 했더라?"), "뭐 했더라");
        same(DayLog.Ask.APP, DayLog.ask("어제 무슨 앱 많이 썼지"), "앱");
        same(DayLog.Ask.NOTICE, DayLog.ask("아까 카톡 누구한테 왔었지"), "카톡");
        same(DayLog.Ask.TALK, DayLog.ask("어제 퍼레한테 시켰던 일 뭐였지"), "퍼레한테 시킨 일 is a talk, not food");
        same(DayLog.Ask.ANY, DayLog.ask("노가리 그거"), "nothing named");
        // Asking about the past: a time gone by and a word that asks back.
        for (String past : new String[]{"지난주 그 집 어디였지?", "어제 뭐 했더라?", "그저께 주문한 거 뭐였지?", "저번에 간 카페 이름 기억나?", "어제 내가 뭐 시켰지", "지난주 금요일에 어디 갔었지", "어제 유튜브 얼마나 봤지"})
            check(DayLog.asksPast(past), "asks the past: " + past);
        for (String now : new String[]{"오늘 날씨 어때", "내일 일정 뭐야", "어제 비 왔어?", "지금 몇 시야", "택배 언제 와?", "카톡 열어 줘", "근처 카페 찾아 줘"})
            check(!DayLog.asksPast(now), "not about the past: " + now);
    }
    static void words() {
        same(List.of(), DayLog.words("지난주 그 집 어디였지?"), "no content words");
        same(List.of("노가리"), DayLog.words("지난주에 간 노가리집"), "the place's own word, 집 and the josa off");
        same(List.of("강남"), DayLog.words("강남에서 먹은 거 뭐였지"), "where");
        same(List.of("치킨"), DayLog.words("그저께 시킨 치킨 어디서 시켰지"), "what");
        same(List.of("을지로", "노가리"), DayLog.words("을지로 노가리"), "two words");
        same(List.of("유튜브"), DayLog.words("어제 유튜브 얼마나 봤지"), "a past verb (ㅆ) is never a word");
        same(List.of(), DayLog.words("지난주에 맛있었던 데 어디였더라"), "맛있었던 is no word");
    }

    // ── never kept ─────────────────────────────────────────────────
    static void privacy() {
        check(DayLog.notice("com.samsung.android.messaging", "메시지", "1588-1234", "[Web발신] 인증번호 [482913]를 입력해 주세요", NOW) == null, "a code is never kept");
        check(DayLog.notice("com.kakao.talk", "카카오톡", "민수", "비밀번호 1234야", NOW) == null, "a password is never kept");
        DayLog.Event bank = DayLog.notice("com.kbstar.kbbank", "KB스타뱅킹", "입출금 알림", "급여 3,200,000원 입금 잔액 4,120,000원", NOW);
        check(bank != null, "a bank's notice is a line");
        same("알림", bank.text(), "a bank's notice is only 알림"); same("KB스타뱅킹", bank.app(), "its app");
        DayLog.Event card = DayLog.notice("com.samsung.android.messaging", "메시지", "신한카드", "[Web발신] 신한카드(1234) 승인 홍*동 12,000원 일시불 11/27 13:00 스타벅스", NOW);
        same("알림", card.text(), "a card payment by text is only 알림"); same("메시지", card.app(), "the messages app");
        check(DayLog.notice("com.toss", "토스", "토스", "송금 완료 50,000원", NOW).text().equals("알림"), "토스 is money");
        DayLog.Event masked = DayLog.clean(DayLog.Event.of(NOW, DayLog.PLAN, "카카오톡", "민수 010-1234-5678로 연락해", "", ""));
        check(masked != null && !masked.text().contains("5678"), "phone numbers masked: " + (masked == null ? "" : masked.text()));
        check(DayLog.clean(DayLog.Event.of(NOW, DayLog.TALK, "퍼레", "내 otp 코드 알려줄게", "", "")) == null, "an OTP in a talk is dropped");
        check(DayLog.clean(DayLog.Event.of(NOW, "Bad Kind!", "x", "y", "", "")) == null, "a kind must be a plain word");
        same("car", DayLog.clean(DayLog.Event.of(NOW, "car", "차", "차에 연결됨", "", "")).kind(), "a new kind from outside (사건 트리거)");
        DayLog.Event longOne = DayLog.clean(DayLog.Event.of(NOW, DayLog.NOTICE, "카카오톡카카오톡카카오톡카카오톡카카오톡", "가".repeat(300), "", ""));
        check(longOne.text().length() <= DayLog.TEXT && longOne.app().length() <= DayLog.APP_NAME, "cut short");
    }

    // ── notifications ──────────────────────────────────────────────
    static void notices() {
        DayLog.Event kakao = DayLog.notice("com.kakao.talk", "카카오톡", "엄마", "오늘 저녁 일찍 들어오니? 김치찌개 끓여 놨어 빨리 와", NOW);
        same(DayLog.NOTICE, kakao.kind(), "a notice"); same("카카오톡", kakao.app(), "its app");
        check(kakao.text().startsWith("엄마: 오늘 저녁 일찍"), "who and what: " + kakao.text());
        check(kakao.text().length() <= "엄마: ".length() + DayLog.BODY + 1, "a message body shortened: " + kakao.text());
        check(!kakao.text().contains("빨리 와"), "the rest left out: " + kakao.text());
        same("n:com.kakao.talk:엄마", kakao.group(), "one line per chat");
        DayLog.Event shop = DayLog.notice("com.coupang.mobile", "쿠팡", "오늘 도착 예정", "주문하신 삼다수 2L 6개 외 1건 상품이 오늘 도착할 예정이에요.", NOW);
        same("오늘 도착 예정", shop.text(), "an app's notice: its title");
        check(DayLog.notice("com.coupang.mobile", "쿠팡", "(광고) 블랙프라이데이", "최대 80% 할인 수신거부 080", NOW) == null, "ads are not kept");
        check(DayLog.notice("com.sampleapp", "배달의민족", "주문 접수", "BHC치킨 강남점에서 주문을 접수했어요", NOW) == null, "food is kept as an order, not a notice");
        DayLog.Event map = DayLog.notice("com.nhn.android.nmap", "네이버 지도", "목적지 근처예요", "을지로 노가리집까지 200m", NOW);
        same(DayLog.PLACE, map.kind(), "a map app's notice is a place");
    }

    // ── orders, parcels, bookings and plans ────────────────────────
    static void orders() {
        Deliveries.Delivery ordered = Deliveries.read("com.sampleapp", "배달의민족", "주문 접수", "BHC치킨 강남점에서 주문을 접수했어요. 40~50분 후 도착 예정", "", t(11, 25, 12, 30), SEOUL);
        check(ordered != null, "a 배민 order read");
        DayLog.Event o = DayLog.delivery(ordered, null);
        same(DayLog.ORDER, o.kind(), "an order"); same("배민", o.app(), "the app"); same("BHC치킨 강남점", o.place(), "the store");
        check(o.text().contains("주문") && o.text().contains("BHC치킨 강남점"), "what: " + o.text());
        Deliveries.Delivery cooking = Deliveries.read("com.sampleapp", "배달의민족", "", "조리를 시작했어요", "", t(11, 25, 12, 35), SEOUL);
        check(DayLog.delivery(cooking, ordered) == null, "the steps between are not lines");
        Deliveries.Delivery done = Deliveries.read("com.sampleapp", "배달의민족", "", "배달이 완료되었어요. 맛있게 드세요", "", t(11, 25, 13, 2), SEOUL);
        DayLog.Event came = DayLog.delivery(done, cooking);
        check(came != null && came.text().contains("배달 완료"), "it came: " + (came == null ? "" : came.text()));
        same("중식당 홍콩반점", DayLog.store("[중식당 홍콩반점] 주문이 접수되었어요", ""), "a bracketed store");
        same("교촌치킨 역삼점", DayLog.store("", "교촌치킨 역삼점에서 음식을 만들고 있어요"), "a store before 에서");
        same("BHC치킨 강남점", DayLog.store("", "주문 접수 BHC치킨 강남점에서 주문을 접수했어요. 40~50분 후 도착 예정"), "the notice's words ahead of the store left out (a live notice's texts joined)");
        same("", DayLog.store("", "주문이 접수되었습니다"), "no store said");

        NoticeActions.Act parcel = NoticeActions.read("com.samsung.android.messaging", "메시지", "1588-1255", "[Web발신]\n[CJ대한통운] 고객님의 상품이 배송출발 하였습니다.\n상품명: 무선 이어폰\n배송예정시간: 14~16시", t(11, 26, 9, 0), SEOUL);
        DayLog.Event p = DayLog.act(parcel, SEOUL);
        same(DayLog.ORDER, p.kind(), "a parcel is an order"); same("CJ대한통운", p.app(), "the courier");
        check(p.text().contains("무선 이어폰") && p.text().contains("배송 출발"), "what and how far: " + p.text());
        NoticeActions.Act table = NoticeActions.read("com.catchtable.android", "캐치테이블", "캐치테이블", "[캐치테이블] 예약 확정 안내\n을지로 노가리집\n11월 19일 (목) 19:00, 2명", t(11, 18, 15, 0), SEOUL);
        check(table != null, "a booking read");
        DayLog.Event b = DayLog.act(table, SEOUL);
        same(DayLog.PLAN, b.kind(), "a booking is a plan"); same("을지로 노가리집", b.place(), "where");
        check(b.text().contains("예약") && b.text().contains("11월 19일") && b.text().contains("19:00"), "what and when: " + b.text());
        NoticeActions.Act minsu = NoticeActions.read("com.kakao.talk", "카카오톡", "민수", "내일 7시 강남역에서 보자", t(11, 26, 20, 0), SEOUL);
        DayLog.Event m = DayLog.act(minsu, SEOUL);
        same(DayLog.PLAN, m.kind(), "a messenger plan"); same("강남역", m.place(), "its place");
        check(m.text().contains("약속") && m.text().contains("11월 27일"), "when, as a date: " + m.text());
    }

    // ── places ─────────────────────────────────────────────────────
    static void places() {
        DayLog.Event nav = DayLog.navigated("네이버 지도", "navigation", "을지로 노가리집", "서울 중구 을지로 123", t(11, 19, 18, 40));
        same(DayLog.PLACE, nav.kind(), "navigation is a place"); same("네이버 지도", nav.app(), "the map app");
        same("‘을지로 노가리집’ 길 안내", nav.text(), "what"); check(nav.place().contains("을지로 노가리집") && nav.place().contains("서울 중구"), "with its address: " + nav.place());
        same("지도에서 ‘근처 카페’ 찾아봄", DayLog.navigated("네이버 지도", "search", "근처 카페", "", NOW).text(), "a map search");
        DayLog.Event area = DayLog.area("사천읍", NOW);
        same(DayLog.PLACE, area.kind(), "the neighbourhood"); same("area:사천읍", area.group(), "once while there");
        check(DayLog.area("", NOW) == null, "none when the phone does not know");
        DayLog.Event looked = DayLog.looked("강남역 맛집", "스시오마카세 강남점", "서울 강남구 …", NOW);
        same("‘강남역 맛집’ 찾아봄", looked.text(), "looked up"); check(looked.place().startsWith("스시오마카세 강남점"), "the first found");
    }

    // ── 퍼레's own acts and jobs ───────────────────────────────────
    static void buddy() {
        DayLog.Event alarm = DayLog.buddy(new JSONObject().put("tool", "alarm").put("hour", 7).put("minute", 30), true, NOW);
        same(DayLog.ACT, alarm.kind(), "퍼레's act"); same("알람 07:30", alarm.text(), "the alarm");
        DayLog.Event song = DayLog.buddy(new JSONObject().put("tool", "play").put("query", "아이유 좋은날").put("app", "멜론"), true, NOW);
        same("‘아이유 좋은날’ 틀기", song.text(), "a song"); same("멜론", song.app(), "where");
        DayLog.Event text = DayLog.buddy(new JSONObject().put("tool", "message").put("who", "엄마").put("text", "곧 도착해"), true, NOW);
        same("문자 써 둠 · 엄마", text.text(), "a text: who, never its words");
        DayLog.Event missed = DayLog.buddy(new JSONObject().put("tool", "open_app").put("app", "카카오톡"), false, NOW);
        same("카카오톡 열기 · 못 함", missed.text(), "not done is said");
        for (String quiet : new String[]{"torch", "volume", "brightness", "coin", "dice", "day_recall", "navigate", "map_search", "phone_job", "buddy_toy", "quiet"})
            check(DayLog.buddy(new JSONObject().put("tool", quiet), true, NOW) == null, "not a line: " + quiet);
        check(DayLog.buddy(new JSONObject().put("tool", "memo").put("text", "비밀번호 1234 적어 둬"), true, NOW) == null || DayLog.clean(DayLog.buddy(new JSONObject().put("tool", "memo").put("text", "비밀번호 1234 적어 둬"), true, NOW)) == null, "a secret memo is never kept");
        BuddyJobs.clear();
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.PHONE, "배민", "지난번 메뉴 다시 담기", NOW);
        DayLog.Event started = DayLog.job(job, NOW);
        same(DayLog.JOB, started.kind(), "a job"); same("배민", started.app(), "who does it"); same("job:" + job.id, started.group(), "one line a job");
        check(started.text().contains("지난번 메뉴 다시 담기") && started.text().contains("맡음"), "taken: " + started.text());
        BuddyJobs.end(job, BuddyJobs.State.DONE, "담았어요", NOW + 60_000);
        check(DayLog.job(job, NOW + 60_000).text().endsWith("끝남"), "done");
        BuddyJobs.clear();
    }

    // ── talks and titles ───────────────────────────────────────────
    static void talks() {
        List<String[]> turns = new ArrayList<>();
        turns.add(new String[]{"user", "내일 부산 가는데 날씨 어때?"}); turns.add(new String[]{"assistant", "맑대!"});
        turns.add(new String[]{"user", "그럼 우산 안 챙겨도 되겠다"}); turns.add(new String[]{"assistant", "응"});
        DayLog.Event talk = DayLog.talk(turns, 0, "퍼레", NOW);
        same(DayLog.TALK, talk.kind(), "a talk"); same("퍼레", talk.app(), "with 퍼레");
        check(talk.text().startsWith("‘내일 부산 가는데 날씨 어때") && talk.text().endsWith("외 1번"), "its topic: " + talk.text());
        List<String[]> worry = List.of(new String[]{"user", "요즘 회사 때문에 너무 우울해"}, new String[]{"assistant", "많이 힘들었구나"});
        check(DayLog.talk(worry, 0, "퍼레", NOW) == null, "a worry confided is not a line");
        List<String[]> guest = new ArrayList<>(); guest.add(new String[]{"user", "안녕 나는 손님이야", "g"});
        check(DayLog.talk(guest, 0, "퍼레", NOW) == null, "a guest's words never");
        List<String[]> short1 = new ArrayList<>(); short1.add(new String[]{"user", "응"}); check(DayLog.talk(short1, 0, "퍼레", NOW) == null, "a word or two is nothing");
        DayLog.Event room = DayLog.titled(DayLog.WORK, "작업창", "이번 달 지출 분석 보고서", "work:ab12", NOW);
        same("work:ab12", room.ref(), "what it opens");
    }

    // ── apps used ──────────────────────────────────────────────────
    static void sessions() {
        DayLog.Sessions s = new DayLog.Sessions();
        check(s.front("com.nhn.android.nmap", "네이버 지도", t(11, 27, 9, 0)) == null, "a first app starts");
        check(s.front("com.nhn.android.nmap", "네이버 지도", t(11, 27, 9, 3)) == null, "the same app again goes on");
        check(s.front("com.kakao.talk", "", t(11, 27, 9, 4)) == null, "not an app (the keyboard, the shade): nothing changes");
        DayLog.Event map = s.front("com.kakao.talk", "카카오톡", t(11, 27, 9, 12));
        check(map != null, "the map ended");
        same("네이버 지도", map.app(), "its name"); same(12 * 60_000L, map.duration(), "twelve minutes"); same(t(11, 27, 9, 0), map.at(), "from when");
        check(s.front("com.google.android.youtube", "YouTube", t(11, 27, 9, 12, 5)) == null, "a flick of five seconds is no session");
        DayLog.Event yt = s.away(t(11, 27, 10, 0));
        same(48 * 60_000L - 5_000L, yt.duration(), "home ends it");
        check(s.away(t(11, 27, 10, 5)) == null, "nothing in front");
        s.front("com.google.android.youtube", "YouTube", t(11, 27, 11, 0));
        same(DayLog.MAX_SESSION_MS, s.away(t(11, 27, 20, 0)).duration(), "a screen left on is cut at three hours");
        same("12분", DayLog.minutes(12 * 60_000L), "minutes"); same("1시간 20분", DayLog.minutes(80 * 60_000L), "hours"); same("1분", DayLog.minutes(20_000L), "under a minute");
    }
    static long t(int month, int day, int hour, int minute, int second) { return t(month, day, hour, minute) + second * 1000L; }

    // ── the files ──────────────────────────────────────────────────
    static File temp() throws Exception { File dir = Files.createTempDirectory("daylog").toFile(); dir.deleteOnExit(); return dir; }
    static void book() throws Exception {
        File dir = temp();
        DayLog.Book b = new DayLog.Book(dir, SEOUL);
        DayLog.Event a = b.put(DayLog.navigated("네이버 지도", "navigation", "을지로 노가리집", "서울 중구 을지로 123", t(11, 19, 18, 40)));
        check(a != null && !a.id().isEmpty(), "kept with an id");
        b.put(DayLog.area("을지로동", t(11, 19, 19, 30)));
        File file = new File(dir, "2026-11-19.jsonl");
        check(file.isFile(), "one file for the day");
        same(2, Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).size(), "a line each");
        same(2, b.day(d(11, 19)).size(), "read back");
        // A fresh book reads the file once, then keeps it while its size and time stay the same.
        DayLog.Book again = new DayLog.Book(dir, SEOUL);
        same(2, again.day(d(11, 19)).size(), "another book reads it");
        int reads = again.reads();
        again.day(d(11, 19)); again.count(d(11, 19)); again.day(d(11, 19));
        same(reads, again.reads(), "read once while unchanged");
        // What it writes itself it knows without reading.
        again.put(DayLog.Event.of(t(11, 19, 21, 0), DayLog.TALK, "퍼레", "‘내일 날씨’", "", ""));
        same(3, again.count(d(11, 19)), "counted"); same(reads, again.reads(), "its own line needs no read");
        // Another writer changed the file: read again.
        Files.writeString(file.toPath(), Files.readString(file.toPath()) + new JSONObject().put("i", "zz").put("t", t(11, 19, 22, 0)).put("k", "talk").put("a", "퍼레").put("x", "밤 인사") + "\n");
        file.setLastModified(file.lastModified() + 2000);
        same(4, again.count(d(11, 19)), "a changed file is read again");
        // A broken line is left out, the rest kept.
        Files.writeString(file.toPath(), Files.readString(file.toPath()) + "{not json\n");
        file.setLastModified(file.lastModified() + 2000);
        same(4, new DayLog.Book(dir, SEOUL).day(d(11, 19)).size(), "a broken line left out");
        // Days kept, oldest first; pruning by the days kept.
        b.put(DayLog.Event.of(t(8, 1, 10, 0), DayLog.TALK, "퍼레", "‘여름’", "", ""));
        b.put(DayLog.Event.of(t(11, 27, 10, 0), DayLog.TALK, "퍼레", "‘오늘’", "", ""));
        same(d(8, 1), b.first(), "the oldest day");
        same(1, b.prune(TODAY, 90), "a day past ninety goes");
        same(d(11, 19), b.first(), "then the oldest is");
        check(!new File(dir, "2026-08-01.jsonl").exists(), "its file gone");
        same(1, b.prune(TODAY, 7), "seven days keep only this week");
        same(TODAY, b.first(), "today stays");
        b.clear();
        same(null, b.first(), "모두 지우기"); same(0, b.count(TODAY), "nothing left");
        check(dir.list().length == 0, "no files left");
    }
    static void merging() throws Exception {
        DayLog.Book b = new DayLog.Book(temp(), SEOUL);
        // A chat's messages within half an hour are one line, counted; the same text again is no new message.
        b.put(DayLog.notice("com.kakao.talk", "카카오톡", "민수", "뭐해?", t(11, 27, 10, 0)));
        b.put(DayLog.notice("com.kakao.talk", "카카오톡", "민수", "뭐해?", t(11, 27, 10, 1)));
        b.put(DayLog.notice("com.kakao.talk", "카카오톡", "민수", "밥 먹었어?", t(11, 27, 10, 5)));
        b.put(DayLog.notice("com.kakao.talk", "카카오톡", "엄마", "저녁 먹었니", t(11, 27, 10, 6)));
        List<DayLog.Event> day = b.day(TODAY);
        same(2, day.size(), "one line per chat");
        DayLog.Event minsu = day.get(0);
        same(2, minsu.count(), "two messages"); same("민수: 밥 먹었어?", minsu.text(), "the latest"); same(t(11, 27, 10, 0), minsu.at(), "since the first"); same(t(11, 27, 10, 5), minsu.last(), "until the last");
        b.put(DayLog.notice("com.kakao.talk", "카카오톡", "민수", "자?", t(11, 27, 11, 30)));
        same(3, b.day(TODAY).size(), "a message after an hour is a new line");
        // App sessions next to each other are one.
        b.put(DayLog.Event.of(t(11, 27, 12, 0), DayLog.APP, "YouTube", "YouTube", "", "").withGroup("app:yt").withDuration(10 * 60_000L));
        b.put(DayLog.Event.of(t(11, 27, 12, 12), DayLog.APP, "YouTube", "YouTube", "", "").withGroup("app:yt").withDuration(20 * 60_000L));
        DayLog.Event yt = b.day(TODAY).get(3);
        same(30 * 60_000L, yt.duration(), "two sessions two minutes apart are one"); same(4, b.day(TODAY).size(), "one line");
        b.put(DayLog.Event.of(t(11, 27, 13, 0), DayLog.APP, "YouTube", "YouTube", "", "").withGroup("app:yt").withDuration(5 * 60_000L));
        same(5, b.day(TODAY).size(), "a session after a while is its own");
        // A job's line says how it ended, in its place.
        BuddyJobs.clear();
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.WEB, "브라우저", "가정통신문 받기", t(11, 27, 9, 0));
        b.put(DayLog.job(job, t(11, 27, 9, 0)));
        BuddyJobs.end(job, BuddyJobs.State.FAILED, "로그인이 필요해요", t(11, 27, 9, 40));
        b.put(DayLog.job(job, t(11, 27, 9, 40)));
        long jobs = b.day(TODAY).stream().filter(e -> DayLog.JOB.equals(e.kind())).count();
        same(1L, jobs, "one line a job");
        check(b.day(TODAY).stream().filter(e -> DayLog.JOB.equals(e.kind())).findFirst().get().text().endsWith("막힘"), "how it ended");
        BuddyJobs.clear();
        // The same place again the same hour is one line, counted.
        b.put(DayLog.navigated("네이버 지도", "navigation", "강남역", "", t(11, 27, 14, 0)));
        b.put(DayLog.navigated("네이버 지도", "navigation", "강남역", "", t(11, 27, 14, 10)));
        same(2, b.day(TODAY).stream().filter(e -> e.text().contains("강남역")).findFirst().get().count(), "asked twice");
        // Superseded lines are folded away when they pile up.
        DayLog.Book c = new DayLog.Book(temp(), SEOUL);
        for (int i = 0; i < 200; i++) c.put(DayLog.notice("com.kakao.talk", "카카오톡", "단톡방", "메시지 " + i, t(11, 27, 8, 0) + i * 1000L));
        same(1, c.day(TODAY).size(), "one chat"); same(200, c.day(TODAY).get(0).count(), "200 messages");
        check(Files.readAllLines(new File(c.dir, "2026-11-27.jsonl").toPath()).size() < 60, "folded: " + Files.readAllLines(new File(c.dir, "2026-11-27.jsonl").toPath()).size() + " lines");
        same(1, new DayLog.Book(c.dir, SEOUL).day(TODAY).size(), "still one when read again");
    }
    static void caps() throws Exception {
        DayLog.Book b = new DayLog.Book(temp(), SEOUL);
        for (int i = 0; i < 400; i++) b.put(DayLog.notice("com.app" + i, "앱" + i, "제목" + i, "내용", t(11, 27, 0, 0) + i * 60_000L));
        same(DayLog.most(DayLog.NOTICE), (int) b.day(TODAY).stream().filter(e -> DayLog.NOTICE.equals(e.kind())).count(), "notices capped");
        check(b.put(DayLog.navigated("네이버 지도", "navigation", "강남역", "", t(11, 27, 20, 0))) != null, "other kinds still kept");
        same(400 - DayLog.most(DayLog.NOTICE), b.dropped(TODAY), "what was left out is counted");
        long bytes = new File(b.dir, "2026-11-27.jsonl").length();
        check(bytes < 120_000, "a full day is small: " + bytes + " bytes");
    }

    // ── answers ────────────────────────────────────────────────────
    static DayLog.Book week() throws Exception {
        DayLog.Book b = new DayLog.Book(temp(), SEOUL);
        b.put(DayLog.act(NoticeActions.read("com.catchtable.android", "캐치테이블", "캐치테이블", "[캐치테이블] 예약 확정 안내\n을지로 노가리집\n11월 19일 (목) 19:00, 2명", t(11, 18, 15, 0), SEOUL), SEOUL));
        b.put(DayLog.navigated("네이버 지도", "navigation", "을지로 노가리집", "서울 중구 을지로 123", t(11, 19, 18, 40)));
        b.put(DayLog.area("을지로동", t(11, 19, 19, 30)));
        b.put(DayLog.notice("com.kakao.talk", "카카오톡", "민수", "오늘 노가리 맛있었다", t(11, 19, 22, 0)));
        Deliveries.Delivery ordered = Deliveries.read("com.sampleapp", "배달의민족", "주문 접수", "BHC치킨 강남점에서 주문을 접수했어요", "", t(11, 25, 12, 30), SEOUL);
        b.put(DayLog.delivery(ordered, null));
        b.put(DayLog.Event.of(t(11, 26, 9, 0), DayLog.APP, "YouTube", "YouTube", "", "").withGroup("app:yt").withDuration(80 * 60_000L));
        b.put(DayLog.Event.of(t(11, 26, 12, 0), DayLog.APP, "카카오톡", "카카오톡", "", "").withGroup("app:kakao").withDuration(25 * 60_000L));
        b.put(DayLog.navigated("네이버 지도", "navigation", "강남역", "", t(11, 26, 18, 50)));
        b.put(DayLog.buddy(new JSONObject().put("tool", "alarm").put("hour", 7).put("minute", 30), true, t(11, 26, 23, 0)));
        return b;
    }
    static DayLog.Source source(DayLog.Book b, List<DayLog.Event> calendar) {
        return (from, to) -> { List<DayLog.Event> all = new ArrayList<>(b.range(from, to)); for (DayLog.Event e : calendar) { LocalDate day = java.time.Instant.ofEpochMilli(e.at()).atZone(SEOUL).toLocalDate(); if (!day.isBefore(from) && !day.isAfter(to)) all.add(e); } return all; };
    }
    static String answer(DayLog.Book b, String heard, String query) { return DayLog.answer(DayLog.find(source(b, List.of()), b.first(), heard, query, "", "", TODAY, SEOUL), TODAY, SEOUL); }
    static void recall() throws Exception {
        DayLog.Book b = week();
        String place = answer(b, "지난주 그 집 어디였지?", "그 집");
        check(place.contains("을지로 노가리집"), "the place: " + place);
        check(place.contains("오후 6:40") || place.contains("7:00") || place.contains("19:00"), "with its time: " + place);
        check(place.contains("네이버 지도") || place.contains("캐치테이블"), "with the app: " + place);
        check(!place.contains("을지로동"), "the neighbourhood only when nothing better: " + place);
        check(place.length() <= DayLog.SPOKEN, "short enough to say: " + place.length());
        String order = answer(b, "그저께 주문한 거 뭐였지?", "주문한 거");
        check(order.contains("BHC치킨 강남점") && order.contains("배민") && order.contains("오후 12:30"), "the order: " + order);
        String did = answer(b, "어제 뭐 했더라?", "어제 한 일");
        check(did.startsWith("어제"), "about yesterday: " + did);
        check(did.contains("강남역") && did.contains("YouTube 1시간 20분") && did.contains("알람 07:30"), "the day's lines: " + did);
        check(!did.contains("노가리"), "nothing from another day: " + did);
        same("어제는 YouTube 1시간 20분 썼어.", answer(b, "어제 유튜브 얼마나 봤지", "어제 유튜브 시청 시간"), "apps used, added up");
        check(answer(b, "어제 무슨 앱 많이 썼지", "").startsWith("어제는 YouTube 1시간 20분, 카카오톡 25분"), "the apps of the day, most first");
        String word = answer(b, "노가리 먹은 데 어디였지", "노가리");
        check(word.contains("을지로 노가리집"), "found by its word: " + word);
        // Nothing found: what it looked through, never a guess.
        String none = answer(b, "지난주에 간 스타벅스 어디였지", "스타벅스");
        check(none.contains("없었어"), "says none: " + none);
        check(none.contains("11월 16일") && none.contains("길 안내") && none.contains("예약"), "says where it looked: " + none);
        check(!none.contains("노가리"), "no guess, nothing else said as if it were: " + none);
        // A kind of place (카페, 식당) is only a hint: the week's places, said as such.
        String cafe = answer(b, "지난주에 간 카페 어디였지", "카페");
        check(cafe.contains("‘카페’") && cafe.contains("을지로 노가리집"), "the week's places, with what was not there: " + cafe);
        String before = answer(b, "지난달에 시킨 거 뭐였지", "시킨 거");
        check(before.contains("11월 18일부터"), "says when the records begin: " + before);
        // A day with nothing: the days around it, said as such.
        String near = answer(b, "11월 20일에 간 데 어디였지", "간 데");
        check(near.contains("11월 20일") && near.contains("을지로 노가리집"), "widened to the days around: " + near);
        // The calendar's events are part of the day (read live, never stored).
        List<DayLog.Event> cal = List.of(DayLog.Event.of(t(11, 26, 9, 0), DayLog.CALENDAR, "캘린더", "치과", "강남 치과", ""));
        String withCal = DayLog.answer(DayLog.find(source(b, cal), b.first(), "어제 뭐 했더라", "", "", "", TODAY, SEOUL), TODAY, SEOUL);
        check(withCal.contains("치과"), "the calendar: " + withCal);
        String plan = DayLog.answer(DayLog.find(source(b, cal), b.first(), "어제 일정 뭐였지", "일정", "", "", TODAY, SEOUL), TODAY, SEOUL);
        check(plan.contains("치과") && plan.contains("오전 9:00"), "a plan from the calendar: " + plan);
        // The brain's dates when the words have none.
        String dated = DayLog.answer(DayLog.find(source(b, List.of()), b.first(), "그 집 어디였지", "그 집", "2026-11-16", "2026-11-22", TODAY, SEOUL), TODAY, SEOUL);
        check(dated.contains("을지로 노가리집"), "by the brain's dates: " + dated);
        // The line as said: when, the app, what.
        DayLog.Event nav = DayLog.navigated("네이버 지도", "navigation", "을지로 노가리집", "서울 중구 을지로 123", t(11, 19, 18, 40));
        same("지난주 목요일(11월 19일) 오후 6:40 · 네이버 지도 · ‘을지로 노가리집’ 길 안내 (서울 중구 을지로 123)", DayLog.line(nav, TODAY, SEOUL), "one line, with the address");
        same("어제 오후 6:50", DayLog.when(t(11, 26, 18, 50), TODAY, SEOUL), "yesterday");
        same("오늘 오전 9:05", DayLog.when(t(11, 27, 9, 5), TODAY, SEOUL), "today");
        same("11월 3일(화) 오전 10:00", DayLog.when(t(11, 3, 10, 0), TODAY, SEOUL), "a day long ago");
    }
    static void digest() throws Exception {
        DayLog.Book b = week();
        String yesterday = DayLog.digest(b.day(d(11, 26)), d(11, 26), SEOUL, 600);
        check(yesterday.startsWith("11월 26일(목)"), "its day: " + yesterday);
        check(yesterday.contains("YouTube 1시간 20분") && yesterday.contains("강남역") && yesterday.contains("알람 07:30"), "what was in it: " + yesterday);
        // A busy day still fits: the notices counted by app, not each one.
        DayLog.Book busy = new DayLog.Book(temp(), SEOUL);
        for (int i = 0; i < 300; i++) busy.put(DayLog.notice(i % 2 == 0 ? "com.kakao.talk" : "com.coupang.mobile", i % 2 == 0 ? "카카오톡" : "쿠팡", "사람" + (i % 40), "메시지 " + i, t(11, 26, 0, 0) + i * 240_000L));
        for (int i = 0; i < 60; i++) busy.put(DayLog.navigated("네이버 지도", "navigation", "장소" + i, "", t(11, 26, 0, 0) + i * 1_400_000L));
        String full = DayLog.digest(busy.day(d(11, 26)), d(11, 26), SEOUL, 700);
        check(full.length() <= 700, "capped: " + full.length());
        check(full.contains("카카오톡") && full.contains("쿠팡"), "the notices by app: " + full);
        check(!full.contains("메시지 1"), "no message words in the digest: " + full);
        same("", DayLog.digest(List.of(), d(11, 26), SEOUL, 700), "nothing for an empty day");
    }
    static void kinds() throws Exception {
        DayLog.Book b = week();
        // For a routine detector later (사건 트리거): one kind across days.
        b.put(DayLog.Event.of(t(11, 24, 8, 10), "car", "차", "차 블루투스 연결", "", ""));
        b.put(DayLog.Event.of(t(11, 25, 8, 12), "car", "차", "차 블루투스 연결", "", ""));
        b.put(DayLog.Event.of(t(11, 26, 8, 5), "car", "차", "차 블루투스 연결", "", ""));
        List<DayLog.Event> cars = b.kind("car", d(11, 20), TODAY);
        same(3, cars.size(), "three mornings"); same(t(11, 24, 8, 10), cars.get(0).at(), "oldest first");
        same(2, b.kind(DayLog.PLACE, d(11, 19), d(11, 19)).stream().filter(e -> e.text().contains("길 안내") || e.text().contains("근처")).count() == 2 ? 2 : -1, "places of a day");
    }
    static void off() throws Exception {
        File dir = temp();
        boolean[] on = {false};
        DayLog.open(new DayLog.Book(dir, SEOUL), () -> on[0], () -> 90);
        DayLog.add(DayLog.NOTICE, "카카오톡", "민수: 뭐해");
        DayLog.add(DayLog.Event.of(NOW, DayLog.PLACE, "네이버 지도", "‘강남역’ 길 안내", "", ""));
        DayLog.flush();
        same(0, dir.list().length, "off: nothing written");
        on[0] = true;
        DayLog.add(DayLog.Event.of(NOW, DayLog.PLACE, "네이버 지도", "‘강남역’ 길 안내", "", ""));
        DayLog.flush();
        same(1, DayLog.book().count(TODAY), "on: written off the caller's thread");
        same(DayLog.OFF, DayLog.answer(null, TODAY, SEOUL), "the answer when off");
        DayLog.open(null, () -> false, () -> 90);
    }
}
