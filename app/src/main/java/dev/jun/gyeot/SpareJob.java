package dev.jun.gyeot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 남는 한도로 한 일's job (SpareWork.schedule): in the window before the free day starts again, while charging on Wi-Fi with
 * the screen off, runs SpareWork on its own thread, then sets the next one: this window again when it was stopped or the phone
 * was not ready, the next day's when it got through. Stopping the job stops the work between requests.
 */
public final class SpareJob extends JobService {
    private final AtomicBoolean stopped = new AtomicBoolean();
    @Override public boolean onStartJob(JobParameters params) {
        AppStore store = ((GyeotApp) getApplication()).store();
        stopped.set(false);
        new Thread(() -> {
            SpareWork.Outcome ended = SpareWork.Outcome.AGAIN;
            try { ended = SpareWork.run(getApplicationContext(), store, stopped::get); }
            catch (RuntimeException quiet) { /* Tried again later in the window. */ }
            finally {
                SpareWork.schedule(getApplicationContext(), store, ended == SpareWork.Outcome.THROUGH, ended == SpareWork.Outcome.AGAIN ? SpareWork.RETRY : 0);
                new Handler(Looper.getMainLooper()).post(() -> jobFinished(params, false));
            }
        }, "spare-work").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { stopped.set(true); return false; }
}
