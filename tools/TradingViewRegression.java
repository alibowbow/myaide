package dev.jun.gyeot.core;

import java.util.List;

/** 트레이딩's own TradingView: addresses, the signed-in reading, the chip's words, when to check, the chart's place. */
public final class TradingViewRegression {
    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    private static void same(Object want, Object got, String message) { if (!java.util.Objects.equals(want, got)) throw new AssertionError(message + ": " + got); }
    public static void main(String[] args) {
        // Addresses: the user's chart at a symbol and interval, each checked; nothing else can be put in.
        same("https://www.tradingview.com/chart/?symbol=NASDAQ%3ANVDA&interval=D", TradingViewLink.chartUrl("NASDAQ:NVDA", "D"), "chart address");
        same("https://www.tradingview.com/chart/?symbol=BINANCE%3ABTCUSDT.P&interval=60", TradingViewLink.chartUrl(" binance:btcusdt.p ", "60"), "upper case, trimmed");
        same("https://www.tradingview.com/chart/?symbol=NYSE%3ABRK.B", TradingViewLink.chartUrl("NYSE:BRK.B", "7"), "an interval the page does not take is left out");
        same("https://www.tradingview.com/chart/?interval=W", TradingViewLink.chartUrl("NVDA\"><script>", "w"), "a symbol that is not one is left out");
        same("https://www.tradingview.com/chart/", TradingViewLink.chartUrl(null, null), "the layout as it is");
        same("", TradingViewLink.symbol("NASDAQ:NVDA&interval=1#x"), "no address parts in a symbol");
        same("KRX:005930", TradingViewLink.symbol("krx:005930"), "Korean stock");
        same("SOXL", TradingViewLink.symbol("SOXL"), "a bare ticker");
        // Only TradingView's own pages, and its sign-in pages go to the AI 브라우저.
        check(TradingViewLink.tradingView("https://www.tradingview.com/chart/"), "www");
        check(TradingViewLink.tradingView("https://kr.tradingview.com/chart/abc/"), "kr subdomain");
        check(!TradingViewLink.tradingView("http://www.tradingview.com/"), "not plain http");
        check(!TradingViewLink.tradingView("https://tradingview.com.evil.example/"), "brand boundary");
        check(!TradingViewLink.tradingView("https://user@www.tradingview.com/"), "no user info");
        check(!TradingViewLink.tradingView("javascript:alert(1)"), "no script");
        check(!TradingViewLink.tradingView(null), "nothing");
        check(TradingViewLink.signInPage("https://www.tradingview.com/accounts/signin/?next=%2F"), "sign-in page");
        check(!TradingViewLink.signInPage("https://www.tradingview.com/chart/?symbol=X"), "chart is not sign-in");
        check(!TradingViewLink.signInPage("https://accounts.example/accounts/signin/"), "someone else's sign-in");
        // Signed in, from what the page shows: its <html> class first (as TradingView serves it to a guest), then its flag, then its user.
        same(TradingViewLink.Login.OUT, TradingViewLink.account("is-not-authenticated is-not-pro  theme-light", true, false), "guest class beats the rest");
        same(TradingViewLink.Login.IN, TradingViewLink.account("is-authenticated is-pro theme-dark", null, null), "signed-in class");
        check(TradingViewLink.pro("is-authenticated is-pro theme-dark") && !TradingViewLink.pro("is-authenticated is-not-pro"), "paid plan");
        same(TradingViewLink.Login.IN, TradingViewLink.account("theme-dark", true, null), "flag when no class");
        same(TradingViewLink.Login.OUT, TradingViewLink.account("", null, true), "Guest user");
        same(TradingViewLink.Login.IN, TradingViewLink.account(null, null, false), "a named user");
        same(TradingViewLink.Login.NONE, TradingViewLink.account("theme-light", null, null), "the page said nothing");
        same(TradingViewLink.Login.NONE, TradingViewLink.account("my-is-authenticated-thing", null, null), "a class that only contains the word");
        // Kept: OUT only after having been signed in here; an empty answer keeps what was known.
        same(TradingViewLink.Login.NONE, TradingViewLink.kept(TradingViewLink.Login.NONE, TradingViewLink.Login.OUT), "never signed in");
        same(TradingViewLink.Login.OUT, TradingViewLink.kept(TradingViewLink.Login.IN, TradingViewLink.Login.OUT), "signed out");
        same(TradingViewLink.Login.OUT, TradingViewLink.kept(TradingViewLink.Login.OUT, TradingViewLink.Login.OUT), "still signed out");
        same(TradingViewLink.Login.IN, TradingViewLink.kept(TradingViewLink.Login.OUT, TradingViewLink.Login.IN), "signed in again");
        same(TradingViewLink.Login.IN, TradingViewLink.kept(TradingViewLink.Login.IN, TradingViewLink.Login.NONE), "unknown keeps it");
        // The chip: short words, each state its own.
        same("트뷰 로그인", TradingViewLink.chip(TradingViewLink.Login.NONE), "sign in");
        same("내 트뷰", TradingViewLink.chip(TradingViewLink.Login.IN), "mine");
        same("로그아웃 상태", TradingViewLink.chip(TradingViewLink.Login.OUT), "signed out");
        for (TradingViewLink.Login state : TradingViewLink.Login.values()) check(TradingViewLink.chip(state).length() <= 7, "chip fits the bar: " + state);
        check(TradingViewLink.chipDescription(TradingViewLink.Login.IN, true).contains("확인하는 중"), "checking said");
        // When to check: asked, never, stale, or a clock that went back.
        long now = 10_000_000L;
        check(TradingViewLink.due(0, now, false), "never checked");
        check(!TradingViewLink.due(now - 60_000, now, false), "fresh");
        check(TradingViewLink.due(now - 60_000, now, true), "asked (back from the sign-in)");
        check(TradingViewLink.due(now - TradingViewLink.FRESH - 1, now, false), "stale");
        check(TradingViewLink.due(now + 60_000, now, false), "clock went back");
        // Pictures on the user's own chart: the interval the user was on last.
        same(List.of("D", "60", "240"), TradingViewLink.shootingOrder(List.of("D", "240", "60"), "240"), "user's interval last");
        same(List.of("60", "15"), TradingViewLink.shootingOrder(List.of("60", "15"), "D"), "not among them: as asked");
        // The chart's place: above the page on a phone, beside it on a wide landscape screen, all of it when made large.
        TradingViewLink.Pane phone = TradingViewLink.pane(411, 760, false);
        check(!phone.beside() && phone.size() >= 400 && phone.size() <= 760 - 200, "phone: above, a little more than half: " + phone);
        TradingViewLink.Pane small = TradingViewLink.pane(360, 420, false);
        check(!small.beside() && small.size() == 260, "a short screen keeps a usable chart: " + small);
        TradingViewLink.Pane wide = TradingViewLink.pane(900, 600, false);
        check(wide.beside() && wide.size() == 540, "wide landscape: beside: " + wide);
        TradingViewLink.Pane tablet = TradingViewLink.pane(800, 1200, false);
        check(!tablet.beside() && tablet.size() == 672, "tall tablet: above: " + tablet);
        same(new TradingViewLink.Pane(false, -1), TradingViewLink.pane(411, 760, true), "large: all of it");
        System.out.println("TradingView link checks passed");
    }
}
