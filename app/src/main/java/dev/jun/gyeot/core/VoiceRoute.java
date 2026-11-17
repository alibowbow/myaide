package dev.jun.gyeot.core;

import java.util.List;

/**
 * Where the assistant's voice comes out, as the echo guard needs to know it: only into the user's
 * ears (a headset: the microphone cannot hear it, so the user may talk over the answer), or into the room or the car (the
 * microphone hears it, so its frames are held back while the answer plays, or the model hears itself, takes it for the
 * user, cuts its own answer short and answers again).
 *
 * Bluetooth is not one kind of thing. Earphones and a car both connect with the same audio profiles, and what tells them
 * apart is the device's own class (headphones, a wearable headset; or hands-free, car audio, a loudspeaker). A car plays
 * the answer on its speakers, so it is not treated as earphones.
 * Pure Java; no Android.
 */
public final class VoiceRoute {
    private VoiceRoute() {}
    /** WIRED: a wired headset or headphones. HEADSET: a USB or BLE headset, a hearing aid. BLUETOOTH: an A2DP or SCO device. */
    public enum Kind { WIRED, HEADSET, BLUETOOTH, OTHER }
    /**
     * One connected output. bluetoothClass: the Bluetooth device class (BluetoothClass.getDeviceClass), -1 when Android does
     * not tell it (no permission, no address). car: the device the user named as the car for the wake word.
     */
    public record Output(Kind kind, int bluetoothClass, boolean car) {}
    /** Bluetooth device classes worn on the head (android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_*). */
    public static final int WEARABLE_HEADSET = 0x0404, HEADPHONES = 0x0418;
    /** Echo reaches the microphone for longer through a Bluetooth speaker or a car: its own delay comes on top of the room's. */
    public static final long ROOM_TAIL_MS = 350, BLUETOOTH_TAIL_MS = 900;

    /** Whether a Bluetooth output is worn: its class says so, and it is not the user's car. An unknown class is not. */
    public static boolean earphones(Output output) {
        return output.kind() == Kind.BLUETOOTH && !output.car() && (output.bluetoothClass() == WEARABLE_HEADSET || output.bluetoothClass() == HEADPHONES);
    }
    /** A Bluetooth output that plays into the room or the car: any that is not earphones. */
    public static boolean loudBluetooth(List<Output> outputs) {
        for (Output output : outputs) if (output.kind() == Kind.BLUETOOTH && !earphones(output)) return true;
        return false;
    }
    /**
     * Whether the voice goes only where the microphone cannot hear it. A car or a Bluetooth speaker connected decides it:
     * the voice goes there, whatever else is plugged in. Otherwise any headset makes it private; the phone's own speaker
     * alone does not.
     */
    public static boolean privateOutput(List<Output> outputs) {
        if (loudBluetooth(outputs)) return false;
        for (Output output : outputs) if (output.kind() == Kind.WIRED || output.kind() == Kind.HEADSET || earphones(output)) return true;
        return false;
    }
    /** How long after the last sample the guard stays shut. */
    public static long echoTailMs(List<Output> outputs) { return loudBluetooth(outputs) ? BLUETOOTH_TAIL_MS : ROOM_TAIL_MS; }
}
