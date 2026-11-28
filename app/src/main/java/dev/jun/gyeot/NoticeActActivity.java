package dev.jun.gyeot;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import dev.jun.gyeot.core.NoticeActions;

/**
 * One tap of 알림을 행동으로 (a pill on the home screen, or 퍼레's bubble on the wallpaper): see-through, over whatever shows.
 * open: the notice's own screen (its tracking page), else its app. calendar: the calendar's new event filled in (the user
 * saves it). reply: the reply sheet (NoticeReply), sent only on 보내기. The act is looked up off the main thread.
 */
public final class NoticeActActivity extends Activity {
    static final String DO = "do", KEY = "key";
    private final Handler main = new Handler(Looper.getMainLooper());

    /** "act:open|calendar|reply:<key>" (the board's go) as the intent that does it; null for anything else. */
    static Intent of(Context c, String what) {
        String[] parts = what == null ? new String[0] : what.split(":", 3);
        if (parts.length != 3 || !parts[0].equals("act") || !parts[1].matches("open|calendar|reply") || !parts[2].matches("[a-z][0-9a-f]{1,12}")) return null;
        return new Intent(c, NoticeActActivity.class).putExtra(DO, parts[1]).putExtra(KEY, parts[2])
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String what = getIntent().getStringExtra(DO), key = getIntent().getStringExtra(KEY);
        Context app = getApplicationContext();
        new Thread(() -> {
            NoticeActions.Act a = NoticeListener.act(app, key == null ? "" : key);
            NoticeListener.Live live = NoticeListener.live(app, a);
            main.post(() -> { if (!isFinishing() && !isDestroyed()) go(what == null ? "" : what, a, live); });
        }, "gyeot-notice-act").start();
    }
    private void go(String what, NoticeActions.Act a, NoticeListener.Live live) {
        if (a == null) { Toast.makeText(this, "그 알림은 이제 지나갔어요", Toast.LENGTH_SHORT).show(); finish(); return; }
        switch (what) {
            case "calendar" -> {
                try { startActivity(calendar(a)); }
                catch (RuntimeException none) { Toast.makeText(this, "캘린더 앱을 못 찾았어요", Toast.LENGTH_SHORT).show(); }
                finish();
            }
            case "reply" -> NoticeReply.show(this, a, live, this::finish);
            default -> { if (!open(this, a, live)) Toast.makeText(this, "열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show(); finish(); }
        }
    }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    /** The calendar's new event for the act, filled in as the calendar_add tool fills it (PhoneCalendar.insert). */
    static Intent calendar(NoticeActions.Act a) {
        NoticeActions.Insert in = NoticeActions.insert(a, java.time.ZoneId.systemDefault());
        return PhoneCalendar.insert(in.title(), in.begin(), in.end(), in.allDay(), in.place(), in.note());
    }
    /**
     * The act's own screen: what its notice opens (the tracking page, the chat) while it is still around, else its app, else a
     * web address in its words. False when none opens.
     */
    static boolean open(Activity from, NoticeActions.Act a, NoticeListener.Live live) {
        if (live != null && live.open() != null) {
            try {
                Bundle options = null;
                // Android 14 lets a notice's own screen open from here only when the one sending it says so (it is in front).
                if (Build.VERSION.SDK_INT >= 34) options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                live.open().send(from, 0, null, null, null, null, options);
                return true;
            } catch (PendingIntent.CanceledException | RuntimeException gone) { /* its app instead */ }
        }
        String pkg = live != null ? live.pkg() : a.app();
        Intent launch = pkg == null || pkg.isEmpty() ? null : from.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) try { from.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (RuntimeException none) { /* the address */ }
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("https://[\\w.-]+(?:/[\\w./?=&%#-]*)?").matcher(a.text());
        if (link.find()) try { from.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link.group())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; } catch (RuntimeException none) { /* nothing */ }
        return false;
    }
}
