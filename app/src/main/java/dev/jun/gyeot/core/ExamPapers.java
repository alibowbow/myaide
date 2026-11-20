package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 수능 기출: exam papers kept as PDFs in 들퍼레/수능, known by their names ("2025_6모_물리1.pdf", "2025학년도 대학수학능력시험
 * 수학영역 문제지.pdf") or, when a name says too little, by what their first page says; where each question of a paper is,
 * found once by the board's model and kept; and the part of a page that holds one question. Pure Java.
 */
public final class ExamPapers {
    private ExamPapers() {}
    public static final String FOLDER = "수능";
    public static final String SUNEUNG = "수능", JUNE = "6월 모의평가", SEPTEMBER = "9월 모의평가";
    /** Pages shown to the model at once while it finds the questions, and the most pages of a paper it looks at. */
    public static final int BATCH = 4, PAGES = 40;

    /** A paper: where it is (inside 들퍼레/수능), its year (학년도 when academic), exam, area and subject, and whether it holds answers. */
    public record Paper(String path, int year, boolean academic, String exam, String area, String subject, boolean answers) {
        public boolean known() { return year > 0 && !area.isEmpty(); }
        /** "2025학년도", or "2025년" for a year the name gave without 학년도. */
        public String when() { return year <= 0 ? "" : year + (academic ? "학년도" : "년"); }
        /** "2025학년도 수능": the exam a paper belongs to. */
        public String sitting() { return (when() + " " + (exam.isEmpty() ? "기출" : exam)).strip(); }
        /** The subject shown for it: the subject when it names one, else the area. */
        public String course() { return subject.isEmpty() ? area : subject; }
        public String title() { return (sitting() + " " + course()).strip(); }
        public Paper with(int year, boolean academic, String exam, String area, String subject) {
            return new Paper(path, this.year > 0 ? this.year : year, this.year > 0 ? this.academic : academic, this.exam.isEmpty() ? exam : this.exam,
                this.area.isEmpty() ? area : this.area, this.subject.isEmpty() ? subject : this.subject, answers);
        }
    }

    // ── What a name or a request says ───────────────────────────────────────────────────────────────

    /** {name, area, pattern}: subjects first (a 탐구 subject, 제2외국어), so "중국어" is not read as 국어. Ⅱ before Ⅰ. */
    private static final String[][] SUBJECTS = {
        {"생활과 윤리", "사회탐구", "생활\\s*과\\s*윤리|생윤"}, {"윤리와 사상", "사회탐구", "윤리\\s*와\\s*사상|윤사"},
        {"한국지리", "사회탐구", "한국\\s*지리|한지"}, {"세계지리", "사회탐구", "세계\\s*지리|세지"}, {"동아시아사", "사회탐구", "동아시아\\s*사|동아시아"},
        {"세계사", "사회탐구", "세계\\s*사(?!회)"}, {"정치와 법", "사회탐구", "정치\\s*와\\s*법|정법"}, {"사회·문화", "사회탐구", "사회\\s*[·ㆍ・\\s-]?\\s*문화|사문"},
        {"경제", "사회탐구", "경제"}, {"통합사회", "사회탐구", "통합\\s*사회"}, {"통합과학", "과학탐구", "통합\\s*과학"},
        {"물리학Ⅱ", "과학탐구", "물리(?:학)?\\s*(?:Ⅱ|II|2)"}, {"물리학Ⅰ", "과학탐구", "물리(?:학)?\\s*(?:Ⅰ|I|1)?"},
        {"화학Ⅱ", "과학탐구", "화학\\s*(?:Ⅱ|II|2)"}, {"화학Ⅰ", "과학탐구", "화학\\s*(?:Ⅰ|I|1)?"},
        {"생명과학Ⅱ", "과학탐구", "생명(?:\\s*과학)?\\s*(?:Ⅱ|II|2)|생과\\s*(?:Ⅱ|II|2)"}, {"생명과학Ⅰ", "과학탐구", "생명(?:\\s*과학)?\\s*(?:Ⅰ|I|1)?|생과\\s*(?:Ⅰ|I|1)?"},
        {"지구과학Ⅱ", "과학탐구", "지구(?:\\s*과학)?\\s*(?:Ⅱ|II|2)|지과\\s*(?:Ⅱ|II|2)"}, {"지구과학Ⅰ", "과학탐구", "지구(?:\\s*과학)?\\s*(?:Ⅰ|I|1)?|지과\\s*(?:Ⅰ|I|1)?"},
        {"독일어Ⅰ", "제2외국어/한문", "독일어"}, {"프랑스어Ⅰ", "제2외국어/한문", "프랑스어"}, {"스페인어Ⅰ", "제2외국어/한문", "스페인어"},
        {"중국어Ⅰ", "제2외국어/한문", "중국어"}, {"일본어Ⅰ", "제2외국어/한문", "일본어"}, {"러시아어Ⅰ", "제2외국어/한문", "러시아어"},
        {"아랍어Ⅰ", "제2외국어/한문", "아랍어"}, {"베트남어Ⅰ", "제2외국어/한문", "베트남어"}, {"한문Ⅰ", "제2외국어/한문", "한문"},
        {"한국사", "한국사", "한국사"},
    };
    private static final String[][] AREAS = {
        {"제2외국어/한문", "제\\s*2\\s*외국어"}, {"국어", "국어"}, {"수학", "수학"}, {"영어", "영어"},
        {"사회탐구", "사회\\s*탐구|사탐"}, {"과학탐구", "과학\\s*탐구|과탐"}, {"직업탐구", "직업\\s*탐구"},
    };
    /** {area, section, pattern}: the elective parts of 국어 and 수학 (2022학년도 on), and 수학's old 가형·나형. */
    private static final String[][] SECTIONS = {
        {"수학", "확률과 통계", "확률\\s*과\\s*통계|확통"}, {"수학", "미적분", "미적분|미적"}, {"수학", "기하", "기하"},
        {"수학", "가형", "가\\s*형"}, {"수학", "나형", "나\\s*형"},
        {"국어", "화법과 작문", "화법\\s*과\\s*작문|화작"}, {"국어", "언어와 매체", "언어\\s*와\\s*매체|언매"},
    };
    private static final Pattern[] COMPILED = new Pattern[SUBJECTS.length];
    static { for (int i = 0; i < SUBJECTS.length; i++) COMPILED[i] = Pattern.compile(SUBJECTS[i][2]); }
    /** The exam's full name holds 수학 ("대학수학능력시험"): it is read as 수능 before any area is looked for. */
    private static final Pattern FULL_NAME = Pattern.compile("대학\\s*수학\\s*능력\\s*시험");
    static String plain(String text) { return FULL_NAME.matcher(text == null ? "" : text).replaceAll(" 수능 "); }

    /** The subject a text names, as {name, area}, or null. */
    static String[] subject(String text) {
        for (int i = 0; i < SUBJECTS.length; i++) if (COMPILED[i].matcher(text).find()) return new String[]{SUBJECTS[i][0], SUBJECTS[i][1]};
        return null;
    }
    static String area(String text) {
        for (String[] area : AREAS) if (Pattern.compile(area[1]).matcher(text).find()) return area[0];
        return "";
    }
    /** The elective part of 국어 or 수학 a text names ("미적", "확률과 통계", "언매"), or "". */
    public static String section(String text) {
        if (text == null) return "";
        for (String[] section : SECTIONS) if (Pattern.compile(section[2]).matcher(text).find()) return section[1];
        return "";
    }
    /** The area an elective part belongs to (미적분 → 수학), or "". */
    static String areaOf(String section) { for (String[] part : SECTIONS) if (part[1].equals(section)) return part[0]; return ""; }
    /** The elective parts an area has, in the order they are printed. */
    public static List<String> sections(String area) {
        List<String> out = new ArrayList<>();
        for (String[] section : SECTIONS) if (section[0].equals(area) && !section[1].endsWith("형")) out.add(section[1]);
        return out;
    }
    /** Whether a question number of an area is one of its elective part's (국어 35번부터, 수학 23번부터). */
    public static boolean elective(String area, int number) { return "수학".equals(area) ? number >= 23 : "국어".equals(area) && number >= 35; }
    /** How many questions a paper of an area has, when no index says otherwise. */
    public static int questions(String area) {
        return switch (area) { case "국어", "영어" -> 45; case "수학", "제2외국어/한문" -> 30; default -> 20; };
    }

    private static final Pattern ACADEMIC = Pattern.compile("(?<![0-9])(20\\d{2}|\\d{2})\\s*학년도"), CALENDAR = Pattern.compile("(?<![0-9])(20[0-4]\\d)\\s*년(?!도)"),
        BARE = Pattern.compile("(?<![0-9])(20[0-4]\\d)(?![0-9])"),
        SHORT = Pattern.compile("(?<![0-9])(\\d{2})(?=\\s*(?:수능|모평|6모|9모|6월|9월|학평|_))"), MONTH = Pattern.compile("(?<![0-9])(0?[1-9]|1[0-2])\\s*(?:월|모(?!든))");
    /**
     * {year, academic (1 or 0)} a text gives, or {0, 0}. "2025학년도" and a bare "2025" (the folder's naming: 2025_수능_수학)
     * are 학년도; "2024년" is the year the exam was sat in, kept as it is.
     */
    static int[] year(String text) {
        Matcher academic = ACADEMIC.matcher(text);
        if (academic.find()) { int y = Integer.parseInt(academic.group(1)); return new int[]{y < 100 ? 2000 + y : y, 1}; }
        Matcher calendar = CALENDAR.matcher(text);
        if (calendar.find()) return new int[]{Integer.parseInt(calendar.group(1)), 0};
        Matcher bare = BARE.matcher(text);
        if (bare.find()) return new int[]{Integer.parseInt(bare.group(1)), 1};
        Matcher two = SHORT.matcher(text);
        if (two.find()) return new int[]{2000 + Integer.parseInt(two.group(1)), 1};
        return new int[]{0, 0};
    }
    /** The exam a text names: 수능, 6월 모의평가, 9월 모의평가, N월 학력평가, or "". */
    public static String exam(String text) {
        String s = text == null ? "" : text;
        Matcher month = MONTH.matcher(s); int m = month.find() ? Integer.parseInt(month.group(1)) : 0;
        boolean mock = Pattern.compile("모의\\s*평가|모의고사|모평|(?<![0-9])[69]\\s*모(?!든)").matcher(s).find(), academy = Pattern.compile("학력\\s*평가|학평|교육청").matcher(s).find();
        boolean csat = Pattern.compile("수능|대학\\s*수학\\s*능력\\s*시험").matcher(s).find();
        if (academy) return m > 0 ? m + "월 학력평가" : "학력평가";
        if (mock && m == 6) return JUNE;
        if (mock && m == 9) return SEPTEMBER;
        if (mock) return m > 0 && m != 11 ? m + "월 모의평가" : "모의평가";
        if (csat || m == 11) return SUNEUNG;
        if (m == 6) return JUNE;
        if (m == 9) return SEPTEMBER;
        return "";
    }
    /** What a paper's place in 들퍼레/수능 says about it ("2025/2025_9모_물리1.pdf"). */
    public static Paper parse(String path) {
        String text = plain(path == null ? "" : path.replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replace('/', ' ').replace('_', ' ').replace('-', ' '));
        int[] year = year(text); String exam = exam(text); String[] subject = subject(text);
        String area = subject != null ? subject[1] : area(text), course = subject != null && !subject[0].equals(subject[1]) ? subject[0] : "";
        String part = subject == null ? section(text) : "";
        if (area.isEmpty()) area = areaOf(part);
        if (!part.isEmpty() && area.equals(areaOf(part))) course = part;
        boolean answers = Pattern.compile("정답|해설|답지|답안").matcher(text).find() && !text.contains("문제지");
        return new Paper(path == null ? "" : path, year[0], year[1] == 1, exam, area, course, answers);
    }

    /** What a request on the board asks for: "2025 수능 수학 22번 풀어 줘". */
    public record Ask(int year, String exam, String area, String subject, String section, int number) {}
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9])([1-9]|[1-3][0-9]|4[0-5])\\s*번(?!째)");
    /** The question a request asks for, or null when it names no numbered question of a paper. */
    public static Ask ask(String words) {
        if (words == null) return null;
        String text = plain(words);
        Matcher number = NUMBER.matcher(text);
        if (!number.find()) return null;
        int[] year = year(text); String exam = exam(text); String[] subject = subject(text); String area = subject != null ? subject[1] : area(text);
        String course = subject != null && !subject[0].equals(subject[1]) ? subject[0] : "", part = section(text);
        if (area.isEmpty()) area = areaOf(part);
        if (year[0] == 0 && exam.isEmpty() && area.isEmpty() && !text.contains("기출")) return null;
        return new Ask(year[0], exam, area, course, part, Integer.parseInt(number.group(1)));
    }
    /** The question papers (not answer sheets) a request could mean, newest first and 수능 before the rest. */
    public static List<Paper> matching(List<Paper> papers, Ask ask) {
        List<Paper> out = new ArrayList<>();
        for (Paper paper : papers) {
            if (!paper.known() || paper.answers()) continue;
            if (ask.year() > 0 && paper.year() != ask.year()) continue;
            if (!ask.exam().isEmpty() && !paper.exam().equals(ask.exam())) continue;
            if (!ask.subject().isEmpty() ? !(paper.subject().equals(ask.subject()) || paper.subject().isEmpty() && paper.area().equals(ask.area())) : !ask.area().isEmpty() && !paper.area().equals(ask.area())) continue;
            // A paper of one elective part only (수학 미적분) is not the paper of another.
            if (!ask.section().isEmpty() && !paper.subject().isEmpty() && sections(paper.area()).contains(paper.subject()) && !paper.subject().equals(ask.section())) continue;
            out.add(paper);
        }
        out.sort(Comparator.comparingInt(Paper::year).reversed().thenComparing(p -> !p.exam().equals(SUNEUNG)).thenComparing(Paper::path));
        return out;
    }

    // ── Where the questions are ─────────────────────────────────────────────────────────────────────

    /** A question that starts on a page: its number, the elective part or subject printed on the page, the column (L, R or F) and the height of its number (0 to 1). */
    public record Spot(int number, String section, int page, char column, float top) {}
    /** Directions or a passage shared by questions from–to ("[1~3] 다음 글을 읽고 물음에 답하시오."), where it starts. */
    public record Passage(int from, int to, int page, char column, float top) {}
    /** Where the questions of a paper are, and what its first page says it is. */
    public record Index(int pages, List<Spot> spots, List<Passage> passages, int year, boolean academic, String exam, String area, String subject) {
        public JSONObject json() {
            JSONArray s = arr(), p = arr();
            for (Spot spot : spots) s.put(obj("n", spot.number(), "section", spot.section(), "page", spot.page(), "col", String.valueOf(spot.column()), "top", spot.top()));
            for (Passage passage : passages) p.put(obj("from", passage.from(), "to", passage.to(), "page", passage.page(), "col", String.valueOf(passage.column()), "top", passage.top()));
            return obj("v", 1, "pages", pages, "spots", s, "passages", p, "paper", obj("year", year, "academic", academic, "exam", exam, "area", area, "subject", subject));
        }
        /** The numbers this paper has, in order, with their sections when a number is in more than one. */
        public List<Integer> numbers() { List<Integer> out = new ArrayList<>(); for (Spot spot : spots) if (!out.contains(spot.number())) out.add(spot.number()); out.sort(Integer::compare); return out; }
        /** The sections a number is found in, in page order ("" for a number in no elective part). */
        public List<String> sections(int number) { List<String> out = new ArrayList<>(); for (Spot spot : spots) if (spot.number() == number && !out.contains(spot.section())) out.add(spot.section()); return out; }
    }
    public static Index index(JSONObject json) {
        if (json == null || json.optInt("v") != 1) return null;
        List<Spot> spots = new ArrayList<>(); List<Passage> passages = new ArrayList<>(); int pages = Math.max(0, Math.min(PAGES, json.optInt("pages")));
        JSONArray s = json.optJSONArray("spots"), p = json.optJSONArray("passages");
        for (int i = 0; s != null && i < s.length(); i++) { Spot spot = spot(s.optJSONObject(i), 0, pages, 1f); if (spot != null) spots.add(spot); }
        for (int i = 0; p != null && i < p.length(); i++) { Passage passage = passage(p.optJSONObject(i), 0, pages, 1f); if (passage != null) passages.add(passage); }
        JSONObject paper = json.optJSONObject("paper"); if (paper == null) paper = new JSONObject();
        return new Index(pages, spots, passages, paper.optInt("year"), paper.optBoolean("academic"), paper.optString("exam"), paper.optString("area"), paper.optString("subject"));
    }
    private static char column(String value) { String c = value == null ? "" : value.strip().toUpperCase(Locale.ROOT); return c.startsWith("R") ? 'R' : c.startsWith("F") ? 'F' : 'L'; }
    /** A spot as stored (scale 1) or as the model said it (pages counted from 1 within its batch, heights on 0–1000). */
    private static Spot spot(JSONObject item, int first, int pages, float scale) {
        if (item == null) return null;
        int number = item.optInt("n", item.optInt("number")), page = item.optInt("page", -1) - (scale == 1f ? 0 : 1) + first;
        double top = item.optDouble("top", Double.NaN) / scale;
        if (number < 1 || number > 60 || page < 0 || page >= Math.max(pages, first + 1) || Double.isNaN(top)) return null;
        return new Spot(number, clean(item.optString("section")), page, column(item.optString("col")), (float) Math.max(0, Math.min(1, top)));
    }
    private static Passage passage(JSONObject item, int first, int pages, float scale) {
        if (item == null) return null;
        int from = item.optInt("from"), to = item.optInt("to"), page = item.optInt("page", -1) - (scale == 1f ? 0 : 1) + first;
        double top = item.optDouble("top", Double.NaN) / scale;
        if (from < 1 || to < from || to > 60 || to - from > 10 || page < 0 || page >= Math.max(pages, first + 1) || Double.isNaN(top)) return null;
        return new Passage(from, to, page, column(item.optString("col")), (float) Math.max(0, Math.min(1, top)));
    }
    /** An elective part's or a subject's name as the app writes it, from however the page printed it. */
    static String clean(String printed) {
        String s = printed == null ? "" : printed.strip(); if (s.isEmpty() || s.length() > 40) return "";
        String part = section(s); if (!part.isEmpty()) return part;
        String[] subject = subject(s); return subject != null ? subject[0] : "";
    }

    public static final String INSTRUCTIONS = "You read the pages of a Korean exam paper (수능, 모의평가 or 학력평가) and say where each question is. Reply with JSON only, no prose.";
    /** The request for the pages from first (counted from 0) of a paper of pages pages, shown as pictures 1 to count. */
    public static String request(int first, int count, int pages) {
        return "These " + count + " pictures are pages " + (first + 1) + "–" + (first + count) + " of a " + pages + "-page exam paper; picture k is page k of this batch (1 to " + count + "). "
            + "For each picture, list every question whose number starts on it: its number, its column (L for the left column, R for the right, F when the page has one column), "
            + "and the height of the top of its number on the page from 0 (top edge) to 1000 (bottom edge). "
            + "Also list directions or passages shared by several questions, such as \"[1~3] 다음 글을 읽고 물음에 답하시오.\" or \"[18~19] 다음 물음에 답하시오.\", with the numbers they cover, column and top. "
            + "When the page's header or a heading names an elective part or a subject (확률과 통계, 미적분, 기하, 화법과 작문, 언어와 매체, 물리학Ⅰ …), give it as section; else \"\". "
            + (first == 0 ? "From the cover or the first page, also give the paper: its 학년도 (year), exam (수능, 6월 모의평가, 9월 모의평가 or N월 학력평가), area (국어, 수학, 영어, 한국사, 사회탐구, 과학탐구 …) and subject when one is named. " : "")
            + "JSON: {\"pages\":[{\"page\":1,\"section\":\"\",\"questions\":[{\"n\":1,\"col\":\"L\",\"top\":120}],\"sets\":[{\"from\":1,\"to\":3,\"col\":\"L\",\"top\":90}]}]"
            + (first == 0 ? ",\"paper\":{\"year\":2025,\"exam\":\"수능\",\"area\":\"수학\",\"subject\":\"\"}" : "") + "}";
    }
    /** Adds what the model said about a batch of pages to spots and passages; the paper it read off the first page, or null. */
    public static JSONObject read(String reply, int first, int count, List<Spot> spots, List<Passage> passages) {
        JSONObject json = json(reply); if (json == null) return null;
        JSONArray pages = json.optJSONArray("pages");
        for (int i = 0; pages != null && i < pages.length(); i++) {
            JSONObject page = pages.optJSONObject(i); if (page == null) continue;
            int number = page.optInt("page", -1); if (number < 1 || number > count) continue;
            String section = page.optString("section");
            JSONArray questions = page.optJSONArray("questions"), sets = page.optJSONArray("sets");
            for (int q = 0; questions != null && q < questions.length(); q++) {
                JSONObject item = questions.optJSONObject(q); if (item == null) continue;
                JSONObject placed = obj("n", item.opt("n"), "col", item.optString("col"), "top", item.opt("top"), "page", number, "section", item.optString("section", section));
                Spot spot = spot(placed, first, first + count, 1000f);
                if (spot != null && spots.stream().noneMatch(s -> s.number() == spot.number() && s.section().equals(spot.section()) && s.page() == spot.page())) spots.add(spot);
            }
            for (int s = 0; sets != null && s < sets.length(); s++) {
                JSONObject item = sets.optJSONObject(s); if (item == null) continue;
                Passage passage = passage(obj("from", item.opt("from"), "to", item.opt("to"), "col", item.optString("col"), "top", item.opt("top"), "page", number), first, first + count, 1000f);
                if (passage != null) passages.add(passage);
            }
        }
        return json.optJSONObject("paper");
    }
    /** The JSON object in a model's reply, which may come wrapped in a code fence or a sentence. */
    static JSONObject json(String reply) {
        if (reply == null) return null;
        int start = reply.indexOf('{'), end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try { return new JSONObject(reply.substring(start, end + 1)); } catch (Exception unreadable) { return null; }
    }
    /** The paper an index read off its first page, laid over what its name said. */
    public static Paper known(Paper named, Index index) {
        if (index == null) return named;
        String[] subject = subject(index.subject() == null ? "" : index.subject());
        String area = subject != null ? subject[1] : index.area().isBlank() ? "" : area(plain(index.area())).isEmpty() ? index.area().strip() : area(plain(index.area()));
        return named.with(index.year() >= 2000 && index.year() < 2100 ? index.year() : 0, index.year() > 0, exam(index.exam()).isEmpty() ? index.exam().strip() : exam(index.exam()), area,
            subject != null && !subject[0].equals(subject[1]) ? subject[0] : "");
    }

    // ── The part of a page that holds a question ────────────────────────────────────────────────────

    /** A piece of a page: its column (L, R or F) from top to bottom (0 to 1). */
    public record Cut(int page, char column, float top, float bottom) {}
    /** Where a page's printed footer starts: what is below is never part of a question. */
    public static final float FOOT = .935f;
    /**
     * The pieces of the paper that show a question: its column from its number down to whatever comes next in that column
     * on its page; with the passage it shares, the whole pages from the passage's to the question's (at most three).
     * section picks among the same number in several elective parts; "" takes the first. Empty when the index has no such question.
     */
    public static List<Cut> cuts(Index index, int number, String section) {
        List<Cut> out = new ArrayList<>(); if (index == null) return out;
        Spot spot = null;
        for (Spot s : index.spots()) if (s.number() == number && (section.isEmpty() || s.section().equals(section)) && (spot == null || s.page() < spot.page())) spot = s;
        if (spot == null && !section.isEmpty()) for (Spot s : index.spots()) if (s.number() == number && s.section().isEmpty()) { spot = s; break; }
        if (spot == null) return out;
        Passage shared = null;
        for (Passage p : index.passages()) if (p.from() <= number && number <= p.to() && p.page() <= spot.page() && (shared == null || p.page() > shared.page())) shared = p;
        // A passage the question shares: the reader needs all of it, and it may run across columns and pages.
        if (shared != null && shared.from() < shared.to()) {
            for (int page = Math.max(shared.page(), spot.page() - 2); page <= spot.page(); page++) out.add(new Cut(page, 'F', 0, 1));
            return out;
        }
        float bottom = FOOT;
        for (Spot other : index.spots()) if (other.page() == spot.page() && same(other.column(), spot.column()) && other.top() > spot.top() + .01f) bottom = Math.min(bottom, other.top());
        for (Passage other : index.passages()) if (other.page() == spot.page() && same(other.column(), spot.column()) && other.top() > spot.top() + .01f) bottom = Math.min(bottom, other.top());
        out.add(new Cut(spot.page(), spot.column(), Math.max(0, spot.top() - .012f), bottom));
        return out;
    }
    private static boolean same(char a, char b) { return a == b || a == 'F' || b == 'F'; }
    /**
     * Moves the edge of a cut up to the nearest row with nothing printed on it, so a line is never cut through: ink[y] is
     * how much is printed on row y. Searches up to reach rows above at; at itself when none is blank.
     */
    public static int snap(int[] ink, int at, int reach) {
        if (ink == null || ink.length == 0) return at;
        int from = Math.max(0, Math.min(ink.length - 1, at));
        for (int y = from; y >= Math.max(0, from - reach); y--) if (ink[y] == 0) return y;
        return from;
    }
    /**
     * The nearest band of blank rows at least gap rows tall above at (within reach rows), as {its top row, its bottom row};
     * null when there is none. A raised exponent, a fraction or a root index leaves only a row or two blank inside a line,
     * so a band this tall lies between lines, never through one.
     */
    static int[] band(int[] ink, int at, int reach, int gap) {
        if (ink == null || ink.length == 0) return null;
        int from = Math.max(0, Math.min(ink.length - 1, at)), stop = Math.max(0, from - reach), run = 0;
        for (int y = from; y >= stop; y--) {
            if (ink[y] != 0) { run = 0; continue; }
            if (++run < gap) continue;
            int bottom = y + run - 1, top = y;
            while (top - 1 >= stop && ink[top - 1] == 0) top--;
            return new int[]{top, bottom};
        }
        return null;
    }
    /** Where a question's piece starts: in the blank band above its first line, close to it (snap when there is none). */
    public static int topEdge(int[] ink, int at, int reach, int gap) {
        int[] b = band(ink, at, reach, gap);
        return b == null ? snap(ink, at, reach) : b[1] - Math.min(8, (b[1] - b[0]) / 2);
    }
    /** Where a question's piece ends: in the blank band above whatever comes next, close to the question (snap when there is none). */
    public static int bottomEdge(int[] ink, int at, int reach, int gap) {
        int[] b = band(ink, at, reach, gap);
        return b == null ? snap(ink, at, reach) : b[0] + Math.min(8, (b[1] - b[0]) / 2);
    }
}
