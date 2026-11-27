package dev.jun.gyeot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.TradingViewLink;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

/**
 * 트레이딩's chart when the user is signed in to TradingView (준: "프로젝트에 트뷰", "무료로 쓰면 미증시 실시간데이터도 못 받고 트뷰
 * 기능도 제약이 있어서"): TradingView's own chart page, in the AI 브라우저's engine and profile (where the user signed in), so it is
 * their last layout with their indicators, drawings, alerts and data subscriptions (real-time US data where their account has it;
 * the embed widgets never do). It sits where the page's chart cards were (ProjectActivity places it, the page turns its cards into
 * the watchlist that picks the symbol). Drawn only while shown: the page closes when the screen stops (release) and opens again
 * when it is back. Every TradingView page here says whether it is signed in (TradingViewLogin.heard); a sign-in page, another
 * site or a new window goes to the AI 브라우저 instead. Main thread.
 */
final class TradingViewPane {
    /** The screen tests draw a stand-in (Robolectric has no Gecko). */
    static boolean standIn = TradingViewLogin.noGecko;
    final FrameLayout host;
    private final Activity activity; private final Handler main = new Handler(Looper.getMainLooper());
    private GeckoView view; private GeckoSession session; private boolean bound; private String url = "", pending;
    private int revived;

    TradingViewPane(Activity activity, Ui ui) {
        this.activity = activity;
        host = new FrameLayout(activity); host.setBackgroundColor(Ui.BG); host.setVisibility(View.GONE);
        host.setContentDescription("내 트레이딩뷰 차트");
        if (standIn) {
            TextView mark = ui.text("내 트레이딩뷰 차트", 13, 600, Ui.MUTED); mark.setGravity(Gravity.CENTER); mark.setBackgroundColor(Ui.CARD);
            host.addView(mark, new FrameLayout.LayoutParams(-1, -1));
        }
    }
    /** The address shown (or about to be), "" when closed. */
    String url() { return url; }
    boolean open() { return session != null; }

    /** Shows this chart address (TradingViewLink.chartUrl); the same one again changes nothing. */
    void show(String address) {
        if (standIn) { url = address; return; }
        try {
            if (view == null) {
                view = new GeckoView(activity); view.setActivityContextDelegate(() -> activity); view.coverUntilFirstPaint(Ui.BG);
                host.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
            }
            GeckoSession s = session();
            if (view.getSession() != s) view.setSession(s);
            s.setActive(host.getVisibility() == View.VISIBLE);
            if (address.equals(url)) return;
            url = address;
            if (bound) s.loadUri(address); else pending = address;
        } catch (RuntimeException | LinkageError unavailable) { release(); }
    }
    /** Shown or hidden for a moment (an AI sheet over the page): the page stays, it only stops drawing. */
    void drawing(boolean on) { if (session != null) try { session.setActive(on); } catch (RuntimeException ignored) {} }
    /** The page closed and its memory given back (the screen stopped, or back to the widgets); show() opens it again. */
    void release() {
        GeckoSession s = session; session = null; bound = false; pending = null; url = "";
        if (view != null) try { view.releaseSession(); } catch (RuntimeException ignored) {}
        if (s != null) try { s.close(); } catch (RuntimeException ignored) {}
    }
    /** The chart as drawn now (for an AI analysis whose off-screen pictures failed); null when it cannot be had. */
    void capture(Consumer<Bitmap> done) {
        if (view == null || session == null || view.getWidth() == 0) { done.accept(null); return; }
        try { view.capturePixels().accept(done::accept, failed -> done.accept(null)); } catch (RuntimeException failed) { done.accept(null); }
    }

    private GeckoSession session() {
        if (session != null) return session;
        GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build());
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                String uri = request.uri;
                if (TradingViewLink.tradingView(uri) && !TradingViewLink.signInPage(uri)) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                // Signing in is the AI 브라우저's (its Google popup works there); another site the user tapped opens there as a tab.
                if (request.hasUserGesture) main.post(() -> elsewhere(uri));
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) { main.post(() -> elsewhere(uri)); return GeckoResult.fromValue(null); }
        });
        // The page's process killed (the phone needed memory) or crashed: opened again at the chart, twice at most.
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onKill(GeckoSession session) { main.post(() -> revive(s)); }
            @Override public void onCrash(GeckoSession session) { main.post(() -> revive(s)); }
        });
        BrowserRuntime shared = BrowserRuntime.get(activity);
        s.open(shared.runtime);
        shared.bridge().accept(extension -> {
            if (session != s) return;
            s.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
                @Override public void onConnect(WebExtension.Port port) {
                    if (port.sender.session != s || !port.sender.isTopLevel()) { port.disconnect(); return; }
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override public void onPortMessage(Object message, WebExtension.Port source) {
                            if (!(message instanceof JSONObject json)) return;
                            main.post(() -> {
                                if (session != s) return;
                                // The chart only, its side panel folded (content.js tradingviewChartOnly); then whether it is signed in.
                                if (json.optBoolean("ready")) { chartOnly(source); TradingViewLogin.ask(source); return; }
                                JSONObject said = json.optJSONObject("tradingview");
                                if (said != null && "account".equals(said.optString("event"))) TradingViewLogin.get(activity).heard(said, "트레이딩 차트");
                            });
                        }
                        @Override public void onDisconnect(WebExtension.Port source) {}
                    });
                }
            }, BrowserEngine.NATIVE);
            loadPending(s);
        }, failed -> loadPending(s));
        session = s;
        return s;
    }
    /** The big chart shows the chart alone (준: "옆에 목록은 디폴트로 끈 상태로 차트만"): TradingView's side panel folded. */
    static void chartOnly(WebExtension.Port port) {
        try { port.postMessage(dev.jun.gyeot.core.Jsons.obj("tradingview", "chart_only")); } catch (RuntimeException gone) {}
    }
    /** The bridge is bound (or could not be: the chart still loads, it only cannot say whether it is signed in). */
    private void loadPending(GeckoSession s) {
        main.post(() -> { if (session != s) return; bound = true; String next = pending; pending = null; if (next != null) s.loadUri(next); });
    }
    private void elsewhere(String uri) {
        if (activity.isDestroyed() || uri == null || !(uri.startsWith("https://") || uri.startsWith("http://"))) return;
        try { BrowserActivity.open(activity, uri); } catch (RuntimeException ignored) {}
    }
    private void revive(GeckoSession which) {
        if (which != session || activity.isDestroyed()) return;
        String at = url; release();
        if (revived++ < 2 && !at.isEmpty()) show(at);
    }
}
