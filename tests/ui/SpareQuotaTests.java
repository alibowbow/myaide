package dev.jun.gyeot;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jun.gyeot.core.SpareQuota;
import java.io.File;
import java.io.FileOutputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 남는 한도 (core/SpareQuota) in the app: 퍼레's talk leaves a full Qwen for the Groq model of its tier with the most room
 * before NVIDIA, and stays on Qwen while it has room (the user's choice); 잡일 spreads off GPT OSS 120B and keeps 퍼레's Qwen;
 * spare work's job waits for the charger, an unmetered network and the screen off, and is gone without Groq; and 설정 → 사용량's
 * 오늘 남은 양 is a few compact rows in one group, with 남는 한도로 한 일.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SpareQuotaTests {
    static final String QWEN = "qwen/qwen3.8-27b", OSS = "openai/gpt-oss-120b", OSS20 = "openai/gpt-oss-20b";
    private GyeotApp app;
    private BuddyTalk.Send was;
    private final List<String[]> asked = new ArrayList<>();

    @Before public void setUp() {
        app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        // Groq connected; the key itself is never read (the brains are played).
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.GROQ, "iv:sealed").commit();
        app.store().settings.edit().putString("groqModels", "[\"" + OSS + "\",\"" + QWEN + "\",\"" + OSS20 + "\",\"groq/compound\",\"whisper-large-v3\"]").commit();
        BuddyTalk.brain(app, BuddyTalk.QWEN);
        // The AI picked for 퍼레 is one not connected here (Claude): it cannot stand in.
        app.store().settings.edit().putString("buddyModels", "{\"engine\":\"claude\",\"models\":{},\"efforts\":{}}").commit();
        was = BuddyTalk.send;
        BuddyTalk.send = (store, who, model, rules, input, reasoning, timeoutMs, cancel, onDelta, guard) -> { asked.add(new String[]{who, model}); return "응, 좋아!"; };
    }
    @After public void tearDown() { BuddyTalk.send = was; BuddyTalk.CHAT_BRAIN.remove(); }
    private String chat(String question) throws Exception { return BuddyTalk.chatText(app, question, "[]", "", new AtomicReference<>(), () -> true, null); }

    /** Qwen's day full and NVIDIA connected: before, NVIDIA answered; now GPT OSS 120B (Groq, same tier), and chatBy says why. */
    @Test public void talkMovesOffAFullQwenBeforeNvidia() throws Exception {
        assertEquals(QWEN, BuddyTalk.qwen(app.store()));
        chat("나 점심 맛있게 먹었어");
        assertArrayEquals("Qwen with room: the brain the user chose", new String[]{BuddyTalk.QWEN, QWEN}, asked.get(0));
        assertEquals("Groq · " + QWEN, BuddyTalk.chatBy);
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.NIM, "iv:sealed").commit();
        GeminiQuotas.used(QWEN, 200_001);
        try {
            asked.clear();
            chat("나 점심 맛있게 먹었어");
            assertArrayEquals("the Groq model of its tier with the most room, not NVIDIA", new String[]{BuddyTalk.QWEN, OSS}, asked.get(0));
            assertTrue(BuddyTalk.chatBy, BuddyTalk.chatBy.startsWith("Groq · " + OSS) && BuddyTalk.chatBy.contains("한도라서"));
            // GPT OSS 120B full too: NVIDIA stands in, as before (a MID one only when NVIDIA is not there).
            GeminiQuotas.used(OSS, 200_001); asked.clear();
            chat("나 점심 맛있게 먹었어");
            assertEquals(BuddyTalk.NIM, asked.get(0)[0]);
            app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().remove(SecretStore.NIM).commit(); asked.clear();
            chat("나 점심 맛있게 먹었어");
            assertArrayEquals("no NVIDIA and no other AI to stand in: GPT OSS 20B rather than no answer", new String[]{BuddyTalk.QWEN, OSS20}, asked.get(0));
            assertFalse(BuddyTalk.costsNothing(app.store()));
        } finally { app.store().changeGeminiQuota(state -> { state.remove(QWEN); state.remove(OSS); return null; }, false); }
    }

    /** 잡일: GPT OSS 120B first; half its day behind a fresh Qwen, the Qwen; 퍼레's Qwen kept once it is low; then the MID one. */
    @Test public void choresSpreadAndKeepQwenForBuddy() {
        assertEquals(OSS, SpareQuotas.light(2_500));
        spend(OSS, 110_000);
        assertTrue("its minute is free again", GroqWork.ready(OSS, 2_500));
        assertEquals("well behind: the roomier of its tier", QWEN, SpareQuotas.light(2_500));
        spend(QWEN, 150_000);
        assertEquals("퍼레's Qwen at a quarter: kept for 퍼레", OSS, SpareQuotas.light(2_500));
        assertEquals("one hand-over within Groq leaves the refused one out (and 퍼레's Qwen)", OSS20, SpareQuotas.lightBut(2_500, OSS));
        spend(OSS, 89_000);
        assertEquals("no HIGH room: GPT OSS 20B, before Gemini's free tier", OSS20, SpareQuotas.light(2_500));
        assertFalse("never search or speech", List.of("groq/compound", "whisper-large-v3").contains(SpareQuotas.light(1_000)));
    }
    /** tokens answered by model a few minutes ago (today), so its minute is free again. */
    private void spend(String model, int tokens) {
        long at = System.currentTimeMillis() - 3 * 60_000L;
        app.store().changeGeminiQuota(state -> { dev.jun.gyeot.core.GeminiQuota.used(state, model, at, tokens); return null; }, false);
    }

    /** The job: charger, unmetered network, the phone left alone, at the window; none without Groq. */
    @Test public void spareJobWaitsForChargerWifiAndScreenOff() {
        AppStore store = app.store();
        long before = System.currentTimeMillis();
        SpareWork.schedule(app, store);
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        JobInfo job = jobs.getPendingJob(SpareWork.JOB);
        assertNotNull("set", job);
        assertTrue("charging", job.isRequireCharging());
        assertTrue("the phone left alone (screen off)", job.isRequireDeviceIdle());
        assertEquals("Wi-Fi or another unmetered network", JobInfo.NETWORK_TYPE_UNMETERED, job.getNetworkType());
        long start = SpareQuota.windowStart(before);
        assertTrue("waits for the window: " + job.getMinLatencyMillis(), Math.abs(job.getMinLatencyMillis() - Math.max(0, start - before)) < 10_000);
        // Outside the window, or not charging here: nothing is sent and nothing is logged.
        SpareWork.Outcome ended = SpareWork.run(app, store, () -> false);
        assertNotEquals(SpareWork.Outcome.THROUGH, ended);
        assertEquals(0, SpareWork.log(store).length());
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().remove(SecretStore.GROQ).commit();
        SpareWork.schedule(app, store);
        assertNull("no Groq: no job", jobs.getPendingJob(SpareWork.JOB));
    }

    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void all(View view, Predicate<View> match, Consumer<View> each) {
        if (match.test(view)) each.accept(view);
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), match, each);
    }
    private static TextView text(View root, String start) { return (TextView) find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(start)); }
    private static void settle(Activity a, View content) {
        View decor = a.getWindow().getDecorView(); var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
            content.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void shot(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    /** 설정 → 사용량 · 오늘 남은 양: a row per free tier or subscription and 남는 한도로 한 일, in one group, each a list row's height. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void todaysRoomInCompactRows() throws Exception {
        AppStore store = app.store();
        long now = System.currentTimeMillis(); ZoneId seoul = ZoneId.of("Asia/Seoul");
        spend(OSS, 50_000);
        app.store().changeGeminiQuota(state -> { dev.jun.gyeot.core.GeminiQuota.groqDay(state, QWEN, now, "on tokens per day (TPD): Limit 200000. Please try again in 2h."); return null; }, false);
        List<UsageUi.Left> left = new ArrayList<>(UsageUi.left(store, now, seoul));
        assertEquals("Groq's chat models together (not search or speech)", "Groq 무료 · 모델 3개", left.get(0).name());
        assertTrue(left.get(0).line(), left.get(0).line().startsWith("토큰 350k / 600k 남음 · 1개 한도 참 · "));
        assertTrue(left.get(0).line(), left.get(0).line().endsWith(" 초기화"));
        assertFalse("some room left", left.get(0).low());
        left.add(new UsageUi.Left("NVIDIA NIM 무료", "하루 한도 없이 분당 한도만 있어요", false));
        left.add(new UsageUi.Left("ChatGPT 구독", "5시간 · 남은 6% · 14:30 초기화", true));
        left.add(new UsageUi.Left("Grok 구독", "주간 · 남은 72% · 12.2 09:00 초기화", false));
        UsageUi.Page page = new UsageUi.Page(List.of(), new JSONArray(), List.of(List.of(), List.of(), List.of()), false, false, left, "대화 제목 3개 · 기억 정리할 것 2가지", 11_500, true);
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        LinearLayout box = ui.column(); box.setPadding(0, 0, 0, ui.dp(24));
        new UsageUi(ui, store).spare(box, page, java.time.Instant.parse("2026-11-28T03:00:00Z").toEpochMilli(), seoul);
        ScrollView scroll = new ScrollView(a); scroll.addView(box); a.setContentView(scroll);
        settle(a, box);
        assertNotNull("its heading", text(box, "오늘 남은 양"));
        List<View> rows = new ArrayList<>();
        all(box, x -> x instanceof Ui.ListRow, rows::add);
        assertEquals("a row each, and 남는 한도로 한 일", left.size() + 1, rows.size());
        ViewGroup group = (ViewGroup) rows.get(0).getParent();
        for (View row : rows) {
            assertSame("one group, no cards", group, row.getParent());
            assertTrue("one line under its name, a list row's height: " + row.getHeight() / ui.dp(1) + "dp", row.getHeight() <= ui.dp(70));
            assertEquals("the group's width", group.getWidth(), row.getWidth());
        }
        assertNull("no buttons", find(box, x -> x instanceof Button));
        TextView done = text(box, "남는 한도로 한 일"); assertNotNull(done);
        assertNotNull("what it did today", text(box, "오늘 대화 제목 3개 · 기억 정리할 것 2가지"));
        assertNotNull("and its tokens, at the row's end", text(box, "토큰 11.5k"));
        TextView low = text(box, "5시간 · 남은 6%"); assertNotNull(low);
        assertEquals("little left: the warning color", Ui.WARNING, low.getCurrentTextColor());
        assertNotNull("when it runs, in the phone's time: 2–5 pm in Seoul", text(box, "다시 채워지기 전 오후 2시~5시,"));
        assertTrue("the whole section stays short: " + box.getHeight(), box.getHeight() <= ui.dp(70) * rows.size() + ui.dp(200));
        shot(scroll.getChildAt(0), "usage_spare_rows");
    }

    /** Without Groq, 남는 한도로 한 일 is not offered; with nothing connected the section is not drawn. */
    @Test public void nothingConnectedDrawsNothing() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a); LinearLayout box = ui.column();
        new UsageUi(ui, app.store()).spare(box, new UsageUi.Page(List.of(), new JSONArray()), System.currentTimeMillis(), ZoneId.of("Asia/Seoul"));
        assertEquals(0, box.getChildCount());
    }
}
