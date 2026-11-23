package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.AppNavigation;
import dev.jun.gyeot.core.AppGallery;
import dev.jun.gyeot.core.AppVideos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Main-thread screen requests; completion comes from the visible Activity, never startActivity alone. Each request is answered
 * exactly once. The screen starts where Android lets this app start one from the background, by DeviceActionRunner's routes (a
 * visible window of this app, the bound accessibility service, a visible overlay); otherwise, or when it is not seen in time,
 * a tap-to-open notification says that a tap is needed.
 */
final class AppNavigator {
    private static final String ACTION = "dev.jun.gyeot.OPEN_SCREEN", REQUEST = "gyeot.navigation_request", TAP = "gyeot.navigation_tap", TAP_TOKEN = "gyeot.navigation_tap_token";
    /** Only this process's own notifications carry it (their PendingIntent is immutable), so another app cannot forge a tap. */
    private static final String TOKEN = UUID.randomUUID().toString();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private record Pending(String screen, String imageId, String videoId, BooleanSupplier allowed, Consumer<JSONObject> reply, Runnable timeout) {}
    private static final Map<String, Pending> pending = new LinkedHashMap<>();
    private AppNavigator() {}
    static boolean isRequest(Intent intent) { return intent != null && ACTION.equals(intent.getAction()); }
    /** overlayVisible: this app's floating bubble is on screen now (an Android 15 background-start route). */
    static void open(Context context, String screen, boolean overlayVisible, BooleanSupplier allowed, Consumer<JSONObject> reply) {
        open(context, screen, "", "", overlayVisible, allowed, reply);
    }
    static void openImage(Context context, String imageId, boolean overlayVisible, BooleanSupplier allowed, Consumer<JSONObject> reply) {
        if (!dev.jun.gyeot.core.WorkBook.safeId(imageId)) { reply.accept(error("이미지 식별자가 올바르지 않아요.")); return; }
        open(context, "gallery", imageId, "", overlayVisible, allowed, reply);
    }
    static void openVideo(Context context, String videoId, boolean overlayVisible, BooleanSupplier allowed, Consumer<JSONObject> reply) {
        if (!dev.jun.gyeot.core.WorkBook.safeId(videoId)) { reply.accept(error("영상 식별자가 올바르지 않아요.")); return; }
        open(context, "gallery", "", videoId, overlayVisible, allowed, reply);
    }
    private static void open(Context context, String screen, String imageId, String videoId, boolean overlayVisible, BooleanSupplier allowed, Consumer<JSONObject> reply) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> open(context, screen, imageId, videoId, overlayVisible, allowed, reply)); return; }
        if (!AppNavigation.SCREENS.contains(screen)) { reply.accept(error("지원하지 않는 앱 화면이에요.")); return; }
        if (!allowed.getAsBoolean()) { reply.accept(error("취소된 화면 이동 요청이에요.")); return; }
        ArrayList<String> older = new ArrayList<>(pending.keySet());
        for (String old : older) complete(old, error("새 화면 이동 요청으로 이전 요청을 취소했어요."));
        String id = UUID.randomUUID().toString(), media = !imageId.isEmpty() ? "image" : !videoId.isEmpty() ? "video" : "";
        String[] route = {""};
        // Android can block a background start without a word: a screen started from the background and not seen in time gets
        // the tap-to-open notification. Started from this app's visible window it was only slow.
        Runnable timeout = () -> {
            Pending request = pending.get(id);
            if (request != null) complete(id, !request.allowed.getAsBoolean() ? error("취소된 화면 이동 요청이에요.")
                : "app".equals(route[0]) ? AppNavigation.unconfirmed(screen, media) : tapToOpen(context, screen, media, "unconfirmed"));
        };
        pending.put(id, new Pending(screen, imageId, videoId, allowed, reply, timeout));
        main.postDelayed(timeout, videoId.isEmpty() ? 6000 : 12000);
        Intent intent = new Intent(context, activity(screen)).setAction(ACTION).putExtra(REQUEST, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { route[0] = DeviceActionRunner.start(context, intent, overlayVisible); }
        catch (RuntimeException refused) { route[0] = "notification"; }
        // Started: the screen reports itself once it is visible. Otherwise Android would not start it now (locked, or in the
        // background with no route): a tap is needed, and the reply says so at once.
        if (!DeviceActionRunner.started(route[0]) && pending.containsKey(id)) complete(id, tapToOpen(context, screen, media, route[0]));
    }
    private static Class<?> activity(String screen) {
        return screen.equals("browser") ? BrowserActivity.class : screen.equals("phone") || screen.equals("files") ? FileExplorerActivity.class : screen.equals("pages") ? PagesActivity.class : screen.equals("mytube") ? MyTubeActivity.class : MainActivity.class;
    }
    /** The notification's tap opens the screen itself; a tap is the user's own action, which Android lets start it. */
    private static JSONObject tapToOpen(Context context, String screen, String media, String route) {
        Intent tap = new Intent(context, activity(screen)).setAction(ACTION).putExtra(TAP, screen).putExtra(TAP_TOKEN, TOKEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return AppNavigation.tapToOpen(screen, media, route, DeviceActionRunner.post(context, tap, AppNavigation.tapText(screen)));
    }
    /**
     * A voice request selects a screen only while it is live in this process, so stale or external intents cannot replay it.
     * A tap on this process's own notification (the user's own action) names only a screen, never an image or video.
     */
    static String destination(Intent intent) {
        if (!isRequest(intent)) return "";
        String tapped = intent.getStringExtra(TAP);
        if (tapped != null) return AppNavigation.SCREENS.contains(tapped) && TOKEN.equals(intent.getStringExtra(TAP_TOKEN)) && !intent.hasExtra(REQUEST) ? tapped : "";
        String id = intent.getStringExtra(REQUEST); Pending request = id == null ? null : pending.get(id);
        if (request == null) return "";
        if (!request.allowed.getAsBoolean()) { complete(id, error("취소된 화면 이동 요청이에요.")); return ""; }
        return request.screen;
    }
    /** A tap on this process's tap-to-open notification (it names only a screen). */
    static boolean tapped(Intent intent) { return isRequest(intent) && intent.hasExtra(TAP); }
    /** The app screen last in front (main thread): main, browser, phone, files or pages; it stays when the user leaves the app. */
    private static String front = "";
    static void inFront(String screen) { front = screen; }
    /** The page open on the 페이지 screen (null for its list), so opening the app again from its icon returns to it. */
    static String pageLeft;
    /**
     * Opened again from the app icon: singleTask brought MainActivity back alone, finishing the browser, 내 휴대폰 or 페이지 the
     * user left above it, which is then opened again. "" when the user left MainActivity, which keeps its own page.
     */
    static String leftAbove() { return front.equals("browser") || front.equals("phone") || front.equals("files") || front.equals("pages") || front.equals("board") || front.equals("school") || front.equals("lab") || front.equals("mytube") ? front : ""; }
    /** BrowserActivity is resumed in front (main thread): a bare spoken 'go back' then leaves it for MainActivity. */
    private static boolean browserFront;
    static void browserInFront(boolean front) { browserFront = front; }
    static boolean browserInFront() { return browserFront; }
    /** Browser chrome visibility, independent of whether any web page has loaded. */
    static void browserShown(android.app.Activity activity, boolean resumed) {
        if (!isRequest(activity.getIntent()) || !resumed || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()
                || !activity.getWindow().getDecorView().isShown()) return;
        shown(activity.getIntent(), 5);
    }
    /** actualTab: the page in front (MainActivity's tab or AppNavigation.CHAT, the browser's 5, FileExplorerActivity's 6 or 7, PagesActivity's 9). */
    static void shown(Intent intent, int actualTab) {
        String screen = destination(intent);
        if (screen.isEmpty() || !imageId(intent).isEmpty() || !videoId(intent).isEmpty() || !AppNavigation.shows(screen, actualTab)) return;
        complete(intent.getStringExtra(REQUEST), obj("ok", true, "verified", true, "screen", screen, "label", AppNavigation.label(screen),
            "handled_by", AppNavigation.FUNCTION, "note", screen.equals("browser") ? "내부 브라우저가 표시됐어요. browser_task/browser_control로 웹페이지를 읽고 조작하세요. 폰 화면 캡처나 접근성 조작은 필요 없어요." : "요청한 들퍼레 내부 화면이 앞에 표시됐어요."));
    }
    static String imageId(Intent intent) {
        if (destination(intent).isEmpty()) return "";
        Pending request = request(intent);
        return request == null ? "" : request.imageId;
    }
    /** Called only after the exact bitmap is decoded and its full-screen dialog has window focus. */
    static void imageShown(Intent intent, String imageId) {
        if (imageId.isEmpty() || !imageId.equals(imageId(intent))) return;
        complete(intent.getStringExtra(REQUEST), obj("ok", true, "verified", true, "screen", "gallery", "image_id", imageId,
            "handled_by", AppGallery.SHOW, "note", "요청한 이미지가 전체 화면에 표시됐어요."));
    }
    static void imageFailed(Intent intent, String reason) {
        if (!imageId(intent).isEmpty()) complete(intent.getStringExtra(REQUEST), error(reason));
    }
    static String videoId(Intent intent) {
        if (destination(intent).isEmpty()) return "";
        Pending request = request(intent);
        return request == null ? "" : request.videoId;
    }
    /** The player reports this only after a rendered frame, window focus and actual playback. */
    static void videoPlaying(Intent intent, String videoId) {
        if (videoId.isEmpty() || !videoId.equals(videoId(intent))) return;
        complete(intent.getStringExtra(REQUEST), obj("ok", true, "verified", true, "playing", true, "screen", "gallery", "video_id", videoId,
            "handled_by", AppVideos.PLAY, "note", "요청한 영상이 전체 화면에서 재생 중이에요."));
    }
    static void videoFailed(Intent intent, String reason) {
        if (!videoId(intent).isEmpty()) complete(intent.getStringExtra(REQUEST), error(reason));
    }
    private static Pending request(Intent intent) {
        String id = intent.getStringExtra(REQUEST);
        return id == null ? null : pending.get(id);
    }
    /** The one reply of a request: whichever of shown, failed, superseded, cancelled or timed out comes first. */
    private static void complete(String id, JSONObject result) {
        Pending request = id == null ? null : pending.remove(id);
        if (request != null) { main.removeCallbacks(request.timeout); request.reply.accept(result); }
    }
    private static JSONObject error(String text) { return obj("ok", false, "verified", false, "playing", false, "error", text); }
}
