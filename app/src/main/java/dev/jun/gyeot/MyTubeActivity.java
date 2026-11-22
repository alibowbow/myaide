package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.MyTube;
import dev.jun.gyeot.core.MyTubeBook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.mozilla.geckoview.GeckoView;
import static dev.jun.gyeot.Ui.*;

/**
 * 마이튜브: YouTube without its algorithm. Feeds the user keeps (videos like a channel or on a topic, only recent news,
 * songs to their taste), each chosen by their AI from YouTube's own pages (MyTubeClient), shown with a frame from the
 * middle of the video instead of its thumbnail and a line on why it was chosen. A tap plays the feed from there in the
 * player above (MyTubePlayer: the app's browser engine, so a YouTube sign-in, Premium included, is its own); likes and
 * 안 볼래요 steer the next choice. The feeds, the choices and the taste stay on this phone.
 */
public final class MyTubeActivity extends Activity implements MyTubeChat.Actions {
    static final String PLAY = "mytube_play";
    /** A feed a voice request asked to play (its name, or news / music), taken when the screen shows. */
    static volatile String requested;
    static void ask(String feedOrKind) { requested = feedOrKind == null || feedOrKind.isBlank() ? null : feedOrKind.strip(); }
    /** The 마이튜브 screen in front, for 음성 대화's functions (VoiceTargets.mytube); null when none. */
    private static java.lang.ref.WeakReference<MyTubeActivity> front = new java.lang.ref.WeakReference<>(null);
    static MyTubeActivity front() { MyTubeActivity a = front.get(); return a == null || a.isDestroyed() ? null : a; }
    /** 마이튜브 AI: its conversation card, composer and voice bar. */
    private MyTubeChat chat;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final List<Dialog> dialogs = new ArrayList<>();
    private static final LruCache<String, Bitmap> FRAMES = new LruCache<>(80);
    private Ui ui; private MyTubeBook book; private MyTubePlayer player; private AppStore store;
    private MainNavigation navigation; private LinearLayout root, playerCard, list, chips, savedChips, content; private GeckoView screen;
    /** 새로 고르기 (라이브 확인 on a live 저장 목록) and 피드 편집 (목록 편집): their words follow what is shown. */
    private Button refreshChip, editChip;
    private TextView nowTitle, nowLine, header, about, when; private ShimmerText statusLine; private View feedActions;
    /** The screen's two parts: the video with its play buttons, pinned on top across the full width, and the list scrolling under it. */
    private LinearLayout body, videoPane, searchBar, listPane, controlsRow; private ScrollView scroll;
    /**
     * 보기: 가로 꽉 (the video across the width, the list under it), 나란히 (a wide screen: the video on the left, the list on the
     * right; a Short always so) or 전체화면 (the video alone). The video sits on a stage, with the way out of 전체화면 on it.
     */
    static final String VIEW = "mytubeView";
    private String viewMode = "wide", lastView = "wide", arranged = ""; private android.widget.FrameLayout stage; private View exitFull, sideToggle, gripView;
    /** The keyboard is up (and how tall): while the search box is typed in, the video shrinks so the box stays in sight over it. */
    private boolean keyboardUp; private int keyboardHeight; private boolean typing;
    /** Under the video, one capsule: 이전 · 10초 뒤로 · 5초 뒤로 · 재생 · 5초 앞으로 · 10초 앞으로 · 다음; 끝내기 beside it (on a YouTube page, ← 목록으로). */
    private ImageView previous, toggle, next; private View transport, backChip; private Ui.Dial endDial;
    private Button heart, skipChip, speedChip, qualityChip, captureChip, summaryChip, saveChip; private int videoHeight;
    /** What a search or a channel lists (videos · shorts · live), and whether by views; the channel shown, when one is. */
    private String listKind = "videos"; private boolean byViews; private MyTube.Channel shownChannel; private LinearLayout listTools;
    /** 크기 조절: the video's height as a share of the screen's, chosen by dragging the bar under the controls (0: fitted). */
    static final String VIDEO_SIZE = "mytubeVideoSize";
    /** A Short's own size (0 작게, 1 보통: as tall as the room, 2 크게), apart from a video's: the size bar does not suit a Short. */
    static final String SHORTS_SIZE = "mytubeShortsSize";
    private int shortsSize = 1; private LinearLayout shortsSizer;
    private float sizeRatio, dragFrom; private int dragHeight; private boolean dragging; private TextView sizeToggle;
    /** 직접 검색: the words searched (null when the feeds show), what YouTube found, and the box itself. */
    private String searched; private List<MyTube.Video> found = List.of(); private EditText searchBox; private View searchActions; private Button searchPlayAll;
    /** A search on its way (its results show in place, with the line that it is looking); the newest search wins. */
    private boolean searching; private int searchTicket;
    /** The channels the words name (shown above the videos found); a channel opened shows its newest videos. */
    private List<MyTube.Channel> foundChannels = List.of();
    /** 라이브 채널: saved channels, one tap each to their broadcast on air now. */
    private LinearLayout liveChannels; private View liveScroll;
    /** 내용 정리 made in this run, by video. */
    private static final java.util.Map<String, String> SUMMARIES = new java.util.LinkedHashMap<>();
    private String feedId; private boolean refreshing, destroyed;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();
    private final Runnable playerChanged = () -> main.post(this::showPlayer);

