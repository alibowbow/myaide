package dev.jun.gyeot;

import android.Manifest;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.RealtimeVoice;
import dev.jun.gyeot.core.WorkBook;
import static dev.jun.gyeot.Ui.*;

/**
 * 음성 대화 on a screen (the main chat, 작업창, the AI browser, 판서), as in the ChatGPT app: with an empty draft the send
 * button is the voice button; a tap talks with GPT or Grok (VoiceChat) about what is on that screen, a long press chooses
 * which, its voice and its sign-in (one choice for every screen). While it talks, a bar in the composer's place shows who
 * is speaking and what is said, with mute and 끝내기; what both said is written where the screen keeps its conversation.
 */
final class VoiceBar {
    static final int PERMISSION = 7433;
    static final String OFFER = "음성 대화 · GPT·Grok과 말로 대화해요 · 길게 누르면 설정";
    private static final String KIND = "voiceChatKind";
    private final android.app.Activity activity; private final AppStore store; private final Ui ui;
    /** This screen (VoiceChat.Target.surface) and what a conversation started here talks about. */
    private final String surface; private final java.util.function.Supplier<VoiceChat.Target> target;
    private final Runnable listener = this::refresh;
    private final LinearLayout bar; private final VoiceOrb orb; private final TextView title, words; private final Ui.Dial mute, replay;
    private View[] aside = new View[0];
    /** The views the bar stands in for, with how they were before it, while a conversation is on. */
    private final java.util.Map<View, Integer> hidden = new java.util.HashMap<>();
    private VoiceChat shown;
    private boolean startAfterPermission;
    private java.util.function.Consumer<android.app.Dialog> tracker = d -> {};

