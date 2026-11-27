package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.TradingViewLink;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;

/**
 * 트레이딩's own TradingView sign-in, for the whole app (준: "Ai 브라우저 구글 로그인으로 트레이딩뷰도 로그인 연동해 줄 수 있어?").
 * The AI 브라우저 is one Gecko profile (BrowserRuntime): the user signs in to TradingView there themselves, tapping 'Google' on
 * TradingView's own page (signIn); nothing is typed, kept or read for them. Whether that profile is signed in is asked of a
 * TradingView page in the same profile, off screen (check), or of the chart the project shows (TradingViewPane): the page says
 * it from its own &lt;html&gt; class and user object (content.js tradingviewAccount), never a cookie. The answer is kept, so the
 * project opens straight on the right chart, and checked again when it is old (TradingViewLink.due) or the user is back from
 * the sign-in. Main thread: Gecko is called from here and its pages load in Gecko's own process; nothing here waits for them.
 */
final class TradingViewLogin {
    private static TradingViewLogin instance;
    static TradingViewLogin get(Context context) { if (instance == null) instance = new TradingViewLogin(context.getApplicationContext()); return instance; }
    /** The kept state (TradingViewLink.Login's name), when it was last known, the plan, and 내 트뷰 차트 (on unless turned off). */
    static final String STATE = "tradingviewLogin", CHECKED = "tradingviewChecked", PRO = "tradingviewPro", MINE = "tradingviewMine";
    /** No Gecko here (the screen tests run without it): no page opens, the state is as kept. */
    static boolean noGecko = "robolectric".equals(android.os.Build.FINGERPRINT);
    private static final long LIMIT = 30_000;

    private final Context context; private final SharedPreferences settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private GeckoSession probe; private boolean checking, asked, signedOutNews; private String failure;

    private TradingViewLogin(Context context) { this.context = context; this.settings = ((GyeotApp) context).store().settings; }
    void listen(Runnable l) { listeners.addIfAbsent(l); }
    void unlisten(Runnable l) { listeners.remove(l); }
    private void changed() { for (Runnable l : listeners) l.run(); }

    TradingViewLink.Login state() {
        try { return TradingViewLink.Login.valueOf(settings.getString(STATE, "NONE")); } catch (RuntimeException unknown) { return TradingViewLink.Login.NONE; }
    }
    boolean checking() { return checking; }
    boolean pro() { return settings.getBoolean(PRO, false); }
    /** The project shows the user's own chart: signed in, and 내 트뷰 차트 not turned off. */
    boolean mine() { return state() == TradingViewLink.Login.IN && settings.getBoolean(MINE, true); }
    void mine(boolean on) { settings.edit().putBoolean(MINE, on).apply(); changed(); }
    /** Signed out since the last time this was asked, after having been signed in (the screen says so once). */
    boolean signedOutNews() { boolean news = signedOutNews; signedOutNews = false; return news; }
    /** Why the last check the user asked for found nothing (once), else null. */
    String failure() { String f = failure; failure = null; return f; }

    /**
     * What a TradingView page the app has open said of the sign-in ({known, classes, flag, guest}: content.js); where names it
     * for the phone log. An answer that says nothing changes nothing.
     */
    void heard(JSONObject said, String where) {
        if (said == null || !said.optBoolean("known")) return;
        String classes = said.optString("classes");
        TradingViewLink.Login found = TradingViewLink.account(classes, bool(said, "flag"), bool(said, "guest"));
        if (found == TradingViewLink.Login.NONE) return;
        TradingViewLink.Login before = state(), now = TradingViewLink.kept(before, found);
        settings.edit().putString(STATE, now.name()).putLong(CHECKED, System.currentTimeMillis()).putBoolean(PRO, found == TradingViewLink.Login.IN && TradingViewLink.pro(classes)).apply();
        if (before != now) {
            if (before == TradingViewLink.Login.IN) signedOutNews = true;
            try { ((GyeotApp) context).store().phoneLog("트레이딩뷰 · " + where + "에서 " + (now == TradingViewLink.Login.IN ? "로그인 확인" : "로그아웃 상태")); } catch (RuntimeException ignored) {}
        }
        changed();
    }
    private static Boolean bool(JSONObject o, String key) { Object v = o.opt(key); return v instanceof Boolean b ? b : null; }

