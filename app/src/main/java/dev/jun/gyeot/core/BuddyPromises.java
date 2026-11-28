package dev.jun.gyeot.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 퍼레's promises made real (기억 2.0: "내일 아침에 알려 줄게", "비 오면 말해 줄게" were only words): a promise in an answer that
 * is bound to a time or to an event is kept only when something real keeps it, a 조건 알림 (when_event, core/Triggers) or a
 * 예약 (schedule_job, core/WorkSchedules), an alarm or a timer. When the user asked for it and the brain wrote no thing to
 * do, the thing is made from the user's own words (backing); when it cannot be kept (the weather is no 조건 알림 yet, a time
 * as vague as "나중에"), the promise is taken out of the answer and 퍼레 says so plainly (without, honest). Pure Java.
 */
public final class BuddyPromises {
    private BuddyPromises() {}
    /** What a promise waits for: a time, an event the phone can hear, or the weather (which no 조건 알림 watches yet). */
    public enum Kind { TIME, EVENT, WEATHER }
    /** A promise found: its kind, the sentence that makes it, and the words of when. */
    public record Promise(Kind kind, String sentence, String when) {}

    /** 퍼레 saying it will tell, remind, wake or check in later. */
    private static final Pattern WILL = Pattern.compile("(?:알려|말해|깨워|챙겨|불러|얘기해|이야기해|리마인드해|상기시켜|연락해|전해|알림\\s*(?:보내|띄워))\\s*(?:줄게|드릴게|줄께|드릴께|둘게)"
        + "|깨울게|챙길게|알려\\s*줄\\s*게|잊지\\s*않고\\s*(?:알려|말해|챙겨)|기억했다가|기억해\\s*뒀다가|리마인드\\s*할게|알림\\s*(?:줄게|보낼게|띄울게|드릴게)|다시\\s*물어볼게"
        + "|\\bI(?:'ll| will)\\s+(?:remind|tell|let you know|wake|ping)\\b", Pattern.CASE_INSENSITIVE);
    /** The weather as a condition ("비 오면", "눈 내리면", "미세먼지 나쁘면"). */
    private static final Pattern WEATHER = Pattern.compile("(?:비|눈|소나기|미세\\s*먼지|초미세\\s*먼지|황사|태풍|장마|날씨)\\s*(?:가|이)?\\s*(?:오면|오거든|올\\s*때|내리면|나쁘면|좋으면|그치면|많으면|심하면|오는\\s*날)");
    /** An event the phone can hear: arriving, leaving, getting in the car, a charge, a delivery, a price. */
    private static final Pattern EVENT = Pattern.compile("(?:[가-힣A-Za-z0-9]+\\s*)?(?:도착하면|도착해서|출발하면|나가면|나서면|떠나면|들어가면|들어오면|타면|내리면|연결되면|충전\\s*(?:이\\s*)?다\\s*되면|충전되면|꽂으면|뽑으면|배터리[가-힣\\s\\d%]*?(?:되면|떨어지면)|배달[가-힣\\s]*?(?:오면|출발하면|도착하면)|택배[가-힣\\s]*?(?:오면|도착하면)|넘으면|떨어지면|오르면|오면|가면)");
    /** A time said plainly enough to set: a day, an hour, a part of the day, minutes or hours from now. */
    private static final Pattern TIME = Pattern.compile("(?:내일|모레|글피|다음\\s*주|이번\\s*주|주말|[월화수목금토일]요일|\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|\\d{1,2}\\s*시|\\d+\\s*(?:분|시간)\\s*(?:뒤|후)|(?:아침|점심|저녁|밤|새벽|오전|오후)\\s*에|자기\\s*전에|출근\\s*전에|이따\\s*(?:가)?\\s*\\d)");
    /** A time too vague to set ("나중에", "이따가", "다음에"). */
    private static final Pattern VAGUE = Pattern.compile("(?:나중에|이따가?|조금\\s*(?:있다가|이따)|다음에|언젠가|시간\\s*(?:나면|되면))");
    private static final Pattern SENTENCE = Pattern.compile("[^.!?~…\\n]+[.!?~…]*");
    /**
     * The promise in an answer bound to a time or an event ("내일 아침에 알려 줄게", "집에 도착하면 말해 줄게", "비 오면 말해 줄게",
     * "나중에 알려 줄게"), or null: an offer ("알려 줄까?"), a promise to answer now ("내가 알려 줄게: …") and its "할일:" line are
     * none.
     */
    public static Promise find(String answer) {
        if (answer == null || answer.isBlank()) return null;
        for (String line : answer.split("\n")) {
            String l = line.strip();
            if (l.startsWith("할일") || l.startsWith("할 일") || l.startsWith("{")) continue;
            Matcher s = SENTENCE.matcher(l);
            while (s.find()) {
                String one = s.group().strip();
                if (one.isEmpty() || !WILL.matcher(one).find() || one.matches("(?s).*(?:까|까요|래)\\s*[?？]?[!~]*$") || one.endsWith("?")) continue;
                Matcher w = WEATHER.matcher(one);
                if (w.find()) return new Promise(Kind.WEATHER, one, w.group().strip());
                Matcher e = EVENT.matcher(one);
                if (e.find() && !e.group().matches("\\s*(?:비|눈)\\s*(?:가|이)?\\s*오면")) return new Promise(Kind.EVENT, one, e.group().strip());
                Matcher t = TIME.matcher(one);
                if (t.find()) return new Promise(Kind.TIME, one, t.group().strip());
                Matcher v = VAGUE.matcher(one);
                if (v.find()) return new Promise(Kind.TIME, one, v.group().strip());
            }
        }
        return null;
    }
    /** The things to do that keep a promise: a 조건 알림 set, a 예약, an alarm, a timer, a calendar event with its reminder. */
    public static boolean backs(JSONObject act) {
        if (act == null) return false;
        String tool = act.optString("tool").strip().toLowerCase(Locale.ROOT);
        if (tool.equals("when_event")) return !Set.of("list", "show", "off", "delete", "cancel", "remove", "stop").contains(act.optString("command").strip().toLowerCase(Locale.ROOT));
        return Set.of("schedule_job", "alarm", "timer", "calendar_add").contains(tool);
    }
    /** The user asked to be told or reminded later ("내일 아침에 알려 줘", "집에 도착하면 말해 줘", "비 오면 알려 줄래?"). */
    private static final Pattern ASK = Pattern.compile("(?:알려|말해|깨워|챙겨|불러|얘기해|리마인드해|상기시켜)\\s*(?:줘|주라|줄래|주세요|줄\\s*수\\s*있|달라)|리마인드|리마인더|알림\\s*(?:줘|해\\s*줘|보내|띄워|설정)|잊지\\s*않게|잊어버리지\\s*않게");
    public static boolean asked(String question) {
        if (question == null || !ASK.matcher(question).find()) return false;
        return WEATHER.matcher(question).find() || EVENT.matcher(question).find() || TIME.matcher(question).find();
    }

