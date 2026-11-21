package dev.jun.gyeot;

import android.app.job.*;
import android.os.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SecurityJob extends JobService {
    private final ConcurrentHashMap<Integer,AtomicBoolean> active=new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        if (!SecurityWatch.enabled(this)) return false;
        AtomicBoolean cancelled=new AtomicBoolean();
        active.put(params.getJobId(),cancelled);
        new Thread(() -> {
            boolean retry=false;
            try {
                var apps=PhoneCheck.userApps(this);
                var device=PhoneCheck.device(this);
                if (!cancelled.get() && SecurityWatch.enabled(this)) {
                    int changes=SecurityWatch.scan(this,apps,device);
                    if (changes>0 && !cancelled.get() && SecurityWatch.enabled(this)) SecurityWatch.notifyChanges(this,changes);
                }
            } catch (Exception unavailable) { retry=true; }
            boolean reschedule=retry;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (active.remove(params.getJobId(),cancelled)) jobFinished(params,reschedule);
            });
        },"phone-security-check").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean token=active.remove(params.getJobId()); if (token!=null) token.set(true);
        return SecurityWatch.enabled(this);
    }
}
