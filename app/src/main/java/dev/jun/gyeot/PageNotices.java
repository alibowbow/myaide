package dev.jun.gyeot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.PageBridge;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 미니앱 알림 (gyeot.notify): a page's notification on the app's own channel (페이지 알림), now or at its time, a tap opening that
 * page. The ones set for later are kept here (at most PageBridge.SCHEDULED per page) and their alarm set: a private receiver
 * with no intent filter, so only this app's alarm reaches it. Alarms do not survive a restart: they are set again when the
 * app starts (rearm). A page's grant taken back, or the page removed, cancels the ones still waiting (cancel). Off the main
 * thread (the list is a small preferences file).
 */
public final class PageNotices extends BroadcastReceiver {
    static final String CHANNEL = "pages", ACTION = "dev.jun.gyeot.PAGE_NOTICE", PREFS = "page-notices", ID = "id";
    private static final int FIRST = 4400, SLOTS = 64;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String id = intent.getStringExtra(ID); if (id == null) return;
        JSONObject n = take(context, id);
        if (n != null) post(context, n.optString("page"), n.optString("pageTitle"), n.optString("title"), n.optString("text"));
    }

    static boolean allowed(Context c) { NotificationManager nm = c.getSystemService(NotificationManager.class); return nm != null && nm.areNotificationsEnabled(); }
    private static NotificationManager channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return null;
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "페이지 알림", NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }
    /** What a tap opens: the project ("project:<id>"), or the home screen's page ("home:<src>") by its name. */
    static Intent opening(Context c, String page, String pageTitle) {
        if (page != null && page.startsWith("project:"))
            return new Intent(c, ProjectActivity.class).putExtra(ProjectActivity.ID, page.substring("project:".length())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new Intent(c, HomeActivity.class).putExtra(HomeActivity.PAGE, pageTitle == null ? "" : pageTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }
    /** The notification now; its id, or -1 when notifications are off. The page's name under its title. */
    static int post(Context c, String page, String pageTitle, String title, String text) {
        NotificationManager nm = channel(c); if (nm == null) return -1;
        int id = FIRST + (int) (Math.floorMod((page + "|" + title + "|" + System.nanoTime()).hashCode(), SLOTS));
        PendingIntent open = PendingIntent.getActivity(c, id, opening(c, page, pageTitle), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setSubText(pageTitle).setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true);
        if (text != null && !text.isBlank()) b.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text));
        try { nm.notify(id, b.build()); } catch (SecurityException off) { return -1; }
        return id;
    }

    // ── the ones set for later ──
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static JSONArray list(Context c) { try { return new JSONArray(prefs(c).getString("list", "[]")); } catch (Exception unreadable) { return new JSONArray(); } }
    private static void keep(Context c, JSONArray list) { prefs(c).edit().putString("list", list.toString()).commit(); }
    /** How many of the page's wait for their time. */
    static synchronized int pending(Context c, String page) {
        JSONArray all = list(c); int n = 0;
        for (int i = 0; i < all.length(); i++) if (page.equals(all.optJSONObject(i) == null ? null : all.optJSONObject(i).optString("page"))) n++;
        return n;
    }
    /** Sets one for its time; throws PageBridge.Refused (LIMIT) past the page's SCHEDULED. Returns its id. */
    static synchronized String schedule(Context c, String page, String pageTitle, String title, String text, long at) {
        if (pending(c, page) >= PageBridge.SCHEDULED) throw new PageBridge.Refused(PageBridge.LIMIT, "예약해 둔 알림이 " + PageBridge.SCHEDULED + "개예요. 지난 알림이 울린 뒤에 다시 예약해 주세요.");
        String id = java.util.UUID.randomUUID().toString().substring(0, 12);
        JSONArray all = list(c);
        all.put(dev.jun.gyeot.core.Jsons.obj("id", id, "page", page, "pageTitle", pageTitle, "title", title, "text", text, "at", at));
        keep(c, all);
        arm(c, id, at);
        return id;
    }
    private static synchronized JSONObject take(Context c, String id) {
        JSONArray all = list(c), rest = new JSONArray(); JSONObject found = null;
        for (int i = 0; i < all.length(); i++) { JSONObject n = all.optJSONObject(i); if (n == null) continue; if (id.equals(n.optString("id"))) found = n; else rest.put(n); }
        if (found != null) keep(c, rest);
        return found;
    }
    /** Cancels the page's notifications still waiting (its 알림 taken back, or the page removed). Returns how many. */
    static synchronized int cancel(Context c, String page) {
        JSONArray all = list(c), rest = new JSONArray(); int gone = 0;
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        for (int i = 0; i < all.length(); i++) {
            JSONObject n = all.optJSONObject(i); if (n == null) continue;
            if (page.equals(n.optString("page"))) { gone++; if (alarms != null) alarms.cancel(fire(c, n.optString("id"))); }
            else rest.put(n);
        }
        if (gone > 0) keep(c, rest);
        return gone;
    }
    /** One of the page's own (gyeot.notify.cancel): false when it has none by that id (already shown, or another page's). */
    static synchronized boolean cancelOne(Context c, String page, String id) {
        JSONArray all = list(c), rest = new JSONArray(); boolean gone = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject n = all.optJSONObject(i); if (n == null) continue;
            if (page.equals(n.optString("page")) && id.equals(n.optString("id"))) gone = true; else rest.put(n);
        }
        if (!gone) return false;
        keep(c, rest);
        AlarmManager alarms = c.getSystemService(AlarmManager.class); if (alarms != null) alarms.cancel(fire(c, id));
        return true;
    }
    /** After a restart (alarms are lost): each one set again, one already past shown now. */
    static synchronized void rearm(Context c) {
        JSONArray all = list(c); long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject n = all.optJSONObject(i); if (n == null) continue;
            arm(c, n.optString("id"), Math.max(now + 5_000, n.optLong("at")));
        }
    }
    private static PendingIntent fire(Context c, String id) {
        Intent fire = new Intent(c, PageNotices.class).setAction(ACTION).putExtra(ID, id).setData(android.net.Uri.parse("gyeot-page-notice:" + id));
        return PendingIntent.getBroadcast(c, FIRST, fire, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void arm(Context c, String id, long at) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class); if (alarms == null) return;
        PendingIntent alarm = fire(c, id);
        try {
            if (WorkScheduleAlarm.exact(c)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm);
        } catch (SecurityException revoked) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm); }
    }
}
