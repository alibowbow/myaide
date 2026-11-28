package dev.jun.gyeot;

import dev.jun.gyeot.core.AutomationGate;
import dev.jun.gyeot.core.SecurityLink;
import dev.jun.gyeot.core.WorkFence;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The AI 브라우저's essential guards without their false positives (준: needless limits off, the essential ones as strong): a link
 * or a card that shows a price opens its product page while a 구매·결제·송금 control, a checkout address and a form that pays stay
 * refused; "upgrade" is refused for a paid plan, not for a browser upgrade notice; banks, securities, pay and crypto exchanges
 * by their own domains, never a security news site or the World Bank; one Work job's uncertain step fences that job only; a
 * stop after refusals says what, where, when and what the user can do. Run by tools/build.py.
 */
final class BrowserSafetyRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static final String WORDS = "아이폰 17 찾아서 상품 페이지 열어 줘";

    public static void main(String[] args) throws Exception {
        money();
        addresses();
        banks();
        workFences();
        stopMessage();
        System.out.println("Browser safety regression checks passed: " + checked);
    }

    private static JSONObject page(String url, JSONObject... elements) {
        JSONArray list = new JSONArray();
        for (JSONObject e : elements) list.put(e);
        return new JSONObject().put("ok", true).put("url", url).put("document", "d1").put("elements", list);
    }
    private static JSONObject link(String label, String href) {
        return new JSONObject().put("ref", "1:0").put("tag", "a").put("label", label).put("href", href).put("navigation_kind", "public_get");
    }
    private static JSONObject control(String tag, String role, String label) {
        return new JSONObject().put("ref", "1:0").put("tag", tag).put("role", role).put("label", label).put("type", "button".equals(tag) ? "submit" : "");
    }
    private static final JSONObject CLICK = new JSONObject().put("action", "click").put("document", "d1").put("ref", "1:0");
    private static AutomationGate.Tier click(JSONObject element, String url) {
        return BrowserDecisionEvidence.tier(CLICK, page(url, element), WORDS);
    }
    private static boolean refused(JSONObject element) { return click(element, "https://shop.example.com/products") == AutomationGate.Tier.FORBIDDEN; }
    private static boolean opens(String url) {
        JSONObject open = new JSONObject().put("action", "open").put("url", url);
        return BrowserDecisionEvidence.tier(open, null, WORDS) != AutomationGate.Tier.FORBIDDEN;
    }

    private static void money() {
        // A link or a card that shows a price only opens the product page.
        check(!refused(link("아이폰 17 1,250,000원", "https://shop.example.com/products/17")), "a product link showing its price opens");
        check(!refused(link("1,250,000원", "https://shop.example.com/products/17")), "a price that links to its product page opens");
        check(!refused(link("Apple iPhone 17 $999", "https://shop.example.com/products/17")), "a product link in dollars opens");
        check(!refused(control("div", "link", "아이폰 17 1,250,000원 무료배송")), "a product card showing its price opens");
        check(!refused(control("div", "button", "아이폰 17 1,250,000원")), "a short product card showing its price opens");
        check(!refused(control("button", "", "장바구니 담기")), "putting in the cart is no payment");
        check(!refused(control("button", "", "1,000원 할인 쿠폰 받기")) && !refused(control("button", "", "Get $10 off")), "taking a coupon pays nothing");
        check(!refused(link("Pro $20/month", "https://app.example.com/pricing/pro")), "a plan's price that links to its page opens it");
        check(refused(control("button", "", "Pro $20/month")), "a plan's price on a button is refused");
        // The words of a control that buys, pays or moves money.
        for (String label : new String[]{"구매하기 1,250,000원", "1,250,000원 구매", "바로구매", "결제하기", "₩12,000", "$4.99", "월 9,900원 구독하기", "Get Pro for $20/month",
                "송금", "송금하기", "이체", "Send money", "Buy now", "Place order", "Checkout", "Pay now", "후원하기", "선물하기", "Donate", "Top up", "충전하기",
                "쿠키 3개로 보기", "Unlock for 3 coins", "Upgrade plan", "Upgrade to Pro", "프리미엄으로 업그레이드", "요금제 업그레이드", "업그레이드", "Upgrade"})
            check(refused(control("button", "", label)), "refused: " + label);
        check(refused(link("구매하기 1,250,000원", "https://shop.example.com/products/17")), "a link that says buy is refused even to a product page");
        check(refused(link("아이폰 17 1,250,000원", "https://shop.example.com/checkout?item=17")), "a priced link to a checkout is refused");
        // The form a button submits: a 확인 that sends a payment form.
        check(refused(control("button", "", "확인").put("form_action", "https://shop.example.com/order/pay")), "a button whose form pays is refused");
        check(!refused(control("button", "", "확인").put("form_action", "https://shop.example.com/search")), "a button whose form searches is not");
        // An upgrade that is no paid plan.
        check(!refused(control("button", "", "브라우저 업그레이드 안내")), "a browser upgrade notice");
        check(!refused(link("Chrome 업그레이드", "https://www.google.com/chrome/")), "a browser upgrade link");
        check(!refused(control("button", "", "앱 업그레이드")), "an app upgrade");
        check(!refused(control("button", "", "프로그램 업그레이드")) && !refused(control("button", "", "Upgrade your browser")), "a program or browser upgrade");
        // Clearing history, a cache or a chat stays refused.
        for (String label : new String[]{"Clear history", "Clear cache", "기록 삭제", "대화 기록 지우기", "검색 기록 삭제", "Delete account", "회원 탈퇴", "휴지통 비우기"})
            check(refused(control("button", "", label)), "refused: " + label);
        // A deletion the user asked for runs; never an account, never everything.
        JSONObject delete = control("button", "", "삭제");
        check(BrowserDecisionEvidence.tier(CLICK, page("https://mail.example.com/", delete), "이 메일 삭제해 줘") == AutomationGate.Tier.CONSEQUENTIAL, "the asked deletion");
        check(BrowserDecisionEvidence.tier(CLICK, page("https://mail.example.com/", control("button", "", "전체 삭제")), "이 메일 삭제해 줘") == AutomationGate.Tier.FORBIDDEN, "never everything");
        // Typing a card number or a password never runs.
        JSONObject card = new JSONObject().put("ref", "1:0").put("tag", "input").put("type", "text").put("label", "카드번호").put("autocomplete", "cc-number").put("editable", true);
        JSONObject type = new JSONObject().put("action", "type").put("document", "d1").put("ref", "1:0").put("text", "4111 1111 1111 1111");
        check(BrowserDecisionEvidence.tier(type, page("https://shop.example.com/", card), "카드번호 넣어 줘") == AutomationGate.Tier.FORBIDDEN, "a card number is never typed");
    }

    private static void addresses() {
        for (String url : new String[]{"https://shop.example.com/checkout", "https://shop.example.com/cart/checkout?step=2", "https://shop.example.com/order/confirm",
                "https://shop.example.com/order/pay", "https://shop.example.com/payment", "https://shop.example.com/api/pay", "https://shop.example.com/pay.do?id=3",
                "https://shop.example.com/billing", "https://example.com/settings/billing/upgrade", "https://shop.example.com/purchase?item=4",
                "https://shop.example.com/orderSheet?item=1", "https://bank.example.com/transfer", "https://cafe.example.com/post/3/delete",
                "https://shop.example.com/cart?action=checkout", "https://shop.example.com/do?cmd=pay"})
            check(!opens(url), "refused address: " + url);
        for (String url : new String[]{"https://shop.example.com/list?sort=order", "https://shop.example.com/list?orderby=price&order=asc", "https://shop.example.com/list?order=desc",
                "https://shop.example.com/my/orders", "https://shop.example.com/order/123", "https://shop.example.com/products/17", "https://www.example.com/browser-upgrade",
                "https://news.example.com/economy/payments-industry-grows", "https://shop.example.com/search?q=pay"})
            check(opens(url), "opens: " + url);
    }

    private static void banks() {
        for (String url : new String[]{"https://www.kbstar.com/", "https://obank.kbstar.com/quics?page=C025255", "https://m.wooribank.com/", "https://www.shinhan.com/",
                "https://banking.nonghyup.com/", "https://www.kakaobank.com/", "https://www.tossbank.com/", "https://www.kiwoom.com/", "https://securities.miraeasset.com/",
                "https://upbit.com/exchange?code=CRIX.UPBIT.KRW-BTC", "https://www.bithumb.com/", "https://www.binance.com/en", "https://www.coinbase.com/", "https://toss.im/",
                "https://pay.naver.com/", "https://new-m.pay.naver.com/historybenefit", "https://www.kakaopay.com/", "https://checkout.stripe.com/c/pay/cs_test",
                "https://www.paypal.com/signin", "https://www.chase.com/", "https://pay.example.com/"})
            check(AutomationGate.bankingHost(url), "a money site: " + url);
        for (String url : new String[]{"https://www.worldbank.org/en/home", "https://www.securityweek.com/patch-tuesday", "https://krebsonsecurity.com/2026/11/",
                "https://www.boannews.com/media/view.asp?idx=1", "https://www.dailysecu.com/news/articleView.html?idxno=1", "https://www.hankyung.com/securities",
                "https://securityblog.example.org/", "https://en.wikipedia.org/wiki/Bank", "https://www.wise.example/", "https://www.giftcard.example.com/",
                "https://cardnews.example.com/", "https://www.mybanker-news.example/", "https://kbstar.com.offer.example/"})
            check(!AutomationGate.bankingHost(url), "no money site: " + url);
        // A page of a bank is never operated; a security news page is.
        JSONObject bankPage = page("https://obank.kbstar.com/quics", control("button", "", "조회"));
        check(BrowserDecisionEvidence.tier(CLICK, bankPage, "잔액 조회해 줘") == AutomationGate.Tier.FORBIDDEN, "a bank's page is never operated");
        check(BrowserDecisionEvidence.tier(CLICK, page("https://www.boannews.com/media/list.asp", link("다음 기사", "https://www.boannews.com/media/view.asp?idx=2")), "보안뉴스 읽어 줘")
            != AutomationGate.Tier.FORBIDDEN, "a security news page is read");
        // The look-alike check stays SecurityLink's.
        check(SecurityLink.inspect("https://upbit.com.offer.example/", "").block(), "an upbit look-alike is blocked");
        check(SecurityLink.inspect("https://kr.tradingview.com/", "").official().equals("tradingview.com"), "TradingView's own domain");
    }

    private static JSONObject row(String job, String receipt) {
        return new JSONObject().put("version", 1).put("receipt_id", receipt).put("job_id", job).put("request", "영상 올리기").put("url", "https://studio.youtube.com")
            .put("reason", "awaiting_acknowledgement").put("action", "click").put("state", "awaiting_acknowledgement").put("tier", "consequential").put("at", 1_000L);
    }
    private static void workFences() throws Exception {
        WorkFence fence = WorkFence.read("");
        check(fence.rows().isEmpty() && fence.save().isEmpty(), "no fence");
        fence.put(row("upload-1", "r1"));
        // Another Work job's tab is not stopped by it; the same job is (its step may have run), and waits while it is in flight.
        check(fence.reserve("blog-2", Set.of()).step() == WorkFence.Step.RESERVE, "another Work job reserves its own step");
        WorkFence.Decision same = fence.reserve("upload-1", Set.of());
        check(same.step() == WorkFence.Step.BLOCKED && "r1".equals(same.earlier().optString("receipt_id")), "the same job is stopped by its own uncertain step");
        check(fence.reserve("upload-1", Set.of("r1")).step() == WorkFence.Step.WAIT, "its step still in flight is waited for");
        fence.put(row("blog-2", "r2"));
        WorkFence kept = WorkFence.read(fence.save());
        check(kept.rows().size() == 2 && "r2".equals(kept.job("blog-2").optString("receipt_id")) && kept.receipt("r1") != null, "both kept, each by its job");
        check(kept.removeReceipt("r1") && kept.job("upload-1") == null && kept.job("blog-2") != null, "the user's check lifts that one only");
        check(kept.removeReceipt("r2") && kept.save().isEmpty(), "none left");
        // The record of an older version: one row for one job.
        WorkFence legacy = WorkFence.read(row("upload-1", "r9").toString());
        check(legacy.job("upload-1") != null && legacy.reserve("blog-2", Set.of()).step() == WorkFence.Step.RESERVE, "an older single record fences its own job only");
        // A record from before steps were tiered, held by no running job, is released.
        JSONObject untiered = row("upload-1", "r8"); untiered.remove("tier");
        WorkFence old = WorkFence.read(untiered.toString());
        check(old.reserve("upload-1", Set.of()).step() == WorkFence.Step.RESERVE && old.rows().isEmpty(), "an untiered old record is released");
        try { WorkFence.read("{broken"); check(false, "unreadable fails closed"); } catch (Exception unreadable) { checked++; }
    }

    private static void stopMessage() {
        JSONObject buy = control("button", "", "구매하기 1,250,000원");
        JSONObject page = page("https://shop.example.com/products/17", buy);
        AutomationGate.Verdict verdict = BrowserDecisionEvidence.verdict(CLICK, page, "아이폰 17 사 줘");
        check(!verdict.allowed() && verdict.message().contains("구매하기 1,250,000원"), "the refusal names the control: " + verdict.message());
        long at = java.time.ZonedDateTime.of(2026, 11, 28, 14, 5, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        String stop = BrowserDecisionEvidence.refusalStop(CLICK, page, verdict, 7, "아이폰 17 사 줘", at);
        check(stop.contains("shop.example.com") && stop.contains("구매하기 1,250,000원") && stop.contains("14:05") && stop.contains("아이폰 17 사 줘")
            && stop.contains("실행하지 않았") && stop.contains("직접"), "the stop says what, where, when, for which request and what to do: " + stop);
        AutomationGate.Verdict bank = BrowserDecisionEvidence.verdict(CLICK, page("https://obank.kbstar.com/quics", control("button", "", "조회")), "잔액 조회해 줘");
        check(bank.message().contains("obank.kbstar.com"), "a bank refusal names the site: " + bank.message());
        JSONObject open = new JSONObject().put("action", "open").put("url", "https://shop.example.com/checkout");
        AutomationGate.Verdict checkout = BrowserDecisionEvidence.verdict(open, null, "결제 페이지 열어 줘");
        check(checkout.message().contains("shop.example.com"), "a refused address names its site: " + checkout.message());
    }
}
