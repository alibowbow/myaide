package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.HomeBoard;
import dev.jun.gyeot.core.HtmlShowcase;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 내 휴대폰 → 화면 꾸미기 (준: "내 휴대폰 바탕화면을 위젯이나 재미나고 실용성있는 화면으로", "jev에 … 인터랙티브한 화면을"):
 * 움직이는 배경화면 first: the HTML 100 collections of the user's jev showcase (Opus 5.5, Sonnet 5.5, Gemini 4 Argon, GPT-6
 * Astra), by collection (the switch at the end of its heading) and kind, a few at a time, each tried full screen before it
 * becomes the live wallpaper (HtmlWallpaper); or one of the user's own HTML pages, or any address.
 */
public final class DecorActivity extends Activity {
    private Ui ui; private LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private boolean destroyed;
    private String collection = "opus", category = ""; private int shown = 20;
    private HtmlShowcase.Catalogue catalogue; private String loading = "";
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(60);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        collection = prefs().getString("collection", "opus");
        draw(); load();
    }
    @Override protected void onDestroy() { destroyed = true; work.shutdownNow(); super.onDestroy(); }
    private SharedPreferences prefs() { return getSharedPreferences(HtmlWallpaper.PREFS, Context.MODE_PRIVATE); }

    private void draw() {
        // A wide screen (a foldable opened, a tablet) keeps the list at a phone's reading width, in the middle.
        int wide = getResources().getConfiguration().screenWidthDp, side = wide > 640 ? (wide - 600) / 2 : 16;
        ScrollView scroll = new ScrollView(this); body = ui.column(); body.setPadding(ui.dp(side), ui.dp(4), ui.dp(side), ui.dp(24)); scroll.addView(body);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        View back = ui.backButton("내 휴대폰", this::finish); back.setMinimumHeight(ui.dp(48)); body.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.title("화면 꾸미기", 26); title.setAccessibilityHeading(true); ui.add(body, title, 4);
        ui.add(body, ui.label(homeHeld() ? "들퍼레 홈 화면의 오른쪽 페이지에 움직이는 작품, 내 페이지, 웹 페이지를 넣어요. 보이지 않는 페이지는 멈춰서 배터리를 아껴요."
            : "움직이고 손으로 만질 수 있는 화면을 바탕화면으로 깔아요. 다른 앱을 쓰는 동안에는 멈춰서 배터리를 아껴요.", 13, Ui.MUTED), 8);

        // 홈 화면 앱 (준: "이 아이콘들을 혁신적으로 편리하게 사용, 정리할 수 있게(애플 맥북like)"): 들퍼레 as the phone's home screen,
        // only when the user picks it; tapping the row tries it first.
        boolean homeApp = homeHeld();
        LinearLayout launcherGroup = ui.group();
        ui.addRow(launcherGroup, ui.listRow(R.drawable.ic_home, Ui.ACCENT, homeApp ? "들퍼레 홈 화면 · 사용 중" : "들퍼레 홈 화면 · 맥 스타일",
            homeApp ? "원래 홈 화면으로 돌아가려면 ‘바꾸기’" : "독 · 자동 묶음 · 런치패드 · 초성 검색 · 눌러서 미리 보기",
            ui.smallChip(homeApp ? "바꾸기" : "켜기", homeApp ? this::homeSettings : this::askHome),
            () -> startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))), true);
        LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(-1, -2); lg.bottomMargin = ui.dp(14);
        body.addView(launcherGroup, lg);

        // 들퍼레 홈 화면 in use: its own pages (준: "현재 화면 구성이랑 이게 달라", "들퍼레 홈 화면 우측 페이지들을 커스터마이징"):
        // 오늘 and 홈 as they are, then the user's pages to the right, each changed or taken out here.
        launcherMode = homeApp;
        if (launcherMode) { drawLauncherPages(); drawPieces(); return; }
        // 홈 화면 페이지 (준: "1페이지는 기존 배경화면 2페이지 3페이지...에만 앱에서 넣은화면"): each page's slot, changed in place.
        List<HomeBoard.Slot> slots = HtmlWallpaper.slots(prefs()); boolean ours = ours();
        LinearLayout pagesHead = ui.row(); pagesHead.setGravity(Gravity.CENTER_VERTICAL); pagesHead.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(8));
        TextView ph = ui.text("홈 화면 페이지", 15, 700, Ui.TEXT); ph.setAccessibilityHeading(true); pagesHead.addView(ph, new LinearLayout.LayoutParams(0, -2, 1));
        if (!slots.isEmpty() && slots.size() < HomeBoard.MOST_SLOTS) pagesHead.addView(ui.smallChip("+ 페이지", () -> choose(slots.size())));
        body.addView(pagesHead);
        LinearLayout pages = ui.group();
        if (slots.isEmpty()) {
            ui.addRow(pages, ui.listRow(R.drawable.ic_home, Ui.ACCENT, "퍼레 홈으로 시작하기", "1페이지는 지금 배경화면 사진, 2페이지는 일정·시세·알림을 보여 주는 퍼레 홈", ui.chevron(), this::startHome), true);
        } else for (int i = 0; i < slots.size(); i++) {
            HomeBoard.Slot slot = slots.get(i); int at = i;
            boolean photo = HomeBoard.PHOTO.equals(slot.src()), home = HomeBoard.HOME.equals(slot.src());
            String sub = photo ? (HtmlWallpaper.photo(this).isFile() ? "원래 배경화면 사진" : "사진이 아직 없어요 · 눌러서 골라요")
                : home ? "일정 · 시세(BTC · SOXL · KORU) · 알림 · 퍼레의 제안" : slot.src().startsWith(HtmlWallpaper.PAGE) ? "내 페이지" : String.valueOf(android.net.Uri.parse(slot.src()).getHost());
            ui.addRow(pages, ui.listRow(photo ? R.drawable.ic_gallery : home ? R.drawable.ic_home : R.drawable.ic_layers, photo ? Ui.BLUE : home ? Ui.ACCENT : Ui.PURPLE,
                (slots.size() > 1 ? (i + 1) + "페이지 · " : "모든 페이지 · ") + slot.title(), sub, ui.smallChip("바꾸기", () -> choose(at)), () -> choose(at)), true);
        }
        body.addView(pages);
        if (!slots.isEmpty()) {
            String note = !ours ? "" : slots.size() < 2 ? "" : prefs().getBoolean(HtmlWallpaper.SEEN, false) ? "홈 화면을 옆으로 넘기면 페이지마다 바뀌어요"
                : "홈 화면을 한 번 옆으로 넘겨 주세요. 넘겨도 그대로면 이 홈 화면이 페이지 위치를 알려 주지 않는 거라, 사진이 아닌 첫 화면만 보여요";
            LinearLayout state = ui.row(); state.setGravity(Gravity.CENTER_VERTICAL); state.setPadding(ui.dp(4), ui.dp(8), 0, 0);
            TextView words = ui.label(ours ? note : "아직 배경화면으로 설정되지 않았어요", 12, ours ? Ui.MUTED : Ui.ERROR); state.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (!ours) state.addView(ui.smallChip("적용", this::ensureOurs));
            if (!words.getText().toString().isEmpty() || !ours) body.addView(state);
            // A tap on 퍼레 홈 opens the app's screen through the edge handle's accessibility service; without it, a notification.
            boolean board = false; for (HomeBoard.Slot s : slots) board |= HomeBoard.HOME.equals(s.src());
            if (board && !PhoneService.connected()) {
                LinearLayout reach = ui.row(); reach.setGravity(Gravity.CENTER_VERTICAL); reach.setPadding(ui.dp(4), ui.dp(6), 0, 0);
                reach.addView(ui.label("퍼레 홈을 눌러 앱 화면을 바로 열려면 오른쪽 바(접근성)를 켜 주세요", 12, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
                reach.addView(ui.smallChip("켜기", () -> { try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (RuntimeException ignored) {} }));
                body.addView(reach);
            }
        }

        drawPieces();
    }
    /** 작품 (by collection and kind) and the other kinds of screen: what a page or the wallpaper can show. */
    private void drawPieces() {
        // 작품: the collection switch at the end of the heading line.
        LinearLayout head = ui.row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(ui.dp(4), ui.dp(22), 0, ui.dp(8));
        TextView h = ui.text("작품", 15, 700, Ui.TEXT); head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        String[] names = new String[HtmlShowcase.COLLECTIONS.size()]; int at = 0;
        for (int i = 0; i < names.length; i++) { names[i] = HtmlShowcase.COLLECTIONS.get(i).name().replace("Gemini 4 ", "").replace("GPT-6 ", ""); if (HtmlShowcase.COLLECTIONS.get(i).id().equals(collection)) at = i; }
        head.addView(ui.switcher(names, at, i -> { collection = HtmlShowcase.COLLECTIONS.get(i).id(); prefs().edit().putString("collection", collection).apply(); category = ""; shown = 20; catalogue = null; draw(); load(); }));
        body.addView(head);
        TextView source = ui.label(HtmlShowcase.collection(collection).name() + " HTML 100 · MiaAI-Lab이 공개한 작품을 그 사이트에서 바로 실행해요 · jev 쇼케이스 목록", 12, Ui.MUTED);
        source.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); body.addView(source);

        if (catalogue == null) {
            TextView wait = ui.label(loading.isEmpty() ? "작품 목록을 받는 중…" : loading, 14, loading.isEmpty() ? Ui.MUTED : Ui.ERROR); wait.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12)); body.addView(wait);
        } else {
            // 종류: small chips in one sideways line.
            LinearLayout chips = ui.row(); HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.addView(chips);
            chips.addView(kind("전체", ""));
            for (HtmlShowcase.Category c : catalogue.categories()) chips.addView(kind(c.name(), c.id()));
            body.addView(strip);
            List<HtmlShowcase.Piece> pieces = new ArrayList<>();
            for (HtmlShowcase.Piece p : catalogue.pieces()) if (category.isEmpty() || category.equals(p.category())) pieces.add(p);
            LinearLayout list = ui.group(); LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(-1, -2); la.topMargin = ui.dp(8);
            for (int i = 0; i < Math.min(shown, pieces.size()); i++) ui.addRow(list, pieceRow(pieces.get(i)), false);
            body.addView(list, la);
            if (pieces.size() > shown) {
                TextView more = ui.text("작품 " + (pieces.size() - shown) + "개 더 보기", 13.5f, 650, Ui.ACCENT); more.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(4));
                more.setOnClickListener(v -> { shown += 20; draw(); }); Ui.pressable(more, true); body.addView(more);
            }
        }

        // 다른 화면
        body.addView(ui.sectionHeader("다른 화면으로"));
        LinearLayout other = ui.group();
        ui.addRow(other, ui.listRow(R.drawable.ic_pages, Ui.BLUE, "내 페이지에서", "페이지에 저장한 HTML(논문, 시각화, 트레이딩 …)을 " + (launcherMode ? "홈 화면 오른쪽 페이지로" : "배경화면으로"), ui.chevron(), () -> { if (launcherMode) target = -1; fromPages(); }), true);
        ui.addRow(other, ui.listRow(R.drawable.ic_globe, Ui.TEAL, "주소로 넣기", "https:// 로 시작하는 웹 페이지", ui.chevron(), this::fromAddress), true);
        body.addView(other);
    }
    /**
     * 들퍼레 홈 화면's pages (준: "들퍼레 홈 화면 우측 페이지들을 커스터마이징 할 수 있게 해줘"): 오늘 and 홈 as they are, then the
     * user's own pages to the right of 홈 (a piece, one of their pages, an address), each changed or taken out; + adds one.
     */
    private void drawLauncherPages() {
        List<HomeBoard.Slot> slots = slots();
        LinearLayout head = ui.row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(8));
        TextView ph = ui.text("들퍼레 홈 화면 페이지", 15, 700, Ui.TEXT); ph.setAccessibilityHeading(true); head.addView(ph, new LinearLayout.LayoutParams(0, -2, 1));
        if (slots.size() < HomeBoard.MOST_SLOTS) head.addView(ui.smallChip("+ 페이지", () -> choose(slots.size())));
        body.addView(head);
        LinearLayout pages = ui.group();
        ui.addRow(pages, ui.listRow(R.drawable.ic_bulb, Ui.TEAL, "왼쪽 · 오늘", "오늘 일정 · 알림 · 이건 어때요? · 퍼레에게 묻기", null, null), true);
        ui.addRow(pages, ui.listRow(R.drawable.ic_home, Ui.ACCENT, "가운데 · 홈", "시계 · 시세 · 날씨 · App Library · 독", null, null), true);
        for (int i = 0; i < slots.size(); i++) {
            HomeBoard.Slot slot = slots.get(i); int at = i;
            String sub = slot.src().startsWith(HtmlWallpaper.PAGE) ? "내 페이지" : String.valueOf(android.net.Uri.parse(slot.src()).getHost());
            ui.addRow(pages, ui.listRow(R.drawable.ic_layers, Ui.PURPLE, "오른쪽 " + (i + 1) + " · " + slot.title(), sub, ui.smallChip("바꾸기", () -> choose(at)), () -> choose(at)), true);
        }
        if (slots.isEmpty()) ui.addRow(pages, ui.listRow(R.drawable.ic_add, Ui.BLUE, "오른쪽에 페이지 더하기", "아래 작품, 내 페이지, 웹 주소 중에서 골라요", ui.chevron(), () -> choose(0)), true);
        body.addView(pages);
        TextView note = ui.label("홈에서 오른쪽으로 넘기면 나와요. 페이지 양쪽 가장자리를 밀면 언제든 옆 페이지로 넘어가요.", 12, Ui.MUTED);
        note.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0); body.addView(note);
    }
    private View kind(String name, String id) {
        android.widget.Button chip = ui.smallChip(name, () -> { category = id; shown = 20; draw(); });
        boolean on = category.equals(id);
        chip.setBackground(ui.shape(on ? Ui.ACCENT : Ui.FILL, Ui.PILL)); chip.setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.rightMargin = ui.dp(6); chip.setLayoutParams(at);
        return chip;
    }
    /** One piece: its picture (from the creator's site), its Korean title and one line of what it does; a tap opens it. */
    private View pieceRow(HtmlShowcase.Piece p) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        ImageView thumb = new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setBackground(ui.shape(Ui.FILL, 10)); thumb.setClipToOutline(true);
        LinearLayout.LayoutParams ta = new LinearLayout.LayoutParams(ui.dp(72), ui.dp(48)); ta.rightMargin = ui.dp(12); row.addView(thumb, ta);
        thumb(p.poster(), thumb);
        LinearLayout words = ui.column();
        TextView t = ui.text(p.number() + " · " + p.title(), 14.5f, 600, Ui.TEXT); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); words.addView(t);
        TextView s = ui.text(p.summary(), 12.5f, 450, Ui.MUTED); s.setMaxLines(2); s.setEllipsize(TextUtils.TruncateAt.END); words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> piece(p)); Ui.pressable(row, true);
        row.setContentDescription(p.title() + ", " + p.summary());
        return row;
    }
    /** A piece: tried full screen (it can be touched there), then set as the wallpaper. */
    private void piece(HtmlShowcase.Piece p) {
        Ui.Sheet sheet = ui.selectionSheet(p.title(), p.original() + " · " + p.summary());
        ImageView big = new ImageView(this); big.setScaleType(ImageView.ScaleType.CENTER_CROP); big.setBackground(ui.shape(Ui.FILL, 14)); big.setClipToOutline(true);
        sheet.body.addView(big, new LinearLayout.LayoutParams(-1, ui.dp(180))); thumb(p.poster(), big);
        pageSwitch(sheet);
        sheet.primary.setText(pageWord("배경화면으로"));
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); put(p.demo(), p.title()); });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.topMargin = ui.dp(8);
        sheet.footer.addView(ui.button("전체 화면으로 미리 보기", false, () -> preview(p.demo())), at);
        sheet.show();
    }
    /** The page full screen to try (touch, sound with a tap), closed with back or ✕. */
    private void preview(String url) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this);
        android.webkit.WebView web = new android.webkit.WebView(this);
        android.webkit.WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        web.setWebViewClient(new android.webkit.WebViewClient());
        if (url.startsWith(HtmlWallpaper.PAGE)) { try { web.loadDataWithBaseURL(null, PageStore.book(this).body(url.substring(HtmlWallpaper.PAGE.length())), "text/html", "utf-8", null); } catch (Exception e) { Toast.makeText(this, "페이지를 열지 못했어요.", Toast.LENGTH_SHORT).show(); return; } }
        else web.loadUrl(url);
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        ImageView close = ui.bareIcon(R.drawable.ic_close, 0xffffffff, "닫기", d::dismiss); close.setBackground(ui.shape(0x88000000, 99));
        FrameLayout.LayoutParams ca = new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP | Gravity.END); ca.setMargins(0, ui.dp(36), ui.dp(16), 0);
        frame.addView(close, ca);
        d.setContentView(frame); d.setOnDismissListener(x -> web.destroy()); d.show();
    }
    // ── pages ────────────────────────────────────────────────────────

    /** The page the next choice goes to: an index, the one after the last (a new page), or -1 for the whole wallpaper. */
    private int target = -1;
    private static final int PICK = 7521;
    /** Which pages this screen edits: 들퍼레 홈 화면's right pages while it is the home screen, else the live wallpaper's. */
    private boolean launcherMode;
    private SharedPreferences homePrefs() { return getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE); }
    private List<HomeBoard.Slot> slots() {
        if (!launcherMode) return new ArrayList<>(HtmlWallpaper.slots(prefs()));
        List<HomeBoard.Slot> out = new ArrayList<>();
        for (HomeBoard.Slot s : HomeBoard.slots(homePrefs().getString(HomeActivity.PAGES, ""), "", "")) if (s.web()) out.add(s);
        return out;
    }
    /** The default page for a piece: the page last asked for, else the last page that is not the photo or the board. */
    private int pageFor(List<HomeBoard.Slot> slots) {
        if (target >= 0 && target <= slots.size()) return target;
        if (launcherMode) return Math.min(slots.size(), HomeBoard.MOST_SLOTS - 1);
        if (slots.size() < 2) return -1;
        for (int i = slots.size() - 1; i >= 0; i--) if (slots.get(i).web()) return i;
        return slots.size() < HomeBoard.MOST_SLOTS ? slots.size() : slots.size() - 1;
    }
    private String pageWord(String whole) {
        int at = pageFor(slots());
        if (launcherMode) return "오른쪽 " + (at + 1) + "페이지에 넣기";
        return at < 0 ? whole : (at + 1) + "페이지에 넣기";
    }
    /** With pages: which page, as a small switch at the end of one line (the pages, then + for a new one). */
    private void pageSwitch(Ui.Sheet sheet) {
        List<HomeBoard.Slot> slots = slots();
        if (launcherMode ? slots.isEmpty() : slots.size() < 2 && target < 0) return;
        int count = Math.min(HomeBoard.MOST_SLOTS, slots.size() + 1);
        String[] names = new String[count]; for (int i = 0; i < count; i++) names[i] = i < slots.size() ? String.valueOf(i + 1) : "+";
        LinearLayout line = ui.row(); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(0, ui.dp(10), 0, 0);
        line.addView(ui.text("넣을 페이지", 13.5f, 600, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        int now = Math.max(0, Math.min(count - 1, pageFor(slots)));
        line.addView(ui.switcher(names, now, i -> { target = i; sheet.primary.setText((launcherMode ? "오른쪽 " : "") + (i + 1) + "페이지에 넣기"); }));
        target = now;
        sheet.body.addView(line);
    }
    /** What a page shows: one of the five, or the page taken out. */
    private void choose(int page) {
        if (launcherMode) { chooseRight(page); return; }
        List<HomeBoard.Slot> slots = slots();
        Ui.Sheet menu = ui.selectionSheet(page < slots.size() ? (page + 1) + "페이지에 넣을 화면" : "새 페이지에 넣을 화면", page < slots.size() ? "지금: " + slots.get(page).title() : (page + 1) + "페이지가 돼요");
        menu.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_gallery, Ui.BLUE, "원래 사진", HtmlWallpaper.photo(this).isFile() ? "지금 쓰는 사진 · 다른 사진은 아래에서" : "지금 배경화면을 가져오거나 갤러리에서 골라요", null,
            () -> { menu.dismiss(); target = page; if (HtmlWallpaper.photo(this).isFile()) put(HomeBoard.PHOTO, HomeBoard.name(HomeBoard.PHOTO)); else takeWallpaper(); }), true);
        if (HtmlWallpaper.photo(this).isFile()) ui.addRow(group, ui.listRow(R.drawable.ic_gallery, Ui.TEAL, "다른 사진 고르기", "갤러리에서 한 장", null, () -> { menu.dismiss(); target = page; pickPhoto(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_home, Ui.ACCENT, "퍼레 홈", "일정 · 시세 · 알림 · 퍼레의 제안, 누르면 앱 화면이 열려요", null, () -> { menu.dismiss(); target = page; put(HomeBoard.HOME, HomeBoard.name(HomeBoard.HOME)); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_layers, Ui.PURPLE, "작품에서 고르기", "아래 목록의 움직이는 화면", ui.chevron(), () -> { menu.dismiss(); target = page; Toast.makeText(this, (page + 1) + "페이지에 넣을 작품을 아래에서 골라 주세요", Toast.LENGTH_SHORT).show(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_pages, Ui.BLUE, "내 페이지에서", "페이지에 저장한 HTML", ui.chevron(), () -> { menu.dismiss(); target = page; fromPages(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_globe, Ui.TEAL, "주소로 넣기", "https:// 로 시작하는 웹 페이지", ui.chevron(), () -> { menu.dismiss(); target = page; fromAddress(); }), true);
        if (page < slots.size()) ui.addRow(group, ui.listRow(R.drawable.ic_close, Ui.ERROR, slots.size() > 1 ? "이 페이지 빼기" : "움직이는 배경화면 끄기", slots.size() > 1 ? "뒤 페이지가 한 칸씩 당겨져요" : "고른 화면을 비워요", null, () -> {
            menu.dismiss(); List<HomeBoard.Slot> now = slots(); if (page < now.size()) now.remove(page); save(now); draw();
        }), true);
        menu.body.addView(group);
        menu.show();
    }
    /** A right page of 들퍼레 홈 화면: a piece, one of the user's pages or an address, or taken out. */
    private void chooseRight(int page) {
        List<HomeBoard.Slot> slots = slots();
        Ui.Sheet menu = ui.selectionSheet(page < slots.size() ? "오른쪽 " + (page + 1) + "페이지에 넣을 화면" : "오른쪽에 새 페이지", page < slots.size() ? "지금: " + slots.get(page).title() : "홈 오른쪽 " + (page + 1) + "번째 페이지가 돼요");
        menu.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_layers, Ui.PURPLE, "작품에서 고르기", "아래 목록의 움직이는 화면", ui.chevron(), () -> { menu.dismiss(); target = page; Toast.makeText(this, "오른쪽 " + (page + 1) + "페이지에 넣을 작품을 아래에서 골라 주세요", Toast.LENGTH_SHORT).show(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_pages, Ui.BLUE, "내 페이지에서", "페이지에 저장한 HTML (트레이딩, 논문, 시각화 …)", ui.chevron(), () -> { menu.dismiss(); target = page; fromPages(); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_globe, Ui.TEAL, "주소로 넣기", "https:// 로 시작하는 웹 페이지", ui.chevron(), () -> { menu.dismiss(); target = page; fromAddress(); }), true);
        if (page < slots.size()) ui.addRow(group, ui.listRow(R.drawable.ic_close, Ui.ERROR, "이 페이지 빼기", "뒤 페이지가 한 칸씩 당겨져요", null, () -> {
            menu.dismiss(); List<HomeBoard.Slot> now = slots(); if (page < now.size()) now.remove(page); save(now); draw();
        }), true);
        menu.body.addView(group);
        menu.show();
    }
    /** The choice goes to the page asked for (a new page after the last), else it becomes the whole wallpaper; then it is applied. */
    private void put(String src, String title) {
        List<HomeBoard.Slot> slots = slots(); int at = pageFor(slots);
        HomeBoard.Slot slot = new HomeBoard.Slot(src, title == null || title.isBlank() ? HomeBoard.name(src) : title);
        if (at >= 0 && at < slots.size()) slots.set(at, slot);
        else if (at >= 0 && slots.size() < HomeBoard.MOST_SLOTS) slots.add(slot);
        else { slots.clear(); slots.add(slot); }
        // 퍼레 홈 once: chosen for another page, it moves there.
        if (HomeBoard.HOME.equals(src)) for (int i = slots.size() - 1; i >= 0; i--) if (slots.get(i) != slot && HomeBoard.HOME.equals(slots.get(i).src())) slots.remove(i);
        target = -1; save(slots);
        if (launcherMode) { Toast.makeText(this, "들퍼레 홈 화면 오른쪽 " + (slots.indexOf(slot) + 1) + "페이지에 ‘" + slot.title() + "’을(를) 넣었어요", Toast.LENGTH_SHORT).show(); draw(); return; }
        if (ours()) { Toast.makeText(this, "‘" + slot.title() + "’(으)로 바꿨어요", Toast.LENGTH_SHORT).show(); draw(); }
        else ensureOurs();
    }
    private void save(List<HomeBoard.Slot> slots) {
        if (launcherMode) { homePrefs().edit().putString(HomeActivity.PAGES, HomeBoard.save(slots)).apply(); return; }
        prefs().edit().putString(HtmlWallpaper.LAYOUT, HomeBoard.save(slots)).putString(HtmlWallpaper.URL, "").putString(HtmlWallpaper.TITLE, "").apply();
    }
    /** 퍼레 홈 from the start: the photo on the first page (the wallpaper there now), the board on the second. */
    private void startHome() {
        save(List.of(new HomeBoard.Slot(HomeBoard.PHOTO, HomeBoard.name(HomeBoard.PHOTO)), new HomeBoard.Slot(HomeBoard.HOME, HomeBoard.name(HomeBoard.HOME))));
        draw();
        if (HtmlWallpaper.photo(this).isFile()) { ensureOurs(); return; }
        target = 0; takeWallpaper();
    }
    /** The wallpaper there now (a still picture, not a live one) as the photo page; when Android does not hand it over, the gallery. */
    private void takeWallpaper() {
        work.execute(() -> {
            boolean took = false;
            try {
                WallpaperManager wm = WallpaperManager.getInstance(this);
                if (wm.getWallpaperInfo() == null) {
                    android.graphics.drawable.Drawable d = wm.getDrawable();
                    if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight(); float k = Math.min(1f, 2400f / Math.max(w, h));
                        Bitmap b = Bitmap.createBitmap(Math.round(w * k), Math.round(h * k), Bitmap.Config.ARGB_8888);
                        d.setBounds(0, 0, b.getWidth(), b.getHeight()); d.draw(new android.graphics.Canvas(b));
                        took = savePhoto(b);
                    }
                }
            } catch (RuntimeException | OutOfMemoryError refused) { /* the gallery instead */ }
            boolean done = took;
            main.post(() -> {
                if (destroyed) return;
                if (done) { Toast.makeText(this, "지금 배경화면 사진을 1페이지에 넣었어요", Toast.LENGTH_SHORT).show(); put(HomeBoard.PHOTO, HomeBoard.name(HomeBoard.PHOTO)); }
                else { Toast.makeText(this, "이 페이지에 깔 사진을 골라 주세요", Toast.LENGTH_LONG).show(); pickPhoto(); }
            });
        });
    }
    private void pickPhoto() {
        try { startActivityForResult(new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PICK); }
        catch (RuntimeException none) {
            try { startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PICK); }
            catch (RuntimeException neither) { Toast.makeText(this, "사진을 고를 앱이 없어요.", Toast.LENGTH_SHORT).show(); }
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == HOME_ROLE) { if (homeHeld()) Toast.makeText(this, "이제 홈 버튼을 누르면 들퍼레 홈 화면이 열려요", Toast.LENGTH_LONG).show(); draw(); return; }
        if (request != PICK) return;
        android.net.Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) { if (!HtmlWallpaper.photo(this).isFile()) { target = -1; draw(); } return; }
        int page = target;
        work.execute(() -> {
            boolean saved = false;
            try {
                // Turned upright and kept sharp for the larger screen (a folded phone's inner screen too), at most 2400 px.
                Bitmap b = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight(); float k = Math.min(1f, 2400f / Math.max(w, h));
                    decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k))); decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                saved = savePhoto(b);
            } catch (Exception | OutOfMemoryError unreadable) { /* said below */ }
            boolean ok = saved;
            main.post(() -> {
                if (destroyed) return;
                if (!ok) { Toast.makeText(this, "이 사진을 읽지 못했어요.", Toast.LENGTH_SHORT).show(); return; }
                target = page; put(HomeBoard.PHOTO, HomeBoard.name(HomeBoard.PHOTO));
            });
        });
    }
    /** The photo page's picture; the wallpaper loads it again (photoAt). */
    private boolean savePhoto(Bitmap b) {
        File f = HtmlWallpaper.photo(this); f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) { if (!b.compress(Bitmap.CompressFormat.JPEG, 90, out)) return false; }
        catch (Exception e) { part.delete(); return false; }
        if (!part.renameTo(f)) { part.delete(); return false; }
        prefs().edit().putLong(HtmlWallpaper.PHOTO_AT, System.currentTimeMillis()).apply();
        return true;
    }
    // ── 홈 화면 앱 ───────────────────────────────────────────────────
    private static final int HOME_ROLE = 7522;
    private boolean homeHeld() {
        try { android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class); return roles != null && roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME); }
        catch (RuntimeException unknown) { return false; }
    }
    /** The phone's own question (which app is the home screen); without it, the home app settings. */
    private void askHome() {
        try {
            android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) { startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), HOME_ROLE); return; }
        } catch (RuntimeException refused) { /* the settings instead */ }
        homeSettings();
    }
    private void homeSettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); Toast.makeText(this, "쓰고 싶은 홈 화면 앱을 골라 주세요", Toast.LENGTH_LONG).show(); }
        catch (RuntimeException none) { Toast.makeText(this, "이 휴대폰에서는 홈 화면 앱 설정을 열 수 없어요.", Toast.LENGTH_LONG).show(); }
    }

    /** 들퍼레 becomes the wallpaper: the system's own preview asks once. */
    private void ensureOurs() {
        if (ours()) { draw(); return; }
        try {
            startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, HtmlWallpaper.class)));
            Toast.makeText(this, "미리보기에서 ‘배경화면으로 설정’을 눌러 주세요", Toast.LENGTH_LONG).show();
        } catch (RuntimeException noPreview) {
            try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); Toast.makeText(this, "목록에서 ‘들퍼레 움직이는 배경화면’을 골라 주세요", Toast.LENGTH_LONG).show(); }
            catch (RuntimeException none) { Toast.makeText(this, "이 휴대폰은 움직이는 배경화면을 바꿀 수 없어요.", Toast.LENGTH_LONG).show(); }
        }
    }
    private boolean ours() {
        try { WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo(); return info != null && getPackageName().equals(info.getPackageName()); }
        catch (RuntimeException unknown) { return false; }
    }
    @Override protected void onResume() { super.onResume(); if (body != null) draw(); }
    private void fromPages() {
        List<dev.jun.gyeot.core.PageBook.Page> pages = new ArrayList<>();
        for (dev.jun.gyeot.core.PageBook.Page p : PageStore.book(this).pages()) if (p.html()) pages.add(p);
        if (pages.isEmpty()) { Toast.makeText(this, "HTML 페이지가 아직 없어요. 연구실 논문이나 마이튜브 시각화를 페이지로 저장하면 여기에 나와요.", Toast.LENGTH_LONG).show(); return; }
        String[] names = new String[Math.min(30, pages.size())]; for (int i = 0; i < names.length; i++) names[i] = pages.get(i).title();
        new android.app.AlertDialog.Builder(this).setTitle(launcherMode ? "홈 화면 오른쪽에 넣을 페이지" : "배경화면으로 쓸 페이지").setItems(names, (d, i) -> put(HtmlWallpaper.PAGE + pages.get(i).id(), pages.get(i).title())).show();
    }
    private void fromAddress() {
        Ui.Sheet sheet = ui.selectionSheet("주소로 넣기", "움직이는 웹 페이지의 주소를 붙여 주세요. 그 페이지 밖으로 나가는 링크는 열지 않아요.");
        android.widget.EditText address = ui.input("https://", 1, 500);
        sheet.body.addView(address, new LinearLayout.LayoutParams(-1, -2));
        sheet.primary.setText(pageWord("배경화면으로"));
        sheet.primary.setOnClickListener(v -> {
            String a = address.getText().toString().strip();
            if (!a.startsWith("https://")) { address.setError("https:// 로 시작하는 주소를 적어 주세요."); return; }
            sheet.dismiss(); put(a, android.net.Uri.parse(a).getHost());
        });
        sheet.show();
    }

    // ── reading ──────────────────────────────────────────────────────

    /** The collection's list: jev's data file, kept a week in the app's files (offline, it opens what was kept). */
    private void load() {
        String id = collection; HtmlShowcase.Collection c = HtmlShowcase.collection(id); loading = "";
        work.execute(() -> {
            File kept = new File(new File(getFilesDir(), "decor"), id + ".js");
            String script = null, failure = null;
            try {
                if (kept.isFile() && System.currentTimeMillis() - kept.lastModified() < 7 * 86_400_000L) script = Files.readString(kept.toPath());
                else { script = fetch(c.data(), 4_000_000); kept.getParentFile().mkdirs(); Files.writeString(kept.toPath(), script); }
            } catch (Exception e) {
                try { if (kept.isFile()) script = Files.readString(kept.toPath()); } catch (Exception ignored) {}
                if (script == null) failure = "작품 목록을 받지 못했어요 · 인터넷을 확인하고 다시 열어 주세요";
            }
            HtmlShowcase.Catalogue got = script == null ? null : HtmlShowcase.parse(script);
            String why = failure;
            main.post(() -> { if (destroyed || !id.equals(collection)) return; catalogue = got != null && !got.pieces().isEmpty() ? got : null; if (catalogue == null) loading = why == null ? "작품을 읽지 못했어요" : why; draw(); });
        });
    }
    private void thumb(String url, ImageView into) {
        if (url == null || url.isEmpty()) return;
        Bitmap kept = THUMBS.get(url); if (kept != null) { into.setImageBitmap(kept); return; }
        into.setTag(url);
        work.execute(() -> {
            try {
                File cache = new File(new File(getCacheDir(), "decor"), Integer.toHexString(url.hashCode()) + ".jpg");
                byte[] bytes;
                if (cache.isFile()) bytes = Files.readAllBytes(cache.toPath());
                else { bytes = fetchBytes(url, 2_000_000); cache.getParentFile().mkdirs(); Files.write(cache.toPath(), bytes); }
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o); if (b == null) return;
                THUMBS.put(url, b);
                main.post(() -> { if (!destroyed && url.equals(into.getTag())) into.setImageBitmap(b); });
            } catch (Exception unavailable) { /* the gray box stays */ }
        });
    }
    private static String fetch(String url, int limit) throws Exception { return new String(fetchBytes(url, limit), StandardCharsets.UTF_8); }
    private static byte[] fetchBytes(String url, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15_000); c.setReadTimeout(20_000); c.setRequestProperty("User-Agent", "Gyeot/1.0");
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) { byte[] all = in.readNBytes(limit + 1); if (all.length > limit) throw new java.io.IOException("too big"); return all; }
        } finally { c.disconnect(); }
    }
}
