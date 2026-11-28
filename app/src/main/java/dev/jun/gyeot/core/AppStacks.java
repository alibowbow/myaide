package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 들퍼레 홈 화면's stacks (준: "이 아이콘들을 혁신적으로 편리하게 사용, 정리할 수 있게(애플 맥북like)", "홈화면에 어플 바로가기
 * 아이콘들이 전체적으로 어질러져 있거든"): every app sorted by what it is for, the way a Mac's desktop stacks sort files, so a
 * home screen of thirty icons and folders becomes a dozen named piles. The app's own words first (package and name, Korean
 * apps included), then Android's category; the user can move an app to another stack. And 초성 search (ㅋㅋㅇ → 카카오톡).
 * Pure Java.
 */
public final class AppStacks {
    private AppStacks() {}
    public record Stack(String id, String name, String color) {}
    public static final List<Stack> STACKS = List.of(
        new Stack("money", "금융·투자", "#2dbe60"), new Stack("chat", "메신저·SNS", "#ffcd00"), new Stack("ai", "AI", "#7c5cff"),
        new Stack("map", "지도·이동", "#12b5a6"), new Stack("shop", "쇼핑·배달", "#ff6b4a"), new Stack("media", "영상·음악", "#ff3b5c"),
        new Stack("photo", "내 휴대폰", "#ff9f0a"), new Stack("google", "구글", "#4285f4"), new Stack("web", "웹·검색", "#3182f6"),
        new Stack("work", "업무", "#5e5ce6"), new Stack("game", "게임", "#af52de"), new Stack("etc", "기타", "#a2845e"));
    public static Stack stack(String id) { String now = current(id); for (Stack s : STACKS) if (s.id().equals(now)) return s; return STACKS.get(STACKS.size() - 1); }
    /**
     * A stack id as it is now: 도구 became part of 내 휴대폰 (준: "사진 카메라 분류는 안에 몇개 안 들어가", "전화 메세지나 내파일탐색기도
     * 같이 넣자", "내 휴대폰 기본앱들을 넣는거야"), so an app the user moved to 도구 is in 내 휴대폰. The id stays "photo" so moves kept
     * before still land there.
     */
    public static String current(String id) { return "tools".equals(id) ? "photo" : id == null ? "" : id; }

    // Android's ApplicationInfo categories (the numbers, so this stays pure Java).
    public static final int GAME = 0, AUDIO = 1, VIDEO = 2, IMAGE = 3, SOCIAL = 4, NEWS = 5, MAPS = 6, PRODUCTIVITY = 7;

    private record Rule(String stack, String[] words) {}
    // Checked in this order: AI before Google (Gemini), media and photos and maps before Google (YouTube, Photos, Maps).
    private static final List<Rule> RULES = List.of(
        new Rule("ai", new String[]{"openai", "chatgpt", "anthropic", "claude", "gemini", "android.apps.bard", "perplexity", "grok", "x.ai", "ai.x.", "zeta", "scatterlab", "wrtn", "뤼튼", "피클", "pickle", "copilot", "deepseek", "character.ai", "poe", "dev.jun.gyeot", "들퍼레", "뮤즈", "gpt", " ai"}),
        new Rule("money", new String[]{"bank", "upbit", "bithumb", "binance", "coinone", "korbit", "toss", "viva.republica", "kakaopay", "kbstar", "shinhan", "woori", "hanabank", "ibk", "nhbank", "nh pay", "nhpay", "nonghyup", "mpop", "samsungpop", "kiwoom", "miraeasset", "securit", "stock", "invest", "trading", "tradingview", "finance", "crypto", "coin", "wallet", "card", "insur",
            "은행", "증권", "페이", "카드", "코인", "투자", "주식", "업비트", "빗썸", "바이낸스", "토스", "보험", "pay"}),
        new Rule("chat", new String[]{"kakao.talk", "telegram", "discord", "whatsapp", "jp.naver.line", "slack", "kakaowork", "instagram", "facebook", "twitter", "com.x.", "threads", "barcelona", "tiktok", "musically", "ugc.trill", "band", "signal", "messenger", "snapchat", "reddit", "wechat",
            "카카오톡", "카카오워크", "텔레그램", "인스타", "스레드", "밴드"}),
        new Rule("map", new String[]{"map", "maps", "navi", "tmap", "taxi", "uber", "korail", "srt", "bus", "subway", "tesla", "socar", "airbnb", "agoda", "booking", "trip", "flight", "airline", "지도", "택시", "버스", "지하철", "기차", "내비", "항공", "여행"}),
        new Rule("shop", new String[]{"yogiyo", "baemin", "sampleapp", "coupang.mobile", "coupangeats", "11st", "gmarket", "auction", "musinsa", "kurly", "towneers", "danggeun", "ssg", "emart", "oliveyoung", "aliexpress", "temu", "kcar", "encar", "shop", "mall", "market",
            "요기요", "배민", "배달", "쿠팡", "쇼핑", "마켓", "당근", "중고", "무신사", "컬리"}),
        new Rule("media", new String[]{"youtube", "netflix", "disney", "tving", "wavve", "watcha", "coupangplay", "spotify", "melon", "genie", "flo", "music", "podcast", "twitch", "chzzk", "afreeca", "soop", "webtoon", "video", "player", "유튜브", "넷플릭스", "음악", "웹툰", "영상"}),
        new Rule("photo", new String[]{"camera", "gallery", "photo", "snow", "b612", "foodie", "lightroom", "picsart", "vsco", "capcut", "카메라", "갤러리", "사진"}),
        new Rule("web", new String[]{"chrome", "browser", "sbrowser", "com.nhn.android.search", "daum", "firefox", "opera", "msedge", "whale", "브라우저", "인터넷", "naver"}),
        new Rule("work", new String[]{"notion", "office", "teams", "zoom", "dropbox", "evernote", "onenote", "todoist", "outlook", "mail", "hwp", "pdf", "scanner", "메일", "문서", "오피스"}),
        new Rule("google", new String[]{"com.google.", "google"}),
        // The phone's own everyday apps (what 도구 held) go to 내 휴대폰 too, checked last as 도구 was (Notion, GoodNotes … stay put).
        new Rule("photo", new String[]{"clock", "calculator", "settings", "myfiles", "files", "voicenote", "recorder", "notes", "memo", "contacts", "dialer", "messaging", "calendar", "weather", "members", "galaxyapps", "vending", "health", "smartthings", "wearable", "fmm", "find",
            "시계", "계산기", "설정", "파일", "녹음", "메모", "노트", "전화", "연락처", "메시지", "캘린더", "날씨", "헬스", "스토어"}));


