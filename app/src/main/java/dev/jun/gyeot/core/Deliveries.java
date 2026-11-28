package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Food on its way (준, 2026-11-27: "배민에서 배달한걸 못알아채네", the status bar saying 조리완료 while 퍼레 said "휴, 땀 났다"):
 * what a delivery app's notification says, read as a stage (주문 접수 → 조리 중 → 조리 완료 → 배달 중 → 곧 도착 → 배달 완료) and the
 * time it should come. 배민's own tracker is an ongoing notification (a live update with a short chip such as "조리완료"), so it
 * is read here whatever kind of notification it is; ads and coupons are not orders. Pure Java.
 */
public final class Deliveries {
    private Deliveries() {}
    public enum Stage {
        ORDERED("ordered"), COOKING("cooking"), READY("ready"), ON_WAY("onway"), NEAR("near"), DONE("done"), CANCELED("canceled");
        public final String id;
        Stage(String id) { this.id = id; }
    }
    /** One reading: the app (package, short name), the stage, when it should come (0: not said), the words, when posted. */
    public record Delivery(String app, String name, Stage stage, long eta, String title, String text, long at) {
        public JSONObject json() { return obj("app", app, "name", name, "stage", stage.id, "eta", eta, "title", title, "text", text, "at", at); }
    }
    /** A reading kept as json (Delivery.json), or null. */
    public static Delivery of(JSONObject o) {
        if (o == null) return null;
        Stage stage = null; for (Stage s : Stage.values()) if (s.id.equals(o.optString("stage"))) stage = s;
        if (stage == null) return null;
        return new Delivery(o.optString("app"), o.optString("name"), stage, o.optLong("eta"), o.optString("title"), o.optString("text"), o.optLong("at"));
    }
    /** Food delivery apps, by package or by name. */
    private static final Pattern APP = Pattern.compile("(?i)com\\.sampleapp|baemin|woowahan|coupangeats|coupang\\.mobile\\.eats|yogiyo|ddangyo|배달의\\s*민족|배민|쿠팡\\s*이츠|요기요|땡겨요");
    public static boolean app(String pkg, String label) { return APP.matcher((pkg == null ? "" : pkg) + " " + (label == null ? "" : label)).find(); }
    /** The name 퍼레 says: 배민, 쿠팡이츠, 요기요, 땡겨요, else the app's own. */
    static String name(String pkg, String label) {
        String all = (pkg == null ? "" : pkg) + " " + (label == null ? "" : label);
        if (Pattern.compile("(?i)sampleapp|baemin|woowahan|배달의\\s*민족|배민").matcher(all).find()) return "배민";
        if (Pattern.compile("(?i)coupang|쿠팡").matcher(all).find()) return "쿠팡이츠";
        if (Pattern.compile("(?i)yogiyo|요기요").matcher(all).find()) return "요기요";
        if (Pattern.compile("(?i)ddangyo|땡겨요").matcher(all).find()) return "땡겨요";
        return label == null || label.isBlank() ? "배달 앱" : label.strip();
    }
    private static final Pattern CANCELED = Pattern.compile("주문\\s*(?:이|을|이\\s*)?\\s*취소|취소\\s*(?:되었|됐|처리|완료)|환불\\s*(?:되었|됐|완료)");
    private static final Pattern DONE = Pattern.compile("배달\\s*(?:이|을)?\\s*완료|배달\\s*(?:했|됐|되었)|전달\\s*(?:완료|했|됐|되었)|(?<!가게에\\s?)(?<!매장에\\s?)도착\\s*(?:했|완료|하였)|맛있게\\s*드세요|문\\s*앞에\\s*(?:두|놓)");
    private static final Pattern NEAR = Pattern.compile("곧\\s*도착|잠시\\s*후\\s*도착|거의\\s*다\\s*(?:왔|도착)|근처에\\s*(?:왔|도착|있)|도착\\s*직전");
    private static final Pattern ON_WAY = Pattern.compile("배달\\s*(?:을|이)?\\s*(?:시작|출발)|배달\\s*중|픽업\\s*(?:했|완료|하였)|음식을\\s*(?:받|픽업|가지고)|출발\\s*(?:했|하였)|가고\\s*있|오고\\s*있|이동\\s*중");
    private static final Pattern READY = Pattern.compile("조리\\s*(?:가|를)?\\s*(?:완료|끝|다\\s*됐)|음식\\s*(?:이|을)?\\s*(?:준비\\s*완료|다\\s*만들)|픽업\\s*(?:을\\s*)?(?:대기|기다)");
    private static final Pattern COOKING = Pattern.compile("조리\\s*(?:를|가)?\\s*(?:시작|중)|만들고\\s*있|준비\\s*(?:중|를\\s*시작)|가게에\\s*도착|매장에\\s*도착");
    private static final Pattern ORDERED = Pattern.compile("주문\\s*(?:이|을|이\\s*)?\\s*(?:접수|완료|수락|확인|들어)|접수\\s*(?:했|되었|됐|완료)|주문\\s*(?:했|하셨)|결제\\s*(?:가|를)?\\s*완료");
    /** Coupons, sales and the like: an order's notice is not one (unless the food is already on its way). */
    private static final Pattern PROMO = Pattern.compile("(?i)쿠폰|할인|이벤트|혜택|특가|적립|추천|무료\\s*배달|[(\\[]\\s*광고\\s*[)\\]]|수신\\s*거부");

