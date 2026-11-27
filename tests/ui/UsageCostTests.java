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

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void nothingYet() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        LinearLayout box = ui.column();
        new UsageUi(ui, app.store()).cost(box, new UsageUi.Page(List.of(), new JSONArray()));
        a.setContentView(box); settle(a, box);
        assertTrue("no answers in the period", shows(box, "동안 받은 답이 없어요"));
    }
}