    private void noTransition() { if (android.os.Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0); } else overridePendingTransition(0, 0); }
    @Override public void finish() { super.finish(); noTransition(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); noTransition();
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); book = MyTubeClient.book(this); player = MyTubePlayer.get(this);
        feedId = state != null ? state.getString("feed") : store.settings.getString("mytubeFeed", null);
        root = ui.column(); root.setBackgroundColor(BG);
        // The video stays in sight while the list scrolls: edge to edge on top (phone or unfolded), its play buttons under it.
        body = ui.column();
        videoPane = ui.column(); videoPane.setVisibility(View.GONE); videoPane.setBackgroundColor(BG);
        searchBar = ui.row(); searchBar.setGravity(Gravity.CENTER_VERTICAL); searchBar.setBackgroundColor(BG); searchBar.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = ui.column(); content.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24));
        scroll.addView(content);
        listPane = ui.column(); listPane.addView(searchBar, new LinearLayout.LayoutParams(-1, -2)); listPane.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(videoPane, new LinearLayout.LayoutParams(-1, -2)); body.addView(listPane, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.post(() -> {
            // Under the page: 음성 대화's bar while it talks, else the composer.
            LinearLayout.LayoutParams barAt = new LinearLayout.LayoutParams(-1, -2); barAt.setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(6));
            root.addView(chat.voice.bar(), barAt);
            chat.composer.setBackgroundColor(BG); root.addView(chat.composer, new LinearLayout.LayoutParams(-1, -2));
        });
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()), keys = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keys.bottom));
            keyboard(v);
            return insets;
        });
        // A window that resizes for the keyboard takes its inset before the root sees it: ask the window on each layout too.
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> keyboard(root));
        root.setFocusableInTouchMode(true);
        // 직접 검색: words search YouTube; a YouTube link (watch, Shorts, youtu.be …) opens that very video.
        searchBox = ui.searchField(ui.input("유튜브 검색 · 영상 주소를 붙여넣어도 돼요", 1, 500));
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        searchBox.setOnEditorActionListener((v, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) { search(searchBox.getText().toString()); return true; } return false; });
        searchBox.setContentDescription("유튜브 검색. 영상 주소를 붙여넣으면 그 영상을 바로 틀어요");
        // Slim and round, pinned between the video and the lists: search is always at hand, whatever the list shows.
        searchBox.setTextSize(14); searchBox.setMinHeight(ui.dp(40)); searchBox.setMinimumHeight(ui.dp(40)); searchBox.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        android.graphics.drawable.StateListDrawable pill = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable focused = ui.shape(CARD, PILL); focused.setStroke(ui.dp(1), ACCENT);
        pill.addState(new int[]{android.R.attr.state_focused}, focused); pill.addState(new int[]{}, ui.shape(FILL, PILL)); searchBox.setBackground(pill);
        android.graphics.drawable.Drawable lens = searchBox.getCompoundDrawablesRelative()[0]; if (lens != null) lens.setBounds(0, 0, ui.dp(17), ui.dp(17));
        searchBox.setCompoundDrawablePadding(ui.dp(8));
        searchBar.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
        // 검색 button: not every keyboard shows a search key.
        ImageView go = ui.quietIcon(R.drawable.ic_search, "검색", () -> search(searchBox.getText().toString()));
        go.setImageTintList(android.content.res.ColorStateList.valueOf(ON_ACCENT)); go.setBackground(ui.shape(ACCENT, PILL)); go.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9));
        LinearLayout.LayoutParams goAt = new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)); goAt.leftMargin = ui.dp(6); searchBar.addView(go, goAt);
        searchBox.setOnFocusChangeListener((v, hasFocus) -> main.post(this::showPlayer));
        // Its own search button and the keyboard's search key both search (the composer is out of the way meanwhile).
        // 라이브 채널: the channels saved from a search, each straight to its live broadcast.
        liveChannels = ui.row(); HorizontalScrollView liveRow = new HorizontalScrollView(this); liveRow.setHorizontalScrollBarEnabled(false); liveRow.addView(liveChannels); liveScroll = liveRow;
        LinearLayout.LayoutParams liveGap = new LinearLayout.LayoutParams(-1, -2); liveGap.bottomMargin = ui.dp(8); content.addView(liveRow, liveGap);
        // 마이튜브 AI: say what to watch or do (typed below, or spoken with 음성 대화).
        chat = new MyTubeChat(this, ui, store, book, work, main, this);
        LinearLayout.LayoutParams chatGap = new LinearLayout.LayoutParams(-1, -2); chatGap.bottomMargin = ui.dp(8); content.addView(chat.card, chatGap);
        buildPlayer(content);
        // Two rows: 추천, the feeds the AI fills; 저장, the lists of what the user saved (never replaced).
        chips = ui.row(); chips.setGravity(Gravity.CENTER_VERTICAL); HorizontalScrollView chipScroll = new HorizontalScrollView(this); chipScroll.setHorizontalScrollBarEnabled(false); chipScroll.addView(chips);
        LinearLayout.LayoutParams chipGap = new LinearLayout.LayoutParams(-1, -2); chipGap.topMargin = ui.dp(4); content.addView(chipScroll, chipGap);
        savedChips = ui.row(); savedChips.setGravity(Gravity.CENTER_VERTICAL); HorizontalScrollView savedScroll = new HorizontalScrollView(this); savedScroll.setHorizontalScrollBarEnabled(false); savedScroll.addView(savedChips);
        LinearLayout.LayoutParams savedGap = new LinearLayout.LayoutParams(-1, -2); savedGap.topMargin = ui.dp(6); content.addView(savedScroll, savedGap);
        header = ui.title("", 17); header.setPadding(0, ui.dp(12), 0, 0); content.addView(header);
        about = ui.label("", 13, MUTED); about.setPadding(0, ui.dp(2), 0, 0); content.addView(about);
        when = ui.label("", 12, MUTED); when.setPadding(0, ui.dp(2), 0, 0); content.addView(when);
        refreshChip = withIcon(ui.smallChip("새로 고르기", this::refresh), R.drawable.ic_refresh);
        editChip = ui.smallChip("피드 편집", () -> { MyTube.Feed f = feed(); if (f != null) { if (f.saved()) editList(f); else editFeed(f); } });
        feedActions = ui.actions(refreshChip, withIcon(ui.smallChip("모두 재생", () -> playFrom(0)), R.drawable.ic_play), editChip);
        content.addView(feedActions);
        searchPlayAll = withIcon(ui.smallChip("모두 재생", () -> playFound(0)), R.drawable.ic_play);
        // A search's or a channel's tools in one slim row: 모두 재생 · 동영상|쇼츠(|라이브) · 최신|조회수 · 유튜브 화면 · 닫기.
        listTools = ui.row(); listTools.setGravity(Gravity.CENTER_VERTICAL); listTools.setPadding(0, ui.dp(6), 0, 0);
        HorizontalScrollView toolScroll = new HorizontalScrollView(this); toolScroll.setHorizontalScrollBarEnabled(false); toolScroll.addView(listTools);
        searchActions = toolScroll; searchActions.setVisibility(View.GONE); content.addView(searchActions);
        statusLine = new ShimmerText(this, ACCENT, TEXT); statusLine.setTextSize(13); statusLine.setTypeface(Ui.weight(600)); statusLine.setPadding(0, ui.dp(8), 0, 0); statusLine.setVisibility(View.GONE); content.addView(statusLine);
        list = ui.column(); LinearLayout.LayoutParams listGap = new LinearLayout.LayoutParams(-1, -2); listGap.topMargin = ui.dp(10); content.addView(list, listGap);
        buildSettings(content);
        navigation = new MainNavigation(this, root, "mytube", target -> MainNavigation.open(this, target), false);
        setContentView(navigation); root.requestApplyInsets();
        render();
        takeRequest();
        confirmShown();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); takeRequest(); confirmShown(); }
    /** A voice request to open 마이튜브 (app_open_screen mytube) is answered once this screen is really in front. */
    private void confirmShown() { root.post(() -> { if (!destroyed && hasWindowFocus()) AppNavigator.shown(getIntent(), dev.jun.gyeot.core.AppNavigation.MYTUBE); }); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) AppNavigator.shown(getIntent(), dev.jun.gyeot.core.AppNavigation.MYTUBE); }
    /** Folding or unfolding keeps this screen (and the video playing); the video's box follows the new width. */
    @Override public void onConfigurationChanged(android.content.res.Configuration config) { super.onConfigurationChanged(config); videoPane.requestLayout(); showPlayer(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("feed", feedId); }
    @Override protected void onResume() {
        super.onResume(); AppNavigator.inFront("mytube"); front = new java.lang.ref.WeakReference<>(this); if (chat != null) chat.attach();
        player.listen(playerChanged); if (player.active()) player.attach(screen); player.checkAccount(); showPlayer();
    }
    @Override protected void onPause() { if (!isFinishing()) AppNavigator.inFront("mytube"); super.onPause(); }
    @Override protected void onStop() { player.unlisten(playerChanged); player.detach(screen); if (chat != null) chat.detach(); super.onStop(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (chat != null) chat.permissionResult(code, results);
    }
    @Override protected void onDestroy() {
        destroyed = true; Runnable abort = cancel.getAndSet(null); if (abort != null) abort.run();
        for (Dialog d : new ArrayList<>(dialogs)) d.dismiss(); work.shutdownNow(); super.onDestroy();
    }
    @Override public void onBackPressed() { if (navigation != null && navigation.close()) return; if ("full".equals(viewMode)) { setView(lastView); return; } super.onBackPressed(); }
    /** 보기 바꾸기: 가로 꽉 · 나란히 are remembered; 전체화면 lasts until it is left (✕ on the video, or back). */
    private void setView(String mode) {
        viewMode = mode; if (!"full".equals(mode)) { lastView = mode; store.settings.edit().putString(VIEW, mode).apply(); }
        showPlayer();
    }
    /**
     * Lays the screen out for the view: returns whether the video takes the whole height it is given (전체화면, or a Short or
     * a YouTube page beside the list). Only what changed is laid out again.
     */
    private boolean arrange(boolean shown, boolean page) {
        boolean wideScreen = getResources().getConfiguration().screenWidthDp >= 600;
        boolean shorts = page && player.browseUrl().contains("/shorts/");
        if (!shown && "full".equals(viewMode)) viewMode = lastView;
        boolean full = shown && "full".equals(viewMode), side = shown && !full && wideScreen && ("side".equals(viewMode) || shorts), fill = full || side && page;
        sideToggle.setVisibility(wideScreen ? View.VISIBLE : View.GONE); sideToggle.setAlpha(side ? 1f : 0.6f);
        sideToggle.setContentDescription(side ? "가로 꽉 차게 보기 · 목록은 아래로" : "나란히 보기 · 왼쪽 영상, 오른쪽 목록");
        // Typing in the search box with the keyboard up (one column): the play buttons, the size bar and the composer step aside.
        boolean searching = searchBox.hasFocus();
        typing = shown && searching && !full && !side;
        // A Short has its own size buttons; the video's 작게 ↔ 크게 and size bar step aside for it.
        sizeToggle.setVisibility(shorts ? View.GONE : View.VISIBLE); shortsSizer.setVisibility(shorts && !full ? View.VISIBLE : View.GONE);
        if (side && shorts && body.getWidth() == 0) body.post(this::showPlayer);
        String key = full + "/" + side + "/" + fill + "/" + shorts + "/" + typing + "/" + searching + (side && shorts ? "/" + shortsSize + "/" + body.getWidth() + "x" + body.getHeight() : "");
        if (key.equals(arranged)) return fill;
        arranged = key;
        body.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        // Side by side: what plays and its buttons (안 볼래요 · 화질 · 캡처 · 저장 …) under the video, the list on its own beside it.
        ViewGroup home = side && !shorts ? videoPane : content;
        if (playerCard != null && playerCard.getParent() != home) {
            if (playerCard.getParent() instanceof ViewGroup was) was.removeView(playerCard);
            if (home == videoPane) { LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-1, -2); under.setMargins(ui.dp(16), ui.dp(4), ui.dp(16), 0); videoPane.addView(playerCard, under); }
            else content.addView(playerCard, 0);
        }
        videoPane.setLayoutParams(side && shorts ? new LinearLayout.LayoutParams(shortsWidth(), -1) : side ? new LinearLayout.LayoutParams(0, -1, 1.3f)
            : full ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, -2));
        stage.setLayoutParams(fill ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, -2));
        listPane.setLayoutParams(side ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1));
        listPane.setVisibility(full ? View.GONE : View.VISIBLE);
        controlsRow.setVisibility(full || typing ? View.GONE : View.VISIBLE); gripView.setVisibility(fill || typing || shorts ? View.GONE : View.VISIBLE); exitFull.setVisibility(full ? View.VISIBLE : View.GONE);
        // The search box in use: 마이튜브에게 말하기 steps aside, so it never covers the box; it comes back when the search ends.
        if (chat != null) chat.composer.setVisibility(full || searching ? View.GONE : View.VISIBLE);
        if (navigation != null) navigation.immersive(full);
        android.view.WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            if (full) { bars.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); bars.hide(WindowInsets.Type.systemBars()); }
            else bars.show(WindowInsets.Type.systemBars());
        }
        return fill;
    }

    // ── the player ───────────────────────────────────────────────────────────
    /**
     * The player in two parts: pinned on top, the video across the whole width (16:9, at most half the screen's height) with
     * its play buttons under it; at the top of the list, what plays (title, channel, why) and 화질 · 캡처 · 내용 정리 · 저장.
     */
    private void buildPlayer(LinearLayout content) {
        screen = new GeckoView(this);
        videoHeight = videoFit(getResources().getDisplayMetrics().widthPixels);
        videoPane.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l <= 0 || r - l == or - ol) return;
            int fit = videoFit(r - l); if (fit != videoHeight) { videoHeight = fit; main.post(this::showPlayer); }
        });
        stage = new android.widget.FrameLayout(this); stage.setBackgroundColor(0xff000000);
        stage.addView(screen, new android.widget.FrameLayout.LayoutParams(-1, videoHeight));
        ImageView leave = ui.quietIcon(R.drawable.ic_close, "전체화면 끝내기", () -> setView(lastView)); leave.setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
        leave.setBackground(ui.shape(0x66000000, PILL)); leave.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9)); leave.setVisibility(View.GONE); exitFull = leave;
        android.widget.FrameLayout.LayoutParams leaveAt = new android.widget.FrameLayout.LayoutParams(ui.dp(38), ui.dp(38), Gravity.TOP | Gravity.END); leaveAt.setMargins(0, ui.dp(10), ui.dp(10), 0);
        stage.addView(leave, leaveAt);
        videoPane.addView(stage, new LinearLayout.LayoutParams(-1, -2));
        viewMode = lastView = "side".equals(store.settings.getString(VIEW, "wide")) ? "side" : "wide";
        playerCard = ui.column(); playerCard.setVisibility(View.GONE); playerCard.setPadding(0, ui.dp(2), 0, ui.dp(8));
        nowTitle = ui.text("", 14, 650, TEXT); nowTitle.setMaxLines(2); nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowLine = ui.label("", 12, MUTED); nowLine.setMaxLines(3);
        playerCard.addView(nowTitle); playerCard.addView(nowLine);
        LinearLayout controls = ui.row(); controls.setGravity(Gravity.CENTER); controls.setPadding(ui.dp(6), ui.dp(5), ui.dp(6), ui.dp(1));
        LinearLayout capsule = ui.row(); capsule.setGravity(Gravity.CENTER_VERTICAL); capsule.setBackground(ui.shape(FILL, PILL)); capsule.setPadding(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2));
        previous = roundIcon(R.drawable.ic_previous, "이전 영상", () -> player.previous()); capsule.addView(previous, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        capsule.addView(seekButton("−10", "10초 뒤로", -10)); capsule.addView(seekButton("−5", "5초 뒤로", -5));
        toggle = roundIcon(R.drawable.ic_pause, "멈춤", () -> player.toggle()); toggle.setBackground(ui.shape(ACCENT, PILL)); toggle.setImageTintList(android.content.res.ColorStateList.valueOf(ON_ACCENT));
        LinearLayout.LayoutParams playAt = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)); playAt.leftMargin = ui.dp(2); playAt.rightMargin = ui.dp(2); capsule.addView(toggle, playAt);
        capsule.addView(seekButton("+5", "5초 앞으로", 5)); capsule.addView(seekButton("+10", "10초 앞으로", 10));
        next = roundIcon(R.drawable.ic_next, "다음 영상", () -> player.next()); capsule.addView(next, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        transport = capsule; controls.addView(capsule, new LinearLayout.LayoutParams(-2, -2));
        // 보기: 나란히 (a wide screen only) and 전체화면, one small group.
        LinearLayout views = ui.row(); views.setGravity(Gravity.CENTER_VERTICAL); views.setBackground(ui.shape(FILL, PILL)); views.setPadding(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2));
        // 작게 ↔ 크게: a smaller video, so more of the list shows (the size bar still sets any size in between).
        sizeToggle = ui.text("작게", 12.5f, 650, TEXT); sizeToggle.setGravity(Gravity.CENTER); sizeToggle.setMinWidth(ui.dp(40)); sizeToggle.setMinimumWidth(ui.dp(40)); sizeToggle.setHeight(ui.dp(34));
        sizeToggle.setPadding(ui.dp(6), 0, ui.dp(6), 0); sizeToggle.setOnClickListener(v -> toggleSmall()); Ui.pressable(sizeToggle, true);
        views.addView(sizeToggle, new LinearLayout.LayoutParams(-2, ui.dp(34)));
        // A Short's own size buttons, shown in place of 작게 ↔ 크게 while a Short plays.
        shortsSizer = ui.switcher(new String[]{"작게", "보통", "크게"}, store.settings.getInt(SHORTS_SIZE, 1), i -> {
            shortsSize = i; store.settings.edit().putInt(SHORTS_SIZE, i).apply(); arranged = null; showPlayer();
        });
        shortsSizer.setContentDescription("쇼츠 크기"); shortsSizer.setVisibility(View.GONE);
        views.addView(shortsSizer, new LinearLayout.LayoutParams(-2, -2));
        sideToggle = roundIcon(R.drawable.ic_layers, "나란히 보기 · 왼쪽 영상, 오른쪽 목록", () -> setView("side".equals(viewMode) ? "wide" : "side"));
        views.addView(sideToggle, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        views.addView(roundIcon(R.drawable.ic_expand, "전체화면 · 영상만", () -> setView("full")), new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        LinearLayout.LayoutParams viewsAt = new LinearLayout.LayoutParams(-2, -2); viewsAt.leftMargin = ui.dp(6); controls.addView(views, viewsAt);
        // On a YouTube page (a channel, Shorts, the sign-in): YouTube's own controls play it; this goes back to the list.
        backChip = withIcon(ui.smallChip("목록으로", () -> player.stop()), R.drawable.ic_back); backChip.setContentDescription("유튜브 화면 닫고 마이튜브 목록으로");
        controls.addView(backChip, new LinearLayout.LayoutParams(-2, -2));
        endDial = ui.dial(R.drawable.ic_stop, "끝내기", true, () -> player.stop()).compact();
        LinearLayout.LayoutParams endAt = new LinearLayout.LayoutParams(-2, -2); endAt.leftMargin = ui.dp(6); controls.addView(endDial, endAt);
        controlsRow = controls; videoPane.addView(controls);
        // 크기 조절: a small bar under the controls; dragged down the video grows, up it shrinks; a double tap fits it again.
        sizeRatio = store.settings.getFloat(VIDEO_SIZE, 0f); shortsSize = Math.max(0, Math.min(2, store.settings.getInt(SHORTS_SIZE, 1)));
        android.widget.FrameLayout grip = new android.widget.FrameLayout(this);
        View bar = new View(this); bar.setBackground(ui.shape(SEPARATOR, PILL));
        grip.addView(bar, new android.widget.FrameLayout.LayoutParams(ui.dp(36), ui.dp(4), Gravity.CENTER));
        grip.setContentDescription("영상 크기 조절 · 위아래로 끌기, 두 번 누르면 원래 크기"); grip.setTooltipText("끌어서 영상 크기 조절");
        android.view.GestureDetector taps = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(android.view.MotionEvent e) { sizeRatio = 0f; store.settings.edit().remove(VIDEO_SIZE).apply(); showPlayer(); return true; }
        });
        grip.setOnTouchListener((v, e) -> {
            taps.onTouchEvent(e);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN -> { dragFrom = e.getRawY(); dragHeight = screen.getHeight(); dragging = false; v.getParent().requestDisallowInterceptTouchEvent(true); }
                case android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.abs(e.getRawY() - dragFrom) < ui.dp(4)) return true;
                    dragging = true; int h = clampVideo(dragHeight + Math.round(e.getRawY() - dragFrom), screenHeight);
                    if (screen.getLayoutParams().height != h) { screen.getLayoutParams().height = h; screen.requestLayout(); }
                }
                case android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) { sizeRatio = screen.getLayoutParams().height / (float) screenHeight; store.settings.edit().putFloat(VIDEO_SIZE, sizeRatio).apply(); }
                    dragging = false;
                }
                default -> {}
            }
            return true;
        });
        gripView = grip; videoPane.addView(grip, new LinearLayout.LayoutParams(-1, ui.dp(14)));
        // The picture quality, as chosen and as it plays now.
        qualityChip = ui.smallChip("화질", this::chooseQuality);
        LinearLayout qualityRow = ui.row(); qualityRow.setGravity(Gravity.CENTER); qualityRow.setPadding(0, ui.dp(6), 0, 0);
        qualityRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        // 좋아요 is not offered here any more (the AI learns from 안 볼래요 and what is watched); kept off the row.
        heart = withIcon(ui.smallChip("좋아요", () -> player.like()), R.drawable.ic_heart); heart.setVisibility(View.GONE);
        skipChip = withIcon(ui.smallChip("안 볼래요", () -> player.skip()), R.drawable.ic_close); skipChip.setContentDescription("안 볼래요 · 다음 추천에서 빼고 다음 영상");
        qualityRow.addView(skipChip, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams qualityAt = new LinearLayout.LayoutParams(-2, -2); qualityAt.leftMargin = ui.dp(6); qualityRow.addView(qualityChip, qualityAt);
        // 배속: 0.5× to 2×, kept for the next videos.
        speedChip = withIcon(ui.smallChip("1×", this::chooseSpeed), R.drawable.ic_bolt);
        LinearLayout.LayoutParams speedAt = new LinearLayout.LayoutParams(-2, -2); speedAt.leftMargin = ui.dp(6); qualityRow.addView(speedChip, speedAt);
        // 캡처: the frame now, into 들퍼레/마이튜브 캡처. 영상 요약: what this video says, by the main chat's AI.
        captureChip = withIcon(ui.smallChip("캡처", () -> player.capture(this::toast)), R.drawable.ic_gallery);
        summaryChip = withAiMark(ui.smallChip("영상 요약", () -> { MyTube.Video v = player.current(); if (v != null) summarize(v); }));
        LinearLayout.LayoutParams chipGapLeft = new LinearLayout.LayoutParams(-2, -2); chipGapLeft.leftMargin = ui.dp(6);
        qualityRow.addView(captureChip, chipGapLeft);
        LinearLayout.LayoutParams chipGapLeft2 = new LinearLayout.LayoutParams(-2, -2); chipGapLeft2.leftMargin = ui.dp(6);
        qualityRow.addView(summaryChip, chipGapLeft2);
        // 저장: into the 저장 feed, the first feed chip, to watch again whenever (the AI never replaces what is there).
        saveChip = withIcon(ui.smallChip("저장", this::saveCurrent), R.drawable.ic_add); saveChip.setContentDescription("저장 · 저장 피드에 넣기");
        LinearLayout.LayoutParams chipGapLeft3 = new LinearLayout.LayoutParams(-2, -2); chipGapLeft3.leftMargin = ui.dp(6);
        qualityRow.addView(saveChip, chipGapLeft3);
        HorizontalScrollView qualityScroll = new HorizontalScrollView(this); qualityScroll.setHorizontalScrollBarEnabled(false); qualityScroll.addView(qualityRow);
        playerCard.addView(qualityScroll);
        content.addView(playerCard, 0);
    }
    /** A round icon button for the controls capsule. */
    private ImageView roundIcon(int icon, String name, Runnable action) {
        ImageView b = ui.quietIcon(icon, name, action); b.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); b.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT)); return b;
    }
    /** 앞뒤 이동: −10 · −5 · +5 · +10, small figures in the capsule. */
    private TextView seekButton(String label, String name, int seconds) {
        TextView b = ui.text(label, 12.5f, 650, TEXT); b.setGravity(Gravity.CENTER); b.setMinWidth(ui.dp(30)); b.setMinimumWidth(ui.dp(30)); b.setHeight(ui.dp(34));
        b.setPadding(ui.dp(3), 0, ui.dp(3), 0); b.setContentDescription(name); b.setTooltipText(name);
        b.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); player.seek(seconds); }); Ui.pressable(b, false);
        return b;
    }
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static String speedLabel(float r) { return (r == Math.rint(r) ? String.valueOf((int) r) : String.valueOf(r)) + "×"; }
    private void chooseSpeed() {
        String[] labels = new String[SPEEDS.length]; int at = 2;
        for (int i = 0; i < SPEEDS.length; i++) { labels[i] = speedLabel(SPEEDS[i]); if (Math.abs(SPEEDS[i] - player.rate()) < 0.01f) at = i; }
        Ui.Sheet sheet = ui.choices("배속", "다음 영상에도 같은 속도로 재생해요", labels, at, i -> player.rate(SPEEDS[i]));
        dialogs.add(sheet); sheet.setOnDismissListener(d -> dialogs.remove(sheet)); sheet.show();
    }
    /** The small size: three quarters of the fitted video, at most 40% of the screen, never under 160dp. */
    private int smallVideo(int screenHeight) { return Math.max(ui.dp(160), Math.min(videoHeight * 3 / 4, screenHeight * 40 / 100)); }
    private boolean small(int screenHeight) { return sizeRatio > 0f && Math.round(screenHeight * sizeRatio) <= smallVideo(screenHeight) + ui.dp(4); }
    /** 작게 ↔ 크게 (fitted), remembered like a dragged size. */
    private void toggleSmall() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        if (small(screenHeight)) { sizeRatio = 0f; store.settings.edit().remove(VIDEO_SIZE).apply(); }
        else { sizeRatio = smallVideo(screenHeight) / (float) screenHeight; store.settings.edit().putFloat(VIDEO_SIZE, sizeRatio).apply(); }
        showPlayer();
    }
    /** A dragged height kept between a small strip (120dp) and most of the screen (80%). */
    private int clampVideo(int height, int screenHeight) { return Math.max(ui.dp(120), Math.min(height, screenHeight * 80 / 100)); }
    /** The video's height for a width: 16:9, never under 160dp nor over half the screen. */
    private int videoFit(int width) { return Math.max(ui.dp(160), Math.min(width * 9 / 16, getResources().getDisplayMetrics().heightPixels / 2)); }
    /**
     * The keyboard shown or gone, read from the window (whatever way it makes room). Gone while the search box still has
     * the focus: the search is over, so the focus leaves it and 마이튜브에게 말하기 comes back.
     */
    private void keyboard(View v) {
        WindowInsets now = v.getRootWindowInsets(); if (now == null) return;
        boolean up = now.isVisible(WindowInsets.Type.ime()); int height = now.getInsets(WindowInsets.Type.ime()).bottom;
        if (up == keyboardUp && height == keyboardHeight) return;
        boolean closed = keyboardUp && !up; keyboardUp = up; keyboardHeight = height;
        if (closed && searchBox.hasFocus()) root.requestFocus();
        main.post(this::showPlayer);
    }
    /**
     * A Short beside the list: as wide as a 9:16 picture as tall as the room (보통), three quarters of it (작게) or a quarter
     * more (크게), never under 30% nor over 70% of the width, so no black band sits above or below it.
     */
    private int shortsWidth() {
        int width = body.getWidth() > 0 ? body.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int height = (body.getHeight() > 0 ? body.getHeight() : getResources().getDisplayMetrics().heightPixels) - ui.dp(56);
        int fit = Math.max(1, height) * 9 / 16, wanted = shortsSize == 0 ? fit * 3 / 4 : shortsSize == 2 ? fit * 5 / 4 : fit;
        return Math.max(width * 30 / 100, Math.min(width * 70 / 100, wanted));
    }
    private void showPlayer() {
        if (accountLine != null) { String state = accountState(); if (!state.contentEquals(accountLine.getText())) accountLine.setText(state); }
        if (destroyed) return;
        MyTube.Video now = player.current();
        boolean signingIn = !player.active() && player.signingIn(), browsing = !player.active() && !signingIn && player.browsing();
        playerCard.setVisibility(player.active() || signingIn || browsing ? View.VISIBLE : View.GONE);
        videoPane.setVisibility(playerCard.getVisibility());
        boolean fill = arrange(player.active() || signingIn || browsing, browsing);
        // A YouTube page (a channel, Shorts, the sign-in) needs room to read and use; a video fills its 16:9 box.
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        boolean shortsPage = browsing && player.browseUrl().contains("/shorts/");
        int height = shortsPage ? screenHeight * (shortsSize == 0 ? 48 : shortsSize == 2 ? 80 : 64) / 100 // a Short in one column: its own size
            : browsing ? Math.max(videoHeight, screenHeight * 64 / 100) : signingIn ? Math.max(videoHeight, screenHeight / 2) : videoHeight;
        if (sizeRatio > 0f && !shortsPage) height = clampVideo(Math.round(screenHeight * sizeRatio), screenHeight); // the size the user dragged it to
        boolean smallNow = small(screenHeight); sizeToggle.setText(smallNow ? "크게" : "작게");
        sizeToggle.setContentDescription(smallNow ? "영상 크게 · 원래 크기로" : "영상 작게 · 목록이 더 많이 보이게");
        if (fill) height = -1; // 전체화면, or a Short / YouTube page beside the list: the whole height it has
        else if (typing) {
            // The keyboard leaves little room: a strip of video (none when there is no room for one), the search box over the keyboard.
            int given = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
            int room = (keyboardUp && given > 0 ? given : screenHeight - keyboardHeight) - ui.dp(56) - ui.dp(120);
            height = room < ui.dp(72) ? 0 : Math.min(height, room);
        }
        if (!dragging && screen.getLayoutParams() != null && screen.getLayoutParams().height != height) { screen.getLayoutParams().height = height; screen.requestLayout(); }
        if (player.active() || signingIn || browsing) player.attach(screen);
        transport.setVisibility(player.active() ? View.VISIBLE : View.GONE); endDial.setVisibility(player.active() ? View.VISIBLE : View.GONE);
        backChip.setVisibility(player.active() ? View.GONE : View.VISIBLE);
        nowTitle.setText(now != null ? now.title() : signingIn ? "유튜브 로그인 · 로그인한 뒤 ‘목록으로’를 누르면 돌아와요" : browsing ? "유튜브 화면 · 채널·쇼츠·정렬을 유튜브 그대로" : "");
        nowLine.setText(now == null ? signingIn && !"유튜브 로그인".equals(player.status()) ? player.status() : browsing ? "다 보면 영상 아래 ‘목록으로’" : ""
            : now.channel() + (player.status().isEmpty() ? "" : " · " + player.status()) + (now.reason().isEmpty() ? "" : "\n" + now.reason()));
        boolean going = player.playing() || "불러오는 중".equals(player.status());
        toggle.setImageResource(going ? R.drawable.ic_pause : R.drawable.ic_play); toggle.setContentDescription(going ? "멈춤" : "재생");
        heart.setText(player.liked() ? "좋아요 취소" : "좋아요");
        android.graphics.drawable.Drawable mark = getDrawable(player.liked() ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        if (mark != null) { mark = mark.mutate(); mark.setTint(player.liked() ? ACCENT : TEXT); mark.setBounds(0, 0, ui.dp(16), ui.dp(16)); heart.setCompoundDrawablesRelative(mark, null, null, null); }
        previous.setEnabled(player.index() > 0); previous.setAlpha(player.index() > 0 ? 1f : 0.35f);
        next.setEnabled(player.active());
        speedChip.setText(speedLabel(player.rate())); speedChip.setContentDescription("배속 " + speedLabel(player.rate()) + ", 눌러서 바꾸기");
        for (View chip : new View[]{skipChip, speedChip}) chip.setVisibility(player.active() ? View.VISIBLE : View.GONE);
        String chosen = MyTube.QUALITY_LABELS[MyTube.qualityIndex(player.quality())], playingAt = player.qualityNow();
        qualityChip.setText("화질 · " + chosen + (playingAt.isEmpty() || playingAt.equals(chosen) ? "" : " · 지금 " + playingAt));
        qualityChip.setContentDescription("화질, " + chosen + (playingAt.isEmpty() ? "" : ", 지금 " + playingAt) + ", 눌러서 바꾸기");
        qualityChip.setVisibility(player.active() ? View.VISIBLE : View.GONE);
        captureChip.setVisibility(player.active() ? View.VISIBLE : View.GONE); summaryChip.setVisibility(player.active() ? View.VISIBLE : View.GONE);
        saveChip.setVisibility(player.active() ? View.VISIBLE : View.GONE);
        markPlaying();
    }

    // ── feeds and their videos ───────────────────────────────────────────────
    private MyTube.Feed feed() {
        List<MyTube.Feed> feeds = book.feeds();
        for (MyTube.Feed f : feeds) if (f.id().equals(feedId)) return f;
        return feeds.isEmpty() ? null : feeds.get(0);
    }
    private void render() {
        renderLiveChannels();
        migrate();
        List<MyTube.Feed> feeds = new ArrayList<>(book.feeds()); MyTube.Feed shown = searched != null ? null : feed();
        if (shown != null) { feedId = shown.id(); store.settings.edit().putString("mytubeFeed", feedId).apply(); }
        // 추천: the feeds the AI fills. 저장: the lists of what the user saved, 저장 first (where 저장 goes when no AI chose).
        List<MyTube.Feed> lists = new ArrayList<>(); for (MyTube.Feed f : feeds) if (f.saved()) lists.add(f);
        feeds.removeAll(lists);
        lists.sort(java.util.Comparator.comparing((MyTube.Feed f) -> !MyTube.SAVED_FEED_NAME.equals(f.name())));
        chips.removeAllViews(); savedChips.removeAllViews();
        rowLabel(chips, "추천");
        for (MyTube.Feed f : feeds) chips.addView(feedChip(f), chipAfter());
        Button add = ui.smallChip("＋ 피드", () -> editFeed(null)); chips.addView(add);
        rowLabel(savedChips, "저장");
        for (MyTube.Feed f : lists) savedChips.addView(feedChip(f), chipAfter());
        if (lists.isEmpty()) savedChips.addView(ui.label("영상에서 ‘저장’을 누르면 주제별 목록이 생겨요", 12, MUTED));
        list.removeAllViews();
        searchActions.setVisibility(searched != null && !searching ? View.VISIBLE : View.GONE);
        searchPlayAll.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        if (searched != null) {
            feedActions.setVisibility(View.GONE);
            String kindName = "shorts".equals(listKind) ? "쇼츠" : "live".equals(listKind) ? "라이브" : "동영상";
            header.setText(shownChannel != null ? "‘" + shownChannel.title() + "’ 채널 · " + kindName : "‘" + searched + "’ " + ("shorts".equals(listKind) ? "쇼츠" : "검색 결과"));
            about.setText(searching || found.isEmpty() ? "" : kindName + " " + found.size() + "개"
                + (byViews ? " · 조회수 순" + (shownChannel != null ? "(최근 것 중)" : "") : "")
                + ("shorts".equals(listKind) ? " · 누르면 유튜브 쇼츠 화면에서 넘겨 봐요" : " · 누르면 여기서부터 재생해요")); when.setText("");
            renderListTools();
            if (!"shorts".equals(listKind)) for (MyTube.Channel c : foundChannels) list.addView(channelRow(c));
            List<MyTube.Video> rows = shownList();
            for (int i = 0; i < rows.size(); i++) { int index = i; list.addView(videoRow(rows.get(i), () -> playFound(index))); }
            markPlaying(); tidyHeadings();
            return;
        }
        feedActions.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        if (shown == null) { header.setText("피드를 만들어 볼까요?"); about.setText("볼 것을 정해 두면 AI가 유튜브에서 골라 와요. 하나를 눌러 바로 시작하세요."); when.setText(""); starters(); tidyHeadings(); return; }
        boolean savedList = shown.saved(), liveList = savedList && book.liveWatch(shown.id());
        refreshChip.setText(liveList ? "라이브 확인" : "새로 고르기"); refreshChip.setVisibility(savedList && !liveList ? View.GONE : View.VISIBLE);
        editChip.setText(savedList ? "목록 편집" : "피드 편집");
        if (savedList) {
            // A 저장 목록: what was saved, as saved; with 라이브만 보기, its channels' broadcasts on air now.
            List<MyTube.Channel> kept = book.sources(shown.id());
            StringBuilder names = new StringBuilder(); for (MyTube.Channel c : kept) names.append(names.length() == 0 ? "" : " · ").append(c.title());
            header.setText(shown.name() + " · " + (liveList ? "라이브만" : "저장 목록"));
            about.setText(liveList ? (kept.isEmpty() ? "채널이 아직 없어요 · ‘목록 편집’이나 채널의 ‘저장’으로 넣어 주세요" : "채널 · " + names + "\n지금 방송 중인 라이브만 보여요")
                : "직접 저장한 영상 · AI가 바꾸지 않아요" + (kept.isEmpty() ? "" : "\n채널 · " + names));
            long checked = book.refreshedAt(shown.id());
            when.setText(liveList && checked > 0 ? "확인한 때 · " + ago(checked) : "");
            List<MyTube.Video> saved = book.videos(shown.id());
            if (saved.isEmpty() && !refreshing) { TextView empty = ui.label(liveList ? "지금 방송 중인 라이브가 없어요" : "영상을 보다가 ‘저장’을 누르면 여기에 모여요", 14, MUTED); empty.setPadding(0, ui.dp(12), 0, 0); list.addView(empty); }
            for (int i = 0; i < saved.size(); i++) { int index = i; list.addView(videoRow(saved.get(i), () -> playFrom(index))); }
            markPlaying(); tidyHeadings();
            return;
        }
        header.setText(shown.name() + " · " + shown.label() + (shown.kind() == MyTube.Kind.NEWS ? " (" + Math.max(1, shown.days()) + "일)" : ""));
        StringBuilder sources = new StringBuilder(); for (MyTube.Channel c : book.sources(shown.id())) sources.append(sources.length() == 0 ? "" : " · ").append(c.title());
        about.setText(shown.about() + (shown.kind() == MyTube.Kind.VIDEOS && !shown.channel().isBlank() ? "\n기준 채널 · " + shown.channel() : "")
            + (sources.length() == 0 ? "" : "\n저장한 채널 · " + sources + (shown.liveOnly() ? " (방송 중이면 맨 위)" : "")));
        long at = book.refreshedAt(shown.id());
        when.setText(at == 0 ? "아직 고르지 않았어요" : "고른 때 · " + ago(at));
        List<MyTube.Video> videos = book.videos(shown.id());
        if (videos.isEmpty() && !refreshing) { TextView empty = ui.label("‘새로 고르기’를 누르면 AI가 유튜브에서 골라 와요.", 14, MUTED); empty.setPadding(0, ui.dp(12), 0, 0); list.addView(empty); }
        for (int i = 0; i < videos.size(); i++) { int index = i; list.addView(videoRow(videos.get(i), () -> playFrom(index))); }
        markPlaying(); tidyHeadings();
    }
    /** A feed's or a list's chip: the slim capsule of ＋ 피드 beside it, the accent when shown; ● before a list that shows live broadcasts. */
    private Button feedChip(MyTube.Feed f) {
        Button chip = ui.smallChip((f.saved() && book.liveWatch(f.id()) ? "● " : "") + f.name(), () -> { searched = null; feedId = f.id(); render(); refreshIfStale(); });
        boolean on = searched == null && f.id().equals(feedId);
        chip.setBackground(ui.centeredCapsule(on ? ACCENT : FILL, ui.dp(28))); chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        chip.setTextColor(on ? ON_ACCENT : TEXT); chip.setSelected(on);
        return chip;
    }
    private LinearLayout.LayoutParams chipAfter() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(8); return p; }
    /** 추천 · 저장 at the start of their rows, the same width so both rows' chips line up. */
    private void rowLabel(LinearLayout row, String name) {
        TextView label = ui.text(name, 12, 650, MUTED); label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(36)));
    }
    /**
     * Older shapes into today's: shorter names (저장한 영상 → 저장, 내 취향 음악 → 내 음악), the older 저장 feed a 저장 목록, and the
     * 라이브 채널 saved before into a 저장 목록 ‘라이브’ that shows their broadcasts on air.
     */
    private void migrate() {
        try {
            for (MyTube.Feed f : book.feeds()) {
                String name = MyTube.RENAMED.getOrDefault(f.name(), f.name());
                boolean toList = MyTube.SAVED_FEED_NAME.equals(name) && !f.saved();
                if (toList) book.save(new MyTube.Feed(f.id(), name, MyTube.Kind.SAVED, "직접 저장한 영상", "", 0));
                else if (!name.equals(f.name())) book.save(new MyTube.Feed(f.id(), name, f.kind(), f.about(), f.channel(), f.days()));
            }
            List<MyTube.Channel> earlier = book.channels();
            if (!earlier.isEmpty()) {
                MyTube.Feed live = null; for (MyTube.Feed f : book.feeds()) if (f.saved() && "라이브".equals(f.name())) live = f;
                if (live == null) live = book.save(MyTube.savedList("라이브"));
                for (MyTube.Channel c : earlier) book.addSource(live.id(), c);
                book.liveWatch(live.id(), true);
                for (MyTube.Channel c : earlier) book.removeChannel(c.id());
            }
        } catch (Exception ignored) {}
    }
    /** Every channel kept in a 저장 목록 (for 라이브 by name). */
    private List<MyTube.Channel> savedChannels() {
        List<MyTube.Channel> out = new ArrayList<>(book.channels());
        for (MyTube.Feed f : book.feeds()) if (f.saved()) out.addAll(book.sources(f.id()));
        return out;
    }
    /** Lines with nothing to say take no room (a search has no 고른 때, a fresh one no count yet). */
    private void tidyHeadings() { for (TextView t : new TextView[]{about, when}) t.setVisibility(t.getText().length() == 0 ? View.GONE : View.VISIBLE); }
    private void starters() {
        LinearLayout group = ui.group();
        for (MyTube.Feed s : MyTube.starters()) ui.addRow(group, ui.listRow(R.drawable.ic_add, ACCENT, s.name(), s.kind().label + " · " + s.about(), ui.chevron(), () -> {
            if (s.kind() == MyTube.Kind.VIDEOS) { editFeed(s); return; } // needs the channel it is measured against
            try { MyTube.Feed saved = book.save(s); feedId = saved.id(); render(); refresh(); organizeSoon(); } catch (Exception e) { toast(e.getMessage()); }
        }), true);
        list.addView(group);
    }
    private View videoRow(MyTube.Video v, Runnable play) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(0, ui.dp(8), 0, ui.dp(8)); row.setTag(v.id());
        ImageView frame = new ImageView(this); frame.setScaleType(ImageView.ScaleType.CENTER_CROP); frame.setBackground(ui.shape(FILL, 12)); frame.setClipToOutline(true);
        frame.setContentDescription("영상 중간 장면");
        row.addView(frame, new LinearLayout.LayoutParams(ui.dp(132), ui.dp(74)));
        loadFrame(frame, v.id());
        LinearLayout text = ui.column(); text.setPadding(ui.dp(12), 0, 0, 0);
        TextView title = ui.text(v.title(), 14, 600, TEXT); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        // A broadcast on air: 실시간 in the accent where its length would be, with how many watch.
        TextView line = ui.label(v.live() ? join(v.channel(), "● 실시간 방송", v.views()) : v.shortForm() ? join(v.channel(), "쇼츠", v.views()) : join(v.channel(), v.published(), v.length(), byViews ? v.views() : ""), 12, v.live() ? ACCENT : MUTED);
        line.setSingleLine(true); line.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title); text.addView(line);
        if (!v.reason().isEmpty()) { TextView why = ui.label(v.reason(), 12, ACCENT); why.setMaxLines(2); text.addView(why); }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(view -> play.run()); Ui.pressable(row, false);
        // 길게 누르면 내용 정리 (what the video says, without playing it).
        row.setOnLongClickListener(view -> { summarize(v); return true; });
        row.setContentDescription(v.title() + ", " + line.getText() + (v.reason().isEmpty() ? "" : ", " + v.reason()) + ". 누르면 여기서부터 재생, 길게 누르면 내용 정리");
        return row;
    }
    /** The row of what plays now stands out. */
    private void markPlaying() {
        MyTube.Video now = player.current(); MyTube.Feed playingFeed = player.feed(); MyTube.Feed shown = feed();
        String shownId = searched != null ? MyTube.SEARCH_FEED : shown == null ? "" : shown.id();
        boolean here = playingFeed != null && playingFeed.id().equals(shownId);
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            row.setBackground(here && now != null && now.id().equals(row.getTag()) ? ui.shape(ACCENT_SOFT, 14) : null);
        }
    }
    private void playFrom(int index) {
        MyTube.Feed f = feed(); if (f == null) return;
        List<MyTube.Video> videos = book.videos(f.id());
        if (videos.isEmpty()) { refresh(); return; }
        player.play(f, videos, index); player.attach(screen); showPlayer();
    }
    private void playFound(int index) {
        List<MyTube.Video> rows = shownList(); if (searched == null || rows.isEmpty() || index >= rows.size()) return;
        // A Short opens in YouTube's own Shorts screen (vertical, swipe for the next).
        if (rows.get(index).shortForm()) { player.browse(MyTube.shortsPage(rows.get(index).id())); player.attach(screen); showPlayer(); scroll.smoothScrollTo(0, 0); return; }
        player.play(MyTube.searchFeed(searched), rows, index); player.attach(screen); showPlayer();
    }
    /** What the list shows: as found, or by views. */
    private List<MyTube.Video> shownList() { return byViews ? MyTube.byViews(found) : found; }
    /** The slim row over a search's or a channel's list: 모두 재생, 동영상|쇼츠(|라이브), 최신|조회수 (관련|조회수 for a search), 유튜브 화면, 닫기. */
    private void renderListTools() {
        listTools.removeAllViews();
        if (!found.isEmpty() && !"shorts".equals(listKind)) addTool(searchPlayAll);
        boolean channel = shownChannel != null;
        String[] kinds = channel ? new String[]{"동영상", "쇼츠", "라이브"} : new String[]{"동영상", "쇼츠"};
        int kindAt = "shorts".equals(listKind) ? 1 : "live".equals(listKind) ? 2 : 0;
        addTool(ui.switcher(kinds, kindAt, i -> { String next = i == 1 ? "shorts" : i == 2 ? "live" : "videos"; if (!next.equals(listKind)) { listKind = next; reload(); } }));
        addTool(ui.switcher(new String[]{channel ? "최신" : "관련", "조회수"}, byViews ? 1 : 0, i -> { boolean views = i == 1; if (views != byViews) { byViews = views; render(); } }));
        // A channel shown: 저장 into a 저장 목록 (with 라이브만 보기, its broadcasts on air show there).
        if (channel) { MyTube.Channel shownNow = shownChannel; addTool(withIcon(ui.smallChip("저장", () -> saveChannel(shownNow)), R.drawable.ic_add)); }
        // YouTube itself: the channel's own page (인기순, playlists, community …) or the full results (every kind).
        Button youtube = withIcon(ui.smallChip(channel ? "채널 화면" : "유튜브에서", () -> {
            player.browse(channel ? MyTube.channelPage(shownChannel.path()) : MyTube.resultsPage(searched)); player.attach(screen); showPlayer(); scroll.smoothScrollTo(0, 0);
        }), R.drawable.ic_globe);
        youtube.setContentDescription(channel ? "유튜브 채널 화면 열기 · 인기순·재생목록도 볼 수 있어요" : "유튜브 검색 화면 열기");
        addTool(youtube);
        Button close = withIcon(ui.smallChip("닫기", this::closeSearch), R.drawable.ic_close); close.setContentDescription(channel ? "채널 닫기" : "검색 닫기"); addTool(close);
    }
    private void addTool(View tool) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(6);
        if (tool.getParent() instanceof android.view.ViewGroup g) g.removeView(tool);
        listTools.addView(tool, p);
    }
    /** The list again for the kind chosen (a search's videos or Shorts; a channel's videos, Shorts or broadcasts). */
    private void reload() {
        String w = searched; MyTube.Channel c = shownChannel; String kind = listKind;
        if (w == null) return;
        int ticket = ++searchTicket; searching = true; found = List.of(); foundChannels = List.of(); render();
        status((c != null ? "‘" + c.title() + "’ " : "‘" + w + "’ ") + ("shorts".equals(kind) ? "쇼츠" : "live".equals(kind) ? "라이브" : "영상") + " 불러오는 중"); revealResults();
        work.execute(() -> {
            List<MyTube.Video> got; String why = null;
            try {
                if (c != null) got = "shorts".equals(kind) ? MyTubeClient.channelShorts(c.path(), c.title(), cancel) : "live".equals(kind) ? MyTubeClient.channelLive(c.path(), cancel) : MyTubeClient.channelVideos(c.path(), cancel);
                else got = "shorts".equals(kind) ? MyTubeClient.searchShorts(w, cancel) : MyTubeClient.search(w, cancel);
            } catch (Exception e) { got = List.of(); why = friendly(e, "불러오지 못했어요."); }
            List<MyTube.Channel> channels = c == null && "videos".equals(kind) && why == null ? MyTubeClient.channels(w, cancel) : List.of();
            List<MyTube.Video> result = got; String failure = why;
            main.post(() -> {
                if (destroyed || ticket != searchTicket) return;
                searching = false; found = result; foundChannels = channels;
                status(failure != null ? failure : result.isEmpty() ? "보여 줄 영상이 없어요." : "");
                render(); revealResults();
            });
        });
    }
    /** 직접 검색 (or a link: that video at once). */
    private void search(String words) { search(words, false, null); }
    /** playFirst: the best match plays at once (마이튜브 AI's play); done hears what came of it. */
    private void search(String words, boolean playFirst, java.util.function.Consumer<String> done) {
        String w = words == null ? "" : words.strip(); if (w.isEmpty()) { if (done != null) done.accept(""); return; }
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        searchBox.clearFocus();
        String linked = MyTube.linkedVideo(w);
        // A new search: no channel shown; Shorts stay Shorts if that is what was being looked at.
        shownChannel = null; if ("live".equals(listKind)) listKind = "videos"; boolean shorts = "shorts".equals(listKind) && linked.isEmpty();
        // At once: the results' place shows the search and that it is looking, scrolled into view.
        int ticket = ++searchTicket; searching = true;
        searched = linked.isEmpty() ? w : "영상 주소"; found = List.of(); foundChannels = List.of(); render();
        status(linked.isEmpty() ? "‘" + w + "’ 찾는 중" : "영상을 여는 중"); revealResults();
        work.execute(() -> {
            List<MyTube.Video> got; String why = null;
            try { got = !linked.isEmpty() ? List.of(MyTubeClient.video(linked, cancel)) : shorts ? MyTubeClient.searchShorts(w, cancel) : MyTubeClient.search(w, cancel); }
            catch (Exception e) { got = List.of(); why = friendly(e, "찾지 못했어요."); }
            // A channel's name searched: the channel too, above the videos.
            List<MyTube.Channel> channels = linked.isEmpty() && !shorts && why == null ? MyTubeClient.channels(w, cancel) : List.of();
            List<MyTube.Video> result = got; String failure = why;
            main.post(() -> {
                if (destroyed) return;
                if (ticket != searchTicket) { if (done != null) done.accept("검색을 바꿨어요"); return; } // closed, or a newer search
                searching = false;
                status(failure != null ? failure : result.isEmpty() ? "찾은 영상이 없어요. 다른 말로 찾아보세요." : "");
                searched = linked.isEmpty() ? w : "영상 주소"; found = result; foundChannels = channels; render(); revealResults();
                boolean play = (!linked.isEmpty() || playFirst) && !result.isEmpty();
                if (play) playFound(0);
                if (done != null) done.accept(failure != null ? failure : result.isEmpty() ? "‘" + w + "’로 찾은 영상이 없어요"
                    : play ? "재생: " + result.get(0).title() : "‘" + w + "’ 검색 결과 " + result.size() + "개");
            });
        });
    }
    /**
     * 저장: the playing video into the feed it fits, chosen by the main chat's AI from what each feed is for (or a new feed
     * it proposes, made at once); without an AI, the feed it plays from, else 저장. It goes to the top of that feed
     * and counts as liked there.
     */
    private void saveCurrent() { saveCurrent(null); }
    private void saveCurrent(java.util.function.Consumer<String> done) {
        MyTube.Video v = player.current(); if (v == null) { if (done != null) done.accept("재생 중인 영상이 없어요"); return; }
        status("어느 목록에 넣을지 고르는 중");
        work.execute(() -> {
            String said;
            try {
                // 저장: into the 저장 목록 it fits, chosen by 마이튜브 AI (or a new list it names); without an AI, the list 저장.
                List<MyTube.Feed> lists = new ArrayList<>(); for (MyTube.Feed f : book.feeds()) if (f.saved()) lists.add(f);
                MyTube.Feed target = null;
                try {
                    MyTube.SavePick pick = MyTube.savePick(MyTubeClient.askMyTube(store, MyTube.SAVE, MyTube.saveListInput(lists, book::pinned, v), cancel), lists);
                    if (pick != null && pick.feed() != null) target = pick.feed();
                    else if (pick != null && pick.made() != null) {
                        for (MyTube.Feed f : lists) if (f.name().equals(pick.made().name())) target = f;
                        if (target == null) target = book.save(pick.made());
                    }
                } catch (java.util.concurrent.CancellationException stop) { throw stop; } catch (Exception noAi) { /* the list 저장 */ }
                if (target == null) {
                    for (MyTube.Feed f : lists) if (MyTube.SAVED_FEED_NAME.equals(f.name())) target = f;
                    if (target == null) target = book.save(MyTube.savedList(MyTube.SAVED_FEED_NAME));
                }
                book.keep(target.id(), v.because("직접 저장"));
                // A broadcast kept: its channel too, so the list can show that channel's broadcasts (라이브만 보기).
                if (v.live()) { MyTube.Channel from = MyTubeClient.channelOf(v.id(), cancel); if (from != null) book.addSource(target.id(), from); }
                said = "‘" + target.name() + "’ 목록에 저장했어요";
            } catch (Exception e) { said = "저장하지 못했어요" + (e.getMessage() == null ? "." : " · " + e.getMessage()); }
            String told = said;
            main.post(() -> { if (destroyed) return; status(""); if (done != null) done.accept(told); else toast(told); render(); });
        });
    }
    /** The app's own words for a failure; a network error's ("Connection refused", a timeout) becomes a Korean one. */
    static String friendly(Exception e, String fallback) {
        String said = e.getMessage();
        if (said != null && said.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL)) return said;
        if (e instanceof java.io.IOException) return "유튜브에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 해 주세요.";
        return fallback;
    }
    // ── 피드 목록 자동 정리 ─────────────────────────────────────────────────
    /** A feed was added: the list is put in order by 마이튜브 AI shortly after (quietly; nothing happens without an AI). */
    private void organizeSoon() { main.removeCallbacks(organizer); main.postDelayed(organizer, 1500); }
    private final Runnable organizer = () -> { if (!destroyed && book.feeds().size() >= 2) organize(null); };
    /** 정리: the feeds in an order that makes sense (related ones together), unclear names made clear; never dropped or merged. */
    private void organize(java.util.function.Consumer<String> done) {
        List<MyTube.Feed> feeds = new ArrayList<>(); for (MyTube.Feed f : book.feeds()) if (!f.saved()) feeds.add(f); // the 추천 row only
        if (feeds.size() < 2) { if (done != null) done.accept("정리할 피드가 아직 적어요"); return; }
        work.execute(() -> {
            String said;
            try {
                MyTube.Order order = MyTube.order(MyTubeClient.askMyTube(store, MyTube.ORGANIZE, MyTube.organizeInput(feeds), cancel), feeds);
                book.arrange(order.ids(), order.renames());
                said = "피드 목록을 정리했어요" + (order.renames().isEmpty() ? "" : " · 이름 " + order.renames().size() + "개를 알아보기 쉽게 바꿨어요");
            } catch (Exception e) { said = done == null ? null : MyTubeActivity.friendly(e, "피드 목록을 정리하지 못했어요."); }
            String told = said;
            main.post(() -> { if (destroyed) return; render(); if (done != null) done.accept(told == null ? "" : told); });
        });
    }

    // ── 마이튜브 AI's actions (MyTubeChat; 음성 대화 through VoiceTargets.mytube) ──
    @Override public String input(String asked, List<String> conversation) {
        MyTube.Feed playing = player.feed(); MyTube.Feed shown = feed();
        List<MyTube.Video> onScreen = searched != null ? found : shown == null ? List.of() : book.videos(shown.id());
        return MyTube.agentInput(asked, book.feeds(), player.current(), playing == null ? "" : playing.name(), player.status(), onScreen, conversation, java.time.LocalDate.now().toString());
    }
    @Override public void act(org.json.JSONObject a, java.util.function.Consumer<String> done) {
        String what = a.optString("do");
        switch (what) {
            case "search" -> search(a.optString("query"), false, done);
            case "play" -> {
                String link = MyTube.linkedVideo(a.optString("link")), id = a.optString("video").strip();
                if (!link.isEmpty()) { search(a.optString("link"), true, done); return; }
                if (!id.isEmpty()) {
                    // A video the screen lists: it plays from there, with the rest after it.
                    if (searched != null) for (int i = 0; i < found.size(); i++) if (found.get(i).id().equals(id)) { int at = i; playFound(at); done.accept("재생: " + found.get(at).title()); return; }
                    for (MyTube.Feed f : book.feeds()) { List<MyTube.Video> vs = book.videos(f.id()); for (int i = 0; i < vs.size(); i++) if (vs.get(i).id().equals(id)) { feedId = f.id(); searched = null; render(); int at = i; playFrom(at); done.accept("재생: " + vs.get(at).title()); return; } }
                    search("https://www.youtube.com/watch?v=" + id, true, done); return;
                }
                search(a.optString("query"), true, done);
            }
            case "play_live" -> {
                String named = a.optString("channel").strip();
                MyTube.Channel saved = MyTube.channelNamed(savedChannels(), named);
                if (saved != null) { playLive(saved, done); return; }
                if (named.isEmpty()) { done.accept("어느 채널의 라이브인지 몰라요"); return; }
                work.execute(() -> {
                    List<MyTube.Channel> channels = MyTubeClient.channels(named, cancel);
                    main.post(() -> { if (destroyed) return; if (channels.isEmpty()) done.accept("‘" + named + "’ 채널을 찾지 못했어요"); else playLive(channels.get(0), done); });
                });
            }
            case "play_results" -> { if (searched == null || found.isEmpty()) { done.accept("보이는 검색 결과가 없어요"); return; } int at = Math.max(0, Math.min(found.size() - 1, a.optInt("from"))); playFound(at); done.accept("재생: " + found.get(at).title()); }
            case "play_feed", "refresh" -> {
                MyTube.Feed f = MyTube.feedNamed(book.feeds(), a.optString("feed")); if (f == null) { done.accept("‘" + a.optString("feed") + "’ 피드를 찾지 못했어요"); return; }
                searched = null; feedId = f.id(); render();
                if ("refresh".equals(what)) { refresh(); done.accept("‘" + f.name() + "’를 새로 고르는 중이에요"); return; }
                if (book.videos(f.id()).isEmpty() || System.currentTimeMillis() - book.refreshedAt(f.id()) > f.freshMs()) { playWhenReady = true; refresh(); done.accept("‘" + f.name() + "’를 골라서 틀게요"); }
                else { playFrom(0); done.accept("‘" + f.name() + "’ 재생"); }
            }
            case "feed_create" -> {
                MyTube.Feed made = MyTube.feedFrom(a); if (made == null) { done.accept("피드 이름이 없어서 만들지 않았어요"); return; }
                try {
                    MyTube.Feed saved = book.save(made); searched = null; feedId = saved.id(); render();
                    playWhenReady = a.optBoolean("play"); refresh(); organizeSoon();
                    done.accept("새 피드 ‘" + saved.name() + "’를 만들고 고르는 중이에요");
                } catch (Exception e) { done.accept("피드를 만들지 못했어요 · " + e.getMessage()); }
            }
            case "organize_feeds" -> organize(done);
            case "control" -> {
                if (!player.active()) { done.accept("재생 중인 영상이 없어요"); return; }
                switch (a.optString("action")) {
                    case "pause" -> { player.pause(); done.accept("멈췄어요"); }
                    case "resume" -> { player.resume(); done.accept("다시 재생해요"); }
                    case "next" -> { player.next(); done.accept("다음 영상"); }
                    case "previous" -> { player.previous(); done.accept("이전 영상"); }
                    case "stop" -> { player.stop(); done.accept("재생을 끝냈어요"); }
                    case "like" -> { if (!player.liked()) player.like(); done.accept("좋아요를 눌렀어요"); }
                    case "skip" -> { player.skip(); done.accept("다음 추천에서 빼고 넘겼어요"); }
                    default -> done.accept("");
                }
            }
            case "save" -> saveCurrent(done);
            case "capture" -> player.capture(done);
            case "summary" -> { MyTube.Video v = player.current(); if (v == null) { done.accept("재생 중인 영상이 없어요"); return; } summarize(v); done.accept("내용 정리를 열었어요"); }
            case "quality" -> { String code = a.optString("value"); int at = MyTube.qualityIndex(code); player.quality(MyTube.QUALITY_CODES[at]); done.accept("화질 · " + MyTube.QUALITY_LABELS[at]); }
            case "background" -> { boolean on = a.optBoolean("on"); store.settings.edit().putBoolean(MyTubePlayer.BACKGROUND, on).apply(); if (on && player.playing()) MyTubeService.start(this); if (!on) MyTubeService.stop(this); done.accept(on ? "화면이 꺼져도 계속 재생해요" : "화면을 떠나면 멈춰요"); }
            default -> done.accept("");
        }
    }
    /** 음성 대화's mytube_do: the actions in order, each outcome kept (and written in the conversation), then finished. */
    void runAll(List<org.json.JSONObject> list, List<String> outcomes, Runnable finished) {
        runFrom(list, 0, outcomes, finished);
    }
    private void runFrom(List<org.json.JSONObject> list, int at, List<String> outcomes, Runnable finished) {
        if (at >= list.size() || destroyed) { finished.run(); return; }
        act(list.get(at), outcome -> {
            if (outcome != null && !outcome.isBlank()) { outcomes.add(outcome); try { book.say("note", outcome, false); } catch (Exception ignored) {} chatChanged(); }
            runFrom(list, at + 1, outcomes, finished);
        });
    }
    /** 음성 대화: a spoken line of the conversation was written; the card shows it. */
    void chatChanged() { if (chat != null && !destroyed) chat.render(); }

    private void closeSearch() { searchTicket++; searching = false; searched = null; found = List.of(); foundChannels = List.of(); shownChannel = null; listKind = "videos"; byViews = false; searchBox.setText(""); status(""); render(); }
    /** Brings the results' heading to the top of the list (the search box stays a short scroll up). */
    private void revealResults() {
        scroll.post(() -> { if (!destroyed) scroll.smoothScrollTo(0, Math.max(0, header.getTop() - ui.dp(6))); });
    }
    /** A channel the search found: its picture, name, handle and subscribers; a tap shows its newest videos. */
    private View channelRow(MyTube.Channel c) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        ImageView face = new ImageView(this); face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable round = new android.graphics.drawable.GradientDrawable(); round.setShape(android.graphics.drawable.GradientDrawable.OVAL); round.setColor(FILL);
        face.setBackground(round); face.setClipToOutline(true); face.setContentDescription(c.title() + " 채널 사진");
        LinearLayout.LayoutParams faceAt = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); faceAt.leftMargin = ui.dp(38); faceAt.rightMargin = ui.dp(50);
        row.addView(face, faceAt);
        if (!c.picture().isEmpty()) loadPicture(face, c.picture());
        LinearLayout text = ui.column();
        TextView name = ui.text(c.title(), 15, 650, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        TextView line = ui.label(join(c.handle(), c.subscribers()), 12, MUTED); line.setSingleLine(true); line.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name); text.addView(line);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        // ● 라이브: its broadcast on air now; 저장: one tap from the top of 마이튜브 from now on.
        Button live = withIcon(ui.smallChip("라이브", () -> playLive(c, this::toast)), R.drawable.ic_play); live.setTextColor(ACCENT);
        boolean saved = savedChannels().stream().anyMatch(k -> k.path().equalsIgnoreCase(c.path()));
        Button keep = ui.smallChip(saved ? "저장됨" : "저장", () -> saveChannel(c));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(6);
        row.addView(live, new LinearLayout.LayoutParams(-2, -2)); row.addView(keep, gap);
        row.setOnClickListener(v -> openChannel(c)); Ui.pressable(row, false);
        row.setContentDescription(c.title() + " 채널, " + line.getText() + ". 누르면 이 채널의 최신 영상");
        return row;
    }
    /** 라이브 채널 above the feeds: ● and the name, a tap to its broadcast; a long press takes it off. */
    private void renderLiveChannels() {
        List<MyTube.Channel> saved = book.channels();
        liveChannels.removeAllViews(); liveScroll.setVisibility(saved.isEmpty() ? View.GONE : View.VISIBLE);
        for (MyTube.Channel c : saved) {
            Button chip = ui.smallChip("● " + c.title(), () -> playLive(c, this::toast)); chip.setTextColor(ACCENT);
            chip.setContentDescription(c.title() + " 라이브 바로 보기, 길게 누르면 목록에서 빼기");
            chip.setOnLongClickListener(v -> { try { book.removeChannel(c.id()); toast("‘" + c.title() + "’를 라이브 채널에서 뺐어요"); } catch (Exception e) { toast(e.getMessage()); } render(); return true; });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(8); liveChannels.addView(chip, p);
        }
    }
    /**
     * A channel's live broadcast on air now, played at once (its 라이브 tab, then its videos after it); when it is not on air,
     * its newest videos show instead and say so.
     */
    private void playLive(MyTube.Channel c, java.util.function.Consumer<String> done) {
        status("‘" + c.title() + "’ 라이브를 찾는 중");
        work.execute(() -> {
            List<MyTube.Video> got; String why = null;
            try { got = MyTubeClient.channelVideos(c.path(), cancel); } catch (Exception e) { got = List.of(); why = friendly(e, "채널을 읽지 못했어요."); }
            List<MyTube.Video> result = got; String failure = why;
            main.post(() -> {
                if (destroyed) return;
                if (failure != null) { status(failure); if (done != null) done.accept(failure); return; }
                searchTicket++; searching = false; shownChannel = c; listKind = "videos"; byViews = false; searched = c.title() + " 채널"; found = result; foundChannels = List.of(); render(); revealResults();
                int at = -1; for (int i = 0; i < result.size(); i++) if (result.get(i).live()) { at = i; break; }
                if (at >= 0) { status(""); playFound(at); if (done != null) done.accept("라이브 재생: " + result.get(at).title()); }
                else { String said = "‘" + c.title() + "’는 지금 라이브 방송 중이 아니에요 · 최신 영상을 보여 줄게요"; status(said); if (done != null) done.accept(said); }
            });
        });
    }
    /** A channel in place of the search: its 동영상 (then 쇼츠, 라이브 by the switch), newest first or by views, and 채널 화면 for YouTube's own page. */
    private void openChannel(MyTube.Channel c) {
        shownChannel = c; listKind = "videos"; byViews = false; searched = c.title() + " 채널";
        reload();
    }
    /** A picture from the web (a channel's), kept like the frames. */
    private void loadPicture(ImageView view, String url) {
        Bitmap kept = FRAMES.get(url); view.setTag(url);
        if (kept != null) { view.setImageBitmap(kept); return; }
        work.execute(() -> {
            try {
                java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                http.setConnectTimeout(10_000); http.setReadTimeout(15_000);
                Bitmap bitmap;
                try (java.io.InputStream in = http.getInputStream()) { bitmap = BitmapFactory.decodeStream(in); } finally { http.disconnect(); }
                if (bitmap == null) return;
                FRAMES.put(url, bitmap);
                main.post(() -> { if (!destroyed && url.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (Exception unavailable) { /* the gray circle stays */ }
        });
    }
    /**
     * 내용 정리: what the video says (its transcript, as the main chat reads a YouTube link), summed up by the main chat's AI in
     * a sheet; kept for the run, so it opens again at once. The video goes on playing meanwhile.
     */
    private void summarize(MyTube.Video v) {
        Ui.Sheet sheet = ui.selectionSheet("영상 요약", v.title());
        sheet.primary.setVisibility(View.GONE); dialogs.add(sheet); sheet.setOnDismissListener(d -> dialogs.remove(sheet));
        TextView body = ui.text("", BODY, 450, TEXT); body.setTextIsSelectable(true); body.setLineSpacing(ui.dp(3), 1);
        sheet.body.addView(body);
        String[] made = {SUMMARIES.get(v.id())};
        LinearLayout actions = ui.row(); actions.setPadding(0, ui.dp(12), 0, 0);
        Button copy = ui.chip("복사", () -> { if (made[0] != null) { getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("영상 요약", made[0])); toast("복사했어요"); } });
        Button transcript = ui.chip("대본 보기", () -> YouTubeTranscriptUi.show(this, MyTube.watch(v.id())));
        Button again = ui.chip("다시 정리", null);
        for (Button b : new Button[]{copy, transcript, again}) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(8); actions.addView(b, p); }
        sheet.body.addView(actions);
        AtomicReference<Runnable> stop = new AtomicReference<>();
        sheet.setOnDismissListener(d -> { dialogs.remove(sheet); Runnable abort = stop.getAndSet(null); if (abort != null) abort.run(); });
        Runnable[] make = new Runnable[1];
        make[0] = () -> {
            made[0] = null; body.setText("대본을 가져오는 중…"); body.setTextColor(MUTED);
            boolean[] closed = {false}; sheet.setOnCancelListener(d -> closed[0] = true);
            work.execute(() -> {
                String said, failure = null;
                try {
                    String block = YouTubeTranscriptClient.block(this, v.id(), () -> closed[0], step -> main.post(() -> { if (sheet.isShowing() && made[0] == null) body.setText(step); }), stop);
                    if (block == null || block.isBlank()) throw new java.io.IOException("이 영상의 대본을 가져오지 못했어요.");
                    main.post(() -> { if (sheet.isShowing() && made[0] == null) body.setText("AI가 정리하는 중…"); });
                    said = MyTubeClient.ask(store, MyTube.SUMMARY, MyTube.summaryInput(v, block), stop);
                } catch (Exception e) { said = null; failure = e.getMessage() == null ? "정리하지 못했어요." : e.getMessage(); }
                String answer = said, why = failure;
                main.post(() -> {
                    if (answer != null && !answer.isBlank()) { synchronized (SUMMARIES) { SUMMARIES.put(v.id(), answer); while (SUMMARIES.size() > 30) SUMMARIES.remove(SUMMARIES.keySet().iterator().next()); } }
                    if (!sheet.isShowing()) return;
                    if (answer == null || answer.isBlank()) { body.setText(why == null ? "정리하지 못했어요." : why); body.setTextColor(MUTED); return; }
                    made[0] = answer; body.setText(RichText.render(answer)); body.setTextColor(TEXT);
                });
            });
        };
        again.setOnClickListener(x -> make[0].run());
        if (made[0] != null) body.setText(RichText.render(made[0])); else make[0].run();
        sheet.show();
    }
    private void refreshIfStale() {
        MyTube.Feed f = feed(); if (f == null) return;
        long at = book.refreshedAt(f.id());
        if (at == 0 || System.currentTimeMillis() - at > f.freshMs()) refresh();
    }
    private void refresh() {
        MyTube.Feed f = feed(); if (f == null || refreshing) return;
        refreshing = true; status("고르기 시작");
        work.execute(() -> {
            String failure = null;
            try { MyTubeClient.refresh(this, store, f, step -> main.post(() -> status(step)), cancel); }
            catch (java.util.concurrent.CancellationException stopped) { failure = ""; }
            catch (Exception e) { failure = friendly(e, "고르지 못했어요."); }
            String why = failure;
            main.post(() -> {
                if (destroyed) return;
                refreshing = false; status(why == null ? "" : why);
                render();
                // Asked by voice to play this feed: it plays once its choice is ready.
                if (why == null && playWhenReady) { playWhenReady = false; playFrom(0); }
            });
        });
    }
    private boolean playWhenReady;
    private void status(String text) {
        statusLine.setText(text == null ? "" : text); statusLine.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        statusLine.shimmer(text != null && !text.isEmpty() && (text.endsWith("중") || refreshing)); // still working: the line's light sweeps
    }

    /** A voice request named a feed (or news / music): it shows, and plays (choosing first when it has nothing yet). */
    private void takeRequest() {
        String wanted = requested; requested = null;
        if (wanted == null) { refreshIfStale(); return; }
        MyTube.Feed match = null; String w = wanted.toLowerCase(java.util.Locale.ROOT);
        for (MyTube.Feed f : book.feeds()) if (f.name().toLowerCase(java.util.Locale.ROOT).contains(w) || w.contains(f.name().toLowerCase(java.util.Locale.ROOT))) { match = f; break; }
        if (match == null) {
            MyTube.Kind kind = w.contains("음악") || w.contains("노래") || w.contains("music") ? MyTube.Kind.MUSIC : w.contains("라이브") || w.contains("생방송") || w.contains("live") ? MyTube.Kind.LIVE : w.contains("뉴스") || w.contains("소식") || w.contains("news") ? MyTube.Kind.NEWS : null;
            if (kind != null) for (MyTube.Feed f : book.feeds()) if (f.kind() == kind) { match = f; break; }
            if (match == null && kind != null) for (MyTube.Feed s : MyTube.starters()) if (s.kind() == kind) { try { match = book.save(s); } catch (Exception ignored) {} break; }
        }
        if (match == null) { toast("‘" + wanted + "’ 피드를 찾지 못했어요."); return; }
        feedId = match.id(); render();
        long at = book.refreshedAt(match.id());
        if (at == 0 || System.currentTimeMillis() - at > match.freshMs() || book.videos(match.id()).isEmpty()) { playWhenReady = true; refresh(); }
        else playFrom(0);
    }

    // ── editing a feed ───────────────────────────────────────────────────────
    private void editFeed(MyTube.Feed editing) {
        boolean fresh = editing == null || editing.id().isEmpty();
        Ui.Sheet sheet = ui.selectionSheet(fresh ? "새 피드" : "피드 편집", "무엇을 보고 싶은지 적으면 AI가 유튜브에서 골라 와요 · 이 휴대폰에만 저장돼요");
        dialogs.add(sheet); sheet.setOnDismissListener(d -> dialogs.remove(sheet));
        MyTube.Kind[] kinds = java.util.Arrays.stream(MyTube.Kind.values()).filter(k -> k != MyTube.Kind.SAVED).toArray(MyTube.Kind[]::new);
        int[] kind = {editing == null ? 0 : Math.max(0, java.util.Arrays.asList(kinds).indexOf(editing.kind()))}; int[] days = {editing == null || editing.days() <= 0 ? 3 : editing.days()};
        // 개수: how many songs or videos the AI chooses each time (20 unless chosen).
        int[] size = {fresh ? MyTube.SIZE : book.size(editing.id())};
        String[] sizeLabels = new String[MyTube.SIZES.length]; int sizeAt = 0;
        for (int i = 0; i < MyTube.SIZES.length; i++) { sizeLabels[i] = MyTube.SIZES[i] + "개"; if (MyTube.SIZES[i] == size[0]) sizeAt = i; }
        LinearLayout sizeRow = ui.choiceRow("개수", "곡 수 · 영상 수", "새로 고를 때 AI가 이만큼 골라요 · 많을수록 오래 걸려요", sizeLabels, sizeAt, i -> size[0] = MyTube.SIZES[i]);
        EditText name = ui.input("이름 · 예: 최신 AI 뉴스", 1, 40); name.setText(editing == null ? "" : editing.name());
        EditText what = ui.input("원하는 것 · 예: AI 업계 새 소식, 원래 발표 위주", 3, 400); what.setText(editing == null ? "" : editing.about());
        EditText channel = ui.input("기준 채널 주소 (선택) · 예: @내채널 또는 youtube.com/@내채널", 1, 200); channel.setText(editing == null ? "" : editing.channel());
        LinearLayout group = ui.group();
        String[] kindLabels = new String[kinds.length]; for (int i = 0; i < kinds.length; i++) kindLabels[i] = kinds[i].label + " · " + kinds[i].about;
        TextView channelNote = ui.footer("영상 추천에 채널 주소를 넣으면 그 채널과 비슷한 다른 사람들의 영상을 골라요. 그 채널 영상은 빼요.");
        String[] dayLabels = {"1일", "3일", "7일", "30일"}; int[] dayValues = {1, 3, 7, 30};
        int dayAt = 1; for (int i = 0; i < dayValues.length; i++) if (dayValues[i] == days[0]) dayAt = i;
        LinearLayout dayRow = ui.choiceRow("기간", "최신 소식의 기간", "이 기간 안에 올라온 영상만 골라요", dayLabels, dayAt, i -> days[0] = dayValues[i]);
        Runnable shape = () -> { boolean videos = kinds[kind[0]] == MyTube.Kind.VIDEOS; channel.setVisibility(videos ? View.VISIBLE : View.GONE); channelNote.setVisibility(videos ? View.VISIBLE : View.GONE); dayRow.setVisibility(kinds[kind[0]] == MyTube.Kind.NEWS ? View.VISIBLE : View.GONE); sizeRow.setVisibility(kinds[kind[0]] == MyTube.Kind.LIVE ? View.GONE : View.VISIBLE); };
        ui.addRow(group, ui.choiceRow("종류", "피드 종류", "무엇을 고를지", kindLabels, kind[0], i -> { kind[0] = i; shape.run(); }), false);
        ui.addRow(group, dayRow, false);
        ui.addRow(group, sizeRow, false);
        sheet.body.addView(group);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(10);
        sheet.body.addView(name, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-1, -2); gap2.topMargin = ui.dp(8); sheet.body.addView(what, gap2);
        LinearLayout.LayoutParams gap3 = new LinearLayout.LayoutParams(-1, -2); gap3.topMargin = ui.dp(8); sheet.body.addView(channel, gap3);
        sheet.body.addView(channelNote);
        // 저장한 채널: the channels kept in this feed (a live feed shows their broadcasts first); ✕ takes one off.
        if (!fresh && !book.sources(editing.id()).isEmpty()) {
            sheet.body.addView(ui.sectionHeader("저장한 채널"));
            LinearLayout kept = ui.row(); HorizontalScrollView keptScroll = new HorizontalScrollView(this); keptScroll.setHorizontalScrollBarEnabled(false); keptScroll.addView(kept);
            for (MyTube.Channel c : book.sources(editing.id())) {
                Button chip = withIcon(ui.smallChip(c.title(), () -> {}), R.drawable.ic_close); chip.setContentDescription(c.title() + " 저장한 채널에서 빼기");
                chip.setOnClickListener(v -> { try { book.removeSource(editing.id(), c.path()); kept.removeView(chip); render(); toast("‘" + c.title() + "’를 뺐어요"); } catch (Exception e) { toast(e.getMessage()); } });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(6); kept.addView(chip, p);
            }
            sheet.body.addView(keptScroll);
        }
        shape.run();
        if (!fresh) sheet.body.addView(ui.actions(ui.chip("앞으로", () -> { try { book.move(editing.id(), true); render(); } catch (Exception ignored) {} }),
            ui.chip("뒤로", () -> { try { book.move(editing.id(), false); render(); } catch (Exception ignored) {} }),
            ui.chip("피드 지우기", () -> { try { book.remove(editing.id()); feedId = null; sheet.dismiss(); render(); } catch (Exception e) { toast(e.getMessage()); } })));
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            String title = name.getText().toString().strip(), want = what.getText().toString().strip(), from = channel.getText().toString().strip();
            MyTube.Kind chosen = kinds[kind[0]];
            if (title.isEmpty()) { name.setError("이름을 적어 주세요."); return; }
            if (want.isEmpty() && !(chosen == MyTube.Kind.VIDEOS && !from.isEmpty())) { what.setError("무엇을 보고 싶은지 적어 주세요."); return; }
            if (chosen == MyTube.Kind.VIDEOS && !from.isEmpty() && MyTube.channelPath(from).isEmpty()) { channel.setError("@채널이름이나 youtube.com/@채널이름 형식으로 적어 주세요."); return; }
            try {
                MyTube.Feed saved = book.save(new MyTube.Feed(fresh ? "" : editing.id(), title, chosen, want, chosen == MyTube.Kind.VIDEOS ? from : "", chosen == MyTube.Kind.NEWS ? days[0] : 0));
                book.size(saved.id(), size[0]);
                feedId = saved.id(); sheet.dismiss(); render(); refresh(); if (fresh) organizeSoon();
            } catch (Exception e) { toast(e.getMessage()); }
        });
        sheet.show();
    }

    /** 목록 편집: a 저장 목록's name, 라이브만 보기, its channels (added by address, ✕ to take one off), its place, and deleting it. */
    private void editList(MyTube.Feed editing) {
        Ui.Sheet sheet = ui.selectionSheet("목록 편집", "직접 저장한 영상과 채널 · AI가 바꾸지 않아요");
        dialogs.add(sheet); sheet.setOnDismissListener(d -> dialogs.remove(sheet));
        boolean[] live = {book.liveWatch(editing.id())};
        LinearLayout group = ui.group();
        ui.addRow(group, ui.switchRow("라이브만 보기", "이 목록의 채널이 지금 방송 중인 라이브만 보여요", live[0], on -> live[0] = on), false);
        sheet.body.addView(group);
        EditText name = ui.input("이름 · 예: 코인 라이브", 1, 40); name.setText(editing.name());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(10); sheet.body.addView(name, gap);
        sheet.body.addView(ui.sectionHeader("채널"));
        LinearLayout kept = ui.row(); HorizontalScrollView keptScroll = new HorizontalScrollView(this); keptScroll.setHorizontalScrollBarEnabled(false); keptScroll.addView(kept);
        Runnable[] showKept = new Runnable[1];
        showKept[0] = () -> {
            kept.removeAllViews();
            List<MyTube.Channel> channels = book.sources(editing.id());
            if (channels.isEmpty()) kept.addView(ui.label("채널이 없어요 · 아래에 주소를 넣거나 채널의 ‘저장’으로 넣어요", 12, MUTED));
            for (MyTube.Channel c : channels) {
                Button chip = withIcon(ui.smallChip(c.title(), () -> {}), R.drawable.ic_close); chip.setContentDescription(c.title() + " 이 목록에서 빼기");
                chip.setOnClickListener(v -> { try { book.removeSource(editing.id(), c.path()); showKept[0].run(); render(); } catch (Exception e) { toast(e.getMessage()); } });
                kept.addView(chip, chipAfter());
            }
        };
        showKept[0].run(); sheet.body.addView(keptScroll);
        LinearLayout adding = ui.row(); adding.setGravity(Gravity.CENTER_VERTICAL);
        EditText address = ui.input("채널 추가 · @채널이름 또는 주소", 1, 200);
        Button addChannel = ui.smallChip("추가", () -> {
            String path = MyTube.channelPath(address.getText().toString().strip());
            if (path.isEmpty()) { address.setError("@채널이름이나 youtube.com/@채널이름 형식으로 적어 주세요."); return; }
            String handle = path.startsWith("/") ? path.substring(1) : path;
            try { book.addSource(editing.id(), new MyTube.Channel("", handle, path, handle, "", "")); address.setText(""); showKept[0].run(); render(); } catch (Exception e) { toast(e.getMessage()); }
        });
        adding.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams addAt = new LinearLayout.LayoutParams(-2, -2); addAt.leftMargin = ui.dp(6); adding.addView(addChannel, addAt);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-1, -2); gap2.topMargin = ui.dp(8); sheet.body.addView(adding, gap2);
        sheet.body.addView(ui.actions(ui.chip("앞으로", () -> { try { book.move(editing.id(), true); render(); } catch (Exception ignored) {} }),
            ui.chip("뒤로", () -> { try { book.move(editing.id(), false); render(); } catch (Exception ignored) {} }),
            ui.chip("목록 지우기", () -> { try { book.remove(editing.id()); feedId = null; sheet.dismiss(); render(); } catch (Exception e) { toast(e.getMessage()); } })));
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            String title = name.getText().toString().strip();
            if (title.isEmpty()) { name.setError("이름을 적어 주세요."); return; }
            try {
                MyTube.Feed saved = book.save(new MyTube.Feed(editing.id(), title, MyTube.Kind.SAVED, editing.about(), "", 0));
                book.liveWatch(saved.id(), live[0]);
                feedId = saved.id(); sheet.dismiss(); render(); refresh();
            } catch (Exception e) { toast(e.getMessage()); }
        });
        sheet.show();
    }
    /** A channel into a 저장 목록 (its broadcasts show there with 라이브만 보기): one of the lists, or a new one by name. */
    private void saveChannel(MyTube.Channel c) {
        if (c == null) return;
        Ui.Sheet sheet = ui.selectionSheet("‘" + c.title() + "’ 저장", "어느 목록에 넣을까요? 목록의 ‘라이브만 보기’를 켜면 방송 중인 라이브만 떠요");
        dialogs.add(sheet); sheet.setOnDismissListener(d -> dialogs.remove(sheet));
        LinearLayout group = ui.group(); int count = 0;
        for (MyTube.Feed f : book.feeds()) {
            if (!f.saved()) continue;
            boolean in = book.sources(f.id()).stream().anyMatch(k -> k.path().equalsIgnoreCase(c.path()));
            ui.addRow(group, ui.listRow(in ? R.drawable.ic_check : R.drawable.ic_add, ACCENT, f.name(), in ? "들어 있어요 · 누르면 빼요" : book.liveWatch(f.id()) ? "라이브만 보기" : "저장 목록", null, () -> {
                try {
                    if (in) book.removeSource(f.id(), c.path()); else book.addSource(f.id(), c);
                    sheet.dismiss(); render(); toast(in ? "‘" + f.name() + "’에서 뺐어요" : "‘" + f.name() + "’에 넣었어요" + (book.liveWatch(f.id()) ? "" : " · ‘목록 편집’에서 라이브만 보기를 켤 수 있어요"));
                } catch (Exception e) { toast(e.getMessage()); }
            }), true);
            count++;
        }
        if (count > 0) sheet.body.addView(group);
        EditText name = ui.input("새 목록 이름 · 예: 코인 라이브", 1, 40);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(10); sheet.body.addView(name, gap);
        sheet.primary.setText("새 목록에 넣기");
        sheet.primary.setOnClickListener(v -> {
            String title = name.getText().toString().strip();
            if (title.isEmpty()) { name.setError("새 목록 이름을 적어 주세요."); return; }
            try {
                // A list made for channels shows their broadcasts on air (라이브만 보기 on; 목록 편집 turns it off).
                MyTube.Feed made = book.save(MyTube.savedList(title)); book.addSource(made.id(), c); book.liveWatch(made.id(), true);
                // The search stays: the next channel can go into the same list.
                sheet.dismiss(); render(); toast("‘" + title + "’ 목록을 만들어 넣었어요 · 라이브만 보기");
            } catch (Exception e) { toast(e.getMessage()); }
        });
        sheet.show();
    }

    // ── settings ─────────────────────────────────────────────────────────────
    /** The 유튜브 로그인 line: kept current as the player hears from YouTube, not only as this screen opens. */
    private TextView accountLine;
    private String accountState() {
        String account = player.account(); long at = player.accountAt(), ago = at <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - at) / 60_000;
        String when = ago < 0 ? "" : ago < 1 ? " · 방금 확인" : ago < 60 ? " · " + ago + "분 전 확인" : ago < 1_440 ? " · " + ago / 60 + "시간 전 확인" : " · " + ago / 1_440 + "일 전 확인";
        return player.signingIn() ? "로그인하는 중 · 마치면 여기에 표시돼요"
            : "in".equals(account) ? "✓ 로그인됨" + when : "out".equals(account) ? "로그인 안 됨" + when + " · 눌러서 로그인" : "확인 전 · 영상을 하나 틀면 로그인 여부를 확인해요";
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView t && text.contentEquals(t.getText())) return t;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { TextView found = findText(g.getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private void buildSettings(LinearLayout content) {
        content.addView(ui.sectionHeader("재생"));
        LinearLayout group = ui.group();
        // What YouTube itself last said of the sign-in (each played page asks), so it is plain whether it took.
        String state = accountState();
        LinearLayout login = ui.listRow(R.drawable.ic_person, ACCENT, "유튜브 로그인", state, ui.chevron(), () -> { player.signIn(); player.attach(screen); showPlayer(); });
        accountLine = findText(login, state);
        ui.addRow(group, login, true);
        ui.addRow(group, ui.choiceRow("화질", "마이튜브 화질", "영상에 그 화질이 없으면 그보다 낮은 것 중 가장 좋은 화질로 틀어요", MyTube.QUALITY_LABELS,
            MyTube.qualityIndex(player.quality()), i -> player.quality(MyTube.QUALITY_CODES[i])), true);
        ui.addRow(group, ui.switchRow("화면 꺼져도 계속 재생", "유튜브 프리미엄 회원용 · 음악 앱처럼 화면을 끄거나 다른 앱으로 가도 이어서 나와요", player.background(),
            value -> { store.settings.edit().putBoolean(MyTubePlayer.BACKGROUND, value).apply(); if (value && player.playing()) MyTubeService.start(this); if (!value) MyTubeService.stop(this); }), false);
        content.addView(group);
        content.addView(ui.footer("유튜브 로그인은 AI 브라우저와 같은 로그인을 써요. 프리미엄이면 광고 없이 재생돼요(프리미엄 라이트는 음악 영상에 광고가 나오고 백그라운드 재생이 안 될 수 있어요). "
            + "피드와 고른 목록, 좋아요는 이 휴대폰에만 저장돼요. 고르는 AI는 메인 대화의 AI예요. 재생은 유튜브 페이지 그대로라, 업로더가 막은 영상은 건너뛰어요."));
    }

    /** The quality sheet: the choices, and what this video has (or that it is chosen on the video itself). */
    private void chooseQuality() {
        List<String> has = player.levels();
        String note = !player.qualitySettable() ? "이 영상은 앱에서 화질을 바꿀 수 없어요 · 영상 화면의 ⚙에서 바꿔 주세요"
            : has.isEmpty() ? "영상에 그 화질이 없으면 그보다 낮은 것 중 가장 좋은 화질로 틀어요" : "이 영상: " + String.join(" · ", has);
        Ui.Sheet sheet = ui.choices("화질", note, MyTube.QUALITY_LABELS, MyTube.qualityIndex(player.quality()), i -> player.quality(MyTube.QUALITY_CODES[i]));
        dialogs.add(sheet); sheet.setOnDismissListener(d -> dialogs.remove(sheet));
        sheet.show();
    }

    // ── small things ─────────────────────────────────────────────────────────
    /** The AI 브라우저's AI mark (AiMark, drawn in code) before an AI action's label, in the label's color. */
    private Button withAiMark(Button chip) {
        AiMark mark = new AiMark(getResources().getDisplayMetrics().density); mark.setTint(TEXT); mark.setBounds(0, 0, ui.dp(20), ui.dp(20));
        chip.setCompoundDrawablesRelative(mark, null, null, null); chip.setCompoundDrawablePadding(ui.dp(4));
        return chip;
    }
    private Button withIcon(Button chip, int icon) {
        android.graphics.drawable.Drawable d = getDrawable(icon); if (d == null) return chip;
        d = d.mutate(); d.setTint(TEXT); d.setBounds(0, 0, ui.dp(16), ui.dp(16)); chip.setCompoundDrawablesRelative(d, null, null, null); chip.setCompoundDrawablePadding(ui.dp(6));
        return chip;
    }
    private void loadFrame(ImageView view, String id) {
        Bitmap kept = FRAMES.get(id); view.setTag(id);
        if (kept != null) { view.setImageBitmap(kept); return; }
        work.execute(() -> {
            try {
                java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL(MyTube.frame(id)).openConnection();
                http.setConnectTimeout(10_000); http.setReadTimeout(15_000);
                Bitmap bitmap;
                try (java.io.InputStream in = http.getInputStream()) { bitmap = BitmapFactory.decodeStream(in); } finally { http.disconnect(); }
                if (bitmap == null) return;
                FRAMES.put(id, bitmap);
                main.post(() -> { if (!destroyed && id.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (Exception unavailable) { /* the gray box stays */ }
        });
    }
    private static String join(String... parts) { StringBuilder out = new StringBuilder(); for (String p : parts) if (p != null && !p.isBlank()) out.append(out.length() == 0 ? "" : " · ").append(p); return out.toString(); }
    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        return minutes < 1 ? "방금" : minutes < 60 ? minutes + "분 전" : minutes < 1440 ? minutes / 60 + "시간 전" : minutes / 1440 + "일 전";
    }
    private void toast(String text) { if (text != null && !text.isBlank()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
