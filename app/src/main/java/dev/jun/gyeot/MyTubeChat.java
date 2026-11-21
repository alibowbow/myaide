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
    final LinearLayout card, composer; private final LinearLayout lines; private final EditText input; private final ImageView send; private final Button model; private final TextView more;
    final VoiceBar voice;
    private boolean expanded, thinking;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();

    MyTubeChat(Activity activity, Ui ui, AppStore store, MyTubeBook book, ExecutorService work, android.os.Handler main, Actions actions) {
        this.activity = activity; this.ui = ui; this.store = store; this.book = book; this.work = work; this.main = main; this.actions = actions;
        card = ui.card();
        LinearLayout head = ui.row(); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text("마이튜브 AI", 15, 650, TEXT); head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        model = ui.chip("모델", this::chooseModel); head.addView(model, new LinearLayout.LayoutParams(-2, -2));
        card.addView(head);
        lines = ui.column(); lines.setPadding(0, ui.dp(6), 0, 0); card.addView(lines);
        more = ui.ghost("지난 대화 보기", () -> { expanded = !expanded; render(); });
        card.addView(more, new LinearLayout.LayoutParams(-2, -2));
        // The composer: words to send, or (with nothing written) the voice button; a long press chooses the voice.
        composer = ui.row(); composer.setGravity(Gravity.CENTER_VERTICAL); composer.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8));
        input = ui.input("마이튜브에게 말하기 · 예: 아이유 노래 틀어 줘", 1, 1000);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND); input.setSingleLine(false); input.setMaxLines(4);
        input.setOnEditorActionListener((v, id, event) -> { if (id == EditorInfo.IME_ACTION_SEND) { submit(); return true; } return false; });
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        voice = new VoiceBar(activity, store, ui, "mytube", () -> VoiceTargets.mytube(activity.getApplicationContext()));
        send = ui.iconButton(R.drawable.ic_send, ACCENT, ON_ACCENT, "메시지 보내기", () -> { if (offersVoice()) voice.start(); else submit(); });
        send.setOnLongClickListener(v -> { voice.settings(""); return true; });
        LinearLayout.LayoutParams sendAt = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)); sendAt.leftMargin = ui.dp(8);
        composer.addView(send, sendAt);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { looks(); }
        });
        voice.aside(composer);
        looks(); render();
    }
    private boolean offersVoice() { return input.getText().toString().isBlank() && !thinking; }
    private void looks() { VoiceBar.glyph(send, offersVoice(), "메시지 보내기"); }

    /** The newest lines (all of them once 지난 대화 보기 is open), and the model it talks with. */
    void render() {
        List<MyTubeBook.Line> all = book.chat();
        lines.removeAllViews();
        int first = expanded ? 0 : Math.max(0, all.size() - SHOWN);
        if (all.isEmpty()) {
            TextView hint = ui.label("무엇을 볼지 말해 보세요. 영상을 찾아 틀고, 피드를 만들고, 멈추거나 넘기는 것도 해요.\n예: 오늘 AI 뉴스 피드 만들어 줘 · 잔잔한 피아노 곡 이어서 틀어 줘", 13, MUTED);
            hint.setPadding(0, ui.dp(4), 0, ui.dp(2)); lines.addView(hint);
        }
        for (int i = first; i < all.size(); i++) lines.addView(line(all.get(i)));
        if (thinking) { TextView wait = ui.label("··· 생각하는 중", 13, MUTED); wait.setPadding(ui.dp(4), ui.dp(4), 0, 0); lines.addView(wait); }
        more.setVisibility(all.size() > SHOWN ? View.VISIBLE : View.GONE);
        more.setText(expanded ? "최근 대화만 보기" : "지난 대화 보기 (" + (all.size() - SHOWN) + ")");
        String named = MyTubeClient.myTubeModel(store);
        model.setText(named.isEmpty() ? "모델 고르기" : named);
        model.setContentDescription("마이튜브 AI 모델, " + (named.isEmpty() ? "연결 필요" : named) + ", 눌러서 바꾸기");
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
        boolean replay = l.spoken() && VoiceClips.has(clip);
        ImageView play = replay ? ui.footIcon(VoiceClips.playing(clip) ? R.drawable.ic_stop : R.drawable.ic_play, mine ? "내 목소리 다시 듣기" : "다시 듣기 · 말한 목소리 그대로", () -> VoiceClips.toggle(activity, store, clip, l.text())) : null;
        LinearLayout.LayoutParams bubbleAt = new LinearLayout.LayoutParams(-2, -2, 1), playAt = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        if (mine) { if (play != null) row.addView(play, playAt); row.addView(bubble, bubbleAt); }
        else { row.addView(bubble, bubbleAt); if (play != null) row.addView(play, playAt); }
        LinearLayout wrap = ui.column(); wrap.setGravity(mine ? Gravity.END : Gravity.START); wrap.addView(row, new LinearLayout.LayoutParams(-2, -2));
        return wrap;
    }

    private void chooseModel() {
        FileExplorerModelUi.show(activity, store, () -> ScopedModels.mytube(store), "마이튜브", "마이튜브에 적용", () -> !activity.isDestroyed(),
            dialog -> dialog.show(), this::render);
    }

    private void submit() {
        String text = input.getText().toString().strip(); if (text.isEmpty() || thinking) return;
        input.setText("");
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        ask(text);
    }
    /** The user's words to 마이튜브 AI: written, answered, and what it says done. */
    void ask(String text) {
        try { book.say("user", text, false); } catch (Exception unsaved) { /* still asked */ }
        thinking = true; render();
        List<String> said = conversation(); if (!said.isEmpty()) said.remove(said.size() - 1);
        String asked = actions.input(text, said);
        work.execute(() -> {
            MyTube.Plan plan; String failure = null;
            try { plan = MyTube.plan(MyTubeClient.askMyTube(store, MyTube.AGENT, asked, cancel)); }
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
    void clear() { try { book.clearChat(); } catch (Exception ignored) {} render(); }
    void attach() { voice.attach(); render(); }
    void detach() { voice.detach(); }
    boolean permissionResult(int code, int[] results) { return voice.permissionResult(code, results); }
}
