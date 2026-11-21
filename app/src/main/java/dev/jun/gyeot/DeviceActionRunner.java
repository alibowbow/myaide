package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.DeviceActions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * Fires a DeviceActions description. The torch and media keys need no activity and run at once. An
 * activity starts only where Android allows it from the background: from a visible app window, from the bound accessibility
 * service, or with a visible overlay; otherwise a tap-to-open notification carries the same intent. Never from AssistantService.
 * AppNavigator starts this app's own screens through the same routes and notification.
 */
final class DeviceActionRunner {
    static final String CHANNEL = "results";
    /** Tap-to-open notifications use ids NOTIFICATION to NOTIFICATION + SLOTS - 1, clear of the app's other ids (10 to 3823). */
    static final int NOTIFICATION = 4110, SLOTS = 32;
    private static int nextSlot;
    private DeviceActionRunner() {}

    /** overlayVisible: this app's floating bubble is on screen now. */
    static JSONObject run(Context context, DeviceActions.Action action, boolean overlayVisible) {
        if (SecurityWatch.paused(context)) return dev.jun.gyeot.core.Jsons.obj("ok",false,"error",SecurityWatch.PAUSE_MESSAGE);
        if (DeviceActions.TORCH.equals(action.tool)) return torch(context, action);
        if (DeviceActions.MEDIA.equals(action.tool)) return media(context, action);
        PackageManager packages = context.getPackageManager();
        DeviceActions.Launch chosen = null; Intent intent = null;
        for (DeviceActions.Launch launch : action.launches) {
            Intent candidate = intent(launch);
            if (!pin(packages, candidate)) continue;
            chosen = launch; intent = candidate; break;
        }
        if (chosen == null) return DeviceActions.noApp(action);
        // An alarm or timer goes to the clock app only after a heard yes; until then the result is the read-back question.
        if (action.needsConfirmation()) return DeviceActions.ask(action);
        String route;
        try { route = start(context, intent, overlayVisible); }
        catch (ActivityNotFoundException gone) { return DeviceActions.noApp(action); }
        return started(route) ? DeviceActions.result(action, chosen, route) : notify(context, action, chosen, route);
    }
    /**
     * Starts an activity the way Android allows from the background right now (DeviceActions.route): from a visible window of
     * this app, from the bound accessibility service, or with the overlay permission (from Android 15 only while the bubble is
     * visible). Returns the route; "locked" and "notification" mean it did not start, and the caller posts its tap-to-open
     * notification instead. System intents and this app's own screens (AppNavigator) start only here.
     */
    static String start(Context context, Intent intent, boolean overlayVisible) {
        GyeotApp app = context.getApplicationContext() instanceof GyeotApp own ? own : null;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        String route = DeviceActions.route(keyguard != null && keyguard.isKeyguardLocked(), app != null && (app.store().appVisible || app.activityVisible()),
            PhoneService.connected(), Settings.canDrawOverlays(context), overlayVisible, Build.VERSION.SDK_INT);
        try {
            if ("accessibility".equals(route) && PhoneService.launch(intent)) return route;
            if ("app".equals(route) || "overlay".equals(route)) {
                context.getApplicationContext().startActivity(intent);
                return route;
            }
        } catch (ActivityNotFoundException gone) { throw gone; }
        catch (RuntimeException refused) { /* Android refused the start: the notification still reaches the user. */ }
        return "locked".equals(route) ? route : "notification";
    }
    static boolean started(String route) { return "app".equals(route) || "accessibility".equals(route) || "overlay".equals(route); }

