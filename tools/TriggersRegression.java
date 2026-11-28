package dev.jun.gyeot.core;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.json.JSONObject;

/**
 * 사건 트리거 (core/Triggers): what people say to 퍼레 read into a condition and an action, once or every time; each kind of
 * event matched the way the phone sees it (a notice, a fix or a proximity alert, a Bluetooth link, the charger and the
 * battery, a price), fired once per event (edges, re-arming, cooldowns), never a send by itself; the words said back, the
 * list's lines, and how they are kept. Run by tools/build.py.
 */
final class TriggersRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** Friday 2026-11-27 18:00 in Seoul. */
    static final long AT = ZonedDateTime.of(2026, 11, 27, 18, 0, 0, 0, SEOUL).toInstant().toEpochMilli();
    static final long MIN = 60_000L;

    public static void main(String[] args) {
        conditions();
        actions();
        modes();
        notices();
        places();
        bluetooth();
        power();
        prices();
        words();
        keeping();
        System.out.println("Triggers regression checks passed: " + checked);
    }

    private static Triggers.Condition c(String words) { Triggers.Condition c = Triggers.condition(words); check(c != null, "a condition in: " + words); return c; }
    static void conditions() {
        // A place: arriving and leaving, by its name as said (found later: SavedPlaces, PlaceLocator).
        Triggers.Condition home = c("집에 도착하면");
        same(Triggers.PLACE, home.kind(), "집"); same(Triggers.ARRIVE, home.edge(), "arrive"); same("집", home.name(), "its name"); check(!home.located(), "not found yet");
        same("집", c("집 도착하면").name(), "without 에");
        same("우리 집", c("우리 집에 오면").name(), "우리 집");
        same("강남역", c("강남역으로 가면").name(), "으로 가면");
        same("을지로", c("을지로에 도착하면").name(), "a name ending in 로 keeps it");
        Triggers.Condition work = c("회사에서 나가면");
        same(Triggers.LEAVE, work.edge(), "leave"); same("회사", work.name(), "회사");
        same(Triggers.LEAVE, c("퇴근하면").edge(), "퇴근 is leaving work"); same("회사", c("출근하면").name(), "출근 is arriving at work");
        check(Triggers.condition("비 오면") == null, "weather is no place");
        check(Triggers.condition("엄마가 오면") == null, "a person coming is no place");
        // The car, and another device.
        Triggers.Condition car = c("차에 타면");
        same(Triggers.BLUETOOTH, car.kind(), "the car"); same(Triggers.CONNECT, car.edge(), "in"); check(car.car(), "it is the car");
        same(Triggers.CONNECT, c("차 타면").edge(), "차 타면"); same(Triggers.CONNECT, c("시동 걸면").edge(), "시동");
        same(Triggers.DISCONNECT, c("차에서 내리면").edge(), "out of the car"); same(Triggers.DISCONNECT, c("운전 끝나면").edge(), "운전 끝");
        check(!Triggers.BLUETOOTH.equals(Triggers.condition("기차에 타면") == null ? "" : Triggers.condition("기차에 타면").kind()), "a train is not the car");
        Triggers.Condition buds = c("버즈 연결되면");
        same(Triggers.BLUETOOTH, buds.kind(), "earbuds"); same("버즈", buds.name(), "their name"); check(!buds.car(), "not the car");
        same(Triggers.DISCONNECT, c("에어팟 끊기면").edge(), "AirPods gone");
        // The charger and the battery.
        same(Triggers.FULL, c("충전 다 되면").edge(), "full"); same(Triggers.FULL, c("완충되면").edge(), "완충"); same(Triggers.FULL, c("배터리 100% 되면").edge(), "100%");
        same(Triggers.PLUGGED, c("충전기 꽂으면").edge(), "plugged"); same(Triggers.UNPLUGGED, c("충전기 빼면").edge(), "unplugged");
        Triggers.Condition eighty = c("충전 80% 되면");
        same(Triggers.LEVEL_UP, eighty.edge(), "up to 80"); same(80.0, eighty.value(), "80");
        Triggers.Condition low = c("배터리 20% 아래로 떨어지면");
        same(Triggers.LEVEL_DOWN, low.edge(), "down to 20"); same(20.0, low.value(), "20");
        same(Triggers.LEVEL_DOWN, c("배터리 15% 남으면").edge(), "15% left");
        // A price: its line in won or dollars, or a % move.
        Triggers.Condition btc = c("비트코인 1억 넘으면");
        same(Triggers.PRICE, btc.kind(), "a price"); same("BTC", btc.id(), "BTC"); same("비트코인", btc.name(), "its name"); same(Triggers.ABOVE, btc.edge(), "above");
        same(1e8, btc.value(), "1억"); same(Triggers.KRW, btc.unit(), "won");
        Triggers.Condition eth = c("이더리움 500만 원 아래로 떨어지면");
        same(Triggers.BELOW, eth.edge(), "below"); same(5e6, eth.value(), "500만"); same(Triggers.KRW, eth.unit(), "원");
        Triggers.Condition usd = c("비트코인 7만 달러 넘으면");
        same(70_000.0, usd.value(), "7만 달러"); same(Triggers.USD, usd.unit(), "dollars");
        same(Triggers.USD, c("비트코인 10만 넘으면").unit(), "a small line with no unit is dollars");
        Triggers.Condition nvda = c("엔비디아 200달러 넘으면");
        same("NVDA", nvda.id(), "a stock"); same(Triggers.USD, nvda.unit(), "in dollars");
        Triggers.Condition up = c("비트코인 5% 오르면");
        same(Triggers.RISE, up.edge(), "a rise"); same(5.0, up.value(), "5%");
        same(Triggers.FALL, c("이더 10프로 떨어지면").edge(), "a fall");
        same(Triggers.MOVE, c("비트코인 3% 움직이면").edge(), "either way");
        same(1.2e8, Triggers.amount("1억 2천만"), "1억 2천만"); same(1.2e8, Triggers.amount("1억2000만"), "1억2000만"); same(9.8e7, Triggers.amount("9,800만"), "9,800만");
        same(1.2e8, Triggers.amount("1.2억"), "1.2억"); same(70_000.0, Triggers.amount("70k"), "70k"); same(70_000.0, Triggers.amount("70,000달러"), "70,000");
        Triggers.Draft stockInWon = Triggers.read("엔비디아 30만 원 넘으면", "tell", "", "", "", null, "엔비디아 30만 원 넘으면 알려 줘");
        check(stockInWon.when() == null && stockInWon.why().contains("달러"), "a stock in won is said why: " + stockInWon.why());
        // Notices: a delivery's stage, a parcel's, a messenger's sender, any app's words.
        Triggers.Condition food = c("배달 출발하면");
        same(Triggers.NOTICE, food.kind(), "a notice"); same(Triggers.DELIVERY, food.sort(), "food"); same("onway", food.stage(), "on its way");
        same("near", c("배달 곧 오면").stage(), "near"); same("done", c("배달 오면").stage(), "come"); same("ready", c("조리 다 되면 배민").stage(), "ready");
        same("배민", c("배민 배달 출발하면").app(), "the app named");
        Triggers.Condition parcel = c("택배 오면");
        same(Triggers.PARCEL, parcel.sort(), "a parcel"); same(NoticeActions.DONE, parcel.stage(), "come");
        same(NoticeActions.OUT, c("택배 배송 출발하면").stage(), "out today"); same("쿠팡", c("쿠팡 택배 오면").app(), "the shop");
        Triggers.Condition mom = c("엄마한테 카톡 오면");
        same(Triggers.MESSAGE, mom.sort(), "a message"); same("엄마", mom.who(), "from"); same("카카오톡", mom.app(), "KakaoTalk");
        same("팀장님", c("팀장님한테서 문자 오면").who(), "한테서"); same("메시지", c("팀장님한테서 문자 오면").app(), "SMS");
        same("", c("카톡 오면").who(), "anyone");
        Triggers.Condition words = c("쿠팡에서 ‘반품 완료’ 알림 오면");
        same(Triggers.APP, words.sort(), "any app"); same("쿠팡", words.app(), "쿠팡"); same("반품 완료", words.words(), "its words");
        check(Triggers.condition("오늘 기분 어때") == null, "small talk is no condition");
        check(Triggers.timed("매일 아침 7시에 뉴스 정리해 줘"), "a time is a 예약 작업");
        check(!Triggers.timed("집에 도착하면 알려 줘"), "an event is not");
    }

    private static Triggers.Draft read(String heard) { return Triggers.read("", "", "", "", "", null, heard); }
    static void actions() {
        String[] s = Triggers.split("집에 도착하면 엄마한테 문자 보내 줘");
        same("집에 도착하면", s[0], "the condition"); same("엄마한테 문자 보내 줘", s[1], "the action");
        same("차에 탈 때", Triggers.split("차에 탈 때 지도 켜 줘")[0], "탈 때");
        same("비트코인 1억 넘으면", Triggers.split("비트코인 1억 넘으면 알려 줘")[0], "넘으면");
        check(Triggers.split("라면 먹고 싶어") == null, "라면 is food");
        // Telling, with what to remind of.
        Triggers.Draft tell = read("충전 다 되면 알려 줘");
        same(Triggers.TELL, tell.then().kind(), "tell"); same("", tell.then().text(), "nothing more");
        Triggers.Draft umbrella = read("집에서 나가면 우산 챙기라고 알려 줘");
        same("우산 챙기라고", umbrella.then().text(), "the reminder"); same(Triggers.LEAVE, umbrella.when().edge(), "leaving home");
        // An app, a route, music.
        Triggers.Draft map = read("차 타면 매번 네이버 지도 켜 줘");
        same(Triggers.OPEN_APP, map.then().kind(), "an app"); same("네이버 지도", map.then().target(), "which"); same(Triggers.EVERY, map.mode(), "매번");
        same("open_app", map.then().act().optString("tool"), "BuddyActs' act"); same("네이버 지도", map.then().act().optString("app"), "its app");
        Triggers.Draft route = read("회사에서 나가면 집으로 길 안내해 줘");
        same(Triggers.NAVIGATE, route.then().kind(), "a route"); same("집", route.then().target(), "home");
        Triggers.Draft song = read("차에 타면 아이유 노래 틀어 줘");
        same(Triggers.PLAY, song.then().kind(), "music"); same("아이유 노래", song.then().target(), "what");
        // Never a send by itself: a message, a KakaoTalk, a mail, a call are drafts the user sends.
        Triggers.Draft text = read("집에 도착하면 엄마한테 곧 들어간다고 문자 보내 줘");
        same(Triggers.DRAFT, text.then().kind(), "a draft"); same("message", text.then().tool(), "a text"); same("엄마", text.then().who(), "to mom");
        same("곧 들어간다", text.then().text(), "its words");
        Triggers.Draft kakao = read("회사에서 나가면 아내한테 카톡 보내 줘");
        same("share", kakao.then().tool(), "KakaoTalk's own share (the user picks the friend)"); same("카카오톡", kakao.then().act().optString("app"), "into KakaoTalk");
        Triggers.Draft byBrain = Triggers.read("집에 도착하면", "phone_job", "카톡으로 엄마한테 도착했다고 보내기", "", "", null, "집에 도착하면 엄마한테 카톡 보내 줘");
        same(Triggers.DRAFT, byBrain.then().kind(), "a job that sends is a draft too"); same("share", byBrain.then().tool(), "KakaoTalk");
        same(Triggers.DRAFT, Triggers.read("집에 도착하면", "message", "", "엄마", "도착", null, "").then().kind(), "the brain's message: a draft");
        Triggers.Draft jobThatSends = Triggers.read("집에 도착하면", "phone_job", "카톡으로 엄마한테 도착했다고 보내기", "", "", null, "");
        same(Triggers.DRAFT, jobThatSends.then().kind(), "a phone job that sends, with no words to go by: still only a draft");
        same(Triggers.DRAFT, Triggers.read("퇴근하면", "web_job", "팀장님께 퇴근 보고 메일 보내기", "", "", null, "").then().kind(), "a mail sent by a site job: a draft");
        same("call", read("차에서 내리면 엄마한테 전화 걸어 줘").then().tool(), "the dialer, the user presses call");
        // A job, with the same guards as the jobs (AutomationGate): never a payment, a transfer, a bank.
        Triggers.Draft cart = read("집에 도착하면 배민에서 지난번 메뉴 다시 담아 줘");
        same(Triggers.JOB, cart.then().kind(), "a job"); same("phone_job", cart.then().tool(), "on the phone"); same("배민", cart.then().app(), "in 배민");
        Triggers.Draft pay = read("집에 도착하면 쿠팡에서 결제해 줘");
        check(!pay.why().isEmpty() && pay.why().contains("결제"), "a payment is never set: " + pay.why());
        Triggers.Draft bank = Triggers.read("집에 도착하면", "phone_job", "잔액 확인", "", "", null, "집에 도착하면 국민은행 앱에서 잔액 확인해 줘");
        check(bank.why().isEmpty() || bank.why().contains("은행"), "a bank app's job: " + bank.why());
        Triggers.Draft bankApp = Triggers.read("집에 도착하면", "phone_job", "국민은행에서 잔액 보기", "", "", null, "");
        check(bankApp.then() == null || !"phone_job".equals(bankApp.then().tool()) || !bankApp.why().isEmpty() || bankApp.then().app().isEmpty(), "never a bank app's job");
        Triggers.Draft site = read("퇴근하면 학교 홈페이지에서 가정통신문 받아 줘");
        same("web_job", site.then().tool(), "a site's job");
        check(read("오늘 기분 어때").when() == null, "no condition, no trigger");
        // The user's words are the authority over what the quick model filled in (퍼레 자동 점검, 2026-11-27, nemotron bolt).
        Triggers.Draft invented = Triggers.read("배달 출발하면", "tell", "배달 출발하면", "", "배달 출발했어", true, "배달 출발하면 알려 줘");
        same(Triggers.ONCE, invented.mode(), "every=true the words never said"); same("", invented.then().text(), "a reminder the words never said");
        Triggers.Draft note = Triggers.read("집에 도착하면", "message", "엄마", "엄마", "알림", false, "집에 도착하면 엄마한테 문자 보내 줘");
        same("엄마", note.then().who(), "to mom"); same("", note.then().text(), "a body the words never said");
        Triggers.Draft fromMom = Triggers.read("카톡 오면", "message", "엄마", "엄마", "", true, "엄마한테 카톡 오면 알려 줘");
        same("엄마", fromMom.when().who(), "the condition in the user's words (the brain left mom out)"); same(Triggers.TELL, fromMom.then().kind(), "told, not a message");
        Triggers.Draft wrongTool = Triggers.read("차에 타면", "tell", "", "", "", null, "차에 타면 네이버 지도 열어 줘");
        same(Triggers.OPEN_APP, wrongTool.then().kind(), "the words say open, whatever the brain said");
        same("우산 챙기라고", Triggers.read("집에서 나가면", "tell", "", "", "우산 챙겨", null, "집에서 나가면 우산 챙기라고 알려 줘").then().text(), "the reminder in the user's words");
        // The quick model's real answers (buddy_eval, nemotron bolt, 2026-11-27), read with the words they came with.
        Triggers.Draft btcNoWhen = Triggers.read("", "tell", "비트코인 1억 넘으면", "", "", true, "비트코인 1억 넘으면 알려 줘");
        same(Triggers.ABOVE, btcNoWhen.when().edge(), "no when: read from the words"); same(1e8, btcNoWhen.when().value(), "1억"); same(Triggers.ONCE, btcNoWhen.mode(), "once");
        Triggers.Draft fullNoArgs = Triggers.read("", "", "", "", "", null, "충전 다 되면 알려 줘");
        same(Triggers.FULL, fullNoArgs.when().edge(), "nothing but make"); same(Triggers.TELL, fullNoArgs.then().kind(), "told");
        Triggers.Draft momWhat = Triggers.read("", "tell", "엄마 카톡", "", "", true, "엄마한테 카톡 오면 알려 줘");
        same("엄마", momWhat.when().who(), "mom from the words"); same("카카오톡", momWhat.when().app(), "KakaoTalk"); same(Triggers.ONCE, momWhat.mode(), "once");
        Triggers.Draft mapNavigate = Triggers.read("차 타면", "navigate", "네이버 지도", "", "", true, "차 타면 매번 네이버 지도 켜 줘");
        same(Triggers.OPEN_APP, mapNavigate.then().kind(), "the words say open the app (the model said navigate)"); same(Triggers.EVERY, mapNavigate.mode(), "매번");
        Triggers.Draft momText = Triggers.read("집에 도착하면", "message", "엄마", "", "", null, "집에 도착하면 엄마한테 문자 보내 줘");
        same(Triggers.DRAFT, momText.then().kind(), "a draft"); same("엄마", momText.then().who(), "to mom");
        same("엄마", Triggers.read("집에 도착하면", "message", "엄마", "", "", null, "").then().who(), "no words: the brain's what is who it goes to");
    }

    static void modes() {
        same(Triggers.ONCE, Triggers.mode("집에 도착하면 알려 줘"), "하면 … once");
        same(Triggers.EVERY, Triggers.mode("차 타면 매번 지도 켜 줘"), "매번");
        same(Triggers.EVERY, Triggers.mode("엄마한테 카톡 올 때마다 알려 줘"), "때마다");
        same(Triggers.EVERY, Triggers.mode("차에 탈 때 지도 켜 줘"), "할 때 is a habit");
        same(Triggers.ONCE, Triggers.mode("이번에 한 번만 알려 줘"), "한 번만");
        same(Triggers.EVERY, Triggers.read("차에 타면", "open_app", "네이버 지도", "", "", true, "").mode(), "the brain's every");
    }

    private static Triggers.Trigger make(String heard) {
        Triggers.Draft d = read(heard); check(d.when() != null && d.then() != null, "read: " + heard + " " + d.why());
        return Triggers.make(d.when(), d.then(), d.mode(), heard, AT);
    }
    private static Triggers.Outcome see(List<Triggers.Trigger> all, Triggers.Event e) { return Triggers.match(all, e, e.at()); }
    static void notices() {
        Triggers.Trigger onWay = make("배달 출발하면 알려 줘");
        List<Triggers.Trigger> all = List.of(onWay);
        // Cooking: not yet. On its way: once. On its way again (a live notice updates), near: nothing more.
        Triggers.Outcome cooking = see(all, Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "조리를 시작했어요", "cooking", "", AT + MIN));
        check(cooking.fired().isEmpty(), "not yet");
        Triggers.Outcome left = see(cooking.list(), Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "라이더가 배달을 시작했어요", "onway", "", AT + 20 * MIN));
        same(1, left.fired().size(), "fired as it left");
        same("배달 출발했대 🛵", Triggers.happened(left.fired().get(0).trigger(), left.fired().get(0).event()), "its words");
        check(left.list().get(0).done(), "once: done"); check(!left.list().get(0).active(), "and no longer listened for");
        check(see(left.list(), Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "곧 도착해요", "near", "", AT + 30 * MIN)).fired().isEmpty(), "told once");
        check(Triggers.kinds(left.list()).isEmpty(), "nothing to listen for now");
        // Every time: once per order (a cooldown of hours), the next order again.
        Triggers.Trigger every = Triggers.make(onWay.when(), onWay.then(), Triggers.EVERY, "배달 출발할 때마다", AT);
        Triggers.Outcome a = see(List.of(every), Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "", "onway", "", AT + MIN));
        Triggers.Outcome b = see(a.list(), Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "", "near", "", AT + 10 * MIN));
        Triggers.Outcome next = see(b.list(), Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "", "onway", "", AT + 26 * 60 * MIN));
        same(1, a.fired().size(), "the first order"); check(b.fired().isEmpty(), "the same order nearing"); same(1, next.fired().size(), "the next day's order");
        // Another app's notice does not count; a delivery app named counts only for it.
        Triggers.Trigger eats = make("쿠팡이츠 배달 오면 알려 줘");
        check(see(List.of(eats), Triggers.Event.notice("com.sampleapp", "배달의민족", Triggers.DELIVERY, "", "", "done", "", AT + MIN)).fired().isEmpty(), "배민 is not 쿠팡이츠");
        same(1, see(List.of(eats), Triggers.Event.notice("com.coupang.mobile.eats", "쿠팡이츠", Triggers.DELIVERY, "", "", "done", "", AT + MIN)).fired().size(), "쿠팡이츠");
        // A parcel: by its key, the same parcel's stage told once.
        Triggers.Trigger box = Triggers.make(c("택배 오면"), Triggers.Action.tell(""), Triggers.EVERY, "택배 올 때마다", AT);
        Triggers.Outcome p1 = see(List.of(box), Triggers.Event.notice("com.coupang.mobile", "쿠팡", Triggers.PARCEL, "", "배송 완료", NoticeActions.DONE, "parcelA", AT + MIN));
        Triggers.Outcome p2 = see(p1.list(), Triggers.Event.notice("com.coupang.mobile", "쿠팡", Triggers.PARCEL, "", "배송 완료", NoticeActions.DONE, "parcelA", AT + 5 * MIN));
        Triggers.Outcome p3 = see(p2.list(), Triggers.Event.notice("kr.co.cjlogistics", "CJ대한통운", Triggers.PARCEL, "", "배송 완료", NoticeActions.DONE, "parcelB", AT + 6 * MIN));
        Triggers.Outcome out = see(p3.list(), Triggers.Event.notice("kr.co.cjlogistics", "CJ대한통운", Triggers.PARCEL, "", "배송 출발", NoticeActions.OUT, "parcelC", AT + 9 * MIN));
        same(1, p1.fired().size(), "come"); check(p2.fired().isEmpty(), "the same parcel again"); same(1, p3.fired().size(), "another parcel"); check(out.fired().isEmpty(), "out today is not come");
        // A messenger's sender: mom's, not another's; a burst fires once.
        Triggers.Trigger mom = Triggers.make(c("엄마한테 카톡 오면"), Triggers.Action.tell(""), Triggers.EVERY, "엄마한테 카톡 올 때마다", AT);
        check(see(List.of(mom), Triggers.Event.notice("com.kakao.talk", "카카오톡", Triggers.MESSAGE, "민수", "뭐해", "", "", AT + MIN)).fired().isEmpty(), "not mom");
        check(see(List.of(mom), Triggers.Event.notice("com.samsung.android.messaging", "메시지", Triggers.MESSAGE, "엄마", "밥 먹었니", "", "", AT + MIN)).fired().isEmpty(), "a text is not a KakaoTalk");
        Triggers.Outcome m1 = see(List.of(mom), Triggers.Event.notice("com.kakao.talk", "카카오톡", Triggers.MESSAGE, "엄마💕", "밥 먹었니", "", "", AT + MIN));
        Triggers.Outcome m2 = see(m1.list(), Triggers.Event.notice("com.kakao.talk", "카카오톡", Triggers.MESSAGE, "엄마💕", "왜 답이 없어", "", "", AT + 2 * MIN));
        Triggers.Outcome m3 = see(m2.list(), Triggers.Event.notice("com.kakao.talk", "카카오톡", Triggers.MESSAGE, "엄마💕", "저녁에 와", "", "", AT + 30 * MIN));
        same(1, m1.fired().size(), "mom"); check(m2.fired().isEmpty(), "her next message in the same burst"); same(1, m3.fired().size(), "later, again");
        same("엄마한테서 카톡 왔어", Triggers.happened(m1.fired().get(0).trigger(), m1.fired().get(0).event()), "said");
        // Any app with its words.
        Triggers.Trigger refund = Triggers.make(c("쿠팡에서 ‘반품 완료’ 알림 오면"), Triggers.Action.tell(""), Triggers.ONCE, "", AT);
        check(see(List.of(refund), Triggers.Event.notice("com.coupang.mobile", "쿠팡", Triggers.APP, "쿠팡", "배송 완료", "", "", AT + MIN)).fired().isEmpty(), "other words");
        same(1, see(List.of(refund), Triggers.Event.notice("com.coupang.mobile", "쿠팡", Triggers.PARCEL, "쿠팡", "고객님의 반품 완료 안내", "", "", AT + MIN)).fired().size(), "its words, whatever sort the notice is");
        check(Triggers.appMatches("카톡", "com.kakao.talk", "KakaoTalk"), "카톡 is KakaoTalk"); check(Triggers.appMatches("문자", "com.google.android.apps.messaging", "Messages"), "문자");
        check(!Triggers.appMatches("쿠팡", "com.kakao.talk", "카카오톡"), "not another app");
    }

    static void places() {
        Triggers.Trigger home = Triggers.make(c("집에 도착하면").at("집", 37.5000, 127.0000), Triggers.Action.tell(""), Triggers.ONCE, "집에 도착하면 알려 줘", AT);
        // Made at home: the first fix only says it is in; leaving and coming back is the arrival.
        Triggers.Outcome first = see(List.of(home), Triggers.Event.fix(37.5001, 127.0001, 20, AT + MIN));
        check(first.fired().isEmpty(), "made at home: no arrival"); same("in", first.list().get(0).seen(), "seen in");
        Triggers.Outcome edge = see(first.list(), Triggers.Event.fix(37.5000 + 0.0018, 127.0, 20, AT + 30 * MIN));
        same("in", edge.list().get(0).seen(), "200 m out, inside the margin: no change");
        Triggers.Outcome away = see(edge.list(), Triggers.Event.fix(37.52, 127.0, 30, AT + 40 * MIN));
        same("out", away.list().get(0).seen(), "2 km away"); check(away.fired().isEmpty(), "leaving is not arriving");
        check(see(away.list(), Triggers.Event.fix(37.5, 127.0, 3000, AT + 50 * MIN)).fired().isEmpty(), "a 3 km-wide guess is no fix");
        Triggers.Outcome back = see(away.list(), Triggers.Event.fix(37.5003, 127.0002, 25, AT + 90 * MIN));
        same(1, back.fired().size(), "arrived"); same("집에 도착했어", Triggers.happened(back.fired().get(0).trigger(), null), "said");
        // Made away: Android's proximity alert for this one trigger.
        Triggers.Trigger work = Triggers.make(c("회사에서 나가면").at("회사", 37.56, 126.97), Triggers.Action.tell("우산 챙기라고"), Triggers.EVERY, "", AT - 10 * MIN);
        Triggers.Outcome in = see(List.of(work), Triggers.Event.proximity(work.id(), true, AT));
        check(in.fired().isEmpty(), "arriving is not leaving"); same("in", in.list().get(0).seen(), "in");
        check(see(in.list(), Triggers.Event.proximity("t-other", false, AT + MIN)).fired().isEmpty(), "another trigger's alert");
        Triggers.Outcome left = see(in.list(), Triggers.Event.proximity(work.id(), false, AT + 9 * 3_600_000L));
        same(1, left.fired().size(), "left work"); check(!left.list().get(0).done(), "every day: not done");
        Triggers.Outcome jitter = see(see(left.list(), Triggers.Event.proximity(work.id(), true, AT + 9 * 3_600_000L + 2 * MIN)).list(), Triggers.Event.proximity(work.id(), false, AT + 9 * 3_600_000L + 4 * MIN));
        check(jitter.fired().isEmpty(), "a flicker at the door fires once (cooldown)");
        // Made at home and primed with where the phone is now.
        Triggers.Trigger primed = Triggers.prime(home, Triggers.Event.fix(37.52, 127.0, 30, AT));
        same("out", primed.seen(), "primed out");
        same(1, see(List.of(primed), Triggers.Event.fix(37.5, 127.0, 20, AT + MIN)).fired().size(), "primed away: arriving right after fires");
        check(Triggers.meters(37.5, 127.0, 37.51, 127.0) > 1100 && Triggers.meters(37.5, 127.0, 37.51, 127.0) < 1120, "about 1.1 km");
    }

    static void bluetooth() {
        Triggers.Trigger car = Triggers.make(c("차에 타면").device("AA:BB:CC:DD:EE:FF"), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "차 타면 매번 네이버 지도 켜 줘", AT);
        check(see(List.of(car), Triggers.Event.bluetooth(Triggers.CONNECT, "11:22:33:44:55:66", "Galaxy Buds2", AT + MIN)).fired().isEmpty(), "another device");
        check(see(List.of(car), Triggers.Event.bluetooth(Triggers.DISCONNECT, "aa:bb:cc:dd:ee:ff", "MY CAR", AT + MIN)).fired().isEmpty(), "getting out is not getting in");
        Triggers.Outcome in = see(List.of(car), Triggers.Event.bluetooth(Triggers.CONNECT, "aa:bb:cc:dd:ee:ff", "MY CAR", AT + MIN));
        same(1, in.fired().size(), "in the car (the address, any case)");
        check(see(in.list(), Triggers.Event.bluetooth(Triggers.CONNECT, "AA:BB:CC:DD:EE:FF", "MY CAR", AT + 3 * MIN)).fired().isEmpty(), "a dropped link coming back is the same drive");
        same(1, see(in.list(), Triggers.Event.bluetooth(Triggers.CONNECT, "AA:BB:CC:DD:EE:FF", "MY CAR", AT + 2 * 3_600_000L)).fired().size(), "the next drive");
        // Not picked yet: a car's name is the car; the words for another device find it by its name.
        Triggers.Trigger unpicked = Triggers.make(c("차에 타면"), Triggers.Action.tell(""), Triggers.ONCE, "", AT);
        same(1, see(List.of(unpicked), Triggers.Event.bluetooth(Triggers.CONNECT, "01:02:03:04:05:06", "Hyundai BT", AT + MIN)).fired().size(), "a carmaker's kit");
        check(see(List.of(unpicked), Triggers.Event.bluetooth(Triggers.CONNECT, "01:02:03:04:05:07", "Galaxy Buds2 Pro", AT + MIN)).fired().isEmpty(), "earbuds are no car");
        Triggers.Trigger buds = Triggers.make(c("버즈 연결되면"), Triggers.Action.play("잔잔한 음악", ""), Triggers.EVERY, "", AT);
        same(1, see(List.of(buds), Triggers.Event.bluetooth(Triggers.CONNECT, "01:02:03:04:05:07", "Galaxy Buds2 Pro", AT + MIN)).fired().size(), "버즈 is Buds");
        String[] picked = Triggers.pickDevice(c("차에 타면"), List.of(new String[]{"Galaxy Buds2", "11"}, new String[]{"KIA MOTORS", "22"}), "", "");
        check(picked != null && picked[1].equals("22"), "the one car among the paired");
        check(Triggers.pickDevice(c("차에 타면"), List.of(new String[]{"KIA", "1"}, new String[]{"BMW 5", "2"}), "", "") == null, "two cars: the user picks");
        same("33", Triggers.pickDevice(c("차에 타면"), List.<String[]>of(new String[]{"KIA", "1"}), "33", "우리 차")[1], "the car chosen for 호출어");
        same("2", Triggers.pickDevice(c("에어팟 연결되면"), List.of(new String[]{"Galaxy Buds", "1"}, new String[]{"Jun's AirPods Pro", "2"}), "", "")[1], "AirPods by name");
    }

    static void power() {
        Triggers.Trigger full = Triggers.prime(Triggers.make(c("충전 다 되면"), Triggers.Action.tell(""), Triggers.EVERY, "충전 다 될 때마다", AT), Triggers.Event.power("", false, 40, false, AT));
        same("u", full.seen(), "unplugged now"); check(full.armed(), "armed");
        Triggers.Outcome plug = see(List.of(full), Triggers.Event.power(Triggers.PLUGGED, true, -1, false, AT + MIN));
        check(plug.fired().isEmpty(), "plugged in is not full");
        Triggers.Outcome ninety = see(plug.list(), Triggers.Event.power("", true, 90, false, AT + 40 * MIN));
        Triggers.Outcome hundred = see(ninety.list(), Triggers.Event.power("", true, 100, true, AT + 60 * MIN));
        Triggers.Outcome still = see(hundred.list(), Triggers.Event.power("", true, 100, true, AT + 80 * MIN));
        check(ninety.fired().isEmpty(), "90"); same(1, hundred.fired().size(), "full"); check(still.fired().isEmpty(), "full stays full: told once");
        same("충전 다 됐어 (100%)", Triggers.happened(hundred.fired().get(0).trigger(), hundred.fired().get(0).event()), "said");
        Triggers.Outcome unplug = see(still.list(), Triggers.Event.power(Triggers.UNPLUGGED, false, -1, false, AT + 90 * MIN));
        Triggers.Outcome again = see(see(unplug.list(), Triggers.Event.power(Triggers.PLUGGED, true, -1, false, AT + 5 * 60 * MIN)).list(), Triggers.Event.power("", true, 100, true, AT + 7 * 60 * MIN));
        same(1, again.fired().size(), "the next charge");
        // Said while full: the next charge (it waits, and says so).
        Triggers.Trigger already = Triggers.prime(Triggers.make(c("충전 다 되면"), Triggers.Action.tell(""), Triggers.ONCE, "", AT), Triggers.Event.power("", true, 100, true, AT));
        check(!already.armed() && Triggers.waiting(already), "waits"); check(Triggers.said(already).contains("다음번"), Triggers.said(already));
        check(see(List.of(already), Triggers.Event.power("", true, 100, true, AT + MIN)).fired().isEmpty(), "not at once");
        // The charger in and out: the broadcast itself, or a reading that shows it changed.
        Triggers.Trigger in = Triggers.prime(Triggers.make(c("충전기 꽂으면"), Triggers.Action.tell(""), Triggers.EVERY, "", AT), Triggers.Event.power("", false, 50, false, AT));
        Triggers.Outcome cast = see(List.of(in), Triggers.Event.power(Triggers.PLUGGED, true, -1, false, AT + MIN));
        same(1, cast.fired().size(), "plugged in");
        check(see(cast.list(), Triggers.Event.power("", true, 51, false, AT + 2 * MIN)).fired().isEmpty(), "the reading after it is the same plugging");
        Triggers.Trigger late = Triggers.prime(Triggers.make(c("충전기 꽂으면"), Triggers.Action.tell(""), Triggers.EVERY, "", AT), Triggers.Event.power("", false, 50, false, AT));
        same(1, see(List.of(late), Triggers.Event.power("", true, 50, false, AT + 20 * MIN)).fired().size(), "a job's reading finds it plugged (the app was not running)");
        Triggers.Trigger out = Triggers.prime(Triggers.make(c("충전기 빼면"), Triggers.Action.tell(""), Triggers.ONCE, "", AT), Triggers.Event.power("", true, 70, false, AT));
        same(1, see(List.of(out), Triggers.Event.power(Triggers.UNPLUGGED, false, -1, false, AT + MIN)).fired().size(), "unplugged");
        // Levels: up while charging, down while not; once until it is no longer so.
        Triggers.Trigger eighty = Triggers.prime(Triggers.make(c("충전 80% 되면"), Triggers.Action.tell(""), Triggers.EVERY, "", AT), Triggers.Event.power("", true, 60, false, AT));
        Triggers.Outcome e1 = see(List.of(eighty), Triggers.Event.power("", true, 80, false, AT + 30 * MIN));
        Triggers.Outcome e2 = see(e1.list(), Triggers.Event.power("", true, 82, false, AT + 50 * MIN));
        same(1, e1.fired().size(), "80"); check(e2.fired().isEmpty(), "82: once");
        Triggers.Trigger low = Triggers.prime(Triggers.make(c("배터리 20% 아래로 떨어지면"), Triggers.Action.tell(""), Triggers.EVERY, "", AT), Triggers.Event.power("", false, 35, false, AT));
        check(see(List.of(low), Triggers.Event.power("", true, 19, false, AT + MIN)).fired().isEmpty(), "charging: not low");
        Triggers.Outcome l1 = see(List.of(low), Triggers.Event.power("", false, 20, false, AT + MIN));
        same(1, l1.fired().size(), "20 left"); same("배터리가 20% 남았어", Triggers.happened(l1.fired().get(0).trigger(), l1.fired().get(0).event()), "said");
        check(see(l1.list(), Triggers.Event.power("", false, 18, false, AT + 30 * MIN)).fired().isEmpty(), "18: once");
        check(Triggers.needsLevel(List.of(eighty)) && !Triggers.needsLevel(List.of(in)), "the level broadcast only when a level is asked");
        check(Triggers.needsCharging(List.of(in)) && !Triggers.needsCharging(List.of(out, low)), "the charging job only for what happens while charging");
    }

    static void prices() {
        Triggers.Trigger btc = Triggers.make(c("비트코인 1억 넘으면"), Triggers.Action.tell(""), Triggers.ONCE, "비트코인 1억 넘으면 알려 줘", AT);
        // The first price read only says where it is; then a crossing fires once.
        Triggers.Outcome first = see(List.of(btc), Triggers.Event.price("BTC", Triggers.KRW, 9.7e7, AT + 5 * MIN));
        check(first.fired().isEmpty(), "first read"); check(first.list().get(0).armed(), "under the line: armed");
        check(see(first.list(), Triggers.Event.price("BTC", Triggers.USD, 1.2e8, AT + 6 * MIN)).fired().isEmpty(), "dollars are not won");
        check(see(first.list(), Triggers.Event.price("ETH", Triggers.KRW, 1.2e8, AT + 6 * MIN)).fired().isEmpty(), "another coin");
        Triggers.Outcome crossed = see(first.list(), Triggers.Event.price("BTC", Triggers.KRW, 1.0023e8, AT + 10 * MIN));
        same(1, crossed.fired().size(), "crossed");
        same("비트코인이 1억 원을 넘었어 (지금 1억 23만 원)", Triggers.happened(crossed.fired().get(0).trigger(), crossed.fired().get(0).event()), "said, in plain words");
        check(crossed.list().get(0).done(), "once: done");
        // Every time: it must come back under (by more than a hair) before it fires again.
        Triggers.Trigger every = Triggers.make(c("비트코인 1억 넘으면"), Triggers.Action.tell(""), Triggers.EVERY, "", AT);
        Triggers.Outcome a = see(see(List.of(every), Triggers.Event.price("BTC", Triggers.KRW, 9.9e7, AT)).list(), Triggers.Event.price("BTC", Triggers.KRW, 1.001e8, AT + 5 * MIN));
        Triggers.Outcome hair = see(see(a.list(), Triggers.Event.price("BTC", Triggers.KRW, 9.99e7, AT + 40 * MIN)).list(), Triggers.Event.price("BTC", Triggers.KRW, 1.0002e8, AT + 80 * MIN));
        Triggers.Outcome back = see(see(hair.list(), Triggers.Event.price("BTC", Triggers.KRW, 9.8e7, AT + 120 * MIN)).list(), Triggers.Event.price("BTC", Triggers.KRW, 1.01e8, AT + 160 * MIN));
        same(1, a.fired().size(), "first crossing"); check(hair.fired().isEmpty(), "a hair under and back is the same crossing"); same(1, back.fired().size(), "down and up again");
        // Already over the line when first read: it waits for the next crossing.
        Triggers.Outcome over = see(List.of(btc), Triggers.Event.price("BTC", Triggers.KRW, 1.05e8, AT));
        check(over.fired().isEmpty() && !over.list().get(0).armed(), "already over: waits");
        // A % move from the first price, counted again after each time.
        Triggers.Trigger rise = Triggers.make(c("비트코인 5% 오르면"), Triggers.Action.tell(""), Triggers.EVERY, "", AT);
        Triggers.Outcome base = see(List.of(rise), Triggers.Event.price("BTC", Triggers.USD, 70_000, AT));
        same(70_000.0, base.list().get(0).when().base(), "its base");
        check(see(base.list(), Triggers.Event.price("BTC", Triggers.USD, 73_000, AT + 40 * MIN)).fired().isEmpty(), "+4.3%");
        Triggers.Outcome five = see(base.list(), Triggers.Event.price("BTC", Triggers.USD, 73_600, AT + 40 * MIN));
        same(1, five.fired().size(), "+5.1%"); same(73_600.0, five.list().get(0).when().base(), "counted again from here");
        check(see(five.list(), Triggers.Event.price("BTC", Triggers.USD, 74_000, AT + 80 * MIN)).fired().isEmpty(), "+0.5% from the new base");
        same("비트코인이 5% 넘게 올랐어 (지금 73,600달러)", Triggers.happened(five.fired().get(0).trigger(), five.fired().get(0).event()), "said");
        Triggers.Trigger primed = Triggers.prime(btc, Triggers.Event.price("BTC", Triggers.KRW, 1.05e8, AT));
        check(Triggers.waiting(primed) && Triggers.said(primed).contains("내려갔다가"), "said back: it waits: " + Triggers.said(primed));
        // Where a price is read, and what is read.
        same(List.of("https://api.upbit.com/v1/ticker?markets=KRW-BTC"), Triggers.quoteUrls("BTC", Triggers.KRW), "won from Upbit");
        same("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT", Triggers.quoteUrls("BTC", Triggers.USD).get(0), "dollars from Binance");
        same(List.of("https://fapi.binance.com/fapi/v1/ticker/price?symbol=NVDAUSDT"), Triggers.quoteUrls("NVDA", Triggers.USD), "a stock's tokenized future");
        same(70123.45, Triggers.quote("{\"symbol\":\"BTCUSDT\",\"price\":\"70123.45000000\"}"), "Binance");
        same(1.0023e8, Triggers.quote("[{\"market\":\"KRW-BTC\",\"trade_price\":1.0023E8}]"), "Upbit");
        check(Double.isNaN(Triggers.quote("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}")), "an error is no price");
        same(List.of("BTC", Triggers.KRW), List.of(Triggers.quotes(List.of(btc, every)).get(0)), "each quote read once");
        same(1, Triggers.quotes(List.of(btc, every)).size(), "one read for both");
        same("1억 23만 원", Triggers.money(1.0023e8, Triggers.KRW), "won"); same("9,870만 원", Triggers.money(9.87e7, Triggers.KRW), "만");
        same("870원", Triggers.money(870, Triggers.KRW), "small"); same("70,000달러", Triggers.money(70_000, Triggers.USD), "dollars"); same("0.25달러", Triggers.money(0.25, Triggers.USD), "cents");
    }

    static void words() {
        Triggers.Trigger car = Triggers.make(c("차에 타면").device("AA"), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "", AT);
        same("차에 타면 네이버 지도 열게. 매번.", Triggers.said(car), "said back (준's example)");
        same("차에 타면 → 네이버 지도 열기", Triggers.title(car), "the list's title");
        Triggers.Trigger home = make("집에 도착하면 알려 줘");
        same("집에 도착하면 알려 줄게. 한 번만.", Triggers.said(home), "once");
        same("충전이 다 되면 알려 줄게. 한 번만.", Triggers.said(make("충전 다 되면 알려 줘")), "full");
        same("비트코인이 1억 원을 넘으면 알려 줄게. 한 번만.", Triggers.said(make("비트코인 1억 넘으면 알려 줘")), "a price, no price of its own");
        same("엄마한테서 카톡 오면 알려 줄게. 한 번만.", Triggers.said(make("엄마한테 카톡 오면 알려 줘")), "mom");
        same("배달이 출발하면 알려 줄게. 한 번만.", Triggers.said(make("배달 출발하면 알려 줘")), "food");
        Triggers.Trigger text = make("집에 도착하면 엄마한테 문자 보내 줘");
        same("집에 도착하면 엄마한테 보낼 문자를 써 둘게. 보내기는 너가 눌러 줘. 한 번만.", Triggers.said(text), "a draft, said so");
        same("엄마한테 보낼 문자 써 뒀어, 눌러서 보내 줘", Triggers.did(text.then(), true), "when it fires");
        same("집에서 나가면 우산 챙기라고 알려 줄게. 한 번만.", Triggers.said(make("집에서 나가면 우산 챙기라고 알려 줘")), "a reminder");
        same("우산 챙기라고 했지!", Triggers.did(make("집에서 나가면 우산 챙기라고 알려 줘").then(), true), "its words when it fires");
        same("회사에서 나가면 집으로 길 안내할게. 한 번만.", Triggers.said(make("회사에서 나가면 집으로 길 안내해 줘")), "a route");
        same("눌러서 네이버 지도 열기", Triggers.did(car.then(), false), "when Android would not let it open");
        same("매번", Triggers.state(car, AT, SEOUL), "state"); same("매번 · 꺼짐", Triggers.state(car.switched(false), AT, SEOUL), "off");
        Triggers.Trigger fired = see(List.of(home), Triggers.Event.proximity(home.id(), true, AT + 10 * MIN)).list().get(0);
        same("한 번 · 끝남 · 오늘 18:10에 했어", Triggers.state(fired, AT + 20 * MIN, SEOUL), "done");
        same("걸어 둔 건 두 개야. 차에 타면 네이버 지도 열기, 매번. 집에 도착하면 알려 주기, 한 번.", Triggers.listSay(List.of(car, home)), "the list said");
        check(Triggers.listSay(List.of()).contains("없어"), "none");
        // Which one the words mean, to turn off or delete.
        Triggers.Trigger full = make("충전 다 되면 알려 줘");
        same(List.of(car), Triggers.named(List.of(car, home, full), "차 타면 지도 켜는 거"), "the car's map");
        same(List.of(full), Triggers.named(List.of(car, home, full), "충전 알림"), "the charger's");
        same(List.of(home), Triggers.named(List.of(home), ""), "the only one");
        check(Triggers.named(List.of(car, home), "").isEmpty(), "two and no words: asked which");
    }

    static void keeping() {
        Triggers.Trigger car = Triggers.make(c("차에 타면").device("AA:BB"), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "차 타면 매번 지도", AT);
        Triggers.Trigger btc = Triggers.prime(Triggers.make(c("비트코인 1억 넘으면"), Triggers.Action.tell(""), Triggers.ONCE, "", AT), Triggers.Event.price("BTC", Triggers.KRW, 9e7, AT));
        Triggers.Trigger home = Triggers.make(c("집에 도착하면").at("집", 37.5, 127.0), Triggers.Action.draft("message", "엄마", "곧 가", ""), Triggers.ONCE, "", AT);
        List<Triggers.Trigger> all = List.of(car, btc, home);
        same(all, Triggers.load(Triggers.save(all), AT), "kept and read back");
        JSONObject json = car.json();
        same("bluetooth", json.getJSONObject("when").getString("kind"), "its condition"); check(!json.getJSONObject("when").has("lat"), "no empty fields");
        Triggers.Trigger done = see(List.of(home), Triggers.Event.proximity(home.id(), true, AT + 10 * MIN)).list().get(0);
        same(1, Triggers.load(Triggers.save(List.of(done)), AT + 3_600_000L).size(), "a done once stays a while");
        same(0, Triggers.load(Triggers.save(List.of(done)), AT + 3 * 24 * 3_600_000L).size(), "then goes");
        same(0, Triggers.load("{broken", AT).size(), "unreadable: none");
        same(java.util.Set.of(Triggers.BLUETOOTH, Triggers.PRICE, Triggers.PLACE), Triggers.kinds(all), "what to listen for");
        same(java.util.Set.of(Triggers.PRICE, Triggers.PLACE), Triggers.kinds(Triggers.switched(all, car.id(), false)), "off: not listened for");
        same(2, Triggers.remove(all, car.id()).size(), "removed");
        List<Triggers.Trigger> many = new java.util.ArrayList<>();
        for (int i = 0; i < Triggers.MOST; i++) many = Triggers.add(many, Triggers.make(c("충전 다 되면"), Triggers.Action.tell(""), Triggers.ONCE, "", AT));
        try { Triggers.add(many, car); check(false, "full"); } catch (IllegalStateException full) { check(full.getMessage().contains(Triggers.MOST + "개"), "says how many"); }
        many = Triggers.replace(many, see(List.of(many.get(0)), Triggers.Event.power("", true, 100, true, AT + MIN)).list().get(0));
        same(Triggers.MOST, Triggers.add(many, car).size(), "a finished one makes room");
        // The structured spec (퍼레's routine suggestion after the 하루 타임라인 will call it): the same trigger as from words.
        Triggers.Trigger spec = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", "AA:BB"), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "", AT);
        same(Triggers.said(car), Triggers.said(spec), "the same words");
        same(1, see(List.of(spec), Triggers.Event.bluetooth(Triggers.CONNECT, "AA:BB", "", AT + MIN)).fired().size(), "and fires the same");
    }
}
