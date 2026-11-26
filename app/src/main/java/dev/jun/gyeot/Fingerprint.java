package dev.jun.gyeot;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.widget.Toast;

/**
 * The phone's fingerprint before 퍼레's private memories (준: "개인정보 중 중요한 내용은 지문인식을 요청해줘", "갤럭시 지문인식"):
 * the system's own prompt, so a Galaxy shows its fingerprint sheet. A strong biometric (the fingerprint) when one is set;
 * a phone without one asks for its screen lock instead. ok runs on success, no on cancel or failure; on the main thread.
 */
final class Fingerprint {
    private Fingerprint() {}

    static void ask(Activity activity, String title, String subtitle, Runnable ok, Runnable no) {
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable yes = () -> { if (once.compareAndSet(false, true)) ok.run(); }, nope = () -> { if (once.compareAndSet(false, true)) no.run(); };
        try {
            BiometricManager bm = activity.getSystemService(BiometricManager.class);
            boolean strong = bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle);
            if (strong) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButton("취소", activity.getMainExecutor(), (d, w) -> nope.run());
            else b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            b.build().authenticate(new CancellationSignal(), activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { yes.run(); }
                @Override public void onAuthenticationError(int code, CharSequence message) { nope.run(); }
            });
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "지문 확인을 열지 못했어요", Toast.LENGTH_SHORT).show();
            nope.run();
        }
    }
}
