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
        + "업그레이드|크레딧(?:구매|구입|충전|추가)|요금제(?:가입|변경)|플랜(?:가입|변경)");
    // Benign uses of those stems: battery charging, resetting a filter or a search.
    private static final String KO_BENIGN = "(?:배터리|고속|급속|무선|유선)충전|충전(?:중|기|량|상태|속도|케이블|단자|제한|모드)|(?:필터|검색어?|조건|선택|정렬|옵션|입력|줌|확대|화면)초기화";
    private static final Pattern FORBIDDEN_EN = Regex.unicode(
        "(?i)\\b(?:pay(?:ments?)?|purchases?|buy(?:ing)?|checkout|check\\s?out|(?:place|complete|submit|confirm)\\s+(?:your\\s+|the\\s+|my\\s+)?order|order\\s+now|"
        + "order\\s+(?:complete[d]?|completion|done|confirm(?:ed|ation)?|pending|placed|processing|in\\s+progress|success(?:ful)?|succeeded|failed|submitted|received)|"
        + "transfers?|withdraw\\w*|deposits?|top\\s?up|recharge|donate|cash\\s+advance|send\\s+money|"
        + "delete|remove|erase|uninstall|wipe|discard|factory\\s?reset|reset\\s+(?:phone|device|data|all|everything)|"
        + "clear\\s+(?:data|storage|cache|history|chat|conversation|all\\s+data)|(?:close|delete|deactivate|cancel)\\s+(?:my\\s+|your\\s+|the\\s+)?account|"
        + "format\\s+(?:sd|card|storage|device|disk|drive|usb)|empty\\s+(?:the\\s+)?(?:trash|bin)|move\\s+to\\s+(?:the\\s+)?(?:trash|bin)|"
        + "upgrade[sd]?|go\\s+(?:pro|premium|plus)|get\\s+(?:pro|premium|plus)|(?:buy|get|add|purchase)\\s+(?:more\\s+)?credits?|subscribe\\s+to\\s+(?:pro|premium|plus))\\b");
    private static final String EN_BENIGN = "(?:remove|clear|reset)\\s+(?:all\\s+)?filters?|remove\\s+from\\s+(?:search|results)|(?:file|data|usb|photo|media)\\s+transfers?|"
        + "transfer\\s+(?:files?|photos?|media|data)|clear\\s+(?:search|text|query|input)";
    // A price or a coin spend: "₩12,000", "3,900원", "$4.99", "쿠키 3개로 보기", "Unlock for 3 coins".
    private static final Pattern PRICE = Regex.unicode("(?i)[₩$€£¥]\\s*\\d|\\d[\\d,.]*\\s*(?:원|달러|유로|엔)(?:(?![가-힣a-z])|(?=에|짜리|씩|부터|이에요|입니다))|\\b(?:usd|krw|eur)\\s*\\d");
    private static final Pattern SPEND = Regex.unicode("(?i)\\d+\\s*(?:개|코인|쿠키|캐시|포인트|젬|다이아|루비|골드|하트|별|원)\\s*(?:으로|로)\\s*(?:보기|열기|구매|구입|대여|소장|이용|받기|사용|잠금\\s*해제)"
        + "|\\b(?:unlock|use|spend|redeem)\\b[^\\n]{0,20}?\\b\\d+\\s*(?:coins?|gems?|credits?|points?|cookies?|tokens?|diamonds?)\\b|\\b\\d+\\s*(?:coins?|gems?|credits?|cookies?|diamonds?)\\s+to\\s+(?:unlock|read|watch|view|buy)\\b");
    // A read or navigation label ("결제 내역", "주문 조회", "Purchase history"): exempt unless a money or confirm verb is present.
    private static final Pattern READ_ENDING = Regex.unicode("(?i)(?:(?:내역|이력|기록|목록|리스트|현황|상세|정보|영수증|명세서?)(?:확인|조회|보기)?|조회|보기|열기|"
        + "history|list|lists|details?|records?|receipts?|status|summary|statements?|activity)$");
    // A status word ("결제 완료", "입금 대기", "Order complete"): the text of a record. It exempts only a text part of a multi-part list
    // row (an order-history row's status chip), never a control of its own: a "결제 완료" button pays.
    private static final Pattern STATUS_ENDING = Regex.unicode("(?i)(?:완료|완료됨|대기|대기중|진행중|처리중|취소됨|실패|"
        + "complete|completed|done|pending|processing|in\\s*progress|confirmed|successful|succeeded|failed|cancell?ed)$");
    private static final Pattern MONEY_VERB = Regex.unicode("(?i)(?:결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|충전|환전|매수|매도|대여|소장|후원|기부|삭제|제거|초기화|탈퇴|해지|포맷|선물)"
        + "(?:하기|하고|하|해|할|합|진행|요청|신청|확인|승인|동의)|바로구매|즉시구매|(?:확인|동의)(?:및|하고|후)(?:결제|구매|주문|송금|이체)|지우|비우|비워|지워"
        + "|\\b(?:pay|buy|checkout|delete|remove|erase|clear|wipe|discard|empty|uninstall|reset|format|confirm|agree)\\b|\\border\\s+now\\b|\\bplace\\s+(?:your\\s+)?order\\b");
    // A forbidden operation in a verb form, as on a control ("결제하기", "삭제하고", "Pay now").
    private static final Pattern FORBIDDEN_VERB = Regex.unicode("(?:결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|충전|환전|매수|매도|대출|대여|소장|후원|기부|삭제|제거|초기화|탈퇴|해지|포맷|업그레이드)"
        + "(?:하기|하고|하|해|할|합)|바로구매|즉시구매|지우|비우|지워|비워|(?i)^\\W*(?:pay|buy|purchase|checkout|order|delete|remove|erase|uninstall|wipe|discard|transfer|withdraw|deposit|donate|top\\s?up|upgrade)\\b");

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
    /** Text naming a forbidden operation: payment, purchase, order, money transfer, deletion, an account or data wipe, a price
     * or a coin spend. Read and navigation labels ("결제 내역", "Purchase history", "필터 초기화") and a record's status ("결제 완료",
     * "Order completed") are not forbidden text; a control that shows a status still is (forbiddenLabel). */
    public static boolean forbiddenText(String text) { return forbiddenText(text, true); }
    private static boolean forbiddenText(String text, boolean status) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT), compact = lower.replaceAll("\\s+", "");
        boolean hit = FORBIDDEN_KO.matcher(compact.replaceAll(KO_BENIGN, "")).find() || FORBIDDEN_EN.matcher(lower.replaceAll(EN_BENIGN, " ")).find()
            || PRICE.matcher(lower).find() || SPEND.matcher(compact).find() || SPEND.matcher(lower).find();
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
        + "\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|\\d{2,4}[./-]\\s*\\d{1,2}[./-]\\s*\\d{1,2}|\\b(?:yesterday|today|just\\s+now|\\d+\\s*(?:s|m|h|d|w|mins?|minutes?|hrs?|hours?|days?)\\s+ago)\\b");
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
        // "결제 완료됨" ends like a sentence, yet on a control of its own it is the money step all the same.
        return !status && statusEnding(text) && text.split("\\s+").length <= 4;
    }
    // A web address step that pays, moves money or deletes.
    private static final Pattern FORBIDDEN_URL = Pattern.compile("(?i)(?:/|[?&][a-z_]+=)(?:checkout|payment|pay|purchase|buy|order|transfer|withdraw|delete|remove|uninstall)(?:[/?#.&=_-]|$)");
    /** A web address that pays, moves money or deletes. */
    public static boolean forbiddenUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI uri = new java.net.URI(url.strip());
            String rest = (uri.getRawPath() == null ? "" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return FORBIDDEN_URL.matcher(rest).find();
        } catch (Exception invalid) { return FORBIDDEN_URL.matcher(url).find(); }
    }

    // Banking, card, pay, securities and crypto apps and sites, by package, launcher label or host.
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
    /** A banking, securities or payment website. */
    public static boolean bankingHost(String url) {
        try {
            String host = new java.net.URI(url == null ? "" : url.strip()).getHost();
            return host != null && (BANKING.matcher(host).find() || host.toLowerCase(Locale.ROOT).matches("(?:.+\\.)?pay\\..+|.*pay\\.(?:com|co\\.kr|kr|net)"));
        } catch (Exception invalid) { return false; }
    }

    // ── Kinds of consequential controls, from their labels ─────────────────────────────────────

    private static final Pattern SEND_LABEL = Regex.unicode("(?i)보내|전송|발송|답장|\\b(?:send|reply|forward)\\b");
    private static final Pattern POST_LABEL = Regex.unicode("(?i)게시|올리기|업로드|댓글|공유|포스팅|\\b(?:post|publish|upload|comment|share|tweet)\\b");
    private static final Pattern SUBMIT_LABEL = Regex.unicode("(?i)제출|신청|예약|예매|접수|등록|가입|응모|\\b(?:submit|apply|book|reserve|register|sign ?up|enroll)\\b");
    private static final Pattern CALL_LABEL = Regex.unicode("(?i)전화|통화|발신|\\b(?:call|dial)\\b");
    private static final Pattern CONFIRM_LABEL = Regex.unicode("(?i)^(?:예|네|확인|동의|수락|허용|계속|ok|okay|yes)$|수락|동의|허용|\\b(?:accept|agree|allow|confirm|continue)\\b");
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
    private static final Pattern NEGATION = Regex.unicode("(?i)지\\s*(?:는|도|를)?\\s*(?:마|말|않)|말고|(?:^|\\s)(?:안|못)\\s+\\S|필요\\s*(?:없|는\\s*없)|하지\\s*않|\\b(?:don'?t|do\\s+not|never|no\\s+need|without)\\b");
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
        + "|^\\s*(?:(?:please|pls|can\\s+you|could\\s+you|would\\s+you|will\\s+you|just|now|go\\s+ahead\\s+and)\\s+)*(?:post|reply)\\b|\\byou\\s+to\\s+(?:post|reply)\\b"
        + "|\\border\\b(?!\\s+(?:status|history|details?|number|list|of|tracking))"
        + "|\\b(?:tell|ask)\\s+(?!(?:me|us|about|what|which|who|whom|when|where|why|how|if|whether)\\b)[a-z]"
        + "|\\b(?:send|respond|forward|publish|share|upload|comment|tweet|submit|apply|book|reserve|register|sign\\s*up|enroll|call|dial|tap|click|press|hit|touch"
        + "|select|choose|pick|toggle|turn\\s+(?:on|off)|switch|enable|disable|type|enter|fill|write|save|add|copy|paste|download|install|subscribe|follow|make|create|generate"
        + "|compose|draw|build|play|start|accept|agree|allow|approve|confirm|pay|buy|purchase|checkout|transfer|delete|remove|erase|uninstall)\\b");
    // Words of a read or a question.
    private static final Pattern READ = Regex.unicode("(?i)읽|요약|정리|알려|말해|보여|확인|찾아|검색|설명|번역|비교|알아|봐\\s*(?:줘|주|줄)|뭐|무슨|무엇|어때|어떤|어떻|어디|언제|누가|누구|왜|몇"
        + "|있어|있나|있니|있는지|있을까|왔어|왔나|왔니|왔는지|됐어|됐나|맞아|맞나|인가|일까|[?？]"
        + "|\\b(?:read|summari[sz]e|summary|tell\\s+me|show\\s+me|what|which|who|when|where|why|how|is\\s+there|are\\s+there|any|check|explain|translate|compare|look\\s+up|find|search|list)\\b");
    // A forbidden step asked for ("결제해 줘", "이 메일 삭제해 줘"); a mention ("결제 내역 보여 줘") is not one.
    private static final Pattern FORBIDDEN_ASK = Regex.unicode("(?i)(?:결제|결재|구매|구입|주문|송금|이체|출금|입금|납부|지불|충전|환전|매수|매도|대출|삭제|제거|초기화|탈퇴|해지|포맷|언인스톨|선물|대여|소장|후원|기부)"
        + PARTICLE + "(?:해|하|합|할)|(?<![가-힣])사\\s*(?:줘|주세요|줄래|주실래요)|지워|지우|비워|비우|없애|돈" + PARTICLE + "(?:보내|부쳐|부치)"
        + "|\\b(?:pay|buy|purchase|checkout|check\\s+out|transfer|withdraw|deposit|delete|remove|erase|uninstall|wipe|reset|donate|send\\s+money)\\b|\\border\\s+(?:it|this|that|a|an|the|me|some|now|online)\\b");
    // A generation request: make, create, draw, compose, write a text or code, or ask an AI. "그리고" is "and", never "draw".
    private static final Pattern GENERATE = Regex.unicode("(?i)만들(?!어진|어져|어졌)|(?:생성|제작|작곡|작사|편곡|작성|구현|개발|질문)" + PARTICLE + "(?:해|하)|그려|그리(?!고)|물어봐|물어보"
        + "|(?:글|코드|시|소설|가사|이야기|대본|스크립트|에세이|보고서|요약문|이메일|메일|답변|프롬프트|함수|프로그램)[^,\\n]{0,15}?(?:써|쓰)"
        + "|\\b(?:make|making|create|creating|generate|generating|compose|composing|draw|drawing|build|building|write|ask)\\b");
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

    private static final Pattern DELETE_WORDS = Regex.unicode("(?i)삭제(?:하기)?|지우기|지워|지울|지우|제거(?:하기)?|휴지통(?:으로|에)\\s*(?:이동|보내기?|버리기?)|\\b(?:delete|remove|erase|discard)\\b|\\bmove\\s+to\\s+(?:the\\s+)?(?:trash|bin)\\b");
    // What an item's deletion never covers: an account or a membership, the phone or its data, an app, a store emptied at once.
    private static final Pattern WIPE_WORDS = Regex.unicode("(?i)탈퇴|해지|계정|회원|초기화|포맷|언인스톨|비우|비워|모두|전체|일괄|\\b(?:account|reset|format|uninstall|empty|all|everything)\\b");
    private static final Pattern DELETE_ASK = Regex.unicode("(?i)(?:삭제|제거)\\s*(?:해|하|시켜|좀)|(?:삭제|제거)[\\s.!~]*$|지워|지우|\\b(?:delete|remove|erase)\\b");
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
        return !Regex.unicode("(?i)탈퇴|해지|계정|회원|초기화|포맷|언인스톨|\\b(?:account|reset|format|uninstall)\\b").matcher(userWords).find();
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
