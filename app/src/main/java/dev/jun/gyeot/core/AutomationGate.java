package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The local automation gate for every automated phone and browser step. Pure: it never
 * executes, and callers keep every executor guard (armed control, ticket and session, unlocked screen, target rebinding,
 * document/ref freshness, the repeat guard, no automatic re-send).
 *
 * Tier 0 reads and tier 1 routine steps run. A forbidden step (payment, money transfer, deletion, credential entry,
 * banking, card, pay, securities or crypto apps) never runs, whatever was said. Any other step is consequential and runs on
 * the model's or planner's choice, with one guard: not when the user only asked to read and the step came after untrusted
 * page or screen text was read.
 */
public final class AutomationGate {
    private AutomationGate() {}
    public enum Tier { OBSERVATION, ROUTINE, CONSEQUENTIAL, FORBIDDEN }
    /** What a consequential step does, from the operation and its observed label; OTHER when unsure. */
    public enum Kind { SEND, POST, SUBMIT, CALL, CONFIRM, OTHER }
    public record Verdict(Tier tier, Kind kind, boolean allowed, String code, String message) {
        public JSONObject refusal() {
            return Jsons.obj("ok", false, "action_executed", false, "execution_status", "not_dispatched", "retryable", false,
                "error_code", code, "automation_tier", tier.name().toLowerCase(Locale.ROOT), "error", message);
        }
    }
    public static final String FORBIDDEN = "automation_forbidden", NOT_REQUESTED = "automation_not_requested";
    public static final String FORBIDDEN_MESSAGE = "결제·송금·삭제 같은 일은 자동으로 하지 않아요. 직접 해 주세요.";
    public static final String CREDENTIAL_MESSAGE = "비밀번호·인증번호 같은 정보는 자동으로 입력하지 않아요. 직접 입력해 주세요.";
    /** A step page or screen text may have suggested while the user only asked to read (verdict). */
    public static final String NOT_REQUESTED_MESSAGE = "읽기만 요청하셔서, 읽은 내용에 따라 보내기·누르기 같은 동작은 하지 않았어요.";
    /** Prefix of a 작업창 browser subtask: only the user's part before AUTHORITY_END is their own words. */
    public static final String AUTHORITY_PREFIX = "원래 사용자 지시(승인 범위): ", AUTHORITY_END = "\n이 범위 안의 하위 작업: ";
    /** One line of that user part (WorkBook.authority). */
    public static final String AUTHORITY_LINE = "사용자 지시: ";

    // ── Forbidden: money, orders, deletion, account and data wipes ───────────────────────────────

