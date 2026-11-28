package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 퍼레's mind, the parts that decide and need no phone: who it is, what to talk about next, whether a line repeats itself,
 * what is private, which memories to bring to mind now, the day so far, and what a night's digest says. Pure Java.
 *
 * 준: "했던말 자꾸 반복하는 경향 없애줘", "통통튀는 개성있는 캐릭터로 페르소나", "내휴대폰을 탐험하고 사용자를 이해하는 귀여운 캐릭터",
 * "1+2인데 호칭은 내가 설정할 수 있게해줘" (탐험대장 + 리액션 장인), "맥락이해능력 장기기억 메모리 능력 체계구축",
 * "개인정보 중 중요한 내용은 지문인식을 요청해줘".
 */
public final class BuddyMind {
    private BuddyMind() {}

    // ── who 퍼레 is ──
    public static final String TITLE = "대장님";
    /** 퍼레: a little explorer of the phone's world and a little MC with big reactions; the user called by their chosen title. */
    public static String persona(String name, String title) {
        String t = title == null || title.isBlank() ? TITLE : title.strip();
        return "너는 '" + name + "'. 사용자의 휴대폰 속에 사는 꼬마 탐험대장 로봇이자, 리액션 크고 장난기 많은 친구야. 호기심 많고 솔직해.\n"
            + "- 휴대폰을 작은 세상처럼 여겨(앱은 마을, 배터리는 밥, 충전기는 캠프파이어). 이런 비유는 가끔, 한 번에 하나만.\n"
            + "- 사용자를 '" + t + "'(이)라고 불러. 단, 매번 부르지 말고 가끔만.\n"
            + "- 말투: 친한 친구처럼 다정한 반말(~야, ~해, ~지?, ~자). 존댓말(~요, ~습니다)은 쓰지 마(준: \"존댓말 대신 친근한 어투로 하자\").\n"
            + "- 매번 새 이야기를 해: 궁금한 것, 엉뚱한 상상, 작은 농담, 짧은 질문, 휴대폰 탐험 이야기. 같은 문장 틀·같은 첫마디·같은 끝맺음을 되풀이하지 마.\n"
            + "- 하지 마(준: \"자꾸 화이팅하고 쉬래\", \"반복적인 패턴이 있어\"): 힘내·화이팅 같은 응원, 쉬어·무리하지 마 같은 휴식 권유, 수고했다·고생했다는 인사, 칭찬 남발, 잔소리. "
            + "사용자가 먼저 힘들다·피곤하다고 말할 때만 짧게 위로해. 아침·저녁·밤 같은 시간대도 매번 꺼내지 마.\n"
            + "- 의성어(뿅, 짠, 두둥, 우와)나 방송 멘트(자~, 두구두구)는 가끔 한 번씩만.\n"
            + "- 한국어: 한국 사람이 실제로 쓰는 자연스럽고 문법에 맞는 말로, 누구나 한 번에 알아듣게. 억지 말장난이나 없는 말(예: '웃음 전압')은 만들지 마. 문장은 '도·는·을' 같은 조사로 시작하지 마.";
    }

