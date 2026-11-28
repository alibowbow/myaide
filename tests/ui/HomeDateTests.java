package dev.jun.gyeot;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** The home screen's date (준: "홈화면 왼쪽 상단부 날짜를 누르면 구글캘린더가 켜지게 해줘"): Google Calendar at today when it is there. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class HomeDateTests {
    @Test public void theDateOpensGoogleCalendar() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        // No calendar app at all: Google Calendar on the web.
        Intent none = HtmlWallpaper.target(c, "gcalendar");
        assertNotNull(none);
        assertEquals(Intent.ACTION_VIEW, none.getAction());
        assertEquals("https://calendar.google.com/", none.getDataString());
        // Google Calendar installed: it opens at today.
        ComponentName google = new ComponentName(HtmlWallpaper.GOOGLE_CALENDAR, HtmlWallpaper.GOOGLE_CALENDAR + ".AllInOneActivity");
        var pm = shadowOf(c.getPackageManager());
        pm.addActivityIfNotPresent(google);
        IntentFilter view = new IntentFilter(Intent.ACTION_VIEW); view.addCategory(Intent.CATEGORY_DEFAULT); view.addDataScheme("content"); view.addDataAuthority("com.android.calendar", null);
        pm.addIntentFilterForActivity(google, view);
        Intent opened = HtmlWallpaper.target(c, "gcalendar");
        assertNotNull(opened);
        assertEquals(HtmlWallpaper.GOOGLE_CALENDAR, opened.getPackage());
        Uri data = opened.getData();
        assertNotNull(data); assertTrue("at today: " + data, data.toString().startsWith("content://com.android.calendar/time/"));
        assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }
}
