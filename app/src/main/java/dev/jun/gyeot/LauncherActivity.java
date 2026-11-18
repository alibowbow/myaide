package dev.jun.gyeot;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** A plain icon launch resumes the board task before entering the singleTask main/voice screen. */
public final class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ActivityManager manager = getSystemService(ActivityManager.class);
        ComponentName board = new ComponentName(this, BoardActivity.class);
        if (manager != null) {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                try {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info != null && board.equals(info.topActivity)) {
                        task.moveToFront();
                        finish();
                        return;
                    }
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
}