    /**
     * The thing to do that keeps it, made from the user's words (question: what they asked; now: the phone's time), or null
     * when it cannot be kept: an event → a 조건 알림 told it (when_event, then tell: BuddyTriggers reads the condition and what
     * to say from the same words); a time → a 예약 once at that day and time (schedule_job: the reminder said then); the
     * weather, a vague time or a time gone by → null.
     */
    public static JSONObject backing(String question, Promise p, ZonedDateTime now) {
        String q = question == null ? "" : question.strip();
        if (p == null || q.isEmpty() || p.kind() == Kind.WEATHER || WEATHER.matcher(q).find()) return null;
        Matcher e = EVENT.matcher(q);
        if (p.kind() == Kind.EVENT || e.find() && !TIME.matcher(q).find()) {
            Matcher ev = EVENT.matcher(q);
            String when = ev.find() ? ev.group().strip() : p.when();
            return Jsons.obj("tool", "when_event", "when", when, "then", "tell");
        }
        ZonedDateTime at = at(q, now);
        if (at == null) at = at(p.sentence(), now);
        if (at == null || !at.isAfter(now.plusMinutes(1))) return null;
        String repeat = q.matches("(?s).*(?:매일|날마다|아침마다|저녁마다|밤마다).*") ? "daily" : "once";
        return Jsons.obj("tool", "schedule_job", "request", "지금 사용자에게 알려 줄 시간이야. 사용자가 이렇게 부탁했어: ‘" + q + "’. 부탁한 걸 한두 문장으로 짧게 전해 줘.",
            "repeat", repeat, "date", at.toLocalDate().toString(), "time", String.format(Locale.ROOT, "%02d:%02d", at.getHour(), at.getMinute()));
    }
    private static final Pattern CLOCK = Pattern.compile("(오전|오후|아침|점심|저녁|밤|새벽|낮)?\\s*(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?"),
        AFTER = Pattern.compile("(\\d+)\\s*(분|시간)\\s*(?:뒤|후)"), WEEKDAY = Pattern.compile("(다음\\s*주\\s*)?([월화수목금토일])요일");
    /** When the words say, as the next such moment from now; null when they give no day or hour that can be set. */
    static ZonedDateTime at(String words, ZonedDateTime now) {
        String w = words == null ? "" : words;
        Matcher after = AFTER.matcher(w);
        if (after.find()) { long n = Long.parseLong(after.group(1)); return after.group(2).equals("분") ? now.plusMinutes(n).withSecond(0).withNano(0) : now.plusHours(n).withSecond(0).withNano(0); }
        LocalDate today = now.toLocalDate(), day = null;
        if (w.contains("모레")) day = today.plusDays(2); else if (w.contains("글피")) day = today.plusDays(3); else if (w.contains("내일")) day = today.plusDays(1);
        Matcher date = Pattern.compile("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일").matcher(w);
        if (day == null && date.find()) day = BuddyTricks.day(date.group(1) + "월 " + date.group(2) + "일", today);
        Matcher wd = WEEKDAY.matcher(w);
        if (day == null && wd.find()) {
            DayOfWeek d = DayOfWeek.of("월화수목금토일".indexOf(wd.group(2)) + 1);
            day = wd.group(1) != null ? today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(d)) : today.with(TemporalAdjusters.nextOrSame(d));
        }
        if (day == null && w.contains("주말")) day = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        int hour = -1, minute = 0;
        Matcher c = CLOCK.matcher(w);
        if (c.find()) {
            hour = Integer.parseInt(c.group(2)); minute = c.group(3) != null ? Integer.parseInt(c.group(3)) : c.group(4) != null ? 30 : 0;
            String part = c.group(1) == null ? "" : c.group(1);
            if (hour < 12 && part.matches("오후|저녁|밤|낮") && !(part.equals("낮") && hour >= 11)) hour += 12;
            // No 오전·오후 and today: the next such hour from now ("8시에" said at 3 pm is 8 pm).
            if (part.isEmpty() && hour >= 1 && hour < 12 && (day == null || day.equals(today)) && hour <= now.getHour()) hour += 12;
        } else if (w.contains("아침")) hour = 8;
        else if (w.contains("점심")) hour = 12;
        else if (w.contains("저녁")) hour = 19;
        else if (w.matches("(?s).*자기\\s*전.*") || w.contains("밤")) hour = 22;
        else if (w.contains("출근 전") || w.contains("출근전")) hour = 8;
        else if (w.contains("오후")) hour = 15;
        else if (w.contains("오전")) hour = 9;
        if (hour < 0 || hour > 23 || minute > 59) { if (day == null) return null; hour = 9; }
        if (day == null) day = today;
        ZonedDateTime out = day.atTime(hour, minute).atZone(now.getZone());
        // An hour already gone today, with no day said: tomorrow's.
        if (!out.isAfter(now) && !w.matches("(?s).*(오늘|내일|모레|글피|요일|월\\s*\\d{1,2}\\s*일).*")) out = out.plusDays(1);
        return out;
    }
    /** The answer without the promise's sentence (its "할일:" line, if any, kept apart by the caller). */
    public static String without(String answer, Promise p) {
        if (answer == null || p == null) return answer == null ? "" : answer;
        String out = answer.replace(p.sentence(), " ").replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").strip();
        return out.replaceAll("^[,.!~…\\s]+", "").strip();
    }
    /** What 퍼레 says in place of a promise it cannot keep (asked: the user asked for it, so say what can be done instead). */
    public static String honest(Promise p, boolean asked) {
        if (p == null) return "";
        return switch (p.kind()) {
            case WEATHER -> asked ? "비·눈 같은 날씨는 아직 내가 지켜볼 수 없어서, 그때 알려 준다고 약속은 못 해. 매일 아침 날씨를 알려 주는 예약은 걸 수 있어." : "";
            case EVENT -> asked ? "그건 아직 조건 알림으로 걸 수가 없어서 약속은 못 해. ‘집에 도착하면’, ‘차에 타면’처럼 말해 주면 걸어 둘 수 있어." : "";
            case TIME -> asked ? "언제 알려 줄지 날짜나 시각을 말해 주면 그때 맞춰 예약을 걸어 둘 수 있어." : "";
        };
    }
}
