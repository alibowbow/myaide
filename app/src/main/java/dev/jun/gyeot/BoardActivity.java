package dev.jun.gyeot;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
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
import dev.jun.gyeot.core.BoardInk;
import dev.jun.gyeot.core.BoardPlot;
import dev.jun.gyeot.core.BoardWidget;
import dev.jun.gyeot.core.Jsons;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    static final String OPEN = "gyeot.board_id", ASK = "gyeot.board_ask", INTAKE = "gyeot.board_intake";
    static final int PICK = 71, FILES = 4, SNAPSHOT_UNITS = 3000, PDF_PAGES = 10, PAGE_PIXELS = 1600;
    /** 글자 크기: how much of the device's own text scale each choice is. */
    static final String[] TEXT_NAMES = {"작게", "보통", "크게", "아주 크게"}; static final float[] TEXT_LEVELS = {.8f, 1f, 1.25f, 1.55f};
    static final long FILE_BYTES = 12L * 1024 * 1024;
    /** The model and the voices; tests put their own here. */
    static BoardTeacher.Brain brain; static BoardVoice.Voices voices;
    /** Reading and writing files off the main thread; tests run it in place. */
    static Executor work;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject classroom=new JSONObject(); private BoardClassroom classroomPicker;
    private Ui ui; private AppStore store; private BoardStore boards; private MainNavigation navigation;
    private FrameLayout root; private BoardView board; private BoardTeacher teacher; private BoardVoice narration;
    private LinearLayout chrome, askBar, lessonBar, files, hint, topBar; private View gapView; private BoardPenShelf toolBar; private TextView hintLead, hintHow; private ImageView hintMark; private TextView titleView, caption, progress; private EditText input;
    private ImageView undo, redo, sound, pause, next, toolToggle, widthButton;
    private final List<ImageView> toolIcons = new ArrayList<>(), swatches = new ArrayList<>();
    private final List<String> toolIds = new ArrayList<>();
    private static final String[] QUICK_COLORS = {"ink", "rust", "teal", "ochre", "indigo"};
    private static final String[] MARKER_COLORS = {"yellow", "pink", "green", "blue", "purple"};
    private static final String[] MARK_IDS = {BoardInk.DOUBLE, BoardInk.DOTTED, BoardInk.WAVY, BoardInk.BRACKET, BoardInk.CHECK, BoardView.LINE, BoardView.ARROW, BoardView.RECT, BoardView.OVAL};
    private static final String[] MARK_NAMES = {"이중 밑줄", "점선", "물결 밑줄", "여백 괄호", "체크", "직선", "화살표", "네모", "타원"};
    private String boardId = "", title = ""; private final List<JSONObject> attached = new ArrayList<>();
    /** 음성 대화 with GPT or Grok about this board (VoiceBar, VoiceTargets.board): the send button starts it with nothing typed. */
    private VoiceBar voice; private ImageView sendButton;
    /** The board in front, for a spoken conversation that outlives one activity (rotation). */
    private static volatile java.lang.ref.WeakReference<BoardActivity> front = new java.lang.ref.WeakReference<>(null);
    static BoardActivity front() { return front.get(); }
    private boolean destroyed, preparing, exporting;
    private String previousTool; private BoardJobs jobs; private boolean resumed;
    private final BoardJobs.Listener jobListener = this::jobFinished; private Ui.Sheet sheet;
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
        if (ask == null || ask.isBlank() || busy()) return;
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
        if (android.os.Build.VERSION.SDK_INT < 35) getWindow().setDecorFitsSystemWindows(false);
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); jobs = ((GyeotApp) getApplication()).boardJobs(); boards = jobs.boards; jobs.watch(jobListener);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        board = new BoardView(this); root.addView(board, new FrameLayout.LayoutParams(-1, -1));
        board.listen(new BoardView.Listener() {
            @Override public void changed() { saveSoon(); paintHistory(); }
            @Override public void textAt(float x, float y) { writeText(x, y); }
            @Override public void tapped(int index, float x, float y) { tappedItem(index); }
            @Override public void full() { toast("이 판이 가득 찼어요. 새 판에서 계속해 주세요."); }
        });
        board.pictures(this::picture); board.textScale = textScale(); look();
        narration = new BoardVoice(this, store, voices);
        teacher = new BoardTeacher(board, narration, brain != null ? brain : this::askModel, new BoardTeacher.Watcher() {
            @Override public void state(String now, int step, int steps, String words) { lesson(now, step, steps, words); }
            @Override public void notice(String why) { toast(why); }
            @Override public void wrote(String lessonTitle) { if (lessonTitle != null && !lessonTitle.isBlank() && title.isBlank()) { title = lessonTitle; paintTitle(); } saveSoon(); paintHistory(); }
        });
        hint = ui.column(); hint.setGravity(Gravity.CENTER_HORIZONTAL); hint.setPadding(ui.dp(40), 0, ui.dp(40), ui.dp(60));
        ImageView mark = new ImageView(this); hintMark = mark; mark.setImageResource(R.drawable.ic_board); mark.setImageTintList(ColorStateList.valueOf(MUTED)); mark.setAlpha(.55f); hint.addView(mark, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView lead = ui.text("AI 선생님의 판서로 배워요", TITLE2, 700, TEXT); hintLead = lead; lead.setGravity(Gravity.CENTER); lead.setPadding(0, ui.dp(14), 0, ui.dp(6)); hint.addView(lead);
        TextView how = hintHow = ui.text("주제나 문제 사진을 보내세요. 아래 +에서 교과·단원이나 수능 기출을 골라 시작할 수도 있어요.", SUBHEAD, 450, MUTED); how.setGravity(Gravity.CENTER); how.setLineSpacing(ui.dp(3), 1); hint.addView(how);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2); hp.gravity = Gravity.CENTER; root.addView(hint, hp);
        teacher.textScale = textScale(); look();
        chrome = ui.column(); root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        // The page starts under the top bar and its writing is followed above the bars at the bottom.
        chrome.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (topBar != null && gapView != null) board.insets(chrome.getPaddingTop() + ui.dp(8), Math.max(0, chrome.getHeight() - gapView.getBottom())); });
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            boolean typing = insets.isVisible(WindowInsets.Type.ime());
            chrome.setPadding(ui.dp(12) + bars.left, ui.dp(8) + bars.top, ui.dp(12) + bars.right, ui.dp(10) + Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            // While typing, the tools make room for the keyboard: the question is what matters.
            if (toolBar != null) toolBar.setVisibility(typing || busy() || !tools() ? View.GONE : View.VISIBLE);
            return insets;
        });
        build();
        navigation = new MainNavigation(this, root, "board", target -> MainNavigation.open(this, target));
        navigation.bar(topBar);
        setContentView(navigation); root.requestApplyInsets();
        String wanted = state != null ? state.getString("board", "") : getIntent().getStringExtra(OPEN);
        Bundle position = state;
        if (position == null && wanted == null && !getIntent().hasExtra(ASK)) {
            var saved = getSharedPreferences("board_view", MODE_PRIVATE);
            wanted = saved.getString("board", "");
            if (!wanted.isBlank()) {
                position = new Bundle(); position.putString("board", wanted);
                for (String key : List.of("boardTop", "boardLeft", "boardZoom"))
                    position.putFloat(key, saved.getFloat(key, key.equals("boardZoom") ? 1 : 0));
            }
        }
        open(wanted == null ? "" : wanted, position);
        String ask = state == null ? getIntent().getStringExtra(ASK) : null;
        BoardIntake.Item intake = state == null ? BoardIntake.take(getIntent().getStringExtra(INTAKE)) : null;
        if (intake != null) main.post(() -> fromVoice(intake));
        else if (ask != null && !ask.isBlank()) main.post(() -> asked(ask));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String id = intent.getStringExtra(OPEN), ask = intent.getStringExtra(ASK);
        if (id != null && !id.equals(boardId)) { teacher.stop(); saveNow(); open(id); }
        BoardIntake.Item intake = BoardIntake.take(intent.getStringExtra(INTAKE));
        if (intake != null) fromVoice(intake); else asked(ask);
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("board", boardId); board.saveViewport(state); state.putString("boardDraft", input.getText().toString()); }
    @Override protected void onResume() { super.onResume(); resumed = true; AppNavigator.inFront("board"); front = new java.lang.ref.WeakReference<>(this); if (voice != null) voice.attach();
        String error = jobs.takeError(boardId); if (error != null) toast(error); }
    @Override protected void onPause() {
        resumed = false; if (voice != null) voice.detach();
        if (!boardId.isBlank() && board.getWidth() > 0) {
            Bundle position = new Bundle(); board.saveViewport(position);
            var saved = getSharedPreferences("board_view", MODE_PRIVATE).edit().putString("board", boardId);
            for (String key : List.of("boardTop", "boardLeft", "boardZoom")) saved.putFloat(key, position.getFloat(key));
            saved.apply();
        }
        if (teacher.state().equals(BoardTeacher.PLAYING)) teacher.pause(); main.removeCallbacks(save); saveNow(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true; if (voice != null) voice.detach(); jobs.unwatch(jobListener); main.removeCallbacks(save); if (sheet != null && sheet.isShowing()) sheet.dismiss();
        teacher.close(); dropFiles(); worker.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (navigation != null && navigation.close()) return;
        if (examBusy) { stopLesson(); return; }
        if (teacher.busy()) { teacher.stop(); return; }
        super.onBackPressed();
    }

    /** The board's text scale: smaller on a wide screen (the page is as wide as the screen), times the user's 글자 크기. */
    private float textScale() {
        float base = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? .5f : .85f;
        return base * TEXT_LEVELS[Math.max(0, Math.min(TEXT_LEVELS.length - 1, store.settings.getInt("boardText", 1)))];
    }
    /** A picture of the board from its file, no larger than the page is ever drawn. */
    private Bitmap picture(String name) {
        java.io.File file = boards.picture(name); if (file == null || !file.isFile()) return null;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds);
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2400) options.inSampleSize *= 2;
        return android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
    }
    /** The hand tapped something: a lesson's line can be heard again or explained more, a picture or one's own writing taken off. */
    private void tappedItem(int index) {
        JSONObject item = index < 0 ? null : board.items().optJSONObject(index); if (item == null || busy()) return;
        boolean picture = Board.IMAGE.equals(item.optString("t")), written = Board.TEXT.equals(item.optString("t")); String lesson = item.optString("ls"), words = item.optString("text");
        boolean widget = Board.WIDGET.equals(item.optString("t"));
        String shown = widget ? BoardWidget.name(item.optString("kind")) : written ? (words.length() > 40 ? words.substring(0, 40) + "…" : words) : picture ? "붙인 자료" : "그린 것";
        Ui.Sheet menu = ui.selectionSheet(shown, lesson.isEmpty() ? "" : "AI가 판서한 부분이에요"); menu.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        if (widget) ui.addRow(group, ui.listRow(R.drawable.ic_pen, ACCENT, "편집·조작", BoardWidget.description(item), ui.chevron(), () -> {
            menu.dismiss(); editWidget(item.optString("kind"), item);
        }), true);
        if(widget)ui.addRow(group,ui.listRow(R.drawable.ic_sparkle,INDIGO,"AI로 수정","바꿀 부분을 말하면 이 자료에 반영해요",ui.chevron(),()->{
            menu.dismiss();
            Ui.Sheet command=ui.selectionSheet("AI로 수정",shown);
            EditText edit=ui.input("예: 두 번째 점을 강조해 줘",2,1200);command.body.addView(edit);
            command.primary.setText("수정 요청");command.primary.setOnClickListener(v->{String change=edit.getText().toString().strip();if(change.isEmpty())return;command.dismiss();input.setText("판서 자료 "+item.optString("wid")+" 수정: "+change);send();});
            show(command);
        }),true);
        if (!lesson.isEmpty()) {
            ui.addRow(group, ui.listRow(R.drawable.ic_play, ACCENT, "여기부터 다시 듣기", "이 단계부터 다시 써 가며 설명해요", null, () -> { menu.dismiss(); teacher.replay(lesson, item.optInt("st")); }), true);
            if (written || widget) ui.addRow(group, ui.listRow(R.drawable.ic_sparkle, INDIGO, "이 부분 더 설명해 줘", "더 쉽게, 다른 방법으로 이어서 판서해요", null, () -> { menu.dismiss(); input.setText("방금 판서에서 ‘" + (widget ? BoardWidget.description(item) : words) + "’ 부분이 이해가 안 돼. 더 쉽게, 다른 방법으로 설명해 줘."); send(); }), true);
        }
        if (picture) {
            // Larger or smaller, with whatever is written on it; what is under it moves to make room.
            ui.addRow(group, ui.listRow(R.drawable.ic_add, BLUE, "크게", "위에 쓴 것도 함께 커져요", null, () -> { menu.dismiss(); board.replace(Board.resized(board.items(), index, 1.25f, board.measure)); paintHistory(); }), true);
            ui.addRow(group, ui.listRow(R.drawable.ic_chevron_down, TEAL, "작게", "위에 쓴 것도 함께 작아져요", null, () -> { menu.dismiss(); board.replace(Board.resized(board.items(), index, .8f, board.measure)); paintHistory(); }), true);
        }
        ui.addRow(group, ui.listRow(R.drawable.ic_eraser, RED, picture ? "이 자료 지우기" : "지우기", "되돌리기로 다시 살릴 수 있어요", null, () -> { menu.dismiss(); board.remove(index); paintHistory(); paintFiles(); }), true);
        menu.body.addView(group); show(menu);
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
        toolToggle = icon(R.drawable.ic_pen, "필기 도구 열기", () -> showTools(!tools()));
        top.addView(toolToggle, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        top.addView(icon(R.drawable.ic_layers, "판 목록", this::listBoards), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        top.addView(icon(R.drawable.ic_more, "더 보기", this::more), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        topBar = top;
        View gap = new View(this); gapView = gap; gap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); chrome.addView(gap, new LinearLayout.LayoutParams(-1, 0, 1));

        caption = ui.text("", CALLOUT, 500, TEXT); caption.setMaxLines(4); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setLineSpacing(ui.dp(3), 1);
        caption.setBackground(surface(R_CARD)); ui.lift(caption, 8); caption.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); caption.setVisibility(View.GONE);
        caption.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        caption.setOnClickListener(v -> {
            Ui.Sheet words = ui.selectionSheet("이 단계의 설명", "");
            TextView all = ui.text(caption.getText().toString(), BODY, 450, TEXT);
            all.setTextIsSelectable(true); all.setLineSpacing(ui.dp(3), 1);
            if (teacher.state().equals(BoardTeacher.PLAYING)) teacher.pause();
            words.body.addView(all); show(words);
        });
        ui.add(chrome, caption, 8);

        lessonBar = ui.row(); lessonBar.setGravity(Gravity.CENTER_VERTICAL); lessonBar.setBackground(bar()); ui.lift(lessonBar, 8); lessonBar.setPadding(ui.dp(18), 0, ui.dp(6), 0); lessonBar.setVisibility(View.GONE);
        progress = ui.text("", SUBHEAD, 620, TEXT); progress.setSingleLine(true); lessonBar.addView(progress, new LinearLayout.LayoutParams(0, -2, 1));
        sound = icon(R.drawable.ic_sound, "소리 끄기", () -> { teacher.mute(!teacher.muted()); paintSound(); });
        pause = icon(R.drawable.ic_pause, "잠깐 멈추기", () -> { if (teacher.state().equals(BoardTeacher.PAUSED)) teacher.resume(); else teacher.pause(); });
        next = icon(R.drawable.ic_next, "다음 단계로", () -> teacher.skip());
        ImageView stop = icon(R.drawable.ic_stop, "수업 끝내기", this::stopLesson); stop.setImageTintList(ColorStateList.valueOf(RED));
        for (ImageView control : new ImageView[]{sound, pause, next, stop}) lessonBar.addView(control, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(52)));
        ui.add(chrome, lessonBar, 8);

        files = ui.row(); HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.addView(files);
        ui.add(chrome, strip, 6);

        askBar = ui.row(); askBar.setGravity(Gravity.CENTER_VERTICAL); askBar.setBackground(surface(R_SHEET)); ui.lift(askBar, 8); askBar.setPadding(ui.dp(4), ui.dp(2), ui.dp(6), ui.dp(2));
        ImageView clip = icon(R.drawable.ic_clip, "문제나 자료 첨부", this::pick); clip.setImageTintList(ColorStateList.valueOf(MUTED));
        askBar.addView(clip, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        ImageView insert = icon(R.drawable.ic_add, "시각 자료 넣기", this::visuals);
        insert.setImageTintList(ColorStateList.valueOf(BLUE));
        askBar.addView(insert, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        input = new EditText(this); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(BODY); input.setHint("풀 문제나 배우고 싶은 것");
        input.setMaxLines(4); input.setPadding(ui.dp(4), ui.dp(10), ui.dp(8), ui.dp(10)); input.setContentDescription("판서로 물어볼 말");
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        askBar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        sendButton = ui.iconButton(R.drawable.ic_send, ACCENT, ON_ACCENT, "판서로 설명 듣기", () -> { if (offersVoice()) voice.start(); else send(); });
        sendButton.setOnLongClickListener(v -> { if (!offersVoice()) return false; voice.settings(""); return true; });
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable t) { sendLooks(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)); sp.leftMargin = ui.dp(2); askBar.addView(sendButton, sp);
        toolBar = new BoardPenShelf(this);
        toolBar.setBackground(surface(R_SHEET)); ui.lift(toolBar, 5);
        toolBar.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2));
        tool(BoardView.HAND, R.drawable.ic_hand, "판 움직이기");
        tool(BoardInk.PEN, R.drawable.ic_pen, "파인라이너");
        tool(BoardInk.PENCIL, R.drawable.ic_pen, "연필");
        tool(BoardInk.BRUSH, R.drawable.ic_pen, "붓펜");
        tool(BoardInk.MARKER, R.drawable.ic_marker, "형광펜");
        tool(BoardView.ERASER, R.drawable.ic_eraser, "지우개");
        tool("shape", R.drawable.ic_shapes, "기호와 도형");
        tool(BoardView.TEXT, R.drawable.ic_text, "글자");
        for (int i=0; i<QUICK_COLORS.length; i++) {
            int slot = i;
            ImageView dot = icon(R.drawable.ic_pen, colorName(QUICK_COLORS[i]), () -> setInkColor(quickColors()[slot]));
            dot.setImageTintList(null); dot.setPadding(ui.dp(2),ui.dp(2),ui.dp(2),ui.dp(2));
            swatches.add(dot); shelfButton(toolBar.options, dot);
            if (i == 3) toolBar.optionalSwatch = dot;
        }
        shelfButton(toolBar.options, icon(R.drawable.ic_more, "모든 잉크 색", this::colors));
        widthButton = icon(R.drawable.ic_tune, "획 굵기", this::widths);
        shelfButton(toolBar.options, widthButton);
        undo = icon(R.drawable.ic_undo, "되돌리기", () -> { board.undo(); paintHistory(); });
        redo = icon(R.drawable.ic_redo, "다시 하기", () -> { board.redo(); paintHistory(); });
        shelfButton(toolBar.options, undo); shelfButton(toolBar.options, redo);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2,-2); pp.gravity = Gravity.CENTER_HORIZONTAL;
        chrome.addView(toolBar, pp); ui.add(chrome, askBar, 6);
        voice = new VoiceBar(this, store, ui, "board", () -> VoiceTargets.board(getApplicationContext())); voice.onToggle(this::fitAsk);
        ui.add(chrome, voice.bar(), 6); sendLooks();
        if (tools()) resumeInk();
        paintTools(); paintHistory(); paintSound(); toolBar.setVisibility(tools() ? View.VISIBLE : View.GONE);
    }
    private void shelfButton(LinearLayout row, View view) {
        row.addView(view, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
    }
    private void tool(String id, int drawable, String name) {
        ImageView view = icon(drawable, name, () -> choose(id));
        if (BoardInk.freehand(id)) {
            view.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
            view.setOnLongClickListener(v -> { activate(id); widths(); return true; });
        }
        toolIds.add(id); toolIcons.add(view); shelfButton(toolBar.pens, view);
    }
    private boolean isShape(String id) { return java.util.Arrays.asList(MARK_IDS).contains(id); }
    private String inkKey(String tool) { return isShape(tool) ? "shape" : tool; }
    private String inkColor(String tool) {
        String fallback = switch (tool) { case BoardInk.BRUSH -> "indigo"; case BoardInk.MARKER -> "yellow"; case "shape" -> "rust"; default -> Board.INK; };
        return Board.color(store.settings.getString("boardInkColor:" + tool, fallback));
    }
    private void activate(String id) {
        String key = inkKey(id);
        board.tool(id); board.color(inkColor(key));
        board.penWidth(store.settings.getFloat("boardInkWidth:" + key, BoardView.WIDTHS[1]));
        store.settings.edit().putString("boardInkTool", id).apply();
        paintTools();
    }
    private void resumeInk() {
        String saved = store.settings.getString("boardInkTool", BoardInk.PEN);
        activate(isShape(saved) || toolIds.contains(saved) && !"shape".equals(saved) ? saved : BoardInk.PEN);
    }
    private boolean writesInk() { return !BoardView.HAND.equals(board.tool()) && !BoardView.ERASER.equals(board.tool()); }
    private String[] quickColors() { return BoardInk.MARKER.equals(board.tool()) ? MARKER_COLORS : QUICK_COLORS; }
    private void setInkColor(String name) {
        if (!writesInk()) activate(BoardInk.PEN);
        board.color(name);
        store.settings.edit().putString("boardInkColor:" + inkKey(board.tool()), name).apply();
        paintTools();
    }
    private void choose(String id) {
        if ("shape".equals(id)) shapes();
        else if (id.equals(board.tool()) && BoardInk.freehand(id)) widths();
        else activate(id);
    }
    private void paintTools() {
        String now = board.tool();
        toolToggle.setSelected(tools());
        String action = tools() ? "필기 도구 닫기" : "필기 도구 열기";
        toolToggle.setContentDescription(action); toolToggle.setTooltipText(action);
        toolToggle.setImageTintList(ColorStateList.valueOf(tools() ? ACCENT : TEXT));
        for (int i = 0; i < toolIcons.size(); i++) {
            String id = toolIds.get(i); boolean on = id.equals(now) || id.equals("shape") && isShape(now); ImageView view = toolIcons.get(i);
            view.setBackground(on ? ui.shape(ACCENT_SOFT, 13) : null); view.setSelected(on);
            if (BoardInk.freehand(id) || on && "shape".equals(id)) {
                view.setImageTintList(null);
                view.setImageDrawable(new BoardInkIcon("shape".equals(id) ? now : id,board.ink(inkColor(id)),TEXT,on,0));
            } else {
                if ("shape".equals(id)) view.setImageResource(R.drawable.ic_shapes);
                view.setImageTintList(ColorStateList.valueOf(on ? ACCENT : TEXT));
            }
        }
        for (int i = 0; i < swatches.size(); i++) {
            String name = quickColors()[i]; boolean selected = writesInk() && name.equals(board.color());
            ImageView dot = swatches.get(i); dot.setSelected(selected);
            dot.setContentDescription(colorName(name)); dot.setTooltipText(colorName(name));
            dot.setImageDrawable(new BoardInkIcon("swatch",board.ink(name),TEXT,selected,0));
        }
        boolean adjustable = BoardInk.freehand(now) || isShape(now);
        widthButton.setEnabled(adjustable); widthButton.setAlpha(adjustable ? 1f : .35f);
    }
    private void paintHistory() {
        if (hint != null) hint.setVisibility(board.items().length() == 0 && !busy() ? View.VISIBLE : View.GONE);
        undo.setEnabled(board.canUndo()); undo.setAlpha(board.canUndo() ? 1f : .3f); redo.setEnabled(board.canRedo()); redo.setAlpha(board.canRedo() ? 1f : .3f);
        toolToggle.setVisibility(busy() ? View.GONE : View.VISIBLE);
    }
    /** Whether the tools for writing by hand are out. They are put away unless asked for: the board is the AI's to write on. */
    private boolean tools() { return store.settings.getBoolean("boardTools", false); }
    private void showTools(boolean on) {
        if (busy()) return;
        store.settings.edit().putBoolean("boardTools", on).apply();
        if (on) resumeInk(); else board.tool(BoardView.HAND);
        paintTools(); paintHistory(); toolBar.setVisibility(on && !busy() ? View.VISIBLE : View.GONE); root.requestApplyInsets();
    }
    /** The board's look as it is chosen: notes or a blackboard, and the letters new writing is in (chalk is always written by hand). */
    private void look() {
        board.style(store.settings.getString("boardStyle", BoardView.NOTE));
        boolean hand = store.settings.getBoolean("boardHand", true);
        board.handwriting(hand);
        if (teacher != null) { teacher.hand = board.handwriting(); teacher.chalk = board.chalk(); }
        if (root != null) root.setBackgroundColor(board.paper());
        if (toolBar != null) paintTools();
        if (hintLead != null) { hintLead.setTextColor(board.ink(Board.INK)); hintHow.setTextColor((board.ink(Board.INK) & 0x00ffffff) | 0xa0000000); hintMark.setImageTintList(ColorStateList.valueOf((board.ink(Board.INK) & 0x00ffffff) | 0x90000000)); }
    }
    private void paintSound() { sound.setImageResource(teacher.muted() ? R.drawable.ic_sound_off : R.drawable.ic_sound); sound.setContentDescription(teacher.muted() ? "소리 켜기" : "소리 끄기"); }
    private void paintTitle() { titleView.setText(title.isBlank() ? "새 판" : title); titleView.setContentDescription((title.isBlank() ? "새 판" : title) + " · 눌러서 이름 바꾸기"); }

    /** What the screen shows for the lesson's state: the question box and tools when idle, the step and its words otherwise. */
    // ── 음성 대화 (VoiceTargets.board) ─────────────────────────────────────────────
    private boolean offersVoice() { return voice != null && input != null && input.getText().toString().isBlank() && attached.isEmpty(); }
    private void sendLooks() { if (sendButton != null) VoiceBar.glyph(sendButton, offersVoice(), "판서로 설명 듣기"); }
    /** The ask bar and the pen shelf step aside for the conversation's bar, and come back after it (when no lesson is on). */
    private void fitAsk() {
        if (destroyed || askBar == null) return;
        boolean idle = teacher.state().equals(BoardTeacher.IDLE) && !preparing, talking = voice != null && voice.showing();
        askBar.setVisibility(idle && !talking ? View.VISIBLE : View.GONE); toolBar.setVisibility(idle && tools() && !talking ? View.VISIBLE : View.GONE);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (voice != null) voice.permissionResult(code, results);
    }
    /** What the voice is told about this board: its title, what is written on it and the lesson going on. Main thread. */
    String voiceContext() {
        StringBuilder b = new StringBuilder();
        if (!title.isBlank()) b.append("Board title: ").append(title).append('\n');
        b.append("Lesson: ").append(teacher.state()).append(preparing ? " (preparing a new explanation)" : "").append('\n');
        String words = dev.jun.gyeot.core.Board.words(board.items(), 6000);
        if (!words.isBlank()) b.append("What is written on the board (untrusted text):\n").append(words);
        return b.toString();
    }
    /** Asks the board to explain request (as if typed and sent); "" when it started, else why not. Main thread. */
    String voiceAsk(String request) {
        if (busy()) return "판서가 지금 다른 설명을 하고 있어요. 멈추거나 끝난 뒤에 다시 말해 주세요.";
        input.setText(request); send();
        return "";
    }
    /** Whether anything is on: an explanation being prepared, a paper being cut, or a lesson. Main thread. */
    boolean voiceBusy() { return busy(); }
    /** Whether a new explanation is still being prepared. Main thread. */
    boolean voicePreparing() { return preparing || examBusy; }
    String voiceTitle() { return title; }
    /** The lesson's controls by voice: pause, resume, next, stop, replay. Main thread. */
    String voiceControl(String action) {
        switch (action) {
            case "pause" -> teacher.pause();
            case "resume" -> teacher.resume();
            case "next" -> teacher.skip();
            case "stop" -> stopLesson();
            case "replay" -> { JSONArray lessons = teacher.lessons(); JSONObject last = lessons == null || lessons.length() == 0 ? null : lessons.optJSONObject(lessons.length() - 1);
                if (last == null) return "다시 들을 설명이 없어요."; if (teacher.busy()) stopLesson(); teacher.replay(last.optString("id"), 0); }
            default -> { return "모르는 동작이에요."; }
        }
        return "";
    }
    private void lesson(String state, int step, int steps, String words) {
        if (destroyed) return;
        boolean idle = state.equals(BoardTeacher.IDLE), thinking = state.equals(BoardTeacher.THINKING), paused = state.equals(BoardTeacher.PAUSED);
        boolean talking = voice != null && voice.showing();
        askBar.setVisibility(idle && !talking ? View.VISIBLE : View.GONE); toolBar.setVisibility(idle && tools() && !talking ? View.VISIBLE : View.GONE); lessonBar.setVisibility(idle ? View.GONE : View.VISIBLE);
        // While the board reads its lesson aloud, a spoken conversation here does not hear it as the user.
        VoiceChat chat = store.voiceChat; if (chat != null && "board".equals(chat.target.surface())) chat.hold(state.equals(BoardTeacher.PLAYING));
        if (!idle && previousTool == null) { previousTool = board.tool(); board.tool(BoardView.HAND); }
        if (idle && previousTool != null) {
            board.tool(tools() ? previousTool : BoardView.HAND); previousTool = null; paintTools();
        }
        progress.setText(thinking ? "판서를 준비하는 중…" : (paused ? "멈춤 · " : "") + step + " / " + steps);
        for (ImageView control : new ImageView[]{sound, pause, next}) control.setVisibility(thinking ? View.GONE : View.VISIBLE);
        pause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause); pause.setContentDescription(paused ? "이어서 하기" : "잠깐 멈추기");
        caption.setVisibility(!idle && !thinking && !words.isBlank() ? View.VISIBLE : View.GONE); caption.setText(words);
        if (idle) root.requestApplyInsets();
        paintHistory(); paintFiles();
    }

    // ── Boards ──────────────────────────────────────────────────────────────────────────────────────────

    /** Shows the board with this id, else the newest one, else a new one. */
    private void open(String id) { open(id, null); }
    private void open(String id, Bundle saved) {
        BoardStore.Entry entry = id.isBlank() ? null : boards.entry(id);
        if (entry == null) { List<BoardStore.Entry> all = boards.list(); entry = all.isEmpty() ? null : all.get(0); }
        if (entry == null) { try { entry = boards.create("", System.currentTimeMillis()); } catch (Exception failed) { toast(failed.getMessage()); finish(); return; } }
        BoardStore.Doc doc = boards.load(entry.id());
        if (!boardId.isBlank() && !boardId.equals(entry.id())) { dropFiles(); input.setText(""); }
        boardId = entry.id(); title = entry.title(); classroom=doc.classroom();
        preparing = jobs.running(boardId);
        board.show(BoardWidget.reflowGraphs(dev.jun.gyeot.core.BoardField.restore(doc.items(),board.measure), board.measure));
        input.setHint(classroom.length()==0?"풀 문제나 배우고 싶은 것":BoardClassroom.summary(classroom)); teacher.lessons(doc.lessons()); paintTitle(); paintHistory(); paintFiles();
        lesson(preparing ? BoardTeacher.THINKING : BoardTeacher.IDLE, 0, 0, "");
        if (resumed) { String error = jobs.takeError(boardId); if (error != null) toast(error); }
        String opened = boardId;
        boolean returning = saved != null && opened.equals(saved.getString("board"));
        if (returning) input.setText(saved.getString("boardDraft", ""));
        board.post(() -> {
            if (!opened.equals(boardId) || destroyed) return;
            if (!returning || !board.restoreViewport(saved)) board.scrollToEnd();
        });
    }
    private void saveSoon() { main.removeCallbacks(save); main.postDelayed(save, 600); }
    private void saveNow() {
        if (boardId.isEmpty() || preparing || jobs.running(boardId)) return;
        String id = boardId, name = title; JSONObject context=BoardWidget.copy(classroom); JSONArray copy, lessons;
        try { copy = new JSONArray(board.items().toString()); lessons = new JSONArray(teacher.lessons().toString()); } catch (Exception impossible) { return; }
        (work == null ? jobs.disk : work).execute(() -> { try { boards.save(id, copy, lessons, context, name, System.currentTimeMillis()); } catch (Exception failed) { foreground(() -> { if (!destroyed) toast("판을 저장하지 못했어요."); }); } });
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
            ui.addRow(group, ui.listRow(R.drawable.ic_board, current ? ACCENT : MUTED, name, under, current ? null : ui.chevron(), () -> { list.dismiss(); if (!current) { teacher.stop(); saveNow(); open(entry.id()); } }), true);
        }
        list.body.addView(group);
        list.primary.setText("새 판"); list.primary.setOnClickListener(v -> { list.dismiss(); newBoard(); });
        show(list);
    }
    private void more() {
        Ui.Sheet menu = ui.selectionSheet("판서", ""); menu.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_add, ACCENT, "새 판", "빈 판에서 시작해요", null, () -> { menu.dismiss(); newBoard(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_file, ORANGE, "수능 기출 풀기", "들퍼레/수능 폴더의 문제지에서 연도·과목·번호로 골라요", ui.chevron(), () -> { menu.dismiss(); exams(null); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_pen, BLUE, "이름 바꾸기", title.isBlank() ? "아직 이름이 없어요" : title, null, () -> { menu.dismiss(); rename(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_share, TEAL, "사진으로 공유", "짧은 판은 한 장으로, 긴 판은 PDF로 저장해요", null, () -> { menu.dismiss(); share(false); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_text, TEAL, "판 내용 읽기", "글과 시각 자료의 데이터를 텍스트로 읽거나 복사해요", null, () -> { menu.dismiss(); readBoard(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_pages, BLUE, "PDF로 저장·공유", "긴 판도 페이지로 나눠 끝까지 저장해요", null, () -> { menu.dismiss(); share(true); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_eraser, ORANGE, "모두 지우기", "되돌리기로 다시 살릴 수 있어요", null, () -> { menu.dismiss(); stopLesson(); board.clear(); paintHistory(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_close, RED, "이 판 지우기", "지우면 되살릴 수 없어요", null, () -> { menu.dismiss(); deleteBoard(); }), true);
        menu.body.addView(group);
        menu.body.addView(ui.sectionHeader("글씨"));
        LinearLayout writing = ui.group(); int level = Math.max(0, Math.min(TEXT_NAMES.length - 1, store.settings.getInt("boardText", 1)));
        ui.addRow(writing, ui.listRow(R.drawable.ic_text, TEAL, "글자 크기", TEXT_NAMES[level] + " · 새로 쓰는 글부터 적용돼요", ui.chevron(), () -> { menu.dismiss(); textSizes(); }), true);
        ui.addRow(writing, ui.listRow(R.drawable.ic_pen, PINK, "글씨체", (board.handwriting() ? "손글씨" : "또박또박") + " · 새 제목과 직접 쓰는 글에 적용돼요", ui.chevron(), () -> { menu.dismiss(); fonts(); }), true);
        menu.body.addView(writing);
        menu.body.addView(ui.sectionHeader("판"));
        LinearLayout looks = ui.group();
        ui.addRow(looks, ui.listRow(R.drawable.ic_board, GREEN, "판 모양", board.chalk() ? "칠판 · 분필로 쓴 판서" : "노트 · 펜과 형광펜으로 정리한 노트", ui.chevron(), () -> { menu.dismiss(); styles(); }), true);
        ui.addRow(looks, ui.listRow(R.drawable.ic_hand, BLUE, "직접 쓰기 도구", tools() ? "켜짐 · 펜·형광펜·지우개·도형이 아래에 보여요" : "꺼짐 · 판은 AI가 쓰고, 나는 움직여 봐요", null, () -> { menu.dismiss(); showTools(!tools()); }), true);
        menu.body.addView(looks);
        menu.body.addView(ui.sectionHeader("AI"));
        // 판서's own choices: the model that prepares lessons, and the Live model and voice that read them. None of them changes the main chat or the assistant.
        LinearLayout ai = ui.group(); ScopedModels models = ScopedModels.board(store); String engine = models.engine();
        ui.addRow(ai, ui.listRow(R.drawable.ic_sparkle, INDIGO, "두뇌", (engine.isBlank() ? "AI를 골라 주세요" : WorkBook.author(engine) + " · " + new TeamRoster(store, models).label(engine, models.model(engine)) + (store.ready(engine) ? "" : " · 연결 필요")) + " · 판서를 준비하는 모델",
            ui.chevron(), () -> { menu.dismiss(); brains(); }), true);
        ui.addRow(ai, ui.listRow(R.drawable.ic_wave, PURPLE, "판서 음성", narration.sourceName(), ui.chevron(), () -> { menu.dismiss(); liveModels(); }), true);
        if (BoardVoice.LIVE.equals(narration.selected())) ui.addRow(ai, ui.listRow(R.drawable.ic_sound, PINK, "목소리", BoardVoice.voice(store).isBlank() ? "모델 기본 목소리" : BoardVoice.voice(store), ui.chevron(), () -> { menu.dismiss(); voiceNames(); }), true);
        else if (BoardVoice.realtimeKind(narration.selected())) { dev.jun.gyeot.core.RealtimeVoice.Kind kind = dev.jun.gyeot.core.RealtimeVoice.Kind.of(narration.selected());
            ui.addRow(ai, ui.listRow(R.drawable.ic_sound, PINK, "목소리", dev.jun.gyeot.core.RealtimeVoice.shown(kind, store.pickedVoice(kind)), ui.chevron(), () -> { menu.dismiss(); realtimeVoices(kind); }), true); }
        menu.body.addView(ai);
        TextView result = ui.footer(narration.status());
        java.util.function.Consumer<String> display = text -> { result.setText(text); result.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); };
        display.accept(narration.status()); narration.onStatus = display; menu.body.addView(result);
        menu.setOnDismissListener(dialog -> { if (narration.onStatus == display) narration.onStatus = null; });
        menu.body.addView(ui.footer("두뇌·Live 모델·목소리는 판서에서만 쓰는 선택이에요. 메인 대화와 음성 비서의 설정은 바뀌지 않아요."));
        menu.body.addView(ui.footer("문제 사진이나 PDF를 붙이면 판에 올라가고, AI가 그 위에 표시하며 풀어 줘요. 판서한 줄을 누르면 거기부터 다시 듣거나 더 물어볼 수 있어요. 한 손가락으로 판을 움직이고 두 손가락으로 키워요."));
        show(menu);
    }
    private void readBoard() {
        String words = Board.words(board.items(), 60_000);
        if (words.isBlank()) { toast("판에 읽을 글이 없어요."); return; }
        Ui.Sheet copy = ui.selectionSheet("판 내용", "");
        TextView text = ui.text(words, BODY, 450, TEXT); text.setTextIsSelectable(true);
        text.setLineSpacing(ui.dp(3), 1); copy.body.addView(text);
        copy.primary.setText("복사");
        copy.primary.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("판서", words));
            copy.dismiss();
        });
        show(copy);
    }

    private void rename() {
        if (preparing) return;
        Ui.Sheet edit = ui.selectionSheet("판 이름", "");
        EditText name = ui.input("예: 이차방정식", 1, BoardStore.TITLE); name.setText(title); name.setSelection(name.getText().length()); name.setContentDescription("판 이름");
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(-1, -2); around.leftMargin = around.rightMargin = ui.dp(16); around.bottomMargin = ui.dp(12); edit.body.addView(name, around);
        edit.primary.setText("저장"); edit.primary.setOnClickListener(v -> { title = name.getText().toString().replaceAll("\\s+", " ").strip(); paintTitle(); saveSoon(); edit.dismiss(); });
        show(edit);
    }
    private void deleteBoard() {
        show(ui.confirm("‘" + (title.isBlank() ? "새 판" : title) + "’을(를) 지울까요? 되살릴 수 없어요.", "판 지우기", () -> {
            stopLesson(); main.removeCallbacks(save); String id = boardId; boardId = "";
            try { boards.delete(id); } catch (Exception failed) { toast("판을 지우지 못했어요."); }
            open("");
        }));
    }

    // ── Tools that ask ──────────────────────────────────────────────────────────────────────────────────

    private static String colorName(String name) {
        return switch (name) {
            case "rust" -> "적갈색"; case "teal" -> "청록"; case "ochre" -> "황토";
            case "indigo" -> "남색"; case "red" -> "빨강"; case "pink" -> "분홍"; case "orange" -> "주황";
            case "yellow" -> "노랑"; case "green" -> "초록"; case "blue" -> "파랑"; case "purple" -> "보라"; default -> "기본 잉크";
        };
    }
    private void colors() {
        Ui.Sheet pick = ui.selectionSheet("잉크 색", ""); pick.primary.setVisibility(View.GONE);
        LinearLayout row = null;
        for (int i = 0; i < Board.COLORS.length; i++) {
            if (i % 4 == 0) { row = ui.row(); pick.body.addView(row); }
            String color = Board.COLORS[i]; boolean on = writesInk() && color.equals(board.color());
            inkChoice(row, "swatch", colorName(color), board.ink(color), on, 0, () -> { setInkColor(color); pick.dismiss(); });
        }
        show(pick);
    }
    private void widths() {
        Ui.Sheet pick = ui.selectionSheet("획 굵기", ""); pick.primary.setVisibility(View.GONE);
        String[] names = {"가늘게", "보통", "굵게"}; LinearLayout group = ui.row();
        for (int i = 0; i < BoardView.WIDTHS.length; i++) {
            float width = BoardView.WIDTHS[i]; boolean on = width == board.penWidth();
            inkChoice(group, "width", names[i], board.ink(board.color()), on, width, () -> {
                board.penWidth(width);
                store.settings.edit().putFloat("boardInkWidth:" + inkKey(board.tool()), width).apply();
                pick.dismiss();
            });
        }
        pick.body.addView(group); show(pick);
    }
    private void inkChoice(LinearLayout row, String kind, String name, int color, boolean selected, float width, Runnable choose) {
        LinearLayout option = ui.column(); option.setGravity(Gravity.CENTER);
        if (selected) option.setBackground(ui.shape(ACCENT_SOFT, 12));
        ImageView sample = new ImageView(this);
        sample.setImageDrawable(new BoardInkIcon(kind, color, TEXT, selected, width));
        option.addView(sample, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
        option.addView(ui.text(name, FOOTNOTE, selected ? 680 : 500, selected ? ACCENT : TEXT));
        option.setContentDescription(name); option.setSelected(selected); option.setFocusable(true);
        option.setOnClickListener(v -> choose.run()); Ui.pressable(option, true);
        row.addView(option, new LinearLayout.LayoutParams(0, ui.dp(69), 1));
    }
    private void shapes() {
        Ui.Sheet pick = ui.selectionSheet("기호와 도형", "끌어서 그리세요. 체크는 한 번 눌러도 찍혀요."); pick.primary.setVisibility(View.GONE);
        LinearLayout row = null;
        for (int i = 0; i < MARK_IDS.length; i++) {
            if (i % 3 == 0) { row = ui.row(); pick.body.addView(row); }
            String id = MARK_IDS[i];
            inkChoice(row, id, MARK_NAMES[i], board.ink(inkColor("shape")), id.equals(board.tool()), 0, () -> { activate(id); pick.dismiss(); });
        }
        pick.body.addView(ui.sectionHeader("계산해서 그리기"));
        LinearLayout made = ui.group();
        ui.addRow(made, ui.listRow(R.drawable.ic_board, BLUE, "함수 그래프", "식과 변수를 바꾸며 그래프를 그려요", ui.chevron(), () -> { pick.dismiss(); editWidget(BoardWidget.GRAPH, null); }), true);
        ui.addRow(made, ui.listRow(R.drawable.ic_layers, GREEN, "모든 시각 자료", "표·차트·평면 도형·입체도형", ui.chevron(), () -> { pick.dismiss(); visuals(); }), true);
        pick.body.addView(made); show(pick);
    }
    /** New material is appended. Editing material keeps its place and makes one undo record. */
    private float below() { float bottom = Board.bottom(board.items(), board.measure); return bottom <= 0 ? Board.MARGIN : bottom + 40; }

    private void visuals() {
        if (busy()) return;
        Ui.Sheet pick = ui.selectionSheet("시각 자료 넣기", "넣은 자료를 탭하면 다시 편집할 수 있어요."); pick.primary.setVisibility(View.GONE);
        LinearLayout curriculum=ui.group();
        ui.addRow(curriculum,ui.listRow(R.drawable.ic_book,INDIGO,"교과 수업",classroom.length()==0?"중1~고3 · 아홉 교과의 성취기준과 주제":BoardClassroom.summary(classroom),ui.chevron(),()->{pick.dismiss();classroom();}),false);
        ui.addRow(curriculum,ui.listRow(R.drawable.ic_file,ORANGE,"수능 기출 풀기","들퍼레/수능 폴더의 문제지에서 연도·과목·번호로 골라요",ui.chevron(),()->{pick.dismiss();exams(null);}),true);
        pick.body.addView(curriculum);pick.body.addView(ui.sectionHeader("판서 도구"));
        String[] kinds = {BoardWidget.GRAPH, BoardWidget.TABLE, BoardWidget.CHART, BoardWidget.GEOMETRY,
            BoardWidget.SOLID, BoardWidget.NUMBER_LINE, BoardWidget.FLOW, BoardWidget.TIMELINE, BoardWidget.TREE,BoardWidget.RELATION,BoardWidget.FIELD,BoardWidget.MAP,BoardWidget.CHEM,BoardWidget.DIAGRAM,BoardWidget.SCENE,BoardWidget.READING};
        String[] descriptions = {"함수식, 축 범위, 변수 슬라이더", "행과 셀을 입력하거나 붙여넣기", "막대·선·원형 차트",
            "꼭짓점을 끌어 길이와 각도 측정", "회전하고 전개도 펼치기", "범위와 열린·닫힌 점",
            "단계를 화살표로 연결", "날짜와 단계 정리", "중심 주제와 가지 정리","방정식·매개변수·극좌표 곡선","작은 소수에서 타원곡선의 점 표시","지도에 장소를 찍고 경로 연결","원자 수와 계수 검산","구조 모식도와 부위별 강조","물체와 이름표 연결 · 단계별 이동","지문과 사료의 원문·해설"};
        LinearLayout group = ui.group();
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            ui.addRow(group, ui.listRow(R.drawable.ic_shapes, i < 5 ? BLUE : TEAL, BoardWidget.name(kind), descriptions[i], ui.chevron(),
                () -> { pick.dismiss(); editWidget(kind, null); }), true);
        }
        pick.body.addView(group); show(pick);
    }

    private void classroom() {
        if(busy())return;
        try { if(classroomPicker==null)classroomPicker=new BoardClassroom(this,ui); }
        catch(java.io.IOException failed){toast("교과 기준 자료를 읽지 못했어요.");return;}
        String id=boardId;
        show(classroomPicker.open(classroom,context->{
            if(!id.equals(boardId) || busy())return;
            classroom=context;saveSoon();
            input.setHint(context.length()==0?"풀 문제나 배우고 싶은 것":BoardClassroom.summary(context));
            if(context.length()>0){
                if(input.getText().toString().isBlank())input.setText(BoardClassroom.summary(context)+" 수업을 시작해 줘.");
                send();
            }
        }));
    }

    private void editWidget(String kind, JSONObject before) {
        if (busy()) return;
        String id = boardId;
        BoardEditor editor = new BoardEditor(this, ui, board);
        show(editor.open(kind, BoardWidget.spec(before), spec -> {
            if (destroyed || !id.equals(boardId) || busy()) return;
            float top = before == null ? below() : (float) before.optDouble("y");
            float scale = before == null ? board.textScale : (float) before.optDouble("scale", board.textScale);
            JSONObject item = BoardWidget.make(kind, spec, top, scale, before == null ? Board.ME : before.optString("by"), board.measure);
            if (item == null) { toast("판에 추가할 내용이 없어요."); return; }
            if (before == null) {
                if (board.addAll(List.of(item))) board.scrollToEnd();
            } else board.replace(Board.editedWidget(board.items(), before, item, board.measure));
            paintHistory();
        }, BoardWidget.GRAPH.equals(kind) && formulaInk() ? this::readFormula : null));
    }

    private static boolean penStroke(JSONObject item) {
        return item != null && Board.ME.equals(item.optString("by")) && Board.STROKE.equals(item.optString("t")) && !item.optBoolean("hl") && !item.has("mark");
    }
    private boolean formulaInk() {
        JSONArray items = board.items();
        for (int i = items.length() - 1; i >= 0; i--) if (penStroke(items.optJSONObject(i))) return true;
        return false;
    }

    /** Transcribes only the newest cluster of handwritten strokes selected by the user's request. */
    private void readFormula(Consumer<String> done) {
        var marks = new JSONArray(); JSONArray items = board.items();
        float from = Float.POSITIVE_INFINITY, to = 0, anchor = Float.NaN;
        for (int i = items.length() - 1; i >= 0 && marks.length() < 36; i--) {
            JSONObject item = items.optJSONObject(i);
            if (!penStroke(item)) continue;
            float[] bounds = Board.bounds(item, board.measure);
            if (Float.isNaN(anchor)) anchor = (bounds[1] + bounds[3]) / 2;
            if (Math.abs((bounds[1] + bounds[3]) / 2 - anchor) > 185 || bounds[3] - bounds[1] > 330) continue;
            marks.put(item); from = Math.min(from, bounds[1]); to = Math.max(to, bounds[3]);
        }
        if (marks.length() == 0 || ScopedModels.board(store).readyEngine().isBlank()) { done.accept(null); return; }
        BoardView renderer = new BoardView(this); renderer.style(board.style()); renderer.show(marks);
        Bitmap image = renderer.render(1000, Math.max(-20, from - 28), to + 28);
        String id = boardId;
        background(() -> {
            String result = null;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new java.io.IOException("수식 이미지를 만들지 못했어요.");
                var attachment = new MainRequest.Attachment("손글씨 수식.png", "image/png", Base64.getEncoder().encodeToString(bytes.toByteArray()));
                String answer = askModel("Transcribe the handwritten function of x. Return exactly one JSON object with the key expr. "
                    + "Do not solve or guess unreadable symbols. Use + - * / ^, parentheses, sin, cos, tan, sqrt, abs, pi and single-letter parameters. "
                    + "Return an empty expr if you cannot read it.", "이 손글씨에 적힌 함수식을 읽어 주세요.", List.of(attachment), new AtomicReference<>());
                int first = answer.indexOf('{'), last = answer.lastIndexOf('}');
                if (first >= 0 && last > first) {
                    String expr = new JSONObject(answer.substring(first, last + 1)).optString("expr").strip();
                    BoardPlot.parameters(expr);
                    result = expr;
                }
            } catch (Exception ignored) { /* The editor keeps the typed expression on failure. */ }
            finally { image.recycle(); }
            String read = result;
            foreground(() -> { if (!destroyed && id.equals(boardId)) done.accept(read); });
        });
    }

    /** A choice of how lessons are read takes effect at once: a lesson that is on pauses, and reads on in the new voice when continued. */
    private void voiceChanged() { if (teacher.state().equals(BoardTeacher.PLAYING)) teacher.pause(); narration.reset(); }
    private void liveModels() {
        Ui.Sheet pick = ui.selectionSheet("판서 음성", "수업 설명을 읽을 음성을 선택하세요."); pick.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); String now = BoardVoice.liveModel(store);
        String selected = narration.selected(); boolean device = BoardVoice.DEVICE.equals(selected), realtime = BoardVoice.realtimeKind(selected);
        // GPT and Grok read in their realtime voices, with the sign-in or key the voice conversations use.
        for (dev.jun.gyeot.core.RealtimeVoice.Kind kind : dev.jun.gyeot.core.RealtimeVoice.Kind.values()) {
            boolean on = kind.name().equalsIgnoreCase(selected), usable = VoiceCredentials.usable(store, kind);
            ui.addRow(group, ui.listRow(R.drawable.ic_wave, on ? ACCENT : MUTED, kind.spoken(), usable ? "실시간 음성으로 단계의 말을 읽어요" : (kind == dev.jun.gyeot.core.RealtimeVoice.Kind.GPT ? "Codex 로그인" : "Grok 로그인") + "이 필요해요 · 설정 → 목소리와 대화",
                on ? ui.text("✓", 16, 700, ACCENT) : null, () -> {
                    if (!usable) { toast(kind.label + " 음성은 로그인한 뒤 고를 수 있어요. 설정 → 목소리와 대화에서 로그인해 주세요."); return; }
                    store.settings.edit().remove("boardDeviceVoice").putString("boardVoiceEngine", kind.name()).apply(); voiceChanged(); pick.dismiss(); }), true);
        }
        for (String[] model : dev.jun.gyeot.core.GeminiProtocol.LIVE_MODELS) {
            boolean on = !device && !realtime && model[0].equals(now);
            ui.addRow(group, ui.listRow(R.drawable.ic_wave, on ? ACCENT : MUTED, model[1].replace(" (기본)", ""), dev.jun.gyeot.core.GeminiProtocol.readsAsWritten(model[0]) ? "단계의 말을 한 번에 읽어요" : "한 문장씩 그대로 읽어요",
                on ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().remove("boardDeviceVoice").remove("boardVoiceEngine").putString("boardLiveModel", model[0]).apply(); voiceChanged(); pick.dismiss(); }), true);
        }
        ui.addRow(group, ui.listRow(R.drawable.ic_sound, device ? ACCENT : MUTED, "기기 TTS", "Android에 설정된 음성으로 읽어요",
            device ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putBoolean("boardDeviceVoice", true).remove("boardVoiceEngine").apply(); voiceChanged(); pick.dismiss(); }), true);
        pick.body.addView(group); show(pick);
    }
    /** A GPT or Grok voice for reading: the one the voice conversations and the voice assistant use too. */
    private void realtimeVoices(dev.jun.gyeot.core.RealtimeVoice.Kind kind) {
        Ui.Sheet pick = ui.selectionSheet("목소리", kind.label + " 목소리 · 음성 대화와 음성 비서에도 같이 적용돼요"); pick.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); String now = store.pickedVoice(kind);
        // GPT's newest (GPT-Live) first, then its Realtime voices.
        for (String[] option : dev.jun.gyeot.core.RealtimeVoice.voiceChoices(kind)) {
            boolean on = option[0].equals(now);
            ui.addRow(group, ui.listRow(R.drawable.ic_sound, on ? ACCENT : MUTED, option[1], "", on ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putString(AppStore.ASSISTANT_VOICE + kind.name(), option[0]).apply(); voiceChanged(); pick.dismiss(); }), true);
        }
        pick.body.addView(group); show(pick);
    }
    private void voiceNames() {
        Ui.Sheet pick = ui.selectionSheet("목소리", "판서의 설명을 읽는 목소리 · 음성 비서의 목소리는 바뀌지 않아요"); pick.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); String now = BoardVoice.voice(store);
        for (String name : dev.jun.gyeot.core.GeminiProtocol.VOICES) {
            boolean on = name.equals(now);
            ui.addRow(group, ui.listRow(R.drawable.ic_sound, on ? ACCENT : MUTED, name.isEmpty() ? "기본 목소리" : name, "", on ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putString("boardVoice", name).apply(); voiceChanged(); pick.dismiss(); }), true);
        }
        pick.body.addView(group); show(pick);
    }
    /** The model that prepares 판서's lessons: an AI, one of its models and how deeply it thinks. */
    private void brains() {
        Ui.Sheet pick = ui.selectionSheet("두뇌", "판서를 준비하는 모델 · 메인 대화의 모델은 바뀌지 않아요"); pick.primary.setVisibility(View.GONE);
        LinearLayout content = ui.column(); pick.body.addView(content);
        String[] ids = dev.jun.gyeot.core.TeamPlan.ENGINES.toArray(new String[0]), names = new String[ids.length]; for (int i = 0; i < ids.length; i++) names[i] = WorkBook.author(ids[i]);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            content.removeAllViews(); ScopedModels chosen = ScopedModels.board(store); TeamRoster roster = new TeamRoster(store, chosen); String engine = chosen.engine(), current = chosen.model(engine);
            View engines = ui.pills(names, java.util.Arrays.asList(ids).indexOf(engine), index -> { try { ScopedModels.board(store).chooseEngine(ids[index]); } catch (RuntimeException refused) { toast(refused.getMessage()); } fill[0].run(); });
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2); ep.leftMargin = ep.rightMargin = ui.dp(16); ep.bottomMargin = ui.dp(10); content.addView(engines, ep);
            if (!store.ready(engine)) content.addView(ui.footer(WorkBook.author(engine) + "는 아직 연결되지 않았어요. 설정 → AI 연결에서 연결한 뒤 쓸 수 있어요."));
            JSONArray catalog = roster.catalog(engine); LinearLayout group = ui.group(); boolean listed = false;
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject model = catalog.optJSONObject(i); String id = model == null ? "" : model.optString("id"); if (id.isBlank()) continue;
                boolean on = id.equals(current); listed |= on;
                ui.addRow(group, ui.listRow(R.drawable.ic_sparkle, on ? ACCENT : MUTED, roster.label(engine, id), "", on ? ui.text("✓", 16, 700, ACCENT) : null, () -> choose(engine, id, ScopedModels.board(store).effort(engine, id), fill[0])), true);
            }
            if (!listed && !current.isBlank()) ui.addRow(group, ui.listRow(R.drawable.ic_sparkle, ACCENT, roster.label(engine, current), "", ui.text("✓", 16, 700, ACCENT), () -> {}), true);
            content.addView(group);
            // How deeply the chosen model thinks, where it offers levels.
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, current);
            if (!current.isBlank() && !profile.efforts.isEmpty()) {
                content.addView(ui.sectionHeader("생각 깊이"));
                List<String> levels = new ArrayList<>(); levels.add(""); levels.addAll(profile.efforts); String[] words = new String[levels.size()];
                for (int i = 0; i < words.length; i++) words[i] = levels.get(i).isEmpty() ? "기본" : WorkBook.CHATGPT.equals(engine) ? dev.jun.gyeot.core.Reasoning.gptShortLabel(levels.get(i)) : levels.get(i);
                View depth = ui.pills(words, Math.max(0, levels.indexOf(chosen.effort(engine, current))), index -> choose(engine, current, levels.get(index), fill[0]));
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2); dp.leftMargin = dp.rightMargin = ui.dp(16); content.addView(depth, dp);
            }
        };
        fill[0].run(); show(pick);
    }
    private void choose(String engine, String model, String effort, Runnable then) {
        try { ScopedModels.board(store).chooseProfile(engine, model, effort); } catch (RuntimeException refused) { toast(refused.getMessage() == null ? "모델을 고르지 못했어요." : refused.getMessage()); }
        then.run();
    }
    private void textSizes() {
        Ui.Sheet pick = ui.selectionSheet("글자 크기", "AI 판서와 글자 도구가 새로 쓰는 글에 적용돼요"); pick.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); int now = Math.max(0, Math.min(TEXT_NAMES.length - 1, store.settings.getInt("boardText", 1)));
        for (int i = 0; i < TEXT_NAMES.length; i++) {
            int level = i;
            ui.addRow(group, ui.listRow(R.drawable.ic_text, i == now ? ACCENT : MUTED, TEXT_NAMES[i], "", i == now ? ui.text("✓", 16, 700, ACCENT) : null, () -> {
                store.settings.edit().putInt("boardText", level).apply(); board.textScale = textScale(); teacher.textScale = textScale(); pick.dismiss();
            }), true);
        }
        pick.body.addView(group); show(pick);
    }
    private void fonts() {
        Ui.Sheet pick = ui.selectionSheet("글씨체", "새 제목과 직접 쓰는 글에 적용돼요. 본문과 수치는 인쇄 글씨로 보여요."); pick.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); boolean hand = store.settings.getBoolean("boardHand", true);
        ui.addRow(group, ui.listRow(R.drawable.ic_pen, hand ? ACCENT : MUTED, "손글씨", "노트에 예쁘게 쓴 듯한 글씨", hand ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putBoolean("boardHand", true).apply(); look(); pick.dismiss(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_text, !hand ? ACCENT : MUTED, "또박또박", "또렷한 인쇄 글씨", !hand ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putBoolean("boardHand", false).apply(); look(); pick.dismiss(); }), true);
        pick.body.addView(group); show(pick);
    }
    private void styles() {
        Ui.Sheet pick = ui.selectionSheet("판 모양", "지금 판에 있는 것도 바로 그 모양으로 보여요"); pick.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); boolean chalk = board.chalk();
        ui.addRow(group, ui.listRow(R.drawable.ic_pages, !chalk ? ACCENT : MUTED, "노트", "크림색 종이에 펜과 형광펜으로 정리한 노트", !chalk ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putString("boardStyle", BoardView.NOTE).apply(); look(); pick.dismiss(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_board, chalk ? ACCENT : MUTED, "칠판", "초록 칠판에 분필로 쓴 판서", chalk ? ui.text("✓", 16, 700, ACCENT) : null, () -> { store.settings.edit().putString("boardStyle", BoardView.CHALK).apply(); look(); pick.dismiss(); }), true);
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
        Uri uri = data.getData(); String destination = boardId;
        background(() -> {
            JSONObject file = null; String problem = null;
            try {
                file = WorkFiles.addPicked(getApplicationContext(), store, uri);
                String kind = file.optString("kind");
                if (!(WorkBook.IMAGE.equals(kind) || WorkBook.PDF.equals(kind) || "text".equals(kind))) { store.attachmentFile(file.optString("id")).delete(); file = null; problem = "사진, PDF, 글 파일을 붙일 수 있어요."; }
            } catch (Exception failed) { problem = failed.getMessage() == null || failed.getMessage().isBlank() ? "파일을 읽지 못했어요." : failed.getMessage(); }
            // A picture or a document goes onto the board itself, to be written on; a text file rides with the question.
            List<float[]> sizes = new ArrayList<>(); List<String> names = new ArrayList<>(); String note = null;
            if (file != null && !"text".equals(file.optString("kind"))) {
                java.io.File stored = store.attachmentFile(file.optString("id"));
                try { note = WorkBook.PDF.equals(file.optString("kind")) ? pages(destination, stored, names, sizes) : photo(destination, stored, "image/png".equals(file.optString("mime")), names, sizes); }
                catch (Exception failed) { for (String name : names) { java.io.File made = boards.picture(name); if (made != null) made.delete(); } names.clear(); problem = "자료를 판에 올리지 못했어요."; }
                finally { stored.delete(); }
                file = null;
            }
            JSONObject added = file; String why = problem, told = note;
            foreground(() -> {
                if (destroyed || !destination.equals(boardId) || busy()) {
                    if (added != null) store.attachmentFile(added.optString("id")).delete();
                    discardPictures(names); return;
                }
                if (added != null) { attached.add(added); paintFiles(); return; }
                if (why != null) { toast(why); return; }
                // As wide as the writing around it calls for: the whole page on a phone, about half of it where the page is a wide screen.
                float y = below(), width = (Board.WIDTH - 2 * Board.MARGIN) * dev.jun.gyeot.core.BoardPlot.compact(textScale()); List<JSONObject> made = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) { float height = width * sizes.get(i)[1] / Math.max(1, sizes.get(i)[0]); made.add(Board.image(names.get(i), Board.MARGIN, y, width, height, Board.ME)); y += height + 30; }
                if (!board.addAll(made)) { discardPictures(names); return; }
                paintHistory(); board.follow((float) made.get(0).optDouble("y") + board.shown() * .4f);
                if (told != null) toast(told);
            });
        });
    }
    /**
     * The part of a picture that holds something: a scan or a screenshot of a problem often has wide empty margins, and
     * the board shows the problem, not its margins. The background is what the four corners are; a row or column
     * belongs to the content when a few of its pixels differ from that. A little margin is kept. A photograph, whose
     * corners are not a plain background, comes back whole.
     */
    static android.graphics.Rect content(Bitmap picture) {
        int width = picture.getWidth(), height = picture.getHeight(); android.graphics.Rect whole = new android.graphics.Rect(0, 0, width, height);
        if (width < 80 || height < 80) return whole;
        int[] corners = {picture.getPixel(2, 2), picture.getPixel(width - 3, 2), picture.getPixel(2, height - 3), picture.getPixel(width - 3, height - 3)};
        int r = 0, g = 0, b = 0; for (int corner : corners) { r += (corner >> 16) & 255; g += (corner >> 8) & 255; b += corner & 255; }
        r /= 4; g /= 4; b /= 4;
        // Corners that differ from one another are not a plain background: nothing is cut.
        for (int corner : corners) if (Math.abs(((corner >> 16) & 255) - r) + Math.abs(((corner >> 8) & 255) - g) + Math.abs((corner & 255) - b) > 40) return whole;
        int step = Math.max(1, Math.min(width, height) / 600), left = width, right = -1, top = height, bottom = -1; int[] row = new int[width];
        for (int y = 0; y < height; y += step) {
            picture.getPixels(row, 0, width, 0, y, width, 1); int found = 0, first = -1, last = -1;
            for (int x = 0; x < width; x += step) { int p = row[x]; if (Math.abs(((p >> 16) & 255) - r) + Math.abs(((p >> 8) & 255) - g) + Math.abs((p & 255) - b) > 90) { found++; if (first < 0) first = x; last = x; } }
            if (found >= 2) { top = Math.min(top, y); bottom = Math.max(bottom, y); left = Math.min(left, first); right = Math.max(right, last); }
        }
        if (right < 0) return whole;
        int pad = Math.max(14, Math.max(width, height) / 40);
        android.graphics.Rect found = new android.graphics.Rect(Math.max(0, left - pad), Math.max(0, top - pad), Math.min(width, right + pad + 1), Math.min(height, bottom + pad + 1));
        return (long) found.width() * found.height() > (long) width * height * 92 / 100 ? whole : found;
    }
    /** Writes a picture, without its empty margins, into the board's own files; adds its name and size. */
    private void discardPictures(List<String> names) { for (String name : names) { java.io.File made = boards.picture(name); if (made != null) made.delete(); } }

    private void keep(String destination, Bitmap picture, boolean png, List<String> names, List<float[]> sizes) throws Exception {
        android.graphics.Rect part = content(picture); Bitmap cut = part.width() == picture.getWidth() && part.height() == picture.getHeight() ? picture : Bitmap.createBitmap(picture, part.left, part.top, part.width(), part.height());
        String name = boards.pictureName(destination, png); names.add(name);
        try (OutputStream out = new java.io.FileOutputStream(boards.picture(name))) { if (!cut.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90, out)) throw new java.io.IOException("not written"); }
        sizes.add(new float[]{cut.getWidth(), cut.getHeight()});
        if (cut != picture) cut.recycle();
    }
    /** Puts a picked picture into the board's own files. */
    private String photo(String destination, java.io.File stored, boolean png, List<String> names, List<float[]> sizes) throws Exception {
        Bitmap picture = android.graphics.BitmapFactory.decodeFile(stored.getPath());
        if (picture == null) throw new java.io.IOException("not a picture");
        try { keep(destination, picture, png, names, sizes); } finally { picture.recycle(); }
        return null;
    }
    /** Draws a PDF's first pages as pictures in the board's own files; says so when it has more pages than are shown. */
    private String pages(String destination, java.io.File stored, List<String> names, List<float[]> sizes) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = android.os.ParcelFileDescriptor.open(stored, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
             android.graphics.pdf.PdfRenderer pdf = new android.graphics.pdf.PdfRenderer(descriptor)) {
            int count = Math.min(PDF_PAGES, pdf.getPageCount());
            for (int i = 0; i < count; i++) {
                try (android.graphics.pdf.PdfRenderer.Page page = pdf.openPage(i)) {
                    int width = PAGE_PIXELS, height = Math.max(1, Math.round(PAGE_PIXELS * page.getHeight() / (float) Math.max(1, page.getWidth())));
                    if (height > 4000) { width = Math.max(1, Math.round(width * 4000f / height)); height = 4000; }
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.WHITE);
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    try { keep(destination, bitmap, false, names, sizes); } finally { bitmap.recycle(); }
                }
            }
            return pdf.getPageCount() > count ? "문서가 길어서 앞의 " + count + "쪽만 판에 올렸어요." : null;
        }
    }
    private TextView chip(String label, String description, Runnable action) {
        TextView chip = ui.text(label, FOOTNOTE, 560, TEXT); chip.setSingleLine(true); chip.setMaxWidth(ui.dp(220)); chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        chip.setBackground(surface(PILL)); ui.lift(chip, 4); chip.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)); chip.setContentDescription(description);
        chip.setOnClickListener(v -> action.run()); Ui.pressable(chip, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(6); p.topMargin = p.bottomMargin = ui.dp(3); files.addView(chip, p);
        return chip;
    }
    private void paintFiles() {
        files.removeAllViews();
        // The newest lesson can be watched again from its start; a line of it, from the hand tool's tap.
        String again = busy() ? "" : teacher.latest();
        if (!again.isEmpty()) chip("▶ 다시 보기", "방금 판서를 처음부터 다시 보기", () -> teacher.replay(again, 0)).setTextColor(ACCENT);
        for (JSONObject file : new ArrayList<>(attached)) {
            chip(file.optString("name") + "  ✕", file.optString("name") + " 첨부 빼기", () -> { attached.remove(file); store.attachmentFile(file.optString("id")).delete(); paintFiles(); });
        }
    }
    private void dropFiles() { for (JSONObject file : attached) store.attachmentFile(file.optString("id")).delete(); attached.clear(); }
    private boolean handDrawn() {
        JSONArray items = board.items();
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null && Board.ME.equals(item.optString("by")) && !Board.TEXT.equals(item.optString("t")) && !Board.IMAGE.equals(item.optString("t")) && !Board.WIDGET.equals(item.optString("t"))) return true; }
        return false;
    }
    private boolean busy() { return preparing || examBusy || teacher.busy(); }
    /** A 기출 search under way (finding a paper's questions, cutting one out), its turn, and its stop. */
    private boolean examBusy; private int examRun; private final java.util.concurrent.atomic.AtomicReference<Runnable> examStop = new java.util.concurrent.atomic.AtomicReference<>();

    private void stopLesson() {
        if (examBusy) {
            examRun++; examBusy = false; Runnable stop = examStop.getAndSet(null);
            if (stop != null) try { stop.run(); } catch (RuntimeException ignored) { /* already over */ }
            lesson(BoardTeacher.IDLE, 0, 0, ""); return;
        }
        if (preparing) {
            if (!jobs.cancel(boardId)) return;
            preparing = false;
            lesson(BoardTeacher.IDLE, 0, 0, "");
        } else teacher.stop();
    }

    /**
     * Sends what was typed. A request for a numbered question of a 기출 paper ("2025 수능 수학 22번 풀어 줘") is looked up in
     * 들퍼레/수능 first: one paper that fits is cut out and solved, several open the 기출 sheet narrowed to them, none
     * send the request as it is.
     */
    private void send() {
        if (busy()) return;
        String words = input.getText().toString().strip(); dev.jun.gyeot.core.ExamPapers.Ask ask = dev.jun.gyeot.core.ExamPapers.ask(words);
        if (ask == null || !attached.isEmpty()) { sendNow(); return; }
        BoardExams.Shelf shelf = BoardExams.shelf(this);
        if (!shelf.allowed()) { sendNow(); return; }
        background(() -> {
            List<BoardExams.Entry> entries; try { entries = BoardExams.entries(getApplicationContext(), shelf); } catch (Exception unreadable) { entries = List.of(); }
            List<dev.jun.gyeot.core.ExamPapers.Paper> papers = new ArrayList<>(); for (BoardExams.Entry entry : entries) papers.add(entry.paper());
            List<dev.jun.gyeot.core.ExamPapers.Paper> fit = dev.jun.gyeot.core.ExamPapers.matching(papers, ask); List<BoardExams.Entry> all = entries;
            foreground(() -> {
                if (destroyed) return;
                if (fit.isEmpty()) { sendNow(); return; }
                if (fit.size() > 1) { exams(ask); return; }
                for (BoardExams.Entry entry : all) if (entry.paper() == fit.get(0)) { input.setText(""); solve(entry, ask.number(), ask.section(), words); return; }
                sendNow();
            });
        });
    }
    /** Sends the question with its files and a snapshot when there is hand-drawn work. */
    private void sendNow() {
        if (busy()) return;
        String words = input.getText().toString().strip();
        // The board's pictures (the newest few) go with every question, in the order the model numbers them.
        List<String> shownPictures = new ArrayList<>(); for (JSONObject picture : dev.jun.gyeot.core.BoardScript.shown(board.items())) shownPictures.add(picture.optString("id"));
        if (words.isEmpty() && attached.isEmpty() && shownPictures.isEmpty()) { toast("풀 문제나 배우고 싶은 것을 적어 주세요."); return; }
        if (brain == null && ScopedModels.board(store).readyEngine().isBlank()) { toast("판서에 쓸 AI를 먼저 골라 주세요. 더 보기 → 두뇌"); return; }
        List<JSONObject> sending = new ArrayList<>(attached);
        String lessonContext=BoardClassroom.instruction(classroom);
        main.removeCallbacks(save); saveNow();
        JSONArray source;
        try { source = new JSONArray(board.items().toString()); } catch (Exception invalid) { toast("판 내용을 읽지 못했어요."); return; }
        Bitmap drawn = null;
        if (handDrawn()) { float bottom = Board.bottom(board.items(), board.measure) + 20; drawn = board.render(Board.WIDTH, Math.max(0, bottom - SNAPSHOT_UNITS), bottom); }
        Bitmap snapshot = drawn;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class); if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        AppStore app = store; BoardStore boardFiles = boards;
        JSONObject model = ScopedModels.board(app).snapshot();
        BoardTeacher.Brain selected = brain != null ? brain : (sys, request, media, abort) ->
                MainAdapters.send(app, model, MainRequest.of(sys, request).files(media), () -> {}, abort).text();
        // The request owns these input files from now on. Leaving the screen can release its own new attachments.
        attached.removeAll(sending); preparing = true;
        input.setText(""); lesson(BoardTeacher.THINKING, 0, 0, "");
        Board.Measure ruler = new BoardView(getApplicationContext()).measure;
        jobs.submit(boardId, selected,
                () -> payload(app, boardFiles, words, lessonContext, shownPictures, sending, snapshot),
                source, ruler, teacher.textScale, teacher.hand, teacher.chalk);
        try { startForegroundService(new Intent(this, BoardJobService.class)); }
        catch (IllegalStateException | SecurityException restricted) {
            // In-app navigation still works if Android disallows foreground service promotion.
        }
    }

    private static BoardJobs.Payload payload(AppStore store, BoardStore boards, String words, String lessonContext,
            List<String> shownPictures, List<JSONObject> sending, Bitmap snapshot) throws Exception {
        List<MainRequest.Attachment> out = new ArrayList<>();
        StringBuilder text = new StringBuilder(words); long bytes = 0;
        try {
            int number = 0;
            for (String name : shownPictures) {
                java.io.File file = boards.picture(name); if (file == null || !file.isFile()) throw new java.io.FileNotFoundException("판에 올린 자료를 찾지 못했어요. 지우고 다시 붙여 주세요.");
                bytes += file.length(); if (bytes > FILE_BYTES) throw new IllegalArgumentException("판에 올린 자료가 너무 커요. 몇 장을 지우고 다시 보내 주세요.");
                out.add(new MainRequest.Attachment("자료 " + (++number) + (name.endsWith(".png") ? ".png" : ".jpg"), name.endsWith(".png") ? "image/png" : "image/jpeg", Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file.toPath()))));
            }
            if (number > 0) text.append("\n\n(사진 1").append(number > 1 ? "~" + number : "").append("번은 판에 올라가 있는 자료예요. 번호는 판의 위에서부터예요.)");
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
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!snapshot.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new java.io.IOException("필기 이미지를 만들지 못했어요.");
                out.add(new MainRequest.Attachment("내가 판에 쓴 것.png", "image/png", Base64.getEncoder().encodeToString(png.toByteArray())));
                text.append("\n\n(마지막 사진은 지금 판의 모습이에요. 내가 손으로 쓴 것도 보고 답해 주세요. 이 사진에는 표시할 수 없어요.)");
            }
            return new BoardJobs.Payload(text.toString() + lessonContext, out);
        } finally {
            if (snapshot != null) snapshot.recycle();
            for (JSONObject file : sending) store.attachmentFile(file.optString("id")).delete();
        }
    }

    private void jobFinished(BoardJobs.Result result) {
        if (destroyed || !boardId.equals(result.boardId())) return;
        preparing = false;
        if (result.doc() == null) {
            lesson(BoardTeacher.IDLE, 0, 0, "");
            if (resumed) { jobs.takeError(boardId); toast(result.error()); }
            return;
        }
        title = result.title(); paintTitle();
        teacher.lessons(result.doc().lessons()); board.replace(result.doc().items());
        lesson(BoardTeacher.IDLE, 0, 0, "");
        if (resumed) teacher.playCompleted(result.lessonId());
    }

    /** The lesson from 판서's own model (더 보기 → 두뇌). */
    // ── 수능 기출 ───────────────────────────────────────────────────────────────────────────────────

    /** Reading 들퍼레/수능 needs Android's 모든 파일 접근: said once, with the way to it. */
    private void allowFiles() {
        Ui.Sheet ask = ui.selectionSheet("수능 기출", "들퍼레/수능 폴더를 읽으려면 권한이 필요해요");
        TextView why = ui.text("Android 설정에서 들퍼레의 '모든 파일 접근'을 허용하면, 들퍼레/수능 폴더에 넣어 둔 문제지 PDF를 판서에서 골라 풀 수 있어요.", BODY, 450, TEXT);
        why.setLineSpacing(ui.dp(4), 1); why.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6)); ask.body.addView(why);
        ask.primary.setText("허용하러 가기");
        ask.primary.setOnClickListener(v -> { ask.dismiss(); try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (RuntimeException none) { toast("설정 화면을 열지 못했어요."); } });
        show(ask);
    }
    /** 수능 기출: the papers in 들퍼레/수능 by exam and subject, then a question's number; narrowed to what a request named. */
    private void exams(dev.jun.gyeot.core.ExamPapers.Ask narrow) {
        // A lesson being read may go on while a paper is chosen; one being prepared, or another search, may not.
        if (preparing || examBusy) return;
        BoardExams.Shelf shelf = BoardExams.shelf(this);
        if (!shelf.allowed()) { allowFiles(); return; }
        background(() -> {
            List<BoardExams.Entry> entries; String problem = null;
            try { entries = BoardExams.entries(getApplicationContext(), shelf); } catch (Exception failed) { entries = List.of(); problem = failed.getMessage(); }
            List<BoardExams.Entry> found = entries; String why = problem;
            foreground(() -> { if (!destroyed) examSheet(shelf, found, narrow, why); });
        });
    }
    private static int rank(String exam) { return exam.equals(dev.jun.gyeot.core.ExamPapers.SUNEUNG) ? 0 : exam.equals(dev.jun.gyeot.core.ExamPapers.SEPTEMBER) ? 1 : exam.equals(dev.jun.gyeot.core.ExamPapers.JUNE) ? 2 : 3; }
    private void examSheet(BoardExams.Shelf shelf, List<BoardExams.Entry> entries, dev.jun.gyeot.core.ExamPapers.Ask narrow, String problem) {
        List<BoardExams.Entry> papers = new ArrayList<>(), unknown = new ArrayList<>();
        for (BoardExams.Entry entry : entries) { if (entry.paper().answers()) continue; if (entry.paper().known()) papers.add(entry); else unknown.add(entry); }
        papers.sort(java.util.Comparator.comparingInt((BoardExams.Entry e) -> -e.paper().year()).thenComparingInt(e -> rank(e.paper().exam())).thenComparing(e -> e.paper().course()));
        Ui.Sheet pick = ui.selectionSheet("수능 기출", papers.isEmpty() ? "들퍼레/수능 폴더" : "들퍼레/수능 폴더의 문제지 " + papers.size() + "개 · 번호를 누르면 판서로 풀어요");
        pick.primary.setVisibility(View.GONE);
        if (problem != null && !problem.isBlank()) pick.body.addView(ui.footer(problem));
        if (papers.isEmpty()) {
            TextView how = ui.text(entries.isEmpty() ? "들퍼레 폴더 안 '수능' 폴더에 문제지 PDF를 넣어 주세요. 연도별 폴더로 나눠도 돼요." : "이름으로 알아본 문제지가 아직 없어요.", BODY, 450, TEXT);
            how.setLineSpacing(ui.dp(4), 1); how.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(8)); pick.body.addView(how);
            if (entries.isEmpty()) { pick.primary.setVisibility(View.VISIBLE); pick.primary.setText("수능 폴더 만들기"); pick.primary.setOnClickListener(v -> { pick.dismiss(); makeFolder(shelf); }); }
        } else {
            List<String> sittings = new ArrayList<>(); for (BoardExams.Entry e : papers) if (!sittings.contains(e.paper().sitting())) sittings.add(e.paper().sitting());
            int[] at = {0};
            if (narrow != null) for (int i = 0; i < sittings.size(); i++) { dev.jun.gyeot.core.ExamPapers.Paper p = first(papers, sittings.get(i)); if ((narrow.year() == 0 || p.year() == narrow.year()) && (narrow.exam().isEmpty() || p.exam().equals(narrow.exam()))) { at[0] = i; break; } }
            LinearLayout courses = ui.column(), parts = ui.column(), numbers = ui.column(); String[] course = {""}; String[] part = {""};
            Runnable[] fill = new Runnable[2];
            // The subjects of the chosen exam, then the elective part (수학·국어) and the numbers of the chosen subject.
            fill[0] = () -> {
                courses.removeAllViews(); List<BoardExams.Entry> sitting = new ArrayList<>(); for (BoardExams.Entry e : papers) if (e.paper().sitting().equals(sittings.get(at[0]))) sitting.add(e);
                String[] names = new String[sitting.size()]; int chosen = 0;
                for (int i = 0; i < names.length; i++) {
                    names[i] = sitting.get(i).paper().course();
                    if (names[i].equals(course[0]) || narrow != null && course[0].isEmpty() && (names[i].equals(narrow.subject()) || narrow.subject().isEmpty() && sitting.get(i).paper().area().equals(narrow.area()))) chosen = i;
                }
                course[0] = names[chosen];
                courses.addView(ui.pills(names, chosen, index -> { course[0] = names[index]; fill[1].run(); }));
                fill[1].run();
            };
            fill[1] = () -> {
                parts.removeAllViews(); numbers.removeAllViews();
                BoardExams.Entry entry = null; for (BoardExams.Entry e : papers) if (e.paper().sitting().equals(sittings.get(at[0])) && e.paper().course().equals(course[0])) { entry = e; break; }
                if (entry == null) return;
                dev.jun.gyeot.core.ExamPapers.Paper paper = entry.paper(); List<String> electives = dev.jun.gyeot.core.ExamPapers.sections(paper.area());
                dev.jun.gyeot.core.ExamPapers.Index index = entry.indexed() ? BoardExams.cached(getApplicationContext(), entry.file()) : null;
                boolean split = !electives.isEmpty() && !electives.contains(paper.subject()) && (paper.year() >= 2022 && paper.year() <= 2027 || index != null && index.sections(paper.area().equals("수학") ? 23 : 35).size() > 1);
                part[0] = "";
                if (split) {
                    String remembered = store.settings.getString("examSection_" + paper.area(), "");
                    if (narrow != null && electives.contains(narrow.section())) remembered = narrow.section();
                    int chosen = Math.max(0, electives.indexOf(remembered)); part[0] = electives.get(chosen); String area = paper.area();
                    TextView label = ui.text("선택과목 · " + (area.equals("수학") ? "23" : "35") + "번부터", FOOTNOTE, 600, MUTED); label.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(6)); parts.addView(label);
                    String[] names = electives.toArray(new String[0]);
                    parts.addView(ui.pills(names, chosen, index2 -> { part[0] = names[index2]; store.settings.edit().putString("examSection_" + area, names[index2]).apply(); }));
                }
                int count = dev.jun.gyeot.core.ExamPapers.questions(paper.area()); if (index != null && !index.numbers().isEmpty()) count = Math.max(count, index.numbers().get(index.numbers().size() - 1));
                TextView label = ui.text("문제 번호", FOOTNOTE, 600, MUTED); label.setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(8)); numbers.addView(label);
                int columns = getResources().getConfiguration().screenWidthDp >= 600 ? 10 : 5; LinearLayout line = null; BoardExams.Entry chosenEntry = entry;
                for (int n = 1; n <= count; n++) {
                    if ((n - 1) % columns == 0) { line = ui.row(); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = ui.dp(6); numbers.addView(line, lp); }
                    int number = n; boolean asked = narrow != null && narrow.number() == n;
                    TextView cell = ui.text(String.valueOf(n), CALLOUT, asked ? 700 : 560, asked ? BG : TEXT); cell.setGravity(Gravity.CENTER); cell.setMinimumHeight(ui.dp(46));
                    cell.setBackground(ui.shape(asked ? TEXT : FILL, 12)); cell.setContentDescription(n + "번 풀기");
                    cell.setOnClickListener(v -> { pick.dismiss(); solve(chosenEntry, number, dev.jun.gyeot.core.ExamPapers.elective(chosenEntry.paper().area(), number) ? part[0] : "", ""); }); Ui.pressable(cell, true);
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); if ((n - 1) % columns > 0) cp.leftMargin = ui.dp(6); line.addView(cell, cp);
                }
                for (int rest = count % columns == 0 ? 0 : columns - count % columns; rest > 0 && line != null; rest--) { View empty = new View(this); LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, 1, 1); ep.leftMargin = ui.dp(6); line.addView(empty, ep); }
            };
            String[] labels = sittings.toArray(new String[0]);
            TextView label = ui.text("시험", FOOTNOTE, 600, MUTED); label.setPadding(ui.dp(4), 0, 0, ui.dp(6)); pick.body.addView(label);
            pick.body.addView(ui.pills(labels, at[0], index -> { at[0] = index; course[0] = ""; fill[0].run(); }));
            TextView subject = ui.text("과목", FOOTNOTE, 600, MUTED); subject.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(6)); pick.body.addView(subject);
            pick.body.addView(courses); pick.body.addView(parts); pick.body.addView(numbers);
            fill[0].run();
        }
        if (!unknown.isEmpty()) {
            pick.body.addView(ui.sectionHeader("이름으로 알아보지 못한 파일"));
            LinearLayout group = ui.group();
            for (BoardExams.Entry entry : unknown) ui.addRow(group, ui.listRow(R.drawable.ic_file, MUTED, entry.file().path(), "눌러서 첫 쪽을 읽고 알아봐요", ui.chevron(), () -> { pick.dismiss(); recognize(entry); }), true);
            pick.body.addView(group);
        }
        pick.body.addView(ui.footer("파일 이름은 연도_시험_과목.pdf로 두면 바로 알아봐요. 예: 2025_수능_수학.pdf · 2025_6모_국어.pdf · 2025_9모_물리1.pdf. 문제를 처음 풀 때 문제지의 쪽을 읽어 문제 위치를 찾아 두고, 다음부터는 바로 잘라 올려요."));
        show(pick);
    }
    private static dev.jun.gyeot.core.ExamPapers.Paper first(List<BoardExams.Entry> papers, String sitting) { for (BoardExams.Entry e : papers) if (e.paper().sitting().equals(sitting)) return e.paper(); return papers.get(0).paper(); }
    private void makeFolder(BoardExams.Shelf shelf) {
        background(() -> { String why = null; try { shelf.make(); } catch (Exception failed) { why = failed.getMessage() == null ? "폴더를 만들지 못했어요." : failed.getMessage(); }
            String problem = why; foreground(() -> toast(problem != null ? problem : "들퍼레/수능 폴더를 만들었어요. 문제지 PDF를 넣어 주세요.")); });
    }
    /** A paper whose name says too little: its pages are read (as for its first question) and what its first page says is kept. */
    private void recognize(BoardExams.Entry entry) { find(entry, 0, "", "", true); }
    /** Solves a question of a paper: finds it (once per paper), cuts it out, puts it on a fresh board and asks for the lesson. */
    private void solve(BoardExams.Entry entry, int number, String section, String words) { find(entry, number, section, words, false); }
    private void find(BoardExams.Entry entry, int number, String section, String words, boolean onlyRecognize) {
        if (preparing || examBusy) return;
        // A new question ends the lesson being read.
        if (teacher.busy()) teacher.stop();
        if (brain == null && ScopedModels.board(store).readyEngine().isBlank()) { toast("판서에 쓸 AI를 먼저 골라 주세요. 더 보기 → 두뇌"); return; }
        BoardExams.Shelf shelf = BoardExams.shelf(this); int run = ++examRun; examBusy = true;
        java.util.concurrent.atomic.AtomicReference<Runnable> cancel = new java.util.concurrent.atomic.AtomicReference<>(); examStop.set(() -> { Runnable stop = cancel.get(); if (stop != null) stop.run(); });
        lesson(BoardTeacher.THINKING, 0, 0, ""); progress.setText(BoardExams.cached(getApplicationContext(), entry.file()) == null ? "문제지를 읽는 중…" : "문제를 자르는 중…");
        background(() -> {
            String problem = null, chosen = section; List<String> choices = List.of(); List<Bitmap> pictures = new ArrayList<>(); dev.jun.gyeot.core.ExamPapers.Paper paper = entry.paper();
            try {
                dev.jun.gyeot.core.ExamPapers.Index index = BoardExams.index(getApplicationContext(), shelf, entry.file(), brain != null ? brain : this::askModel, cancel,
                    (done, all) -> foreground(() -> { if (examBusy && run == examRun && !destroyed) progress.setText("문제 위치를 찾는 중 · " + done + "/" + all + "쪽"); }));
                paper = dev.jun.gyeot.core.ExamPapers.known(paper, index);
                if (!onlyRecognize) {
                    List<String> found = new ArrayList<>(index.sections(number)); found.remove("");
                    if (chosen.isEmpty() && found.size() > 1) {
                        String remembered = store.settings.getString("examSection_" + paper.area(), "");
                        if (found.contains(remembered)) chosen = remembered; else choices = found;
                    }
                    if (choices.isEmpty()) {
                        List<dev.jun.gyeot.core.ExamPapers.Cut> cuts = dev.jun.gyeot.core.ExamPapers.cuts(index, number, chosen);
                        if (cuts.isEmpty()) problem = number + "번을 이 문제지에서 찾지 못했어요.";
                        else pictures = BoardExams.pictures(shelf, entry.file(), cuts);
                    }
                }
            } catch (java.util.concurrent.CancellationException stopped) { problem = ""; }
            catch (Exception failed) { problem = failed.getMessage() == null || failed.getMessage().isBlank() ? "문제지를 읽지 못했어요." : failed.getMessage(); }
            String why = problem, part = chosen; List<String> options = choices; List<Bitmap> made = pictures; dev.jun.gyeot.core.ExamPapers.Paper known = paper;
            foreground(() -> {
                if (destroyed || run != examRun) { for (Bitmap b : made) b.recycle(); return; }
                examBusy = false; examStop.set(null); lesson(BoardTeacher.IDLE, 0, 0, "");
                if (why != null) { for (Bitmap b : made) b.recycle(); if (!why.isEmpty()) toast(why); return; }
                if (onlyRecognize) { toast(known.known() ? "알아봤어요: " + known.title() : "첫 쪽에서 시험을 알아보지 못했어요. 파일 이름을 바꿔 주세요."); exams(null); return; }
                if (!options.isEmpty()) { chooseSection(new BoardExams.Entry(known, entry.file(), true), number, options, words); return; }
                place(known, number, part, made, words);
            });
        });
    }
    private void chooseSection(BoardExams.Entry entry, int number, List<String> options, String words) {
        Ui.Sheet which = ui.selectionSheet(number + "번", "어느 선택과목의 문제인가요?"); which.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        for (String option : options) ui.addRow(group, ui.listRow(R.drawable.ic_book, ORANGE, option, entry.paper().title() + " " + number + "번", ui.chevron(), () -> {
            which.dismiss(); store.settings.edit().putString("examSection_" + entry.paper().area(), option).apply(); solve(entry, number, option, words); }), true);
        which.body.addView(group); show(which);
    }
    /** The question's pictures on a fresh board, named after it, then the lesson asked for. */
    /**
     * The voice assistant asked for 판서 (AssistantService, BoardIntake): the phone screens it took, top to bottom, go on a new
     * board, and the board explains them; with none, the request alone. A lesson running here stops for it: the user asked.
     */
    private void fromVoice(BoardIntake.Item item) {
        if (busy()) stopLesson();
        String request = item.request().strip();
        if (item.pictures().isEmpty()) { if (board.items().length() > 0) newBoard(); input.setText(request.isEmpty() ? "판서로 설명해 줘." : request); sendNow(); return; }
        List<Bitmap> pictures = new ArrayList<>();
        for (byte[] jpeg : item.pictures()) { Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length); if (decoded != null) pictures.add(decoded); }
        if (pictures.isEmpty()) { toast("화면 그림을 열지 못했어요."); return; }
        int count = pictures.size();
        String about = "판에 올린 자료는 사용자가 보던 휴대폰 화면" + (count > 1 ? " " + count + "장이에요(위에서 아래로 스크롤한 차례). 처음부터 차례로" : "이에요. 이 내용을") + " 판서로 차근차근 설명해 줘.";
        placePictures(pictures, count > 1 ? "화면 판서 (" + count + "장)" : "화면 판서", (request.isEmpty() ? "" : request + "\n\n") + about);
    }
    private void place(dev.jun.gyeot.core.ExamPapers.Paper paper, int number, String section, List<Bitmap> pictures, String words) {
        String named = paper.title() + (section.isEmpty() ? "" : " " + section) + " " + number + "번";
        placePictures(pictures, named, (words.isBlank() ? "" : words + "\n\n") + "판에 올린 자료는 " + named + " 문제예요. 이 문제를 판서로 차근차근 풀어 줘.");
    }
    /** Pictures go on a new board, one under another, titled; then the request is sent. The bitmaps are recycled. */
    private void placePictures(List<Bitmap> pictures, String named, String request) {
        if (board.items().length() > 0) newBoard();
        List<String> names = new ArrayList<>(); List<float[]> sizes = new ArrayList<>();
        try { for (Bitmap picture : pictures) keep(boardId, picture, false, names, sizes); }
        catch (Exception failed) { for (String name : names) { java.io.File made = boards.picture(name); if (made != null) made.delete(); } toast("자료를 판에 올리지 못했어요."); return; }
        finally { for (Bitmap picture : pictures) picture.recycle(); }
        float y = below(), width = (Board.WIDTH - 2 * Board.MARGIN) * dev.jun.gyeot.core.BoardPlot.compact(textScale()); List<JSONObject> made = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) { float height = width * sizes.get(i)[1] / Math.max(1, sizes.get(i)[0]); made.add(Board.image(names.get(i), Board.MARGIN, y, width, height, Board.ME)); y += height + 30; }
        board.addAll(made); paintHistory();
        title = named; paintTitle(); saveSoon();
        input.setText(request);
        sendNow();
    }

    private String askModel(String instructions, String request, List<MainRequest.Attachment> with, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        ScopedModels models = ScopedModels.board(store);
        if (models.readyEngine().isBlank()) throw new IllegalStateException("판서에 쓸 AI를 먼저 골라 주세요. 더 보기 → 두뇌");
        return MainAdapters.send(store, models.snapshot(), MainRequest.of(instructions, request).files(with), () -> {}, cancel).text();
    }

    /** Full-board export. Short notes fit in a PNG; PDF pages continue through the last line. */
    private void share(boolean pdfRequested) {
        if (exporting) { toast("판서를 저장하는 중이에요."); return; }
        float bottom = Board.bottom(board.items(), board.measure);
        if (bottom <= 0) { toast("판이 비어 있어요."); return; }
        float height = bottom + Board.MARGIN;
        boolean pdf = pdfRequested || height > 5_500;
        String mime = pdf ? "application/pdf" : "image/png";
        String name = (title.isBlank() ? "판서" : title.replaceAll("[\\\\/:*?\"<>|]", " ")) + "-"
            + System.currentTimeMillis() + (pdf ? ".pdf" : ".png");
        BoardView renderer = new BoardView(this);
        renderer.style(board.style());
        renderer.pictures(this::picture);
        try { renderer.show(new JSONArray(board.items().toString())); }
        catch (Exception unreadable) { toast("판을 저장할 수 없어요."); return; }
        exporting = true;
        if (pdf) toast("판서를 PDF로 저장하는 중이에요.");
        background(() -> {
            Uri saved = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name); values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, (pdf ? Environment.DIRECTORY_DOWNLOADS : Environment.DIRECTORY_PICTURES) + "/" + Brand.NAME);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                saved = getContentResolver().insert(pdf ? MediaStore.Downloads.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (saved == null) throw new java.io.IOException("no place");
                try (OutputStream out = getContentResolver().openOutputStream(saved)) {
                    if (out == null) throw new java.io.IOException("not written");
                    if (pdf) exportPdf(renderer, out);
                    else {
                        int width = Math.min(1400, (int) Math.sqrt(6_000_000.0 * Board.WIDTH / height));
                        Bitmap picture = renderer.render(width, 0, height);
                        try { if (!picture.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("not written"); }
                        finally { picture.recycle(); }
                    }
                }
                ContentValues published = new ContentValues(); published.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (getContentResolver().update(saved, published, null, null) != 1) throw new java.io.IOException("not published");
            } catch (Exception | OutOfMemoryError failed) {
                if (saved != null) try { getContentResolver().delete(saved, null, null); } catch (RuntimeException ignored) { /* best effort */ }
                saved = null;
            }
            Uri uri = saved;
            foreground(() -> {
                exporting = false;
                if (destroyed) return;
                if (uri == null) { toast("판서를 저장하지 못했어요."); return; }
                toast(pdf ? "다운로드에 PDF를 저장했어요." : "사진 폴더에 저장했어요.");
                try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "판서 공유")); }
                catch (RuntimeException none) { /* The file is already saved. */ }
            });
        });
    }

    private static void exportPdf(BoardView renderer, OutputStream out) throws java.io.IOException {
        int width = 595, height = 842, inset = 26;
        float from = 0, span = (height - 2 * inset) * Board.WIDTH / (float) width;
        List<Float> ends = Board.pageEnds(renderer.items(), renderer.measure, span);
        PdfDocument pdf = new PdfDocument();
        try {
            for (int i = 0; i < ends.size(); i++) {
                float to = ends.get(i);
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(width, height, i + 1).create());
                try {
                    var canvas = page.getCanvas();
                    canvas.drawColor(renderer.paper()); canvas.translate(0, inset);
                    renderer.renderPage(canvas, width, from, to);
                } finally { pdf.finishPage(page); }
                from = to;
            }
            pdf.writeTo(out);
        } finally { pdf.close(); }
    }
}
