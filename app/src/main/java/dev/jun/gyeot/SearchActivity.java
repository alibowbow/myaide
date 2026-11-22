package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jun.gyeot.core.GlobalSearch;
import dev.jun.gyeot.core.GlobalSearch.Hit;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static dev.jun.gyeot.Ui.*;

/**
 * 전체 검색: one box that looks through the main chat, work rooms, pages, memories, notes, voice records, recordings and
 * fetched texts. A work room or a page opens in its own screen; everything else opens as text to read and copy. It has no
 * place of its own in the app menu: the search boxes of 작업창, 기록 and 페이지 and the main chat's menu lead here.
 */
public final class SearchActivity extends Activity {
    /** Starts with these words already searched. */
    static final String QUERY = "gyeot.search_query";
    /** In 전체, each kind shows this many and a row to see the rest; a result's text is drawn up to SHOWN characters. */
    static final int PREVIEW = 4, SHOWN = 30_000;
    /** Reading the stores off the main thread; tests run it in place. */
    static Executor work;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui; private AppStore store; private MainNavigation navigation; private LinearLayout root, list; private FrameLayout kinds; private EditText field;
    private String query = "", kind = GlobalSearch.ALL; private List<Hit> hits = new ArrayList<>();
    private long searches; private boolean destroyed, searched; private Ui.Sheet open;
    private final Runnable search = this::search, meaning = this::meaning;
    /** 뜻으로 찾기: the results nearest the query's meaning (SemanticIndex), asked once typing has paused a little longer. */
    private List<Hit> similar = new ArrayList<>(); private long meanings;

