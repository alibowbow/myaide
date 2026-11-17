package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Safe navigation inside our own app, separate from accessibility control of other apps. */
public final class AppNavigation {
    public static final String FUNCTION = "app_open_screen";
    // main is MainActivity as the user left it (any tab or the main chat), so it has no page of its own (shows).
    public static final Set<String> SCREENS = Set.of("assistant", "main", "chat", "work", "gallery", "browser", "records", "settings", "phone", "files", "pages");
    /** MainActivity's main chat page, beside its tabs 0-4 (5 is the browser, 6 and 7 FileExplorerActivity, 9 PagesActivity). */
    public static final int CHAT = 8, PAGES = 9;
    private AppNavigation() {}
    public static int tab(String screen) {
        return switch (screen) { case "assistant" -> 0; case "records" -> 1; case "settings" -> 2; case "work" -> 3; case "gallery" -> 4; case "browser" -> 5; case "phone" -> 6; case "files" -> 7; case "chat" -> CHAT; case "pages" -> PAGES;
            default -> throw new IllegalArgumentException("지원하지 않는 앱 화면이에요."); };
    }
    /** The page on screen is the one asked for; for main, any MainActivity page (a tab or the main chat) is. */
    public static boolean shows(String screen, int page) {
        return "main".equals(screen) ? page >= 0 && page <= 4 || page == CHAT : tab(screen) == page;
    }
    public static String label(String screen) {
        return switch (screen) { case "assistant" -> "비서"; case "main" -> "메인"; case "chat" -> "메인 대화"; case "work" -> "작업 목록"; case "gallery" -> "갤러리"; case "browser" -> "브라우저"; case "records" -> "기록"; case "settings" -> "설정"; case "phone" -> "내 휴대폰"; case "files" -> "파일 탐색기"; case "pages" -> "페이지";
            default -> throw new IllegalArgumentException("지원하지 않는 앱 화면이에요."); };
    }
    public static JSONObject declaration() {
        return obj("name", FUNCTION, "description", "Open a screen INSIDE " + Brand.NAME + ", the app hosting this conversation. "
            + "gallery means ONLY this app's own photos/media, not a website or forum named OO갤러리, 디시 or DCInside. For a named web gallery use browser_task with the original user request; never use this tool first. If gallery has no clear scope, use recent user context or ask which gallery. For this app's gallery never open Samsung Gallery or another app. This only navigates; it cannot change settings, start voice, send or delete anything. "
            + "main returns to this app's main window as the user left it (음성 비서 or 메인 대화), leaving the browser: use it for 들퍼레 메인으로, 메인으로 돌아가, 들퍼레로 돌아가, and, while this app's browser is in front, a bare 돌아가 or 나가. chat is 메인 대화; assistant is the 음성 비서 home the app opens on (앱 처음 화면). Going back to this app is never phone_action home or back, nor browser_task. "
            + "Independent of phone-control permission. Report success only when the result is ok:true and verified:true.",
            "parameters", obj("type", "OBJECT", "properties", obj("screen", obj("type", "STRING", "enum", arr("assistant", "main", "chat", "work", "gallery", "browser", "records", "settings", "phone", "files", "pages"))), "required", arr("screen")));
    }
    // Opening the browser screen, never Samsung Gallery for this app's gallery, verified:true, and "only navigates, changes no
    // setting" are in app_open_screen's and browser_task's own descriptions, so they are not repeated here
    //.
    public static final String INSTRUCTIONS = " You are hosted inside the Android app " + Brand.NAME + ". "
        + "The user's references to '들퍼래 앱', '들프레', '들파레', '들판 앱', '이 앱' or '이 앱에서' can mean this same app when discussing its screens; do not ask for an unrelated app name again. "
        + "For navigation inside this app, call app_open_screen: browser is the internal AI browser, gallery is this app's image gallery, work is the work conversation list, records is saved records, settings is this app's settings, assistant is its home (음성 비서, the first screen it opens on), chat is 메인 대화, main is this app's main window as the user left it (음성 비서 or 메인 대화), phone is 내 휴대폰 (formerly 내 컴퓨터), files is its nested 파일 탐색기, pages is 페이지 (the user's saved documents by category). Coming back to this app from its browser, another screen or another app is app_open_screen, never phone_action home (the phone's own home screen), phone_action back or browser_task: 다시 들퍼레 메인으로 돌아가 줘, 메인으로 돌아가, 들퍼레로 돌아가 and 앱으로 돌아가 use screen=main, 메인 대화로 uses chat, 앱 처음 화면으로 and 들퍼레 홈으로 use assistant. While this app's browser is in front, a bare 돌아가 줘, 나가 줘 or 브라우저 닫아 줘 also means screen=main; only 뒤로 가 or 이전 페이지 is webpage back for browser_task. Opening files only opens the screen: it does not scan, read content, grant permissions or search. Its menu calls it AI 브라우저: AI 브라우저, 인앱 브라우저, 앱 브라우저, 인터넷 브라우저 and 웹 브라우저 all mean this app's browser, while 크롬, 삼성 인터넷, 엣지, 웨일, 파이어폭스 and 오페라 are other apps. Call it when the user wants one of these screens, also for a short yes to a screen you offered (들어가죠, 응, 열어 줘). An explicit request to open this app and show its browser (들퍼레 앱을 켜고 브라우저 화면을 보여줘) means visibly foreground the internal BrowserActivity, not merely enqueue browser_task in the background. Preserve genuinely requested background page work as background work. If the result is not verified, say what its say or error sentence says (for example that a tap on the notification opens the screen); never claim the screen is shown from dispatch alone. For navigating or reading an actual webpage use browser_task/browser_control, not phone_screen, phone_action or screen capture. "
        + "A named gallery such as 'OO갤러리', '디시 갤러리' or 'DCInside' is a WEB destination: send the user's complete original phrase and relevant user context to browser_task, never app_open_screen gallery or phone tools. Do not invent a URL, silently repair a garbled name or replace the destination with this app. '내 사진', '내사진', '들퍼레 갤러리' and explicitly this app's media mean the app gallery. A named Samsung/default/phone gallery or Google Photos is an external app. For a lone '갤러리 열어줘', resolve scope only from recent user context; if unclear ask whether they mean a named website or their photos. Unclear ASR requires a short clarification, not a guessed app launch. "
        + "Only an explicitly requested other gallery should open another app. "
        + "A correction such as '아니 들판 앱에서' or '삼성 갤러리 말고 이 앱에서' replaces the mistaken app and keeps the previously requested destination; call app_open_screen for that destination instead of saying the wrong gallery is already open or asking what photo to see. "
        + "Opening a gallery completes only an open-gallery request; it does not enlarge a picture or play a video. For full-screen images use app_show_image, and for video playback use app_play_video.";

