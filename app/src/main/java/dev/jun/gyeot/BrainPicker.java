package dev.jun.gyeot;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 퍼레 두뇌, chosen where it is used (준: "대화모델 설정가능하게 해줘", "대화창에서", "그건 항상 기본인데 내가 반복해서 요청해야 넣더라"):
 * the one picker for 화면 꾸미기's 퍼레 두뇌 row and 퍼레와 대화's model chip on the home screen. The quick brains (Qwen, NVIDIA,
 * both), then each of the app's AIs, its models, a model's thinking levels; what is in use marked. changed runs after a choice.
 * Main thread.
 */
final class BrainPicker {
    private BrainPicker() {}
    /** The brain in a few words for 퍼레와 대화's chip: "Qwen", "NVIDIA", "Qwen+NVIDIA", or the chosen model and its depth. */
    static String chip(Activity a) {
        AppStore store = ((GyeotApp) a.getApplication()).store();
        String brain = BuddyTalk.brain(a);
        if (BuddyTalk.NIM.equals(brain)) return "NVIDIA";
        if (BuddyTalk.BOTH.equals(brain)) return "Qwen+NVIDIA";
        if (BuddyTalk.CUSTOM.equals(brain)) {
            try {
                ScopedModels m = ScopedModels.buddy(store); String engine = m.engine();
                if (engine != null && !engine.isBlank() && store.ready(engine)) {
                    String model = m.model(engine), effort = m.effort(engine, model);
                    TeamRoster roster = new TeamRoster(store, m); String name = roster.label(engine, model);
                    if (effort == null || effort.isEmpty()) return name;
                    dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, model);
                    String[] values = profile.values(), labels = dev.jun.gyeot.core.WorkBook.CHATGPT.equals(engine) ? profile.gptLabels() : profile.labels();
                    for (int i = 0; i < values.length; i++) if (effort.equals(values[i]) && i < labels.length) return name + " · " + labels[i];
                    return name;
                }
            } catch (Exception unavailable) { /* Qwen answers then (BuddyTalk.chatText). */ }
        }
        return "Qwen";
    }
    /** What 퍼레 thinks with now, in a line. */
    static String label(Activity a) {
        AppStore store = ((GyeotApp) a.getApplication()).store();
        return switch (BuddyTalk.brain(a)) {
            case BuddyTalk.NIM -> "NVIDIA 빠른 모델 · 늦으면 Qwen";
            case BuddyTalk.BOTH -> "Qwen과 NVIDIA 함께 · 먼저 온 답";
            case BuddyTalk.CUSTOM -> { String l = BuddyTalk.customLabel(store); yield l.isEmpty() ? "고른 AI가 연결 안 됨 · Qwen이 대신 답해요" : l + " · 안 되면 Qwen"; }
            default -> "Qwen · Groq (빠름) · 늦으면 NVIDIA";
        };
    }
    /**
     * 퍼레 두뇌 (준: "우리앱이 수십가지 모델을 사용가능한데 세부모델 고를 수가 없어", "메뉴도 너무 허접해 그냥 텍스트 성의없이 나열"):
     * the quick brains, then each of the app's AIs with its icon and state; an AI opens its own models (every one its
     * connection lists), a model with thinking levels asks for one. What is in use is marked.
     */
    static void show(Activity a, Ui ui, Runnable changed) {
        AppStore store = ((GyeotApp) a.getApplication()).store(); String now = BuddyTalk.brain(a);
        ScopedModels scope; try { scope = ScopedModels.buddy(store); } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        String usedEngine = BuddyTalk.CUSTOM.equals(now) ? scope.engine() : "";
        TeamRoster roster = new TeamRoster(store, scope);
        // Groq's list read again when a day old (준: "kimi k2는 선택안돼": Groq retired it in April 2026; a list kept from before
        // still named it). It shows from the next opening on; nothing here waits for the network.
        if (store.groq.connected() && System.currentTimeMillis() - store.groq.modelsAt() > 24 * 3_600_000L) store.groq.check();
        Ui.Sheet menu = ui.selectionSheet("퍼레 두뇌", "한마디 · 퍼레 대화 · 음성 대화에 써요");
        menu.primary.setVisibility(View.GONE);
        menu.body.addView(ui.sectionHeader("빠른 두뇌"));
        LinearLayout quick = ui.group();
        String[][] fast = {{BuddyTalk.QWEN, "Qwen · Groq", "가장 빨라요 · 늦으면 NVIDIA가 답해요"}, {BuddyTalk.NIM, "NVIDIA 빠른 모델", "늦으면 Qwen이 답해요"}, {BuddyTalk.BOTH, "Qwen과 NVIDIA 함께", "둘에게 묻고 먼저 온 답"}};
        for (String[] q : fast) ui.addRow(quick, ui.listRow(R.drawable.ic_bolt, Ui.ORANGE, q[1], q[2], q[0].equals(now) ? inUse(ui) : null, () -> { menu.dismiss(); BuddyTalk.brain(a, q[0]); changed.run(); }), true);
        menu.body.addView(quick);
        menu.body.addView(ui.sectionHeader("앱의 AI · 모델 직접 고르기"));
        LinearLayout engines = ui.group();
        for (String engine : dev.jun.gyeot.core.TeamPlan.ENGINES) {
            boolean ready = store.ready(engine); int count = ready ? roster.catalog(engine).length() : 0;
            int[] look = engineLook(engine);
            String sub = !ready ? "연결 안 됨 · 설정 → AI 연결에서 연결해요" : engine.equals(usedEngine) ? roster.label(engine, scope.model(engine)) + " · 모델 " + count + "개" : "모델 " + count + "개";
            ui.addRow(engines, ui.listRow(look[0], look[1], dev.jun.gyeot.core.WorkBook.author(engine), sub, engine.equals(usedEngine) ? inUse(ui) : ui.chevron(), () -> {
                if (!ready) { Toast.makeText(a, dev.jun.gyeot.core.WorkBook.author(engine) + "은(는) 설정 → AI 연결에서 먼저 연결해 주세요", Toast.LENGTH_LONG).show(); return; }
                menu.dismiss(); models(a, ui, changed, engine);
            }), true);
        }
        menu.body.addView(engines);
        menu.show();
    }
    /** An AI's models, every one its connection lists; the one in use marked. */
    private static void models(Activity a, Ui ui, Runnable changed, String engine) {
        AppStore store = ((GyeotApp) a.getApplication()).store();
        ScopedModels scope; try { scope = ScopedModels.buddy(store); } catch (Exception e) { return; }
        TeamRoster roster = new TeamRoster(store, scope); org.json.JSONArray catalog = roster.catalog(engine);
        boolean inUse = BuddyTalk.CUSTOM.equals(BuddyTalk.brain(a)) && engine.equals(scope.engine()); String usedModel = inUse ? scope.model(engine) : "";
        int[] look = engineLook(engine);
        Ui.Sheet sheet = ui.selectionSheet(dev.jun.gyeot.core.WorkBook.author(engine), catalog.length() == 0 ? "모델 목록이 없어요 · 설정 → AI 연결에서 새로고침해요" : "모델 " + catalog.length() + "개 · 고르면 퍼레가 이 모델로 생각해요");
        sheet.primary.setVisibility(View.GONE);
        LinearLayout list = ui.group(); java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < catalog.length(); i++) {
            org.json.JSONObject item = catalog.optJSONObject(i); if (item == null) continue;
            String id = item.optString("id"); if (id.isBlank() || !seen.add(id)) continue;
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, id);
            int levels = profile.values().length;
            String sub = (levels > 1 ? "생각 깊이 " + levels + "단계" : "") + (id.equals(roster.label(engine, id)) ? "" : (levels > 1 ? " · " : "") + id);
            ui.addRow(list, ui.listRow(look[0], look[1], roster.label(engine, id), sub, id.equals(usedModel) ? inUse(ui) : levels > 1 ? ui.chevron() : null, () -> {
                sheet.dismiss();
                if (levels > 1) depth(a, ui, changed, engine, id); else apply(a, changed, engine, id, "");
            }), true);
        }
        sheet.body.addView(list);
        sheet.show();
    }
    /** A model's thinking levels; the current one marked. */
    private static void depth(Activity a, Ui ui, Runnable changed, String engine, String model) {
        AppStore store = ((GyeotApp) a.getApplication()).store();
        ScopedModels scope; try { scope = ScopedModels.buddy(store); } catch (Exception e) { return; }
        TeamRoster roster = new TeamRoster(store, scope);
        dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, model);
        String[] values = profile.values(), labels = dev.jun.gyeot.core.WorkBook.CHATGPT.equals(engine) ? profile.gptLabels() : profile.labels();
        String current = scope.effort(engine, model);
        Ui.Sheet sheet = ui.selectionSheet("생각 깊이", roster.label(engine, model) + " · 얕을수록 빨리 답해요");
        sheet.primary.setVisibility(View.GONE);
        LinearLayout list = ui.group();
        // The model's default first, then shallow to deep; "빨라요"/"깊게" by the level itself, not by its place
        // (준: "낮음에 가장깊이생각이라고 되어있어": the levels came as default, high, xhigh, medium, low).
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < values.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> values[x].isEmpty() ? -1 : values[y].isEmpty() ? 1 : Integer.compare(dev.jun.gyeot.core.Reasoning.rank(values[x]), dev.jun.gyeot.core.Reasoning.rank(values[y])));
        int shallow = -1, deep = -1;
        for (int i : order) if (!values[i].isEmpty()) { if (shallow < 0) shallow = i; deep = i; }
        for (int i : order) {
            String value = values[i], label = i < labels.length ? labels[i] : value;
            String note = value.isEmpty() ? "모델이 정한 깊이" : shallow == deep ? "" : i == shallow ? "가장 빨라요" : i == deep ? "가장 깊게 생각해요" : "";
            ui.addRow(list, ui.listRow(R.drawable.ic_tune, Ui.INDIGO, label, note, value.equals(current) ? inUse(ui) : null, () -> { sheet.dismiss(); apply(a, changed, engine, model, value); }), true);
        }
        sheet.body.addView(list);
        sheet.show();
    }
    private static void apply(Activity a, Runnable changed, String engine, String model, String effort) {
        AppStore store = ((GyeotApp) a.getApplication()).store();
        try {
            ScopedModels.buddy(store).chooseProfile(engine, model, effort);
            BuddyTalk.brain(a, BuddyTalk.CUSTOM);
            Toast.makeText(a, "퍼레 두뇌 · " + dev.jun.gyeot.core.WorkBook.author(engine) + " " + new TeamRoster(store).label(engine, model), Toast.LENGTH_SHORT).show();
            changed.run();
        } catch (Exception e) { Toast.makeText(a, e.getMessage() == null ? "바꾸지 못했어요" : e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    /** A small "사용 중" mark at a row's end. */
    private static View inUse(Ui ui) { TextView t = ui.label("사용 중", 12, Ui.ACCENT); t.setPadding(ui.dp(8), 0, ui.dp(4), 0); return t; }
    /** Each AI's icon and hue, as the settings show them. */
    private static int[] engineLook(String engine) {
        return switch (engine) {
            case dev.jun.gyeot.core.WorkBook.CHATGPT -> new int[]{R.drawable.ic_sparkle, Ui.MINT};
            case dev.jun.gyeot.core.WorkBook.CLAUDE -> new int[]{R.drawable.ic_sparkle, Ui.ORANGE};
            case dev.jun.gyeot.core.WorkBook.GEMINI -> new int[]{R.drawable.ic_sparkle, Ui.BLUE};
            case dev.jun.gyeot.core.WorkBook.GROK -> new int[]{R.drawable.ic_sparkle, Ui.GRAY};
            case dev.jun.gyeot.core.WorkBook.NIM -> new int[]{R.drawable.ic_sparkle, Ui.GREEN};
            case dev.jun.gyeot.core.WorkBook.ANTIGRAVITY -> new int[]{R.drawable.ic_sparkle, Ui.PURPLE};
            case dev.jun.gyeot.core.WorkBook.GROQ -> new int[]{R.drawable.ic_bolt, Ui.ORANGE};
            default -> new int[]{R.drawable.ic_sparkle, Ui.ACCENT};
        };
    }
}
