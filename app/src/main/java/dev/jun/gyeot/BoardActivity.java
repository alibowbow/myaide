package dev.jun.gyeot;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Board;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.MainRequest;
import dev.jun.gyeot.core.WorkBook;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * 판서: a board to write and draw on by hand, and to be taught on. Asking for something (with a photo, a PDF or a text
 * file of the problem when there is one) has the chosen AI model prepare a lesson, which is then written on the board
 * step by step while a voice reads the explanation. The board is kept as it changes; older boards are a tap away.
 */
public final class BoardActivity extends Activity {
    /** Opens this board; asks this at once. */
    static final String OPEN = "gyeot.board_id", ASK = "gyeot.board_ask";
    static final int PICK = 71, FILES = 4, SNAPSHOT_UNITS = 3000;
    static final long FILE_BYTES = 12L * 1024 * 1024;
    /** The model and the voices; tests put their own here. */
    static BoardTeacher.Brain brain; static BoardVoice.Voices voices;
    /** Reading and writing files off the main thread; tests run it in place. */
    static Executor work;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui; private AppStore store; private BoardStore boards; private MainNavigation navigation;
    private FrameLayout root; private BoardView board; private BoardTeacher teacher;
    private LinearLayout chrome, palette, askBar, lessonBar, files, hint; private TextView titleView, caption, progress; private EditText input;
    private ImageView undo, redo, sound, pause, next, colorDot; private final List<ImageView> toolIcons = new ArrayList<>(); private final List<String> toolIds = new ArrayList<>();
    private String boardId = "", title = "", shape = BoardView.LINE; private final List<JSONObject> attached = new ArrayList<>();
    private boolean destroyed; private Ui.Sheet sheet;
    private final Runnable save = this::saveNow;

