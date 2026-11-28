package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import dev.jun.gyeot.core.KoreanHolidays;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The home screen's date as a month card (준: "캘린더가 너무 작게 떠", "일욜이랑 공휴일이 붉은색으로 표시되지 않아"): nearly as wide
 * as the phone, 일요일 and 공휴일 red, 토요일 blue, a 기념일 not red; the moving holidays (추석, 대체공휴일) from the phone's holiday
 * calendar, a user's own event named like one never; each day's events in its cell and under the month.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class, qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeDateTests {
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static long at(int y, int m, int d, int h) { return LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
    private static long utc(int y, int m, int d) { return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }

    @Test public void theWallpapersDateStillOpensThePhonesCalendar() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Intent wall = HtmlWallpaper.target(a, "gcalendar");
        assertNull(wall.getPackage()); assertTrue(String.valueOf(wall.getData()).startsWith("content://com.android.calendar/time/"));
    }

    @Test public void holidaysFromTheirCalendarOnly() {
        assertEquals("성탄절", KoreanHolidays.fixed(LocalDate.of(2026, 12, 25)));
        assertEquals("삼일절", KoreanHolidays.fixed(LocalDate.of(2027, 3, 1)));
        assertNull(KoreanHolidays.fixed(LocalDate.of(2026, 12, 24)));
        assertTrue(KoreanHolidays.holidayCalendar("대한민국의 휴일")); assertTrue(KoreanHolidays.holidayCalendar("Holidays in South Korea"));
        assertFalse(KoreanHolidays.holidayCalendar("내 캘린더"));
        assertTrue(KoreanHolidays.dayOff("추석", "")); assertTrue(KoreanHolidays.dayOff("대체공휴일", "공휴일"));
        assertTrue(KoreanHolidays.dayOff("Chuseok", "Public holiday")); assertTrue(KoreanHolidays.dayOff("제22대 국회의원 선거", ""));
        assertFalse("a 기념일", KoreanHolidays.dayOff("어버이날", "기념일")); assertFalse(KoreanHolidays.dayOff("크리스마스 이브", ""));
        assertFalse(KoreanHolidays.dayOff("Parents' Day", "Observance"));
    }

    @Test public void theMonthIsLargeWithSundaysAndHolidaysInRed() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        MonthCard card = new MonthCard(a, new Ui(a), Runnable::run);
        card.show(LocalDate.of(2026, 11, 29)); idle();
        assertNotNull("the card", card.shown); assertTrue(card.shown.isShowing());
        float d = a.getResources().getDisplayMetrics().density;
        int width = card.shown.getWindow().getAttributes().width;
        assertTrue("nearly the phone's width (not the small widget card) " + width / d, width >= 380 * d);

        // The phone's calendar for the month: a holiday calendar's 대체공휴일 (a day off) and a 기념일 (not), the user's own events.
        Map<LocalDate, MonthCard.Day> days = new HashMap<>();
        LocalDate from = MonthCard.firstCell(YearMonth.of(2026, 11)), to = from.plusDays(42);
        ZoneId zone = ZoneId.systemDefault();
        MonthCard.add(days, new MonthCard.Item(1, "대체공휴일", utc(2026, 11, 17), utc(2026, 11, 18), true, 0xff2e8b57, "대한민국의 휴일"), "공휴일", from, to, zone);
        MonthCard.add(days, new MonthCard.Item(2, "농업인의 날", utc(2026, 11, 11), utc(2026, 11, 12), true, 0xff2e8b57, "대한민국의 휴일"), "기념일", from, to, zone);
        MonthCard.add(days, new MonthCard.Item(3, "추석 선물 고르기", at(2026, 11, 20, 19), at(2026, 11, 20, 20), false, 0xff3f51b5, "내 캘린더"), "", from, to, zone);
        MonthCard.add(days, new MonthCard.Item(4, "가족 점심", at(2026, 11, 29, 12), at(2026, 11, 29, 14), false, 0xffe67c73, "내 캘린더"), "", from, to, zone);
        MonthCard.add(days, new MonthCard.Item(5, "LP 정리", at(2026, 11, 29, 16), at(2026, 11, 29, 17), false, 0xff33b679, "내 캘린더"), "", from, to, zone);
        MonthCard.add(days, new MonthCard.Item(6, "제주 여행", utc(2026, 11, 5), utc(2026, 11, 8), true, 0xfff6bf26, "내 캘린더"), "", from, to, zone);
        MonthCard.add(days, new MonthCard.Item(7, "치과", at(2026, 11, 12, 10), at(2026, 11, 12, 11), false, 0xff039be5, "내 캘린더"), "", from, to, zone);
        card.fill(YearMonth.of(2026, 11), days); idle();

        assertEquals("일요일 red", Ui.RED, card.ink(LocalDate.of(2026, 11, 22)));
        assertEquals("토요일 blue", Ui.BLUE, card.ink(LocalDate.of(2026, 11, 21)));
        assertEquals("a weekday as text", Ui.TEXT, card.ink(LocalDate.of(2026, 11, 19)));
        assertEquals("a day off from the holiday calendar red", Ui.RED, card.ink(LocalDate.of(2026, 11, 17)));
        assertEquals("대체공휴일", card.day(LocalDate.of(2026, 11, 17)).holiday);
        assertEquals("a 기념일 is not red", Ui.TEXT, card.ink(LocalDate.of(2026, 11, 11)));
        assertEquals("but named", "농업인의 날", card.day(LocalDate.of(2026, 11, 11)).holiday);
        assertEquals("the user's own event named like a holiday is an event", Ui.TEXT, card.ink(LocalDate.of(2026, 11, 20)));
        assertEquals(1, card.day(LocalDate.of(2026, 11, 20)).items.size());
        assertEquals("an all-day event over three days on each", 1, card.day(LocalDate.of(2026, 11, 7)).items.size());
        assertNull("its end day is not one of them", card.day(LocalDate.of(2026, 11, 8)));
        assertEquals(2, card.day(LocalDate.of(2026, 11, 29)).items.size());

        // Weeks shown: 5 for 2026-11 (starts on a Sunday), 5 for 2026-12, 6 for 2026-08 (starts on a Saturday).
        assertEquals(5, MonthCard.weeks(YearMonth.of(2026, 11))); assertEquals(5, MonthCard.weeks(YearMonth.of(2026, 12))); assertEquals(6, MonthCard.weeks(YearMonth.of(2026, 8)));
        // December: 성탄절 red with no calendar at all.
        card.show(LocalDate.of(2026, 12, 3)); idle();
        assertEquals(Ui.RED, card.ink(LocalDate.of(2026, 12, 25)));
        assertEquals("성탄절", card.day(LocalDate.of(2026, 12, 25)).holiday);

        // The picture, for a look (the calendar allowed: no line asking for it).
        shadowOf(a.getApplication()).grantPermissions(android.Manifest.permission.READ_CALENDAR);
        card.show(LocalDate.of(2026, 11, 29)); idle(); card.fill(YearMonth.of(2026, 11), days); idle();
        View root = card.shown.getWindow().getDecorView();
        int w = Math.round(400 * d);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight()); idle();
        assertTrue("a month's height and the day under it, not a screen's " + root.getHeight() / d, root.getHeight() <= 620 * d);
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(0xff2a1450); root.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "home_month_card.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