    // Korean stems, matched with whitespace removed.
    private static final Pattern FORBIDDEN_KO = Pattern.compile(
        "결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|환전|매수|매도|대출|현금서비스|카드론|선물하기|대여|소장(?:하기|권|구매)|^소장|"
        + "후원하기|기부하기|충전|삭제|지우기|지워|지울|제거|비우기|비워|탈퇴|해지|언인스톨|포맷|공장초기화|초기화|휴지통(?:으로|에)(?:이동|보내|버리)|"
        + "크레딧(?:구매|구입|충전|추가)|요금제(?:가입|변경)|플랜(?:가입|변경)|(?:유료|프리미엄|정기|멤버십)구독|멤버십(?:가입|결제|시작)|구독(?:결제|료결제)");
    // Benign uses of those stems: battery charging, resetting a filter or a search.
    private static final String KO_BENIGN = "(?:배터리|고속|급속|무선|유선)충전|충전(?:중|기|량|상태|속도|케이블|단자|제한|모드)|(?:필터|검색어?|조건|선택|정렬|옵션|입력|줌|확대|화면)초기화";
    private static final Pattern FORBIDDEN_EN = Regex.unicode(
        "(?i)(?<![A-Za-z0-9])(?:pay(?:ments?)?|purchases?|buy(?:ing)?|checkout|check\\s?out|(?:place|complete|submit|confirm)\\s+(?:your\\s+|the\\s+|my\\s+)?order|order\\s+now|"
        + "order\\s+(?:complete[d]?|completion|done|confirm(?:ed|ation)?|pending|placed|processing|in\\s+progress|success(?:ful)?|succeeded|failed|submitted|received)|"
        + "transfers?|withdraw\\w*|deposits?|top\\s?up|recharge|donate|cash\\s+advance|send\\s+money|"
        + "delete|remove|erase|uninstall|wipe|discard|factory\\s?reset|reset\\s+(?:phone|device|data|all|everything)|"
        + "clear\\s+(?:data|storage|cache|history|chat|conversation|all\\s+data)|(?:close|delete|deactivate|cancel)\\s+(?:my\\s+|your\\s+|the\\s+)?account|"
        + "format\\s+(?:sd|card|storage|device|disk|drive|usb)|empty\\s+(?:the\\s+)?(?:trash|bin)|move\\s+to\\s+(?:the\\s+)?(?:trash|bin)|"
        + "go\\s+(?:pro|premium|plus)|get\\s+(?:pro|premium|plus)|(?:buy|get|add|purchase)\\s+(?:more\\s+)?credits?|subscribe\\s+to\\s+(?:pro|premium|plus|a\\s+plan|the\\s+plan)"
        + "|start\\s+(?:my\\s+|your\\s+)?(?:paid\\s+)?subscription)(?![A-Za-z0-9])");
    private static final String EN_BENIGN = "(?:remove|clear|reset)\\s+(?:all\\s+)?filters?|remove\\s+from\\s+(?:search|results)|(?:file|data|usb|photo|media)\\s+transfers?|"
        + "transfer\\s+(?:files?|photos?|media|data)|clear\\s+(?:search|text|query|input)";
    // A price or a coin spend: "₩12,000", "3,900원", "$4.99", "쿠키 3개로 보기", "Unlock for 3 coins". A price alone is no money step:
    // a product link or card shows one and only opens the product page (pricePays).
    private static final Pattern PRICE = Regex.unicode("(?i)[₩$€£¥]\\s*\\d|\\d[\\d,.]*\\s*(?:원|달러|유로|엔)(?:(?![가-힣a-z])|(?=에|짜리|씩|부터|이에요|입니다))|(?<![A-Za-z0-9])(?:usd|krw|eur)\\s*\\d");
    private static final Pattern SPEND = Regex.unicode("(?i)\\d+\\s*(?:개|코인|쿠키|캐시|포인트|젬|다이아|루비|골드|하트|별|원)\\s*(?:으로|로|에)\\s*(?:보기|열기|구매|구입|대여|소장|이용|받기|사용|시청|열람|잠금\\s*해제)"
        + "|(?<![A-Za-z0-9])(?:unlock|use|spend|redeem)(?![A-Za-z0-9])[^\\n]{0,20}?\\b\\d+\\s*(?:coins?|gems?|credits?|points?|cookies?|tokens?|diamonds?)(?![A-Za-z0-9])|\\b\\d+\\s*(?:coins?|gems?|credits?|cookies?|diamonds?)\\s+to\\s+(?:unlock|read|watch|view|buy)(?![A-Za-z0-9])");
    // A read or navigation label ("결제 내역", "주문 조회", "Purchase history"): exempt unless a money or confirm verb is present.
    private static final Pattern READ_ENDING = Regex.unicode("(?i)(?:(?:내역|이력|기록|목록|리스트|현황|상세|정보|영수증|명세서?)(?:확인|조회|보기)?|조회|보기|열기|"
        + "history|list|lists|details?|records?|receipts?|status|summary|statements?|activity)$");
    // A status word ("결제 완료", "입금 대기", "Order complete"): the text of a record. It exempts only a text part of a multi-part list
    // row (an order-history row's status chip), never a control of its own: a "결제 완료" button pays.
    private static final Pattern STATUS_ENDING = Regex.unicode("(?i)(?:완료|완료됨|대기|대기중|진행중|처리중|취소됨|실패|"
        + "complete|completed|done|pending|processing|in\\s*progress|confirmed|successful|succeeded|failed|cancell?ed)$");
    private static final Pattern MONEY_VERB = Regex.unicode("(?i)(?:결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|충전|환전|매수|매도|대여|소장|후원|기부|삭제|제거|초기화|탈퇴|해지|포맷|선물)"
        + "(?:하기|하고|하|해|할|합|진행|요청|신청|확인|승인|동의)|바로구매|즉시구매|(?:확인|동의)(?:및|하고|후)(?:결제|구매|주문|송금|이체)|지우|비우|비워|지워"
        + "|(?<![A-Za-z0-9])(?:pay|buy|checkout|delete|remove|erase|clear|wipe|discard|empty|uninstall|reset|format|confirm|agree)(?![A-Za-z0-9])|\\border\\s+now\\b|\\bplace\\s+(?:your\\s+)?order\\b");
    // A forbidden operation in a verb form, as on a control ("결제하기", "삭제하고", "Pay now").
    private static final Pattern FORBIDDEN_VERB = Regex.unicode("(?:결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|충전|환전|매수|매도|대출|대여|소장|후원|기부|삭제|제거|초기화|탈퇴|해지|포맷|업그레이드)"
        + "(?:하기|하고|하|해|할|합)|바로구매|즉시구매|지우|비우|지워|비워|(?i)^\\W*(?:pay|buy|purchase|checkout|order|delete|remove|erase|uninstall|wipe|discard|transfer|withdraw|deposit|donate|top\\s?up|upgrade)(?![A-Za-z0-9])");
    // The words that take a price: subscribing, joining, renting, "for $4.99" ("월 9,900원 구독하기", "Get Pro for $20/month",
    // "1,250,000원 구매"); and softer ones (starting, unlocking, continuing) that take it unless the price is a discount or a
    // coupon ("10,000원 할인 쿠폰 받기", "Get $10 off" pay nothing).
    private static final Pattern COMMIT = Regex.unicode("(?i)(?:구독|가입|결제|구매|구입|주문|충전|후원|기부|선물|대여|소장|업그레이드)(?:하기|하고|하|해|할|합|$)"
        + "|(?<![A-Za-z0-9])(?:subscribe|rent|upgrade|order|pay|buy|purchase|checkout)(?![A-Za-z0-9])"
        + "|(?<![A-Za-z0-9])for\\s+(?:just\\s+|only\\s+)?(?:[₩$€£¥]\\s*\\d|\\d[\\d,.]*\\s*(?:원|달러|usd|krw|eur))");
    private static final Pattern SOFT_COMMIT = Regex.unicode("(?i)(?:신청|시작|이용|계속|잠금\\s*해제|열람|시청)(?:하기|하고|하|해|할|합|$)"
        + "|(?<![A-Za-z0-9])(?:get|start|continue|join|try|unlock|book)(?![A-Za-z0-9])");
    private static final Pattern DISCOUNT = Regex.unicode("(?i)할인|쿠폰|적립|캐시백|(?<![A-Za-z0-9])(?:off|discount|coupon|cashback|save)(?![A-Za-z0-9])");
    private static boolean takesPrice(String lower) {
        String compact = lower.replaceAll("\\s+", "");
        return COMMIT.matcher(lower).find() || COMMIT.matcher(compact).find()
            || (SOFT_COMMIT.matcher(lower).find() || SOFT_COMMIT.matcher(compact).find()) && !DISCOUNT.matcher(lower).find();
    }
    // A price for a period ("월 9,900원", "$20/month", "연 99,000원"): a plan's price.
    private static final Pattern PERIOD = Regex.unicode("(?i)(?:^|[\\s(])(?:월|매월|연|매년|년)\\s*[₩$]?\\s*\\d|/\\s*(?:월|년|연|mo|month|yr|year)(?![A-Za-z])"
        + "|(?<![A-Za-z0-9])(?:per|a|each)\\s+(?:month|year)(?![A-Za-z])|(?<![A-Za-z0-9])(?:monthly|yearly|annually)(?![A-Za-z0-9])");
    // A bare price's parts: amounts, a period and price words ("₩12,000", "$4.99/mo", "월 9,900원부터", "할인가 12,000원").
    private static final Pattern PRICE_PARTS = Regex.unicode("(?i)[₩$€£¥]\\s*\\d[\\d,.]*|\\d[\\d,.]*\\s*(?:원|달러|유로|엔|usd|krw|eur)|(?:usd|krw|eur)\\s*\\d[\\d,.]*|\\d+"
        + "|(?:매월|매년|부터|짜리|정가|판매가|할인가|최저가|가격|부가세|포함|별도)|(?<![가-힣])[월연년씩](?![가-힣])"
        + "|(?<![A-Za-z0-9])(?:per|a|each|month|monthly|year|yearly|annually|mo|yr|from|only|just|now|price|incl|vat|tax)(?![A-Za-z0-9])");
    private static String priceRest(String text) {
        return PRICE_PARTS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("[^\\p{L}]+", " ").strip();
    }
    /** A price that pays: shown alone (an app store's buy button is its price), with words that take it (COMMIT), or a plan's
     * price for a period on a short control. A product link or card that only shows a price ("아이폰 17 1,250,000원") is none. */
    static boolean pricePays(String text) {
        String value = text == null ? "" : text.strip(), lower = value.toLowerCase(Locale.ROOT);
        if (!PRICE.matcher(lower).find()) return false;
        if (priceRest(value).isEmpty() || takesPrice(lower)) return true;
        return PERIOD.matcher(lower).find() && actionLike(value);
    }
    /** A price is all there is to this label that a money step could be ("1,250,000원", "아이폰 17 1,250,000원", "Pro $20/month"): no
     * word that takes it, no coin spend, nothing else forbidden. A link with it to a product page only opens the page. */
    public static boolean priceOnly(String label) {
        String lower = label == null ? "" : label.strip().toLowerCase(Locale.ROOT), compact = lower.replaceAll("\\s+", "");
        if (!PRICE.matcher(lower).find() || SPEND.matcher(compact).find() || SPEND.matcher(lower).find() || takesPrice(lower)) return false;
        String rest = priceRest(label);
        return rest.isEmpty() || !forbiddenText(rest);
    }
    // "Upgrade" is a money step when it is a paid plan's: a plan named, or nothing named ("Upgrade", "지금 업그레이드"). An upgrade
    // of a browser, an app or software, and a notice or a guide about one ("브라우저 업그레이드 안내"), is none.
    private static final Pattern UPGRADE = Regex.unicode("(?i)업그레이드|(?<![A-Za-z0-9])upgrad(?:e[sd]?|ing)(?![A-Za-z0-9])");
    private static final Pattern PLAN = Regex.unicode("(?i)요금제|플랜|멤버십|구독|유료|프리미엄|프로(?![그필젝세모듀토])|플러스|이용권|등급|저장\\s*공간|요금"
        + "|(?<![A-Za-z0-9])(?:plans?|pro|premium|plus|max|team|business|enterprise|tier|subscription|membership|billing|account|paid|ultra|unlimited|storage|seats?)(?![A-Za-z0-9])");
    private static final Pattern NOT_PLAN = Regex.unicode("(?i)브라우저|크롬|파이어폭스|엣지|사파리|앱|어플|소프트웨어|펌웨어|버전|윈도우|안드로이드|드라이버|시스템|업데이트|프로그램|플러그인|확장|안내|방법|가이드|도움말|공지|소식|후기|리뷰|비교|정보"
        + "|(?<![A-Za-z0-9])(?:browsers?|chrome|firefox|edge|safari|apps?|applications?|software|firmware|versions?|os|windows|android|ios|drivers?|system|updates?|programs?|plugins?|extensions?|guide|how|help|notice|news|review|faq|tips?|info)(?![A-Za-z0-9])");
    /** An upgrade of a paid plan (PLAN named, or nothing else named), never of a browser, an app or software, nor a notice about one. */
    static boolean planUpgrade(String text) {
        if (text == null || !UPGRADE.matcher(text).find()) return false;
        return PLAN.matcher(text).find() || !NOT_PLAN.matcher(text).find();
    }

