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
    private static final String KIND = "voiceChatKind", VOICE = "voiceChatVoice.";
    private final android.app.Activity activity; private final AppStore store; private final Ui ui;
    /** This screen (VoiceChat.Target.surface) and what a conversation started here talks about. */
    private final String surface; private final java.util.function.Supplier<VoiceChat.Target> target;
    private final Runnable listener = this::refresh;
    private final LinearLayout bar; private final VoiceOrb orb; private final TextView title, words; private final Ui.Dial mute;
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
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        mute = ui.dial(R.drawable.ic_mic, "마이크 끄기", false, () -> { VoiceChat c = store.voiceChat; if (c != null) c.toggleMute(); });
        bar.addView(mute);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(4);
        bar.addView(ui.dial(R.drawable.ic_close, "음성 대화 끝내기", true, () -> { VoiceChat c = store.voiceChat; if (c != null) c.end(""); }), gap);
    }
    View bar() { return bar; }
    /** What the bar stands in for while a conversation is on (the composer and what sits over it). */
    void aside(View... views) { aside = views; }
    void track(java.util.function.Consumer<android.app.Dialog> tracker) { this.tracker = tracker; }

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
        String engine = ScopedModels.main(store).engine();
        if (WorkBook.GROK.equals(engine) && usable(RealtimeVoice.Kind.GROK)) return RealtimeVoice.Kind.GROK;
        if (usable(RealtimeVoice.Kind.GPT)) return RealtimeVoice.Kind.GPT;
        return usable(RealtimeVoice.Kind.GROK) ? RealtimeVoice.Kind.GROK : RealtimeVoice.Kind.GPT;
    }
    /** Whether this voice has a sign-in or key to start with. */
    boolean usable(RealtimeVoice.Kind kind) {
        if (kind == RealtimeVoice.Kind.GPT)
            return store.secrets.has(SecretStore.OPENAI_VOICE) || store.codexImages.connected() || "key".equals(store.openai.describe().optString("mode"));
        return store.secrets.has(SecretStore.XAI_VOICE) || store.grok.connected();
    }
    private String voice(RealtimeVoice.Kind kind) { return kind.voice(store.settings.getString(VOICE + kind.name(), kind.voice)); }
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
    void attach() { VoiceChat c = store.voiceChat; if (c != null && mine(c)) { shown = c; c.listen(listener); } refresh(); }
    void detach() { VoiceChat c = store.voiceChat; if (c != null) c.unlisten(listener); }
    private boolean mine(VoiceChat c) { return surface.equals(c.target.surface()); }

    // ── the bar ──────────────────────────────────────────────────────────
    void refresh() {
        VoiceChat c = store.voiceChat; boolean on = c != null && mine(c);
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { for (View v : aside) if (v != null && !hidden.containsKey(v)) { hidden.put(v, v.getVisibility()); v.setVisibility(View.GONE); } }
        else { for (java.util.Map.Entry<View, Integer> was : hidden.entrySet()) was.getKey().setVisibility(was.getValue()); hidden.clear(); }
        if (!on) {
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
        title.setText(c.kind.spoken() + " · " + doing);
        String said = c.words();
        words.setText(said.isBlank() ? state == VoiceChat.State.CONNECTING ? "대화 기록과 기억을 이어서 연결해요" : "말씀하시면 대답해요 · 대화는 아래 기록에 남아요" : said);
        mute.on(c.muted() ? R.drawable.ic_mic_off : R.drawable.ic_mic, c.muted() ? "마이크 켜기" : "마이크 끄기", c.muted());
    }

    // ── settings ─────────────────────────────────────────────────────────
    /** Which voice talks, how it sounds and its sign-in; note says why it opened (a sign-in is missing or was refused). */
    void settings(String note) {
        Ui.Sheet sheet = ui.selectionSheet("음성 대화", note == null || note.isBlank() ? "메인 대화에서 GPT·Grok과 실시간으로 말해요. 말한 내용은 대화에 기록돼요." : note);
        sheet.primary.setVisibility(View.GONE); tracker.accept(sheet);
        RealtimeVoice.Kind current = kind();
        LinearLayout who = ui.group();
        String[] labels = {"GPT" + (usable(RealtimeVoice.Kind.GPT) ? "" : " · 로그인 필요"), "Grok" + (usable(RealtimeVoice.Kind.GROK) ? "" : " · 로그인 필요")};
        ui.addRow(who, ui.choiceRow("대화 상대", "음성 대화 상대", "실시간 음성으로 대답할 AI", labels, current.ordinal(), index -> {
            store.settings.edit().putString(KIND, RealtimeVoice.Kind.values()[index].name()).apply(); sheet.dismiss(); settings("");
        }), false);
        String[] voices = current.voices();
        int at = java.util.Arrays.asList(voices).indexOf(voice(current));
        ui.addRow(who, ui.choiceRow("목소리", current.label + " 목소리", "다음 음성 대화부터 적용돼요", voices, Math.max(0, at), index ->
            store.settings.edit().putString(VOICE + current.name(), voices[index]).apply()), false);
        sheet.body.addView(who);
        LinearLayout sign = ui.group();
        if (current == RealtimeVoice.Kind.GPT) {
            boolean codex = store.codexImages.connected();
            ui.addRow(sign, ui.listRow(0, 0, codex ? "Codex 로그인 됨" : "Codex로 로그인", codex ? "ChatGPT 구독으로 GPT 실시간 음성을 써요 · " + store.codexImages.describe().optString("label") : "ChatGPT 구독 계정으로 로그인하면 API 키 없이 써요",
                codex ? null : ui.chevron(), codex ? null : () -> { sheet.dismiss(); store.codexImages.signIn(); }), false);
            keyRow(sign, sheet, SecretStore.OPENAI_VOICE, "OpenAI API 키 (선택)", "넣으면 로그인 대신 이 키로 연결해요", "sk-로 시작하는 키");
        } else {
            boolean grok = store.grok.connected();
            ui.addRow(sign, ui.listRow(0, 0, grok ? "Grok 구독 로그인 됨" : "Grok 구독 계정으로 로그인", grok ? "구독 로그인으로 먼저 연결해요" : "설정 → Grok에서 로그인해요",
                grok ? null : ui.chevron(), grok ? null : () -> { sheet.dismiss(); store.grok.signIn(); }), false);
            keyRow(sign, sheet, SecretStore.XAI_VOICE, "xAI API 키 (선택)", "구독 로그인이 거절되면 이 키로 연결해요", "xai-로 시작하는 키");
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(12); sheet.body.addView(sign, p);
        TextView about = ui.text("음성 대화는 웹 검색이나 폰 조작을 하지 않아요. 그런 일은 글로 보내 주세요. 말하는 중에 말씀하시면 대답을 멈추고 들어요.", FOOTNOTE, 450, MUTED);
        about.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), 0); sheet.body.addView(about);
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
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
