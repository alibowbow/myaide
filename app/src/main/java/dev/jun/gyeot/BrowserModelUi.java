package dev.jun.gyeot;

import android.widget.*;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/** Native browser model picker, independent of the Gecko rendering surface. */
final class BrowserModelUi {
    static Ui.Sheet create(android.content.Context context, Ui ui, AppStore store, String initialBrain, Runnable applied, Runnable connect) {
        BrowserModels models = new BrowserModels(store);
        store.openai.refreshModelsIfDue();
        java.util.Map<String, String> speeds = new java.util.HashMap<>();
        String speedEpoch = store.openai.speedEpoch();
        String[] brain = {initialBrain}, selected = {models.model(brain[0])}, effort = {models.selectedEffort(brain[0], selected[0])};
        Ui.Sheet sheet = ui.selectionSheet("브라우저 모델과 생각 깊이", "브라우저에만 저장 · 다음 지시부터 적용");
        String[] engines = {"none", "gpt", "claude", "gemini", "grok", "nim"};
        String[] labels = {"Live", "GPT", "Claude", "Gemini", "Grok", "NIM"};
        Runnable[] fill = new Runnable[1]; boolean[] depthMode = {false};
        EditText search = ui.input("세부 모델 검색", 1, 100);
        sheet.controls.addView(ui.pills(labels, java.util.Arrays.asList(engines).indexOf(brain[0]), index -> {
            depthMode[0] = false; brain[0] = engines[index]; selected[0] = models.model(brain[0]); effort[0] = models.selectedEffort(brain[0], selected[0]); search.setText(""); fill[0].run();
        }));
        LinearLayout modes = ui.row();
        Button modelMode = ui.chip("세부 모델", () -> { depthMode[0] = false; fill[0].run(); });
        Button thinkingMode = ui.chip("생각 깊이", () -> { depthMode[0] = true; fill[0].run(); });
        modes.addView(modelMode, new LinearLayout.LayoutParams(0, -2, 1)); modes.addView(thinkingMode, new LinearLayout.LayoutParams(0, -2, 1));
        ui.add(sheet.controls, modes, 8); sheet.controls.addView(search);
        android.app.AlertDialog[] diagnostic = {null};
        fill[0] = () -> {
            if ("gpt".equals(brain[0])) store.openai.refreshModelsIfDue();
            modelMode.setSelected(!depthMode[0]); thinkingMode.setSelected(depthMode[0]);
            modelMode.setTextColor(!depthMode[0] ? ACCENT : MUTED); thinkingMode.setTextColor(depthMode[0] ? ACCENT : MUTED);
            modelMode.setBackground(ui.shape(!depthMode[0] ? ACCENT_SOFT : android.graphics.Color.TRANSPARENT, 16));
            thinkingMode.setBackground(ui.shape(depthMode[0] ? ACCENT_SOFT : android.graphics.Color.TRANSPARENT, 16));
            if (depthMode[0]) { search.clearFocus(); context.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0); }
            search.setVisibility(depthMode[0] ? android.view.View.GONE : android.view.View.VISIBLE);
            sheet.body.removeAllViews();
            sheet.body.addView(ui.text(models.label(brain[0], selected[0]), 18, 700, TEXT));
            sheet.body.addView(ui.footer(selected[0]));
            // Gemini Decision picks routine steps before this AI: saved at once, off by default.
            // Live direct plans no steps of its own, so it has no use for it.
            if (!"none".equals(brain[0])) {
                boolean hasKey = store.secrets.has(SecretStore.GEMINI);
                sheet.body.addView(ui.toggle("Gemini 디시전으로 빠르게 조작 (실험)", hasKey && store.settings.getBoolean("browserDecisions", false), value -> {
                    if (value && !store.secrets.has(SecretStore.GEMINI)) {
                        android.widget.Toast.makeText(context, "Gemini 디시전은 Gemini API 키로 써요. 설정 → Gemini에서 키를 먼저 넣어 주세요.", android.widget.Toast.LENGTH_LONG).show();
                        fill[0].run(); return;
                    }
                    store.settings.edit().putBoolean("browserDecisions", value).apply();
                }));
                sheet.body.addView(ui.footer("링크·탭 누르기, 스크롤, 뒤로 가기는 디시전이 0.2초쯤에 골라요. 입력, 읽고 답하기, 완료 확인은 위에서 고른 모델이 해요. "
                    + "보내기·결제·삭제는 고르지 않아요. 페이지 글이 Gemini에도 가고, 요금은 Gemini API 키의 프로젝트로 나가요. 다음 지시부터 적용돼요."));
                // A YouTube transcript the app could not read itself is asked of Gemini's own site, in a tab in the background.
                sheet.body.addView(ui.toggle("유튜브 대본을 Gemini 홈페이지에서 받아 오기", store.youtubeGeminiWeb(), value -> store.settings.edit().putBoolean("youtubeGeminiWeb", value).apply()));
                sheet.body.addView(ui.footer("앱이 자막을 직접 읽지 못한 영상만, 뒤에서 gemini.google.com을 열어 대본을 요청하고 그 답을 가져와요. "
                    + "이 브라우저에 로그인해 둔 Google 계정으로 Gemini에 메시지 하나를 보내고, 페이지 조작은 위에서 고른 모델이 해요."));
            }
            if ("gpt".equals(brain[0])) GptSpeedUi.add(context, ui, sheet.body, store.openai, selected[0], speeds.getOrDefault(selected[0], models.speed(selected[0])), value -> {
                speeds.put(selected[0], value); fill[0].run();
            });
            if ("gpt".equals(brain[0])) {
                sheet.body.addView(ui.footer(store.openai.modelsStatus()));
                if (!speedEpoch.equals(store.openai.speedEpoch())) sheet.body.addView(ui.footer("GPT 연결이 바뀌었어요. 이 창을 닫고 다시 열어 모델을 확인해 주세요."));
                sheet.body.addView(ui.actions(ui.chip("목록 새로고침", store.openai::refreshModelsAsync), ui.chip("모델 목록 진단", () -> {
                    if (diagnostic[0] != null) diagnostic[0].dismiss(); diagnostic[0] = GptCatalogDiagnosticsUi.show(context, store.openai);
                })));
            }
            if (depthMode[0]) { sheet.body.addView(ui.reasoningChoices(models.profile(brain[0], selected[0]), effort[0], "gpt".equals(brain[0]), value -> effort[0] = value)); return; }
            sheet.body.addView(ui.sectionHeader("세부 모델"));
            org.json.JSONArray catalog = models.catalog(brain[0]); String query = search.getText().toString().strip().toLowerCase(java.util.Locale.ROOT); int found = 0;
            for (int i = 0; i < catalog.length(); i++) { JSONObject item = catalog.optJSONObject(i); if (item == null) continue; String id = item.optString("id"), name = models.label(brain[0], id);
                if (!(name + " " + id).toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                boolean chosen = selected[0].equals(id); LinearLayout row = ui.listRow(R.drawable.ic_sparkle, ACCENT, name, id, chosen ? ui.badge("선택됨", ACCENT) : ui.chevron(), () -> {
                    depthMode[0] = true; selected[0] = id; effort[0] = models.selectedEffort(brain[0], id); fill[0].run(); sheet.scroll.smoothScrollTo(0, 0);
                }); row.setSelected(chosen); row.setBackground(chosen ? ui.shape(ACCENT_SOFT, 16) : null); sheet.body.addView(row); found++;
            }
            if (found == 0) sheet.body.addView(ui.footer("일치하는 모델이 없어요. 연결 설정에서 모델 목록을 새로고침할 수 있어요."));
            sheet.body.addView(ui.button("모델 연결 설정", false, () -> { sheet.dismiss(); connect.run(); }));
        };
        search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) {} public void onTextChanged(CharSequence s, int a, int b, int c) { fill[0].run(); } public void afterTextChanged(android.text.Editable e) {} });
        sheet.primary.setText("브라우저에 적용"); sheet.primary.setOnClickListener(v -> {
            try {
                if ("gpt".equals(brain[0]) && !speedEpoch.equals(store.openai.speedEpoch())) throw new IllegalStateException("GPT 연결이 바뀌었어요. 모델 설정을 다시 열어 확인해 주세요.");
                if ("gpt".equals(brain[0]) && speeds.containsKey(selected[0])) {
                    if ("fast".equals(speeds.get(selected[0])) && !speedEpoch.equals(store.openai.speedEpoch())) throw new IllegalStateException("GPT 연결이 바뀌었어요. 모델 설정을 다시 열어 Fast 사용을 확인해 주세요.");
                    models.saveSpeed(selected[0], speeds.get(selected[0]));
                }
                models.save(brain[0], selected[0], effort[0]); sheet.dismiss(); applied.run(); }
            catch (Exception e) { android.widget.Toast.makeText(context, e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
        });
        Runnable watch = () -> { if (sheet.isShowing()) fill[0].run(); };
        sheet.body.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) { store.observe(watch); fill[0].run(); }
            @Override public void onViewDetachedFromWindow(android.view.View v) { store.unobserve(watch); if (diagnostic[0] != null) diagnostic[0].dismiss(); }
        });
        fill[0].run(); return sheet;
    }
}
