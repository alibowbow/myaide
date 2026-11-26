package dev.jun.gyeot;

import android.os.Looper;
import dev.jun.gyeot.core.AppStacks;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** 들퍼레 홈 화면: it starts as a home screen app, and the user's own apps (their home screen of 2026-11-26) land in the stacks meant. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class HomeScreenTests {
    private static String of(String pkg, String label) { return AppStacks.of(pkg, label, -1, false, false); }

    @Test public void sortsTheUsersApps() {
        assertEquals("money", of("com.dunamu.exchange", "업비트"));
        assertEquals("money", of("com.binance.dev", "Binance"));
        assertEquals("money", of("com.tradingview.tradingviewapp", "TradingView"));
        assertEquals("money", of("com.samsungpop.android.mpop", "삼성증권 mPOP"));
        assertEquals("money", of("nh.smart.nhallonepay", "NH pay"));
        assertEquals("chat", of("com.kakao.talk", "카카오톡"));
        assertEquals("chat", of("org.telegram.messenger", "Telegram"));
        assertEquals("chat", of("com.kakaoenterprise.kakaowork", "카카오워크"));
        assertEquals("ai", of("ai.x.grok", "Grok Bot"));
        assertEquals("ai", of("dev.jun.gyeot", "들퍼레"));
        assertEquals("ai", of("com.scatterlab.zeta", "zeta"));
        assertEquals("map", of("com.nhn.android.nmap", "네이버지도"));
        assertEquals("map", of("com.google.android.apps.maps", "지도"));
        assertEquals("map", of("com.teslamotors.tesla", "Tesla"));
        assertEquals("shop", of("com.fineapp.yogiyo", "요기요"));
        assertEquals("shop", of("com.towneers.www", "당근"));
        assertEquals("shop", of("com.kcar.app", "K Car 직영중고차"));
        assertEquals("media", of("com.google.android.youtube", "YouTube"));
        assertEquals("photo", of("com.sec.android.gallery3d", "갤러리"));
        assertEquals("photo", of("com.peace.SilentCamera", "무음 카메라"));
        assertEquals("web", of("com.android.chrome", "Chrome"));
        assertEquals("web", of("com.nhn.android.search", "NAVER"));
        assertEquals("tools", of("com.sec.android.app.clockpackage", "시계"));
        assertEquals("google", of("com.google.android.apps.docs", "Drive"));
        assertEquals("no short word inside another", "etc", of("com.example.workflow", "Workflow"));
        assertEquals("game", AppStacks.of("com.example.puzzle", "Puzzle", AppStacks.GAME, false, false));
    }
    @Test public void findsByInitials() {
        assertEquals("ㅋㅋㅇㅌ", AppStacks.initials("카카오톡"));
        assertTrue(AppStacks.score("ㅋㅋㅇ", "카카오톡", "com.kakao.talk") > 0);
        assertTrue("the start of the name first", AppStacks.score("카카", "카카오톡", "com.kakao.talk") > AppStacks.score("카오", "카카오톡", "com.kakao.talk"));
        assertEquals(0, AppStacks.score("ㅂㄴ", "카카오톡", "com.kakao.talk"));
        assertEquals(java.util.List.of("com.kakao.talk", "com.android.chrome"), AppStacks.dock(java.util.List.of("com.android.chrome", "com.example", "com.kakao.talk"), 6));
    }
    @Test @Config(qualifiers="w714dp-h831dp-xxhdpi") public void startsAsAHomeScreen() throws Exception {
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        JSONObject apps = a.apps();
        assertEquals("every stack offered", AppStacks.STACKS.size(), apps.getJSONArray("stacks").length());
        assertNotNull("a dock, even empty", apps.getJSONArray("dock"));
        // Back never closes a home screen.
        a.onBackPressed(); shadowOf(Looper.getMainLooper()).idle();
        assertFalse(a.isFinishing());
    }

    /** 사이드 독: its handle comes and goes with the setting, and a tap with no apps in the dock says what to do. */
    @Test public void sideDockFollowsItsSetting() {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        AppStore store = app.store();
        SideDock dock = new SideDock(app, store);
        store.settings.edit().putBoolean("sideDock", true).commit();
        dock.refresh(); shadowOf(Looper.getMainLooper()).idle();
        android.view.WindowManager wm = app.getSystemService(android.view.WindowManager.class);
        int withHandle = ((org.robolectric.shadows.ShadowWindowManagerImpl) org.robolectric.shadow.api.Shadow.extract(wm)).getViews().size();
        assertTrue("the handle is on the screen", withHandle >= 1);
        store.settings.edit().putBoolean("sideDock", false).commit();
        dock.refresh(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("turned off, it goes", withHandle - 1, ((org.robolectric.shadows.ShadowWindowManagerImpl) org.robolectric.shadow.api.Shadow.extract(wm)).getViews().size());
        dock.close();
    }
}
