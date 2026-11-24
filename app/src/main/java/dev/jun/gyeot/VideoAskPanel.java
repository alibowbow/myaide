package dev.jun.gyeot;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.MyTube;
import dev.jun.gyeot.core.VideoChat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import static dev.jun.gyeot.Ui.*;

/**
 * 이 동영상에 대해 물어보세요, as YouTube shows it: a panel in place of the list under the video (beside it, side by side),
 * the video playing on above. At its top a slim bar of the suggestions and ✕ (no title line); then the conversation, starting with a summary in the way last
 * chosen and the suggestions (타임라인 요약 · 일반 요약 · 대본 보기); at its foot the composer of 작업창 and 마이튜브 AI — the
 * words, the model with its thinking level (ModelPopover), 음성 입력 and the round button. Summaries come from Gemini watching
 * the video; questions go to the AI and model chosen here, with the summaries, the transcript when there is one and the
 * conversation. A time in an answer jumps the video there. Each video's conversation stays for the run.
 */
final class VideoAskPanel {
    /** What the panel needs from 마이튜브. */
    interface Host { MyTubePlayer player(); void closed(); boolean gone(); }
    /**
     * Each video's conversation, oldest first: read from the phone once (mytube-chats.json) and written back after every
     * answer, so it stays when the app is closed; the newest VideoChat.KEPT videos with an answer are kept, older ones go.
     */
    private static final Map<String, List<VideoChat.Turn>> CHATS = new LinkedHashMap<>();
    /** Each kept video's title and channel, and when its conversation last changed. */
    private static final Map<String, String[]> ABOUT = new LinkedHashMap<>();
    private static boolean loaded;
    private static java.io.File file(android.content.Context context) { return new java.io.File(context.getFilesDir(), "mytube-chats.json"); }
    /** Reads the kept conversations once. */
    private static void load(android.content.Context context) {
        synchronized (CHATS) {
            if (loaded) return; loaded = true;
            java.io.File file = file(context); if (!file.isFile()) return;
            try {
                String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                for (VideoChat.Kept one : VideoChat.load(new org.json.JSONObject(text))) {
                    CHATS.put(one.id(), new ArrayList<>(one.turns()));
                    ABOUT.put(one.id(), new String[]{one.title(), one.channel(), String.valueOf(one.at())});
                }
            } catch (Exception unreadable) { /* Starts empty; the file is written again with the next answer. */ }
        }
    }
    /** The kept conversations, newest last (전체 검색 · 뜻으로 찾기), read from the phone when not read yet. Any thread. */
    static List<VideoChat.Kept> saved(android.content.Context context) {
        load(context);
        List<VideoChat.Kept> out = new ArrayList<>();
        synchronized (CHATS) {
            for (Map.Entry<String, List<VideoChat.Turn>> e : CHATS.entrySet()) {
                if (e.getValue().stream().noneMatch(t -> !t.user())) continue;
                String[] about = ABOUT.getOrDefault(e.getKey(), new String[]{"", "", "0"}); long at; try { at = Long.parseLong(about[2]); } catch (Exception x) { at = 0; }
                out.add(new VideoChat.Kept(e.getKey(), about[0], about[1], at, new ArrayList<>(e.getValue())));
            }
        }
        return out;
    }
    /** One kept conversation as text to read: the video, then each request and answer. */
    static String text(VideoChat.Kept one) {
        StringBuilder out = new StringBuilder((one.title().isEmpty() ? "유튜브 영상" : one.title()) + (one.channel().isEmpty() ? "" : " · " + one.channel()) + "\nhttps://youtu.be/" + one.id());
        for (VideoChat.Turn turn : one.turns()) out.append("\n\n").append(turn.user() ? "■ " + turn.shown() : turn.shown() + (turn.by().isEmpty() ? "" : "\n(" + turn.by() + ")"));
        return out.toString();
    }
    /** The video's conversation changed: it becomes the newest, older ones past the limit go, and the file is written. Main thread. */
    private static void keep(android.content.Context app, MyTube.Video v) {
        String json;
        synchronized (CHATS) {
            List<VideoChat.Turn> list = CHATS.remove(v.id()); if (list != null) CHATS.put(v.id(), list);
            ABOUT.put(v.id(), new String[]{v.title(), v.channel(), String.valueOf(System.currentTimeMillis())});
            List<VideoChat.Kept> all = new ArrayList<>();
            for (Map.Entry<String, List<VideoChat.Turn>> e : CHATS.entrySet()) {
                String[] about = ABOUT.getOrDefault(e.getKey(), new String[]{"", "", "0"}); long at; try { at = Long.parseLong(about[2]); } catch (Exception x) { at = 0; }
                all.add(new VideoChat.Kept(e.getKey(), about[0], about[1], at, new ArrayList<>(e.getValue())));
            }
            org.json.JSONObject saved = VideoChat.save(all);
            json = saved.toString();
            // In memory too, only what the file keeps (and what a panel shows or a job is making now).
            java.util.Set<String> kept = new java.util.HashSet<>(JOBS.keySet());
            for (VideoAskPanel panel : live()) if (panel.video != null) kept.add(panel.video.id());
            org.json.JSONArray videos = saved.optJSONArray("videos");
            for (int i = 0; videos != null && i < videos.length(); i++) kept.add(videos.optJSONObject(i).optString("id"));
            CHATS.keySet().removeIf(id -> !kept.contains(id));
            ABOUT.keySet().removeIf(id -> !CHATS.containsKey(id));
        }
        RUN.execute(() -> {
            java.io.File file = file(app), next = new java.io.File(file.getPath() + ".new");
            try {
                java.nio.file.Files.write(next.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!next.renameTo(file)) { file.delete(); next.renameTo(file); }
            } catch (Exception failed) { next.delete(); }
        });
    }
    /** The transcript characters a question takes along. */
    private static final int TRANSCRIPT_CHARS = 60_000;

