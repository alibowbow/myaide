package dev.jun.gyeot;

import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import dev.jun.gyeot.core.Triggers;

/**
 * 조건 알림's two jobs (TriggerWatch), each on its own thread and set again only while a trigger needs it:
 *  - PRICE_JOB: reads the prices the price triggers need (at least five minutes apart, with a network; Doze waits longer).
 *  - CHARGE_JOB: runs when charging starts (and every 15 minutes while it goes on) to read the battery for "충전기 꽂으면",
 *    "충전 다 되면" and "N%가 되면" when the app was not running; it also sets the live receivers while the app runs.
 */
public final class TriggerJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        int id = params.getJobId();
        android.content.Context app = getApplicationContext();
        try {
            TriggerWatch.IO.execute(() -> {
                boolean again = false;
                try {
                    if (id == TriggerWatch.PRICE_JOB) again = TriggerWatch.prices(app) > 0;
                    else if (id == TriggerWatch.CHARGE_JOB) {
                        TriggerWatch.receivers(app, TriggerStore.list(app));
                        TriggerWatch.handle(app, TriggerWatch.battery(app));
                        again = Triggers.needsCharging(TriggerStore.list(app));
                    }
                } catch (RuntimeException failed) { again = true; }
                jobFinished(params, false);
                // The next one only after this one is finished (setting the same job while it runs would stop it).
                JobScheduler jobs = app.getSystemService(JobScheduler.class);
                if (jobs != null && again) {
                    if (id == TriggerWatch.PRICE_JOB) TriggerWatch.prices(app, jobs, TriggerWatch.PRICE_MS);
                    else TriggerWatch.charging(app, jobs, TriggerWatch.CHARGE_MS);
                }
            });
        } catch (RuntimeException stopped) { return false; }
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
