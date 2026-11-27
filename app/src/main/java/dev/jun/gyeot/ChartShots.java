package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;

/**
 * 트레이딩's chart pictures, taken where no one sees them (준: "백그라운드에서, 보일 수 있는 만큼 큰 화면으로, 내 앱 조작과
 * 안 겹치게"): a Gecko page the app opens off screen, desktop-wide and large, with TradingView's chart of the symbol (RSI and
 * MACD under it), one picture per interval, then the page is closed. The screen the user is on is never touched. Main thread.
 */
final class ChartShots {
    record Shot(String interval, Bitmap picture) {}
    /** The page's pixels: desktop layout (980 CSS px wide) drawn large. */
    static final int WIDTH = 1800, HEIGHT = 1130;
    private static final long SETTLE = 5500, RETRY = 3500, LIMIT = 80_000;

    private final Context context; private final String symbol; private final List<String> intervals; private final boolean dark;
    private final Consumer<List<Shot>> done;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Shot> shots = new ArrayList<>();
    private GeckoSession session; private GeckoDisplay display; private ImageReader reader;
    private int at = -1, tries; private long page; private boolean finished;

    private ChartShots(Context context, String symbol, List<String> intervals, boolean dark, Consumer<List<Shot>> done) {
        this.context = context.getApplicationContext(); this.symbol = symbol; this.intervals = intervals; this.dark = dark; this.done = done;
    }
    /** Takes one picture per interval and gives them, in order, to done (an empty list when none could be taken). */
    static void take(Context context, String symbol, List<String> intervals, boolean dark, Consumer<List<Shot>> done) {
        new ChartShots(context, symbol, intervals, dark, done).start();
    }
    private void start() {
        try {
            session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP).viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build());
            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override public void onPageStop(GeckoSession s, boolean success) { if (s == session && !finished) { long loaded = page; main.postDelayed(() -> shoot(loaded), SETTLE); } }
            });
            session.open(BrowserRuntime.get(context).runtime);
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> { try (android.media.Image image = r.acquireLatestImage()) { } catch (Exception ignored) { } }, main);
            display = session.acquireDisplay();
            display.surfaceChanged(new GeckoDisplay.SurfaceInfo.Builder(reader.getSurface()).size(WIDTH, HEIGHT).build());
            session.setActive(true);
        } catch (RuntimeException unavailable) { finish(); return; }
        main.postDelayed(this::finish, LIMIT);
        next();
    }
    private void next() {
        if (finished) return;
        if (++at >= intervals.size()) { finish(); return; }
        tries = 0; page++;
        session.load(new GeckoSession.Loader().data(html(symbol, intervals.get(at), dark), "text/html"));
    }
    private void shoot(long loaded) {
        if (finished || loaded != page || display == null) return;
        try {
            display.capturePixels().accept(picture -> {
                if (finished || loaded != page) { if (picture != null) picture.recycle(); return; }
                // TradingView draws after its data comes: a blank page gets a little longer once.
                if (picture == null || blank(picture)) {
                    if (picture != null) picture.recycle();
                    if (tries++ < 1) { main.postDelayed(() -> shoot(loaded), RETRY); return; }
                    next(); return;
                }
                shots.add(new Shot(intervals.get(at), picture)); next();
            }, failure -> { if (!finished && loaded == page) next(); });
        } catch (RuntimeException failed) { next(); }
    }
    private void finish() {
        if (finished) return; finished = true;
        try { if (display != null && session != null) session.releaseDisplay(display); } catch (RuntimeException ignored) { }
        try { if (session != null) session.close(); } catch (RuntimeException ignored) { }
        try { if (reader != null) reader.close(); } catch (RuntimeException ignored) { }
        display = null; session = null; reader = null;
        done.accept(new ArrayList<>(shots));
    }
    /** Almost one color all over: nothing drawn yet. */
    static boolean blank(Bitmap picture) {
        int w = picture.getWidth(), h = picture.getHeight(); if (w < 8 || h < 8) return true;
        int first = picture.getPixel(w / 2, h / 2), same = 0, n = 0;
        for (int y = h / 10; y < h; y += h / 10) for (int x = w / 10; x < w; x += w / 10) { n++; if (Math.abs((picture.getPixel(x, y) & 0xffffff) - (first & 0xffffff)) < 0x030303) same++; }
        return same > n * 0.95;
    }
    /** TradingView's advanced chart for the symbol and interval, filling the page, with RSI and MACD below the candles. */
    static String html(String symbol, String interval, boolean dark) {
        JSONObject config = new JSONObject();
        try {
            config.put("autosize", true).put("symbol", symbol).put("interval", interval).put("timezone", "Asia/Seoul").put("theme", dark ? "dark" : "light")
                .put("style", "1").put("locale", "kr").put("hide_side_toolbar", true).put("allow_symbol_change", false).put("save_image", false)
                .put("calendar", false).put("hide_volume", false).put("studies", new JSONArray().put("STD;RSI").put("STD;MACD"));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        String bg = dark ? "#131722" : "#ffffff";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;height:100%;background:" + bg + "}"
            + ".tradingview-widget-container,.tradingview-widget-container__widget{height:100%;width:100%}</style></head><body>"
            + "<div class=\"tradingview-widget-container\"><div class=\"tradingview-widget-container__widget\"></div>"
            + "<script type=\"text/javascript\" src=\"https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js\" async>"
            + config.toString().replace("</", "<\\/") + "</script></div></body></html>";
    }
}
