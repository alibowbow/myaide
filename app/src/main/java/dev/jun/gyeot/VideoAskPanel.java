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
    /** The video's conversation changed: it becomes the newest, older ones past the limit go, and the file is written. */
    private void keep(MyTube.Video v) {
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
            // In memory too, only what the file keeps (and the one shown now).
            java.util.Set<String> kept = new java.util.HashSet<>();
            org.json.JSONArray videos = saved.optJSONArray("videos");
            for (int i = 0; videos != null && i < videos.length(); i++) kept.add(videos.optJSONObject(i).optString("id"));
            CHATS.keySet().removeIf(id -> !kept.contains(id) && (video == null || !id.equals(video.id())));
            ABOUT.keySet().removeIf(id -> !CHATS.containsKey(id));
        }
        android.content.Context app = activity.getApplicationContext();
        work.execute(() -> {
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
    private final AtomicReference<Runnable> stop = new AtomicReference<>();
    private MyTube.Video video; private List<VideoChat.Turn> turns = new ArrayList<>();
    private boolean busy, halted, open; private String transcript; private long generation; private String error = "";
    private ComposerDictation dictation;

    VideoAskPanel(Activity activity, Ui ui, AppStore store, ExecutorService work, android.os.Handler main, Host host) {
        this.activity = activity; this.ui = ui; this.store = store; this.work = work; this.main = main; this.host = host;
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
                if (busy || video == null) return;
                store.settings.edit().putBoolean("mytubeTimeline", VideoChat.TIMELINE.equals(each)).apply();
                ask(VideoChat.Turn.user(each, VideoChat.request(video, each), true));
            });
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.rightMargin = ui.dp(6); chips.addView(chip, at);
        }
        Button script = ui.smallChip("대본 보기", null); script.setOnClickListener(x -> transcriptSheet(script));
        chips.addView(script, new LinearLayout.LayoutParams(-2, -2));
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
        model = ui.text("", 13.5f, 500, TEXT); model.setSingleLine(true); model.setEllipsize(TextUtils.TruncateAt.END);
        model.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); model.setPadding(ui.dp(10), 0, ui.dp(6), 0); model.setMinHeight(ui.dp(44));
        model.setOnClickListener(v -> chooseModel()); Ui.pressable(model, true);
        bar.addView(model, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.bareIcon(R.drawable.ic_mic, TEXT, "음성 입력 · 입력창에만 넣기", this::dictate), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        send = ui.sendButton("보내기", () -> { if (busy) halt(); else submit(); });
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        view.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    }

    boolean isOpen() { return open; }
    /** Opens the panel on a video: its conversation as it was, or a new one that starts with a summary in the way last chosen. */
    void open(MyTube.Video v) {
        if (v == null) return;
        if (open && video != null && video.id().equals(v.id())) { draw(); return; }
        abandon();
        video = v; open = true; error = ""; transcript = null;
        load(activity.getApplicationContext());
        synchronized (CHATS) { turns = CHATS.computeIfAbsent(v.id(), id -> new ArrayList<>()); }
        view.setVisibility(View.VISIBLE);
        // Nothing starts by itself: a summary begins when 타임라인 요약 or 일반 요약 above is pressed, or a question is sent.
        draw();
    }
    /** ✕ or back: the panel goes, an answer being made stops, and the list comes back. */
    void close() {
        if (!open) return;
        abandon(); open = false; view.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager keyboard = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        host.closed();
    }
    /** Whatever is running for the video shown stops, and its answer, if it comes, is dropped. */
    private void abandon() { generation++; halted = true; busy = false; Runnable abort = stop.getAndSet(null); if (abort != null) abort.run(); }
    private void halt() { halted = true; Runnable abort = stop.getAndSet(null); if (abort != null) abort.run(); }
    private void submit() {
        String text = input.getText().toString().strip(); if (text.isEmpty() || busy || video == null) return;
        input.setText(""); ask(VideoChat.Turn.user(text, text, false));
    }

    /** The suggestions, the turns and what is being made, then to the newest; the model and the button as they are now. */
    private void draw() {
        talk.removeAllViews();
        List<VideoChat.Turn> shown; synchronized (CHATS) { shown = new ArrayList<>(turns); }
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
        renderModel();
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
        TextView answer = ui.text("", BODY, 450, TEXT); answer.setLineSpacing(ui.dp(4), 1);
        answer.setText(timed(RichText.render(turn.shown()))); answer.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        wrap.addView(answer, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout foot = ui.row(); foot.setGravity(Gravity.CENTER_VERTICAL);
        String text = turn.shown();
        // Small icons: a 16dp mark in a 36dp target, quiet under the text.
        ImageView copy = ui.footIcon(R.drawable.ic_copy, "복사", () -> {
            activity.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("영상 답", text));
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
        if (busy) return;
        VideoChat.Turn asked;
        synchronized (CHATS) { if (turns.size() < 2 || turns.get(turns.size() - 1).user()) return; turns.remove(turns.size() - 1); asked = turns.remove(turns.size() - 1); }
        ask(asked);
    }

    /** Asks: a summary to Gemini watching the video (else the transcript to the AI chosen here), a question to the AI chosen here. */
    private void ask(VideoChat.Turn question) {
        if (busy || video == null) return;
        MyTube.Video v = video; long mine = generation;
        List<VideoChat.Turn> sent;
        synchronized (CHATS) { turns.add(question); while (turns.size() > VideoChat.MOST_TURNS) turns.remove(0); while (!turns.isEmpty() && !turns.get(0).user()) turns.remove(0); sent = new ArrayList<>(turns); }
        busy = true; halted = false; error = "";
        waiting.setText(question.summary() ? "Gemini가 영상을 보는 중" : "생각하는 중"); draw();
        work.execute(() -> {
            String said = null, failure = null;
            String[] by = {""};
            Runnable guard = () -> { if (halted || mine != generation || host.gone()) throw new java.util.concurrent.CancellationException("멈췄어요."); };
            String gemini; try { gemini = store.secrets.workKey(); } catch (Exception e) { gemini = ""; }
            String[] how = {""};
            try {
                // The transcript first (kept, or read now): far fewer tokens than the video, and it carries the times.
                if (transcript == null) {
                    status(mine, "대본을 가져오는 중");
                    if (!YouTubeTranscriptClient.kept(activity, v.id()))
                        YouTubeTranscriptClient.block(activity, v.id(), () -> halted || mine != generation, step -> status(mine, step), stop);
                    // Only a transcript really kept (from YouTube, or written down by Gemini on 대본 보기).
                    String kept = YouTubeTranscriptClient.saved(activity, v.id());
                    transcript = kept.length() <= TRANSCRIPT_CHARS ? kept : kept.substring(0, TRANSCRIPT_CHARS) + "\n…(대본이 길어 앞부분만)";
                }
                guard.run();
                if (question.summary()) {
                    boolean timeline = VideoChat.TIMELINE.equals(question.shown());
                    String textFail = "", watchFail = "";
                    // 1. Gemini Flash-Lite sums up the transcript.
                    if (!transcript.isBlank() && !gemini.isBlank()) try {
                        status(mine, "Gemini가 대본을 정리하는 중");
                        said = GeminiAudioClient.summarize(gemini, VideoChat.transcriptSummary(timeline), MyTube.summaryInput(v, transcript), guard, stop); by[0] = "Gemini · 대본";
                    } catch (java.util.concurrent.CancellationException stopped) { throw stopped; } catch (Exception e) { textFail = reason(e); }
                    // 2. No transcript: Gemini watches the video itself.
                    if ((said == null || said.isBlank()) && transcript.isBlank() && !gemini.isBlank()) try {
                        status(mine, "Gemini가 영상을 보는 중");
                        said = GeminiAudioClient.watchYouTube(gemini, VideoChat.watch(v, List.of(question)), guard, stop); by[0] = "Gemini · 영상";
                    } catch (java.util.concurrent.CancellationException stopped) { throw stopped; } catch (Exception e) { watchFail = reason(e); }
                    // 3. Gemini could not: the AI chosen here, from the transcript.
                    if ((said == null || said.isBlank()) && !transcript.isBlank()) {
                        status(mine, "AI가 정리하는 중");
                        said = MyTubeClient.askScoped(store, ScopedModels.videoChat(store), VideoChat.transcriptSummary(timeline), MyTube.summaryInput(v, transcript), stop, name -> by[0] = name);
                        how[0] = "Gemini가 정리하지 못해 고른 AI가 대본으로 정리했어요" + (gemini.isBlank() ? " · Gemini API 키가 없어요" : textFail.isEmpty() ? "" : " · " + textFail);
                        store.phoneLog("마이튜브 · 영상 요약 · " + how[0]);
                    }
                    if (said == null || said.isBlank()) throw new java.io.IOException(!transcript.isBlank() ? "요약하지 못했어요."
                        : gemini.isBlank() ? "대본을 가져오지 못했고, 영상을 직접 볼 Gemini API 키도 없어요."
                        : "대본을 가져오지 못했고, Gemini가 영상을 직접 보지도 못했어요" + (watchFail.isEmpty() ? "." : " · " + watchFail));
                } else said = MyTubeClient.askScoped(store, ScopedModels.videoChat(store), VideoChat.CHAT, VideoChat.chatInput(v, transcript, sent), stop,
                    name -> { by[0] = name; status(mine, name + " 생각하는 중"); });
            } catch (java.util.concurrent.CancellationException stopped) { said = null; failure = "멈췄어요."; }
            catch (Exception e) { said = null; failure = reason(e); }
            if (halted) { said = null; failure = "멈췄어요."; }
            String answer = said, why = failure, who = by[0];
            main.post(() -> {
                if (mine != generation) return;
                busy = false; stop.set(null);
                synchronized (CHATS) {
                    if (answer != null && !answer.isBlank()) turns.add(VideoChat.Turn.model(answer.strip(), who, question.summary(), how[0]));
                    // Not answered: the question leaves the conversation and goes back into the box, to send again.
                    else if (!turns.isEmpty() && turns.get(turns.size() - 1) == question) turns.remove(turns.size() - 1);
                }
                if (answer == null || answer.isBlank()) {
                    error = why == null ? "답하지 못했어요." : why;
                    if (!question.summary() && input.getText().length() == 0) input.setText(question.shown());
                }
                if (answer != null && !answer.isBlank()) keep(v);
                if (open) draw();
            });
        });
    }
    private static String reason(Exception e) { return e.getMessage() == null || e.getMessage().isBlank() ? "답하지 못했어요." : e.getMessage().strip(); }
    private void status(long mine, String text) { main.post(() -> { if (mine == generation && busy) waiting.setText(text); }); }

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
    private void transcriptSheet(Button chip) {
        MyTube.Video v = video; if (v == null) return;
        if (YouTubeTranscriptClient.kept(activity, v.id())) { YouTubeTranscriptUi.show(activity, MyTube.watch(v.id())); return; }
        if (!chip.isEnabled()) return;
        chip.setEnabled(false); chip.setText("대본 가져오는 중…");
        work.execute(() -> {
            String why = null;
            try {
                String block = YouTubeTranscriptClient.block(activity, v.id(), host::gone, step -> {}, null);
                if (!YouTubeTranscriptClient.kept(activity, v.id())) {
                    String key = store.secrets.workKey();
                    if (key.isBlank()) why = firstLine(block);
                    else {
                        main.post(() -> chip.setText("Gemini가 받아 적는 중…"));
                        YouTubeTranscriptClient.writeDown(activity, v.id(), v.title(), v.channel(), key, () -> { if (host.gone()) throw new java.util.concurrent.CancellationException(); }, null);
                    }
                }
            } catch (java.util.concurrent.CancellationException stopped) { return; }
            catch (Exception e) { why = e.getMessage() == null || e.getMessage().isBlank() ? "대본을 가져오지 못했어요." : e.getMessage(); }
            String reason = why;
            main.post(() -> {
                chip.setEnabled(true); chip.setText("대본 보기");
                if (host.gone()) return;
                if (YouTubeTranscriptClient.kept(activity, v.id())) { if (video == v) transcript = null; YouTubeTranscriptUi.show(activity, MyTube.watch(v.id())); }
                else Toast.makeText(activity, "대본을 가져오지 못했어요" + (reason == null || reason.isBlank() ? "" : ": " + reason), Toast.LENGTH_LONG).show();
            });
        });
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
