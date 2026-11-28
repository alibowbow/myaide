package dev.jun.gyeot.core;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which days are 빨간 날 on the home screen's month card (준: "일욜이랑 공휴일이 붉은색으로 표시되지 않아"). The holidays fixed by the
 * solar calendar are known here. 설날, 추석, 부처님오신날, 대체공휴일 and election days move every year, so they come from the
 * phone's holiday calendar (Google's 대한민국의 휴일, synced with the account) rather than a table here that could be wrong. That
 * calendar also lists days that are not off (어버이날, 스승의 날, 식목일: 기념일), and those stay black. Pure Java.
 */
public final class KoreanHolidays {
    private KoreanHolidays() {}

    /** The public holiday fixed on this solar date, or null. */
    public static String fixed(LocalDate day) {
        return switch (day.getMonthValue() * 100 + day.getDayOfMonth()) {
            case 101 -> "신정";
            case 301 -> "삼일절";
            case 505 -> "어린이날";
            case 606 -> "현충일";
            case 815 -> "광복절";
            case 1003 -> "개천절";
            case 1009 -> "한글날";
            case 1225 -> "성탄절";
            default -> null;
        };
    }

    private static final Pattern HOLIDAY_CALENDAR = Pattern.compile("휴일|공휴일|holiday", Pattern.CASE_INSENSITIVE);
    /** A calendar of holidays (Google's "대한민국의 휴일", "Holidays in South Korea"), not one of the user's own. */
    public static boolean holidayCalendar(String calendar) { return calendar != null && HOLIDAY_CALENDAR.matcher(calendar).find(); }

    private static final Pattern OFF = Pattern.compile(
        "설날|설 연휴|추석|부처님|석가탄신|대체\\s*공휴일|대체\\s*휴일|임시\\s*공휴일|삼일절|3[·.]?1절|어린이날|현충일|광복절|개천절|한글날|성탄절|기독탄신일|크리스마스|신정|선거"
            + "|new year|seollal|lunar|chuseok|buddha|children'?s day|memorial day|liberation day|foundation day|hangul|hangeul|christmas|independence movement"
            + "|alternative holiday|substitute holiday|election", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_OFF = Pattern.compile("이브|전날|\\beve\\b|기념일|observance", Pattern.CASE_INSENSITIVE);

    /**
     * Whether an event of a holiday calendar is a day off: its note says so (공휴일, "Public holiday"), or its name is one of
     * them; a 기념일 ("Observance", 크리스마스 이브) is not.
     */
    public static boolean dayOff(String title, String description) {
        String name = title == null ? "" : title, note = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (NOT_OFF.matcher(name).find() || note.contains("기념일") || note.contains("observance")) return false;
        if (note.contains("공휴일") || note.contains("public holiday")) return true;
        return OFF.matcher(name).find();
    }
}
