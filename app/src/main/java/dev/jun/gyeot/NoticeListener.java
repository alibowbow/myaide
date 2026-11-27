package dev.jun.gyeot;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.jun.gyeot.core.Deliveries;
import dev.jun.gyeot.core.Notices;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 알림 비서: the notifications other apps post, kept on the phone (core/Notices) while the user has it on and has allowed
 * 알림 접근 for this app. Not this app's own, not ongoing ones (music, navigation, downloads), not group summaries, never codes.
 */
public final class NoticeListener extends NotificationListenerService {
    private static final Object LOCK = new Object();
    private static List<Notices.Notice> kept;
    static boolean on(Context context) { return ((GyeotApp) context.getApplicationContext()).store().settings.getBoolean("noticeOn", false); }
    /** 알림 접근 is allowed for this app. */
    static boolean allowed(Context context) {
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(context.getPackageName());
    }
    private static File file(Context context) { return new File(context.getFilesDir(), "notices.json"); }
    /** The kept notices, oldest first (a day at most). */
    static List<Notices.Notice> list(Context context) {
        synchronized (LOCK) {
            if (kept == null) {
                String json = "[]";
                try { File f = file(context); if (f.isFile()) json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) {}
                kept = Notices.load(json, System.currentTimeMillis());
            }
            return new java.util.ArrayList<>(kept);
        }
    }
    /** Forgets every kept notice (turned off, or 모두 지우기). */
    static void clear(Context context) { synchronized (LOCK) { kept = new java.util.ArrayList<>(); file(context).delete(); } }

