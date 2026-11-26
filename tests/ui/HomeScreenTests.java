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

    /** A suggestion about a day already over is left out (the 11-14~15 trip suggested on the 26th); prices and follow-ups are not dates gone. */
    @Test public void dropsSuggestionsAboutPastDays() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Seoul");
        long now = java.time.ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, zone).toInstant().toEpochMilli();
        java.util.function.Function<String, dev.jun.gyeot.core.Proactive.Card> card = t -> new dev.jun.gyeot.core.Proactive.Card("진주 통치모임 예산 검토", t, "예산 다시 봐 줘", "");
        assertTrue(dev.jun.gyeot.core.Proactive.stale(card.apply("마이아카이브에 기록된 11-14~15 일정·예산을 최종 확인하고, 출발 전 체크하면 좋겠어요."), now, zone));
        assertTrue(dev.jun.gyeot.core.Proactive.stale(card.apply("11월 20일 발표 준비"), now, zone));
        assertFalse("tomorrow is ahead", dev.jun.gyeot.core.Proactive.stale(card.apply("11월 27일 발표 준비"), now, zone));
        assertFalse("yesterday may still be followed up", dev.jun.gyeot.core.Proactive.stale(card.apply("11/25 회의 내용 확인"), now, zone));
        assertFalse("an after-the-fact follow-up", dev.jun.gyeot.core.Proactive.stale(card.apply("11-14~15 여행 후기 정리"), now, zone));
        assertFalse("percentages are not dates", dev.jun.gyeot.core.Proactive.stale(card.apply("SOXL 3.12% 하락, 1.5배 레버리지 점검"), now, zone));
        assertFalse("no date at all", dev.jun.gyeot.core.Proactive.stale(card.apply("AI 모델 카탈로그 업데이트"), now, zone));
    }
}
