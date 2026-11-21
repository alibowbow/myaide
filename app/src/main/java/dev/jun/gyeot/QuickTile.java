package dev.jun.gyeot;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import dev.jun.gyeot.core.Brand;

/**
 * Quick settings tile "들퍼레": one tap starts the voice assistant, another ends it. It goes through QuickVoiceActivity, because
 * only a visible activity lets the voice service use the microphone; on the lock screen it asks to unlock first.
 */
public final class QuickTile extends TileService {
    private final Runnable observer = this::show;
    private AppStore store;

    @Override public void onCreate() { super.onCreate(); store = ((GyeotApp) getApplication()).store(); }
    @Override public void onStartListening() { super.onStartListening(); store.observe(observer); show(); }
    @Override public void onStopListening() { store.unobserve(observer); super.onStopListening(); }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::launch); else launch();
    }
    @SuppressWarnings("deprecation")
    private void launch() {
        Intent intent = new Intent(this, QuickVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(intent);
    }
    private void show() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = store.voiceActive;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(on ? "대화 중 · 눌러서 종료" : "눌러서 대화");
        tile.setContentDescription(on ? Brand.NAME + " 음성 대화 중. 누르면 종료" : Brand.NAME + " 음성 대화 시작");
        tile.updateTile();
    }
}