    /** What a notification of a delivery app says, or null (not such an app, not an order, an ad). */
    public static Delivery read(String pkg, String label, String title, String text, String chip, long at, ZoneId zone) {
        if (!app(pkg, label)) return null;
        String all = String.join(" ", chip == null ? "" : chip, title == null ? "" : title, text == null ? "" : text).replaceAll("\\s+", " ").strip();
        if (all.isEmpty()) return null;
        Stage stage = stage(chip == null ? "" : chip);
        if (stage == null) stage = stage(all);
        if (stage == null) return null;
        if (PROMO.matcher(all).find() && (stage == Stage.ORDERED || stage == Stage.COOKING || stage == Stage.READY)) return null;
        long eta = chipEta(chip, at, zone); if (eta == 0) eta = eta(all, at, zone);
        return new Delivery(pkg == null ? "" : pkg, name(pkg, label), stage, eta, title == null ? "" : title.strip(), text == null ? "" : text.strip(), at);
    }
    static Stage stage(String words) {
        if (words == null || words.isBlank()) return null;
        if (CANCELED.matcher(words).find()) return Stage.CANCELED;
        if (DONE.matcher(words).find()) return Stage.DONE;
        if (NEAR.matcher(words).find()) return Stage.NEAR;
        if (ON_WAY.matcher(words).find()) return Stage.ON_WAY;
        if (READY.matcher(words).find()) return Stage.READY;
        if (COOKING.matcher(words).find()) return Stage.COOKING;
        if (ORDERED.matcher(words).find()) return Stage.ORDERED;
        return null;
    }

