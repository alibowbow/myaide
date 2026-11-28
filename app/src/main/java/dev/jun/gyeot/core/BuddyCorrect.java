package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user setting 퍼레 right, taken in at once (기억 2.0: "아니 그거 아니고 …", "내 생일은 3월이야"): the memory it is about is
 * changed before 퍼레 answers, not only told to the learning brain for later, and a lesson is kept ("‘5월’로 잘못 알았는데
 * ‘3월’이래") so the same mistake is not made again. What is set right: a time, a day or a date the user gives anew (its
 * place in the memory changed, the rest kept), or the whole memory said anew, the opposite, or a fact 퍼레 had wrong in its
 * last answer. A question, a request or a choice ("아니 커피 말고 차 마실래") changes nothing. Pure Java.
 */
public final class BuddyCorrect {
    private BuddyCorrect() {}
    /**
     * A correction found: the memory set right (null: none was known), its new words ("" none: only the lesson), the lesson,
     * and whether the new words are kept as a new memory (nothing known that they set right).
     */
    public record Fix(BuddyMemory.Entry target, String text, String lesson, boolean added) {}

    /** The words that open a correction, one or two of them ("아니 그거 아니고 …"). */
    private static final Pattern LEAD = Pattern.compile("^\\s*(?:아니(?:야|요|거든|지|라)?|아냐|아뇨|노노|땡|틀렸어|틀렸고|틀린\\s*거야|잘못\\s*알았(?:어|네|구나)?|잘못\\s*알고\\s*있(?:어|네)|그게\\s*아니(?:라|고|야)|그거\\s*아니(?:라|고|야)|그건\\s*아니(?:라|고|야)|그거\\s*말고|그건\\s*말고)(?:[\\s,.!~…]+|$)");
    /** Whether the user's words open as a correction ("아니", "그거 아니고", "틀렸어", "잘못 알았어"). */
    public static boolean marked(String said) { return said != null && LEAD.matcher(said).find(); }
    /** The words after the correction's opening ("아니 그거 아니고 내 생일은 3월이야" → "내 생일은 3월이야"). */
    public static String body(String said) {
        String b = said == null ? "" : said.strip();
        for (int i = 0; i < 3; i++) { Matcher m = LEAD.matcher(b); if (!m.find() || m.end() == 0) break; b = b.substring(m.end()).strip(); }
        return b;
    }

    // ── the places a correction gives anew: a time, a day, a date ──
    enum Slot { DATE, MONTH, WEEKDAY, TIME, DAY }
    private static final Map<Slot, Pattern> SLOTS = new EnumMap<>(Map.of(
        Slot.DATE, Pattern.compile("\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일"),
        Slot.MONTH, Pattern.compile("(?<![0-9])\\d{1,2}\\s*월(?!\\s*\\d)(?!요일)"),
        Slot.WEEKDAY, Pattern.compile("[월화수목금토일]요일"),
        Slot.TIME, Pattern.compile("(?:(?:오전|오후|아침|저녁|밤|새벽|낮)\\s*)?\\d{1,2}\\s*시(?:\\s*\\d{1,2}\\s*분|\\s*반)?"),
        Slot.DAY, Pattern.compile("오늘|내일|모레|글피")));
    /** The times, days and dates a text gives, each its first (as written). */
    static Map<Slot, String> slots(String text) {
        Map<Slot, String> out = new EnumMap<>(Slot.class);
        String t = text == null ? "" : text;
        for (Map.Entry<Slot, Pattern> s : SLOTS.entrySet()) { Matcher m = s.getValue().matcher(t); if (m.find()) out.put(s.getKey(), m.group()); }
        // A date holds its month: the month alone is not another place.
        if (out.containsKey(Slot.DATE)) out.remove(Slot.MONTH);
        return out;
    }
    private static String flat(String s) { return s == null ? "" : s.replaceAll("\\s+", ""); }
    /** The text with each of these places given anew (its first time, day or date of that kind replaced). */
    static String substituted(String text, Map<Slot, String> anew) {
        String out = text;
        for (Map.Entry<Slot, String> s : anew.entrySet()) {
            Matcher m = SLOTS.get(s.getKey()).matcher(out);
            if (!m.find()) continue;
            String value = s.getValue().strip();
            // "3시" for "오후 2시": the part of the day kept (the user gave the hour, not the half of the day).
            Matcher part = Pattern.compile("^(오전|오후|아침|저녁|밤|새벽|낮)\\s*").matcher(m.group());
            if (s.getKey() == Slot.TIME && part.find() && !value.matches("^(오전|오후|아침|저녁|밤|새벽|낮).*")) value = part.group() + value;
            out = out.substring(0, m.start()) + value + out.substring(m.end());
        }
        return out;
    }