    /** The intent a launch describes. Only the fixed actions, URIs and extras DeviceActions builds; never parsed from text. */
    static Intent intent(DeviceActions.Launch launch) {
        Intent intent = new Intent(launch.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!launch.data.isEmpty()) intent.setData(Uri.parse(launch.data));
        for (Map.Entry<String, Object> extra : launch.extras.entrySet()) {
            Object value = extra.getValue();
            if (value instanceof Integer number) intent.putExtra(extra.getKey(), number.intValue());
            else if (value instanceof Boolean flag) intent.putExtra(extra.getKey(), flag.booleanValue());
            else if (value instanceof String text) intent.putExtra(extra.getKey(), text);
            else if (value instanceof List<?> list) {
                ArrayList<Integer> numbers = new ArrayList<>();
                for (Object item : list) numbers.add((Integer) item);
                intent.putIntegerArrayListExtra(extra.getKey(), numbers);
            }
        }
        return intent;
    }
    /**
     * Whether an app handles the intent. The handling app is pinned, so the start and a notification go only to it; when several
     * apps handle it and none is the default, it stays open for Android's chooser.
     */
    static boolean pin(PackageManager packages, Intent intent) {
        List<ResolveInfo> handlers = packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (handlers.isEmpty()) return false;
        ResolveInfo preferred = packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        String chosen = handlers.size() == 1 && handlers.get(0).activityInfo != null ? handlers.get(0).activityInfo.packageName : null;
        if (preferred != null && preferred.activityInfo != null)
            for (ResolveInfo handler : handlers)
                if (handler.activityInfo != null && handler.activityInfo.packageName.equals(preferred.activityInfo.packageName)) chosen = handler.activityInfo.packageName;
        if (chosen != null) intent.setPackage(chosen);
        return true;
    }
    private static JSONObject notify(Context context, DeviceActions.Action action, DeviceActions.Launch launch, String route) {
        DeviceActions.Launch shown = launch.forTap();
        Intent tap = intent(shown);
        pin(context.getPackageManager(), tap);
        return post(context, tap, DeviceActions.notificationText(action, shown)) ? DeviceActions.notified(action, shown, route) : DeviceActions.blocked(action);
    }
    /** A tap-to-open notification on the 작업 결과 channel for an intent that could not start now; false when notifications are off. */
    static boolean post(Context context, Intent tap, String text) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) return false;
        if (notifications.getNotificationChannel(CHANNEL) == null) notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "작업 결과", NotificationManager.IMPORTANCE_DEFAULT));
        // A tap is the user's own action, which Android lets open the screen. Its own id and request code keep it and its
        // extras apart from another fallback that the user has not tapped yet.
        int id = slot(notifications);
        PendingIntent open = PendingIntent.getActivity(context, id, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Brand.NAME).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build();
        try { notifications.notify(id, notification); }
        catch (SecurityException off) { return false; }
        return true;
    }
    /** The next tap-to-open id that no shown notification holds; when all are shown, the next one in turn is reused. */
    static int slot(NotificationManager notifications) {
        Set<Integer> shown = new HashSet<>();
        for (StatusBarNotification active : notifications.getActiveNotifications()) shown.add(active.getId());
        int id = NOTIFICATION + nextSlot;
        for (int i = 1; i <= SLOTS && shown.contains(id); i++) id = NOTIFICATION + (nextSlot + i) % SLOTS;
        nextSlot = (id - NOTIFICATION + 1) % SLOTS;
        return id;
    }
    /** The back camera's flash; turning it on needs no permission. */
    private static JSONObject torch(Context context, DeviceActions.Action action) {
        CameraManager cameras = context.getSystemService(CameraManager.class);
        if (cameras == null) return DeviceActions.failed(action, "이 폰에서는 손전등을 쓸 수 없어요.");
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics camera = cameras.getCameraCharacteristics(id);
                Integer facing = camera.get(CameraCharacteristics.LENS_FACING);
                if (!Boolean.TRUE.equals(camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) || facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                cameras.setTorchMode(id, action.torchOn);
                return DeviceActions.result(action, null, "direct");
            }
            return DeviceActions.failed(action, "이 폰에는 손전등으로 쓸 뒤쪽 플래시가 없어요.");
        } catch (CameraAccessException | IllegalArgumentException busy) {
            return DeviceActions.failed(action, "카메라를 쓰는 중이라 손전등을 " + (action.torchOn ? "켤" : "끌") + " 수 없어요.");
        }
    }
    /** A media button press (down, then up), as a headset sends it, to whichever app the system routes media keys to. */
    private static JSONObject media(Context context, DeviceActions.Action action) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return DeviceActions.failed(action, "미디어 신호를 보낼 수 없어요.");
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, action.mediaKey, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, action.mediaKey, 0));
        return DeviceActions.result(action, null, "direct");
    }
}