    /** A read label, or with status also a record's status, with no money, spend or confirm verb in it. */
    private static boolean readLabel(String text, boolean status) {
        String trimmed = text.strip().replaceAll("[\\s\\d()\\[\\]·•,.:;>›»→…+|/-]+$", "");
        String compact = trimmed.replaceAll("\\s+", "").toLowerCase(Locale.ROOT), lower = trimmed.toLowerCase(Locale.ROOT);
        if (compact.isEmpty() || SPEND.matcher(compact).find() || SPEND.matcher(text).find()) return false;
        boolean read = READ_ENDING.matcher(compact).find() || READ_ENDING.matcher(lower).find() || status && statusEnding(trimmed);
        return read && !MONEY_VERB.matcher(compact).find() && !MONEY_VERB.matcher(lower).find();
    }
    private static boolean statusEnding(String text) {
        String lower = text.strip().replaceAll("[\\s()\\[\\]·•,.:;>›»→…+|/-]+$", "").toLowerCase(Locale.ROOT);
        return STATUS_ENDING.matcher(lower.replaceAll("\\s+", "")).find() || STATUS_ENDING.matcher(lower).find();
    }
    // A label that opens a read screen: "결제 내역", "주문 조회", "자세히 보기", "Order history". Not "완료" (a Done button).
    private static final Pattern READ_NAVIGATION = Regex.unicode("(?i)(?:(?:내역|이력|기록|목록|리스트|현황|상세|정보|영수증|명세서?)(?:확인|조회|보기)?|조회|보기|"
        + "history|list|lists|details?|records?|receipts?|statements?|activity)$");
    /** A read or navigation label that opens a screen to read, with no money, spend or confirm verb in it (L1). */
    public static boolean readNavigationLabel(String label) {
        if (label == null || label.isBlank() || !actionLike(label) || moneyText(label) || !readLabel(label, false)) return false;
        String trimmed = label.strip().replaceAll("[\\s\\d()\\[\\]·•,.:;>›»→…+|/-]+$", "");
        return READ_NAVIGATION.matcher(trimmed.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)).find() || READ_NAVIGATION.matcher(trimmed.toLowerCase(Locale.ROOT)).find();
    }
    /** Text naming a forbidden operation: payment, purchase, order, money transfer, deletion, an account or data wipe, a paid
     * plan's upgrade, a price that pays (pricePays: alone, with a word that takes it, or a plan's period) or a coin spend. A
     * product link or card that only shows a price is none. Read and navigation labels ("결제 내역", "Purchase history",
     * "필터 초기화") and a record's status ("결제 완료", "Order completed") are not forbidden text; a control that shows a status
     * still is (forbiddenLabel). */
    public static boolean forbiddenText(String text) { return forbiddenText(text, true); }
    private static boolean forbiddenText(String text, boolean status) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT), compact = lower.replaceAll("\\s+", "");
        boolean hit = FORBIDDEN_KO.matcher(compact.replaceAll(KO_BENIGN, "")).find() || FORBIDDEN_EN.matcher(lower.replaceAll(EN_BENIGN, " ")).find()
            || planUpgrade(text) || pricePays(text) || SPEND.matcher(compact).find() || SPEND.matcher(lower).find();
        return hit && !readLabel(text, status);
    }
    // Section names that also name a forbidden step: KakaoTalk's 선물하기 tab, a 충전 tab, a 대여 or 소장 shelf, a 초기화 menu. A
    // reset of the phone, its data or the device is never one, however it is spaced.
    private static final Pattern SECTION = Pattern.compile("선물(?:\\s*하기)?|충전(?:\\s*하기)?|대여(?:\\s*하기)?|소장(?:\\s*하기)?|초기화(?:\\s*하기)?");
    private static final Pattern WIPE = Pattern.compile("(?:공장|데이터|기기|디바이스|휴대폰|전체)초기화");
    /** A label with its section names taken out ("선물하기" → "", "선물하기 내역" → "내역"); null when it has none, or shows a
     * price or a coin spend, or names a wipe. What is left is judged on its own. */
    public static String withoutSections(String label) {
        if (label == null || moneyText(label) || WIPE.matcher(label.replaceAll("\\s+", "")).find() || !SECTION.matcher(label).find()) return null;
        return SECTION.matcher(label).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }
    /** A forbidden label whose only forbidden words are section names (선물하기, 충전, 대여, 소장, 초기화). On a tab, a selected or
     * current navigation item or a read-navigation label it switches sections or opens a record; on a button or any other
     * control the same words still forbid it (forbiddenLabel). */
    public static boolean sectionName(String label) {
        String rest = withoutSections(label);
        return rest != null && forbiddenText(label) && !forbiddenText(rest);
    }
    /** A read-navigation label once its section names are taken out ("선물하기 내역", "대여하기 목록"). */
    public static boolean sectionReadLabel(String label) {
        return sectionName(label) && readNavigationLabel(withoutSections(label));
    }
    /** A record's status ("결제 완료", "배송 완료", "입금 대기", "Order completed"): a short text ending in a status word, with no
     * verb, price or coin spend. It tells what already happened to a payment or an order; it is no control. */
    public static boolean statusText(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.split("\\s+").length > 3 || moneyText(value) || !statusEnding(value)) return false;
        String lower = value.toLowerCase(Locale.ROOT), compact = lower.replaceAll("\\s+", "");
        return !MONEY_VERB.matcher(compact).find() && !MONEY_VERB.matcher(lower).find();
    }
    /** An amount in a record ("₩99,000", "결제금액 99,000원"): a price with no verb or coin spend. */
    public static boolean amountText(String text) {
        String value = text == null ? "" : text.strip(), lower = value.toLowerCase(Locale.ROOT), compact = lower.replaceAll("\\s+", "");
        if (!PRICE.matcher(lower).find() || SPEND.matcher(compact).find() || SPEND.matcher(lower).find() || value.split("\\s+").length > 4) return false;
        return !MONEY_VERB.matcher(compact).find() && !MONEY_VERB.matcher(lower).find() && !FORBIDDEN_VERB.matcher(compact).find() && !FORBIDDEN_VERB.matcher(lower).find();
    }
    /** Whether a price or a coin spend appears ("₩12,000", "쿠키 3개로 보기"). */
    public static boolean moneyText(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return PRICE.matcher(lower).find() || SPEND.matcher(lower.replaceAll("\\s+", "")).find() || SPEND.matcher(lower).find();
    }
    private static final Pattern SENTENCE_END = Pattern.compile("(?:[요어아야지네다까게래자죠니냐음함됨임군걸데요]|[ㅋㅎㅠㅜ]+|[?!.~…]+)$");
    private static final Pattern CONTROL_END = Pattern.compile("(?:하기|보기|열기|받기|담기|끄기|켜기|나가기|지우기|비우기|버튼)$");
    /** A label shaped like a control ("결제하기", "₩12,000", "Delete"), not a sentence of content (a message preview or a headline). */
    public static boolean actionLike(String label) {
        String text = label == null ? "" : label.strip();
        if (text.isEmpty()) return false;
        String compact = text.replaceAll("\\s+", "");
        if (SENTENCE_END.matcher(compact).find() && !CONTROL_END.matcher(compact).find()) return false;
        if (compact.matches("(?:(?:오전|오후|am|pm)?\\d{1,2}:\\d{2}|[\\d.:/,\\-]+)")) return false;
        String[] words = text.replaceAll("[·•|/]", " ").strip().split("\\s+");
        return words.length <= 4;
    }
    private static final Pattern TIME_OR_DATE = Regex.unicode("(?i)(?:오전|오후|am|pm)\\s*\\d|\\d{1,2}:\\d{2}|\\d+\\s*(?:초|분|시간|일|주|개월|년)\\s*전|어제|그제|방금|"
        + "\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|\\d{2,4}[./-]\\s*\\d{1,2}[./-]\\s*\\d{1,2}|(?<![A-Za-z0-9])(?:yesterday|today|just\\s+now|\\d+\\s*(?:s|m|h|d|w|mins?|minutes?|hrs?|hours?|days?)\\s+ago)(?![A-Za-z0-9])");
    /** One short reply sentence ("네, 그렇게 할게요", "이따 전화할게", "Sounds good!"): at most 40 characters, a sentence ending,
     * no time or date in it. As the only text of a row in a messaging app, it is a reply that one tap sends. */
    public static boolean replySentence(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.length() > 40 || TIME_OR_DATE.matcher(value).find()) return false;
        String compact = value.replaceAll("\\s+", "");
        return SENTENCE_END.matcher(compact).find() && !CONTROL_END.matcher(compact).find();
    }
    /** A control label naming a forbidden operation: forbidden text shaped like a control or in a verb form. A status word never
     * exempts a control: "결제 완료", "주문 완료", "₩12,000 결제 완료" and "Order complete" buttons are forbidden like "결제하기". */
    public static boolean forbiddenLabel(String label) { return forbiddenControl(label, false); }
    /** One text part of a multi-part list row, where the row is the control: its status chip ("결제 완료") is the row's record. */
    public static boolean forbiddenRowPart(String part) { return forbiddenControl(part, true); }
    private static boolean forbiddenControl(String label, boolean status) {
        if (!forbiddenText(label, status)) return false;
        String text = label.strip();
        if (actionLike(text) || FORBIDDEN_VERB.matcher(text.replaceAll("\\s+", "")).find() || FORBIDDEN_VERB.matcher(text.toLowerCase(Locale.ROOT)).find()) return true;
        // A price with the word that takes it is the money step however long it reads ("Get Pro for $20/month").
        if (PRICE.matcher(text.toLowerCase(Locale.ROOT)).find() && takesPrice(text.toLowerCase(Locale.ROOT)) && !statusText(text)) return true;
        // "결제 완료됨" ends like a sentence, yet on a control of its own it is the money step all the same.
        return !status && statusEnding(text) && text.split("\\s+").length <= 4;
    }
    // A web address step that pays, moves money or deletes: a checkout, payment, billing or order-confirm path or a pay endpoint
    // (one path part: "/checkout", "/order/pay", "/pay.do", "/orderSheet", "/post/3/delete"), or an action key naming one
    // ("?action=checkout", "?cmd=pay"). A sort or a filter that says order ("?sort=order", "?order=asc") is none, nor is an
    // order's own page ("/order/123", "/my/orders") or a page about payments ("/news/payments-industry").
    private static final Pattern MONEY_PART = Pattern.compile("(?i)(?:secure-?)?check-?out|payments?|pay|billing|purchase|buy|buy-?now|direct-?buy|instant-?buy|place-?order"
        + "|order-?(?:confirm(?:ation)?|pay(?:ment)?|checkout|sheet|form|submit|place)|transfer|withdraw(?:al)?|remit(?:tance)?|top-?up|recharge|delete|remove|uninstall"
        + "|(?:do|go|req|request|submit|proc)?(?:pay(?:ment)?|checkout|purchase)(?:proc|process|action|submit|request|ok|start|form|info|ready|approve)?");
    private static final Set<String> ORDER_PART = Set.of("order", "orders", "cart", "basket", "bag");
    private static final Set<String> ORDER_STEP = Set.of("confirm", "confirmation", "pay", "payment", "checkout", "place", "submit", "sheet", "form", "approve");
    private static final Set<String> PLAN_PART = Set.of("plan", "plans", "pricing", "subscription", "subscriptions", "premium", "pro", "plus", "membership", "account", "billing");
    private static final Pattern ACTION_KEY = Pattern.compile("(?i)action|act|cmd|command|mode|method|step|op|operation|do|task|fn|func");
    /** A web address that pays, moves money or deletes (its path parts and its action keys; never its sort or filter keys). */
    public static boolean forbiddenUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String path, query;
        try {
            java.net.URI uri = new java.net.URI(url.strip());
            path = uri.getRawPath() == null ? "" : uri.getRawPath(); query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        } catch (Exception invalid) {
            String plain = url.strip().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*", "").replaceFirst("#.*$", "");
            int at = plain.indexOf('?'); path = at < 0 ? plain : plain.substring(0, at); query = at < 0 ? "" : plain.substring(at + 1);
        }
        java.util.List<String> parts = new ArrayList<>();
        for (String part : path.split("[/;]")) {
            String decoded;
            try { decoded = java.net.URLDecoder.decode(part, java.nio.charset.StandardCharsets.UTF_8); } catch (IllegalArgumentException bad) { decoded = part; }
            decoded = decoded.toLowerCase(Locale.ROOT).replaceFirst("\\.[a-z0-9]{1,5}$", "");
            if (!decoded.isBlank()) parts.add(decoded);
        }
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (MONEY_PART.matcher(part).matches() || MONEY_PART.matcher(part.replaceAll("[-_]", "")).matches()) return true;
            if (i > 0 && ORDER_PART.contains(parts.get(i - 1)) && ORDER_STEP.contains(part)) return true;
            if (part.equals("upgrade") && parts.stream().anyMatch(PLAN_PART::contains)) return true;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !ACTION_KEY.matcher(pair.substring(0, eq)).matches()) continue;
            String value;
            try { value = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).strip(); }
            catch (IllegalArgumentException bad) { value = pair.substring(eq + 1).toLowerCase(Locale.ROOT); }
            if (MONEY_PART.matcher(value).matches() || MONEY_PART.matcher(value.replaceAll("[-_\\s]", "")).matches() || value.equals("order") || value.equals("place_order")) return true;
        }
        return false;
    }

    // Banking, card, pay, securities and crypto apps, by package or launcher label (sites: bankingHost).
    private static final Pattern BANKING = Regex.unicode("(?i)bank|뱅크|뱅킹|은행|증권|securit|kbstar|shinhan|wooribank|woori|hanabank|kebhana|hana\\.?onq|하나원큐|원큐|"
        + "nh\\.?smart|nhallonepay|nonghyup|농협|ibk|kakaobank|카카오뱅크|toss|토스|kbank|케이뱅크|epost|우체국|suhyup|수협|kdb|citibank|씨티|scbank|standardchartered|"
        + "busanbank|kjbank|jbbank|knbank|jejubank|dgb|bnk|imbank|아이엠뱅크|새마을금고|kfcc|신협|저축은행|savingsbank|sbisb|"
        + "kiwoom|키움|miraeasset|미래에셋|samsungpop|truefriend|한국투자|nhqv|kbsec|daishin|대신증권|shinhaninvest|yuanta|유안타|meritz|메리츠|ebest|ls증권|hanwhawm|kyobo|"
        + "heromts|mstock|영웅문|"
        + "kbcard|kbpay|kb\\s*pay|kb\\s*스타|samsungcard|삼성카드|hyundaicard|현대카드|shcard|신한카드|신한플레이|sol\\s*pay|sol페이|쏠페이|lottecard|롯데카드|hanacard|hanaskcard|하나카드|"
        + "wooricard|우리카드|bccard|bc카드|비씨카드|페이북|ispmobile|mispandroid|kvp\\.jjy|nhcard|농협카드|citicard|국민카드|[a-z]+card\\.|card$|[가-힣a-z]+카드$|"
        + "kakaopay|카카오페이(?!지)|naverpay|네이버페이|naverfin|spay|samsungpay|삼성페이|samsung\\s*wallet|삼성\\s*월렛|payco|페이코|paypal|페이팔|venmo|zelle|cash\\s*app|squareup\\.cash|"
        + "\\bwise\\b|transferwise|revolut|\\bchase\\b|bankofamerica|bofa|wellsfargo|capitalone|\\bn26\\b|monzo|robinhood|schwab|fidelity|etrade|e\\*trade|interactivebrokers|klarna|afterpay|affirm|"
        + "westernunion|remitly|moneygram|payoneer|skrill|alipay|paytm|phonepe|walletnfcrel|nbu\\.paisa|google\\s*pay|google\\s*wallet|apple\\s*pay|ssgpay|ssg\\s*pay|ssg페이|"
        + "smilepay|스마일페이|l\\.?pay|엘페이|zeropay|제로페이|우리페이|원페이|쿠페이|coupay|\\bchai\\b|차이페이|하나머니|"
        + "wallet|월렛|upbit|업비트|dunamu|bithumb|빗썸|coinone|코인원|korbit|코빗|gopax|고팍스|binance|coinbase|kraken|\\bokx\\b|bybit|crypto\\.com|co\\.mona|"
        + "pay(?:app|apk)|[a-z]pay\\b|\\bpay\\b|[가-힣a-z]+페이(?![가-힣])|금융투자");
    public static boolean bankingApp(String packageOrLabel) {
        if (packageOrLabel == null || packageOrLabel.isBlank()) return false;
        String value = packageOrLabel.strip();
        // A package name: any "pay" segment counts ("com.naverfin.payapp", "com.shcard.smartpay").
        if (!value.contains(" ") && value.contains(".") && value.toLowerCase(Locale.ROOT).contains("pay")) return true;
        return BANKING.matcher(value).find();
    }
    // Banking, card, securities, pay and crypto-exchange websites by their own domains (each with its subdomains): never a word
    // inside a host, which also caught security news, the World Bank and blogs. Look-alike hosts are SecurityLink's.
    private static final Set<String> MONEY_SITES = Set.of(
        // Korean banks, savings banks and cooperatives
        "kbstar.com", "shinhan.com", "wooribank.com", "hanabank.com", "kebhana.com", "ibk.co.kr", "nonghyup.com", "nhbank.com", "kdb.co.kr", "suhyup-bank.com",
        "kbanknow.com", "kakaobank.com", "tossbank.com", "sc.co.kr", "standardchartered.co.kr", "citibank.co.kr", "busanbank.co.kr", "dgb.co.kr", "imbank.co.kr",
        "kjbank.com", "jbbank.co.kr", "knbank.co.kr", "jejubank.co.kr", "epostbank.go.kr", "cu.co.kr", "kfcc.co.kr", "mgbank.co.kr", "fsb.or.kr", "sbisb.co.kr",
        "oksavingsbank.com", "welcomebank.co.kr", "pepperbank.kr",
        // Korean card companies
        "kbcard.com", "shinhancard.com", "samsungcard.com", "hyundaicard.com", "lottecard.co.kr", "hanacard.co.kr", "wooricard.com", "bccard.com", "nhcard.co.kr",
        // Korean securities
        "kiwoom.com", "miraeasset.com", "samsungpop.com", "truefriend.com", "koreainvestment.com", "nhqv.com", "kbsec.com", "shinhansec.com", "shinhaninvest.com",
        "hanaw.com", "daishin.com", "yuantakorea.com", "eugenefn.com", "imeritz.com", "ls-sec.co.kr", "ebestsec.co.kr", "hanwhawm.com", "iprovest.com", "db-fi.com",
        "hmsec.com", "ibks.com", "sks.co.kr", "shinyoung.com", "hi-ib.com", "kiwoomsec.com", "tossinvest.com", "kakaopaysec.com",
        // Pay and payment gateways
        "toss.im", "tosspayments.com", "pay.naver.com", "kakaopay.com", "payco.com", "pay.samsung.com", "samsungpay.com", "wallet.samsung.com", "paypal.com", "paypal.me",
        "checkout.stripe.com", "buy.stripe.com", "billing.stripe.com", "invoice.stripe.com", "dashboard.stripe.com", "pay.google.com", "payments.google.com",
        "wallet.google.com", "pay.amazon.com", "alipay.com", "paytm.com", "venmo.com", "cash.app", "zellepay.com", "wise.com", "revolut.com", "westernunion.com",
        "remitly.com", "moneygram.com", "payoneer.com", "skrill.com", "klarna.com", "afterpay.com", "affirm.com", "ssgpay.com", "lpay.com", "zeropay.or.kr",
        "chai.finance", "inicis.com", "kcp.co.kr", "nicepay.co.kr", "danal.co.kr", "danalpay.com", "kgmobilians.com", "settlebank.co.kr", "kicc.co.kr", "ksnet.co.kr",
        "payapp.kr", "smilepay.co.kr",
        // Crypto exchanges and wallets
        "upbit.com", "bithumb.com", "coinone.co.kr", "korbit.co.kr", "gopax.co.kr", "binance.com", "coinbase.com", "okx.com", "bybit.com", "kraken.com", "bitget.com",
        "kucoin.com", "gate.io", "gate.com", "htx.com", "huobi.com", "mexc.com", "bitfinex.com", "bitstamp.net", "gemini.com", "crypto.com", "bitflyer.com", "probit.com",
        "blockchain.com",
        // Major banks and brokers elsewhere
        "chase.com", "bankofamerica.com", "wellsfargo.com", "citi.com", "citibank.com", "capitalone.com", "usbank.com", "pnc.com", "hsbc.com", "hsbc.co.uk",
        "barclays.co.uk", "lloydsbank.com", "natwest.com", "santander.com", "santander.co.uk", "bnpparibas.com", "db.com", "deutsche-bank.de", "ing.com", "mufg.jp",
        "bk.mufg.jp", "smbc.co.jp", "mizuhobank.co.jp", "n26.com", "monzo.com", "schwab.com", "fidelity.com", "etrade.com", "robinhood.com", "interactivebrokers.com",
        "vanguard.com", "tdbank.com", "rbcroyalbank.com", "commbank.com.au", "anz.com", "westpac.com.au", "dbs.com.sg", "ocbc.com", "uob.com.sg");
    // A host whose own part says pay ("pay.example.com", "checkout.example.com", "billing.example.com"), never its last part.
    private static final Pattern PAY_LABEL = Pattern.compile("(?:^|\\.)(?:pay|payment|payments|checkout|billing)\\.[^.]+(?:\\.[^.]+)*$");
    /** A banking, card, securities, pay or crypto-exchange website: its host is one of MONEY_SITES or a subdomain of one, or names
     * pay itself (PAY_LABEL). worldbank.org, a security news site or a blog about securities is none. */
    public static boolean bankingHost(String url) {
        try {
            String host = new java.net.URI(url == null ? "" : url.strip()).getHost();
            if (host == null || host.isBlank()) return false;
            host = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (PAY_LABEL.matcher(host).find()) return true;
            for (String site = host; ; site = site.substring(site.indexOf('.') + 1)) {
                if (MONEY_SITES.contains(site)) return true;
                if (site.indexOf('.') < 0) return false;
            }
        } catch (Exception invalid) { return false; }
    }

    // ── Kinds of consequential controls, from their labels ─────────────────────────────────────

    private static final Pattern SEND_LABEL = Regex.unicode("(?i)보내|전송|발송|답장|(?<![A-Za-z0-9])(?:send|reply|forward)(?![A-Za-z0-9])");
    private static final Pattern POST_LABEL = Regex.unicode("(?i)게시|올리기|업로드|댓글|공유|포스팅|(?<![A-Za-z0-9])(?:post|publish|upload|comment|share|tweet)(?![A-Za-z0-9])");
    private static final Pattern SUBMIT_LABEL = Regex.unicode("(?i)제출|신청|예약|예매|접수|등록|가입|응모|(?<![A-Za-z0-9])(?:submit|apply|book|reserve|register|sign ?up|enroll)(?![A-Za-z0-9])");
    private static final Pattern CALL_LABEL = Regex.unicode("(?i)전화|통화|발신|(?<![A-Za-z0-9])(?:call|dial)(?![A-Za-z0-9])");
    private static final Pattern CONFIRM_LABEL = Regex.unicode("(?i)^(?:예|네|확인|동의|수락|허용|계속|ok|okay|yes)$|수락|동의|허용|(?<![A-Za-z0-9])(?:accept|agree|allow|confirm|continue)(?![A-Za-z0-9])");
    public static Kind kind(String label) {
        String text = label == null ? "" : label.strip();
        if (text.isEmpty()) return Kind.OTHER;
        if (SEND_LABEL.matcher(text).find()) return Kind.SEND;
        if (POST_LABEL.matcher(text).find()) return Kind.POST;
        if (SUBMIT_LABEL.matcher(text).find()) return Kind.SUBMIT;
        if (CALL_LABEL.matcher(text).find()) return Kind.CALL;
        if (CONFIRM_LABEL.matcher(text.replaceAll("\\s+", "")).find() || CONFIRM_LABEL.matcher(text).find()) return Kind.CONFIRM;
        return Kind.OTHER;
    }

    // ── The user's own words: one injection guard, never a verb table ────────────────────────────

    /*
     * A consequential step runs on the model's or planner's choice. The user's words are
     * read for one thing only: whether the task is nothing but a read or a question. Recognition can be imperfect, so they
     * never have to match a request form; an empty or missing transcript is no evidence of anything.
     */
    // A quote is content to send or post ("'보내지 마'라고 보내 줘"), never part of the request.
    private static final Pattern QUOTED = Pattern.compile("(?<![A-Za-z0-9])'[^'\\n]{1,200}'(?![A-Za-z0-9])|\"[^\"\\n]{1,200}\"|“[^”\\n]{0,200}”|‘[^’\\n]{0,200}’|「[^」\\n]{0,200}」|『[^』\\n]{0,200}』");
    private static final Pattern CLAUSE_BREAK = Regex.unicode("(?i)[,，.!?。！？;\\n]+|\\s(?:그리고|그런데|근데|하지만|그다음에?|다음에|and|but|then)\\s");
    // A clause that says not to do something ("보내지 마", "답장은 하지 말고", "don't send"): its action words ask for nothing.
    private static final Pattern NEGATION = Regex.unicode("(?i)지\\s*(?:는|도|를)?\\s*(?:마|말|않)|말고|(?:^|\\s)(?:안|못)\\s+\\S|필요\\s*(?:없|는\\s*없)|하지\\s*않|(?<![A-Za-z0-9])(?:don'?t|do\\s+not|never|no\\s+need|without)(?![A-Za-z0-9])");
    private static final String PARTICLE = "\\s*(?:을|를|은|는|도|만|좀)?\\s*";
    // Passing words on to someone: a quoted ending ("늦는다고", "언제 오냐고", "확인했다고") right before a telling or asking verb.
    // What comes before the ending is the message, not the request (reported).
    private static final String TELL = "해\\s*(?:줘|주|줄|드려|달라)|알려\\s*(?:줘|주|줄|드려|라)|말해\\s*(?:줘|주|줄|드려|라)|말씀\\s*(?:해|드려)|전해|물어\\s*(?:봐|줘|주|볼래)"
        + "|여쭤|카톡\\s*(?:해|보내)|문자\\s*(?:해|보내)|연락\\s*해|답(?:장)?\\s*(?:해|보내)|보내|써\\s*(?:줘|주|줄|라)|남겨\\s*(?:줘|주)";
    private static final Pattern QUOTE_TELL = Regex.unicode("(?:다|냐|라|자)고(?:만|는|도|요)?\\s*(?:좀\\s*)?(" + TELL + ")");
    // A person the words go to ("엄마한테", "팀장님께", "동료에게"), not the user ("나한테", "저에게") nor a sender ("엄마한테 온",
    // "엄마께서"), then a verb that tells, asks or messages them.
    private static final String RECIPIENT_TELL = "(?<![가-힣a-z0-9])(?!(?:나|저|우리|저희|함)(?:한테|에게|께))[가-힣a-z0-9]+?(?:한테|에게|께)(?!서)"
        + "(?:(?!왔|받은|받았|보낸|(?<![가-힣])온(?![가-힣])).)*?"
        + "(?:알려(?!\\s*(?:줬|준|드렸|드린))|말해|말씀|전해(?!\\s*(?:들|온))|물어(?!\\s*(?:봤|본))|여쭤(?!\\s*(?:봤|본))|카톡\\s*해|문자\\s*해|연락\\s*해|답\\s*해|답장\\s*해)";
    // Words that change something. Opening an app, a page or a screen is no change (routine) and is not here. A noun of the same
    // stem is no change: 게시글, 재생목록, 팔로워, 작성자, 켜져 있어, 변경 내역, 구매 내역.
    private static final Pattern ACTION = Regex.unicode("(?i)보내|전송(?!된|\\s*(?:내역|기록))|부쳐|부치|답장" + PARTICLE + "(?:해|하|보내|써|쓰|남겨|달아)|답해|회신|올려|올리|게시(?!\\s*(?:글|물|판))|업로드(?!된|한)"
        + "|포스팅" + PARTICLE + "(?:해|하)|공유" + PARTICLE + "(?:해|하)"
        + "|댓글" + PARTICLE + "(?:달|남겨|남기|써|쓰|작성|등록)|남겨\\s*(?:줘|주)|써\\s*(?:줘|주|줄|서|라)|쓰자|작성(?!자|된|한|했)|적어|입력(?!된|한|했|창|란)|채워|넣어|담아(?!\\s*(?:둔|놓은|놨|뒀|있))"
        + "|제출|신청" + PARTICLE + "(?:해|하)"
        + "|예약" + PARTICLE + "(?:해|하|잡)|예매" + PARTICLE + "(?:해|하)|접수" + PARTICLE + "(?:해|하)|등록" + PARTICLE + "(?:해|하)|가입" + PARTICLE + "(?:해|하)|응모(?!\\s*(?:내역|기록|결과))"
        + "|전화" + PARTICLE + "(?:해|하|걸|드려)|통화" + PARTICLE + "(?:해|하|걸)|걸어|눌러|누르|클릭|탭\\s*(?:해|하)|터치|선택" + PARTICLE + "(?:해|하)|골라|체크" + PARTICLE + "(?:해|하)"
        + "|켜(?!져|진|\\s*있)|꺼\\s*(?:줘|주|줄)|끄|바꿔|바꾸|변경(?!\\s*(?:내역|이력|사항|기록|된|됐))|설정" + PARTICLE + "(?:해|하)|저장" + PARTICLE + "(?:해|하)|추가" + PARTICLE + "(?:해|하)|복사|붙여"
        + "|다운(?!\\s*(?:로드)?\\s*(?:목록|내역|기록|폴더|된|한|받은))|설치(?!된|\\s*(?:목록|내역))"
        + "|구독" + PARTICLE + "(?:해|하|눌러)|팔로(?!워|잉)|좋아요" + PARTICLE + "(?:눌러|해|하)|만들|생성(?!된)|그려|작곡(?!가)|실행" + PARTICLE + "(?:해|하)|틀어|재생(?!\\s*(?:목록|시간|중|횟수|수))"
        + "|시작" + PARTICLE + "(?:해|하)"
        + "|동의" + PARTICLE + "(?:해|하|눌러)|수락(?!된|\\s*(?:내역|대기))|허용" + PARTICLE + "(?:해|하|눌러)|승인(?!된|\\s*(?:내역|이력|대기))|확정(?!된)|결제" + PARTICLE + "(?:해|하)"
        + "|(?:구매|구입)(?!\\s*(?:내역|이력|목록|기록|한|했))|주문" + PARTICLE + "(?:해|하)|(?:송금|이체)(?!\\s*(?:내역|이력|기록|한|했))"
        + "|삭제" + PARTICLE + "(?:해|하)|지워|지우|사\\s*(?:줘|주세요|줄래)"
        + "|" + RECIPIENT_TELL + "|(?:다|냐|라|자)고(?:만|는|도|요)?\\s*(?:좀\\s*)?(?:" + TELL + ")"
        // English post and reply are nouns as often as verbs ("read this post", "read the reply"): only an imperative asks.
        + "|^\\s*(?:(?:please|pls|can\\s+you|could\\s+you|would\\s+you|will\\s+you|just|now|go\\s+ahead\\s+and)\\s+)*(?:post|reply)(?![A-Za-z0-9])|\\byou\\s+to\\s+(?:post|reply)(?![A-Za-z0-9])"
        + "|\\border\\b(?!\\s+(?:status|history|details?|number|list|of|tracking))"
        + "|(?<![A-Za-z0-9])(?:tell|ask)\\s+(?!(?:me|us|about|what|which|who|whom|when|where|why|how|if|whether)(?![A-Za-z0-9]))[a-z]"
        + "|(?<![A-Za-z0-9])(?:send|respond|forward|publish|share|upload|comment|tweet|submit|apply|book|reserve|register|sign\\s*up|enroll|call|dial|tap|click|press|hit|touch"
        + "|select|choose|pick|toggle|turn\\s+(?:on|off)|switch|enable|disable|type|enter|fill|write|save|add|copy|paste|download|install|subscribe|follow|make|create|generate"
        + "|compose|draw|build|play|start|accept|agree|allow|approve|confirm|pay|buy|purchase|checkout|transfer|delete|remove|erase|uninstall)(?![A-Za-z0-9])");
    // Words of a read or a question.
    private static final Pattern READ = Regex.unicode("(?i)읽|요약|정리|알려|말해|보여|확인|찾아|검색|설명|번역|비교|알아|봐\\s*(?:줘|주|줄)|뭐|무슨|무엇|어때|어떤|어떻|어디|언제|누가|누구|왜|몇"
        + "|있어|있나|있니|있는지|있을까|왔어|왔나|왔니|왔는지|됐어|됐나|맞아|맞나|인가|일까|[?？]"
        + "|(?<![A-Za-z0-9])(?:read|summari[sz]e|summary|tell\\s+me|show\\s+me|what|which|who|when|where|why|how|is\\s+there|are\\s+there|any|check|explain|translate|compare|look\\s+up|find|search|list)(?![A-Za-z0-9])");
    // A forbidden step asked for ("결제해 줘", "이 메일 삭제해 줘"); a mention ("결제 내역 보여 줘") is not one.
    private static final Pattern FORBIDDEN_ASK = Regex.unicode("(?i)(?:결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|충전|환전|매수|매도|대출|삭제|제거|초기화|탈퇴|해지|포맷|언인스톨|선물|대여|소장|후원|기부)"
        + PARTICLE + "(?:해|하|합|할)|(?<![가-힣])사\\s*(?:줘|주세요|줄래|주실래요)|지워|지우|비워|비우|없애|돈" + PARTICLE + "(?:보내|부쳐|부치)"
        + "|(?<![A-Za-z0-9])(?:pay|buy|purchase|checkout|check\\s+out|transfer|withdraw|deposit|delete|remove|erase|uninstall|wipe|reset|donate|send\\s+money)(?![A-Za-z0-9])|\\border\\s+(?:it|this|that|a|an|the|me|some|now|online)(?![A-Za-z0-9])");
    // A generation request: make, create, draw, compose, write a text or code, or ask an AI. "그리고" is "and", never "draw".
    private static final Pattern GENERATE = Regex.unicode("(?i)만들(?!어진|어져|어졌)|(?:생성|제작|작곡|작사|편곡|작성|구현|개발|질문)" + PARTICLE + "(?:해|하)|그려|그리(?!고)|물어봐|물어보"
        + "|(?:글|코드|시|소설|가사|이야기|대본|스크립트|에세이|보고서|요약문|이메일|메일|답변|프롬프트|함수|프로그램)[^,\\n]{0,15}?(?:써|쓰)"
        + "|(?<![A-Za-z0-9])(?:make|making|create|creating|generate|generating|compose|composing|draw|drawing|build|building|write|ask)(?![A-Za-z0-9])");
    /** The user's words in clauses, quotes taken out, each with whether it says not to do something. A "not" or a question word
     * inside a message passed on ("오늘 못 간다고 알려 줘", "언제 오냐고 카톡해 줘") is the message's own (reported). */
    private record Clause(String text, String request, boolean negated) {}
    private static List<Clause> clauses(String words) {
        List<Clause> out = new ArrayList<>();
        if (words == null) return out;
        String text = QUOTED.matcher(words.toLowerCase(Locale.ROOT).replaceAll("(?<=[a-z])’(?=[a-z])", "'")).replaceAll(" Q ");
        for (String clause : CLAUSE_BREAK.split(text)) if (!clause.isBlank()) { String request = reported(clause); out.add(new Clause(clause, request, NEGATION.matcher(request).find())); }
        return out;
    }
    /** A clause from its last passed-on message's telling verb on ("늦는다고 알려 줘" → "알려 줘"); the clause itself when it has none. */
    private static String reported(String clause) {
        java.util.regex.Matcher tell = QUOTE_TELL.matcher(clause);
        int at = -1;
        while (tell.find()) at = tell.start(1);
        return at < 0 ? clause : clause.substring(at);
    }
    private static boolean asks(Pattern pattern, String words) {
        for (Clause clause : clauses(words)) if (!clause.negated() && pattern.matcher(clause.text()).find()) return true;
        return false;
    }
    /**
     * The user's words are only a read or a question: a read word ("읽어 줘", "요약해 줘", "뭐야", "summarize", "?") and no word
     * that changes something outside a "don't" ("보내 줘", "눌러 줘", "send"), and no yes (a yes answers an offer). Words passed
     * on to someone ("엄마한테 늦는다고 알려 줘", "동료한테 어디냐고 물어봐") change something. Empty words are no read. This is the
     * one test a consequential step can fail (verdict), and it is forgiving: when unsure, no.
     */
    public static boolean pureRead(String words) {
        if (words == null || words.isBlank()) return false;
        for (String line : words.split("\n")) if (followUp(line)) return false;
        boolean read = false;
        for (Clause clause : clauses(words)) {
            if (!clause.negated() && ACTION.matcher(clause.text()).find()) return false;
            read |= READ.matcher(clause.request()).find();
        }
        return read;
    }
    /** Whether the user's words ask for a change beyond reading or opening ("보내 줘", "눌러 줘", "켜 줘"), outside a "don't". */
    public static boolean asksForAction(String userWords) { return asks(ACTION, userWords); }
    /** Whether the user's own goal was a forbidden step ("결제해 줘", "이 메일 삭제해 줘"); mentioning one ("결제 내역 보여 줘") is not. */
    public static boolean requestsForbidden(String userWords) { return asks(FORBIDDEN_ASK, userWords); }

    // ── Deleting what the user asked to delete ───────────────────────────────────────────────────

    private static final Pattern DELETE_WORDS = Regex.unicode("(?i)삭제(?:하기)?|지우기|지워|지울|지우|제거(?:하기)?|휴지통(?:으로|에)\\s*(?:이동|보내기?|버리기?)|(?<![A-Za-z0-9])(?:delete|remove|erase|discard)(?![A-Za-z0-9])|\\bmove\\s+to\\s+(?:the\\s+)?(?:trash|bin)(?![A-Za-z0-9])");
    // What an item's deletion never covers: an account or a membership, the phone or its data, an app, a store emptied at once.
    private static final Pattern WIPE_WORDS = Regex.unicode("(?i)탈퇴|해지|계정|회원|초기화|포맷|언인스톨|비우|비워|모두|전체|일괄|(?<![A-Za-z0-9])(?:account|reset|format|uninstall|empty|all|everything)(?![A-Za-z0-9])");
    private static final Pattern DELETE_ASK = Regex.unicode("(?i)(?:삭제|제거)\\s*(?:해|하|시켜|좀)|(?:삭제|제거)[\\s.!~]*$|지워|지우|(?<![A-Za-z0-9])(?:delete|remove|erase)(?![A-Za-z0-9])");
    /**
     * A control whose only forbidden part is deleting an item ("삭제", "대화 삭제", "Delete", "Remove"): no price or coin, no
     * money step, no account, no wipe, no "delete all". Such a control may run when the user asked to delete (browser only).
     */
    public static boolean deletionOnly(String label) {
        if (label == null || label.isBlank() || moneyText(label) || WIPE_WORDS.matcher(label).find() || !DELETE_WORDS.matcher(label).find()) return false;
        String rest = DELETE_WORDS.matcher(label).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return rest.isEmpty() || !forbiddenText(rest) && !FORBIDDEN_VERB.matcher(rest.replaceAll("\\s+", "")).find() && !FORBIDDEN_VERB.matcher(rest.toLowerCase(Locale.ROOT)).find();
    }
    /** Whether the user's own words ask to delete something ("최근 대화 10개 삭제해 줘", "지워"), outside a "don't", and not an account, the phone or everything. */
    public static boolean requestsDeletion(String userWords) {
        if (userWords == null || !asks(DELETE_ASK, userWords)) return false;
        return !Regex.unicode("(?i)탈퇴|해지|계정|회원|초기화|포맷|언인스톨|(?<![A-Za-z0-9])(?:account|reset|format|uninstall)(?![A-Za-z0-9])").matcher(userWords).find();
    }

    // ── A generation site's own create control ───────────────────────────────────────────────────

    private static final Set<String> CHAT_SITES = Set.of("gemini.google.com", "chatgpt.com", "chat.openai.com", "claude.ai", "grok.com", "aistudio.google.com");
    private static final Set<String> CREATE_SITES = Set.of("udio.com", "www.udio.com", "ideogram.ai", "leonardo.ai", "app.leonardo.ai", "midjourney.com", "www.midjourney.com",
        "firefly.adobe.com", "labs.google", "krea.ai", "www.krea.ai", "playground.com", "openart.ai");
    /** "chat" for an AI chat composer (Gemini, ChatGPT, Claude, Grok, AI Studio), "create" for a music or image generator (Udio, Ideogram, Leonardo, Midjourney, Firefly, Google Labs, Krea, Playground, OpenArt), else "". */
    public static String generationSite(String url) {
        try {
            java.net.URI uri = new java.net.URI(url == null ? "" : url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return "";
            if (CHAT_SITES.contains(host)) return "chat";
            return CREATE_SITES.contains(host) ? "create" : "";
        } catch (Exception invalid) { return ""; }
    }
    private static final Pattern CHAT_SUBMIT = Regex.unicode("(?i)send|send message|send prompt|submit|submit prompt|run|보내기|전송|실행|질문하기");
    private static final Pattern CREATE_SUBMIT = Regex.unicode("(?i)create|create song|create image|generate|generate image|imagine|compose|생성|생성하기|만들기|노래 만들기|이미지 만들기|그리기|이미지 생성|작곡|작곡하기");
    /** The site's own generation submit: an AI chat composer's send, or a Create/Generate control. */
    public static boolean generationControl(String url, String label) {
        String site = generationSite(url), name = label == null ? "" : label.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (site.isEmpty() || name.isEmpty() || forbiddenLabel(label)) return false;
        return CREATE_SUBMIT.matcher(name).matches() || "chat".equals(site) && CHAT_SUBMIT.matcher(name).matches();
    }
    /** The user's own generation request: 만들어/생성해/그려/작곡해/써 줘 (글·코드)/make/create/generate/compose/draw, or for an AI chat
     * 물어봐/질문해 줘/ask, outside a "don't". It is no send, post, purchase, subscription or upgrade. */
    public static boolean requestsGeneration(String userWords) { return asks(GENERATE, userWords); }
    /** On an AI generation site the user's own generation request is the authority for that site's generation submit (a composer's
     * send, a music or image generator's Create/Generate) and for nothing else: never a send to people, a post, a purchase, a
     * subscription or an upgrade, which keep their own rules (and credit purchases and plan upgrades stay forbidden). */
    public static boolean generationSubmit(String url, String label, String userWords) {
        return generationControl(url, label) && requestsGeneration(userWords);
    }

    // ── Short follow-ups ─────────────────────────────────────────────────────────────────────────

    private static final Pattern YES = Regex.unicode("(?i)^(?:응|어|엉|웅|네|넹|예|그래|그래요|좋아|좋아요|오케이|오키|ok|okay|yes|yeah|yep|sure|맞아|맞아요|그렇게\\s*해(?:\\s*줘|요)?|해\\s*줘|해줘|부탁해|부탁해요|please|go\\s+ahead)"
        + "(?:[\\s,.!~]*(?:응|네|그래|좋아|ok|yes|please|부탁해))*[\\s,.!~]*$");
    private static final Pattern CONFIRMATION = Regex.unicode("(?i)^(?:(?:응|어|네|예|그래|좋아|ok|okay|yes|yeah|sure)[\\s,.!~]*)?(?:(?:그거|그걸|그것|이거|이걸|이것|저거|저걸|그대로|그렇게|지금|바로|그냥|얼른|빨리|좀)\\s*)*"
        + "(?:보내|올려|전송해|제출해|신청해|예약해|게시해|등록해|전화해|전화\\s*걸어|걸어|눌러|해|send|post|submit|call|press|tap|do)\\s*(?:줘|주세요|줘요|요|라|it|that|this|now|please)?[\\s,.!~]*$");
    /** A bare yes ("응", "네", "ok"). */
    public static boolean bareYes(String turn) { return turn != null && YES.matcher(turn.strip()).matches(); }
    /** A short follow-up that answers the previous turn: a bare yes, or a pronoun-only confirmation such as "그거 보내". */
    public static boolean followUp(String turn) {
        String text = turn == null ? "" : turn.strip();
        return !text.isEmpty() && text.replaceAll("\\s+", "").length() <= 20 && (bareYes(text) || CONFIRMATION.matcher(text).matches());
    }

    // ── The verdict ──────────────────────────────────────────────────────────────────────────────

    /** As verdict(tier, kind, words, afterRead, credential), with untrusted content read and no credential. */
    public static Verdict verdict(Tier tier, Kind kind, String userWords) { return verdict(tier, kind, userWords, true, false); }
    /**
     * The gate itself: reads and routine steps run, a forbidden step never does, and a consequential
     * one runs on the model's or planner's choice. Its one guard against page and screen text: it does not run when the user's
     * words for the task are only a read or a question (pureRead) and it was proposed after untrusted content (a page, a
     * screen, a notification) was read in the task (afterRead).
     */
    public static Verdict verdict(Tier tier, Kind kind, String userWords, boolean afterRead, boolean credential) {
        return switch (tier) {
            case OBSERVATION, ROUTINE -> new Verdict(tier, kind, true, "", "");
            case FORBIDDEN -> new Verdict(tier, kind, false, FORBIDDEN, credential ? CREDENTIAL_MESSAGE : FORBIDDEN_MESSAGE);
            case CONSEQUENTIAL -> afterRead && pureRead(userWords) ? new Verdict(tier, kind, false, NOT_REQUESTED, NOT_REQUESTED_MESSAGE)
                : new Verdict(tier, kind, true, "", "");
        };
    }
    /** Whether a tool result is this gate's refusal; such a step did not run. */
    public static boolean refused(JSONObject result) {
        return result != null && Set.of(FORBIDDEN, NOT_REQUESTED).contains(result.optString("error_code"));
    }

    // ── Phone ────────────────────────────────────────────────────────────────────────────────────

    /** Phone tier from PhoneDecisionEvidence.action(...): the trusted local classifiers judge, never a model flag. */
    public static Tier phone(JSONObject evidence) {
        if (evidence == null) return Tier.CONSEQUENTIAL;
        String tool = evidence.optString("tool");
        if (PhoneDecisionRisk.localRead(tool)) return Tier.OBSERVATION;
        if (!"phone_action".equals(tool)) return Tier.CONSEQUENTIAL;
        String action = evidence.optString("action");
        JSONObject current = evidence.optJSONObject("current"), target = evidence.optJSONObject("target");
        // Leaving a screen never acts on it, whatever app is open.
        boolean leaving = Set.of("back", "home", "recents", "notifications", "wait").contains(action);
        if (!leaving && current != null && (bankingApp(current.optString("app")) || bankingApp(current.optString("app_label")))) return Tier.FORBIDDEN;
        // The app the request names and the app it actually resolves to on this phone.
        if ("open_app".equals(action) && (bankingApp(evidence.optString("requested_app")) || bankingApp(evidence.optString("resolved_package"))
                || bankingApp(evidence.optString("resolved_label")))) return Tier.FORBIDDEN;
        if (evidence.optBoolean("input_sensitive")) return Tier.FORBIDDEN;
        if (target != null && PhoneDecisionRisk.forbiddenTarget(target)) return Tier.FORBIDDEN;
        if ("open_url".equals(action) && (forbiddenUrl(evidence.optString("destination")) || bankingHost(evidence.optString("destination")))) return Tier.FORBIDDEN;
        if (PhoneDecisionRisk.routine("action", evidence)) return Tier.ROUTINE;
        return Tier.CONSEQUENTIAL;
    }
    public static Kind phoneKind(JSONObject evidence) {
        if (evidence == null) return Kind.OTHER;
        String action = evidence.optString("action");
        JSONObject target = evidence.optJSONObject("target");
        // The send button actually found on the screen: a 제출 button submits.
        if ("send".equals(action)) { Kind found = target == null ? Kind.OTHER : kind(target.optString("label")); return found == Kind.OTHER ? Kind.SEND : found; }
        // Enter in a chat or reply composer sends, in a comment box posts; a search box is routine.
        if ("press_enter".equals(action)) {
            String field = target == null ? "" : target.optString("field_kind");
            return "chat".equals(field) ? Kind.SEND : "comment".equals(field) ? Kind.POST : Kind.OTHER;
        }
        if (!Set.of("tap", "long_press").contains(action)) return Kind.OTHER;
        if (target == null) return Kind.OTHER;
        // The system UI: a quick-settings tile is unsure and needs words naming it; a notification's smart reply sends.
        if ("tile".equals(target.optString("system_ui"))) return Kind.OTHER;
        if ("reply".equals(target.optString("system_ui"))) return Kind.SEND;
        // A row of a dialer or call log calls back with one tap; a reply chip, or a short reply row, in a messaging app or beside a
        // message box sends it. Each needs the user's own "전화해 줘" or "보내 줘".
        JSONObject current = evidence.optJSONObject("current");
        String app = current == null ? "" : current.optString("app");
        if ("tap".equals(action) && PhoneDecisionRisk.callRow(target, app)) return Kind.CALL;
        if ("tap".equals(action) && PhoneDecisionRisk.replyChip(target, app, current != null && current.optBoolean("composer"))) return Kind.SEND;
        return kind(target.optString("label"));
    }
    /** afterRead: a screen reading (or a notification in it) was handed to the model since the user's latest instruction. */
    public static Verdict phoneVerdict(JSONObject evidence, String userWords, boolean afterRead) {
        Tier tier = phone(evidence);
        return verdict(tier, phoneKind(evidence), userWords, afterRead, evidence != null && evidence.optBoolean("input_sensitive"));
    }

    public static void phoneCompletion(String goal, String answer, JSONObject screen, int actions) throws PhoneAgent.CompletionPending {
        phoneCompletion(goal, answer, screen, actions, "");
    }
    /**
     * Phone completion without a vote. Done when the report claims no success (a question or what could not be done,
     * PhoneTools.claimsNoSuccess), when the user only asked to open something, the job's last step was that open_app and the
     * app it opened (opened, its package) is on the screen, or when the current screen shows text relevant to the goal or the
     * report (PhoneDecisionRisk.completionEvidence). Otherwise CompletionPending, so the caller rereads or reports honestly
     * that it could not confirm. An opened app proves only the opening: "카톡으로 엄마한테 보내 줘" needs the message on screen.
     */
    public static void phoneCompletion(String goal, String answer, JSONObject screen, int actions, String opened) throws PhoneAgent.CompletionPending {
        if (goal == null || goal.length() > 4000 || answer == null || answer.length() > 4000) throw new IllegalStateException("완료 확인 문맥이 너무 길거나 없어요.");
        if (PhoneTools.claimsNoSuccess(answer)) return;
        if (opened != null && !opened.isBlank() && !asksForAction(goal) && screen != null && screen.optBoolean("ok") && opened.equals(screen.optString("app"))) return;
        if (!PhoneDecisionRisk.completionEvidence(PhoneDecisionEvidence.completion(goal, answer, screen, actions)))
            throw new PhoneAgent.CompletionPending("현재 폰 화면에서 완료 근거를 다시 확인해요. 수행한 동작은 반복하지 않아요.");
    }

    /** What the read-only guard reads of one task message: the user's own words (userWords), and for this app's own prompts,
     * which are all reads (a summary or translate chip, the selected-text translate and explain), a plain read request. */
    public static String guardWords(String message) {
        String text = message == null ? "" : message.strip();
        return BrowserPrompts.resumedWords(text) == null && BrowserPrompts.appPrompt(text) ? APP_READ : userWords(message);
    }
    static final String APP_READ = "읽어 줘";
    /**
     * The user's own words in one task message: a finalized voice envelope gives its original instruction and the turn a
     * short follow-up answers, a 작업창 subtask only its user part, this app's own prompts (selected-text translate/explain,
     * suggestion chips) nothing, and its resume prompt only the original task's words it carries. Model interpretations,
     * page and tool text are left out.
     */
    public static String userWords(String message) {
        String text = message == null ? "" : message.strip();
        if (text.startsWith("{")) try {
            JSONObject envelope = new JSONObject(text);
            if ("finalized_voice".equals(envelope.optString("source"))) {
                StringBuilder words = new StringBuilder();
                org.json.JSONArray earlier = envelope.optJSONArray("earlier_user_turns");
                for (int i = 0; earlier != null && i < earlier.length(); i++) words.append(earlier.optString(i)).append('\n');
                return words.append(envelope.optString("original_user_instruction")).toString();
            }
        } catch (Exception plainText) { /* An ordinary instruction that starts with a brace. */ }
        String resumed = BrowserPrompts.resumedWords(text);
        if (resumed != null) return resumed;
        if (BrowserPrompts.appPrompt(text)) return "";
        if (text.startsWith(AUTHORITY_PREFIX)) {
            int end = text.indexOf(AUTHORITY_END);
            if (end < 0) return "";
            StringBuilder words = new StringBuilder();
            for (String line : text.substring(AUTHORITY_PREFIX.length(), end).split("\n"))
                if (!line.isBlank()) words.append(line.startsWith(AUTHORITY_LINE) ? line.substring(AUTHORITY_LINE.length()) : line).append('\n');
            return words.toString().strip();
        }
        return text;
    }
}
