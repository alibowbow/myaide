package dev.jun.gyeot;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.WakeStandby;
import dev.jun.gyeot.core.WakeWord;

/**
 * 차에서 호출어: a standby started from the app's settings (Android 14 lets a microphone service start only
 * while the app is in front). It holds the microphone only while the chosen car's Bluetooth is connected and no voice call
 * has it, matches the user's own recordings on the phone (core/WakeWord), and on "들퍼레" or "퍼레" releases the microphone
 * and opens the quick voice start the way the app opens its screens from the background (DeviceActionRunner.start), or posts
 * a tap-to-open notification when Android allows neither. Nothing it hears leaves the phone; the phone log keeps only a line.
 */
public final class WakeWordService extends Service {
    static final String START = "dev.jun.gyeot.WAKE_START", STOP = "dev.jun.gyeot.WAKE_STOP", REFRESH = "dev.jun.gyeot.WAKE_REFRESH";
    static final String CHANNEL = "wake";
    static final int NOTIFICATION = 4170;
    /** After a wake that was answered it does not wake again for this long; after one nobody answered, for longer (core/WakeRest). */
    static final long COOLDOWN_MS = dev.jun.gyeot.core.WakeRest.COOLDOWN_MS;
    private final dev.jun.gyeot.core.WakeRest rest = new dev.jun.gyeot.core.WakeRest();
    /** For tests: the last state applied. */
    static volatile WakeStandby.State current = WakeStandby.State.OFF;

    private AppStore store; private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable observer = this::apply;
    /** Looks again when a rest is over, or when a wake's call should have shown up by now. */
    private final Runnable again = this::apply;
    private BroadcastReceiver bluetooth;
    private volatile WakeWord.Model model; private boolean carConnected, foreground;
    private AudioRecord record; private Thread reader; private volatile boolean reading;
    /** connectedAt: when the chosen car last connected (0 when the standby started with it already connected). */
    private long wokeAt, connectedAt;

    static void start(Context context) { context.startForegroundService(new Intent(context, WakeWordService.class).setAction(START)); }
    static void stop(Context context) { context.startService(new Intent(context, WakeWordService.class).setAction(STOP)); }
    /** Settings changed (recordings, car, sensitivity): rebuilt while running. */
    static void refresh(Context context) { context.startService(new Intent(context, WakeWordService.class).setAction(REFRESH)); }