    /** Opens the board on a fresh page and has it teach this: a question, and (when there is one) the answer to explain. */
    static void teach(Activity from, String question, String answer) {
        String asked = question == null ? "" : question.strip(), known = answer == null ? "" : dev.jun.gyeot.core.Markdown.plain(answer).strip();
        if (known.length() > 8000) known = known.substring(0, 8000);
        String ask = known.isEmpty() ? asked : (asked.isEmpty() ? "" : asked + "\n\n") + "아래 내용을 판서로 차근차근 설명해 줘.\n\n" + known;
        if (ask.isBlank()) return;
        from.startActivity(new Intent(from, BoardActivity.class).putExtra(ASK, ask).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    /** A request that came with the screen: on a new board when this one has something on it, then asked. */
    private void asked(String ask) {
        if (ask == null || ask.isBlank() || teacher.busy()) return;
        if (board.items().length() > 0) newBoard();
        input.setText(ask); send();
    }
    private void noTransition() { if (android.os.Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0); } else overridePendingTransition(0, 0); }
    @Override public void finish() { super.finish(); noTransition(); }
    private void background(Runnable job) { if (work != null) work.execute(job); else worker.execute(job); }
    private void foreground(Runnable job) { if (work != null) job.run(); else main.post(job); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); noTransition();
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); boards = new BoardStore(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        board = new BoardView(this); root.addView(board, new FrameLayout.LayoutParams(-1, -1));
        board.listen(new BoardView.Listener() {
            @Override public void changed() { saveSoon(); paintHistory(); }
            @Override public void textAt(float x, float y) { writeText(x, y); }
        });
        teacher = new BoardTeacher(board, new BoardVoice(this, store, voices), brain != null ? brain : this::askModel, new BoardTeacher.Watcher() {
            @Override public void state(String now, int step, int steps, String words) { lesson(now, step, steps, words); }
            @Override public void notice(String why) { toast(why); }
            @Override public void wrote(String lessonTitle) { if (lessonTitle != null && !lessonTitle.isBlank() && title.isBlank()) { title = lessonTitle; paintTitle(); } saveSoon(); paintHistory(); }
        });
        hint = ui.column(); hint.setGravity(Gravity.CENTER_HORIZONTAL); hint.setPadding(ui.dp(40), 0, ui.dp(40), ui.dp(60));
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_board); mark.setImageTintList(ColorStateList.valueOf(MUTED)); mark.setAlpha(.55f); hint.addView(mark, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView lead = ui.text("손으로 쓰고, AI에게 배워요", TITLE2, 700, TEXT); lead.setGravity(Gravity.CENTER); lead.setPadding(0, ui.dp(14), 0, ui.dp(6)); hint.addView(lead);
        TextView how = ui.text("문제 사진이나 PDF를 붙여 보내면 판서로 풀어 주고, 주제를 적으면 차례로 가르쳐 줘요.", SUBHEAD, 450, MUTED); how.setGravity(Gravity.CENTER); how.setLineSpacing(ui.dp(3), 1); hint.addView(how);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2); hp.gravity = Gravity.CENTER; root.addView(hint, hp);
        chrome = ui.column(); root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            boolean typing = insets.isVisible(WindowInsets.Type.ime());
            chrome.setPadding(ui.dp(12) + bars.left, ui.dp(8) + bars.top, ui.dp(12) + bars.right, ui.dp(10) + Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            // While typing, the tools make room for the keyboard: the question is what matters.
            if (palette != null) palette.setVisibility(typing || teacher.busy() ? View.GONE : View.VISIBLE);
            return insets;
        });
        build();
        navigation = new MainNavigation(this, root, "board", target -> MainNavigation.open(this, target), false);
        setContentView(navigation); root.requestApplyInsets();
        String wanted = state != null ? state.getString("board", "") : getIntent().getStringExtra(OPEN);
        open(wanted == null ? "" : wanted);
        String ask = state == null ? getIntent().getStringExtra(ASK) : null;
        if (ask != null && !ask.isBlank()) main.post(() -> asked(ask));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String id = intent.getStringExtra(OPEN), ask = intent.getStringExtra(ASK);
        if (id != null && !id.equals(boardId)) { teacher.stop(); saveNow(); open(id); }
        asked(ask);
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("board", boardId); }
    @Override protected void onResume() { super.onResume(); AppNavigator.inFront("board"); }
    @Override protected void onPause() { if (teacher.state().equals(BoardTeacher.PLAYING)) teacher.pause(); main.removeCallbacks(save); saveNow(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacks(save); if (sheet != null && sheet.isShowing()) sheet.dismiss();
        teacher.close(); dropFiles(); worker.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (navigation != null && navigation.close()) return;
        if (teacher.busy()) { teacher.stop(); return; }
        super.onBackPressed();
    }

    // ── The screen ──────────────────────────────────────────────────────────────────────────────────────

    /** A floating bar: a white capsule with a hairline, lifted off the page by a soft shadow. */
    private GradientDrawable bar() { return surface(PILL); }
    private GradientDrawable surface(int radius) { GradientDrawable surface = ui.glass(radius, false); if (!DARK) surface.setStroke(Math.max(1, ui.dp(.6f)), SEPARATOR); return surface; }
    private ImageView icon(int drawable, String description, Runnable action) {
        ImageView view = ui.bareIcon(drawable, TEXT, description, action); view.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); return view;
    }
    private void build() {
        LinearLayout top = ui.row(); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackground(bar()); ui.lift(top, 8); top.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        ImageView back = icon(R.drawable.ic_back, "돌아가기", this::finish); back.setImageTintList(ColorStateList.valueOf(ACCENT));
        top.addView(back, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        titleView = ui.text("", HEADLINE, 680, TEXT); titleView.setSingleLine(true); titleView.setEllipsize(TextUtils.TruncateAt.END); titleView.setPadding(ui.dp(4), 0, ui.dp(8), 0);
        titleView.setOnClickListener(v -> rename()); Ui.pressable(titleView, true);
        top.addView(titleView, new LinearLayout.LayoutParams(0, ui.dp(48), 1)); titleView.setGravity(Gravity.CENTER_VERTICAL);
        undo = icon(R.drawable.ic_undo, "되돌리기", () -> { board.undo(); paintHistory(); }); redo = icon(R.drawable.ic_redo, "다시 하기", () -> { board.redo(); paintHistory(); });
        top.addView(undo, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48))); top.addView(redo, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        top.addView(icon(R.drawable.ic_layers, "판 목록", this::listBoards), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        top.addView(icon(R.drawable.ic_more, "더 보기", this::more), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        chrome.addView(top, new LinearLayout.LayoutParams(-1, -2));
        View gap = new View(this); gap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); chrome.addView(gap, new LinearLayout.LayoutParams(-1, 0, 1));

        caption = ui.text("", CALLOUT, 500, TEXT); caption.setMaxLines(4); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setLineSpacing(ui.dp(3), 1);
        caption.setBackground(surface(R_CARD)); ui.lift(caption, 8); caption.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); caption.setVisibility(View.GONE);
        caption.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.add(chrome, caption, 8);

        lessonBar = ui.row(); lessonBar.setGravity(Gravity.CENTER_VERTICAL); lessonBar.setBackground(bar()); ui.lift(lessonBar, 8); lessonBar.setPadding(ui.dp(18), 0, ui.dp(6), 0); lessonBar.setVisibility(View.GONE);
        progress = ui.text("", SUBHEAD, 620, TEXT); progress.setSingleLine(true); lessonBar.addView(progress, new LinearLayout.LayoutParams(0, -2, 1));
        sound = icon(R.drawable.ic_sound, "소리 끄기", () -> { teacher.mute(!teacher.muted()); paintSound(); });
        pause = icon(R.drawable.ic_pause, "잠깐 멈추기", () -> { if (teacher.state().equals(BoardTeacher.PAUSED)) teacher.resume(); else teacher.pause(); });
        next = icon(R.drawable.ic_next, "다음 단계로", () -> teacher.skip());
        ImageView stop = icon(R.drawable.ic_stop, "수업 끝내기", () -> teacher.stop()); stop.setImageTintList(ColorStateList.valueOf(RED));
        for (ImageView control : new ImageView[]{sound, pause, next, stop}) lessonBar.addView(control, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(52)));
        ui.add(chrome, lessonBar, 8);

        files = ui.row(); HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.addView(files);
        ui.add(chrome, strip, 6);

        askBar = ui.row(); askBar.setGravity(Gravity.CENTER_VERTICAL); askBar.setBackground(surface(R_SHEET)); ui.lift(askBar, 8); askBar.setPadding(ui.dp(4), ui.dp(2), ui.dp(6), ui.dp(2));
        ImageView clip = icon(R.drawable.ic_clip, "문제나 자료 첨부", this::pick); clip.setImageTintList(ColorStateList.valueOf(MUTED));
        askBar.addView(clip, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        input = new EditText(this); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(BODY); input.setHint("풀 문제나 배우고 싶은 것");
        input.setMaxLines(4); input.setPadding(ui.dp(4), ui.dp(10), ui.dp(8), ui.dp(10)); input.setContentDescription("판서로 물어볼 말");
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        askBar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView sendButton = ui.iconButton(R.drawable.ic_send, ACCENT, ON_ACCENT, "판서로 설명 듣기", this::send);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)); sp.leftMargin = ui.dp(2); askBar.addView(sendButton, sp);
        ui.add(chrome, askBar, 8);

        palette = ui.row(); palette.setGravity(Gravity.CENTER); palette.setBackground(bar()); ui.lift(palette, 8); palette.setPadding(ui.dp(6), ui.dp(3), ui.dp(6), ui.dp(3));
        tool(BoardView.HAND, R.drawable.ic_hand, "판 움직이기"); tool(BoardView.PEN, R.drawable.ic_pen, "펜"); tool(BoardView.MARKER, R.drawable.ic_marker, "형광펜"); tool(BoardView.ERASER, R.drawable.ic_eraser, "지우개");
        tool("shape", R.drawable.ic_shapes, "도형"); tool(BoardView.TEXT, R.drawable.ic_text, "글자");
        colorDot = new ImageView(this); colorDot.setContentDescription("색 고르기"); colorDot.setOnClickListener(v -> colors()); Ui.pressable(colorDot, true); colorDot.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        palette.addView(colorDot, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.gravity = Gravity.CENTER_HORIZONTAL; chrome.addView(palette, pp);
        paintTools(); paintHistory(); paintSound();
    }
    private void tool(String id, int drawable, String name) {
        ImageView view = icon(drawable, name, () -> choose(id));
        toolIds.add(id); toolIcons.add(view); palette.addView(view, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
    }
    private boolean isShape(String tool) { return tool.equals(BoardView.LINE) || tool.equals(BoardView.ARROW) || tool.equals(BoardView.RECT) || tool.equals(BoardView.OVAL); }
    /** A tap on a tool takes it; a second tap on the pen or the shapes opens what they offer. */
    private void choose(String id) {
        String now = board.tool();
        if (id.equals("shape")) { if (isShape(now)) shapes(); else board.tool(shape); }
        else if (id.equals(BoardView.PEN) && now.equals(BoardView.PEN)) widths();
        else board.tool(id);
        paintTools();
    }
    private void paintTools() {
        String now = board.tool();
        for (int i = 0; i < toolIcons.size(); i++) {
            String id = toolIds.get(i); boolean on = id.equals(now) || id.equals("shape") && isShape(now); ImageView view = toolIcons.get(i);
            view.setBackground(on ? ui.shape(ACCENT_SOFT, PILL) : null); view.setImageTintList(ColorStateList.valueOf(on ? ACCENT : TEXT)); view.setSelected(on);
        }
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(BoardView.swatch(board.color())); dot.setStroke(ui.dp(2), SEPARATOR);
        colorDot.setImageDrawable(dot);
    }
    private void paintHistory() {
        if (hint != null) hint.setVisibility(board.items().length() == 0 && !teacher.busy() ? View.VISIBLE : View.GONE);
        undo.setEnabled(board.canUndo()); undo.setAlpha(board.canUndo() ? 1f : .3f); redo.setEnabled(board.canRedo()); redo.setAlpha(board.canRedo() ? 1f : .3f);
    }
    private void paintSound() { sound.setImageResource(teacher.muted() ? R.drawable.ic_sound_off : R.drawable.ic_sound); sound.setContentDescription(teacher.muted() ? "소리 켜기" : "소리 끄기"); }
    private void paintTitle() { titleView.setText(title.isBlank() ? "새 판" : title); titleView.setContentDescription((title.isBlank() ? "새 판" : title) + " · 눌러서 이름 바꾸기"); }

    /** What the screen shows for the lesson's state: the question box and tools when idle, the step and its words otherwise. */
    private void lesson(String state, int step, int steps, String words) {
        if (destroyed) return;
        boolean idle = state.equals(BoardTeacher.IDLE), thinking = state.equals(BoardTeacher.THINKING), paused = state.equals(BoardTeacher.PAUSED);
        askBar.setVisibility(idle ? View.VISIBLE : View.GONE); palette.setVisibility(idle ? View.VISIBLE : View.GONE); lessonBar.setVisibility(idle ? View.GONE : View.VISIBLE);
        progress.setText(thinking ? "판서를 준비하는 중…" : (paused ? "멈춤 · " : "") + step + " / " + steps);
        for (ImageView control : new ImageView[]{sound, pause, next}) control.setVisibility(thinking ? View.GONE : View.VISIBLE);
        pause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause); pause.setContentDescription(paused ? "이어서 하기" : "잠깐 멈추기");
        caption.setVisibility(!idle && !thinking && !words.isBlank() ? View.VISIBLE : View.GONE); caption.setText(words);
        if (idle) root.requestApplyInsets();
        paintHistory();
    }

    // ── Boards ──────────────────────────────────────────────────────────────────────────────────────────

    /** Shows the board with this id, else the newest one, else a new one. */
    private void open(String id) {
        BoardStore.Entry entry = id.isBlank() ? null : boards.entry(id);
        if (entry == null) { List<BoardStore.Entry> all = boards.list(); entry = all.isEmpty() ? null : all.get(0); }
        if (entry == null) { try { entry = boards.create("", System.currentTimeMillis()); } catch (Exception failed) { toast(failed.getMessage()); finish(); return; } }
        boardId = entry.id(); title = entry.title(); board.show(boards.items(boardId)); paintTitle(); paintHistory();
        board.post(board::scrollToEnd);
    }
    private void saveSoon() { main.removeCallbacks(save); main.postDelayed(save, 600); }
    private void saveNow() {
        if (boardId.isEmpty()) return;
        String id = boardId, name = title; JSONArray copy;
        try { copy = new JSONArray(board.items().toString()); } catch (Exception impossible) { return; }
        background(() -> { try { boards.save(id, copy, name, System.currentTimeMillis()); } catch (Exception failed) { foreground(() -> { if (!destroyed) toast("판을 저장하지 못했어요."); }); } });
    }
    private void newBoard() {
        teacher.stop(); main.removeCallbacks(save); saveNow();
        try { BoardStore.Entry made = boards.create("", System.currentTimeMillis()); open(made.id()); } catch (Exception failed) { toast(failed.getMessage()); }
    }
    private Ui.Sheet show(Ui.Sheet next) { if (sheet != null && sheet.isShowing()) sheet.dismiss(); sheet = next; next.show(); return next; }
    private void listBoards() {
        main.removeCallbacks(save); saveNow();
        Ui.Sheet list = ui.selectionSheet("판 목록", "지난 판서를 다시 열어요");
        LinearLayout group = ui.group(); ZonedDateTime now = ZonedDateTime.now();
        for (BoardStore.Entry entry : boards.list()) {
            boolean current = entry.id().equals(boardId);
            String name = current ? (title.isBlank() ? "새 판" : title) : entry.title().isBlank() ? "새 판" : entry.title();
            String under = (current ? Board.summary(board.items()) : entry.summary()) + " · " + PagesActivity.when(entry.updated(), now);
            ui.addRow(group, ui.listRow(R.drawable.ic_board, current ? ACCENT : MUTED, name, under, current ? null : ui.chevron(), () -> { list.dismiss(); if (!current) { teacher.stop(); open(entry.id()); } }), true);
        }
        list.body.addView(group);
        list.primary.setText("새 판"); list.primary.setOnClickListener(v -> { list.dismiss(); newBoard(); });
        show(list);
    }
    private void more() {
        Ui.Sheet menu = ui.selectionSheet("판서", ""); menu.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_add, ACCENT, "새 판", "빈 판에서 시작해요", null, () -> { menu.dismiss(); newBoard(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_pen, BLUE, "이름 바꾸기", title.isBlank() ? "아직 이름이 없어요" : title, null, () -> { menu.dismiss(); rename(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_share, TEAL, "사진으로 공유", "판 전체를 사진 한 장으로 저장하고 보내요", null, () -> { menu.dismiss(); share(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_eraser, ORANGE, "모두 지우기", "되돌리기로 다시 살릴 수 있어요", null, () -> { menu.dismiss(); teacher.stop(); board.clear(); paintHistory(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_close, RED, "이 판 지우기", "지우면 되살릴 수 없어요", null, () -> { menu.dismiss(); deleteBoard(); }), true);
        menu.body.addView(group);
        menu.body.addView(ui.sectionHeader("AI"));
        LinearLayout ai = ui.group(); ScopedModels models = ScopedModels.main(store); String engine = models.readyEngine();
        ui.addRow(ai, ui.listRow(R.drawable.ic_sparkle, INDIGO, "두뇌", engine.isBlank() ? "연결된 AI가 없어요 · 메인 대화에서 고르기" : WorkBook.author(engine) + " · " + models.model(engine) + " · 메인 대화에서 고른 모델",
            ui.chevron(), () -> { menu.dismiss(); MainNavigation.open(this, "chat"); }), true);
        boolean live = store.secrets.hasKey();
        ui.addRow(ai, ui.listRow(R.drawable.ic_wave, PURPLE, "목소리", live ? "Gemini Live" + (store.voice().isBlank() ? "" : " · " + store.voice()) + " · 안 되면 기기 음성" : "Gemini 키가 없어 기기 음성으로 읽어요", null, null), true);
        menu.body.addView(ai);
        menu.body.addView(ui.footer("문제 사진이나 PDF를 붙여 보내면 판서로 풀어 주고, 주제나 자료를 주면 차례로 가르쳐 줘요. 두 손가락으로 판을 움직이고 키울 수 있어요."));
        show(menu);
    }
    private void rename() {
        Ui.Sheet edit = ui.selectionSheet("판 이름", "");
        EditText name = ui.input("예: 이차방정식", 1, BoardStore.TITLE); name.setText(title); name.setSelection(name.getText().length()); name.setContentDescription("판 이름");
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(-1, -2); around.leftMargin = around.rightMargin = ui.dp(16); around.bottomMargin = ui.dp(12); edit.body.addView(name, around);
        edit.primary.setText("저장"); edit.primary.setOnClickListener(v -> { title = name.getText().toString().replaceAll("\\s+", " ").strip(); paintTitle(); saveSoon(); edit.dismiss(); });
        show(edit);
    }
    private void deleteBoard() {
        show(ui.confirm("‘" + (title.isBlank() ? "새 판" : title) + "’을(를) 지울까요? 되살릴 수 없어요.", "판 지우기", () -> {
            teacher.stop(); main.removeCallbacks(save); String id = boardId; boardId = "";
            try { boards.delete(id); } catch (Exception failed) { toast("판을 지우지 못했어요."); }
            open("");
        }));
    }

    // ── Tools that ask ──────────────────────────────────────────────────────────────────────────────────

    private void colors() {
        Ui.Sheet pick = ui.selectionSheet("색", ""); pick.primary.setVisibility(View.GONE);
        String[] names = {"기본", "빨강", "파랑", "초록", "주황", "보라"};
        LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER); row.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(16));
        for (int i = 0; i < Board.COLORS.length; i++) {
            String color = Board.COLORS[i]; boolean on = color.equals(board.color());
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(BoardView.swatch(color)); dot.setStroke(ui.dp(on ? 3 : 1), on ? ACCENT : SEPARATOR);
            ImageView view = new ImageView(this); view.setImageDrawable(dot); view.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); view.setContentDescription(names[i] + (on ? ", 선택됨" : ""));
            view.setOnClickListener(v -> { board.color(color); paintTools(); pick.dismiss(); }); Ui.pressable(view, true);
            row.addView(view, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
        }
        pick.body.addView(row); show(pick);
    }
    private void widths() {
        Ui.Sheet pick = ui.selectionSheet("펜 굵기", ""); pick.primary.setVisibility(View.GONE);
        String[] names = {"가늘게", "보통", "굵게"}; LinearLayout group = ui.group();
        for (int i = 0; i < BoardView.WIDTHS.length; i++) {
            float width = BoardView.WIDTHS[i]; boolean on = width == board.penWidth();
            ui.addRow(group, ui.listRow(R.drawable.ic_pen, on ? ACCENT : MUTED, names[i], "", on ? ui.text("✓", 16, 700, ACCENT) : null, () -> { board.penWidth(width); pick.dismiss(); }), true);
        }
        pick.body.addView(group); show(pick);
    }
    private void shapes() {
        Ui.Sheet pick = ui.selectionSheet("도형", "끌어서 그려요"); pick.primary.setVisibility(View.GONE);
        String[] ids = {BoardView.LINE, BoardView.ARROW, BoardView.RECT, BoardView.OVAL}, names = {"선", "화살표", "네모", "동그라미"}; LinearLayout group = ui.group();
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i]; boolean on = id.equals(board.tool());
            ui.addRow(group, ui.listRow(R.drawable.ic_shapes, on ? ACCENT : MUTED, names[i], "", on ? ui.text("✓", 16, 700, ACCENT) : null, () -> { shape = id; board.tool(id); paintTools(); pick.dismiss(); }), true);
        }
        pick.body.addView(group); show(pick);
    }
    private void writeText(float x, float y) {
        Ui.Sheet write = ui.selectionSheet("글자 넣기", "누른 자리에 적어요");
        EditText words = ui.input("적을 말", 2, Board.TEXT_LIMIT); words.setContentDescription("판에 적을 말");
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(-1, -2); around.leftMargin = around.rightMargin = ui.dp(16); around.bottomMargin = ui.dp(12); write.body.addView(words, around);
        write.primary.setText("적기"); write.primary.setOnClickListener(v -> { board.addText(x, y, words.getText().toString()); paintHistory(); write.dismiss(); });
        show(write); words.requestFocus();
    }

    // ── Asking the AI ───────────────────────────────────────────────────────────────────────────────────

    private void pick() {
        if (attached.size() >= FILES) { toast("자료는 " + FILES + "개까지 붙일 수 있어요."); return; }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "text/*"}), PICK);
        } catch (RuntimeException none) { toast("파일을 고를 앱이 없어요."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        background(() -> {
            JSONObject file = null; String problem = null;
            try {
                file = WorkFiles.addPicked(getApplicationContext(), store, uri);
                String kind = file.optString("kind");
                if (!(WorkBook.IMAGE.equals(kind) || WorkBook.PDF.equals(kind) || "text".equals(kind))) { store.attachmentFile(file.optString("id")).delete(); file = null; problem = "사진, PDF, 글 파일을 붙일 수 있어요."; }
            } catch (Exception failed) { problem = failed.getMessage() == null || failed.getMessage().isBlank() ? "파일을 읽지 못했어요." : failed.getMessage(); }
            JSONObject added = file; String why = problem;
            foreground(() -> { if (destroyed) { if (added != null) store.attachmentFile(added.optString("id")).delete(); return; } if (added != null) { attached.add(added); paintFiles(); } else toast(why); });
        });
    }
    private void paintFiles() {
        files.removeAllViews();
        for (JSONObject file : new ArrayList<>(attached)) {
            TextView chip = ui.text(file.optString("name") + "  ✕", FOOTNOTE, 560, TEXT); chip.setSingleLine(true); chip.setMaxWidth(ui.dp(220)); chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.setBackground(surface(PILL)); ui.lift(chip, 4); chip.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)); chip.setContentDescription(file.optString("name") + " 첨부 빼기");
            chip.setOnClickListener(v -> { attached.remove(file); store.attachmentFile(file.optString("id")).delete(); paintFiles(); }); Ui.pressable(chip, true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(6); p.topMargin = p.bottomMargin = ui.dp(3); files.addView(chip, p);
        }
    }
    private void dropFiles() { for (JSONObject file : attached) store.attachmentFile(file.optString("id")).delete(); attached.clear(); }
    private boolean handDrawn() {
        JSONArray items = board.items();
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null && Board.ME.equals(item.optString("by")) && !Board.TEXT.equals(item.optString("t"))) return true; }
        return false;
    }
    /** Sends the question with its files, and with a picture of the board when the user drew on it by hand. */
    private void send() {
        if (teacher.busy()) return;
        String words = input.getText().toString().strip();
        if (words.isEmpty() && attached.isEmpty()) { toast("풀 문제나 배우고 싶은 것을 적어 주세요."); return; }
        if (brain == null && ScopedModels.main(store).readyEngine().isBlank()) { toast("메인 대화에서 쓸 AI를 먼저 연결해 주세요."); return; }
        List<JSONObject> sending = new ArrayList<>(attached); attached.clear(); paintFiles();
        Bitmap drawn = null;
        if (handDrawn()) { float bottom = Board.bottom(board.items(), board.measure) + 20; drawn = board.render(Board.WIDTH, Math.max(0, bottom - SNAPSHOT_UNITS), bottom); }
        Bitmap snapshot = drawn;
        input.setText(""); InputMethodManager keyboard = getSystemService(InputMethodManager.class); if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        lesson(BoardTeacher.THINKING, 0, 0, "");
        background(() -> {
            List<MainRequest.Attachment> out = new ArrayList<>(); StringBuilder text = new StringBuilder(words); String problem = null; long bytes = 0;
            try {
                for (JSONObject file : sending) {
                    java.io.File stored = store.attachmentFile(file.optString("id"));
                    if ("text".equals(file.optString("kind"))) {
                        String body = file.optString("text"); if (body.isBlank() && stored.isFile()) body = new String(java.nio.file.Files.readAllBytes(stored.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                        text.append("\n\n[첨부한 글: ").append(file.optString("name")).append("]\n").append(body.length() > 60_000 ? body.substring(0, 60_000) : body);
                    } else {
                        bytes += stored.length(); if (bytes > FILE_BYTES) throw new IllegalArgumentException("첨부한 자료가 너무 커요. 12MB까지 보낼 수 있어요.");
                        out.add(new MainRequest.Attachment(file.optString("name"), WorkBook.PDF.equals(file.optString("kind")) ? "application/pdf" : file.optString("mime"), Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(stored.toPath()))));
                    }
                }
                if (snapshot != null) {
                    ByteArrayOutputStream png = new ByteArrayOutputStream(); snapshot.compress(Bitmap.CompressFormat.PNG, 100, png); snapshot.recycle();
                    out.add(new MainRequest.Attachment("내가 판에 쓴 것.png", "image/png", Base64.getEncoder().encodeToString(png.toByteArray())));
                    text.append("\n\n(마지막 사진은 지금 판의 모습이에요. 내가 손으로 쓴 것도 보고 답해 주세요.)");
                }
            } catch (Exception failed) { problem = failed.getMessage() == null || failed.getMessage().isBlank() ? "첨부한 자료를 읽지 못했어요." : failed.getMessage(); }
            finally { for (JSONObject file : sending) store.attachmentFile(file.optString("id")).delete(); }
            String why = problem, request = text.toString();
            foreground(() -> {
                if (destroyed) return;
                if (why != null) { lesson(BoardTeacher.IDLE, 0, 0, ""); toast(why); return; }
                teacher.ask(request, out, "Korean");
            });
        });
    }
    /** The lesson from the model chosen in the main chat. */
    private String askModel(String instructions, String request, List<MainRequest.Attachment> with, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        ScopedModels models = ScopedModels.main(store);
        if (models.readyEngine().isBlank()) throw new IllegalStateException("메인 대화에서 쓸 AI를 먼저 연결해 주세요.");
        return MainAdapters.send(store, models.snapshot(), MainRequest.of(instructions, request).files(with), () -> {}, cancel).text();
    }

    /** The whole board as one picture in the phone's Pictures, then the share sheet. */
    private void share() {
        float bottom = Board.bottom(board.items(), board.measure);
        if (bottom <= 0) { toast("판이 비어 있어요."); return; }
        float height = bottom + Board.MARGIN; int width = height > 12_000 ? 700 : 1400;
        Bitmap picture = board.render(width, 0, Math.min(height, 24_000));
        String name = (title.isBlank() ? "판서" : title.replaceAll("[\\\\/:*?\"<>|]", " ")) + "-" + System.currentTimeMillis() + ".png";
        background(() -> {
            Uri saved = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name); values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Brand.NAME);
                saved = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (saved == null) throw new java.io.IOException("no place");
                try (OutputStream out = getContentResolver().openOutputStream(saved)) { if (out == null || !picture.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("not written"); }
            } catch (Exception failed) { if (saved != null) { try { getContentResolver().delete(saved, null, null); } catch (RuntimeException ignored) { /* nothing was kept */ } } saved = null; }
            finally { picture.recycle(); }
            Uri uri = saved;
            foreground(() -> {
                if (destroyed) return;
                if (uri == null) { toast("사진으로 저장하지 못했어요."); return; }
                toast("사진 폴더에 저장했어요.");
                try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "판서 공유")); }
                catch (RuntimeException none) { /* saved; nothing to share with */ }
            });
        });
    }
}
