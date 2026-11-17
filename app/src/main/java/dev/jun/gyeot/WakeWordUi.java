package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.WakeStandby;
import dev.jun.gyeot.core.WakeWord;
import java.util.ArrayList;
import java.util.List;

/**
 * 설정 → 차에서 호출어: turn it on, choose the car, record the user's own "들퍼레" and "퍼레", pick how
 * easily it wakes, and try it here. Recording and the test listen only while this page asks; the standby starts from here,
 * while the app is in front, as Android requires for a microphone service.
 */
final class WakeWordUi {
    static final int PERMISSION_REQUEST = 24;
    /** One recording: this long, from the moment the dialog says to speak. */
    static final int RECORD_MS = 2200, TEST_MS = 10_000;
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable refresh;
    private final Handler main = new Handler(Looper.getMainLooper());
    WakeWordUi(Activity activity, Ui ui, AppStore store, Runnable refresh) { this.activity = activity; this.ui = ui; this.store = store; this.refresh = refresh; }

    static String summary(Activity activity, AppStore store) {
        if (!store.settings.getBoolean(WakeWordFiles.ENABLED, false)) return "꺼짐 · 차 블루투스가 연결되면 “들퍼레”로 부르기";
        return WakeStandby.label(WakeWordService.current == WakeStandby.State.OFF ? WakeStandby.State.WAITING_FOR_CAR : WakeWordService.current, store.settings.getString(WakeWordFiles.CAR_NAME, ""));
    }
    private boolean permitted() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private void ask() { activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST); }
    void permissionResult() { if (permitted() && store.settings.getBoolean(WakeWordFiles.ENABLED, false)) WakeWordService.start(activity); refresh.run(); }

    /**
     * The page, in the order it is set up: the switch first (it says what the standby is doing),
     * then 1단계 the car, 2단계 the recordings, 3단계 a test and how easily it wakes; erasing the recordings comes last.
     */
    View page() {
        LinearLayout page = ui.column();
        TextView about = ui.text("차 블루투스가 연결된 동안에만 마이크를 켜고, 내 목소리로 녹음한 “들퍼레”·“퍼레”가 들리면 음성 대화를 시작해요.", 14, 400, Ui.MUTED);
        about.setPadding(ui.dp(16), 0, ui.dp(16), 0); ui.add(page, about, 12);
        boolean ready = WakeWordFiles.complete(activity) && !store.settings.getString(WakeWordFiles.CAR, "").isBlank(), enabled = store.settings.getBoolean(WakeWordFiles.ENABLED, false);
        String carName = store.settings.getString(WakeWordFiles.CAR_NAME, "");
        LinearLayout use = ui.group();
        ui.addRow(use, ui.switchRow("차에서 호출어 쓰기", !ready ? "먼저 아래 1·2단계를 마쳐 주세요"
            : enabled ? WakeStandby.label(WakeWordService.current == WakeStandby.State.OFF ? WakeStandby.State.WAITING_FOR_CAR : WakeWordService.current, carName) : "꺼져 있어요", enabled, on -> {
            if (on && !ready) { store.error("먼저 차를 고르고 “들퍼레”·“퍼레”를 녹음해 주세요."); refresh.run(); return; }
            store.settings.edit().putBoolean(WakeWordFiles.ENABLED, on).apply();
            if (!on) { WakeWordService.stop(activity); store.status("차에서 호출어를 껐어요"); refresh.run(); return; }
            if (!permitted()) { ask(); return; }
            WakeWordService.start(activity); store.status("호출어 대기를 시작했어요 · 차가 연결되면 들어요"); refresh.run();
        }), false);
        page.addView(use);
        page.addView(ui.footer("소리는 폰 안에서만 비교하고 어디에도 보내지 않아요."));

        page.addView(ui.sectionHeader("1단계 · 내 차"));
        LinearLayout car = ui.group();
        ui.addRow(car, ui.listRow(R.drawable.ic_plug, Ui.BLUE, "차 블루투스", carName.isBlank() ? "골라 주세요" : carName, ui.chevron(), this::chooseCar), true);
        page.addView(car);

        page.addView(ui.sectionHeader("2단계 · 내 목소리로 녹음"));
        LinearLayout words = ui.group();
        for (int w = 0; w < WakeWordFiles.WORDS.length; w++) {
            int word = w, have = WakeWordFiles.count(activity, w), need = WakeWordFiles.COUNTS[w];
            ui.addRow(words, ui.listRow(R.drawable.ic_mic, have >= need ? Ui.GREEN : Ui.ORANGE, "“" + WakeWordFiles.WORDS[w] + "” 녹음", have + " / " + need + (have >= need ? " · 완료" : " · 눌러서 이어 녹음"), ui.chevron(),
                () -> record(word, have >= need ? 0 : have)), true);
        }
        page.addView(words);
        page.addView(ui.footer("차 안처럼 평소 쓰는 목소리 크기로, 한 번에 한 단어씩 말해 주세요. 조용한 곳에서 녹음하면 더 잘 알아들어요."));

        page.addView(ui.sectionHeader("3단계 · 시험하고 맞추기"));
        LinearLayout test = ui.group();
        ui.addRow(test, ui.listRow(R.drawable.ic_wave, Ui.PURPLE, "지금 말해 보기", "10초 동안 들으며 점수를 보여 줘요", ui.chevron(), this::test), true);
        String[] steps = {"1 · 엄격하게 (잘못 켜짐 적음)", "2 · 조금 엄격하게", "3 · 보통", "4 · 조금 너그럽게", "5 · 너그럽게 (잘 못 알아들을 때)"};
        ui.addRow(test, ui.choiceRow("반응 정도", "반응 정도", "기본은 3 · 보통이에요", steps, WakeWordFiles.sensitivity(store) - 1, position -> {
            store.settings.edit().putInt(WakeWordFiles.SENSITIVITY, position + 1).apply();
            if (store.settings.getBoolean(WakeWordFiles.ENABLED, false)) WakeWordService.refresh(activity);
        }), false);
        page.addView(test);
        ui.add(page, ui.footer("잘 못 알아들으면 숫자를 크게, 잘못 켜지면 작게 해 주세요."), 16);

        // Alone in the last group and asked first: without the recordings the wake word turns off too.
        LinearLayout erase = ui.group();
        ui.addRow(erase, ui.dangerRow("녹음 모두 지우기", "지우면 호출어도 꺼져요", () -> ui.confirm("호출어 녹음을 모두 지울까요? 호출어도 함께 꺼져요.", "지우기", () -> {
            WakeWordFiles.clear(activity); store.settings.edit().putBoolean(WakeWordFiles.ENABLED, false).apply(); WakeWordService.stop(activity); refresh.run();
        }).show()), false);
        page.addView(erase);
        page.addView(ui.footer("듣는 동안에는 Android의 마이크 표시가 켜져 있어요. 폰을 다시 켠 뒤에는 " + Brand.NAME + " 앱을 한 번 열어야 호출어 대기가 다시 시작돼요."));
        return page;
    }

    /** The car is one of the phone's paired Bluetooth devices, chosen from a sheet; the one chosen before is checked. */
    private void chooseCar() {
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { ask(); return; }
        BluetoothManager manager = activity.getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { store.error("이 폰에서 블루투스를 찾지 못했어요."); return; }
        List<BluetoothDevice> devices = new ArrayList<>(); List<String> names = new ArrayList<>();
        String chosen = store.settings.getString(WakeWordFiles.CAR, ""); int current = -1;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (device.getAddress().equals(chosen)) current = devices.size();
                devices.add(device); String name = device.getName(); names.add(name == null || name.isBlank() ? device.getAddress() : name);
            }
        } catch (SecurityException denied) { ask(); return; }
        if (devices.isEmpty()) { store.error("연결해 둔 블루투스 기기가 없어요. 먼저 폰 설정에서 차와 연결해 주세요."); return; }
        ui.choices("어느 차인가요?", "폰에 연결해 둔 블루투스 기기예요", names.toArray(new String[0]), current, which -> {
            store.settings.edit().putString(WakeWordFiles.CAR, devices.get(which).getAddress()).putString(WakeWordFiles.CAR_NAME, names.get(which)).apply();
            if (store.settings.getBoolean(WakeWordFiles.ENABLED, false)) WakeWordService.refresh(activity);
            refresh.run();
        }).show();
    }

    /** Records one sample of a word and the rest that word still needs, one dialog each. */
    private void record(int word, int index) {
        if (!permitted()) { ask(); return; }
        if (store.voiceActive) { store.error("음성 대화 중에는 녹음할 수 없어요. 대화를 끝낸 뒤 다시 눌러 주세요."); return; }
        TextView line = ui.label("‘" + WakeWordFiles.WORDS[word] + "’라고 한 번 말해 주세요 (" + (index + 1) + " / " + WakeWordFiles.COUNTS[word] + ")", 16, Ui.TEXT);
        line.setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(8));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("듣는 중…").setView(line).setCancelable(false).show();
        new Thread(() -> {
            short[] pcm = capture(RECORD_MS);
            String problem = null;
            if (pcm == null) problem = "마이크를 열지 못했어요.";
            else if (WakeWord.enrolled(pcm) == null) problem = "말소리를 알아듣지 못했어요. 조금 더 또렷하게 다시 말해 주세요.";
            else try { WakeWordFiles.save(activity, word, index, pcm); } catch (Exception e) { problem = "녹음을 저장하지 못했어요."; }
            String reason = problem;
            main.post(() -> {
                dialog.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (reason != null) { store.error(reason); refresh.run(); return; }
                if (index + 1 < WakeWordFiles.COUNTS[word]) { record(word, index + 1); return; }
                store.status("“" + WakeWordFiles.WORDS[word] + "” 녹음을 마쳤어요");
                if (store.settings.getBoolean(WakeWordFiles.ENABLED, false)) WakeWordService.refresh(activity);
                refresh.run();
            });
        }, "gyeot-wake-record").start();
    }

    /** Listens for TEST_MS with the recordings and shows each score against the threshold. */
    private void test() {
        if (!permitted()) { ask(); return; }
        WakeWord.Model model = WakeWordFiles.model(activity, WakeWordFiles.sensitivity(store));
        if (model == null) { store.error("먼저 “들퍼레”·“퍼레”를 녹음해 주세요."); return; }
        if (store.voiceActive) { store.error("음성 대화 중에는 시험할 수 없어요."); return; }
        TextView line = ui.label("“들퍼레” 또는 “퍼레”라고 말해 보세요.", 16, Ui.TEXT); line.setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(8));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("시험 중 · 10초").setView(line).setNegativeButton("그만", null).show();
        WakeWord.Listener listener = new WakeWord.Listener(model);
        boolean[] stop = {false}; dialog.setOnDismissListener(d -> stop[0] = true);
        new Thread(() -> {
            AudioRecord microphone = open(); if (microphone == null) { main.post(() -> { dialog.dismiss(); store.error("마이크를 열지 못했어요."); }); return; }
            short[] chunk = new short[1600]; long until = System.currentTimeMillis() + TEST_MS; int heard = 0;
            try {
                microphone.startRecording();
                while (!stop[0] && System.currentTimeMillis() < until) {
                    int n = microphone.read(chunk, 0, chunk.length); if (n <= 0) continue;
                    boolean woke = listener.feed(chunk, n);
                    double score = listener.lastScore();
                    if (!Double.isNaN(score)) {
                        if (woke) heard++;
                        String text = (woke ? "들렸어요! " : "아직이에요 · ") + String.format(java.util.Locale.ROOT, "점수 %.1f (기준 %.1f 이하면 반응)", score, listener.threshold()) + (heard > 0 ? " · 반응 " + heard + "번" : "");
                        main.post(() -> line.setText(text));
                    }
                }
            } finally { try { microphone.stop(); } catch (RuntimeException ignored) {} microphone.release(); }
            main.post(() -> { if (dialog.isShowing()) dialog.setTitle("시험 끝"); });
        }, "gyeot-wake-test").start();
    }
    private static AudioRecord open() {
        int buffer = Math.max(AudioRecord.getMinBufferSize(WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), WakeWord.RATE);
        try {
            AudioRecord microphone = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
            if (microphone.getState() == AudioRecord.STATE_INITIALIZED) return microphone;
            microphone.release(); return null;
        } catch (SecurityException | IllegalArgumentException refused) { return null; }
    }
    /** RECORD_MS of microphone audio, or null when the microphone could not open. */
    private static short[] capture(int millis) {
        AudioRecord microphone = open(); if (microphone == null) return null;
        short[] out = new short[WakeWord.RATE * millis / 1000]; int at = 0;
        try { microphone.startRecording(); while (at < out.length) { int n = microphone.read(out, at, out.length - at); if (n <= 0) break; at += n; } }
        finally { try { microphone.stop(); } catch (RuntimeException ignored) {} microphone.release(); }
        return at < out.length / 2 ? null : out;
    }
}
