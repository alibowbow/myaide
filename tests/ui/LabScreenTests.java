package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jun.gyeot.core.LabBook;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LabScreenTests {
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void tap(Activity a, String label) {
        View v = find(a.getWindow().getDecorView(), x -> x instanceof TextView t && t.getText().toString().equals(label));
        assertNotNull(label, v);
        while (!v.isClickable() && v.getParent() instanceof View p) v = p;
        assertTrue(label, v.performClick());
        shadowOf(Looper.getMainLooper()).idle();
    }
    private static void layout(View decor) {
        var m = decor.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }
    private static void save(Bitmap b, String name) throws Exception {
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private static void shot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView(); layout(decor); shadowOf(Looper.getMainLooper()).idle(); layout(decor);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c); save(b, name);
        // The whole scrolled content, too.
        View scroll = find(decor, v -> v instanceof ScrollView s && s.isShown() && s.getChildCount() > 0 && s.getWidth() > decor.getWidth() / 2);
        if (scroll instanceof ScrollView s) {
            View inner = s.getChildAt(0);
            inner.measure(View.MeasureSpec.makeMeasureSpec(s.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = Math.min(inner.getMeasuredHeight(), 12000);
            inner.layout(0, 0, s.getWidth(), inner.getMeasuredHeight());
            Bitmap full = Bitmap.createBitmap(Math.max(1, s.getWidth()), Math.max(1, h), Bitmap.Config.ARGB_8888);
            Canvas fc = new Canvas(full); fc.drawColor(Ui.BG); inner.draw(fc); save(full, name + "-full");
        }
    }
    private static void fresh() throws Exception { java.lang.reflect.Field f = LabRunner.class.getDeclaredField("instance"); f.setAccessible(true); f.set(null, null); }
    private static String seed(Application app) throws Exception {
        fresh();
        LabBook book = new LabBook(new File(app.getFilesDir(), "lab"));
        LabBook.Project p = book.create("리만 제타 영점 간격", "리만 제타 함수의 비자명 영점 간격 분포가 무작위 행렬(GUE)의 고유값 간격 분포를 따르는지 수치로 확인하기", "math");
        String id = p.id();
        book.note(id, "user", "", "영점 간격이 정말 GUE를 따를까?", "", new JSONObject().put("debate", true));
        book.note(id, "say", "theorist", "## 핵심 주장\n- 몽고메리의 짝 상관 추측에 따르면 정규화한 영점 간격은 **GUE**의 고유값 간격과 같은 분포를 따라요.\n- 가설: 높이 T가 클수록 일치가 좋아져요.\n\n확인 방법: 첫 10만 개 영점으로 히스토그램을 그려 Wigner surmise와 비교해요.", "ChatGPT · GPT-5.5", new JSONObject().put("round", 1).put("topic", "GUE"));
        book.note(id, "say", "critic", "수치 일치는 **증명이 아니에요**. 낮은 높이에서는 산술적 보정 때문에 어긋남이 생길 수 있어요. 비교 기준(포아송 분포)도 함께 그려야 해요.", "Grok · Grok 4.3", new JSONObject().put("round", 1));
        book.note(id, "say", "experimenter", "mpmath로 처음 2천 개 영점을 구해 간격을 정규화하고 KS 검정으로 GUE·포아송과 비교할게요.", "Claude · Opus 4.7", new JSONObject().put("round", 2));
        book.note(id, "judge", "pi", "## 합의한 것\n- 수치 비교는 증거일 뿐 증명이 아니에요.\n## 남은 쟁점\n- 낮은 높이의 보정 크기\n## 확인이 필요한 것\n- 실험: 영점 2천 개 간격 분포", "Claude · Opus 4.7", new JSONObject().put("topic", "영점 간격이 정말 GUE를 따를까?"));
        List<LabBook.Source> s = book.sources(id, List.of(new LabBook.Source("", "On the distribution of spacings between zeros of the zeta function", "A. M. Odlyzko", "1987", "Mathematics of Computation", "https://doi.org/10.1090/S0025-5718-1987-0866115-0", "We computed...", "openalex"),
            new LabBook.Source("", "The pair correlation of zeros of the zeta function", "H. L. Montgomery", "1973", "Proc. Symp. Pure Math.", "https://example.org/m", "", "openalex")));
        JSONArray keys = new JSONArray(); for (LabBook.Source x : s) keys.put(x.key());
        book.note(id, "search", "scout", "## 핵심 발견\n- 오들리즈코의 계산에서 높은 영점의 간격은 GUE와 매우 잘 맞았어요 [S1].\n- 짝 상관 함수는 1 − (sin πu / πu)² 꼴이에요 [S2].", "Gemini · Gemini 3.5 Pro", new JSONObject().put("queries", new JSONArray().put("riemann zeta zeros spacing GUE").put("montgomery pair correlation")).put("sources", keys));
        book.note(id, "step", "pi", "토론에서 합의한 실험으로 확인해요.", "Claude · Opus 4.7", new JSONObject().put("action", "experiment").put("focus", "영점 2천 개 간격 분포 vs GUE"));
        book.note(id, "run", "experimenter", "처음 2,000개 영점의 간격을 평균 밀도로 정규화해 GUE와 포아송 분포에 KS 검정을 했어요.", "Claude · Opus 4.7", new JSONObject().put("n", 1).put("goal", "영점 2천 개 간격 분포 vs GUE").put("code", "import mpmath\nzs=[mpmath.zetazero(n) for n in range(1,2001)]\nprint(len(zs))").put("exit", 0).put("millis", 48210).put("output", "zeros: 2000\nKS vs GUE: D=0.031 p=0.42\nKS vs Poisson: D=0.271 p<1e-6").put("figures", new JSONArray()).put("attempts", 2));
        book.note(id, "analysis", "experimenter", "## 결과\n- GUE와의 KS 거리 0.031 (p=0.42), 포아송은 0.271.\n## 해석\n가설을 **지지**해요.", "Claude · Opus 4.7", new JSONObject().put("n", 1));
        book.note(id, "ask", "pi", "이 실험을 리눅스에서 돌려도 될까요?\n\n영점 1만 개로 늘려 높이별로 비교하기", "", new JSONObject().put("action", "experiment").put("focus", "영점 1만 개로 늘려 높이별로 비교하기"));
        book.pending(id, new JSONObject().put("action", "experiment").put("focus", "영점 1만 개로 늘려 높이별로 비교하기").put("why", "높이 의존성 확인"));
        book.autopilot(id, "waiting");
        book.stage(id, "plan", "done"); book.stage(id, "literature", "done"); book.stage(id, "debate", "done"); book.stage(id, "experiment", "done"); book.stage(id, "analysis", "done");
        book.spend(id, 12);
        book.claims(id, List.of(new LabBook.Claim("정규화한 영점 간격은 GUE 분포와 맞는다", "verified", "E1", "실험가", 1), new LabBook.Claim("수치 일치는 증명이 아니다", "agreed", "", "책임연구원", 2), new LabBook.Claim("낮은 높이에서 보정이 크다", "disputed", "", "비평가", 3), new LabBook.Claim("간격은 포아송을 따른다", "refuted", "E1", "실험가", 4)));
        book.report(id, "# 리만 제타 영점 간격\n## 요약\n영점 2천 개의 간격은 GUE와 잘 맞았어요 [E1].\n## 참고문헌\n[S1] Odlyzko (1987)");
        LabBook.Project q = book.create("CRISPR 오프타깃 줄이기", "최신 방법 비교", "bio");
        return id;
    }
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void phoneScreens() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String id = seed(app);
        Activity list = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class)).setup().get();
        shot(list, "phone-list");
        Activity a = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, id)).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        shot(a, "phone-room");
        tap(a, "노트"); shot(a, "phone-notes");
        tap(a, "산출물"); shot(a, "phone-outputs");
        tap(a, "연구원"); shot(a, "phone-team");
        tap(a, "진행판"); shot(a, "phone-board");
    }
    /** 준: "보고서 쓰기가 안돼": the screen jumped to 토론방's oldest words while the switch still said 산출물. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void reportStaysInOutputs() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        seed(app);
        LabBook book = new LabBook(new File(app.getFilesDir(), "lab"));
        String id = book.create("복시, 축농증", "", "medicine").id();
        book.note(id, "say", "scout", "문헌 조사 결과예요.", "", null);
        Activity a = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, id)).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        tap(a, "산출물"); tap(a, "보고서 쓰기");
        // No AI is connected here, so the 기록원 fails at once: the reason must show where the report was asked for.
        View decor = a.getWindow().getDecorView(); boolean shown = false;
        for (int i = 0; i < 60 && !shown; i++) {
            Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle();
            java.util.ArrayList<View> found = new java.util.ArrayList<>();
            decor.findViewsWithText(found, "지난번엔 쓰지 못했어요", View.FIND_VIEWS_WITH_TEXT); shown = !found.isEmpty();
        }
        shot(a, "phone-report-failed");
        assertTrue("the failure shows in 산출물", shown);
        java.util.ArrayList<View> chosen = new java.util.ArrayList<>();
        decor.findViewsWithText(chosen, "산출물, 선택됨", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertFalse("the switch still says 산출물", chosen.isEmpty());
    }
    @Test @Config(qualifiers="w900dp-h700dp-land-night-hdpi") public void wideScreens() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String id = seed(app);
        Activity a = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, id)).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        shot(a, "wide-room");
        LabBook book = new LabBook(new File(app.getFilesDir(), "lab"));
        String fresh = book.create("새 연구", "", "general").id();
        Activity b = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, fresh)).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        shot(b, "wide-intro");
    }
    /** 준: "접었을때 아이콘이 똑같아서 뭔지 알 수가 없어": folded, each research shows the first letters of its title, not one flask for all. */
    @Test @Config(qualifiers="w900dp-h700dp-land-night-hdpi") public void foldedListTellsResearchApart() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String id = seed(app);
        LabBook book = new LabBook(new File(app.getFilesDir(), "lab"));
        book.create("복시, 축농증", "10년 된 복시와 축농증", "bio");
        ((GyeotApp) app).store().settings.edit().putBoolean("labListFolded", true).apply();
        Activity a = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, id)).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        java.util.List<String> marks = new java.util.ArrayList<>();
        collect(a.getWindow().getDecorView(), v -> v instanceof TextView t && t.getWidth() > 0 && t.getWidth() <= Math.round(48 * a.getResources().getDisplayMetrics().density) && t.getText().length() <= 2 && t.getText().length() > 0, v -> marks.add(((TextView) v).getText().toString()));
        assertTrue("each research by its letters: " + marks, marks.containsAll(java.util.List.of("리만", "CR", "복시")));
        assertEquals("복시", LabActivity.initials("복시, 축농증")); assertEquals("Pr", LabActivity.initials("prime gaps")); assertEquals("연구", LabActivity.initials("  ·  "));
        shot(a, "wide-folded");
        ((GyeotApp) app).store().settings.edit().putBoolean("labListFolded", false).apply();
    }
    private static void collect(View v, java.util.function.Predicate<View> want, java.util.function.Consumer<View> got) {
        if (want.test(v)) got.accept(v);
        if (v instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), want, got);
    }
    /** 준: "새로운 연구, 실험을 하고 새로운 논문도", "가설 제시 부터 결론도출까지": ideas, 사전 등록, 판정, 동료 심사 and the paper as a page with its figure. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void paperPipeline() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String id = seed(app);
        LabBook book = new LabBook(new File(app.getFilesDir(), "lab"));
        JSONArray ideas = new JSONArray()
            .put(new JSONObject().put("title", "높이별 GUE 수렴 속도").put("hypothesis", "정규화 간격의 KS 거리는 log T에 반비례해 줄어든다").put("novelty_score", 4).put("feasible", 4).put("impact", 3).put("closest", "[S1]").put("note", "높이별 수렴 속도는 다루지 않았어요"))
            .put(new JSONObject().put("title", "포아송 혼합 모형").put("hypothesis", "낮은 높이 간격은 GUE와 포아송의 혼합이다").put("novelty_score", 3).put("feasible", 3).put("impact", 2));
        JSONObject state = book.state(id); state.put("ideas", ideas).put("idea", ideas.getJSONObject(0)).put("verdict", "supported"); book.state(id, state);
        book.note(id, "ideas", "pi", "**★ 1. 높이별 GUE 수렴 속도**\n정규화 간격의 KS 거리는 log T에 반비례해 줄어든다\n새로움 4/5 · 확인 가능 4/5 · 의미 3/5 · 가장 가까운 연구 [S1]\n\n**2. 포아송 혼합 모형**\n새로움 3/5\n\n고른 아이디어: 1번", "Claude · Opus 5.5", new JSONObject().put("chosen", 0));
        book.note(id, "prereg", "pi", "## 가설\nKS 거리는 log T에 반비례해 줄어든다.\n## 예측\n- P1: 높이 구간 5개에서 KS 거리가 단조 감소\n## 반증 기준\n- 감소하지 않으면 반박", "Claude · Opus 5.5", new JSONObject());
        book.note(id, "verdict", "pi", "## 판정\n지지 · KS 거리가 0.052 → 0.021로 줄었어요 [E1].", "Claude · Opus 5.5", new JSONObject().put("verdict", "supported"));
        book.note(id, "peer", "critic", "## 요약\n수치 실험으로 수렴을 보였어요.\n## 고쳐야 할 것\n1. 오차 막대를 넣어요.", "Grok · Grok 4.3", new JSONObject().put("decision", "minor").put("novelty", 7).put("soundness", 6).put("clarity", 8).put("significance", 6));
        // The experiment's figure, as LabPython keeps it.
        android.graphics.Bitmap fig = android.graphics.Bitmap.createBitmap(640, 400, android.graphics.Bitmap.Config.ARGB_8888); new Canvas(fig).drawColor(0xff3366cc);
        try (FileOutputStream out = new FileOutputStream(new File(book.files(id), "e1-fig1.png"))) { fig.compress(Bitmap.CompressFormat.PNG, 100, out); }
        String paper = "# 리만 제타 영점 간격의 높이별 GUE 수렴\n**초록** 영점 2천 개로 KS 거리가 높이에 따라 줄어드는 것을 보였어요.\n## 1. 서론\n- 기여 1\n## 4. 결과\n| 구간 | KS |\n|---|---|\n| 1 | 0.052 |\n| 5 | 0.021 |\n\n![그림 1. 높이별 KS 거리](e1-fig1.png)\n## 참고문헌\n[S1] Odlyzko (1987)";
        book.paper(id, paper);
        book.note(id, "paper", "scribe", paper, "Claude · Opus 5.5", new JSONObject().put("version", 2).put("revised", true));
        String html = LabPaper.html(paper, book.files(id));
        assertTrue("figure inside the page", html.contains("data:image/jpeg;base64,") && html.contains("<figcaption>그림 1. 높이별 KS 거리</figcaption>"));
        assertTrue("table kept", html.contains("<table") && html.contains("0.021"));
        assertEquals("리만 제타 영점 간격의 높이별 GUE 수렴", LabPaper.title(paper));
        Activity a = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, id)).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        shot(a, "phone-paper-room");
        tap(a, "산출물"); shot(a, "phone-paper-outputs");
        View decor = a.getWindow().getDecorView(); java.util.ArrayList<View> found = new java.util.ArrayList<>();
        decor.findViewsWithText(found, "페이지로 저장 (그림 포함)", View.FIND_VIEWS_WITH_TEXT);
        assertFalse("the paper can be kept as a page", found.isEmpty());
        found.clear(); decor.findViewsWithText(found, "가설 지지", View.FIND_VIEWS_WITH_TEXT);
        assertFalse("the verdict shows above the paper", found.isEmpty());
        tap(a, "진행판"); shot(a, "phone-paper-board");
    }
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void emptyList() throws Exception {
        Application app = RuntimeEnvironment.getApplication(); fresh();
        Activity a = Robolectric.buildActivity(LabActivity.class, new Intent(app, LabActivity.class)).setup().get();
        shot(a, "phone-empty");
    }
}
