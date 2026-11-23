package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.SchoolBook;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * 나의학교: the teachers the user brings in (SchoolBook), listed on the left, and a 1:1 lesson with the one open on the
 * right (on a phone the list opens the lesson full screen). A teacher answers on Groq's free GPT OSS 120B, or on the app's
 * AI the teacher was given (its own ScopedModels); 판서 turns the lesson's topic into a board lesson (BoardActivity's ASK).
 * A teacher's picture can be drawn by GPT image, as the assistant's profile is.
 */
public final class SchoolActivity extends Activity {
    static final String OPEN = "gyeot.school_teacher";
    private Ui ui; private AppStore store; private SchoolBook book; private MainNavigation navigation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-school"); t.setDaemon(true); return t; });
    private FrameLayout root;
    private String openId; private boolean wide, destroyed;
    // The lesson's views (rebuilt with draw()).
    private LinearLayout lines; private ScrollView scroll; private EditText input; private ImageView send; private TextView model;
    private volatile String thinkingFor; private String failure;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();
    private volatile int ticket;
    private LinearLayout listColumn;
    /** On a wide screen the list can fold into a slim column of pictures (kept across visits). */
    private boolean folded;
    private ComposerDictation dictation;
    private final Map<String, Drawable> faces = new HashMap<>();

