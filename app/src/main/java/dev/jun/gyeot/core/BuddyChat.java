package dev.jun.gyeot.core;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * How 퍼레와 대화 (BuddyTalk.chatText, HomeActivity.chatAnswer) reads a question and an answer, in plain Java so
 * tools/BrainRegression checks it: a question that wants a full answer (wantsLong), one about the world now that the web
 * answers (asksNow) unless a search is to be opened (opensSearch), an answer that only promises a thing (promised), the things
 * whose result 퍼레 says in its own words (informs), and whether those words keep the result's facts (faithful).
 */
public final class BuddyChat {
    private BuddyChat() {}

    // ── how long the answer may be ──
    private static final Pattern LONG = Pattern.compile("(설명|자세히|자세하게|방법|[는은]\\s*법|법\\s*(좀\\s*)?(알려|가르쳐)|어떻게\\s*(해|하|하면|하는|만들|써|쓰|고쳐|바꿔|시작)|차이|비교|장단점|장점|단점|목록|리스트|"
        + "정리해|정리\\s*좀|요약|써\\s*줘|써줘|써\\s*줄래|써\\s*봐|작성|글\\s*좀|편지|일기|소개\\s*글|(시|노래\\s*가사|가사)\\s*(를|좀)?\\s*(써|지어)|지어\\s*줘|추천|"
        + "\\d+\\s*(가지|개)|몇\\s*가지|단계|순서|예시|예를|뭐가\\s*있|어떤\\s*게\\s*있|계획\\s*(좀)?\\s*(짜|세워)|짜\\s*줘|가르쳐|알려\\s*줄래|원리|이유|왜\\s+.+[?？]?$|explain|list|compare|write)",
        Pattern.CASE_INSENSITIVE);
    /**
     * The user asks to explain, list, compare or write something (준: "핵심부터 2~6문장" cut a recipe or a letter short): then the
     * answer may be as long as it needs. Small talk ("안녕", "뭐해?", "심심해") stays short.
     */
    public static boolean wantsLong(String said) { return said != null && LONG.matcher(said.strip()).find(); }

    // ── the world now, answered from the web (Groq's search model) ──
    private static final Pattern NOW = Pattern.compile("(환율|달러\\s*(얼마|몇|환율|시세)|엔화|유로화?\\s*(얼마|환율)|원\\s*달러|"
        + "주가|주식\\s*(시세|가격)|코스피|코스닥|나스닥|다우\\s*지수|S&P|비트코인\\s*(얼마|시세|가격|지금)|이더리움\\s*(얼마|시세|가격)|(코인|금|유가|기름\\s*값|휘발유)\\s*(시세|가격|값)|"
        + "뉴스|속보|소식|헤드라인|무슨\\s*일\\s*(있|났)|"
        + "경기\\s*(결과|어떻게|이겼|졌|스코어|점수)|스코어|몇\\s*대\\s*몇|(이겼|졌)\\s*(어|냐|니|나)|우승\\s*(했|팀)|순위|"
        + "영업\\s*시간|몇\\s*시\\s*(까지|에)\\s*(해|하|열|닫|문|영업)|문\\s*(열었|여는|닫)|오늘\\s*(쉬어|쉬나|휴무|영업)|휴무일?|"
        + "날씨|기온|미세\\s*먼지|비\\s*(와|올|오)|눈\\s*(와|올|오)|"
        + "개봉|출시일|발표\\s*(했|됐)|최신\\s*(버전|소식))");
    // A question as it is typed: a question mark, a question word, or a question's ending ("비 오나", "몇 대 몇이냐").
    private static final Pattern ASKING = Pattern.compile("([?？]|얼마|어때|어떄|어떻|몇|뭐야|뭐예요|뭐\\s*있|뭐래|있나|됐어|됐냐|됐니|났어|나왔|알려\\s*줘|알려줘|알려\\s*줄래|"
        + "궁금|아는\\s*거\\s*있|냐$|니$|나요?$|까요?$|는지$|ㄴ지$)");
    /** Words that ask for a thing done, not a fact said: a thing to play, a job, a condition, a time to repeat. */
    private static final Pattern NOT_ASKING = Pattern.compile("(틀어|재생|켜\\s*줘|열어|보여\\s*줘|보내|공유|면\\s*알려|넘으면|되면|떨어지면|오르면|마다|매일|매주|평일|"
        + "정리해서|보고서|분석해|메모|기억해|일정|알람|타이머|예약|만들어|그려|번역)");
    /**
     * A question about the world now (준's list: 환율, 날씨, 주가, 뉴스, 경기 결과, 영업시간 …) that 퍼레 would otherwise answer by
     * opening a Google search: the web answers it (BuddyTalk.webAnswer). Not a request to open a search (opensSearch), a thing
     * to do ("뉴스 틀어 줘", "비트코인 1억 넘으면 알려 줘", "평일마다 뉴스 정리해 줘") or plain talk ("날씨 좋네").
     */
    public static boolean asksNow(String said) {
        // The past ("어제 본 뉴스 뭐였지") is the day's own record (day_recall), not the web.
        if (said == null || said.isBlank() || opensSearch(said) || DayLog.asksPast(said)) return false;
        String s = said.strip();
        return NOW.matcher(s).find() && ASKING.matcher(s).find() && !NOT_ASKING.matcher(s).find() && !s.matches("(?s).*(좋네|좋다|싫다|덥다|춥다|최고|짜증)[.!~ㅋㅎ\\s]*$");
    }
    private static final Pattern OPEN_SEARCH = Pattern.compile("(검색|구글|google|네이버\\s*에서|네이버로|다음\\s*에서|검색창|찾아\\s*봐\\s*줘|찾아\\s*보게|브라우저|인터넷\\s*(으로|에서)\\s*(열|찾아|봐)|웹\\s*(에서|으로)\\s*(열|봐))", Pattern.CASE_INSENSITIVE);
    /** The user asks for a search opened ("환율 검색해 줘", "구글에서 찾아봐 줘"): then the 검색 tool opens it, as asked. */
    public static boolean opensSearch(String said) { return said != null && OPEN_SEARCH.matcher(said).find(); }

