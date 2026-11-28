package dev.jun.gyeot;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick settings tile "화면 녹화": a tap starts a recording through ScreenRecordActivity (Android's consent needs an
 * activity), another tap stops it. On the lock screen it asks to unlock first. While the shade is open it follows the
 * recording through the app's store (ScreenRecordService calls store.changed() on start, stop, saving and end), the way
 * QuickTile does: requestListeningState only works for active tiles, which this one is not.
 */
public final class ScreenRecordTile extends TileService {
    private final Runnable observer = this::show;
    private AppStore store;

    @Override public void onCreate() { super.onCreate(); store = ((GyeotApp) getApplication()).store(); }
    @Override public void onStartListening() { super.onStartListening(); store.observe(observer); show(); }
    @Override public void onStopListening() { store.unobserve(observer); super.onStopListening(); }
    @Override public void onClick() {
        super.onClick();
        if (ScreenRecordService.running()) { if (!ScreenRecordService.saving()) ScreenRecordService.stop(this); show(); return; }
        if (isLocked()) unlockAndRun(this::launch); else launch();
    }
    @SuppressWarnings("deprecation")
    private void launch() {
        Intent intent = new Intent(this, ScreenRecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(intent);
    }
    private void show() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = ScreenRecordService.running(), saving = on && ScreenRecordService.saving(), paused = on && ScreenRecordService.paused();
        tile.setState(on && !saving ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(saving ? "저장하는 중" : paused ? "일시정지 · 눌러서 중지" : on ? "녹화 중 · 눌러서 중지" : "눌러서 녹화");
        tile.setContentDescription(saving ? "화면 녹화를 저장하는 중" : paused ? "화면 녹화 일시정지. 누르면 중지" : on ? "화면 녹화 중. 누르면 중지" : "화면 녹화 시작");
        tile.updateTile();
    }
}
