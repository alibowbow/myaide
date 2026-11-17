package dev.jun.gyeot;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.mozilla.geckoview.*;

/** Prepares Gecko and the bridge without opening a tab or requesting a website. */
final class BrowserRuntime {
    private static BrowserRuntime instance;
    private static boolean warmupScheduled;
    final GeckoRuntime runtime;
    private GeckoResult<WebExtension> bridge;

    private BrowserRuntime(Context context) { runtime = GeckoRuntime.create(context.getApplicationContext()); }
    static BrowserRuntime get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser must run on the main thread");
        if (instance == null) instance = new BrowserRuntime(context);
        return instance;
    }
    GeckoResult<WebExtension> bridge() {
        if (bridge == null) bridge = runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/browser/", BrowserEngine.EXTENSION);
        return bridge;
    }
    static void prepareWhenIdle(Activity activity) {
        if (instance != null || warmupScheduled) return;
        warmupScheduled = true;
        // Let the app's first frame and foreground work start before optional preparation.
        new Handler(Looper.getMainLooper()).postDelayed(() -> Looper.myQueue().addIdleHandler(() -> {
            warmupScheduled = false;
            if (instance != null || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return false;
            ActivityManager memory = activity.getSystemService(ActivityManager.class);
            if ((memory != null && memory.isLowRamDevice()) || ((GyeotApp) activity.getApplicationContext()).store().voiceActive) return false;
            try {
                BrowserRuntime shared = get(activity);
                shared.runtime.warmUp();
                shared.bridge();
            } catch (RuntimeException | LinkageError ignored) { /* Optional; an explicit browser open still uses the normal path. */ }
            return false;
        }), 750);
    }
}
