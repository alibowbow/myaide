package dev.jun.gyeot;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import dev.jun.gyeot.core.BuddyJobs;
import dev.jun.gyeot.core.Macros;
import dev.jun.gyeot.core.PhoneGate;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
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
import static dev.jun.gyeot.core.Jsons.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 매크로: the rows in 설정 → 한마디 루틴 stay compact (매크로 녹화, each macro with a small 실행), a macro's sheet renames,
 * runs and deletes it; a run does each recorded step as a phone_action with the element found on the screen now, stops
 * with the step, what was not found and why, stops before a payment button for the user, asks before a send when nobody
 * watches (and goes on after 할게요), never runs on a locked phone (a tap-to-run notification instead); 퍼레's words and its
 * phone_job reach the macro by name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MacroTests {
    static final String KAKAO_T = "com.kakao.taxi";
    static final int[] PHONE = {1080, 2340};
    private GyeotApp app;
    private MacroRunner.Phone was;
    private Fake phone;

    @Before public void fresh() {
        app = (GyeotApp) RuntimeEnvironment.getApplication();
        app.getSharedPreferences(MacroBook.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        MacroBook.forget(); BuddyJobs.clear();
        app.store().settings.edit().putBoolean("phoneControl", true).commit();
        was = MacroRunner.phone; phone = new Fake(); MacroRunner.phone = phone; MacroRunner.pace = 0.02;
        app.getSystemService(NotificationManager.class).cancelAll();
    }
    @After public void clean() { MacroRunner.phone = was; MacroRunner.pace = 1; BuddyJobs.clear(); MacroBook.forget(); }

    // ── the page ──
    @Test @Config(qualifiers = "w411dp-h891dp") public void theRoutinesPageListsMacrosCompactly() throws Exception {
        MacroBook.change(app, all -> Macros.save(all, parking()));
        Activity a = Robolectric.buildActivity(MainActivity.class, new Intent().putExtra(MainActivity.SETTINGS_PAGE, "routines")).setup().get();
        settle(a);
        View decor = a.getWindow().getDecorView();
        View record = text(decor, "매크로 녹화"); assertNotNull("its record row", record);
        View row = text(decor, "주차 정산"); assertNotNull("the macro's row", row);
        assertNotNull("its steps in a few words", text(decor, "3단계 · 카카오T"));
        float d = a.getResources().getDisplayMetrics().density;
        View rowView = clickable(row), recordRow = clickable(record);
        assertTrue("a list row, not a card: " + rowView.getHeight() / d, rowView.getHeight() <= Math.round(72 * d));
        assertEquals("its 실행 makes it no taller than a plain row", recordRow.getHeight(), rowView.getHeight());
        Button run = (Button) find(rowView, v -> v instanceof Button b && "실행".contentEquals(b.getText()));
        assertNotNull("a small 실행 in the row", run);
        assertEquals("the small chip's height", Math.round(36 * d), run.getHeight());
        assertTrue("narrow, as wide as its word", run.getWidth() < Math.round(90 * d));
        shot(a, "macros_page");
        // Its sheet: the name to change, the steps, 지금 실행 and 지우기.
        assertTrue(rowView.performClick()); shadowOf(Looper.getMainLooper()).idle();
        View sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView(); settle(sheet);
        assertNotNull("its steps", text(sheet, "1. ‘카카오T’ 열기"));
        assertNotNull(text(sheet, "지금 실행")); assertNotNull(text(sheet, "지우기"));
        EditText name = (EditText) find(sheet, v -> v instanceof EditText);
        name.setText("주차 정산하기");
        Button save = (Button) find(sheet, v -> v instanceof Button b && "저장".contentEquals(b.getText()));
        assertTrue(save.performClick()); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("renamed", "주차 정산하기", MacroBook.list(app).get(0).name());
    }
    @Test @Config(qualifiers = "w411dp-h891dp") public void aRecordingsEndOpensItsSheet() throws Exception {
        Macros.Macro m = parking();
        MacroBook.change(app, all -> Macros.save(all, m));
        Activity a = Robolectric.buildActivity(MainActivity.class, MacrosUi.opening(app, m.id())).setup().get();
        settle(a);
        assertNotNull("the sheet came up with the page", ShadowDialog.getLatestDialog());
        View sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView(); settle(sheet);
        assertNotNull(find(sheet, x -> x instanceof TextView t && t.getText().toString().contains("3. ‘차량번호’ 칸에 ‘12가3456’ 입력")));
    }

    // ── a run ──
    @Test public void aRunDoesEachStepOnTheControlFoundNow() throws Exception {
        Macros.Macro m = parking();
        MacroBook.change(app, all -> Macros.save(all, m));
        BuddyHands.Started s = MacroRunner.start(app, m, false, "주차 정산 해 줘", null);
        assertTrue(s.say(), s.ok()); assertEquals("‘주차 정산’ 할게!", s.say());
        BuddyJobs.Job job = await(s.job());
        assertEquals(job.result(), BuddyJobs.State.DONE, job.state());
        assertEquals("‘주차 정산’ 다 했어요 · 3단계", job.result());
        List<String> actions = actions();
        assertEquals(List.of("open_app", "tap", "set_text"), actions);
        assertEquals(KAKAO_T, phone.acts.get(0).optString("app"));
        assertEquals("the button found where it is now (it moved and its spacing changed)", "e1", phone.acts.get(1).optString("element"));
        assertEquals("e2", phone.acts.get(2).optString("element")); assertEquals("12가3456", phone.acts.get(2).optString("text"));
        assertFalse("each step's words are the user's ask, never a read", phone.goals.get(1).isEmpty());
        assertNotEquals("each step its own words", phone.goals.get(1), phone.goals.get(2));
        assertEquals(1, MacroBook.list(app).get(0).runs());
        assertTrue("its line was the job's", job.label.contains("주차 정산"));
    }
    @Test public void aRunThatCannotFindAControlSaysWhichStepWhatAndWhy() throws Exception {
        phone.field = false;
        Macros.Macro m = parking();
        BuddyHands.Started s = MacroRunner.start(app, m, false, "", null);
        BuddyJobs.Job job = await(s.job());
        assertEquals(BuddyJobs.State.FAILED, job.state());
        assertEquals("‘주차 정산’ 3단계: ‘차량번호’ 칸을 찾지 못했어요 · 화면이 바뀌었어요", job.result());
        assertEquals("nothing typed", List.of("open_app", "tap"), actions());
        assertNotNull("told in a notification too", notification(MacroRunner.RESULT));
        assertTrue(MacroBook.list(app).isEmpty() || !MacroBook.list(app).get(0).last().startsWith("✓"));
    }
    @Test public void aGoneControlIsMendedOnceByThePhoneAgentAndKept() throws Exception {
        Macros.Macro m = parking();
        MacroBook.change(app, all -> Macros.save(all, m));
        phone.renamed = true;
        // No phone AI: the run stops, saying what was not found.
        BuddyJobs.Job plain = await(MacroRunner.start(app, m, false, "", null).job());
        assertEquals(BuddyJobs.State.FAILED, plain.state());
        assertEquals("‘주차 정산’ 2단계: ‘주차 정산’ 버튼을 찾지 못했어요 · 화면이 바뀌었어요", plain.result());
        // With it: that one step is asked for (its words and clues), done, and the macro keeps the control it found.
        phone.acts.clear(); BuddyJobs.clear();
        phone.mend = new Macros.Target("pay_parking", "주차요금 내기", "", "", "android.widget.Button", "button", 1, new int[]{0, 1720, 1080, 1870}, PHONE, false, null);
        BuddyJobs.Job mended = await(MacroRunner.start(app, MacroBook.list(app).get(0), false, "", null).job());
        assertEquals(mended.result(), BuddyJobs.State.DONE, mended.state());
        assertEquals(1, phone.repaired.size());
        assertTrue(phone.repaired.get(0), phone.repaired.get(0).contains("2단계만") && phone.repaired.get(0).contains("‘주차 정산’ 누르기"));
        assertEquals("the app opened once more from its first screen, then the agent's one tap", List.of("open_app", "open_app", "tap", "set_text"), actions());
        assertTrue(phone.acts.get(1).optBoolean("fresh")); assertEquals("agent", phone.acts.get(2).optString("by"));
        assertEquals("kept for the next run", "pay_parking", MacroBook.list(app).get(0).steps().get(1).target().vid());
        // The next run finds it with no AI.
        phone.acts.clear(); phone.repaired.clear(); BuddyJobs.clear();
        BuddyJobs.Job next = await(MacroRunner.start(app, MacroBook.list(app).get(0), false, "", null).job());
        assertEquals(BuddyJobs.State.DONE, next.state());
        assertTrue("no AI this time", phone.repaired.isEmpty());
    }
    @Test public void aRunStopsBeforeAPaymentButtonForTheUser() throws Exception {
        Macros.Macro m = new Macros.Macro("", "배달 주문", List.of(Macros.Step.open(KAKAO_T, "카카오T", 0),
            Macros.Step.of(Macros.TAP, KAKAO_T, button("주차 정산", "parking", 0, 1500, 1080, 1650), 0),
            new Macros.Step(Macros.TAP, KAKAO_T, "", button("결제하기", "pay", 0, 2100, 1080, 2300), "", "", 0, false, true)), 0, 0, 0, "");
        BuddyJobs.Job job = await(MacroRunner.start(app, m, false, "", null).job());
        assertEquals(BuddyJobs.State.WAITING, job.state());
        assertEquals("‘배달 주문’ 3단계: ‘결제하기’는 직접 눌러 주세요 · 그 앞까지 했어요", job.result());
        assertEquals("never pressed", List.of("open_app", "tap"), actions());
    }
    @Test public void aRunNobodyWatchesAsksBeforeItSends() throws Exception {
        Macros.Macro m = new Macros.Macro("", "퇴근 문자", List.of(Macros.Step.open(KAKAO_T, "카카오T", 0),
            Macros.Step.of(Macros.TAP, KAKAO_T, button("전송", "send", 900, 2200, 1080, 2340), 0)), 0, 0, 0, "");
        phone.send = true;
        BuddyJobs.Job job = MacroRunner.start(app, m, true, "", null).job();
        StatusBarNotification ask = null;
        for (long until = System.currentTimeMillis() + 5000; ask == null && System.currentTimeMillis() < until; ) { ask = notification(MacroRunner.ASKING); if (ask == null) Thread.sleep(20); }
        assertNotNull("it asks first", ask);
        assertEquals("nothing sent yet", List.of("open_app"), actions());
        Notification.Action yes = ask.getNotification().actions[0];
        assertEquals("할게요", yes.title.toString());
        new MacroReceiver().onReceive(app, shadowOf(yes.actionIntent).getSavedIntent());
        await(job);
        assertEquals(job.result(), BuddyJobs.State.DONE, job.state());
        assertEquals(List.of("open_app", "tap"), actions());
        // Asked by the user just now: no question.
        phone.acts.clear(); BuddyJobs.clear();
        BuddyJobs.Job asked = await(MacroRunner.start(app, m, false, "퇴근 문자 해 줘", null).job());
        assertEquals(BuddyJobs.State.DONE, asked.state());
        assertNull(notification(MacroRunner.ASKING));
    }
    @Test public void aLockedPhoneRunsNothingAndLeavesATapToRunIt() {
        phone.locked = true;
        BuddyHands.Started s = MacroRunner.start(app, parking(), true, "", null);
        assertFalse(s.ok()); assertTrue(s.say(), s.say().contains("잠겨"));
        assertTrue("nothing done", phone.acts.isEmpty());
        StatusBarNotification later = notification(MacroRunner.LATER);
        assertNotNull("a notification whose tap runs it", later);
        Intent tap = shadowOf(later.getNotification().contentIntent).getSavedIntent();
        assertEquals(TriggerActivity.class.getName(), tap.getComponent().getClassName());
    }
    @Test public void phoneControlOffSaysWhereToTurnItOn() {
        app.store().settings.edit().putBoolean("phoneControl", false).commit();
        BuddyHands.Started s = MacroRunner.start(app, parking(), false, "", null);
        assertFalse(s.ok()); assertEquals("settings:phone", s.fix());
        assertNotNull(BuddyMisses.fixIntent(app, s.fix()));
    }

    // ── by words ──
    @Test public void 퍼레ReachesAMacroByItsName() throws Exception {
        Macros.Macro m = parking().named("출근 체크");
        MacroBook.change(app, all -> Macros.save(all, m));
        JSONObject local = MacroActs.local(app, "퍼레야 출근 체크 해 줘");
        assertNotNull("no brain needed", local);
        assertEquals("run", local.optString("command"));
        BuddyVoice.Outcome o = BuddyActs.run(app, local, "출근 체크 해 줘", false);
        assertEquals("‘출근 체크’ 할게!", o.say()); assertFalse(o.missed());
        await(BuddyJobs.latestRunning());
        assertEquals(List.of("open_app", "tap", "set_text"), actions());
        // The brain's phone_job with the name (and a 조건 알림's "… 매크로 실행", unattended) is the macro, not the AI phone agent.
        phone.acts.clear(); BuddyJobs.clear();
        BuddyHands.Started job = BuddyHands.start(app, new JSONObject().put("tool", "phone_job").put("request", "출근 체크 매크로 실행").put("unattended", true), "차 타면 출근 체크 매크로 실행해 줘");
        assertTrue(job.say(), job.ok());
        assertEquals("‘출근 체크’ 매크로 실행", job.job().request);
        await(job.job());
        assertEquals(List.of("open_app", "tap", "set_text"), actions());
        // The list, by words.
        assertTrue(MacroActs.run(app, new JSONObject().put("tool", "macro").put("command", "list"), "매크로 뭐 있어").say().contains("‘출근 체크’"));
        assertNull("other words go to the brain", MacroActs.local(app, "오늘 날씨 어때"));
        // A phone job just done is offered as a macro, and saved by words.
        MacroBook.offer(m.steps(), "배민 재주문", System.currentTimeMillis());
        JSONObject save = MacroActs.local(app, "매크로로 저장해 줘");
        assertEquals("save", save.optString("command"));
        assertTrue(BuddyActs.run(app, save, "매크로로 저장해 줘", false).say().contains("‘배민 재주문’"));
        assertNotNull(Macros.find(MacroBook.list(app), "배민 재주문"));
        // The voice assistant's run_macro: the names when none is given.
        JSONObject names = MacroActs.voice(app, new JSONObject());
        assertTrue(names.optBoolean("ok")); assertEquals(2, names.optJSONArray("macros").length());
    }

    // ── helpers ──
    static Macros.Target button(String text, String vid, int l, int t, int r, int b) { return new Macros.Target(vid, text, "", "", "android.widget.Button", "button", 0, new int[]{l, t, r, b}, PHONE, false, null); }
    static Macros.Macro parking() {
        return new Macros.Macro("", "주차 정산", List.of(Macros.Step.open(KAKAO_T, "카카오T", 0),
            Macros.Step.of(Macros.TAP, KAKAO_T, button("주차 정산", "parking", 0, 1500, 1080, 1650), 1200),
            new Macros.Step(Macros.TYPE, KAKAO_T, "", new Macros.Target("plate", "", "", "차량번호", "android.widget.EditText", "input", 0, new int[]{40, 600, 1040, 740}, PHONE, true, null), "12가3456", "", 800, false, false)),
            0, 0, 0, "");
    }
    /** The phone as a run sees it: the home screen until an app opens, then 카카오T with its parking button (moved, its
     * spacing changed), its plate field (unless field is false) and a send button (send). */
    static final class Fake implements MacroRunner.Phone {
        final List<JSONObject> acts = new CopyOnWriteArrayList<>(); final List<String> goals = new CopyOnWriteArrayList<>();
        volatile boolean locked, field = true, send, opened, renamed;
        /** What the phone agent finds for a gone control (null: no phone AI). */
        volatile Macros.Target mend;
        final List<String> repaired = new CopyOnWriteArrayList<>();
        final PhoneGate gate = new PhoneGate();
        @Override public boolean ready() { return true; }
        @Override public boolean locked(Context c) { return locked; }
        @Override public boolean arm() { if (gate.armed()) return false; gate.arm(); return true; }
        @Override public PhoneGate.Task task() { return gate.task(); }
        @Override public AutoCloseable watch(PhoneGate.Task control) { return () -> {}; }
        @Override public JSONObject read(PhoneGate.Task control) {
            control.check();
            if (!opened) return obj("ok", true, "snapshot_id", "s0", "app", "com.sec.android.app.launcher", "screen", arr(1080, 2340), "elements", arr());
            JSONArray elements = arr(obj("id", "e0", "role", "button", "text", "홈", "vid", "tab_home", "cls", "android.widget.Button", "flags", "click", "bounds", arr(0, 2200, 200, 2340)),
                renamed ? obj("id", "e1", "role", "button", "text", "주차요금 내기", "vid", "pay_parking", "cls", "android.widget.Button", "flags", "click", "bounds", arr(0, 1720, 1080, 1870))
                    : obj("id", "e1", "role", "button", "text", "주차정산", "vid", "parking", "cls", "android.widget.Button", "flags", "click", "bounds", arr(0, 1520, 1080, 1670)));
            if (field) elements.put(obj("id", "e2", "role", "input", "hint", "차량번호", "vid", "plate", "cls", "android.widget.EditText", "flags", "edit,click", "bounds", arr(40, 600, 1040, 740)));
            if (send) elements.put(obj("id", "e3", "role", "button", "text", "전송", "vid", "send", "cls", "android.widget.Button", "flags", "click", "bounds", arr(900, 2200, 1080, 2340)));
            return obj("ok", true, "snapshot_id", "s" + acts.size(), "app", KAKAO_T, "app_label", "카카오T", "screen", arr(1080, 2340), "elements", elements);
        }
        @Override public JSONObject act(PhoneGate.Task control, JSONObject args, String goal) {
            control.check();
            acts.add(args); goals.add(goal);
            if ("open_app".equals(args.optString("action"))) { opened = true; return obj("ok", true, "action", "open_app", "opened", args.optString("app")); }
            return obj("ok", true, "action", args.optString("action"), "action_executed", true);
        }
        @Override public void disarm() { gate.stop(); }
        @Override public MacroRunner.Repair repair(Context c, PhoneGate.Task control, Macros.Macro m, int index, Macros.Step step, boolean unattended) {
            if (mend == null) return null;
            repaired.add(Macros.repairTask(m, index, step));
            acts.add(obj("action", "tap", "element", "e1", "by", "agent")); goals.add(Macros.goal(m, index));
            return new MacroRunner.Repair(true, mend, "");
        }
    }
    private List<String> actions() { List<String> out = new java.util.ArrayList<>(); for (JSONObject a : phone.acts) out.add(a.optString("action")); return out; }
    private static BuddyJobs.Job await(BuddyJobs.Job job) throws Exception {
        assertNotNull("a job", job);
        for (long until = System.currentTimeMillis() + 10_000; job.running() && System.currentTimeMillis() < until; ) Thread.sleep(20);
        assertFalse("it ended", job.running());
        return job;
    }
    private StatusBarNotification notification(int id) {
        for (StatusBarNotification n : app.getSystemService(NotificationManager.class).getActiveNotifications()) if (n.getId() == id) return n;
        return null;
    }
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View text(View root, String start) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(start)); }
    private static View clickable(View v) { View row = v; while (row != null && !row.isClickable() && row.getParent() instanceof View p) row = p; return row; }
    private static void settle(Activity a) { settle(a.getWindow().getDecorView()); }
    private static void settle(View d) {
        var m = d.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            d.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
            d.layout(0, 0, d.getMeasuredWidth(), d.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void shot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView(); settle(a);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), Math.max(1, decor.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
