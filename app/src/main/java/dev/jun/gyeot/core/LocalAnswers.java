package dev.jun.gyeot.core;

import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 기기 안 (두뇌 배분's DEVICE kind, core/BrainRoutes): the few things 퍼레 answers on the phone with no model at all, so they
 * come at once and cost nothing: the time, today's date, a sum, a timer, a coin or a die, the flashlight, an app opened or
 * closed by its name, and the weather the home screen fetched in the last half hour. Only a short thing said as a whole
 * counts ("지금 몇 시야", "3분 타이머 맞춰 줘"); anything more ("몇 시에 만날까?", "3분 타이머 말고 5분", "주사위 꺼내 줘") goes
 * to the brain as before. The things to do are the very tools the brain would call (BuddyTools), so they are done, confirmed
 * and told the same way; 퍼레 자동 점검's words layer checks every scenario agrees (BuddyCheck.word). Pure Java.
 */
public final class LocalAnswers {
    private LocalAnswers() {}
    /** rule: which one answered (for the ledger and the report); say: the words, or "" when the thing to do says them; act: a BuddyTools call, or null. */
    public record Answer(String rule, String say, JSONObject act) {}
    /** The weather the home screen keeps (Weather.now): where, how warm, the sky in words, today's high and low (null unknown), and how old it is. */
    public record Sky(String place, int temp, String sky, Integer hi, Integer lo, long ageMs) {}
    /** Weather older than this is not said: the brain (or the weather app) answers instead. */
    public static final long WEATHER_FRESH_MS = 30 * 60_000L;
    /** Longer than this (letters, no spaces), what was said is talk. */
    static final int LONGEST = 24;

    private static final Pattern NAME = Pattern.compile("^(?:들)?퍼레(?:야|아)?[\\s,，!.]*");
    private static final Pattern END = Pattern.compile("[\\s.!?？。~…]+$");
    private static final String PLEASE = "(?:\\s*(?:줘|줄래|줄래요|주세요|줘요|봐|봐요|줄 수 있어))?";
    private static final Pattern TIME = Pattern.compile("^(?:지금|현재)?\\s*(?:몇\\s*시(?:\\s*몇\\s*분)?|시간|시각)\\s*(?:이야|야|예요|에요|이에요|인가요|인가|임|이지|지|니|돼|됐어|알려" + PLEASE + "|말해" + PLEASE + ")?$");
    private static final Pattern DATE = Pattern.compile("^오늘\\s*(?:은|이)?\\s*(?:며칠|몇\\s*일|날짜|무슨\\s*요일|몇\\s*요일|몇\\s*월\\s*며칠|몇\\s*월\\s*몇\\s*일)\\s*(?:이야|야|예요|에요|이에요|인가요|이지|지|니|알려" + PLEASE + ")?$");
    private static final Pattern CALC_TAIL = Pattern.compile("^(.+?)\\s*(?:(?:은|는|이|가)\\s*)?(?:얼마(?:야|예요|에요|지|니|일까|인가요)?|뭐(?:야|예요|지)?|몇이야)?\\s*(?:계산(?:해" + PLEASE + "|해)?)?\\s*=?$");
    private static final Pattern CALC_ASKED = Pattern.compile("얼마|계산|뭐(?:야|예요|지)?$|몇이야|=");
    private static final Pattern CALC_WORD = Pattern.compile("더하기|빼기|곱하기|나누기|플러스|마이너스|퍼센트|프로|제곱|[+*/×÷^%xX]|\\d\\s*의\\s*\\d");
    /** A phone number, a date or a time written with dashes and dots: never a sum. */
    private static final Pattern NOT_SUM = Pattern.compile("\\d{2,4}\\s*-\\s*\\d{3,4}\\s*-\\s*\\d{4}|\\d{4}\\s*[-.]\\s*\\d{1,2}\\s*[-.]\\s*\\d{1,2}|\\d{1,2}\\s*:\\s*\\d{2}");
    private static final String SET = "(?:\\s*좀)?\\s*(?:맞춰|맞춰" + PLEASE + "|설정해" + PLEASE + "|설정|켜" + PLEASE + "|켜|시작해" + PLEASE + "|시작|돌려" + PLEASE + "|해" + PLEASE + "|부탁해)?(?:요)?";
    private static final String SPAN = "(?:(\\d{1,2})\\s*시간)?\\s*(?:(\\d{1,3})\\s*분)?\\s*(?:(\\d{1,4})\\s*초)?";
    private static final Pattern TIMER_FIRST = Pattern.compile("^" + SPAN + "\\s*(?:짜리\\s*)?타이머" + SET + "$");
    private static final Pattern TIMER_AFTER = Pattern.compile("^타이머\\s*(?:를|을)?\\s*" + SPAN + "\\s*(?:으로|로)?" + SET + "$");
    private static final Pattern COIN = Pattern.compile("^(?:동전\\s*(?:을|좀)?\\s*(?:던져|던져" + PLEASE + "|던지기)|동전\\s*던지기\\s*해" + PLEASE + "|코인\\s*토스(?:\\s*해" + PLEASE + ")?)(?:요)?$");
    private static final Pattern DICE = Pattern.compile("^주사위\\s*(?:를|좀)?\\s*(?:굴려|굴려" + PLEASE + "|던져|던져" + PLEASE + ")(?:요)?$");
    private static final Pattern TORCH = Pattern.compile("^(?:손전등|플래시|후레시|플래쉬)\\s*(?:을|를|좀)?\\s*(켜|꺼)" + PLEASE + "(?:요)?$");
    private static final Pattern WEATHER = Pattern.compile("^(?:오늘|지금|현재|여기)?\\s*(?:날씨|기온|온도)\\s*(?:는|가|은)?\\s*(?:어때|어때요|어떠니|어떄|어떻게\\s*돼|알려" + PLEASE + "|몇\\s*도(?:야|예요)?)?$");
    private static final Pattern LATER = Pattern.compile("\\S면\\s|(?:할|갈|올|탈|볼|잘|먹을|나갈|들어갈|일어날)\\s*때|마다|매번|매일|평일|주말|(?:뒤|후|전)에\\s|있다가|이따");
    /** Names that are 퍼레's own tools, not an app to open by its label (녹음 → recorder, 사진첩 → gallery, 홈페이지 → open_url …). */
    private static final Pattern NOT_AN_APP = Pattern.compile("녹음|녹화|사진|갤러리|카메라|셀카|계산기|연락처|메모|다운로드|받은|파일|홈페이지|사이트|페이지|주소|설정|알람|타이머|시계|스톱워치|음악|노래|손전등|플래시|캘린더|일정|달력|화면|지도앱|^지도$|길|날씨|번역|메일|문자|전화|^불$|조명|와이파이|블루투스|데이터|볼륨|소리|진동|무음|방해|에어컨|티비|퍼레");

    /** What 퍼레 answers itself for said at now, or null when the brain answers (sky: the weather kept, null for none). */
    public static Answer of(String said, ZonedDateTime now, Sky sky) {
        if (said == null || now == null) return null;
        String t = END.matcher(NAME.matcher(said.strip()).replaceFirst("")).replaceAll("").strip();
        String bare = t.replaceAll("\\s+", "");
        if (bare.isEmpty() || bare.codePointCount(0, bare.length()) > LONGEST) return null;
        // "…하면 …", "…마다 …", "…뒤에 …": a condition or a time to do it later (when_event, schedules) is the brain's.
        if (LATER.matcher(t).find()) return null;
        if (TIME.matcher(t).matches()) return new Answer("time", "지금 " + BuddyTricks.clock(now) + "야.", null);
        if (DATE.matcher(t).matches()) return new Answer("date", "오늘은 " + BuddyTricks.dateWords(now.toLocalDate(), now.toLocalDate()) + "이야.", null);
        Answer timer = timer(t); if (timer != null) return timer;
        if (COIN.matcher(t).matches()) return new Answer("coin", "", act("coin"));
        if (DICE.matcher(t).matches()) return new Answer("dice", "", act("dice"));
        Matcher torch = TORCH.matcher(t);
        if (torch.matches()) return new Answer("torch", "", Jsons.obj("tool", "torch", "on", "켜".equals(torch.group(1))));
        if (WEATHER.matcher(t).matches()) {
            if (sky == null || sky.ageMs() < 0 || sky.ageMs() > WEATHER_FRESH_MS) return null;
            String where = sky.place() == null || sky.place().isBlank() ? "" : sky.place().strip() + " ";
            String range = sky.hi() != null && sky.lo() != null ? " 오늘은 최고 " + sky.hi() + "도, 최저 " + sky.lo() + "도야." : "";
            return new Answer("weather", "지금 " + where + sky.temp() + "도, " + sky.sky() + "이야." + range, null);
        }
        Answer sum = sum(t); if (sum != null) return sum;
        // An app by its name: only 퍼레's own tool words go to the brain; the phone opens it only for an exact installed label.
        String open = DecisionRouting.appQuery(t);
        if (!open.isEmpty() && !NOT_AN_APP.matcher(open.replaceAll("\\s+", "")).find()) return new Answer("open_app", "", Jsons.obj("tool", "open_app", "app", open));
        String close = VoiceTurns.closing(t);
        if (close != null && !close.isEmpty() && !NOT_AN_APP.matcher(close.replaceAll("\\s+", "")).find()) return new Answer("close_app", "", Jsons.obj("tool", "close_app", "app", close));
        return null;
    }
    private static JSONObject act(String tool) { return Jsons.obj("tool", tool); }
    /** "3분 타이머", "타이머 1시간 30분 맞춰 줘", "10초 타이머 켜 줘": seconds from one second to a day. */
    private static Answer timer(String t) {
        Matcher m = TIMER_FIRST.matcher(t);
        if (!m.matches()) { m = TIMER_AFTER.matcher(t); if (!m.matches()) return null; }
        long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long minutes = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long seconds = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        // "1시간 반" (no 분 after 반) is not read here: the brain answers it.
        long total = hours * 3600 + minutes * 60 + seconds;
        if (total <= 0 || total > DeviceActions.TIMER_MAX_SECONDS) return null;
        return new Answer("timer", "", Jsons.obj("tool", "timer", "seconds", total));
    }
    /** "12 곱하기 7은?", "35000원의 15퍼센트 계산해 줘", "(3+4)^2 얼마야": the calculator tool with the expression as said. */
    private static Answer sum(String t) {
        if (NOT_SUM.matcher(t).find()) return null;
        Matcher m = CALC_TAIL.matcher(t);
        if (!m.matches()) return null;
        String expression = m.group(1).strip();
        if (!expression.matches(".*\\d.*")) return null;
        // A bare minus between numbers ("3-2") is a sum only when it was asked for as one ("3-2는 얼마야").
        boolean minus = expression.matches(".*\\d\\s*-\\s*[\\d(].*");
        if (!CALC_WORD.matcher(expression).find() && !(minus && CALC_ASKED.matcher(t).find())) return null;
        try { if (BuddyTricks.calc(expression) == null) return null; }
        catch (ArithmeticException byZero) { /* the calculator tool says it cannot divide by zero */ }
        return new Answer("calc", "", Jsons.obj("tool", "calculator", "expression", expression));
    }
}