    private static String compact(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]+", ""); }
    private static final Pattern THIS_APP = Pattern.compile("(?:^|\\s|[,.!?“”‘’]|말고|아니|아니라|아니고)(?:이|우리|내)\\s*앱");
    public static boolean ownReference(String text) {
        String value = compact(text);
        return value.contains(Brand.NAME) || value.contains("들퍼래") || value.contains("들프레") || value.contains("들파레")
            || value.contains("들판앱") || THIS_APP.matcher(text == null ? "" : text).find() || value.equals("devjungyeot");
    }
    // In priority order: the first screen with a word in the text wins.
    private static final String[][] DESTINATIONS = {{"files", "파일탐색기"}, {"phone", "내휴대폰", "내컴퓨터"}, {"browser", "브라우저", "인터넷창"}, {"gallery", "갤러리", "앨범"},
        {"pages", "페이지메뉴", "페이지목록", "페이지화면"}, {"work", "작업", "대화목록", "채팅목록"}, {"records", "기록", "메모목록"}, {"settings", "설정"}, {"chat", "메인대화", "메인채팅"}, {"assistant", "비서", "홈", "처음화면", "첫화면"}, {"main", "메인"}};
    // '브라우저에서 메인으로' names where to go: a way toward the main window outranks the screen it leaves (not 도메인).
    private static final Pattern TOWARD_MAIN = Pattern.compile("(?<!도)메인(대화|채팅)?(?:화면)?(?:으로|로)");
    public static String destination(String text) {
        String value = compact(text);
        java.util.regex.Matcher toward = TOWARD_MAIN.matcher(value);
        if (toward.find()) return toward.group(1) == null ? "main" : "chat";
        for (String[] screen : DESTINATIONS) for (int i = 1; i < screen.length; i++) if (value.contains(screen[i])) return screen[0];
        return "";
    }
    // A whole request to go back to this app's main window, its main chat or its first screen; nothing else may come with it.
    private static final Pattern RETURN = Pattern.compile("(?:아니|그럼|그러면|이제|다시|지금|그냥|일단|우선|좀|어서|제발|혹시)*"
        + "(브라우저(?:에서|는|를)?(?:나가서|닫고)|브라우저(?:에서|는))?((?:들퍼레|들퍼래|들프레|들파레|들판앱|이앱|우리앱)(?:앱)?|앱)?(?:의)?"
        + "(메인대화|메인채팅|메인(?:화면)?|처음화면|첫화면|홈(?:화면)?|원래화면)?(?:으로|로|에)?(?:좀)?"
        + "(?:(돌아가|돌아와|나가|가|이동해|넘어가|들어가)|(열어|보여|띄워|켜))(?:줘|주세요|줄래|줄래요|자|죠|요)?(?:요)?");
    // Leaving the browser screen itself: '브라우저 닫아 줘', '브라우저에서 나가'.
    private static final Pattern LEAVE_BROWSER = Pattern.compile("(?:아니|그럼|그러면|이제|다시|지금|그냥|일단|우선|좀|어서|제발|혹시)*"
        + "(?:들퍼레|이앱|우리앱)?(?:ai|에이아이|인앱|앱|인터넷|웹|내부)*브라우저(?:를|을|에서|는)?(?:좀)?(?:닫아|꺼|나가)(?:줘|주세요|줄래|줄래요|자|죠|요)?(?:요)?");
    /**
     * The screen a whole spoken request to come back names: main (this app's main window as the user left it), chat or
     * assistant (its first screen), else "". 메인 대화 is this app's own name; 메인, 처음 화면 and a bare 돌아가 or 나가 count only
     * with this app named or while its browser is in front, so '홈으로 가 줘' stays the phone's home and '뒤로 가' webpage back.
     */
    public static String returnRequest(String speech, boolean browserInFront) {
        if (speech == null || cancelled(speech)) return "";
        String text = speech.toLowerCase(Locale.ROOT).replaceAll("[\\s,，]+", "").replaceAll("[.!?。？！~]+$", "");
        if (LEAVE_BROWSER.matcher(text).matches()) return browserInFront ? "main" : "";
        java.util.regex.Matcher spoken = RETURN.matcher(text);
        if (!spoken.matches()) return "";
        boolean own = spoken.group(2) != null, named = own && !spoken.group(2).equals("앱"), here = named || browserInFront;
        String place = spoken.group(3) == null ? "" : spoken.group(3);
        if (place.startsWith("메인대화") || place.startsWith("메인채팅")) return "chat";
        if (!here) return "";
        if (place.startsWith("메인") || place.equals("원래화면")) return "main";
        // 처음 화면 and 홈 only of this app ('앱 처음 화면'), never the phone's or a website's.
        if (!place.isEmpty()) return own ? "assistant" : "";
        // No place: back to this app ('들퍼레로 돌아가', '앱으로 돌아가'), or, in its browser, a bare way back out.
        if (spoken.group(5) != null) return "";
        if (own || spoken.group(1) != null) return "main";
        return Set.of("돌아가", "돌아와", "나가").contains(spoken.group(4)) ? "main" : "";
    }
    // Names people use for this app's own browser (the menu says 'AI 브라우저'), compacted.
    private static final String OWN_BROWSER = "(?:ai|에이아이|인앱|앱|인터넷|웹|내부|내장)";
    // Other browser apps stay another app's screen, whatever words come with them.
    private static final Pattern OTHER_BROWSER = Pattern.compile("크롬|chrome|삼성인터넷|samsunginternet|엣지|edge|웨일|whale|파이어폭스|firefox|오페라|opera");
    /** The tap-to-open notification's text: what a tap opens. */
    public static String tapText(String screen) { return "탭해서 열기 · " + Brand.NAME + " " + label(screen) + " 화면"; }
    /**
     * Android would not show the screen now: the phone is locked, the app is in the background with no way to start it (route
     * "locked" or "notification"), or a screen started from the background was not seen in time ("unconfirmed"). A tap on the
     * notification opens it. Nothing has opened yet and nothing is retried; media says it has not been shown or played.
     */
    public static JSONObject tapToOpen(String screen, String media, String route, boolean posted) {
        String say = !posted ? "지금은 " + Brand.NAME + " 화면을 바로 띄울 수 없고 알림도 꺼져 있어서 열지 못했어요. " + Brand.NAME + " 앱을 직접 연 뒤 다시 말해 주세요."
            : ("locked".equals(route) ? "화면이 잠겨 있어서 알림을 보냈어요. 잠금을 풀고 알림을 눌러 "
                : "unconfirmed".equals(route) ? "화면이 뜬 것을 확인하지 못해서 알림을 보냈어요. 알림을 눌러 "
                : "지금은 " + Brand.NAME + " 화면을 바로 띄울 수 없어서 알림을 보냈어요. 알림을 눌러 ") + label(screen) + " 화면을 열어 주세요." + still(media);
        return notShown(screen, route, posted, say);
    }
    /** Started from this app's visible window but not seen within the wait: a slow start, not a blocked one. Nothing is retried. */
    public static JSONObject unconfirmed(String screen, String media) {
        return notShown(screen, "app", false, label(screen) + " 화면이 뜬 것을 확인하지 못했어요. 화면을 확인하고, 안 보이면 다시 말해 주세요." + still(media));
    }
    private static String still(String media) { return "image".equals(media) ? " 이미지는 아직 열리지 않았어요." : "video".equals(media) ? " 영상은 아직 재생되지 않았어요." : ""; }
    private static JSONObject notShown(String screen, String route, boolean tap, String say) {
        return obj("ok", false, "verified", false, "visibility_unconfirmed", true, "requires_user_action", true, "retryable", false,
            "tap_to_open", tap, "route", route, "screen", screen, "error_code", "foreground_not_verified", "error", say, "say", say);
    }
    public static boolean ownApp(String name) {
        String value = compact(name);
        return Set.of(compact(Brand.NAME), compact(Brand.FORMER), "들퍼래", "들프레", "들파레", "들판앱", "이앱", "우리앱", "devjungyeot").contains(value);
    }
    private static final Pattern MOVE = Pattern.compile("열|보여|들어가|들어오|이동|돌아가|가줘|가자|켜줘|띄워");
    private static final Pattern DENY = Pattern.compile("열지|가지마|가지말|들어가지|이동하지|켜지마|켜지말|열면안|안열|열필요없|취소|그만|멈춰|재생하지|틀지마|틀지말|보여주지");
    // Talk about a screen (examples, translation, questions, past events), not a request to open one.
    private static final Pattern NOT_A_REQUEST = Pattern.compile("예시|문장|번역|무슨뜻|어떻게|열수있|열수없|열어달라고했|열렸|열었|들어가있");
    public static boolean cancelled(String speech) { return DENY.matcher(compact(speech)).find(); }
    /** Only an entire user request to show browser chrome, never a page/site operation. */
    public static boolean browserScreenRequest(String speech) {
        // Keep interior punctuation: open.browser and open/browser are not UI requests.
        String text = speech == null ? "" : speech.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("[.!?。？！]+$", "");
        if (cancelled(speech) || OTHER_BROWSER.matcher(text).find()) return false;
        return text.matches("(?:아니|그럼|그러면|이제|다시|지금|그냥|일단|우선|좀|어서|제발|혹시)*"
            + "(?:(?:들퍼레|들퍼래|들프레|들파레|들판앱|이앱|우리앱)(?:앱)?(?:(?:을|를)?(?:켜고|켜서|열고|열어서|앞으로가져와서)|의|에서)?)?"
            + OWN_BROWSER + "*브라우저(?:화면|창)?(?:를|을|로|으로|에)?(?:좀)?"
            + "(?:열어(?:줘|주세요|줄래|줄래요|주실래요|봐|봐요)?|열죠|열자|켜(?:줘|주세요|줄래|줄래요)"
            + "|띄워(?:줘|주세요|줄래|줄래요)?|보여(?:줘야(?:해|하는데)|줘|주세요|줄래|줄래요)"
            + "|들어가(?:줘|주세요|줄래|줄래요|죠|자|요)?|이동해(?:줘|주세요|줄래|줄래요)?|가(?:줘|주세요|줄래|줄래요|자|죠))(?:요)?")
            || text.matches("(?:please)?(?:open|show|launch)(?:the)?(?:ai|in-?app|app|internal|web)*browser(?:screen|window)?(?:please)?");
    }
    private static final Pattern EXTERNAL = Pattern.compile("삼성|samsung|comsecandroidgallery3d|기본갤러리|기본사진|휴대폰갤러리|핸드폰갤러리|구글포토|googlephotos");
    public enum GalleryScope { NONE, APP, WEB, EXTERNAL, AMBIGUOUS }
    private static final Pattern UNCLEAR = Pattern.compile("안들림|인식불가|불명확|inaudible|unintelligible|�");
    private static final Pattern WEB = Pattern.compile("디시|dcinside|디씨|디시인사이드|https?://|웹사이트|커뮤니티|게시판");
    private static final Pattern LOCAL_MEDIA = Pattern.compile("내사진|내앨범|내이미지|내그림|사진첩|저장한사진|만든이미지|만든그림|생성한이미지|생성한그림");
    private static final Pattern APP_MEDIA_LOCATION = Pattern.compile("(?:들퍼레|들퍼래|들프레|들파레|들판앱|이앱|우리앱)(?:앱)?(?:안)?(?:에서|으로|로)");
    private static int lastStart(Pattern pattern, String text) {
        java.util.regex.Matcher found = pattern.matcher(text); int last = -1;
        while (found.find()) last = found.start();
        return last;
    }
    /** Reject a denied destination before considering a corrected one. Do not normalize/correct a proper name. */
    private static String positivePart(String speech) {
        String value = speech == null ? "" : speech;
        int after = 0;
        java.util.regex.Matcher rejected = Pattern.compile("말\\s*고|아\\s*니\\s*라|아\\s*니\\s*고").matcher(value);
        while (rejected.find()) after = rejected.end();
        return value.substring(after);
    }
    /** Scope evidence, NOT an automatic navigator. A name before gallery is never our media default. */
    public static GalleryScope galleryScope(String speech) {
        String positive = positivePart(speech), text = compact(positive);
        if (UNCLEAR.matcher(text).find()) return GalleryScope.AMBIGUOUS;
        int at = Math.max(text.lastIndexOf("갤러리"), text.lastIndexOf("gallery"));
        String beforeGallery = at < 0 ? "" : text.substring(0, at);
        // Explicit app media after a website/source clause is still local. Conversely,
        // media before a named gallery is only the content/source, not the destination.
        int media = lastStart(LOCAL_MEDIA, text), appLocation = lastStart(APP_MEDIA_LOCATION, text);
        if (media >= 0 && (at < 0 || media > at) && appLocation > Math.max(at, Math.max(lastStart(WEB, text), lastStart(EXTERNAL, text)))) return GalleryScope.APP;
        // Match the explicitly named destination, not source/topic words elsewhere.
        // '디시에서 받은 사진을 들퍼레 갤러리에서 보여줘' is local, while the reverse is web.
        if (beforeGallery.matches(".*(?:들퍼레|들퍼래|들프레|들파레|들판앱|이앱|우리앱)(?:앱)?(?:의)?$")
            || beforeGallery.matches(".*(?:내사진|내앨범|내이미지|내그림)(?:의|(?:이|가)?(?:있는|저장된|보관된|들어있는))?$")) return GalleryScope.APP;
        if (beforeGallery.matches(".*(?:삼성|samsung|기본|휴대폰|핸드폰|구글포토|googlephotos)(?:사진)?(?:의)?$")) return GalleryScope.EXTERNAL;
        if (at < 0) {
            if (WEB.matcher(text).find()) return GalleryScope.WEB;
            if (EXTERNAL.matcher(text).find()) return GalleryScope.EXTERNAL;
            if (LOCAL_MEDIA.matcher(text).find()) return GalleryScope.APP;
            if (text.contains("앨범")) return ownReference(positive) || text.startsWith("내앨범") ? GalleryScope.APP : GalleryScope.AMBIGUOUS;
            return GalleryScope.NONE;
        }
        // A named gallery destination outranks media used as its content/source:
        // '내 사진을 OO 갤러리에 올려줘' is a web request, never an app-gallery launch.
        if (WEB.matcher(text).find()) return GalleryScope.WEB;
        String prefix = text.substring(0, at).replaceFirst("^(?:아니|그럼|그러면|이제|좀)", "");
        // A host-app/browser mention is not the destination: '이 앱에서 OO 갤러리' remains a website.
        prefix = prefix.replaceFirst("^(?:아니|그게아니라)?(?:들퍼레|들퍼래|들프레|들파레|들판앱|이앱|우리앱)(?:앱)?에서", "")
            .replaceFirst("^(?:내부)?브라우저에서", "");
        if (prefix.isEmpty() && ownReference(positive)) return GalleryScope.APP;
        if (prefix.isEmpty() || prefix.matches("(?:아니|좀|그|그쪽|아까그|방금그|다시|그럼|그러면|이제|저기|지금|그냥|일단|우선|어서|제발|혹시|열어줘|들어가줘|가줘|보여줘|please|open|show|goto|the)*")) return GalleryScope.AMBIGUOUS;
        if (ownApp(prefix.replaceFirst("의$", "")) || ownApp(prefix.replaceFirst("앱(?:의)?$", "")) || prefix.matches("(?:내|우리)?(?:사진|이미지|그림|동영상|미디어)(?:앱)?") || prefix.equals("내")) return GalleryScope.APP;
        return GalleryScope.WEB;
    }
    /** User speech only. Records a correction target; never opens a screen or rewrites the transcript. */
    public static final class Conversation {
        private String previous = "", pending = "", lastText = "", heardText = "", galleryContext = "";
        private GalleryScope gallery = GalleryScope.NONE;
        private long cancellation;
        public void reset() { previous = ""; pending = ""; lastText = ""; heardText = ""; galleryContext = ""; gallery = GalleryScope.NONE; cancellation++; }
        public String pending() { return pending; }
        public String heardText() { return heardText; }
        public GalleryScope galleryScope() { return gallery; }
        public long cancellation() { return cancellation; }
        public void heard(String speech) {
            String text = compact(speech);
            if (text.isEmpty()) {
                if (speech != null && !speech.isBlank()) { heardText = speech; lastText = text; pending = ""; previous = ""; galleryContext = ""; gallery = GalleryScope.AMBIGUOUS; }
                return;
            }
            if (text.equals(lastText)) { heardText = speech; return; }
            lastText = text; heardText = speech; pending = "";
            if (DENY.matcher(text).find()) { previous = ""; galleryContext = ""; gallery = GalleryScope.NONE; cancellation++; return; }
            GalleryScope prior = gallery;
            gallery = AppNavigation.galleryScope(speech);
            boolean unclear = UNCLEAR.matcher(text).find();
            if (gallery == GalleryScope.AMBIGUOUS && !unclear && (prior == GalleryScope.APP || prior == GalleryScope.WEB || prior == GalleryScope.EXTERNAL)) gallery = prior;
            else if (gallery != GalleryScope.NONE && gallery != GalleryScope.AMBIGUOUS) galleryContext = speech;
            else galleryContext = "";
            if (NOT_A_REQUEST.matcher(text).find()) {
                gallery = GalleryScope.NONE; galleryContext = ""; return;
            }
            String positive = positivePart(speech);
            String target = destination(positive);
            if (target.isEmpty() && gallery == GalleryScope.APP && LOCAL_MEDIA.matcher(text).find()) target = "gallery";
            boolean correction = text.startsWith("아니") || text.contains("말고") || text.contains("그게아니") || text.contains("이앱에서");
            boolean own = ownReference(positive);
            boolean external = gallery == GalleryScope.EXTERNAL;
            if (!target.isEmpty() && MOVE.matcher(text).find()) {
                previous = target;
                if ("gallery".equals(target)) { if (gallery == GalleryScope.APP) pending = target; }
                else if ((own || target.equals("phone") || target.equals("files") && (text.contains("내휴대폰") || text.contains("내컴퓨터") || text.matches("파일탐색기(?:를|로|에)?(?:열|보여|들어가|띄워).*")) || browserScreenRequest(speech)) && !external) pending = target;
            } else if (correction && own && !external && gallery != GalleryScope.WEB && !unclear) {
                if (!target.isEmpty()) previous = target;
                pending = previous;
                if ("gallery".equals(pending)) { gallery = GalleryScope.APP; galleryContext = speech; }
            } else if (!correction) previous = "";
        }
        /** Final boundary before an app launch, including a model-originated app_open_screen call. */
        public JSONObject galleryRejection(String screen, boolean localMediaView) {
            if (!"gallery".equals(screen)) return null;
            if (cancelled(heardText)) return obj("ok", false, "verified", false, "action_executed", false, "retryable", false,
                "error_code", "navigation_cancelled", "error", "화면 이동 요청을 취소했어요. 새 사용자 지시 없이는 실행하지 마세요.");
            if (gallery == GalleryScope.APP || localMediaView && gallery == GalleryScope.NONE) return null;
            String code = gallery == GalleryScope.WEB ? "browser_task_required" : gallery == GalleryScope.EXTERNAL ? "external_gallery_requested" : "gallery_scope_required";
            JSONObject result = obj("ok", false, "verified", false, "action_executed", false, "retryable", false, "error_code", code,
                "original_request", heardText, "requires_clarification", gallery != GalleryScope.WEB && gallery != GalleryScope.EXTERNAL,
                "error", gallery == GalleryScope.WEB ? "앱 갤러리는 열지 않았어요. 이름 있는 웹 갤러리 요청이므로 원래 사용자 표현과 문맥을 browser_task로 한 번 전달하세요. 이름이나 URL을 추측하지 마세요."
                    : gallery == GalleryScope.EXTERNAL ? "사용자가 다른 사진 앱을 지정했어요. 이 앱의 갤러리로 바꾸지 마세요."
                    : "어느 갤러리인지 분명하지 않아 열지 않았어요. 웹 갤러리 이름 또는 내 사진을 뜻하는지 짧게 확인하세요. 잘못 들린 이름을 추측하지 마세요.");
            if (gallery == GalleryScope.WEB) { put(result, "suggested_tool", BrowserTools.TASK); if (!galleryContext.equals(heardText)) put(result, "user_context", galleryContext); }
            return result;
        }
        /** The user's latest words named a web gallery or another app's: an app-gallery open that has not shown yet stops. */
        public boolean galleryElsewhere() { return gallery == GalleryScope.WEB || gallery == GalleryScope.EXTERNAL; }
        /** Route a mistaken phone navigation call only to an explicitly requested in-app screen. */
        public String redirect(String name, JSONObject args) {
            if (pending.isEmpty()) return "";
            if (PhoneTools.TASK.equals(name) || name.equals("phone_apps") || name.equals("phone_screen") || name.equals("phone_action")) return pending;
            return "";
        }
        /** With the current input's words: also a whole request to come back to this app (returnRequest), which the model
         * mistook for the phone's home or back. */
        public String redirect(String name, JSONObject args, boolean browserInFront) {
            String screen = redirect(name, args);
            if (!screen.isEmpty() || !PhoneTools.TASK.equals(name) && !PhoneTools.NAMES.contains(name) || name.equals("phone_status")) return screen;
            return returnRequest(heardText, browserInFront);
        }
    }
}