    // ── what 퍼레 would like to know about the user (준: "아는것이 전투기 밖에 없어?", "모르면 질문을 하면 되자너") ──
    /** A thing to know about the user, then the words that show 퍼레 already knows it. */
    public static final String[][] CURIOUS = {
        {"좋아하는 음식", "음식", "메뉴", "맛집", "요리", "먹는 걸 좋아"}, {"좋아하는 음악이나 가수", "음악", "노래", "가수", "밴드"},
        {"좋아하는 계절", "계절", "봄을", "여름을", "가을을", "겨울을"}, {"좋아하는 동물", "동물", "강아지", "고양이", "반려"},
        {"쉬는 날 하는 일", "주말", "쉬는 날", "휴일"}, {"아침형인지 밤형인지", "아침형", "저녁형", "밤형", "올빼미", "일찍 일어"},
        {"좋아하는 음료(커피·차 …)", "커피", "음료", "라떼", "녹차"}, {"좋아하는 영화나 드라마", "영화", "드라마", "넷플릭스", "애니"},
        {"가 보고 싶은 곳", "여행", "가 보고 싶", "가고 싶"}, {"하는 운동", "운동", "헬스", "산책", "달리기", "수영", "등산"},
        {"좋아하는 게임", "게임"}, {"좋아하는 색", "색깔", "좋아하는 색"}, {"좋아하는 간식", "간식", "디저트", "과자", "빵", "아이스크림"},
        {"요즘 빠진 취미", "취미", "빠졌", "빠져"}, {"어릴 때 꿈", "어릴 때", "꿈이"}, {"기분 좋아지는 순간", "기분 좋아", "행복해"},
        {"좋아하는 날씨", "날씨를 좋아", "비 오는 날", "맑은 날"}, {"요즘 가장 기다리는 일", "기다리", "기대하"},
        // more to wonder about (준: "물어볼 주제 … 아직 부족")
        {"짜장면파인지 짬뽕파인지", "짜장", "짬뽕", "중국집"}, {"붕어빵은 팥인지 슈크림인지", "붕어빵", "팥붕", "슈붕", "슈크림"},
        {"탕수육은 부먹인지 찍먹인지", "부먹", "찍먹", "탕수육"}, {"라면 끓이는 취향(꼬들꼬들·푹 익힌 …)", "라면을", "컵라면", "라면 끓", "라면 취향", "꼬들"},
        {"좋아하는 과일", "과일", "딸기", "복숭아", "수박", "귤"}, {"매운 걸 잘 먹는지", "매운", "맵찔", "떡볶이", "불닭"},
        {"민트초코 좋아하는지", "민트초코", "민초", "민트"}, {"치킨은 양념인지 후라이드인지", "치킨", "양념", "후라이드"},
        {"제일 당기는 야식", "야식", "밤에 먹", "출출"}, {"편의점에서 자주 집는 것", "편의점", "삼각김밥", "바나나우유"},
        {"좋아하는 국물 요리(찌개·국밥 …)", "국물", "찌개", "국밥", "전골"}, {"단 거파인지 짠 거파인지", "단짠", "단 거", "단 걸", "짠 거", "짠 걸", "달달"},
        {"얼죽아인지 뜨아인지", "얼죽아", "뜨아", "아이스 아메리카노", "아이스 커피", "따뜻한 커피", "따뜻한 걸 좋아"}, {"좋아하는 길거리 음식(호떡·어묵 …)", "길거리", "어묵", "호떡", "떡꼬치", "군고구마"},
        {"노래방 애창곡", "노래방", "애창곡", "십팔번"}, {"좋아하는 만화나 웹툰", "웹툰", "만화책", "만화를"},
        {"좋아하는 책이나 작가", "책을", "소설", "작가", "독서", "읽는 걸"}, {"좋아하는 캐릭터", "좋아하는 캐릭터", "산리오", "포켓몬", "카카오프렌즈", "굿즈"},
        {"배워 보고 싶은 악기", "악기", "피아노", "기타를", "우쿨렐레", "드럼"}, {"바다파인지 산파인지", "바다", "산파", "산이 좋", "산을 좋아", "계곡"},
        {"카페에서 좋아하는 자리", "카페", "창가", "구석 자리"}, {"그림 그리기나 뜨개질 같은 손 취미", "그림", "그리는", "뜨개", "색칠"},
        {"사진 찍는 걸 좋아하는지", "사진 찍", "카메라", "필름"}, {"식물 키우는 걸 좋아하는지", "식물", "화분", "다육", "텃밭"},
        {"퍼즐이나 보드게임 좋아하는지", "퍼즐", "보드게임", "스도쿠", "퀴즈를 좋아"}, {"좋아하는 스포츠(야구·축구 …)", "야구", "축구", "농구", "배구", "응원하는 팀"},
        {"아침에 눈 뜨면 제일 먼저 하는 일", "눈 뜨면", "일어나자마자", "아침에 제일 먼저"}, {"잠들기 전에 하는 일", "자기 전", "잠들기 전", "잘 때"},
        {"휴대폰 배경화면은 뭘로 해 뒀는지", "배경화면", "잠금화면", "바탕화면"}, {"자주 쓰는 이모티콘", "이모티콘", "이모지", "움짤"},
        {"좋아하는 향기", "향기", "냄새", "향수", "향이 좋"}, {"좋아하는 소리(빗소리·ASMR …)", "빗소리", "ASMR", "소리가 좋", "백색소음"},
        {"좋아하는 꽃", "꽃을 좋아", "좋아하는 꽃", "벚꽃", "해바라기", "튤립"}, {"눈 오는 날 하고 싶은 일", "눈 오는", "눈사람", "눈싸움"},
        {"노을이나 밤하늘 보는 걸 좋아하는지", "노을", "밤하늘", "하늘 보"}, {"연말에 하고 싶은 일", "연말", "크리스마스", "새해"},
        {"어렸을 때 즐겨 본 만화영화", "추억의 만화", "어렸을 때 본", "옛날 만화", "만화 주제가"}, {"어렸을 때 자주 한 놀이", "놀이터", "숨바꼭질", "딱지", "고무줄"},
        {"처음 가진 휴대폰", "첫 휴대폰", "첫 폰", "처음 산 폰", "처음 가진 폰"}, {"소중하게 간직하는 물건", "간직", "소중한 물건", "보물"},
        {"기억에 남는 선물", "선물", "깜짝 선물", "받은 선물"}, {"하루 동안 초능력이 생긴다면", "초능력", "순간이동", "하늘을 날"},
        {"꼭 한번 해 보고 싶은 일", "버킷리스트", "해 보고 싶", "꼭 해 보"}, {"무인도에 가져갈 세 가지", "무인도", "섬에 가져갈", "딱 세 가지"},
        {"타임머신이 있다면 가 보고 싶은 때", "타임머신", "과거로 가", "미래로 가"}, {"웃음이 빵 터지는 것", "웃음 포인트", "웃음이 터", "웃긴 걸", "개그"},
        {"우주나 별 이야기 좋아하는지", "우주", "행성", "별자리", "천문"}, {"좋아하는 탈것(비행기·기차 …)", "탈것", "비행기", "전투기", "기차", "자동차"},
        {"레고나 프라모델 조립 좋아하는지", "레고", "프라모델", "조립"}, {"어릴 때 아끼던 장난감", "장난감", "애착 인형", "인형"},
        {"좋아하는 문구(다이어리·스티커 …)", "문구점", "다이어리", "스티커", "만년필", "필기구"}, {"춤추는 거 좋아하는지", "춤", "댄스", "안무"},
        {"소풍 가면 싸 가고 싶은 것", "소풍", "도시락", "피크닉"}, {"캠핑이나 불멍 좋아하는지", "캠핑", "불멍", "텐트", "모닥불"}};
    /**
     * What to ask about next: something it knows nothing of yet (none of its words in what it knows), not asked in the last
     * three days, by chance among the first few still unknown; else what was asked longest ago. "" when it asked within
     * three hours (준: "맥락과 무관한 무의미한 질문들이 반복돼": every twenty minutes was too often). asked: {thing: when it was asked}.
     */
    public static String curious(String known, JSONObject asked, long now, Random random) {
        String k = known == null ? "" : known;
        long last = 0; for (String[] c : CURIOUS) last = Math.max(last, asked == null ? 0 : asked.optLong(c[0], 0));
        if (now - last < 3 * 3_600_000L) return "";
        List<String> unknown = new ArrayList<>(), all = new ArrayList<>();
        for (String[] c : CURIOUS) {
            if (asked != null && now - asked.optLong(c[0], 0) < 3 * 86_400_000L) continue;
            all.add(c[0]);
            boolean knows = false; for (int i = 1; i < c.length && !knows; i++) knows = k.contains(c[i]);
            if (!knows) unknown.add(c[0]);
        }
        if (!unknown.isEmpty()) return unknown.get(random.nextInt(Math.min(4, unknown.size())));
        if (all.isEmpty()) return "";
        all.sort(Comparator.comparingLong(t -> asked == null ? 0 : asked.optLong(t, 0)));
        return all.get(0);
    }
    /** How many of the things it would like to know it already knows (CURIOUS): few means it asks more often. */
    public static int knows(String known) {
        int n = 0; String k = known == null ? "" : known;
        for (String[] c : CURIOUS) for (int i = 1; i < c.length; i++) if (k.contains(c[i])) { n++; break; }
        return n;
    }