    /**
     * The sign-in: TradingView's own sign-in page in a new tab of the AI 브라우저, where the user taps 'Google' (its popup opens
     * as a tab there and closes back onto the page) and signs in themselves. Checked again as soon as the project is back.
     */
    void signIn(Activity from) {
        asked = true;
        BrowserActivity.open(from, TradingViewLink.SIGN_IN);
    }
    /** Checks when it is time (TradingViewLink.due), or when the user is back from the sign-in. */
    void checkIfDue() { if (TradingViewLink.due(settings.getLong(CHECKED, 0), System.currentTimeMillis(), asked)) check(asked); }
    /**
     * Opens TradingView's light sign-in page off screen in the AI 브라우저's profile, binds the page bridge before it loads, asks
     * the page (on its bridge's ready) whether it is signed in, and closes it as soon as it said, it failed, or 30 seconds passed.
     * user: asked by the user, whose screen says why when it finds nothing.
     */
    void check(boolean user) {
        if (checking || noGecko) return;
        asked = false; checking = true; changed();
        GeckoSession s;
        try {
            s = new GeckoSession(new GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build());
            s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                // Only TradingView's pages (its sign-in redirects stay on it); nothing else opens in the check.
                @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                    return GeckoResult.fromValue(TradingViewLink.tradingView(request.uri) ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
                }
                @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) { return GeckoResult.fromValue(null); }
                @Override public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
                    main.post(() -> done(s, user, "tradingview.com을 열지 못했어요 (인터넷 연결 확인, 오류 " + error.code + ")")); return null;
                }
            });
            BrowserRuntime shared = BrowserRuntime.get(context);
            s.open(shared.runtime); probe = s;
            shared.bridge().accept(extension -> {
                if (probe != s) return;
                s.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
                    @Override public void onConnect(WebExtension.Port port) {
                        if (port.sender.session != s || !port.sender.isTopLevel()) { port.disconnect(); return; }
                        port.setDelegate(new WebExtension.PortDelegate() {
                            @Override public void onPortMessage(Object message, WebExtension.Port source) {
                                if (!(message instanceof JSONObject json)) return;
                                main.post(() -> {
                                    if (probe != s) return;
                                    if (json.optBoolean("ready")) { ask(source); return; }
                                    JSONObject said = json.optJSONObject("tradingview");
                                    if (said == null || !"account".equals(said.optString("event"))) return;
                                    heard(said, "로그인 확인");
                                    done(s, user, said.optBoolean("known") ? "" : "TradingView 페이지가 로그인 여부를 보여 주지 않았어요");
                                });
                            }
                            @Override public void onDisconnect(WebExtension.Port source) {}
                        });
                    }
                }, BrowserEngine.NATIVE);
                // Bound before the page loads: a page loaded earlier would have no one to answer.
                s.setActive(true); s.loadUri(TradingViewLink.PROBE);
            }, failed -> main.post(() -> done(s, user, "AI 브라우저의 페이지 연결을 준비하지 못했어요 (앱을 다시 열면 고쳐져요)")));
        } catch (RuntimeException | LinkageError unavailable) {
            probe = null; checking = false; if (user) failure = "이 기기에서 AI 브라우저 엔진을 열지 못했어요."; changed(); return;
        }
        main.postDelayed(() -> done(s, user, "30초 안에 tradingview.com이 답하지 않았어요"), LIMIT);
    }
    /** Asks a TradingView page whether it is signed in (it answers on the same port). */
    static void ask(WebExtension.Port port) {
        try { port.postMessage(dev.jun.gyeot.core.Jsons.obj("tradingview", "account")); } catch (RuntimeException gone) {}
    }
    private void done(GeckoSession s, boolean user, String why) {
        if (probe != s) return;
        probe = null; checking = false;
        try { s.close(); } catch (RuntimeException ignored) {}
        if (!why.isEmpty()) {
            try { ((GyeotApp) context).store().phoneLog("트레이딩뷰 로그인 확인 · " + why); } catch (RuntimeException ignored) {}
            if (user) failure = "트레이딩뷰 로그인을 확인하지 못했어요: " + why + ". 위의 칩을 눌러 다시 확인할 수 있어요.";
        }
        changed();
    }
}
