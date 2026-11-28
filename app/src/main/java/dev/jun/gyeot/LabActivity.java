package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.LabBook;
import dev.jun.gyeot.core.WorkBook;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * 연구실: research projects on the left (folding into a slim column on a wide screen), the open one on the right in five
 * views: 토론방 (the researchers' words as a group conversation, with the composer: a topic starts a 토론, @이름 asks one),
 * 노트 (every step on a timeline), 산출물 (보고서, 검증 장부, 실험, 참고문헌), 연구원 (each role's own model, who debates) and
 * 진행판 (자율 연구, its budget and approvals, the stages, the 계산 환경). The work itself is LabRunner's.
 */
public final class LabActivity extends Activity implements LabRunner.Listener {
    static final String OPEN = "gyeot.lab_project";
    private static final String[] TABS = {"토론방", "노트", "산출물", "연구원", "진행판"};
    private Ui ui; private AppStore store; private LabRunner runner; private LabBook book; private MainNavigation navigation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private String openId; private boolean wide, destroyed, folded, resumed;
    private int tab;
    private LinearLayout listColumn, content, activityStrip; private View composerBox;
    private ScrollView scroll;
    private EditText input; private ImageView send; private TextView debateLine, headerStatus;
    private ShimmerText activityText;
    private ComposerDictation dictation;
    private final Set<String> expanded = new HashSet<>();
    private int noteFilter;
    private final LruCache<String, Bitmap> figures = new LruCache<>(24 * 1024 * 1024) { @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); } };
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.KOREA), day = new SimpleDateFormat("M월 d일 (E)", Locale.KOREA);

    /** The menu and rail look the same on every screen: opening and leaving this one swaps only the content, with no slide or dim. */
    private void noTransition() { if (android.os.Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0); } else overridePendingTransition(0, 0); }
    @Override public void finish() { super.finish(); noTransition(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); noTransition();
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); runner = LabRunner.get(this); book = runner.book();
        openId = state != null ? state.getString("open") : getIntent().getStringExtra(OPEN);
        tab = state != null ? state.getInt("tab") : 0;
        if (openId == null && state == null) { String last = store.settings.getString("labOpen", ""); if (!last.isEmpty() && wideScreen()) openId = last; }
        folded = store.settings.getBoolean("labListFolded", false);
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, 0, 0, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)); return insets;
        });
        navigation = new MainNavigation(this, root, "lab", target -> MainNavigation.open(this, target));
        setContentView(navigation);
        draw(); drawnOnCreate = true; root.requestApplyInsets();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String asked = intent.getStringExtra(OPEN); if (asked != null) { openId = asked; tab = 0; draw(); }
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("open", openId); out.putInt("tab", tab); }
    /** onCreate drew the screen: the first onResume does not draw it again. */
    private boolean drawnOnCreate;
    /**
     * Coming back (the menu brings this same screen to the front) draws again only when something it shows moved while it
     * was away: else the screen as it was is shown at once, with nothing rebuilt. 진행판 (Linux and Python can change
     * outside) is always drawn again.
     */
    @Override protected void onResume() {
        super.onResume(); resumed = true; AppNavigator.inFront("lab"); runner.watch(this); LabRunner.onScreen = openId;
        if (drawnOnCreate) { drawnOnCreate = false; drawnLooks = looks(); return; }
        String now = looks(); if (tab == 4 || !now.equals(drawnLooks)) draw(); drawnLooks = now;
    }
    private String drawnLooks = "";
    /** What the screen shows, in short: its width, the projects (with what each is doing), the open one's notes and state. */
    private String looks() {
        StringBuilder s = new StringBuilder().append(wideScreen()).append(folded).append('|').append(openId).append('|').append(tab);
        for (LabBook.Project p : book.projects()) s.append('|').append(p.id()).append(':').append(p.touched()).append(':').append(p.title().hashCode()).append(':').append(runner.busy(p.id())).append(':').append(book.autopilot(p.id()));
        if (openId != null) s.append('#').append(book.noteCount(openId)).append(':').append(book.state(openId)).append(':').append(new java.util.TreeSet<>(runner.speaking(openId))).append(':').append(book.report(openId).length());
        return s.toString();
    }
    @Override protected void onPause() { resumed = false; LabRunner.onScreen = null; runner.unwatch(this); if (dictation != null) dictation.pause(); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; runner.unwatch(this); decoder.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (navigation != null && navigation.close()) return;
        if (!wide && openId != null) { openId = null; draw(); return; }
        if (!HtmlWallpaper.backHome(this)) super.onBackPressed();
    }
    private boolean wideScreen() { return getResources().getConfiguration().screenWidthDp >= 600; }
    private final java.util.Map<String, String> shown = new java.util.HashMap<>();
    /** A change of the project open: only its activity line when nothing else moved, the whole view when it did. */
    @Override public void changed(String project) {
        if (destroyed || !resumed) return;
        String sign = project + "|" + book.noteCount(project) + "|" + book.state(project) + "|" + runner.busy(project) + "|" + runner.speaking(project);
        boolean moved = !sign.equals(shown.put(project, sign));
        if (moved && wide && listColumn != null) fillList(book.projects());
        if (!project.equals(openId)) return;
        if (moved) { header(); render(false); }
        else if (activityStrip != null && runner.busy(project)) { activityText.setText(runner.activity(project)); if (headerStatus != null) headerStatus.setText(runner.activity(project)); if (reportNow != null && reportNow.isAttachedToWindow()) reportNow.setText(runner.activity(project)); }
    }

    // ── colors and faces ─────────────────────────────────────────────

    static int roleHue(String role) {
        return switch (role == null ? "" : role) { case "pi" -> INDIGO; case "scout" -> TEAL; case "theorist" -> PURPLE; case "experimenter" -> ORANGE; case "critic" -> RED; case "scribe" -> GREEN; default -> GRAY; };
    }
    static int fieldHue(String field) {
        return switch (field == null ? "" : field) { case "math" -> INDIGO; case "physics" -> BLUE; case "cs" -> TEAL; case "bio" -> GREEN; case "chem" -> ORANGE; case "social" -> PINK; default -> PURPLE; };
    }
    /** A researcher's face: its role's first letter on a soft wash of its color. */
    private View face(LabBook.Role r, int size) {
        TextView mark = ui.text(r.mark(), size * 0.42f, 760, roleHue(r.id()) | 0xff000000); mark.setGravity(Gravity.CENTER);
        // An opaque wash with a ring of the surface around it, so faces laid over one another stay clean.
        GradientDrawable disc = new GradientDrawable(); disc.setShape(GradientDrawable.OVAL); disc.setColor(Ui.over(ui.tone(roleHue(r.id())), DARK ? RAISED : CARD));
        disc.setStroke(Math.max(1, ui.dp(size >= 34 ? 2 : 1.5f)), DARK ? RAISED : CARD);
        mark.setBackground(disc); mark.setIncludeFontPadding(false);
        mark.setContentDescription(r.name()); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return mark;
    }
    private View fieldTile(LabBook.Project p, int size) {
        ImageView tile = new ImageView(this); tile.setImageResource(R.drawable.ic_lab); int hue = fieldHue(p.field());
        tile.setImageTintList(android.content.res.ColorStateList.valueOf(hue | 0xff000000)); tile.setBackground(ui.shape(ui.tone(hue), Math.max(10, size / 4)));
        int pad = Math.round(size * 0.24f); tile.setPadding(ui.dp(pad), ui.dp(pad), ui.dp(pad), ui.dp(pad)); tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tile;
    }

    /**
     * A research in the folded list (준: "접었을때 아이콘이 똑같아서 뭔지 알 수가 없어": two 생명·의학 ones were the same flask): the
     * first two letters of its title on its field's color, as a chat app shows a group.
     */
    private View initialTile(LabBook.Project p, int size) {
        TextView tile = ui.text(initials(p.title()), size >= 40 ? 14 : 12, 750, fieldHue(p.field()) | 0xff000000);
        tile.setGravity(Gravity.CENTER); tile.setSingleLine(true); tile.setIncludeFontPadding(false);
        tile.setBackground(ui.shape(ui.tone(fieldHue(p.field())), Math.max(10, size / 4))); tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tile;
    }
    /** The first two letters of a title (복시, 축농증 → 복시; Prime gaps → Pr), or "연구" when it has none. */
    static String initials(String title) {
        String letters = (title == null ? "" : title).replaceAll("[^\\p{L}\\p{N}]", "");
        if (letters.isEmpty()) return "연구";
        int end = letters.offsetByCodePoints(0, Math.min(2, letters.codePointCount(0, letters.length())));
        String two = letters.substring(0, end);
        return two.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + two.substring(1);
    }

    // ── the layout ───────────────────────────────────────────────────

    private void draw() {
        wide = wideScreen();
        List<LabBook.Project> projects = book.projects();
        LabBook.Project open = openId == null ? null : book.project(openId);
        if (open == null) openId = null;
        if (open == null && wide && !projects.isEmpty()) { open = projects.get(0); openId = open.id(); }
        store.settings.edit().putString("labOpen", openId == null ? "" : openId).apply();
        if (resumed) LabRunner.onScreen = openId;
        if (dictation != null) dictation.pause();
        String draft = input == null ? "" : input.getText().toString();
        root.removeAllViews(); listColumn = null; content = null; input = null; send = null; debateLine = null; headerStatus = null; activityStrip = null; composerBox = null;
        if (wide) {
            LinearLayout panes = ui.row(); panes.setGravity(Gravity.TOP);
            panes.addView(listPane(projects), new LinearLayout.LayoutParams(ui.dp(folded ? 64 : 252), -1));
            View rule = new View(this); rule.setBackgroundColor(SEPARATOR); panes.addView(rule, new LinearLayout.LayoutParams(Math.max(1, ui.dp(.5f)), -1));
            panes.addView(open == null ? emptyPane() : projectPane(open, true), new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new FrameLayout.LayoutParams(-1, -1));
            navigation.bar(null);
        } else if (open == null) {
            root.addView(listPane(projects), new FrameLayout.LayoutParams(-1, -1));
            navigation.bar(null);
            root.post(() -> { for (int i = 0; i < Math.min(3, projects.size()); i++) warm(book, projects.get(i).id()); });
        } else root.addView(projectPane(open, false), new FrameLayout.LayoutParams(-1, -1));
        if (input != null && !draft.isEmpty()) { input.setText(draft); input.setSelection(draft.length()); }
    }
    private void open(String id) { if (id.equals(openId) && wide) return; openId = id; tab = 0; roomMore = 0; notesMore = 0; expanded.clear(); draw(); }
    private void fold(boolean value) {
        folded = value; store.settings.edit().putBoolean("labListFolded", value).apply();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) android.transition.TransitionManager.beginDelayedTransition(root);
        draw();
    }

    // ── the projects ─────────────────────────────────────────────────

    private View listPane(List<LabBook.Project> projects) {
        ScrollView list = new ScrollView(this); list.setFillViewport(true);
        LinearLayout column = ui.column(); list.addView(column);
        if (wide && folded) { column.setGravity(Gravity.CENTER_HORIZONTAL); column.setPadding(0, ui.dp(4), 0, ui.dp(24)); }
        else column.setPadding(ui.dp(wide ? 8 : 12), ui.dp(4), ui.dp(wide ? 8 : 12), ui.dp(24));
        listColumn = column; fillList(projects);
        return list;
    }
    private void fillList(List<LabBook.Project> projects) {
        LinearLayout column = listColumn; if (column == null) return;
        column.removeAllViews();
        if (wide && folded) {
            column.addView(ui.bareIcon(R.drawable.ic_chevron, MUTED, "연구 목록 펼치기", () -> fold(false)), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            ImageView add = ui.iconButton(R.drawable.ic_add, ACCENT_SOFT, ACCENT, "새 연구", () -> edit(null));
            LinearLayout.LayoutParams addAt = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)); addAt.topMargin = ui.dp(4); addAt.bottomMargin = ui.dp(8); column.addView(add, addAt);
            for (LabBook.Project p : projects) {
                FrameLayout cell = new FrameLayout(this); cell.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
                if (p.id().equals(openId)) cell.setBackground(ui.shape(FILL, 14));
                View tile = initialTile(p, 40); cell.addView(tile, new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40)));
                if (runner.busy(p.id())) cell.addView(dot(ACCENT), corner());
                cell.setOnClickListener(v -> open(p.id())); cell.setContentDescription(p.title()); cell.setTooltipText(p.title()); Ui.pressable(cell, true);
                LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)); at.topMargin = ui.dp(4); at.bottomMargin = ui.dp(4);
                column.addView(cell, at);
            }
            return;
        }
        LinearLayout top = ui.row();
        if (wide) top.addView(ui.bareIcon(R.drawable.ic_back, MUTED, "연구 목록 접기", () -> fold(true)), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView about = ui.label(projects.isEmpty() ? "연구 프로젝트" : "연구 " + projects.size() + "개", 13, MUTED); about.setPadding(ui.dp(4), 0, 0, 0);
        top.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.pill("새 연구", R.drawable.ic_add, true, () -> edit(null)), new LinearLayout.LayoutParams(-2, -2));
        ui.add(column, top, 8);
        if (projects.isEmpty()) { column.addView(welcome()); return; }
        for (LabBook.Project p : projects) column.addView(projectRow(p), new LinearLayout.LayoutParams(-1, -2));
    }
    private View dot(int color) { View d = new View(this); GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color); g.setStroke(ui.dp(2), BG); d.setBackground(g); return d; }
    private FrameLayout.LayoutParams corner() { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ui.dp(12), ui.dp(12), Gravity.END | Gravity.TOP); return p; }
    /** The empty list: what 연구실 is, and three ways to start. */
    private View welcome() {
        LinearLayout box = ui.column(); box.setPadding(ui.dp(4), ui.dp(16), ui.dp(4), ui.dp(8));
        LinearLayout hero = ui.column(); hero.setBackground(ui.glass(R_CARD, false)); hero.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(20));
        LinearLayout faces = ui.row();
        for (int i = 0; i < LabBook.ROLES.size(); i++) {
            View f = face(LabBook.ROLES.get(i), 34); LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); if (i > 0) at.leftMargin = -ui.dp(7);
            f.setElevation(ui.dp(i)); faces.addView(f, at);
        }
        hero.addView(faces);
        TextView title = ui.title("여러 AI가 함께 연구해요", 20); title.setPadding(0, ui.dp(16), 0, ui.dp(6)); hero.addView(title);
        TextView body = ui.label("책임연구원, 문헌조사원, 이론가, 실험가, 비평가, 기록원이 저마다 다른 회사의 모델로 토론하고, 논문을 찾고, 앱 안 리눅스에서 계산해서 검증한 것만 보고서로 묶어요.", 14, MUTED);
        body.setLineSpacing(ui.dp(3), 1); hero.addView(body);
        box.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        TextView ideas = ui.text("이런 연구로 시작해 보세요", 13, 650, MUTED); ideas.setPadding(ui.dp(6), ui.dp(22), 0, ui.dp(8)); box.addView(ideas);
        for (String f : new String[]{"math", "bio", "cs"}) {
            LabBook.Field field = LabBook.field(f);
            LinearLayout row = ui.row(); row.setBackground(ui.glass(R_GROUP, false)); row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            ImageView tile = new ImageView(this); tile.setImageResource(R.drawable.ic_lab); tile.setImageTintList(android.content.res.ColorStateList.valueOf(fieldHue(f) | 0xff000000));
            tile.setBackground(ui.shape(ui.tone(fieldHue(f)), 11)); tile.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); row.addView(tile, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
            LinearLayout words = ui.column(); words.setPadding(ui.dp(12), 0, 0, 0);
            TextView name = ui.text(field.sample(), 14.5f, 600, TEXT); name.setMaxLines(2); words.addView(name);
            words.addView(ui.text(field.name(), 12, 500, MUTED));
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> start(field.sample(), field.sample(), f)); Ui.pressable(row, true);
            ui.add(box, row, 8);
        }
        return box;
    }
    private View projectRow(LabBook.Project p) {
        boolean on = wide && p.id().equals(openId);
        LinearLayout row = ui.row(); row.setPadding(ui.dp(wide ? 8 : 10), ui.dp(9), ui.dp(wide ? 8 : 10), ui.dp(9)); row.setMinimumHeight(ui.dp(62));
        if (on) row.setBackground(ui.shape(FILL, R_CONTROL));
        FrameLayout tileBox = new FrameLayout(this);
        tileBox.addView(fieldTile(p, 42), new FrameLayout.LayoutParams(ui.dp(42), ui.dp(42)));
        if (runner.busy(p.id())) tileBox.addView(dot(ACCENT), corner());
        else if ("waiting".equals(book.autopilot(p.id()))) tileBox.addView(dot(ORANGE), corner());
        row.addView(tileBox, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout words = ui.column(); words.setPadding(ui.dp(12), 0, 0, 0);
        TextView name = ui.text(p.title(), 15.5f, 650, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); words.addView(name);
        String[] st = status(p);
        TextView sub = ui.text(st[0], 13, 500, st[1].equals("busy") ? ACCENT : st[1].equals("wait") ? ORANGE : MUTED); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END); words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(p.title() + ", " + st[0] + (on ? ", 열려 있음" : ""));
        row.setOnClickListener(v -> open(p.id()));
        row.setOnLongClickListener(v -> { projectMenu(v, p); return true; });
        Ui.pressable(row, true);
        return row;
    }
    /** {what the project is doing or where it is, busy|wait|idle}. */
    private String[] status(LabBook.Project p) {
        if (runner.busy(p.id())) { String a = runner.activity(p.id()); return new String[]{a.isBlank() ? "연구 중" : a, "busy"}; }
        String auto = book.autopilot(p.id());
        if ("waiting".equals(auto)) { JSONObject w = book.pending(p.id()); return new String[]{w != null && "ask".equals(w.optString("action")) ? "답을 기다려요" : "승인을 기다려요", "wait"}; }
        if ("paused".equals(auto)) return new String[]{"자율 연구 멈춤 · " + LabBook.field(p.field()).name(), "idle"};
        if ("done".equals(auto)) return new String[]{"자율 연구 완료 · " + LabBook.field(p.field()).name(), "idle"};
        int notes = book.noteCount(p.id());
        return new String[]{LabBook.field(p.field()).name() + (notes == 0 ? " · 아직 시작 전" : " · 노트 " + notes), "idle"};
    }
    private View emptyPane() {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER); box.setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32));
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_lab); mark.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));
        box.addView(mark, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView words = ui.label("왼쪽에서 ‘새 연구’를 눌러 첫 연구를 시작해 보세요.", 15, MUTED); words.setGravity(Gravity.CENTER); words.setPadding(0, ui.dp(12), 0, 0); box.addView(words);
        return box;
    }

    // ── one project ──────────────────────────────────────────────────

    private LinearLayout headerBox;
    /** The five views' switch: render keeps it on the view shown, also when the screen moves to another view itself. */
    private LinearLayout tabSwitch;
    /** 산출물's line saying what the 기록원 is doing while a report is written. */
    private TextView reportNow;
    /** A scroll to 토론방's newest words not drawn yet: a render right after it (a job reporting it started) must not undo it. */
    private boolean endPending;
    private View projectPane(LabBook.Project p, boolean inPane) {
        LinearLayout pane = ui.column();
        headerBox = ui.row(); headerBox.setGravity(Gravity.CENTER_VERTICAL);
        if (inPane) { pane.addView(headerBox, new LinearLayout.LayoutParams(-1, -2)); headerBox.setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(6)); }
        else navigation.bar(headerBox);
        header();
        // The five views as one small switch, as wide as its words (scrolls sideways on a narrow phone).
        HorizontalScrollView tabStrip = new HorizontalScrollView(this); tabStrip.setHorizontalScrollBarEnabled(false); tabStrip.setClipToPadding(false);
        tabStrip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        tabSwitch = ui.switcher(TABS, tab, i -> { tab = i; expanded.clear(); render(true); });
        tabStrip.addView(tabSwitch);
        LinearLayout.LayoutParams tabsAt = new LinearLayout.LayoutParams(-1, -2); tabsAt.topMargin = ui.dp(inPane ? 2 : 4); tabsAt.bottomMargin = ui.dp(4);
        pane.addView(tabStrip, tabsAt);
        activityStrip = ui.row(); activityStrip.setPadding(ui.dp(16), ui.dp(2), ui.dp(8), ui.dp(2)); activityStrip.setVisibility(View.GONE);
        View pulse = dot(ACCENT); LinearLayout.LayoutParams pulseAt = new LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)); pulseAt.rightMargin = ui.dp(8); activityStrip.addView(pulse, pulseAt);
        activityText = new ShimmerText(this, MUTED, TEXT); activityText.setTextSize(13); activityText.setTypeface(Ui.weight(560)); activityText.setSingleLine(true); activityText.setEllipsize(TextUtils.TruncateAt.END);
        activityStrip.addView(activityText, new LinearLayout.LayoutParams(0, -2, 1));
        activityStrip.addView(ui.pill("멈추기", R.drawable.ic_stop, false, () -> runner.stop(openId)), new LinearLayout.LayoutParams(-2, -2));
        pane.addView(activityStrip, new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content = ui.column(); content.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(20));
        scroll.addView(content);
        pane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        composerBox = composer(p);
        pane.addView(composerBox, new LinearLayout.LayoutParams(-1, -2));
        render(true);
        return pane;
    }
    /** The header: back (phone), the project's tile and name with where it is, 자율 연구, and the menu. */
    private void header() {
        LinearLayout h = headerBox; if (h == null || openId == null) return;
        LabBook.Project p = book.project(openId); if (p == null) return;
        h.removeAllViews();
        if (!wide) h.addView(ui.bareIcon(R.drawable.ic_back, TEXT, "연구 목록으로", () -> { openId = null; draw(); }), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        h.addView(fieldTile(p, 34), new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout who = ui.column(); who.setPadding(ui.dp(10), 0, ui.dp(6), 0);
        TextView name = ui.text(p.title(), 16, 700, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); who.addView(name);
        String[] st = status(p);
        headerStatus = ui.text(st[0], 12, 520, st[1].equals("busy") ? ACCENT : st[1].equals("wait") ? ORANGE : MUTED); headerStatus.setSingleLine(true); headerStatus.setEllipsize(TextUtils.TruncateAt.END); who.addView(headerStatus);
        who.setOnClickListener(v -> edit(p)); Ui.pressable(who, true);
        h.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        boolean busy = runner.busy(p.id()); String auto = book.autopilot(p.id());
        String go = "waiting".equals(auto) || "paused".equals(auto) ? "이어서 연구" : "자율 연구";
        // A phone's bar is narrow: the sparkle alone there, the words beside it on a wide screen.
        if (!busy && wide) h.addView(ui.pill(go, R.drawable.ic_sparkle, true, () -> autopilot(p)), new LinearLayout.LayoutParams(-2, -2));
        else if (!busy) { ImageView spark = ui.iconButton(R.drawable.ic_sparkle, ACCENT_SOFT, ACCENT, go, () -> autopilot(p)); spark.setTooltipText(go); h.addView(spark, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))); }
        ImageView more = ui.bareIcon(R.drawable.ic_more, TEXT, "연구 메뉴", () -> {}); more.setOnClickListener(v -> projectMenu(v, p));
        h.addView(more, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
    }
    private void autopilot(LabBook.Project p) {
        String auto = book.autopilot(p.id());
        if ("waiting".equals(auto)) {
            JSONObject pending = book.pending(p.id());
            if (pending != null && "ask".equals(pending.optString("action"))) { tab = 0; render(true); if (input != null) { input.requestFocus(); Toast.makeText(this, "책임연구원의 질문에 아래 입력창으로 답해 주세요.", Toast.LENGTH_LONG).show(); } return; }
            if (pending != null) { tab = 4; render(true); return; }
        }
        if (!runner.autopilot(p.id(), false)) Toast.makeText(this, "지금은 다른 작업이 진행 중이에요.", Toast.LENGTH_SHORT).show();
        else { tab = 0; render(true); }
    }

    /** The open view again; toEnd scrolls 토론방 to the newest words (also when it was near them already). */
    private void render(boolean toEnd) {
        if (content == null || openId == null) return;
        LabBook.Project p = book.project(openId); if (p == null) { draw(); return; }
        boolean nearEnd = scroll != null && content.getHeight() - (scroll.getScrollY() + scroll.getHeight()) < ui.dp(160);
        int keep = scroll == null ? 0 : scroll.getScrollY();
        content.removeAllViews(); readNotes = null;
        if (tabSwitch != null) ui.selectSegment(tabSwitch, TABS, tab);
        boolean busy = runner.busy(p.id());
        if (activityStrip != null) { activityStrip.setVisibility(busy ? View.VISIBLE : View.GONE); if (busy) activityText.setText(runner.activity(p.id())); }
        if (composerBox != null) composerBox.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        switch (tab) {
            case 1 -> notesView(p);
            case 2 -> outputsView(p);
            case 3 -> teamView(p);
            case 4 -> boardView(p);
            default -> roomView(p);
        }
        composerLooks(p);
        boolean end = tab == 0 && (toEnd || nearEnd || endPending) && !notes(p).isEmpty();
        // Which scroll the next frame takes: the newest words when asked, else where it was (or the top of a newly opened view).
        boolean top = toEnd && !end; int stay = endPending ? -1 : keep; endPending = end;
        // Placed before the next frame is drawn: the view opens where it should (the newest words), with no scroll to watch.
        ScrollView at = scroll;
        at.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                at.getViewTreeObserver().removeOnPreDrawListener(this);
                if (content != null) { if (end) at.scrollTo(0, Math.max(0, content.getHeight() - at.getHeight())); else if (top) at.scrollTo(0, 0); else if (stay >= 0) at.scrollTo(0, stay); }
                endPending = false;
                return true;
            }
        });
    }

    /** The notes for one drawing of the view: read once, however many parts of it ask. */
    private List<LabBook.Note> readNotes; private String readFor = "";
    private List<LabBook.Note> notes(LabBook.Project p) {
        if (readNotes == null || !readFor.equals(p.id())) { readNotes = book.notes(p.id()); readFor = p.id(); }
        return readNotes;
    }
    /** 토론방 draws the newest ROOM entries; 이전 기록 더 보기 draws the rest (they are all in 노트 too). */
    private static final int ROOM = 12, MORE = 30, NOTE_ROWS = 40; private int roomMore, notesMore;

    // ── 토론방 ────────────────────────────────────────────────────────

    private void roomView(LabBook.Project p) {
        List<LabBook.Note> notes = notes(p);
        if (notes.isEmpty() && !runner.busy(p.id())) { content.addView(intro(p)); return; }
        long lastDay = -1;
        int from = Math.max(0, notes.size() - ROOM - roomMore);
        if (from > 0) {
            TextView earlier = ui.text("이전 기록 " + from + "개 더 보기", 13, 650, ACCENT); earlier.setGravity(Gravity.CENTER); earlier.setPadding(0, ui.dp(10), 0, ui.dp(10));
            earlier.setOnClickListener(v -> { roomMore += MORE; render(false); }); Ui.pressable(earlier, true); content.addView(earlier);
        }
        for (int i = from; i < notes.size(); i++) {
            LabBook.Note n = notes.get(i); long d = n.at() / 86_400_000L;
            if (d != lastDay) { lastDay = d; content.addView(dayMark(n.at())); }
            View v = noteView(p, n, false); if (v != null) content.addView(v);
        }
        for (String role : runner.speaking(p.id())) content.addView(typing(LabBook.role(role)));
    }
    private View dayMark(long at) {
        TextView t = ui.text(day.format(new Date(at)), 12, 560, MUTED); t.setGravity(Gravity.CENTER); t.setPadding(0, ui.dp(10), 0, ui.dp(6)); return t;
    }
    /** An empty project: its question, its researchers, and the first steps. */
    private View intro(LabBook.Project p) {
        LinearLayout box = ui.column(); box.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout card = ui.column(); card.setBackground(ui.glass(R_CARD, false)); card.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(18));
        TextView kicker = ui.text(LabBook.field(p.field()).name() + " 연구", 12.5f, 650, fieldHue(p.field()) | 0xff000000); card.addView(kicker);
        TextView title = ui.title(p.title(), 21); title.setPadding(0, ui.dp(6), 0, ui.dp(6)); card.addView(title);
        if (!p.question().isBlank() && !p.question().equals(p.title())) { TextView q = ui.label(p.question(), 14, MUTED); q.setLineSpacing(ui.dp(3), 1); card.addView(q); }
        LinearLayout faces = ui.row(); faces.setPadding(0, ui.dp(16), 0, 0);
        for (int i = 0; i < LabBook.ROLES.size(); i++) {
            LabBook.Role r = LabBook.ROLES.get(i);
            LinearLayout who = ui.column(); who.setGravity(Gravity.CENTER_HORIZONTAL);
            who.addView(face(r, 36), new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
            TextView nm = ui.text(r.name(), 10.5f, 560, MUTED); nm.setPadding(0, ui.dp(4), 0, 0); nm.setSingleLine(true); who.addView(nm);
            faces.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        }
        card.addView(faces);
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView how = ui.text("어떻게 시작할까요?", 13, 650, MUTED); how.setPadding(ui.dp(6), ui.dp(20), 0, ui.dp(8)); box.addView(how);
        LinearLayout starts = ui.group();
        ui.addRow(starts, ui.listRow(R.drawable.ic_sparkle, ACCENT, "자율 연구", "책임연구원이 계획부터 보고서까지 알아서 진행해요", ui.chevron(), () -> autopilot(p)), true);
        ui.addRow(starts, ui.listRow(R.drawable.ic_team, roleHue("theorist"), "토론 시작", "연구 질문으로 연구원들이 주장하고 반박해요", ui.chevron(), () -> act(p, "debate")), true);
        ui.addRow(starts, ui.listRow(R.drawable.ic_search, roleHue("scout"), "문헌 조사", "논문을 찾아 출처 번호와 함께 정리해요", ui.chevron(), () -> act(p, "search")), true);
        ui.addRow(starts, ui.listRow(R.drawable.ic_bulb, roleHue("pi"), "계획 세우기", "질문을 나누고 가설과 방법을 정해요", ui.chevron(), () -> act(p, "plan")), true);
        box.addView(starts, new LinearLayout.LayoutParams(-1, -2));
        TextView tip = ui.footer("아래 입력창에 적으면 연구원들이 그 주제로 토론해요. ‘@비평가’처럼 이름을 붙이면 그 연구원만 답해요.");
        tip.setPadding(ui.dp(6), ui.dp(14), ui.dp(6), 0); box.addView(tip);
        return box;
    }
    private View typing(LabBook.Role r) {
        LinearLayout row = ui.row(); row.setPadding(0, ui.dp(8), 0, ui.dp(4));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)); at.rightMargin = ui.dp(10);
        row.addView(face(r, 30), at);
        ShimmerText t = new ShimmerText(this, MUTED, TEXT); t.setText(LabBook.ida(r.name()) + " 생각하는 중"); t.setTextSize(13.5f); t.setTypeface(Ui.weight(520));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    /**
     * The entries as drawn, kept while they look the same (what is opened in them, an ask still waiting): a redraw (a job
     * moved, 더 보기, back from another tab) puts the same views back instead of building and laying out their words again.
     */
    private final java.util.LinkedHashMap<String, View> drawn = new java.util.LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, View> e) { return size() > 90; }
    };
    private String looks(LabBook.Project p, LabBook.Note n, boolean compact) {
        StringBuilder k = new StringBuilder(n.id()).append(compact ? "|c" : "|r");
        for (String part : new String[]{"", ":src", ":out", ":code"}) if (expanded.contains(n.id() + part)) k.append('|').append(part.isEmpty() ? "open" : part);
        if ("ask".equals(n.kind())) k.append(live(p, n) ? "|live" : "|done");
        return k.toString();
    }
    /** One entry as 토론방 shows it (compact: as 노트 shows it opened). */
    private View noteView(LabBook.Project p, LabBook.Note n, boolean compact) {
        String key = looks(p, n, compact);
        View kept = drawn.get(key);
        if (kept != null) { if (kept.getParent() instanceof ViewGroup parent) parent.removeView(kept); return kept; }
        View made = buildNote(p, n, compact);
        if (made != null) drawn.put(key, made);
        return made;
    }
    private View buildNote(LabBook.Project p, LabBook.Note n, boolean compact) {
        return switch (n.kind()) {
            case "user" -> mine(n);
            case "system" -> systemLine(n.text(), MUTED);
            case "error" -> systemLine((n.role().isBlank() ? "" : LabBook.role(n.role()).name() + " · ") + n.text(), ERROR);
            case "step" -> stepLine(n);
            case "judge" -> highlight(n, "토론 정리", R.drawable.ic_sparkle, ACCENT);
            case "report" -> highlight(n, n.data().optBoolean("mid") ? "중간 보고서" : "연구 보고서", R.drawable.ic_pages, roleHue("scribe"));
            case "learned" -> highlight(n, "지금까지 알게 된 것", R.drawable.ic_bulb, roleHue("pi"));
            case "ideas" -> highlight(n, "새 아이디어 · 새로움 확인", R.drawable.ic_bulb, roleHue("pi"));
            case "prereg" -> highlight(n, "사전 등록 · 예측과 반증 기준", R.drawable.ic_check, roleHue("pi"));
            case "verdict" -> highlight(n, "판정 · " + LabBook.verdictName(n.data().optString("verdict")), R.drawable.ic_sparkle, "supported".equals(n.data().optString("verdict")) ? GREEN : "refuted".equals(n.data().optString("verdict")) ? RED : ORANGE);
            case "paper" -> highlight(n, "논문 v" + n.data().optInt("version", 1) + (n.data().optBoolean("revised") ? " · 심사 반영" : ""), R.drawable.ic_pages, roleHue("scribe"));
            case "data", "sheets" -> systemLine(n.text(), roleHue("experimenter") | 0xff000000);
            case "search" -> searchCard(p, n);
            case "run" -> runCard(p, n);
            case "ask" -> askCard(p, n);
            default -> said(n);
        };
    }
    private View mine(LabBook.Note n) {
        LinearLayout wrap = ui.column(); wrap.setGravity(Gravity.END); wrap.setPadding(ui.dp(48), ui.dp(6), 0, ui.dp(6));
        TextView text = ui.text(n.text(), 15, 500, ON_ACCENT); text.setLineSpacing(ui.dp(3), 1); text.setTextIsSelectable(true);
        text.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(10));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(ACCENT); float round = ui.dp(18), tail = ui.dp(5);
        shape.setCornerRadii(new float[]{round, round, round, round, tail, tail, round, round}); text.setBackground(shape);
        wrap.addView(text, new LinearLayout.LayoutParams(-2, -2));
        String tag = n.data().optBoolean("debate") ? "토론 주제" : n.data().optBoolean("answer") ? "책임연구원에게 답" : n.data().has("approval") ? "결정" : "";
        TextView meta = ui.text((tag.isEmpty() ? "" : tag + " · ") + clock.format(new Date(n.at())), 11.5f, 450, MUTED); meta.setPadding(0, ui.dp(3), ui.dp(4), 0);
        wrap.addView(meta);
        return wrap;
    }
    private View systemLine(String text, int color) {
        TextView t = ui.text(text, 12.5f, 500, color); t.setGravity(Gravity.CENTER); t.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8)); t.setLineSpacing(ui.dp(2), 1);
        return t;
    }
    private View stepLine(LabBook.Note n) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(6));
        ImageView arrow = new ImageView(this); arrow.setImageResource(R.drawable.ic_next); arrow.setImageTintList(android.content.res.ColorStateList.valueOf(roleHue("pi") | 0xff000000));
        arrow.setBackground(ui.shape(ui.tone(roleHue("pi")), 99)); arrow.setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)); at.rightMargin = ui.dp(10); at.topMargin = ui.dp(1); row.addView(arrow, at);
        LinearLayout words = ui.column();
        SpannableStringBuilder head = new SpannableStringBuilder("다음 단계 · " + LabBook.actionName(n.data().optString("action")));
        head.setSpan(new StyleSpan(Typeface.BOLD), 0, head.length(), 0);
        String focus = n.data().optString("focus"); if (!focus.isBlank()) { int s = head.length(); head.append("  ").append(focus); head.setSpan(new ForegroundColorSpan(MUTED), s, head.length(), 0); }
        TextView h = ui.text("", 13.5f, 500, TEXT); h.setText(head); words.addView(h);
        if (!n.text().isBlank()) { TextView why = ui.text(n.text(), 12.5f, 450, MUTED); why.setPadding(0, ui.dp(2), 0, 0); words.addView(why); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    /** A researcher speaking: face, name and what kind of words, the words in a card, then who answered. */
    private View said(LabBook.Note n) {
        LabBook.Role r = LabBook.role(n.role());
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(0, ui.dp(8), ui.dp(12), ui.dp(4));
        LinearLayout.LayoutParams faceAt = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)); faceAt.rightMargin = ui.dp(10); faceAt.topMargin = ui.dp(2);
        row.addView(face(r, 32), faceAt);
        LinearLayout col = ui.column();
        LinearLayout top = ui.row();
        top.addView(ui.text(r.name(), 14, 700, TEXT));
        String tag = switch (n.kind()) {
            case "say" -> n.data().optBoolean("direct") ? "답변" : n.data().has("round") ? n.data().optInt("round") + "라운드" : "";
            case "plan" -> "연구 계획"; case "analysis" -> "실험 E" + n.data().optInt("n") + " 분석"; case "critique" -> "비평";
            case "peer" -> "동료 심사 · " + LabBook.decisionName(n.data().optString("decision"));
            default -> LabBook.kindName(n.kind());
        };
        if (!tag.isEmpty()) { TextView t = ui.text(tag, 11.5f, 600, roleHue(r.id()) | 0xff000000); t.setBackground(ui.shape(ui.tone(roleHue(r.id())), 99)); t.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2));
            LinearLayout.LayoutParams ta = new LinearLayout.LayoutParams(-2, -2); ta.leftMargin = ui.dp(8); top.addView(t, ta); }
        View gap = new View(this); top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(ui.text(clock.format(new Date(n.at())), 11.5f, 450, MUTED));
        col.addView(top);
        LinearLayout card = ui.column(); card.setBackground(ui.shape(DARK ? RAISED : CARD, 18)); card.setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(12));
        card.addView(body(n, 14));
        LinearLayout.LayoutParams cardAt = new LinearLayout.LayoutParams(-1, -2); cardAt.topMargin = ui.dp(6); col.addView(card, cardAt);
        col.addView(foot(n));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    /** The words of an entry, folded past lines unless opened (더 보기 · 접기). */
    private View body(LabBook.Note n, int lines) {
        LinearLayout box = ui.column();
        TextView text = ui.text("", 15, 420, TEXT); text.setLineSpacing(ui.dp(3), 1); text.setTextIsSelectable(true);
        boolean open = expanded.contains(n.id());
        // A paper's figures are named where they stand (the page made from it shows them).
        String all = "paper".equals(n.kind()) ? figureNames(n.text()) : n.text();
        // Folded, only the start that can show is formatted and laid out (a long answer is the slow part of a redraw).
        String shown = open ? all : folded(all, lines); boolean cut = shown.length() < all.length();
        text.setText(RichText.render(shown));
        box.addView(text);
        if (!open) { text.setMaxLines(lines); text.setEllipsize(TextUtils.TruncateAt.END); }
        TextView more = ui.text(open ? "접기" : "더 보기", 13, 650, ACCENT); more.setPadding(0, ui.dp(6), ui.dp(12), ui.dp(2)); more.setVisibility(View.GONE);
        more.setOnClickListener(v -> { if (!expanded.remove(n.id())) expanded.add(n.id()); render(false); }); Ui.pressable(more, true);
        box.addView(more);
        if (open || cut) more.setVisibility(View.VISIBLE);
        else text.post(() -> { android.text.Layout l = text.getLayout(); boolean longer = l != null && l.getLineCount() > 0 && (l.getEllipsisCount(l.getLineCount() - 1) > 0 || text.getLineCount() > lines); if (longer) more.setVisibility(View.VISIBLE); });
        return box;
    }
    /** The start of words folded at lines that can show (cut at a line break when one is near): all that is formatted. */
    static String folded(String text, int lines) {
        if (text.length() <= lines * 90) return text;
        int at = text.lastIndexOf('\n', lines * 90); return text.substring(0, at > lines * 40 ? at : lines * 90);
    }
    /** How many lines 토론방 shows of an entry before 더 보기 (0: drawn whole, -1: no formatted words). */
    static int foldAt(LabBook.Note n) {
        return switch (n.kind()) {
            case "user", "system", "error", "step" -> -1;
            case "judge", "learned", "ideas", "prereg", "verdict" -> 24; case "report", "paper" -> 18; case "run" -> 4; case "ask" -> 0; case "data", "sheets" -> -1;
            default -> 14;
        };
    }
    /**
     * Gets a project's newest entries ready off the main thread (read, and their words formatted into RichText's keep),
     * so opening it draws at once. Quiet, at the lowest priority; a failure only means the screen does that work itself.
     */
    static void warm(LabBook book, String id) {
        if (id == null || id.isEmpty() || Ui.TEXT == 0) return;
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                List<LabBook.Note> notes = book.notes(id);
                for (int i = Math.max(0, notes.size() - ROOM); i < notes.size(); i++) {
                    LabBook.Note n = notes.get(i); int at = foldAt(n);
                    if (at >= 0 && !n.text().isBlank()) RichText.render(at == 0 ? n.text() : folded(n.text(), at));
                }
            } catch (RuntimeException ignored) {}
        }, "gyeot-lab-warm");
        t.setDaemon(true); t.start();
    }
    /** The two projects touched last, got ready (the app has just started; one of them is likely opened next). */
    static void warmRecent(android.content.Context c) {
        LabBook book = LabRunner.get(c).book();
        new Thread(() -> { List<LabBook.Project> all = book.projects(); for (int i = 0; i < Math.min(2, all.size()); i++) warm(book, all.get(i).id()); }, "gyeot-lab-warm").start();
    }
    private View foot(LabBook.Note n) {
        LinearLayout foot = ui.row(); foot.setPadding(ui.dp(2), 0, 0, 0);
        if (!n.by().isBlank()) { TextView by = ui.text(n.by(), 11.5f, 450, MUTED); by.setSingleLine(true); by.setEllipsize(TextUtils.TruncateAt.END); foot.addView(by, new LinearLayout.LayoutParams(0, -2, 1)); }
        else foot.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        ImageView copy = ui.quietIcon(R.drawable.ic_copy, "복사", () -> copy(n.text()));
        copy.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); foot.addView(copy, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        return foot;
    }
    /** 토론 정리 and 보고서: a full-width card on a wash of its color, the researcher who wrote it at its head. */
    private View highlight(LabBook.Note n, String title, int icon, int hue) {
        LinearLayout card = ui.column(); card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(10));
        GradientDrawable bg = ui.shape(DARK ? RAISED : CARD, R_CARD); bg.setStroke(Math.max(1, ui.dp(1)), (hue & 0x00ffffff) | 0x55000000); card.setBackground(bg);
        LinearLayout top = ui.row();
        ImageView mark = new ImageView(this); mark.setImageResource(icon); mark.setImageTintList(android.content.res.ColorStateList.valueOf(hue | 0xff000000));
        mark.setBackground(ui.shape(ui.tone(hue), 10)); mark.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        LinearLayout.LayoutParams ma = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)); ma.rightMargin = ui.dp(10); top.addView(mark, ma);
        LinearLayout words = ui.column();
        words.addView(ui.text(title, 15, 720, TEXT));
        String topic = n.data().optString("topic");
        words.addView(ui.text(LabBook.role(n.role()).name() + (topic.isBlank() ? "" : " · " + LabBook.clip(topic, 60)), 12, 500, MUTED));
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.text(clock.format(new Date(n.at())), 11.5f, 450, MUTED));
        card.addView(top);
        View rule = new View(this); rule.setBackgroundColor(SEPARATOR); LinearLayout.LayoutParams ra = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f))); ra.topMargin = ui.dp(12); ra.bottomMargin = ui.dp(10); card.addView(rule, ra);
        card.addView(body(n, "report".equals(n.kind()) ? 18 : 24));
        card.addView(foot(n));
        LinearLayout wrap = ui.column(); wrap.setPadding(0, ui.dp(10), 0, ui.dp(8)); wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }
    private View searchCard(LabBook.Project p, LabBook.Note n) {
        LinearLayout card = ui.column(); card.setBackground(ui.shape(DARK ? RAISED : CARD, R_CARD)); card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(10));
        JSONArray keys = n.data().optJSONArray("sources"); int count = keys == null ? 0 : keys.length();
        LinearLayout top = ui.row();
        LinearLayout.LayoutParams fa = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)); fa.rightMargin = ui.dp(10); top.addView(face(LabBook.SCOUT, 28), fa);
        LinearLayout words = ui.column();
        words.addView(ui.text("문헌 조사 · 논문 " + count + "편", 15, 720, TEXT));
        JSONArray q = n.data().optJSONArray("queries"); List<String> qs = new ArrayList<>(); if (q != null) for (int i = 0; i < q.length(); i++) qs.add(q.optString(i));
        if (!qs.isEmpty()) { TextView qt = ui.text("검색어 · " + String.join(" / ", qs), 12, 480, MUTED); qt.setMaxLines(2); qt.setEllipsize(TextUtils.TruncateAt.END); words.addView(qt); }
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.text(clock.format(new Date(n.at())), 11.5f, 450, MUTED));
        card.addView(top);
        LinearLayout.LayoutParams ba = new LinearLayout.LayoutParams(-1, -2); ba.topMargin = ui.dp(10); card.addView(body(n, 14), ba);
        if (count > 0) {
            List<LabBook.Source> all = book.sources(p.id());
            LinearLayout list = ui.column(); list.setBackground(ui.shape(FILL, 14)); list.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            int shown = 0;
            for (int i = 0; i < count && shown < (expanded.contains(n.id() + ":src") ? count : 4); i++) {
                String key = keys.optString(i);
                for (LabBook.Source s : all) if (s.key().equals(key)) { list.addView(sourceRow(s)); shown++; break; }
            }
            if (count > 4) { TextView more = ui.text(expanded.contains(n.id() + ":src") ? "접기" : "논문 " + (count - 4) + "편 더 보기", 13, 650, ACCENT); more.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
                more.setOnClickListener(v -> { String k = n.id() + ":src"; if (!expanded.remove(k)) expanded.add(k); render(false); }); Ui.pressable(more, true); list.addView(more); }
            LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(-1, -2); la.topMargin = ui.dp(10); card.addView(list, la);
        }
        card.addView(foot(n));
        LinearLayout wrap = ui.column(); wrap.setPadding(0, ui.dp(8), 0, ui.dp(6)); wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }
    private View sourceRow(LabBook.Source s) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        TextView key = ui.text(s.key(), 11.5f, 700, roleHue("scout") | 0xff000000); key.setBackground(ui.shape(ui.tone(roleHue("scout")), 8)); key.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        LinearLayout.LayoutParams ka = new LinearLayout.LayoutParams(-2, -2); ka.rightMargin = ui.dp(10); ka.topMargin = ui.dp(1); row.addView(key, ka);
        LinearLayout words = ui.column();
        TextView title = ui.text(s.title(), 13.5f, 600, TEXT); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); words.addView(title);
        String meta = String.join(" · ", nonEmpty(s.authors(), s.year(), s.venue()));
        if (!meta.isEmpty()) { TextView m = ui.text(meta, 12, 450, MUTED); m.setSingleLine(true); m.setEllipsize(TextUtils.TruncateAt.END); words.addView(m); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (!s.url().isBlank()) { row.setOnClickListener(v -> openUrl(s.url())); Ui.pressable(row, true); row.setContentDescription(s.key() + " " + s.title() + ", 열기"); }
        return row;
    }
    private static List<String> nonEmpty(String... parts) { List<String> out = new ArrayList<>(); for (String s : parts) if (s != null && !s.isBlank()) out.add(s); return out; }
    private View runCard(LabBook.Project p, LabBook.Note n) {
        JSONObject d = n.data(); int number = d.optInt("n"); boolean ok = d.optInt("exit") == 0;
        LinearLayout card = ui.column(); card.setBackground(ui.shape(DARK ? RAISED : CARD, R_CARD)); card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(10));
        LinearLayout top = ui.row();
        LinearLayout.LayoutParams fa = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)); fa.rightMargin = ui.dp(10); top.addView(face(LabBook.EXPERIMENTER, 28), fa);
        LinearLayout words = ui.column();
        LinearLayout titleRow = ui.row();
        titleRow.addView(ui.text("실험 E" + number, 15, 720, TEXT));
        TextView badge = ui.badge(ok ? "성공" : "실패", ok ? GREEN : RED); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.leftMargin = ui.dp(8); titleRow.addView(badge, bp);
        words.addView(titleRow);
        String meta = String.format(Locale.KOREA, "%.1f초", d.optLong("millis") / 1000.0) + (d.optInt("attempts") > 1 ? " · 고쳐서 " + d.optInt("attempts") + "번째" : "");
        words.addView(ui.text(meta, 12, 480, MUTED));
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.text(clock.format(new Date(n.at())), 11.5f, 450, MUTED));
        card.addView(top);
        String goal = d.optString("goal");
        if (!goal.isBlank()) { TextView g = ui.text("목표 · " + goal, 13.5f, 560, TEXT); g.setPadding(0, ui.dp(10), 0, 0); g.setLineSpacing(ui.dp(2), 1); card.addView(g); }
        if (!n.text().isBlank()) { LinearLayout.LayoutParams ba = new LinearLayout.LayoutParams(-1, -2); ba.topMargin = ui.dp(6); card.addView(body(n, 4), ba); }
        JSONArray figs = d.optJSONArray("figures");
        if (figs != null) for (int i = 0; i < figs.length(); i++) {
            File f = new File(book.files(p.id()), figs.optString(i));
            ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (!showFigure(image, f)) continue;
            image.setBackground(ui.shape(DARK ? 0xfff4f2ee : 0xffffffff, 14)); image.setClipToOutline(true); image.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            image.setContentDescription("실험 E" + number + " 그림 " + (i + 1));
            image.setOnClickListener(v -> showFigure(f)); Ui.pressable(image, true);
            LinearLayout.LayoutParams ia = new LinearLayout.LayoutParams(-1, -2); ia.topMargin = ui.dp(10); card.addView(image, ia);
        }
        String output = d.optString("output");
        if (!output.isBlank()) card.addView(mono("출력", output, n.id() + ":out", 10));
        card.addView(mono("코드", d.optString("code"), n.id() + ":code", 0));
        card.addView(foot(n));
        LinearLayout wrap = ui.column(); wrap.setPadding(0, ui.dp(8), 0, ui.dp(6)); wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }
    /** A block of code or output: a title line that opens it (code starts closed; output shows its first lines). */
    private View mono(String title, String text, String key, int preview) {
        LinearLayout box = ui.column(); boolean open = expanded.contains(key);
        TextView head = ui.text((open ? "▾ " : "▸ ") + title, 13, 650, MUTED); head.setPadding(0, ui.dp(12), 0, ui.dp(6));
        head.setOnClickListener(v -> { if (!expanded.remove(key)) expanded.add(key); render(false); }); Ui.pressable(head, true);
        box.addView(head);
        if (open || preview > 0) {
            TextView t = ui.text(text, 12, 450, TEXT); t.setTypeface(Typeface.MONOSPACE); t.setTextIsSelectable(true); t.setLineSpacing(ui.dp(1.5f), 1);
            t.setBackground(ui.shape(FILL, 12)); t.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            if (!open) { t.setMaxLines(preview); t.setEllipsize(TextUtils.TruncateAt.END); }
            box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        return box;
    }
    /** The ask still waits for its answer (the newest ask, and the one 자율 연구 holds for). */
    private boolean live(LabBook.Project p, LabBook.Note n) {
        JSONObject pending = book.pending(p.id());
        return pending != null && pending.optString("focus").equals(n.data().optString("focus")) && "waiting".equals(book.autopilot(p.id())) && isLastAsk(p, n);
    }
    private View askCard(LabBook.Project p, LabBook.Note n) {
        boolean live = live(p, n);
        boolean experiment = "experiment".equals(n.data().optString("action"));
        LinearLayout card = ui.column(); card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        GradientDrawable bg = ui.shape(ui.tone(ORANGE), R_CARD); card.setBackground(bg);
        LinearLayout top = ui.row();
        LinearLayout.LayoutParams fa = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)); fa.rightMargin = ui.dp(10); top.addView(face(LabBook.PI, 28), fa);
        top.addView(ui.text(experiment ? "실험 승인 요청" : "책임연구원의 질문", 15, 720, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        if (!live) top.addView(ui.text("답함", 12, 560, MUTED));
        card.addView(top);
        TextView t = ui.text("", 14.5f, 450, TEXT); t.setText(RichText.render(n.text())); t.setLineSpacing(ui.dp(3), 1); t.setPadding(0, ui.dp(10), 0, 0); card.addView(t);
        if (live && experiment) {
            LinearLayout buttons = ui.row(); buttons.setPadding(0, ui.dp(12), 0, 0);
            buttons.addView(ui.pill("승인하고 실행", R.drawable.ic_play, true, () -> approve(p, true)), new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
            buttons.addView(ui.pill("건너뛰기", 0, false, () -> approve(p, false)), gap);
            card.addView(buttons);
        } else if (live) {
            TextView hint = ui.text("아래 입력창에 답을 적으면 이어서 연구해요.", 12.5f, 520, MUTED); hint.setPadding(0, ui.dp(10), 0, 0); card.addView(hint);
        }
        LinearLayout wrap = ui.column(); wrap.setPadding(0, ui.dp(8), 0, ui.dp(6)); wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }
    private boolean isLastAsk(LabBook.Project p, LabBook.Note n) {
        List<LabBook.Note> all = notes(p);
        for (int i = all.size() - 1; i >= 0; i--) if ("ask".equals(all.get(i).kind())) return all.get(i).id().equals(n.id());
        return false;
    }
    private void approve(LabBook.Project p, boolean yes) {
        if (yes && !LabPython.ready(this)) { Toast.makeText(this, "계산 환경이 아직 없어요. 진행판에서 준비해 주세요.", Toast.LENGTH_LONG).show(); tab = 4; render(true); return; }
        try { if (!runner.approve(p.id(), yes)) Toast.makeText(this, "지금은 다른 작업이 진행 중이에요.", Toast.LENGTH_SHORT).show(); }
        catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        tab = 0; render(true);
    }

    // ── the composer ─────────────────────────────────────────────────

    private View composer(LabBook.Project p) {
        LinearLayout composer = ui.column(); composer.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8));
        LinearLayout box = ui.column(); box.setBackground(ui.glass(28, false)); ui.lift(box, 4); box.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4));
        composer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        input = new EditText(this); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setMaxLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        input.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(2));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(ui.bareIcon(R.drawable.ic_add, TEXT, "연구 작업: 토론, 문헌 조사, 실험, 비평, 보고서", () -> {}), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        View plus = bar.getChildAt(0); plus.setOnClickListener(v -> actions(v, p));
        TextView at = ui.text("@", 19, 600, TEXT); at.setGravity(Gravity.CENTER); at.setContentDescription("연구원 한 명 부르기"); at.setOnClickListener(v -> mention(v)); Ui.pressable(at, true);
        bar.addView(at, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(44)));
        debateLine = ui.text("", 13, 500, TEXT); debateLine.setSingleLine(true); debateLine.setEllipsize(TextUtils.TruncateAt.END);
        debateLine.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); debateLine.setPadding(ui.dp(8), 0, ui.dp(6), 0); debateLine.setMinHeight(ui.dp(44));
        debateLine.setOnClickListener(v -> debateSettings()); Ui.pressable(debateLine, true);
        bar.addView(debateLine, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.bareIcon(R.drawable.ic_mic, TEXT, "음성 입력 · 입력창에만 넣기", this::dictate), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        send = ui.sendButton("보내기", () -> { if (openId != null && runner.busy(openId)) runner.stop(openId); else submit(); });
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return composer;
    }
    private void composerLooks(LabBook.Project p) {
        if (input == null) return;
        JSONObject pending = book.pending(p.id());
        boolean asking = pending != null && "ask".equals(pending.optString("action"));
        input.setHint(asking ? "책임연구원의 질문에 답하기" : "토론할 주제나 질문 · @이름으로 한 명에게");
        if (send != null) ui.sendLooks(send, runner.busy(p.id()));
        if (debateLine != null) {
            List<String> names = new ArrayList<>(); for (String d : p.debaters()) names.add(LabBook.role(d).name());
            SpannableStringBuilder label = new SpannableStringBuilder("토론 " + p.debaters().size() + "명");
            label.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), 0);
            int s = label.length(); label.append("  ").append(p.rounds() + "라운드");
            label.setSpan(new ForegroundColorSpan(MUTED), s, label.length(), 0);
            debateLine.setText(label);
            debateLine.setContentDescription("토론 설정 바꾸기, 지금 " + String.join(", ", names) + ", " + p.rounds() + "라운드");
        }
    }
    private void submit() {
        if (input == null || openId == null) return;
        String text = input.getText().toString().strip(); if (text.isEmpty()) return;
        try {
            if (!runner.say(openId, text)) { Toast.makeText(this, "지금은 다른 작업이 진행 중이에요. 끝나면 보내 주세요.", Toast.LENGTH_SHORT).show(); return; }
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        input.setText("");
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        render(true);
    }
    /** +: the research steps, each on what is typed (or the project's question when nothing is). */
    private void actions(View anchor, LabBook.Project p) {
        String[] labels = {"가설→결론 (전 과정)", "새 아이디어 찾기", "토론 시작", "문헌 조사", "실험 (리눅스 Python)", "비평 받기", "계획 세우기", "보고서 쓰기", "논문 쓰기", "동료 심사·고치기", "구글 시트 데이터 가져오기", "결과 표를 구글 시트로"};
        ui.miniMenu(anchor, labels, -1, i -> act(p, new String[]{"pipeline", "ideate", "debate", "search", "experiment", "critique", "plan", "report", "paper", "review", "sheetIn", "sheetOut"}[i]));
    }
    private void act(LabBook.Project p, String what) {
        if (runner.busy(p.id())) { Toast.makeText(this, "지금은 다른 작업이 진행 중이에요.", Toast.LENGTH_SHORT).show(); return; }
        String typed = input == null ? "" : input.getText().toString().strip();
        String focus = typed.isEmpty() ? (p.question().isBlank() ? p.title() : p.question()) : typed;
        if ("sheetIn".equals(what)) { sheetIn(p, typed); return; }
        if ("sheetOut".equals(what)) { sheetOut(p); return; }
        if ("experiment".equals(what) && !LabPython.ready(this)) {
            ui.confirm("실험은 앱 안 리눅스의 Python으로 돌려요. 계산 환경을 먼저 준비할까요?", "진행판에서 준비하기", () -> { tab = 4; render(true); }).show(); return;
        }
        boolean started;
        try {
            if (!typed.isEmpty() && !"critique".equals(what) && !"report".equals(what) && !"plan".equals(what) && !"review".equals(what)) book.note(p.id(), "user", "", typed, "", null);
            started = switch (what) {
                case "pipeline" -> runner.pipeline(p.id(), typed);
                case "ideate" -> runner.ideate(p.id(), typed);
                case "paper" -> runner.paper(p.id(), typed);
                case "review" -> runner.review(p.id());
                case "search" -> runner.search(p.id(), typed);
                case "experiment" -> runner.experiment(p.id(), focus);
                case "critique" -> runner.critique(p.id());
                case "plan" -> runner.plan(p.id());
                case "report" -> runner.report(p.id());
                default -> runner.debate(p.id(), focus);
            };
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        if (!started) { Toast.makeText(this, "시작하지 못했어요. 잠시 뒤 다시 해 주세요.", Toast.LENGTH_SHORT).show(); return; }
        if (input != null && !typed.isEmpty() && !"critique".equals(what) && !"report".equals(what) && !"plan".equals(what) && !"review".equals(what)) input.setText("");
        // A report or a paper appears in 산출물: asked from there, the screen stays there and shows it being written.
        if (("report".equals(what) || "paper".equals(what) || "review".equals(what)) && tab == 2) { render(false); return; }
        tab = 0; render(true);
    }
    private void mention(View anchor) {
        String[] names = new String[LabBook.ROLES.size()];
        for (int i = 0; i < names.length; i++) names[i] = LabBook.ROLES.get(i).name() + " · " + LabBook.ROLES.get(i).about();
        ui.miniMenu(anchor, names, -1, i -> {
            if (input == null) return;
            String text = input.getText().toString(); LabBook.Role old = LabBook.mention(text);
            String rest = old == null ? text.strip() : LabBook.withoutMention(text, old);
            String value = "@" + LabBook.ROLES.get(i).name() + " " + rest;
            input.setText(value); input.setSelection(value.length()); input.requestFocus();
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.showSoftInput(input, 0);
        });
    }
    private void dictate() {
        if (store.voiceActive || store.recording || store.voiceChat != null) { Toast.makeText(this, "음성 대화나 녹음을 끝낸 뒤 음성으로 입력해 주세요.", Toast.LENGTH_LONG).show(); return; }
        if (!store.secrets.hasWorkKey()) { Toast.makeText(this, "음성 입력에 쓸 Gemini API 키가 없어요. 설정에서 Gemini를 연결해 주세요.", Toast.LENGTH_LONG).show(); return; }
        if (input == null) return;
        store.stopReading(null);
        if (dictation != null) dictation.pause();
        dictation = new ComposerDictation(this, input, () -> "lab", message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        dictation.begin(() -> new GeminiComposerSession(store.secrets.workKey()), "Gemini 3.5 Transcribe Live");
    }
    /** 토론 설정: who debates, how many rounds, who sums it up. */
    private void debateSettings() {
        if (openId == null) return; LabBook.Project p = book.project(openId); if (p == null) return;
        Ui.Sheet sheet = ui.selectionSheet("토론 설정", "다른 회사 모델끼리 토론할수록 서로의 실수를 잘 잡아요");
        List<String> chosen = new ArrayList<>(p.debaters()); int[] rounds = {p.rounds()}; String[] judge = {p.judge()};
        sheet.body.addView(ui.sectionHeader("참여 연구원"));
        LinearLayout group = ui.group();
        for (LabBook.Role r : LabBook.ROLES) {
            if (r == LabBook.SCRIBE) continue;
            ui.addRow(group, ui.switchRow(r.name(), r.about() + " · " + modelText(p, r.id()), chosen.contains(r.id()), on -> { if (on) { if (!chosen.contains(r.id())) chosen.add(r.id()); } else chosen.remove(r.id()); }), false);
        }
        sheet.body.addView(group);
        sheet.body.addView(ui.sectionHeader("라운드"));
        sheet.body.addView(ui.switcher(new String[]{"1라운드", "2라운드", "3라운드"}, rounds[0] - 1, i -> rounds[0] = i + 1), new LinearLayout.LayoutParams(-2, -2));
        sheet.body.addView(ui.footer("1라운드는 서로의 답을 보지 않고 답해요. 2라운드부터는 다른 연구원의 답을 읽고 반박하거나 보완해요."));
        sheet.body.addView(ui.sectionHeader("정리하는 연구원"));
        String[] judges = {LabBook.PI.id(), LabBook.CRITIC.id(), LabBook.SCRIBE.id()};
        int at = Math.max(0, java.util.Arrays.asList(judges).indexOf(judge[0]));
        sheet.body.addView(ui.switcher(new String[]{"책임연구원", "비평가", "기록원"}, at, i -> judge[0] = judges[i]), new LinearLayout.LayoutParams(-2, -2));
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            if (chosen.size() < 2) { Toast.makeText(this, "토론에는 두 명 이상이 필요해요.", Toast.LENGTH_SHORT).show(); return; }
            List<String> ordered = new ArrayList<>(); for (LabBook.Role r : LabBook.ROLES) if (chosen.contains(r.id())) ordered.add(r.id());
            try { book.save(p.debate(ordered, rounds[0], judge[0])); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
            sheet.dismiss(); render(false);
        });
        sheet.show();
    }

    // ── 노트 ─────────────────────────────────────────────────────────

    private static final String[] FILTERS = {"전체", "토론", "문헌", "실험", "비평·결정", "보고서"};
    private boolean shows(LabBook.Note n) {
        return switch (noteFilter) {
            case 1 -> List.of("say", "judge", "user").contains(n.kind());
            case 2 -> "search".equals(n.kind());
            case 3 -> List.of("run", "analysis").contains(n.kind());
            case 4 -> List.of("critique", "step", "plan", "ask", "learned").contains(n.kind());
            case 5 -> "report".equals(n.kind());
            default -> true;
        };
    }
    private void notesView(LabBook.Project p) {
        content.addView(ui.smallPills(FILTERS, noteFilter, i -> { noteFilter = i; notesMore = 0; expanded.clear(); render(true); }));
        List<LabBook.Note> notes = notes(p); List<LabBook.Note> shown = new ArrayList<>();
        for (LabBook.Note n : notes) if (shows(n)) shown.add(n);
        if (shown.isEmpty()) { content.addView(emptyNote(notes.isEmpty() ? "아직 기록이 없어요. 토론방에서 연구를 시작해 보세요." : "이 종류의 기록은 아직 없어요.")); return; }
        long lastDay = -1;
        LinearLayout line = ui.column(); line.setPadding(0, ui.dp(8), 0, 0);
        int until = Math.max(0, shown.size() - NOTE_ROWS - notesMore);
        for (int i = shown.size() - 1; i >= until; i--) {
            LabBook.Note n = shown.get(i); long d = n.at() / 86_400_000L;
            if (d != lastDay) { lastDay = d; TextView dm = ui.text(day.format(new Date(n.at())), 12.5f, 650, MUTED); dm.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(6)); line.addView(dm); }
            line.addView(timelineRow(p, n, i == 0));
        }
        content.addView(line);
        if (until > 0) {
            TextView older = ui.text("이전 기록 " + until + "개 더 보기", 13, 650, ACCENT); older.setGravity(Gravity.CENTER); older.setPadding(0, ui.dp(10), 0, ui.dp(10));
            older.setOnClickListener(v -> { notesMore += MORE; render(false); }); Ui.pressable(older, true); content.addView(older);
        }
    }
    private View emptyNote(String text) { TextView t = ui.label(text, 14, MUTED); t.setGravity(Gravity.CENTER); t.setPadding(ui.dp(24), ui.dp(40), ui.dp(24), ui.dp(24)); return t; }
    private int kindHue(LabBook.Note n) {
        return switch (n.kind()) { case "user" -> ACCENT; case "error" -> RED; case "system" -> GRAY; case "ask" -> ORANGE; default -> roleHue(n.role()); };
    }
    private View timelineRow(LabBook.Project p, LabBook.Note n, boolean last) {
        boolean open = expanded.contains("t:" + n.id());
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP);
        TextView time = ui.text(clock.format(new Date(n.at())), 12, 500, MUTED); time.setGravity(Gravity.END); time.setPadding(0, ui.dp(10), ui.dp(10), 0);
        row.addView(time, new LinearLayout.LayoutParams(ui.dp(48), -2));
        FrameLayout rail = new FrameLayout(this);
        View lineView = new View(this); lineView.setBackgroundColor(SEPARATOR);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, ui.dp(1.5f)), -1, Gravity.CENTER_HORIZONTAL); if (last) lp.height = ui.dp(20); rail.addView(lineView, lp);
        View node = dot(kindHue(n) | 0xff000000); FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ui.dp(12), ui.dp(12), Gravity.CENTER_HORIZONTAL | Gravity.TOP); np.topMargin = ui.dp(12); rail.addView(node, np);
        row.addView(rail, new LinearLayout.LayoutParams(ui.dp(16), -1));
        LinearLayout col = ui.column(); col.setPadding(ui.dp(10), ui.dp(6), 0, ui.dp(10));
        String who = n.mine() ? "나" : n.role().isBlank() ? "" : LabBook.role(n.role()).name();
        SpannableStringBuilder head = new SpannableStringBuilder(LabBook.kindName(n.kind()));
        head.setSpan(new StyleSpan(Typeface.BOLD), 0, head.length(), 0);
        if (!who.isEmpty()) { int s = head.length(); head.append("  ").append(who); head.setSpan(new ForegroundColorSpan(MUTED), s, head.length(), 0); }
        TextView h = ui.text("", 14, 500, TEXT); h.setText(head); col.addView(h);
        if (open) {
            View full = noteView(p, n, true); if (full != null) { LinearLayout.LayoutParams fa = new LinearLayout.LayoutParams(-1, -2); fa.topMargin = ui.dp(4); col.addView(full, fa); }
        } else {
            String preview = "run".equals(n.kind()) ? "실험 E" + n.data().optInt("n") + (n.data().optInt("exit") == 0 ? " 성공 · " : " 실패 · ") + n.data().optString("goal")
                : "step".equals(n.kind()) ? LabBook.actionName(n.data().optString("action")) + " · " + n.data().optString("focus") : n.text();
            if (preview.length() > 400) preview = preview.substring(0, 400);
            TextView pv = ui.text(preview.replaceAll("[#*`>_]+", "").replaceAll("\\s+", " ").strip(), 13, 450, MUTED); pv.setMaxLines(2); pv.setEllipsize(TextUtils.TruncateAt.END); pv.setPadding(0, ui.dp(2), 0, 0);
            col.addView(pv);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        h.setOnClickListener(v -> { String k = "t:" + n.id(); if (!expanded.remove(k)) expanded.add(k); render(false); });
        if (!open) col.setOnClickListener(v -> { expanded.add("t:" + n.id()); render(false); });
        Ui.pressable(h, true);
        return row;
    }

    // ── 산출물 ────────────────────────────────────────────────────────

    private void outputsView(LabBook.Project p) {
        paperView(p);
        String report = book.report(p.id());
        content.addView(heading("보고서", report.isBlank() ? null : "복사", report.isBlank() ? null : () -> copy(report)));
        boolean writing = runner.busy(p.id()) && runner.activity(p.id()).contains("보고서");
        if (writing) {
            // Being written now: what the 기록원 is doing, live, instead of a button that seems to do nothing.
            LinearLayout card = ui.column(); card.setBackground(ui.glass(R_CARD, false)); card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
            ShimmerText now = new ShimmerText(this, MUTED, TEXT); now.setTextSize(15); now.setTypeface(Ui.weight(620)); now.setText(runner.activity(p.id()));
            card.addView(now); reportNow = now;
            TextView about = ui.text("다 쓰면 여기에 바로 나와요. 다른 탭을 보거나 앱을 나가도 계속 써요.", 13.5f, 450, MUTED); about.setPadding(0, ui.dp(6), 0, 0); card.addView(about);
            content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        } else if (report.isBlank()) {
            LinearLayout card = ui.column(); card.setBackground(ui.glass(R_CARD, false)); card.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
            card.addView(ui.text("아직 보고서가 없어요", 15.5f, 680, TEXT));
            // The last try failed: why, here where the report was asked for (it is in 토론방 too).
            List<LabBook.Note> all = notes(p); LabBook.Note last = all.isEmpty() ? null : all.get(all.size() - 1);
            if (last != null && "error".equals(last.kind())) {
                TextView why = ui.text("지난번엔 쓰지 못했어요 · " + last.text(), 13.5f, 520, ERROR); why.setPadding(0, ui.dp(6), 0, 0); card.addView(why);
                // A model to choose: the 연구원 tab, one tap away.
                if (last.text().contains("연구원 탭")) { LinearLayout fix = ui.row(); fix.setPadding(0, ui.dp(8), 0, 0); fix.addView(ui.pill("연구원 탭 열기", R.drawable.ic_settings, false, () -> { tab = 3; render(true); }), new LinearLayout.LayoutParams(-2, -2)); card.addView(fix); }
            }
            TextView about = ui.text("기록원이 지금까지의 토론, 문헌, 실험을 묶어 검증된 것 위주로 써요. 자율 연구는 마지막에 저절로 써요.", 13.5f, 450, MUTED); about.setPadding(0, ui.dp(6), 0, ui.dp(12)); card.addView(about);
            LinearLayout buttons = ui.row(); buttons.addView(ui.pill("보고서 쓰기", R.drawable.ic_pen, true, () -> act(p, "report")), new LinearLayout.LayoutParams(-2, -2)); card.addView(buttons);
            content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout card = ui.column(); card.setBackground(ui.glass(R_CARD, false)); card.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14));
            TextView text = ui.text("", 15, 420, TEXT); text.setText(RichText.render(report)); text.setLineSpacing(ui.dp(4), 1); text.setTextIsSelectable(true);
            card.addView(text);
            LinearLayout buttons = ui.row(); buttons.setPadding(0, ui.dp(14), 0, 0);
            buttons.addView(ui.pill("페이지로 저장", R.drawable.ic_pages, false, () -> saveReport(p)), new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
            buttons.addView(ui.pill("다시 쓰기", R.drawable.ic_refresh, false, () -> act(p, "report")), gap);
            card.addView(buttons);
            content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        }
        // 검증 장부
        List<LabBook.Claim> claims = book.claims(p.id());
        content.addView(heading("검증 장부", null, null));
        if (claims.isEmpty()) content.addView(quiet("토론, 문헌 조사, 실험에서 나온 주장이 근거와 함께 여기에 쌓여요."));
        else {
            LinearLayout counts = ui.row(); counts.setPadding(ui.dp(2), 0, 0, ui.dp(8));
            for (String s : LabBook.STATUSES) { int c = 0; for (LabBook.Claim cl : claims) if (cl.status().equals(s)) c++; if (c == 0) continue;
                TextView b = ui.text(LabBook.statusName(s) + " " + c, 12, 650, statusHue(s) | 0xff000000); b.setBackground(ui.shape(ui.tone(statusHue(s)), 99)); b.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
                LinearLayout.LayoutParams ba = new LinearLayout.LayoutParams(-2, -2); ba.rightMargin = ui.dp(6); counts.addView(b, ba); }
            android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.addView(counts);
            content.addView(strip);
            LinearLayout group = ui.group();
            List<LabBook.Claim> sorted = new ArrayList<>(claims); sorted.sort((a, b) -> Integer.compare(LabBook.STATUSES.indexOf(a.status()), LabBook.STATUSES.indexOf(b.status())));
            for (LabBook.Claim c : sorted) ui.addRow(group, claimRow(c), false);
            content.addView(group);
        }
        // 실험
        List<LabBook.Note> runs = new ArrayList<>(); for (LabBook.Note n : notes(p)) if ("run".equals(n.kind())) runs.add(n);
        content.addView(heading("실험", null, null));
        if (runs.isEmpty()) content.addView(quiet("실험가가 앱 안 리눅스에서 Python으로 계산한 결과와 그래프가 여기에 모여요."));
        for (int i = runs.size() - 1; i >= 0; i--) content.addView(runCard(p, runs.get(i)));
        // 참고문헌
        List<LabBook.Source> sources = book.sources(p.id());
        content.addView(heading("참고문헌", null, null));
        if (sources.isEmpty()) content.addView(quiet("문헌 조사에서 찾은 논문이 번호(S1, S2 …)와 함께 여기에 쌓여요."));
        else { LinearLayout list = ui.group(); for (LabBook.Source s : sources) ui.addRow(list, sourceRow(s), false); content.addView(list); }
    }
    private static int statusHue(String s) { return switch (s) { case "verified" -> GREEN; case "agreed" -> TEAL; case "disputed" -> ORANGE; case "refuted" -> RED; default -> GRAY; }; }
    private View claimRow(LabBook.Claim c) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        TextView b = ui.text(LabBook.statusName(c.status()), 11.5f, 700, statusHue(c.status()) | 0xff000000); b.setBackground(ui.shape(ui.tone(statusHue(c.status())), 99)); b.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
        b.setMinWidth(ui.dp(52)); b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ba = new LinearLayout.LayoutParams(-2, -2); ba.rightMargin = ui.dp(12); ba.topMargin = ui.dp(1); row.addView(b, ba);
        LinearLayout words = ui.column();
        TextView t = ui.text(c.text(), 14, 500, TEXT); t.setLineSpacing(ui.dp(2), 1); words.addView(t);
        String meta = String.join(" · ", nonEmpty(c.evidence().isBlank() ? "" : "근거 " + c.evidence(), c.by()));
        if (!meta.isEmpty()) { TextView m = ui.text(meta, 12, 450, MUTED); m.setPadding(0, ui.dp(3), 0, 0); words.addView(m); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    private View heading(String title, String action, Runnable run) {
        LinearLayout row = ui.row(); row.setPadding(ui.dp(4), ui.dp(20), 0, ui.dp(8));
        TextView t = ui.text(title, 17, 720, TEXT); t.setAccessibilityHeading(true); row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) { TextView a = ui.text(action, 13.5f, 650, ACCENT); a.setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6)); a.setOnClickListener(v -> run.run()); Ui.pressable(a, true); row.addView(a); }
        return row;
    }
    private View quiet(String text) { TextView t = ui.text(text, 13.5f, 450, MUTED); t.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4)); t.setLineSpacing(ui.dp(2), 1); return t; }
    /**
     * 논문 in 산출물: being written (live), none yet (what it is, with 가설→결론 and 논문 쓰기), or the newest version with its
     * verdict and reviews, to keep as a page with its figures, review again, or send its tables to Google Sheets.
     */
    private void paperView(LabBook.Project p) {
        String paper = book.paper(p.id());
        content.addView(heading("논문", paper.isBlank() ? null : "복사", paper.isBlank() ? null : () -> copy(paper)));
        String doing = runner.busy(p.id()) ? runner.activity(p.id()) : "";
        boolean writing = doing.contains("논문") || doing.contains("심사");
        LinearLayout card = ui.column(); card.setBackground(ui.glass(R_CARD, false)); card.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14));
        if (writing) {
            ShimmerText now = new ShimmerText(this, MUTED, TEXT); now.setTextSize(15); now.setTypeface(Ui.weight(620)); now.setText(doing);
            card.addView(now); reportNow = now;
            TextView about = ui.text("쓰고 → 비평가·이론가가 심사하고 → 심사를 반영해 고쳐요. 앱을 나가도 계속해요.", 13.5f, 450, MUTED); about.setPadding(0, ui.dp(6), 0, 0); card.addView(about);
            content.addView(card, new LinearLayout.LayoutParams(-1, -2)); return;
        }
        if (paper.isBlank()) {
            card.addView(ui.text("아직 논문이 없어요", 15.5f, 680, TEXT));
            TextView about = ui.text("‘가설→결론’은 새 아이디어 → 문헌 → 사전 등록 → 실험 → 비평 → 판정 → 논문·동료 심사까지 한 번에 해요. 이미 실험이 있으면 ‘논문 쓰기’만 해도 돼요.", 13.5f, 450, MUTED);
            about.setPadding(0, ui.dp(6), 0, ui.dp(12)); about.setLineSpacing(ui.dp(2), 1); card.addView(about);
            LinearLayout buttons = ui.row();
            buttons.addView(ui.pill("가설→결론", R.drawable.ic_sparkle, true, () -> act(p, "pipeline")), new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
            buttons.addView(ui.pill("논문 쓰기", R.drawable.ic_pen, false, () -> act(p, "paper")), gap);
            card.addView(buttons);
            content.addView(card, new LinearLayout.LayoutParams(-1, -2)); return;
        }
        // The verdict and the reviews' scores, one line each, above the paper.
        String verdict = book.state(p.id()).optString("verdict");
        List<String> marks = new ArrayList<>(); if (!verdict.isBlank()) marks.add(LabBook.verdictName(verdict));
        for (LabBook.Note n : notes(p)) if ("peer".equals(n.kind())) { marks.removeIf(x -> x.startsWith(LabBook.role(n.role()).name())); marks.add(LabBook.role(n.role()).name() + " " + LabBook.decisionName(n.data().optString("decision"))); }
        if (!marks.isEmpty()) { TextView m = ui.text(String.join(" · ", marks), 12.5f, 600, ACCENT); m.setPadding(0, 0, 0, ui.dp(8)); card.addView(m); }
        TextView text = ui.text("", 15, 420, TEXT); text.setLineSpacing(ui.dp(4), 1); text.setTextIsSelectable(true);
        // Figures show in the page (페이지로 저장); here each is named where it stands.
        text.setText(RichText.render(figureNames(paper)));
        card.addView(text);
        LinearLayout buttons = ui.row(); buttons.setPadding(0, ui.dp(14), 0, 0);
        buttons.addView(ui.pill("페이지로 저장 (그림 포함)", R.drawable.ic_pages, true, () -> savePaper(p)), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
        buttons.addView(ui.pill("심사 다시", R.drawable.ic_refresh, false, () -> act(p, "review")), gap);
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.addView(buttons);
        card.addView(strip);
        LinearLayout more = ui.row(); more.setPadding(0, ui.dp(8), 0, 0);
        more.addView(ui.pill("결과 표를 구글 시트로", R.drawable.ic_download, false, () -> sheetOut(p)), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(-2, -2); g2.leftMargin = ui.dp(8);
        more.addView(ui.pill("다시 쓰기", R.drawable.ic_pen, false, () -> act(p, "paper")), g2);
        card.addView(more);
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
    /** ![그림 1. …](e1-fig1.png) as words: the figure is in the page made from the paper. */
    static String figureNames(String paper) { return paper.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)", "_[그림: $1 · $2]_"); }
    /** The paper as one page in 페이지 (연구실), its figures inside it (LabPaper); off the main thread. */
    private void savePaper(LabBook.Project p) {
        String paper = book.paper(p.id()); if (paper.isBlank()) return;
        Toast.makeText(this, "그림을 넣어 페이지로 만드는 중…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String said;
            try {
                String html = LabPaper.html(paper, book.files(p.id()));
                dev.jun.gyeot.core.PageBook.Page page = PageStore.book(this).save(LabPaper.title(paper), "연구실", dev.jun.gyeot.core.PageBook.HTML, html, "", "");
                said = "페이지 → 연구실에 저장했어요 · " + page.title();
            } catch (Exception e) { said = e.getMessage() == null ? "저장하지 못했어요." : e.getMessage(); }
            String done = said; main.post(() -> { if (!destroyed) Toast.makeText(this, done, Toast.LENGTH_LONG).show(); });
        }, "lab-paper-page").start();
    }
    /** 구글 시트 데이터 가져오기: the sheet's address (typed, or asked for), read through Drive into the project's data. */
    private void sheetIn(LabBook.Project p, String typed) {
        if (!store.drive.connected()) { driveNeeded(); return; }
        if (typed.contains("/spreadsheets/d/")) { importSheet(p, typed); return; }
        Ui.Sheet sheet = ui.selectionSheet("구글 시트 데이터 가져오기", "시트 주소를 붙여 주세요. 첫 번째 탭을 CSV로 받아 실험가가 data/ 폴더에서 읽어요.");
        android.widget.EditText address = ui.input("https://docs.google.com/spreadsheets/d/…", 1, 400);
        sheet.body.addView(address, new LinearLayout.LayoutParams(-1, -2));
        sheet.primary.setText("가져오기");
        sheet.primary.setOnClickListener(v -> { String a = address.getText().toString().strip(); if (!a.contains("/spreadsheets/d/")) { address.setError("구글 시트 주소가 아니에요."); return; } sheet.dismiss(); importSheet(p, a); });
        sheet.show();
    }
    private void importSheet(LabBook.Project p, String address) {
        Toast.makeText(this, "구글 시트를 받는 중…", Toast.LENGTH_SHORT).show();
        if (input != null && input.getText().toString().contains("/spreadsheets/d/")) input.setText("");
        new Thread(() -> {
            String said;
            try { runner.importSheet(p.id(), address); said = "데이터로 가져왔어요 · 실험에서 쓸 수 있어요"; }
            catch (Exception e) { said = e.getMessage() == null ? "가져오지 못했어요." : e.getMessage(); }
            String done = said; main.post(() -> { if (destroyed) return; Toast.makeText(this, done, Toast.LENGTH_LONG).show(); render(false); });
        }, "lab-sheet-in").start();
    }
    /** 결과 표를 구글 시트로: each table the experiments wrote becomes a Google Sheet; the links land in 토론방. */
    private void sheetOut(LabBook.Project p) {
        if (!store.drive.connected()) { driveNeeded(); return; }
        Toast.makeText(this, "결과 표를 구글 시트로 보내는 중…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String said;
            try { int n = runner.exportSheets(p.id()); said = "구글 시트 " + n + "개를 만들었어요 · 토론방에 링크가 있어요"; }
            catch (Exception e) { said = e.getMessage() == null ? "보내지 못했어요." : e.getMessage(); }
            String done = said; main.post(() -> { if (destroyed) return; Toast.makeText(this, done, Toast.LENGTH_LONG).show(); render(false); });
        }, "lab-sheet-out").start();
    }
    /** Sheets go through the Google Drive connection: one tap to where it is connected. */
    private void driveNeeded() {
        ui.confirm("구글 시트는 Google Drive 연결로 읽고 만들어요. 지금 연결할까요?", "연결하러 가기",
            () -> startActivity(new android.content.Intent(this, MainActivity.class).putExtra("tab", 2).putExtra(MainActivity.SETTINGS_PAGE, "drive").addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))).show();
    }
    private void saveReport(LabBook.Project p) {
        String report = book.report(p.id()); if (report.isBlank()) return;
        try {
            dev.jun.gyeot.core.PageBook.Page page = PageStore.book(this).save(p.title() + " · 연구 보고서", "연구실", dev.jun.gyeot.core.PageBook.MARKDOWN, report, "", "");
            Toast.makeText(this, "페이지에 저장했어요 · " + page.title(), Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, e.getMessage() == null ? "저장하지 못했어요." : e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    // ── 연구원 ────────────────────────────────────────────────────────

    private void teamView(LabBook.Project p) {
        TextView about = ui.text("연구원마다 모델을 따로 골라요. 회사를 섞으면 같은 실수를 덜 해요. 모델 이름을 누르면 바꿀 수 있어요.", 13.5f, 450, MUTED);
        about.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(10)); about.setLineSpacing(ui.dp(2), 1); content.addView(about);
        LinearLayout group = ui.group();
        for (LabBook.Role r : LabBook.ROLES) ui.addRow(group, roleRow(p, r), true);
        content.addView(group);
        LinearLayout buttons = ui.row(); buttons.setPadding(ui.dp(2), ui.dp(14), 0, 0);
        buttons.addView(ui.pill("회사 섞어서 다시 배치", R.drawable.ic_refresh, false, () -> mix(p)), new LinearLayout.LayoutParams(-2, -2));
        content.addView(buttons);
        content.addView(heading("토론", "설정", this::debateSettings));
        List<String> names = new ArrayList<>(); for (String d : p.debaters()) names.add(LabBook.role(d).name());
        content.addView(quiet(LabBook.ida(String.join(", ", names)) + " " + p.rounds() + "라운드 토론하고, " + LabBook.ida(LabBook.role(p.judge()).name()) + " 합의·쟁점·확인할 것으로 정리해요."));
    }
    private View roleRow(LabBook.Project p, LabBook.Role r) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(ui.dp(14), ui.dp(14), ui.dp(12), ui.dp(12));
        LinearLayout.LayoutParams fa = new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)); fa.rightMargin = ui.dp(12); row.addView(face(r, 38), fa);
        LinearLayout words = ui.column();
        LinearLayout top = ui.row();
        top.addView(ui.text(r.name(), 15.5f, 700, TEXT));
        if (p.debaters().contains(r.id())) { TextView t = ui.text("토론", 11, 650, roleHue(r.id()) | 0xff000000); t.setBackground(ui.shape(ui.tone(roleHue(r.id())), 99)); t.setPadding(ui.dp(7), ui.dp(1), ui.dp(7), ui.dp(1));
            LinearLayout.LayoutParams ta = new LinearLayout.LayoutParams(-2, -2); ta.leftMargin = ui.dp(6); top.addView(t, ta); }
        if (p.judge().equals(r.id())) { TextView t = ui.text("정리", 11, 650, MUTED); t.setBackground(ui.shape(FILL, 99)); t.setPadding(ui.dp(7), ui.dp(1), ui.dp(7), ui.dp(1));
            LinearLayout.LayoutParams ta = new LinearLayout.LayoutParams(-2, -2); ta.leftMargin = ui.dp(6); top.addView(t, ta); }
        words.addView(top);
        TextView a = ui.text(r.about(), 13, 450, MUTED); a.setPadding(0, ui.dp(2), 0, ui.dp(6)); words.addView(a);
        TextView model = ui.text("", 13.5f, 500, TEXT); model.setSingleLine(true); model.setEllipsize(TextUtils.TruncateAt.END);
        model.setBackground(ui.shape(FILL, 99)); model.setPadding(ui.dp(12), ui.dp(7), ui.dp(10), ui.dp(7)); model.setMinHeight(ui.dp(36)); model.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.Drawable chevron = getDrawable(R.drawable.ic_chevron_down);
        if (chevron != null) { chevron = chevron.mutate(); chevron.setTint(MUTED); chevron.setBounds(0, 0, ui.dp(14), ui.dp(14)); model.setCompoundDrawablesRelative(null, null, chevron, null); model.setCompoundDrawablePadding(ui.dp(4)); }
        modelLabel(model, p, r.id());
        model.setOnClickListener(v -> chooseModel(model, p, r)); Ui.pressable(model, true);
        words.addView(model, new LinearLayout.LayoutParams(-2, -2));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    /** {name, level} of a researcher's model; "AI 연결·선택" when none is connected. */
    private String[] modelParts(LabBook.Project p, String role) {
        try {
            ScopedModels selection = ScopedModels.lab(store, p.id(), role); String engine = selection.engine();
            if (engine == null || engine.isBlank() || !store.ready(engine)) return new String[]{"AI 연결·선택", ""};
            String id = selection.model(engine); if (id.isBlank()) return new String[]{WorkBook.author(engine) + " · 모델 고르기", ""};
            TeamRoster roster = new TeamRoster(store, selection);
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, id); String effort = selection.effort(engine, id);
            String level = profile.efforts.isEmpty() ? "" : MyTubeChat.effortWord(engine, effort.isEmpty() ? profile.defaultEffort : effort);
            return new String[]{WorkBook.author(engine) + " · " + roster.label(engine, id), level};
        } catch (Exception unavailable) { return new String[]{"AI 연결·선택", ""}; }
    }
    private String modelText(LabBook.Project p, String role) { String[] m = modelParts(p, role); return m[0] + (m[1].isEmpty() ? "" : " " + m[1]); }
    private void modelLabel(TextView view, LabBook.Project p, String role) {
        String[] parts = modelParts(p, role);
        ModelLine.show(view, parts, LabBook.role(role).name());
    }
    private void chooseModel(TextView anchor, LabBook.Project p, LabBook.Role r) {
        ScopedModels selection; try { selection = ScopedModels.lab(store, p.id(), r.id()); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        Runnable changed = () -> { if (!destroyed) { modelLabel(anchor, p, r.id()); } };
        Runnable all = () -> FileExplorerModelUi.show(this, store, () -> ScopedModels.lab(store, p.id(), r.id()), r.name(), "연구원에게 적용", () -> !isDestroyed(), dialog -> dialog.show(), () -> render(false));
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
            try { selection.chooseEngine(e); } catch (Exception failed) { Toast.makeText(this, failed.getMessage(), Toast.LENGTH_LONG).show(); return; }
            if (!store.ready(e)) Toast.makeText(this, WorkBook.author(e) + "를 설정에서 먼저 연결해 주세요.", Toast.LENGTH_LONG).show();
            changed.run();
        }, all, null, changed).show(anchor);
    }
    /** Each role again on the connected engine its role suits best. */
    private void mix(LabBook.Project p) {
        int moved = 0;
        for (LabBook.Role r : LabBook.ROLES) {
            for (String e : ScopedModels.labPreference(r.id())) if (store.ready(e)) { try { ScopedModels.lab(store, p.id(), r.id()).chooseEngine(e); moved++; } catch (Exception ignored) {} break; }
        }
        Toast.makeText(this, moved == 0 ? "연결된 AI가 없어요. 설정에서 AI를 연결해 주세요." : "역할에 맞게 회사를 섞어 다시 배치했어요.", Toast.LENGTH_SHORT).show();
        render(false);
    }

    // ── 진행판 ────────────────────────────────────────────────────────

    private final Runnable linuxTick = new Runnable() { @Override public void run() { if (destroyed || tab != 4) return; render(false); if (LinuxRuntime.busy) main.postDelayed(this, 700); } };
    private void boardView(LabBook.Project p) {
        boolean busy = runner.busy(p.id()); String auto = book.autopilot(p.id()); JSONObject pending = book.pending(p.id());
        // The state, large, with what can be done about it.
        LinearLayout hero = ui.column(); hero.setBackground(ui.glass(R_CARD, false)); hero.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18));
        String state = busy ? "연구 중" : "waiting".equals(auto) ? (pending != null && "ask".equals(pending.optString("action")) ? "답을 기다려요" : "승인을 기다려요") : "paused".equals(auto) ? "잠시 멈췄어요" : "done".equals(auto) ? "자율 연구를 마쳤어요" : "쉬고 있어요";
        int hue = busy ? ACCENT : "waiting".equals(auto) ? ORANGE : "done".equals(auto) ? GREEN : GRAY;
        LinearLayout top = ui.row();
        View d = dot(hue | 0xff000000); LinearLayout.LayoutParams da = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)); da.rightMargin = ui.dp(10); top.addView(d, da);
        top.addView(ui.text(state, 19, 720, TEXT));
        hero.addView(top);
        String detail = busy ? runner.activity(p.id()) : pending != null ? LabBook.actionName(pending.optString("action")) + " · " + pending.optString("focus") : "자율 연구를 켜면 책임연구원이 계획, 문헌 조사, 토론, 실험, 비평을 골라 진행하고 마지막에 보고서를 써요.";
        TextView dt = ui.text(detail, 13.5f, 450, MUTED); dt.setPadding(0, ui.dp(6), 0, ui.dp(14)); dt.setLineSpacing(ui.dp(2), 1); hero.addView(dt);
        LinearLayout buttons = ui.row();
        if (busy) buttons.addView(ui.pill("멈추기", R.drawable.ic_stop, false, () -> runner.stop(p.id())), new LinearLayout.LayoutParams(-2, -2));
        else if (pending != null && "experiment".equals(pending.optString("action"))) {
            buttons.addView(ui.pill("승인하고 실행", R.drawable.ic_play, true, () -> approve(p, true)), new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
            buttons.addView(ui.pill("건너뛰기", 0, false, () -> approve(p, false)), gap);
        } else {
            boolean piping = book.state(p.id()).optBoolean("pipeline");
            buttons.addView(ui.pill("paused".equals(auto) || "waiting".equals(auto) || ("done".equals(auto) && !piping) ? "이어서 연구" : "자율 연구 시작", R.drawable.ic_sparkle, true, () -> autopilot(p)), new LinearLayout.LayoutParams(-2, -2));
            // 가설→결론: the whole way in a fixed order (a paused one goes on with 이어서 연구).
            if (!piping) { LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8); buttons.addView(ui.pill("가설→결론", R.drawable.ic_next, false, () -> act(p, "pipeline")), gap); }
        }
        hero.addView(buttons);
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        // The budget.
        content.addView(heading("한도", null, null));
        LinearLayout budget = ui.column(); budget.setBackground(ui.glass(R_GROUP, false)); budget.setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(14));
        int spent = book.spent(p.id()), total = book.total(p.id());
        LinearLayout nums = ui.row();
        SpannableStringBuilder big = new SpannableStringBuilder(String.valueOf(Math.min(spent, p.budget()))); big.setSpan(new StyleSpan(Typeface.BOLD), 0, big.length(), 0);
        int s = big.length(); big.append(" / " + p.budget() + " AI 답"); big.setSpan(new ForegroundColorSpan(MUTED), s, big.length(), 0);
        TextView bt = ui.text("", 17, 600, TEXT); bt.setText(big); nums.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
        // The budget's few choices as the small switch at the end of its own line.
        int[] options = {15, 30, 60, 100}; int at = 1; for (int i = 0; i < options.length; i++) if (options[i] == p.budget()) at = i;
        nums.addView(ui.switcher(new String[]{"15", "30", "60", "100"}, at, i -> { try { book.save(p.settings(options[i], p.approve())); } catch (Exception ignored) {} render(false); }), new LinearLayout.LayoutParams(-2, -2));
        budget.addView(nums);
        budget.addView(meter(Math.min(1f, spent / (float) Math.max(1, p.budget())), spent >= p.budget() ? ORANGE : ACCENT));
        TextView bh = ui.text("자율 연구 한 번에 쓰는 AI 답 · 지금까지 전체 " + total + "번", 12, 450, MUTED); bh.setPadding(0, ui.dp(8), 0, 0); budget.addView(bh);
        content.addView(budget, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout approvals = ui.group();
        ui.addRow(approvals, ui.switchRow("실험 전에 승인 받기", "자율 연구가 리눅스에서 코드를 돌리기 전에 무엇을 확인할지 보여 주고 기다려요.", p.approve(), on -> { try { book.save(p.settings(p.budget(), on)); } catch (Exception ignored) {} }), false);
        LinearLayout.LayoutParams aa = new LinearLayout.LayoutParams(-1, -2); aa.topMargin = ui.dp(10); content.addView(approvals, aa);
        // The stages.
        content.addView(heading("진행 단계", null, null));
        LinearLayout stages = ui.group();
        for (LabBook.Stage st : LabBook.STAGES) ui.addRow(stages, stageRow(st, book.stage(p.id(), st.id())), true);
        content.addView(stages);
        // 계산 환경.
        content.addView(heading("계산 환경", null, null));
        content.addView(computeCard());
        if (LinuxRuntime.busy) { main.removeCallbacks(linuxTick); main.postDelayed(linuxTick, 700); }
    }
    private View meter(float value, int color) {
        FrameLayout track = new FrameLayout(this); track.setBackground(ui.shape(FILL, 99));
        View fill = new View(this); fill.setBackground(ui.shape(color, 99));
        track.addView(fill, new FrameLayout.LayoutParams(0, -1));
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { int w = Math.round((r - l) * value); ViewGroup.LayoutParams lp = fill.getLayoutParams(); if (lp.width != w) { lp.width = w; fill.post(() -> fill.setLayoutParams(lp)); } });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, ui.dp(8)); at.topMargin = ui.dp(10);
        track.setLayoutParams(at);
        return track;
    }
    private View stageRow(LabBook.Stage st, String state) {
        LinearLayout row = ui.row(); row.setPadding(ui.dp(16), ui.dp(12), ui.dp(14), ui.dp(12)); row.setMinimumHeight(ui.dp(56));
        FrameLayout mark = new FrameLayout(this);
        boolean done = "done".equals(state), doing = "doing".equals(state);
        GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL);
        if (done) ring.setColor(GREEN | 0xff000000); else { ring.setColor(0); ring.setStroke(ui.dp(2), doing ? ACCENT : SEPARATOR); }
        mark.setBackground(ring);
        if (done) { ImageView check = new ImageView(this); check.setImageResource(R.drawable.ic_check); check.setImageTintList(android.content.res.ColorStateList.valueOf(DARK ? 0xff191816 : 0xffffffff)); check.setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)); mark.addView(check, new FrameLayout.LayoutParams(-1, -1)); }
        else if (doing) { View core = dot(ACCENT); FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ui.dp(10), ui.dp(10), Gravity.CENTER); mark.addView(core, cp); }
        LinearLayout.LayoutParams ma = new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)); ma.rightMargin = ui.dp(14); row.addView(mark, ma);
        LinearLayout words = ui.column();
        words.addView(ui.text(st.name(), 15, done || doing ? 650 : 520, doing ? ACCENT : TEXT));
        words.addView(ui.text(st.about(), 12.5f, 450, MUTED));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(ui.text(done ? "완료" : doing ? "진행 중" : "", 12.5f, 600, done ? GREEN | 0xff000000 : ACCENT));
        return row;
    }
    /** 계산 환경: Linux, then Python with its science packages, each put in from here. */
    private View computeCard() {
        boolean supported = LinuxRuntime.supported(this), linux = LinuxRuntime.installed(this), python = linux && LabPython.ready(this);
        LinearLayout card = ui.column(); card.setBackground(ui.glass(R_GROUP, false)); card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        String state = !supported ? "이 휴대폰에서는 쓸 수 없어요" : python ? "준비됐어요" : linux ? "Python 준비가 필요해요" : LinuxRuntime.busy ? "리눅스 설치 중" : "리눅스 설치가 필요해요";
        LinearLayout top = ui.row();
        View d = dot((python ? GREEN : supported ? ORANGE : GRAY) | 0xff000000); LinearLayout.LayoutParams da = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)); da.rightMargin = ui.dp(10); top.addView(d, da);
        top.addView(ui.text(state, 15.5f, 680, TEXT));
        card.addView(top);
        String about = !supported ? "ARM64 휴대폰에서만 앱 안 리눅스를 쓸 수 있어요. 토론과 문헌 조사는 그대로 돼요."
            : python ? "앱 안 리눅스(" + LinuxRuntime.DISTRIBUTION + ")의 Python과 " + LabPython.available(this) + "로 실험해요. 인터넷 없이 150초 안에 끝나는 계산만 돌려요."
                + (LabPython.extrasMissing(this) ? " 통계·기계학습 도구(scikit-learn, statsmodels, networkx)는 ‘분석 도구 추가’로 받아요." : "")
            : linux ? "실험가가 쓰는 Python 과학 패키지를 pacman으로 받아요. 약 150MB, 몇 분 걸려요."
            : LinuxRuntime.busy ? LinuxRuntime.state
            : "실험은 앱 안 리눅스에서 돌아가요. 다운로드 " + LinuxRuntime.mb(LinuxRuntime.ARCHIVE_BYTES) + ", 필요 공간 " + LinuxRuntime.mb(LinuxRuntime.needed()) + ".";
        TextView a = ui.label(about, 13, MUTED); a.setPadding(0, ui.dp(6), 0, ui.dp(12)); a.setLineSpacing(ui.dp(2), 1); card.addView(a);
        LinearLayout buttons = ui.row();
        if (supported && !linux && LinuxRuntime.busy) buttons.addView(ui.pill("설치 취소", 0, false, LinuxRuntime::cancel), new LinearLayout.LayoutParams(-2, -2));
        else if (supported && !linux) buttons.addView(ui.pill("리눅스 설치", R.drawable.ic_download, true, this::installLinux), new LinearLayout.LayoutParams(-2, -2));
        else if (supported && !python) buttons.addView(ui.pill("계산 환경 준비", R.drawable.ic_download, true, this::installPython), new LinearLayout.LayoutParams(-2, -2));
        else if (python && LabPython.extrasMissing(this)) buttons.addView(ui.pill("분석 도구 추가", R.drawable.ic_download, true, this::installPython), new LinearLayout.LayoutParams(-2, -2));
        else if (python) buttons.addView(ui.pill("다시 설치", R.drawable.ic_refresh, false, this::installPython), new LinearLayout.LayoutParams(-2, -2));
        if (buttons.getChildCount() > 0) card.addView(buttons);
        return card;
    }
    private void installLinux() {
        if (LinuxRuntime.free(this) < LinuxRuntime.needed()) { Toast.makeText(this, "저장 공간이 부족해요. " + LinuxRuntime.mb(LinuxRuntime.needed()) + "가 필요해요.", Toast.LENGTH_LONG).show(); return; }
        new Thread(() -> {
            String failed = null;
            try { LinuxRuntime.install(this, () -> {}); } catch (Exception e) { failed = e.getMessage() == null ? "설치하지 못했어요." : e.getMessage(); }
            String why = failed; main.post(() -> { if (destroyed) return; if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show(); render(false); });
        }, "lab-linux-install").start();
        main.postDelayed(() -> render(false), 300);
        main.postDelayed(linuxTick, 700);
    }
    private void installPython() {
        LabPython.prepare(this);
        LinuxTerminal.show(this, ui, "계산 환경 준비", "pacman으로 Python과 numpy, scipy, sympy, matplotlib, pandas, scikit-learn, statsmodels, networkx를 받아요. 몇 분 걸려요. 끝나면 닫아 주세요.", LabPython.installCommand(), null, () -> { if (!destroyed) render(false); });
    }

    // ── projects: making, changing, removing ─────────────────────────

    private void start(String title, String question, String field) {
        try { LabBook.Project p = book.create(title, question, field); openId = p.id(); tab = 0; draw(); }
        catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void edit(LabBook.Project p) {
        Ui.Sheet sheet = ui.selectionSheet(p == null ? "새 연구" : "연구 정보", "이 휴대폰에만 저장돼요");
        EditText title = ui.input("연구 제목 · 예: 제타 영점 간격 탐구", 1, 60), question = ui.input("연구 질문 · 무엇을 알아내고 싶나요?", 3, 1200);
        String[] field = {p == null ? "general" : p.field()};
        if (p != null) { title.setText(p.title()); question.setText(p.question()); }
        sheet.body.addView(title);
        LinearLayout.LayoutParams qa = new LinearLayout.LayoutParams(-1, -2); qa.topMargin = ui.dp(10); sheet.body.addView(question, qa);
        sheet.body.addView(ui.sectionHeader("분야"));
        String[] names = new String[LabBook.FIELDS.size()]; int at = 0;
        for (int i = 0; i < names.length; i++) { names[i] = LabBook.FIELDS.get(i).name(); if (LabBook.FIELDS.get(i).id().equals(field[0])) at = i; }
        TextView guide = ui.footer(LabBook.field(field[0]).guide());
        sheet.body.addView(ui.smallPills(names, at, i -> { field[0] = LabBook.FIELDS.get(i).id(); guide.setText(LabBook.field(field[0]).guide() + (p == null ? "\n예: " + LabBook.FIELDS.get(i).sample() : "")); if (p == null) question.setHint("연구 질문 · 예: " + LabBook.FIELDS.get(i).sample()); }));
        if (p == null) guide.setText(LabBook.field(field[0]).guide() + "\n예: " + LabBook.field(field[0]).sample());
        sheet.body.addView(guide);
        sheet.primary.setText(p == null ? "연구 만들기" : "저장");
        sheet.primary.setOnClickListener(v -> {
            String t = title.getText().toString().strip(), q = question.getText().toString().strip();
            if (t.isEmpty() && q.isEmpty()) { Toast.makeText(this, "연구 제목이나 질문을 적어 주세요.", Toast.LENGTH_SHORT).show(); return; }
            try {
                if (p == null) { LabBook.Project made = book.create(t, q, field[0]); openId = made.id(); tab = 0; }
                else book.save(p.with(t.isEmpty() ? p.title() : t, q, field[0]));
            } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
            sheet.dismiss(); draw();
        });
        sheet.show();
    }
    private void projectMenu(View anchor, LabBook.Project p) {
        ui.miniMenu(anchor, new String[]{"연구 정보 고치기", "보고서를 페이지로 저장", "기록 모두 지우기", "연구 지우기"}, -1, i -> {
            if (i == 0) edit(p);
            else if (i == 1) { if (book.report(p.id()).isBlank()) Toast.makeText(this, "아직 보고서가 없어요. 산출물 탭에서 먼저 써 주세요.", Toast.LENGTH_LONG).show(); else saveReport(p); }
            else if (i == 2) ui.confirm("‘" + p.title() + "’의 토론, 노트, 장부, 실험, 보고서를 모두 지울까요? 연구 정보와 연구원 모델은 남아요.", "기록 지우기", () -> {
                runner.stop(p.id()); try { book.clearNotes(p.id()); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); } expanded.clear(); draw(); }).show();
            else ui.confirm("‘" + p.title() + "’ 연구를 지울까요? 기록과 그림도 함께 지워져요.", "연구 지우기", () -> {
                runner.stop(p.id());
                try { book.remove(p.id()); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
                ScopedModels.forgetLab(store, p.id());
                try { LinuxRuntime.delete(new File(LinuxRuntime.rootfs(this), "root/lab/" + p.id().replaceAll("[^A-Za-z0-9_-]", "_"))); } catch (Exception ignored) {}
                if (p.id().equals(openId)) openId = null;
                draw();
            }).show();
        });
    }

    // ── small things ─────────────────────────────────────────────────

    private void copy(String text) {
        android.content.ClipboardManager clip = getSystemService(android.content.ClipboardManager.class);
        if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText("연구실", dev.jun.gyeot.core.Markdown.plain(text)));
        Toast.makeText(this, "복사했어요.", Toast.LENGTH_SHORT).show();
    }
    private void openUrl(String url) {
        try { BrowserActivity.open(this, url); } catch (Exception e) { Toast.makeText(this, "이 주소는 열 수 없어요.", Toast.LENGTH_SHORT).show(); }
    }
    private Bitmap figure(File f) {
        if (!f.isFile()) return null;
        Bitmap kept = figures.get(f.getPath()); if (kept != null) return kept;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), bounds);
            Bitmap b = decode(f, sample(bounds.outWidth)); if (b != null) figures.put(f.getPath(), b);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) { return null; }
    }
    private static int sample(int width) { int sample = 1; while (width / (sample * 2) >= 1200) sample *= 2; return sample; }
    private static Bitmap decode(File f, int sample) {
        try { BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample; return BitmapFactory.decodeFile(f.getPath(), opts); }
        catch (RuntimeException | OutOfMemoryError e) { return null; }
    }
    /** Figures are decoded on their own thread: decoding a large PNG on the main one is a pause. */
    private final java.util.concurrent.ExecutorService decoder = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-lab-figures"); t.setDaemon(true); return t; });
    /**
     * Puts a figure in its view: at once when decoded before, else an empty box of its size (read from the file's head, so
     * nothing moves when it comes) while it is decoded off the main thread. False when the file is no picture.
     */
    private boolean showFigure(ImageView image, File f) {
        Bitmap kept = figures.get(f.getPath()); if (kept != null) { image.setImageBitmap(kept); return true; }
        if (!f.isFile()) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        int sample = sample(bounds.outWidth);
        GradientDrawable hold = new GradientDrawable(); hold.setColor(0); hold.setSize(Math.max(1, bounds.outWidth / sample), Math.max(1, bounds.outHeight / sample));
        image.setImageDrawable(hold);
        decoder.execute(() -> {
            Bitmap b = decode(f, sample);
            main.post(() -> { if (destroyed || b == null) return; figures.put(f.getPath(), b); image.setImageBitmap(b); });
        });
        return true;
    }
    private void showFigure(File f) {
        Bitmap b = figure(f); if (b == null) return;
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(0xff0b0b0c);
        ImageView image = new ImageView(this); image.setImageBitmap(b); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setBackgroundColor(0xffffffff);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        ImageView close = ui.iconButton(R.drawable.ic_close, 0x66000000, 0xffffffff, "닫기", dialog::dismiss);
        FrameLayout.LayoutParams ca = new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.END | Gravity.TOP); ca.topMargin = ui.dp(28); ca.rightMargin = ui.dp(12); frame.addView(close, ca);
        frame.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(frame); dialog.show();
    }
}