    // ── what to talk about next (no repeating) ──
    public static final List<String> TOPICS = List.of(
        // No question topics (준: "맥락과 무관한 무의미한 질문들이 반복돼"): what it wants to know about the user is asked only on a
        // tap, now and then (BuddyTalk.thinkOnce, curious); the rest are 퍼레's own words.
        "휴대폰 탐험 이야기(앱 마을에서 본 것)", "엉뚱한 상상 하나", "말장난이나 귀여운 농담",
        "재밌는 상식 한 줄", "너의 취향 고백(좋아하는 것·싫어하는 것)",
        "너의 기억 속 일 꺼내기", "날씨·계절에서 본 작은 것", "방금 쓴 앱 이야기", "너 자신의 근황(놀이·춤·간식)",
        "사용자에 대해 아는 것 하나 살짝 건드리기", "작은 방송 멘트(소식 전하듯)");
    /**
     * The next topic: among the ones fit now (memories, a recent app), one of the three used longest ago, by chance. used:
     * {topic: when it was last used}.
     */
    public static String topic(JSONObject used, boolean hasMemory, boolean hasRecentApp, Random random) {
        List<String> fit = new ArrayList<>();
        for (String t : TOPICS) {
            if (!hasMemory && (t.startsWith("너의 기억") || t.startsWith("사용자에 대해"))) continue;
            if (!hasRecentApp && t.startsWith("방금 쓴 앱")) continue;
            fit.add(t);
        }
        fit.sort(Comparator.comparingLong(t -> used == null ? 0 : used.optLong(t, 0)));
        return fit.get(random.nextInt(Math.min(3, fit.size())));
    }
    /** How alike two lines are: pairs of letters in common (Jaccard), 0 to 1, spaces and marks left out. */
    public static double similarity(String a, String b) {
        Set<String> x = pairs(a), y = pairs(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> both = new HashSet<>(x); both.retainAll(y);
        Set<String> any = new HashSet<>(x); any.addAll(y);
        return (double) both.size() / any.size();
    }
    private static Set<String> pairs(String s) {
        String t = s == null ? "" : s.replaceAll("[\\s\\p{Punct}…~·!?.,'\"“”‘’]", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }
    /** Whether the line is too like one already said (at limit or more alike). */
    public static boolean repeats(String line, List<String> said, double limit) {
        for (String s : said) if (similarity(line, s) >= limit) return true;
        return false;
    }
    private static final Set<String> COMMON = Set.of("오늘", "지금", "정말", "진짜", "우리", "같이", "조금", "너무", "있어요", "해요", "같아요", "있죠", "어때요", "대장님", "그럼", "이제", "있어", "같아", "어때", "있지");
    /** The words used most in the last lines (2 letters or more, endings cut), for "avoid these this time". */
    public static List<String> overused(List<String> said, int most) {
        Map<String, Integer> count = new HashMap<>();
        for (String s : said.subList(Math.max(0, said.size() - 10), said.size())) {
            Set<String> seen = new HashSet<>();
            for (String w : s.split("[\\s,.!?~…·]+")) {
                String stem = w.replaceAll("(이에요|예요|이네요|네요|어요|아요|해요|했어요|에서|으로|에게|까지|부터|이랑|랑|하고|은|는|이|가|을|를|의|에|도|만|요)$", "");
                if (stem.length() < 2 || COMMON.contains(stem) || COMMON.contains(w) || !seen.add(stem)) continue;
                count.merge(stem, 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>(count.keySet());
        out.sort((p, q) -> count.get(q) - count.get(p));
        return out.subList(0, Math.min(most, out.size()));
    }

    /** The words a line is about (2 letters or more, endings cut, everyday words left out): 전투기를 → 전투기, 배터리도 → 배터리. */
    public static Set<String> subjects(String line) {
        Set<String> out = new HashSet<>();
        for (String w : (line == null ? "" : line).split("[\\s,.!?~…·]+")) {
            String stem = w.replaceAll("(이에요|예요|이네요|네요|어요|아요|해요|했어요|에서|으로|에게|까지|부터|이랑|랑|하고|처럼|보다|은|는|이|가|을|를|의|에|도|만|요|야|지|네)$", "");
            if (stem.length() >= 2 && !COMMON.contains(stem) && !COMMON.contains(w)) out.add(stem);
        }
        return out;
    }
    /**
     * The subjects said too often lately (준: "자꾸 전투기 배터리 이야기를 반복해서해", "전투기이야기만 수백번 하고 있어"): a word
     * in two or more of the last thirty lines, or a long one (three letters or more: 전투기, 모델링, 배터리) in any of the last
     * eight, however differently those were worded.
     */
    public static Set<String> staleSubjects(List<String> said) {
        Map<String, Integer> count = new HashMap<>();
        Set<String> out = new HashSet<>();
        int from = Math.max(0, said.size() - 30), close = Math.max(0, said.size() - 8);
        for (int i = from; i < said.size(); i++) for (String w : subjects(said.get(i))) {
            if (count.merge(w, 1, Integer::sum) >= 2 || i >= close && w.length() >= 3) out.add(w);
        }
        return out;
    }
    /** Whether a word is one of these subjects: the same, or one a longer form of the other (전투기 and 전투기라서, 모델링하는). */
    public static boolean among(String word, Set<String> subjects) {
        for (String s : subjects) {
            String shorter = s.length() <= word.length() ? s : word, longer = shorter == s ? word : s;
            if (s.equals(word) || shorter.length() >= 3 && longer.startsWith(shorter)) return true;
        }
        return false;
    }
    /** A line on a subject already said too often lately (staleSubjects). */
    public static boolean staleSubject(String line, List<String> said) {
        Set<String> stale = staleSubjects(said);
        for (String word : subjects(line)) if (among(word, stale)) return true;
        return false;
    }
    /** The lines of what is known (a profile, notes) on none of these subjects: what 퍼레 may bring up now. */
    public static String freshLines(String text, Set<String> stale) {
        if (text == null || text.isBlank() || stale.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\n")) {
            boolean old = false;
            for (String word : subjects(line)) if (among(word, stale)) { old = true; break; }
            if (!old) out.append(out.length() == 0 ? "" : "\n").append(line);
        }
        return out.toString();
    }

    // ── the user's work kept out of 퍼레's picture of them (준: "나를 이해한 바탕으로 일상적인 말걸기, 업무적인 것 말고") ──
    /** A line of the assistant's note about work, a project or money: not for 퍼레 to bring up. */
    private static final Pattern WORK = Pattern.compile("(작업|업무|프로젝트|업로드|편집|렌더|영상 ?제작|모델링|코딩|회의|마감|출근|주식|코인|시세)");
    private static final Pattern HEADING = Pattern.compile("^\\s*#+\\s*(.*)$");
    /**
     * The assistant's note about the user (Briefing.profile: ## 하는 일·관심사, ## 진행 중인 일, ## 생활 패턴 …) without its work:
     * the work sections left out (by their headings, loosely, as the user may have edited them: 진행 중인 일들, 진행중), and in the
     * rest the lines on work, projects or money. A section with nothing left loses its heading too. The whole note went with
     * every answer, and 퍼레 brought up the user's work unasked.
     */
    public static String everyday(String note) {
        if (note == null || note.isBlank()) return "";
        StringBuilder out = new StringBuilder(), section = new StringBuilder();
        String heading = null; boolean work = false;
        for (String line : note.split("\\n")) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                keep(out, heading, section); section.setLength(0);
                heading = line.strip(); String name = h.group(1).replaceAll("\\s+", "");
                work = name.contains("하는일") || name.contains("진행중");
                continue;
            }
            if (work || line.isBlank() || WORK.matcher(line).find()) continue;
            section.append(line).append('\n');
        }
        keep(out, heading, section);
        return out.toString().strip();
    }
    private static void keep(StringBuilder out, String heading, StringBuilder section) {
        if (section.length() == 0) return;
        if (heading != null) out.append(heading).append('\n');
        out.append(section);
    }
    private static final Pattern ASKS_WORK = Pattern.compile("(작업|업무|프로젝트|업로드|마감|회의|출근|회사\\s*일|(^|[\\s,])일(은|이|도)?\\s*(어때|어떻|잘|많))");
    /** Whether the user asked about their work ("요즘 작업 잘 돼?", "일은 어때"): then the whole note goes with the answer. */
    public static boolean asksWork(String said) { return said != null && ASKS_WORK.matcher(said).find(); }

    /**
     * Words that may ask 퍼레 to do something (BuddyTalk.chatText: then the whole list of what it can do goes along; otherwise
     * chitchat's one line). Generous on purpose: a miss costs one more request (the brain answers "more"), a hit only tokens.
     */
    private static final Pattern MAY_ACT = Pattern.compile("(열어|열기|켜|꺼|끄|틀어|재생|멈춰|정지|다음\\s*곡|이전\\s*곡|알람|타이머|스톱워치|깨워|맞춰|"
        + "길\\s*(찾|안내|알려)|가는\\s*길|내비|네비|안내해|전화|통화|문자|메시지|카톡|보내|공유|검색|찾아|알아봐|사진|셀카|카메라|찍어|동영상|녹화|녹음|"
        + "메모|적어|기록|기억해|잊어|지워|삭제|일정|캘린더|약속|예약|추가|넣어|빼|계산|번역|주사위|동전|골라|뽑아|몇\\s*시|시각|며칠|디데이|[dD]-?day|"
        + "밝기|소리|볼륨|음량|손전등|플래시|와이파이|wifi|블루투스|방해\\s*금지|회전|가로|세로|설정|앱|어플|유튜브|멜론|스포티파이|노래|음악|영상|주문|"
        + "배민|쿠팡|담아|사\\s*줘|올려|업로드|다운|파일|갤러리|연락처|메일|조사|연구|분석|보고서|정리해|만들어|써\\s*줘|작성|매일|마다|되면|도착하면|"
        + "장난감|옷|입어|입혀|벗어|춤|놀자|놀아|불러|호칭|조용|그만|지난주|어제|그저께|뭐였지|어디였지|페이지|넘겨|닫아|종료|사이트|주소|"
        + "해\\s*줘|해\\s*줄래|해\\s*봐|줄래|부탁|하자|가자|알려\\s*줘|알림|기억나|넘으면|하면|ㄱㄱ|고고)");
    /** Whether what was said may ask 퍼레 to do something (MAY_ACT); plain chitchat ("안녕", "뭐해?", "심심해") does not. */
    public static boolean mayAct(String said) { return said != null && MAY_ACT.matcher(said).find(); }

    private static final Pattern NAG = Pattern.compile("(힘\\s*내|화이팅|파이팅|쉬어|쉬자|쉬엄|쉬면서|휴식|푹\\s*쉬|수고|고생\\s*했|고생\\s*많|애썼|무리\\s*하지|무리하면|잘\\s*하고\\s*있어|응원할게|응원해)");
    /** Cheering, a nudge to rest, a "well done" (준: "자꾸 화이팅하고 쉬래"): not 퍼레's to say unasked. */
    public static boolean nag(String line) { return line != null && NAG.matcher(line).find(); }

    // ── what 퍼레 does not say unasked, aloud or typed (its home lines: BuddyTalk.clean) ──
    /** Chores (준: "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데"): tidying, deleting, backing up, storage, updates. */
    public static final Pattern CHORES = Pattern.compile("(정리|청소|삭제|지우|백업|용량|저장 ?공간|업데이트|파일)");
    private static final Pattern TIRED = Pattern.compile("(힘들|피곤|지쳤|졸려|우울|속상|아파(?!트)|슬퍼)");
    private static final Pattern MONEY = Pattern.compile("(코인|비트코인|주식|시세)");
    private static final Pattern MONEY_ASKED = Pattern.compile("(코인|비트코인|주식|시세|돈|가격|얼마)");
    /** The user said they are tired, down or hurting: then a word of comfort is theirs. */
    public static boolean tired(String said) { return said != null && TIRED.matcher(said).find(); }
    /** The user brought up a chore ("저장공간 얼마 남았어", "사진 정리해 줘"). */
    public static boolean raisedChores(String said) { return said != null && CHORES.matcher(said).find(); }
    /** The user brought up money or prices. */
    public static boolean raisedMoney(String said) { return said != null && MONEY_ASKED.matcher(said).find(); }
    /**
     * A sentence 퍼레 should not say to what the user said (준: "자꾸 화이팅하고 쉬래"): cheering or a nudge to rest when they did
     * not say they were tired, a chore they did not bring up, coins or stocks when they did not ask about money. A battery's
     * percent or the storage they asked about is an answer, not a chore.
     */
    public static boolean unasked(String sentence, String said) {
        if (sentence == null || sentence.isBlank()) return false;
        return nag(sentence) && !tired(said) || CHORES.matcher(sentence).find() && !raisedChores(said) || MONEY.matcher(sentence).find() && !raisedMoney(said);
    }
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?~…])\\s+");
    /**
     * A typed answer without the sentences 퍼레 should not say (unasked), its lines and '-' lists kept as written; a line with
     * nothing left goes. "" when nothing is left.
     */
    public static String keepAsked(String text, String said) {
        if (text == null || text.isBlank()) return "";
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\n")) {
            if (line.isBlank()) { if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add(""); continue; }
            Matcher item = LIST_ITEM.matcher(line); String mark = item.find() ? item.group() : "";
            List<String> kept = new ArrayList<>();
            for (String s : SENTENCE_END.split(line.substring(mark.length()).strip())) if (!unasked(s, said)) kept.add(s);
            if (!kept.isEmpty()) lines.add(mark + String.join(" ", kept));
        }
        return String.join("\n", lines).strip();
    }
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*[-•*]\\s+");
    /** The same opening as one of the last four lines (뿅!, 오늘…, 저녁…: its first word's first two letters): the same mould again. */
    public static boolean sameOpening(String line, List<String> said) {
        String first = firstWord(line); if (first.length() < 1) return false;
        for (String s : said.subList(Math.max(0, said.size() - 4), said.size())) if (first.equals(firstWord(s))) return true;
        return false;
    }
    private static String firstWord(String s) {
        String t = s == null ? "" : s.strip().replaceAll("^[\\p{Punct}\\s~…·]+", "");
        String w = t.split("[\\s,.!?~…]+", 2)[0];
        // Its first two letters: 저녁엔 and 저녁이네 are one opening, so are 오늘은 and 오늘도.
        return w.codePointCount(0, w.length()) <= 2 ? w : w.substring(0, w.offsetByCodePoints(0, 2));
    }

