package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.provider.CalendarContract;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import dev.jun.gyeot.core.NoticeActions;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 알림을 행동으로 (준's plan, stage 1): a notice read into one tap. The calendar's new event filled in as the calendar_add tool
 * fills it; a messenger's notice with a reply action answered only when 보내기 is tapped, with the words the user left, through
 * that action's RemoteInput; a notice without one offers the chat instead; and the sheet kept compact.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NoticeActionsTests {
    private static final String REPLY = "dev.jun.gyeot.test.REPLY";
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void all(View view, Predicate<View> match, List<View> out) {
        if (match.test(view)) out.add(view);
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), match, out);
    }
    private static View labelled(View root, String words) { return find(root, v -> v instanceof TextView t && t.getText().toString().equals(words) && v.getVisibility() == View.VISIBLE); }
    private static void settle(View d) {
        var m = d.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            d.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
            d.layout(0, 0, d.getMeasuredWidth(), d.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void shot(View v, String name) throws Exception {
        settle(v);
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(0xff8a8f99); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private static float dp(View v) { return v.getResources().getDisplayMetrics().density; }
    private static Application app() { return RuntimeEnvironment.getApplication(); }
    private static NoticeActions.Act plan(String text) {
        return NoticeActions.read("com.kakao.talk", "카카오톡", "민수", text, System.currentTimeMillis(), java.time.ZoneId.systemDefault());
    }
    /** A messenger's reply action as KakaoTalk posts it: a free-form RemoteInput into a broadcast the app receives. */
    private static Notification.Action replyAction() {
        PendingIntent sent = PendingIntent.getBroadcast(app(), 7, new Intent(REPLY).setPackage(app().getPackageName()), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(null, "답장", sent).addRemoteInput(new RemoteInput.Builder("reply_text").setLabel("답장").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
    }
    private static List<Intent> replies() {
        List<Intent> out = new ArrayList<>();
        for (Intent i : shadowOf(app()).getBroadcastIntents()) if (REPLY.equals(i.getAction())) out.add(i);
        return out;
    }
    @Before public void fresh() { NoticeListener.forget(); shadowOf(app()).clearBroadcastIntents(); }
    @After public void clean() { NoticeListener.forget(); }

    /** 일정에 넣기: the calendar's own new-event screen with the booking's name, times and place, as BuddyActs' calendar_add opens it. */
    @Test public void fillsTheCalendarsNewEvent() throws Exception {
        long now = System.currentTimeMillis(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        NoticeActions.Act ktx = NoticeActions.read("com.samsung.android.messaging", "메시지", "1544-7788", "[Web발신]\n[코레일] 승차권 발권 완료\n내일 KTX 023 서울(08:00)→부산(10:39)\n5호차 12A석", now, zone);
        assertNotNull("the train read", ktx);
        Intent add = NoticeActActivity.calendar(ktx);
        assertEquals(Intent.ACTION_INSERT, add.getAction());
        assertEquals(CalendarContract.Events.CONTENT_URI, add.getData());
        assertEquals("vnd.android.cursor.dir/event", add.getType());
        assertEquals("KTX 023 서울→부산", add.getStringExtra(CalendarContract.Events.TITLE));
        java.time.LocalDate tomorrow = java.time.LocalDate.now(zone).plusDays(1);
        assertEquals(tomorrow.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), add.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0));
        assertEquals(tomorrow.atTime(10, 39).atZone(zone).toInstant().toEpochMilli(), add.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0));
        assertFalse(add.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true));
        assertEquals("서울역", add.getStringExtra(CalendarContract.Events.EVENT_LOCATION));
        assertTrue(add.getStringExtra(CalendarContract.Events.DESCRIPTION).contains("코레일"));
        assertFalse("no phone numbers in the note", add.getStringExtra(CalendarContract.Events.DESCRIPTION).contains("1544"));
        // The calendar_add tool's own intent, the same way (PhoneCalendar.insert).
        Intent tool = PhoneCalendar.insert("치과", 1000L, 2000L, false, "강남역", "");
        assertEquals(Intent.ACTION_INSERT, tool.getAction()); assertEquals("강남역", tool.getStringExtra(CalendarContract.Events.EVENT_LOCATION)); assertNull(tool.getStringExtra(CalendarContract.Events.DESCRIPTION));

        // A tap on the pill (act:calendar:<key>) opens it, the act looked up among those kept.
        app().getSharedPreferences("gyeot_notice_acts", Context.MODE_PRIVATE).edit().putString("acts", NoticeActions.save(List.of(ktx))).commit();
        Intent tap = HtmlWallpaper.target(app(), "act:calendar:" + ktx.key());
        assertNotNull("the board's go becomes the act's screen", tap);
        assertNull("only act keys", HtmlWallpaper.target(app(), "act:calendar:../../x"));
        Activity a = Robolectric.buildActivity(NoticeActActivity.class, tap).setup().get();
        Intent started = null;
        for (int i = 0; i < 100 && started == null; i++) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle(); started = shadowOf(a).getNextStartedActivity(); }
        assertNotNull("the calendar opened", started);
        assertEquals(Intent.ACTION_INSERT, started.getAction());
        assertEquals("KTX 023 서울→부산", started.getStringExtra(CalendarContract.Events.TITLE));
        assertTrue("the see-through screen goes", a.isFinishing());
    }

    /** A messenger's notice as the listener reads it: the newest message, who wrote, and its reply action kept for 답장. */
    @Test public void readsAMessengersNoticeWithItsReply() {
        android.app.Person minsu = new android.app.Person.Builder().setName("민수").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(new android.app.Person.Builder().setName("나").build());
        style.addMessage("뭐해?", System.currentTimeMillis() - 60_000, minsu).addMessage("내일 7시 강남역에서 보자", System.currentTimeMillis(), minsu);
        Notification n = new Notification.Builder(app(), "chat").setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("민수").setContentText("내일 7시 강남역에서 보자")
            .setStyle(style).addAction(replyAction()).build();
        StatusBarNotification posted = new StatusBarNotification("com.kakao.talk", "com.kakao.talk", 3, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), System.currentTimeMillis());
        NoticeActions.Act a = NoticeListener.actOf(app(), posted);
        assertNotNull("a plan", a);
        assertEquals(NoticeActions.PLAN, a.kind()); assertEquals("민수", a.who()); assertEquals("강남역", a.place());
        assertEquals("the notice's own key, to answer it after a restart", posted.getKey(), a.nkey());
        assertTrue("its reply action kept", NoticeListener.canReply(app(), a));
        assertNotNull(NoticeReply.replyAction(n));
        // A notice with no free-form input (only buttons) cannot be answered here.
        Notification buttons = new Notification.Builder(app(), "chat").setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("민수")
            .addAction(new Notification.Action.Builder(null, "읽음", PendingIntent.getBroadcast(app(), 8, new Intent("x").setPackage(app().getPackageName()), PendingIntent.FLAG_IMMUTABLE)).build()).build();
        assertNull(NoticeReply.replyAction(buttons));
        assertTrue("nothing sent by reading", replies().isEmpty());
    }

    /** 답장: the draft can be changed and goes only on 보내기, as the user left it, through the notice's own reply action. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void sendsOnlyOnTheTapWithTheUsersWords() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        NoticeActions.Act minsu = plan("내일 7시 강남역에서 보자");
        assertNotNull(minsu);
        boolean[] closed = {false};
        Ui.Sheet sheet = NoticeReply.show(a, minsu, new NoticeListener.Live("com.kakao.talk", null, replyAction(), "0|com.kakao.talk|3|null|0"), () -> closed[0] = true);
        shadowOf(Looper.getMainLooper()).idle();
        View s = sheet.getWindow().getDecorView(); settle(s);
        assertTrue("open", sheet.isShowing());
        assertNotNull("to whom", labelled(s, "민수에게 답장"));
        EditText field = (EditText) find(s, v -> v instanceof EditText);
        assertNotNull("the draft's field", field);
        assertEquals("a draft ready", "좋아! 내일 7시에 강남역에서 봐", field.getText().toString());
        assertTrue("nothing sent by opening it", replies().isEmpty());
        // Compact: small pills at the line's end, no wide button.
        View send = labelled(s, "보내기"); assertNotNull("보내기", send);
        assertTrue("a small pill: " + send.getHeight() / dp(send) + "dp", send.getHeight() <= 40 * dp(send));
        List<View> wide = new ArrayList<>(); all(s, v -> (v instanceof Button || v.isClickable() && v instanceof TextView && !(v instanceof EditText)) && v.getVisibility() == View.VISIBLE && v.getWidth() > s.getWidth() * 0.6, wide);
        assertTrue("no full-width bar: " + wide, wide.isEmpty());
        assertNotNull("the chat one tap away", labelled(s, "채팅 열기"));
        shot(s, "notice_reply");
        // The user changes it; still nothing sent until the tap.
        field.setText("좋아, 7시에 거기서 봐!"); shadowOf(Looper.getMainLooper()).idle();
        assertTrue("nothing sent while writing", replies().isEmpty());
        assertTrue(send.performClick()); shadowOf(Looper.getMainLooper()).idle();
        List<Intent> sent = replies();
        assertEquals("one reply", 1, sent.size());
        Bundle results = RemoteInput.getResultsFromIntent(sent.get(0));
        assertNotNull("through RemoteInput", results);
        assertEquals("the words the user left", "좋아, 7시에 거기서 봐!", results.getCharSequence("reply_text").toString());
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(sent.get(0)));
        assertFalse("closed once sent", sheet.isShowing()); assertTrue(closed[0]);
    }

    /** A notice that cannot be answered here: it says so, keeps the draft to copy, and opens the chat; never a 보내기. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void withoutAReplyActionOffersTheChat() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui.Sheet sheet = NoticeReply.show(a, plan("내일 3시에 스타벅스 앞에서 봐"), null, null);
        shadowOf(Looper.getMainLooper()).idle();
        View s = sheet.getWindow().getDecorView(); settle(s);
        assertNull("no 보내기", labelled(s, "보내기"));
        assertNotNull(labelled(s, "채팅 열기")); assertNotNull(labelled(s, "복사"));
        assertNotNull("says why", find(s, v -> v instanceof TextView t && t.getText().toString().startsWith("이 알림에는 바로 답장하는 버튼이 없어요")));
        shot(s, "notice_reply_none");
        assertTrue(replies().isEmpty());
        Dialog latest = ShadowDialog.getLatestDialog(); assertSame(sheet, latest);
    }
}