    /** The stack an app belongs to: its own words first, then Android's category, then 내 휴대폰 for the phone's own apps. */
    public static String of(String pkg, String label, int category, boolean game, boolean system) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT), l = label == null ? "" : " " + label.toLowerCase(Locale.ROOT);
        if (game || category == GAME) return "game";
        for (Rule r : RULES) for (String w : r.words()) {
            String word = w.toLowerCase(Locale.ROOT);
            // Short Latin words only as whole parts of the name or package ("pay", " ai"), not inside other words.
            boolean hit = word.length() <= 3 && word.matches("[ a-z]+") ? part(p, word.strip()) || part(l, word.strip()) : p.contains(word) || l.contains(word);
            if (hit) return r.stack();
        }
        switch (category) {
            case AUDIO: case VIDEO: return "media";
            case IMAGE: return "photo";
            case SOCIAL: return "chat";
            case MAPS: return "map";
            case PRODUCTIVITY: return "work";
            case NEWS: return "web";
            default: return system ? "photo" : "etc";
        }
    }
    private static boolean part(String text, String word) {
        for (String piece : text.split("[^a-z0-9]+")) if (piece.equals(word)) return true;
        return false;
    }

    private static final char[] INITIALS = {'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'};
    /** 카카오톡 → ㅋㅋㅇㅌ; other letters as they are, lowercased. */
    public static String initials(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : (text == null ? "" : text).toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) out.append(INITIALS[(c - 0xAC00) / 588]);
            else if (!Character.isWhitespace(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }
    /** How well a query finds an app (0 none; higher first): the name's start, a word's start, inside, its 초성, its package. */
    public static int score(String query, String label, String pkg) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (q.isEmpty()) return 0;
        String l = (label == null ? "" : label).toLowerCase(Locale.ROOT), flat = l.replace(" ", "");
        if (flat.startsWith(q)) return 100 - Math.min(40, flat.length() - q.length());
        for (String w : l.split("\\s+")) if (w.startsWith(q)) return 70;
        if (flat.contains(q)) return 55;
        if (q.chars().allMatch(ch -> ch >= 0x3131 && ch <= 0x314E)) { String i = initials(l); if (i.startsWith(q)) return 60; if (i.contains(q)) return 45; }
        if (pkg != null && q.length() >= 3 && pkg.toLowerCase(Locale.ROOT).contains(q)) return 20;
        return 0;
    }
    /** Defaults for a new dock, by package, among what is installed: the everyday ones first. */
    public static final List<String> DOCK = List.of("com.kakao.talk", "com.android.chrome", "com.google.android.youtube", "com.tradingview.tradingviewapp",
        "com.binance.dev", "com.dunamu.exchange", "org.telegram.messenger", "com.nhn.android.search", "dev.jun.gyeot");
    public static List<String> dock(List<String> installed, int most) {
        List<String> out = new ArrayList<>();
        for (String want : DOCK) for (String have : installed) if (out.size() < most && have.startsWith(want) && !out.contains(have)) out.add(have);
        return out;
    }
}
