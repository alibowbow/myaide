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
        ScrollView scroll = new ScrollView(this); body = ui.column(); body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)); scroll.addView(body);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        View back = ui.backButton("내 휴대폰", this::finish); back.setMinimumHeight(ui.dp(48)); body.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.title("화면 꾸미기", 26); title.setAccessibilityHeading(true); ui.add(body, title, 4);
        ui.add(body, ui.label("움직이고 손으로 만질 수 있는 화면을 바탕화면으로 깔아요. 다른 앱을 쓰는 동안에는 멈춰서 배터리를 아껴요.", 13, Ui.MUTED), 8);

        // 지금 배경화면
        String now = prefs().getString(HtmlWallpaper.TITLE, ""); boolean ours = ours();
        LinearLayout current = ui.group();
        View off = ui.smallChip(ours ? "다시 적용" : "적용", () -> apply(prefs().getString(HtmlWallpaper.URL, ""), now));
        ui.addRow(current, ui.listRow(R.drawable.ic_sparkle, Ui.ACCENT, ours ? "지금 배경화면 · " + (now.isEmpty() ? "고르지 않음" : now) : now.isEmpty() ? "움직이는 배경화면이 꺼져 있어요" : "고른 화면 · " + now,
            ours ? "홈 화면을 만지면 화면도 반응해요" : now.isEmpty() ? "아래에서 하나를 골라 ‘배경화면으로’를 눌러요" : "아직 배경화면으로 설정되지 않았어요", now.isEmpty() ? null : off, null), true);
        body.addView(current);

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
        ui.addRow(other, ui.listRow(R.drawable.ic_pages, Ui.BLUE, "내 페이지에서", "페이지에 저장한 HTML(논문, 시각화, 트레이딩 …)을 배경화면으로", ui.chevron(), this::fromPages), true);
        ui.addRow(other, ui.listRow(R.drawable.ic_globe, Ui.TEAL, "주소로 넣기", "https:// 로 시작하는 웹 페이지", ui.chevron(), this::fromAddress), true);
        body.addView(other);
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
        sheet.primary.setText("배경화면으로");
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); apply(p.demo(), p.title()); });
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
    /** The wallpaper is this page from now: the system's own preview asks once when 들퍼레 is not the wallpaper yet. */
    private void apply(String url, String title) {
        if (url == null || url.isBlank()) return;
        prefs().edit().putString(HtmlWallpaper.URL, url).putString(HtmlWallpaper.TITLE, title).apply();
        if (ours()) { Toast.makeText(this, "배경화면을 ‘" + title + "’(으)로 바꿨어요", Toast.LENGTH_SHORT).show(); draw(); return; }
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
        new android.app.AlertDialog.Builder(this).setTitle("배경화면으로 쓸 페이지").setItems(names, (d, i) -> apply(HtmlWallpaper.PAGE + pages.get(i).id(), pages.get(i).title())).show();
    }
    private void fromAddress() {
        Ui.Sheet sheet = ui.selectionSheet("주소로 넣기", "움직이는 웹 페이지의 주소를 붙여 주세요. 그 페이지 밖으로 나가는 링크는 열지 않아요.");
        android.widget.EditText address = ui.input("https://", 1, 500);
        sheet.body.addView(address, new LinearLayout.LayoutParams(-1, -2));
        sheet.primary.setText("배경화면으로");
        sheet.primary.setOnClickListener(v -> {
            String a = address.getText().toString().strip();
            if (!a.startsWith("https://")) { address.setError("https:// 로 시작하는 주소를 적어 주세요."); return; }
            sheet.dismiss(); apply(a, android.net.Uri.parse(a).getHost());
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
