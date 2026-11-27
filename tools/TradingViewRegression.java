package dev.jun.gyeot.core;

/** 트레이딩's TradingView button: the chart page's address, each part checked. */
public final class TradingViewRegression {
    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    private static void same(Object want, Object got, String message) { if (!java.util.Objects.equals(want, got)) throw new AssertionError(message + ": " + got); }
    public static void main(String[] args) {
        // The chart at a symbol and interval, each checked; nothing else can be put in.
        same("https://www.tradingview.com/chart/?symbol=NASDAQ%3ANVDA&interval=D", TradingViewLink.chartUrl("NASDAQ:NVDA", "D"), "chart address");
        same("https://www.tradingview.com/chart/?symbol=BINANCE%3ABTCUSDT.P&interval=60", TradingViewLink.chartUrl(" binance:btcusdt.p ", "60"), "upper case, trimmed");
        same("https://www.tradingview.com/chart/?symbol=NYSE%3ABRK.B", TradingViewLink.chartUrl("NYSE:BRK.B", "7"), "an interval the page does not take is left out");
        same("https://www.tradingview.com/chart/?interval=W", TradingViewLink.chartUrl("NVDA\"><script>", "w"), "a symbol that is not one is left out");
        same("https://www.tradingview.com/chart/", TradingViewLink.chartUrl(null, null), "the layout as it is");
        same("", TradingViewLink.symbol("NASDAQ:NVDA&interval=1#x"), "no address parts in a symbol");
        same("KRX:005930", TradingViewLink.symbol("krx:005930"), "Korean stock");
        same("SOXL", TradingViewLink.symbol("SOXL"), "a bare ticker");
        System.out.println("TradingView link checks passed");
    }
}