    // ── the words said anew, as 퍼레 keeps a memory ──
    /** A request, a wish to do, a choice or a question: not a fact set right ("차 마실래", "그거 틀어 줘", "할까?"). */
    private static final Pattern NOT_A_FACT = Pattern.compile("([?？]|(?:할|볼|갈|먹을|마실|들을|살|쓸|입을|잘|쉴)래[.!~]*$|줘[.!~]*$|주세요|하자[.!~]*$|할까|까요?[.!~]*$|어때[.!~]*$|해\\s*봐|틀어|열어|켜|꺼|알려|찾아|검색)");
    /**
     * The words said anew as a memory ("내 생일은 3월이야" → "사용자의 생일은 3월이래", "나 커피 끊었어" → "사용자는 커피 끊었대"),
     * their own words kept when the ending does not turn ("사용자가 고쳐 말했어: …"); null for a request, a question, a choice,
     * nothing about anything, a number of four digits or more (an account, a code), or more than seventy letters.
     */
    public static String statement(String body) {
        String b = body == null ? "" : body.strip().replaceAll("[.!~…ㅋㅎㅠㅜ^\\s]+$", "").strip();
        if (b.length() < 2 || NOT_A_FACT.matcher(b).find() || b.contains("말고") || b.matches(".*\\d{4,}.*") || BuddyMind.topics(b).isEmpty()) return null;
        String rest; String subject;
        Matcher own = Pattern.compile("^(?:내|제|나의)\\s+").matcher(b), me = Pattern.compile("^(?:나는|난|나|저는|전|저)\\s+").matcher(b);
        if (own.find()) { subject = "사용자의 "; rest = b.substring(own.end()); }
        else if (me.find()) { subject = "사용자는 "; rest = b.substring(me.end()); }
        else { subject = "사용자는 "; rest = b; }
        String told = told(rest);
        String out = told == null ? "사용자가 고쳐 말했어: " + rest.strip() : subject + told;
        return out.length() <= 70 ? out : null;
    }
    /** "3월이야" → "3월이래", "끊었어" → "끊었대", "좋아해" → "좋아한대"; null when the ending does not turn. */
    static String told(String rest) {
        String r = rest.strip();
        String[][] endings = {{"이에요", "이래"}, {"예요", "래"}, {"이거든", "이래"}, {"이야", "이래"}, {"였어", "였대"}, {"었어", "었대"}, {"았어", "았대"}, {"했어", "했대"}, {"거든", "대"}, {"좋아해", "좋아한대"}, {"싫어해", "싫어한대"}};
        for (String[] e : endings) if (r.endsWith(e[0])) return r.substring(0, r.length() - e[0].length()) + e[1];
        if (r.endsWith("야")) return r.substring(0, r.length() - 1) + "래";
        if (r.endsWith("해")) return r.substring(0, r.length() - 1) + "한대";
        return null;
    }

