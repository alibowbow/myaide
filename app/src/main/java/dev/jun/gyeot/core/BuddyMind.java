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
        {"좋아하는 날씨", "날씨를 좋아", "비 오는 날", "맑은 날"}, {"요즘 가장 기다리는 일", "기다리", "기대하"}};
    /**
     * What to ask about next: something it knows nothing of yet (none of its words in what it knows), not asked in the last
     * three days, by chance among the first few still unknown; else what was asked longest ago. "" when it asked within
     * twenty minutes. asked: {thing: when it was asked}.
     */
    public static String curious(String known, JSONObject asked, long now, Random random) {
        String k = known == null ? "" : known;
        long last = 0; for (String[] c : CURIOUS) last = Math.max(last, asked == null ? 0 : asked.optLong(c[0], 0));
        if (now - last < 20 * 60_000L) return "";
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
        "휴대폰 탐험 이야기(앱 마을에서 본 것)", "엉뚱한 상상 하나", "말장난이나 귀여운 농담", "사용자를 알아 가는 질문",
        "둘 중 하나 고르기 질문(예: 붕어빵 팥 vs 슈크림)", "짧은 수수께끼나 퀴즈", "재밌는 상식 한 줄", "너의 취향 고백(좋아하는 것·싫어하는 것)",
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

    private static final Pattern NAG = Pattern.compile("(힘\\s*내|화이팅|파이팅|쉬어|쉬자|쉬엄|쉬면서|휴식|푹\\s*쉬|수고|고생\\s*했|고생\\s*많|애썼|무리\\s*하지|무리하면|잘\\s*하고\\s*있어|응원할게|응원해)");
    /** Cheering, a nudge to rest, a "well done" (준: "자꾸 화이팅하고 쉬래"): not 퍼레's to say unasked. */
    public static boolean nag(String line) { return line != null && NAG.matcher(line).find(); }
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

    // ── Korean that reads ──
    private static final Pattern CUT = Pattern.compile("^(도|는|은|을|를|에|의|과|로|으로|에서|랑)(\\s|[,.!?~])");
    private static final Pattern FOREIGN = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff\\u0400-\\u04ff\\u0e00-\\u0e7f]");
    /**
     * Whether a line reads as Korean (준: "가끔 한국어를 못알아듣게 말해"): not cut at its start (a particle first: "도 구름 낀
     * 사천…"), no Chinese, Japanese, Cyrillic or Thai letters slipped in, and mostly Hangul.
     */
    public static boolean readable(String line) {
        if (line == null || line.isBlank()) return false;
        String t = line.strip();
        if (CUT.matcher(t).find() || FOREIGN.matcher(t).find()) return false;
        int hangul = 0, letters = 0;
        for (int i = 0; i < t.length(); i++) { char ch = t.charAt(i); if (Character.isLetter(ch)) { letters++; if (ch >= '\uac00' && ch <= '\ud7a3') hangul++; } }
        return letters > 0 && hangul * 2 >= letters;
    }

    // ── its name, misheard ──
    /** How the phone's recognizer and Whisper hear 퍼레 (준: "퍼레가 자기 이름을 못알아듣고 자꾸 벌레 버려 이런걸로 알아들어"). */
    private static final String LIKE = "벌레|벌래|버레|버래|버려|퍼래|퍼려|펄레|펄래|뻐레|빠레|페레|파레|퍼리|프레|포레|허레";
    /** Said as a call anywhere (벌레야, 퍼래야): never meant as a bug. 버려야 and 거래야 are words too, so only at the start. */
    private static final Pattern CALLED = Pattern.compile("(^|[\\s,.!?~])(?:벌레|벌래|버레|버래|퍼래|퍼려|펄레|펄래|뻐레|빠레|페레|파레|퍼리|프레|포레|허레)(야|아)(?=$|[\\s,.!?~])");
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
    private static final Pattern ASKS = Pattern.compile("(내|나의|제)\\s*(비밀|비번|비밀번호|암호|아이디|계정|로그인|권한|계좌|카드|번호|주소|고민|걱정)|기억나|기억해\\?|뭐라고\\s*했|뭐라 했|알고\\s*있|알려\\s*줘.*(나|내)|(나|내)에\\s*대해");
    /** Whether what the user asked may need what is private (then the fingerprint first). */
    public static boolean asksPersonal(String said) { return said != null && ASKS.matcher(said).find(); }

    // ── what to bring to mind now ──
    /**
     * The memories to bring to mind now, at most n: by how much they matter, how recent they are (two weeks to fade by
     * half), and how much they share with what is going on (context: the hour, the weather, apps, what was said); a memory
     * brought up in the last day waits (shown: memory time → when last brought up); private ones only when unlocked.
     */
    public static List<BuddyMemory.Entry> recall(List<BuddyMemory.Entry> all, String context, long now, Map<Long, Long> shown, boolean unlocked, int n) {
        String ctx = context == null ? "" : context;
        List<BuddyMemory.Entry> pool = new ArrayList<>();
        Map<BuddyMemory.Entry, Double> score = new HashMap<>();
        for (BuddyMemory.Entry e : all) {
            if (e.secret() && !unlocked) continue;
            if ("profile".equals(e.kind()) || "promise".equals(e.kind())) continue; // given apart
            Long last = shown == null ? null : shown.get(e.at());
            if (last != null && now - last < 24 * 3_600_000L) continue;
            double age = Math.max(0, now - e.at()) / (14 * 86_400_000.0);
            double s = e.weight() + Math.pow(0.5, age) * 1.5 + similarity(e.text(), ctx) * 4;
            score.put(e, s); pool.add(e);
        }
        pool.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
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
    /** What a night's digest wrote: the user's profile, line by line, and the day as one memory (or ""). */
    public record Dream(List<String> profile, String episode) {}
    private static final Pattern LINE = Pattern.compile("^\\s*[-•*]?\\s*(프로필|추억)\\s*[:：]\\s*(.+)$");
    public static Dream dream(String text) {
        List<String> profile = new ArrayList<>(); String episode = "";
        if (text != null) for (String line : text.replaceAll("(?s)<think>.*?</think>", "").split("\\n")) {
            Matcher m = LINE.matcher(line); if (!m.find()) continue;
            String v = m.group(2).replaceAll("[\"“”*]", "").strip();
            if (v.isEmpty() || v.startsWith("없음") || v.length() > 80 || v.matches(".*\\d{4,}.*")) continue;
            if ("추억".equals(m.group(1))) { if (episode.isEmpty()) episode = v; }
            else if (profile.size() < 12 && !profile.contains(v)) profile.add(v);
        }
        return new Dream(profile, episode);
    }
}
