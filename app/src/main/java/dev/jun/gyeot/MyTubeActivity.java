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
public final class MyTubeActivity extends Activity {
    static final String PLAY = "mytube_play";
    /** A feed a voice request asked to play (its name, or news / music), taken when the screen shows. */
    static volatile String requested;
    static void ask(String feedOrKind) { requested = feedOrKind == null || feedOrKind.isBlank() ? null : feedOrKind.strip(); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final List<Dialog> dialogs = new ArrayList<>();
    private static final LruCache<String, Bitmap> FRAMES = new LruCache<>(80);
    private Ui ui; private MyTubeBook book; private MyTubePlayer player; private AppStore store;
    private MainNavigation navigation; private LinearLayout root, playerCard, list, chips; private GeckoView screen;
    private TextView nowTitle, nowLine, statusLine, header, about, when; private View feedActions;
    private Ui.Dial previous, toggle, next, heart; private Button qualityChip; private int videoHeight;
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
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = ui.column(); content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        content.addView(ui.largeTitle("마이튜브"));
        TextView lead = ui.label("알고리즘 대신 내가 정한 기준으로 AI가 골라요", 14, MUTED); lead.setPadding(0, ui.dp(2), 0, ui.dp(12)); content.addView(lead);
        buildPlayer(content);
        chips = ui.row(); HorizontalScrollView chipScroll = new HorizontalScrollView(this); chipScroll.setHorizontalScrollBarEnabled(false); chipScroll.addView(chips);
        LinearLayout.LayoutParams chipGap = new LinearLayout.LayoutParams(-1, -2); chipGap.topMargin = ui.dp(14); content.addView(chipScroll, chipGap);
        header = ui.title("", 20); header.setPadding(0, ui.dp(16), 0, 0); content.addView(header);
        about = ui.label("", 13, MUTED); about.setPadding(0, ui.dp(2), 0, 0); content.addView(about);
        when = ui.label("", 12, MUTED); when.setPadding(0, ui.dp(2), 0, 0); content.addView(when);
        feedActions = ui.actions(withIcon(ui.chip("새로 고르기", this::refresh), R.drawable.ic_refresh), withIcon(ui.chip("모두 재생", () -> playFrom(0)), R.drawable.ic_play), ui.chip("피드 편집", () -> { MyTube.Feed f = feed(); if (f != null) editFeed(f); }));
        content.addView(feedActions);
        statusLine = ui.label("", 13, ACCENT); statusLine.setPadding(0, ui.dp(8), 0, 0); statusLine.setVisibility(View.GONE); content.addView(statusLine);
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
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("feed", feedId); }
    @Override protected void onResume() {
        super.onResume(); AppNavigator.inFront("mytube");
        player.listen(playerChanged); if (player.active()) player.attach(screen); showPlayer();
    }
    @Override protected void onPause() { if (!isFinishing()) AppNavigator.inFront("mytube"); super.onPause(); }
    @Override protected void onStop() { player.unlisten(playerChanged); player.detach(screen); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; Runnable abort = cancel.getAndSet(null); if (abort != null) abort.run();
        for (Dialog d : new ArrayList<>(dialogs)) d.dismiss(); work.shutdownNow(); super.onDestroy();
    }
    @Override public void onBackPressed() { if (navigation != null && navigation.close()) return; super.onBackPressed(); }

