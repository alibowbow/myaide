package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The home screen's date (준: "구글캘린더 실행이 이상한데", "가벼운 구글캘린더 없어?", "일반적으로 갤럭시 폰에서 위젯으로 있는 구글캘린더 같이
 * 가벼운 버전"): Google Calendar's own month widget in a small card over the home screen, the right to show it asked once and the
 * widget kept; without it, the phone's calendar at today (as the 오늘 page's events), never the whole Google Calendar app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class, qualifiers="w411dp-h891dp-xxhdpi")
public class HomeDateTests {
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static AppWidgetProviderInfo provider(String cls) {
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(CalendarWidget.GOOGLE, cls); info.minWidth = 250; info.minHeight = 250;
        return info;
    }
    private static View find(View v, Class<?> type) {
        if (type.isInstance(v)) return v;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), type); if (c != null) return c; }
        return null;
    }

    @Test public void withoutGoogleCalendarsWidgetThePhonesCalendarAtToday() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences prefs = a.getSharedPreferences("dateTest", Context.MODE_PRIVATE);
        new CalendarWidget(a, prefs, new Ui(a)).show(); idle();
        Intent opened = shadowOf(a).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertNull("not the whole Google Calendar app", opened.getPackage());
        assertTrue("at today: " + opened.getData(), String.valueOf(opened.getData()).startsWith("content://com.android.calendar/time/"));
        // The live wallpaper's board (no widget there) opens the same light day.
        Intent wall = HtmlWallpaper.target(a, "gcalendar");
        assertNull(wall.getPackage()); assertTrue(String.valueOf(wall.getData()).startsWith("content://com.android.calendar/time/"));
    }

    @Test public void googleCalendarsMonthWidgetInASmallCardTheRightAskedOnce() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences prefs = a.getSharedPreferences("dateTest2", Context.MODE_PRIVATE);
        ShadowAppWidgetManager widgets = shadowOf(AppWidgetManager.getInstance(a));
        widgets.addInstalledProvider(provider("com.android.calendar.widget.ScheduleWidgetProvider"));
        widgets.addInstalledProvider(provider("com.android.calendar.widget.MonthViewWidgetProvider"));
        CalendarWidget calendar = new CalendarWidget(a, prefs, new Ui(a));
        assertTrue("its month", calendar.provider().provider.getClassName().contains("Month"));
        // Not yet allowed: Android's own question, once.
        widgets.setAllowedToBindAppWidgets(false);
        calendar.show(); idle();
        var asked = shadowOf(a).getNextStartedActivityForResult();
        assertNotNull("the right to show it asked", asked);
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_BIND, asked.intent.getAction());
        assertEquals(CalendarWidget.BIND, asked.requestCode);
        assertNull("no card before the answer", calendar.shown);
        // Refused: the phone's calendar instead.
        assertTrue(calendar.result(CalendarWidget.BIND, Activity.RESULT_CANCELED)); idle();
        Intent instead = shadowOf(a).getNextStartedActivity();
        assertNotNull(instead); assertTrue(String.valueOf(instead.getData()).startsWith("content://com.android.calendar/time/"));
        // Allowed: the card with the widget, small, over the home screen.
        widgets.setAllowedToBindAppWidgets(true);
        calendar.show(); idle();
        Dialog card = calendar.shown; assertNotNull("the card", card); assertTrue(card.isShowing());
        AppWidgetHostView view = (AppWidgetHostView) find(card.getWindow().getDecorView(), AppWidgetHostView.class);
        assertNotNull("Google Calendar's widget in it", view);
        assertEquals(CalendarWidget.GOOGLE, view.getAppWidgetInfo().provider.getPackageName());
        float d = a.getResources().getDisplayMetrics().density;
        assertTrue("narrower than the screen", card.getWindow().getAttributes().width <= a.getResources().getDisplayMetrics().widthPixels - 30 * d);
        assertTrue("a month's height, not the whole screen", card.getWindow().getAttributes().height <= 400 * d + 1);
        int kept = prefs.getInt("calendarWidget", -1); assertTrue("kept", kept >= 0);
        // Again: the same widget, no question.
        card.dismiss(); idle();
        while (shadowOf(a).getNextStartedActivityForResult() != null) { /* what came before */ }
        calendar.show(); idle();
        for (var r = shadowOf(a).getNextStartedActivityForResult(); r != null; r = shadowOf(a).getNextStartedActivityForResult())
            assertNotEquals("not asked again", AppWidgetManager.ACTION_APPWIDGET_BIND, r.intent.getAction());
        assertNotNull(calendar.shown); assertEquals(kept, prefs.getInt("calendarWidget", -1));
        // The home screen goes away: the card closes.
        calendar.close(); assertNull(calendar.shown);
    }
}
