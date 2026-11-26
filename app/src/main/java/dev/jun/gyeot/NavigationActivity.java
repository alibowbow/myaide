package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.NaverPlaces;
import dev.jun.gyeot.core.SavedPlaces;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 내 휴대폰 → 내비게이션: 자주 가는 곳 (presets: the words for a place, the place, its own way of travel and waypoints), the
 * default way of travel, 안내 시작 대신 누르기, and the 네이버 길안내 key. With a preset, "집으로 가자" in 음성 대화 starts Naver
 * Map's navigation there at once.
 */
public final class NavigationActivity extends Activity {
    private Ui ui; private AppStore store; private LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private boolean destroyed;
    /** Opened with a place's words to add (퍼레 was asked to go to 집 before it was saved): the add sheet, those words in. */
    static final String ADD = "addPlace";
    private String prefill;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store();
        draw();
        prefill = getIntent().getStringExtra(ADD);
        if (prefill != null && !prefill.isBlank() && state == null) edit(-1);
    }
    @Override protected void onResume() { super.onResume(); if (body != null) draw(); }
    @Override protected void onDestroy() { destroyed = true; work.shutdownNow(); super.onDestroy(); }

    private void draw() {
        ScrollView scroll = new ScrollView(this); body = ui.column(); body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)); scroll.addView(body);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        View back = ui.backButton("내 휴대폰", this::finish); back.setMinimumHeight(ui.dp(48)); body.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.title("내비게이션", 26); title.setAccessibilityHeading(true); ui.add(body, title, 4);
        ui.add(body, ui.label("음성 대화에서 “집으로 가자”, “회사 들렀다가 집으로”처럼 말하면 네이버 지도 길안내를 바로 시작해요.", 13, Ui.MUTED), 12);

        // 자주 가는 곳
        body.addView(ui.sectionHeader("자주 가는 곳"));
        List<SavedPlaces.Saved> places = store.savedPlaces();
        LinearLayout group = ui.group();
        for (int i = 0; i < places.size(); i++) {
            SavedPlaces.Saved one = places.get(i); int index = i;
            String sub = (one.name().isEmpty() ? one.address() : one.name() + (one.address().isEmpty() ? "" : " · " + one.address()))
                + (one.mode().isEmpty() ? "" : " · " + NaverPlaces.modeLabel(one.mode()))
                + (one.via().isEmpty() ? "" : " · 경유 " + String.join(", ", one.via().stream().map(NaverPlaces.Place::name).toList()));
            ui.addRow(group, ui.listRow(R.drawable.ic_navigation, Ui.BLUE, String.join(" · ", one.words()), sub, ui.chevron(), () -> edit(index)), true);
        }
        ui.addRow(group, ui.listRow(R.drawable.ic_add, Ui.ACCENT, "장소 추가", places.isEmpty() ? "예: 집, 회사, 엄마집" : places.size() + " / " + SavedPlaces.MOST + "곳", ui.chevron(), () -> edit(-1)), false);
        body.addView(group);
        body.addView(ui.footer("부르는 말은 여러 개 넣을 수 있어요 (예: 집, 우리집). 장소마다 이동 수단과 경유지를 따로 정할 수 있어요."));

        // 길안내
        body.addView(ui.sectionHeader("길안내"));
        LinearLayout guide = ui.group();
        String[] labels = {"자동차", "대중교통", "도보", "자전거"};
        ui.addRow(guide, ui.choiceRow("기본 이동 수단", "기본 이동 수단", "말로 이동 수단을 정하지 않았을 때 써요", labels, NaverPlaces.MODES.indexOf(store.navigationMode()),
            i -> { store.settings.edit().putString("navigationMode", NaverPlaces.MODES.get(i)).apply(); draw(); }), true);
        boolean phone = store.phoneControl() && PhoneService.connected();
        ui.addRow(guide, ui.switchRow("안내 시작 버튼 대신 누르기", phone ? "네이버 지도가 열리면 ‘안내 시작’까지 눌러요" : "설정 → 폰 조작을 켜야 동작해요", store.pressNavigationStart(),
            value -> store.settings.edit().putBoolean("pressNavigationStart", value).apply()), true);
        ui.addRow(guide, new NaverNavigationUi(this, ui, store, this::draw).row(), false);
        body.addView(guide);
        body.addView(ui.footer("네이버 길안내 키가 있으면 장소 이름으로도 정확히 찾아요. 없으면 주소로 찾아요."));
    }

    /** Adds (index -1) or changes a preset: its words, the place (searched), its way of travel and waypoints; delete and order. */
    private void edit(int index) {
        List<SavedPlaces.Saved> places = new ArrayList<>(store.savedPlaces());
        SavedPlaces.Saved old = index >= 0 && index < places.size() ? places.get(index) : null;
        if (old == null && places.size() >= SavedPlaces.MOST) { Toast.makeText(this, "자주 가는 곳은 " + SavedPlaces.MOST + "곳까지 저장할 수 있어요.", Toast.LENGTH_LONG).show(); return; }
        Ui.Sheet sheet = ui.selectionSheet(old == null ? "장소 추가" : "장소 편집", "음성 대화에서 부르는 말로 바로 길안내해요");
        NaverPlaces.Place[] place = {old == null ? null : old.place()};
        List<NaverPlaces.Place> via = new ArrayList<>(old == null ? List.of() : old.via());
        String[] mode = {old == null ? "" : old.mode()};

        sheet.body.addView(ui.label("부르는 말 (쉼표로 여러 개)", 13, Ui.MUTED));
        EditText words = ui.input("예: 집, 우리집", 1, 80); if (old != null) words.setText(String.join(", ", old.words())); else if (prefill != null) { words.setText(prefill); prefill = null; } ui.add(sheet.body, words, 12);

        sheet.body.addView(ui.label("목적지", 13, Ui.MUTED));
        TextView chosen = ui.text("", 15, 600, Ui.TEXT); ui.add(sheet.body, chosen, 4);
        Runnable showChosen = () -> chosen.setText(place[0] == null ? "아직 고르지 않았어요" : place[0].name() + (place[0].address().isEmpty() ? "" : "\n" + place[0].address()));
        showChosen.run();
        ui.add(sheet.body, ui.button(place[0] == null ? "주소나 장소 이름으로 찾기" : "목적지 바꾸기", false, () -> pick("목적지 찾기", found -> { place[0] = found; showChosen.run(); })), 12);

        sheet.body.addView(ui.label("이동 수단", 13, Ui.MUTED));
        String[] modeLabels = {"기본 설정 따르기", "자동차", "대중교통", "도보", "자전거"};
        int at = mode[0].isEmpty() ? 0 : NaverPlaces.MODES.indexOf(mode[0]) + 1;
        sheet.body.addView(ui.pills(modeLabels, at, i -> mode[0] = i == 0 ? "" : NaverPlaces.MODES.get(i - 1)));

        TextView viaTitle = ui.label("", 13, Ui.MUTED); viaTitle.setPadding(0, ui.dp(12), 0, 0); sheet.body.addView(viaTitle);
        LinearLayout viaList = ui.column(); sheet.body.addView(viaList);
        Runnable[] showVia = new Runnable[1];
        showVia[0] = () -> {
            viaTitle.setText("경유지 (자동차·도보·자전거, " + via.size() + " / " + NaverPlaces.MOST_VIA + ")");
            viaList.removeAllViews();
            LinearLayout list = ui.group();
            for (int i = 0; i < via.size(); i++) {
                int at2 = i; NaverPlaces.Place stop = via.get(i);
                ui.addRow(list, ui.listRow(0, 0, (i + 1) + ". " + stop.name(), stop.address(), ui.chip("빼기", () -> { via.remove(at2); showVia[0].run(); }), () -> {}), true);
            }
            if (via.size() < NaverPlaces.MOST_VIA)
                ui.addRow(list, ui.listRow(R.drawable.ic_add, Ui.ACCENT, "경유지 추가", "들렀다 갈 곳", ui.chevron(), () -> pick("경유지 찾기", found -> { via.add(found); showVia[0].run(); })), false);
            viaList.addView(list);
        };
        showVia[0].run();

        if (old != null) {
            LinearLayout more = ui.row(); more.setPadding(0, ui.dp(14), 0, 0);
            if (index > 0) more.addView(ui.chip("위로", () -> { move(index, -1); sheet.dismiss(); }));
            if (index < places.size() - 1) { Button down = ui.chip("아래로", () -> { move(index, 1); sheet.dismiss(); }); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.leftMargin = ui.dp(8); more.addView(down, p); }
            sheet.body.addView(more);
            ui.add(sheet.body, ui.dangerRow("삭제", String.join(", ", old.words()), () -> new AlertDialog.Builder(this).setTitle("삭제하시겠습니까?").setMessage("‘" + String.join(", ", old.words()) + "’ 장소를 지워요.")
                .setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> { List<SavedPlaces.Saved> now = new ArrayList<>(store.savedPlaces()); if (index < now.size()) now.remove(index); store.savedPlaces(now); sheet.dismiss(); draw(); }).show()), 8);
        }

        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            List<String> said = SavedPlaces.words(words.getText().toString());
            if (said.isEmpty()) { Toast.makeText(this, "부르는 말을 적어 주세요 (예: 집).", Toast.LENGTH_SHORT).show(); return; }
            if (place[0] == null) { Toast.makeText(this, "목적지를 찾아 골라 주세요.", Toast.LENGTH_SHORT).show(); return; }
            List<SavedPlaces.Saved> now = new ArrayList<>(store.savedPlaces());
            // A word another preset already uses goes to this one.
            for (int i = 0; i < now.size(); i++) {
                if (i == index) continue;
                SavedPlaces.Saved other = now.get(i);
                List<String> left = new ArrayList<>(other.words()); left.removeIf(w -> said.stream().anyMatch(s -> SavedPlaces.match(s, List.of(new SavedPlaces.Saved(List.of(w), "", "", 0, 0))) != null));
                if (left.size() != other.words().size()) now.set(i, new SavedPlaces.Saved(left.isEmpty() ? other.words() : left, other.name(), other.address(), other.lat(), other.lng(), other.mode(), other.via()));
            }
            SavedPlaces.Saved made = new SavedPlaces.Saved(said, place[0].name(), place[0].address(), place[0].lat(), place[0].lng(), mode[0], new ArrayList<>(via));
            if (index >= 0 && index < now.size()) now.set(index, made); else now.add(made);
            store.savedPlaces(now); sheet.dismiss(); draw();
            Toast.makeText(this, "저장했어요 · “" + said.get(0) + "으로 가자”라고 말해 보세요", Toast.LENGTH_SHORT).show();
        });
        sheet.show();
    }
    private void move(int index, int by) {
        List<SavedPlaces.Saved> now = new ArrayList<>(store.savedPlaces()); int to = index + by;
        if (index < 0 || to < 0 || index >= now.size() || to >= now.size()) return;
        SavedPlaces.Saved one = now.remove(index); now.add(to, one); store.savedPlaces(now); draw();
    }

    /** Finds a place by what is typed (an address or a name) and hands the one picked to done. */
    private void pick(String title, java.util.function.Consumer<NaverPlaces.Place> done) {
        Ui.Sheet sheet = ui.selectionSheet(title, "주소나 장소 이름을 적고 찾기를 누르세요");
        EditText query = ui.input("예: 서울 강남구 테헤란로 152, 강남역", 1, 120); ui.add(sheet.body, query, 8);
        TextView state = ui.label("", 13, Ui.MUTED); LinearLayout results = ui.column();
        Runnable search = () -> {
            String text = query.getText().toString().strip(); if (text.isEmpty()) return;
            state.setText("찾는 중…"); results.removeAllViews();
            work.execute(() -> {
                List<NaverPlaces.Place> found = PlaceLocator.find(this, store, text);
                main.post(() -> {
                    if (destroyed || !sheet.isShowing()) return;
                    results.removeAllViews();
                    if (found.isEmpty()) { state.setText("찾지 못했어요. 주소를 시·구부터 적거나 다른 이름으로 찾아 보세요."); return; }
                    state.setText("맞는 곳을 고르세요");
                    LinearLayout list = ui.group();
                    for (int i = 0; i < found.size(); i++) {
                        NaverPlaces.Place one = found.get(i);
                        ui.addRow(list, ui.listRow(R.drawable.ic_navigation, Ui.BLUE, one.name(), one.address(), ui.chevron(), () -> { sheet.dismiss(); done.accept(one); }), i < found.size() - 1);
                    }
                    results.addView(list);
                });
            });
        };
        ui.add(sheet.body, ui.button("찾기", true, search), 8);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, id, e) -> { search.run(); return true; });
        sheet.body.addView(state); ui.add(sheet.body, results, 8);
        sheet.primary.setText("닫기");
        sheet.show();
    }
}
