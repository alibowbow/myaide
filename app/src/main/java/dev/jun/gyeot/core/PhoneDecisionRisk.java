package dev.jun.gyeot.core;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
/** The trusted local phone classifier behind AutomationGate's tiers; never an executor. */
public final class PhoneDecisionRisk {
    private PhoneDecisionRisk() {}
    /** Tier 0: screen and app-list reads, which run on the local read guards alone. */
    public static boolean localRead(String tool) { return "phone_screen".equals(tool) || "phone_apps".equals(tool); }
    /** Tier 1: an easy-to-undo phone_action ("action"), or its one repeat after no visible change ("retry"). */
    public static boolean routine(String stage, JSONObject evidence) {
        if (evidence == null) return false;
        String tool = evidence.optString("tool");
        // A repeat is routine only when the executor observed no visible change after the same
        // non-consequential navigation. The executor offers that retry at most once.
        if ("retry".equals(stage)) return evidence.optBoolean("repeat_without_visible_change") && routine("action", evidence);
        if (!"action".equals(stage) || !"phone_action".equals(tool) || evidence.optBoolean("input_withheld")) return false;
        String action = evidence.optString("action");
        // Leaving, waiting, opening an app, scrolling and swiping move around; they press nothing.
        if (Set.of("wait", "back", "home", "recents", "notifications", "scroll_down", "scroll_up", "swipe", "open_app").contains(action)) return true;
        if ("open_url".equals(action)) return evidence.optBoolean("local_public_read_url");
        JSONObject target = evidence.optJSONObject("target");
        if (target == null || target.optBoolean("missing") || target.optBoolean("password") || !evidence.optBoolean("snapshot_matches")) return false;
        if ("set_text".equals(action)) return target.optBoolean("editable") && evidence.has("proposed_input");
        // Enter in a search box runs a search; in a chat, comment or other field it may send, post or submit. The field Enter
        // reaches, as PhoneService found it (multi-line, view id), decides; the reading's description only when it did not.
        if ("press_enter".equals(action)) return target.optBoolean("editable")
            && (target.has("field_kind") ? "search".equals(target.optString("field_kind")) : evidence.optBoolean("search_field"));
        JSONObject current = evidence.optJSONObject("current");
        return "tap".equals(action) && navigationControl(target, current == null ? "" : current.optString("app"), current != null && current.optBoolean("composer"));
    }
    /** Navigation labels a tap may press without the user's words: back, close a menu, more, tabs and the like. */
    private static final Set<String> NAVIGATION = Set.of("back", "navigateup", "navigate up", "forward", "home", "menu", "moreoptions", "more options", "more", "refresh", "close",
        "뒤로", "뒤로가기", "이전화면", "앞으로", "홈", "메뉴", "새로고침", "목록", "더보기", "옵션더보기", "닫기", "메뉴닫기", "접기", "펼치기", "맨위로", "처음으로",
        "검색", "탐색", "피드", "쇼츠", "보관함", "라이브러리", "알림", "채팅", "채팅방", "친구", "프로필", "내정보", "인기", "최신", "전체", "뉴스", "지도", "오픈채팅", "홈화면", "설정",
        "재생", "일시정지", "전체화면", "search", "explore", "library", "notifications", "chats", "friends", "profile", "shorts", "subscriptions", "feed", "trending",
        "latest", "popular", "all", "news", "maps", "settings", "play", "pause", "fullscreen", "full screen");
    // Labels that can submit, pay, delete, change state, contact someone or confirm a dialog stay strict.
    private static final Pattern CONSEQUENTIAL_EN = Regex.unicode(
        "(?i)\\b(?:send|submit|post|publish|share|buy|purchase|order|checkout|pay|payment|donat\\w*|transfer|withdraw|deposit|delete|remove|erase|clear|reset|wipe|"
        + "uninstall|install|download|upload|subscri\\w*|unsubscri\\w*|follow|unfollow|like|block|report|accept|agree|allow|deny|grant|approve|confirm|ok|okay|yes|"
        + "continue|sign\\w*|log ?in|log ?out|call|dial|invite|join|leave|cancel|archive|mute|enable|disable|turn (?:on|off)|apply|save|update|edit|modify|change|"
        + "reply|comment|record|capture|start|stop|end|finish|done|book|reserve|rent|bid|vote|claim|redeem|add|connect|pair|unlock|lock|restore|backup|sync|password)\\b");
    // What a list row itself can do when pressed: the strict list without the plain category names (설정, 연결, 알림 …).
    private static final Pattern ROW_ACTION_KO = Pattern.compile(
        "(?:전송|보내|제출|게시|등록|올리기|업로드|공유|구매|구입|주문|결제|송금|이체|충전|후원|기부|삭제|지우|제거|초기화|비우기|설치|다운로드|구독|팔로우|좋아요|차단|신고|"
        + "수락|동의|허용|거부|거절|승인|확인|계속|로그아웃|가입|탈퇴|전화|통화|발신|초대|추가|참여|참가|나가기|취소|보관|음소거|적용|저장|답장|댓글|녹음|녹화|촬영|"
        + "시작|종료|해지|신청|예약|예매|발송|투표|응모|받기|담기|선물|대여|소장|납부|지불)");
    /** Whether a tap target names a forbidden operation. A list row is judged by its parts that look like controls, so a
     * message preview or a headline that mentions 결제 does not make opening the chat or article forbidden, and an order or
     * payment record's status chip and amount ("결제 완료", "₩99,000") do not make opening that record forbidden. A control of
     * its own (a button, a one-text row) is judged by its whole label, status words included: "결제 완료" pays. */
    public static boolean forbiddenTarget(JSONObject target) {
        if (target == null) return false;
        // The system UI (PhoneService.systemUiPart): nothing of a banking, card, pay or crypto app's notification is opened or
        // answered, and no such quick-settings tile (a wallet) is tapped. A notification row's texts are its content, never
        // controls: its own buttons are judged by their labels, as separate controls.
        String systemUi = target.optString("system_ui");
        if (!systemUi.isEmpty()) {
            if (AutomationGate.bankingApp(target.optString("notification_app"))) return true;
            if ("row".equals(systemUi)) return false;
            if ("tile".equals(systemUi) && AutomationGate.bankingApp(target.optString("label"))) return true;
        }
        // A tab or a read-navigation label whose only forbidden words are section names (KakaoTalk's 선물하기 tab, a 충전 tab,
        // 선물하기 내역) switches sections or opens a record. On a button, a switch or a field the words still forbid.
        if (sectionNavigation(target)) return false;
        JSONArray parts = target.optJSONArray("parts");
        if (target.optBoolean("list_item") && parts != null && parts.length() > 1) {
            boolean record = record(parts);
            for (int i = 0; i < parts.length(); i++) {
                String part = parts.optString(i);
                if (record && AutomationGate.amountText(part)) continue;
                if (AutomationGate.forbiddenRowPart(part) && !AutomationGate.sectionReadLabel(part)) return true;
            }
            return false;
        }
        return AutomationGate.forbiddenLabel(target.optString("label")) || AutomationGate.forbiddenLabel(target.optString("field_label"));
    }
    /** A tab (PhoneService: a tab view, a bottom navigation or tab-layout item, a node whose role description is a tab). */
    private static boolean tab(JSONObject target) { return target.optBoolean("tab") || "tab".equals(target.optString("role")); }
    /** A tab, or a read-navigation label, whose label names a forbidden step only through section names (선물하기, 충전, 대여, 소장,
     * 초기화); never a field, a switch or a check box. */
    private static boolean sectionNavigation(JSONObject target) {
        String label = target.optString("label");
        if (target.optBoolean("editable") || target.optBoolean("checkable") || Set.of("switch", "check", "input").contains(target.optString("role"))) return false;
        return tab(target) && AutomationGate.sectionName(label) || AutomationGate.sectionReadLabel(label);
    }
    /** An order, payment or delivery record in a list ("무선 이어폰 · 2026.11.01 · ₩99,000 · 결제 완료"): several texts, one of
     * them a status. Opening it reads the record. */
    private static boolean record(JSONArray parts) {
        if (parts == null || parts.length() < 2) return false;
        for (int i = 0; i < parts.length(); i++) if (AutomationGate.statusText(parts.optString(i))) return true;
        return false;
    }
    private static boolean rowAction(String part) {
        if (!AutomationGate.actionLike(part)) return false;
        return AutomationGate.kind(part) != AutomationGate.Kind.OTHER || ROW_ACTION_KO.matcher(part.replaceAll("\\s+", "")).find() || CONSEQUENTIAL_EN.matcher(part).find();
    }
    // A phone dialer, call log or in-call screen: a list row there calls back with one tap.
    private static final Pattern DIALER = Pattern.compile("(?i)(?:^|\\.)(?:dialer|incallui|dialertab)(?:\\.|$)");
    private static final Set<String> CALL_APPS = Set.of("com.android.phone", "com.android.server.telecom", "com.skt.prod.dialer", "com.skt.prod.phone");
    /** Whether a tap on this target places a call: any list row of a dialer or call log app (Google, Samsung, AOSP, T전화). */
    public static boolean callRow(JSONObject target, String app) {
        String name = app == null ? "" : app.strip().toLowerCase(Locale.ROOT);
        return target != null && target.optBoolean("list_item") && !target.optBoolean("editable") && (DIALER.matcher(name).find() || CALL_APPS.contains(name));
    }
    // Messaging and social apps, where a one-tap chip or a short reply row sends to a person.
    private static final Set<String> MESSAGING = Set.of("com.kakao.talk", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "com.whatsapp", "com.whatsapp.w4b", "jp.naver.line.android",
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.orca", "com.facebook.katana", "com.facebook.mlite", "com.discord", "com.slack",
        "com.microsoft.teams", "com.google.android.apps.dynamite", "com.viber.voip", "com.snapchat.android", "com.twitter.android", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill", "com.linkedin.android", "com.tencent.mm", "com.skype.raider", "com.nhn.android.band", "kr.co.vcnc.android.couple",
        "com.kakao.story", "com.reddit.frontpage", "us.zoom.videomeetings");
    /** Whether the app on screen is a messaging or social app (KakaoTalk, Messages, Telegram, WhatsApp, LINE, Instagram, Messenger …). */
    public static boolean messagingApp(String app) { return app != null && MESSAGING.contains(app.strip().toLowerCase(Locale.ROOT)); }
    /**
     * Whether a tap on this target sends a canned reply: a chip of a horizontally scrolling row (smart replies, sticker and
     * emoji suggestions) or a row whose only text is one short reply sentence ("네, 그렇게 할게요"), in a messaging or social app
     * or next to a message composer. In a messaging app with no composer on screen an image tile of a horizontal row (a
     * story or a profile in a carousel) is no chip and opens like any list item.
     */
    public static boolean replyChip(JSONObject target, String app, boolean composer) {
        if (target == null || !target.optBoolean("list_item") || target.optBoolean("editable")) return false;
        boolean messaging = messagingApp(app);
        if (!messaging && !composer) return false;
        if (target.optBoolean("horizontal") && (composer || !target.optBoolean("image"))) return true;
        JSONArray parts = target.optJSONArray("parts");
        String only = parts == null || parts.length() == 0 ? target.optString("label") : parts.length() == 1 ? parts.optString(0) : "";
        return AutomationGate.replySentence(only);
    }
    /**
     * The tap allowlist: a navigation label, a tab, a search field, or an item inside a list whose text names no consequential,
     * forbidden or money operation. Everything else (any other button, a switch, an unlabeled view) is consequential.
     * Target rebinding, protected-screen guards and the repeat guard still apply.
     */
    public static boolean navigationControl(JSONObject target) { return navigationControl(target, "", false); }
    /** As above on this app's screen: a dialer's list row calls, and a reply chip sends (composer: a message box is on screen). */
    public static boolean navigationControl(JSONObject target, String app, boolean composer) {
        if (target == null || target.optBoolean("missing") || target.optBoolean("password") || target.optBoolean("unlabeled")) return false;
        for (String flag : target.optString("flags").split(","))
            if (Set.of("disabled", "password").contains(flag.strip())) return false;
        // The system UI: a notification row opens its app like a list row; a notification's own controls are judged below like
        // any other. A quick-settings tile, a smart reply and anything else there are never routine.
        String systemUi = target.optString("system_ui");
        if ("row".equals(systemUi)) return true;
        if (!systemUi.isEmpty() && !"notification".equals(systemUi)) return false;
        // Tapping a text field (a search box, a message box) only focuses it; typing, Enter and send are judged on their own.
        if (target.optBoolean("editable") || target.optString("flags").contains("edit") || "input".equals(target.optString("role"))) return true;
        if (Set.of("switch", "check").contains(target.optString("role")) || target.optString("flags").contains("checked") || target.optBoolean("checkable")) return false;
        String label = target.optString("label").strip();
        if (label.isEmpty()) return false;
        String lower = label.toLowerCase(Locale.ROOT), compact = lower.replaceAll("\\s+", "");
        if (NAVIGATION.contains(lower) || NAVIGATION.contains(compact)) return true;
        // A label that opens a read screen ("결제 내역", "주문 조회", "Order history", "선물하기 내역") is navigation too (L1).
        if (AutomationGate.readNavigationLabel(label) || AutomationGate.sectionReadLabel(label)) return true;
        if (forbiddenTarget(target)) return false;
        JSONArray parts = target.optJSONArray("parts");
        if (parts == null || parts.length() == 0) parts = new JSONArray().put(label);
        boolean record = target.optBoolean("list_item") && record(parts);
        if (!record && AutomationGate.moneyText(label)) return false;
        // A tab switches sections; its section name (선물하기, 충전 …) is no action, any other action word in its label is.
        if (tab(target)) return !rowAction(AutomationGate.sectionName(label) ? AutomationGate.withoutSections(label) : label);
        if (!target.optBoolean("list_item") || callRow(target, app) || replyChip(target, app, composer)) return false;
        for (int i = 0; i < parts.length(); i++) {
            String part = parts.optString(i);
            // An order or payment record's status chip and amount are its text: opening the record reads it.
            if (record && (AutomationGate.statusText(part) || AutomationGate.amountText(part))) continue;
            if (AutomationGate.moneyText(part) || rowAction(part)) return false;
        }
        return true;
    }
    /** Completion needs goal- or answer-relevant text actually visible now, not just an app on screen. */
    public static boolean completionEvidence(JSONObject evidence) {
        if (evidence == null || evidence.optString("goal").isBlank()) return false;
        JSONObject current = evidence.optJSONObject("current");
        JSONArray matches = current == null ? null : current.optJSONArray("visible_matches");
        return current != null && current.optBoolean("observed") && !current.optString("snapshot_id").isBlank() && matches != null && matches.length() > 0;
    }
    /** Called only on the actual local argument. Unknown destinations stay consequential. */
    public static boolean publicReadUrl(String address) {
        if (address == null || address.length() > 2048) return false;
        try {
            String value = address.strip(); if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) value = "https://" + value;
            java.net.URI uri = new java.net.URI(value);
            String host = uri.getHost(), path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            if (path != null && !path.equals(uri.normalize().getRawPath())) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (!host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}") || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".localhost") || host.equals("localhost")) return false;
            if (path != null && path.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(transfer|delete|remove|pay|buy|purchase|checkout|submit|send|logout|login|auth|token|invite|join|confirm|accept|unsubscribe|cancel).*")) return false;
            return path == null || path.isEmpty() || path.equals("/") || path.matches("/(?:news|articles?|wiki|docs|help)(?:/[A-Za-z0-9_.~-]+)*/?");
        } catch (Exception invalid) { return false; }
    }

}
