package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import dev.jun.gyeot.core.VideoJobs;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** A local, letterboxed video viewer. Controls stay in bounds; every gesture also has a labelled button. */
final class VideoPlayerDialog extends Dialog {
    interface Thumbnails { void load(ImageView target, JSONObject job, int size); }
    // The darkroom palette shared with the photo viewer: warm near-blacks, not cool grays.
    private static final int WHITE = 0xfff5f3ee, SECONDARY = 0xffb9b5ac, SURFACE = 0xff1d1c1a;
    private final Activity activity; private final Ui ui; private final List<JSONObject> items;
    private final Thumbnails thumbnails; private final Consumer<JSONObject> share, info;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout stage; private final LinearLayout dock, strip;
    private final HorizontalScrollView film;
    private final TextView counter, caption, metadata, elapsed, total, notice;
    private final ImageView play, previous, next, sound, expand;
    private final SeekBar seek;
    private final GestureDetector gestures;
    private VideoView video; private MediaPlayer media;
    private int index, position; private boolean ready, muted, scrubbing, wantsPlay = true, background, controls = true;
    private boolean rendered;
    private String requestedVideo = "";
    private BooleanSupplier playbackAllowed;
    private Consumer<String> playbackResult;
    private final Runnable tick = new Runnable() { @Override public void run() { if(video!=null&&!rootAllowed()){blocked();return;}updatePosition(); confirmPlayback(); if (isShowing() && !background) main.postDelayed(this, 250); } };