    // ── the home screen's badges (HomeActivity) ─────────────────────
    private static volatile NoticeListener live;
    /** Told when the phone's notifications change, while the home screen is shown. */
    static volatile Runnable changed;
    @Override public void onListenerConnected() { super.onListenerConnected(); live = this; tell(); }
    @Override public void onListenerDisconnected() { if (live == this) live = null; super.onListenerDisconnected(); }
    @Override public void onNotificationRemoved(StatusBarNotification removed) { tell(); }
    private static void tell() { Runnable r = changed; if (r != null) try { r.run(); } catch (RuntimeException ignored) {} }
    /**
     * The notifications showing now, counted per app (only counts: no words are read): what a home screen puts on its icons.
     * Empty when 알림 접근 is off. Ongoing ones (music, downloads) and group summaries are not counted.
     */
    static java.util.Map<String, Integer> badges() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        NoticeListener l = live; if (l == null) return out;
        try {
            StatusBarNotification[] all = l.getActiveNotifications();
            for (StatusBarNotification n : all == null ? new StatusBarNotification[0] : all) {
                Notification note = n.getNotification();
                if (note == null || n.isOngoing() || (note.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (note.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) continue;
                out.merge(n.getPackageName(), Math.max(1, Math.min(99, note.number)), Integer::sum);
            }
        } catch (RuntimeException unavailable) { out.clear(); }
        return out;
    }

    // ── food on its way (core/Deliveries; 준: "배민에서 배달한걸 못알아채네") ──
    // 배민's tracker is an ongoing live notification (the status bar chip "조리완료"), left out of the notices below: read here
    // whatever kind it is, the newest kept so 퍼레 still knows after it is gone (배달 완료).
    private static volatile Deliveries.Delivery lastDelivery;
    /** Told when the food's stage changes, while the home screen is shown (it draws its board again at once). */
    static volatile Runnable deliveryChanged;
    private static final String DELIVERY_PREFS = "gyeot_delivery";
    /** A delivery app's notification as words: every short text in it (Samsung's live notifications keep theirs under their own keys), and its chip. */
    static String[] deliveryWords(StatusBarNotification posted) {
        Notification n = posted.getNotification(); if (n == null) return null;
        Bundle extras = n.extras; StringBuilder all = new StringBuilder(); String chip = "";
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (extras != null) for (String key : extras.keySet()) {
            Object v; try { v = extras.get(key); } catch (RuntimeException unreadable) { continue; }
            java.util.List<CharSequence> values = new java.util.ArrayList<>();
            if (v instanceof CharSequence cs) values.add(cs); else if (v instanceof CharSequence[] many) values.addAll(java.util.Arrays.asList(many));
            for (CharSequence cs : values) {
                if (cs == null) continue; String t = cs.toString().strip();
                if (t.isEmpty() || t.length() > 300 || t.matches("[\\w.$]+\\.[\\w.$]+") || !seen.add(t)) continue;
                String k = key.toLowerCase(java.util.Locale.ROOT);
                if (chip.isEmpty() && (k.contains("chip") || k.contains("shortcritical"))) chip = t;
                all.append(t).append(' ');
            }
        }
        if (n.tickerText != null && seen.add(n.tickerText.toString().strip())) all.append(n.tickerText).append(' ');
        return new String[]{all.toString().strip(), chip};
    }
    static Deliveries.Delivery deliveryOf(Context c, StatusBarNotification posted) {
        String pkg = posted.getPackageName(), label = pkg;
        try { PackageManager pm = c.getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
        if (!Deliveries.app(pkg, label)) return null;
        String[] words = deliveryWords(posted); if (words == null) return null;
        return Deliveries.read(pkg, label, "", words[0], words[1], posted.getPostTime(), java.time.ZoneId.systemDefault());
    }
    /** The food on its way now, or null: the delivery apps' notifications showing now, else the last one read (3 hours at most). */
    static Deliveries.Delivery delivery(Context c) {
        long now = System.currentTimeMillis();
        Deliveries.Delivery best = lastDelivery;
        if (best == null) try {
            String json = c.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE).getString("last", "");
            if (!json.isEmpty()) { org.json.JSONObject o = new org.json.JSONObject(json); best = lastDelivery = Deliveries.of(o); }
        } catch (Exception broken) { best = null; }
        NoticeListener l = live;
        if (l != null) try {
            StatusBarNotification[] all = l.getActiveNotifications();
            for (StatusBarNotification n : all == null ? new StatusBarNotification[0] : all) {
                if (n == null || c.getPackageName().equals(n.getPackageName())) continue;
                best = Deliveries.newer(best, deliveryOf(c, n));
            }
        } catch (RuntimeException unavailable) { /* the last one read */ }
        return Deliveries.current(best, now);
    }
    private void readDelivery(StatusBarNotification posted) {
        if (posted == null || getPackageName().equals(posted.getPackageName()) || !on(this)) return;
        String pkg = posted.getPackageName(), label = pkg;
        try { PackageManager pm = getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
        if (!Deliveries.app(pkg, label)) return;
        Deliveries.Delivery d = deliveryOf(this, posted);
        AppStore store = ((GyeotApp) getApplicationContext()).store();
        if (d == null) {
            // What the app said that was not read as an order (for 오류 진단 보고서, to teach the reader new words).
            String[] words = deliveryWords(posted); String w = words == null ? "" : words[0];
            store.phoneLog("● 배달 앱 알림(단계 못 읽음): " + label + " · " + (w.length() > 80 ? w.substring(0, 80) + "…" : w));
            return;
        }
        Deliveries.Delivery before = lastDelivery;
        lastDelivery = Deliveries.newer(before, d);
        getSharedPreferences(DELIVERY_PREFS, MODE_PRIVATE).edit().putString("last", lastDelivery.json().toString()).apply();
        if (before == null || before.stage() != d.stage()) {
            store.phoneLog("● 배달: " + Deliveries.words(d, d.at(), java.time.ZoneId.systemDefault()));
            Runnable r = deliveryChanged; if (r != null) try { r.run(); } catch (RuntimeException ignored) {}
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        try { readDelivery(posted); } catch (RuntimeException unreadable) { /* not an order after all */ }
        tell();
        try {
            if (posted == null || !on(this) || getPackageName().equals(posted.getPackageName())) return;
            Notification n = posted.getNotification(); if (n == null) return;
            if (posted.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
            if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_PROGRESS.equals(n.category) || Notification.CATEGORY_SERVICE.equals(n.category)) return;
            Bundle extras = n.extras;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE), big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT), text = extras.getCharSequence(Notification.EXTRA_TEXT);
            String label = posted.getPackageName();
            try { PackageManager pm = getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(posted.getPackageName(), 0)).toString(); } catch (Exception ignored) {}
            Notices.Notice notice = new Notices.Notice(posted.getPackageName(), label, title == null ? "" : title.toString().strip(),
                (big != null ? big : text == null ? "" : text).toString().strip(), System.currentTimeMillis());
            if (!Notices.keep(notice)) return;
            String json;
            synchronized (LOCK) { list(this); kept = Notices.add(kept, notice, notice.at()); json = Notices.save(kept); }
            File f = file(this), next = new File(f.getPath() + ".new");
            Files.write(next.toPath(), json.getBytes(StandardCharsets.UTF_8)); if (!next.renameTo(f)) { f.delete(); next.renameTo(f); }
        } catch (Exception quiet) { /* A notification that cannot be read is left out. */ }
    }
}