    /** The menu and rail look the same on every screen: opening and leaving this one swaps only the content, with no slide or dim. */
    private void noTransition() { if (android.os.Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0); } else overridePendingTransition(0, 0); }
    @Override public void finish() { super.finish(); noTransition(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); noTransition();
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); book = new SchoolBook(new File(getFilesDir(), "school"));
        openId = state != null ? state.getString("open") : getIntent().getStringExtra(OPEN);
        if (openId == null && state == null) { String last = store.settings.getString("schoolOpen", ""); if (!last.isEmpty() && wideScreen()) openId = last; }
        folded = store.settings.getBoolean("schoolListFolded", false);
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, 0, 0, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)); return insets;
        });
        navigation = new MainNavigation(this, root, "board", target -> MainNavigation.open(this, target));
        setContentView(navigation);
        draw(); root.requestApplyInsets();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String asked = intent.getStringExtra(OPEN); if (asked != null) { openId = asked; draw(); }
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("open", openId); }
    @Override protected void onResume() { super.onResume(); AppNavigator.inFront("school"); }
    @Override protected void onPause() { if (dictation != null) dictation.pause(); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; stop(); work.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (navigation != null && navigation.close()) return;
        if (!wide && openId != null) { openId = null; draw(); return; }
        super.onBackPressed();
    }
    private boolean wideScreen() { return getResources().getConfiguration().screenWidthDp >= 600; }

    // ── the layout ───────────────────────────────────────────────────────

    /** A teacher kept on the retired "Antigravity · 구독" choice: now the app's AIs with Antigravity chosen, as every screen offers it. */
    private void migrateAgy(List<SchoolBook.Teacher> teachers) {
        for (SchoolBook.Teacher t : teachers) if ("agy".equals(t.ai()) || "groq".equals(t.ai())) {
            // Groq and Antigravity are AIs of the model menu now: the teacher keeps the one it had, chosen there.
            String engine = "agy".equals(t.ai()) ? dev.jun.gyeot.core.WorkBook.ANTIGRAVITY : dev.jun.gyeot.core.WorkBook.GROQ;
            try { if (!"groq".equals(t.ai()) || store.groq.connected()) ScopedModels.tutor(store, t.id()).chooseEngine(engine); book.save(t.ai("app")); } catch (Exception ignored) {}
        }
    }
    /** The whole screen again: both panes on a wide screen, the list or the lesson on a phone. */
    private void draw() {
        wide = wideScreen();
        List<SchoolBook.Teacher> teachers = book.teachers(); migrateAgy(teachers); teachers = book.teachers();
        SchoolBook.Teacher open = openId == null ? null : book.teacher(openId);
        if (open == null) openId = null;
        if (open == null && wide && !teachers.isEmpty()) { open = teachers.get(0); openId = open.id(); }
        store.settings.edit().putString("schoolOpen", openId == null ? "" : openId).apply();
        if (dictation != null) dictation.pause();
        root.removeAllViews(); lines = null; model = null; input = null; send = null; listColumn = null;
        if (wide) {
            LinearLayout panes = ui.row(); panes.setGravity(Gravity.TOP);
            panes.addView(listPane(teachers), new LinearLayout.LayoutParams(ui.dp(folded ? 64 : 236), -1));
            View rule = new View(this); rule.setBackgroundColor(SEPARATOR); panes.addView(rule, new LinearLayout.LayoutParams(Math.max(1, ui.dp(.5f)), -1));
            panes.addView(open == null ? emptyLesson(teachers.isEmpty()) : lessonPane(open, true), new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new FrameLayout.LayoutParams(-1, -1));
            navigation.bar(null);
        } else if (open == null) {
            root.addView(listPane(teachers), new FrameLayout.LayoutParams(-1, -1));
            navigation.bar(null);
        } else {
            View lesson = lessonPane(open, false);
            root.addView(lesson, new FrameLayout.LayoutParams(-1, -1));
        }
    }
    private void open(String id) {
        if (id.equals(openId) && wide) return;
        if (dictation != null) dictation.pause();
        openId = id; failure = null; draw();
    }

    /** The teachers: each with their picture, subject and the lesson's last words; + 선생님 모시기 at the top. */
    private View listPane(List<SchoolBook.Teacher> teachers) {
        ScrollView list = new ScrollView(this); list.setFillViewport(true);
        LinearLayout column = ui.column(); list.addView(column);
        if (wide && folded) { column.setGravity(Gravity.CENTER_HORIZONTAL); column.setPadding(0, ui.dp(4), 0, ui.dp(24)); }
        else column.setPadding(ui.dp(wide ? 8 : 12), ui.dp(4), ui.dp(wide ? 8 : 12), ui.dp(24));
        listColumn = column; fillList(teachers);
        return list;
    }
    private void fillList(List<SchoolBook.Teacher> teachers) {
        LinearLayout column = listColumn; if (column == null) return;
        column.removeAllViews();
        if (wide && folded) { foldedList(column, teachers); return; }
        LinearLayout top = ui.row();
        if (wide) top.addView(ui.bareIcon(R.drawable.ic_back, MUTED, "선생님 목록 접기", () -> fold(true)), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView about = ui.label(teachers.isEmpty() ? "선생님을 모셔서 1:1로 배워요" : "선생님 " + teachers.size() + "명", 13, MUTED);
        about.setPadding(ui.dp(4), 0, 0, 0);
        top.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.pill(wide ? "모시기" : "선생님 모시기", R.drawable.ic_add, true, () -> edit(null)), new LinearLayout.LayoutParams(-2, -2));
        ui.add(column, top, 8);
        if (teachers.isEmpty()) {
            TextView empty = ui.label("아직 모신 선생님이 없어요.\n배우고 싶은 과목의 선생님을 직접 만들어 보세요. 이름, 과목, 말투와 가르치는 방식, 내 수준을 정하면 그 선생님이 1:1로 가르쳐 줘요.", 14, MUTED);
            empty.setLineSpacing(ui.dp(3), 1); empty.setPadding(ui.dp(8), ui.dp(24), ui.dp(8), ui.dp(12));
            column.addView(empty);
            TextView ideas = ui.text("예시 · 친절한 수학 선생님(중2) · 깐깐한 영어 회화 코치 · 재밌는 한국사 이야기꾼 · 코딩 멘토", 12.5f, 450, MUTED);
            ideas.setPadding(ui.dp(8), 0, ui.dp(8), 0); column.addView(ideas);
            return;
        }
        for (SchoolBook.Teacher t : teachers) column.addView(teacherRow(t), new LinearLayout.LayoutParams(-1, -2));
    }
    /** The folded list: 펼치기, 모시기, then each teacher's picture alone (the open one ringed). */
    private void foldedList(LinearLayout column, List<SchoolBook.Teacher> teachers) {
        column.addView(ui.bareIcon(R.drawable.ic_chevron, MUTED, "선생님 목록 펼치기", () -> fold(false)), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageView add = ui.iconButton(R.drawable.ic_add, ACCENT_SOFT, ACCENT, "선생님 모시기", () -> edit(null));
        LinearLayout.LayoutParams addAt = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)); addAt.topMargin = ui.dp(4); addAt.bottomMargin = ui.dp(8);
        column.addView(add, addAt);
        for (SchoolBook.Teacher t : teachers) {
            boolean on = t.id().equals(openId);
            FrameLayout cell = new FrameLayout(this); cell.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            if (on) { GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke(ui.dp(2), ACCENT); cell.setBackground(ring); }
            View picture = face(t, 40);
            // Here a tap opens the lesson (the picture's own tap would enlarge it); a long press, the teacher's menu.
            picture.setOnClickListener(v -> open(t.id())); picture.setOnLongClickListener(v -> { teacherMenu(v, t); return true; });
            picture.setContentDescription(t.name() + " 선생님" + (on ? ", 열려 있음" : "")); picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            cell.addView(picture, new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40)));
            cell.setTooltipText(t.name() + (t.subject().isBlank() ? "" : " · " + t.subject()));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)); at.topMargin = ui.dp(4); at.bottomMargin = ui.dp(4);
            column.addView(cell, at);
        }
    }
    private void fold(boolean value) {
        folded = value; store.settings.edit().putBoolean("schoolListFolded", value).apply();
        String draft = input == null ? "" : input.getText().toString();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) android.transition.TransitionManager.beginDelayedTransition(root);
        draw();
        if (input != null && !draft.isEmpty()) { input.setText(draft); input.setSelection(draft.length()); }
    }
    private View teacherRow(SchoolBook.Teacher t) {
        boolean on = wide && t.id().equals(openId);
        int size = wide ? 40 : 46;
        LinearLayout row = ui.row(); row.setPadding(ui.dp(wide ? 8 : 10), ui.dp(8), ui.dp(wide ? 8 : 10), ui.dp(8)); row.setMinimumHeight(ui.dp(wide ? 58 : 64));
        if (on) row.setBackground(ui.shape(FILL, R_CONTROL));
        row.addView(face(t, size), new LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)));
        LinearLayout words = ui.column(); words.setPadding(ui.dp(wide ? 10 : 12), 0, 0, 0);
        TextView name = ui.text(t.name(), 15.5f, 650, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        List<SchoolBook.Line> said = book.lines(t.id());
        String last = said.isEmpty() ? "" : said.get(said.size() - 1).text().replaceAll("\\s+", " ");
        String detail = t.subject().isBlank() ? last : last.isEmpty() ? t.subject() : t.subject() + " · " + last;
        TextView sub = ui.text(detail.isEmpty() ? "아직 수업 전이에요" : detail, 13, 450, MUTED); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(t.name() + " 선생님" + (t.subject().isBlank() ? "" : ", " + t.subject()) + (on ? ", 열려 있음" : ""));
        row.setOnClickListener(v -> open(t.id()));
        row.setOnLongClickListener(v -> { teacherMenu(v, t); return true; });
        Ui.pressable(row, true);
        return row;
    }
    private View emptyLesson(boolean none) {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER); box.setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32));
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_book); mark.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));
        box.addView(mark, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView words = ui.label(none ? "왼쪽에서 ‘선생님 모시기’를 눌러 첫 선생님을 만들어 보세요." : "왼쪽에서 선생님을 골라 주세요.", 15, MUTED);
        words.setGravity(Gravity.CENTER); words.setPadding(0, ui.dp(12), 0, 0); box.addView(words);
        return box;
    }

    /** One lesson: its header (picture, name, 판서, the menu), the conversation, and the composer. */
    private View lessonPane(SchoolBook.Teacher t, boolean inPane) {
        LinearLayout pane = ui.column();
        LinearLayout header = ui.row(); header.setGravity(Gravity.CENTER_VERTICAL);
        if (!inPane) header.addView(ui.bareIcon(R.drawable.ic_back, TEXT, "선생님 목록으로", () -> { openId = null; draw(); }), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        else header.setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(6));
        header.addView(face(t, 34), new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout who = ui.column(); who.setPadding(ui.dp(10), 0, ui.dp(6), 0);
        TextView name = ui.text(t.name(), 16, 680, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); who.addView(name);
        if (!t.subject().isBlank()) { TextView subject = ui.text(t.subject(), 12, 500, MUTED); subject.setSingleLine(true); subject.setEllipsize(TextUtils.TruncateAt.END); who.addView(subject); }
        who.setOnClickListener(v -> edit(t)); Ui.pressable(who, true);
        header.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(ui.pill("판서", R.drawable.ic_board, false, () -> board(t)), new LinearLayout.LayoutParams(-2, -2));
        ImageView more = ui.bareIcon(R.drawable.ic_more, TEXT, "선생님 메뉴", () -> {});
        more.setOnClickListener(v -> teacherMenu(v, t));
        header.addView(more, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        if (inPane) {
            pane.addView(header, new LinearLayout.LayoutParams(-1, -2));
            View rule = new View(this); rule.setBackgroundColor(SEPARATOR); pane.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f))));
        } else navigation.bar(header);

        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        lines = ui.column(); lines.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12));
        scroll.addView(lines);
        pane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        pane.addView(composer(t), new LinearLayout.LayoutParams(-1, -2));
        renderLesson(t, true);
        return pane;
    }

    /** The conversation: an introduction with ways to begin while it is empty, bubbles after. */
    private void renderLesson(SchoolBook.Teacher t, boolean toEnd) {
        if (lines == null) return;
        lines.removeAllViews();
        List<SchoolBook.Line> all = book.lines(t.id());
        boolean busy = t.id().equals(thinkingFor);
        if (all.isEmpty() && !busy) lines.addView(intro(t));
        for (SchoolBook.Line l : all) lines.addView(bubble(t, l));
        if (busy) {
            ShimmerText wait = new ShimmerText(this, MUTED, TEXT); wait.setText(t.name() + " 선생님이 생각하는 중"); wait.setTextSize(13); wait.setTypeface(Ui.weight(500));
            wait.setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(4)); lines.addView(wait);
        }
        if (failure != null && !busy) {
            TextView note = ui.text("· " + failure, 12.5f, 450, ERROR); note.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4)); lines.addView(note);
        }
        modelLabel(t);
        if (send != null) ui.sendLooks(send, busy);
        if (toEnd) scroll.post(() -> { if (lines != null) scroll.smoothScrollTo(0, Math.max(0, lines.getHeight() - scroll.getHeight())); });
    }
    private View intro(SchoolBook.Teacher t) {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(ui.dp(12), ui.dp(28), ui.dp(12), ui.dp(12));
        box.addView(face(t, 76), new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)));
        TextView name = ui.title(t.name() + " 선생님", 20); name.setGravity(Gravity.CENTER); name.setPadding(0, ui.dp(10), 0, ui.dp(2)); box.addView(name);
        String detail = (t.subject().isBlank() ? "" : t.subject()) + (t.level().isBlank() ? "" : (t.subject().isBlank() ? "" : " · ") + t.level() + " 수준");
        if (!detail.isEmpty()) { TextView d = ui.text(detail, 13.5f, 450, MUTED); d.setGravity(Gravity.CENTER); box.addView(d); }
        if (!t.style().isBlank()) { TextView s = ui.label("“" + (t.style().length() > 90 ? t.style().substring(0, 90) + "…" : t.style()) + "”", 13, MUTED); s.setGravity(Gravity.CENTER); s.setPadding(0, ui.dp(8), 0, 0); box.addView(s); }
        String subject = t.subject().isBlank() ? "" : t.subject() + " ";
        String[] starts = SchoolBook.method(t.method()).starts().clone();
        for (int i = 0; i < starts.length; i++) starts[i] = starts[i].replace("%s", subject);
        LinearLayout chips = ui.column(); chips.setGravity(Gravity.CENTER_HORIZONTAL); chips.setPadding(0, ui.dp(18), 0, 0);
        for (String s : starts) chips.addView(ui.pill(s.strip(), 0, false, () -> ask(t, s.strip())), new LinearLayout.LayoutParams(-2, -2));
        for (int i = 1; i < chips.getChildCount(); i++) ((LinearLayout.LayoutParams) chips.getChildAt(i).getLayoutParams()).topMargin = ui.dp(8);
        box.addView(chips);
        return box;
    }
    private View bubble(SchoolBook.Teacher t, SchoolBook.Line l) {
        boolean mine = l.mine();
        LinearLayout wrap = ui.column(); wrap.setGravity(mine ? Gravity.END : Gravity.START); wrap.setPadding(0, ui.dp(4), 0, ui.dp(4));
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP);
        TextView text = ui.text("", 15, mine ? 500 : 420, mine ? ON_ACCENT : TEXT); text.setLineSpacing(ui.dp(3), 1); text.setTextIsSelectable(true);
        text.setText(mine ? l.text() : RichText.render(l.text()));
        text.setPadding(ui.dp(13), ui.dp(8), ui.dp(13), ui.dp(9));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(mine ? ACCENT : FILL);
        float round = ui.dp(17), tail = ui.dp(5);
        shape.setCornerRadii(mine ? new float[]{round, round, round, round, tail, tail, round, round} : new float[]{tail, tail, round, round, round, round, round, round});
        text.setBackground(shape);
        text.setContentDescription((mine ? "나: " : t.name() + " 선생님: ") + l.text());
        if (mine) { row.setPadding(ui.dp(40), 0, 0, 0); row.addView(text, new LinearLayout.LayoutParams(-2, -2)); }
        else {
            row.setPadding(0, 0, ui.dp(24), 0);
            LinearLayout.LayoutParams faceAt = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)); faceAt.rightMargin = ui.dp(8); faceAt.topMargin = ui.dp(2);
            row.addView(face(t, 28), faceAt); row.addView(text, new LinearLayout.LayoutParams(-2, -2, 1));
        }
        wrap.addView(row, new LinearLayout.LayoutParams(-2, -2));
        if (!mine) {
            LinearLayout foot = ui.row(); foot.setPadding(ui.dp(36), 0, 0, 0);
            ImageView copy = ui.quietIcon(R.drawable.ic_copy, "답 복사", () -> copy(l.text()));
            copy.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); foot.addView(copy, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
            if (l.by() != null && !l.by().isBlank()) { TextView by = ui.text(l.by(), 11.5f, 450, MUTED); by.setPadding(ui.dp(4), 0, 0, 0); foot.addView(by); }
            wrap.addView(foot, new LinearLayout.LayoutParams(-2, -2));
        }
        return wrap;
    }
    private void copy(String text) {
        android.content.ClipboardManager clip = getSystemService(android.content.ClipboardManager.class);
        if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText("나의학교", text));
        Toast.makeText(this, "복사했어요.", Toast.LENGTH_SHORT).show();
    }

    // ── the composer ─────────────────────────────────────────────────────

    /** As 작업창's: the words on top; under them the model (its name bold, the level quiet), 음성 입력 and send/stop. */
    private View composer(SchoolBook.Teacher t) {
        LinearLayout composer = ui.column(); composer.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8));
        LinearLayout box = ui.column(); box.setBackground(ui.glass(28, false)); ui.lift(box, 4); box.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4));
        composer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        input = new EditText(this); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setHint(t.name() + " 선생님에게 질문하기"); input.setMaxLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        input.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(2));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, event) -> { if (id == EditorInfo.IME_ACTION_SEND) { submit(t); return true; } return false; });
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL);
        model = ui.text("", 13.5f, 500, TEXT); model.setSingleLine(true); model.setEllipsize(TextUtils.TruncateAt.END);
        model.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); model.setPadding(ui.dp(10), 0, ui.dp(6), 0); model.setMinHeight(ui.dp(44));
        model.setOnClickListener(v -> chooseModel(t)); Ui.pressable(model, true);
        bar.addView(model, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.bareIcon(R.drawable.ic_mic, TEXT, "음성 입력 · 입력창에만 넣기", this::dictate), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        send = ui.sendButton("질문 보내기", () -> { if (t.id().equals(thinkingFor)) stop(); else submit(t); });
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return composer;
    }
    private void dictate() {
        if (store.voiceActive || store.recording || store.voiceChat != null) { Toast.makeText(this, "음성 대화나 녹음을 끝낸 뒤 음성으로 입력해 주세요.", Toast.LENGTH_LONG).show(); return; }
        if (!store.secrets.hasWorkKey()) { Toast.makeText(this, "음성 입력에 쓸 Gemini API 키가 없어요. 설정에서 Gemini를 연결해 주세요.", Toast.LENGTH_LONG).show(); return; }
        if (input == null) return;
        store.stopReading(null);
        if (dictation != null) dictation.pause();
        dictation = new ComposerDictation(this, input, () -> "school", message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        dictation.begin(() -> new GeminiComposerSession(store.secrets.workKey()), "Gemini 3.5 Transcribe Live");
    }
    private void modelLabel(SchoolBook.Teacher t) {
        if (model == null) return;
        String[] parts = appParts(t);
        android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(parts[0]);
        label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), 0);
        if (!parts[1].isEmpty()) { int at = label.length(); label.append("  ").append(parts[1]); label.setSpan(new android.text.style.ForegroundColorSpan(MUTED), at, label.length(), 0); }
        model.setText(label);
        model.setContentDescription("선생님이 쓰는 AI 바꾸기, 지금 " + parts[0] + (parts[1].isEmpty() ? "" : " " + parts[1]));
    }
    /** {name, thinking level} of the teacher's app AI; "AI 연결·선택" when none is connected. */
    private String[] appParts(SchoolBook.Teacher t) {
        try {
            ScopedModels selection = ScopedModels.tutor(store, t.id()); String engine = selection.engine();
            if (engine == null || engine.isBlank() || !store.ready(engine)) return new String[]{"AI 연결·선택", ""};
            String id = selection.model(engine); if (id.isBlank()) return new String[]{dev.jun.gyeot.core.WorkBook.author(engine) + " · 모델 고르기", ""};
            TeamRoster roster = new TeamRoster(store, selection);
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, id); String effort = selection.effort(engine, id);
            String level = profile.efforts.isEmpty() ? "" : MyTubeChat.effortWord(engine, effort.isEmpty() ? profile.defaultEffort : effort);
            return new String[]{roster.label(engine, id), level};
        } catch (Exception unavailable) { return new String[]{"AI 연결·선택", ""}; }
    }
    /** With Groq connected: Groq's free model or the app's AIs (then their menu); without, the app's AIs' menu at once. */
    /** The model chip opens the same model menu as every screen (Groq and Antigravity are AIs in it, as the others). */
    private void chooseModel(SchoolBook.Teacher t) { appModels(t); }
    private void appModels(SchoolBook.Teacher t) {
        if (t == null || model == null) return;
        ScopedModels selection; try { selection = ScopedModels.tutor(store, t.id()); } catch (Exception e) { store.error(e.getMessage()); return; }
        Runnable changed = () -> { SchoolBook.Teacher now = book.teacher(t.id()); if (now != null) modelLabel(now); };
        Runnable all = () -> FileExplorerModelUi.show(this, store, () -> ScopedModels.tutor(store, t.id()), t.name() + " 선생님", "선생님에게 적용", () -> !isDestroyed(), dialog -> dialog.show(), changed);
        String engine = selection.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) { all.run(); return; }
        TeamRoster roster = new TeamRoster(store, selection);
        new ModelPopover(this, ui, store, selection, new ModelPopover.Catalog() {
            @Override public JSONArray models(String e) { return roster.catalog(e); }
            @Override public String label(String e, String m) { return roster.label(e, m); }
            @Override public dev.jun.gyeot.core.Reasoning.Profile profile(String e, String m) { return new TeamRoster(store).profile(e, m); }
            @Override public String effortLabel(String e, String effort) { return MyTubeChat.effortWord(e, effort); }
            @Override public String effortHint(String effort, String fallback) { return MainActivity.effortHint(effort, fallback); }
            @Override public String team() { return ""; }
        }, e -> {
            try { selection.chooseEngine(e); } catch (Exception failed) { store.error(failed.getMessage()); return; }
            if (!store.ready(e)) Toast.makeText(this, dev.jun.gyeot.core.WorkBook.author(e) + "를 설정에서 먼저 연결해 주세요.", Toast.LENGTH_LONG).show();
            changed.run();
        }, all, null, changed).show(model);
    }

    // ── asking ───────────────────────────────────────────────────────────

    private void submit(SchoolBook.Teacher t) {
        if (input == null) return;
        String text = input.getText().toString().strip(); if (text.isEmpty() || thinkingFor != null) return;
        input.setText("");
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        ask(t, text);
    }
    /** The learner's words written into the lesson, then the teacher's answer (off the main thread). */
    private void ask(SchoolBook.Teacher t, String text) {
        if (thinkingFor != null) { Toast.makeText(this, "선생님이 답하는 중이에요. 잠시만 기다려 주세요.", Toast.LENGTH_SHORT).show(); return; }
        try { book.say(t.id(), "user", text, ""); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        thinkingFor = t.id(); failure = null; int mine = ++ticket;
        refresh(t.id(), true);
        work.execute(() -> {
            String[] answer = null; String why = null;
            try { answer = answer(book.teacher(t.id())); }
            catch (Exception e) { why = e.getMessage() == null ? "답하지 못했어요." : e.getMessage(); }
            if (answer != null && ticket == mine) try { book.say(t.id(), "teacher", answer[0], answer[1]); } catch (Exception e) { why = e.getMessage(); }
            String failed = why;
            main.post(() -> {
                if (destroyed || ticket != mine) return;
                thinkingFor = null; failure = failed;
                refresh(t.id(), true);
            });
        });
    }
    /** Redraws what shows this teacher: the lesson when open, and the list row's last words on a wide screen. */
    private void refresh(String id, boolean toEnd) {
        if (destroyed) return;
        if (wide) fillList(book.teachers());
        SchoolBook.Teacher t = book.teacher(id);
        if (t != null && id.equals(openId)) renderLesson(t, toEnd);
    }
    private void stop() {
        Runnable stop = cancel.getAndSet(null); if (stop != null) try { stop.run(); } catch (RuntimeException ignored) {}
        if (thinkingFor != null) { String id = thinkingFor; ticket++; thinkingFor = null; failure = "멈췄어요."; if (!destroyed) refresh(id, false); }
    }
    /**
     * The teacher's answer and who gave it. Groq's free GPT OSS 120B when the teacher uses it and its free tier has room (the
     * lesson's newest part, as messages), else the teacher's app AI with the lesson written out. Blocking.
     */
    private String[] answer(SchoolBook.Teacher t) throws Exception {
        if (t == null) throw new IllegalStateException("이 선생님을 찾지 못했어요.");
        List<SchoolBook.Line> all = book.lines(t.id());
        String system = SchoolBook.tutor(t);
        ScopedModels scope = ScopedModels.tutor(store, t.id());
        // Groq's free tier takes about 8,000 tokens a minute, the answer included: the lesson's newest 3,000 characters there.
        int budget = dev.jun.gyeot.core.WorkBook.GROQ.equals(scope.engine()) ? 3000 : 16000;
        String[] by = {""};
        String text = MyTubeClient.askScoped(store, scope, system, SchoolBook.transcript(SchoolBook.history(all, budget)), cancel, b -> by[0] = b);
        return new String[]{text, by[0]};
    }
    private boolean ticketStopped() { return thinkingFor == null; }

    // ── 판서 ─────────────────────────────────────────────────────────────

    /** 판서: opens the board with the lesson's last question in its field, waiting for the user to send it. */
    private void board(SchoolBook.Teacher t) {
        // Only the question field is filled: the board is prepared when the user sends it there.
        String draft = SchoolBook.boardDraft(t, book.lines(t.id()));
        Intent intent = new Intent(this, BoardActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!draft.isBlank()) intent.putExtra(BoardActivity.DRAFT, draft);
        startActivity(intent);
    }

    // ── the teachers ─────────────────────────────────────────────────────

    private void teacherMenu(View anchor, SchoolBook.Teacher t) {
        ui.miniMenu(anchor, new String[]{"선생님 정보 고치기", "대화 지우기", "선생님 내보내기"}, -1, i -> {
            if (i == 0) edit(t);
            else if (i == 1) ui.confirm(t.name() + " 선생님과의 대화를 모두 지울까요?", "대화 지우기", () -> { if (t.id().equals(thinkingFor)) stop(); book.clear(t.id()); failure = null; draw(); }).show();
            else ui.confirm(t.name() + " 선생님을 내보낼까요? 대화와 사진도 함께 지워져요.", "내보내기 · 삭제", () -> {
                if (t.id().equals(thinkingFor)) stop();
                try { book.remove(t.id()); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
                store.settings.edit().remove("tutorModels:" + t.id()).apply(); faces.remove(t.id());
                if (t.id().equals(openId)) openId = null;
                draw();
            }).show();
        });
    }

    /** 선생님 모시기 / 정보 고치기: picture (GPT image), name, subject, level and how they teach. */
    private void edit(SchoolBook.Teacher t) {
        Ui.Sheet sheet = ui.selectionSheet(t == null ? "선생님 모시기" : "선생님 정보", "이 휴대폰에만 저장돼요");
        byte[][] picture = {null}; boolean[] cleared = {false};
        ImageView avatar = new ImageView(this); avatar.setContentDescription("선생님 사진");
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ui.dp(88), ui.dp(88)); at.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.body.addView(avatar, at);
        Runnable showFace = () -> {
            Drawable shown = null;
            if (picture[0] != null) shown = decodeFace(picture[0]);
            else if (t != null && !cleared[0]) shown = faceDrawable(t);
            if (shown != null) { avatar.setImageDrawable(new AssistantProfile.Circle(shown)); avatar.setBackground(null); avatar.setPadding(0, 0, 0, 0); }
            else { avatar.setImageResource(R.drawable.ic_person); avatar.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED)); avatar.setBackground(ui.shape(FILL, PILL)); avatar.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22)); }
            if (shown != null) avatar.setImageTintList(null);
        };
        showFace.run();
        avatar.setOnClickListener(v -> {
            if (picture[0] != null) showLarge(decodeFace(picture[0], 1600), "새 사진 · 저장하면 바뀌어요");
            else if (t != null && !cleared[0]) showFace(t);
        });
        EditText name = ui.input("이름 · 예: 김민지", 1, 30), subject = ui.input("과목·분야 · 예: 중학 수학, 영어 회화, 파이썬", 1, 40),
            level = ui.input("내 수준 · 예: 중학교 2학년, 회화 초급, 완전 처음", 1, 80),
            style = ui.input("성격·말투·가르치는 방식 · 예: 다정하고 칭찬을 많이 해요. 비유로 쉽게 설명하고 매번 짧은 퀴즈를 내요.", 3, 600);
        if (t != null) { name.setText(t.name()); subject.setText(t.subject()); level.setText(t.level()); style.setText(t.style()); }
        LinearLayout faces = ui.row(); faces.setGravity(Gravity.CENTER_HORIZONTAL); faces.setPadding(0, ui.dp(8), 0, ui.dp(4));
        faces.addView(ui.pill("GPT로 프사 만들기", R.drawable.ic_gallery, true, () -> makeFace(
            describe(name.getText().toString(), subject.getText().toString(), style.getText().toString()),
            png -> { picture[0] = png; cleared[0] = false; showFace.run(); })), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
        faces.addView(ui.pill("사진 지우기", 0, false, () -> { picture[0] = null; cleared[0] = true; showFace.run(); }), gap);
        sheet.body.addView(faces);
        // AI 자동 채우기: the empty fields filled to match the ones written (all of them when nothing is written).
        TextView[] filling = {null};
        filling[0] = ui.pill("AI로 빈칸 채우기", R.drawable.ic_sparkle, false, () -> {
            EditText[] fields = {name, subject, level, style};
            boolean any = false; for (EditText f : fields) if (f.getText().toString().isBlank()) any = true;
            if (!any) { Toast.makeText(this, "모든 칸이 채워져 있어요. 바꾸고 싶은 칸을 비우고 다시 눌러 주세요.", Toast.LENGTH_LONG).show(); return; }
            String asked = SchoolBook.fillInput(name.getText().toString(), subject.getText().toString(), level.getText().toString(), style.getText().toString());
            filling[0].setEnabled(false); filling[0].setText("채우는 중…");
            work.execute(() -> {
                String[] got = null; String why = null;
                try { got = SchoolBook.parseFill(Chores.run(store, SchoolBook.FILL, asked, true)); }
                catch (Exception e) { why = e.getMessage() == null ? "채우지 못했어요." : e.getMessage(); }
                String[] filled = got; String failed = why;
                main.post(() -> {
                    if (destroyed || !sheet.isShowing()) return;
                    filling[0].setEnabled(true); filling[0].setText("AI로 빈칸 채우기");
                    if (filled == null) { Toast.makeText(this, failed, Toast.LENGTH_LONG).show(); return; }
                    int put = 0;
                    for (int i = 0; i < fields.length; i++) if (fields[i].getText().toString().isBlank() && !filled[i].isBlank()) { fields[i].setText(filled[i]); put++; }
                    if (put == 0) Toast.makeText(this, "AI가 채울 내용을 보내지 않았어요. 다시 눌러 주세요.", Toast.LENGTH_LONG).show();
                });
            });
        });
        LinearLayout fillRow = ui.row(); fillRow.setGravity(Gravity.CENTER_HORIZONTAL); fillRow.setPadding(0, ui.dp(2), 0, 0);
        fillRow.addView(filling[0], new LinearLayout.LayoutParams(-2, -2));
        sheet.body.addView(fillRow);
        for (Object[] field : new Object[][]{{"이름", name}, {"과목·분야", subject}, {"내 수준", level}, {"성격·말투·가르치는 방식", style}}) {
            TextView title = ui.text((String) field[0], FOOTNOTE, 620, MUTED); title.setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(6));
            sheet.body.addView(title); sheet.body.addView((View) field[1]);
        }
        // 지도법: a preset way of teaching (기본 과외, or 목표부터 거꾸로 · 페테르손식).
        TextView methodTitle = ui.text("지도법", FOOTNOTE, 620, MUTED); methodTitle.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(8)); sheet.body.addView(methodTitle);
        int[] method = {Math.max(0, SchoolBook.METHODS.indexOf(SchoolBook.method(t == null ? "" : t.method())))};
        String[] methodNames = new String[SchoolBook.METHODS.size()]; for (int i = 0; i < methodNames.length; i++) { String n = SchoolBook.METHODS.get(i).name(); methodNames[i] = n.contains(" · ") ? n.substring(n.lastIndexOf(" · ") + 3) : n; }
        TextView methodAbout = ui.footer(SchoolBook.METHODS.get(method[0]).name() + "\n" + SchoolBook.METHODS.get(method[0]).about());
        sheet.body.addView(ui.pills(methodNames, method[0], i -> { method[0] = i; methodAbout.setText(SchoolBook.METHODS.get(i).name() + "\n" + SchoolBook.METHODS.get(i).about()); }));
        sheet.body.addView(methodAbout);
        sheet.body.addView(ui.footer("일부만 적고 ‘AI로 빈칸 채우기’를 누르면 나머지를 어울리게 채워 줘요(Groq 우선). 선생님은 정한 성격과 말투로 1:1 과외를 해요. Groq가 연결돼 있으면 Groq(GPT OSS 120B)가 답하고, 대화창 아래 모델 이름을 눌러 다른 AI로 바꿀 수 있어요."));
        sheet.primary.setText(t == null ? "모시기" : "저장");
        sheet.primary.setOnClickListener(v -> {
            String n = name.getText().toString().strip();
            if (n.isEmpty()) { name.setError("이름을 적어 주세요."); return; }
            try {
                SchoolBook.Teacher saved;
                if (t == null) {
                    saved = book.add(n, subject.getText().toString(), style.getText().toString(), level.getText().toString(), "app", SchoolBook.METHODS.get(method[0]).id());
                    // A new teacher starts on Groq when it is connected (chosen in the model menu like any AI).
                    if (store.groq.connected()) try { ScopedModels.tutor(store, saved.id()).chooseEngine(dev.jun.gyeot.core.WorkBook.GROQ); } catch (Exception ignored) {}
                } else { SchoolBook.Teacher now = book.teacher(t.id()); saved = (now == null ? t : now).with(n, subject.getText().toString(), style.getText().toString(), level.getText().toString(), SchoolBook.METHODS.get(method[0]).id()); book.save(saved); }
                if (picture[0] != null) book.avatar(saved.id(), picture[0]); else if (cleared[0]) book.avatar(saved.id(), null);
                this.faces.remove(saved.id());
                sheet.dismiss();
                openId = saved.id(); failure = null; draw();
            } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        sheet.show();
    }
    /** A first description for GPT image from the form: who the teacher is and how they feel. */
    static String describe(String name, String subject, String style) {
        String s = subject.strip(), feel = style.strip();
        if (feel.length() > 80) feel = feel.substring(0, 80);
        return ((s.isEmpty() ? "" : s + " ") + "선생님의 프로필 사진, 상반신, 따뜻한 표정" + (feel.isEmpty() ? "" : ", 분위기: " + feel)).strip();
    }

    /** GPT image draws the teacher's picture from a description and a style; 적용 hands it to the form (saved with it). */
    private void makeFace(String suggestion, java.util.function.Consumer<byte[]> done) {
        String via = AssistantProfileUi.route(store);
        if (via.isEmpty()) { Toast.makeText(this, "GPT 이미지를 쓰려면 설정 → ChatGPT에서 이미지용 Codex 계정이나 API 키를 연결해 주세요.", Toast.LENGTH_LONG).show(); return; }
        boolean codex = via.equals("codex");
        Ui.Sheet s = ui.selectionSheet("GPT로 프사 만들기", (codex ? "ChatGPT 이미지(Codex)" : "OpenAI 이미지 API") + " · 정사각형 한 장");
        ImageView preview = new ImageView(this); preview.setContentDescription("만든 선생님 사진 미리보기"); preview.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(120), ui.dp(120)); pp.gravity = Gravity.CENTER_HORIZONTAL; pp.bottomMargin = ui.dp(12);
        s.body.addView(preview, pp);
        EditText describe = ui.input("어떤 모습의 선생님일까요? 예: 동그란 안경을 쓴 다정한 수학 선생님, 칠판 앞", 3, dev.jun.gyeot.core.ProfilePicture.LIMIT);
        describe.setText(suggestion); s.body.addView(describe);
        TextView styleTitle = ui.text("스타일", FOOTNOTE, 620, MUTED); styleTitle.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(8)); s.body.addView(styleTitle);
        int[] style = {0}; s.body.addView(ui.pills(dev.jun.gyeot.core.ProfilePicture.STYLES, 0, i -> style[0] = i));
        TextView status = ui.footer("적은 설명만 " + (codex ? "ChatGPT(Codex)" : "OpenAI") + "로 보내 그림을 만들어요. 보통 30초~1분 걸리고 사용량이 들 수 있어요.");
        s.body.addView(status);
        Button again = ui.button("다시 만들기", false, () -> {}); again.setVisibility(View.GONE);
        LinearLayout.LayoutParams ag = new LinearLayout.LayoutParams(-1, -2); ag.bottomMargin = ui.dp(8); s.footer.addView(again, 0, ag);
        byte[][] made = {null}; int[] turn = {0}; boolean[] open = {true};
        AtomicReference<Runnable> stop = new AtomicReference<>();
        Runnable draw = () -> {
            if (!open[0]) return;
            JSONObject args;
            try {
                String ask = dev.jun.gyeot.core.ProfilePicture.prompt(describe.getText().toString(), style[0]);
                args = dev.jun.gyeot.core.Drawing.directArguments(new JSONObject().put("prompt", ask).put("aspect", "square").put("edit", false).put("codex", codex).toString());
            } catch (Exception e) { status.setText(e.getMessage() == null ? "설명을 확인해 주세요." : e.getMessage()); return; }
            int mine = ++turn[0]; made[0] = null;
            s.primary.setEnabled(false); s.primary.setText("그리는 중…"); again.setVisibility(View.GONE); describe.setEnabled(false);
            status.setText("그리는 중이에요. 이 창을 닫으면 멈춰요.");
            Thread worker = new Thread(() -> {
                try {
                    JSONObject drawn = AssistantProfileUi.painter.draw(store, args, stop, codex);
                    byte[] png = AssistantProfile.avatar(java.util.Base64.getMimeDecoder().decode(drawn.optString("data")));
                    Drawable face = decodeFace(png);
                    main.post(() -> {
                        if (!open[0] || turn[0] != mine || destroyed) return;
                        made[0] = png;
                        if (face != null) { preview.setImageDrawable(new AssistantProfile.Circle(face)); preview.setVisibility(View.VISIBLE); }
                        s.primary.setEnabled(true); s.primary.setText("적용"); again.setVisibility(View.VISIBLE); describe.setEnabled(true);
                        status.setText("마음에 들면 ‘적용’을 누른 뒤 선생님 정보를 저장해요. 설명을 고쳐 다시 만들 수도 있어요.");
                    });
                } catch (Exception | OutOfMemoryError e) {
                    main.post(() -> {
                        if (!open[0] || turn[0] != mine || destroyed) return;
                        s.primary.setEnabled(true); s.primary.setText("다시 시도"); describe.setEnabled(true);
                        status.setText(e.getMessage() == null ? "그림을 만들지 못했어요." : e.getMessage());
                    });
                }
            }, "school-face");
            worker.setDaemon(true); worker.start();
        };
        again.setOnClickListener(v -> draw.run());
        s.primary.setText("만들기");
        s.primary.setOnClickListener(v -> { if (made[0] != null) { done.accept(made[0]); s.dismiss(); } else draw.run(); });
        s.setOnDismissListener(d -> { open[0] = false; turn[0]++; Runnable r = stop.getAndSet(null); if (r != null) try { r.run(); } catch (RuntimeException ignored) {} });
        s.show();
    }

    // ── pictures ─────────────────────────────────────────────────────────

    /** The teacher's picture in a circle, or their first letter on a soft color when they have none. */
    private View face(SchoolBook.Teacher t, int size) {
        Drawable picture = faceDrawable(t);
        if (picture != null) {
            ImageView view = new ImageView(this); view.setImageDrawable(new AssistantProfile.Circle(picture));
            // Tapped, the picture opens large (the row or bubble under it keeps its own tap).
            view.setContentDescription(t.name() + " 선생님 사진 크게 보기"); view.setOnClickListener(v -> showFace(t)); Ui.pressable(view, true);
            return view;
        }
        TextView letter = ui.text(t.name().isEmpty() ? "?" : t.name().substring(0, Character.charCount(t.name().codePointAt(0))), size * 0.42f, 700, 0xffffffff);
        letter.setGravity(Gravity.CENTER); letter.setIncludeFontPadding(false);
        int[] tones = {0xff5b8def, 0xffe0795a, 0xff3fae8c, 0xffb57bd8, 0xffd9a23c, 0xff5aa7c7, 0xffd1667f};
        letter.setBackground(ui.shape(tones[Math.floorMod(t.id().hashCode(), tones.length)], PILL));
        letter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return letter;
    }
    private Drawable faceDrawable(SchoolBook.Teacher t) {
        File file = book.avatar(t.id());
        if (!file.isFile()) return null;
        String key = t.id() + ":" + file.lastModified();
        Drawable kept = faces.get(key); if (kept != null) return kept.getConstantState() == null ? kept : kept.getConstantState().newDrawable();
        try { Drawable made = decodeFace(java.nio.file.Files.readAllBytes(file.toPath())); if (made != null) { faces.put(key, made); return made.getConstantState() == null ? made : made.getConstantState().newDrawable(); } }
        catch (Exception | OutOfMemoryError ignored) {}
        return null;
    }
    /** The teacher's picture large on a dark screen, with their name; a tap anywhere (or back) closes it. */
    private void showFace(SchoolBook.Teacher t) {
        File file = book.avatar(t.id()); if (!file.isFile()) return;
        Drawable large; try { large = decodeFace(java.nio.file.Files.readAllBytes(file.toPath()), 1600); } catch (Exception | OutOfMemoryError e) { large = null; }
        showLarge(large, t.name() + " 선생님" + (t.subject().isBlank() ? "" : " · " + t.subject()));
    }
    private void showLarge(Drawable large, String caption) {
        if (large == null) return;
        android.app.Dialog viewer = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER); box.setBackgroundColor(0xf0000000); box.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        ImageView picture = new ImageView(this); picture.setImageDrawable(large); picture.setScaleType(ImageView.ScaleType.FIT_CENTER); picture.setAdjustViewBounds(true);
        picture.setBackground(ui.shape(0, R_CARD)); picture.setClipToOutline(true); picture.setContentDescription(caption + " 사진");
        int side = Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) - ui.dp(40);
        box.addView(picture, new LinearLayout.LayoutParams(Math.min(side, ui.dp(520)), Math.min(side, ui.dp(520))));
        TextView name = ui.text(caption, 16, 650, 0xffffffff); name.setGravity(Gravity.CENTER); name.setPadding(0, ui.dp(16), 0, 0);
        box.addView(name);
        box.setOnClickListener(v -> viewer.dismiss());
        viewer.setContentView(box); viewer.setCanceledOnTouchOutside(true);
        viewer.show();
    }
    /** A small bitmap of the picture (at most about 192px), enough for the list, the lesson and the form. */
    private Drawable decodeFace(byte[] bytes) { return decodeFace(bytes, 192); }
    private Drawable decodeFace(byte[] bytes, int largest) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= largest) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = sample;
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            return bitmap == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        } catch (Exception | OutOfMemoryError e) { return null; }
    }
}
