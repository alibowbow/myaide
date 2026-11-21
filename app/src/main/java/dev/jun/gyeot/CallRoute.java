package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import java.util.Comparator;

/**
 * A spoken conversation's sound route, as a call's: the phone in communication mode (so its echo cancelling hears what is
 * played and takes it out of the microphone), a headset (Bluetooth, then wired) before the phone's own speaker, and
 * everything as it was afterwards. Used by GPT-Live's call and by a realtime conversation's speaker.
 */
final class CallRoute {
    private final AudioManager manager; private final Handler handler;
    private int previousMode; private boolean started;
    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] values) { select(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] values) { select(); }
    };
    CallRoute(Context context, Handler handler) { manager = context.getSystemService(AudioManager.class); this.handler = handler; }
    void begin() {
        if (manager == null || started) return;
        try {
            previousMode = manager.getMode(); started = true;
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            manager.registerAudioDeviceCallback(devices, handler); select();
        } catch (RuntimeException refused) { /* Android's own route stays. */ }
    }
    static int priority(int type) {
        return switch (type) {
            case AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 2;
            default -> 99;
        };
    }
    private void select() {
        if (!started) return;
        try {
            AudioDeviceInfo preferred = manager.getAvailableCommunicationDevices().stream().filter(d -> priority(d.getType()) < 99)
                .min(Comparator.comparingInt(d -> priority(d.getType()))).orElse(null);
            if (preferred != null && !manager.setCommunicationDevice(preferred)) manager.clearCommunicationDevice();
        } catch (RuntimeException ignored) { /* Android's own route stays. */ }
    }
    void close() {
        if (!started) return;
        started = false;
        try { manager.unregisterAudioDeviceCallback(devices); manager.clearCommunicationDevice(); } catch (RuntimeException ignored) {}
        // A phone call's or another app's new mode is left alone.
        if (manager.getMode() == AudioManager.MODE_IN_COMMUNICATION) manager.setMode(previousMode);
    }
}
