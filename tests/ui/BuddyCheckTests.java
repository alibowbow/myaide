package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
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
 * 퍼레 자동 점검 (준: "자동테스트도 구현해줘"): the scenarios read and judged as the phone and tools/buddy_eval.py judge them,
 * the words layer catching what it should (and failing a wrong expectation), the tools written out as words read back,
 * the report, and the row and the sheet kept compact.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BuddyCheckTests {
    private static String scenarios() throws Exception {
        try (java.io.InputStream in = RuntimeEnvironment.getApplication().getAssets().open("buddy/scenarios.json")) { return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
    }
    private static BuddyCheck.Scenario one(String json) { return BuddyCheck.parse("[" + json + "]").get(0); }
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void all(View view, Predicate<View> match, List<View> out) {
        if (match.test(view)) out.add(view);
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), match, out);
    }
    private static View text(View root, String start) { return find(root, x -> x instanceof TextView t && !(x instanceof Button) && t.getText().toString().startsWith(start)); }
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
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private static float dp(View v) { return v.getResources().getDisplayMetrics().density; }

    private final java.util.concurrent.Executor runner = BuddyCheckUi.runner;
    private final BuddyCheckUi.Brain brain = BuddyCheckUi.brain;
    private final BuddyCheckUi.Ready ready = BuddyCheckUi.ready;
    private final BuddyCheckUi.Connected connected = BuddyCheckUi.connected;
    @After public void restore() { BuddyCheckUi.runner = runner; BuddyCheckUi.brain = brain; BuddyCheckUi.ready = ready; BuddyCheckUi.connected = connected; }

    /** The one file: enough scenarios, every tool covered, today's failures in it, and the words layer passing it all. */
    @Test public void readsTheScenariosAndPassesTheWords() throws Exception {
        List<BuddyCheck.Scenario> all = BuddyCheck.parse(scenarios());
        assertTrue("at least 40: " + all.size(), all.size() >= 40);
        java.util.Set<String> said = new java.util.HashSet<>(); for (BuddyCheck.Scenario s : all) said.add(s.said());
        for (String today : new String[]{"음악 만들어서 틀어 줘", "유튜브 꺼 줘", "LP 페이지로", "음성종료", "들어가", "이제 쉬어"}) assertTrue("today's failure: " + today, said.contains(today));
        int none = 0; for (BuddyCheck.Scenario s : all) if (s.none()) none++;
        assertTrue("small talk that calls nothing", none >= 5);
        List<BuddyCheck.Result> words = BuddyCheck.words(all);
        for (BuddyCheck.Result r : words) assertTrue(BuddyCheck.line(r), r.pass());
        assertEquals("one result each, and the five checks of the tools", all.size() + 5, words.size());
    }

    /** A wrong expectation fails, with what happened (a check that passes everything checks nothing). */
    @Test public void failsWhatIsWrong() {
        BuddyCheck.Result bye = BuddyCheck.word(one("{\"said\":\"음성종료\",\"expect\":{\"turn\":\"CALL\"}}"));
        assertFalse(bye.pass()); assertEquals("대화 끝(BYE)", bye.got());
        BuddyCheck.Result caught = BuddyCheck.word(one("{\"said\":\"그만할래\",\"expect\":{\"tool\":\"media\"}}"));
        assertFalse("the words end the talk before the brain hears it", caught.pass()); assertTrue(caught.got(), caught.got().startsWith("말 단계에서 가로챔"));
        BuddyCheck.Result notSent = BuddyCheck.word(one("{\"said\":\"손전등 켜 줘\",\"expect\":{\"tool\":\"buddy_toy\"}}"));
        assertFalse("퍼레's own tools are not sent for the phone's words", notSent.pass()); assertTrue(notSent.got(), notSent.got().contains("buddy_toy"));
        assertFalse(BuddyCheck.word(one("{\"said\":\"벌레야 몇 시야\",\"expect\":{\"name\":\"벌레야 몇 시야\"}}")).pass());
        assertTrue(BuddyCheck.word(one("{\"said\":\"벌레야 몇 시야\",\"expect\":{\"name\":\"퍼레야, 몇 시야\"}}")).pass());
        assertFalse("a song's words do not get through", BuddyCheck.word(one("{\"said\":\"사랑해 오늘 밤 너와 함께\",\"expect\":{\"music\":\"사랑해\"}}")).pass());
        assertTrue(BuddyCheck.word(one("{\"said\":\"퍼레야 다음 곡\",\"expect\":{\"music\":\"다음 곡\"}}")).pass());
        assertTrue(BuddyCheck.word(one("{\"said\":\"그만\",\"expect\":{\"turn\":\"PAUSE\"}}")).pass());
        // A file that does not read says which entry.
        try { BuddyCheck.parse("[{\"said\":\"x\",\"expect\":{\"tool\":\"fly\"}}]"); fail(); } catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains("1번째") && e.getMessage().contains("fly")); }
        try { BuddyCheck.parse("[{\"said\":\"x\",\"expect\":{\"tool\":\"coin\",\"none\":true}}]"); fail(); } catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("하나만")); }
        try { BuddyCheck.parse("[{\"said\":\"x\",\"expect\":{\"turn\":\"SING\"}}]"); fail(); } catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("SING")); }
    }

    /** "자가 점검해 줘" opens the check; talk about it does not. */
    @Test public void knowsWhenItIsAskedToCheck() {
        for (String yes : new String[]{"자가 점검해 줘", "퍼레야 자동 점검 해 줘", "자가진단 해 봐", "스스로 점검해 볼래?"}) assertTrue(yes, BuddyCheck.asked(yes));
        for (String no : new String[]{"자가 점검이 뭐야", "차 점검 예약해 줘", "점검", "자동차 점검 받았어"}) assertFalse(no, BuddyCheck.asked(no));
    }

    /**
     * 퍼레 자동 점검 found NVIDIA's quick model writing its tools out ("settings panel=dnd", "mapsearch", "play{query:…}"): read
     * back as the act, asked again with a tool required, and never said aloud; a name in passing is none.
     */
    @Test public void readsToolsWrittenAsWords() throws Exception {
        JSONObject play = BuddyPrompt.act("play{query:<|\"|>아이유 노래<|\"|>}");
        assertEquals("play", play.optString("tool")); assertEquals("아이유 노래", play.optString("query"));
        JSONObject pause = BuddyPrompt.act("media {command: \"pause\"}");
        assertEquals("media", pause.optString("tool")); assertEquals("pause", pause.optString("command"));
        JSONObject wish = BuddyPrompt.act("buddywish(thing=트램펄린)");
        assertEquals("buddy_wish", wish.optString("tool")); assertEquals("트램펄린", wish.optString("thing"));
        JSONObject page = BuddyPrompt.act("홈page(page=LP)");
        assertEquals("home_page", page.optString("tool")); assertEquals("LP", page.optString("page"));
        assertNull("a name in brackets is not a call", BuddyPrompt.act("YouTube(유튜브)에서 찾아볼게"));
        assertNull(BuddyPrompt.act("그냥 이야기야"));
        // Written out with its arguments as words (the quick model's "volume mute": the retry then chose quiet for 음소거): read as written.
        JSONObject mute = BuddyPrompt.act("volume mute");
        assertEquals("volume", mute.optString("tool")); assertEquals("mute", mute.optString("direction"));
        JSONObject six = BuddyPrompt.act("알겠어! ⏰ alarm 6 30");
        assertEquals("alarm", six.optString("tool")); assertEquals(6, six.optInt("hour")); assertEquals(30, six.optInt("minute"));
        assertEquals("dnd", BuddyPrompt.act("settings panel=dnd").optString("panel"));
        assertEquals(2, BuddyPrompt.act("quiet 2 조용히 있을게").optInt("hours"));
        assertEquals("카카오톡", BuddyPrompt.act("open_app 카카오톡").optString("app"));
        assertNull("what it needs is missing: asked again", BuddyPrompt.act("네이버 지도 열게! open_app"));
        assertNull("a name alone: asked again (it fills in which app)", BuddyPrompt.act("유튜브 끌게! close_app"));
        assertNull("words for two texts (who, text) cannot be told apart", BuddyPrompt.act("message 엄마한테 곧 도착한다고"));
        assertNull("nor can a request and its app", BuddyPrompt.act("phone_job 배민에서 지난번 메뉴 다시 담기"));
        assertNull("a list written out", BuddyPrompt.act("pick options=['짜장면', '짬뽕']"));
        assertNull(BuddyPrompt.act("mapsearch"));
        assertNull("a name in passing", BuddyPrompt.act("play 버튼 눌러 봐"));
        assertEquals(0, BuddyPrompt.toolWritten("settings panel=dnd"));
        assertEquals(0, BuddyPrompt.toolWritten("mapsearch"));
        assertTrue(BuddyPrompt.toolWritten("알겠어! ⏰ alarm 6 0") > 0);
        assertEquals(-1, BuddyPrompt.toolWritten("play 버튼 눌러 봐"));
        assertEquals(-1, BuddyPrompt.toolWritten("YouTube Music에서 틀어 줄게"));
        assertNull("never said aloud", BuddyPrompt.spoken("settings panel=dnd"));
        assertEquals("알겠어! ⏰", BuddyPrompt.spoken("알겠어! ⏰ alarm 6 0"));
        assertEquals("유튜브 열고 최신곡 틀게!", BuddyPrompt.spoken("유튜브 열고 최신곡 틀게!\n{tool:openapp,app:YouTube}"));
        // Written out, it is asked again with a tool required (BuddyTools.promised), as a promise is.
        assertTrue(BuddyTools.promised("근처 카페 찾아 줘", "mapsearch"));
        assertTrue(BuddyTools.promised("크리스마스까지 며칠 남았어", "countdays"));
        assertTrue(BuddyTools.promised("음소거 해 줘", "음소거 할게 🔇"));
        assertTrue(BuddyTools.promised("유튜브 꺼 줘", "유튜브 닫을게 👋"));
        assertFalse(BuddyTools.promised("오늘 기분 어때", "나 오늘 완전 좋아!"));
        assertFalse(BuddyTools.promised("노래 불러 줘", "라라라~ 한 곡 부를게!"));
        // The sentences said as they stream leave it out too.
        List<String> said = new ArrayList<>();
        BuddyTalk.Sentences s = new BuddyTalk.Sentences(said::add);
        for (String piece : new String[]{"알겠어! 지금", " 바로 할게. settings", " panel=dnd"}) s.add(piece);
        s.end();
        assertEquals(List.of("알겠어! 지금 바로 할게."), said);
    }

    /** A brain's answer judged: the tool, the arguments that matter (a word of several, a number written as words), nothing. */
    @Test public void judgesTheBrainsAnswer() throws Exception {
        BuddyCheck.Scenario alarm = one("{\"said\":\"오후 3시에 알람 맞춰 줘\",\"expect\":{\"tool\":\"alarm\",\"args\":{\"hour\":15}}}");
        assertTrue(BuddyCheck.judge(alarm, BuddyCheck.VOICE, new JSONObject("{\"tool\":\"alarm\",\"hour\":\"15\",\"minute\":0}"), "", null, 800, 200).pass());
        BuddyCheck.Result wrong = BuddyCheck.judge(alarm, BuddyCheck.VOICE, new JSONObject("{\"tool\":\"alarm\",\"hour\":3}"), "", null, 800, 200);
        assertFalse(wrong.pass()); assertEquals("alarm · hour=3", wrong.got());
        BuddyCheck.Scenario close = one("{\"said\":\"유튜브 꺼 줘\",\"expect\":{\"tool\":\"close_app\",\"args\":{\"app\":\"유튜브|youtube\"}}}");
        assertTrue(BuddyCheck.judge(close, BuddyCheck.VOICE, new JSONObject("{\"tool\":\"close_app\",\"app\":\"YouTube\"}"), "", null, 1, 1).pass());
        BuddyCheck.Result paused = BuddyCheck.judge(close, BuddyCheck.VOICE, new JSONObject("{\"tool\":\"media\",\"command\":\"pause\"}"), "잠깐 멈출게!", null, 1, 1);
        assertFalse("today's failure: it only paused", paused.pass()); assertEquals("media · command=pause", paused.got());
        BuddyCheck.Result words = BuddyCheck.judge(close, BuddyCheck.VOICE, null, "유튜브 닫을게", null, 1, 1);
        assertEquals("도구 안 부름: “유튜브 닫을게”", words.got());
        BuddyCheck.Scenario pick = one("{\"said\":\"짜장면이랑 짬뽕 중에 골라 줘\",\"expect\":{\"tool\":\"pick\",\"args\":{\"options\":[\"짜장면\",\"짬뽕\"]}}}");
        assertTrue(BuddyCheck.judge(pick, BuddyCheck.CHAT, new JSONObject().put("tool", "pick").put("options", new JSONArray().put("짬뽕").put("짜장면")), "", null, 1, 0).pass());
        BuddyCheck.Scenario talk = one("{\"said\":\"심심해\",\"expect\":{\"none\":true}}");
        assertTrue(BuddyCheck.judge(talk, BuddyCheck.VOICE, null, "나랑 놀자!", null, 300, 100).pass());
        assertFalse(BuddyCheck.judge(talk, BuddyCheck.VOICE, new JSONObject("{\"tool\":\"play\",\"query\":\"신나는 노래\"}"), "", null, 300, 100).pass());
        BuddyCheck.Result down = BuddyCheck.judge(talk, BuddyCheck.VOICE, null, "", "Groq HTTP 429", 0, 0);
        assertFalse(down.pass()); assertEquals("답 없음: Groq HTTP 429", down.got());
    }

    /** The report: the counts by layer, the brain and its speed, each failure on its line; the diagnostic lines at most as many as asked. */
    @Test public void writesTheReport() throws Exception {
        List<BuddyCheck.Result> results = new ArrayList<>();
        results.add(BuddyCheck.Result.of(BuddyCheck.WORDS, "음성종료", true, "대화 끝(BYE)", "대화 끝(BYE)", ""));
        BuddyCheck.Scenario music = one("{\"said\":\"음악 만들어서 틀어 줘\",\"expect\":{\"tool\":\"make_music\"}}");
        results.add(BuddyCheck.judge(music, BuddyCheck.VOICE, new JSONObject("{\"tool\":\"play\",\"query\":\"음악\"}"), "", null, 900, 300));
        results.add(BuddyCheck.judge(music, BuddyCheck.CHAT, new JSONObject("{\"tool\":\"make_music\",\"mood\":\"calm piano\"}"), "", null, 1500, 0));
        results.add(BuddyCheck.Result.of(BuddyCheck.READY, "마이크 권한", false, "허용", "허용 안 됨", "mic"));
        assertEquals("통과 2 · 실패 2 (말 1/1 · 두뇌 1/2 · 준비 0/1)", BuddyCheck.summary(results));
        String report = BuddyCheck.report(results, "음성 Groq · openai/gpt-oss-120b", "11-27 15:03", "");
        assertTrue(report, report.contains("[두뇌·음성] “음악 만들어서 틀어 줘” 기대 make_music · 실제 play · query=음악"));
        assertTrue(report, report.contains("[준비] 마이크 권한 기대 허용 · 실제 허용 안 됨"));
        assertTrue(report, report.contains("도구까지 중간"));
        assertFalse("what passed is only counted", report.contains("음성종료"));
        List<String[]> lines = BuddyCheck.lines(results, "음성 Groq", "11-27 15:03", "", 4);
        assertEquals(4, lines.size()); assertEquals("결과", lines.get(0)[0]);
        assertEquals("마이크 허용", BuddyCheckUi.fixLabel("mic")); assertNotNull(BuddyCheckUi.fixIntent(RuntimeEnvironment.getApplication(), "settings:groq"));
        assertEquals("groq", BuddyCheckUi.fixIntent(RuntimeEnvironment.getApplication(), "settings:groq").getStringExtra(MainActivity.SETTINGS_PAGE));
    }

    /** NVIDIA's quick model goes out as it is (it was refused before it left: 퍼레's lines never reached NVIDIA), never offered to choose. */
    @Test public void sendsTheQuickNvidiaModel() throws Exception {
        assertTrue(dev.jun.gyeot.core.NimProtocol.QUICK.contains(BuddyPrompt.NIM_MODEL));
        JSONArray turn = new JSONArray().put(new JSONObject().put("role", "user").put("content", "안녕"));
        assertEquals(BuddyPrompt.NIM_MODEL, dev.jun.gyeot.core.NimProtocol.request(BuddyPrompt.NIM_MODEL, "너는 퍼레야", turn, null).getString("model"));
        assertFalse("not in the model pickers", dev.jun.gyeot.core.NimProtocol.listed(BuddyPrompt.NIM_MODEL));
    }

    /** No brain connected: the dry run says so at once, does nothing on the phone and keeps no miss. */
    @Test public void aDryRunDoesNothing() throws Exception {
        GyeotApp app = (GyeotApp) RuntimeEnvironment.getApplication();
        BuddyCheckUi.Answer a = BuddyCheckUi.ask(app, one("{\"said\":\"유튜브 꺼 줘\",\"expect\":{\"tool\":\"close_app\"}}"), BuddyCheck.VOICE, new java.util.concurrent.atomic.AtomicBoolean());
        assertNull(a.act()); assertNotNull("why it could not", a.failed());
        assertTrue("no miss kept", BuddyMisses.list(app).isEmpty());
    }

    /** 화면 꾸미기 → 퍼레 자동 점검: one list row; its sheet lists only the failures, each a row with its fix chip, compact. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void showsTheRowAndTheReport() throws Exception {
        BuddyCheckUi.runner = Runnable::run;
        BuddyCheckUi.connected = c -> true;
        BuddyCheckUi.ready = c -> List.of(BuddyCheck.Result.of(BuddyCheck.READY, "마이크 권한", true, "허용", "허용", ""),
            BuddyCheck.Result.of(BuddyCheck.READY, "알림 접근(음악 조작)", false, "허용", "허용 안 됨 · 다음 곡·멈춤을 못 해요", "notices"));
        // The brain as it behaved on 2026-11-27 for two of them; right for the rest.
        BuddyCheckUi.brain = (c, s, path, cancelled) -> {
            try {
                if (s.none()) return new BuddyCheckUi.Answer(null, "응응!", null, 400, 150, "Groq · openai/gpt-oss-120b");
                if (s.said().equals("음악 만들어서 틀어 줘") && path.equals(BuddyCheck.VOICE)) return new BuddyCheckUi.Answer(new JSONObject("{\"tool\":\"play\",\"query\":\"음악\"}"), "틀어 줄게!", null, 900, 300, "Groq · openai/gpt-oss-120b");
                if (s.said().equals("유튜브 꺼 줘") && path.equals(BuddyCheck.VOICE)) return new BuddyCheckUi.Answer(new JSONObject("{\"tool\":\"media\",\"command\":\"pause\"}"), "잠깐 멈출게!", null, 700, 250, "Groq · openai/gpt-oss-120b");
                JSONObject act = new JSONObject(s.args().toString()).put("tool", s.tool());
                return new BuddyCheckUi.Answer(act, "할게!", null, 800, 200, path.equals(BuddyCheck.CHAT) ? "Groq · qwen/qwen3-32b" : "Groq · openai/gpt-oss-120b");
            } catch (Exception e) { throw new IllegalStateException(e); }
        };
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        View decor = a.getWindow().getDecorView(); settle(decor);
        View label = text(decor, "퍼레 자동 점검"); assertNotNull("the row", label);
        View row = label; while (!row.isClickable() && row.getParent() instanceof View p) row = p;
        assertTrue("a list row, not a big card: " + row.getHeight() / dp(row) + "dp", row.getHeight() <= 76 * dp(row));
        assertTrue("not a full-width button", !(row instanceof Button));
        shot(decor, "buddy_check_row");
        assertTrue(row.performClick()); shadowOf(Looper.getMainLooper()).idle();
        Dialog sheet = ShadowDialog.getLatestDialog(); assertNotNull("the check's sheet", sheet);
        shadowOf(Looper.getMainLooper()).idle();
        View s = sheet.getWindow().getDecorView(); settle(s);
        View verdict = find(s, v -> v instanceof TextView t && t.getText().toString().matches("통과 \\d+ · 실패 3"));
        assertNotNull("the counts with three failures", verdict);
        assertNotNull("the music failure", find(s, v -> v instanceof TextView t && t.getText().toString().equals("“음악 만들어서 틀어 줘”")));
        View sub = find(s, v -> v instanceof TextView t && t.getText().toString().startsWith("두뇌·음성 · 기대 close_app"));
        assertNotNull("what was expected and what happened", sub);
        assertTrue(((TextView) sub).getText().toString().contains("실제 media · command=pause"));
        assertNull("what passed is not listed", find(s, v -> v instanceof TextView t && t.getText().toString().equals("“손전등 켜 줘”")));
        View fix = find(s, v -> v instanceof Button b && b.getText().toString().equals("알림 접근")); assertNotNull("the fix one tap away", fix);
        assertTrue("a small chip: " + fix.getHeight() / dp(fix) + "dp", fix.getHeight() <= 40 * dp(fix));
        List<View> chips = new ArrayList<>(); all(s, v -> v instanceof Button, chips);
        for (View chip : chips) assertTrue("no wide button: " + ((Button) chip).getText(), chip.getWidth() < s.getWidth() * 0.6 || chip.getVisibility() != View.VISIBLE);
        assertNotNull("again and the report at the line's end", find(s, v -> v instanceof Button b && b.getText().toString().equals("보고서")));
        shot(s, "buddy_check_report");
        // Kept for the 오류 진단 보고서, and as the row's last line.
        assertTrue(BuddyCheckUi.last(a), BuddyCheckUi.last(a).startsWith("통과 "));
        List<dev.jun.gyeot.core.DiagnosticReport.Check> kept = DiagnosticsExport.buddyChecks(a.getApplicationContext());
        assertTrue("in the diagnostics", kept.stream().anyMatch(c -> c.value().contains("음악 만들어서 틀어 줘")));
    }
}
