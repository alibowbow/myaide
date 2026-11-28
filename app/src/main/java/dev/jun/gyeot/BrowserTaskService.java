package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.HashSet;
import java.util.Set;

/** Keeps an explicitly requested browser job alive when its Activity is hidden. Never restarts an interrupted action. */
public final class BrowserTaskService extends Service {
    private static final String CHANNEL = "browser_work", RESULTS = "browser_results", STOP = "browser.stop";
    private static final int NOTIFICATION = 430, RESULT = 431;
    private static boolean stoppingService;
    private PowerManager.WakeLock wake;
    // Only jobs actually held by this service instance may be stopped by its
    // destruction. A delayed old onDestroy must not cancel a newly started job.
    private final Set<Long> heldLeases = new HashSet<>();
    static final String TASK_LEASE_EXTRA = "browser_task_lease";
    static final String TASK_PROCESS_EXTRA = "browser_task_process";
    private static final String PROCESS_ID = java.util.UUID.randomUUID().toString();
    static boolean currentTaskIntent(Intent intent) {
        return intent != null && PROCESS_ID.equals(intent.getStringExtra(TASK_PROCESS_EXTRA));
    }
    static void hold(Context context, long lease) {
        context.startForegroundService(new Intent(context, BrowserTaskService.class)
            .putExtra("lease", lease).putExtra(TASK_PROCESS_EXTRA, PROCESS_ID));
    }
    static void release(Context context, long lease, String status, boolean needsUser, boolean anyRunning) {
        BrowserEngine engine = BrowserEngine.get(context);
        if (stoppingService) {
            // Timeout/destruction is already releasing foreground ownership.
            // A lane's cancellation callback must not restart that service.
        } else if (anyRunning || engine.anyRunning()) {
            long remaining = engine.activeTaskLease();
            if (remaining >= 0) hold(context, remaining);
        } else context.stopService(new Intent(context, BrowserTaskService.class));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(RESULTS, "브라우저 작업 결과", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, RESULT, new Intent(context, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(RESULT, new Notification.Builder(context, RESULTS).setSmallIcon(R.drawable.ic_notification).setContentTitle(needsUser ? "브라우저에 확인이 필요해요" : "브라우저 작업 결과")
            .setContentText(status).setStyle(new Notification.BigTextStyle().bigText(status)).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build());
    }
    /** The browser opened on the earlier Work step's review (BrowserActivity.REVIEW_WORK). */
    private static final int REVIEW = 0x42e;
    private static final java.util.Set<String> askedReview = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * A Work job was stopped by its own earlier step whose result is unknown (other Work jobs go on): one notification for that
     * step, whose tap opens that step's review at once (the user checks the site and lifts the block there; nothing is
     * replayed). Once per step.
     */
    static void askWorkReview(Context context, String receipt, String site, String action) {
        if (receipt == null || receipt.isBlank() || !askedReview.add(receipt)) return;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(RESULTS, "브라우저 작업 결과", NotificationManager.IMPORTANCE_DEFAULT));
            Intent review = new Intent(context, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(BrowserActivity.REVIEW_WORK, true).putExtra(BrowserActivity.REVIEW_RECEIPT, receipt);
            PendingIntent open = PendingIntent.getActivity(context, REVIEW, review, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text = site + "에서 한 '" + action + "'의 결과가 확인되지 않아 그 작업의 클릭·입력만 멈췄어요(다른 작업은 그대로 해요). 사이트에서 실제 결과를 본 뒤 눌러서 확인하면 그 작업도 이어서 할 수 있어요.";
            manager.notify(REVIEW, new Notification.Builder(context, RESULTS).setSmallIcon(R.drawable.ic_notification).setContentTitle("이전 브라우저 작업 확인이 필요해요")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build());
        } catch (RuntimeException unavailable) { /* The answer in the chat still says where to look. */ }
    }
    /** The browser opened from a site's question (siteQuestion): the tab that asks comes to the front and shows it. */
    static final String QUESTION_TAB_EXTRA = "browser_question_tab";
    /**
     * A site asked something (a confirm, a leave-page question, a sign-in …) in a tab out of sight while the AI worked there:
     * the AI stopped. One tap opens that tab, where the site's question waits to be answered. It replaces the task's result
     * notification, which says the same less exactly.
     */
    static void siteQuestion(Context context, String tabId, String question) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(RESULTS, "브라우저 작업 결과", NotificationManager.IMPORTANCE_DEFAULT));
            Intent answer = new Intent(context, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra(QUESTION_TAB_EXTRA, tabId);
            PendingIntent open = PendingIntent.getActivity(context, RESULT, answer, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text = question + " · AI는 답하지 않고 멈췄어요. 눌러서 직접 답해 주세요.";
            manager.notify(RESULT, new Notification.Builder(context, RESULTS).setSmallIcon(R.drawable.ic_notification).setContentTitle("웹사이트가 확인을 물어요")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build());
        } catch (RuntimeException unavailable) { /* the browser's line and the task's answer still say it */ }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        BrowserEngine engine = BrowserEngine.get(this);
        long requested = intent == null ? -1 : intent.getLongExtra("lease", -1);
        if (intent != null && STOP.equals(intent.getAction())) {
            // The notification owns the captured run, not whichever tab happens
            // to be selected when Android eventually delivers this Intent.
            if (currentTaskIntent(intent) && engine.running(requested)) engine.stopTask(requested, "알림에서 이 탭의 브라우저 작업을 중지했어요");
            requested = -1;
        }
        long lease = engine.running(requested) ? requested : engine.activeTaskLease();
        if (lease < 0) { stopSelfResult(startId); return START_NOT_STICKY; }
        heldLeases.removeIf(value -> !engine.running(value));
        heldLeases.add(lease);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "브라우저 작업 진행", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION,
            new Intent(this, BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .setIdentifier("browser-task-open-" + PROCESS_ID + "-" + lease)
                .putExtra(TASK_LEASE_EXTRA, lease).putExtra(TASK_PROCESS_EXTRA, PROCESS_ID),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION,
            new Intent(this, BrowserTaskService.class).setAction(STOP)
                .setIdentifier("browser-task-" + PROCESS_ID + "-" + lease)
                .putExtra("lease", lease).putExtra(TASK_PROCESS_EXTRA, PROCESS_ID),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(NOTIFICATION, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("들퍼레가 브라우저에서 작업 중이에요")
            .setContentText(notificationTarget(engine, lease)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null, "이 작업 중지", stop).build()).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        keepAwake();
        return START_NOT_STICKY;
    }
    /** The wake lock is taken for WAKE_MS and renewed every RENEW_MS while any task runs, so a long task never loses it. */
    private static final long WAKE_MS = 5 * 60_000L, RENEW_MS = 4 * 60_000L;
    private final Handler renewer = new Handler(Looper.getMainLooper());
    private final Runnable renew = this::keepAwake;
    /** Holds the wake lock while a browser task runs (renewing it), and lets it go once none does. */
    private void keepAwake() {
        renewer.removeCallbacks(renew);
        if (!BrowserEngine.get(this).anyRunning()) { if (wake != null && wake.isHeld()) wake.release(); return; }
        if (wake == null) { wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gyeot:browser"); wake.setReferenceCounted(false); }
        wake.acquire(WAKE_MS);
        renewer.postDelayed(renew, RENEW_MS);
    }
    private static String notificationTarget(BrowserEngine engine, long lease) {
        BrowserEngine.Tab target = engine.tabForLease(lease);
        if (target == null) return "브라우저 작업 · 눌러서 확인";
        String title = target.title == null ? "" : target.title.replaceAll("[\\r\\n\\t]", " ").strip();
        if (title.length() > 80) title = title.substring(0, 80);
        return "작업 탭: " + (title.isEmpty() ? "새 탭" : title);
    }
    @Override public void onTimeout(int startId, int fgsType) {
        stoppingService = true;
        try { BrowserEngine.get(this).stopAll("시스템의 백그라운드 작업 제한으로 멈췄어요"); }
        finally { stoppingService = false; stopSelf(); }
    }
    @Override public void onDestroy() {
        renewer.removeCallbacks(renew);
        if (wake != null && wake.isHeld()) wake.release();
        BrowserEngine engine = BrowserEngine.get(this);
        stoppingService = true;
        try { for (long lease : new HashSet<>(heldLeases)) if (engine.running(lease)) {
            try { engine.stopTask(lease, "브라우저 서비스가 종료돼 작업을 멈췄어요"); }
            catch (RuntimeException ignored) { /* One failed consumer must not retain another lane. */ }
        } }
        finally { stoppingService = false; heldLeases.clear(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
    }
}
