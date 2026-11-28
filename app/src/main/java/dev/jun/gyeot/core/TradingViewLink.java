package dev.jun.gyeot.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 트레이딩's TradingView button (준: "내트뷰 누르면 그냥 ai브라우저로만 열리기", "트레이딩뷰 공식 아이콘만 쓰자"): the address of
 * TradingView's chart page at the symbol and interval the page picked, opened in the AI 브라우저. Each part is checked, so a page
 * can put nothing else in the address.
 */
public final class TradingViewLink {
    private TradingViewLink() {}

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
    /** TradingView's chart page at this symbol and interval; either may be left out (the chart's own is kept). */
    public static String chartUrl(String symbol, String interval) {
        String s = symbol(symbol), i = interval(interval);
        StringBuilder url = new StringBuilder("https://www.tradingview.com/chart/");
        if (!s.isEmpty()) url.append("?symbol=").append(URLEncoder.encode(s, StandardCharsets.UTF_8));
        if (!i.isEmpty()) url.append(s.isEmpty() ? '?' : '&').append("interval=").append(i);
        return url.toString();
    }
}