    // ── the player ───────────────────────────────────────────────────────────
    private void buildPlayer(LinearLayout content) {
        playerCard = ui.card(); playerCard.setVisibility(View.GONE);
        screen = new GeckoView(this);
        int width = getResources().getDisplayMetrics().widthPixels - ui.dp(64);
        videoHeight = Math.max(ui.dp(180), Math.min(ui.dp(420), width * 9 / 16));
        playerCard.addView(screen, new LinearLayout.LayoutParams(-1, videoHeight));
        nowTitle = ui.text("", 15, 650, TEXT); nowTitle.setMaxLines(2); nowTitle.setEllipsize(TextUtils.TruncateAt.END); nowTitle.setPadding(0, ui.dp(10), 0, 0);
        nowLine = ui.label("", 12, MUTED); nowLine.setMaxLines(2);
        playerCard.addView(nowTitle); playerCard.addView(nowLine);
        LinearLayout controls = ui.row(); controls.setGravity(Gravity.CENTER); controls.setPadding(0, ui.dp(10), 0, 0);
        previous = ui.dial(R.drawable.ic_previous, "이전", false, () -> player.previous());
        toggle = ui.dial(R.drawable.ic_pause, "멈춤", false, () -> player.toggle());
        next = ui.dial(R.drawable.ic_next, "다음", false, () -> player.next());
        heart = ui.dial(R.drawable.ic_heart, "좋아요", false, () -> player.like());
        Ui.Dial skip = ui.dial(R.drawable.ic_close, "안 볼래요 · 다음 추천에서 빼요", false, () -> player.skip());
        Ui.Dial end = ui.dial(R.drawable.ic_stop, "끝내기", true, () -> player.stop());
        for (View v : new View[]{previous, toggle, next, heart, skip, end}) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.leftMargin = ui.dp(4); p.rightMargin = ui.dp(4); controls.addView(v, p); }
        playerCard.addView(controls);
        // The picture quality, as chosen and as it plays now.
        qualityChip = withIcon(ui.chip("화질", this::chooseQuality), R.drawable.ic_tune);
        LinearLayout qualityRow = ui.row(); qualityRow.setGravity(Gravity.CENTER); qualityRow.setPadding(0, ui.dp(8), 0, 0);
        qualityRow.addView(qualityChip, new LinearLayout.LayoutParams(-2, -2));
        playerCard.addView(qualityRow);
        content.addView(playerCard);
    }
    private void showPlayer() {
        if (destroyed) return;
        MyTube.Video now = player.current();
        boolean signingIn = !player.active() && "유튜브 로그인".equals(player.status());
        playerCard.setVisibility(player.active() || signingIn ? View.VISIBLE : View.GONE);
        // The sign-in page needs room to read and type; a video fills a 16:9 box.
        int height = signingIn ? Math.max(videoHeight, getResources().getDisplayMetrics().heightPixels * 3 / 5) : videoHeight;
        if (screen.getLayoutParams() != null && screen.getLayoutParams().height != height) { screen.getLayoutParams().height = height; screen.requestLayout(); }
        if (player.active() || signingIn) player.attach(screen);
        nowTitle.setText(now == null ? signingIn ? "유튜브 로그인 · 로그인한 뒤 ‘끝내기’를 누르면 목록으로 돌아와요" : "" : now.title());
        nowLine.setText(now == null ? "" : now.channel() + (player.status().isEmpty() ? "" : " · " + player.status()) + (now.reason().isEmpty() ? "" : "\n" + now.reason()));
        toggle.on(player.playing() || "불러오는 중".equals(player.status()) ? R.drawable.ic_pause : R.drawable.ic_play, player.playing() ? "멈춤" : "재생", false);
        heart.on(player.liked() ? R.drawable.ic_heart_filled : R.drawable.ic_heart, player.liked() ? "좋아요 취소" : "좋아요", player.liked());
        previous.setEnabled(player.index() > 0);
        next.setEnabled(player.active());
        String chosen = MyTube.QUALITY_LABELS[MyTube.qualityIndex(player.quality())], playingAt = player.qualityNow();
        qualityChip.setText("화질 · " + chosen + (playingAt.isEmpty() || playingAt.equals(chosen) ? "" : " · 지금 " + playingAt));
        qualityChip.setContentDescription("화질, " + chosen + (playingAt.isEmpty() ? "" : ", 지금 " + playingAt) + ", 눌러서 바꾸기");
        qualityChip.setVisibility(player.active() ? View.VISIBLE : View.GONE);
        markPlaying();
    }

    // ── feeds and their videos ───────────────────────────────────────────────
    private MyTube.Feed feed() {
        List<MyTube.Feed> feeds = book.feeds();
        for (MyTube.Feed f : feeds) if (f.id().equals(feedId)) return f;
        return feeds.isEmpty() ? null : feeds.get(0);
    }
    private void render() {
        List<MyTube.Feed> feeds = book.feeds(); MyTube.Feed shown = feed();
        if (shown != null) { feedId = shown.id(); store.settings.edit().putString("mytubeFeed", feedId).apply(); }
        chips.removeAllViews();
        for (MyTube.Feed f : feeds) {
            Button chip = ui.chip(f.name(), () -> { feedId = f.id(); render(); refreshIfStale(); });
            boolean on = f.id().equals(feedId);
            chip.setBackground(ui.shape(on ? ACCENT : FILL, PILL)); chip.setTextColor(on ? ON_ACCENT : TEXT); chip.setSelected(on);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(8); chips.addView(chip, p);
        }
        Button add = ui.chip("＋ 피드", () -> editFeed(null)); chips.addView(add);
        list.removeAllViews();
        feedActions.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        if (shown == null) { header.setText("피드를 만들어 볼까요?"); about.setText("볼 것을 정해 두면 AI가 유튜브에서 골라 와요. 하나를 눌러 바로 시작하세요."); when.setText(""); starters(); return; }
        header.setText(shown.name() + " · " + shown.kind().label + (shown.kind() == MyTube.Kind.NEWS ? " (" + Math.max(1, shown.days()) + "일)" : ""));
        about.setText(shown.about() + (shown.kind() == MyTube.Kind.VIDEOS && !shown.channel().isBlank() ? "\n기준 채널 · " + shown.channel() : ""));
        long at = book.refreshedAt(shown.id());
        when.setText(at == 0 ? "아직 고르지 않았어요" : "고른 때 · " + ago(at));
        List<MyTube.Video> videos = book.videos(shown.id());
        if (videos.isEmpty() && !refreshing) { TextView empty = ui.label("‘새로 고르기’를 누르면 AI가 유튜브에서 골라 와요.", 14, MUTED); empty.setPadding(0, ui.dp(12), 0, 0); list.addView(empty); }
        for (int i = 0; i < videos.size(); i++) list.addView(videoRow(videos.get(i), i));
        markPlaying();
    }
    private void starters() {
        LinearLayout group = ui.group();
        for (MyTube.Feed s : MyTube.starters()) ui.addRow(group, ui.listRow(R.drawable.ic_add, ACCENT, s.name(), s.kind().label + " · " + s.about(), ui.chevron(), () -> {
            if (s.kind() == MyTube.Kind.VIDEOS) { editFeed(s); return; } // needs the channel it is measured against
            try { MyTube.Feed saved = book.save(s); feedId = saved.id(); render(); refresh(); } catch (Exception e) { toast(e.getMessage()); }
        }), true);
        list.addView(group);
    }
    private View videoRow(MyTube.Video v, int index) {
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP); row.setPadding(0, ui.dp(8), 0, ui.dp(8)); row.setTag(v.id());
        ImageView frame = new ImageView(this); frame.setScaleType(ImageView.ScaleType.CENTER_CROP); frame.setBackground(ui.shape(FILL, 12)); frame.setClipToOutline(true);
        frame.setContentDescription("영상 중간 장면");
        row.addView(frame, new LinearLayout.LayoutParams(ui.dp(132), ui.dp(74)));
        loadFrame(frame, v.id());
        LinearLayout text = ui.column(); text.setPadding(ui.dp(12), 0, 0, 0);
        TextView title = ui.text(v.title(), 14, 600, TEXT); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        TextView line = ui.label(join(v.channel(), v.published(), v.length()), 12, MUTED); line.setSingleLine(true); line.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title); text.addView(line);
        if (!v.reason().isEmpty()) { TextView why = ui.label(v.reason(), 12, ACCENT); why.setMaxLines(2); text.addView(why); }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(view -> playFrom(index)); Ui.pressable(row, false);
        row.setContentDescription(v.title() + ", " + line.getText() + (v.reason().isEmpty() ? "" : ", " + v.reason()) + ". 누르면 여기서부터 재생");
        return row;
    }
    /** The row of what plays now stands out. */
    private void markPlaying() {
        MyTube.Video now = player.current(); MyTube.Feed playingFeed = player.feed(); MyTube.Feed shown = feed();
        boolean here = playingFeed != null && shown != null && playingFeed.id().equals(shown.id());
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
            catch (Exception e) { failure = e.getMessage() == null ? "고르지 못했어요." : e.getMessage(); }
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
    private void status(String text) { statusLine.setText(text == null ? "" : text); statusLine.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE); }

    /** A voice request named a feed (or news / music): it shows, and plays (choosing first when it has nothing yet). */
    private void takeRequest() {
        String wanted = requested; requested = null;
        if (wanted == null) { refreshIfStale(); return; }
        MyTube.Feed match = null; String w = wanted.toLowerCase(java.util.Locale.ROOT);
        for (MyTube.Feed f : book.feeds()) if (f.name().toLowerCase(java.util.Locale.ROOT).contains(w) || w.contains(f.name().toLowerCase(java.util.Locale.ROOT))) { match = f; break; }
        if (match == null) {
            MyTube.Kind kind = w.contains("음악") || w.contains("노래") || w.contains("music") ? MyTube.Kind.MUSIC : w.contains("뉴스") || w.contains("소식") || w.contains("news") ? MyTube.Kind.NEWS : null;
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
        MyTube.Kind[] kinds = MyTube.Kind.values(); int[] kind = {editing == null ? 0 : editing.kind().ordinal()}; int[] days = {editing == null || editing.days() <= 0 ? 3 : editing.days()};
        EditText name = ui.input("이름 · 예: 최신 AI 뉴스", 1, 40); name.setText(editing == null ? "" : editing.name());
        EditText what = ui.input("원하는 것 · 예: AI 업계 새 소식, 원래 발표 위주", 3, 400); what.setText(editing == null ? "" : editing.about());
        EditText channel = ui.input("기준 채널 주소 (선택) · 예: @내채널 또는 youtube.com/@내채널", 1, 200); channel.setText(editing == null ? "" : editing.channel());
        LinearLayout group = ui.group();
        String[] kindLabels = new String[kinds.length]; for (int i = 0; i < kinds.length; i++) kindLabels[i] = kinds[i].label + " · " + kinds[i].about;
        TextView channelNote = ui.footer("영상 추천에 채널 주소를 넣으면 그 채널과 비슷한 다른 사람들의 영상을 골라요. 그 채널 영상은 빼요.");
        String[] dayLabels = {"1일", "3일", "7일", "30일"}; int[] dayValues = {1, 3, 7, 30};
        int dayAt = 1; for (int i = 0; i < dayValues.length; i++) if (dayValues[i] == days[0]) dayAt = i;
        LinearLayout dayRow = ui.choiceRow("기간", "최신 소식의 기간", "이 기간 안에 올라온 영상만 골라요", dayLabels, dayAt, i -> days[0] = dayValues[i]);
        Runnable shape = () -> { boolean videos = kinds[kind[0]] == MyTube.Kind.VIDEOS; channel.setVisibility(videos ? View.VISIBLE : View.GONE); channelNote.setVisibility(videos ? View.VISIBLE : View.GONE); dayRow.setVisibility(kinds[kind[0]] == MyTube.Kind.NEWS ? View.VISIBLE : View.GONE); };
        ui.addRow(group, ui.choiceRow("종류", "피드 종류", "무엇을 고를지", kindLabels, kind[0], i -> { kind[0] = i; shape.run(); }), false);
        ui.addRow(group, dayRow, false);
        sheet.body.addView(group);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(10);
        sheet.body.addView(name, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-1, -2); gap2.topMargin = ui.dp(8); sheet.body.addView(what, gap2);
        LinearLayout.LayoutParams gap3 = new LinearLayout.LayoutParams(-1, -2); gap3.topMargin = ui.dp(8); sheet.body.addView(channel, gap3);
        sheet.body.addView(channelNote);
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
                feedId = saved.id(); sheet.dismiss(); render(); refresh();
            } catch (Exception e) { toast(e.getMessage()); }
        });
        sheet.show();
    }

    // ── settings ─────────────────────────────────────────────────────────────
    private void buildSettings(LinearLayout content) {
        content.addView(ui.sectionHeader("재생"));
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_person, ACCENT, "유튜브 로그인", "AI 브라우저와 같은 로그인을 써요 · 프리미엄이면 광고 없이 재생돼요", ui.chevron(), () -> { player.signIn(); player.attach(screen); showPlayer(); }), true);
        ui.addRow(group, ui.choiceRow("화질", "마이튜브 화질", "영상에 그 화질이 없으면 그보다 낮은 것 중 가장 좋은 화질로 틀어요", MyTube.QUALITY_LABELS,
            MyTube.qualityIndex(player.quality()), i -> player.quality(MyTube.QUALITY_CODES[i])), true);
        ui.addRow(group, ui.switchRow("화면 꺼져도 계속 재생", "유튜브 프리미엄 회원용 · 음악 앱처럼 화면을 끄거나 다른 앱으로 가도 이어서 나와요", player.background(),
            value -> { store.settings.edit().putBoolean(MyTubePlayer.BACKGROUND, value).apply(); if (value && player.playing()) MyTubeService.start(this); if (!value) MyTubeService.stop(this); }), false);
        content.addView(group);
        content.addView(ui.footer("피드와 고른 목록, 좋아요는 이 휴대폰에만 저장돼요. 고르는 AI는 메인 대화의 AI예요. 재생은 유튜브 페이지 그대로라, 업로더가 막은 영상은 건너뛰어요."));
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