    VoiceBar(android.app.Activity activity, AppStore store, Ui ui, String surface, java.util.function.Supplier<VoiceChat.Target> target) {
        this.activity = activity; this.store = store; this.ui = ui; this.surface = surface; this.target = target;
        bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8));
        bar.setBackground(ui.shape(CARD, 28)); bar.setVisibility(View.GONE);
        orb = new VoiceOrb(activity); orb.setContentDescription("음성 대화 설정"); orb.setOnClickListener(v -> settings(""));
        bar.addView(orb, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout text = ui.column(); text.setPadding(ui.dp(10), 0, ui.dp(6), 0);
        title = ui.text("", SUBHEAD, 650, TEXT); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        words = ui.text("", FOOTNOTE, 450, MUTED); words.setMaxLines(2); words.setEllipsize(TextUtils.TruncateAt.START);
        text.addView(title); text.addView(words);
        // Words in another language: a tap shows them in Korean.
        words.setOnClickListener(v -> { VoiceChat c = store.voiceChat; if (c != null && dev.jun.gyeot.core.CaptionLanguage.foreign(c.words())) translate(c.words(), c.wordsBy() == VoiceChat.MINE); });
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        // 다시 듣기: the words on the bar in the voice they were said in (VoiceClips), while their sound is kept.
        replay = ui.dial(R.drawable.ic_play, "다시 듣기", false, () -> { VoiceChat c = store.voiceChat; if (c != null) VoiceClips.toggle(activity, store, c.wordsClip(), c.words()); });
        replay.setVisibility(View.GONE);
        bar.addView(replay);
        mute = ui.dial(R.drawable.ic_mic, "마이크 끄기", false, () -> { VoiceChat c = store.voiceChat; if (c != null) c.toggleMute(); });
        bar.addView(mute);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(4);
        bar.addView(ui.dial(R.drawable.ic_close, "음성 대화 끝내기", true, () -> { VoiceChat c = store.voiceChat; if (c != null) c.end(""); }), gap);
    }
    View bar() { return bar; }
    /** What the bar stands in for while a conversation is on (the composer and what sits over it). */
    void aside(View... views) { aside = views; }
    void track(java.util.function.Consumer<android.app.Dialog> tracker) { this.tracker = tracker; }

    /** A screen's own send button keeps its look; only its glyph and name follow (the voice button with nothing to send). */
    static void glyph(ImageView send, boolean offer, String sendName) {
        send.setImageResource(offer ? R.drawable.ic_wave : R.drawable.ic_send);
        send.setContentDescription(offer ? OFFER : sendName); send.setTooltipText(offer ? "음성 대화" : null);
    }
    /** The send button: the voice button while there is nothing to send. */
    void looks(ImageView send, boolean offer) {
        ui.sendLooks(send, false);
        if (offer) { send.setImageResource(R.drawable.ic_wave); send.setContentDescription(OFFER); send.setTooltipText("음성 대화"); }
        else { send.setContentDescription("메시지 보내기"); send.setTooltipText(null); }
    }

    // ── starting ─────────────────────────────────────────────────────────
    RealtimeVoice.Kind kind() {
        RealtimeVoice.Kind chosen = RealtimeVoice.Kind.of(store.settings.getString(KIND, ""));
        if (chosen != null) return chosen;
        // Not chosen here yet: the voice assistant's realtime voice, when it runs on one.
        RealtimeVoice.Kind assistant = store.assistantRealtime();
        if (assistant != null && usable(assistant)) return assistant;
        String engine = ScopedModels.main(store).engine();
        if (WorkBook.GROK.equals(engine) && usable(RealtimeVoice.Kind.GROK)) return RealtimeVoice.Kind.GROK;
        if (usable(RealtimeVoice.Kind.GPT)) return RealtimeVoice.Kind.GPT;
        return usable(RealtimeVoice.Kind.GROK) ? RealtimeVoice.Kind.GROK : RealtimeVoice.Kind.GPT;
    }
    /** Whether this voice has a sign-in or key to start with. */
    boolean usable(RealtimeVoice.Kind kind) { return VoiceCredentials.usable(store, kind); }
    /** One voice per kind for every screen and the voice assistant (AppStore.assistantVoice). */
    /** Not chosen yet, GPT talks in GPT-Live's Korean voice (한울); Realtime's marin stands in when GPT-Live does not open. */
    private String voice(RealtimeVoice.Kind kind) { return store.pickedVoice(kind); }
    void start() {
        VoiceChat elsewhere = store.voiceChat;
        if (elsewhere != null && mine(elsewhere)) return;
        // One microphone: a conversation on another screen ends here (what it said stays there).
        if (elsewhere != null) elsewhere.end("");
        if (store.voiceActive || store.recording) { toast("음성 비서 통화나 녹음을 끝낸 뒤 음성 대화를 시작해 주세요."); return; }
        if (store.talk != null && store.talk.busy()) store.talk.stop();
        RealtimeVoice.Kind kind = kind();
        if (!usable(kind)) { settings(kind == RealtimeVoice.Kind.GPT ? "GPT 음성 대화는 Codex 로그인(ChatGPT 구독)으로 해요. 아래에서 로그인해 주세요." : "Grok 음성 대화는 Grok 구독 로그인이나 xAI API 키로 해요."); return; }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true; activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION); return;
        }
        VoiceChat chat = new VoiceChat(activity, store, kind, voice(kind), target.get());
        store.voiceChat = chat; shown = chat; chat.listen(listener);
        chat.start(); refresh();
    }
    boolean permissionResult(int code, int[] results) {
        if (code != PERMISSION) return false;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (startAfterPermission && granted) { startAfterPermission = false; start(); }
        else if (!granted) toast("음성 대화에는 마이크 권한이 필요해요.");
        startAfterPermission = false; return true;
    }
    /** The page shows again: the bar follows the conversation that may have gone on meanwhile. */
    void attach() { VoiceChat c = store.voiceChat; if (c != null && mine(c)) { shown = c; c.listen(listener); } VoiceClips.listen(listener); refresh(); }
    void detach() { VoiceChat c = store.voiceChat; if (c != null) c.unlisten(listener); VoiceClips.unlisten(listener); }
    private boolean mine(VoiceChat c) { return surface.equals(c.target.surface()); }

    // ── the bar ──────────────────────────────────────────────────────────
    /** Whether the bar may show at all here now (the screen's tab is in front); the conversation goes on either way. */
    void enabled(boolean value) { if (enabled != value) { enabled = value; refresh(); } }
    private boolean enabled = true, wasOn;
    /** Called when the bar shows or hides, for a screen that lays out its composer itself. */
    void onToggle(Runnable toggled) { this.toggled = toggled; }
    private Runnable toggled;
    /** Whether the bar stands in the composer's place now. */
    boolean showing() { return wasOn; }
    void refresh() {
        VoiceChat c = store.voiceChat; boolean on = enabled && c != null && mine(c);
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on != wasOn) { wasOn = on; Runnable t = toggled; if (t != null) t.run(); }
        if (on) { for (View v : aside) if (v != null && !hidden.containsKey(v)) { hidden.put(v, v.getVisibility()); v.setVisibility(View.GONE); } }
        else { for (java.util.Map.Entry<View, Integer> was : hidden.entrySet()) was.getKey().setVisibility(was.getValue()); hidden.clear(); }
        if (!on) {
            if (c != null && mine(c)) return; // only hidden here (another tab is in front): still the same conversation
            VoiceChat ended = shown; shown = null;
            if (ended != null && !ended.failure().isEmpty()) { if (ended.needsSetup()) settings(ended.failure()); else toast(ended.failure()); }
            return;
        }
        shown = c;
        VoiceChat.State state = c.state();
        orb.state(c.muted() ? VoiceOrb.State.MUTED : switch (state) {
            case CONNECTING -> VoiceOrb.State.CONNECTING;
            case SPEAKING -> VoiceOrb.State.SPEAKING;
            case THINKING -> VoiceOrb.State.THINKING;
            case HEARING, LISTENING -> VoiceOrb.State.LISTENING;
            case ENDED -> VoiceOrb.State.IDLE;
        });
        String doing = c.muted() ? "마이크 꺼짐" : switch (state) {
            case CONNECTING -> "연결하는 중…";
            case LISTENING -> "듣고 있어요";
            case HEARING -> "듣는 중";
            case THINKING -> "생각하는 중";
            case SPEAKING -> "말하는 중 · 말씀하시면 멈춰요";
            case ENDED -> "끝났어요";
        };
        // The voice that really speaks (a GPT-Live voice, or the Realtime one standing in for it).
        title.setText(c.kind.label + " · " + c.speaking() + " · " + doing);
        title.setContentDescription(c.kind.spoken() + " " + c.speaking() + " · " + doing);
        String said = c.words();
        // Whose words they are, at a glance: the user's in the accent after 나, the AI's in the text color, notes muted.
        boolean spoken = !c.held() && !said.isBlank(); int by = spoken ? c.wordsBy() : VoiceChat.NOTE;
        words.setText(c.held() ? "화면이 말하는 동안 잠시 듣지 않아요" : said.isBlank() ? state == VoiceChat.State.CONNECTING ? "대화 기록과 기억을 이어서 연결해요"
            : "말씀하시면 대답해요 · 대화는 " + c.target.label() + "에 남아요" : by == VoiceChat.MINE ? "나 · " + said : said);
        words.setTextColor(by == VoiceChat.MINE ? ACCENT : by == VoiceChat.THEIRS ? TEXT : MUTED);
        boolean foreign = spoken && by != VoiceChat.NOTE && dev.jun.gyeot.core.CaptionLanguage.foreign(said);
        words.setClickable(foreign);
        String clip = c.wordsClip(); boolean replayable = spoken && by != VoiceChat.NOTE && VoiceClips.has(clip), now = VoiceClips.playing(clip);
        replay.setVisibility(replayable || now ? View.VISIBLE : View.GONE);
        replay.on(now ? R.drawable.ic_stop : R.drawable.ic_play, now ? "다시 듣기 멈추기" : by == VoiceChat.MINE ? "내 말 다시 듣기" : "다시 듣기 · 말한 목소리 그대로", now);
        words.setContentDescription((by == VoiceChat.MINE ? "내 말: " : by == VoiceChat.THEIRS ? c.speaking() + "의 말: " : "") + words.getText() + (foreign ? ", 눌러서 한국어로 보기" : ""));
        mute.on(c.muted() ? R.drawable.ic_mic_off : R.drawable.ic_mic, c.muted() ? "마이크 켜기" : "마이크 끄기", c.muted());
    }

    /** The words in Korean, in a sheet: the original, then the translation once it comes (CaptionTranslator). */
    private void translate(String said, boolean mine) {
        Ui.Sheet sheet = ui.selectionSheet("한국어로 보기", mine ? "내가 한 말" : "AI가 한 말");
        sheet.primary.setVisibility(View.GONE); tracker.accept(sheet);
        TextView original = ui.text(said, BODY, 450, MUTED); original.setTextIsSelectable(true); original.setLineSpacing(ui.dp(3), 1);
        TextView korean = ui.text("한국어로 옮기는 중…", BODY, 500, TEXT); korean.setTextIsSelectable(true); korean.setLineSpacing(ui.dp(3), 1);
        korean.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sheet.body.addView(original);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(12); sheet.body.addView(korean, gap);
        CaptionTranslator.translate(store, said, done -> { if (sheet.isShowing()) korean.setText(done); }, why -> { if (sheet.isShowing()) { korean.setText(why); korean.setTextColor(MUTED); } });
        sheet.show();
    }

    // ── settings ─────────────────────────────────────────────────────────
    /** Which voice talks, how it sounds and its sign-in; note says why it opened (a sign-in is missing or was refused). */
    void settings(String note) {
        VoiceChat.Target here = target.get();
        Ui.Sheet sheet = ui.selectionSheet("음성 대화", note == null || note.isBlank() ? here.label() + "에서 GPT·Grok과 실시간으로 말해요. 고른 상대는 모든 화면에, 목소리는 음성 비서에도 같이 적용돼요." : note);
        sheet.primary.setVisibility(View.GONE); tracker.accept(sheet);
        RealtimeVoice.Kind current = kind();
        LinearLayout who = ui.group();
        String[] labels = {"GPT" + (usable(RealtimeVoice.Kind.GPT) ? "" : " · 로그인 필요"), "Grok" + (usable(RealtimeVoice.Kind.GROK) ? "" : " · 로그인 필요")};
        ui.addRow(who, ui.choiceRow("대화 상대", "음성 대화 상대", "실시간 음성으로 대답할 AI", labels, current.ordinal(), index -> {
            store.settings.edit().putString(KIND, RealtimeVoice.Kind.values()[index].name()).apply(); sheet.dismiss();
            if (!restart()) settings("");
        }), false);
        // GPT: GPT-Live's newest voices first (한울, 햇살…), then the Realtime voices.
        java.util.List<String> ids = new java.util.ArrayList<>(), names = new java.util.ArrayList<>();
        for (String[] v : RealtimeVoice.voiceChoices(current)) { ids.add(v[0]); names.add(v[1]); }
        int at = ids.indexOf(voice(current));
        ui.addRow(who, ui.choiceRow("목소리", current.label + " 목소리", current == RealtimeVoice.Kind.GPT
                ? "‘최신’ 목소리는 GPT-Live로 말해요. 열리지 않으면 다른 목소리로 바꾸지 않고 이유를 알려 줘요. 음성 비서와 판서도 같은 목소리를 써요." : "대화 중이면 바로 이 목소리로 다시 연결해요",
            names.toArray(new String[0]), Math.max(0, at), index -> {
                store.settings.edit().putString(AppStore.ASSISTANT_VOICE + current.name(), ids.get(index)).apply();
                if (talking()) { sheet.dismiss(); restart(); }
            }), false);
        sheet.body.addView(who);
        // 화면 보기: the same permission as the voice assistant's; asked aloud, the voice looks at the screen once.
        LinearLayout look = ui.group();
        ui.addRow(look, ui.switchRow("화면 보기", "“화면 봐 줘”라고 하면 지금 화면 한 장을 보고 이야기해요", store.screenView(), value -> {
            store.settings.edit().putBoolean("screenView", value).apply(); store.changed();
            toast(value ? "화면 보기를 켰어요 · " + VoiceScreen.reach() : "화면 보기를 껐어요");
        }), false);
        LinearLayout.LayoutParams lookGap = new LinearLayout.LayoutParams(-1, -2); lookGap.topMargin = ui.dp(12); sheet.body.addView(look, lookGap);
        LinearLayout sign = ui.group();
        if (current == RealtimeVoice.Kind.GPT) {
            boolean codex = store.codexImages.connected();
            ui.addRow(sign, ui.listRow(0, 0, codex ? "Codex 로그인 됨" : "Codex로 로그인", codex ? "ChatGPT 구독으로 GPT 음성(최신·실시간)을 써요 · " + store.codexImages.describe().optString("label") : "ChatGPT 구독 계정으로 로그인하면 API 키 없이 써요",
                codex ? null : ui.chevron(), codex ? null : () -> { sheet.dismiss(); store.codexImages.signIn(); }), false);
            keyRow(sign, sheet, SecretStore.OPENAI_VOICE, "OpenAI API 키 (선택)", "넣으면 로그인 대신 이 키로 연결해요", "sk-로 시작하는 키");
        } else {
            boolean grok = store.grok.connected();
            ui.addRow(sign, ui.listRow(0, 0, grok ? "Grok 구독 로그인 됨" : "Grok 구독 계정으로 로그인", grok ? "구독 로그인으로 먼저 연결해요" : "설정 → Grok에서 로그인해요",
                grok ? null : ui.chevron(), grok ? null : () -> { sheet.dismiss(); store.grok.signIn(); }), false);
            keyRow(sign, sheet, SecretStore.XAI_VOICE, "xAI API 키 (선택)", "구독 로그인이 거절되면 이 키로 연결해요", "xai-로 시작하는 키");
        }
        if (current == RealtimeVoice.Kind.GPT) ui.addRow(sign, ui.listRow(0, 0, "최신 음성 연결 시험", "GPT-Live(한울·햇살)가 열리는지 단계별로 확인해요 · 마이크는 쓰지 않아요", ui.chevron(), () -> { sheet.dismiss(); liveTest(); }), false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(12); sheet.body.addView(sign, p);
        TextView about = ui.text(here.about() + " 말하는 중에 말씀하시면 대답을 멈추고 들어요.", FOOTNOTE, 450, MUTED);
        about.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0); sheet.body.addView(about);
        sheet.show();
    }
    /**
     * 최신 음성 연결 시험: a GPT-Live call without the microphone, each step shown as it happens. Closing the sheet ends
     * the call.
     */
    private void liveTest() {
        String voice = RealtimeVoice.live(store.pickedVoice(RealtimeVoice.Kind.GPT)) ? store.pickedVoice(RealtimeVoice.Kind.GPT) : "hanul";
        Ui.Sheet sheet = ui.selectionSheet("최신 음성 연결 시험", RealtimeVoice.voiceName(voice) + " · 단계마다 결과가 아래에 쌓여요. 결과를 복사해서 보내 주시면 바로 고칠게요.");
        tracker.accept(sheet);
        TextView log = ui.text("", FOOTNOTE, 450, TEXT); log.setTextIsSelectable(true); log.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        sheet.body.addView(log);
        StringBuilder lines = new StringBuilder();
        java.util.function.Consumer<String> add = text -> activity.runOnUiThread(() -> {
            lines.append(lines.length() == 0 ? "" : "\n").append("• ").append(text); log.setText(lines);
            store.phoneLog("  · 최신 음성 시험 · " + text);
        });
        sheet.primary.setText("복사"); sheet.primary.setOnClickListener(v -> {
            android.content.ClipboardManager clip = activity.getSystemService(android.content.ClipboardManager.class);
            if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText("최신 음성 연결 시험", lines.toString())); toast("복사했어요.");
        });
        LiveCall call = LiveCall.test(activity, store, voice, add, ok -> {
            add.accept(ok ? "성공 · 이제 최신 음성으로 대화할 수 있어요" : "실패 · 위 기록이 막힌 곳이에요");
        });
        sheet.setOnDismissListener(d -> call.close());
        sheet.show();
    }
    private void keyRow(LinearLayout group, Ui.Sheet parent, String name, String title, String subtitle, String hint) {
        boolean saved = store.secrets.has(name);
        ui.addRow(group, ui.listRow(0, 0, title, saved ? "저장됨 · 눌러서 바꾸거나 지우기" : subtitle, ui.chevron(), () -> {
            parent.dismiss();
            Ui.Sheet sheet = ui.secretSheet(title, saved ? "비워 두고 저장하면 지워요" : subtitle, hint, 300, "저장", (s, field) -> {
                String value = field.getText().toString().strip();
                if (!value.isEmpty() && (value.length() < 16 || value.matches("(?s).*\\s.*"))) { field.setError("API 키 형식을 확인해 주세요."); return; }
                try { store.secrets.put(name, value); s.dismiss(); toast(value.isEmpty() ? "키를 지웠어요." : "키를 저장했어요."); }
                catch (Exception e) { field.setError(e.getMessage() == null ? "키를 저장하지 못했어요." : e.getMessage()); }
            });
            tracker.accept(sheet); sheet.show();
        }), false);
    }
    /** Whether a conversation of this screen is on now. */
    private boolean talking() { VoiceChat c = store.voiceChat; return c != null && mine(c) && c.state() != VoiceChat.State.ENDED; }
    /**
     * The partner or voice changed while this screen talks: the conversation ends (what was said stays) and starts again at
     * once with the new choice. False when nothing was on.
     */
    private boolean restart() {
        if (!talking()) return false;
        store.voiceChat.end(""); start(); return true;
    }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
