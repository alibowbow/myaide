package dev.jun.gyeot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** 오늘의 제안's morning job (Briefing.schedule): makes what is due on its own thread, then sets the next morning. */
public final class BriefingJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        AppStore store = ((GyeotApp) getApplication()).store();
        if (!Briefing.on(store)) return false;
        new Thread(() -> {
            try { Briefing.due(getApplicationContext(), store); } catch (RuntimeException quiet) { /* The next morning, or opening the app, tries again. */ }
            new Handler(Looper.getMainLooper()).post(() -> jobFinished(params, false));
        }, "briefing-job").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}