    // ── an answer that only promises ──
    private static final Pattern PROMISE = Pattern.compile("((찾아|알아|확인해|검색해|살펴|조사해|열어|틀어|켜|꺼|맞춰|보내|적어|기억해|계산해|알려|불러|걸어|찍어|넣어|담아|바꿔|정해|골라|던져|굴려|세어)\\s*(볼게|줄게|드릴게|둘게|볼께|줄께|놓을게)"
        + "|찾을게|찾아볼게|알아볼게|확인할게|검색할게|열게|틀게|켤게|끌게|보낼게|맞출게|적을게|기억할게|계산할게|알려\\s*줄게|금방\\s*(올게|알려)|잠깐만\\s*기다려|조금만\\s*기다려"
        + "|\\bI(?:'ll| will)\\s+(?:check|look|find|search|open|set|play|send|start|turn|get|do)\\b|\\blet me (?:check|look|find|search|open|see)\\b)", Pattern.CASE_INSENSITIVE);
    /**
     * An answer that says a thing will be done ("찾아볼게!", "알아볼게", "확인해 볼게", "열어 줄게", "I'll check") with no "할일:" line
     * (BuddyTalk.chatText asks once more, the whole list of what 퍼레 can do and "do it now"). A question back ("찾아볼까?") is not one.
     */
    public static boolean promised(String answer) {
        if (answer == null || answer.isBlank()) return false;
        for (String line : answer.split("\n")) {
            String l = line.strip();
            if (l.startsWith("할일") || l.startsWith("할 일")) return false;
            Matcher m = PROMISE.matcher(l);
            if (m.find()) return true;
        }
        return false;
    }

    // ── the things whose result 퍼레 says in its own words ──
    /** The tools that find or work out something to say (the rest only do something: open, set, play, turn on …). */
    public static final Set<String> INFORMS = Set.of("day_recall", "calculator", "world_time", "count_days", "coin", "dice", "pick");
    /**
     * Whether this thing's result is something to tell (INFORMS, 퍼레's stopwatch read or stopped, the conditions set listed):
     * then its words go back to the brain that chose it, to be said naturally (BuddyTalk.toolAnswer).
     */
    public static boolean informs(JSONObject act) {
        if (act == null) return false;
        String tool = act.optString("tool").strip().toLowerCase(Locale.ROOT);
        if (INFORMS.contains(tool)) return true;
        String command = act.optString("command").strip().toLowerCase(Locale.ROOT);
        if (tool.equals("stopwatch")) return Set.of("check", "lap", "read", "status", "stop", "end").contains(command);
        return tool.equals("when_event") && command.equals("list");
    }
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) { String n = m.group().replace(",", ""); if (n.contains(".")) n = n.replaceAll("\\.?0+$", ""); out.add(n); }
        return out;
    }
    /**
     * The brain's words keep the result's facts: no number the result and the question do not have, at least one of the
     * result's numbers when it has any, and the side of a coin as it fell. Otherwise the result's own words are shown.
     */
    public static boolean faithful(String result, String question, String answer) {
        if (answer == null || answer.isBlank() || result == null) return false;
        Set<String> said = numbers(answer), known = numbers(result), asked = numbers(question);
        for (String n : said) if (!known.contains(n) && !asked.contains(n)) return false;
        if (!known.isEmpty() && said.stream().noneMatch(known::contains)) return false;
        for (String[] side : new String[][]{{"앞면", "뒷면"}, {"뒷면", "앞면"}})
            if (result.contains(side[0]) && !result.contains(side[1]) && (!answer.contains(side[0]) || answer.contains(side[1]))) return false;
        return true;
    }
}
