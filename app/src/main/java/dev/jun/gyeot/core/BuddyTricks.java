package dev.jun.gyeot.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.json.JSONArray;

/**
 * 퍼레's own little tools that need no app (BuddyActs): a coin, dice, one pick of a few, a sum worked out, the time in another
 * city, the days to or from a date, a stopwatch's reading, which memories a "잊어 줘" means and what the user wants to be called.
 * The brain writes words well and numbers badly, so these answers are worked out here, never made up. Pure Java.
 */
public final class BuddyTricks {
    private BuddyTricks() {}

    // ── chance: a coin, dice, one of a few ──
    public static boolean heads(Random r) { return r.nextBoolean(); }
    public static String coinSay(boolean heads, Random r) {
        String side = heads ? "앞면" : "뒷면";
        String[] ways = {"짠, " + side + "이야!", "또르르… " + side + "!", side + " 나왔어!"};
        return ways[r.nextInt(ways.length)];
    }
    /** count dice (1–5) of sides faces (2–100, 6 when not said). */
    public static int[] dice(int count, int sides, Random r) {
        int n = Math.max(1, Math.min(5, count)), s = sides < 2 ? 6 : Math.min(100, sides);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = 1 + r.nextInt(s);
        return out;
    }
    public static String diceSay(int[] rolls) {
        if (rolls.length == 1) return "데구르르… " + rolls[0] + "!";
        StringBuilder all = new StringBuilder(); int sum = 0;
        for (int i = 0; i < rolls.length; i++) { all.append(i == 0 ? "" : i == rolls.length - 1 ? "이랑 " : ", ").append(rolls[i]); sum += rolls[i]; }
        return all + ", 합쳐서 " + sum + "!";
    }
    /** The choices as the brain gave them: a list, or one line ("짜장면, 짬뽕 아니면 볶음밥"). At most ten, none twice. */
    public static List<String> options(Object raw) {
        List<String> parts = new ArrayList<>();
        if (raw instanceof JSONArray a) for (int i = 0; i < a.length(); i++) parts.add(a.optString(i));
        else if (raw != null) for (String p : String.valueOf(raw).split("\\s*(?:,|/|·|\\||또는|아니면|(?<=[가-힣])이나\\s|\\svs\\s|\\sVS\\s)\\s*")) parts.add(p);
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String v = p == null ? "" : p.replaceAll("[\"“”‘’'\\[\\]]", "").replaceAll("\\s+", " ").replaceAll("\\s*중(에서|에)?(\\s*하나)?(\\s*골라.*)?$", "").strip();
            if (!v.isEmpty() && v.length() <= 30 && !out.contains(v) && out.size() < 10) out.add(v);
        }
        return out;
    }
    public static String pickSay(String chosen, Random r) {
        String[] ways = {"음… ‘" + chosen + "’ 어때?", "나는 ‘" + chosen + "’!", "골랐다, ‘" + chosen + "’!"};
        return ways[r.nextInt(ways.length)];
    }

    // ── a sum worked out (a quick brain's 퍼센트 and 곱하기 are guesses) ──
    /** The value of an expression said or written (12*7, 3만5천 곱하기 3, 50000의 15%, (3+4)^2), or null when it is not one. */
    public static Double calc(String said) {
        if (said == null || said.isBlank()) return null;
        String s = said.strip().replaceAll("(?<=\\d),(?=\\d{3})", "")
            .replace("×", "*").replace("÷", "/").replace("−", "-").replace("**", "^").replace("＋", "+").replace("－", "-")
            .replaceAll("더하기|플러스", "+").replaceAll("빼기|마이너스", "-").replaceAll("곱하기", "*").replaceAll("나누기", "/")
            .replaceAll("의\\s*세제곱", "^3").replaceAll("의\\s*제곱", "^2").replaceAll("세제곱", "^3").replaceAll("제곱", "^2")
            .replaceAll("퍼센트|프로", "%").replaceAll("의\\s*(?=[\\d(])", "*")
            .replaceAll("(?<=[\\d)])\\s*[xX]\\s*(?=[\\d(])", "*")
            .replaceAll("원|개|명|은|는|이|가|얼마|야|=|\\?|？", "").replaceAll("\\s+", "");
        if (s.isEmpty() || !s.matches("[0-9.+\\-*/^%()억만천백]+") || !s.matches(".*\\d.*")) return null;
        try {
            Calc c = new Calc(s); double v = c.sum();
            if (c.at != s.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (ArithmeticException byZero) { throw byZero; }
        catch (RuntimeException unreadable) { return null; }
    }
    /** A number as said back: whole with commas (35,000), else at most four decimals (3.1416). */
    public static String number(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return String.format(Locale.ROOT, "%,d", (long) Math.rint(v));
        String plain = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        int dot = plain.indexOf('.'); if (dot < 0) return String.format(Locale.ROOT, "%,d", Long.parseLong(plain));
        String whole = plain.substring(0, dot); boolean minus = whole.startsWith("-");
        long w = Math.abs(Long.parseLong(whole.isEmpty() || whole.equals("-") ? "0" : whole));
        return (minus ? "-" : "") + String.format(Locale.ROOT, "%,d", w) + plain.substring(dot);
    }
    public static String calcSay(double v, Random r) {
        String n = number(v);
        String[] ways = {"계산해 보니 " + n + "이야!", "답은 " + n + "!", n + " 나왔어!"};
        return ways[r.nextInt(ways.length)];
    }
    /** sum = term (± term)*, term = power (×÷ power)*, power = unary (^ power)?, unary = -unary | value %*, value = number | ( sum ). */
    private static final class Calc {
        final String s; int at;
        Calc(String s) { this.s = s; }
        boolean eat(char ch) { if (at < s.length() && s.charAt(at) == ch) { at++; return true; } return false; }
        double sum() { double v = term(); while (true) { if (eat('+')) v += term(); else if (eat('-')) v -= term(); else return v; } }
        double term() {
            double v = power();
            while (true) {
                if (eat('*')) v *= power();
                else if (eat('/')) { double d = power(); if (d == 0) throw new ArithmeticException("0"); v /= d; }
                else return v;
            }
        }
        double power() { double b = unary(); if (eat('^')) { double e = power(); if (Math.abs(e) > 64) throw new IllegalArgumentException("too big"); return Math.pow(b, e); } return b; }
        double unary() { if (eat('-')) return -unary(); if (eat('+')) return unary(); double v = value(); while (eat('%')) v /= 100; return v; }
        double value() {
            if (eat('(')) { double v = sum(); if (!eat(')')) throw new IllegalArgumentException("("); return v; }
            // Korean units: 3만5천 = 35,000, 1억2천만 = 120,000,000, 5천 = 5,000.
            double total = 0, section = 0; boolean any = false;
            while (at < s.length()) {
                int from = at; while (at < s.length() && (Character.isDigit(s.charAt(at)) || s.charAt(at) == '.')) at++;
                double n = at > from ? Double.parseDouble(s.substring(from, at)) : 0; boolean digits = at > from;
                char u = at < s.length() ? s.charAt(at) : ' ';
                if (u == '백' || u == '천') { at++; section += (digits ? n : 1) * (u == '백' ? 100 : 1000); any = true; continue; }
                if (u == '만' || u == '억') { at++; total += (section + (digits ? n : section == 0 ? 1 : 0)) * (u == '만' ? 1e4 : 1e8); section = 0; any = true; continue; }
                if (!digits) break;
                section += n; any = true; break;
            }
            if (!any) throw new IllegalArgumentException("no number");
            return total + section;
        }
    }

    // ── the time in another city ──
    private static final Map<String, String[]> CITIES = new LinkedHashMap<>();
    private static void city(String zone, String label, String... names) { for (String n : names) CITIES.put(n, new String[]{zone, label}); }
    static {
        city("Asia/Seoul", "서울", "서울", "한국", "seoul", "korea");
        city("Asia/Tokyo", "도쿄", "도쿄", "동경", "일본", "오사카", "후쿠오카", "삿포로", "오키나와", "tokyo", "japan", "osaka");
        city("Asia/Shanghai", "베이징", "베이징", "북경", "중국", "상하이", "상해", "beijing", "shanghai", "china");
        city("Asia/Hong_Kong", "홍콩", "홍콩", "hongkong");
        city("Asia/Taipei", "타이베이", "타이베이", "대만", "taipei", "taiwan");
        city("Asia/Singapore", "싱가포르", "싱가포르", "singapore");
        city("Asia/Bangkok", "방콕", "방콕", "태국", "푸껫", "치앙마이", "bangkok", "thailand");
        city("Asia/Ho_Chi_Minh", "베트남", "베트남", "하노이", "호치민", "다낭", "나트랑", "vietnam", "hanoi");
        city("Asia/Manila", "필리핀", "필리핀", "마닐라", "세부", "보라카이", "manila", "philippines", "cebu");
        city("Asia/Jakarta", "자카르타", "자카르타", "jakarta");
        city("Asia/Makassar", "발리", "발리", "bali");
        city("Asia/Kuala_Lumpur", "말레이시아", "말레이시아", "쿠알라룸푸르", "코타키나발루", "malaysia");
        city("Asia/Kolkata", "인도", "인도", "뉴델리", "델리", "뭄바이", "india", "delhi", "mumbai");
        city("Asia/Dubai", "두바이", "두바이", "dubai");
        city("Asia/Ulaanbaatar", "몽골", "몽골", "울란바토르", "mongolia");
        city("Asia/Vladivostok", "블라디보스토크", "블라디보스토크", "vladivostok");
        city("Europe/Moscow", "모스크바", "모스크바", "러시아", "moscow", "russia");
        city("Europe/Istanbul", "튀르키예", "튀르키예", "터키", "이스탄불", "istanbul", "turkey");
        city("Europe/London", "런던", "런던", "영국", "london", "uk", "england");
        city("Europe/Paris", "파리", "파리", "프랑스", "paris", "france");
        city("Europe/Berlin", "독일", "독일", "베를린", "프랑크푸르트", "뮌헨", "berlin", "germany");
        city("Europe/Rome", "이탈리아", "이탈리아", "로마", "밀라노", "베네치아", "rome", "italy");
        city("Europe/Madrid", "스페인", "스페인", "마드리드", "바르셀로나", "madrid", "spain", "barcelona");
        city("Europe/Amsterdam", "네덜란드", "네덜란드", "암스테르담", "amsterdam");
        city("Europe/Zurich", "스위스", "스위스", "취리히", "제네바", "zurich", "switzerland");
        city("Europe/Vienna", "오스트리아", "오스트리아", "비엔나", "vienna");
        city("Europe/Prague", "프라하", "프라하", "체코", "prague");
        city("America/New_York", "뉴욕", "뉴욕", "워싱턴", "보스턴", "마이애미", "필라델피아", "newyork", "ny", "nyc", "washington", "boston", "miami");
        city("America/New_York", "미국 동부", "미국", "미국동부", "usa", "us");
        city("America/Toronto", "토론토", "토론토", "캐나다", "몬트리올", "toronto", "canada");
        city("America/Chicago", "시카고", "시카고", "댈러스", "휴스턴", "chicago", "dallas", "houston");
        city("America/Denver", "덴버", "덴버", "denver");
        city("America/Los_Angeles", "LA", "la", "엘에이", "로스앤젤레스", "로스엔젤레스", "샌프란시스코", "실리콘밸리", "시애틀", "라스베이거스", "미국서부", "losangeles", "sanfrancisco", "seattle", "lasvegas");
        city("America/Vancouver", "밴쿠버", "밴쿠버", "vancouver");
        city("Pacific/Honolulu", "하와이", "하와이", "호놀룰루", "hawaii", "honolulu");
        city("Pacific/Guam", "괌", "괌", "guam");
        city("Pacific/Saipan", "사이판", "사이판", "saipan");
        city("Australia/Sydney", "시드니", "시드니", "호주", "sydney", "australia");
        city("Australia/Melbourne", "멜버른", "멜버른", "melbourne");
        city("Pacific/Auckland", "뉴질랜드", "뉴질랜드", "오클랜드", "auckland", "newzealand");
        city("America/Mexico_City", "멕시코", "멕시코", "멕시코시티", "mexico");
        city("America/Sao_Paulo", "브라질", "브라질", "상파울루", "리우", "brazil", "saopaulo");
        city("America/Argentina/Buenos_Aires", "아르헨티나", "아르헨티나", "부에노스아이레스", "argentina");
        city("Africa/Cairo", "이집트", "이집트", "카이로", "egypt", "cairo");
    }
    /** {zone, label} for a place said (뉴욕, 파리는, LA 지금), or the zone the brain named (America/New_York); null when unknown. */
    public static String[] place(String said, String zone) {
        if (zone != null && !zone.isBlank()) try { ZoneId z = ZoneId.of(zone.strip()); return new String[]{z.getId(), said == null || said.isBlank() ? z.getId() : said.strip()}; } catch (RuntimeException unknown) { /* the place, below */ }
        String k = said == null ? "" : said.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]", "").replaceAll("(지금|현지|시간|시각|몇시|은|는|에서|에|의)+$", "");
        if (k.isEmpty()) return null;
        if (CITIES.containsKey(k)) return CITIES.get(k).clone();
        String best = null;
        // Inside the words (뉴욕시티, 도쿄의 지금): a name of two Korean letters or three Latin ones at least (not la in dallas).
        for (String name : CITIES.keySet()) if (name.length() >= (name.matches("[a-z]+") ? 3 : 2) && k.contains(name) && (best == null || name.length() > best.length())) best = name;
        return best == null ? null : CITIES.get(best).clone();
    }
    /** "뉴욕은 지금 어제 오후 9:05야. 여기보다 14시간 느려." */
    public static String worldTime(String label, ZoneId there, ZonedDateTime here) {
        ZonedDateTime t = here.withZoneSameInstant(there);
        long days = ChronoUnit.DAYS.between(here.toLocalDate(), t.toLocalDate());
        int diff = t.getOffset().getTotalSeconds() - here.getOffset().getTotalSeconds();
        String day = days == 0 ? "" : days == 1 ? "내일 " : days == -1 ? "어제 " : t.getMonthValue() + "월 " + t.getDayOfMonth() + "일 ";
        int h = Math.abs(diff) / 3600, m = Math.abs(diff) % 3600 / 60;
        String gap = diff == 0 ? "여기랑 같은 시각이야." : "여기보다 " + (h > 0 ? h + "시간" : "") + (m > 0 ? (h > 0 ? " " : "") + m + "분" : "") + (diff > 0 ? " 빨라." : " 느려.");
        return Brand.josa(label, "은", "는") + " 지금 " + day + clock(t) + "야. " + gap;
    }
    /** 오전 10:05, 오후 9:05. */
    public static String clock(ZonedDateTime t) {
        int hour = t.getHour(), minute = t.getMinute();
        return (hour < 12 ? "오전 " : "오후 ") + (hour % 12 == 0 ? 12 : hour % 12) + ":" + (minute < 10 ? "0" : "") + minute;
    }

    // ── days ──
    private static final Map<String, int[]> DAYS_NAMED = new LinkedHashMap<>();
    static {
        DAYS_NAMED.put("크리스마스이브", new int[]{12, 24}); DAYS_NAMED.put("크리스마스", new int[]{12, 25}); DAYS_NAMED.put("성탄절", new int[]{12, 25});
        DAYS_NAMED.put("새해", new int[]{1, 1}); DAYS_NAMED.put("신정", new int[]{1, 1}); DAYS_NAMED.put("밸런타인데이", new int[]{2, 14}); DAYS_NAMED.put("발렌타인데이", new int[]{2, 14});
        DAYS_NAMED.put("화이트데이", new int[]{3, 14}); DAYS_NAMED.put("삼일절", new int[]{3, 1}); DAYS_NAMED.put("어린이날", new int[]{5, 5}); DAYS_NAMED.put("어버이날", new int[]{5, 8});
        DAYS_NAMED.put("광복절", new int[]{8, 15}); DAYS_NAMED.put("개천절", new int[]{10, 3}); DAYS_NAMED.put("한글날", new int[]{10, 9});
        DAYS_NAMED.put("할로윈", new int[]{10, 31}); DAYS_NAMED.put("핼러윈", new int[]{10, 31}); DAYS_NAMED.put("빼빼로데이", new int[]{11, 11});
    }
    private static final java.util.regex.Pattern YMD = java.util.regex.Pattern.compile("(?:(\\d{4})\\s*[-./년]\\s*)?(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})\\s*일?");
    /**
     * The day said: 오늘·내일·모레·글피·어제·그제, 2026-12-25 (2026.12.25, 2026년 12월 25일), 12-25 or 12월 25일 (the next one), or a day by
     * its name (크리스마스, 한글날 …, the next one); null when it is none of these.
     */
    public static LocalDate day(String said, LocalDate today) {
        String d = said == null ? "" : said.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (d.isEmpty() || d.equals("오늘") || d.equals("today")) return today;
        switch (d) {
            case "내일", "tomorrow" -> { return today.plusDays(1); }
            case "모레" -> { return today.plusDays(2); }
            case "글피" -> { return today.plusDays(3); }
            case "어제", "yesterday" -> { return today.minusDays(1); }
            case "그제", "그저께" -> { return today.minusDays(2); }
            default -> {}
        }
        for (Map.Entry<String, int[]> e : DAYS_NAMED.entrySet()) if (d.contains(e.getKey())) return next(today, e.getValue()[0], e.getValue()[1]);
        java.util.regex.Matcher m = YMD.matcher(said.strip());
        if (!m.find()) return null;
        try {
            int month = Integer.parseInt(m.group(2)), date = Integer.parseInt(m.group(3));
            if (m.group(1) != null) return LocalDate.of(Integer.parseInt(m.group(1)), month, date);
            return next(today, month, date);
        } catch (RuntimeException impossible) { return null; }
    }
    private static LocalDate next(LocalDate today, int month, int date) {
        LocalDate d = LocalDate.of(today.getYear(), month, Math.min(date, java.time.YearMonth.of(today.getYear(), month).lengthOfMonth()));
        return d.isBefore(today) ? LocalDate.of(today.getYear() + 1, month, Math.min(date, java.time.YearMonth.of(today.getYear() + 1, month).lengthOfMonth())) : d;
    }
    private static final String WEEK = "월화수목금토일";
    /** 3월 6일 토요일 (with its year when it is not this year's). */
    public static String dateWords(LocalDate d, LocalDate today) {
        return (d.getYear() != today.getYear() ? d.getYear() + "년 " : "") + d.getMonthValue() + "월 " + d.getDayOfMonth() + "일 " + WEEK.charAt(d.getDayOfWeek().getValue() - 1) + "요일";
    }
    /** To a day or from it: "크리스마스까지 29일 남았어!", "바로 오늘이야!", "그날부터 99일 지났어. 그날을 1일로 세면 100일째야." */
    public static String countDays(LocalDate target, String label, LocalDate today) {
        long n = ChronoUnit.DAYS.between(today, target);
        String name = label == null || label.isBlank() ? dateWords(target, today) : label.strip();
        if (n == 0) return Brand.josa(name, "은", "는") + " 바로 오늘이야!";
        if (n > 0) return name + "까지 " + String.format(Locale.ROOT, "%,d", n) + "일 남았어!" + (n == 1 ? " 내일이야!" : "");
        return name + "부터 오늘까지 " + String.format(Locale.ROOT, "%,d", -n) + "일 지났어. 그날을 1일로 세면 " + String.format(Locale.ROOT, "%,d", -n + 1) + "일째야.";
    }
    /** N days after (or before) a day: "오늘부터 100일 뒤는 2027년 3월 6일 토요일이야." */
    public static String addDays(LocalDate from, long days, LocalDate today) {
        LocalDate to = from.plusDays(days);
        String start = from.equals(today) ? "오늘" : dateWords(from, today);
        return (start.equals("오늘") ? "오늘부터" : Brand.euro(start) + "부터") + " " + String.format(Locale.ROOT, "%,d", Math.abs(days)) + "일 " + (days >= 0 ? "뒤는 " : "전은 ") + dateWords(to, today) + "이야.";
    }

    // ── a language to translate to ──
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();
    static {
        String[][] names = {{"ko", "한국어", "한국말", "korean"}, {"en", "영어", "english"}, {"ja", "일본어", "일어", "japanese"}, {"zh-CN", "중국어", "중국말", "chinese", "zh"},
            {"zh-TW", "대만어", "번체"}, {"es", "스페인어", "spanish"}, {"fr", "프랑스어", "불어", "french"}, {"de", "독일어", "german"}, {"it", "이탈리아어", "italian"},
            {"ru", "러시아어", "russian"}, {"vi", "베트남어", "vietnamese"}, {"th", "태국어", "thai"}, {"id", "인도네시아어", "indonesian"}, {"pt", "포르투갈어", "portuguese"},
            {"ar", "아랍어", "arabic"}, {"hi", "힌디어", "hindi"}, {"tr", "튀르키예어", "터키어", "turkish"}, {"nl", "네덜란드어", "dutch"}, {"mn", "몽골어"}};
        for (String[] n : names) { LANGUAGES.put(n[0].toLowerCase(Locale.ROOT), n[0]); for (int i = 1; i < n.length; i++) LANGUAGES.put(n[i], n[0]); }
    }
    /** The language code to translate into: as said (영어, ja, 중국어로), else English for Korean words and Korean for any other. */
    public static String language(String said, String text) {
        String k = said == null ? "" : said.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("(으로|로)$", "");
        if (LANGUAGES.containsKey(k)) return LANGUAGES.get(k);
        for (Map.Entry<String, String> e : LANGUAGES.entrySet()) if (e.getKey().length() >= 2 && !k.isEmpty() && k.contains(e.getKey())) return e.getValue();
        return text != null && text.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3) ? "en" : "ko";
    }

    // ── a stopwatch's reading ──
    /** 12초, 1분 5초, 1시간 2분 3초 (under ten seconds with a tenth: 3.2초). */
    public static String elapsed(long ms) {
        long t = Math.max(0, ms);
        if (t < 10_000) return String.format(Locale.ROOT, "%.1f초", t / 1000.0).replace(".0초", "초");
        long s = t / 1000, h = s / 3600, m = s % 3600 / 60, r = s % 60;
        StringBuilder out = new StringBuilder();
        if (h > 0) out.append(h).append("시간");
        if (m > 0) out.append(out.length() == 0 ? "" : " ").append(m).append("분");
        if (r > 0 || out.length() == 0) out.append(out.length() == 0 ? "" : " ").append(r).append("초");
        return out.toString();
    }

    // ── memories ──
    private static final Set<String> FILLER = Set.of("사용자", "사용자가", "사용자는", "기억", "기억해", "기억한", "기억하는", "잊어", "잊어줘", "잊어버려", "지워", "지워줘", "그거", "그것", "거", "것",
        "내가", "나", "내", "날", "얘기", "이야기", "말", "했던", "한", "다", "좀", "줘", "대해", "대한", "관한", "관련", "그", "이", "저", "있잖아", "했잖아");
    private static final String[] ENDINGS = {"이라는", "라는", "에서", "으로", "한테", "에게", "이랑", "랑", "을", "를", "이", "가", "은", "는", "도", "에", "의", "로", "과", "와", "대", "래", "요"};
    /** Which memories (their texts) a "잊어 줘" names: every word of it found in the memory, or a line much alike. */
    public static List<Integer> matching(List<String> texts, String what) {
        List<Integer> out = new ArrayList<>();
        List<List<String>> words = new ArrayList<>();
        for (String w : (what == null ? "" : what).split("[\\s\\p{Punct}·…~]+")) {
            if (w.isBlank() || FILLER.contains(w)) continue;
            List<String> forms = new ArrayList<>(); forms.add(w);
            for (String e : ENDINGS) if (w.endsWith(e) && w.length() - e.length() >= 2) forms.add(w.substring(0, w.length() - e.length()));
            // A verb as said is rarely as kept (좋아한다는 / 좋아한대요): its stem, down to two letters, counts too.
            for (int n = w.length() - 1; n >= 2; n--) forms.add(w.substring(0, n));
            if (forms.stream().allMatch(f -> f.length() < 2)) continue;
            words.add(forms);
        }
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i) == null ? "" : texts.get(i).replaceAll("\\s+", "");
            boolean all = !words.isEmpty();
            for (List<String> forms : words) if (forms.stream().noneMatch(f -> f.length() >= 2 && t.contains(f))) { all = false; break; }
            if (all || what != null && what.strip().length() >= 6 && BuddyMind.similarity(what, texts.get(i)) >= 0.5) out.add(i);
        }
        return out;
    }
    /** What a "이제 ○○라고 불러 줘" wants to be called, at most twelve letters; "" when there is none. */
    public static String title(String said) {
        String t = said == null ? "" : said.replaceAll("[\"“”‘’'()\\[\\]]", "").strip()
            .replaceAll("\\s*(이라고|라고|으로|로)?\\s*(불러|부르라고|부르자|해)\\s*(줘|주세요|줄래|봐)?[.!~]*$", "")
            .replaceAll("\\s*(이라고|라고)$", "").replaceAll("^(나를|날|저를|제|나는|앞으로|이제|이제부터|지금부터)\\s*", "").strip();
        return t.isEmpty() || t.codePointCount(0, t.length()) > 12 || t.matches(".*\\d{4,}.*") ? "" : t;
    }
}
