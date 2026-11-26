package dev.jun.gyeot;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/**
 * A plain icon tap brings the app back as the user left it: its task comes to the front as it is, so the screen on top
 * (트레이딩 or another project, a page, the browser, the board …) stays open with what it showed (준: "트레이딩 페이지에서 다른
 * 앱 보다가 다시 들어가면 그 페이지로 열리게", "항상 닫혀버려"). Starting the singleTask MainActivity instead finished every
 * screen above it, and only some of them were opened again (a project never was). MainActivity is started when there is no
 * task to bring back, when it is itself on top, or when 열면 바로 음성 should start a call there.
 */
public final class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ActivityManager manager = getSystemService(ActivityManager.class);
        ComponentName main = new ComponentName(this, MainActivity.class);
        ComponentName home = new ComponentName(this, HomeActivity.class);
        if (manager != null && !voiceFirst()) {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    // This trampoline's own task, and tasks with nothing left in them, are not the app. Nor is 들퍼레 홈 화면's
                    // own task (HomeActivity): bringing it forward from itself showed nothing (준: "들퍼레 앱 실행이 안돼").
                    if (info == null || info.taskId == getTaskId() || info.topActivity == null || info.numActivities <= 0) continue;
                    if (home.equals(info.topActivity) || home.equals(info.baseActivity)) continue;
                    if (main.equals(info.topActivity)) break;
                    task.moveToFront();
                    finish();
                    return;
                } catch (IllegalArgumentException | SecurityException removed) {
                    // The task may have been removed while the launcher was opening.
                }
            }
        }
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        finish();
    }
    /** 열면 바로 음성 is on and no call is going: the icon opens the voice screen and starts one, as it always did. */
    private boolean voiceFirst() {
        try { AppStore store = ((GyeotApp) getApplication()).store(); return store.voiceOnOpen() && !store.voiceActive; }
        catch (RuntimeException unreadable) { return false; }
    }
}
