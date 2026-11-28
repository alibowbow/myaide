package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import dev.jun.gyeot.core.AutomationGate;
import dev.jun.gyeot.core.Triggers;
import org.json.JSONObject;

/**
 * A trigger that fired, done: always a notification in plain words ("차에 탔네 · 네이버 지도 열었어"), and one passing line
 * from 퍼레 when the home screen is in front (준: "흘러가듯이 슬쩍": a few seconds, HomeActivity.passing). What it does goes
 * the way 퍼레 does it when asked (BuddyActs: an app, a route, music; BuddyHands: a job, behind the same AutomationGate),
 * started only where Android allows from the background (폰 조작, the app in front); otherwise, and for every draft, the
 * notification's tap does it (TriggerActivity): 퍼레 never sends a message by itself.
 */
final class TriggerFire {
    private TriggerFire() {}
    static final String CHANNEL = "triggers";
    /** Notification ids 4200–4263 (one per trigger, so an every-time one replaces its last), and 4290 for what to allow. */
    static final int FIRST = 4200, SLOTS = 64, ASK = 4290;

    /** How an act is done (tests put their own): BuddyActs, as 퍼레 does it when asked. */
    interface Doer { BuddyVoice.Outcome act(Context c, JSONObject act, String heard); }
    static Doer doer = (c, act, heard) -> BuddyActs.run(c, act, heard, false);
    /** Whether Android lets this app start a screen right now (DeviceActions.route), as DeviceActionRunner.start would. */
    static boolean canStart(Context c) {
        GyeotApp app = c.getApplicationContext() instanceof GyeotApp own ? own : null;
        KeyguardManager keyguard = c.getSystemService(KeyguardManager.class);
        String route = dev.jun.gyeot.core.DeviceActions.route(keyguard != null && keyguard.isKeyguardLocked(), app != null && (app.store().appVisible || app.activityVisible()),
            PhoneService.connected(), Settings.canDrawOverlays(c), false, Build.VERSION.SDK_INT);
        return DeviceActionRunner.started(route);
    }

    static void fire(Context c, Triggers.Fired f) {
        Triggers.Trigger t = f.trigger(); Triggers.Action a = t.then();
        String what = Triggers.happened(t, f.event()), line; Intent tap; String go = "";
        switch (a.kind()) {
            case Triggers.OPEN_APP, Triggers.NAVIGATE, Triggers.PLAY -> {
                BuddyVoice.Outcome o = canStart(c) ? doer.act(c, a.act(), t.said()) : null;
                boolean done = o != null && !o.missed();
                if (done) { line = what + " · " + Triggers.did(a, true); tap = list(c); }
                else { String id = TriggerStore.pend(c, a.act(), t.said()); line = what + " · " + Triggers.did(a, false); tap = TriggerActivity.fire(c, id); go = "trig:" + id; }
            }
            case Triggers.DRAFT -> {
                // Written ready, never sent: the tap opens it, the user sends.
                String id = TriggerStore.pend(c, a.act(), t.said());
                line = what + " · " + Triggers.did(a, false); tap = TriggerActivity.fire(c, id); go = "trig:" + id;
            }
            case Triggers.JOB -> {
                // The same path and guards as 퍼레's job tools (BuddyHands: AutomationGate, no payment, transfer or bank).
                // Nobody asked just now: a macro it runs asks before it sends (MacroRunner).
                JSONObject job = a.act(); try { job.put("unattended", true); } catch (org.json.JSONException never) { /* a plain key */ }
                BuddyHands.Started s = AutomationGate.requestsForbidden(a.target()) ? null : BuddyHands.start(c, job, t.said());
                boolean done = s != null && s.ok();
                line = what + " · " + Triggers.did(a, done) + (done || s == null ? "" : " (" + s.say() + ")");
                if (s == null) line = what + " · " + AutomationGate.FORBIDDEN_MESSAGE;
                Intent result = done && s.job() != null ? BuddyHands.opening(c, s.job()) : s != null && !s.fix().isEmpty() ? BuddyMisses.fixIntent(c, s.fix()) : null;
                tap = result != null ? result : list(c);
                if (done && s.job() != null) go = "job:" + s.job().id;
            }
            default -> { String r = Triggers.did(a, true); line = r.isEmpty() ? what : what + " · " + r; tap = eventTap(c, f.event()); }
        }
        TriggerWatch.log(c, "● 조건 알림: " + Triggers.title(t) + " · " + line);
        notify(c, t, line, tap);
        HomeActivity.passing(line, go);
    }
    /** What a plain notice's tap opens: the app the notice came from (its own screen), else 조건 알림's list. */
    private static Intent eventTap(Context c, Triggers.Event e) {
        if (e != null && Triggers.NOTICE.equals(e.kind()) && !e.app().isEmpty()) {
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(e.app());
            if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return list(c);
    }
    /** 작업창's 예약 작업 sheet, where 조건 알림 are listed. */
    static Intent list(Context c) {
        return new Intent(c, MainActivity.class).putExtra("tab", 3).putExtra(MainActivity.SCHEDULES, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    private static NotificationManager channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return null;
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "조건 알림", NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }
    static int id(Triggers.Trigger t) { return FIRST + (t.id().hashCode() & 0x7fffffff) % SLOTS; }
    private static void notify(Context c, Triggers.Trigger t, String line, Intent tap) {
        NotificationManager nm = channel(c); if (nm == null) return;
        int id = id(t);
        PendingIntent open = PendingIntent.getActivity(c, id, tap == null ? list(c) : tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Triggers.when(t.when())).setContentText(line).setStyle(new Notification.BigTextStyle().bigText(line))
            .setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true).build();
        try { nm.notify(id, n); } catch (SecurityException off) { /* notifications are off */ }
    }
    /** What the user needs to allow for a trigger to be heard, one tap away (BuddyMisses.fixIntent), as a notification. */
    static void ask(Context c, String fix, String why) {
        NotificationManager nm = channel(c); if (nm == null) return;
        Intent tap = BuddyMisses.fixIntent(c, fix); if (tap == null) return;
        PendingIntent open = PendingIntent.getActivity(c, ASK, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("조건 알림 · " + BuddyMisses.fixLabel(fix))
            .setContentText(why).setStyle(new Notification.BigTextStyle().bigText(why)).setContentIntent(open).setAutoCancel(true).build();
        try { nm.notify(ASK, n); } catch (SecurityException off) { /* notifications are off */ }
    }
}
