package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class GyeotApp extends Application {
    private AppStore store;
    private BoardJobs boardJobs;
    BoardJobs boardJobs() {
        if (boardJobs == null) boardJobs = new BoardJobs(this);
        return boardJobs;
    }
    private int startedActivities;
    @Override public void onCreate() {
        super.onCreate();
        // Gecko also starts Android child processes. They must not restore/cancel the main app's work journal.
        if (getPackageName().equals(Application.getProcessName())) {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override public void onActivityStarted(Activity activity) { startedActivities++; }
                @Override public void onActivityStopped(Activity activity) { if (startedActivities > 0) startedActivities--; }
                @Override public void onActivityCreated(Activity activity, Bundle saved) {}
                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
            DiagnosticsExport.installCrashRecorder(this);
            store = new AppStore(this);
            // How Android ended the previous process; a system kill during a call or task gets one notice.
            String exit = DiagnosticsExport.readExits(this);
            if (exit != null) store.exitNotice = exit;
            // Alarms do not survive a phone restart and nothing listens for boot: the 예약 작업 alarm comes back here.
            WorkScheduleAlarm.refresh(this, store);
            // Pictures and videos saved by an older version were never registered with Android's media index; do it once, off the main thread.
            AppStore saved = store; Thread index = new Thread(() -> MediaIndex.registerExisting(this, saved), "media-index"); index.setDaemon(true); index.start();
        }
    }
    /** Any of this app's activities is started, not only MainActivity: the app has a visible window. */
    boolean activityVisible() { return startedActivities > 0; }
    public AppStore store() {
        if (store == null) throw new IllegalStateException("AppStore is only available in the main process");
        return store;
    }
}

