package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.List;
import java.util.Locale;

/**
 * The home screen's date, light (준: "구글캘린더 실행이 이상한데", "가벼운 구글캘린더 없어?", "일반적으로 갤럭시 폰에서 위젯으로 있는
 * 구글캘린더 같이 가벼운 버전"): Google Calendar's own home screen widget (its month, else its first), shown over the home screen in
 * a small card instead of opening the whole app. The home screen is a launcher, so it hosts the widget as any launcher does: the
 * right to show it is asked once (Android's own dialog), the widget is kept (its id in the home screen's settings) and draws
 * itself; a tap in it is Google Calendar's (a day, an event). Outside the card closes it. Without Google Calendar's widget (or
 * when the right is refused) the phone's calendar opens at today, as the 오늘 page's events do. Main thread.
 */
final class CalendarWidget {
    static final String GOOGLE = "com.google.android.calendar";
    /** The home screen's widget host, and its requests for the right to bind and for a widget's own setup. */
    static final int HOST = 0x6779, BIND = 7551, SETUP = 7552;
    private static final String KEPT = "calendarWidget";
    private final Activity activity; private final SharedPreferences prefs; private final Ui ui;
    private AppWidgetHost host; private AppWidgetManager manager;
    private int pending = -1;
    /** The card while it shows (tests look at it). */
    Dialog shown;

    CalendarWidget(Activity activity, SharedPreferences prefs, Ui ui) { this.activity = activity; this.prefs = prefs; this.ui = ui; }
    private boolean ready() {
        if (manager != null) return true;
        try { manager = AppWidgetManager.getInstance(activity); host = new AppWidgetHost(activity.getApplicationContext(), HOST); return manager != null; }
        catch (RuntimeException none) { manager = null; return false; }
    }
    /** The home screen in front: the widget may draw. */
    void start() { if (ready()) try { host.startListening(); } catch (RuntimeException ignored) {} }
    void stop() { if (host != null) try { host.stopListening(); } catch (RuntimeException ignored) {} }

    /** Google Calendar's month widget, else its first; null when it has none (or is not installed). */
    AppWidgetProviderInfo provider() {
        if (!ready()) return null;
        AppWidgetProviderInfo first = null;
        List<AppWidgetProviderInfo> all;
        try { all = manager.getInstalledProviders(); } catch (RuntimeException none) { return null; }
        for (AppWidgetProviderInfo info : all) {
            if (info == null || info.provider == null || !GOOGLE.equals(info.provider.getPackageName())) continue;
            if (first == null) first = info;
            if (info.provider.getClassName().toLowerCase(Locale.ROOT).contains("month")) return info;
        }
        return first;
    }

    /** The date's tap: the widget's card (asking for the right first, once), or the phone's calendar at today. */
    void show() {
        AppWidgetProviderInfo info = provider();
        if (info == null) { fallback(); return; }
        int id = prefs.getInt(KEPT, -1);
        AppWidgetProviderInfo bound = id < 0 ? null : manager.getAppWidgetInfo(id);
        if (bound != null && info.provider.equals(bound.provider)) { card(id, bound); return; }
        if (id >= 0) { try { host.deleteAppWidgetId(id); } catch (RuntimeException ignored) {} prefs.edit().remove(KEPT).apply(); }
        id = host.allocateAppWidgetId();
        boolean allowed;
        try { allowed = manager.bindAppWidgetIdIfAllowed(id, info.provider); } catch (RuntimeException refused) { allowed = false; }
        if (allowed) { bound(id, info); return; }
        pending = id;
        try {
            activity.startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), BIND);
        } catch (RuntimeException none) { drop(id); fallback(); }
    }
    /** Bound: its own setup first when it has one, else the card. */
    private void bound(int id, AppWidgetProviderInfo info) {
        prefs.edit().putInt(KEPT, id).apply();
        if (info.configure != null) {
            pending = id;
            try { host.startAppWidgetConfigureActivityForResult(activity, id, 0, SETUP, null); return; } catch (RuntimeException none) { pending = -1; }
        }
        card(id, info);
    }
    /** What Android answered (the right to show it, the widget's setup); true when it was this one's. */
    boolean result(int request, int result) {
        if (request != BIND && request != SETUP) return false;
        int id = pending; pending = -1;
        if (id < 0 || !ready()) return true;
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (result != Activity.RESULT_OK || info == null) { drop(id); fallback(); return true; }
        if (request == BIND) bound(id, info); else card(id, info);
        return true;
    }
    private void drop(int id) { try { host.deleteAppWidgetId(id); } catch (RuntimeException ignored) {} if (prefs.getInt(KEPT, -1) == id) prefs.edit().remove(KEPT).apply(); }
    /** The phone's calendar at today (the 오늘 page's events open it so). */
    private void fallback() {
        Intent today = HtmlWallpaper.target(activity, "calendar");
        try { if (today != null) activity.startActivity(today); } catch (RuntimeException none) {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://calendar.google.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) {}
        }
    }

    /** The widget in a small card over the home screen: as wide as a phone allows (420dp at most), a month's height. */
    private void card(int id, AppWidgetProviderInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (shown != null) { try { shown.dismiss(); } catch (RuntimeException ignored) {} }
        AppWidgetHostView view;
        try { view = host.createView(activity.getApplicationContext(), id, info); } catch (RuntimeException none) { fallback(); return; }
        float density = activity.getResources().getDisplayMetrics().density;
        int screenDp = Math.round(activity.getResources().getDisplayMetrics().widthPixels / density);
        int widthDp = Math.min(420, screenDp - 32), heightDp = Math.min(400, Math.max(300, Math.round(widthDp * 0.95f)));
        try {
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
            view.updateAppWidgetSize(options, List.of(new SizeF(widthDp, heightDp)));
        } catch (RuntimeException ignored) { /* It draws at its own size. */ }
        FrameLayout card = new FrameLayout(activity);
        card.setBackground(ui.shape(Ui.CARD, 26)); card.setClipToOutline(true); card.setElevation(ui.dp(12));
        card.setContentDescription("구글 캘린더 위젯 · 바깥을 누르면 닫혀요");
        card.addView(view, new FrameLayout.LayoutParams(-1, -1));
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card, new ViewGroup.LayoutParams(ui.dp(widthDp), ui.dp(heightDp)));
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ui.dp(widthDp), ui.dp(heightDp)); w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setDimAmount(0.28f);
        }
        dialog.setOnDismissListener(d -> { if (shown == dialog) shown = null; });
        shown = dialog;
        dialog.show();
    }
    /** The card closes when the home screen goes away. */
    void close() { if (shown != null) { try { shown.dismiss(); } catch (RuntimeException ignored) {} shown = null; } }
}