    private static final Pattern AT_TIME = Pattern.compile("(오전|오후|AM|PM|am|pm)?\\s*(\\d{1,2})\\s*(?::|시)\\s*(\\d{1,2})?\\s*분?\\s*(?:에|쯤|경|까지)?\\s*(?:도착|배달|예정|완료)");
    private static final Pattern TIME_AFTER = Pattern.compile("(?:도착\\s*예정|예상\\s*도착|도착\\s*시간|도착)\\s*(?:시간|시각)?\\s*[:은는]?\\s*(오전|오후|AM|PM|am|pm)?\\s*(\\d{1,2})\\s*(?::|시)\\s*(\\d{1,2})?");
    private static final Pattern RANGE = Pattern.compile("(\\d{1,3})\\s*[~\\-–]\\s*(\\d{1,3})\\s*분");
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})\\s*분\\s*(?:후|뒤|내|이내|안에|정도|쯤|남)?\\s*(?:에\\s*)?(?:도착|예정|소요|남)");
    /** When the words say it comes: a clock time (the next one after the notice, within four hours), or minutes from it; 0 when none. */
    static long eta(String all, long at, ZoneId zone) {
        Matcher m = TIME_AFTER.matcher(all); if (!m.find()) { m = AT_TIME.matcher(all); if (!m.find()) m = null; }
        if (m != null) {
            long t = clock(m.group(1), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), at, zone);
            if (t > 0) return t;
        }
        Matcher r = RANGE.matcher(all);
        if (r.find()) { int a = Integer.parseInt(r.group(1)), b = Integer.parseInt(r.group(2)); if (a <= b && b <= 180) return at + (a + b) / 2 * 60_000L; }
        Matcher n = MINUTES.matcher(all);
        if (n.find()) { int min = Integer.parseInt(n.group(1)); if (min > 0 && min <= 180) return at + min * 60_000L; }
        return 0;
    }
    private static final Pattern CHIP_CLOCK = Pattern.compile("^(오전|오후|AM|PM|am|pm)?\\s*(\\d{1,2}):(\\d{2})$"), CHIP_MIN = Pattern.compile("^(\\d{1,3})\\s*분$");
    /** The status bar chip alone may be the time ("오후 6:40", "12분"). */
    static long chipEta(String chip, long at, ZoneId zone) {
        String c = chip == null ? "" : chip.strip();
        Matcher m = CHIP_CLOCK.matcher(c);
        if (m.matches()) return clock(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), at, zone);
        m = CHIP_MIN.matcher(c);
        if (m.matches()) { int min = Integer.parseInt(m.group(1)); if (min > 0 && min <= 180) return at + min * 60_000L; }
        return 0;
    }
    private static long clock(String half, int h, int min, long at, ZoneId zone) {
        if (h > 23 || min > 59) return 0;
        ZonedDateTime base = Instant.ofEpochMilli(at).atZone(zone);
        int[] hours;
        if (half != null && (half.equals("오후") || half.equalsIgnoreCase("PM"))) hours = new int[]{h < 12 ? h + 12 : h};
        else if (half != null && (half.equals("오전") || half.equalsIgnoreCase("AM"))) hours = new int[]{h == 12 ? 0 : h};
        else hours = h < 12 ? new int[]{h, h + 12} : new int[]{h};
        for (int day = 0; day <= 1; day++) for (int hour : hours) {
            long t = base.toLocalDate().plusDays(day).atTime(hour, min).atZone(zone).toInstant().toEpochMilli();
            if (t >= at - 10 * 60_000L && t <= at + 4 * 3_600_000L) return t;
        }
        return 0;
    }

    /** The delivery still worth telling: none after three quiet hours, and none once it came or was called off forty minutes ago. */
    public static Delivery current(Delivery d, long now) {
        if (d == null || now - d.at() > 3 * 3_600_000L) return null;
        if ((d.stage() == Stage.DONE || d.stage() == Stage.CANCELED) && now - d.at() > 40 * 60_000L) return null;
        return d;
    }
    /** The newer of two readings (the one posted later). */
    public static Delivery newer(Delivery a, Delivery b) { return a == null ? b : b == null ? a : b.at() >= a.at() ? b : a; }

    /** "오후 6:40" for the board and the words. */
    public static String clock(long t, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(zone); int h = z.getHour();
        return (h < 12 ? "오전 " : "오후 ") + (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(java.util.Locale.ROOT, "%02d", z.getMinute());
    }
    /** What 퍼레 says at each stage, in its own words (no guessing what was ordered). */
    public static String line(Delivery d, ZoneId zone) {
        String when = d.eta() > 0 ? clock(d.eta(), zone).replace("오전 ", "").replace("오후 ", "") + "쯤 " : "";
        // Short, said in passing (준: "그냥 흘러가듯이 슬쩍 말하는 정도").
        return switch (d.stage()) {
            case ORDERED -> d.name() + " 주문 들어갔네 😋";
            case COOKING -> "음식 만드는 중이래";
            case READY -> "음식 다 됐대, 라이더님 기다리는 중";
            case ON_WAY -> "배달 출발했대" + (when.isEmpty() ? " 🛵" : ", " + when + "온대 🛵");
            case NEAR -> "배달 곧 온대!";
            case DONE -> "배달 왔대, 맛있게 먹어 🍚";
            case CANCELED -> "주문 취소됐대…";
        };
    }
    /** For the brain: "배민 배달: 조리 완료, 오후 6:40 도착 예정 (3분 전 알림)". */
    public static String words(Delivery d, long now, ZoneId zone) {
        String stage = switch (d.stage()) {
            case ORDERED -> "주문 접수"; case COOKING -> "조리 중"; case READY -> "조리 완료(라이더 기다리는 중)"; case ON_WAY -> "배달 중";
            case NEAR -> "곧 도착"; case DONE -> "배달 완료"; case CANCELED -> "주문 취소";
        };
        long min = Math.max(0, (now - d.at()) / 60_000);
        return d.name() + " 배달: " + stage + (d.eta() > 0 && d.stage() != Stage.DONE && d.stage() != Stage.CANCELED ? ", " + clock(d.eta(), zone) + " 도착 예정" : "")
            + " (" + (min < 1 ? "방금" : min + "분 전") + " 알림)";
    }
}
