package dev.jun.gyeot.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 트레이딩's own TradingView (준: "Ai 브라우저 구글 로그인으로 트레이딩뷰도 로그인 연동", "무료로 쓰면 미증시 실시간데이터도 못 받고
 * 트뷰 기능도 제약이 있어서"): the embed widgets are always anonymous (delayed US data, no layouts), so a signed-in user's chart
 * is TradingView's own chart page, opened in the AI 브라우저's engine where the user signed in themselves (with Google). What
 * the app decides about it, with no Android in it: the addresses, whether a page says it is signed in (from what the page
 * shows, never a cookie), the chip's words, when to check again, and where the chart goes on the screen.
 */
public final class TradingViewLink {
    private TradingViewLink() {}

    /** TradingView's own sign-in page; the user taps 'Google' there and signs in themselves. Nothing is typed or read for them. */
    public static final String SIGN_IN = "https://www.tradingview.com/accounts/signin/";
    /**
     * A light page (about 80 KB, its &lt;html&gt; class written by the server) that says whether the browser is signed in; the
     * check closes it as soon as the page has said.
     */
    public static final String PROBE = SIGN_IN;
    /** How long a check stays good: the project asks again when it opens after this. */
    public static final long FRESH = 15 * 60_000L;

    public enum Login { NONE, IN, OUT }

    /** The intervals TradingView's chart page takes in its address (the page's 15분 · 1시간 · 4시간 · 일 · 주, and a few more). */
    private static final Set<String> INTERVALS = Set.of("1", "3", "5", "15", "30", "45", "60", "120", "180", "240", "D", "W", "M");
    /** EXCHANGE:TICKER as TradingView writes it (NASDAQ:NVDA, BINANCE:BTCUSDT.P, NYSE:BRK.B, KRX:005930, FX:EURUSD). */
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9_.!&-]{0,29}(?::[A-Z0-9][A-Z0-9_.!&/-]{0,39})?");

    /** The symbol cleaned (upper case, no spaces), or "" when it is not one TradingView would take. */
    public static String symbol(String value) {
        String s = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return SYMBOL.matcher(s).matches() ? s : "";
    }
    /** The interval when the chart page takes it, else "". */
    public static String interval(String value) {
        String v = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return INTERVALS.contains(v) ? v : "";
    }
    /**
     * The user's own chart page (their last layout: its indicators, drawings and their data subscriptions) at this symbol and
     * interval; either may be left out (the layout's own is kept).
     */
    public static String chartUrl(String symbol, String interval) {
        String s = symbol(symbol), i = interval(interval);
        StringBuilder url = new StringBuilder("https://www.tradingview.com/chart/");
        if (!s.isEmpty()) url.append("?symbol=").append(URLEncoder.encode(s, StandardCharsets.UTF_8));
        if (!i.isEmpty()) url.append(s.isEmpty() ? '?' : '&').append("interval=").append(i);
        return url.toString();
    }
    /** An https page of TradingView itself (tradingview.com or one of its subdomains: kr., www., s3.). */
    public static boolean tradingView(String url) {
        try {
            URI u = URI.create(url == null ? "" : url.strip());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getRawUserInfo() == null && (host.equals("tradingview.com") || host.endsWith(".tradingview.com"));
        } catch (RuntimeException invalid) { return false; }
    }
    /** TradingView's sign-in pages, which belong in the AI 브라우저 (the chart's own frame does not sign in). */
    public static boolean signInPage(String url) {
        if (!tradingView(url)) return false;
        String path = URI.create(url.strip()).getRawPath();
        return path != null && path.toLowerCase(Locale.ROOT).startsWith("/accounts/");
    }

    /**
     * Whether a TradingView page is signed in, from what the page itself shows: its &lt;html&gt; class (is-authenticated or
     * is-not-authenticated, on every page), else its is_authenticated flag, else its user object (Guest or not). null where the
     * page did not say. Unknown when none says.
     */
    public static Login account(String htmlClass, Boolean flag, Boolean guest) {
        String c = " " + (htmlClass == null ? "" : htmlClass.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")) + " ";
        if (c.contains(" is-authenticated ")) return Login.IN;
        if (c.contains(" is-not-authenticated ")) return Login.OUT;
        if (flag != null) return flag ? Login.IN : Login.OUT;
        if (guest != null) return guest ? Login.OUT : Login.IN;
        return Login.NONE;
    }
    /** A paid plan (is-pro on the page): the user's real-time data subscriptions come with an account, shown as said. */
    public static boolean pro(String htmlClass) {
        String c = " " + (htmlClass == null ? "" : htmlClass.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")) + " ";
        return c.contains(" is-pro ");
    }
    /**
     * The state kept: what a check found. Signed out after having been signed in here stays OUT (the chip says so); signed out
     * before ever signing in is NONE (the chip offers the sign-in).
     */
    public static Login kept(Login before, Login found) {
        if (found == Login.IN) return Login.IN;
        if (found == Login.OUT) return before == Login.IN || before == Login.OUT ? Login.OUT : Login.NONE;
        return before == null ? Login.NONE : before;
    }
    /** The chip at the end of the project's bar: short (the bar is a phone's width), the state in its words. */
    public static String chip(Login state) {
        return switch (state == null ? Login.NONE : state) { case IN -> "내 트뷰"; case OUT -> "로그아웃 상태"; default -> "트뷰 로그인"; };
    }
    /** What a screen reader says of the chip, with what a tap does. */
    public static String chipDescription(Login state, boolean checking) {
        String now = switch (state == null ? Login.NONE : state) {
            case IN -> "내 트레이딩뷰에 로그인돼 있어요. 눌러서 차트 보기를 바꿔요";
            case OUT -> "트레이딩뷰가 로그아웃 상태예요. 눌러서 AI 브라우저에서 다시 로그인해요";
            default -> "트레이딩뷰 로그인. 눌러서 AI 브라우저에서 Google로 로그인해요";
        };
        return checking ? now + ". 로그인을 확인하는 중" : now;
    }
    /** Check again: asked (back from the sign-in), never checked, or the last check is older than FRESH. */
    public static boolean due(long checked, long now, boolean asked) {
        return asked || checked <= 0 || now - checked > FRESH || now < checked;
    }
    /**
     * The order the pictures are taken on the user's own chart: the interval the user was on last (TradingView may keep the last
     * interval the layout showed), the others before it as asked.
     */
    public static List<String> shootingOrder(List<String> intervals, String current) {
        List<String> out = new ArrayList<>(intervals == null ? List.of() : intervals);
        if (current != null && out.remove(current)) out.add(current);
        return out;
    }

    /** Where the chart goes in the project's screen: beside the page (wide) or above it, and its size in dp (-1: all of it). */
    public record Pane(boolean beside, int size) {}
    /**
     * The chart pane for a screen of this size (dp): the whole of it when made large; on a wide landscape screen 60% of the width
     * beside the page; else above the page, a little more than half the height, the page keeping at least 200dp for the
     * watchlist below it and the chart at least 260dp (TradingView's own toolbars take room).
     */
    public static Pane pane(int widthDp, int heightDp, boolean large) {
        if (large || widthDp <= 0 || heightDp <= 0) return new Pane(false, -1);
        if (widthDp >= 720 && widthDp > heightDp) return new Pane(true, Math.round(widthDp * 0.6f));
        int size = Math.min(Math.round(heightDp * 0.56f), heightDp - 200);
        return new Pane(false, Math.max(size, Math.min(260, heightDp)));
    }
}
