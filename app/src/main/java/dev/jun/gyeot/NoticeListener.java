package dev.jun.gyeot;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
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

    @Override public void onNotificationPosted(StatusBarNotification posted) {
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
