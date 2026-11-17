package dev.jun.gyeot;

import android.content.Context;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.jun.gyeot.core.*;
import java.util.*;
import java.util.function.*;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;
import static dev.jun.gyeot.Ui.*;

/** Native team controls: one conversation, a compact activity group, and a bottom sheet. */
final class WorkTeamUi {
    private final Ui ui;
    private final AppStore store;
    private final ScopedModels scoped;
    private final Context context;
    private final Function<String, JSONArray> catalogs;
    private final BiFunction<String, String, String> labels;
    private final BiFunction<String, String, Reasoning.Profile> reasoning;
    private final Runnable changed;
    private static final String[] ENGINES = TeamPlan.ENGINES.toArray(new String[0]);
    WorkTeamUi(Context context, Ui ui, AppStore store, Function<String, JSONArray> catalogs,
            BiFunction<String, String, String> labels, BiFunction<String, String, Reasoning.Profile> reasoning, Runnable changed) {
        this(context, ui, store, new ScopedModels(store, ""), catalogs, labels, reasoning, changed);
    }
    WorkTeamUi(Context context, Ui ui, AppStore store, ScopedModels scoped, Function<String, JSONArray> catalogs,
            BiFunction<String, String, String> labels, BiFunction<String, String, Reasoning.Profile> reasoning, Runnable changed) {
        this.scoped = scoped;
        this.context = context; this.ui = ui; this.store = store; this.catalogs = catalogs; this.labels = labels; this.reasoning = reasoning; this.changed = changed;
    }
    private TextView heading(String text) { TextView v = ui.text(text, 14, 650, TEXT); v.setPadding(0, ui.dp(22), 0, ui.dp(10)); v.setAccessibilityHeading(true); return v; }
    private LinearLayout panel() {
        LinearLayout v = ui.column(); android.graphics.drawable.GradientDrawable background = ui.shape(CARD, 22);
        background.setStroke(Math.max(1, ui.dp(.5f)), SEPARATOR); v.setBackground(background); v.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)); return v;
    }
    private View divider() { View v = new View(context); v.setBackgroundColor(SEPARATOR); v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f)))); return v; }
    private Button action(String text, Runnable run) { Button b = ui.button(text, false, run); b.setMinHeight(ui.dp(48)); return b; }
    void show() {
        TeamRoster models = new TeamRoster(store, scoped); JSONArray draft = models.manual();
        boolean[] enabled = {scoped.teamEnabled()}, fallback = {scoped.teamFallback()}, automatic = {scoped.teamAutomatic()}; int[] parallel = {scoped.teamParallel()};
        Set<String> excluded = models.excluded(); JSONObject[] removed = {null}; int[] removedAt = {0};
        Ui.Sheet sheet = ui.selectionSheet("에이전트 팀", "이 작업창에만 저장돼요 · 다음 지시부터 적용");
        Runnable[] fill = new Runnable[1];
        IntConsumer remove = index -> { removed[0] = copy(draft.optJSONObject(index)); removedAt[0] = index; draft.remove(index); fill[0].run(); };
        fill[0] = () -> {
            sheet.body.removeAllViews();
            TextView lead = ui.text("팀장 · " + (scoped.engine().isEmpty() ? "작업창에서 선택" : WorkBook.author(scoped.engine())), 15, 650, ACCENT);
            lead.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(16)); sheet.body.addView(lead);
            sheet.body.addView(ui.segmented(new String[]{"자동 구성", "직접 구성"}, automatic[0] ? 0 : 1, index -> {
                automatic[0] = index == 0; if (automatic[0]) enabled[0] = true; fill[0].run();
            }));
            TextView modeNote = ui.label(automatic[0] ? "팀장이 필요한 모델·역할·인원을 골라 일을 나눠요. 간단한 요청은 직접 처리해요."
                : "모델 변경·생각 깊이·삭제를 직접 설정해요. 역할 이름은 선택 사항이에요.", 13, MUTED);
            modeNote.setPadding(0, ui.dp(12), 0, ui.dp(12)); sheet.body.addView(modeNote);
            LinearLayout toggles = panel();
            Switch team = ui.toggle("팀 작업", enabled[0], value -> enabled[0] = value); team.setMinHeight(ui.dp(56)); toggles.addView(team); toggles.addView(divider());
            Switch handoff = ui.toggle("한도 도달 시 자동 전환", fallback[0], value -> fallback[0] = value); handoff.setMinHeight(ui.dp(56)); toggles.addView(handoff);
            sheet.body.addView(toggles);
            sheet.body.addView(heading("동시에 실행할 작업"));
            int[] counts = {1, 2, 4, 8}; LinearLayout numbers = ui.row();
            for (int count : counts) {
                TextView number = ui.text(String.valueOf(count), 16, 650, count == parallel[0] ? ON_ACCENT : TEXT);
                number.setGravity(Gravity.CENTER); number.setMinimumHeight(ui.dp(48)); number.setBackground(ui.shape(count == parallel[0] ? ACCENT : FILL, 12));
                number.setContentDescription("동시 작업 " + count + "개"); number.setSelected(count == parallel[0]); number.setFocusable(true);
                number.setOnClickListener(v -> { parallel[0] = count; fill[0].run(); }); Ui.pressable(number, false);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.rightMargin = ui.dp(4); numbers.addView(number, p);
            }
            sheet.body.addView(numbers);
            TextView countNote = ui.label("넘치는 작업은 대기해요. 같은 모델에도 여러 작업을 맡길 수 있어요.", 13, MUTED); countNote.setPadding(0, ui.dp(8), 0, 0); sheet.body.addView(countNote);
            if (automatic[0]) {
                sheet.body.addView(heading("자동 구성에 사용할 연결")); LinearLayout accounts = panel();
                for (String engine : ENGINES) {
                    boolean ready = store.ready(engine);
                    Switch allow = ui.toggle(WorkBook.author(engine) + (ready ? "" : " · 연결 필요"), ready && !excluded.contains(engine), value -> {
                        if (value) excluded.remove(engine); else excluded.add(engine); fill[0].run();
                    }); allow.setEnabled(ready); allow.setMinHeight(ui.dp(48)); allow.setContentDescription(WorkBook.author(engine) + " 자동 구성 허용"); accounts.addView(allow);
                }
                sheet.body.addView(accounts);
                JSONArray candidates = models.automatic(excluded);
                sheet.body.addView(heading("팀장이 고를 후보 · " + candidates.length() + "개"));
                LinearLayout choices = panel();
                for (int i = 0; i < candidates.length(); i++) {
                    JSONObject p = candidates.optJSONObject(i); if (i > 0) choices.addView(divider());
                    TextView candidate = ui.text(models.label(p.optString("engine"), p.optString("model")) + "\n" + WorkBook.author(p.optString("engine")) + " · " + depth(p), 14, 500, TEXT);
                    candidate.setPadding(0, ui.dp(12), 0, ui.dp(12)); choices.addView(candidate);
                }
                if (candidates.length() == 0) choices.addView(ui.footer("사용할 연결을 켜거나 설정에서 AI 계정을 연결해 주세요."));
                sheet.body.addView(choices);
                sheet.body.addView(ui.footer("연결별 현재 모델부터 최대 12개를 고르게 후보로 준비해요. 생각 깊이는 각 모델의 저장 설정을 따르며, 실제 팀원과 결과는 작업 진행 상황에 표시돼요. 직접 구성한 목록은 보관됩니다."));
            } else {
            sheet.body.addView(heading("사용할 모델 · 자동 전환 우선순위"));
            if (removed[0] != null && draft.length() < TeamPlan.MAX_PROFILES) sheet.body.addView(action("삭제 취소", () -> {
                int at = Math.min(removedAt[0], draft.length());
                try { for (int i = draft.length(); i > at; i--) draft.put(i, draft.opt(i - 1)); draft.put(at, removed[0]); } catch (Exception e) { throw new IllegalStateException(e); }
                removed[0] = null; fill[0].run();
            }));
            LinearLayout roster = panel();
            if (draft.length() == 0) { TextView empty = ui.text("함께 일할 모델을 추가해 주세요", 15, 450, MUTED); empty.setPadding(0, ui.dp(20), 0, ui.dp(20)); roster.addView(empty); }
            for (int i = 0; i < draft.length(); i++) {
                int index = i; JSONObject p = draft.optJSONObject(i); boolean ready = store.ready(p.optString("engine"));
                if (i > 0) roster.addView(divider());
                LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(72));
                LinearLayout words = ui.column(); words.setPadding(0, ui.dp(12), ui.dp(8), ui.dp(12));
                String actualName = labels.apply(p.optString("engine"), p.optString("model"));
                words.addView(ui.text((i + 1) + ". " + actualName, 16, 600, TEXT));
                if (p.optBoolean("customName")) words.addView(ui.label("역할 · " + p.optString("name"), 13, MUTED));
                TextView model = ui.text(WorkBook.author(p.optString("engine")) + " · " + depth(p) + (ready ? "" : " · 연결 필요"), 13, 400, ready ? MUTED : ERROR);
                model.setPadding(0, ui.dp(4), 0, 0); words.addView(model); words.setFocusable(true); words.setContentDescription(p.optString("name") + ", 모델·생각 깊이·이름 수정");
                words.setOnClickListener(v -> edit(draft, index, fill[0], () -> remove.accept(index))); Ui.pressable(words, false);
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                Switch use = ui.toggle("", p.optBoolean("enabled", true), value -> put(p, "enabled", value)); use.setContentDescription(p.optString("name") + " 사용"); use.setMinWidth(ui.dp(48)); use.setMinHeight(ui.dp(48)); row.addView(use);
                roster.addView(row);
                LinearLayout actions = ui.row();
                Button edit = action("모델 변경", () -> edit(draft, index, fill[0], () -> remove.accept(index))); edit.setContentDescription(actualName + " 모델 변경");
                Button delete = action("삭제", () -> remove.accept(index)); delete.setTextColor(ERROR); delete.setContentDescription(actualName + " 팀에서 삭제");
                actions.addView(edit, new LinearLayout.LayoutParams(0, -2, 1)); actions.addView(delete, new LinearLayout.LayoutParams(ui.dp(80), -2)); roster.addView(actions);
                TextView depth = ui.text("생각 깊이 · " + depth(p) + "  ›", 14, 600, ACCENT); depth.setMinimumHeight(ui.dp(48)); depth.setGravity(Gravity.CENTER_VERTICAL);
                depth.setFocusable(true); depth.setContentDescription(p.optString("name") + " 생각 깊이 변경");
                depth.setOnClickListener(v -> { Ui.Sheet depths = ui.selectionSheet("생각 깊이", p.optString("name") + " · 다음 지시부터 적용");
                    String[] selected = {p.optString("effort")}; depths.body.addView(ui.reasoningChoices(reasoning.apply(p.optString("engine"), p.optString("model")), selected[0], WorkBook.CHATGPT.equals(p.optString("engine")), value -> selected[0] = value));
                    depths.primary.setText("선택 적용"); depths.primary.setOnClickListener(button -> { put(p, "effort", selected[0]); depths.dismiss(); fill[0].run(); }); depths.show();
                }); Ui.pressable(depth, true); roster.addView(depth);
            }
            sheet.body.addView(roster);
            Button add = action("모델 추가", () -> {
                if (draft.length() >= TeamPlan.MAX_PROFILES) { store.status("모델 프로필은 12개까지 만들 수 있어요."); return; }
                pick("", "", (engine, model) -> { draft.put(obj("id", "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "name", labels.apply(engine, model), "customName", false, "engine", engine, "model", model, "effort", "", "enabled", true)); fill[0].run(); });
            }); sheet.body.addView(add);
            if (TeamPlan.enabled(draft).isEmpty()) sheet.body.addView(ui.footer("사용할 모델이 없으면 저장할 때 팀 작업과 자동 전환이 꺼져요."));
            }
            TextView note = ui.label("선택한 모델의 계정 할당량·API 요금이 적용돼요. 자동 전환은 위 목록 순서로 진행하며, 남은 한도는 제공자가 알려줄 때만 확인할 수 있어요. 변경은 다음 지시부터 적용됩니다.", 13, MUTED);
            note.setPadding(ui.dp(2), ui.dp(16), ui.dp(2), ui.dp(4)); sheet.body.addView(note);
        };
        sheet.primary.setText("팀 설정 저장"); sheet.primary.setOnClickListener(v -> {
            try {
                JSONArray valid = TeamPlan.profiles(draft); boolean usable = !TeamPlan.enabled(automatic[0] ? models.automatic(excluded) : valid).isEmpty();
                scoped.saveTeam(valid, automatic[0], enabled[0] && usable, fallback[0] && usable, parallel[0], excluded);
                changed.run(); store.changed(); sheet.dismiss();
            } catch (Exception e) { store.error("팀 설정을 저장하지 못했어요. 모델을 확인해 주세요."); }
        });
        fill[0].run(); sheet.show();
    }
    private void edit(JSONArray draft, int index, Runnable refresh, Runnable remove) {
        JSONObject original = draft.optJSONObject(index), edited = copy(original);
        Ui.Sheet sheet = ui.selectionSheet("팀 모델 편집", "모델을 고르고 팀 설정 저장까지 눌러 주세요");
        sheet.controls.addView(ui.label("역할 이름 · 선택 사항", 13, MUTED));
        EditText name = ui.input("예: 코드 검토 · 비워 두면 모델명 사용", 1, 60); name.setText(edited.optBoolean("customName") ? edited.optString("name") : ""); sheet.controls.addView(name);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            sheet.body.removeAllViews(); String engine = edited.optString("engine"), model = edited.optString("model");
            sheet.body.addView(action(WorkBook.author(engine) + " · " + labels.apply(engine, model), () -> pick(engine, model, (nextEngine, nextModel) -> {
                if (!nextEngine.equals(engine) || !nextModel.equals(model)) { put(edited, "engine", nextEngine); put(edited, "model", nextModel); put(edited, "effort", ""); }
                fill[0].run();
            })));
            Reasoning.Profile info = reasoning.apply(engine, model);
            sheet.body.addView(heading("생각 깊이"));
            sheet.body.addView(ui.reasoningChoices(info, edited.optString("effort"), WorkBook.CHATGPT.equals(engine), value -> put(edited, "effort", value)));
            sheet.body.addView(heading("자동 전환 순서"));
            if (index > 0) sheet.body.addView(action("한 칸 위로", () -> { move(draft, index, index - 1); sheet.dismiss(); refresh.run(); }));
            if (index + 1 < draft.length()) sheet.body.addView(action("한 칸 아래로", () -> { move(draft, index, index + 1); sheet.dismiss(); refresh.run(); }));
            Button delete = action("팀에서 제거", () -> { sheet.dismiss(); remove.run(); }); delete.setTextColor(ERROR); sheet.body.addView(delete);
        };
        sheet.primary.setText("변경 적용"); sheet.primary.setOnClickListener(v -> { put(edited, "customName", !name.getText().toString().strip().isEmpty()); put(edited, "name", name.getText().toString().strip().isEmpty() ? labels.apply(edited.optString("engine"), edited.optString("model")) : name.getText().toString().strip());
            try { draft.put(index, TeamPlan.profile(edited)); } catch (Exception e) { store.error("모델 설정을 확인해 주세요."); return; }
            sheet.dismiss(); refresh.run(); });
        fill[0].run(); sheet.show();
    }
    private String depth(JSONObject p) {
        Reasoning.Profile info = reasoning.apply(p.optString("engine"), p.optString("model"));
        String selected = p.optString("effort"), resolved = info.resolve(selected);
        return selected.isEmpty() ? "기본" + (resolved.isEmpty() ? "" : " (" + (WorkBook.CHATGPT.equals(p.optString("engine")) ? Reasoning.gptLabel(resolved) : Reasoning.label(resolved)) + ")") : (WorkBook.CHATGPT.equals(p.optString("engine")) ? Reasoning.gptLabel(selected) : Reasoning.label(selected));
    }
    private static void move(JSONArray draft, int from, int to) { try { Object other = draft.opt(to); draft.put(to, draft.opt(from)); draft.put(from, other); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    private static String effort(String value) { return switch (value) { case "none" -> "끔"; case "minimal" -> "최소"; case "low" -> "낮음"; case "medium" -> "보통"; case "high" -> "높음"; case "xhigh" -> "매우 높음"; case "max" -> "Max"; case "ultra" -> "Ultra"; default -> "기본"; }; }
    private void pick(String preferred, String current, BiConsumer<String, String> selected) {
        String[] engine = {preferred}; if (engine[0].isBlank() || !store.ready(engine[0])) for (String e : ENGINES) if (store.ready(e)) { engine[0] = e; break; }
        if (engine[0].isBlank()) { store.status("설정에서 사용할 AI 계정을 먼저 연결해 주세요."); return; }
        String[] chosen = {engine[0].equals(preferred) ? current : ""};
        Ui.Sheet sheet = ui.selectionSheet("팀에 사용할 모델", "선택 표시를 확인하고 아래에서 적용하세요"); sheet.primary.setText("모델 선택");
        LinearLayout providers = ui.row(); HorizontalScrollView horizontal = new HorizontalScrollView(context); horizontal.setHorizontalScrollBarEnabled(false); horizontal.addView(providers); sheet.controls.addView(horizontal);
        EditText search = ui.input("모델 검색", 1, 80); sheet.controls.addView(search);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            providers.removeAllViews();
            for (String e : ENGINES) if (store.ready(e)) {
                Button button = action(WorkBook.author(e), () -> { engine[0] = e; chosen[0] = e.equals(preferred) ? current : ""; search.setText(""); fill[0].run(); }); button.setSelected(engine[0].equals(e)); button.setTextColor(engine[0].equals(e) ? ACCENT : MUTED); button.setBackground(ui.shape(engine[0].equals(e) ? ACCENT_SOFT : FILL, 14)); providers.addView(button);
            }
            sheet.body.removeAllViews(); JSONArray models = catalogs.apply(engine[0]); String query = search.getText().toString().strip().toLowerCase(Locale.ROOT); int found = 0;
            for (int i = 0; i < models.length(); i++) {
                JSONObject p = models.optJSONObject(i); if (p == null) continue; String id = p.optString("id"), label = labels.apply(engine[0], id);
                if (id.isBlank() || !(label + " " + id).toLowerCase(Locale.ROOT).contains(query)) continue;
                String by = engine[0]; TextView row = ui.text(label + (label.equals(id) ? "" : "\n" + id), 15, 500, TEXT); row.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(14)); row.setMinimumHeight(ui.dp(56)); row.setFocusable(true);
                boolean on = id.equals(chosen[0]); row.setSelected(on); row.setContentDescription(label + ", " + id + (on ? ", 선택됨" : "")); row.setBackground(on ? ui.shape(ACCENT_SOFT, 14) : null);
                row.setText((on ? "✓  " : "") + label + (label.equals(id) ? "" : "\n" + id));
                row.setOnClickListener(v -> { engine[0] = by; chosen[0] = id; fill[0].run(); }); Ui.pressable(row, false); sheet.body.addView(row); sheet.body.addView(divider()); found++;
            }
            if (found == 0) sheet.body.addView(ui.label("일치하는 모델이 없어요. 모델 연결 설정에서 목록을 새로고침할 수 있어요.", 14, MUTED));
            sheet.primary.setEnabled(!chosen[0].isBlank()); sheet.primary.setText(chosen[0].isBlank() ? "모델을 골라 주세요" : labels.apply(engine[0], chosen[0]) + " 선택");
        };
        sheet.primary.setOnClickListener(v -> { if (!chosen[0].isBlank() && store.ready(engine[0])) { selected.accept(engine[0], chosen[0]); sheet.dismiss(); } });
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) {} public void onTextChanged(CharSequence s, int a, int b, int c) { fill[0].run(); } public void afterTextChanged(Editable e) {} });
        fill[0].run(); sheet.show();
    }
    String summary(JSONArray agents) {
        int running = 0, queued = 0, completed = 0;
        for (int i = 0; agents != null && i < agents.length(); i++) { String state = agents.optJSONObject(i).optString("state"); if (state.equals("running")) running++; else if (state.equals("queued")) queued++; else if (state.equals("completed")) completed++; }
        return running > 0 || queued > 0 ? "팀 작업 · " + running + "개 진행 · " + queued + "개 대기" : "팀 작업 · " + completed + "/" + (agents == null ? 0 : agents.length()) + "개 완료";
    }
    View reports(JSONArray agents, JSONArray switches) {
        LinearLayout group = panel();
        if (agents != null && agents.length() > 0) {
            TextView title = ui.text(summary(agents), 14, 650, ACCENT); title.setPadding(0, ui.dp(12), 0, ui.dp(12)); group.addView(title);
            for (int i = 0; i < agents.length(); i++) {
                JSONObject task = agents.optJSONObject(i); if (task == null) continue;
                group.addView(divider()); LinearLayout row = ui.column(); row.setMinimumHeight(ui.dp(64)); row.setPadding(0, ui.dp(12), 0, ui.dp(12));
                row.addView(ui.text(task.optString("title"), 15, 600, TEXT));
                String state = state(task.optString("state")); row.addView(ui.text(labels.apply(task.optString("engine"), task.optString("model")) + " · " + state, 13, 450, task.optString("error").isBlank() ? MUTED : ERROR));
                row.setFocusable(true); row.setContentDescription(task.optString("title") + ", " + state + ", 결과 자세히 보기"); row.setOnClickListener(v -> details(task)); Ui.pressable(row, false); group.addView(row);
            }
        }
        if (switches != null) for (int i = 0; i < switches.length(); i++) {
            JSONObject event = switches.optJSONObject(i); if (event == null) continue; String next = event.optString("to");
            TextView note = ui.text(event.optString("from") + (next.isBlank() ? " · 사용 한도 도달" : " → " + next + "로 전환"), 13, 550, ACCENT); note.setPadding(0, ui.dp(10), 0, ui.dp(10)); group.addView(note);
        }
        return group;
    }
    private static String state(String value) { return switch (value) { case "queued" -> "대기"; case "running" -> "진행 중"; case "completed" -> "완료"; case "blocked" -> "선행 작업 확인 필요"; case "cancelled" -> "중지됨"; case "timed_out" -> "시간 초과"; default -> "오류"; }; }
    private void details(JSONObject task) {
        Ui.Sheet sheet = ui.selectionSheet(task.optString("title"), labels.apply(task.optString("engine"), task.optString("model")) + " · " + state(task.optString("state")));
        String result = task.optString("result"), error = task.optString("error");
        TextView text = ui.text(!error.isBlank() ? error + (result.isBlank() ? "" : "\n\n미완성 부분 결과\n" + result) : result.isBlank() ? "아직 결과가 없어요." : result, 15, 400, TEXT); text.setTextIsSelectable(true); text.setLineSpacing(ui.dp(4), 1); sheet.body.addView(text);
        JSONArray events = task.optJSONArray("switches"); if (events != null && events.length() > 0) sheet.body.addView(reports(null, events));
        sheet.primary.setText("닫기"); sheet.show();
    }
}