    /**
     * The correction in what the user said (lastAnswer: 퍼레's answer before it, "" none), or null when there is none: the
     * memory it sets right (one the user told: a fact, a liking, a plan, a promise, a note of the night; a private one only
     * when unlocked), chosen by what they share (a topic, a time or day of the same kind given anew, the opposite) and, for
     * words opened as a correction ("아니 …"), by 퍼레's last answer; with no memory, words opened as a correction that set
     * right 퍼레's last answer are kept as a lesson (and as a new memory when they are a fact).
     */
    public static Fix fix(List<BuddyMemory.Entry> all, String said, String lastAnswer, boolean unlocked) {
        if (said == null || said.isBlank()) return null;
        boolean marked = marked(said);
        String body = body(said); if (body.length() < 2) return null;
        Map<Slot, String> mine = slots(body);
        String statement = statement(body), last = lastAnswer == null ? "" : lastAnswer;
        // A question or a request is never a correction (its places included: "아니 내일 몇 시야?").
        if (statement == null && (NOT_A_FACT.matcher(body).find() || body.contains("말고") && mine.isEmpty())) return null;
        BuddyMemory.Entry best = null; Map<Slot, String> bestAnew = Map.of(); double most = 0;
        for (BuddyMemory.Entry e : all) {
            if (!BuddyRecall.TOLD.contains(e.kind()) || e.done() || e.secret() && !unlocked) continue;
            boolean topic = BuddyMind.shareTopic(e.text(), body), context = marked && !last.isBlank() && BuddyMind.shareTopic(e.text(), last);
            Map<Slot, String> theirs = slots(e.text()), anew = new EnumMap<>(Slot.class);
            for (Map.Entry<Slot, String> s : mine.entrySet()) if (theirs.containsKey(s.getKey()) && !flat(theirs.get(s.getKey())).equals(flat(s.getValue()))) anew.put(s.getKey(), s.getValue());
            boolean opposite = statement != null && BuddyMind.negates(e.text(), statement);
            boolean fits = topic && (!anew.isEmpty() || opposite || marked && statement != null) || context && !anew.isEmpty();
            if (!fits) continue;
            double score = (topic ? 2 : 0) + (context ? 1 : 0) + (anew.isEmpty() ? 0 : 1.5) + (opposite ? 1.5 : 0) + BuddyMind.similarity(e.text(), body);
            if (score > most) { most = score; best = e; bestAnew = anew; }
        }
        if (best != null) {
            String text = !bestAnew.isEmpty() ? substituted(best.text(), bestAnew) : statement;
            if (text == null || text.isBlank() || text.equals(best.text())) return null;
            return new Fix(best, text, lesson(best.text(), text), false);
        }
        // Nothing known: 퍼레's last answer had it wrong ("결혼식 토요일이지?" "아니 일요일이야").
        if (!marked || last.isBlank()) return null;
        // Its words a fact as 퍼레 keeps one, about more than a feeling ("괜찮아" is not a correction of "괜찮아?").
        boolean aboutLast = converted(statement) && BuddyMind.topics(body).size() >= 2 && BuddyMind.shareTopic(last, body);
        Map<Slot, String> lastSlots = slots(last);
        boolean placeAnew = false;
        for (Map.Entry<Slot, String> s : mine.entrySet()) if (lastSlots.containsKey(s.getKey()) && !flat(lastSlots.get(s.getKey())).equals(flat(s.getValue()))) placeAnew = true;
        if (!aboutLast && !placeAnew) return null;
        String lesson = "퍼레가 ‘" + cut(last.replaceAll("\\s+", " ").strip(), 30) + "’라고 했는데, 사용자가 ‘" + cut(body, 30) + "’라고 고쳐 줬어";
        return new Fix(null, aboutLast ? statement : "", lesson, aboutLast);
    }
    /** Words said anew whose ending turned into a memory's ("…이래", "…대"), not kept as they were said. */
    private static boolean converted(String statement) { return statement != null && !statement.startsWith("사용자가 고쳐 말했어"); }
    /** The lesson of a memory set right, as kept. */
    static String lesson(String before, String after) {
        return "‘" + cut(BuddyMind.toldBack(before), 30) + "’로 잘못 알았는데, 사용자가 ‘" + cut(BuddyMind.toldBack(after), 30) + "’로 고쳐 줬어";
    }
    /**
     * The memories with the correction applied: the memory set right said anew (its kind and its time kept, something told:
     * weight 2 at least), or the new words kept as their kind (BuddyRecall.sort); then the lesson, unless one nearly the same
     * is kept already. The list as it was when nothing changed.
     */
    public static List<BuddyMemory.Entry> apply(List<BuddyMemory.Entry> all, Fix fix, long now) {
        if (fix == null) return all;
        List<BuddyMemory.Entry> out = all;
        if (fix.target() != null && !fix.text().isBlank()) out = BuddyMemory.replace(out, fix.target().at(), fix.text(), now, fix.target().secret() || BuddyMind.secret(fix.text()), Math.max(2, fix.target().weight()));
        else if (fix.added() && !fix.text().isBlank()) out = BuddyMemory.add(out, now, BuddyRecall.sort(fix.text()), fix.text(), 0, 2, BuddyMind.secret(fix.text()));
        if (fix.lesson() != null && !fix.lesson().isBlank()) {
            boolean known = false;
            for (BuddyMemory.Entry e : out) if (BuddyRecall.LESSON.equals(e.kind()) && BuddyMind.similarity(e.text(), fix.lesson()) >= 0.8) known = true;
            if (!known) out = BuddyMemory.add(out, now + 1, BuddyRecall.LESSON, fix.lesson(), 0, 2, BuddyMind.secret(fix.lesson()));
        }
        return out;
    }
    /** The line for the answer's rules: what was set right, already kept; take it in briefly and answer with the new fact. */
    public static String line(Fix fix) {
        if (fix == null) return "";
        if (fix.target() != null) return "- 사용자가 방금 네가 알던 걸 고쳐 줬어. 예전: ‘" + fix.target().text() + "’ → 지금: ‘" + fix.text() + "’(벌써 고쳐서 기억했어). 짧게 받아들이고 새 사실로 답해. 사과를 길게 하지 마.\n";
        return "- 사용자가 방금 네 말을 고쳐 줬어(" + fix.lesson() + "). 짧게 받아들이고 고친 사실로 답해. 사과를 길게 하지 마.\n";
    }
    /** The lessons as the brain reads them: "전에 사용자가 고쳐 준 것(같은 실수 하지 마)"; "" none. */
    public static String words(List<BuddyMemory.Entry> lessons) {
        if (lessons == null || lessons.isEmpty()) return "";
        List<String> lines = new ArrayList<>(); for (BuddyMemory.Entry e : lessons) lines.add(e.text());
        return "전에 사용자가 고쳐 준 것(같은 실수 하지 마):\n- " + String.join("\n- ", lines) + "\n";
    }
    private static String cut(String s, int most) { String t = s == null ? "" : s.strip(); return t.codePointCount(0, t.length()) <= most ? t : t.substring(0, t.offsetByCodePoints(0, most)) + "…"; }
}