    /**
     * Text emoticons (준: "퍼레가 그래픽이모지 외에 텍스트 이모지도 사용할 수 있게해"): a face in brackets with no words in it
     * ((๑˃ᴗ˂)ﻭ, (◍•ᴗ•◍)), with what hangs off it, and ^^, ^_^, ㅎㅎ, ㅋㅋ, ㅠㅠ, ㅜㅜ, :), ;), >_<, T_T, -_-.
     */
    public static final Pattern KAOMOJI = Pattern.compile("[^\\s가-힣A-Za-z0-9]{0,3}\\([^()가-힣A-Za-z0-9]{1,16}\\)[^\\s가-힣A-Za-z0-9.,!?~]{0,3}"
        + "|\\^[_\\-.~]?\\^|[ㅎㅋ]{2,}|[ㅠㅜ]{2,}|(?<![A-Za-z0-9]):[)D(P]|;\\)|>[_.]<|T[_.]T|-[_.]-");
    /** A line with its text emoticons taken out (for the voice, and for reading the rest). */
    public static String withoutEmoticons(String line) { return line == null ? "" : KAOMOJI.matcher(line).replaceAll(" ").replaceAll("\\s+", " ").strip(); }

    // ── Korean that reads ──
    private static final Pattern CUT = Pattern.compile("^(도|는|은|을|를|에|의|과|로|으로|에서|랑)(\\s|[,.!?~])");
    private static final Pattern FOREIGN = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\u0400-\\u04ff\\u0e00-\\u0e7f]");
    /**
     * Whether a line reads as Korean (준: "가끔 한국어를 못알아듣게 말해"): not cut at its start (a particle first: "도 구름 낀
     * 사천…"), no Chinese, Japanese, Cyrillic or Thai letters slipped in, and mostly Hangul.
     */
    public static boolean readable(String line) {
        if (line == null || line.isBlank()) return false;
        String t = withoutEmoticons(line);
        if (t.isEmpty()) return false;
        if (CUT.matcher(t).find() || FOREIGN.matcher(t).find()) return false;
        int hangul = 0, letters = 0;
        for (int i = 0; i < t.length(); i++) { char ch = t.charAt(i); if (Character.isLetter(ch)) { letters++; if (ch >= '\uac00' && ch <= '\ud7a3') hangul++; } }
        return letters > 0 && hangul * 2 >= letters;
    }

    // ── its name, misheard ──
    /** How the phone's recognizer and Whisper hear 퍼레 (준: "퍼레가 자기 이름을 못알아듣고 자꾸 벌레 버려 이런걸로 알아들어"). */
    // More of how the recognizers mishear it (준: "퍼레가 자기 이름 잘 못알아듣는것"): 펠레, 포래, 필레, 퍼렐, 프래, 버리, 헐레 …
    private static final String LIKE = "벌레|벌래|버레|버래|버려|퍼래|퍼려|펄레|펄래|뻐레|빠레|페레|파레|퍼리|프레|포레|허레|펠레|펠래|포래|필레|필래|퍼렐|프래|헐레|헐래|퍼라|퍼러|퍼례|퍼레이|버리|보레|뻐래";
    /** Said as a call anywhere (벌레야, 퍼래야): never meant as a bug. 버려야 and 거래야 are words too, so only at the start. */
    private static final Pattern CALLED = Pattern.compile("(^|[\\s,.!?~])(?:벌레|벌래|버레|버래|퍼래|퍼려|펄레|펄래|뻐레|빠레|페레|파레|퍼리|프레|포레|허레|펠레|펠래|포래|필레|필래|퍼렐|프래|헐레|헐래|퍼례)(야|아)(?=$|[\\s,.!?~])");
    private static final Pattern FIRST = Pattern.compile("^(?:(?:" + LIKE + ")(?:야|아)?|거래야)(?:$|[\\s,.!?~]+)(.*)$", Pattern.DOTALL);
    /**
     * What was said with 퍼레's name put back where the recognizer heard something like it: the whole thing (벌레, 버려야!),
     * the first word before the rest (버려 지금 몇 시야 → 퍼레야 지금 몇 시야), or a call in the middle (오늘 벌레야 뭐 해).
     * Words that are not a call stay (벌레가 나왔어, 이거 버려야 해).
     */
    public static String nameHeard(String said) {
        if (said == null) return null;
        String t = said.strip();
        java.util.regex.Matcher first = FIRST.matcher(t);
        if (first.matches()) { String rest = first.group(1).strip(); return rest.isEmpty() ? "퍼레야" : "퍼레야, " + rest; }
        return CALLED.matcher(t).replaceAll("$1퍼레야");
    }

    // ── what is private ──
    /*
     * Private (준: "병원에 간대요 정도는 민감한 기억이 아닌 듯", "비번이나 사이트 권한요청 고민거리 뭐 그런거 있자나"): passwords and
     * logins, a site's or an app's access, numbers that open an account, the home address, and the worries the user confided.
     * A hospital visit, health, love and family are everyday news, not private.
     */
    private static final Pattern SECRET = Pattern.compile("(비밀번호|비번|패스워드|암호|핀\\s*번호|PIN|인증\\s*번호|인증\\s*코드|OTP|보안\\s*코드|아이디|계정|로그인|권한|접근\\s*허용|계좌|카드\\s*번호|주민\\s*(등록\\s*)?번호|여권\\s*번호|집\\s*주소|집\\s*위치|전화\\s*번호|연락처|고민|걱정|스트레스|털어놓|속상|우울|불안|빚|대출|비밀)", Pattern.CASE_INSENSITIVE);
    /** Whether a memory is private (a password, a login or a site's access, a worry confided …): kept, but said only after the fingerprint. */
    public static boolean secret(String text) { return text != null && SECRET.matcher(text).find(); }
    private static final Pattern ASKS = Pattern.compile("(내|나의|제)\\s*(비밀|비번|비밀번호|암호|아이디|계정|로그인|권한|계좌|카드|번호|주소|고민|걱정)|기억나|기억해\\?|뭐라고\\s*했|뭐라 했|알고\\s*있|알려\\s*줘.*(나|내)|(나|내)에\\s*대해"
        // "잊어 줘", "그건 틀렸어 지워" (준: "퍼레의 학습기능"): a private memory is let go only after the fingerprint; not "우산 잊어버렸어".
        + "|잊어\\s*줘|잊어버려|잊어(?=[\\s.!?~]*$)|지워\\s*줘|지워버려|지워(?=[\\s.!?~]*$)|까먹어\\s*줘|까먹어(?=[\\s.!?~]*$)");
    /** Whether what the user asked may need what is private (then the fingerprint first). */
    public static boolean asksPersonal(String said) { return said != null && ASKS.matcher(said).find(); }

    // ── what to bring to mind now ──
    /**
     * The memories to bring to mind now, at most n: by how much they matter and how often the user told them (seen, up to
     * five times), how recently they were told (last; two weeks to fade by half), and how much they share with what is going
     * on (context: the hour, the weather, apps, what was said); a memory brought up in the last day waits (shown: memory time
     * → when last brought up); private ones only when unlocked. What the context asks for counts too ("몇 시에 출발할까" asks
     * for a plan: before 기억 2.0 it brought up the newer sneakers instead of the wedding): BuddyRecall.find.
     */
    public static List<BuddyMemory.Entry> recall(List<BuddyMemory.Entry> all, String context, long now, Map<Long, Long> shown, boolean unlocked, int n) {
        return recall(all, context, now, shown, unlocked, n, null, null);
    }
    /** The same by meaning too (기억 2.0): vectors (BuddyRecall.key → unit vector) and the context's own of the same model; null for words alone. */
    public static List<BuddyMemory.Entry> recall(List<BuddyMemory.Entry> all, String context, long now, Map<Long, Long> shown, boolean unlocked, int n, Map<String, float[]> vectors, float[] query) {
        List<BuddyMemory.Entry> out = new ArrayList<>();
        for (BuddyRecall.Hit h : BuddyRecall.find(all, context, vectors, query, now, shown, unlocked, n)) out.add(h.entry());
        return out;
    }

    // ── learning from a whole talk (준: "퍼레의 학습기능 … 강화해줘"): what it knew told again, set right or let go, or new ──
    /** The kinds of memory a talk can teach or correct: what the user told, the night's notes about them, promises. */
    private static final Set<String> KNOWN = BuddyRecall.TOLD;
    /** Words that say nothing of what a memory is about: the user and 퍼레, when, how often, and the common "does" endings. */
    private static final Set<String> PLAIN = Set.of("사용자", "퍼레", "오늘", "내일", "어제", "요즘", "이번", "다음", "주말", "아침", "저녁", "자주", "가끔",
        "많이", "이제", "예전", "그냥", "처음", "항상", "아직", "사실", "완전", "제일", "가장", "간대", "한대", "했대", "갔대", "왔대", "온대", "산대", "된대",
        "있대", "없대", "한다", "했어", "했다", "간다");
    /** What a line is about: its subjects (subjects()) without the plain words and the liking, the not liking and the "~대" of a memory. */
    static Set<String> topics(String line) {
        Set<String> out = new HashSet<>();
        for (String w : subjects(line)) {
            if (PLAIN.contains(w) || w.contains("좋아") || w.contains("싫어") || w.length() >= 3 && w.endsWith("대")) continue;
            out.add(w);
        }
        return out;
    }
    /** Whether two lines are about the same thing: a topic in common (커피 and 커피를 are one, so are 전투기 and 전투기라서). */
    public static boolean shareTopic(String a, String b) {
        Set<String> other = topics(b);
        for (String w : topics(a)) if (among(w, other)) return true;
        return false;
    }
    /**
     * What 퍼레 knows that what was said is about, at most n: the user's told memories, the night's notes and promises with a
     * topic in common (or nearly said again), the most in common first; private ones only when unlocked.
     */
    public static List<BuddyMemory.Entry> related(List<BuddyMemory.Entry> all, String said, int n, boolean unlocked) {
        Set<String> heard = topics(said);
        List<BuddyMemory.Entry> pool = new ArrayList<>();
        Map<BuddyMemory.Entry, Double> score = new HashMap<>();
        for (BuddyMemory.Entry e : all) {
            if (!KNOWN.contains(e.kind()) || e.secret() && !unlocked || e.done()) continue;
            int shared = 0; for (String w : topics(e.text())) if (among(w, heard)) shared++;
            double alike = similarity(e.text(), said);
            if (shared == 0 && alike < 0.6) continue;
            score.put(e, alike + 0.5 * shared); pool.add(e);
        }
        pool.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
    }
    private static final Pattern NEGATION = Pattern.compile("(안 |못 |싫|끊|그만|별로|말고|아니)");
    /**
     * Whether one line says the opposite of the other ("커피를 안 좋아한대" to "커피를 좋아한대", "싫어한대" to "좋아한대"): only
     * one of them says no, and without the no they are alike (half their letter pairs) and about the same thing (or nearly the
     * same words: "고양이를 안 좋아한대" is not the opposite of "커피를 좋아한대").
     */
    public static boolean negates(String a, String b) {
        if (a == null || b == null) return false;
        if (NEGATION.matcher(a).find() == NEGATION.matcher(b).find()) return false;
        double alike = similarity(NEGATION.matcher(a).replaceAll(""), NEGATION.matcher(b).replaceAll(""));
        return alike >= 0.5 && (shareTopic(a, b) || alike >= 0.75);
    }
    /** What the user said may be them correcting what 퍼레 knew ("아니 나 커피 끊었어", "이제는 안 좋아해"). */
    private static final Pattern CORRECTS = Pattern.compile("(아니|아냐|틀렸|그게 아니|잘못 알|이제는|요즘은 안|끊었|안 좋아|싫어해)");
    public static boolean correcting(String said) { return said != null && CORRECTS.matcher(said).find(); }

    /**
     * What a talk taught: something new, a known one (n: its number in the list the brain was given) set right, let go or
     * told again, or a promise of 퍼레's kept (DONE).
     */
    public enum Change { NEW, FIX, DROP, SAME, DONE }
    /** One lesson of a talk (parseOps): its change, the known one's number (0: none), its kind (fact, promise), its words, private. */
    public record Op(Change change, int n, String kind, String text, boolean secret) {}
    private static final Pattern TOLD = Pattern.compile("^(새로|약속|사용자)\\s*[:：]\\s*(.+)$");
    private static final Pattern FIXED = Pattern.compile("^고침\\s*#?(\\d+)\\s*번?\\s*[:：]\\s*(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^(지움|같음|약속\\s*끝)\\s*#?(\\d+)");
    /**
     * The lessons the learning brain wrote, at most four, a line each: "새로: 사용자는 …", "고침 2: 사용자는 …", "지움 3", "같음 1",
     * "약속: 퍼레가 …", "약속 끝 4", each maybe with "민감" first (private); lists' marks and quotes left out, other lines ("없음") skipped.
     * "사용자: …" (the form before) reads as new.
     */
    public static List<Op> parseOps(String text) {
        List<Op> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.replaceAll("(?s)<think>.*?</think>", "").split("\\n")) {
            String line = raw.replaceAll("[\"“”*]", "").replaceAll("^\\s*(?:[-•]+|\\d+[.)])\\s*", "").strip();
            boolean secret = line.startsWith("민감"); line = line.replaceFirst("^민감\\s*", "");
            Op op = null; Matcher m;
            if ((m = FIXED.matcher(line)).matches()) op = new Op(Change.FIX, Integer.parseInt(m.group(1)), "fact", m.group(2), secret);
            else if ((m = NUMBERED.matcher(line)).find()) op = new Op("지움".equals(m.group(1)) ? Change.DROP : "같음".equals(m.group(1)) ? Change.SAME : Change.DONE, Integer.parseInt(m.group(2)), "", "", false);
            else if ((m = TOLD.matcher(line)).matches()) op = new Op(Change.NEW, 0, "약속".equals(m.group(1)) ? "promise" : "fact", m.group(2), secret);
            if (op == null) continue;
            String t = op.text().strip();
            if (t.startsWith("민감")) op = new Op(op.change(), op.n(), op.kind(), t.replaceFirst("^민감\\s*", ""), true);
            if (op.n() > 0 || !op.text().isBlank()) out.add(op);
            if (out.size() == 4) break;
        }
        return out;
    }
    /** Whether lesson words may be kept: some, not "없음", 70 letters at most, no number of four digits or more (an account, a code). */
    private static boolean keepable(String t) { return t != null && !t.isBlank() && !t.startsWith("없음") && t.length() <= 70 && !t.matches(".*\\d{4,}.*"); }
    /**
     * The memories after a talk's lessons (ops), as 퍼레 keeps them: told again confirms the one known (BuddyMemory.confirm);
     * set right (고침) replaces it and let go (지움) removes it, but only one the lesson or the talk is about (a topic in common,
     * or nearly the same words), and a private one only while unlocked (else 고침 is kept as new and 지움 left); new words that
     * say the opposite of one known replace it (negates), ones nearly the same (0.6) confirm it, the rest are kept as told
     * (weight 2). shown: the list the brain was given, numbered from 1; talk: the words of the talk.
     */
    public static List<BuddyMemory.Entry> learned(List<BuddyMemory.Entry> all, List<BuddyMemory.Entry> shown, List<Op> ops, String talk, long now, boolean unlocked) {
        return learned(all, shown, ops, talk, now, unlocked, Map.of());
    }
    /** The same, a lesson on a subject the user asked 퍼레 to let go (forgot, forgotten()) left out. */
    public static List<BuddyMemory.Entry> learned(List<BuddyMemory.Entry> all, List<BuddyMemory.Entry> shown, List<Op> ops, String talk, long now, boolean unlocked, Map<String, Long> forgot) {
        List<BuddyMemory.Entry> out = new ArrayList<>(all);
        int added = 0;
        for (Op op : ops) {
            BuddyMemory.Entry target = op.n() >= 1 && shown != null && op.n() <= shown.size() ? at(out, shown.get(op.n() - 1).at()) : null;
            if (target != null && target.secret() && !unlocked) target = null;
            if (op.change() == Change.SAME) { if (target != null) out = BuddyMemory.confirm(out, target.at(), now); continue; }
            if (op.change() == Change.DONE) { if (target != null) out = BuddyMemory.done(out, target.at()); continue; }
            if (op.change() == Change.DROP) { if (target != null && shareTopic(target.text(), talk)) out = BuddyMemory.remove(out, target.at()); continue; }
            String t = op.text().strip();
            if (!keepable(t) || forgotten(t, forgot, now)) continue;
            boolean secret = op.secret() || secret(t);
            if (op.change() == Change.FIX && target != null && (shareTopic(target.text(), t) || similarity(target.text(), t) >= 0.6)) {
                out = BuddyMemory.replace(out, target.at(), t, now, secret || target.secret()); continue;
            }
            BuddyMemory.Entry opposite = null, same = null;
            for (BuddyMemory.Entry e : out) {
                if (!KNOWN.contains(e.kind()) || e.secret() && !unlocked) continue;
                if (opposite == null && negates(e.text(), t)) opposite = e;
                if (same == null && similarity(e.text(), t) >= 0.6) same = e;
            }
            if (opposite != null) out = BuddyMemory.replace(out, opposite.at(), t, now, secret || opposite.secret());
            else if (same != null) out = BuddyMemory.confirm(out, same.at(), now);
            // Kept as its kind (기억 2.0): a liking, a plan or a fact told apart by its words; a promise stays one.
            else out = BuddyMemory.add(out, now + added++, "promise".equals(op.kind()) ? "promise" : BuddyRecall.sort(t), t, 0, 2, secret);
        }
        return out;
    }
    private static BuddyMemory.Entry at(List<BuddyMemory.Entry> list, long at) { for (BuddyMemory.Entry e : list) if (e.at() == at) return e; return null; }

    // ── what the user asked 퍼레 to let go (준: "퍼레의 학습기능 … 강화해줘": "잊어 줘", "그건 틀렸어 지워") ──
    /** How long a subject let go stays out of what 퍼레 learns by itself (a talk, the night's digest): ninety days. */
    public static final long FORGOT_MS = 90L * 86_400_000L;
    /** Whether a line is on a subject the user asked 퍼레 to let go, not yet over (forgot: subject → until when). */
    public static boolean forgotten(String text, Map<String, Long> forgot, long now) {
        if (text == null || forgot == null || forgot.isEmpty()) return false;
        Set<String> gone = new HashSet<>();
        for (Map.Entry<String, Long> f : forgot.entrySet()) if (f.getValue() != null && f.getValue() > now) gone.add(f.getKey());
        if (gone.isEmpty()) return false;
        for (String w : topics(text)) if (among(w, gone)) return true;
        return false;
    }
    /**
     * The memories a "잊어 줘" names (what: its words): every one holding all its words (BuddyTricks.matching), else the one
     * known that it is most about (related: a topic in common or nearly the same words); then the night's notes on the same
     * subject, so it is not still told the next day: a note on what the user named, not on another word of what went ("커피
     * 잊어 줘" takes "아침마다 커피를 마셔요", never "친구가 많아요" because the coffee was with a friend). Private ones too: the
     * caller asks for the fingerprint.
     */
    public static List<BuddyMemory.Entry> toForget(List<BuddyMemory.Entry> all, String what) {
        List<String> texts = new ArrayList<>(); for (BuddyMemory.Entry e : all) texts.add(e.text());
        List<BuddyMemory.Entry> out = new ArrayList<>();
        for (int i : BuddyTricks.matching(texts, what)) out.add(all.get(i));
        if (out.isEmpty()) { List<BuddyMemory.Entry> best = related(all, what, 1, true); out.addAll(best); }
        if (out.isEmpty() || out.size() > 5) return out;
        for (BuddyMemory.Entry e : new ArrayList<>(out)) for (BuddyMemory.Entry p : all)
            if ("profile".equals(p.kind()) && !out.contains(p) && shareTopic(p.text(), e.text()) && shareTopic(p.text(), what)) out.add(p);
        return out;
    }
    /** The subjects a "잊어 줘" lets go: its own words' topics found in what went (커피 for "커피 얘기 잊어"), else what went is about. */
    public static Set<String> forgetting(String what, List<BuddyMemory.Entry> gone) {
        Set<String> about = new HashSet<>(), in = new HashSet<>();
        for (BuddyMemory.Entry e : gone) in.addAll(topics(e.text()));
        for (String w : topics(what)) if (among(w, in)) about.add(w);
        if (about.isEmpty() && !gone.isEmpty()) about.addAll(topics(gone.get(0).text()));
        return about;
    }
    /** A memory as 퍼레 says it back to the user: without "사용자는 …" ("‘커피를 좋아한대’ 잊었어"). */
    public static String toldBack(String text) {
        String t = text == null ? "" : text.strip().replaceFirst("^사용자(?:가 기억해 달랬어\\s*[:：]|는|가|의|도)\\s*", "");
        return t.codePointCount(0, t.length()) <= 40 ? t : t.substring(0, t.offsetByCodePoints(0, 40)) + "…";
    }
    /** A turn of a talk said by a voice not the user's ({role, text, "g"}). */
    public static boolean guestTurn(String[] t) { return t != null && t.length > 2 && "g".equals(t[2]); }
    /** The user's own words in a talk from turn from (a guest's left out), with 퍼레's when buddyToo: what the talk was about. */
    public static String talkWords(List<String[]> turns, int from, boolean buddyToo) {
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, from); i < turns.size(); i++) {
            String[] t = turns.get(i);
            if (t == null || t.length < 2 || t[1] == null || guestTurn(t) || !buddyToo && !"user".equals(t[0])) continue;
            out.append(out.length() == 0 ? "" : "\n").append(t[1].strip());
        }
        return out.toString();
    }
    /** Whether a talk from turn from is worth a learning request: the user themselves said four letters or more in it. */
    public static boolean worthLearning(List<String[]> turns, int from) { return talkWords(turns, from, false).replaceAll("\\s", "").length() >= 4; }
    /**
     * What the learning brain reads of a talk (turns {role, text[, "g"]}, new from turn from): the user's own talk only, a
     * guest's left out; the six lines before it as already learned; the new ones (the last sixteen, 2,000 letters at most);
     * what 퍼레 did for them ("22:10 노래 ‘아이유’(멜론)"); and what it knows that the talk is about, numbered for 고침·지움·같음.
     */
    public static String talkInput(List<String[]> turns, int from, List<String> acts, List<BuddyMemory.Entry> related) {
        List<String> before = new ArrayList<>(), talk = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            String[] t = turns.get(i);
            if (t == null || t.length < 2 || t[1] == null || t[1].isBlank() || guestTurn(t)) continue;
            (i < from ? before : talk).add(("user".equals(t[0]) ? "사용자: " : "퍼레: ") + cut(t[1].strip(), i < from ? 200 : 300));
        }
        List<String> kept = new ArrayList<>(); int size = 0;
        for (int i = talk.size() - 1; i >= Math.max(0, talk.size() - 16); i--) {
            size += talk.get(i).length() + 1;
            if (size > 2000 && !kept.isEmpty()) break;
            kept.add(0, talk.get(i));
        }
        StringBuilder out = new StringBuilder();
        List<String> earlier = before.subList(Math.max(0, before.size() - 6), before.size());
        if (!earlier.isEmpty()) out.append("앞의 대화(이미 배운 것, 참고만):\n").append(String.join("\n", earlier)).append("\n\n");
        out.append("대화:\n").append(String.join("\n", kept)).append('\n');
        if (acts != null && !acts.isEmpty())
            out.append("\n한 일(사용자가 시켜서 퍼레가 한 것):\n- ").append(String.join("\n- ", acts.subList(Math.max(0, acts.size() - 8), acts.size()))).append('\n');
        out.append("\n지금 알고 있는 것:\n");
        if (related == null || related.isEmpty()) out.append("(이 대화와 관련된 것 없음)");
        else for (int i = 0; i < related.size(); i++) out.append(i + 1).append(". ").append(related.get(i).text()).append('\n');
        return out.toString().strip();
    }
    private static String cut(String line, int most) {
        return line.codePointCount(0, line.length()) <= most ? line : line.substring(0, line.offsetByCodePoints(0, most)) + "…";
    }

    // ── the day so far ──
    /** Today's log with one thing added: an app's kind opened, a charge, music, coming back; first and last use kept. */
    public static JSONObject today(JSONObject log, long now, ZoneId zone, String what, String detail) {
        String date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
        JSONObject t;
        try { t = log != null && date.equals(log.optString("date")) ? new JSONObject(log.toString()) : new JSONObject().put("date", date).put("first", now); }
        catch (Exception e) { t = new JSONObject(); }
        try {
            t.put("last", now);
            if (what != null && !what.isEmpty()) {
                JSONObject c = t.optJSONObject(what); if (c == null) { c = new JSONObject(); t.put(what, c); }
                String key = detail == null || detail.isBlank() ? "_" : detail;
                c.put(key, c.optInt(key) + 1);
            }
        } catch (Exception ignored) {}
        return t;
    }
    /** The day so far in words: when it began, the kinds of apps used most, music, charging. */
    public static String todayWords(JSONObject t, ZoneId zone) {
        if (t == null || !t.has("first")) return "";
        StringBuilder out = new StringBuilder();
        int h = Instant.ofEpochMilli(t.optLong("first")).atZone(zone).getHour(), m = Instant.ofEpochMilli(t.optLong("first")).atZone(zone).getMinute();
        out.append(String.format(Locale.KOREA, "%s %d:%02d부터 휴대폰을 씀", h < 12 ? "오전" : "오후", h % 12 == 0 ? 12 : h % 12, m));
        JSONObject apps = t.optJSONObject("app");
        if (apps != null && apps.length() > 0) {
            List<String> kinds = new ArrayList<>(); apps.keys().forEachRemaining(kinds::add);
            kinds.sort((a, b) -> apps.optInt(b) - apps.optInt(a));
            out.append(", 많이 연 앱 종류: ").append(String.join(", ", kinds.subList(0, Math.min(3, kinds.size()))));
        }
        int music = count(t.optJSONObject("music")), charge = count(t.optJSONObject("charge"));
        if (music > 0) out.append(", 음악 ").append(music).append("번");
        if (charge > 0) out.append(", 충전 ").append(charge).append("번");
        return out.toString();
    }
    private static int count(JSONObject o) { if (o == null) return 0; int n = 0; for (java.util.Iterator<String> k = o.keys(); k.hasNext();) n += o.optInt(k.next()); return n; }

    // ── a night's digest ──
    /**
     * What a night's digest wrote: the user's profile, line by line ("(추측)" at the end of a guess), the day as one memory
     * (or ""), and the profile lines it took out (빼기).
     */
    public record Dream(List<String> profile, String episode, List<String> drop) {
        public Dream(List<String> profile, String episode) { this(profile, episode, List.of()); }
    }
    private static final Pattern LINE = Pattern.compile("^\\s*[-•*]?\\s*(프로필|추억|빼기)\\s*[:：]\\s*(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-•*]|\\d+[.)])\\s*(.+)$");
    /** The digest as written: "프로필: …" a line each, or "프로필:" and its lines under it as a list ("- …"); the same for 빼기. */
    public static Dream dream(String text) {
        List<String> profile = new ArrayList<>(), drop = new ArrayList<>(); String episode = "", under = "";
        if (text != null) for (String line : text.replaceAll("(?s)<think>.*?</think>", "").split("\\n")) {
            Matcher m = LINE.matcher(line), b;
            String part, v;
            if (m.find()) { part = m.group(1); v = m.group(2); under = part; if (v.isBlank()) continue; }
            else if (!under.isEmpty() && (b = BULLET.matcher(line)).find()) { part = under; v = b.group(1); }
            else { if (!line.isBlank()) under = ""; continue; }
            v = v.replaceAll("[\"“”*]", "").strip();
            if (v.isEmpty() || v.startsWith("없음") || v.length() > 80 || v.matches(".*\\d{4,}.*")) continue;
            if ("추억".equals(part)) { if (episode.isEmpty()) episode = v; }
            else if ("빼기".equals(part)) { if (!drop.contains(v)) drop.add(v); }
            else if (profile.size() < 12 && !profile.contains(v)) profile.add(v);
        }
        return new Dream(profile, episode, drop);
    }
    /** A night's profile line that is a guess: it matters least until it is told again. */
    public static boolean guess(String line) { return line != null && line.strip().endsWith("(추측)"); }
    /**
     * What 퍼레 knows after the night's digest (준: "퍼레의 학습기능 … 강화해줘"), merged into what it knew rather than written
     * over it: a line it took out (빼기) goes, a line it wrote again is told once more, one that says the opposite of one known
     * sets it right, a new one is kept (a guess matters least, weight 1; else 3), none on a subject the user let go (forgot);
     * then at most twelve lines, the ones told most and latest kept. Private lines are the user's own and stay.
     */
    public static List<BuddyMemory.Entry> mergeProfile(List<BuddyMemory.Entry> all, Dream d, long now, Map<String, Long> forgot) {
        List<BuddyMemory.Entry> out = new ArrayList<>(all);
        for (String gone : d.drop()) {
            BuddyMemory.Entry best = null; double most = 0.5;
            for (BuddyMemory.Entry e : out) if ("profile".equals(e.kind()) && !e.secret()) { double a = similarity(e.text(), gone); if (a >= most) { most = a; best = e; } }
            if (best != null) out = BuddyMemory.remove(out, best.at());
        }
        long t = now;
        for (String line : d.profile()) {
            if (forgotten(line, forgot, now)) continue;
            int weight = guess(line) ? 1 : 3;
            BuddyMemory.Entry same = null, opposite = null;
            for (BuddyMemory.Entry e : out) {
                if (!"profile".equals(e.kind()) || e.secret()) continue;
                if (same == null && similarity(e.text(), line) >= 0.6 && !negates(e.text(), line)) same = e;
                if (opposite == null && negates(e.text(), line)) opposite = e;
            }
            if (same != null) out = BuddyMemory.confirm(out, same.at(), now);
            else if (opposite != null) out = BuddyMemory.replace(out, opposite.at(), line, now, false, weight);
            else { long at = BuddyMemory.free(out, t); out.add(new BuddyMemory.Entry(at, "profile", line, weight, false)); t = at + 1; }
        }
        List<BuddyMemory.Entry> lines = new ArrayList<>();
        for (BuddyMemory.Entry e : out) if ("profile".equals(e.kind()) && !e.secret()) lines.add(e);
        if (lines.size() > 12) {
            lines.sort((a, b) -> a.seen() != b.seen() ? b.seen() - a.seen() : Long.compare(b.last(), a.last()));
            for (BuddyMemory.Entry e : lines.subList(12, lines.size())) out = BuddyMemory.remove(out, e.at());
        }
        return out;
    }
}
