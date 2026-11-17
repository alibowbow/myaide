package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import dev.jun.gyeot.core.DraftDictation;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One independent push-to-talk session owns one screen/room and unchanged draft. */
final class ComposerDictation {
    static final int PERMISSION_REQUEST = 7416;
    interface Listener {
        void ready(); void preview(String text); void complete(String text); void failed(String reason); void limitReached();
    }
    interface Session extends AutoCloseable {
        void start(Listener listener) throws Exception;
        void finish();
        @Override void close();
    }
    interface Factory { Session create() throws Exception; }
    /** What the dialog says for a recognizer: while it connects, once it listens, after a failure, and when it cannot start. */
    record Words(String waiting, String ready, String failedTail, String startFailed) {
        static final Words GEMINI = new Words("Gemini에 연결 중… 연결되면 말해 주세요. 오디오는 Gemini로 전송되고 초안에만 입력돼요.",
            "말씀하세요 · 최대 2분 또는 2MiB(약 2MB) 중 먼저 도달하는 제한까지. 완료 후 직접 보내 주세요.", " Google Speech나 다른 모델로 전환하지 않았어요.",
            "Gemini 음성 입력을 시작하지 못했어요. 키·연결·마이크 사용 여부를 확인해 주세요.");
        /** The phone's own recognizer (SpeechInput) on the device, with Korean installed: it stops by itself when the speech ends. */
        static final Words DEVICE = new Words("기기 음성 인식을 준비하고 있어요… 기기 안에서 알아듣고 초안에만 입력돼요.",
            "말씀하세요 · 말이 끝나면 저절로 멈춰요. 완료 후 직접 보내 주세요.", "", "기기 음성 인식을 시작하지 못했어요. 마이크 사용 여부를 확인해 주세요.");
        /** The phone's recognition service (SpeechInput, not known to be on-device): it may send the audio to its provider. */
        static final Words SERVICE = new Words("휴대폰 음성 인식을 준비하고 있어요… 오디오는 휴대폰의 음성 인식 서비스(보통 Google)로 전송될 수 있고 초안에만 입력돼요.",
            DEVICE.ready(), "", "휴대폰 음성 인식을 시작하지 못했어요. 마이크 사용 여부를 확인해 주세요.");
    }
    /** Every composer mic in the process (weakly held), so spoken output can stay quiet while one listens. Main thread only. */
    private static final java.util.Set<ComposerDictation> ALL = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Activity activity;
    private final EditText input;
    private final Supplier<String> scope;
    private final Consumer<String> notice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DraftDictation state = new DraftDictation();
    private DraftDictation.Ticket ticket;
    private Session session;
    private Factory factory;
    private Words words = Words.GEMINI;
    private AlertDialog dialog;
    private boolean closed;

    ComposerDictation(Activity activity, EditText input, Supplier<String> scope, Consumer<String> notice) {
        this.activity = activity; this.input = input; this.scope = scope; this.notice = notice;
        ALL.add(this);
    }
    /** Some composer mic is waiting for the microphone permission, recording or transcribing. */
    static boolean listening() {
        for (ComposerDictation one : ALL) if (!one.closed && one.state.state() != DraftDictation.State.IDLE) return true;
        return false;
    }
    void begin(Factory selected, String providerLabel) { begin(selected, providerLabel, Words.GEMINI); }
    void begin(Factory selected, String providerLabel, Words said) {
        if (closed || state.state() != DraftDictation.State.IDLE) return;
        if (selected == null) { notice.accept("음성 입력 제공자를 먼저 선택해 주세요."); return; }
        boolean granted = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        ticket = state.begin(scope.get(), input.getText().toString(), input.getSelectionStart(), granted);
        factory = selected; words = said == null ? Words.GEMINI : said;
        dialog = new AlertDialog.Builder(activity).setTitle("음성 입력 · " + providerLabel)
            .setMessage(granted ? words.waiting() : "마이크 권한을 기다리고 있어요.")
            .setPositiveButton("완료 · 초안에 넣기", null).setNegativeButton("취소", (d, which) -> cancel())
            .setOnCancelListener(d -> cancel()).create();
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> stop());
        if (granted) record();
        else activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
    }
    boolean permissionResult(int requestCode, int[] results) {
        if (requestCode != PERMISSION_REQUEST) return false;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (state.permission(ticket, scope.get(), granted)) record();
        else { cancel(); if (!granted) notice.accept("음성 입력에는 마이크 권한이 필요해요."); }
        return true;
    }
    private boolean current(DraftDictation.Ticket owner) { return !closed && state.owns(owner, scope.get()); }
    private void record() {
        final DraftDictation.Ticket owner = ticket;
        if (!current(owner)) { cancel(); return; }
        try {
            session = factory.create();
            session.start(new Listener() {
                @Override public void ready() { main.post(() -> {
                    if (!current(owner)) return;
                    dialog.setMessage(words.ready());
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                }); }
                @Override public void preview(String text) { main.post(() -> {
                    if (current(owner) && state.state() == DraftDictation.State.RECORDING)
                        dialog.setMessage("듣는 중…\n" + text + "\n\n완료하면 초안에만 넣어요.");
                }); }
                @Override public void complete(String text) { main.post(() -> {
                    if (!current(owner) || state.state() != DraftDictation.State.TRANSCRIBING) return;
                    String draft = state.complete(owner, scope.get(), input.getText().toString(), text);
                    release(); dismiss();
                    if (draft != null && exceedsInputLimit(draft)) { notice.accept("입력 가능한 글자 수를 넘어 음성을 추가하지 않았어요. 기존 초안은 그대로예요."); return; }
                    if (draft != null) { input.setText(draft); input.setSelection(input.length()); notice.accept("음성을 입력창에 넣었어요. 확인한 뒤 보내 주세요."); }
                    else notice.accept("입력 내용이 바뀌었거나 인식된 말이 없어 덮어쓰지 않았어요.");
                }); }
                @Override public void failed(String reason) { main.post(() -> {
                    if (!current(owner)) return;
                    String tail = words.failedTail();
                    cancel(); notice.accept(reason + tail);
                }); }
                @Override public void limitReached() { main.post(() -> { if (current(owner)) stop(); }); }
            });
        } catch (Exception error) { String why = words.startFailed(); cancel(); notice.accept(why); }
    }
    private void stop() {
        if (!state.stop(ticket, scope.get()) || session == null) return;
        dialog.setMessage("마지막 말을 확정하는 중… 취소하면 입력하지 않아요.");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        session.finish();
    }
    private boolean exceedsInputLimit(String draft) {
        for (android.text.InputFilter filter : input.getFilters())
            if (filter instanceof android.text.InputFilter.LengthFilter length && draft.length() > length.getMax()) return true;
        return false;
    }
    void validateScope() { if (ticket != null && !state.owns(ticket, scope.get())) cancel(); }
    void cancel() { state.cancel(); ticket = null; factory = null; release(); dismiss(); }
    /** The screen is pausing: cancel, unless Android's permission dialog, which pauses it, is what this mic is waiting on. */
    void pause() { if (state.state() != DraftDictation.State.PERMISSION) cancel(); }
    private void release() { Session current = session; session = null; if (current != null) current.close(); }
    private void dismiss() { AlertDialog shown = dialog; dialog = null; if (shown != null) shown.dismiss(); }
    void close() { closed = true; cancel(); }
}
