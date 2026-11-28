package dev.jun.gyeot.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 캡쳐 (준: "캡쳐도 만들어줘"): which way takes the screen, without Android's consent prompt whenever one can. Pure Java.
 * <ul>
 * <li>SERVICE: 들퍼레 폰 조작 (the accessibility service) takes it itself (takeScreenshot, Android 11+): no prompt, and 들퍼레
 *     saves it in Pictures/들퍼레.</li>
 * <li>SYSTEM: the same service presses the phone's own screenshot (GLOBAL_ACTION_TAKE_SCREENSHOT) when its own take failed;
 *     the phone saves it in its Screenshots and shows its own thumbnail.</li>
 * <li>RECORDING: a 화면 녹화 is on: one frame from its own capture, so no second consent ends the recording.</li>
 * <li>CONSENT: none of these: Android's consent once, then one frame of a screen capture.</li>
 * </ul>
 * The service is not used while 보안 점검 paused it (held).
 */
public final class CapturePlan {
    private CapturePlan() {}
    public enum Way { SERVICE, SYSTEM, RECORDING, CONSENT }

    /** The ways to try, in order: each next one when the one before failed. */
    public static List<Way> ways(boolean service, boolean held, int sdk, boolean recording) {
        List<Way> out = new ArrayList<>();
        if (service && !held) { if (sdk >= 30) out.add(Way.SERVICE); out.add(Way.SYSTEM); }
        out.add(recording ? Way.RECORDING : Way.CONSENT);
        return out;
    }
    /** How long the home screen's satellite or the recording's controls get to leave the screen before it is taken. */
    public static final long SETTLE_MS = 350;
    /** Where 들퍼레's own captures go, under the phone's shared storage. */
    public static final String FOLDER = "Pictures/" + Brand.NAME;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);
    /** "들퍼레_캡쳐_20261128_101500.png". */
    public static String fileName(LocalDateTime at) { return Brand.NAME + "_캡쳐_" + STAMP.format(at) + ".png"; }
}
