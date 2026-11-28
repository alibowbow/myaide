package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 매크로 (core/Macros): what a recording's accessibility events become (an app opened from the home screen is one open step,
 * typing in a field one step, a password never kept, a banking app's steps left out, a payment button a stop), how a step's
 * control is found again on a changed screen (words and view id first, a changed count or spacing still matches, its place
 * only as a last resort, a field only a field), the words that start, end and run one, what a run nobody watches asks
 * first, the plain line a run that stopped says, the steps of a phone job learned, and how they are kept. Run by
 * tools/build.py.
 */
final class MacrosRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    static final String OWN = "dev.jun.gyeot", LAUNCHER = "com.sec.android.app.launcher", KAKAO_T = "com.kakao.taxi", KAKAO = "com.kakao.talk", BANK = "com.kbstar.kbbank";
    static final int[] PHONE = {1080, 2340};

    public static void main(String[] args) {
        recording();
        keeping();
        finding();
        words();
        safety();
        lines();
        learned();
        repair();
        everywhere();
        System.out.println("Macros regression checks passed: " + checked);
    }

    static Macros.Target t(String text, String vid, int l, int top, int r, int b) { return new Macros.Target(vid, text, "", "", "android.widget.Button", "button", 0, new int[]{l, top, r, b}, PHONE, false, null); }
    static Macros.Target field(String hint, String vid, int top) { return new Macros.Target(vid, "", "", hint, "android.widget.EditText", "input", 0, new int[]{40, top, 1040, top + 140}, PHONE, true, null); }
    static Macros.Recording rec(String front) { return new Macros.Recording(Set.of(OWN, "com.android.systemui", "com.samsung.android.honeyboard"), LAUNCHER::equals, front, front.isEmpty() ? "" : "카카오T", 0); }

    static void recording() {
        // Started from this app: the home screen, then 카카오T from its icon, a tap, a field typed into letter by letter.
        Macros.Recording r = rec(OWN);
        r.window(LAUNCHER, "Launcher", "One UI 홈", 500);
        r.click(LAUNCHER, t("카카오T", "", 100, 100, 200, 200), false, 900); // the icon itself is no step
        r.window(KAKAO_T, "MainActivity", "카카오T", 1200);
        r.click(KAKAO_T, t("주차", "com.kakao.taxi:id/tab_parking", 0, 2200, 200, 2340), false, 3000);
        r.click(KAKAO_T, field("차량번호", "com.kakao.taxi:id/plate", 600), false, 4000);
        r.typed(KAKAO_T, field("차량번호", "com.kakao.taxi:id/plate", 600), "12", false, 4500);
        r.typed(KAKAO_T, field("차량번호", "com.kakao.taxi:id/plate", 600), "12가3456", false, 6000);
        r.click(OWN, t("끝", "", 0, 0, 10, 10), false, 6100); // our own bar: never a step
        Macros.Result done = r.finish();
        List<Macros.Step> s = done.steps();
        same(3, s.size(), "open, tap, type (the home screen and the tap that focused the field are no steps)");
        same(Macros.OPEN, s.get(0).kind(), "an app from the home screen is an open step"); same(KAKAO_T, s.get(0).app(), "its package"); same("카카오T", s.get(0).text(), "its name");
        same(Macros.TAP, s.get(1).kind(), "the tap"); same("주차", s.get(1).target().text(), "its label");
        same(Macros.TYPE, s.get(2).kind(), "typing is one step"); same("12가3456", s.get(2).text(), "the whole text at the end");
        check(s.get(2).target().edit() && s.get(2).target().text().isEmpty(), "a field's target never keeps its typed value");
        same(1800L, s.get(1).gap(), "the time since the step before, for patience");
        same("", done.note(), "nothing left out");

        // A password, and an OTP field by its hint: never kept, and the run stops there.
        Macros.Recording secret = rec(KAKAO_T);
        secret.typed(KAKAO_T, field("비밀번호", "pw", 600), "hunter2", true, 100);
        secret.typed(KAKAO_T, field("인증번호 6자리", "code", 900), "123456", false, 200);
        List<Macros.Step> ss = secret.finish().steps();
        check(ss.get(1).secret() && ss.get(1).text().isEmpty(), "a password field (Android says so) is never kept");
        check(ss.get(2).secret() && ss.get(2).text().isEmpty(), "a code field (its hint) is never kept either");
        check(!secret.finish().note().isEmpty(), "the save says so");

        // A banking app: its steps are not kept, and opening it is a stop.
        Macros.Recording bank = rec(KAKAO_T);
        bank.click(KAKAO_T, t("내 정보", "", 0, 0, 100, 100), false, 100);
        bank.window(LAUNCHER, "", "", 200);
        bank.window(BANK, "Main", "KB국민은행", 300);
        bank.click(BANK, t("이체", "", 0, 0, 100, 100), false, 400);
        bank.typed(BANK, field("받는 분 계좌", "acct", 300), "123-456", false, 500);
        Macros.Result banked = bank.finish();
        check(banked.money() && banked.dropped() == 2, "the banking app's tap and typing are left out: " + banked.dropped());
        Macros.Step bankOpen = banked.steps().get(banked.steps().size() - 1);
        check(Macros.OPEN.equals(bankOpen.kind()) && bankOpen.stop(), "opening it is kept as a stop");
        check(banked.note().contains("은행"), "the save says so");

        // A payment or deletion button is a stop, not a tap.
        Macros.Recording pay = rec(KAKAO_T);
        pay.click(KAKAO_T, t("결제하기", "", 0, 2000, 1080, 2200), false, 100);
        pay.click(KAKAO_T, t("대화 삭제", "", 0, 100, 400, 200), false, 200);
        pay.click(KAKAO_T, t("결제 내역", "", 0, 300, 400, 400), false, 300);
        List<Macros.Step> ps = pay.finish().steps();
        check(ps.get(1).stop() && ps.get(2).stop(), "결제하기 and 대화 삭제 are stops");
        check(!ps.get(3).stop(), "결제 내역 (a read) is a plain tap");

        // A tap that opens another app (a share to KakaoTalk): no open step, the tap does it again.
        Macros.Recording share = rec(KAKAO_T);
        share.click(KAKAO_T, t("공유", "", 0, 0, 100, 100), false, 1000);
        share.window(KAKAO, "ShareActivity", "카카오톡", 2500);
        share.click(KAKAO, t("엄마", "", 0, 500, 1080, 650), false, 3500);
        List<Macros.Step> sh = share.finish().steps();
        same(3, sh.size(), "open 카카오T, 공유, 엄마");
        same(KAKAO, sh.get(2).app(), "the tap in the app it opened");
        // The same app from the recents a while later is an open step.
        Macros.Recording recents = rec(KAKAO_T);
        recents.click(KAKAO_T, t("공유", "", 0, 0, 100, 100), false, 1000);
        recents.window(LAUNCHER, "Recents", "", 9000);
        recents.window(KAKAO, "Main", "카카오톡", 9500);
        recents.click(KAKAO, t("채팅", "", 0, 0, 100, 100), false, 10000);
        List<Macros.Step> rs = recents.finish().steps();
        same(List.of(Macros.OPEN, Macros.TAP, Macros.OPEN, Macros.TAP), kinds(rs), "home then the app: one open step (the home step before it goes)");

        // This app as the user's home screen: going there is going home, so the next app is an open step even right after a tap.
        Macros.Recording own = new Macros.Recording(Set.of(OWN, "com.android.systemui"), p -> p.equals(OWN) || p.equals(LAUNCHER), KAKAO_T, "카카오T", 0);
        own.click(KAKAO_T, t("주차", "", 0, 0, 10, 10), false, 1000);
        own.window(OWN, "HomeActivity", "들퍼레", 1500);
        own.click(OWN, t("카카오톡", "", 0, 0, 10, 10), false, 1800);
        own.window(KAKAO, "Main", "카카오톡", 2500);
        same(List.of(Macros.OPEN, Macros.TAP, Macros.OPEN), kinds(own.finish().steps()), "home (this app), then 카카오톡: an open step; this app's taps none");
        // Scrolling on in one list is one step; the bar's 뒤로 is a step; a trailing home screen and scroll are not.
        Macros.Recording scroll = rec(KAKAO_T);
        Macros.Target list = new Macros.Target("list", "", "", "", "androidx.recyclerview.widget.RecyclerView", "list", 0, new int[]{0, 200, 1080, 2200}, PHONE, false, null);
        scroll.scrolled(KAKAO_T, list, 0, 300, 100); scroll.scrolled(KAKAO_T, list, 0, 280, 300); scroll.scrolled(KAKAO_T, list, 0, 500, 500);
        scroll.click(KAKAO_T, t("주차 정산", "", 0, 1500, 1080, 1650), false, 900);
        scroll.back(1200);
        scroll.scrolled(KAKAO_T, list, 0, -300, 1500);
        scroll.window(LAUNCHER, "", "", 2000);
        List<Macros.Step> sc = scroll.finish().steps();
        same(List.of(Macros.OPEN, Macros.SCROLL, Macros.TAP, Macros.BACK), kinds(sc), "one scroll step, the tap, 뒤로; the trailing scroll and home go");
        same("down", sc.get(1).dir(), "scrolled forward");
        // Started while another app was in front (퍼레 called over it): that app opens first.
        Macros.Recording over = rec(KAKAO_T);
        over.click(KAKAO_T, t("주차", "", 0, 0, 10, 10), false, 100);
        same(Macros.OPEN, over.finish().steps().get(0).kind(), "the app in front opens first");
        // Recorded with no app opened: an open step is put first from the app of the first tap.
        Macros.Recording bare = new Macros.Recording(Set.of(OWN), LAUNCHER::equals, "", "", 0);
        bare.click(KAKAO_T, t("주차", "", 0, 0, 10, 10), false, 100);
        same(Macros.OPEN, bare.finish().steps().get(0).kind(), "an open step first");
        // Nothing done: no macro.
        try { rec(OWN).finish(); check(false, "an empty recording is refused"); } catch (IllegalStateException empty) { check(empty.getMessage().contains("녹화된 단계"), "says why"); }
    }
    static List<String> kinds(List<Macros.Step> steps) { List<String> out = new ArrayList<>(); for (Macros.Step s : steps) out.add(s.kind()); return out; }

    static Macros.Macro sample(String name) {
        return new Macros.Macro("", name, List.of(Macros.Step.open(KAKAO_T, "카카오T", 0), Macros.Step.of(Macros.TAP, KAKAO_T, t("주차 정산", "parking", 0, 1500, 1080, 1650), 1200),
            new Macros.Step(Macros.TYPE, KAKAO_T, "Main", field("차량번호", "plate", 600), "12가3456", "", 800, false, false)), 1000, 0, 0, "");
    }
    static void keeping() {
        Macros.Macro m = sample("주차 정산");
        List<Macros.Macro> list = Macros.save(new ArrayList<>(), m);
        List<Macros.Macro> back = Macros.parse(Macros.json(list));
        same(1, back.size(), "kept"); same("주차 정산", back.get(0).name(), "its name"); same(3, back.get(0).steps().size(), "its steps");
        Macros.Step typed = back.get(0).steps().get(2);
        same("12가3456", typed.text(), "typed text kept"); same("차량번호", typed.target().hint(), "the field's hint"); check(typed.target().edit(), "a field");
        same(m.id(), back.get(0).id(), "its id");
        same(0, Macros.parse("not json").size(), "an unreadable list is an empty one");
        same(1, Macros.parse("[{\"name\":\"x\"}, " + list.get(0).json() + ", {\"name\":\"y\",\"steps\":[{\"kind\":\"tap\"}]}]").size(), "broken ones are left out");
        try { Macros.save(list, sample("주차정산")); check(false, "a name taken (spacing aside) is refused"); } catch (IllegalArgumentException taken) { check(taken.getMessage().contains("이미 있어요"), "says why"); }
        try { Macros.save(list, sample(" ")); check(false, "an empty name is refused"); } catch (IllegalArgumentException empty) { checked++; }
        same(1, Macros.save(list, back.get(0).named("주차 정산하기")).size(), "renaming keeps one");
        same("주차 정산 2", Macros.freeName(list, "주차 정산"), "a free name");
        same(0, Macros.remove(list, m.id()).size(), "removed");
        check(Macros.summary(m).startsWith("3단계 · 카카오T"), "its row: " + Macros.summary(m));
        check(Macros.steps(m).contains("2. ‘주차 정산’ 누르기") && Macros.steps(m).contains("‘차량번호’ 칸에 ‘12가3456’ 입력"), "its steps in words: " + Macros.steps(m));
    }

    static JSONObject e(String id, String text, String vid, String flags, String role, int l, int top, int r, int b) {
        JSONObject o = obj("id", id, "role", role, "bounds", arr(l, top, r, b));
        if (!text.isEmpty()) put(o, "text", text);
        if (!vid.isEmpty()) put(o, "vid", vid);
        if (!flags.isEmpty()) put(o, "flags", flags);
        put(o, "cls", "input".equals(role) ? "android.widget.EditText" : "android.widget.Button");
        return o;
    }
    static void finding() {
        JSONArray screen = arr(
            e("e0", "홈", "tab_home", "click", "button", 0, 2200, 200, 2340),
            e("e1", "주차정산", "parking", "click", "button", 0, 1480, 1080, 1630),
            e("e2", "알림 5", "bell", "click", "button", 900, 100, 1080, 200),
            e("e3", "12가3456", "plate", "edit,click", "input", 40, 640, 1040, 780),
            e("e4", "", "", "click", "button", 950, 2200, 1080, 2340));
        same("e1", Macros.find(t("주차 정산", "parking", 0, 1500, 1080, 1650), screen, PHONE).id(), "spacing changed: still it");
        same("e2", Macros.find(t("알림 3", "", 900, 100, 1080, 200), screen, PHONE).id(), "a count changed: still it");
        same("e1", Macros.find(t("주차 요금 정산", "parking", 0, 1500, 1080, 1650), screen, PHONE).id(), "renamed, its view id finds it");
        check(Macros.find(t("결제", "", 0, 1500, 1080, 1650), screen, PHONE) == null, "another label in the same place is not it");
        check(Macros.find(t("주차 정산", "other_id", 0, 1500, 1080, 1650), screen, PHONE) != null, "a changed id with the same words still is");
        // A generic view id that several buttons share ("btn"): the words decide, and other words are never it.
        JSONArray generic = arr(e("e0", "취소", "btn", "click", "button", 0, 300, 540, 400), e("e1", "확인", "btn", "click", "button", 540, 300, 1080, 400));
        same("e1", Macros.find(t("확인", "btn", 540, 2000, 1080, 2100), generic, PHONE).id(), "a shared id: the words decide");
        check(Macros.find(t("확인", "btn", 0, 2000, 540, 2100), arr(e("e0", "취소", "btn", "click", "button", 0, 300, 540, 400)), PHONE) == null, "the same generic id with other words is not it");
        same("e3", Macros.find(field("차량번호", "plate", 600), screen, PHONE).id(), "a field by its id, whatever was typed in it");
        check(Macros.find(new Macros.Target("", "12가3456", "", "", "", "input", 0, new int[]{40, 1640, 1040, 1780}, PHONE, true, null), screen, PHONE) == null,
            "a field is never found by its typed text (it has no name): only near where it was");
        same("", new Macros.Target("", "12가3456", "", "", "", "input", 0, new int[4], PHONE, true, null).text(), "a field's typed text is never kept");
        check(Macros.find(field("메모", "memo", 600), screen, PHONE) == null, "a field with other words and id is not it");
        check(Macros.find(t("12가3456", "", 40, 640, 1040, 780), screen, PHONE) == null, "a button is never a field");
        // No words and no id (an icon): only near where it was.
        Macros.Target icon = new Macros.Target("", "", "", "", "android.widget.Button", "button", 0, new int[]{940, 2190, 1080, 2340}, PHONE, false, null);
        same("e4", Macros.find(icon, screen, PHONE).id(), "an unlabeled icon by its place");
        check(Macros.find(new Macros.Target("", "", "", "", "android.widget.Button", "button", 0, new int[]{0, 900, 140, 1040}, PHONE, false, null), screen, PHONE) == null,
            "an unlabeled control far from where it was is not found");
        // Rotated or another screen size: places as shares of the screen.
        Macros.Target wide = new Macros.Target("", "", "", "", "android.widget.Button", "button", 0, new int[]{2110, 1000, 2340, 1080}, new int[]{2340, 1080}, false, null);
        check(Macros.find(wide, arr(e("e0", "", "", "click", "button", 950, 2200, 1080, 2340)), PHONE) != null, "the same share of the screen on another size");
        // Text and description swapped between versions.
        JSONObject described = obj("id", "e9", "role", "button", "bounds", arr(0, 0, 100, 100), "desc", "검색", "flags", "click");
        same("e9", Macros.find(t("검색", "", 500, 500, 600, 600), arr(described), PHONE).id(), "a text now a description");
        // The label inside a row is gone (a dynamic title): the row itself by its id.
        Macros.Target row = new Macros.Target("", "오늘의 특가 3시간", "", "", "", "text", 0, new int[]{40, 800, 900, 860}, PHONE, false,
            new Macros.Target("deal_row", "", "", "", "android.widget.LinearLayout", "item", 2, new int[]{0, 780, 1080, 960}, PHONE, false, null));
        JSONArray deal = arr(e("e0", "내일의 특가", "", "", "text", 40, 800, 900, 860), e("e1", "", "deal_row", "click", "item", 0, 780, 1080, 960));
        Macros.Match host = Macros.find(row, deal, PHONE);
        check(host != null && "e1".equals(host.id()) && "host".equals(host.how()), "the row by its id: " + host);
        // Two of the same label: the one where it was.
        JSONArray twice = arr(e("e0", "더보기", "", "click", "button", 0, 300, 300, 400), e("e1", "더보기", "", "click", "button", 0, 1300, 300, 1400));
        same("e1", Macros.find(t("더보기", "", 0, 1310, 300, 1410), twice, PHONE).id(), "the nearer of two alike");
        same(2, Macros.alike("주차 정산", "주차정산"), "spacing is the same words");
        same(1, Macros.alike("받은 쪽지함", "받은쪽지함 (3)"), "a count is alike");
        same(0, Macros.alike("확인", "취소"), "other words");
    }

    static void words() {
        List<Macros.Macro> list = List.of(sample("출근 체크"), sample("배민"), sample("퇴근 문자 보내줘"));
        for (String said : new String[]{"출근 체크", "출근 체크 해 줘", "퍼레야 출근체크 해줘", "출근 체크 매크로 실행해 줘", "아까 만든 출근 체크 매크로 돌려 줘", "출근 체크 실행", "출근체크 돌려"})
            same("출근 체크", Macros.named(list, said) == null ? null : Macros.named(list, said).name(), "asks for it: " + said);
        for (String said : new String[]{"출근 체크하고 메일도 봐 줘", "출근하면 알려 줘", "배민에서 지난번 메뉴 담기", "체크"})
            check(Macros.named(list, said) == null, "not a run: " + said);
        same("퇴근 문자 보내줘", Macros.named(list, "퇴근 문자 보내줘").name(), "a name with asking words in it");
        // 퍼레 with no brain.
        JSONObject start = Macros.local(list, "퍼레야 매크로 녹화 시작", false, false);
        same("record", start.optString("command"), "start"); same("", start.optString("name"), "no name yet");
        same("주차 정산", Macros.local(list, "주차 정산 매크로 녹화해 줘", false, false).optString("name"), "a name said first");
        check(Macros.local(list, "녹화 시작해 줘", false, false) == null, "a bare 녹화 is the screen recording's");
        check(Macros.local(list, "녹화 끝", false, false) == null, "녹화 끝 only while one records");
        same("stop", Macros.local(list, "녹화 끝", true, false).optString("command"), "끝 while recording");
        same("stop", Macros.local(list, "매크로 저장해 줘", true, false).optString("command"), "저장 while recording");
        same("run", Macros.local(list, "출근 체크 해 줘", false, false).optString("command"), "run by name");
        check(Macros.local(list, "오늘 날씨 어때", false, false) == null, "other words");
        JSONObject save = Macros.local(list, "응 매크로로 저장해 줘, 이름은 배민 재주문으로 해", false, true);
        same("save", save.optString("command"), "save the offer"); same("배민 재주문", save.optString("name"), "its name");
        check(Macros.local(list, "매크로로 저장해 줘", false, false) == null || !"save".equals(Macros.local(list, "매크로로 저장해 줘", false, false).optString("command")), "no offer, no save");
    }

    static void safety() {
        Macros.Step send = Macros.Step.of(Macros.TAP, KAKAO, t("전송", "send", 900, 2200, 1080, 2340), 0);
        check(Macros.asks(send, "전송", true), "a run nobody watches asks before it sends");
        check(!Macros.asks(send, "전송", false), "a run the user just asked for does not");
        check(Macros.asks(Macros.Step.of(Macros.TAP, KAKAO, t("게시", "", 0, 0, 1, 1), 0), "게시", true), "posts ask");
        check(Macros.asks(Macros.Step.of(Macros.TAP, KAKAO, t("전화 걸기", "", 0, 0, 1, 1), 0), "전화 걸기", true), "calls ask");
        check(!Macros.asks(Macros.Step.of(Macros.TAP, KAKAO_T, t("주차", "", 0, 0, 1, 1), 0), "주차", true), "a plain tap does not");
        check(Macros.asks(new Macros.Step(Macros.SEND, KAKAO, "", null, "", "", 0, false, false), "", true), "a send step asks");
        // The gate reads each step's words as an ask to do it, never a pure read; each step's own, so a second "+" is no repeat.
        Macros.Macro m = sample("배민 재주문");
        check(!AutomationGate.pureRead(Macros.goal(m, 0)), "a run is no read: " + Macros.goal(m, 0));
        check(!Macros.goal(m, 0).equals(Macros.goal(m, 1)), "each step's words its own");
        check(!AutomationGate.requestsForbidden(Macros.goal(m, 2)), "and no forbidden ask");
        check(Macros.blocked(t("₩12,000 결제", "", 0, 0, 1, 1)), "a price that pays is blocked");
        check(!Macros.blocked(t("주차 정산", "", 0, 0, 1, 1)), "a plain control is not");
        same("open_app", Macros.action(Macros.Step.open(KAKAO_T, "카카오T", 0)).optString("action"), "open");
        same(KAKAO_T, Macros.action(Macros.Step.open(KAKAO_T, "카카오T", 0)).optString("app"), "by its package");
        same("set_text", Macros.action(sample("x").steps().get(2)).optString("action"), "type");
        same(8_000L, Macros.patience(Macros.Step.open(KAKAO_T, "", 0)), "at least 8 s");
        same(45_000L, Macros.patience(Macros.Step.of(Macros.TAP, KAKAO_T, t("a", "", 0, 0, 1, 1), 60_000)), "at most 45 s");
    }

    static void lines() {
        Macros.Macro m = sample("주차 정산");
        same("‘주차 정산’ 3단계: ‘차량번호’ 칸을 찾지 못했어요 · 화면이 바뀌었어요", Macros.line(m, 2, Macros.missing(m.steps().get(2)) + " · 화면이 바뀌었어요"), "the line a stopped run says");
        same("‘주차 정산’ 버튼을 찾지 못했어요", Macros.missing(m.steps().get(1)), "a button");
        same("‘카카오T’ 앱이 열리지 않았어요", Macros.missing(m.steps().get(0)), "an app");
        same("‘결제하기’는 직접 눌러 주세요 · 그 앞까지 했어요", Macros.stopped(Macros.Step.of(Macros.TAP, KAKAO_T, t("결제하기", "", 0, 0, 1, 1), 0).stopping()), "a stop before paying");
        same("‘구매 확정’은 직접 눌러 주세요 · 그 앞까지 했어요", Macros.stopped(Macros.Step.of(Macros.TAP, KAKAO_T, t("구매 확정", "", 0, 0, 1, 1), 0).stopping()), "은 after a final consonant");
        check(Macros.stopped(new Macros.Step(Macros.TYPE, KAKAO_T, "", field("비밀번호", "", 0), "", "", 0, true, false)).contains("직접 입력"), "a password: type it yourself");
        same("‘카카오T’ 열기", Macros.what(m.steps().get(0)), "open in words");
    }

    static void learned() {
        JSONObject before = obj("app", KAKAO_T, "screen", arr(1080, 2340), "elements", arr(
            e("e0", "주차", "", "click", "button", 0, 2200, 200, 2340), e("e1", "", "", "edit,click", "input", 40, 600, 1040, 740)));
        put(before.optJSONArray("elements").optJSONObject(1), "hint", "차량번호");
        List<JSONObject[]> done = new ArrayList<>();
        done.add(new JSONObject[]{obj("action", "open_app", "app", "카카오T"), null, obj("ok", true, "opened", KAKAO_T)});
        done.add(new JSONObject[]{obj("action", "tap", "element", "e0", "snapshot_id", "s1"), before, obj("ok", true)});
        done.add(new JSONObject[]{obj("action", "wait", "seconds", 2), before, obj("ok", true)});
        done.add(new JSONObject[]{obj("action", "set_text", "element", "e1", "text", "12가3456"), before, obj("ok", true)});
        List<Macros.Step> steps = Macros.learned(done);
        check(steps != null, "a phone job that went right is offered");
        same(List.of(Macros.OPEN, Macros.TAP, Macros.TYPE), kinds(steps), "its steps, the wait left out");
        same(KAKAO_T, steps.get(0).app(), "the package it opened"); same("주차", steps.get(1).target().text(), "the control by its label");
        same("차량번호", steps.get(2).target().hint(), "the field by its hint");
        List<JSONObject[]> coordinate = new ArrayList<>(done);
        coordinate.add(new JSONObject[]{obj("action", "tap", "x", 500, "y", 900), before, obj("ok", true)});
        check(Macros.learned(coordinate) == null, "a coordinate tap cannot be done the same way: no offer");
        List<JSONObject[]> url = new ArrayList<>(done.subList(0, 2));
        url.add(new JSONObject[]{obj("action", "open_url", "url", "https://example.com"), before, obj("ok", true)});
        check(Macros.learned(url) == null, "a web address: no offer");
        check(Macros.learned(done.subList(0, 1)) == null, "only opening an app: nothing to learn");
    }

    static void repair() {
        Macros.Macro m = sample("주차 정산");
        Macros.Step tap = m.steps().get(1), type = m.steps().get(2);
        String task = Macros.repairTask(m, 1, tap);
        check(task.contains("2단계만") && task.contains("‘주차 정산’ 누르기") && task.contains("id parking") && task.contains("한 번만"), "the agent is asked that one step, with its clues: " + task);
        check(!AutomationGate.pureRead(task), "and it is an ask to act");
        same("", Macros.repairRefusal(tap, obj("action", "tap", "element", "e4")), "its own tap on an element");
        same("", Macros.repairRefusal(tap, obj("action", "scroll_down")), "looking further is fine");
        check(!Macros.repairRefusal(tap, obj("action", "tap", "x", 500, "y", 900)).isEmpty(), "never a coordinate tap");
        check(!Macros.repairRefusal(tap, obj("action", "send")).isEmpty(), "never another action (a send)");
        check(!Macros.repairRefusal(tap, obj("action", "back")).isEmpty(), "nor leaving the screen");
        check(!Macros.repairRefusal(type, obj("action", "tap", "element", "e2")).isEmpty(), "a typing step types");
        same("12가3456", Macros.repairArgs(type, obj("action", "set_text", "element", "e2", "text", "다른 글")).optString("text"), "the recorded text, never the agent's");
        Macros.Target found = new Macros.Target("parking_v2", "주차요금 정산", "", "", "android.widget.Button", "button", 3, new int[]{0, 1700, 1080, 1850}, PHONE, false, null);
        Macros.Macro fixed = Macros.repaired(m, 1, found);
        same("parking_v2", fixed.steps().get(1).target().vid(), "the step keeps the control found");
        same(Macros.TAP, fixed.steps().get(1).kind(), "still a tap"); same(m.id(), fixed.id(), "the same macro");
        same(m.steps().get(2), fixed.steps().get(2), "the other steps as they were");
        same(m, Macros.repaired(m, 1, null), "nothing found: unchanged");
    }

    static void everywhere() {
        // 조건 알림: "차 타면 출근 체크 매크로 실행해 줘" is a phone job (BuddyHands runs the macro), never an app named 출근 체크 매크로.
        Triggers.Draft car = Triggers.read("차 타면", "", "", "", "", null, "차 타면 출근 체크 매크로 실행해 줘");
        check(car.then() != null && Triggers.JOB.equals(car.then().kind()) && "phone_job".equals(car.then().tool()), "a macro on a trigger is a phone job: " + (car.then() == null ? null : car.then().json()));
        check(car.then().target().contains("출근 체크"), "naming it");
        same(Triggers.EVERY, Triggers.read("차 타면", "", "", "", "", null, "차 탈 때마다 출근 체크 매크로 돌려 줘").mode(), "every time");
        // A macro that sends is still the macro (it asks before sending when nobody watches), not a draft.
        Triggers.Draft leave = Triggers.read("회사에서 나가면", "", "", "", "", null, "회사에서 나가면 퇴근 문자 보내기 매크로 실행해 줘");
        check(Triggers.JOB.equals(leave.then().kind()), "a sending macro is still a job: " + leave.then().json());
        // A plain send is a draft, as before.
        same(Triggers.DRAFT, Triggers.read("집에 도착하면", "", "", "", "", null, "집에 도착하면 엄마한테 문자 보내 줘").then().kind(), "a send is a draft");
        // 예약 작업 keeps which macro it runs.
        long now = 1_800_000_000_000L;
        JSONObject s = WorkSchedules.normalize(obj("goal", "‘출근 체크’ 매크로 실행", "repeat", WorkSchedules.WEEKDAYS, "time", "08:50", "macro", obj("name", "출근 체크")), now, java.time.ZoneId.of("Asia/Seoul"));
        same("출근 체크", WorkSchedules.macro(s), "the macro it runs");
        same("", WorkSchedules.macro(WorkSchedules.normalize(obj("goal", "새 공고 찾기", "repeat", WorkSchedules.DAILY, "time", "07:00"), now, java.time.ZoneId.of("Asia/Seoul"))), "an AI schedule has none");
        // The voice assistant's function.
        same(Macros.FUNCTION, Macros.declaration().optString("name"), "declared");
    }
}
