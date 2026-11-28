package dev.jun.gyeot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import dev.jun.gyeot.core.Prices;
import dev.jun.gyeot.core.WorkBook;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 설정 → 사용량's 예상 비용 and 두뇌 배분 (두뇌 배분, stage 3): the cost per company and per surface for the period chosen with
 * a compact switcher at the end of the heading's own line (오늘 · 7일 · 30일), subscriptions and free tiers shown as 구독 and
 * 무료 rather than money, the routing table, and 막히면 다음 모델로 as a switch that is on unless the user turns it off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UsageCostTests {
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static TextView text(View root, String start) { return (TextView) find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(start)); }
    private static boolean shows(View root, String part) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().contains(part)) != null; }
    private static void settle(Activity a, View content) {
        View decor = a.getWindow().getDecorView(); var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            // The window first (as on a phone: the views are attached and their direction resolved), then the whole length.
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
    static Prices.Tokens t(String surface, String company, String model, String route, long requests, long in, long read, long write, long out) {
        return new Prices.Tokens(surface, company, model, route, requests, requests, in, read, write, out);
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void costByCompanyAndWhere() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        AppStore store = app.store();
        store.settings.edit().remove(MainChatEngine.FALLBACK).commit();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        List<Prices.Tokens> today = List.of(
            t("main", WorkBook.CLAUDE, "claude-opus-5-5", "key", 3, 120_000, 400_000, 20_000, 9_000),
            t("main", WorkBook.CHATGPT, "gpt-6.1-terra", "plan", 7, 50_000, 0, 0, 4_000),
            t("buddy", WorkBook.GROQ, "qwen/qwen3-32b", "key", 12, 30_000, 0, 0, 2_000),
            t("buddy", UsageLedger.DEVICE, "time", UsageLedger.LOCAL, 5, 0, 0, 0, 0),
            t("chores", WorkBook.GEMINI, "gemma-4-31b-it", "key", 4, 8_000, 0, 0, 600),
            t("browser", WorkBook.GROK, "grok-4.7", "plan", 6, 90_000, 0, 0, 3_000));
        List<Prices.Tokens> month = List.of(t("work", WorkBook.CLAUDE, "claude-fable-5-2", "key", 40, 3_000_000, 9_000_000, 500_000, 400_000));
        UsageUi.Page page = new UsageUi.Page(List.of(), new JSONArray(), List.of(today, today, month), false, false);
        LinearLayout box = ui.column(); box.setPadding(0, 0, 0, ui.dp(24));
        UsageUi usage = new UsageUi(ui, store);
        // The switcher shows 7일 first; start from 오늘 for the picture.
        usage.cost(box, page); usage.routes(box, page);
        ScrollView scroll = new ScrollView(a); scroll.addView(box); a.setContentView(scroll);
        settle(a, box);
        TextView heading = text(box, "예상 비용"); assertNotNull("예상 비용 heading", heading);
        TextView week = text(box, "7일"), month30 = text(box, "30일"), day = text(box, "오늘");
        assertNotNull("the period switcher", week); assertNotNull(month30); assertNotNull(day);
        // Compact: the switch is 34dp segments on the heading's own line, not a full-width bar of its own.
        View track = (View) week.getParent();
        assertSame("the switcher sits on the heading's line", heading.getParent(), track.getParent());
        assertTrue("34dp segments: " + week.getHeight(), week.getHeight() <= ui.dp(36));
        assertTrue("as wide as its words: " + track.getWidth(), track.getWidth() < box.getWidth() / 2);
        day.performClick(); settle(a, box);
        TextView claude = text(box, "Claude");
        assertNotNull("Claude's row", claude);
        assertTrue("Claude is money: " + shows(box, "$"), shows(box, "약 "));
        assertTrue("ChatGPT on its sign-in is 구독", shows(box, "구독 · 요청 7번") || shows(box, "구독 + "));
        assertTrue("Groq's free tier is 무료", shows(box, "무료 · 요청 12번"));
        assertNotNull("answers made on the phone", text(box, "기기 안 (모델 없음)"));
        assertNotNull("by where it was used", text(box, "어디서 썼나"));
        assertNotNull("퍼레 as a surface", text(box, "퍼레"));
        assertNotNull("잡일 as a surface", text(box, "잡일"));
        assertTrue("today's total", shows(box, "합계 $"));
        assertNotNull("the price table, folded", text(box, "요금표"));
        assertTrue("the day the prices were read", shows(box, Prices.CHECKED + " 확인"));
        assertNotNull("두뇌 배분", text(box, "두뇌 배분"));
        assertNotNull("the routing table's talk row", text(box, "대화 · 퍼레 말·글"));
        assertTrue("기기 안 counted today", shows(box, "오늘 5번"));
        shot(scroll.getChildAt(0), "usage_cost_today");
        // 30일: the month's rows instead.
        text(box, "30일").performClick(); settle(a, box);
        assertTrue("the month's Fable work", shows(box, "요청 40번"));
        assertTrue("작업창 in the month", text(box, "작업창") != null);
        // 막히면 다음 모델로: on by default, and the user's switch is kept.
        Switch fallback = (Switch) find(box, x -> x instanceof Switch s && s.getText().toString().equals("막히면 다음 모델로"));
        assertNotNull("the fallback switch", fallback);
        assertTrue("on unless turned off", fallback.isChecked());
        fallback.setPressed(true); fallback.performClick();
        assertFalse("turned off and kept", store.settings.getBoolean(MainChatEngine.FALLBACK, true));
        fallback.performClick();
        assertTrue("on again", store.settings.getBoolean(MainChatEngine.FALLBACK, false));
        settle(a, box);
        shot(scroll.getChildAt(0), "usage_cost_month");
    }

    /** Where a model is picked (the composer's model menu): one short line with the chosen model's rough price beside its AI. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void pickerShowsThePrice() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        AppStore store = app.store();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        ScopedModels main = ScopedModels.main(store);
        main.chooseEngine(WorkBook.CLAUDE); main.model(WorkBook.CLAUDE, "claude-opus-5-5");
        ModelPopover.Catalog catalog = new ModelPopover.Catalog() {
            @Override public JSONArray models(String engine) { return new JSONArray().put(dev.jun.gyeot.core.Jsons.obj("id", "claude-opus-5-5", "name", "Claude Opus 5.5")); }
            @Override public String label(String engine, String model) { return "Claude Opus 5.5"; }
            @Override public dev.jun.gyeot.core.Reasoning.Profile profile(String engine, String model) { return dev.jun.gyeot.core.Reasoning.Profile.of(List.of(), ""); }
            @Override public String effortLabel(String engine, String effort) { return ""; }
            @Override public String effortHint(String effort, String fallback) { return ""; }
            @Override public String team() { return ""; }
        };
        LinearLayout root = ui.column(); root.setPadding(0, ui.dp(500), 0, 0);
        TextView chip = ui.text("Opus 5.5", 13, 500, Ui.TEXT); root.addView(chip, new LinearLayout.LayoutParams(ui.dp(120), ui.dp(34)));
        a.setContentView(root); settle(a, root);
        new ModelPopover(a, ui, store, main, catalog, e -> {}, () -> {}, null, () -> {}).show(chip);
        shadowOf(Looper.getMainLooper()).idle();
        android.widget.PopupWindow window = shadowOf(app).getLatestPopupWindow();
        assertNotNull("the model menu", window);
        View card = window.getContentView();
        TextView caption = (TextView) find(card, x -> x instanceof TextView t && t.getText().toString().startsWith("Claude · "));
        assertNotNull("the price beside the AI's name", caption);
        assertEquals("Claude · 100만 토큰 $4/$20", caption.getText().toString());
        card.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(264), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        assertEquals("one line, not a new row", 1, caption.getLineCount());
        shot(card, "model_menu_price");
    }

    /** 준: "생각깊이가 너무 동떨어진 곳에 떠", "부모 플로팅창을 지우지말고 클로드처럼 해줘": 생각 깊이 opens beside the menu, which stays. */
    @Test @Config(qualifiers="w840dp-h900dp-xhdpi") public void depthOpensBesideTheMenu() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        AppStore store = app.store();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        ScopedModels main = ScopedModels.main(store);
        main.chooseEngine(WorkBook.CLAUDE); main.model(WorkBook.CLAUDE, "claude-opus-5-5");
        ModelPopover.Catalog catalog = new ModelPopover.Catalog() {
            @Override public JSONArray models(String engine) { return new JSONArray().put(dev.jun.gyeot.core.Jsons.obj("id", "claude-opus-5-5", "name", "Claude Opus 5.5")); }
            @Override public String label(String engine, String model) { return "Claude Opus 5.5"; }
            @Override public dev.jun.gyeot.core.Reasoning.Profile profile(String engine, String model) { return dev.jun.gyeot.core.Reasoning.Profile.of(List.of("low", "medium", "high", "xhigh", "max"), "medium"); }
            @Override public String effortLabel(String engine, String effort) { return switch (effort) { case "low" -> "낮음"; case "medium" -> "중간"; case "high" -> "높음"; case "xhigh" -> "엑스트라"; default -> "최대"; }; }
            @Override public String effortHint(String effort, String fallback) { return ""; }
            @Override public String team() { return ""; }
        };
        LinearLayout root = ui.column(); root.setGravity(android.view.Gravity.END); root.setPadding(0, ui.dp(700), ui.dp(24), 0);
        TextView chip = ui.text("Opus 5.5", 13, 500, Ui.TEXT); root.addView(chip, new LinearLayout.LayoutParams(ui.dp(120), ui.dp(34)));
        a.setContentView(root); settle(a, root);
        new ModelPopover(a, ui, store, main, catalog, e -> {}, () -> {}, null, () -> {}).show(chip);
        shadowOf(Looper.getMainLooper()).idle();
        android.widget.PopupWindow menu = shadowOf(app).getLatestPopupWindow();
        View card = menu.getContentView();
        card.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(264), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        View depth = find(card, x -> x.isClickable() && x.getContentDescription() != null && x.getContentDescription().toString().startsWith("생각 깊이"));
        assertNotNull("the 생각 깊이 row", depth);
        depth.performClick(); shadowOf(Looper.getMainLooper()).idle();
        android.widget.PopupWindow side = shadowOf(app).getLatestPopupWindow();
        assertNotSame("a card of its own", menu, side);
        assertTrue("the menu stays", menu.isShowing()); assertTrue(side.isShowing());
        View levels = side.getContentView();
        assertNotNull("its levels", find(levels, x -> x instanceof TextView t && "엑스트라".equals(t.getText().toString())));
        assertNotNull("titled with the model", find(levels, x -> x instanceof TextView t && t.getText().toString().startsWith("생각 깊이 · Claude Opus 5.5")));
        assertNull("no back arrow: it is not a page in place of the menu", find(levels, x -> "뒤로".equals(String.valueOf(x.getContentDescription()))));
        levels.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(264), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        levels.layout(0, 0, levels.getMeasuredWidth(), levels.getMeasuredHeight());
        shot(levels, "model_menu_depth_side");
        // A level chosen: both close.
        View high = find(levels, x -> x.isClickable() && String.valueOf(x.getContentDescription()).startsWith("생각 깊이 높음"));
        high.performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertFalse("chosen: the side card closes", side.isShowing()); assertFalse("and the menu", menu.isShowing());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void nothingYet() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        LinearLayout box = ui.column();
        new UsageUi(ui, app.store()).cost(box, new UsageUi.Page(List.of(), new JSONArray()));
        a.setContentView(box); settle(a, box);
        assertTrue("no answers in the period", shows(box, "동안 받은 답이 없어요"));
    }

    /**
     * 준: "1000회 중에 13회만 썼는데 왜 막힌거야", "Groq이 막은거야 앱이 막은거야": Groq's tokens a day (200,000 for Qwen 3.8 27B)
     * filled after 13 long requests. Kept as Groq's own limit with its wait, the model back after it (not at Pacific midnight), its
     * tokens shown next to its requests; a mark from before (no kind) no longer keeps it out; Gemini's own day limit stays.
     */
    @Test public void groqTokenDayLimitSaysWhoWhatAndWhen() throws Exception {
        String qwen = "qwen/qwen3.8-27b";
        long now = java.time.Instant.parse("2026-11-28T03:00:00Z").toEpochMilli();
        org.json.JSONObject state = new org.json.JSONObject();
        for (int i = 0; i < 13; i++) dev.jun.gyeot.core.GeminiQuota.used(state, qwen, now - (13 - i) * 60_000L, 15_000);
        assertFalse("195k of 200k today: one more long request would pass the day's tokens", dev.jun.gyeot.core.GeminiQuota.ready(qwen, state, now, 15_000));
        assertTrue("a short one still fits", dev.jun.gyeot.core.GeminiQuota.ready(qwen, state, now, 2_000));
        String body = "{\"error\":{\"message\":\"Rate limit reached for model `qwen/qwen3.8-27b` in organization `org_x` service tier `on_demand` on tokens per day (TPD): Limit 200000, Used 196000, Requested 15000. Please try again in 23m14.4s. Need more tokens?\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";
        dev.jun.gyeot.core.GeminiQuota.groqDay(state, qwen, now, body);
        assertFalse("resting as Groq said", dev.jun.gyeot.core.GeminiQuota.ready(qwen, state, now + 60_000, 0));
        dev.jun.gyeot.core.GeminiQuota.Row row = dev.jun.gyeot.core.GeminiQuota.rows(state, new String[]{qwen}, now, true).get(0);
        assertEquals("Groq 하루 토큰 한도가 찼어요 · 24분 뒤 다시", row.status());
        assertTrue(row.blocked()); assertEquals(13, row.uses()); assertEquals(1000, row.perDay());
        assertEquals(195_000, row.tokens()); assertEquals(200_000, row.tokensPerDay());
        long later = now + 24 * 60_000L;
        assertEquals("", dev.jun.gyeot.core.GeminiQuota.rows(state, new String[]{qwen}, later, true).get(0).status());
        assertTrue("back after Groq's wait, not at midnight", dev.jun.gyeot.core.GeminiQuota.ready(qwen, state, later, 2_000));
        // Requests a day: the limit Groq names is kept; no time named: out until the day turns.
        org.json.JSONObject other = new org.json.JSONObject();
        dev.jun.gyeot.core.GeminiQuota.groqDay(other, "openai/gpt-oss-120b", now, "Rate limit reached for model `openai/gpt-oss-120b` on requests per day (RPD): Limit 1000, Used 1000, Requested 1.");
        dev.jun.gyeot.core.GeminiQuota.Row full = dev.jun.gyeot.core.GeminiQuota.rows(other, new String[]{"openai/gpt-oss-120b"}, now, true).get(0);
        assertEquals("Groq 하루 요청 한도가 찼어요 · 내일 다시", full.status()); assertTrue(full.blocked());
        // A mark from before this version (full, no kind) does not keep a Groq model out; a Gemini one's day limit still does.
        org.json.JSONObject old = new org.json.JSONObject();
        dev.jun.gyeot.core.GeminiQuota.used(old, qwen, now, 100); old.getJSONObject(qwen).put("full", true);
        assertTrue(dev.jun.gyeot.core.GeminiQuota.ready(qwen, old, now, 100));
        assertEquals("", dev.jun.gyeot.core.GeminiQuota.rows(old, new String[]{qwen}, now, true).get(0).status());
        assertTrue(dev.jun.gyeot.core.GeminiQuota.refused(old, "gemini-3.5-flash-lite", 429, "Quota exceeded for metric generate_content_free_tier_requests, limit: 500, model: gemini-3.5-flash-lite PerDay", now));
        assertFalse(dev.jun.gyeot.core.GeminiQuota.ready("gemini-3.5-flash-lite", old, now, 0));
        assertEquals("오늘 한도를 다 썼어요", dev.jun.gyeot.core.GeminiQuota.rows(old, new String[]{"gemini-3.5-flash-lite"}, now, false).get(0).status());
        // Groq's waits as it writes them.
        assertEquals(3_724_000, dev.jun.gyeot.core.GeminiQuota.groqRetry("Please try again in 1h2m3.5s."));
        assertEquals(7_660 + 500, dev.jun.gyeot.core.GeminiQuota.groqRetry("Please try again in 7.66s"));
        assertEquals(450 + 500, dev.jun.gyeot.core.GeminiQuota.groqRetry("Please try again in 450ms"));
        assertEquals("none named: a minute", 60_000, dev.jun.gyeot.core.GeminiQuota.groqRetry("Rate limit reached"));
        // Each section keeps to its own company's models.
        org.json.JSONObject both = new org.json.JSONObject();
        dev.jun.gyeot.core.GeminiQuota.used(both, "gemini-3.8-flash", now, 0); dev.jun.gyeot.core.GeminiQuota.used(both, "meta-llama/llama-3.3-70b-versatile", now, 900);
        List<String> google = dev.jun.gyeot.core.GeminiQuota.rows(both, new String[]{"gemma-4-31b-it"}, now, false).stream().map(dev.jun.gyeot.core.GeminiQuota.Row::model).toList();
        List<String> groq = dev.jun.gyeot.core.GeminiQuota.rows(both, new String[]{"openai/gpt-oss-120b"}, now, true).stream().map(dev.jun.gyeot.core.GeminiQuota.Row::model).toList();
        assertEquals(List.of("gemma-4-31b-it", "gemini-3.8-flash"), google);
        assertEquals(List.of("openai/gpt-oss-120b", "meta-llama/llama-3.3-70b-versatile"), groq);
        assertEquals("Llama 3.3 70B: 100k tokens a day", 100_000, dev.jun.gyeot.core.GeminiQuota.rows(both, new String[]{}, now, true).get(0).tokensPerDay());
    }

    /** 준: "제미니 무료 api키랑 Groq 모델 한도 서로 분리해줘 헷갈려": two cards, each with its own models; Groq's rows say tokens. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void geminiAndGroqQuotasApart() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        AppStore store = app.store(); long now = System.currentTimeMillis();
        String qwen = BuddyTalk.qwen(store);
        store.changeGeminiQuota(state -> {
            for (int i = 0; i < 13; i++) dev.jun.gyeot.core.GeminiQuota.used(state, qwen, now - 60_000L * (13 - i), 15_000);
            for (int i = 0; i < 28; i++) dev.jun.gyeot.core.GeminiQuota.used(state, GroqWork.CHORES, now - 60_000L * (28 - i), 1_800);
            dev.jun.gyeot.core.GeminiQuota.groqDay(state, qwen, now, "Rate limit reached for model `" + qwen + "` on tokens per day (TPD): Limit 200000, Used 196000, Requested 15000. Please try again in 23m14.4s.");
            for (int i = 0; i < 3; i++) dev.jun.gyeot.core.GeminiQuota.used(state, "gemini-3.5-flash-lite", now, 0);
            return null;
        }, false);
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a); UsageUi usage = new UsageUi(ui, store);
        LinearLayout box = ui.column(); box.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24));
        box.addView(ui.sectionHeader("Gemini 무료 한도 · Gemini API 키")); LinearLayout google = usage.quota(usage.geminiModels(), false, now); box.addView(google);
        box.addView(ui.sectionHeader("Groq 무료 한도 · Groq API 키")); LinearLayout groq = usage.quota(usage.groqModels(), true, now); box.addView(groq);
        ScrollView scroll = new ScrollView(a); scroll.addView(box); a.setContentView(scroll); settle(a, box);
        assertNotNull("Gemma in Gemini's card", text(google, "Gemma 4 31B")); assertNull("no Groq model in Gemini's card", text(google, "gpt-oss"));
        assertNotNull(text(google, "Gemini 3.5 Flash-Lite")); assertTrue("Gemini: when Google fills it again", shows(google, "다시 채워져요"));
        assertNotNull("GPT OSS in Groq's card", text(groq, "gpt-oss-120b")); assertNull("labels without a Groq prefix there", text(groq, "Groq gpt-oss"));
        assertNotNull("퍼레's Qwen in Groq's card", text(groq, qwen.replaceFirst("^[^/]+/", "")));
        assertNull("no Gemini model in Groq's card", text(groq, "Gemma"));
        assertTrue("who stopped it and when", shows(groq, "Groq 하루 토큰 한도가 찼어요 · 24분 뒤 다시"));
        assertTrue("its requests", shows(groq, "오늘 13 / 1000회"));
        assertNotNull("what it is for", text(groq, qwen.replaceFirst("^[^/]+/", "") + " · 퍼레 대화")); assertNotNull(text(groq, "gpt-oss-120b · 잡일"));
        assertFalse("no empty token counts", shows(groq, "토큰 0"));
        assertTrue("GPT OSS's tokens today", shows(groq, "토큰 50.4k / 200k"));
        assertFalse("no Pacific refill time on Groq's rows", shows(groq, "다시 채워져요"));
        shot(box, "usage-quota-split");
    }
}
