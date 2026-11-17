package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.GeminiProtocol;

/**
 * "곁 바로 대화": a see-through entry (app shortcut, quick settings tile, edge button, assist gesture) that starts the voice
 * assistant, or ends voice when it is already on. It stays (invisibly) on screen until the service holds the
 * microphone, because Android grants a foreground service the microphone only while the app is visible. Without a key or
 * microphone permission it opens 곁, which asks for what is missing.
 */
public final class QuickVoiceActivity extends Activity {
    /** Long enough for the service to start; if voice fails, the error shows in 곁's notification. */
    private static final long WAIT_MS = 4000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable observer = this::check;
    private AppStore store;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Invisible and see-through to touches, so the app underneath keeps working while voice starts.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        store = ((GyeotApp) getApplication()).store();
        if (state != null) { close(); return; }
        boolean ready = store.secrets.hasKey() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            if (!ready) { startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.START_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); close(); return; }
            Intent intent = new Intent(this, AssistantService.class);
            if (store.voiceActive) { startService(intent.setAction(AssistantService.END_LIVE)); close(); return; }
            intent.setAction(AssistantService.LIVE).putExtra("mode", GeminiProtocol.ASSISTANT);
            if (store.serviceActive) startService(intent); else startForegroundService(intent);
        } catch (Exception e) { store.error(Brand.EUL + " 시작하지 못했어요: " + e.getClass().getSimpleName()); close(); return; }
        store.observe(observer);
        main.postDelayed(this::close, WAIT_MS);
    }
    private void check() { if (store.voiceActive) close(); }
    private void close() {
        if (isFinishing()) return;
        finish();
    }
    @Override protected void onDestroy() { store.unobserve(observer); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