    final LinearLayout view;
    private final Activity activity; private final Ui ui; private final AppStore store; private final ExecutorService work; private final android.os.Handler main; private final Host host;
    private final LinearLayout talk; private final ScrollView scroll; private final EditText input; private final ImageView send; private final TextView model, waiting;
    private MyTube.Video video; private List<VideoChat.Turn> turns = new ArrayList<>();
    private boolean open; private String transcript; private String error = "";
    /** One video's running job: what it is doing, and how to stop it. It outlives the panel and 마이튜브's screen. */
    private static final class Job { volatile String status = ""; volatile boolean halted; final AtomicReference<Runnable> stop = new AtomicReference<>(); }
    /** The running jobs, by video: a panel opened again on the video shows it going on. */
    private static final Map<String, Job> JOBS = new java.util.concurrent.ConcurrentHashMap<>();
    /** The app's threads for jobs and for writing the kept conversations (not the screen's, which go with it). */
    private static final ExecutorService RUN = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "gyeot-video-ask"); t.setDaemon(true); return t; });
    /** The panels alive now, so a job that ends finds the one showing its video. Main thread. */
    private static final java.util.Set<VideoAskPanel> LIVE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static List<VideoAskPanel> live() { return new ArrayList<>(LIVE); }
    /** A summary, an answer, a picture or a transcript is being made now (RunningWork). */
    static boolean anyRunning() { return !JOBS.isEmpty() || !FETCHING.isEmpty(); }
    private boolean busy() { return video != null && JOBS.containsKey(video.id()); }
    private ComposerDictation dictation;

    VideoAskPanel(Activity activity, Ui ui, AppStore store, ExecutorService work, android.os.Handler main, Host host) {
        this.activity = activity; this.ui = ui; this.store = store; this.work = work; this.main = main; this.host = host;
        LIVE.add(this);
        // Its own card, set apart from the page: the card color, rounded, a hairline around it and a soft lift.
        view = ui.column(); view.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable surface = ui.shape(CARD, 20); surface.setStroke(Math.max(1, ui.dp(1)), SEPARATOR);
        view.setBackground(surface); view.setClipToOutline(true); view.setElevation(ui.dp(3));
        // No title line: the summary chips and ✕ in one slim bar at the top, a hairline under them.
        LinearLayout top = ui.row(); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(ui.dp(12), ui.dp(2), ui.dp(4), ui.dp(2));
        HorizontalScrollView strip = new HorizontalScrollView(activity); strip.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = ui.row(); chips.setGravity(Gravity.CENTER_VERTICAL); strip.addView(chips);
        for (String each : new String[]{VideoChat.TIMELINE, VideoChat.PLAIN}) {
            Button chip = ui.smallChip(each, () -> {
                if (busy() || video == null) return;
                store.settings.edit().putBoolean("mytubeTimeline", VideoChat.TIMELINE.equals(each)).apply();
                ask(VideoChat.Turn.user(each, VideoChat.request(video, each), true));
            });
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.rightMargin = ui.dp(6); chips.addView(chip, at);
        }
        scriptChip = ui.smallChip("대본 보기", null); scriptChip.setOnClickListener(x -> transcriptSheet(scriptChip));
        chips.addView(scriptChip, new LinearLayout.LayoutParams(-2, -2));
        top.addView(strip, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.bareIcon(R.drawable.ic_close, TEXT, "닫기", this::close), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        view.addView(top, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(activity); line.setBackgroundColor(SEPARATOR); view.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f))));
        // The conversation.
        scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        talk = ui.column(); talk.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(16)); scroll.addView(talk);
        view.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        waiting = new ShimmerText(activity, MUTED, TEXT); waiting.setTextSize(14); waiting.setTypeface(Ui.weight(500));
        // The composer, as 마이튜브 AI's and 작업창's: one rounded card, the words on top, under them the model (its name bold, its
        // thinking level quiet beside it), 음성 입력 and the round button (send; stop while an answer is being made).
        LinearLayout composer = ui.column(); composer.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8));
        LinearLayout box = ui.column(); box.setBackground(ui.glass(28, false)); ui.lift(box, 4); box.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4));
        composer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        input = new EditText(activity); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16);
        input.setHint("질문하기…"); input.setMaxLines(4);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        input.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(2)); input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, event) -> { if (id == EditorInfo.IME_ACTION_SEND) { submit(); return true; } return false; });
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL);
        // +: pictures of the video with GPT image (그래픽 레코딩, 인포그래픽, 4컷 만화, or the user's own words).
        bar.addView(ui.bareIcon(R.drawable.ic_add, TEXT, "더하기 · 그림 그리기", this::plus), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        model = ui.text("", 13.5f, 500, TEXT); model.setSingleLine(true); model.setEllipsize(TextUtils.TruncateAt.END);
        model.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); model.setPadding(ui.dp(10), 0, ui.dp(6), 0); model.setMinHeight(ui.dp(44));
        model.setOnClickListener(v -> chooseModel()); Ui.pressable(model, true);
        bar.addView(model, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.bareIcon(R.drawable.ic_mic, TEXT, "음성 입력 · 입력창에만 넣기", this::dictate), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        send = ui.sendButton("보내기", () -> { if (busy()) halt(); else submit(); });
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        view.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    }

    boolean isOpen() { return open; }
    /** Opens the panel on a video: its conversation as it was, or a new one that starts with a summary in the way last chosen. */
    void open(MyTube.Video v) {
        if (v == null) return;
        if (open && video != null && video.id().equals(v.id())) { draw(); return; }
        // Another video's job (or this one's) goes on; the panel only shows a different conversation.
        video = v; open = true; error = ""; transcript = null;
        load(activity.getApplicationContext());
        synchronized (CHATS) { turns = CHATS.computeIfAbsent(v.id(), id -> new ArrayList<>()); }
        view.setVisibility(View.VISIBLE);
        // Nothing starts by itself: a summary begins when 타임라인 요약 or 일반 요약 above is pressed, or a question is sent.
        draw();
    }
    /** ✕ or back: the panel goes and the list comes back; an answer being made goes on and is kept (멈추기 stops it). */
    void close() {
        if (!open) return;
        open = false; view.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        host.closed();
    }
    /** Whatever is running for the video shown stops, and its answer, if it comes, is dropped. */
    /** 멈추기: the job for the video shown stops. */
    private void halt() { Job job = video == null ? null : JOBS.get(video.id()); if (job == null) return; job.halted = true; Runnable abort = job.stop.getAndSet(null); if (abort != null) abort.run(); }
    /** The video the panel shows, when open (마이튜브 keeps it across being made again). */
    MyTube.Video shown() { return open ? video : null; }
    private void submit() {
        String text = input.getText().toString().strip(); if (text.isEmpty() || busy() || video == null) return;
        input.setText("");
        boolean draw = drawNext; drawNext(false);
        ask(VideoChat.Turn.user(text, draw ? DRAW + text : text, false));
    }
    /** A request sent with this before it is drawn (+ → 직접 적어서 그리기), whatever its words. */
    private static final String DRAW = "[그림] ";
    /** + → 직접 적어서 그리기: the next words sent are drawn; the box says so until then. */
    private boolean drawNext;
    private void drawNext(boolean on) { drawNext = on; input.setHint(on ? "어떤 그림을 그릴까요? 예: 핵심만 인포그래픽으로" : "질문하기…"); }
    /** +: ways to draw the video, as a short list. */
    private void plus() {
        if (video == null) return;
        Ui.Sheet sheet = ui.selectionSheet("그림으로 그리기", "GPT 이미지가 이 영상의 내용으로 그려요 · 갤러리와 들퍼레/이미지에 저장돼요");
        sheet.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        String[][] ways = {{"그래픽 레코딩", "대본을 요약해서 그래픽 레코딩 스타일로 그려줘"}, {"인포그래픽", "핵심 내용과 숫자를 한 장의 인포그래픽으로 그려줘"}, {"4컷 만화", "영상 내용을 4컷 만화로 그려줘"}};
        for (String[] way : ways) ui.addRow(group, ui.listRow(R.drawable.ic_gallery, ACCENT, way[0], way[1], null, () -> { sheet.dismiss(); if (!busy()) ask(VideoChat.Turn.user(way[1], DRAW + way[1], false)); }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_sparkle, ACCENT, "직접 적어서 그리기", "아래 입력창에 원하는 그림을 적어요", null, () -> { sheet.dismiss(); drawNext(true); input.requestFocus(); }), true);
        sheet.body.addView(group, new LinearLayout.LayoutParams(-1, -2));
        sheet.show();
    }
    private MainChatPictures pictures;
    private MainChatPictures pictures() { if (pictures == null) pictures = new MainChatPictures(activity, store); return pictures; }
    /** The picture an answer holds, from its private copy (small), as wide as the panel; a tap shows it whole. */
    private View picture(String id) {
        java.io.File file = store.attachmentFile(id);
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) { TextView gone = ui.text("그림을 찾지 못했어요 (갤러리에서 지웠을 수 있어요).", FOOTNOTE, 450, MUTED); return gone; }
        int sample = 1; while (bounds.outWidth / (sample * 2) >= 1200) sample *= 2;
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options(); opts.inSampleSize = sample;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
        ImageView image = new ImageView(activity); image.setImageBitmap(b); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(ui.shape(FILL, 14)); image.setClipToOutline(true); image.setContentDescription("그린 그림, 누르면 크게 보기");
        image.setOnClickListener(v -> {
            android.app.Dialog dialog = new android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView whole = new ImageView(activity); whole.setImageBitmap(b); whole.setScaleType(ImageView.ScaleType.FIT_CENTER); whole.setBackgroundColor(0xff0b0b0c);
            whole.setOnClickListener(x -> dialog.dismiss()); dialog.setContentView(whole); dialog.show();
        });
        Ui.pressable(image, true);
        return image;
    }

    /** The suggestions, the turns and what is being made, then to the newest; the model and the button as they are now. */
    private void draw() {
        talk.removeAllViews();
        List<VideoChat.Turn> shown; synchronized (CHATS) { shown = new ArrayList<>(turns); }
        boolean busy = busy(); Job running = video == null ? null : JOBS.get(video.id());
        if (running != null && !running.status.isEmpty()) waiting.setText(running.status);
        if (shown.isEmpty() && !busy && error.isEmpty()) {
            TextView hint = ui.text("위에서 타임라인 요약이나 일반 요약을 누르거나, 아래에 물어보세요.", FOOTNOTE, 450, MUTED); hint.setPadding(0, ui.dp(8), 0, 0);
            talk.addView(hint);
        }
        for (int i = 0; i < shown.size(); i++) {
            VideoChat.Turn turn = shown.get(i);
            // A summary chip's request is not shown as a turn: its answer stands alone.
            if (turn.user()) { if (!turn.summary()) talk.addView(asked(turn)); }
            else talk.addView(answered(turn, i == shown.size() - 1 && i > 0));
        }
        if (busy) {
            LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, ui.dp(16), 0, 0);
            if (waiting.getParent() != null) ((android.view.ViewGroup) waiting.getParent()).removeView(waiting);
            row.addView(waiting, new LinearLayout.LayoutParams(-2, -2));
            talk.addView(row);
        } else if (!error.isEmpty()) {
            TextView why = ui.text(error, FOOTNOTE, 450, MUTED); why.setPadding(0, ui.dp(14), 0, 0); talk.addView(why);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        renderModel(); scriptLooks();
        ui.sendLooks(send, busy); send.setContentDescription(busy ? "멈추기" : "보내기");
    }
    /** The user's turn: on the right in a soft box; a summary chip with its mark, as YouTube's. */
    private View asked(VideoChat.Turn turn) {
        LinearLayout wrap = ui.column(); wrap.setGravity(Gravity.END); wrap.setPadding(ui.dp(48), ui.dp(18), 0, 0);
        LinearLayout box = ui.row(); box.setGravity(Gravity.CENTER_VERTICAL); box.setBackground(ui.shape(FILL, 18));
        box.setPadding(ui.dp(turn.summary() ? 8 : 14), ui.dp(8), ui.dp(14), ui.dp(8));
        if (turn.summary()) {
            ImageView mark = new ImageView(activity); mark.setImageResource(R.drawable.ic_text); mark.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT));
            mark.setBackground(ui.shape(CARD, 12)); mark.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)); mp.rightMargin = ui.dp(10); box.addView(mark, mp);
        }
        TextView words = ui.text(turn.shown(), BODY, 480, TEXT); words.setTextIsSelectable(true); box.addView(words);
        wrap.addView(box, new LinearLayout.LayoutParams(-2, -2));
        return wrap;
    }
    /** An answer: the text (its times jump the video), and its icons; the AI that gave it, quietly. */
    private View answered(VideoChat.Turn turn, boolean last) {
        LinearLayout wrap = ui.column(); wrap.setPadding(0, ui.dp(18), 0, 0);
        java.util.regex.Matcher drawn = VideoChat.PICTURE_MARK.matcher(turn.shown());
        if (drawn.lookingAt()) { LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(-1, -2); pa.bottomMargin = ui.dp(8); wrap.addView(picture(drawn.group(1)), pa); }
        String text = VideoChat.words(turn.shown());
        TextView answer = ui.text("", BODY, 450, TEXT); answer.setLineSpacing(ui.dp(4), 1);
        answer.setText(timed(RichText.render(text))); answer.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        wrap.addView(answer, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout foot = ui.row(); foot.setGravity(Gravity.CENTER_VERTICAL);
        // Small icons: a 16dp mark in a 36dp target, quiet under the text.
        ImageView copy = ui.footIcon(R.drawable.ic_copy, "복사", () -> {
            activity.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("영상 답", MyTube.withoutTimes(dev.jun.gyeot.core.Markdown.plain(text))));
            Toast.makeText(activity, "복사했어요", Toast.LENGTH_SHORT).show();
        });
        copy.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); foot.addView(copy, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        if (last) { ImageView redo = ui.footIcon(R.drawable.ic_refresh, "다시 답하기", this::again); redo.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); foot.addView(redo, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36))); }
        if (!turn.by().isEmpty()) { TextView who = ui.text(turn.by(), FOOTNOTE, 450, MUTED); who.setPadding(ui.dp(6), 0, 0, 0); foot.addView(who); }
        wrap.addView(foot, new LinearLayout.LayoutParams(-2, -2));
        if (!turn.note().isEmpty()) { TextView note = ui.text(turn.note(), FOOTNOTE, 450, MUTED); note.setPadding(ui.dp(4), 0, 0, 0); wrap.addView(note, new LinearLayout.LayoutParams(-1, -2)); }
        return wrap;
    }
    /** 다시 답하기: the last answer goes and its request is asked again. */
    private void again() {
        if (busy()) return;
        VideoChat.Turn asked;
        synchronized (CHATS) { if (turns.size() < 2 || turns.get(turns.size() - 1).user()) return; turns.remove(turns.size() - 1); asked = turns.remove(turns.size() - 1); }
        ask(asked);
    }

    /** Asks: a summary to Gemini watching the video (else the transcript to the AI chosen here), a question to the AI chosen here. */
    private void ask(VideoChat.Turn question) {
        if (busy() || video == null) return;
        MyTube.Video v = video; Job job = new Job(); List<VideoChat.Turn> list = turns;
        List<VideoChat.Turn> sent;
        synchronized (CHATS) { list.add(question); while (list.size() > VideoChat.MOST_TURNS) list.remove(0); while (!list.isEmpty() && !list.get(0).user()) list.remove(0); sent = new ArrayList<>(list); }
        JOBS.put(v.id(), job); error = "";
        android.content.Context app = activity.getApplicationContext(); String known = transcript;
        boolean picture = !question.summary() && (question.sent().startsWith(DRAW) || VideoChat.asksPicture(question.shown()));
        job.status = question.summary() ? "Gemini가 영상을 보는 중" : picture ? "그릴 내용을 정리하는 중" : "생각하는 중"; waiting.setText(job.status); draw();
        // On the app's own threads: closing the panel or leaving 마이튜브 does not end it; only 멈추기 does.
        RUN.execute(() -> {
            String said = null, failure = null;
            String[] by = {""};
            Runnable guard = () -> { if (job.halted) throw new java.util.concurrent.CancellationException("멈췄어요."); };
            String script = known;
            String gemini; try { gemini = store.secrets.workKey(); } catch (Exception e) { gemini = ""; }
            String[] how = {""};
            try {
                // The script first (kept, or read now): far fewer tokens than the video, and it carries the times.
                if (script == null) {
                    status(v, job, "대본을 가져오는 중");
                    if (!YouTubeTranscriptClient.kept(app, v.id()))
                        YouTubeTranscriptClient.block(app, v.id(), () -> job.halted, step -> status(v, job, step), job.stop);
                    // Only a script really kept (from YouTube, or written down by Gemini on 대본 보기).
                    String kept = YouTubeTranscriptClient.saved(app, v.id());
                    script = kept.length() <= TRANSCRIPT_CHARS ? kept : kept.substring(0, TRANSCRIPT_CHARS) + "\n…(대본이 길어 앞부분만)";
                }
                guard.run();
                if (picture) {
                    // The AI chosen here writes the picture's prompt from the video, then GPT image draws it.
                    status(v, job, "그릴 내용을 정리하는 중");
                    String planned = MyTubeClient.askScoped(store, ScopedModels.videoChat(store), VideoChat.PICTURE, VideoChat.chatInput(v, script, sent), job.stop, name -> by[0] = name);
                    guard.run();
                    org.json.JSONObject plan = null;
                    int from = planned == null ? -1 : planned.indexOf('{'), to = planned == null ? -1 : planned.lastIndexOf('}');
                    if (from >= 0 && to > from) try { plan = new org.json.JSONObject(planned.substring(from, to + 1)); } catch (org.json.JSONException notJson) { plan = null; }
                    if (plan == null || plan.optString("prompt").isBlank()) plan = new org.json.JSONObject().put("prompt", planned == null ? question.shown() : planned.strip()).put("aspect", "portrait");
                    status(v, job, "GPT 이미지가 그리는 중");
                    org.json.JSONObject made = pictures().drawFor("마이튜브", "마이튜브", plan, question.shown(), "mytube-picture-" + java.util.UUID.randomUUID(), 0, 1, guard, job.stop);
                    said = "[[picture:" + made.optString("id") + "]]\n" + (made.optString("note").isBlank() ? "그림을 그렸어요 · 갤러리와 들퍼레/이미지에 저장했어요." : made.optString("note"));
                    by[0] = "GPT 이미지" + (by[0].isBlank() ? "" : " · 내용 정리 " + by[0]);
                } else if (question.summary()) {
                    boolean timeline = VideoChat.TIMELINE.equals(question.shown());
                    String textFail = "", watchFail = "";
                    // 1. Gemini Flash-Lite sums up the script.
                    if (!script.isBlank() && !gemini.isBlank()) try {
                        status(v, job, "Gemini가 대본을 정리하는 중");
                        said = GeminiAudioClient.summarize(gemini, VideoChat.transcriptSummary(timeline), MyTube.summaryInput(v, script), guard, job.stop); by[0] = "Gemini · 대본";
                    } catch (java.util.concurrent.CancellationException stopped) { throw stopped; } catch (Exception e) { textFail = reason(e); }
                    // 2. No script: Gemini watches the video itself.
                    if ((said == null || said.isBlank()) && script.isBlank() && !gemini.isBlank()) try {
                        status(v, job, "Gemini가 영상을 보는 중");
                        said = GeminiAudioClient.watchYouTube(gemini, VideoChat.watch(v, List.of(question)), guard, job.stop); by[0] = "Gemini · 영상";
                    } catch (java.util.concurrent.CancellationException stopped) { throw stopped; } catch (Exception e) { watchFail = reason(e); }
                    // 3. Gemini could not: the AI chosen here, from the script.
                    if ((said == null || said.isBlank()) && !script.isBlank()) {
                        status(v, job, "AI가 정리하는 중");
                        said = MyTubeClient.askScoped(store, ScopedModels.videoChat(store), VideoChat.transcriptSummary(timeline), MyTube.summaryInput(v, script), job.stop, name -> by[0] = name);
                        how[0] = "Gemini가 정리하지 못해 고른 AI가 대본으로 정리했어요" + (gemini.isBlank() ? " · Gemini API 키가 없어요" : textFail.isEmpty() ? "" : " · " + textFail);
                        store.phoneLog("마이튜브 · 영상 요약 · " + how[0]);
                    }
                    if (said == null || said.isBlank()) throw new java.io.IOException(!script.isBlank() ? "요약하지 못했어요."
                        : gemini.isBlank() ? "대본을 가져오지 못했고, 영상을 직접 볼 Gemini API 키도 없어요."
                        : "대본을 가져오지 못했고, Gemini가 영상을 직접 보지도 못했어요" + (watchFail.isEmpty() ? "." : " · " + watchFail));
                } else said = MyTubeClient.askScoped(store, ScopedModels.videoChat(store), VideoChat.CHAT, VideoChat.chatInput(v, script, sent), job.stop,
                    name -> { by[0] = name; status(v, job, name + " 생각하는 중"); });
            } catch (java.util.concurrent.CancellationException stopped) { said = null; failure = "멈췄어요."; }
            catch (Exception e) { said = null; failure = reason(e); }
            if (job.halted) { said = null; failure = "멈췄어요."; }
            String answer = said, why = failure, who = by[0], found = script;
            main.post(() -> {
                JOBS.remove(v.id(), job);
                synchronized (CHATS) {
                    if (answer != null && !answer.isBlank()) list.add(VideoChat.Turn.model(answer.strip(), who, question.summary(), how[0]));
                    // Not answered: the question leaves the conversation and goes back into the box, to send again.
                    else if (!list.isEmpty() && list.get(list.size() - 1) == question) list.remove(list.size() - 1);
                }
                if (answer != null && !answer.isBlank()) keep(app, v);
                // Whichever panel shows this video now (this one, or the one 마이튜브 made when it came back) shows the outcome.
                boolean shown = false;
                for (VideoAskPanel panel : live()) shown |= panel.finished(v.id(), question, answer, why, found);
                if (!shown && answer != null && !answer.isBlank() && app != null)
                    Toast.makeText(app, (question.summary() ? "영상 요약" : "답") + "이 끝났어요 · ‘요약’을 누르면 보여요", Toast.LENGTH_SHORT).show();
            });
        });
    }
    private static String reason(Exception e) { return e.getMessage() == null || e.getMessage().isBlank() ? "답하지 못했어요." : e.getMessage().strip(); }
    /** What a job is doing now: kept on the job, shown by whichever panel shows its video. */
    private void status(MyTube.Video v, Job job, String text) {
        job.status = text;
        main.post(() -> { for (VideoAskPanel panel : live()) if (panel.video != null && panel.video.id().equals(v.id()) && JOBS.get(v.id()) == job) panel.waiting.setText(text); });
    }
    /** A job for this panel's video ended: its answer (already in the conversation) or why not. True when this panel shows that video. */
    private boolean finished(String id, VideoChat.Turn question, String answer, String why, String found) {
        if (video == null || !video.id().equals(id)) return false;
        if (found != null) transcript = found;
        if (answer == null || answer.isBlank()) {
            error = why == null ? "답하지 못했어요." : why;
            if (!question.summary() && input.getText().length() == 0) input.setText(question.shown());
        }
        if (open) draw();
        return open;
    }

    /** Makes each time in an answer (03:12, 1:02:03) a link that jumps the video there, when it is the one playing. */
    private CharSequence timed(CharSequence rendered) {
        List<MyTube.Stamp> stamps = MyTube.stamps(rendered);
        if (stamps.isEmpty() || video == null) return rendered;
        String id = video.id();
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(rendered);
        for (MyTube.Stamp stamp : stamps) {
            out.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(View widget) {
                    widget.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    if (!host.player().seekTo(id, stamp.seconds())) Toast.makeText(activity, "이 영상을 재생하는 동안 누르면 그 지점으로 가요", Toast.LENGTH_SHORT).show();
                }
                @Override public void updateDrawState(android.text.TextPaint paint) { paint.setColor(ACCENT); paint.setUnderlineText(false); }
            }, stamp.start(), stamp.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /**
     * 대본 보기: the kept transcript, else read now (YouTube's captions, else Gemini writes it down from the video), then shown.
     * The chip says what it is doing meanwhile.
     */
    private Button scriptChip;
    /** The videos whose transcript is being read now (the app's, not the panel's: it goes on when the panel or 마이튜브 closes). */
    private static final java.util.Set<String> FETCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 대본 보기 as it is for the video shown: waiting while its transcript is being read. */
    private void scriptLooks() {
        if (scriptChip == null) return;
        boolean reading = video != null && FETCHING.contains(video.id());
        scriptChip.setEnabled(!reading); scriptChip.setText(reading ? "대본 가져오는 중…" : "대본 보기");
    }
    private void transcriptSheet(Button chip) {
        MyTube.Video v = video; if (v == null) return;
        if (YouTubeTranscriptClient.kept(activity, v.id())) { YouTubeTranscriptUi.show(activity, MyTube.watch(v.id())); return; }
        if (!FETCHING.add(v.id())) return;
        scriptLooks();
        android.content.Context app = activity.getApplicationContext();
        RUN.execute(() -> {
            String why = null;
            try {
                String block = YouTubeTranscriptClient.block(app, v.id(), () -> false, step -> main.post(() -> { for (VideoAskPanel panel : live()) if (panel.video != null && panel.video.id().equals(v.id()) && panel.scriptChip != null) panel.scriptChip.setText("받아 적는 중…"); }), null);
                if (!YouTubeTranscriptClient.kept(app, v.id())) {
                    String key = store.secrets.workKey();
                    if (key.isBlank()) why = firstLine(block);
                    else YouTubeTranscriptClient.writeDown(app, v.id(), v.title(), v.channel(), key, () -> {}, null);
                }
            } catch (Exception e) { why = e.getMessage() == null || e.getMessage().isBlank() ? "대본을 가져오지 못했어요." : e.getMessage(); }
            String reason = why;
            main.post(() -> {
                FETCHING.remove(v.id());
                boolean shown = false;
                for (VideoAskPanel panel : live()) shown |= panel.scriptDone(v, reason);
                if (!shown) Toast.makeText(app, YouTubeTranscriptClient.kept(app, v.id()) ? "대본을 받았어요 · ‘대본 보기’를 누르면 보여요" : "대본을 가져오지 못했어요" + (reason == null || reason.isBlank() ? "" : ": " + reason), Toast.LENGTH_LONG).show();
            });
        });
    }
    /** A transcript read for v came back: shown here when this panel is open on v (the chip is set right either way). */
    private boolean scriptDone(MyTube.Video v, String reason) {
        scriptLooks();
        if (host.gone() || !open || video == null || !video.id().equals(v.id())) return false;
        if (YouTubeTranscriptClient.kept(activity, v.id())) { transcript = null; YouTubeTranscriptUi.show(activity, MyTube.watch(v.id())); }
        else Toast.makeText(activity, "대본을 가져오지 못했어요" + (reason == null || reason.isBlank() ? "" : ": " + reason), Toast.LENGTH_LONG).show();
        return true;
    }
    private static String firstLine(String text) {
        String value = text == null ? "" : text.strip(); int cut = value.indexOf('\n');
        value = cut < 0 ? value : value.substring(0, cut);
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }

    // ── the model, as 작업창 and 마이튜브 AI choose it ─────────────────────
    private ScopedModels models() { return ScopedModels.videoChat(store); }
    /** The model as 작업창 shows it: its name in bold, its thinking level quiet beside it. */
    private void renderModel() {
        String[] parts = modelParts();
        android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(parts[0]);
        label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, label.length(), 0);
        if (!parts[1].isEmpty()) { int at = label.length(); label.append("  ").append(parts[1]); label.setSpan(new android.text.style.ForegroundColorSpan(MUTED), at, label.length(), 0); }
        model.setText(label);
        model.setContentDescription("질문에 답할 모델과 생각 깊이 바꾸기, 지금 " + parts[0] + (parts[1].isEmpty() ? "" : " " + parts[1]));
    }
    private String[] modelParts() {
        try {
            ScopedModels selection = models(); String engine = selection.engine();
            if (engine == null || engine.isBlank() || !store.ready(engine)) return new String[]{"AI 연결·선택", ""};
            String id = selection.model(engine); if (id.isBlank()) return new String[]{dev.jun.gyeot.core.WorkBook.author(engine) + " · 모델 고르기", ""};
            TeamRoster roster = new TeamRoster(store, selection);
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, id); String effort = selection.effort(engine, id);
            String level = profile.efforts.isEmpty() ? "" : MyTubeChat.effortWord(engine, effort.isEmpty() ? profile.defaultEffort : effort);
            return new String[]{roster.label(engine, id), level};
        } catch (Exception unavailable) { return new String[]{"AI 연결·선택", ""}; }
    }
    /** The menu over the model (ModelPopover): its models, 생각 깊이, 다른 AI and 모든 모델·설정. */
    private void chooseModel() {
        ScopedModels selection; try { selection = models(); } catch (Exception e) { store.error(e.getMessage()); return; }
        String engine = selection.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) { allModels(); return; }
        TeamRoster roster = new TeamRoster(store, selection);
        new ModelPopover(activity, ui, store, selection, new ModelPopover.Catalog() {
            @Override public org.json.JSONArray models(String e) { return roster.catalog(e); }
            @Override public String label(String e, String m) { return roster.label(e, m); }
            @Override public dev.jun.gyeot.core.Reasoning.Profile profile(String e, String m) { return new TeamRoster(store).profile(e, m); }
            @Override public String effortLabel(String e, String effort) { return MyTubeChat.effortWord(e, effort); }
            @Override public String effortHint(String effort, String fallback) { return MainActivity.effortHint(effort, fallback); }
            @Override public String team() { return ""; }
        }, e -> {
            try { selection.chooseEngine(e); } catch (Exception failed) { store.error(failed.getMessage()); return; }
            if (!store.ready(e)) Toast.makeText(activity, dev.jun.gyeot.core.WorkBook.author(e) + "를 설정에서 먼저 연결해 주세요.", Toast.LENGTH_LONG).show();
            renderModel();
        }, this::allModels, null, this::renderModel).show(model);
    }
    private void allModels() {
        FileExplorerModelUi.show(activity, store, this::models, "영상 대화", "영상 대화에 적용", () -> !activity.isDestroyed(), dialog -> dialog.show(), this::renderModel);
    }
    /** 음성 입력: what is said goes into the field (Gemini's transcription, as 작업창's), to read and send. */
    private void dictate() {
        if (store.voiceActive || store.recording || store.voiceChat != null) { Toast.makeText(activity, "음성 대화나 녹음을 끝낸 뒤 음성으로 입력해 주세요.", Toast.LENGTH_LONG).show(); return; }
        if (!store.secrets.hasWorkKey()) { Toast.makeText(activity, "음성 입력에 쓸 Gemini API 키가 없어요. 설정에서 Gemini를 연결해 주세요.", Toast.LENGTH_LONG).show(); return; }
        store.stopReading(null);
        if (dictation == null) dictation = new ComposerDictation(activity, input, () -> "mytube", message -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
        dictation.begin(() -> new GeminiComposerSession(store.secrets.workKey()), "Gemini 3.5 Transcribe Live");
    }
}
