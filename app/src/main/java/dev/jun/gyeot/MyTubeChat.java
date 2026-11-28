package dev.jun.gyeot;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.MyTube;
import dev.jun.gyeot.core.MyTubeBook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * 마이튜브 AI: 마이튜브's own conversation, as the other screens have theirs. Typed, the AI (마이튜브's own model choice) answers
 * with what to do (core/MyTube's AGENT: search, play, play a feed, make one, put the feeds in order, control what plays, save,
 * capture, sum up, quality) and the screen does it in order, saying what it did; spoken (음성 대화, VoiceTargets.mytube), the same
 * actions are its functions. The conversation is kept on this phone (MyTubeBook's chat.json).
 */
final class MyTubeChat {
    /** The screen's side: does one action and tells what it did ("" for nothing to tell), on the main thread. */
    interface Actions {
        void act(JSONObject action, Consumer<String> done);
        /** What the AI is told: the screen now (feeds, what plays, the videos shown), the conversation, and the words asked. */
        String input(String asked, List<String> conversation);
    }
    static final int SHOWN = 6;
    private final Activity activity; private final Ui ui; private final AppStore store; private final MyTubeBook book; private final Actions actions;
    private final ExecutorService work; private final android.os.Handler main;
    final LinearLayout card, composer;
    /** The conversation over the composer: at most about a third of the screen, scrolled to its newest line. */
    final android.widget.ScrollView panel;
    private boolean folded, away;
    /** Folded until the next line: the number of lines when it was folded (kept, so the app opening again keeps it folded). */
    private void fold(boolean on) { folded = on; store.settings.edit().putInt("mytubeChatFoldedAt", on ? book.chat().size() : -1).apply(); render(); }
    private final LinearLayout lines; private final EditText input; private final ImageView send; private final TextView model, more;
    final VoiceBar voice;
    private boolean expanded, thinking;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();

    MyTubeChat(Activity activity, Ui ui, AppStore store, MyTubeBook book, ExecutorService work, android.os.Handler main, Actions actions) {
        this.activity = activity; this.ui = ui; this.store = store; this.book = book; this.work = work; this.main = main; this.actions = actions;
        int foldedAt = store.settings.getInt("mytubeChatFoldedAt", -1); folded = foldedAt >= 0 && foldedAt == book.chat().size();
        // The conversation, only once there is one (no title, no hint): bubbles, as the other chats show them.
        card = ui.card();
        // At the card's top right, two quiet icons: the eraser clears the whole conversation (a reply on its way stops first),
        // the chevron folds it away until the next thing said (kept folded when the app opens again).
        LinearLayout head = ui.row(); head.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        ImageView wipe = ui.quietIcon(R.drawable.ic_eraser, "대화 모두 지우기", () -> ui.confirm("마이튜브와 나눈 대화를 모두 지울까요?", "모두 지우기", () -> {
            Runnable stop = cancel.getAndSet(null); if (stop != null) try { stop.run(); } catch (RuntimeException ignored) {}
            thinking = false; expanded = false; clear();
        }).show());
        wipe.setTooltipText("모두 지우기");
        wipe.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); head.addView(wipe, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        ImageView fold = ui.quietIcon(R.drawable.ic_chevron_down, "대화 접기", () -> fold(true));
        fold.setTooltipText("접기");
        fold.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); head.addView(fold, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        lines = ui.column(); card.addView(lines);
        more = ui.ghost("지난 대화 보기", () -> { expanded = !expanded; render(); });
        card.addView(more, new LinearLayout.LayoutParams(-2, -2));
        panel = new android.widget.ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int most = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.32f);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST));
            }
        };
        panel.setVerticalScrollBarEnabled(false); panel.addView(card);
        // The composer, compact as 작업창's: one rounded card with the words on top, and under them the model (name, thinking
        // level quiet beside it; its menu is ModelPopover), 음성 입력 (into the field) and the round button (the voice with
        // nothing written, a long press choosing the voice; send otherwise).
        composer = ui.column(); composer.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8));
        LinearLayout box = ui.column(); box.setBackground(ui.glass(28, false)); ui.lift(box, 4); box.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4));
        composer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        input = new EditText(activity); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setHint("마이튜브에게 말하기"); input.setMaxLines(4);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        input.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(2));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, event) -> { if (id == EditorInfo.IME_ACTION_SEND) { submit(); return true; } return false; });
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL);
        model = ModelLine.make(activity, ui, v -> chooseModel()); model.setMinHeight(ui.dp(44));
        LinearLayout.LayoutParams modelAt = new LinearLayout.LayoutParams(0, -2, 1); bar.addView(model, modelAt);
        bar.addView(ui.bareIcon(R.drawable.ic_mic, TEXT, "음성 입력 · 입력창에만 넣기", this::dictate), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        voice = new VoiceBar(activity, store, ui, "mytube", () -> VoiceTargets.mytube(activity.getApplicationContext()));
        send = ui.sendButton("메시지 보내기", () -> { if (offersVoice()) voice.start(); else submit(); });
        send.setOnLongClickListener(v -> { if (!offersVoice()) return false; voice.settings(""); return true; });
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { looks(); }
        });
        voice.aside(composer);
        looks(); render();
    }
    private boolean offersVoice() { return input.getText().toString().isBlank() && !thinking; }
    private ComposerDictation dictation;
    /** 음성 입력: what is said goes into the field (Gemini's transcription, as 작업창's), to read and send. */
    private void dictate() {
        if (store.voiceActive || store.recording || store.voiceChat != null) { android.widget.Toast.makeText(activity, "음성 대화나 녹음을 끝낸 뒤 음성으로 입력해 주세요.", android.widget.Toast.LENGTH_LONG).show(); return; }
        if (!store.secrets.hasWorkKey()) { android.widget.Toast.makeText(activity, "음성 입력에 쓸 Gemini API 키가 없어요. 설정에서 Gemini를 연결해 주세요.", android.widget.Toast.LENGTH_LONG).show(); return; }
        store.stopReading(null);
        if (dictation == null) dictation = new ComposerDictation(activity, input, () -> "mytube", message -> android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show());
        dictation.begin(() -> new GeminiComposerSession(store.secrets.workKey()), "Gemini 3.5 Transcribe Live");
    }
    private void looks() { VoiceBar.glyph(send, offersVoice(), "메시지 보내기"); }

    /** The newest lines (all of them once 지난 대화 보기 is open), and the model it talks with. */
    void render() {
        List<MyTubeBook.Line> all = book.chat();
        lines.removeAllViews();
        int first = expanded ? 0 : Math.max(0, all.size() - SHOWN);
        boolean none = all.isEmpty() && !thinking;
        card.setVisibility(none ? View.GONE : View.VISIBLE);
        panel.setVisibility(none || folded || away ? View.GONE : View.VISIBLE);
        panel.post(() -> panel.scrollTo(0, Math.max(0, card.getHeight() - panel.getHeight())));
        for (int i = first; i < all.size(); i++) lines.addView(line(all.get(i)));
        if (thinking) {
            // 생각하는 중 with a soft light sweeping across it, as in the other chats.
            ShimmerText wait = new ShimmerText(activity, MUTED, TEXT); wait.setText("생각하는 중"); wait.setTextSize(13); wait.setTypeface(Ui.weight(500));
            wait.setPadding(ui.dp(4), ui.dp(4), 0, 0); lines.addView(wait);
        }
        more.setVisibility(all.size() > SHOWN ? View.VISIBLE : View.GONE);
        more.setText(expanded ? "최근 대화만 보기" : "지난 대화 보기 (" + (all.size() - SHOWN) + ")");
        // The model as 작업창 shows it: its name in bold, its thinking level quiet beside it.
        String[] parts = modelParts();
        ModelLine.show(model, parts, "마이튜브 AI");
        looks();
    }
    private View line(MyTubeBook.Line l) {
        if ("note".equals(l.role())) {
            TextView note = ui.label("· " + l.text(), 12, MUTED); note.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)); return note;
        }
        boolean mine = "user".equals(l.role());
        LinearLayout row = ui.row(); row.setGravity(mine ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(mine ? 32 : 0), ui.dp(3), ui.dp(mine ? 0 : 32), ui.dp(3));
        TextView bubble = ui.text(l.text(), 14, mine ? 500 : 420, mine ? ON_ACCENT : TEXT); bubble.setLineSpacing(ui.dp(2), 1);
        bubble.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(8)); bubble.setTextIsSelectable(true);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(mine ? ACCENT : FILL);
        float round = ui.dp(16), tail = ui.dp(5); shape.setCornerRadii(mine ? new float[]{round, round, round, round, tail, tail, round, round} : new float[]{tail, tail, round, round, round, round, round, round});
        bubble.setBackground(shape);
        bubble.setContentDescription((mine ? "나" : "마이튜브 AI") + (l.spoken() ? " (음성)" : "") + ": " + l.text());
        // Spoken: 다시 듣기 in the voice it was said in (VoiceClips), when it was kept.
        String clip = VoiceChat.clipKey("mytube", l.id());
        boolean replay = !mine && l.spoken() && VoiceClips.has(clip); // the AI's words only
        ImageView play = replay ? ui.footIcon(VoiceClips.playing(clip) ? R.drawable.ic_stop : R.drawable.ic_play, mine ? "내 목소리 다시 듣기" : "다시 듣기 · 말한 목소리 그대로", () -> VoiceClips.toggle(activity, store, clip, l.text())) : null;
        if (play != null) play.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9)); // an 18dp mark in a 36dp target
        LinearLayout.LayoutParams bubbleAt = new LinearLayout.LayoutParams(-2, -2, 1), playAt = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36));
        if (mine) { if (play != null) row.addView(play, playAt); row.addView(bubble, bubbleAt); }
        else { row.addView(bubble, bubbleAt); if (play != null) row.addView(play, playAt); }
        LinearLayout wrap = ui.column(); wrap.setGravity(mine ? Gravity.END : Gravity.START); wrap.addView(row, new LinearLayout.LayoutParams(-2, -2));
        return wrap;
    }

    // ── the model, as the other chats choose it ──────────────────────────
    private ScopedModels models() { return ScopedModels.mytube(store); }
    /** {name, thinking level} for the composer; "AI 연결·선택" when none is connected. */
    private String[] modelParts() {
        try {
            ScopedModels selection = models(); String engine = selection.engine();
            if (engine == null || engine.isBlank() || !store.ready(engine)) return new String[]{"AI 연결·선택", ""};
            String id = selection.model(engine); if (id.isBlank()) return new String[]{dev.jun.gyeot.core.WorkBook.author(engine) + " · 모델 고르기", ""};
            TeamRoster roster = new TeamRoster(store, selection);
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, id); String effort = selection.effort(engine, id);
            String level = profile.efforts.isEmpty() ? "" : effortWord(engine, effort.isEmpty() ? profile.defaultEffort : effort);
            return new String[]{roster.label(engine, id), level};
        } catch (Exception unavailable) { return new String[]{"AI 연결·선택", ""}; }
    }
    static String effortWord(String engine, String effort) {
        return dev.jun.gyeot.core.WorkBook.CHATGPT.equals(engine) ? dev.jun.gyeot.core.Reasoning.gptShortLabel(effort)
            : dev.jun.gyeot.core.WorkBook.CLAUDE.equals(engine) ? dev.jun.gyeot.core.Reasoning.claudeLabel(effort) : MainActivity.effortName(effort);
    }
    /** The menu over the model (ModelPopover): its models, 생각 깊이, 다른 AI and 모든 모델·설정; with none connected, the AI to connect. */
    private void chooseModel() {
        ScopedModels selection; try { selection = models(); } catch (Exception e) { store.error(e.getMessage()); return; }
        String engine = selection.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) { allModels(); return; }
        TeamRoster roster = new TeamRoster(store, selection);
        new ModelPopover(activity, ui, store, selection, new ModelPopover.Catalog() {
            @Override public org.json.JSONArray models(String e) { return roster.catalog(e); }
            @Override public String label(String e, String m) { return roster.label(e, m); }
            @Override public dev.jun.gyeot.core.Reasoning.Profile profile(String e, String m) { return new TeamRoster(store).profile(e, m); }
            @Override public String effortLabel(String e, String effort) { return effortWord(e, effort); }
            @Override public String effortHint(String effort, String fallback) { return MainActivity.effortHint(effort, fallback); }
            @Override public String team() { return ""; }
        }, e -> {
            try { selection.chooseEngine(e); } catch (Exception failed) { store.error(failed.getMessage()); return; }
            if (!store.ready(e)) android.widget.Toast.makeText(activity, dev.jun.gyeot.core.WorkBook.author(e) + "를 설정에서 먼저 연결해 주세요.", android.widget.Toast.LENGTH_LONG).show();
            render();
        }, this::allModels, null, this::render).show(model);
    }
    /** 모든 모델·설정: the AI, its model and thinking level in full (as 탐색's). */
    private void allModels() {
        FileExplorerModelUi.show(activity, store, this::models, "마이튜브", "마이튜브에 적용", () -> !activity.isDestroyed(), dialog -> dialog.show(), this::render);
    }

    private void submit() {
        String text = input.getText().toString().strip(); if (text.isEmpty() || thinking) return;
        input.setText("");
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        ask(text);
    }
    /** The user's words to 마이튜브 AI: written, answered, and what it says done. */
    /** While the video is full screen, the search box is in use or a video is asked about, the conversation steps aside too. */
    void away(boolean value) { if (away == value) return; away = value; render(); }
    void ask(String text) {
        if (folded) fold(false);
        try { book.say("user", text, false); } catch (Exception unsaved) { /* still asked */ }
        thinking = true; render();
        List<String> said = conversation(); if (!said.isEmpty()) said.remove(said.size() - 1);
        String asked = actions.input(text, said);
        work.execute(() -> {
            MyTube.Plan plan; String failure = null;
            try { plan = MyTube.plan(MyTubeClient.askMyTube(store, MyTube.AGENT, asked, cancel)); plan = feedWish(text, plan); }
            catch (Exception e) { plan = null; failure = MyTubeActivity.friendly(e, "답하지 못했어요."); }
            MyTube.Plan done = plan; String why = failure;
            main.post(() -> {
                thinking = false;
                if (activity.isDestroyed()) return;
                if (done == null) { note(why); render(); return; }
                if (!done.reply().isEmpty()) try { book.say("assistant", done.reply(), false); } catch (Exception ignored) {}
                render();
                run(done.actions(), 0);
            });
        });
    }
    /**
     * A wish about a named feed (빼 줘, 포함 안 되게, …만 나오게) that the answer did not act on (no feed action, or it told the
     * user to edit it themselves): the AI is asked once more for that one feed's new description, and the app changes it.
     * Blocking (the answer's thread).
     */
    private MyTube.Plan feedWish(String text, MyTube.Plan plan) {
        for (JSONObject a : plan.actions()) if (a.optString("do").startsWith("feed_")) return plan;
        if (!MyTube.wantsFeedChange(text)) return plan;
        MyTube.Feed feed = MyTube.feedMentioned(book.feeds(), text); if (feed == null) return plan;
        try {
            JSONObject fix = MyTube.feedFix(feed, MyTubeClient.askMyTube(store, MyTube.FEED_FIX, MyTube.feedFixInput(feed, text), cancel));
            if (fix == null) return plan;
            String line = fix.optString("_reply"); fix.remove("_reply");
            java.util.List<JSONObject> actions = new java.util.ArrayList<>(plan.actions()); actions.add(0, fix);
            String reply = plan.reply().isEmpty() || MyTube.toldToDoIt(plan.reply()) ? (line.isEmpty() ? "‘" + feed.name() + "’ 피드를 바꿨어요." : line) : plan.reply();
            return new MyTube.Plan(reply, actions);
        } catch (Exception keep) { return plan; }
    }
    /** Does the actions one after another, each one's outcome written as a note. */
    void run(List<JSONObject> list, int at) {
        if (at >= list.size() || activity.isDestroyed()) { render(); return; }
        actions.act(list.get(at), outcome -> { if (outcome != null && !outcome.isBlank()) note(outcome); render(); run(list, at + 1); });
    }
    private void note(String text) { try { book.say("note", text, false); } catch (Exception ignored) {} }
    /** The conversation so far as the AI reads it. */
    List<String> conversation() {
        List<String> out = new ArrayList<>();
        for (MyTubeBook.Line l : book.chat()) out.add(("user".equals(l.role()) ? "사용자: " : "assistant".equals(l.role()) ? "AI: " : "(한 일) ") + l.text());
        return out;
    }
    void clear() { try { book.clearChat(); } catch (Exception ignored) {} folded = false; store.settings.edit().remove("mytubeChatFoldedAt").apply(); render(); }
    void attach() { voice.attach(); render(); }
    void detach() { voice.detach(); if (dictation != null) dictation.pause(); }
    boolean permissionResult(int code, int[] results) { return voice.permissionResult(code, results); }
}