    /** Opens this screen with query already searched ("" for an empty box). */
    static void open(Activity from, String query) {
        from.startActivity(new Intent(from, SearchActivity.class).putExtra(QUERY, query == null ? "" : query.strip()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    /** Under a screen's own search box: a row that takes the same words to 전체 검색. null while there is too little to search for. */
    static View link(Activity from, Ui ui, String query) {
        if (GlobalSearch.words(query).isEmpty()) return null;
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_search, BLUE, "전체에서 ‘" + query.strip() + "’ 찾기", "메인 대화·작업창·페이지·기억·메모·기록을 한 번에", ui.chevron(), () -> open(from, query)), true);
        return group;
    }
    private void noTransition() { if (android.os.Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0); } else overridePendingTransition(0, 0); }
    @Override public void finish() { super.finish(); noTransition(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); noTransition();
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null) { query = state.getString("query", ""); kind = state.getString("kind", GlobalSearch.ALL); }
        else if (getIntent().getStringExtra(QUERY) != null) query = getIntent().getStringExtra(QUERY);
        root = ui.column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            v.setPadding(ui.dp(12) + bars.left, ui.dp(8) + bars.top, ui.dp(12) + bars.right, ui.dp(8) + bottom); return insets;
        });
        navigation = new MainNavigation(this, root, "search", target -> MainNavigation.open(this, target), false);
        setContentView(navigation); root.requestApplyInsets();
        LinearLayout heading = ui.row(); heading.addView(back(), new LinearLayout.LayoutParams(-2, ui.dp(48))); root.addView(heading);
        TextView title = ui.title("전체 검색", 26); title.setAccessibilityHeading(true); ui.add(root, title, 4);
        ui.add(root, ui.label("메인 대화·작업창·페이지·기억·메모·음성 기록·녹음·가져온 글에서 한 번에 찾아요.", 13, MUTED), 12);
        field = ui.searchField(ui.input("찾을 말 · 두 글자부터", 1, 100)); field.setText(query); field.setSelection(field.getText().length()); field.setContentDescription("전체 검색");
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            // Searching reads files: it waits until typing pauses.
            @Override public void afterTextChanged(Editable s) { if (s.toString().equals(query)) return; query = s.toString(); main.removeCallbacks(search); main.removeCallbacks(meaning); similar = new ArrayList<>(); meanings++; main.postDelayed(search, 250); }
        });
        ui.add(root, field, 10);
        kinds = new FrameLayout(this); ui.add(root, kinds, 4);
        ScrollView scroll = new ScrollView(this); list = ui.column(); list.setPadding(0, 0, 0, ui.dp(24)); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (query.isBlank()) { draw(); field.requestFocus(); } else search();
        SemanticIndex.catchUpIfDue(this, store);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String asked = intent.getStringExtra(QUERY);
        if (asked != null && !asked.equals(query)) { kind = GlobalSearch.ALL; field.setText(asked); }
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("query", query); state.putString("kind", kind); }
    @Override protected void onResume() { super.onResume(); AppNavigator.inFront("search"); SemanticIndex.listener = () -> { if (!destroyed) draw(); }; if (searched) search(); }
    @Override protected void onPause() { SemanticIndex.listener = null; super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacks(search); main.removeCallbacks(meaning); if (open != null && open.isShowing()) open.dismiss(); worker.shutdown(); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (navigation != null && navigation.close()) return;
        if (!GlobalSearch.ALL.equals(kind)) { kind = GlobalSearch.ALL; draw(); return; }
        super.onBackPressed();
    }
    private View back() {
        Button back = ui.chip("돌아가기", this::finish); back.setContentDescription("돌아가기");
        android.graphics.drawable.Drawable chevron = getDrawable(R.drawable.ic_back).mutate(); chevron.setTint(ACCENT); chevron.setBounds(0, 0, ui.dp(24), ui.dp(24));
        back.setCompoundDrawablesRelative(chevron, null, null, null); back.setBackground(ui.shape(android.graphics.Color.TRANSPARENT, PILL));
        back.setTextColor(ACCENT); back.setPadding(ui.dp(12), 0, ui.dp(14), 0); back.setCompoundDrawablePadding(ui.dp(2));
        return back;
    }

    /** Reads the stores for the words in the box; an answer to words that were typed over since is dropped. */
    private void search() {
        long mine = ++searches; String asked = query;
        if (GlobalSearch.words(asked).isEmpty()) { hits = new ArrayList<>(); searched = false; draw(); return; }
        Runnable job = () -> {
            List<Hit> found;
            try { found = GlobalSearchSource.find(getApplicationContext(), store, asked); } catch (RuntimeException failed) { found = new ArrayList<>(); }
            List<Hit> result = found;
            Runnable show = () -> {
                if (destroyed || mine != searches) return; hits = result; searched = true; if (GlobalSearch.of(hits, kind).isEmpty()) kind = GlobalSearch.ALL; draw();
                main.removeCallbacks(meaning); main.postDelayed(meaning, 600);
            };
            if (work != null) show.run(); else main.post(show);
        };
        if (work != null) work.execute(job); else worker.execute(job);
    }
    /** Asks for the query's meaning (one Gemini request, none for a query asked before); dropped when typed over since. */
    private void meaning() {
        if (work != null || !SemanticIndex.searchable(this, store)) return;
        long mine = ++meanings; String asked = query; android.content.Context app = getApplicationContext();
        worker.execute(() -> {
            List<Hit> found;
            try { found = SemanticIndex.find(app, store, asked); } catch (RuntimeException failed) { found = new ArrayList<>(); }
            List<Hit> result = found;
            main.post(() -> { if (destroyed || mine != meanings || !asked.equals(query)) return; similar = result; draw(); });
        });
    }

    private static int icon(String kind) {
        return switch (kind) {
            case GlobalSearch.CHAT -> R.drawable.ic_chat; case GlobalSearch.WORK -> R.drawable.ic_work; case GlobalSearch.PAGE -> R.drawable.ic_pages; case GlobalSearch.MEMORY -> R.drawable.ic_bulb;
            case GlobalSearch.NOTE -> R.drawable.ic_file; case GlobalSearch.VOICE -> R.drawable.ic_wave; case GlobalSearch.RECORDING -> R.drawable.ic_mic; default -> R.drawable.ic_globe;
        };
    }
    private static int tint(String kind) {
        return switch (kind) {
            case GlobalSearch.CHAT -> BLUE; case GlobalSearch.WORK -> INDIGO; case GlobalSearch.PAGE -> TEAL; case GlobalSearch.MEMORY -> ORANGE;
            case GlobalSearch.NOTE -> GREEN; case GlobalSearch.VOICE -> PURPLE; case GlobalSearch.RECORDING -> RED; default -> MINT;
        };
    }

    /** The kinds that have results (with how many), then the results: a few of each kind in 전체, all of one kind otherwise. */
    private void draw() {
        kinds.removeAllViews(); list.removeAllViews();
        drawHits();
        if (searched && !GlobalSearch.words(query).isEmpty() && GlobalSearch.ALL.equals(kind)) drawSimilar();
        drawMeaningSwitch();
    }
    /** 뜻이 비슷한 것: the nearest by meaning that the words did not find already. */
    private void drawSimilar() {
        java.util.Set<String> found = new java.util.HashSet<>(); for (Hit hit : hits) found.add(hit.kind() + "/" + hit.id());
        List<Hit> more = new ArrayList<>(); for (Hit hit : similar) if (!found.contains(hit.kind() + "/" + hit.id())) more.add(hit);
        if (more.isEmpty()) return;
        list.addView(ui.sectionHeader("뜻이 비슷한 것"));
        rows(more, PREVIEW * 2, ZonedDateTime.now());
    }
    /** The switch for 뜻으로 찾기, with how far its index has come. */
    private void drawMeaningSwitch() {
        boolean on = store.semanticSearch();
        String note = on ? SemanticIndex.progress() + "\n낱말이 달라도 뜻이 비슷한 것을 함께 찾아요."
            : "낱말이 달라도 뜻이 비슷한 것을 찾아요. 켜면 글의 앞부분을 Gemini로 보내 이 폰에 색인을 만들어요.";
        list.addView(ui.sectionHeader("뜻으로 찾기"));
        LinearLayout group = ui.group();
        group.addView(ui.switchRow("뜻으로도 찾기 (Gemini)", note, on, value -> {
            store.semanticSearch(value);
            if (value) SemanticIndex.start(this, store); else { SemanticIndex.forget(this); similar = new ArrayList<>(); }
            draw(); if (value) { main.removeCallbacks(meaning); main.postDelayed(meaning, 600); }
        }));
        list.addView(group);
    }
    private void drawHits() {
        if (GlobalSearch.words(query).isEmpty()) { ui.add(list, ui.label(query.isBlank() ? "찾을 말을 적으면 여기에 나와요. 낱말을 띄어 쓰면 모두 들어 있는 것만 찾아요." : "두 글자부터 찾아요.", 14, MUTED), 20); return; }
        if (!searched) { ui.add(list, ui.label("찾는 중…", 14, MUTED), 20); return; }
        if (hits.isEmpty()) { ui.add(list, ui.label("‘" + query.strip() + "’이(가) 들어 있는 것이 없어요.", 14, MUTED), 20); return; }
        List<String> shown = new ArrayList<>(), labels = new ArrayList<>();
        for (String each : GlobalSearch.KINDS) {
            int count = GlobalSearch.of(hits, each).size();
            if (count > 0) { shown.add(each); labels.add(GlobalSearch.label(each) + " " + count); }
        }
        kinds.addView(ui.pills(labels.toArray(new String[0]), Math.max(0, shown.indexOf(kind)), index -> { kind = shown.get(index); draw(); }));
        ZonedDateTime now = ZonedDateTime.now();
        if (!GlobalSearch.ALL.equals(kind)) { rows(GlobalSearch.of(hits, kind), Integer.MAX_VALUE, now); return; }
        for (String each : shown) {
            if (GlobalSearch.ALL.equals(each)) continue;
            List<Hit> of = GlobalSearch.of(hits, each);
            list.addView(ui.sectionHeader(GlobalSearch.label(each)));
            LinearLayout group = rows(of, PREVIEW, now);
            if (of.size() > PREVIEW) ui.addRow(group, ui.listRow(0, 0, GlobalSearch.label(each) + " " + of.size() + "개 모두 보기", "", ui.chevron(), () -> { kind = each; draw(); }), false);
        }
    }
    private LinearLayout rows(List<Hit> of, int most, ZonedDateTime now) {
        LinearLayout group = ui.group();
        for (int i = 0; i < of.size() && i < most; i++) {
            Hit hit = of.get(i); String when = PagesActivity.when(hit.time(), now);
            String under = hit.snippet().isEmpty() ? when : hit.snippet() + (when.isEmpty() ? "" : "\n" + when);
            LinearLayout row = ui.listRow(icon(hit.kind()), tint(hit.kind()), hit.title(), under, ui.chevron(), () -> open(hit));
            row.setContentDescription(GlobalSearch.label(hit.kind()) + ", " + hit.title() + (hit.snippet().isEmpty() ? "" : ", " + hit.snippet()));
            ui.addRow(group, row, true);
        }
        list.addView(group);
        return group;
    }

    /** A work room and a page open where they live; the rest is read here. */
    private void open(Hit hit) {
        if (GlobalSearch.WORK.equals(hit.kind())) { startActivity(room(hit.id())); return; }
        if (GlobalSearch.PAGE.equals(hit.kind())) { startActivity(new Intent(this, PagesActivity.class).putExtra(PagesActivity.OPEN, hit.id()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)); return; }
        Runnable job = () -> {
            String text = GlobalSearchSource.text(getApplicationContext(), store, hit);
            Runnable show = () -> { if (!destroyed) read(hit, text); };
            if (work != null) show.run(); else main.post(show);
        };
        if (work != null) work.execute(job); else worker.execute(job);
    }
    private Intent room(String id) { return new Intent(this, MainActivity.class).putExtra("tab", 3).putExtra("work", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); }
    private void read(Hit hit, String text) {
        if (text.isBlank()) { android.widget.Toast.makeText(this, "지금은 없는 내용이에요.", android.widget.Toast.LENGTH_SHORT).show(); search(); return; }
        if (open != null && open.isShowing()) open.dismiss();
        String when = PagesActivity.when(hit.time(), ZonedDateTime.now()), shownText = text.length() <= SHOWN ? text : text.substring(0, SHOWN) + "\n…(길어서 앞부분만 보여요. ‘전체 복사’는 전부 복사해요)";
        Ui.Sheet sheet = ui.selectionSheet(hit.title(), GlobalSearch.label(hit.kind()) + (when.isEmpty() ? "" : " · " + when));
        TextView body = ui.text(shownText, 14, 400, TEXT);
        body.setTextIsSelectable(true); body.setLineSpacing(ui.dp(4), 1); body.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); body.setBackground(ui.shape(FILL, 14));
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(-1, -2); around.leftMargin = around.rightMargin = ui.dp(16); around.bottomMargin = ui.dp(12);
        sheet.body.addView(body, around);
        sheet.primary.setText("전체 복사");
        sheet.primary.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText(hit.title(), text.length() <= PagesActivity.COPY_LIMIT ? text : text.substring(0, PagesActivity.COPY_LIMIT))); android.widget.Toast.makeText(this, "복사했어요", android.widget.Toast.LENGTH_SHORT).show(); }
        });
        if (GlobalSearch.CHAT.equals(hit.kind())) {
            Button go = ui.button("메인 대화 열기", false, () -> { sheet.dismiss(); MainNavigation.open(this, "chat"); });
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2); below.leftMargin = below.rightMargin = ui.dp(16); below.bottomMargin = ui.dp(8);
            sheet.body.addView(go, below);
        }
        open = sheet; sheet.show();
    }
}