    @Override public void onCreate() {
        super.onCreate();
        store = ((GyeotApp) getApplication()).store();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "차에서 호출어", NotificationManager.IMPORTANCE_LOW));
        bluetooth = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (device == null || !car(device.getAddress())) return;
                carConnected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction());
                // A new drive (the car away long enough) starts afresh; a short drop of Bluetooth keeps this drive's rest.
                // Either way it first lets the sounds of connecting pass (WakeRest.SETTLE_MS).
                long now = SystemClock.elapsedRealtime();
                if (carConnected) { rest.connected(now); connectedAt = now; } else rest.disconnected(now);
                store.phoneLog(!carConnected ? "● 차 블루투스 끊김 · 호출어 마이크 끔"
                    : rest.restingForDrive(now) ? "● 차 블루투스 다시 연결 · 이번 운행 동안은 호출어를 쉬어요"
                    : "● 차 블루투스 연결 · 연결할 때 나는 소리가 지나도록 " + dev.jun.gyeot.core.WakeRest.words(dev.jun.gyeot.core.WakeRest.SETTLE_MS) + " 뒤부터 호출어를 들어요");
                apply();
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED); filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bluetooth, filter, Context.RECEIVER_EXPORTED);
        store.observe(observer);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? START : intent.getAction();
        if (STOP.equals(action)) { stopReading(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        try {
            startForeground(NOTIFICATION, notification(WakeStandby.State.WAITING_FOR_CAR), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true;
        } catch (RuntimeException refused) {
            // Started from the background (after the app was closed): Android refuses the microphone, so ask for a tap.
            store.phoneLog("● 호출어 대기를 시작하지 못했어요 · 앱을 한 번 열어 주세요");
            stopSelf(); return START_NOT_STICKY;
        }
        model = WakeWordFiles.model(this, WakeWordFiles.sensitivity(store));
        // A listener already running keeps the recordings and the 반응 정도 it started with: it starts again with these.
        stopReading();
        connectedNow();
        apply();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        store.unobserve(observer); main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(bluetooth); } catch (RuntimeException ignored) {}
        stopReading(); current = WakeStandby.State.OFF; super.onDestroy();
    }

    private boolean car(String address) { String chosen = store.settings.getString(WakeWordFiles.CAR, ""); return !chosen.isBlank() && chosen.equalsIgnoreCase(address); }
    private boolean permissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    /** Whether the chosen car is connected right now (the audio and call profiles), for a standby started in the car. */
    private void connectedNow() {
        if (!permissions()) return;
        BluetoothManager manager = getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
            try {
                adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int which, BluetoothProfile proxy) {
                        boolean found = false;
                        try { for (BluetoothDevice device : proxy.getConnectedDevices()) found |= car(device.getAddress()); } catch (SecurityException denied) {}
                        adapter.closeProfileProxy(which, proxy);
                        if (found) main.post(() -> { carConnected = true; apply(); });
                    }
                    @Override public void onServiceDisconnected(int which) {}
                }, profile);
            } catch (SecurityException denied) { return; }
        }
    }
    /** The one decision (WakeStandby), then the microphone and the notification follow it. */
    private void apply() {
        if (!foreground) return;
        boolean enabled = store.settings.getBoolean(WakeWordFiles.ENABLED, false);
        WakeStandby.State state = WakeStandby.state(enabled, model != null, !store.settings.getString(WakeWordFiles.CAR, "").isBlank(), permissions(), carConnected, store.voiceActive);
        if (state == WakeStandby.State.OFF) { stopReading(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); current = state; return; }
        // What became of the last wake: a conversation (a short rest from its end), or nothing said or no call at all (a
        // longer rest each time in a row).
        long now = SystemClock.elapsedRealtime();
        long began = rest.follow(now, store.voiceActive, store.lastCallAsked, store.lastCallStopped);
        if (began > 0 && rest.restingForDrive(now))
            store.phoneLog("● 호출어 쉼 · 요청 없이 끝난 호출이 이어져 이번 운행 동안은 듣지 않아요 · 차를 다시 연결하면 다시 들어요");
        else if (began > 0 && rest.unanswered() > 0)
            store.phoneLog("● 호출어 쉼 · 요청 없이 끝난 호출 · " + dev.jun.gyeot.core.WakeRest.words(began) + " 뒤 다시 들어요");
        else if (began > dev.jun.gyeot.core.WakeRest.COOLDOWN_MS)
            store.phoneLog("● 호출어 쉼 · 직접 꺼서 " + dev.jun.gyeot.core.WakeRest.words(began) + " 뒤 다시 들어요");
        if (WakeStandby.microphone(state) && !rest.resting(now)) startReading(); else stopReading();
        boolean drive = rest.restingForDrive(now);
        if (state != current || drive != shownDriveRest) { current = state; shownDriveRest = drive; getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(state)); }
        main.removeCallbacks(again);
        if (state == WakeStandby.State.LISTENING && rest.resting(now) && !drive) main.postDelayed(again, rest.left(now) + 50);
        else if (rest.pending() && !store.voiceActive) main.postDelayed(again, dev.jun.gyeot.core.WakeRest.NO_CALL_MS + 50);
    }
    /** The notification says so while the wake word rests for the rest of the drive. */
    private boolean shownDriveRest;
    private Notification notification(WakeStandby.State state) {
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        String text = shownDriveRest && state == WakeStandby.State.LISTENING ? "이번 운행 동안 호출어를 쉬어요 · 차를 다시 연결하면 다시 들어요"
            : WakeStandby.label(state, store.settings.getString(WakeWordFiles.CAR_NAME, ""));
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(brand() + " 호출어")
            .setContentText(text).setOngoing(true).setContentIntent(open).build();
    }
    private static String brand() { return dev.jun.gyeot.core.Brand.NAME; }

    private void startReading() {
        if (reading || model == null) return;
        int buffer = Math.max(AudioRecord.getMinBufferSize(WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), WakeWord.RATE);
        AudioRecord microphone;
        try { microphone = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWord.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer); }
        catch (SecurityException | IllegalArgumentException refused) { store.phoneLog("● 호출어 마이크를 열지 못했어요"); return; }
        if (microphone.getState() != AudioRecord.STATE_INITIALIZED) { microphone.release(); store.phoneLog("● 호출어 마이크를 열지 못했어요"); return; }
        record = microphone; reading = true;
        WakeWord.Listener listener = new WakeWord.Listener(model);
        reader = new Thread(() -> {
            short[] chunk = new short[1600];
            // The last few seconds, for 호출어 확인: the clip the match came from.
            short[] ring = new short[CLIP]; int at = 0; boolean full = false;
            try {
                microphone.startRecording();
                while (reading) {
                    int n = microphone.read(chunk, 0, chunk.length);
                    if (n <= 0) continue;
                    for (int i = 0; i < n; i++) { ring[at] = chunk[i]; at = (at + 1) % CLIP; if (at == 0) full = true; }
                    if (listener.feed(chunk, n)) {
                        double score = listener.lastScore(), threshold = listener.threshold();
                        int count = full ? CLIP : at; byte[] pcm = new byte[count * 2];
                        for (int i = 0; i < count; i++) { short v = ring[(full ? at + i : i) % CLIP]; pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8); }
                        main.post(() -> candidate(score, threshold, pcm)); break;
                    }
                }
            } catch (RuntimeException stopped) { /* released while reading */ }
        }, "gyeot-wake-word");
        reader.start();
    }
    private void stopReading() {
        reading = false; AudioRecord microphone = record; record = null;
        if (microphone != null) { try { microphone.stop(); } catch (RuntimeException ignored) {} microphone.release(); }
    }
    /** The clip kept for 호출어 확인: the last 2.6 seconds. */
    private static final int CLIP = WakeWord.RATE * 26 / 10;
    private volatile boolean verifying;
    /**
     * A match on the phone: while music plays the threshold is tighter; a match that is not plainly the user's own (or any
     * while music plays) is checked by Flash-Lite with the clip it came from (호출어 확인, on by default). A clear one, or one
     * the check could not answer in time, wakes as before; one the check says is lyrics or noise does not, and listening goes on.
     */
    private void candidate(double score, double threshold, byte[] pcm) {
        stopReading();
        android.media.AudioManager audio = getSystemService(android.media.AudioManager.class);
        boolean music = audio != null && audio.isMusicActive();
        if (music && score > threshold * WakeWord.MUSIC) {
            store.phoneLog(String.format(java.util.Locale.ROOT, "● 호출어 후보 넘김 · 음악 재생 중 · 점수 %.1f / 기준 %.1f", score, threshold * WakeWord.MUSIC));
            apply(); return;
        }
        boolean check = store.settings.getBoolean("wakeVerify", true) && (music || score > threshold * WakeWord.SURE);
        String key = "";
        try { key = store.secrets.workKey(); } catch (Exception ignored) {}
        if (!check || key.isBlank() || verifying) { woke(score, threshold); return; }
        verifying = true; String gemini = key; long started = SystemClock.elapsedRealtime();
        new Thread(() -> {
            Boolean said;
            try { said = GeminiAudioClient.heardWakeWord(gemini, dev.jun.gyeot.core.GeminiSpeech.wav(pcm, WakeWord.RATE), 4_500); }
            catch (Exception unanswered) { said = null; }
            Boolean answer = said; long took = SystemClock.elapsedRealtime() - started;
            main.post(() -> {
                verifying = false;
                if (Boolean.FALSE.equals(answer)) {
                    store.phoneLog(String.format(java.util.Locale.ROOT, "● 호출어 후보 넘김 · AI 확인: 노래나 잡음 · 점수 %.1f / 기준 %.1f · %dms", score, threshold, took) + (music ? " · 음악 재생 중" : ""));
                    apply(); return;
                }
                if (answer == null) store.phoneLog("● 호출어 AI 확인이 늦거나 안 돼서 그대로 깨웠어요 · " + took + "ms");
                woke(score, threshold);
            });
        }, "gyeot-wake-verify").start();
    }
    /** The wake word: the microphone is released first (the call needs it), then the quick voice start opens. */
    private void woke(double score, double threshold) {
        stopReading(); wokeAt = SystemClock.elapsedRealtime(); rest.woke(wokeAt);
        // How long after the car connected, so a wake by the car's own sounds shows in the log.
        String since = connectedAt <= 0 ? "" : " · 차 연결 " + dev.jun.gyeot.core.WakeRest.words(wokeAt - connectedAt) + " 뒤";
        store.phoneLog(String.format(java.util.Locale.ROOT, "● 호출어 들림 · 점수 %.1f / 기준 %.1f", score, threshold) + since);
        if (store.voiceActive) { apply(); return; }
        Intent quick = new Intent(this, QuickVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String route;
        try { route = DeviceActionRunner.start(this, quick, false); } catch (RuntimeException refused) { route = "notification"; }
        if (!DeviceActionRunner.started(route)) DeviceActionRunner.post(this, quick, "탭해서 " + brand() + " 부르기");
        apply();
    }
}
