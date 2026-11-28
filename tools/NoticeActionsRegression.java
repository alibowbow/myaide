package dev.jun.gyeot.core;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 알림을 행동으로 (core/NoticeActions): the notices of couriers, shops, bookings and messengers as they come on a Korean phone,
 * each read into its act (or left alone: ads, coupons, codes, money, a friend's chat). Run by tools/build.py.
 */
final class NoticeActionsRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** Friday 2026-11-27 13:00 in Seoul. */
    static final long AT = ZonedDateTime.of(2026, 11, 27, 13, 0, 0, 0, SEOUL).toInstant().toEpochMilli();
    static long t(int month, int day, int hour, int minute) { return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, SEOUL).toInstant().toEpochMilli(); }
    static NoticeActions.Act read(String pkg, String label, String title, String text) { return NoticeActions.read(pkg, label, title, text, AT, SEOUL); }
    static final String SMS = "com.samsung.android.messaging", KAKAO = "com.kakao.talk";

    public static void main(String[] args) {
        parcels();
        bookings();
        plans();
        neverRead();
        board();
        keeping();
        System.out.println("NoticeActions regression checks passed: " + checked);
    }

    private static NoticeActions.Act parcel(String pkg, String label, String title, String text, String stage, String name) {
        NoticeActions.Act a = read(pkg, label, title, text);
        check(a != null, "a parcel: " + text);
        same(NoticeActions.PARCEL, a.kind(), "kind of " + text);
        if (stage != null) same(stage, a.stage(), "stage of " + text);
        if (name != null) same(name, a.name(), "name of " + text);
        return a;
    }
    static void parcels() {
        NoticeActions.Act cj = parcel(SMS, "메시지", "1588-1255", "[Web발신]\n[CJ대한통운] 고객님의 상품이 배송출발 하였습니다.\n상품명: 무선 이어폰\n배송예정시간: 14~16시\n배송기사: 김기사 010-1234-5678", NoticeActions.OUT, "CJ대한통운");
        same("무선 이어폰", cj.what(), "the item");
        same(t(11, 27, 14, 0), cj.begin(), "from two"); same(t(11, 27, 16, 0), cj.end(), "to four"); check(cj.timed(), "a window");
        check(!cj.text().contains("1234-5678"), "the driver's number is not kept: " + cj.text());
        NoticeActions.Act today = parcel("com.coupang.mobile", "쿠팡", "오늘 도착 예정", "주문하신 삼다수 2L 6개 외 1건 상품이 오늘 도착할 예정이에요.", NoticeActions.OUT, "쿠팡");
        same("삼다수 2L 6개", today.what(), "쿠팡's item");
        NoticeActions.Act door = parcel("com.coupang.mobile", "쿠팡", "배송 완료", "[문 앞] 고객님의 상품(삼다수 2L, 6개 외 1건)이 배송 완료되었습니다.", NoticeActions.DONE, "쿠팡");
        same("문 앞", door.place(), "where it was left"); check(door.what().startsWith("삼다수"), "the item: " + door.what());
        NoticeActions.Act post = parcel(SMS, "메시지", "우체국", "[Web발신] [우체국택배] 고객님께 보내드린 소포(등기번호 6012345678901)가 오늘 13~15시 사이에 배달될 예정입니다. 집배원 010-2222-3333", NoticeActions.OUT, "우체국택배");
        same(t(11, 27, 13, 0), post.begin(), "the post's window");
        same("경비실", parcel(SMS, "메시지", "", "[한진택배] 고객님의 택배가 배송 완료되었습니다. 경비실에 보관하였습니다.", NoticeActions.DONE, "한진택배").place(), "한진 at the guard's");
        NoticeActions.Act lotte = parcel("com.lotteglogis.app", "롯데택배", "발송 안내", "[롯데택배] 주문하신 상품이 발송되었습니다. 운송장번호 2345-6789-0123 도착 예정일 11/30(월)", NoticeActions.SHIPPED, "롯데택배");
        same(t(11, 30, 0, 0), lotte.begin(), "the day it should come"); check(!lotte.timed(), "a day, no hour");
        parcel(SMS, "메시지", "", "[로젠택배] 고객님의 상품이 집하되었습니다. 배송조회 https://ilogen.com", NoticeActions.SHIPPED, "로젠택배");
        same("블루투스 키보드", parcel("com.nhn.android.search", "NAVER", "네이버페이", "주문하신 상품의 배송이 시작되었어요. 블루투스 키보드 외 1건", NoticeActions.SHIPPED, "네이버").what(), "네이버's item");
        parcel(SMS, "메시지", "", "[CJ대한통운] 고객님의 상품이 대전HUB에 도착했습니다.", NoticeActions.SHIPPED, "CJ대한통운");
        NoticeActions.Act app = parcel("com.cjkoreaexpress", "CJ대한통운", "배송 예정", "오늘 배송 예정입니다 (14~16시)", NoticeActions.OUT, "CJ대한통운");
        same(t(11, 27, 16, 0), app.end(), "the app's own window");
        parcel(KAKAO, "카카오톡", "CJ대한통운", "[CJ대한통운] 고객님의 택배가 오늘 배송될 예정입니다. 배송기사 홍길동", NoticeActions.OUT, "CJ대한통운");
        parcel(SMS, "메시지", "", "[Web발신] 쿠팡친구가 고객님의 로켓배송 상품을 배송하고 있습니다.", NoticeActions.OUT, "쿠팡");
        same("무인택배함", parcel(SMS, "메시지", "", "[롯데택배] 고객님의 상품을 무인택배함에 보관하였습니다.", NoticeActions.DONE, "롯데택배").place().replace(" ", ""), "a locker");
        check(read(SMS, "메시지", "", "[롯데택배] 고객님의 상품을 무인택배함에 보관하였습니다. 비밀번호 4821") == null, "a locker's password: never read");
        // A later day said: still on its way, not today.
        NoticeActions.Act tomorrow = parcel("com.coupang.mobile", "쿠팡", "배송 중", "고객님의 상품이 배송 중입니다. 내일(토) 도착 예정", NoticeActions.SHIPPED, "쿠팡");
        same(t(11, 28, 0, 0), tomorrow.begin(), "tomorrow");
        same(NoticeActions.OUT, parcel("com.ebay.kr.gmarket", "G마켓", "", "[G마켓] 고객님의 주문상품이 오늘 도착 예정입니다.", null, "G마켓").stage(), "G마켓 today");
        // Not parcels.
        check(read("com.elevenst", "11번가", "11번가", "[11번가] 오늘만 특가! 최대 70% 할인, 오늘 주문하면 내일 도착") == null, "an ad with 도착 in it");
        check(read("com.coupang.mobile", "쿠팡", "쿠팡", "(광고) 로켓배송 특가 🚀 오늘 도착 보장") == null, "a marked ad");
        check(read("com.coupang.mobile", "쿠팡", "", "와우 회원 전용 쿠폰이 도착했어요! 지금 확인하세요") == null, "a coupon arrived");
        check(read(KAKAO, "카카오톡", "엄마", "택배 왔더라 문 앞에 뒀어") == null, "a friend's chat about a parcel");
        check(read(SMS, "메시지", "", "[CJ대한통운] 인증번호 [482913]를 입력해 주세요") == null, "a verification code");
        check(read("com.sampleapp", "배달의민족", "", "배달이 완료되었어요. 맛있게 드세요!") == null, "food is Deliveries'");
        check(read("com.coupang.mobile.eats", "쿠팡이츠", "", "배달 파트너가 음식을 픽업했습니다") == null, "쿠팡이츠 is Deliveries'");
        check(read("com.coupang.mobile", "쿠팡", "", "반품 회수가 완료되었습니다") == null, "a return");
    }

    private static NoticeActions.Act booked(String pkg, String label, String title, String text, String sort) {
        NoticeActions.Act a = read(pkg, label, title, text);
        check(a != null, "a booking: " + text);
        same(NoticeActions.BOOKING, a.kind(), "kind of " + text);
        same(NoticeActions.BOOKED, a.stage(), "stage of " + text);
        same(sort, a.sort(), "sort of " + text);
        return a;
    }
    static void bookings() {
        NoticeActions.Act sushi = booked("com.nhn.android.search", "NAVER", "네이버 예약", "[네이버 예약] 예약이 확정되었습니다.\n스시오마카세 강남점\n11.30(월) 오후 7:00 · 2명", "food");
        same("스시오마카세 강남점 예약", sushi.what(), "what"); same("스시오마카세 강남점", sushi.place(), "where"); same(t(11, 30, 19, 0), sushi.begin(), "when"); same("네이버 예약", sushi.name(), "from");
        NoticeActions.Act table = booked("com.catchtable.android", "캐치테이블", "캐치테이블", "[캐치테이블] 예약 확정 안내\n레스토랑 밍글스\n12월 1일 (화) 18:30, 2명\n노쇼 시 위약금이 부과됩니다.", "food");
        same("레스토랑 밍글스 예약", table.what(), "캐치테이블's what"); same(t(12, 1, 18, 30), table.begin(), "캐치테이블's when");
        NoticeActions.Act skin = booked(KAKAO, "카카오톡", "OO피부과 강남점", "[예약 확정 안내]\n고객님의 예약이 확정되었습니다.\n■ 예약일시 : 2026년 11월 30일 (월) 오후 2:00\n■ 예약매장 : OO피부과 강남점", "hospital");
        same("OO피부과 강남점 진료", skin.what(), "알림톡's what"); same("OO피부과 강남점", skin.place(), "알림톡's place"); same(t(11, 30, 14, 0), skin.begin(), "알림톡's when");
        NoticeActions.Act kid = booked("com.ddocdoc.android", "똑닥", "진료 예약 확정", "[똑닥] 진료 예약이 확정되었어요. 연세소아과의원 12월 2일(수) 오전 10:30", "hospital");
        same("연세소아과의원 진료", kid.what(), "똑닥's what"); same(t(12, 2, 10, 30), kid.begin(), "똑닥's when"); same("연세소아과의원", kid.place(), "똑닥's place");
        NoticeActions.Act ktx = booked(SMS, "메시지", "1544-7788", "[Web발신]\n[코레일] 승차권 발권 완료\n11월 30일(월) KTX 023 서울(08:00)→부산(10:39)\n5호차 12A석", "train");
        same("KTX 023 서울→부산", ktx.what(), "the train"); same("서울역", ktx.place(), "from the station"); same(t(11, 30, 8, 0), ktx.begin(), "leaves"); same(t(11, 30, 10, 39), ktx.end(), "arrives");
        NoticeActions.Act srt = booked("kr.co.srail.newapp", "SRT", "SRT", "[SRT] 예매가 완료되었습니다.\n12월 5일(토) SRT 331 수서(09:00)→부산(11:31)\n4호차 8B", "train");
        same("SRT 331 수서→부산", srt.what(), "SRT"); same("수서역", srt.place(), "수서"); same(t(12, 5, 9, 0), srt.begin(), "SRT leaves");
        NoticeActions.Act talk = booked("com.korail.talk", "코레일톡", "승차권 예매 완료", "승차권 예매 완료 · 2026.12.05 KTX 101 서울 06:00 → 부산 08:45", "train");
        same(t(12, 5, 6, 0), talk.begin(), "a morning train stays in the morning"); same(t(12, 5, 8, 45), talk.end(), "and arrives");
        NoticeActions.Act ke = booked("com.koreanair.passenger", "대한항공", "대한항공", "[대한항공] 예약이 확정되었습니다. KE1203 김포(GMP) 12월 5일 07:00 → 제주(CJU) 08:10", "flight");
        same("KE1203 김포→제주", ke.what(), "the flight"); same("김포공항", ke.place(), "from the airport"); same(t(12, 5, 7, 0), ke.begin(), "takes off"); same(t(12, 5, 8, 10), ke.end(), "lands");
        NoticeActions.Act jeju = booked("com.jejuair.mobile", "제주항공", "항공권 발권 완료", "[제주항공] 항공권 발권 완료 7C101 12/05(토) 06:15 김포 → 제주", "flight");
        same("7C101 김포→제주", jeju.what(), "제주항공"); same(t(12, 5, 6, 15), jeju.begin(), "제주항공 at dawn");
        same("KE1203 김포→제주", booked("com.hanatour.app", "하나투어", "하나투어", "[하나투어] 항공권 발권 완료 KE1203 김포 12/05(토) 07:00 → 제주", "flight").what(), "a travel agency (not a bank by its name)");
        NoticeActions.Act cgv = booked("com.cgv.android", "CGV", "CGV", "[CGV] 예매가 완료되었습니다.\n듄: 파트3\nCGV강남 3관\n2026.11.30(월) 19:30~22:16\nH12, H13 (2매)", "movie");
        same("영화 듄: 파트3", cgv.what(), "the film"); same("CGV강남", cgv.place(), "the cinema"); same(t(11, 30, 19, 30), cgv.begin(), "starts"); same(t(11, 30, 22, 16), cgv.end(), "ends");
        NoticeActions.Act mega = booked("com.megabox.mop", "메가박스", "메가박스", "[메가박스] 위키드: 포 굿 | 코엑스 2관 | 11/30(월) 14:20 | 성인 2 예매완료", "movie");
        same("영화 위키드: 포 굿", mega.what(), "메가박스's film"); same("메가박스 코엑스", mega.place(), "메가박스's cinema"); same(t(11, 30, 14, 20), mega.begin(), "메가박스's time");
        NoticeActions.Act lotte = booked("com.lotte.lottecinema", "롯데시네마", "롯데시네마", "[롯데시네마] 예매완료 주토피아 2 / 월드타워 6관 / 12.01(화) 20:10 / F7,F8", "movie");
        same("영화 주토피아 2", lotte.what(), "롯데시네마's film"); same("롯데시네마 월드타워", lotte.place(), "롯데시네마's cinema"); same(t(12, 1, 20, 10), lotte.begin(), "롯데시네마's time");
        NoticeActions.Act show = booked("com.interpark.app.ticket", "인터파크 티켓", "인터파크 티켓", "[인터파크 티켓] 예매완료 안내\n뮤지컬 <레미제라블>\n2026.12.06(일) 19:30\n블루스퀘어 신한카드홀\nR석 2매", "show");
        same("뮤지컬 레미제라블", show.what(), "the musical"); same("블루스퀘어 신한카드홀", show.place(), "the hall"); same(t(12, 6, 19, 30), show.begin(), "the curtain");
        NoticeActions.Act gig = booked(SMS, "메시지", "", "[YES24 티켓] 김연우 콘서트 예매가 완료되었습니다. 일시: 12월 13일(일) 오후 6시 장소: 올림픽공원 핸드볼경기장", "show");
        same("김연우 콘서트", gig.what(), "the concert"); same("올림픽공원 핸드볼경기장", gig.place(), "the venue"); same(t(12, 13, 18, 0), gig.begin(), "six in the evening");
        NoticeActions.Act eye = booked(SMS, "메시지", "02-123-4567", "[Web발신] [서울밝은안과] 홍길동님 12월 3일(목) 오후 3시 30분 진료 예약되었습니다. 변경 문의 02-123-4567", "hospital");
        same("서울밝은안과 진료", eye.what(), "a clinic's own text"); same(t(12, 3, 15, 30), eye.begin(), "half past three");
        NoticeActions.Act cafe = booked("com.nhn.android.search", "NAVER", "네이버 예약", "[네이버 예약] 홍길동님, 블루보틀 성수 예약이 접수되었습니다. 이용일시: 2026.12.03(목) 15:00", "other");
        same("블루보틀 성수 예약", cafe.what(), "a request taken"); same("블루보틀 성수", cafe.place(), "its place");
        NoticeActions.Act stay = booked("com.goodchoice.abouthere", "여기어때", "여기어때", "[여기어때] 예약이 확정되었습니다. 강릉 씨마크호텔 체크인 12월 5일(토) 15:00", "stay");
        same("강릉 씨마크호텔 예약", stay.what(), "the hotel");
        // A booking called off: the same key, so its pill goes.
        NoticeActions.Act off = read("com.nhn.android.search", "NAVER", "네이버 예약", "[네이버 예약] 스시오마카세 강남점 11.30(월) 오후 7:00 예약이 취소되었습니다.");
        check(off != null && off.stage().equals(NoticeActions.CANCELED), "called off: " + off);
        same(sushi.key(), off.key(), "the same booking");
        // Not bookings.
        check(read("com.cgv.android", "CGV", "CGV", "(광고)[CGV] 이번 주말 예매하면 50% 할인! 지금 예매하세요") == null, "an ad");
        check(read("com.catchtable.android", "캐치테이블", "캐치테이블", "[캐치테이블] 예약 대기가 접수되었습니다. 자리가 나면 알려드릴게요") == null, "a waitlist");
        check(read(SMS, "메시지", "", "[코레일] 인증번호는 [4821]입니다") == null, "a code");
        check(read("com.hyundaicard.appcard", "현대카드", "현대카드", "[현대카드] 승인 59,800원 일시불 CGV 11/27 13:00") == null, "a card payment");
        check(read(SMS, "메시지", "", "[Web발신] 신한카드(1234)승인 홍*동 59,800원(일시불)11/27 13:00 코레일") == null, "a card payment by text");
        check(read("com.nhn.android.search", "NAVER", "네이버 예약", "[네이버 예약] 연말 모임 장소 예약하세요! 지금 예약 가능") == null, "an invitation to book");
        check(read(KAKAO, "카카오톡", "민지", "나 영화 예매 완료했어 ㅋㅋ") == null, "a friend's chat, no day or time");
    }

    private static NoticeActions.Act plan(String pkg, String label, String who, String text) {
        NoticeActions.Act a = read(pkg, label, who, text);
        check(a != null, "a plan: " + text);
        same(NoticeActions.PLAN, a.kind(), "kind of " + text);
        return a;
    }
    static void plans() {
        NoticeActions.Act minsu = plan(KAKAO, "카카오톡", "민수", "내일 7시 강남역에서 보자");
        same(t(11, 28, 19, 0), minsu.begin(), "tomorrow at seven in the evening"); same("강남역", minsu.place(), "강남역"); same("민수와 약속", minsu.what(), "with 민수"); same("민수", minsu.who(), "who");
        same("카카오톡", minsu.name(), "the app");
        NoticeActions.Act jihun = plan(KAKAO, "카카오톡", "지훈", "토요일 저녁 6시 반에 홍대입구역 9번 출구에서 만나");
        same(t(11, 28, 18, 30), jihun.begin(), "Saturday half past six"); same("홍대입구역 9번 출구", jihun.place(), "the exit"); same("지훈과 저녁", jihun.what(), "과 after a final consonant");
        NoticeActions.Act sujin = plan(KAKAO, "카카오톡", "수진", "이따 3시에 스타벅스 앞에서 봐");
        same(t(11, 27, 15, 0), sujin.begin(), "this afternoon"); same("스타벅스 앞", sujin.place(), "in front of it");
        NoticeActions.Act alex = plan("org.telegram.messenger", "Telegram", "Alex", "다음 주 금요일 12시에 점심 어때? 판교에서");
        same(t(12, 4, 12, 0), alex.begin(), "next week's Friday noon"); same("판교", alex.place(), "판교"); same("Alex와 점심", alex.what(), "lunch"); same("텔레그램", alex.name(), "Telegram");
        NoticeActions.Act mom = plan("com.google.android.apps.messaging", "메시지", "엄마", "11월 30일 오후 2시 성수동 카페에서 모이자");
        same(t(11, 30, 14, 0), mom.begin(), "a date said"); same("성수동 카페", mom.place(), "two words"); same("문자", mom.name(), "a text");
        same(t(11, 27, 19, 0), plan(KAKAO, "카카오톡", "민수", "7시에 보자").begin(), "no day: today, in the evening");
        NoticeActions.Act boss = plan(KAKAO, "카카오톡", "김팀장", "내일 10시 회의 잡혔어요");
        same(t(11, 28, 10, 0), boss.begin(), "ten in the morning"); check(boss.work(), "work");
        same(t(11, 28, 19, 0), plan("com.discord", "Discord", "jun", "see you at 7pm tomorrow").begin(), "English");
        same("", plan("com.discord", "Discord", "jun_dev", "오늘 밤 9시 디코에서 모이자").place(), "online is no place");
        same(t(11, 27, 21, 0), plan("com.discord", "Discord", "jun_dev", "오늘 밤 9시 디코에서 모이자").begin(), "nine at night");
        same("CGV", plan(KAKAO, "카카오톡", "민수", "내일 7시 CGV에서 영화 보자 예매했어").place(), "a film with a friend is a plan");
        // Not plans.
        check(read(KAKAO, "카카오톡", "민수", "내일 보자") == null, "no time");
        check(read(KAKAO, "카카오톡", "민수", "어제 7시에 봤잖아 ㅋㅋ") == null, "the past");
        check(read(KAKAO, "카카오톡", "OO마트", "(광고) 이번 주 토요일 7시 강남점 오픈! 꼭 오세요") == null, "an ad");
        check(read(KAKAO, "카카오톡", "민수", "6시에 끝나") == null, "no meeting");
        check(read(KAKAO, "카카오톡", "민수", "3시간 뒤에 보자") == null, "hours from now are not a clock time");
        check(read(KAKAO, "카카오톡", "민수", "미안 오늘 7시 약속 못 갈 것 같아") == null, "called off");
        check(read(KAKAO, "카카오톡", "민수", "몇 시에 볼까?") == null, "no hour");
        check(read("com.android.chrome", "Chrome", "민수", "내일 7시 강남역에서 보자") == null, "only messengers");
    }

    static void neverRead() {
        check(read(SMS, "메시지", "", "인증번호 1234를 7시까지 입력해 주세요") == null, "a code with a time");
        check(read(KAKAO, "카카오톡", "카카오뱅크", "내일 7시 이체 예정입니다") == null, "a bank");
        check(read("com.kbstar.kbbank", "KB스타뱅킹", "", "[KB] 내일 7시 자동이체 예정") == null, "a bank app");
        check(read("viva.republica.toss", "토스", "", "[토스] 쿠팡 결제 완료 32,000원, 내일 도착 예정") == null, "pay");
        check(read(SMS, "메시지", "", "[KB국민카드] 홍*동님 32,000원 승인 일시불 쿠팡 11/27 13:00") == null, "a card's approval naming a shop");
        check(read(SMS, "메시지", "", "[Web발신] [우리은행] 입금 50,000원 잔액 120,000원 11/27 13:00") == null, "a bank's text");
        check(read(KAKAO, "카카오톡", "민수", "내일 7시에 계좌로 보낼게") == null, "money in a chat is not a plan");
        check(NoticeActions.masked("010-1234-5678 2026-11-30 12345678").equals("# 2026-11-30 12345678"), "long numbers masked, dates and short numbers kept: " + NoticeActions.masked("010-1234-5678 2026-11-30 12345678"));
        same("대표번호 #, 문의 # 11/30 2026-11-30", NoticeActions.masked("대표번호 1544-7788, 문의 02-123-4567 11/30 2026-11-30"), "phone numbers however short");
        check(!read(SMS, "메시지", "1544-7788", "[코레일] 승차권 발권 완료\n11월 30일(월) KTX 023 서울(08:00)→부산(10:39)").text().contains("1544"), "an SMS sender's number is not kept");
        same("택배", NoticeActions.unread(SMS, "메시지", "", "[CJ대한통운] 고객님의 상품 배송 관련 안내입니다"), "a parcel notice not read");
        same("", NoticeActions.unread(KAKAO, "카카오톡", "엄마", "밥 먹었니?"), "a chat is nothing");
        same("", NoticeActions.unread(SMS, "메시지", "", "[CJ대한통운] 인증번호 [482913]"), "a code is nothing");
    }

    static void board() {
        long now = AT + 5 * 60_000L;
        NoticeActions.Act out = read(SMS, "메시지", "", "[CJ대한통운] 고객님의 상품이 배송출발 하였습니다. 상품명: 무선 이어폰 배송예정시간: 14~16시");
        List<NoticeActions.Pill> p = NoticeActions.pills(out, now, false, false);
        same(1, p.size(), "one pill"); same("택배", p.get(0).label(), "택배"); same("오늘 도착", p.get(0).word(), "오늘 도착"); same("act:open:" + out.key(), p.get(0).go(), "opens it");
        same("‘무선 이어폰’ 오늘 2~4시쯤 온대 📦", NoticeActions.line(out, now, SEOUL), "said as it sets off, the item named");
        same("", NoticeActions.line(out, AT + 4 * 3_600_000L, SEOUL), "not hours later");
        NoticeActions.Act came = read("com.coupang.mobile", "쿠팡", "배송 완료", "[문 앞] 고객님의 상품이 배송 완료되었습니다.");
        same("택배 문 앞에 왔대 📦", NoticeActions.line(came, now, SEOUL), "said as it comes");
        check(!NoticeActions.lineKey(out).equals(NoticeActions.lineKey(came)), "each stage its own once");
        same("택배(오늘 도착): CJ대한통운", NoticeActions.logLine(out, now, SEOUL), "the log's line");
        same("약속: 카카오톡 · 내일 19:00 · 장소 있음", NoticeActions.logLine(read(KAKAO, "카카오톡", "민수", "내일 7시 강남역에서 보자"), now, SEOUL), "never who or what was said");
        same("도착", NoticeActions.pills(came, now, false, false).get(0).word(), "come");
        check(NoticeActions.pills(came, AT + 4 * 3_600_000L, false, false).isEmpty(), "the come pill goes after a while");
        NoticeActions.Act sushi = read("com.nhn.android.search", "NAVER", "네이버 예약", "[네이버 예약] 예약이 확정되었습니다.\n스시오마카세 강남점\n11.30(월) 오후 7:00 · 2명");
        same("일정에 넣기", NoticeActions.pills(sushi, now, false, false).get(0).word(), "a booking: into the calendar");
        same("예약", NoticeActions.pills(sushi, now, false, false).get(0).label(), "its short name");
        check(NoticeActions.pills(sushi, now, true, false).isEmpty(), "already in the calendar: no pill");
        same("월 19:00 예약 잡혔대 🍽️", NoticeActions.line(sushi, now, SEOUL), "said once in passing");
        List<CalendarQuery.Event> cal = List.of(new CalendarQuery.Event("스시오마카세 저녁", t(11, 30, 19, 0), t(11, 30, 21, 0), false, "", "내 캘린더"));
        check(NoticeActions.inCalendar(sushi, cal), "the same name and time");
        check(!NoticeActions.inCalendar(sushi, List.of(new CalendarQuery.Event("치과", t(11, 30, 19, 0), t(11, 30, 20, 0), false, "", "내 캘린더"))), "another thing at that time");
        check(!NoticeActions.inCalendar(sushi, List.of(new CalendarQuery.Event("스시오마카세", t(11, 30, 12, 0), t(11, 30, 13, 0), false, "", "내 캘린더"))), "the same name at another time");
        NoticeActions.Act ktx = read(SMS, "메시지", "", "[코레일] 승차권 발권 완료\n11월 30일(월) KTX 023 서울(08:00)→부산(10:39)");
        same("KTX", NoticeActions.pills(ktx, now, false, false).get(0).label(), "KTX");
        same("월 8:00 기차표 끊었네 🚄", NoticeActions.line(ktx, now, SEOUL), "the train in passing");
        NoticeActions.Insert in = NoticeActions.insert(ktx, SEOUL);
        same("KTX 023 서울→부산", in.title(), "the event's name"); same(t(11, 30, 8, 0), in.begin(), "its begin"); same(t(11, 30, 10, 39), in.end(), "its end"); same("서울역", in.place(), "its place"); check(!in.allDay(), "timed");
        check(in.note().contains("코레일") && !in.note().contains("1544"), "a note: " + in.note());
        NoticeActions.Act minsu = read(KAKAO, "카카오톡", "민수", "내일 7시 강남역에서 보자");
        List<NoticeActions.Pill> two = NoticeActions.pills(minsu, now, false, true);
        same(2, two.size(), "into the calendar and a reply"); same("일정에 넣기", two.get(0).word(), "calendar first"); same("답장", two.get(1).word(), "then the reply"); same("민수", two.get(1).label(), "to whom");
        same(1, NoticeActions.pills(minsu, now, false, false).size(), "no reply where the notice cannot answer");
        same("민수랑 내일 19:00 약속 생겼네", NoticeActions.line(minsu, now, SEOUL), "the plan in passing");
        same("", NoticeActions.line(read(KAKAO, "카카오톡", "김팀장", "내일 10시 회의 잡혔어요"), now, SEOUL), "never work");
        same("좋아! 내일 7시에 강남역에서 봐", NoticeActions.draft(minsu, now, SEOUL), "a draft in the friend's tone");
        same("네, 내일 10시에 뵐게요!", NoticeActions.draft(read(KAKAO, "카카오톡", "김팀장", "내일 10시 회의 잡혔어요"), now, SEOUL), "a polite one");
        same("좋아, 7시에 강남역에서 봐!", NoticeActions.cleanDraft("<think>…</think>\n답장: \"좋아, 7시에 강남역에서 봐!\""), "the brain's draft cleaned");
        check(NoticeActions.cleanDraft("계좌 123-456으로 입금해 줘") == null, "no money in a draft");
        check(NoticeActions.draftInput(minsu, SEOUL).contains("<<<내일 7시 강남역에서 보자>>>"), "the message marked as data");
        // The board: the most useful first, a pill's go and the passing line.
        JSONArray rows = NoticeActions.board(List.of(out, sushi, minsu), now, SEOUL, Set.of(), Set.of(minsu.key()));
        same(3, rows.length(), "three acts");
        same(NoticeActions.PLAN, rows.getJSONObject(0).getString("kind"), "the plan first (sooner, with a reply)");
        same(NoticeActions.PARCEL, rows.getJSONObject(1).getString("kind"), "the parcel coming today next");
        same(NoticeActions.BOOKING, rows.getJSONObject(2).getString("kind"), "the booking three days off last");
        JSONObject first = rows.getJSONObject(0);
        same(2, first.getJSONArray("pills").length(), "its two pills");
        same("내일 19:00", first.getString("whenText"), "when, short");
        String words = NoticeActions.words(List.of(out, sushi, minsu), now, SEOUL);
        check(words.contains("택배(휴대폰 알림에서 읽음): CJ대한통운 · 무선 이어폰 — 배송 출발, 오늘 도착 예정 2~4시"), words);
        check(words.contains("예약(휴대폰 알림에서 읽음): 네이버 예약 · 스시오마카세 강남점 예약 — 11월 30일(월) 오후 7:00, 스시오마카세 강남점"), words);
        check(words.contains("약속(카카오톡 메시지에서 읽음): 민수와 약속 — 11월 28일(토) 오후 7:00, 강남역"), words);
    }

    static void keeping() {
        NoticeActions.Act shipped = read("com.lotteglogis.app", "롯데택배", "", "[롯데택배] 주문하신 상품이 발송되었습니다. 운송장번호 2345-6789-0123");
        NoticeActions.Act out = NoticeActions.read("com.lotteglogis.app", "롯데택배", "", "[롯데택배] 운송장번호 2345-6789-0123 상품이 오늘 배송 출발했습니다.", AT + 3_600_000L, SEOUL);
        same(shipped.key(), out.key(), "the same parcel by its tracking number");
        List<NoticeActions.Act> kept = NoticeActions.merge(List.of(), shipped, AT, SEOUL);
        kept = NoticeActions.merge(kept, out, AT + 3_600_000L, SEOUL);
        same(1, kept.size(), "the newer stage replaces it"); same(NoticeActions.OUT, kept.get(0).stage(), "out now");
        List<NoticeActions.Act> again = NoticeActions.load(NoticeActions.save(kept), AT + 3_600_000L, SEOUL);
        same(kept, again, "kept and read back");
        check(NoticeActions.current(out, AT + 12 * 3_600_000L, SEOUL) == null, "out today: gone the next day");
        NoticeActions.Act minsu = read(KAKAO, "카카오톡", "민수", "내일 7시 강남역에서 보자");
        check(NoticeActions.current(minsu, t(11, 28, 19, 30), SEOUL) != null, "until an hour after it begins");
        check(NoticeActions.current(minsu, t(11, 28, 20, 30), SEOUL) == null, "then gone");
        List<NoticeActions.Act> many = List.of();
        for (int i = 0; i < 7; i++) many = NoticeActions.merge(many, NoticeActions.read(KAKAO, "카카오톡", "친구" + i, "내일 " + (i + 1) + "시에 보자", AT + i * 1000L, SEOUL), AT + i * 1000L, SEOUL);
        same(NoticeActions.MOST, many.size(), "a few of each kind");
        same("친구6", many.get(many.size() - 1).who(), "the newest kept");
    }
}