    VideoPlayerDialog(Activity activity, Ui ui, List<JSONObject> items, int index, Thumbnails thumbnails, Consumer<JSONObject> share, Consumer<JSONObject> info, Bundle saved) {
        super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.activity = activity; this.ui = ui; this.items = List.copyOf(items); this.index = Math.max(0, Math.min(index, items.size() - 1));
        this.thumbnails = thumbnails; this.share = share; this.info = info;
        if (saved != null) { position = saved.getInt("position"); muted = saved.getBoolean("muted"); wantsPlay = saved.getBoolean("playing", true); }
        LinearLayout root = ui.column(); root.setBackgroundColor(0xff0b0a09);
        root.setOnApplyWindowInsetsListener((v, insets) -> { Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()); root.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets; });
        LinearLayout top = ui.row(); top.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        top.addView(icon(R.drawable.ic_close, "영상 닫기", this::dismiss), lp(48, 48));
        counter = ui.text("", 14, 600, WHITE); counter.setGravity(Gravity.CENTER);
        top.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon(R.drawable.ic_share, "영상 공유", () -> { pauseForAction(); share.accept(current()); }), lp(48, 48));
        top.addView(icon(R.drawable.ic_info, "프롬프트와 영상 정보", () -> { pauseForAction(); info.accept(current()); }), lp(48, 48));
        root.addView(top);
        stage = new FrameLayout(activity); root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { togglePlaying(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a != null && Math.abs(b.getX() - a.getX()) > ui.dp(64) && Math.abs(vx) > Math.abs(vy)) { move(b.getX() < a.getX() ? 1 : -1); return true; }
                return false;
            }
        });
        stage.setOnTouchListener((v, event) -> gestures.onTouchEvent(event)); stage.setContentDescription("영상 화면. 좌우로 밀어 이전·다음 영상, 한 번 눌러 조작부 숨기기, 두 번 눌러 재생·일시정지");
        dock = ui.column(); dock.setBackground(ui.shape(SURFACE, 24)); dock.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(8));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2); dp.setMargins(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12)); root.addView(dock, dp);
        caption = ui.text("", 15, 600, WHITE); caption.setMaxLines(1); caption.setEllipsize(TextUtils.TruncateAt.END); dock.addView(caption);
        metadata = ui.text("", 12, 450, SECONDARY); metadata.setPadding(0, ui.dp(6), 0, 0); dock.addView(metadata);
        seek = new SeekBar(activity); seek.setMax(1000); seek.setProgressTintList(ColorStateList.valueOf(WHITE)); seek.setThumbTintList(ColorStateList.valueOf(WHITE)); seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff55555c)); seek.setContentDescription("영상 재생 위치");
        dock.addView(seek, new LinearLayout.LayoutParams(-1, ui.dp(44)));
        LinearLayout times = ui.row(); elapsed = ui.text("0:00", 11, 500, SECONDARY); total = ui.text("0:00", 11, 500, SECONDARY); total.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1)); times.addView(total); dock.addView(times);
        LinearLayout buttons = ui.row(); buttons.setGravity(Gravity.CENTER); buttons.setPadding(0, ui.dp(2), 0, ui.dp(4));
        sound = icon(R.drawable.ic_sound, "소리 끄기", () -> { muted = !muted; applyVolume(); });
        previous = icon(R.drawable.ic_previous, "이전 영상", () -> move(-1));
        play = icon(R.drawable.ic_pause, "일시정지", this::togglePlaying); play.setBackground(ui.shape(0xff34332f, 99));
        next = icon(R.drawable.ic_next, "다음 영상", () -> move(1));
        expand = icon(R.drawable.ic_expand, "조작부 숨기고 크게 보기", this::toggleControls);
        for (ImageView button : new ImageView[]{sound, previous, play, next, expand}) buttons.addView(button, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        dock.addView(buttons);
        film = new HorizontalScrollView(activity); film.setHorizontalScrollBarEnabled(false); strip = ui.row(); strip.setGravity(Gravity.CENTER); film.addView(strip); dock.addView(film);
        boolean compact = activity.getResources().getConfiguration().screenHeightDp < 500;
        if (compact) { caption.setVisibility(View.GONE); metadata.setVisibility(View.GONE); film.setVisibility(View.GONE); }
        notice = ui.text("", 14, 500, WHITE); notice.setGravity(Gravity.CENTER); notice.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user && ready) elapsed.setText(time((long) duration() * value / 1000)); }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (ready) { position = duration() * bar.getProgress() / 1000; video.seekTo(position); } scrubbing = false; }
        });
        setContentView(root);
    }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(ui.dp(w), ui.dp(h)); }
    private ImageView icon(int resource, String label, Runnable action) { return ui.iconButton(resource, 0, WHITE, label, action); }
    private JSONObject current() { return items.get(index); }
    void awaitPlayback(String id, BooleanSupplier allowed, Consumer<String> result) { requestedVideo = id; playbackAllowed = allowed; playbackResult = result; }
    private void playbackFinished(String error) {
        Consumer<String> result = playbackResult; playbackResult = null; playbackAllowed = null;
        if (result != null) result.accept(error);
    }
    private boolean rootAllowed(){return SharedOutputFolder.canAccess(activity,Uri.parse(current().optString("uri")));}
    private void blocked(){playbackFinished("지정 들퍼레 폴더 밖의 영상은 재생하지 않아요.");wantsPlay=false;ready=false;release();stage.removeAllViews();notice.setText("이 영상은 내용 접근 범위 밖에 있어요\n옛 Pictures/Movies 파일은 자동으로 읽거나 이전하지 않아요.\n새 들퍼레 폴더의 파일인지 확인해 주세요.");notice.setOnClickListener(null);stage.addView(notice,new FrameLayout.LayoutParams(-1,-1));seek.setEnabled(false);updatePlay();}
    private boolean canStart() {
        if(!rootAllowed()){blocked();return false;}
        if (playbackResult == null) return true;
        if (!requestedVideo.equals(current().optString("id"))) { playbackFinished("재생 확인 전에 다른 영상으로 이동했어요."); return true; }
        if (playbackAllowed.getAsBoolean()) return true;
        wantsPlay = false; if (ready && video != null) video.pause(); updatePlay();
        playbackFinished("취소되었거나 시간이 지나 영상 재생 요청을 중지했어요."); return false;
    }
    private void confirmPlayback() {
        if (playbackResult != null && canStart() && rendered && ready && video != null && video.isPlaying() && !background
            && isShowing() && getWindow() != null && getWindow().getDecorView().hasWindowFocus()) playbackFinished("");
    }
    @Override public void show() { super.show(); if (getWindow() != null) { getWindow().setLayout(-1, -1); getWindow().setDecorFitsSystemWindows(false); } open(); main.post(tick); }
    private void open() {
        release(); ready = false; rendered = false; scrubbing = false; stage.removeAllViews();
        if (!canStart()) return;
        JSONObject job = current();
        counter.setText((index + 1) + " / " + items.size()); caption.setText(job.optString("prompt"));
        metadata.setText(VideoJobs.resolution(job) + " · " + job.optInt("duration") + "초 · 반복 재생");
        previous.setEnabled(index > 0); previous.setAlpha(index > 0 ? 1 : .3f); next.setEnabled(index < items.size() - 1); next.setAlpha(index < items.size() - 1 ? 1 : .3f);
        seek.setEnabled(false); seek.setProgress(0); elapsed.setText("0:00"); total.setText(time(job.optInt("duration") * 1000L));
        VideoView opened = new VideoView(activity); video = opened;
        opened.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build());
        stage.addView(opened, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // The non-clickable video lets the stage own taps/swipes; controls below retain normal accessibility actions.
        opened.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
        ProgressBar loading = new ProgressBar(activity); loading.setIndeterminateTintList(ColorStateList.valueOf(WHITE)); stage.addView(loading, new FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.CENTER));
        opened.setOnPreparedListener(player -> {
            if (video != opened || !isShowing()) return;
            media = player; ready = true; stage.removeView(loading); player.setLooping(true); applyVolume(); seek.setEnabled(true);
            total.setText(time(duration())); if (position > 0) opened.seekTo(position);
            if (canStart() && wantsPlay && !background) opened.start(); updatePlay();
        });
        opened.setOnErrorListener((player, what, extra) -> { if (video == opened) failed(); return true; });
        opened.setOnInfoListener((player, what, extra) -> { if (video == opened && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) { rendered = true; stage.removeView(loading); confirmPlayback(); } return false; });
        try { SharedOutputFolder.requireContentAccess(activity,Uri.parse(job.getString("uri")));opened.setVideoURI(Uri.parse(job.getString("uri"))); } catch (Exception e) { failed(); }
        renderStrip(); updatePlay();
    }
    private void renderStrip() {
        strip.removeAllViews();
        int first = Math.max(0, index - 4), last = Math.min(items.size(), first + 9);
        for (int i = first; i < last; i++) {
            final int target = i; FrameLayout tile = new FrameLayout(activity); android.graphics.drawable.GradientDrawable shape = ui.shape(0xff2c2b28, 10); if (i == index) shape.setStroke(ui.dp(2), WHITE);
            tile.setBackground(shape); tile.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(ui.shape(0xff2c2b28, 7)); image.setClipToOutline(true); tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
            thumbnails.load(image, items.get(i), ui.dp(90));
            tile.setContentDescription("영상 " + (i + 1) + ", " + items.get(i).optString("prompt") + (i == index ? ", 선택됨" : "")); tile.setSelected(i == index); tile.setFocusable(true);
            tile.setOnClickListener(v -> { if (index != target) { index = target; position = 0; wantsPlay = true; open(); } });
            LinearLayout.LayoutParams p = lp(64, 52); p.rightMargin = ui.dp(4); strip.addView(tile, p);
            if (i == index) film.post(() -> film.smoothScrollTo(Math.max(0, tile.getLeft() - film.getWidth() / 2 + tile.getWidth() / 2), 0));
        }
        if (items.size() <= 1) film.setVisibility(View.GONE);
    }
    private void failed() {
        playbackFinished("영상 파일을 재생하지 못했어요. 파일이 삭제되었거나 지원하지 않는 형식일 수 있어요.");
        ready = false; wantsPlay = false; release(); stage.removeAllViews(); position = 0;
        notice.setText("영상을 열 수 없어요\n파일이 삭제되었는지 확인해 주세요.\n\n눌러서 다시 시도"); notice.setOnClickListener(v -> { wantsPlay = true; open(); }); stage.addView(notice, new FrameLayout.LayoutParams(-1, -1));
        seek.setEnabled(false); updatePlay();
    }
    private void move(int by) { int target = index + by; if (target < 0 || target >= items.size()) return; index = target; position = 0; wantsPlay = true; open(); }
    private void togglePlaying() { if (!canStart() || !ready) return; wantsPlay = !video.isPlaying(); if (wantsPlay) video.start(); else video.pause(); updatePlay(); }
    private void updatePlay() { play.setImageResource(wantsPlay ? R.drawable.ic_pause : R.drawable.ic_play); play.setContentDescription(wantsPlay ? "일시정지" : "재생"); play.setEnabled(ready); stage.setKeepScreenOn(ready && wantsPlay && !background); }
    private void applyVolume() { if (media != null) media.setVolume(muted ? 0 : 1, muted ? 0 : 1); sound.setImageResource(muted ? R.drawable.ic_sound_off : R.drawable.ic_sound); sound.setContentDescription(muted ? "소리 켜기" : "소리 끄기"); }
    private int duration() { return video == null ? 0 : Math.max(0, video.getDuration()); }
    private void updatePosition() { if (ready && video != null && !scrubbing) { position = video.getCurrentPosition(); int duration = duration(); seek.setProgress(duration == 0 ? 0 : (int) (position * 1000L / duration)); elapsed.setText(time(position)); } }
    private static String time(long millis) { long seconds = Math.max(0, millis / 1000); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private void toggleControls() { controls = !controls; dock.setVisibility(controls ? View.VISIBLE : View.GONE); stage.setContentDescription(controls ? "영상. 눌러 조작부 숨기기, 좌우로 넘기기" : "영상. 눌러 조작부 다시 표시하기, 좌우로 넘기기"); }
    private void pauseForAction() { wantsPlay = false; if (ready) video.pause(); updatePlay(); }
    void background() { background = true; updatePosition(); if (ready) video.pause(); main.removeCallbacks(tick); updatePlay(); }
    void foreground() { background = false; if (canStart() && ready && wantsPlay) video.start(); main.removeCallbacks(tick); if (isShowing()) main.post(tick); updatePlay(); }
    Bundle snapshot() { updatePosition(); Bundle state = new Bundle(); state.putString("id", current().optString("id")); state.putInt("position", position); state.putBoolean("muted", muted); state.putBoolean("playing", wantsPlay); return state; }
    private void release() { if (video != null) { video.setOnPreparedListener(null); video.setOnErrorListener(null); video.setOnInfoListener(null); video.stopPlayback(); video = null; } media = null; }
    @Override public void dismiss() { playbackFinished("재생 확인 전에 영상 화면을 닫았어요."); main.removeCallbacksAndMessages(null); release(); stage.setKeepScreenOn(false); super.dismiss(); }
}
