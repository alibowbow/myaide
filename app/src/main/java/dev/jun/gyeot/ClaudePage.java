package dev.jun.gyeot;

import android.content.SharedPreferences;
import java.util.Locale;

/**
 * 들퍼레 홈 화면's left page as Claude Code (준: "홈화면 왼쪽 페이지 자체를 내 ai브라우저로 https://claude.ai/code 이페이지를 띄울 수
 * 있어?", "왼쪽 페이지는 하단 독 없애고", then "내 들퍼레 ai 브라우저가 안 뜨고 클로드 페이지만 떠", "일단 1번으로 가자"): a swipe onto the
 * left page opens the AI 브라우저 itself (address bar, tabs, the AI) at claude.ai/code, its claude.ai tab again if one is open
 * (BrowserActivity.openFromHome), and the home screen goes back to 홈 under it. The launcher shows a stand-in card there meanwhile
 * and no dock.
 *
 * Ways back (준: "뒤로 가기는 브라우저 내에서 필요한거자나", "뒤로가기로 홈페이지 돌아가는 건 넣지마, 홈버튼이나 우측 스와이프로"): the
 * browser's back stays the page's history only; its ⌂ button, a swipe in from its right edge (as from the left page to 홈) and
 * the phone's home button go to 홈.
 */
final class ClaudePage {
    static final String HOME = "https://claude.ai/code";
    /** In HomeActivity.PREFS: the left page is Claude Code (on unless turned off in 화면 꾸미기). */
    static final String ON = "claudeLeft";
    static boolean on(SharedPreferences prefs) { return prefs.getBoolean(ON, true); }

    /**
     * The launcher's word on its first page (launcher.html tellLeft: its scroll in pages, 0 the left page shown; the swipe ended;
     * Launchpad over it), kept to tell when to open the browser: once a swipe (or a tap on its dot, or 퍼레's home_page) has
     * come to rest on the left page and nothing covers it. A home screen that merely starts there (drawn again) opens nothing.
     */
    static final class Swipe {
        private boolean moving, opened;
        /**
         * True when this report should open the browser: once a swipe toward the left page is most of the way there (EARLY, 준:
         * "화면 뜨는 딜레이를 획기적으로 줄여줘": the page's picture shows meanwhile, BrowserActivity.leftShot), or comes to rest on
         * it; once a swipe.
         */
        boolean opens(float pages, boolean settled, boolean covered) {
            if (covered) { if (settled) { moving = false; opened = false; } return false; }
            if (!settled) {
                if (pages < 1) moving = true;
                if (moving && !opened && pages <= EARLY) { opened = true; return true; }
                return false;
            }
            boolean there = moving && !opened && pages < .02f;
            moving = false; opened = false;
            return there;
        }
    }
    /** How far onto the left page a swipe has come (in pages from it) when the browser starts to open. */
    static final float EARLY = .25f;

    private static String host(String uri) {
        try { String h = java.net.URI.create(uri.strip()).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (RuntimeException bad) { return ""; }
    }
    private static boolean https(String uri) { return uri != null && uri.regionMatches(true, 0, "https://", 0, 8); }
    private static boolean under(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
    /** claude.ai itself (a tab there is the one the left page opens again). */
    static boolean inside(String uri) { return https(uri) && under(host(uri), "claude.ai"); }
}
