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
    private long wokeAt;

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
                if (carConnected) rest.connected(SystemClock.elapsedRealtime()); else rest.disconnected(SystemClock.elapsedRealtime());
                store.phoneLog(carConnected ? "● 차 블루투스 연결 · 호출어 듣기 시작" : "● 차 블루투스 끊김 · 호출어 마이크 끔");
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
            try {
                microphone.startRecording();
                while (reading) {
                    int n = microphone.read(chunk, 0, chunk.length);
                    if (n <= 0) continue;
                    if (listener.feed(chunk, n)) { double score = listener.lastScore(), threshold = listener.threshold(); main.post(() -> woke(score, threshold)); break; }
                }
            } catch (RuntimeException stopped) { /* released while reading */ }
        }, "gyeot-wake-word");
        reader.start();
    }
    private void stopReading() {
        reading = false; AudioRecord microphone = record; record = null;
        if (microphone != null) { try { microphone.stop(); } catch (RuntimeException ignored) {} microphone.release(); }
    }
    /** The wake word: the microphone is released first (the call needs it), then the quick voice start opens. */
    private void woke(double score, double threshold) {
        stopReading(); wokeAt = SystemClock.elapsedRealtime(); rest.woke(wokeAt);
        store.phoneLog(String.format(java.util.Locale.ROOT, "● 호출어 들림 · 점수 %.1f / 기준 %.1f", score, threshold));
        if (store.voiceActive) { apply(); return; }
        Intent quick = new Intent(this, QuickVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String route;
        try { route = DeviceActionRunner.start(this, quick, false); } catch (RuntimeException refused) { route = "notification"; }
        if (!DeviceActionRunner.started(route)) DeviceActionRunner.post(this, quick, "탭해서 " + brand() + " 부르기");
        apply();
    }
}
